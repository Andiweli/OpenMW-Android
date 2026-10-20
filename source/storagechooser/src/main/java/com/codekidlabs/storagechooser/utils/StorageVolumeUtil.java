package com.codekidlabs.storagechooser.utils;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import com.codekidlabs.storagechooser.R;
import com.codekidlabs.storagechooser.models.Storages;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists the shared storage volumes that can be offered in the storage overview.
 *
 * Sources, in order of preference:
 * <ul>
 *     <li>primary shared storage via Environment (always first, as before);</li>
 *     <li>API 30+: StorageManager.getStorageVolumes() with StorageVolume.getDirectory();</li>
 *     <li>API 21-29: roots of Context.getExternalFilesDirs(), described through
 *     StorageManager.getStorageVolume(File) on API 24+ or Environment on API 21-23;</li>
 *     <li>API 21-29: the historic "/storage" listing, which only stops working on Android 11;</li>
 *     <li>API 24-29: volumes StorageManager knows about but that have no usable path.</li>
 * </ul>
 * Only public SDK APIs are used. A listed volume is "detected", which does not prove that
 * this app may read it; callers have to check that separately.
 */
public final class StorageVolumeUtil {

    public static final String PRIMARY_ID = "primary";

    private static final String TAG = "StorageChooser";

    private static final String[] LEGACY_IGNORED_NAMES = {
            MemoryUtil.SELF_DIR_NAME,
            MemoryUtil.EMULATED_DIR_NAME,
            MemoryUtil.EMULATED_DIR_KNOX,
            MemoryUtil.SDCARD0_DIR_NAME,
            MemoryUtil.CONTAINER
    };

    private StorageVolumeUtil() {
    }

    /**
     * @param internalStorageTitle display title of the primary shared storage
     * @return detected storages; the primary shared storage is always the first entry
     */
    public static List<Storages> getStorages(Context context, String internalStorageTitle) {
        Context appContext = context.getApplicationContext();
        List<Storages> result = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        Set<String> seenIds = new HashSet<>();

        Storages primary = new Storages();
        primary.setStorageTitle(internalStorageTitle);
        primary.setStorageId(PRIMARY_ID);
        primary.setPrimary(true);
        primary.setStoragePath(Environment.getExternalStorageDirectory().getAbsolutePath());
        applyState(primary, Environment.getExternalStorageState());
        add(result, seenPaths, seenIds, primary);

        StorageManager storageManager = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageManager = (StorageManager) appContext.getSystemService(Context.STORAGE_SERVICE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (StorageVolume volume : storageVolumes(storageManager)) {
                add(result, seenPaths, seenIds, fromVolume(appContext, volume, volume.getDirectory()));
            }
        } else {
            for (File root : appSpecificVolumeRoots(appContext)) {
                add(result, seenPaths, seenIds, describe(appContext, storageManager, root));
            }

            for (File root : legacyStorageDirs()) {
                add(result, seenPaths, seenIds, describe(appContext, storageManager, root));
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (StorageVolume volume : storageVolumes(storageManager)) {
                    if (volume.isPrimary()) {
                        continue;
                    }
                    String uuid = volume.getUuid();
                    if (uuid != null && seenIds.contains(uuid)) {
                        continue;
                    }
                    // No public API before Android 11 returns this volume's path.
                    add(result, seenPaths, seenIds, fromVolume(appContext, volume, null));
                }
            }
        }

        StoragePathUtil.makeTitlesUnique(result);

        for (Storages storage : result) {
            Log.i(TAG, "Storage: id=" + storage.getStorageId()
                    + "; path=" + storage.getStoragePath()
                    + "; state=" + storage.getStorageState()
                    + "; available=" + storage.isAvailable()
                    + "; readOnly=" + storage.isReadOnly()
                    + "; removable=" + storage.isRemovable()
                    + "; title=" + storage.getStorageTitle());
        }

        return result;
    }

    /**
     * @return the detected storage that contains {@code path}, or null if none does
     */
    public static Storages findStorageContaining(Context context, String path) {
        return StoragePathUtil.findStorageContaining(getStorages(context, ""), path);
    }

