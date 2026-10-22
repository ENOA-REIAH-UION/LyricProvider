/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.namidaprovider.xposed

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Namida 的 Lyricon 适配.
 *
 * 歌词按顺序取第一个可用的来源, 均来自 Namida 自己 (不联网匹配):
 * 同名 .lrc/.txt -> 内嵌标签 -> Namida 歌词缓存 -> 播放会话里的 lyricInfo.
 */
object Namida : YukiBaseHooker() {

    private const val TAG = Constants.LOG_TAG

    private const val EXTRA_LYRIC_INFO = "lyricInfo"
    private const val EXTRA_PATH = "path"

    private const val LYRICS_DIR_NAME = "Lyrics"
    private const val CACHE_SEARCH_MAX_DEPTH = 3

    /** 播放状态兜底刷新间隔: 位置由宿主按 position + speed 外推. */
    private const val PLAYBACK_REFRESH_MS = 3000L

    /** 位置跳变超过该值视为 seek. */
    private const val SEEK_THRESHOLD_MS = 1000L

    /** 合并译文时允许的时间戳误差. */
    private const val TRANSLATION_TOLERANCE_MS = 100L

    private var provider: LyriconProvider? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 上次推送的歌曲标识 (含歌词行数), 用于去重. */
    private var lastPushKey: String? = null

    /** 已推送过歌词的歌曲标识. */
    private var lastLyricsKey: String? = null

    /** 当前歌曲标识; 异步取词返回时用于判断是否已切歌. */
    @Volatile
    private var currentSongKey: String? = null

    /** 正在取词的歌曲标识; 避免同一首歌并发取词. */
    @Volatile
    private var loadingSongKey: String? = null

    /** 已确认没有歌词的歌曲标识, 避免重复取词. */
    @Volatile
    private var lastNoLyricsKey: String? = null

    /** 已定位到的歌词缓存目录; 找到后不再重复扫描. */
    @Volatile
    private var lyricsCacheDir: File? = null

    private var lastPlaybackState: Int = Int.MIN_VALUE
    private var lastPlaybackSpeed: Float = Float.NaN
    private var lastPlaybackPosition: Long = Long.MIN_VALUE
    private var lastPlaybackForwardAt: Long = 0L

    override fun onHook() {
        YLog.info(tag = TAG, msg = "starting provider")

        onAppLifecycle {
            onCreate { initProvider(this) }
            onTerminate {
                safeProvider { it.unregister() }
                provider = null
                scope.cancel()
            }
        }

        hookMediaSession()
    }

    private fun initProvider(context: Context) {
        try {
            provider = LyriconFactory.createProvider(
                context = context,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = context.packageName,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            ).apply {
                player.setDisplayTranslation(true)
                register()
            }
        } catch (t: Throwable) {
            YLog.error(tag = TAG, msg = "register provider failed", e = t)
        }
    }

