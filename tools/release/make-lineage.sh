#!/usr/bin/env bash
# Writes the two lineage (signing history) files the v3.2 hybrid signature needs.
# Usage: make-lineage.sh <dir> rotate|clean
#   rotate: history debug key -> RSA and debug key -> ML-DSA, so phones with 0.1.22 (signed with
#           ~/.android/debug.keystore) update in place. The debug key keeps the installed data and
#           the permission right, and loses shared uid, auth and rollback. The permission right
#           must stay: Matty declares the signature permission
#           ceo.villa.matty.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION (from AndroidX core), and
#           without it Android refuses the update with INSTALL_FAILED_DUPLICATE_PERMISSION (tried).
#   clean:  one-signer histories (RSA alone, ML-DSA alone): a new identity; 0.1.22 must be uninstalled.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

[[ $# -eq 2 ]] || die "usage: make-lineage.sh <dir> rotate|clean"
dir="$1"; design="$2"
key_paths "$dir"
[[ -f "$RSA_KS" && -f "$PQC_KS" ]] || die "no keys in $dir (run make-keys.sh first)"
for f in "$RSA_LINEAGE" "$PQC_LINEAGE" "$DESIGN_FILE"; do
    if [[ -e "$f" ]]; then die "refusing: $f already exists"; fi
done
umask 077

case "$design" in
rotate)
    debug_ks="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
    [[ -f "$debug_ks" ]] || die "no debug keystore at $debug_ks"
    tmp="$(mktemp -d "$dir/.tmp.XXXXXX")"
    trap 'rm -rf -- "$tmp"' EXIT
    # The debug keystore's password is the well-known "android"; still given by file, not argument.
    printf 'android\n' > "$tmp/debug.pass"
    "$APKSIGNER" rotate --out "$RSA_LINEAGE" \
        --old-signer --ks "$debug_ks" --ks-key-alias androiddebugkey --ks-pass "file:$tmp/debug.pass" \
            --set-installed-data true --set-permission true --set-shared-uid false \
            --set-auth false --set-rollback false \
        --new-signer --ks "$RSA_KS" --ks-key-alias matty --ks-pass "file:$RSA_PASS"
    # apksigner reads one line per use of a password file, so the second rotate gets a fresh copy.
    printf 'android\n' > "$tmp/debug2.pass"
    "$APKSIGNER" rotate --out "$PQC_LINEAGE" \
        --old-signer --ks "$debug_ks" --ks-key-alias androiddebugkey --ks-pass "file:$tmp/debug2.pass" \
            --set-installed-data true --set-permission true --set-shared-uid false \
            --set-auth false --set-rollback false \
        --new-signer --ks "$PQC_KS" --ks-key-alias matty --ks-pass "file:$PQC_PASS"
    ;;
clean)
    # apksigner 0.9 needs a lineage on both hybrid signers (it crashes on a null one), and its
    # command line can only make lineages of two signers; the helper makes a one-signer lineage
    # through apksigner's own Java API.
    java -cp "$APKSIGNER_JAR" "$SCRIPT_DIR/OneSignerLineage.java" "$RSA_KS" matty "$RSA_PASS" "$RSA_LINEAGE" 2>/dev/null
    java -cp "$APKSIGNER_JAR" "$SCRIPT_DIR/OneSignerLineage.java" "$PQC_KS" matty "$PQC_PASS" "$PQC_LINEAGE" 2>/dev/null
    ;;
*) die "design must be rotate or clean" ;;
esac
chmod 600 "$RSA_LINEAGE" "$PQC_LINEAGE"
printf '%s\n' "$design" > "$DESIGN_FILE"
"$APKSIGNER" lineage --in "$PQC_LINEAGE" --print-certs | grep -E 'DN|capability'
