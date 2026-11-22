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

package ui.controls

import android.content.SharedPreferences

/**
 * How the player looks around on the touch screen: with the right on-screen
 * stick, or by swiping on a free part of the screen (the stick is then hidden).
 */
object RightLook {
    const val PREF = "pref_right_look"
    const val STICK = "stick"
    const val SWIPE = "swipe"

    /** The switch of earlier versions, where swiping came on top of the stick. */
    private const val OLD_SWIPE_PREF = "pref_swipe_to_look"

    fun isSwipe(prefs: SharedPreferences): Boolean =
        (prefs.getString(PREF, null) ?: if (prefs.getBoolean(OLD_SWIPE_PREF, false)) SWIPE else STICK) == SWIPE

    /** Takes the earlier switch over once, so the launcher shows the same choice. */
    fun migrate(prefs: SharedPreferences) {
        if (!prefs.contains(PREF) && prefs.contains(OLD_SWIPE_PREF))
            prefs.edit().putString(PREF, if (prefs.getBoolean(OLD_SWIPE_PREF, false)) SWIPE else STICK).apply()
    }
}
