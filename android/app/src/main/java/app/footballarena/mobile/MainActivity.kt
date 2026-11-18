package app.footballarena.mobile

import android.app.Activity
import android.app.AlertDialog
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

private data class Site(val id: String, val name: String, val url: String, val quick: Boolean = false, val custom: Boolean = false)

/** Android phone and Android TV shell. Chat is intentionally omitted on both form factors. */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("football-arena", MODE_PRIVATE) }
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
    private var layoutCount = 1
    private val pages = linkedMapOf<String, WebView>()
    private val pageSites = mutableMapOf<String, Site>()
    private var leanback = false
    private val bg = Color.rgb(8, 13, 19)
    private val panel = Color.rgb(17, 25, 35)
    private val gold = Color.rgb(245, 196, 81)
    private val text = Color.rgb(233, 237, 242)
    private val adHosts = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leanback = packageManager.hasSystemFeature("android.software.leanback")
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        layoutCount = prefs.getInt("layout", 1).let { if (it == 2 || it == 4) it else 1 }
        loadSites()
        buildUi()
        val previous = prefs.getString("last", "") ?: ""
        if (prefs.getBoolean("restore", true) && previous.startsWith("https://")) openUrl(previous)
        else if (sites.isNotEmpty()) openSite(sites.first())
        refreshLinks(false)
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
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(10), dp(8)); setBackgroundColor(panel) }
        title = TextView(this).apply { text = "FOOTBALL ARENA"; setTextColor(gold); textSize = if (leanback) 23f else 18f; typeface = Typeface.DEFAULT_BOLD; isFocusable = true }
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(button("＋ Site", true) { addSite() })
        toolbar.addView(button("⋮", false) { showMenu(it) })
        root.addView(toolbar)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(panel); isFocusable = false }
        listBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        scroll.addView(listBar)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(if (leanback) 66 else 52)))
        browser = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webViewClient = pageClient
            webChromeClient = videoClient()
            setDownloadListener { url, _, _, _, _ -> try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) { toast("Cannot open this download") } }
        }
        buildStage(root)
    }

    private fun makePageClient(): WebViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url.toString()
                    return !(u.startsWith("https://") || u.startsWith("http://"))
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!prefs.getBoolean("adblock", true)) return null
                    val host = request.url.host?.lowercase() ?: return null
                    if (adHosts.any { host == it || host.endsWith(".$it") }) return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), "".byteInputStream())
                    return null
                }
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (view === browser) {
                        selected = url
                        prefs.edit().putString("last", url).apply()
                        status.text = "Ready"
                    }
                    runAutoSkip(view)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) status.text = "Page failed to load · ${error.description}"
                }
    }

    private fun buildStage(root: LinearLayout) {
        stage = GridLayout(this).apply { setBackgroundColor(Color.BLACK); useDefaultMargins = true }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(5), dp(8), dp(5)); setBackgroundColor(panel) }
        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f; text = "Ready" }
        footer.addView(status, LinearLayout.LayoutParams(0, dp(42), 1f))
        footer.addView(button("1", layoutCount == 1) { setLayout(1) })
        footer.addView(button("2", layoutCount == 2) { setLayout(2) })
        footer.addView(button("4", layoutCount == 4) { setLayout(4) })
        footer.addView(button("‹", false) { if (browser.canGoBack()) browser.goBack() })
        footer.addView(button("↻", false) { browser.reload() })
        footer.addView(button("›", false) { if (browser.canGoForward()) browser.goForward() })
        root.addView(footer)
        setContentView(root)
        renderSiteBar()
    }

    private fun renderSiteBar() {
        if (!::listBar.isInitialized) return
        listBar.removeAllViews()
        sites.forEach { s ->
            val b = button(s.name, s.url == selected || s.id == selected) { openSite(s) }
            b.contentDescription = "Open ${s.name}"
            b.setOnLongClickListener { editSite(s); true }
            listBar.addView(b)
        }
    }

    private fun openSite(site: Site) {
        selectedKey = site.id
        if (!pages.containsKey(site.id)) {
            if (pages.size >= 4) {
                val victim = pages.keys.firstOrNull { it != selectedKey } ?: pages.keys.firstOrNull()
                if (victim != null) { pages.remove(victim)?.destroy(); pageSites.remove(victim) }
            }
            val page = if (pages.isEmpty() && browser.url == null) browser else createPage()
            pages[site.id] = page; pageSites[site.id] = site
            if (page.url.isNullOrBlank()) page.loadUrl(site.url)
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
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        webViewClient = pageClient
        webChromeClient = videoClient()
        setDownloadListener { url, _, _, _, _ -> try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) { toast("Cannot open this download") } }
    }

    private fun renderPages() {
        if (!::stage.isInitialized) return
        val open = (listOf(selectedKey) + pages.keys.filter { it != selectedKey }).filter { it in pages }.take(layoutCount)
        val cols = if (layoutCount == 2) 2 else if (layoutCount == 4) 2 else 1
        stage.removeAllViews(); stage.rowCount = GridLayout.UNDEFINED; stage.columnCount = cols; stage.rowCount = if (layoutCount == 4) 2 else 1
        open.forEachIndexed { i, key ->
            val page = pages[key] ?: return@forEachIndexed
            val col = i % cols; val row = i / cols
            val lp = GridLayout.LayoutParams(GridLayout.spec(row, 1, 1f), GridLayout.spec(col, 1, 1f)).apply {
                width = 0; height = 0; setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            stage.addView(page, lp)
            page.onResume()
            page.setOnFocusChangeListener { _, focused -> if (focused) { selectedKey = key; browser = page; applyAudio() } }
            page.visibility = View.VISIBLE
            page.alpha = if (key == selectedKey) 1f else 0.96f
        }
        pages.filterKeys { it !in open }.values.forEach { it.onPause(); if (it.parent != null) (it.parent as? android.view.ViewGroup)?.removeView(it) }
        applyAudio()
    }

    private fun setLayout(n: Int) { layoutCount = n; prefs.edit().putInt("layout", n).apply(); renderPages(); toast("${n}-pane layout") }
    private fun openUrl(url: String) {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return
        selected = url
        if (selectedKey.isBlank() || selectedKey !in pages) selectedKey = "custom-${System.currentTimeMillis()}"
        if (!pages.containsKey(selectedKey)) { val page = if (pages.isEmpty()) browser else createPage(); pages[selectedKey] = page }
        browser = pages[selectedKey]!!
        status.text = "Loading…"; renderSiteBar(); renderPages(); browser.loadUrl(url)
    }

    private fun button(label: String, primary: Boolean = false, action: (View) -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = if (leanback) 17f else 14f; setTextColor(if (primary) bg else this@MainActivity.text)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(if (primary) gold else Color.rgb(35, 47, 61)))
        minHeight = dp(if (leanback) 50 else 42); minimumHeight = minHeight; minWidth = dp(if (leanback) 72 else 48)
        isFocusable = true; setOnClickListener { action(it) }; setPadding(dp(10), 0, dp(10), 0)
        val lp = LinearLayout.LayoutParams(-2, dp(if (leanback) 50 else 42)); lp.marginStart = dp(4); lp.marginEnd = dp(4); layoutParams = lp
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Check for site updates").setOnMenuItemClickListener { refreshLinks(true); true }
        popup.menu.add(if (prefs.getBoolean("adblock", true)) "Disable ad blocking" else "Enable ad blocking").setOnMenuItemClickListener { prefs.edit().putBoolean("adblock", !prefs.getBoolean("adblock", true)).apply(); toast("Ad blocking preference saved"); true }
        popup.menu.add(if (prefs.getBoolean("autoskip", true)) "Disable auto-skip" else "Enable auto-skip").setOnMenuItemClickListener { prefs.edit().putBoolean("autoskip", !prefs.getBoolean("autoskip", true)).apply(); true }
        popup.menu.add(if (prefs.getBoolean("restore", true)) "Do not restore last page" else "Restore last page").setOnMenuItemClickListener { prefs.edit().putBoolean("restore", !prefs.getBoolean("restore", true)).apply(); true }
        popup.menu.add("Share current page").setOnMenuItemClickListener { sharePage(); true }
        popup.menu.add("Edit selected site").setOnMenuItemClickListener { sites.firstOrNull { it.id == selectedKey }?.let { editSite(it) }; true }
        popup.menu.add("Mute / unmute selected pane").setOnMenuItemClickListener { prefs.edit().putBoolean("mute-$selectedKey", !prefs.getBoolean("mute-$selectedKey", false)).apply(); applyAudio(); true }
        popup.menu.add("Only focused pane plays sound").apply { isCheckable = true; isChecked = prefs.getBoolean("focusAudio", false); setOnMenuItemClickListener { prefs.edit().putBoolean("focusAudio", !isChecked).apply(); applyAudio(); true } }
        popup.menu.add("Close selected pane").setOnMenuItemClickListener { closePane(); true }
        popup.menu.add("About Football Arena").setOnMenuItemClickListener { AlertDialog.Builder(this).setTitle("Football Arena").setMessage("Android and Android TV preview · ${if (leanback) "TV" else "Mobile"} mode\nChat is not included.").setPositiveButton("OK", null).show(); true }
        popup.show()
    }

    private fun addSite() = siteDialog(null)
    private fun editSite(s: Site) = AlertDialog.Builder(this).setTitle(s.name).setItems(arrayOf("Open", "Edit", "Delete")) { _, which -> when (which) { 0 -> openSite(s); 1 -> siteDialog(s); 2 -> AlertDialog.Builder(this).setMessage("Delete ${s.name}?").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> sites.remove(s); saveSites() }.show() } }.show()

    private fun siteDialog(old: Site?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        val name = EditText(this).apply { hint = "Site name"; setText(old?.name ?: ""); setSingleLine(true) }
        val url = EditText(this).apply { hint = "https://example.com"; setText(old?.url ?: "https://"); setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        box.addView(name); box.addView(url)
        AlertDialog.Builder(this).setTitle(if (old == null) "Add site" else "Edit site").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
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
                    sites.clear(); sites.addAll(remote); custom.forEach { u -> if (sites.none { it.url == u.url }) sites.add(u) }
                    saveSites(); status.text = if (manual) "Site list updated" else "Ready"
                }
            } catch (_: Exception) { runOnUiThread { if (manual) status.text = "Could not update site list · using saved sites" } }
        }
    }

    private fun runAutoSkip(view: WebView = browser) {
        applyAudio()
        if (!prefs.getBoolean("autoskip", true)) return
        try {
            val js = assets.open("adskip.js").bufferedReader().use { it.readText() }
            view.evaluateJavascript(js, null)
            view.postDelayed({ if (!isFinishing) try { view.evaluateJavascript(js, null) } catch (_: Exception) {} }, 1800)
        } catch (_: Exception) { }
    }

    private fun sharePage() {
        val url = browser.url ?: return
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url)
        startActivity(android.content.Intent.createChooser(i, "Share page"))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun closePane() {
        val page = pages.remove(selectedKey) ?: return
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
            val muted = prefs.getBoolean("mute-$key", false) || (prefs.getBoolean("focusAudio", false) && key != selectedKey) || page.parent == null
            page.evaluateJavascript("(()=>{window.__faMuted=$muted;if(!window.__faSound){window.__faSound=true;setInterval(()=>document.querySelectorAll('video,audio').forEach(e=>e.muted=window.__faMuted),1000)}document.querySelectorAll('video,audio').forEach(e=>e.muted=window.__faMuted)})()", null)
        }
    }
    override fun onPause() { super.onPause(); pages.values.forEach { it.onPause() } }
    override fun onResume() { super.onResume(); if (::stage.isInitialized) renderPages() }
    private fun videoClient() = object : WebChromeClient() {
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
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && ::browser.isInitialized) {
            if (exitVideo()) return true
            if (browser.canGoBack()) { browser.goBack(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onDestroy() { pages.values.distinct().forEach { it.destroy() }; if (pages.isEmpty() && ::browser.isInitialized) browser.destroy(); io.shutdownNow(); super.onDestroy() }
}
