# adb wireless pairing: third-party notice

The Java code in this folder (`dev.sprocket.app.adb`) is a port of the adb wireless-pairing client in **Mika**:

- Project: https://github.com/wangzan101/Mika
- Commit: `d6bdb3aa3066fcfd482c10fc8e258553dc360dcc`
- License: Apache License 2.0 (full text: `LICENSE-Mika.txt` in this folder)
- Mika's NOTICE file reads:

  ```
  Mika
  Copyright 2026 wangzan101
  ```

## Source files

| Mika file (Kotlin) | Java port |
| --- | --- |
| `core/src/main/kotlin/com/wangzan101/mika/core/adb/Ed25519.kt` | `EdPoint.java` |
| `core/.../adb/Spake2.kt` | `Spake2.java` |
| `core/.../adb/PairingAuth.kt` | `Hkdf.java`, `PairingCipher.java`, `PairingAuth.java` |
| `core/.../adb/AdbMessage.kt` | `AdbMessage.java` |
| `core/.../adb/AdbPairing.kt` | `AdbPairing.java`, `AdbPairingException.java`, `AdbPeerInfo.java`, `AdbServiceTypes.java`, `AdbServiceFinder.java` |
| `core/.../adb/AdbTls.kt` | `AdbTls.java`, `KeyingMaterialExporter.java` |
| `core/.../adb/AdbIdentity.kt` | `AdbIdentity.java`, `AdbIdentitySource.java`, `AdbKeyStore.java`, `AdbKeyState.java`, `AdbKeyUnreadableException.java`, `WrappedKeyFile.java`, `AdbPublicKey.java`, `Pkcs8Rsa.java`, `SelfSignedCertificate.java` |
| `core/.../adb/AdbConnection.kt` | `AdbConnection.java`, `AdbConnectionException.java` |
| `core/.../adb/AdbConnectPort.kt` | `AdbConnectPort.java`, `PortCache.java`, `ConnectCandidates.java` |
| `core/.../adb/LocalPortScan.kt` | `LocalPortScan.java`, `LocalPortScanner.java`, `NioPortScanner.java`, `ScanResult.java`, `AdbEndpoint.java`, `OwnListeningPorts.java`, `PortRange.java` |
| `core/.../adb/AdbHosts.kt` | `AdbHosts.java` |
| `app/src/main/java/com/wangzan101/mika/cap/phone/shell/adb/AndroidAdb.kt` | `AndroidAdb.java` (Android only) |
| `app/src/main/java/com/wangzan101/mika/cap/phone/shell/adb/NsdAdbServiceFinder.kt` | `NsdAdbServiceFinder.java` (Android only) |
| `core/src/test/kotlin/com/wangzan101/mika/core/adb/*.kt` | `android/test/AdbTest.java` |
| `core/src/test/resources/adb/spake2-vectors.txt` | `android/test/adb-spake2-vectors.txt` (unchanged copy) |

Each Java file starts with a comment naming its Mika source file.

## Changes made in the port

- Kotlin to plain Java 11: no Kotlin runtime, no coroutines. Mika's `suspend` functions (`AdbServiceFinder.findPorts`,
  `AdbConnectPort.connect`, `LocalPortScan.scan`, `LocalPortScanner.openPorts`) block the calling thread instead;
  coroutine cancellation becomes thread interruption. Kotlin default arguments became overloads or
  `AdbConnectPort.Builder`; Kotlin `IntRange` became `PortRange`; checked exceptions are declared where Kotlin let them
  through unchecked.
- Protocol constants, byte layouts, SPAKE2/Ed25519 math, TLS setup, packet formats and the pairing/connect flows are
  unchanged. Comments were translated from Chinese to English, keeping their meaning.
- Added `AdbConnection.shell(command)`, a one-line wrapper that opens the `shell:` service (Mika only has `exec:`).
- `AndroidAdb.java`: key name `sprocket@<model>` instead of `mika@<model>`, Keystore alias and log tag renamed, and an
  Android 12 (API 31) check before `SSLSockets.exportKeyingMaterial`, which doesn't exist on older Android.
- Tests: plain `main()` checks on the desktop JDK instead of JUnit, JDK TLS instead of Conscrypt (so the Conscrypt
  exporter test is reduced to a TLS 1.3 check), and a fake adbd that also serves `shell:echo`.
