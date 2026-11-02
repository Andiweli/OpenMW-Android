package ui.activity

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Installs the "Cheats" Lua content folder: an in-game Settings page (Cheats > Gold)
 * with a "Give gold" toggle. Always present; nothing happens until the player uses it.
 */
object CheatsController {
    private const val TAG = "CheatsController"
    const val CONTENT_FILE = "cheats.omwscripts"
    private const val ASSET_ROOT = "cheats"
    // PLAYER only: I.Settings.registerGroup() errors if the same key is registered twice, and
    // the MENU and PLAYER instances of a MENU,PLAYER script both run and both call it, which
    // duplicated the "Cheats" page in Settings. Nothing here is needed before a save is loaded.
    private const val SCRIPTS = """PLAYER: scripts/cheats/settings.lua
PLAYER: scripts/cheats/player.lua
GLOBAL: scripts/cheats/global.lua
"""

    fun install(context: Context): File? {
        val root = File(context.filesDir, "cheats")
        return try {
            root.deleteRecursively()
            copyAssets(context, ASSET_ROOT, root)
            File(root, CONTENT_FILE).writeText(SCRIPTS)
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
