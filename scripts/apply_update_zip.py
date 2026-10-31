#!/usr/bin/env python3
"""Apply source ZIPs in Update/ while preserving the project's README layout.

If the current README contains HORIZONZ_README_PRESERVE, an incoming root README
is never copied wholesale. Only its marked HORIZONZ_UPDATE_SUMMARY block may be
synchronized. The version block is refreshed from packaging/android/build.gradle.
"""

import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
UPDATE_DIR = ROOT_DIR / "Update"
README_PRESERVE_MARKER = "<!-- HORIZONZ_README_PRESERVE -->"
VERSION_START = "<!-- HORIZONZ_VERSION_START -->"
VERSION_END = "<!-- HORIZONZ_VERSION_END -->"
SUMMARY_START = "<!-- HORIZONZ_UPDATE_SUMMARY_START -->"
SUMMARY_END = "<!-- HORIZONZ_UPDATE_SUMMARY_END -->"


def extract_marked_block(text: str, start_marker: str, end_marker: str) -> str | None:
    start = text.find(start_marker)
    if start < 0:
        return None
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        return None
    return text[start : end + len(end_marker)]


def replace_marked_block(
    text: str, replacement: str, start_marker: str, end_marker: str
) -> tuple[str, bool]:
    start = text.find(start_marker)
    if start < 0:
        return text, False
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        return text, False
    end += len(end_marker)
    return text[:start] + replacement + text[end:], True


def read_build_metadata(root_dir: Path) -> dict[str, str]:
    gradle_file = root_dir / "packaging/android/build.gradle"
    if not gradle_file.is_file():
        return {}
    text = gradle_file.read_text(encoding="utf-8", errors="replace")
    patterns = {
        "version_name": r"\bversionName\s+['\"]([^'\"]+)['\"]",
        "version_code": r"\bversionCode\s+(\d+)",
        "min_sdk": r"customMinSdk\s*=\s*Integer\.parseInt\(\(project\.findProperty\('minSdk'\)\s*\?:\s*'(\d+)'",
        "compile_sdk": r"\bcompileSdk\s+(\d+)",
        "target_sdk": r"\btargetSdk\s+(\d+)",
    }
    result = {}
    for key, pattern in patterns.items():
        match = re.search(pattern, text)
        if match:
            result[key] = match.group(1)
    return result


def version_block(metadata: dict[str, str]) -> str | None:
    version = metadata.get("version_name")
    if not version:
        return None
    code = metadata.get("version_code", "—")
    min_sdk = metadata.get("min_sdk", "—")
    compile_sdk = metadata.get("compile_sdk", "—")
    target_sdk = metadata.get("target_sdk", "—")
    badge_version = version.split("-", 1)[0]
    return (
        f"{VERSION_START}\n"
        f"<p align=\"center\">\n"
        f"  <a href=\"https://github.com/Fadiba21/Horizonz-Launcher/releases/latest\"><img src=\"https://img.shields.io/badge/Release-v{badge_version}-52b788?style=for-the-badge&logo=android&logoColor=white\" alt=\"Release v{badge_version}\"></a>\n"
        f"  <strong>Source saat ini:</strong> "
        f"<code>{version}</code> · <strong>versionCode:</strong> "
        f"<code>{code}</code> · <strong>minimum Android:</strong> "
        f"API {min_sdk} · <strong>compile/target SDK:</strong> "
        f"{compile_sdk}/{target_sdk}\n</p>\n"
        f"{VERSION_END}"
    )


