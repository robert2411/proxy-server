#!/usr/bin/env bash
# =============================================================================
# squash-task-commits.sh — Squash Consecutive Same-Task Commits
# =============================================================================
# PURPOSE:
#   Reads the recent git log (last 100 commits), identifies consecutive runs of
#   commits sharing the same task-id prefix (format: task-<id>: ...), and
#   squashes each consecutive run into a single commit (keeping the first
#   message). Non-consecutive occurrences of the same task-id are left
#   independent.
#
# USAGE:
#   squash-task-commits.sh [--dry-run]
#
# FLAGS:
#   --dry-run   Print planned squash operations without modifying history.
#               Exits 0 after printing.
#
# EXAMPLES:
#   # Preview what would be squashed
#   .github/skills/backlog-cli/scripts/squash-task-commits.sh --dry-run
#
#   # Actually squash
#   .github/skills/backlog-cli/scripts/squash-task-commits.sh
#
# EXIT CODES:
#   0   Success (or nothing to squash, or dry-run complete)
#   1   Dirty working tree / script error
#
# NOTES:
#   - Uses git rebase -i with GIT_SEQUENCE_EDITOR for squashing.
#   - Processes oldest runs first to avoid SHA invalidation.
#   - After each rebase, re-reads git log to refresh SHAs.
#   - Requires bash 3.2+ (macOS compatible — no mapfile/readarray used).
# =============================================================================

set -euo pipefail

# -----------------------------------------------------------------------------
# Parse CLI arguments
# -----------------------------------------------------------------------------
DRY_RUN=false

for arg in "$@"; do
  case "$arg" in
    --dry-run)
      DRY_RUN=true
      ;;
    *)
      echo "Usage: $(basename "$0") [--dry-run]" >&2
      exit 1
      ;;
  esac
done

# -----------------------------------------------------------------------------
# Dirty working tree check (AC#7)
# -----------------------------------------------------------------------------
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Error: Working tree is dirty. Commit or stash changes before squashing." >&2
  exit 1
fi

# -----------------------------------------------------------------------------
# read_log_newest_first() — Read last 100 commits into parallel arrays
#   OUT: LOG_HASHES[]   (newest=index 0)
#        LOG_SUBJECTS[]
#        LOG_TASK_IDS[]
# -----------------------------------------------------------------------------
read_log_newest_first() {
  LOG_HASHES=()
  LOG_SUBJECTS=()
  LOG_TASK_IDS=()

  while IFS='|' read -r hash subject; do
    LOG_HASHES+=("$hash")
    LOG_SUBJECTS+=("$subject")

    local tid=""
    if [[ "$subject" =~ ^(task-[^:]+): ]]; then
      tid="${BASH_REMATCH[1]}"
    fi
    LOG_TASK_IDS+=("$tid")
  done < <(git log --format="%H|%s" -100)
}

