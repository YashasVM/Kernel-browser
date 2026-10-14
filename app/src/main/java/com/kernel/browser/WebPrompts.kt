package com.kernel.browser

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import java.util.Calendar

/**
 * Native UI for the prompts web pages raise: alert/confirm/prompt, <select> menus, date and
 * color inputs, HTTP sign-in, blocked pop-ups, "leave page?" and navigator.share.
 *
 * GeckoView treats an unhandled prompt as dismissed, so without these a <select> never opens
 * and confirm() always answers "Cancel".
 */
class WebPrompts(
    private val context: Context,
    private val showMessage: (message: String, action: String?, onAction: (() -> Unit)?) -> Unit
) {
    private fun dp(value: Int) = ChromeSheet.dp(context, value)

    /** Completes [result] once, whichever of confirm, cancel, or the page's own dismissal comes first. */
    private class Once(private val prompt: BasePrompt, private val result: GeckoResult<PromptResponse>) {
        var dialog: Dialog? = null

        fun complete(response: () -> PromptResponse) {
            if (prompt.isComplete) return
            result.complete(response())
        }

        fun dismiss() = complete { prompt.dismiss() }
    }

    private fun begin(prompt: BasePrompt): Pair<Once, GeckoResult<PromptResponse>> {
        val result = GeckoResult<PromptResponse>()
        val once = Once(prompt, result)
        // The page can withdraw a prompt (navigation, script); close our UI with it.
        prompt.delegate = object : PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: BasePrompt) {
                once.dialog?.dismiss()
            }
        }
        return once to result
    }

    private fun sheet(
        once: Once,
        title: String,
        subtitle: String? = null,
        scrollable: Boolean = true,
        builder: LinearLayout.(Dialog) -> Unit
    ): Dialog {
        val dialog = ChromeSheet.show(context, title, subtitle, scrollable = scrollable, showDone = false,
            compactTitle = true, builder = builder)
        dialog.setOnDismissListener { once.dismiss() }
        once.dialog = dialog
        return dialog
    }

    fun alert(prompt: PromptDelegate.AlertPrompt, origin: String?): GeckoResult<PromptResponse> {
        val (once, result) = begin(prompt)
        sheet(once, title = siteSays(origin)) { dialog ->
            prompt.message?.takeIf { it.isNotBlank() }?.let { addView(ChromeSheet.message(context, it.take(MAX_MESSAGE))) }
            addView(ChromeSheet.actionButton(context, "OK", primary = true) { dialog.dismiss() })
        }
        return result
    }

    fun button(prompt: PromptDelegate.ButtonPrompt, origin: String?): GeckoResult<PromptResponse> {
        val (once, result) = begin(prompt)
        sheet(once, title = siteSays(origin)) { dialog ->
            prompt.message?.takeIf { it.isNotBlank() }?.let { addView(ChromeSheet.message(context, it.take(MAX_MESSAGE))) }
            addView(ChromeSheet.buttonRow(
                context,
                ChromeSheet.actionButton(context, "Cancel") {
                    once.complete { prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE) }
                    dialog.dismiss()
                },
                ChromeSheet.actionButton(context, "OK", primary = true) {
                    once.complete { prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE) }
                    dialog.dismiss()
                }
            ))
        }
        return result
    }

    fun text(prompt: PromptDelegate.TextPrompt, origin: String?): GeckoResult<PromptResponse> {
        val (once, result) = begin(prompt)
        val input = ChromeSheet.textField(context, hint = "", value = prompt.defaultValue)
        sheet(once, title = siteSays(origin), scrollable = false) { dialog ->
            prompt.message?.takeIf { it.isNotBlank() }?.let { addView(ChromeSheet.message(context, it.take(MAX_MESSAGE))) }
            addView(input)
            val submit = {
                once.complete { prompt.confirm(input.text.toString()) }
                dialog.dismiss()
            }
            input.imeOptions = EditorInfo.IME_ACTION_DONE
            input.setOnEditorActionListener { _, _, _ -> submit(); true }
            addView(ChromeSheet.buttonRow(
                context,
                ChromeSheet.actionButton(context, "Cancel") { dialog.dismiss() },
                ChromeSheet.actionButton(context, "OK", primary = true) { submit() }
            ))
        }
        focus(input)
        return result
    }

    fun auth(prompt: PromptDelegate.AuthPrompt, origin: String?): GeckoResult<PromptResponse> {
        val (once, result) = begin(prompt)
        val options = prompt.authOptions
        val onlyPassword = options.flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        val user = ChromeSheet.textField(context, hint = "Username", value = options.username)
        val password = ChromeSheet.textField(context, hint = "Password", value = options.password, password = true)
        val host = runCatching { Uri.parse(options.uri).host }.getOrNull() ?: origin
        sheet(once, title = "Sign in", subtitle = host, scrollable = false) { dialog ->
            prompt.message?.takeIf { it.isNotBlank() }?.let { addView(ChromeSheet.message(context, it.take(MAX_MESSAGE))) }
            if (options.level == PromptDelegate.AuthPrompt.AuthOptions.Level.NONE) {
                addView(ChromeSheet.note(context, "Your sign-in details will be sent without encryption."))
            }
            if (!onlyPassword) addView(user)
            addView(password)
            val submit = {
                once.complete {
                    if (onlyPassword) prompt.confirm(password.text.toString())
                    else prompt.confirm(user.text.toString(), password.text.toString())
                }
                dialog.dismiss()
            }
            password.imeOptions = EditorInfo.IME_ACTION_DONE
            password.setOnEditorActionListener { _, _, _ -> submit(); true }
            addView(ChromeSheet.buttonRow(
                context,
                ChromeSheet.actionButton(context, "Cancel") { dialog.dismiss() },
                ChromeSheet.actionButton(context, "Sign in", primary = true) { submit() }
            ))
        }
        focus(if (onlyPassword || !options.username.isNullOrEmpty()) password else user)
        return result
    }

    fun choice(prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
        val (once, result) = begin(prompt)
        val multiple = prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE
        val selected = linkedSetOf<String>()
        prompt.choices.flatMap { it.items?.toList() ?: listOf(it) }.forEach { if (it.selected) selected += it.id }
        sheet(once, title = prompt.title?.takeIf { it.isNotBlank() } ?: if (multiple) "Choose options" else "Choose an option") { dialog ->
            prompt.message?.takeIf { it.isNotBlank() }?.let { addView(ChromeSheet.message(context, it.take(MAX_MESSAGE))) }
            val rows = mutableListOf<View>()
            fun flushGroup() {
                if (rows.isNotEmpty()) {
                    addView(KernelUi.group(context, rows.toList()))
                    rows.clear()
                }
            }
            prompt.choices.forEach { choice ->
                val children = choice.items
                if (children != null) {
                    flushGroup()
                    addView(KernelUi.sectionHeader(context, choice.label))
                    children.forEach { child -> rows += choiceRow(child, multiple, selected, once, prompt, dialog) }
                    flushGroup()
                } else if (choice.separator) {
                    flushGroup()
                } else {
                    rows += choiceRow(choice, multiple, selected, once, prompt, dialog)
                }
            }
            flushGroup()
            if (multiple) {
                addView(View(context), LinearLayout.LayoutParams(1, dp(12)))
                addView(ChromeSheet.actionButton(context, "Done", primary = true) {
                    once.complete { prompt.confirm(selected.toTypedArray()) }
                    dialog.dismiss()
                })
            }
        }
        return result
    }

    private fun choiceRow(
        choice: PromptDelegate.ChoicePrompt.Choice,
        multiple: Boolean,
        selected: MutableSet<String>,
        once: Once,
        prompt: PromptDelegate.ChoicePrompt,
        dialog: Dialog
    ): View {
        val row = if (multiple) {
            KernelUi.toggle(context, choice.label.ifBlank { " " }, checked = choice.id in selected) { on ->
                if (on) selected += choice.id else selected -= choice.id
            }
        } else {
            KernelUi.choice(context, choice.label.ifBlank { " " }, selected = choice.selected) {
                once.complete { prompt.confirm(choice) }
                dialog.dismiss()
            }
        }
        if (choice.disabled) {
            row.isEnabled = false
            row.isClickable = false
            row.alpha = 0.4f
        }
        return row
    }

    fun color(prompt: PromptDelegate.ColorPrompt): GeckoResult<PromptResponse> {
        val (once, result) = begin(prompt)
        val presets = (prompt.predefinedValues?.toList().orEmpty() + COLOR_PRESETS).distinctBy { it.lowercase() }.take(24)
        val input = ChromeSheet.textField(context, hint = "#RRGGBB", value = prompt.defaultValue)
        sheet(once, title = prompt.title?.takeIf { it.isNotBlank() } ?: "Pick a color", scrollable = true) { dialog ->
            val grid = GridLayout(context).apply {
                columnCount = 6
                setPadding(0, 0, 0, dp(12))
            }
            val size = dp(44)
            presets.forEach { value ->
                val parsed = runCatching { Color.parseColor(value) }.getOrNull() ?: return@forEach
                grid.addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(parsed)
                        if (value.equals(prompt.defaultValue, ignoreCase = true)) {
                            setStroke(dp(3), context.getColor(R.color.kernel_accent))
                        } else {
                            setStroke(dp(1), context.getColor(R.color.kernel_border))
                        }
                    }
                    contentDescription = value
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        once.complete { prompt.confirm(value.lowercase()) }
                        dialog.dismiss()
                    }
                }, GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                })
            }
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(input)
            addView(ChromeSheet.buttonRow(
                context,
                ChromeSheet.actionButton(context, "Cancel") { dialog.dismiss() },
                ChromeSheet.actionButton(context, "Use color", primary = true) {
                    val value = normalizeHex(input.text.toString())
                    if (value == null) {
                        input.error = "Use a hex color like #3366ff"
                    } else {
                        once.complete { prompt.confirm(value) }
                        dialog.dismiss()
                    }
                }
            ))
        }
        return result
    }

    fun dateTime(prompt: PromptDelegate.DateTimePrompt): GeckoResult<PromptResponse> {
        val (once, result) = begin(prompt)
        val type = prompt.type
        val initial = WebDateTime.parse(type, prompt.defaultValue) ?: WebDateTime.now()
        val dialog: Dialog = when (type) {
            PromptDelegate.DateTimePrompt.Type.TIME -> TimePickerDialog(context, { _, hour, minute ->
                once.complete { prompt.confirm(WebDateTime.format(type, initial.copy(hour = hour, minute = minute))) }
            }, initial.hour, initial.minute, DateFormat.is24HourFormat(context))
            else -> DatePickerDialog(context, { _, year, month, day ->
                val picked = initial.copy(year = year, month = month + 1, day = day)
                if (type == PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL) {
                    // Ask for the time next; the prompt stays open until both are picked.
                    TimePickerDialog(context, { _, hour, minute ->
                        once.complete { prompt.confirm(WebDateTime.format(type, picked.copy(hour = hour, minute = minute))) }
                    }, picked.hour, picked.minute, DateFormat.is24HourFormat(context)).apply {
                        setOnDismissListener { once.dismiss() }
                        show()
                    }
                } else {
                    once.complete { prompt.confirm(WebDateTime.format(type, picked)) }
                }
            }, initial.year, initial.month - 1, initial.day).apply {
                WebDateTime.parse(PromptDelegate.DateTimePrompt.Type.DATE, prompt.minValue)?.let { datePicker.minDate = it.toMillis() }
                WebDateTime.parse(PromptDelegate.DateTimePrompt.Type.DATE, prompt.maxValue)?.let { datePicker.maxDate = it.toMillis() }
            }
        }
        if (dialog is android.app.AlertDialog) {
            dialog.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "Clear") { _, _ ->
                once.complete { prompt.confirm("") }
            }
        }
        dialog.setOnDismissListener {
            // DATETIME_LOCAL hands off to a second picker; only that one may cancel the prompt.
            if (type != PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL) once.dismiss()
        }
        dialog.setOnCancelListener { once.dismiss() }
        once.dialog = dialog
        dialog.show()
        return result
    }

    fun popup(prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> {
        val (once, result) = begin(prompt)
        // Blocked unless the user taps Allow on the message within a few seconds; nothing modal.
        val target = prompt.targetUri?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.removePrefix("www.")
        showMessage(if (target.isNullOrBlank()) "Pop-up blocked" else "Pop-up blocked: $target", "Allow") {
            once.complete { prompt.confirm(AllowOrDeny.ALLOW) }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            once.complete { prompt.confirm(AllowOrDeny.DENY) }
        }, POPUP_DECISION_MS)
        return result
    }

    fun beforeUnload(prompt: PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptResponse> {
        val (once, result) = begin(prompt)
        sheet(once, title = "Leave this page?", subtitle = "Changes you made may not be saved.", scrollable = false) { dialog ->
            addView(ChromeSheet.buttonRow(
                context,
                ChromeSheet.actionButton(context, "Stay") {
                    once.complete { prompt.confirm(AllowOrDeny.DENY) }
                    dialog.dismiss()
                },
                ChromeSheet.actionButton(context, "Leave", danger = true) {
                    once.complete { prompt.confirm(AllowOrDeny.ALLOW) }
                    dialog.dismiss()
                }
            ))
        }
        return result
    }

    fun repost(prompt: PromptDelegate.RepostConfirmPrompt): GeckoResult<PromptResponse> {
        val (once, result) = begin(prompt)
        sheet(once, title = "Resend form?", subtitle = "This page needs the information you entered earlier. Sending it again may repeat an action, like a purchase.", scrollable = false) { dialog ->
            addView(ChromeSheet.buttonRow(
                context,
                ChromeSheet.actionButton(context, "Cancel") {
                    once.complete { prompt.confirm(AllowOrDeny.DENY) }
                    dialog.dismiss()
                },
                ChromeSheet.actionButton(context, "Resend", primary = true) {
                    once.complete { prompt.confirm(AllowOrDeny.ALLOW) }
                    dialog.dismiss()
                }
            ))
        }
        return result
    }

    fun share(prompt: PromptDelegate.SharePrompt): GeckoResult<PromptResponse> {
        val (once, result) = begin(prompt)
        val text = listOfNotNull(prompt.text?.takeIf { it.isNotBlank() }, prompt.uri?.takeIf { it.isNotBlank() })
            .joinToString("\n")
        if (text.isBlank()) {
            once.complete { prompt.confirm(PromptDelegate.SharePrompt.Result.FAILURE) }
            return result
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            prompt.title?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val shared = runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }.isSuccess
        once.complete {
            prompt.confirm(if (shared) PromptDelegate.SharePrompt.Result.SUCCESS else PromptDelegate.SharePrompt.Result.FAILURE)
        }
        return result
    }

    private fun siteSays(origin: String?): String {
        val host = origin?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.removePrefix("www.")
        return if (host.isNullOrBlank()) "This page says" else "$host says"
    }

    private fun focus(input: EditText) {
        input.post {
            input.requestFocus()
            input.setSelection(input.text.length)
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun normalizeHex(value: String): String? {
        val trimmed = value.trim().removePrefix("#")
        val full = when {
            trimmed.length == 3 && trimmed.all { it.isHexDigit() } -> trimmed.map { "$it$it" }.joinToString("")
            trimmed.length == 6 && trimmed.all { it.isHexDigit() } -> trimmed
            else -> return null
        }
        return "#" + full.lowercase()
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    companion object {
        private const val POPUP_DECISION_MS = 6_000L
        private const val MAX_MESSAGE = 2_000
        private val COLOR_PRESETS = listOf(
            "#000000", "#5f6368", "#9aa0a6", "#dadce0", "#ffffff", "#e8eaed",
            "#ea4335", "#fbbc04", "#34a853", "#4285f4", "#a142f4", "#f439a0",
            "#b31412", "#e37400", "#0d652d", "#174ea6", "#681da8", "#9c166b"
        )
    }
}

/** Parses and formats the value strings HTML date/time inputs use. Pure, so it is unit tested. */
object WebDateTime {
    data class Value(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int) {
        fun toMillis(): Long = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis
    }

    private const val DATE = 1
    private const val MONTH = 2
    private const val WEEK = 3
    private const val TIME = 4
    private const val DATETIME_LOCAL = 5

    fun now(): Value {
        val c = Calendar.getInstance()
        return Value(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun parse(type: Int, value: String?): Value? {
        if (value.isNullOrBlank()) return null
        val base = Value(1970, 1, 1, 0, 0)
        return runCatching {
            when (type) {
                DATE -> {
                    val (y, m, d) = value.trim().split('-').map { it.toInt() }
                    base.copy(year = y, month = m, day = d)
                }
                MONTH -> {
                    val (y, m) = value.trim().split('-').map { it.toInt() }
                    base.copy(year = y, month = m)
                }
                WEEK -> {
                    val (y, w) = value.trim().split("-W").map { it.toInt() }
                    val c = Calendar.getInstance().apply {
                        clear()
                        minimalDaysInFirstWeek = 4
                        firstDayOfWeek = Calendar.MONDAY
                        set(Calendar.YEAR, y)
                        set(Calendar.WEEK_OF_YEAR, w)
                        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    }
                    base.copy(year = c.get(Calendar.YEAR), month = c.get(Calendar.MONTH) + 1, day = c.get(Calendar.DAY_OF_MONTH))
                }
                TIME -> {
                    val parts = value.trim().split(':')
                    base.copy(hour = parts[0].toInt(), minute = parts[1].take(2).toInt())
                }
                DATETIME_LOCAL -> {
                    val (date, time) = value.trim().split('T', ' ', limit = 2)
                    val d = parse(DATE, date)!!
                    val t = parse(TIME, time)!!
                    d.copy(hour = t.hour, minute = t.minute)
                }
                else -> null
            }
        }.getOrNull()
    }

    fun format(type: Int, v: Value): String {
        fun two(n: Int) = n.toString().padStart(2, '0')
        val date = "${v.year.toString().padStart(4, '0')}-${two(v.month)}-${two(v.day)}"
        val time = "${two(v.hour)}:${two(v.minute)}"
        return when (type) {
            MONTH -> "${v.year.toString().padStart(4, '0')}-${two(v.month)}"
            WEEK -> {
                val c = Calendar.getInstance().apply {
                    clear()
                    minimalDaysInFirstWeek = 4
                    firstDayOfWeek = Calendar.MONDAY
                    set(v.year, v.month - 1, v.day)
                }
                // ISO week-numbering year can differ from the calendar year around New Year.
                val week = c.get(Calendar.WEEK_OF_YEAR)
                val weekYear = c.weekYear
                "${weekYear.toString().padStart(4, '0')}-W${two(week)}"
            }
            TIME -> time
            DATETIME_LOCAL -> "${date}T$time"
            else -> date
        }
    }
}
