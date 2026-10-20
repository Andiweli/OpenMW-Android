package com.codekidlabs.storagechooser;

import com.codekidlabs.storagechooser.models.Storages;
import com.codekidlabs.storagechooser.utils.StoragePathUtil;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class StoragePathUtilTest {

    private static Storages storage(String id, String title, String path, boolean primary) {
        Storages storage = new Storages();
        storage.setStorageId(id);
        storage.setStorageTitle(title);
        storage.setStoragePath(path);
        storage.setPrimary(primary);
        return storage;
    }

    @Test
    public void volumeRootIsDerivedFromAppSpecificDirectory() {
        assertEquals(new File("/storage/1A2B-3C4D"), StoragePathUtil.volumeRootFromAppSpecificDir(
                new File("/storage/1A2B-3C4D/Android/data/com.ast.openmw/files"), "com.ast.openmw"));
        assertEquals(new File("/storage/emulated/0"), StoragePathUtil.volumeRootFromAppSpecificDir(
                new File("/storage/emulated/0/Android/data/com.ast.openmw/files/"), "com.ast.openmw"));
    }

    @Test
    public void unexpectedLayoutsAreRejected() {
        assertNull(StoragePathUtil.volumeRootFromAppSpecificDir(
                new File("/storage/1A2B-3C4D/Android/data/other.app/files"), "com.ast.openmw"));
        assertNull(StoragePathUtil.volumeRootFromAppSpecificDir(
                new File("/Android/data/com.ast.openmw/files"), "com.ast.openmw"));
        assertNull(StoragePathUtil.volumeRootFromAppSpecificDir(null, "com.ast.openmw"));
    }

    @Test
    public void containmentRespectsPathSegments() {
        assertTrue(StoragePathUtil.isSameOrInside("/storage/AB12/Games/Morrowind", "/storage/AB12"));
        assertTrue(StoragePathUtil.isSameOrInside("/storage/AB12", "/storage/AB12/"));
        assertFalse(StoragePathUtil.isSameOrInside("/storage/AB12-CD34/Morrowind", "/storage/AB12"));
        assertFalse(StoragePathUtil.isSameOrInside("/storage/emulated/0/Morrowind", "/storage/AB12"));
    }

    @Test
    public void longestContainingStorageWins() {
        Storages primary = storage("primary", "Internal", "/storage/emulated/0", true);
        Storages card = storage("AB12-CD34", "SD card", "/storage/AB12-CD34", false);
        Storages unmounted = storage("EF56-7890", "USB", null, false);
        List<Storages> list = Arrays.asList(primary, card, unmounted);

        assertSame(card, StoragePathUtil.findStorageContaining(list, "/storage/AB12-CD34/Games/Morrowind"));
        assertSame(primary, StoragePathUtil.findStorageContaining(list, "/storage/emulated/0/Morrowind"));
        assertNull(StoragePathUtil.findStorageContaining(list, "/data/local/tmp/Morrowind"));
    }

    @Test
    public void equalTitlesBecomeDistinguishableWithoutTouchingPaths() {
        List<Storages> list = new ArrayList<>();
        list.add(storage("primary", "Internal", "/storage/emulated/0", true));
        list.add(storage("AB12-CD34", "SD card", "/storage/AB12-CD34", false));
        list.add(storage("EF56-7890", "SD card", "/storage/EF56-7890", false));

        StoragePathUtil.makeTitlesUnique(list);

        assertEquals("Internal", list.get(0).getStorageTitle());
        assertEquals("SD card · AB12-CD34", list.get(1).getStorageTitle());
        assertEquals("SD card · EF56-7890", list.get(2).getStorageTitle());
        assertEquals("/storage/AB12-CD34", list.get(1).getStoragePath());
        assertEquals("/storage/EF56-7890", list.get(2).getStoragePath());
    }

    @Test
    public void titlesWithoutIdFallBackToPathOrCounter() {
        List<Storages> list = new ArrayList<>();
        list.add(storage(null, "USB storage", "/storage/usb1", false));
        list.add(storage(null, "USB storage", "/storage/usb2", false));
        list.add(storage(null, "Card", null, false));
        list.add(storage(null, "Card", null, false));

        StoragePathUtil.makeTitlesUnique(list);

        assertEquals("USB storage · /storage/usb1", list.get(0).getStorageTitle());
        assertEquals("USB storage · /storage/usb2", list.get(1).getStorageTitle());
        assertNotEquals(list.get(2).getStorageTitle(), list.get(3).getStorageTitle());
    }

    @Test
    public void availabilityRequiresAPath() {
        Storages withoutPath = storage("AB12-CD34", "SD card", null, false);
        withoutPath.setAvailable(true);
        assertFalse(withoutPath.isAvailable());

        Storages unmounted = storage("AB12-CD34", "SD card", "/storage/AB12-CD34", false);
        unmounted.setAvailable(false);
        assertFalse(unmounted.isAvailable());
    }
}
