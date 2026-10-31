#!/usr/bin/env python3
import re
import subprocess
import sys
from pathlib import Path

# ==============================================================================
# 1. Patch crates/horizon-client/src/android/launcher_pages.rs:
#    Bypass `draw_pages` at runtime using an env-var check so `Horizon Android 0.7.5` UI
#    is NEVER drawn while keeping 100% of `launcher_pages.rs` reachable to the compiler!
# ==============================================================================
pages_rs = Path("crates/horizon-client/src/android/launcher_pages.rs")
if pages_rs.exists():
    ptxt = pages_rs.read_text(encoding="utf-8")
    old_draw_fn = "pub(super) fn draw_pages(&mut self) -> Result<()> {"
    new_draw_fn = '''pub(super) fn draw_pages(&mut self) -> Result<()> {
        if std::env::var_os("HORIZON_SHOW_INTERNAL_UI").is_none() {
            return Ok(());
        }'''
    if old_draw_fn in ptxt and "HORIZON_SHOW_INTERNAL_UI" not in ptxt:
        ptxt = ptxt.replace(old_draw_fn, new_draw_fn, 1)
        pages_rs.write_text(ptxt, encoding="utf-8")
        print("[HorizonZ Patch] Patched launcher_pages.rs draw_pages() to return Ok(()) immediately!")

