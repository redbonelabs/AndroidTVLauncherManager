package com.shieldtools.launchermanager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.system.Os
import android.system.OsConstants
import com.shieldtools.launchermanager.backend.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-install RSA identity encrypted at rest by a nonexportable Android Keystore AES key.
 * Both identity and transaction journal are app-private, outside backup. Never log keys. */
internal class AdbIdentity(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir,"adb-identity-v1"))
    private val alias = "launcher-manager.adb.identity.v1"
    fun loadOrCreate(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val key = store.getKey(alias,null) as? SecretKey ?: error("ADB identity wrapping key lost; recovery required. Do not silently regenerate.")
            val packed = file.openRead().use { it.readBytes() }
            check(packed.size in 32..8192)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,packed.copyOfRange(0,12)))
            cipher.updateAAD(alias.toByteArray())
            val plain = cipher.doFinal(packed.copyOfRange(12,packed.size))
            try {
                return DataInputStream(ByteArrayInputStream(plain)).use { input ->
                    fun block(): ByteArray { val n = input.readInt(); check(n in 1..4096); return ByteArray(n).also(input::readFully) }
                    val factory = KeyFactory.getInstance("RSA")
                    val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(block()))
                    val publicKey = factory.generatePublic(X509EncodedKeySpec(block()))
                    check(input.available() == 0)
                    KeyPair(publicKey,privateKey).also { AdbRsa.publicKey(it) }
                }
            } finally { plain.fill(0) }
        }
        val key = (store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val plain = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(pair.private.encoded.size); it.write(pair.private.encoded)
            it.writeInt(pair.public.encoded.size); it.write(pair.public.encoded)
        } }.toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key); updateAAD(alias.toByteArray()) }
            val encrypted = cipher.doFinal(plain)
            check(cipher.iv.size == 12)
            val stream = file.startWrite()
            try { stream.write(cipher.iv + encrypted); stream.fd.sync(); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        } finally { plain.fill(0) }
        check(file.openRead().use { it.readBytes().isNotEmpty() })
        syncPrivateDirectory(file.baseFile.parentFile!!)
        return pair
    }
}

internal class AppJournal(context: Context) : BlobStore {
    private val file = AtomicFile(File(context.noBackupFilesDir,"home-transactions-v1"))
    override fun read(): ByteArray? = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
        file.openRead().use { check(it.channel.size() <= 524332); it.readBytes() } else null
    override fun atomicWrite(bytes: ByteArray) {
        require(bytes.size <= 524332)
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) } catch(e: Exception) { file.failWrite(stream); throw e }
        check(file.openRead().use { it.readBytes().contentEquals(bytes) }) { "Journal write verification failed" }
        syncPrivateDirectory(file.baseFile.parentFile!!)
    }
}

/** No endpoint parameter exists: the only destination is this device's IPv4 loopback, port 5555. */
internal fun connectLoopback(pair: KeyPair): AdbProtocol {
    val socket = Socket()
    try {
        socket.connect(InetSocketAddress(OnDeviceCommands.HOST,OnDeviceCommands.PORT),3000)
        socket.tcpNoDelay = true
        var expires = 0L
        fun checkTime() { val left = expires - android.os.SystemClock.elapsedRealtime(); check(left > 0) { "ADB operation timed out; result unknown" }; socket.soTimeout = left.coerceAtMost(60000).toInt() }
        val raw = socket.getInputStream()
        val bounded = object : FilterInputStream(raw) {
            override fun read(): Int { checkTime(); return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { checkTime(); return `in`.read(b,off,len) }
        }
        return AdbProtocol(bounded,socket.getOutputStream(),{ expires = android.os.SystemClock.elapsedRealtime() + it },{}, { socket.close() })
            .also { it.authenticate(pair,allowPublicKey = true) }
    } catch(e: Exception) { socket.close(); throw e }
}


private fun syncPrivateDirectory(directory: File) {
    val fd = Os.open(directory.path,OsConstants.O_RDONLY,0)
    try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
}

