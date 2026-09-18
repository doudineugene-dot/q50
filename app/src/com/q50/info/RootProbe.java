package com.q50.info;

/**
 * Глубокое извлечение с root: полный список сигналов VS_ID из прошивки и канал,
 * через который служба ГУ читает CAN.
 *
 * Команды идут через Root.run() (su читает их со stdin).
 */
final class RootProbe {

    private static final int MAX = 1600;
    private static final String SVC =
            "ygomi|connexis|vehicle|\\bivi\\b|vsig|candec|canrx|vs_svc|adcm";

    private RootProbe() {
    }

    static void appendTo(StringBuilder b) {
        b.append('\n').append("== ИЗВЛЕЧЕНИЕ (root) ==\n");
        if (!Root.available()) {
            b.append("Root приложению не выдан — раздел пропущен.\n");
            return;
        }
        b.append("Root: есть\n");

        // ГЛАВНОЕ: полный список имён сигналов, зашитых в прошивку.
        // -a: читать бинарные jar/so как текст (строки VS_ID лежат в dex).
        dump(b, "ПОЛНЫЙ СПИСОК VS_ID из прошивки",
                Root.run("grep -rhoaE 'VS_ID_[A-Z0-9_]+' "
                        + "/system/framework /system/app /system/lib /vendor 2>/dev/null "
                        + "| sort -u"));

        // Где именно они лежат (какой jar/apk/so декодирует шину)
        dump(b, "Файлы, содержащие VS_ID",
                Root.run("grep -rlaE 'VS_ID_' /system/framework /system/app /system/lib "
                        + "/vendor 2>/dev/null | head -8"));

        // Служба, читающая шину, и её PID
        dump(b, "Процесс службы",
                Root.run("ps | grep -iE '" + SVC + "'"));

        // Канал: открытые дескрипторы службы -> устройство/сокет шины
        dump(b, "Канал службы (fd)",
                Root.run("for pp in $(ps | grep -iE '" + SVC
                        + "' | awk '{print $2}'); do echo PID $pp:; "
                        + "ls -l /proc/$pp/fd 2>/dev/null "
                        + "| grep -iE 'can|/dev/|socket|tty|spi'; done"));

        // Что за узлы вообще есть в /dev
        dump(b, "Узлы /dev",
                Root.run("ls /dev 2>/dev/null | tr '\\n' ' '"));

        // Сервисы и сокеты
        dump(b, "Сервисы",
                Root.run("service list 2>/dev/null | grep -iE 'vehicle|can|ivi|ygomi'"));

        // Точки монтирования — найти флешку
        dump(b, "Монтирование",
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
            t = t.substring(0, MAX) + "\n  …(обрезано)";
        }
        String[] lines = t.split("\n");
        for (int i = 0; i < lines.length; i++) {
            b.append("  ").append(lines[i]).append('\n');
        }
    }
}