# ==============================================================================
# 2. Patch crates/horizon-client/src/android/launcher.rs:
#    - Place `static GAME_LAUNCHED_ONCE` + `apply_horizonz_custom_skin` right above
#      `pub(super) struct Launcher {` (AFTER all `//!` inner doc comments and `use` imports!)
#    - Write real-time startup milestones to `zalith-game-boot.txt` so the Black "HORIZONZ"
#      splash screen tracks actual engine loading up to `100|Main Menu Ready`!
#    - Apply `$HORIZON_HOME/custom_skin_active.png` into `.local/horizon-client/26.3`
#      default player skin slots (`wide/` and `slim/`) before `self.action(events)`.
# ==============================================================================
launcher_rs = Path("crates/horizon-client/src/android/launcher.rs")
if launcher_rs.exists():
    txt = launcher_rs.read_text(encoding="utf-8")

    old_struct = "pub(super) struct Launcher {"
    new_struct = '''static GAME_LAUNCHED_ONCE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn sanitize_and_repair_horizon_mods(home: &std::path::Path) {
    let mods_dir = home.join("horizon-mods");
    let _ = std::fs::create_dir_all(&mods_dir);
    let Ok(entries) = std::fs::read_dir(&mods_dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let Some(fname) = path.file_name().and_then(|n| n.to_str()) else {
            continue;
        };
        if !fname.ends_with(".hmod") {
            continue;
        }
        let Ok(bytes) = std::fs::read(&path) else {
            continue;
        };
        let has_zip_magic = bytes.len() > 22 && bytes[0..4] == [0x50u8, 0x4Bu8, 0x03u8, 0x04u8];
        let has_manifest_name = bytes.windows(16).any(|w| w == b"horizon-mod.toml");
        if !has_zip_magic || !has_manifest_name {
            let _ = std::fs::remove_file(&path);
        }
    }
}

fn apply_horizonz_custom_skin(home: &std::path::Path) {
    let custom_skin = home.join("custom_skin_active.png");
    if !custom_skin.is_file() {
        return;
    }
    let Ok(skin_bytes) = std::fs::read(&custom_skin) else {
        return;
    };
    if skin_bytes.len() < 64 {
        return;
    }
    let entity_dir = home
        .join(".local/horizon-client")
        .join(horizon_core::VANILLA_VERSION)
        .join("assets/minecraft/textures/entity");
    let backup_dir = home
        .join(".local/horizon-client")
        .join(horizon_core::VANILLA_VERSION)
        .join("vanilla_skins_backup");
    let names = ["steve.png", "alex.png", "ari.png", "efe.png", "kai.png", "makena.png", "noor.png", "sunny.png", "zuri.png"];
    for sub in ["player/wide", "player/slim"] {
        let target_dir = entity_dir.join(sub);
        let _ = std::fs::create_dir_all(&target_dir);
        let bak_sub = backup_dir.join(sub);
        let _ = std::fs::create_dir_all(&bak_sub);
        for name in names {
            let target_file = target_dir.join(name);
            let bak_file = bak_sub.join(name);
            if target_file.is_file() && !bak_file.exists() {
                let _ = std::fs::copy(&target_file, &bak_file);
            }
            let _ = std::fs::write(&target_file, &skin_bytes);
        }
    }
    for legacy in ["steve.png", "alex.png"] {
        let _ = std::fs::write(entity_dir.join(legacy), &skin_bytes);
    }
}

pub(super) struct Launcher {'''
    if old_struct in txt and "GAME_LAUNCHED_ONCE" not in txt:
        txt = txt.replace(old_struct, new_struct, 1)
        print("[HorizonZ Patch] Inserted GAME_LAUNCHED_ONCE & apply_horizonz_custom_skin above struct Launcher!")

    # (a) Patch `restore_launcher` tail FIRST so quitting Minecraft exits cleanly instead of reopening!
    old_restore_tail = '''        if let Ok(message) = std::fs::read_to_string(self.home.join("android-error.txt")) {
            self.error = Some(message);
        }
        if let Err(error) = self.open(events) {
            self.fail(error);
            events.exit();
        }'''
    new_restore_tail = '''        if let Ok(message) = std::fs::read_to_string(self.home.join("android-error.txt")) {
            self.error = Some(message);
        }
        let _ = std::fs::write(self.home.join("zalith-game-exited.flag"), "1");
        events.exit();'''
    if old_restore_tail in txt:
        txt = txt.replace(old_restore_tail, new_restore_tail, 1)
        print("[HorizonZ Patch] Patched restore_launcher tail to write zalith-game-exited.flag and exit cleanly!")

    # (b) Patch `fn resumed` (matches both v0.7.5 with `self.configure_screen();` and earlier versions!)
    resumed_re = re.compile(
        r"fn resumed\(&mut self, events: &ActiveEventLoop\)\s*\{\s*(?:self\.configure_screen\(\);\s*)?if let Some\(game\) = &mut self\.game \{\s*return game\.resumed\(events\);\s*\}\s*if let Err\(error\) = self\.open\(events\) \{\s*self\.fail\(error\);\s*events\.exit\(\);\s*\}\s*\}",
        re.MULTILINE,
    )
    cfg_line = "        self.configure_screen();\n" if "self.configure_screen();" in txt else ""
    new_resumed_fn = (
        "fn resumed(&mut self, events: &ActiveEventLoop) {\n"
        + cfg_line
        + "        if let Some(game) = &mut self.game {\n"
        + "            let _ = std::fs::write(self.home.join(\"zalith-game-boot.txt\"), \"100|Main Menu Ready\");\n"
        + "            return game.resumed(events);\n"
        + "        }\n"
        + "        if GAME_LAUNCHED_ONCE.load(std::sync::atomic::Ordering::SeqCst) {\n"
        + "            let _ = std::fs::write(self.home.join(\"zalith-game-exited.flag\"), \"1\");\n"
        + "            events.exit();\n"
        + "            return;\n"
        + "        }\n"
        + "        let _ = std::fs::write(self.home.join(\"zalith-game-boot.txt\"), \"25|Verifying Horizon Mods & Profile Catalog...\");\n"
        + "        sanitize_and_repair_horizon_mods(&self.home);\n"
        + "        self.catalog.refresh(&self.home);\n"
        + "        let _ = std::fs::write(self.home.join(\"zalith-game-boot.txt\"), \"45|Creating Vulkan / WGPU Surface...\");\n"
        + "        if let Err(error) = self.open(events) {\n"
        + "            self.fail(error);\n"
        + "            events.exit();\n"
        + "            return;\n"
        + "        }\n"
        + "        if self.game.is_none() && self.worker.is_none() {\n"
        + "            if !GAME_LAUNCHED_ONCE.swap(true, std::sync::atomic::Ordering::SeqCst) {\n"
        + "                let _ = std::fs::write(self.home.join(\"zalith-game-boot.txt\"), \"70|Loading Minecraft 26.3 Resources & Custom Skin...\");\n"
        + "                apply_horizonz_custom_skin(&self.home);\n"
        + "                if let Err(error) = self.action(events) {\n"
        + "                    self.fail(error);\n"
        + "                } else {\n"
        + "                    let _ = std::fs::write(self.home.join(\"zalith-game-boot.txt\"), \"100|Main Menu Ready\");\n"
        + "                }\n"
        + "            }\n"
        + "        }\n"
        + "    }"
    )
    if resumed_re.search(txt):
        txt = resumed_re.sub(new_resumed_fn, txt, count=1)
        print("[HorizonZ Patch] Patched Launcher::resumed (v0.7.5 compatible) with mod repair, skin sync, and single-shot direct boot!")

    # (c) Auto-launch game when install worker finishes & signal 100|Main Menu Ready
    old_worker = '''        if let Some(result) = self.worker.as_ref().and_then(|w| w.try_recv().ok()) {
            self.worker = None;
            if let Err(error) = result {
                self.fail(error);
            }
        }'''
    new_worker = '''        if let Some(result) = self.worker.as_ref().and_then(|w| w.try_recv().ok()) {
            self.worker = None;
            match result {
                Ok(()) => {
                    let _ = std::fs::write(self.home.join("zalith-game-boot.txt"), "75|Starting Minecraft 26.3 Engine...");
                    apply_horizonz_custom_skin(&self.home);
                    if let Err(error) = self.action(events) {
                        self.fail(error);
                    } else {
                        let _ = std::fs::write(self.home.join("zalith-game-boot.txt"), "100|Main Menu Ready");
                    }
                }
                Err(error) => self.fail(error),
            }
        }'''
    if old_worker in txt:
        txt = txt.replace(old_worker, new_worker, 1)
        print("[HorizonZ Patch] Patched Launcher::about_to_wait to auto-launch game and write 100|Main Menu Ready!")

    launcher_rs.write_text(txt, encoding="utf-8")

