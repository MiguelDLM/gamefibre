package com.gamefibre

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

internal data class StorePriceOffer(
    val storeName: String,
    val storeKey: String,
    val regionCode: String,
    val regionLabel: String,
    val isKeyshop: Boolean,
    val originalAmount: Double,
    val originalCurrency: String,
    val originalFormatted: String,
    val convertedAmount: Double?,
    val convertedFormatted: String?,
    val discountPercent: Int = 0,
    val historicalLowAmount: Double? = null,
    val historicalLowFormatted: String? = null,
    val historicalLowConverted: Double? = null,
    val url: String,
    val platform: String = "PC",
    val historicalLowDateMs: Long? = null,
)

internal data class RegionalPriceReport(
    val title: String,
    val steamAppId: Int?,
    val xboxProductId: String?,
    val targetCurrency: String,
    val offers: List<StorePriceOffer>,
    val regionOrder: List<String>,
    val localRegionCode: String,
    val ggDealsUrl: String?,
    val ggDealsError: String? = null,
    val fetchedAtMs: Long = System.currentTimeMillis(),
) {
    val bestOffer: StorePriceOffer? get() = offers.firstOrNull()
    val platforms: List<String> get() = offers.map { it.platform }.distinct()
    fun bestForPlatform(platform: String): StorePriceOffer? = offers.firstOrNull { it.platform == platform }
}

internal data class PriceSnapshot(
    val steamPrice: String? = null,
    val steamDiscountPercent: Int = 0,
    val ggDealsUrl: String? = null,
    val officialCurrent: String? = null,
    val officialHistorical: String? = null,
    val keyshopCurrent: String? = null,
    val keyshopHistorical: String? = null,
    val ggDealsError: String? = null,
    val fetchedAtMs: Long = System.currentTimeMillis(),
)

internal object GamePrices {
    private val hosts = setOf(
        "store.steampowered.com",
        "api.gg.deals",
        "displaycatalog.mp.microsoft.com",
        "emerald.xboxservices.com",
        "www.cheapshark.com",
    )
    private val cache = ConcurrentHashMap<String, RegionalPriceReport>()
    @Volatile private var cheapSharkStores: Map<String, String>? = null
    private const val TTL_MS = 15 * 60 * 1000L

    fun steamDbUrl(steamAppId: Int?, title: String): String =
        if (steamAppId != null) "https://steamdb.info/app/$steamAppId/"
        else "https://steamdb.info/search/?a=app&q=${URLEncoder.encode(title, "UTF-8")}"

    fun ggDealsUrl(snapshot: PriceSnapshot?, title: String): String =
        snapshot?.ggDealsUrl?.takeIf { it.startsWith("https://gg.deals/") }
            ?: "https://gg.deals/games/?title=${URLEncoder.encode(title, "UTF-8")}"

    fun ggDealsUrl(report: RegionalPriceReport?, title: String): String =
        report?.ggDealsUrl?.takeIf { it.startsWith("https://gg.deals/") }
            ?: "https://gg.deals/games/?title=${URLEncoder.encode(title, "UTF-8")}"

    fun nuuvemBrUrl(title: String): String =
        "https://www.nuuvem.com/br-pt/catalog/search/${URLEncoder.encode(title, "UTF-8")}"

    fun nuuvemLatamUrl(title: String): String =
        "https://www.nuuvem.com/mx-en/catalog/search/${URLEncoder.encode(title, "UTF-8")}"

    fun xboxStoreUrl(xboxId: String?, title: String, market: String): String =
        if (!xboxId.isNullOrBlank()) "https://www.xbox.com/${market.lowercase()}/games/store/_/$xboxId"
        else "https://www.xbox.com/search?q=${URLEncoder.encode(title, "UTF-8")}"

    fun clearCache() {
        cache.clear()
    }

    suspend fun fetchReport(
        context: Context,
        title: String,
        steamAppId: Int?,
        xboxProductId: String? = null,
    ): RegionalPriceReport = coroutineScope {
        val targetCurrency = PricePreferences.targetCurrency(context)
        val localRegion = PricePreferences.localRegion(context)
        val regions = PricePreferences.favoriteRegions(context)
        val stores = PricePreferences.favoriteStores(context)
        val apiKey = GgDealsKeyVault(context).read()

        val cacheKey = buildString {
            append(steamAppId ?: 0).append(':')
            append(xboxProductId.orEmpty()).append(':')
            append(title.lowercase()).append(':')
            append(targetCurrency).append(':')
            append(localRegion.code).append(':')
            append(regions.joinToString(",") { it.code }).append(':')
            append(stores.joinToString(",") { it.key }).append(':')
            append(apiKey != null)
        }
        cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.fetchedAtMs < TTL_MS }?.let { return@coroutineScope it }

