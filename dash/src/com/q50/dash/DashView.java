package com.q50.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Приборка Q50 с тремя экранами (переключение тапом справа снизу):
 *   0 — ШКАЛЫ: тахометр-дуга + плитки основных параметров;
 *   1 — ГРАФИК: живая кривая выбранного сигнала (тап слева/справа — выбор);
 *   2 — ВСЕ СИГНАЛЫ: полный список VS_ID с текущим и мин/макс значениями.
 * Рисуется на Canvas под экран 800x480.
 */
public class DashView extends View {

    private static final int BG    = 0xFF14181E;
    private static final int PANEL = 0xFF1E242C;
    private static final int GRID  = 0xFF2C333C;
    private static final int FG    = 0xFFE8EDF2;
    private static final int DIM    = 0xFF8A94A0;
    private static final int ACCENT = 0xFFE2B76B;
    private static final int WARN   = 0xFFE0A030;
    private static final int RED    = 0xFFE0503C;
    private static final int GREEN  = 0xFF5FB87A;
    private static final int CYAN   = 0xFF57C7D4;

    private static final int HIST = 260;   // длина истории графика

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final Map<String, Float> values = new HashMap<String, Float>();
    private final Map<String, Float> mins = new HashMap<String, Float>();
    private final Map<String, Float> maxs = new HashMap<String, Float>();
    private final List<String> order = new ArrayList<String>();   // порядок появления сигналов

    private int page;
    private String graphId = "VS_ID_ENGINE_RPM";
    private final float[] hist = new float[HIST];
    private int histPos;
    private int histLen;

