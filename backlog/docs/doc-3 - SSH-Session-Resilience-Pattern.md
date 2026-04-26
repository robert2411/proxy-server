---
id: doc-3
title: SSH Session Resilience Pattern
type: other
created_date: '2026-04-26 21:24'
---

## Overview

SshSessionManager implements a multi-layer resilience strategy for SSH connections that traverse NAT gateways and firewalls which silently drop idle sessions.

## Keepalive (Layer 1 — Prevention)

Every new `SSHClient` is configured with a 30-second keepalive interval before being cached:

```java
client.getConnection().getKeepAlive().setKeepAliveInterval(30);
```

This sends `SSH_MSG_IGNORE` packets every 30s, preventing NAT mapping timeouts.

## Transparent Reconnect (Layer 2 — Reactive Recovery)

When `clientFor()` detects a stale session (`!isConnected() || !isAuthenticated()`), it:
1. Evicts the dead session from the `ConcurrentHashMap` cache
2. Notifies the eviction listener (if registered)
3. Builds a fresh `SSHClient` transparently — callers never see the failure

Reconnect failures are logged at WARN level with the target hostname.

## Scheduled Health Check (Layer 3 — Proactive Eviction)

A `ScheduledExecutorService` (single daemon thread) runs every 60 seconds:
1. Iterates all cached sessions
2. Checks `isConnected()` and `isAuthenticated()` flags (no network I/O)
3. Evicts dead sessions using CAS removal: `sessionCache.remove(host, client)`

The CAS removal (`remove(key, value)`) prevents a race condition where a freshly reconnected session could be accidentally evicted by a concurrent health check that observed the previous dead instance.

## Eviction Listener (Cross-Concern Coordination)

`PortForwardEvictionListener` is a `@FunctionalInterface`:

```java
public interface PortForwardEvictionListener {
    void onSessionEvicted(String canonicalHost);
}
```

- SshSessionManager holds a `volatile` reference (nullable)
- Registration via `setEvictionListener(PortForwardEvictionListener listener)`
- `PortForwardCache` implements this interface and registers itself via `@PostConstruct` on startup (TASK-4)
- Listener exceptions are caught and logged (cannot crash the health check)

## Key Files

| File | Role |
|------|------|
| `SshSessionManager.java` | Session cache, keepalive, reconnect, health check |
| `PortForwardEvictionListener.java` | Eviction callback interface |
| `PortForwardCache.java` | Implements eviction listener; invalidates all forwards for evicted host |
| `SshSessionManagerKeepaliveTest.java` | 8 tests covering all resilience layers |

## Reconnect Metrics Listener (TASK-6)

In addition to the eviction listener, SshSessionManager supports a **reconnect listener** (`BiConsumer<String, SSHClient>`) registered by `ProxyMetrics` via `@PostConstruct`:

- Fires in `clientFor()` when a stale session is detected and replaced
- Increments `ssh.reconnects.total{target}` counter for Prometheus/Grafana visibility
- Independent from the eviction listener — both fire on stale session replacement

This provides operational visibility into SSH session instability without coupling the SSH subsystem to the metrics library.

## Design Constraints

- Health check interval (60s) is hardcoded — suitable for internal proxy with low session count
- Eviction listener is single-consumer (one listener at a time) — sufficient for current architecture
- Volatile listener field ensures safe publication across health-check thread and request threads
