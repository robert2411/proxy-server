---
id: TASK-7
title: Bound forwarder thread pool and add actuator health indicator
status: Done
assignee:
  - '@myself'
created_date: '2026-04-24 21:27'
updated_date: '2026-04-26 22:40'
labels: []
milestone: m-2
dependencies:
  - TASK-4
priority: medium
ordinal: 2000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Each LocalPortForwarder.listen() blocks one thread indefinitely. Using an unbounded pool risks OOM under load or misconfiguration. Bound the pool to a configurable maximum.

Also add a Spring Boot HealthIndicator that reports UP only when the SSH session manager has at least one live session (or reports details per-target). This surfaces SSH connectivity issues in /actuator/health.

Implementation notes:
- Expose pool size as a config property (e.g. proxy.ssh.forwarder-threads=50)
- Wire the bounded ExecutorService as a @Bean and inject into PortForwardCache
- HealthIndicator: check all cached SSHClient.isConnected() && isAuthenticated(); report DOWN with details if any are dead
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Forwarder thread pool size is configurable via application.properties/yml
- [x] #2 Thread pool is bounded — attempting to open more forwarders than pool size fails fast with a clear error rather than spinning up unbounded threads
- [x] #3 GET /actuator/health includes a ssh-proxy component with UP/DOWN status
- [x] #4 Health indicator reports details (per-host status) when health show-details is enabled
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Make forwarder thread pool size configurable
   - File: src/main/java/com/github/robert2411/proxy/PortForwardConfig.java
   - Replace hardcoded PORT_FORWARD_POOL_SIZE = 16 with @Value("${proxy.ssh.forwarder-threads:16}") int poolSize constructor parameter
   - Use Executors.newFixedThreadPool(poolSize, factory) — the pool is already bounded at 16, this just makes it configurable
   - File: src/main/resources/application.yml — add proxy.ssh.forwarder-threads: 16 as default

2. Make thread pool reject tasks when saturated (fail-fast)
   - Replace Executors.newFixedThreadPool() with new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), factory)
   - A SynchronousQueue with no spare threads means submit() throws RejectedExecutionException when all threads are busy
   - Alternative: use a bounded LinkedBlockingQueue(0) — SynchronousQueue is cleaner for fail-fast
   - Wrap the RejectedExecutionException in PortForwardCache.createForwardEntry() catch block to surface a clear error message: "Port forwarder thread pool exhausted (max=N). Increase proxy.ssh.forwarder-threads or reduce active forwards."
   - File: src/main/java/com/github/robert2411/proxy/PortForwardCache.java — catch RejectedExecutionException in createForwardEntry(), close ServerSocket, throw IOException with descriptive message

3. Create SshHealthIndicator @Component
   - File: src/main/java/com/github/robert2411/ssh/SshHealthIndicator.java
   - Implements org.springframework.boot.actuate.health.HealthIndicator
   - Inject SshSessionManager
   - doHealthCheck(Health.Builder builder):
     a. Iterate sshSessionManager.cachedHosts()
     b. For each host, get the SSHClient via sessionCache (need read-only accessor) — check isConnected() && isAuthenticated()
     c. If all sessions healthy → Health.up().withDetail("sessions", detailsMap).build()
     d. If any session dead → Health.down().withDetail("sessions", detailsMap).build()
     e. If zero sessions → Health.up().withDetail("sessions", "none cached").build() (no sessions is normal at startup)
   - Details map: Map<String, String> per host → "UP" or "DOWN (disconnected)" / "DOWN (unauthenticated)"

4. Expose session status on SshSessionManager for HealthIndicator
   - File: src/main/java/com/github/robert2411/ssh/SshSessionManager.java
   - Add method: public Map<String, Boolean> sessionStatus()
     Returns map of host → (isConnected && isAuthenticated) for each cached session
   - This avoids exposing raw SSHClient references outside the manager
   - cachedHosts() already exists; add a method that returns status per host

5. Configure health endpoint to show details
   - File: src/main/resources/application.yml — add under management:
     endpoint.health.show-details: when-authorized (or always for dev)
   - The health indicator auto-registers as "sshProxy" component (Spring Boot uses bean name minus "HealthIndicator")
   - Rename bean to sshProxyHealthIndicator so it appears as "sshProxy" in /actuator/health

6. Write unit test: SshHealthIndicatorTest
   - File: src/test/java/com/github/robert2411/ssh/SshHealthIndicatorTest.java
   - Mock SshSessionManager.sessionStatus() to return various states
   - Verify Health.up() when all sessions connected
   - Verify Health.down() when any session disconnected
   - Verify details map contains per-host status
   - Verify empty cache returns UP

7. Write unit test: PortForwardConfig pool exhaustion
   - File: src/test/java/com/github/robert2411/proxy/PortForwardConfigTest.java
   - Create pool of size 1, submit 2 blocking tasks, verify second throws RejectedExecutionException
   - Verify configurable pool size is respected

8. Update PortForwardCacheTest for rejection handling
   - Verify that when executor rejects a task, localPortFor() throws IOException with descriptive "pool exhausted" message
   - Verify ServerSocket is properly closed on rejection
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Self-review complete. Plan covers all 4 ACs:
- AC#1: Step 1 makes pool size configurable via proxy.ssh.forwarder-threads property
- AC#2: Step 2 switches to SynchronousQueue for fail-fast rejection; PortForwardCache catches and wraps as IOException
- AC#3: Step 3 creates SshHealthIndicator → appears as sshProxy component in /actuator/health
- AC#4: Step 5 configures show-details; step 3 includes per-host detail map
Note: SshSessionManager needs a new sessionStatus() method (step 4) to avoid leaking SSHClient references — cachedHosts() exists but no per-host status accessor yet. This is a minor addition, not a blocker.
Analysis complete. Plan ready. No blockers.

