package com.jj.nexusfloat.bridge;

import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * SystemUI 进程这边：读模块 App 写进来的设置和 GPU 数据。
 *
 * 三条读取通道（v1.6.11）
 * 1. RemotePreferences——libxposed 提供的，最快，但要求模块自己的进程也被框架注入。
 * 2. PrefsSnapshot——经模块 App 的 exported ContentProvider 取本地 SharedPreferences 快照。
 *    不需要注入，但要 SystemUI 能跨应用 query，可能被 SELinux 或包可见性挡住。
 * 3. SettingsChannel——经 Settings.Global。写侧用 root 的 settings put global，
 *    读侧是 system uid 直接读，不经过任何跨应用调用。只要 root 在就通。
 *
 * 为什么要三条：实测澎湃 OS 4 上 LSPosed 只把模块注入了 com.android.systemui，
 * 模块自己的进程没注入。于是 App 侧 getXposedService() 恒为 null，写设置时 Remote
 * 那半段被静默跳过——SystemUI 读 Remote 永远是空的，表现就是所有开关都不生效，
 * 而监视条本身还能显示（读不到就用默认值），故障看着像「功能残缺」而不是「通道断了」。
 *
 * 优先级是逐键判断的，不是整体二选一：有些机型几条通道都通，而 Remote 可能只有一部分键。
 *
 * v1.9.4 起按「修订号」选通道，不再按固定顺序
 * 固定顺序（Remote 永远最高）有个致命的失效模式：Remote 只在模块自己的进程被注入时
 * 才会更新。注入一旦失效（换版本、重置作用域、ROM 更新），Remote 文件里留下的旧值就
 * 会永久压住另外两条通道——provider 快照和 Settings.Global 里明明是用户刚改的新值，
 * 读到的却永远是旧的。现象是「改字号/位置/底部开关完全没反应」，而悬浮窗本身照常显示
 * （它按旧值正常工作），故障看着像某个功能坏了，而不是通道断了。
 * 现在每次发布设置都会盖一个毫秒时间戳（Constants.Remote#KEY_PREFS_REV），三条通道
 * 各带一份，读侧比大小取最新的那一套；三条都没有修订号（老版本写下的数据）才退回
 * 原来的固定顺序，保证非 root 用户走 Remote 的老路径不被破坏。
 */
public final class NexusBridge {

    /** 诊断日志标签，走 android.util.Log 是为了在 logcat 里抓得到，见 SettingsChannel#TAG */
    private static final String TAG = "NexusFloat";

    /** 通道编号 */
    private static final int CHANNEL_NONE = -1;
    private static final int CHANNEL_REMOTE = 0;
    private static final int CHANNEL_SNAPSHOT = 1;
    private static final int CHANNEL_GLOBAL = 2;

    /** 通道里没有修订号的哨兵值：负数表示「证明不了自己比别人新」 */
    private static final long REV_UNKNOWN = -1L;

    /** 上次记下的解析结果，用来抑制重复日志：值没变就不打 */
    private static final Map<String, String> lastResolved = new HashMap<>();
    /** 三条通道的就绪状态只在进程里打一次，避免每秒刷屏 */
    private static volatile boolean channelsLogged;

    private static volatile XposedModule module;

    private NexusBridge() {}

    /** Hook 启动时把模块实例绑上，后面读 Remote 要用 */
    public static void bindModule(XposedModule xposedModule) {
        module = xposedModule;
        // 让普通日志（LogUtils.i/w/e）也进 LSPosed 模块日志，不然 SystemUI 这边的
        // 诊断只在 logcat 里，用户导出的日志包看不到
        LogUtils.bindModule(xposedModule);
        try {
            SharedPreferences prefs = xposedModule.getRemotePreferences(Constants.Remote.PREFS_NAME);
            LogUtils.i("NexusBridge bound, remote keys=" + prefs.getAll().size()
                    + " gpu=" + prefs.getInt(Constants.Remote.KEY_GPU_FREQ_MHZ, 0));
        } catch (Throwable t) {
            LogUtils.e("NexusBridge bind prefs failed", t);
        }
    }

