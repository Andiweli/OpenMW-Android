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

package file

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameDataSearchTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun game(root: File, relative: String): File {
        val dir = File(root, relative)
        File(dir, "Data Files").mkdirs()
        File(dir, "Morrowind.ini").writeText("[General]\n")
        return dir
    }

    @Test
    fun findsGameFoldersOnAllRootsNearestFirst() {
        val phone = temp.newFolder("phone")
        val card = temp.newFolder("card")
        val deep = game(phone, "Games/PC/Morrowind")
        val top = game(card, "morrowind")
        File(phone, "Music/Album").mkdirs()

        assertEquals(listOf(deep, top), GameDataSearch.find(listOf(phone, card)))
    }

    @Test
    fun matchesNamesIgnoringCaseAndNeedsBothParts() {
        val root = temp.newFolder("root")
        val lower = File(root, "a")
        File(lower, "data files").mkdirs()
        File(lower, "morrowind.INI").writeText("")
        File(root, "onlyIni").mkdirs()
        File(root, "onlyIni/Morrowind.ini").writeText("")

        assertEquals(listOf(lower.absoluteFile), GameDataSearch.find(listOf(root)))
    }

    @Test
    fun skipsHiddenAndAppPrivateFoldersAndRespectsDepth() {
        val root = temp.newFolder("root")
        game(root, ".hidden/Morrowind")
        game(root, "Android/data/com.example/Morrowind")
        game(root, "a/b/c/d/e/f/Morrowind")
        val media = game(root, "Android/media/com.ast.openmw/Morrowind")

        assertEquals(listOf(media), GameDataSearch.find(listOf(root)))
    }
}
