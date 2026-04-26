---
id: TASK-5
title: >-
  Implement ProxyHandler and RouterFunction — path routing, body streaming, Host
  header rewrite
status: In Progress
assignee:
  - '@myself'
created_date: '2026-04-24 21:27'
updated_date: '2026-04-26 21:55'
labels: []
milestone: m-1
dependencies:
  - TASK-4
priority: high
ordinal: 2000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
WebFlux functional router that handles all inbound requests matching /{host}/{port}/**. Extracts target host and port from path, resolves local port from PortForwardCache, builds upstream URI, and streams request/response through WebClient.

Design decisions (from design discussion):
- Route: RequestPredicates.path("/{host}/{port}/**")
- Strip /{host}/{port} prefix from path before forwarding; preserve remaining path and raw query string
- Rewrite Host header to host:port (backends reject mismatched Host)
- Stream body as Flux<DataBuffer> in both directions — do not buffer full body in memory
- Preserve all original request headers except Host; do not add X-Forwarded-* unless intentional
- Forward response status and headers verbatim
- URL-encoded slashes in the ** tail: ensure PathPatternParser does not double-decode them
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 GET/POST/PUT/DELETE/PATCH all proxied correctly
- [x] #2 Path remainder and query string preserved verbatim on upstream request
- [x] #3 Host header on upstream request is set to targetHost:targetPort
- [x] #4 Response status code and headers returned to caller unchanged
- [x] #5 Large request and response bodies stream without full in-memory buffering (verified with a > 10 MB payload)
- [x] #6 URL-encoded slashes in path tail are not corrupted
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Create class `com.github.robert2411.proxy.ProxyHandler` as a Spring `@Component`
   - File: `src/main/java/com/github/robert2411/proxy/ProxyHandler.java`
   - Inject `PortForwardCache` and `WebClient.Builder` (constructor injection)
   - Build a `WebClient` instance in constructor (base URL not set -- dynamic per request)

2. Create `ProxyRouterConfig` that registers the RouterFunction bean
   - File: `src/main/java/com/github/robert2411/proxy/ProxyRouterConfig.java`
   - `@Configuration` class with `@Bean RouterFunction<ServerResponse> proxyRoute(ProxyHandler handler)`
   - Register TWO route patterns to handle both cases:
     a. `RequestPredicates.path("/{host}/{port}/**")` — matches requests with path tail
     b. `RequestPredicates.path("/{host}/{port}")` — matches requests with NO trailing path
   - Both bind to `handler::handle` method
   - Order: the more specific pattern (with /**) first, then the bare pattern
   - The bare `/{host}/{port}` route forwards to `/` on the backend

3. Implement `ProxyHandler.handle(ServerRequest request)` returning `Mono<ServerResponse>`:
   a. Extract `host` and `port` path variables from request
   b. Validate port: wrap `Integer.parseInt(port)` in try-catch for NumberFormatException.
      On NFE -> return `ServerResponse.badRequest().bodyValue("Invalid port: <port> — must be numeric")`
      immediately (Mono.just). Do NOT let it bubble as a 500.
   c. Call `portForwardCache.localPortFor(host, parsedPort)` to get local loopback port
   d. Build upstream URI:
      - Scheme: "http"
      - Host: "127.0.0.1"
      - Port: localPort from cache
      - Path: strip the `/{host}/{port}` prefix from request path, preserve remainder verbatim
      - If no remainder after stripping (bare /{host}/{port} matched), use "/" as upstream path
      - Query: `request.uri().getRawQuery()` -- preserve raw query string (AC #2)
   e. Use `request.uri().getRawPath()` to avoid double-decoding of URL-encoded slashes (AC #6)
   f. Strip prefix by finding the index after `/{host}/{port}` in the raw path.
      Algorithm: find first `/`, then second `/` (start of port), then third `/` (start of tail).
      If no third `/` exists -> path remainder is "/" (handles bare route case).

4. Build upstream WebClient request (AC #1):
   - Method: `request.method()` (handles GET/POST/PUT/DELETE/PATCH)
   - Headers: copy all original headers EXCEPT `Host`
   - Set Host header to `targetHost:targetPort` (AC #3) — use original host and port from URL, not loopback
   - Body: `request.bodyToFlux(DataBuffer.class)` -- streams without buffering (AC #5)
   - Use `WebClient.method(httpMethod).uri(upstreamUri).headers(h -> ...).body(bodyFlux, DataBuffer.class)`

5. Stream response back (AC #4, #5):
   - Use `.exchangeToMono(clientResponse -> ...)` to access raw response
   - Build `ServerResponse` with:
     - Status: `clientResponse.statusCode()` (AC #4)
     - Headers: copy all response headers verbatim (AC #4)
     - Body: `clientResponse.bodyToFlux(DataBuffer.class)` streamed back via `BodyInserters.fromDataBuffers(flux)`
   - Zero-copy streaming — no in-memory buffering of full body

6. Handle URL-encoded slashes (AC #6):
   - Do NOT use `pathVariable()` for path tail extraction (it decodes)
   - Instead, parse `request.uri().getRawPath()` directly
   - Find the third slash (after /{host}/{port}) and take everything after it
   - If no third slash (bare route), use "/"

7. Error handling:
   - NumberFormatException on port parsing -> 400 Bad Request with message "Invalid port: must be a number" (step 3b)
   - If `portForwardCache.localPortFor()` throws IOException -> return 502 Bad Gateway with error message
   - If WebClient exchange fails (ConnectException, timeout) -> return 502 Bad Gateway
   - Log errors at WARN level

8. Write tests in `src/test/java/com/github/robert2411/proxy/ProxyHandlerTest.java`:
   - Use `WebTestClient` bound to the RouterFunction
   - Mock `PortForwardCache` to return a known port
   - Start a local MockWebServer on that port for upstream simulation
   - Test GET with path remainder and query string preserved (AC #1, #2)
   - Test Host header rewritten (AC #3)
   - Test response status and headers forwarded (AC #4)
   - Test large body streaming (AC #5) -- use a 10+ MB generated body with chunked response
   - Test URL-encoded slash in path not corrupted (AC #6) -- request `/{host}/{port}/path%2Fwith%2Fslashes`
   - Test POST/PUT/DELETE/PATCH methods work (AC #1)
   - **Test bare route `/{host}/{port}` (no trailing path) forwards to `/` on backend** (route gap fix)
   - **Test non-numeric port returns 400 Bad Request** (NFE handling)
   - **Test port with leading zeros, negative numbers, or overflow returns 400** (edge cases)

9. Add `com.squareup.okhttp3:mockwebserver` test dependency to pom.xml for upstream HTTP mocking
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Analysis complete. Plan ready. No blockers. Depends on TASK-4 (PortForwardCache must be implemented first).

Self-review complete. Plan covers all 6 AC: #1 all HTTP methods via WebClient.method(), #2 raw query string preserved, #3 Host header rewrite, #4 status+headers forwarded, #5 streaming via DataBuffer flux (no buffering), #6 raw path parsing avoids double-decode. Dependency on TASK-4 is explicit (PortForwardCache injected). Error handling covers IOException from cache and WebClient failures. Test strategy uses MockWebServer for deterministic upstream simulation. No gaps or unverified assumptions.

🔍 PLAN REVIEW CONCERNS:
- Concern #1 (Step 2/6): Route pattern "/{host}/{port}/**" in Spring WebFlux PathPatternParser requires at least one more path segment or trailing slash after /{port}. A request to /{host}/{port} (no trailing path) will NOT match and will 404. Plan should either add a second route "/{host}/{port}" or confirm that "/**" matches empty remainder (it does not by default). Add explicit handling or document the constraint.
- Concern #2 (Step 3b): Integer.parseInt(port) throws NumberFormatException for non-numeric port values. Error handling (step 7) only covers IOException and WebClient failures. The plan should catch NFE and return 400 Bad Request with a descriptive message, rather than letting it bubble as a 500 Internal Server Error.

Verdict: Plan needs revision — route matching gap and unhandled parse exception must be addressed before implementation.

Plan revised to address Plan Reviewer concerns:
- FIXED Concern #1: Added second route pattern "/{host}/{port}" (step 2b) to handle requests with no trailing path. Path extraction logic (step 3f, step 6) now explicitly handles "no third slash" case by defaulting to "/". New test case added (step 8) to verify bare route works.
- FIXED Concern #2: Added explicit NumberFormatException handling (step 3b, step 7). parseInt is wrapped in try-catch, returning 400 Bad Request with descriptive message. Added test cases for non-numeric port, leading zeros, negative numbers, and overflow.

Self-review complete. Plan covers all AC. No gaps or unverified assumptions.

✅ PLAN APPROVED — plan is complete, all AC covered, no ambiguity
- Steps verified: 9
- AC mapped: 6/6 (#1 all methods via WebClient.method(), #2 raw query preserved, #3 Host rewrite, #4 status+headers forwarded, #5 DataBuffer streaming, #6 raw path parsing)
- Prior concerns resolved: dual route patterns for bare path (concern #1), NFE caught with 400 response (concern #2)
- Source code consistency verified: Spring @Component conventions, reactive patterns, PortForwardCache dependency correct

- Implemented ProxyHandler with WebFlux functional router
- Implemented ProxyRouterConfig with dual route patterns (/{host}/{port}/** and /{host}/{port})
- All 15 unit tests passing covering all 6 AC
- Uses toEntityFlux for streaming proxy (no full-body buffering)
- URL-encoded slashes preserved via raw path extraction
- Host header rewritten, status+headers forwarded verbatim
- 10MB+ streaming test passes
- Added mockwebserver test dependency

All AC/DoD checked. Ready for QA.

❌ QA REJECTED: proxy header handling and error exposure need hardening.

🔍 QA REVIEW FINDINGS:
- Issue #1: [High] Inbound hop-by-hop headers are forwarded upstream almost verbatim (only Host is removed), which violates proxy semantics and can cause request smuggling/intermediary issues (src/main/java/com/github/robert2411/proxy/ProxyHandler.java:86-95).
- Issue #2: [Medium] Outbound filtering removes only Transfer-Encoding and Content-Length, but other hop-by-hop headers (Connection, Keep-Alive, TE, Trailer, Upgrade, Proxy-Authenticate, Proxy-Authorization) should also be stripped (src/main/java/com/github/robert2411/proxy/ProxyHandler.java:103-107,154-157).
- Issue #3: [Low] 502 responses include raw exception messages, potentially leaking internal network/runtime details to clients (src/main/java/com/github/robert2411/proxy/ProxyHandler.java:113-116).
- Issue #4: [Low] Test suite does not explicitly cover HEAD/OPTIONS method passthrough despite “all methods” intent (src/test/java/com/github/robert2411/proxy/ProxyHandlerTest.java).

Verification:
- AC/DoD checkboxes: complete
- Targeted tests rerun: mvn test -Dtest="PortForwardCacheTest,ProxyHandlerTest" (pass)

Verdict: Fix required before approval.

Fixed QA findings:
- Issue #1 (High): Added full hop-by-hop header filtering for inbound requests (Connection, Keep-Alive, Proxy-Authenticate, Proxy-Authorization, TE, Trailer, Transfer-Encoding, Upgrade)
- Issue #2 (Medium): Extended outbound isHopByHopHeader to cover all RFC 2616 hop-by-hop headers
- Issue #3 (Low): Sanitized 502 error messages — no internal details exposed to clients
- Issue #4 (Low): Added HEAD and OPTIONS passthrough tests + hop-by-hop stripping test

All 18 tests passing. Re-submitting for QA.

✅ QA APPROVED — all tests passing, no regressions
- Re-review result: previous findings resolved
  - High/Medium: hop-by-hop headers now filtered for inbound and outbound paths using RFC set (+ Content-Length handling)
  - Low: 502 responses now use sanitized generic messages
  - Low: HEAD/OPTIONS passthrough tests added; hop-by-hop stripping test added
- AC/DoD: Complete
- Security: No new issues found
- Verification: mvn test -Dtest="PortForwardCacheTest,ProxyHandlerTest" (pass, 29/29)

✅ Milestone M2 complete. All tasks implemented and QA approved. Awaiting Security and Documentation routing by Manager.

✅ SECURITY APPROVED — static audit complete, zero vulnerabilities identified
- Files reviewed: ProxyHandler.java, ProxyRouterConfig.java, ProxyHandlerTest.java, pom.xml
- Checks: OWASP Top 10, SSRF, path traversal, host header injection, request smuggling, open redirect, resource exhaustion, input validation, hop-by-hop headers, ReDoS
- Notes:
  • SSRF mitigated: upstream connections go only to 127.0.0.1 (loopback) via SSH port forwards; host param gated by SshSessionManager (SSH config aliases only)
  • Port validated 1-65535 with 400 on failure (line 60-68)
  • Hop-by-hop headers stripped inbound and outbound per RFC 2616 (line 33-36, 99, 115)
  • Error messages sanitized — no internal details leaked (lines 77, 126)
  • Streaming via toEntityFlux(DataBuffer.class) — no full-body memory buffering
  • Raw path extraction preserves URL-encoding without double-decode (line 81-82)
  • Host header set to user-controlled host:port (line 104) — acceptable since Spring/Netty reject control chars at transport layer and value is single path segment (no slashes/CRLF)
  • maxInMemorySize(-1) on WebClient codecs is acceptable given streaming design (body never buffered as single unit)
  • No regex patterns — zero ReDoS risk
  • No authentication layer present — noted as architectural deferral, not a vulnerability introduced by this task

✅ DOCUMENTATION COMPLETE
- Created: backlog/docs/doc-5 - Proxy-Subsystem-Architecture.md (new architecture doc for proxy subsystem: ProxyHandler, ProxyRouterConfig, dual route patterns, streaming, hop-by-hop filtering, security posture, test strategy)
- Updated: backlog/docs/doc-4 - Tech-Stack-and-Dependencies.md (added mockwebserver 4.12.0 test dependency)
- No new decision record needed: architectural choices (streaming, dual routes, raw path extraction) are implementation details of the existing WebFlux+sshj decision (decision-3)

Squash dry-run output:
Nothing to squash.

✅ COMMIT COMPLETE: task-5: Implement ProxyHandler and RouterFunction — path routing, body streaming, Host header rewrite
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Implemented ProxyHandler and RouterFunction — a WebFlux functional router that proxies all HTTP methods through SSH port forwards with streaming body support.

Changes:
- src/main/java/com/github/robert2411/proxy/ProxyHandler.java — @Component handler that extracts host/port from URL path, resolves local forwarded port via PortForwardCache, builds upstream URI from raw path (preserving URL-encoded chars), streams request/response via WebClient with toEntityFlux for zero-copy body streaming.
- src/main/java/com/github/robert2411/proxy/ProxyRouterConfig.java — @Configuration with dual route patterns: /{host}/{port}/** and /{host}/{port} to handle both cases.
- src/test/java/com/github/robert2411/proxy/ProxyHandlerTest.java — 15 unit tests with MockWebServer covering all 6 AC: all HTTP methods, path+query preservation, Host header rewrite, response status/headers forwarding, 11MB streaming test, URL-encoded slash preservation.
- pom.xml — Added mockwebserver 4.12.0 test dependency.

Architectural decisions:
- Used retrieve().toEntityFlux(DataBuffer.class) instead of exchangeToMono for proper streaming (exchangeToMono releases connection before body consumption)
- Excluded Content-Length and Transfer-Encoding from forwarded headers (Spring WebFlux manages these)
- Port validation with 400 Bad Request for non-numeric/out-of-range values
- defaultStatusHandler configured to not throw on error status codes (forwards upstream errors as-is)
<!-- SECTION:FINAL_SUMMARY:END -->
