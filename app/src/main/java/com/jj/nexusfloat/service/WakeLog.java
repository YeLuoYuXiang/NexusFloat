package com.jj.nexusfloat.service;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.RootShell;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 后台唤醒的诊断记录（v1.8.11）。
 *
 * 为什么需要它：ColorOS 全部清除后台之后 GPU 数据会停更，但到底卡在哪一步
 * 从外面看不出来——su 没拿到？am 命令报错？进程起来了但没开始采集？
 * 这条链路的日志关着的时候，LSPosed 日志里连模块名都搜不到，只能靠猜，
 * 已经连着猜错两版了。
 *
 * 记录产生在 SystemUI 进程（唤醒是那边发起的），但要看的人是 App 界面的用户，
 * 两者不是同一个进程，静态变量过不去。所以每写一条就顺手用 root 追加到模块的
 * files 目录里，App 读自己目录下这个文件就能看到完整过程。
 *
 * 文件不大（只留最近几十条），也不用清理：本来就是排查用的。
 */
public final class WakeLog {

    /** 最多保留多少条，够看清最近几次唤醒就行 */
    private static final int MAX_LINES = 40;

    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private WakeLog() {}

    /**
     * 记一条，同时追加到落盘文件。
     *
     * 落盘走 root：SystemUI 进程是 system uid，写不了模块自己 files 目录
     * （那是应用私有目录，只有该应用和 root 能写）。
     */
    public static synchronized void add(String msg) {
        String line = TIME.format(new Date()) + "  " + msg;
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) {
            LINES.removeFirst();
        }
        LogUtils.i("WakeDiag: " + msg);
        appendToFile(line);
    }

    /**
     * 追加一行到模块 files 目录下的诊断文件。
     *
     * 用 shell 的 >> 重定向追加，不读改写整个文件，省一次读。
     * 文件行数由 Android 侧的 MAX_LINES 控制不住，所以到一定长度就让 shell 截断一次，
     * 免得用户长时间不用、文件无限长下去。
     */
    private static void appendToFile(String line) {
        try {
            String path = Constants.Component.WAKE_LOG_PATH;
            // 单引号包住内容，避免行里的空格和特殊字符被 shell 拆开
            String safe = line.replace("'", "");
            String cmd = "echo '" + safe + "' >> " + path;
            RootShell.get().execLines(cmd, 4, Constants.Config.EXEC_WAKE_TIMEOUT_MS);
        } catch (Throwable t) {
            // 落盘失败不影响内存记录，也不再往上抛：诊断本身不该制造问题
            LogUtils.w("WakeLog append failed", t);
        }
    }

    /** 把最近记录拼成多行文本（同一个进程内用） */
    public static synchronized String dump() {
        if (LINES.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
