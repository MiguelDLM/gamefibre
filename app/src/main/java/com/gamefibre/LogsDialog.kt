package com.gamefibre

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.widget.Toast

internal fun showLogsDialog(activity: Activity, ui: NativeUi) {
    val dialog = ContentDialog(activity, ui, "Logs")
    val emptyText = "No logs recorded yet."
    val currentLogs = AppLogs.read().ifBlank { emptyText }

    val info = ui.text(
        "Sensitive credentials, tokens, and keys are redacted. Long-press to select text or tap Copy.",
        12,
        ui.secondary
    ).apply {
        setPadding(0, 0, 0, ui.dp(10))
    }

    val logView = ui.text(currentLogs, 11, ui.ink).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        background = ui.shape(ui.surface, 12)
    }

    dialog.content.addView(info)
    dialog.content.addView(logView)

    dialog.setActions(
        ui.button("Copy", primary = false) {
            val text = AppLogs.read()
            if (text.isBlank()) {
                Toast.makeText(activity, "No logs to copy", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("GameFibre Logs", text))
                Toast.makeText(activity, "Logs copied", Toast.LENGTH_SHORT).show()
            }
        },
        ui.button("Clear", primary = false) {
            AppLogs.clear()
            logView.text = emptyText
        },
        ui.button("Close", primary = true) {
            dialog.dismiss()
        }
    )
    dialog.show()
}
