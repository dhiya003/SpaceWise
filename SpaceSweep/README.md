# SpaceSweep — Android cleanup-first prototype

Status: native source implementation, not yet an installable or device-tested app.
The standalone duplicate engine and deletion policy passed 32 tests on Java 17. Java source
syntax parsing also passed (this does not check Android API types). Android compilation,
UI verification, permission-dialog testing and end-to-end deletion testing are pending.
The build environment requires approval of the Android SDK licence before installing
Android platform 35 and build-tools 35.0.0. No APK is included.

## Implemented in source

- Android 11+ native interface; no website wrapper.
- Cleanup home: duplicate copies first, large files second, screenshots for manual review.
- Exact media duplicate detection: size grouping, streaming SHA-256, then full byte comparison.
- One protected retained copy per group; user can choose another keeper.
- Favorites are excluded from automatic duplicate removal.
- Revalidation of retained and duplicate bytes before requesting deletion.
- Select up to 100 duplicate copies at once; persistent delete button shows selected file bytes and opens a selection review.
- Large photos, videos, audio and documents sorted by size, with previews.
- Folder totals lead directly to file selection.
- Android's media deletion approval; explicit irreversible-deletion warning.
- User-granted document-folder browsing and deletion through Storage Access Framework.
- Background cancellable scan, bounded thumbnail queue and fixed-size result pages.
- Metadata and favorite/write-capability checks before deletion; document checks repeated after media consent.
- Interrupted cleanups never replay outstanding document deletes; files refresh automatically after cleanup.
- No internet permission or cloud processing. Only the Android local external-storage document provider is accepted.

## Scope and limitations

This is an initial prototype, not a completed production storage manager. Do not test deletion
on valuable files until Android build and device acceptance tests have passed. Use disposable
copies and a verified backup first.

Only accessible indexed media and selected document folders are inspected. Other applications'
private data, private caches, protected Android directories and ungranted folders cannot be
fully analysed. The app links to system storage settings for those areas. No all-files-access
permission is requested. Android may restrict selecting the storage root and Downloads folder.

Original media access is required for photo/video duplicate comparison to avoid comparing
redacted content as if it were original bytes. Without it, those files remain available for
large-file review but are excluded from exact matching. No GPS data is interpreted or uploaded.

The retained copy prefers a favorite, then a DCIM/Camera file, then the oldest modification time.
That is not a guarantee of historical original-file provenance. Similar-looking images,
re-encoded videos and edited copies are not classified as exact duplicates.

Document duplicate detection, perceptual similarity, resumable scans, persistent hash caching,
individual app-size ranking and app-cache deletion are not implemented. User-granted document
folders exclude media to avoid counting the same media file via two different access routes.
Selected document folders must use `com.android.externalstorage.documents` (internal storage
or SD card). Cloud and other providers are rejected; some OEM-specific local providers will
therefore also be unavailable in this prototype.

Deletion is permanent, not trash. Android consent is still required for media. Available
filesystem space may differ from the sum of removed file sizes. Deletion results are conservative:
an inaccessible media verification query is reported as unconfirmed, not as a successful removal.
Documents use the local provider's successful deletion response rather than assuming a query
on an already-deleted URI must return an empty cursor.
No software can prevent another application changing files between validation and the system
confirmation dialog; retain backups. Activity recreation cancels scans and uses a persisted
interruption marker to prevent automatic replay of pending deletion. An operation already in
progress can still finish. Lifecycle handling still needs device testing before release.

See `REVIEW.md` for the self-review findings, fixes and remaining verification limits.

## Build on Mac / Android Studio

1. Install Android Studio and JDK 17.
2. Review and accept the Android SDK licence yourself when prompted.
3. Install Android SDK Platform 35 and Build Tools 35.0.0.
4. Open this folder as a Gradle project. Use Gradle 8.11.1 (AGP 8.9.2).
5. Sync, then build the debug APK and install on a disposable/test Android device.

This archive does not include a Gradle wrapper binary. With Gradle 8.11.1 installed:

```sh
gradle wrapper --gradle-version 8.11.1
./gradlew :app:assembleDebug
```

The resulting APK is `app/build/outputs/apk/debug/app-debug.apk`.
Google Play release requirements and restricted media-permission eligibility must be reviewed
before publishing. This source targets SDK 35 for a test build, not a claim of current store eligibility.

## Run the standalone safety tests

From this project folder, with JDK 17:

```sh
javac -d /tmp/spacesweep-tests app/src/main/java/com/verve/spacesweep/DuplicateEngine.java tests/DuplicateEngineTest.java
java -cp /tmp/spacesweep-tests com.verve.spacesweep.DuplicateEngineTest
```

If `javac` is not on PATH but the compiler module is installed, replace `javac` with
`java com.sun.tools.javac.Main`.

## Device acceptance checklist (not yet executed)

- Full, partial, denied and revoked photo/video/audio permissions on Android 11, 13, 14 and 15.
- Grant/deny original-media access and ensure no redacted false-positive matches.
- Disposable identical photos and videos: selected extras removed, keeper readable afterwards.
- Same-size different files, edited photos and re-encoded videos never auto-selected.
- Favorites protected; changing keeper preserves one copy.
- Missing/modified retained copy cancels duplicate deletion.
- Cancel both app confirmation and Android system confirmation without deleting documents.
- Read-only and revoked document grants, nested and overlapping folders, provider failures.
- Rotation, backgrounding, process death and returning from preview/settings/system dialogs.
- Large collections and giant duplicate groups: responsiveness, thumbnail queue size and memory.
- Compare real available storage before and after; avoid claiming file-byte totals as physical savings.

## Platform references

- https://developer.android.com/training/data-storage/shared/media
- https://developer.android.com/training/data-storage/manage-all-files
- https://developer.android.com/build/releases/agp-8-9-0-release-notes
