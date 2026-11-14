package com.jj.nexusfloat.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.jj.nexusfloat.bridge.NexusBridge;
import com.jj.nexusfloat.bridge.PrefsSnapshot;
import com.jj.nexusfloat.bridge.SettingsChannel;
import com.jj.nexusfloat.collector.ForegroundAppTracker;
import com.jj.nexusfloat.collector.PerformanceCollector;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.service.CollectorSignal;
import com.jj.nexusfloat.ui.view.MonitorView;
import com.jj.nexusfloat.utils.LogUtils;

import java.util.Set;

/**
 * 拿独立悬浮窗装 MonitorView，按屏幕方向和用户开关决定要不要显示。
 *
 * 监视器原先挂在 R.id.status_bar 下面，可见性完全跟着状态栏窗口走：全屏应用
 * （横屏游戏、看视频）一隐藏状态栏，监视器就跟着没了，得下滑呼出来才能短暂看到。
 * 改成在 SystemUI 进程里自己建窗口之后，就不受状态栏显示状态影响了。
 *
 * 竖屏/横屏开关和各指标模块开关都是模块 App 写进 libxposed RemotePreferences 的，
 * 这边通过 NexusBridge 读。开关状态、屏幕方向、前台应用统一在 evaluate() 里判定：
 * 配置变更广播触发一次立即判定，另外还有 Constants.Overlay#WATCH_INTERVAL_MS 的
 * 周期轮询，用来抓 App 端刚改的开关和前台应用切换。
 *
 * 隐藏的时候不只是看不见：MonitorView 的采集线程会随 detach 停掉，模块进程里的
 * root GPU 采集也会被通知暂停，所以隐藏状态下不耗电。
 *
 * 这段代码跑在 SystemUI 进程（system uid）里，已经持有添加系统窗口需要的权限，
 * 模块自己的 Manifest 不用声明 SYSTEM_ALERT_WINDOW。
 */
public final class MonitorOverlay {

    private final Context context;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 前台应用判定，只有开了「仅在选定应用显示」才会用到它 */
    private final ForegroundAppTracker foregroundTracker;

    private View content;
    /** content 就是 MonitorView 时的同一引用，用来下发模块开关 */
    private MonitorView monitorView;
    private WindowManager.LayoutParams params;
    private BroadcastReceiver configReceiver;

    /** 为 true 时监视器是调用方以子 View 形式加进状态栏的，本类只切 visibility */
    private boolean fallbackMode;
    /**
     * 窗口状态机的瞬态。
     *
     * IDLE      稳态，不在添加也不在移除
     * ADDING    addToWindow() 执行中
     * REMOVING  removeFromWindow() 执行中
     *
     * 只有 IDLE 才允许 evaluate() 触发新的 add / remove。add 和 remove 都是异步的，
     * 布尔标志无法表达"正在进行中"这个中间态——PiP 切换时 evaluate() 会被轮询和
     * ACTION_CONFIGURATION_CHANGED 广播两条路径同时驱动，两边都读到"未添加"就各自
     * 走一次 addView，SurfaceFlinger 里留下两个 token，合成后就是重影。
     */
    private static final int STATE_IDLE = 0;
    private static final int STATE_ADDING = 1;
    private static final int STATE_REMOVING = 2;
    private int windowState = STATE_IDLE;

    /**
     * 窗口当前是否真实存在（稳态）。
     *
     * 跟 windowState 分开是有意的：后者的 IDLE 只表示"没有正在进行的操作"，
     * 不代表窗口一定在。isShowing() 和 syncModuleFlags() 要的是"在不在"，
     * evaluate() 的并发保护要的是"能不能动手"，混用一个变量会让 isShowing()
     * 在窗口从未添加成功时也返回 true。
     */
    private boolean attached;
    /** watch 循环在不在跑 */
    private boolean watching;
    /** 上次已经通知给模块进程的采集状态，免得每秒重复下发同一条指令 */
    private Boolean lastCollectSignal;
    /** 同一个状态连续保持了几轮，用来做周期性复发指令 */
    private int signalTicks;
    /** 距上次后台唤醒过了多少轮（v1.8.11） */
    private int wakeElapsedTicks;
    /**
     * 当前防烧屏微移量，瞬态：不进设置、不落盘，关掉开关立刻归零。
     *
     * 之所以只放在内存里：这是「显示效果」不是「用户设定」。写进 prefs 会带来两个
     * 麻烦——每 45 秒一次跨进程写盘（还带着 RemotePreferences 的整表同步），以及
     * App 端界面上的「水平位置 / 垂直位置」会跟着漂移跳动。基值始终是用户设定的数，
     * 漂移只是在 applyOffset() 里临时叠加上去。
     */
    private int driftX;
    private int driftY;
    /**
     * 下一次该换漂移量的时刻，SystemClock.elapsedRealtime() 基准；0 表示还没起算。
     *
     * 为什么用墙钟而不是「算了几轮 evaluate」：evaluate() 有三个入口——watch 轮询、
     * ACTION_CONFIGURATION_CHANGED / SCREEN_ON / SCREEN_OFF 广播。按次数计数的话，
     * 分屏拖动、折叠屏连续改尺寸时广播能到每秒几十次，间隔会被压到 1~2 秒，
     * 于是每两秒 updateViewLayout 一次——连续抖动的观感比不防烧屏还难受。
     * 用时刻比较，多触发几次 evaluate 只是多问一嘴，到点才动。
     *
     * 不用 System.currentTimeMillis()：那个会被用户改系统时间、NTP 校准拨动，
     * 往回拨一次就会让间隔变成几分钟甚至更久。elapsedRealtime 只受开机时长影响，
     * 单调递增（含深睡），正是「隔了多久」该用的钟。
     */
    private long nextDriftAt;
    /** 微移用的随机源，独立一个实例，别去动别的地方的 Random 序列 */
    private final java.util.Random driftRandom = new java.util.Random();

