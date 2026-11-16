package com.gamefibre

import android.content.Context
import androidx.core.content.edit
import java.util.Locale

internal enum class PriceRegion(val code: String, val label: String, val currency: String, val ggDealsCode: String?) {
    MX("MX", "Mexico", "MXN", null),
    BR("BR", "Brazil", "BRL", "br"),
    TR("TR", "Turkey", "TRY", null),
    AR("AR", "Argentina", "ARS", null),
    US("US", "United States", "USD", "us"),
    EU("EU", "Europe", "EUR", "eu"),
    GB("GB", "United Kingdom", "GBP", "gb"),
    CL("CL", "Chile", "CLP", null),
    CO("CO", "Colombia", "COP", null),
    ;

    val steamCountryCode: String
        get() = when (this) {
            EU -> "DE"
            else -> code
        }

    val xboxMarket: String
        get() = when (this) {
            EU -> "DE"
            else -> code
        }
}

internal enum class PriceStoreSource(val key: String, val label: String) {
    STEAM("STEAM", "Steam"),
    XBOX("XBOX", "Xbox Store"),
    GG_OFFICIAL("GG_OFFICIAL", "GG.deals Official"),
    GG_KEYSHOPS("GG_KEYSHOPS", "GG.deals Keyshops"),
    NUUVEM("NUUVEM", "Nuuvem (BR / LATAM)"),
}

internal object PricePreferences {
    private const val PREFS = "price_prefs_v1"
    private const val KEY_CURRENCY = "target_currency"
    private const val KEY_LOCAL_REGION = "local_region"
    private const val KEY_REGIONS = "favorite_regions"
    private const val KEY_REGIONS_ORDER = "favorite_regions_order"
    private const val KEY_STORES = "favorite_stores"

    val supportedCurrencies = listOf("USD", "EUR", "MXN", "BRL", "GBP", "ARS", "TRY", "CLP", "COP")
    val defaultRegions = setOf("US", "EU", "BR", "MX")
    val defaultStores = PriceStoreSource.entries.map { it.key }.toSet()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun localRegion(context: Context): PriceRegion {
        val saved = prefs(context).getString(KEY_LOCAL_REGION, null)
        if (saved != null) {
            PriceRegion.entries.firstOrNull { it.code == saved }?.let { return it }
        }
        val sysCountry = Locale.getDefault().country.uppercase(Locale.ROOT)
        return PriceRegion.entries.firstOrNull { it.code == sysCountry } ?: PriceRegion.US
    }

    fun setLocalRegion(context: Context, code: String) {
        val region = PriceRegion.entries.firstOrNull { it.code == code } ?: PriceRegion.US
        prefs(context).edit { putString(KEY_LOCAL_REGION, region.code) }
    }

    fun targetCurrency(context: Context): String {
        val saved = prefs(context).getString(KEY_CURRENCY, null)
        if (saved != null && saved in supportedCurrencies) return saved
        val localCurr = localRegion(context).currency
        return if (localCurr in supportedCurrencies) localCurr else "USD"
    }

    fun setTargetCurrency(context: Context, currency: String) {
        prefs(context).edit { putString(KEY_CURRENCY, currency.uppercase(Locale.ROOT)) }
    }

    fun regionOrder(context: Context): List<PriceRegion> {
        val raw = prefs(context).getString(KEY_REGIONS_ORDER, null).orEmpty()
        val parsed = raw.split(',').mapNotNull { code -> PriceRegion.entries.firstOrNull { it.code == code.trim() } }
        return (parsed + PriceRegion.entries).distinct()
    }

    fun setRegionOrder(context: Context, orderedCodes: List<String>) {
        val valid = orderedCodes.mapNotNull { code -> PriceRegion.entries.firstOrNull { it.code == code }?.code }.distinct()
        val full = (valid + PriceRegion.entries.map { it.code }).distinct()
        prefs(context).edit { putString(KEY_REGIONS_ORDER, full.joinToString(",")) }
    }

    fun favoriteRegions(context: Context): List<PriceRegion> {
        val local = localRegion(context)
        val saved = prefs(context).getStringSet(KEY_REGIONS, null)?.takeIf { it.isNotEmpty() } ?: defaultRegions
        val additional = regionOrder(context).filter { it.code in saved && it != local }
        return (listOf(local) + additional).distinct()
    }

    fun setFavoriteRegions(context: Context, codes: Set<String>) {
        val valid = codes.filter { code -> PriceRegion.entries.any { it.code == code } }.toSet()
        prefs(context).edit { putStringSet(KEY_REGIONS, valid.ifEmpty { defaultRegions }) }
    }

    fun favoriteStores(context: Context): Set<PriceStoreSource> {
        val saved = prefs(context).getStringSet(KEY_STORES, null)?.takeIf { it.isNotEmpty() } ?: defaultStores
        return PriceStoreSource.entries.filter { it.key in saved }.toSet()
            .ifEmpty { PriceStoreSource.entries.toSet() }
    }

    fun setFavoriteStores(context: Context, keys: Set<String>) {
        val valid = keys.filter { key -> PriceStoreSource.entries.any { it.key == key } }.toSet()
        prefs(context).edit { putStringSet(KEY_STORES, valid.ifEmpty { defaultStores }) }
    }
}
