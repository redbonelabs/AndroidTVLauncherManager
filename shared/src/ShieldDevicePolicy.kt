package com.shieldtools.launchermanager.backend

/** Exact local-device allowlist; Tube support is experimental pending hardware verification. */
internal object ShieldDevicePolicy {
    fun requireSupported(manufacturer: String, device: String, api: Int) {
        check(manufacturer.equals("NVIDIA", true) && device in setOf("mdarcy", "sif") && api == 30) {
            "Supported device required: NVIDIA Shield mdarcy or experimental sif / Android 11 (API 30)"
        }
    }
}
