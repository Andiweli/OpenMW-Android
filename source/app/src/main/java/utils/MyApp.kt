package utils

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.bugsnag.android.Bugsnag
import com.bugsnag.android.Configuration
import constants.Constants
import file.LegacyStorageMigrator
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

class MyApp : Application() {

    var defaultScaling = 0f

    /** Outcome of the one-time move of the legacy /storage/emulated/0/omw tree. */
    sealed class LegacyStorageMigrationResult {
        object NotNeeded : LegacyStorageMigrationResult()

        /** [conflictDirectory] holds legacy files that differed from newer ones, if any. */
        data class Migrated(val conflictDirectory: File?) : LegacyStorageMigrationResult()

        data class NotEnoughSpace(val legacyDirectory: File, val requiredBytes: Long) :
            LegacyStorageMigrationResult()

        data class Failed(val legacyDirectory: File, val reason: String) :
            LegacyStorageMigrationResult()
    }

    private val legacyMigrationLock = Any()
    private var legacyMigrationLatch: CountDownLatch? = null
    private val legacyMigrationListeners = mutableListOf<(LegacyStorageMigrationResult) -> Unit>()
    private var legacyMigrationAttemptFailed = false

    override fun onCreate() {
        app = this

        super.onCreate()

        setupPaths()

        // Enable Bugsnag only when an API key is provided and we have user consent.
        // Also don't enable Bugsnag in debug builds.
        if (isProductionBuild() && BugsnagApiKey.API_KEY.isNotEmpty() && !com.libopenmw.openmw.BuildConfig.DEBUG) {
            haveBugsnagApiKey = true

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (prefs.getString("bugsnag_consent", "false") == "true") {
                val config = Configuration(BugsnagApiKey.API_KEY)
                config.buildUUID = ""
                Bugsnag.init(this, config)
                reportCrashes = true
            }
        }
    }

    private fun setupPaths() {
        // Android-conform app-specific external storage. Do not construct
        // /storage/emulated/0/Android/data paths manually; Android supplies
        // the correct volume/package path here (also on ChromeOS).
        val externalBase = getExternalFilesDir(null)
        val userRoot = if (externalBase != null) {
            File(externalBase, USER_STORAGE_DIRECTORY)
        } else {
            // Very unusual fallback when shared/external storage is unavailable.
            // OpenMW can still run with an internal user-data directory.
            Log.w(TAG, "getExternalFilesDir(null) returned null; using internal user storage.")
            File(filesDir, "user/$USER_STORAGE_DIRECTORY")
        }

        Constants.USER_FILE_STORAGE = userRoot.absolutePath

        // With the canonical applicationId com.ast.openmw this normally resolves to:
        // /storage/emulated/0/Android/data/com.ast.openmw/files/OpenMW
        Constants.USER_CONFIG = File(userRoot, "config").absolutePath
        Constants.USER_OPENMW_CFG = File(userRoot, "config/openmw.cfg").absolutePath

        Constants.DEFAULTS_BIN = File(filesDir, "config/defaults.bin").absolutePath
        Constants.OPENMW_CFG = File(filesDir, "config/openmw.cfg").absolutePath
        Constants.OPENMW_BASE_CFG = File(filesDir, "config/openmw.base.cfg").absolutePath
        Constants.OPENMW_FALLBACK_CFG = File(filesDir, "config/openmw.fallback.cfg").absolutePath
        Constants.RESOURCES = File(filesDir, "resources").absolutePath
        Constants.GLOBAL_CONFIG = File(filesDir, "config").absolutePath
        Constants.VERSION_STAMP = File(filesDir, "stamp").absolutePath

        Log.i(TAG, "OpenMW user storage: ${Constants.USER_FILE_STORAGE}")
    }

    /**
     * Starts the one-time move of the old public /storage/emulated/0/omw tree
     * (saves, screenshots, config, icons, resources, navmesh.db) on a background
     * thread, if it is needed.
     *
     * This is intentionally called from MainActivity.onResume(), rather than
     * Application.onCreate(), because an existing installation may still need
     * to grant legacy shared-storage permission before the old tree is readable.
     *
     * Must be called on the main thread. [listener] is called on the main thread
     * once the migration has finished.
     *
     * @return true if a migration is running and [listener] was registered
     */
    fun startLegacyUserStorageMigrationIfNeeded(
        listener: (LegacyStorageMigrationResult) -> Unit
    ): Boolean {
        synchronized(legacyMigrationLock) {
            if (legacyMigrationLatch != null) {
                legacyMigrationListeners += listener
                return true
            }

            if (!legacyUserStorageMigrationNeeded())
                return false

            legacyMigrationLatch = CountDownLatch(1)
            legacyMigrationListeners += listener
        }

        Thread({
            val result = migrateLegacyUserStorage()

            val listeners = synchronized(legacyMigrationLock) {
                // A failed attempt is retried on the next app start, not on every onResume().
                if (result is LegacyStorageMigrationResult.Failed ||
                    result is LegacyStorageMigrationResult.NotEnoughSpace) {
                    legacyMigrationAttemptFailed = true
                }

                legacyMigrationLatch?.countDown()
                legacyMigrationLatch = null
                legacyMigrationListeners.toList().also { legacyMigrationListeners.clear() }
            }

            Handler(Looper.getMainLooper()).post {
                listeners.forEach { it(result) }
            }
        }, "OpenMW-storage-migration").start()

        return true
    }

