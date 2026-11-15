# SPDX-License-Identifier: Apache-2.0
# Explicit experimental product; the installer verifies its kernel SOC config.
# haretic's own defaults, ahead of LineageOS's product overlay: set before the inherit, as
# overlays listed first win and inherited ones come after
PRODUCT_PACKAGE_OVERLAYS += device/rabbit/r1/overlay-haretic
# haretic's sounds under the names AOSP gives the system's own (lock, unlock, charging, low
# battery, touch, keys), ahead of AOSP's for the same reason: of two files copied to one place
# the first listed goes in. Made by r1-tools scripts/install-haretic-sounds.py.
PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*.ogg,device/rabbit/r1/sounds/ui,$(TARGET_COPY_OUT_PRODUCT)/media/audio/ui) \
    $(call find-copy-subdir-files,*.ogg,device/rabbit/r1/sounds/notifications,$(TARGET_COPY_OUT_PRODUCT)/media/audio/notifications)
# The notification sound a new install starts with, where LineageOS only suggests its own
PRODUCT_PRODUCT_PROPERTIES += ro.config.notification_sound=haretic.ogg

$(call inherit-product, device/rabbit/r1/lineage_r1.mk)
PRODUCT_NAME := haretic_r1
# Apps show "<manufacturer|brand> <model>": haretic rabbit r1.
PRODUCT_BRAND := haretic
PRODUCT_MANUFACTURER := haretic
PRODUCT_MODEL := rabbit r1
