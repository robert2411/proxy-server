---
id: TASK-6
title: >-
  Wire Micrometer/Prometheus metrics — request counter, session gauge, forward
  gauge, latency histogram
status: To Do
assignee: []
created_date: '2026-04-24 21:27'
labels: []
milestone: m-2
dependencies:
  - TASK-5
priority: medium
ordinal: 1000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Instrument the proxy with the five key metrics called out in the design discussion. Expose them via /actuator/prometheus for Grafana scraping.

Metrics to add:
- proxy_requests_total{target, status} — Counter, incremented on each proxied request
- ssh_sessions_active — Gauge, tracks live SSHClient count in SshSessionManager cache
- port_forwards_active — Gauge, tracks live LocalPortForwarder count in PortForwardCache
- proxy_upstream_latency_seconds — Timer/Histogram, latency from proxy receiving request to first response byte from upstream
- ssh_reconnects_total{target} — Counter, incremented each time a dead session is replaced

Dependency: spring-boot-starter-actuator + micrometer-registry-prometheus already declared in task-1.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 GET /actuator/prometheus returns all five metrics listed above
- [ ] #2 proxy_requests_total increments on each request with correct target and HTTP status label
- [ ] #3 ssh_sessions_active reflects actual live session count (spot-check after adding/dropping a session)
- [ ] #4 port_forwards_active reflects actual forwarder count
- [ ] #5 proxy_upstream_latency_seconds histogram has at least p50, p95, p99 buckets
<!-- AC:END -->
