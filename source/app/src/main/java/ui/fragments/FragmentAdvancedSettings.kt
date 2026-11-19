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

import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceFragment
import android.util.Log
import com.libopenmw.openmw.BuildConfig
import com.libopenmw.openmw.R
import constants.Constants
import file.UserDataLocation
import java.io.File

/**
 * Game settings > Advanced: the saves folder and the in-game cheats switch.
 */
class FragmentAdvancedSettings : PreferenceFragment(), OnSharedPreferenceChangeListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addPreferencesFromResource(R.xml.gs_advanced)
        preferenceScreen.sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        // A saves folder in shared storage lets sync tools such as Syncthing reach the
        // saves. Automotive targets API 35 with scoped storage, where the engine cannot
        // write to such folders, so it keeps the app-specific folder.
        val userDataPreference = findPreference(UserDataLocation.PREF_USER_DATA_DIR)
        if (BuildConfig.IS_AUTOMOTIVE_BUILD) {
            preferenceScreen.removePreference(userDataPreference)
        } else {
            userDataPreference?.setOnPreferenceClickListener {
                showUserDataDialog()
                true
            }
        }
        updateUserDataSummary()
    }

    override fun onResume() {
        super.onResume()
        updateUserDataSummary()
    }

    override fun onDestroy() {
        preferenceScreen.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == UserDataLocation.PREF_USER_DATA_DIR)
            updateUserDataSummary()
    }

    private fun updateUserDataSummary() {
        val preference = findPreference(UserDataLocation.PREF_USER_DATA_DIR) ?: return
        val path = preferenceScreen.sharedPreferences
            .getString(UserDataLocation.PREF_USER_DATA_DIR, "").orEmpty()
        preference.summary = if (path.isEmpty()) getString(R.string.user_data_dir_default_summary) else path
    }

    private fun showUserDataDialog() {
        val hostActivity = activity ?: return
        val options = arrayOf(
            getString(R.string.user_data_dir_choose),
            getString(R.string.user_data_dir_use_default)
        )
        AlertDialog.Builder(hostActivity)
            .setTitle(R.string.user_data_dir_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> FolderChooser.show(hostActivity, fragmentManager) { path ->
                        changeUserDataDir(File(path).absolutePath)
                    }
                    else -> changeUserDataDir("")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Switches the saves folder to [path] (empty for the default app folder). The
     * folder is checked for write access, and the saves from the current folder
     * are copied over without replacing any save already there.
     */
    private fun changeUserDataDir(path: String) {
        val hostActivity = activity ?: return
        val sharedPref = preferenceScreen.sharedPreferences
        val currentPath = sharedPref.getString(UserDataLocation.PREF_USER_DATA_DIR, "").orEmpty()
        if (path == currentPath)
            return

        val defaultDir = File(Constants.USER_FILE_STORAGE)
        val from = if (currentPath.isEmpty()) defaultDir else File(currentPath)
        val to = if (path.isEmpty()) defaultDir else File(path)

        val progress = AlertDialog.Builder(hostActivity)
            .setMessage(R.string.user_data_dir_copying)
            .setCancelable(false)
            .show()
        val mainHandler = Handler(Looper.getMainLooper())

        Thread({
            var error: String? = null
            var copied = 0
            try {
                UserDataLocation.ensureWritable(to)
                copied = UserDataLocation.copySaves(from, to)
                sharedPref.edit().putString(UserDataLocation.PREF_USER_DATA_DIR, path).commit()
            } catch (e: Exception) {
                Log.e(TAG, "Could not switch the saves folder to ${to.absolutePath}", e)
                error = e.message ?: e.javaClass.simpleName
            }

            mainHandler.post {
                progress.dismiss()
                if (!isAdded)
                    return@post
                if (error != null) {
                    showMessage(
                        getString(R.string.user_data_dir_error_title),
                        getString(R.string.user_data_dir_error_message, to.absolutePath, error)
                    )
                } else {
                    showMessage(
                        getString(R.string.user_data_dir_title),
                        getString(R.string.user_data_dir_changed_message, to.absolutePath, copied)
                    )
                }
            }
        }, "OpenMW-saves-folder").start()
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val TAG = "OpenMW-Storage"
    }
}
