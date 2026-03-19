package com.xware

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Piped 공개 인스턴스 목록 (하나 실패하면 다음으로 시도)
    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://piped-api.garudalinux.org",
        "https://api.piped.projectsegfau.lt",
        "https://pipedapi.in.projectsegfau.lt"
    )

    data class StreamInfo(
        val audioUrl: String,
        val title: String,
        val duration: Long,
        val thumbnailUrl: String
    )

    fun extractAudio(videoId: String): StreamInfo? {
        for (instance in pipedInstances) {
            val result = tryPiped(videoId, instance)
            if (result != null) return result
        }
        android.util.Log.e("XWare/Extract", "All Piped instances failed for $videoId")
        return null
    }

    private fun tryPiped(videoId: String, baseUrl: String): StreamInfo? {
        return try {
            val req = Request.Builder()
                .url("$baseUrl/streams/$videoId")
                .header("User-Agent", "XWare/1.0")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                android.util.Log.w("XWare/Extract", "$baseUrl failed: ${resp.code}")
                resp.close()
                return null
            }

            val json = resp.body?.string() ?: return null
            resp.close()

            val doc = JSONObject(json)

            // 오류 응답 체크
            if (doc.has("error")) {
                android.util.Log.w("XWare/Extract", "$baseUrl error: ${doc.optString("error")}")
                return null
            }

            val title = doc.optString("title", "")
            val duration = doc.optLong("duration", 0L)
            val thumbnail = doc.optString("thumbnailUrl",
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")

            // audioStreams 배열에서 최적 오디오 선택
            val audioStreams = doc.optJSONArray("audioStreams") ?: return null
            if (audioStreams.length() == 0) return null

            data class AS(val url: String, val bitrate: Int, val mime: String)
            val list = mutableListOf<AS>()

            for (i in 0 until audioStreams.length()) {
                val s = audioStreams.getJSONObject(i)
                val url = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                val bitrate = s.optInt("bitrate", 0)
                val mime = s.optString("mimeType", "")
                list.add(AS(url, bitrate, mime))
            }

            if (list.isEmpty()) return null

            // opus > aac, bitrate 높은 순
            val best = list.sortedWith(
                compareByDescending<AS> { if (it.mime.contains("opus")) 1 else 0 }
                    .thenByDescending { it.bitrate }
            ).first()

            android.util.Log.d("XWare/Extract",
                "[$baseUrl] ✓ ${best.mime} ${best.bitrate}bps")

            StreamInfo(
                audioUrl     = best.url,
                title        = title,
                duration     = duration,
                thumbnailUrl = thumbnail
            )
        } catch (e: Exception) {
            android.util.Log.e("XWare/Extract", "$baseUrl exception: ${e.message}")
            null
        }
    }
}
