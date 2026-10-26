package com.shieldtools.launchermanager.backend

import com.shieldtools.launchermanager.HomeEntry
import com.shieldtools.launchermanager.LauncherSafety
import java.io.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import javax.crypto.Cipher

private var passed = 0
private fun test(name: String, body: () -> Unit) { body(); passed++; println("PASS $name") }
private fun denied(body: () -> Unit) { check(runCatching(body).isFailure) { "Expected rejection" } }
private fun packets(vararg p: AdbPacket): ByteArray = ByteArrayOutputStream().also { out -> p.forEach { AdbWire.write(out,it) } }.toByteArray()
private fun frames(checksums: Boolean, vararg packets: AdbPacket): ByteArray = ByteArrayOutputStream().also { out -> packets.forEach { AdbWire.write(out,it,checksums) } }.toByteArray()
private fun withChecksum(bytes: ByteArray, checksum: Int): ByteArray = bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16,checksum) }
private fun shell(id: Int, data: ByteArray) = ByteBuffer.allocate(5+data.size).order(ByteOrder.LITTLE_ENDIAN).put(id.toByte()).putInt(data.size).put(data).array()
private fun peer(features: String = "shell_v2", version: Int = 0x01000000) = AdbPacket(AdbWire.CNXN,version,4096,"device::features=$features;\u0000".toByteArray())
private fun entry(pkg: String = "org.example.launcher") = HomeEntry(pkg,"$pkg.Home",pkg,0,true,true,true,true,false,false,false,false,false,true,1,"a".repeat(64),0,0)
private class ProtocolRig(input: ByteArray, val fragment: Boolean = false) {
    val output = ByteArrayOutputStream()
    var closed = false
    var closeCalls = 0
    val inputStream = object : ByteArrayInputStream(input) { override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b,off,if(fragment) minOf(len,1) else len) }
    val protocol = AdbProtocol(inputStream,output,{}, {}, { closed = true; closeCalls++ })
    fun written(): List<AdbPacket> {
        val source = ByteArrayInputStream(output.toByteArray()); val result=mutableListOf<AdbPacket>()
        while(source.available()>0) result += AdbWire.read(source,checksums=false)
        return result
    }
}
fun main() {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val token = ByteArray(20) { it.toByte() }
    test("RSA AUTH signs SHA-1 DigestInfo without hashing challenge again") {
        val decoded = Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(Cipher.DECRYPT_MODE,pair.public); doFinal(AdbRsa.signature(pair,token)) }
        check(decoded.size == 35 && decoded.takeLast(20).toByteArray().contentEquals(token))
        check(decoded.take(15).toByteArray().contentEquals(byteArrayOf(0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0,0x04,0x14)))
    }
    test("ADB public key encodes modulus and Montgomery parameters") {
        val text = AdbRsa.publicKey(pair).toString(Charsets.UTF_8); check(text.endsWith("\u0000"))
        val b = ByteBuffer.wrap(java.util.Base64.getDecoder().decode(text.substringBefore(' '))).order(ByteOrder.LITTLE_ENDIAN)
        check(b.remaining() == 524 && b.int == 64)
        val inv = b.int.toLong() and 0xffffffffL
        val n = BigInteger(1,ByteArray(256).also(b::get).reversedArray())
        val rr = BigInteger(1,ByteArray(256).also(b::get).reversedArray())
        check(n == (pair.public as java.security.interfaces.RSAPublicKey).modulus)
        check(n.multiply(BigInteger.valueOf(inv)).add(BigInteger.ONE).mod(BigInteger.ONE.shiftLeft(32)) == BigInteger.ZERO)
        check(rr == BigInteger.ONE.shiftLeft(4096).mod(n) && b.int == 65537)
    }
    test("invalid AUTH token length rejected") { denied { AdbRsa.signature(pair,ByteArray(19)) } }
    test("remembered authorization sends signature and no public key") {
        val r=ProtocolRig(packets(AdbPacket(AdbWire.AUTH,1,0,token),peer()),true)
        r.protocol.authenticate(pair,false); check(r.protocol.connected)
        check(r.written().map { it.command } == listOf(AdbWire.CNXN,AdbWire.AUTH)); check(r.written()[1].arg0==2)
    }
    test("new authorization publishes only public key after explicit consent") {
        val r=ProtocolRig(packets(AdbPacket(AdbWire.AUTH,1,0,token),AdbPacket(AdbWire.AUTH,1,0,token),peer()))
        r.protocol.authenticate(pair,true); check(r.written().last().arg0==3)
        check(r.written().last().data.contentEquals(AdbRsa.publicKey(pair)))
    }
    test("public-key publication without consent fails closed") {
        val r=ProtocolRig(packets(AdbPacket(AdbWire.AUTH,1,0,token),AdbPacket(AdbWire.AUTH,1,0,token)))
        denied { r.protocol.authenticate(pair,false) }; check(r.closed && r.written().none { it.command==AdbWire.AUTH && it.arg0==3 })
    }
    test("denied or repeated authorization closes connection") {
        val challenge=AdbPacket(AdbWire.AUTH,1,0,token); val r=ProtocolRig(packets(challenge,challenge,challenge))
        denied { r.protocol.authenticate(pair,true) }; check(r.closed && !r.protocol.connected)
    }
    test("TLS or unknown protocol rejected without insecure fallback") {
        val p=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(0x534c5453).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0x534c5453.inv()).array()
        val r=ProtocolRig(p); denied { r.protocol.authenticate(pair,true) }; check(r.closed)
    }
    test("shell-v2 feature required") { val r=ProtocolRig(packets(peer("cmd"))); denied { r.protocol.authenticate(pair,true) }; check(r.closed) }
    test("unexpected peer or version fails closed") { val r=ProtocolRig(packets(peer(version=0x01000002))); denied { r.protocol.authenticate(pair,true) }; check(r.closed) }
    test("checksum mismatch rejected") { val bytes=packets(peer()); bytes[24]=(bytes[24].toInt() xor 1).toByte(); denied { AdbWire.read(ByteArrayInputStream(bytes)) } }
    test("negative and oversized wire payloads rejected before allocation") {
        for(n in listOf(-1,4097)) {
            val bytes=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(AdbWire.WRTE).putInt(1).putInt(1).putInt(n).putInt(0).putInt(AdbWire.WRTE.inv()).array()
            denied { AdbWire.read(ByteArrayInputStream(bytes)) }
        }
    }
    test("split shell-v2 frames collect output and require zero exit") {
        val data=shell(1,"com.example.launcher/.Home\n".toByteArray())+shell(3,byteArrayOf(0))
        val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.OKAY,42,1),AdbPacket(AdbWire.WRTE,42,1,data.copyOfRange(0,3)),AdbPacket(AdbWire.WRTE,42,1,data.copyOfRange(3,data.size)),AdbPacket(AdbWire.CLSE,42,1)),true)
        r.protocol.authenticate(pair,false); check(r.protocol.execute(LauncherAdbOperation.Home)=="com.example.launcher/.Home\n")
        check(r.written().count { it.command==AdbWire.OKAY } == 2)
        check(r.written().single { it.command==AdbWire.OPEN }.data.toString(Charsets.UTF_8).startsWith("shell,v2,raw:cmd package resolve-activity"))
    }
    test("failed shell exit cannot count as command success") { val s=ShellV2(); s.accept(shell(1,"Success".toByteArray())+shell(3,byteArrayOf(1))); denied { s.finish() } }
    test("stderr is treated as failure even with zero exit") { val s=ShellV2(); s.accept(shell(2,"denied".toByteArray())+shell(3,byteArrayOf(0))); denied { s.finish() } }
    test("truncated shell output or missing exit is unknown, never success") { val s=ShellV2(); s.accept(shell(1,"Success".toByteArray())); denied { s.finish() }; val t=ShellV2(); t.accept(byteArrayOf(3,1)); denied { t.finish() } }
    test("oversized shell stream rejected") { val s=ShellV2(); denied { s.accept(ByteArray(524289)) } }
    test("data after exit and invalid exit sizes rejected") {
        val s=ShellV2(); denied { s.accept(shell(3,byteArrayOf(0))+shell(1,byteArrayOf(1))) }
        val t=ShellV2(); denied { t.accept(shell(3,byteArrayOf(0,0))) }
    }
    test("wrong channel fails closed and invalidates session") {
        val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.OKAY,42,1),AdbPacket(AdbWire.WRTE,42,2,shell(3,byteArrayOf(0)))))
        r.protocol.authenticate(pair,false); denied { r.protocol.execute(LauncherAdbOperation.Home) }; check(r.closed && !r.protocol.connected)
    }
    test("transport loss after command cannot be retried silently") {
        val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.OKAY,42,1)))
        r.protocol.authenticate(pair,false); denied { r.protocol.execute(LauncherAdbOperation.Home) }; val size=r.output.size()
        denied { r.protocol.execute(LauncherAdbOperation.Home) }; check(r.output.size()==size && r.closed)
    }
    test("closed typed vocabulary rejects protected, stale and injected targets") {
        val e=entry(); check(LauncherAdbOperation.Select.of(e,listOf(e)).command.endsWith(e.component))
        denied { LauncherAdbOperation.Select.of(e,emptyList()) }
        denied { LauncherAdbOperation.Select.of(e.copy(versionCode=2),listOf(e)) }
        val injection=e.copy(activityName="org.example.launcher.Home;reboot")
        denied { LauncherAdbOperation.Launch.of(injection,listOf(injection)) }
        LauncherSafety.protectedPackages.filter { it != "android" }.forEach { pkg -> val bad=entry(pkg); denied { LauncherAdbOperation.Select.of(bad,listOf(bad)) } }
    }
    test("only stock can be disabled and malformed states rejected") {
        denied { LauncherAdbOperation.Enabled.of(entry(),3) }
        denied { LauncherAdbOperation.Enabled.of(entry(LauncherSafety.STOCK),5) }
        check(LauncherAdbOperation.Enabled.of(entry(LauncherSafety.STOCK),3).command=="pm disable-user --user 0 ${LauncherSafety.STOCK}")
    }
    test("partial header EOF rejected") { denied { AdbWire.read(ByteArrayInputStream(byteArrayOf(1,2,3))) } }
    test("explicit disconnect does not send a HOME or package command") {
        val r=ProtocolRig(packets(peer())); r.protocol.authenticate(pair,false); val count=r.written().size
        r.protocol.close(); check(!r.protocol.connected && r.closed && r.written().size==count)
    }
    test("definitive command failure retains authenticated transport for rollback") {
        val r=ProtocolRig(packets(peer(),
            AdbPacket(AdbWire.OKAY,41,1),AdbPacket(AdbWire.WRTE,41,1,shell(3,byteArrayOf(1))),AdbPacket(AdbWire.CLSE,41,1),
            AdbPacket(AdbWire.OKAY,42,2),AdbPacket(AdbWire.WRTE,42,2,shell(1,"stock".toByteArray())+shell(3,byteArrayOf(0))),AdbPacket(AdbWire.CLSE,42,2)))
        r.protocol.authenticate(pair,false)
        denied { r.protocol.execute(LauncherAdbOperation.Home) }
        check(r.protocol.connected && !r.closed)
        check(r.protocol.execute(LauncherAdbOperation.Home)=="stock")
        check(r.written().none { it.command==AdbWire.CLSE })
    }
    test("bootstrap advertises maximum supported version with legacy-compatible checksum") {
        val r=ProtocolRig(packets(peer())); r.protocol.authenticate(pair,false)
        val sent=r.written().first(); check(sent.arg0==AdbWire.VERSION_CURRENT && sent.arg1==4096 && AdbWire.checksumValid(sent))
        check(r.protocol.negotiatedVersion==AdbWire.VERSION_MIN && r.protocol.negotiatedMaxPayload==4096)
    }
    test("modern CNXN and AUTH accept skipped checksums while preserving RSA authentication") {
        val r=ProtocolRig(frames(false,AdbPacket(AdbWire.AUTH,1,0,token),peer(version=AdbWire.VERSION_CURRENT)))
        r.protocol.authenticate(pair,false); check(r.protocol.connected && r.protocol.negotiatedVersion==AdbWire.VERSION_CURRENT)
        check(r.written().single { it.command==AdbWire.AUTH }.data.contentEquals(AdbRsa.signature(pair,token)))
    }
    test("modern protocol ignores checksum field on data and sends zero checksums") {
        val reply=shell(1,"verified".toByteArray())+shell(3,byteArrayOf(0))
        val data=withChecksum(frames(false,AdbPacket(AdbWire.WRTE,42,1,reply)),123456)
        val r=ProtocolRig(frames(false,peer(version=AdbWire.VERSION_CURRENT),AdbPacket(AdbWire.OKAY,42,1))+data+frames(false,AdbPacket(AdbWire.CLSE,42,1)),true)
        r.protocol.authenticate(pair,false); check(r.protocol.execute(LauncherAdbOperation.Home)=="verified")
        check(r.written().filter { it.command!=AdbWire.CNXN }.all { it.checksum==0 })
    }
    test("legacy checksum validation stays enabled after negotiation") {
        val bad=withChecksum(packets(AdbPacket(AdbWire.WRTE,42,1,shell(1,"data".toByteArray()))),0)
        val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.OKAY,42,1))+bad)
        r.protocol.authenticate(pair,false); denied { r.protocol.execute(LauncherAdbOperation.Home) }; check(r.closed)
    }
    test("legacy handshake rejects missing AUTH checksum even if CNXN is valid") {
        val r=ProtocolRig(frames(false,AdbPacket(AdbWire.AUTH,1,0,token))+packets(peer()))
        denied { r.protocol.authenticate(pair,false) }; check(r.closed && !r.protocol.connected)
    }
    test("legacy CNXN checksum cannot be skipped") {
        val r=ProtocolRig(frames(false,peer())); denied { r.protocol.authenticate(pair,false) }; check(r.closed)
    }
    test("version bounds reject older, future and unsigned-invalid versions") {
        for(version in listOf(0x00ffffff,0x01000002,-1)) {
            val r=ProtocolRig(frames(false,peer(version=version))); denied { r.protocol.authenticate(pair,false) }; check(r.closed)
        }
    }
    test("peer banner and shell feature remain mandatory in modern mode") {
        val p=peer(version=AdbWire.VERSION_CURRENT)
        for(bad in listOf(p.copy(data="host::features=shell_v2;".toByteArray()),peer("cmd",AdbWire.VERSION_CURRENT))) {
            val r=ProtocolRig(frames(false,bad)); denied { r.protocol.authenticate(pair,false) }; check(r.closed)
        }
    }
    test("advertised large payload is capped to our receive and send limit") {
        val p=peer(version=AdbWire.VERSION_CURRENT).copy(arg1=1024*1024)
        val r=ProtocolRig(frames(false,p)); r.protocol.authenticate(pair,false); check(r.protocol.negotiatedMaxPayload==4096)
    }
    test("zero negative and excessive peer payload limits are rejected") {
        for(limit in listOf(0,-1,1024*1024+1)) {
            val r=ProtocolRig(frames(false,peer(version=AdbWire.VERSION_CURRENT).copy(arg1=limit)))
            denied { r.protocol.authenticate(pair,false) }; check(r.closed)
        }
    }
    test("smaller negotiated payload bounds inbound frames") {
        val p=peer(version=AdbWire.VERSION_CURRENT).copy(arg1=512)
        val r=ProtocolRig(frames(false,p,AdbPacket(AdbWire.OKAY,42,1),AdbPacket(AdbWire.WRTE,42,1,ByteArray(513))))
        r.protocol.authenticate(pair,false); check(r.protocol.negotiatedMaxPayload==512)
        denied { r.protocol.execute(LauncherAdbOperation.Home) }; check(r.closed)
    }
    test("smaller negotiated payload bounds outgoing service request before sending") {
        val r=ProtocolRig(frames(false,peer(version=AdbWire.VERSION_CURRENT).copy(arg1=16)))
        r.protocol.authenticate(pair,false); val count=r.written().size
        denied { r.protocol.execute(LauncherAdbOperation.Home) }; check(r.closed && r.written().size==count)
    }
    test("magic and hard framing bounds are enforced even with checksums skipped") {
        val bad=frames(false,peer(version=AdbWire.VERSION_CURRENT)); bad[20]=(bad[20].toInt() xor 1).toByte()
        val r=ProtocolRig(bad); denied { r.protocol.authenticate(pair,false) }; check(r.closed)
        val header=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(AdbWire.WRTE).putInt(1).putInt(1).putInt(4097).putInt(0).putInt(AdbWire.WRTE.inv()).array()
        denied { AdbWire.read(ByteArrayInputStream(header),checksums=false) }
    }
    test("unexpected pre-authentication commands never establish modern connection") {
        val r=ProtocolRig(frames(false,AdbPacket(AdbWire.OKAY,1,1)))
        denied { r.protocol.authenticate(pair,true) }; check(r.closed && !r.protocol.connected)
    }
    test("modern reconnect signs with same identity and never republishes key") {
        for(i in 1..2) {
            val r=ProtocolRig(frames(false,AdbPacket(AdbWire.AUTH,1,0,token),peer(version=AdbWire.VERSION_CURRENT)))
            r.protocol.authenticate(pair,false); check(r.written().none { it.command==AdbWire.AUTH && it.arg0==3 })
            check(r.written().last().data.contentEquals(AdbRsa.signature(pair,token)))
            r.protocol.close(); r.protocol.close(); check(r.closeCalls==1)
            denied { r.protocol.authenticate(pair,false) }
        }
    }
    test("modern command failure preserves session but truncated exit closes transport") {
        val p=peer(version=AdbWire.VERSION_CURRENT)
        val r=ProtocolRig(frames(false,p,AdbPacket(AdbWire.OKAY,42,1),AdbPacket(AdbWire.WRTE,42,1,shell(2,"denied".toByteArray())+shell(3,byteArrayOf(1))),AdbPacket(AdbWire.CLSE,42,1)))
        r.protocol.authenticate(pair,false); denied { r.protocol.execute(LauncherAdbOperation.Home) }; check(r.protocol.connected && !r.closed)
        val t=ProtocolRig(frames(false,p,AdbPacket(AdbWire.OKAY,42,1),AdbPacket(AdbWire.WRTE,42,1,shell(1,"incomplete".toByteArray())),AdbPacket(AdbWire.CLSE,42,1)))
        t.protocol.authenticate(pair,false); denied { t.protocol.execute(LauncherAdbOperation.Home) }; check(t.closed && !t.protocol.connected)
    }
    test("sequential commands use unique local IDs and never answer remote CLOSE") {
        val inputs=mutableListOf(peer(version=AdbWire.VERSION_CURRENT))
        for(id in 1..3) inputs += listOf(AdbPacket(AdbWire.OKAY,42,id),AdbPacket(AdbWire.WRTE,42,id,shell(1,"result$id".toByteArray())+shell(3,byteArrayOf(0))),AdbPacket(AdbWire.CLSE,42,id))
        val r=ProtocolRig(frames(false,*inputs.toTypedArray()));r.protocol.authenticate(pair,false)
        for(id in 1..3) check(r.protocol.execute(LauncherAdbOperation.Home)=="result$id")
        check(r.written().filter { it.command==AdbWire.OPEN }.map { it.arg0 }==listOf(1,2,3))
        check(r.written().none { it.command==AdbWire.CLSE } && r.protocol.connected)
    }
    test("recorded Shield duplicate CLOSE before next OPEN acknowledgement is ignored") {
        val r=ProtocolRig(frames(false,peer(version=AdbWire.VERSION_CURRENT),
            AdbPacket(AdbWire.OKAY,471,1),AdbPacket(AdbWire.WRTE,471,1,shell(1,"NVIDIA".toByteArray())+shell(3,byteArrayOf(0))),AdbPacket(AdbWire.CLSE,471,1),
            AdbPacket(AdbWire.CLSE,471,1),AdbPacket(AdbWire.OKAY,472,2),AdbPacket(AdbWire.WRTE,472,2,shell(1,"mdarcy".toByteArray())+shell(3,byteArrayOf(0))),AdbPacket(AdbWire.CLSE,472,2)))
        r.protocol.authenticate(pair,false);check(r.protocol.execute(LauncherAdbOperation.Manufacturer)=="NVIDIA")
        check(r.protocol.execute(LauncherAdbOperation.Device)=="mdarcy" && r.protocol.connected)
    }
    test("late packets for closed streams cannot contaminate active stdout or ACKs") {
        val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.OKAY,40,1),AdbPacket(AdbWire.WRTE,40,1,shell(3,byteArrayOf(0))),AdbPacket(AdbWire.CLSE,40,1),
            AdbPacket(AdbWire.OKAY,40,1),AdbPacket(AdbWire.WRTE,40,1,shell(1,"stale".toByteArray())),AdbPacket(AdbWire.CLSE,40,1),
            AdbPacket(AdbWire.OKAY,41,2),AdbPacket(AdbWire.CLSE,40,1),AdbPacket(AdbWire.WRTE,40,1,shell(1,"stale".toByteArray())),
            AdbPacket(AdbWire.WRTE,41,2,shell(1,"current".toByteArray())+shell(3,byteArrayOf(0))),AdbPacket(AdbWire.CLSE,41,2)))
        r.protocol.authenticate(pair,false);check(r.protocol.execute(LauncherAdbOperation.Home)=="")
        check(r.protocol.execute(LauncherAdbOperation.Home)=="current")
        check(r.written().count { it.command==AdbWire.OKAY }==2)
    }
    test("failed OPEN closes only its local stream and permits the next command") {
        val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.CLSE,0,1),AdbPacket(AdbWire.CLSE,0,1),
            AdbPacket(AdbWire.OKAY,42,2),AdbPacket(AdbWire.WRTE,42,2,shell(3,byteArrayOf(0))),AdbPacket(AdbWire.CLSE,42,2)))
        r.protocol.authenticate(pair,false)
        var rejected=false;try{r.protocol.execute(LauncherAdbOperation.Home)}catch(_:AdbOpenFailure){rejected=true}
        check(rejected && r.protocol.connected && !r.closed)
        check(r.protocol.execute(LauncherAdbOperation.Home)=="")
        check(r.written().filter { it.command==AdbWire.OPEN }.map { it.arg0 }==listOf(1,2))
    }
    test("active remote CLOSE without a verified shell exit fails and cleans transport") {
        val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.OKAY,42,1),AdbPacket(AdbWire.CLSE,42,1)))
        r.protocol.authenticate(pair,false);denied{r.protocol.execute(LauncherAdbOperation.Home)}
        check(r.closed && r.closeCalls==1 && r.written().none { it.command==AdbWire.CLSE })
    }
    test("wrong sender on active CLOSE is a real protocol failure") {
        for(remote in listOf(0,43)) {
            val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.OKAY,42,1),AdbPacket(AdbWire.CLSE,remote,1)))
            r.protocol.authenticate(pair,false);denied{r.protocol.execute(LauncherAdbOperation.Home)};check(r.closed)
        }
    }
    test("CLOSE for unissued recipient is not discarded as stale") {
        val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.CLSE,0,2)))
        r.protocol.authenticate(pair,false);denied{r.protocol.execute(LauncherAdbOperation.Home)};check(r.closed)
    }
    test("WRTE before OPEN READY is rejected") {
        val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.WRTE,42,1,shell(3,byteArrayOf(0)))))
        r.protocol.authenticate(pair,false);denied{r.protocol.execute(LauncherAdbOperation.Home)};check(r.closed)
    }
    test("same-pair duplicate READY preserves stream and shell exit validation") {
        val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.OKAY,42,1),AdbPacket(AdbWire.OKAY,42,1),AdbPacket(AdbWire.WRTE,42,1,shell(3,byteArrayOf(7))),AdbPacket(AdbWire.CLSE,42,1)))
        r.protocol.authenticate(pair,false);denied{r.protocol.execute(LauncherAdbOperation.Home)};check(r.protocol.connected && !r.closed)
    }
    test("malformed closed-stream control payload is still rejected") {
        val r=ProtocolRig(packets(peer(),AdbPacket(AdbWire.OKAY,42,1),AdbPacket(AdbWire.WRTE,42,1,shell(3,byteArrayOf(0))),AdbPacket(AdbWire.CLSE,42,1),AdbPacket(AdbWire.CLSE,42,1,byteArrayOf(1))))
        r.protocol.authenticate(pair,false);r.protocol.execute(LauncherAdbOperation.Home)
        denied{r.protocol.execute(LauncherAdbOperation.Home)};check(r.closed)
    }
    test("read timeout closes resources once and never yields partial success") {
        val bytes=ByteArrayInputStream(packets(peer(),AdbPacket(AdbWire.OKAY,42,1)))
        var reads=0;var closes=0
        val protocol=AdbProtocol(bytes,ByteArrayOutputStream(),{}, { if(++reads==3)throw java.net.SocketTimeoutException() },{closes++})
        protocol.authenticate(pair,false);denied{protocol.execute(LauncherAdbOperation.Home)}
        protocol.close();check(closes==1 && !protocol.connected)
    }
    println("$passed native ADB protocol/security tests passed. In-memory peer only; no device or network connection.")
}


