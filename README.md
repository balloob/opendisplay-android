# OpenDisplay Android

Android app that implements the [OpenDisplay](https://opendisplay.org/) WiFi protocol, turning any Android device into an OpenDisplay-compatible screen.

Built for the **AValue EPD-42S** (42" e-ink display running Android 5.1), but works on any Android 5.0+ device.

> **Note:** OpenDisplay over WiFi is still under development. This app currently only works with the experimental WiFi server in [py-opendisplay (wifi-server branch)](https://github.com/balloob/py-opendisplay/tree/wifi-server).
> 
> To run a server:
> ```
> uvx --from "py-opendisplay @ git+https://github.com/balloob/py-opendisplay@wifi-server" opendisplay-serve path/to/image_or_url.png
> ```

## How it works

1. App discovers OpenDisplay servers on the local network via mDNS (`_opendisplay._tcp`)
2. Connects over TCP and announces display capabilities (resolution, color scheme)
3. Receives images from the server and renders them full-screen
4. Polls for updates at the interval specified by the server

## Architecture

```
opendisplay-android/
├── opendisplay-java/     # Standalone Java library (pure Java, no Android deps)
│   └── src/main/java/org/opendisplay/
│       ├── OpenDisplayProtocol.java   # Packet framing, CRC16, encode/decode
│       ├── OpenDisplayClient.java     # TCP client with polling loop
│       ├── ImageDecoder.java          # Image bytes → ARGB pixel array
│       └── DisplayConfig.java        # Display capabilities config
├── app/                  # Android app
│   └── src/main/java/org/opendisplay/android/
│       ├── MainActivity.java          # Full-screen display + lifecycle
│       └── MdnsDiscovery.java         # Android NsdManager mDNS discovery
```

The **opendisplay-java** library is a standalone Java library with zero Android dependencies. It can be used in any Java application — the Android app is just one consumer. The library is tested against [py-opendisplay](https://github.com/balloob/py-opendisplay)'s WiFi server.

## Android compatibility

- **minSdkVersion**: 22 (Android 5.1 Lollipop)
- **targetSdkVersion**: 28
- **Java 7** source/target for maximum device compatibility
- Tested on the AValue EPD-42S (NXP i.MX 7Dual, Android 5.1)

## Building

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Testing

The Java library has unit tests and integration tests that automatically start a [py-opendisplay](https://github.com/balloob/py-opendisplay) WiFi server:

```bash
./gradlew :opendisplay-java:test
```

Requires `uv` and Python 3.11+ for the integration tests.
If `py-opendisplay` is checked out somewhere else, set `PY_OPENDISPLAY_DIR=/path/to/py-opendisplay`.

## Server

Use [py-opendisplay](https://github.com/balloob/py-opendisplay) to serve images:

```bash
cd py-opendisplay
uv run opendisplay-serve photo.png
```

## Protocol

Implements the [OpenDisplay Basic Standard](https://opendisplay.org/protocol/basic-standard.html) WiFi protocol with uint32 frame lengths for large display support. Supports all 5 color schemes (monochrome, BW+red, BW+yellow, BW+red+yellow, 6-color).

## License

MIT