    private final Runnable watchRunnable = new Runnable() {
        @Override
        public void run() {
            if (!watching) {
                return;
            }
            evaluate();
            handler.postDelayed(this, Constants.Overlay.WATCH_INTERVAL_MS);
        }
    };

    public MonitorOverlay(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.foregroundTracker = new ForegroundAppTracker(context);
        // 设置快照要拿 Context 去 query 模块 App 的 provider
        PrefsSnapshot.bind(context);
    }

    /**
     * 用独立悬浮窗托管监视器。按 Constants.Overlay#WINDOW_TYPES 的顺序试窗口类型，
     * 有一个成功就算可用；全失败返回 false，调用方该改用 startFallback(View) 了。
     */
    public boolean start(View view) {
        if (windowManager == null || view == null) {
            return false;
        }
        stop();
        content = view;
        monitorView = (view instanceof MonitorView) ? (MonitorView) view : null;
        fallbackMode = false;

        // 先把字号和间隔应用上，probeWindowType 才能按正确高度建窗口
        if (monitorView != null) {
            monitorView.setFontSizeSp(NexusBridge.getFontSizeSp());
            monitorView.setFontBold(NexusBridge.isFontBold());
            monitorView.setSpacingDp(NexusBridge.getSpacingDp());
        }

        if (!probeWindowType()) {
            content = null;
            monitorView = null;
            return false;
        }

        startWatching();
        return true;
    }

    /**
     * 退化模式：监视器已经由调用方加进状态栏布局了，本类只按方向和开关切可见性。
     * 这种模式下全屏隐藏状态栏的问题解决不了，但两个开关还是有效的。
     */
    public void startFallback(View view) {
        if (view == null) {
            return;
        }
        stop();
        content = view;
        monitorView = (view instanceof MonitorView) ? (MonitorView) view : null;
        fallbackMode = true;
        startWatching();
    }

    /** 停止托管：移除悬浮窗，停掉 watch 循环和广播 */
    public void stop() {
        watching = false;
        handler.removeCallbacks(watchRunnable);
        unregisterConfigReceiver();
        removeFromWindow();
        content = null;
        monitorView = null;
        params = null;
        fallbackMode = false;
        // 把去重记录清掉：下次 evaluate 必定重新下发一次采集状态。
        // 这里不主动发 pause——stop() 多数时候是重新注入前的清理，
        // 发了马上又得 resume，白折腾一轮跨进程调用
        lastCollectSignal = null;
        signalTicks = 0;
        // 漂移跟着会话一起清掉：params 已经置空，留着旧值只会在下一次 start() 的
        // 第一次 applyOffset() 里让新窗口先歪一下
        driftX = 0;
        driftY = 0;
        // 节拍也从头起算：重新注入后是一个新的显示会话，不该沿用旧会话的剩余时间
        nextDriftAt = 0L;
    }

    public boolean isShowing() {
        return attached;
    }

