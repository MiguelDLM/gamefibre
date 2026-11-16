package com.gamefibre

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gamefibre.assets.CatalogArtwork
import com.gamefibre.assets.CatalogCard
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

/** Thirty logical results per page. Only decoded, verified artwork can enter the results adapter. */
@android.annotation.SuppressLint("ViewConstructor") // Constructed with the Activity-owned coroutine scope, never inflated.
internal class StoreSearchView(context: Context, private val ui: NativeUi, private val scope: CoroutineScope,
    private val open: (LibraryEntry) -> Unit, private val chooseArtwork: (LibraryEntry) -> Unit,
    private val artworkReady: (LibraryEntry) -> Unit = {},
    private val openUrl: (String) -> Unit = {},
    saved: Bundle? = null) : LinearLayout(context) {
    private enum class Source { STEAM, XBOX, PC_DEALS }
    private var source = Source.STEAM
    private var remoteEntries = emptyList<LibraryEntry>()
    private val remoteBitmaps = mutableMapOf<String, android.graphics.Bitmap>()
    private val remoteSubtitles = mutableMapOf<String, String>()
    private var catalog: SteamCatalog? = null
    private val artwork = CatalogArtwork(context)
    private var query = saved?.getString("query").orEmpty()
    private var filters = TagFilters(saved?.getStringArrayList("tags").orEmpty().toSet())
    private var sort = CatalogSort.entries.firstOrNull { it.name == saved?.getString("sort") } ?: CatalogSort.METASCORE
    private var descending = saved?.getBoolean("descending", true) ?: true
    private var page = saved?.getInt("page") ?: 0
    private var request: Job? = null
    private var images: Job? = null
    private var generation = 0
    private var result: CatalogPage? = null
    private var cards = emptyList<CatalogCard>()
    private val ready = mutableMapOf<Int, CatalogCard>()
    private val failures = mutableMapOf<Int, String>()
    private var preparationDialog: ContentDialog? = null
    private var countView: TextView? = null
    private val tagNames = resources.getStringArray(R.array.steam_catalog_tag_names).toList()
    val tagBar = TagFilterBar(context, ui, alwaysVisible = true, showCounts = false) { next ->
        filters = next; updateTagPills(); search()
    }
    private fun updateTagPills() {
        tagBar.update(tagNames.map { TagFacet(it, filters.state(it), 0, 0, 0, 0) }, filters)
    }
    val queryText get() = query
    val sortLabel get() = sort.label
    val isDescending get() = descending
    val pendingCount get() = result?.games?.count { it.appId !in ready } ?: 0
    fun setQuery(value: String) { query = value; search() }
    fun bindCount(view: TextView) { countView = view; updateCount() }
    private fun updateCount() {
        val total = if (source != Source.STEAM) remoteEntries.size else result?.total ?: 0
        countView?.apply { text = String.format(Locale.getDefault(), "%d", total); contentDescription = resources.getQuantityString(R.plurals.catalog_matching_count, total, total) }
    }
    fun applySort(value: String, reverse: Boolean) {
        sort = CatalogSort.entries.first { it.label == value }; descending = reverse; search()
    }
    private val pageLabel = ui.text("", 13, ui.secondary).apply { gravity = Gravity.CENTER }
    private val previous = ui.button("Previous") { page--; search(reset = false) }
    private val next = ui.button("Next") { page++; search(reset = false) }
    private inner class Holder(val views: GameCardViews) : RecyclerView.ViewHolder(views.card)
    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = if (source != Source.STEAM) remoteEntries.size else cards.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(createGameCardViews(parent.context, ui, 1, 1))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val views = holder.views
            if (source != Source.STEAM) {
                val game = remoteEntries[position]
                val bmp = remoteBitmaps[game.key]
                views.art.setImageBitmap(bmp)
                views.art.alpha = 1f
                views.art.scaleType = ImageView.ScaleType.CENTER_CROP
                views.title.text = game.name
                views.subtitle.text = remoteSubtitles[game.key] ?: if (source == Source.XBOX) "Xbox" else "PC Deals"
                views.coverTitle.visibility = GONE
                views.backdrop.visibility = GONE; views.backdropShade.visibility = GONE
                views.selectionMark.visibility = GONE; views.progress.visibility = GONE
                bindCardMetric(views.metric, null, null)
                views.card.contentDescription = "${game.name}, ${views.subtitle.text}"
                views.card.setOnClickListener { open(game) }
                return
            }
            val card = cards[position]
            views.art.setImageBitmap(card.bitmap)
            views.art.alpha = 1f
            views.art.scaleType = ImageView.ScaleType.CENTER_CROP
            views.title.text = card.game.name
            views.subtitle.text = LibraryStore.STEAM.label
            views.coverTitle.visibility = GONE
            views.backdrop.visibility = GONE; views.backdropShade.visibility = GONE
            views.selectionMark.visibility = GONE; views.progress.visibility = GONE
            val metric = card.game.metric(sort)
            bindCardMetric(views.metric, metric, metric?.let { "${sort.label}: $it" })
            views.card.contentDescription = listOfNotNull(card.game.name, LibraryStore.STEAM.label,
                metric?.let { "${sort.label}: $it" }).joinToString(", ")
            views.card.setOnClickListener { open(card.game.entry()) }
        }
        override fun onViewRecycled(holder: Holder) { holder.views.art.setImageDrawable(null) }
    }
    private val recycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        adapter = this@StoreSearchView.adapter
        itemAnimator = null
        contentDescription = "Steam search results"
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) publish()
            }
        })
    }
    private val empty = ui.column().apply { gravity = Gravity.CENTER; visibility = GONE }
    val pagination = ui.row().apply {
        addView(previous); addView(pageLabel, LayoutParams(0, -2, 1f).apply { gravity = Gravity.CENTER_VERTICAL }); addView(next)
    }
    private fun styleToggle(btn: LinearLayout, selected: Boolean) {
        btn.background = ui.shape(if (selected) ui.blue else ui.surface, 24)
        for (i in 0 until btn.childCount) {
            (btn.getChildAt(i) as? TextView)?.setTextColor(if (selected) ui.onPrimary else ui.blue)
        }
    }
    private val steamBtn = ui.button("Steam", primary = true) {}
    private val xboxBtn = ui.button("Xbox") {}
    private val dealsBtn = ui.button("PC Deals") {}
    init {
        orientation = VERTICAL
        updateTagPills()
        setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8))
        val sourceBar = ui.row().apply {
            setPadding(0, ui.dp(4), 0, ui.dp(8))
        }
        fun selectSource(next: Source) {
            if (source == next) return
            source = next
            styleToggle(steamBtn, source == Source.STEAM)
            styleToggle(xboxBtn, source == Source.XBOX)
            styleToggle(dealsBtn, source == Source.PC_DEALS)
            tagBar.visibility = if (source == Source.STEAM) VISIBLE else GONE
            search(reset = true)
        }
        styleToggle(steamBtn, true)
        styleToggle(xboxBtn, false)
        styleToggle(dealsBtn, false)
        steamBtn.setOnClickListener { selectSource(Source.STEAM) }
        xboxBtn.setOnClickListener { selectSource(Source.XBOX) }
        dealsBtn.setOnClickListener { selectSource(Source.PC_DEALS) }
        sourceBar.addView(steamBtn, LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
        sourceBar.addView(xboxBtn, LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
        sourceBar.addView(dealsBtn, LayoutParams(0, -2, 1f))
        addView(sourceBar, LayoutParams(-1, -2))
        val frame = android.widget.FrameLayout(context).apply {
            addView(recycler, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(empty, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        addView(frame, LayoutParams(-1, 0, 1f))
        previous.isEnabled = false; next.isEnabled = false
    }
    fun snapshot() = Bundle().apply {
        putString("query", query); putStringArrayList("tags", ArrayList(filters.included))
        putString("sort", sort.name); putBoolean("descending", descending); putInt("page", page)
    }
    fun load(value: SteamCatalog) { if (catalog !== value) { catalog = value; search(reset = false) } }
    fun close() { request?.cancel(); images?.cancel(); preparationDialog?.dismiss(); recycler.adapter = null; ready.clear(); cards = emptyList(); remoteEntries = emptyList(); remoteBitmaps.clear(); remoteSubtitles.clear() }
    fun retryArtwork() { if (result != null) prepareImages(force = true) }
    fun bitmap(game: LibraryEntry) = if (source != Source.STEAM) remoteBitmaps[game.key] else bitmap(game.id.toIntOrNull().takeIf { game.store == LibraryStore.STEAM })
    fun bitmap(appId: Int?) = appId?.let { ready[it]?.bitmap }
    private fun search(reset: Boolean = true) {
        if (source != Source.STEAM) {
            request?.cancel(); images?.cancel()
            val token = ++generation
            val text = query.trim()
            val activeSource = source
            remoteEntries = emptyList()
            remoteSubtitles.clear()
            adapter.notifyDataSetChanged()
            updateCount()
            pageLabel.text = if (activeSource == Source.XBOX) "Xbox" else "PC Deals"
            previous.isEnabled = false; next.isEnabled = false
            empty.removeAllViews()
            if (text.isBlank()) {
                empty.visibility = VISIBLE
                ui.add(empty, ui.text(
                    if (activeSource == Source.XBOX) "Type a game title to search Xbox Store"
                    else "Type a game title to search PC Deals (Steam, GOG, Epic, Humble…)",
                    16,
                ).apply { gravity = Gravity.CENTER })
                return
            }
            empty.visibility = VISIBLE
            ui.add(empty, ui.text(if (activeSource == Source.XBOX) "Searching Xbox Store…" else "Searching PC Deals…", 14, ui.secondary).apply { gravity = Gravity.CENTER })
            request = scope.launch {
                delay(200)
                val found: List<LibraryEntry> = withContext(Dispatchers.IO) {
                    if (activeSource == Source.XBOX) {
                        runCatching { StoreCatalog().search(LibraryStore.XBOX, text) }.getOrDefault(emptyList())
                    } else {
                        searchCheapSharkGames(text)
                    }
                }
                if (token != generation) return@launch
                remoteEntries = found
                updateCount()
                empty.removeAllViews()
                if (found.isEmpty()) {
                    empty.visibility = VISIBLE
                    ui.add(empty, ui.text("No games found", 18).apply { gravity = Gravity.CENTER })
                } else {
                    empty.visibility = GONE
                }
                adapter.notifyDataSetChanged()
                images = scope.launch {
                    val semaphore = Semaphore(3)
                    found.forEach { game ->
                        val url = game.artwork.ifBlank { game.details.portrait }
                        if (url.isNotBlank() && remoteBitmaps[game.key] == null) {
                            launch {
                                semaphore.withPermit {
                                    val bmp = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                            conn.connectTimeout = 6000; conn.readTimeout = 8000
                                            conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                                        }.getOrNull()
                                    }
                                    if (bmp != null && token == generation) {
                                        remoteBitmaps[game.key] = bmp
                                        val idx = remoteEntries.indexOfFirst { it.key == game.key }
                                        if (idx >= 0) adapter.notifyItemChanged(idx)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return
        }
        val index = catalog ?: return
        if (reset) page = 0
        request?.cancel(); images?.cancel()
        val token = ++generation
        val text = query; val selection = filters; val ordering = sort; val reverse = descending; val target = page
        request = scope.launch {
            delay(150)
            val found = withContext(Dispatchers.Default) { index.search(text, selection, ordering, reverse, target) }
            if (token != generation) return@launch
            result = found; page = found.page
            recycler.stopScroll(); ready.keys.retainAll(found.games.map { it.appId }.toSet()); failures.clear()
            val removed = cards.size; cards = emptyList(); adapter.notifyItemRangeRemoved(0, removed); recycler.scrollToPosition(0)
            updateCount()
            pageLabel.text = context.getString(R.string.catalog_page, page + 1, found.pageCount)
            previous.isEnabled = page > 0; next.isEnabled = page + 1 < found.pageCount
            publish(); prepareImages()
        }
    }
    private fun searchCheapSharkGames(queryText: String): List<LibraryEntry> = runCatching {
        val url = "https://www.cheapshark.com/api/1.0/games?title=${java.net.URLEncoder.encode(queryText, "UTF-8")}&limit=25"
        val resp = Http.exchange(java.net.URI(url), connectTimeout = 7_000, readTimeout = 10_000)
        if (!resp.ok) return emptyList()
        val arr = org.json.JSONArray(resp.text)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("external").takeIf { it.isNotBlank() } ?: continue
                val steamAppId = obj.optString("steamAppID").toIntOrNull()
                val gameId = obj.optString("gameID").takeIf { it.isNotBlank() } ?: continue
                val cheapest = obj.optString("cheapest").takeIf { it.isNotBlank() }
                val thumb = obj.optString("thumb").orEmpty()
                val entry = if (steamAppId != null) {
                    LibraryEntry(LibraryStore.STEAM, steamAppId.toString(), title, remote = com.gamefibre.stores.StoreGame(steamAppId.toString(), title, imageUrl = thumb))
                } else {
                    LibraryEntry(LibraryStore.GOG, "cs-$gameId", title, remote = com.gamefibre.stores.StoreGame("cs-$gameId", title, imageUrl = thumb))
                }
                remoteSubtitles[entry.key] = cheapest?.let { "PC Deals · from $$it" } ?: "PC Deals"
                add(entry)
            }
        }
    }.getOrDefault(emptyList())

    private fun publish() {
        val found = result ?: return
        if (recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        val value = found.games.mapNotNull { ready[it.appId] }
        empty.removeAllViews()
        empty.visibility = if (value.isEmpty()) VISIBLE else GONE
        if (found.total == 0) {
            ui.add(empty, ui.text("No Steam games found", 20).apply { gravity = Gravity.CENTER })
            if (query.isNotBlank()) {
                ui.gap(empty, 8)
                ui.add(empty, ui.button("Try Xbox search", true) { xboxBtn.performClick() })
                ui.gap(empty, 6)
                ui.add(empty, ui.button("Try PC Deals search") { dealsBtn.performClick() })
            }
        }
        else if (value.isEmpty()) ui.add(empty, ui.button("Artwork preparation") { showPreparation() })
        if (value == cards) return
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize() = cards.size
            override fun getNewListSize() = value.size
            override fun areItemsTheSame(a: Int, b: Int) = cards[a].game.appId == value[b].game.appId
            override fun areContentsTheSame(a: Int, b: Int) = cards[a] == value[b]
        })
        cards = value; diff.dispatchUpdatesTo(adapter)
    }
    private fun prepareImages(force: Boolean = false) {
        images?.cancel()
        val found = result ?: return
        val token = generation
        images = scope.launch {
            artwork.resolve(found.games)
            val semaphore = Semaphore(3)
            found.games.filter { force || it.appId !in ready }.map { game -> launch {
                semaphore.withPermit {
                    try {
                        val card = artwork.prepare(game)
                        if (token == generation) { ready[game.appId] = card; failures.remove(game.appId); publish(); artworkReady(game.entry()) }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { if (token == generation) { failures[game.appId] = error.message.orEmpty(); publish() } }
                }
            } }.joinAll()
        }
    }
    fun showPreparation() {
        val dialog = ContentDialog(context, ui, "Artwork preparation")
        preparationDialog = dialog
        val missing = result?.games.orEmpty().filter { it.appId !in ready }
        missing.forEach { game ->
            ui.add(dialog.content, ui.text(game.name, 16, medium = true))
            ui.add(dialog.content, ui.text(failures[game.appId] ?: "Preparing artwork…", 12, ui.secondary))
            ui.add(dialog.content, ui.button("Select artwork") { dialog.dismiss(); chooseArtwork(game.entry()) })
            ui.gap(dialog.content, 12)
        }
        dialog.setActions(ui.button("Retry artwork", true) { dialog.dismiss(); retryArtwork() }, ui.button("Close") { dialog.dismiss() })
        dialog.show()
    }
}
