#!/usr/bin/env python3
"""Patch the public HGTV Android TV source in an isolated repair build.

The script is deliberately fail-fast: unexpected upstream changes stop the CI build
rather than producing a partially modified APK.
"""
from pathlib import Path
import re
import sys

ROOT = Path("upstream")


def read(rel):
    path = ROOT / rel
    if not path.is_file():
        raise SystemExit("Missing expected source file: " + str(path))
    return path, path.read_text(encoding="utf-8")


def write(path, text):
    path.write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit("Expected one patch point for " + label + ", found " + str(count))
    return text.replace(old, new, 1)


# A) StreamInfo carries request headers so official-page streams can use a browser UA.
path, src = read("android/core/src/main/kotlin/com/hgtv/star/core/ContentRepository.kt")
src = replace_once(
    src,
    "data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)",
    """data class StreamInfo(
    val url: String,
    val key: ByteArray?,
    val quality: String,
    val userAgent: String = VendorConstants.VIDEO_UA,
    val referer: String = "https://novel.snssdk.com/"
)""",
    "StreamInfo request headers",
)
src = replace_once(
    src,
    "    private val signer = Signer()",
    """    private val signer = Signer()
    // Retry attempts are keyed by episode and advance through same-quality CDN mirrors.
    private val streamSourceAttempts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    fun rotateStreamSource(id: String) {
        if (id.matches(Regex("[0-9]{1,30}"))) {
            streamSourceAttempts.computeIfAbsent(id) {
                java.util.concurrent.atomic.AtomicInteger(0)
            }.incrementAndGet()
        }
    }""",
    "source rotation state",
)
stream_start = src.index("    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {")
stream_end = src.index("    /**\n     * 取一集的弹幕", stream_start)
new_stream = r'''    fun stream(id: String, maxQuality: Int = 1080, seriesId: String = ""): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val model = videoModel(id)
        val raw = model.opt("fallback_api")
        val fallback = when (raw) {
            is JSONObject -> raw.str("fallback_api")
            is JSONArray -> raw.optString(0)
            else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString())
        }
        if (!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info = JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data")
            ?: throw IOException("没有播放资源")
        val videoList = info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        val rows = videoList.keys().asSequence()
            .mapNotNull { key -> videoList.optJSONObject(key)?.let { key to it } }
            .filter {
                it.second.str("main_url").isNotEmpty() &&
                    it.second.str("codec_type").lowercase() !in setOf("bytevc2", "bvc2")
            }
            .map { (key, item) ->
                Triple(
                    qualityNumber(item.str("quality_desc").ifEmpty {
                        item.str("height").ifEmpty {
                            item.str("vheight").ifEmpty { item.str("quality").ifEmpty { key } }
                        }
                    }),
                    key,
                    item
                )
            }
            .sortedByDescending { it.first }
            .toList()

        // 720P is a hard floor. If a source cannot confirm at least 720P, stop instead
        // of quietly selecting a low-resolution stream.
        val qualityCeiling = maxOf(720, maxQuality)
        val selected = rows.firstOrNull { it.first in 720..qualityCeiling }
            ?: throw IOException("本集没有可确认的 720P 或更高画质，已停止自动降质")
        val item = selected.third
        val seed = info.str("key_seed")
        val candidates = buildList {
            add(item.str("main_url"))
            add(item.str("backup_url"))
            item.optJSONArray("backup_url_list")?.let { backups ->
                for (i in 0 until backups.length()) add(backups.optString(i))
            }
        }.mapNotNull { rawUrl ->
            if (rawUrl.isBlank()) return@mapNotNull null
            runCatching {
                val cleaned = cleanUrl(rawUrl)
                val resolved = if (seed.isNotEmpty()) MediaCrypto.decryptUrl(cleaned, decode64(seed)) else cleaned
                if (resolved.startsWith("https://") || resolved.startsWith("http://")) resolved else null
            }.getOrNull()
        }.distinct()
        if (candidates.isEmpty()) throw IOException("本集没有可用的 720P 或更高播放地址")

        val attempt = streamSourceAttempts[id]?.get() ?: 0
        val selectedUrl = when {
            attempt < candidates.size -> candidates[attempt]
            attempt == candidates.size -> officialWebStream(id, seriesId, qualityCeiling) ?: candidates[0]
            else -> candidates[(attempt - candidates.size - 1) % candidates.size]
        }
        val key = item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        return StreamInfo(selectedUrl, key, selected.first.toString() + "P")
    }

    /**
     * Last-resort official website player source. The page and media URL must be HTTPS,
     * identify this exact episode, and explicitly advertise 720P or higher in the URL.
     * If quality cannot be verified, the candidate is rejected.
     */
    private fun officialWebStream(id: String, seriesId: String, maxQuality: Int): StreamInfo? {
        if (!id.matches(Regex("[0-9]{1,30}")) || !seriesId.matches(Regex("[0-9]{1,30}"))) return null
        val page = site + "/player/" + seriesId + "/" + id
        val browserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        val request = Request.Builder()
            .url(page)
            .header("User-Agent", browserUa)
            .header("Referer", site + "/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        val html = runCatching {
            RequestScope.execute(http.newCall(request)) { response ->
                if (!response.isSuccessful) throw IOException("官网备援页面请求失败")
                val body = response.body ?: throw IOException("官网备援页面为空")
                if (body.contentLength() > 16 * 1024 * 1024) throw IOException("官网备援页面过大")
                body.string()
            }
        }.getOrNull() ?: return null

        val scripts = Regex("<script\\b([^>]*)>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE).findAll(html)
        val expectedPath = "/player/" + seriesId + "/" + id
        for (match in scripts) {
            if (!match.groupValues[1].contains("application/ld+json", ignoreCase = true)) continue
            val video = runCatching { JSONObject(match.groupValues[2]) }.getOrNull() ?: continue
            if (video.optString("@type") != "VideoObject") continue
            val pageUri = runCatching { java.net.URI(page).resolve(video.optString("url")) }.getOrNull() ?: continue
            if (pageUri.scheme != "https" || !pageUri.host.equals("hongguoduanju.com", ignoreCase = true) ||
                pageUri.path != expectedPath) continue

            val contentUrl = cleanUrl(video.optString("contentUrl"))
            val mediaUri = runCatching { java.net.URI(contentUrl) }.getOrNull() ?: continue
            if (mediaUri.scheme != "https" || !mediaUri.host.equals("hongguoduanju.com", ignoreCase = true) ||
                mediaUri.userInfo != null) continue
            val resolution = Regex("(2160|1440|1080|720)p", RegexOption.IGNORE_CASE)
                .find(contentUrl)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (resolution < 720 || resolution > maxQuality || !contentUrl.endsWith(".mp4", ignoreCase = true)) continue
            return StreamInfo(contentUrl, null, resolution.toString() + "P · 官网备援", browserUa, site + "/")
        }
        return null
    }
'''
src = src[:stream_start] + new_stream + "\n" + src[stream_end:]
write(path, src)

