package xyz.amjmc.sayeh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import ie.equalit.ouinet.Config
import ie.equalit.ouinet.OuinetBackground
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom

/**
 * Runs the Ceno engine and routes the whole phone through it.
 *
 * Order matters: the engine must find an injector BEFORE the tun comes up,
 * otherwise the user sees "connected" while nothing actually passes.
 */
class SayehVpnService : VpnService() {

    private val main = Handler(Looper.getMainLooper())
    private var engine: OuinetBackground? = null
    private var bridge: TunnelBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    @Volatile private var cancelled = false
    private val frontToken = randomToken()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Status.init(this)
        when (intent?.action) {
            ACTION_STOP -> { shutdown("قطع شد"); return START_NOT_STICKY }
            else -> begin()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // another VPN app took over, or the user switched us off in settings
        shutdown("یک وی‌پی‌ان دیگر جایش را گرفت")
    }

    override fun onDestroy() {
        if (Status.phase != Status.Phase.OFF && Status.phase != Status.Phase.ERROR) shutdown("قطع شد")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ start

    private fun begin() {
        if (worker != null) return
        cancelled = false
        goForeground("در حال روشن شدن…")
        Status.set(Status.Phase.STARTING, "روشن کردن موتور…")
        Status.log("device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, app ${BuildConfig.VERSION_NAME}")

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
                .setBtBootstrapExtras(setOf(CenoNetwork.BT_BOOTSTRAP_IR_1))
                .setDnsProtocols(setOf("plain"))
                .setDisableOriginAccess(true)
                .build()
            // must be built on the main thread (it creates a Handler) — we are on it
            val bg = OuinetBackground.Builder(this)
                .setOuinetConfig(cfg)
                .restartOnConnectivityChange(false)
                .build()
            engine = bg
            bg.start()
        } catch (t: Throwable) {
            fail("موتور روشن نشد", t)
            return
        }

        worker = Thread({ connect() }, "sayeh-connect").also { it.start() }
    }

    private fun connect() {
        val bg = engine ?: return
        val t0 = System.currentTimeMillis()
        try {
            // 1) engine opens its local ports
            var proxy = bg.getProxyEndpoint()
            var front = bg.getFrontendEndpoint()
            while ((proxy == null || front == null) && since(t0) < 60_000) {
                if (cancelled) return
                Thread.sleep(500)
                proxy = bg.getProxyEndpoint(); front = bg.getFrontendEndpoint()
            }
            if (proxy == null || front == null) {
                return fail("موتور بالا نیامد (state=${bg.getState()})", null)
            }
            Status.log("engine up in ${since(t0) / 1000}s proxy=$proxy")

            // 2) find an injector through the Ceno network
            Status.set(Status.Phase.SEARCHING, "دنبال مسیر می‌گردم… (معمولاً زیر یک دقیقه)")
            updateNotification("دنبال مسیر…")
            var ready = false
            var lastPeers = -2
            while (since(t0) < SEARCH_LIMIT_MS) {
                if (cancelled) return
                val st = status(front.toString())
                if (st != null) {
                    ready = st.optBoolean("injector_ready", false)
                    val peers = st.optInt("injector_peers_n", -1)
                    if (peers != lastPeers) {
                        Status.log("search ${since(t0) / 1000}s state=${st.optString("state")} peers=$peers")
                        lastPeers = peers
                    }
                    if (ready) break
                }
                Thread.sleep(2000)
            }
            if (!ready) {
                return fail("مسیری پیدا نشد. اینترنتت رو چک کن و دوباره بزن.", null)
            }
            Status.log("injector ready after ${since(t0) / 1000}s")
            if (cancelled) return

            // 3) local bridge, then the tun
            val br = TunnelBridge(proxy.getAddress(), proxy.getPort())
            val socksPort = br.start()
            bridge = br

            val conf = File(filesDir, "tunnel.yml")
            conf.writeText(hevConfig(socksPort))

            val fd = Builder()
                .setSession("Sayeh")
                .setMtu(MTU)
                .addAddress(TUN_V4, 32)
                .addRoute("0.0.0.0", 0)
                .addAddress(TUN_V6, 128)
                .addRoute("::", 0)
                .addDnsServer(DNS_V4)
                .addDisallowedApplication(packageName) // the engine itself must not loop into the tun
                .setConfigureIntent(openAppIntent())
                .also { if (Build.VERSION.SDK_INT >= 29) it.setMetered(false) }
                .establish()
                ?: return fail("اجازه‌ی وی‌پی‌ان داده نشده", null)
            tun = fd
            if (!TProxyService.TProxyStartService(conf.absolutePath, fd.fd)) {
                return fail("تونل داخلی بالا نیامد", null)
            }
            // hev exits its thread right away if it rejects the config; give it a moment
            Thread.sleep(400)
            if (!TProxyService.TProxyIsRunning()) {
                return fail("تونل داخلی بلافاصله بسته شد", null)
            }

            Status.set(Status.Phase.ON, "وصلی")
            updateNotification("وصل")
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            fail("خطای غیرمنتظره", t)
        } finally {
            worker = null
        }
    }

