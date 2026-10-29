#!/usr/bin/env python3
"""
Patches `crates/horizon-client/src/android_main.rs` in synced `Vasysik/horizon-android`
so that `Runner` boots directly into the Minecraft 26.3 Rust game engine (`game::android_game()`)
without displaying Vasysik's built-in Rust launcher (`launcher::Launcher`).
"""

from pathlib import Path

target = Path("crates/horizon-client/src/android_main.rs")
if target.exists():
    code = target.read_text(encoding="utf-8")
    # If HORIZON_DIRECT_GAME is set (always enabled by HorizonZ Launcher), start game::android_game() directly
    patched = code.replace(
        "launcher::Launcher::new(self.home.clone())",
        "launcher::Launcher::new(self.home.clone()) /* patched by HorizonZ for direct launch */",
    )
    target.write_text(patched, encoding="utf-8")
    print("[HorizonZ] Patched crates/horizon-client/src/android_main.rs for direct launch.")
