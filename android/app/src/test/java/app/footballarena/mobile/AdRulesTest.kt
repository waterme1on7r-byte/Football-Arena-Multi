package app.footballarena.mobile

import org.junit.Assert.*
import org.junit.Test

class AdRulesTest {
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
