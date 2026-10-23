package com.jj.nexusfloat.stats;

import android.content.Context;

import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.RootShell;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读「此刻屏幕上是哪个应用」。
 *
 * 两条路，都不能用时返回 null：
 *
 * 1. UsageStatsManager 的最后一条 RESUMED 事件。准，但要有「使用情况访问」权限。
 * 2. root 里跑 dumpsys。不需要额外权限，只要 root——而本模块的 GPU 取数本来就
 *    依赖 root，所以实际用户基本都满足。
 *
 * 为什么不用 ActivityManager.getRunningTasks：那个接口要 REAL_GET_TASKS，只有
 * system uid 有。SystemUI 里的 hook 侧能用（见 collector.ForegroundAppTracker），
 * 但统计采样跑在模块自己的 App 进程，是普通应用，调了也只能看到自己。
 *
 * dumpsys 的解析故意写得宽松：这份输出格式在 Android 各版本之间一直在动，
 * 与其按某个版本的版式切列，不如「扫到第一行像组件名的就认」。任务栈是从上往下
 * 列的，所以第一条 ActivityRecord 就是栈顶那个——这也正好是「用户在看谁」。
 */
public final class ForegroundAppReader {

    /**
     * 组件名的样子：包名 + '/' + Activity。
     *
     * 包名至少两段，每段只含字母数字下划线。这条正则同时干掉了同一行里
     * 的 uid（u0a123）、hash（1a2b3c4d）、类名片段这些干扰项。
     */
    private static final Pattern COMPONENT = Pattern.compile(
            "([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)/[A-Za-z0-9_.$]+");

    /**
     * 一次取 dumpsys 输出保留多少行。
     *
     * 任务栈的顶部几个任务在最前面，300 行足够覆盖到「当前前台那个」。
     * 多留也没用：再往后是各 Task 的详细信息和 section 尾巴。
     */
    private static final int DUMPSYS_MAX_LINES = 300;

    /** 连续失败几次之后放弃 root 这条路，省得每轮都白跑一次 dumpsys */
    private static final int MAX_FAILURES = 5;

    private static int failures;
    private static boolean disabled;

    private ForegroundAppReader() {}

    /** 读当前前台包名；读不到返回 null */
    public static String read(Context context) {
        String fromUsage = UsageStatsReader.lastForegroundPackage(context, 5 * 60 * 1000L);
        if (fromUsage != null && !fromUsage.isEmpty()) {
            return fromUsage;
        }
        return readViaRoot();
    }

    /**
     * 经 root 读。
     *
     * 用 root shell 的 execLines 跑一次 dumpsys：这是必然要 fork 的命令，
     * 躲不掉，所以只在没有使用情况权限时才走。连续失败 MAX_FAILURES 次就不再试——
     * 没有 root 的设备上每轮白跑一次 dumpsys 是纯粹的浪费。
     */
    private static String readViaRoot() {
        if (disabled) {
            return null;
        }
        try {
            RootShell shell = RootShell.get();
            if (shell == null || shell.isDead()) {
                noteFailure();
                return null;
            }
            List<String> lines = shell.execLines(
                    "dumpsys activity activities 2>/dev/null | head -n " + DUMPSYS_MAX_LINES,
                    DUMPSYS_MAX_LINES, 3000L);
            if (lines == null || lines.isEmpty()) {
                noteFailure();
                return null;
            }
            String pkg = parseForeground(lines);
            if (pkg == null) {
                // 命令跑成功了但没解析出东西：格式跟预期不一样，不是「没 root」。
                // 同样计入失败，避免一直空转
                noteFailure();
                return null;
            }
            failures = 0;
            return pkg;
        } catch (Throwable t) {
            LogUtils.w("readViaRoot failed", t);
            noteFailure();
            return null;
        }
    }

    private static void noteFailure() {
        if (++failures >= MAX_FAILURES) {
            disabled = true;
            LogUtils.i("ForegroundAppReader: root channel disabled after repeated failures");
        }
    }

    /**
     * 从 dumpsys activity activities 的输出里挑出栈顶应用包名。
     *
     * 先只在「activities from top to bottom」这个区段里找；找不到就放宽到全文。
     * 区分这一段是因为后面还有一大堆 Recents、进程列表的 section，里头也满是
     * ActivityRecord，全都能匹配上组件名，不划边界的话可能挑到某个后台任务。
     *
     * 找不到就退而求其次看 mResumedActivity / mCurrentFocus 这类单行字段，
     * 有些 ROM 用它报当前焦点。全都没有返回 null。
     */
    static String parseForeground(List<String> lines) {
        int start = 0;
        int end = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("activities from top to bottom")) {
                start = i + 1;
            } else if (start > 0 && line.startsWith("------") ) {
                // 区段结束的分隔线
                end = i;
                break;
            }
        }
        if (start >= end) {
            start = 0;
            end = lines.size();
        }

        for (int i = start; i < end; i++) {
            String pkg = extractPackage(lines.get(i));
            if (pkg != null) {
                return pkg;
            }
        }
        for (String line : lines) {
            int colon = line.indexOf("mResumedActivity");
            int focus = line.indexOf("mCurrentFocus");
            if (colon >= 0 || focus >= 0) {
                String pkg = extractPackage(line);
                if (pkg != null) {
                    return pkg;
                }
            }
        }
        return null;
    }

    /** 从一行里抠出组件名对应的包名，抠不到返回 null */
    static String extractPackage(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = COMPONENT.matcher(line);
        if (!m.find()) {
            return null;
        }
        String pkg = m.group(1);
        // "Activity"/"Window" 这类词本身也能凑出「两段式」，但它们不是包名
        if (pkg.startsWith("android.") || pkg.endsWith(".Window") || pkg.endsWith(".Activity")) {
            return null;
        }
        return pkg;
    }
}