    /**
     * 试出一个能用的窗口类型：真的 addView 一次，成了就留着
     * （后面由 evaluate() 按开关决定去留）。
     */
    private boolean probeWindowType() {
        // 高度跟着字号缩放，免得大字号被窗口裁掉
        int height = monitorView != null
                ? monitorView.getBarHeightPx()
                : dp2px(Constants.Ui.MONITOR_BAR_HEIGHT_DP);
        for (int type : Constants.Overlay.WINDOW_TYPES) {
            params = buildParams(type, height);
            // View 已有 parent 说明窗口其实已经在，别再 add 一次
            if (content.getParent() != null) {
                attached = true;
                windowState = STATE_IDLE;
                LogUtils.i("MonitorOverlay already attached, skip add for type " + type);
                return true;
            }
            windowState = STATE_ADDING;
            try {
                windowManager.addView(content, params);
                attached = true;
                windowState = STATE_IDLE;
                LogUtils.i("MonitorOverlay added with window type " + type);
                return true;
            } catch (Throwable t) {
                // 把异常类名和消息带上：BadTokenException 说明窗口类型不被放行，
                // SecurityException 说明缺权限，这俩的对策完全不一样
                windowState = STATE_IDLE;
                LogUtils.w("MonitorOverlay addView failed for type " + type
                        + ": " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            }
        }
        LogUtils.e("MonitorOverlay: all " + Constants.Overlay.WINDOW_TYPES.length
                + " window types rejected");
        params = null;
        return false;
    }

    private void startWatching() {
        registerConfigReceiver();
        watching = true;
        evaluate();
        handler.removeCallbacks(watchRunnable);
        handler.postDelayed(watchRunnable, Constants.Overlay.WATCH_INTERVAL_MS);
    }

    /**
     * 看屏幕方向、方向开关和前台应用，同步监视器的显示状态，顺便下发各指标模块的开关。
     * 悬浮窗模式下 add/remove 窗口；退化模式下切 visibility 并显式启停采集。
     */
    private void evaluate() {
        if (content == null) {
            return;
        }
        syncModuleFlags();

        boolean shouldShow = shouldShowNow();

        if (fallbackMode) {
            int target = shouldShow ? View.VISIBLE : View.GONE;
            if (content.getVisibility() != target) {
                content.setVisibility(target);
            }
            // 退化模式下 View 一直 attached，采集不会随可见性自动停，必须显式切。
            // 不放在可见性变化的分支里：初始可见性可能刚好就是目标值，而
            // onAttachedToWindow 早就把采集开起来了。collector 的 start/stop 是幂等的。
            if (monitorView != null) {
                monitorView.setCollecting(shouldShow);
            }
        } else if (shouldShow && !attached) {
            // ADDING / REMOVING 期间静默跳过：两条驱动路径（每秒轮询 +
            // ACTION_CONFIGURATION_CHANGED 广播）可能同时进来，非稳态时动手
            // 就是重影的来源
            if (windowState == STATE_IDLE) {
                addToWindow();
            }
        } else if (!shouldShow && attached) {
            if (windowState == STATE_IDLE) {
                removeFromWindow();
            }
        }

        // 无条件同步，不放在上面的状态迁移分支里：开机后要是当前方向本来就关着，
        // 监视条从来没添加过，也就谈不上「移除」这个迁移，可模块进程的采集默认是开着的，
        // 得靠这里把它关掉。signalCollector 自己会去重。
        signalCollector(shouldShow);

        // 防烧屏微移：必须放在上面的 add/remove 迁移之后——只有窗口的「在不在」已经
        // 定下来，才能判断这一轮该不该动。放在 signalCollector 之后是因为它是纯视觉
        // 动作，不影响采集状态，晚一步执行没有任何副作用
        maybeDrift();

        // 后台唤醒：模块进程被 ROM 清掉时，按用户设的间隔把它拉回来，
        // 否则 GPU 数据会一直停在进程被杀之前的值
        wakeModuleIfDue(shouldShow);

    }

    /**
     * 通知模块进程启停 root GPU 采集。
     *
     * SystemUI 这边的 MonitorView 会随 detach 停掉自己的采集线程，但模块 App 进程里的
     * GpuCollectorWorker 是另一个进程的常驻 su 读取循环，不受 SystemUI 影响。监视条
     * 隐藏时不通知它停，su 进程就会继续每秒读 sysfs，这正是「关了竖屏显示，竖屏下还是
     * 耗电」的来源。
     *
     * 状态没变一般就不重复下发，免得每秒一次跨进程 query。例外是「需要采集」这个状态，
     * 每 Constants.Overlay#SIGNAL_REPEAT_TICKS 轮会复发一次当心跳：模块进程被系统回收
     * 后重启时默认不采集，靠这个心跳把它拉回来。
     *
     * 「暂停」不复发。模块进程重启后本来就是暂停状态，没什么漂移要纠正；在这儿复发
     * pause 反而会把已经被回收的模块进程反复重新拉起来，比省下的那点还费电。
     */
    private void signalCollector(boolean collecting) {
        boolean sameAsLast = lastCollectSignal != null && lastCollectSignal == collecting;
        if (sameAsLast) {
            if (!collecting) {
                return;
            }
            if (++signalTicks < Constants.Overlay.SIGNAL_REPEAT_TICKS) {
                return;
            }
        }
        signalTicks = 0;
        lastCollectSignal = collecting;
        CollectorSignal.send(context, collecting);
    }

    /**
     * 定时唤醒模块进程（v1.8.11）。
     *
     * 为什么需要：GPU 数据由模块 App 进程用 root 读出来再回传，进程被清掉数据就停更。
     * ColorOS 在最近任务里点「全部清除」会把应用置为 stopped 状态，这种状态下
     * 系统只放行带 FLAG_INCLUDE_STOPPED_PACKAGES 的广播。划卡只是普通杀进程、
     * 不置 stopped，所以划卡没事，只有全部清除后才需要这条。
     *
     * 频率由用户设定，0 表示关闭、只用原来的心跳。
     * 只在监视条需要显示时才唤醒，隐藏时唤起来没人看，白耗电。
     */
    private void wakeModuleIfDue(boolean showing) {
        if (!showing) {
            wakeElapsedTicks = 0;
            return;
        }
        int intervalSec = NexusBridge.getWakeIntervalSec();
        if (intervalSec <= 0) {
            wakeElapsedTicks = 0;
            return;
        }
        // watch 循环每 WATCH_INTERVAL_MS 一轮，据此换算成需要等多少轮
        int ticksNeeded = (int) Math.max(1,
                intervalSec * 1000L / Constants.Overlay.WATCH_INTERVAL_MS);
        if (++wakeElapsedTicks < ticksNeeded) {
            return;
        }
        wakeElapsedTicks = 0;
        CollectorSignal.wakeModule(context);
        // 顺带确认采集在跑：模块进程刚被广播唤起时，需求还没打开
        CollectorSignal.send(context, true);
    }

    /**
     * 把 App 端写进 Remote 的模块开关、名称、背景开关、字号和项目间隔下发给 MonitorView。
     * 有变化的话窗口尺寸会跟着变，得触发一次窗口重新测量。
     */
    private void syncModuleFlags() {
        if (monitorView == null) {
            return;
        }
        // 请求刷新设置快照。真正的 query 在后台线程做，这个调用立刻返回。
        // 这是 RemotePreferences 不可用的机型（实测澎湃 OS 4）拿到设置的途径
        PrefsSnapshot.requestRefresh();
        // Settings.Global 通道：system uid 直接读，很轻，每轮都刷得起
        SettingsChannel.refresh(context);

        boolean changed = monitorView.setModuleEnabled(NexusBridge.readModuleFlags());
        changed |= monitorView.setModuleNames(NexusBridge.readModuleNames());
        changed |= monitorView.setModuleOrder(NexusBridge.readModuleOrder());
        changed |= monitorView.setSpacesBefore(
                NexusBridge.readSpaceBefore());
        changed |= monitorView.setBackgroundEnabled(NexusBridge.isBackgroundEnabled());
        changed |= monitorView.setFpsDebugEnabled(NexusBridge.isFpsDebugEnabled());
        changed |= monitorView.setSpacingDp(NexusBridge.getSpacingDp());
        changed |= monitorView.setBarsEnabled(
                NexusBridge.isCpuBarEnabled(), NexusBridge.isGpuBarEnabled());
        // 12/24 小时制只改文字格式，列宽是按最宽的情形定死的，不影响尺寸
        monitorView.setClock24h(NexusBridge.isClock24h());
        // 字体颜色和自动反色都只换颜色不改尺寸，所以不算进 changed。
        // 先设颜色再设反色：后者会按需覆盖前者，顺序反了就用了上一次的颜色
        monitorView.setTextColorIndex(NexusBridge.getTextColorIndex());
        monitorView.setAutoContrastEnabled(NexusBridge.isAutoContrastEnabled());
        // 采集周期：collector 每轮重读，不影响布局
        monitorView.setIntervalMs(NexusBridge.getUpdateIntervalMs());
        boolean fontChanged = monitorView.setFontSizeSp(NexusBridge.getFontSizeSp());
        changed |= fontChanged;
        changed |= monitorView.setFontBold(NexusBridge.isFontBold());
        // 位置偏移改的是窗口参数不是 View 内容，所以单独判断——它不需要重新测量，
        // 但同样得 updateViewLayout 才会生效
        changed |= applyOffset();
        if (changed && attached && params != null && windowManager != null) {
            // 窗口宽高都是 WRAP_CONTENT，内容变了得 updateViewLayout 才会重新测量
            try {
                windowManager.updateViewLayout(content, params);
            } catch (Throwable t) {
                LogUtils.w("MonitorOverlay updateViewLayout failed", t);
            }
        }
    }

    /**
     * 应用用户设定的位置偏移。
     *
     * lp.x / lp.y 是相对 gravity 锚点的偏移量。锚点由「显示在屏幕底部」开关决定
     * （见 resolveGravity()）：默认顶端，开了就是底部，水平方向恒为居中。
     *
     * y 的「值语义」与「param 语义」在底部基准下差一个负号，这里做换算：
     * 界面上的「垂直位置」一律按「正值向下、负值向上」讲（StepperRow 的提示、
     * changeAnchorBottom 的越界提醒都是这么写的），也就是屏幕坐标的方向，
     * 跟锚点无关。可 AOSP Gravity 对 BOTTOM 是
     * {@code outRect.bottom = container.bottom - yAdj}（正值向上），对 TOP 是
     * {@code outRect.top = container.top + yAdj}（正值向下）。所以底部基准下
     * 得把值取反再写进 params.y，界面那句「正值向下」在两种基准下才都成立，
     * 用户换基准不用重新学一遍方向。
     *
     * gravity 也要在这里一起比较和同步，不能只在 buildParams 里定死：用户运行中
     * 拨开关时窗口不会重建，唯一的生效路径是本方法返回 true 让 syncModuleFlags()
     * 走到 updateViewLayout。而 gravity 只有在 updateViewLayout 里改才会被
     * WindowManager 采纳——直接改完 params 不作为是不会生效的。
     *
     * 退化模式下（监视条是状态栏的子 View）没有窗口参数可改，直接跳过——
     * 那种模式本来就受状态栏布局摆布，位置微调无从下手。
     *
     * 防烧屏微移（driftX / driftY）在这里叠加到用户设定值上，基值永远是
     * NexusBridge 读回来的那个数。这样用户改「水平位置 / 垂直位置」时不会因为
     * 要「算干净」而先清掉漂移，也就不会在每次调值时明显跳一下。
     * 注意 driftY 和 offsetY 同属「值语义」，所以要在取反之前叠加——见下面 paramY。
     *
     * 返回「gravity 或偏移」有没有变。
     */
    private boolean applyOffset() {
        if (params == null) {
            return false;
        }
        int gravity = resolveGravity();
        // 锚点直接从上面这次结果里取，不再调一次 anchoredBottom()：后者内部又会走一遍
        // resolveGravity()，等于每秒多一次跨进程读；而且两次读之间用户若正好拨了
        // 「显示在屏幕底部」，会出现「gravity 是 TOP、paramY 却按底部取了反」的一帧
        boolean bottom = (gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM;
        // x 叠加后仍要钳在 ±OFFSET_MAX_PX 内：用户已经调到 400px 时再加 2 会让整条
        // 滑出屏幕一侧，那是真的看不见了，不是为了防烧屏该付的代价
        int x = clampOffsetPx(NexusBridge.getOffsetX() + driftX);
        // y 不钳：贴边时用户可能已经顶到 ±400，而漂移方向恰恰是「往屏幕内侧」的，
        // 钳掉等于把这次微移吞了。漂移本身受 ±BURN_IN_AMPLITUDE_PX 约束，
        // 不会把窗口推出屏幕
        int y = NexusBridge.getOffsetY() + driftY;
        // 值语义（正值向下）→ param 语义（BOTTOM 基准下的 yAdj 正值向上）。取反必须
        // 放在叠加 driftY 之后：driftY 是按「值语义」取的（往屏幕内侧 = 顶部基准取正、
        // 底部基准取负），先取反再叠加会让漂移方向反掉，那正是这一版要修的问题。
        int paramY = bottom ? -y : y;
        if (params.gravity == gravity && params.x == x && params.y == paramY) {
            return false;
        }
        params.gravity = gravity;
        params.x = x;
        params.y = paramY;
        return true;
    }

    private static int clampOffsetPx(int px) {
        return Math.max(Constants.Modules.OFFSET_MIN_PX,
                Math.min(Constants.Modules.OFFSET_MAX_PX, px));
    }

    /**
     * 防烧屏微移的节拍器：每隔 Constants.Modules#BURN_IN_INTERVAL_MS 换一个漂移量。
     *
     * 分三种情形：
     * 1. 窗口不在（隐藏）或处于退化模式：不累计、不移动，顺手把残留的漂移归零。
     *    归零要写回 params 而不是只是清字段——addToWindow() 复用旧 params 前会调
     *    applyOffset()，字段已经干净了，那里刷出来就是用户的原始位置，不会带着
     *    隐藏前的最后一点偏移突然出现。
     * 2. 开关关着：如果之前开着留下过漂移，立刻归零并推一次布局，让位置马上回到
     *    用户设定值。等 45 秒的下一个节拍才复位是不能接受的——用户点了开关却看到
     *    监视条还歪着，只会以为开关坏了。
     * 3. 开关开着：到点换一个新漂移量并推到窗口上。
     *
     * 节拍按 SystemClock.elapsedRealtime() 的时刻比较，不按 evaluate 次数：
     * 后者会被配置变更广播带快（分屏拖动时每秒几十次），见 nextDriftAt 的注释。
     */
    private void maybeDrift() {
        long now = SystemClock.elapsedRealtime();
        if (!attached || params == null || fallbackMode) {
            // 复位成「还没起算」：重新显示时从那一刻起算一个完整间隔，
            // 否则用户切一圈回来可能立刻看到跳一下
            nextDriftAt = 0L;
            if (driftX != 0 || driftY != 0) {
                driftX = 0;
                driftY = 0;
                // 窗口不在，没有布局可推；这里只是把干净的值写回 params 备用
                applyOffset();
            }
            return;
        }
        if (!NexusBridge.isBurnInProtectEnabled()) {
            nextDriftAt = 0L;
            if (driftX == 0 && driftY == 0) {
                return;
            }
            driftX = 0;
            driftY = 0;
            pushDrift();
            return;
        }
        // 当前漂移必须落在当前基准的合法区间内。用户中途切换「显示在屏幕底部」时，
        // 旧的 driftY 可能正好是反方向的（顶部基准的 +2 到底部基准就是朝屏幕外），
        // 那样监视条会被屏幕边缘裁掉一条，而且要等满一个间隔才自愈。
        // 发现越界就立刻换一个，但**不动 nextDriftAt**：这是纠错，不是「到点微移」，
        // 顺带把下一次正常微移往后推会让人转几次屏就再也等不到微移。
        if (!driftInBounds()) {
            pickDrift();
            pushDrift();
        }
        if (nextDriftAt == 0L) {
            // 首次（刚显示 / 刚打开开关）：从这里起算一个完整间隔
            nextDriftAt = now + Constants.Modules.BURN_IN_INTERVAL_MS;
            return;
        }
        if (now < nextDriftAt) {
            return;
        }
        nextDriftAt = now + Constants.Modules.BURN_IN_INTERVAL_MS;
        pickDrift();
        if (!pushDrift()) {
            // 白动的那一轮：|offsetX| 已经顶到 400 时，抽到的 x 若指向屏幕外会被
            // clampOffsetPx 钳回 400，要是这一轮 y 又恰好没变，params 就一个数都没动
            // （漂移字段变了、窗口没动，等于这一轮白微移）。再换一个重推一次——
            // 只重试一次不写成循环：实测残余约 1.2%（且必须先把水平位置顶到 ±400），
            // 代价只是那一轮没动，下次节拍照常，没有抖动风险；
            // 写成循环反而有理论上的重试尾巴
            pickDrift();
            pushDrift();
        }
    }

    /**
     * 当前 driftX / driftY 是不是落在当前基准的合法区间内（见 driftMinY() 的方向约束）。
     *
     * 之所以要单独判：合法区间依赖于「顶端还是底部」这个运行时可变的基准，
     * 漂移量却是跨基准保留的，所以切基准之后旧值可能变成越界值（顶部基准的 +2
     * 到底部基准就是朝屏幕外）。发现越界立刻换一个，不用等满一个间隔才自愈。
     */
    private boolean driftInBounds() {
        int amp = Constants.Modules.BURN_IN_AMPLITUDE_PX;
        boolean bottom = anchoredBottom();
        return driftX >= -amp && driftX <= amp
                && driftY >= driftMinY(bottom) && driftY <= driftMaxY(bottom);
    }

    /**
     * 漂移 y 分量的合法下限（值语义：正值向下、负值向上）。
     *
     * 顶部基准：窗口贴屏幕上沿，能走的只有「往下」，所以下限 0；
     * 底部基准：窗口贴屏幕下沿，能走的只有「往上」，所以下限为 -A。
     *
     * 这里说的是**值语义**（界面上「垂直位置」的那套方向），不是 params.y 的语义。
     * 依据是 AOSP android/view/Gravity.java：L278 `outRect.top = container.top + yAdj`
     * （TOP，正值向下）、L289 `outRect.bottom = container.bottom - yAdj`
     * （BOTTOM，正值向上），L212-214 的 javadoc 也写着「if gravity is BOTTOM it pushes
     * it up」。我们在 applyOffset() 里已经把底部基准的值取反成 param 语义，所以按
     * 「值语义」取区间就够了，取反后会正好落在屏幕内侧。
     *
     * 注意这个区间看起来和 AOSP「正值朝内」的结论相反（底部取 [-A,0] 而不是 [0,+A]），
     * 那是对的、不是笔误：AOSP 那句讲的是 yAdj（params 空间），这里的 [-A,0] 讲的是
     * 值空间，两者在底部基准下差一个负号。换算只发生在 applyOffset() 一处。
     */
    private static int driftMinY(boolean bottom) {
        return bottom ? -Constants.Modules.BURN_IN_AMPLITUDE_PX : 0;
    }

    /** 漂移 y 分量的合法上限（值语义）：顶部基准往下能走到 +A，底部基准最多回到原位 0 */
    private static int driftMaxY(boolean bottom) {
        return bottom ? 0 : Constants.Modules.BURN_IN_AMPLITUDE_PX;
    }

    /**
     * 把当前的 driftX / driftY 反映到窗口上，位置没变就不折腾 WindowManager。
     *
     * 返回「窗口位置到底有没有变化」：判断是不是白动一轮的调用方要看它，
     * 只是想刷一下的调用方忽略返回值即可。
     */
    private boolean pushDrift() {
        if (!applyOffset()) {
            return false;
        }
        if (windowManager == null || content == null) {
            return true;
        }
        try {
            windowManager.updateViewLayout(content, params);
        } catch (Throwable t) {
            LogUtils.w("MonitorOverlay burn-in drift updateViewLayout failed", t);
        }
        return true;
    }

    /**
     * 生成新的漂移量，保证和当前值不同（否则这一轮等于没动）。
     *
     * 方向约束：漂移只能取「往屏幕内侧」的那半边，绝对不能把已经贴边的窗口推到屏幕外。
     * 取值按**值语义**（正值向下、负值向上，也就是界面上「垂直位置」那套方向）：
     * - y 分量：顶部基准 [0, +A]（往下挪），底部基准 [-A, 0]（往上抬），见 driftMinY()
     * - x 分量：水平居中，两侧都是屏幕内，取全区间 [-A, +A]（越界由 applyOffset 的钳制兜住）
     *
     * 实现上把所有合法取值摊成一维下标，然后在「除当前值以外」的取值里随机取一个。
     * 用摊平下标而不是 do-while 重试：重试有个理论上不收敛的尾巴（连续抽到旧值），
     * 而且概率上也不好解释；摊平之后一次就够，逻辑还更好读。
     */
    private void pickDrift() {
        int amp = Constants.Modules.BURN_IN_AMPLITUDE_PX;
        // 基准只问一次：resolveGravity() 底下是一次跨进程读，能省就省
        boolean bottom = anchoredBottom();
        int minY = driftMinY(bottom);
        int yCount = driftMaxY(bottom) - minY + 1;
        int total = (2 * amp + 1) * yCount;
        // 当前值的下标。用户中途切换了定位基准时，旧 driftY 可能落在新区间之外，
        // 算出的下标就越界了——不要紧，下面取模后照样是一个合法下标，只是等于是
        // 随机换了个新位置
        int current = (driftX + amp) * yCount + (driftY - minY);
        // 跳过当前下标里「自己」，就能保证新值一定和旧值不同
        int step = 1 + driftRandom.nextInt(Math.max(1, total - 1));
        int next = Math.floorMod(current + step, total);
        driftX = next / yCount - amp;
        driftY = next % yCount + minY;
    }

    /**
     * 定位基准：默认屏幕顶端，开了「显示在屏幕底部」就换成底部；水平方向恒为居中。
     *
     * 抽成一个方法是因为 buildParams()（首次建窗口、旋转后重建）和 applyOffset()
     * （运行中拨开关）都得用同一个答案，两处各写一份三元表达式迟早会有一处漏改，
     * 表现就是「开关打开后只有转屏才生效」这种很难复现的问题。
     */
    private static int resolveGravity() {
        return NexusBridge.isAnchorBottom()
                ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
                : Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    }

    /**
     * 当前是不是以屏幕底部为定位基准。
     *
     * 从 resolveGravity() 反推而不是直接再读一次 NexusBridge.isAnchorBottom()：定位基准
     * 的定义只在 resolveGravity() 里写一份，y 的方向换算（applyOffset / buildParams）
     * 和防烧屏微移的合法区间都必须跟着同一个答案走，否则以后基准判定改了
     * （比如横向改成左右锚定），这里会悄悄用错方向的偏移，表现就是「开着微移
     * 监视条往屏幕外跑」或者「底部模式下垂直位置反着走」。
     *
     * 必须按 VERTICAL_GRAVITY_MASK 取值比较，不能写 (g & Gravity.BOTTOM) != 0：
     * TOP 的 0x30 和 BOTTOM 的 0x50 有重叠位（0x10），按位与会非零，顶端基准
     * 也会被判成底部。
     *
     * 判据来自 AOSP android/view/Gravity.java：L278 `outRect.top = container.top + yAdj`
     * （TOP 走 AXIS_PULL_BEFORE，yAdj 正值向下）、L289
     * `outRect.bottom = container.bottom - yAdj`（BOTTOM 走 AXIS_PULL_AFTER，yAdj 正值
     * 向上），L212-214 的 javadoc 原文「if gravity is TOP this pushes it down;
     * if gravity is BOTTOM it pushes it up」。正因为两者方向相反，写 params.y 时才
     * 需要按基准翻一次符号，见 applyOffset() 与 buildParams()。
     */
    private static boolean anchoredBottom() {
        return (resolveGravity() & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM;
    }

    /**
     * 该不该显示：当前方向的开关得是开的、开了应用过滤的话前台应用得在白名单里，
     * 息屏时还得开了「息屏显示」开关（v1.8.10），开了「仅充电时显示」时还得在充电
     * （v1.8.14）。
     */
    private boolean shouldShowNow() {
        // 息屏（灭屏/锁屏）判定：没开「息屏显示」就直接隐藏，
        // 隐藏会一并把采集停掉，省电。开屏状态用 isInteractive 现取，
        // 广播只负责提前触发一次评估，两者不冲突
        if (!NexusBridge.isShowOnScreenOff() && !isScreenInteractive()) {
            return false;
        }
        // 仅充电时显示：没在充电就隐藏。这里的「充电」用的是和功率方向同一套判定，
        // 插着 USB 但功耗超过供电、电池在掉电时不算充电
        if (NexusBridge.isShowChargingOnly()
                && !PerformanceCollector.isChargingNow(context)) {
            return false;
        }
        boolean orientationOn = isLandscape()
                ? NexusBridge.isShowInLandscape()
                : NexusBridge.isShowInPortrait();
        return orientationOn && isForegroundAllowed();
    }

    /**
     * 屏幕现在是不是处于可交互状态（亮屏，且没锁到黑屏）。
     *
     * 取不到 PowerManager 就按「亮屏」处理——宁可多显示，也别因为判定手段失效
     * 让监视条无声无息地消失。
     */
    private boolean isScreenInteractive() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    context.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            LogUtils.w("isScreenInteractive failed", t);
            return true;
        }
    }

    /**
     * 应用过滤判定。
     *
     * 没开过滤就恒为 true。开了之后：
     * 白名单为空 → 一直隐藏（用户开了过滤却一个应用没选，按字面意思办）；
     * 读不到前台包名 → 放行。判定手段失效时宁可显示，也别让监视条无声无息地消失，
     * 让人以为模块坏了。
     */
    private boolean isForegroundAllowed() {
        if (!NexusBridge.isAppFilterEnabled()) {
            return true;
        }
        Set<String> whitelist = NexusBridge.getWhitelist();
        if (whitelist.isEmpty()) {
            return false;
        }
        String foreground = foregroundTracker.getForegroundPackage();
        if (foreground == null) {
            return true;
        }
        return whitelist.contains(foreground);
    }

    /**
     * 判横屏。以 Configuration.orientation 为主，UNDEFINED 的时候退回比显示宽高。
     */
    private boolean isLandscape() {
        try {
            Configuration cfg = context.getResources().getConfiguration();
            if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return true;
            }
            if (cfg.orientation == Configuration.ORIENTATION_PORTRAIT) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        try {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            return dm.widthPixels > dm.heightPixels;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void addToWindow() {
        if (windowManager == null || content == null) {
            windowState = STATE_IDLE;
            return;
        }
        // 幂等：View 已有 parent 是物理事实，比任何标志位都可靠。
        // 即便状态机被异常路径带偏，这一条也能拦住重复 add
        if (content.getParent() != null) {
            attached = true;
            windowState = STATE_IDLE;
            return;
        }
        if (params == null) {
            params = buildParams(Constants.Overlay.WINDOW_TYPES[0],
                    dp2px(Constants.Ui.MONITOR_BAR_HEIGHT_DP));
        } else {
            // 复用旧 params 前先把 gravity / x / y 刷新一遍：窗口隐藏期间用户可能
            // 拨了「显示在屏幕底部」或调了位置，旧对象里还带着上次的基准，直接
            // addView 会让监视条先闪一下旧位置。syncModuleFlags() 每轮虽然也会调
            // applyOffset()，但它在 monitorView == null 时会提前返回，
            // 这条路径不能指望它，所以这里再同步一次（幂等，没变就什么都不做）
            applyOffset();
        }
        windowState = STATE_ADDING;
        try {
            windowManager.addView(content, params);
            attached = true;
        } catch (Throwable t) {
            LogUtils.w("MonitorOverlay re-add failed", t);
        } finally {
            windowState = STATE_IDLE;
        }
    }

    private void removeFromWindow() {
        if (content == null || windowManager == null) {
            attached = false;
            windowState = STATE_IDLE;
            return;
        }
        // 幂等：没有 parent 说明本来就不在窗口里
        if (content.getParent() == null) {
            attached = false;
            windowState = STATE_IDLE;
            return;
        }
        windowState = STATE_REMOVING;
        try {
            // 用 removeViewImmediate 而不是 removeView：它同步等 View 真正脱离
            // ViewRootImpl。重影场景下移除必须立刻生效，否则紧接着的 addView
            // 会与还没退场的旧窗口短暂并存
            windowManager.removeViewImmediate(content);
        } catch (Throwable t) {
            LogUtils.w("MonitorOverlay removeView failed", t);
        } finally {
            attached = false;
            windowState = STATE_IDLE;
        }
    }

    private WindowManager.LayoutParams buildParams(int type, int height) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        // 高度也用 WRAP_CONTENT：给死值等于替 View 决定它该多高，
        // 可实际行高受字号、中英文字形、柱条和背景内边距一起影响，算不准。
        // v1.6.6 给的是按字号推算的固定值，大字号和中文名称都会被裁掉下半截。
        // 参数 height 留着但悬浮窗不再用它，退化模式还需要
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT);

        // 贴屏幕边：y = 0 配合 FLAG_LAYOUT_IN_SCREEN / FLAG_LAYOUT_NO_LIMITS，
        // 竖屏横屏都紧贴各自方向下的物理屏幕边缘（默认上边缘，开了「显示在屏幕底部」
        // 就是下边缘、压在手势栏上），不再按状态栏高度居中下移。
        //
        // y 用「值语义」写：界面上「垂直位置」一律按「正值向下、负值向上」讲，与锚点
        // 无关，所以底部基准这里要取反再写进 lp.y。原因是 AOSP Gravity 的两条公式
        // 方向不同——TOP 走 PULL_BEFORE（top = container.top + yAdj，正值向下），
        // BOTTOM 走 PULL_AFTER（bottom = container.bottom - yAdj，正值向上）。
        // 取反之后，「正值推到哪里出屏」在两个基准下才对称：顶端基准正值往下是屏幕内、
        // 负值出上沿；底部基准正值往下是出下沿、负值是屏幕内。界面切换基准时会提示
        // 这种越界，见 MainActivity#changeAnchorBottom()。
        //
        // 建窗这一帧也要取反：否则先按未取反的 y 建出来，紧接着 syncModuleFlags() 的
        // applyOffset() 再纠正，用户会看到监视条先闪一下反方向。建窗时 drift 必为 0
        // （start()/stop() 都清过），所以只算用户值。
        // x/y 和 gravity 之后都会被「位置微调」和「显示在屏幕底部」覆盖，见 applyOffset()
        lp.gravity = resolveGravity();
        lp.x = NexusBridge.getOffsetX();
        int userY = NexusBridge.getOffsetY();
        lp.y = anchoredBottom() ? -userY : userY;
        lp.windowAnimations = 0;
        lp.setTitle(Constants.Overlay.WINDOW_TITLE);

        // 允许画进刘海/挖孔区域，不然横屏可能被 inset 推出可视范围
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        // 脱离系统 insets，才能稳稳停在屏幕最边缘——顶端基准贴物理屏幕上沿，
        // 底部基准贴物理屏幕下沿（不被导航栏/手势栏的 inset 顶上来）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                lp.setFitInsetsTypes(0);
            } catch (Throwable ignored) {
            }
        }
        return lp;
    }

    private void registerConfigReceiver() {
        if (configReceiver != null) {
            return;
        }
        configReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                // 旋转后 Configuration 可能晚一点才更新，推迟一拍再判定
                handler.post(MonitorOverlay.this::evaluate);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
            // v1.8.10：亮屏/灭屏时立刻重新评估一次可见性，
            // 不用等 watch 循环的下一拍（最多迟 1 秒）
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            context.registerReceiver(configReceiver, filter);
        } catch (Throwable t) {
            LogUtils.w("MonitorOverlay config receiver register failed", t);
            configReceiver = null;
        }
    }

    private void unregisterConfigReceiver() {
        if (configReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(configReceiver);
        } catch (Throwable ignored) {
        }
        configReceiver = null;
    }

    private int dp2px(float dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
