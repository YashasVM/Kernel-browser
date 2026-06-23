package com.kernel.browser

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.animation.DecelerateInterpolator

object ChromeSheet {
    private val sheetInterpolator = DecelerateInterpolator(1.6f)

    fun show(
        context: Context,
        title: String,
        subtitle: String? = null,
        scrollable: Boolean = true,
        builder: LinearLayout.(Dialog) -> Unit
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_background)
            setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 16))
        }

        sheet.addView(View(context).apply {
            setBackgroundResource(R.drawable.sheet_handle)
            layoutParams = LinearLayout.LayoutParams(dp(context, 38), dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 16)
            }
        })

        sheet.addView(header(context, title, subtitle) { dialog.dismiss() })

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            builder(dialog)
        }

        if (scrollable) {
            sheet.addView(ScrollView(context).apply {
                isFillViewport = false
                addView(content)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        } else {
            sheet.addView(content)
        }

        dialog.setContentView(sheet)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(0.36f)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setGravity(Gravity.BOTTOM)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            sheet.alpha = 0f
            sheet.translationY = dp(context, 28).toFloat()
            sheet.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(sheetInterpolator)
                .start()
        }
        dialog.show()
        return dialog
    }

    fun sectionLabel(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text.uppercase()
            textSize = 12f
            setTextColor(context.getColor(R.color.kernel_muted))
            letterSpacing = 0.08f
            setPadding(dp(context, 2), dp(context, 18), dp(context, 2), dp(context, 8))
        }
    }

    fun note(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setLineSpacing(dp(context, 2).toFloat(), 1f)
            setTextColor(context.getColor(R.color.kernel_muted))
            setBackgroundResource(R.drawable.sheet_row_background)
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, 8)
            }
        }
    }

    fun statusPill(context: Context, text: String, accent: Boolean = false): TextView {
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            minHeight = dp(context, 32)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(context.getColor(if (accent) R.color.kernel_accent else R.color.kernel_muted))
            setPadding(dp(context, 10), 0, dp(context, 10), 0)
        }
    }

    fun row(
        context: Context,
        title: String,
        subtitle: String? = null,
        trailing: View? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 64)
            setPadding(dp(context, 16), dp(context, 13), dp(context, 12), dp(context, 13))
            setBackgroundResource(R.drawable.sheet_row_background)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, 8)
            }
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 16f
                    setTextColor(context.getColor(R.color.kernel_text))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                if (!subtitle.isNullOrBlank()) {
                    addView(TextView(context).apply {
                        text = subtitle
                        textSize = 13f
                        setLineSpacing(dp(context, 1).toFloat(), 1f)
                        setTextColor(context.getColor(R.color.kernel_muted))
                        setPadding(0, dp(context, 3), dp(context, 8), 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            trailing?.let {
                addView(it)
            }
        }
    }

    fun actionButton(
        context: Context,
        text: String,
        primary: Boolean = false,
        danger: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            minHeight = dp(context, 46)
            textSize = 15f
            setTextColor(
                when {
                    primary -> Color.WHITE
                    danger -> context.getColor(R.color.kernel_danger)
                    else -> context.getColor(R.color.kernel_accent)
                }
            )
            setBackgroundResource(if (primary) R.drawable.sheet_button_background else R.drawable.sheet_button_secondary_background)
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun header(
        context: Context,
        title: String,
        subtitle: String?,
        onDone: () -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, 12))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 26f
                    setTextColor(context.getColor(R.color.kernel_text))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                if (!subtitle.isNullOrBlank()) {
                    addView(TextView(context).apply {
                        text = subtitle
                        textSize = 14f
                        setTextColor(context.getColor(R.color.kernel_muted))
                        setPadding(0, dp(context, 2), dp(context, 12), 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(context).apply {
                text = "Done"
                gravity = Gravity.CENTER
                minWidth = dp(context, 58)
                minHeight = dp(context, 44)
                setTextColor(context.getColor(R.color.kernel_accent))
                textSize = 16f
                isClickable = true
                isFocusable = true
                setOnClickListener { onDone() }
            })
        }
    }

    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
