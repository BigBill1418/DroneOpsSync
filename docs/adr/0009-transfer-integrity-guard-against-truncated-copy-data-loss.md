# ADR-0009: Transfer-integrity guard against truncated-copy data loss

- Status: Accepted
- Date: 2026-07-02
- Related: ADR-0006 (SAF persisted WRITE grant for delete)

## Context

An audit found a critical data-loss path for irreplaceable field flight logs.
`SafTreeSource.scan()` copied a controller log with `input.copyTo(output)` and never
compared the landed byte count to the source length. `InputStream.copyTo` returns
normally on a short/interrupted content-provider read, so a **truncated** cache file
could parse server-side, be marked `imported` → `SYNCED`, and then become eligible for
the delete-from-controller flow — replacing the only copy of the field data with a
partial one and deleting the original.

The client trusted the server's SHA-256 only for *dedup*, never for *integrity of this
transfer*, and never asserted a content length client-side.

## Decision

Add a minimal, client-only, fail-safe integrity guard (pure, JVM-testable module
`TransferIntegrity.kt`, mirroring the `UploadOutcome.kt` seam):

- After the SAF copy, capture the source length and compare it to the copied length.
  On a **known** mismatch (source length > 0 and unequal), discard the cache file and
  skip the file so it is re-copied next scan — never proceed to upload/delete.
- Tag each log with `verifiedTransfer`. A file is delete-eligible only when it is
  `SYNCED`/`DUPLICATE` **and** `verifiedTransfer` is true.
- When the source length is unknown (`0`/`-1`, a provider that hides length), the file
  still uploads but is `verifiedTransfer = false`, so it is **never** auto-deleted —
  conservatively keeping the original rather than risking deletion on an unverifiable
  transfer.

## Consequences

- A truncated transfer can no longer be recorded as synced and then delete the
  original; the destructive delete is gated on a verified byte-count match.
- Length-only integrity catches truncation (the observed field failure) but not a
  same-length bit-flip. Full end-to-end integrity would need the server to echo the
  received SHA-256 for the client to assert against — recommended follow-up.
- The Android-runtime edits (`SafTreeSource`, `MainViewModel`, `FlightLog`) were
  inspection-verified only in the fix environment (no JDK/Android SDK available); the
  instrumented JVM suite must pass in CI before this is trusted in the field.
