---
id: decision-3
title: Spring Boot WebFlux with sshj for SSH-aware HTTP proxy
date: '2026-04-26 21:24'
status: Accepted
---
## Context

The team needed an HTTP proxy that manages SSH tunnels directly — parsing an existing `~/.ssh/config`, establishing multi-hop SSH connections (ProxyJump chains with certificate auth), and routing HTTP requests through those tunnels via path-based URLs (`/<target-host>/<target-port>/<path>`). Traffic is predominantly HTTP, used by 10–20 people internally.

## Options Considered

### Option A — Separate concerns (external tunnels + reverse proxy)
- Use autossh/systemd to maintain SSH tunnels externally, binding to local ports.
- Place nginx or Caddy in front for path-based routing.
- **Pro:** Off-the-shelf tools, no custom code for proxying.
- **Con:** Mapping table drift between ssh_config and proxy config; no dynamic tunnel lifecycle; harder to observe per-tunnel metrics.

### Option B — Single-process SSH-aware proxy (chosen)
- Spring Boot WebFlux service with sshj managing SSH sessions internally.
- Maintains a pool of SSH sessions keyed by target host; opens local port forwards on demand.
- Proxies HTTP via WebClient to ephemeral loopback ports.
- **Pro:** One deployable artifact; native ssh_config reuse; built-in metrics/observability; on-demand tunnel lifecycle.
- **Con:** Custom code to write and maintain; SSH keepalive/reconnect must be handled explicitly.

## Decision

Adopt **Option B**: a Spring Boot 4.0.5 WebFlux application using sshj 0.40.0 as the SSH client. This gives a single artifact with full lifecycle control over tunnels, native Prometheus metrics via Micrometer, and natural extension points for auth and per-target access control.

## Consequences

- All SSH tunnel logic lives inside the Java process — no external autossh sidecars needed.
- The team must implement SSH keepalive, reconnect, and session pooling (covered in later milestones).
- Observability is first-class from day one (Actuator + Micrometer wired at bootstrap).
- Future additions (WebSocket proxying, per-user access control) fit naturally into Spring Security and WebFlux infrastructure.

