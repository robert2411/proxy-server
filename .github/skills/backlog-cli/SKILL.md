---
name: backlog-cli
description: 'Comprehensive project management via the Backlog.md CLI tool. Use when asked to manage tasks, create tasks, update task status, manage acceptance criteria (AC), manage definition of done (DoD), search backlog, manage docs, milestones, and decisions, handle multi-shell newline input patterns, implement agent workflows with backlog, or work with task lifecycle (To Do → In Progress → Done). Covers CLI commands, AI agent integration, and best practices.'
---

# Backlog CLI Skill

Full-featured project management through the `backlog` CLI. Handles entire task lifecycle, AC/DoD management, search, board visualization, and MCP integration.

## When to Use This Skill

- Creating, editing, viewing, or listing tasks
- Managing acceptance criteria or definition of done
- Searching for tasks by topic, status, or assignee
- Managing docs, milestones, and decisions
- Implementing AI agent workflows with task tracking
- Handling multi-shell newline input for descriptions, plans, notes, summaries

## Prerequisites

- `backlog` CLI installed (`npm install -g backlog.md` or `bun install -g backlog.md`)
- Project initialised: `backlog init` (creates `backlog/config.yml` and `backlog/tasks/`)
- Working directory set to project root

---

## Core Concepts

| Concept | Description |
|---------|-------------|
| **Task** | Unit of work in `backlog/tasks/task-<id> - <title>.md` |
| **Acceptance Criteria (AC)** | Numbered checkboxes defining "what done looks like" |
| **Definition of Done (DoD)** | Per-task or global checklist for quality bar |
| **Status** | `To Do` → `In Progress` → `Done` |
| **Labels** | Free-form tags for filtering |
| **Milestone** | Grouping of related tasks |

**Golden rule:** Never edit task or milestone `.md` files directly. All writes go through the CLI or the approved [`milestone-helper.sh`](.github/skills/backlog-cli/scripts/milestone-helper.sh) script.

---

## Task Lifecycle Workflow

### 1. Create a Task

```bash
backlog task create "Title" \
  -d "Description of the why" \
  --ac "First acceptance criterion" \
  --ac "Second acceptance criterion" \
  -l label1,label2 \
  --priority high \
  -a @assignee
```

### 2. Start Work

```bash
backlog task edit <id> -s "In Progress" -a @myself
backlog task edit <id> --plan $'1. Research\n2. Implement\n3. Test'
```

### 3. Track Progress

```bash
# Append notes (preferred — preserves history)
backlog task edit <id> --append-notes $'- Completed X\n- Blocked on Y'

# Replace notes
backlog task edit <id> --notes "Full replacement note"
```

### 4. Check Off Acceptance Criteria

```bash
# One at a time
backlog task edit <id> --check-ac 1

# Multiple at once
backlog task edit <id> --check-ac 1 --check-ac 2 --check-ac 3
```

### 5. Wrap Up

```bash
backlog task edit <id> --final-summary $'What changed and why.\n\nChanges:\n- File A updated\n- File B added\n\nTests:\n- All passing'
backlog task edit <id> -s Done
```

---

## Acceptance Criteria Management

| Operation | Command |
|-----------|---------|
| Add AC | `backlog task edit <id> --ac "Criterion text"` |
| Add multiple ACs | `backlog task edit <id> --ac "First" --ac "Second"` |
| Check AC #1 | `backlog task edit <id> --check-ac 1` |
| Check multiple | `backlog task edit <id> --check-ac 1 --check-ac 2` |
| Uncheck AC #2 | `backlog task edit <id> --uncheck-ac 2` |
| Remove AC #3 | `backlog task edit <id> --remove-ac 3` |
| Mixed ops | `backlog task edit <id> --check-ac 1 --uncheck-ac 2 --remove-ac 3 --ac "New"` |

**Rules:**
- Criteria are outcome-oriented and testable, not implementation steps
- Good: "User can log in with valid credentials"
- Bad: "Add handleLogin() function to auth.ts"

---

## Definition of Done (DoD) Management

