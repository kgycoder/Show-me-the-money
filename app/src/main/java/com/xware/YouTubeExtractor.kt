package com.xware

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class StreamInfo(
        val audioUrl: String,
        val title: String,
        val duration: Long,
        val thumbnailUrl: String
    )

    fun extractAudio(videoId: String): StreamInfo? {
        // ANDROID_TESTSUITE: 서명 없이 바로 재생 가능한 URL 반환
        return tryClient(
            videoId,
            clientName    = "ANDROID_TESTSUITE",
            clientVersion = "1.9",
            clientId      = "30",
            apiKey        = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        ) ?: tryClient(
            videoId,
            clientName    = "ANDROID",
            clientVersion = "19.09.37",
            clientId      = "3",
            apiKey        = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        ) ?: tryClient(
            videoId,
            clientName    = "IOS",
            clientVersion = "19.09.3",
            clientId      = "5",
            apiKey        = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"
        )
    }

    private fun tryClient(
        videoId: String,
        clientName: String,
        clientVersion: String,
        clientId: String,
        apiKey: String
    ): StreamInfo? {
        return try {
            val body = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("androidSdkVersion", 30)
                        put("hl", "ko")
                        put("gl", "KR")
                        put("utcOffsetMinutes", 540)
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?key=$apiKey&prettyPrint=false")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent",
                    "com.google.android.youtube/$clientVersion (Linux; U; Android 11) gzip")
                .header("X-YouTube-Client-Name", clientId)
                .header("X-YouTube-Client-Version", clientVersion)
                .header("Content-Type", "application/json")
                .build()

            val resp = client.newCall(req).execute()
            val json = resp.body?.string() ?: return null
            resp.close()

            android.util.Log.d("XWare/Extract", "[$clientName] response length: ${json.length}")

            val doc = JSONObject(json)

            // 재생 가능 여부 확인
            val playability = doc.optJSONObject("playabilityStatus")
            val status = playability?.optString("status") ?: ""
            android.util.Log.d("XWare/Extract", "[$clientName] status: $status")
            if (status == "ERROR" || status == "LOGIN_REQUIRED") return null

            val details = doc.optJSONObject("videoDetails")
            val title = details?.optString("title") ?: ""
            val duration = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
            val thumb = details?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?.let { arr ->
                    if (arr.length() > 0)
                        arr.getJSONObject(arr.length() - 1).optString("url")
                    else null
                } ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            val streaming = doc.optJSONObject("streamingData") ?: return null
            val audioUrl = findBestAudio(streaming)

            if (audioUrl == null) {
                android.util.Log.w("XWare/Extract", "[$clientName] no audio URL found")
                return null
            }

            android.util.Log.d("XWare/Extract", "[$clientName] got audio URL ✓")
            StreamInfo(audioUrl, title, duration, thumb)

        } catch (e: Exception) {
            android.util.Log.e("XWare/Extract", "[$clientName] failed: ${e.message}")
            null
        }
    }

    private fun findBestAudio(streaming: JSONObject): String? {
        // 1순위: adaptiveFormats (오디오 전용, 고품질)
        val adaptive = streaming.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            data class AF(val url: String, val bitrate: Int, val isOpus: Boolean)
            val list = mutableListOf<AF>()

            for (i in 0 until adaptive.length()) {
                val fmt = adaptive.getJSONObject(i)
                val mime = fmt.optString("mimeType")
                if (!mime.startsWith("audio/")) continue

                // signatureCipher가 있으면 건너뜀 (복호화 불가)
                if (fmt.has("signatureCipher") || fmt.has("cipher")) continue

                val url = fmt.optString("url").takeIf { it.isNotEmpty() } ?: continue
                list.add(AF(url, fmt.optInt("bitrate", 0), mime.contains("opus")))
            }

            if (list.isNotEmpty()) {
                return list
                    .sortedWith(
                        compareByDescending<AF> { if (it.isOpus) 1 else 0 }
                            .thenByDescending { it.bitrate }
                    )
                    .first().url
            }
        }

        // 2순위: formats (영상+오디오 혼합, 낮은 화질이지만 재생 가능)
        val formats = streaming.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val fmt = formats.getJSONObject(i)
                if (fmt.has("signatureCipher") || fmt.has("cipher")) continue
                val url = fmt.optString("url").takeIf { it.isNotEmpty() }
                if (url != null) return url
            }
        }

        return null
    }
}
