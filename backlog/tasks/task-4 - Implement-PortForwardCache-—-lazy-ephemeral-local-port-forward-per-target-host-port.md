---
id: TASK-4
title: >-
  Implement PortForwardCache — lazy ephemeral local port forward per target
  host:port
status: To Do
assignee: []
created_date: '2026-04-24 21:27'
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
- [ ] #1 localPortFor(host, port) returns a stable loopback port number for a given target
- [ ] #2 Second call to localPortFor with same args returns same port without opening a new forwarder
- [ ] #3 invalidate(host, port) removes the entry so next call opens a fresh forwarder
- [ ] #4 Forwarder threads are submitted to an injected Executor (not newCachedThreadPool)
- [ ] #5 Dead forwarder (thread exited) is detected and replaced on next localPortFor call
<!-- AC:END -->
