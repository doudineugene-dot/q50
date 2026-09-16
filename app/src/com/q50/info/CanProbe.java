package com.q50.info;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Разведка: есть ли на головном устройстве путь к CAN-шине.
 *
 * Из Java напрямую читать шину нельзя — SocketCAN это сокеты семейства AF_CAN,
 * которых в java.net нет. Поэтому задача класса не читать шину, а выяснить,
 * через что до неё вообще можно добраться: есть ли CAN в ядре, поднят ли
 * интерфейс, лежат ли на устройстве can-utils, есть ли root.
 *
 * Всё только на API 9: обычные java.io и Runtime.exec.
 */
final class CanProbe {

    /** ARPHRD_CAN — значение /sys/class/net/<if>/type у CAN-интерфейса. */
    private static final int ARPHRD_CAN = 280;

    private static final String[] BIN_DIRS = {
            "/system/bin", "/system/xbin", "/vendor/bin",
            "/sbin", "/bin", "/usr/bin", "/data/local/bin"
    };

    private static final String[] CAN_TOOLS = {
            "candump", "cansend", "cangen", "canconfig", "ip", "ifconfig", "busybox"
    };

    /** Имена модулей ядра, по которым видно поддержку CAN. */
    private static final String[] CAN_MODULES = {
            "can", "can_raw", "can_bcm", "can_dev", "vcan", "slcan",
            "mcp251x", "flexcan", "c_can", "ti_hecc", "at91_can", "sja1000"
    };

    private CanProbe() {
    }

    static void appendTo(StringBuilder b) {
        kernel(b);
        interfaces(b);
        devices(b);
        tools(b);
    }

    // ------------------------------------------------------------ ядро

    private static void kernel(StringBuilder b) {
        head(b, "CAN: ЯДРО");

        File procCan = new File("/proc/net/can");
        boolean socketcan = procCan.isDirectory();
        row(b, "SocketCAN (/proc/net/can)", socketcan ? "ЕСТЬ" : "нет");
        if (socketcan) {
            String[] entries = procCan.list();
            row(b, "  содержимое", entries == null ? "не прочитать" : join(entries));
            String version = firstLine("/proc/net/can/version");
            if (version != null) {
                row(b, "  версия стека", version);
            }
        }

        List<String> found = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/modules"));
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                String name = sp > 0 ? line.substring(0, sp) : line;
                if (Arrays.asList(CAN_MODULES).contains(name)) {
                    found.add(name);
                }
            }
            row(b, "Модули CAN в /proc/modules",
                    found.isEmpty() ? "не найдены" : join(found.toArray(new String[0])));
        } catch (Exception e) {
            row(b, "Модули CAN в /proc/modules", "не прочитать (" + e.getClass().getSimpleName() + ")");
        } finally {
            close(r);
        }
    }

    // ------------------------------------------------------- интерфейсы

    private static void interfaces(StringBuilder b) {
        head(b, "CAN: ИНТЕРФЕЙСЫ");

        File net = new File("/sys/class/net");
        String[] ifaces = net.list();
        if (ifaces == null) {
            row(b, "/sys/class/net", "не прочитать");
            return;
        }
        row(b, "Все интерфейсы", join(ifaces));

        List<String> cans = new ArrayList<String>();
        for (int i = 0; i < ifaces.length; i++) {
            // ARPHRD_CAN надёжнее, чем имя: интерфейс не обязан называться can0.
            String type = firstLine("/sys/class/net/" + ifaces[i] + "/type");
            if (type != null && type.trim().equals(String.valueOf(ARPHRD_CAN))) {
                String state = firstLine("/sys/class/net/" + ifaces[i] + "/operstate");
                String bitrate = firstLine("/sys/class/net/" + ifaces[i] + "/can_bittiming/bitrate");
                cans.add(ifaces[i]
                        + " (состояние: " + (state == null ? "?" : state.trim())
                        + (bitrate != null ? ", " + bitrate.trim() + " бит/с" : "") + ")");
            }
        }
        row(b, "CAN-интерфейсы (type=280)",
                cans.isEmpty() ? "НЕ НАЙДЕНЫ" : join(cans.toArray(new String[0])));

        String ip = exec(new String[]{"ip", "-o", "link"});
        if (ip != null) {
            row(b, "ip -o link", ip.length() > 400 ? ip.substring(0, 400) + "…" : ip);
        }
    }

    // ---------------------------------------------------- устройства

    private static void devices(StringBuilder b) {
        head(b, "CAN: УСТРОЙСТВА В /dev");

        String[] dev = new File("/dev").list();
        if (dev == null) {
            row(b, "/dev", "не прочитать (обычное дело без root)");
            return;
        }
        List<String> can = new ArrayList<String>();
        List<String> tty = new ArrayList<String>();
        for (int i = 0; i < dev.length; i++) {
            String n = dev[i];
            if (n.startsWith("can") || n.contains("mcp") || n.startsWith("vcan")) {
                can.add(n);
            } else if (n.startsWith("tty") && !n.equals("tty")) {
                tty.add(n);
            }
        }
        row(b, "Похожие на CAN", can.isEmpty() ? "нет" : join(can.toArray(new String[0])));
        // последовательный порт — путь к slcan-адаптеру, если прямого CAN нет
        row(b, "Последовательные порты", tty.isEmpty() ? "нет" : join(tty.toArray(new String[0])));
    }

    // ------------------------------------------------------ инструменты

    private static void tools(StringBuilder b) {
        head(b, "CAN: ИНСТРУМЕНТЫ НА УСТРОЙСТВЕ");
        for (int i = 0; i < CAN_TOOLS.length; i++) {
            row(b, CAN_TOOLS[i], find(CAN_TOOLS[i]));
        }
    }

    private static String find(String name) {
        for (int i = 0; i < BIN_DIRS.length; i++) {
            File f = new File(BIN_DIRS[i], name);
            if (f.exists()) {
                return f.getAbsolutePath() + (f.canExecute() ? "" : " (не исполняемый)");
            }
        }
        return "не найден";
    }

    // ---------------------------------------------------------- утилиты

    private static void head(StringBuilder b, String title) {
        b.append('\n').append("== ").append(title).append(" ==\n");
    }

    private static void row(StringBuilder b, String key, String value) {
        b.append(key).append(": ").append(value == null ? "—" : value).append('\n');
    }

    private static String join(String[] items) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                s.append(", ");
            }
            s.append(items[i]);
        }
        return s.toString();
    }

    private static String firstLine(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            return r.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            close(r);
        }
    }

    /** Запуск без root. Читаем поток до конца — процесс к этому моменту завершится. */
    private static String exec(String[] cmd) {
        BufferedReader r = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append("; ");
            }
            p.waitFor();
            return out.length() == 0 ? null : out.toString();
        } catch (Exception e) {
            return null;
        } finally {
            close(r);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
