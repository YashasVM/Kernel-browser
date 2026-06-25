package com.kernel.browser

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kernel.browser.tabs.BrowserTab
import com.kernel.browser.tabs.BrowserTabs
import com.kernel.browser.tabs.TabMode

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
    private val motionInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var mode = tabs.activeTab?.mode ?: TabMode.NORMAL
    private var query = ""
    private lateinit var grid: LinearLayout
    private lateinit var emptyState: TextView
    private lateinit var normalToggle: TextView
    private lateinit var privateToggle: TextView
    private var currentDialog: Dialog? = null

    fun show() {
        val dialog = Dialog(context)
        currentDialog = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val activity = context as? Activity
        val previousStatusBarColor = activity?.window?.statusBarColor
        val previousNavigationBarColor = activity?.window?.navigationBarColor
        val previousSystemUiVisibility = activity?.window?.decorView?.systemUiVisibility
        val statusOverlay = activity?.let {
            View(context).apply {
                setBackgroundColor(context.getColor(R.color.kernel_background))
                elevation = ChromeSheet.dp(context, 32).toFloat()
            }
        }
        val navigationOverlay = activity?.let {
            View(context).apply {
                setBackgroundColor(context.getColor(R.color.kernel_background))
                elevation = ChromeSheet.dp(context, 32).toFloat()
            }
        }
        activity?.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = context.getColor(R.color.kernel_background)
            navigationBarColor = context.getColor(R.color.kernel_background)
            decorView.systemUiVisibility = 0
        }
        statusOverlay?.let { overlay ->
            (activity.window.decorView as? ViewGroup)?.addView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    statusBarHeight(),
                    Gravity.TOP
                )
            )
        }
        navigationOverlay?.let { overlay ->
            (activity.window.decorView as? ViewGroup)?.addView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    navigationBarHeight(),
                    Gravity.BOTTOM
                )
            )
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.kernel_background))
            setPadding(0, statusBarHeight(), 0, navigationBarHeight())
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ChromeSheet.dp(context, 18), ChromeSheet.dp(context, 10), ChromeSheet.dp(context, 18), ChromeSheet.dp(context, 88))
        }

        content.addView(topControls(dialog))
        content.addView(searchField())

        grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
        }
        emptyState = TextView(context).apply {
            text = "No tabs found"
            gravity = Gravity.CENTER
            textSize = 17f
            setTextColor(context.getColor(R.color.kernel_muted))
            setPadding(0, ChromeSheet.dp(context, 42), 0, ChromeSheet.dp(context, 42))
        }

        content.addView(ScrollView(context).apply {
            clipToPadding = false
            isFillViewport = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(grid)
                addView(emptyState)
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        root.addView(content)
        root.addView(addButton(dialog), FrameLayout.LayoutParams(
            ChromeSheet.dp(context, 64),
            ChromeSheet.dp(context, 64),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = ChromeSheet.dp(context, 20)
        })

        renderTabs(dialog)

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            currentDialog = null
            statusOverlay?.let { overlay ->
                (overlay.parent as? ViewGroup)?.removeView(overlay)
            }
            navigationOverlay?.let { overlay ->
                (overlay.parent as? ViewGroup)?.removeView(overlay)
            }
            activity?.window?.apply {
                previousStatusBarColor?.let { statusBarColor = it }
                previousNavigationBarColor?.let { navigationBarColor = it }
                previousSystemUiVisibility?.let { decorView.systemUiVisibility = it }
            }
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                setDimAmount(0f)
                setGravity(Gravity.CENTER)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                statusBarColor = context.getColor(R.color.kernel_background)
                navigationBarColor = context.getColor(R.color.kernel_background)
                decorView.systemUiVisibility = 0
            }
            content.alpha = 0f
            content.translationY = ChromeSheet.dp(context, 22).toFloat()
            content.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280L)
                .setInterpolator(motionInterpolator)
                .start()
        }
        dialog.show()
    }

    private fun topControls(dialog: Dialog): LinearLayout {
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, ChromeSheet.dp(context, 16))

            addView(iconButton(R.drawable.ic_close, "Close tabs", onClick = { dialog.dismiss() }))

            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                background = rounded(context.getColor(R.color.kernel_surface_elevated), ChromeSheet.dp(context, 16).toFloat())
                setPadding(ChromeSheet.dp(context, 6), ChromeSheet.dp(context, 6), ChromeSheet.dp(context, 6), ChromeSheet.dp(context, 6))

                normalToggle = toggleButton("${normalTabs().size}", mode == TabMode.NORMAL) {
                    mode = TabMode.NORMAL
                    renderTabs(dialog)
                }
                privateToggle = toggleButton("Private", mode == TabMode.PRIVATE) {
                    mode = TabMode.PRIVATE
                    renderTabs(dialog)
                }
                addView(normalToggle)
                addView(privateToggle)
            }, LinearLayout.LayoutParams(0, ChromeSheet.dp(context, 58), 1f).apply {
                marginStart = ChromeSheet.dp(context, 12)
                marginEnd = ChromeSheet.dp(context, 12)
            })

            addView(iconButton(R.drawable.ic_settings, "Tab options", onClick = { showTabOptions(dialog) }))
        }
    }

    private fun showTabOptions(dialog: Dialog) {
        ChromeSheet.show(
            context = context,
            title = "Tab options",
            subtitle = if (mode == TabMode.PRIVATE) "Private tabs" else "Normal tabs"
        ) { optionsDialog ->
            addView(ChromeSheet.row(
                context = context,
                title = if (mode == TabMode.PRIVATE) "Close private tabs" else "Close normal tabs",
                subtitle = "Close every tab in the current group.",
                onClick = {
                    optionsDialog.dismiss()
                    dialog.dismiss()
                    closeTabs(mode)
                }
            ))
            addView(ChromeSheet.row(
                context = context,
                title = "Undo close tab",
                subtitle = "Restore the last closed normal tab.",
                onClick = {
                    optionsDialog.dismiss()
                    dialog.dismiss()
                    undoCloseTab()
                }
            ))
            addView(ChromeSheet.row(
                context = context,
                title = if (mode == TabMode.NORMAL) "Show private tabs" else "Show normal tabs",
                subtitle = "Switch the tab group view.",
                onClick = {
                    optionsDialog.dismiss()
                    mode = if (mode == TabMode.NORMAL) TabMode.PRIVATE else TabMode.NORMAL
                    renderTabs(dialog)
                }
            ))
        }
    }

    private fun searchField(): EditText {
        return EditText(context).apply {
            hint = "Search your tabs"
            textSize = 18f
            setSingleLine(true)
            setTextColor(context.getColor(R.color.kernel_text))
            setHintTextColor(context.getColor(R.color.kernel_muted))
            background = rounded(context.getColor(R.color.kernel_surface_alt), ChromeSheet.dp(context, 12).toFloat())
            setPadding(ChromeSheet.dp(context, 18), 0, ChromeSheet.dp(context, 18), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    renderTabs(currentDialog)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ChromeSheet.dp(context, 54)
            ).apply {
                bottomMargin = ChromeSheet.dp(context, 18)
            }
        }
    }

    private fun renderTabs(dialog: Dialog?) {
        if (!::grid.isInitialized) return
        normalToggle.text = normalTabs().size.toString()
        privateToggle.text = privateTabs().size.toString()
        styleToggle(normalToggle, mode == TabMode.NORMAL)
        styleToggle(privateToggle, mode == TabMode.PRIVATE)

        grid.removeAllViews()
        val visibleTabs = tabs.all()
            .filter { it.mode == mode }
            .filter { tab ->
                query.isBlank() ||
                    tab.title.contains(query, ignoreCase = true) ||
                    tab.url.contains(query, ignoreCase = true)
            }

        emptyState.visibility = if (visibleTabs.isEmpty()) View.VISIBLE else View.GONE
        val horizontalPadding = ChromeSheet.dp(context, 18) * 2
        val columnGap = ChromeSheet.dp(context, 16)
        val availableWidth = context.resources.displayMetrics.widthPixels - horizontalPadding - columnGap
        val cardSize = (availableWidth / 2)
            .coerceIn(ChromeSheet.dp(context, 148), ChromeSheet.dp(context, 224))

        visibleTabs.chunked(2).forEachIndexed { rowIndex, rowTabs ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBaselineAligned(false)
                alpha = 0f
                translationY = ChromeSheet.dp(context, 14).toFloat()
            }
            rowTabs.forEachIndexed { columnIndex, tab ->
                row.addView(tabCard(tab, dialog), LinearLayout.LayoutParams(
                    0,
                    cardSize,
                    1f
                ).apply {
                    marginEnd = if (columnIndex == 0) ChromeSheet.dp(context, 8) else 0
                    marginStart = if (columnIndex == 1) ChromeSheet.dp(context, 8) else 0
                })
            }
            if (rowTabs.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(
                    0,
                    cardSize,
                    1f
                ).apply {
                    marginStart = ChromeSheet.dp(context, 8)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = ChromeSheet.dp(context, 16)
            })
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((rowIndex * 34L).coerceAtMost(120L))
                .setDuration(260L)
                .setInterpolator(motionInterpolator)
                .start()
        }
    }

    private fun tabCard(tab: BrowserTab, dialog: Dialog?): LinearLayout {
        val active = tab.id == tabs.activeTab?.id
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(active)
            foreground = selectableItemBackground()
            isClickable = true
            isFocusable = true
            clipToOutline = true
            setPadding(ChromeSheet.dp(context, 8), ChromeSheet.dp(context, 8), ChromeSheet.dp(context, 8), ChromeSheet.dp(context, 8))
            setOnClickListener {
                dialog?.dismiss()
                selectTab(tab)
            }

            addView(FrameLayout(context).apply {
                background = previewBackground(tab.isPrivate)
                clipToOutline = true
                val thumbnail = thumbnailProvider(tab)
                if (thumbnail != null) {
                    addView(ImageView(context).apply {
                        setImageBitmap(thumbnail)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = null
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    addView(View(context).apply {
                        setBackgroundColor(Color.argb(44, 0, 0, 0))
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                } else {
                    addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    orientation = LinearLayout.VERTICAL
                    setPadding(ChromeSheet.dp(context, 14), 0, ChromeSheet.dp(context, 14), 0)

                    addView(ImageView(context).apply {
                        setImageResource(if (tab.isPrivate) R.drawable.ic_shield else R.drawable.ic_home)
                        imageTintList = ColorStateList.valueOf(context.getColor(R.color.kernel_muted))
                    }, LinearLayout.LayoutParams(ChromeSheet.dp(context, 30), ChromeSheet.dp(context, 30)).apply {
                        bottomMargin = ChromeSheet.dp(context, 12)
                    })

                    addView(TextView(context).apply {
                        text = previewSubtitle(tab)
                        gravity = Gravity.CENTER
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        textSize = 13f
                        setTextColor(context.getColor(R.color.kernel_muted))
                    })
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))

            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ChromeSheet.dp(context, 2), ChromeSheet.dp(context, 7), 0, 0)
                addView(TextView(context).apply {
                    text = tabTitle(tab)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(context.getColor(R.color.kernel_text))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                addView(iconButton(R.drawable.ic_close, "Close tab", onClick = {
                    closeTab(tab)
                    if (tabs.count() == 0) {
                        dialog?.dismiss()
                    } else {
                        renderTabs(dialog)
                    }
                }, compact = true))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ChromeSheet.dp(context, 42)
            ))
        }
    }

    private fun addButton(dialog: Dialog): TextView {
        return TextView(context).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 36f
            typeface = Typeface.DEFAULT
            setTextColor(Color.WHITE)
            background = rounded(context.getColor(R.color.kernel_accent), ChromeSheet.dp(context, 16).toFloat())
            elevation = ChromeSheet.dp(context, 14).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "New tab"
            setOnClickListener {
                dialog.dismiss()
                createTab(mode)
            }
        }
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit, compact: Boolean = false): ImageButton {
        return ImageButton(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.kernel_text))
            background = selectableItemBackground()
            contentDescription = label
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ChromeSheet.dp(context, if (compact) 40 else 48),
                ChromeSheet.dp(context, if (compact) 40 else 48)
            )
        }
    }

    private fun toggleButton(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            minWidth = ChromeSheet.dp(context, 64)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            styleToggle(this, selected)
        }
    }

    private fun styleToggle(view: TextView, selected: Boolean) {
        view.setTextColor(context.getColor(if (selected) R.color.kernel_text else R.color.kernel_muted))
        view.background = rounded(
            if (selected) context.getColor(R.color.kernel_surface_alt) else Color.TRANSPARENT,
            ChromeSheet.dp(context, 10).toFloat()
        )
    }

    private fun cardBackground(active: Boolean): GradientDrawable {
        return rounded(context.getColor(R.color.kernel_surface_alt), ChromeSheet.dp(context, 10).toFloat()).apply {
            setStroke(
                ChromeSheet.dp(context, if (active) 2 else 1),
                context.getColor(if (active) R.color.kernel_accent else R.color.kernel_border)
            )
        }
    }

    private fun previewBackground(private: Boolean): GradientDrawable {
        val color = if (private) Color.rgb(32, 34, 40) else context.getColor(R.color.kernel_surface_elevated)
        return rounded(color, ChromeSheet.dp(context, 8).toFloat())
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val outValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return context.getDrawable(outValue.resourceId)
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun tabTitle(tab: BrowserTab): String {
        return tab.title.ifBlank {
            if (tab.isPrivate) "Private tab" else "New tab"
        }
    }

    private fun previewSubtitle(tab: BrowserTab): String {
        return when {
            tab.isPrivate && tab.url.isBlank() -> "No history saved for this tab."
            tab.url.isBlank() -> "Search or enter a site."
            else -> tab.url
        }
    }

    private fun normalTabs(): List<BrowserTab> = tabs.all().filter { it.mode == TabMode.NORMAL }

    private fun privateTabs(): List<BrowserTab> = tabs.all().filter { it.mode == TabMode.PRIVATE }

    private fun statusBarHeight(): Int = systemDimension("status_bar_height").coerceAtLeast(ChromeSheet.dp(context, 12))

    private fun navigationBarHeight(): Int = systemDimension("navigation_bar_height").coerceAtLeast(ChromeSheet.dp(context, 12))

    private fun systemDimension(name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
