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
            "Application" to "Uygulama",
            "Site list" to "Site listesi",
            "Site list transfer" to "Dışa aktar / İçe aktar",
            "Choose the app display language." to "Uygulamanın görüntüleme dilini seçin.",
            "Remember open sites and the pane layout." to "Açık siteleri ve bölme düzenini hatırla.",
            "Enter your four-digit password when the app starts." to "Açılışta dört haneli şifrenizi girin.",
            "Save your sites or load a saved list." to "Sitelerinizi kaydedin veya kayıtlı listenizi yükleyin.",
            "Enter code" to "Kod Girin",
            "Remove" to "Kaldır",
            "Active" to "Etkin",
            "Export" to "Dışa aktar",
            "Import" to "İçe aktar",
            "About" to "Hakkında",
            "Version" to "Sürüm",
            "Adjust volume" to "Sesi ayarla",
            "Update links" to "Bağlantıları güncelle",
            "Check" to "Kontrol et",
            "View" to "Görüntüle",
            "Share" to "Paylaş",
            "Move left" to "Sola taşı",
            "Move right" to "Sağa taşı",
            "Name" to "Ad",
            "Save this temporary tab to your site list." to "Bu geçici sekmeyi site listenize kaydedin.",
            "Enter a site name" to "Bir site adı girin",
            "Enter a valid HTTP or HTTPS address" to "Geçerli bir HTTP veya HTTPS adresi girin",
            "App lock" to "Giriş kilidi",
            "Welcome back" to "Tekrar hoş geldiniz",
            "Create an app lock" to "Giriş kilidi oluştur",
            "Change your password" to "Şifrenizi değiştirin",
            "Remove app lock" to "Giriş kilidini kaldır",
            "New password:" to "Yeni şifre:",
            "Re-enter new password:" to "Yeni şifreyi tekrar girin:",
            "Current password:" to "Mevcut şifre:",
            "Enter your four-digit password" to "Dört haneli şifrenizi girin",
            "Unlock Football Arena to continue." to "Devam etmek için Football Arena kilidini açın.",
            "Verify your current password to remove the lock." to "Kilidi kaldırmak için mevcut şifrenizi doğrulayın.",
            "Use a four-digit password for Football Arena." to "Football Arena için dört haneli bir şifre belirleyin.",
            "Continues automatically after four digits." to "4 hane girildiğinde otomatik devam eder.",
            "Delete digit" to "Sil",
            "Delete last digit" to "Son haneyi sil",
            "{0} of 4 digits entered" to "4 haneden {0} tanesi girildi",
            "Too many attempts. Try again in 30 seconds." to "Çok fazla deneme. 30 saniye sonra tekrar deneyin.",
            "Incorrect password. Try again." to "Şifre yanlış. Tekrar deneyin.",
            "Passwords do not match. Try again." to "Şifreler eşleşmiyor. Tekrar deneyin.",
            "Remove the app lock? Verify your current password to continue." to "Giriş kilidi kaldırılsın mı? Devam etmek için mevcut şifrenizi doğrulayın.",
            "Verify and remove" to "Doğrula ve kaldır",
            "Save site" to "Siteyi kaydet",
            "Ad blocking diagnostics" to "Reklam engelleme tanılaması",
            "Check blocking activity and embedded-player support." to "Engelleme etkinliğini ve gömülü oynatıcı desteğini kontrol edin.",
            "Advertising domains" to "Reklam alan adları",
            "Same-site player fallback available. Update WebView for cross-site players." to "Aynı sitedeki oynatıcılar destekleniyor. Farklı sitelerdeki oynatıcılar için WebView'i güncelleyin.",
            "Promotional elements hidden on this page" to "Bu sayfada gizlenen reklam öğeleri",
            "Requests blocked this session" to "Bu oturumda engellenen istekler",
            "Service-worker requests blocked" to "Engellenen servis çalışanı istekleri",
            "Skip buttons pressed on this page" to "Bu sayfada basılan reklam atlama düğmeleri",
            "Embedded-player support" to "Gömülü oynatıcı desteği",
            "Available" to "Kullanılabilir",
            "Update Android System WebView for embedded-player auto-skip." to "Gömülü oynatıcıda otomatik atlama için Android System WebView'i güncelleyin.",
            "DPI bypass" to "DPI engel aşma",
            "Enable or disable DPI bypass for this app." to "Bu uygulama için DPI engel aşmayı açın veya kapatın.",
            "Embedded content error" to "Gömülü içerik hatası",
            "Deceptive site warning" to "Yanıltıcı site uyarısı",
            "Back to safety" to "Güvenli sayfaya dön",
            "Open once" to "Bir kez aç",
            "Android Safe Browsing flagged this address as deceptive. Continue only if you trust the site. Opening once does not mark it safe or disable protection for other pages." to "Android Güvenli Tarama bu adresi yanıltıcı olarak işaretledi. Yalnızca siteye güveniyorsanız devam edin. Bir kez açmak, siteyi güvenli olarak işaretlemez veya diğer sayfaların korumasını kapatmaz.",
            "Security warning" to "Güvenlik uyarısı",
            "Certificate validation failed ({0}). Connection cancelled." to "Sertifika doğrulanamadı ({0}). Bağlantı iptal edildi.",
            "Safe Browsing blocked this page. This is a security warning, not a DPI failure." to "Güvenli Tarama bu sayfayı engelledi. Bu bir güvenlik uyarısıdır, DPI bağlantı hatası değildir.",
            "Page loaded with bypass" to "Seçili sayfa engel aşma ile yüklendi",
            "Bypass ready; access not verified" to "Engel aşma hazır; seçili sayfaya erişim doğrulanmadı",
            "Attempts" to "Bağlantı denemeleri",
            "Encrypted DNS + TLS records" to "Şifreli DNS + TLS kayıt parçalama",
            "Encrypted DNS + TCP split" to "Şifreli DNS + TCP parçalama",
            "Encrypted DNS, unchanged TLS" to "Şifreli DNS, değiştirilmemiş TLS",
            "Automatic uses encrypted DNS for HTTPS pages and resources, and adapts TLS fragmentation when needed." to "Otomatik mod, HTTPS sayfaları ve kaynakları için şifreli DNS kullanır; gerektiğinde TLS parçalamayı uyarlar.",
            "Learned routes expire after four hours or when the site address changes." to "Öğrenilen yollar dört saat sonra veya site adresi değiştiğinde sıfırlanır."
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
