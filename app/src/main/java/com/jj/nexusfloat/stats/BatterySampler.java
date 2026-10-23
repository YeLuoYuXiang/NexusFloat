package com.jj.nexusfloat.stats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;

import com.jj.nexusfloat.collector.PowerTracker;
import com.jj.nexusfloat.collector.SysfsReader;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 电池采样循环，整个统计功能的数据源头。
 *
 * 每过一个采样间隔做一次「读电池 + 记前台应用 + 落库」，并顺带维护充电会话和
 * 放电周期这两个状态机。之所以把状态机也放这儿而不是等采样完了再离线算：
 * 「插上充电器的那一刻」只能靠实时比较相邻两次的插电状态才发现，事后翻数据
 * 也能推出来，但要多扫一遍全表，而且进程被杀之后中间那段就断了。
 *
 * 会话/周期的当前状态不放在内存里当唯一真相——每落一个采样点就把汇总行
 * UPDATE 一次，进程什么时候死都不影响，重启后从 ongoing=1 那几行接着算。
 *
 * 唤醒策略（这是省电的关键）：
 * - 充电中持一个 PARTIAL_WAKE_LOCK，保证采样点密集，充电曲线才好看。充电时
 *   本来就在插着电，这点开销无所谓。
 * - 放电时**不持锁**，只靠 Handler 定时器。CPU 醒了就采一次，深睡就跳过——
 *   真是好事：那段时间本来就没有应用在前台，采到了也归因不到具体应用头上。
 * - 插拔充电器和亮灭屏另外注册广播立刻补采一次，这三个时刻正是状态机要转折的点。
 *
 * 因为放电期间会长时间不采，落点时写入的 dt_ms 做了封顶（见 clampDt），
 * 避免一次睡眠后的长间隔被当成「这几小时一直在以这个电流放电」，把耗电算成天量。
 */
public final class BatterySampler {

    private static final String TAG = "BatterySampler";

    /**
     * 用系统数据刷新亮屏时长的最小间隔（毫秒）。
     *
     * 亮屏时长精确到分钟就够了，没必要跟着采样频率走——那个查询要把整个周期
     * 的 UsageEvents 翻一遍，周期长的时候是几万条记录。
     */
    private static final long PERIOD_EXTRAS_INTERVAL_MS = 5 * 60 * 1000L;

    /**
     * 「当前电流」在电池广播里的 extra 键名。
     *
     * 写成字面量而不是引用 BatteryManager.EXTRA_CURRENT_NOW：那个常量是 @SystemApi，
     * 公开 SDK 里根本没有（编译直接报找不到符号）。
     *
     * 注意这只是**兜底**：AOSP 的 BatteryService 从来不往 ACTION_BATTERY_CHANGED
     * 里塞电流，只有部分 ROM（MIUI / ColorOS 之类）自己加了这个键。见
     * {@link #readCurrentA}。
     */
    private static final String EXTRA_CURRENT_NOW = "current_now";

    /**
     * root 通道连续失败多少次之后就永久放弃。
     *
     * 没有 root（或者用户没在 root 管理器里给本应用授权）时，每次尝试都要新起一个
     * su 进程；su 在等授权确认时会把读操作整个挂住，采样线程卡在那儿会连带把统计
     * 停摆。试几次不通就彻底不试。
     */
    private static final int ROOT_MISS_LIMIT = 3;

    /**
     * 最近一次采样里电流是从哪条路读到的，全部失败时为 null。
     *
     * 机型差异太大（联发科 HAL 常常不实现 CURRENT_NOW，有些 ROM 又只给 mA），
     * 界面上把这条露出来，出问题时一眼能看出是「三条路全不通」还是
     * 「通了但值不对」，比让用户去翻 logcat 强。
     */
    private static volatile String sCurrentSource;

    private static volatile BatterySampler sInstance;

    private final Context context;
    private final StatsStore store;

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;

