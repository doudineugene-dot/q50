# Что показал ГУ (Q50 Info на живом устройстве)

Данные сняты запуском Q50 Info прямо на головном устройстве. Установка прошла
через `.epk`, собранный включённым сертификатом OBU, — значит **сертификат
подошёл к прошивке, и путь доставки работает**.

## Платформа

| | |
|---|---|
| Экран | 800 × 480, 240 dpi (hdpi), 5.0 × 3.0" |
| Root | **есть** — `/system/xbin/su` |
| Неизвестные источники | запрещены (для `.epk` не требуется) |
| Локаль | en_US, GMT |
| Инструменты | есть `ip`, `ifconfig`, `busybox` (в `/system/xbin`) |

## CAN: как НЕ читается

Стандартного Linux-пути к шине на Android-стороне нет:

- `/proc/net/can` — нет (SocketCAN не поднят)
- модулей `can*` в `/proc/modules` — нет
- CAN-интерфейсов (`type=280`) — нет; есть только `lo` и `audio0` (mtu 5040)
- `/dev/can*` — нет; из портов только `tty0..63`, `ttyAdp0..3`, `ttyPCH0..2`
- `candump`/`cansend`/`cangen`/`canconfig` — не установлены

Поэтому зонд SocketCAN пуст — и это правильный отрицательный результат: он
исключает тупиковый путь.

## CAN: как читается на самом деле

Шина отдаётся **через Android SensorManager**: вендор «Ygomi», имена `VS_ID_*`,
типы сенсоров 12..53. Читается штатно:

```java
SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL))
    sm.registerListener(this, s, 200000);
// onSensorChanged(e): e.sensor.getType(), e.values[0]
```

Нужно разрешение `com.ygomi.permission.IVI_CAN_READ` (dangerous-уровня,
на Android 2.3 выдаётся自动). В манифесте — `ivi.isDistractive=false`, чтобы
приложение работало на ходу.

Q50 Info **1.3** это уже делает: перечисляет все сенсоры (каталог CAN-сигналов
машины) и показывает живые значения. Механизм — из открытого
`qazwsd147/appgarage-dash`.

## Ключ OBU на устройстве

Поиск сертификатов нашёл `obu.p12` (2026 байт) в `/etc/security/` и
`/system/etc/security/`. Это хранилище с **закрытым** ключом OBU. При наличии
root его можно снять с устройства — тогда `epktool.py parse` сможет
расшифровать чужие `.epk` (например `gtr.apk`). Для сборки своих пакетов это
не нужно — там хватает публичного сертификата.
