---
id: decision-1
title: Custom SshConfigParser over sshj OpenSSHConfig
date: '2026-04-26 21:24'
status: Accepted
---
## Context

The implementation plan for SshSessionManager (TASK-2) assumed sshj would provide `OpenSSHConfig` and `ConfigFile.Entry` classes for parsing `~/.ssh/config` files. This assumption came from the original Handoff document which referenced "sshj has OpenSSHConfig + config parsing helpers."

During implementation, it was discovered that **sshj 0.40.0 does not include an `OpenSSHConfig` class**. The library provides SSH client functionality but not a general-purpose ssh_config parser.

## Options Considered

1. **Use sshj's OpenSSHConfig** — Not viable; class does not exist in sshj 0.40.0.
2. **Use Apache MINA SSHD's config parser** — Would introduce a second SSH library dependency alongside sshj.
3. **Use a third-party ssh_config parsing library** — No well-maintained Java library found for this purpose.
4. **Write a custom parser** — Implement only the subset of ssh_config directives needed (Host, HostName, User, Port, IdentityFile, ProxyJump).

## Decision

Implemented a custom `SshConfigParser` class that handles the subset of OpenSSH config directives required by this project. The parser supports:
- Host block matching with wildcard patterns
- First-match-wins directive merging (matching OpenSSH semantics)
- Case-insensitive keyword handling
- `~` expansion for IdentityFile paths

This keeps the dependency graph minimal (only sshj) and gives full control over parsing behavior.

## Consequences

- **Pro:** No additional dependencies beyond sshj
- **Pro:** Full control over parsing — can add directives as needed
- **Pro:** Easier to test (no mocking third-party config classes)
- **Con:** Must maintain the parser ourselves; any new ssh_config directives we need must be added manually
- **Con:** May not handle edge cases that a battle-tested parser would (e.g., Match blocks, Include directives) — acceptable for current scope
- **Risk:** If the project needs `Match` or `Include` support in future, the parser will need non-trivial extension
