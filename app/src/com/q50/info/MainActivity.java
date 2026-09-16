package com.q50.info;

import android.app.Activity;
import android.app.ActivityManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.ClipboardManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Показывает параметры головного устройства, от которых зависит, встанет ли
 * на него тот или иной APK: версию Android, API level, архитектуру, экран.
 *
 * Собирается под API 9 (Android 2.3.0), поэтому здесь нет ни AndroidX, ни
 * support-library, ни XML-разметки — только то, что существует в 2.3.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final String FILE_NAME = "q50-info.txt";

    private String report;
    private CanSensors can;
    private TextView textView;
    private long lastRefresh;
    private long lastAutoSave;
    private File autoSaveDir;
    private String savedPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        can = new CanSensors(this);
        report = buildReport();

        textView = new TextView(this);
        textView.setText(report);
        textView.setTextSize(15);
        textView.setPadding(16, 16, 16, 16);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(textView);

        Button copy = new Button(this);
        copy.setText("Копировать");
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyToClipboard();
            }
        });

        Button save = new Button(this);
        save.setText("Сохранить в файл");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveToFile();
            }
        });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(copy, half);
        buttons.addView(save, half);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, 0, 1f));
        root.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        // сразу пишем отчёт на флешку, без нажатия кнопки
        autoSaveDir = UsbStorage.pickWritableDir();
        autoSave(true);
    }

    // ------------------------------------------------------------------ отчёт

    private String buildReport() {
        StringBuilder b = new StringBuilder();

        // CAN-датчики — первыми, чтобы их было видно и удобно фотографировать
        // без прокрутки. Это главное, ради чего снимается отчёт.
        can.appendTo(b);

        section(b, "СИСТЕМА");
        row(b, "Android", Build.VERSION.RELEASE);
        row(b, "API level", String.valueOf(Build.VERSION.SDK_INT));
        row(b, "Сборка", Build.VERSION.INCREMENTAL);
        row(b, "Codename", Build.VERSION.CODENAME);
        row(b, "Ядро", System.getProperty("os.version"));
        row(b, "VM", System.getProperty("java.vm.version"));

        section(b, "АРХИТЕКТУРА");
        // На API 9 нет Build.SUPPORTED_ABIS (он с API 21) — только эти два поля.
        row(b, "CPU_ABI", Build.CPU_ABI);
        row(b, "CPU_ABI2", Build.CPU_ABI2);
        row(b, "Разрядность", is64bit() ? "64 бита" : "32 бита");

        section(b, "УСТРОЙСТВО");
        row(b, "Производитель", Build.MANUFACTURER);
        row(b, "Модель", Build.MODEL);
        row(b, "Устройство", Build.DEVICE);
        row(b, "Плата", Build.BOARD);
        row(b, "Бренд", Build.BRAND);
        row(b, "Продукт", Build.PRODUCT);
        row(b, "Железо", Build.HARDWARE);
        row(b, "Fingerprint", Build.FINGERPRINT);

        section(b, "ЭКРАН");
        DisplayMetrics m = getResources().getDisplayMetrics();
        row(b, "Разрешение", m.widthPixels + " x " + m.heightPixels + " px");
        row(b, "Плотность", m.densityDpi + " dpi (" + densityBucket(m.densityDpi) + ")");
        row(b, "Масштаб", String.valueOf(m.density));
        row(b, "Физически", String.format(Locale.US, "%.2f x %.2f дюйма",
                m.widthPixels / m.xdpi, m.heightPixels / m.ydpi));

        section(b, "УСТАНОВКА ПАКЕТОВ");
        row(b, "Неизвестные источники", unknownSourcesAllowed());
        row(b, "Root (su)", suPath());

        section(b, "ПАМЯТЬ");
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        row(b, "Лимит на приложение", am.getMemoryClass() + " МБ");
        row(b, "Внутренняя", space(Environment.getDataDirectory()));
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            row(b, "Внешняя", space(Environment.getExternalStorageDirectory()));
        } else {
            row(b, "Внешняя", "не подключена (" + Environment.getExternalStorageState() + ")");
        }

        section(b, "ТРАНСПОРТЫ ДО CAN");
        row(b, "Bluetooth", bluetooth());
        row(b, "WiFi", getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI)
                ? "есть" : "нет");
        // FEATURE_USB_HOST как константа появилась только в API 12 — здесь строкой.
        // На Android 2.3 USB Host API нет, так что ответ заведомо «нет»; выводим
        // явно, чтобы не было соблазна рассчитывать на USB-донгл.
        row(b, "USB Host", getPackageManager().hasSystemFeature("android.hardware.usb.host")
                ? "есть" : "нет (в Android 2.3 USB Host API отсутствует)");

        CanProbe.appendTo(b);
        CertProbe.appendTo(b);

        section(b, "ЛОКАЛЬ");
        row(b, "Язык", Locale.getDefault().toString());
        row(b, "Часовой пояс", TimeZone.getDefault().getID());

        if (savedPath != null) {
            b.append("\nФайл сохраняется на: ").append(savedPath).append('\n');
        } else if (autoSaveDir != null) {
            b.append("\nФлешка: ").append(autoSaveDir.getAbsolutePath()).append('\n');
        }
        b.append("\n--\nQ50 Info 1.5 · собрано под API 9\n");
        return b.toString();
    }

    private static void section(StringBuilder b, String title) {
        if (b.length() > 0) {
            b.append('\n');
        }
        b.append("== ").append(title).append(" ==\n");
    }

    private static void row(StringBuilder b, String key, String value) {
        b.append(key).append(": ").append(value == null ? "—" : value).append('\n');
    }

    // ------------------------------------------------------------- показатели

    /**
     * На API 9 нет ни Build.SUPPORTED_64_BIT_ABIS, ни os.arch, которому можно
     * верить, поэтому смотрим на сами ABI-строки.
     */
    private static boolean is64bit() {
        String abi = Build.CPU_ABI;
        return abi != null && (abi.contains("64") || abi.startsWith("x86_64"));
    }

    private static String densityBucket(int dpi) {
        if (dpi <= 120) return "ldpi";
        if (dpi <= 160) return "mdpi";
        if (dpi <= 240) return "hdpi";
        if (dpi <= 320) return "xhdpi";
        return "xxhdpi и выше";
    }

    private String unknownSourcesAllowed() {
        try {
            // На API 9 настройка живёт в Secure; в Global её перенесли только в API 17.
            int v = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.INSTALL_NON_MARKET_APPS, 0);
            return v == 1 ? "разрешены" : "ЗАПРЕЩЕНЫ — включить перед установкой";
        } catch (Exception e) {
            return "не удалось прочитать";
        }
    }

    private String bluetooth() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) {
                return "адаптера нет";
            }
            // getName() и isEnabled() требуют разрешения BLUETOOTH — оно в манифесте.
            return "есть, " + (a.isEnabled() ? "включён" : "выключен")
                    + ", имя: " + a.getName();
        } catch (Exception e) {
            return "не опросить (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String suPath() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/system/sbin/su", "/vendor/bin/su", "/su/bin/su"
        };
        for (int i = 0; i < paths.length; i++) {
            if (new File(paths[i]).exists()) {
                return "есть — " + paths[i];
            }
        }
        return "не найден";
    }

    private static String space(File dir) {
        try {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            // getBlockSizeLong() появился в API 18 — здесь только int-версии.
            long block = fs.getBlockSize();
            long total = block * (long) fs.getBlockCount();
            long free = block * (long) fs.getAvailableBlocks();
            return human(free) + " свободно из " + human(total);
        } catch (Exception e) {
            return "недоступно";
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f КБ", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f МБ", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f ГБ", bytes / 1073741824.0);
    }

    // -------------------------------------------------------------- действия

    private void copyToClipboard() {
        try {
            // android.content.ClipboardManager — с API 11. На 2.3 только текстовый.
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setText(report);
            toast("Скопировано в буфер обмена");
        } catch (Exception e) {
            toast("Буфер обмена недоступен: " + e);
        }
    }

    /** Кнопка «Сохранить»: перезаписать отчёт на флешку прямо сейчас. */
    private void saveToFile() {
        if (autoSaveDir == null) {
            autoSaveDir = UsbStorage.pickWritableDir();
        }
        if (autoSaveDir == null) {
            toast("Флешка для записи не найдена. Вставьте USB (FAT32).");
            return;
        }
        if (writeReport(autoSaveDir)) {
            toast("Сохранено: " + savedPath);
        } else {
            toast("Не удалось записать на " + autoSaveDir.getAbsolutePath());
        }
    }

    /** Автозапись: тихо, без всплывающих сообщений (кроме первого раза). */
    private void autoSave(boolean announce) {
        if (autoSaveDir == null) {
            if (announce) {
                toast("Флешка не найдена — файл не записан. Вставьте USB (FAT32).");
            }
            return;
        }
        boolean ok = writeReport(autoSaveDir);
        lastAutoSave = System.currentTimeMillis();
        if (announce) {
            toast(ok ? ("Файл на флешке: " + savedPath)
                     : ("Не удалось записать на " + autoSaveDir.getAbsolutePath()));
        }
    }

    /** Записать текущий отчёт в q50-info.txt в указанном каталоге. */
    private boolean writeReport(File dir) {
        File out = new File(dir, FILE_NAME);
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
            w.write(report);
            w.flush();
            savedPath = out.getAbsolutePath();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (can != null && can.manager() != null && can.sensors() != null) {
            for (int i = 0; i < can.sensors().size(); i++) {
                try {
                    can.manager().registerListener(this, can.sensors().get(i), 200000);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (can != null && can.manager() != null) {
            try {
                can.manager().unregisterListener(this);
            } catch (Throwable ignored) {
            }
        }
        // зафиксировать последние данные при выходе
        report = buildReport();
        autoSave(false);
    }

    public void onSensorChanged(SensorEvent e) {
        if (can == null) {
            return;
        }
        can.update(e.sensor, e.values);
        long now = System.currentTimeMillis();
        if (now - lastRefresh < 500) {
            return;
        }
        lastRefresh = now;
        report = buildReport();
        if (textView != null) {
            textView.setText(report);
        }
        // держим файл на флешке свежим, но не насилуем флеш-память
        if (now - lastAutoSave >= 3000) {
            autoSave(false);
        }
    }

    public void onAccuracyChanged(Sensor s, int a) {
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
