package com.q50.info;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Чтение CAN-шины Infiniti InTouch через SensorManager.
 *
 * На этом ГУ шина отдаётся НЕ через SocketCAN, а как обычные Android-сенсоры:
 * вендор "Ygomi", имена VS_ID_*, типы сенсоров в диапазоне 12..53. Любое
 * приложение читает их штатным SensorManager + registerListener, значения
 * приходят в onSensorChanged(e).values. Нужно разрешение
 * com.ygomi.permission.IVI_CAN_READ — оно dangerous-уровня и на Android 2.3
 * выдаётся自动 обычному самоподписанному приложению.
 *
 * Механизм подтверждён открытой реализацией qazwsd147/appgarage-dash.
 *
 * Класс перечисляет все сенсоры (это сразу даёт каталог CAN-сигналов вашей
 * машины) и хранит последние значения, чтобы показать их живьём.
 */
final class CanSensors {

    private static final int VEHICLE_TYPE_MIN = 12;
    private static final int VEHICLE_TYPE_MAX = 53;

    private final SensorManager sm;
    private final List<Sensor> sensors;
    // тип сенсора -> последняя строка значений
    private final Map<Integer, String> values = new HashMap<Integer, String>();

    CanSensors(Context ctx) {
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        sensors = (sm == null) ? null : sm.getSensorList(Sensor.TYPE_ALL);
    }

    SensorManager manager() {
        return sm;
    }

    List<Sensor> sensors() {
        return sensors;
    }

    static boolean isVehicle(Sensor s) {
        String name = s.getName() == null ? "" : s.getName().toUpperCase();
        String vendor = s.getVendor() == null ? "" : s.getVendor().toUpperCase();
        int t = s.getType();
        return vendor.indexOf("YGOMI") >= 0
                || name.indexOf("VS_ID") >= 0
                || (t >= VEHICLE_TYPE_MIN && t <= VEHICLE_TYPE_MAX);
    }

    /** Запомнить пришедшее значение. Возвращает true, если сенсор автомобильный. */
    boolean update(Sensor s, float[] v) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(v.length, 4);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(fmt(v[i]));
        }
        values.put(new Integer(s.getType()), sb.toString());
        return isVehicle(s);
    }

    private static String fmt(float f) {
        if (f == (long) f) {
            return String.valueOf((long) f);
        }
        return String.valueOf(((long) (f * 1000)) / 1000.0f);
    }

    void appendTo(StringBuilder b) {
        b.append('\n').append("== CAN-СЕНСОРЫ (Ygomi) ==\n");

        if (sm == null) {
            b.append("SENSOR_SERVICE недоступен.\n");
            return;
        }
        if (sensors == null || sensors.isEmpty()) {
            b.append("Сенсоров нет — ГУ не отдаёт CAN через SensorManager.\n");
            return;
        }

        int vehicle = 0;
        int live = 0;
        // сначала автомобильные, потом остальные
        StringBuilder veh = new StringBuilder();
        StringBuilder other = new StringBuilder();
        for (int i = 0; i < sensors.size(); i++) {
            Sensor s = sensors.get(i);
            String val = values.get(new Integer(s.getType()));
            if (val != null) {
                live++;
            }
            if (isVehicle(s)) {
                vehicle++;
                veh.append("  ").append(vehicle).append(". t")
                   .append(s.getType()).append(" ").append(s.getName())
                   .append(val == null ? "  (нет данных)" : " = " + val)
                   .append('\n');
            } else {
                other.append("  t").append(s.getType()).append(" ")
                     .append(s.getName()).append(" (").append(s.getVendor()).append(")")
                     .append(val == null ? "" : " = " + val).append('\n');
            }
        }

        b.append("Всего сенсоров: ").append(sensors.size())
                .append(", автомобильных: ").append(vehicle)
                .append(", с живыми данными: ").append(live).append('\n');

        if (vehicle > 0) {
            b.append("-- CAN-сигналы --\n").append(veh);
        } else {
            b.append("Автомобильных сенсоров (Ygomi/VS_ID/тип 12-53) не найдено.\n");
            b.append("Возможно, нужно разрешение IVI_CAN_READ или другая прошивка.\n");
        }
        if (other.length() > 0) {
            b.append("-- прочие сенсоры --\n").append(other);
        }
    }
}
