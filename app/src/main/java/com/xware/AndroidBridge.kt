package com.xware

import android.content.Intent
import android.provider.Settings
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AndroidBridge(
    private val activity: MainActivity,
    private val webView: android.webkit.WebView
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api = ApiService()

    init {
        activity.startService(Intent(activity, AudioPlayerService::class.java))
        scope.launch {
            // 서비스 시작 대기
            repeat(40) {
                if (AudioPlayerService.instance != null) return@repeat
                delay(100)
            }
            val svc = AudioPlayerService.instance ?: return@launch
            svc.onReady = {
                send(JSONObject().apply {
                    put("type", "playerState"); put("state", "ready")
                    put("duration", svc.getDuration())
                })
            }
            svc.onStateChanged = { state ->
                send(JSONObject().apply {
                    put("type", "playerState"); put("state", state)
                    put("isPlaying", svc.isPlaying())
                })
            }
            svc.onTimeUpdate = { cur, dur ->
                send(JSONObject().apply {
                    put("type", "playerTime"); put("cur", cur); put("dur", dur)
                })
            }
            svc.onError = { code ->
                send(JSONObject().apply {
                    put("type", "playerError"); put("code", code)
                })
            }
        }
    }

    @JavascriptInterface
    fun postMessage(json: String) {
        scope.launch {
            try {
                val msg = JSONObject(json)
                val type = msg.optString("type")
                val id = msg.optString("id", "0")
                when (type) {
                    "search"      -> doSearch(msg.optString("query"), id)
                    "suggest"     -> doSuggest(msg.optString("query"), id)
                    "fetchLyrics" -> doLyrics(
                        msg.optString("title"), msg.optString("channel"),
                        msg.optDouble("duration", 0.0), id)
                    "ytLoad"  -> svcIntent(AudioPlayerService.ACTION_LOAD) {
                        putExtra(AudioPlayerService.EXTRA_VIDEO_ID, msg.optString("videoId")) }
                    "ytPlay"  -> svcIntent(AudioPlayerService.ACTION_PLAY)
                    "ytPause" -> svcIntent(AudioPlayerService.ACTION_PAUSE)
                    "ytSeek"  -> svcIntent(AudioPlayerService.ACTION_SEEK) {
                        putExtra(AudioPlayerService.EXTRA_SEEK_MS, (msg.optDouble("seconds", 0.0) * 1000).toLong()) }
                    "ytVolume" -> svcIntent(AudioPlayerService.ACTION_VOL) {
                        putExtra(AudioPlayerService.EXTRA_VOLUME, (msg.optDouble("volume", 100.0) / 100.0).toFloat()) }
                    "overlayMode"   -> handleOverlay(msg.optBoolean("active", false))
                    "overlayLyrics" -> handleLyricsOverlay(
                        msg.optString("prev"), msg.optString("active"), msg.optString("next1"))
                }
            } catch (e: Exception) {
                android.util.Log.e("XWare/Bridge", e.message ?: "error")
            }
        }
    }

    @JavascriptInterface fun exitApp() { activity.runOnUiThread { activity.finish() } }
    @JavascriptInterface fun requestOverlayPermission() { activity.runOnUiThread { activity.requestOverlayPermission() } }
    @JavascriptInterface fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(activity)

    private suspend fun doSearch(query: String, id: String) {
        val r = JSONObject().apply { put("type", "searchResult"); put("id", id) }
        try {
            val t = withContext(Dispatchers.IO) { api.searchYouTube(query) }
            r.put("success", true); r.put("tracks", t)
        } catch (e: Exception) {
            r.put("success", false); r.put("error", e.message ?: "오류")
            r.put("tracks", org.json.JSONArray())
        }
        activity.sendToWebView(r.toString())
    }

    private suspend fun doSuggest(query: String, id: String) {
        val r = JSONObject().apply { put("type", "suggestResult"); put("id", id) }
        try {
            val s = withContext(Dispatchers.IO) { api.getSuggestions(query) }
            r.put("success", true); r.put("suggestions", s)
        } catch (e: Exception) {
            r.put("success", false); r.put("suggestions", org.json.JSONArray())
        }
        activity.sendToWebView(r.toString())
    }

    private suspend fun doLyrics(title: String, channel: String, dur: Double, id: String) {
        val r = JSONObject().apply { put("type", "lyricsResult"); put("id", id) }
        try {
            val lines = withContext(Dispatchers.IO) { api.fetchLyrics(title, channel, dur) }
            if (lines != null) { r.put("success", true); r.put("lines", lines) }
            else { r.put("success", false); r.put("lines", org.json.JSONArray()) }
        } catch (e: Exception) {
            r.put("success", false); r.put("lines", org.json.JSONArray())
        }
        activity.sendToWebView(r.toString())
    }

    private fun svcIntent(action: String, extra: (Intent.() -> Unit)? = null) {
        val i = Intent(activity, AudioPlayerService::class.java).apply { this.action = action }
        extra?.invoke(i)
        activity.startService(i)
    }

    private fun handleOverlay(active: Boolean) {
        val i = Intent(activity, OverlayService::class.java)
        if (active) {
            if (!Settings.canDrawOverlays(activity)) { activity.requestOverlayPermission(); return }
            i.action = OverlayService.ACTION_START
        } else i.action = OverlayService.ACTION_STOP
        activity.startService(i)
    }

    private fun handleLyricsOverlay(prev: String, active: String, next: String) {
        activity.startService(Intent(activity, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_LYRICS
            putExtra(OverlayService.EXTRA_PREV, prev)
            putExtra(OverlayService.EXTRA_ACTIVE, active)
            putExtra(OverlayService.EXTRA_NEXT, next)
        })
    }

    private fun send(obj: JSONObject) { activity.sendToWebView(obj.toString()) }
    fun destroy() { scope.cancel() }
}
