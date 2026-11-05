# SPDX-License-Identifier: Apache-2.0
# Explicit experimental product; the installer verifies its kernel SOC config.
$(call inherit-product, device/rabbit/r1/lineage_r1.mk)
PRODUCT_NAME := haretic_r1
# Apps show "<manufacturer|brand> <model>": haretic rabbit r1.
PRODUCT_BRAND := haretic
PRODUCT_MANUFACTURER := haretic
PRODUCT_MODEL := rabbit r1
# The haretic version, as the image is named: haretic-YYYYMMDD-<style>, the style one of
# nightly, beta and release, nightly unless HARETIC_RELEASE says otherwise
HARETIC_RELEASE ?= nightly
ifeq ($(filter nightly beta release,$(HARETIC_RELEASE)),)
$(error HARETIC_RELEASE is nightly, beta or release, not "$(HARETIC_RELEASE)")
endif
HARETIC_VERSION := haretic-$(shell date -u +%Y%m%d)-$(HARETIC_RELEASE)
PRODUCT_SYSTEM_PROPERTIES += ro.haretic.version=$(HARETIC_VERSION)
