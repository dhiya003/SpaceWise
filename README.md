# SpaceWise Android

SpaceWise 2.0 is a native, local-first Android storage cleaner.

## Implemented

- Real MediaStore scanning for photos, videos, audio and documents
- Installed application inventory
- Actual internal-storage totals
- Media previews using Android viewers
- Large-file review
- Exact duplicate detection using file size plus SHA-256 hashing
- One selected original retained from every duplicate group
- Android-protected deletion confirmation for duplicate copies
- Optional usage-access analysis for last-used application information
- No server upload and no website dependency

## APK from GitHub Actions

1. Open **Actions** → **Build Android APK**.
2. Open the latest successful **Release SpaceWise 2.0 native scanner** run.
3. Download the **SpaceWise-debug-apk** artifact.
4. Uninstall earlier SpaceWise test builds before installing this APK.

Android controls media access and deletion. SpaceWise cannot remove files without Android's confirmation.
