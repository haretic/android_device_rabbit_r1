// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.usbemu;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Portable image/form logic adapted from UsbMode UsbDrive and VirtualDrive. */
final class Images {
    static final long GB = 1073741824L;
    static final File STATUS = new File("/data/system/r1-usbemu/status");
    final String name, stamp;
    final long bytes;
    final boolean iso, readOnly;

    Images(String name, String stamp, boolean readOnly) {
        this.name = name;
        this.stamp = stamp;
        this.bytes = Long.parseLong(stamp.substring(stamp.lastIndexOf(':') + 1));
        this.iso = name.toLowerCase(Locale.ROOT).endsWith(".iso");
        this.readOnly = readOnly || iso;
    }

    static boolean imageName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !name.isEmpty() && !name.startsWith(".") && !name.contains("..")
                && !name.contains("/") && !name.contains("\\")
                && name.codePoints().noneMatch(Character::isISOControl)
                && name.getBytes(StandardCharsets.UTF_8).length <= 255
                && (lower.endsWith(".iso") || lower.endsWith(".img"));
    }

    static boolean createName(String name) {
        return name.length() <= 87 && !name.contains("..")
                && name.matches("[a-zA-Z0-9][a-zA-Z0-9._ -]*")
                && !name.endsWith(" ") && !name.endsWith(".");
    }

    static boolean cdSize(long bytes) {
        return bytes >= 614400 && bytes <= 2359293952L && bytes % 2048 == 0;
    }

    static long maximum(long free) {
        return Math.max(0, Math.min(16 * GB, free - GB)) / GB * GB;
    }

    static String nextName(List<Images> images) {
        Set<String> used = new HashSet<>();
        for (Images image : images) used.add(image.name.toLowerCase(Locale.ROOT));
        for (int n = 1; ; n++) if (!used.contains("image" + n + ".img")) return "image" + n;
    }

    static List<Images> inventory() {
        List<Images> images = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(new File(STATUS, "inventory").toPath())) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 3 || !imageName(fields[2])
                        || !fields[0].matches("[0-9]+:[0-9]+:[0-9]+")
                        || !fields[1].matches("[01]")) continue;
                try {
                    Images image = new Images(fields[2], fields[0], "1".equals(fields[1]));
                    if (image.bytes > 0 && image.bytes % 512 == 0
                            && (!image.iso || cdSize(image.bytes))) images.add(image);
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
        images.sort(Comparator.comparing(image -> image.name.toLowerCase(Locale.ROOT)));
        return images;
    }

    static long free() {
        try { return Long.parseLong(Files.readAllLines(new File(STATUS, "space").toPath()).get(0).trim()); }
        catch (IOException | RuntimeException e) { return 0; }
    }

    static long total() {
        try { return Long.parseLong(Files.readAllLines(new File(STATUS, "space").toPath()).get(1).trim()); }
        catch (IOException | RuntimeException e) { return 0; }
    }

    static String[] session() {
        try {
            List<String> fields = Files.readAllLines(new File(STATUS, "session").toPath());
            if (fields.size() == 5 && imageName(fields.get(1)) && fields.get(2).matches("[01]")
                    && fields.get(3).matches("[0-9]+") && fields.get(4).matches("[0-9]+")) {
                return fields.toArray(new String[0]);
            }
        } catch (IOException ignored) { }
        return new String[] {"", "", "1", "0", "0"};
    }
}
