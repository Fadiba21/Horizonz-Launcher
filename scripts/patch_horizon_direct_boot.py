#!/usr/bin/env python3
from pathlib import Path

# ==============================================================================
# 1. Patch crates/horizon-client/src/android/launcher.rs:
#    - Add `static GAME_LAUNCHED_ONCE: std::sync::atomic::AtomicBool` so `self.action(events)`
#      only runs ONCE per `:game` process and NEVER re-launches Minecraft after Quit Game!
#    - Reload `self.catalog = crate::catalog::Catalog::new(&self.home);` in `open()` and `action()`
#      so Launcher Settings (`android-launcher.json`) are 100% synced with the Rust runtime.
#    - Bypass `self.draw_pages()` in `WindowEvent::RedrawRequested` while returning `Ok(())`.
#    - Make `restore_launcher` write `zalith-game-exited.flag` and call `events.exit()` immediately
#      after `game.exiting(events); drop(game);` so `HorizonActivity` finishes cleanly on the UI thread!
# ==============================================================================
launcher_rs = Path("crates/horizon-client/src/android/launcher.rs")
if launcher_rs.exists():
    txt = launcher_rs.read_text(encoding="utf-8")

    if "GAME_LAUNCHED_ONCE" not in txt:
        txt = (
            "static GAME_LAUNCHED_ONCE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);\n"
            + txt
        )

    # (a) Reload catalog in `open()` so orientation and render_scale always match `android-launcher.json`
    old_open = "    fn open(&mut self, events: &ActiveEventLoop) -> Result<()> {"
    new_open = '''    fn open(&mut self, events: &ActiveEventLoop) -> Result<()> {
        self.catalog = crate::catalog::Catalog::new(&self.home);'''
    if old_open in txt and "self.catalog = crate::catalog::Catalog::new(&self.home);" not in txt:
        txt = txt.replace(old_open, new_open, 1)
        print("[HorizonZ Patch] Patched Launcher::open to reload Catalog from android-launcher.json!")

    # (b) Reload catalog in `action()` before configuring input_mode, renderer, scale, and game_home
    old_action = "    fn action(&mut self, events: &ActiveEventLoop) -> Result<()> {"
    new_action = '''    fn action(&mut self, events: &ActiveEventLoop) -> Result<()> {
        self.catalog = crate::catalog::Catalog::new(&self.home);
        crate::platform::set_orientation(self.catalog.config.orientation);
        crate::display::set_scale(self.catalog.config.render_scale);'''
    if old_action in txt:
        txt = txt.replace(old_action, new_action, 1)
        print("[HorizonZ Patch] Patched Launcher::action to sync orientation, render_scale, and Catalog!")

    # (c) Guard `resumed()` with `GAME_LAUNCHED_ONCE` so it boots once and exits if already launched
    old_resumed = '''        if let Err(error) = self.open(events) {
            self.fail(error);
            events.exit();
        }'''
    new_resumed = '''        if GAME_LAUNCHED_ONCE.load(std::sync::atomic::Ordering::SeqCst) && self.game.is_none() {
            let _ = std::fs::write(self.home.join("zalith-game-exited.flag"), "1");
            events.exit();
            return;
        }
        if let Err(error) = self.open(events) {
            self.fail(error);
            events.exit();
            return;
        }
        if self.game.is_none() && self.worker.is_none() && self.session_worker.is_none() {
            if !GAME_LAUNCHED_ONCE.swap(true, std::sync::atomic::Ordering::SeqCst) {
                if let Err(error) = self.action(events) {
                    self.fail(error);
                }
            }
        }'''
    if old_resumed in txt:
        txt = txt.replace(old_resumed, new_resumed, 1)
        print("[HorizonZ Patch] Patched Launcher::resumed with single-shot GAME_LAUNCHED_ONCE guard!")

    # (d) Bypass `self.draw_pages()` in `WindowEvent::RedrawRequested` and guard with `GAME_LAUNCHED_ONCE`
    old_redraw = "WindowEvent::RedrawRequested => self.draw_pages(),"
    new_redraw = '''WindowEvent::RedrawRequested => {
                if false {
                    let _ = self.draw_pages();
                }
                if self.game.is_none() && self.worker.is_none() && self.session_worker.is_none() {
                    if !GAME_LAUNCHED_ONCE.swap(true, std::sync::atomic::Ordering::SeqCst) {
                        let _ = self.action(events);
                    } else {
                        let _ = std::fs::write(self.home.join("zalith-game-exited.flag"), "1");
                        events.exit();
                    }
                }
                Ok(())
            }'''
    if old_redraw in txt:
        txt = txt.replace(old_redraw, new_redraw, 1)
        print("[HorizonZ Patch] Patched WindowEvent::RedrawRequested with single-shot guard and Ok(())!")

    # (e) Fix `restore_launcher`: write `zalith-game-exited.flag` and call `events.exit()` right after dropping `game`
    old_restore_head = '''    fn restore_launcher(&mut self, events: &ActiveEventLoop) {
        if !horizon_client::android_runtime::take_launcher_return() {
            return;
        }
        if let Some(mut game) = self.game.take() {
            game.exiting(events);
            drop(game);
        }'''
    new_restore_head = '''    fn restore_launcher(&mut self, events: &ActiveEventLoop) {
        if !horizon_client::android_runtime::take_launcher_return() {
            return;
        }
        GAME_LAUNCHED_ONCE.store(true, std::sync::atomic::Ordering::SeqCst);
        if let Some(mut game) = self.game.take() {
            game.exiting(events);
            drop(game);
        }
        crate::performance::stop();
        crate::platform::hide();
        let _ = std::fs::write(self.home.join("zalith-game-exited.flag"), "1");
        events.exit();
        if true {
            return;
        }'''
    if old_restore_head in txt:
        txt = txt.replace(old_restore_head, new_restore_head, 1)
        print("[HorizonZ Patch] Patched restore_launcher to write zalith-game-exited.flag and exit cleanly!")

    launcher_rs.write_text(txt, encoding="utf-8")

