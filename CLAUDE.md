# Claude Code Instructions — DroneOpsSync

## MANDATORY: Bump version before every push to main

**This rule is non-negotiable. Never push to main (directly or via PR) without first bumping the version.**

### How to bump

The single source of truth is `android/version.properties`:

```
VERSION_NAME=<major>.<minor>.<patch>
```

- `versionCode` is auto-calculated from the git commit count in `build.gradle` — do not touch it.
- `VERSION_NAME` must be bumped manually before every push.

**Default rule: increment the patch number** (e.g. `1.0.1` → `1.0.2`).
Use minor bumps (`1.0.x` → `1.1.0`) for new features, major bumps (`1.x.x` → `2.0.0`) only when explicitly instructed.

### Steps (in order)

1. Edit `android/version.properties` — increment `VERSION_NAME`
2. Run the bump script to verify and commit it:
   ```bash
   bash scripts/bump-version.sh
   ```
   Or do it manually:
   ```bash
   # Edit android/version.properties, then:
   git add android/version.properties
   git commit -m "chore: bump version to <new-version> [skip ci]"
   ```
3. Push the branch and open/merge the PR as normal.

### Why

- `BuildConfig.VERSION_NAME` is shown in the Settings screen footer.
- `versionCode` increments automatically via commit count — but only `VERSION_NAME` communicates a meaningful release to users.
- Every push to main = a releasable build. The version must always reflect that.

---

## Workflow summary

1. Make code changes on `claude/...` branch
2. Commit changes with descriptive message
3. **Bump version in `android/version.properties`** (patch++ by default)
4. Commit the version bump: `chore: bump version to X.Y.Z [skip ci]`
5. Push branch: `git push -u origin <branch>`
6. Create PR: `gh pr create --repo BigBill1418/DroneOpsSync --base main`
7. Merge PR: `gh pr merge <number> --repo BigBill1418/DroneOpsSync --squash`
