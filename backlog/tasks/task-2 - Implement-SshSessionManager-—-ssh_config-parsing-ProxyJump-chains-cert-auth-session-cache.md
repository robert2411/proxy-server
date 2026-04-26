---
id: TASK-2
title: >-
  Implement SshSessionManager — ssh_config parsing, ProxyJump chains, cert auth,
  session cache
status: To Do
assignee: []
created_date: '2026-04-24 21:26'
updated_date: '2026-04-26 21:02'
labels: []
milestone: m-0
dependencies: []
priority: high
ordinal: 2000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Core SSH lifecycle component. Reads ~/.ssh/config via sshj's OpenSSHConfig, resolves ProxyJump hops recursively, handles certificate auth, and maintains a ConcurrentHashMap cache of live SSHClient sessions keyed by target host.

Design decisions (from design discussion):
- sshj reads IdentityFile and picks up -cert.pub automatically (OpenSSHKeyV1KeyFile)
- ProxyJump: for each hop, call priorClient.newDirectConnection(nextHost, nextPort), then connect the new SSHClient via connectVia()
- Cache key = canonical hostname after ssh_config HostName substitution
- Session validation: existing != null && existing.isConnected() && existing.isAuthenticated()
- Reconnect: on failure, remove from cache and re-enter clientFor() — reconnect is handled by the keepalive task (task-3)

Key sshj classes: OpenSSHConfig, SSHClient, ConfigFile.Entry, DefaultConfig
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 SshSessionManager is a @Component that loads ~/.ssh/config on construction
- [ ] #2 clientFor(targetHost) returns a connected and authenticated SSHClient
- [ ] #3 ProxyJump chains of depth >= 2 are resolved correctly (integration test or manual verification against a test host)
- [ ] #4 IdentityFile from ssh_config is used for authentication; certificate (-cert.pub) is picked up automatically when present
- [ ] #5 Stale/disconnected sessions are evicted from the cache and a fresh session is returned on next call
- [ ] #6 Component logs connection events at INFO and errors at WARN/ERROR
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Create package com.github.robert2411.ssh under src/main/java/
2. Create class SshSessionManager annotated with @Component, @Slf4j (Lombok)
3. Constructor: load ~/.ssh/config with explicit error handling
   - Wrap OpenSSHConfig.parseFile(new File(System.getProperty("user.home"), ".ssh/config")) in try-catch
   - On FileNotFoundException: log ERROR "SSH config not found at ~/.ssh/config. Ensure the file exists and is readable. SshSessionManager cannot operate without it." then throw new BeanCreationException (fail-fast — the app cannot proxy without SSH config)
   - On IOException (permissions, parse errors): log ERROR with cause message, throw BeanCreationException
   - Rationale: fail-fast is correct here because all downstream functionality depends on SSH config. Degraded mode would just defer failures to request time with worse diagnostics.
   - Store as field: private final OpenSSHConfig sshConfig;
   - Log INFO "Loaded SSH config from ~/.ssh/config" on success
4. Declare session cache: private final ConcurrentHashMap<String, SSHClient> sessionCache = new ConcurrentHashMap<>()
5. Implement public method: public SSHClient clientFor(String targetHost) throws IOException
   - Use ConcurrentHashMap.computeIfAbsent pattern for per-key locking:
     sessionCache.compute(canonicalHost, (key, existing) -> { ... })
   - Inside the compute lambda:
     - If existing != null && existing.isConnected() && existing.isAuthenticated() → return existing
     - Otherwise log WARN "Evicting stale session for {}" if existing was non-null
     - Call buildClient(targetHost) to create fresh session
     - Return new client
   - Note: ConcurrentHashMap.compute() locks only the hash bucket for canonicalHost, so connections to different hosts proceed in parallel. This avoids the synchronized bottleneck while remaining thread-safe.
   - Wrap buildClient exceptions: catch IOException inside compute → wrap in CompletionException, unwrap after compute returns, rethrow as IOException
