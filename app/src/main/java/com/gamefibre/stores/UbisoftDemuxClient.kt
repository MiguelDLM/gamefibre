package com.gamefibre.stores

import com.gamefibre.AppLogs
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class UbisoftOwnedProduct(
    val productId: Int,
    val spaceId: String,
    val name: String,
)

/**
 * Lightweight TLS + Protobuf client for Ubisoft Connect's Demux `ownership_service`
 * (`dmx.upc.ubisoft.com:443`). Returns all owned games (including legacy titles omitted
 * from the public `uplay/graphql` endpoint).
 */
internal object UbisoftDemuxClient {
    private const val HOST = "dmx.upc.ubisoft.com"
    private const val PORT = 443
    private const val TIMEOUT_MS = 15_000
    private const val CLIENT_VERSION = 13416
    private const val OWNERSHIP_PROTO_VERSION = 7

    fun fetchOwnedGames(ticket: String): List<UbisoftOwnedProduct> {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket() as SSLSocket
        return socket.use { s ->
            s.enabledProtocols = arrayOf("TLSv1.2")
            s.soTimeout = TIMEOUT_MS
            s.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            s.startHandshake()

            val out = DataOutputStream(s.getOutputStream())
            val input = DataInputStream(s.getInputStream())

            // 1. Push client version.
            writeFrame(out, encodeUpstreamPushClientVersion(CLIENT_VERSION))

            // 2. Authenticate with Ubi_v1 ticket.
            val authReqId = 1
            writeFrame(out, encodeUpstreamAuthReq(authReqId, ticket))
            val authRsp = awaitResponse(input, authReqId)
                ?: throw IllegalStateException("Ubisoft Demux authentication timed out.")
            if (authRsp.expired) throw StoreSessionExpired()
            if (!authRsp.success) throw IllegalStateException("Ubisoft Demux authentication failed.")

            // 3. Open connection to ownership_service.
            val openReqId = 2
            writeFrame(out, encodeUpstreamOpenConnectionReq(openReqId, "ownership_service"))
            val openRsp = awaitResponse(input, openReqId)
                ?: throw IllegalStateException("Ubisoft Demux ownership_service open timed out.")
            if (!openRsp.openSuccess || openRsp.connectionId <= 0) {
                throw IllegalStateException("Could not open Ubisoft ownership_service.")
            }

            // 4. Send ownership.InitializeReq over the opened Demux connection (inner payload is 4-byte length-prefixed).
            val ownershipReq = addLengthPrefix(encodeOwnershipInitializeReq(requestId = 1))
            writeFrame(out, encodeUpstreamDataPush(openRsp.connectionId, ownershipReq))

            val rawPayload = awaitConnectionData(input, openRsp.connectionId)
                ?: throw IllegalStateException("Ubisoft ownership_service returned no data.")
            val payload = stripLengthPrefix(rawPayload)
            parseOwnershipDownstream(payload)
        }
    }

    private fun addLengthPrefix(bytes: ByteArray): ByteArray {
        val out = ByteArray(4 + bytes.size)
        out[0] = (bytes.size ushr 24).toByte()
        out[1] = (bytes.size ushr 16).toByte()
        out[2] = (bytes.size ushr 8).toByte()
        out[3] = bytes.size.toByte()
        System.arraycopy(bytes, 0, out, 4, bytes.size)
        return out
    }

    private fun stripLengthPrefix(bytes: ByteArray): ByteArray {
        if (bytes.size < 4) return bytes
        val len = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        return if (len in 1..(bytes.size - 4)) bytes.copyOfRange(4, 4 + len) else bytes
    }

    private fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray? {
        val len = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (len <= 0 || len > 32 * 1024 * 1024) {
            throw IllegalStateException("Invalid Ubisoft Demux frame length: $len")
        }
        val buf = ByteArray(len)
        input.readFully(buf)
        return buf
    }

    private data class DemuxRsp(
        val requestId: Int = 0,
        val success: Boolean = false,
        val expired: Boolean = false,
        val connectionId: Int = 0,
        val openSuccess: Boolean = false,
    )

    private fun awaitResponse(input: DataInputStream, expectedReqId: Int): DemuxRsp? {
        repeat(32) {
            val frame = readFrame(input) ?: return null
            val rsp = parseDemuxDownstreamResponse(frame)
            if (rsp != null && rsp.requestId == expectedReqId) return rsp
        }
        return null
    }

    private fun awaitConnectionData(input: DataInputStream, expectedConnectionId: Int): ByteArray? {
        repeat(64) {
            val frame = readFrame(input) ?: return null
            val data = parseDemuxDownstreamData(frame, expectedConnectionId)
            if (data != null) return data
        }
        return null
    }

    // --- Protobuf encoders ---

    private inline fun proto(block: (CodedOutputStream) -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        val cos = CodedOutputStream.newInstance(baos)
        block(cos)
        cos.flush()
        return baos.toByteArray()
    }

