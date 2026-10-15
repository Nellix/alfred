# Alfred Android client

This module is Alfred's independent Android frontend and semantic device runtime. It uses Android
public APIs and includes no Mobilerun Portal source, resource, or binary dependency.

Implemented behavior:

- Animated pet states for listening, thinking, acting, asking, success, results, and failure.
- Android speech recognition with offline recognition requested when available.
- Built-in routes for Settings, Maps, YouTube, mail, and safe messaging drafts.
- A clean-room accessibility service with redacted semantic nodes, live-node reacquisition,
  bounded actions, post-action verification, and ambiguous-target rejection.
- A private on-device generalized-skill cache with confidence and failure-based disablement.
- A direct GitHub skill catalog client with revision/digest/schema/policy validation and atomic
  updates. Users can refresh and enable/disable skills in the Skill Library.
- Explicit generalized-skill export for a human-reviewed GitHub pull request; no background upload.
- Optional planner fallback. Model credentials remain outside the APK.
- Separate Gmail inbox-row and confirmed body-summary skills. Body content is processed locally.

Build and install:

```bash
cd android
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app works without the Python planner for built-in, learned, and downloaded skills. To test
novel tasks through the optional planner:

```bash
# From the repository root, after configuring provider environment variables:
.venv/bin/sma serve --host 127.0.0.1 --port 8765
adb reverse tcp:8765 tcp:8765
```

Use `http://127.0.0.1:8765` in Alfred Settings. The GitHub registry URL is a separate setting and
defaults to `https://raw.githubusercontent.com/Nellix/alfred-skills/main`.

Plain HTTP is accepted only for loopback/emulator testing. Accessibility access is powerful: use a
test device/account, avoid sensitive apps, and disable the service or uninstall the app after
testing.
