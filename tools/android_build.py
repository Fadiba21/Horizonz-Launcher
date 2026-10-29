#!/usr/bin/env python3
"""
HorizonZ-Launcher Android Build Tool (compatible with Vasysik/horizon-android `tools/android_build.py`).
"""

import argparse
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

ABI_TO_TRIPLE = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android"),
    "armeabi-v7a": ("armv7-linux-androideabi", "armv7a-linux-androideabi"),
    "x86_64": ("x86_64-linux-android", "x86_64-linux-android"),
    "x86": ("i686-linux-android", "i686-linux-android"),
}

ROOT_DIR = Path(__file__).resolve().parent.parent
LOCAL_ANDROID_DIR = ROOT_DIR / ".local" / "android"
GRADLE_JNI_DIR = LOCAL_ANDROID_DIR / "gradle-jni"
GRADLE_ASSETS_DIR = LOCAL_ANDROID_DIR / "gradle-assets"
ANDROID_PKG_DIR = ROOT_DIR / "packaging" / "android"


def find_ndk(explicit_ndk: str | None) -> Path:
    if explicit_ndk:
        return Path(explicit_ndk)
    for env_key in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME"):
        val = os.environ.get(env_key)
        if val and Path(val).exists():
            return Path(val)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        ndk_root = Path(sdk) / "ndk"
        if ndk_root.exists():
            versions = sorted(ndk_root.iterdir())
            if versions:
                return versions[-1]
    raise RuntimeError("Android NDK not found. Pass --ndk or set ANDROID_NDK_HOME.")


def ensure_debug_keystore():
    ks_path = ANDROID_PKG_DIR / "debug.keystore"
    if not ks_path.exists():
        subprocess.run(
            [
                "keytool",
                "-genkeypair",
                "-v",
                "-keystore",
                str(ks_path),
                "-storepass",
                "android",
                "-alias",
                "androiddebugkey",
                "-keypass",
                "android",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "10000",
                "-dname",
                "CN=Android Debug,O=Android,C=US",
            ],
            check=True,
        )


def prepare_assets(public_mode: bool):
    GRADLE_ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    for doc_name in ("LICENSE.txt", "THIRD_PARTY_NOTICES.md"):
        src = ROOT_DIR / doc_name
        if src.exists():
            shutil.copy2(src, GRADLE_ASSETS_DIR / doc_name)

    controls_src = ANDROID_PKG_DIR / "bedrock-controls.json"
    if controls_src.exists():
        shutil.copy2(controls_src, GRADLE_ASSETS_DIR / "bedrock-controls.json")

    engine_zip = GRADLE_ASSETS_DIR / "engine-data.zip"
    if not engine_zip.exists():
        with zipfile.ZipFile(engine_zip, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.writestr(
                "horizon-engine-meta.json",
                '{"engine":"horizon-rust","minecraft_version":"26.3","android_port":"0.7.4","public_mode":'
                + ("true" if public_mode else "false")
                + "}\n",
            )


def build_rust_for_abi(abi: str, ndk_path: Path, min_sdk: int, profile: str, check_only: bool, public_mode: bool):
    rust_triple, clang_prefix = ABI_TO_TRIPLE[abi]
    host_tag = "linux-x86_64"
    toolchain_bin = ndk_path / "toolchains" / "llvm" / "prebuilt" / host_tag / "bin"
    cc = toolchain_bin / f"{clang_prefix}{min_sdk}-clang"
    cxx = toolchain_bin / f"{clang_prefix}{min_sdk}-clang++"
    ar = toolchain_bin / "llvm-ar"
    strip = toolchain_bin / "llvm-strip"

    env = os.environ.copy()
    if public_mode:
        env["HORIZON_ANDROID_PUBLIC"] = "1"

    env_triple = rust_triple.upper().replace("-", "_")
    env[f"CC_{rust_triple.replace('-', '_')}"] = str(cc)
    env[f"CXX_{rust_triple.replace('-', '_')}"] = str(cxx)
    env[f"AR_{rust_triple.replace('-', '_')}"] = str(ar)
    env[f"CARGO_TARGET_{env_triple}_LINKER"] = str(cc)

    cmd = ["cargo", "check" if check_only else "build", "-p", "horizon-android", "--target", rust_triple]
    if profile == "release":
        cmd.append("--release")

    print(f"[HorizonZ] Running: {' '.join(cmd)}")
    subprocess.run(cmd, cwd=ROOT_DIR, env=env, check=True)

    if not check_only:
        target_subdir = "release" if profile == "release" else "debug"
        built_so = ROOT_DIR / "target" / rust_triple / target_subdir / "libhorizon_android.so"
        dest_dir = GRADLE_JNI_DIR / abi
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest_so = dest_dir / "libhorizon_android.so"
        shutil.copy2(built_so, dest_so)
        if strip.exists():
            subprocess.run([str(strip), "--strip-unneeded", str(dest_so)], check=False)


def build_gradle_apk(abi: str, min_sdk: int, profile: str):
    ensure_debug_keystore()
    gradle_cmd = ["gradle", "assembleRelease" if profile == "release" else "assembleDebug"]
    if abi:
        gradle_cmd.append(f"-Pabi={abi}")
    gradle_cmd.append(f"-PminSdk={min_sdk}")
    print(f"[HorizonZ] Running Gradle in {ANDROID_PKG_DIR}: {' '.join(gradle_cmd)}")
    subprocess.run(gradle_cmd, cwd=ANDROID_PKG_DIR, check=True)


def main():
    parser = argparse.ArgumentParser(description="Build HorizonZ-Launcher Android APK")
    parser.add_argument("--abi", default="arm64-v8a", choices=list(ABI_TO_TRIPLE.keys()) + ["all"])
    parser.add_argument("--profile", default="release", choices=["dev", "release"])
    parser.add_argument("--ndk", default=None)
    parser.add_argument("--legacy", action="store_true", help="Use minSdk 27 instead of 28")
    parser.add_argument("--public", action="store_true", help="Set HORIZON_ANDROID_PUBLIC=1")
    parser.add_argument("--check", action="store_true", help="Run cargo check instead of full APK packaging")
    parser.add_argument("--skip-rust", action="store_true", help="Skip Rust compilation if libhorizon_android.so is already staged")
    parser.add_argument("--rust-only", action="store_true", help="Only compile Rust cdylib")
    args = parser.parse_args()

    min_sdk = 27 if args.legacy else 28
    abis = list(ABI_TO_TRIPLE.keys()) if args.abi == "all" else [args.abi]

    prepare_assets(args.public)

    if not args.skip_rust:
        ndk_path = find_ndk(args.ndk)
        for abi in abis:
            build_rust_for_abi(abi, ndk_path, min_sdk, args.profile, args.check, args.public)

    if not args.check and not args.rust_only:
        target_abi_prop = "" if args.abi == "all" else args.abi
        build_gradle_apk(target_abi_prop, min_sdk, args.profile)


if __name__ == "__main__":
    main()
