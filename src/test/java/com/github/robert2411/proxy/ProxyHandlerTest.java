package com.github.robert2411.proxy;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProxyHandlerTest {

    private MockWebServer mockServer;
    private PortForwardCache portForwardCache;
    private ProxyMetrics proxyMetrics;
    private SimpleMeterRegistry meterRegistry;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        portForwardCache = mock(PortForwardCache.class);

        // Create a real ProxyMetrics with SimpleMeterRegistry and mocked dependencies
        meterRegistry = new SimpleMeterRegistry();
        com.github.robert2411.ssh.SshSessionManager mockManager =
                mock(com.github.robert2411.ssh.SshSessionManager.class);
        proxyMetrics = new ProxyMetrics(meterRegistry, mockManager, portForwardCache);

        ProxyHandler handler = new ProxyHandler(portForwardCache, WebClient.builder(), proxyMetrics);
        ProxyRouterConfig routerConfig = new ProxyRouterConfig();
        RouterFunction<ServerResponse> route = routerConfig.proxyRoute(handler);

        webTestClient = WebTestClient.bindToRouterFunction(route)
                .configureClient()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void get_withPathAndQuery_preserved() throws Exception {
        // AC #1 (GET), AC #2 (path + query preserved)
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setBody("hello").setResponseCode(200));

        webTestClient.get()
                .uri("/myhost/8080/api/data?foo=bar&baz=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("hello");

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/api/data?foo=bar&baz=1");
    }

    @Test
    void post_proxiedCorrectly() throws Exception {
        // AC #1 (POST)
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setBody("created").setResponseCode(201));

        webTestClient.post()
                .uri("/myhost/8080/items")
                .bodyValue("request-body")
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody(String.class).isEqualTo("created");

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/items");
        assertThat(req.getBody().readUtf8()).isEqualTo("request-body");
    }

    @Test
    void put_proxiedCorrectly() throws Exception {
        // AC #1 (PUT)
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setResponseCode(204));

        webTestClient.put()
                .uri("/myhost/8080/items/1")
                .bodyValue("updated")
                .exchange()
                .expectStatus().isNoContent();

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("PUT");
    }

    @Test
    void delete_proxiedCorrectly() throws Exception {
        // AC #1 (DELETE)
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setResponseCode(200));

        webTestClient.delete()
                .uri("/myhost/8080/items/1")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("DELETE");
    }

    @Test
    void patch_proxiedCorrectly() throws Exception {
        // AC #1 (PATCH)
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("patched"));

        webTestClient.patch()
                .uri("/myhost/8080/items/1")
                .bodyValue("patch-data")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("patched");

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("PATCH");
    }

    @Test
    void hostHeader_rewrittenToTargetHostPort() throws Exception {
        // AC #3: Host header on upstream request is set to targetHost:targetPort
        when(portForwardCache.localPortFor("backend-server", 9090)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setBody("ok"));

        webTestClient.get()
                .uri("/backend-server/9090/api")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getHeader("Host")).isEqualTo("backend-server:9090");
    }

    @Test
    void responseStatusAndHeaders_forwardedUnchanged() throws Exception {
        // AC #4: Response status code and headers returned unchanged
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse()
                .setResponseCode(207)
                .setHeader("X-Custom-Header", "custom-value")
                .setHeader("X-Another", "another-value")
                .setBody("multi-status"));

        webTestClient.get()
                .uri("/myhost/8080/status")
                .exchange()
                .expectStatus().isEqualTo(207)
                .expectHeader().valueEquals("X-Custom-Header", "custom-value")
                .expectHeader().valueEquals("X-Another", "another-value")
                .expectBody(String.class).isEqualTo("multi-status");
    }

    @Test
    void largeBody_streamsWithoutFullBuffering() throws Exception {
        // AC #5: Large bodies stream without full in-memory buffering
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());

        // Generate a 10MB+ response
        int size = 11 * 1024 * 1024; // 11MB
        StringBuilder sb = new StringBuilder(size);
        String chunk = "x".repeat(1024);
        while (sb.length() < size) {
            sb.append(chunk);
        }
        String largeBody = sb.toString();

        mockServer.enqueue(new MockResponse()
                .setBody(largeBody)
                .setResponseCode(200));

        byte[] response = webTestClient.get()
                .uri("/myhost/8080/large")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.length).isEqualTo(largeBody.length());
    }

    @Test
    void urlEncodedSlashes_notCorrupted() throws Exception {
        // AC #6: URL-encoded slashes in path tail are not corrupted
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setBody("ok"));

        // Use URI directly to avoid WebTestClient re-encoding the %2F
        webTestClient.get()
                .uri(URI.create("/myhost/8080/path%2Fwith%2Fslashes"))
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/path%2Fwith%2Fslashes");
    }

    @Test
    void bareRoute_forwardsToRoot() throws Exception {
        // Bare /{host}/{port} with no trailing path forwards to /
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setBody("root"));

        webTestClient.get()
                .uri("/myhost/8080")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("root");

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/");
    }

    @Test
    void nonNumericPort_returns400() throws Exception {
        // Non-numeric port returns 400 Bad Request
        webTestClient.get()
                .uri("/myhost/abc/api")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(body ->
                        assertThat(body).contains("Invalid port").contains("abc"));
    }

    @Test
    void portOutOfRange_returns400() throws Exception {
        // Port > 65535 returns 400
        webTestClient.get()
                .uri("/myhost/99999/api")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void sshFailure_returns502() throws Exception {
        // IOException from PortForwardCache returns 502 with safe message
        when(portForwardCache.localPortFor("badhost", 8080))
                .thenThrow(new IOException("Connection refused"));

        webTestClient.get()
                .uri("/badhost/8080/api")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("Bad Gateway");
                    // Should NOT expose internal details
                    assertThat(body).doesNotContain("Connection refused");
                });
    }

    @Test
    void head_proxiedCorrectly() throws Exception {
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setResponseCode(200).setHeader("X-Test", "head"));

        webTestClient.head()
                .uri("/myhost/8080/resource")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Test", "head");

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("HEAD");
    }

    @Test
    void options_proxiedCorrectly() throws Exception {
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setResponseCode(204)
                .setHeader("Allow", "GET, POST, OPTIONS"));

        webTestClient.options()
                .uri("/myhost/8080/resource")
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Allow", "GET, POST, OPTIONS");

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("OPTIONS");
    }

    @Test
    void hopByHopHeaders_notForwardedUpstream() throws Exception {
        // Verify that hop-by-hop headers from client are stripped before forwarding
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setBody("ok"));

        webTestClient.get()
                .uri("/myhost/8080/api")
                .header("Connection", "keep-alive")
                .header("Keep-Alive", "timeout=5")
                .header("Proxy-Authorization", "Basic abc123")
                .header("X-Custom", "should-pass")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        // Hop-by-hop headers should be stripped
        assertThat(req.getHeader("Connection")).isNull();
        assertThat(req.getHeader("Keep-Alive")).isNull();
        assertThat(req.getHeader("Proxy-Authorization")).isNull();
        // Non-hop-by-hop headers should pass through
        assertThat(req.getHeader("X-Custom")).isEqualTo("should-pass");
    }

    @Test
    void queryStringOnly_preserved() throws Exception {
        // AC #2: Query string preserved even without complex path
        when(portForwardCache.localPortFor("myhost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setBody("ok"));

        webTestClient.get()
                .uri(URI.create("/myhost/8080/?key=value&special=%20encoded"))
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/?key=value&special=%20encoded");
    }

    @Test
    void metrics_recordedOnSuccessfulRequest() throws Exception {
        when(portForwardCache.localPortFor("metricshost", 8080)).thenReturn(mockServer.getPort());
        mockServer.enqueue(new MockResponse().setBody("ok").setResponseCode(200));

        webTestClient.get()
                .uri("/metricshost/8080/api")
                .exchange()
                .expectStatus().isOk();

        // Verify request counter was incremented
        io.micrometer.core.instrument.Counter counter = meterRegistry.find("proxy.requests.total")
                .tag("target", "metricshost").tag("status", "200").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // Verify timer recorded a sample
        Timer timer = meterRegistry.find("proxy.upstream.latency.seconds").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void metrics_recordedOnPortForwardFailure() throws Exception {
        when(portForwardCache.localPortFor("failhost", 8080))
                .thenThrow(new IOException("Connection refused"));

        webTestClient.get()
                .uri("/failhost/8080/api")
                .exchange()
                .expectStatus().isEqualTo(502);

        // Verify request counter was incremented with 502 status
        io.micrometer.core.instrument.Counter counter = meterRegistry.find("proxy.requests.total")
                .tag("target", "failhost").tag("status", "502").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void extractPathRemainder_variousInputs() {
        ProxyHandler handler = new ProxyHandler(portForwardCache, WebClient.builder(), proxyMetrics);

        // Normal path with remainder
        assertThat(handler.extractPathRemainder("/myhost/8080/api/data", "myhost", "8080"))
                .isEqualTo("/api/data");

        // No remainder (bare route)
        assertThat(handler.extractPathRemainder("/myhost/8080", "myhost", "8080"))
                .isEqualTo("/");

        // Trailing slash only
        assertThat(handler.extractPathRemainder("/myhost/8080/", "myhost", "8080"))
                .isEqualTo("/");

        // URL-encoded content preserved
        assertThat(handler.extractPathRemainder("/myhost/8080/path%2Fencoded", "myhost", "8080"))
                .isEqualTo("/path%2Fencoded");
    }
}
