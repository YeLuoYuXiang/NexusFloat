package com.jj.nexusfloat;

import android.app.Application;

import com.jj.nexusfloat.bridge.NexusRemoteWriter;
import com.jj.nexusfloat.collector.GpuCollectorWorker;

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
        // 本地写入通道要 Context，而且必须赶在采集线程起来之前
        NexusRemoteWriter.bind(this);
        XposedServiceHelper.registerListener(this);
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