6. Implement private method: private SSHClient buildClient(String targetHost) throws IOException
   - Create SSHClient with DefaultConfig
   - client.addHostKeyVerifier(new PromiscuousVerifier()) — for initial dev; TODO: use KnownHosts
   - Load config entry: sshConfig.getConfig(targetHost)
   - Resolve: hostname = entry.get("HostName") or targetHost, port = entry.get("Port") or 22, user = entry.get("User") or system user
   - Check ProxyJump: entry.get("ProxyJump")
     - If null/empty: client.connect(hostname, port)
     - If set: call resolveProxyJump(proxyJumpValue, client, hostname, port) to connect via chain
   - Authenticate: load IdentityFile from entry.get("IdentityFile") — expand ~ to home dir
     - Create KeyProvider via client.loadKeys(identityFilePath) — sshj auto-loads -cert.pub if present
     - client.authPublickey(user, keyProvider)
   - Log INFO "Connected to {} ({}:{}) as {}" with targetHost, hostname, port, user
   - Return client
7. Implement private method: private void resolveProxyJump(String proxyJump, SSHClient targetClient, String finalHost, int finalPort) throws IOException
   - Split proxyJump by comma to get hop list
   - For first hop: recursively call clientFor(hop1) to get or create jump host session
   - For each subsequent hop: use priorClient.newDirectConnection(nextHost, nextPort)
   - Final hop: targetClient.connectVia(channel) where channel = lastJumpClient.newDirectConnection(finalHost, finalPort)
   - This handles chains of depth >= 2
8. Create unit test: src/test/java/com/github/robert2411/ssh/SshSessionManagerTest.java
   - Test config parsing with a test ssh_config fixture (src/test/resources/test_ssh_config)
   - Test missing config file: verify BeanCreationException is thrown with descriptive message
   - Test that clientFor() with a mock verifies ProxyJump resolution logic
   - Test cache hit: second call returns same instance (no lock contention)
   - Test stale eviction: disconnect a client, next call returns fresh one
   - Test concurrent access: multiple threads calling clientFor() for different hosts proceed in parallel
9. Create test fixture: src/test/resources/test_ssh_config with sample Host entries including ProxyJump
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Self-review complete. Plan covers all 6 AC: AC#1→steps 2-3, AC#2→step 5, AC#3→step 7, AC#4→step 6 (loadKeys auto-picks cert), AC#5→step 5 (stale eviction), AC#6→logging in steps 3,5. No unverified assumptions — sshj OpenSSHConfig API confirmed in Handoff doc. Depends on TASK-1 for sshj dependency. Analysis complete. No blockers.

🔍 PLAN REVIEW CONCERNS:
- Concern #1: Missing error handling for absent ~/.ssh/config — constructor calls OpenSSHConfig.parseFile() unconditionally. If the file is missing, the app crashes on startup with an opaque IOException. Plan should specify: catch FileNotFoundException → log ERROR with actionable message and either fail-fast with clear exit, or start in degraded mode. AC#1 says "loads ~/.ssh/config on construction" but doesn't define failure behaviour.
- Concern #2: Step 5 uses `synchronized` on the entire clientFor() method. While functional, this means ALL target hosts block on a single lock (e.g., connecting to host-B waits while host-A connection times out). Consider documenting this as a known limitation or using per-key locking (ConcurrentHashMap.computeIfAbsent pattern). If intentional trade-off, add a brief comment in the plan acknowledging it.

Verdict: Plan needs minor revision before implementation — address missing-config error path (Concern #1 is blocking; Concern #2 is advisory but should be explicitly acknowledged in the plan).

Plan revised to address Plan Reviewer concerns #1 and #2. Concern #1 (BLOCKING): Added explicit FileNotFoundException/IOException handling in step 3 — fail-fast with BeanCreationException and actionable ERROR log. Concern #2 (Advisory): Replaced synchronized method with ConcurrentHashMap.compute() per-key locking in step 5, allowing parallel connections to different hosts.

✅ PLAN APPROVED — plan is complete, all AC covered, no ambiguity
- Steps verified: 9
- AC mapped: 6/6 (AC#1→steps 2-3, AC#2→step 5, AC#3→step 7, AC#4→step 6, AC#5→step 5, AC#6→logging throughout)
- Prior Concern #1 (missing-config error handling): RESOLVED — step 3 now has explicit FileNotFoundException/IOException handling with fail-fast BeanCreationException
- Prior Concern #2 (synchronized bottleneck): RESOLVED — step 5 uses ConcurrentHashMap.compute() for per-bucket locking, parallel connections confirmed
- No new concerns introduced
<!-- SECTION:NOTES:END -->