        val ratesDeferred = async(Dispatchers.IO) { ExchangeRates.rates(context) }
        val resolvedXboxIdDeferred = async(Dispatchers.IO) {
            if (PriceStoreSource.XBOX !in stores) null
            else xboxProductId ?: resolveXboxProductId(title)
        }

        val rates = ratesDeferred.await()
        val resolvedXboxId = resolvedXboxIdDeferred.await()

        val steamJobs = if (steamAppId != null && PriceStoreSource.STEAM in stores) {
            regions.map { region ->
                async(Dispatchers.IO) { fetchSteamRegion(steamAppId, region, targetCurrency, rates) }
            }
        } else emptyList()

        val xboxJobs = if (!resolvedXboxId.isNullOrBlank() && PriceStoreSource.XBOX in stores) {
            regions.map { region ->
                async(Dispatchers.IO) { fetchXboxRegion(resolvedXboxId, title, region, targetCurrency, rates) }
            }
        } else emptyList()

        val wantOfficial = PriceStoreSource.GG_OFFICIAL in stores
        val wantKeyshops = PriceStoreSource.GG_KEYSHOPS in stores
        val ggRegions = if (!apiKey.isNullOrBlank() && steamAppId != null && (wantOfficial || wantKeyshops)) {
            regions.mapNotNull { region -> region.ggDealsCode?.let { region to it } }
                .distinctBy { it.second }
                .ifEmpty { listOf(PriceRegion.US to "us", PriceRegion.EU to "eu") }
        } else emptyList()

        val ggJobs = ggRegions.map { (region, ggCode) ->
            async(Dispatchers.IO) {
                fetchGgDealsRegion(steamAppId!!, apiKey!!, region, ggCode, wantOfficial, wantKeyshops, targetCurrency, rates)
            }
        }
        val cheapSharkJob = if (wantOfficial || PriceStoreSource.STEAM in stores) {
            async(Dispatchers.IO) { fetchCheapSharkDeals(title, steamAppId, targetCurrency, rates) }
        } else null

        val offers = mutableListOf<StorePriceOffer>()
        steamJobs.awaitAll().filterNotNull().let(offers::addAll)
        xboxJobs.awaitAll().filterNotNull().let(offers::addAll)
        cheapSharkJob?.await()?.let { csOffers ->
            csOffers.filterTo(offers) { cs ->
                // Avoid duplicating Steam US if Steam regional already fetched US
                !(cs.storeName.equals("Steam", ignoreCase = true) && offers.any { it.storeKey == PriceStoreSource.STEAM.key && it.regionCode == "US" })
            }
        }

        var ggUrl: String? = null
        var ggError: String? = null
        ggJobs.awaitAll().forEach { result ->
            if (ggUrl == null && result.url != null) ggUrl = result.url
            if (ggError == null && result.error != null) ggError = result.error
            offers.addAll(result.offers)
        }

        val sorted = offers.sortedWith(
            compareBy<StorePriceOffer> { it.convertedAmount ?: Double.MAX_VALUE }
                .thenBy { it.originalAmount }
        )

