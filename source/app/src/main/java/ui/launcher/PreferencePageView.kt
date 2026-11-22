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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceGroup
import android.preference.TwoStatePreference
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
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
 * - a category becomes a section label with a line,
 * - every setting becomes a cell in a two-column grid: its name small on top,
 *   its value large below,
 *   - a switch shows On/Off and is gold while on,
 *   - a choice list has ‹ and › fields to step through the choices, and dots
 *     for the position,
 *   - a [SliderSpec] key has the ‹ and › fields and a slider,
 *   - a value (folder, resolution, text) opens its dialog,
 * - the explanations of the cells follow the grid as short paragraphs,
 * - entries that open another screen become tiles, up to three side by side.
 *
 * On a controller the D-pad moves between cells, A changes or opens a cell, and
 * L1/R1 (Q/E on a keyboard) step a value back and forth.
 *
 * Call [refresh] after a preference changed; the controls update in place, so
 * the controller focus stays where it is.
 */
class PreferencePageView(
    context: Context,
    private val screen: PreferenceGroup,
    private val sliders: Map<String, SliderSpec> = emptyMap(),
    private val navigationKeys: Set<String> = emptySet(),
    /** An explanation for a value cell, by preference key. */
    private val notes: Map<String, CharSequence> = emptyMap()
) : FrameLayout(context) {

    /** An inline slider for a preference stored as a decimal string ("" = automatic). */
    class SliderSpec(
        val minimum: Float,
        val maximum: Float,
        val step: Float,
        val defaultValue: Float,
        /** Decimal places stored and shown; 0 stores whole numbers ("4", not "4.0"). */
        val decimals: Int = 1,
        /** When set, the first slider position stores "" and the value shows this text. */
        val autoLabel: (() -> String)? = null,
        /** Show the preference's summary (its explanation) below the grid. */
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
        val pendingCells = ArrayList<Pair<View, View?>>()
        val pendingTiles = ArrayList<Preference>()

        fun fullWidth() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        fun flushTiles() {
            if (pendingTiles.isEmpty())
                return
            val grid = EqualGrid(context, maxColumns = 3, minCellWidthDp = 220)
            pendingTiles.forEach { grid.addView(navigationTile(it)) }
            parent.addView(grid, fullWidth().apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
            pendingTiles.clear()
        }

        // Two cells side by side, as in CubeEvil4; their explanations below.
        fun flushCells() {
            if (pendingCells.isEmpty())
                return
            val grid = EqualGrid(context, maxColumns = 2, minCellWidthDp = 240)
            pendingCells.forEach { grid.addView(it.first) }
            parent.addView(grid, fullWidth().apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
            pendingCells.mapNotNull { it.second }.forEach { note ->
                parent.addView(note, fullWidth().apply { topMargin = dp(6) })
            }
            pendingCells.clear()
        }

        for (preference in children(group)) {
            when {
                preference is PreferenceCategory -> {
                    flushCells()
                    flushTiles()
                    parent.addView(LauncherStyle.sectionLabel(context, preference.title ?: ""))
                    buildGroup(parent, preference)
                }
                preference.key in navigationKeys -> {
                    flushCells()
                    pendingTiles += preference
                }
                else -> {
                    flushTiles()
                    pendingCells += when {
                        preference.key in sliders -> sliderCell(preference, sliders.getValue(preference.key))
                        preference is TwoStatePreference -> toggleCell(preference)
                        preference is ListPreference && (preference.entries?.size ?: 0) >= 2 -> choiceCell(preference)
                        else -> valueCell(preference)
                    }
                }
            }
        }
        flushCells()
        flushTiles()
    }

    /**
     * One grid cell: the setting's name small on top, its value large below, and
     * for steppable values a ‹ and a › field at the sides.
     */
    private inner class Cell(title: CharSequence, arrows: Boolean) {
        val titleView: TextView = LauncherStyle.text(context, title, 14f, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val valueView: TextView = LauncherStyle.text(context, "", 18f, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(valueView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        private val arrowViews = ArrayList<TextView>()
        private val dividers = ArrayList<View>()

        /** Called with -1 or +1 by the arrow fields and L1/R1. */
        var onStep: (Int) -> Unit = {}
        /** Called by a tap on the middle or the A button. */
        var onActivate: () -> Unit = {}
        /** The preference the cell belongs to; nothing changes while it is disabled. */
        var preference: Preference? = null

        val root: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(LauncherStyle.CONTROL_HEIGHT)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (enabled()) onActivate() }
            setOnKeyListener { _, keyCode, event ->
                val by = when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_Q -> -1
                    KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_E -> 1
                    else -> 0
                }
                if (by != 0 && event.action == KeyEvent.ACTION_DOWN && enabled())
                    onStep(by)
                by != 0
            }
        }

        init {
            if (arrows)
                addSide("‹", -1, atStart = true)
            root.addView(center, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (arrows)
                addSide("›", 1, atStart = false)
        }

        private fun enabled() = preference?.isEnabled != false

        private fun addSide(label: String, by: Int, atStart: Boolean) {
            val arrow = LauncherStyle.text(context, label, 22f, true).apply {
                gravity = Gravity.CENTER
                // Tappable, but the focus stays on the whole cell.
                isClickable = true
                isFocusable = false
                setOnClickListener { if (enabled()) onStep(by) }
            }
            val divider = View(context)
            arrowViews += arrow
            dividers += divider
            val arrowParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.MATCH_PARENT)
            val dividerParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
            if (atStart) {
                root.addView(arrow, arrowParams)
                root.addView(divider, dividerParams)
            } else {
                root.addView(divider, dividerParams)
                root.addView(arrow, arrowParams)
            }
        }

        /** Gold for a switch that is on or a choice away from its first entry. */
        fun setActive(active: Boolean) {
            root.background = if (active)
                LauncherStyle.background(context, LauncherStyle.ACTIVE, LauncherStyle.GOLD, pressed = 0xff5a4729.toInt())
            else
                LauncherStyle.background(context, LauncherStyle.SURFACE, LauncherStyle.BORDER)
            val ink = if (active) LauncherStyle.GOLD else LauncherStyle.INK
            titleView.setTextColor(ink)
            valueView.setTextColor(ink)
            arrowViews.forEach { it.setTextColor(ink) }
            dividers.forEach { it.setBackgroundColor(if (active) 0xff7a6440.toInt() else LauncherStyle.BORDER) }
        }
    }

    /** The paragraph below the grid: "Name: explanation". */
    private fun noteView(title: CharSequence, explanation: CharSequence): TextView {
        val text = SpannableStringBuilder().apply {
            append(title)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(LauncherStyle.INK), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(": ")
            append(explanation)
        }
        return LauncherStyle.text(context, text, 14f, false).apply {
            setLineSpacing(0f, 1.1f)
        }
    }

    private fun note(preference: Preference, explanation: CharSequence?): View? {
        if (explanation.isNullOrBlank())
            return null
        val view = noteView(preference.title ?: "", explanation)
        updaters += { view.alpha = if (preference.isEnabled) 1f else 0.4f }
        return view
    }

    private fun bindEnabled(cell: Cell, preference: Preference) {
        cell.preference = preference
        updaters += {
            val enabled = preference.isEnabled
            cell.root.alpha = if (enabled) 1f else 0.4f
            cell.root.isEnabled = enabled
            cell.root.isFocusable = enabled
        }
    }

    private fun toggleCell(preference: TwoStatePreference): Pair<View, View?> {
        val cell = Cell(preference.title ?: "", arrows = false)
        cell.onActivate = { preference.isChecked = !preference.isChecked }
        cell.onStep = { by -> preference.isChecked = by > 0 }
        updaters += {
            val on = preference.isChecked
            cell.valueView.text = context.getString(if (on) R.string.launcher_on else R.string.launcher_off)
            cell.setActive(on)
        }
        bindEnabled(cell, preference)
        return cell.root to note(preference, preference.summary)
    }

    private fun choiceCell(preference: ListPreference): Pair<View, View?> {
        val values = preference.entryValues
        val count = values.size
        val cell = Cell(preference.title ?: "", arrows = true)
        fun index() = preference.findIndexOfValue(preference.value).coerceAtLeast(0)
        fun step(by: Int) {
            preference.value = values[((index() + by) % count + count) % count].toString()
        }
        cell.onStep = { by -> step(by) }
        cell.onActivate = { step(1) }

        // Dots for the position, as in CubeEvil4; only for short lists.
        val dots = if (count in 2..8) LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
            repeat(count) {
                addView(View(context), LinearLayout.LayoutParams(dp(5), dp(5)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                })
            }
        } else null
        dots?.let { cell.center.addView(it) }

        updaters += {
            val current = index()
            cell.valueView.text = preference.entry ?: ""
            cell.setActive(current > 0)
            dots?.let {
                val on = if (current > 0) LauncherStyle.GOLD else LauncherStyle.INK
                for (i in 0 until it.childCount) {
                    it.getChildAt(i).background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (i == current) on else LauncherStyle.BORDER)
                    }
                }
            }
        }
        bindEnabled(cell, preference)
        return cell.root to note(preference, descriptiveSummary(preference))
    }

    /** A list summary that explains the setting instead of repeating the chosen entry. */
    private fun descriptiveSummary(preference: ListPreference): CharSequence? {
        val summary = preference.summary?.toString() ?: return null
        if (summary.isBlank() || summary == preference.entry?.toString())
            return null
        return summary
    }

    private fun valueCell(preference: Preference): Pair<View, View?> {
        val cell = Cell(preference.title ?: "", arrows = false)
        cell.valueView.ellipsize = TextUtils.TruncateAt.MIDDLE
        cell.onActivate = { openValue(preference) }
        updaters += {
            val value = when (preference) {
                is ListPreference -> preference.entry
                is EditTextPreference -> preference.text
                else -> preference.summary
            }
            cell.valueView.text = if (value.isNullOrBlank()) "…  ›" else "$value  ›"
            cell.setActive(false)
        }
        bindEnabled(cell, preference)
        return cell.root to note(preference, notes[preference.key])
    }

    private fun navigationTile(preference: Preference): View {
        val tile = LauncherStyle.tile(context, preference.title ?: "", preference.summary ?: "") { openValue(preference) }
        updaters += {
            val enabled = preference.isEnabled
            tile.alpha = if (enabled) 1f else 0.4f
            tile.isEnabled = enabled
            tile.isFocusable = enabled
        }
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

    private fun sliderCell(preference: Preference, spec: SliderSpec): Pair<View, View?> {
        val prefs = preference.sharedPreferences
        val key = preference.key
        // With an automatic value, the first slider position is "Auto".
        val autoOffset = if (spec.autoLabel != null) 1 else 0
        val steps = ((spec.maximum - spec.minimum) / spec.step).roundToInt() + autoOffset

        fun valueFor(position: Int) =
            (spec.minimum + (position - autoOffset) * spec.step).coerceIn(spec.minimum, spec.maximum)
        fun format(value: Float) =
            if (spec.decimals == 0) value.roundToInt().toString()
            else String.format(Locale.ROOT, "%.${spec.decimals}f", value)
        fun label(position: Int) =
            if (position < autoOffset) spec.autoLabel?.invoke().orEmpty() else format(valueFor(position))
        fun store(position: Int) =
            prefs.edit().putString(key, if (position < autoOffset) "" else format(valueFor(position))).apply()
        fun position(): Int {
            val value = prefs.getString(key, "").orEmpty().toFloatOrNull()
                ?: return if (autoOffset == 1) 0
                    else ((spec.defaultValue.coerceIn(spec.minimum, spec.maximum) - spec.minimum) / spec.step).roundToInt()
            return ((value.coerceIn(spec.minimum, spec.maximum) - spec.minimum) / spec.step).roundToInt() + autoOffset
        }

        val cell = Cell(preference.title ?: "", arrows = true)
        val seekBar = SeekBar(context).apply {
            max = steps
            // Dragged with a finger; the controller uses the cell's L1/R1 and arrows.
            isFocusable = false
            setPadding(dp(14), dp(4), dp(14), dp(2))
            progressTintList = ColorStateList.valueOf(LauncherStyle.GOLD)
            thumbTintList = ColorStateList.valueOf(LauncherStyle.GOLD)
            progressBackgroundTintList = ColorStateList.valueOf(LauncherStyle.BORDER)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser)
                        cell.valueView.text = label(progress)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = store(bar.progress)
            })
        }
        cell.center.addView(seekBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        cell.onStep = { by -> store((position() + by).coerceIn(0, steps)) }
        cell.onActivate = { store((position() + 1).coerceIn(0, steps)) }

        updaters += {
            val current = position().coerceIn(0, steps)
            seekBar.progress = current
            seekBar.isEnabled = preference.isEnabled
            cell.valueView.text = label(current)
            cell.setActive(false)
        }
        bindEnabled(cell, preference)
        return cell.root to note(preference, if (spec.summaryAsNote) preference.summary else null)
    }
}
