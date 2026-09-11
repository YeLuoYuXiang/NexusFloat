package com.jj.nexusfloat.utils;

import com.jj.nexusfloat.constant.Constants;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 在模块 App 自己进程里跑 su -c，前提是 Magisk 已经给本应用授过 root。
 */
public final class ExecUtils {

    private static final String[][] SU_PATHS = {
            {"su", "-c"},
            {"/system/bin/su", "-c"},
            {"/sbin/su", "-c"},
            {"/system/xbin/su", "-c"},
    };

    private ExecUtils() {}

    /**
     * 跑一条 shell 命令，把标准输出返回；跑不通返回 null。
     *
     * 四个 su 路径挨个试，哪个先出非空输出就用哪个。
     */
    public static String exec(String shellCommand) {
        for (String[] prefix : SU_PATHS) {
            String[] cmd = new String[prefix.length + 1];
            System.arraycopy(prefix, 0, cmd, 0, prefix.length);
            cmd[prefix.length] = shellCommand;
            String out = runProcess(cmd, Constants.Config.EXEC_TIMEOUT_MS);
            if (out != null && !out.trim().isEmpty()) {
                return out.trim();
            }
        }
        return null;
    }

    private static String runProcess(String[] command, long timeoutMs) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();
            final Process p = process;
            final StringBuilder output = new StringBuilder();
            Thread reader = startOutputReader(p, output);

            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            reader.join(1000);
            synchronized (output) {
                return output.length() > 0 ? output.toString() : null;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static Thread startOutputReader(Process process, StringBuilder output) {
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() > 0) {
                            output.append('\n');
                        }
                        output.append(line);
                    }
                }
            } catch (Exception ignored) {
            }
        }, Constants.ThreadName.EXEC_READER);
        reader.setDaemon(true);
        reader.start();
        return reader;
    }
}