    /** 对词幕的调用不得把异常抛回宿主. */
    private inline fun safeProvider(block: (LyriconProvider) -> Unit) {
        try {
            provider?.let(block)
        } catch (t: Throwable) {
            YLog.error(tag = TAG, msg = "provider call failed", e = t)
        }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    forwardPlaybackState(args[0] as? PlaybackState)
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters(MediaMetadata::class.java)
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after
                    handleMetadata(metadata)
                }
            }
        }
    }

    /** 只在状态/速度变化、seek 或周期性校准时转发, 避免 binder 事务被打满. */
    private fun forwardPlaybackState(playbackState: PlaybackState?) {
        val state = playbackState ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            val unchanged =
                state.state == lastPlaybackState && state.playbackSpeed == lastPlaybackSpeed
            val seek = abs(state.position - lastPlaybackPosition) > SEEK_THRESHOLD_MS
            if (unchanged && !seek && now - lastPlaybackForwardAt < PLAYBACK_REFRESH_MS) return

            lastPlaybackState = state.state
            lastPlaybackSpeed = state.playbackSpeed
            lastPlaybackPosition = state.position
            lastPlaybackForwardAt = now

            safeProvider { it.player.setPlaybackState(state) }
        } catch (t: Throwable) {
            YLog.error(tag = TAG, msg = "forward playback state failed", e = t)
        }
    }

    private fun readExtra(metadata: MediaMetadata, key: String): String? =
        metadata.getString(key) ?: metadata.description?.extras?.getString(key)

    private fun handleMetadata(metadata: MediaMetadata) {
        try {
            val info = LyricInfo.parse(readExtra(metadata, EXTRA_LYRIC_INFO))

            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            val name = (info?.name ?: metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
                ?.takeIf { it.isNotBlank() } ?: return
            val id = (info?.songId ?: mediaId)?.takeIf { it.isNotBlank() } ?: name
            val artist = info?.artist ?: metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            // 本地曲目的 media id 就是音频路径, extras 缺失时兜底.
            val audioPath = readExtra(metadata, EXTRA_PATH)
                ?: mediaId?.takeIf { it.startsWith("/") }

            // 不含 duration: 时长常晚于首帧元数据到达, 不该因此重跑取词.
            val songKey = "$id|$name|$artist"
            currentSongKey = songKey
            if (songKey == lastLyricsKey || songKey == lastNoLyricsKey) return

            // 先推送歌曲信息, 歌词随后异步补齐.
            push(id, name, artist, duration, emptyList(), songKey)

            if (songKey == loadingSongKey) return
            loadingSongKey = songKey
            loadLyrics(id, name, artist, duration, audioPath, info?.rawLyric, songKey)
        } catch (t: Throwable) {
            YLog.error(tag = TAG, msg = "handle metadata failed", e = t)
        }
    }

    private fun loadLyrics(
        id: String,
        name: String,
        artist: String?,
        duration: Long,
        audioPath: String?,
        inlineLyric: String?,
        songKey: String,
    ) {
        scope.launch {
            try {
                val candidate = findLyrics(audioPath, inlineLyric, duration)
                if (candidate == null) {
                    lastNoLyricsKey = songKey
                    return@launch
                }
                if (songKey != currentSongKey) return@launch

                val translations = candidate.lines.count { !it.translation.isNullOrBlank() }
                YLog.info(
                    tag = TAG,
                    msg = "lyrics: ${candidate.source}, ${candidate.lines.size} lines, " +
                        "$translations translated ($name)"
                )
                push(id, name, artist, duration, candidate.lines, songKey)
            } finally {
                if (loadingSongKey == songKey) loadingSongKey = null
            }
        }
    }

    /** 按顺序尝试各来源, 返回第一个能解析出歌词的. */
    private fun findLyrics(
        audioPath: String?,
        inlineLyric: String?,
        duration: Long,
    ): LyricCandidate? {
        val sources = mutableListOf<Pair<String, () -> RawLyrics?>>()
        // 网络曲目没有本地文件, 跳过文件相关的来源.
        if (audioPath != null && !audioPath.startsWith("http", ignoreCase = true)) {
            sources.add("sidecar" to { readSidecar(audioPath)?.let { RawLyrics(it) } })
            sources.add("tag" to { readEmbeddedLyrics(audioPath) })
            sources.add("cache" to { readNamidaLyricsCache(audioPath)?.let { RawLyrics(it) } })
        }
        sources.add("lyricInfo" to { inlineLyric?.let { RawLyrics(it) } })

        for ((source, read) in sources) {
            val raw = read() ?: continue
            val lines = parseLyrics(raw.text, duration)
            if (lines.isEmpty()) continue
            val merged = raw.translation?.let { mergeTranslation(lines, it, duration) } ?: lines
            return LyricCandidate(source, merged)
        }
        return null
    }

    /** 音频同目录的同名 .lrc / .LRC / .txt. */
    private fun readSidecar(audioPath: String): String? = try {
        val audio = File(audioPath)
        val dir = audio.parentFile
        val base = audio.nameWithoutExtension
        dir?.let {
            listOf("$base.lrc", "$base.LRC", "$base.txt")
                .asSequence()
                .map { name -> File(it, name) }
                .firstOrNull { file -> file.isFile }
                ?.readText()
        }
    } catch (t: Throwable) {
        YLog.error(tag = TAG, msg = "read sidecar failed: $audioPath", e = t)
        null
    }

    /** Namida 把取到的歌词缓存在 `<用户数据目录>/Lyrics/<音频文件名>.lrc`. */
    private fun readNamidaLyricsCache(audioPath: String): String? = try {
        val audio = File(audioPath)
        val names = listOfNotNull(
            audio.name.takeIf { it.isNotEmpty() },
            audio.nameWithoutExtension.takeIf { it.isNotEmpty() },
        ).map { "$it.lrc" }.distinct()

        val dir = lyricsCacheDir?.takeIf { it.isDirectory }
            ?: findLyricsCacheDir()?.also { lyricsCacheDir = it }

        var found: String? = null
        if (dir != null) {
            for (name in names) {
                val file = File(dir, name)
                if (file.isFile) {
                    found = file.readText()
                    break
                }
            }
        }
        found
    } catch (t: Throwable) {
        YLog.error(tag = TAG, msg = "read lyrics cache failed: $audioPath", e = t)
        null
    }

    /** 定位歌词缓存目录: 先按约定路径直接命中, 命中不了才递归扫描. */
    private fun findLyricsCacheDir(): File? {
        val context = appContext ?: return null
        val roots = cacheRoots(context)

        for (root in roots) {
            File(root, LYRICS_DIR_NAME).takeIf { it.isDirectory }?.let { return it }
        }
        for (root in roots) {
            findLyricsDir(root, CACHE_SEARCH_MAX_DEPTH)?.let { return it }
        }
        return null
    }

    /** Namida 用户数据根目录候选 (外部私有目录优先). */
    private fun cacheRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()
        runCatching { context.getExternalFilesDirs(null) }.getOrNull()?.forEach { dir ->
            if (dir != null) roots.add(dir)
        }
        runCatching { context.externalCacheDirs }.getOrNull()?.forEach { dir ->
            if (dir != null) roots.add(dir)
        }
        roots.add(context.filesDir)
        roots.add(context.cacheDir)
        roots.add(context.noBackupFilesDir)
        return roots
    }

    private fun findLyricsDir(dir: File, depthLeft: Int): File? {
        if (depthLeft < 0) return null
        val children = dir.listFiles() ?: return null

        children.firstOrNull { it.isDirectory && it.name.equals(LYRICS_DIR_NAME, true) }
            ?.let { return it }

        for (child in children) {
            if (!child.isDirectory) continue
            findLyricsDir(child, depthLeft - 1)?.let { return it }
        }
        return null
    }

    @Synchronized
    private fun push(
        id: String,
        name: String,
        artist: String?,
        duration: Long,
        lyrics: List<RichLyricLine>,
        songKey: String,
    ) {
        // duration 参与推送去重: 时长变了就再推一次 (不重新取词).
        val pushKey = "$songKey|$duration|${lyrics.size}"
        if (pushKey == lastPushKey) return
        lastPushKey = pushKey

        val song = if (lyrics.isEmpty()) {
            Song(id = id, name = name, artist = artist, duration = duration)
        } else {
            Song(id = id, name = name, artist = artist, duration = duration, lyrics = lyrics)
        }
        safeProvider { it.player.setSong(song) }

        if (lyrics.isNotEmpty()) lastLyricsKey = songKey
    }

    /** 一个歌词来源及其解析结果. */
    private data class LyricCandidate(val source: String, val lines: List<RichLyricLine>)

    /** 原始歌词; 译文也可能来自独立标签. */
    private data class RawLyrics(val text: String, val translation: String? = null)

    /** 读取内嵌歌词标签; 原文与译文可能是两个独立标签. */
    private fun readEmbeddedLyrics(audioPath: String): RawLyrics? = try {
        val uri = if (audioPath.startsWith("content://")) {
            Uri.parse(audioPath)
        } else {
            Uri.fromFile(File(audioPath))
        }
        val entries = appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
            // fd 由 TagLib 的 FileStream 接管并关闭; 只取标签, 不解析内嵌封面.
            TagLib.getMetadata(pfd.dup().detachFd(), readPictures = false)
                ?.propertyMap
                ?.entries
                ?.map { entry -> entry.key to entry.value?.toList().orEmpty() }
        }
        pickEmbeddedLyrics(entries)
    } catch (t: Throwable) {
        YLog.error(tag = TAG, msg = "read embedded lyrics failed: $audioPath", e = t)
        null
    }

    private fun pickEmbeddedLyrics(entries: List<Pair<String, List<String>>>?): RawLyrics? {
        val candidates = entries.orEmpty().filter { (key, values) ->
            isLyricsTag(key) && values.any { it.isNotBlank() }
        }
        if (candidates.isEmpty()) return null

        val primary = candidates.firstOrNull { it.first.equals("LYRICS", ignoreCase = true) }
            ?: candidates.firstOrNull { !isTranslationTag(it.first) }
            ?: candidates.first()
        val primaryText = primary.second.firstOrNull { it.isNotBlank() } ?: return null

        val translationText = candidates
            .firstOrNull { isTranslationTag(it.first) }
            ?.second
            ?.firstOrNull { it.isNotBlank() }

        return RawLyrics(primaryText, translationText?.takeIf { it != primaryText })
    }

    private fun isLyricsTag(key: String): Boolean {
        val upper = key.uppercase()
        return upper.contains("LYRIC") || upper.contains("SYLT") || upper.contains("USLT")
    }

    private fun isTranslationTag(key: String): Boolean {
        val upper = key.uppercase()
        return upper.contains("TRANS") || upper.contains("译文") || upper.contains("訳")
    }

    /** 把独立的一份译文并进主行: 优先按时间戳, 没有时间轴时按行序. */
    private fun mergeTranslation(
        lines: List<RichLyricLine>,
        translationRaw: String,
        duration: Long,
    ): List<RichLyricLine> {
        val parsed = EnhanceLrcParser.parse(translationRaw, duration).lines
        val byStamp = LinkedHashMap<Long, String>()
        for (line in parsed) {
            val text = line.text
            if (text.isNullOrBlank()) continue
            byStamp[line.begin] = byStamp[line.begin]?.let { "$it\n$text" } ?: text
        }

        if (byStamp.isNotEmpty()) {
            return lines.map { line ->
                if (!line.translation.isNullOrBlank()) {
                    line
                } else {
                    translationAt(byStamp, line.begin)?.let { line.copy(translation = it) } ?: line
                }
            }
        }

        // 译文没有时间轴: 行数与原文一致才按行序配对.
        val plain = translationRaw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") }
        if (plain.size != lines.size) return lines

        return lines.mapIndexed { index, line ->
            if (!line.translation.isNullOrBlank()) line
            else line.copy(translation = plain[index])
        }
    }

    /** 先按时间戳精确匹配, 再容忍小误差取最近的. */
    private fun translationAt(byStamp: Map<Long, String>, stamp: Long): String? =
        byStamp[stamp] ?: byStamp.entries
            .minByOrNull { abs(it.key - stamp) }
            ?.takeIf { abs(it.key - stamp) <= TRANSLATION_TOLERANCE_MS }
            ?.value

    /** 行首时间戳: `[mm:ss]` / `[mm:ss.x]` / `[mm:ss.xx]` / `[mm:ss.xxx]`. */
    private val LINE_TIMESTAMP = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** 对唱 / 背景人声前缀, 这类行不是译文. */
    private val PERSON_PREFIX = Regex("""^(?:v\d+|bg)\s*:""", RegexOption.IGNORE_CASE)

    /**
     * 解析 LRC.
     *
     * 同一时间戳可出现任意多条: 第一条为原文, 其余均为译文 (多条用 \n 拼接).
     * 原文交给 [EnhanceLrcParser] 保留逐字时间轴; 无效时间戳 (秒 >= 60) 忽略.
     */
    private fun parseLyrics(raw: String?, duration: Long): List<RichLyricLine> {
        if (raw.isNullOrBlank()) return emptyList()

        val primaryText = StringBuilder()
        val translations = LinkedHashMap<Long, String>()
        val seenPrimary = HashSet<Long>()

        for (line in raw.removePrefix("\uFEFF").lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val match = LINE_TIMESTAMP.find(trimmed)
            if (match == null) {
                primaryText.append(trimmed).append('\n') // 元数据行
                continue
            }

            val stamp = stampOf(match) ?: continue
            val text = trimmed.substring(match.range.last + 1).trim()
            if (text.isEmpty()) continue

            // 首条为原文 (对唱/背景人声同属原文), 其余为译文.
            if (seenPrimary.add(stamp) || PERSON_PREFIX.containsMatchIn(text)) {
                primaryText.append(trimmed).append('\n')
            } else {
                translations[stamp] = translations[stamp]?.let { "$it\n$text" } ?: text
            }
        }

        val document = EnhanceLrcParser.parse(primaryText.toString(), duration)
        val offset = document.metadata["offset"]?.toLongOrNull() ?: 0L

        return document.lines.mapNotNull { line ->
            // 逐字行末尾带空的结束标签, 需过滤; 逐字之间无分隔符, 直接拼接还原原文.
            val words = line.words?.filter { !it.text.isNullOrEmpty() }
            val text = if (words.isNullOrEmpty()) {
                line.text
            } else {
                words.joinToString("") { word -> word.text ?: "" }
            }
            if (text.isNullOrBlank()) return@mapNotNull null

            // [offset:] 会移动 begin, 先还原成原始时间戳再查译文.
            line.copy(
                text = text,
                words = words?.takeIf { it.isNotEmpty() },
                translation = translationAt(translations, line.begin - offset),
            )
        }
    }

    /** 把行首时间戳换算成毫秒; 无效时间戳 (秒 >= 60) 返回 null. */
    private fun stampOf(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        if (seconds >= 60) return null

        val fraction = match.groupValues[3]
        val millis = when (fraction.length) {
            1 -> (fraction.toLongOrNull() ?: 0L) * 100
            2 -> (fraction.toLongOrNull() ?: 0L) * 10
            3 -> fraction.toLongOrNull() ?: 0L
            else -> 0L
        }
        return minutes * 60_000 + seconds * 1_000 + millis
    }
}
