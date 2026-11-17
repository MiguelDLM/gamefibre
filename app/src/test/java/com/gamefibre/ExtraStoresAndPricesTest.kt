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

    @Test fun ubisoftDemuxParsesOwnedGamesAndYamlConfig() {
        val yamlDirect = """
            version: 2.0
            root:
              name: "Assassin's Creed Revelations"
        """.trimIndent()
        val yamlLoc = """
            version: 2.0
            root:
              name: GAMENAME
            localizations:
              default:
                GAMENAME: Assassin's Creed Rogue
        """.trimIndent()

        fun pb(block: (com.google.protobuf.CodedOutputStream) -> Unit): ByteArray {
            val baos = java.io.ByteArrayOutputStream()
            val cos = com.google.protobuf.CodedOutputStream.newInstance(baos)
            block(cos)
            cos.flush()
            return baos.toByteArray()
        }

        val game1 = pb {
            it.writeUInt32(1, 40)
            it.writeUInt32(8, 0) // ProductType_Game
            it.writeString(17, yamlDirect)
            it.writeBool(20, true)
            it.writeString(30, "space-rev")
        }
        val dlc = pb {
            it.writeUInt32(1, 41)
            it.writeUInt32(8, 1) // ProductType_AddOn (must be filtered out)
            it.writeString(17, yamlDirect)
            it.writeBool(20, true)
        }
        val unowned = pb {
            it.writeUInt32(1, 42)
            it.writeUInt32(8, 0)
            it.writeString(17, yamlLoc)
            it.writeBool(20, false) // not owned (must be filtered out)
        }
        val game2 = pb {
            it.writeUInt32(1, 43)
            it.writeUInt32(8, 0)
            it.writeString(17, yamlLoc)
            it.writeBool(20, true)
        }
        val ownedGames = pb {
            it.writeByteArray(1, game1)
            it.writeByteArray(1, dlc)
            it.writeByteArray(1, unowned)
            it.writeByteArray(1, game2)
        }
        val initRsp = pb {
            it.writeBool(1, true)
            it.writeByteArray(2, ownedGames)
        }
        val rsp = pb {
            it.writeUInt32(1, 1)
            it.writeByteArray(2, initRsp)
        }
        val downstream = pb { it.writeByteArray(1, rsp) }

        val parsed = com.gamefibre.stores.UbisoftDemuxClient.parseOwnershipDownstream(downstream)
        assertEquals(2, parsed.size)
        assertEquals("Assassin's Creed Revelations", parsed[0].name)
        assertEquals("space-rev", parsed[0].spaceId)
        assertEquals("Assassin's Creed Rogue", parsed[1].name)
        assertEquals(43, parsed[1].productId)
    }
}
