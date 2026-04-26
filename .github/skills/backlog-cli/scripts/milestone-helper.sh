#!/usr/bin/env bash
# =============================================================================
# milestone-helper.sh — Backlog CLI Milestone Helper
# =============================================================================
# PURPOSE:
#   Automates two operations that the backlog CLI does not natively support:
#   1. Creating a new milestone file with correct YAML frontmatter.
#   2. Assigning an existing task to a milestone by patching its frontmatter.
#
# USAGE:
#   milestone-helper.sh create-milestone <title> [description]
#   milestone-helper.sh assign-task <task-id> <milestone-title-or-id>
#
# SUBCOMMANDS:
#   create-milestone  Create a new milestone in $BACKLOG_DIR/milestones/
#     <title>         Required. The milestone title (quoted if it contains spaces).
#     [description]   Optional. A short description of the milestone.
#
#   assign-task       Patch a task file's frontmatter to set the milestone field.
#     <task-id>       Required. Numeric ID (e.g. 5) or TASK-N format (e.g. TASK-5).
#     <milestone-title-or-id> Required. The title or id of the milestone to assign.
#                     The script resolves the title/id to the milestone's id field
#                     and writes that id (e.g. m-1) into the task frontmatter.
#
# EXAMPLES:
#   milestone-helper.sh create-milestone "Sprint 1" "First sprint goals"
#   milestone-helper.sh assign-task 5 "Sprint 1"
#   milestone-helper.sh assign-task TASK-5 "Sprint 1"
#   milestone-helper.sh assign-task 5 m-1
#
# ENVIRONMENT VARIABLES:
#   BACKLOG_DIR   Path to the backlog root directory. Defaults to ./backlog.
#                 Override this in tests to point at a temporary directory so
#                 the script never touches the real backlog during test runs.
#                 Example: BACKLOG_DIR=/tmp/test-backlog ./milestone-helper.sh ...
# =============================================================================

set -euo pipefail

BACKLOG_DIR="${BACKLOG_DIR:-./backlog}"
MILESTONES_DIR="$BACKLOG_DIR/milestones"
TASKS_DIR="$BACKLOG_DIR/tasks"

# -----------------------------------------------------------------------------
# usage() — Print help text and exit 1
# -----------------------------------------------------------------------------
usage() {
  cat >&2 <<EOF
Usage:
  $(basename "$0") create-milestone <title> [description]
  $(basename "$0") assign-task <task-id> <milestone-title-or-id>

Subcommands:
  create-milestone  Create a new milestone file in \$BACKLOG_DIR/milestones/
  assign-task       Assign a task to a milestone by patching its frontmatter
                    (resolves title or id to the milestone's id field)

Environment Variables:
  BACKLOG_DIR   Path to backlog root (default: ./backlog)

Examples:
  $(basename "$0") create-milestone "Sprint 1" "First sprint goals"
  $(basename "$0") assign-task 5 "Sprint 1"
  $(basename "$0") assign-task TASK-5 "Sprint 1"
  $(basename "$0") assign-task 5 m-1
EOF
  exit 1
}

# -----------------------------------------------------------------------------
# slugify() — Convert a string to a URL-safe slug
# -----------------------------------------------------------------------------
slugify() {
  echo "$1" \
    | tr '[:upper:]' '[:lower:]' \
    | sed 's/[^a-z0-9]/-/g' \
    | sed 's/-\{2,\}/-/g' \
    | sed 's/^-//;s/-$//'
}

# -----------------------------------------------------------------------------
# cmd_create_milestone() — Create a new milestone file
# -----------------------------------------------------------------------------
cmd_create_milestone() {
  local title="${1:-}"
  local description="${2:-}"

  if [[ -z "$title" ]]; then
    echo "Error: <title> is required." >&2
    usage
  fi

  # Ensure milestones directory exists
  mkdir -p "$MILESTONES_DIR"

  local slug
  slug="$(slugify "$title")"

  # Duplicate check — find an existing milestone file with exactly this slug.
  # Pattern: m-N - <slug>.md  (exact slug, no substring match).
  local existing
  existing="$(find "$MILESTONES_DIR" -maxdepth 1 -name "m-* - ${slug}.md" 2>/dev/null | head -1)"
  if [[ -n "$existing" ]]; then
    echo "Error: Milestone '${title}' already exists (slug: ${slug})." >&2
    exit 1
  fi

  # Determine next ID by scanning for m-N - *.md files
  local max_id=0
  local id_match
  while IFS= read -r filepath; do
    filename="$(basename "$filepath")"
    # Extract the leading number: m-N - ...
    id_match="$(echo "$filename" | sed -n 's/^m-\([0-9]*\) - .*/\1/p')"
    if [[ -n "$id_match" ]] && (( id_match > max_id )); then
      max_id="$id_match"
    fi
  done < <(find "$MILESTONES_DIR" -maxdepth 1 -name "m-*.md" 2>/dev/null)

  local next_id=$(( max_id + 1 ))
  local outfile="$MILESTONES_DIR/m-${next_id} - ${slug}.md"

  cat > "$outfile" <<EOF
---
id: m-${next_id}
title: "${title}"
---

## Description

Milestone: ${title}
EOF

  echo "Created milestone: $outfile"
}

# -----------------------------------------------------------------------------
# lookup_milestone_id() — Find a milestone's id by title or id string
# Returns the id via stdout and exit 0 on success; exit 1 if not found.
# -----------------------------------------------------------------------------
lookup_milestone_id() {
  local query="$1"   # may be a title OR an id
  local file id title name
  while IFS= read -r file; do
    id=""
    title=""
    name=""
    # Parse only the YAML frontmatter (between first pair of ---)
    while IFS= read -r line; do
      [[ "$line" == "---" ]] && break   # end of frontmatter
      case "$line" in
        id:*   ) id="${line#id: }" ;;
        title:*) title="${line#title: }"; title="${title//\"}" ;;
        name:* ) name="${line#name: }"; name="${name//\"}" ;;
      esac
    done < <(tail -n +2 "$file")   # skip first ---
    if [[ "$id" == "$query" || "$title" == "$query" || "$name" == "$query" ]]; then
      echo "$id"
      return 0
    fi
  done < <(find "$MILESTONES_DIR" -maxdepth 1 -name "*.md" 2>/dev/null)
  return 1
}

