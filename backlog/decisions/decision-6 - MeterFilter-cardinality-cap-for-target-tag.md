---
id: decision-6
title: MeterFilter cardinality cap for target tag
date: '2026-04-26 22:38'
status: Accepted
---
## Context

The `proxy.requests.total` counter uses a `target` tag derived from the URL path variable `host` in ProxyHandler. This value is user-controlled — any unauthenticated client can send requests to arbitrary hostnames (e.g. `/random-host-N/80/path`). Each unique target value creates a permanent Counter in the MeterRegistry, causing unbounded memory growth and eventual OOM under a cardinality spray attack. The metric is recorded even on the early 502 exit path (port-forward failure), so no valid SSH config is needed to exploit. Identified as SEC-001 during security audit of TASK-6.

**Options considered:**

1. **Validate host against known SSH config entries before recording** — Would require coupling metrics to SSH config parsing logic; adds latency to every request.
2. **MeterFilter.maximumAllowableTags()** — Built-in Micrometer safeguard that caps tag cardinality at a configurable limit and denies new tag values beyond it. Zero coupling, minimal overhead.
3. **Allow-list filter** — Explicit set of known hosts; requires maintenance as SSH config changes.

## Decision

Apply `MeterFilter.maximumAllowableTags("proxy.requests.total", "target", 128, MeterFilter.deny())` on the MeterRegistry in the ProxyMetrics constructor. This caps the `target` tag to 128 unique values; requests beyond that threshold are silently denied metric registration.

The limit of 128 was chosen to be well above the expected number of legitimate SSH targets (typically <20) while still preventing meaningful memory exhaustion.

The `ssh.reconnects.total` counter's `target` tag is NOT capped because it only fires on legitimate stale-session replacement in `clientFor()` — the target must already exist in the SSH config and session cache, so it cannot be attacker-sprayed.

## Consequences

- Legitimate proxy targets (up to 128 unique hosts) are tracked with full fidelity
- Attacker-sprayed hostnames beyond the 128th are invisible in metrics (denied, not errored)
- No runtime errors or request failures — only metric recording is suppressed
- If the deployment ever exceeds 128 legitimate SSH targets, the cap must be raised in ProxyMetrics