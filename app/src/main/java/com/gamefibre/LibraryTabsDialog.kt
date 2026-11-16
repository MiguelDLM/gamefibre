package com.gamefibre

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

@SuppressLint("ClickableViewAccessibility")
internal fun showLibraryTabsDialog(
    activity: Activity,
    ui: NativeUi,
    collection: CollectionData,
    onSaved: () -> Unit,
) {
    val dialog = ContentDialog(activity, ui, "Library tabs")
    ui.add(dialog.content, ui.text("Drag ≡ to reorder tabs, or uncheck to hide tabs you don't use.", 13, ui.secondary))
    ui.gap(dialog.content, 10)

    data class TabItem(val name: String, var visible: Boolean)

    val fixedHead = listOf("All games")
    val fixedTail = listOf(WishlistItems.LIST, CollectionData.HIDDEN_LIST)
    val items = collection.lists
        .filter { it !in fixedHead && it !in fixedTail && it != "Downloads" }
        .map { name ->
            val shown = if (name == "Game Pass" && collection.isAutomaticList(name)) {
                collection.showGamePass && name !in collection.hiddenLists
            } else {
                name !in collection.hiddenLists
            }
            TabItem(name, shown)
        }.toMutableList()

    lateinit var touchHelper: ItemTouchHelper
    val recycler = RecyclerView(activity).apply {
        layoutManager = LinearLayoutManager(activity)
        isNestedScrollingEnabled = false
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = ui.row().apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    bottomMargin = ui.dp(6)
                }
                background = ui.shape(ui.surface, 12)
                setPadding(ui.dp(10), ui.dp(4), ui.dp(12), ui.dp(4))
            }
            val handle = TextView(activity).apply {
                text = "≡"
                textSize = 20f
                setTextColor(ui.secondary)
                gravity = Gravity.CENTER
                setPadding(ui.dp(6), ui.dp(8), ui.dp(12), ui.dp(8))
            }
            val box = CheckBox(activity).apply {
                typeface = ui.font
                textSize = 15f
                setTextColor(ui.ink)
                buttonTintList = ColorStateList.valueOf(ui.blue)
                minHeight = ui.dp(44)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            row.addView(handle)
            row.addView(box)
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = holder.itemView as LinearLayout
            val handle = row.getChildAt(0) as TextView
            val box = row.getChildAt(1) as CheckBox
            val item = items[position]
            box.setOnCheckedChangeListener(null)
            box.text = item.name
            box.isChecked = item.visible
            box.setOnCheckedChangeListener { _, checked ->
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) items[pos].visible = checked
            }
            handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder)
                }
                false
            }
        }
    }

    touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        0,
    ) {
        override fun onMove(
            rv: RecyclerView,
            source: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = source.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            Collections.swap(items, from, to)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    })

    recycler.adapter = adapter
    touchHelper.attachToRecyclerView(recycler)
    ui.add(dialog.content, recycler)

    dialog.setActions(
        ui.button("Save", primary = true) {
            val allLists = collection.lists
            val newOrder = (fixedHead + items.map { it.name } + fixedTail.filter { it in allLists } +
                allLists.filter { it !in fixedHead && it !in fixedTail && items.none { item -> item.name == it } }).distinct()
            collection.reorderLists(newOrder)
            val hidden = items.filter { !it.visible }.map { it.name }.toSet()
            collection.hiddenLists = hidden
            items.firstOrNull { it.name == "Game Pass" && collection.isAutomaticList("Game Pass") }?.let { gp ->
                collection.showGamePass = gp.visible
            }
            if (collection.list in hidden) collection.list = "All games"
            dialog.dismiss()
            onSaved()
        },
        ui.button("Cancel") { dialog.dismiss() },
    )
    dialog.show()
}
