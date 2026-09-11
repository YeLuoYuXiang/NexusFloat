package com.jj.nexusfloat.collector;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.RootShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * sysfs 指标读取：GPU 频率、GPU 占用、面板实测 FPS。
 * 高通 KGSL、联发科 GED、通用 Mali devfreq 都认。
 *
 * 各平台节点的单位和格式都不一样：高通 clock_mhz 给 MHz、devfreq/cur_freq 给 Hz，
 * 联发科 current_freqency 给「档位 频率KHz」两个字段。与其给每个平台维护一张平行的
 * 单位表（新增机型要同步改两处，容易错位），不如按数值量级自动判定：真实 GPU 频率
 * 落在 50–3000 MHz，对应的 KHz、Hz 数值区间互不重叠，所以解释是唯一的。
 *
 * 两条读取通道：
 * direct() 是本进程的 FileReader。SystemUI 是 system uid，对联发科
 * /sys/kernel/ged/hal/* 通常可读，不用 root。
 * root() 经 RootShell 在 su 域里读。高通 vendor_sysfs_kgsl 对 untrusted_app 关着，
 * 模块 App 进程必须走这条。
 *
 * 每个通道缓存命中的路径。这对 root 通道是必需的：一次读取就是一轮 su 管道往返
 * （超时 600ms），要是每秒把十几个候选路径全试一遍，最坏会累积到秒级。
 */
public final class SysfsReader {

    private static final Channel DIRECT = new Channel(false);
    private static final Channel ROOT = new Channel(true);

    private SysfsReader() {}

    /** 本进程直读通道。SystemUI 是 system uid，联发科 GED 节点一般能读 */
    public static Channel direct() {
        return DIRECT;
    }

    /** root shell 通道，高通 KGSL 必须走这条 */
    public static Channel root() {
        return ROOT;
    }

    /** 一条读取通道，各指标自己带命中路径缓存 */
    public static final class Channel {

        /** power_supply 节点可能给 µ 也可能给 m，两者的有效数值区间不重叠 */
        private static final float[] UNIT_MICRO_MILLI = Constants.Battery.CURRENT_DIVISORS;
        /** power_now 按 Linux ABI 固定当 µW。µW/mW 区间会重叠，不猜 */
        private static final float[] UNIT_MICRO = Constants.Battery.POWER_DIVISORS;

        private final boolean viaRoot;
        /** 上次成功读出该指标的路径，命中之后不再遍历候选表 */
        private String freqHit;
        private String busyHit;
        private String fpsHit;
        /** vsync_event 的命中路径，以及上一次的时间戳（纳秒） */
        private String vsyncHit;
        private long lastVsyncNs;
        /** 整张表连着多少次没命中，用来退避重扫 */
        private int freqMiss;
        private int busyMiss;
        private int fpsMiss;

        private final ScaledSlot batteryCurrent;
        private final ScaledSlot batteryVoltage;
        private final ScaledSlot batteryPower;

        Channel(boolean viaRoot) {
            this.viaRoot = viaRoot;
            this.batteryCurrent = new ScaledSlot(Constants.Battery.CURRENT_PATHS,
                    UNIT_MICRO_MILLI, Constants.Battery.CURRENT_MIN_A, Constants.Battery.CURRENT_MAX_A);
            this.batteryVoltage = new ScaledSlot(Constants.Battery.VOLTAGE_PATHS,
                    UNIT_MICRO_MILLI, Constants.Battery.VOLTAGE_MIN_V, Constants.Battery.VOLTAGE_MAX_V);
            this.batteryPower = new ScaledSlot(Constants.Battery.POWER_PATHS,
                    UNIT_MICRO, Constants.Battery.POWER_MIN_W, Constants.Battery.POWER_MAX_W);
        }

        /** 电池电流绝对值（A），读不到就是 0 */
        public float readBatteryCurrentA() {
            return batteryCurrent.read();
        }

