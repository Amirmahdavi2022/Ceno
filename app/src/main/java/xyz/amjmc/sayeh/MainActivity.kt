package xyz.amjmc.sayeh

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val easeOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    private lateinit var power: PowerButton
    private lateinit var phaseText: TextView
    private lateinit var detailText: TextView
    private lateinit var timerText: TextView
    private lateinit var crashCard: LinearLayout

    private val onStatus: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() { renderTimer(); main.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Status.init(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(36), dp(20), dp(20))
        }

        root.addView(text("سایه", 26f, Color.WHITE, bold = true))
        root.addView(text("بدون سرور، از راه شبکه‌ی همتابه‌همتای Ceno", 14f, MUTED).apply {
            setPadding(0, dp(4), 0, 0)
        })

        crashCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#2A1C1C"), dp(14).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
            addView(text("دفعه‌ی قبل برنامه یهو بسته شد. گزارشش رو کپی کن و بفرست تا درستش کنم.", 13f,
                Color.parseColor("#F3C9C4")))
            addView(pill("کپی گزارش خرابی") { copyCrash() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        root.addView(crashCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        // centre block
        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        power = PowerButton(this).apply { setOnClickListener { toggle() } }
        centre.addView(power, LinearLayout.LayoutParams(dp(210), dp(210)))
        phaseText = text("", 22f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(22), 0, 0)
        }
        detailText = text("", 14f, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), 0) }
        timerText = text("", 13f, MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0); typeface = Typeface.MONOSPACE
        }
        centre.addView(phaseText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        centre.addView(detailText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        centre.addView(timerText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(FrameLayout(this).apply {
            addView(centre, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // bottom row
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(pill("کپی گزارش") { copyLog() })
        row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(pill("کانال تلگرام") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHANNEL))) } catch (_: Throwable) {}
        })
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(text("نسخه ${BuildConfig.VERSION_NAME}", 11f, Color.parseColor("#4E5864")).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onStart() {
        super.onStart()
        Status.listen(onStatus)
        render()
        crashCard.visibility = if (crashFile().exists()) View.VISIBLE else View.GONE
        main.post(tick)
    }

    override fun onStop() {
        Status.unlisten(onStatus)
        main.removeCallbacks(tick)
        super.onStop()
    }

    // ------------------------------------------------------------------ actions

    private fun toggle() {
        when (Status.phase) {
            Status.Phase.OFF, Status.Phase.ERROR -> {
                val ask = VpnService.prepare(this)
                if (ask != null) startActivityForResult(ask, REQ_VPN) else SayehVpnService.start(this)
            }
            Status.Phase.STOPPING -> {}
            else -> SayehVpnService.stop(this)
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) SayehVpnService.start(this)
            else toast("بدون اجازه‌ی وی‌پی‌ان نمی‌تونم وصل کنم")
        }
    }

    private fun copyLog() {
        val crash = crashFile().takeIf { it.exists() }?.readText()?.let { "\n\n=== last crash ===\n$it" } ?: ""
        copy("sayeh-log", Status.fullReport() + crash)
        toast("گزارش کپی شد")
    }

    private fun copyCrash() {
        val f = crashFile()
        copy("sayeh-crash", (f.takeIf { it.exists() }?.readText() ?: "") + "\n\n=== log ===\n" + Status.fullReport())
        f.delete()
        crashCard.animate().alpha(0f).setDuration(180).setInterpolator(easeOut)
            .withEndAction { crashCard.visibility = View.GONE; crashCard.alpha = 1f }.start()
        toast("گزارش خرابی کپی شد")
    }

    // ------------------------------------------------------------------- render

    private fun render() {
        power.setPhase(Status.phase)
        phaseText.text = when (Status.phase) {
            Status.Phase.OFF -> "خاموش"
            Status.Phase.STARTING -> "در حال روشن شدن…"
            Status.Phase.SEARCHING -> "در حال اتصال…"
            Status.Phase.ON -> "وصلی"
            Status.Phase.STOPPING -> "در حال قطع…"
            Status.Phase.ERROR -> "وصل نشد"
        }
        detailText.text = when (Status.phase) {
            Status.Phase.OFF -> if (Status.detail.isBlank() || Status.detail == "قطع شد") "برای وصل شدن دکمه رو بزن" else Status.detail
            Status.Phase.ON -> "سرعت پایینه ولی بدون سرور کار می‌کنه"
            else -> Status.detail
        }
        renderTimer()
    }

    private fun renderTimer() {
        val t = Status.startedAt
        timerText.text = if (Status.phase == Status.Phase.ON && t > 0) {
            val s = (System.currentTimeMillis() - t) / 1000
            String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        } else ""
    }

    // ------------------------------------------------------------------ helpers

    private fun crashFile() = File(filesDir, SayehApp.CRASH_FILE)

    private fun copy(label: String, s: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, s))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.parseColor("#D7DEE6"))
        gravity = Gravity.CENTER
        background = rounded(Color.parseColor("#1B2027"), dp(22).toFloat())
        setPadding(dp(18), dp(11), dp(18), dp(11))
        isClickable = true
        setOnClickListener { onClick() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(160).setInterpolator(easeOut).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(easeOut).start()
            }
            false
        }
    }

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_VPN = 1
        private val BG = Color.parseColor("#0E1116")
        private val MUTED = Color.parseColor("#8A949F")
        private const val CHANNEL = "https://t.me/parsv2r"
    }
}
