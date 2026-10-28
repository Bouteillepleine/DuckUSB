#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

prop() { sed -n "s/^$1=//p" key.properties | tr -d '\r' | head -1; }

if [ -f key.properties ]; then
  export DUCKUSB_STORE_FILE=$(prop storeFile)
  export DUCKUSB_STORE_PASSWORD=$(prop storePassword)
  export DUCKUSB_KEY_ALIAS=$(prop keyAlias)
  export DUCKUSB_KEY_PASSWORD=$(prop keyPassword)
  export DUCKUSB_LEGACY_STORE_FILE=$(prop legacyStoreFile)
  export DUCKUSB_LEGACY_STORE_PASSWORD=$(prop legacyStorePassword)
  export DUCKUSB_LEGACY_KEY_ALIAS=$(prop legacyKeyAlias)
  export DUCKUSB_LEGACY_KEY_PASSWORD=$(prop legacyKeyPassword)
fi

for v in DUCKUSB_STORE_FILE DUCKUSB_STORE_PASSWORD DUCKUSB_KEY_ALIAS DUCKUSB_KEY_PASSWORD \
         DUCKUSB_LEGACY_STORE_FILE DUCKUSB_LEGACY_STORE_PASSWORD DUCKUSB_LEGACY_KEY_ALIAS DUCKUSB_LEGACY_KEY_PASSWORD; do
  [ -n "${!v:-}" ] || { echo "sign.sh: $v is not set" >&2; exit 1; }
done

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || { echo "sign.sh: ANDROID_HOME is not set" >&2; exit 1; }
APKSIGNER=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)apksigner
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) APKSIGNER="$APKSIGNER.bat";; esac

for APK in "$@"; do
  "$APKSIGNER" sign \
    --ks "$DUCKUSB_LEGACY_STORE_FILE" --ks-key-alias "$DUCKUSB_LEGACY_KEY_ALIAS" \
    --ks-pass env:DUCKUSB_LEGACY_STORE_PASSWORD --key-pass env:DUCKUSB_LEGACY_KEY_PASSWORD \
    --next-signer \
    --ks "$DUCKUSB_STORE_FILE" --ks-key-alias "$DUCKUSB_KEY_ALIAS" \
    --ks-pass env:DUCKUSB_STORE_PASSWORD --key-pass env:DUCKUSB_KEY_PASSWORD \
    --lineage duckusb.lineage --rotation-min-sdk-version 28 \
    --v4-signing-enabled false "$APK"
  echo "signed $APK"
  "$APKSIGNER" verify --print-certs -v "$APK" | grep -E 'Verified using v[23] |Signer: certificate SHA-256'
  "$APKSIGNER" lineage --in "$APK" --print-certs | grep -E 'in lineage certificate SHA-256'
done
