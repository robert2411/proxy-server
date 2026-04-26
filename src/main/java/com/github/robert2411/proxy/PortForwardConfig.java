package com.github.robert2411.proxy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configuration for the port-forwarding executor.
 * Provides a bounded thread pool for LocalPortForwarder.listen() calls.
 */
@Configuration
public class PortForwardConfig {

    private static final int PORT_FORWARD_POOL_SIZE = 16;

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService portForwardExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "port-fwd-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(PORT_FORWARD_POOL_SIZE, factory);
    }
}
