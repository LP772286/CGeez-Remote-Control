# CGeez RC

An Android app that turns your phone into a background remote for **LG webOS TVs**. Connect once over Wi-Fi, and a Bluetooth or USB remote keeps controlling the TV, even with the phone locked and the app closed.

> Unofficial. Not affiliated with or endorsed by LG. "LG" and "webOS" are trademarks of LG Electronics.

- Package: `com.cgreez.remote.control`
- Version: 6.6
- Android 8.0+ (API 26)

## Features

- **Works locked.** Runs as a background service and restarts after a reboot.
- **Keeps the phone screen off.** If a remote key wakes the phone, the app locks it again straight away (Android 9+).
- **Real remote behavior**
  - Back: tap = Back, hold = Exit (sent once).
  - OK: tap = OK (on release), hold = one hold event, never OK, OK, OK. Optional **real hold over Bluetooth** (Settings > OK hold): the phone pairs with the TV as a keyboard and a held OK is a true held key, so long-press menus open.
  - Arrows, volume and channel repeat while held.
- **Reliable volume.** If the TV ignores the direct command, the same press is sent as a normal remote key.
- **Program any button** to a TV key, an app launch or a command.
- **On-screen remote:** D-pad, volume, channel, media keys, app launcher with the TV's own icons, pop-up messages on the TV, Wake-on-LAN.

## Setup

1. Download the latest APK from the [Releases](../../releases) page, install it and open the app.
2. Enter the TV's IP address (TV and phone on the same Wi-Fi) and connect.
3. Accept the pairing prompt on the TV.
4. Enable the app's **Accessibility service** in Android settings. This is how it reads remote keys while the phone is locked.
5. Choose your remote in the app (use the learn function and press a key on it).
6. Turn off battery optimization for the app if your phone kills background apps.
7. Optional, for a real OK hold (long-press menus): open Settings > OK hold, turn on "Real hold over Bluetooth", tap "Make phone visible", then pair the phone from the TV's Bluetooth settings.

## Build

To build it yourself you need JDK 17, the Android SDK (platform 34) and Gradle 8.7:

```bash
gradle :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Limitations

- LG webOS TVs only.
- The phone and TV must be on the same network.
- The phone screen may flash for a moment when a remote key wakes it.
- LG's Wi-Fi remote channel has no "key down / key up", so over Wi-Fi an OK hold can only be soft OK taps. For a real long press, turn on "Real hold over Bluetooth" in Settings and pair the phone to the TV as a keyboard (Android 9+).
- With external speakers (ARC) or some HDMI inputs, volume uses plain remote keys instead of the direct command.
- The connection to the TV skips certificate checks, because the TV uses a self-signed certificate. Use it only on your own home network.
- The project is signed with the included debug keystore, so it is not tamper-proof.

## License

MIT. See `LICENSE`.
