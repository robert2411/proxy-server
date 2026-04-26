---
id: decision-5
title: Non-root container user for Docker runtime
date: '2026-04-26 22:37'
status: Accepted
---
## Context

TASK-8 containerised the proxy service. The initial Dockerfile ran the JVM process as root (UID 0). Security audit (SEC-001) identified that any application-level compromise (e.g. RCE via deserialization) would grant root inside the container. Defence-in-depth requires dropping privileges.

## Options Considered

1. **Run as root (default)** — simplest, but violates least-privilege principle.
2. **Non-root system user (`app`)** — requires adjusting SSH mount path from `/root/.ssh` to `/home/app/.ssh` and `--chown` on JAR copy, but limits blast radius of a container escape.
3. **Rootless Docker / user-namespace remapping** — host-level config, out-of-scope for image definition.

## Decision

Create a non-root system user `app` in the runtime stage and switch to it via `USER app`. SSH credentials mount at `/home/app/.ssh:ro`. JAR ownership set with `COPY --chown=app:app`.

## Consequences

- Container process runs as unprivileged UID; root exploits inside the container are mitigated.
- `docker-compose.yml` and `docker run` examples must mount SSH to `/home/app/.ssh` (not `/root/.ssh`).
- Any future writable volume mounts must be owned by `app:app`.
- Aligns with Docker/OCI security best practices and CIS Docker Benchmark 4.1.
