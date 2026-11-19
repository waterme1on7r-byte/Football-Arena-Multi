package app.footballarena.mobile

import org.junit.Assert.*
import org.junit.Test

class AutoRoutingTest {
    @Test fun startsDirectRetriesOnceAndKeepsSitesIndependent() {
        val routes = AutoRouting { 1000L }
        routes.registerSite("inatbox", "https://old.example/")
        routes.registerSite("ardaspor", "https://other.example/")
        assertFalse(routes.usesBypass("old.example"))
        assertTrue(routes.retryWithBypass("old.example"))
        assertTrue(routes.usesBypass("old.example"))
        assertFalse(routes.retryWithBypass("old.example"))
        assertFalse(routes.usesBypass("other.example"))
    }
    @Test fun addressChangeResetsLearnedRouteAndRemovesOldHosts() {
        val routes = AutoRouting { 1000L }
        routes.registerSite("inatbox", "https://old.example/")
        routes.registerHost("inatbox", "player.example")
        routes.retryWithBypass("old.example")
        assertTrue(routes.usesBypass("player.example"))
        routes.registerSite("inatbox", "https://new.example/")
        assertFalse(routes.usesBypass("new.example"))
        assertFalse(routes.retryWithBypass("old.example"))
        assertFalse(routes.usesBypass("player.example"))
    }
    @Test fun learnedRouteExpires() {
        var time = 1000L
        val routes = AutoRouting { time }
        routes.registerSite("inatbox", "https://site.example/")
        routes.retryWithBypass("site.example")
        time += 4 * 60 * 60 * 1000L + 1
        assertFalse(routes.usesBypass("site.example"))
        assertTrue(routes.retryWithBypass("site.example"))
    }
}