    // ---- 分层读取：按修订号挑最新的一套，见类注释 ----

    private static boolean readBool(String key, boolean def) {
        SharedPreferences p = getPrefs();
        boolean inRemote = p != null && p.contains(key);
        int channel = pickChannel(key, p, inRemote);
        boolean value = def;
        if (channel == CHANNEL_REMOTE) {
            value = p.getBoolean(key, def);
        } else if (channel == CHANNEL_SNAPSHOT) {
            value = PrefsSnapshot.getBoolean(key, def);
        } else if (channel == CHANNEL_GLOBAL) {
            value = SettingsChannel.getBoolean(key, def);
        }
        logResolved(key, channel, value);
        return value;
    }

    private static int readInt(String key, int def) {
        SharedPreferences p = getPrefs();
        boolean inRemote = p != null && p.contains(key);
        int channel = pickChannel(key, p, inRemote);
        int value = def;
        if (channel == CHANNEL_REMOTE) {
            value = p.getInt(key, def);
        } else if (channel == CHANNEL_SNAPSHOT) {
            value = PrefsSnapshot.getInt(key, def);
        } else if (channel == CHANNEL_GLOBAL) {
            value = SettingsChannel.getInt(key, def);
        }
        logResolved(key, channel, value);
        return value;
    }

    private static float readFloat(String key, float def) {
        SharedPreferences p = getPrefs();
        boolean inRemote = p != null && p.contains(key);
        int channel = pickChannel(key, p, inRemote);
        float value = def;
        if (channel == CHANNEL_REMOTE) {
            value = p.getFloat(key, def);
        } else if (channel == CHANNEL_SNAPSHOT) {
            value = PrefsSnapshot.getFloat(key, def);
        } else if (channel == CHANNEL_GLOBAL) {
            value = SettingsChannel.getFloat(key, def);
        }
        logResolved(key, channel, value);
        return value;
    }

    private static long readLong(String key, long def) {
        SharedPreferences p = getPrefs();
        boolean inRemote = p != null && p.contains(key);
        int channel = pickChannel(key, p, inRemote);
        long value = def;
        if (channel == CHANNEL_REMOTE) {
            value = p.getLong(key, def);
        } else if (channel == CHANNEL_SNAPSHOT) {
            value = PrefsSnapshot.getLong(key, def);
        } else if (channel == CHANNEL_GLOBAL) {
            value = SettingsChannel.getLong(key, def);
        }
        logResolved(key, channel, value);
        return value;
    }

    private static String readString(String key, String def) {
        SharedPreferences p = getPrefs();
        boolean inRemote = p != null && p.contains(key);
        int channel = pickChannel(key, p, inRemote);
        if (channel == CHANNEL_REMOTE) {
            String v = p.getString(key, def);
            String value = v != null ? v : def;
            logResolved(key, channel, value);
            return value;
        }
        if (channel == CHANNEL_SNAPSHOT) {
            String value = PrefsSnapshot.getString(key, def);
            logResolved(key, channel, value);
            return value;
        }
        String value = SettingsChannel.getString(key, def);
        logResolved(key, channel, value);
        return value;
    }

