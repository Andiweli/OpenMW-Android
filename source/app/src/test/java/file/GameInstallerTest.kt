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

import constants.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var fallback: File

    @Before
    fun setUp() {
        fallback = File(temp.root, "config/openmw.fallback.cfg")
        Constants.OPENMW_FALLBACK_CFG = fallback.absolutePath
    }

    private fun gameFolder(name: String, iniName: String = "Morrowind.ini", dataName: String = "Data Files"): File {
        val root = File(temp.root, name)
        File(root, dataName).mkdirs()
        File(root, iniName).writeText("[Fonts]\nFont 0=magic_cards_regular\n")
        return root
    }

    @Test
    fun dataFilesPathIsAbsoluteAndKeepsRealCase() {
        val root = gameFolder("sd/Morrowind", iniName = "MORROWIND.INI", dataName = "data files")
        val inst = GameInstaller(root.absolutePath)

        assertTrue(inst.check())
        assertEquals(File(root, "data files").absolutePath, inst.findDataFiles())
        assertTrue(File(inst.findDataFiles()).isAbsolute)
    }

    @Test
    fun convertIniWritesFallbackConfig() {
        val inst = GameInstaller(gameFolder("valid").absolutePath)

        assertTrue(inst.convertIni("win1252"))
        assertTrue(fallback.readText().contains("fallback=Fonts_Font_0,magic_cards_regular"))
        assertFalse(File(fallback.parentFile, fallback.name + ".tmp").exists())
    }

    @Test
    fun failedConversionKeepsPreviousFallbackConfig() {
        fallback.parentFile!!.mkdirs()
        fallback.writeText("previous")
        val emptyFolder = File(temp.root, "empty").apply { mkdirs() }

        assertNull(GameInstaller(emptyFolder.absolutePath).convertIniToString("win1252"))
        assertFalse(GameInstaller(emptyFolder.absolutePath).convertIni("win1252"))
        assertEquals("previous", fallback.readText())
    }

    @Test
    fun convertIniToStringDoesNotWrite() {
        val inst = GameInstaller(gameFolder("dry").absolutePath)

        val output = inst.convertIniToString("win1252")

        assertTrue(output!!.isNotEmpty())
        assertFalse(fallback.exists())
    }

    @Test
    fun writeAtomicallyReplacesExistingFile() {
        val target = File(temp.root, "out/file.cfg")
        target.parentFile!!.mkdirs()
        target.writeText("old")

        assertTrue(GameInstaller.writeAtomically(target, "new"))
        assertEquals("new", target.readText())
    }

    @Test
    fun writeAtomicallyFailureKeepsExistingFile() {
        val target = File(temp.root, "blocked/file.cfg")
        target.parentFile!!.mkdirs()
        target.writeText("old")
        // A directory in place of the temporary file makes the write fail.
        File(target.parentFile, target.name + ".tmp").mkdirs()

        assertFalse(GameInstaller.writeAtomically(target, "new"))
        assertEquals("old", target.readText())
    }
}