    private fun encodeUpstreamPushClientVersion(version: Int): ByteArray {
        val cv = proto { it.writeUInt32(1, version) }
        val push = proto { it.writeByteArray(4, cv) }
        return proto { it.writeByteArray(2, push) }
    }

    private fun encodeUpstreamAuthReq(requestId: Int, ticket: String): ByteArray {
        val token = proto { it.writeString(3, ticket) }
        val authReq = proto {
            it.writeByteArray(1, token)
            it.writeString(2, "uplay_pc")
            it.writeBool(4, false)
        }
        val req = proto {
            it.writeUInt32(1, requestId)
            it.writeByteArray(2, authReq)
        }
        return proto { it.writeByteArray(1, req) }
    }

    private fun encodeUpstreamOpenConnectionReq(requestId: Int, serviceName: String): ByteArray {
        val openReq = proto { it.writeString(2, serviceName) }
        val req = proto {
            it.writeUInt32(1, requestId)
            it.writeByteArray(3, openReq)
        }
        return proto { it.writeByteArray(1, req) }
    }

    private fun encodeUpstreamDataPush(connectionId: Int, data: ByteArray): ByteArray {
        val dataMsg = proto {
            it.writeUInt32(1, connectionId)
            it.writeByteArray(2, data)
        }
        val push = proto { it.writeByteArray(1, dataMsg) }
        return proto { it.writeByteArray(2, push) }
    }

    private fun encodeOwnershipInitializeReq(requestId: Int): ByteArray {
        val initReq = proto {
            it.writeBool(1, false) // deprecated_test_config
            it.writeBool(2, true)  // get_associations
            it.writeUInt32(3, OWNERSHIP_PROTO_VERSION)
            it.writeBool(5, false) // use_staging
        }
        val req = proto {
            it.writeUInt32(1, requestId)
            it.writeByteArray(2, initReq)
        }
        return proto { it.writeByteArray(1, req) }
    }

    // --- Protobuf decoders ---

