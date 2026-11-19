#!/usr/bin/env bash
# Signs a Matty release with APK Signature Scheme v3.0 (RSA) plus the v3.2 hybrid block (RSA +
# ML-DSA-87), verifies it with warnings as errors, and prints the verification info to publish.
# Usage: sign-release.sh <dir> <unsigned-or-debug-signed.apk> <out.apk>
# apksigner removes any earlier signature and re-aligns the APK, so Gradle's debug-signed release
# build is a fine input.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

[[ $# -eq 3 ]] || die "usage: sign-release.sh <dir> <in.apk> <out.apk>"
dir="$1"; in="$2"; out="$3"
key_paths "$dir"
for f in "$RSA_KS" "$PQC_KS" "$RSA_PASS" "$PQC_PASS" "$RSA_LINEAGE" "$PQC_LINEAGE" "$DESIGN_FILE" "$in"; do
    [[ -f "$f" ]] || die "missing $f"
done
if [[ -e "$out" ]]; then die "refusing: $out already exists"; fi
design="$(<"$DESIGN_FILE")"

lineage_args=()
# In the rotate design the v3.0 signer carries the debug->RSA history too, so a phone that does
# not read the v3.2 block still accepts the update over 0.1.22.
if [[ "$design" == rotate ]]; then lineage_args=(--lineage "$RSA_LINEAGE"); fi

# Passwords go through stdin, one line per use in signer order: v3.0 RSA, hybrid RSA, hybrid ML-DSA.
# v1/v2 are off (Android 17 only; the lineage's oldest signer is not given); v4 refuses ML-DSA.
{ cat -- "$RSA_PASS"; cat -- "$RSA_PASS"; cat -- "$PQC_PASS"; } | "$APKSIGNER" sign \
    --in "$in" --out "$out" \
    --v1-signing-enabled false --v2-signing-enabled false --v4-signing-enabled false \
    "${lineage_args[@]}" \
    --ks "$RSA_KS" --ks-key-alias matty --ks-pass stdin \
    --next-signer --ks "$RSA_KS" --ks-key-alias matty --ks-pass stdin \
        --signer-lineage "$RSA_LINEAGE" --hybrid-signer-role classical \
    --next-signer --ks "$PQC_KS" --ks-key-alias matty --ks-pass stdin \
        --signer-lineage "$PQC_LINEAGE" --hybrid-signer-role pqc

report="$("$APKSIGNER" verify -Werr --verbose --print-certs "$out")" || die "verification failed"
grep -q '^Verified using v3.2 scheme (APK Signature Scheme v3.2): true' <<<"$report" \
    || die "no v3.2 hybrid signature in $out"

# The info AppVerifier reads: the package name, then one hash per line. A phone that reads the
# hybrid block reports ML-DSA as its current signer, one that does not reports RSA; AppVerifier
# matches the phone's current signer against any line, so both are listed (and not the debug key,
# which would make anything signed with it match).
rsa="$(fingerprint "$RSA_KS" matty "$RSA_PASS")"
pqc="$(fingerprint "$PQC_KS" matty "$PQC_PASS")"
rsa_apk="$(sed -n 's/^V3.2 Hybrid Classical Signer: .*certificate SHA-256 digest: //p' <<<"$report")"
pqc_apk="$(sed -n 's/^V3.2 Hybrid PQC Signer: .*certificate SHA-256 digest: //p' <<<"$report")"
norm() { tr -d ':' <<<"$1" | tr 'a-f' 'A-F'; }
[[ "$(norm "$rsa")" == "$(norm "$rsa_apk")" && "$(norm "$pqc")" == "$(norm "$pqc_apk")" ]] \
    || die "the signed APK's certificates are not the keystores' certificates"
printf '%s\n%s\n%s\n' "$("$AAPT2" dump packagename "$out")" "$rsa" "$pqc"
