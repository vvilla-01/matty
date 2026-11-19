Matty signing kit

Scripts (keep beside each other): common.sh, make-keys.sh, make-lineage.sh, sign-release.sh,
OneSignerLineage.java (only for the clean design). Needs: JDK keytool with ML-DSA (Arch OpenJDK
21.0.13 has it), openssl, Android build-tools 37.0.0 (BUILD_TOOLS=... to use another).

Once:  make-keys.sh <dir>             then  make-lineage.sh <dir> rotate   (or clean)
Every release: ./gradlew :app:assembleRelease, then
       sign-release.sh <dir> app/build/outputs/apk/release/app-release.apk matty-<version>.apk
It prints the text to publish for AppVerifier (package name, RSA hash, ML-DSA hash).

What is in <dir> (mode 700; every file 600):
  matty-rsa.p12          RSA 4096 key + certificate (alias matty)  - the classical signer
  matty-mldsa87.p12      ML-DSA-87 key + certificate (alias matty) - the post-quantum signer
  pass/matty-rsa.pass    password of matty-rsa.p12 (one line)
  pass/matty-mldsa87.pass password of matty-mldsa87.p12 (one line)
  matty-rsa.lineage      signing history ending in the RSA key (rotate: debug -> RSA)
  matty-mldsa87.lineage  signing history ending in the ML-DSA key (rotate: debug -> ML-DSA)
  design                 "rotate" or "clean"

Back up ALL of it, every file: losing either key, either password or either lineage file means no
update can ever install over Matty again (phones would have to uninstall and lose their data).
The debug keystore is needed only to remake the rotate lineages; the lineage files replace it.
Keep two copies offline (for example two encrypted USB drives in different places), never in the
repository, a cloud folder or e-mail. Write the passwords on paper too if the copies are encrypted
with something else.

Check a backup (on another machine or folder, from the copy only):
  sign-release.sh <restored-dir> <any release apk> /tmp/check.apk
It must succeed and print the same three lines as the published verification info. Then delete
/tmp/check.apk. Do this after making the backup and once a year.
