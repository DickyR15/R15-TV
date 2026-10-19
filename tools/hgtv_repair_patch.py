#!/usr/bin/env python3
"""Apply the isolated HGTV repair patch to the checked-out public source tree.

This patch is fail-fast: any unexpected upstream source shape stops the build
instead of silently shipping a partially patched APK.
"""
from pathlib import Path
import re
import sys

ROOT = Path("upstream")

def read(rel):
    p = ROOT / rel
    if not p.is_file():
        raise SystemExit(f"Missing expected source file: {p}")
    return p, p.read_text(encoding="utf-8")

def write(p, text):
    p.write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one patch point for {label}, found {count}")
    return text.replace(old, new, 1)

# 1) Give StreamInfo per-source HTTP headers. The original app CDN keeps its
# original headers; official-site direct MP4s use a browser UA/referer.
p, s = read("android/core/src/main/kotlin/com/hgtv/star/core/ContentRepository.kt")
s = replace_once(
    s,
    "data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)",
    'data class StreamInfo(val url: String, val key: ByteArray?, val quality: String,\\n'
    '    val userAgent: String = VendorConstants.VIDEO_UA,\\n'
    '    val referer: String = "https://novel.snssdk.com/")',
    "StreamInfo headers",
)
s = replace_once(
    s,
    "    private val signer = Signer()",
    "    private val signer = Signer()\\n"
    "    // Per-episode source rotation survives automatic retries; each retry advances\\n"
    "    // through same-quality CDN mirrors before attempting the official web player.\\n"
    "    private val streamSourceAttempts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()\\n"
    "    fun rotateStreamSource(id: String) {\\n"
    "        if (id.matches(Regex(\\"[0-9]{1,30}\\"))) streamSourceAttempts.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()\\n"
    "    }",
    "source rotation state",
)
stream_start = s.index("    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {")
stream_end = s.index("    /**\\n     * 取一集的弹幕", stream_start)
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
        val rows = videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() &&
                it.second.str("codec_type").lowercase() !in setOf("bytevc2", "bvc2")
        }.map { (k, o) ->
            Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }), k, o)
        }.sortedByDescending { it.first }.toList()

        // This build never silently falls back to 480P/360P. Prefer the requested
        // ceiling, but enforce 720P as the hard minimum even if an old setting was 480P.
        val qualityCeiling = maxOf(720, maxQuality)
        val selected = rows.firstOrNull { it.first in 720..qualityCeiling }
            ?: throw IOException("本集没有可确认的 720P 或更高画质，已停止自动降质")
        val item = selected.third
        val seed = info.str("key_seed")
        val rawCandidates = mutableListOf<String>()
        rawCandidates += item.str("main_url")
        rawCandidates += item.str("backup_url")
        item.optJSONArray("backup_url_list")?.let { backups ->
            for (i in 0 until backups.length()) rawCandidates += backups.optString(i)
        }
        val candidates = rawCandidates.mapNotNull { rawUrl ->
            if (rawUrl.isBlank()) return@mapNotNull null
            val resolved = runCatching {
                val cleaned = cleanUrl(rawUrl)
                if (seed.isNotEmpty()) MediaCrypto.decryptUrl(cleaned, decode64(seed)) else cleaned
            }.getOrNull() ?: return@mapNotNull null
            if (resolved.startsWith("https://") || resolved.startsWith("http://")) resolved else null
        }.distinct()
        if (candidates.isEmpty()) throw IOException("本集没有可用的 720P 或更高播放地址")

        val attempt = streamSourceAttempts[id]?.get() ?: 0
        val url = when {
            attempt < candidates.size -> candidates[attempt]
            attempt == candidates.size -> {
                // A distinct source: read the official site's public player metadata.
                // Only accept a direct MP4 whose URL explicitly identifies 720P or higher.
                officialWebStream(id, seriesId, qualityCeiling)?.let { return it }
                candidates[0]
            }
            else -> candidates[(attempt - candidates.size - 1) % candidates.size]
        }
        val key = item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        return StreamInfo(url, key, selected.first.toString() + "P")
    }

    /**
     * Last-resort source from the official public player page. The URL must belong to
     * hongguoduanju.com and explicitly identify a 720P/1080P/1440P/2160P MP4.
     * Unknown quality and lower-resolution files are rejected, never silently used.
     */
    private fun officialWebStream(id: String, seriesId: String, maxQuality: Int): StreamInfo? {
        if (!id.matches(Regex("[0-9]{1,30}")) || !seriesId.matches(Regex("[0-9]{1,30}"))) return null
        val page = site + "/player/" + seriesId + "/" + id
        val html = runCatching { text(page) }.getOrNull() ?: return null
        val scripts = Regex("<script\\\\b([^>]*)>([\\\\s\\\\S]*?)</script\\\\s*>", RegexOption.IGNORE_CASE)
            .findAll(html)
        var video: JSONObject? = null
        for (match in scripts) {
            if (!match.groupValues[1].contains("application/ld+json", ignoreCase = true)) continue
            val candidate = runCatching { JSONObject(match.groupValues[2]) }.getOrNull() ?: continue
            if (candidate.optString("@type") != "VideoObject") continue
            if (candidate.optString("url").trimEnd('/') != page.trimEnd('/')) continue
            video = candidate
            break
        }
        val contentUrl = cleanUrl(video?.optString("contentUrl").orEmpty())
        if (contentUrl.isBlank()) return null
        val parsed = runCatching { java.net.URI(contentUrl) }.getOrNull() ?: return null
        val host = parsed.host?.lowercase().orEmpty()
        if (parsed.scheme != "https" || !(host == "hongguoduanju.com" || host.endsWith(".hongguoduanju.com"))) return null
        val resolution = Regex("(2160|1440|1080|720)p", RegexOption.IGNORE_CASE)
            .find(contentUrl)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (resolution < 720 || resolution > maxQuality) return null
        return StreamInfo(
            contentUrl, null, resolution.toString() + "P · 官网备援",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            site + "/"
        )
    }
