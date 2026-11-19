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

package ui.activity

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Installs the "Cheats" Lua content folder: an in-game Settings page (Cheats > Gold)
 * with a "Give gold" toggle, enabled in the launcher under Game settings > Advanced.
 *
 * Like the Handheld mode scripts, the content file is always listed in openmw.cfg and
 * left empty while cheats are off, so toggling them does not change the content list
 * that saves remember.
 */
object CheatsController {
    private const val TAG = "CheatsController"
    const val CONTENT_FILE = "cheats.omwscripts"
    const val PREF_ENABLED = "pref_cheats_enabled"
    private const val ASSET_ROOT = "cheats"
    // PLAYER only: I.Settings.registerGroup() errors if the same key is registered twice, and
    // the MENU and PLAYER instances of a MENU,PLAYER script both run and both call it, which
    // duplicated the "Cheats" page in Settings. Nothing here is needed before a save is loaded.
    private const val SCRIPTS = """PLAYER: scripts/cheats/settings.lua
PLAYER: scripts/cheats/player.lua
GLOBAL: scripts/cheats/global.lua
"""

    fun install(context: Context, enabled: Boolean): File? {
        val root = File(context.filesDir, "cheats")
        return try {
            root.deleteRecursively()
            if (enabled) {
                copyAssets(context, ASSET_ROOT, root)
            } else if (!root.mkdirs()) {
                throw IOException("Could not create ${root.absolutePath}")
            }
            File(root, CONTENT_FILE).writeText(if (enabled) SCRIPTS else "")
            root
        } catch (e: IOException) {
            Log.e(TAG, "Could not install the cheats scripts", e)
            null
        }
    }

    private fun copyAssets(context: Context, path: String, target: File) {
        val children = context.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            return
        }
        if (!target.isDirectory && !target.mkdirs()) {
            throw IOException("Could not create ${target.absolutePath}")
        }
        for (child in children) {
            copyAssets(context, "$path/$child", File(target, child))
        }
    }
}
