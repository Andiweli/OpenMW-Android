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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserDataLocationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(root: File, relative: String, text: String): File {
        val file = File(root, relative)
        file.parentFile!!.mkdirs()
        file.writeText(text)
        return file
    }

    @Test
    fun cfgLineEscapesQuotesAndAmpersands() {
        assertEquals(
            "user-data=\"/storage/emulated/0/A&&B/&\"x&\"\"",
            UserDataLocation.cfgLine(File("/storage/emulated/0/A&B/\"x\""))
        )
    }

    @Test
    fun applyToReplacesTheBaseLine() {
        val lines = listOf("resources=x", "user-data=\"?userdata?\"", "data=y")
        val result = UserDataLocation.applyTo(lines, File("/storage/emulated/0/Documents/OpenMW"))
        assertEquals(
            listOf("resources=x", "user-data=\"/storage/emulated/0/Documents/OpenMW\"", "data=y"),
            result
        )
    }

    @Test
    fun applyToKeepsASingleLine() {
        val lines = listOf("user-data=\"a\"", "user-data=\"b\"")
        val result = UserDataLocation.applyTo(lines, File("/sync"))
        assertEquals(listOf("user-data=\"/sync\""), result)
    }

    @Test
    fun applyToAppendsWhenMissingAndIgnoresNull() {
        assertEquals(listOf("data=y", "user-data=\"/sync\""), UserDataLocation.applyTo(listOf("data=y"), File("/sync")))
        assertEquals(listOf("data=y"), UserDataLocation.applyTo(listOf("data=y"), null))
    }

    @Test
    fun copySavesNeverReplacesExistingFiles() {
        val from = temp.newFolder("app")
        val to = temp.newFolder("sync")
        write(from, "saves/Nerevar/1.omwsave", "phone")
        write(from, "saves/Nerevar/2.omwsave", "phone two")
        write(to, "saves/Nerevar/1.omwsave", "pc")

        val copied = UserDataLocation.copySaves(from, to)

        assertEquals(1, copied)
        assertEquals("pc", File(to, "saves/Nerevar/1.omwsave").readText())
        assertEquals("phone two", File(to, "saves/Nerevar/2.omwsave").readText())
        // The source stays untouched.
        assertEquals("phone", File(from, "saves/Nerevar/1.omwsave").readText())
        assertFalse(File(to, "saves/Nerevar/2.omwsave.copying").exists())
    }

    @Test
    fun copySavesWithoutSourceOrIntoItselfDoesNothing() {
        val from = temp.newFolder("empty")
        assertEquals(0, UserDataLocation.copySaves(from, temp.newFolder("target")))

        write(from, "saves/a.omwsave", "x")
        assertEquals(0, UserDataLocation.copySaves(from, from))
    }

    @Test
    fun ensureWritableCreatesTheFolder() {
        val dir = File(temp.root, "Documents/OpenMW")
        UserDataLocation.ensureWritable(dir)
        assertTrue(dir.isDirectory)
        assertFalse(File(dir, ".openmw-write-test").exists())
    }
}
