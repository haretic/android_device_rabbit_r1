/*
 * wpa_supplicant driver commands for the MediaTek gen4m Wi-Fi driver.
 *
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Android sends driver commands (SETSUSPENDMODE on screen off, COUNTRY, ...)
 * through ISupplicantStaIface; without a private lib wpa_supplicant fails
 * them all. gen4m takes them as SIOCDEVPRIVATE+1 with the command inline in
 * struct priv_driver_cmd_s (gl_wext_priv.c), not behind a pointer.
 */

#include "includes.h"
#include <sys/ioctl.h>
#include <net/if.h>

#include "common.h"
#include "linux_ioctl.h"
#include "driver_nl80211.h"
#include "wpa_supplicant_i.h"
#include "android_drv.h"

/* gen4m PRIV_CMD_SIZE */
#define GEN4M_PRIV_CMD_SIZE 512

struct gen4m_priv_cmd {
	char buf[GEN4M_PRIV_CMD_SIZE];
	int used_len;
	int total_len;
};

static void notify_country_change(void *ctx, const char *cmd)
{
	union wpa_event_data event;

	if (os_strncasecmp(cmd, "COUNTRY", 7) != 0 &&
	    os_strncasecmp(cmd, "SETBAND", 7) != 0)
		return;

	os_memset(&event, 0, sizeof(event));
	event.channel_list_changed.initiator = REGDOM_SET_BY_USER;
	if (os_strncasecmp(cmd, "COUNTRY", 7) == 0) {
		event.channel_list_changed.type = REGDOM_TYPE_COUNTRY;
		if (os_strlen(cmd) > 9) {
			event.channel_list_changed.alpha2[0] = cmd[8];
			event.channel_list_changed.alpha2[1] = cmd[9];
		}
	} else {
		event.channel_list_changed.type = REGDOM_TYPE_UNKNOWN;
	}
	wpa_supplicant_event(ctx, EVENT_CHANNEL_LIST_CHANGED, &event);
}

int wpa_driver_nl80211_driver_cmd(void *priv, char *cmd, char *buf,
				  size_t buf_len)
{
	struct i802_bss *bss = priv;
	struct wpa_driver_nl80211_data *drv = bss->drv;
	struct gen4m_priv_cmd priv_cmd;
	struct ifreq ifr;
	size_t len;
	int ret;

	if (os_strcasecmp(cmd, "STOP") == 0) {
		linux_set_iface_flags(drv->global->ioctl_sock, bss->ifname, 0);
		wpa_msg(drv->ctx, MSG_INFO, WPA_EVENT_DRIVER_STATE "STOPPED");
		return 0;
	}
	if (os_strcasecmp(cmd, "START") == 0) {
		linux_set_iface_flags(drv->global->ioctl_sock, bss->ifname, 1);
		wpa_msg(drv->ctx, MSG_INFO, WPA_EVENT_DRIVER_STATE "STARTED");
		return 0;
	}
	if (os_strcasecmp(cmd, "MACADDR") == 0) {
		u8 macaddr[ETH_ALEN] = {};

		ret = linux_get_ifhwaddr(drv->global->ioctl_sock, bss->ifname,
					 macaddr);
		if (!ret)
			ret = os_snprintf(buf, buf_len, "Macaddr = " MACSTR "\n",
					  MAC2STR(macaddr));
		return ret;
	}

	/* buf_len is the size of the reply buffer, not of the command */
	len = os_strlen(cmd) + 1;
	if (len > sizeof(priv_cmd.buf)) {
		wpa_printf(MSG_ERROR, "%s: command too long (%zu): %s",
			   __func__, len, cmd);
		return -1;
	}

	os_memset(&priv_cmd, 0, sizeof(priv_cmd));
	os_memcpy(priv_cmd.buf, cmd, len);
	priv_cmd.used_len = len;
	priv_cmd.total_len = sizeof(priv_cmd.buf);

	os_memset(&ifr, 0, sizeof(ifr));
	os_strlcpy(ifr.ifr_name, bss->ifname, IFNAMSIZ);
	ifr.ifr_data = (void *)&priv_cmd;

	ret = ioctl(drv->global->ioctl_sock, SIOCDEVPRIVATE + 1, &ifr);
	if (ret < 0) {
		wpa_printf(MSG_ERROR, "%s: private command %s failed: %s",
			   __func__, cmd, strerror(errno));
		return ret;
	}

	priv_cmd.buf[sizeof(priv_cmd.buf) - 1] = '\0';
	notify_country_change(drv->ctx, cmd);
	if (buf_len == 0)
		return 0;
	os_strlcpy(buf, priv_cmd.buf, buf_len);
	ret = os_strlen(buf);
	wpa_printf(MSG_DEBUG, "%s: %s -> %s", __func__, cmd, buf);
	return ret;
}

int wpa_driver_set_p2p_noa(void *priv, u8 count, int start, int duration)
{
	char buf[MAX_DRV_CMD_SIZE];

	os_snprintf(buf, sizeof(buf), "P2P_SET_NOA %d %d %d", count, start,
		    duration);
	return wpa_driver_nl80211_driver_cmd(priv, buf, buf, os_strlen(buf) + 1);
}

int wpa_driver_get_p2p_noa(void *priv, u8 *buf, size_t len)
{
	/* gen4m reports NoA in its beacons itself */
	return 0;
}

int wpa_driver_set_p2p_ps(void *priv, int legacy_ps, int opp_ps, int ctwindow)
{
	char buf[MAX_DRV_CMD_SIZE];

	os_snprintf(buf, sizeof(buf), "P2P_SET_PS %d %d %d", legacy_ps, opp_ps,
		    ctwindow);
	return wpa_driver_nl80211_driver_cmd(priv, buf, buf, os_strlen(buf) + 1);
}

int wpa_driver_set_ap_wps_p2p_ie(void *priv, const struct wpabuf *beacon,
				 const struct wpabuf *proberesp,
				 const struct wpabuf *assocresp)
{
	/* gen4m takes these IEs from cfg80211 (start_ap / change_beacon) */
	return 0;
}