    /**
     * 这个键该从哪条通道读。
     *
     * 规则：
     * 1. 只有一条通道有这个键——就它，没有可比的东西；
     * 2. 多条都有，比修订号，取最大的那条（并列时按 Remote → 快照 → Global 的顺序，
     *    因为并列说明是同一份数据，用近的那条少一次跨进程视角的偏差）；
     * 3. 三条都没有修订号（升级前写下的数据）——退回老顺序：Remote → 快照 → Global。
     *    这一条是为非 root 用户留的：他们只有 Remote 一条通道可用，不能因为缺修订号
     *    就把 Remote 判死。
     *
     * inRemote 由调用方传进来，避免为了判断 contains 再取一次 RemotePreferences。
     * 返回 CHANNEL_NONE 表示三处都没有这个键，调用方该用默认值。
     */
    private static int pickChannel(String key, SharedPreferences remote, boolean inRemote) {
        logChannelsOnce();
        boolean inSnapshot = PrefsSnapshot.contains(key);
        boolean inGlobal = SettingsChannel.contains(key);
        if (!inRemote && !inSnapshot && !inGlobal) {
            return CHANNEL_NONE;
        }
        long revRemote = inRemote ? revOfRemote(remote) : REV_UNKNOWN;
        long revSnapshot = inSnapshot
                ? PrefsSnapshot.getLong(Constants.Remote.KEY_PREFS_REV, REV_UNKNOWN)
                : REV_UNKNOWN;
        long revGlobal = inGlobal
                ? SettingsChannel.getLong(Constants.Remote.KEY_PREFS_REV, REV_UNKNOWN)
                : REV_UNKNOWN;
        long newest = Math.max(revRemote, Math.max(revSnapshot, revGlobal));
        if (newest > REV_UNKNOWN) {
            if (revRemote == newest) {
                return CHANNEL_REMOTE;
            }
            return revSnapshot == newest ? CHANNEL_SNAPSHOT : CHANNEL_GLOBAL;
        }
        // 三边都证明不了新鲜度，按老顺序
        if (inRemote) {
            return CHANNEL_REMOTE;
        }
        return inSnapshot ? CHANNEL_SNAPSHOT : CHANNEL_GLOBAL;
    }

    /**
     * Remote 那份表的修订号。
     *
     * 类型对不上会抛 ClassCastException（比如别处存成了字符串），这时按「没有修订号」
     * 处理：宁可让别的通道胜出，也不要让读设置这个每秒都要跑的动作把 SystemUI 带崩。
     */
    private static long revOfRemote(SharedPreferences remote) {
        if (remote == null) {
            return REV_UNKNOWN;
        }
        try {
            return remote.getLong(Constants.Remote.KEY_PREFS_REV, REV_UNKNOWN);
        } catch (Throwable t) {
            return REV_UNKNOWN;
        }
    }

    /**
     * 记一条 logcat 可见的诊断日志：某个键从哪条通道取到了什么值。
     *
     * 只在值变化时打一条——每秒轮询三十来个键，无条件打会把 logcat 刷满，反而没法用。
     * 设置不生效这类问题全靠这几行定位：能看出「读到的是什么」和「是哪条通道给的」，
     * 一眼就能区分「通道断了」和「读到了但没应用」。LogUtils 的输出只进 LSPosed 日志，
     * 用户抓 logcat 时看不到，所以这里用 android.util.Log。
     */
    private static void logResolved(String key, int channel, Object value) {
        String text = String.valueOf(value);
        synchronized (lastResolved) {
            String prev = lastResolved.put(key, text);
            if (text.equals(prev)) {
                return;
            }
        }
        Log.i(TAG, key + "=" + text + " <- " + channelName(channel) + revSummary());
    }

    private static String channelName(int channel) {
        switch (channel) {
            case CHANNEL_REMOTE:
                return "remote";
            case CHANNEL_SNAPSHOT:
                return "snapshot";
            case CHANNEL_GLOBAL:
                return "settings";
            default:
                return "default";
        }
    }

    /** 三条通道的修订号摘要，附在诊断日志后面 */
    private static String revSummary() {
        SharedPreferences p = getPrefs();
        long revRemote = revOfRemote(p);
        long revSnapshot = PrefsSnapshot.getLong(Constants.Remote.KEY_PREFS_REV, REV_UNKNOWN);
        long revGlobal = SettingsChannel.getLong(Constants.Remote.KEY_PREFS_REV, REV_UNKNOWN);
        return " rev(r=" + revRemote + " s=" + revSnapshot + " g=" + revGlobal + ")";
    }

