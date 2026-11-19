package app.footballarena.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import android.widget.GridLayout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import androidx.webkit.ProxyController
import androidx.webkit.ProxyConfig
import androidx.webkit.WebViewFeature

private data class Site(val id: String, val name: String, val url: String, val quick: Boolean = false, val custom: Boolean = false)

/** Android phone and Android TV shell. Chat is intentionally omitted on both form factors. */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("football-arena", MODE_PRIVATE) }
    private val contentBlocker by lazy { ContentBlocker(applicationContext) }
    private val adSkipScript by lazy { assets.open("adskip.js").bufferedReader().use { it.readText() } }
    private val io = Executors.newSingleThreadExecutor()
    private val sites = mutableListOf<Site>()
    private lateinit var listBar: LinearLayout
    private lateinit var stage: GridLayout
    private lateinit var root: LinearLayout
    private var fullVideo: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private val pageClient by lazy { makePageClient() }
    private lateinit var browser: WebView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private var selected = ""
    private var selectedKey = ""
    private var layoutCount = 0
    private val layoutButtons = mutableListOf<Button>()
    private val addresses = mutableMapOf<WebView, EditText>()
    private val historyButtons = mutableMapOf<WebView, Pair<Button, Button>>()
    private var mainPane = ""
    private var dpiProxy: DpiProxy? = null
    private var dpiState = "Off"
    private val bypassPages = mutableSetOf<WebView>()
    private val loadErrors = mutableMapOf<WebView, String>()
    private val errorLabels = mutableMapOf<WebView, TextView>()
    private var lastPageError = "None"
    private lateinit var dpiButton: Button
    private lateinit var quickCount: TextView
    private lateinit var quickButton: LinearLayout
    private val brandTypeface by lazy {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) Typeface.Builder(assets, "fonts/big_shoulders_display.ttf").setFontVariationSettings("'wght' 900").build()
            else Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }.getOrElse { Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    }
    private val pages = linkedMapOf<String, WebView>()
    private val paneViews = linkedMapOf<String, LinearLayout>()
    private val mainButtons = mutableMapOf<String, Button>()
    private val pageSites = mutableMapOf<String, Site>()
    private val automaticRoutes = AutoRouting()
    private val viewSites = java.util.concurrent.ConcurrentHashMap<WebView, String>()
    private val safetyWarnings = mutableSetOf<WebView>()
    private var routingGeneration = 0
    private var routingReady = false
    private val pendingNavigations = linkedMapOf<WebView, String>()
    private val desktopRedirects = mutableMapOf<WebView, Pair<String, Long>>()
    private fun routingMode() = prefs.getString("dpiMode", "auto") ?: "auto"
    private fun setDpiEnabled(enabled: Boolean) {
        val current = routingMode()
        if (enabled == (current != "off")) return
        val remembered = prefs.getString("dpiEnabledMode", "auto").takeIf { it == "auto" || it == "always" } ?: "auto"
        val edit = prefs.edit().putString("dpiMode", if (enabled) remembered else "off")
        if (!enabled) edit.putString("dpiEnabledMode", current)
        edit.apply()
        updateDpi(true)
    }
    private var leanback = false
    private val bg = Color.rgb(11, 15, 20)
    private val panel = Color.rgb(18, 25, 35)
    private val gold = Color.rgb(246, 196, 81)
    private val text = Color.rgb(233, 237, 242)
    private val contentScripts = mutableMapOf<WebView, androidx.webkit.ScriptHandler>()
    private val resourceErrors = java.util.concurrent.ConcurrentHashMap<WebView, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStrings.init(this)
        contentBlocker.installServiceWorkerBlocking()
        leanback = packageManager.hasSystemFeature("android.software.leanback")
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        layoutCount = prefs.getInt("layout", 0).coerceIn(0, 4)
        loadSites()
        buildUi()
        if (!prefs.getBoolean("languageChosen", false)) languageDialog(true)
        else if (prefs.contains("passHash")) unlock() else startSession()
    }
    private fun languageDialog(firstLaunch: Boolean = false) {
        var chosen = AppStrings.language
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(this).apply { tag = "user-content"; text = "Football Arena için dil seçin.\nSelect a language for Football Arena."; setTextColor(Color.rgb(150,167,186)); textSize = 14f; setPadding(0, 0, 0, dp(20)) })
        val options = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val choices = mutableListOf<Button>()
        for ((value, label) in listOf("tr" to "🇹🇷  Türkçe", "en" to "🇺🇸  English")) {
            val b = button(label, value == chosen) {
                chosen = value
                choices.forEachIndexed { i, choice -> val active = (if (i == 0) "tr" else "en") == chosen; choice.background = controlBackground(active); choice.setTextColor(if (active) bg else text) }
            }.apply { tag = "user-content"; setSingleLine(true) }
            choices.add(b); options.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        }
        body.addView(options)
        val d = ArenaDialog.Builder(this).setTitle("DİL / LANGUAGE").setView(body).setCancelable(!firstLaunch)
            .setPositiveButton("Continue") { _, _ ->
                AppStrings.language = chosen
                prefs.edit().putString("language", chosen).putBoolean("languageChosen", true).apply()
                AppStrings.apply(root)
                if (firstLaunch) { if (prefs.contains("passHash")) unlock() else startSession() }
            }
        if (!firstLaunch) d.setNegativeButton("Cancel", null)
        d.show()
    }
    private fun startSession() {
        updateDpi(false)
        val previous = prefs.getString("last", "") ?: ""
        if (prefs.getBoolean("restore", true)) {
            val previousSite = sites.firstOrNull { it.id == prefs.getString("lastSiteId", "") }
            if (previousSite != null) openSite(previousSite)
            else if (previous.startsWith("https://")) openUrl(previous)
        }
        refreshLinks(false)
    }
    private fun digestPass(code: String, salt: String): String {
        val spec = javax.crypto.spec.PBEKeySpec(code.toCharArray(), android.util.Base64.decode(salt, 0), 100000, 256)
        return try { android.util.Base64.encodeToString(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded, 2) } finally { spec.clearPassword() }
    }
    private fun verifyPass(code: String): Boolean {
        if (!code.matches(Regex("[0-9]{4}"))) return false
        return java.security.MessageDigest.isEqual(digestPass(code, prefs.getString("passSalt", "")!!).toByteArray(), prefs.getString("passHash", "")!!.toByteArray())
    }
    private fun unlock() {
        val input = EditText(this).apply { inputType = 18; hint = "4-digit passcode" }
        val dialog = ArenaDialog.Builder(this).setTitle("Football Arena").setMessage("Enter your passcode").setView(input).setCancelable(false).setPositiveButton("Unlock", null).create()
        var tries = 0
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            if (verifyPass(input.text.toString())) { dialog.dismiss(); startSession() }
            else { tries++; input.text.clear(); input.error = "Incorrect passcode"; if (tries % 5 == 0) { dialog.getButton(-1).isEnabled = false; input.postDelayed({ dialog.getButton(-1).isEnabled = true }, 30000) } }
        } }; dialog.show()
    }
    private fun changePass() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val current = EditText(this).apply { hint = "Current code"; inputType = 18 }
        val next = EditText(this).apply { hint = "New 4-digit code (blank removes it)"; inputType = 18 }
        if (prefs.contains("passHash")) box.addView(current)
        box.addView(next)
        val d = ArenaDialog.Builder(this).setTitle("Passcode").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        d.setOnShowListener { d.getButton(-1).setOnClickListener {
            if (prefs.contains("passHash") && !verifyPass(current.text.toString())) { current.error = "Incorrect code"; return@setOnClickListener }
            val code = next.text.toString()
            if (code.isNotEmpty() && !code.matches(Regex("[0-9]{4}"))) { next.error = "Use exactly four digits"; return@setOnClickListener }
            val editor = prefs.edit()
            if (code.isEmpty()) editor.remove("passSalt").remove("passHash") else {
                val salt = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
                val encoded = android.util.Base64.encodeToString(salt, 2)
                editor.putString("passSalt", encoded).putString("passHash", digestPass(code, encoded))
            }
            editor.apply(); d.dismiss()
        } }; d.show()
    }

    private fun loadSites() {
        val saved = prefs.getString("sites", null)
        if (saved != null) try { decodeSites(JSONArray(saved)).forEach { sites.add(it) } } catch (_: Exception) {}
        if (sites.isEmpty()) {
            try { decodeSites(JSONObject(assets.open("sites.json").bufferedReader().readText()).optJSONArray("sites") ?: JSONArray()).forEach { sites.add(it) } }
            catch (_: Exception) { }
        }
    }

    private fun decodeSites(a: JSONArray): List<Site> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val url = o.optString("url")
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
        Site(o.optString("id", "site-$i"), o.optString("name", "Site"), url, o.optBoolean("quick"), o.optBoolean("custom"))
    }

    private fun saveSites() {
        sites.forEach { automaticRoutes.registerSite(it.id, it.url) }
        val a = JSONArray()
        sites.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("quick", it.quick).put("custom", it.custom)) }
        prefs.edit().putString("sites", a.toString()).apply()
        renderSiteBar()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        if (android.os.Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val wide = resources.configuration.screenWidthDp >= 900
        val toolbar = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(38,33,24), bg, bg))
        }
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, dp(16), 0) }
        brand.addView(ImageView(this).apply { setImageResource(app.footballarena.mobile.R.drawable.arena_mark); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(if (wide) 40 else 32), dp(if (wide) 40 else 32)))
        title = object : TextView(this) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w,h,oldw,oldh)
                paint.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(Color.rgb(255,227,154), gold, Color.WHITE), floatArrayOf(0f,.55f,1f), android.graphics.Shader.TileMode.CLAMP)
            }
        }.apply { tag = "user-content"; text = "Football Arena"; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, 0, 0); setTextColor(gold); textSize = if (wide) 40f else 32f; typeface = brandTypeface; includeFontPadding = false; letterSpacing = .03f; setSingleLine(true) }
        brand.addView(title); toolbar.addView(brand)
        val tools = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        dpiButton = headerButton("● DPI") { dpiQuickMenu(it) }
        tools.addView(dpiButton)
        val segment = LinearLayout(this).apply { background = controlBackground(false); clipToOutline = true }
        listOf("Auto", "1", "2", "3", "4").forEachIndexed { n, label ->
            val b = headerButton(label, n == layoutCount) { setLayout(n) }.apply { layoutParams = LinearLayout.LayoutParams(dp(if (n == 0) 80 else 30), dp(if (leanback) 44 else 36)); setPadding(dp(6), 0, dp(6), 0) }
            b.background = layoutBackground(n == layoutCount)
            b.setTextColor(if (n == layoutCount) bg else Color.rgb(134,150,168))
            layoutButtons.add(b); segment.addView(b)
        }
        tools.addView(segment)
        tools.addView(headerButton("Close all") { pages.keys.toList().forEach { selectedKey = it; closePane() } })
        tools.addView(headerButton("⚙ Settings") { settingsDialog() })
        tools.addView(headerButton("Add site", true) { addSite() })
        toolbar.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(tools) }, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(toolbar)
        root.addView(View(this).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(gold, Color.rgb(34,48,66), bg)) }, LinearLayout.LayoutParams(-1, dp(1)))
        val siteRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFocusable = false }
        listBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), dp(8), dp(2)) }
        scroll.addView(listBar)
        siteRow.addView(scroll, LinearLayout.LayoutParams(0, dp(if (leanback) 52 else 44), 1f))
        quickButton = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0); background = tabBackground(true, false)
            isClickable = true; isFocusable = true; setOnClickListener { quickAccess(it) }
        }
        fun quickLabel(value: String, size: Float) = TextView(this).apply { text = value; setTextColor(Color.rgb(255,227,154)); textSize = size; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }
        quickButton.addView(quickLabel("ϟ", 18f), LinearLayout.LayoutParams(dp(18), -1))
        quickButton.addView(quickLabel("Quick access", 12f).apply { setPadding(dp(5), 0, dp(6), 0) })
        quickCount = TextView(this).apply { textSize = 11f; setTextColor(bg); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; minWidth = dp(18); background = android.graphics.drawable.GradientDrawable().apply { setColor(gold); cornerRadius = dp(12).toFloat() }; setPadding(dp(4), 0, dp(4), 0) }
        quickButton.addView(quickCount, LinearLayout.LayoutParams(-2, dp(18)))
        quickButton.addView(quickLabel("⌄", 16f).apply { setPadding(dp(6), 0, 0, 0) })
        siteRow.addView(quickButton, LinearLayout.LayoutParams(-2, dp(if (leanback) 44 else 36)).apply { marginStart = dp(10) })
        root.addView(siteRow)
        browser = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            configureBrowser(this)
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webViewClient = pageClient
            webChromeClient = videoClient(this)
            setDownloadListener { url, _, _, _, _ -> try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) { toast("Cannot open this download") } }
        }
        buildStage(root)
    }

    private fun makePageClient(): WebViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    bypassPages.remove(view)
                    addresses[view]?.let { if (!it.hasFocus()) it.setText(url) }
                    loadErrors.remove(view); errorLabels[view]?.visibility = View.GONE
                    safetyWarnings.remove(view)
                    android.net.Uri.parse(url).host?.let { host -> viewSites[view]?.let { automaticRoutes.registerHost(it, host) } }
                    if (view === browser) { status.text = "Loading…"; paintDpiStatus() }
                    for (delay in listOf(250L, 1000L, 3000L)) view.postDelayed({
                        if (!isFinishing && viewSites.containsKey(view) && view !in safetyWarnings) runAutoSkip(view)
                    }, delay)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url.toString()
                    if (contentBlocker.blocksNavigation(u)) return true
                    val desktop = desktopUrl(view, u)
                    if (request.isForMainFrame && request.method == "GET" && desktop != u) {
                        val previous = desktopRedirects[view]
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (previous?.first != desktop || now - previous.second > 10000) {
                            desktopRedirects[view] = desktop to now
                            loadPage(view, desktop)
                            return true
                        }
                    }
                    request.url.host?.let { host -> viewSites[view]?.let { automaticRoutes.registerHost(it, host) } }
                    return !(u.startsWith("https://") || u.startsWith("http://"))
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    request.url.host?.let { host -> viewSites[view]?.let { automaticRoutes.registerHost(it, host) } }
                    return contentBlocker.intercept(request.url.toString())
                }
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    historyButtons[view]?.let { it.first.isEnabled = view.canGoBack(); it.second.isEnabled = view.canGoForward() }
                    addresses[view]?.let { if (!it.hasFocus()) it.setText(url) }
                    if (viewSites[view]?.startsWith("custom-") == true) renderSiteBar()
                    if (view === browser) {
                        selected = url
                        prefs.edit().putString("last", url).putString("lastSiteId", viewSites[view] ?: "").apply()
                        status.text = if (view in safetyWarnings) "Security warning" else if (loadErrors.containsKey(view)) "Page failed · see pane" else "Ready"
                    }
                    if (!loadErrors.containsKey(view) && view !in safetyWarnings) runAutoSkip(view)
                }
                override fun onPageCommitVisible(view: WebView, url: String) {
                    if (!loadErrors.containsKey(view) && view !in safetyWarnings && url.startsWith("https://") && dpiProxy != null && (routingMode() == "always" || android.net.Uri.parse(url).host?.let { automaticRoutes.usesBypass(it) } == true)) bypassPages.add(view)
                    paintDpiStatus()
                    if (!loadErrors.containsKey(view) && view !in safetyWarnings) runAutoSkip(view)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) resourceErrors[view] = "${request.url}\n${error.description}"
                    if (request.isForMainFrame && error.errorCode == ERROR_UNSAFE_RESOURCE) {
                        safetyWarnings.add(view)
                        bypassPages.remove(view); paintDpiStatus()
                        if (view === browser) status.text = "Security warning"
                        return
                    }
                    if (view in safetyWarnings) return // Keep WebView's security interstitial visible.
                    val connectionFailure = error.errorCode in setOf(ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_IO, ERROR_TIMEOUT) || error.description.toString() in setOf("net::ERR_CONNECTION_RESET", "net::ERR_EMPTY_RESPONSE", "net::ERR_CONNECTION_CLOSED")
                    if (request.isForMainFrame && request.method == "GET" && request.url.scheme == "https" && connectionFailure && view !in safetyWarnings && routingMode() == "auto" && dpiProxy != null) {
                        if (retrySecureRoute(view, request.url.toString())) return
                    }
                    if (request.isForMainFrame) pageFailed(view, request.url.toString(), "WebView ${error.errorCode}: ${error.description}")
                }
                override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
                    safetyWarnings.add(view)
                    bypassPages.remove(view); paintDpiStatus()
                    loadErrors[view] = AppStrings.t("Safe Browsing blocked this page. This is a security warning, not a DPI failure.") + "\n${request.url} (type $threatType)"
                    if (view === browser) status.text = "Security warning"
                    if (request.isForMainFrame && threatType == SAFE_BROWSING_THREAT_PHISHING) {
                        safeBrowsingChoice(view, request.url.toString(), callback)
                    } else callback.showInterstitial(true)
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) pageFailed(view, request.url.toString(), "HTTP ${response.statusCode}: ${response.reasonPhrase}")
                    else resourceErrors[view] = "${request.url}\nHTTP ${response.statusCode}: ${response.reasonPhrase}"
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                    // Reject the untrusted connection. A poisoned direct DNS answer can have a block-page certificate;
                    // try a fresh encrypted-DNS connection once, with normal certificate validation still enabled.
                    if ((view.url == error.url || addresses[view]?.text?.toString() == error.url) && retrySecureRoute(view, error.url)) return
                    if (view.url == error.url) pageFailed(view, error.url, "Certificate validation failed (${error.primaryError}). Connection cancelled.")
                    else resourceErrors[view] = "${error.url}\nCertificate validation failed (${error.primaryError})"
                }
    }
    private fun safeBrowsingChoice(page: WebView, url: String, callback: SafeBrowsingResponse): ArenaDialog {
        return ArenaDialog.Builder(this).setTitle("Deceptive site warning")
            .setMessage(AppStrings.t("Android Safe Browsing flagged this address as deceptive. Continue only if you trust the site. Opening once does not mark it safe or disable protection for other pages.") + "\n\n$url")
            .setCancelable(false)
            .setNegativeButton("Back to safety") { _, _ -> callback.backToSafety(true) }
            .setPositiveButton("Open once") { _, _ ->
                loadErrors.remove(page); errorLabels[page]?.visibility = View.GONE
                callback.proceed(true)
            }.show()
    }
    private fun retrySecureRoute(view: WebView, url: String): Boolean {
        if (routingMode() != "auto" || dpiProxy == null || view in safetyWarnings || !url.startsWith("https://")) return false
        val host = android.net.Uri.parse(url).host ?: return false
        viewSites[view]?.let { automaticRoutes.registerHost(it, host) }
        if (!automaticRoutes.retryWithBypass(host)) return false
        bypassPages.remove(view); paintDpiStatus()
        if (view === browser) status.text = "Retrying with bypass…"
        view.post { if (!isFinishing && viewSites.containsKey(view) && view !in safetyWarnings) loadPage(view, url) }
        return true
    }
    private fun pageFailed(view: WebView, url: String, message: String) {
        bypassPages.remove(view); paintDpiStatus()
        lastPageError = "$url\n${AppStrings.t(message)}"
        loadErrors[view] = lastPageError
        errorLabels[view]?.apply { text = "Couldn’t load this page\n\n$lastPageError\n\nUse the pane’s refresh button to retry."; visibility = View.VISIBLE }
        if (view === browser) status.text = "Page failed · see pane"
    }

    private fun buildStage(root: LinearLayout) {
        stage = GridLayout(this).apply { setBackgroundColor(bg); useDefaultMargins = false; setPadding(dp(14), dp(2), dp(14), dp(10)) }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(Color.rgb(34,48,66)) }, LinearLayout.LayoutParams(-1, dp(1)))
        val footer = FrameLayout(this).apply { setPadding(dp(14), dp(4), dp(14), dp(4)); setBackgroundColor(bg) }
        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f; text = "Ready" }
        status.gravity = Gravity.END or Gravity.CENTER_VERTICAL; status.maxWidth = dp(300); status.setSingleLine(true); status.ellipsize = android.text.TextUtils.TruncateAt.END
        footer.addView(button("Update links") { refreshLinks(true) }, FrameLayout.LayoutParams(-2, dp(38), Gravity.START or Gravity.CENTER_VERTICAL))
        footer.addView(status, FrameLayout.LayoutParams(-2, dp(32), Gravity.END or Gravity.CENTER_VERTICAL))
        footer.addView(TextView(this).apply { text = "h o w l . d e v"; setTextColor(gold); textSize = 12f; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-2, dp(32), Gravity.CENTER))
        root.addView(footer)
        setContentView(root)
        AppStrings.watch(root)
        renderSiteBar()
    }

    private fun renderSiteBar() {
        if (!::listBar.isInitialized) return
        listBar.removeAllViews()
        sites.filter { !it.quick }.forEach { s ->
            val isOpen = pages.containsKey(s.id)
            val chip = LinearLayout(this).apply {
                tag = "user-content"; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(2), 0)
                background = tabBackground(isOpen, s.id == selectedKey); setAddStatesFromChildren(true); isSelected = isOpen
            }
            val name = headerButton(s.name) { openSite(s) }.apply {
                setTextColor(if (isOpen) gold else this@MainActivity.text); textSize = if (leanback) 14f else 11f; typeface = Typeface.DEFAULT_BOLD; maxWidth = dp(180)
                setBackgroundColor(Color.TRANSPARENT); setPadding(dp(2), 0, dp(4), 0)
                setCompoundDrawablesWithIntrinsicBounds(SiteInitialBadge(s.name, dp(18)), null, null, null); compoundDrawablePadding = dp(5)
                contentDescription = "${s.name}, ${if (isOpen) "open" else "closed"}${if (s.id == selectedKey) ", focused" else ""}"
                setOnLongClickListener { editSite(s, chip); true }
            }
            chip.addView(name, LinearLayout.LayoutParams(-2, dp(if (leanback) 44 else 36)))
            chip.addView(headerButton("⋮") { editSite(s, it) }.apply {
                contentDescription = AppStrings.t("Options") + " · " + s.name; textSize = 18f; setTextColor(Color.rgb(134,150,168)); setBackgroundColor(Color.TRANSPARENT); setPadding(0,0,0,0)
            }, LinearLayout.LayoutParams(dp(18), dp(if (leanback) 44 else 36)))
            listBar.addView(chip, LinearLayout.LayoutParams(-2, dp(if (leanback) 46 else 38)).apply { marginEnd = dp(5) })
        }
        pages.filterKeys { key -> sites.none { it.id == key } }.forEach { (key, page) ->
            val url = page.url?.takeIf { it.startsWith("http") } ?: pendingNavigations[page] ?: pageSites[key]?.url ?: return@forEach
            val label = android.net.Uri.parse(url).host ?: url
            val chip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = controlBackground(true); tag = "user-content" }
            chip.addView(headerButton(label) { selectedKey = key; browser = page; renderPages(); renderSiteBar() }.apply { setTextColor(bg); setBackgroundColor(Color.TRANSPARENT); maxWidth = dp(180) })
            chip.addView(headerButton("+") { saveTemporarySite(key, url) }.apply { contentDescription = AppStrings.t("Save site"); setTextColor(bg); setBackgroundColor(Color.TRANSPARENT) })
            listBar.addView(chip, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(5) })
        }
        if (::quickCount.isInitialized) {
            val count = sites.count { it.quick }; quickCount.text = count.toString()
            quickButton.contentDescription = AppStrings.t("Quick access") + " · " + count
        }
        if (::browser.isInitialized) paintDpiStatus()
    }
    private fun quickAccess(anchor: View? = null) {
        val quick = sites.filter { it.quick }
        themedMenu(anchor, "Quick access", quick.map { it.name }) { n -> openSite(quick[n]) }
    }

    private fun openSite(site: Site) {
        automaticRoutes.registerSite(site.id, site.url)
        selectedKey = site.id
        val existing = pages[site.id]
        if (existing != null) {
            browser = existing
            renderSiteBar(); renderPages()
            return
        }
        if (!pages.containsKey(site.id)) {
            val page = if (pages.isEmpty() && browser.url == null) browser else createPage()
            pages[site.id] = page; pageSites[site.id] = site
            viewSites[page] = site.id
            if (page.url.isNullOrBlank()) loadPage(page, site.url)
        }
        browser = pages[site.id]!!
        selected = site.id
        status.text = "Loading ${site.name}…"
        renderSiteBar(); renderPages()
    }

    private fun createPage(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadsImagesAutomatically = true
        configureBrowser(this)
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        webViewClient = pageClient
        webChromeClient = videoClient(this)
        setDownloadListener { url, _, _, _, _ -> try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) { toast("Cannot open this download") } }
    }

    private fun renderPages() {
        if (!::stage.isInitialized) return
        val count = if (layoutCount == 0) 4 else layoutCount
        val open = pages.keys.take(count).toMutableList()
        if (selectedKey in pages && selectedKey !in open) open[open.indexOfLast { it != mainPane }.coerceAtLeast(0)] = selectedKey
        if (count > 1 && mainPane in pages && mainPane !in open) open[open.indexOfLast { it != selectedKey }.coerceAtLeast(0)] = mainPane
        val prominent = mainPane in open && open.size > 1 && resources.configuration.screenWidthDp >= 600
        val cols = if (open.size > 1 && resources.configuration.screenWidthDp >= 600) 2 else 1
        // Keep both the WebView and its parent attached. Reparenting disrupts embedded players.
        paneViews.keys.filter { it !in pages }.toList().forEach { key -> stage.removeView(paneViews.remove(key)); mainButtons.remove(key) }
        stage.rowCount = GridLayout.UNDEFINED; stage.columnCount = GridLayout.UNDEFINED
        paneViews.filterKeys { it !in open }.values.forEach { pane ->
            pane.visibility = View.GONE
            pane.layoutParams = GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0)).apply { width = 0; height = 0 }
        }
        val ordered = if (prominent) listOf(mainPane) + open.filter { it != mainPane } else open
        ordered.forEachIndexed { i, key ->
            val page = pages[key] ?: return@forEachIndexed
            val col = if (prominent) { if (i == 0) 0 else 1 } else i % cols
            val row = if (prominent) maxOf(0, i - 1) else i / cols
            val span = if (prominent && i == 0) open.size - 1 else 1
            val lp = GridLayout.LayoutParams(GridLayout.spec(row, span, GridLayout.FILL, 1f), GridLayout.spec(col, 1, GridLayout.FILL, if (prominent && i == 0) 2f else 1f)).apply {
                width = 0; height = 0; setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            val pane = paneViews.getOrPut(key) {
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = controlBackground(false); clipToOutline = true; setPadding(dp(1), dp(1), dp(1), dp(1)) }
            val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(2)) }
            controls.addView(TextView(this).apply {
                tag = "user-content"; text = pageSites[key]?.name ?: "Page"; setTextColor(gold); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0); maxWidth = dp(160); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                isFocusable = true; setOnClickListener { selectedKey = key; browser = page; renderSiteBar(); applyAudio() }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            val mainButton = button("▣", true) { mainPane = if (mainPane == key) "" else key; renderPages() }.apply { contentDescription = "Toggle main pane" }
            mainButtons[key] = mainButton; controls.addView(mainButton)
            controls.addView(button("♫") { selectedKey = key; browser = page; volumeDialog(key) })
            controls.addView(button("↻") { if (routingReady) page.reload() else (page.url ?: pageSites[key]?.url)?.let { loadPage(page, it) } })
            controls.addView(button("×") { selectedKey = key; closePane() })
            container.addView(controls)
            val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(6)) }
            val back = button("‹") { if (page.canGoBack()) page.goBack() }.apply { contentDescription = "Back"; isEnabled = page.canGoBack() }
            val forward = button("›") { if (page.canGoForward()) page.goForward() }.apply { contentDescription = "Forward"; isEnabled = page.canGoForward() }
            historyButtons[page] = back to forward
            navigation.addView(back); navigation.addView(forward)
            val address = EditText(this).apply {
                setSingleLine(true); setText(page.url ?: pageSites[key]?.url ?: ""); textSize = 13f; setTextColor(this@MainActivity.text)
                minimumWidth = 0; minWidth = 0; setPadding(dp(10), 0, dp(10), 0); background = controlBackground(false)
                contentDescription = "Address bar"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
                setOnEditorActionListener { _, _, _ ->
                    var url = text.toString().trim(); if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                    val uri = android.net.Uri.parse(url)
                    if (uri.host != null && uri.scheme in listOf("https", "http")) { automaticRoutes.registerHost(viewSites[page] ?: key, uri.host!!); loadPage(page, url); clearFocus() }
                    true
                }
            }
            addresses[page] = address
            navigation.addView(address, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
            container.addView(navigation)
            val viewport = FrameLayout(this)
            viewport.addView(page, FrameLayout.LayoutParams(-1, -1))
            val errorLabel = TextView(this).apply {
                gravity = Gravity.CENTER; setPadding(dp(20), dp(20), dp(20), dp(20)); setBackgroundColor(bg); setTextColor(this@MainActivity.text); textSize = 15f
                visibility = if (loadErrors.containsKey(page)) View.VISIBLE else View.GONE
                text = "Couldn’t load this page\n\n${loadErrors[page] ?: ""}\n\nUse the pane’s refresh button to retry."
                setTextIsSelectable(true)
            }
            errorLabels[page] = errorLabel
            viewport.addView(errorLabel, FrameLayout.LayoutParams(-1, -1))
            container.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
            page.setOnFocusChangeListener { _, focused -> if (focused) { selectedKey = key; browser = page; renderSiteBar(); applyAudio() } }
            page.visibility = View.VISIBLE
            page.alpha = 1f
            container
            }
            mainButtons[key]?.isSelected = mainPane == key
            pane.visibility = View.VISIBLE
            if (pane.parent == null) stage.addView(pane, lp) else pane.layoutParams = lp
        }
        stage.columnCount = cols
        stage.rowCount = maxOf(1, if (prominent) open.size - 1 else (open.size + cols - 1) / cols)
        applyAudio()
    }

    private fun setLayout(n: Int) { mainPane = ""; layoutCount = n; prefs.edit().putInt("layout", n).apply(); layoutButtons.forEachIndexed { i, b -> b.background = layoutBackground(i == n); b.setTextColor(if (i == n) bg else Color.rgb(134,150,168)) }; renderPages() }
    private fun openUrl(url: String) {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return
        sites.firstOrNull { it.url == url }?.let { openSite(it); return }
        selected = url
        selectedKey = "custom-${System.nanoTime()}"
        if (!pages.containsKey(selectedKey)) { val page = if (pages.isEmpty()) browser else createPage(); pages[selectedKey] = page }
        val site = sites.firstOrNull { it.url == url }
        val stableId = site?.id ?: selectedKey
        pageSites[selectedKey] = Site(selectedKey, android.net.Uri.parse(url).host ?: url, url, custom = true)
        automaticRoutes.registerSite(stableId, url)
        browser = pages[selectedKey]!!
        viewSites[browser] = stableId
        status.text = "Loading…"; renderSiteBar(); renderPages(); loadPage(browser, url)
    }
    private fun saveTemporarySite(key: String, url: String): ArenaDialog? {
        if (sites.any { it.id == key }) return null
        val name = EditText(this).apply { setText(android.net.Uri.parse(url).host ?: url); setSingleLine(true); setTextColor(this@MainActivity.text) }
        return ArenaDialog.Builder(this).setTitle("Save site").setView(name).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
            if (name.text.isNotBlank() && pages.containsKey(key)) {
                val site = Site(key, name.text.toString().trim(), url, custom = true)
                sites.add(site); pageSites[key] = site; saveSites(); renderSiteBar()
            }
        }.show()
    }
    private fun loadPage(page: WebView, url: String) {
        // Page ownership is assigned after createPage(); install its site rules before navigating.
        installContentScript(page)
        if (SiteBrowserPolicy.usesDesktop(viewSites[page])) {
            val agent = SiteBrowserPolicy.desktopAgent(WebSettings.getDefaultUserAgent(this))
            if (page.settings.userAgentString != agent) page.settings.userAgentString = agent
        }
        val target = desktopUrl(page, url)
        if (routingReady) page.loadUrl(target) else pendingNavigations[page] = target
    }
    private fun desktopUrl(page: WebView, url: String): String {
        val id = viewSites[page]
        val configured = sites.firstOrNull { it.id == id }?.url ?: id?.let { pageSites[it]?.url } ?: url
        return SiteBrowserPolicy.desktopUrl(id, url, configured)
    }
    private fun routingPrepared(reload: Boolean) {
        routingReady = true
        val queued = pendingNavigations.toMap(); pendingNavigations.clear()
        queued.forEach { (page, url) -> if (page in pages.values) page.loadUrl(url) }
        if (reload) pages.values.filter { it !in queued }.forEach { it.reload() }
    }

    private fun headerButton(label: String, primary: Boolean = false, action: (View) -> Unit) = button(label, primary, action).apply {
        textSize = if (leanback) 15f else 12f
        layoutParams = LinearLayout.LayoutParams(-2, dp(if (leanback) 44 else 36)).apply { marginStart = dp(3); marginEnd = dp(3) }
    }
    private fun layoutBackground(active: Boolean) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), controlBackground(active))
        addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(if (active) gold else Color.rgb(41,49,61)))
        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(if (active) gold else Color.TRANSPARENT))
    }
    private fun button(label: String, primary: Boolean = false, action: (View) -> Unit): Button {
        val icon = mapOf("‹" to "back", "›" to "forward", "×" to "close", "↻" to "refresh", "▣" to "main", "♫" to "sound", "⏻" to "power")[label]
        return Button(this).apply {
        text = if (icon == null) label else ""; isAllCaps = false; textSize = if (leanback) 17f else 14f; setTextColor(if (primary) bg else this@MainActivity.text)
        gravity = Gravity.CENTER; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        background = controlBackground(primary)
        if (icon != null) {
            contentDescription = mapOf("back" to "Back", "forward" to "Forward", "close" to "Close", "refresh" to "Refresh", "main" to "Main pane", "sound" to "Sound", "power" to "Turn off")[icon]
            setCompoundDrawablesWithIntrinsicBounds(ControlIcon(icon, if (primary) bg else this@MainActivity.text, dp(20)), null, null, null)
        }
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        val size = if (leanback) 48 else 40
        val inset = if (icon != null) dp((size - 20) / 2) else dp(10)
        isFocusable = true; setOnClickListener { action(it) }; setPadding(inset, 0, inset, 0)
        val lp = LinearLayout.LayoutParams(if (icon != null) dp(size) else -2, dp(if (leanback) 48 else 42)); lp.marginStart = dp(3); lp.marginEnd = dp(3); layoutParams = lp
        }
    }
    private fun controlBackground(primary: Boolean, pill: Boolean = false): android.graphics.drawable.StateListDrawable {
        fun shape(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = dp(if (pill) 24 else 8).toFloat(); setStroke(dp(1), stroke) }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(if (primary) gold else Color.rgb(41, 49, 61), gold))
            addState(intArrayOf(android.R.attr.state_pressed), shape(if (primary) gold else Color.rgb(41, 49, 61), gold))
            addState(intArrayOf(), shape(if (primary) gold else panel, if (primary) gold else Color.rgb(34, 48, 66)))
        }
    }
    private fun tabBackground(open: Boolean, focused: Boolean): android.graphics.drawable.StateListDrawable {
        fun shape(highlight: Boolean) = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            if (open) intArrayOf(Color.rgb(55,46,27), Color.rgb(29,27,23)) else intArrayOf(Color.rgb(24,34,47), panel)).apply {
            cornerRadius = dp(24).toFloat(); setStroke(dp(1), if (highlight || focused) gold else if (open) Color.rgb(128,104,51) else Color.rgb(36,50,70))
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(true)); addState(intArrayOf(android.R.attr.state_pressed), shape(true)); addState(intArrayOf(), shape(false))
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Check for site updates").setOnMenuItemClickListener { refreshLinks(true); true }
        popup.menu.add("Link health").setOnMenuItemClickListener { healthCheck(); true }
        popup.menu.add("Passcode").setOnMenuItemClickListener { changePass(); true }
        popup.menu.add("Export sites").setOnMenuItemClickListener { documentPicker(true); true }
        popup.menu.add("Import sites").setOnMenuItemClickListener { documentPicker(false); true }
        popup.menu.add(if (prefs.getBoolean("adblock", true)) "Disable ad blocking" else "Enable ad blocking").setOnMenuItemClickListener { prefs.edit().putBoolean("adblock", !prefs.getBoolean("adblock", true)).apply(); toast("Ad blocking preference saved"); true }
        popup.menu.add(if (prefs.getBoolean("autoskip", true)) "Disable auto-skip" else "Enable auto-skip").setOnMenuItemClickListener { prefs.edit().putBoolean("autoskip", !prefs.getBoolean("autoskip", true)).apply(); true }
        popup.menu.add(if (prefs.getBoolean("restore", true)) "Do not restore last page" else "Restore last page").setOnMenuItemClickListener { prefs.edit().putBoolean("restore", !prefs.getBoolean("restore", true)).apply(); true }
        popup.menu.add("Share current page").setOnMenuItemClickListener { sharePage(); true }
        popup.menu.add("Open current page in browser").setOnMenuItemClickListener {
            val url = browser.url
            if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (_: Exception) { toast("No external browser is installed") }
            true
        }
        popup.menu.add("Edit selected site").setOnMenuItemClickListener { sites.firstOrNull { it.id == selectedKey }?.let { editSite(it) }; true }
        popup.menu.add("Mute / unmute selected pane").setOnMenuItemClickListener { prefs.edit().putBoolean("mute-$selectedKey", !prefs.getBoolean("mute-$selectedKey", false)).apply(); applyAudio(); true }
        popup.menu.add("Only focused pane plays sound").apply { isCheckable = true; isChecked = prefs.getBoolean("focusAudio", false); setOnMenuItemClickListener { prefs.edit().putBoolean("focusAudio", !isChecked).apply(); applyAudio(); true } }
        popup.menu.add("Close selected pane").setOnMenuItemClickListener { closePane(); true }
        popup.menu.add("About Football Arena").setOnMenuItemClickListener { ArenaDialog.Builder(this).setTitle("Football Arena").setMessage("Android and Android TV preview · ${if (leanback) "TV" else "Mobile"} mode\nChat is not included.").setPositiveButton("OK", null).show(); true }
        popup.show()
    }

    private fun addSite() = siteDialog(null)
    private fun themedMenu(anchor: View?, title: String, entries: List<String>, choose: (Int) -> Unit) {
        if (anchor == null) { ArenaDialog.Builder(this).setTitle(title).setItems(entries.toTypedArray()) { _, n -> choose(n) }.setNegativeButton("Close", null).show(); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); background = controlBackground(false) }
        val popup = PopupWindow(ScrollView(this).apply { addView(box) }, dp(300), -2, true).apply { elevation = dp(12).toFloat(); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable = true }
        if (entries.isEmpty()) box.addView(TextView(this).apply { text = AppStrings.t("Nothing here yet. Open a site’s ⋮ menu and choose Add to quick access."); setTextColor(this@MainActivity.text); setPadding(dp(12), dp(12), dp(12), dp(12)) })
        entries.forEachIndexed { n, label -> box.addView(button(label) { popup.dismiss(); choose(n) }.apply { if (title == "Quick access") tag = "user-content" }, LinearLayout.LayoutParams(-1, dp(48))) }
        AppStrings.watch(box); popup.showAsDropDown(anchor, 0, dp(6))
    }
    private fun editSite(s: Site, anchor: View? = null) {
        themedMenu(anchor, s.name, listOf("Open", "Edit", "Delete", if (s.quick) "Remove from quick access" else "Add to quick access")) { which ->
            when(which) {
                0 -> openSite(s)
                1 -> siteDialog(s)
                2 -> ArenaDialog.Builder(this).setMessage("Delete ${s.name}?").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> sites.remove(s); prefs.edit().putStringSet("deleted", (prefs.getStringSet("deleted", emptySet())!! + s.id).toSet()).apply(); if (pages.containsKey(s.id)) { selectedKey = s.id; closePane() }; saveSites() }.show()
                3 -> { val i = sites.indexOf(s); if (i >= 0) sites[i] = s.copy(quick = !s.quick, custom = true); saveSites() }
            }
        }
    }


    private fun siteDialog(old: Site?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        val name = EditText(this).apply { hint = "Site name"; setText(old?.name ?: ""); setSingleLine(true) }
        val url = EditText(this).apply { hint = "https://example.com"; setText(old?.url ?: "https://"); setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        box.addView(name); box.addView(url)
        ArenaDialog.Builder(this).setTitle(if (old == null) "Add site" else "Edit site").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
            val n = name.text.toString().trim(); var u = url.text.toString().trim(); if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            if (n.isBlank() || !(u.startsWith("https://") || u.startsWith("http://"))) { toast("Enter a name and a valid web address"); return@setPositiveButton }
            if (old == null) sites.add(Site("custom-${System.currentTimeMillis()}", n.take(40), u, custom = true))
            else { val i = sites.indexOf(old); if (i >= 0) sites[i] = old.copy(name = n.take(40), url = u, custom = true) }
            saveSites()
        }.show()
    }

    private fun refreshLinks(manual: Boolean) {
        if (manual) status.text = "Checking site list…"
        io.execute {
            try {
                val c = URL("https://raw.githubusercontent.com/waterme1on7r-byte/football-arena-multi/main/sites.json").openConnection() as HttpURLConnection
                c.connectTimeout = 9000; c.readTimeout = 9000; c.setRequestProperty("User-Agent", "FootballArena-Android"); c.connect()
                val raw = c.inputStream.bufferedReader().use { it.readText() }; c.disconnect()
                val remote = decodeSites(JSONObject(raw).optJSONArray("sites") ?: JSONArray())
                if (remote.isEmpty()) throw IllegalArgumentException("Empty site list")
                runOnUiThread {
                    val custom = sites.filter { it.custom }
                    val deleted = prefs.getStringSet("deleted", emptySet())!!
                    sites.clear(); sites.addAll(remote.filter { it.id !in deleted && custom.none { c -> c.id == it.id } }); custom.forEach { u -> if (sites.none { it.url == u.url }) sites.add(u) }
                    saveSites(); status.text = if (manual) "Site list updated" else "Ready"; if (manual) ArenaDialog.Builder(this).setTitle("Link updates").setMessage("Site list updated").setPositiveButton("OK", null).show()
                }
            } catch (_: Exception) { runOnUiThread { if (manual) { status.text = "Could not update site list · using saved sites"; ArenaDialog.Builder(this).setTitle("Link updates").setMessage("Could not update site list · using saved sites").setPositiveButton("OK", null).show() } } }
        }
    }

    private fun runAutoSkip(view: WebView = browser) {
        applyAudio()
        try {
            view.evaluateJavascript("try {" + contentSettings(view) + adSkipScript + ";window.__faApplyContentSettings&&window.__faApplyContentSettings({skipOff:window.__faAdOff,blockAds:window.__faBlockAds});}catch(e){window.__faContentError=String(e);}", null)
        } catch (_: Exception) { }
    }
    private fun contentSettings(view: WebView) = "window.__faSiteId=${JSONObject.quote(viewSites[view] ?: "")};window.__faAdOff=${!prefs.getBoolean("autoskip", true)};window.__faBlockAds=${prefs.getBoolean("adblock", true)};"
    private fun installContentScript(view: WebView) {
        contentScripts.remove(view)?.remove()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val script = contentSettings(view) + adSkipScript
            contentScripts[view] = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))
        }
    }
    private fun contentBlockingDiagnostics() {
        val page = browser
        page.evaluateJavascript("JSON.stringify(window.__faContentStats||{})") { value ->
            if (isFinishing) return@evaluateJavascript
            val stats = runCatching { JSONObject(JSONArray("[$value]").getString(0)) }.getOrElse { JSONObject() }
            val frameSupport = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            val engine = if (android.os.Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName else null
            fun state(key: String) = AppStrings.t(if (prefs.getBoolean(key, true)) "On" else "Off")
            val lines = listOf(
                AppStrings.t("Ad blocking") + ": " + state("adblock"),
                AppStrings.t("Advertising domains") + ": " + AdRules.domainCount,
                AppStrings.t("Requests blocked this session") + ": " + contentBlocker.blockedRequests.get(),
                AppStrings.t("Service-worker requests blocked") + ": " + contentBlocker.blockedWorkerRequests.get(),
                "",
                AppStrings.t("Auto-skip") + ": " + state("autoskip"),
                AppStrings.t("Skip buttons pressed on this page") + ": " + stats.optInt("skipActions"),
                AppStrings.t("Promotional elements hidden on this page") + ": " + stats.optInt("hiddenPromotions"),
                AppStrings.t("Embedded-player support") + ": " + AppStrings.t(if (frameSupport) "Available" else "Same-site player fallback available. Update WebView for cross-site players."),
                "WebView: ${engine ?: "Unknown"}"
            )
            ArenaDialog.Builder(this).setTitle("Ad blocking diagnostics").setMessage(lines.joinToString("\n")).setPositiveButton("Close", null).show()
        }
    }

    private fun sharePage() {
        val url = browser.url ?: return
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url)
        startActivity(android.content.Intent.createChooser(i, AppStrings.t("Share page")))
    }
    private fun documentPicker(export: Boolean) {
        try {
            val intent = android.content.Intent(if (export) android.content.Intent.ACTION_CREATE_DOCUMENT else android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "application/json"
                if (export) putExtra(android.content.Intent.EXTRA_TITLE, "football-arena-sites.json")
            }
            startActivityForResult(intent, if (export) 101 else 102)
        } catch (_: Exception) { toast("No document picker is installed on this device") }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            if (requestCode == 101) contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(prefs.getString("sites", "[]")!!) }
            if (requestCode == 102) {
                val raw = contentResolver.openInputStream(uri)?.use { input -> input.readBytes().also { require(it.size <= 1048576) }.toString(Charsets.UTF_8) } ?: return
                decodeSites(JSONArray(raw)).take(200).forEach { s -> if (sites.none { it.url == s.url }) sites.add(s.copy(id = "custom-${System.nanoTime()}", custom = true)) }; saveSites()
            }
            toast("Done")
        } catch (_: Exception) { toast("Could not read or write that sites file") }
    }
    private fun healthCheck() {
        status.text = "Checking links…"
        val snapshot = sites.toList()
        io.execute {
            val result = snapshot.joinToString("\n") { s ->
                val state = try {
                    val c = URL(s.url).openConnection() as HttpURLConnection
                    try { c.requestMethod = "HEAD"; c.connectTimeout = 4000; c.readTimeout = 4000; if (c.responseCode in 200..399) "Reachable" else "HTTP ${c.responseCode}" } finally { c.disconnect() }
                } catch (_: Exception) { "Unreachable" }
                "${s.name} · $state"
            }
            runOnUiThread { if (!isFinishing) { status.text = "Link checks finished"; ArenaDialog.Builder(this).setTitle("Link health").setMessage(result).setPositiveButton("Close", null).show() } }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpiQuickMenu(anchor: View) {
        val controls = LinearLayout(this).apply { setPadding(dp(6), dp(6), dp(6), dp(6)); background = controlBackground(false) }
        val popup = PopupWindow(controls, -2, -2, true).apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); elevation = dp(12).toFloat(); isOutsideTouchable = true }
        controls.addView(button("⏻", routingMode() != "off") { popup.dismiss(); setDpiEnabled(routingMode() == "off") }.apply { contentDescription = if (routingMode() == "off") "Turn on" else "Turn off" })
        controls.addView(button("↻") { popup.dismiss(); updateDpi(true) }.apply { contentDescription = "Restart" })
        AppStrings.watch(controls); popup.showAsDropDown(anchor, 0, dp(6))
    }
    private fun paintDpiStatus() {
        val bypassPageLoaded = ::browser.isInitialized && browser in bypassPages && !loadErrors.containsKey(browser) && browser !in safetyWarnings
        val indicator = when (dpiState) { "Automatic", "Always bypass" -> if (bypassPageLoaded) Color.rgb(67,222,143) else gold; "Failed" -> Color.rgb(255,115,115); "Starting" -> gold; else -> Color.rgb(134,150,168) }
        dpiButton.text = android.text.SpannableString("● DPI").apply { setSpan(android.text.style.ForegroundColorSpan(indicator), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        dpiButton.contentDescription = "DPI · $dpiState"
        dpiButton.setTextColor(Color.rgb(134,150,168))
        dpiButton.contentDescription = AppStrings.t("DPI · $dpiState") + " · " + AppStrings.t(if (bypassPageLoaded) "Page loaded with bypass" else "Bypass ready; access not verified")
    }
    private fun dpiMenu() {
        val p = dpiProxy
        val focusedHost = android.net.Uri.parse(browser.url ?: pageSites[selectedKey]?.url ?: "").host ?: "Unknown"
        val focusedReport = p?.describeHost(focusedHost) ?: "Proxy is off"
        ArenaDialog.Builder(this).setTitle("DPI bypass · $dpiState")
            .setItems(arrayOf("Routing mode · ${routingMode()}", "Restart routing", "Option 1 · 8-byte fragments", "Option 2 · 32-byte fragments", "Option 3 · 64-byte fragments", "Diagnostics · ${p?.connections?.get() ?: 0} routed connections", if (prefs.getBoolean("dpiTlsRecords", true)) "Try compatibility mode · preserve TLS record" else "Use TLS record fragmentation")) { _, n ->
                if (n == 0) { routingModeDialog(); return@setItems }
                if (n == 6) { prefs.edit().putBoolean("dpiTlsRecords", !prefs.getBoolean("dpiTlsRecords", true)).apply(); updateDpi(true); return@setItems }
                if (n == 5) { siteDiagnostics(focusedReport); return@setItems }
                if (n >= 2) prefs.edit().putInt("dpiFragment", listOf(8, 32, 64)[n - 2]).apply()
                updateDpi(true)
            }.setNegativeButton("Close", null).show()
    }
    private fun siteDiagnostics(focusedReport: String) {
        val page = browser
        page.evaluateJavascript("JSON.stringify(Array.from(document.querySelectorAll('iframe')).map(f=>f.src).filter(u=>u.startsWith('https://')))") { value ->
            if (isFinishing || page !in pages.values) return@evaluateJavascript
            val frameUrls = runCatching { JSONArray(JSONArray("[$value]").getString(0)) }.getOrElse { JSONArray() }
            val frames = (0 until frameUrls.length()).map { frameUrls.getString(it) }.distinct()
            val reports = frames.map { url ->
                val host = android.net.Uri.parse(url).host.orEmpty()
                "$url\n${dpiProxy?.describeHost(host) ?: "Proxy is off"}"
            }.joinToString("\n\n")
            val details = "Mode: ${if (prefs.getBoolean("dpiTlsRecords", true)) "TLS record fragmentation" else "TCP writes, original TLS record"}\n\n$focusedReport\n\nSelected page error: ${loadErrors[page] ?: "None recorded"}\n\n${AppStrings.t("Embedded content error")}: ${resourceErrors[page] ?: "None recorded"}\n\n$reports\n\nApp totals: ${dpiProxy?.connections?.get() ?: 0} connections, ${dpiProxy?.serverReplies?.get() ?: 0} server replies."
            ArenaDialog.Builder(this).setTitle("Selected site diagnostics").setMessage(details).setPositiveButton("Close", null).show()
        }
    }
    private fun routingModeDialog() {
        val values = listOf("auto", "off", "always")
        ArenaDialog.Builder(this).setTitle("Connection routing")
            .setSingleChoiceItems(arrayOf("Automatic (recommended)", "Direct connection", "Always use bypass"), values.indexOf(routingMode())) { dialog, index ->
                prefs.edit().putString("dpiMode", values[index]).apply()
                automaticRoutes.reset(); dialog.dismiss(); updateDpi(true)
            }.setNegativeButton("Cancel", null).show()
    }
    private fun updateDpi(reload: Boolean) {
        routingReady = false
        bypassPages.clear()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) { dpiState = "Unsupported WebView"; paintDpiStatus(); routingPrepared(reload); return }
        dpiState = "Starting"; paintDpiStatus()
        val executor = java.util.concurrent.Executor { runOnUiThread(it) }
        val generation = ++routingGeneration
        // Clear old routing before stopping its listener, then apply the replacement.
        ProxyController.getInstance().clearProxyOverride(executor) {
            if (generation != routingGeneration || isFinishing) return@clearProxyOverride
            dpiProxy?.stop(); dpiProxy = null
            if (routingMode() == "off") { dpiState = "Direct"; paintDpiStatus(); routingPrepared(reload); return@clearProxyOverride }
            try {
                val proxy = DpiProxy(prefs.getInt("dpiFragment", 32).coerceIn(8, 64), tlsRecords = prefs.getBoolean("dpiTlsRecords", true), routing = if (routingMode() == "auto") automaticRoutes else null)
                val port = proxy.start(); dpiProxy = proxy
                val config = ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port", "https").build()
                ProxyController.getInstance().setProxyOverride(config, executor) {
                    if (generation != routingGeneration || isFinishing) return@setProxyOverride
                    dpiState = if (routingMode() == "auto") "Automatic" else "Always bypass"; paintDpiStatus()
                    routingPrepared(reload)
                }
            } catch (e: Exception) { dpiProxy?.stop(); dpiProxy = null; dpiState = "Failed"; paintDpiStatus(); toast(e.message ?: "Could not start DPI bypass") }
        }
    }
    private fun volumeDialog(key: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
        val mute = CheckBox(this).apply { text = "Mute"; isChecked = prefs.getBoolean("mute-$key", false); setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("mute-$key", on).apply(); applyAudio() } }
        val slider = SeekBar(this).apply { max = 100; progress = prefs.getInt("volume-$key", 100); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, user: Boolean) { if (user) { prefs.edit().putInt("volume-$key", value).apply(); applyAudio() } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }) }
        box.addView(mute); box.addView(slider)
        ArenaDialog.Builder(this).setTitle("Sound").setView(box).setPositiveButton("Done", null).show()
    }
    private fun settingsDialog(): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "settings-shell"
            background = android.graphics.drawable.GradientDrawable().apply { setColor(panel); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(39,54,73)) }
            clipToOutline = true
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(8), dp(14), dp(8)); setBackgroundColor(Color.rgb(24,34,47)) }
        head.addView(TextView(this).apply { text = AppStrings.t("Settings").uppercase(); textSize = 24f; setTextColor(gold); typeface = brandTypeface }, LinearLayout.LayoutParams(0, dp(42), 1f))
        head.addView(headerButton("×") { dialog.dismiss() }.apply { contentDescription = AppStrings.t("Close"); setBackgroundColor(Color.TRANSPARENT); textSize = 24f }); shell.addView(head)
        val body = LinearLayout(this)
        shell.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(12), dp(8), dp(8)); setBackgroundColor(bg); tag = "settings-sidebar" }
        body.addView(ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; addView(tabs) }, LinearLayout.LayoutParams(dp(if (resources.configuration.screenWidthDp < 600) 112 else 176), -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)) }
        val scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        body.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        var dpiSwitch: Switch? = null
        val routingListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "dpiMode") dpiSwitch?.isChecked = routingMode() != "off"
        }
        prefs.registerOnSharedPreferenceChangeListener(routingListener)
        dialog.setOnDismissListener { prefs.unregisterOnSharedPreferenceChangeListener(routingListener) }
        fun heading(label: String, description: String) {
            content.addView(TextView(this).apply { text = AppStrings.t(label).uppercase(); textSize = 17f; typeface = brandTypeface; setTextColor(gold); setPadding(0, 0, 0, dp(6)) })
            if (description.isNotBlank()) content.addView(TextView(this).apply { text = description; textSize = 13f; setTextColor(Color.rgb(134,150,168)); setPadding(0, 0, 0, dp(14)) })
        }
        fun row(label: String, description: String, key: String? = null, default: Boolean = false, action: (() -> Unit)? = null) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(10), 0) }
            labels.addView(TextView(this).apply { text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(this@MainActivity.text) })
            if (description.isNotBlank()) labels.addView(TextView(this).apply { text = description; textSize = 12f; setPadding(0, dp(4), 0, 0); setTextColor(Color.rgb(134,150,168)) })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            if (key != null) row.addView(Switch(this).apply {
                contentDescription = label; isChecked = if (key == "dpi") routingMode() != "off" else prefs.getBoolean(key, default)
                if (key == "dpi") { dpiSwitch = this; tag = "dpi-settings-switch" }
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(gold, Color.rgb(175,186,199)))
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.rgb(126,100,44), Color.rgb(57,68,82)))
                setOnCheckedChangeListener { _, on ->
                    if (key == "dpi") setDpiEnabled(on) else prefs.edit().putBoolean(key, on).apply()
                    if (key == "focusAudio") applyAudio()
                    if (key == "autoskip" || key == "adblock") pages.values.forEach { installContentScript(it); runAutoSkip(it) }
                }
            }) else row.addView(headerButton(if (label == "Language") "Türkçe / English  ⌄" else label) { action?.invoke() }.apply { maxWidth = dp(if (resources.configuration.screenWidthDp < 600) 100 else 190) })
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
            content.addView(View(this).apply { setBackgroundColor(Color.rgb(34,48,66)) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        fun section(n: Int) {
            content.removeAllViews()
            scroll.scrollTo(0, 0)
            for (i in 0 until tabs.childCount) (tabs.getChildAt(i) as Button).apply {
                background = android.graphics.drawable.GradientDrawable().apply { setColor(if (i == n) Color.rgb(42,50,61) else Color.TRANSPARENT); cornerRadius = dp(8).toFloat() }
                setTextColor(if (i == n) this@MainActivity.text else Color.rgb(134,150,168))
            }
            when (n) {
                0 -> {
                    heading("App", "Football Arena · ${packageManager.getPackageInfo(packageName, 0).versionName}")
                    row("Language", "Türkçe / English", action = { dialog.dismiss(); languageDialog() })
                    row("Restore session", "Remember the last page and layout.", "restore", true)
                    row("Passcode", "Ask for a four-digit code when the app starts.", action = { changePass() })
                    row("Export sites", "Save a JSON file compatible with the desktop app.", action = { documentPicker(true) })
                    row("Import sites", "Add sites from a saved JSON file.", action = { documentPicker(false) })
                    row("Share current page", "", action = { sharePage() })
                    row("Open current page in browser", "", action = { val url = browser.url; if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) { toast("No external browser is installed") } })
                }
                1 -> {
                    heading("Sound & Video", "Control audio for each open pane.")
                    row("Focused pane audio", "Only the selected pane plays sound.", "focusAudio")
                    row("Pane volume", "Adjust volume and mute for the selected pane.", action = { volumeDialog(selectedKey) })
                }
                2 -> {
                    heading("Links", "Site addresses are shared with the desktop app.")
                    row("Update site list", "Download the latest addresses from GitHub.", action = { refreshLinks(true) })
                    row("Link health", "Check whether your saved sites can be reached.", action = { healthCheck() })
                    row("Quick access", "Open the sites in your quick-access list.", action = { quickAccess() })
                }
                3 -> {
                    heading("Network", "Automatic uses encrypted DNS for HTTPS pages and resources, and adapts TLS fragmentation when needed.")
                    row("DPI bypass", "Enable or disable DPI bypass for this app.", "dpi", true)
                    row("Connection routing", "Learned routes expire after four hours or when the site address changes.", action = { routingModeDialog() })
                    row("DPI options & diagnostics", "See routed connections, handshakes, errors, and fragment options.", action = { dpiMenu() })
                    row("Ad blocking", "Block known advertising hosts.", "adblock", true)
                    row("Auto-skip", "Attempt to skip supported video ads.", "autoskip", true)
                    row("Ad blocking diagnostics", "Check blocking activity and embedded-player support.", action = { contentBlockingDiagnostics() })
                }
            }
        }
        arrayOf("App", "Sound & Video", "Links", "Network").forEachIndexed { i, label -> tabs.addView(button(label) { section(i) }.apply { gravity = Gravity.CENTER_VERTICAL; textSize = 13f; setPadding(dp(12), 0, dp(8), 0) }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(4) }) }
        section(0); dialog.setContentView(shell); dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(.65f)
        dialog.window?.setLayout(minOf(dp(920), resources.displayMetrics.widthPixels - dp(28)), minOf(dp(650), resources.displayMetrics.heightPixels - dp(48)))
        AppStrings.watch(shell)
        return dialog
    }
    private fun configureBrowser(view: WebView) {
        // Third-party players often use cookies and HTTP media within HTTPS pages.
        // Keep certificate validation and the normal WebView origin protections intact.
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.setSupportMultipleWindows(true)
        view.settings.javaScriptCanOpenWindowsAutomatically = false
        installContentScript(view)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        if (android.os.Build.VERSION.SDK_INT >= 29) view.isForceDarkAllowed = false
    }
    private fun closePane() {
        val page = pages.remove(selectedKey) ?: return
        pendingNavigations.remove(page)
        contentScripts.remove(page)?.remove()
        resourceErrors.remove(page)
        desktopRedirects.remove(page)
        bypassPages.remove(page)
        errorLabels.remove(page); loadErrors.remove(page); historyButtons.remove(page)
        addresses.remove(page)
        viewSites.remove(page); safetyWarnings.remove(page)
        (page.parent as? android.view.ViewGroup)?.removeView(page); page.destroy()
        pageSites.remove(selectedKey)
        selectedKey = pages.keys.firstOrNull() ?: ""
        val next = pages[selectedKey]
        browser = next ?: createPage()
        if (next == null) browser.loadUrl("about:blank")
        renderPages(); renderSiteBar()
    }
    private fun applyAudio() {
        pages.forEach { (key, page) ->
            val muted = prefs.getBoolean("mute-$key", false) || (prefs.getBoolean("focusAudio", false) && key != selectedKey)
            val volume = prefs.getInt("volume-$key", 100).coerceIn(0, 100) / 100.0
            page.evaluateJavascript("(()=>{window.__faVolume=$volume;if(!window.__faVolumeTimer){window.__faVolumeTimer=true;setInterval(()=>document.querySelectorAll('video,audio').forEach(e=>e.volume=window.__faVolume),1000)}document.querySelectorAll('video,audio').forEach(e=>e.volume=window.__faVolume)})()", null)
            page.evaluateJavascript("(()=>{window.__faMuted=$muted;if(!window.__faSound){window.__faSound=true;setInterval(()=>document.querySelectorAll('video,audio').forEach(e=>e.muted=window.__faMuted),1000)}document.querySelectorAll('video,audio').forEach(e=>e.muted=window.__faMuted)})()", null)
        }
    }
    override fun onPause() { super.onPause(); pages.values.forEach { it.onPause() } }
    override fun onResume() { super.onResume(); pages.values.forEach { it.onResume() } }
    private fun videoClient(owner: WebView) = object : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            if (!isUserGesture) return false
            // Capture target=_blank/window.open without replacing the playing pane.
            val child = WebView(this@MainActivity)
            var handled = false
            fun accept(url: String) {
                if (handled || !(url.startsWith("https://") || url.startsWith("http://"))) return
                handled = true
                if (!contentBlocker.blocksNavigation(url)) openUrl(url)
                child.post { child.destroy() }
            }
            child.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { accept(r.url.toString()); return true }
                override fun onPageStarted(v: WebView, url: String, icon: android.graphics.Bitmap?) { accept(url) }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            child.postDelayed({ if (!handled) { handled = true; child.destroy() } }, 15000)
            return true
        }
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) resourceErrors[owner] = "${message.sourceId()}:${message.lineNumber()}\n${message.message()}"
            return false
        }
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullVideo != null) { callback.onCustomViewHidden(); return }
            fullVideo = view; fullCallback = callback
            root.visibility = View.GONE
            addContentView(view, android.view.ViewGroup.LayoutParams(-1, -1))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        override fun onHideCustomView() { exitVideo() }
    }
    private fun exitVideo(): Boolean {
        val view = fullVideo ?: return false
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        fullVideo = null; root.visibility = View.VISIBLE
        val cb = fullCallback; fullCallback = null; cb?.onCustomViewHidden()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        return true
    }
    private fun toast(s: String) = Toast.makeText(this, AppStrings.t(s), Toast.LENGTH_SHORT).show()
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && ::browser.isInitialized) {
            if (exitVideo()) return true
            if (browser.canGoBack()) { browser.goBack(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onDestroy() { dpiProxy?.stop(); pages.values.distinct().forEach { it.destroy() }; if (pages.isEmpty() && ::browser.isInitialized) browser.destroy(); io.shutdownNow(); super.onDestroy() }
}

