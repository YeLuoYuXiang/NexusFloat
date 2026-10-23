package com.jj.nexusfloat.frame;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.jj.nexusfloat.bridge.NexusBridge;
import com.jj.nexusfloat.collector.CpuTempReader;
import com.jj.nexusfloat.collector.FpsgoReader;
import com.jj.nexusfloat.collector.GedKpiReader;
import com.jj.nexusfloat.collector.GpuRootReader;
import com.jj.nexusfloat.collector.PowerTracker;
import com.jj.nexusfloat.collector.SurfaceFlingerFpsReader;
import com.jj.nexusfloat.collector.SysfsReader;
import com.jj.nexusfloat.collector.TimeStatsFpsReader;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.stats.ForegroundAppReader;
import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.RootShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 帧率记录的采样循环。
 *
 * 用户在悬浮球上点了「开始」才跑，1 秒一个点：帧率曲线要能看出「什么时候掉的帧」，
 * 比统计的 15 秒密 15 倍，但只在记录期间存在，不是常驻开销。
 *
 * 取数全部复用现成的读取器，不另写第二套：
 * - 帧率：面板 measured_fps → SurfaceFlinger latency → TimeStats → FPSGO → GED，
 *   按用户在「高级」页开的开关与顺序走（读的是同一份 prefs）。**不退回
 *   Choreographer**——那个数的是屏幕刷新率，拿它记游戏帧率等于记假数据。
 * - CPU 占用：/proc/stat 前后两拍做差，口径与顶部悬浮栏完全一致
 *   （不再自己单独一套解析，详见 readCpuUsage）。
 * - CPU 频率：每个 cpufreq policy 读一个 scaling_cur_freq，得各簇的值
 *   （policy 目录不可用时退回逐核目录）；曲线只画最高的那个簇。
 * - GPU 频率/占用：root 直读（GpuRootReader），本进程读不到再试 direct。
 * - 温度：CpuTempReader（CPU/GPU 各自的 thermal zone）。
 * - 内存：ActivityManager.MemoryInfo。
 * - 功率：电流 × 电压，走 PowerTracker 的同一套突变守卫，与监视条/统计页对得上。
 *
 * 帧率「读不到」和「画面静止」是两回事，落库时严格区分（fps=-1 表示没读到，
 * 0 表示真的没出帧）——这是修充电功率曲线时踩过的坑：把读不到当成
 * 0 落库，曲线就会出现假的归零。
 */
public final class FrameSampler {

    private static final String TAG = "FrameSampler";

    /** 采样回调。onTick 跑在采样线程上，别在里面碰 UI */
    public interface Listener {
        void onTick(FrameRecordStore.Sample sample);

        /**
         * 记录状态变化（开始 / 结束 / 丢弃）。跑在哪个线程不确定，实现方自己切。
         * 悬浮球靠它把颜色翻回去——丢弃/结束后不会再有 onTick，不补这条的话
         * 球会一直红着。
         */
        default void onRecordingChanged(boolean recording) {
        }
    }

    private final Context context;
    private final FrameRecordStore store;
    private final PowerTracker powerTracker = new PowerTracker();
    private final SurfaceFlingerFpsReader sfReader = new SurfaceFlingerFpsReader();
    private final TimeStatsFpsReader timeStatsReader = new TimeStatsFpsReader();
    private final FpsgoReader fpsgoReader = new FpsgoReader();
    private final GedKpiReader gedReader = new GedKpiReader();
    private final ActivityManager activityManager;
    private final BatteryManager batteryManager;
    private final List<Listener> listeners = new ArrayList<>();

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;
    private volatile boolean recording;

    /** 正在写的记录行 id；<0 表示没在记录 */
    private long recordId = -1L;
    /** 这条记录目前的整体前台应用（采样点最多的那个才算数，收尾时按多数重定） */
    private String recordPkg;
    private String recordLabel;

    /** 攒批缓冲：攒够 FLUSH_EVERY_TICKS 条才写库，省掉每秒一次 fsync */
    private final List<FrameRecordStore.Sample> pending = new ArrayList<>();

