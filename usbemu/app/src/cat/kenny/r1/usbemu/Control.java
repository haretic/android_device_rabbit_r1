// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.usbemu;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemProperties;
import android.provider.DocumentsContract;
import java.util.List;

/** UI/service boundary; the helper alone writes the HAL lease properties. */
final class Control {
    static final String PREFIX = "sys.r1.usbemu.";
    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("usbemu", Context.MODE_PRIVATE);
    }
    static String prop(String name) { return SystemProperties.get(PREFIX + name, ""); }
    static boolean running() { return !prop("st.session").isEmpty() && !"idle".equals(prop("st.session")); }
    static boolean busy(Context c) { return prefs(c).getBoolean("busy", false); }
    static String folder(Context c) { return prefs(c).getString("folder", ""); }
    static Uri tree(Context c) {
        String value = prefs(c).getString("tree", "");
        return value.isEmpty() ? null : Uri.parse(value);
    }
    static boolean hasFolder(Context c) {
        Uri tree = tree(c);
        if (tree == null) return false;
        return c.getContentResolver().getPersistedUriPermissions().stream()
                .anyMatch(p -> p.getUri().equals(tree) && p.isReadPermission() && p.isWritePermission());
    }
    static String primaryPath(Uri uri) {
        if (!"com.android.externalstorage.documents".equals(uri.getAuthority())
                || !DocumentsContract.isTreeUri(uri)) throw new IllegalArgumentException();
        String id = DocumentsContract.getTreeDocumentId(uri);
        if (!id.startsWith("primary:")) throw new IllegalArgumentException();
        String relative = id.substring(8);
        if (relative.contains("\\") || relative.contains("//")
                || relative.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        for (String part : relative.split("/", -1)) {
            if (part.equals(".") || part.equals("..")) throw new IllegalArgumentException();
        }
        while (relative.endsWith("/")) relative = relative.substring(0, relative.length() - 1);
        return "/data/media/0" + (relative.isEmpty() ? "" : "/" + relative);
    }
    static void request(Context c, String action, Images image, boolean ro) {
        Intent intent = new Intent(c, SessionService.class).setAction(action);
        if (image != null) intent.putExtra("image", image.name).putExtra("stamp", image.stamp);
        intent.putExtra("ro", ro);
        c.startForegroundService(intent);
    }
    static void create(Context c, String name, int gb, String fs, boolean use) {
        c.startForegroundService(new Intent(c, SessionService.class).setAction("create")
                .putExtra("image", name + ".img").putExtra("size", gb)
                .putExtra("fs", fs).putExtra("use", use));
    }
    static boolean input() { return prop("st.session").endsWith("input"); }
    static String label(Context c) {
        switch (prop("st.session")) {
            case "sound": return c.getString(R.string.sound);
            case "input": return c.getString(R.string.keyboard);
            case "drive_input": case "cd_input": return c.getString(R.string.drive_input);
            default: return Images.session()[1];
        }
    }
}