# -----------------------------------------------------------------------------
# cmd_assign_task() — Patch a task file's frontmatter with a milestone field
# -----------------------------------------------------------------------------
cmd_assign_task() {
  local task_id_raw="${1:-}"
  local milestone_ref="${2:-}"

  if [[ -z "$task_id_raw" ]]; then
    echo "Error: <task-id> is required." >&2
    usage
  fi

  if [[ -z "$milestone_ref" ]]; then
    echo "Error: <milestone-title-or-id> is required." >&2
    usage
  fi

  # Normalise: strip leading TASK- or task- prefix
  local task_num
  task_num="$(echo "$task_id_raw" | sed 's/^[Tt][Aa][Ss][Kk]-//')"

  # Validate: must be a non-empty integer
  if [[ -z "$task_num" ]] || ! [[ "$task_num" =~ ^[0-9]+$ ]]; then
    echo "Error: Invalid task ID '${task_id_raw}'. Must be a number or TASK-N format." >&2
    exit 1
  fi

  # Find the task file
  local task_file
  task_file="$(find "$TASKS_DIR" -maxdepth 1 -name "task-${task_num} - *.md" 2>/dev/null | head -1)"

  if [[ -z "$task_file" ]]; then
    echo "Error: Task file for ID '${task_num}' not found in ${TASKS_DIR}." >&2
    exit 1
  fi

  # Resolve milestone title or id to the milestone's id field
  local milestone_id
  milestone_id="$(lookup_milestone_id "$milestone_ref")" || {
    echo "error: Milestone '${milestone_ref}' not found in ${MILESTONES_DIR}." >&2
    exit 1
  }
  if [[ -z "$milestone_id" ]]; then
    echo "error: milestone id is empty (milestone file has no id: field)" >&2
    exit 1
  fi

  # Use awk to check/modify milestone: ONLY within the frontmatter block.
  # The frontmatter block is bounded by the first pair of --- delimiters.
  # This prevents accidentally modifying a "milestone:" line in the task body.
  #
  # SEC-001 fix: use ENVIRON instead of awk -v to pass milestone_title.
  # awk's -v flag interprets backslash escape sequences (\n, \t, \\, …) at
  # parse time, so a title like "Sprint 1\ninjected: evil" would inject an
  # extra YAML field into the frontmatter.  ENVIRON passes the value as raw
  # bytes with no escape processing, eliminating that attack surface.
  MILESTONE_VAR="milestone: ${milestone_id}" \
  awk '
    BEGIN { milestone = ENVIRON["MILESTONE_VAR"]; in_front=0; front_done=0; found=0 }

    /^---/ {
      if (!in_front) {
        in_front=1
        print
        next
      } else if (!front_done) {
        if (!found) {
          print milestone
        }
        front_done=1
        print
        next
      }
    }

    in_front && !front_done && /^milestone:/ {
      print milestone
      found=1
      next
    }

    { print }
  ' "$task_file" > "${task_file}.tmp" && mv "${task_file}.tmp" "$task_file"

  echo "Assigned task ${task_num} to milestone '${milestone_ref}' (id: ${milestone_id})."
}

# -----------------------------------------------------------------------------
# Argument dispatch
# -----------------------------------------------------------------------------
if [[ $# -eq 0 ]]; then
  usage
fi

subcommand="$1"
shift

case "$subcommand" in
  create-milestone)
    cmd_create_milestone "$@"
    ;;
  assign-task)
    cmd_assign_task "$@"
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    echo "Error: Unknown subcommand '${subcommand}'." >&2
    usage
    ;;
esac
