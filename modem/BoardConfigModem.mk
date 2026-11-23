# SPDX-License-Identifier: Apache-2.0
# Board side of modem.mk; include from BoardConfig.mk together with it.

R1_MODEM_PATH := device/rabbit/r1/modem

# The stock modem blobs are installed verbatim (PRODUCT_COPY_FILES of ELFs).
BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES := true

BOARD_VENDOR_SEPOLICY_DIRS += $(R1_MODEM_PATH)/sepolicy
# The IMS service's side: its binder services and the property it sets (ims.mk)
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(R1_MODEM_PATH)/sepolicy-system_ext

DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += \
    $(R1_MODEM_PATH)/framework_compatibility_matrix-modem.xml
