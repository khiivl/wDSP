#!/system/bin/sh
# Sourced by Magisk's installer: util_functions.sh is loaded and MODPATH is set.
ui_print "- wDSP boot logger"
ui_print "- one folder per boot: /data/local/tmp/bootlog/NNNN"
ui_print "- reads logs only; changes nothing on the unit"
ui_print "- starts logging from the next boot"
set_perm "$MODPATH/service.sh" 0 0 0755
