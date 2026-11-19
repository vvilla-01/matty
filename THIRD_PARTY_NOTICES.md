# Third-party notices

Matty's own code, pictures and text are in the public domain under [The Unlicense](LICENSE).
The parts below are other people's work. They keep their own licences, in the repository and in the APK.

## In the repository

| What | From | Licence |
| --- | --- | --- |
| `app/src/main/assets/emoji.tsv` | Mattermost's emoji list (`webapp/channels/src/utils/emoji.json`, © Mattermost, Inc.), rearranged as a table. It is itself derived from [iamcal/emoji-data](https://github.com/iamcal/emoji-data) (MIT) and Unicode data. | Apache-2.0 |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` | The Gradle wrapper, © the Gradle authors, unchanged. | Apache-2.0 |

## In the APK

| Library | Licence |
| --- | --- |
| AndroidX (Core, Activity, Browser, Lifecycle, Compose, Material 3, Material icons, Media3), © The Android Open Source Project | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization, © JetBrains s.r.o. and contributors | Apache-2.0 |
| OkHttp and Okio, © Square, Inc. (OkHttp's public suffix list is under MPL-2.0) | Apache-2.0 |
| Coil, © Coil Contributors | Apache-2.0 |
| Roboto, © Google | Apache-2.0 |
| WebRTC (`io.github.webrtc-sdk:android`), © The WebRTC project authors | BSD-3-Clause, below |

WebRTC's library also contains, among others:

| Component | Copyright | Licence |
| --- | --- | --- |
| libvpx | The WebM Project authors | BSD-3-Clause |
| libaom | Alliance for Open Media | BSD-2-Clause, with the AOM patent licence |
| dav1d | VideoLAN and dav1d authors | BSD-2-Clause |
| Opus | Xiph.Org, Skype Limited, Octasic, Jean-Marc Valin, Timothy B. Terriberry, CSIRO, Gregory Maxwell, Mark Borgerding, Erik de Castro Lopo | BSD-3-Clause |
| libyuv | The LibYuv Project Authors | BSD-3-Clause |
| libsrtp | Cisco Systems, Inc. | BSD-3-Clause |
| BoringSSL | Google, the OpenSSL Project, Eric Young | Apache-2.0, OpenSSL and ISC |
| Abseil | The Abseil Authors | Apache-2.0 |

The complete list and every licence text is in WebRTC's own
[LICENSE](https://webrtc.googlesource.com/src/+/refs/heads/main/LICENSE) and
[third-party licences](https://webrtc.googlesource.com/src/+/refs/heads/main/LICENSE.third_party).
The Apache licence is at <https://www.apache.org/licenses/LICENSE-2.0>.

## WebRTC

Copyright (c) 2011, The WebRTC project authors. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of Google nor the names of its contributors may
    be used to endorse or promote products derived from this software
    without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

The components listed under WebRTC are distributed on the same conditions under their own
copyright lines, with the names of their own authors in place of Google's.

Mattermost is a trademark of Mattermost, Inc.
