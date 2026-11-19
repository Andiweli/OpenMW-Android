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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherUpdateTest {

    @Test
    fun sameReleaseIsCurrent() {
        assertEquals(0, LauncherUpdate.compare("0.51.0-15", "0.51.0-15"))
        assertEquals(0, LauncherUpdate.compare("0.51.0-15", "v0.51.0-15"))
    }

    @Test
    fun newerAndroidBuildIsAnUpdate() {
        assertTrue(LauncherUpdate.compare("0.51.0-15", "0.51.0-16") < 0)
        assertTrue(LauncherUpdate.compare("0.51.0-9", "0.51.0-10") < 0)
    }

    @Test
    fun newerEngineIsAnUpdate() {
        assertTrue(LauncherUpdate.compare("0.51.0-15", "0.52.0-1") < 0)
    }

    @Test
    fun installedNewerThanReleaseIsNotAnUpdate() {
        assertTrue(LauncherUpdate.compare("0.51.0-16", "0.51.0-15") > 0)
        assertTrue(LauncherUpdate.compare("0.51.1", "0.51.0-15") > 0)
    }
}
