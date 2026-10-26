package com.shieldtools.launchermanager.backend

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** Independently implemented from the documented ADB wire format; no upstream implementation copied. */
internal data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = byteArrayOf(),
    val checksum: Int = data.sumOf { it.toInt() and 255 })
internal object AdbWire {
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val WRTE = 0x45545257
    const val CLSE = 0x45534c43
    const val MAX = 4096
    const val VERSION_MIN = 0x01000000
    const val VERSION_SKIP_CHECKSUM = 0x01000001
    const val VERSION_CURRENT = VERSION_SKIP_CHECKSUM
    const val PEER_MAX_PAYLOAD = 1024 * 1024
    fun checksumValid(p: AdbPacket) = p.checksum == p.data.sumOf { it.toInt() and 255 }
    fun negotiateVersion(peer: Int): Int {
        require(peer in VERSION_MIN..VERSION_CURRENT) { "Unsupported ADB version" }
        return minOf(VERSION_CURRENT,peer)
    }
    fun negotiatePayload(peer: Int): Int {
        require(peer in 1..PEER_MAX_PAYLOAD) { "Invalid ADB peer payload limit" }
        return minOf(MAX,peer)
    }
    fun write(out: OutputStream, p: AdbPacket, checksums: Boolean = true, maxPayload: Int = MAX) {
        require(maxPayload in 1..MAX && p.data.size <= maxPayload)
        val checksum = if (checksums) p.data.sumOf { it.toInt() and 255 } else 0
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(p.command).putInt(p.arg0)
            .putInt(p.arg1).putInt(p.data.size).putInt(checksum).putInt(p.command.inv()).array())
        out.write(p.data); out.flush()
    }
    fun read(input: InputStream, checksums: Boolean = true, maxPayload: Int = MAX): AdbPacket {
        require(maxPayload in 1..MAX)
        fun bytes(n: Int): ByteArray = ByteArray(n).also { data ->
            var offset = 0
            while (offset < n) { val got = input.read(data, offset, n-offset); check(got > 0) { "ADB connection closed" }; offset += got }
        }
        val header = ByteBuffer.wrap(bytes(24)).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int; val a = header.int; val b = header.int; val length = header.int; val checksum = header.int
        check(header.int == command.inv() && length in 0..maxPayload) { "Invalid ADB frame" }
        check(command in setOf(CNXN, AUTH, OPEN, OKAY, WRTE, CLSE)) { "Unsupported ADB protocol (TLS pairing is not supported)" }
        val data = bytes(length)
        val packet = AdbPacket(command,a,b,data,checksum)
        check(!checksums || checksumValid(packet)) { "ADB checksum mismatch" }
        return packet
    }
}

internal object AdbRsa {
    fun signature(pair: KeyPair, token: ByteArray): ByteArray {
        require(token.size == 20) { "Invalid ADB authentication token" }
        // AUTH is already a SHA-1 digest: sign DigestInfo, do not hash the token again.
        val digestInfo = byteArrayOf(0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14)
        return Signature.getInstance("NONEwithRSA").run { initSign(pair.private); update(digestInfo + token); sign() }
    }
    fun publicKey(pair: KeyPair): ByteArray {
        val key = pair.public as RSAPublicKey
        require(key.modulus.bitLength() == 2048 && key.publicExponent == BigInteger.valueOf(65537))
        val n = key.modulus; val radix = BigInteger.ONE.shiftLeft(32)
        fun little(value: BigInteger): ByteArray = value.toByteArray().reversedArray().copyOf(256)
        val binary = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN).putInt(64)
            .putInt(radix.subtract(n.mod(radix).modInverse(radix)).toInt()).put(little(n))
            .put(little(BigInteger.ONE.shiftLeft(4096).mod(n))).putInt(65537).array()
        return (Base64.getEncoder().encodeToString(binary) + " redbonelabs@launcher-manager\u0000").toByteArray(Charsets.UTF_8)
    }
}

