package app.footballarena.mobile

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DpiProxyTest {
    private fun runTunnel(reply: Boolean, records: Boolean = true, automaticDirect: Boolean = false) {
        val server = ServerSocket(0)
        val worker = Executors.newSingleThreadExecutor()
        val routes = if (automaticDirect) AutoRouting().apply { registerSite("test", "https://test.example/") } else null
        val proxy = DpiProxy(8, tlsRecords = records, routing = routes, retryHandshake = false) { Socket("127.0.0.1", server.localPort) }
        val payload = ByteArray(16) { it.toByte() }
        val task = worker.submit {
            server.accept().use { peer ->
                peer.soTimeout = 4000
                val input = java.io.DataInputStream(peer.getInputStream())
                val reconstructed = java.io.ByteArrayOutputStream()
                repeat(if (records) 2 else 1) {
                    assertEquals(22, input.readUnsignedByte())
                    assertEquals(3, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                    val size = input.readUnsignedShort(); assertEquals(if (records) 8 else 16, size)
                    val bytes = ByteArray(size); input.readFully(bytes); reconstructed.write(bytes)
                }
                assertArrayEquals(payload, reconstructed.toByteArray())
                if (reply) peer.getOutputStream().write(byteArrayOf(22, 3, 3, 0, 1, 0))
            }
        }
        try {
            val port = proxy.start()
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 5000
                val output = client.getOutputStream()
                output.write("CONNECT test.example:443 HTTP/1.1\r\nHost: test.example:443\r\n\r\n".toByteArray())
                val input = client.getInputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) { val b = input.read(); assertTrue(b >= 0); header.append(b.toChar()) }
                assertTrue(header.startsWith("HTTP/1.1 200"))
                output.write(byteArrayOf(22, 3, 1, 0, 16) + payload)
                val response = input.readBytes()
                if (reply) assertArrayEquals(byteArrayOf(22, 3, 3, 0, 1, 0), response) else assertEquals(0, response.size)
            }
            task.get(6, TimeUnit.SECONDS)
            assertEquals(if (automaticDirect) 0 else 1, proxy.handshakes.get())
            assertTrue(proxy.describeHost("test.example").contains("Connections: 1"))
            if (reply) {
                assertEquals(1, proxy.serverReplies.get()); assertEquals(6L, proxy.receivedBytes.get())
                assertEquals("Normal peer EOF must not become Socket closed failure", 0, proxy.failures.get())
            } else {
                assertEquals(1, proxy.failures.get())
                assertTrue(proxy.lastError!!.contains("before returning any TLS data"))
            }
        } finally { proxy.stop(); server.close(); worker.shutdownNow() }
    }

    @Test fun relaysServerReplyAndDoesNotCountNormalClosureAsError() { runTunnel(true) }
    @Test fun reportsUpstreamClosureBeforeReply() { runTunnel(false) }
    @Test fun compatibilityModePreservesOriginalTlsRecord() { runTunnel(true, false) }
    @Test fun automaticDirectDoesNotFragmentClientHandshake() { runTunnel(true, false, true) }

    @Test fun automaticRetriesResetBeforeServerHelloInsideTheSameTunnel() {
        val server = ServerSocket(0)
        val worker = Executors.newSingleThreadExecutor()
        val routes = AutoRouting().apply { registerSite("test", "https://test.example/") }
        val proxy = DpiProxy(8, routing = routes) { Socket("127.0.0.1", server.localPort) }
        val payload = ByteArray(16) { it.toByte() }
        val task = worker.submit {
            repeat(2) { attempt ->
                server.accept().use { peer ->
                    peer.soTimeout = 4000
                    val input = java.io.DataInputStream(peer.getInputStream())
                    val bytes = java.io.ByteArrayOutputStream()
                    repeat(if (attempt == 0) 1 else 2) {
                        assertEquals(22, input.readUnsignedByte()); input.readUnsignedShort()
                        val chunk = ByteArray(input.readUnsignedShort()); input.readFully(chunk); bytes.write(chunk)
                    }
                    assertArrayEquals(payload, bytes.toByteArray())
                    if (attempt == 1) peer.getOutputStream().write(byteArrayOf(22, 3, 3, 0, 1, 0))
                }
            }
        }
        try {
            Socket("127.0.0.1", proxy.start()).use { client ->
                client.soTimeout = 6000
                client.getOutputStream().write("CONNECT test.example:443 HTTP/1.1\r\n\r\n".toByteArray())
                val input = client.getInputStream(); val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) { val b = input.read(); assertTrue(b >= 0); header.append(b.toChar()) }
                client.getOutputStream().write(byteArrayOf(22, 3, 1, 0, 16) + payload)
                assertArrayEquals(byteArrayOf(22, 3, 3, 0, 1, 0), input.readBytes())
            }
            task.get(6, TimeUnit.SECONDS)
            assertTrue(routes.usesBypass("test.example"))
            assertEquals(1, proxy.handshakes.get()); assertEquals(1, proxy.serverReplies.get())
            assertEquals(0, proxy.failures.get())
        } finally { proxy.stop(); server.close(); worker.shutdownNow() }
    }
}