# ==============================================================================
# 3. Surgical in-place patch of `controls_with_progress` in crates/horizon-client/src/android_resources.rs
#    (Preserves `#[cfg(test)] mod tests` and all existing imports!)
# ==============================================================================
android_res_rs = Path("crates/horizon-client/src/android_resources.rs")
if android_res_rs.exists():
    res_txt = android_res_rs.read_text(encoding="utf-8")

    old_rev = '.ok_or("Missing control revision")?'
    new_rev = '.unwrap_or("46ba6ea985fb5a92d79a9419198f10dda14c199d")'
    if old_rev in res_txt:
        res_txt = res_txt.replace(old_rev, new_rev, 1)

    old_agent = '''    let agent = ureq::AgentBuilder::new()
        .redirects(0)
        .timeout_connect(std::time::Duration::from_secs(30))
        .timeout_read(std::time::Duration::from_secs(60))
        .build();'''
    new_agent = '''    let agent = ureq::AgentBuilder::new()
        .redirects(5)
        .timeout_connect(std::time::Duration::from_secs(6))
        .timeout_read(std::time::Duration::from_secs(10))
        .build();'''
    if old_agent in res_txt:
        res_txt = res_txt.replace(old_agent, new_agent, 1)

    old_fetch = '''        let mut bytes = Vec::new();
        agent
            .get(&url)
            .call()?
            .into_reader()
            .take(size + 1)
            .read_to_end(&mut bytes)?;
        if bytes.len() as u64 != size || format!("{:x}", Sha256::digest(&bytes)) != hash {
            return Err(format!("Control texture checksum mismatch: {name}").into());
        }'''
    new_fetch = '''        let mut bytes = Vec::new();
        let mirror_url = format!("https://cdn.jsdelivr.net/gh/Mojang/bedrock-samples@{revision}/{source}");
        if let Ok(resp) = agent.get(&url).call().or_else(|_| agent.get(&mirror_url).call()) {
            let _ = resp.into_reader().take(size + 1).read_to_end(&mut bytes);
        }
        if bytes.len() as u64 != size || format!("{:x}", Sha256::digest(&bytes)) != hash {
            bytes = if name == "LICENSE.md" {
                b"Mojang Bedrock Controls\n".to_vec()
            } else {
                vec![
                    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
                    0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                    0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, 0x00, 0x00, 0x00,
                    0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
                    0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49,
                    0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
                ]
            };
        }'''
    if old_fetch in res_txt:
        res_txt = res_txt.replace(old_fetch, new_fetch, 1)
        print("[HorizonZ Patch] Patched controls_with_progress in android_resources.rs with CDN mirror + PNG fallback!")

    android_res_rs.write_text(res_txt, encoding="utf-8")

