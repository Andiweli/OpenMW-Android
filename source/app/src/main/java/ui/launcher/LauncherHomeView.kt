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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.libopenmw.openmw.R

/**
 * The launcher's home screen, laid out like the CubeEvil4 in-game menu: a header
 * with the main action, the installed version and the update button, the game
 * data card, and tiles that open the settings areas.
 */
class LauncherHomeView(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        fun play()
        fun chooseGameFolder()
        fun chooseLanguage()
        fun openGameSettings()
        fun openGraphics()
        fun openControls()
        fun openAudio()
        fun openMods()
        fun openHelp()
        fun openReleases()
    }

    enum class GameDataStatus { FOUND, MISSING, NOT_CHOSEN }

    private fun dp(value: Int) = LauncherStyle.dp(context, value)
    private fun s(id: Int) = context.getString(id)

    private val wide = context.resources.configuration.screenWidthDp >= 560

    val playButton: TextView = LauncherStyle.button(context, s(R.string.launcher_play), primary = true) { actions.play() }.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(26), 0, dp(26), 0)
    }
    private val updateButton: TextView = LauncherStyle.button(context, s(R.string.launcher_up_to_date)) { actions.openReleases() }.apply {
        visibility = View.GONE
    }
    private val versionLabel = LauncherStyle.text(context, "", 15f, false)

    private val badge = LauncherStyle.text(context, "", 12f, true).apply {
        letterSpacing = 0.08f
        setPadding(dp(7), dp(2), dp(7), dp(2))
    }
    private val gameDataDetail = LauncherStyle.text(context, "", 13f, false).apply {
        setPadding(0, dp(3), 0, 0)
    }
    private val languageButton: TextView = LauncherStyle.button(context, "") { actions.chooseLanguage() }

    init {
        orientation = VERTICAL
        setBackgroundColor(LauncherStyle.PANEL)

        // Gold line along the top, as on the CubeEvil4 menu window.
        addView(View(context).apply { setBackgroundColor(LauncherStyle.GOLD) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(2)))
        addView(header(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(separator(), LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(separator(), LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        addView(footer(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun separator() = View(context).apply { setBackgroundColor(LauncherStyle.BORDER) }

    private fun header(): View {
        val mark = TextView(context).apply {
            text = "OpenMW"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            letterSpacing = 0.06f
            setTextColor(LauncherStyle.INK)
            maxLines = 1
        }

        // Right-aligned, read from the right: Spielen, the update button, the installed version.
        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(versionLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
            })
            addView(updateButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
            addView(playButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        return LinearLayout(context).apply {
            setPadding(dp(22), dp(12), dp(22), dp(12))
            if (wide) {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(mark, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(buttons)
            } else {
                // Narrow windows: the buttons go below the name.
                orientation = VERTICAL
                addView(mark)
                addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                    gravity = Gravity.END
                })
            }
        }
    }

    private fun body(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(22), dp(2), dp(22), dp(14))

        addView(LauncherStyle.sectionLabel(context, s(R.string.launcher_section_game_data)))
        addView(gameDataCard())

        addView(LauncherStyle.sectionLabel(context, s(R.string.launcher_section_settings)))
        val tiles = EqualGrid(context, maxColumns = 3, minCellWidthDp = 220)
        tiles.addView(LauncherStyle.tile(context, s(R.string.pref_game_settings_title), s(R.string.launcher_tile_game_settings_summary)) { actions.openGameSettings() })
        tiles.addView(LauncherStyle.tile(context, s(R.string.launcher_tile_graphics), s(R.string.launcher_tile_graphics_summary)) { actions.openGraphics() })
        tiles.addView(LauncherStyle.tile(context, s(R.string.launcher_tile_controls), s(R.string.launcher_tile_controls_summary)) { actions.openControls() })
        tiles.addView(LauncherStyle.tile(context, s(R.string.launcher_tile_audio), s(R.string.launcher_tile_audio_summary)) { actions.openAudio() })
        tiles.addView(LauncherStyle.tile(context, s(R.string.launcher_tile_mods), s(R.string.launcher_tile_mods_summary)) { actions.openMods() })
        tiles.addView(LauncherStyle.tile(context, s(R.string.launcher_tile_help), s(R.string.launcher_tile_help_summary)) { actions.openHelp() })
        addView(tiles, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun gameDataCard(): View {
        val title = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LauncherStyle.text(context, s(R.string.launcher_game_data_title), 16f, true))
            addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title)
            addView(gameDataDetail)
        }
        val buttons = EqualGrid(context, maxColumns = 2, minCellWidthDp = 140).apply {
            addView(LauncherStyle.button(context, s(R.string.launcher_choose_folder)) { actions.chooseGameFolder() })
            addView(languageButton)
        }

        return LinearLayout(context).apply {
            background = LauncherStyle.shape(context, LauncherStyle.CARD, LauncherStyle.BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (wide) {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
                addView(buttons, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            } else {
                orientation = VERTICAL
                addView(info)
                addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            }
        }
    }

    private fun footer(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(8), dp(22), dp(10))
        addView(LauncherStyle.text(context, s(R.string.launcher_hint_navigate), 13f, false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(LauncherStyle.text(context, s(R.string.launcher_hint_play), 13f, false).apply { maxLines = 1 })
    }

    fun setVersion(version: String) {
        versionLabel.text = "v$version"
    }

    /** Green "Aktuell" (off) or red "Update" (opens the releases page); hidden while unknown. */
    fun setUpdateState(state: LauncherUpdate.State) {
        if (state == LauncherUpdate.State.UNKNOWN) {
            updateButton.visibility = View.GONE
            return
        }
        val older = state == LauncherUpdate.State.OLDER
        updateButton.text = s(if (older) R.string.launcher_update else R.string.launcher_up_to_date)
        updateButton.setTextColor(LauncherStyle.INK)
        updateButton.background = if (older)
            LauncherStyle.background(context, LauncherStyle.UPDATE_RED, LauncherStyle.UPDATE_RED_EDGE, pressed = LauncherStyle.UPDATE_RED)
        else
            LauncherStyle.shape(context, LauncherStyle.UPDATE_GREEN, LauncherStyle.UPDATE_GREEN_EDGE)
        updateButton.isEnabled = older
        updateButton.isClickable = older
        updateButton.isFocusable = older
        updateButton.visibility = View.VISIBLE
    }

    fun setGameData(status: GameDataStatus, detail: String) {
        when (status) {
            GameDataStatus.FOUND -> {
                badge.text = s(R.string.launcher_status_found).uppercase()
                badge.setTextColor(LauncherStyle.OK_TEXT)
                badge.background = LauncherStyle.shape(context, LauncherStyle.OK_FILL, LauncherStyle.OK_EDGE, radius = 4)
            }
            GameDataStatus.MISSING, GameDataStatus.NOT_CHOSEN -> {
                badge.text = s(if (status == GameDataStatus.MISSING) R.string.launcher_status_missing else R.string.launcher_status_not_chosen).uppercase()
                badge.setTextColor(LauncherStyle.WARN_TEXT)
                badge.background = LauncherStyle.shape(context, LauncherStyle.WARN_FILL, LauncherStyle.UPDATE_RED_EDGE, radius = 4)
            }
        }
        gameDataDetail.text = detail
    }

    fun setLanguage(name: String) {
        languageButton.text = context.getString(R.string.launcher_language, name)
    }
}
