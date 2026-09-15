package com.jj.nexusfloat.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.jj.nexusfloat.collector.GpuCollectorWorker;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 被后台拉起的空 Service（v1.8.11）。
 *
 * 它不干正事，唯一的作用是「把进程带起来」。进程一起来，Application.onCreate
 * 就会跑，GpuCollectorWorker 也就跟着起来了，采集照常进行。
 *
 * 为什么用 Service 而不是广播：ColorOS 在最近任务「全部清除」之后会把应用置为
 * stopped 状态（等同 adb am force-stop）。这个状态下系统会拒绝隐式唤醒，
 * 广播也在其中——实测带 FLAG_INCLUDE_STOPPED_PACKAGES 也拦。而用 root 执行
 * am start-service 走的是 shell 权限那条路，AMS 对 stopped 的拦截不适用。
 *
 * 必须 exported：SystemUI 是另一个应用（虽然是 system uid），要能启动它。
 * 启动方式只有 root 的 am 命令一条，应用层没有入口。
 *
 * 这里返回 START_NOT_STICKY：被杀不用系统重启，下一次唤醒会再拉起来，
 * 不需要靠 sticky 赖着。
 */
public class WakeService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogUtils.i("WakeService started, waking collector");
        // 进程已经起来了，把采集需求打开。系统之后要是想回收这个 Service，
        // 采集线程还在模块进程里跑着，不受影响
        GpuCollectorWorker.setOverlayDemand(true);
        GpuCollectorLauncher.startInProcess();
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
