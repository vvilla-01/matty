[← Back to Matty](../README.md)

# Building

Requirements: JDK 21 (its keytool makes the ML-DSA key), Android SDK with platform 37 and build-tools 37.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified, unsigned: app/build/outputs/apk/release/app-release-unsigned.apk
```

Android 17 only: `minSdk`, `targetSdk` and `compileSdk` are all 37, like MarkOut. Toolchain: AGP 9.4.1, Gradle 9.6.1, Kotlin 2.2.10,
Compose Material 3 (Expressive) 1.5.0-alpha14.

## Release signing

Gradle leaves release builds unsigned; the scripts in `tools/release/` sign them (their
`README.txt` says what each file is):

```bash
tools/release/make-keys.sh <key folder>            # once: an RSA 4096 and an ML-DSA-87 key
tools/release/make-lineage.sh <key folder> clean   # once
tools/release/sign-release.sh <key folder> \
    app/build/outputs/apk/release/app-release-unsigned.apk matty-<version>.apk
```

`<key folder>` is a folder of your own outside the tree. The last command verifies what it signed
and prints the text for AppVerifier. The keys, their passwords and the two lineage files live
outside the repository and must be backed up: without
any one of them no update can install over Matty again. A fork signs with keys of its own, and
its builds do not install over the official ones.

The same two keys sign the releases of [Matty Plugin](https://github.com/vvilla-01/matty-plugin),
with that repository's `build/sign-release.sh` (its `docs/building.md` says how), and the app
checks those signatures against the two certificates it holds (`verify/MattyCertificates.kt`). A
fork that signs with keys of its own puts its own certificates there, and its people's servers
list the digest of the first certificate in its signing history (the one Android attests) under
the plugin's **More signing certificates**.

## A local test server

`tools/dev-server/` starts a throwaway Mattermost + PostgreSQL on port 8065 (no Docker or root
needed) and seeds it with users, teams, threads, reactions and DMs. It speaks plain http, so it
works with **debug builds only** — they make a single exception for `localhost`, `127.0.0.1` and
`10.0.2.2`; release builds refuse it. See [tools/dev-server/README.md](../tools/dev-server/README.md).

Push notifications are tried there with a stand-in UnifiedPush distributor, a development-only
app under `tools/dev-server/distributor`. It is part of the build only when asked for
(`./gradlew --offline -PdevDistributor=true :devdistributor:assembleDebug`), never by the tasks
above; [tools/dev-server/README.md](../tools/dev-server/README.md) has the details.

## Project layout

```
app/src/main/java/ceo/villa/matty/
  data/api/        REST client, WebSocket, models
  data/repo/       ChatRepository — the live state of one server
  data/session/    saved servers (Keystore-sealed tokens), device settings
  data/emoji/      Mattermost's emoji set (assets/emoji.tsv)
  data/giphy/      Giphy client for the GIF picker
  plugins/         MattyPlugin API, registry, and built-in modules (villanet/)
  push/            UnifiedPush: the connector, Web Push decryption, registering with the server
  service/         background connection + notifications
  ui/              Compose UI: home (rail, panels, lists), chat, picker, profile, settings…
```

## Verifying Matty

Matty's releases are signed with two keys together, in Android 17's hybrid signature (APK
Signature Scheme v3.2): a classical one (RSA 4096) and a post-quantum one (ML-DSA-87). The only
official downloads are the releases of this repository.

With [AppVerifier](https://github.com/soupslurpr/AppVerifier), share or paste this into it:

```
ceo.villa.matty
E5:52:50:BE:A6:69:00:39:B7:84:9E:33:B0:49:08:8E:CB:B9:98:D9:F2:0A:AA:8C:7E:B2:DC:5A:43:63:27:3C
75:78:CB:63:B4:3E:CC:6F:B1:27:64:51:54:F5:22:FB:15:D1:3A:26:08:E4:59:BC:F4:2A:7C:D1:41:86:A9:49
```

The first hash is the RSA certificate, the second the ML-DSA one. Android 17 reads the hybrid
signature and reports the ML-DSA certificate as the app's signer, with the RSA one before it in
its history; AppVerifier shows both and checks the last.

With the Android SDK (build-tools 37 or newer), `apksigner verify --verbose --print-certs
matty-<version>.apk` says "Verified using v3.2 scheme (APK Signature Scheme v3.2): true" and
prints the same two SHA-256 digests.

Versions up to 0.1.22 were pre-releases signed with a development key. Later versions do not
install over them: uninstall the old one first.

Matty also looks at itself: once at start, a release build reads Android's record of its signing
certificates, and Settings → Verification says whether it is the official build (the ML-DSA
certificate signing now, the RSA one before it). That is the app's own word, so it catches a
careless repackaging and nothing more; a debug build says it is a development build. For what the
app checks of a server's Matty Plugin, and how it proves itself to a server on Android's word,
see [Checking each other](PLUGINS.md#checking-each-other).
