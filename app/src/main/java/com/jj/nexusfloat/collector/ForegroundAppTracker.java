package com.jj.nexusfloat.collector;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;

import com.jj.nexusfloat.utils.LogUtils;

import java.util.List;

/**
 * 读当前前台应用包名。
 *
 * 两个地方要用：一个是「仅在选定应用显示」的白名单判定（MonitorOverlay），
 * 一个是把 FPSGO 的逐进程帧率对准前台应用（FpsgoReader）。
 *
 * 这里跑在 SystemUI 进程（system uid），手里有 REAL_GET_TASKS，
 * ActivityManager.getRunningTasks(int) 才拿得到真实栈顶。普通应用调这个方法
 * 只看得到自己，所以只有 Hook 侧能用，App 进程里别指望。
 *
 * 结果加了个短缓存。监视条一秒轮询一次，旋转广播还可能在同一拍里再触发一次判定，
 * 有缓存就不用重复跨进程调。
 *
 * 非线程安全，内部状态没做同步，一个实例只能给一个线程用。悬浮窗判定在主线程、
 * 帧率采集在采集线程，所以两边各拿一个实例，没共用。
 */
public final class ForegroundAppTracker {

    /** 缓存有效期（毫秒）。比轮询间隔小得多，切应用不会因此变迟钝 */
    private static final long CACHE_TTL_MS = 300L;

    private final ActivityManager activityManager;

    private String cachedPackage;
    private long cachedAt;
    /** 连续失败到这个次数就不试了，省得每秒都打日志 */
    private int failures;
    private static final int MAX_FAILURES = 5;

    public ForegroundAppTracker(Context context) {
        this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    }

    /** 返回前台包名；读不到就给 null，调用方按「不过滤」处理 */
    public String getForegroundPackage() {
        long now = SystemClock.uptimeMillis();
        if (cachedPackage != null && now - cachedAt < CACHE_TTL_MS) {
            return cachedPackage;
        }
        if (activityManager == null || failures >= MAX_FAILURES) {
            return null;
        }

        try {
            List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) {
                return null;
            }
            ComponentName top = tasks.get(0).topActivity;
            if (top == null) {
                return null;
            }
            cachedPackage = top.getPackageName();
            cachedAt = now;
            failures = 0;
            return cachedPackage;
        } catch (Throwable t) {
            failures++;
            if (failures == 1) {
                LogUtils.w("getRunningTasks failed, app filter will be ignored", t);
            }
            return null;
        }
    }
}
