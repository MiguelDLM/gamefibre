package com.gamefibre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogsTest {
    @Test
    fun sanitizeRedactsTokensPasswordsAndUrls() {
        val raw = "Failed GET https://user:secretpass@api.example.com/auth?key=ABC123&refresh_token=xyz987 " +
            "Authorization: Bearer tok_12345.67890 " +
            "{\"access_token\":\"secret_access\",\"password\":\"mypassword\",\"npsso\":\"cookie123\"} " +
            "qr=https://s.team/q/1/999999999 jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3OCJ9.sig"

        val cleaned = AppLogs.sanitize(raw)

        assertFalse(cleaned.contains("secretpass"))
        assertFalse(cleaned.contains("ABC123"))
        assertFalse(cleaned.contains("xyz987"))
        assertFalse(cleaned.contains("tok_12345"))
        assertFalse(cleaned.contains("secret_access"))
        assertFalse(cleaned.contains("mypassword"))
        assertFalse(cleaned.contains("cookie123"))
        assertFalse(cleaned.contains("999999999"))
        assertFalse(cleaned.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(cleaned.contains("[REDACTED]"))
        assertTrue(cleaned.contains("[REDACTED_URL]"))
        assertTrue(cleaned.contains("[REDACTED_TOKEN]"))
    }

    @Test
    fun logErrorAndClearWork() {
        AppLogs.clear()
        AppLogs.log("Steam", "Opening WebSocket connection")
        AppLogs.error("Steam", "Auth failed", IllegalStateException("Bad token?token=supersecret"))

        val content = AppLogs.read()
        assertTrue(content.contains("I/Steam: Opening WebSocket connection"))
        assertTrue(content.contains("E/Steam: Auth failed [IllegalStateException: Bad token?token=[REDACTED]]"))
        assertFalse(content.contains("supersecret"))

        AppLogs.clear()
        assertEquals("", AppLogs.read())
    }
}
