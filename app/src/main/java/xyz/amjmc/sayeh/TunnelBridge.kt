package xyz.amjmc.sayeh

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tiny SOCKS5 server on 127.0.0.1 that hev-socks5-tunnel talks to.
 *
 * Each SOCKS CONNECT becomes an HTTP CONNECT to the Ceno engine's local proxy
 * with the `X-Ouinet-Raw-Tunnel` header. Our engine patch then opens a plain
 * CONNECT tunnel through a Ceno injector (the same thing Ceno's own private
 * mode does, minus the TLS interception), so the bytes go through untouched.
 */
class TunnelBridge(private val engineHost: String, private val enginePort: Int) {

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "bridge").apply { isDaemon = true } }
    private var server: ServerSocket? = null
    @Volatile private var running = false

    val active = AtomicInteger(0)
    val bytesUp = AtomicLong(0)
    val bytesDown = AtomicLong(0)
    val opened = AtomicLong(0)
    val failed = AtomicLong(0)

    /** Starts listening and returns the local port. */
    fun start(): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress("127.0.0.1", 0), 256)
        server = s
        running = true
        pool.execute {
            while (running) {
                val c = try { s.accept() } catch (_: IOException) { break }
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // greeting: VER NMETHODS METHODS...
            if (cin.read() != 5) return
            val n = cin.read()
            if (n < 0) return
            readFully(cin, n)
            cout.write(byteArrayOf(5, 0)); cout.flush()

            // request: VER CMD RSV ATYP DST.ADDR DST.PORT
            val hdr = readFully(cin, 4)
            val cmd = hdr[1].toInt()
            val host = when (hdr[3].toInt()) {
                1 -> InetAddress.getByAddress(readFully(cin, 4)).hostAddress
                3 -> String(readFully(cin, cin.read()), Charsets.US_ASCII)
                4 -> "[" + (InetAddress.getByAddress(readFully(cin, 16)) as Inet6Address).hostAddress + "]"
                else -> { reply(cout, 8); return }
            }
            val pb = readFully(cin, 2)
            val port = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)

            if (cmd != 1) { reply(cout, 7); return }            // only CONNECT, no UDP
            if (port !in ALLOWED_PORTS) { reply(cout, 2); return } // injector refuses other ports anyway

            val target = "$host:$port"
            val up = Socket()
            upstream = up
            up.tcpNoDelay = true
            up.connect(InetSocketAddress(engineHost, enginePort), 5_000)
            // The engine may wait for an injector before answering.
            up.soTimeout = 60_000
            val uout = up.getOutputStream()
            val uin = up.getInputStream()
            uout.write(
                ("CONNECT $target HTTP/1.1\r\n" +
                 "Host: $target\r\n" +
                 "X-Ouinet-Raw-Tunnel: 1\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            uout.flush()

            val status = readStatus(uin)
            if (status !in 200..299) {
                failed.incrementAndGet()
                Status.log("tunnel $target -> engine said $status")
                reply(cout, 5)
                return
            }
            reply(cout, 0)
            opened.incrementAndGet()

            client.soTimeout = 0
            up.soTimeout = 0
            active.incrementAndGet()
            try {
                val t = Thread({ pipe(cin, uout, bytesUp, up, client) }, "bridge-up")
                t.isDaemon = true
                t.start()
                pipe(uin, cout, bytesDown, client, up)
                t.join(2_000)
            } finally {
                active.decrementAndGet()
            }
        } catch (_: Throwable) {
            // client gave up, engine dropped, timeout — nothing useful to do
        } finally {
            try { client.close() } catch (_: Throwable) {}
            try { upstream?.close() } catch (_: Throwable) {}
        }
    }

    private fun pipe(src: InputStream, dst: OutputStream, counter: AtomicLong, a: Socket, b: Socket) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: Throwable) {
        } finally {
            // half-close what we can, then let the other side finish
            try { a.shutdownOutput() } catch (_: Throwable) {}
            try { b.shutdownInput() } catch (_: Throwable) {}
        }
    }

    /** Reads the HTTP response head byte by byte so no tunnel bytes are swallowed. */
    private fun readStatus(inp: InputStream): Int {
        val sb = StringBuilder()
        var last4 = 0
        while (sb.length < 8192) {
            val b = inp.read()
            if (b < 0) return -1
            sb.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
        }
        val first = sb.lineSequence().firstOrNull() ?: return -1
        return first.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun reply(out: OutputStream, code: Int) {
        try {
            out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
        } catch (_: Throwable) {}
    }

    private fun readFully(inp: InputStream, len: Int): ByteArray {
        if (len < 0) throw IOException("eof")
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(b, off, len - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
        return b
    }

    companion object {
        /** Ports Ceno's injectors accept for CONNECT (ouinet src/injector.cpp). */
        val ALLOWED_PORTS = setOf(80, 443, 8080, 8443)
    }
}
