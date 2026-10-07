package xyz.amjmc.sayeh

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Shared connection state between the VPN service and the screen, plus a small log. */
object Status {

    enum class Phase { OFF, STARTING, SEARCHING, ON, STOPPING, ERROR }

    @Volatile var phase: Phase = Phase.OFF
        private set
    /** Short Persian line shown under the button. */
    @Volatile var detail: String = ""
        private set
    @Volatile var startedAt: Long = 0L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val lines = ArrayDeque<String>()
    private var logFile: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        if (logFile == null) logFile = File(ctx.filesDir, "sayeh.log")
    }

    fun set(p: Phase, d: String) {
        phase = p
        detail = d
        if (p == Phase.ON && startedAt == 0L) startedAt = System.currentTimeMillis()
        if (p != Phase.ON) startedAt = 0L
        log("[$p] $d")
        notifyAll()
    }

    fun log(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 400) lines.removeFirst()
        }
        try {
            logFile?.let { f ->
                if (f.length() > 512 * 1024) f.writeText("")
                f.appendText(line + "\n")
            }
        } catch (_: Throwable) {}
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    /** Everything worth sending when something goes wrong: this run plus the saved file. */
    fun fullReport(): String = try {
        logFile?.takeIf { it.exists() }?.readText()?.takeLast(60_000) ?: logText()
    } catch (_: Throwable) { logText() }

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    private fun notifyAll() = main.post { listeners.forEach { it() } }
}
