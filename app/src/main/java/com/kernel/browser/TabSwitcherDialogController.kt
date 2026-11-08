package com.kernel.browser

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kernel.browser.tabs.BrowserTab
import com.kernel.browser.tabs.BrowserTabs
import com.kernel.browser.tabs.TabMode
import com.kernel.browser.tabs.TabSwitcherLogic
import kotlin.math.max
import kotlin.math.sign

/**
 * Full-screen tab switcher: a Tabs / Private segmented control, a search field, a grid of
 * cards (tap to switch, swipe or x to close), an inline undo bar, and a bottom bar with
 * Close all, New tab, and Done. Closing re-renders the grid in place so the switcher never
 * flickers shut and open again.
 *
 * Layout grid: everything sits on a 4dp grid with a 16dp outer gutter that the segmented
 * control, search field, cards, and the bottom bar's text all share, and every tappable
 * element is at least 48dp. Icons are separate ImageViews centered by their parent's
 * gravity, never compound drawables, so an icon always sits next to its label.
 * Private mode uses the same violet as the private address bar.
 */
class TabSwitcherDialogController(
    private val context: Context,
    private val tabs: BrowserTabs,
    private val thumbnailProvider: (BrowserTab) -> Bitmap?,
    private val selectTab: (BrowserTab) -> Unit,
    private val createTab: (TabMode) -> Unit,
    private val closeTab: (BrowserTab) -> Unit,
    private val closeTabs: (TabMode) -> Unit,
    private val undoCloseTab: () -> Unit
) {
    private val motion = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val handler = Handler(Looper.getMainLooper())
    private val hideUndoRunnable = Runnable { hideUndoBar() }
    private var mode = tabs.activeTab?.mode ?: TabMode.NORMAL
    private var query = ""

    private lateinit var dialog: Dialog
    private lateinit var host: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var grid: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var normalSegment: Segment
    private lateinit var privateSegment: Segment
    private lateinit var searchField: EditText
    private lateinit var closeAllButton: TextView
    private lateinit var newTabButton: FrameLayout
    private lateinit var newTabIcon: ImageView
    private lateinit var undoBar: LinearLayout

    /** One half of the segmented control: icon, label, and count, centered together. */
    private class Segment(val root: LinearLayout, val icon: ImageView, val label: TextView, val count: TextView)

    private fun dp(value: Int): Int = KernelUi.dp(context, value)

    private fun color(res: Int): Int = context.getColor(res)

    private val privateMode: Boolean
        get() = mode == TabMode.PRIVATE

    /** Accent for the current mode: blue for normal tabs, violet for private ones. */
    private fun modeAccent(): Int = color(if (privateMode) R.color.kernel_private_accent else R.color.kernel_accent)

    private fun rounded(fill: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }
    }

    fun show() {
        dialog = Dialog(context, R.style.KernelFullScreenDialog)
        host = FrameLayout(context).apply {
            setBackgroundColor(color(R.color.kernel_background))
            fitsSystemWindows = true
            // Keep focus off the search field so the cursor doesn't blink on open.
            isFocusableInTouchMode = true
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(segmentedControl())
            searchField = buildSearchField()
            addView(searchField)
            scroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    // Bottom padding keeps the last row clear of the floating undo bar.
                    setPadding(dp(GUTTER_DP), dp(8), dp(GUTTER_DP), dp(80))
                    grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    emptyState = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(emptyState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                })
            }
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(context).apply { setBackgroundColor(color(R.color.kernel_divider)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(bottomBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BOTTOM_BAR_DP)))
        }
        host.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        undoBar = buildUndoBar()
        host.addView(undoBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(BOTTOM_BAR_DP + 12) })

        // Re-flow the grid when the window width changes (rotation, split screen). Height
        // changes alone (the keyboard opening for search) keep the current layout.
        host.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val oldWidth = oldRight - oldLeft
            if (oldWidth > 0 && oldWidth != right - left) host.post { render(animate = false) }
        }

        dialog.setContentView(host)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(color(R.color.kernel_background)))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = color(R.color.kernel_background)
            navigationBarColor = color(R.color.kernel_background)
            KernelTheme.applySystemBarAppearance(this)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            setWindowAnimations(android.R.style.Animation_Activity)
        }
        dialog.setOnDismissListener { handler.removeCallbacks(hideUndoRunnable) }

        render(animate = true)
        dialog.show()
        host.requestFocus()
        scrollToActiveTab()
    }

    // region rendering

    private fun isLandscape(): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun render(animate: Boolean) {
        if (!::grid.isInitialized) return
        val normalCount = tabsIn(TabMode.NORMAL).size
        val privateCount = tabsIn(TabMode.PRIVATE).size
        styleSegment(normalSegment, normalCount, selected = !privateMode, private = false)
        styleSegment(privateSegment, privateCount, selected = privateMode, private = true)
        styleModeChrome()

        val segmentTabs = tabsIn(mode)
        setEnabledLook(closeAllButton, segmentTabs.isNotEmpty())

        val visible = segmentTabs.filter { tab ->
            query.isBlank() || tab.title.contains(query, ignoreCase = true) || tab.url.contains(query, ignoreCase = true)
        }

        grid.removeAllViews()
        emptyState.removeAllViews()
        if (visible.isEmpty()) {
            buildEmptyState(searching = segmentTabs.isNotEmpty())
            emptyState.visibility = View.VISIBLE
            return
        }
        emptyState.visibility = View.GONE

        // Measure against the content box: fitsSystemWindows pads the host for a side
        // navigation bar in landscape, and the cards only get what is left.
        val density = context.resources.displayMetrics.density
        val hostWidth = host.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val widthPx = hostWidth - host.paddingLeft - host.paddingRight
        val columns = TabSwitcherLogic.columnsFor((widthPx / density).toInt())
        val gap = dp(GAP_DP)
        val cardWidth = (widthPx - dp(GUTTER_DP) * 2 - gap * (columns - 1)) / columns
        // Thumbnails are captured in the current orientation; match their shape so the
        // top-crop shows the same part of the page in portrait and landscape.
        val thumbHeight = (cardWidth * if (isLandscape()) LANDSCAPE_THUMB_RATIO else PORTRAIT_THUMB_RATIO).toInt()
        val activeId = tabs.activeTab?.id

        visible.chunked(columns).forEachIndexed { rowIndex, rowTabs ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            for (column in 0 until columns) {
                val tab = rowTabs.getOrNull(column)
                // Equal weights with a leading gap on every column but the first give
                // equal card widths and equal gutters; empty slots keep the last row aligned.
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (column > 0) marginStart = gap
                }
                row.addView(if (tab != null) tabCard(tab, tab.id == activeId, thumbHeight) else View(context), params)
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = gap
            })
            if (animate) {
                row.alpha = 0f
                row.translationY = dp(16).toFloat()
                row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((rowIndex * 40L).coerceAtMost(160L))
                    .setDuration(260L)
                    .setInterpolator(motion)
                    .start()
            }
        }
    }

    /** Colors that follow the mode: search field, new-tab button, and Done. */
    private fun styleModeChrome() {
        searchField.hint = if (privateMode) "Search private tabs" else "Search tabs"
        searchField.background = if (privateMode) {
            rounded(color(R.color.kernel_private_surface), 24, color(R.color.kernel_private_border))
        } else {
            rounded(color(R.color.kernel_group), 24, color(R.color.kernel_border))
        }
        newTabButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(modeAccent())
        }
        newTabButton.contentDescription = if (privateMode) "New private tab" else "New tab"
        newTabIcon.imageTintList = ColorStateList.valueOf(
            color(if (privateMode) R.color.kernel_on_private_accent else R.color.kernel_on_accent)
        )
        doneButton.setTextColor(modeAccent())
    }

    private fun scrollToActiveTab() {
        val activeId = tabs.activeTab?.id ?: return
        val visible = tabsIn(mode)
        val index = visible.indexOfFirst { it.id == activeId }
        if (index < 0) return
        scroll.post {
            val columns = (grid.getChildAt(0) as? LinearLayout)?.childCount ?: return@post
            val row = grid.getChildAt(index / columns) ?: return@post
            scroll.scrollTo(0, (row.top - dp(8)).coerceAtLeast(0))
        }
    }

    private fun buildEmptyState(searching: Boolean) {
        val private = privateMode
        val (title, body) = when {
            searching -> "No matching tabs" to "Nothing in ${if (private) "private" else "your"} tabs matches “$query”."
            private -> "No private tabs" to
                "Private tabs don't save history, cookies, or site data, and they all close when you exit Kernel."
            else -> "No open tabs" to "Tabs you open will show up here."
        }
        emptyState.setPadding(dp(24), dp(56), dp(24), dp(24))
        emptyState.addView(
            KernelUi.iconTile(context, when {
                searching -> R.drawable.ic_search
                private -> R.drawable.ic_private
                else -> R.drawable.ic_tabs
            }, if (private) R.color.tile_purple else R.color.tile_blue).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(16) }
        )
        emptyState.addView(TextView(context).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(color(R.color.kernel_text))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        emptyState.addView(TextView(context).apply {
            text = body
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(color(R.color.kernel_muted))
            maxWidth = dp(320)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        if (!searching) {
            emptyState.addView(
                ChromeSheet.actionButton(context, if (private) "Open private tab" else "Open new tab", primary = true) {
                    openNewTab()
                }.apply {
                    minHeight = dp(48)
                    setPadding(dp(24), 0, dp(24), 0)
                    if (private) {
                        backgroundTintList = ColorStateList.valueOf(color(R.color.kernel_private_accent))
                        setTextColor(color(R.color.kernel_on_private_accent))
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(24)
                }
            )
        }
    }

    // endregion

    // region tab cards

    /**
     * A card is a 48dp header (site mark, title, close) above a thumbnail. The close
     * button lives in the header row, so its glyph shares the title's center line
     * instead of floating over the page image.
     */
    private fun tabCard(tab: BrowserTab, active: Boolean, thumbHeight: Int): View {
        val title = TabSwitcherLogic.displayTitle(tab.title, tab.url, tab.isPrivate)
        val hostLabel = TabSwitcherLogic.displayHost(tab.url).ifEmpty { if (tab.isPrivate) "Private" else "Start page" }
        val radius = dp(CARD_RADIUS_DP).toFloat()
        val accent = color(if (tab.isPrivate) R.color.kernel_private_accent else R.color.kernel_accent)

        lateinit var card: SwipeCard
        card = SwipeCard(
            context,
            onTap = { selectFromSwitcher(tab) },
            onDismiss = { closeFromSwitcher(tab) }
        ).apply {
            background = GradientDrawable().apply {
                setColor(color(R.color.kernel_group))
                cornerRadius = radius
            }
            clipToOutline = true
            foreground = KernelUi.selectableItemBackground(context)
            contentDescription = buildString {
                append(title)
                append(", ").append(hostLabel)
                if (tab.isPrivate) append(", private")
                if (active) append(", current tab")
            }
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(12), 0, 0, 0)
        }
        header.addView(siteMark(tab, hostLabel), LinearLayout.LayoutParams(dp(MARK_DP), dp(MARK_DP)))
        header.addView(TextView(context).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextColor(if (active) accent else color(R.color.kernel_text))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(color(R.color.kernel_muted))
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 48dp target around an 18dp glyph.
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = context.getDrawable(R.drawable.circle_button_background)
            contentDescription = "Close $title"
            isClickable = true
            isFocusable = true
            setOnClickListener { card.dismissAnimated(1f) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val thumbnail = thumbnailProvider(tab)
        body.addView(FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setBackgroundColor(color(R.color.kernel_surface_elevated))
            if (thumbnail != null) {
                addView(TopCropImageView(context).apply { setImageBitmap(thumbnail) },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                addView(
                    KernelUi.iconTile(context, if (tab.isPrivate) R.drawable.ic_private else R.drawable.ic_globe,
                        if (tab.isPrivate) R.color.tile_purple else R.color.tile_blue),
                    FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
                )
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbHeight))
        card.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Outline drawn above the thumbnail so the active ring is never covered. The stroke
        // is inset by the drawable, so it follows the card's own corner radius exactly.
        card.addView(View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = radius
                if (active) setStroke(dp(2), accent) else setStroke(1, color(R.color.kernel_divider))
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        return card
    }

    /** 20dp mark at the start of a card header: the private icon, or the site's initial. */
    private fun siteMark(tab: BrowserTab, hostLabel: String): View {
        if (tab.isPrivate) {
            return ImageView(context).apply {
                setImageResource(R.drawable.ic_private)
                imageTintList = ColorStateList.valueOf(color(R.color.kernel_private_accent))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        return KernelUi.letterTile(context, hostLabel, tab.url.ifBlank { hostLabel }).apply {
            textSize = 11f
            includeFontPadding = false
            (background as? GradientDrawable)?.cornerRadius = dp(6).toFloat()
        }
    }

    private fun selectFromSwitcher(tab: BrowserTab) {
        hideKeyboard()
        dialog.dismiss()
        selectTab(tab)
    }

    private fun closeFromSwitcher(tab: BrowserTab) {
        if (!dialog.isShowing) return
        val undoable = !tab.isPrivate && tab.url.isNotBlank()
        closeTab(tab)
        if (undoable) showUndoBar() else hideUndoBar()
        render(animate = false)
    }

    // endregion

    // region chrome

    /**
     * Two equal-width segments in a 56dp track (4dp inset, 48dp segments). The selected
     * thumb's 12dp radius is the track's 16dp minus the inset, so the corners are concentric.
     */
    private fun segmentedControl(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(color(R.color.kernel_segment_track), 16)
            normalSegment = segment(R.drawable.ic_tabs, "Tabs") { switchMode(TabMode.NORMAL) }
            privateSegment = segment(R.drawable.ic_private, "Private") { switchMode(TabMode.PRIVATE) }
            addView(normalSegment.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(privateSegment.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(4)
            })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(GUTTER_DP), dp(8), dp(GUTTER_DP), dp(8))
            }
        }
    }

    /**
     * The icon, label, and count are separate views in a centered row, so they are centered
     * as one group and share a vertical center line. (A compound drawable on a full-width
     * TextView would pin the icon to the segment's edge, away from its label.)
     */
    private fun segment(icon: Int, label: String, onClick: () -> Unit): Segment {
        val iconView = ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val countView = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            minWidth = dp(24)
            setPadding(dp(6), 0, dp(6), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(8), 0, dp(8), 0)
            // Ripple masked to the selected thumb's 12dp corners.
            foreground = context.getDrawable(R.drawable.segment_ripple)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(iconView, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(labelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
            addView(countView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20)).apply {
                marginStart = dp(8)
            })
        }
        return Segment(root, iconView, labelView, countView)
    }

    private fun styleSegment(segment: Segment, count: Int, selected: Boolean, private: Boolean) {
        val accent = color(if (private) R.color.kernel_private_accent else R.color.kernel_accent)
        val onAccent = color(if (private) R.color.kernel_on_private_accent else R.color.kernel_on_accent)
        val muted = color(R.color.kernel_muted)

        segment.root.isSelected = selected
        segment.root.contentDescription = if (private) "Private tabs, $count open" else "Tabs, $count open"
        segment.root.background = when {
            selected && private -> rounded(color(R.color.kernel_private_surface), 12, color(R.color.kernel_private_border))
            selected -> rounded(color(R.color.kernel_segment_thumb), 12)
            else -> null
        }
        segment.icon.imageTintList = ColorStateList.valueOf(if (selected) accent else muted)
        segment.label.setTextColor(
            when {
                !selected -> muted
                private -> accent
                else -> color(R.color.kernel_text)
            }
        )
        segment.count.text = if (count > 99) "99+" else count.toString()
        segment.count.setTextColor(if (selected) onAccent else muted)
        segment.count.background = if (selected) {
            rounded(accent, 10)
        } else {
            rounded(Color.TRANSPARENT, 10, muted)
        }
    }

    private fun switchMode(target: TabMode) {
        if (mode == target) return
        mode = target
        hideUndoBar()
        render(animate = true)
        scroll.scrollTo(0, 0)
        scrollToActiveTab()
    }

    private fun buildSearchField(): EditText {
        return EditText(context).apply {
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(color(R.color.kernel_text))
            setHintTextColor(color(R.color.kernel_muted))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPaddingRelative(dp(16), 0, dp(16), 0)
            compoundDrawablePadding = dp(12)
            context.getDrawable(R.drawable.ic_search)?.mutate()?.let { drawable ->
                drawable.setBounds(0, 0, dp(20), dp(20))
                setCompoundDrawablesRelative(drawable, null, null, null)
            }
            compoundDrawableTintList = ColorStateList.valueOf(color(R.color.kernel_muted))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString()?.trim().orEmpty()
                    render(animate = false)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                setMargins(dp(GUTTER_DP), 0, dp(GUTTER_DP), 0)
            }
        }
    }

    private lateinit var doneButton: TextView

    /**
     * Close all (start), New tab (center), Done (end) in a FrameLayout, so the round button
     * is truly centered whatever the label widths, and both labels' text edges sit on the
     * 16dp gutter (4dp bar inset + 12dp button padding).
     */
    private fun bottomBar(): View {
        return FrameLayout(context).apply {
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(color(R.color.kernel_background))

            closeAllButton = textButton("Close all", R.color.kernel_danger) { confirmCloseAll() }
            addView(closeAllButton, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.START or Gravity.CENTER_VERTICAL
            ))

            newTabIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_add)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            newTabButton = FrameLayout(context).apply {
                isClickable = true
                isFocusable = true
                foreground = KernelUi.selectableItemBackground(context)
                // Clip the ripple to the circle instead of its square bounds.
                clipToOutline = true
                elevation = dp(4).toFloat()
                setOnClickListener { openNewTab() }
                addView(newTabIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }
            addView(newTabButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

            doneButton = textButton("Done", R.color.kernel_accent) {
                hideKeyboard()
                dialog.dismiss()
            }.apply { typeface = Typeface.DEFAULT_BOLD }
            addView(doneButton, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.END or Gravity.CENTER_VERTICAL
            ))
        }
    }

    private fun textButton(label: String, colorRes: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(64)
            setTextColor(color(colorRes))
            setPadding(dp(12), 0, dp(12), 0)
            background = context.getDrawable(R.drawable.toolbar_button_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun setEnabledLook(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    private fun confirmCloseAll() {
        val count = tabsIn(mode).size
        if (count == 0) return
        val private = privateMode
        val noun = if (private) "private tab" else "tab"
        val title = if (count == 1) "Close 1 $noun?" else "Close all $count ${noun}s?"
        val message = if (private) {
            "Their history, cookies, and site data are cleared. This can't be undone."
        } else {
            "Your saved session is cleared too. This can't be undone."
        }
        val targetMode = mode
        ChromeSheet.show(context, title = title, subtitle = message, scrollable = false) { sheet ->
            addView(ChromeSheet.buttonRow(
                context,
                ChromeSheet.actionButton(context, "Cancel") { sheet.dismiss() },
                ChromeSheet.actionButton(context, "Close all", danger = true) {
                    sheet.dismiss()
                    if (!dialog.isShowing) return@actionButton
                    closeTabs(targetMode)
                    hideUndoBar()
                    render(animate = true)
                }
            ))
        }
    }

    private fun openNewTab() {
        hideKeyboard()
        dialog.dismiss()
        createTab(mode)
    }

    /** Matches KernelSnackbar: 48dp tall, 16dp corners, inverse surface. */
    private fun buildUndoBar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            elevation = dp(8).toFloat()
            minimumHeight = dp(48)
            setPaddingRelative(dp(16), 0, dp(4), 0)
            background = rounded(color(R.color.kernel_snackbar), 16)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(TextView(context).apply {
                text = "Tab closed"
                textSize = 14f
                setTextColor(color(R.color.kernel_snackbar_text))
            })
            addView(TextView(context).apply {
                text = "Undo"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minWidth = dp(64)
                setTextColor(color(R.color.kernel_snackbar_action))
                setPadding(dp(12), 0, dp(12), 0)
                background = context.getDrawable(R.drawable.toolbar_button_background)
                contentDescription = "Undo close tab"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    hideUndoBar()
                    undoCloseTab()
                    mode = TabMode.NORMAL
                    render(animate = false)
                    scrollToActiveTab()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(8) })
        }
    }

    private fun showUndoBar() {
        handler.removeCallbacks(hideUndoRunnable)
        if (undoBar.visibility != View.VISIBLE) {
            undoBar.visibility = View.VISIBLE
            undoBar.alpha = 0f
            undoBar.translationY = dp(16).toFloat()
        }
        undoBar.animate().cancel()
        undoBar.animate().alpha(1f).translationY(0f).setDuration(180L).setInterpolator(motion).start()
        handler.postDelayed(hideUndoRunnable, UNDO_TIMEOUT_MS)
    }

    private fun hideUndoBar() {
        handler.removeCallbacks(hideUndoRunnable)
        if (!::undoBar.isInitialized || undoBar.visibility != View.VISIBLE) return
        undoBar.animate().cancel()
        undoBar.animate().alpha(0f).translationY(dp(16).toFloat()).setDuration(160L).setInterpolator(motion)
            .withEndAction { undoBar.visibility = View.GONE }
            .start()
    }

    private fun hideKeyboard() {
        if (!::searchField.isInitialized) return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    // endregion

    private fun tabsIn(target: TabMode): List<BrowserTab> = tabs.all().filter { it.mode == target }

    /**
     * A card that follows a horizontal drag and closes past [TabSwitcherLogic.DISMISS_FRACTION]
     * of its width (or on a fling). Vertical drags fall through to the ScrollView,
     * taps still click, and children such as the close button keep their own clicks.
     */
    @SuppressLint("ViewConstructor")
    private inner class SwipeCard(
        context: Context,
        private val onTap: () -> Unit,
        private val onDismiss: () -> Unit
    ) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val flingThreshold = TabSwitcherLogic.FLING_DISMISS_DP_PER_SECOND * resources.displayMetrics.density
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var dismissing = false
        private var velocity: VelocityTracker? = null

        init {
            isClickable = true
            isFocusable = true
            setOnClickListener { if (!dismissing) onTap() }
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
            }
            velocity?.let { tracker ->
                // Raw coordinates: local ones shift as the card translates.
                val copy = MotionEvent.obtain(event)
                copy.setLocation(event.rawX, event.rawY)
                tracker.addMovement(copy)
                copy.recycle()
            }
            return super.dispatchTouchEvent(event)
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (dismissing) return true
            if (event.actionMasked == MotionEvent.ACTION_MOVE && !dragging &&
                TabSwitcherLogic.isHorizontalDrag(event.rawX - downX, event.rawY - downY, touchSlop)
            ) {
                startDrag(event)
                return true
            }
            return dragging
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (dismissing) return true
            val dx = event.rawX - downX
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && TabSwitcherLogic.isHorizontalDrag(dx, event.rawY - downY, touchSlop)) {
                        startDrag(event)
                    }
                    if (dragging) {
                        translationX = dx
                        alpha = TabSwitcherLogic.dragAlpha(dx, width)
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    dragging = false
                    val vx = velocity?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                    if (TabSwitcherLogic.shouldDismiss(dx, width, vx, flingThreshold)) {
                        dismissAnimated(if (dx != 0f) sign(dx) else sign(vx))
                    } else {
                        settle()
                    }
                    recycleVelocity()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) settle()
                    dragging = false
                    recycleVelocity()
                }
            }
            return super.onTouchEvent(event)
        }

        private fun startDrag(event: MotionEvent) {
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            // Drop the pressed state and pending click from the initial touch.
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.onTouchEvent(cancel)
            cancel.recycle()
        }

        private fun settle() {
            animate().cancel()
            animate().translationX(0f).alpha(1f).setDuration(200L).setInterpolator(motion).start()
        }

        fun dismissAnimated(direction: Float) {
            if (dismissing) return
            dismissing = true
            val distance = max(width, dp(120)) * 1.2f * (if (direction == 0f) 1f else direction)
            animate().cancel()
            animate().translationX(distance).alpha(0f).setDuration(180L).setInterpolator(motion)
                .withEndAction { onDismiss() }
                .start()
        }

        private fun recycleVelocity() {
            velocity?.recycle()
            velocity = null
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            recycleVelocity()
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.isDismissable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id) {
                dismissAnimated(1f)
                return true
            }
            return super.performAccessibilityAction(action, arguments)
        }
    }

    /** Scales a page thumbnail to fill the card, anchored to the top of the page. */
    private class TopCropImageView(context: Context) : ImageView(context) {
        init {
            scaleType = ScaleType.MATRIX
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
            val changed = super.setFrame(l, t, r, b)
            val drawable = drawable ?: return changed
            val viewWidth = (r - l - paddingLeft - paddingRight).toFloat()
            val viewHeight = (b - t - paddingTop - paddingBottom).toFloat()
            val drawableWidth = drawable.intrinsicWidth.toFloat()
            val drawableHeight = drawable.intrinsicHeight.toFloat()
            if (viewWidth <= 0f || drawableWidth <= 0f || drawableHeight <= 0f) return changed
            val scale = max(viewWidth / drawableWidth, viewHeight / drawableHeight)
            imageMatrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((viewWidth - drawableWidth * scale) / 2f, 0f)
            }
            return changed
        }
    }

    private companion object {
        const val GUTTER_DP = 16
        const val GAP_DP = 12
        const val CARD_RADIUS_DP = 16
        const val MARK_DP = 20
        const val BOTTOM_BAR_DP = 72
        const val PORTRAIT_THUMB_RATIO = 1.1f
        const val LANDSCAPE_THUMB_RATIO = 0.62f
        const val UNDO_TIMEOUT_MS = 4000L
    }
}
