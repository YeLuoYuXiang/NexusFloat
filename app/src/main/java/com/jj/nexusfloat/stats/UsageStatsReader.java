package com.jj.nexusfloat.stats;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import com.jj.nexusfloat.utils.LogUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 问系统要「亮屏多久、每个应用在前台多久」。
 *
 * 走 UsageStatsManager，要求用户授予「使用情况访问」权限（AppOps 的
 * OPSTR_GET_USAGE_STATS，是个设置里的开关，不是运行时弹窗）。没授权的时候
 * 系统会把事件过滤成只剩自己，拿到的数据是空的——不是异常，是空，所以
 * {@link #hasPermission} 必须单独问一次，不能靠「有没有拿到数据」来判断。
 *
 * 用 queryEvents 而不是 queryUsageStats：后者按天/周/月的桶来聚合，桶边界和
 * 「本次充电后到现在」这种任意区间对不上，而且 INTERVAL_BEST 的具体桶宽还会
 * 随区间长度变。queryEvents 给的是逐条原始事件，区间想怎么切就怎么切，
 * 前台时长和亮屏时长能一次算完。
 *
 * 事件类型用的是整型常量而不是 UsageEvents.Event 的静态字段：
 * ACTIVITY_RESUMED / ACTIVITY_PAUSED 是 API 29 才改的名字，在 API 26–28 上叫
 * MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND，但两者的值都是 1 和 2，直接写值
 * 可以一份代码兼顾 minSdk 26 到最新的全部版本。
 */
public final class UsageStatsReader {

    /** MOVE_TO_FOREGROUND / ACTIVITY_RESUMED */
    private static final int EVENT_RESUMED = 1;
    /** MOVE_TO_BACKGROUND / ACTIVITY_PAUSED */
    private static final int EVENT_PAUSED = 2;
    /** 屏幕点亮 */
    private static final int EVENT_SCREEN_INTERACTIVE = 15;
    /** 屏幕熄灭 */
    private static final int EVENT_SCREEN_NON_INTERACTIVE = 16;

    /**
     * 取区间起点之前多长时间的事件当「前情」。
     *
     * 区间刚开始的那一刻，前台是哪个应用、屏幕亮不亮，只有往前翻事件才知道。
     * 十分钟足够覆盖「充电器拔掉那一刻」前后；取更长只会让每次查询慢一点，
     * 并不增加精度——真正的区间累加是从 from 才开始算的。
     */
    private static final long PRELUDE_MS = 10 * 60 * 1000L;

    /** 一次查询最多取多少条事件，防异常数据把内存撑爆 */
    private static final int MAX_EVENTS = 200_000;

    private UsageStatsReader() {}

    /** 统计结果 */
    public static final class Snapshot {
        /** 是否有使用情况访问权限。false 时其余字段都是空的，不能当「用量为 0」用 */
        public boolean granted;
        /** 区间内亮屏总时长（毫秒） */
        public long screenOnMs;
        /** 包名 -> 前台时长（毫秒） */
        public final Map<String, Long> foregroundMs = new HashMap<>();
        /** 包名 -> 启动次数 */
        public final Map<String, Integer> launches = new HashMap<>();

        public boolean isEmpty() {
            return foregroundMs.isEmpty() && screenOnMs == 0L;
        }
    }

    /**
     * 有没有「使用情况访问」权限。
     *
     * 两个接口都返回 int（AppOpsManager.MODE_ALLOWED / MODE_IGNORED 这些），不是字符串，
     * 这点跟「设置里那一栏的描述文字」容易混淆，所以这里写明白。
     *
     * unsafeCheckOpNoThrow（API 29 起）不做前置的权限校验，读起来快一点；
     * 更低版本退回 checkOpNoThrow。两个都抛异常就返回 false——按没权限处理，
     * 让调用方走采样积分的兜底路径，不会因为读不到权限状态而误判成
     * 「有权限但数据为空」。
     */
    public static boolean hasPermission(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            String op = AppOpsManager.OPSTR_GET_USAGE_STATS;
            int uid = Process.myUid();
            String pkg = context.getPackageName();
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? ops.unsafeCheckOpNoThrow(op, uid, pkg)
                    : ops.checkOpNoThrow(op, uid, pkg);
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            LogUtils.w("hasUsagePermission check failed", t);
            return false;
        }
    }

    /**
     * 上一次 summarize 的结果缓存。
     *
     * 统计页每几秒重装一次数据，采样服务也会定期刷新亮屏时长，两边问的都是
     * 「同一个周期、终点稍微往后挪一点」的同一个区间。不做缓存的话，一个跨三天的
     * 周期每次都要把几万条 UsageEvents 翻一遍，而这只是为了一张图上多出来的
     * 几秒钟时长——完全没必要。
     *
     * 命中条件是区间起点完全一致（换了周期就不能复用）且缓存不超过 TTL。
     * 终点差几十秒对「亮屏 4 小时」「微信用了 1 小时 20 分」这种量级没有影响。
     */
    private static final long CACHE_TTL_MS = 60_000L;
    private static volatile long cachedFrom = Long.MIN_VALUE;
    private static volatile long cachedAt;
    private static volatile Snapshot cachedSnapshot;

    /**
     * 读区间 [from, to] 里每个应用的前台时长、启动次数和亮屏时长。
     *
     * 事件流是「状态变化」而不是「持续时长」，所以要把相邻两个事件之间的那段
     * 时间记在当前状态头上：状态是「A 在前台、屏幕亮着」时那段就同时进 A 的前台
     * 时长和亮屏时长。
     *
     * 边界处理：
     * - 事件时间戳早于 from 的，只用来更新状态，不计入时长（前情）；
     * - 事件时间戳晚于 to 的，直接结束循环，后面不用看了；
     * - 区间末尾还有一段没被任何事件封口，补一段 [prev, to]。
     */
    public static Snapshot summarize(Context context, long from, long to) {
        long now = System.currentTimeMillis();
        Snapshot cached = cachedSnapshot;
        if (cached != null && cachedFrom == from && now - cachedAt < CACHE_TTL_MS) {
            return cached;
        }

        Snapshot snap = new Snapshot();
        if (to <= from) {
            return snap;
        }
        snap.granted = hasPermission(context);
        if (!snap.granted) {
            return snap;
        }

        UsageStatsManager usm =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            snap.granted = false;
            return snap;
        }

        UsageEvents events = null;
        try {
            events = usm.queryEvents(from - PRELUDE_MS, to);
        } catch (Throwable t) {
            LogUtils.w("queryEvents failed", t);
        }
        if (events == null) {
            return snap;
        }

        UsageEvents.Event e = new UsageEvents.Event();
        long prev = from;
        boolean screenOn = false;
        // 前情区间里如果一直没有任何事件，说明这段时间没人动过屏幕，
        // 默认按「屏幕灭着、没有前台应用」处理——这是最保守的假设，
        // 万一实际是亮着，误差也只是把区间开头的时长丢掉，不会凭空多算
        String foreground = null;
        int seen = 0;

        while (seen < MAX_EVENTS && events.hasNextEvent()) {
            events.getNextEvent(e);
            seen++;
            long ts = e.getTimeStamp();
            if (ts > to) {
                break;
            }
            int type = e.getEventType();
            String pkg = e.getPackageName();

            if (ts >= from) {
                accumulate(snap, prev, ts, foreground, screenOn);
                prev = ts;
            }

            switch (type) {
                case EVENT_RESUMED:
                    foreground = pkg;
                    if (ts >= from && pkg != null) {
                        Integer n = snap.launches.get(pkg);
                        snap.launches.put(pkg, n == null ? 1 : n + 1);
                    }
                    break;
                case EVENT_PAUSED:
                    if (pkg != null && pkg.equals(foreground)) {
                        foreground = null;
                    }
                    break;
                case EVENT_SCREEN_INTERACTIVE:
                    screenOn = true;
                    break;
                case EVENT_SCREEN_NON_INTERACTIVE:
                    screenOn = false;
                    break;
                default:
                    break;
            }
        }

        // 末尾那段没有封口事件
        accumulate(snap, prev, to, foreground, screenOn);

        cachedFrom = from;
        cachedAt = now;
        cachedSnapshot = snap;
        return snap;
    }

    /** 把 [from, to] 这段挂到当前状态上 */
    private static void accumulate(Snapshot snap, long from, long to,
                                   String foreground, boolean screenOn) {
        long span = to - from;
        if (span <= 0) {
            return;
        }
        if (screenOn) {
            snap.screenOnMs += span;
        }
        if (foreground != null) {
            Long old = snap.foregroundMs.get(foreground);
            snap.foregroundMs.put(foreground, (old == null ? 0L : old) + span);
        }
    }

    /**
     * 此刻前台是哪个包。
     *
     * 只往前看一小段（默认 5 分钟）取最后一条 RESUMED 事件：翻太长的历史没必要，
     * 采样本身就是十几秒一次。拿到的事件可能已经过时（用户切走了但事件还没到），
     * 这种情况由下一个采样点自己纠正，不必在这里做额外校验。
     *
     * 返回 null 表示读不到（没权限或者事件为空），调用方按「归不到具体应用」处理。
     */
    public static String lastForegroundPackage(Context context, long lookbackMs) {
        if (!hasPermission(context)) {
            return null;
        }
        UsageStatsManager usm =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return null;
        }
        try {
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - lookbackMs, now);
            if (events == null) {
                return null;
            }
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            long lastTs = 0L;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == EVENT_RESUMED && e.getTimeStamp() >= lastTs) {
                    lastTs = e.getTimeStamp();
                    last = e.getPackageName();
                }
            }
            return last;
        } catch (Throwable t) {
            LogUtils.w("lastForegroundPackage failed", t);
            return null;
        }
    }
}
