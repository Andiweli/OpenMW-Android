package cursor;

import android.util.Log;

/**
 * The OpenMW 0.51 GUI cursor flag, through libhandheld.
 * While the flag is on, A clicks at the cursor instead of activating the
 * focused menu entry. Every call is a no-op before the engine is running.
 */
public final class EngineCursor {
    private static final String TAG = "EngineCursor";
    private static boolean sLoaded;

    static {
        try {
            System.loadLibrary("handheld");
            sLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libhandheld is missing", e);
        }
    }

    private EngineCursor() {
    }

    private static native boolean nativeIsMenuOpen();

    private static native boolean nativeSetVisible(boolean visible);

    public static boolean isMenuOpen() {
        return sLoaded && nativeIsMenuOpen();
    }

    public static void setVisible(boolean visible) {
        if (sLoaded) {
            nativeSetVisible(visible);
        }
    }
}
