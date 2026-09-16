#!/usr/bin/env bash
#
# Сборка APK под Android 2.3.0 (API 9) без Gradle и без Android Studio.
#
# Почему не Gradle: современный Android Gradle Plugin не поддерживает minSdk 9,
# а AndroidX требует минимум API 21. Здесь классическая цепочка
# aapt -> javac -> dx -> zipalign -> apksigner, которая с API 9 работает.
#
set -euo pipefail

cd "$(dirname "$0")"

PKG_NAME="q50info"
VERSION="1.2"
MIN_SDK=9

BUILD_DIR="build"
CACHE_DIR=".cache"
ANDROID_JAR="$CACHE_DIR/android-2.3.3.jar"
ANDROID_JAR_URL="https://repo1.maven.org/maven2/com/google/android/android/2.3.3/android-2.3.3.jar"
KEYSTORE="${Q50_KEYSTORE:-$CACHE_DIR/q50.keystore}"
KEY_ALIAS="${Q50_KEY_ALIAS:-q50}"
KEY_PASS="${Q50_KEY_PASS:-q50pass}"

UNSIGNED="$BUILD_DIR/$PKG_NAME-unsigned.apk"
ALIGNED="$BUILD_DIR/$PKG_NAME-aligned.apk"
OUTPUT="$BUILD_DIR/$PKG_NAME-$VERSION.apk"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31mОшибка: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- зависимости

for t in aapt zipalign apksigner keytool; do
    command -v "$t" >/dev/null 2>&1 || die "не найден '$t'. См. app/README.md, раздел «Установка инструментов»."
done

# dx не понимает байт-код Java 8 (class file version 52), а javac из JDK 20+
# больше не умеет -source 7. Поэтому нужен JDK 8..17.
find_javac() {
    local candidates=()
    # ${JAVA17_HOME}/bin/javac при пустой переменной схлопнулось бы в /bin/javac,
    # то есть в системный JDK — поэтому переменную проверяем отдельно.
    [ -n "${JAVA17_HOME:-}" ] && candidates+=("$JAVA17_HOME/bin/javac")
    candidates+=(/usr/lib/jvm/java-17-openjdk*/bin/javac)
    candidates+=(/usr/lib/jvm/java-11-openjdk*/bin/javac)
    candidates+=(/usr/lib/jvm/java-8-openjdk*/bin/javac)
    command -v javac >/dev/null 2>&1 && candidates+=("$(command -v javac)")

    local c v
    for c in "${candidates[@]}"; do
        [ -x "$c" ] || continue
        v=$("$c" -version 2>&1 | grep -oE '[0-9]+' | head -1)
        # 8..17: ниже 8 нет нужного javac, выше 17 нет -source 7
        if [ -n "$v" ] && [ "$v" -ge 8 ] && [ "$v" -le 17 ]; then
            echo "$c"
            return 0
        fi
    done
    return 1
}
JAVAC=$(find_javac) || die "нужен JDK 8-17 (javac из JDK 18+ не умеет -source 7, а dx не читает Java 8 байт-код)."

# Компилятор dex называется по-разному: d8 в современных build-tools, dx в
# старых, dalvik-exchange в Debian/Ubuntu. Проверяем не только имя, но и флаги:
# в Ubuntu есть посторонний пакет "dx" (Open Visualization Data Explorer),
# который ничего не знает про Android.
DEX_TOOL=""; DEX_KIND=""
for c in d8 dx dalvik-exchange; do
    path=$(command -v "$c" 2>/dev/null) || continue
    help=$("$path" --help 2>&1 || true)
    if printf '%s' "$help" | grep -q -- '--min-api'; then
        DEX_TOOL="$path"; DEX_KIND="d8"; break
    elif printf '%s' "$help" | grep -q -- '--dex'; then
        DEX_TOOL="$path"; DEX_KIND="dx"; break
    fi
done
[ -n "$DEX_TOOL" ] || die "не найден компилятор dex (d8, dx или dalvik-exchange). См. app/README.md."