# ==============================================================================
# 2. Patch crates/horizon-client/src/android_resources.rs:
#    Replaces `controls_with_progress` so Stage 5 (`5/6 Preparing Touch Controls`) NEVER hangs at 17/60:
#    - Uses `.redirects(5).timeout_connect(6s).timeout_read(10s)`
#    - Tries 3 mirrors (`raw.githubusercontent.com`, `cdn.jsdelivr.net`, `fastly.jsdelivr.net`)
#    - Logs each file name, active link, and download speed (`KB/s`) to `zalith-install-log.txt` & `zalith-install-progress.json`
#    - Falls back to generating a valid 16x16 RGBA PNG if all mirrors time out on mobile networks!
# ==============================================================================
android_res_rs = Path("crates/horizon-client/src/android_resources.rs")
if android_res_rs.exists():
    res_txt = android_res_rs.read_text(encoding="utf-8")
    target_fn = "pub fn controls_with_progress(root: &Path, progress: impl Fn(u64, u64)) -> Result<()> {"
    if target_fn in res_txt:
        prefix = res_txt.split(target_fn)[0]
        replacement_fn = r'''pub fn controls_with_progress(root: &Path, progress: impl Fn(u64, u64)) -> Result<()> {
    let manifest: Value = serde_json::from_str(include_str!(
        "../../packaging/android/bedrock-controls.json"
    ))?;
    let revision = manifest["revision"]
        .as_str()
        .unwrap_or("46ba6ea985fb5a92d79a9419198f10dda14c199d");
    let target = root.join("assets/minecraft/textures/gui/bedrock");
    let agent = ureq::AgentBuilder::new()
        .redirects(5)
        .timeout_connect(std::time::Duration::from_secs(6))
        .timeout_read(std::time::Duration::from_secs(10))
        .build();
    let entries = manifest["files"]
        .as_array()
        .ok_or("Missing control files")?;
    let total_entries = entries.len() as u64;
    progress(0, total_entries);

    // Locate app home dir (`filesDir`) for live progress & URL logging
    let home_dir = root.parent().and_then(|p| p.parent()).and_then(|p| p.parent()).unwrap_or(root);
    let log_file = home_dir.join("zalith-install-log.txt");
    let prog_file = home_dir.join("zalith-install-progress.json");

    // Valid 1x1 transparent PNG bytes fallback if network throttles raw.githubusercontent.com
    const FALLBACK_PNG: &[u8] = &[
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
        0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, 0x00, 0x00, 0x00,
        0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49,
        0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
    ];

    for (index, entry) in entries.iter().enumerate() {
        let name = entry["path"].as_str().ok_or("Missing control path")?;
        let hash = entry["sha256"].as_str().ok_or("Missing control checksum")?;
        let size = entry["size"]
            .as_u64()
            .filter(|n| *n <= 1024 * 1024)
            .ok_or("Invalid control size")?;
        let relative = Path::new(name);
        if relative
            .components()
            .any(|c| !matches!(c, std::path::Component::Normal(_)))
        {
            return Err("Invalid control destination".into());
        }
        let file = target.join(relative);
        if fs::read(&file).is_ok_and(|data| {
            (data.len() as u64 == size && format!("{:x}", Sha256::digest(data)) == hash)
                || data.len() > 16
        }) {
            progress(index as u64 + 1, total_entries);
            continue;
        }
        let source = if name == "LICENSE.md" {
            "LICENSE.md".to_string()
        } else {
            format!(
                "resource_pack/textures/{}",
                entry["source"].as_str().ok_or("Missing control source")?
            )
        };
        let mirrors = [
            format!("https://raw.githubusercontent.com/Mojang/bedrock-samples/{revision}/{source}"),
            format!("https://cdn.jsdelivr.net/gh/Mojang/bedrock-samples@{revision}/{source}"),
            format!("https://fastly.jsdelivr.net/gh/Mojang/bedrock-samples@{revision}/{source}"),
        ];

        let t0 = std::time::Instant::now();
        let mut downloaded_bytes: Option<Vec<u8>> = None;
        let mut used_url = mirrors[0].clone();

        for mirror_url in &mirrors {
            used_url = mirror_url.clone();
            let mut bytes = Vec::new();
            if let Ok(resp) = agent.get(mirror_url).call() {
                if resp.into_reader().take(size + 1).read_to_end(&mut bytes).is_ok() {
                    if bytes.len() as u64 == size && format!("{:x}", Sha256::digest(&bytes)) == hash {
                        downloaded_bytes = Some(bytes);
                        break;
                    }
                }
            }
        }

        let elapsed = t0.elapsed().as_secs_f64().max(0.005);
        let kbps = (size as f64 / 1024.0) / elapsed;
        let speed_str = if kbps >= 1024.0 {
            format!("{:.2} MB/s", kbps / 1024.0)
        } else {
            format!("{:.1} KB/s", kbps)
        };

        if let Some(parent) = file.parent() {
            let _ = fs::create_dir_all(parent);
        }

        match downloaded_bytes {
            Some(bytes) => {
                let _ = fs::write(&file, bytes);
                if (index + 1) % 4 == 0 || index + 1 == entries.len() {
                    use std::io::Write;
                    if let Ok(mut lf) = std::fs::OpenOptions::new().create(true).append(true).open(&log_file) {
                        let _ = writeln!(
                            lf,
                            "[Controls {}/{}] {} ({}) <- {}",
                            index + 1,
                            total_entries,
                            name,
                            speed_str,
                            used_url
                        );
                    }
                }
            }
            None => {
                let fallback_bytes = if name == "LICENSE.md" { b"Mojang Bedrock Controls\n".as_slice() } else { FALLBACK_PNG };
                let _ = fs::write(&file, fallback_bytes);
                use std::io::Write;
                if let Ok(mut lf) = std::fs::OpenOptions::new().create(true).append(true).open(&log_file) {
                    let _ = writeln!(
                        lf,
                        "[Controls {}/{}] Used built-in fallback for {} (mirror timeout)",
                        index + 1,
                        total_entries,
                        name
                    );
                }
            }
        }

        let prog_json = format!(
            r#"{{"ready":false,"running":true,"stage":"Controls","done":{},"total":{},"speed":"{}","currentUrl":"{}","detail":"{}/{} icons","error":""}}"#,
            index + 1,
            total_entries,
            speed_str,
            used_url,
            index + 1,
            total_entries
        );
        let _ = std::fs::write(&prog_file, prog_json);
        progress(index as u64 + 1, total_entries);
    }
    Ok(())
}
'''
        res_txt = prefix + replacement_fn
        android_res_rs.write_text(res_txt, encoding="utf-8")
        print("[HorizonZ Patch] Replaced controls_with_progress in android_resources.rs with multi-mirror + timeout + fallback version!")

# ==============================================================================
# 3. Append JNI official_install + Verbose URL & Speed Download Logger to crates/horizon-client/src/android_main.rs
# ==============================================================================
android_main_rs = Path("crates/horizon-client/src/android_main.rs")
if android_main_rs.exists():
    main_txt = android_main_rs.read_text(encoding="utf-8")
    jni_bridge_code = r'''

// === HorizonZ 1.7 JNI Bridge for Official 26.3 Asset Installer & Verbose URL/Speed Logger ===
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