    /** 电池广播最近一次的内容，采样时直接读它，不再每次去 registerReceiver */
    private volatile Intent batteryIntent;
    private BroadcastReceiver receiver;

    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private BatteryManager batteryManager;

    /** 上一个采样点的时间戳 */
    private long lastSampleTs;
    /** 上次用系统数据刷新亮屏时长的时刻，用于节流 */
    private long lastPeriodExtrasTs;

    /** root 通道连续失败了几次，到 {@link #ROOT_MISS_LIMIT} 就不再试 */
    private int rootMisses;
    /** 充电中才需要看的功率取值状态机，参数沿用监视条那一套 */
    private final PowerTracker powerTracker = new PowerTracker();

    /**
     * 上一次成功读到的电流绝对值（A），充电读不到时用来「沿用」。
     *
     * 只作用在充电场景（见 buildSample）：充电采样密、漏一拍不该归零，否则充入
     * 电量的积分会系统性偏小。放电不沿用——那段时间本来就不常采，沿用反而会
     * 把整晚的读数锁在一个旧值上。
     */
    private float lastValidCurrentA;
    /** 充电中已经连着沿用了多少拍，到 CHARGE_CARRY_MAX_TICKS 就不再沿用 */
    private int chargeCarryTicks;

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** 采样回调，给界面实时刷新用。跑在采样线程上，别在里面碰 UI */
    public interface Listener {
        void onSample(StatsStore.Sample sample);
    }

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
                handler.postDelayed(this, intervalMs());
            }
        }
    };

    private BatterySampler(Context context) {
        this.context = context.getApplicationContext();
        this.store = StatsStore.get(this.context);
        this.powerManager =
                (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        this.batteryManager =
                (BatteryManager) this.context.getSystemService(Context.BATTERY_SERVICE);
    }

    public static BatterySampler get(Context context) {
        BatterySampler inst = sInstance;
        if (inst == null) {
            synchronized (BatterySampler.class) {
                inst = sInstance;
                if (inst == null) {
                    sInstance = inst = new BatterySampler(context);
                }
            }
        }
        return inst;
    }

    // ======================= 生命周期 =======================

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        ensureThread();
        registerReceiver();
        handler.removeCallbacks(tickRunnable);
        // 立刻先采一次：服务刚起来的时候监视条/界面等着数字，
        // 也顺手把状态机从上一次运行的状态接上
        handler.post(tickRunnable);
        LogUtils.i(TAG + " started, interval=" + intervalMs() + "ms");
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (handler != null) {
            handler.removeCallbacks(tickRunnable);
        }
        unregisterReceiver();
        releaseWakeLock();
        LogUtils.i(TAG + " stopped");
    }

    public boolean isRunning() {
        return running;
    }

    /** 界面要求「立刻出一版数据」时用，比如用户刚点进统计页 */
    public void sampleNow() {
        Handler h = handler;
        if (h == null || !running) {
            return;
        }
        h.removeCallbacks(tickRunnable);
        h.post(tickRunnable);
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
        thread = new HandlerThread(Constants.ThreadName.STATS_SAMPLER);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    private int intervalMs() {
        return readIntervalSec() * 1000;
    }

    private int readIntervalSec() {
        SharedPreferences p = context.getSharedPreferences(
                Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
        int sec = p.getInt(Constants.Stats.KEY_INTERVAL_SEC,
                Constants.Stats.INTERVAL_DEFAULT_SEC);
        return Math.max(Constants.Stats.INTERVAL_MIN_SEC,
                Math.min(Constants.Stats.INTERVAL_MAX_SEC, sec));
    }

    private boolean readScreenOnOnly() {
        SharedPreferences p = context.getSharedPreferences(
                Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
        return p.getBoolean(Constants.Stats.KEY_SCREEN_ON_ONLY, false);
    }

    /**
     * 这一刻要不要按「仅亮屏时记录」跳过落点。
     *
     * 充电期间恒不跳过：插着电谈不上省电，而跳过的代价是一次整夜充电只剩零星几个点，
     * 收尾时样本太少会被判成无效会话直接删掉，充电记录就全没了。
     *
     * 这条规则同时反映在界面上：统计设置里那一行在充电时会显示成「已自动关闭」且
     * 不可点，拔电后恢复用户原本的选择。注意用户的设置值**从头到尾没有被改写**，
     * 所以「恢复原本的状态」是天然的——真去「先存原值、改掉、充电完再写回来」的话，
     * 进程一旦在充电中途被杀，用户的选择就永久丢了。
     */
    private boolean skipByScreenOnly(boolean screenOn, int plugged) {
        return readScreenOnOnly() && !screenOn && plugged == 0;
    }

    /**
     * 注册电池与亮灭屏广播。
     *
     * 电池广播是「粘性」的：注册之后系统会立刻把当前状态补一份过来，所以顺带
     * 拿到了第一份数据，不用再单独去 registerReceiver(null, ...) 同步要一次。
     *
     * 亮灭屏和插拔电源都触发一次立即采样：这四个时刻正是状态机的转折点，
     * 等下一个定时器再采会晚十几秒，会话的起止时间就不准了。
     */
    private void registerReceiver() {
        if (receiver != null) {
            return;
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                    batteryIntent = intent;
                }
                sampleNow();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            registerCompat(context, receiver, filter);
        } catch (Throwable t) {
            LogUtils.w(TAG + " registerReceiver failed", t);
        }
    }

    /**
     * 注册广播接收器，按版本补上导出标志。
     *
     * Android 13（targetSdk 34 起强制）要求 context 注册接收器时显式声明是否导出，
     * 否则注册直接抛异常。这里收的是电池、亮灭屏这三条系统保护的广播，本来就到不了
     * 第三方应用手里，标成 NOT_EXPORTED 既满足要求又不改变行为。
     *
     * receiver 传 null 时读粘性广播，返回当前 Intent，不注册任何东西。
     */
    private static Intent registerCompat(Context context, BroadcastReceiver receiver,
                                         IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        return context.registerReceiver(receiver, filter);
    }

    private void unregisterReceiver() {
        if (receiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        receiver = null;
    }

    // ======================= 一次采样 =======================

    private void tick() {
        long now = System.currentTimeMillis();
        boolean screenOn = powerManager == null || powerManager.isInteractive();

        Intent intent = batteryIntent;
        if (intent == null) {
            // 广播还没来（刚启动那一拍），同步要一次粘性广播
            try {
                intent = registerCompat(context, null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                batteryIntent = intent;
            } catch (Throwable t) {
                LogUtils.w(TAG + " sticky battery read failed", t);
            }
        }
        if (intent == null) {
            return;
        }

        int levelNow = readLevel(intent);
        int pluggedNow = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        if (skipByScreenOnly(screenOn, pluggedNow)) {
            // 用户选了「只在亮屏时记录」，息屏期间不落点（充电时不算，见
            // skipByScreenOnly 的注释）。
            //
            // 但状态机照样要跑：充电器插拔的转折不能因为息屏就漏掉，不然拔电那一刻
            // 开始的放电周期要等到下次亮屏才建，前面那段时间全被算进上一轮。
            // writeTotals 传 false：这一刻没必要重算汇总，反正没新采样点，
            // 等下一次真正落点时会一起更新。
            handleTransitions(now, levelNow, pluggedNow, false);
            return;
        }

        StatsStore.Sample sample = buildSample(intent, now, screenOn, levelNow, pluggedNow);

        // dt 按「距上一拍」算；第一拍没有上一拍，dt 记 0（不贡献积分）
        long dt = lastSampleTs > 0 ? clampDt(now - lastSampleTs) : 0L;
        sample.dtMs = dt;

        if (store.insertSample(sample)) {
            lastSampleTs = now;
        }

        updateWakeLock(sample);

        handleTransitions(now, sample.level, sample.plugged, true);

        for (Listener l : listeners) {
            try {
                l.onSample(sample);
            } catch (Throwable t) {
                LogUtils.w(TAG + " listener failed", t);
            }
        }
    }

    /**
     * 把两次采样之间的间隔封顶。
     *
     * 放电时进程不持锁，深睡期间定时器不会醒，醒来后第一拍的间隔可能是几小时。
     * 直接拿这个间隔去乘电流，就会把「几小时前的那个读数」当成「这几小时一直
     * 这么费电」，耗电直接算成天量。封到三倍采样间隔，宁可少算也不乱算。
     */
    private long clampDt(long raw) {
        long max = intervalMs() * 3L;
        return raw > max ? max : raw;
    }

    /** 从粘性广播里拼出一个采样点 */
    private StatsStore.Sample buildSample(Intent intent, long now, boolean screenOn,
                                          int level, int plugged) {
        StatsStore.Sample s = new StatsStore.Sample();
        s.ts = now;
        s.screenOn = screenOn;
        s.level = level;
        s.plugged = plugged;

        // ---- 电压 ----
        // 广播 extra 优先：它是系统自己算好的，最准。落到合理区间之外（读不到、
        // 或者是别的量纲）才去直读 sysfs。
        int rawMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        float voltageV = rawMv / 1000f;
        if (voltageV < Constants.Battery.VOLTAGE_MIN_V
                || voltageV > Constants.Battery.VOLTAGE_MAX_V) {
            voltageV = readVoltageViaSysfs();
            if (voltageV > 0f) {
                rawMv = Math.round(voltageV * 1000f);
            } else {
                // 真的读不到：兜底值只拿来算功率，voltageMv 记 0，
                // 界面上显示成「未知」而不是编一个 4.0V 出来
                rawMv = 0;
                voltageV = Constants.Battery.VOLTAGE_FALLBACK_V;
            }
        }
        s.voltageMv = rawMv;

        s.tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        s.status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);

        // ---- 电流与功率 ----
        // 跟监视条（collector.PerformanceCollector#collectBatteryData）走同一套：
        // 电流 × 电压 得功率，取数失败再退回 power_now，并过一遍 PowerTracker
        // 的突变守卫。两边共用同一个类的同一套常量，读数才不会对不上。
        boolean charging = plugged != 0
                && s.status != BatteryManager.BATTERY_STATUS_DISCHARGING;
        if (!charging) {
            // 拔电就把沿用计数归零：沿用窗口只服务「这一次充电」，不该跨会话累计。
            // lastValidCurrentA 不在这里清——它会在下一次读到有效值或第一次沿用后
            // 自然被覆盖，清不清对结果没影响，留着反而能桥接插拔瞬间那一拍
            chargeCarryTicks = 0;
        }
        float currentA = readCurrentA(intent);
        // readCurrentA 四条路全失败时返回的是 0，这个 0 是「读不到」的意思，
        // 不是「电流真的是 0」。必须把两者分开，否则 0 会被原样落库、当成测量值。
        boolean currentFresh = currentA > 0f;

        // 充电中延长「沿用窗口」：读不到就沿用上次有效电流，别几拍就归零。
        // 充电采样间隔默认 15 秒，PowerTracker 里放电用的 STALE_MAX_TICKS(5) 只顶
        // 75 秒，快充时电流节点连续抖动超过 5 拍就会落一串 0，充入电量的积分
        // （Σ I·dt）因此系统性偏小——容量反推跟着偏小就是这么来的。
        // 沿用有明确上限（CHARGE_CARRY_MAX_TICKS），且下方落库时标成「非本拍实测」。
        float effCurrentA = currentA;
        boolean carriedCurrent = false;
        if (currentFresh) {
            lastValidCurrentA = currentA;
            chargeCarryTicks = 0;
        } else if (charging && lastValidCurrentA > 0f
                && chargeCarryTicks < Constants.Battery.CHARGE_CARRY_MAX_TICKS) {
            effCurrentA = lastValidCurrentA;
            carriedCurrent = true;
            chargeCarryTicks++;
        }

        float magnitudeMa = effCurrentA * 1000f;
        s.currentMa = Math.round(charging ? magnitudeMa : -magnitudeMa);

        // 功率状态机照走（沿用值也过一遍，保证突变守卫对它是连续的）
        float powerW = powerTracker.resolve(effCurrentA, voltageV, this::readPowerNowW);
        s.powerW = charging ? powerW : -powerW;

        // power_known：这一拍的功率/电流是不是**本拍真读到**的。
        // 读不到、被区间拒掉、沿用旧值都记 false：
        // - 曲线据此把该点用相邻有效值桥接，不画假 0、也不留空洞（见 ui/StatsScreen.kt）；
        // - 聚合据此把非实测点从平均功率/峰值功率里剔除，不再把假 0 算进平均。
        // 注意：沿用值仍然写进了 power_w/current_ma（充电积分类继续用它），
        // 只是打了「非实测」的标记，数据仍可追溯到采样时刻。
        s.powerKnown = currentFresh
                || (!carriedCurrent && powerTracker.lastWasFresh());

        s.fgPkg = readForegroundPackage(screenOn, charging);
        return s;
    }

    /** 电量百分比。scale 读不到或者为 0 时返回 0，不让它把界面搞成 NaN */
    private static int readLevel(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : 0;
    }

    /** 界面用的诊断信息：电流是从哪条路读到的，全不通就是 null */
    public static String currentSourceName() {
        return sCurrentSource;
    }

    // ======================= 电池取数 =======================
    //
    // 这一节刻意跟监视条（collector.PerformanceCollector）保持逐条一致，
    // 不再自己写第二套。监视条上功率一直是准的，统计页读不到，原因就是当初
    // 另写了一套（只认 BatteryManager + 广播 extra + root），缺了「本进程直读
    // sysfs」这条，而这恰好是联发科机型唯一走得通的那条。

    /**
     * 读电池电流，单位 A（绝对值）；四条路都不通返回 0。
     *
     * 1. {@link BatteryManager#getIntProperty(int)}。官方公开 API，但**不少联发科
     *    机型的 HAL 根本没实现 CURRENT_NOW**，会恒返回 0 或 Integer.MIN_VALUE
     *    （取数失败返回的是 MIN_VALUE，不是 0，必须单独判掉）。
     * 2. ACTION_BATTERY_CHANGED 的 current_now extra。AOSP 不塞这个键，
     *    只有部分 ROM 自己加。
     * 3. 本进程直读 sysfs。battery 下的节点大多是 0444，普通应用也读得到；
     *    监视条跑在 SystemUI（system uid）走的就是这条。
     * 4. root shell 直读，给本进程被 SELinux 挡住的情况收尾。
     *
     * 单位一律交给 {@link SysfsReader#parseScaled}：电流的 µA / mA 两种解释对应的
     * 原始数值区间不重叠（见 Constants.Battery 的区间常量），所以判定是唯一的。
     * 自己写「除完小于 1mA 就当作 mA」这种启发式，在内核本来就用 mA、数值又不小的
     * 机型上会判错。
     */
    private float readCurrentA(Intent intent) {
        if (batteryManager != null) {
            try {
                int currentNow =
                        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                float a = parseBatteryValue(currentNow,
                        Constants.Battery.CURRENT_MIN_A, Constants.Battery.CURRENT_MAX_A);
                if (a > 0f) {
                    sCurrentSource = "系统接口";
                    return a;
                }
            } catch (Throwable t) {
                LogUtils.w(TAG + " getIntProperty current failed", t);
            }
        }

        float viaExtra = parseBatteryValue(readCurrentExtra(intent),
                Constants.Battery.CURRENT_MIN_A, Constants.Battery.CURRENT_MAX_A);
        if (viaExtra > 0f) {
            sCurrentSource = "广播";
            return viaExtra;
        }

        // 本进程直读：不起进程、不要 root，代价就是几次 open/read
        float direct = SysfsReader.direct().readBatteryCurrentA();
        if (direct > 0f) {
            sCurrentSource = "sysfs 直读";
            return direct;
        }

        float viaRoot = readCurrentViaRoot();
        if (viaRoot > 0f) {
            sCurrentSource = "sysfs(root)";
            return viaRoot;
        }

        sCurrentSource = null;
        return 0f;
    }

    /** 按电池电流的合法区间解释一个整数，单位有歧义时按量级定；解不出返回 0 */
    private static float parseBatteryValue(int raw, float min, float max) {
        if (raw == 0 || raw == Integer.MIN_VALUE) {
            return 0f;
        }
        return SysfsReader.parseScaled(String.valueOf(raw),
                Constants.Battery.CURRENT_DIVISORS, min, max);
    }

    /**
     * 广播 extra 里的电流。
     *
     * 先按 int 读，再按 long 读：个别 ROM 往 extra 里塞的是 long，而
     * Intent.getIntExtra 碰到类型不匹配会静默返回默认值 0，不会有异常提示。
     */
    private static int readCurrentExtra(Intent intent) {
        if (intent == null) {
            return 0;
        }
        int asInt = intent.getIntExtra(EXTRA_CURRENT_NOW, 0);
        if (asInt != 0) {
            return asInt;
        }
        long asLong = intent.getLongExtra(EXTRA_CURRENT_NOW, 0L);
        if (asLong > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (asLong < Integer.MIN_VALUE + 1L) {
            return Integer.MIN_VALUE + 1;
        }
        return (int) asLong;
    }

    /**
     * root 通道读电流。
     *
     * 连续失败 {@link #ROOT_MISS_LIMIT} 次就永久关掉：没有 root（或者用户没在
     * root 管理器里授权本应用）时，每次尝试都要新起一个 su，而 su 在等授权确认时
     * 会把整个读操作挂住，采样线程卡在那儿会让统计直接停摆。
     */
    private float readCurrentViaRoot() {
        if (rootMisses >= ROOT_MISS_LIMIT) {
            return 0f;
        }
        float a = SysfsReader.root().readBatteryCurrentA();
        if (a > 0f) {
            rootMisses = 0;
        } else {
            rootMisses++;
        }
        return a;
    }

    /** 电压（V）：直读 sysfs，本进程读不到再走 root，都读不到返回 0 */
    private float readVoltageViaSysfs() {
        float v = SysfsReader.direct().readBatteryVoltageV();
        if (v > 0f) {
            return v;
        }
        if (rootMisses >= ROOT_MISS_LIMIT) {
            return 0f;
        }
        v = SysfsReader.root().readBatteryVoltageV();
        rootMisses = v > 0f ? 0 : rootMisses + 1;
        return v;
    }

    /**
     * power_now（W），给 PowerTracker 当「电流那条路走不通」时的兜底来源。
     *
     * 只在真的要用时才被调到（Source 是惰性的），所以平时不会多花读取开销。
     */
    private float readPowerNowW() {
        float p = SysfsReader.direct().readBatteryPowerW();
        if (p > 0f) {
            return p;
        }
        if (rootMisses >= ROOT_MISS_LIMIT) {
            return 0f;
        }
        p = SysfsReader.root().readBatteryPowerW();
        rootMisses = p > 0f ? 0 : rootMisses + 1;
        return p;
    }


    /**
     * 读此刻前台应用。
     *
     * 息屏时不读：设备没有「被使用的应用」，这时候去读只会把「息屏前最后一个
     * 前台应用」记成一整晚的前台耗时，把应用榜彻底带偏。
     *
     * 充电时**照读**：边充边用是很常见的场景，这时候前台是谁仍然有意义——用户
     * 要的正是「同时充电和使用时也能统计到」。
     */
    private String readForegroundPackage(boolean screenOn, boolean charging) {
        if (!screenOn) {
            return null;
        }
        return ForegroundAppReader.read(context);
    }

    // ======================= 状态机 =======================

    /**
     * 维护充电会话和放电周期。
     *
     * 两种状态各自最多只有一行 ongoing=1 的记录，所以「当前是哪一次」不用额外
     * 存 id，按 ongoing 查出来就是。这样进程被杀、重启，甚至用户清数据之外的一切
     * 情况都能自动接上。
     */
    private void handleTransitions(long now, int level, int plugged, boolean writeTotals) {
        try {
            StatsStore.Session session = store.latestSession(true);
            StatsStore.Period period = store.latestPeriod(true);

            if (plugged != 0) {
                // 插上电：先给放电周期收尾，再开/续充电会话
                if (period != null) {
                    store.finishPeriod(period.id, period.startTs, period.startLevel, now, level);
                }
                if (session == null) {
                    long id = store.beginSession(now, level, plugged);
                    LogUtils.i(TAG + " charge session begin at " + level + "%");
                    if (id > 0) {
                        store.refreshSession(id, now, level, 0, level, plugged, true);
                    }
                } else if (writeTotals) {
                    store.refreshSession(session.id, session.startTs, session.startLevel,
                            0, level, plugged, true);
                }
            } else {
                // 拔下电：给充电会话收尾，再开/续放电周期
                if (session != null) {
                    store.finishSession(session.id, session.startTs, now,
                            session.startLevel, level, session.plugged);
                    LogUtils.i(TAG + " charge session finish at " + level + "%");
                }
                if (period == null) {
                    long id = store.beginPeriod(now, level);
                    if (id > 0) {
                        store.refreshPeriod(id, now, level, 0, level, true);
                    }
                } else if (writeTotals) {
                    store.refreshPeriod(period.id, period.startTs, period.startLevel,
                            0, level, true);
                    refreshPeriodExtras(period, now);
                }
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " handleTransitions failed", t);
        }
    }

    /**
     * 有使用情况权限时，用系统的精确亮屏时长覆盖采样积分的估算值。
     *
     * 采样积分在息屏期间会漏（进程不持锁，定时器不醒），得出的亮屏时长会偏小；
     * 系统那份是从屏幕交互事件算的，不受我们采不采样影响。
     *
     * 加了节流：这个查询要把整个周期的事件翻一遍，周期有几天的话是几万条记录，
     * 每 15 秒跑一次纯属浪费。亮屏时长本来就只需要精确到分钟。
     */
    private void refreshPeriodExtras(StatsStore.Period period, long now) {
        if (now - lastPeriodExtrasTs < PERIOD_EXTRAS_INTERVAL_MS) {
            return;
        }
        lastPeriodExtrasTs = now;
        if (!UsageStatsReader.hasPermission(context)) {
            return;
        }
        long from = period.startTs;
        if (now - from < 60_000L) {
            return;
        }
        UsageStatsReader.Snapshot snap = UsageStatsReader.summarize(context, from, now);
        if (snap.granted) {
            store.setPeriodScreenOnMs(period.id, snap.screenOnMs);
        }
    }

    // ======================= 唤醒锁 =======================

    /**
     * 只在充电时持锁。
     *
     * 放电时不持：那段时间本来就没有前台应用，靠定时器能采到几次就够，
     * 为了画一条更密的放电曲线让 CPU 整晚不睡不值得。
     */
    private void updateWakeLock(StatsStore.Sample sample) {
        boolean want = sample.charging() && sample.dtMs >= 0;
        if (want) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }
    }

    private void acquireWakeLock() {
        if (powerManager == null) {
            return;
        }
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "NexusFloat:stats-charging");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(12 * 60 * 60 * 1000L);
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " acquireWakeLock failed", t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 供界面判断「统计服务到底在不在跑」——比 Service 状态好查 */
    public long uptimeMs() {
        return SystemClock.elapsedRealtime();
    }
}
