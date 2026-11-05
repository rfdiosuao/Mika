package com.wangzan101.mika.core.lom

import java.io.File
import java.io.IOException

/**
 * 信封去重表。键是 `sender + ":" + id`;登记发生在执行之前,所以一个登记过的信封永远不会执行第二次。
 * 必须持久化(重启后仍有效)并对并发安全:两个相同键同时到达只有一个返回 true。
 */
interface LomReplayStore {
    /** 键不存在则登记并返回 true;已存在返回 false。登记不上(磁盘写失败)抛 `remote.operation_failed`,宁可拒绝也不放行。 */
    fun registerIfNew(key: String, nowMs: Long): Boolean
}

class MemoryReplayStore : LomReplayStore {
    private val seen = HashMap<String, Long>()
    @Synchronized override fun registerIfNew(key: String, nowMs: Long): Boolean {
        seen.entries.removeAll { nowMs - it.value > LomReplay.RETENTION_MS }
        return seen.putIfAbsent(key, nowMs) == null
    }
}

/** 追加写的文本文件,每行 `<毫秒> <键>`;启动时读入并剔除过期项,条目过多时重写压缩。 */
class FileReplayStore(private val file: File) : LomReplayStore {
    private val seen = HashMap<String, Long>()
    private var loaded = false
    private var lines = 0

    @Synchronized override fun registerIfNew(key: String, nowMs: Long): Boolean {
        if (!loaded) load(nowMs)
        val old = seen[key]
        if (old != null && nowMs - old <= LomReplay.RETENTION_MS) return false
        try {
            file.parentFile?.mkdirs()
            file.appendText("$nowMs $key\n")
        } catch (_: IOException) {
            throw LomException("remote.operation_failed")
        }
        seen[key] = nowMs
        if (++lines > 2000) compact(nowMs)
        return true
    }

    private fun load(nowMs: Long) {
        loaded = true
        if (!file.isFile) return
        try {
            file.forEachLine { line ->
                val i = line.indexOf(' ')
                val t = if (i > 0) line.substring(0, i).toLongOrNull() else null
                if (t != null && nowMs - t <= LomReplay.RETENTION_MS) { seen[line.substring(i + 1)] = t; lines++ }
            }
        } catch (_: IOException) {
            throw LomException("remote.operation_failed")
        }
    }

    private fun compact(nowMs: Long) {
        seen.entries.removeAll { nowMs - it.value > LomReplay.RETENTION_MS }
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(seen.entries.joinToString("") { "${it.value} ${it.key}\n" })
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            lines = seen.size
        } catch (_: IOException) {
            // 压缩失败不影响正确性:原文件仍有全部登记项
        }
    }
}

object LomReplay {
    /** 时间窗口前后各 5 分钟,所以一个信封最多有 10 分钟可能被重放;留 15 分钟余量。 */
    const val RETENTION_MS = 15 * 60_000L
}
