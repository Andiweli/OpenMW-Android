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

/**
 * A user-chosen OpenMW user-data folder, so saves (and screenshots) can live in
 * shared storage where sync tools such as Syncthing can reach them.
 *
 * OpenMW takes the folder from the `user-data` option of openmw.cfg and keeps
 * saves in `<folder>/saves`. The launcher's base config already sets
 * `user-data`, and the option may appear only once, so the generated config
 * replaces that line instead of adding a second one.
 */
object UserDataLocation {
    const val PREF_USER_DATA_DIR = "pref_user_data_dir"

    private const val SAVES = "saves"
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

    /** The openmw.cfg line for [dir]. OpenMW reads quoted paths with '&' as escape character. */
    fun cfgLine(dir: File): String {
        val escaped = dir.absolutePath.replace("&", "&&").replace("\"", "&\"")
        return "user-data=\"$escaped\""
    }

    /**
     * Replaces the `user-data=` line of [lines] with one for [dir], or appends it
     * when there is none. Leaves [lines] unchanged when [dir] is null.
     */
    fun applyTo(lines: List<String>, dir: File?): List<String> {
        if (dir == null)
            return lines
        val line = cfgLine(dir)
        var replaced = false
        val result = lines.mapNotNull {
            if (it.trim().startsWith("user-data=")) {
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
    fun copySaves(from: File, to: File): Int {
        val source = File(from, SAVES)
        val target = File(to, SAVES)
        if (!source.isDirectory)
            return 0
        if (source.canonicalFile == target.canonicalFile)
            return 0

        var copied = 0
        source.walkTopDown()
            .onFail { _, e -> throw e }
            .filter { it.isFile && !it.name.endsWith(COPY_SUFFIX) }
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
