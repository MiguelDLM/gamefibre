package com.gamefibre

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.util.Locale

internal object ExchangeRates {
    private val hosts = setOf("open.er-api.com")
    private const val PREFS = "exchange_rates_v1"
    private const val TTL_MS = 12 * 60 * 60 * 1000L

    @Volatile private var cachedRates: Map<String, Double>? = null
    @Volatile private var cachedAtMs: Long = 0L

    fun rates(context: Context): Map<String, Double> {
        val now = System.currentTimeMillis()
        cachedRates?.takeIf { now - cachedAtMs < TTL_MS }?.let { return it }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong("updated_at", 0L)
        val savedJson = prefs.getString("rates_json", null)
        if (savedJson != null && now - savedAt < TTL_MS) {
            parseRates(savedJson)?.let {
                cachedRates = it
                cachedAtMs = savedAt
                return it
            }
        }

        val fetched = runCatching {
            val uri = Http.validate("https://open.er-api.com/v6/latest/USD", hosts)
            val resp = Http.exchange(uri, connectTimeout = 8_000, readTimeout = 10_000)
            if (resp.ok) {
                val json = JSONObject(resp.text)
                if (json.optString("result") == "success") {
                    val ratesObj = json.optJSONObject("rates")
                    if (ratesObj != null) {
                        val raw = ratesObj.toString()
                        parseRates(raw)?.also {
                            cachedRates = it
                            cachedAtMs = now
                            prefs.edit {
                                putString("rates_json", raw)
                                putLong("updated_at", now)
                            }
                        }
                    } else null
                } else null
            } else null
        }.getOrNull()

        if (fetched != null) return fetched
        if (savedJson != null) {
            parseRates(savedJson)?.let { return it }
        }
        return emptyMap()
    }

    fun convert(amount: Double, fromCurrency: String, toCurrency: String, rates: Map<String, Double>): Double? {
        val from = fromCurrency.uppercase(Locale.ROOT)
        val to = toCurrency.uppercase(Locale.ROOT)
        if (from == to) return amount
        val fromRate = if (from == "USD") 1.0 else rates[from] ?: return null
        val toRate = if (to == "USD") 1.0 else rates[to] ?: return null
        if (fromRate <= 0.0 || toRate <= 0.0) return null
        return (amount / fromRate) * toRate
    }

    fun format(amount: Double, currency: String): String {
        val code = currency.uppercase(Locale.ROOT)
        val symbol = when (code) {
            "MXN" -> "MX$"
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            "BRL" -> "R$"
            "TRY" -> "₺"
            "ARS" -> "ARS$"
            "CLP" -> "CLP$"
            "COP" -> "COP$"
            else -> "$code "
        }
        return if (code in setOf("CLP", "COP")) {
            String.format(Locale.US, "%s%,.0f %s", symbol, amount, code)
        } else {
            String.format(Locale.US, "%s%.2f %s", symbol, amount, code)
        }
    }

    private fun parseRates(raw: String): Map<String, Double>? = runCatching {
        val obj = JSONObject(raw)
        buildMap {
            obj.keys().forEach { key ->
                val v = obj.optDouble(key, Double.NaN)
                if (!v.isNaN() && v > 0.0) put(key.uppercase(Locale.ROOT), v)
            }
            put("USD", 1.0)
        }
    }.getOrNull()
}
