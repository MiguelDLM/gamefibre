package com.gamefibre

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun styleToggleButton(ui: NativeUi, btn: LinearLayout, selected: Boolean) {
    btn.background = ui.shape(if (selected) ui.blue else ui.surface, 24)
    for (i in 0 until btn.childCount) {
        (btn.getChildAt(i) as? android.widget.TextView)?.setTextColor(if (selected) ui.onPrimary else ui.blue)
    }
}

private fun shortStoreLabel(offer: StorePriceOffer, showPlatform: Boolean = false): String {
    val shortName = when (offer.storeKey) {
        PriceStoreSource.GG_OFFICIAL.key -> "GG Official"
        PriceStoreSource.GG_KEYSHOPS.key -> "GG Keyshop"
        else -> offer.storeName
    }
    val prefix = if (showPlatform) "[${offer.platform}] " else ""
    return "$prefix$shortName · ${offer.regionCode}"
}

/** Smartphone-friendly stacked horizontal bar chart comparing regional prices and historical lows. */
internal class PriceBarChartView(
    context: Context,
    private val ui: NativeUi,
    offers: List<StorePriceOffer>,
    private val targetCurrency: String,
    private val showPlatform: Boolean = false,
    private val localRegionCode: String = "",
) : View(context) {
    private val displayed = offers.take(12)
    private val rowHeight = ui.dp(50)
    private val headerHeight = ui.dp(28)
    private val padH = ui.dp(4)
    private val barColorOfficial = ui.blue
    private val barColorKeyshop = Color.rgb(230, 145, 56)
    private val barTrackColor = Color.argb(36, 128, 134, 139)
    private val lowMarkerColor = Color.rgb(46, 160, 67)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.ink
        textSize = ui.dp(13).toFloat()
        typeface = ui.font
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.secondary
        textSize = ui.dp(11).toFloat()
        typeface = ui.font
    }
    private val pricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.ink
        textSize = ui.dp(13).toFloat()
        typeface = ui.font
        textAlign = Paint.Align.RIGHT
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val homeIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_home)?.mutate()?.apply {
        setTint(ui.blue)
    }
    private val homeIconSize = ui.dp(14)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = headerHeight + displayed.size * rowHeight + ui.dp(8)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (displayed.isEmpty()) return

        // Legend header
        val legendY = ui.dp(16).toFloat()
        var lx = padH.toFloat()
        fun drawLegend(color: Int, label: String) {
            barPaint.color = color
            canvas.drawCircle(lx + ui.dp(5), legendY - ui.dp(4), ui.dp(4).toFloat(), barPaint)
            lx += ui.dp(13)
            canvas.drawText(label, lx, legendY, subPaint)
            lx += subPaint.measureText(label) + ui.dp(14)
        }
        drawLegend(barColorOfficial, "Official")
        if (displayed.any { it.isKeyshop }) drawLegend(barColorKeyshop, "Keyshop")
        if (displayed.any { it.historicalLowConverted != null }) drawLegend(lowMarkerColor, "Hist. Low")

        val maxVal = displayed.maxOf {
            maxOf(it.convertedAmount ?: it.originalAmount, it.historicalLowConverted ?: 0.0)
        }.coerceAtLeast(1.0)

        val fullBarLeft = padH.toFloat()
        val fullBarRight = (width - padH).toFloat()
        val barMaxWidth = (fullBarRight - fullBarLeft).coerceAtLeast(ui.dp(60).toFloat())

        displayed.forEachIndexed { index, offer ->
            val top = headerHeight + index * rowHeight
            val textY = top + ui.dp(16).toFloat()

            // Line 1: Right-aligned price (+ original currency / discount) and full-width left label
            val primaryPrice = offer.convertedFormatted ?: offer.originalFormatted
            val origNote = buildString {
                if (offer.convertedFormatted != null && offer.originalCurrency != targetCurrency) {
                    append(" (").append(offer.originalFormatted).append(")")
                }
                if (offer.discountPercent > 0) append(" -").append(offer.discountPercent).append("%")
            }
            val rightText = "$primaryPrice$origNote"
            val rightWidth = pricePaint.measureText(rightText)
            canvas.drawText(rightText, fullBarRight, textY, pricePaint)

            val isLocal = offer.regionCode == localRegionCode && homeIcon != null
            val iconReserve = if (isLocal) homeIconSize + ui.dp(5) else 0
            val maxLabelWidth = (barMaxWidth - rightWidth - ui.dp(10) - iconReserve).coerceAtLeast(ui.dp(70).toFloat())
            val labelText = ellipsize(shortStoreLabel(offer, showPlatform), labelPaint, maxLabelWidth)
            canvas.drawText(labelText, fullBarLeft, textY, labelPaint)
            if (isLocal) {
                val ix = (fullBarLeft + labelPaint.measureText(labelText) + ui.dp(5)).toInt()
                val iy = (textY - homeIconSize + ui.dp(2)).toInt()
                homeIcon?.setBounds(ix, iy, ix + homeIconSize, iy + homeIconSize)
                homeIcon?.draw(canvas)
            }

            // Line 2: Full-width horizontal bar + historical low marker
            val barTop = top + ui.dp(24).toFloat()
            val barBottom = top + ui.dp(36).toFloat()
            barPaint.color = barTrackColor
            rect.set(fullBarLeft, barTop, fullBarLeft + barMaxWidth, barBottom)
            canvas.drawRoundRect(rect, ui.dp(5).toFloat(), ui.dp(5).toFloat(), barPaint)

            val value = offer.convertedAmount ?: offer.originalAmount
            val ratio = (value / maxVal).toFloat().coerceIn(0.05f, 1f)
            barPaint.color = if (offer.isKeyshop) barColorKeyshop else barColorOfficial
            rect.set(fullBarLeft, barTop, fullBarLeft + barMaxWidth * ratio, barBottom)
            canvas.drawRoundRect(rect, ui.dp(5).toFloat(), ui.dp(5).toFloat(), barPaint)

            offer.historicalLowConverted?.let { low ->
                val lowRatio = (low / maxVal).toFloat().coerceIn(0.03f, 1f)
                val mx = fullBarLeft + barMaxWidth * lowRatio
                barPaint.color = lowMarkerColor
                canvas.drawRect(mx - ui.dp(2), barTop - ui.dp(3), mx + ui.dp(2), barBottom + ui.dp(3), barPaint)
            }
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}

