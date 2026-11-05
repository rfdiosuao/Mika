package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** 把已校验的图片存进系统相册,返回 `content://media/…`。 */
fun interface LomGallery {
    fun save(file: File, name: String, mime: String): String
}

/**
 * `file.*`(公开文档 07 §7):桌面把文件分片传到手机的暂存区。
 * 按发送方桌面隔离,只认经本通道登记的 id,**绝不接受任意路径**(磁盘上的文件名是 id 的哈希,不含桌面给的名字);
 * 单文件 ≤ 100 MiB,分片 ≤ 128 KiB,暂存总量 ≤ 500 MiB。内容只当数据存放,不解释、不执行。
 */
class LomFileMethods(
    private val root: File,
    private val gallery: LomGallery? = null,
    private val quota: Long = QUOTA,
) : LomMethods {
    private class Meta(val id: String, val name: String, val mime: String, val size: Long, val sha256: String, val complete: Boolean)

    override val methods = setOf("file.begin", "file.chunk", "file.finish", "file.status", "file.read", "file.list", "file.delete", "file.save_gallery")

    override fun call(method: String, payload: String, sender: String): String? {
        if (method !in methods) return null
        val dir = File(root, LomSender.key(sender))
        val o = LomPayload.parse(payload, "file.invalid_id")
        return synchronized(this) {
            when (method) {
                "file.begin" -> begin(dir, o)
                "file.chunk" -> chunk(dir, o)
                "file.finish" -> finish(dir, o)
                "file.status" -> state(dir, meta(dir, id(o)))
                "file.read" -> read(dir, o)
                "file.list" -> list(dir)
                "file.delete" -> delete(dir, o)
                "file.save_gallery" -> saveGallery(dir, o)
                else -> null
            }
        }
    }

    private fun id(o: JsonObject): String =
        LomPayload.str(o, "id")?.takeIf { ID.matches(it) } ?: throw LomException("file.invalid_id")

    private fun begin(dir: File, o: JsonObject): String {
        val id = id(o)
        val name = LomPayload.str(o, "name").orEmpty()
        if (name.isEmpty() || name.length > 200 || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.isISOControl() }) throw LomException("file.invalid_name")
        val mime = LomPayload.str(o, "mime").orEmpty()
        if (mime.length > 200 || mime.any { it.isISOControl() }) throw LomException("file.invalid_mime")
        val sizeText = LomPayload.str(o, "size").orEmpty()
        val sha = LomPayload.str(o, "sha256").orEmpty()
        val size = sizeText.takeIf { it.isNotEmpty() && it.length <= 9 && it.all { c -> c in '0'..'9' } }?.toLongOrNull()?.takeIf { it <= MAX_FILE }
        if (size == null || !SHA.matches(sha)) throw LomException("file.invalid_size_or_hash")
        existing(dir, id)?.let { m ->
            if (m.name != name || m.mime != mime || m.size != size || m.sha256 != sha) throw LomException("file.id_reused")
            return state(dir, m) // 同一份元数据再 begin:返回当前进度(续传)
        }
        if (used(dir) + size > quota) throw LomException("file.quota_exceeded")
        val m = Meta(id, name, mime, size, sha, false)
        try {
            dir.mkdirs()
            data(dir, id, false).writeBytes(ByteArray(0))
            writeMeta(dir, m)
        } catch (_: IOException) { throw LomException("file.storage_failed") }
        return state(dir, m)
    }

    private fun chunk(dir: File, o: JsonObject): String {
        val m = meta(dir, id(o))
        val offset = number(LomPayload.str(o, "offset")) ?: throw LomException("file.invalid_chunk")
        val raw = LomPayload.str(o, "data").orEmpty()
        if (raw.length > MAX_B64 || raw.length % 4 != 0 || !B64.matches(raw)) throw LomException("file.invalid_chunk")
        val bytes = try { java.util.Base64.getDecoder().decode(raw) } catch (_: IllegalArgumentException) { throw LomException("file.invalid_chunk") }
        if (bytes.isEmpty() || bytes.size > CHUNK) throw LomException("file.invalid_chunk")
        val file = data(dir, m.id, m.complete)
        val have = file.length()
        try {
            if (offset > have) throw LomException("file.offset_mismatch")
            if (offset < have) {
                // 重发:必须完整落在已收范围内且逐字节相同(幂等),否则冲突
                if (offset + bytes.size > have) throw LomException("file.chunk_conflict")
                val old = ByteArray(bytes.size)
                RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(old) }
                if (!old.contentEquals(bytes)) throw LomException("file.chunk_conflict")
            } else {
                if (m.complete || have + bytes.size > m.size) throw LomException("file.invalid_chunk")
                RandomAccessFile(file, "rw").use { it.seek(have); it.write(bytes) }
            }
        } catch (_: IOException) { throw LomException("file.storage_failed") }
        return state(dir, m)
    }

    private fun finish(dir: File, o: JsonObject): String {
        val m = meta(dir, id(o))
        if (m.complete) return state(dir, m)
        val part = data(dir, m.id, false)
        if (part.length() != m.size || sha256(part) != m.sha256) throw LomException("file.integrity_failed")
        try {
            val done = data(dir, m.id, true)
            if (!part.renameTo(done)) throw IOException("rename")
            val c = Meta(m.id, m.name, m.mime, m.size, m.sha256, true)
            writeMeta(dir, c)
            return state(dir, c)
        } catch (_: IOException) { throw LomException("file.storage_failed") }
    }

    private fun read(dir: File, o: JsonObject): String {
        val m = meta(dir, id(o))
        if (!m.complete) throw LomException("file.incomplete")
        val offset = number(LomPayload.str(o, "offset")) ?: throw LomException("file.invalid_offset")
        if (offset >= m.size) throw LomException("file.invalid_offset")
        val n = minOf(CHUNK.toLong(), m.size - offset).toInt()
        val buf = ByteArray(n)
        try { RandomAccessFile(data(dir, m.id, true), "r").use { it.seek(offset); it.readFully(buf) } }
        catch (_: IOException) { throw LomException("file.storage_failed") }
        return LomPayload.obj("data" to java.util.Base64.getEncoder().encodeToString(buf), "next" to (offset + n).toString())
    }

    private fun list(dir: File): String {
        val items = dir.listFiles { f -> f.name.endsWith(".meta") }.orEmpty().mapNotNull { readMeta(it) }.sortedBy { it.id }
        return LomPayload.obj("items" to JsonArray(items.map { stateObj(dir, it) }).toString())
    }

    private fun delete(dir: File, o: JsonObject): String {
        val id = id(o)
        val files = listOf(data(dir, id, false), data(dir, id, true), metaFile(dir, id))
        if (files.any { it.exists() && !it.delete() }) throw LomException("file.delete_failed")
        return LomPayload.obj("status" to "deleted")
    }

    private fun saveGallery(dir: File, o: JsonObject): String {
        val m = meta(dir, id(o))
        val file = data(dir, m.id, true)
        if (!m.complete || !file.isFile || file.length() != m.size || sha256(file) != m.sha256) throw LomException("file.incomplete_or_corrupt")
        if (!m.mime.startsWith("image/")) throw LomException("file.invalid_mime")
        val saver = gallery ?: throw LomException("file.storage_failed")
        val uri = try { saver.save(file, m.name, m.mime) } catch (_: LomException) { throw LomException("file.storage_failed") } catch (_: IOException) { throw LomException("file.storage_failed") }
        if (!uri.startsWith("content://media/")) throw LomException("file.storage_failed")
        return LomPayload.obj("id" to m.id, "status" to "saved", "uri" to uri)
    }

    // ---- 存储 ----

    private fun state(dir: File, m: Meta) = stateObj(dir, m).toString()

    private fun stateObj(dir: File, m: Meta): JsonObject = LomPayload.jobj(
        "id" to m.id, "name" to m.name, "mime" to m.mime, "size" to m.size.toString(), "sha256" to m.sha256,
        "offset" to (if (m.complete) m.size else data(dir, m.id, false).length()).toString(), "status" to if (m.complete) "complete" else "uploading",
    )

    private fun meta(dir: File, id: String) = existing(dir, id) ?: throw LomException("file.missing")
    private fun existing(dir: File, id: String): Meta? = metaFile(dir, id).takeIf { it.isFile }?.let(::readMeta)
    private fun used(dir: File) = dir.listFiles { f -> f.name.endsWith(".meta") }.orEmpty().mapNotNull { readMeta(it)?.size }.sum()

    // 磁盘上的名字只由 id 的 SHA-256 决定,桌面给的文件名只存在元数据里
    private fun stem(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private fun data(dir: File, id: String, complete: Boolean) = File(dir, stem(id) + if (complete) ".bin" else ".part")
    private fun metaFile(dir: File, id: String) = File(dir, stem(id) + ".meta")

    private fun writeMeta(dir: File, m: Meta) {
        val json = LomPayload.obj("id" to m.id, "name" to m.name, "mime" to m.mime, "size" to m.size, "sha256" to m.sha256, "complete" to m.complete)
        val tmp = File(dir, stem(m.id) + ".meta.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(metaFile(dir, m.id))) { metaFile(dir, m.id).delete(); if (!tmp.renameTo(metaFile(dir, m.id))) throw IOException("rename") }
    }

    private fun readMeta(f: File): Meta? = try {
        val o = Json.parseToJsonElement(f.readText()).jsonObject
        Meta(
            LomPayload.str(o, "id")!!, LomPayload.str(o, "name")!!, LomPayload.str(o, "mime").orEmpty(),
            LomPayload.long(o, "size")!!, LomPayload.str(o, "sha256")!!, LomPayload.bool(o, "complete") == true,
        )
    } catch (_: Exception) { null }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun number(s: String?): Long? = s?.takeIf { it.isNotEmpty() && it.length <= 12 && it.all { c -> c in '0'..'9' } }?.toLongOrNull()

    companion object {
        val ID = Regex("^[A-Za-z0-9-]{16,64}$")
        val SHA = Regex("^[0-9a-f]{64}$")
        private val B64 = Regex("^[A-Za-z0-9+/]*={0,2}$")
        const val MAX_FILE = 104_857_600L
        const val CHUNK = 131_072
        private const val MAX_B64 = 4 * ((CHUNK + 2) / 3)
        const val QUOTA = 500L * 1024 * 1024
    }
}
