package com.q50.dash;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Q50 Dash — приборка для головного устройства Infiniti InTouch (V37 Q50/Q60).
 *
 * Читает сигналы CAN, которые ГУ отдаёт как Android-сенсоры (вендор Ygomi,
 * VS_ID_*, типы 12..53), и рисует калиброванные шкалы. Чистая Java, без
 * нативного кода — работает на x86 API-10. Нужно разрешение
 * com.ygomi.permission.IVI_CAN_READ (dangerous, авто-выдача на 2.3).
 */
public class MainActivity extends Activity implements SensorEventListener {

    private SensorManager sm;
    private DashView view;
    private final Map<Integer, String> typeName = new HashMap<Integer, String>();
    private final Handler handler = new Handler();
    private boolean dirty;

    // перерисовка не чаще ~20 Гц, чтобы не грузить старый CPU
    private final Runnable repaint = new Runnable() {
        public void run() {
            if (dirty) {
                dirty = false;
                view.invalidate();
            }
            handler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new DashView(this);
        setContentView(view);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int vehicle = 0;
        if (sm != null) {
            List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
            if (all != null) {
                for (int i = 0; i < all.size(); i++) {
                    Sensor s = all.get(i);
                    typeName.put(new Integer(s.getType()), s.getName());
                    if (isVehicle(s)) {
                        vehicle++;
                    }
                    try {
                        sm.registerListener(this, s, 100000);   // ~10 Гц
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (vehicle == 0) {
            view.setStatus("Шина не найдена — запустите на автомобиле");
        } else {
            view.setLiveCount(vehicle);
        }
        handler.postDelayed(repaint, 50);
        view.invalidate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(repaint);
        if (sm != null) {
            try {
                sm.unregisterListener(this);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isVehicle(Sensor s) {
        String name = s.getName() == null ? "" : s.getName().toUpperCase();
        String vendor = s.getVendor() == null ? "" : s.getVendor().toUpperCase();
        int t = s.getType();
        return vendor.indexOf("YGOMI") >= 0 || name.indexOf("VS_ID") >= 0
                || (t >= 12 && t <= 53);
    }

    public void onSensorChanged(SensorEvent e) {
        String name = typeName.get(new Integer(e.sensor.getType()));
        if (name == null) {
            name = e.sensor.getName();
        }
        if (name != null && e.values != null && e.values.length > 0) {
            view.setValue(name, e.values[0]);
            dirty = true;
        }
    }

    public void onAccuracyChanged(Sensor s, int a) {
    }
}
