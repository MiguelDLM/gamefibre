package com.gamefibre

import com.gamefibre.stores.ExtraStoresApi
import com.gamefibre.stores.GameStore
import org.junit.Assert.*
import org.junit.Test

class ExtraStoresAndPricesTest {
    @Test fun extraStoresArePresentAndDoNotClaimDownloads() {
        val extras = listOf(GameStore.UBISOFT, GameStore.EA, GameStore.PSN, GameStore.AMAZON)
        assertTrue(extras.all { !it.downloads })
        assertEquals(
            setOf("Ubisoft Connect", "EA App", "PlayStation", "Amazon Games"),
            extras.map { it.label }.toSet(),
        )
    }

    @Test fun libraryStoreMapsAllExtraStores() {
        assertEquals(GameStore.UBISOFT, LibraryStore.UBISOFT.account)
        assertEquals(GameStore.EA, LibraryStore.EA.account)
        assertEquals(GameStore.PSN, LibraryStore.PSN.account)
        assertEquals(GameStore.AMAZON, LibraryStore.AMAZON.account)
        assertNotNull(storeSearchUrl(LibraryStore.UBISOFT, "Assassin's Creed"))
        assertNotNull(storeSearchUrl(LibraryStore.EA, "Mass Effect"))
        assertNotNull(storeSearchUrl(LibraryStore.PSN, "Astro Bot"))
        assertNotNull(storeSearchUrl(LibraryStore.AMAZON, "Fallout"))
    }

    @Test fun extraStoreArtworkSurvivesStoredRecords() {
        val psn = "https://image.api.playstation.com/vulcan/img/rnd/202010/2614/NVmnBXze9ElHzU6SmykrJLIV.png"
        val game = LibraryEntry(LibraryStore.PSN, "PPSA01325_00", "Astro's Playroom",
            remote = com.gamefibre.stores.StoreGame("PPSA01325_00", "Astro's Playroom", imageUrl = psn))
        assertEquals(psn, LibraryRecords.read(org.json.JSONArray().put(LibraryRecords.json(game))).single().artwork)
        assertEquals("", WishlistItems.imageUrl("https://evil.example/image.png"))
    }

    @Test fun collectionDataRecognizesExtraStoresAsAutomaticLists() {
        val collection = CollectionData(testPreferences())
        for (name in listOf("Ubisoft Connect", "EA App", "PlayStation", "Amazon Games")) {
            assertTrue(name in collection.lists)
            assertTrue(collection.isAutomaticList(name))
        }
    }

    @Test fun priceDatabaseUrlsResolveAppIdOrSearch() {
        assertEquals("https://steamdb.info/app/620/", GamePrices.steamDbUrl(620, "Portal 2"))
        assertEquals("https://steamdb.info/search/?a=app&q=Portal+2", GamePrices.steamDbUrl(null, "Portal 2"))
        assertEquals(
            "https://gg.deals/game/portal-2/",
            GamePrices.ggDealsUrl(PriceSnapshot(ggDealsUrl = "https://gg.deals/game/portal-2/"), "Portal 2"),
        )
        assertEquals("https://gg.deals/games/?title=Portal+2", GamePrices.ggDealsUrl(null as PriceSnapshot?, "Portal 2"))
        assertEquals("https://www.nuuvem.com/br-pt/catalog/search/Portal+2", GamePrices.nuuvemBrUrl("Portal 2"))
        assertEquals("https://www.nuuvem.com/mx-en/catalog/search/Portal+2", GamePrices.nuuvemLatamUrl("Portal 2"))
    }

    @Test fun exchangeRateConversionAndFormattingWorkAcrossCurrencies() {
        val rates = mapOf("USD" to 1.0, "MXN" to 20.0, "TRY" to 34.0, "BRL" to 5.8, "EUR" to 0.92)
        val tryToMxn = ExchangeRates.convert(340.0, "TRY", "MXN", rates)!!
        assertEquals(200.0, tryToMxn, 0.01)
        val brlToMxn = ExchangeRates.convert(58.0, "BRL", "MXN", rates)!!
        assertEquals(200.0, brlToMxn, 0.01)
        assertEquals("MX$200.00 MXN", ExchangeRates.format(200.0, "MXN"))
        assertEquals("₺340.00 TRY", ExchangeRates.format(340.0, "TRY"))
        assertEquals("R$58.00 BRL", ExchangeRates.format(58.0, "BRL"))
    }
}
