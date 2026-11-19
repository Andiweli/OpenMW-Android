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

package ui.launcher

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The launcher's version button, as in CubeEvil4: the newest release on GitHub
 * against the installed version. Asked once per app run, in the background.
 * Without an answer (no internet) the button stays hidden.
 */
object LauncherUpdate {
    private const val TAG = "OpenMW-Update"
    private const val LATEST = "https://api.github.com/repos/Andiweli/OpenMW-Android/releases/latest"
    const val RELEASES = "https://github.com/Andiweli/OpenMW-Android/releases"

    enum class State { UNKNOWN, CURRENT, OLDER }

    @Volatile
    var state = State.UNKNOWN
        private set

    private var asked = false
    private val listeners = ArrayList<(State) -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Asks GitHub once; [listener] gets the answer on the main thread (at once if already known). */
    fun check(installed: String, listener: (State) -> Unit) {
        if (state != State.UNKNOWN) {
            listener(state)
            return
        }
        if (listener !in listeners)
            listeners += listener
        if (asked)
            return
        asked = true
        Thread({
            val answer = try {
                val latest = fetchLatestTag()
                if (latest == null) State.UNKNOWN
                else if (compare(installed, latest) < 0) State.OLDER
                else State.CURRENT
            } catch (e: Exception) {
                Log.i(TAG, "Update check failed: ${e.javaClass.simpleName}: ${e.message}")
                State.UNKNOWN
            }
            main.post {
                state = answer
                if (answer == State.UNKNOWN)
                    asked = false // try again on the next launcher start
                val waiting = ArrayList(listeners)
                listeners.clear()
                waiting.forEach { it(answer) }
            }
        }, "OpenMW-update-check").start()
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    private fun fetchLatestTag(): String? {
        val connection = URL(LATEST).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK)
                return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).optString("tag_name").takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Compares two versions such as "0.51.0-15" and "v0.51.0-16" by their numbers
     * in order. Negative when [a] is older than [b], 0 when equal, positive when newer.
     */
    fun compare(a: String, b: String): Int {
        val left = numbers(a)
        val right = numbers(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrElse(i) { 0L }
            val y = right.getOrElse(i) { 0L }
            if (x != y)
                return x.compareTo(y)
        }
        return 0
    }

    private fun numbers(version: String): List<Long> =
        Regex("\\d+").findAll(version).map { it.value.toLongOrNull() ?: 0L }.toList()
}
