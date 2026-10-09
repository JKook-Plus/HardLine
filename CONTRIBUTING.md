# Contributing

Thanks for taking an interest. The most useful contributions right now are reports of how real
cameras behave, because the app has been tested with very few.

## Reporting a camera

Open a [camera report](https://github.com/JKook-Plus/HardLine/issues/new?template=camera_report.yml),
whether the camera worked or not. In the app, More, Device details shows the USB ID and the list of
formats. Copy that in.

## Reporting a bug

Say what you did, what you expected and what happened. Include the phone model, the Android
version, the HardLine version from Settings, About, and the camera's USB ID. For crashes and
streaming problems, a log helps a great deal:

```sh
adb logcat -d > hardline.log
```

Read the log before you attach it. It can contain addresses on your network.

## Setting up

[docs/BUILDING.md](docs/BUILDING.md) covers the build. [docs/TESTING.md](docs/TESTING.md) covers
the virtual camera, which lets you work on the app without owning the hardware.
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) says where things are.

## Pull requests

- Keep a pull request to one change. A bug fix and a refactor are two pull requests.
- Run `./gradlew testDebugUnitTest lintDebug` before you push. CI runs the same.
- Say in the description how you tested the change: unit test, virtual camera, or which real camera
  and phone. "Not tested on hardware" is a fine thing to write, as long as you write it.
- If the change is visible to users, add a line to `CHANGELOG.md` under an `Unreleased` heading.
- For protocol and container code in `net/` and `media/`, add or extend a unit test. That code has
  exact right answers and the tests are cheap.

## Code style

- Kotlin follows the official style, with lines up to 160 characters. `.editorconfig` has the
  details.
- Comments say why, or state a fact the code cannot show, such as a quirk of a camera or a protocol.
  A comment that repeats the line below it gets deleted in review.
- User-facing text is plain English in sentence case. No exclamation marks.
- New settings are typed keys in `core/Settings.kt`, in the group where the settings screen shows
  them.
- Do not add a dependency for something a hundred lines of Kotlin can do. The APK is 3 MB and
  staying small is a goal.

## Licence

By contributing you agree that your contribution is licensed under the GNU General Public License,
version 3, the same as the rest of the project.
