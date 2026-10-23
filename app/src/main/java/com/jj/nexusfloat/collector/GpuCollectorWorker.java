package com.jj.nexusfloat.collector;

import android.os.Handler;
import android.os.HandlerThread;
import com.jj.nexusfloat.App;
import com.jj.nexusfloat.bridge.NexusRemoteWriter;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import io.github.libxposed.service.XposedService;

/**
 * 模块 App 进程里的后台 GPU 采集，没有前台 Service。
 *
 * 流程：等 App.getXposedService() 绑定上，然后 root 读 sysfs，数值有变化就写进 Remote。
 *
 * 采不采由两个互不相干的需求方共同决定，任一方要采就采，都不要就停：
 *
 * overlay 是 SystemUI 那边的监视条。默认 false，进程重启后也还是 false，完全是
 * 由 SystemUI 每 Constants.Overlay.SIGNAL_REPEAT_TICKS 秒发来的 resume 心跳驱动的。
 * 这样监视条一隐藏，SystemUI 就不发心跳，采集自然停住，不会因为模块进程被谁顺手
 * 拉起来就又自己跑起来。
 *
 * ui 是模块 App 自己的设置界面。它的「运行状态」卡片要显示实时 GPU 读数，所以界面
 * 在前台时必须采集，哪怕监视条正藏着。
 *
 * 两边分开记是为了别互相覆盖，否则打开一次 App 就把监视条那边的暂停状态冲掉了，
 * 退出 App 之后再没人把它停回去。
 */
public final class GpuCollectorWorker {

    private static HandlerThread workerThread;
    private static Handler workerHandler;
    private static volatile boolean running;
    private static int waitServiceTicks;

    /**
     * SystemUI 监视条要不要数据。
     *
     * 默认 false，靠 SystemUI 的 resume 心跳打开。要是反过来默认 true，模块进程
     * 每次被系统回收再拉起来都会自作主张开始采集，而 SystemUI 那边只在状态变化时
     * 才下发指令，没人来纠正它，「关了显示还在耗电」就是这么来的。
     */
    private static boolean overlayDemand;
    /** 模块 App 的设置界面在不在前台 */
    private static boolean uiDemand;

    /** 上次成功写进 Remote 的值 */
    private static int lastPublishedMhz = Constants.Config.UNPUBLISHED;
    private static int lastPublishedUsage = Constants.Config.UNPUBLISHED;

    private static final Runnable collectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            tick();
            workerHandler.postDelayed(this, Constants.Config.UPDATE_INTERVAL_MS);
        }
    };

    private GpuCollectorWorker() {}

    /**
     * 按当前需求启停。进程拉起、Xposed 服务绑定这些时机调，
     * 它自己不产生需求，没有需求方就什么都不做。
     */
    public static synchronized void start() {
        apply();
    }

    /** 硬停：服务断开这种场景，不管谁还想要数据都停掉 */
    public static synchronized void stop() {
        overlayDemand = false;
        uiDemand = false;
        apply();
    }

    /** SystemUI 监视条的需求，EarlyInitProvider 收到指令后调这里 */
    public static synchronized void setOverlayDemand(boolean want) {
        overlayDemand = want;
        apply();
    }

    /** 模块 App 设置界面的需求，跟着 Activity 的 onStart/onStop 切 */
    public static synchronized void setUiDemand(boolean want) {
        uiDemand = want;
        apply();
    }

    private static void apply() {
        boolean wanted = overlayDemand || uiDemand;
        if (wanted == running) {
            return;
        }
        if (wanted) {
            running = true;
            ensureThread();
            workerHandler.removeCallbacks(collectRunnable);
            workerHandler.post(collectRunnable);
            LogUtils.i("GpuCollectorWorker started");
        } else {
            running = false;
            // 把已发布值复位：下次恢复时第一轮必定重新写一次 Remote，
            // 不然暂停期间 SystemUI 读到的还是停之前的旧值
            lastPublishedMhz = Constants.Config.UNPUBLISHED;
            lastPublishedUsage = Constants.Config.UNPUBLISHED;
            if (workerHandler != null) {
                workerHandler.removeCallbacks(collectRunnable);
            }
            LogUtils.i("GpuCollectorWorker stopped");
        }
    }

    private static void ensureThread() {
        if (workerThread != null && workerThread.isAlive()) {
            return;
        }
        workerThread = new HandlerThread(Constants.ThreadName.GPU_COLLECTOR);
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    private static void tick() {
        int gpuMhz = GpuRootReader.readFreqMhz();
        int gpuUsage = GpuRootReader.readUsagePercent();

        // 频率和占用都没变就不写，省点传输开销
        if (gpuMhz == lastPublishedMhz && gpuUsage == lastPublishedUsage) {
            return;
        }

        // 先写本地 SharedPreferences，SystemUI 经 EarlyInitProvider 读的就是它。
        // v1.6.9 及以前这里第一件事是 if (service == null) return，结果在模块自身
        // 进程没被注入的机型上（实测澎湃 OS 4）采集永远不执行，GPU 数据一直是空的。
        // 本地写入不依赖任何框架能力，必须先做
        boolean written = NexusRemoteWriter.publishLocal(gpuMhz, gpuUsage);

        // 这里不走 Settings.Global：那条通道每写一次要 fork 一个 settings 进程，
        // 而 GPU 数据每秒都在变，等于每分钟白烧 60 次 fork。
        // 监视条上的 GPU 频率本来也是 SystemUI 侧自己 root 直读 sysfs，
        // 不靠这条跨进程通道。Settings.Global 只留给用户偶尔改动的设置

        // Remote 只在服务可用时顺带写一份：两条通道并存的机型上它更快，
        // 但它不再是采集的前提
        XposedService service = App.getXposedService();
        if (service != null) {
            waitServiceTicks = 0;
            NexusRemoteWriter.publish(service, gpuMhz, gpuUsage);
        } else {
            waitServiceTicks++;
            if (waitServiceTicks % Constants.Config.SERVICE_BIND_WARN_EVERY_TICKS == 1) {
                LogUtils.w("XposedService unavailable, using provider channel only");
            }
        }

        if (written) {
            lastPublishedMhz = gpuMhz;
            lastPublishedUsage = gpuUsage;
        } else if (gpuMhz > 0) {
            LogUtils.w("local publish failed, gpuMhz=" + gpuMhz);
        }
    }
}
