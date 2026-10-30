#!/usr/bin/env python3
import hashlib
import json
import os
import shutil
import subprocess
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent


def prestage_bedrock_controls():
    controls_json = ROOT_DIR / "packaging/android/bedrock-controls.json"
    if not controls_json.exists():
        print("[Prestage] bedrock-controls.json not found, skipping.")
        return

    manifest = json.loads(controls_json.read_text(encoding="utf-8"))
    revision = manifest.get("revision") or "46ba6ea985fb5a92d79a9419198f10dda14c199d"
    entries = manifest.get("files") or []
    print(f"[Prestage] Pre-fetching {len(entries)} Bedrock touch control textures (rev {revision[:10]})...")

    staging_dir = Path("/tmp/horizonz_bedrock_controls")
    if staging_dir.exists():
        shutil.rmtree(staging_dir, ignore_errors=True)
    staging_dir.mkdir(parents=True, exist_ok=True)

    def fetch_one(entry):
        name = entry["path"]
        expected_sha = entry["sha256"]
        source = "LICENSE.md" if name == "LICENSE.md" else f"resource_pack/textures/{entry['source']}"
        urls = [
            f"https://raw.githubusercontent.com/Mojang/bedrock-samples/{revision}/{source}",
            f"https://cdn.jsdelivr.net/gh/Mojang/bedrock-samples@{revision}/{source}",
        ]
        for url in urls:
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "HorizonZ-CI/1.7"})
                with urllib.request.urlopen(req, timeout=15) as resp:
                    data = resp.read()
                if hashlib.sha256(data).hexdigest() == expected_sha:
                    dest = staging_dir / "assets/minecraft/textures/gui/bedrock" / name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(data)
                    return True
            except Exception:
                continue
        return False

    ok_count = 0
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(fetch_one, e) for e in entries]
        for f in as_completed(futures):
            if f.result():
                ok_count += 1

    print(f"[Prestage] Downloaded & verified {ok_count}/{len(entries)} touch control files.")

    # Inject all verified touch control textures into .local/android/gradle-assets/engine-data.zip
    assets_dir = ROOT_DIR / ".local/android/gradle-assets"
    assets_dir.mkdir(parents=True, exist_ok=True)
    engine_zip = assets_dir / "engine-data.zip"
    mode = "a" if engine_zip.exists() else "w"
    with zipfile.ZipFile(engine_zip, mode, zipfile.ZIP_DEFLATED) as zf:
        existing = set(zf.namelist())
        for file_path in sorted(staging_dir.rglob("*")):
            if file_path.is_file():
                arcname = str(file_path.relative_to(staging_dir)).replace("\\", "/")
                if arcname not in existing:
                    zf.write(file_path, arcname)
    print(f"[Prestage] Injected {ok_count} Bedrock touch controls into {engine_zip} ({engine_zip.stat().st_size} bytes).")


def fetch_real_hd_minecraft_wallpapers():
    try:
        from PIL import Image
    except ImportError:
        subprocess.run(["pip", "install", "--quiet", "Pillow"], check=False)
        try:
            from PIL import Image
        except ImportError:
            print("[Prestage] Pillow not available, keeping bundled HD wallpapers.")
            return

    nodpi_dir = ROOT_DIR / "packaging/android/res/drawable-nodpi"
    nodpi_dir.mkdir(parents=True, exist_ok=True)

    candidates = []
    minegrub_dir = Path("/tmp/minegrub-wallpapers")
    usman_dir = Path("/tmp/usman-wallpapers")
    shutil.rmtree(minegrub_dir, ignore_errors=True)
    shutil.rmtree(usman_dir, ignore_errors=True)

    subprocess.run(
        ["git", "clone", "--depth", "1", "https://github.com/Lxtharia/minegrub-theme.git", str(minegrub_dir)],
        check=False,
    )
    subprocess.run(
        ["git", "clone", "--depth", "1", "https://github.com/usman-369/wallpapers.git", str(usman_dir)],
        check=False,
    )

    preferred_minegrub = [
        "26.3 - [Wilderness Bound].png",
        "26.2 - [Chaos Cubed].png",
        "26.1 - [Tiny Takeover].png",
        "1.21.11 - [Mounts of Mayhem].png",
        "1.21.9 - [The Copper Age].png",
        "1.21.6 - [Chase the Skies].png",
        "1.21.5 - [Spring to Life].png",
        "1.21.4 - [The Garden Awakens].png",
        "1.21 - [Tricky Trials].png",
        "1.20 - [Trails & Tales].png",
        "1.19 - [The Wild Update].png",
        "1.18 - [Caves And Cliffs 2].png",
        "1.16 - [Nether Update].png",
    ]

    usman_mc = usman_dir / "static/minecraft"
    if usman_mc.is_dir():
        for p in sorted(usman_mc.glob("*.png")):
            candidates.append(p)

    bg_opt = minegrub_dir / "background_options"
    if bg_opt.is_dir():
        for fname in preferred_minegrub:
            fpath = bg_opt / fname
            if fpath.is_file():
                candidates.append(fpath)

    if len(candidates) < 12 and bg_opt.is_dir():
        for p in sorted(bg_opt.glob("*.png")):
            if p not in candidates:
                candidates.append(p)

    print(f"[Prestage] Found {len(candidates)} real web HD Minecraft wallpapers.")
    target_w, target_h = 1280, 720
    target_ratio = target_w / target_h

    for idx in range(min(12, len(candidates))):
        src = candidates[idx]
        try:
            with Image.open(src) as im:
                im = im.convert("RGB")
                w, h = im.size
                ratio = w / h
                if ratio > target_ratio:
                    new_w = int(h * target_ratio)
                    left = (w - new_w) // 2
                    im = im.crop((left, 0, left + new_w, h))
                elif ratio < target_ratio:
                    new_h = int(w / target_ratio)
                    top = (h - new_h) // 2
                    im = im.crop((0, top, w, top + new_h))
                im = im.resize((target_w, target_h), Image.Resampling.LANCZOS)
                out_file = nodpi_dir / f"mc_wall_{idx + 1:02d}.jpg"
                im.save(out_file, format="JPEG", quality=92, optimize=True)
                print(f"[Prestage] Saved real HD 16:9 wallpaper {out_file.name} from {src.name}")
        except Exception as e:
            print(f"[Prestage] Skipping {src}: {e}")

    shutil.rmtree(minegrub_dir, ignore_errors=True)
    shutil.rmtree(usman_dir, ignore_errors=True)


if __name__ == "__main__":
    prestage_bedrock_controls()
    fetch_real_hd_minecraft_wallpapers()
