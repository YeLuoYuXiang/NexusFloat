package com.jj.nexusfloat.collector;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.jj.nexusfloat.bridge.NexusBridge;
import com.jj.nexusfloat.constant.Constants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SystemUI 进程里的性能采集：CPU / 内存 / 电池 / FPS。
 * GPU 频率和占用来自 com.jj.nexusfloat.bridge.NexusBridge（模块侧 root 写进 Remote）。
 */
public class PerformanceCollector {

    public interface OnUpdateListener {
        void onUpdate(PerformanceData data);
    }

    public static class PerformanceData {
        public List<Float> cpuCoreUsages = new ArrayList<>();
        public float cpuTotalUsage = 0f;
        /** 各核 scaling_cur_freq 最大值（MHz） */
        public int cpuFreqMaxMhz = 0;
        public int cpuFreqMinMhz = 0;

        public List<Float> gpuCoreUsages = new ArrayList<>(); // Often just 1
        public int gpuFreq = 0;
        public int gpuTotalUsage = 0;

        public float fps = 0;
        /**
         * 帧率是不是来自应用级数据源（SurfaceFlinger / FPSGO / GED KPI）。
         *
         * 为 false 时来自面板实测帧率或者 Choreographer，反映的是屏幕刷新率而不是
         * 应用渲染帧率。这个标记决定 0 帧要不要显示：应用级数据源报 0 表示画面静止，
         * 是真实结果；兜底源报 0 只是没读到。
         */
        public boolean fpsFromApp = false;
        /**
         * 本拍帧率的来源标记（单字符），只在「FPS 诊断」打开时非空。
         * 取值见 Constants.Fps.TAG_*。
         */
        public String fpsTag = "";
        /**
         * 允不允许退回 Choreographer 计帧。
         *
         * 只有「自动」方案允许。用户明确选了高通或联发科方案时置 false：那两条路读不到
         * 就该显示 0，而不是拿屏幕刷新率冒充应用帧率，后者正是「30 帧游戏显示 120」
         * 这个 bug 的表现。
         */
        public boolean useChoreographerFallback = true;
        public boolean isCharging = false;

        public float batteryPowerW = 0f;
        /** 电池电流（mA），正是充电、负是放电；读不到时是 0 */
        public float batteryCurrentMa = 0f;
        public float batteryTemp = 0f;
        /** CPU 温度（℃），Float.NaN 表示读不到 */
        public float cpuTemp = Float.NaN;

        public float ramUsagePercent = 0f;
        public float zramUsagePercent = 0f;
    }

    private final Context context;
    private final Handler mainHandler;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private final OnUpdateListener listener;

    private boolean isRunning = false;

    private final BatteryManager batteryManager;
    private final ActivityManager activityManager;

    // CPU calculation state
    private final long[][] lastCpuTimes = new long[16][2]; // [core][0: idle, 1: total], index 0 is total CPU

    // Battery Intent state
    private float lastBatteryTemp = 0f;
    private int lastBatteryVoltageMv = 0;
    private int lastBatteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;

    private long lastFpsTime = 0;
    private int currentFps = 0;

    private float lastSuccessfulFps = 0f;
    private int lastSuccessfulGpuFreq = 0;
    private int lastSuccessfulCpuFreqMaxMhz = 0;
    private int lastSuccessfulCpuFreqMinMhz = 0;
    private int lastSuccessfulGpuUsage = -1;
    /** 功率取值状态机：来源优先级、静态值识别与陈旧值上限都在其中 */
    private final PowerTracker powerTracker = new PowerTracker();
    /** 联发科 FPSGO 逐进程帧率读取；非联发科机型会持续返回 READ_FAILED */
    private final FpsgoReader fpsgoReader = new FpsgoReader();
    /**
     * SurfaceFlinger 帧时间戳；与芯片厂商无关，是应用级帧率的主力来源。
     * 只在 SystemUI 进程内可用（需要 android.permission.DUMP 权限）。
     */
    private final SurfaceFlingerFpsReader sfFpsReader = new SurfaceFlingerFpsReader();
    /**
     * SurfaceFlinger TimeStats 逐 layer 帧数增量。
     *
     * --latency 背后的 FrameTracker 在 Android 14 起被 FrameTimeline 取代，
     * 参数保留但不再填数据。实测天玑 1200 / Android 13 正常，天玑 9400 /
     * Android 16 上所有候选层的帧环都是空的。TimeStats 是官方替代路径。
     */
    private final TimeStatsFpsReader timeStatsReader = new TimeStatsFpsReader();
    /** 联发科 GED KPI 逐进程帧率；作为 FPSGO 之后的补充 */
    private final GedKpiReader gedKpiReader = new GedKpiReader();
    /**
     * 前台包名，用来把 FPSGO 的逐进程帧率对准前台应用。
     *
     * 与 MonitorOverlay 各持一个实例：那个在主线程用，这个在采集线程用，
     * 而 ForegroundAppTracker 的缓存字段没有做同步。
     */
    private final ForegroundAppTracker foregroundTracker;

