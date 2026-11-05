package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class LomFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val a = "A".repeat(86)
    private val b = "B".repeat(86)
    private val id = "file-0001-aaaaaaaaaaaa"
    private val saved = mutableListOf<Triple<Long, String, String>>()
    private var uri = "content://media/external/images/media/7"
    private fun files(quota: Long = LomFileMethods.QUOTA) =
        LomFileMethods(tmp.root, { f, name, mime -> saved += Triple(f.length(), name, mime); uri }, quota)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun call(m: LomFileMethods, method: String, vararg p: Pair<String, Any?>, sender: String = a) =
        Json.parseToJsonElement(m.call(method, LomPayload.obj(*p), sender)!!).jsonObject
    private fun code(m: LomFileMethods, method: String, vararg p: Pair<String, Any?>, sender: String = a) =
        try { m.call(method, LomPayload.obj(*p), sender); "" } catch (e: LomException) { e.code }
    private fun begin(m: LomFileMethods, data: ByteArray, name: String = "a.png", mime: String = "image/png", fid: String = id, sender: String = a) =
        call(m, "file.begin", "id" to fid, "name" to name, "mime" to mime, "size" to data.size.toString(), "sha256" to sha(data), sender = sender)

    private fun upload(m: LomFileMethods, data: ByteArray, fid: String = id, sender: String = a) {
        begin(m, data, fid = fid, sender = sender)
        var off = 0
        while (off < data.size) {
            val part = data.copyOfRange(off, minOf(off + LomFileMethods.CHUNK, data.size))
            val st = call(m, "file.chunk", "id" to fid, "offset" to off.toString(), "data" to b64(part), sender = sender)
            off += part.size
            assertEquals(off.toString(), st["offset"]!!.jsonPrimitive.content)
        }
        assertEquals("complete", call(m, "file.finish", "id" to fid, sender = sender)["status"]!!.jsonPrimitive.content)
    }

    @Test fun `upload then read back bit for bit across several chunks`() {
        val m = files(); val data = ByteArray(300_000) { (it * 31).toByte() }
        upload(m, data)
        val out = java.io.ByteArrayOutputStream(); var off = 0L
        while (off < data.size) {
            val r = call(m, "file.read", "id" to id, "offset" to off.toString())
            val chunk = Base64.getDecoder().decode(r["data"]!!.jsonPrimitive.content)
            assertTrue(chunk.size in 1..LomFileMethods.CHUNK)
            assertEquals((off + chunk.size).toString(), r["next"]!!.jsonPrimitive.content) // next == offset + 本片长度
            out.write(chunk); off += chunk.size
        }
        assertTrue(data.contentEquals(out.toByteArray()))
    }

    @Test fun `begin reports state and resumes with the same metadata`() {
        val m = files(); val data = ByteArray(200_000) { 5 }
        val s = begin(m, data)
        assertEquals("uploading", s["status"]!!.jsonPrimitive.content); assertEquals("0", s["offset"]!!.jsonPrimitive.content)
        call(m, "file.chunk", "id" to id, "offset" to "0", "data" to b64(data.copyOf(LomFileMethods.CHUNK)))
        val again = begin(m, data) // 断点续传:返回已收位置
        assertEquals(LomFileMethods.CHUNK.toString(), again["offset"]!!.jsonPrimitive.content)
        assertEquals("file.id_reused", code(m, "file.begin", "id" to id, "name" to "other.png", "mime" to "image/png", "size" to data.size.toString(), "sha256" to sha(data)))
    }

    @Test fun `begin validation`() {
        val m = files(); val sh = sha(ByteArray(3))
        fun bad(vararg p: Pair<String, Any?>) = code(m, "file.begin", *p)
        val ok = arrayOf("id" to id, "name" to "a.bin", "mime" to "x/y", "size" to "3", "sha256" to sh)
        fun with(k: String, v: Any?) = ok.map { if (it.first == k) k to v else it }.toTypedArray()
        assertEquals("file.invalid_id", bad(*with("id", "short")))
        assertEquals("file.invalid_id", bad(*with("id", "../../../../etc/passwd-aaaaa")))
        for (n in listOf("", ".", "..", "a/b", "a\\b", "a\u0000b", "x".repeat(201))) assertEquals("name=$n", "file.invalid_name", bad(*with("name", n)))
        assertEquals("file.invalid_mime", bad(*with("mime", "m".repeat(201))))
        assertEquals("file.invalid_size_or_hash", bad(*with("size", "104857601")))
        assertEquals("file.invalid_size_or_hash", bad(*with("size", "-1")))
        assertEquals("file.invalid_size_or_hash", bad(*with("size", "3.0")))
        assertEquals("file.invalid_size_or_hash", bad(*with("size", 3)))
        assertEquals("file.invalid_size_or_hash", bad(*with("sha256", sh.uppercase())))
        assertEquals("file.invalid_size_or_hash", bad(*with("sha256", "abc")))
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test fun `chunk rules`() {
        val m = files(); val data = ByteArray(10) { it.toByte() }; begin(m, data)
        assertEquals("file.missing", code(m, "file.chunk", "id" to "nofile-nofile-nofile", "offset" to "0", "data" to b64(data)))
        assertEquals("file.offset_mismatch", code(m, "file.chunk", "id" to id, "offset" to "5", "data" to b64(data)))
        assertEquals("file.invalid_chunk", code(m, "file.chunk", "id" to id, "offset" to "0", "data" to ""))
        assertEquals("file.invalid_chunk", code(m, "file.chunk", "id" to id, "offset" to "0", "data" to "!!!!"))
        // 超过声明的总长度
        assertEquals("file.invalid_chunk", code(m, "file.chunk", "id" to id, "offset" to "0", "data" to b64(ByteArray(11))))
        assertEquals("file.invalid_chunk", code(m, "file.chunk", "id" to id, "offset" to "0", "data" to b64(ByteArray(10)).trimEnd('=')))  // 缺填充
        call(m, "file.chunk", "id" to id, "offset" to "0", "data" to b64(data.copyOf(4)))
        call(m, "file.chunk", "id" to id, "offset" to "0", "data" to b64(data.copyOf(4))) // 重发相同内容:幂等
        assertEquals("file.chunk_conflict", code(m, "file.chunk", "id" to id, "offset" to "0", "data" to b64(byteArrayOf(9, 9, 9, 9))))
        assertEquals("file.chunk_conflict", code(m, "file.chunk", "id" to id, "offset" to "2", "data" to b64(data.copyOfRange(2, 8)))) // 越出已收范围
    }

    @Test fun `oversized chunk is rejected`() {
        val m = files(); val data = ByteArray(200_000); begin(m, data)
        assertEquals("file.invalid_chunk", code(m, "file.chunk", "id" to id, "offset" to "0", "data" to b64(ByteArray(LomFileMethods.CHUNK + 1))))
        call(m, "file.chunk", "id" to id, "offset" to "0", "data" to b64(ByteArray(LomFileMethods.CHUNK)))
    }

    @Test fun `finish verifies length and sha256 and read needs complete`() {
        val m = files(); val data = "hello".toByteArray()
        begin(m, data)
        assertEquals("file.integrity_failed", code(m, "file.finish", "id" to id)) // 还没传完
        assertEquals("file.incomplete", code(m, "file.read", "id" to id, "offset" to "0"))
        call(m, "file.chunk", "id" to id, "offset" to "0", "data" to b64("HELLO".toByteArray())) // 内容不对
        assertEquals("file.integrity_failed", code(m, "file.finish", "id" to id))
        assertEquals("file.incomplete", code(m, "file.read", "id" to id, "offset" to "0"))
    }

    @Test fun `read offset bounds`() {
        val m = files(); upload(m, ByteArray(10))
        assertEquals("file.invalid_offset", code(m, "file.read", "id" to id, "offset" to "10"))
        assertEquals("file.invalid_offset", code(m, "file.read", "id" to id, "offset" to "x"))
        assertEquals("file.missing", code(m, "file.read", "id" to "nofile-nofile-nofile", "offset" to "0"))
    }

    @Test fun `files are isolated per desktop`() {
        val m = files(); upload(m, ByteArray(10))
        assertEquals("file.missing", code(m, "file.read", "id" to id, "offset" to "0", sender = b))
        assertEquals(0, call(m, "file.list", sender = b)["items"].let { Json.parseToJsonElement(it!!.jsonPrimitive.content).jsonArray.size })
        assertEquals(1, Json.parseToJsonElement(call(m, "file.list")["items"]!!.jsonPrimitive.content).jsonArray.size)
    }

    @Test fun `list is a json text of FileState`() {
        val m = files(); upload(m, ByteArray(10)); 
        val items = Json.parseToJsonElement(call(m, "file.list")["items"]!!.jsonPrimitive.content).jsonArray
        val s = items.single().jsonObject
        assertEquals(setOf("id", "name", "mime", "size", "sha256", "offset", "status"), s.keys)
        assertEquals("complete", s["status"]!!.jsonPrimitive.content); assertEquals("10", s["size"]!!.jsonPrimitive.content)
    }

    @Test fun `delete removes data and is idempotent`() {
        val m = files(); upload(m, ByteArray(10))
        assertEquals("deleted", call(m, "file.delete", "id" to id)["status"]!!.jsonPrimitive.content)
        assertEquals("deleted", call(m, "file.delete", "id" to id)["status"]!!.jsonPrimitive.content)
        assertEquals("file.missing", code(m, "file.read", "id" to id, "offset" to "0"))
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test fun `quota counts declared sizes`() {
        val m = files(quota = 100)
        begin(m, ByteArray(60), fid = "file-0001-aaaaaaaaaaaa")
        assertEquals("file.quota_exceeded", code(m, "file.begin", "id" to "file-0002-aaaaaaaaaaaa", "name" to "b", "mime" to "", "size" to "60", "sha256" to sha(ByteArray(60))))
        call(m, "file.delete", "id" to "file-0001-aaaaaaaaaaaa")
        begin(m, ByteArray(60), fid = "file-0002-aaaaaaaaaaaa") // 删掉后腾出空间
    }

    @Test fun `desktop supplied names never reach the disk layout`() {
        val m = files(); begin(m, ByteArray(3), name = "..evil.sh")
        val names = tmp.root.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertTrue(names.none { it.contains("evil") })
        assertTrue(tmp.root.walkTopDown().all { it.canonicalPath.startsWith(tmp.root.canonicalPath) })
    }

    @Test fun `save_gallery verifies and returns a media uri`() {
        val m = files(); val data = ByteArray(2000) { 3 }
        upload(m, data)
        val r = call(m, "file.save_gallery", "id" to id)
        assertEquals("saved", r["status"]!!.jsonPrimitive.content); assertEquals(id, r["id"]!!.jsonPrimitive.content)
        assertTrue(r["uri"]!!.jsonPrimitive.content.startsWith("content://media/"))
        assertEquals(Triple(2000L, "a.png", "image/png"), saved.single())
    }

    @Test fun `save_gallery refuses unfinished corrupt or non-image files`() {
        val m = files(); begin(m, ByteArray(5))
        assertEquals("file.incomplete_or_corrupt", code(m, "file.save_gallery", "id" to id))
        upload(m, ByteArray(5), fid = "file-0009-aaaaaaaaaaaa")
        // 磁盘上的文件被改坏:再校验一遍,不存坏文件
        File(tmp.root, LomSender.key(a)).listFiles { f -> f.name.endsWith(".bin") }!!.single().writeBytes(ByteArray(5) { 9 })
        assertEquals("file.incomplete_or_corrupt", code(m, "file.save_gallery", "id" to "file-0009-aaaaaaaaaaaa"))
        upload(m, ByteArray(5), fid = "file-0010-aaaaaaaaaaaa").also { }
        assertTrue(saved.isEmpty())
    }

    @Test fun `save_gallery rejects non-image mime and a bad uri from the platform`() {
        val m = files(); val d = ByteArray(5)
        begin(m, d, mime = "application/pdf"); call(m, "file.chunk", "id" to id, "offset" to "0", "data" to b64(d)); call(m, "file.finish", "id" to id)
        assertEquals("file.invalid_mime", code(m, "file.save_gallery", "id" to id))
        val m2 = files(); upload(m2, d, fid = "file-0011-aaaaaaaaaaaa"); uri = "file:///sdcard/x.png"
        assertEquals("file.storage_failed", code(m2, "file.save_gallery", "id" to "file-0011-aaaaaaaaaaaa"))
    }

    @Test fun `file_status works and other methods are not claimed`() {
        val m = files(); begin(m, ByteArray(3))
        assertEquals("uploading", call(m, "file.status", "id" to id)["status"]!!.jsonPrimitive.content)
        assertNull(m.call("skills.list", "{}", a)); assertNull(m.call("file.nope", "{}", a))
    }
}
