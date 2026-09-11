package com.jj.nexusfloat.bridge;

import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
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
 */
public final class NexusBridge {

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

    // ---- 分层读取：Remote → provider 快照 → Settings.Global → 默认值，一个个往下试 ----

    private static boolean readBool(String key, boolean def) {
        SharedPreferences p = getPrefs();
        if (p != null && p.contains(key)) {
            return p.getBoolean(key, def);
        }
        if (PrefsSnapshot.contains(key)) {
            return PrefsSnapshot.getBoolean(key, def);
        }
        return SettingsChannel.getBoolean(key, def);
    }

    private static int readInt(String key, int def) {
        SharedPreferences p = getPrefs();
        if (p != null && p.contains(key)) {
            return p.getInt(key, def);
        }
        if (PrefsSnapshot.contains(key)) {
            return PrefsSnapshot.getInt(key, def);
        }
        return SettingsChannel.getInt(key, def);
    }

    private static float readFloat(String key, float def) {
        SharedPreferences p = getPrefs();
        if (p != null && p.contains(key)) {
            return p.getFloat(key, def);
        }
        if (PrefsSnapshot.contains(key)) {
            return PrefsSnapshot.getFloat(key, def);
        }
        return SettingsChannel.getFloat(key, def);
    }

    private static long readLong(String key, long def) {
        SharedPreferences p = getPrefs();
        if (p != null && p.contains(key)) {
            return p.getLong(key, def);
        }
        if (PrefsSnapshot.contains(key)) {
            return PrefsSnapshot.getLong(key, def);
        }
        return SettingsChannel.getLong(key, def);
    }

    private static String readString(String key, String def) {
        SharedPreferences p = getPrefs();
        if (p != null && p.contains(key)) {
            String v = p.getString(key, def);
            return v != null ? v : def;
        }
        if (PrefsSnapshot.contains(key)) {
            return PrefsSnapshot.getString(key, def);
        }
        return SettingsChannel.getString(key, def);
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
     * 读各指标模块的开关，下标跟 Constants.Modules.KEYS 对应。
     * 读不到时全部当开启。
     */
    public static boolean[] readModuleFlags() {
        boolean[] flags = new boolean[Constants.Modules.COUNT];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = readBool(Constants.Modules.KEYS[i], true);
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
     * 解析失败、缺项、重复或者混进了子项，一律回退 Constants.Modules.DEFAULT_ORDER——
     * 顺序这个键是「从旧版本升级」必然会缺的，回退必须无感。
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
        return Constants.Modules.isValidOrder(order)
                ? order : Constants.Modules.DEFAULT_ORDER.clone();
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
