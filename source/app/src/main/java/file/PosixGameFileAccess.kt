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

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.InterruptedIOException

/**
 * [GameFileAccess] through the POSIX calls the native engine uses for game data
 * (OpenMW components/platform/fileposix.cpp: open, read, lseek). A successful probe
 * means the same path is readable for OpenMW in this app process.
 */
object PosixGameFileAccess : GameFileAccess {

    override fun list(dir: File): List<String> {
        if (!isDirectory(dir))
            throw FileAccessException(FileAccessFailure.NOT_DIRECTORY, dir, "Not a directory")

        val names = try {
            dir.list()
        } catch (e: SecurityException) {
            null
        }
        if (names != null)
            return names.toList()

        // File.list() hides the reason, so ask the kernel directly.
        try {
            Os.access(dir.path, OsConstants.R_OK or OsConstants.X_OK)
        } catch (e: ErrnoException) {
            throw failure(e, dir)
        }
        throw FileAccessException(FileAccessFailure.IO_ERROR, dir, "Directory listing failed")
    }

    override fun isDirectory(file: File): Boolean {
        return try {
            OsConstants.S_ISDIR(Os.stat(file.path).st_mode)
        } catch (e: ErrnoException) {
            throw failure(e, file)
        }
    }

    override fun readHead(file: File, count: Int): ByteArray {
        val fd: FileDescriptor = try {
            Os.open(file.path, OsConstants.O_RDONLY, 0)
        } catch (e: ErrnoException) {
            throw failure(e, file)
        }

        try {
            val buffer = ByteArray(count)
            var total = 0
            while (total < count) {
                val read = Os.read(fd, buffer, total, count - total)
                if (read <= 0)
                    break
                total += read
            }

            val size = Os.fstat(fd).st_size
            if (size > count) {
                Os.lseek(fd, size - 1, OsConstants.SEEK_SET)
                Os.read(fd, ByteArray(1), 0, 1)
            }

            return buffer.copyOf(total)
        } catch (e: ErrnoException) {
            throw failure(e, file)
        } catch (e: InterruptedIOException) {
            throw FileAccessException(FileAccessFailure.IO_ERROR, file, e.message ?: "Interrupted")
        } finally {
            try {
                Os.close(fd)
            } catch (_: ErrnoException) {
            }
        }
    }

    private fun failure(e: ErrnoException, file: File): FileAccessException {
        val failure = when (e.errno) {
            OsConstants.ENOENT, OsConstants.ENOTDIR -> FileAccessFailure.NOT_FOUND
            OsConstants.EACCES, OsConstants.EPERM -> FileAccessFailure.DENIED
            else -> FileAccessFailure.IO_ERROR
        }
        return FileAccessException(failure, file, e.message ?: "errno ${e.errno}")
    }
}
