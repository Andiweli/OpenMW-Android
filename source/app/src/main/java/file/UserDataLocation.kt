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

import constants.Constants
import java.io.File
import java.io.IOException

/**
 * A user-chosen OpenMW user-data folder, so saves (and screenshots) can live in
 * shared storage where sync tools such as Syncthing can reach them.
 *
 * OpenMW takes the folder from the `user-data` option of openmw.cfg and keeps
 * saves in `<folder>/saves`. The launcher's base config already sets
 * `user-data`, and the option may appear only once, so the generated config
 * replaces that line instead of adding a second one.
 *
 * The user config (settings.cfg, the user openmw.cfg, key bindings) moves along
 * to `<folder>/config`, where a file manager can open it: the base config's
 * `config=` line names the folder OpenMW reads it from and saves it to.
 */
object UserDataLocation {
    const val PREF_USER_DATA_DIR = "pref_user_data_dir"
    /** The folder whose config already holds the user's settings (taken over once). */
    const val PREF_CONFIG_ADOPTED = "pref_user_config_adopted"

    private const val SAVES = "saves"
    private const val CONFIG = "config"
    private const val WRITE_PROBE = ".openmw-write-test"
    private const val COPY_SUFFIX = ".copying"

    /**
     * Creates [dir] if needed and checks that a file can be written there.
     *
     * @throws IOException if the folder cannot be created or written
     */
    @Throws(IOException::class)
    fun ensureWritable(dir: File) {
        try {
            if (!dir.isDirectory && !dir.mkdirs())
                throw IOException("Could not create ${dir.absolutePath}")
            val probe = File(dir, WRITE_PROBE)
            probe.writeText("")
            probe.delete()
        } catch (e: SecurityException) {
            throw IOException("Not allowed to write to ${dir.absolutePath}", e)
        }
    }

    /** The user config folder below the user-data folder [dir]. */
    fun configDir(dir: File): File = File(dir, CONFIG)

    /**
     * Points the launcher at the user config of [dir], or of the app's own
     * folder when [dir] is null, so it edits the settings.cfg OpenMW reads.
     */
    fun useConfigOf(dir: File?) {
        val config = configDir(dir ?: File(Constants.USER_FILE_STORAGE))
        Constants.USER_CONFIG = config.absolutePath
        Constants.USER_OPENMW_CFG = File(config, "openmw.cfg").absolutePath
    }

    /** A quoted openmw.cfg path. OpenMW reads quoted paths with '&' as escape character. */
    private fun quoted(dir: File): String =
        "\"" + dir.absolutePath.replace("&", "&&").replace("\"", "&\"") + "\""

    /** The openmw.cfg line for [dir]. */
    fun cfgLine(dir: File): String = "user-data=${quoted(dir)}"

    /** The openmw.cfg line naming the user config folder of [dir]. */
    fun configLine(dir: File): String = "config=${quoted(configDir(dir))}"

    /**
     * Replaces the `user-data=` and `config=` lines of [lines] with ones for [dir],
     * or appends them when missing. Leaves [lines] unchanged when [dir] is null.
     */
    fun applyTo(lines: List<String>, dir: File?): List<String> {
        if (dir == null)
            return lines
        return replaceOption(replaceOption(lines, "user-data=", cfgLine(dir)), "config=", configLine(dir))
    }

    /** Keeps one [prefix] line, set to [line]; appends it when there is none. */
    private fun replaceOption(lines: List<String>, prefix: String, line: String): List<String> {
        var replaced = false
        val result = lines.mapNotNull {
            if (it.trim().startsWith(prefix)) {
                if (replaced) {
                    null
                } else {
                    replaced = true
                    line
                }
            } else {
                it
            }
        }
        return if (replaced) result else result + line
    }

    /**
     * Copies the saves below [from] into [to] without replacing any file that
     * already exists there, so switching folders never overwrites a save.
     * Files are written through a temporary name, so an interrupted copy never
     * leaves a truncated save behind.
     *
     * @return the number of files copied
     * @throws IOException if a file cannot be read or written
     */
    @Throws(IOException::class)
    fun copySaves(from: File, to: File): Int = copyMissing(File(from, SAVES), File(to, SAVES))

    /**
     * Copies the user config (settings.cfg, the user openmw.cfg, key bindings) of
     * [from] into [to] the same way, without replacing files already there.
     * Log files stay behind.
     *
     * @return the number of files copied
     * @throws IOException if a file cannot be read or written
     */
    @Throws(IOException::class)
    fun copyConfig(from: File, to: File): Int =
        copyMissing(configDir(from), configDir(to)) { !it.name.endsWith(".log") }

    @Throws(IOException::class)
    private fun copyMissing(source: File, target: File, include: (File) -> Boolean = { true }): Int {
        if (!source.isDirectory)
            return 0
        if (source.canonicalFile == target.canonicalFile)
            return 0

        var copied = 0
        source.walkTopDown()
            .onFail { _, e -> throw e }
            .filter { it.isFile && !it.name.endsWith(COPY_SUFFIX) && include(it) }
            .forEach { file ->
                val destination = File(target, file.relativeTo(source).path)
                if (destination.exists())
                    return@forEach

                val parent = destination.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs())
                    throw IOException("Could not create ${parent.absolutePath}")

                val temporary = File(parent, destination.name + COPY_SUFFIX)
                try {
                    file.copyTo(temporary, overwrite = true)
                    temporary.setLastModified(file.lastModified())
                    if (!temporary.renameTo(destination))
                        throw IOException("Could not move ${temporary.absolutePath} to ${destination.absolutePath}")
                } catch (e: IOException) {
                    temporary.delete()
                    throw e
                }
                copied++
            }
        return copied
    }
}
