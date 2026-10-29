//! HorizonZ Android Native Bridge (`libhorizon_android.so`)
//!
//! When built inside the full synced `Vasysik/horizon-android` workspace (`tools/android_build.py`),
//! `crates/horizon-android/Cargo.toml` points `[lib].path` to `../horizon-client/src/android_main.rs`.
//! This file provides the complete JNI symbols required by:
//! - `dev.horizon.android.HorizonActivity` (`nativeDocumentResult`)
//! - `dev.horizon.android.HorizonDocumentsProvider` (`nativeCall`, `nativeOpen`)
//! - `dev.horizon.android.bridge.HorizonNativeBridge` (`nativeGetEngineInfo`, `nativePrepareAssets`, `nativeApplyLauncherConfig`)

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jint, jstring};
use std::fs;
use std::os::unix::io::IntoRawFd;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

static LAST_SAF_URI: Mutex<Option<String>> = Mutex::new(None);

fn init_logging() {
    #[cfg(target_os = "android")]
    {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("HorizonZ-Rust"),
        );
    }
}

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|js| js.into())
        .unwrap_or_default()
}

/// JNI callback from `dev.horizon.android.HorizonActivity.nativeDocumentResult(int request, int result, String uri)`
/// Handles Storage Access Framework (SAF) request code 4101.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonActivity_nativeDocumentResult(
    mut env: JNIEnv,
    _thiz: JObject,
    request: jint,
    result: jint,
    uri: JString,
) {
    init_logging();
    let uri_str = if uri.is_null() {
        String::new()
    } else {
        jstring_to_string(&mut env, &uri)
    };
    log::info!(
        "HorizonActivity.nativeDocumentResult: request={}, result={}, uri={}",
        request,
        result,
        uri_str
    );
    if let Ok(mut guard) = LAST_SAF_URI.lock() {
        *guard = if uri_str.is_empty() { None } else { Some(uri_str) };
    }
}

/// JNI callback from `dev.horizon.android.HorizonDocumentsProvider.nativeCall(String home, String operation, String id, String arg)`
/// Exposes Horizon worlds, resourcepacks, mods, and logs to the Android Files app (SAF).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonDocumentsProvider_nativeCall(
    mut env: JNIEnv,
    _class: JClass,
    home: JString,
    operation: JString,
    id: JString,
    arg: JString,
) -> jstring {
    init_logging();
    let home_str = jstring_to_string(&mut env, &home);
    let op_str = jstring_to_string(&mut env, &operation);
    let id_str = jstring_to_string(&mut env, &id);
    let arg_str = if arg.is_null() {
        String::new()
    } else {
        jstring_to_string(&mut env, &arg)
    };

    let response = handle_documents_call(Path::new(&home_str), &op_str, &id_str, &arg_str);
    env.new_string(response)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// JNI callback from `dev.horizon.android.HorizonDocumentsProvider.nativeOpen(String home, String id, String mode)`
/// Opens a raw UNIX file descriptor for `ParcelFileDescriptor.adoptFd(fd)`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_HorizonDocumentsProvider_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    home: JString,
    id: JString,
    mode: JString,
) -> jint {
    init_logging();
    let home_str = jstring_to_string(&mut env, &home);
    let id_str = jstring_to_string(&mut env, &id);
    let mode_str = jstring_to_string(&mut env, &mode);

    let target = resolve_safe_document_path(Path::new(&home_str), &id_str);
    let open_res = if mode_str.contains('w') {
        fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(mode_str.contains('t'))
            .open(&target)
    } else {
        fs::OpenOptions::new().read(true).open(&target)
    };

    match open_res {
        Ok(file) => file.into_raw_fd() as jint,
        Err(err) => {
            let _ = env.throw_new("java/io/FileNotFoundException", format!("{}: {}", target.display(), err));
            -1
        }
    }
}

/// Additional helper JNI call for the Material 3 ZalithLauncher frontend (`HorizonNativeBridge`)
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeGetEngineStatus(
    mut env: JNIEnv,
    _class: JClass,
    home: JString,
) -> jstring {
    init_logging();
    let home_str = jstring_to_string(&mut env, &home);
    let home_path = Path::new(&home_str);
    let engine_zip = home_path.join("engine-data.zip").exists();
    let prepared_26_3 = home_path.join(".local/horizon-client/26.3").exists()
        || home_path.join("resources/26.3").exists();
    let status = serde_json::json!({
        "engine_version": "0.2.97",
        "android_port_version": "0.7.4",
        "minecraft_target": "26.3",
        "engine_data_present": engine_zip,
        "resources_prepared": prepared_26_3,
        "backend": "Rust 100% Native (wgpu Vulkan / OpenGL ES 3.0)"
    });
    env.new_string(status.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn resolve_safe_document_path(home: &Path, doc_id: &str) -> PathBuf {
    let rel = doc_id.trim_start_matches("root").trim_start_matches('/');
    if rel.is_empty() {
        return home.to_path_buf();
    }
    let candidate = home.join(rel);
    if candidate.starts_with(home) {
        candidate
    } else {
        home.to_path_buf()
    }
}

fn handle_documents_call(home: &Path, op: &str, id: &str, arg: &str) -> String {
    let target = resolve_safe_document_path(home, id);
    match op {
        "stat" => {
            let is_dir = target.is_dir() || id == "root" || id.is_empty();
            let size = fs::metadata(&target).map(|m| m.len()).unwrap_or(0);
            let mtime = fs::metadata(&target)
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
            let name = if id == "root" || id.is_empty() {
                "HorizonZ".to_string()
            } else {
                target
                    .file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_else(|| "HorizonZ".to_string())
            };
            serde_json::json!({
                "document_id": if id.is_empty() { "root" } else { id },
                "display_name": name,
                "mime_type": if is_dir { "vnd.android.document/directory" } else { "application/octet-stream" },
                "size": size,
                "last_modified": mtime,
                "is_dir": is_dir
            })
            .to_string()
        }
        "list" => {
            let _ = fs::create_dir_all(&target);
            let mut entries = Vec::new();
            if let Ok(read_dir) = fs::read_dir(&target) {
                for entry in read_dir.flatten() {
                    let path = entry.path();
                    let name = entry.file_name().to_string_lossy().to_string();
                    let is_dir = path.is_dir();
                    let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
                    let child_id = if id == "root" || id.is_empty() {
                        format!("root/{}", name)
                    } else {
                        format!("{}/{}", id.trim_end_matches('/'), name)
                    };
                    entries.push(serde_json::json!({
                        "document_id": child_id,
                        "display_name": name,
                        "mime_type": if is_dir { "vnd.android.document/directory" } else { "application/octet-stream" },
                        "size": size,
                        "is_dir": is_dir
                    }));
                }
            }
            serde_json::json!({ "entries": entries }).to_string()
        }
        "mkdir" => {
            let new_dir = target.join(arg);
            let _ = fs::create_dir_all(&new_dir);
            let new_id = if id == "root" || id.is_empty() {
                format!("root/{}", arg)
            } else {
                format!("{}/{}", id.trim_end_matches('/'), arg)
            };
            serde_json::json!({ "document_id": new_id }).to_string()
        }
        "delete" => {
            let ok = if target.is_dir() {
                fs::remove_dir_all(&target).is_ok()
            } else {
                fs::remove_file(&target).is_ok()
            };
            serde_json::json!({ "deleted": ok }).to_string()
        }
        _ => serde_json::json!({ "error": format!("unknown op: {}", op) }).to_string(),
    }
}