    /**
     * 帧计数在主线程递增、采集线程读取，所以用 AtomicInteger 保证可见性。
     * 之前的普通 int 在采集线程可能长期读到旧值，帧率会一直是 0。
     */
    private final AtomicInteger frameCounter = new AtomicInteger();

    private final android.view.Choreographer.FrameCallback frameCallback = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameCounter.incrementAndGet();
            if (isRunning) {
                android.view.Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            long now = System.currentTimeMillis();
            float calcFps = 0;
            if (lastFpsTime > 0) {
                long delta = now - lastFpsTime;
                // 窗口取 500ms（小于采集周期），保证每轮都能算出帧率；
                // 原先用 UPDATE_INTERVAL_MS 做下限，delta 恰好等于周期时会整轮跳过
                if (delta >= Constants.Fps.FRAME_WINDOW_MS) {
                    calcFps = frameCounter.getAndSet(0) * 1000f / delta;
                    currentFps = (int) calcFps;
                    lastFpsTime = now;
                }
            } else {
                lastFpsTime = now;
                frameCounter.set(0);
            }

            PerformanceData data = collectData();

            if (data.fpsFromApp) {
                // 应用级帧率（SurfaceFlinger / FPSGO / GED）：0 表示画面静止，
                // 是真实结果，不要用旧值覆盖
                lastSuccessfulFps = data.fps;
            } else if (!data.useChoreographerFallback) {
                // 用户关掉了 Choreographer：绝不用屏幕刷新率冒充应用帧率。
                //
                // 取不到数时沿用上一次的有效值，而不是直接跳 0：TimeStats 每次
                // 切应用要重建基线、SurfaceFlinger 换 layer 也有空窗，这些间隙
                // 属于取数机制的正常组成部分。每拍都闪回 0 会让数值不可读，
                // 而诊断标记已经如实报出了断点，不存在掩盖故障的问题。
                if (data.fps == Constants.Fps.READ_FAILED) {
                    data.fps = lastSuccessfulFps > 0 ? lastSuccessfulFps : 0f;
                } else {
                    lastSuccessfulFps = data.fps;
                }
            } else {
                // 面板节点也读不到（Fps.READ_FAILED）时，用 Choreographer 兜底
                if (data.fps == Constants.Fps.READ_FAILED) {
                    data.fps = calcFps > 0 ? calcFps : currentFps;
                }
                // 如果最终还是获取失败 (<=0)，使用上一次成功的数据
                if (data.fps <= 0) {
                    data.fps = lastSuccessfulFps;
                } else {
                    lastSuccessfulFps = data.fps;
                }
            }

            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onUpdate(data);
                }
            });

            bgHandler.postDelayed(this, intervalMs);
        }
    };

    /**
     * 当前采集周期（毫秒），由用户在「刷新时间」里设定。
     * 每轮结束时重读一次，改动下一拍就生效，不用重启采集线程。
     */
    private volatile int intervalMs = Constants.Config.UPDATE_INTERVAL_MS;

    /**
     * 应用用户设定的采集周期。
     *
     * ms 单位毫秒，超出 UPDATE_INTERVAL_MIN_MS 到 MAX 的范围时会被截断。
     */
    public void setIntervalMs(int ms) {
        intervalMs = Math.max(Constants.Config.UPDATE_INTERVAL_MIN_MS,
                Math.min(Constants.Config.UPDATE_INTERVAL_MAX_MS, ms));
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int tempInt = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                lastBatteryTemp = tempInt / 10f;
                lastBatteryVoltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                lastBatteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            }
        }
    };

    public PerformanceCollector(Context context, OnUpdateListener listener) {
        this.context = context;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        this.foregroundTracker = new ForegroundAppTracker(context);
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        context.registerReceiver(batteryReceiver, filter);

        // 复位帧计时：暂停期间没有帧回调，若沿用旧时间戳，
        // 恢复后第一轮会用「暂停时长」当分母，算出接近 0 的帧率
        lastFpsTime = 0;
        frameCounter.set(0);

        // Start FPS counter on main thread
        mainHandler.post(() -> {
            android.view.Choreographer.getInstance().postFrameCallback(frameCallback);
        });

        bgThread = new HandlerThread(Constants.ThreadName.NEXUS_COLLECTOR);
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        bgHandler.post(updateRunnable);
    }

    public void stop() {
        if (!isRunning) return;
        isRunning = false;

        try {
            context.unregisterReceiver(batteryReceiver);
        } catch (Exception e) {}

        if (bgHandler != null) {
            bgHandler.removeCallbacks(updateRunnable);
        }
        if (bgThread != null) {
            bgThread.quitSafely();
        }
        sfFpsReader.shutdown();
        // 采集停了，TimeStats 的基线也过期了：下次启动重新建基线，
        // 否则会拿停采集前的累计帧数与恢复后的值做差，算出离谱的帧率
        timeStatsReader.reset();
    }

    /** 在后台线程聚合各子项指标 */
    private PerformanceData collectData() {
        PerformanceData data = new PerformanceData();

        collectCpuData(data);
        collectGpuData(data);
        collectMemoryData(data);
        collectBatteryData(data);
        collectFpsData(data);

        return data;
    }

    private void collectCpuData(PerformanceData data) {
        try (BufferedReader br = new BufferedReader(new FileReader(Constants.Proc.STAT))) {
            String line;
            int coreIndex = 0; // 0 is total, 1..n are cores cpu0..cpuN
            while ((line = br.readLine()) != null) {
                if (line.startsWith("cpu")) {
                    String[] tokens = line.trim().split("\\s+");
                    if (tokens.length < 5) continue;

                    long user = Long.parseLong(tokens[1]);
                    long nice = Long.parseLong(tokens[2]);
                    long system = Long.parseLong(tokens[3]);
                    long idle = Long.parseLong(tokens[4]);
                    long iowait = tokens.length > 5 ? Long.parseLong(tokens[5]) : 0;
                    long irq = tokens.length > 6 ? Long.parseLong(tokens[6]) : 0;
                    long softirq = tokens.length > 7 ? Long.parseLong(tokens[7]) : 0;

                    long idleTime = idle + iowait;
                    long totalTime = user + nice + system + idle + iowait + irq + softirq;

                    if (coreIndex < 16) {
                        long lastIdle = lastCpuTimes[coreIndex][0];
                        long lastTotal = lastCpuTimes[coreIndex][1];

                        long deltaIdle = idleTime - lastIdle;
                        long deltaTotal = totalTime - lastTotal;

                        float usage = 0f;
                        if (deltaTotal > 0) {
                            usage = (deltaTotal - deltaIdle) * 100f / deltaTotal;
                        }

                        if (coreIndex == 0) {
                            data.cpuTotalUsage = usage;
                        } else {
                            data.cpuCoreUsages.add(usage);
                        }

                        lastCpuTimes[coreIndex][0] = idleTime;
                        lastCpuTimes[coreIndex][1] = totalTime;
                    }
                    coreIndex++;
                }
            }
        } catch (Exception e) {
            // Ignore or log
        }

        collectCpuFreqMax(data);
    }

    /** 读取各 policy / 各核 scaling_cur_freq，取当前最大值（MHz） */
    private void collectCpuFreqMax(PerformanceData data) {
        int maxMhz = 0;
        int minMhz = Integer.MAX_VALUE;

        File cpuFreqDir = new File(Constants.Cpu.CPUFREQ_DIR);
        if (cpuFreqDir.isDirectory()) {
            File[] policies = cpuFreqDir.listFiles((dir, name) -> name.startsWith("policy"));
            if (policies != null) {
                for (File policy : policies) {
                    int mhz = readCpuFreqMhz(
                            policy.getAbsolutePath() + "/" + Constants.Cpu.SCALING_CUR_FREQ);
                    if (mhz > 0) {
                        maxMhz = Math.max(maxMhz, mhz);
                        minMhz = Math.min(minMhz, mhz);
                    }
                }
            }
        }

        if (maxMhz == 0 || minMhz == Integer.MAX_VALUE) {
            File cpuDir = new File(Constants.Cpu.DEVICE_DIR);
            File[] cpus = cpuDir.listFiles((dir, name) -> name.matches("cpu\\d+"));
            if (cpus != null) {
                for (File cpu : cpus) {
                    int mhz = readCpuFreqMhz(
                            cpu.getAbsolutePath() + "/cpufreq/" + Constants.Cpu.SCALING_CUR_FREQ);
                    if (mhz > 0) {
                        maxMhz = Math.max(maxMhz, mhz);
                        minMhz = Math.min(minMhz, mhz);
                    }
                }
            }
        }

        if (maxMhz > 0) {
            data.cpuFreqMaxMhz = maxMhz;
            data.cpuFreqMinMhz = minMhz;
            lastSuccessfulCpuFreqMaxMhz = maxMhz;
            lastSuccessfulCpuFreqMinMhz = minMhz;
        } else if (lastSuccessfulCpuFreqMaxMhz > 0) {
            data.cpuFreqMaxMhz = lastSuccessfulCpuFreqMaxMhz;
            data.cpuFreqMinMhz = lastSuccessfulCpuFreqMinMhz;
        }
    }

    private int readCpuFreqMhz(String path) {
        String line = readFirstLine(path);
        if (line == null) {
            return 0;
        }
        try {
            long khz = Long.parseLong(line.trim());
            return (int) (khz / Constants.Cpu.KHZ_TO_MHZ);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * GPU 数据来源（v1.8.6 起三通道，优先级从高到低）：
     *
     * 1. 本进程 root 直读（GpuRootReader）：SystemUI 能 su 就自己读，
     *    最实时，也不依赖模块 App 进程是否存活。v1.8.6 提到第一位，
     *    重启后 App 进程没起来时 GPU 也能照常更新。
     * 2. 模块 App 进程经 Remote 传入：SystemUI 的 su 不稳定时走这条，
     *    App 侧采集线程把 root 读数写过来。
     * 3. 本进程直接读 sysfs（system uid 可读的那部分节点，联发科 GED）。
     *
     * 全都失败就退回上次有效值。
     */
    private void collectGpuData(PerformanceData data) {
        // 第一优先：SystemUI 侧 root 直读（su 可用即得数）
        int gpuMhz = GpuRootReader.readFreqMhz();
        if (gpuMhz <= 0) {
            gpuMhz = NexusBridge.getGpuFreqMhz();
        }
        if (gpuMhz <= 0) {
            gpuMhz = SysfsReader.direct().readGpuFreqMhz();
        }
        if (gpuMhz <= 0) {
            gpuMhz = lastSuccessfulGpuFreq;
        } else {
            lastSuccessfulGpuFreq = gpuMhz;
        }
        data.gpuFreq = gpuMhz;

        int usage = GpuRootReader.readUsagePercent();
        if (usage < 0) {
            usage = NexusBridge.getGpuUsage();
        }
        if (usage < 0) {
            usage = SysfsReader.direct().readGpuUsagePercent();
        }
        if (usage < 0) {
            usage = lastSuccessfulGpuUsage;
        } else {
            lastSuccessfulGpuUsage = usage;
        }
        if (usage >= 0) {
            data.gpuCoreUsages.add((float) usage);
            data.gpuTotalUsage = usage;
        }
    }

    private void collectMemoryData(PerformanceData data) {
        if (activityManager != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(mi);
            if (mi.totalMem > 0) {
                data.ramUsagePercent = (mi.totalMem - mi.availMem) * 100f / mi.totalMem;
            }
        }

        // ZRAM
        try (BufferedReader br = new BufferedReader(new FileReader(Constants.Proc.MEMINFO))) {
            String line;
            long swapTotal = 0, swapFree = 0;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("SwapTotal:")) {
                    swapTotal = parseMeminfoLine(line);
                } else if (line.startsWith("SwapFree:")) {
                    swapFree = parseMeminfoLine(line);
                }
            }
            if (swapTotal > 0) {
                data.zramUsagePercent = (swapTotal - swapFree) * 100f / swapTotal;
            }
        } catch (Exception e) {}
    }

    /**
     * 功率 = 电流 × 电压，符号由系统充放电状态决定（各家 ROM 对放电给正还是
     * 给负并不统一，所以不依赖内核电流的符号）。
     *
     * 电流优先走 BatteryManager 的 CURRENT_NOW，但不少联发科机型的 HAL
     * 没实现这个 property，会一直返回 0，功率就跟着一直是 0.00W——
     * v1.4.2 及之前功率读不出来就是这个原因。读不到时退回直读
     * /sys/class/power_supply/ 下的节点。
     *
     * 来源优先级和静态值识别都放在 PowerTracker。
     */
    private void collectBatteryData(PerformanceData data) {
        data.batteryTemp = lastBatteryTemp;
        // CPU 温度走独立的 thermal_zone 读取器（v1.8.0）
        data.cpuTemp = CpuTempReader.readCpuTempC();

        boolean isCharging = lastBatteryStatus == BatteryManager.BATTERY_STATUS_CHARGING
                || lastBatteryStatus == BatteryManager.BATTERY_STATUS_FULL;
        data.isCharging = isCharging;

        boolean dualCell = NexusBridge.isDualCellEnabled();

        float currentA = readBatteryCurrentA();
        if (currentA > 0) {
            float ma = currentA * 1000f;
            // 双电芯并联时 current_now 多半只报单颗电芯的电流，故与功率一同翻倍。
            // 注意这里翻的是显示值，传给 PowerTracker 的仍是原始 currentA——
            // 功率在下面单独乘一次倍率，两处各乘一次，不会重复
            if (dualCell) {
                ma *= Constants.Modules.DUAL_CELL_MULTIPLIER;
            }
            data.batteryCurrentMa = isCharging ? ma : -ma;
        }

        float power = powerTracker.resolve(
                currentA,
                readBatteryVoltageV(),
                () -> SysfsReader.direct().readBatteryPowerW());

        if (power > 0) {
            // 倍率作用在 resolve 的结果上，故三条来源（电流×电压 / power_now / 陈旧值）
            // 都会被翻倍，不会因为来源切换而出现忽大忽小
            if (dualCell) {
                power *= Constants.Modules.DUAL_CELL_MULTIPLIER;
            }
            data.batteryPowerW = isCharging ? power : -power;
        }
    }

    /** @return 电压（V），读不到时用兜底值 */
    private float readBatteryVoltageV() {
        float fromBroadcast = lastBatteryVoltageMv / 1000f;
        if (fromBroadcast >= Constants.Battery.VOLTAGE_MIN_V
                && fromBroadcast <= Constants.Battery.VOLTAGE_MAX_V) {
            return fromBroadcast;
        }
        float fromSysfs = SysfsReader.direct().readBatteryVoltageV();
        return fromSysfs > 0 ? fromSysfs : Constants.Battery.VOLTAGE_FALLBACK_V;
    }

    /** @return 电流绝对值（A），读不到返回 0 */
    private float readBatteryCurrentA() {
        if (batteryManager != null) {
            int currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (currentNow != 0 && currentNow != Integer.MIN_VALUE) {
                // ABI 规定 µA，但部分 ROM 实际给 mA；两者有效区间不重叠，故可判定
                float a = SysfsReader.parseScaled(String.valueOf(currentNow),
                        Constants.Battery.CURRENT_DIVISORS,
                        Constants.Battery.CURRENT_MIN_A, Constants.Battery.CURRENT_MAX_A);
                if (a > 0) {
                    return a;
                }
            }
        }
        return SysfsReader.direct().readBatteryCurrentA();
    }

    /**
     * 帧率采集，按用户选择的方案分路。
     *
     * 三个方案：
     * - 自动（默认）：SF → FPSGO → 面板 → Choreographer，跟 v1.5.4 一样。
     * - 高通：面板实测帧率（measured_fps）优先，SF 兜底，不退回 Choreographer。
     *   高通 8 Gen 3 实测这条路是对的。
     * - 联发科：只用应用级来源（SF → FPSGO → GED KPI），SF 优先走 root
     *   dumpsys；不读面板、不退回 Choreographer。读不到就显示 0，
     *   不拿屏幕刷新率冒充帧率。
     */
    private void collectFpsData(PerformanceData data) {
        String pkg = foregroundTracker.getForegroundPackage();
        boolean[] enabled = NexusBridge.readFpsSourceFlags();
        boolean preferRoot = NexusBridge.isSfPreferRoot();

        // Choreographer 兜底由 updateRunnable 处理，故只在这里传达「是否允许」
        data.useChoreographerFallback =
                enabled[Constants.Modules.FpsSource.IDX_VSYNC];

        collectFpsChain(data, pkg, enabled, preferRoot);

        // 诊断关掉时清空标记，监视条就只显示纯数字
        if (!NexusBridge.isFpsDebugEnabled()) {
            data.fpsTag = "";
        }
    }

    /**
     * 按 FpsSource.KEYS 的顺序逐个尝试用户开启的来源。
     *
     * 取代了 v1.6.1 的三条写死链路（自动 / 高通 / 联发科）：那时每个预设背后
     * 是固定顺序，某个来源在某机型上出数慢或不准时，用户只能整条换掉。
     * 现在关掉的来源直接跳过，顺序不变。
     *
     * 顺序即优先级，按「响应速度 + 准确度」排的，不随用户勾选而变：
     * 面板节点给瞬时值最快，SurfaceFlinger 两条各有 1 秒左右的窗口，
     * Choreographer 只是刷新率，永远排最后。
     *
     * 0 不再截断整条链（v1.6.3）：某个来源报 0 有两种含义，画面真的静止，
     * 或者这条路在本机取不到活跃层，两者当场分不开。0 又是合法值，v1.6.2
     * 因此在拿到 0 时就停下——TimeStats 一旦系统性地返回 0，它后面的 FPSGO、
     * GED 永远没机会，用户看到的就是恒定的 0t，换哪个「方案」都一样。
     * 现在记下这个 0 继续往下试，后面有谁给出正数就用它；全都没有才落回 0。
     * 静止画面的显示不受影响（所有来源都会是 0）。
     */
    private void collectFpsChain(PerformanceData data, String pkg,
                                 boolean[] enabled, boolean preferRoot) {
        // 曾有来源报 0 时记下它的标记：全链无正数时用它，
        // 这样静止画面显示的仍是「谁报的 0」而不是「全部失败」
        String zeroTag = null;

        // 1. 面板实测帧率（高通 measured_fps）
        // v1.8.4 恢复 v1.7.6 的双通道：先直读（system uid 对部分节点开放，
        // 联发科 GED 与部分高通 node 可读），再 root（高通 vendor_sysfs_* 域
        // 对 system uid 关闭，只有 su 能读）。v1.7.6 实测此顺序在澎湃/ColorOS
        // 的骁龙 8 Gen 3 / 8 Elite 上面板 p 正常。
        if (enabled[Constants.Modules.FpsSource.IDX_PANEL]) {
            float fps = SysfsReader.direct().readFps();
            if (fps > 0) {
                data.fps = fps;
                data.fpsTag = Constants.Fps.TAG_PANEL;
                return;
            }
            fps = SysfsReader.root().readFps();
            if (fps > 0) {
                data.fps = fps;
                data.fpsTag = Constants.Fps.TAG_PANEL;
                return;
            }
            // 面板节点读不到时返回的也是 0，无法与「静止」区分；
            // 但它不属于应用级来源，不占 zeroTag，继续往下走
        }

        // 2. SurfaceFlinger --latency（仅 Android 13 及更早有数据）
        if (enabled[Constants.Modules.FpsSource.IDX_SF_LATENCY]) {
            float fps = sfFpsReader.read(pkg, preferRoot);
            if (fps > 0) {
                data.fps = fps;
                data.fpsFromApp = true;
                data.fpsTag = sfTag();
                return;
            }
            if (fps == 0f && zeroTag == null) {
                zeroTag = sfTag();
            }
        }

        // 3. SurfaceFlinger TimeStats（Android 14+ 的正解）
        if (enabled[Constants.Modules.FpsSource.IDX_SF_TIMESTATS]) {
            float fps = timeStatsReader.read(pkg, preferRoot);
            if (fps > 0) {
                data.fps = fps;
                data.fpsFromApp = true;
                data.fpsTag = timeStatsTag();
                return;
            }
            if (fps == 0f && zeroTag == null) {
                zeroTag = timeStatsTag();
            }
        }

        // 4. 联发科 FPSGO
        if (enabled[Constants.Modules.FpsSource.IDX_FPSGO]) {
            float fps = fpsgoReader.read(pkg);
            if (fps > 0) {
                data.fps = fps;
                data.fpsFromApp = true;
                data.fpsTag = Constants.Fps.TAG_FPSGO;
                return;
            }
            if (fps == 0f && zeroTag == null) {
                zeroTag = Constants.Fps.TAG_FPSGO;
            }
        }

        // 5. 联发科 GED KPI
        if (enabled[Constants.Modules.FpsSource.IDX_GED]) {
            // 这里固定试 root：GED 节点在多数 ROM 上对 system uid 也不可读，
            // 而「SurfaceFlinger 走 root」那个开关只管 SF 两条路。读取前会先用
            // 内建 read 批量筛掉不存在的节点，故不存在时零开销
            float fps = gedKpiReader.read(pkg, true);
            if (fps > 0) {
                data.fps = fps;
                data.fpsFromApp = true;
                data.fpsTag = Constants.Fps.TAG_GED;
                return;
            }
            if (fps == 0f && zeroTag == null) {
                zeroTag = Constants.Fps.TAG_GED;
            }
        }

        // 6. 有来源报过 0：画面确实静止，按它的标记显示 0
        if (zeroTag != null) {
            data.fps = 0f;
            data.fpsFromApp = true;
            data.fpsTag = zeroTag;
            return;
        }

        // 7. 全部取不到数。Choreographer 开着就交给 updateRunnable 填数，
        // 关掉则沿用上一次的有效值（若有），再不行才显示 0。
        //
        // 沿用旧值是 v1.6.5 加的：TimeStats 每次切应用要重建基线（1~2 拍），
        // SurfaceFlinger 换 layer 也有空窗，这些间隙里全链确实无数可取。
        // 每次都跳到 0 会让数值闪断，而这些间隙是取数机制的正常组成部分，
        // 不是故障。诊断标记仍如实报出断点，故不会掩盖真正的失败。
        if (data.useChoreographerFallback) {
            data.fps = Constants.Fps.READ_FAILED;
            data.fpsTag = Constants.Fps.TAG_VSYNC + fpsFailureStage();
        } else {
            data.fps = Constants.Fps.READ_FAILED;
            data.fpsTag = Constants.Fps.TAG_NONE + fpsFailureStage();
        }
    }

    /**
     * 全链失败时报告是哪一步出的问题。
     *
     * TimeStats 是新机型的主力路径，它的失败原因比 --latency 的有用
     * （8 = 连 TimeStats 都没数据，9 = 还在等第二次采样），所以优先报它，
     * 它没话说时才退回 --latency 的阶段编号。
     */
    private String fpsFailureStage() {
        String stage = timeStatsReader.lastFailure();
        return stage.isEmpty() ? sfFpsReader.lastFailure() : stage;
    }

    /** SF 取数成功时的诊断标记：区分 Binder / root / 不带层名的兜底 */
    private String sfTag() {
        if (sfFpsReader.lastViaDisplay()) {
            return Constants.Fps.TAG_SF_DISPLAY;
        }
        return sfFpsReader.lastViaRoot()
                ? Constants.Fps.TAG_SF_ROOT : Constants.Fps.TAG_SF_BINDER;
    }

    /**
     * TimeStats 取数成功时的标记。
     *
     * 走 root 时会附加 r（显示成 tr）：Binder 通道不创建进程，root 每次取数
     * 要 fork 一个 dumpsys。让用户看到这个区别，才知道「SurfaceFlinger 走 root」
     * 这个开关有没有必要开着。
     */
    private String timeStatsTag() {
        return timeStatsReader.lastViaRoot()
                ? Constants.Fps.TAG_SF_TIMESTATS + Constants.Fps.TAG_SF_ROOT
                : Constants.Fps.TAG_SF_TIMESTATS;
    }

    private String readFirstLine(String path) {
        File f = new File(path);
        if (!f.exists()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private long parseMeminfoLine(String line) {
        try {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) {
                return Long.parseLong(parts[1]);
            }
        } catch (Exception e) {}
        return 0;
    }
}
