package com.github.robert2411.proxy;

import com.github.robert2411.ssh.SshSessionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that all five proxy metrics appear in
 * the /actuator/prometheus endpoint with correct Prometheus exposition format.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ssh.enabled=true")
@ActiveProfiles("test")
class ProxyMetricsIntegrationTest {

    @MockitoBean
    private SshSessionManager sshSessionManager;

    @MockitoBean
    private PortForwardCache portForwardCache;

    @MockitoBean
    private ProxyHandler proxyHandler;

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ProxyMetrics proxyMetrics;

    @Test
    void prometheusEndpoint_containsAllFiveMetrics() {
        // Trigger at least one request counter and reconnect counter so they appear in output
        proxyMetrics.recordRequest("test-target", 200);
        proxyMetrics.recordReconnect("test-target");

        // Record a timer sample to ensure latency metric appears
        Timer.Sample sample = Timer.start(meterRegistry);
        sample.stop(proxyMetrics.upstreamLatencyTimer());

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        String body = client.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();

        // AC#1: All five metrics present
        assertThat(body).contains("proxy_requests_total");
        assertThat(body).contains("ssh_sessions_active");
        assertThat(body).contains("port_forwards_active");
        assertThat(body).contains("proxy_upstream_latency_seconds");
        assertThat(body).contains("ssh_reconnects_total");

        // AC#2: proxy_requests_total has target and status labels
        assertThat(body).contains("proxy_requests_total{");
        assertThat(body).contains("target=\"test-target\"");
        assertThat(body).contains("status=\"200\"");

        // AC#5: Histogram bucket lines present (proves publishPercentileHistogram is working)
        assertThat(body).contains("proxy_upstream_latency_seconds_bucket{");
    }
}