        /** 电池电压（V），读不到就是 0 */
        public float readBatteryVoltageV() {
            return batteryVoltage.read();
        }

        /** 电池功率绝对值（W），读不到就是 0 */
        public float readBatteryPowerW() {
            return batteryPower.read();
        }

        /** GPU 频率（MHz），失败返回 0 */
        public int readGpuFreqMhz() {
            if (freqHit != null) {
                int mhz = parseFreqMhz(readFirstLine(freqHit));
                if (mhz > 0) {
                    freqMiss = 0;
                    return mhz;
                }
                // 节点没了或者格式变了，回全表重新探
                freqHit = null;
            }
            if (skipScan(freqMiss)) {
                freqMiss++;
                return 0;
            }
            String[] paths = expandAll(Constants.Gpu.FREQ_PATHS);
            String[] values = readAll(paths);
            for (int i = 0; i < paths.length; i++) {
                int mhz = parseFreqMhz(values[i]);
                if (mhz > 0) {
                    freqHit = paths[i];
                    freqMiss = 0;
                    return mhz;
                }
            }
            freqMiss++;
            return 0;
        }

        /** GPU 占用（0–100），全部节点都读不到返回 -1 */
        public int readGpuUsagePercent() {
            if (busyHit != null) {
                int pct = parseUsagePercent(readFirstLine(busyHit));
                if (pct >= 0) {
                    busyMiss = 0;
                    return pct;
                }
                busyHit = null;
            }
            if (skipScan(busyMiss)) {
                busyMiss++;
                return -1;
            }
            String[] paths = expandAll(Constants.Gpu.BUSY_PATHS);
            String[] values = readAll(paths);
            for (int i = 0; i < paths.length; i++) {
                int pct = parseUsagePercent(values[i]);
                if (pct >= 0) {
                    busyHit = paths[i];
                    busyMiss = 0;
                    return pct;
                }
            }
            busyMiss++;
            return -1;
        }

        /**
         * 面板实测 FPS。全部节点都读不到、或者都在静止，返回 0。
         *
         * v1.8.4 把 v1.7.6 那套实测可用的实现恢复了回来。
         * v1.7.6-debug 在澎湃 / ColorOS 的骁龙 8 Gen 3 / 8 Elite 上面板 p 正常，
         * 后来的版本（缓存保留、删 direct、多轮改写）全都回归了。
         * 这一版把 readFps 完整恢复成 v1.7.6 的两级链：
         * 一是 readMeasuredFps，把候选全展开逐个读，选 frame_count 最大且
         * isActive 的节点（主屏出帧频率最高，自然胜出）；
         * 二是 readVsyncFps，measured_fps 全静止时用 vsync_event 时间戳差分兜底。
         */
        public float readFps() {
            float fps = readMeasuredFps();
            if (fps > 0) {
                return fps;
            }
            return readVsyncFps();
        }

        /**
         * measured_fps 这条路。命中之后缓存路径，只有连续失败才重扫。
         *
         * 在多个非零读数里选 frame_count 最大的：主屏出帧频率应该是最高的。
         * 两个 crtc 都报非零而且 frame_count 差不多的话，
         * String.compareTo 也还能给出确定的次序。
         */
        private float readMeasuredFps() {
            if (fpsHit != null) {
                Constants.Fps.FpsResult r = parseFpsWithFrames(readFirstLine(fpsHit));
                if (r.isActive()) {
                    fpsMiss = 0;
                    return r.fps;
                }
                fpsHit = null;
            }
            if (skipScan(fpsMiss)) {
                fpsMiss++;
                return 0f;
            }
            String[] paths = expandForFps(Constants.Fps.SYSFS_PATHS);
            String[] values = readAll(paths);
            String bestPath = null;
            Constants.Fps.FpsResult best = Constants.Fps.FpsResult.INACTIVE;
            for (int i = 0; i < paths.length; i++) {
                Constants.Fps.FpsResult r = parseFpsWithFrames(values[i]);
                if (r.isActive() && r.frames > best.frames) {
                    best = r;
                    bestPath = paths[i];
                }
            }
            if (bestPath != null) {
                fpsHit = bestPath;
                fpsMiss = 0;
                return best.fps;
            }
            fpsMiss++;
            return 0f;
        }

