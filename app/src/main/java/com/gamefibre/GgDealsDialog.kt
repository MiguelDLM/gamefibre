package com.gamefibre

import android.app.Activity
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout

internal fun showGgDealsKey(
    activity: Activity,
    ui: NativeUi,
    openUrl: (String) -> Unit,
    dismissed: () -> Unit = {},
    saved: () -> Unit = {},
) {
    val vault = GgDealsKeyVault(activity)
    val dialog = ContentDialog(activity, ui, "Price comparison", secureContent = true)
    val savedKey = vault.read()
    val status = ui.text(
        if (savedKey == null) "Add your GG.deals API key to see live official store, keyshop, and historical low prices."
        else "Your saved GG.deals API key is shown below. You can edit or replace it.",
        14,
    )
    val input = ui.input("GG.deals API key", password = true).apply {
        background = null
        setText(savedKey.orEmpty())
        transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
    }
    val keyField = ui.row().apply {
        background = ui.shape(ui.background, 8, android.graphics.Color.rgb(128, 134, 139))
    }
    keyField.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
    var keyVisible = false
    keyField.addView(ui.iconButton(R.drawable.ic_eye, "Show API key") {}.apply {
        setOnClickListener {
            val start = input.selectionStart
            val end = input.selectionEnd
            keyVisible = !keyVisible
            input.transformationMethod = if (keyVisible) null else android.text.method.PasswordTransformationMethod.getInstance()
            if (start >= 0 && end >= 0) input.setSelection(start, end)
            contentDescription = if (keyVisible) "Hide API key" else "Show API key"
            (getChildAt(0) as ImageView).setImageResource(if (keyVisible) R.drawable.ic_eye_off else R.drawable.ic_eye)
        }
    })
    ui.add(dialog.content, status); ui.gap(dialog.content)
    ui.add(dialog.content, ui.button("Get a GG.deals API key") { openUrl("https://gg.deals/api/") })
    ui.gap(dialog.content); ui.add(dialog.content, keyField)
    ui.gap(dialog.content)
    ui.add(dialog.content, ui.text("Your key is encrypted on this device. Steam prices and direct links to SteamDB and GG.deals also work without a key.", 13, ui.secondary))
    dialog.setActions(
        ui.button("Save", primary = true) {
            try {
                vault.save(input.text.toString().trim())
                input.text.clear()
                dialog.dismiss()
                saved()
            } catch (error: Exception) {
                status.text = error.message ?: "Could not save the API key."
            }
        },
        ui.button("Remove key") {
            try {
                vault.clear()
                input.text.clear()
                dialog.dismiss()
                saved()
            } catch (error: Exception) {
                status.text = error.message ?: "Could not remove the API key."
            }
        },
    )
    dialog.setOnDismissListener { input.text.clear(); dismissed() }
    dialog.show()
}

internal fun showLocalRegionDialog(activity: Activity, ui: NativeUi, saved: () -> Unit = {}) {
    val dialog = ContentDialog(activity, ui, "Local region")
    ui.add(dialog.content, ui.text("Choose your home region for price comparisons.", 13, ui.secondary))
    ui.gap(dialog.content, 12)
    val current = PricePreferences.localRegion(activity)
    PriceRegion.entries.forEach { region ->
        ui.add(dialog.content, ui.button(
            "${region.label} (${region.code} · ${region.currency})",
            primary = region == current,
        ) {
            PricePreferences.setLocalRegion(activity, region.code)
            GamePrices.clearCache()
            dialog.dismiss()
            saved()
        })
        ui.gap(dialog.content, 6)
    }
    dialog.setActions(ui.button("Close") { dialog.dismiss() })
    dialog.show()
}

