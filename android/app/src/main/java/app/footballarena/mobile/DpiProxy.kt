package app.footballarena.mobile

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/** App-local CONNECT tunnel. TLS stays encrypted; no certificates or interception. */
class DpiProxy(private val fragmentSize: Int) {
    private val pool = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(32)
    @Volatile private var listener: ServerSocket? = null
    @Volatile var lastError: String? = null
        private set
    val connections = AtomicInteger()
    val handshakes = AtomicInteger()
    val failures = AtomicInteger()
    @Volatile var lastHost = "No traffic yet"
        private set

    fun start(): Int {
        val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        listener = server
        pool.execute {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    if (!slots.tryAcquire()) { client.close(); continue }
                    sockets.add(client)
                    pool.execute { try { tunnel(client) } catch (e: Exception) { failures.incrementAndGet(); lastError = e.message?.take(120) } finally { sockets.remove(client); client.close(); slots.release() } }
                } catch (_: Exception) { break }
            }
        }
        return server.localPort
    }

    private fun tunnel(client: Socket) {
        client.soTimeout = 15000
        val input = client.getInputStream()
        val header = ByteArrayOutputStream()
        while (header.size() < 16384) {
            val b = input.read(); if (b < 0) return
            header.write(b)
            val a = header.toByteArray()
            if (a.size >= 4 && a.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
        }
        val request = header.toString("US-ASCII").lineSequence().first().split(' ')
        if (request.size != 3 || request[0] != "CONNECT") { client.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray()); return }
        val authority = request[1]
        val split = authority.lastIndexOf(':')
        if (split < 1 || authority.substring(split + 1) != "443") return
        val host = authority.substring(0, split).removeSurrounding("[", "]")
        connections.incrementAndGet(); lastHost = host
        val addresses = InetAddress.getAllByName(host)
        // Never let an untrusted page use the tunnel to reach local network services.
        val address = addresses.firstOrNull { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isMulticastAddress } ?: return
        val remote = Socket()
        sockets.add(remote)
        try {
            remote.connect(InetSocketAddress(address, 443), 12000)
            remote.tcpNoDelay = true
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            // Read the initial TLS record as a unit, then split its bytes into writes.
            val head = ByteArray(5)
            var read = 0
            while (read < 5) { val n = input.read(head, read, 5 - read); if (n < 0) return; read += n }
            if (head[0].toInt() != 22) return
            val size = ((head[3].toInt() and 255) shl 8) or (head[4].toInt() and 255)
            if (size !in 1..18432) return
            val record = ByteArray(5 + size); head.copyInto(record)
            read = 5
            while (read < record.size) { val n = input.read(record, read, record.size - read); if (n < 0) return; read += n }
            val output = remote.getOutputStream()
            var offset = 5
            while (offset < record.size) {
                val n = minOf(fragmentSize, record.size - offset)
                // Separate TLS records, not merely socket writes which TCP can coalesce.
                output.write(byteArrayOf(head[0], head[1], head[2], (n shr 8).toByte(), n.toByte()))
                output.write(record, offset, n); output.flush(); offset += n
                if (offset < record.size) Thread.sleep(2)
            }
            handshakes.incrementAndGet()
            client.soTimeout = 0
            pool.execute { try { remote.getInputStream().copyTo(client.getOutputStream()) } catch (_: Exception) {} finally { client.close(); remote.close() } }
            try { input.copyTo(output) } finally { remote.close() }
        } finally { sockets.remove(remote); remote.close() }
    }

    fun stop() {
        listener?.close(); listener = null
        sockets.forEach { try { it.close() } catch (_: Exception) {} }; sockets.clear()
        pool.shutdownNow()
    }
}