✅ PLAN APPROVED — plan is complete, all AC covered, no ambiguity
- Steps verified: 8
- AC mapped: 4/4
  - AC#1 → Step 1 (configurable @Value property)
  - AC#2 → Step 2 (SynchronousQueue + RejectedExecutionException handling)
  - AC#3 → Step 3 (SshHealthIndicator auto-registers in /actuator/health)
  - AC#4 → Steps 3+5 (per-host detail map + show-details config)
- sessionStatus() addition to SshSessionManager (step 4) is clean — avoids leaking SSHClient refs
- Pool exhaustion error path (step 2) correctly closes ServerSocket before re-throwing
- Note for implementer: plan says "implements HealthIndicator" but uses doHealthCheck(builder) pattern — use AbstractHealthIndicator instead

- PortForwardConfig now accepts @Value("${proxy.ssh.forwarder-threads:16}") pool size
- Switched from Executors.newFixedThreadPool to ThreadPoolExecutor with SynchronousQueue for fail-fast
- PortForwardCache catches RejectedExecutionException with descriptive "pool exhausted" message
- SshSessionManager.sessionStatus() added for health indicator
- SshHealthIndicator created with per-host UP/DOWN details
- application.yml updated with proxy.ssh.forwarder-threads and show-details config
- Tests: SshHealthIndicatorTest (5), PortForwardConfigTest (4), PortForwardCacheTest pool exhaustion test added
- All 68 non-context tests pass

All AC/DoD checked. Ready for QA.

❌ QA REJECTED: Fix required before approval.

🔍 QA REVIEW FINDINGS:
- Issue #1: [Medium] Rejection-path error handling can throw ClassCastException instead of the intended IOException. In PortForwardCache.createForwardEntry(), the RejectedExecutionException handler casts ExecutorService to ThreadPoolExecutor when building the message; if a different ExecutorService implementation is injected, the cast fails and masks the real saturation error (src/main/java/com/github/robert2411/proxy/PortForwardCache.java:162-164).
- Issue #2: [High] Health endpoint details are configured as always visible, which exposes internal SSH host/status details to unauthenticated callers in this app (no spring-boot-starter-security present). This is information disclosure risk and should be restricted (e.g., when-authorized, profile-specific override, or secured actuator endpoint) (src/main/resources/application.yml:15, pom.xml:24-70).

Verdict: Fix required before approval.

QA fixes applied:
- Issue #1: Removed unsafe cast to ThreadPoolExecutor. Error message no longer attempts to read pool size from executor.
- Issue #2: Changed show-details from "always" to "when-authorized" to prevent information disclosure.
All 21 tests still pass. Re-submitting for QA.

✅ QA APPROVED — all tests passing, no regressions
- Verified Issue #1 fixed: no unsafe ThreadPoolExecutor cast in rejection path (src/main/java/com/github/robert2411/proxy/PortForwardCache.java:155-164)
- Verified Issue #2 fixed: health show-details set to when-authorized (src/main/resources/application.yml:15)
- AC/DoD: Complete
- Code quality: Good
- Security: No issues
- Spelling: Clean

✅ SECURITY APPROVED — static audit complete, zero vulnerabilities identified
- Files reviewed: PortForwardConfig.java, SshHealthIndicator.java, SshSessionManager.java (sessionStatus), PortForwardCache.java (rejection path), application.yml
- Checks: OWASP Top 10, path traversal, ReDoS, input validation, information leakage, resource exhaustion
- Notes: Health show-details=when-authorized prevents info disclosure without Spring Security (details hidden from all unauthenticated callers). Thread pool bounded with SynchronousQueue + fail-fast rejection. sessionStatus() returns Map<String,Boolean> without leaking SSHClient references.

✅ DOCUMENTATION COMPLETE
- Updated: backlog/docs/doc-2 - SSH-Subsystem-Architecture.md
  - Added SshHealthIndicator component section (new actuator health endpoint)
  - Updated SshSessionManager to document sessionStatus() accessor
  - Updated PortForwardConfig to reflect configurable pool size + SynchronousQueue fail-fast
  - Updated Configuration section with proxy.ssh.forwarder-threads and show-details properties
  - Updated Test Strategy with SshHealthIndicatorTest, PortForwardConfigTest, and updated PortForwardCacheTest counts
- No new decision record required (SynchronousQueue fail-fast and sessionStatus() encapsulation are standard engineering patterns, not architectural trade-offs)

Squash dry-run output:
Nothing to squash.

✅ COMMIT COMPLETE: task-7: Bound forwarder thread pool and add actuator health indicator
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Bounded the forwarder thread pool and added an SSH health indicator.

Changes:
- PortForwardConfig.java: Pool size now configurable via proxy.ssh.forwarder-threads (default 16). Uses ThreadPoolExecutor with SynchronousQueue for fail-fast rejection.
- PortForwardCache.java: Catches RejectedExecutionException with descriptive "pool exhausted" error, closes ServerSocket on rejection.
- SshSessionManager.java: Added sessionStatus() method returning per-host connection status without leaking SSHClient references.
- SshHealthIndicator.java (new): AbstractHealthIndicator reporting UP/DOWN with per-host details in /actuator/health.
- application.yml: Added proxy.ssh.forwarder-threads config and health show-details: when-authorized.

Tests:
- SshHealthIndicatorTest: 5 tests covering UP/DOWN/empty states and per-host details
- PortForwardConfigTest: 4 tests covering configurable size, rejection, daemon threads
- PortForwardCacheTest: Added pool exhaustion test
- Total: 21 targeted tests pass
<!-- SECTION:FINAL_SUMMARY:END -->
