package com.codekidlabs.storagechooser.utils;

import com.codekidlabs.storagechooser.models.Storages;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Path helpers for the storage overview. Free of Android dependencies so they can be
 * covered by plain JVM unit tests.
 */
public final class StoragePathUtil {

    private StoragePathUtil() {
    }

    /**
     * Android places the app-specific directory of every mounted shared volume at
     * {@code <volume root>/Android/data/<package>/files} (see Context.getExternalFilesDirs()).
     * This strips exactly that suffix to obtain the volume root.
     *
     * @return the volume root, or null if the directory does not have the expected layout
     */
    public static File volumeRootFromAppSpecificDir(File appSpecificDir, String packageName) {
        if (appSpecificDir == null || packageName == null || packageName.isEmpty()) {
            return null;
        }

        String path = appSpecificDir.getAbsolutePath();
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String suffix = "/Android/data/" + packageName + "/files";
        if (!path.endsWith(suffix) || path.length() == suffix.length()) {
            return null;
        }

        return new File(path.substring(0, path.length() - suffix.length()));
    }

    /**
     * @return true if {@code path} equals {@code root} or lies below it. Compares whole
     * path segments, so "/storage/AB12" does not contain "/storage/AB12-CD34".
     */
    public static boolean isSameOrInside(String path, String root) {
        if (path == null || root == null || root.isEmpty()) {
            return false;
        }

        String normalizedPath = stripTrailingSlashes(path);
        String normalizedRoot = stripTrailingSlashes(root);
        if (normalizedPath.equals(normalizedRoot)) {
            return true;
        }
        if (normalizedRoot.equals("/")) {
            return normalizedPath.startsWith("/");
        }
        return normalizedPath.startsWith(normalizedRoot + "/");
    }

    /**
     * Finds the storage whose mount path is the longest prefix of {@code path}.
     *
     * @return the matching storage, or null if the path is not on any listed storage
     */
    public static Storages findStorageContaining(List<Storages> storages, String path) {
        Storages best = null;
        int bestLength = -1;

        for (Storages storage : storages) {
            String root = storage.getStoragePath();
            if (root == null || !isSameOrInside(path, root)) {
                continue;
            }

            int length = stripTrailingSlashes(root).length();
            if (length > bestLength) {
                best = storage;
                bestLength = length;
            }
        }

        return best;
    }

    /**
     * Makes the display titles of non-primary storages distinguishable. The storage id
     * is appended first; if titles still collide the mount path is appended as well.
     * Only the titles change, never the paths.
     */
    public static void makeTitlesUnique(List<Storages> storages) {
        for (Storages storage : storages) {
            if (!storage.isPrimary() && storage.getStorageId() != null
                    && !storage.getStorageId().isEmpty()
                    && !storage.getStorageTitle().contains(storage.getStorageId())) {
                storage.setStorageTitle(storage.getStorageTitle() + " · " + storage.getStorageId());
            }
        }

        Map<String, Integer> counts = new HashMap<>();
        for (Storages storage : storages) {
            Integer count = counts.get(storage.getStorageTitle());
            counts.put(storage.getStorageTitle(), count == null ? 1 : count + 1);
        }

        int unnamed = 0;
        for (Storages storage : storages) {
            Integer count = counts.get(storage.getStorageTitle());
            if (storage.isPrimary() || count == null || count < 2) {
                continue;
            }

            if (storage.getStoragePath() != null) {
                storage.setStorageTitle(storage.getStorageTitle() + " · " + storage.getStoragePath());
            } else {
                unnamed++;
                storage.setStorageTitle(storage.getStorageTitle() + " · " + unnamed);
            }
        }
    }

    private static String stripTrailingSlashes(String path) {
        String result = path;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
