// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes personal things out of every line that goes into the report. First this r1's own
 * identifiers, looked up as the system user may: the serial number, IMEI and MEID, IMSI,
 * SIM serial and phone numbers, the saved Wi-Fi networks and the one in use, the Bluetooth
 * name and paired devices, the device name, the accounts and the MAC addresses. Then rules for
 * what looks like one anywhere (andr36oid's ProblemReport): Wi-Fi network names and keys,
 * e-mail addresses, serial numbers and IMEIs by their key or their check digit, the last half
 * of every MAC address, phone numbers, and every path or link into the shared storage (file
 * names).
 */
final class Scrubber {

    private static final String TAG = "ProblemReport";
    /** Shorter ones are words, not names. */
    private static final int MIN_LENGTH = 4;

    private interface Source {
        String get() throws Exception;
    }

    private static final class Rule {
        final Pattern pattern;
        final String replacement;

        Rule(String regex, String replacement) {
            pattern = Pattern.compile(regex);
            this.replacement = replacement;
        }
    }

    // Paths into the shared storage, the rules below cut them after the volume
    private static final String STORAGE = "(/storage/emulated/[0-9]+|/storage/self/primary"
            + "|/storage/[0-9A-Fa-f-]{9,36}|/sdcard|/mnt/media_rw/[0-9A-Fa-f-]+"
            + "|/data/media/[0-9]+|/mnt/user/[0-9]+/[A-Za-z0-9._-]+"
            + "|/mnt/pass_through/[0-9]+/[A-Za-z0-9._-]+|/mnt/runtime/[a-z]+/[A-Za-z0-9._-]+"
            + "|/mnt/installer/[0-9]+/[A-Za-z0-9._-]+"
            + "|/mnt/androidwritable/[0-9]+/[A-Za-z0-9._-]+)";
    // An identifier by its key: "serialno=", "lk_barcode=" (LK's command line), "mImei: "...
    // Lower-case keys must start a word, so "lifetimeIn" isn't taken for one.
    private static final String ID_KEY = "((?:[Ss]erial|SERIAL|[Bb]arcode|BARCODE"
            + "|(?<![A-Za-z])(?:imei|meid|imsi|iccid|msisdn|esn)"
            + "|Imei|IMEI|Meid|MEID|Imsi|IMSI|Iccid|ICCID|Msisdn|MSISDN)"
            + "[A-Za-z_.]*[\"']?\\s*[=:]\\s*[\"']?)";

    private static final Rule[] RULES = {
        // Wi-Fi network names, in their usual forms
        new Rule("([Ss][Ss][Ii][Dd][^\"'<>]{0,12})\"[^\"]*\"", "$1\"<ssid>\""),
        new Rule("([Ss][Ss][Ii][Dd][^\"'<>]{0,12})'[^']*'", "$1'<ssid>'"),
        new Rule("(extra: )\"[^\"]*\"", "$1\"<ssid>\""),
        new Rule("\"[^\"]*\"-?(WPA_PSK|WPA_EAP|IEEE8021X|NONE|SAE|OWE|WEP|SUITE_B_192"
                + "|WAPI_PSK|WAPI_CERT|DPP)", "\"<ssid>\"$1"),
        // Keys and passwords
        new Rule("([Pp][Ss][Kk]|[Pp]ass[Ww]ord|[Pp]assphrase|[Pp]re[Ss]hared[Kk]ey"
                + "|[Ww]ep[Kk]ey[s0-9]*)([\"']?\\s*[:=]\\s*)[^\\s,;)}]+", "$1$2<hidden>"),
        // Accounts and e-mail addresses
        new Rule("(Account\\s*\\{name=)[^,}]*", "$1<hidden>"),
        new Rule("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]*[A-Za-z][A-Za-z0-9-]*(\\.[A-Za-z0-9-]+)*"
                + "\\.[A-Za-z]{2,}", "<email>"),
        // Properties as getprop prints them, and settings as "settings list" does
        new Rule("^(\\[[^\\]]*(?:[Ss]erial|barcode|imei|meid|iccid|imsi|msisdn|hostname"
                + "|device_name|bt\\.name|ssid|wifimac|bdaddr)[^\\]]*\\]: \\[).*\\]$",
                "$1<hidden>]"),
        new Rule("^(device_name|bluetooth_name|bluetooth_address|[a-z_]*ssid[a-z_]*)=.*",
                "$1=<hidden>"),
        // Serial numbers, IMEIs and the like by their key
        new Rule(ID_KEY + "[^\\s,;)}\"'\\]]+", "$1<hidden>"),
        // The last half of MAC addresses: the first half is the maker
        new Rule("((?:[0-9A-Fa-f]{2}:){3})[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}",
                "$1xx:xx:xx"),
        // Phone numbers in international form
        new Rule("(?<![\\w+])\\+[1-9][0-9]{7,14}(?![0-9])", "<phone>"),
        // Links and paths into the shared storage: file names
        new Rule("(content://[A-Za-z0-9._-]+)/[^\\s\"']*", "$1/<hidden>"),
        new Rule("(primary(?:%3A|:))[^\\s\"'&]+", "$1<hidden>"),
        new Rule("\"" + STORAGE + "/[^\"]*\"", "\"$1/<hidden>\""),
        new Rule("'" + STORAGE + "/[^']*'", "'$1/<hidden>'"),
        // Without quotes the name can have spaces: cut to the end of the line
        new Rule(STORAGE + "/[^<].*$", "$1/<hidden>"),
    };

    /** 15 digits on their own: an IMEI if the check digit fits. */
    private static final Pattern FIFTEEN_DIGITS = Pattern.compile("(?<![0-9])[0-9]{15}(?![0-9])");

    /** This r1's identifiers and what they become. */
    private final Map<String, String> mValues;
    private final Pattern mLiterals;

    private Scrubber(Map<String, String> values) {
        mValues = values;
        final List<String> keys = new ArrayList<>(values.keySet());
        // The longest first, so a name that holds another one goes as a whole
        keys.sort((a, b) -> b.length() - a.length());
        final StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append('|');
            sb.append(Pattern.quote(k));
        }
        mLiterals = sb.length() > 0 ? Pattern.compile(sb.toString()) : null;
    }

    /** Looks this r1's identifiers up; whatever can't be read is left to the rules. */
    static Scrubber forDevice(Context context) {
        final Map<String, String> v = new HashMap<>();
        add(v, "<serial>", Build::getSerial);

        final TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        if (tm != null) {
            int modems = 1;
            try {
                modems = Math.max(1, tm.getActiveModemCount());
            } catch (Throwable t) {
                // no telephony
            }
            for (int slot = 0; slot < modems; slot++) {
                final int s = slot;
                add(v, "<imei>", () -> tm.getImei(s));
                add(v, "<meid>", () -> tm.getMeid(s));
            }
            add(v, "<imsi>", tm::getSubscriberId);
            add(v, "<iccid>", tm::getSimSerialNumber);
            add(v, "<phone>", tm::getLine1Number);
        }
        final SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
        if (sm != null) {
            try {
                final List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                if (subs != null) {
                    for (SubscriptionInfo info : subs) {
                        add(v, "<iccid>", info::getIccId);
                        add(v, "<phone>", () -> sm.getPhoneNumber(info.getSubscriptionId()));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "no subscriptions: " + t);
            }
        }

        final BluetoothManager bm = context.getSystemService(BluetoothManager.class);
        final BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter != null) {
            add(v, "<bt-name>", adapter::getName);
            addMac(v, "<bt-address>", adapter::getAddress);
            try {
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    add(v, "<bt-name>", d::getName);
                    add(v, "<bt-name>", d::getAlias);
                    addMac(v, "<bt-address>", d::getAddress);
                }
            } catch (Throwable t) {
                Log.w(TAG, "no paired devices: " + t);
            }
        }

        final WifiManager wm = context.getSystemService(WifiManager.class);
        if (wm != null) {
            try {
                for (WifiConfiguration c : wm.getPrivilegedConfiguredNetworks()) {
                    add(v, "<ssid>", () -> unquote(c.SSID));
                }
            } catch (Throwable t) {
                Log.w(TAG, "no saved networks: " + t);
            }
            try {
                final WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    add(v, "<ssid>", () -> unquote(info.getSSID()));
                    addMac(v, "<bssid>", info::getBSSID);
                    addMac(v, "<mac>", info::getMacAddress);
                }
                for (String mac : wm.getFactoryMacAddresses()) {
                    addMac(v, "<mac>", () -> mac);
                }
            } catch (Throwable t) {
                Log.w(TAG, "no Wi-Fi state: " + t);
            }
        }

        add(v, "<device-name>", () -> {
            final String name = Settings.Global.getString(context.getContentResolver(),
                    Settings.Global.DEVICE_NAME);
            // The default is the model, which the report has anyway
            return Build.MODEL.equals(name) ? null : name;
        });
        add(v, "<bt-name>", () -> Settings.Secure.getString(context.getContentResolver(),
                "bluetooth_name"));
        addMac(v, "<bt-address>", () -> Settings.Secure.getString(
                context.getContentResolver(), "bluetooth_address"));

        try {
            for (Account a : AccountManager.get(context).getAccounts()) {
                add(v, "<account>", () -> a.name);
            }
        } catch (Throwable t) {
            Log.w(TAG, "no accounts: " + t);
        }
        return new Scrubber(v);
    }

    private static void add(Map<String, String> values, String label, Source source) {
        final String value;
        try {
            value = source.get();
        } catch (Throwable t) {
            return;
        }
        if (TextUtils.isEmpty(value)) return;
        final String v = value.trim();
        if (v.length() < MIN_LENGTH || "<unknown ssid>".equals(v)) return;
        values.putIfAbsent(v, label);
    }

    /** MAC addresses are printed in both cases; a blank one isn't anyone's. */
    private static void addMac(Map<String, String> values, String label, Source source) {
        final String value;
        try {
            value = source.get();
        } catch (Throwable t) {
            return;
        }
        if (TextUtils.isEmpty(value) || value.startsWith("02:00:00:00:00:00")
                || value.startsWith("00:00:00:00:00:00")) {
            return;
        }
        add(values, label, () -> value.toLowerCase(Locale.ROOT));
        add(values, label, () -> value.toUpperCase(Locale.ROOT));
    }

    private static String unquote(String ssid) {
        if (ssid != null && ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    /** One line, cleaned. */
    String line(String line) {
        String s = line;
        if (mLiterals != null) {
            final Matcher m = mLiterals.matcher(s);
            if (m.find()) {
                final StringBuffer sb = new StringBuffer();
                do {
                    m.appendReplacement(sb, Matcher.quoteReplacement(mValues.get(m.group())));
                } while (m.find());
                m.appendTail(sb);
                s = sb.toString();
            }
        }
        for (Rule r : RULES) {
            s = r.pattern.matcher(s).replaceAll(r.replacement);
        }
        final Matcher m = FIFTEEN_DIGITS.matcher(s);
        if (m.find()) {
            final StringBuffer sb = new StringBuffer();
            do {
                m.appendReplacement(sb, luhn(m.group()) ? "<imei>" : m.group());
            } while (m.find());
            m.appendTail(sb);
            s = sb.toString();
        }
        return s;
    }

    /** The check digit of an IMEI (Luhn). */
    private static boolean luhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return sum % 10 == 0;
    }
}
