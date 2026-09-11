package com.jj.nexusfloat.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.jj.nexusfloat.collector.GpuCollectorWorker;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 开机自启（v1.8.0 加的），把模块进程拉起来直接开始 GPU 采集。
 *
 * 为什么需要它
 * GPU 频率/占用是模块 App 进程读 sysfs 之后写给 SystemUI 的（SystemUI 自己 su 不可靠）。
 * 重启后模块进程就没了，本来指望 SystemUI 的 resume 心跳通过 EarlyInitProvider 把它拉起来，
 * 但实测澎湃 OS 4 上这条链路时通时断（LSPosed 注入模块进程、Direct Boot 期间查 Provider
 * 都可能卡住），用户看到的就是「重启后 GPU 参数一直是旧值，手动打开一次 App 才恢复」。
 *
 * 为什么开机就采
 * 这里直接 setOverlayDemand(true)，不等 SystemUI 的心跳：
 * 监视条一般跟 SystemUI 一起出现，SystemUI 起来后每 30 拍发一次 resume 心跳维持 demand，
 * 不发 pause 就不会关；要是监视条在当前方向是关闭的，SystemUI 第一次 evaluate 就会发 pause，
 * 采集自动停下，多采的只是开机到 SystemUI 就绪之间那几秒；等心跳这个方案已经实测不可靠，
 * 它本身就是要修的那个 bug。
 *
 * 同时监听 LOCKED_BOOT_COMPLETED（Direct Boot 阶段就会触发）：未解锁时 root 读 sysfs 可能失败，
 * 但进程先起来、Provider 先注册，SystemUI 的心跳到了就不用再等冷启动。失败的那几拍
 * GpuCollectorWorker 自己的重试兜得住。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            return;
        }
        LogUtils.i("BootReceiver: " + action);
        GpuCollectorLauncher.startInProcess();
        // 见类注释：先采着，SystemUI 的 pause 心跳会把它停回去
        GpuCollectorWorker.setOverlayDemand(true);
    }
}
