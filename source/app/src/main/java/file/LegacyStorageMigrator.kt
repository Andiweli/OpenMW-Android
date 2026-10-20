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
import java.io.InputStream

/**
 * Moves the legacy user tree (saves, screenshots, config, icons, resources,
 * navmesh.db) into the new user storage.
 *
 * A file that already exists in the target is never replaced: it may be newer,
 * e.g. a save made after an earlier interrupted attempt. A differing legacy
 * version of such a file is kept below [CONFLICT_DIRECTORY] instead. The legacy
 * tree is deleted only after every file has been copied completely, so the
 * migration can be repeated safely after any failure.
 *
 * @param usableSpace free bytes on the volume of a directory; replaceable for tests
 */
class LegacyStorageMigrator(
    private val usableSpace: (File) -> Long = { it.usableSpace }
) {

    sealed class Result {
        /** [conflictDirectory] holds legacy files that differed from newer ones, if any. */
        data class Migrated(val conflictDirectory: File?) : Result()

        /** Nothing was changed; [requiredBytes] are needed on the target volume. */
        data class NotEnoughSpace(val requiredBytes: Long) : Result()
    }

    /**
     * @param onCopied called once all files are safely in [target] and before [legacy]
     * is deleted; used to record that the migration must never run again
     * @throws IOException if a file cannot be read or written; [legacy] is unchanged then
     */
    @Throws(IOException::class)
    fun migrate(legacy: File, target: File, onCopied: () -> Unit): Result {
        target.parentFile?.mkdirs()

        // Fast path: a rename preserves timestamps and needs no extra space. It is
        // only safe while nothing has been written to the new location yet.
        if (target.isDirectory && target.list()?.isEmpty() == true)
            target.delete()

        if (!target.exists() && legacy.renameTo(target)) {
            onCopied()
            return Result.Migrated(null)
        }

        // Fallback for FUSE/scoped-storage implementations where rename across
        // directory classes is rejected.
        return copy(legacy, target, onCopied)
    }

    private fun copy(legacy: File, target: File, onCopied: () -> Unit): Result {
        val conflictRoot = File(target, CONFLICT_DIRECTORY)
        val plan = mutableListOf<Pair<File, File>>()

        legacy.walkTopDown()
            .onFail { _, e -> throw e }
            .filter { it.isFile }
            .forEach { source ->
                val relative = source.relativeTo(legacy).path
                val destination = File(target, relative)
                when {
                    !destination.exists() -> plan += source to destination
                    destination.isFile && sameContent(source, destination) -> Unit
                    else -> {
                        val conflict = File(conflictRoot, relative)
                        if (!conflict.isFile || !sameContent(source, conflict))
                            plan += source to conflict
                    }
                }
            }

        if (!target.isDirectory && !target.mkdirs())
            throw IOException("Could not create ${target.absolutePath}")

        val requiredBytes = plan.sumOf { it.first.length() }
        if (requiredBytes + FREE_SPACE_RESERVE_BYTES > usableSpace(target))
            return Result.NotEnoughSpace(requiredBytes)

        plan.forEach { (source, destination) -> copyFileCompletely(source, destination) }

        // Verify before anything is deleted.
        plan.forEach { (source, destination) ->
            if (!destination.isFile || destination.length() != source.length())
                throw IOException("Copy of ${source.absolutePath} is incomplete")
        }

        onCopied()
        // A partly failed delete leaves only files that were already copied.
        legacy.deleteRecursively()

        val conflicts = plan.any { (_, destination) ->
            destination.absolutePath.startsWith(conflictRoot.absolutePath + File.separator)
        }
        return Result.Migrated(if (conflicts) conflictRoot else null)
    }

    /** Copies [source] through a temporary file, so [destination] is never left half-written. */
    private fun copyFileCompletely(source: File, destination: File) {
        val parent = destination.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs())
            throw IOException("Could not create ${parent.absolutePath}")

        val temporary = File(parent, destination.name + ".migrating")
        try {
            source.copyTo(temporary, overwrite = true)
            temporary.setLastModified(source.lastModified())
            if (!temporary.renameTo(destination))
                throw IOException("Could not move ${temporary.absolutePath} to ${destination.absolutePath}")
        } catch (e: IOException) {
            temporary.delete()
            throw e
        }
    }

    private fun sameContent(first: File, second: File): Boolean {
        if (first.length() != second.length())
            return false

        first.inputStream().use { a ->
            second.inputStream().use { b ->
                val bufferA = ByteArray(COMPARE_BUFFER_SIZE)
                val bufferB = ByteArray(COMPARE_BUFFER_SIZE)
                while (true) {
                    val count = readBlock(a, bufferA)
                    if (count != readBlock(b, bufferB))
                        return false
                    if (count == 0)
                        return true
                    for (i in 0 until count) {
                        if (bufferA[i] != bufferB[i])
                            return false
                    }
                }
            }
        }
    }

    private fun readBlock(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0)
                break
            total += read
        }
        return total
    }

    companion object {
        const val CONFLICT_DIRECTORY = "legacy-omw-conflicts"
        const val FREE_SPACE_RESERVE_BYTES = 64L * 1024 * 1024
        private const val COMPARE_BUFFER_SIZE = 64 * 1024
    }
}
