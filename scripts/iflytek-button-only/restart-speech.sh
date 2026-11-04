#!/system/bin/sh
# Restart one verified speech process, allowing the OEM persistent service to recover.
set -eu
test "$#" = 1
pid=$1
case "$pid" in ''|*[!0-9]*) exit 20;; esac
test "$pid" -gt 100
test "$(/system/bin/busybox id -u)" = 0
test "$(getprop ro.product.board)" = C3ALFUS
test "$(getprop ro.build.version.sdk)" = 18
test "$(getprop ro.build.display.id)" = 06.03.08600.H53.00060
name=$(/system/bin/busybox tr '\000' '\n' < /proc/$pid/cmdline | /system/bin/busybox head -n 1)
test "$name" = com.iflytek.cutefly.speechclient
kill -TERM "$pid"
echo "SPEECH_TERM_SENT:$pid"
