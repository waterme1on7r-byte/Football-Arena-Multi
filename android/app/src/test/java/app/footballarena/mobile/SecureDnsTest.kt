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
        assertEquals(2, queries)
    }
    @Test fun keepsIpv6AsFallbackEvenWhenIpv4Exists() {
        val dns = SecureDns { url -> if (url.contains("type=28")) """{"Status":0,"Answer":[{"type":28,"TTL":60,"data":"2606:4700:3034::6815:52b4"}]}""" else answer }
        val results = dns.resolve("ardaspor40.top")
        assertEquals(2, results.size)
        assertTrue(results.any { it is java.net.Inet6Address })
        assertTrue(results.any { it is java.net.Inet4Address })
    }
    @Test fun keepsIpv4IfIpv6LookupFails() {
        val dns = SecureDns { url -> if (url.contains("type=28")) throw java.io.IOException("AAAA lookup failed") else answer }
        assertEquals("104.21.82.180", dns.resolve("ardaspor40.top").single().hostAddress)
    }
    @Test fun resolverFailureTriesIpEndpoint() {
        val dns = SecureDns { url -> if (url.startsWith("https://dns.google/")) throw java.io.IOException("Resolver blocked") else { assertTrue(url.startsWith("https://1.1.1.1/")); answer } }
        assertEquals("104.21.82.180", dns.resolve("ardaspor40.top").single().hostAddress)
    }
    @Test fun alternateResolverDoesNotReusePrimaryCache() {
        var queries = 0
        val dns = SecureDns { url ->
            queries++
            if (url.startsWith("https://dns.google/")) answer
            else """{"Status":0,"Answer":[{"type":1,"TTL":60,"data":"172.67.179.52"}]}"""
        }
        assertEquals("104.21.82.180", dns.resolve("test.example").single().hostAddress)
        assertEquals("172.67.179.52", dns.resolve("test.example", true).single().hostAddress)
        dns.resolve("test.example"); dns.resolve("test.example", true)
        assertEquals(4, queries)
    }
    @Test(expected = UnknownHostException::class) fun failsInsteadOfUsingSystemDns() {
        SecureDns { throw java.io.IOException("DoH unavailable") }.resolve("ardaspor40.top")
    }
    @Test(expected = UnknownHostException::class) fun rejectsHostnamesInAddressAnswers() {
        SecureDns { """{"Status":0,"Answer":[{"type":1,"data":"localhost"}]}""" }.resolve("ardaspor40.top")
    }
}