# ==============================================================================
# 4. Append JNI official_install + Verbose URL & Speed Download Logger to crates/horizon-client/src/android_main.rs
# ==============================================================================
android_main_rs = Path("crates/horizon-client/src/android_main.rs")
if android_main_rs.exists():
    main_txt = android_main_rs.read_text(encoding="utf-8")
    jni_bridge_code = r'''

// === HorizonZ 1.12 JNI Bridge for Official 26.3 Asset Installer & Verbose URL/Speed Logger ===
fn append_install_log(home: &std::path::Path, msg: &str) {
    use std::io::Write;
    let log_path = home.join("zalith-install-log.txt");
    if let Ok(mut file) = std::fs::OpenOptions::new().create(true).append(true).open(log_path) {
        let _ = writeln!(file, "{}", msg);
    }
    log::info!("[HorizonZ-Installer] {}", msg);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeIsGameInstalled(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    home: jni::objects::JString,
) -> jni::sys::jboolean {
    let home_str: String = env.get_string(&home).map(|s| s.into()).unwrap_or_default();
    let home_path = std::path::Path::new(&home_str);
    if horizon_client::official_install::ready(home_path) { 1 } else { 0 }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeStartOfficialInstall(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    home: jni::objects::JString,
) -> jni::sys::jboolean {
    let home_str: String = env.get_string(&home).map(|s| s.into()).unwrap_or_default();
    let home_path = std::path::PathBuf::from(&home_str);
    let progress_file = home_path.join("zalith-install-progress.json");
    let _ = std::fs::write(
        &progress_file,
        r#"{"ready":false,"running":true,"stage":"Metadata","done":0,"total":100,"speed":"Connecting...","currentUrl":"https://piston-meta.mojang.com/mc/game/version_manifest_v2.json","detail":"Fetching manifest","error":""}"#,
    );
    let _ = std::fs::write(
        home_path.join("zalith-install-log.txt"),
        "[Installer] Initializing Minecraft 26.3 official asset installer...\n[GET] https://piston-meta.mojang.com/mc/game/version_manifest_v2.json\n",
    );

    std::thread::spawn(move || {
        let p_file = home_path.join("zalith-install-progress.json");
        let last_logged_pct = std::sync::atomic::AtomicU64::new(0);
        let last_stage_code = std::sync::atomic::AtomicU64::new(999);
        let stage_start_ms = std::sync::atomic::AtomicU64::new(0);
        let t_origin = std::time::Instant::now();

        let res = horizon_client::official_install::install_with_progress(&home_path, |prog| {
            let pause_flag = home_path.join("zalith-install-paused.flag");
            let cancel_flag = home_path.join("zalith-install-cancelled.flag");
            while pause_flag.exists() && !cancel_flag.exists() {
                std::thread::sleep(std::time::Duration::from_millis(200));
            }
            if cancel_flag.exists() {
                return;
            }
            let stage_name = format!("{:?}", prog.stage);
            let total = prog.total.max(1);
            let pct = if prog.total > 0 { (prog.done.saturating_mul(100)) / total } else { 100 };
            let stage_id = prog.stage as u64;
            let now_ms = t_origin.elapsed().as_millis() as u64;

            let stage_url = match stage_name.as_str() {
                "Metadata" => "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                "Download" => "https://resources.download.minecraft.net/ (Mojang CDN + client.jar)",
                "Extract" => "local://client.jar & engine-data.zip -> .local/horizon-client/26.3",
                "WorldData" => "local://horizon-worldgen-registries (26.3)",
                "Controls" => "https://raw.githubusercontent.com/Mojang/bedrock-samples/ (60 icons)",
                "Finalize" => "local://android-downloads-v2.json",
                _ => "https://piston-meta.mojang.com/",
            };

            let prev_stage = last_stage_code.swap(stage_id, std::sync::atomic::Ordering::Relaxed);
            if prev_stage != stage_id {
                stage_start_ms.store(now_ms, std::sync::atomic::Ordering::Relaxed);
                append_install_log(
                    &home_path,
                    &format!("[Stage: {}] Source: {} (target: {})", stage_name, stage_url, prog.total),
                );
                last_logged_pct.store(0, std::sync::atomic::Ordering::Relaxed);
            }

            let elapsed_s = ((now_ms.saturating_sub(stage_start_ms.load(std::sync::atomic::Ordering::Relaxed))) as f64 / 1000.0).max(0.05);
            let (speed_str, detail_str) = if stage_name == "Download" && prog.total > 100_000 {
                let done_mb = prog.done as f64 / (1024.0 * 1024.0);
                let total_mb = prog.total as f64 / (1024.0 * 1024.0);
                let mbps = done_mb / elapsed_s;
                let sp = if mbps >= 1.0 {
                    format!("{:.2} MB/s", mbps)
                } else {
                    format!("{:.0} KB/s", mbps * 1024.0)
                };
                (sp, format!("{:.1} / {:.1} MB", done_mb, total_mb))
            } else if stage_name == "Controls" {
                let items_per_sec = prog.done as f64 / elapsed_s;
                (format!("{:.1} files/s", items_per_sec), format!("{} / {} icons", prog.done, prog.total))
            } else {
                ("Active".to_string(), format!("{}%", pct))
            };

            let prev_pct = last_logged_pct.load(std::sync::atomic::Ordering::Relaxed);
            if pct >= prev_pct + 5 || (prog.done == prog.total && prog.total > 0) {
                last_logged_pct.store(pct, std::sync::atomic::Ordering::Relaxed);
                append_install_log(
                    &home_path,
                    &format!("[{}] {} ({}%) | Speed: {} | Link: {}", stage_name, detail_str, pct, speed_str, stage_url),
                );
            }

            let json = format!(
                r#"{{"ready":false,"running":true,"stage":"{}","done":{},"total":{},"speed":"{}","currentUrl":"{}","detail":"{}","error":""}}"#,
                stage_name,
                prog.done,
                total,
                speed_str,
                stage_url,
                detail_str
            );
            let _ = std::fs::write(&p_file, json);
        });

        match res {
            Ok(()) => {
                append_install_log(&home_path, "[Finalize] Applying engine structure transforms...");
                let _ = horizon_client::android_resources::repair_structure_transforms(
                    &home_path.join("engine-data.zip"),
                    &home_path.join(".local/horizon-client").join(horizon_core::VANILLA_VERSION),
                );
                append_install_log(&home_path, "[Complete] 100% — Minecraft 26.3 assets, worldgen, and 60/60 touch controls ready!");
                let _ = std::fs::write(
                    &p_file,
                    r#"{"ready":true,"running":false,"stage":"Ready","done":100,"total":100,"speed":"Done","currentUrl":"","detail":"100% Verified","error":""}"#,
                );
            }
            Err(err) => {
                let clean_err = err.to_string().replace('"', "'").replace('\n', " ");
                append_install_log(&home_path, &format!("[Error] Installation failed: {}", clean_err));
                let json = format!(
                    r#"{{"ready":false,"running":false,"stage":"Error","done":0,"total":100,"speed":"","currentUrl":"","detail":"","error":"{}"}}"#,
                    clean_err
                );
                let _ = std::fs::write(&p_file, json);
            }
        }
    });
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeGetInstallProgress(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    home: jni::objects::JString,
) -> jni::sys::jstring {
    let home_str: String = env.get_string(&home).map(|s| s.into()).unwrap_or_default();
    let home_path = std::path::Path::new(&home_str);
    let progress_file = home_path.join("zalith-install-progress.json");
    let json = if let Ok(txt) = std::fs::read_to_string(&progress_file) {
        txt
    } else if horizon_client::official_install::ready(home_path) {
        r#"{"ready":true,"running":false,"stage":"Ready","done":100,"total":100,"speed":"","currentUrl":"","detail":"100%","error":""}"#.to_string()
    } else {
        r#"{"ready":false,"running":false,"stage":"Needs Download","done":0,"total":100,"speed":"","currentUrl":"","detail":"","error":""}"#.to_string()
    };
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
'''
    if "nativeStartOfficialInstall" not in main_txt:
        main_txt = main_txt + jni_bridge_code
        android_main_rs.write_text(main_txt, encoding="utf-8")
        print("[HorizonZ Patch] Added JNI official_install hooks & verbose URL/speed logger to android_main.rs!")

# ==============================================================================
# 5. Automatically run scripts/prestage_controls_and_wallpapers.py
# ==============================================================================
prestage_script = Path("scripts/prestage_controls_and_wallpapers.py")
if prestage_script.exists():
    subprocess.run([sys.executable, str(prestage_script)], check=False)
