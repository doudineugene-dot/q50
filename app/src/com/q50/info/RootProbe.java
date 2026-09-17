package com.q50.info;

/**
 * Разведка с root: как ГУ читает CAN и где реально смонтирована флешка.
 *
 * Команды выполняются через Root.run() (su читает их со stdin) — прежний
 * `su -c "cmd"` на этом устройстве падал с "exec failed".
 */
final class RootProbe {

    private static final int MAX = 1100;
    private static final String KEYS =
            "can|vehicle|ivi|ygomi|connexis|vsig|vs_|obd|diag|kwp|mcu|adcm|adp";

    private RootProbe() {
    }

    static void appendTo(StringBuilder b) {
        b.append('\n').append("== РАЗВЕДКА CAN (root) ==\n");

        if (!Root.available()) {
            b.append("Root приложению не выдан — раздел пропущен.\n");
            return;
        }
        b.append("Root: есть\n");

        // Служба, читающая шину
        dump(b, "Процессы", Root.run("ps | grep -iE '" + KEYS + "'"));
        dump(b, "Сервисы", Root.run("service list 2>/dev/null | grep -iE '" + KEYS + "'"));

        // Каналы к шине
        dump(b, "/dev (can/spi/адаптеры)",
                Root.run("ls -l /dev 2>/dev/null | grep -iE 'can|spi|vehicle|ttyAdp|ttyPCH|mcu|adcm'"));
        dump(b, "Все узлы /dev (кратко)",
                Root.run("ls /dev 2>/dev/null | tr '\\n' ' '"));
        dump(b, "Unix-сокеты службы",
                Root.run("cat /proc/net/unix 2>/dev/null | grep -iE '" + KEYS + "'"));
        dump(b, "getprop (can/vehicle)",
                Root.run("getprop 2>/dev/null | grep -iE '" + KEYS + "'"));

        // Где вендорская логика и полный список сигналов
        dump(b, "Файлы с VS_ID",
                Root.run("grep -rl VS_ID /system 2>/dev/null | head -8"));

        // Где смонтирована флешка — полный список
        dump(b, "Точки монтирования",
                Root.run("cat /proc/mounts 2>/dev/null"));
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
}
