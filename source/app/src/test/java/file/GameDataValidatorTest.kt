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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** [GameFileAccess] on top of java.io for JVM tests. */
private open class JvmFileAccess : GameFileAccess {
    override fun list(dir: File): List<String> {
        if (!dir.exists())
            throw FileAccessException(FileAccessFailure.NOT_FOUND, dir, "not found")
        if (!dir.isDirectory)
            throw FileAccessException(FileAccessFailure.NOT_DIRECTORY, dir, "not a directory")
        return dir.list()?.toList()
            ?: throw FileAccessException(FileAccessFailure.IO_ERROR, dir, "list failed")
    }

    override fun isDirectory(file: File): Boolean {
        if (!file.exists())
            throw FileAccessException(FileAccessFailure.NOT_FOUND, file, "not found")
        return file.isDirectory
    }

    override fun readHead(file: File, count: Int): ByteArray {
        if (!file.exists())
            throw FileAccessException(FileAccessFailure.NOT_FOUND, file, "not found")
        try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(count)
                val read = raf.read(buffer).coerceAtLeast(0)
                if (raf.length() > count) {
                    raf.seek(raf.length() - 1)
                    raf.read()
                }
                return buffer.copyOf(read)
            }
        } catch (e: IOException) {
            throw FileAccessException(FileAccessFailure.IO_ERROR, file, e.message ?: "io")
        }
    }
}

/** Simulates EACCES for selected paths; the tests run as a user that may read everything. */
private class DenyingFileAccess(private val denied: Set<File>) : JvmFileAccess() {
    private fun check(file: File) {
        if (file in denied)
            throw FileAccessException(FileAccessFailure.DENIED, file, "Permission denied")
    }

    override fun list(dir: File): List<String> {
        check(dir)
        return super.list(dir)
    }

    override fun readHead(file: File, count: Int): ByteArray {
        check(file)
        return super.readHead(file, count)
    }
}

class GameDataValidatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun install(
        root: File,
        iniName: String = "Morrowind.ini",
        dataName: String = "Data Files",
        masterName: String? = "Morrowind.esm",
        archiveName: String? = "Morrowind.bsa"
    ): File {
        root.mkdirs()
        File(root, iniName).writeText("[Game Files]\nGameFile0=Morrowind.esm\n")
        val data = File(root, dataName)
        data.mkdirs()
        if (masterName != null)
            File(data, masterName).writeBytes("TES3".toByteArray() + ByteArray(64))
        if (archiveName != null)
            File(data, archiveName).writeBytes(byteArrayOf(0, 1, 0, 0) + ByteArray(64))
        return root
    }

    private fun validator(access: GameFileAccess = JvmFileAccess(), available: Boolean? = true) =
        GameDataValidator(access) { available }

    @Test
    fun validInstallationIsAccepted() {
        val root = install(File(temp.root, "Morrowind"))

        val result = validator().validate(root)

        assertTrue(result is GameDataCheck.Valid)
        result as GameDataCheck.Valid
        assertEquals(File(root, "Morrowind.ini"), result.iniFile)
        assertEquals(File(root, "Data Files"), result.dataFiles)
        assertEquals(File(root, "Data Files/Morrowind.esm"), result.masterFile)
        assertEquals(File(root, "Data Files/Morrowind.bsa"), result.archiveFile)
    }

    @Test
    fun namesAreMatchedCaseInsensitivelyAndKeepTheirRealCase() {
        val root = install(
            File(temp.root, "game"),
            iniName = "morrowind.INI",
            dataName = "data files",
            masterName = "MORROWIND.ESM",
            archiveName = "morrowind.bsa"
        )

        val result = validator().validate(root) as GameDataCheck.Valid

        assertEquals(File(root, "morrowind.INI"), result.iniFile)
        assertEquals(File(root, "data files"), result.dataFiles)
        assertEquals(File(root, "data files/MORROWIND.ESM"), result.masterFile)
    }

    @Test
    fun loosefileInstallationWithoutArchiveIsAccepted() {
        val root = install(File(temp.root, "loose"), archiveName = null)

        val result = validator().validate(root) as GameDataCheck.Valid

        assertNull(result.archiveFile)
    }

    @Test
    fun missingIniAndDataFilesAreReported() {
        val root = File(temp.root, "empty").apply { mkdirs() }

        val result = validator().validate(root)

        assertEquals(
            GameDataCheck.MissingGameFiles(root, listOf("Morrowind.ini", "Data Files")),
            result
        )
    }

    @Test
    fun dataFilesWithoutMasterIsReported() {
        val root = install(File(temp.root, "nomaster"), masterName = null)

        val result = validator().validate(root)

        assertTrue(result is GameDataCheck.MissingGameFiles)
    }

    @Test
    fun otherMasterFileIsAcceptedWhenMorrowindEsmIsMissing() {
        val root = install(File(temp.root, "tc"), masterName = "Other.esm")

        val result = validator().validate(root) as GameDataCheck.Valid

        assertEquals("Other.esm", result.masterFile.name)
    }

    @Test
    fun emptyMasterFileIsAReadFailure() {
        val root = install(File(temp.root, "broken"))
        File(root, "Data Files/Morrowind.esm").writeBytes(ByteArray(0))

        val result = validator().validate(root)

        assertTrue(result is GameDataCheck.ReadFailed)
    }

    @Test
    fun missingFolderOnUnavailableStorageIsStorageUnavailable() {
        val root = File(temp.root, "removed-card/Morrowind")

        assertEquals(GameDataCheck.StorageUnavailable(root), validator(available = false).validate(root))
        assertEquals(GameDataCheck.StorageUnavailable(root), validator(available = false).locate(root))
    }

    @Test
    fun missingFolderOnMountedOrUnknownStorageIsFolderNotFound() {
        val root = File(temp.root, "deleted/Morrowind")

        assertEquals(GameDataCheck.FolderNotFound(root), validator(available = true).validate(root))
        assertEquals(GameDataCheck.FolderNotFound(root), validator(available = null).validate(root))
    }

    @Test
    fun deniedFolderIsAccessDenied() {
        val root = install(File(temp.root, "denied"))

        val result = validator(DenyingFileAccess(setOf(root))).validate(root)

        assertEquals(GameDataCheck.AccessDenied(root), result)
    }

    @Test
    fun deniedArchiveIsAccessDenied() {
        val root = install(File(temp.root, "deniedbsa"))
        val archive = File(root, "Data Files/Morrowind.bsa")

        val result = validator(DenyingFileAccess(setOf(archive))).validate(root)

        assertEquals(GameDataCheck.AccessDenied(archive), result)
    }

    @Test
    fun locateAcceptsPresentFolder() {
        val root = install(File(temp.root, "present"))

        assertNull(validator().locate(root))
    }

    @Test
    fun locateReportsRevokedAccess() {
        val root = install(File(temp.root, "revoked"))

        assertEquals(GameDataCheck.AccessDenied(root), validator(DenyingFileAccess(setOf(root))).locate(root))
    }
}