    fun removeLegacyUserStorageMigrationListener(listener: (LegacyStorageMigrationResult) -> Unit) {
        synchronized(legacyMigrationLock) {
            legacyMigrationListeners.remove(listener)
        }
    }

    /** True while the legacy tree is being moved; the game must not start meanwhile. */
    fun isLegacyUserStorageMigrationRunning(): Boolean =
        synchronized(legacyMigrationLock) { legacyMigrationLatch != null }

    /** Blocks until a running migration has finished. Not for the main thread. */
    fun awaitLegacyUserStorageMigration() {
        val latch = synchronized(legacyMigrationLock) { legacyMigrationLatch }
        latch?.await()
    }

    /** Cheap check (a few stat calls) whether the legacy tree still has to be moved. */
    private fun legacyUserStorageMigrationNeeded(): Boolean {
        if (legacyMigrationAttemptFailed)
            return false

        // The API 35 Automotive flavor has scoped storage and no legacy tree to read.
        if (com.libopenmw.openmw.BuildConfig.IS_AUTOMOTIVE_BUILD &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return false

        if (PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(PREF_LEGACY_MIGRATION_DONE, false))
            return false

        // Without the storage permission the legacy tree can look empty instead of
        // unreadable; it must not be "migrated" in that state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED)
            return false

        return try {
            val legacy = legacyUserStorageDirectory()
            // Never attempt to migrate if paths unexpectedly resolve to the same place.
            legacy.isDirectory &&
                legacy.canonicalFile != File(Constants.USER_FILE_STORAGE).canonicalFile
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            // Expected before legacy storage permission is granted.
            false
        }
    }

    private fun legacyUserStorageDirectory(): File =
        File(Environment.getExternalStorageDirectory(), LEGACY_USER_STORAGE_DIRECTORY)

    private fun migrateLegacyUserStorage(): LegacyStorageMigrationResult {
        val legacy = legacyUserStorageDirectory()
        val target = File(Constants.USER_FILE_STORAGE)

        return try {
            if (!legacy.isDirectory)
                return LegacyStorageMigrationResult.NotNeeded

            when (val result = LegacyStorageMigrator().migrate(legacy, target) { markLegacyUserStorageMigrated() }) {
                is LegacyStorageMigrator.Result.Migrated -> {
                    Log.i(TAG, "Legacy OpenMW storage migrated: ${legacy.absolutePath} -> ${target.absolutePath}")
                    if (legacy.exists())
                        Log.w(TAG, "Migration succeeded, but the old directory could not be removed: ${legacy.absolutePath}")
                    result.conflictDirectory?.let {
                        Log.w(TAG, "Differing legacy files were kept in ${it.absolutePath}")
                    }
                    LegacyStorageMigrationResult.Migrated(result.conflictDirectory)
                }
                is LegacyStorageMigrator.Result.NotEnoughSpace -> {
                    Log.w(TAG, "Not enough space to migrate legacy OpenMW storage; ${result.requiredBytes} bytes needed.")
                    LegacyStorageMigrationResult.NotEnoughSpace(legacy, result.requiredBytes)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Legacy /omw migration failed; keeping the old directory untouched.", e)
            LegacyStorageMigrationResult.Failed(legacy, e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            Log.e(TAG, "Legacy /omw migration is not permitted; keeping the old directory untouched.", e)
            LegacyStorageMigrationResult.Failed(legacy, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun markLegacyUserStorageMigrated() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean(PREF_LEGACY_MIGRATION_DONE, true)
            .commit()
    }

    private fun isProductionBuild(): Boolean {
        val packageInfo = applicationContext.packageManager.getPackageInfo(
            applicationContext.packageName,
            PackageManager.GET_SIGNATURES
        )
        val sig = packageInfo.signatures?.firstOrNull() ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(sig.toByteArray())
        val hash = Base64.encodeToString(hashBytes, Base64.NO_WRAP)
        return hash == "cOqSYH3ucLraOQ7wyg/v8UKTGHlxP8N8JTN6UXO7rV0="
    }

    companion object {
        private const val TAG = "OpenMW-Storage"
        private const val USER_STORAGE_DIRECTORY = "OpenMW"
        private const val LEGACY_USER_STORAGE_DIRECTORY = "omw"
        private const val PREF_LEGACY_MIGRATION_DONE = "legacy_omw_storage_migrated"

        var reportCrashes = false
        var haveBugsnagApiKey = false
        lateinit var app: MyApp
    }
}
