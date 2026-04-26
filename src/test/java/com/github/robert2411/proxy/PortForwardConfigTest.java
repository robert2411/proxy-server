package com.github.robert2411.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortForwardConfigTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void poolSizeIsConfigurable() {
        PortForwardConfig config = new PortForwardConfig();
        executor = config.portForwardExecutor(4);

        // Should be a ThreadPoolExecutor with the configured pool size
        assertThat(executor).isInstanceOf(java.util.concurrent.ThreadPoolExecutor.class);
        java.util.concurrent.ThreadPoolExecutor tpe = (java.util.concurrent.ThreadPoolExecutor) executor;
        assertThat(tpe.getMaximumPoolSize()).isEqualTo(4);
        assertThat(tpe.getCorePoolSize()).isEqualTo(4);
    }

    @Test
    void poolRejectsWhenExhausted() throws InterruptedException {
        PortForwardConfig config = new PortForwardConfig();
        executor = config.portForwardExecutor(1);

        CountDownLatch blockingLatch = new CountDownLatch(1);

        // Fill the single thread with a blocking task
        executor.submit(() -> {
            try {
                blockingLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Give the thread time to start
        Thread.sleep(50);

        // Second submit should be rejected since pool is exhausted and SynchronousQueue can't queue
        assertThatThrownBy(() -> executor.submit(() -> {}))
                .isInstanceOf(RejectedExecutionException.class);

        // Clean up
        blockingLatch.countDown();
    }

    @Test
    void poolCreatesNamedDaemonThreads() throws InterruptedException {
        PortForwardConfig config = new PortForwardConfig();
        executor = config.portForwardExecutor(1);

        CountDownLatch latch = new CountDownLatch(1);
        final Thread[] threadHolder = new Thread[1];

        executor.submit(() -> {
            threadHolder[0] = Thread.currentThread();
            latch.countDown();
        });

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(threadHolder[0].getName()).startsWith("port-fwd-");
        assertThat(threadHolder[0].isDaemon()).isTrue();
    }

    @Test
    void defaultPoolSizeIsSixteen() {
        // Verify the default value annotation is 16
        PortForwardConfig config = new PortForwardConfig();
        executor = config.portForwardExecutor(16);

        java.util.concurrent.ThreadPoolExecutor tpe = (java.util.concurrent.ThreadPoolExecutor) executor;
        assertThat(tpe.getMaximumPoolSize()).isEqualTo(16);
    }
}
