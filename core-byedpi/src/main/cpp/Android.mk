# byedpi собирается исполняемым файлом: у него глобальное состояние (params, getopt),
# поэтому каждый экземпляр прокси — отдельный процесс. Файл кладётся в APK как libbyedpi.so,
# чтобы установщик распаковал его в nativeLibraryDir, откуда разрешён запуск.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := byedpi
LOCAL_SRC_FILES := \
    byedpi/packets.c \
    byedpi/main.c \
    byedpi/conev.c \
    byedpi/proxy.c \
    byedpi/desync.c \
    byedpi/mpool.c \
    byedpi/extend.c \
    tube_dns.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/byedpi
LOCAL_CFLAGS := -std=c99 -O2 -D_DEFAULT_SOURCE -Wall -Wno-unused -Wno-unused-parameter
# getaddrinfo → резолвинг через DNS-сервер приложения (DoH), см. tube_dns.c.
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--wrap=getaddrinfo
include $(BUILD_EXECUTABLE)
