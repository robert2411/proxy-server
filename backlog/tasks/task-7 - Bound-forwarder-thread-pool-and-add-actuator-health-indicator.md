---
id: TASK-7
title: Bound forwarder thread pool and add actuator health indicator
status: To Do
assignee: []
created_date: '2026-04-24 21:27'
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
- [ ] #1 Forwarder thread pool size is configurable via application.properties/yml
- [ ] #2 Thread pool is bounded — attempting to open more forwarders than pool size fails fast with a clear error rather than spinning up unbounded threads
- [ ] #3 GET /actuator/health includes a ssh-proxy component with UP/DOWN status
- [ ] #4 Health indicator reports details (per-host status) when health show-details is enabled
<!-- AC:END -->
