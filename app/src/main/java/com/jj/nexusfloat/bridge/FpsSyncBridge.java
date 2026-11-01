package com.jj.nexusfloat.bridge;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.RootShell;

/**
 * 把监视条的帧率同步给模块进程。
 *
 * 为什么要这条通道：帧记录自己也有一套帧率取数，但它跑在模块 App 进程里，
 * 和监视条（SystemUI 进程）看到的 layer 与节点权限不一样。两边算出来的数经常
 * 对不上——监视条上明明显示 60，切到记录页却是 --。既然监视条那个数已经是准的，
 * 帧记录直接拿它的结果就行。
 *
 * ## 为什么不用 Settings.Global
 *
 * 之前走 `settings put global`。那条路每次都要 fork 一个 app_process 去跑
 * settings 这个 Java 程序，单次几百毫秒的 CPU 时间。帧率每秒都在跳，等于每秒
 * fork 一次——监视条开着比关着多耗 0.2W、还偶发功耗尖峰，主要就是它。
 *
 * 现在改成往文件里写一行。关键差别是**用什么命令写**：
 * - `settings put global k v`  → fork 一个 Java 进程
 * - `echo v > /path/file`      → shell 内建，零 fork
 *
 * 两者都通过常驻 su 管道执行，但后者不创建任何进程，只有一次管道往返。
 *
 * 另外写入是**异步**的：原来在 updateData（主线程）里同步等 su 往返，
 * 每次都要卡住 UI 线程几百毫秒。现在只把值记到内存，由后台线程去写。
 *
 * ## 节流
 *
 * 即使零 fork，也没必要每拍都写：
 * - 帧率变化小于 PUBLISH_STEP 且来源标记没变，不写
 * - 两次写入间隔不小于 PUBLISH_MIN_INTERVAL_MS
 * - 画面从有到无、从无到有，立刻写（这是状态变化，不是抖动）
 *
 * 帧记录那边一秒采一个点，同步值慢一两秒完全够用；真读不到会退回自己算。
 */
public final class FpsSyncBridge {

    private FpsSyncBridge() {}

    /** 帧率变化小于这个值不重新发布 */
    private static final float PUBLISH_STEP = 1.0f;
    /** 两次发布之间的最小间隔 */
    private static final long PUBLISH_MIN_INTERVAL_MS = 3000L;

    /** 待写入的值，由 publish 更新、后台线程消费 */
    private static volatile String pendingValue;
    private static volatile boolean writeScheduled;

    private static float lastPublished = -1f;
    private static String lastTag = "";
    private static long lastPublishMs;
    /** 上一次发布时是不是「有画面」，用来识别状态翻转 */
    private static boolean lastHadFrames;

    /** 写文件的后台线程，懒加载 */
    private static HandlerThread writeThread;
    private static Handler writeHandler;

    /**
     * SystemUI 侧调用：把当前帧率同步出去。
     *
     * 只做判断和记账，实际写盘在后台线程，调用方（主线程）不会被 su 往返卡住。
     */
    public static void publish(Context context, float fps, String tag) {
        if (context == null) {
            return;
        }
        // 记下 context：无 root 兜底写 Settings.Global 时要用
        appContext = context.getApplicationContext();
        String safeTag = tag != null ? tag : "";
        boolean hasFrames = fps > 0f;
        long now = System.currentTimeMillis();

        // 状态翻转（有画面 <-> 没画面）立刻写：代表用户切了应用或画面停了，
        // 帧记录那边应该马上知道
        boolean stateFlip = hasFrames != lastHadFrames;

        if (!stateFlip) {
            if (Math.abs(fps - lastPublished) < PUBLISH_STEP && safeTag.equals(lastTag)) {
                return;
            }
            if (now - lastPublishMs < PUBLISH_MIN_INTERVAL_MS) {
                return;
            }
        }

        lastPublished = fps;
        lastTag = safeTag;
        lastPublishMs = now;
        lastHadFrames = hasFrames;

        pendingValue = String.format(java.util.Locale.US, "%.1f", fps) + "|" + safeTag;
        scheduleWrite();
    }

