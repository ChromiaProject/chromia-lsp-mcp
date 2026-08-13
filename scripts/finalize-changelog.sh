#!/bin/sh
# Renames the "## [Unreleased]" section in CHANGELOG.md to the version and date being released,
# puts a fresh empty Unreleased heading above it, and refreshes the compare links at the bottom.
# Run by release-patch/release-minor right after gradle.properties is bumped, before committing.
set -eu

if [ $# -ne 2 ]; then
  echo "Usage: finalize-changelog.sh <previousVersion> <nextVersion>" >&2
  exit 1
fi

previous=$1
version=$2
project_url=${CI_PROJECT_URL:-https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp}
branch=${CI_COMMIT_REF_NAME:-dev}
today=$(date -u +%Y-%m-%d)
changelog=CHANGELOG.md

if ! grep -q '^## \[Unreleased\]$' "$changelog"; then
  echo "No \"## [Unreleased]\" section in $changelog; leaving it untouched."
  exit 0
fi

# Rename the Unreleased heading to this version and open a fresh empty one above it.
awk -v ver="$version" -v today="$today" '
  !renamed && /^## \[Unreleased\]$/ {
    print "## [Unreleased]"
    print ""
    print "## [" ver "] — " today
    renamed = 1
    next
  }
  { print }
' "$changelog" > "$changelog.staged"

# Drop the stale Unreleased link so re-running cannot duplicate it, then put both links back
# above the first existing version link.
grep -v '^\[Unreleased\]:' "$changelog.staged" > "$changelog.body"
{
  printf '[Unreleased]: %s/-/compare/%s...%s\n' "$project_url" "$version" "$branch"
  printf '[%s]: %s/-/compare/%s...%s\n' "$version" "$project_url" "$previous" "$version"
} > "$changelog.links"

if grep -qE '^\[[0-9]' "$changelog.body"; then
  first_link=$(grep -nE '^\[[0-9]' "$changelog.body" | head -1 | cut -d: -f1)
  head -n "$((first_link - 1))" "$changelog.body" > "$changelog"
  cat "$changelog.links" >> "$changelog"
  tail -n "+$first_link" "$changelog.body" >> "$changelog"
else
  cat "$changelog.body" > "$changelog"
  printf '\n' >> "$changelog"
  cat "$changelog.links" >> "$changelog"
fi

rm -f "$changelog.staged" "$changelog.body" "$changelog.links"
echo "CHANGELOG.md: [Unreleased] -> [$version] — $today"
