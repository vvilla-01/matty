# Shared settings for the Matty signing scripts. Sourced, not run.
# The build-tools that first carried apksigner with --hybrid-signer-role; override with BUILD_TOOLS=...
BUILD_TOOLS="${BUILD_TOOLS:-$HOME/Android/Sdk/build-tools/37.0.0}"
APKSIGNER="$BUILD_TOOLS/apksigner"
APKSIGNER_JAR="$BUILD_TOOLS/lib/apksigner.jar"
AAPT2="$BUILD_TOOLS/aapt2"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() { printf '%s\n' "$*" >&2; exit 1; }

# Files in the key directory; every script uses these names.
key_paths() {
    RSA_KS="$1/matty-rsa.p12"
    PQC_KS="$1/matty-mldsa87.p12"
    RSA_PASS="$1/pass/matty-rsa.pass"
    PQC_PASS="$1/pass/matty-mldsa87.pass"
    RSA_LINEAGE="$1/matty-rsa.lineage"
    PQC_LINEAGE="$1/matty-mldsa87.lineage"
    DESIGN_FILE="$1/design"
}

# SHA-256 of a keystore's certificate as AppVerifier writes it: uppercase hex pairs joined by ":".
fingerprint() {  # <keystore> <alias> <password-file>
    keytool -list -v -keystore "$1" -storetype PKCS12 -alias "$2" -storepass:file "$3" \
        | sed -n 's/^[[:space:]]*SHA256: //p' | head -n 1
}
