package com.gamefibre.stores

import android.net.Uri
import android.util.Base64
import androidx.core.net.toUri
import com.gamefibre.GameDetails
import com.gamefibre.Http
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal data class AmazonAuthChallenge(
    val loginUrl: String,
    val serial: String,
    val clientId: String,
    val codeVerifier: String,
)

internal sealed class UbisoftAuthResult {
    data class Success(val session: StoreSession) : UbisoftAuthResult()
    data class TwoFactorRequired(val ticket: String) : UbisoftAuthResult()
}

/**
 * Provider APIs for Ubisoft Connect, EA App, PlayStation Network, and Amazon Games.
 * Endpoints and protocol parameters mirror the verified NUC game-inventory collectors.
 */
internal object ExtraStoresApi {
    private val hosts = setOf(
        "public-ubiservices.ubi.com",
        "accounts.ea.com",
        "gateway.ea.com",
        "service-aggregation-layer.juno.ea.com",
        "ca.account.sony.com",
        "m.np.playstation.com",
        "api.amazon.com",
        "gaming.amazon.com",
    )

    private const val UBI_APP_ID = "f68a4bb5-608a-4ff2-8123-be8ef797e0a6"
    private const val UBI_SESSIONS_URL = "https://public-ubiservices.ubi.com/v3/profiles/sessions"
    private const val UBI_GRAPHQL_URL = "https://public-ubiservices.ubi.com/v1/profiles/me/uplay/graphql"

    private const val EA_CLIENT_ID = "ORIGIN_JS_SDK"
    private const val EA_REDIRECT_URI = "nucleus:rest"
    const val EA_LOGIN_URL = "https://www.ea.com/login"
    const val EA_TOKEN_URL =
        "https://accounts.ea.com/connect/auth?client_id=$EA_CLIENT_ID&response_type=token&redirect_uri=$EA_REDIRECT_URI&prompt=none"

    private const val PSN_AUTH_BASE = "https://ca.account.sony.com/api/authz/v3/oauth"
    private const val PSN_CLIENT_ID = "09515159-7237-4370-9b40-3806e67c0891"
    private const val PSN_BASIC_AUTH = "MDk1MTUxNTktNzIzNy00MzcwLTliNDAtMzgwNmU2N2MwODkxOnVjUGprYTV0bnRCMktxc1A="
    private const val PSN_REDIRECT_URI = "com.scee.psxandroid.scecompcall://redirect"
    private const val PSN_SCOPES = "psn:mobile.v2.core psn:clientapp"
    const val PSN_LOGIN_URL = "https://ca.account.sony.com/api/v1/ssocookie"

    // --- Dispatchers from StoreApi ---

    fun signIn(store: GameStore, code: String): StoreSession = when (store) {
        GameStore.UBISOFT -> throw IllegalStateException("Use Ubisoft sign-in dialog.")
        GameStore.EA -> eaSignIn(code)
        GameStore.PSN -> psnSignIn(code)
        GameStore.AMAZON -> amazonSignIn(code)
        else -> throw IllegalArgumentException("Unsupported extra store: $store")
    }

    fun refresh(store: GameStore, refreshToken: String, owner: String): StoreSession = when (store) {
        GameStore.UBISOFT -> ubisoftRefresh(refreshToken, owner)
        GameStore.EA -> eaRefresh(refreshToken, owner)
        GameStore.PSN -> psnRefresh(refreshToken, owner)
        GameStore.AMAZON -> amazonRefresh(refreshToken, owner)
        else -> throw IllegalArgumentException("Unsupported extra store: $store")
    }

    suspend fun library(store: GameStore, session: StoreSession): List<StoreGame> = when (store) {
        GameStore.UBISOFT -> ubisoftLibrary(session)
        GameStore.EA -> eaLibrary(session)
        GameStore.PSN -> psnLibrary(session)
        GameStore.AMAZON -> amazonLibrary(session)
        else -> emptyList()
    }

