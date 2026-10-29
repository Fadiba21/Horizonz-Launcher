#!/usr/bin/env python3
# Patches the cloned Vasysik/horizon-android repository so that:
# 1. `crates/horizon-client/src/android/launcher.rs` auto-triggers `self.action(events)` on `resumed()`
#    and after `self.worker` installation finishes, booting straight into the real `game::android_game()`
#    engine without stopping on Vasysik's built-in Rust launcher menu.
# 2. `crates/horizon-client/src/android_main.rs` exports JNI hooks (`nativeIsGameInstalled`,
#    `nativeStartOfficialInstall`, `nativeGetInstallProgress`) so the Zalith Material 3 Android UI
#    can download and prepare Minecraft 26.3 official assets directly.

from pathlib import Path

launcher_rs = Path("crates/horizon-client/src/android/launcher.rs")
if launcher_rs.exists():
    txt = launcher_rs.read_text(encoding="utf-8")

    # 1. Auto-trigger self.action(events) inside Launcher::resumed right after self.open(events)
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
        print("[HorizonZ Patch] Patched Launcher::resumed for automatic direct boot into game::android_game()!")

    # 2. Auto-trigger self.action(events) when installation worker finishes in about_to_wait
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
        print("[HorizonZ Patch] Patched Launcher::about_to_wait to auto-launch game::android_game() on install completion!")

    launcher_rs.write_text(txt, encoding="utf-8")

android_main_rs = Path("crates/horizon-client/src/android_main.rs")
if android_main_rs.exists():
    main_txt = android_main_rs.read_text(encoding="utf-8")
    jni_bridge_code = '''

// === HorizonZ Zalith Material 3 Launcher JNI Bridge for Official 26.3 Asset Installer ===
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
    std::thread::spawn(move || {
        let p_file = home_path.join("zalith-install-progress.json");
        let res = horizon_client::official_install::install_with_progress(&home_path, |prog| {
            let stage_name = format!("{:?}", prog.stage);
            let json = format!(
                r#"{{"ready":false,"running":true,"stage":"{}","done":{},"total":{},"error":""}}"#,
                stage_name,
                prog.done,
                prog.total.max(1)
            );
            let _ = std::fs::write(&p_file, json);
        });
        match res {
            Ok(()) => {
                let _ = horizon_client::android_resources::repair_structure_transforms(
                    &home_path.join("engine-data.zip"),
                    &home_path.join(".local/horizon-client").join(horizon_core::VANILLA_VERSION),
                );
                let _ = std::fs::write(
                    &p_file,
                    r#"{"ready":true,"running":false,"stage":"Ready","done":100,"total":100,"error":""}"#,
                );
            }
            Err(err) => {
                let clean_err = err.to_string().replace('"', "'").replace('\n', " ");
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
        r#"{"ready":false,"running":false,"stage":"Not Installed","done":0,"total":100,"error":""}"#.to_string()
    };
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
'''
    if "nativeStartOfficialInstall" not in main_txt:
        main_txt = main_txt + jni_bridge_code
        android_main_rs.write_text(main_txt, encoding="utf-8")
        print("[HorizonZ Patch] Added JNI official_install hooks to crates/horizon-client/src/android_main.rs!")