'''
s = s[:stream_start] + new_stream + "\n" + s[stream_end:]
write(p, s)

# 2) Honor StreamInfo's user-agent and referer for the official-web source.
p, s = read("android/core/src/main/kotlin/com/hgtv/star/core/RemoteVideo.kt")
s = replace_once(
    s,
    '.header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/")',
    '.header("User-Agent",info.userAgent).header("Referer",info.referer)',
    "RemoteVideo source headers",
)
write(p, s)

# 3) Pass the series ID so the official web page can be resolved per episode.
p, s = read("android/app/src/main/kotlin/com/hgtv/star/MainActivityPlayer.kt")
s = replace_once(
    s,
    "prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality)).prepare()",
    "prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality,data.series.id)).prepare()",
    "normal playback source lookup",
)
needle = "        val listener=object: Player.Listener {"
buffering = '''        // If an actively playing source buffers continuously, move to the next source
        // after 18 seconds instead of leaving the TV spinning indefinitely.
        val bufferingTimeout = Runnable {
            if (player===p && generation==ticket && p.currentMediaItem?.mediaId==requestedId &&
                p.playbackState==Player.STATE_BUFFERING && p.playWhenReady && foreground && !playError) {
                playerFailure(java.io.IOException("來源持續緩衝 18 秒，嘗試切換備援來源"))
            }
        }
