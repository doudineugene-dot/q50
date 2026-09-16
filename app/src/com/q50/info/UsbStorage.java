package com.q50.info;

import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Поиск подключённой USB-флешки для записи отчёта.
 *
 * На головном устройстве Infiniti накопитель монтируется НЕ туда, куда
 * показывает Environment.getExternalStorageDirectory() (там обычно внутренняя
 * память). Этот класс находит реальную точку монтирования USB двумя способами:
 * разбором /proc/mounts и перебором типовых путей. Возвращает первый каталог,
 * в который реально удаётся записать.
 *
 * Только API 9: java.io.
 */
final class UsbStorage {

    /** Файловые системы, характерные для флешек. */
    private static final String[] FS = {
            "vfat", "exfat", "fuseblk", "texfat", "ntfs", "msdos"
    };

    /** Типовые точки монтирования USB на автомобильных ГУ. */
    private static final String[] GUESSES = {
            "/mnt/usb", "/mnt/usbdisk", "/mnt/usb_storage", "/mnt/usbotg",
            "/mnt/usbhost", "/mnt/usbhost1", "/mnt/sda1", "/mnt/sdb1",
            "/mnt/media", "/mnt/media_rw/usb", "/storage/usb0", "/storage/usb1",
            "/storage/usbdisk", "/udisk", "/mnt/udisk", "/mnt/ext_sd",
            "/mnt/external_sd", "/mnt/USB", "/mnt/USBSTORAGE"
    };

    private UsbStorage() {
    }

    /** Каталог для записи: сперва настоящая флешка, иначе внешняя память. */
    static File pickWritableDir() {
        List<File> candidates = new ArrayList<File>();

        // 1. из /proc/mounts — самый надёжный источник
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 3) {
                    continue;
                }
                String mountPoint = parts[1];
                String fs = parts[2];
                if (isFlashFs(fs) && looksRemovable(mountPoint)) {
                    candidates.add(new File(mountPoint));
                }
            }
        } catch (Exception ignored) {
        } finally {
            close(r);
        }

        // 2. типовые пути и их первый подкаталог (иногда монтируют вложенно)
        for (int i = 0; i < GUESSES.length; i++) {
            File g = new File(GUESSES[i]);
            candidates.add(g);
            File[] subs = g.listFiles();
            if (subs != null) {
                for (int k = 0; k < subs.length && k < 8; k++) {
                    if (subs[k].isDirectory()) {
                        candidates.add(subs[k]);
                    }
                }
            }
        }

        // 3. штатная внешняя память как запасной вариант
        candidates.add(Environment.getExternalStorageDirectory());

        for (int i = 0; i < candidates.size(); i++) {
            File d = candidates.get(i);
            if (isWritableDir(d)) {
                return d;
            }
        }
        return null;
    }

    private static boolean isFlashFs(String fs) {
        for (int i = 0; i < FS.length; i++) {
            if (FS[i].equalsIgnoreCase(fs)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksRemovable(String path) {
        String p = path.toLowerCase();
        // отсекаем системные разделы, оставляем похожее на съёмный носитель
        if (p.equals("/") || p.startsWith("/system") || p.startsWith("/data")
                || p.startsWith("/cache") || p.startsWith("/proc")
                || p.startsWith("/dev") || p.startsWith("/vendor")) {
            return false;
        }
        return p.indexOf("usb") >= 0 || p.indexOf("sd") >= 0
                || p.indexOf("udisk") >= 0 || p.indexOf("media") >= 0
                || p.indexOf("storage") >= 0 || p.startsWith("/mnt");
    }

    /** Проверка записи делом: создать и удалить пробный файл. */
    private static boolean isWritableDir(File dir) {
        if (dir == null || !dir.isDirectory() || !dir.canWrite()) {
            return false;
        }
        File probe = new File(dir, ".q50_wtest");
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(probe);
            o.write('q');
            o.close();
            probe.delete();
            return true;
        } catch (Exception e) {
            return false;
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
