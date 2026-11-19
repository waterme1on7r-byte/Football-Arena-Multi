package app.footballarena.mobile

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.util.WeakHashMap

object AppStrings {
    var language = "tr"
    private var catalog = mapOf<String, String>()
    private var pattern: Regex? = null
    private val original = WeakHashMap<TextView, Pair<String, String>>()
    private val metadata = WeakHashMap<View, MutableMap<String, Pair<String, String>>>()
    private var templates = listOf<Pair<Regex, String>>()
    fun init(context: Context) {
        language = context.getSharedPreferences("football-arena", 0).getString("language", "tr") ?: "tr"
        val json = JSONObject(context.assets.open("i18n-tr.json").bufferedReader().use { it.readText() })
        catalog = json.keys().asSequence().associateWith { json.getString(it) } + mapOf(
            "Security warning" to "Güvenlik uyarısı",
            "Certificate validation failed ({0}). Connection cancelled." to "Sertifika doğrulanamadı ({0}). Bağlantı iptal edildi.",
            "Safe Browsing blocked this page. This is a security warning, not a DPI failure." to "Güvenli Tarama bu sayfayı engelledi. Bu bir güvenlik uyarısıdır, DPI bağlantı hatası değildir.",
            "Page loaded with bypass" to "Seçili sayfa engel aşma ile yüklendi",
            "Bypass ready; access not verified" to "Engel aşma hazır; seçili sayfaya erişim doğrulanmadı",
            "Attempts" to "Bağlantı denemeleri",
            "Encrypted DNS + TLS records" to "Şifreli DNS + TLS kayıt parçalama",
            "Encrypted DNS + TCP split" to "Şifreli DNS + TCP parçalama"
        )
        templates = catalog.filterKeys { it.contains("{0}") }.map { (key, value) -> Regex("^" + key.split(Regex("\\{\\d+\\}")).joinToString("(.+?)") { Regex.escape(it) } + "$") to value }
        pattern = Regex(catalog.keys.filter { !it.contains("{0}") }.sortedByDescending { it.length }.joinToString("|") { "(?<![A-Za-z])" + Regex.escape(it) + "(?![A-Za-z])" })
    }
    fun t(source: String): String {
        if (language != "tr") return source
        catalog[source]?.let { return it }
        for ((regex, template) in templates) {
            val match = regex.matchEntire(source) ?: continue
            return Regex("\\{(\\d+)\\}").replace(template) { match.groupValues[it.groupValues[1].toInt() + 1] }
        }
        return pattern?.replace(source) { catalog[it.value] ?: it.value } ?: source
    }
    fun apply(view: View) {
        if (view.tag == "user-content" || view is android.webkit.WebView) return
        fun translated(key: String, current: String): String {
            val record = metadata.getOrPut(view) { mutableMapOf() }
            val old = record[key]; val source = if (old?.second == current) old.first else current
            val result = t(source); record[key] = source to result; return result
        }
        if (view is TextView) {
            if (view !is EditText) {
                val current = view.text.toString(); val previous = original[view]
                val source = if (previous?.second == current) previous.first else current
                val rendered = t(source); original[view] = source to rendered
                if (current != rendered) view.text = rendered
            }
            view.hint?.toString()?.let { val result = translated("hint", it); if (result != it) view.hint = result }
            view.error?.toString()?.let { val result = translated("error", it); if (result != it) view.error = result }
        }
        view.contentDescription?.toString()?.let { val result = translated("description", it); if (result != it) view.contentDescription = result }
        if (view is ViewGroup) for (index in 0 until view.childCount) apply(view.getChildAt(index))
    }
    fun watch(view: View) { apply(view); view.viewTreeObserver.addOnGlobalLayoutListener { apply(view) } }
}