    /**
     * Probes whether the directory can actually be listed by this app. Only the
     * directory itself is listed, not its children.
     */
    public static boolean isListable(File directory) {
        try {
            return directory.list() != null;
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * @return true for media states in which the volume can be read
     */
    public static boolean isReadableState(String state) {
        return Environment.MEDIA_MOUNTED.equals(state)
                || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    /**
     * @return true for media states which mean the volume is definitely not usable now
     */
    public static boolean isUnavailableState(String state) {
        return Environment.MEDIA_REMOVED.equals(state)
                || Environment.MEDIA_UNMOUNTED.equals(state)
                || Environment.MEDIA_CHECKING.equals(state)
                || Environment.MEDIA_NOFS.equals(state)
                || Environment.MEDIA_SHARED.equals(state)
                || Environment.MEDIA_BAD_REMOVAL.equals(state)
                || Environment.MEDIA_UNMOUNTABLE.equals(state)
                || Environment.MEDIA_EJECTING.equals(state);
    }

    private static List<StorageVolume> storageVolumes(StorageManager storageManager) {
        if (storageManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return Collections.emptyList();
        }
        try {
            return storageManager.getStorageVolumes();
        } catch (RuntimeException e) {
            Log.w(TAG, "StorageManager.getStorageVolumes() failed", e);
            return Collections.emptyList();
        }
    }

    private static Storages fromVolume(Context context, StorageVolume volume, File directory) {
        Storages storage = new Storages();
        storage.setPrimary(volume.isPrimary());
        storage.setRemovable(volume.isRemovable());

        String uuid = volume.getUuid();
        if (volume.isPrimary()) {
            storage.setStorageId(PRIMARY_ID);
        } else if (uuid != null) {
            storage.setStorageId(uuid);
        } else if (directory != null) {
            storage.setStorageId(directory.getName());
        }

        String description = volume.getDescription(context);
        if (description == null || description.trim().isEmpty()) {
            description = defaultTitle(context, volume.isRemovable());
        }
        storage.setStorageTitle(description);
        storage.setStoragePath(directory != null ? directory.getAbsolutePath() : null);
        applyState(storage, volume.getState());
        return storage;
    }

    private static Storages describe(Context context, StorageManager storageManager, File root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && storageManager != null) {
            StorageVolume volume = null;
            try {
                volume = storageManager.getStorageVolume(root);
            } catch (RuntimeException e) {
                Log.w(TAG, "StorageManager.getStorageVolume() failed for " + root, e);
            }
            if (volume != null) {
                return fromVolume(context, volume, root);
            }
        }

        Storages storage = new Storages();
        boolean removable = false;
        try {
            removable = Environment.isExternalStorageRemovable(root);
        } catch (IllegalArgumentException e) {
            // Not a path Android knows as shared storage; keep the default.
        }
        storage.setRemovable(removable);
        storage.setStorageId(root.getName());
        storage.setStorageTitle(defaultTitle(context, removable));
        storage.setStoragePath(root.getAbsolutePath());
        applyState(storage, Environment.getExternalStorageState(root));
        return storage;
    }

    private static String defaultTitle(Context context, boolean removable) {
        return context.getString(removable ? R.string.sc_sd_card : R.string.external_storage);
    }

    private static void applyState(Storages storage, String state) {
        storage.setStorageState(state);
        // MEDIA_UNKNOWN is kept selectable: older devices may not report a state for
        // vendor mount points that were usable before; the read probe decides then.
        storage.setAvailable(!isUnavailableState(state));
        storage.setReadOnly(Environment.MEDIA_MOUNTED_READ_ONLY.equals(state));
    }

    private static List<File> appSpecificVolumeRoots(Context context) {
        List<File> roots = new ArrayList<>();
        File[] dirs;
        try {
            dirs = context.getExternalFilesDirs(null);
        } catch (RuntimeException e) {
            Log.w(TAG, "getExternalFilesDirs() failed", e);
            return roots;
        }
        if (dirs == null) {
            return roots;
        }

        for (File dir : dirs) {
            // Entries are null for volumes that are currently not available.
            File root = StoragePathUtil.volumeRootFromAppSpecificDir(dir, context.getPackageName());
            if (root != null) {
                roots.add(root);
            } else if (dir != null) {
                Log.w(TAG, "Unexpected app-specific directory layout: " + dir);
            }
        }
        return roots;
    }

    private static List<File> legacyStorageDirs() {
        List<File> roots = new ArrayList<>();
        File[] entries = new File("/storage").listFiles();
        // This is null on Android 11 and newer.
        if (entries == null) {
            return roots;
        }

        for (File entry : entries) {
            boolean ignored = false;
            for (String name : LEGACY_IGNORED_NAMES) {
                if (name.equals(entry.getName())) {
                    ignored = true;
                    break;
                }
            }
            if (!ignored) {
                roots.add(entry);
            }
        }
        return roots;
    }

    private static void add(List<Storages> result, Set<String> seenPaths, Set<String> seenIds,
                            Storages storage) {
        if (storage.getStorageTitle() == null) {
            storage.setStorageTitle("");
        }

        String path = storage.getStoragePath();
        String pathKey = path != null ? canonical(path) : null;
        String id = storage.getStorageId();

        if (pathKey != null && seenPaths.contains(pathKey)) {
            return;
        }
        if (id != null && seenIds.contains(id)) {
            return;
        }

        if (pathKey != null) {
            seenPaths.add(pathKey);
        }
        if (id != null) {
            seenIds.add(id);
        }
        result.add(storage);
    }

    private static String canonical(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException | SecurityException e) {
            return new File(path).getAbsolutePath();
        }
    }
}