| Operation | Command |
|-----------|---------|
| Add DoD item | `backlog task edit <id> --dod "Item text"` |
| Check DoD #1 | `backlog task edit <id> --check-dod 1` |
| Uncheck DoD #2 | `backlog task edit <id> --uncheck-dod 2` |
| Remove DoD #3 | `backlog task edit <id> --remove-dod 3` |
| Create without defaults | `backlog task create "Title" --no-dod-defaults` |

Global DoD defaults live in `backlog/config.yml` under `definition_of_done`.

---

## Viewing and Searching

```bash
# View single task (AI-friendly)
backlog task <id> --plain

# List all tasks
backlog task list --plain

# Filter by status
backlog task list -s "In Progress" --plain

# Filter by assignee
backlog task list -a @sara --plain

# Fuzzy search (titles, descriptions, content)
backlog search "auth" --plain

# Search within type and status
backlog search "api" --type task --status "To Do" --plain

# Search by priority
backlog search "bug" --priority high --plain
```

Always use `--plain` for AI-readable output.

---

## Docs, Milestones, and Decisions

### Documents

Store design docs, specs, and reference material alongside tasks.

```bash
# Create a document
backlog doc create "API Design"

# List all documents
backlog doc list --plain

# View a document
backlog doc view <docId>
```

### Milestones

Group related tasks into milestones to track larger units of work.

```bash
# List milestones with completion status
backlog milestone list --plain

# Archive a completed milestone
backlog milestone archive "Milestone Name"
```

> **Note:** The `backlog milestone` CLI only supports `list` and `archive` commands. There is no `create` subcommand and no `--milestone` flag on `task create` or `task edit`.

Use the `milestone-helper.sh` script to create milestones and assign tasks to them:

#### Using the milestone-helper.sh script

The `milestone-helper.sh` script (located in `.github/skills/backlog-cli/scripts/`) automates both milestone creation (by updating `backlog/config.yml`) and task-to-milestone assignment (by patching task frontmatter).

```bash
# Create a new milestone
bash .github/skills/backlog-cli/scripts/milestone-helper.sh create-milestone "Sprint 1" "First sprint"

# Assign a task to a milestone
bash .github/skills/backlog-cli/scripts/milestone-helper.sh assign-task 42 "Sprint 1"
```

- `create-milestone <name> <description>` — adds the milestone to `backlog/config.yml`
- `assign-task <task-id> <milestone-name>` — sets `milestone:` in the task's frontmatter

### Scripts

Helper scripts bundled with this skill live in `.github/skills/backlog-cli/scripts/`.

| Script | Description |
|--------|-------------|
| `milestone-helper.sh` | Creates milestones and assigns tasks to milestones via two subcommands: `create-milestone` and `assign-task` |

### Decisions

Record architectural decision records (ADRs) in `backlog/decisions/`.

```bash
# Create a decision record
backlog decision create "Use PostgreSQL for primary storage"
```

---

## Multi-Shell Newline Patterns

The CLI preserves input literally — `\n` in normal quotes is NOT converted to a newline. Use these patterns:

### Bash / Zsh (ANSI-C quoting — recommended)

```bash
backlog task edit <id> --plan $'1. Step one\n2. Step two\n3. Step three'
backlog task edit <id> --notes $'- Done A\n- Doing B\n- TODO: C'
backlog task edit <id> --final-summary $'Outcome summary\n\nChanges:\n- Added X\n- Updated Y'
backlog task edit <id> -d $'Line one\n\nLine two after blank'
```

### POSIX Portable (printf)

```bash
backlog task edit <id> --notes "$(printf 'Line one\nLine two\nLine three')"
backlog task edit <id> --plan "$(printf '1. Research\n2. Build\n3. Test')"
```

### PowerShell (backtick-n)

```powershell
backlog task edit <id> --notes "Line one`nLine two`nLine three"
```

---

## AI Agent Integration Patterns

### Pattern 1: Implementation Agent

