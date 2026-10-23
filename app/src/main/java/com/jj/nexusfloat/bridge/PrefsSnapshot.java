package com.jj.nexusfloat.bridge;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SystemUI 这边的设置快照通道：借模块 App 的 exported ContentProvider 读设置。
 *
 * 为什么要它（v1.6.10）
 * libxposed 的 RemotePreferences 要求模块自己的进程也被框架注入——XposedServiceHelper
 * 拿到的 service 就是那时候建立的。实测澎湃 OS 4 上 LSPosed 只把模块注入了
 * com.android.systemui，模块自己的进程没注入：作用域表里只有一条记录，
 * 日志里 (com.jj.nexusfloat) 零匹配。
 * 后果就是 App 侧 getXposedService() 恒为 null，写设置时只落到本地 SharedPreferences，
 * Remote 那半段被跳过。SystemUI 轮询 Remote 永远读到空，于是所有开关不生效、
 * GPU 数据不更新，而监视条本身还能显示（它读不到设置就用默认值）。
 *
 * 线程约束
 * ContentResolver.query 是同步 binder 调用，目标进程不在时还会触发一次进程启动，
 * 耗时能到几百毫秒。绝对不能放在 SystemUI 主线程上做，否则掉帧甚至 ANR。
 * 所以这里在后台线程刷新，主线程只读内存里的快照。
 *
 * 快照第一次填上之前，所有读取都会落到默认值，监视条会先按默认设置显示一两秒，
 * 这比阻塞主线程可接受得多。
 */
public final class PrefsSnapshot {

    /**
     * 刷新线程。单线程就够了：每秒最多一次查询，而且必须串行——
     * 并发刷新两次结果会互相覆盖，设置看起来就会来回跳。
     */
    private static final ExecutorService REFRESHER =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, Constants.ThreadName.PREFS_SNAPSHOT);
                t.setDaemon(true);
                return t;
            });

    /**
     * 当前快照。整体替换而不是增量改，所以读侧不用加锁：
     * 拿到的引用要么是旧的完整快照，要么是新的完整快照，读不到半成品。
     */
    private static volatile Map<String, String> snapshot = new HashMap<>();
    /** 跟 snapshot 配对的类型表 */
    private static volatile Map<String, String> types = new HashMap<>();

    /** 上次刷新完成的时间，用来限流 */
    private static volatile long lastRefreshMs;
    /** 有没有刷新正在跑，免得任务堆起来 */
    private static volatile boolean refreshing;

    /** 快照有没有成功填过一次 */
    private static volatile boolean everLoaded;

    private static volatile Context context;

    private PrefsSnapshot() {}

    /** 绑上 SystemUI 的 Context，后面 query 要用 */
    public static void bind(Context ctx) {
        context = ctx;
    }

    /** 快照能不能用；不能用时调用方该退回 RemotePreferences 或默认值 */
    public static boolean isReady() {
        return everLoaded;
    }

    /**
     * 请求刷新快照。
     *
     * 监视条的每秒轮询会调它。真正查询在后台线程做，这方法立刻返回，
     * 所以在主线程上调用是安全的。限流到 SNAPSHOT_REFRESH_MS：
     * 设置改动的生效延迟本来就是秒级，没必要刷得更勤。
     */
    public static void requestRefresh() {
        if (context == null || refreshing) {
            return;
        }
        long now = System.currentTimeMillis();
        if (everLoaded && now - lastRefreshMs < Constants.Overlay.SNAPSHOT_REFRESH_MS) {
            return;
        }
        refreshing = true;
        try {
            REFRESHER.execute(PrefsSnapshot::refresh);
        } catch (Throwable t) {
            refreshing = false;
        }
    }

    private static void refresh() {
        try {
            Map<String, String> values = new HashMap<>();
            Map<String, String> kinds = new HashMap<>();
            Uri uri = Uri.parse("content://" + Constants.Component.EARLY_INIT_AUTHORITY
                    + "/" + Constants.Component.PATH_PREFS);
            Cursor c = context.getContentResolver().query(uri, null, null, null, null);
            if (c == null) {
                LogUtils.w("PrefsSnapshot: provider returned null cursor");
                return;
            }
            try {
                int ik = c.getColumnIndex(Constants.Component.COL_KEY);
                int it = c.getColumnIndex(Constants.Component.COL_TYPE);
                int iv = c.getColumnIndex(Constants.Component.COL_VALUE);
                if (ik < 0 || it < 0 || iv < 0) {
                    LogUtils.w("PrefsSnapshot: unexpected cursor columns");
                    return;
                }
                while (c.moveToNext()) {
                    String k = c.getString(ik);
                    if (k == null) {
                        continue;
                    }
                    values.put(k, c.getString(iv));
                    kinds.put(k, c.getString(it));
                }
            } finally {
                c.close();
            }
            // 整体替换，所以读侧不用加锁
            snapshot = values;
            types = kinds;
            if (!everLoaded) {
                LogUtils.i("PrefsSnapshot loaded, " + values.size() + " keys");
            }
            everLoaded = true;
            lastRefreshMs = System.currentTimeMillis();
        } catch (Throwable t) {
            LogUtils.w("PrefsSnapshot refresh failed", t);
        } finally {
            refreshing = false;
        }
    }

    /** 键在不在快照里 */
    public static boolean contains(String key) {
        return snapshot.containsKey(key);
    }

    public static boolean getBoolean(String key, boolean def) {
        String v = snapshot.get(key);
        if (v == null) {
            return def;
        }
        // 只认 boolean 类型存进去的值：类型对不上说明这个键被复用了，用默认值更安全
        if (!Constants.Component.TYPE_BOOL.equals(types.get(key))) {
            return def;
        }
        return Boolean.parseBoolean(v);
    }

    public static int getInt(String key, int def) {
        String v = snapshot.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static float getFloat(String key, float def) {
        String v = snapshot.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long getLong(String key, long def) {
        String v = snapshot.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static String getString(String key, String def) {
        String v = snapshot.get(key);
        return v != null ? v : def;
    }
}