    /** 把待写值丢给后台线程，同一时刻只排一个任务 */
    private static void scheduleWrite() {
        synchronized (FpsSyncBridge.class) {
            if (writeScheduled) {
                return;
            }
            writeScheduled = true;
            Handler h = ensureHandler();
            if (h == null) {
                writeScheduled = false;
                return;
            }
            h.post(() -> {
                try {
                    String v = pendingValue;
                    if (v != null) {
                        writeToFile(v);
                    }
                } finally {
                    synchronized (FpsSyncBridge.class) {
                        writeScheduled = false;
                    }
                }
            });
        }
    }

    private static Handler ensureHandler() {
        if (writeHandler != null) {
            return writeHandler;
        }
        try {
            writeThread = new HandlerThread("NexusFpsSync");
            writeThread.start();
            writeHandler = new Handler(writeThread.getLooper());
            return writeHandler;
        } catch (Throwable t) {
            LogUtils.w("FpsSyncBridge thread start failed", t);
            return null;
        }
    }

    /**
     * 真正写盘。走常驻 su 管道的 echo 重定向，零 fork。
     *
     * 先写临时文件再 mv：避免模块进程读到写了一半的内容。
     * mv 在 shell 里也是内建，同样零 fork。
     */
    private static void writeToFile(String value) {
        try {
            RootShell shell = RootShell.get();
            if (shell.isDead()) {
                // 没 root 就退回 Settings.Global（有 fork 开销，但至少能用）
                writeViaSettings(value);
                return;
            }
            String path = Constants.Component.FPS_SYNC_FILE;
            String cmd = "echo " + RootShell.quote(value) + " > " + path + ".tmp"
                    + " && mv " + path + ".tmp " + path;
            shell.execLines(cmd, 1, Constants.Config.EXEC_TIMEOUT_MS);
        } catch (Throwable t) {
            LogUtils.w("FpsSyncBridge write failed", t);
        }
    }

    /**
     * 没有 root 时的兜底：写 Settings.Global。
     *
     * 这条路要 fork，只在 su 不可用时用。无 root 的机器本来就拿不到 GPU 数据，
     * 帧率同步慢一点无所谓。
     */
    private static void writeViaSettings(String value) {
        try {
            android.content.Context ctx = appContext;
            if (ctx == null) {
                return;
            }
            SettingsChannel.writeRaw(ctx, Constants.Component.SETTINGS_KEY_FPS_SYNC, value);
        } catch (Throwable t) {
            LogUtils.w("FpsSyncBridge settings fallback failed", t);
        }
    }

    /** publish 时记下的 context，供无 root 兜底用 */
    private static volatile android.content.Context appContext;

    /**
     * 模块侧调用：读监视条同步过来的帧率。
     *
     * 先读文件（零 fork，走常驻 su 的 read 内建），读不到再退回 Settings.Global。
     * 返回 NaN 表示两边都没有可用值，调用方应该退回自己算。
     */
    public static float read(Context context) {
        String raw = readRaw(context);
        if (raw == null) {
            return Float.NaN;
        }
        int sep = raw.indexOf('|');
        String num = sep >= 0 ? raw.substring(0, sep) : raw;
        try {
            return Float.parseFloat(num);
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** 读监视条同步过来的诊断标记；没有返回空串 */
    public static String readTag(Context context) {
        String raw = readRaw(context);
        if (raw == null) {
            return "";
        }
        int sep = raw.indexOf('|');
        return sep >= 0 && sep + 1 < raw.length() ? raw.substring(sep + 1) : "";
    }

    private static String readRaw(Context context) {
        // 1) 文件通道（零 fork）
        try {
            RootShell shell = RootShell.get();
            if (!shell.isDead()) {
                String v = shell.readFirstLine(Constants.Component.FPS_SYNC_FILE);
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
            // 落到下面的 Settings 通道
        }
        // 2) Settings.Global 兜底（兼容无 root 与旧版本写入的值）
        if (context == null) {
            return null;
        }
        try {
            String v = Settings.Global.getString(context.getContentResolver(),
                    Constants.Component.SETTINGS_KEY_FPS_SYNC);
            return (v == null || v.isEmpty()) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }
}
