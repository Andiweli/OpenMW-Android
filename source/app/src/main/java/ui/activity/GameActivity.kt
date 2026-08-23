/*
    Copyright (C) 2015-2017 sandstranger
    Copyright (C) 2018, 2019 Ilya Zhuravlev

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

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.preference.PreferenceManager
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import android.view.WindowManager
import android.widget.RelativeLayout
import com.libopenmw.openmw.BuildConfig
import com.libopenmw.openmw.R

import org.libsdl.app.SDLActivity

import constants.Constants
import cursor.MouseCursor
import parser.CommandlineParser
import ui.controls.Osc

import utils.Utils.hideAndroidControls

import android.util.DisplayMetrics
import android.os.AsyncTask
import android.widget.ImageView
import android.widget.TextView
import android.graphics.Typeface
import android.graphics.Rect
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Enum for different mouse modes as specified in settings
 */
enum class MouseMode {
    Hybrid,
    Joystick,
    Touch;

    companion object {
        fun get(s: String): MouseMode {
            return when (s) {
                "joystick" -> Joystick
                "touch" -> Touch
                else -> Hybrid
            }
        }
    }
}

class GameActivity : SDLActivity() {

    private var prefs: SharedPreferences? = null

    @Volatile
    private var nativeLogBridgeRunning = false

    // OPENMW_CHROMEOS_NATIVE_MOUSE_V1
    // ChromeOS uses SDL's own relative-mouse / pointer-capture path. Do not install
    // a second captured-pointer bridge or force the host cursor permanently hidden.

    // OPENMW_CHROMEOS_EXIT_NO_WHITE_V1
    private val chromeOsExitHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var chromeOsExitScheduled = false

    val layout: RelativeLayout
        get() = SDLActivity.mLayout as RelativeLayout

    override fun loadLibraries() {
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val physicsFPS = prefs!!.getString("pref_physicsFPS2", "")
        if (!physicsFPS!!.isEmpty()) {
            try {
                Os.setenv("OPENMW_PHYSICS_FPS", physicsFPS, true)
                Os.setenv("OSG_TEXT_SHADER_TECHNIQUE", "ALL", true)
            } catch (e: ErrnoException) {
                Log.e("OpenMW", "Failed setting environment variables.")
                e.printStackTrace()
            }
        }

        System.loadLibrary("c++_shared")
        System.loadLibrary("openal")
        System.loadLibrary("SDL2")

        // OPENMW_ANDROID_051_LAUNCHER_AUDIT_V1
        // OpenMW 0.51 final and this port's GL4ES/OMWFX compatibility shaders
        // use the GLES2 backend. The old GLES1 launcher selector is a CaveBros
        // legacy path and is no longer exposed.
        try {
            Os.setenv("OPENMW_GLES_VERSION", "2", true)
            Os.setenv("LIBGL_ES", "2", true)
        } catch (e: ErrnoException) {
            Log.e("OpenMW", "Failed setting GLES2 environment variables.")
            e.printStackTrace()
        }

        val textureShrinkingOption = prefs!!.getString("pref_textureShrinking_v2", "")
        if (textureShrinkingOption == "low") Os.setenv("LIBGL_SHRINK", "2", true)
        if (textureShrinkingOption == "medium") Os.setenv("LIBGL_SHRINK", "7", true)
        if (textureShrinkingOption == "high") Os.setenv("LIBGL_SHRINK", "6", true)

        val shaderDirOption = prefs!!.getString("pref_shadersDir_v2", "")
        if (shaderDirOption == "modified") Os.setenv("OPENMW_SHADERS", "modified", true)
        if (shaderDirOption == "zesterer") Os.setenv("OPENMW_SHADERS", "zesterer", true)

        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_nohighp", false) && shaderDirOption == "modified") {
            Os.setenv("LIBGL_NOHIGHP", "1", true)
        }

        Os.setenv("OSG_VERTEX_BUFFER_HINT", "VBO", true)
        Os.setenv("OPENMW_USER_FILE_STORAGE", Constants.USER_FILE_STORAGE + "/", true)
        //Os.setenv("OSG_NOTIFY_LEVEL", "FATAL", true) //hide osg errors for now, gl4es bug.
        