# B) Respect the user-agent / referer assigned to the selected source.
path, src = read("android/core/src/main/kotlin/com/hgtv/star/core/RemoteVideo.kt")
src = replace_once(
    src,
    '.header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/")',
    '.header("User-Agent",info.userAgent).header("Referer",info.referer)',
    "RemoteVideo request headers",
)
write(path, src)

# C) Pass series ID into both foreground and prefetched source resolution.
path, src = read("android/app/src/main/kotlin/com/hgtv/star/MainActivityPlayer.kt")
src = replace_once(
    src,
    "prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality)).prepare()",
    "prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality,data.series.id)).prepare()",
    "foreground stream call",
)
src = replace_once(
    src,
    "        val listener=object: Player.Listener {",
    """        val bufferingTimeout = Runnable {
            if (player===p && generation==ticket && p.currentMediaItem?.mediaId==requestedId &&
                p.playbackState==Player.STATE_BUFFERING && p.playWhenReady && foreground && !playError) {
                playerFailure(java.io.IOException("來源持續緩衝 18 秒，嘗試切換備援來源"))
            }
        }
        val listener=object: Player.Listener {""",
    "continuous-buffer timeout",
)
src = replace_once(
    src,
    "            override fun onPlaybackStateChanged(state: Int) {\n                if(!active()) return",
    """            override fun onPlaybackStateChanged(state: Int) {
                main.removeCallbacks(bufferingTimeout)
                if(!active()) return
                if(state==Player.STATE_BUFFERING && p.playWhenReady) main.postDelayed(bufferingTimeout,18_000)""",
    "buffering timeout state",
)
src = replace_once(
    src,
    "            override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }",
    "            override fun onPlayerError(error: PlaybackException) { main.removeCallbacks(bufferingTimeout); if(active()) playerFailure(error) }",
    "clear timeout on player error",
)
write(path, src)

