// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The Android log of the last start, which liblog also writes to pstore (pmsg) on a
 * debuggable build: the last 64 KiB of it. "logcat -L" would print it, but it reads
 * /sys/fs/pstore itself, which only a few system domains may; init copies the file for us
 * (r1-problem-report.rc) and this prints it as logcat -v threadtime does. The records and
 * their checks are those of liblog's pmsg_reader.cpp; binary ones (events, stats, security)
 * are only counted.
 */
final class Pmsg {

    /** android_pmsg_log_header_t, android_log_header_t and the priority byte */
    private static final int HEADER = 7 + 11 + 1;
    /** LOGGER_ENTRY_MAX_PAYLOAD */
    private static final int MAX_PAYLOAD = 4068;
    private static final String[] BUFFERS =
            {"main", "radio", "events", "system", "crash", "stats", "security", "kernel"};
    private static final String PRIORITIES = "  VDIWEF";
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS");

    private Pmsg() {
    }

    static void decode(File in, Writer out) throws IOException {
        final byte[] b = Files.readAllBytes(in.toPath());
        final ZoneId zone = ZoneId.systemDefault();
        int lastBuffer = -1;
        int binary = 0;
        int i = 0;
        while (i + HEADER <= b.length) {
            if (b[i] != 'l') {
                i++;
                continue;
            }
            final int len = u16(b, i + 1);
            final int pid = u16(b, i + 5);
            final int id = b[i + 7] & 0xff;
            final int tid = u16(b, i + 8);
            final long sec = u32(b, i + 10);
            final long nsec = u32(b, i + 14);
            final int prio = b[i + 18] & 0xff;
            final boolean text = id != 2 && id != 5 && id != 6;
            if (len <= HEADER || len > HEADER + MAX_PAYLOAD || i + len > b.length
                    || id >= BUFFERS.length || nsec >= 1_000_000_000L
                    || (text && (prio < 2 || prio >= PRIORITIES.length()))) {
                // Not a record start: look on from the next byte, as liblog does
                i++;
                continue;
            }
            if (!text) {
                binary++;
                i += len;
                continue;
            }
            // The payload after the priority: the tag, a NUL, the message
            final int start = i + HEADER;
            final int end = i + len;
            int nul = start;
            while (nul < end && b[nul] != 0) nul++;
            final String tag = new String(b, start, nul - start, StandardCharsets.UTF_8);
            int msgEnd = end;
            while (msgEnd > nul + 1 && (b[msgEnd - 1] == 0 || b[msgEnd - 1] == '\n')) msgEnd--;
            final String msg = nul + 1 < msgEnd
                    ? new String(b, nul + 1, msgEnd - nul - 1, StandardCharsets.UTF_8) : "";

            if (id != lastBuffer) {
                out.write("--------- beginning of " + BUFFERS[id] + "\n");
                lastBuffer = id;
            }
            final String head = String.format("%s %5d %5d %c %-8s: ",
                    TIME.format(Instant.ofEpochSecond(sec, nsec).atZone(zone)), pid, tid,
                    PRIORITIES.charAt(prio), tag);
            for (String line : msg.split("\n", -1)) {
                out.write(head);
                out.write(line);
                out.write('\n');
            }
            i += len;
        }
        if (binary > 0) {
            out.write("(" + binary + " binary records left out: events, stats, security)\n");
        }
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xffL) | (b[at + 1] & 0xffL) << 8 | (b[at + 2] & 0xffL) << 16
                | (b[at + 3] & 0xffL) << 24;
    }
}