        /**
         * vsync_event 差分读取：同一个路径两次读到的 VSYNC= 时间戳相减。
         *
         * 这条路跟 measured_fps 是独立的，measured_fps 锁屏时会归零，
         * vsync_event 不会，所以前者拿不到的时候这边还可能有数。
         */
        private float readVsyncFps() {
            if (vsyncHit != null) {
                long ns = parseVsyncNs(readFirstLine(vsyncHit));
                if (ns > 0) {
                    float fps = diffVsyncNs(ns);
                    if (fps > 0) {
                        return fps;
                    }
                } else {
                    vsyncHit = null;
                    lastVsyncNs = 0;
                }
            }
            // 重新探一遍：展开成具体节点
            String[] paths = expandForFps(Constants.Fps.VSYNC_PATHS);
            String[] values = readAll(paths);
            for (int i = 0; i < paths.length; i++) {
                long ns = parseVsyncNs(values[i]);
                if (ns > 0) {
                    vsyncHit = paths[i];
                    lastVsyncNs = ns;
                    // 第一次只建基线，不算帧率，得等下一次采集
                    return 0f;
                }
            }
            return 0f;
        }

        /** 跟上一次 vsync 时间戳做差分，结果就是秒级帧率 */
        private float diffVsyncNs(long nowNs) {
            if (lastVsyncNs == 0 || nowNs <= lastVsyncNs) {
                lastVsyncNs = nowNs;
                return 0f;
            }
            long delta = nowNs - lastVsyncNs;
            // 防呆：同一个时间戳被读两次（kernel 没更新），delta 极小，
            // 算出来就是几千 fps 的假信号，直接丢掉
            if (delta < Constants.Fps.VSYNC_MIN_DELTA_NS) {
                lastVsyncNs = nowNs;
                return 0f;
            }
            lastVsyncNs = nowNs;
            float fps = 1_000_000_000f / delta;
            return fps < Constants.Fps.MAX_VALID ? fps : 0f;
        }

        /**
         * 给帧率读取展开候选表：通配必须展开成全部匹配，不能只留一个。
         *
         * 跟 expandAll 的区别就在这儿。频率、占用这些指标一台机器上只有一个有效节点，
         * 交给 shell 取首个匹配就行；可 measured_fps 每个 crtc 都有一个，必须逐个读过
         * 才知道哪个在出帧。
         *
         * root 通道走 RootShell.expandGlobs（能进本进程无权 list 的目录），
         * 直读通道用本地的 expandWildcard。
         */
        private String[] expandForFps(String[] patterns) {
            if (!viaRoot) {
                // 直读：expandAll 已经展开出全部匹配
                return expandAll(patterns);
            }
            List<String>[] expanded = RootShell.get().expandGlobs(patterns);
            if (expanded == null) {
                // shell 用不了：原样退回去，让 readAll 走既有的失败分支
                return patterns;
            }
            List<String> out = new ArrayList<>(patterns.length * 2);
            for (List<String> group : expanded) {
                out.addAll(group);
            }
            return out.toArray(new String[0]);
        }

        /**
         * 整张表扫过若干次还没命中，说明本机确实没这类节点，
         * 之后每 Constants.Config.SYSFS_RESCAN_EVERY_TICKS 轮才重扫一次。
         *
         * 对 root 通道尤其重要：一次读取就是一轮 su 管道往返（超时 600ms），
         * 每秒把十几个路径全试一遍会把采集线程拖到秒级。
         */
        private boolean skipScan(int missCount) {
            int grace = Constants.Config.SYSFS_MISS_GRACE;
            if (missCount < grace) {
                return false;
            }
            return (missCount - grace) % Constants.Config.SYSFS_RESCAN_EVERY_TICKS != 0;
        }

