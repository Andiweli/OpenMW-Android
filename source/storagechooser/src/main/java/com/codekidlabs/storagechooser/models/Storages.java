package com.codekidlabs.storagechooser.models;

/**
 * A model that is used as the element to store any data regarding storage volumes/disks.
 *
 * The display title, the storage id and the mount path are kept apart on purpose:
 * the title is only shown to the user and is never used to build a path.
 */
public class Storages {

    String storageTitle;
    String storagePath;
    String memoryTotalSize;
    String memoryAvailableSize;

    // Identifier of the volume (filesystem UUID, "primary" or the mount directory name).
    String storageId;
    // Raw android.os.Environment media state, e.g. "mounted" or "unmounted".
    String storageState;
    boolean available = true;
    boolean readOnly;
    boolean primary;
    boolean removable;


    public String getStorageTitle() {
        return storageTitle;
    }

    public void setStorageTitle(String storageTitle) {
        this.storageTitle = storageTitle;
    }

    /**
     * @return the mount path reported by Android, or null if the volume has no
     * accessible path (for example because it is not mounted)
     */
    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getMemoryTotalSize() {
        return memoryTotalSize;
    }

    public void setMemoryTotalSize(String memoryTotalSize) {
        this.memoryTotalSize = memoryTotalSize;
    }

    public String getMemoryAvailableSize() {
        return memoryAvailableSize;
    }

    public void setMemoryAvailableSize(String memoryAvailableSize) {
        this.memoryAvailableSize = memoryAvailableSize;
    }

    public String getStorageId() {
        return storageId;
    }

    public void setStorageId(String storageId) {
        this.storageId = storageId;
    }

    public String getStorageState() {
        return storageState;
    }

    public void setStorageState(String storageState) {
        this.storageState = storageState;
    }

    /**
     * @return true if the volume is mounted (possibly read-only) and has a path.
     * This only means the storage was detected; it does not prove read access.
     */
    public boolean isAvailable() {
        return available && storagePath != null;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public boolean isRemovable() {
        return removable;
    }

    public void setRemovable(boolean removable) {
        this.removable = removable;
    }
}
