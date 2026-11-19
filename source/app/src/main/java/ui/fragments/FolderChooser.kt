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
import android.app.FragmentManager
import com.codekidlabs.storagechooser.Content
import com.codekidlabs.storagechooser.StorageChooser
import com.libopenmw.openmw.R
import permission.PermissionHelper

/** The launcher's directory chooser, shared by the game files and saves folder settings. */
object FolderChooser {

    /** Opens the directory chooser, after checking the storage permission. */
    fun show(activity: Activity, fragmentManager: FragmentManager, onSelect: (String) -> Unit) {
        if (!PermissionHelper.canOpenLegacyStorageChooser(activity)) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.permissions_error_title)
                .setMessage(R.string.permissions_error_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val chooserContent = Content().apply {
            selectLabel = activity.getString(R.string.chooser_select)
            createLabel = activity.getString(R.string.chooser_create)
            newFolderLabel = activity.getString(R.string.chooser_new_folder)
            cancelLabel = activity.getString(R.string.chooser_cancel)
            overviewHeading = activity.getString(R.string.chooser_heading)
            internalStorageText = activity.getString(R.string.chooser_internal_storage)
            freeSpaceText = activity.getString(R.string.chooser_free_space)
            folderCreatedToastText = activity.getString(R.string.chooser_folder_created)
            folderErrorToastText = activity.getString(R.string.chooser_folder_error)
            textfieldHintText = activity.getString(R.string.chooser_folder_name)
            textfieldErrorText = activity.getString(R.string.chooser_empty_folder_name)
        }

        val chooser = StorageChooser.Builder()
            .withActivity(activity)
            .withFragmentManager(fragmentManager)
            .withMemoryBar(true)
            .withContent(chooserContent)
            .allowCustomPath(true)
            .setType(StorageChooser.DIRECTORY_CHOOSER)
            .build()

        chooser.show()

        chooser.setOnSelectListener { path -> onSelect(path) }
    }
}