# D) Retryable network/timeout failures rotate to the next source. A genuine
# no-720P response is terminal and does not trigger repeated requests.
path, src = read("android/app/src/main/kotlin/com/hgtv/star/MainActivityPlayback.kt")
src = replace_once(
    src,
    "    val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException",
    """    val noHdSource=problem.message.orEmpty().contains("没有可确认的 720P") ||
        problem.message.orEmpty().contains("没有可用的 720P")
    val retryable=!noHdSource && (if(problem is PlaybackException) problem.errorCode in 2000..2999 ||
        problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException)
    if(retryable) detail?.episodes?.getOrNull(episodeIndex)?.let { repository.rotateStreamSource(it) }""",
    "rotation on recoverable playback failure",
)
src = replace_once(
    src,
    "RemoteVideo(http,source.stream(id,quality)).also { remote=it; it.warm() }",
    "RemoteVideo(http,source.stream(id,quality,data.series.id)).also { remote=it; it.warm() }",
    "prefetch stream call",
)
write(path, src)

# E) Do not expose sub-720P choices in either playback menu or the settings picker.
path, src = read("android/app/src/main/kotlin/com/hgtv/star/MainActivityPlaybackMenu.kt")
src = replace_once(
    src,
    '.addRow("清晰度",PlaybackQuality.options.mapIndexed { index,value ->',
    '.addRow("清晰度",PlaybackQuality.options.filter { it >= 720 }.mapIndexed { index,value ->',
    "in-player quality choices",
)
src = replace_once(
    src,
    "    val options=PlaybackQuality.options\n",
    "    if (library.maxQuality < 720) library.maxQuality = 720\n    val options=PlaybackQuality.options.filter { it >= 720 }\n",
    "settings quality choices",
)
write(path, src)
path, src = read("android/app/src/main/kotlin/com/hgtv/star/MainActivityPlayer.kt")
src = replace_once(
    src,
    "    val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation",
    "    if(library.maxQuality<720) library.maxQuality=720\n    val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation",
    "minimum quality migration",
)
write(path, src)