    private fun hevConfig(socksPort: Int) = """
        tunnel:
          mtu: $MTU
          ipv4: $TUN_V4
          ipv6: '$TUN_V6'
        socks5:
          port: $socksPort
          address: 127.0.0.1
          udp: 'tcp'
        mapdns:
          address: $DNS_V4
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000
        misc:
          task-stack-size: 81920
          connect-timeout: 70000
          read-write-timeout: 300000
          log-file: stderr
          log-level: warn
    """.trimIndent() + "\n"

    // ------------------------------------------------------------------- stop

    private fun fail(msg: String, t: Throwable?) {
        if (t != null) Status.log("ERROR $msg: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(2000)}")
        teardown()
        Status.set(Status.Phase.ERROR, msg)
        main.post { stopForegroundCompat(); stopSelf() }
    }

    private fun shutdown(msg: String) {
        if (Status.phase == Status.Phase.OFF) { stopSelf(); return }
        Status.set(Status.Phase.STOPPING, "در حال قطع…")
        Thread({
            teardown()
            Status.set(Status.Phase.OFF, msg)
            main.post { stopForegroundCompat(); stopSelf() }
        }, "sayeh-stop").start()
    }

    @Synchronized
    private fun teardown() {
        cancelled = true
        worker?.interrupt()
        try { if (tun != null) TProxyService.TProxyStopService() } catch (t: Throwable) { Status.log("hev stop: ${t.message}") }
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
        bridge?.let {
            Status.log("session: opened=${it.opened.get()} failed=${it.failed.get()} up=${it.bytesUp.get() / 1024}KB down=${it.bytesDown.get() / 1024}KB")
            it.stop()
        }
        bridge = null
        try { engine?.stop() } catch (t: Throwable) { Status.log("engine stop: ${t.message}") }
        engine = null
    }

    // ------------------------------------------------------------ notification

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "اتصال", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) = main.post {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text)) } catch (_: Throwable) {}
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SayehVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("سایه")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, "قطع", stop).build())
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    // ---------------------------------------------------------------- helpers

    private fun status(frontEp: String): JSONObject? = try {
        val c = URL("http://$frontEp/api/status").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        c.setRequestProperty("X-Ouinet-Front-End-Token", frontToken)
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        JSONObject(body)
    } catch (_: Throwable) { null }

    private fun since(t: Long) = System.currentTimeMillis() - t

    private fun randomToken(): String {
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val r = SecureRandom()
        return (1..27).map { pool[r.nextInt(pool.size)] }.joinToString("")
    }

    companion object {
        const val ACTION_START = "xyz.amjmc.sayeh.START"
        const val ACTION_STOP = "xyz.amjmc.sayeh.STOP"
        private const val CHANNEL = "conn"
        private const val NOTIF_ID = 7
        private const val MTU = 8500
        private const val TUN_V4 = "198.18.0.1"
        private const val TUN_V6 = "fc00::1"
        private const val DNS_V4 = "198.18.0.2"
        private const val SEARCH_LIMIT_MS = 4 * 60_000L

        fun start(ctx: Context) {
            val i = Intent(ctx, SayehVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SayehVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
