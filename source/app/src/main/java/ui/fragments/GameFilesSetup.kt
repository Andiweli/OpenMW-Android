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

package ui.fragments

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import com.codekidlabs.storagechooser.utils.StorageVolumeUtil
import com.libopenmw.openmw.BuildConfig
import com.libopenmw.openmw.R
import file.GameDataCheck
import file.GameFilesLocation
import file.GameInstaller
import java.io.File

/**
 * Choosing and checking the Morrowind game folder, used by the launcher's home
 * screen. [onChanged] runs on the main thread after a folder was accepted.
 */
class GameFilesSetup(private val activity: Activity, private val onChanged: () -> Unit) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)

    /** Opens the folder chooser and checks the chosen folder. */
    fun choose() {
        FolderChooser.show(activity, activity.fragmentManager) { path -> setup(path) }
    }

    /**
     * On AAOS, prefers the app-owned shared-media directory. This remains a normal
     * filesystem path, so the native OpenMW core does not need SAF/VFS changes and
     * the game data is not copied into Android/data.
     */
    fun autoSelectAutomotiveIfAvailable() {
        if (!BuildConfig.IS_AUTOMOTIVE_BUILD)
            return

        val currentPath = prefs.getString(GameFilesLocation.PREF_GAME_FILES, "") ?: ""
        if (currentPath.isNotEmpty() && GameInstaller(currentPath).check())
            return

        for (mediaRoot in activity.externalMediaDirs) {
            if (mediaRoot == null)
                continue
            val candidate = File(mediaRoot, "Morrowind")
            if (GameInstaller(candidate.absolutePath).check()) {
                setup(candidate.absolutePath, showProgress = false)
                return
            }
        }
    }

    /**
     * Checks the specified path for a valid morrowind installation by actually reading
     * the required files, generates config files and only then saves the path.
     *
     * The check runs off the UI thread. An invalid or unreadable selection never changes
     * the stored game folder or its openmw.fallback.cfg; the reason is shown instead.
     */
    fun setup(path: String, showProgress: Boolean = true) {
        val encoding = prefs.getString("pref_encoding", GameInstaller.DEFAULT_CHARSET_PREF)!!
        val hadSelection = prefs.getString(GameFilesLocation.PREF_GAME_FILES, "").orEmpty().isNotEmpty()
        val gameDir = File(path).absoluteFile

        // Remember which volume holds the folder, so that a removed SD card can later be
        // reported as such instead of as a missing folder.
        val storage = StorageVolumeUtil.findStorageContaining(activity, gameDir.path)
        val storageRoot = storage?.storagePath
        val storageId = storage?.storageId

        val progress = if (showProgress) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.game_files_checking)
                .setCancelable(false)
                .show()
        } else {
            null
        }
        val mainHandler = Handler(Looper.getMainLooper())

        Thread({
            val check = GameFilesLocation.validator(storageRoot).validate(gameDir)

            var configReady = false
            if (check is GameDataCheck.Valid) {
                val inst = GameInstaller(gameDir.path)
                val output = inst.convertIniToString(encoding)
                configReady = output != null && GameInstaller.writeFallbackConfig(output)

                if (configReady) {
                    inst.setNomedia()
                    prefs.edit()
                        .putString(GameFilesLocation.PREF_GAME_FILES, gameDir.path)
                        .putString(GameFilesLocation.PREF_STORAGE_ROOT, storageRoot)
                        .putString(GameFilesLocation.PREF_STORAGE_ID, storageId)
                        .commit()
                }
            }

            Log.i(TAG, "Game folder check: path=${gameDir.path}; storage=$storageId at $storageRoot; " +
                "result=$check; configReady=$configReady")

            mainHandler.post {
                progress?.dismiss()
                if (activity.isFinishing || activity.isDestroyed)
                    return@post

                val unchanged = if (hadSelection) "\n\n" + activity.getString(R.string.game_files_selection_unchanged) else ""
                when {
                    check is GameDataCheck.Valid && configReady -> onChanged()
                    check is GameDataCheck.Valid ->
                        showMessage(activity.getString(R.string.data_error_title),
                            activity.getString(R.string.ini_error_message) + unchanged)
                    else -> {
                        val (title, message) = GameFilesLocation.describe(activity, check)
                        val url = if (check is GameDataCheck.MissingGameFiles) "https://omw.xyz.is/game.html" else null
                        showMessage(title, message + unchanged, url)
                    }
                }
            }
        }, "OpenMW-game-files-check").start()
    }

    private fun showMessage(title: String, message: String, url: String? = null) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)

        if (url != null) {
            dialog.setNeutralButton(R.string.dialog_howto) { _, _ -> openUrl(activity, url) }
        }

        dialog.show()
    }

    companion object {
        private const val TAG = "OpenMW-Storage"

        fun openUrl(activity: Activity, url: String) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.no_browser_title)
                    .setMessage(activity.getString(R.string.no_browser_message, url))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }
}
