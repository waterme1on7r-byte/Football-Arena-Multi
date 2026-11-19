package app.footballarena.mobile

import java.net.URI

/** Keep player/CDN domains untouched; match advertising hosts at domain boundaries. */
object AdRules {
    private val hosts = """
        doubleclick.net googlesyndication.com googleadservices.com adservice.google.com imasdk.googleapis.com
        adnxs.com adsrvr.org taboola.com outbrain.com pubmatic.com rubiconproject.com criteo.com criteo.net
        amazon-adsystem.com moatads.com adform.net mgid.com revcontent.com a-ads.com bidvertiser.com
        popads.net popcash.net propellerads.com propu.sh onclickads.net onclkds.com
        profitableratecpm.com highperformanceformat.com effectivegatecpm.com effectiveratecpm.com
        adsterra.com juicyads.com exoclick.com exosrv.com exdynsrv.com trafficjunky.net clickadu.com
        hilltopads.net adcash.com ad-maven.com admaven.com adsco.re yandexadexchange.net an.yandex.ru
    """.trim().split(Regex("\\s+")).toSet()
    private val paths = Regex("(/pagead/|/adserver|/adsbygoogle|/vast[/.?_-]|/preroll|/pre-roll|/popunder|/popads|/ima3\\.js|/ads/(banner|js|video))", RegexOption.IGNORE_CASE)
    fun blocks(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return false
        hosts.any { host == it || host.endsWith(".$it") } || paths.containsMatchIn((uri.rawPath ?: "") + (uri.rawQuery ?: ""))
    }.getOrDefault(false)
}
