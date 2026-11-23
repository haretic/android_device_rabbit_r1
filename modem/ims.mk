# SPDX-License-Identifier: Apache-2.0
# IMS (VoLTE) on the stock MediaTek RIL. The modem firmware has the IMS stack and rild serves
# IMtkRadioEx/imsSlot1; rabbitOS shipped neither the SIP daemons nor an IMS service, so both
# come from the LineageOS MT6765 trees (vendor/rabbit/r1-ims/blobs.txt), with LineageOS's
# hardware/mediatek for the framework classes the IMS service is written against.
# Voice only: no video calls, no Wi-Fi calling, no RCS.

$(call inherit-product, vendor/rabbit/r1-ims/r1-ims.mk)
$(call inherit-product, hardware/lineage/compat/frameworks/compat.mk)
$(call inherit-product, hardware/mediatek/frameworks/mediatek-frameworks.mk)

# hardware/mediatek keeps its modules in a Soong namespace of its own
PRODUCT_SOONG_NAMESPACES += hardware/mediatek

# The daemons are arm32 (vendor/lib); their AOSP dependencies. libc++ and liblog come with
# gsm0710muxd (modem.mk).
PRODUCT_PACKAGES += \
    libcutils.vendor:32 \
    libcrypto.vendor:32

PRODUCT_PACKAGES += \
    R1ImsTelephony \
    R1ImsCarrierConfig

PRODUCT_COPY_FILES += \
    $(R1_MODEM_PATH)/ims/init.r1.ims.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.r1.ims.rc \
    $(R1_MODEM_PATH)/ims/permissions/privapp-permissions-com.mediatek.ims.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-com.mediatek.ims.xml \
    frameworks/native/data/etc/android.hardware.telephony.ims.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.telephony.ims.xml

# ro.vendor.md_auto_setup_ims: the modem brings up the IMS bearer itself and registers once
# the IMS service turns VoLTE on, as on stock.
PRODUCT_VENDOR_PROPERTIES += \
    ro.vendor.md_auto_setup_ims=1 \
    persist.vendor.ims_support=1 \
    persist.vendor.mims_support=1 \
    persist.vendor.volte_support=1 \
    persist.vendor.mtk.volte.enable=1 \
    persist.vendor.mtk_dynamic_ims_switch=1 \
    persist.vendor.mtk_ct_volte_support=3 \
    persist.vendor.vilte_support=0 \
    persist.vendor.viwifi_support=0 \
    persist.vendor.mtk_wfc_support=0
