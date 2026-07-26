#!/usr/bin/env bash
set -euo pipefail

scan_root="${1:-.}"
cd "$scan_root"

tracked_files=()
while IFS= read -r -d '' file; do
  tracked_files+=("$file")
done < <(git ls-files -z)

if ((${#tracked_files[@]} == 0)); then
  echo "No tracked files to scan."
  exit 0
fi

failed=0

if grep -nE 'github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}' "${tracked_files[@]}"; then
  echo "Potential GitHub token found."
  failed=1
fi

if grep -nE '[?&]hash=[A-Za-z0-9_-]{16,}' "${tracked_files[@]}"; then
  echo "Potential live WSS hash found."
  failed=1
fi

if grep -nE '"accessToken"[[:space:]]*:[[:space:]]*"[A-Za-z0-9._-]{20,}"' "${tracked_files[@]}"; then
  echo "Potential live access token found."
  failed=1
fi

if ((failed)); then
  exit 1
fi

echo "Secret scan passed."