/** Time-based line chart: X-axis = time (Historical Low -> Now), Y-axis = price. */
internal class PriceTrendChartView(
    context: Context,
    private val ui: NativeUi,
    offers: List<StorePriceOffer>,
    private val targetCurrency: String,
) : View(context) {
    private val officialOffers = offers.filter { !it.isKeyshop && it.convertedAmount != null }
    private val keyshopOffers = offers.filter { it.isKeyshop && it.convertedAmount != null }
    private val bestOfficialNow = officialOffers.minOfOrNull { it.convertedAmount!! }
    private val bestOfficialLow = offers.filter { !it.isKeyshop }.mapNotNull { it.historicalLowConverted }.minOrNull()
    private val bestKeyshopNow = keyshopOffers.minOfOrNull { it.convertedAmount!! }
    private val bestKeyshopLow = offers.filter { it.isKeyshop }.mapNotNull { it.historicalLowConverted }.minOrNull()
    private val lowDateMs = offers.firstNotNullOfOrNull { it.historicalLowDateMs }
    private val hasData = (bestOfficialNow != null && bestOfficialLow != null) || (bestKeyshopNow != null && bestKeyshopLow != null)

    private val headerHeight = ui.dp(24)
    private val chartHeight = ui.dp(120)
    private val footerHeight = ui.dp(26)
    private val padH = ui.dp(6)
    private val lowColor = Color.rgb(46, 160, 67)
    private val officialColor = ui.blue
    private val keyshopColor = Color.rgb(230, 145, 56)
    private val gridColor = Color.argb(45, 128, 134, 139)

    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.secondary
        textSize = ui.dp(10).toFloat()
        typeface = ui.font
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (!hasData) 0 else headerHeight + chartHeight + footerHeight
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasData) return

        val legendY = ui.dp(14).toFloat()
        var lx = padH.toFloat()
        fun legend(color: Int, text: String) {
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawCircle(lx + ui.dp(4), legendY - ui.dp(4), ui.dp(4).toFloat(), paint)
            lx += ui.dp(12)
            canvas.drawText(text, lx, legendY, subPaint)
            lx += subPaint.measureText(text) + ui.dp(14)
        }
        if (bestOfficialNow != null && bestOfficialLow != null) legend(officialColor, "Official")
        if (bestKeyshopNow != null && bestKeyshopLow != null) legend(keyshopColor, "Keyshops")

        val allVals = listOfNotNull(bestOfficialNow, bestOfficialLow, bestKeyshopNow, bestKeyshopLow)
        val maxVal = allVals.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val yLabelWidth = subPaint.measureText(ExchangeRates.format(maxVal, targetCurrency)) + ui.dp(8)
        val left = padH + yLabelWidth
        val right = (width - padH - ui.dp(12)).toFloat().coerceAtLeast(left + ui.dp(40))
        val top = headerHeight + ui.dp(8).toFloat()
        val bottom = headerHeight + chartHeight - ui.dp(8).toFloat()
        val spanH = (bottom - top).coerceAtLeast(1f)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ui.dp(1).toFloat()
        paint.color = gridColor
        listOf(0.0, 0.5, 1.0).forEach { frac ->
            val y = bottom - (spanH * frac.toFloat())
            canvas.drawLine(left, y, right, y, paint)
            val label = ExchangeRates.format(maxVal * frac, targetCurrency)
            canvas.drawText(label, padH.toFloat(), y + ui.dp(3), subPaint)
        }

        fun yFor(value: Double) = bottom - (value / maxVal).toFloat().coerceIn(0f, 1f) * spanH

        fun drawSeries(low: Double?, now: Double?, color: Int) {
            if (low == null || now == null) return
            val y1 = yFor(low)
            val y2 = yFor(now)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ui.dp(3).toFloat()
            paint.color = color
            canvas.drawLine(left, y1, right, y2, paint)

            paint.style = Paint.Style.FILL
            paint.color = lowColor
            canvas.drawCircle(left, y1, ui.dp(4).toFloat(), paint)
            paint.color = color
            canvas.drawCircle(right, y2, ui.dp(4).toFloat(), paint)
        }

        drawSeries(bestOfficialLow, bestOfficialNow, officialColor)
        drawSeries(bestKeyshopLow, bestKeyshopNow, keyshopColor)

        val lowLabel = lowDateMs?.let {
            "Low (${java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))})"
        } ?: "All-time low"
        canvas.drawText(lowLabel, left, bottom + ui.dp(16), subPaint)
        val nowLabel = "Now"
        canvas.drawText(nowLabel, right - subPaint.measureText(nowLabel), bottom + ui.dp(16), subPaint)
    }
}

