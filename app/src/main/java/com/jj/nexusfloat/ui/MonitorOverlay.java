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
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.jj.nexusfloat.bridge.NexusBridge;
import com.jj.nexusfloat.bridge.PrefsSnapshot;
import com.jj.nexusfloat.bridge.SettingsChannel;
import com.jj.nexusfloat.collector.ForegroundAppTracker;
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
    /** 悬浮窗这会儿 addView 了没有 */
    private boolean added;
    /** watch 循环在不在跑 */
    private boolean watching;
    /** 上次已经通知给模块进程的采集状态，免得每秒重复下发同一条指令 */
    private Boolean lastCollectSignal;
    /** 同一个状态连续保持了几轮，用来做周期性复发指令 */
    private int signalTicks;
    /** 距上次后台唤醒过了多少轮（v1.8.11） */
    private int wakeElapsedTicks;

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
    }

    public boolean isShowing() {
        return added;
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
            try {
                windowManager.addView(content, params);
                added = true;
                LogUtils.i("MonitorOverlay added with window type " + type);
                return true;
            } catch (Throwable t) {
                // 把异常类名和消息带上：BadTokenException 说明窗口类型不被放行，
                // SecurityException 说明缺权限，这俩的对策完全不一样
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
        } else if (shouldShow && !added) {
            addToWindow();
        } else if (!shouldShow && added) {
            removeFromWindow();
        }

        // 无条件同步，不放在上面的状态迁移分支里：开机后要是当前方向本来就关着，
        // 监视条从来没添加过，也就谈不上「移除」这个迁移，可模块进程的采集默认是开着的，
        // 得靠这里把它关掉。signalCollector 自己会去重。
        signalCollector(shouldShow);

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
        if (changed && added && params != null && windowManager != null) {
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
     * lp.x / lp.y 是相对 gravity 锚点的偏移量。gravity 是 TOP|CENTER_HORIZONTAL，
     * 所以 x 正值向右、y 正值向下，正好符合直觉。
     *
     * 退化模式下（监视条是状态栏的子 View）没有窗口参数可改，直接跳过——
     * 那种模式本来就受状态栏布局摆布，位置微调无从下手。
     *
     * 返回偏移有没有变。
     */
    private boolean applyOffset() {
        if (params == null) {
            return false;
        }
        int x = NexusBridge.getOffsetX();
        int y = NexusBridge.getOffsetY();
        if (params.x == x && params.y == y) {
            return false;
        }
        params.x = x;
        params.y = y;
        return true;
    }

    /**
     * 该不该显示：当前方向的开关得是开的、开了应用过滤的话前台应用得在白名单里，
     * 息屏时还得开了「息屏显示」开关（v1.8.10）。
     */
    private boolean shouldShowNow() {
        // 息屏（灭屏/锁屏）判定：没开「息屏显示」就直接隐藏，
        // 隐藏会一并把采集停掉，省电。开屏状态用 isInteractive 现取，
        // 广播只负责提前触发一次评估，两者不冲突
        if (!NexusBridge.isShowOnScreenOff() && !isScreenInteractive()) {
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
            return;
        }
        if (params == null) {
            params = buildParams(Constants.Overlay.WINDOW_TYPES[0],
                    dp2px(Constants.Ui.MONITOR_BAR_HEIGHT_DP));
        }
        try {
            windowManager.addView(content, params);
            added = true;
        } catch (Throwable t) {
            LogUtils.w("MonitorOverlay re-add failed", t);
        }
    }

    private void removeFromWindow() {
        if (!added || content == null || windowManager == null) {
            added = false;
            return;
        }
        try {
            windowManager.removeViewImmediate(content);
        } catch (Throwable t) {
            LogUtils.w("MonitorOverlay removeView failed", t);
        }
        added = false;
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

        // 贴屏幕最顶端：y = 0 配合 FLAG_LAYOUT_IN_SCREEN / FLAG_LAYOUT_NO_LIMITS，
        // 竖屏横屏都紧贴各自方向下的物理屏幕上边缘，不再按状态栏高度居中下移。
        // x/y 之后会被「位置微调」覆盖，见 applyOffset()
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = NexusBridge.getOffsetX();
        lp.y = NexusBridge.getOffsetY();
        lp.windowAnimations = 0;
        lp.setTitle(Constants.Overlay.WINDOW_TITLE);

        // 允许画进刘海/挖孔区域，不然横屏可能被 inset 推出可视范围
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        // 脱离系统 insets，才能稳稳停在屏幕最顶部
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
