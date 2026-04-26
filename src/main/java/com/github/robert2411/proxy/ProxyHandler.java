package com.github.robert2411.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * WebFlux handler that proxies HTTP requests through SSH port forwards.
 * Extracts target host and port from the URL path, resolves the local forwarded port
 * via PortForwardCache, and streams the request/response through WebClient.
 */
@Component
@ConditionalOnProperty(name = "ssh.enabled", havingValue = "true", matchIfMissing = true)
public class ProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyHandler.class);

    /**
     * Hop-by-hop headers per RFC 2616 Section 13.5.1 that must not be forwarded by proxies.
     */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade"
    );

    private final PortForwardCache portForwardCache;
    private final WebClient webClient;
    private final ProxyMetrics proxyMetrics;

    public ProxyHandler(PortForwardCache portForwardCache, WebClient.Builder webClientBuilder,
                        ProxyMetrics proxyMetrics) {
        this.portForwardCache = portForwardCache;
        this.proxyMetrics = proxyMetrics;
        this.webClient = webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(-1))
                .defaultStatusHandler(status -> true, response -> Mono.empty())
                .build();
    }

    /**
     * Handles an incoming proxy request. Extracts host and port from the path,
     * resolves the local forwarded port, and streams the request to the upstream.
     */
    public Mono<ServerResponse> handle(ServerRequest request) {
        String host = request.pathVariable("host");
        String portStr = request.pathVariable("port");

        // Validate port
        int targetPort;
        try {
            targetPort = Integer.parseInt(portStr);
            if (targetPort < 1 || targetPort > 65535) {
                return ServerResponse.badRequest()
                        .bodyValue("Invalid port: " + portStr + " — must be between 1 and 65535");
            }
        } catch (NumberFormatException e) {
            return ServerResponse.badRequest()
                    .bodyValue("Invalid port: " + portStr + " — must be numeric");
        }

        // Resolve local forwarded port
        int localPort;
        try {
            localPort = portForwardCache.localPortFor(host, targetPort);
        } catch (IOException e) {
            log.warn("Failed to establish port forward for {}:{}: {}", host, targetPort, e.getMessage());
            proxyMetrics.recordRequest(host, 502);
            return ServerResponse.status(502)
                    .bodyValue("Bad Gateway: unable to establish connection to upstream");
        }

        // Start latency timer AFTER localPortFor (measures only HTTP upstream round-trip)
        Timer.Sample sample = Timer.start(proxyMetrics.registry());

        // Build upstream URI from raw path (avoids double-decoding URL-encoded slashes)
        String rawPath = request.uri().getRawPath();
        String pathRemainder = extractPathRemainder(rawPath, host, portStr);
        String rawQuery = request.uri().getRawQuery();

        // Build URI using the raw components to avoid double-encoding
        URI upstreamUri = buildUpstreamUri(localPort, pathRemainder, rawQuery);

        HttpMethod method = request.method();

        // Stream request body
        Flux<DataBuffer> requestBody = request.bodyToFlux(DataBuffer.class);

        return webClient.method(method)
                .uri(upstreamUri)
                .headers(headers -> {
                    // Copy all original headers except Host and hop-by-hop headers
                    request.headers().asHttpHeaders().forEach((name, values) -> {
                        if (!HttpHeaders.HOST.equalsIgnoreCase(name)
                                && !isHopByHopHeader(name)) {
                            headers.addAll(name, values);
                        }
                    });
                    // Set Host to targetHost:targetPort
                    headers.set(HttpHeaders.HOST, host + ":" + targetPort);
                })
                .body(BodyInserters.fromDataBuffers(requestBody))
                .retrieve()
                .toEntityFlux(DataBuffer.class)
                .flatMap(entity -> {
                    // Record metrics on success
                    sample.stop(proxyMetrics.upstreamLatencyTimer());
                    proxyMetrics.recordRequest(host, entity.getStatusCode().value());

                    ServerResponse.BodyBuilder responseBuilder = ServerResponse
                            .status(entity.getStatusCode());

                    // Copy all response headers except hop-by-hop headers
                    entity.getHeaders().forEach((name, values) -> {
                        if (!isHopByHopHeader(name)) {
                            responseBuilder.header(name, values.toArray(new String[0]));
                        }
                    });

                    Flux<DataBuffer> responseBody = entity.getBody();
                    return responseBuilder.body(BodyInserters.fromDataBuffers(responseBody));
                })
                .onErrorResume(ex -> {
                    // Record metrics on error
                    sample.stop(proxyMetrics.upstreamLatencyTimer());
                    proxyMetrics.recordRequest(host, 502);

                    log.warn("Proxy request to {} failed: {}", upstreamUri, ex.getMessage());
                    return ServerResponse.status(502)
                            .bodyValue("Bad Gateway: upstream connection failed");
                });
    }

    /**
     * Extracts the path remainder after /{host}/{port} from the raw path.
     * Uses raw path to preserve URL-encoded characters (like %2F).
     */
    String extractPathRemainder(String rawPath, String host, String port) {
        // Find the prefix /{host}/{port} in the raw path
        String prefix = "/" + host + "/" + port;
        int prefixIndex = rawPath.indexOf(prefix);
        if (prefixIndex == -1) {
            return "/";
        }
        String remainder = rawPath.substring(prefixIndex + prefix.length());
        if (remainder.isEmpty()) {
            return "/";
        }
        return remainder;
    }

    /**
     * Builds the upstream URI using raw components to avoid double-encoding.
     */
    private URI buildUpstreamUri(int localPort, String rawPath, String rawQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append("http://127.0.0.1:").append(localPort).append(rawPath);
        if (rawQuery != null) {
            sb.append("?").append(rawQuery);
        }
        // Use URI constructor that takes an already-encoded string
        return URI.create(sb.toString());
    }

    /**
     * Checks if a header is a hop-by-hop header that should not be forwarded.
     */
    private boolean isHopByHopHeader(String headerName) {
        return HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase())
                || "Content-Length".equalsIgnoreCase(headerName);
    }
}
