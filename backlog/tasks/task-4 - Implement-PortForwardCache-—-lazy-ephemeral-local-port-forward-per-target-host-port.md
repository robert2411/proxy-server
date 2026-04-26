---
id: TASK-4
title: >-
  Implement PortForwardCache — lazy ephemeral local port forward per target
  host:port
status: In Progress
assignee:
  - '@myself'
created_date: '2026-04-24 21:27'
updated_date: '2026-04-26 21:49'
labels: []
milestone: m-1
dependencies:
  - TASK-2
priority: high
ordinal: 1000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
On first request to a (targetHost, targetPort) pair, open a LocalPortForwarder bound to an ephemeral loopback port (ServerSocket(0, 0, loopback)). Cache the local port. Subsequent requests to the same target reuse it. This lets WebClient target a plain 127.0.0.1:<port> so HTTP keepalive, chunked transfer, and gzip all work without custom stream wiring.

Design decisions (from design discussion):
- Cache key: targetHost + ":" + targetPort
- Use computeIfAbsent for thread-safe lazy creation
- LocalPortForwarder.listen() blocks a thread — submit to a bounded executor (wired in task-6, but prepare the injection point here)
- Expose invalidate(host, port) for SshSessionManager to call on session eviction (task-3)
- If the forwarder thread dies, the cached port becomes stale — detect and recreate on next access
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 localPortFor(host, port) returns a stable loopback port number for a given target
- [x] #2 Second call to localPortFor with same args returns same port without opening a new forwarder
- [x] #3 invalidate(host, port) removes the entry so next call opens a fresh forwarder
- [x] #4 Forwarder threads are submitted to an injected Executor (not newCachedThreadPool)
- [x] #5 Dead forwarder (thread exited) is detected and replaced on next localPortFor call
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Create class `com.github.robert2411.proxy.PortForwardCache` implementing `PortForwardEvictionListener` as a Spring `@Component`
   - File: `src/main/java/com/github/robert2411/proxy/PortForwardCache.java`
   - Inject `SshSessionManager` and an `Executor` (constructor injection)
   - Register `this` as eviction listener on SshSessionManager in `@PostConstruct`

2. Define internal record/value class `ForwardEntry` holding: localPort (int), ServerSocket reference, Future<?> reference (for liveness check), targetHost, targetPort

3. Implement cache storage:
   - Field: `ConcurrentHashMap<String, ForwardEntry> cache` keyed by `targetHost + ":" + targetPort`
   - Helper method `cacheKey(String host, int port)` returns the concatenated key

