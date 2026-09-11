# SpaceSweep self-review — 11 September 2026

Review status: source fixes completed; standalone tests passed. Android build and device
verification remain pending. The Android SDK licence has not been accepted on the user's behalf.

## Findings and fixes

| Finding | Impact | Fix in source |
| --- | --- | --- |
| Deletion trusted old favorite/write/size/timestamp metadata | A newly favorited or changed file could be removed using a stale selection | Validate current metadata for every target; repeat document checks immediately before deletion |
| Selection guard existed mainly in UI | Empty, stale or excessive target sets lacked independent validation | Immutable batch builder rejects missing IDs, favorites, read-only files and batches over 100; deduplicates targets |
| Interrupted media cleanup lost in-memory targets | Returning after recreation could misleadingly report zero deletions or resume remaining work incorrectly | Persist an interruption marker before side effects; never replay documents after recreation; require refreshed results |
| Successful document deletion was followed by querying the deleted URI | Providers can throw for a removed URI, falsely reporting successful cleanup as unconfirmed | Use the local provider's boolean delete response; preserve conservative media verification |
| Cleanup results stayed stale until another user action | Deleted files and old savings could stay visible | Invalidate the old list and automatically rescan after cleanup; block deletion after an incomplete scan |
| Thumbnail executor had an unlimited queue | Navigating repeatedly could retain views and waste I/O/memory | Bound queue to 120, cancel obsolete thumbnail work, guard results by render generation |
| Large groups and “show more” accumulated unlimited views | Large libraries could freeze the screen or exhaust memory | Fixed result pages: 30 large files, 5 duplicate groups and 10 copies per group |
| Video comparison allocated two arrays every 64 KiB | Multi-GB videos caused excessive allocation and garbage collection | Reuse comparison buffers; check cancellation inside short-read loops |
| Hash streams were closed twice | Providers that reject repeated close could create false scan failures | Single owning buffered stream; regression test verifies one close |
| Cancellation was checked only inside populated loops | Empty scans could report completion after cancellation; slow short reads delayed Stop | Check at scan entry and within block reads; add provider query cancellation |
| Document folder picker could include cloud providers | Remote files could be deleted under a phone-cleanup label | Local-only picker hint plus explicit local external-storage provider allowlist |
| Null document sizes became zero / unknown values affected totals | Misleading space estimates and deletion choices | Show unknown size, exclude it from totals, disable deletion until a known-size scan |
| “DCIM/CameraBackup” matched “DCIM/Camera” | Wrong keeper preference | Require the full Camera directory boundary |
| Binary byte calculations were labelled GB/MB | Display units were inaccurate | Use GiB/MiB/KiB labels |
| Previous category filters leaked into new navigation | “Largest files” could unexpectedly remain filtered | Reset filter when navigating; add direct selected-file review |

## Verification performed

- Compiled and ran DuplicateEngine and its policy tests with the Java 17 compiler module.
- 32 tests passed, including changed/missing keeper, new favorite, lost write capability,
  stale IDs, read-only targets, duplicate target IDs, batch limit, stream lifecycle,
  cancellation and multi-block media equality.
- Parsed both application Java source files with javac's syntax parser, without Android
  type resolution. This is not an Android compilation or runtime test.
- Reviewed Android media, DocumentsContract and Android 14 partial-access documentation:
  https://developer.android.com/training/data-storage/shared/media
  https://developer.android.com/reference/android/provider/DocumentsContract
  https://developer.android.com/about/versions/14/changes/partial-photo-video-access

## Remaining risks and work

1. No APK build, emulator run, layout screenshot or real device deletion test has been completed.
   Platform API compatibility and OEM differences remain unverified. Do not call this production-ready.
2. Android's approval dialog is outside app control. Another app may modify or delete a keeper
   after preflight verification; the consent flow is not an atomic compare-and-delete transaction.
3. Local media opening can still block in a provider call; cancellation cannot guarantee instant
   interruption of every filesystem/provider read. There is no persisted scan cache or resume.
4. Rotation/process-death behavior is designed to stop remaining operations, not complete the whole
   batch in the background. An already-issued operation may finish; the next scan is authoritative.
5. Restricting documents to the platform external-storage provider deliberately excludes some OEM
   local providers. Broader provider support needs a verified locality contract.
6. Full device/private app data analysis, private-cache removal, document duplicate detection,
   visual-similarity classification and app-size ranking are not implemented.
7. Review/confirmation is required for permanent deletion. Files are not moved to trash and the
   app has no undo. Device acceptance testing must use disposable fixtures before real data.

The fixes above were reviewed at source level. Only the standalone engine/policy claims have
executable regression-test evidence; lifecycle, UI, provider and permission fixes need Android testing.
