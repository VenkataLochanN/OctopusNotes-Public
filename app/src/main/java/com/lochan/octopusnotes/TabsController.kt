package com.lochan.octopusnotes

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TabsController(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val dataManager: DataManager,
    private val notebookId: () -> Long,
    private val saveNow: () -> Unit,

    private val switchInPlace: (Notebook) -> Boolean = { false }
) {
    private val stripRow: View = activity.findViewById(R.id.tabStripRow)
    private val addButton: ImageButton = activity.findViewById(R.id.tabAddButton)
    private val pillsScroll: android.widget.HorizontalScrollView = activity.findViewById(R.id.tabPillsScroll)
    private val pillsRow: ViewGroup = activity.findViewById(R.id.tabPillsRow)

    private var notebooks: Map<Long, Notebook> = emptyMap()
    private var switching = false

    init {

        (stripRow.background?.mutate() as? GradientDrawable)?.setColor(
            (BAR_ALPHA shl 24) or
                (MaterialColors.getColor(stripRow, MaterialR.attr.colorSurfaceContainerHigh) and 0x00FFFFFF)
        )
    }

    fun attach() {
        addButton.setOnClickListener {
            LibraryPickerDialog(activity, scope, dataManager) { switchTo(it) }.show()
        }
    }

    fun refresh() {
        if (!TabSession.isEnabled(activity) || notebookId() < 0) {
            stripRow.visibility = View.GONE
            return
        }

        TabSession.addOrFocus(activity, notebookId())
        stripRow.visibility = View.VISIBLE
        scope.launch {
            loadNotebooks()
            buildTabPills()
        }
    }

    private fun switchTo(id: Long) {
        if (switching) return
        if (id == notebookId()) return
        switching = true
        saveNow()
        TabSession.addOrFocus(activity, id)
        scope.launch {
            val target = dataManager.getNotebook(id)
            if (target == null) {

                TabSession.remove(activity, id)
                switching = false
                refresh()
                return@launch
            }
            if (switchInPlace(target)) {

                switching = false
                refresh()
            } else {
                val intent = Intent(
                    activity,
                    if (target.documentType == DocumentType.INFINITE)
                        InfiniteCanvasActivity::class.java
                    else
                        DrawingActivity::class.java
                ).putExtra("NOTEBOOK_ID", id)
                activity.startActivity(intent)
                activity.finish()
            }
        }
    }

    private fun closeTab(id: Long) {
        val next = TabSession.remove(activity, id)
        if (id == notebookId()) {
            if (next == -1L) {

                activity.finish()
            } else {
                switchTo(next)
            }
        } else {
            notebooks = notebooks - id
            buildTabPills()
        }
    }

    private suspend fun loadNotebooks() {
        val ids = TabSession.getTabs(activity)
        val rows = ids.mapNotNull { id -> dataManager.getNotebook(id)?.let { id to it } }
        notebooks = rows.toMap()
        if (rows.size != ids.size) {
            val cur = TabSession.currentId(activity)
            TabSession.replace(activity, rows.map { it.first }, if (cur in notebooks) cur else rows.firstOrNull()?.first ?: -1L)
        }
    }

    private fun buildTabPills() {
        pillsRow.removeAllViews()
        val tabs = TabSession.getTabs(activity)
        val self = notebookId()
        var activePill: View? = null

        for (id in tabs) {
            val pill = LinearLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.bg_tab_pill)
                isActivated = id == self
                setPadding(dp(12), dp(3), dp(2), dp(3))
            }
            if (id == self) activePill = pill

            val label = TextView(activity).apply {
                text = notebooks[id]?.title?.ifBlank { "Notebook" } ?: "Notebook"
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(120)
                val attr = if (id == self) MaterialR.attr.colorOnSecondaryContainer
                else MaterialR.attr.colorOnSurface
                setTextColor(MaterialColors.getColor(this, attr))
            }

            val close = ImageButton(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                setImageResource(R.drawable.ic_close)
                contentDescription = "Close tab"

                background = TypedValue().let { tv ->
                    activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                    activity.getDrawable(tv.resourceId)
                }
                setPadding(dp(6), dp(6), dp(6), dp(6))
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant))
            }

            pill.addView(label)
            pill.addView(close)
            pill.setOnClickListener { switchTo(id) }
            close.setOnClickListener { closeTab(id) }
            pillsRow.addView(pill)
        }

        centerRowWhenItFits {
            if (activePill != null && pillsRow.width > pillsScroll.width) {
                pillsScroll.smoothScrollTo((activePill!!.left - dp(8)).coerceAtLeast(0), 0)
            }
        }
    }

    private fun centerRowWhenItFits(then: () -> Unit) {
        pillsScroll.post {
            val lp = pillsRow.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.gravity = if (pillsRow.width <= pillsScroll.width)
                android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START
            pillsRow.layoutParams = lp
            pillsScroll.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        pillsScroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        then()
                    }
                }
            )
        }
    }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    companion object {

        private const val BAR_ALPHA = 0xD9
    }
}
