package com.kernel.browser

import android.app.Dialog
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.kernel.browser.extensions.AllowedExtension
import com.kernel.browser.extensions.AllowedExtensions
import com.kernel.browser.extensions.ExtensionInstaller
import com.kernel.browser.extensions.ExtensionPreferences
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.StorageController

class SettingsDialogController(
    private val context: Context,
    private val runtime: GeckoRuntime,
    private val extensionPreferences: ExtensionPreferences,
    private val extensionInstaller: ExtensionInstaller,
    private val resetBrowserState: () -> Unit,
    private val historyProvider: () -> List<Pair<String, String>> = { emptyList() },
    private val openHistoryEntry: (String) -> Unit = {},
    private val clearHistory: () -> Unit = {}
) {
    private val motionInterpolator = DecelerateInterpolator(1.6f)

    fun show() {
        val dialog = Dialog(context)
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
            setPadding(ChromeSheet.dp(context, 22), ChromeSheet.dp(context, 18), ChromeSheet.dp(context, 22), ChromeSheet.dp(context, 28))
        }

        content.addView(header(dialog))
        content.addView(sectionLabel("Features"))
        content.addView(group(
            settingsRow(
                icon = R.drawable.ic_shield,
                title = "Privacy & security",
                subtitle = "Site permissions, private browsing, and browser data.",
                position = RowPosition.TOP,
                onClick = { showPrivacySheet() }
            ),
            settingsRow(
                icon = R.drawable.ic_extension,
                title = "Extensions",
                subtitle = extensionStatus(),
                position = RowPosition.MIDDLE,
                onClick = { showExtensionsSheet() }
            ),
            settingsRow(
                icon = R.drawable.ic_close,
                title = "Clear browsing data",
                subtitle = "Cookies, cache, permissions, storage, and open tabs.",
                position = RowPosition.BOTTOM,
                onClick = { confirmClearData() }
            )
        ))

        content.addView(sectionLabel("General"))
        content.addView(group(
            settingsRow(
                icon = R.drawable.ic_tabs,
                title = "History",
                subtitle = "Review saved browsing and clear local data.",
                position = RowPosition.TOP,
                onClick = { showHistorySheet() }
            ),
            settingsRow(
                icon = R.drawable.ic_settings,
                title = "Search engine",
                subtitle = "Address bar search provider.",
                position = RowPosition.MIDDLE,
                onClick = { showSearchEngineSheet() }
            ),
            settingsRow(
                icon = R.drawable.ic_home,
                title = "Homepage",
                subtitle = "On",
                position = RowPosition.BOTTOM,
                onClick = { showHomepageSheet() }
            )
        ))

        content.addView(sectionLabel("Browser"))
        content.addView(group(
            settingsRow(
                icon = R.drawable.ic_arrow_back,
                title = "Navigation",
                subtitle = "Back, forward, reload, tabs, and home controls.",
                position = RowPosition.TOP,
                onClick = {
                    Toast.makeText(context, "Navigation controls are in the bottom browser pill.", Toast.LENGTH_SHORT).show()
                }
            ),
            settingsRow(
                icon = R.drawable.ic_shield,
                title = "Build",
                subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) - ${BuildConfig.UI_BUILD_TAG}",
                position = RowPosition.BOTTOM,
                onClick = {
                    Toast.makeText(context, "Running ${BuildConfig.UI_BUILD_TAG}", Toast.LENGTH_SHORT).show()
                }
            )
        ))

        root.addView(ScrollView(context).apply {
            isFillViewport = false
            clipToPadding = false
            addView(content)
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        dialog.setContentView(root)
        dialog.setOnDismissListener {
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
            content.translationY = ChromeSheet.dp(context, 18).toFloat()
            content.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(motionInterpolator)
                .start()
        }
        dialog.show()
    }

    private fun header(dialog: Dialog): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ChromeSheet.dp(context, 64)
            setPadding(0, 0, 0, ChromeSheet.dp(context, 10))

            addView(iconButton(R.drawable.ic_arrow_back, "Close settings") { dialog.dismiss() })

            addView(TextView(context).apply {
                text = "Settings"
                textSize = 32f
                typeface = Typeface.DEFAULT
                setTextColor(context.getColor(R.color.kernel_text))
                includeFontPadding = false
                setPadding(ChromeSheet.dp(context, 14), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(iconButton(R.drawable.ic_close, "Close settings") { dialog.dismiss() })
        }
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(context.getColor(R.color.kernel_muted))
            setPadding(ChromeSheet.dp(context, 40), ChromeSheet.dp(context, 26), 0, ChromeSheet.dp(context, 12))
        }
    }

    private fun group(vararg rows: View): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            rows.forEach { addView(it) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = ChromeSheet.dp(context, 12)
            }
        }
    }

    private fun settingsRow(
        icon: Int,
        title: String,
        subtitle: String? = null,
        position: RowPosition,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ChromeSheet.dp(context, if (subtitle.isNullOrBlank()) 72 else 82)
            setPadding(ChromeSheet.dp(context, 22), ChromeSheet.dp(context, 12), ChromeSheet.dp(context, 16), ChromeSheet.dp(context, 12))
            background = rowBackground(position)
            foreground = selectableItemBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            contentDescription = title

            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.kernel_muted))
                scaleType = ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams(ChromeSheet.dp(context, 32), ChromeSheet.dp(context, 32)).apply {
                marginEnd = ChromeSheet.dp(context, 22)
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(context).apply {
                    text = title
                    textSize = 20f
                    setTextColor(context.getColor(R.color.kernel_text))
                    includeFontPadding = false
                })

                if (!subtitle.isNullOrBlank()) {
                    addView(TextView(context).apply {
                        text = subtitle
                        textSize = 15f
                        setTextColor(context.getColor(R.color.kernel_muted))
                        includeFontPadding = false
                        setPadding(0, ChromeSheet.dp(context, 5), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_arrow_forward)
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.kernel_muted))
                scaleType = ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams(ChromeSheet.dp(context, 32), ChromeSheet.dp(context, 44)))
        }
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.kernel_text))
            background = selectableItemBackground()
            contentDescription = label
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ChromeSheet.dp(context, 48), ChromeSheet.dp(context, 48))
        }
    }

    private fun rowBackground(position: RowPosition): GradientDrawable {
        val radius = ChromeSheet.dp(context, 28).toFloat()
        val smallGap = ChromeSheet.dp(context, 6)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(context.getColor(R.color.kernel_surface_alt))
            cornerRadii = when (position) {
                RowPosition.TOP -> floatArrayOf(radius, radius, radius, radius, smallGap.toFloat(), smallGap.toFloat(), smallGap.toFloat(), smallGap.toFloat())
                RowPosition.MIDDLE -> floatArrayOf(smallGap.toFloat(), smallGap.toFloat(), smallGap.toFloat(), smallGap.toFloat(), smallGap.toFloat(), smallGap.toFloat(), smallGap.toFloat(), smallGap.toFloat())
                RowPosition.BOTTOM -> floatArrayOf(smallGap.toFloat(), smallGap.toFloat(), smallGap.toFloat(), smallGap.toFloat(), radius, radius, radius, radius)
                RowPosition.SINGLE -> FloatArray(8) { radius }
            }
        }
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return attrs.getDrawable(0).also { attrs.recycle() }
    }

    private fun showHistorySheet() {
        ChromeSheet.show(
            context = context,
            title = "History",
            subtitle = "Recent pages from normal tabs."
        ) { dialog ->
            val entries = historyProvider()
            if (entries.isEmpty()) {
                addView(ChromeSheet.note(
                    context,
                    "Pages you visit in normal tabs will appear here. Private tabs are not saved."
                ))
            } else {
                entries.take(30).forEach { (title, url) ->
                    addView(ChromeSheet.row(
                        context = context,
                        title = title,
                        subtitle = url,
                        onClick = {
                            dialog.dismiss()
                            openHistoryEntry(url)
                        }
                    ))
                }
                addView(ChromeSheet.actionButton(context, "Clear history", danger = true) {
                    clearHistory()
                    dialog.dismiss()
                    Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }

    private fun showSearchEngineSheet() {
        ChromeSheet.show(
            context = context,
            title = "Search engine",
            subtitle = "Address bar search provider."
        ) {
            addView(ChromeSheet.row(
                context = context,
                title = "Default search",
                subtitle = "The address bar uses the app's current search routing.",
                trailing = ChromeSheet.statusPill(context, "On", true)
            ))
            addView(ChromeSheet.note(
                context,
                "Search provider selection needs the address bar loader to expose provider choices. This settings page is ready for that option."
            ))
        }
    }

    private fun showHomepageSheet() {
        ChromeSheet.show(
            context = context,
            title = "Homepage",
            subtitle = "Shown when you tap Home."
        ) {
            addView(ChromeSheet.row(
                context = context,
                title = "Homepage",
                subtitle = context.getString(R.string.home_url),
                trailing = ChromeSheet.statusPill(context, "On", true)
            ))
            addView(ChromeSheet.note(
                context,
                "The homepage is fixed in this build. A custom homepage field can be added once browser preferences are exposed."
            ))
        }
    }

    private fun showPrivacySheet() {
        ChromeSheet.show(
            context = context,
            title = "Privacy & security",
            subtitle = "Private tabs, permissions, and local data."
        ) {
            addView(ChromeSheet.row(
                context = context,
                title = "Private browsing",
                subtitle = "Private tabs close automatically when the app exits.",
                trailing = ChromeSheet.statusPill(context, "On", true)
            ))
            addView(ChromeSheet.row(
                context = context,
                title = "Site data",
                subtitle = "Clear cookies, cache, permissions, and GeckoView storage.",
                onClick = { confirmClearData() }
            ))
            addView(ChromeSheet.row(
                context = context,
                title = "Extension private access",
                subtitle = "Manage which extensions can run in private tabs.",
                onClick = { showExtensionsSheet() }
            ))
        }
    }

    private fun showExtensionsSheet() {
        ChromeSheet.show(
            context = context,
            title = "Extensions",
            subtitle = "Install, enable, and allow private tab access."
        ) {
            val activeCount = AllowedExtensions.all.count { extensionPreferences.isEnabled(it) }
            addView(ChromeSheet.row(
                context = context,
                title = "Extension status",
                subtitle = "$activeCount of ${AllowedExtensions.all.size} enabled",
                trailing = ChromeSheet.statusPill(context, if (activeCount > 0) "On" else "Off", activeCount > 0)
            ))
            addView(ChromeSheet.note(
                context,
                "Enable an extension once. Use the Extensions button in the browser pill to open extension controls for the current page."
            ))
            AllowedExtensions.all.forEach { extension ->
                addView(extensionControl(extension))
            }
        }
    }

    private fun confirmClearData() {
        ChromeSheet.show(
            context = context,
            title = "Clear data?",
            subtitle = "This resets open tabs before clearing cookies, cache, permissions, and storage.",
            scrollable = false
        ) { dialog ->
            addView(actionRow(
                ChromeSheet.actionButton(context, "Cancel") { dialog.dismiss() },
                ChromeSheet.actionButton(context, "Clear", danger = true) {
                    dialog.dismiss()
                    clearHistory()
                    resetBrowserState()
                    runtime.storageController.clearData(StorageController.ClearFlags.ALL).accept(
                        { Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show() },
                        {
                            SafeLog.warning("Clearing browsing data failed", it)
                            Toast.makeText(context, "Could not clear browsing data", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            ))
        }
    }

    private fun extensionControl(extension: AllowedExtension): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            addView(ChromeSheet.row(
                context = context,
                title = extension.displayName,
                subtitle = "Install and allow this extension. ${extension.permissionSummary}",
                trailing = Switch(context).apply {
                    contentDescription = "Enable ${extension.displayName}"
                    minWidth = ChromeSheet.dp(context, 56)
                    isChecked = extensionPreferences.isEnabled(extension)
                    var internalChange = false
                    setOnCheckedChangeListener { button, checked ->
                        if (internalChange) return@setOnCheckedChangeListener
                        if (checked) {
                            internalChange = true
                            button.isChecked = false
                            internalChange = false
                            confirmEnable(extension) {
                                extensionPreferences.setEnabled(extension, true)
                                internalChange = true
                                button.isChecked = true
                                internalChange = false
                                extensionInstaller.ensureInstalled(extension)
                            }
                        } else {
                            extensionPreferences.setEnabled(extension, false)
                            extensionInstaller.disableIfInstalled(extension)
                        }
                    }
                }
            ))

            addView(ChromeSheet.row(
                context = context,
                title = "Private tabs",
                subtitle = "Let ${extension.displayName} run while private browsing.",
                trailing = Switch(context).apply {
                    contentDescription = "Allow ${extension.displayName} in private tabs"
                    minWidth = ChromeSheet.dp(context, 56)
                    isChecked = extensionPreferences.isAllowedInPrivate(extension)
                    setOnCheckedChangeListener { _, checked ->
                        extensionPreferences.setAllowedInPrivate(extension, checked)
                        extensionInstaller.applyPrivateMode(extension)
                    }
                }
            ))
        }
    }

    private fun confirmEnable(extension: AllowedExtension, onApproved: () -> Unit) {
        ChromeSheet.show(
            context = context,
            title = "Enable ${extension.displayName}?",
            subtitle = extension.permissionSummary,
            scrollable = false
        ) { dialog ->
            addView(actionRow(
                ChromeSheet.actionButton(context, "Cancel") { dialog.dismiss() },
                ChromeSheet.actionButton(context, "Enable", primary = true) {
                    dialog.dismiss()
                    onApproved()
                }
            ))
        }
    }

    private fun actionRow(left: View, right: View): LinearLayout {
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = ChromeSheet.dp(context, 6)
            })
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChromeSheet.dp(context, 6)
            })
        }
    }

    private fun extensionStatus(): String {
        val activeCount = AllowedExtensions.all.count { extensionPreferences.isEnabled(it) }
        return "$activeCount of ${AllowedExtensions.all.size} enabled"
    }

    private fun statusBarHeight(): Int {
        return systemDimension("status_bar_height").coerceAtLeast(ChromeSheet.dp(context, 12))
    }

    private fun navigationBarHeight(): Int {
        return systemDimension("navigation_bar_height").coerceAtLeast(ChromeSheet.dp(context, 12))
    }

    private fun systemDimension(name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private enum class RowPosition {
        TOP,
        MIDDLE,
        BOTTOM,
        SINGLE
    }
}
