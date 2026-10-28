#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Image validation, journal and creation adapted from andr36oid UsbMode's
# andr36oid-usb-drive.sh and andr36oid-virtual-drive.sh. See the r1-tools doc docs/device/usbemu.md.
# Guard chains below deliberately fail if any validation fails.
# shellcheck disable=SC2015
# No UDC, configuration links, vold, mounts, loop devices or raw disks here.

P=sys.r1.usbemu
STATE=${STATE:-/data/misc/r1-usbemu}
IPC=${IPC:-/data/system/r1-usbemu}
MEDIA=${MEDIA:-/data/media/0}
FUNCTIONS=${FUNCTIONS:-/config/usb_gadget/g1/functions}
MOUNTS=${MOUNTS:-/proc/self/mountinfo}
LOOPS=${LOOPS:-/sys/block}
F=$FUNCTIONS/mass_storage.usbemu0
export LC_ALL=C
umask 077
NL='
'

prop() { getprop "$P.$1"; }
put() { setprop "$P.$1" "$2"; }
valid_token() { case $1 in ''|*[!0-9]*|?????????????????????*) return 1 ;; esac; }
valid_name() {
    [ "${#1}" -le 255 ] || return 1
    case $1 in ''|.*|*/*|*\\*|*..*|*[!a-zA-Z0-9._\ -]*|[!a-zA-Z0-9]*|*\ |*.) return 1 ;; esac
}
image_name() {
    [ "${#1}" -le 255 ] || return 1
    case $1 in ''|.*|*/*|*\\*|*..*|*"$NL"*) return 1 ;; esac
    printf '%s' "$1" | grep -q '[[:cntrl:]]' && return 1
    case $1 in *.[iI][sS][oO]|*.[iI][mM][gG]) ;; *) return 1 ;; esac
}
cd_size() {
    # Same complete-sector/CD capacity checks as UsbMode (f_mass_storage limit).
    awk -v bytes="$1" 'BEGIN {exit !(bytes>=614400 && bytes<=2359293952 && bytes%2048==0)}'
}
stamp() { stat -Lc '%d:%i:%s' "$1"; }
# Bytes free and in all on the folder's filesystem, from it alone: df would look at every
# mount, most of them closed to this domain
space() {
    stat -f -c '%a:%S:%b' "$1" | awk -F: 'NF==3 {printf "%.0f\n%.0f\n",$1*$2,$3*$2}'
}
identity() { stat -Lc '%d:%i' "$1"; }
fail() { error=$1; return 1; }
clock_now() {
    # Monotonic seconds, with no date process or wall-clock adjustment races.
    IFS=' ' read -r uptime _clock_idle </proc/uptime || return 1
    clock_seconds=${uptime%%.*}
}
progress() { put st.stage "$1" && put st.percent "$2"; }
result() { put st.error "${error:-}"; put st.result "$token:$1"; }
cancelled() {
    [ "$(prop req.cancel)" = "$token" ] || [ "$(prop req.stop)" != "$stop_at_start" ]
}

