package com.shieldtools.launchermanager.backend

fun main() {
    var passed=0
    fun test(name: String, body: () -> Unit) { body(); passed++; println("PASS $name") }
    fun denied(manufacturer: String="NVIDIA", device: String="sif", api: Int=30) {
        check(runCatching { ShieldDevicePolicy.requireSupported(manufacturer,device,api) }.isFailure)
    }
    test("Pro mdarcy API 30 stays supported") { ShieldDevicePolicy.requireSupported("NVIDIA","mdarcy",30) }
    test("Tube sif API 30 is supported by exact identity") { ShieldDevicePolicy.requireSupported("NVIDIA","sif",30) }
    test("existing case-insensitive manufacturer rule remains") { ShieldDevicePolicy.requireSupported("nViDiA","mdarcy",30) }
    test("other manufacturers fail for both devices") { listOf("mdarcy","sif").forEach { denied(manufacturer="Other",device=it) } }
    test("other NVIDIA devices are rejected") { listOf("foster","darcy","foster_e","other").forEach { denied(device=it) } }
    test("API versions outside 30 are rejected for both devices") { listOf("mdarcy","sif").forEach { d -> listOf(29,31,34).forEach { denied(device=d,api=it) } } }
    test("device identity is exact with no prefix case or whitespace bypass") { listOf("SIF","sif2","sif "," mdarcy","").forEach { denied(device=it) } }
    test("missing manufacturer fails closed") { denied(manufacturer="") }
    println("$passed Shield device policy tests passed")
}
