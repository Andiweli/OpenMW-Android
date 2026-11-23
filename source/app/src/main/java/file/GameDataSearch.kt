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

/**
 * Looks for Morrowind game folders (a folder with Morrowind.ini and "Data Files")
 * on the given storage roots, for the launcher's "Automatic" button.
 *
 * The search goes breadth first, so folders near the top of a drive are found
 * first, and it stops at [maxDepth] levels and [maxFolders] listed folders, so
 * a full card never keeps it busy for long. Hidden folders and the app-private
 * Android/data and Android/obb trees are skipped.
 */
object GameDataSearch {

    private val SKIPPED = setOf("android/data", "android/obb")

    /**
     * @return the game folders found, without duplicates, in the order found
     */
    fun find(roots: List<File>, maxDepth: Int = 5, maxFolders: Int = 20000): List<File> {
        val found = LinkedHashSet<File>()
        var listed = 0
        val visited = HashSet<String>()

        for (root in roots.distinctBy { it.absolutePath }) {
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty() && listed < maxFolders) {
                val (dir, depth) = queue.removeFirst()
                val key = try { dir.canonicalPath } catch (e: Exception) { dir.absolutePath }
                if (!visited.add(key))
                    continue

                val children = dir.listFiles() ?: continue
                listed++

                if (isGameFolder(children)) {
                    found += dir.absoluteFile
                    continue
                }
                if (depth >= maxDepth)
                    continue

                children
                    .filter { it.isDirectory && !it.name.startsWith(".") }
                    .filterNot { relative(root, it) in SKIPPED }
                    .sortedBy { it.name.lowercase() }
                    .forEach { queue.add(it to depth + 1) }
            }
        }
        return found.toList()
    }

    private fun isGameFolder(children: Array<File>): Boolean {
        val ini = children.any { it.isFile && it.name.equals(GameInstaller.INI_NAME, ignoreCase = true) }
        val data = children.any { it.isDirectory && it.name.equals(GameInstaller.DATA_NAME, ignoreCase = true) }
        return ini && data
    }

    private fun relative(root: File, dir: File): String =
        dir.absolutePath.removePrefix(root.absolutePath).trim('/').lowercase()
}
