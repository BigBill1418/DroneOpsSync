# Claude Code Instructions — DroneOpsSync

## Version bumps are fully automated — do NOT bump manually

The CI pipeline handles versioning automatically. **Never add a version bump commit to a PR.**

- `VERSION_NAME` in `android/version.properties` is bumped by the "Bump patch version" workflow the moment a PR lands on main.
- `versionCode` is auto-calculated from git commit count in `build.gradle`.
- The release APK is built and published automatically after the bump completes.

### Why manual bumps break the pipeline

A manual `[skip ci]` version bump commit in a PR gets folded into the squash merge body. GitHub sees `[skip ci]` anywhere in the HEAD commit message and suppresses **all** push-triggered workflows — including "Bump patch version" — so the release never fires.

---

## Workflow summary

1. Make code changes on `claude/...` branch
2. Commit changes with a descriptive message (no version bump)
3. Push branch: `git push -u origin <branch>`
4. Create PR: `gh pr create --repo BigBill1418/DroneOpsSync --base main`
5. Merge PR: `gh pr merge <number> --repo BigBill1418/DroneOpsSync --squash`

CI takes it from there: bump → build → publish release automatically.
