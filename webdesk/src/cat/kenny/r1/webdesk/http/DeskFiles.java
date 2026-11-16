// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk.http;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Shared storage paths and upload names, independent of Android. */
public final class DeskFiles {
    private DeskFiles() { }

    public static File resolve(File storage, String path) throws IOException {
        File root = storage.getCanonicalFile();
        if (path != null && (path.indexOf('\0') >= 0 || new File(path).isAbsolute())) {
            return null;
        }
        File file = new File(root, path == null ? "" : path).getCanonicalFile();
        return file.equals(root) || file.getPath().startsWith(root.getPath() + File.separator)
                ? file : null;
    }

    public static String name(String supplied) {
        String name = supplied == null ? "" : supplied.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1)
                .replaceAll("[/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || name.startsWith(".")) {
            name = "file" + name;
        }
        // Leave room for a collision suffix on filesystems with a 255 byte name limit.
        StringBuilder shortName = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < name.length(); ) {
            int point = name.codePointAt(i);
            String letter = new String(Character.toChars(point));
            int size = letter.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > 180) {
                break;
            }
            shortName.append(letter);
            bytes += size;
            i += Character.charCount(point);
        }
        return shortName.toString();
    }

    public static File temporary(File storage, String directory) throws IOException {
        File dir = resolve(storage, directory);
        if (dir == null) {
            throw new IOException("Upload directory outside storage");
        }
        Files.createDirectories(dir.toPath());
        return Files.createTempFile(dir.toPath(), ".webdesk-", ".part").toFile();
    }

    /** Never replace another upload, an existing file or a dangling link. */
    public static synchronized File publish(File part, String supplied) throws IOException {
        String name = name(supplied);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; n <= 10_000; n++) {
            Path target = part.toPath().resolveSibling(n == 1 ? name
                    : stem + " (" + n + ")" + ext);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                return Files.move(part.toPath(), target).toFile();
            } catch (FileAlreadyExistsException e) {
                // Another writer took the name while this file was arriving.
            }
        }
        throw new IOException("No free upload name");
    }
}