# -----------------------------------------------------------------------------
# find_runs() — Walk newest-first log arrays and identify consecutive same-task
#   runs of length >= 2.
#
#   OUT: RUN_TASK_IDS[]   task-id for each qualifying run
#        RUN_MESSAGES[]   subject of newest commit in run (representative label)
#        RUN_END_IDX[]    index of oldest commit in run (in newest-first ordering)
#        RUN_COUNTS[]     number of commits in the run
# -----------------------------------------------------------------------------
find_runs() {
  RUN_TASK_IDS=()
  RUN_MESSAGES=()
  RUN_END_IDX=()
  RUN_COUNTS=()

  local total="${#LOG_HASHES[@]}"
  local i=0

  while (( i < total )); do
    local current_tid="${LOG_TASK_IDS[$i]}"

    if [[ -z "$current_tid" ]]; then
      (( i++ )) || true
      continue
    fi

    # Find the end of this consecutive run
    local run_start=$i
    local j=$(( i + 1 ))
    while (( j < total )) && [[ "${LOG_TASK_IDS[$j]}" == "$current_tid" ]]; do
      (( j++ )) || true
    done
    local run_end=$(( j - 1 ))
    local run_count=$(( run_end - run_start + 1 ))

    if (( run_count >= 2 )); then
      RUN_TASK_IDS+=("$current_tid")
      RUN_MESSAGES+=("${LOG_SUBJECTS[$run_start]}")  # newest commit's message
      RUN_END_IDX+=("$run_end")                       # oldest commit index (largest = furthest from HEAD)
      RUN_COUNTS+=("$run_count")
    fi

    i=$(( run_end + 1 ))
  done
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

read_log_newest_first
find_runs

if (( ${#RUN_TASK_IDS[@]} == 0 )); then
  echo "Nothing to squash."
  exit 0
fi

# -----------------------------------------------------------------------------
# Dry-run output (AC#6)
# -----------------------------------------------------------------------------
if [[ "$DRY_RUN" == "true" ]]; then
  echo "[DRY-RUN] Planned squash operations:"
  local_i=0
  while (( local_i < ${#RUN_TASK_IDS[@]} )); do
    echo "[DRY-RUN] Would squash ${RUN_COUNTS[$local_i]} commits for ${RUN_TASK_IDS[$local_i]} into one commit"
    (( local_i++ )) || true
  done
  exit 0
fi

# -----------------------------------------------------------------------------
# Squash execution (AC#3, AC#5)
# Process runs from OLDEST to NEWEST (highest RUN_END_IDX first) so that
# rebasing earlier history does not invalidate SHAs for later (closer to HEAD)
# runs that have not been processed yet.
# -----------------------------------------------------------------------------

total_runs="${#RUN_TASK_IDS[@]}"

# Build processing order: sort run indices by RUN_END_IDX descending (oldest first)
order=()
for (( idx=0; idx < total_runs; idx++ )); do
  order+=("$idx")
done

# Bubble sort order[] by RUN_END_IDX descending (oldest run = largest index = processed first)
for (( a=0; a < total_runs; a++ )); do
  for (( b=a+1; b < total_runs; b++ )); do
    if (( RUN_END_IDX[${order[$a]}] < RUN_END_IDX[${order[$b]}] )); then
      tmp="${order[$a]}"
      order[$a]="${order[$b]}"
      order[$b]="$tmp"
    fi
  done
done

# Process each run in oldest-first order
for run_order_idx in "${order[@]}"; do
  task_id="${RUN_TASK_IDS[$run_order_idx]}"
  end_idx="${RUN_END_IDX[$run_order_idx]}"   # oldest commit index (furthest from HEAD)

  echo "Squashing run for ${task_id} (${RUN_COUNTS[$run_order_idx]} commits)..."

  # N = number of commits from HEAD that the rebase window must cover.
  # end_idx is 0-based (0=HEAD), so we need HEAD~(end_idx+1)..HEAD
  N=$(( end_idx + 1 ))

  # Re-read the window OLDEST-FIRST to get current SHAs after prior rebases.
  # git log --reverse HEAD~N..HEAD lists exactly N commits, oldest to newest.
  # We APPEND to build an oldest-first array (index 0 = oldest).
  win_hashes=()
  win_subjects=()
  win_task_ids=()

  while IFS='|' read -r h s; do
    win_hashes+=("$h")
    win_subjects+=("$s")

    local_tid=""
    if [[ "$s" =~ ^(task-[^:]+): ]]; then
      local_tid="${BASH_REMATCH[1]}"
    fi
    win_task_ids+=("$local_tid")
  done < <(git log --format="%H|%s" --reverse "HEAD~${N}..HEAD")

  # Find the first consecutive run of task_id in oldest-first window.
  # Oldest-first means we find the earliest (oldest) consecutive block first.
  run_hashes=()
  in_run=false
  run_found=false

  for (( wi=0; wi < ${#win_hashes[@]}; wi++ )); do
    wtid="${win_task_ids[$wi]}"
    if [[ "$wtid" == "$task_id" ]] && [[ "$in_run" == "false" ]] && [[ "$run_found" == "false" ]]; then
      in_run=true
      run_hashes+=("${win_hashes[$wi]}")
    elif [[ "$wtid" == "$task_id" ]] && [[ "$in_run" == "true" ]]; then
      run_hashes+=("${win_hashes[$wi]}")
    elif [[ "$in_run" == "true" ]]; then
      # End of the consecutive run
      in_run=false
      run_found=true
    fi
  done

  if (( ${#run_hashes[@]} < 2 )); then
    echo "  Run for ${task_id} already squashed or not found in refreshed log. Skipping."
    continue
  fi

  # Build the rebase todo in oldest-first order (as git rebase -i expects).
  # First commit in run → pick (keeps its message)
  # Remaining commits in run → fixup (discards their messages, no editor)
  # All other commits in window → pick
  todo_file="$(mktemp)"

  seen_first_in_run=false

  for (( wi=0; wi < ${#win_hashes[@]}; wi++ )); do
    wh="${win_hashes[$wi]}"
    ws="${win_subjects[$wi]}"

    # Check if this hash is in our run
    in_this_run=false
    for rh in "${run_hashes[@]}"; do
      if [[ "$wh" == "$rh" ]]; then
        in_this_run=true
        break
      fi
    done

    if [[ "$in_this_run" == "true" ]]; then
      if [[ "$seen_first_in_run" == "false" ]]; then
        echo "pick $wh $ws" >> "$todo_file"
        seen_first_in_run=true
      else
        echo "fixup $wh $ws" >> "$todo_file"
      fi
    else
      echo "pick $wh $ws" >> "$todo_file"
    fi
  done

  # Run interactive rebase non-interactively using GIT_SEQUENCE_EDITOR.
  # GIT_SEQUENCE_EDITOR replaces the editor with a script that copies our pre-built todo.
  todo_final="$todo_file"
  GIT_SEQUENCE_EDITOR="cp ${todo_final}" git rebase -i "HEAD~${N}"

  rm -f "$todo_file"

  echo "  Squashed ${#run_hashes[@]} commits for ${task_id} into one."

  # Re-read git log after rebase to refresh SHAs for the next iteration
  read_log_newest_first
done

echo "Squash complete."
exit 0

