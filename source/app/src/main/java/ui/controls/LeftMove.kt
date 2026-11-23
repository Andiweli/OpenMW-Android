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
 * How the left on-screen stick moves the player:
 * - [STICK]: centred where the finger lands inside the stick's field (the original behaviour),
 * - [FREE]: the finger can land anywhere in the left half of the screen,
 * - [FIXED]: the centre stays in the middle of the field, like a real stick.
 */
object LeftMove {
    const val PREF = "pref_left_move"
    const val STICK = "stick"
    const val FREE = "free"
    const val FIXED = "fixed"

    fun mode(prefs: SharedPreferences): String =
        when (val value = prefs.getString(PREF, STICK)) {
            FREE, FIXED -> value
            else -> STICK
        }
}
