package com.jj.nexusfloat.service;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

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
}
