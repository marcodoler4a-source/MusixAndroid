# Musix Android — initial native Android port

This is an **Android Studio project**, not an already compiled APK. It is a fresh Android implementation based on the provided MeloGlass/Musix iOS repository. The iOS SwiftUI code cannot compile on Android.

## Features included
- Android device audio library scanning via MediaStore (permission requested at runtime)
- Songs, albums, artists, search and sorting
- Media3 ExoPlayer and MediaSessionService for playback/background system controls
- Now Playing, seek, skip, shuffle, repeat, mini player
- Dark blue UI and the original iOS app icon artwork

## Not yet ported
- LRC lyrics fetching/synchronization and floating lyrics
- Playlist creation and management, custom artwork, metadata editing, advanced library settings
- Exact iOS liquid-glass visuals and all advanced iOS-specific features
- Manual document-provider file importing (currently scans Android's media library)

## Build APK using GitHub Actions
1. Create a GitHub repository and upload **the contents of this folder to its root** (including `.github`).
2. Open the repository's **Actions** tab and run **Build Musix Android APK** (or push to main).
3. Open the completed run > **Artifacts** > download `Musix-Android-debug-APK`.
4. Extract the artifact and install `app-debug.apk` on your Android phone (allow installing apps from that source).

Alternatively open in Android Studio and select **Build > Build APK(s)**. The output is `app/build/outputs/apk/debug/app-debug.apk`.

The debug APK is signed automatically for testing. A public release requires a properly managed signing key and release configuration.

**Validation:** Source files and archive structure checked, but no Android SDK/Gradle build was available in this environment, so compilation and on-device behavior have not been verified.