        private String readFirstLine(String path) {
            if (viaRoot) {
                return RootShell.get().readFirstLine(path);
            }
            File f = new File(path);
            if (!f.exists()) {
                return null;
            }
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                return br.readLine();
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * 一次把整张候选表读完。
         *
         * root 通道合并成一次管道往返（RootShell.readMany），全表扫描的代价从
         * 「每个路径一次往返」降到一次；直读通道就逐个读，本进程 open/read 本身
         * 没有进程创建开销，不用合并。
         *
         * 返回值数组跟 paths 等长，读不到的位置是 null。
         */
        private String[] readAll(String[] paths) {
            if (viaRoot) {
                String[] values = RootShell.get().readMany(paths);
                // 整体失败（shell 已经死了）就返回全 null，让调用方走未命中分支
                return values == null ? new String[paths.length] : values;
            }
            String[] values = new String[paths.length];
            for (int i = 0; i < paths.length; i++) {
                values[i] = readFirstLine(paths[i]);
            }
            return values;
        }

        /** 把整张候选表的 * 通配展开，结果按原顺序摊平 */
        private String[] expandAll(String[] patterns) {
            List<String> out = new ArrayList<>(patterns.length);
            for (String pattern : patterns) {
                Collections.addAll(out, expand(pattern));
            }
            return out.toArray(new String[0]);
        }

        /** 展开 * 通配。root 通道交给 shell 展开（能进本进程无权 list 的目录） */
        private String[] expand(String pattern) {
            if (viaRoot || pattern.indexOf('*') < 0) {
                return new String[]{pattern};
            }
            return expandWildcard(pattern);
        }

        /**
         * 一个「候选路径 + 单位换算 + 有效区间」的读取槽，自带命中缓存。
         * 电池的电流/电压/功率结构完全一样，用同一个槽省掉三份重复代码。
         */
        private final class ScaledSlot {
            private final String[] paths;
            private final float[] divisors;
            private final float min;
            private final float max;
            private String hit;
            private int miss;

            ScaledSlot(String[] paths, float[] divisors, float min, float max) {
                this.paths = paths;
                this.divisors = divisors;
                this.min = min;
                this.max = max;
            }

            float read() {
                if (hit != null) {
                    float v = parse(readFirstLine(hit));
                    if (v > 0) {
                        // 必须清零：不然早期的零星失败会累积下来，
                        // 之后一次读取失败就误触发 60 轮退避
                        miss = 0;
                        return v;
                    }
                    hit = null;
                }
                if (skipScan(miss)) {
                    miss++;
                    return 0f;
                }
                String[] values = readAll(paths);
                for (int i = 0; i < paths.length; i++) {
                    float v = parse(values[i]);
                    if (v > 0) {
                        hit = paths[i];
                        miss = 0;
                        return v;
                    }
                }
                miss++;
                return 0f;
            }

            /** 取绝对值后按各候选单位换算，返回第一个落在有效区间的结果 */
            private float parse(String raw) {
                return parseScaled(raw, divisors, min, max);
            }
        }
    }

    /**
     * 解析单位有歧义的数值：取绝对值后依次用各除数换算，
     * 返回第一个落在 [min, max] 里的结果。
     *
     * 只有各候选除数对应的原始数值区间互不重叠，结果才唯一，
     * 这由 Constants.Battery 里的区间常量保证。
     *
     * 返回换算后的值，解析不了就是 0。
     */
    static float parseScaled(String raw, float[] divisors, float min, float max) {
        if (raw == null) {
            return 0f;
        }
        String token = raw.trim().replaceAll("[^0-9.]", "");
        if (token.isEmpty()) {
            return 0f;
        }
        float val;
        try {
            val = Math.abs(Float.parseFloat(token));
        } catch (Exception e) {
            return 0f;
        }
        for (float divisor : divisors) {
            float scaled = val / divisor;
            if (scaled >= min && scaled <= max) {
                return scaled;
            }
        }
        return 0f;
    }