    private fun parseDemuxDownstreamResponse(bytes: ByteArray): DemuxRsp? {
        val cis = CodedInputStream.newInstance(bytes)
        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            if (tag == 0) break
            if (WireFormat.getTagFieldNumber(tag) == 1) {
                return parseDemuxRsp(cis.readByteArray())
            } else {
                cis.skipField(tag)
            }
        }
        return null
    }

    private fun parseDemuxRsp(bytes: ByteArray): DemuxRsp {
        val cis = CodedInputStream.newInstance(bytes)
        var reqId = 0
        var success = false
        var expired = false
        var connId = 0
        var openSuccess = false
        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            if (tag == 0) break
            when (WireFormat.getTagFieldNumber(tag)) {
                1 -> reqId = cis.readUInt32()
                2 -> {
                    val authCis = CodedInputStream.newInstance(cis.readByteArray())
                    while (!authCis.isAtEnd) {
                        val aTag = authCis.readTag()
                        if (aTag == 0) break
                        when (WireFormat.getTagFieldNumber(aTag)) {
                            1 -> success = authCis.readBool()
                            2 -> expired = authCis.readBool()
                            else -> authCis.skipField(aTag)
                        }
                    }
                }
                3 -> {
                    val openCis = CodedInputStream.newInstance(cis.readByteArray())
                    while (!openCis.isAtEnd) {
                        val oTag = openCis.readTag()
                        if (oTag == 0) break
                        when (WireFormat.getTagFieldNumber(oTag)) {
                            1 -> connId = openCis.readUInt32()
                            2 -> openSuccess = openCis.readBool()
                            else -> openCis.skipField(oTag)
                        }
                    }
                }
                else -> cis.skipField(tag)
            }
        }
        return DemuxRsp(reqId, success, expired, connId, openSuccess)
    }

    private fun parseDemuxDownstreamData(bytes: ByteArray, expectedConnectionId: Int): ByteArray? {
        val cis = CodedInputStream.newInstance(bytes)
        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            if (tag == 0) break
            if (WireFormat.getTagFieldNumber(tag) == 2) {
                val pushCis = CodedInputStream.newInstance(cis.readByteArray())
                while (!pushCis.isAtEnd) {
                    val pTag = pushCis.readTag()
                    if (pTag == 0) break
                    if (WireFormat.getTagFieldNumber(pTag) == 1) {
                        val dataCis = CodedInputStream.newInstance(pushCis.readByteArray())
                        var connId = -1
                        var payload: ByteArray? = null
                        while (!dataCis.isAtEnd) {
                            val dTag = dataCis.readTag()
                            if (dTag == 0) break
                            when (WireFormat.getTagFieldNumber(dTag)) {
                                1 -> connId = dataCis.readUInt32()
                                2 -> payload = dataCis.readByteArray()
                                else -> dataCis.skipField(dTag)
                            }
                        }
                        if (connId == expectedConnectionId && payload != null) return payload
                    } else {
                        pushCis.skipField(pTag)
                    }
                }
            } else {
                cis.skipField(tag)
            }
        }
        return null
    }

    internal fun parseOwnershipDownstream(bytes: ByteArray): List<UbisoftOwnedProduct> {
        val cis = CodedInputStream.newInstance(bytes)
        val products = mutableListOf<UbisoftOwnedProduct>()
        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            if (tag == 0) break
            if (WireFormat.getTagFieldNumber(tag) == 1) {
                val rspCis = CodedInputStream.newInstance(cis.readByteArray())
                while (!rspCis.isAtEnd) {
                    val rTag = rspCis.readTag()
                    if (rTag == 0) break
                    if (WireFormat.getTagFieldNumber(rTag) == 2) {
                        parseInitializeRsp(rspCis.readByteArray(), products)
                    } else {
                        rspCis.skipField(rTag)
                    }
                }
            } else {
                cis.skipField(tag)
            }
        }
        return products
    }

    private fun parseInitializeRsp(bytes: ByteArray, out: MutableList<UbisoftOwnedProduct>) {
        val cis = CodedInputStream.newInstance(bytes)
        var success = false
        val collected = mutableListOf<UbisoftOwnedProduct>()
        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            if (tag == 0) break
            when (WireFormat.getTagFieldNumber(tag)) {
                1 -> success = cis.readBool()
                2 -> parseOwnedGames(cis.readByteArray(), collected)
                else -> cis.skipField(tag)
            }
        }
        if (!success && collected.isEmpty()) {
            AppLogs.error("UBISOFT", "Demux InitializeRsp reported success=false")
        }
        out.addAll(collected)
    }

    private fun parseOwnedGames(bytes: ByteArray, out: MutableList<UbisoftOwnedProduct>) {
        val cis = CodedInputStream.newInstance(bytes)
        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            if (tag == 0) break
            if (WireFormat.getTagFieldNumber(tag) == 1) {
                parseOwnedGame(cis.readByteArray())?.let(out::add)
            } else {
                cis.skipField(tag)
            }
        }
    }

    private fun parseOwnedGame(bytes: ByteArray): UbisoftOwnedProduct? {
        val cis = CodedInputStream.newInstance(bytes)
        var productId = 0
        var owned: Boolean? = null
        var productType = 0
        var configuration = ""
        var spaceId = ""
        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            if (tag == 0) break
            when (WireFormat.getTagFieldNumber(tag)) {
                1 -> productId = cis.readUInt32()
                8 -> productType = cis.readUInt32()
                17 -> configuration = cis.readString()
                20 -> owned = cis.readBool()
                30 -> spaceId = cis.readString()
                else -> cis.skipField(tag)
            }
        }
        // Only include owned full games (ProductType_Game=0, ProductType_PreOrderGame=2).
        if (productId <= 0) return null
        if (owned == false) return null
        if (productType != 0 && productType != 2) return null
        val name = extractNameFromConfig(configuration) ?: return null
        return UbisoftOwnedProduct(productId, spaceId.trim(), name)
    }

    internal fun extractNameFromConfig(yaml: String): String? {
        if (yaml.isBlank()) return null
        var rootName: String? = null
        var inRoot = false
        var inLocalizations = false
        var inDefaultLoc = false
        val defaultLoc = mutableMapOf<String, String>()

        for (rawLine in yaml.lineSequence()) {
            if (rawLine.isBlank() || rawLine.trimStart().startsWith('#')) continue
            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) rawLine.length else it }
            val trimmed = rawLine.trim()
            if (indent == 0) {
                inRoot = trimmed.startsWith("root:")
                inLocalizations = trimmed.startsWith("localizations:")
                inDefaultLoc = false
                continue
            }
            if (inRoot && indent == 2) {
                if (trimmed.startsWith("name:")) {
                    rootName = unquoteYaml(trimmed.removePrefix("name:").trim())
                }
                continue
            }
            if (inLocalizations) {
                if (indent == 2) {
                    inDefaultLoc = trimmed.startsWith("default:")
                } else if (inDefaultLoc && indent >= 4) {
                    val colon = trimmed.indexOf(':')
                    if (colon > 0) {
                        val key = trimmed.substring(0, colon).trim()
                        val value = unquoteYaml(trimmed.substring(colon + 1).trim())
                        if (key.isNotEmpty() && value.isNotEmpty()) defaultLoc[key] = value
                    }
                }
            }
        }

        val candidate = rootName?.takeIf { it.isNotBlank() } ?: defaultLoc["GAMENAME"] ?: defaultLoc["NAME"]
        if (candidate.isNullOrBlank()) return null
        val resolved = defaultLoc[candidate] ?: candidate
        return resolved.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun unquoteYaml(value: String): String {
        if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\'')))) {
            return value.substring(1, value.length - 1).replace("\\\"", "\"")
        }
        return value
    }
}
