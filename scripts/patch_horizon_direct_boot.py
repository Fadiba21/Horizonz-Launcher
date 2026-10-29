#!/usr/bin/env python3
from pathlib import Path

# 1. Patch crates/horizon-client/src/android/launcher.rs for automatic direct boot into game::android_game()
launcher_rs = Path("crates/horizon-client/src/android/launcher.rs")
if launcher_rs.exists():
    txt = launcher_rs.read_text(encoding="utf-8")

    old_resumed = """        if let Err(error) = self.open(events) {
            self.fail(error);
            events.exit();
        }"""
    new_resumed = """        if let Err(error) = self.open(events) {
            self.fail(error);
            events.exit();
            return;
        }
        if self.game.is_none() && self.worker.is_none() {
            if let Err(error) = self.action(events) {
                self.fail(error);
            }
        }"""
    if old_resumed in txt:
        txt = txt.replace(old_resumed, new_resumed, 1)
        print("[HorizonZ Patch] Patched Launcher::resumed for automatic direct boot!")

    old_worker = """        if let Some(result) = self.worker.as_ref().and_then(|w| w.try_recv().ok()) {
            self.worker = None;
            if let Err(error) = result {
                self.fail(error);
            }
        }"""
    new_worker = """        if let Some(result) = self.worker.as_ref().and_then(|w| w.try_recv().ok()) {
            self.worker = None;
            match result {
                Ok(()) => {
                    if let Err(error) = self.action(events) {
                        self.fail(error);
                    }
                }
                Err(error) => self.fail(error),
            }
        }"""
    if old_worker in txt:
        txt = txt.replace(old_worker, new_worker, 1)
        print("[HorizonZ Patch] Patched Launcher::about_to_wait to auto-launch game::android_game()!")

    launcher_rs.write_text(txt, encoding="utf-8")

# 2. Make controls_with_progress in crates/horizon-client/src/android_resources.rs fault-tolerant
#    and ensure "../../../packaging/android/bedrock-controls.json" always has a valid revision fallback
android_res_rs = Path("crates/horizon-client/src/android_resources.rs")
if android_res_rs.exists():
    res_txt = android_res_rs.read_text(encoding="utf-8")
    old_rev = '.ok_or("Missing control revision")?'
    new_rev = '.unwrap_or("46ba6ea985fb5a92d79a9419198f10dda14c199d")'
    if old_rev in res_txt:
        res_txt = res_txt.replace(old_rev, new_rev)
        android_res_rs.write_text(res_txt, encoding="utf-8")
        print("[HorizonZ Patch] Patched android_resources.rs with guaranteed control revision fallback!")

# 3. Append JNI official_install + detailed Download Process Logger to crates/horizon-client/src/android_main.rs
android_main_rs = Path("crates/horizon-client/src/android_main.rs")
if android_main_rs.exists():
    main_txt = android_main_rs.read_text(encoding="utf-8")
    jni_bridge_code = '''

// === HorizonZ 1.1 JNI Bridge for Official 26.3 Asset Installer & Live Download Logger ===
fn append_install_log(home: &std::path::Path, msg: &str) {
    use std::io::Write;
    let log_path = home.join("zalith-install-log.txt");
    if let Ok(mut file) = std::fs::OpenOptions::new().create(true).append(true).open(log_path) {
        let _ = writeln!(file, "{}", msg);
    }
    log::info!("[HorizonZ-Installer] {}", msg);
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeIsGameInstalled(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    home: jni::objects::JString,
) -> jni::sys::jboolean {
    let home_str: String = env.get_string(&home).map(|s| s.into()).unwrap_or_default();
    let home_path = std::path::Path::new(&home_str);
    if horizon_client::official_install::ready(home_path) { 1 } else { 0 }
}

#[no_mangle]
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
        r#"{"ready":false,"running":true,"stage":"Metadata","done":0,"total":100,"error":""}"#,
    );
    let _ = std::fs::write(
        home_path.join("zalith-install-log.txt"),
        "[Installer] Initializing Minecraft 26.3 official asset installer...\n",
    );

    std::thread::spawn(move || {
        let p_file = home_path.join("zalith-install-progress.json");
        let last_stage = std::sync::Mutex::new(String::new());
        let last_logged_pct = std::sync::atomic::AtomicU64::new(0);

        let res = horizon_client::official_install::install_with_progress(&home_path, |prog| {
            let stage_name = format!("{:?}", prog.stage);
            let total = prog.total.max(1);
            let pct = (prog.done.saturating_mul(100)) / total;

            if let Ok(mut guard) = last_stage.lock() {
                if *guard != stage_name {
                    *guard = stage_name.clone();
                    append_install_log(
                        &home_path,
                        &format!("[Stage: {}] Started (target items: {})", stage_name, prog.total),
                    );
                    last_logged_pct.store(0, std::sync::atomic::Ordering::Relaxed);
                }
            }

            let prev_pct = last_logged_pct.load(std::sync::atomic::Ordering::Relaxed);
            if pct >= prev_pct + 10 || (prog.done == prog.total && prog.total > 0) {
                last_logged_pct.store(pct, std::sync::atomic::Ordering::Relaxed);
                append_install_log(
                    &home_path,
                    &format!("[{}] Progress: {} / {} ({}%)", stage_name, prog.done, prog.total, pct),
                );
            }

            let json = format!(
                r#"{{"ready":false,"running":true,"stage":"{}","done":{},"total":{},"error":""}}"#,
                stage_name,
                prog.done,
                total
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
                append_install_log(&home_path, "[Complete] Minecraft 26.3 assets and worldgen ready!");
                let _ = std::fs::write(
                    &p_file,
                    r#"{"ready":true,"running":false,"stage":"Ready","done":100,"total":100,"error":""}"#,
                );
            }
            Err(err) => {
                let clean_err = err.to_string().replace('"', "'").replace('\n', " ");
                append_install_log(&home_path, &format!("[Error] Installation failed: {}", clean_err));
                let json = format!(
                    r#"{{"ready":false,"running":false,"stage":"Error","done":0,"total":100,"error":"{}"}}"#,
                    clean_err
                );
                let _ = std::fs::write(&p_file, json);
            }
        }
    });
    1
}

#[no_mangle]
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
        r#"{"ready":true,"running":false,"stage":"Ready","done":100,"total":100,"error":""}"#.to_string()
    } else {
        r#"{"ready":false,"running":false,"stage":"Needs Download","done":0,"total":100,"error":""}"#.to_string()
    };
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
'''
    if "nativeStartOfficialInstall" not in main_txt:
        main_txt = main_txt + jni_bridge_code
        android_main_rs.write_text(main_txt, encoding="utf-8")
        print("[HorizonZ Patch] Added JNI official_install hooks & live logger to android_main.rs!")
