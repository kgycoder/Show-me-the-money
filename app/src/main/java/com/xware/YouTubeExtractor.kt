package com.xware

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val instances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://piped-api.garudalinux.org",
        "https://api.piped.projectsegfau.lt",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.reallyaweso.me"
    )

    data class StreamInfo(
        val audioUrl: String,
        val title: String,
        val duration: Long,
        val thumbnailUrl: String,
        val needsProxy: Boolean = false
    )

    fun extractAudio(videoId: String): StreamInfo? {
        // 1순위: Piped API
        for (inst in instances) {
            val r = tryPiped(videoId, inst)
            if (r != null) return r
        }
        // 2순위: Invidious API (Piped 전부 실패 시)
        return tryInvidious(videoId)
    }

    private fun tryPiped(videoId: String, base: String): StreamInfo? {
        return try {
            val req = Request.Builder()
                .url("$base/streams/$videoId")
                .header("User-Agent", "XWare/1.0")
                .header("Accept", "application/json")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) { resp.close(); return null }
            val body = resp.body?.string() ?: run { resp.close(); return null }
            resp.close()

            val doc = JSONObject(body)
            if (doc.has("error") || doc.has("message")) return null

            val title     = doc.optString("title", "")
            val duration  = doc.optLong("duration", 0L)
            val thumbnail = doc.optString("thumbnailUrl",
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")

            val streams = doc.optJSONArray("audioStreams") ?: return null

            data class AS(val url: String, val bitrate: Int, val isOpus: Boolean)
            val list = mutableListOf<AS>()
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                val url = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                list.add(AS(
                    url      = url,
                    bitrate  = s.optInt("bitrate", 0),
                    isOpus   = s.optString("mimeType").contains("opus")
                ))
            }
            if (list.isEmpty()) return null

            val best = list.sortedWith(
                compareByDescending<AS> { if (it.isOpus) 1 else 0 }
                    .thenByDescending { it.bitrate }
            ).first()

            android.util.Log.d("XWare", "Piped [$base] OK: ${best.bitrate}bps")
            StreamInfo(best.url, title, duration, thumbnail)

        } catch (e: Exception) {
            android.util.Log.w("XWare", "Piped [$base]: ${e.message}")
            null
        }
    }

    private fun tryInvidious(videoId: String): StreamInfo? {
        val invidiousInstances = listOf(
            "https://invidious.snopyta.org",
            "https://inv.riverside.rocks",
            "https://invidious.kavin.rocks"
        )
        for (base in invidiousInstances) {
            try {
                val req = Request.Builder()
                    .url("$base/api/v1/videos/$videoId?fields=title,lengthSeconds,videoThumbnails,adaptiveFormats")
                    .header("User-Agent", "XWare/1.0")
                    .build()

                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) { resp.close(); continue }
                val body = resp.body?.string() ?: run { resp.close(); continue }
                resp.close()

                val doc      = JSONObject(body)
                val title    = doc.optString("title", "")
                val duration = doc.optLong("lengthSeconds", 0L)
                val thumb    = doc.optJSONArray("videoThumbnails")
                    ?.optJSONObject(0)?.optString("url")
                    ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                val formats = doc.optJSONArray("adaptiveFormats") ?: continue

                data class AF(val url: String, val bitrate: Int, val isOpus: Boolean)
                val list = mutableListOf<AF>()
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    if (!f.optString("type").startsWith("audio/")) continue
                    val url = f.optString("url").takeIf { it.isNotEmpty() } ?: continue
                    list.add(AF(url, f.optInt("bitrate", 0),
                        f.optString("type").contains("opus")))
                }
                if (list.isEmpty()) continue

                val best = list.sortedWith(
                    compareByDescending<AF> { if (it.isOpus) 1 else 0 }
                        .thenByDescending { it.bitrate }
                ).first()

                android.util.Log.d("XWare", "Invidious [$base] OK")
                return StreamInfo(best.url, title, duration, thumb)

            } catch (e: Exception) {
                android.util.Log.w("XWare", "Invidious [$base]: ${e.message}")
            }
        }
        return null
    }
}