```bash
# 1. Claim task
backlog task edit <id> -s "In Progress" -a @agent

# 2. Read plan
backlog task <id> --plain

# 3. Implement, then log progress
backlog task edit <id> --append-notes $'- Implemented feature X\n- Added tests, coverage 85%'

# 4. Check all ACs
backlog task edit <id> --check-ac 1 --check-ac 2 --check-ac 3

# 5. Final summary + done
backlog task edit <id> --final-summary "Implemented X; updated files A, B; all tests pass"
backlog task edit <id> -s Done
```

### Pattern 2: QA Agent

```bash
# Read task to review
backlog task <id> --plain

# Append QA findings
backlog task edit <id> --append-notes $'QA REVIEW:\n- Issue 1: ...\n- Issue 2: ...'

# Or approve
backlog task edit <id> --append-notes "✅ QA APPROVED — all ACs verified, tests pass"
```

### Pattern 3: Analyse Agent

```bash
# Create task with full detail
backlog task create "Feature title" \
  -d "Why this feature is needed" \
  --ac "User can do X" \
  --ac "System handles Y" \
  --priority high \
  -l backend,api

# Add implementation plan after research
backlog task edit <id> --plan $'1. Review existing code\n2. Design approach\n3. Implement\n4. Write tests'
```

---

## Task Content Reference

| Field | CLI Flag | Notes |
|-------|----------|-------|
| Title | `-t "New Title"` | Short, action-oriented |
| Description | `-d "text"` | The "why" |
| Status | `-s "In Progress"` | To Do / In Progress / Done |
| Assignee | `-a @name` | One assignee |
| Labels | `-l label1,label2` | Comma-separated |
| Priority | `--priority high` | low / medium / high |
| Plan | `--plan "text"` | The "how" — added after starting |
| Notes (replace) | `--notes "text"` | Progress log |
| Notes (append) | `--append-notes "text"` | Preferred — preserves history |
| Final Summary | `--final-summary "text"` | PR description — added at wrap-up |
| Dependencies | `--dep task-1` | Task relationships |
| References | `--ref src/file.ts` | Code or URL references |
| Milestone | *(milestone-helper.sh)* | Use `bash .github/skills/backlog-cli/scripts/milestone-helper.sh assign-task <id> "<name>"` — no CLI flag exists |

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Skill not triggering | Add more keywords from description to your prompt |
| Task not found | Run `backlog task list --plain` to check ID |
| AC won't check | View task first: `backlog task <id> --plain` to confirm AC indices |
| Metadata out of sync | Re-edit via CLI: `backlog task edit <id> -s <current-status>` |
| Newlines not preserved | Use `$'...'` quoting (bash/zsh) or `printf` |
| MCP server not responding | Ensure `cwd` in MCP config points to project root with `backlog/config.yml` |
| `backlog` not found | Run `npm install -g backlog.md` or `bun install -g backlog.md` |
| Init error | Run `backlog init` in project root |

---

## References

- [Complete CLI Reference](../../../backlog/docs/doc-7%20-%20Backlog-CLI-Complete-Reference-Guide.md)
- [Quick Start Guide](../../../backlog/docs/doc-9%20-%20Backlog-CLI-Quick-Start-Guide.md)
- [Task Management Tutorial](../../../backlog/docs/doc-10%20-%20Backlog-CLI-Task-Management-Tutorial.md)
- [MCP Integration Guide](../../../backlog/docs/doc-11%20-%20Backlog-CLI-MCP-Integration-Guide.md)
- [Best Practices](../../../backlog/docs/doc-12%20-%20Backlog-CLI-Best-Practices.md)
- [AI Agent Integration Guide](../../../backlog/docs/doc-13%20-%20Backlog-CLI-AI-Agent-Integration-Guide.md)
- [Advanced Features Guide](../../../backlog/docs/doc-14%20-%20Backlog-CLI-Advanced-Features-Guide.md)
- [USAGE.md](./references/USAGE.md)
- [milestone-helper.sh](./scripts/milestone-helper.sh) — shell script for milestone creation and task-to-milestone assignment







