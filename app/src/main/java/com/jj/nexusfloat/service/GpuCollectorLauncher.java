package com.jj.nexusfloat.service;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import com.jj.nexusfloat.collector.GpuCollectorWorker;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 把模块 App 进程拉起来，然后启动 GpuCollectorWorker。
 * SystemUI 那边 su 不可靠，所以 GPU 采集在模块进程里做完，再经 Remote 传给 SystemUI。
 */
public final class GpuCollectorLauncher {

    private GpuCollectorLauncher() {}

    /** 已经在模块进程里，直接启动采集线程 */
    public static void startInProcess() {
        GpuCollectorWorker.start();
    }

    /**
     * 查一次 EarlyInitProvider，把模块进程拉起来。
     * 系统会自己启动宿主进程并触发 Provider.onCreate()，不用广播。
     */
    public static void wakeModuleByProvider(Context context) {
        if (context == null) {
            return;
        }
        try {
            Uri uri = Uri.parse("content://" + Constants.Component.EARLY_INIT_AUTHORITY);
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                cursor.close();
            }
            LogUtils.i("wakeModuleByProvider: ok");
        } catch (Throwable t) {
            LogUtils.w("wakeModuleByProvider failed", t);
        }
    }

    /**
     * 外进程来唤醒：走 ContentProvider 把模块进程拉起来；
     * 要是本来就在模块进程里，直接 start。
     */
    public static void wakeFromExternal(Context context) {
        wakeModuleByProvider(context);
        if (context != null && Constants.Package.MODULE.equals(context.getPackageName())) {
            startInProcess();
        }
    }
}


