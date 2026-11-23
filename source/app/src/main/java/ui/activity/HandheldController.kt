package ui.activity

import android.content.Context
import android.util.Log
import constants.Constants
import java.io.File
import java.io.IOException

/**
 * Installs the Handheld mode content folder: the radial menu scripts from the
 * Switch port. The pointer itself is handled in SDLActivity and MouseCursor.
 *
 * The content file is always listed in openmw.cfg, empty when Handheld mode is
 * off, so toggling the mode does not change the content list saves remember.
 */
object HandheldController {
    private const val TAG = "HandheldController"
    const val CONTENT_FILE = "handheld.omwscripts"
    private const val ASSET_ROOT = "handheld"
    private const val SCRIPTS = """MENU,PLAYER: scripts/omw/radialmenu/settings.lua
PLAYER: scripts/omw/radialmenu/player.lua
"""

    // OpenMW action ids and SDL controller buttons used in input_v3.xml.
    private const val A_REST = 13
    private const val A_JOURNAL = 14
    private const val BUTTON_BACK = 4
    private const val BUTTON_LEFTSHOULDER = 9
    private const val BUTTON_RIGHTSHOULDER = 10

    fun install(context: Context, enabled: Boolean): File? {
        if (enabled) {
            remapShoulderButtons()
        } else {
            restoreShoulderButtons()
        }
        val root = File(context.filesDir, "handheld")
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
            Log.e(TAG, "Could not install the handheld scripts", e)
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

    /**
     * L1 opens the radial menu, so Journal moves to R1 and Rest to Select,
     * like the Switch port. Only applied while Journal is still on L1, so a
     * layout the player changed in the game is kept.
     */
    private fun remapShoulderButtons() {
        editBindings { text ->
            if (controlButton(text, A_JOURNAL) != BUTTON_LEFTSHOULDER) {
                return@editBindings null
            }
            Log.i(TAG, "Controller remap: L1=radial menu, R1=journal, Select=rest")
            setControlButton(setControlButton(text, A_JOURNAL, BUTTON_RIGHTSHOULDER), A_REST, BUTTON_BACK)
        }
    }

    /** Undoes [remapShoulderButtons] when Handheld mode is turned off. */
    private fun restoreShoulderButtons() {
        editBindings { text ->
            if (controlButton(text, A_JOURNAL) != BUTTON_RIGHTSHOULDER
                || controlButton(text, A_REST) != BUTTON_BACK) {
                return@editBindings null
            }
            Log.i(TAG, "Controller remap undone: L1=journal, R1=rest")
            setControlButton(setControlButton(text, A_JOURNAL, BUTTON_LEFTSHOULDER), A_REST, BUTTON_RIGHTSHOULDER)
        }
    }

    private fun editBindings(edit: (String) -> String?) {
        val file = File(Constants.USER_CONFIG, "input_v3.xml")
        if (!file.isFile) {
            return
        }
        try {
            edit(file.readText())?.let { file.writeText(it) }
        } catch (e: IOException) {
            Log.e(TAG, "Could not update ${file.absolutePath}", e)
        }
    }

    private fun controlBlock(text: String, action: Int): MatchResult? =
        Regex("""<Control name="$action"[^>]*>.*?</Control>""", RegexOption.DOT_MATCHES_ALL).find(text)

    private val buttonBinder = Regex("""\s*<JoystickButtonBinder button="(\d+)"[^>]*/>""")

    private fun controlButton(text: String, action: Int): Int? {
        val block = controlBlock(text, action) ?: return null
        return buttonBinder.find(block.value)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun setControlButton(text: String, action: Int, button: Int): String {
        val block = controlBlock(text, action) ?: return text
        val withoutButton = buttonBinder.replace(block.value, "")
        val binder = "\n\t\t<JoystickButtonBinder button=\"$button\" direction=\"INCREASE\" deviceId=\"1\" />"
        val channelAt = withoutButton.indexOf("\n\t\t<Channel")
        val newBlock = if (channelAt >= 0) {
            withoutButton.substring(0, channelAt) + binder + withoutButton.substring(channelAt)
        } else {
            withoutButton.replace("</Control>", "$binder\n\t</Control>")
        }
        return text.replaceRange(block.range, newBlock)
    }
}
