package ui.activity

import android.content.Context
import android.util.Log
import constants.Constants
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * OpenMW clamps [GUI] font size to 12..18. Larger text comes from copies of the
 * TrueType font definitions with a proportionally higher Resolution, placed in
 * a data folder so they override the ones in resources/vfs. Bitmap fonts have no
 * such property, so this only applies with TrueType fonts.
 */
object FontSizeOverride {
    private const val TAG = "FontSizeOverride"
    const val ENGINE_MAX = 18
    private val resolution = Regex("""(<Property\s+key="Resolution"\s+value=")(\d+)(")""")

    fun engineSize(requested: Int): Int = requested.coerceIn(12, ENGINE_MAX)

    /** Returns the data folder to add to openmw.cfg, or null when none is needed. */
    fun install(context: Context, requested: Int, trueType: Boolean): File? {
        val root = File(context.filesDir, "fontsize")
        root.deleteRecursively()
        if (!trueType || requested <= ENGINE_MAX) {
            return null
        }
        val scale = requested.toFloat() / ENGINE_MAX
        return try {
            val target = File(root, "fonts")
            if (!target.mkdirs()) {
                throw IOException("Could not create ${target.absolutePath}")
            }
            File(Constants.RESOURCES, "vfs/fonts")
                .listFiles { file -> file.extension.equals("omwfont", ignoreCase = true) }
                ?.forEach { font ->
                    val scaled = resolution.replace(font.readText()) { match ->
                        val value = (match.groupValues[2].toInt() * scale).roundToInt()
                        match.groupValues[1] + value + match.groupValues[3]
                    }
                    File(target, font.name).writeText(scaled)
                }
            root
        } catch (e: IOException) {
            Log.e(TAG, "Could not write the enlarged font definitions", e)
            null
        }
    }
}