    /**
     * 三条通道各自能不能用，进程生命周期里只打一次。
     *
     * 排查「设置不生效」时这是第一手材料：哪条通道没就绪，后面的问题就都顺理成章了。
     * 第一次调用通常发生在窗口刚建好、两条兜底通道还没加载完的时候，那时打印出来只是
     * 两条「not loaded」的噪声，所以等至少有一条兜底通道就绪之后再打。
     */
    private static void logChannelsOnce() {
        if (channelsLogged) {
            return;
        }
        if (!PrefsSnapshot.isReady() && !SettingsChannel.isReady()) {
            return;
        }
        channelsLogged = true;
        SharedPreferences p = getPrefs();
        Log.i(TAG, "channels: remote=" + (p == null ? "unavailable"
                        : (p.getAll().size() + " keys, rev=" + revOfRemote(p)))
                + " snapshot=" + (PrefsSnapshot.isReady()
                        ? "ready, rev=" + PrefsSnapshot.getLong(Constants.Remote.KEY_PREFS_REV, REV_UNKNOWN)
                        : "not loaded")
                + " settings=" + (SettingsChannel.isReady()
                        ? "ready, rev=" + SettingsChannel.getLong(Constants.Remote.KEY_PREFS_REV, REV_UNKNOWN)
                        : "not loaded"));
    }

    // ---- GPU 数据 ----

    public static int getGpuFreqMhz() {
        int fromPrefs = readInt(Constants.Remote.KEY_GPU_FREQ_MHZ, 0);
        if (fromPrefs > 0) {
            return fromPrefs;
        }
        return readFromRemoteFile();
    }

