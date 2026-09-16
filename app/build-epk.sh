#!/usr/bin/env bash
#
# Собрать APK и упаковать его в контейнер .epk для головного устройства Q50.
#
# ГУ не распознаёт голый APK — установить приложение можно только как .epk.
# Для сборки принимаемого устройством пакета нужен ОТКРЫТЫЙ ключ RSA из
# прошивки ГУ (см. docs/epk-format.md). Без него скрипт всё равно соберёт
# .epk, но с нулевым m_key — устройство такой пакет отвергнет; это годится
# лишь чтобы проверить сам факт приёма формата.
#
set -euo pipefail
cd "$(dirname "$0")"

RSA_PUB="${Q50_RSA_PUB:-}"          # открытый ключ IVI в PEM, если есть
MODE="${Q50_EPK_MODE:-cbc}"          # режим AES (уточнить по прошивке)
NAME="${Q50_EPK_NAME:-q50info.apk}"  # имя вложенного файла в заголовке

./build.sh
APK="build/$(ls -t build | grep -E '^q50info-.*\.apk$' | head -1)"
OUT="build/q50info.epk"

echo
echo "==> Упаковка $APK -> $OUT"
if [ -n "$RSA_PUB" ]; then
    python3 ../tools/epktool.py pack "$APK" -o "$OUT" \
        --name "$NAME" --rsa-pub "$RSA_PUB" --mode "$MODE"
else
    echo "    Q50_RSA_PUB не задан — m_key будет нулевым, устройство отвергнет пакет."
    python3 ../tools/epktool.py pack "$APK" -o "$OUT" --name "$NAME"
fi

echo
python3 ../tools/epktool.py info "$OUT" | sed -n '3,16p'
echo "==> Готово: $OUT"
