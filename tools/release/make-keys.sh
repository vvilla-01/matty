#!/usr/bin/env bash
# Creates Matty's two signing keys: RSA 4096 (the classical half of the v3.2 hybrid signature, read
# by every tool) and ML-DSA-87 (the post-quantum half, the app's current signer on Android 17).
# Usage: make-keys.sh <dir>     <dir> must not exist yet. Prints only the two SHA-256 fingerprints.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

[[ $# -eq 1 ]] || die "usage: make-keys.sh <dir>"
dir="$1"
if [[ -e "$dir" ]]; then die "refusing: $dir already exists"; fi
umask 077
mkdir -m 700 -p "$dir"
mkdir -m 700 "$dir/pass"
key_paths "$dir"

# The ML-DSA key needs a keytool with ML-DSA; Arch's OpenJDK 21.0.13 has it (JDK 24+ upstream).
keytool -genkeypair -help >/dev/null 2>&1 || die "keytool not found"

# Passwords never pass through a command line: generated straight into 600 files.
openssl rand -base64 33 | tr -d '\n=+/' | head -c 40 > "$RSA_PASS"; echo >> "$RSA_PASS"
openssl rand -base64 33 | tr -d '\n=+/' | head -c 40 > "$PQC_PASS"; echo >> "$PQC_PASS"
chmod 600 "$RSA_PASS" "$PQC_PASS"

# 36500 days = 100 years: Android ignores expiry, but some tools do not, and the key must outlive the app.
keytool -genkeypair -keystore "$RSA_KS" -storetype PKCS12 -storepass:file "$RSA_PASS" \
    -alias matty -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 36500 \
    -dname "CN=Matty, O=Villa" >/dev/null 2>&1 || die "RSA key generation failed"
# keytool cannot derive a signature algorithm for ML-DSA by itself, hence -sigalg.
keytool -genkeypair -keystore "$PQC_KS" -storetype PKCS12 -storepass:file "$PQC_PASS" \
    -alias matty -keyalg ML-DSA-87 -sigalg ML-DSA -validity 36500 \
    -dname "CN=Matty, O=Villa" >/dev/null 2>&1 || die "ML-DSA-87 key generation failed"
chmod 600 "$RSA_KS" "$PQC_KS"

printf 'RSA-4096  SHA-256 %s\n' "$(fingerprint "$RSA_KS" matty "$RSA_PASS")"
printf 'ML-DSA-87 SHA-256 %s\n' "$(fingerprint "$PQC_KS" matty "$PQC_PASS")"
