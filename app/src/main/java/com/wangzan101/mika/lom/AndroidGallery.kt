package com.wangzan101.mika.lom

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.wangzan101.mika.core.lom.LomException
import com.wangzan101.mika.core.lom.LomGallery
import java.io.File
import java.io.IOException

/** 把桌面传来的图片存进系统相册(Pictures/Mika);安卓 10 起不需要存储权限。写不进去就抛错,不假装保存。 */
class AndroidGallery(private val context: Context) : LomGallery {
    override fun save(file: File, name: String, mime: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mika")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw LomException("file.storage_failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw LomException("file.storage_failed")
        }
        return uri.toString()
    }
}
