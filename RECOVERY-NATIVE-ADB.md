# Independent NVIDIA Stock recovery

The recovery script accepts explicitly selected `mdarcy` (Shield Pro) or `sif` (Shield TV 2019 Tube), requiring NVIDIA/API30, Android user0 and shell UID2000. Its historical filename is retained for compatibility. Do not use an inferred model/IP; verify each actual endpoint first. No network scanning is included.

Record the current HOME component, Stock package enabled setting AND component enabled setting, firmware/API/device identity and independent recovery access before any test. Enabled state0 is default,1 explicit-enabled,2/3/4 disabled variants. Do not treat the Pro's historical state0 as a Tube's baseline.

```powershell
$adb = '<platform-tools-directory>\adb.exe'
$shield = '<your-verified-shield-address>:5555'
& $adb -s $shield get-state
& $adb -s $shield shell getprop ro.product.manufacturer
& $adb -s $shield shell getprop ro.product.device
& $adb -s $shield shell getprop ro.build.version.sdk
& $adb -s $shield shell getprop ro.product.cpu.abilist
& $adb -s $shield shell am get-current-user
& $adb -s $shield shell id -u
& $adb -s $shield shell cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME
& $adb -s $shield shell dumpsys package com.google.android.tvlauncher
```

Require the verified address/identity, owner user and expected Stock component. Stop on unknown/disabled component states or missing packages. These examples do not enable debugging, reset keys or create a connection to an unspecified device.

## Explicit emergency Stock HOME

The script intentionally restores STOCK HOME. It requires `-DesiredStockEnabledSetting 0` or `1` with NO default. For a recorded original enabled state0 or1, explicitly pass that independently recorded value. Example placeholders must be replaced only after authorization:

```powershell
.\scripts\Restore-Pro-StockHome.ps1 -ExecuteRecovery -AdbPath $adb -Target $shield -ExpectedDevice '<mdarcy-or-sif>' -DesiredStockEnabledSetting <recorded-0-or-1>
```

For a Tube starting with Detox HOME and Stock disabled, there is NO original enabled0/1 baseline to restore. Do not run this command merely by copying the example. Emergency Stock availability would require separately approving an intentional enabled state1 (or an independently justified default state0); that changes the original configuration and is NOT exact original-state restoration. Keeping Stock HOME and disabled state3 simultaneously is not possible. Restoring original Detox HOME plus disabled Stock needs a validated journal-owned Restore Previous path or a separately reviewed recovery plan.

A fresh Manager install does not own pre-existing Stock disabling. Restore Stock should refuse without journal proof. Do not import/fabricate a journal, clear data or normalize Stock automatically. A separately approved Activate HOME on Stock is a new transaction, explicitly enables it and records its prior disabled state; Restore Previous can then target the original Detox/disabled baseline, subject to strict identity/HOME checks. The primary Tube completed that controlled route with exact 3/0 restoration; do not assume the same baseline on another device.

The script verifies actual Stock HOME and exact requested enabled setting before launching Stock. On failure STOP; do not improvise other package writes. Physically test HOME. It never alters other applications or Manager credentials/journals. An ambiguous nested package dump must not be mistaken for the live user0 state. Keep the physical remote and independent workstation available.

Recover pending uses the interrupted transaction's recorded prior HOME. For an interrupted restoration, that may be the alternate launcher you were leaving. Review the exact target before consenting; never assume it returns to the initial configuration of a longer session. Process death, reboot and lost ADB do not guarantee automatic rollback.