    /**
     * 从节点原始内容里解析频率（MHz）。
     *
     * 对每个数字字段依次按 MHz、KHz、Hz 解释，取第一个落在
     * Constants.Gpu.FREQ_MIN_MHZ–FREQ_MAX_MHZ 里的结果。三种单位在真实频率下的
     * 数值区间互不重叠，所以解释是唯一的。
     *
     * 多字段时按原始数值从大到小试。联发科 current_freqency 的格式是
     * 「档位号 频率KHz」（比如 "63 850000"），档位号可能落在 50–3000 里被误判成
     * MHz，所以不能简单取首个字段。频率的原始数值总是远大于档位号，降序试就避开了。
     *
     * 返回频率 MHz，解析不了就是 0。
     */
    static int parseFreqMhz(String raw) {
        if (raw == null) {
            return 0;
        }
        List<Float> values = new ArrayList<>();
        for (String token : raw.trim().split("[^0-9.]+")) {
            if (token.isEmpty()) {
                continue;
            }
            try {
                float val = Float.parseFloat(token);
                if (val > 0) {
                    values.add(val);
                }
            } catch (Exception ignored) {
            }
        }
        Collections.sort(values, Collections.reverseOrder());

        for (Float val : values) {
            // 除数从小到大：MHz、KHz、Hz
            for (float divisor : new float[]{1f, 1_000f, 1_000_000f}) {
                int mhz = (int) (val / divisor);
                if (mhz >= Constants.Gpu.FREQ_MIN_MHZ && mhz <= Constants.Gpu.FREQ_MAX_MHZ) {
                    return mhz;
                }
            }
        }
        return 0;
    }

