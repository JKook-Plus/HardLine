# Building

## What you need

- JDK 17 or newer. Releases are built with JDK 21.
- The Android SDK with platform 36. Set `ANDROID_HOME`, or put `sdk.dir=/path/to/sdk` in
  `local.properties`.
- `bash`, `curl`, `tar` and `patch`.

Gradle installs the NDK (28.2.13676358) and CMake (3.31.6) itself if you have accepted the SDK
licences. To accept them, run `sdkmanager --licenses`.

## Build

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/HardLine-<version>-debug.apk
./gradlew assembleRelease        # app/build/outputs/apk/release/HardLine-<version>-release.apk
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew lintDebug              # Android lint; errors fail the build
```

`source scripts/env.sh` puts `adb` and the emulator on the path. It leaves `ANDROID_HOME` and
`JAVA_HOME` alone if you have set them.

## Native dependencies

libusb and libuvc are not in the repository. Before the native build, Gradle runs
`scripts/fetch-deps.sh`, which:

1. downloads libusb 1.0.30 and libuvc 0.0.8 into `third_party/`,
2. checks each archive against a SHA-256 checksum written in the script,
3. applies the patches in `app/src/main/cpp/patches/`.

It does nothing when the sources are already there, so only the first build needs a network
connection. To change a version, edit the URL and checksum in the script.

There is one patch. It adds the H.265 format identifiers to libuvc so that cameras with an H.265
stream have a usable format.

If you open the project in Android Studio before building once, run `scripts/fetch-deps.sh`
yourself first. Studio configures CMake during sync, before any Gradle task has run.

## Windows

The dependency script needs a POSIX shell. Use WSL, or run Gradle from Git Bash.

## Signing a release

Without a keystore, `assembleRelease` signs with the debug key. The APK installs, but it cannot
update a copy that was signed with a different key.

To sign with your own key, create `keystore.properties` in the repository root:

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Both that file and `*.jks` are git-ignored. Keep the keystore somewhere safe as well. Android only
installs an update over an existing copy when both carry the same signature, so a lost key means
every user has to uninstall and start again.

## Releases on GitHub

Pushing a tag such as `v0.2.0` runs `.github/workflows/release.yml`. It builds the release APK,
signs it, and attaches it and its checksum to a draft GitHub release. The workflow reads the
keystore from four repository secrets:

| Secret | Contents |
|---|---|
| `KEYSTORE_BASE64` | The keystore file, base64-encoded: `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | The keystore password |
| `KEY_ALIAS` | The key's alias |
| `KEY_PASSWORD` | The key's password |

Before tagging, raise `versionCode` and `versionName` in `app/build.gradle.kts`, add the version to
`CHANGELOG.md`, and add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## The icon and logo

`branding/build.py` describes the icon once and `branding/build.sh` writes everything from it: the
SVG and PNG files in `branding/`, the launcher and notification drawables in `app/src/main/res`,
and the store images in `fastlane/`. It needs Docker. Do not edit the generated drawables by hand.
