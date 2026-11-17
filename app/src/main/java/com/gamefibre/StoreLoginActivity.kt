package com.gamefibre

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gamefibre.stores.AmazonAuthChallenge
import com.gamefibre.stores.ExtraStoresApi
import com.gamefibre.stores.GameStore
import com.gamefibre.stores.StoreAccounts
import com.gamefibre.stores.StoreLoginResponse
import org.json.JSONObject
import org.json.JSONTokener

/** GOG returns a URL code; Epic may instead render its code in the final JSON page. */
class StoreLoginActivity : AppCompatActivity() {
    private var web: WebView? = null
    private var completed = false
    private var navigation = 0
    private var eaTokenRequested = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = GameStore.entries.firstOrNull { it.name == intent.getStringExtra("store") }
        val slotId = intent.getStringExtra("slotId")?.takeIf { it.isNotBlank() } ?: StoreAccounts.DEFAULT_SLOT
        if (store == null || store == GameStore.XBOX || store == GameStore.UBISOFT) { finish(); return }
        val amazonChallenge: AmazonAuthChallenge? = if (store == GameStore.AMAZON) ExtraStoresApi.amazonAuthChallenge() else null
        val initialUrl = when (store) {
            GameStore.AMAZON -> amazonChallenge!!.loginUrl
            GameStore.PSN -> "https://www.playstation.com/"
            else -> store.loginUrl
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ui = NativeUi(this)
        val root = ui.column().apply { setBackgroundColor(ui.background) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val heading = ui.row().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(8), ui.dp(8)) }
        heading.addView(ui.text("Sign in to ${store.label}", 22, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ui.iconButton(R.drawable.ic_x, "Close ${store.label} sign-in") { finish() })
        ui.add(root, heading)
        val status = ui.text("Complete sign-in below. This window closes automatically when your account is ready to connect.", 13, ui.secondary)
            .apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        ui.add(root, status)
        val retry = ui.button("Try sign-in again") {
            status.text = "Complete sign-in below. This window closes automatically when your account is ready to connect."
            status.setTextColor(ui.secondary)
            web?.stopLoading(); web?.loadUrl(initialUrl)
        }.apply { visibility = View.GONE }
        ui.add(root, retry)
        fun showError(message: String) {
            status.text = message; status.setTextColor(ui.error); retry.visibility = View.VISIBLE
        }
        fun connect(code: String) {
            if (completed || isFinishing || isDestroyed) return
            StoreAccounts.get(this).signIn(store, code, slotId)
            completed = true
            web?.stopLoading()
            CookieManager.getInstance().flush()
            finish()
        }
        if (store == GameStore.PSN) {
            val psnActions = ui.row().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8)) }
            psnActions.addView(ui.button("Complete sign-in", primary = true) {
                web?.loadUrl(ExtraStoresApi.PSN_LOGIN_URL)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
            psnActions.addView(ui.button("Paste NPSSO") {
                val dialog = ContentDialog(this, ui, "PlayStation NPSSO", secureContent = true)
                ui.add(dialog.content, ui.text(
                    "If Sony blocks the in-app browser, sign in at playstation.com in your browser, then open ca.account.sony.com/api/v1/ssocookie and paste the JSON or npsso value below.",
                    13,
                    ui.secondary
                ))
                ui.gap(dialog.content, 10)
                val browserRow = ui.row()
                browserRow.addView(ui.button("1. Open PSN") {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.playstation.com/")))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                browserRow.addView(ui.button("2. Open ssocookie") {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ExtraStoresApi.PSN_LOGIN_URL)))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                ui.add(dialog.content, browserRow)
                ui.gap(dialog.content, 12)
                val input = ui.input("Paste npsso or {\"npsso\":\"...\"}")
                ui.add(dialog.content, input)
                dialog.setActions(
                    ui.button("Connect", primary = true) {
                        val raw = input.text.toString().trim()
                        if (raw.isNotEmpty()) {
                            dialog.dismiss()
                            connect(raw)
                        }
                    },
                    ui.button("Cancel") { dialog.dismiss() }
                )
                dialog.show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            ui.add(root, psnActions)
        }
        fun receive(address: String): Boolean {
            if (completed) return true
            if (store == GameStore.EA && address.startsWith("nucleus:rest") && address.contains("access_token=")) {
                connect(address); return true
            }
            if (store == GameStore.AMAZON && amazonChallenge != null && address.startsWith("https://www.amazon.com")) {
                val code = address.toUri().getQueryParameter("openid.oa2.authorization_code")
                if (!code.isNullOrBlank()) {
                    val payload = JSONObject()
                        .put("code", code)
                        .put("serial", amazonChallenge.serial)
                        .put("clientId", amazonChallenge.clientId)
                        .put("codeVerifier", amazonChallenge.codeVerifier)
                        .toString()
                    connect(payload); return true
                }
            }
            if (!StoreLoginResponse.isCallback(store, address)) return false
            return try {
                val code = StoreLoginResponse.codeFromUrl(store, address)
                if (code != null) { connect(code); true }
                else if (store == GameStore.GOG) {
                    showError("GOG did not return a sign-in code. Try signing in again."); true
                } else false // Let Epic render its JSON in the authenticated browser session.
            } catch (_: Exception) {
                showError("${store.label} could not complete sign-in. Try signing in again."); true
            }
        }
        if (slotId != StoreAccounts.DEFAULT_SLOT) {
            CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        }
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    if (receive(request.url.toString())) return true
                    // HTTPS identity-provider redirects stay in this browser, preserving social-login sessions.
                    val uri = request.url
                    val allowed = uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.port == -1
                    if (!allowed) showError("This link cannot be opened in the sign-in window. Use another sign-in method on the store page.")
                    return !allowed
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    navigation++
                    if (receive(url)) view.stopLoading()
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (completed || receive(url)) return
                    if (store == GameStore.PSN) {
                        val cookies = CookieManager.getInstance().getCookie("https://ca.account.sony.com").orEmpty()
                        val npssoCookie = cookies.split(';').map { it.trim() }
                            .firstOrNull { it.startsWith("npsso=") }?.substringAfter("npsso=")
                        if (!npssoCookie.isNullOrBlank()) {
                            connect(npssoCookie); return
                        }
                        if (url.startsWith(ExtraStoresApi.PSN_LOGIN_URL) && view.url == url) {
                            val revision = navigation
                            view.evaluateJavascript("(function(){return document.body ? document.body.innerText.slice(0, 4096) : null;})()") { result ->
                                if (completed || isFinishing || isDestroyed || revision != navigation) return@evaluateJavascript
                                val body = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull().orEmpty()
                                val json = runCatching { JSONObject(body) }.getOrNull()
                                val npsso = json?.optString("npsso")
                                if (!npsso.isNullOrBlank()) {
                                    connect(npsso)
                                } else {
                                    val err = json?.optString("error")?.takeIf { it.isNotBlank() }
                                        ?: json?.optString("error_description")?.takeIf { it.isNotBlank() }
                                    if (err != null) {
                                        AppLogs.error("PSN", "PSN ssocookie returned error: $err")
                                        showError("Sign in on PlayStation first ($err), then tap Complete PlayStation sign-in.")
                                        view.loadUrl("https://www.playstation.com/")
                                    }
                                }
                            }
                        }
                        return
                    }
                    if (store == GameStore.EA && (url.startsWith("https://accounts.ea.com") || url.startsWith("https://www.ea.com")) && view.url == url) {
                        val cookies = listOf("https://www.ea.com", "https://accounts.ea.com", "https://signin.ea.com")
                            .joinToString(";") { CookieManager.getInstance().getCookie(it).orEmpty() }
                        val remid = cookies.split(';').map { it.trim() }
                            .firstOrNull { it.startsWith("remid=") }?.substringAfter("remid=")
                        if (!remid.isNullOrBlank()) {
                            AppLogs.log("EA", "Captured EA remid cookie from WebView")
                            connect("remid:$remid"); return
                        }
                        if (!eaTokenRequested && url.startsWith("https://www.ea.com") && !url.startsWith("https://www.ea.com/login")) {
                            eaTokenRequested = true
                            AppLogs.log("EA", "Requesting EA token via authenticated WebView session")
                            view.loadUrl(ExtraStoresApi.EA_TOKEN_URL)
                            return
                        }
                        val revision = navigation
                        view.evaluateJavascript("(function(){return document.body ? document.body.innerText.slice(0, 8192) : null;})()") { result ->
                            if (completed || isFinishing || isDestroyed || revision != navigation) return@evaluateJavascript
                            val body = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull().orEmpty()
                            val token = runCatching { JSONObject(body).optString("access_token") }.getOrNull()
                            if (!token.isNullOrBlank()) connect(body)
                        }
                        return
                    }
                    if (store != GameStore.EPIC || !StoreLoginResponse.isCallback(store, url) || view.url != url) return
                    val revision = navigation
                    // No JavaScript bridge and no second HTTP request. Read only the verified final page.
                    val address = org.json.JSONObject.quote(url)
                    view.evaluateJavascript("(function(){if(location.href !== $address) return null; return document.body ? document.body.innerText.slice(0, 16385) : null;})()") { result ->
                        if (completed || isFinishing || isDestroyed || revision != navigation || view.url != url) return@evaluateJavascript
                        try {
                            val body = JSONTokener(result).nextValue() as? String ?: ""
                            val code = StoreLoginResponse.epicPageCode(url, body)
                            if (code != null) connect(code)
                            else showError("Epic did not return a sign-in code. Try signing in again.")
                        } catch (_: Exception) {
                            showError("Epic could not complete sign-in. Check your account on Epic Games, then try again.")
                        }
                    }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && !completed) showError("The store could not load the sign-in page. Try signing in again.")
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !completed) showError("The sign-in page could not be reached. Check your connection and try again.")
                }
            }
        }
        web = view
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null || view.restoreState(savedInstanceState) == null) view.loadUrl(initialUrl)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (view.canGoBack()) view.goBack() else finish() }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val store = GameStore.entries.firstOrNull { it.name == intent.getStringExtra("store") }
        val slotId = intent.getStringExtra("slotId")?.takeIf { it.isNotBlank() } ?: StoreAccounts.DEFAULT_SLOT
        web?.let { view ->
            if (!completed && store != null && !StoreLoginResponse.isCallback(store, view.url.orEmpty())) view.saveState(outState)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        navigation++
        web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        web = null
        super.onDestroy()
    }
}
