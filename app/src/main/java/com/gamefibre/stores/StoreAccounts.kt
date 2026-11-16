package com.gamefibre.stores

import android.content.Context
import android.util.AtomicFile
import androidx.core.content.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal data class StoreAccountSlot(val slotId: String, val account: StoreAccount)

/** Process-owned accounts survive Activity recreation; supports multiple accounts per store. */
internal class StoreAccounts internal constructor(private val context: Context) {
    companion object {
        const val DEFAULT_SLOT = "default"
        fun get(context: Context): StoreAccounts = com.gamefibre.App.of(context).storeAccounts
    }

    private data class SlotKey(val store: GameStore, val slotId: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slotPrefs = context.getSharedPreferences("store_slots_v1", Context.MODE_PRIVATE)
    private val vaults = ConcurrentHashMap<SlotKey, StoreTokenVault>()
    private val files = ConcurrentHashMap<SlotKey, AtomicFile>()
    private val locks = ConcurrentHashMap<SlotKey, Any>()
    private val generations = ConcurrentHashMap<SlotKey, Int>()
    private val jobs = ConcurrentHashMap<SlotKey, Job>()
    private val sessions = ConcurrentHashMap<SlotKey, StoreSession>()
    private val authentication = ConcurrentHashMap<SlotKey, Mutex>()

    private fun vaultFor(store: GameStore, slotId: String): StoreTokenVault =
        vaults.getOrPut(SlotKey(store, slotId)) { StoreTokenVault(context, store, slotId) }

    private fun fileFor(store: GameStore, slotId: String): AtomicFile =
        files.getOrPut(SlotKey(store, slotId)) {
            val suffix = if (slotId == DEFAULT_SLOT) store.name.lowercase() else "${store.name.lowercase()}-$slotId"
            AtomicFile(File(context.filesDir, "store-library-$suffix.json"))
        }

    private fun lockFor(store: GameStore, slotId: String): Any =
        locks.getOrPut(SlotKey(store, slotId)) { Any() }

    private fun authMutexFor(store: GameStore, slotId: String): Mutex =
        authentication.getOrPut(SlotKey(store, slotId)) { Mutex() }

    private fun readSlotIds(store: GameStore): List<String> {
        val extra = slotPrefs.getStringSet("slots_${store.name}", emptySet()).orEmpty()
            .filter { it.matches(Regex("[a-z0-9_]{1,32}")) && it != DEFAULT_SLOT }
            .sorted()
        return listOf(DEFAULT_SLOT) + extra
    }

    private fun registerSlotId(store: GameStore, slotId: String) {
        if (slotId == DEFAULT_SLOT) return
        val current = slotPrefs.getStringSet("slots_${store.name}", emptySet()).orEmpty()
        if (slotId !in current) {
            slotPrefs.edit(commit = true) { putStringSet("slots_${store.name}", current + slotId) }
        }
    }

    private fun unregisterSlotId(store: GameStore, slotId: String) {
        if (slotId == DEFAULT_SLOT) return
        val current = slotPrefs.getStringSet("slots_${store.name}", emptySet()).orEmpty()
        if (slotId in current) {
            slotPrefs.edit(commit = true) { putStringSet("slots_${store.name}", current - slotId) }
        }
    }

    fun nextSlotId(store: GameStore): String {
        val existing = readSlotIds(store).toSet()
        if (!vaultFor(store, DEFAULT_SLOT).let { it.read() != null || it.owner() != null }) return DEFAULT_SLOT
        var idx = 2
        while ("acc_$idx" in existing) idx++
        return "acc_$idx"
    }

    private val mutableSlots = MutableStateFlow(
        GameStore.entries.associateWith { store ->
            readSlotIds(store).map { slotId ->
                val vault = vaultFor(store, slotId)
                val saved = vault.read()
                val owner = vault.owner()
                val cached = owner?.let { loadLibrary(store, slotId, it) }
                StoreAccountSlot(
                    slotId,
                    StoreAccount(owner = owner, saved = saved != null, games = cached.orEmpty(), libraryLoaded = cached != null),
                )
            }
        }
    )
    val slotsState = mutableSlots.asStateFlow()

    private val mutable = MutableStateFlow(
        GameStore.entries.associateWith { store ->
            aggregate(mutableSlots.value.getValue(store))
        }
    )
    val state = mutable.asStateFlow()

    private fun aggregate(slots: List<StoreAccountSlot>): StoreAccount {
        val mergedGames = linkedMapOf<String, StoreGame>()
        slots.forEach { slot ->
            slot.account.games.forEach { game ->
                val existing = mergedGames[game.id]
                if (existing == null || (existing.xboxAccess == XboxAccess.GAME_PASS && game.xboxAccess == XboxAccess.OWNED)) {
                    mergedGames[game.id] = game
                }
            }
        }
        return StoreAccount(
            owner = slots.firstOrNull { it.account.owner != null }?.account?.owner,
            saved = slots.any { it.account.saved },
            connected = slots.any { it.account.connected },
            loading = slots.any { it.account.loading },
            games = mergedGames.values.sortedBy { it.name.lowercase() },
            libraryLoaded = slots.any { it.account.libraryLoaded },
            error = slots.firstNotNullOfOrNull { it.account.error },
        )
    }

    private fun updateSlot(store: GameStore, slotId: String, transform: (StoreAccount) -> StoreAccount) {
        mutableSlots.update { map ->
            val current = map.getValue(store)
            val updated = if (current.any { it.slotId == slotId }) {
                current.map { if (it.slotId == slotId) it.copy(account = transform(it.account)) else it }
            } else {
                current + StoreAccountSlot(slotId, transform(StoreAccount()))
            }
            map + (store to updated)
        }
        mutable.update { map ->
            map + (store to aggregate(mutableSlots.value.getValue(store)))
        }
    }

    /** Restore saved accounts without refetching cached libraries or restarting active authentication. */
    fun restoreSessions() {
        GameStore.entries.forEach { store ->
            mutableSlots.value.getValue(store).forEach { slot ->
                if (slot.account.saved && !slot.account.connected && !slot.account.loading) {
                    refresh(store, fetchLibrary = false, slotId = slot.slotId)
                }
            }
        }
    }

    /** Rendering reads published identity without decrypting or waiting for a token rotation. */
    fun libraryOwner(store: GameStore): String? = state.value.getValue(store).owner

    fun owner(store: GameStore, slotId: String = DEFAULT_SLOT): String =
        vaultFor(store, slotId).read()?.getString("owner") ?: throw StoreSessionExpired()

    suspend fun downloadSession(store: GameStore, owner: String, force: Boolean = false): StoreSession {
        val slotId = mutableSlots.value.getValue(store)
            .firstOrNull { it.account.owner == owner }?.slotId ?: DEFAULT_SLOT
        val key = SlotKey(store, slotId)
        return authMutexFor(store, slotId).withLock {
            val revision = synchronized(lockFor(store, slotId)) { generations[key] ?: 0 }
            val stored = vaultFor(store, slotId).read() ?: throw StoreSessionExpired()
            check(stored.getString("owner") == owner) { "Sign in with the account that started this download." }
            val old = sessions[key]
            val session = try {
                if (!force && old?.owner == owner && old.expiresAt > System.currentTimeMillis() + 60_000) old
                else StoreApi.refresh(store, stored.getString("refresh_token"), owner)
            } catch (e: StoreSessionExpired) {
                synchronized(lockFor(store, slotId)) {
                    if ((generations[key] ?: 0) == revision) {
                        vaultFor(store, slotId).invalidate(); sessions.remove(key)
                        updateSlot(store, slotId) { it.copy(saved = false, connected = false, loading = false, error = e.message) }
                    }
                }
                throw e
            }
            currentCoroutineContext().ensureActive()
            synchronized(lockFor(store, slotId)) {
                check((generations[key] ?: 0) == revision) { "The account changed. Resume after signing in again." }
                vaultFor(store, slotId).save(session.refreshToken, session.owner); sessions[key] = session
                updateSlot(store, slotId) { it.copy(owner = session.owner, saved = true, connected = true) }
            }
            session
        }
    }

    fun signIn(store: GameStore, pasted: String, slotId: String = DEFAULT_SLOT) {
        val code = store.authorizationCode(pasted)
        registerSlotId(store, slotId)
        perform(store, slotId, authenticating = true) { StoreApi.signIn(store, code) }
    }

    fun signInDirect(store: GameStore, session: StoreSession, slotId: String = DEFAULT_SLOT) {
        registerSlotId(store, slotId)
        perform(store, slotId, authenticating = true) { session }
    }

    fun refresh(store: GameStore, fetchLibrary: Boolean = true, slotId: String? = null) {
        val targets = if (slotId != null) listOf(slotId) else mutableSlots.value.getValue(store).map { it.slotId }
        targets.forEach { id ->
            val slot = mutableSlots.value.getValue(store).firstOrNull { it.slotId == id }
            if (slotId != null || slot?.account?.saved == true || slot?.account?.connected == true) {
                perform(store, id, fetchLibrary = fetchLibrary) {
                    val key = SlotKey(store, id)
                    val session = sessions[key]
                    if (session != null && session.expiresAt > System.currentTimeMillis() + 60_000) session
                    else {
                        val saved = vaultFor(store, id).read() ?: throw StoreSessionExpired()
                        StoreApi.refresh(store, saved.getString("refresh_token"), saved.getString("owner"))
                    }
                }
            }
        }
    }

    fun signOut(store: GameStore, slotId: String? = null) {
        if (slotId == null) {
            StoreDownloads.get(context).forgetAccount(store)
            mutableSlots.value.getValue(store).map { it.slotId }.forEach { id ->
                clearSlot(store, id, removeSlot = id != DEFAULT_SLOT)
            }
            com.gamefibre.CollectionData.clearStore(context, store.name)
            return
        }
        if (slotId == DEFAULT_SLOT) StoreDownloads.get(context).forgetAccount(store)
        clearSlot(store, slotId, removeSlot = slotId != DEFAULT_SLOT)
        if (mutableSlots.value.getValue(store).none { it.account.saved || it.account.connected || it.account.libraryLoaded }) {
            com.gamefibre.CollectionData.clearStore(context, store.name)
        }
    }

    private fun clearSlot(store: GameStore, slotId: String, removeSlot: Boolean) {
        val key = SlotKey(store, slotId)
        synchronized(lockFor(store, slotId)) {
            generations[key] = (generations[key] ?: 0) + 1
            jobs.remove(key)?.cancel(); sessions.remove(key)
            vaultFor(store, slotId).clear(); fileFor(store, slotId).delete()
            if (removeSlot) {
                unregisterSlotId(store, slotId)
                mutableSlots.update { map ->
                    map + (store to map.getValue(store).filterNot { it.slotId == slotId })
                }
                mutable.update { map ->
                    map + (store to aggregate(mutableSlots.value.getValue(store)))
                }
            } else {
                updateSlot(store, slotId) { StoreAccount() }
            }
        }
    }

    private fun perform(
        store: GameStore,
        slotId: String,
        authenticating: Boolean = false,
        fetchLibrary: Boolean = true,
        session: () -> StoreSession,
    ) = synchronized(lockFor(store, slotId)) {
        val slotAccount = mutableSlots.value.getValue(store).firstOrNull { it.slotId == slotId }?.account ?: StoreAccount()
        if (slotAccount.loading) return@synchronized
        val key = SlotKey(store, slotId)
        val revision = (generations[key] ?: 0) + 1
        generations[key] = revision
        updateSlot(store, slotId) { it.copy(loading = true, error = null) }
        jobs[key] = scope.launch {
            try {
                var current = authMutexFor(store, slotId).withLock {
                    val result = session()
                    ensureActive()
                    synchronized(lockFor(store, slotId)) {
                        ensureActive()
                        if ((generations[key] ?: 0) != revision) return@launch
                        val previousOwner = vaultFor(store, slotId).owner()
                        val sameOwner = previousOwner == result.owner
                        if (!sameOwner && slotId == DEFAULT_SLOT) {
                            StoreDownloads.get(context).forgetAccount(store)
                        }
                        vaultFor(store, slotId).save(result.refreshToken, result.owner)
                        if (authenticating && !sameOwner) fileFor(store, slotId).delete()
                        sessions[key] = result
                        updateSlot(store, slotId) {
                            StoreAccount(
                                owner = result.owner,
                                saved = true,
                                connected = true,
                                loading = true,
                                games = if (sameOwner) slotAccount.games else emptyList(),
                                libraryLoaded = sameOwner && slotAccount.libraryLoaded,
                            )
                        }
                    }
                    result
                }
                val currentSlot = mutableSlots.value.getValue(store).firstOrNull { it.slotId == slotId }?.account
                if (!fetchLibrary && currentSlot?.libraryLoaded == true) {
                    synchronized(lockFor(store, slotId)) {
                        if ((generations[key] ?: 0) == revision) {
                            updateSlot(store, slotId) { it.copy(loading = false) }
                        }
                    }
                    return@launch
                }
                val previous = currentSlot?.games.orEmpty()
                val games = try {
                    StoreApi.library(store, current, previous, com.gamefibre.assets.AssetTransport.get(context).store)
                } catch (_: StoreSessionExpired) {
                    val refreshed = StoreApi.refresh(
                        store,
                        vaultFor(store, slotId).read()?.getString("refresh_token") ?: throw StoreSessionExpired(),
                        current.owner,
                    )
                    vaultFor(store, slotId).save(refreshed.refreshToken, refreshed.owner)
                    sessions[key] = refreshed
                    current = refreshed
                    StoreApi.library(store, current, previous, com.gamefibre.assets.AssetTransport.get(context).store)
                }
                ensureActive()
                synchronized(lockFor(store, slotId)) {
                    if ((generations[key] ?: 0) != revision) return@launch
                    saveLibrary(store, slotId, current.owner, games)
                    updateSlot(store, slotId) {
                        StoreAccount(owner = current.owner, saved = true, connected = true, games = games, libraryLoaded = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.gamefibre.AppLogs.error(store.name, "Account sync failed (slot=$slotId)", e)
                synchronized(lockFor(store, slotId)) {
                    if ((generations[key] ?: 0) != revision) return@launch
                    if (e is StoreSessionExpired) {
                        vaultFor(store, slotId).invalidate(); sessions.remove(key)
                        updateSlot(store, slotId) { it.copy(saved = false, connected = false, loading = false, error = e.message) }
                    } else {
                        val message = when (e) {
                            is IOException -> "Could not reach ${store.label}. Check your connection and try again."
                            is org.json.JSONException, is ClassCastException -> "${store.label} returned an unexpected response. Please try again later."
                            is IllegalStateException, is IllegalArgumentException -> e.message ?: "Could not connect to ${store.label}."
                            else -> "Could not connect to ${store.label}. Please try again."
                        }
                        updateSlot(store, slotId) { it.copy(loading = false, error = message) }
                    }
                }
            }
        }
    }

    fun reportCleanupFailure(store: GameStore) {
        updateSlot(store, DEFAULT_SLOT) { it.copy(error = "Some saved game files could not be removed. Open Storage to clear them.") }
    }

    private fun loadLibrary(store: GameStore, slotId: String, owner: String): List<StoreGame>? = runCatching {
        val saved = fileFor(store, slotId).openRead().use { input ->
            check(input.available() <= 16 * 1024 * 1024)
            JSONObject(input.bufferedReader().readText())
        }
        check(saved.getString("owner") == owner)
        val games = saved.getJSONArray("games")
        (0 until games.length()).map { i ->
            games.getJSONObject(i).let {
                StoreGame(
                    it.getString("id"),
                    it.getString("name"),
                    it.optString("namespace"),
                    it.optString("catalogId"),
                    it.optString("imageUrl"),
                    com.gamefibre.GameDetails.read(it.optJSONObject("details")),
                    if (store == GameStore.XBOX) XboxAccess.valueOf(it.getString("xboxAccess")) else null,
                )
            }
        }
    }.getOrNull()

    private fun saveLibrary(store: GameStore, slotId: String, owner: String, games: List<StoreGame>) {
        val array = JSONArray().apply {
            games.forEach {
                put(
                    JSONObject().put("id", it.id).put("name", it.name).put("namespace", it.namespace)
                        .put("catalogId", it.catalogId).put("imageUrl", it.imageUrl).put("details", it.details.json())
                        .apply { if (store == GameStore.XBOX) put("xboxAccess", requireNotNull(it.xboxAccess).name) }
                )
            }
        }
        val file = fileFor(store, slotId)
        val output = file.startWrite()
        try {
            output.write(JSONObject().put("owner", owner).put("games", array).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Throwable) {
            file.failWrite(output); throw e
        }
    }
}
