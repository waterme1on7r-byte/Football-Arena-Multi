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
class DpiProxy(private val fragmentSize: Int, private val dns: SecureDns = SecureDns(), private val tlsRecords: Boolean = true, private val routing: AutoRouting? = null, private val retryHandshake: Boolean = true, private val addressConnect: ((Socket, InetAddress) -> Unit)? = null, private val maxConnections: Int = 64, private val connector: ((String) -> Socket)? = null) {
    private class HostStats {
        val connections = AtomicInteger(); val sent = AtomicInteger(); val replies = AtomicInteger(); val bytes = AtomicLong(); val failures = AtomicInteger()
        @Volatile var error = "None"
        @Volatile var addresses = "Not resolved yet"
        @Volatile var route = "Waiting"
        val attempts = java.util.concurrent.CopyOnWriteArrayList<String>()
    }
    private val hosts = ConcurrentHashMap<String, HostStats>()
    private val alternativeDns = ConcurrentHashMap.newKeySet<String>()
    private val clientHosts = ConcurrentHashMap<Socket, String>()
    private fun noteAttempt(stats: HostStats, message: String) = synchronized(stats.attempts) {
        stats.attempts.add(message)
        while (stats.attempts.size > 8) stats.attempts.removeAt(0)
    }
    fun describeHost(host: String): String {
        val s = hosts[host] ?: return "$host: no tunneled requests yet"
        return "$host\nRoute: ${s.route}\nAddresses: ${s.addresses}\nConnections: ${s.connections.get()} · Fragmented handshakes: ${s.sent.get()}\nServer replies: ${s.replies.get()} · Bytes: ${s.bytes.get()}\nErrors: ${s.failures.get()}\nLast host error: ${s.error}\nAttempts:\n${s.attempts.takeLast(8).joinToString("\n")}"
    }
    private fun recordFailure(client: Socket, message: String) {
        val host = clientHosts[client] ?: "Unknown host"
        failures.incrementAndGet(); lastError = "$host: $message".take(240)
        hosts[host]?.let { it.failures.incrementAndGet(); it.error = message.take(180) }
    }
    private val pool = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(maxConnections)
    @Volatile private var listener: ServerSocket? = null
    @Volatile var lastError: String? = null
        private set
    val connections = AtomicInteger()
    val handshakes = AtomicInteger()
    val failures = AtomicInteger()
    val serverReplies = AtomicInteger()
    val receivedBytes = AtomicLong()
    val dnsResult: String get() = dns.lastResult
    @Volatile var lastHost = "No traffic yet"
        private set

