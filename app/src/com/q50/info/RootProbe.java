package com.q50.info;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Разведка с root: как ГУ читает CAN и можно ли достать больше 42 сигналов.
 *
 * 42 сенсора — это то, что вендорская служба выбрала выводить в Android.
 * На шине сигналов больше. Чтобы понять, есть ли к ним доступ, нужно найти:
 *   - службу/демон, который читает шину (ps, service list),
 *   - через какой канал он её читает (его открытые дескрипторы, /dev, сокеты),
 *   - вендорские сервисы автомобиля в service manager,
 *   - свойства системы с намёками на CAN.
 *
 * Всё через su. Вывод обрезается, чтобы уместиться на экран.
 */
final class RootProbe {

    private static final int MAX = 900;
    private static final String KEYS = "can|vehicle|ivi|ygomi|connexis|vsig|vs_|obd|diag|kwp|mcu|adcm|adp";

    private RootProbe() {
    }

    static void appendTo(StringBuilder b) {
        b.append('\n').append("== РАЗВЕДКА CAN (root) ==\n");

        String id = su("id");
        if (id == null || id.indexOf("uid=0") < 0) {
            b.append("Root недоступен приложению — раздел пропущен.\n");
            b.append("(su есть в системе, но не выдал прав этому приложению.)\n");
            return;
        }
        b.append("Root: есть (").append(id.trim()).append(")\n");

        // 1. процессы, похожие на службу шины
        dump(b, "Процессы (служба шины)",
                su("ps | grep -iE '" + KEYS + "'"));

        // 2. системные сервисы автомобиля
        dump(b, "Сервисы (service list)",
                su("service list 2>/dev/null | grep -iE '" + KEYS + "'"));

        // 3. устройства в /dev, интересные для CAN
        dump(b, "/dev (can/spi/адаптеры)",
                su("ls -l /dev 2>/dev/null | grep -iE 'can|spi|vehicle|ttyAdp|ttyPCH|mcu'"));

        // 4. локальные сокеты — служба может отдавать сигналы через unix-сокет
        dump(b, "Unix-сокеты",
                su("cat /proc/net/unix 2>/dev/null | grep -iE '" + KEYS + "'"));

        // 5. свойства системы
        dump(b, "getprop",
                su("getprop 2>/dev/null | grep -iE '" + KEYS + "'"));

        // 6. где лежит вендорская логика — jar/apk с VS_ID
        dump(b, "Файлы с VS_ID",
                su("grep -rl VS_ID /system/framework /system/app /system/lib "
                        + "/vendor 2>/dev/null | head -10"));

        b.append("\nЭто карта: по ней видно, откуда служба берёт шину и есть ли\n");
        b.append("канал к полному потоку сигналов.\n");
    }

    private static void dump(StringBuilder b, String title, String out) {
        b.append("-- ").append(title).append(" --\n");
        if (out == null || out.trim().length() == 0) {
            b.append("  (пусто)\n");
            return;
        }
        String t = out.trim();
        if (t.length() > MAX) {
            t = t.substring(0, MAX) + "…";
        }
        String[] lines = t.split("\n");
        for (int i = 0; i < lines.length; i++) {
            b.append("  ").append(lines[i]).append('\n');
        }
    }

    private static String su(String cmd) {
        Process p = null;
        BufferedReader r = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < 60) {
                sb.append(line).append('\n');
                n++;
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
            if (p != null) {
                p.destroy();
            }
        }
    }
}