'''
s = replace_once(s, needle, buffering + needle, "buffering timeout declaration")
s = replace_once(
    s,
    "            override fun onPlaybackStateChanged(state: Int) {\\n                if(!active()) return",
    "            override fun onPlaybackStateChanged(state: Int) {\\n"
    "                if(!active()) { main.removeCallbacks(bufferingTimeout); return }\\n"
    "                if(state==Player.STATE_BUFFERING && p.playWhenReady) {\\n"
    "                    main.removeCallbacks(bufferingTimeout); main.postDelayed(bufferingTimeout,18_000)\\n"
    "                } else main.removeCallbacks(bufferingTimeout)",
    "buffering timeout scheduling",
)
s = replace_once(
    s,
    "            override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }",
    "            override fun onPlayerError(error: PlaybackException) { main.removeCallbacks(bufferingTimeout); if(active()) playerFailure(error) }",
    "clear buffering timeout on error",
)
write(p, s)

# 4) Every retryable playback failure advances the source candidate. Decoder errors
# are not network failures, and explicit no-720P failures should not be retried needlessly.
p, s = read("android/app/src/main/kotlin/com/hgtv/star/MainActivityPlayback.kt")
s = replace_once(
    s,
    "    val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException",
    "    val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException\\n"
    "    if(retryable && !problem.message.orEmpty().contains(\\"没有可确认的 720P\\") && !problem.message.orEmpty().contains(\\"没有可用的 720P\\")) {\\n"
    "        detail?.episodes?.getOrNull(episodeIndex)?.let { repository.rotateStreamSource(it) }\\n"
    "    }",
    "rotate source on recoverable error",
)
s = replace_once(
    s,
    "RemoteVideo(http,source.stream(id,quality)).also { remote=it; it.warm() }",
    "RemoteVideo(http,source.stream(id,quality,data.series.id)).also { remote=it; it.warm() }",
    "prefetch source lookup",
)
write(p, s)

# 5) Remove old sub-720P choices and migrate old persisted quality to 720P.
p, s = read("android/app/src/main/kotlin/com/hgtv/star/MainActivityPlaybackMenu.kt")
s = replace_once(
    s,
    '.addRow("清晰度",PlaybackQuality.options.mapIndexed { index,value ->',
    '.addRow("清晰度",PlaybackQuality.options.filter { it >= 720 }.mapIndexed { index,value ->',
    "player quality choices",
)
s = replace_once(
    s,
    "    val options=PlaybackQuality.options\\n",
    "    if (library.maxQuality < 720) library.maxQuality = 720\\n    val options=PlaybackQuality.options.filter { it >= 720 }\\n",
    "quality-picker choices",
)
write(p, s)

# 6) Netflix-inspired home hero: broad featured artwork, title, synopsis and a
# focusable primary action. Existing TV focus behavior remains the navigation base.
p, s = read("android/app/src/main/kotlin/com/hgtv/star/list/HomeScreen.kt")
s = replace_once(s, "import android.graphics.Color\\n", "import android.graphics.Color\\nimport android.graphics.drawable.GradientDrawable\\n", "hero drawable import")
s = replace_once(s, "    private var compact = false", "    private var compact = false\\n    private var heroButton: View? = null", "hero focus reference")
s = replace_once(
    s,
    "        lastRow = null\\n\\n        shelves.filter { it.entries.isNotEmpty() || it.more != null }.forEach { shelf ->",
    "        lastRow = null\\n        heroButton = null\\n\\n"
    "        val featured = shelves.firstOrNull { it.key == \\"rank-hongguo\\" && it.entries.isNotEmpty() }?.entries?.firstOrNull()\\n"
    "            ?: shelves.firstOrNull { it.key.startsWith(\\"rank-\\") && it.entries.isNotEmpty() }?.entries?.firstOrNull()\\n"
    "        if (featured != null) contents.addView(featuredHero(featured), LinearLayout.LayoutParams(-1, dp(256)).apply { bottomMargin = dp(18) })\\n\\n"
    "        shelves.filter { it.entries.isNotEmpty() || it.more != null }.forEach { shelf ->",
    "featured hero insertion",
)
s = replace_once(
    s,
    "        top.nextFocusDownId = firstId()",
    "        heroButton?.let { hero ->\\n"
    "            hero.nextFocusUpId = top.id\\n"
    "            hero.nextFocusDownId = rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id\\n"
    "            rows.firstOrNull()?.cards?.firstOrNull()?.view?.nextFocusUpId = hero.id\\n"
    "        }\\n"
    "        top.nextFocusDownId = firstId()",
    "hero focus chain",
)
s = replace_once(
    s,
    "    fun firstId() = rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id",
    "    fun firstId() = heroButton?.id?.takeIf { it != View.NO_ID } ?: rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id",
    "hero nav down target",
)
s = replace_once(
    s,
    "                    onHeadUp?.invoke() ?: false",
    "                    if (heroButton?.isAttachedToWindow == true) { heroButton?.requestFocus(); true } else onHeadUp?.invoke() ?: false",
    "first row up to hero",
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
        info.addView(context.tvText("本週主打", 12f, Color.LTGRAY, true), LinearLayout.LayoutParams(-1, -2))
        info.addView(context.tvText(entry.series.title, 27f, Color.WHITE, true).apply { maxLines = 2 },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val synopsis = entry.series.description.ifBlank { entry.series.tags }.take(88)
        if (synopsis.isNotBlank()) {
            info.addView(context.tvText(synopsis, 14f, Color.LTGRAY).apply { maxLines = 2 },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
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
s = replace_once(s, "    fun restorePosition(position: Position, requestFocus: Boolean = true) {", hero_method + "    fun restorePosition(position: Position, requestFocus: Boolean = true) {", "featured hero method")
write(p, s)

# 7) Convert visible Chinese literals/resources to Traditional Chinese (Taiwan).
# Comments and identifiers are untouched; only Kotlin string literals are converted.
try:
    from opencc import OpenCC
    cc = OpenCC("s2twp")
except Exception as exc:
    raise SystemExit("OpenCC is required to produce the Traditional Chinese UI: " + str(exc))

literal_re = re.compile(r'"""[\s\S]*?"""|"(?:\\\\.|[^"\\\\])*"', re.MULTILINE)
for path in list((ROOT / "android").rglob("*.kt")):
    text = path.read_text(encoding="utf-8")
    def convert_literal(match):
        token = match.group(0)
        if token.startswith('"""'):
            return '"""' + cc.convert(token[3:-3]) + '"""'
        return '"' + cc.convert(token[1:-1]) + '"'
    converted = literal_re.sub(convert_literal, text)
    if converted != text:
        path.write_text(converted, encoding="utf-8")
for path in (ROOT / "android/app/src/main/res").rglob("*.xml"):
    text = path.read_text(encoding="utf-8")
    converted = cc.convert(text)
    if converted != text:
        path.write_text(converted, encoding="utf-8")

# 8) Keep the test package separate so it can be installed side-by-side with the user's
# current APK. Do not silently replace the existing app or its local data.
p, s = read("android/app/build.gradle.kts")
s = replace_once(s, 'applicationId = "com.hgtv.star"', 'applicationId = "com.hgtv.star.dickyfix"', "test application ID")
write(p, s)

print("HGTV repair patch applied: rank/focus baseline, featured hero, 720P floor, CDN rotation, official-site fallback, Taiwan Traditional Chinese.")
