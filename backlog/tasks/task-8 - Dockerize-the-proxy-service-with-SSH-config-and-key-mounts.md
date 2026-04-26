---
id: TASK-8
title: Dockerize the proxy service with SSH config and key mounts
status: To Do
assignee: []
created_date: '2026-04-24 21:27'
labels: []
milestone: m-2
dependencies:
  - TASK-5
priority: medium
ordinal: 3000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Package the service as a Docker image. SSH config, private keys, and certificates must be mounted read-only at runtime — never baked into the image.

Design decisions:
- Multi-stage build: Maven build stage + slim JRE runtime stage (eclipse-temurin:21-jre or similar)
- Mount points: /root/.ssh/config, /root/.ssh/ (keys + certs) — read-only mounts
- Expose port 8080 (HTTP proxy) and 8081 or same port for actuator (or use management.server.port)
- Document recommended docker run / compose snippet in README or inline in Dockerfile comments
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 docker build produces an image without SSH keys or config baked in
- [ ] #2 Container starts with -v ~/.ssh:/root/.ssh:ro mount and proxy handles requests correctly
- [ ] #3 Image is based on a slim JRE (not JDK) runtime layer
- [ ] #4 Actuator health endpoint reachable from host after docker run
- [ ] #5 docker-compose.yml example (or equivalent) documents volume mount and port mapping
<!-- AC:END -->