validate_folder() {
    # Refuse escaped mountinfo paths, traversal and every symlink component.
    case $folder in "$MEDIA"|"$MEDIA"/*) ;; *) fail folder; return 1 ;; esac
    case $folder in *'/../'*|*/..|*'/./'*|*/.|*//*|*\\*) fail folder; return 1 ;; esac
    [ -d "$folder" ] && [ "$(readlink -f "$folder")" = "$folder" ] || { fail folder; return 1; }
    [ "$(stat -c %d "$folder")" = "$(stat -c %d "$MEDIA")" ] || { fail mounted; return 1; }
    # Decode mountinfo's escapes before comparing. Reject nested/bind mounts.
    awk -v dir="$folder" -v media="$MEDIA" 'function decode(s) {gsub(/\\040/," ",s);gsub(/\\011/,"\t",s);gsub(/\\012/,"\n",s);gsub(/\\134/,"\\",s);return s}
        {p=decode($5); if((p==media || index(p,media "/")==1) && (p==dir || index(p,dir "/")==1 || index(dir,p "/")==1)) bad=1} END {exit bad}' "$MOUNTS" || { fail mounted; return 1; }
    # Pin the directory across name replacement/rename. All output uses this fd.
    exec 8<"$folder" || { fail folder; return 1; }
    dirfd=/proc/$$/fd/8
    folder_id=$(identity "$dirfd") || return 1
}
not_mounted() {
    # Reject a loop association, including an unmounted loop and deleted backing files.
    for backing in "$LOOPS"/loop*/loop/backing_file; do
        [ -e "$backing" ] || continue
        loop_path=$(cat "$backing") || { fail mounted; return 1; }
        [ -n "$loop_path" ] || continue
        loop_path=$(readlink -f "$loop_path" 2>/dev/null) || { fail mounted; return 1; }
        case $loop_path in
            /storage/emulated/0/*) loop_path=$MEDIA/${loop_path#/storage/emulated/0/} ;;
            /mnt/runtime/*/emulated/0/*) loop_path=$MEDIA/${loop_path#*/emulated/0/} ;;
            /mnt/user/0/primary/*) loop_path=$MEDIA/${loop_path#/mnt/user/0/primary/} ;;
        esac
        case $loop_path in /*) ;; *) loop_path=/$loop_path ;; esac
        if [ "$loop_path" = "$path" ] || [ "$loop_path" = "$path (deleted)" ] ||
            [ "$(identity "$loop_path" 2>/dev/null)" = "$file_id" ]; then fail mounted; return 1; fi
    done
    # A file bind-mounted elsewhere is in use even when its source path is not
    # a mountpoint in the chosen directory. /data is a separate filesystem.
    awk -v full="$path" -v relative="${path#/data}" 'function decode(s) {gsub(/\\040/," ",s);return s}
        {p=decode($4); if(p==full || p==relative) bad=1} END {exit bad}' "$MOUNTS" || { fail mounted; return 1; }
}
open_image() {
    image_name "$name" || { fail name; return 1; }
    path=$folder/$name
    [ -f "$path" ] && [ ! -L "$path" ] && [ "$(readlink -f "$path")" = "$path" ] || { fail image; return 1; }
    [ "$(stat -c %h "$path")" = 1 ] || { fail image; return 1; }
    before=$(stamp "$path") || return 1
    [ "$before" = "$expected" ] || { fail changed; return 1; }
    # Open read-only first, then compare inode before any writable open. The
    # configfs backing path is a pinned descriptor, never the app's mutable path.
    exec 9<"$path" || { fail image; return 1; }
    imagefd=/proc/$$/fd/9
    [ -f "$imagefd" ] && [ "$(stamp "$imagefd")" = "$before" ] || { fail changed; return 1; }
    file_id=$(identity "$imagefd") || return 1
    [ "${file_id%:*}" = "${folder_id%:*}" ] || { fail image; return 1; }
    bytes=${before##*:}
    awk -v n="$bytes" 'BEGIN {exit !(n>0 && n%512==0)}' || { fail size; return 1; }
    cdrom=0
    case $name in *.[iI][sS][oO]) cdrom=1; ro=1; cd_size "$bytes" || { fail cd_size; return 1; } ;; esac
    case $ro in 0|1) ;; *) fail access; return 1 ;; esac
    not_mounted || return 1
    if [ "$ro" = 0 ]; then
        # The new descriptor resolves the already pinned inode.
        exec 7<>"$imagefd" || { fail readonly; return 1; }
        exec 9<&7; exec 7>&-
    fi
}

journal() {
    # Publish durable intent before the first mutation; never source journal text.
    rm -rf "$STATE/journal.tmp"
    mkdir "$STATE/journal.tmp" || return 1
    printf '%s\n' "$1" >"$STATE/journal.tmp/op"
    printf '%s\n' "$folder" >"$STATE/journal.tmp/folder"
    printf '%s\n' "$name" >"$STATE/journal.tmp/name"
    printf '%s\n' "$token" >"$STATE/journal.tmp/token"
    fsync "$STATE/journal.tmp/op" "$STATE/journal.tmp/folder" "$STATE/journal.tmp/name" "$STATE/journal.tmp/token" "$STATE/journal.tmp" || return 1
    mv "$STATE/journal.tmp" "$STATE/journal" && fsync "$STATE"
}
clear_journal() { rm -rf "$STATE/journal" "$STATE/journal.tmp"; fsync "$STATE"; }
wait_idle() {
    clock_now || return 1; deadline=$((clock_seconds + 10))
    while clock_now && [ "$clock_seconds" -lt "$deadline" ]; do
        [ "$(getprop vendor.r1.usbemu.state)" = idle ] && return 0
        sleep 0.1
    done
    fail hal_timeout
}
release() {
    put functions none || return 1
    wait_idle || return 1
    # HAL publishes idle before tearing down its old links. Retry rmdir until
    # that teardown actually completes; never unlink a HAL-owned config link.
    clock_now || return 1; deadline=$((clock_seconds + 10))
    while [ -d "$F" ]; do
        rmdir "$F" 2>/dev/null && break
        clock_now && [ "$clock_seconds" -lt "$deadline" ] || { fail cleanup; return 1; }
        sleep 0.1
    done
    rm -f "$STATE/session" "$IPC/status/session"
    put st.session idle
}
recover_create() {
    [ -f "$STATE/journal/op" ] || return 0
    [ "$(cat "$STATE/journal/op")" = create ] || return 0
    folder=$(cat "$STATE/journal/folder"); name=$(cat "$STATE/journal/name")
    saved_token=$(cat "$STATE/journal/token")
    valid_token "$saved_token" && valid_name "$name" && validate_folder || { fail recovery; return 1; }
    partial=$dirfd/.r1-usbemu-$saved_token.creating
    if [ -e "$partial" ] || [ -L "$partial" ]; then
        [ -f "$partial" ] && [ ! -L "$partial" ] || { fail recovery; return 1; }
        saved_id=$(cat "$STATE/journal/inode" 2>/dev/null) || saved_id=
        if [ -n "$saved_id" ]; then
            [ "$(identity "$partial")" = "$saved_id" ] || { fail recovery; return 1; }
        else
            [ "$(stat -c %s "$partial")" = 0 ] || { fail recovery; return 1; }
        fi
        rm "$partial" && fsync "$dirfd" || return 1
    fi
}
remove_stale() {
    for stale in "$FUNCTIONS"/*.usbemu*; do
        [ ! -d "$stale" ] || rmdir "$stale" 2>/dev/null || { fail cleanup; return 1; }
    done
}
recover() {
    release && remove_stale && recover_create && clear_journal
}
save_session() {
    # Root-private authority plus read-only UI projection (the app cannot edit it).
    printf '%s\n' "$folder" "$name" "$ro" "$bytes" "$generation" >"$STATE/session.tmp"
    fsync "$STATE/session.tmp" && mv "$STATE/session.tmp" "$STATE/session" && fsync "$STATE" || return 1
    cp "$STATE/session" "$IPC/status/session.tmp" || return 1
    chown root:system "$IPC/status/session.tmp" && chmod 0640 "$IPC/status/session.tmp" || return 1
    mv "$IPC/status/session.tmp" "$IPC/status/session" || return 1
    if [ "$cdrom" = 1 ]; then put st.session cd; else put st.session drive; fi
}
start_drive() {
    [ ! -f "$STATE/session" ] && [ ! -d "$F" ] || { fail active; return 1; }
    validate_folder && open_image && journal start || return 1
    mkdir "$F" || return 1
    printf '%s\n' "$cdrom" >"$F/lun.0/cdrom" && printf '%s\n' "$ro" >"$F/lun.0/ro" &&
        printf '1\n' >"$F/lun.0/removable" && printf '%s\n' "$imagefd" >"$F/lun.0/file" || return 1
    generation=$(cat "$STATE/generation" 2>/dev/null) || generation=0
    case $generation in ''|*[!0-9]*|??????????*) generation=0 ;; esac
    generation=$((generation + 1))
    printf '%s\n' "$generation" >"$STATE/generation"
    fsync "$STATE/generation" "$STATE" || return 1
    put id 1d6b:0104 && put functions "$generation:mass_storage.usbemu0" || return 1
    progress connecting 0
    clock_now || return 1; deadline=$((clock_seconds + 10))
    while clock_now && [ "$clock_seconds" -lt "$deadline" ]; do
        cancelled && { fail cancelled; return 1; }
        case $(getprop vendor.r1.usbemu.state) in
            "$generation applied") save_session && clear_journal; return $? ;;
            "$generation failed") fail hal_failed; return 1 ;;
        esac
        sleep 0.1
    done
    fail hal_timeout
}
swap_disc() {
    [ -f "$STATE/session" ] && [ "$(prop st.session)" = cd ] || { fail session; return 1; }
    old_folder=$(sed -n '1p' "$STATE/session")
    [ "$folder" = "$old_folder" ] || { fail folder; return 1; }
    validate_folder && open_image && [ "$cdrom" = 1 ] || { fail image; return 1; }
    [ "$force" = 1 ] || { fail confirm; return 1; }
    generation=$(sed -n '5p' "$STATE/session")
    [ "$(getprop vendor.r1.usbemu.state)" = "$generation applied" ] || { fail hal_failed; return 1; }
    journal swap || return 1
    printf '1\n' >"$F/lun.0/forced_eject" && printf '%s\n' "$imagefd" >"$F/lun.0/file" || return 1
    save_session && clear_journal
}
run_formatter() {
    "$@" >"$STATE/format.log" 2>&1 &
    child=$!
    clock_now || return 1; deadline=$((clock_seconds + 120))
    while kill -0 "$child" 2>/dev/null; do
        format_error=
        if cancelled; then format_error=cancelled
        elif ! clock_now || [ "$clock_seconds" -ge "$deadline" ]; then format_error=format_timeout; fi
        if [ -n "$format_error" ]; then
            kill "$child" 2>/dev/null || true
            sleep 0.1
            kill -9 "$child" 2>/dev/null || true
            wait "$child" 2>/dev/null || true
            child=; fail "$format_error"; return 1
        fi
        sleep 0.1
    done
    wait "$child"; rc=$?; child=
    [ "$rc" = 0 ] || { fail format; return 1; }
}
create_image() {
    [ ! -f "$STATE/session" ] || { fail active; return 1; }
    valid_name "$name" && case $name in *.[iI][mM][gG]) true ;; *) false ;; esac || { fail name; return 1; }
    case $size in 1|2|4|8|16) ;; *) fail size; return 1 ;; esac
    case $fs in fat32) formatter=newfs_msdos ;; exfat) formatter=mkfs.exfat ;; *) fail format; return 1 ;; esac
    command -v "$formatter" >/dev/null || { fail formatter; return 1; }
    validate_folder || return 1
    # Android mksh arithmetic is 32-bit, including on arm64. Keep byte sizes
    # as exact decimal strings; only the sector count fits shell arithmetic.
    bytes=$(awk -v gb="$size" 'BEGIN {printf "%.0f",gb*1073741824}')
    free=$(space "$dirfd" | sed -n 1p)
    awk -v n="$bytes" -v free="$free" 'BEGIN {exit !(free>=n+1073741824)}' || { fail space; return 1; }
    [ ! -e "$dirfd/$name" ] && [ ! -L "$dirfd/$name" ] || { fail exists; return 1; }
    journal create || return 1
    partial=$dirfd/.r1-usbemu-$token.creating
    (set -C; : >"$partial") || return 1
    exec 9<>"$partial" || return 1
    imagefd=/proc/$$/fd/9
    identity "$imagefd" >"$STATE/journal/inode"
    fsync "$STATE/journal/inode" "$STATE/journal" "$dirfd" || return 1
    progress allocate 10
    truncate -s "$bytes" "$imagefd" || return 1
    cancelled && { fail cancelled; return 1; }
    progress format 30
    case $fs in
        fat32) run_formatter newfs_msdos -F 32 -S 512 -h 255 -u 63 -o 0 -s "$((size * 2097152))" -c 8 -L USB_DRIVE "$imagefd" || return 1 ;;
        exfat) run_formatter mkfs.exfat -L USB_DRIVE "$imagefd" || return 1 ;;
    esac
    progress save 90
    fsync "$imagefd" || return 1
    cancelled && { fail cancelled; return 1; }
    [ "$(identity "$partial")" = "$(identity "$imagefd")" ] &&
        [ "$(identity "$folder")" = "$folder_id" ] || { fail changed; return 1; }
    # Hard-link publication is exclusive and atomic on primary f2fs/ext4. Never
    # overwrite a destination created while formatting; no mv -n ambiguity.
    chmod 0664 "$imagefd" && chown media_rw:media_rw "$imagefd" || return 1
    ln -T "$partial" "$dirfd/$name" || { fail exists; return 1; }
    rm "$partial" && fsync "$dirfd" && clear_journal || return 1
    progress "done" 100
}
scan_images() {
    validate_folder || return 1
    space "$dirfd" >"$IPC/status/space" && [ -s "$IPC/status/space" ] || return 1
    chmod 0640 "$IPC/status/space" && chown root:system "$IPC/status/space" || return 1
    : >"$IPC/status/inventory.tmp" || return 1
    for path in "$folder"/*; do
        name=${path##*/}
        image_name "$name" || continue
        [ -f "$path" ] && [ ! -L "$path" ] && [ "$(stat -c %h "$path")" = 1 ] || continue
        expected=$(stamp "$path") || continue
        ro=0; [ -w "$path" ] || ro=1
        # Use a subshell so descriptor/validation failures skip just this file.
        if open_image; then
            case $name in *.[iI][sS][oO]) ro=1 ;; esac
            printf '%s\t%s\t%s\n' "$expected" "$ro" "$name" >>"$IPC/status/inventory.tmp"
        fi
    done
    chmod 0640 "$IPC/status/inventory.tmp" && chown root:system "$IPC/status/inventory.tmp" || return 1
    mv "$IPC/status/inventory.tmp" "$IPC/status/inventory"
}
read_request() {
    valid_token "$token" || return 1
    request=$IPC/requests/$token
    [ -f "$request" ] && [ ! -L "$request" ] || { fail request; return 1; }
    exec 5<"$request" || return 1
    [ -f /proc/$$/fd/5 ] || { fail request; return 1; }
    head -c 8193 /proc/$$/fd/5 >"$STATE/request" || return 1
    exec 5<&-
    [ "$(stat -c %s "$STATE/request")" -le 8192 ] || { fail request; return 1; }
    # Eight newline-delimited fields; no eval, shell source or argument expansion.
    [ "$(wc -l <"$STATE/request")" = 8 ] || { fail request; return 1; }
    folder=$(sed -n '1p' "$STATE/request"); name=$(sed -n '2p' "$STATE/request")
    ro=$(sed -n '3p' "$STATE/request"); expected=$(sed -n '4p' "$STATE/request")
    size=$(sed -n '5p' "$STATE/request"); fs=$(sed -n '6p' "$STATE/request")
    force=$(sed -n '7p' "$STATE/request"); version=$(sed -n '8p' "$STATE/request")
    [ "$version" = 1 ] || { fail request; return 1; }
    rm -f "$request"
}
finish() {
    [ -z "${child:-}" ] || { kill -9 "$child" 2>/dev/null; wait "$child" 2>/dev/null; }
}
main() {
    error=; child=; token=0
    exec 6>"$STATE/lock" || exit 1
    clock_now || exit 1; deadline=$((clock_seconds + 20))
    # mksh marks exec-opened fds close-on-exec. Pass this fd explicitly so
    # toybox flock receives the same open-file description as the parent.
    until flock -n 6 6>&6; do
        clock_now && [ "$clock_seconds" -lt "$deadline" ] || exit 1
        sleep 0.1
    done
    trap 'finish' EXIT
    trap 'exit 1' HUP INT TERM
    stop_at_start=$(prop req.stop)
    case $1 in
        boot)
            rm -rf "$STATE/journal.tmp"
            if [ ! -d "$STATE/journal" ]; then folder=$MEDIA; name=boot.img; journal boot || exit 1; fi
            if recover; then
                put st.result 0:done; put st.error ''
            else result error; fi
            ;;
        stop)
            token=$(prop req.stop); valid_token "$token" || exit 1
            result working
            if [ ! -d "$STATE/journal" ]; then folder=$MEDIA; name=stop.img; journal stop || exit 1; fi
            if recover; then progress idle 0; result "done"; else result error; fi
            ;;
        recover)
            if [ -d "$STATE/journal" ]; then
                token=$(cat "$STATE/journal/token")
                if recover; then error=interrupted; else error=recovery; fi
                result error
            fi
            ;;
        check)
            if [ -f "$STATE/session" ]; then
                generation=$(sed -n '5p' "$STATE/session")
                case $(getprop vendor.r1.usbemu.state) in
                    "$generation failed"|idle)
                        token=0; folder=$MEDIA; name=failed.img
                        journal stop || exit 1
                        if recover; then error=hal_failed; else error=recovery; fi
                        result error
                        ;;
                esac
            fi
            ;;
        run)
            command=$(prop req.run); token=${command%%:*}; op=${command#*:}
            valid_token "$token" || exit 1
            # Crash recovery precedes a fresh request. Never discard the journal.
            if [ -d "$STATE/journal" ] && ! recover; then result error; exit 1; fi
            result working; progress validate 0
            if read_request; then
                case $op in scan) scan_images ;; start) start_drive ;; swap) swap_disc ;; create) create_image ;; *) fail request ;; esac
                rc=$?
            else rc=1; fi
            if [ "$rc" = 0 ]; then result "done"
            else
                original_error=${error:-io}
                if [ -d "$STATE/journal" ]; then
                    if ! recover; then original_error=recovery; fi
                fi
                error=$original_error
                if [ "$error" = cancelled ]; then result cancelled; else result error; fi
            fi
            ;;
        *) exit 1 ;;
    esac
}
# Host tests source exactly these functions, with fake files/properties.
if [ "${R1_USBEMU_SOURCE_ONLY:-0}" != 1 ]; then main "$@"; fi
