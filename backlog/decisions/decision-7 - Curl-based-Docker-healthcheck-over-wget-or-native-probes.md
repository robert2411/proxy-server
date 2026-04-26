---
id: decision-7
title: Curl-based Docker healthcheck over wget or native probes
date: '2026-04-26 22:38'
status: Accepted
---
## Context

The Docker Compose healthcheck needs a tool to probe the Spring Boot actuator health endpoint (`/actuator/health`) from inside the container. The runtime base image (`eclipse-temurin:21-jre`) ships neither curl nor wget by default.

## Options Considered

1. **curl** — well-known, supports `-sf` flags for silent failure detection. Must be explicitly installed via `apt-get`.
2. **wget** — sometimes pre-installed in Debian-slim images, but flag semantics differ (`-qO-`). Not guaranteed present.
3. **No tool / Java-based probe** — avoids installing any extra binary, but requires a custom health-check class or script, adding complexity.

## Decision

Install `curl` in the runtime stage (`apt-get install -y --no-install-recommends curl`) and use `curl -sf http://localhost:8080/actuator/health` in the Compose healthcheck. Single consistent tool for healthcheck use; no ambiguity.

## Consequences

- Adds ~3 MB to the image (curl + libcurl + dependencies).
- Healthcheck is readable and debuggable (`docker exec … curl …`).
- No wget references or fallback logic needed.
- If image size becomes critical, can revisit with a compiled Go binary or native Java health probe.