    private String status = "";
    private int liveCount = -1;

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
        if (vsId == null) {
            return;
        }
        String id = vsId.toUpperCase();
        if (!values.containsKey(id)) {
            order.add(id);
            mins.put(id, new Float(raw));
            maxs.put(id, new Float(raw));
        } else {
            if (raw < mins.get(id).floatValue()) mins.put(id, new Float(raw));
            if (raw > maxs.get(id).floatValue()) maxs.put(id, new Float(raw));
        }
        values.put(id, new Float(raw));
        if (id.equals(graphId)) {
            hist[histPos] = raw;
            histPos = (histPos + 1) % HIST;
            if (histLen < HIST) histLen++;
        }
    }

    public void setStatus(String s) { status = s == null ? "" : s; }
    public void setLiveCount(int n) { liveCount = n; }

    private Float raw(String id) { return values.get(id); }

    // ------------------------------------------------------- переключение

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) {
            return true;
        }
        float x = e.getX(), y = e.getY();
        int w = getWidth(), h = getHeight();
        // правый нижний угол — следующая страница
        if (x > w * 0.78f && y > h * 0.8f) {
            page = (page + 1) % 3;
            if (page == 1) resetHist();
            invalidate();
            return true;
        }
        // на графике: левая/правая половина — выбор сигнала
        if (page == 1) {
            List<String> sig = vehicleSignals();
            if (!sig.isEmpty()) {
                int idx = Math.max(0, sig.indexOf(graphId));
                idx = (x < w / 2f) ? (idx - 1 + sig.size()) % sig.size()
                                   : (idx + 1) % sig.size();
                graphId = sig.get(idx);
                resetHist();
                invalidate();
            }
        }
        return true;
    }

    private void resetHist() {
        histPos = 0;
        histLen = 0;
    }

    private List<String> vehicleSignals() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i);
            if (id.indexOf("VS_ID") >= 0) out.add(id);
        }
        Collections.sort(out);
        return out;
    }

    // ------------------------------------------------------------- рисование

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(BG);
        cv.drawRect(0, 0, w, h, p);

        if (page == 0) drawGauges(cv, w, h);
        else if (page == 1) drawGraph(cv, w, h);
        else drawSignals(cv, w, h);

        // кнопка страницы
        p.setColor(PANEL);
        cv.drawRect(w - 96, h - 34, w - 6, h - 4, p);
        p.setColor(ACCENT);
        p.setTextSize(14f);
        p.setTextAlign(Paint.Align.CENTER);
        String[] names = {"ШКАЛЫ", "ГРАФИК", "СИГНАЛЫ"};
        cv.drawText(names[(page + 1) % 3] + " ›", w - 51, h - 13, p);
    }

    // ---- страница 0: шкалы --------------------------------------------------

    private void drawGauges(Canvas cv, int w, int h) {
        float cx = h * 0.52f, cy = h * 0.46f, r = h * 0.38f;
        drawTacho(cv, cx, cy, r);

        float gridLeft = h * 0.98f, gridTop = h * 0.06f;
        float colW = (w - gridLeft - 12) / 2f;
        float rowH = (h - gridTop - 44) / 3f;
        for (int i = 0; i < TILES.length; i++) {
            drawTile(cv, TILES[i], gridLeft + (i % 2) * colW,
                    gridTop + (i / 2) * rowH, colW - 10, rowH - 10);
        }
        footer(cv, w, h);
    }

    private void drawTacho(Canvas cv, float cx, float cy, float r) {
        float start = 135f, sweep = 270f;
        int max = Signals.RPM_MAX;
        RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(8f, r * 0.14f));
        p.setColor(PANEL);
        cv.drawArc(box, start, sweep, false, p);
        float redStart = start + sweep * ((float) Signals.REDLINE / max);
        p.setColor(RED);
        cv.drawArc(box, redStart, start + sweep - redStart, false, p);
        Float rr = raw("VS_ID_ENGINE_RPM");
        float rpm = rr == null ? 0 : Math.max(0, rr.floatValue());
        p.setColor(rpm >= Signals.REDLINE ? RED : ACCENT);
        cv.drawArc(box, start, sweep * Math.min(rpm / max, 1f), false, p);

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(r * 0.11f);
        for (int n = 0; n <= max / 1000; n++) {
            float a = (float) Math.toRadians(start + sweep * (n * 1000f / max));
            p.setColor(n * 1000 >= Signals.REDLINE ? RED : DIM);
            cv.drawText(String.valueOf(n),
                    cx + (float) Math.cos(a) * r * 0.72f,
                    cy + (float) Math.sin(a) * r * 0.72f + r * 0.04f, p);
        }
        p.setColor(FG);
        p.setTextSize(r * 0.6f);
        cv.drawText(String.valueOf(Math.round(rpm)), cx, cy + r * 0.12f, p);
        p.setColor(DIM);
        p.setTextSize(r * 0.15f);
        cv.drawText("об/мин", cx, cy + r * 0.36f, p);
    }

    private void drawTile(Canvas cv, String id, float x, float y, float w, float h) {
        Signals.Metric m = Signals.metric(id);
        Float rv = raw(id);
        p.setStyle(Paint.Style.FILL);
        p.setColor(PANEL);
        cv.drawRect(x, y, x + w, y + h, p);
        p.setColor(DIM);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(h * 0.2f);
        cv.drawText(m != null ? m.label : Signals.shortLabel(id), x + 10, y + h * 0.26f, p);

        String valStr = "--";
        int col = FG;
        String unit = m != null ? m.unit : "";
        if (rv != null) {
            float v = rv.floatValue() * (m != null ? m.scale : 1f);
            valStr = fmt(v);
            if (m != null && m.redv > 0 && v >= m.redv) col = RED;
            else if (m != null && m.warn > 0 && v >= m.warn) col = WARN;
            else col = GREEN;
        }
        p.setColor(col);
        p.setTextSize(h * 0.42f);
        cv.drawText(valStr, x + 10, y + h * 0.72f, p);
        p.setColor(DIM);
        p.setTextSize(h * 0.18f);
        cv.drawText(unit, x + 10, y + h * 0.94f, p);
    }

    // ---- страница 1: график -------------------------------------------------

    private void drawGraph(Canvas cv, int w, int h) {
        Signals.Metric m = Signals.metric(graphId);
        String label = m != null ? m.label : Signals.shortLabel(graphId);
        String unit = m != null ? m.unit : "";
        float scale = m != null ? m.scale : 1f;

        float left = 54, top = 40, right = w - 16, bot = h - 48;

        // рамка и сетка
        p.setStyle(Paint.Style.FILL);
        p.setColor(PANEL);
        cv.drawRect(left, top, right, bot, p);

        // диапазон по данным
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < histLen; i++) {
            float v = hist[i] * scale;
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        if (histLen == 0) { lo = 0; hi = 1; }
        if (hi - lo < 1e-3f) { hi = lo + 1; }
        float pad = (hi - lo) * 0.1f;
        lo -= pad; hi += pad;

        p.setColor(GRID);
        p.setStrokeWidth(1f);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(12f);
        for (int g = 0; g <= 4; g++) {
            float yy = bot - (bot - top) * g / 4f;
            cv.drawLine(left, yy, right, yy, p);
            p.setColor(DIM);
            cv.drawText(fmt(lo + (hi - lo) * g / 4f), left - 4, yy + 4, p);
            p.setColor(GRID);
        }

        // кривая
        if (histLen > 1) {
            path.reset();
            for (int i = 0; i < histLen; i++) {
                int idx = (histPos - histLen + i + HIST * 2) % HIST;
                float v = hist[idx] * scale;
                float xx = left + (right - left) * i / (float) (HIST - 1);
                float yy = bot - (bot - top) * (v - lo) / (hi - lo);
                if (i == 0) path.moveTo(xx, yy);
                else path.lineTo(xx, yy);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f);
            p.setColor(CYAN);
            cv.drawPath(path, p);
        }

        // подписи
        p.setStyle(Paint.Style.FILL);
        p.setColor(FG);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(20f);
        Float cur = raw(graphId);
        String now = cur == null ? "--" : fmt(cur.floatValue() * scale);
        cv.drawText(label + ": " + now + " " + unit, left, top - 12, p);
        p.setColor(DIM);
        p.setTextSize(13f);
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText("‹ тап слева/справа — другой сигнал ›", w / 2f, h - 14, p);
    }

    // ---- страница 2: все сигналы -------------------------------------------

    private void drawSignals(Canvas cv, int w, int h) {
        p.setColor(FG);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(16f);
        List<String> sig = vehicleSignals();
        cv.drawText("ВСЕ СИГНАЛЫ CAN: " + sig.size(), 12, 22, p);

        float y = 44, rowH = 21;
        int col2 = (int) (w * 0.52f), col3 = (int) (w * 0.7f), col4 = (int) (w * 0.85f);
        p.setColor(DIM);
        p.setTextSize(11f);
        cv.drawText("сигнал", 12, y - 4, p);
        cv.drawText("значение", col2, y - 4, p);
        cv.drawText("мин", col3, y - 4, p);
        cv.drawText("макс", col4, y - 4, p);

        p.setTextSize(12f);
        for (int i = 0; i < sig.size() && y < h - 40; i++) {
            String id = sig.get(i);
            p.setColor(FG);
            cv.drawText(Signals.shortLabel(id), 12, y + 12, p);
            p.setColor(GREEN);
            cv.drawText(fmt(values.get(id).floatValue()), col2, y + 12, p);
            p.setColor(DIM);
            cv.drawText(fmt(mins.get(id).floatValue()), col3, y + 12, p);
            cv.drawText(fmt(maxs.get(id).floatValue()), col4, y + 12, p);
            y += rowH;
        }
    }

    // ----------------------------------------------------------- общее

    private void footer(Canvas cv, int w, int h) {
        p.setColor(DIM);
        p.setTextSize(14f);
        p.setTextAlign(Paint.Align.LEFT);
        String foot = liveCount >= 0 ? (liveCount + " сигналов в реальном времени")
                : (status.length() > 0 ? status : "ожидание данных…");
        cv.drawText(foot, 16, h - 12, p);
    }

    private String fmt(float v) {
        float a = Math.abs(v);
        if (a >= 100) return String.valueOf(Math.round(v));
        if (a >= 10) return String.valueOf(Math.round(v * 10) / 10f);
        return String.valueOf(Math.round(v * 100) / 100f);
    }
}
