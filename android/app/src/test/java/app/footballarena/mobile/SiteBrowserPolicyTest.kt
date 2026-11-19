package app.footballarena.mobile

import org.junit.Assert.*
import org.junit.Test

class SiteBrowserPolicyTest {
    @Test fun desktopAgentUsesInstalledChromeVersionAndNoAndroidIdentity() {
        val agent = SiteBrowserPolicy.desktopAgent("Mozilla/5.0 (Linux; Android 13; wv) Chrome/130.0.6723.86 Mobile Safari/537.36")
        assertTrue(agent.contains("Windows NT 10.0")); assertTrue(agent.contains("Chrome/130.0.6723.86"))
        assertFalse(agent.contains("Android")); assertFalse(agent.contains("Mobile"))
    }
    @Test fun mobileLinksRetainTheirNavigationAndEncodedParameters() {
        assertEquals("https://www.example.com/tr/int/sport/TV?return=a%26b#live", SiteBrowserPolicy.desktopUrl("extratv", "https://m.example.com/tr/int/sport/TV?return=a%26b#live", "https://www.example.com/tr"))
    }
    @Test fun profileFollowsUpdatedDomainWithoutChangingUnrelatedHosts() {
        assertEquals("https://www.new-domain.test/help", SiteBrowserPolicy.desktopUrl("extratv", "https://m.new-domain.test/help", "https://www.new-domain.test/TV"))
        assertEquals("https://m.external.test/help", SiteBrowserPolicy.desktopUrl("extratv", "https://m.external.test/help", "https://www.new-domain.test/TV"))
        assertEquals("https://m.example.com/help", SiteBrowserPolicy.desktopUrl("inatbox", "https://m.example.com/help", "https://www.example.com/"))
        assertFalse(SiteBrowserPolicy.usesDesktop("inatbox"))
    }
}