    /**
     * 解析占用百分比：取第一个落在 0–100 的整数字段。高通
     * gpu_busy_percentage（"45 100"）和联发科 gpu_utilization（"45 3 52"）
     * 都是首字段就是占用。
     *
     * 返回 0–100，解析不了就是 -1。
     */
    static int parseUsagePercent(String raw) {
        if (raw == null) {
            return -1;
        }
        for (String token : raw.trim().split("[^0-9]+")) {
            if (token.isEmpty()) {
                continue;
            }
            try {
                int val = Integer.parseInt(token);
                if (val >= 0 && val <= 100) {
                    return val;
                }
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    /**
     * 解析 measured_fps 的三个字段（v1.8.4 恢复成 v1.7.6 那版）。
     *
     * 高通报告的实际格式是
     * "fps: 22.7 duration:1000000 frame_count:23"，三个字段都返回。
     * 锁屏态的 fps: 0.7 frame_count: 1（一秒一帧）也能正确解析：frames=1 表明确实
     * 出了一帧，但这种「活动」是伪活动，后面用 Constants.Fps.FpsResult.isActive
     * 再按 frame_count 过滤一次。
     *
     * 解析失败返回 Constants.Fps.FpsResult.INACTIVE。
     */
    static Constants.Fps.FpsResult parseFpsWithFrames(String raw) {
        if (raw == null) {
            return Constants.Fps.FpsResult.INACTIVE;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Constants.Fps.FpsResult.INACTIVE;
        }
        // 纯数字（有些 kernel 老版本只打数字不打键名）：
        // frames 不知道，按 fps 推——120fps 一秒该出 120 帧，就按 fps 算
        if (isNumericOnly(trimmed)) {
            float fps = parseFpsToken(trimmed);
            if (fps > 0) {
                return new Constants.Fps.FpsResult(fps, (int) (double) fps);
            }
            return Constants.Fps.FpsResult.INACTIVE;
        }
        // 用正则匹配 fps / frame_count 各自的「key 数字」组合：
        // - "fps: 22.7" / "fps:22.7" / "fps :22.7" 都行
        // - "frame_count: 23" / "frame_count:23" 都行
        Float fps = null;
        Integer frames = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\bfps\\s*[:=]\\s*([0-9.]+)")
                .matcher(trimmed);
        if (m.find()) {
            fps = parseFpsToken(m.group(1));
        }
        m = java.util.regex.Pattern
                .compile("\\bframe_count\\s*[:=]\\s*([0-9]+)")
                .matcher(trimmed);
        if (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 0) {
                    frames = v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (fps == null || fps <= 0) {
            return Constants.Fps.FpsResult.INACTIVE;
        }
        // 没有 frame_count 字段：按 fps 取整当 frames（fps 一定跟 frame_count
        // 等量，120fps 一秒就是 120 帧）。fps=0.7 时 frames=0，不会被当成活动。
        if (frames == null) {
            return new Constants.Fps.FpsResult(fps, (int) (double) fps);
        }
        return new Constants.Fps.FpsResult(fps, frames);
    }

    /**
     * 解析 vsync_event 里的 VSYNC=&lt;nanos&gt;。
     *
     * 闲置 crtc 的值是 0，主屏非零。失败或者 0 都返回 0，怎么处理交给调用方。
     */
    static long parseVsyncNs(String raw) {
        if (raw == null) {
            return 0L;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return 0L;
        }
        int eq = trimmed.indexOf('=');
        String value = eq >= 0 ? trimmed.substring(eq + 1) : trimmed;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 整个字符串是不是只由数字、小数点和符号组成 */
    private static boolean isNumericOnly(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isDigit(c) && c != '.' && c != '-' && c != '+') {
                return false;
            }
        }
        return true;
    }

    /** 把单个 token 解析成帧率，不是合理帧率就返回 0 */
    private static float parseFpsToken(String token) {
        String digits = token.replaceAll("[^0-9.]", "");
        if (digits.isEmpty()) {
            return 0f;
        }
        // 多个小数点必然不是有效数值
        if (digits.indexOf('.') != digits.lastIndexOf('.')) {
            return 0f;
        }
        try {
            float fps = Float.parseFloat(digits);
            return (fps > 0 && fps < Constants.Fps.MAX_VALID) ? fps : 0f;
        } catch (Exception e) {
            return 0f;
        }
    }

    /**
     * 展开路径里的 * 通配。Mali devfreq 的目录名带平台相关前缀
     * （比如 13000000.mali），没法写死。
     */
    private static String[] expandWildcard(String pattern) {
        int star = pattern.indexOf('*');
        int slashBefore = pattern.lastIndexOf('/', star);
        if (slashBefore <= 0) {
            return new String[]{pattern};
        }
        int slashAfter = pattern.indexOf('/', star);
        File parent = new File(pattern.substring(0, slashBefore));
        String segment = slashAfter < 0
                ? pattern.substring(slashBefore + 1)
                : pattern.substring(slashBefore + 1, slashAfter);
        String rest = slashAfter < 0 ? "" : pattern.substring(slashAfter);

        String[] names = parent.list();
        if (names == null) {
            return new String[0];
        }

        // segment 里的 * 转成 .*，其余部分按字面量转义
        StringBuilder regex = new StringBuilder();
        String[] literals = segment.split("\\*", -1);
        for (int i = 0; i < literals.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literals[i]));
        }
        Pattern compiled = Pattern.compile(regex.toString());

        List<String> matched = new ArrayList<>();
        for (String name : names) {
            if (!compiled.matcher(name).matches()) {
                continue;
            }
            String expanded = parent.getPath() + "/" + name + rest;
            if (expanded.indexOf('*') >= 0) {
                // rest 里还有通配，继续展开
                matched.addAll(Arrays.asList(expandWildcard(expanded)));
            } else {
                matched.add(expanded);
            }
        }
        return matched.toArray(new String[0]);
    }
    }