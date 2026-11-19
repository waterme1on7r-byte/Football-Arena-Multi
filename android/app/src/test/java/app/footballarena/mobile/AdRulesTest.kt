package app.footballarena.mobile

import org.junit.Assert.*
import org.junit.Test

class AdRulesTest {
    @Test fun bundledHostsRespectDomainBoundariesAndRejectInvalidLines() {
        try {
            AdRules.loadBundledHosts("# fixture\n127.0.0.1 ads.example.net\n0.0.0.0 metrics.example.org\n127.0.0.1 player.example.test extra\n<html>error page</html>")
            assertTrue(AdRules.blocks("https://cdn.ads.example.net/file.js"))
            assertTrue(AdRules.blocks("https://ADS.EXAMPLE.NET./file.js"))
            assertTrue(AdRules.blocks("https://metrics.example.org/pixel"))
            assertFalse(AdRules.blocks("https://notads.example.net/player"))
            assertFalse(AdRules.blocks("https://player.example.test/match.m3u8"))
            assertFalse(AdRules.blocks("ftp://ads.example.net/file"))
        } finally { AdRules.loadBundledHosts("") }
    }
    @Test fun coversDesktopAdvertisingRulesWithoutBlockingPlayers() {
        assertTrue(AdRules.blocks("https://delivery.popads.net/pop.js"))
        assertTrue(AdRules.blocks("https://cdn.example.org/vast/preroll.xml"))
        assertTrue(AdRules.blocks("https://imasdk.googleapis.com/js/sdkloader/ima3.js"))
        assertFalse(AdRules.blocks("https://notpopads.net/player.js"))
        assertFalse(AdRules.blocks("https://cdn.example.org/live/match.m3u8"))
        assertFalse(AdRules.blocks("https://www.extrabet2113.com/tr/int/sport/TV"))
        assertFalse(AdRules.blocks("not a URL"))
    }
}
