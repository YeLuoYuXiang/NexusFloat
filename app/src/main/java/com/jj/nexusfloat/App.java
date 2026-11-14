package com.jj.nexusfloat;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.jj.nexusfloat.bridge.NexusRemoteWriter;
import com.jj.nexusfloat.collector.GpuCollectorWorker;
import com.jj.nexusfloat.constant.Constants;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块的 Application：绑上 libxposed 的 XposedService 之后把 GPU 采集起起来。
 *
 * v1.6.10 起 GPU 采集不再等 XposedService 了。实测澎湃 OS 4 上 LSPosed 只注入
 * SystemUI，不注入模块自己这个进程，服务永远绑不上。采集改成照常跑，结果写本地
 * SharedPreferences，SystemUI 那边通过 exported ContentProvider 来读；
 * 服务要是能绑上，就顺带再写一份 RemotePreferences。
 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static volatile XposedService xposedService;

    public static XposedService getXposedService() {
        return xposedService;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        stampPrefsRevision();
        // 本地写入通道要 Context，而且必须赶在采集线程起来之前
        NexusRemoteWriter.bind(this);
        XposedServiceHelper.registerListener(this);
    }

    /**
     * 启动时给本地设置表盖一个修订号（v1.9.4）。
     *
     * 为什么在这里做：SystemUI 侧（NexusBridge）按修订号挑最新的那条通道读设置，
     * 而 RemotePreferences 里可能留着上次注入有效时的旧值。本地 SharedPreferences
     * 是设置的源头——每次改设置都是先写它、再推给别的通道——所以进程一起来它就代表
     * 「此刻最新的一份」。盖个号，provider 快照那条通道立刻就能证明自己比 Remote 新，
     * 用户升级完不用先去动一下某个开关，打开 App 就恢复正常。
     *
     * 这里只写本地，不触发 publish：publish 要 fork 一次 root 的 settings 命令，
     * 而 App 进程会被广播频繁拉起来，不该每次启动都付这个代价。
     */
    private void stampPrefsRevision() {
        try {
            SharedPreferences prefs = getSharedPreferences(
                    Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putLong(Constants.Remote.KEY_PREFS_REV, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
            // 盖号失败只是让读侧退回到老顺序，不该影响进程启动
        }
    }

    /** Xposed 服务绑上了 → Remote 通道可用，顺便确认一下采集在跑 */
    @Override
    public void onServiceBind(XposedService service) {
        xposedService = service;
        GpuCollectorWorker.start();
    }

    /**
     * 服务断开只把引用清掉，采集不停：本地通道还能用，
     * 停掉的话 SystemUI 那边的 GPU 数据就凭空没了。
     */
    @Override
    public void onServiceDied(XposedService service) {
        xposedService = null;
    }
}
