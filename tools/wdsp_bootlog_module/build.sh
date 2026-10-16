#!/bin/sh
#
# Builds the installable Magisk zip of the wDSP boot logger. Run it in WSL, not from a Windows shell -
# a Windows checkout or editor turns LF into CRLF, and sh on the unit then reads "\r" as part of every
# command:
#
#   wsl -e sh /mnt/c/Users/kosty/AndroidStudioProjects/wDSP/tools/wdsp_bootlog_module/build.sh
#
# Install (takes effect from the next boot):
#   adb push out/wDSP_bootlog.zip /data/local/tmp/
#   adb shell "su -c 'magisk --install-module /data/local/tmp/wDSP_bootlog.zip'"
#
# Read a boot:  adb shell "cat /data/local/tmp/bootlog/latest"  then the files in that folder.

set -e
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/out"
ZIP="$OUT/wDSP_bootlog.zip"
FILES="module.prop customize.sh service.sh META-INF/com/google/android/update-binary META-INF/com/google/android/updater-script"
CR=$(printf '\r')

cd "$HERE"
for f in $FILES; do
    if grep -q "$CR" "$f"; then
        echo "REFUSED: $f has CR line endings - convert it to LF first" >&2
        exit 1
    fi
done

mkdir -p "$OUT"
rm -f "$ZIP"
zip -X -q "$ZIP" $FILES

# Proof rather than trust: no CR byte in any file as it sits inside the archive.
for f in $FILES; do
    if unzip -p "$ZIP" "$f" | grep -q "$CR"; then
        echo "BROKEN: $f carries CR inside the zip" >&2
        exit 1
    fi
done
echo "built $ZIP"
unzip -l "$ZIP"
