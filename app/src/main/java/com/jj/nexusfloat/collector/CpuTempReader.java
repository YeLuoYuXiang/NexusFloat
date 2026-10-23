package com.jj.nexusfloat.collector;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.RootShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 温度读取（CPU / GPU，一个类管两种）。
 *
 * Android 把温度源全挂在 /sys/class/thermal/thermal_zoneN 下面：CPU、GPU、电池、
 * 外壳、各种电压调节器都算。编号随机型完全不一样，一台机器上五六十个 zone 很常见，
 * 想知道哪个是 CPU 只能去翻 type 文件的内容。
 *
 * 流程是这样：
 * 1. 用 root shell 把 thermal_zone 的 type 文件全展开，一次批量读回来；
 * 2. 在 type 的值里找第一个含关键字的，记下这个 zone 的 temp 路径，缓存住；
 * 3. 以后每拍只读缓存的那一个 temp 文件。
 *
 * temp 的单位是千分之一摄氏度（milli-°C），除 1000 得到 ℃。解析出来的数要是掉在
 * Constants.Thermal.TEMP_MIN_C~TEMP_MAX_C 之外就当失败，别把离谱值显示出去。
 *
 * CPU 和 GPU 各有一份独立的缓存与退避计数：两者是不同的 zone，命中进度互不相干，
 * 共用一个计数会让先失败的那个把另一个的扫描也拖进退避。
 *
 * 只该由采集线程调用。
 */
public final class CpuTempReader {

    /** 每种温度源一份「缓存路径 + 退避计数」 */
    private static final class Zone {
        volatile String tempPath;
        int missCount;
    }

    private static final Map<String, Zone> ZONES = new HashMap<>();

    private CpuTempReader() {}

    /**
     * 读 CPU 温度（℃）。
     *
     * 找不到 CPU zone，或者读失败，都返回 Float.NaN。
     */
    public static float readCpuTempC() {
        return readFor("cpu", new String[]{Constants.Thermal.TYPE_HINT});
    }

    /**
     * 读 GPU 温度（℃），读不到返回 Float.NaN。
     *
     * 关键字比 CPU 多几个：高通的 GPU zone 常叫 gpu / gpu0 / kgsl，联发科常见
     * mali。按 Constants.Frame.GPU_TYPE_HINTS 的顺序找，命中谁就用谁。
     */
    public static float readGpuTempC() {
        return readFor("gpu", Constants.Frame.GPU_TYPE_HINTS);
    }

    /** 按关键字找 zone、读温度；命中过的路径缓存住，之后每拍只读那一个文件 */
    private static float readFor(String key, String[] hints) {
        Zone zone;
        synchronized (ZONES) {
            zone = ZONES.get(key);
            if (zone == null) {
                zone = new Zone();
                ZONES.put(key, zone);
            }
        }
        if (zone.tempPath != null) {
            float t = readTempFile(zone.tempPath);
            if (!Float.isNaN(t)) {
                zone.missCount = 0;
                return t;
            }
            // 缓存的 zone 没了（热插拔或者 ROM 更新，很少见），重新扫一遍
            zone.tempPath = null;
        }
        if (zone.missCount > Constants.Config.SYSFS_MISS_GRACE
                && (zone.missCount - Constants.Config.SYSFS_MISS_GRACE)
                % Constants.Config.SYSFS_RESCAN_EVERY_TICKS != 0) {
            // 退避：一直找不到，就没必要每秒扫几十个 type 文件
            zone.missCount++;
            return Float.NaN;
        }
        zone.missCount++;
        String found = scanAndCache(hints);
        if (found == null) {
            return Float.NaN;
        }
        zone.tempPath = found;
        return readTempFile(found);
    }

    /** 扫 thermal_zone* 找第一个 type 命中关键字的，返回它的 temp 路径 */
    private static String scanAndCache(String[] hints) {
        // 先走 root 通道。thermal 对普通应用一般只放 type，temp 常被 SELinux 挡
        String found = scanViaRoot(hints);
        if (found == null) {
            found = scanViaDirect(hints);
        }
        return found;
    }

    /** root 通道：把 type 通配展开批量读一遍，返回命中的那条 temp 路径 */
    private static String scanViaRoot(String[] hints) {
        List<String>[] expanded = RootShell.get().expandGlobs(
                new String[]{Constants.Thermal.TYPE_GLOB});
        if (expanded == null || expanded.length == 0 || expanded[0] == null) {
            return null;
        }
        List<String> typePaths = expanded[0];
        if (typePaths.isEmpty()) {
            return null;
        }
        String[] values = RootShell.get().readMany(typePaths.toArray(new String[0]));
        if (values == null) {
            return null;
        }
        for (int i = 0; i < typePaths.size() && i < values.length; i++) {
            String type = values[i];
            if (type != null && matchesAny(type, hints)) {
                return typePaths.get(i).replaceFirst("type$", "temp");
            }
        }
        return null;
    }

    /** 直读通道：只在本进程能 list 的目录里找。覆盖面小，root 用不了时兜底 */
    private static String scanViaDirect(String[] hints) {
        File dir = new File("/sys/class/thermal");
        String[] zones = dir.list();
        if (zones == null) {
            return null;
        }
        for (String zone : zones) {
            if (!zone.startsWith("thermal_zone")) {
                continue;
            }
            File typeFile = new File(dir, zone + "/type");
            String type = readDirect(typeFile.getPath());
            if (type != null && matchesAny(type, hints)) {
                return new File(dir, zone + "/temp").getPath();
            }
        }
        return null;
    }

    /** type 值（转小写）里含任意一个关键字就算命中 */
    private static boolean matchesAny(String type, String[] hints) {
        String lower = type.toLowerCase(Locale.US);
        for (String hint : hints) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读 temp 文件，换算成 ℃。
     *
     * 读不到、或者数值不在合理区间里，都返回 Float.NaN。
     */
    private static float readTempFile(String path) {
        String raw = RootShell.get().readFirstLine(path);
        if (raw == null || raw.isEmpty()) {
            raw = readDirect(path);
        }
        if (raw == null || raw.isEmpty()) {
            return Float.NaN;
        }
        try {
            float c = Float.parseFloat(raw.trim()) / Constants.Thermal.MILLI_DIVISOR;
            if (c >= Constants.Thermal.TEMP_MIN_C && c <= Constants.Thermal.TEMP_MAX_C) {
                return c;
            }
            return Float.NaN;
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static String readDirect(String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }
}
