package com.q50.info;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Выполнение команд с правами root на этом ГУ.
 *
 * su на устройстве не понимает `su -c "cmd"` — он пытается запустить всю строку
 * как один файл ("exec failed"). Рабочий способ — запустить su как корневой
 * шелл и подать команды ему на stdin. Так работает практически любой su.
 */
final class Root {

    private Root() {
    }

    /** true, если su выдаёт root. */
    static boolean available() {
        String id = run("id");
        return id != null && id.indexOf("uid=0") >= 0;
    }

    /** Выполнить команду в корневом шелле, вернуть stdout+stderr (или null). */
    static String run(String cmd) {
        Process p = null;
        BufferedReader r = null;
        try {
            p = Runtime.getRuntime().exec("su");
            OutputStream os = p.getOutputStream();
            os.write((cmd + "\n").getBytes("UTF-8"));
            os.write("exit\n".getBytes("UTF-8"));
            os.flush();
            os.close();
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < 200) {
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

    /** Записать данные в файл через root: подаём `cat > путь` на stdin шелла. */
    static boolean writeFile(String path, byte[] data) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            OutputStream os = p.getOutputStream();
            os.write(("cat > " + path + "\n").getBytes("UTF-8"));
            os.write(data);
            os.flush();
            os.close();               // EOF -> cat закрывает файл, шелл выходит
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }
}
