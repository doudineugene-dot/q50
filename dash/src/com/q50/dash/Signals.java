package com.q50.dash;

import java.util.HashMap;
import java.util.Map;

/**
 * Калибровка сигналов CAN головного устройства Q50 (сенсоры Ygomi, VS_ID_*).
 *
 * Сырые значения — безразмерные float. Пересчёт откалиброван на VR30DDTT
 * (по данным открытого проекта qazwsd147/appgarage-dash, MIT):
 *   RPM, температуры (°C), скорость (км/ч), момент (Нм) — напрямую;
 *   давление масла (тип 16) — в МПа, ×10 = бар, ×145 = psi;
 *   мощность (тип 32) = rpm*Нм, ×1.047e-4 = кВт;
 *   боковое/продольное ускорение — в g.
 * Редлайн VR30DDTT — 6800.
 */
final class Signals {

    static final int REDLINE = 6800;
    static final int RPM_MAX = 7500;

    /** Описание одной метрики: подпись, единица, множитель к сырому значению. */
    static final class Metric {
        final String label;
        final String unit;
        final float scale;
        final float warn;   // порог «внимание» в единицах после scale (0 = нет)
        final float redv;   // порог «опасно» (0 = нет)

        Metric(String label, String unit, float scale, float warn, float redv) {
            this.label = label;
            this.unit = unit;
            this.scale = scale;
            this.warn = warn;
            this.redv = redv;
        }
    }

    private static final Map<String, Metric> MAP = new HashMap<String, Metric>();

    static {
        // имя VS_ID -> калибровка
        put("VS_ID_ENGINE_RPM", "ОБОРОТЫ", "об/мин", 1f, 6800, 7000);
        put("VS_ID_ENGINE_OIL_TEMPERATURE", "МАСЛО", "°C", 1f, 120, 140);
        put("VS_ID_ENGINE_OIL_PRESSURE", "ДАВЛ.МАСЛА", "бар", 10f, 0, 0);
        put("VS_ID_ENGINE_COOLANT_TEMPERATURE", "ОЖ", "°C", 1f, 105, 115);
        put("VS_ID_VEHICLE_SPEED", "СКОРОСТЬ", "км/ч", 1f, 0, 0);
        put("VS_ID_EFFECTIVE_TORQUE", "МОМЕНТ", "Нм", 1f, 0, 0);
        put("VS_ID_TRANSVERSAL_ACCELERATION", "G бок.", "g", 1f, 0, 0);
        put("VS_ID_LONGITUDINAL_ACCELERATION", "G прод.", "g", 1f, 0, 0);
        put("VS_ID_FUEL_CONSUMPTION_FINE", "РАСХОД", "л/100", 1f, 0, 0);
        put("VS_ID_DISTANCE_TO_EMPTY", "ЗАПАС", "км", 1f, 0, 0);
        put("VS_ID_THROTTLE_POSITION", "ДРОССЕЛЬ", "%", 1f, 0, 0);
        put("VS_ID_BOOST_PRESSURE", "НАДДУВ", "бар", 10f, 0, 0);
        put("VS_ID_TURBO_BOOST", "НАДДУВ", "бар", 10f, 0, 0);
        put("VS_ID_POWER", "МОЩНОСТЬ", "кВт", 1.047e-4f, 0, 0);
    }

    private static void put(String id, String label, String unit, float scale,
                            float warn, float redv) {
        MAP.put(id, new Metric(label, unit, scale, warn, redv));
    }

    static Metric metric(String vsId) {
        if (vsId == null) {
            return null;
        }
        Metric m = MAP.get(vsId.toUpperCase());
        return m;
    }

    /** Короткая подпись для неизвестного сигнала: убрать префикс VS_ID_. */
    static String shortLabel(String vsId) {
        if (vsId == null) {
            return "?";
        }
        String s = vsId.toUpperCase();
        if (s.startsWith("VS_ID_")) {
            s = s.substring(6);
        }
        return s.length() > 14 ? s.substring(0, 14) : s;
    }
}