        val envline: String = PreferenceManager.getDefaultSharedPreferences(this).getString("envLine", "").toString()
        if (envline.length > 0) {
            val envs: List<String> = envline.split(" ", "\n")
            var i = 0

            repeat(envs.count())
            {
                val env: List<String> = envs[i].split("=", limit = 2)
                if (env.count() == 2) Os.setenv(env[0], env[1], true)
                i = i + 1
            }
        }

        System.loadLibrary("GL")
        System.loadLibrary("openmw")
    }

    override fun getMainSharedObject(): String {
        return "libopenmw.so"
    }


    private fun showProgressBar() {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)

        val progressBarBackground = ImageView(layout.context)
        progressBarBackground.setImageResource(R.drawable.progressbarbackground)
        progressBarBackground.setScaleType(ImageView.ScaleType.FIT_XY)
        progressBarBackground.setX(((dm.widthPixels / 2) - 405).toFloat())
        progressBarBackground.setY(((dm.heightPixels / 2) - 105).toFloat())
        layout.addView(progressBarBackground)
        progressBarBackground.getLayoutParams().width = 810
        progressBarBackground.getLayoutParams().height = 60


        val progressBar = ImageView(layout.context)
        progressBar.setImageResource(R.drawable.progressbar)
        progressBar.setScaleType(ImageView.ScaleType.FIT_XY)
        progressBar.setX(((dm.widthPixels / 2) - 400).toFloat())
        progressBar.setY(((dm.heightPixels / 2) - 100).toFloat())
        layout.addView(progressBar)
        progressBar.getLayoutParams().width = 0
        progressBar.getLayoutParams().height = 50

        val message = getString(R.string.navmesh_generating_cache)
        val text = TextView(this)
        text.text = message
        val bounds = Rect()
        text.paint.getTextBounds(message, 0, message.length, bounds)
        text.setX(((dm.widthPixels / 2) - (bounds.width() / 2)) .toFloat())
        text.setY(((dm.heightPixels / 2) - 200).toFloat())
        text.setTypeface(null, Typeface.BOLD)
        layout.addView(text)

        val percentageText = TextView(this)
        percentageText.setX((dm.widthPixels / 2).toFloat())
        percentageText.setY(((dm.heightPixels / 2) + 50).toFloat())
        layout.addView(percentageText)

        Os.setenv("NAVMESHTOOL_MESSAGE", "0.0", true)
        ProgressBarUpdater(percentageText, progressBar, dm.widthPixels, dm.heightPixels).execute()
    }

    class ProgressBarUpdater(val percentageText: TextView, val progressBar: ImageView, val screenWidth: Int, val screenHeight: Int) : AsyncTask<Void, String, String>() {
        override fun doInBackground(vararg params: Void?): String {

            while(Os.getenv("NAVMESHTOOL_MESSAGE") != "Done") {
                publishProgress(Os.getenv("NAVMESHTOOL_MESSAGE"))
                Thread.sleep(50)
            }

            return "DONE"
        }
/*
        override fun onPreExecute() {
            super.onPreExecute()
        }

        override fun onPostExecute() {
            super.onPostExecute()
        }
*/
        override fun onProgressUpdate(vararg progress: String?) {
            super.onProgressUpdate()

            val progressText = progress.firstOrNull() ?: return
            val progressValue = progressText.toFloatOrNull() ?: return

            progressBar.requestLayout()
            progressBar.layoutParams.width = (8.0f * progressValue).toInt().coerceIn(0, 800)

            val bounds = Rect()
            percentageText.paint.getTextBounds(progressText, 0, progressText.length, bounds)

            percentageText.x = ((screenWidth / 2) - (bounds.width() / 2)).toFloat()
            percentageText.text = progressText
        }

    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayInCutoutArea = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_display_cutout_area", false)
        if (displayInCutoutArea) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        KeepScreenOn()
        getPathToJni(filesDir.parent, Constants.USER_FILE_STORAGE)

        prepareNativeDiagnostics()

        if(Os.getenv("OPENMW_GENERATE_NAVMESH_CACHE") == "1")
            showProgressBar()
        else
            showControls()
    }

    private fun prepareNativeDiagnostics() {
        val userConfigDir = File(Constants.USER_CONFIG)
        if (!userConfigDir.exists() && !userConfigDir.mkdirs()) {
            Log.w(DIAG_TAG, "Could not create user config directory: ${userConfigDir.absolutePath}")
        }

        // On debug builds rotate stale files before SDL_main starts, so every Logcat
        // capture contains the diagnostics from exactly this launch.
        if (BuildConfig.DEBUG) {
            rotateLogForDiagnostics(File(userConfigDir, "openmw.log"))
            rotateLogForDiagnostics(File(userConfigDir, "crash.log"))
        }

        logLaunchDiagnostics()
        startNativeLogBridge()
    }

    private fun rotateLogForDiagnostics(logFile: File) {
        if (!logFile.isFile) {
            return
        }

        val previous = File(logFile.parentFile, "${logFile.name}.previous")
        if (previous.exists() && !previous.delete()) {
            Log.w(DIAG_TAG, "Could not delete old ${previous.absolutePath}")
        }

        if (!logFile.renameTo(previous)) {
            Log.w(DIAG_TAG, "Could not rotate ${logFile.absolutePath}; truncating it instead.")
            try {
                logFile.writeText("")
            } catch (e: IOException) {
                Log.e(DIAG_TAG, "Could not reset ${logFile.absolutePath}", e)
            }
        }
    }

    private fun logLaunchDiagnostics() {
        val graphicsLibrary = prefs?.getString("pref_graphicsLibrary_v2", "") ?: ""
        val shaderDirectory = prefs?.getString("pref_shadersDir_v2", "") ?: ""
        val commandLine = prefs?.getString("commandLine", "") ?: ""

        Log.i(DIAG_TAG, "package=$packageName debug=${BuildConfig.DEBUG}")
        Log.i(DIAG_TAG, "device=${Build.MANUFACTURER} ${Build.MODEL}; sdk=${Build.VERSION.SDK_INT}; abis=${Build.SUPPORTED_ABIS.joinToString()}")
        Log.i(DIAG_TAG, "customResolution=${MainActivity.resolutionX}x${MainActivity.resolutionY}; surfaceView=${SDLActivity.getSurface()?.width}x${SDLActivity.getSurface()?.height}")
        Log.i(DIAG_TAG, "chromebook=${SDLActivity.isChromebook()}; relativeMouseSupported=${SDLActivity.supportsRelativeMouse()}")
        Log.i(DIAG_TAG, "mousePath=${if (SDLActivity.isChromebook()) "SDL native capture + system cursor" else "legacy Android cursor"}")
        Log.i(DIAG_TAG, "graphicsLibrary=${if (graphicsLibrary.isBlank()) "<default/GLES2>" else graphicsLibrary}; shaderDirectory=${if (shaderDirectory.isBlank()) "<default>" else shaderDirectory}")
        Log.i(DIAG_TAG, "OPENMW_GLES_VERSION=${Os.getenv("OPENMW_GLES_VERSION")}; LIBGL_ES=${Os.getenv("LIBGL_ES")}; OPENMW_USER_FILE_STORAGE=${Os.getenv("OPENMW_USER_FILE_STORAGE")}")
        Log.i(DIAG_TAG, "USER_FILE_STORAGE=${Constants.USER_FILE_STORAGE}")
        Log.i(DIAG_TAG, "USER_CONFIG=${Constants.USER_CONFIG}")
        Log.i(DIAG_TAG, "GLOBAL_CONFIG=${Constants.GLOBAL_CONFIG}")
        Log.i(DIAG_TAG, "RESOURCES=${Constants.RESOURCES}")
        Log.i(DIAG_TAG, "runtimeResources=${selectRuntimeResourcesPath()}")
        Log.i(DIAG_TAG, "commandLine=${if (commandLine.isBlank()) "<empty>" else commandLine}")
        Log.i(DIAG_TAG, "SDL arguments=${getArguments().joinToString(" ")}")

        logFileState("openmw.cfg", File(Constants.OPENMW_CFG))
        logFileState("defaults.bin", File(Constants.DEFAULTS_BIN))
        logFileState("bundled resources", File(Constants.RESOURCES))
        logFileState("bundled resources/version", File(Constants.RESOURCES, "version"))
        logFileState("bundled resources/openmw.png", File(Constants.RESOURCES, "openmw.png"))
        logFileState("user resources", File(Constants.USER_FILE_STORAGE + "/resources/"))
        logFileState("user resources/version", File(Constants.USER_FILE_STORAGE + "/resources/version"))
        logFileState("user resources/openmw.png", File(Constants.USER_FILE_STORAGE + "/resources/openmw.png"))
        logOpenMwConfigSummary()
    }

    private fun logFileState(label: String, file: File) {
        val size = if (file.isFile) file.length() else 0L
        Log.i(DIAG_TAG, "$label: path=${file.absolutePath}; exists=${file.exists()}; directory=${file.isDirectory}; size=$size")
    }

    private fun logOpenMwConfigSummary() {
        val config = File(Constants.OPENMW_CFG)
        if (!config.isFile) {
            Log.e(DIAG_TAG, "Cannot inspect openmw.cfg because it does not exist.")
            return
        }

        val interestingPrefixes = listOf(
            "resources=",
            "data=",
            "content=",
            "groundcover=",
            "fallback-archive=",
            "encoding="
        )

        try {
            var count = 0
            config.forEachLine { line ->
                val trimmed = line.trim()
                if (interestingPrefixes.any { trimmed.startsWith(it) }) {
                    if (count < MAX_CONFIG_LOG_LINES) {
                        Log.i(CONFIG_TAG, trimmed)
                    }
                    count += 1
                }
            }

            if (count == 0) {
                Log.e(CONFIG_TAG, "No data/content/resources entries found in openmw.cfg")
            } else if (count > MAX_CONFIG_LOG_LINES) {
                Log.i(CONFIG_TAG, "... ${count - MAX_CONFIG_LOG_LINES} additional entries omitted")
            }
        } catch (e: IOException) {
            Log.e(CONFIG_TAG, "Failed reading openmw.cfg", e)
        }
    }

    private fun startNativeLogBridge() {
        nativeLogBridgeRunning = true

        Thread({
            val logs = listOf(
                File(Constants.USER_CONFIG, "openmw.log"),
                File(Constants.USER_CONFIG, "crash.log")
            )
            val positions = mutableMapOf<String, Long>()
            val deadline = System.currentTimeMillis() + NATIVE_LOG_BRIDGE_MILLIS

            Log.i(DIAG_TAG, "Native log bridge active for ${NATIVE_LOG_BRIDGE_MILLIS / 1000}s")

            while (nativeLogBridgeRunning && System.currentTimeMillis() < deadline) {
                logs.forEach { logFile ->
                    if (!logFile.isFile) {
                        return@forEach
                    }

                    var position = positions[logFile.absolutePath] ?: 0L
                    if (logFile.length() < position) {
                        position = 0L
                    }

                    if (logFile.length() > position) {
                        try {
                            RandomAccessFile(logFile, "r").use { reader ->
                                reader.seek(position)

                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (logFile.name == "crash.log") {
                                        Log.e(NATIVE_LOG_TAG, "${logFile.name}: $line")
                                    } else {
                                        Log.i(NATIVE_LOG_TAG, "${logFile.name}: $line")
                                    }
                                }

                                positions[logFile.absolutePath] = reader.filePointer
                            }
                        } catch (e: IOException) {
                            Log.e(DIAG_TAG, "Failed reading ${logFile.absolutePath}", e)
                        }
                    }
                }

                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            Log.i(DIAG_TAG, "Native log bridge stopped")
        }, "OpenMW-native-log-bridge").apply {
            isDaemon = true
            start()
        }
    }

    private fun showControls() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        mouseMode = MouseMode.get((prefs.getString("pref_mouse_mode",
            getString(R.string.pref_mouse_mode_default))!!))

        val pref_hide_controls = prefs.getBoolean(Constants.HIDE_CONTROLS, true)
        var osc: Osc? = null
        if (!pref_hide_controls) {
            val layout = layout
            osc = Osc()
            osc.placeElements(layout)
        }
        MouseCursor(this, osc)
    }

    private fun installChromeOsExitCover() {
        val decor = window.decorView as? android.view.ViewGroup ?: return
        val cover = android.view.View(this)
        cover.setBackgroundColor(android.graphics.Color.BLACK)
        cover.isClickable = true
        cover.isFocusable = true
        decor.addView(
            cover,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        cover.bringToFront()
    }

    private fun finishAfterChromeOsExitDelay() {
        super.finish()
    }

    override fun finish() {
        if (Build.VERSION.SDK_INT >= 26 &&
            SDLActivity.isChromebook() &&
            !chromeOsExitScheduled &&
            !isFinishing) {
            chromeOsExitScheduled = true

            runOnUiThread {
                // OPENMW_CHROMEOS_EXIT_NO_WHITE_V1
                // Cover the final GL frame, put the task behind the ChromeOS desktop
                // immediately, then close the now-invisible Activity shortly afterwards.
                installChromeOsExitCover()
                moveTaskToBack(true)
                chromeOsExitHandler.postDelayed(
                    { finishAfterChromeOsExitDelay() },
                    CHROMEOS_EXIT_FINISH_DELAY_MILLIS
                )
            }
            return
        }

        super.finish()
    }

    private fun KeepScreenOn() {
        val needKeepScreenOn = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_screen_keeper", false)
        if (needKeepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    public override fun onDestroy() {
        nativeLogBridgeRunning = false

        if (Build.VERSION.SDK_INT >= 26 && SDLActivity.isChromebook()) {
            chromeOsExitHandler.removeCallbacksAndMessages(null)

            // Let SDLActivity release native state after the task has already been
            // moved behind the desktop. Kill the shared launcher/game process only
            // after the Android/SDL teardown path has completed.
            super.onDestroy()
            Process.killProcess(Process.myPid())
            return
        }

        // Preserve the existing Android behavior outside ChromeOS.
        finish()
        Process.killProcess(Process.myPid())
        super.onDestroy()
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        // SDLActivity maintains native focus state and reclaims relative mouse
        // capture here. Do not bypass the superclass implementation.
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            hideAndroidControls(this)
        }
    }

    private fun resourcePayloadMatches(source: File, target: File): Boolean {
        if (!source.isDirectory || !target.isDirectory) {
            return false
        }

        val sourceVersion = File(source, "version")
        val targetVersion = File(target, "version")
        val targetLogo = File(target, "openmw.png")
        val targetVfs = File(target, "vfs")

        if (!sourceVersion.isFile || !targetVersion.isFile ||
            !targetLogo.isFile || !targetVfs.isDirectory) {
            return false
        }

        return try {
            sourceVersion.readText().trim() == targetVersion.readText().trim()
        } catch (e: IOException) {
            false
        }
    }

    private fun selectRuntimeResourcesPath(): String {
        val bundledResources = File(Constants.RESOURCES)
        val userResources = File(Constants.USER_FILE_STORAGE + "/resources/")

        return if (resourcePayloadMatches(bundledResources, userResources)) {
            userResources.absolutePath
        } else {
            Log.w(
                DIAG_TAG,
                "User resource mirror is invalid; falling back to bundled resources at ${bundledResources.absolutePath}"
            )
            bundledResources.absolutePath
        }
    }

    override fun getArguments(): Array<String> {
        val cmd = PreferenceManager.getDefaultSharedPreferences(this).getString("commandLine", "") ?: ""
        val resourcesPath = selectRuntimeResourcesPath()
        val commandlineParser = CommandlineParser("--resources $resourcesPath $cmd")
        return commandlineParser.argv
    }

    private external fun getPathToJni(path_global: String, path_user: String)

    companion object {
        private const val DIAG_TAG = "OpenMW-Diag"
        private const val CONFIG_TAG = "OpenMW-Config"
        private const val NATIVE_LOG_TAG = "OpenMW-Native"
        private const val MAX_CONFIG_LOG_LINES = 120
        private const val NATIVE_LOG_BRIDGE_MILLIS = 120_000L
        private const val CHROMEOS_EXIT_FINISH_DELAY_MILLIS = 50L

        var mouseMode = MouseMode.Hybrid
    }
}
