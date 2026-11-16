package com.gamefibre

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Personal GG.deals API key encrypted with Android Keystore. */
internal class GgDealsKeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("ggdeals_key_v1", Context.MODE_PRIVATE)
    private val alias = "gamefibre.ggdeals.v1"

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    @Synchronized fun save(value: String) {
        require(value.matches(Regex("[A-Za-z0-9._-]{8,128}"))) { "Enter a valid GG.deals API key." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        var saved = false
        prefs.edit(commit = true) {
            clear()
            putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            saved = true
        }
        check(saved) { "Could not save the API key." }
    }

    @Synchronized fun read(): String? {
        val data = prefs.getString("data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            clear(); null
        }
    }

    @Synchronized fun clear() {
        prefs.edit(commit = true) { clear() }
    }
}
