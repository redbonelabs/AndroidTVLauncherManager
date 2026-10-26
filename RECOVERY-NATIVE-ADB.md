# Independent NVIDIA Stock recovery

For the tested NVIDIA Shield Pro mdarcy/API 30 only. Keep authorized workstation recovery independent of Manager. Do not reset authorization, clear application data or delete journals to bypass an error.

Before switching record Stock's exact state/component. The tested original baseline was `com.google.android.tvlauncher/.MainActivity`, state **0 (default)**. This does not establish your own original state.

```powershell
$adb = '<platform-tools-directory>\adb.exe'
$shield = '<your-verified-shield-address>:5555'
& $adb connect $shield
& $adb -s $shield get-state
& $adb -s $shield shell getprop ro.product.manufacturer
& $adb -s $shield shell getprop ro.product.device
& $adb -s $shield shell getprop ro.build.version.sdk
& $adb -s $shield shell am get-current-user
& $adb -s $shield shell dumpsys package com.google.android.tvlauncher
```

Require device/NVIDIA/mdarcy/API 30/user 0 and the expected installed package. Stop on failure. Run workstation ADB under your normal Windows account; do not change firewall rules or debugging settings to bypass an error.

For independently recorded original state **0**, this guarded script restores default-state and Stock HOME:

```powershell
.\scripts\Restore-Pro-StockHome.ps1 -AdbPath $adb -Target $shield -ExecuteRecovery
```

This is an intentional recovery, not a diagnostic. It does not install/uninstall/clear data, reset credentials or edit Manager's journal. Original state 1 needs a separately reviewed `pm enable --user 0 com.google.android.tvlauncher` restoration instead; do not use this state-0 script for that baseline.

```powershell
& $adb -s $shield shell cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME
& $adb -s $shield shell dumpsys package com.google.android.tvlauncher
& $adb -s $shield shell dumpsys window displays
& $adb -s $shield shell dumpsys activity activities
```

Require Stock HOME and live user-0 installed=true/enabled=0, not an older nested system-package entry. Focus/resumed summaries should agree with Stock. Physically press HOME and verify. Stop on mismatch; command success alone is insufficient.

Use Manager's normal Connect and reviewed Restore Stock/Restore Previous/Recover pending when its journal is valid. Missing journal-owned state is a deliberate refusal to infer history. Retain diagnostics. Outside recovery may leave old committed history that is not authoritative for later restoration. A fresh reviewed switch captures a fresh baseline. No blanket enabling, package removal or general debloating is part of recovery.
