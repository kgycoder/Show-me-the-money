package com.xware

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                .build())
        }.build()

    fun searchYouTube(query: String): JSONArray {
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20240101.00.00")
                    put("hl", "ko"); put("gl", "KR")
                })
            })
            put("query", query)
            put("params", "EgIQAQ==")
        }.toString()

        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search" +
                 "?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", "2.20240101.00.00")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .build()

        val resp = client.newCall(req).execute()
        val json = resp.body?.string() ?: return JSONArray()
        resp.close()
        return parseSearch(json)
    }

    private fun parseSearch(json: String): JSONArray {
        val result = JSONArray()
        try {
            val doc = JSONObject(json)
            val sections = doc.getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer")
                .getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents")

            for (si in 0 until sections.length()) {
                if (result.length() >= 20) break
                val items = sections.getJSONObject(si)
                    .optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents") ?: continue

                for (ii in 0 until items.length()) {
                    if (result.length() >= 20) break
                    val vr = items.getJSONObject(ii).optJSONObject("videoRenderer") ?: continue
                    val id = vr.optString("videoId").takeIf { it.isNotEmpty() } ?: continue
                    val title = vr.optJSONObject("title")?.optJSONArray("runs")
                        ?.optJSONObject(0)?.optString("text") ?: continue
                    val ch = (vr.optJSONObject("ownerText") ?: vr.optJSONObject("shortBylineText"))
                        ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                    val durStr = vr.optJSONObject("lengthText")?.optString("simpleText") ?: ""
                    val dur = parseDur(durStr)
                    if (!isMusic(title, ch, dur)) continue
                    result.put(JSONObject().apply {
                        put("id", id); put("title", title); put("channel", ch)
                        put("dur", dur)
                        put("thumb", "https://i.ytimg.com/vi/$id/mqdefault.jpg")
                    })
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("XWare/API", "parseSearch: ${e.message}")
        }
        return result
    }

    private fun isMusic(title: String, ch: String, dur: Int): Boolean {
        val t = title.lowercase(); val c = ch.lowercase()
        if (c.containsAny("vevo","topic","music","records","entertainment","sound","audio","official")) return true
        if (t.containsAny("official","mv","m/v","music video","audio","lyrics","lyric","visualizer","live","performance","concert")) return true
        if (dur >= 60) return true
        return false
    }

    private fun String.containsAny(vararg tokens: String) = tokens.any { this.contains(it) }

    private fun parseDur(s: String): Int {
        if (s.isEmpty()) return 0
        return try {
            val p = s.split(":")
            when (p.size) {
                3 -> p[0].toInt() * 3600 + p[1].toInt() * 60 + p[2].toInt()
                2 -> p[0].toInt() * 60 + p[1].toInt()
                else -> 0
            }
        } catch (e: Exception) { 0 }
    }

    fun getSuggestions(query: String): JSONArray {
        val url = "https://suggestqueries.google.com/complete/search" +
                  "?client=firefox&ds=yt&q=${enc(query)}&hl=ko"
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        val json = resp.body?.string() ?: return JSONArray()
        resp.close()
        val arr = JSONArray(json)
        val sugs = JSONArray()
        if (arr.length() > 1) {
            val list = arr.getJSONArray(1)
            for (i in 0 until minOf(list.length(), 8)) {
                val s = list.optString(i)
                if (s.isNotEmpty()) sugs.put(s)
            }
        }
        return sugs
    }

    fun fetchLyrics(rawTitle: String, channel: String, ytDuration: Double): JSONArray? {
        val title = cleanTitle(rawTitle)
        val artist = cleanArtist(channel)
        var results = searchLrc("$title $artist")
        if (results.length() == 0) results = searchLrc(title)
        if (results.length() == 0) return null

        data class C(val lrc: String, val dur: Double)
        val cands = mutableListOf<C>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val lrc = item.optString("syncedLyrics").takeIf { it.isNotEmpty() } ?: continue
            var d = lastTs(lrc)
            if (d <= 0) d = item.optDouble("duration", 0.0)
            cands.add(C(lrc, d))
        }
        if (cands.isEmpty()) return null

        val best = if (ytDuration > 0) {
            val wd = cands.filter { it.dur > 0 }
            if (wd.isNotEmpty()) wd.minByOrNull { Math.abs(it.dur - ytDuration) }!!.lrc
            else cands[0].lrc
        } else cands[0].lrc

        return parseLrc(best)
    }

    private fun searchLrc(q: String): JSONArray {
        val resp = client.newCall(
            Request.Builder().url("https://lrclib.net/api/search?q=${enc(q)}")
                .header("Lrclib-Client", "XWare/1.0").build()
        ).execute()
        val json = resp.body?.string() ?: return JSONArray()
        resp.close()
        return try { JSONArray(json) } catch (e: Exception) { JSONArray() }
    }

    private fun lastTs(lrc: String): Double {
        val re = Regex("""^\[(\d+):(\d+)\.(\d+)]""")
        var last = 0.0
        lrc.lines().forEach { line ->
            val m = re.find(line.trim()) ?: return@forEach
            val ms = m.groupValues[3].padEnd(3, '0').take(3)
            val t = m.groupValues[1].toInt() * 60.0 + m.groupValues[2].toInt() + ms.toInt() / 1000.0
            if (t > last) last = t
        }
        return last
    }

    private fun parseLrc(lrc: String): JSONArray {
        data class L(val start: Double, val text: String)
        val re = Regex("""^\[(\d+):(\d+)\.(\d+)](.*)""")
        val list = mutableListOf<L>()
        lrc.lines().forEach { raw ->
            val m = re.find(raw.trim()) ?: return@forEach
            val ms = m.groupValues[3].padEnd(3, '0').take(3)
            val t = m.groupValues[1].toInt() * 60.0 + m.groupValues[2].toInt() + ms.toInt() / 1000.0
            val text = m.groupValues[4].trim()
            if (text.isNotEmpty()) list.add(L(t, text))
        }
        list.sortBy { it.start }
        val result = JSONArray()
        list.forEachIndexed { i, l ->
            val end = if (i + 1 < list.size) list[i + 1].start else l.start + 5.0
            result.put(JSONObject().apply { put("start", l.start); put("end", end); put("text", l.text) })
        }
        return result
    }

    private fun cleanTitle(t: String): String {
        var s = t
        s = s.replace(Regex("""(?i)\((?:official|mv|m/v|video|audio|lyrics?|visualizer|live|performance|hd|4k)[^)]*\)"""), "").trim()
        s = s.replace(Regex("""(?i)\[(?:official|mv|m/v|video|audio|lyrics?|visualizer|live|performance|hd|4k)[^\]]*]"""), "").trim()
        s = s.replace(Regex("""(?i)\s*[-|]\s*(official|mv|lyrics?|audio|video)\s*$"""), "").trim()
        s = s.replace(Regex("""(?i)\s*[\(\[]?feat\..*$"""), "").trim()
        return s
    }

    private fun cleanArtist(c: String): String {
        var s = c
        s = s.replace(Regex("""(?i)\s*[-·]\s*Topic\s*$"""), "").trim()
        s = s.replace(Regex("""(?i)VEVO$"""), "").trim()
        s = s.replace(Regex("""(?i)\s*(Records|Entertainment|Music|Official)\s*$"""), "").trim()
        return s
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
