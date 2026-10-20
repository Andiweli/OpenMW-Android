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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LegacyStorageMigratorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var legacy: File
    private lateinit var target: File
    private var copiedCalls = 0

    @Before
    fun setUp() {
        legacy = File(temp.root, "omw")
        target = File(temp.root, "Android/data/com.ast.openmw/files/OpenMW")
        write(legacy, "saves/Nerevar/Quicksave.omwsave", "old quicksave")
        write(legacy, "saves/Nerevar/Save 1.omwsave", "save one")
        write(legacy, "config/settings.cfg", "[Video]\nresolution x = 1920\n")
        copiedCalls = 0
    }

    private fun write(root: File, relative: String, text: String): File {
        val file = File(root, relative)
        file.parentFile!!.mkdirs()
        file.writeText(text)
        return file
    }

    private fun read(root: File, relative: String): String = File(root, relative).readText()

    private fun migrate(freeBytes: Long = Long.MAX_VALUE): LegacyStorageMigrator.Result =
        LegacyStorageMigrator { freeBytes }.migrate(legacy, target) { copiedCalls++ }

    @Test
    fun renamesWhenTargetDoesNotExist() {
        val result = migrate()

        assertEquals(LegacyStorageMigrator.Result.Migrated(null), result)
        assertFalse(legacy.exists())
        assertEquals("old quicksave", read(target, "saves/Nerevar/Quicksave.omwsave"))
        assertEquals(1, copiedCalls)
    }

    @Test
    fun renamesWhenTargetIsAnEmptyDirectory() {
        target.mkdirs()

        val result = migrate()

        assertEquals(LegacyStorageMigrator.Result.Migrated(null), result)
        assertFalse(legacy.exists())
        assertEquals("save one", read(target, "saves/Nerevar/Save 1.omwsave"))
    }

    @Test
    fun neverReplacesNewerFilesInTarget() {
        write(target, "saves/Nerevar/Quicksave.omwsave", "new quicksave")
        write(target, "config/settings.cfg", "[Video]\nresolution x = 1280\n")

        val result = migrate()

        val conflicts = File(target, LegacyStorageMigrator.CONFLICT_DIRECTORY)
        assertEquals(LegacyStorageMigrator.Result.Migrated(conflicts), result)
        assertEquals("new quicksave", read(target, "saves/Nerevar/Quicksave.omwsave"))
        assertEquals("[Video]\nresolution x = 1280\n", read(target, "config/settings.cfg"))
        // The differing legacy versions are kept, not dropped.
        assertEquals("old quicksave", read(conflicts, "saves/Nerevar/Quicksave.omwsave"))
        assertEquals("[Video]\nresolution x = 1920\n", read(conflicts, "config/settings.cfg"))
        // Files only present in the legacy tree are copied normally.
        assertEquals("save one", read(target, "saves/Nerevar/Save 1.omwsave"))
        assertFalse(legacy.exists())
        assertEquals(1, copiedCalls)
    }

    @Test
    fun identicalFilesAreNotReportedAsConflicts() {
        // State after an earlier attempt that copied some files and was interrupted,
        // including its leftover temporary file.
        write(target, "saves/Nerevar/Quicksave.omwsave", "old quicksave")
        write(target, "saves/Nerevar/Save 1.omwsave.migrating", "sav")

        val result = migrate()

        assertEquals(LegacyStorageMigrator.Result.Migrated(null), result)
        assertEquals("save one", read(target, "saves/Nerevar/Save 1.omwsave"))
        assertFalse(File(target, LegacyStorageMigrator.CONFLICT_DIRECTORY).exists())
        assertFalse(legacy.exists())
    }

    @Test
    fun keepsEverythingWhenSpaceIsInsufficient() {
        write(target, "config/openmw.cfg", "# user config\n")

        val result = migrate(freeBytes = 1024)

        assertTrue(result is LegacyStorageMigrator.Result.NotEnoughSpace)
        assertTrue((result as LegacyStorageMigrator.Result.NotEnoughSpace).requiredBytes > 0)
        assertEquals("old quicksave", read(legacy, "saves/Nerevar/Quicksave.omwsave"))
        assertFalse(File(target, "saves").exists())
        assertEquals(0, copiedCalls)
    }

    @Test
    fun keepsLegacyTreeWhenCopyFails() {
        // A file where the legacy tree has a directory makes the copy fail.
        write(target, "saves", "not a directory")

        try {
            migrate()
            fail("Expected an IOException")
        } catch (_: IOException) {
        }

        assertEquals(0, copiedCalls)
        assertEquals("old quicksave", read(legacy, "saves/Nerevar/Quicksave.omwsave"))
        assertEquals("save one", read(legacy, "saves/Nerevar/Save 1.omwsave"))
        assertEquals("not a directory", read(target, "saves"))
    }

    @Test
    fun retryAfterFailureCompletesWithoutReplacingNewerFiles() {
        write(target, "saves", "not a directory")
        try {
            migrate()
            fail("Expected an IOException")
        } catch (_: IOException) {
        }

        // The user fixes the problem and keeps playing before the retry.
        File(target, "saves").delete()
        write(target, "saves/Nerevar/Quicksave.omwsave", "newest quicksave")

        val result = migrate()

        assertTrue(result is LegacyStorageMigrator.Result.Migrated)
        assertEquals("newest quicksave", read(target, "saves/Nerevar/Quicksave.omwsave"))
        assertEquals("save one", read(target, "saves/Nerevar/Save 1.omwsave"))
        assertFalse(legacy.exists())
        assertEquals(1, copiedCalls)
    }

    @Test
    fun conflictDirectoryIsNullWithoutConflicts() {
        write(target, "config/openmw.cfg", "# user config\n")

        val result = migrate() as LegacyStorageMigrator.Result.Migrated

        assertNull(result.conflictDirectory)
        assertEquals("# user config\n", read(target, "config/openmw.cfg"))
        assertEquals("[Video]\nresolution x = 1920\n", read(target, "config/settings.cfg"))
    }
}
