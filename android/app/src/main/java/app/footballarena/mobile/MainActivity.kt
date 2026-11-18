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
    private val bg = Color.rgb(11, 15, 20)
    private val panel = Color.rgb(18, 25, 35)
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
        val wide = resources.configuration.screenWidthDp >= 1000
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(38,33,24), bg, bg))
        }
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, dp(16), 0) }
        if (wide) brand.addView(ImageView(this).apply { setImageResource(app.footballarena.mobile.R.drawable.arena_mark); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(48), dp(48)))
        title = TextView(this).apply { text = if (wide) "FOOTBALL ARENA" else "FOOTBALL\nARENA"; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0); setTextColor(gold); textSize = if (wide) 30f else 18f; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
        brand.addView(title); toolbar.addView(brand)
        val tools = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        dpiButton = button("DPI · Off") { dpiMenu() }
        tools.addView(dpiButton)
        tools.addView(button("Layout ▾") { AlertDialog.Builder(this).setTitle("Layout").setSingleChoiceItems(arrayOf("Auto", "1 pane", "2 panes", "3 panes", "4 panes"), layoutCount) { d, n -> setLayout(n); d.dismiss() }.show() })
        tools.addView(button("Close all") { pages.keys.toList().forEach { selectedKey = it; closePane() } })
        tools.addView(button("⚙ Settings") { settingsDialog() })
        tools.addView(button("Add site", true) { addSite() })
        toolbar.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(tools) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(toolbar)
        root.addView(View(this).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(gold, Color.rgb(34,48,66), bg)) }, LinearLayout.LayoutParams(-1, dp(1)))
        val siteRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFocusable = false }
        listBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        scroll.addView(listBar)
        siteRow.addView(scroll, LinearLayout.LayoutParams(0, dp(if (leanback) 60 else 50), 1f))
        siteRow.addView(View(this).apply { setBackgroundColor(Color.rgb(34,48,66)) }, LinearLayout.LayoutParams(dp(1), dp(26)).apply { marginStart = dp(12); marginEnd = dp(12) })
        siteRow.addView(button("ϟ Quick access", true) { quickAccess() })
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
        stage = GridLayout(this).apply { setBackgroundColor(bg); useDefaultMargins = false; setPadding(dp(14), dp(2), dp(14), dp(10)) }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(Color.rgb(34,48,66)) }, LinearLayout.LayoutParams(-1, dp(1)))
        val footer = FrameLayout(this).apply { setPadding(dp(14), dp(4), dp(14), dp(4)); setBackgroundColor(bg) }
        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f; text = "Ready" }
        status.gravity = Gravity.CENTER_VERTICAL; status.maxWidth = dp(180)
        footer.addView(status, FrameLayout.LayoutParams(-2, dp(32), Gravity.START or Gravity.CENTER_VERTICAL))
        footer.addView(TextView(this).apply { text = "WTMLJR"; setTextColor(gold); textSize = 12f; letterSpacing = .25f; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-2, dp(32), Gravity.CENTER))
        root.addView(footer)
        setContentView(root)
        renderSiteBar()
    }

    private fun renderSiteBar() {
        if (!::listBar.isInitialized) return
        listBar.removeAllViews()
        sites.filter { !it.quick }.forEach { s ->
            val isOpen = pages.containsKey(s.id)
            val b = button((if (isOpen) "●  " else "") + s.name) { openSite(s) }
            b.setTextColor(if (isOpen) gold else text)
            b.background = tabBackground(isOpen, s.id == selectedKey)
            b.contentDescription = "${s.name}, ${if (isOpen) "open" else "closed"}${if (s.id == selectedKey) ", focused" else ""}"
            b.isSelected = isOpen
            b.setOnLongClickListener { editSite(s); true }
            listBar.addView(b)
        }
    }
    private fun quickAccess() {
        val quick = sites.filter { it.quick }
        AlertDialog.Builder(this).setTitle("Quick access").setItems(quick.map { it.name }.toTypedArray()) { _, n -> openSite(quick[n]) }.setNegativeButton("Close", null).show()
    }

    private fun openSite(site: Site) {
        selectedKey = site.id
        val existing = pages[site.id]
        if (existing != null) {
            browser = existing; selected = site.id
            renderSiteBar()
            if (existing.parent == null) renderPages() else { existing.requestFocus(); applyAudio() }
            status.text = site.name
            return
        }
        if (!pages.containsKey(site.id)) {
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
                width = 0; height = 0; setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = controlBackground(false); clipToOutline = true; setPadding(dp(1), dp(1), dp(1), dp(1)) }
            val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
            controls.addView(button(pageSites[key]?.name ?: "Page", key == selectedKey) { selectedKey = key; browser = page; renderSiteBar(); applyAudio() })
            controls.addView(button("▣", mainPane == key) { mainPane = if (mainPane == key) "" else key; renderPages() }.apply { contentDescription = "Toggle main pane" })
            controls.addView(button("♫") { selectedKey = key; browser = page; volumeDialog(key) })
            controls.addView(button("↻") { page.reload() })
            controls.addView(button("×") { selectedKey = key; closePane() })
            pane.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(controls) })
            pane.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
            stage.addView(pane, lp)
            page.onResume()
            page.setOnFocusChangeListener { _, focused -> if (focused) { selectedKey = key; browser = page; renderSiteBar(); applyAudio() } }
            page.visibility = View.VISIBLE
            page.alpha = 1f
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
        background = controlBackground(primary)
        minHeight = dp(if (leanback) 50 else 42); minimumHeight = minHeight; minWidth = dp(if (leanback) 72 else 48)
        isFocusable = true; setOnClickListener { action(it) }; setPadding(dp(10), 0, dp(10), 0)
        val lp = LinearLayout.LayoutParams(-2, dp(if (leanback) 50 else 42)); lp.marginStart = dp(4); lp.marginEnd = dp(4); layoutParams = lp
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
        val p = dpiProxy
        AlertDialog.Builder(this).setTitle("DPI bypass · $dpiState")
            .setItems(arrayOf(if (dpiProxy == null) "Turn on" else "Turn off", "Restart", "Option 1 · 8-byte fragments", "Option 2 · 32-byte fragments", "Option 3 · 64-byte fragments", "Diagnostics · ${p?.connections?.get() ?: 0} routed connections")) { _, n ->
                if (n == 5) { AlertDialog.Builder(this).setTitle("DPI diagnostics").setMessage("App HTTPS traffic only. Other apps and Android DNS are not routed.\n\nConnections: ${p?.connections?.get() ?: 0}\nFragmented handshakes sent: ${p?.handshakes?.get() ?: 0}\nServer replies: ${p?.serverReplies?.get() ?: 0}\nReceived bytes: ${p?.receivedBytes?.get() ?: 0}\nErrors: ${p?.failures?.get() ?: 0}\nLast host: ${p?.lastHost ?: "None"}\nLast error: ${p?.lastError ?: "None"}\n\nThese counts verify routing and transmission, not successful bypass. If counts stay zero after a page reload, no HTTPS traffic reached this proxy.").setPositiveButton("Close", null).show(); return@setItems }
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
                val config = ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port", "https").build()
                ProxyController.getInstance().setProxyOverride(config, executor) {
                    dpiState = "Proxy ready"; dpiButton.text = "DPI · Ready"
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
        val dialog = android.app.Dialog(this)
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(12)); setBackgroundColor(bg) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = "Settings"; textSize = 26f; setTextColor(this@MainActivity.text); typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, dp(58), 1f))
        head.addView(button("Close") { dialog.dismiss() }); shell.addView(head)
        val tabs = LinearLayout(this)
        shell.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(20), 0, dp(10)) }
        shell.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        fun heading(label: String, description: String) {
            content.addView(TextView(this).apply { text = label; textSize = 22f; setTextColor(gold); setPadding(0, 0, 0, dp(6)) })
            content.addView(TextView(this).apply { text = description; textSize = 14f; setTextColor(Color.rgb(134,150,168)); setPadding(0, 0, 0, dp(22)) })
        }
        fun row(label: String, description: String, key: String? = null, default: Boolean = false, action: (() -> Unit)? = null) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(10), dp(14)); background = controlBackground(false) }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(10), 0) }
            labels.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(this@MainActivity.text) })
            labels.addView(TextView(this).apply { text = description; textSize = 13f; setPadding(0, dp(5), 0, 0); setTextColor(Color.rgb(134,150,168)) })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            if (key != null) row.addView(Switch(this).apply {
                contentDescription = label; isChecked = prefs.getBoolean(key, default)
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putBoolean(key, on).apply()
                    if (key == "dpi") updateDpi(true)
                    if (key == "focusAudio") applyAudio()
                    if (key == "autoskip") pages.values.forEach { it.reload() }
                }
            }) else row.addView(button("Open") { action?.invoke() })
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        fun section(n: Int) {
            content.removeAllViews()
            when (n) {
                0 -> {
                    heading("App", "Football Arena · Android preview 1.8.6")
                    row("Restore session", "Remember the last page and layout.", "restore", true)
                    row("Passcode", "Ask for a four-digit code when the app starts.", action = { changePass() })
                    row("Export sites", "Save a JSON file compatible with the desktop app.", action = { documentPicker(true) })
                    row("Import sites", "Add sites from a saved JSON file.", action = { documentPicker(false) })
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
                    heading("Network", "DPI routing affects this app’s HTTPS browser traffic only. Android DNS and other apps are unchanged.")
                    row("Experimental DPI bypass", "Routes HTTPS through the local TLS-fragmentation proxy. Success depends on the network.", "dpi")
                    row("DPI options & diagnostics", "See routed connections, handshakes, errors, and fragment options.", action = { dpiMenu() })
                    row("Ad blocking", "Block known advertising hosts.", "adblock", true)
                    row("Auto-skip", "Attempt to skip supported video ads.", "autoskip", true)
                }
            }
        }
        arrayOf("App", "Sound & Video", "Links", "Network").forEachIndexed { i, label -> tabs.addView(button(label) { section(i) }) }
        section(0); dialog.setContentView(shell); dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(if (leanback) dp(850) else -1, -1)
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