def update_preserved_readme(root_dir: Path, incoming_readme: str | None) -> None:
    readme_path = root_dir / "README.md"
    if not readme_path.is_file():
        if incoming_readme is not None:
            readme_path.write_text(incoming_readme, encoding="utf-8")
            print("[Auto-Update] README.md added because no README existed.")
        return

    current = readme_path.read_text(encoding="utf-8", errors="replace")
    if README_PRESERVE_MARKER not in current:
        if incoming_readme is not None:
            readme_path.write_text(incoming_readme, encoding="utf-8")
            print("[Auto-Update] Legacy README replaced; preservation marker was absent.")
        return

    updated = current
    metadata = read_build_metadata(root_dir)
    block = version_block(metadata)
    if block is not None:
        updated, changed = replace_marked_block(updated, block, VERSION_START, VERSION_END)
        if changed:
            print(f"[Auto-Update] README version refreshed from Gradle: {metadata['version_name']}.")
        else:
            print("[Auto-Update] README preservation is active, but its version markers are missing or incomplete.")
    else:
        print("[Auto-Update] Could not read version metadata; README version block was left unchanged.")

    if incoming_readme is not None:
        summary = extract_marked_block(incoming_readme, SUMMARY_START, SUMMARY_END)
        if summary is not None:
            updated, changed = replace_marked_block(updated, summary, SUMMARY_START, SUMMARY_END)
            if changed:
                print("[Auto-Update] README update summary synchronized from the ZIP's marked block.")
            else:
                print("[Auto-Update] Incoming summary found, but current README summary markers are missing.")
        else:
            print("[Auto-Update] Incoming README has no marked update summary; existing summary was preserved.")
    else:
        print("[Auto-Update] ZIP has no root README.md; existing summary was preserved.")

    if updated != current:
        readme_path.write_text(updated, encoding="utf-8")


def extract_zip_to_root(zip_path: Path, root_dir: Path) -> int:
    print(f"[Auto-Update] Extracting {zip_path.name} into repository root...")
    extracted_count = 0
    with tempfile.TemporaryDirectory() as tmp_dir_str:
        tmp_dir = Path(tmp_dir_str)
        with zipfile.ZipFile(zip_path, "r") as zf:
            zf.extractall(tmp_dir)

        entries = [p for p in tmp_dir.iterdir() if p.name != "__MACOSX"]
        source_base = tmp_dir
        if len(entries) == 1 and entries[0].is_dir():
            if entries[0].name not in ("crates", "packaging", "scripts", "tools", ".github", "Update"):
                source_base = entries[0]
                print(f"[Auto-Update] Detected wrapper folder '{source_base.name}/', unwrapping to root...")

        current_readme_path = root_dir / "README.md"
        preserve_readme = (
            current_readme_path.is_file()
            and README_PRESERVE_MARKER in current_readme_path.read_text(
                encoding="utf-8", errors="replace"
            )
        )
        incoming_readme = None

        for item in sorted(source_base.rglob("*")):
            if not item.is_file():
                continue
            rel_path = item.relative_to(source_base)
            if rel_path.parts[0] == "Update" and rel_path.suffix.lower() == ".zip":
                continue
            if rel_path.as_posix() == "README.md" and preserve_readme:
                incoming_readme = item.read_text(encoding="utf-8", errors="replace")
                extracted_count += 1
                print("[Auto-Update] Kept current README layout; queued only marked version/changelog sync.")
                continue
            dest_path = root_dir / rel_path
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(item, dest_path)
            if dest_path.suffix in (".py", ".sh"):
                dest_path.chmod(dest_path.stat().st_mode | 0o755)
            extracted_count += 1

        if preserve_readme:
            update_preserved_readme(root_dir, incoming_readme)

    return extracted_count


def main() -> int:
    if not UPDATE_DIR.exists():
        UPDATE_DIR.mkdir(parents=True, exist_ok=True)
        (UPDATE_DIR / ".gitkeep").write_text("# Keep Update/ directory tracked in Git.\n", encoding="utf-8")
        print("[Auto-Update] No Update/ zip files found.")
        return 0

    zip_files = sorted(UPDATE_DIR.glob("*.zip"))
    if not zip_files:
        print("[Auto-Update] No .zip files in Update/ directory. Continuing with current main source.")
        return 0

    applied_names = []
    for zpath in zip_files:
        if not zipfile.is_zipfile(zpath):
            print(f"[Auto-Update] Skipping invalid ZIP file: {zpath.name}")
            continue
        count = extract_zip_to_root(zpath, ROOT_DIR)
        print(f"[Auto-Update] Applied {count} source files from {zpath.name}.")
        zpath.unlink()
        applied_names.append(zpath.name)

    UPDATE_DIR.mkdir(parents=True, exist_ok=True)
    gitkeep = UPDATE_DIR / ".gitkeep"
    if not gitkeep.exists():
        gitkeep.write_text("# Keep Update/ directory tracked in Git.\n", encoding="utf-8")

    if applied_names:
        summary_file = ROOT_DIR / ".auto_update_applied.txt"
        summary_file.write_text(", ".join(applied_names) + "\n", encoding="utf-8")
        print(f"[Auto-Update] Successfully applied and cleaned up: {', '.join(applied_names)}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
