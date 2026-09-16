package com.q50.info;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Поиск на устройстве файлов, похожих на сертификаты и ключи.
 *
 * Публичный сертификат OBU, нужный для сборки .epk, лежит где-то в файловой
 * системе ГУ в открытом виде. Этот класс обходит типичные каталоги и ищет
 * файлы с характерными расширениями и с DER-сигнатурой X.509 в начале.
 * Найденное имеет смысл вытащить с устройства и прогнать через tools/certscan.py.
 *
 * Только API 9: java.io и никаких сторонних библиотек.
 */
final class CertProbe {

    private static final String[] DIRS = {
            "/etc", "/etc/security", "/system/etc", "/system/etc/security",
            "/system/etc/security/cacerts", "/vendor/etc", "/data/misc/keychain",
            "/system/app", "/system/vendor", "/mnt/sdcard", "/sdcard",
            "/cust", "/persist", "/factory"
    };

    private static final String[] EXTS = {
            ".cer", ".crt", ".pem", ".der", ".key", ".pub", ".p12", ".pfx", ".cert"
    };

    private CertProbe() {
    }

    static void appendTo(StringBuilder b) {
        b.append('\n').append("== ПОИСК СЕРТИФИКАТОВ (для сборки .epk) ==\n");

        List<String> hits = new ArrayList<String>();
        for (int i = 0; i < DIRS.length; i++) {
            scan(new File(DIRS[i]), hits, 0);
        }

        if (hits.isEmpty()) {
            b.append("Файлов-сертификатов не найдено в стандартных каталогах.\n");
            b.append("Возможно, нужен root, либо сертификат лежит внутри штатного\n");
            b.append("установщика — снять прошивку и прогнать через certscan.py.\n");
        } else {
            b.append("Найдено кандидатов: ").append(hits.size()).append('\n');
            for (int i = 0; i < hits.size(); i++) {
                b.append("  ").append(hits.get(i)).append('\n');
            }
            b.append("Вытащить эти файлы с устройства и проверить certscan.py.\n");
        }
    }

    /** Обход каталога вглубь до 3 уровней, чтобы не уйти в бесконечность. */
    private static void scan(File dir, List<String> hits, int depth) {
        if (depth > 3 || !dir.isDirectory() || !dir.canRead()) {
            return;
        }
        File[] items = dir.listFiles();
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length; i++) {
            File f = items[i];
            if (f.isDirectory()) {
                scan(f, hits, depth + 1);
            } else if (looksLikeCert(f)) {
                hits.add(f.getAbsolutePath() + " (" + f.length() + " Б)");
                if (hits.size() >= 100) {
                    return;
                }
            }
        }
    }

    private static boolean looksLikeCert(File f) {
        String name = f.getName().toLowerCase();
        for (int i = 0; i < EXTS.length; i++) {
            if (name.endsWith(EXTS[i])) {
                return true;
            }
        }
        // по расширению не подошло — заглянуть в первые байты
        return hasCertSignature(f);
    }

    /** PEM начинается с "-----BEGIN", DER X.509 — с 0x30 0x82. */
    private static boolean hasCertSignature(File f) {
        long len = f.length();
        if (len < 64 || len > 65536 || !f.canRead()) {
            return false;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] head = new byte[16];
            int n = in.read(head);
            if (n < 10) {
                return false;
            }
            if (head[0] == '-' && head[1] == '-' && head[2] == '-'
                    && head[3] == '-' && head[4] == '-' && head[5] == 'B') {
                return true;
            }
            return (head[0] & 0xff) == 0x30 && (head[1] & 0xff) == 0x82;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
