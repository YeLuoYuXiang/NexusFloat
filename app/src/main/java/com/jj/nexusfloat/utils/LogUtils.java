package com.jj.nexusfloat.utils;

import android.util.Log;

import com.jj.nexusfloat.constant.Constants;

import io.github.libxposed.api.XposedModule;

/**
 * 日志统一从这里走，开不开由 Constants.Config#LOG_ENABLED 决定。
 */
public final class LogUtils {

    /**
     * 绑上的模块实例，让 i/w/e 这几个也能写进 LSPosed 模块日志。
     *
     * v1.6.10 及以前它们只写 logcat。logcat 环形缓冲在有刷屏进程的机器上几分钟
     * 就被冲掉，导出 LSPosed 日志时 SystemUI 侧什么诊断都看不到（比如
     * PrefsSnapshot、addView failed），排查时会误以为那些代码没执行，
     * 其实是日志没落到导得出来的地方。
     */
    private static volatile XposedModule sModule;

    private LogUtils() {}

    /** NexusBridge.bindModule 调这个；绑完之后普通日志也会进模块日志 */
    public static void bindModule(XposedModule module) {
        sModule = module;
    }

    public static boolean isEnabled() {
        return Constants.Config.LOG_ENABLED;
    }

    /** 模块日志和 logcat 各写一份 */
    private static void dual(int level, String msg, Throwable t) {
        if (!isEnabled()) {
            return;
        }
        XposedModule m = sModule;
        if (m != null) {
            try {
                if (t != null) {
                    m.log(level, Constants.TAG, msg, t);
                } else {
                    m.log(level, Constants.TAG, msg);
                }
            } catch (Throwable ignored) {
                // 模块日志写失败不能连累 logcat 那一份
            }
        }
        writeLogcat(level, msg, t);
    }

    public static void d(String msg) {
        dual(Log.DEBUG, msg, null);
    }

    public static void i(String msg) {
        dual(Log.INFO, msg, null);
    }

    public static void w(String msg) {
        dual(Log.WARN, msg, null);
    }

    public static void w(String msg, Throwable t) {
        dual(Log.WARN, msg, t);
    }

    public static void e(String msg) {
        dual(Log.ERROR, msg, null);
    }

    public static void e(String msg, Throwable t) {
        dual(Log.ERROR, msg, t);
    }

    /** 写 Xposed 模块日志，同时补一份到 logcat，方便 adb 过滤 */
    public static void xposed(XposedModule module, int level, String msg) {
        if (!isEnabled() || module == null) {
            return;
        }
        module.log(level, Constants.TAG, msg);
        writeLogcat(level, msg, null);
    }

    public static void xposed(XposedModule module, int level, String msg, Throwable t) {
        if (!isEnabled() || module == null) {
            return;
        }
        module.log(level, Constants.TAG, msg, t);
        writeLogcat(level, msg, t);
    }

    private static void writeLogcat(int level, String msg, Throwable t) {
        switch (level) {
            case Log.DEBUG:
                if (t != null) Log.d(Constants.TAG, msg, t);
                else Log.d(Constants.TAG, msg);
                break;
            case Log.INFO:
                if (t != null) Log.i(Constants.TAG, msg, t);
                else Log.i(Constants.TAG, msg);
                break;
            case Log.WARN:
                if (t != null) Log.w(Constants.TAG, msg, t);
                else Log.w(Constants.TAG, msg);
                break;
            case Log.ERROR:
            default:
                if (t != null) Log.e(Constants.TAG, msg, t);
                else Log.e(Constants.TAG, msg);
                break;
        }
    }
}
