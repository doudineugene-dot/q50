package com.q50.info;

import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Запись файлов на съёмный носитель головного устройства, с диагностикой.
 *
 * ГУ монтирует USB не туда, куда смотрит getExternalStorageDirectory(), а
 * прямая запись может упираться в права. Класс:
 *   - перечисляет все точки монтирования (напрямую и через su),
 *   - выбирает пригодные для записи съёмные каталоги,
 *   - пишет файл: сначала обычным способом, при неудаче — через su (root),
 *   - отдаёт диагностический текст, чтобы причину было видно с фотографии.
 *
 * Только API 9.
 */
final class Storage {

    private static final String[] FS = {
            "vfat", "exfat", "fuseblk", "texfat", "ntfs", "msdos", "sdcardfs"
    };
    private static final String[] GUESSES = {
            "/mnt/usb", "/mnt/usbdisk", "/mnt/usb_storage", "/mnt/usbotg",
            "/mnt/usbhost", "/mnt/usbhost1", "/mnt/sda1", "/mnt/sdb1",
            "/mnt/media", "/mnt/media_rw/usb", "/storage/usb0", "/storage/usb1",
            "/storage/usbdisk", "/udisk", "/mnt/udisk", "/mnt/ext_sd",
            "/mnt/external_sd", "/mnt/sdcard/usbStorage", "/mnt/sdcard"
    };

    /** Последний способ успешной записи — для показа в отчёте. */
    static String lastMethod = "";

    private Storage() {
    }

    // ---------------------------------------------------- точки монтирования

    /** mount point -> файловая система, из /proc/mounts (и через su, если дал). */
    static Map<String, String> mounts() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        collectMounts(readFile("/proc/mounts"), out);
        // через su можно увидеть смонтированное, скрытое от обычного процесса
        collectMounts(runSu("cat /proc/mounts"), out);
        return out;
    }

    private static void collectMounts(String text, Map<String, String> out) {
        if (text == null) {
            return;
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String[] p = lines[i].split("\\s+");
            if (p.length >= 3) {
                out.put(p[1], p[2]);
            }
        }
    }

    // ------------------------------------------------------- выбор каталога

    /** Съёмные каталоги-кандидаты в порядке предпочтения. */
    static List<File> candidates() {
        List<File> list = new ArrayList<File>();
        Map<String, String> m = mounts();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (isFlashFs(e.getValue()) && removable(e.getKey())) {
                list.add(new File(e.getKey()));
            }
        }
        for (int i = 0; i < GUESSES.length; i++) {
            File g = new File(GUESSES[i]);
            if (!list.contains(g)) {
                list.add(g);
            }
        }
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && !list.contains(ext)) {
            list.add(ext);
        }
        return list;
    }

    /** Первый каталог, куда реально удалось записать (прямо или через su). */
    static File pickWritableDir() {
        List<File> c = candidates();
        for (int i = 0; i < c.size(); i++) {
            if (canWrite(c.get(i))) {
                return c.get(i);
            }
        }
        return null;
    }

    // -------------------------------------------------------------- запись

    /** Записать данные в dir/name. Возвращает true при успехе; метод — в lastMethod. */
    static boolean write(File dir, String name, byte[] data) {
        File out = new File(dir, name);
        // 1. напрямую
        OutputStream o = null;
        try {
            o = new FileOutputStream(out);
            o.write(data);
            o.flush();
            lastMethod = "напрямую";
            return true;
        } catch (Exception e) {
            // 2. через root
        } finally {
            close(o);
        }
        if (writeViaSu(out.getAbsolutePath(), data)) {
            lastMethod = "через su (root)";
            return true;
        }
        lastMethod = "не удалось";
        return false;
    }

    private static boolean canWrite(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File probe = new File(dir, ".q50_wtest");
        if (dir.canWrite()) {
            try {
                FileOutputStream o = new FileOutputStream(probe);
                o.write('q');
                o.close();
                probe.delete();
                return true;
            } catch (Exception ignored) {
            }
        }
        // прямой записи нет — пробуем root
        if (writeViaSu(probe.getAbsolutePath(), new byte[]{'q'})) {
            runSu("rm -f " + probe.getAbsolutePath());
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ su

    /** Записать файл через su: su -c "cat > путь". */
    private static boolean writeViaSu(String path, byte[] data) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "cat > " + path).start();
            OutputStream os = p.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    /** Выполнить команду через su, вернуть stdout (или null). */
    private static String runSu(String cmd) {
        Process p = null;
        BufferedReader r = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).start();
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            close(r);
            if (p != null) {
                p.destroy();
            }
        }
    }

    // ------------------------------------------------------- диагностика

    static void appendDiagnostics(StringBuilder b) {
        b.append('\n').append("== НАКОПИТЕЛИ ==\n");
        boolean root = runSu("id") != null;
        b.append("Root для записи: ").append(root ? "доступен" : "нет").append('\n');

        List<File> c = candidates();
        Map<String, String> m = mounts();
        int shown = 0;
        for (int i = 0; i < c.size() && shown < 14; i++) {
            File d = c.get(i);
            if (!d.exists()) {
                continue;
            }
            String fs = m.get(d.getAbsolutePath());
            b.append("  ").append(d.getAbsolutePath())
                    .append(fs == null ? "" : " [" + fs + "]")
                    .append(canWrite(d) ? " — ЗАПИСЬ ЕСТЬ" : " — только чтение")
                    .append('\n');
            shown++;
        }
        if (shown == 0) {
            b.append("  съёмные каталоги не обнаружены\n");
        }
    }

    // ----------------------------------------------------------- утилиты

    private static boolean isFlashFs(String fs) {
        for (int i = 0; i < FS.length; i++) {
            if (FS[i].equalsIgnoreCase(fs)) {
                return true;
            }
        }
        return false;
    }

    private static boolean removable(String path) {
        String p = path.toLowerCase();
        if (p.equals("/") || p.startsWith("/system") || p.startsWith("/data")
                || p.startsWith("/cache") || p.startsWith("/proc")
                || p.startsWith("/dev") || p.startsWith("/vendor")
                || p.startsWith("/acct") || p.startsWith("/sys")) {
            return false;
        }
        return p.indexOf("usb") >= 0 || p.indexOf("sd") >= 0
                || p.indexOf("udisk") >= 0 || p.indexOf("media") >= 0
                || p.indexOf("storage") >= 0 || p.startsWith("/mnt");
    }

    private static String readFile(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new java.io.FileReader(path));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
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
