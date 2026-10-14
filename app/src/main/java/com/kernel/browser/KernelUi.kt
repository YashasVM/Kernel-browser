package com.kernel.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * Grouped-list building blocks shared by Settings, the browser menu, and the
 * extension manager: rounded cards of rows with a colored icon tile, a title,
 * an optional subtitle, and an optional value, switch, or chevron.
 */
object KernelUi {
    fun dp(context: Context, value: Int): Int = ChromeSheet.dp(context, value)

    fun sectionHeader(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text.uppercase()
            textSize = 12f
            letterSpacing = 0.06f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(context.getColor(R.color.kernel_muted))
            setPadding(dp(context, 18), dp(context, 22), dp(context, 18), dp(context, 8))
        }
    }

    fun footer(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            setLineSpacing(dp(context, 2).toFloat(), 1f)
            setTextColor(context.getColor(R.color.kernel_muted))
            setPadding(dp(context, 18), dp(context, 8), dp(context, 18), 0)
        }
    }

    /** A rounded card that stacks [rows] with inset dividers between them. */
    fun group(context: Context, rows: List<View>): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.kernel_group))
                cornerRadius = dp(context, 18).toFloat()
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(context, 18).toFloat())
                }
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    addView(View(context).apply {
                        setBackgroundColor(context.getColor(R.color.kernel_divider))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        marginStart = if (row.getTag(R.id.kernel_row_has_icon) == true) dp(context, 62) else dp(context, 16)
                    })
                }
                addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        }
    }

    fun group(context: Context, vararg rows: View): LinearLayout = group(context, rows.toList())

    fun iconTile(context: Context, icon: Int, colorRes: Int): FrameLayout {
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(context.getColor(colorRes))
                cornerRadius = dp(context, 9).toFloat()
            }
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(context.getColor(android.R.color.white))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(context, 19), dp(context, 19), Gravity.CENTER))
        }
    }

    fun bitmapTile(context: Context, bitmap: Bitmap?, fallbackIcon: Int, colorRes: Int): View {
        if (bitmap == null) return iconTile(context, fallbackIcon, colorRes)
        return ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    /**
     * A list row. Pass [value] for a right-aligned detail label, [trailing] for
     * a custom control, and [chevron] when tapping navigates somewhere.
     */
    fun item(
        context: Context,
        title: String,
        subtitle: String? = null,
        icon: Int? = null,
        tile: Int = R.color.tile_blue,
        leading: View? = null,
        value: String? = null,
        trailing: View? = null,
        chevron: Boolean = false,
        danger: Boolean = false,
        accent: Boolean = false,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, if (subtitle.isNullOrBlank()) 54 else 64)
            setPadding(dp(context, 16), dp(context, 10), dp(context, 14), dp(context, 10))
            contentDescription = listOfNotNull(title, subtitle, value).joinToString(", ")
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                foreground = selectableItemBackground(context)
                setOnClickListener { onClick() }
            }

            val leadingView = leading ?: icon?.let { iconTile(context, it, tile) }
            if (leadingView != null) {
                setTag(R.id.kernel_row_has_icon, true)
                addView(leadingView, LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)).apply {
                    marginEnd = dp(context, 14)
                })
            }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 16f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(context.getColor(
                        when {
                            danger -> R.color.kernel_danger
                            accent -> R.color.kernel_accent
                            else -> R.color.kernel_text
                        }
                    ))
                })
                if (!subtitle.isNullOrBlank()) {
                    addView(TextView(context).apply {
                        text = subtitle
                        textSize = 13f
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(context.getColor(R.color.kernel_muted))
                        setPadding(0, dp(context, 2), dp(context, 4), 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (!value.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = value
                    textSize = 15f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(context, 150)
                    setTextColor(context.getColor(R.color.kernel_muted))
                    setPadding(dp(context, 8), 0, dp(context, 2), 0)
                })
            }
            trailing?.let { addView(it) }
            if (chevron) {
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    imageTintList = ColorStateList.valueOf(context.getColor(R.color.kernel_disabled))
                }, LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)))
            }
        }
    }

    /** A row whose whole surface flips the switch, so the target is never just the thumb. */
    fun toggle(
        context: Context,
        title: String,
        subtitle: String? = null,
        icon: Int? = null,
        tile: Int = R.color.tile_blue,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): LinearLayout {
        val switch = Switch(context).apply {
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        return item(
            context = context,
            title = title,
            subtitle = subtitle,
            icon = icon,
            tile = tile,
            trailing = switch,
            onClick = { switch.toggle() }
        )
    }

    /** A single-choice row with a check mark on the selected option. */
    fun choice(context: Context, title: String, subtitle: String? = null, selected: Boolean, onClick: () -> Unit): LinearLayout {
        val check = ImageView(context).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.kernel_accent))
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(dp(context, 22), dp(context, 22))
        }
        return item(context, title = title, subtitle = subtitle, trailing = check, onClick = onClick)
    }

    fun pill(context: Context, text: String, colorRes: Int = R.color.kernel_accent): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(context.getColor(colorRes))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.kernel_surface_alt))
                cornerRadius = dp(context, 12).toFloat()
            }
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
        }
    }

    fun selectableItemBackground(context: Context): android.graphics.drawable.Drawable? {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return attrs.getDrawable(0).also { attrs.recycle() }
    }
}