        RegionalPriceReport(
            title = title,
            steamAppId = steamAppId,
            xboxProductId = resolvedXboxId,
            targetCurrency = targetCurrency,
            offers = sorted,
            regionOrder = regions.map { it.code },
            localRegionCode = localRegion.code,
            ggDealsUrl = ggUrl,
            ggDealsError = if (offers.none { it.storeKey.startsWith("GG_") }) ggError else null,
        ).also { cache[cacheKey] = it }
    }

    private fun fetchSteamRegion(
        appId: Int,
        region: PriceRegion,
        targetCurrency: String,
        rates: Map<String, Double>,
    ): StorePriceOffer? = runCatching {
        val url = "https://store.steampowered.com/api/appdetails?appids=$appId&cc=${region.steamCountryCode.lowercase()}&filters=price_overview"
        val resp = Http.exchange(Http.validate(url, hosts), connectTimeout = 8_000, readTimeout = 12_000)
        if (!resp.ok) return null
        val overview = JSONObject(resp.text)
            .optJSONObject(appId.toString())
            ?.optJSONObject("data")
            ?.optJSONObject("price_overview") ?: return null
        val currency = overview.optString("currency").takeIf { it.isNotBlank() } ?: return null
        val finalCents = overview.optLong("final", -1L)
        if (finalCents < 0L) return null
        val amount = finalCents / 100.0
        val discount = overview.optInt("discount_percent", 0)
        val formatted = overview.optString("final_formatted").ifBlank { ExchangeRates.format(amount, currency) }
        val converted = ExchangeRates.convert(amount, currency, targetCurrency, rates)
        StorePriceOffer(
            storeName = "Steam",
            storeKey = PriceStoreSource.STEAM.key,
            regionCode = region.code,
            regionLabel = region.label,
            isKeyshop = false,
            originalAmount = amount,
            originalCurrency = currency,
            originalFormatted = formatted,
            convertedAmount = converted,
            convertedFormatted = converted?.let { ExchangeRates.format(it, targetCurrency) },
            discountPercent = discount,
            url = "https://store.steampowered.com/app/$appId/?cc=${region.steamCountryCode.lowercase()}",
        )
    }.getOrNull()

    private fun resolveXboxProductId(title: String): String? = runCatching {
        val uri = Http.validate("https://emerald.xboxservices.com/xboxcomfd/search/games?locale=en-US", hosts)
        val payload = JSONObject()
            .put("Query", title)
            .put("ChannelId", "games")
            .put("ChannelKeyToBeUsedInResponse", "games")
            .put("ReturnFilters", false)
        val correlation = Base64.getEncoder().withoutPadding().encodeToString(
            ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        ) + ".0"
        val resp = Http.exchange(
            uri,
            headers = mapOf("X-MS-API-Version" to "1.1", "MS-CV" to correlation),
            body = payload.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            connectTimeout = 8_000,
            readTimeout = 12_000,
        )
        if (!resp.ok) return null
        val products = JSONObject(resp.text)
            .optJSONObject("channels")
            ?.optJSONObject("games")
            ?.optJSONArray("products") ?: return null
        if (products.length() == 0) return null
        products.optJSONObject(0)?.optString("productId")?.takeIf { it.matches(Regex("[A-Z0-9]{12}")) }
    }.getOrNull()

    private fun fetchXboxRegion(
        xboxId: String,
        title: String,
        region: PriceRegion,
        targetCurrency: String,
        rates: Map<String, Double>,
    ): StorePriceOffer? = runCatching {
        val url = "https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=$xboxId&market=${region.xboxMarket}&languages=en-us"
        val resp = Http.exchange(Http.validate(url, hosts), connectTimeout = 8_000, readTimeout = 12_000)
        if (!resp.ok) return null
        val products = JSONObject(resp.text).optJSONArray("Products") ?: return null
        if (products.length() == 0) return null
        val skus = products.optJSONObject(0)?.optJSONArray("DisplaySkuAvailabilities") ?: return null
        for (i in 0 until skus.length()) {
            val avails = skus.optJSONObject(i)?.optJSONArray("Availabilities") ?: continue
            for (j in 0 until avails.length()) {
                val price = avails.optJSONObject(j)
                    ?.optJSONObject("OrderManagementData")
                    ?.optJSONObject("Price") ?: continue
                val currency = price.optString("CurrencyCode").takeIf { it.isNotBlank() } ?: continue
                val listPrice = price.optDouble("ListPrice", -1.0)
                val msrp = price.optDouble("MSRP", listPrice)
                if (listPrice <= 0.0) continue
                val discount = if (msrp > listPrice && msrp > 0.0) ((msrp - listPrice) * 100.0 / msrp).toInt() else 0
                val converted = ExchangeRates.convert(listPrice, currency, targetCurrency, rates)
                return StorePriceOffer(
                    storeName = "Xbox Store",
                    storeKey = PriceStoreSource.XBOX.key,
                    regionCode = region.code,
                    regionLabel = region.label,
                    isKeyshop = false,
                    originalAmount = listPrice,
                    originalCurrency = currency,
                    originalFormatted = ExchangeRates.format(listPrice, currency),
                    convertedAmount = converted,
                    convertedFormatted = converted?.let { ExchangeRates.format(it, targetCurrency) },
                    discountPercent = discount,
                    url = xboxStoreUrl(xboxId, title, region.xboxMarket),
                    platform = "Xbox",
                )
            }
        }
        null
    }.getOrNull()

    private data class GgDealsFetchResult(
        val offers: List<StorePriceOffer>,
        val url: String?,
        val error: String?,
    )

    private fun fetchGgDealsRegion(
        steamAppId: Int,
        apiKey: String,
        region: PriceRegion,
        ggCode: String,
        wantOfficial: Boolean,
        wantKeyshops: Boolean,
        targetCurrency: String,
        rates: Map<String, Double>,
    ): GgDealsFetchResult {
        return runCatching {
            val url = "https://api.gg.deals/v1/prices/by-steam-app-id/".toUri().buildUpon()
                .appendQueryParameter("ids", steamAppId.toString())
                .appendQueryParameter("region", ggCode)
                .appendQueryParameter("key", apiKey)
                .build().toString()
            val resp = Http.exchange(Http.validate(url, hosts), connectTimeout = 8_000, readTimeout = 12_000)
            if (!resp.ok) return GgDealsFetchResult(emptyList(), null, "GG.deals HTTP ${resp.status}")
            val root = JSONObject(resp.text)
            if (!root.optBoolean("success")) {
                val msg = root.optJSONObject("data")?.optString("message")?.takeIf { it.isNotBlank() } ?: "GG.deals error"
                return GgDealsFetchResult(emptyList(), null, msg)
            }
            val item = root.optJSONObject("data")?.optJSONObject(steamAppId.toString())
                ?: return GgDealsFetchResult(emptyList(), null, null)
            val gameUrl = item.optString("url").takeIf { it.startsWith("https://gg.deals/") }
                ?: "https://gg.deals/games/?title=$steamAppId"
            val prices = item.optJSONObject("prices") ?: return GgDealsFetchResult(emptyList(), gameUrl, null)
            val currency = prices.optString("currency").takeIf { it.isNotBlank() } ?: "USD"

            fun parseDouble(field: String): Double? {
                val v = prices.opt(field)
                if (v == null || v == JSONObject.NULL) return null
                return v.toString().trim().toDoubleOrNull()?.takeIf { it > 0.0 }
            }

            val list = mutableListOf<StorePriceOffer>()
            if (wantOfficial) {
                parseDouble("currentRetail")?.let { amount ->
                    val hist = parseDouble("historicalRetail")
                    val conv = ExchangeRates.convert(amount, currency, targetCurrency, rates)
                    val histConv = hist?.let { ExchangeRates.convert(it, currency, targetCurrency, rates) }
                    list.add(
                        StorePriceOffer(
                            storeName = "GG.deals Official",
                            storeKey = PriceStoreSource.GG_OFFICIAL.key,
                            regionCode = region.code,
                            regionLabel = region.label,
                            isKeyshop = false,
                            originalAmount = amount,
                            originalCurrency = currency,
                            originalFormatted = ExchangeRates.format(amount, currency),
                            convertedAmount = conv,
                            convertedFormatted = conv?.let { ExchangeRates.format(it, targetCurrency) },
                            historicalLowAmount = hist,
                            historicalLowFormatted = hist?.let { ExchangeRates.format(it, currency) },
                            historicalLowConverted = histConv,
                            url = gameUrl,
                        )
                    )
                }
            }
            if (wantKeyshops) {
                parseDouble("currentKeyshops")?.let { amount ->
                    val hist = parseDouble("historicalKeyshops")
                    val conv = ExchangeRates.convert(amount, currency, targetCurrency, rates)
                    val histConv = hist?.let { ExchangeRates.convert(it, currency, targetCurrency, rates) }
                    list.add(
                        StorePriceOffer(
                            storeName = "GG.deals Keyshops",
                            storeKey = PriceStoreSource.GG_KEYSHOPS.key,
                            regionCode = region.code,
                            regionLabel = region.label,
                            isKeyshop = true,
                            originalAmount = amount,
                            originalCurrency = currency,
                            originalFormatted = ExchangeRates.format(amount, currency),
                            convertedAmount = conv,
                            convertedFormatted = conv?.let { ExchangeRates.format(it, targetCurrency) },
                            historicalLowAmount = hist,
                            historicalLowFormatted = hist?.let { ExchangeRates.format(it, currency) },
                            historicalLowConverted = histConv,
                            url = gameUrl,
                        )
                    )
                }
            }
            GgDealsFetchResult(list, gameUrl, null)
        }.getOrElse {
            GgDealsFetchResult(emptyList(), null, it.message ?: "GG.deals unavailable")
        }
    }

    private fun cheapSharkStoreNames(): Map<String, String> {
        cheapSharkStores?.let { return it }
        return runCatching {
            val resp = Http.exchange(
                Http.validate("https://www.cheapshark.com/api/1.0/stores", hosts),
                connectTimeout = 6_000,
                readTimeout = 8_000,
            )
            if (!resp.ok) return@runCatching emptyMap()
            val arr = org.json.JSONArray(resp.text)
            buildMap {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("storeID")
                    val name = obj.optString("storeName")
                    if (id.isNotBlank() && name.isNotBlank()) put(id, name)
                }
            }.also { if (it.isNotEmpty()) cheapSharkStores = it }
        }.getOrDefault(emptyMap())
    }

    private fun fetchCheapSharkDeals(
        title: String,
        steamAppId: Int?,
        targetCurrency: String,
        rates: Map<String, Double>,
    ): List<StorePriceOffer> = runCatching {
        val storeMap = cheapSharkStoreNames()
        val uriBuilder = "https://www.cheapshark.com/api/1.0/deals".toUri().buildUpon()
            .appendQueryParameter("sortBy", "Price")
            .appendQueryParameter("pageSize", "8")
        if (steamAppId != null) {
            uriBuilder.appendQueryParameter("steamAppID", steamAppId.toString())
        } else {
            uriBuilder.appendQueryParameter("title", title)
            uriBuilder.appendQueryParameter("exact", "1")
        }
        val resp = Http.exchange(
            Http.validate(uriBuilder.build().toString(), hosts),
            connectTimeout = 7_000,
            readTimeout = 10_000,
        )
        if (!resp.ok) return emptyList()
        val arr = org.json.JSONArray(resp.text)
        val out = mutableListOf<StorePriceOffer>()
        val seenStores = mutableSetOf<String>()
        var cheapSharkGameId: String? = null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (cheapSharkGameId == null) cheapSharkGameId = obj.optString("gameID").takeIf { it.isNotBlank() }
            val storeId = obj.optString("storeID")
            val storeName = storeMap[storeId] ?: continue
            if (!seenStores.add(storeName)) continue
            val salePrice = obj.optString("salePrice").toDoubleOrNull()?.takeIf { it > 0.0 } ?: continue
            val savings = obj.optString("savings").toDoubleOrNull()?.toInt()?.coerceIn(0, 99) ?: 0
            val dealId = obj.optString("dealID")
            val dealUrl = if (dealId.isNotBlank()) "https://www.cheapshark.com/redirect?dealID=$dealId"
                else "https://www.cheapshark.com/search#q:${URLEncoder.encode(title, "UTF-8")}"
            val conv = ExchangeRates.convert(salePrice, "USD", targetCurrency, rates)
            out.add(
                StorePriceOffer(
                    storeName = storeName,
                    storeKey = "CS_$storeId",
                    regionCode = "US",
                    regionLabel = "United States",
                    isKeyshop = false,
                    originalAmount = salePrice,
                    originalCurrency = "USD",
                    originalFormatted = ExchangeRates.format(salePrice, "USD"),
                    convertedAmount = conv,
                    convertedFormatted = conv?.let { ExchangeRates.format(it, targetCurrency) },
                    discountPercent = savings,
                    url = dealUrl,
                    platform = "PC",
                )
            )
        }
        if (out.isNotEmpty() && cheapSharkGameId != null) {
            val gameResp = runCatching {
                Http.exchange(
                    Http.validate("https://www.cheapshark.com/api/1.0/games?id=$cheapSharkGameId", hosts),
                    connectTimeout = 5_000,
                    readTimeout = 7_000,
                )
            }.getOrNull()
            if (gameResp?.ok == true) {
                val cheapest = JSONObject(gameResp.text).optJSONObject("cheapestPriceEver")
                val lowUsd = cheapest?.optString("price")?.toDoubleOrNull()?.takeIf { it > 0.0 }
                val lowDateMs = cheapest?.optLong("date", 0L)?.takeIf { it > 0L }?.let { it * 1000L }
                if (lowUsd != null) {
                    val lowConv = ExchangeRates.convert(lowUsd, "USD", targetCurrency, rates)
                    val lowFmt = ExchangeRates.format(lowUsd, "USD")
                    for (idx in out.indices) {
                        out[idx] = out[idx].copy(
                            historicalLowAmount = lowUsd,
                            historicalLowFormatted = lowFmt,
                            historicalLowConverted = lowConv,
                            historicalLowDateMs = lowDateMs,
                        )
                    }
                }
            }
        }
        out
    }.getOrDefault(emptyList())
}