/** Shell-v2 frames may be split across arbitrary ADB WRTE boundaries. Exit status is mandatory. */
internal class AdbCommandFailure : IllegalStateException("Launcher command returned a failure status")
internal class AdbOpenFailure : IllegalStateException("ADB shell OPEN rejected")
internal class ShellV2 {
    private var pending = byteArrayOf()
    private val stdout = ByteArrayOutputStream()
    private val stderr = ByteArrayOutputStream()
    private var exit: Int? = null
    private var received = 0
    fun accept(data: ByteArray) {
        received += data.size; check(received <= 524288) { "ADB output limit exceeded" }
        pending += data
        while (pending.size >= 5) {
            val id = pending[0].toInt() and 255
            val count = ByteBuffer.wrap(pending,1,4).order(ByteOrder.LITTLE_ENDIAN).int
            check(count in 0..524288 && id in 1..3 && exit == null) { "Invalid shell-v2 frame" }
            if (pending.size < 5 + count) break
            val body = pending.copyOfRange(5,5+count)
            when(id) {
                1 -> stdout.write(body)
                2 -> stderr.write(body)
                3 -> { check(count == 1) { "Invalid exit status" }; exit = body[0].toInt() and 255 }
            }
            pending = pending.copyOfRange(5+count,pending.size)
        }
    }
    fun finish(): String {
        check(pending.isEmpty() && exit != null) { "ADB closed without complete exit status; result unknown" }
        if (exit != 0 || stderr.size() != 0) throw AdbCommandFailure()
        return stdout.toString("UTF-8")
    }
}

