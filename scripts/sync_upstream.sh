#!/usr/bin/env bash
set -euo pipefail

echo "=== HorizonZ-Launcher Upstream Sync Tool ==="
echo "1. Fetching Vasysik/horizon-android (branch: android, release v0.7.3+)..."
echo "2. Preserving HorizonZ-Launcher Material 3 UI (ZalithLauncher2Plus frontend) & JNI bridges..."

TMP_UPSTREAM="$(mktemp -d)"
git clone --depth 1 --branch android https://github.com/Vasysik/horizon-android.git "$TMP_UPSTREAM"

rsync -a --exclude='packaging/android' --exclude='.git' --exclude='.github' "$TMP_UPSTREAM/" ./

if [ -f "crates/horizon-client/src/android_main.rs" ]; then
    sed -i 's|path = "src/lib.rs"|path = "../horizon-client/src/android_main.rs"|g' crates/horizon-android/Cargo.toml || true
fi

rm -rf "$TMP_UPSTREAM"
echo "=== Upstream sync complete! Ready to run: python3 tools/android_build.py --public --abi arm64-v8a ==="
