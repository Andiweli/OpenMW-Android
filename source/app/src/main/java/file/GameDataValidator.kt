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

import java.io.File
import java.io.IOException

/** Why a file or directory could not be accessed. */
enum class FileAccessFailure {
    NOT_FOUND,
    DENIED,
    NOT_DIRECTORY,
    IO_ERROR
}

class FileAccessException(
    val failure: FileAccessFailure,
    val file: File,
    message: String
) : IOException(message)

/**
 * Read-only file access used to validate game data. Implementations must report the
 * actual reason of a failure instead of collapsing everything into "does not exist".
 */
interface GameFileAccess {
    /** Lists the entry names of [dir]. */
    @Throws(FileAccessException::class)
    fun list(dir: File): List<String>

    /** Returns whether [file] is a directory. Throws if it cannot be examined. */
    @Throws(FileAccessException::class)
    fun isDirectory(file: File): Boolean

    /**
     * Opens [file] read-only, reads up to [count] bytes from its start and seeks to its
     * last byte, like the engine does for archives. Returns the bytes read from the start.
     */
    @Throws(FileAccessException::class)
    fun readHead(file: File, count: Int): ByteArray
}

/** Result of validating a selected game folder. */
sealed class GameDataCheck {
    data class Valid(
        val gameDir: File,
        val iniFile: File,
        val dataFiles: File,
        val masterFile: File,
        val archiveFile: File?
    ) : GameDataCheck()

    /** The storage holding the folder is not mounted, e.g. the SD card was removed. */
    data class StorageUnavailable(val gameDir: File) : GameDataCheck()

    /** The folder does not exist although its storage is (or may be) available. */
    data class FolderNotFound(val gameDir: File) : GameDataCheck()

    /** The file or folder exists but this app is not allowed to read it. */
    data class AccessDenied(val file: File) : GameDataCheck()

    /** The folder is readable but required game files are missing. */
    data class MissingGameFiles(val gameDir: File, val missing: List<String>) : GameDataCheck()

    data class ReadFailed(val file: File, val reason: String) : GameDataCheck()
}

/**
 * Checks that a selected folder is a usable Morrowind installation by actually listing
 * and opening the files OpenMW needs. File.exists()/canRead() are not sufficient on
 * Android, where a detected path does not imply read permission.
 *
 * @param storageAvailable false if the storage holding a path is known to be unavailable,
 * true if it is mounted, null if this cannot be determined
 */
class GameDataValidator(
    private val access: GameFileAccess,
    private val storageAvailable: (File) -> Boolean?
) {

    /** Full validation. Lists two directories and opens three files; not for the UI thread. */
    fun validate(gameDir: File): GameDataCheck {
        val rootNames = try {
            access.list(gameDir)
        } catch (e: FileAccessException) {
            return failure(e, gameDir)
        }

        val iniName = rootNames.findIgnoringCase(GameInstaller.INI_NAME)
        val dataName = rootNames.findIgnoringCase(GameInstaller.DATA_NAME)
        val iniFile = iniName?.let { File(gameDir, it) }
        val dataFiles = dataName?.let { File(gameDir, it) }

        val missing = mutableListOf<String>()
        try {
            if (iniFile == null || access.isDirectory(iniFile))
                missing += GameInstaller.INI_NAME
            if (dataFiles == null || !access.isDirectory(dataFiles))
                missing += GameInstaller.DATA_NAME
        } catch (e: FileAccessException) {
            return failure(e, gameDir)
        }
        if (missing.isNotEmpty() || iniFile == null || dataFiles == null)
            return GameDataCheck.MissingGameFiles(gameDir, missing)

        try {
            access.readHead(iniFile, 1)

            val dataNames = access.list(dataFiles)
            val masterName = dataNames.findIgnoringCase(MASTER_NAME)
                ?: dataNames.firstOrNull { it.hasExtension(".esm") || it.hasExtension(".omwgame") }
                ?: return GameDataCheck.MissingGameFiles(gameDir, listOf("${GameInstaller.DATA_NAME}/$MASTER_NAME"))
            val masterFile = File(dataFiles, masterName)
            if (access.readHead(masterFile, HEADER_SIZE).size < HEADER_SIZE)
                return GameDataCheck.ReadFailed(masterFile, "file is empty or incomplete")

            // Loose-file installations may have no archives; probe one if present.
            val archiveName = dataNames.findIgnoringCase(ARCHIVE_NAME)
                ?: dataNames.firstOrNull { it.hasExtension(".bsa") }
            val archiveFile = archiveName?.let { File(dataFiles, it) }
            if (archiveFile != null && access.readHead(archiveFile, HEADER_SIZE).size < HEADER_SIZE)
                return GameDataCheck.ReadFailed(archiveFile, "file is empty or incomplete")

            return GameDataCheck.Valid(gameDir, iniFile, dataFiles, masterFile, archiveFile)
        } catch (e: FileAccessException) {
            return failure(e, gameDir)
        }
    }

    /**
     * Cheap check of the folder itself (one stat call and a listing of only this folder,
     * not of Data Files), suitable for explaining on the UI thread why a previously
     * selected folder cannot be used right now.
     *
     * @return null if the folder is present and can be listed
     */
    fun locate(gameDir: File): GameDataCheck? {
        return try {
            if (!access.isDirectory(gameDir))
                return GameDataCheck.FolderNotFound(gameDir)
            access.list(gameDir)
            null
        } catch (e: FileAccessException) {
            failure(e, gameDir)
        }
    }

    private fun failure(e: FileAccessException, gameDir: File): GameDataCheck {
        return when (e.failure) {
            FileAccessFailure.DENIED -> GameDataCheck.AccessDenied(e.file)
            FileAccessFailure.NOT_FOUND ->
                if (e.file == gameDir) {
                    if (storageAvailable(gameDir) == false)
                        GameDataCheck.StorageUnavailable(gameDir)
                    else
                        GameDataCheck.FolderNotFound(gameDir)
                } else {
                    GameDataCheck.ReadFailed(e.file, "file disappeared during the check")
                }
            FileAccessFailure.NOT_DIRECTORY ->
                if (e.file == gameDir) GameDataCheck.FolderNotFound(gameDir)
                else GameDataCheck.ReadFailed(e.file, "not a directory")
            FileAccessFailure.IO_ERROR ->
                if (e.file == gameDir && storageAvailable(gameDir) == false)
                    GameDataCheck.StorageUnavailable(gameDir)
                else
                    GameDataCheck.ReadFailed(e.file, e.message ?: "I/O error")
        }
    }

    companion object {
        const val MASTER_NAME = "Morrowind.esm"
        const val ARCHIVE_NAME = "Morrowind.bsa"
        private const val HEADER_SIZE = 4

        private fun List<String>.findIgnoringCase(name: String): String? =
            firstOrNull { it.equals(name, ignoreCase = true) }

        private fun String.hasExtension(extension: String): Boolean =
            endsWith(extension, ignoreCase = true)
    }
}
