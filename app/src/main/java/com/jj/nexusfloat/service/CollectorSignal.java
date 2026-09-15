package com.jj.nexusfloat.service;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.RootShell;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SystemUI 到模块进程的单向指令通道：告诉对端启停 root GPU 采集。
 *
 * 为什么要它：GPU 频率/占用是模块 App 进程用常驻 su 读出来、再写进 RemotePreferences 的，
 * SystemUI 只是读的一方。监视条一隐藏，SystemUI 这边的采集线程会随 View detach 停掉，
 * 但模块进程那个 su 读取循环在另一个进程里，丝毫不受影响，会一直每秒 cat 一遍 sysfs。
 * 这就是「关掉某个方向的显示后，这个方向下照样耗电」的原因。
 *
 * RemotePreferences 的方向是模块进程写、SystemUI 读，正好反着来，用不了。
 * 这里复用现成的 EarlyInitProvider：往它的 authority 上挂一个路径段做 query，
 * 模块进程收到就启停采集。不用加组件，也不用广播（广播在后台限制下不总能唤醒目标进程）。
 */
public final class CollectorSignal {

    /**
     * 指令走单线程后台队列，不占 SystemUI 主线程。
     *
     * ContentResolver.query 是同步 binder 调用，模块进程不在的时候还会顺带把进程拉起来，
     * 一次能干到几百毫秒。放 SystemUI 主线程上做会掉帧甚至 ANR。
     * 单线程是为了保证指令按下发顺序执行，不会出现 pause 反超 resume。
     */
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, Constants.ThreadName.COLLECT_SIGNAL);
                t.setDaemon(true);
                return t;
            });

    private CollectorSignal() {}

    /**
     * 下发指令。失败就只记条日志：采集状态没同步上顶多多耗点电，
     * 不该因为这个把 SystemUI 侧的显示流程打断。
     *
     * collecting 传 true 恢复采集，false 暂停采集
     */
    public static void send(Context context, boolean collecting) {
        if (context == null) {
            return;
        }
        String path = collecting
                ? Constants.Component.PATH_COLLECT_RESUME
                : Constants.Component.PATH_COLLECT_PAUSE;
        try {
            EXECUTOR.execute(() -> query(context, path));
        } catch (Throwable t) {
            LogUtils.w("CollectorSignal dispatch failed: " + path, t);
        }
    }

    /**
     * 用 root 从后台把模块进程拉起来（v1.8.11 重写）。
     *
     * 为什么最后走到这条路上：
     * ColorOS 在最近任务里点「全部清除」会把应用置为 stopped 状态（等同
     * adb am force-stop）。这个状态下系统拒绝隐式唤醒，实测这些都不行：
     * ContentProvider query、普通广播、带 FLAG_INCLUDE_STOPPED_PACKAGES 的广播。
     * 划卡只是普通杀进程、不置 stopped，所以划卡没事——这个 bug 只在全部清除后出现。
     *
     * 现在改由 root 执行 am 命令启动一个空 Service。走 shell 权限那条路时，
     * AMS 对 stopped 应用的拦截不适用，进程能被直接拉起来。进程一起来
     * Application.onCreate 就跑，GPU 采集跟着恢复。
     *
     * 依次试两个入口：start-service 优先（后台启动，不会有任何界面）；
     * 失败再试 broadcast，覆盖个别 ROM 对 service 启动更严的情况。
     *
     * 命令在后台线程执行：su 往返加上 am 启动进程要几百毫秒，
     * 放 SystemUI 主线程上做会掉帧。
     */
    public static void wakeByRoot() {
        try {
            EXECUTOR.execute(CollectorSignal::runWakeCommands);
        } catch (Throwable t) {
            LogUtils.w("CollectorSignal root wake dispatch failed", t);
        }
    }

    private static void runWakeCommands() {
        String pkg = Constants.Package.MODULE;
        String service = pkg + "/" + Constants.Component.WAKE_SERVICE;
        // am start-service 是后台启动，不会拉起任何界面。
        // --user 0 明确指定用户，避免分身/多用户环境下投错地方
        String startService = "am start-service --user 0 -n " + service;
        // 备用：广播同样能带起进程，部分 ROM 只放行这一条
        String sendBroadcast = "am broadcast --user 0 --include-stopped-packages"
                + " -a " + Constants.Component.ACTION_WAKE
                + " -p " + pkg;

        try {
            List<String> out = RootShell.get().execLines(startService, 8,
                    Constants.Config.EXEC_WAKE_TIMEOUT_MS);
            WakeLog.add("start-service → " + describe(out));
            if (out != null && !looksLikeError(out)) {
                LogUtils.i("wake by root: start-service ok");
                return;
            }
            LogUtils.w("start-service failed, trying broadcast");
            List<String> out2 = RootShell.get().execLines(sendBroadcast, 8,
                    Constants.Config.EXEC_WAKE_TIMEOUT_MS);
            WakeLog.add("broadcast → " + describe(out2));
            if (out2 != null && !looksLikeError(out2)) {
                LogUtils.i("wake by root: broadcast ok");
                return;
            }
            WakeLog.add("两条 root 命令都没成功");
            LogUtils.w("wake by root: both commands failed");
        } catch (Throwable t) {
            WakeLog.add("root 命令异常: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            LogUtils.w("wake by root failed", t);
        }
    }

    /**
     * 把 am 的输出压成一行短描述，供 App 界面显示。
     * am 成功时一般没有输出（或只有一两条提示），失败时会打 Error 那几行。
     */
    private static String describe(List<String> lines) {
        if (lines == null) {
            return "没有输出（su 不可用或命令超时）";
        }
        if (lines.isEmpty()) {
            return "无输出（通常表示执行成功）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size() && i < 3; i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(lines.get(i).trim());
        }
        if (lines.size() > 3) {
            sb.append(" …");
        }
        return sb.toString();
    }

    /**
     * am 命令失败时会把错误打在 stdout（比如 Error: Not found; no service started），
     * 退出码却仍是 0，所以只能看输出内容判断。
     */
    private static boolean looksLikeError(List<String> lines) {
        for (String line : lines) {
            String s = line.trim();
            if (s.startsWith("Error") || s.contains("not found")
                    || s.contains("Permission Denial") || s.contains("Exception")) {
                return true;
            }
        }
        return false;
    }

    private static void query(Context context, String path) {
        try {
            Uri uri = Uri.parse("content://" + Constants.Component.EARLY_INIT_AUTHORITY
                    + "/" + path);
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                cursor.close();
            }
        } catch (Throwable t) {
            LogUtils.w("CollectorSignal send failed: " + path, t);
        }
    }

    /**
     * 用广播把模块进程唤醒（v1.8.11）。
     *
     * 为什么不能只用上面的 Provider query：ColorOS 在最近任务里「全部清除」之后
     * 会把应用置为 stopped 状态，这种状态下系统拒绝一切需要拉起进程的隐式手段，
     * ContentProvider query 也在其中，于是进程永远起不来，GPU 数据停更。
     * 划卡只是普通杀进程、不置 stopped，所以划卡时是好的——这就是这个 bug
     * 只在「全部清除」后出现的原因。
     *
     * 系统对 stopped 应用唯一放行的条件是 Intent 带 FLAG_INCLUDE_STOPPED_PACKAGES。
     * 这个 flag 由发送方加，而我们的发送方是被注入过的 SystemUI 进程（代码是我们写的），
     * 所以不需要 hook 系统框架，也不用把模块作用域扩到 system。
     *
     * 显式广播（setPackage 指定收件包）+ 带上那个 flag，两个都是必需的：
     * 前者保证只有我们自己的接收者收到，后者才能穿透 stopped 状态。
     */
    public static void wakeByBroadcast(Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(Constants.Component.ACTION_WAKE);
            intent.setPackage(Constants.Package.MODULE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
            LogUtils.i("CollectorSignal: wake broadcast sent");
        } catch (Throwable t) {
            LogUtils.w("CollectorSignal wake broadcast failed", t);
        }
    }
}
