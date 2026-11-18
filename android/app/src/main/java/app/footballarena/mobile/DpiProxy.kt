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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** App-local CONNECT tunnel. TLS stays encrypted; no certificates or interception. */
class DpiProxy(private val fragmentSize: Int, private val connector: ((String) -> Socket)? = null) {
    private val pool = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(32)
    @Volatile private var listener: ServerSocket? = null
    @Volatile var lastError: String? = null
        private set
    val connections = AtomicInteger()
    val handshakes = AtomicInteger()
    val failures = AtomicInteger()
    val serverReplies = AtomicInteger()
    val receivedBytes = AtomicLong()
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
                    pool.execute { try { tunnel(client) } catch (e: Exception) { if (!client.isClosed && !server.isClosed) { failures.incrementAndGet(); lastError = "Tunnel: ${e.message}".take(160) } } finally { sockets.remove(client); client.close(); slots.release() } }
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
        val remote = connector?.invoke(host) ?: connectHost(host)
        sockets.add(remote)
        try {
            remote.tcpNoDelay = true
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            // Read the initial TLS record as a unit, then split its bytes into writes.
            val inputTls = input
            val head = ByteArray(5)
            var read = 0
            while (read < 5) { val n = inputTls.read(head, read, 5 - read); if (n < 0) return; read += n }
            if (head[0].toInt() != 22) throw IllegalArgumentException("Client did not send a TLS handshake")
            val size = ((head[3].toInt() and 255) shl 8) or (head[4].toInt() and 255)
            if (size !in 1..18432) throw IllegalArgumentException("Invalid TLS record size")
            val record = ByteArray(5 + size); head.copyInto(record)
            read = 5
            while (read < record.size) { val n = inputTls.read(record, read, record.size - read); if (n < 0) return; read += n }
            val output = remote.getOutputStream()
            var offset = 5
            while (offset < record.size) {
                val n = minOf(fragmentSize, record.size - offset)
                output.write(byteArrayOf(head[0], head[1], head[2], (n shr 8).toByte(), n.toByte()))
                output.write(record, offset, n); output.flush(); offset += n
                if (offset < record.size) Thread.sleep(2)
            }
            handshakes.incrementAndGet()
            client.soTimeout = 0
            remote.soTimeout = 15000
            val reported = AtomicBoolean()
            fun failure(message: String) {
                if (!client.isClosed && listener?.isClosed == false && reported.compareAndSet(false, true)) {
                    failures.incrementAndGet(); lastError = message.take(160)
                }
            }
            pool.execute {
                var response = false
                try {
                    val buffer = ByteArray(16384)
                    val upstream = remote.getInputStream()
                    while (true) {
                        val n = upstream.read(buffer)
                        if (n < 0) { if (!response) failure("Server closed before returning any TLS data"); break }
                        if (!response) {
                            response = true; serverReplies.incrementAndGet(); remote.soTimeout = 0
                            if (buffer[0].toInt() == 21) failure("Server returned a TLS alert after fragmentation")
                        }
                        receivedBytes.addAndGet(n.toLong())
                        client.getOutputStream().write(buffer, 0, n)
                    }
                } catch (e: Exception) { failure("Server read: ${e.message}") }
                finally { client.close(); remote.close() }
            }
            try { input.copyTo(output) } catch (e: Exception) { if (!client.isClosed && !remote.isClosed) failure("Client relay: ${e.message}") }
        } finally { sockets.remove(remote); remote.close() }
    }

    private fun connectHost(host: String): Socket {
        val addresses = InetAddress.getAllByName(host).sortedBy { it is java.net.Inet6Address }
        // Never let an untrusted page use the tunnel to reach local network services.
        val allowed = addresses.filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isMulticastAddress }
        var error: Exception? = null
        for (address in allowed.take(4)) {
            val socket = Socket(); sockets.add(socket)
            try { socket.connect(InetSocketAddress(address, 443), 5000); return socket }
            catch (e: Exception) { error = e; socket.close(); sockets.remove(socket) }
        }
        throw java.io.IOException("No reachable public address for $host: ${error?.message ?: "DNS returned no public address"}")
    }

    fun stop() {
        listener?.close(); listener = null
        sockets.forEach { try { it.close() } catch (_: Exception) {} }; sockets.clear()
        pool.shutdownNow()
    }
}
