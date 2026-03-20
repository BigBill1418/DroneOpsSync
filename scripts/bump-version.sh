#!/usr/bin/env bash
# bump-version.sh — increment patch version in android/version.properties
# Usage:
#   bash scripts/bump-version.sh          # patch bump (default): 1.0.1 → 1.0.2
#   bash scripts/bump-version.sh minor    # minor bump:           1.0.1 → 1.1.0
#   bash scripts/bump-version.sh major    # major bump:           1.0.1 → 2.0.0
#   bash scripts/bump-version.sh 1.2.3    # set explicit version

set -euo pipefail

PROPS="$(git rev-parse --show-toplevel)/android/version.properties"

if [[ ! -f "$PROPS" ]]; then
  echo "ERROR: $PROPS not found" >&2
  exit 1
fi

current=$(grep '^VERSION_NAME=' "$PROPS" | cut -d= -f2 | tr -d '[:space:]')
if [[ -z "$current" ]]; then
  echo "ERROR: VERSION_NAME not found in $PROPS" >&2
  exit 1
fi

IFS='.' read -r major minor patch <<< "$current"

bump="${1:-patch}"

case "$bump" in
  major)
    major=$((major + 1)); minor=0; patch=0 ;;
  minor)
    minor=$((minor + 1)); patch=0 ;;
  patch)
    patch=$((patch + 1)) ;;
  [0-9]*.[0-9]*.[0-9]*)
    IFS='.' read -r major minor patch <<< "$bump" ;;
  *)
    echo "Usage: $0 [patch|minor|major|X.Y.Z]" >&2
    exit 1 ;;
esac

new="${major}.${minor}.${patch}"

# Write new version
sed -i "s/^VERSION_NAME=.*/VERSION_NAME=${new}/" "$PROPS"

echo "Version bumped: ${current} → ${new}"

# Stage and commit
git add "$PROPS"
git commit -m "chore: bump version to ${new} [skip ci]"

echo "Committed: chore: bump version to ${new} [skip ci]"
