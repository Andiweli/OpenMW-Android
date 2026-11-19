/*
    This file is part of OpenMW-Android.

    OpenMW-Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenMW-Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenMW-Android.  If not, see <https://www.gnu.org/licenses/>.
*/

package ui.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceGroup
import android.preference.TwoStatePreference
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.libopenmw.openmw.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shows a launcher settings screen in the CubeEvil4 menu style. The preferences
 * from the XML stay the model (keys, stored values, dependencies and dialogs are
 * unchanged); only their presentation is new:
 *
 * - a category becomes a section label,
 * - switches become full-width ON/OFF tiles, each with its short explanation below,
 * - a choice list with short labels becomes a row of option buttons; any other
 *   list becomes one button that steps through the choices,
 * - a value (resolution, a longer list, text) becomes a row with a value button,
 * - a [SliderSpec] key becomes an inline slider,
 * - entries that open another screen become tiles, up to three side by side.
 *
 * Call [refresh] after a preference changed; the controls update in place, so
 * the controller focus stays where it is.
 */
class PreferencePageView(
    context: Context,
    private val screen: PreferenceGroup,
    private val sliders: Map<String, SliderSpec> = emptyMap(),
    private val navigationKeys: Set<String> = emptySet()
) : FrameLayout(context) {

    /** An inline slider for a preference stored as a decimal string ("" = [autoValue]). */
    class SliderSpec(
        val minimum: Float,
        val maximum: Float,
        val step: Float,
        val defaultValue: Float,
        /** Decimal places stored and shown; 0 stores whole numbers ("4", not "4.0"). */
        val decimals: Int = 1,
        /** When set, an "Auto" button stores "" and the label shows this text. */
        val autoLabel: (() -> String)? = null,
        /** Show the preference's summary (its explanation) below the slider. */
        val summaryAsNote: Boolean = false
    )

    private val updaters = ArrayList<() -> Unit>()
    private fun dp(value: Int) = LauncherStyle.dp(context, value)

    init {
        setBackgroundColor(LauncherStyle.PANEL)
        // PreferenceFragment binds its adapter to android.R.id.list; keep one, hidden.
        addView(ListView(context).apply {
            id = android.R.id.list
            visibility = View.GONE
        }, LayoutParams(0, 0))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(2), dp(22), dp(16))
        }
        buildGroup(body, screen)
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        refresh()
    }

    fun refresh() {
        updaters.forEach { it() }
    }

    private fun children(group: PreferenceGroup): List<Preference> =
        (0 until group.preferenceCount).map { group.getPreference(it) }

    private fun buildGroup(parent: LinearLayout, group: PreferenceGroup) {
        val pendingToggles = ArrayList<TwoStatePreference>()
        val pendingTiles = ArrayList<Preference>()

        fun flushTiles() {
            if (pendingTiles.isEmpty())
                return
            val grid = EqualGrid(context, maxColumns = 3, minCellWidthDp = 220)
            pendingTiles.forEach { grid.addView(navigationTile(it)) }
            parent.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
            pendingTiles.clear()
        }

        // One switch per row, so its title is never cut off; its explanation right below.
        fun flushToggles() {
            pendingToggles.forEach { preference ->
                parent.addView(toggle(preference), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                })
                if (!preference.summary.isNullOrBlank())
                    parent.addView(note(preference.summary).also { bindEnabled(it, preference) })
            }
            pendingToggles.clear()
        }

        for (preference in children(group)) {
            if (preference is TwoStatePreference) {
                flushTiles()
                pendingToggles += preference
                continue
            }
            flushToggles()
            if (preference.key in navigationKeys) {
                pendingTiles += preference
                continue
            }
            flushTiles()
            when {
                preference is PreferenceCategory -> {
                    parent.addView(LauncherStyle.sectionLabel(context, preference.title ?: ""))
                    buildGroup(parent, preference)
                }
                preference.key in sliders -> {
                    val spec = sliders.getValue(preference.key)
                    parent.addView(sliderRow(preference, spec))
                    if (spec.summaryAsNote && !preference.summary.isNullOrBlank())
                        parent.addView(note(preference.summary))
                }
                preference is ListPreference && (preference.entries?.size ?: 0) >= 2 -> {
                    parent.addView(if (fitsAsButtons(preference)) optionRow(preference) else cycleRow(preference))
                    descriptiveSummary(preference)?.let { parent.addView(note(it)) }
                }
                else -> parent.addView(valueRow(preference))
            }
        }
        flushToggles()
        flushTiles()
    }

    /**
     * Short labels (numbers, Low/Medium/High) fit side by side; longer ones would be
     * cut off, so they get a single button that steps through the choices.
     */
    private fun fitsAsButtons(preference: ListPreference): Boolean {
        val labels = preference.entries.map { it.toString() }
        return labels.size <= 4 && labels.maxOf { it.length } <= 11 && labels.sumOf { it.length } <= 36
    }

    /** One button showing the current choice; tap or right goes to the next, left back. */
    private fun cycleRow(preference: ListPreference): View {
        val values = preference.entryValues
        fun step(by: Int) {
            val count = values.size
            val current = preference.findIndexOfValue(preference.value).coerceAtLeast(0)
            preference.value = values[((current + by) % count + count) % count].toString()
        }
        val button = LauncherStyle.button(context, "") { step(1) }.apply {
            setOnKeyListener { _, keyCode, event ->
                val by = when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    else -> 0
                }
                if (by != 0 && event.action == android.view.KeyEvent.ACTION_DOWN)
                    step(by)
                by != 0
            }
        }
        updaters += {
            button.text = "\u2039   ${preference.entry ?: ""}   \u203a"
        }
        val view = row(preference.title ?: "", button)
        bindEnabled(view, preference)
        return view
    }

    /** A list summary that explains the setting instead of repeating the chosen entry. */
    private fun descriptiveSummary(preference: ListPreference): CharSequence? {
        val summary = preference.summary?.toString() ?: return null
        if (summary.isBlank() || summary == preference.entry?.toString())
            return null
        return summary
    }

    private fun note(text: CharSequence): TextView =
        LauncherStyle.text(context, text, 12f, false).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }

    private fun bindEnabled(view: View, preference: Preference) {
        updaters += {
            val enabled = preference.isEnabled
            view.alpha = if (enabled) 1f else 0.4f
            setEnabledDeep(view, enabled)
        }
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view.isClickable || view.isFocusable)
            view.isFocusable = enabled
        if (view is android.view.ViewGroup)
            for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
    }

    /** Label on the left, the control on the right; stacked on narrow screens. */
    private fun row(title: CharSequence, control: View): LinearLayout {
        val wide = context.resources.configuration.screenWidthDp >= 600
        return LinearLayout(context).apply {
            setPadding(0, dp(4), 0, dp(4))
            val label = LauncherStyle.text(context, title, 14f, false, LauncherStyle.INK).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            if (wide) {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(dp(210), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
                addView(control, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            } else {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(control, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
        }
    }

    private fun toggle(preference: TwoStatePreference): View {
        val title = LauncherStyle.text(context, preference.title ?: "", 14f, false, LauncherStyle.INK).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val state = LauncherStyle.text(context, "", 10.5f, true).apply { letterSpacing = 0.08f }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(LauncherStyle.CONTROL_HEIGHT)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
            addView(state)
            setOnClickListener { preference.isChecked = !preference.isChecked }
        }
        updaters += {
            val on = preference.isChecked
            state.text = context.getString(if (on) R.string.launcher_on else R.string.launcher_off)
            state.setTextColor(if (on) LauncherStyle.GOLD else LauncherStyle.MUTED)
            view.background = if (on)
                LauncherStyle.background(context, LauncherStyle.ACTIVE, LauncherStyle.GOLD, pressed = 0xff5a4729.toInt())
            else
                LauncherStyle.background(context, LauncherStyle.SURFACE, LauncherStyle.BORDER)
        }
        bindEnabled(view, preference)
        return view
    }

    private fun optionRow(preference: ListPreference): View {
        val entries = preference.entries
        val values = preference.entryValues
        val grid = EqualGrid(context, maxColumns = entries.size, minCellWidthDp = 96)
        val buttons = entries.indices.map { index ->
            LauncherStyle.button(context, entries[index]) {
                val value = values[index].toString()
                if (preference.value != value)
                    preference.value = value
            }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f) }
        }
        buttons.forEach { grid.addView(it) }
        updaters += {
            buttons.forEachIndexed { index, button ->
                val selected = values[index].toString() == preference.value
                button.setTextColor(if (selected) LauncherStyle.GOLD else LauncherStyle.INK)
                button.background = if (selected)
                    LauncherStyle.background(context, LauncherStyle.ACTIVE, LauncherStyle.GOLD, pressed = 0xff5a4729.toInt())
                else
                    LauncherStyle.background(context, LauncherStyle.SURFACE, LauncherStyle.BORDER)
            }
        }
        val view = row(preference.title ?: "", grid)
        bindEnabled(view, preference)
        return view
    }

    private fun valueRow(preference: Preference): View {
        val button = LauncherStyle.button(context, "") { openValue(preference) }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        updaters += {
            val value = when (preference) {
                is ListPreference -> preference.entry
                is EditTextPreference -> preference.text
                else -> preference.summary
            }
            button.text = if (value.isNullOrBlank()) "…" else "$value  ›"
        }
        val view = row(preference.title ?: "", button)
        bindEnabled(view, preference)
        return view
    }

    private fun navigationTile(preference: Preference): View {
        val tile = LauncherStyle.tile(context, preference.title ?: "", preference.summary ?: "") { openValue(preference) }
        bindEnabled(tile, preference)
        return tile
    }

    /** What a tap on the preference did before: its own click handler, or a choice/text dialog. */
    private fun openValue(preference: Preference) {
        if (preference.onPreferenceClickListener?.onPreferenceClick(preference) == true)
            return
        when (preference) {
            is ListPreference -> AlertDialog.Builder(context)
                .setTitle(preference.dialogTitle ?: preference.title)
                .setSingleChoiceItems(preference.entries, preference.findIndexOfValue(preference.value)) { dialog, which ->
                    preference.value = preference.entryValues[which].toString()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            is EditTextPreference -> {
                val input = EditText(context).apply {
                    setText(preference.text)
                    setSingleLine(true)
                    setSelection(text.length)
                }
                val frame = FrameLayout(context).apply {
                    setPadding(dp(20), dp(8), dp(20), 0)
                    addView(input)
                }
                AlertDialog.Builder(context)
                    .setTitle(preference.dialogTitle ?: preference.title)
                    .setView(frame)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> preference.text = input.text.toString() }
                    .show()
            }
        }
    }

    private fun sliderRow(preference: Preference, spec: SliderSpec): View {
        val prefs = preference.sharedPreferences
        val key = preference.key
        val steps = ((spec.maximum - spec.minimum) / spec.step).roundToInt()
        val valueLabel = LauncherStyle.text(context, "", 14f, true, LauncherStyle.GOLD).apply {
            minWidth = dp(72)
            gravity = Gravity.END
        }
        fun valueFor(progress: Int) = (spec.minimum + progress * spec.step).coerceIn(spec.minimum, spec.maximum)
        fun format(value: Float) =
            if (spec.decimals == 0) value.roundToInt().toString()
            else String.format(Locale.ROOT, "%.${spec.decimals}f", value)

        val seekBar = SeekBar(context).apply {
            max = steps
            progressTintList = ColorStateList.valueOf(LauncherStyle.GOLD)
            thumbTintList = ColorStateList.valueOf(LauncherStyle.GOLD)
            progressBackgroundTintList = ColorStateList.valueOf(LauncherStyle.BORDER)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser)
                        valueLabel.text = format(valueFor(progress))
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) {
                    prefs.edit().putString(key, format(valueFor(bar.progress))).apply()
                }
            })
            // Controller left/right moves the slider; store at once there too.
            setOnKeyListener { bar, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
                    prefs.edit().putString(key, format(valueFor((bar as SeekBar).progress))).apply()
                false
            }
        }

        val control = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(LauncherStyle.CONTROL_HEIGHT)
            addView(seekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            if (spec.autoLabel != null) {
                addView(LauncherStyle.button(context, context.getString(R.string.slider_auto)) {
                    prefs.edit().putString(key, "").apply()
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            }
        }

        updaters += {
            val stored = prefs.getString(key, "").orEmpty()
            val value = stored.toFloatOrNull()?.coerceIn(spec.minimum, spec.maximum)
            val shown = value ?: spec.defaultValue.coerceIn(spec.minimum, spec.maximum)
            seekBar.progress = ((shown - spec.minimum) / spec.step).roundToInt().coerceIn(0, steps)
            valueLabel.text = if (value == null && spec.autoLabel != null) spec.autoLabel.invoke() else format(shown)
        }
        val view = row(preference.title ?: "", control)
        bindEnabled(view, preference)
        return view
    }
}
