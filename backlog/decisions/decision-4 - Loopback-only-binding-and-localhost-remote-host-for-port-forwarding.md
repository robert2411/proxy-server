---
id: decision-4
title: Loopback-only binding and localhost remote host for port forwarding
date: '2026-04-26 21:53'
status: Accepted
---
## Context

PortForwardCache (TASK-4) creates SSH local port forwards that bind a local ServerSocket to an ephemeral port, then forward traffic through the SSH tunnel to the target service. Two key parameters needed decisions:

1. **Local bind address:** Which network interface should the ServerSocket bind to?
2. **Remote forward destination:** What hostname should the SSH LocalPortForwarder use as the remote endpoint?

These choices affect security (who can reach the forwarded port) and correctness (does traffic reach the intended service).

## Options Considered

### Local bind address
1. **Wildcard bind (`0.0.0.0`)** — Accessible from any network interface. Simple but exposes forwarded ports to the entire network.
2. **Loopback-only (`InetAddress.getLoopbackAddress()`)** — Only accessible from the local machine. Limits attack surface.

### Remote forward destination
1. **Use `targetHost` string (e.g., `"my-server"`)** — Relies on DNS resolution on the remote machine, which may resolve to a different interface or fail entirely.
2. **Use `"localhost"`** — Since the SSH session terminates directly on the target machine (1:1 SSH-to-target topology via SshSessionManager), `localhost` on the remote end IS the backend service.

## Decision

- **Local bind:** `ServerSocket(0, 0, InetAddress.getLoopbackAddress())` — loopback-only binding on an ephemeral port.
- **Remote host:** `"localhost"` in LocalPortForwarder Parameters — the SSH tunnel already terminates on the target machine, so `localhost` reliably reaches the service without DNS dependency.

This assumes the 1:1 SSH-to-target topology where each URL host segment maps to a dedicated SSH session that terminates on that backend server (not a bastion/jumpbox topology). This topology is enforced by SshSessionManager's `clientFor(host)` design.

## Consequences

- **Pro:** Forwarded ports are never exposed to the network — only the proxy process on localhost can reach them
- **Pro:** No DNS resolution needed on the remote end — eliminates a class of misconfiguration failures
- **Pro:** Consistent with the SSH subsystem's 1:1 host-to-session mapping
- **Con:** If the architecture ever moves to a bastion topology (one SSH session serving multiple backends), the remote host parameter would need to change from `"localhost"` to actual target hostnames — this would be a breaking change to PortForwardCache
- **Con:** Loopback binding means the proxy cannot be deployed as a sidecar that other containers connect to — acceptable for the current single-process deployment model