internal fun showFavoriteRegionsDialog(activity: Activity, ui: NativeUi, saved: () -> Unit = {}) {
    val dialog = ContentDialog(activity, ui, "Comparison regions")
    ui.add(dialog.content, ui.text("Your local region is always included. Drag ≡ to reorder and check additional regions to compare.", 13, ui.secondary))
    ui.gap(dialog.content, 10)
    val local = PricePreferences.localRegion(activity)
    val selected = PricePreferences.favoriteRegions(activity).map { it.code }.toMutableSet()
    data class RegionRow(val region: PriceRegion, var enabled: Boolean)
    val items = PricePreferences.regionOrder(activity).map { RegionRow(it, it.code in selected) }.toMutableList()

    lateinit var touchHelper: androidx.recyclerview.widget.ItemTouchHelper
    val recycler = androidx.recyclerview.widget.RecyclerView(activity).apply {
        layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
        isNestedScrollingEnabled = false
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            val row = ui.row().apply {
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2).apply {
                    bottomMargin = ui.dp(6)
                }
                background = ui.shape(ui.surface, 12)
                setPadding(ui.dp(10), ui.dp(4), ui.dp(12), ui.dp(4))
            }
            val handle = android.widget.TextView(activity).apply {
                text = "≡"
                textSize = 20f
                setTextColor(ui.secondary)
                gravity = android.view.Gravity.CENTER
                setPadding(ui.dp(6), ui.dp(8), ui.dp(12), ui.dp(8))
            }
            val box = CheckBox(activity).apply {
                typeface = ui.font
                textSize = 15f
                setTextColor(ui.ink)
                buttonTintList = android.content.res.ColorStateList.valueOf(ui.blue)
                minHeight = ui.dp(44)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            row.addView(handle)
            row.addView(box)
            return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(row) {}
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
            val row = holder.itemView as LinearLayout
            val handle = row.getChildAt(0) as android.widget.TextView
            val box = row.getChildAt(1) as CheckBox
            val item = items[position]
            box.setOnCheckedChangeListener(null)
            box.text = "${item.region.label} (${item.region.code} · ${item.region.currency})"
            box.isChecked = item.enabled || item.region == local
            box.isEnabled = item.region != local
            box.setOnCheckedChangeListener { _, checked ->
                val pos = holder.bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) items[pos].enabled = checked
            }
            handle.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder)
                }
                false
            }
        }
    }

    touchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
        androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,
        0,
    ) {
        override fun onMove(
            rv: androidx.recyclerview.widget.RecyclerView,
            source: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            target: androidx.recyclerview.widget.RecyclerView.ViewHolder,
        ): Boolean {
            val from = source.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == androidx.recyclerview.widget.RecyclerView.NO_POSITION || to == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
            java.util.Collections.swap(items, from, to)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}
    })

    recycler.adapter = adapter
    touchHelper.attachToRecyclerView(recycler)
    ui.add(dialog.content, recycler)

    dialog.setActions(
        ui.button("Save", primary = true) {
            PricePreferences.setRegionOrder(activity, items.map { it.region.code })
            PricePreferences.setFavoriteRegions(activity, items.filter { it.enabled }.map { it.region.code }.toSet())
            GamePrices.clearCache()
            dialog.dismiss()
            saved()
        },
        ui.button("Cancel") { dialog.dismiss() },
    )
    dialog.show()
}

internal fun showFavoriteStoresDialog(activity: Activity, ui: NativeUi, saved: () -> Unit = {}) {
    val dialog = ContentDialog(activity, ui, "Favorite stores")
    ui.add(dialog.content, ui.text("Select which storefronts and price sources to include.", 13, ui.secondary))
    ui.gap(dialog.content, 8)
    val selected = PricePreferences.favoriteStores(activity).map { it.key }.toMutableSet()
    PriceStoreSource.entries.forEach { source ->
        val box = CheckBox(activity).apply {
            text = source.label
            typeface = ui.font
            textSize = 15f
            setTextColor(ui.ink)
            buttonTintList = android.content.res.ColorStateList.valueOf(ui.blue)
            isChecked = source.key in selected
            minHeight = ui.dp(48)
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(source.key) else selected.remove(source.key)
            }
        }
        ui.add(dialog.content, box)
    }
    dialog.setActions(
        ui.button("Save", primary = true) {
            PricePreferences.setFavoriteStores(activity, selected)
            GamePrices.clearCache()
            dialog.dismiss()
            saved()
        },
        ui.button("Cancel") { dialog.dismiss() },
    )
    dialog.show()
}
