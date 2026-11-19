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

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The launcher's look, taken from the CubeEvil4 in-game menu and tinted with
 * OpenMW's browns: dark panels, parchment text, a muted gold for the selection
 * and the main action. Every button, toggle and option has the same height.
 */
object LauncherStyle {
    const val PANEL = 0xff1e1914.toInt()
    const val SURFACE = 0xff2a231b.toInt()
    const val SURFACE_PRESSED = 0xff332a20.toInt()
    const val CARD = 0xff241e17.toInt()
    const val BORDER = 0xff4a3e30.toInt()
    const val INK = 0xffefe6d4.toInt()
    const val MUTED = 0xffb0a28a.toInt()
    const val GOLD = 0xffc9a465.toInt()
    const val ACTIVE = 0xff4a3a22.toInt()
    const val FOCUS = 0xffe8c983.toInt()

    // The update button's colours, as in CubeEvil4.
    const val UPDATE_GREEN = 0xff2d5233.toInt()
    const val UPDATE_GREEN_EDGE = 0xff4f8a57.toInt()
    const val UPDATE_RED = 0xff7a2a26.toInt()
    const val UPDATE_RED_EDGE = 0xffb2463f.toInt()

    // Status badge on the game data card.
    const val OK_FILL = 0xff2e3a24.toInt()
    const val OK_EDGE = 0xff4f6340.toInt()
    const val OK_TEXT = 0xffb9cf9a.toInt()
    const val WARN_FILL = 0xff4e1f1c.toInt()
    const val WARN_TEXT = 0xffe2c7c2.toInt()

    /** Height of every button, toggle and option, in dp. */
    const val CONTROL_HEIGHT = 44
    /** Gap between controls and around rows, in dp. */
    const val GAP = 8
    const val BUTTON_RADIUS = 6

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun shape(context: Context, fill: Int, stroke: Int, radius: Int = BUTTON_RADIUS, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
            setStroke(dp(context, strokeWidth), stroke)
        }

    /** A background whose focused state shows a gold frame, for D-pad navigation. */
    fun background(context: Context, fill: Int, stroke: Int, pressed: Int = SURFACE_PRESSED): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(context, pressed, FOCUS))
            addState(intArrayOf(android.R.attr.state_focused), shape(context, fill, FOCUS, strokeWidth = 2))
            addState(intArrayOf(), shape(context, fill, stroke))
        }

    fun text(context: Context, label: CharSequence, sizeSp: Float, bold: Boolean, color: Int = if (bold) INK else MUTED): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** A section label in small capitals, as above each group in CubeEvil4. */
    fun sectionLabel(context: Context, label: CharSequence): TextView =
        text(context, label.toString().uppercase(), 11f, true, MUTED).apply {
            letterSpacing = 0.14f
            setPadding(0, dp(context, 14), 0, dp(context, 6))
        }

    /** A plain button of the common height. */
    fun button(context: Context, label: CharSequence, primary: Boolean = false, onClick: (View) -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(context, CONTROL_HEIGHT)
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            if (primary) {
                setTextColor(GOLD)
                background = background(context, ACTIVE, GOLD, pressed = 0xff5a4729.toInt())
            } else {
                setTextColor(INK)
                background = background(context, SURFACE, BORDER)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)
        }

    /**
     * The frame of a launcher sub-page, as in CubeEvil4: "‹ Back", the title and
     * "Play" on top, [content] in the middle, the controller hints below.
     */
    fun pageFrame(context: Context, title: CharSequence, onBack: () -> Unit, onPlay: () -> Unit, content: View): LinearLayout {
        fun d(v: Int) = dp(context, v)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(22), d(12), d(22), d(12))
            addView(button(context, context.getString(com.libopenmw.openmw.R.string.launcher_back)) { onBack() })
            addView(text(context, title, 22f, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = d(16)
                marginEnd = d(16)
            })
            addView(button(context, context.getString(com.libopenmw.openmw.R.string.launcher_play), primary = true) { onPlay() })
        }
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d(22), d(8), d(22), d(10))
            addView(text(context, context.getString(com.libopenmw.openmw.R.string.launcher_hint_navigate), 11.5f, false).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(context, context.getString(com.libopenmw.openmw.R.string.launcher_hint_back_play), 11.5f, false).apply { maxLines = 1 })
        }
        fun line(color: Int, height: Int) = View(context).apply { setBackgroundColor(color) } to
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(height))
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
            line(GOLD, 2).let { addView(it.first, it.second) }
            addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            line(BORDER, 1).let { addView(it.first, it.second) }
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            line(BORDER, 1).let { addView(it.first, it.second) }
            addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** A navigation tile: bold title, a short line below and a gold chevron. */
    fun tile(context: Context, title: CharSequence, summary: CharSequence, onClick: (View) -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            minimumHeight = dp(context, 60)
            background = background(context, SURFACE, BORDER)
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)

            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(context, title, 16f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            texts.addView(text(context, summary, 12f, false).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(context, 2), 0, 0)
            })
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(context, "›", 20f, true, GOLD).apply { setPadding(dp(context, 10), 0, 0, 0) })
        }
}