say "Инструменты"
echo "javac:     $JAVAC ($("$JAVAC" -version 2>&1 | tail -1))"
echo "aapt:      $(command -v aapt)"
echo "dex:       $DEX_TOOL ($DEX_KIND)"
echo "apksigner: $(command -v apksigner)"

# --------------------------------------------------------------- android.jar

mkdir -p "$CACHE_DIR" "$BUILD_DIR"
if [ ! -f "$ANDROID_JAR" ]; then
    say "Скачиваю android.jar от SDK 2.3.3"
    curl -fsSL -o "$ANDROID_JAR" "$ANDROID_JAR_URL" \
        || die "не удалось скачать $ANDROID_JAR_URL"
fi

# ------------------------------------------------------------------- сборка

rm -rf "$BUILD_DIR/classes" "$UNSIGNED" "$ALIGNED" "$OUTPUT"
mkdir -p "$BUILD_DIR/classes"

say "1/5 aapt: ресурсы и манифест"
aapt package -f \
    -M AndroidManifest.xml \
    -S res \
    -I "$ANDROID_JAR" \
    -F "$UNSIGNED"

say "2/5 javac: компиляция под Java 7"
# --release 7 даёт class file version 51 — максимум, который читает dx.
#
# android.jar идёт через -classpath, а не -bootclasspath: в артефакте с Maven
# Central лежат только пакеты android/* и dalvik/*, без java.lang, поэтому в
# роли bootclasspath он работать не может. Сигнатуры java.* берутся из ct.sym
# самого JDK. Побочный эффект: компилятор не проверяет, что вызываемый java.*
# API существовал в libcore Android 2.3 (это примерно Java 6) — за этим нужно
# следить самому.
"$JAVAC" \
    --release 7 \
    -classpath "$ANDROID_JAR" \
    -encoding UTF-8 \
    -nowarn \
    -d "$BUILD_DIR/classes" \
    $(find src -name '*.java')

say "3/5 dx: байт-код JVM -> Dalvik"
if [ "$DEX_KIND" = "d8" ]; then
    "$DEX_TOOL" --min-api "$MIN_SDK" --output "$BUILD_DIR" \
        $(find "$BUILD_DIR/classes" -name '*.class')
else
    "$DEX_TOOL" --dex --min-sdk-version="$MIN_SDK" \
        --output="$BUILD_DIR/classes.dex" \
        "$BUILD_DIR/classes"
fi

# aapt add записывает путь ровно в том виде, в каком он передан, поэтому
# classes.dex нужно добавлять из его собственного каталога — иначе внутри APK
# он окажется как "build/classes.dex" и система его не найдёт.
( cd "$BUILD_DIR" && aapt add -f "$(basename "$UNSIGNED")" classes.dex >/dev/null )

say "4/5 zipalign"
# Выравнивание строго до подписи: apksigner его сохраняет, а zipalign после
# подписи её сломает.
zipalign -f 4 "$UNSIGNED" "$ALIGNED"

say "5/5 apksigner: подпись v1"
if [ ! -f "$KEYSTORE" ]; then
    echo "Создаю ключ: $KEYSTORE"
    keytool -genkeypair -noprompt \
        -keystore "$KEYSTORE" \
        -alias "$KEY_ALIAS" \
        -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
        -keyalg RSA -keysize 2048 -sigalg SHA1withRSA \
        -validity 10000 \
        -dname "CN=Q50, OU=Head Unit, O=Personal, L=-, ST=-, C=RU" 2>&1 | grep -v '^Warning' || true
fi

# Android 2.3 знает только схему подписи v1 (JAR signing) и только SHA1-дайджесты.
# Флаг --min-sdk-version заставляет apksigner выбрать именно их.
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEY_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --min-sdk-version "$MIN_SDK" \
    --v1-signing-enabled true \
    --v2-signing-enabled false \
    --v3-signing-enabled false \
    --out "$OUTPUT" \
    "$ALIGNED"

rm -f "$UNSIGNED" "$ALIGNED"

say "Проверка"
apksigner verify --min-sdk-version "$MIN_SDK" --verbose "$OUTPUT"
aapt dump badging "$OUTPUT" | grep -E "^package|sdkVersion|targetSdkVersion|application-label:|uses-permission"

say "Готово: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
