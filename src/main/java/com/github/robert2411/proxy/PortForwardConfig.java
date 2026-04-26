package com.github.robert2411.proxy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configuration for the port-forwarding executor.
 * Provides a bounded thread pool for LocalPortForwarder.listen() calls.
 * Pool size is configurable via {@code proxy.ssh.forwarder-threads} (default: 16).
 * Uses a SynchronousQueue so that when all threads are busy, new submissions
 * fail fast with RejectedExecutionException rather than queuing unboundedly.
 */
@Configuration
public class PortForwardConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService portForwardExecutor(
            @Value("${proxy.ssh.forwarder-threads:16}") int poolSize) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "port-fwd-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                factory);
    }
}
