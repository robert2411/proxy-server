package com.github.robert2411.proxy;

import com.github.robert2411.ssh.SshSessionManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Centralised Micrometer metrics for the SSH proxy.
 * Registers five key metrics:
 * <ul>
 *   <li>proxy.requests.total — Counter per target/status</li>
 *   <li>ssh.sessions.active — Gauge tracking live SSH sessions</li>
 *   <li>port.forwards.active — Gauge tracking live port forwards</li>
 *   <li>proxy.upstream.latency.seconds — Timer with percentile histogram</li>
 *   <li>ssh.reconnects.total — Counter per target</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "ssh.enabled", havingValue = "true", matchIfMissing = true)
public class ProxyMetrics {

    private final MeterRegistry registry;
    private final SshSessionManager sshSessionManager;
    private final PortForwardCache portForwardCache;
    private final Timer upstreamLatencyTimer;

    /** Maximum number of distinct 'target' tag values before new values are denied. */
    static final int MAX_TARGET_TAGS = 128;

    public ProxyMetrics(MeterRegistry registry,
                        SshSessionManager sshSessionManager,
                        PortForwardCache portForwardCache) {
        this.registry = registry;
        this.sshSessionManager = sshSessionManager;
        this.portForwardCache = portForwardCache;

        // SEC-001: Cap cardinality of the 'target' tag to prevent unbounded memory
        // growth from attacker-controlled hostnames sprayed via URL path variables.
        registry.config().meterFilter(
                MeterFilter.maximumAllowableTags(
                        "proxy.requests.total", "target", MAX_TARGET_TAGS, MeterFilter.deny()));

        this.upstreamLatencyTimer = Timer.builder("proxy.upstream.latency.seconds")
                .description("Latency from proxy to upstream first response byte")
                .publishPercentileHistogram(true)
                .register(registry);
    }

    @PostConstruct
    void init() {
        // Bind gauges to live objects
        registry.gauge("ssh.sessions.active", sshSessionManager, SshSessionManager::cacheSize);
        registry.gauge("port.forwards.active", portForwardCache, PortForwardCache::size);

        // Register reconnect listener on SshSessionManager
        sshSessionManager.setReconnectListener((host, client) -> recordReconnect(host));
    }

    /**
     * Record a proxied request with target host and HTTP status code.
     */
    public void recordRequest(String target, int status) {
        Counter.builder("proxy.requests.total")
                .tag("target", target)
                .tag("status", String.valueOf(status))
                .register(registry)
                .increment();
    }

    /**
     * Record an SSH reconnection event for the given target host.
     */
    public void recordReconnect(String target) {
        Counter.builder("ssh.reconnects.total")
                .tag("target", target)
                .register(registry)
                .increment();
    }

    /**
     * Returns the upstream latency timer for use with Timer.Sample.
     */
    public Timer upstreamLatencyTimer() {
        return upstreamLatencyTimer;
    }

    /**
     * Returns the registry for creating Timer.Samples in ProxyHandler.
     */
    public MeterRegistry registry() {
        return registry;
    }
}
