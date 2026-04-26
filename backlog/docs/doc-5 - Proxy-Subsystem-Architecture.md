---
id: doc-5
title: Proxy Subsystem Architecture
type: other
created_date: '2026-04-26 21:53'
---

# Proxy Subsystem Architecture

## Overview

The proxy subsystem lives in `com.github.robert2411.proxy` and handles all inbound HTTP requests, routing them through SSH port forwards managed by the SSH subsystem. It is implemented as a WebFlux functional router with streaming body support and RFC-compliant header handling.

## Components

### ProxyHandler

**File:** `src/main/java/com/github/robert2411/proxy/ProxyHandler.java`

Spring `@Component` that handles all proxied HTTP requests. Key behaviors:

- **Path-based routing:** Extracts `host` and `port` from the URL path (`/{host}/{port}/...`), resolves the local forwarded port via `PortForwardCache`, and builds an upstream URI targeting `127.0.0.1:<localPort>`.
- **Raw path extraction:** Uses `request.uri().getRawPath()` instead of `pathVariable()` to avoid double-decoding of URL-encoded characters (e.g. `%2F` slashes in path tail).
- **Host header rewrite:** Sets the upstream `Host` header to `targetHost:targetPort` so backends receive the expected hostname, not the proxy's loopback address.
- **DataBuffer streaming:** Request and response bodies are streamed as `Flux<DataBuffer>` — no full in-memory buffering. Uses `retrieve().toEntityFlux(DataBuffer.class)` for proper connection lifecycle (avoids premature connection release that occurs with `exchangeToMono`).
- **Hop-by-hop header filtering:** Strips RFC 2616 hop-by-hop headers (`Connection`, `Keep-Alive`, `Proxy-Authenticate`, `Proxy-Authorization`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`) on both inbound (client→proxy) and outbound (upstream→client) paths. `Content-Length` is also excluded from forwarded headers as Spring WebFlux manages it.
- **Port validation:** `Integer.parseInt(port)` with range check (1–65535). Invalid ports return `400 Bad Request` with a descriptive message. No internal details are leaked.
- **Sanitized error responses:** Upstream connection failures return `502 Bad Gateway` with a generic message — no raw exception text is exposed to clients.
- **SSRF mitigation:** All upstream connections target `127.0.0.1` only (local SSH port forwards). The host parameter is gated by `SshSessionManager` which only accepts SSH config aliases.

### ProxyRouterConfig

**File:** `src/main/java/com/github/robert2411/proxy/ProxyRouterConfig.java`

`@Configuration` class that registers the `RouterFunction<ServerResponse>` bean with **dual route patterns**:

1. `RequestPredicates.path("/{host}/{port}/**")` — matches requests with a path tail (e.g. `/myhost/8080/api/v1/users`)
2. `RequestPredicates.path("/{host}/{port}")` — matches bare requests with no trailing path (e.g. `/myhost/8080`)

The more specific pattern (with `/**`) is registered first. The bare `/{host}/{port}` route forwards to `/` on the backend. Both bind to `ProxyHandler::handle`.

**Why dual patterns:** Spring WebFlux `PathPatternParser` does not match `/{host}/{port}/**` when there is no trailing path segment. Without the second pattern, requests to `/{host}/{port}` would 404.

### ProxyMetrics

**File:** `src/main/java/com/github/robert2411/proxy/ProxyMetrics.java`

Spring `@Component` that centralises all proxy observability metrics. Injected with `MeterRegistry`, `SshSessionManager`, and `PortForwardCache`. Registers five metrics:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `proxy.requests.total` | Counter | `target`, `status` | Incremented on every proxied request (success, error, and early 502 paths) |
| `ssh.sessions.active` | Gauge | — | Bound to `SshSessionManager.cacheSize()`; reflects live SSH session count |
| `port.forwards.active` | Gauge | — | Bound to `PortForwardCache.size()`; reflects active port forwarder count |
| `proxy.upstream.latency.seconds` | Timer | — | Histogram with `publishPercentileHistogram(true)` for p50/p95/p99 buckets |
| `ssh.reconnects.total` | Counter | `target` | Fires via reconnect listener on SshSessionManager stale-session replacement |

**Cardinality protection:** A `MeterFilter.maximumAllowableTags("proxy.requests.total", "target", 128, MeterFilter.deny())` caps the target tag cardinality at 128 to prevent OOM from attacker-controlled hostnames (SEC-001 fix).

**Instrumentation in ProxyHandler:**
- `Timer.Sample` starts **after** `localPortFor()` returns — measures only HTTP upstream latency, excludes SSH connection/port-forward setup time
- Timer stopped and request counter recorded in: success `flatMap`, error `onErrorResume`, and early 502 port-forward failure catch block

**Reconnect listener in SshSessionManager:**
- `BiConsumer<String, SSHClient>` registered via `@PostConstruct`
- Fires `recordReconnect(host)` when a stale session is replaced in `clientFor()`

## Request Flow

```
Client request: GET /targetHost/8080/api/users?q=test
  → ProxyRouterConfig matches /{host}/{port}/**
  → ProxyHandler.handle(request)
    → Extract host="targetHost", port="8080" from raw path
    → Validate port (1–65535)
    → PortForwardCache.localPortFor("targetHost", 8080) → localPort
    → Build upstream URI: http://127.0.0.1:<localPort>/api/users?q=test
    → Strip hop-by-hop headers from inbound request
    → Set Host: targetHost:8080
    → Stream request body as Flux<DataBuffer> via WebClient
    → Receive upstream response
    → Strip hop-by-hop headers from response
    → Stream response body back to client
```

## Security Posture

- **No SSRF:** Upstream target is always `127.0.0.1` (loopback only)
- **No information leakage:** 502 errors use sanitized messages; no stack traces or internal addresses exposed
- **No request smuggling:** Hop-by-hop headers stripped in both directions per RFC 2616
- **Input validation:** Port must be numeric and within 1–65535; invalid values return 400
- **Host header injection mitigated:** Spring/Netty reject control characters at transport layer; host value is a single path segment (no slashes/CRLF)

## Test Strategy

- **ProxyHandlerTest** (20 tests) using `WebTestClient` + `MockWebServer`:
  - All HTTP methods: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
  - Path remainder and query string preserved verbatim
  - Host header rewritten correctly
  - Response status and headers forwarded unchanged
  - 11 MB streaming test (no memory buffering)
  - URL-encoded slash preservation (`%2F` not corrupted)
  - Bare route `/{host}/{port}` forwards to `/`
  - Invalid port returns 400 (non-numeric, out of range)
  - Hop-by-hop header stripping verified
  - Metrics recorded on successful request (counter + timer)
  - Metrics recorded on port-forward failure (counter with status 502)
- **ProxyMetricsTest** (7 tests) using `SimpleMeterRegistry`:
  - All 5 metric registrations verified
  - Tag correctness for target/status labels
  - Histogram bucket generation confirmed
  - Reconnect listener integration
- **ProxyMetricsIntegrationTest** (`@SpringBootTest`):
  - `/actuator/prometheus` contains all 5 metric names
  - `proxy_upstream_latency_seconds_bucket` lines present (validates percentile histogram)

## Related

- [SSH Subsystem Architecture](doc-2%20-%20SSH-Subsystem-Architecture.md) — upstream SSH session management
- [Decision: Spring Boot WebFlux with sshj](../decisions/decision-3%20-%20Spring-Boot-WebFlux-with-sshj-for-SSH-aware-HTTP-proxy.md) — overall architecture choice
- Handoff doc (doc-1) — original design discussion including ProxyHandler sketch
