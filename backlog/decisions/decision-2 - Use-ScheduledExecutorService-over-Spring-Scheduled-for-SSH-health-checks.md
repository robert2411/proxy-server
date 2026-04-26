---
id: decision-2
title: Use ScheduledExecutorService over Spring @Scheduled for SSH health checks
date: '2026-04-26 21:24'
status: Accepted
---
## Context

TASK-3 required a scheduled background health check to proactively evict dead SSH sessions from the session cache. The proxy server uses Spring Boot but the SSH subsystem (SshSessionManager) is intentionally decoupled from Spring lifecycle concerns to remain testable and reusable.

Two options were considered:
1. **Spring @Scheduled** — annotation-driven, auto-managed by Spring context
2. **ScheduledExecutorService** — JDK standard, manually created and destroyed

## Decision

Use `ScheduledExecutorService` with a single daemon thread, created in the SshSessionManager constructor and shut down via `@PreDestroy`.

**Rationale:**
- Keeps SSH infrastructure decoupled from Spring scheduling — SshSessionManager can be tested or reused outside a Spring context
- Daemon thread ensures the JVM can shut down cleanly even if @PreDestroy is not called
- Single-thread executor is sufficient for a lightweight 60s health check that only inspects connection flags (no network I/O)
- Explicit lifecycle control via @PreDestroy provides deterministic shutdown

## Consequences

- SshSessionManager owns its own executor lifecycle — must call `shutdownNow()` on destroy
- Health check runs at 60s fixed rate regardless of Spring scheduler configuration
- Future scheduled tasks in SSH subsystem should reuse this executor rather than creating new ones
- If Spring context is not present (e.g., in integration tests), the executor still works correctly