# F) Netflix-inspired featured shelf: featured title, synopsis, artwork, and a
# remote-focusable primary action. All focus paths remain explicit for a D-pad.
path, src = read("android/app/src/main/kotlin/com/hgtv/star/list/HomeScreen.kt")
src = replace_once(
    src,
    "import android.graphics.Color\n",
    "import android.graphics.Color\nimport android.graphics.drawable.GradientDrawable\n",
    "hero gradient import",
)
src = replace_once(
    src,
    "    private var compact = false",
    "    private var compact = false\n    private var heroButton: View? = null",
    "hero button state",
)
src = replace_once(
    src,
    "        lastRow = null\n\n        shelves.filter { it.entries.isNotEmpty() || it.more != null }.forEach { shelf ->",
    """        lastRow = null
        heroButton = null

        val featured = shelves.firstOrNull { it.key == "rank-hongguo" && it.entries.isNotEmpty() }?.entries?.firstOrNull()
            ?: shelves.firstOrNull { it.key.startsWith("rank-") && it.entries.isNotEmpty() }?.entries?.firstOrNull()
        if (featured != null) contents.addView(
            featuredHero(featured),
            LinearLayout.LayoutParams(-1, dp(256)).apply { bottomMargin = dp(18) }
        )

        shelves.filter { it.entries.isNotEmpty() || it.more != null }.forEach { shelf ->""",
    "featured hero insertion",
)
src = replace_once(
    src,
    "        top.nextFocusDownId = firstId()",
    """        heroButton?.let { hero ->
            hero.nextFocusUpId = top.id
            hero.nextFocusDownId = rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id
            rows.firstOrNull()?.cards?.firstOrNull()?.view?.nextFocusUpId = hero.id
        }
        top.nextFocusDownId = firstId()""",
    "hero D-pad focus chain",
)
src = replace_once(
    src,
    "    fun firstId() = rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id",
    "    fun firstId() = heroButton?.id?.takeIf { it != View.NO_ID } ?: rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id",
    "first focus target",
)
src = replace_once(
    src,
    "                    onHeadUp?.invoke() ?: false",
    "                    if (heroButton?.isAttachedToWindow == true) { heroButton?.requestFocus(); true } else onHeadUp?.invoke() ?: false",
    "first shelf up focus",
)
hero_method = r'''    private fun featuredHero(entry: Entry): View {
        val hero = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            background = TvStyle.shape(context, Color.rgb(8, 10, 15), radius = 16, stroke = 0)
        }
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = entry.series.title
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        loadCover(poster, entry.series.cover)
        hero.addView(poster, FrameLayout.LayoutParams(dp(220), -1, Gravity.END))
        hero.addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(8, 10, 15), Color.rgb(8, 10, 15), Color.argb(0, 8, 10, 15))
            )
        }, FrameLayout.LayoutParams(-1, -1))
        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(26), dp(14), dp(18), dp(20))
            isFocusable = false
        }
        info.addView(
            context.tvText("本週主打", 12f, Color.LTGRAY, true),
            LinearLayout.LayoutParams(-1, -2)
        )
        info.addView(
            context.tvText(entry.series.title, 27f, Color.WHITE, true).apply { maxLines = 2 },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        )
        val synopsis = entry.series.description.ifBlank { entry.series.tags }.take(88)
        if (synopsis.isNotBlank()) {
            info.addView(
                context.tvText(synopsis, 14f, Color.LTGRAY).apply { maxLines = 2 },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            )
        }
        val cta = TextView(context).apply {
            id = View.generateViewId()
            text = "▶  查看劇集"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(dp(18), dp(11), dp(18), dp(11))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            isFocusable = true
            isClickable = true
            setOnClickListener { open(entry.series, entry.resume) }
            setOnFocusChangeListener { view, focused ->
                TvStyle.focus(view, focused, scale = 1.035f)
                view.alpha = if (focused) 1f else .94f
            }
        }
        heroButton = cta
        info.addView(cta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(13) })
        hero.addView(info, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM or Gravity.START))
        return hero
    }

'''
src = replace_once(
    src,
    "    fun restorePosition(position: Position, requestFocus: Boolean = true) {",
    hero_method + "    fun restorePosition(position: Position, requestFocus: Boolean = true) {",
    "featured hero view",
)
write(path, src)

# G) Convert visible UI literals/resources into Taiwan Traditional Chinese while
# leaving identifiers, comments, API routes and keys unchanged.
try:
    from opencc import OpenCC
    converter = OpenCC("s2twp")
except Exception as exc:
    raise SystemExit("Cannot convert UI to Traditional Chinese: " + str(exc))

literal_re = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"', re.MULTILINE)
for path in (ROOT / "android").rglob("*.kt"):
    original = path.read_text(encoding="utf-8")
    def convert_literal(match):
        token = match.group(0)
        if token.startswith('"""'):
            return '"""' + converter.convert(token[3:-3]) + '"""'
        return '"' + converter.convert(token[1:-1]) + '"'
    converted = literal_re.sub(convert_literal, original)
    if converted != original:
        path.write_text(converted, encoding="utf-8")
for path in (ROOT / "android/app/src/main/res").rglob("*.xml"):
    original = path.read_text(encoding="utf-8")
    converted = converter.convert(original)
    if converted != original:
        path.write_text(converted, encoding="utf-8")

# H) Keep the custom repair APK installable side-by-side; do not overwrite the
# current HGTV install and do not risk deleting its saved local data.
path, src = read("android/app/build.gradle.kts")
src = replace_once(
    src,
    'applicationId = "com.hgtv.star"',
    'applicationId = "com.hgtv.star.dickyfix"',
    "separate test package",
)
write(path, src)

print("Applied HGTV repair patch: independent four-rank baseline, stable D-pad focus,")
print("Netflix-inspired featured hero, 720P hard floor, CDN rotation, verified official")
print("website fallback, playback-position recovery and Taiwan Traditional Chinese.")
