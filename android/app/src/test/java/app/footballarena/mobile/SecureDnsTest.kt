package app.footballarena.mobile

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class SecureDnsTest {
    private val answer = """{"Status":0,"Answer":[{"type":1,"TTL":60,"data":"104.21.82.180"}]}"""
    @Test fun usesEncryptedAnswerAndCachesIt() {
        var queries = 0
        val dns = SecureDns { url -> assertTrue(url.startsWith("https://")); queries++; answer }
        assertEquals("104.21.82.180", dns.resolve("ardaspor40.top").single().hostAddress)
        dns.resolve("ardaspor40.top")
        assertEquals(1, queries)
    }
    @Test fun resolverFailureTriesIpEndpoint() {
        val dns = SecureDns { url -> if (url.startsWith("https://dns.google/")) throw java.io.IOException("Resolver blocked") else { assertTrue(url.startsWith("https://1.1.1.1/")); answer } }
        assertEquals("104.21.82.180", dns.resolve("ardaspor40.top").single().hostAddress)
    }
    @Test(expected = UnknownHostException::class) fun failsInsteadOfUsingSystemDns() {
        SecureDns { throw java.io.IOException("DoH unavailable") }.resolve("ardaspor40.top")
    }
    @Test(expected = UnknownHostException::class) fun rejectsHostnamesInAddressAnswers() {
        SecureDns { """{"Status":0,"Answer":[{"type":1,"data":"localhost"}]}""" }.resolve("ardaspor40.top")
    }
}
