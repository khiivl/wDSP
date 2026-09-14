#!/system/bin/sh
#
# wDSP boot logger - one folder per boot, so that reading one boot never means scrolling through all
# of them. Reads logs only: it changes no setting, plays nothing and sends nothing.
#
# Why a Magisk module and not a logger started from adb: adb comes back a minute into a boot at best,
# and by then logcat's main buffer (256 KiB on this unit, with the platform's volume polling writing
# three lines every 100 ms) has already rolled past the start. late_start service runs before the
# apps do, and logcat hands over the whole buffer first, so nothing from the start is lost.
#
# /data/local/tmp/bootlog/NNNN:
#   00_header.txt        boot number, boot reason, uptime / wall-clock pairs, kernel command line
#   01_dmesg.txt         the kernel ring buffer as it stood when logging started
#   02_props.txt         boot-stage properties each time they change, seconds since boot, until
#                        90 s after boot_completed
#   10_boot.log          every buffer, unfiltered, until boot_completed + BOOT_WINDOW_S. Timestamps
#                        are seconds since boot (-v monotonic), so the boot order reads directly,
#                        also after the power was removed and the wall clock is set only later
#   20_run.log[.1..]     the rest of the boot, wall-clock timestamps (to match dumpsys event logs),
#                        platform volume polling silenced, rotated at RUN_KB x RUN_FILES
#   30_dumpsys_audio.txt, 31_audio_flinger.txt    taken when the boot window closes
# /data/local/tmp/bootlog/latest names the current boot's folder.
#
# Keep this file with LF line endings: sh reads "\r" as part of every command. The .gitattributes
# next to it pins that for git; build.sh refuses a file that has one.

MODPATH=${0%/*}
BASE=/data/local/tmp/bootlog
KEEP=15
BOOT_WINDOW_S=240
RUN_KB=4096
RUN_FILES=4
PROPS_AFTER_BOOT_TICKS=450   # 90 s of property watching after boot_completed, at 0.2 s a tick

(
    umask 022
    mkdir -p "$BASE"
    chmod 0755 "$BASE"

    N=$(cat "$BASE/.counter" 2>/dev/null)
    case "$N" in ''|*[!0-9]*) N=0 ;; esac
    N=$((N + 1))
    echo "$N" > "$BASE/.counter"
    DIR="$BASE/$(printf '%04d' "$N")"
    mkdir -p "$DIR"
    echo "$DIR" > "$BASE/latest"

    # The newest KEEP boots stay.
    COUNT=$(ls -d "$BASE"/[0-9][0-9][0-9][0-9] 2>/dev/null | wc -l)
    if [ "$COUNT" -gt "$KEEP" ]; then
        ls -d "$BASE"/[0-9][0-9][0-9][0-9] | sort | head -n $((COUNT - KEEP)) | while read -r OLD; do
            rm -rf "$OLD"
        done
    fi

    stamp() {
        echo "$1: uptime $(cut -d' ' -f1 /proc/uptime) s, wall clock $(date '+%Y-%m-%d %H:%M:%S')"
    }

    {
        echo "boot $N"
        stamp "logger started"
        echo "kernel command line: $(cat /proc/cmdline)"
    } > "$DIR/00_header.txt"

    dmesg > "$DIR/01_dmesg.txt" 2>&1

    # When the properties that mark the boot's stages change, in seconds since boot: logcat does
    # not say when a property is set, and wDSP's boot stages wait on some of these.
    watch_props() {
        PREV=""
        AFTER=0
        while :; do
            CUR="acc_on=$(getprop sys.qf.is.acc.on) audioserver=$(getprop init.svc.audioserver) boot_completed=$(getprop sys.boot_completed) audio_src=$(getprop sys.qf.last_audio_src)"
            if [ "$CUR" != "$PREV" ]; then
                echo "$(cut -d' ' -f1 /proc/uptime) $CUR" >> "$DIR/02_props.txt"
                PREV="$CUR"
            fi
            if [ "$(getprop sys.boot_completed)" = "1" ]; then
                AFTER=$((AFTER + 1))
                [ "$AFTER" -ge "$PROPS_AFTER_BOOT_TICKS" ] && break
            fi
            sleep 0.2
        done
    }
    watch_props &

    logcat -b main,system,events,crash -v threadtime -v monotonic > "$DIR/10_boot.log" 2>&1 &
    BOOT_PID=$!

    until [ "$(getprop sys.boot_completed)" = "1" ]; do
        sleep 1
    done
    {
        stamp "boot_completed seen"
        echo "boot reason: $(getprop sys.boot.reason)"
        echo "bootloader reason: $(getprop ro.boot.bootreason)"
        echo "boot reason history: $(getprop persist.sys.boot.reason.history | tr '\n' ' ')"
    } >> "$DIR/00_header.txt"

    sleep "$BOOT_WINDOW_S"

    # The long log starts before the unfiltered one stops (-T 1: from the newest line on), so the
    # hand-over has an overlap of a few lines rather than a gap.
    logcat -b main,system,events,crash -v threadtime -T 1 \
        -f "$DIR/20_run.log" -r "$RUN_KB" -n "$RUN_FILES" \
        VolumeState:S VolumeManager:S chatty:S &
    kill "$BOOT_PID" 2>/dev/null
    stamp "boot window closed, unfiltered log stopped" >> "$DIR/00_header.txt"

    dumpsys audio > "$DIR/30_dumpsys_audio.txt" 2>&1
    dumpsys media.audio_flinger > "$DIR/31_audio_flinger.txt" 2>&1
    chmod -R a+rX "$DIR"

    sed -i "s|^description=.*|description=Boot $N ($(getprop sys.boot.reason)) logged in $DIR. One folder per boot in $BASE; reads logs only, changes nothing.|" \
        "$MODPATH/module.prop"
) &
