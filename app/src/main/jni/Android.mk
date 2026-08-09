LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := duckusb
LOCAL_SRC_FILES := native_hooks.cpp
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
