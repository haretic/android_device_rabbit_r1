# SPDX-License-Identifier: Apache-2.0
# The haretic version, as the image is named: haretic-YYYYMMDD-<style>, the style one of
# nightly, beta and release, nightly unless HARETIC_RELEASE says otherwise
ifndef HARETIC_VERSION
HARETIC_RELEASE ?= nightly
ifeq ($(filter nightly beta release,$(HARETIC_RELEASE)),)
$(error HARETIC_RELEASE is nightly, beta or release, not "$(HARETIC_RELEASE)")
endif
HARETIC_VERSION := haretic-$(shell date -u +%Y%m%d)-$(HARETIC_RELEASE)
endif
