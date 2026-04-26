---
id: doc-2
title: SSH Subsystem Architecture
type: other
created_date: '2026-04-26 21:24'
---

# SSH Subsystem Architecture

## Overview

The SSH subsystem lives in `com.github.robert2411.ssh` and provides managed SSH connectivity to remote hosts via `~/.ssh/config`. It is the foundation for all proxy routing — every HTTP request ultimately flows through an SSH direct-tcpip channel managed by this subsystem.

## Components

### SshConfigParser

**File:** `src/main/java/com/github/robert2411/ssh/SshConfigParser.java`

Custom parser for OpenSSH `~/.ssh/config` files. Implements:
- Host block matching (including wildcard patterns with `*` and `?`)
- Directive merging (first-match-wins semantics, matching OpenSSH behavior)
- Case-insensitive keyword normalization (stores canonical casing internally)
- Supported directives: `HostName`, `User`, `Port`, `IdentityFile`, `ProxyJump`

### SshSessionManager

**File:** `src/main/java/com/github/robert2411/ssh/SshSessionManager.java`

Spring `@Component` that manages the lifecycle of SSH sessions. Key behaviors:

- **Fail-fast on startup:** If `~/.ssh/config` is missing or unreadable, throws `BeanCreationException` with an actionable error message. The application cannot operate without SSH config.
- **Session cache:** `ConcurrentHashMap<String, SSHClient>` keyed by canonical hostname (after HostName substitution from config).
- **Per-key locking:** Uses `ConcurrentHashMap.compute()` so connections to different hosts proceed in parallel without blocking each other.
- **Stale eviction:** On cache lookup, checks `isConnected() && isAuthenticated()`. If stale, evicts and rebuilds.
- **ProxyJump resolution:** Recursively resolves multi-hop chains (depth >= 2). Each hop uses `newDirectConnection()` to tunnel through prior hops.
- **Certificate auth:** Uses sshj `loadKeys(identityFilePath)` which automatically picks up `-cert.pub` companion files.
- **Host key verification:** Uses `loadKnownHosts()` from `~/.ssh/known_hosts`. Fails closed if known_hosts is missing/unreadable (throws IOException).
- **Conditional activation:** `@ConditionalOnProperty(name = "ssh.enabled", havingValue = "true", matchIfMissing = true)` allows disabling in test profiles.

### PortForwardCache

**File:** `src/main/java/com/github/robert2411/proxy/PortForwardCache.java`

Spring `@Component` implementing `PortForwardEvictionListener`. Lazily creates SSH local port forwards per target host:port pair. Key behaviors:

- **Cache storage:** `ConcurrentHashMap<String, ForwardEntry>` keyed by `targetHost + ":" + targetPort`
- **Per-key locking:** Uses `ConcurrentHashMap.compute()` (same pattern as SshSessionManager) for thread-safe lazy creation
- **Loopback-only binding:** `ServerSocket(0, 0, InetAddress.getLoopbackAddress())` — ephemeral port on loopback interface only (security: no external exposure)
- **Localhost remote host:** Port forwarding Parameters use `"localhost"` as remote destination because the SSH session terminates on the target machine (1:1 SSH-to-target topology)
- **Dead forwarder detection:** Checks `Future.isDone()` on each access — if the forwarder thread exited, closes the stale ServerSocket and recreates
- **Resource cleanup:** `try-catch` in `createForwardEntry()` closes ServerSocket on forwarder creation failure; `@PreDestroy` closes all entries on shutdown
- **Eviction integration:** Implements `PortForwardEvictionListener.onSessionEvicted()` — invalidates all cached forwards for the evicted host. Registers itself via `@PostConstruct`

API:
- `localPortFor(String targetHost, int targetPort)` → returns stable loopback port number
- `invalidate(String targetHost, int targetPort)` → removes cached forward, closes resources
- `onSessionEvicted(String canonicalHost)` → cascading invalidation for all forwards to that host

### PortForwardConfig

**File:** `src/main/java/com/github/robert2411/proxy/PortForwardConfig.java`

Spring `@Configuration` providing a bounded `ExecutorService` bean for forwarder listener threads:
- Fixed pool of 16 daemon threads (named `"port-fwd-"`)
- Injected into PortForwardCache for `forwarder.listen(serverSocket)` submission
- Bounded to prevent unbounded thread growth from many forwarded targets

## Connection Flow

```
clientFor(targetHost)
  → compute() on sessionCache
    → if cached & healthy → return existing
    → else → buildClient(targetHost)
      → parse config for targetHost
      → resolve ProxyJump chain (recursive)
      → connect (direct or via tunnel)
      → loadKnownHosts() verification
      → authenticate with IdentityFile (+cert)
      → return new SSHClient
```

## Thread Safety Model

- `ConcurrentHashMap.compute()` provides per-bucket locking
- Parallel connections to different hosts are non-blocking
- Same-host concurrent requests serialize on the bucket lock (prevents duplicate connections)

## Test Strategy

- **SshConfigParserTest** (11 tests): Parsing, wildcard matching, directive merging, case-insensitive keywords, error handling
- **SshSessionManagerTest** (9 tests): Cache behavior, stale eviction, missing config error, concurrent access, conditional property
- **PortForwardCacheTest** (11 tests): Stable port return, cache reuse, invalidation, executor injection, dead forwarder replacement, session eviction cascading, ServerSocket cleanup on creation failure

## Configuration

- SSH config path: `~/.ssh/config` (hardcoded, follows OpenSSH convention)
- Known hosts path: `~/.ssh/known_hosts`
- Spring property: `ssh.enabled=true|false` (default: true)
- Test profile: `application-test.yml` sets `ssh.enabled: false`

## Related

- [Decision: Custom SshConfigParser over sshj OpenSSHConfig](../decisions/decision-1%20-%20Custom-SshConfigParser-over-sshj-OpenSSHConfig.md)
- [Decision: Loopback-only binding and localhost remote host for port forwarding](../decisions/decision-4%20-%20Loopback-only-binding-and-localhost-remote-host-for-port-forwarding.md)
- Handoff doc (doc-1) — original architecture discussion