internal fun populatePriceSection(
    activity: Activity,
    container: LinearLayout,
    ui: NativeUi,
    scope: CoroutineScope,
    title: String,
    steamAppId: Int?,
    xboxProductId: String? = null,
    openUrl: (String) -> Unit,
) {
    ui.gap(container, 20)
    val headingRow = ui.row()
    headingRow.addView(ui.text("Price", 18, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
    val targetCurrency = PricePreferences.targetCurrency(activity)
    headingRow.addView(ui.text(targetCurrency, 12, ui.blue, medium = true))
    ui.add(container, headingRow)
    ui.gap(container, 10)

    val card = ui.column(16).apply { background = ui.shape(ui.surface) }
    val status = ui.text("Comparing regional prices & converting to $targetCurrency…", 13, ui.secondary)
    ui.add(card, status)
    ui.add(container, card)

    scope.launch {
        val report = GamePrices.fetchReport(activity, title, steamAppId, xboxProductId)
        card.removeAllViews()

        val platforms = report.platforms
        val multiPlatform = platforms.size > 1
        var activePlatform: String? = if (multiPlatform) null else platforms.firstOrNull()
        val bodySlot = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun renderCardBody() {
            bodySlot.removeAllViews()
            val filtered = if (activePlatform == null) report.offers else report.offers.filter { it.platform == activePlatform }
            if (filtered.isNotEmpty()) {
                ui.add(bodySlot, PriceBarChartView(activity, ui, filtered, report.targetCurrency, showPlatform = activePlatform == null && multiPlatform, localRegionCode = report.localRegionCode))
            } else {
                ui.add(bodySlot, ui.text("No live regional price offers found for this title.", 13, ui.secondary))
            }
        }

        if (multiPlatform) {
            val platRow = ui.row()
            val tabs = listOf<Pair<String?, String>>(null to "All") + platforms.map { it to it }
            val buttons = mutableListOf<Pair<String?, LinearLayout>>()
            tabs.forEachIndexed { idx, (plat, label) ->
                val btn = ui.button(label, primary = plat == activePlatform) {
                    activePlatform = plat
                    buttons.forEach { (p, b) -> styleToggleButton(ui, b, p == activePlatform) }
                    renderCardBody()
                }
                styleToggleButton(ui, btn, plat == activePlatform)
                buttons.add(plat to btn)
                platRow.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply { if (idx < tabs.lastIndex) marginEnd = ui.dp(6) })
            }
            ui.add(card, platRow)
            ui.gap(card, 10)
        }
        ui.add(card, bodySlot)
        renderCardBody()

        if (report.ggDealsError != null) {
            ui.gap(card, 8)
            ui.add(card, ui.text("GG.deals: ${report.ggDealsError}", 12, ui.secondary))
        } else if (GgDealsKeyVault(activity).read() == null) {
            ui.gap(card, 8)
            ui.add(card, ui.text("Tip: add a GG.deals API key in Settings for keyshops & historical lows.", 12, ui.secondary))
        }

        ui.gap(card, 12)
        ui.add(card, ui.button("Full price view", primary = true) {
            showPriceComparisonDialog(activity, ui, report, openUrl)
        })
    }
}

internal fun showPriceComparisonDialog(
    activity: Activity,
    ui: NativeUi,
    report: RegionalPriceReport,
    openUrl: (String) -> Unit,
) {
    val dialog = ContentDialog(activity, ui, "Prices · ${report.title}")

    val platforms = report.platforms
    val multiPlatform = platforms.size > 1
    var activePlatform: String? = if (multiPlatform) null else platforms.firstOrNull()
    val dynamicSlot = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    fun orderedOffers(): List<StorePriceOffer> {
        return if (activePlatform == null) report.offers else report.offers.filter { it.platform == activePlatform }
    }

    fun rebuildDialogBody() {
        dynamicSlot.removeAllViews()
        val sorted = orderedOffers()
        val showPlat = activePlatform == null && multiPlatform
        val best = sorted.firstOrNull()
        if (best != null) {
            val summaryCard = ui.column(12).apply { background = ui.shape(ui.surface, 14) }
            val channel = if (best.isKeyshop) "Keyshop" else "Official Store"
            val priceText = best.convertedFormatted ?: best.originalFormatted
            val orig = if (best.convertedFormatted != null && best.originalCurrency != report.targetCurrency) " (${best.originalFormatted})" else ""
            val localSuffix = if (best.regionCode == report.localRegionCode) " (Local)" else ""
            ui.add(summaryCard, ui.text("Best ${best.platform} deal: ${best.storeName} · $channel · ${best.regionLabel}$localSuffix", 13, ui.blue, medium = true))
            ui.gap(summaryCard, 4)
            ui.add(summaryCard, ui.text("$priceText$orig", 18, medium = true))
            val cur = best.convertedAmount
            val low = best.historicalLowConverted
            if (cur != null && low != null && low > 0.0) {
                val pct = (((cur - low) / low) * 100.0).roundToInt()
                val verdict = if (pct <= 5) {
                    "At / near all-time low (${ExchangeRates.format(low, report.targetCurrency)}) — great time to buy."
                } else {
                    "+$pct% above all-time low (${ExchangeRates.format(low, report.targetCurrency)}) — consider waiting for a seasonal sale."
                }
                ui.gap(summaryCard, 4)
                ui.add(summaryCard, ui.text(verdict, 12, ui.secondary))
            }
            ui.gap(summaryCard, 8)
            ui.add(summaryCard, ui.button("Open cheapest offer", primary = true) { openUrl(best.url) })
            ui.add(dynamicSlot, summaryCard)
            ui.gap(dynamicSlot, 14)
        }

        if (sorted.any { it.historicalLowConverted != null && it.convertedAmount != null }) {
            ui.add(dynamicSlot, ui.text("Price history (Low vs. Now)", 15, medium = true))
            ui.gap(dynamicSlot, 6)
            ui.add(dynamicSlot, PriceTrendChartView(activity, ui, sorted, report.targetCurrency))
            ui.gap(dynamicSlot, 14)
        }

        if (sorted.isNotEmpty()) {
            ui.add(dynamicSlot, PriceBarChartView(activity, ui, sorted, report.targetCurrency, showPlatform = showPlat, localRegionCode = report.localRegionCode))
        } else {
            ui.add(dynamicSlot, ui.text("No live price offers returned for your selected stores/regions.", 13, ui.secondary))
        }
    }

    if (multiPlatform) {
        val platRow = ui.row()
        val tabs = listOf<Pair<String?, String>>(null to "All") + platforms.map { it to it }
        val platBtns = mutableListOf<Pair<String?, LinearLayout>>()
        tabs.forEachIndexed { idx, (plat, label) ->
            val btn = ui.button(label, primary = plat == activePlatform) {
                activePlatform = plat
                platBtns.forEach { (p, b) -> styleToggleButton(ui, b, p == activePlatform) }
                rebuildDialogBody()
            }
            styleToggleButton(ui, btn, plat == activePlatform)
            platBtns.add(plat to btn)
            platRow.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply { if (idx < tabs.lastIndex) marginEnd = ui.dp(6) })
        }
        ui.add(dialog.content, platRow)
        ui.gap(dialog.content, 8)
    }

    ui.add(dialog.content, dynamicSlot)
    rebuildDialogBody()

    dialog.setActions(ui.button("Close", primary = true) { dialog.dismiss() })
    dialog.show()
}
