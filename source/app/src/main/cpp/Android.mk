LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := handheld
LOCAL_SRC_FILES := handheld.c
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_SHARED_LIBRARY)
