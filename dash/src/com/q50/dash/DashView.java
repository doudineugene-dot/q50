package com.q50.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Приборка Q50: большой тахометр-дуга и плитки основных параметров.
 * Рисуется на Canvas под экран 800x480 InTouch. Значения приходят по имени
 * сигнала (VS_ID_*), калибровка — в Signals.
 */
public class DashView extends View {

    private static final int BG    = 0xFF14181E;
    private static final int PANEL = 0xFF1E242C;
    private static final int FG    = 0xFFE8EDF2;
    private static final int DIM    = 0xFF8A94A0;
    private static final int ACCENT = 0xFFE2B76B;
    private static final int WARN   = 0xFFE0A030;
    private static final int RED    = 0xFFE0503C;
    private static final int GREEN  = 0xFF5FB87A;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, Float> values = new HashMap<String, Float>();
    private String status = "";
    private int liveCount = -1;

    // порядок и состав плиток справа
    private static final String[] TILES = {
            "VS_ID_ENGINE_OIL_PRESSURE",
            "VS_ID_ENGINE_OIL_TEMPERATURE",
            "VS_ID_ENGINE_COOLANT_TEMPERATURE",
            "VS_ID_EFFECTIVE_TORQUE",
            "VS_ID_VEHICLE_SPEED",
            "VS_ID_TRANSVERSAL_ACCELERATION"
    };

    public DashView(Context c) {
        super(c);
    }

    public void setValue(String vsId, float raw) {
        if (vsId != null) {
            values.put(vsId.toUpperCase(), new Float(raw));
        }
    }

    public void setStatus(String s) {
        status = s == null ? "" : s;
    }

    public void setLiveCount(int n) {
        liveCount = n;
    }

    private Float raw(String id) {
        return values.get(id);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(BG);
        cv.drawRect(0, 0, w, h, p);

        // левая зона — тахометр
        float gaugeCx = h * 0.52f;
        float gaugeCy = h * 0.46f;
        float gaugeR = h * 0.38f;
        drawTacho(cv, gaugeCx, gaugeCy, gaugeR);

        // правая зона — плитки 2 столбца
        float gridLeft = h * 0.98f;
        float gridTop = h * 0.06f;
        float colW = (w - gridLeft - 12) / 2f;
        float rowH = (h - gridTop - 40) / 3f;
        for (int i = 0; i < TILES.length; i++) {
            int col = i % 2;
            int row = i / 2;
            drawTile(cv, TILES[i],
                    gridLeft + col * colW, gridTop + row * rowH,
                    colW - 10, rowH - 10);
        }

        // нижняя строка статуса
        p.setColor(DIM);
        p.setTextSize(14f);
        p.setTextAlign(Paint.Align.LEFT);
        String foot = liveCount >= 0 ? (liveCount + " сигналов CAN в реальном времени")
                : (status.length() > 0 ? status : "ожидание данных с шины…");
        cv.drawText(foot, 16, h - 12, p);
    }

    // -------------------------------------------------------- тахометр

    private void drawTacho(Canvas cv, float cx, float cy, float r) {
        float start = 135f, sweep = 270f;
        int max = Signals.RPM_MAX;

        // фон дуги
        RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(8f, r * 0.14f));
        p.setColor(PANEL);
        cv.drawArc(box, start, sweep, false, p);

        // красная зона
        float redStart = start + sweep * ((float) Signals.REDLINE / max);
        p.setColor(RED);
        cv.drawArc(box, redStart, start + sweep - redStart, false, p);

        // заполнение по оборотам
        Float rpmRaw = raw("VS_ID_ENGINE_RPM");
        float rpm = rpmRaw == null ? 0 : rpmRaw.floatValue();
        if (rpm < 0) {
            rpm = 0;
        }
        float frac = Math.min(rpm / max, 1f);
        p.setColor(rpm >= Signals.REDLINE ? RED : ACCENT);
        cv.drawArc(box, start, sweep * frac, false, p);

        // деления и подписи ×1000
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(r * 0.11f);
        for (int n = 0; n <= max / 1000; n++) {
            float a = (float) Math.toRadians(start + sweep * (n * 1000f / max));
            float tx = cx + (float) Math.cos(a) * (r * 0.72f);
            float ty = cy + (float) Math.sin(a) * (r * 0.72f) + r * 0.04f;
            p.setColor(n * 1000 >= Signals.REDLINE ? RED : DIM);
            cv.drawText(String.valueOf(n), tx, ty, p);
        }

        // цифра оборотов в центре
        p.setColor(FG);
        p.setTextSize(r * 0.6f);
        cv.drawText(String.valueOf(Math.round(rpm)), cx, cy + r * 0.12f, p);
        p.setColor(DIM);
        p.setTextSize(r * 0.16f);
        cv.drawText("об/мин", cx, cy + r * 0.38f, p);

        // передача (если есть сигнал)
        Float gearRaw = raw("VS_ID_GEAR_POSITION");
        if (gearRaw == null) {
            gearRaw = raw("VS_ID_TRANSMISSION_GEAR");
        }
        if (gearRaw != null) {
            p.setColor(ACCENT);
            p.setTextSize(r * 0.3f);
            cv.drawText(gearLabel(Math.round(gearRaw.floatValue())), cx, cy - r * 0.45f, p);
        }
    }

    private String gearLabel(int g) {
        switch (g) {
            case 0: return "P";
            case 1: return "R";
            case 2: return "N";
            default: return "D" + (g - 2);
        }
    }

    // ---------------------------------------------------------- плитка

    private void drawTile(Canvas cv, String id, float x, float y, float w, float h) {
        Signals.Metric m = Signals.metric(id);
        Float rv = raw(id);

        p.setStyle(Paint.Style.FILL);
        p.setColor(PANEL);
        cv.drawRect(x, y, x + w, y + h, p);

        String label = m != null ? m.label : Signals.shortLabel(id);
        p.setColor(DIM);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(h * 0.2f);
        cv.drawText(label, x + 10, y + h * 0.26f, p);

        String valStr = "--";
        int valColor = FG;
        String unit = m != null ? m.unit : "";
        if (rv != null) {
            float v = rv.floatValue() * (m != null ? m.scale : 1f);
            valStr = fmt(v);
            if (m != null && m.redv > 0 && v >= m.redv) {
                valColor = RED;
            } else if (m != null && m.warn > 0 && v >= m.warn) {
                valColor = WARN;
            } else {
                valColor = GREEN;
            }
        }

        p.setColor(valColor);
        p.setTextSize(h * 0.42f);
        cv.drawText(valStr, x + 10, y + h * 0.72f, p);

        p.setColor(DIM);
        p.setTextSize(h * 0.18f);
        cv.drawText(unit, x + 10, y + h * 0.94f, p);
    }

    private String fmt(float v) {
        float a = Math.abs(v);
        if (a >= 100) {
            return String.valueOf(Math.round(v));
        }
        if (a >= 10) {
            return String.valueOf(Math.round(v * 10) / 10f);
        }
        return String.valueOf(Math.round(v * 100) / 100f);
    }
}