    // --- Ubisoft Connect ---

    fun ubisoftAuthenticate(
        email: String,
        password: String,
        twoFaTicket: String? = null,
        twoFaCode: String? = null,
    ): UbisoftAuthResult {
        val is2fa = !twoFaTicket.isNullOrBlank() && !twoFaCode.isNullOrBlank()
        com.gamefibre.AppLogs.log("UBISOFT", "Starting Ubisoft sign-in (step=${if (is2fa) "2fa" else "credentials"}" +
            (if (is2fa) ", ticketLength=${twoFaTicket!!.length}, codeLength=${twoFaCode!!.replace("\\s+".toRegex(), "").length})" else ")"))
        val headers = linkedMapOf(
            "Ubi-AppId" to UBI_APP_ID,
            "Ubi-RequestedPlatformType" to "uplay",
        )
        if (is2fa) {
            // Answer the pending challenge with its ticket. Re-sending credentials starts a new
            // challenge and makes Ubisoft issue another code.
            headers["Authorization"] = "ubi_2fa_v1 t=$twoFaTicket"
            headers["Ubi-2faCode"] = twoFaCode!!.replace("\\s+".toRegex(), "")
        } else {
            val basic = Base64.encodeToString("${email.trim()}:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            headers["Authorization"] = "Basic $basic"
        }
        val response = postJson(UBI_SESSIONS_URL, JSONObject().put("rememberMe", true), headers, userAgent = "Massgate")
        if (!response.ok) {
            val errJson = runCatching { JSONObject(response.text) }.getOrNull()
            val errorCode = errJson?.optInt("errorCode", -1)?.takeIf { it >= 0 }
            val apiMsg = errJson?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }
            val detail = listOfNotNull(
                "HTTP ${response.status}",
                errorCode?.let { "errorCode=$it" },
                apiMsg,
            ).joinToString(", ")
            com.gamefibre.AppLogs.error("UBISOFT", "Ubisoft sign-in failed ($detail)")
            if (response.status == 401 || response.status == 403) {
                throw IllegalStateException("Ubisoft rejected your credentials or 2FA code ($detail).")
            }
            throw IllegalStateException("Ubisoft sign-in failed ($detail).")
        }
        val json = JSONObject(response.text)
        val pending2fa = json.ubiText("twoFactorAuthenticationTicket")
        if (pending2fa.isNotBlank()) {
            // Diagnostics only: field names and ticket shape, never the ticket itself.
            com.gamefibre.AppLogs.log("UBISOFT", "Ubisoft 2FA verification required (step=${if (is2fa) "2fa" else "credentials"}, " +
                "fields=${json.keys().asSequence().sorted().joinToString(",")}, ticketLength=${pending2fa.length}, " +
                "codeGenerationPreference=${json.opt("codeGenerationPreference")})")
            return UbisoftAuthResult.TwoFactorRequired(pending2fa)
        }
        com.gamefibre.AppLogs.log("UBISOFT", "Ubisoft sign-in succeeded")
        return UbisoftAuthResult.Success(parseUbisoftSession(json, expectedOwner = null))
    }

    private fun ubisoftRefresh(rememberMeTicket: String, owner: String): StoreSession {
        val response = postJson(
            UBI_SESSIONS_URL,
            JSONObject().put("rememberMe", true),
            mapOf("Ubi-AppId" to UBI_APP_ID, "Authorization" to "rm_v1 t=$rememberMeTicket"),
            userAgent = "Massgate",
        )
        if (response.status == 401 || response.status == 403) throw StoreSessionExpired()
        if (!response.ok) throw IllegalStateException("Ubisoft session refresh failed (HTTP ${response.status}).")
        return parseUbisoftSession(JSONObject(response.text), expectedOwner = owner, fallbackRefresh = rememberMeTicket)
    }

    /** Ubisoft sends absent values as JSON null; optString would turn them into the text "null". */
    private fun JSONObject.ubiText(name: String): String = if (isNull(name)) "" else optString(name)

