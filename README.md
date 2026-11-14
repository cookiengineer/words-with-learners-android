# Words with Learners for Android

This is the Android version of the [Words with Learners](https://github.com/cookiengineer/words-with-learners-game) game.

The Android app wraps the game's Go server in a local foreground service and displays the game in a
WebView. The server listens on an ephemeral port and is reachable from other devices on the same
Wi-Fi or LAN networks, and allows to invite other players to locally join for a game.

## Features

- Local game server embedded in the APK.
- Full-screen WebView pointing at `http://127.0.0.1:<port>`.
- Foreground service keeps the server alive during other players' turns.
- LAN invites through the game's **Invite** button.
- Server binary built for `arm64-v8a` and `x86_64`.

## Building

This repository expects the game repository as a sibling directory:

```text
words-with-learners-game/       # The game repository
words-with-learners-android/    # This repository
```

The toolchain runs in a Podman container based on Debian Bookworm, including the Android SDK, NDK,
Go, and Gradle.

```sh
make image;    # build the container image using podman
make apk;      # build the APK using podman container image
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device with:

```sh
adb devices;     # check if your device is connected
make install;    # install the APK via ADB
```

Set the `GAME_DIR=/path/to/words-with-learners-game` environment variable if the game repository
is located elsewhere.

## License

This project is licensed under the GNU Affero General Public License v3.0 (`AGPL-3.0-only`),
matching the upstream game's license. See [LICENSE.txt](LICENSE.txt) for the full text.
