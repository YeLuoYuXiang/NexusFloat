package com.jj.nexusfloat.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.jj.nexusfloat.collector.GpuCollectorWorker;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 被 SystemUI 唤醒的入口（v1.8.11）。
 *
 * 为什么需要它：ColorOS 在最近任务里点「全部清除」会把应用置为 stopped 状态
 * （等同 adb am force-stop），此后 ContentProvider query、隐式广播、JobScheduler
 * 全部被系统拒绝，进程拉不起来，GPU 数据就停更。而划卡只是普通杀进程，
 * 不置 stopped，所以那时是好的——这个 bug 只在 ColorOS 全部清除后出现，原因就在这里。
 *
 * 系统对 stopped 应用唯一放行的口子是 Intent 带 FLAG_INCLUDE_STOPPED_PACKAGES，
 * 这个 flag 由发送方加。发送方是我们的 SystemUI 进程（已被注入，代码我们自己写），
 * 所以不需要 hook 系统框架、也不用给模块加 system 作用域。
 *
 * 收到后只做一件事：把采集需求打开。进程是系统因广播而拉起来的，
 * 起来之后 GpuCollectorWorker 照常跑，SystemUI 那边就能继续读到 GPU 数据。
 */
public class WakeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Constants.Component.ACTION_WAKE.equals(action)) {
            return;
        }
        LogUtils.i("WakeReceiver: woken by SystemUI");
        // 只管揭开需求。进程可能是冷启动起来的，这里不能假定
        // GpuCollectorWorker 已经在跑，start() 会按需求把它拉起来
        GpuCollectorWorker.setOverlayDemand(true);
        GpuCollectorLauncher.startInProcess();
    }
}
