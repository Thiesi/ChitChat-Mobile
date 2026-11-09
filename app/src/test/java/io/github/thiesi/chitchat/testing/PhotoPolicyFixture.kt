package io.github.thiesi.chitchat.testing

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/** Structural PNG for real preparation/state tests, not new codec or pixel acceptance. */
internal fun pngWithPrivateText(): ByteArray {
    fun chunk(type: String, bytes: ByteString): ByteString {
        val crc = CRC32().apply { update(type.encodeUtf8().toByteArray()); update(bytes.toByteArray()) }
        return Buffer().writeInt(bytes.size).writeUtf8(type).write(bytes).writeInt(crc.value.toInt()).readByteString()
    }
    val header = Buffer().writeInt(1).writeInt(1).writeByte(8).writeByte(6)
        .writeByte(0).writeByte(0).writeByte(0).readByteString()
    val compressed = ByteArrayOutputStream().also { out ->
        DeflaterOutputStream(out).use { it.write(byteArrayOf(0, -1, 0, 0, -1)) }
    }.toByteArray().toByteString()
    return Buffer().write(byteArrayOf(-119,80,78,71,13,10,26,10))
        .write(chunk("IHDR", header)).write(chunk("tEXt", ("Comment\u0000" + "x".repeat(512)).encodeUtf8()))
        .write(chunk("IDAT", compressed)).write(chunk("IEND", ByteString.EMPTY)).readByteArray()
}
