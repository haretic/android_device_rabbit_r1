#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Cromite's switches for init.r1.cromite.rc; a file someone wrote themselves stays
f=/data/local/tmp/chrome-command-line
[ -e "$f" ] && exit 0
echo "_ --disable-fre" > "$f" && chmod 0644 "$f"
