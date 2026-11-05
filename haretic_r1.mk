# SPDX-License-Identifier: Apache-2.0
# Explicit experimental product; the installer verifies its kernel SOC config.
# haretic's own defaults, ahead of LineageOS's product overlay: set before the inherit, as
# overlays listed first win and inherited ones come after
PRODUCT_PACKAGE_OVERLAYS += device/rabbit/r1/overlay-haretic

$(call inherit-product, device/rabbit/r1/lineage_r1.mk)
PRODUCT_NAME := haretic_r1
# Apps show "<manufacturer|brand> <model>": haretic rabbit r1.
PRODUCT_BRAND := haretic
PRODUCT_MANUFACTURER := haretic
PRODUCT_MODEL := rabbit r1
include device/rabbit/r1/version.mk
PRODUCT_SYSTEM_PROPERTIES += ro.haretic.version=$(HARETIC_VERSION)
