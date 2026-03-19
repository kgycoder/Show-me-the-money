package com.xware

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent",
                        "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip")
                    .header("Accept-Language", "ko-KR,ko;q=0.9")
                    .build()
            )
        }
        .build()

    data class StreamInfo(
        val audioUrl: String,
        val title: String,
        val duration: Long,
        val thumbnailUrl: String
    )

    fun extractAudio(videoId: String): StreamInfo? {
        return tryAndroid(videoId) ?: tryAndroidMusic(videoId) ?: tryIos(videoId)
    }

    private fun tryAndroid(videoId: String): StreamInfo? =
        tryFetch(videoId, "ANDROID", "19.09.37", "3",
            "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8")

    private fun tryAndroidMusic(videoId: String): StreamInfo? =
        tryFetch(videoId, "ANDROID_MUSIC", "7.27.52", "21",
            "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8")

    private fun tryIos(videoId: String): StreamInfo? =
        tryFetch(videoId, "IOS", "19.09.3", "5",
            "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc")

    private fun tryFetch(
        videoId: String, clientName: String,
        clientVersion: String, clientId: String, apiKey: String
    ): StreamInfo? {
        return try {
            val body = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("hl", "ko")
                        put("gl", "KR")
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?key=$apiKey&prettyPrint=false")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("X-YouTube-Client-Name", clientId)
                .header("X-YouTube-Client-Version", clientVersion)
                .header("Origin", "https://www.youtube.com")
                .build()

            val resp = client.newCall(req).execute()
            val json = resp.body?.string() ?: return null
            resp.close()

            parseResponse(videoId, json)
        } catch (e: Exception) {
            android.util.Log.w("XWare/Extractor", "$clientName failed: ${e.message}")
            null
        }
    }

    private fun parseResponse(videoId: String, json: String): StreamInfo? {
        return try {
            val doc = JSONObject(json)

            val status = doc.optJSONObject("playabilityStatus")?.optString("status") ?: ""
            if (status == "ERROR" || status == "LOGIN_REQUIRED") return null

            val details = doc.optJSONObject("videoDetails")
            val title = details?.optString("title") ?: ""
            val duration = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
            val thumb = details?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?.let { arr -> if (arr.length() > 0) arr.getJSONObject(arr.length() - 1).optString("url") else null }
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            val streaming = doc.optJSONObject("streamingData") ?: return null
            val audioUrl = bestAudioUrl(streaming) ?: return null

            StreamInfo(audioUrl, title, duration, thumb)
        } catch (e: Exception) {
            android.util.Log.e("XWare/Extractor", "parse failed: ${e.message}")
            null
        }
    }

    private fun bestAudioUrl(streaming: JSONObject): String? {
        val adaptive = streaming.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            data class AF(val url: String, val bitrate: Int, val isOpus: Boolean)
            val list = mutableListOf<AF>()
            for (i in 0 until adaptive.length()) {
                val fmt = adaptive.getJSONObject(i)
                if (!fmt.optString("mimeType").startsWith("audio/")) continue
                val url = fmt.optString("url").takeIf { it.isNotEmpty() }
                    ?: decipher(fmt) ?: continue
                list.add(AF(url, fmt.optInt("bitrate", 0), fmt.optString("mimeType").contains("opus")))
            }
            if (list.isNotEmpty()) {
                return list.sortedWith(
                    compareByDescending<AF> { if (it.isOpus) 1 else 0 }.thenByDescending { it.bitrate }
                ).first().url
            }
        }
        val formats = streaming.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val url = formats.getJSONObject(i).optString("url").takeIf { it.isNotEmpty() }
                if (url != null) return url
            }
        }
        return null
    }

    private fun decipher(fmt: JSONObject): String? {
        val cipher = fmt.optString("signatureCipher").takeIf { it.isNotEmpty() }
            ?: fmt.optString("cipher").takeIf { it.isNotEmpty() }
            ?: return null
        return try {
            cipher.split("&").associate {
                val kv = it.split("=", limit = 2)
                URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }["url"]
        } catch (e: Exception) { null }
    }
}
