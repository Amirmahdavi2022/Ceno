package xyz.amjmc.cenoprobe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ie.equalit.ouinet.Config
import ie.equalit.ouinet.OuinetBackground
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Stage-1 probe: run the Ceno (Ouinet) engine inside our own app with Ceno's
 * public network settings, then load blocked sites through it.
 *
 * Direct ("origin") access is switched OFF, so every page that loads came
 * through the Ceno P2P network (bridges -> injector, or other users' caches).
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var startBtn: Button
    private val logBuf = StringBuilder()

    private var background: OuinetBackground? = null
    private var config: Config? = null
    private val frontToken = randomToken()
    @Volatile private var running = false

    private val testSites = listOf(
        "https://www.bbc.com/persian",
        "https://www.youtube.com/",
        "https://x.com/",
        "https://www.instagram.com/",
        "https://telegram.org/",
        "https://en.wikipedia.org/wiki/Iran",
        "https://www.google.com/",
        "https://github.com/",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(dp(16), dp(28), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            text = "Ceno Probe"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            text = "Turn every VPN off, then tap Start. Takes 2–5 minutes."
            textSize = 14f
            setTextColor(Color.parseColor("#9AA4AE"))
            setPadding(0, dp(4), 0, dp(12))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply {
            text = "Start"
            setOnClickListener { startProbe() }
        }
        val copyBtn = Button(this).apply {
            text = "Copy log"
            setOnClickListener { copyLog() }
        }
        row.addView(startBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(copyBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#D7E0E8"))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            gravity = Gravity.START
        }
        scroll = ScrollView(this).apply { addView(logView) }

        root.addView(title)
        root.addView(sub)
        root.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        log("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        log("engine: ouinet-omni 1.6.11 (same as Ceno 2.11.x)")
    }

    // ---------------------------------------------------------------- probe

    private fun startProbe() {
        if (running) return
        running = true
        startBtn.isEnabled = false
        log("---- start ${stamp()}")
        try {
            val cfg = Config.ConfigBuilder(this)
                .setCacheHttpPubKey(CenoNetwork.CACHE_PUB_KEY)
                .setInjectorCredentials(CenoNetwork.INJECTOR_CREDENTIALS)
                .setInjectorTlsCert(CenoNetwork.INJECTOR_TLS_CERT)
                .setTlsCaCertStorePath(CenoNetwork.CA_STORE_ASSET)
                .setCacheType(CenoNetwork.CACHE_TYPE)
                .setListenOnTcp("127.0.0.1:0")
                .setFrontEndEp("127.0.0.1:0")
                .setFrontEndAccessToken(frontToken)
                // What the official Ceno build does for Iran: extra BitTorrent
                // bootstrap node IR_1, and plain system DNS instead of DoH.
                .setBtBootstrapExtras(setOf(CenoNetwork.BT_BOOTSTRAP_IR_1))
                .setDnsProtocols(setOf("plain"))
                // Force every request over the Ceno network: no direct access.
                .setDisableOriginAccess(true)
                .build()
            config = cfg
            // Must be built on the main thread (it creates a Handler).
            val bg = OuinetBackground.Builder(this)
                .setOuinetConfig(cfg)
                .restartOnConnectivityChange(false)
                .build()
            background = bg
            bg.start()
            log("engine starting…")
        } catch (t: Throwable) {
            log("ENGINE START FAILED: ${t.javaClass.simpleName}: ${t.message}")
            done()
            return
        }
        Thread { runProbe() }.start()
    }

    private fun runProbe() {
        val bg = background ?: return done()
        val t0 = System.currentTimeMillis()

        // 1) wait for the engine to bind its ports
        var proxyEp = bg.getProxyEndpoint()
        var frontEp = bg.getFrontendEndpoint()
        while ((proxyEp == null || frontEp == null) && elapsed(t0) < 60_000) {
            Thread.sleep(1000)
            proxyEp = bg.getProxyEndpoint()
            frontEp = bg.getFrontendEndpoint()
        }
        if (proxyEp == null || frontEp == null) {
            log("engine never opened its ports (state=${bg.getState()}) — STOP")
            return done()
        }
        log("engine up in ${secs(t0)}s  proxy=$proxyEp  front=$frontEp")

        // 2) wait for injectors (up to 4 min), printing status as it changes
        var lastLine = ""
        var lastPrint = 0L
        log("bootstrap extra: IR_1 ${CenoNetwork.BT_BOOTSTRAP_IR_1}, DNS: plain")
        var ready = false
        while (elapsed(t0) < 240_000) {
            val st = status(frontEp.toString())
            if (st != null) {
                ready = st.optBoolean("injector_ready", false)
                val line = "state=${st.optString("state")} injector_ready=$ready " +
                    "injector_peers=${st.optInt("injector_peers_n", -1)} " +
                    "udp_reachable=${st.optString("udp_world_reachable", "?")}"
                if (line != lastLine || elapsed(lastPrint) >= 30_000) {
                    log("[${secs(t0)}s] $line"); lastLine = line; lastPrint = System.currentTimeMillis()
                }
                if (ready) break
            } else {
                log("[${secs(t0)}s] status not available yet (engine state=${bg.getState()})")
            }
            Thread.sleep(5000)
        }
        log(if (ready) "injector READY after ${secs(t0)}s" else "injector NOT ready after ${secs(t0)}s — testing anyway")

        // 3) load sites through the engine
        val ssl = try { sslTrustingOuinet() } catch (t: Throwable) {
            log("could not load engine CA: ${t.message}"); null
        }
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyEp.getAddress(), proxyEp.getPort()))
        var ok = 0
        for (site in testSites) {
            val r = fetch(site, proxy, ssl, private = true)
            if (r.startsWith("OK")) ok++
            log("$site\n    $r")
        }
        log("RESULT: $ok/${testSites.size} sites loaded with direct access OFF (total ${secs(t0)}s)")

        // 4) one public (shared-cache) request, the way Ceno's public mode does it
        log("public-mode check:\n    " + fetch("https://www.bbc.com/persian", proxy, ssl, private = false))
        log("---- done ${stamp()}  (tap Copy log and send it)")
        done()
    }

    private fun fetch(url: String, proxy: Proxy, ssl: SSLContext?, private: Boolean): String {
        val t = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection(proxy) as HttpURLConnection
            if (conn is HttpsURLConnection && ssl != null) conn.sslSocketFactory = ssl.socketFactory
            conn.connectTimeout = 45_000
            conn.readTimeout = 90_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Android 14; Mobile; rv:140.0) Gecko/140.0 Firefox/140.0")
            if (private) conn.setRequestProperty("X-Ouinet-Private", "true")
            else conn.setRequestProperty("X-Ouinet-Group", URL(url).host.removePrefix("www.") + URL(url).path.trimEnd('/'))
            val code = conn.responseCode
            val src = conn.getHeaderField("X-Ouinet-Source") ?: "-"
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            var bytes = 0L
            stream?.use { s ->
                val buf = ByteArray(16 * 1024)
                while (bytes < 300_000) { val n = s.read(buf); if (n < 0) break; bytes += n }
            }
            val kbps = if (elapsed(t) > 0) bytes * 8 / elapsed(t) else 0
            val tag = if (code in 200..399) "OK  " else "FAIL"
            "$tag http=$code via=$src ${secs(t)}s ${bytes / 1024}KB ~${kbps}kbps"
        } catch (e: Throwable) {
            "FAIL ${secs(t)}s ${e.javaClass.simpleName}: ${e.message?.take(140)}"
        } finally {
            conn?.disconnect()
        }
    }

    private fun status(frontEp: String): JSONObject? = try {
        val c = URL("http://$frontEp/api/status").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        c.setRequestProperty("X-Ouinet-Front-End-Token", frontToken)
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        JSONObject(body)
    } catch (_: Throwable) { null }

    /** Trust the normal system CAs plus the engine's own CA (it re-signs HTTPS locally). */
    private fun sslTrustingOuinet(): SSLContext {
        val caPath = config!!.caRootCertPath
        val cf = CertificateFactory.getInstance("X.509")
        val ouinetCa = FileInputStream(File(caPath)).use { cf.generateCertificate(it) as X509Certificate }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setCertificateEntry("ouinet-ca", ouinetCa)
        val sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val own = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(ks) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val combined = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                sys.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try { own.checkServerTrusted(chain, authType) }
                catch (_: Exception) { sys.checkServerTrusted(chain, authType) }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = sys.acceptedIssuers + own.acceptedIssuers
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(combined), SecureRandom()) }
    }

    // ---------------------------------------------------------------- helpers

    private fun done() {
        running = false
        main.post { startBtn.isEnabled = true }
    }

    private fun log(msg: String) {
        main.post {
            logBuf.append(msg).append('\n')
            logView.text = logBuf
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ceno-probe", logBuf.toString()))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        try { background?.stop() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun elapsed(t: Long) = System.currentTimeMillis() - t
    private fun secs(t: Long) = String.format(Locale.US, "%.1f", elapsed(t) / 1000.0)
    private fun stamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun randomToken(): String {
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val r = SecureRandom()
        return (1..27).map { pool[r.nextInt(pool.size)] }.joinToString("")
    }
}
