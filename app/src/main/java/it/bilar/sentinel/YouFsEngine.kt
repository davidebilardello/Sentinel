package it.bilar.sentinel

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

// The private, offline engine shares the web app's tested protocol. UI and GATT stay native.
@SuppressLint("SetJavaScriptEnabled")
class YouFsEngine(context: Context, private val write: (Int, ByteArray) -> Unit, private val event: (JSONObject) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var disposed = false
    private var loaded = false
    private var pendingProfile: YouFsProfile? = null
    private val web = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.blockNetworkLoads = true; settings.domStorageEnabled = false
        addJavascriptInterface(object {
            @JavascriptInterface fun packet(id: Int, encoded: String) {
                main.post {
                    if (disposed) return@post
                    try {
                        require(encoded.length <= 64)
                        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                        require(bytes.size in 1..20)
                        write(id, bytes)
                    } catch (_: Exception) { completeWrite(id, appText(R.string.bluetooth_write_failed)) }
                }
            }
            @JavascriptInterface fun event(encoded: String) {
                if (encoded.length > 131072) return
                main.post {
                    if (!disposed) try {
                        val data = JSONObject(encoded)
                        for(key in listOf("message","reason")) if(data.has(key)) data.put(key,AppLanguage.translate(data.getString(key)))
                        event(data)
                    }
                    catch (_: Exception) { event(JSONObject().put("type", "error").put("message", appText(R.string.invalid_bluetooth_engine_response))) }
                }
            }
        }, "NativeYouFs")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String) {
                if (!disposed) { loaded = true; pendingProfile?.let { start(it) }; pendingProfile = null }
            }
        }
    }
    init {
        val scripts = listOf("youfs/crypto-js-4.2.0.js", "youfs/tuya-ble.js", "youfs/engine.js").joinToString("\n") {
            "<script>" + context.assets.open(it).bufferedReader().use { r -> r.readText() }.replace("</script", "<\\/script") + "</script>"
        }
        web.loadDataWithBaseURL("https://sentinel.invalid/", "<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; connect-src 'none'; frame-src 'none'; base-uri 'none'\">$scripts", "text/html", "UTF-8", null)
    }
    private fun execute(script: String) { if (!disposed) web.evaluateJavascript(script, null) }
    fun start(profile: YouFsProfile) {
        if (!loaded) { pendingProfile = profile; return }
        val encoded = Base64.encodeToString(profile.json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        execute("engineStart(${JSONObject.quote(encoded)})")
    }
    fun receive(bytes: ByteArray) = execute("engineReceive(${JSONObject.quote(Base64.encodeToString(bytes, Base64.NO_WRAP))})")
    fun completeWrite(id: Int, error: String? = null) = execute("engineWriteDone($id,${error?.let(JSONObject::quote) ?: "null"})")
    fun command(id: Int, input: Any) = execute("engineCommand($id,${org.json.JSONArray().put(input).toString().drop(1).dropLast(1)})")
    fun query() = execute("engineQuery()")
    fun close() {
        if (disposed) return
        execute("engineClose()")
        disposed = true; pendingProfile = null; web.removeJavascriptInterface("NativeYouFs"); web.stopLoading(); web.destroy()
    }
}
