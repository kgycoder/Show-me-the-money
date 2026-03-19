package com.xware

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var view: LinearLayout? = null
    private var tvPrev: TextView? = null
    private var tvActive: TextView? = null
    private var tvNext: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_START         = "com.xware.OVERLAY_START"
        const val ACTION_STOP          = "com.xware.OVERLAY_STOP"
        const val ACTION_UPDATE_LYRICS = "com.xware.UPDATE_LYRICS"
        const val EXTRA_PREV           = "prev"
        const val EXTRA_ACTIVE         = "active"
        const val EXTRA_NEXT           = "next"
        private const val CH_ID        = "xware_overlay"
        private const val NID          = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NID, buildNote())
        buildView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> show()
            ACTION_STOP  -> { hide(); stopForeground(true); stopSelf() }
            ACTION_UPDATE_LYRICS -> update(
                intent.getStringExtra(EXTRA_PREV)   ?: "",
                intent.getStringExtra(EXTRA_ACTIVE) ?: "",
                intent.getStringExtra(EXTRA_NEXT)   ?: ""
            )
        }
        return START_STICKY
    }

    private fun buildView() {
        view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        tvPrev   = tv(15f, 0.32f, false).also { view!!.addView(it) }
        tvActive = tv(21f, 0.96f, true).also  { view!!.addView(it) }
        tvNext   = tv(15f, 0.28f, false).also { view!!.addView(it) }
    }

    private fun tv(sp: Float, alpha: Float, bold: Boolean) = TextView(this).apply {
        textSize = sp; setTextColor(Color.WHITE); this.alpha = alpha
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setShadowLayer(12f, 0f, 2f, Color.BLACK)
        gravity = Gravity.CENTER_HORIZONTAL
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(3), 0, dp(3)) }
    }

    private fun show() {
        handler.post {
            if (view?.windowToken != null) return@post
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm?.defaultDisplay?.getRealMetrics(dm)

            val p = WindowManager.LayoutParams(
                dm.widthPixels, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.START; x = 0; y = dp(168) }

            runCatching { wm?.addView(view, p) }
        }
    }

    private fun hide() {
        handler.post { runCatching { if (view?.windowToken != null) wm?.removeView(view) } }
    }

    private fun update(prev: String, active: String, next: String) {
        handler.post { anim(tvPrev, prev); anim(tvActive, active); anim(tvNext, next) }
    }

    private fun anim(tv: TextView?, text: String) {
        if (tv == null || tv.text.toString() == text) return
        val target = when (tv) { tvActive -> 0.96f; tvPrev -> 0.32f; else -> 0.28f }
        if (text.isEmpty()) {
            tv.animate().alpha(0f).setDuration(300).start()
            tv.postDelayed({ tv.text = "" }, 320)
        } else if (tv.text.isEmpty()) {
            tv.text = text; tv.alpha = 0f
            tv.animate().alpha(target).setDuration(300).start()
        } else {
            tv.animate().alpha(0f).setDuration(180).withEndAction {
                tv.text = text; tv.alpha = 0f
                tv.animate().alpha(target).setDuration(220).start()
            }.start()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "가사 오버레이",
                NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNote(): Notification =
        NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("X-WARE").setContentText("가사 오버레이 실행 중")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() { hide(); super.onDestroy() }
}
