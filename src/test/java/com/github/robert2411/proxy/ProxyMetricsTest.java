package com.github.robert2411.proxy;

import com.github.robert2411.ssh.SshSessionManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProxyMetricsTest {

    private SimpleMeterRegistry registry;
    private SshSessionManager sshSessionManager;
    private PortForwardCache portForwardCache;
    private ProxyMetrics proxyMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        sshSessionManager = mock(SshSessionManager.class);
        portForwardCache = mock(PortForwardCache.class);
        proxyMetrics = new ProxyMetrics(registry, sshSessionManager, portForwardCache);
        proxyMetrics.init();
    }

    @Test
    void recordRequest_incrementsCounterWithTags() {
        proxyMetrics.recordRequest("myhost", 200);
        proxyMetrics.recordRequest("myhost", 200);
        proxyMetrics.recordRequest("myhost", 500);

        Counter ok = registry.find("proxy.requests.total")
                .tag("target", "myhost").tag("status", "200").counter();
        assertThat(ok).isNotNull();
        assertThat(ok.count()).isEqualTo(2.0);

        Counter err = registry.find("proxy.requests.total")
                .tag("target", "myhost").tag("status", "500").counter();
        assertThat(err).isNotNull();
        assertThat(err.count()).isEqualTo(1.0);
    }

    @Test
    void sshSessionsActiveGauge_reflectsManagerCacheSize() {
        when(sshSessionManager.cacheSize()).thenReturn(3);

        Gauge gauge = registry.find("ssh.sessions.active").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(3.0);
    }

    @Test
    void portForwardsActiveGauge_reflectsCacheSize() {
        when(portForwardCache.size()).thenReturn(5);

        Gauge gauge = registry.find("port.forwards.active").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(5.0);
    }

    @Test
    void upstreamLatencyTimer_recordsSamples() {
        Timer timer = proxyMetrics.upstreamLatencyTimer();
        assertThat(timer).isNotNull();

        Timer.Sample sample = Timer.start(registry);
        // Simulate some latency
        sample.stop(timer);

        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThan(0);
    }

    @Test
    void recordReconnect_incrementsCounterWithTarget() {
        proxyMetrics.recordReconnect("host-a");
        proxyMetrics.recordReconnect("host-a");
        proxyMetrics.recordReconnect("host-b");

        Counter counterA = registry.find("ssh.reconnects.total")
                .tag("target", "host-a").counter();
        assertThat(counterA).isNotNull();
        assertThat(counterA.count()).isEqualTo(2.0);

        Counter counterB = registry.find("ssh.reconnects.total")
                .tag("target", "host-b").counter();
        assertThat(counterB).isNotNull();
        assertThat(counterB.count()).isEqualTo(1.0);
    }

    @Test
    void reconnectListener_registeredOnManager() {
        // Verify that setReconnectListener was called during init
        verify(sshSessionManager).setReconnectListener(any());
    }

    @Test
    void registryAccessor_returnsInjectedRegistry() {
        assertThat(proxyMetrics.registry()).isSameAs(registry);
    }

    @Test
    void recordRequest_capsTargetCardinalityAtMaxTags() {
        // Record requests for MAX_TARGET_TAGS distinct targets — all should register
        for (int i = 0; i < ProxyMetrics.MAX_TARGET_TAGS; i++) {
            proxyMetrics.recordRequest("host-" + i, 200);
        }
        long distinctBefore = registry.find("proxy.requests.total").counters().stream()
                .map(c -> c.getId().getTag("target"))
                .distinct().count();
        assertThat(distinctBefore).isEqualTo(ProxyMetrics.MAX_TARGET_TAGS);

        // One more distinct target should be denied by the MeterFilter
        proxyMetrics.recordRequest("overflow-host", 200);
        long distinctAfter = registry.find("proxy.requests.total").counters().stream()
                .map(c -> c.getId().getTag("target"))
                .distinct().count();
        assertThat(distinctAfter).isEqualTo(ProxyMetrics.MAX_TARGET_TAGS);
    }
}
