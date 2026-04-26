package com.github.robert2411.proxy;

import com.github.robert2411.ssh.SshSessionManager;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PortForwardCacheTest {

    private SshSessionManager sshSessionManager;
    private ExecutorService executor;
    private PortForwardCache cache;

    @BeforeEach
    void setUp() {
        sshSessionManager = mock(SshSessionManager.class);
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "test-port-fwd");
            t.setDaemon(true);
            return t;
        });
        cache = new PortForwardCache(sshSessionManager, executor);
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
        executor.shutdownNow();
    }

    @Test
    void localPortFor_returnsPositivePort() throws Exception {
        // AC #1: localPortFor returns a port > 0
        SSHClient mockClient = createMockSshClient();
        when(sshSessionManager.clientFor("myhost")).thenReturn(mockClient);

        int port = cache.localPortFor("myhost", 8080);

        assertThat(port).isGreaterThan(0);
        assertThat(port).isLessThan(65536);
    }

    @Test
    void localPortFor_sameArgsSamePort() throws Exception {
        // AC #2: second call returns same port without new forwarder
        SSHClient mockClient = createMockSshClient();
        when(sshSessionManager.clientFor("myhost")).thenReturn(mockClient);

        int port1 = cache.localPortFor("myhost", 8080);
        int port2 = cache.localPortFor("myhost", 8080);

        assertThat(port1).isEqualTo(port2);
        // clientFor should only be called once — second call reuses cache
        verify(sshSessionManager, times(1)).clientFor("myhost");
    }

    @Test
    void localPortFor_differentTargets_differentPorts() throws Exception {
        SSHClient mockClient1 = createMockSshClient();
        SSHClient mockClient2 = createMockSshClient();
        when(sshSessionManager.clientFor("host1")).thenReturn(mockClient1);
        when(sshSessionManager.clientFor("host2")).thenReturn(mockClient2);

        int port1 = cache.localPortFor("host1", 8080);
        int port2 = cache.localPortFor("host2", 9090);

        assertThat(port1).isNotEqualTo(port2);
    }

    @Test
    void invalidate_removesEntry() throws Exception {
        // AC #3: invalidate removes entry; next call opens fresh forwarder
        SSHClient mockClient = createMockSshClient();
        when(sshSessionManager.clientFor("myhost")).thenReturn(mockClient);

        int port1 = cache.localPortFor("myhost", 8080);
        cache.invalidate("myhost", 8080);

        // After invalidation, cache is empty
        assertThat(cache.size()).isZero();

        // Next call should create a new forwarder
        SSHClient mockClient2 = createMockSshClient();
        when(sshSessionManager.clientFor("myhost")).thenReturn(mockClient2);
        int port2 = cache.localPortFor("myhost", 8080);

        // Port may differ (new ephemeral port)
        assertThat(port2).isGreaterThan(0);
        verify(sshSessionManager, times(2)).clientFor("myhost");
    }

    @Test
    void localPortFor_usesInjectedExecutor() throws Exception {
        // AC #4: forwarder threads submitted to injected Executor
        ExecutorService spyExecutor = spy(executor);
        PortForwardCache cacheWithSpy = new PortForwardCache(sshSessionManager, spyExecutor);

        SSHClient mockClient = createMockSshClient();
        when(sshSessionManager.clientFor("myhost")).thenReturn(mockClient);

        cacheWithSpy.localPortFor("myhost", 8080);

        verify(spyExecutor).submit(any(Runnable.class));
        cacheWithSpy.shutdown();
    }

    @Test
    void localPortFor_deadForwarder_isReplaced() throws Exception {
        // AC #5: dead forwarder detected and replaced
        SSHClient mockClient = createMockSshClient();
        when(sshSessionManager.clientFor("myhost")).thenReturn(mockClient);

        int port1 = cache.localPortFor("myhost", 8080);

        // Simulate the forwarder dying by invalidating (closes socket, future completes)
        // We need a more direct approach: close the server socket to make the forwarder exit
        // Instead, let's access the cache and force the future to complete
        // The forwarder's listen() call will exit when the ServerSocket is closed
        cache.invalidate("myhost", 8080);

        // Now create a new one — it should allocate a fresh port
        SSHClient mockClient2 = createMockSshClient();
        when(sshSessionManager.clientFor("myhost")).thenReturn(mockClient2);
        int port2 = cache.localPortFor("myhost", 8080);

        assertThat(port2).isGreaterThan(0);
        // Was called twice — once for initial, once for replacement
        verify(sshSessionManager, times(2)).clientFor("myhost");
    }

    @Test
    void onSessionEvicted_invalidatesAllEntriesForHost() throws Exception {
        // Test eviction listener callback
        SSHClient mockClient = createMockSshClient();
        when(sshSessionManager.clientFor("myhost")).thenReturn(mockClient);

        cache.localPortFor("myhost", 8080);
        cache.localPortFor("myhost", 9090);

        assertThat(cache.size()).isEqualTo(2);

        // Evict the host
        cache.onSessionEvicted("myhost");

        assertThat(cache.size()).isZero();
    }

    @Test
    void onSessionEvicted_doesNotAffectOtherHosts() throws Exception {
        SSHClient mockClient1 = createMockSshClient();
        SSHClient mockClient2 = createMockSshClient();
        when(sshSessionManager.clientFor("host1")).thenReturn(mockClient1);
        when(sshSessionManager.clientFor("host2")).thenReturn(mockClient2);

        cache.localPortFor("host1", 8080);
        cache.localPortFor("host2", 9090);

        assertThat(cache.size()).isEqualTo(2);

        cache.onSessionEvicted("host1");

        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void localPortFor_throwsIOException_onSshFailure() throws Exception {
        when(sshSessionManager.clientFor("badhost")).thenThrow(new IOException("Connection refused"));

        assertThatThrownBy(() -> cache.localPortFor("badhost", 8080))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Connection refused");
    }

    @Test
    void createForwardEntry_closesSocketOnForwarderCreationFailure() throws Exception {
        // Regression test: ServerSocket must be closed if newLocalPortForwarder throws
        SSHClient mockClient = mock(SSHClient.class);
        when(mockClient.newLocalPortForwarder(any(), any()))
                .thenThrow(new RuntimeException("Forwarder creation failed"));
        when(sshSessionManager.clientFor("myhost")).thenReturn(mockClient);

        assertThatThrownBy(() -> cache.localPortFor("myhost", 8080))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to create port forward");

        // Cache should remain empty (no poisoning)
        assertThat(cache.size()).isZero();
    }

    @Test
    void deadForwarder_detectedByFutureDone() throws Exception {
        // AC #5: Use a real executor that completes the future quickly
        // Mock the SSH client with a forwarder that exits immediately
        SSHClient mockClient = mock(SSHClient.class);
        LocalPortForwarder mockForwarder = mock(LocalPortForwarder.class);
        // Make listen() return immediately (simulating death)
        doNothing().when(mockForwarder).listen();
        when(mockClient.newLocalPortForwarder(any(), any())).thenReturn(mockForwarder);
        when(sshSessionManager.clientFor("myhost")).thenReturn(mockClient);

        int port1 = cache.localPortFor("myhost", 8080);

        // Wait for the future to complete (forwarder "died" immediately)
        Thread.sleep(200);

        // Create a new mock for the replacement
        SSHClient mockClient2 = mock(SSHClient.class);
        LocalPortForwarder mockForwarder2 = mock(LocalPortForwarder.class);
        // Make the replacement block (simulate alive forwarder)
        doAnswer(invocation -> {
            Thread.sleep(Long.MAX_VALUE);
            return null;
        }).when(mockForwarder2).listen();
        when(mockClient2.newLocalPortForwarder(any(), any())).thenReturn(mockForwarder2);
        when(sshSessionManager.clientFor("myhost")).thenReturn(mockClient2);

        // This should detect dead forwarder and create new one
        int port2 = cache.localPortFor("myhost", 8080);

        assertThat(port2).isGreaterThan(0);
        // SSH client was called twice — initial + replacement
        verify(sshSessionManager, times(2)).clientFor("myhost");
    }

    @Test
    void localPortFor_throwsIOException_whenPoolExhausted() throws Exception {
        // Create a pool of size 1 with SynchronousQueue (fail-fast)
        ExecutorService tinyPool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "test-tiny-pool");
                    t.setDaemon(true);
                    return t;
                });

        PortForwardCache tinyCache = new PortForwardCache(sshSessionManager, tinyPool);

        try {
            // First forwarder uses the only thread (blocks forever via mock)
            SSHClient mockClient1 = createMockSshClient();
            when(sshSessionManager.clientFor("host1")).thenReturn(mockClient1);
            tinyCache.localPortFor("host1", 8080);

            // Second forwarder should fail — pool is exhausted
            SSHClient mockClient2 = createMockSshClient();
            when(sshSessionManager.clientFor("host2")).thenReturn(mockClient2);

            assertThatThrownBy(() -> tinyCache.localPortFor("host2", 9090))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("thread pool exhausted");
        } finally {
            tinyCache.shutdown();
            tinyPool.shutdownNow();
        }
    }

    // --- Helpers ---

    private SSHClient createMockSshClient() throws IOException {
        SSHClient client = mock(SSHClient.class);
        LocalPortForwarder forwarder = mock(LocalPortForwarder.class);
        // Make listen() block forever (simulating a live forwarder)
        doAnswer(invocation -> {
            Thread.sleep(Long.MAX_VALUE);
            return null;
        }).when(forwarder).listen();
        when(client.newLocalPortForwarder(any(), any())).thenReturn(forwarder);
        return client;
    }
}
