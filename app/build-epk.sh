#!/usr/bin/env bash
#
# Собрать APK и упаковать его в загружаемый .epk для головного устройства Q50.
#
# ГУ не распознаёт голый APK — только .epk. Для сборки нужен ПУБЛИЧНЫЙ
# сертификат OBU (закрытый ключ не нужен). По умолчанию берётся включённый
# ../keys/obu_cert.pem; свой задаётся через Q50_OBU_CERT.
#
set -euo pipefail
cd "$(dirname "$0")"

CERT="${Q50_OBU_CERT:-../keys/obu_cert.pem}"

./build.sh
APK="build/$(ls -t build | grep -E '^q50info-.*\.apk$' | head -1)"
OUT="build/$(basename "${APK%.apk}").epk"

echo
echo "==> Упаковка $APK -> $OUT сертификатом $CERT"
python3 ../tools/epktool.py build "$APK" -o "$OUT" --cert "$CERT"
echo
python3 ../tools/epktool.py info "$OUT"
echo "==> Готово: $OUT — скопировать в корень USB (FAT32), выбрать в AppManager."