/** One serialized outgoing connection. No sync, forwarding, pairing, raw console or arbitrary services. */
internal class AdbProtocol(private val input: InputStream, private val output: OutputStream,
    private val deadline: (Long) -> Unit, private val beforeRead: () -> Unit, private val disconnect: () -> Unit) : Closeable {
    private var local = 0
    private data class ShellStream(val localId: Int, var remoteId: Int? = null)
    private var activeStream: ShellStream? = null
    private var started = false
    private var closed = false
    var negotiatedVersion: Int? = null
        private set
    var negotiatedMaxPayload = AdbWire.MAX
        private set
    private var ready = false
    val connected: Boolean get() = ready
    private fun read(): AdbPacket {
        beforeRead()
        // Before CNXN, AUTH can already use the offered modern checksum rules.
        // Preserve legacy checksum evidence until the peer version is known.
        return AdbWire.read(input, checksums = negotiatedVersion == AdbWire.VERSION_MIN,
            maxPayload = negotiatedMaxPayload)
    }
    private fun send(p: AdbPacket) = AdbWire.write(output,p,
        checksums = negotiatedVersion != AdbWire.VERSION_SKIP_CHECKSUM, maxPayload = negotiatedMaxPayload)
    fun authenticate(pair: KeyPair, allowPublicKey: Boolean) {
        check(!started && !closed) { "Create a fresh transport for reconnect" }; started = true
        deadline(60000)
        try {
            send(AdbPacket(AdbWire.CNXN,AdbWire.VERSION_CURRENT,AdbWire.MAX,"host::features=shell_v2;\u0000".toByteArray()))
            var attempts = 0
            var legacyAuthChecksumsValid = true
            while (true) {
                val p = read()
                when(p.command) {
                    AdbWire.AUTH -> {
                        legacyAuthChecksumsValid = legacyAuthChecksumsValid && AdbWire.checksumValid(p)
                        check(p.arg0 == 1 && p.arg1 == 0 && p.data.size == 20 && attempts < 2) { "ADB authorization rejected" }
                        if (attempts++ == 0) send(AdbPacket(AdbWire.AUTH,2,0,AdbRsa.signature(pair,p.data)))
                        else {
                            check(allowPublicKey) { "ADB authorization required; explicitly Connect and accept Android's prompt" }
                            send(AdbPacket(AdbWire.AUTH,3,0,AdbRsa.publicKey(pair)))
                        }
                    }
                    AdbWire.CNXN -> {
                        val banner = p.data.toString(Charsets.UTF_8)
                        val version = AdbWire.negotiateVersion(p.arg0)
                        val payload = AdbWire.negotiatePayload(p.arg1)
                        check(banner.startsWith("device::")) { "Unexpected ADB peer" }
                        check(version != AdbWire.VERSION_MIN ||
                            (legacyAuthChecksumsValid && AdbWire.checksumValid(p))) { "Legacy ADB handshake checksum mismatch" }
                        val features = banner.substringAfter("features=", "").substringBefore(';').trimEnd('\u0000').split(',')
                        check("shell_v2" in features) { "ADB shell-v2 exit verification is required" }
                        negotiatedVersion = version; negotiatedMaxPayload = payload
                        ready = true; return
                    }
                    else -> error("Unexpected ADB authentication response")
                }
            }
        } catch (e: Exception) { close(); throw e }
    }
    /** Previous local IDs are closed: only one stream is active and IDs never wrap/reuse.
     * Route by recipient ID before interpreting a reply. Ignore packets for those closed
     * IDs per ADB protocol; retain strict validation for the current or an unissued ID. */
    private fun readFor(stream: ShellStream): AdbPacket {
        while (true) {
            val p = read()
            check(p.command in setOf(AdbWire.OKAY,AdbWire.WRTE,AdbWire.CLSE)) { "Unexpected ADB stream response" }
            check(p.arg1 > 0) { "Invalid ADB recipient stream ID" }
            if (p.command != AdbWire.WRTE) check(p.data.isEmpty()) { "Invalid ADB control payload" }
            if (p.command != AdbWire.CLSE) check(p.arg0 != 0) { "Invalid ADB sender stream ID" }
            if (p.arg1 < stream.localId) continue // Allocated previously and already closed; no reply.
            check(p.arg1 == stream.localId) { "Unissued ADB recipient stream ID" }
            stream.remoteId?.let { check(p.arg0 == it) { "Wrong ADB sender stream ID" } }
            return p
        }
    }
    @Synchronized fun execute(operation: LauncherAdbOperation): String {
        check(ready) { "ADB session disconnected; explicitly reconnect" }
        check(activeStream == null) { "ADB stream already active" }
        deadline(15000)
        try {
            check(local < Int.MAX_VALUE) { "ADB stream IDs exhausted; reconnect explicitly" }
            val stream = ShellStream(++local).also { activeStream = it }
            send(AdbPacket(AdbWire.OPEN,stream.localId,0,("shell,v2,raw:" + operation.command + "\u0000").toByteArray()))
            val opened = readFor(stream)
            if (opened.command == AdbWire.CLSE) {
                check(opened.arg0 == 0) { "Invalid failed OPEN sender ID" }
                throw AdbOpenFailure() // Definitive rejection, not a failure of the transport.
            }
            check(opened.command == AdbWire.OKAY) { "ADB data received before OPEN acknowledgement" }
            stream.remoteId = opened.arg0
            val shell = ShellV2()
            while (true) {
                val p = readFor(stream)
                when(p.command) {
                    AdbWire.OKAY -> Unit // An empty READY for this same pair cannot change the stream.
                    AdbWire.WRTE -> { shell.accept(p.data); send(AdbPacket(AdbWire.OKAY,stream.localId,stream.remoteId!!)) }
                    AdbWire.CLSE -> return shell.finish() // Remote CLOSE has no CLOSE response.
                }
            }
        } catch (e: AdbOpenFailure) { throw e }
        catch (e: AdbCommandFailure) { throw e }
        catch (e: Exception) { close(); throw e }
        finally { activeStream = null }
    }
    override fun close() { ready = false; if (!closed) { closed = true; disconnect() } }
}