    private fun parseUbisoftSession(
        json: JSONObject,
        expectedOwner: String?,
        fallbackRefresh: String = "",
    ): StoreSession {
        val ticket = json.ubiText("ticket")
        val sessionId = json.ubiText("sessionId")
        val userId = json.ubiText("userId").ifBlank { expectedOwner.orEmpty() }
        val rememberMe = json.ubiText("rememberMeTicket").ifBlank { fallbackRefresh }
        check(ticket.isNotBlank() && userId.isNotBlank() && rememberMe.isNotBlank()) {
            "Ubisoft did not return a complete session. Try signing in again."
        }
        check(expectedOwner == null || expectedOwner == userId) {
            "Ubisoft returned a different account. Sign out and connect it again."
        }
        val accessPayload = JSONObject().put("ticket", ticket).put("sessionId", sessionId).toString()
        // Ubisoft tickets last ~3 hours; refresh proactively after 2 hours.
        return StoreSession(accessPayload, rememberMe, userId, System.currentTimeMillis() + 2 * 3600_000L)
    }

    private suspend fun ubisoftLibrary(session: StoreSession): List<StoreGame> {
        coroutineContext.ensureActive()
        val auth = JSONObject(session.accessToken)
        val ticket = auth.getString("ticket")
        val sessionId = auth.optString("sessionId")
        val query = """
            query Games {
                viewer {
                    games {
                        edges {
                            node {
                                id
                                spaceId
                                name
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        val response = postJson(
            UBI_GRAPHQL_URL,
            JSONObject().put("query", query),
            mapOf(
                "Ubi-AppId" to UBI_APP_ID,
                "Authorization" to "Ubi_v1 t=$ticket",
                "Ubi-SessionId" to sessionId,
            ),
            userAgent = "Massgate",
        )
        if (response.status == 401 || response.status == 403) throw StoreSessionExpired()
        if (!response.ok) throw IllegalStateException("Ubisoft library request failed (HTTP ${response.status}).")
        val edges = JSONObject(response.text)
            .optJSONObject("data")?.optJSONObject("viewer")?.optJSONObject("games")?.optJSONArray("edges")
            ?: JSONArray()
        val games = linkedMapOf<String, StoreGame>()
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            val gid = node.optString("spaceId").ifBlank { node.optString("id") }
            if (gid.isBlank() || gid == "null") continue
            val name = node.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "Ubisoft Game $gid"
            games[gid] = StoreGame(gid, name)
        }
        return games.values.sortedBy { it.name.lowercase() }
    }

    // --- EA App / Origin ---

    private fun eaSignIn(pasted: String): StoreSession {
        val raw = pasted.trim()
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        val remid = parsed?.optString("remid")?.takeIf { it.isNotBlank() }
            ?: if (raw.startsWith("remid:")) raw.removePrefix("remid:").trim() else null
        var accessToken = parsed?.optString("access_token")?.takeIf { it.isNotBlank() } ?: ""
        if (accessToken.isBlank() && raw.contains("access_token=")) {
            val fragment = raw.substringAfter("#", raw)
            accessToken = fragment.split('&').firstNotNullOfOrNull { part ->
                val kv = part.split('=', limit = 2)
                if (kv.size == 2 && kv[0] == "access_token") URLDecoder.decode(kv[1], "UTF-8") else null
            }.orEmpty()
        }
        if (accessToken.isBlank() && remid != null) {
            accessToken = exchangeEaRemid(remid)
        }
        if (accessToken.isBlank() && !raw.startsWith("{") && !raw.startsWith("http")) {
            // Try as raw access_token first, then as remid cookie.
            accessToken = runCatching {
                validateEaAccessToken(raw)
                raw
            }.getOrElse { exchangeEaRemid(raw) }
        }
        require(accessToken.isNotBlank()) { "Could not find a valid EA access token or remid cookie." }
        val pidId = validateEaAccessToken(accessToken)
        val refresh = remid ?: "token:$accessToken"
        return StoreSession(accessToken, refresh, pidId, System.currentTimeMillis() + 3600_000L)
    }

    private fun eaRefresh(refreshToken: String, owner: String): StoreSession {
        if (refreshToken.startsWith("token:")) {
            val token = refreshToken.removePrefix("token:")
            val pidId = runCatching { validateEaAccessToken(token) }.getOrNull() ?: throw StoreSessionExpired()
            check(pidId == owner) { "EA returned a different account." }
            return StoreSession(token, refreshToken, pidId, System.currentTimeMillis() + 3600_000L)
        }
        val accessToken = exchangeEaRemid(refreshToken)
        val pidId = validateEaAccessToken(accessToken)
        check(pidId == owner) { "EA returned a different account." }
        return StoreSession(accessToken, refreshToken, pidId, System.currentTimeMillis() + 3600_000L)
    }

    private fun validateEaAccessToken(accessToken: String): String {
        val uri = Http.validate("https://gateway.ea.com/proxy/identity/pids/me", hosts)
        val response = Http.exchange(uri, headers = mapOf("Authorization" to "Bearer $accessToken"))
        if (response.status == 401 || response.status == 403) throw StoreSessionExpired()
        if (!response.ok) throw IllegalStateException("EA rejected the access token (HTTP ${response.status}).")
        val pidId = JSONObject(response.text).optJSONObject("pid")?.optString("pidId").orEmpty()
        require(pidId.isNotBlank()) { "EA did not return a valid pidId." }
        return pidId
    }

    private fun exchangeEaRemid(remid: String): String {
        val url = "https://accounts.ea.com/connect/auth?client_id=$EA_CLIENT_ID&response_type=token&redirect_uri=$EA_REDIRECT_URI&prompt=none"
        val uri = Http.validate(url, hosts)
        val response = Http.exchange(uri, headers = mapOf("Cookie" to "remid=$remid"))
        val location = response.header("location").orEmpty()
        var token = ""
        if (location.contains("access_token=")) {
            val fragment = location.substringAfter("#", location)
            token = fragment.split('&').firstNotNullOfOrNull { part ->
                val kv = part.split('=', limit = 2)
                if (kv.size == 2 && kv[0] == "access_token") URLDecoder.decode(kv[1], "UTF-8") else null
            }.orEmpty()
        } else if (response.ok) {
            token = runCatching { JSONObject(response.text).optString("access_token") }.getOrDefault("")
        }
        if (token.isBlank()) throw StoreSessionExpired()
        return token
    }

    private suspend fun eaLibrary(session: StoreSession): List<StoreGame> {
        coroutineContext.ensureActive()
        val url = "https://service-aggregation-layer.juno.ea.com/graphql"
        val headers = mapOf(
            "Authorization" to "Bearer ${session.accessToken}",
            "Origin" to "https://www.ea.com",
            "Referer" to "https://www.ea.com/",
        )
        val browserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        val ownedResp = postJson(url, JSONObject().put("query", "{ me { ownedGameProducts { items { originOfferId } } } }"), headers, userAgent = browserAgent)
        if (ownedResp.status == 401 || ownedResp.status == 403) throw StoreSessionExpired()
        if (!ownedResp.ok) throw IllegalStateException("EA library request failed (HTTP ${ownedResp.status}).")
        val items = JSONObject(ownedResp.text)
            .optJSONObject("data")?.optJSONObject("me")?.optJSONObject("ownedGameProducts")?.optJSONArray("items")
            ?: JSONArray()
        val offerIds = linkedSetOf<String>()
        for (i in 0 until items.length()) {
            val oid = items.optJSONObject(i)?.optString("originOfferId").orEmpty()
            if (oid.isNotBlank() && oid != "null") offerIds.add(oid)
        }
        val titles = mutableMapOf<String, String>()
        offerIds.chunked(50).forEach { chunk ->
            coroutineContext.ensureActive()
            val idsLiteral = JSONArray(chunk).toString()
            val q = "{ gameProducts(offerIds: $idsLiteral) { items { originOfferId name } } }"
            runCatching {
                val catResp = postJson(url, JSONObject().put("query", q), headers, userAgent = browserAgent)
                if (catResp.ok) {
                    val catItems = JSONObject(catResp.text)
                        .optJSONObject("data")?.optJSONObject("gameProducts")?.optJSONArray("items")
                        ?: JSONArray()
                    for (i in 0 until catItems.length()) {
                        val ci = catItems.optJSONObject(i) ?: continue
                        val oid = ci.optString("originOfferId")
                        val name = ci.optString("name")
                        if (oid.isNotBlank() && name.isNotBlank() && name != "null") titles[oid] = name
                    }
                }
            }
        }
        return offerIds.map { oid ->
            StoreGame(oid, titles[oid] ?: oid)
        }.sortedBy { it.name.lowercase() }
    }

    // --- PlayStation Network (PSN) ---

    private fun psnSignIn(raw: String): StoreSession {
        val text = raw.trim()
        val npsso = runCatching { JSONObject(text).optString("npsso") }.getOrNull()?.takeIf { it.isNotBlank() } ?: text
        require(npsso.length in 16..512) { "Enter a valid PlayStation npsso code." }
        val authorizeUrl = "$PSN_AUTH_BASE/authorize".toUri().buildUpon()
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("client_id", PSN_CLIENT_ID)
            .appendQueryParameter("redirect_uri", PSN_REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", PSN_SCOPES)
            .build().toString()
        val authResp = Http.exchange(Http.validate(authorizeUrl, hosts), headers = mapOf("Cookie" to "npsso=$npsso"))
        val location = authResp.header("location").orEmpty()
        val code = location.toUri().getQueryParameter("code")
        require(!code.isNullOrBlank()) { "Could not exchange PlayStation npsso cookie. Sign in to PlayStation again." }
        return psnTokenExchange(
            linkedMapOf(
                "code" to code,
                "redirect_uri" to PSN_REDIRECT_URI,
                "grant_type" to "authorization_code",
                "token_format" to "jwt",
            ),
            expectedOwner = null,
        )
    }

    private fun psnRefresh(refreshToken: String, owner: String): StoreSession = psnTokenExchange(
        linkedMapOf(
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token",
            "token_format" to "jwt",
            "scope" to PSN_SCOPES,
        ),
        expectedOwner = owner,
    )

    private fun psnTokenExchange(fields: Map<String, String>, expectedOwner: String?): StoreSession {
        val body = fields.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)
        val resp = Http.exchange(
            Http.validate("$PSN_AUTH_BASE/token", hosts),
            headers = mapOf("Authorization" to "Basic $PSN_BASIC_AUTH"),
            body = body,
        )
        if (resp.status == 400 || resp.status == 401 || resp.status == 403) throw StoreSessionExpired()
        if (!resp.ok) throw IllegalStateException("PlayStation sign-in failed (HTTP ${resp.status}).")
        val json = JSONObject(resp.text)
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        check(access.isNotBlank() && refresh.isNotBlank()) { "PlayStation did not return a valid session." }
        val owner = expectedOwner ?: extractJwtAccountId(access) ?: "psn"
        val expiresIn = json.optLong("expires_in", 3600).coerceIn(60, 30 * 86400)
        return StoreSession(access, refresh, owner, System.currentTimeMillis() + expiresIn * 1000L)
    }

    private fun extractJwtAccountId(jwt: String): String? = runCatching {
        val part = jwt.split('.').getOrNull(1) ?: return null
        val decoded = String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        JSONObject(decoded).optString("account_id").takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun psnLibrary(session: StoreSession): List<StoreGame> {
        val games = linkedMapOf<String, StoreGame>()
        val seenTitles = mutableSetOf<String>()
        val headers = mapOf("Authorization" to "Bearer ${session.accessToken}")
        val limit = 200

        // 1. Recent / played PS4 & PS5 titles
        var offset = 0
        while (true) {
            coroutineContext.ensureActive()
            val url = "https://m.np.playstation.com/api/gamelist/v2/users/me/titles?limit=$limit&offset=$offset"
            val resp = Http.exchange(Http.validate(url, hosts), headers = headers)
            if (resp.status == 401 || resp.status == 403) throw StoreSessionExpired()
            if (!resp.ok) break
            val data = JSONObject(resp.text)
            val titles = data.optJSONArray("titles") ?: JSONArray()
            for (i in 0 until titles.length()) {
                val item = titles.optJSONObject(i) ?: continue
                val tid = item.optString("titleId")
                if (tid.isBlank() || tid in games) continue
                val name = item.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "PSN $tid"
                val icon = item.optString("imageUrl").ifBlank { item.optString("localizedImageUrl") }
                seenTitles.add(name.trim().lowercase())
                games[tid] = StoreGame(tid, name, imageUrl = icon)
            }
            val total = data.optInt("totalItemCount", 0)
            offset += limit
            if (offset >= total || titles.length() == 0) break
        }

        // 2. Full trophy title history (PS3, Vita, PS4, PS5)
        offset = 0
        while (true) {
            coroutineContext.ensureActive()
            val url = "https://m.np.playstation.com/api/trophy/v1/users/me/trophyTitles?limit=$limit&offset=$offset"
            val resp = Http.exchange(Http.validate(url, hosts), headers = headers)
            if (resp.status == 401 || resp.status == 403) throw StoreSessionExpired()
            if (!resp.ok) break
            val data = JSONObject(resp.text)
            val trophyTitles = data.optJSONArray("trophyTitles") ?: JSONArray()
            for (i in 0 until trophyTitles.length()) {
                val item = trophyTitles.optJSONObject(i) ?: continue
                val commId = item.optString("npCommunicationId")
                val name = item.optString("trophyTitleName").trim()
                if (commId.isBlank() || commId in games || name.isBlank() || name == "null") continue
                if (name.lowercase() in seenTitles) continue
                seenTitles.add(name.lowercase())
                val icon = item.optString("trophyTitleIconUrl")
                games[commId] = StoreGame(commId, name, imageUrl = icon)
            }
            val total = data.optInt("totalItemCount", 0)
            offset += limit
            if (offset >= total || trophyTitles.length() == 0) break
        }

        return games.values.sortedBy { it.name.lowercase() }
    }

    // --- Amazon Games (Prime Gaming) ---

    fun amazonAuthChallenge(): AmazonAuthChallenge {
        val serial = UUID.randomUUID().toString().replace("-", "").uppercase()
        val clientIdBytes = serial.toByteArray(Charsets.US_ASCII) + "#A2UMVHOX7UP4V7".toByteArray(Charsets.US_ASCII)
        val clientId = clientIdBytes.joinToString("") { "%02x".format(it) }
        val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val codeVerifier = Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val codeChallenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        val loginUrl = "https://www.amazon.com/ap/signin".toUri().buildUpon()
            .appendQueryParameter("openid.ns", "http://specs.openid.net/auth/2.0")
            .appendQueryParameter("openid.claimed_id", "http://specs.openid.net/auth/2.0/identifier_select")
            .appendQueryParameter("openid.identity", "http://specs.openid.net/auth/2.0/identifier_select")
            .appendQueryParameter("openid.mode", "checkid_setup")
            .appendQueryParameter("openid.oa2.scope", "device_auth_access")
            .appendQueryParameter("openid.ns.oa2", "http://www.amazon.com/ap/ext/oauth/2")
            .appendQueryParameter("openid.oa2.response_type", "code")
            .appendQueryParameter("openid.oa2.code_challenge_method", "S256")
            .appendQueryParameter("openid.oa2.client_id", "device:$clientId")
            .appendQueryParameter("marketPlaceId", "ATVPDKIKX0DER")
            .appendQueryParameter("openid.return_to", "https://www.amazon.com")
            .appendQueryParameter("openid.pape.max_auth_age", "0")
            .appendQueryParameter("openid.assoc_handle", "amzn_sonic_games_launcher")
            .appendQueryParameter("pageId", "amzn_sonic_games_launcher")
            .appendQueryParameter("openid.oa2.code_challenge", codeChallenge)
            .build().toString()
        return AmazonAuthChallenge(loginUrl, serial, clientId, codeVerifier)
    }

    private fun amazonSignIn(payload: String): StoreSession {
        val json = JSONObject(payload)
        val code = json.getString("code")
        val serial = json.getString("serial")
        val clientId = json.getString("clientId")
        val codeVerifier = json.getString("codeVerifier")

        val regPayload = JSONObject()
            .put("auth_data", JSONObject()
                .put("authorization_code", code)
                .put("client_domain", "DeviceLegacy")
                .put("client_id", clientId)
                .put("code_algorithm", "SHA-256")
                .put("code_verifier", codeVerifier)
                .put("use_global_authentication", false))
            .put("registration_data", JSONObject()
                .put("app_name", "AGSLauncher for Windows")
                .put("app_version", "1.0.0")
                .put("device_model", "Windows")
                .put("device_name", JSONObject.NULL)
                .put("device_serial", serial)
                .put("device_type", "A2UMVHOX7UP4V7")
                .put("domain", "Device")
                .put("os_version", "10.0.19044.0"))
            .put("requested_extensions", JSONArray(listOf("customer_info", "device_info")))
            .put("requested_token_type", JSONArray(listOf("bearer", "mac_dms")))

        val resp = postJson(
            "https://api.amazon.com/auth/register",
            regPayload,
            headers = mapOf("x-amzn-identity-auth-domain" to "api.amazon.com"),
            userAgent = "AGSLauncher/1.0.0",
        )
        if (!resp.ok) {
            val detail = amazonErrorDetail(resp.text)
            com.gamefibre.AppLogs.error("AMAZON", "Amazon /auth/register failed (HTTP ${resp.status})${if (detail.isNotBlank()) ": $detail" else ""}")
            throw IllegalStateException("Amazon Games sign-in failed (HTTP ${resp.status})${if (detail.isNotBlank()) ": $detail" else "."}")
        }
        val root = JSONObject(resp.text).optJSONObject("response")?.optJSONObject("success")
        val bearer = root?.optJSONObject("tokens")?.optJSONObject("bearer")
        val access = bearer?.optString("access_token").orEmpty()
        val refresh = bearer?.optString("refresh_token").orEmpty()
        val owner = root?.optJSONObject("extensions")?.optJSONObject("customer_info")?.optString("user_id")
            ?.takeIf { it.isNotBlank() } ?: serial
        check(access.isNotBlank() && refresh.isNotBlank()) { "Amazon did not return valid tokens." }
        return StoreSession(access, "$refresh|$serial", owner, System.currentTimeMillis() + 3000_000L)
    }

    private fun amazonRefresh(refreshAndSerial: String, owner: String): StoreSession {
        val parts = refreshAndSerial.split('|', limit = 2)
        val refresh = parts[0]
        val serial = parts.getOrElse(1) { UUID.randomUUID().toString().replace("-", "").uppercase() }
        val payload = JSONObject()
            .put("app_name", "AGSLauncher for Windows")
            .put("app_version", "1.0.0")
            .put("source_token", refresh)
            .put("requested_token_type", "access_token")
            .put("source_token_type", "refresh_token")
        val resp = postJson("https://api.amazon.com/auth/token", payload, userAgent = "AGSLauncher/1.0.0")
        if (resp.status == 400 || resp.status == 401 || resp.status == 403) throw StoreSessionExpired()
        if (!resp.ok) throw IllegalStateException("Amazon session refresh failed (HTTP ${resp.status}).")
        val access = JSONObject(resp.text).optString("access_token")
        if (access.isBlank()) throw StoreSessionExpired()
        return StoreSession(access, "$refresh|$serial", owner, System.currentTimeMillis() + 3000_000L)
    }

    private suspend fun amazonLibrary(session: StoreSession): List<StoreGame> {
        val serial = session.refreshToken.substringAfter('|', UUID.randomUUID().toString().replace("-", "").uppercase())
        val hardwareHash = MessageDigest.getInstance("SHA-256")
            .digest(serial.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02X".format(it) }
        val headers = mapOf(
            "X-Amz-Target" to "com.amazon.animusdistributionservice.entitlement.AnimusEntitlementsService.GetEntitlements",
            "x-amzn-token" to session.accessToken,
            "Content-Encoding" to "amz-1.0",
        )
        val games = linkedMapOf<String, StoreGame>()
        var nextToken: String? = null
        while (true) {
            coroutineContext.ensureActive()
            val payload = JSONObject()
                .put("Operation", "GetEntitlements")
                .put("clientId", "Sonic")
                .put("syncPoint", JSONObject.NULL)
                .put("nextToken", nextToken ?: JSONObject.NULL)
                .put("maxResults", 50)
                .put("productIdFilter", JSONObject.NULL)
                .put("keyId", "d5dc8b8b-86c8-4fc4-ae93-18c0def5314d")
                .put("hardwareHash", hardwareHash)
            val resp = postJson(
                "https://gaming.amazon.com/api/distribution/entitlements",
                payload,
                headers,
                userAgent = "com.amazon.agslauncher.win/3.0.9202.1",
            )
            if (resp.status == 401 || resp.status == 403) throw StoreSessionExpired()
            if (!resp.ok) throw IllegalStateException("Amazon Games library request failed (HTTP ${resp.status}).")
            val data = JSONObject(resp.text)
            val entitlements = data.optJSONArray("entitlements") ?: JSONArray()
            for (i in 0 until entitlements.length()) {
                val prod = entitlements.optJSONObject(i)?.optJSONObject("product") ?: continue
                val pid = prod.optString("id")
                val title = prod.optString("title")
                if (pid.isBlank() || title.isBlank() || pid in games) continue
                val detail = prod.optJSONObject("productDetail")
                val icon = detail?.optString("iconUrl").orEmpty()
                val developer = detail?.optJSONObject("details")?.optString("developer").orEmpty()
                val publisher = detail?.optJSONObject("details")?.optString("publisher").orEmpty()
                games[pid] = StoreGame(
                    pid,
                    title,
                    imageUrl = icon,
                    details = GameDetails(developer = developer, publisher = publisher),
                )
            }
            nextToken = data.optString("nextToken").takeIf { it.isNotBlank() && it != "null" }
            if (nextToken == null) break
        }
        return games.values.sortedBy { it.name.lowercase() }
    }

    private fun amazonErrorDetail(body: String): String {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return ""
        val err = root.optJSONObject("response")?.optJSONObject("error") ?: root.optJSONObject("error") ?: root
        val code = err.optString("code").takeIf { it.isNotBlank() && it != "null" }
        val message = err.optString("message").takeIf { it.isNotBlank() && it != "null" }
            ?: err.optString("error_description").takeIf { it.isNotBlank() && it != "null" }
        return listOfNotNull(code, message).joinToString(": ").take(200)
    }

    private fun postJson(
        address: String,
        body: JSONObject,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = Http.DEFAULT_AGENT,
    ) = Http.exchange(
        Http.validate(address, hosts),
        headers = headers,
        body = body.toString().toByteArray(Charsets.UTF_8),
        contentType = "application/json",
        userAgent = userAgent,
    )
}
