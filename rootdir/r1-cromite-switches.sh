#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Cromite's switches for init.r1.cromite.rc: no first-run screens, and pages drawn with Vulkan
# as the rest of the UI is. A file someone wrote themselves stays; the one an earlier haretic
# wrote is brought up to date.
f=/data/local/tmp/chrome-command-line
want="_ --disable-fre --enable-features=Vulkan"
if [ -e "$f" ]; then
    case "$(cat "$f")" in
        "_ --disable-fre") ;;
        *) exit 0 ;;
    esac
fi
echo "$want" > "$f" && chmod 0644 "$f"
