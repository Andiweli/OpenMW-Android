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

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.codekidlabs.storagechooser.utils.StoragePathUtil
import com.codekidlabs.storagechooser.utils.StorageVolumeUtil
import com.libopenmw.openmw.R
import java.io.File

/**
 * Where the selected game folder lives and how to explain problems with it.
 *
 * Besides the folder path, the mount path and id of its storage volume are stored at
 * selection time. They tell a removed SD card ("storage not available") apart from a
 * deleted or renamed folder, without ever discarding the stored selection.
 */
object GameFilesLocation {
    const val PREF_GAME_FILES = "game_files"
    const val PREF_STORAGE_ROOT = "game_files_storage_root"
    const val PREF_STORAGE_ID = "game_files_storage_id"

    /**
     * @param storageRoot mount path of the volume as reported when the folder was selected
     * @return true if the storage holding [path] is mounted, false if it is known to be
     * unavailable, null if this cannot be determined
     */
    fun isStorageAvailable(path: File, storageRoot: String?): Boolean? {
        if (!storageRoot.isNullOrEmpty() && StoragePathUtil.isSameOrInside(path.path, storageRoot)) {
            val root = File(storageRoot)
            val state = Environment.getExternalStorageState(root)
            return when {
                StorageVolumeUtil.isReadableState(state) -> true
                StorageVolumeUtil.isUnavailableState(state) -> false
                // The mount point of a removed card disappears.
                !root.exists() -> false
                else -> null
            }
        }

        val state = Environment.getExternalStorageState(path)
        return when {
            StorageVolumeUtil.isReadableState(state) -> true
            StorageVolumeUtil.isUnavailableState(state) -> false
            else -> null
        }
    }

    fun validator(storageRoot: String?): GameDataValidator =
        GameDataValidator(PosixGameFileAccess) { isStorageAvailable(it, storageRoot) }

    /**
     * Cheap check (one stat call) of the configured folder, usable on the UI thread.
     *
     * @return why the configured folder is unusable, or null if it is present or unset
     */
    fun locateConfigured(prefs: SharedPreferences): GameDataCheck? {
        val path = prefs.getString(PREF_GAME_FILES, "").orEmpty()
        if (path.isEmpty())
            return null
        return validator(prefs.getString(PREF_STORAGE_ROOT, null)).locate(File(path))
    }

    /** Title and message explaining a failed [check] to the user. */
    fun describe(context: Context, check: GameDataCheck): Pair<String, String> {
        return when (check) {
            is GameDataCheck.StorageUnavailable -> Pair(
                context.getString(R.string.game_files_storage_unavailable_title),
                context.getString(R.string.game_files_storage_unavailable_message, check.gameDir.path)
            )
            is GameDataCheck.FolderNotFound -> Pair(
                context.getString(R.string.game_files_folder_not_found_title),
                context.getString(R.string.game_files_folder_not_found_message, check.gameDir.path)
            )
            is GameDataCheck.AccessDenied -> Pair(
                context.getString(R.string.game_files_access_denied_title),
                context.getString(R.string.game_files_access_denied_message, check.file.path)
            )
            is GameDataCheck.MissingGameFiles -> Pair(
                context.getString(R.string.data_error_title),
                context.getString(R.string.game_files_missing_message, check.missing.joinToString("\n"))
            )
            is GameDataCheck.ReadFailed -> Pair(
                context.getString(R.string.game_files_read_failed_title),
                context.getString(R.string.game_files_read_failed_message, check.file.path, check.reason)
            )
            is GameDataCheck.Valid -> Pair("", "")
        }
    }
}
