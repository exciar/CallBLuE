LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := bluespeex
# Unmodified libspeex 1.2.1 (BSD-3, see speex/COPYING), codec only.
SPEEX := speex/libspeex
LOCAL_SRC_FILES := speex_jni.c \
    $(SPEEX)/bits.c $(SPEEX)/cb_search.c $(SPEEX)/exc_10_16_table.c $(SPEEX)/exc_10_32_table.c \
    $(SPEEX)/exc_20_32_table.c $(SPEEX)/exc_5_256_table.c $(SPEEX)/exc_5_64_table.c $(SPEEX)/exc_8_128_table.c \
    $(SPEEX)/filters.c $(SPEEX)/gain_table.c $(SPEEX)/gain_table_lbr.c $(SPEEX)/hexc_10_32_table.c \
    $(SPEEX)/hexc_table.c $(SPEEX)/high_lsp_tables.c $(SPEEX)/lpc.c $(SPEEX)/lsp.c $(SPEEX)/lsp_tables_nb.c \
    $(SPEEX)/ltp.c $(SPEEX)/modes.c $(SPEEX)/modes_wb.c $(SPEEX)/nb_celp.c $(SPEEX)/quant_lsp.c \
    $(SPEEX)/sb_celp.c $(SPEEX)/speex.c $(SPEEX)/speex_callbacks.c $(SPEEX)/speex_header.c \
    $(SPEEX)/stereo.c $(SPEEX)/vbr.c $(SPEEX)/vq.c $(SPEEX)/window.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/speex/include $(LOCAL_PATH)/speex/libspeex
LOCAL_CFLAGS := -DFLOATING_POINT -DVAR_ARRAYS -DEXPORT= -O2 -Wno-unused-variable -Wno-unused-but-set-variable
# 16 KB page alignment, required on Android 15+ devices that use large pages.
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
