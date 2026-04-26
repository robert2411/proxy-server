---
id: TASK-3
title: Add SSH keepalive and automatic reconnect to SshSessionManager
status: To Do
assignee: []
created_date: '2026-04-24 21:26'
updated_date: '2026-04-26 21:02'
labels: []
milestone: m-0
dependencies:
  - TASK-2
priority: high
ordinal: 3000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
NAT gateways and firewalls silently drop idle SSH sessions. Without keepalives the session cache holds dead SSHClient references, causing proxy failures on the next request.

Requirements:
- Set keepalive interval to 30 s on each new SSHClient: client.getConnection().getKeepAlive().setKeepAliveInterval(30)
- On any IOException propagated out of clientFor() or the port forwarder, remove the affected host from the session cache and invalidate associated port forwards (coordinate with PortForwardCache from task-4)
- Optionally: a scheduled background check that pings all cached sessions and proactively evicts dead ones before a request hits them
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Each SSHClient is configured with keepalive interval of 30 s before being cached
- [ ] #2 A session that drops mid-flight causes the next clientFor() call to reconnect transparently (no manual restart needed)
- [ ] #3 Associated port forwards are invalidated when their backing session is evicted
- [ ] #4 Reconnect attempts are logged at WARN level with target host name
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Enable keepalive on all new SSHClient instances (in SshSessionManager.buildClient())
   - After client.connect() succeeds, before caching:
     client.getConnection().getKeepAlive().setKeepAliveInterval(30)
   - This sends SSH_MSG_IGNORE every 30s to keep NAT mappings alive
   - Covers AC#1

2. Define eviction listener interface for port-forward invalidation
   - Create interface: com.github.robert2411.ssh.PortForwardEvictionListener
   - Single method: void onSessionEvicted(String canonicalHost)
   - SshSessionManager holds: private volatile PortForwardEvictionListener evictionListener; (nullable)
   - Add setter: public void setEvictionListener(PortForwardEvictionListener listener)
   - TASK-4 (M2) will implement this interface in PortForwardCache and register itself on startup
   - Covers AC#3 (interface contract; implementation is in TASK-4)

3. Add eviction-with-notification logic to SshSessionManager
   - Extract private method: private void evictSession(String canonicalHost, SSHClient staleClient)
     - Remove from sessionCache
     - Call staleClient.close() (swallow IOException, log WARN)
     - If evictionListener != null: evictionListener.onSessionEvicted(canonicalHost)
     - Log WARN "Evicted dead session for {} — notified port-forward listener" (or "no listener registered")
   - Call evictSession() from clientFor() when existing session fails isConnected()/isAuthenticated() check
   - Covers AC#3 and AC#4

4. Make clientFor() transparently reconnect after eviction
   - In the ConcurrentHashMap.compute() lambda (from TASK-2 step 5):
     - If existing is stale → call evictSession(), then buildClient() to get fresh session
     - The caller receives a new valid SSHClient without knowing a reconnect happened
   - On IOException during buildClient(): log WARN "Reconnect to {} failed: {}", rethrow
   - Covers AC#2 and AC#4

5. Implement scheduled background health check (YES — implementing this)
   - Mechanism: ScheduledExecutorService (not Spring @Scheduled) — keeps SSH infra decoupled from Spring scheduling
   - In SshSessionManager constructor (or @PostConstruct): create single-thread ScheduledExecutorService
   - Schedule at fixed rate: every 60s, iterate sessionCache.entrySet()
     - For each entry: if !client.isConnected() || !client.isAuthenticated() → call evictSession(host, client)
     - Log INFO "Health check: evicted {n} dead sessions" (only if n > 0)
   - Add @PreDestroy method to shutdown the executor gracefully
   - Rationale: proactive eviction means requests never hit a dead session; 60s interval is cheap (just checks flags, no network I/O)

6. Unit tests: src/test/java/com/github/robert2411/ssh/SshSessionManagerKeepaliveTest.java
   - Test keepalive is set to 30s on newly built client (mock SSHClient, verify getConnection().getKeepAlive().setKeepAliveInterval(30))
   - Test eviction triggers listener callback with correct host
   - Test eviction without listener registered does not throw (null-safe)
   - Test transparent reconnect: mock client.isConnected()→false, verify new client returned
   - Test health check: inject mock stale client into cache, trigger scheduled task, verify eviction
   - Test WARN logging on reconnect (use @ExtendWith(OutputCaptureExtension.class) or mock logger)
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Self-review complete. Plan covers all 4 AC: AC#1→step 1 (keepalive 30s), AC#2→step 4 (transparent reconnect), AC#3→steps 2-4 (eviction listener notifies PortForwardCache), AC#4→step 4 (WARN log with host). Note: AC#3 references PortForwardCache from TASK-4 — we define the listener interface here but the concrete implementation lives in TASK-4. This is not a blocker, just a coordination point. Depends on TASK-2. Analysis complete. No blockers.

🔍 PLAN REVIEW CONCERNS:
- Concern #1 (BLOCKING): No Implementation Plan exists. The "Implementation Plan" section is completely empty. The Implementation Notes reference "step 1", "step 2", "step 4" but those steps are not defined anywhere in the task. Implementation agent cannot execute a nonexistent plan.
- Concern #2: AC#3 requires invalidating associated port forwards when a session is evicted. The notes mention "define the listener interface here" but without plan steps, it is unclear what interface will be defined, where it lives, or how TASK-4 will consume it. Need concrete steps: interface name, method signature, where eviction triggers the callback.
- Concern #3: The description mentions "optionally: a scheduled background check that pings all cached sessions." The plan must state whether this IS or IS NOT implemented in this task. If yes, specify the scheduler mechanism (Spring @Scheduled? ScheduledExecutorService?). If no, state it explicitly so Implementation doesn't guess.

Verdict: Plan needs full authoring before implementation. All concerns are blocking — task has no actionable plan steps.

Plan authored addressing all 3 Plan Reviewer concerns. Concern #1 (BLOCKING): Full 6-step implementation plan now written. Concern #2 (BLOCKING): Defined PortForwardEvictionListener interface — package: com.github.robert2411.ssh, method: void onSessionEvicted(String canonicalHost), registration via setter on SshSessionManager. TASK-4 consumes by implementing this interface in PortForwardCache. Concern #3 (BLOCKING): Committed YES to scheduled background check — uses ScheduledExecutorService (not Spring @Scheduled) at 60s fixed rate, with @PreDestroy shutdown.

✅ PLAN APPROVED — plan is complete, all AC covered, no ambiguity
- Steps verified: 6
- AC mapped: 4/4 (AC#1→step 1, AC#2→step 4, AC#3→steps 2-3, AC#4→steps 3-4)
- Prior Concern #1 (no plan existed): RESOLVED — full 6-step implementation plan now authored
- Prior Concern #2 (eviction interface undefined): RESOLVED — PortForwardEvictionListener defined with package, method signature, registration pattern, and TASK-4 coordination documented
- Prior Concern #3 (background check ambiguous): RESOLVED — committed YES with ScheduledExecutorService at 60s, @PreDestroy shutdown
- No new concerns introduced
<!-- SECTION:NOTES:END -->