    fun start(): Int {
        val server = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        listener = server
        pool.execute {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    sockets.add(client)
                    // Backpressure instead of dropping CSS/script connections when several panes load.
                    slots.acquire()
                    pool.execute { try { tunnel(client) } catch (e: Exception) { if (!client.isClosed && !server.isClosed) recordFailure(client, "Tunnel: ${e.message}") } finally { clientHosts.remove(client); sockets.remove(client); client.close(); slots.release() } }
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
        clientHosts[client] = host
        val stats = hosts.getOrPut(host) { HostStats() }
        stats.connections.incrementAndGet()
        connections.incrementAndGet(); lastHost = host
        // Accept CONNECT immediately so resolver work does not consume WebView's proxy handshake timeout.
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
        var bypass = routing?.let { it.usesBypass(host) || !it.isRegistered(host) } ?: true
        var records = tlsRecords
        var compatibilityTried = false
        var remote: Socket? = null
        val first = ByteArray(16384)
        var firstSize: Int
        while (true) {
            var attempt: Socket? = null
            try {
                attempt = connector?.invoke(host) ?: connectHost(host)
                sockets.add(attempt); attempt.tcpNoDelay = true; attempt.soTimeout = 5000
                val output = attempt.getOutputStream()
                stats.route = if (!bypass) "Encrypted DNS, unchanged TLS" else if (records) "Encrypted DNS + TLS records" else "Encrypted DNS + TCP split"
                noteAttempt(stats, "${stats.route} → ${attempt.inetAddress.hostAddress}")
                if (!bypass) { output.write(record); output.flush() }
                var offset = if (!bypass) record.size else if (records) 5 else 0
            while (offset < record.size) {
                val n = minOf(fragmentSize, record.size - offset)
                if (records) output.write(byteArrayOf(head[0], head[1], head[2], (n shr 8).toByte(), n.toByte()))
                output.write(record, offset, n); output.flush(); offset += n
                if (offset < record.size) Thread.sleep(2)
            }
            if (bypass) { handshakes.incrementAndGet(); stats.sent.incrementAndGet() }
                firstSize = attempt.getInputStream().read(first)
                if (firstSize < 0) throw java.io.EOFException("Server closed before returning any TLS data")
                while (firstSize < 5) {
                    val n = attempt.getInputStream().read(first, firstSize, 5 - firstSize)
                    if (n < 0) throw java.io.EOFException("Incomplete TLS response header")
                    firstSize += n
                }
                // Some block pages answer an HTTPS socket with plaintext HTTP. A reply alone is not TLS success.
                val responseSize = ((first[3].toInt() and 255) shl 8) or (first[4].toInt() and 255)
                require(first[0].toInt() in 20..23 && first[1].toInt() == 3 && first[2].toInt() in 0..4 && responseSize in 1..18432) { "Non-TLS response on HTTPS connection" }
                // Retry only a ClientHello. Never replay application data or hide TLS alerts.
                remote = attempt
                break
            } catch (e: Exception) {
                noteAttempt(stats, "Failed: ${e.message}")
                attempt?.let { sockets.remove(it); it.close() }
                if (!retryHandshake || client.isClosed || listener?.isClosed != false) throw e
                if (!bypass && (routing?.retryWithBypass(host) == true || routing?.usesBypass(host) == true)) bypass = true
                else if (bypass && !compatibilityTried) { records = !records; compatibilityTried = true }
                else throw e
            }
        }
        val upstreamSocket = requireNotNull(remote)
        try {
            serverReplies.incrementAndGet(); stats.replies.incrementAndGet()
            receivedBytes.addAndGet(firstSize.toLong()); stats.bytes.addAndGet(firstSize.toLong())
            client.getOutputStream().write(first, 0, firstSize)
            val output = upstreamSocket.getOutputStream()
            client.soTimeout = 0
            upstreamSocket.soTimeout = 0
            val reported = AtomicBoolean()
            fun failure(message: String) {
                if (!client.isClosed && listener?.isClosed == false && reported.compareAndSet(false, true)) {
                    recordFailure(client, message)
                }
            }
            if (first[0].toInt() == 21) failure("Server returned a TLS alert; forwarded unchanged")
            pool.execute {
                try {
                    val buffer = ByteArray(16384)
                    val upstream = upstreamSocket.getInputStream()
                    while (true) {
                        val n = upstream.read(buffer)
                        if (n < 0) break
                        receivedBytes.addAndGet(n.toLong())
                        stats.bytes.addAndGet(n.toLong())
                        client.getOutputStream().write(buffer, 0, n)
                    }
                } catch (e: Exception) { failure("Server read: ${e.message}") }
                finally { client.close(); upstreamSocket.close() }
            }
            try { input.copyTo(output) } catch (e: Exception) { if (!client.isClosed && !upstreamSocket.isClosed) failure("Client relay: ${e.message}") }
        } finally { sockets.remove(upstreamSocket); upstreamSocket.close() }
    }

    private fun connectHost(host: String): Socket {
        var error: Exception? = null
        val resolvers = if (host in alternativeDns) listOf(true, false) else listOf(false, true)
        for (alternative in resolvers) {
        val addresses = try { dns.resolve(host, alternative).sortedBy { it is java.net.Inet6Address } }
            catch (e: Exception) { error = e; continue }
        hosts[host]?.addresses = addresses.joinToString { it.hostAddress ?: "?" }
        // Never let an untrusted page use the tunnel to reach local network services.
        val allowed = addresses.filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isMulticastAddress }
        for (address in allowed.take(4)) {
            val socket = Socket(); sockets.add(socket)
            try {
                if (addressConnect != null) addressConnect.invoke(socket, address) else socket.connect(InetSocketAddress(address, 443), 3000)
                if (alternative) alternativeDns.add(host)
                return socket
            }
            catch (e: Exception) { error = e; socket.close(); sockets.remove(socket) }
        }
        }
        throw java.io.IOException("No reachable public address for $host: ${error?.message ?: "DNS returned no public address"}")
    }

    fun stop() {
        listener?.close(); listener = null
        sockets.forEach { try { it.close() } catch (_: Exception) {} }; sockets.clear()
        pool.shutdownNow()
    }
}
