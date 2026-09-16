# Q50 Info — исходники

Приложение под **Android 2.3.0 (API 9)**. Показывает на экране головного
устройства параметры, от которых зависит, встанет ли на него сторонний APK:
версию Android, API level, архитектуру процессора, разрешение экрана, состояние
настройки «неизвестные источники» и наличие `su`.

Значения можно скопировать в буфер или сохранить в `q50-info.txt` на накопитель —
чтобы не переписывать их с экрана вручную в [`../README.md`](../README.md).

## Сборка

```bash
./build.sh
```

Результат: `build/q50info-1.0.apk`. Скрипт сам скачает `android.jar` и создаст
ключ подписи при первом запуске.

## Установка инструментов

На Debian/Ubuntu всё есть в штатном репозитории — Android SDK и Android Studio
не нужны:

```bash
sudo apt-get install -y aapt android-sdk-build-tools dalvik-exchange \
                        openjdk-17-jdk-headless
```

Осторожно: пакет с именем **`dx`** в Ubuntu — это *Open Visualization Data
Explorer*, научная визуализация, к Android он отношения не имеет. Компилятор dex
называется `dalvik-exchange`. `build.sh` это учитывает: он проверяет не имя
бинарника, а поддерживаемые им флаги.

## Почему не Gradle

- Android Gradle Plugin не поддерживает `minSdk 9`.
- AndroidX требует минимум API 21, support-library — API 14.

Поэтому сборка идёт классической цепочкой:

```
aapt  ->  javac  ->  dx  ->  zipalign  ->  apksigner
```

## Ограничения API 9, заложенные в код

| Ограничение | Как обойдено |
|---|---|
| `Build.SUPPORTED_ABIS` появился в API 21 | `Build.CPU_ABI` + `CPU_ABI2` |
| `android.content.ClipboardManager` — с API 11 | `android.text.ClipboardManager` |
| `StatFs.getBlockSizeLong()` — с API 18 | `getBlockSize()` с приведением к `long` |
| `INSTALL_NON_MARKET_APPS` переехал в `Settings.Global` в API 17 | читается из `Settings.Secure` |
| `setTextIsSelectable()` — с API 11 | не используется |
| XML-разметка и темы | вёрстка создаётся кодом |

Компиляция идёт с `-classpath android-2.3.3.jar`, поэтому вызвать android-API
новее API 10 просто не получится — компилятор не найдёт символ.

## Тонкости, которые ломают сборку под 2.3

**Байт-код.** `dx` читает class file version не выше 51 (Java 7). `javac` из
JDK 20+ больше не умеет `-source 7`, поэтому нужен JDK 8–17 и флаг
`--release 7`. Скрипт ищет подходящий компилятор сам.

**Подпись.** Android 2.3 знает только схему v1 (JAR signing) и только
SHA1-дайджесты: SHA-256 в JAR-подписи появился лишь в Android 4.3 (API 18).
Флаг `--min-sdk-version 9` заставляет `apksigner` выбрать SHA1 и отключить
v2/v3. Проверить готовый пакет:

```bash
apksigner verify --min-sdk-version 9 --verbose build/q50info-1.0.apk
```

**Порядок операций.** `zipalign` строго до подписи — `apksigner` выравнивание
сохраняет, а `zipalign` после подписи её ломает.

**Путь к classes.dex.** `aapt add` записывает путь ровно таким, каким его
передали, поэтому `classes.dex` добавляется из своего каталога — иначе внутри
APK он окажется как `build/classes.dex`, и система его не найдёт.

## Ключ подписи

Создаётся при первой сборке в `.cache/q50.keystore` (пароль `q50pass`, алиас
`q50`). Каталог `.cache/` в `.gitignore`: ключи подписи в репозитории хранить
нельзя.

**Сохраните этот файл отдельно.** Android разрешает обновлять установленное
приложение только пакетом, подписанным тем же ключом. Потеряете ключ — новую
версию получится поставить лишь после удаления старой, вместе с её данными.

Свой ключ можно подставить через переменные окружения:

```bash
Q50_KEYSTORE=~/my.keystore Q50_KEY_ALIAS=mykey Q50_KEY_PASS=secret ./build.sh
```

## Структура

```
app/
├── build.sh                          сборка
├── AndroidManifest.xml               minSdk 9, targetSdk 10
├── res/
│   ├── values/strings.xml
│   └── drawable-{l,m,h,xh}dpi/       иконки лаунчера 36/48/72/96 px
└── src/com/q50/info/MainActivity.java
```