    /**
     * 上一拍 /proc/stat 的 (idle, total)，算 CPU 占用用。
     *
     * 下标是「CPU 槽位」：[0] 是整机行，[N+1] 是 cpuN——和顶部悬浮栏
     * [com.jj.nexusfloat.collector.PerformanceCollector] 一个口径。
     */
    private static final int CPU_SLOTS = 17;
    private final long[][] lastCpuTimes = new long[CPU_SLOTS][2];
    /** 上一次的读数：两拍之间没有推进时沿用它，不写假的 0 */
    private final float[] lastCpuUsage = new float[CPU_SLOTS];
    private float lastCpuTotalUsage = 0f;
    private long lastCpuReadTs;
    /** CPU 读数有没有出问题（读不到 / 两拍没推进），连续几拍就考虑换 root 通道 */
    private int cpuTrouble;
    private boolean cpuViaRoot;
    /** root shell 试过不行就别再试（没授权就是没授权，每拍起一次 su 纯属浪费） */
    private boolean rootReadUnusable;

    /** 缓存的前台包名，FG_REFRESH_MS 才重查一次 */
    private String fgPkg;
    private long lastFgQueryTs;

    /** 电池广播缓存 */
    private volatile Intent batteryIntent;
    private int rootMisses;

    /**
     * 面板帧率连续失败的拍数，以及跳过计数的剩余拍数。
     *
     * 面板那条路读不到时要展开全部候选路径扫两遍（direct 一次、root 一次），
     * 在模块 App 进程里没有 root 或者节点不可读时，单拍能耗好几秒。而采样周期
     * 只有 1 秒，采样线程会被拖死——表现就是球上一直是 --，点按也没反应。
     * 连续失败就跳过若干拍，只保留低频复探。
     */
    private int panelMissStreak;
    private int panelSkipLeft;
    /** 面板连续失败多少拍后开始跳过 */
    private static final int PANEL_MISS_LIMIT = 2;
    /** 跳过多少拍后复探一次 */
    private static final int PANEL_SKIP_TICKS = 30;

    private static volatile FrameSampler sInstance;

    /** 进程级单例：悬浮球、前台服务、记录页三处共用同一个状态 */
    public static FrameSampler get(Context context) {
        FrameSampler inst = sInstance;
        if (inst == null) {
            synchronized (FrameSampler.class) {
                inst = sInstance;
                if (inst == null) {
                    sInstance = inst = new FrameSampler(context.getApplicationContext());
                }
            }
        }
        return inst;
    }

    private FrameSampler(Context context) {
        this.context = context.getApplicationContext();
        this.store = FrameRecordStore.get(this.context);
        this.activityManager =
                (ActivityManager) this.context.getSystemService(Context.ACTIVITY_SERVICE);
        this.batteryManager =
                (BatteryManager) this.context.getSystemService(Context.BATTERY_SERVICE);
    }

    // ======================= 生命周期 =======================

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        registerBattery();
        ensureThread();
        handler.removeCallbacks(tickRunnable);
        handler.post(tickRunnable);
        LogUtils.i(TAG + " started");
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        unregisterBattery();
        if (handler != null) {
            handler.removeCallbacks(tickRunnable);
        }
        // 服务退场前的最后收尾，这里同步做：再不落库就没机会了。
        // 量很小（至多 5 个待落库点 + 一条汇总 UPDATE），主线程等这一下可接受
        if (recording) {
            recording = false;
        }
        long id = recordId;
        if (id > 0) {
            // 先落库再清 id：flush 按 recordId 归属采样点，先清掉就把尾巴全丢了
            flush();
            recordId = -1L;
            store.finishRecord(id, System.currentTimeMillis(), readBatteryPct());
            LogUtils.i(TAG + " recording finish on stop id=" + id);
        } else {
            flush();
        }
        recordPkg = null;
        recordLabel = null;
        LogUtils.i(TAG + " stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isRecording() {
        return recording;
    }

    /** 正在记录的记录行 id；没在记录返回 -1 */
    public long currentRecordId() {
        return recordId;
    }

    /** 正在记录的应用名；没在记录返回 null */
    public String currentLabel() {
        return recording ? recordLabel : null;
    }

