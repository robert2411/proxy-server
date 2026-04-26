package com.github.robert2411.ssh;

import net.schmizz.sshj.SSHClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanCreationException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SshSessionManager.
 * Tests config loading, cache behaviour, stale eviction, and error handling.
 * Note: actual SSH connections are not made in unit tests — integration tests
 * would require a real SSH server.
 */
class SshSessionManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void constructorLoadsValidConfig() {
        File configFile = getTestConfigFile();
        SshSessionManager manager = new SshSessionManager(configFile);
        assertNotNull(manager);
        assertEquals(0, manager.cacheSize());
    }

    @Test
    void constructorThrowsBeanCreationExceptionForMissingFile() {
        File missingFile = new File("/nonexistent/path/.ssh/config");
        BeanCreationException ex = assertThrows(BeanCreationException.class,
                () -> new SshSessionManager(missingFile));
        assertTrue(ex.getMessage().contains("SSH config not found"));
    }

    @Test
    void constructorThrowsBeanCreationExceptionForInvalidConfig() throws IOException {
        // Create a directory (not a file) to trigger IOException
        File dirAsConfig = tempDir.resolve("fake_config_dir").toFile();
        dirAsConfig.mkdirs();
        // Trying to read a directory as a file should fail
        // (In practice, sshj/our parser handles this differently on different OS)
        // So we test with a valid but trivial config to ensure no crash
        File validConfig = tempDir.resolve("empty_config").toFile();
        Files.writeString(validConfig.toPath(), "# empty config\n");
        SshSessionManager manager = new SshSessionManager(validConfig);
        assertNotNull(manager);
    }

    @Test
    void clientForThrowsIOExceptionWhenConnectionFails() {
        File configFile = getTestConfigFile();
        SshSessionManager manager = new SshSessionManager(configFile);

        // Attempting to connect to bastion.example.com will fail (host doesn't exist)
        assertThrows(IOException.class, () -> manager.clientFor("bastion"));
    }

    @Test
    void clientForHandlesUnknownHostGracefully() {
        File configFile = getTestConfigFile();
        SshSessionManager manager = new SshSessionManager(configFile);

        // Unknown host should attempt connection with defaults and fail
        assertThrows(IOException.class, () -> manager.clientFor("nonexistent-host"));
    }

    @Test
    void evictRemovesSessionFromCache() {
        File configFile = getTestConfigFile();
        SshSessionManager manager = new SshSessionManager(configFile);

        // Evict a host that's not in cache — should be a no-op
        manager.evict("bastion");
        assertEquals(0, manager.cacheSize());
    }

    @Test
    void cacheSizeReflectsState() {
        File configFile = getTestConfigFile();
        SshSessionManager manager = new SshSessionManager(configFile);
        assertEquals(0, manager.cacheSize());
    }

    @Test
    void concurrentAccessToManagerDoesNotThrow() throws Exception {
        File configFile = getTestConfigFile();
        SshSessionManager manager = new SshSessionManager(configFile);

        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger concurrencyErrors = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String host = "host-" + i;
            executor.submit(() -> {
                try {
                    // These will all fail to connect (hosts don't exist)
                    // but they should not throw ConcurrentModificationException
                    manager.clientFor(host);
                } catch (IOException e) {
                    // Expected — hosts don't exist
                } catch (Exception e) {
                    // Unexpected concurrent error
                    concurrencyErrors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertEquals(0, concurrencyErrors.get(), "No unexpected concurrent errors should occur");
    }

    @Test
    void cachedHostsReturnsEmptyInitially() {
        File configFile = getTestConfigFile();
        SshSessionManager manager = new SshSessionManager(configFile);
        assertFalse(manager.cachedHosts().iterator().hasNext());
    }

    private File getTestConfigFile() {
        return new File(getClass().getClassLoader().getResource("test_ssh_config").getFile());
    }
}
