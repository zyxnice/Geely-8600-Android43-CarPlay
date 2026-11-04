#!/system/bin/sh
# Restore verified OEM bytes atomically; never replace any APK or clear app data.
set -eu
bb=/system/bin/busybox
target=/system/app/XCSpeechClient.odex
backup=/system/button-only-speech-backup-20261104-v2/XCSpeechClient.odex
held=/system/button-only-speech-backup-20261104-v2/held-before-restore.odex
stage=/system/app/.XCSpeechClient.button-only-restore
original=84fa3b983da21d7c52d8703cea85efa467a4f70263eb0ff21b991026e35c4bd8
test "$#" = 1
expected=$1
test "${#expected}" = 64
case "$expected" in *[!0-9a-f]*) exit 20;; esac
test "$($bb id -u)" = 0
test "$(getprop ro.product.board)" = C3ALFUS
test "$(getprop ro.build.version.sdk)" = 18
test "$(getprop ro.build.display.id)" = 06.03.08600.H53.00060
test ! -L /system/button-only-speech-backup-20261104-v2
test ! -L "$backup" && test ! -L "$target" && test ! -e "$stage" && test ! -L "$stage"
test ! -e "$held" && test ! -L "$held"
test "$($bb sha256sum "$backup" | $bb cut -d ' ' -f 1)" = "$original"
current=$($bb sha256sum "$target" | $bb cut -d ' ' -f 1)
test "$current" = "$expected" || test "$current" = "$original"
cleanup() {
    result=$?
    trap - EXIT
    mount -o remount,ro /system || result=92
    $bb grep -E ' /system [^ ]* ro(,| )' /proc/mounts >/dev/null || result=93
    echo "ROLLBACK_EXIT:$result"
    exit "$result"
}
trap cleanup EXIT
mount -o remount,rw /system
cp "$backup" "$stage"
chmod 644 "$stage"
chown 0:0 "$stage"
test "$($bb sha256sum "$stage" | $bb cut -d ' ' -f 1)" = "$original"
# Retain a link to any still-mapped candidate so remount-ro is possible before
# restarting its process. Do not remove these retained inodes during the trial.
ln "$target" "$held"
mv "$stage" "$target"
sync
test "$($bb sha256sum "$target" | $bb cut -d ' ' -f 1)" = "$original"
echo OEM_ODEX_RESTORED_RESTART_PENDING
