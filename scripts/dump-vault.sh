#!/bin/sh
# Copy vault off the phone before any uninstall. Encrypted file alone is useless after uninstall.
set -e
PKG=ru.srr.passwords
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/vault-dumps/$(date +%Y%m%d-%H%M%S)"
ADB="${ADB:-adb}"

if ! "$ADB" shell pm path "$PKG" 2>/dev/null | grep -q "package:"; then
  echo "dump-vault: $PKG not installed"
  exit 0
fi

mkdir -p "$OUT"
"$ADB" shell am broadcast --receiver-foreground \
  -a ru.srr.passwords.DUMP_VAULT \
  -n "$PKG/.dump.VaultDumpReceiver" >/dev/null
sleep 2

"$ADB" exec-out run-as "$PKG" cat files/passwords-dump-latest.csv > "$OUT/passwords-dump-latest.csv" || true
"$ADB" exec-out run-as "$PKG" cat files/passwords-dump-latest.json > "$OUT/passwords-dump-latest.json" || true
"$ADB" exec-out run-as "$PKG" cat files/vault.json.enc > "$OUT/vault.json.enc" || true
"$ADB" pull /sdcard/Download/passwords-dump-latest.csv "$OUT/download-passwords-dump-latest.csv" 2>/dev/null || true
"$ADB" pull /sdcard/Download/passwords-dump-latest.json "$OUT/download-passwords-dump-latest.json" 2>/dev/null || true

csv_ok=0
[ -s "$OUT/passwords-dump-latest.csv" ] && csv_ok=1
[ -s "$OUT/download-passwords-dump-latest.csv" ] && csv_ok=1
if [ "$csv_ok" -eq 1 ]; then
  echo "dump-vault: $OUT"
  exit 0
fi
if [ -s "$OUT/vault.json.enc" ]; then
  echo "dump-vault: only encrypted copy (CSV dump needs debug APK with receiver): $OUT"
  exit 0
fi
echo "dump-vault: failed, empty dump in $OUT" >&2
exit 1