    /**
     * 丢弃当前记录：立刻停采样、清掉归属，**不做收尾**。
     *
     * 给「删除正在进行的那条记录」用——记录都要删了，落库和算汇总纯属浪费，
     * 还会在删除之后又写回几个孤儿采样点。调用方随后自己把这条记录删掉。
     *
     * @return 被丢弃的记录 id，没在记录时返回 -1
     */
    public long discardRecording() {
        long id;
        synchronized (this) {
            id = recordId;
            recordId = -1L;
            if (!recording) {
                return id;
            }
            recording = false;
        }
        synchronized (pending) {
            pending.clear();
        }
        for (Listener l : listeners) {
            try {
                l.onRecordingChanged(false);
            } catch (Throwable t) {
                LogUtils.w(TAG + " listener failed", t);
            }
        }
        LogUtils.i(TAG + " recording discarded id=" + id);
        return id;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void ensureThread() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        thread = new HandlerThread(Constants.ThreadName.FRAME_SAMPLER);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    // ======================= 记录控制 =======================

    /**
     * 开始一条记录。
     *
     * 由悬浮球的点按调，跑在**主线程**上——所以这里只翻状态位，重活（建记录行、
     * 解析前台应用、读电池）全部 post 到采样线程去做。主线程上跑一次 root dumpsys
     * 加一次 SQLite 插入，游戏里点了悬浮球立刻 ANR，这种手感问题比功能缺失更糟。
     *
     * 状态位先翻：悬浮球下一拍（最多 1 秒）就会收到 onTick 把颜色刷成红色；
     * tick 里看到 recordId 还没建好会直接跳过，不会写出没有归属的采样点。
     */
    public void beginRecording() {
        synchronized (this) {
            if (recording) {
                return;
            }
            recording = true;
        }
        if (handler == null) {
            ensureThread();
        }
        handler.post(this::beginRecordingInner);
        start();
        for (Listener l : listeners) {
            try {
                l.onRecordingChanged(true);
            } catch (Throwable t) {
                LogUtils.w(TAG + " listener failed", t);
            }
        }
    }

    private void beginRecordingInner() {
        long now = System.currentTimeMillis();
        String pkg = resolveForegroundPackage(true);
        recordPkg = pkg;
        recordLabel = resolveLabel(pkg);
        long id = store.beginRecord(now, pkg, recordLabel, readBatteryPct());
        synchronized (this) {
            if (id <= 0) {
                // 建行失败（磁盘满、库坏了）：退回未记录状态，别让悬浮球红着骗人
                LogUtils.w(TAG + " beginRecord failed, not recording");
                recording = false;
                return;
            }
            recordId = id;
            // CPU 基线作废：上一拍可能是很久以前（上次记录留下的），直接做差会把
            // 中间那一大段也算进这一拍的占用里。清掉之后第一拍算出来的是
            // 「开机至今」的平均值，跟顶部悬浮栏刚起来那一拍一样，不是 0
            resetCpuBaseline();
            lastCpuReadTs = 0L;
        }
        LogUtils.i(TAG + " recording begin id=" + id + " pkg=" + pkg);
    }

    /**
     * 结束当前记录（收尾、落库、清状态）。
     *
     * 同样只在主线程翻状态位、把落库挪到采样线程：落库前线程里排着的那些 tick
     * 会因为 recording=false 直接返回，不会写出多余的点。没在记录时是空操作。
     */
    public void finishRecording() {
        synchronized (this) {
            if (!recording) {
                return;
            }
            recording = false;
        }
        if (handler == null) {
            return;
        }
        handler.post(() -> finishRecordingInner(System.currentTimeMillis()));
        for (Listener l : listeners) {
            try {
                l.onRecordingChanged(false);
            } catch (Throwable t) {
                LogUtils.w(TAG + " listener failed", t);
            }
        }
    }

    private void finishRecordingInner(long now) {
        long id;
        synchronized (this) {
            id = recordId;
            if (id <= 0) {
                return;
            }
        }
        // 先落库再清 id：flush 按 recordId 归属采样点，先清掉就把尾巴全丢了。
        // recordId 的清掉放在 flush 之后、finishRecord 之前，中间不会再有 tick
        // 插进来（本方法就跑在采样线程上，tick 也在这个线程上排队）
        flush();
        synchronized (this) {
            recordId = -1L;
        }
        store.finishRecord(id, now, readBatteryPct());
        LogUtils.i(TAG + " recording finish id=" + id);
        recordPkg = null;
        recordLabel = null;
    }

    // ======================= 采样循环 =======================

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                tick();
            } catch (Throwable t) {
                LogUtils.w(TAG + " tick failed", t);
            }
            if (running) {
                handler.postDelayed(this, Constants.Frame.SAMPLE_INTERVAL_MS);
            }
        }
    };

    private void tick() {
        // 没在记录时循环也空转着等（悬浮球还没点）：不采不写，只是别让线程退掉。
        // start() 本来就只在记录时被调，这里只是双保险
        if (!recording || recordId <= 0) {
            return;
        }

        FrameRecordStore.Sample s = new FrameRecordStore.Sample();
        s.ts = System.currentTimeMillis();

        // ---- 前台应用（低频刷新，给 FPSGO/GED 对准进程用）----
        refreshForegroundIfNeeded();

        // ---- 帧率 ----
        resolveFps(s);

        // ---- CPU ----
        readCpuUsage(s);
        s.cpuFreqs = readCpuFreqs();

        // ---- GPU ----
        s.gpuFreqMhz = readGpuFreq();
        s.gpuUsage = readGpuUsage();

        // ---- 温度 ----
        s.cpuTemp = CpuTempReader.readCpuTempC();
        s.gpuTemp = CpuTempReader.readGpuTempC();

        // ---- 内存 ----
        readMemory(s);

        // ---- 电池 / 功率 ----
        readPower(s);

        synchronized (pending) {
            pending.add(s);
        }
        if (pending.size() >= Constants.Frame.FLUSH_EVERY_TICKS) {
            flush();
        }

        for (Listener l : listeners) {
            try {
                l.onTick(s);
            } catch (Throwable t) {
                LogUtils.w(TAG + " listener failed", t);
            }
        }
    }

    private void flush() {
        List<FrameRecordStore.Sample> batch;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        store.insertSamples(recordId, batch);
    }


    // ======================= 帧率 =======================

    /**
     * 按「高级」页用户开的来源逐个试，顺序即优先级（与监视条同一套）。
     *
     * 写进 Sample 的取值只有三种含义：
     * - &gt;0：真实测到的帧率
     * - =0：应用级来源明确报了「没出帧」（画面静止），是真实结果
     * - =-1（READ_FAILED）：所有来源都没读到，曲线里画成断点，不当成 0
     */
    private void resolveFps(FrameRecordStore.Sample s) {
        boolean[] enabled = NexusBridge.readFpsSourceFlags();
        boolean preferRoot = NexusBridge.isSfPreferRoot();
        String pkg = fgPkg;

        if (enabled[Constants.Modules.FpsSource.IDX_PANEL]) {
            // 熔断：连续读不到就先跳过一阵，别让全表扫描把采样线程拖死。
            // 复探那一拍照常扫，能读到就自动恢复
            boolean tryPanel = true;
            if (panelSkipLeft > 0) {
                panelSkipLeft--;
                tryPanel = false;
            }
            if (tryPanel) {
                float fps = SysfsReader.direct().readFps();
                if (fps <= 0f) {
                    fps = SysfsReader.root().readFps();
                }
                if (fps > 0f) {
                    panelMissStreak = 0;
                    panelSkipLeft = 0;
                    s.fps = fps;
                    s.fpsSrc = Constants.Fps.TAG_PANEL;
                    return;
                }
                // 读不到：累积失败次数，到阈值就转入跳过
                if (++panelMissStreak >= PANEL_MISS_LIMIT) {
                    panelMissStreak = 0;
                    panelSkipLeft = PANEL_SKIP_TICKS;
                }
            }
        }
        if (enabled[Constants.Modules.FpsSource.IDX_SF_LATENCY]) {
            float fps = sfReader.read(pkg, preferRoot);
            if (fps > 0f) {
                s.fps = fps;
                s.fpsSrc = sfReader.lastViaRoot()
                        ? Constants.Fps.TAG_SF_ROOT : Constants.Fps.TAG_SF_BINDER;
                return;
            }
            if (fps == 0f) {
                s.fps = 0f;
                s.fpsSrc = sfReader.lastViaRoot()
                        ? Constants.Fps.TAG_SF_ROOT : Constants.Fps.TAG_SF_BINDER;
                // 0 是应用级来源给的「没出帧」，先记着，后面有正数再覆盖
            }
        }
        if (enabled[Constants.Modules.FpsSource.IDX_SF_TIMESTATS]) {
            float fps = timeStatsReader.read(pkg, preferRoot);
            if (fps > 0f) {
                s.fps = fps;
                s.fpsSrc = Constants.Fps.TAG_SF_TIMESTATS;
                return;
            }
            if (fps == 0f && s.fps == 0f) {
                s.fpsSrc = Constants.Fps.TAG_SF_TIMESTATS;
            }
        }
        if (enabled[Constants.Modules.FpsSource.IDX_FPSGO]) {
            float fps = fpsgoReader.read(pkg);
            if (fps > 0f) {
                s.fps = fps;
                s.fpsSrc = Constants.Fps.TAG_FPSGO;
                return;
            }
            if (fps == 0f && s.fps == 0f) {
                s.fpsSrc = Constants.Fps.TAG_FPSGO;
            }
        }
        if (enabled[Constants.Modules.FpsSource.IDX_GED]) {
            float fps = gedReader.read(pkg, preferRoot);
            if (fps > 0f) {
                s.fps = fps;
                s.fpsSrc = Constants.Fps.TAG_GED;
                return;
            }
            if (fps == 0f && s.fps == 0f) {
                s.fpsSrc = Constants.Fps.TAG_GED;
            }
        }
        if (s.fps == 0f && s.fpsSrc.isEmpty()) {
        // 诊断：全链失败时把关键上下文打出来，方便判断卡在哪一环
        LogUtils.w(TAG + " fps chain failed: pkg=" + pkg
                + " panelSkip=" + panelSkipLeft
                + " enabled=" + java.util.Arrays.toString(enabled));
            // 全链没人给出 0 也没人给出正数：这条路在本机确实读不到
            s.fps = Constants.Fps.READ_FAILED;
        }
    }

    // ======================= CPU =======================

    /**
     * /proc/stat 做差算整机与各核占用，**照抄顶部长悬浮栏的口径**。
     *
     * 帧记录跑在模块 App 自己的进程里（untrusted_app 域），有些机器在这里连
     * /proc/stat 都打不开——而监视条跑在 SystemUI（system 域）不受这个限制，
     * 于是看起来就是「悬浮栏有数、帧记录恒 0」。既然要看的是同一个量，这里直接用
     * 同一套做法，读不到时再换成 root 读同一份文件：
     *
     * - **逐行容错**：单行解析失败只跳过这一行，不再拖垮整机行；
     * - **按 CPU 号落位**：整机行认 "cpu"，核行认 "cpuN" 并按 N 归位，行序无关；
     * - **不写假 0**：两拍之间没有推进（进程被冻结后刚恢复、或者同一毫秒连读两拍）
     *   时沿用上一次的读数；整机行都没读到时保持上一次的值，而不是落 0；
     * - **基线过期就重来**：隔了 3 秒以上没读（新一次记录、进程被冻结过），把基线
     *   清零再算。这一拍得到的是「开机至今的平均占用」，跟悬浮栏刚启动那一拍一样，
     *   是个正常的非零数，之后每一拍都是相邻两拍的差值；
     * - **读不出就换 root 通道**：本地连着几拍落空就自动切到 su 读同一份文件，
     *   见 [maybeFallBackToRoot]。这条路径已经在真机上验证过：本地恒报「读不到」、
     *   切 root 之后占用正常出数。
     */
    private void readCpuUsage(FrameRecordStore.Sample s) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCpuReadTs > Constants.Frame.CPU_BASELINE_STALE_MS) {
            resetCpuBaseline();
        }
        lastCpuReadTs = now;

        CpuRow[] rows = cpuViaRoot ? readCpuStatViaRoot() : readCpuStatRows();
        if (rows == null || rows[0] == null) {
            cpuTrouble++;
            if (maybeFallBackToRoot()) {
                rows = readCpuStatViaRoot();
            }
        }
        if (rows == null || rows[0] == null) {
            // 两条路都没读到：沿用上一次的读数，不写假的 0
            s.cpuTotal = lastCpuTotalUsage;
            return;
        }

        // 先看这一拍相对上一拍有没有推进：usageOf 会把基线推到这一拍，
        // 之后再取差值就永远是 0 了
        long dTotal = rows[0].total - lastCpuTimes[0][1];
        if (dTotal <= 0) {
            cpuTrouble++;
            if (maybeFallBackToRoot()) {
                CpuRow[] viaRoot = readCpuStatViaRoot();
                if (viaRoot != null && viaRoot[0] != null) {
                    rows = viaRoot;
                }
            }
        } else {
            cpuTrouble = 0;
        }

        s.cpuTotal = usageOf(0, rows[0]);
        lastCpuTotalUsage = s.cpuTotal;

        List<Float> cores = new ArrayList<>();
        for (int slot = 1; slot < CPU_SLOTS; slot++) {
            if (rows[slot] != null) {
                cores.add(usageOf(slot, rows[slot]));
            }
        }
        if (!cores.isEmpty()) {
            float[] out = new float[cores.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = cores.get(i);
            }
            s.cpuCores = out;
        }
    }

    /**
     * 本地一直读不到%/没推进时，试着改走 root shell。
     *
     * @return true 表示已经切到 root 并且这一拍应该重新读一次
     */
    private boolean maybeFallBackToRoot() {
        if (cpuViaRoot || rootReadUnusable
                || cpuTrouble < Constants.Frame.CPU_ROOT_FALLBACK_TROUBLES) {
            return false;
        }
        CpuRow[] test = readCpuStatViaRoot();
        if (test == null || test[0] == null) {
            return false;
        }
        cpuViaRoot = true;
        cpuTrouble = 0;
        // 换通道等于换了一个「读数来源」，旧基线（可能来自另一条路、也可能是几拍前
        // 的残留）必须作废：清零之后下一拍算的是「开机至今」的均值，是个正常值
        resetCpuBaseline();
        LogUtils.i(TAG + " cpuStat fallback -> root shell");
        return true;
    }

    /** /proc/stat 里一行 cpu 的累计时间（单位 jiffy）；slot=0 是整机，>0 是 cpu(slot-1) */
    private static final class CpuRow {
        int slot;
        long idle;
        long total;
    }

    /** 把 CPU 基线和上一次的读数清掉 */
    private void resetCpuBaseline() {
        for (int i = 0; i < CPU_SLOTS; i++) {
            lastCpuTimes[i][0] = 0L;
            lastCpuTimes[i][1] = 0L;
            lastCpuUsage[i] = 0f;
        }
        lastCpuTotalUsage = 0f;
    }

    /**
     * 某个槽位这一拍的占用（%），顺手把基线和「上一次读数」往前推。
     *
     * @return 0–100 的占用；两拍之间没有推进时返回上一次的结果
     */
    private float usageOf(int slot, CpuRow row) {
        long dIdle = row.idle - lastCpuTimes[slot][0];
        long dTotal = row.total - lastCpuTimes[slot][1];
        lastCpuTimes[slot][0] = row.idle;
        lastCpuTimes[slot][1] = row.total;
        if (dTotal <= 0) {
            return lastCpuUsage[slot];
        }
        float usage = (dTotal - dIdle) * 100f / dTotal;
        lastCpuUsage[slot] = usage;
        return usage;
    }

    /**
     * 读 /proc/stat，按 CPU 号落位：[0]=整机、[N+1]=cpuN；打不开返回 null。
     *
     * 逐行 try/catch 是这一版的关键：认不出来的行（过长、过短、核名不是 cpuN）
     * 只丢它自己，整机行和其它核行照样能用。
     */
    private CpuRow[] readCpuStatRows() {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(Constants.Proc.STAT))) {
            String line;
            boolean inCpuBlock = false;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("cpu")) {
                    // 已经进过 cpu 段说明这一段读完了，后面的 intr/ctxt 不用再看
                    if (inCpuBlock) {
                        break;
                    }
                    continue;
                }
                inCpuBlock = true;
                lines.add(line);
                if (lines.size() >= CPU_SLOTS) {
                    break;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return parseCpuStat(lines);
    }

    /**
     * root shell 读同一份文件。
     *
     * App 进程（untrusted_app 域）被 SELinux 拦着读不到的东西，走 su 是通的——
     * 顶部监视条跑在 system 域所以不受影响，这正是「悬浮栏有数、帧记录恒 0」的
     * 样子。用 [RootShell] 的常驻 shell，每拍一条命令，不额外 fork 进程去拉起 su。
     */
    private CpuRow[] readCpuStatViaRoot() {
        if (rootReadUnusable) {
            return null;
        }
        List<String> lines = RootShell.get().execLines(
                "cat " + Constants.Proc.STAT, CPU_SLOTS, 1200L);
        if (lines == null || lines.isEmpty()) {
            // shell 起不来 / 没有授权：别每拍都再试一遍
            rootReadUnusable = true;
            return null;
        }
        CpuRow[] rows = parseCpuStat(lines);
        if (rows[0] == null) {
            // 输出拿到了却认不出整机行，再试也没用
            rootReadUnusable = true;
        }
        return rows;
    }

    /**
     * 解析 cpu 行：整机行落 [0]，cpuN 落 [N+1]。
     *
     * 注意别把整机行当成「核号非法」甩掉——slot=0 是合法的（它就是整机），
     * 只有 cpu 号超过开槽上限才是真的放不下。
     */
    private CpuRow[] parseCpuStat(List<String> lines) {
        CpuRow[] rows = new CpuRow[CPU_SLOTS];
        boolean inCpuBlock = false;
        for (String raw : lines) {
            if (!raw.startsWith("cpu")) {
                if (inCpuBlock) {
                    break;
                }
                continue;
            }
            inCpuBlock = true;
            CpuRow parsed = parseCpuLine(raw);
            if (parsed == null) {
                continue;
            }
            if (parsed.slot >= CPU_SLOTS) {
                continue;
            }
            rows[parsed.slot] = parsed;
        }
        return rows;
    }

    /** 解析一行 cpu 记录；认不出来返回 null（slot=0 是整机，是合法值） */
    private CpuRow parseCpuLine(String line) {
        try {
            String[] t = line.trim().split("\\s+");
            if (t.length < 5) {
                return null;
            }
            int slot;
            if (t[0].equals("cpu")) {
                slot = 0;
            } else {
                slot = Integer.parseInt(t[0].substring(3)) + 1;
            }
            long user = Long.parseLong(t[1]);
            long nice = Long.parseLong(t[2]);
            long system = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long softirq = t.length > 7 ? Long.parseLong(t[7]) : 0;
            CpuRow r = new CpuRow();
            r.slot = slot;
            r.idle = idle + iowait;
            r.total = user + nice + system + idle + iowait + irq + softirq;
            return r;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * CPU 频率采样（MHz）。
     *
     * 先按 cpufreq policy 读一遍（一个 policy 一个簇），policy 目录读不到时退回逐核
     * 目录 —— 和顶部悬浮栏 [com.jj.nexusfloat.collector.PerformanceCollector] 的
     * collectCpuFreqMax 两条路完全一致。
     *
     * 详情曲线只画一条：取这一拍里最高的那个簇频率（也就是悬浮栏
     * CPU 频率那个括号里靠右的数）。分簇画多条的话，看帧率曲线的人还得先分清
     * 「哪条是大核」，而真正和掉帧相关的是当前跑得最高的那个簇。
     *
     * @return 各 policy 的频率；读不到返回 null
     */
    private int[] readCpuFreqs() {
        List<Integer> out = new ArrayList<>();
        File dir = new File(Constants.Cpu.CPUFREQ_DIR);
        File[] policies = dir.listFiles((d, name) -> name.startsWith("policy"));
        if (policies != null && policies.length > 0) {
            // policy 目录名不带数字补零，按数字排才有稳定的顺序
            java.util.Arrays.sort(policies, (a, b) -> {
                int na = parsePolicyNo(a.getName());
                int nb = parsePolicyNo(b.getName());
                return Integer.compare(na, nb);
            });
            for (File p : policies) {
                int mhz = readKhzFile(new File(p, Constants.Cpu.SCALING_CUR_FREQ).getPath());
                if (mhz > 0) {
                    out.add(mhz);
                }
            }
        }
        if (out.isEmpty()) {
            // 没有 policy 目录的机型（部分老内核）：逐个核目录读
            File cpuDir = new File(Constants.Cpu.DEVICE_DIR);
            File[] cpus = cpuDir.listFiles((d, name) -> name.matches("cpu\\d+"));
            if (cpus != null) {
                for (File cpu : cpus) {
                    int mhz = readKhzFile(
                            cpu.getAbsolutePath() + "/cpufreq/" + Constants.Cpu.SCALING_CUR_FREQ);
                    if (mhz > 0) {
                        out.add(mhz);
                    }
                }
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private static int parsePolicyNo(String name) {
        try {
            return Integer.parseInt(name.substring("policy".length()));
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    private static int readKhzFile(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            if (line == null) {
                return 0;
            }
            long khz = Long.parseLong(line.trim());
            return khz > 0 ? (int) (khz / Constants.Cpu.KHZ_TO_MHZ) : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    // ======================= GPU / 内存 / 电池 =======================

    private int readGpuFreq() {
        int mhz = GpuRootReader.readFreqMhz();
        if (mhz <= 0) {
            mhz = SysfsReader.direct().readGpuFreqMhz();
        }
        return Math.max(mhz, 0);
    }

    private int readGpuUsage() {
        int pct = GpuRootReader.readUsagePercent();
        if (pct < 0) {
            pct = SysfsReader.direct().readGpuUsagePercent();
        }
        return pct;
    }

    private void readMemory(FrameRecordStore.Sample s) {
        if (activityManager == null) {
            return;
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(mi);
        if (mi.totalMem <= 0) {
            return;
        }
        s.ramTotalMb = (int) (mi.totalMem / 1024L / 1024L);
        s.ramUsedMb = (int) ((mi.totalMem - mi.availMem) / 1024L / 1024L);
    }

    private void readPower(FrameRecordStore.Sample s) {
        Intent intent = batteryIntent;
        int rawMv = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) : 0;
        float voltageV = rawMv / 1000f;
        if (voltageV < Constants.Battery.VOLTAGE_MIN_V
                || voltageV > Constants.Battery.VOLTAGE_MAX_V) {
            voltageV = SysfsReader.direct().readBatteryVoltageV();
        }
        s.batteryPct = readBatteryPct();

        float currentA = readCurrentA();
        float power = powerTracker.resolve(currentA, voltageV,
                () -> SysfsReader.direct().readBatteryPowerW());
        boolean charging = isCharging();
        s.powerW = charging ? power : -power;
    }

    private float readCurrentA() {
        if (batteryManager != null) {
            try {
                int raw = batteryManager.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (raw != 0 && raw != Integer.MIN_VALUE) {
                    float a = SysfsReader.parseScaled(String.valueOf(raw),
                            Constants.Battery.CURRENT_DIVISORS,
                            Constants.Battery.CURRENT_MIN_A,
                            Constants.Battery.CURRENT_MAX_A);
                    if (a > 0f) {
                        return a;
                    }
                }
            } catch (Throwable t) {
                LogUtils.w(TAG + " getIntProperty failed", t);
            }
        }
        float direct = SysfsReader.direct().readBatteryCurrentA();
        if (direct > 0f) {
            return direct;
        }
        // root 直读收尾。连续失败就不再试：没 root 的机器每拍起一次 su 会把采样拖死
        if (rootMisses < 3) {
            float viaRoot = SysfsReader.root().readBatteryCurrentA();
            if (viaRoot > 0f) {
                rootMisses = 0;
                return viaRoot;
            }
            rootMisses++;
        }
        return 0f;
    }

    private boolean isCharging() {
        Intent intent = batteryIntent;
        int status = intent != null
                ? intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private int readBatteryPct() {
        Intent intent = batteryIntent;
        if (intent == null) {
            return -1;
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
    }

    private void registerBattery() {
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            context.registerReceiver(batteryReceiver, filter);
        } catch (Throwable t) {
            LogUtils.w(TAG + " registerReceiver failed", t);
        }
    }

    private void unregisterBattery() {
        try {
            context.unregisterReceiver(batteryReceiver);
        } catch (Throwable ignored) {
        }
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                batteryIntent = intent;
            }
        }
    };

    // ======================= 前台应用 =======================

    private void refreshForegroundIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        if (fgPkg != null && now - lastFgQueryTs < Constants.Frame.FG_REFRESH_MS) {
            return;
        }
        lastFgQueryTs = now;
        String pkg = resolveForegroundPackage(false);
        if (pkg != null) {
            fgPkg = pkg;
        }
    }

    /**
     * 解析当前前台包名。
     *
     * 只在「开始记录」这一下走 root dumpsys（UsageStats 不可用时的兜底，也最贵）；
     * 记录中间的低频刷新只走 UsageStats，宁可 5 秒后才认出新应用也不拖慢采样。
     */
    private String resolveForegroundPackage(boolean allowRoot) {
        String fromUsage = com.jj.nexusfloat.stats.UsageStatsReader
                .lastForegroundPackage(context, Constants.Frame.FG_REFRESH_MS * 2);
        if (fromUsage != null && !fromUsage.isEmpty()) {
            return fromUsage;
        }
        if (!allowRoot) {
            return fgPkg;
        }
        return ForegroundAppReader.read(context);
    }

    /** 包名 → 应用名；解析不了就显示包名 */
    private String resolveLabel(String pkg) {
        if (pkg == null) {
            return "未知应用";
        }
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            CharSequence label = pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0));
            if (label != null) {
                return label.toString();
            }
        } catch (Throwable ignored) {
        }
        return pkg;
    }
}
