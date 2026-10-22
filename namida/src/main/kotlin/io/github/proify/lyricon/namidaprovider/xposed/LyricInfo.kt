/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.namidaprovider.xposed

import org.json.JSONObject

/**
 * Namida 附加在播放会话 MediaMetadata 上的 ColorOS 格式歌词 JSON.
 *
 * 对应 namida/lib/controller/lyrics_integrations.dart 的 `_LyricInfoLanes.toJsonString`:
 * `{ songName, artist, album, songId, lyricType, lyric, rawLyric, noLyric }`.
 */
internal object LyricInfo {

    data class Payload(
        val songId: String?,
        val name: String?,
        val artist: String?,
        /** 携带逐字时间轴的增强 LRC; 不可用时退回普通 LRC. */
        val rawLyric: String?,
    )

    fun parse(json: String?): Payload? {
        if (json.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null

        // noLyric 为 true 时表示该曲目没有歌词, 此时不再回退到 lyric 字段.
        val lyric = if (obj.optBoolean("noLyric", false)) {
            null
        } else {
            obj.optText("rawLyric") ?: obj.optText("lyric")
        }

        return Payload(
            songId = obj.optText("songId"),
            name = obj.optText("songName"),
            artist = obj.optText("artist"),
            rawLyric = lyric,
        )
    }

    /** 读取字符串字段, 忽略 JSON null 与空白值. */
    private fun JSONObject.optText(key: String): String? {
        val value = opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        return value.toString().trim().takeIf { it.isNotEmpty() }
    }
}
