# Sound Amplifier (Flutter)

A Flutter UI over a native Android foreground service that captures ambient
sound from the phone's mic and streams it live to your connected Bluetooth
earphones — the same idea as Google's "Sound Amplifier" or a personal
hearing-aid app.

## Why there's native Android code in a Flutter project

Dart/Flutter has no API for raw, low-latency audio loopback (mic straight
to speaker output). So the actual audio work lives in
`android/app/src/main/kotlin/.../AmplifierService.kt`, written in Kotlin,
exactly like a normal native Android app. Flutter (`lib/main.dart`) is just
the UI: a Start/Stop button and a gain slider, talking to that service
through a `MethodChannel` (`soundamp/control`).

## Background operation

This is the "additional feature" you asked for, and it comes from how the
service is built, not from anything in Dart:

- `AmplifierService` is a **foreground service**, started with
  `startForegroundService()`. Once running, it keeps capturing and playing
  audio independent of the Flutter UI or Activity — minimizing the app,
  switching to another app, or turning the screen off does **not** stop it.
- It only starts when you explicitly tap "Start Listening", and Android
  requires (and this app shows) a **persistent, non-dismissible
  notification** the entire time the mic is active. There is no silent or
  hidden background mode — that's intentional, so it's always obvious to
  the phone's owner (and anyone glancing at their notifications) that the
  mic is live.
- It stops when you tap "Stop Listening", when you swipe it away from the
  notification (if you add a stop action — see "possible improvements"
  below), or if Android kills it under extreme memory pressure — it will
  not silently restart itself (`START_NOT_STICKY`).
- No network permission is requested anywhere in this project; audio never
  leaves the device.

## Project layout

```
lib/main.dart                                     Flutter UI + MethodChannel calls
android/app/src/main/kotlin/.../MainActivity.kt    Routes Flutter calls to the service
android/app/src/main/kotlin/.../AmplifierService.kt  Actual mic capture + earphone playback
android/app/src/main/AndroidManifest.xml           Permissions + foreground service declaration
```

Only the Android platform is implemented. iOS would need a comparable
native module written in Swift (AVAudioEngine for the loopback, plus the
"Audio" Background Mode capability) — it's a similar amount of work but a
different API surface, and isn't included here.

## Setup

1. Install [Flutter](https://docs.flutter.dev/get-started/install) (stable
   channel) and open this folder in your editor of choice.
2. From the project root, run:
   ```
   flutter pub get
   ```
3. `android/local.properties` isn't included (it's machine-specific and
   normally auto-generated). If it's missing, create it pointing at your
   SDKs:
   ```
   flutter.sdk=/path/to/flutter
   sdk.dir=/path/to/Android/sdk
   ```
   Opening the project once with `flutter run` or in Android Studio will
   also generate this automatically.

## Running

```
flutter run
```

Use a physical device — emulators don't have real mic input, and you'll
want actual Bluetooth earphones paired to hear the effect.

## Building an APK to share

```
flutter build apk --release
```

The APK lands at `build/app/outputs/flutter-apk/app-release.apk`. This is
signed with the Flutter debug-fallback key configured in
`android/app/build.gradle.kts`, which is fine to test with but not for the
Play Store. For a properly signed release, follow Flutter's official guide
on [signing the app](https://docs.flutter.dev/deployment/android#sign-the-app),
which walks through generating a keystore and wiring it into
`signingConfigs`.

## Notes / things you may want to tune

- **Latency**: Bluetooth media audio typically adds 100–300ms of delay.
- **Gain**: linear multiplier (0x–4x) with clipping protection; for more
  natural loudness you could add compression/limiting.
- **Stop-from-notification**: right now you stop from the app UI. You could
  add a "Stop" action button directly on the persistent notification by
  extending `buildNotification()` in `AmplifierService.kt` with an
  `addAction()` tied to a `PendingIntent` that sends `ACTION_STOP`.
- **Permissions**: requests `RECORD_AUDIO` (and notification permission on
  Android 13+) at runtime via the `permission_handler` package before
  starting.

## Minimum SDK

`minSdk 26` (Android 8.0) — required for the `microphone` foreground
service type and the adaptive launcher icon used here.

## Modes

- **Phone → Earphones**: phone mic is played live into your Bluetooth earphones.
- **Earphones → Phone**: your Bluetooth earphone's microphone is played on the
  phone speaker. Bluetooth earphones only expose their mic through the call
  profile (SCO), so audio is 16 kHz voice quality at best, and routing to the
  phone speaker while a call-style connection is open depends on the phone.
  Keep the phone away from the earphones to avoid feedback. Needs the
  Bluetooth permission on Android 12+.