4. Implement `localPortFor(String targetHost, int targetPort)` (AC #1, #2):
   - Use `cache.compute(cacheKey, (key, existing) -> { ... })` for thread-safe lazy creation
   - Inside compute: if existing != null and forwarder future is NOT done -> return existing (AC #2)
   - If existing is null OR future.isDone() (AC #5) -> create new ForwardEntry:
     a. Get SSHClient via `sshSessionManager.clientFor(targetHost)`
        — TOPOLOGY ASSUMPTION: Each `{host}` in the proxy URL maps 1:1 to an SSH config Host alias.
          `clientFor(targetHost)` opens (or reuses) an SSH session directly to that backend server
          (potentially via ProxyJump hops as resolved by SshSessionManager).
          The SSH session terminates ON the target machine.
     b. Open `new ServerSocket(0, 0, InetAddress.getLoopbackAddress())` to get ephemeral port
     c. Create LocalPortForwarder with `Parameters("localhost", localPort, "localhost", targetPort)`
        — The FIRST "localhost" + localPort = local bind address (loopback on this proxy machine)
        — The SECOND "localhost" + targetPort = remote forward destination as seen by the SSH server.
          Because the SSH session connects directly to the target backend, "localhost" on that
          remote machine IS the backend service. Using the hostname string (e.g. "my-server")
          would rely on DNS resolution on the remote machine, which is fragile and may resolve
          to a different interface or fail entirely.
     d. Submit `forwarder.listen(serverSocket)` to the injected Executor (AC #4)
     e. Store localPort, serverSocket, future in ForwardEntry
   - Return `entry.localPort()`

5. Implement dead-forwarder detection (AC #5):
   - In the compute lambda above, check if existing entry's Future.isDone()\n   - If done -> close old ServerSocket, log WARN, recreate forwarder\n   - This handles the case where the forwarder thread exits unexpectedly\n\n6. Implement `invalidate(String targetHost, int targetPort)` (AC #3):\n   - Remove entry from cache via `cache.remove(cacheKey(targetHost, targetPort))`\n   - If entry existed: cancel the Future, close the ServerSocket\n   - Log the invalidation at INFO level\n\n7. Implement `PortForwardEvictionListener.onSessionEvicted(String canonicalHost)`:\n   - Iterate all cache entries\n   - For each entry whose targetHost matches canonicalHost, call `invalidate(host, port)`\n   - This ensures all port forwards for an evicted SSH session are cleaned up\n\n8. Implement `@PreDestroy` cleanup:\n   - Iterate all cache entries, cancel futures, close server sockets\n   - Clear the cache\n\n9. Expose Executor injection point:\n   - Constructor parameter: Executor executor (qualified or from config bean)\n   - Create `src/main/java/com/github/robert2411/proxy/PortForwardConfig.java` with a bounded `@Bean` ExecutorService (fixed pool of 16 threads, daemon threads, named "port-fwd-")\n   - TASK-6 may tune this later, but provide working default now\n\n10. Write unit tests in `src/test/java/com/github/robert2411/proxy/PortForwardCacheTest.java`:\n    - Test localPortFor returns a port > 0 (AC #1)\n    - Test same args return same port (AC #2) -- mock SSHClient/LocalPortForwarder\n    - Test invalidate causes fresh forwarder on next call (AC #3)\n    - Test executor is used (AC #4) -- verify submit() called on mock Executor\n    - Test dead forwarder is replaced (AC #5) -- mark Future as done, verify new port allocated\n    - Test onSessionEvicted invalidates all entries for that host\n\n## Network Topology Assumption (documented per plan review)\n\nThis plan assumes the following network topology:\n- Each `{host}` segment in proxy URLs (e.g. `/myserver/8080/api`) maps to an SSH config Host alias\n- `SshSessionManager.clientFor(host)` establishes an SSH session directly TO that backend server\n  (via direct connect or ProxyJump chain — transparent to PortForwardCache)\n- The SSH session terminates on the target machine itself\n- Port forwarding therefore forwards to `localhost:{port}` on the remote end, because\n  "localhost" on the remote machine IS the backend service listening on that port\n- This is NOT a bastion/jumpbox topology where one SSH session serves multiple backends.\n  Each unique target host gets its own SSH session (cached by SshSessionManager).
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Analysis complete. Plan ready. No blockers.

Self-review complete. Plan covers all 5 AC: #1/#2 via compute-based cache, #3 via invalidate(), #4 via injected Executor, #5 via Future.isDone() check. onSessionEvicted bridges to TASK-3's eviction listener. No unverified assumptions — sshj 0.40.0 LocalPortForwarder.listen(ServerSocket) is blocking and returns void (confirmed in sshj API). No gaps found.

🔍 PLAN REVIEW CONCERNS:
- Concern #1 (Step 4c): LocalPortForwarder Parameters uses targetHost as remoteHost — since clientFor(targetHost) returns an SSH session TO targetHost (possibly via ProxyJump), the remote end of the port forward should be "localhost" or "127.0.0.1" (forwarding to the target machine's own port), not targetHost (which would ask the remote SSH server to resolve its own hostname via DNS — fragile and potentially wrong). Clarify the intended remoteHost value in Parameters.
- Concern #2 (Step 4a): The relationship between SSH session target and port-forward target is ambiguous. If the proxy's {host} maps 1:1 to an SSH config alias (SSH directly to each backend), then remoteHost should be "localhost". If instead a single bastion SSH session serves all targets, then clientFor(targetHost) is wrong — it should be clientFor(bastionAlias). The plan must explicitly state which topology is assumed.

Verdict: Plan needs revision — the remote host parameter and SSH-to-target mapping must be unambiguous for Implementation to execute correctly.

Plan revised to address Plan Reviewer concerns:
- FIXED Concern #1: LocalPortForwarder Parameters now uses "localhost" as remote host (step 4c), with detailed explanation of why targetHost string is wrong (DNS fragility on remote).
- FIXED Concern #2: Network topology explicitly documented in new section at end of plan. Clearly states 1:1 mapping between URL host and SSH config alias, SSH session terminates on target, and port forwarding targets localhost on that machine.

Self-review complete. Plan covers all AC. No gaps or unverified assumptions.

✅ PLAN APPROVED — plan is complete, all AC covered, no ambiguity
- Steps verified: 10
- AC mapped: 5/5 (#1/#2 via compute-based cache, #3 via invalidate(), #4 via injected Executor, #5 via Future.isDone() check)
- Prior concerns resolved: remote host now "localhost" (concern #1), topology documented (concern #2)
- Source code consistency verified: clientFor(), setEvictionListener(), PortForwardEvictionListener all match plan references

- Implemented PortForwardCache with ConcurrentHashMap-based cache
- Implemented PortForwardConfig with bounded 16-thread executor
- All 10 unit tests passing covering all 5 AC
- Dead forwarder detection via Future.isDone() working
- onSessionEvicted properly invalidates all forwards for a host

All AC/DoD checked. Ready for QA.

❌ QA REJECTED: resource-leak risk in port-forward creation.

🔍 QA REVIEW FINDINGS:
- Issue #1: [Medium] Potential ServerSocket leak if forwarder creation fails. In createForwardEntry(), socket is bound before client.newLocalPortForwarder(...) and executor.submit(...), but not closed on exception (src/main/java/com/github/robert2411/proxy/PortForwardCache.java:135-143).
- Issue #2: [Low] Missing regression test for failed forwarder creation/submit path to verify socket cleanup and cache non-poisoning (src/test/java/com/github/robert2411/proxy/PortForwardCacheTest.java).

Verification:
- AC/DoD checkboxes: complete
- Targeted tests rerun: mvn test -Dtest="PortForwardCacheTest,ProxyHandlerTest" (pass)

Verdict: Fix required before approval.

Fixed QA findings:
- Issue #1: Added try-catch around forwarder creation in createForwardEntry() — ServerSocket is closed on any exception
- Issue #2: Added regression test createForwardEntry_closesSocketOnForwarderCreationFailure

All 11 tests passing. Re-submitting for QA.

✅ QA APPROVED — all tests passing, no regressions
- Re-review result: previous findings resolved
  - Medium: createForwardEntry now closes ServerSocket on forwarder creation/submit failure (verified in PortForwardCache.java)
  - Low: regression test added for cleanup on forwarder creation failure (createForwardEntry_closesSocketOnForwarderCreationFailure)
- AC/DoD: Complete
- Security: No new issues found
- Verification: mvn test -Dtest="PortForwardCacheTest,ProxyHandlerTest" (pass, 29/29)
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Implemented PortForwardCache — a ConcurrentHashMap-based cache that lazily creates SSH local port forwards per target host:port pair.

Changes:
- src/main/java/com/github/robert2411/proxy/PortForwardCache.java — Core cache component implementing PortForwardEvictionListener. Uses compute() for thread-safe lazy creation, Future.isDone() for dead-forwarder detection, and integrates with SshSessionManager eviction callbacks.
- src/main/java/com/github/robert2411/proxy/PortForwardConfig.java — @Configuration providing a bounded 16-thread daemon ExecutorService for forwarder threads.
- src/test/java/com/github/robert2411/proxy/PortForwardCacheTest.java — 10 unit tests covering all 5 AC: stable port return, cache reuse, invalidation, executor injection, dead forwarder replacement, session eviction cascading.

Architectural decisions:
- Used record ForwardEntry for immutable cache entries
- PortForwardException unchecked wrapper for IOException inside compute lambda
- Parameters uses "localhost" as remote host (SSH session terminates on target machine)
- @PostConstruct registers this as eviction listener on SshSessionManager
<!-- SECTION:FINAL_SUMMARY:END -->
