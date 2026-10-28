/*
    Gives the Java controller code access to the OpenMW 0.51 GUI cursor flag.

    The engine only lets A act on the focused menu entry while that flag is off,
    and nothing reachable from Java or Lua clears it. The symbols are resolved
    from the already-loaded libopenmw.so; the library itself is not modified.
*/

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <stddef.h>

#define TAG "OpenMW-Handheld"

/* MWBase::Environment::mWindowManager, the seventh pointer member in 0.51.0. */
#define WINDOW_MANAGER_OFFSET 0x30

typedef void (*SetCursorVisibleFn)(void* windowManager, bool visible);
typedef bool (*IsGuiModeFn)(const void* windowManager);

static bool sResolved = false;
static void** sEnvironment = NULL;
static SetCursorVisibleFn sSetCursorVisible = NULL;
static IsGuiModeFn sIsGuiMode = NULL;

static void* windowManager(void)
{
    if (!sResolved)
    {
        sResolved = true;
        void* lib = dlopen("libopenmw.so", RTLD_NOW | RTLD_NOLOAD);
        if (lib == NULL)
        {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "libopenmw.so is not loaded: %s", dlerror());
            return NULL;
        }
        sEnvironment = (void**)dlsym(lib, "_ZN6MWBase11Environment5sThisE");
        sSetCursorVisible = (SetCursorVisibleFn)dlsym(lib, "_ZN5MWGui13WindowManager16setCursorVisibleEb");
        sIsGuiMode = (IsGuiModeFn)dlsym(lib, "_ZNK5MWGui13WindowManager9isGuiModeEv");
        if (sEnvironment == NULL || sSetCursorVisible == NULL || sIsGuiMode == NULL)
            __android_log_print(ANDROID_LOG_ERROR, TAG, "OpenMW cursor symbols not found");
    }

    if (sEnvironment == NULL || sSetCursorVisible == NULL || sIsGuiMode == NULL)
        return NULL;
    char* environment = (char*)*sEnvironment;
    if (environment == NULL)
        return NULL;
    return *(void**)(environment + WINDOW_MANAGER_OFFSET);
}

JNIEXPORT jboolean JNICALL Java_cursor_EngineCursor_nativeIsMenuOpen(JNIEnv* env, jclass cls)
{
    void* wm = windowManager();
    return wm != NULL && sIsGuiMode(wm);
}

JNIEXPORT jboolean JNICALL Java_cursor_EngineCursor_nativeSetVisible(JNIEnv* env, jclass cls, jboolean visible)
{
    void* wm = windowManager();
    if (wm == NULL)
        return JNI_FALSE;
    sSetCursorVisible(wm, visible == JNI_TRUE);
    return JNI_TRUE;
}
