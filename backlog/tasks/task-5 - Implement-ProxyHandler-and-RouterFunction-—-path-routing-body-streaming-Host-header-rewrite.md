---
id: TASK-5
title: >-
  Implement ProxyHandler and RouterFunction — path routing, body streaming, Host
  header rewrite
status: To Do
assignee: []
created_date: '2026-04-24 21:27'
labels: []
milestone: m-1
dependencies:
  - TASK-4
priority: high
ordinal: 2000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
WebFlux functional router that handles all inbound requests matching /{host}/{port}/**. Extracts target host and port from path, resolves local port from PortForwardCache, builds upstream URI, and streams request/response through WebClient.

Design decisions (from design discussion):
- Route: RequestPredicates.path("/{host}/{port}/**")
- Strip /{host}/{port} prefix from path before forwarding; preserve remaining path and raw query string
- Rewrite Host header to host:port (backends reject mismatched Host)
- Stream body as Flux<DataBuffer> in both directions — do not buffer full body in memory
- Preserve all original request headers except Host; do not add X-Forwarded-* unless intentional
- Forward response status and headers verbatim
- URL-encoded slashes in the ** tail: ensure PathPatternParser does not double-decode them
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 GET/POST/PUT/DELETE/PATCH all proxied correctly
- [ ] #2 Path remainder and query string preserved verbatim on upstream request
- [ ] #3 Host header on upstream request is set to targetHost:targetPort
- [ ] #4 Response status code and headers returned to caller unchanged
- [ ] #5 Large request and response bodies stream without full in-memory buffering (verified with a > 10 MB payload)
- [ ] #6 URL-encoded slashes in path tail are not corrupted
<!-- AC:END -->
