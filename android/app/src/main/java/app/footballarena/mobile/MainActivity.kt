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
import androidx.webkit.ProxyController
import androidx.webkit.ProxyConfig
import androidx.webkit.WebViewFeature

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
    private var mainPane = ""
    private var dpiProxy: DpiProxy? = null
    private var dpiState = "Off"
    private lateinit var dpiButton: Button
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
        layoutCount = prefs.getInt("layout", 1).coerceIn(0, 4)
        loadSites()
        buildUi()
        if (prefs.contains("passHash")) unlock() else startSession()
    }
    private fun startSession() {
        updateDpi(false)
        val previous = prefs.getString("last", "") ?: ""
        if (prefs.getBoolean("restore", true) && previous.startsWith("https://")) openUrl(previous)
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
        val dialog = AlertDialog.Builder(this).setTitle("Football Arena").setMessage("Enter your passcode").setView(input).setCancelable(false).setPositiveButton("Unlock", null).create()
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
        val d = AlertDialog.Builder(this).setTitle("Passcode").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
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
        root.addView(toolbar)
        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        dpiButton = button("DPI · Off") { dpiMenu() }
        tools.addView(dpiButton)
        tools.addView(button("Auto") { setLayout(0) })
        for (n in 1..4) tools.addView(button(n.toString()) { setLayout(n) })
        tools.addView(button("Close all") { pages.keys.toList().forEach { selectedKey = it; closePane() } })
        tools.addView(button("Settings") { settingsDialog() })
        tools.addView(button("Add site", true) { addSite() })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tools) })
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(panel); isFocusable = false }
        listBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        scroll.addView(listBar)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(if (leanback) 66 else 52)))
        listBar.addView(button("Quick access") { quickAccess() })
        browser = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            configureBrowser(this)
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
        footer.addView(TextView(this).apply { text = "WTMLJR"; setTextColor(gold); textSize = 12f })
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
        sites.filter { !it.quick }.forEach { s ->
            val b = button(s.name, s.id == selectedKey) { openSite(s) }
            b.contentDescription = "Open ${s.name}"
            b.setOnLongClickListener { editSite(s); true }
            listBar.addView(b)
        }
        listBar.addView(button("Quick access") { quickAccess() })
    }
    private fun quickAccess() {
        val quick = sites.filter { it.quick }
        AlertDialog.Builder(this).setTitle("Quick access").setItems(quick.map { it.name }.toTypedArray()) { _, n -> openSite(quick[n]) }.setNegativeButton("Close", null).show()
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
        configureBrowser(this)
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        webViewClient = pageClient
        webChromeClient = videoClient()
        setDownloadListener { url, _, _, _, _ -> try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) { toast("Cannot open this download") } }
    }

    private fun renderPages() {
        if (!::stage.isInitialized) return
        val count = if (layoutCount == 0) 4 else layoutCount
        val open = (listOf(selectedKey) + pages.keys.filter { it != selectedKey }).filter { it in pages }.take(count)
        val prominent = mainPane in open && open.size > 1
        val cols = if (open.size > 1) 2 else 1
        pages.values.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        stage.removeAllViews(); stage.rowCount = GridLayout.UNDEFINED; stage.columnCount = cols; stage.rowCount = if (prominent) open.size - 1 else (open.size + cols - 1) / cols
        val ordered = if (prominent) listOf(mainPane) + open.filter { it != mainPane } else open
        ordered.forEachIndexed { i, key ->
            val page = pages[key] ?: return@forEachIndexed
            val col = if (prominent) { if (i == 0) 0 else 1 } else i % cols
            val row = if (prominent) maxOf(0, i - 1) else i / cols
            val span = if (prominent && i == 0) open.size - 1 else 1
            val lp = GridLayout.LayoutParams(GridLayout.spec(row, span, GridLayout.FILL, 1f), GridLayout.spec(col, 1, GridLayout.FILL, if (prominent && i == 0) 2f else 1f)).apply {
                width = 0; height = 0; setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(panel) }
            val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            controls.addView(button(pageSites[key]?.name ?: "Page", key == selectedKey) { selectedKey = key; browser = page; renderSiteBar(); applyAudio() })
            controls.addView(button("Main", true) { mainPane = if (mainPane == key) "" else key; renderPages() })
            controls.addView(button("♫") { selectedKey = key; browser = page; volumeDialog(key) })
            controls.addView(button("↻") { page.reload() })
            controls.addView(button("×") { selectedKey = key; closePane() })
            pane.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(controls) })
            pane.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
            stage.addView(pane, lp)
            page.onResume()
            page.setOnFocusChangeListener { _, focused -> if (focused) { selectedKey = key; browser = page; applyAudio() } }
            page.visibility = View.VISIBLE
            page.alpha = if (key == selectedKey) 1f else 0.96f
        }
        pages.filterKeys { it !in open }.values.forEach { it.onPause() }
        applyAudio()
    }

    private fun setLayout(n: Int) { mainPane = ""; layoutCount = n; prefs.edit().putInt("layout", n).apply(); renderPages() }
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
        popup.menu.add("About Football Arena").setOnMenuItemClickListener { AlertDialog.Builder(this).setTitle("Football Arena").setMessage("Android and Android TV preview · ${if (leanback) "TV" else "Mobile"} mode\nChat is not included.").setPositiveButton("OK", null).show(); true }
        popup.show()
    }

    private fun addSite() = siteDialog(null)
    private fun editSite(s: Site) = AlertDialog.Builder(this).setTitle(s.name).setItems(arrayOf("Open", "Edit", "Delete", if (s.quick) "Remove from quick access" else "Add to quick access")) { _, which -> when (which) { 0 -> openSite(s); 1 -> siteDialog(s); 2 -> AlertDialog.Builder(this).setMessage("Delete ${s.name}?").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> sites.remove(s); prefs.edit().putStringSet("deleted", (prefs.getStringSet("deleted", emptySet())!! + s.id).toSet()).apply(); saveSites() }.show(); 3 -> { val i = sites.indexOf(s); sites[i] = s.copy(quick = !s.quick, custom = true); saveSites() } } }.show()

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
                    val deleted = prefs.getStringSet("deleted", emptySet())!!
                    sites.clear(); sites.addAll(remote.filter { it.id !in deleted && custom.none { c -> c.id == it.id } }); custom.forEach { u -> if (sites.none { it.url == u.url }) sites.add(u) }
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
            runOnUiThread { if (!isFinishing) { status.text = "Link checks finished"; AlertDialog.Builder(this).setTitle("Link health").setMessage(result).setPositiveButton("Close", null).show() } }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpiMenu() {
        AlertDialog.Builder(this).setTitle("DPI bypass · $dpiState")
            .setItems(arrayOf(if (dpiProxy == null) "Turn on" else "Turn off", "Restart", "Option 1 · 8-byte fragments", "Option 2 · 32-byte fragments", "Option 3 · 64-byte fragments")) { _, n ->
                if (n >= 2) prefs.edit().putInt("dpiFragment", listOf(8, 32, 64)[n - 2]).putBoolean("dpi", true).apply()
                else prefs.edit().putBoolean("dpi", if (n == 1) true else dpiProxy == null).apply()
                updateDpi(true)
            }.setNegativeButton("Close", null).show()
    }
    private fun updateDpi(reload: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) { dpiState = "Unsupported WebView"; dpiButton.text = "DPI · $dpiState"; return }
        dpiState = "Starting"; dpiButton.text = "DPI · $dpiState"
        val executor = java.util.concurrent.Executor { runOnUiThread(it) }
        // Clear old routing before stopping its listener, then apply the replacement.
        ProxyController.getInstance().clearProxyOverride(executor) {
            dpiProxy?.stop(); dpiProxy = null
            if (!prefs.getBoolean("dpi", false)) { dpiState = "Off"; dpiButton.text = "DPI · Off"; if (reload) pages.values.forEach { it.reload() }; return@clearProxyOverride }
            try {
                val proxy = DpiProxy(prefs.getInt("dpiFragment", 32).coerceIn(8, 64))
                val port = proxy.start(); dpiProxy = proxy
                val config = ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port", "https").addDirect().build()
                ProxyController.getInstance().setProxyOverride(config, executor) {
                    dpiState = "On"; dpiButton.text = "DPI · On"
                    if (reload) pages.values.forEach { it.reload() }
                }
            } catch (e: Exception) { dpiProxy?.stop(); dpiProxy = null; dpiState = "Failed"; dpiButton.text = "DPI · Failed"; toast(e.message ?: "Could not start DPI bypass") }
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
        AlertDialog.Builder(this).setTitle("Sound").setView(box).setPositiveButton("Done", null).show()
    }
    private fun settingsDialog() {
        val sections = arrayOf("App", "Sound & Video", "Links", "Network")
        AlertDialog.Builder(this).setTitle("Settings").setItems(sections) { _, section ->
            when (section) {
                0 -> showMenu(title)
                1 -> AlertDialog.Builder(this).setTitle("Sound & Video").setMultiChoiceItems(arrayOf("Remember last page and layout", "Only focused pane plays sound"), booleanArrayOf(prefs.getBoolean("restore", true), prefs.getBoolean("focusAudio", false))) { _, i, on -> prefs.edit().putBoolean(if (i == 0) "restore" else "focusAudio", on).apply(); applyAudio() }.setPositiveButton("Done", null).show()
                2 -> refreshLinks(true)
                3 -> AlertDialog.Builder(this).setTitle("Network").setItems(arrayOf("DPI bypass · $dpiState", "Ad blocking and auto-skip")) { _, i -> if (i == 0) dpiMenu() else showMenu(title) }.setPositiveButton("Done", null).show()
            }
        }.setNegativeButton("Close", null).show()
    }
    private fun configureBrowser(view: WebView) {
        // Third-party players often use cookies and HTTP media within HTTPS pages.
        // Keep certificate validation and the normal WebView origin protections intact.
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        if (android.os.Build.VERSION.SDK_INT >= 29) view.isForceDarkAllowed = false
    }
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
            val volume = prefs.getInt("volume-$key", 100).coerceIn(0, 100) / 100.0
            page.evaluateJavascript("(()=>{window.__faVolume=$volume;if(!window.__faVolumeTimer){window.__faVolumeTimer=true;setInterval(()=>document.querySelectorAll('video,audio').forEach(e=>e.volume=window.__faVolume),1000)}document.querySelectorAll('video,audio').forEach(e=>e.volume=window.__faVolume)})()", null)
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
    override fun onDestroy() { dpiProxy?.stop(); pages.values.distinct().forEach { it.destroy() }; if (pages.isEmpty() && ::browser.isInitialized) browser.destroy(); io.shutdownNow(); super.onDestroy() }
}
