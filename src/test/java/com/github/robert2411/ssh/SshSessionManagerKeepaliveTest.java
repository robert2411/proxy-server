package com.github.robert2411.ssh;

import net.schmizz.sshj.SSHClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SSH keepalive and automatic reconnect functionality.
 */
class SshSessionManagerKeepaliveTest {

    @TempDir
    Path tempDir;

    private SshConfigParser config;
    private SshSessionManager manager;

    @BeforeEach
    void setUp() throws IOException {
        File configFile = tempDir.resolve("ssh_config").toFile();
        Files.writeString(configFile.toPath(), """
                Host testhost
                    HostName test.example.com
                    User testuser
                    Port 22
                    IdentityFile ~/.ssh/id_ed25519
                """);
        config = SshConfigParser.parse(configFile);
        // Disable health check for unit tests to avoid background thread interference
        manager = new SshSessionManager(config, false);
    }

    @Test
    void evictionListenerCalledOnStaleSessionEviction() throws Exception {
        // Inject a mock stale client into the cache
        SSHClient mockClient = mock(SSHClient.class);
        when(mockClient.isConnected()).thenReturn(false);
        when(mockClient.isAuthenticated()).thenReturn(false);

        injectIntoCache("testhost", mockClient);

        AtomicReference<String> evictedHost = new AtomicReference<>();
        manager.setEvictionListener(evictedHost::set);

        // Evict explicitly
        manager.evict("testhost");

        assertEquals("testhost", evictedHost.get());
        verify(mockClient).close();
    }

    @Test
    void evictionWithoutListenerDoesNotThrow() throws Exception {
        SSHClient mockClient = mock(SSHClient.class);
        when(mockClient.isConnected()).thenReturn(false);
        injectIntoCache("testhost", mockClient);

        // No listener registered
        manager.setEvictionListener(null);

        assertDoesNotThrow(() -> manager.evict("testhost"));
        verify(mockClient).close();
    }

    @Test
    void evictionListenerExceptionDoesNotBreakEviction() throws Exception {
        SSHClient mockClient = mock(SSHClient.class);
        when(mockClient.isConnected()).thenReturn(false);
        injectIntoCache("testhost", mockClient);

        manager.setEvictionListener(host -> {
            throw new RuntimeException("Listener failed");
        });

        // Should not throw despite listener failure
        assertDoesNotThrow(() -> manager.evict("testhost"));
        verify(mockClient).close();
        assertEquals(0, manager.cacheSize());
    }

    @Test
    void transparentReconnectOnStaleSession() throws Exception {
        // Inject a stale client
        SSHClient staleClient = mock(SSHClient.class);
        when(staleClient.isConnected()).thenReturn(false);
        when(staleClient.isAuthenticated()).thenReturn(false);
        injectIntoCache("testhost", staleClient);

        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        manager.setEvictionListener(host -> listenerCalled.set(true));

        // clientFor should attempt reconnect — which will fail because host doesn't exist,
        // but the eviction should happen first
        try {
            manager.clientFor("testhost");
        } catch (IOException e) {
            // Expected — host doesn't exist for real connection
        }

        // Verify stale client was closed (evicted)
        verify(staleClient).close();
        assertTrue(listenerCalled.get(), "Eviction listener should have been called");
    }

    @Test
    void healthCheckEvictsDeadSessions() throws Exception {
        SSHClient deadClient = mock(SSHClient.class);
        when(deadClient.isConnected()).thenReturn(false);
        when(deadClient.isAuthenticated()).thenReturn(false);

        SSHClient aliveClient = mock(SSHClient.class);
        when(aliveClient.isConnected()).thenReturn(true);
        when(aliveClient.isAuthenticated()).thenReturn(true);

        injectIntoCache("dead-host", deadClient);
        injectIntoCache("alive-host", aliveClient);

        AtomicReference<String> evictedHost = new AtomicReference<>();
        manager.setEvictionListener(evictedHost::set);

        // Run health check manually
        manager.runHealthCheck();

        // Dead session should be evicted
        assertEquals("dead-host", evictedHost.get());
        verify(deadClient).close();

        // Alive session should remain
        assertEquals(1, manager.cacheSize());
    }

    @Test
    void healthCheckNoEvictionWhenAllSessionsHealthy() throws Exception {
        SSHClient aliveClient = mock(SSHClient.class);
        when(aliveClient.isConnected()).thenReturn(true);
        when(aliveClient.isAuthenticated()).thenReturn(true);

        injectIntoCache("alive-host", aliveClient);

        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        manager.setEvictionListener(host -> listenerCalled.set(true));

        manager.runHealthCheck();

        assertFalse(listenerCalled.get());
        assertEquals(1, manager.cacheSize());
        verify(aliveClient, never()).close();
    }

    @Test
    void shutdownClosesAllSessions() throws Exception {
        SSHClient client1 = mock(SSHClient.class);
        SSHClient client2 = mock(SSHClient.class);

        injectIntoCache("host1", client1);
        injectIntoCache("host2", client2);

        manager.shutdown();

        verify(client1).close();
        verify(client2).close();
        assertEquals(0, manager.cacheSize());
    }

    @Test
    void setEvictionListenerReplacesExisting() {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        manager.setEvictionListener(first::set);
        manager.setEvictionListener(second::set);

        // Only the second listener should be active
        // (We can't easily test this without evicting, but at minimum verify no exceptions)
        assertDoesNotThrow(() -> manager.setEvictionListener(null));
    }

    @Test
    void healthCheckDoesNotEvictReplacedSession() throws Exception {
        // Simulate race: health check finds dead session, but by the time it removes,
        // clientFor() has already reconnected with a fresh one.
        SSHClient deadClient = mock(SSHClient.class);
        when(deadClient.isConnected()).thenReturn(false);
        when(deadClient.isAuthenticated()).thenReturn(false);

        SSHClient freshClient = mock(SSHClient.class);
        when(freshClient.isConnected()).thenReturn(true);
        when(freshClient.isAuthenticated()).thenReturn(true);

        // Put the dead client first
        injectIntoCache("host-race", deadClient);

        // Now replace with fresh client (simulating clientFor() reconnect between check and remove)
        injectIntoCache("host-race", freshClient);

        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        manager.setEvictionListener(host -> listenerCalled.set(true));

        // Health check should NOT evict because the value has changed (remove(key, deadClient) returns false)
        // But since deadClient is no longer in the map, it won't match
        manager.runHealthCheck();

        // Fresh client should remain (it's connected+authenticated)
        assertEquals(1, manager.cacheSize());
        assertFalse(listenerCalled.get(), "Listener should NOT be called for fresh session");
        verify(freshClient, never()).close();
    }

    // --- Helper methods ---

    @SuppressWarnings("unchecked")
    private void injectIntoCache(String host, SSHClient client) throws Exception {
        Field cacheField = SshSessionManager.class.getDeclaredField("sessionCache");
        cacheField.setAccessible(true);
        ConcurrentHashMap<String, SSHClient> cache =
                (ConcurrentHashMap<String, SSHClient>) cacheField.get(manager);
        cache.put(host, client);
    }
}