    public static int getGpuUsage() {
        int pct = readInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, -1);
        return pct >= 0 ? pct : -1;
    }

    public static long getUpdatedAt() {
        return readLong(Constants.Remote.KEY_UPDATED_AT, 0L);
    }

    // ---- 显示时机 ----

    /** 竖屏显不显示监视器；读不到时默认显示 */
    public static boolean isShowInPortrait() {
        return readBool(Constants.Remote.KEY_SHOW_PORTRAIT, true);
    }

    /** 横屏显不显示监视器；读不到时默认显示 */
    public static boolean isShowInLandscape() {
        return readBool(Constants.Remote.KEY_SHOW_LANDSCAPE, true);
    }

    /**
     * 息屏（锁屏/灭屏）时还显不显示监视条（v1.8.10）。
     * 读不到时默认 false：息屏后就隐藏并停采集，省电。
     */
    public static boolean isShowOnScreenOff() {
        return readBool(Constants.Remote.KEY_SHOW_SCREEN_OFF, false);
    }

    /**
     * 是不是「仅充电时显示」（v1.8.14）。
     * 读不到默认 false：没设过就按常显走，别让监视条意外消失。
     */
    public static boolean isShowChargingOnly() {
        return readBool(Constants.Remote.KEY_SHOW_CHARGING_ONLY, false);
    }

    /**
     * 读各指标模块的开关，下标跟 Constants.Modules.KEYS 对应。
     *
     * v1.8.11 起默认值不再是「全开」，而是 DEFAULT_ENABLED 那张表
     * （CPU、GPU、功率、FPS 四项）。用户改过的项以存下来的为准。
     */
    public static boolean[] readModuleFlags() {
        boolean[] flags = new boolean[Constants.Modules.COUNT];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = readBool(Constants.Modules.KEYS[i],
                    Constants.Modules.DEFAULT_ENABLED[i]);
        }
        return flags;
    }

    /**
     * 读各模块在监视条上显示的名称，下标跟 Constants.Modules.KEYS 对应。
     *
     * 用户没改过的项返回 Constants.Modules.DEFAULT_NAMES 里的默认值；
     * 用户特意清空的项返回空串，监视条看到空串就只留数值、不显示名称。
     */
    public static String[] readModuleNames() {
        String[] names = new String[Constants.Modules.COUNT];
        for (int i = 0; i < names.length; i++) {
            String def = Constants.Modules.DEFAULT_NAMES[i];
            names[i] = readString(Constants.Modules.nameKey(i), def);
        }
        return names;
    }

    /**
     * 读顶层模块的显示顺序（v1.8.0 加的）。
     *
     * 存的是逗号分隔的 IDX 列表（比如 "0,2,4,6,7,8,9,10,11,12"）。
     * 解析失败、重复或者混进了子项，一律回退 Constants.Modules.DEFAULT_ORDER——
     * 顺序这个键是「从旧版本升级」必然会缺的，回退必须无感。
     *
     * v1.8.14 起先过一遍 normalizeOrder：老版本存的列表里没有新加的模块，
     * 补齐到末尾就行，不必整个丢掉。
     */
    public static int[] readModuleOrder() {
        String raw = readString(Constants.Modules.KEY_MODULE_ORDER, null);
        if (raw == null || raw.isEmpty()) {
            return Constants.Modules.DEFAULT_ORDER.clone();
        }
        String[] parts = raw.split(",");
        int[] order = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                order[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return Constants.Modules.DEFAULT_ORDER.clone();
        }
        int[] normalized = Constants.Modules.normalizeOrder(order);
        return normalized != null ? normalized : Constants.Modules.DEFAULT_ORDER.clone();
    }

    /**
     * 读空格自定义（v1.8.7）：下标是顶层模块 IDX，值是这模块前面加几个空格。
     * 解析失败或缺键就全 0（监视条全紧贴）。
     */
    public static int[] readSpaceBefore() {
        return Constants.Modules.parseSpaceBefore(
                readString(Constants.Modules.KEY_SPACE_BEFORE, ""));
    }

    /**
     * 读各 FPS 来源的开关，下标跟 Constants.Modules.FpsSource.KEYS 对应。
     *
     * v1.6.4 修的 bug：开关全关掉仍然生效
     * v1.6.2/v1.6.3 在这里先查一个 fps_src_migrated 标记，标记为 false 就一律返回旧版预设。
     * 而 App 写这个标记依赖 XposedService，服务还没绑定时只写进了本地 SharedPreferences——
     * 于是 SystemUI 永远读到 false，用户在界面上的勾选全被忽略，关掉全部来源照样显示 0t。
     * 现在改成逐键读取，每个键的默认值由旧版整数推导。键存在就用键值，不存在才用默认——
     * 迁移不再依赖任何一个额外的写入是否成功。
     */
    public static boolean[] readFpsSourceFlags() {
        int count = Constants.Modules.FpsSource.COUNT;
        boolean[] fallback = legacyPreset();
        boolean[] flags = new boolean[count];
        for (int i = 0; i < count; i++) {
            // 默认值取旧版预设推导出来的那一位：从旧版升级、还没动过开关的用户
            // 保持原有行为；动过的以实际键值为准
            flags[i] = readBool(Constants.Modules.FpsSource.KEYS[i], fallback[i]);
        }
        return flags;
    }

    /**
     * 按旧版的 fps_source 整数取对应预设，当成逐键读取的默认值。
     * 读不到就返回「全部」，等价于旧版的「自动」。
     */
    private static boolean[] legacyPreset() {
        int legacy = readInt(Constants.Modules.KEY_FPS_SOURCE,
                Constants.Modules.FPS_SOURCE_AUTO);
        int preset = (legacy >= 0 && legacy < Constants.Modules.FpsSource.PRESETS.length)
                ? legacy : 0;
        return Constants.Modules.FpsSource.PRESETS[preset].clone();
    }

    /**
     * 「SurfaceFlinger 走 root」开关；默认关。
     *
     * 关着的时候走 IBinder.dump()，Binder 调用不会创建进程；开了之后每次取数多一次
     * dumpsys 的 fork+exec，只有 Binder 通道被 SELinux 拦住的时候才需要。
     */
    public static boolean isSfPreferRoot() {
        return readBool(Constants.Modules.KEY_SF_PREFER_ROOT, false);
    }

    /** 「FPS 诊断」开关；默认关 */
    public static boolean isFpsDebugEnabled() {
        return readBool(Constants.Modules.KEY_FPS_DEBUG, false);
    }

    /** 「背景」开关；默认关，跟旧版本观感保持一致 */
    public static boolean isBackgroundEnabled() {
        return readBool(Constants.Modules.KEY_BACKGROUND, false);
    }

    /** 「自动反色」开关；默认关，保持旧版固定字色的行为 */
    public static boolean isAutoContrastEnabled() {
        return readBool(Constants.Modules.KEY_AUTO_CONTRAST, false);
    }

    /**
     * 时间用不用 24 小时制。
     *
     * 读不到就返回 true，没有去查系统的 12/24 小时设置：这里拿不到 Context，
     * 而 DateFormat.is24HourFormat 要它。真正的「跟随系统」初值是 MonitorView
     * 构造的时候设的，这方法只管用户改过之后的值。
     */
    public static boolean isClock24h() {
        return readBool(Constants.Modules.KEYS[Constants.Modules.IDX_CLOCK_24H], true);
    }

    /**
     * 「字体颜色」在 Constants.Ui.TEXT_COLORS 里的下标；默认 0（白）。
     *
     * 越界就按 0 处理：色板长度可能随版本变，旧存的下标不能把监视条搞崩。
     */
    public static int getTextColorIndex() {
        int idx = readInt(Constants.Modules.KEY_TEXT_COLOR, 0);
        return (idx >= 0 && idx < Constants.Ui.TEXT_COLORS.length) ? idx : 0;
    }

    /**
     * CPU 柱状图开关；默认开。
     *
     * 默认开是为了跟旧版本一致——柱条一直画着，只不过现在能关掉了。
     */
    public static boolean isCpuBarEnabled() {
        return readBool(Constants.Modules.KEY_CPU_BAR, true);
    }

    /** GPU 柱状图开关；默认开，理由同上 */
    public static boolean isGpuBarEnabled() {
        return readBool(Constants.Modules.KEY_GPU_BAR, true);
    }

    /** 「双电芯」开关；默认关，用户确认自己机型是双电芯才开 */
    public static boolean isDualCellEnabled() {
        return readBool(Constants.Modules.KEY_DUAL_CELL, false);
    }

    /** 用户设的字号（sp）；读不到就用默认字号 */
    public static float getFontSizeSp() {
        return readFloat(Constants.Modules.KEY_FONT_SIZE, Constants.Ui.TEXT_SIZE_SP);
    }

    /** 用户设的字体加不加粗（v1.8.9）；默认加粗，跟旧版一致 */
    public static boolean isFontBold() {
        return readBool(Constants.Modules.KEY_FONT_BOLD, true);
    }

    /** 用户设的项目间隔（dp）；读不到就用默认间隔 */
    public static float getSpacingDp() {
        return readFloat(Constants.Modules.KEY_SPACING, Constants.Ui.DIVIDER_WIDTH_DP);
    }

    /**
     * 用户设的采集周期（毫秒）；读不到或者越界就用默认值。
     *
     * 越界是截断而不是回退默认：用户可能在旧版本里存过别的值，
     * 截到边上比一下跳回 1 秒更接近他的本意。
     */
    public static int getUpdateIntervalMs() {
        int ms = readInt(Constants.Modules.KEY_UPDATE_INTERVAL,
                Constants.Config.UPDATE_INTERVAL_MS);
        return Math.max(Constants.Config.UPDATE_INTERVAL_MIN_MS,
                Math.min(Constants.Config.UPDATE_INTERVAL_MAX_MS, ms));
    }

    /**
     * 后台唤醒间隔（秒，v1.8.11）；0 表示不额外唤醒。
     * 越界一样是截断，理由同刷新时间。
     */
    public static int getWakeIntervalSec() {
        int sec = readInt(Constants.Modules.KEY_WAKE_INTERVAL,
                Constants.Modules.WAKE_INTERVAL_DEFAULT_SEC);
        return Math.max(Constants.Modules.WAKE_INTERVAL_MIN_SEC,
                Math.min(Constants.Modules.WAKE_INTERVAL_MAX_SEC, sec));
    }

    /** 监视条水平偏移（px，正值往右）；读不到是 0 */
    public static int getOffsetX() {
        return clampOffset(readInt(Constants.Modules.KEY_OFFSET_X, 0));
    }

    /** 监视条垂直偏移（px，正值往下）；读不到是 0 */
    public static int getOffsetY() {
        return clampOffset(readInt(Constants.Modules.KEY_OFFSET_Y, 0));
    }

    private static int clampOffset(int px) {
        return Math.max(Constants.Modules.OFFSET_MIN_PX,
                Math.min(Constants.Modules.OFFSET_MAX_PX, px));
    }

    /**
     * 「显示在屏幕底部」开关：为 true 时悬浮窗以屏幕底部（手势栏那一带）为定位基准。
     *
     * 读不到默认 false，也就是老行为——贴屏幕顶端。老版本从没写过这个键，
     * 升级后必须原样不动，不能让人打开更新就发现监视条跑到下面去了。
     */
    public static boolean isAnchorBottom() {
        return readBool(Constants.Modules.KEY_ANCHOR_BOTTOM, false);
    }

    /**
     * 「防烧屏微移」开关：为 true 时监视条每隔 Constants.Modules#BURN_IN_INTERVAL_MS
     * 在 ±2px 内随机挪一下，轮换点亮的像素，延缓 OLED 上留下残影。
     *
     * 读不到默认 false：微移是个能被人眼察觉的「小动作」，默认必须不动，
     * 免得老用户升级后以为监视条在抖。真正有烧屏顾虑的人才会主动打开。
     */
    public static boolean isBurnInProtectEnabled() {
        return readBool(Constants.Modules.KEY_BURN_IN_PROTECT, false);
    }

    /** 「仅在选定应用显示」开关；默认关，也就是所有界面都显示 */
    public static boolean isAppFilterEnabled() {
        return readBool(Constants.Modules.KEY_APP_FILTER, false);
    }

    /**
     * 白名单包名。返回空集合表示用户开了过滤却一个应用都没选——
     * 这种情况监视条应该一直隐藏，跟「选了但不在前台」一样处理。
     *
     * 换行和空格都当分隔符：本地存的是换行分隔，而 Settings.Global 那条通道会把
     * 换行换成空格（换行是那边的行分隔符，不能混进值里）。
     */
    public static Set<String> getWhitelist() {
        String raw = readString(Constants.Modules.KEY_APP_WHITELIST, "");
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String pkg : raw.split("[\\n ]+")) {
            String trimmed = pkg.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static SharedPreferences getPrefs() {
        XposedModule m = module;
        if (m == null) {
            return null;
        }
        try {
            return m.getRemotePreferences(Constants.Remote.PREFS_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readFromRemoteFile() {
        XposedModule m = module;
        if (m == null) {
            return 0;
        }
        try {
            ParcelFileDescriptor pfd = m.openRemoteFile(Constants.Remote.GPU_FREQ_FILE);
            try (java.io.InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                 BufferedReader br = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line = br.readLine();
                if (line == null || line.isEmpty()) {
                    return 0;
                }
                float mhz = Float.parseFloat(line.trim().replaceAll("[^0-9.]", ""));
                return mhz > 0 ? (int) mhz : 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }
}
