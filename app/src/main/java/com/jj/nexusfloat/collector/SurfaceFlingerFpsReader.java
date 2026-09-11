package com.jj.nexusfloat.collector;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 走 SurfaceFlinger 的 --latency 读真实渲染帧率，不依赖前台包名。
 *
 * SurfaceFlinger 给每个 layer 留了一圈 128 格的帧记录环（FrameTracker），
 * 按 layer 取就等于按窗口取。这里用 --list 枚举所有 layer，再对候选层逐个
 * --latency 探测，挑出最近一秒里出帧最多的那个锁定。不依赖
 * android.app.ActivityManager 拿前台包名，所以不受 ColorOS 之类 ROM 对
 * getRunningTasks 的限制影响。
 *
 * 适用范围有限：FrameTracker 从 Android 14 起被 FrameTimeline 取代，--latency
 * 参数还留着但不再填帧数据。实测天玑 1200 / Android 13 正常，天玑 9400 /
 * Android 16 上所有候选层的帧环都是空的。后者交给 TimeStatsFpsReader。
 *
 * 非线程安全，只该由采集线程调。
 */
final class SurfaceFlingerFpsReader {

    /** 参数不被支持连着判定了多少次 */
    private int unsupportedCount;
    /** 这一拍有没有出现过「latency 格式没认出来」，一拍最多加一次 */
    private boolean badFormatThisTick;
    /**
     * --latency 该用剥掉 #序号 的名字（TRUE）还是 --list 给的原名（FALSE），
     * null 表示还没确认。
     */
    private Boolean useStrippedName;

    /** 缓存的前台包名，用来判断有没有切应用。只是个优先级提示，不影响可用性 */
    private String candidatePkg;
    /** 当前候选 layer 列表（按优先级排，噪声已经滤掉） */
    private List<String> candidates = new ArrayList<>();
    /** 当前选定的 layer，null 表示还得从候选里挑 */
    private String activeLayer;
    /** 选定的 layer 连着多少轮没出帧，用来触发重挑 */
    private int idleTicks;
    /** 距离下次强制重扫还有几轮 */
    private int rescanTicks;

    /** 连续失败次数，用来退避 */
    private int miss;
    /**
     * 这条路有没有真的取到过帧数据。
     *
     * 用来区分「画面静止」和「候选层全选错了」：两者表现都是所有候选层都没新帧，
     * 但前者是完全正常的状态（看着桌面不动），后者才是故障。曾经成功过就按静止
     * 处理返回 0，从没成功过才报失败，让自动模式往下走。
     */
    private boolean everSucceeded;

    /** 上一次取数走没走 root，诊断标记要用 */
    private boolean lastViaRoot;
    /** 上一次取数走没走不带层名的兜底，诊断标记要用 */
    private boolean lastViaDisplay;
    /** 上一次失败卡在哪一步，见 Constants.Fps.SF_FAIL_*；成功时是空串 */
    private String lastFailure = "";

    boolean lastViaRoot() {
        return lastViaRoot;
    }

    /**
     * 上一次走没走不带层名的兜底。
     *
     * 这条路取的是合成器级的上屏时间，不保证严格等于前台应用的渲染帧率，
     * 所以在诊断标记里单独标出来，方便判断数值可不可信。
     */
    boolean lastViaDisplay() {
        return lastViaDisplay;
    }

    /**
     * 上一次失败卡在哪一步，诊断标记要拼在 x 后面。
     * 成功取到数的时候返回空串。
     */
    String lastFailure() {
        return lastFailure;
    }

    private float fail(String stage) {
        lastFailure = stage;
        return Constants.Fps.READ_FAILED;
    }

    /** 两条 dump 通道是不是都已经判死 */
    private boolean channelsExhausted() {
        return SfDumpChannel.get().exhausted();
    }

    /**
     * 读当前正在渲染的帧率。
     *
     * foregroundPkg 是前台包名，可以是 null，只用来优先挑同一个包的 layer；
     * preferRoot 决定优不优先走 root dumpsys（联发科方案下建议 true）。
     *
     * 返回帧率；画面静止时 0；这条路用不了时返回 Constants.Fps.READ_FAILED。
     */
    float read(String foregroundPkg, boolean preferRoot) {
        badFormatThisTick = false;
        if (unsupportedCount >= Constants.Fps.SF_CHANNEL_MAX_FAILURES) {
            return fail(Constants.Fps.SF_FAIL_UNSUPPORTED);
        }
        if (channelsExhausted()) {
            return fail(Constants.Fps.SF_FAIL_NO_CHANNEL);
        }
        if (skipScan()) {
            miss++;
            // 退避是失败的后果，不是原因。上一次真正的失败阶段要留着，
            // 不然诊断标记恒为 7，把根因整个盖住了（v1.5.7 实测如此）
            return fail(lastFailure.isEmpty()
                    ? Constants.Fps.SF_FAIL_BACKOFF : lastFailure);
        }

        float result = readInner(foregroundPkg, preferRoot);

        // 一拍最多加一次：pickActiveLayer 会连着探好几个候选，逐次累加一下就
        // 顶过上限了，等于复现 v1.5.6「一次误判永久废掉整条路」的 bug
        if (badFormatThisTick) {
            unsupportedCount++;
        } else if (result != Constants.Fps.READ_FAILED) {
            unsupportedCount = 0;
        }
        return result;
    }

    private float readInner(String foregroundPkg, boolean preferRoot) {
        // 切应用了：候选表作废（但不会让整条路失效，只是重扫）
        if (foregroundPkg != null && !foregroundPkg.equals(candidatePkg)) {
            candidatePkg = foregroundPkg;
            candidates = buildCandidates(foregroundPkg, preferRoot);
            activeLayer = null;
            idleTicks = 0;
            rescanTicks = Constants.Fps.SF_RESCAN_EVERY_TICKS;
        } else if (candidates.isEmpty() || rescanTicks <= 0) {
            // 候选为空或者到了重扫周期，重新构建
            candidates = buildCandidates(foregroundPkg, preferRoot);
            activeLayer = null;
            idleTicks = 0;
            rescanTicks = Constants.Fps.SF_RESCAN_EVERY_TICKS;
        } else {
            rescanTicks--;
        }

        if (candidates.isEmpty()) {
            miss++;
            // 区分「list 本身就没输出」和「输出了但过滤完没剩下」：
            // 前者是通道问题，后者是过滤规则问题，处理方向完全不一样
            return fail(lastFailure.isEmpty()
                    ? Constants.Fps.SF_FAIL_NO_CANDIDATE : lastFailure);
        }

        if (activeLayer == null) {
            activeLayer = pickActiveLayer(preferRoot);
            if (activeLayer == null) {
                // 所有候选层都没有新帧。这是「画面静止」的正常表现，不算故障，
                // 所以不累积 miss。v1.5.7 在这里累积，导致看着静止的桌面 3 秒
                // 就进退避、一分钟里只真试一次，进游戏的时候早就在退避期里了。
                if (everSucceeded) {
                    lastFailure = "";
                    return 0f;
                }
                // 从没成功过：逐层匹配这条路在本机不通（Android 14+ 的新显示
                // 前端下 --latency <name> 可能一个层都匹配不上），
                // 退到不带层名的合成器级帧时间戳
                return displayFallback(preferRoot, Constants.Fps.SF_FAIL_NO_ACTIVE);
            }
            idleTicks = 0;
        }

        Latency sample = readLatency(activeLayer, preferRoot);
        if (sample == null || !sample.layerFound) {
            // layer 没了（Activity 重建会让名字里的序号变），下一轮重新挑。
            // 这同样是常态，不累积 miss
            activeLayer = null;
            String stage = lastFailure.isEmpty()
                    ? Constants.Fps.SF_FAIL_LAYER_GONE : lastFailure;
            if (!everSucceeded) {
                return displayFallback(preferRoot, stage);
            }
            return fail(stage);
        }

        miss = 0;
        everSucceeded = true;
        lastViaDisplay = false;
        lastFailure = "";
        float fps = sample.fpsAt(System.nanoTime());

        if (fps <= 0f) {
            // 选错 layer 和画面真的静止，表现都是不出帧。连着几轮为 0 就换个 layer
            if (++idleTicks >= Constants.Fps.SF_REPICK_IDLE_TICKS) {
                idleTicks = 0;
                String next = pickActiveLayer(preferRoot);
                if (next != null && !next.equals(activeLayer)) {
                    activeLayer = next;
                }
            }
            return 0f;
        }
        idleTicks = 0;
        return fps < Constants.Fps.MAX_VALID ? fps : Constants.Fps.READ_FAILED;
    }

    /**
     * 逐层匹配失败后的兜底：用不带层名的 --latency。
     *
     * stage 是逐层匹配失败的阶段编号，兜底也失败就报这个。
     */
    private float displayFallback(boolean preferRoot, String stage) {
        float fps = readDisplayLatency(preferRoot);
        if (fps == Constants.Fps.READ_FAILED) {
            return fail(stage);
        }
        lastViaDisplay = true;
        lastFailure = "";
        return fps;
    }

    /**
     * 退避策略：只有结构性失败（通道不通、list 空、参数不支持）才累积 miss，
     * 「画面静止」「layer 消失」这类常态不算。
     *
     * 退避周期用 SF 自己的 SF_BACKOFF_EVERY_TICKS，不再沿用 sysfs 全表扫描的
     * 60 轮。SF 探测的代价小得多，退避一分钟只会让诊断标记一直停在退避态，
     * 看不到真正的失败原因。
     */
    private boolean skipScan() {
        int grace = Constants.Config.SYSFS_MISS_GRACE;
        if (miss < grace) {
            return false;
        }
        return (miss - grace) % Constants.Fps.SF_BACKOFF_EVERY_TICKS != 0;
    }

    /**
     * 构建候选 layer 列表。
     *
     * pkg 是要优先取的包名，可以为 null；preferRoot 决定优不优先 root。
     * 返回候选层列表（按优先级排序），最多 SF_MAX_CANDIDATES 个。
     */
    private List<String> buildCandidates(String pkg, boolean preferRoot) {
        List<String> listed = dump(new String[]{Constants.Fps.SF_ARG_LIST}, preferRoot);
        if (listed == null || listed.isEmpty()) {
            lastFailure = Constants.Fps.SF_FAIL_LIST_EMPTY;
            return new ArrayList<>();
        }
        if (isFullDump(listed)) {
            // SurfaceFlinger 不认 --list，把完整 dump 打出来了
            LogUtils.w("SurfaceFlinger does not support --list");
            unsupportedCount++;
            lastFailure = Constants.Fps.SF_FAIL_UNSUPPORTED;
            return new ArrayList<>();
        }
        unsupportedCount = 0;

        // 先按包名筛一遍，筛空了退回「所有非噪声层」。
        // 这是 v1.5.6 的关键修正：ColorOS 上前台包名可能取不到，或者跟 layer 名
        // 对不上，v1.5.4 遇到这种情况直接放弃整条 SF 路径，帧率于是退化成屏幕
        // 刷新率。宁可多探几个 layer，也不能因为对不上包名就交白卷。
        List<String> byPkg = collectLayers(listed, pkg);
        if (!byPkg.isEmpty()) {
            lastFailure = "";
            return byPkg;
        }
        List<String> all = collectLayers(listed, null);
        lastFailure = all.isEmpty() ? Constants.Fps.SF_FAIL_NO_CANDIDATE : "";
        return all;
    }

    /**
     * 从 --list 的输出里挑候选层。
     *
     * 排序决定了谁先被探测，直接影响能不能取到数：
     * 1. 含 SurfaceView 的层，游戏和播放器把画面画在这里；
     * 2. 含 / 的层，形如 包名/Activity名，是应用窗口层；
     * 3. 其余非噪声层。
     * v1.5.6 只区分了第一类，候选上限又只有 4，于是在 ColorOS 上前 4 名全是
     * WindowedMagnification 这类架构层，应用层一个都进不来。
     *
     * pkg 非 null 时只要名字里含这个包名的层。
     */
    private static List<String> collectLayers(List<String> listed, String pkg) {
        List<String> surfaceViews = new ArrayList<>();
        List<String> components = new ArrayList<>();
        List<String> plain = new ArrayList<>();

        for (int i = 0; i < listed.size(); i++) {
            String name = listed.get(i).trim();
            if (name.isEmpty() || isHeaderLine(name) || isNoiseLayer(name)) {
                continue;
            }
            if (pkg != null && !name.contains(pkg)) {
                continue;
            }
            if (name.contains(Constants.Fps.SF_HINT_SURFACE_VIEW)) {
                surfaceViews.add(name);
            } else if (name.indexOf(Constants.Fps.SF_HINT_COMPONENT) >= 0) {
                components.add(name);
            } else {
                plain.add(name);
            }
        }

        List<String> result = new ArrayList<>(surfaceViews);
        result.addAll(components);
        result.addAll(plain);
        // 限制候选数量：每个候选都要单独 dump 一次，太多会拖慢采集
        while (result.size() > Constants.Fps.SF_MAX_CANDIDATES) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    /**
     * 在候选 layer 里挑活跃的：最近一秒出帧最多的，平局取最新的那个。
     *
     * preferRoot 决定优不优先 root。
     * 返回选中的 layer 名；一个都没上过屏就返回 null。
     */
    private String pickActiveLayer(boolean preferRoot) {
        String best = null;
        int bestFrames = -1;
        long bestRecent = 0;
        long now = System.nanoTime();
        long since = now - Constants.Fps.SF_WINDOW_NS;

        for (String layer : candidates) {
            Latency sample = readLatency(layer, preferRoot);
            if (sample == null || !sample.layerFound || sample.count == 0) {
                continue;
            }
            int frames = sample.countAfter(since);
            if (frames > bestFrames
                    || (frames == bestFrames && sample.maxPresentNs > bestRecent)) {
                bestFrames = frames;
                bestRecent = sample.maxPresentNs;
                best = layer;
            }
        }
        return best;
    }

    /**
     * 读一个 layer 的帧记录。
     *
     * --latency <name> 是把参数跟 layer 名精确比较的，差一个字符就静默失配、
     * 只输出一行 vsync 周期。可各版本 Android 里 --list 打印的名字跟比较用的
     * 名字并不总是同一个：Android 12 起 layer 名本身就带 #序号，更早的版本不带，
     * 个别 ROM 还自己改过。所以两种形式都试，并记住哪种有效，省得每拍都试两次。
     *
     * layer 是 --list 给出的原始 layer 名；preferRoot 决定优不优先 root。
     * 返回 Latency 对象；失败或者 layer 不存在都返回 null。
     */
    private Latency readLatency(String layer, boolean preferRoot) {
        String stripped = stripSequence(layer);
        // 已经确认过用哪种形式，就不用再试另一种了
        if (useStrippedName != null) {
            return readLatencyExact(useStrippedName ? stripped : layer, preferRoot);
        }
        // 第一次：先试 --list 的原名（Android 12+ 的正确形式）
        Latency out = readLatencyExact(layer, preferRoot);
        if (out != null && out.layerFound) {
            useStrippedName = Boolean.FALSE;
            return out;
        }
        if (stripped.equals(layer)) {
            // 名字本来就不带序号，没有第二种形式可试
            return out;
        }
        Latency alt = readLatencyExact(stripped, preferRoot);
        if (alt != null && alt.layerFound) {
            useStrippedName = Boolean.TRUE;
            return alt;
        }
        // 两种都没匹配上：先不锁定形式，下一拍继续重试
        return out != null ? out : alt;
    }

    /** 用给定的名字精确查一次 */
    private Latency readLatencyExact(String layer, boolean preferRoot) {
        List<String> lines = dump(new String[]{Constants.Fps.SF_ARG_LATENCY, layer}, preferRoot);
        if (lines == null || lines.isEmpty()) {
            lastFailure = Constants.Fps.SF_FAIL_LAYER_GONE;
            return null;
        }
        if (!looksLikeLatencyDump(lines)) {
            LogUtils.w("Unrecognized --latency output for " + layer);
            // 只置标记，不在这里累加计数：pickActiveLayer 一轮会探好几个候选，
            // 每次都累加的话，一拍就能把计数顶过上限，等于复现 v1.5.6 那个
            // 「一次误判永久废掉整条路」的 bug。累加统一放在 read() 收尾时做一次。
            badFormatThisTick = true;
            lastFailure = Constants.Fps.SF_FAIL_BAD_FORMAT;
            return null;
        }
        return parseLatency(lines);
    }

    /**
     * 不带 layer 名跑 --latency，作为逐层匹配全失败后的最后一招。
     *
     * 按 AOSP 的实现，不给 layer 名只会打印一行 vsync 周期。但实测 ColorOS 在这种
     * 调用下会输出真实的三列帧时间戳（有用户提供的终端输出为证），说明这个 ROM
     * 改过 dumpStatsLocked。这一路取到的是合成器级别的上屏时间，不保证严格等于
     * 前台应用的渲染帧率，所以只在逐层匹配失败时启用，并且用独立的诊断标记 d
     * 标出来，方便判断它的数值可不可信。
     *
     * 返回帧率；用不了就返回 Constants.Fps.READ_FAILED。
     */
    private float readDisplayLatency(boolean preferRoot) {
        List<String> lines = dump(new String[]{Constants.Fps.SF_ARG_LATENCY}, preferRoot);
        if (lines == null || lines.isEmpty() || !looksLikeLatencyDump(lines)) {
            return Constants.Fps.READ_FAILED;
        }
        Latency sample = parseLatency(lines);
        if (!sample.layerFound || sample.count == 0) {
            return Constants.Fps.READ_FAILED;
        }
        float fps = sample.fpsAt(System.nanoTime());
        if (fps <= 0f) {
            return 0f;
        }
        return fps < Constants.Fps.MAX_VALID ? fps : Constants.Fps.READ_FAILED;
    }

    /**
     * 剥掉 --list 名字尾巴上的 #序号。
     *
     * 只剥最后一个 # 后面的纯数字部分：layer 名本身可能含 #，
     * 得尾部全是数字才算序号。
     */
    static String stripSequence(String name) {
        int hash = name.lastIndexOf(Constants.Fps.SF_SEQ_SEPARATOR);
        if (hash <= 0 || hash == name.length() - 1) {
            return name;
        }
        if (!isAllDigits(name.substring(hash + 1))) {
            return name;
        }
        return name.substring(0, hash);
    }

    /**
     * 判断输出是不是 --latency 的格式。
     *
     * 正常输出首行是单个纯数字的 vsync 周期，后面是三列纳秒时间戳。
     * 但不能只看首行：ColorOS 会在前面插一行 ---- TIME: ... ----，
     * v1.5.6 正是被这行顿住，把整条 SurfaceFlinger 路径误判成不可用。
     * 所以跳过输出头，在前几行里找到「纯数字行」或者「三列数字行」任意一种就认。
     */
    static boolean looksLikeLatencyDump(List<String> lines) {
        int probed = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || isHeaderLine(line)) {
                continue;
            }
            if (isAllDigits(line)) {
                return true;
            }
            // 有些 ROM 不打 vsync 周期行，直接给三列时间戳
            String[] cols = line.split("\\s+");
            if (cols.length >= 3 && isAllDigits(cols[0]) && isAllDigits(cols[1])) {
                return true;
            }
            if (++probed >= Constants.Fps.SF_FORMAT_PROBE_LINES) {
                return false;
            }
        }
        return false;
    }

    /**
     * 是不是 ROM 插进来的输出头，比如
     * ---- TIME: 2026-09-03 20:59:24.152 ----。
     */
    static boolean isHeaderLine(String line) {
        return line.startsWith(Constants.Fps.SF_HEADER_PREFIX);
    }

    /** 完整 dump 的开头标记，用来识别「参数没被认出来」 */
    private static boolean isFullDump(List<String> lines) {
        // 多留几行余量：ROM 插的输出头会把标记行往后顶
        int limit = Math.min(lines.size(), Constants.Fps.SF_FORMAT_PROBE_LINES);
        for (int i = 0; i < limit; i++) {
            if (lines.get(i).contains(Constants.Fps.SF_FULL_DUMP_MARKER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 排除肯定不出帧的层（容器、占位、系统架构层）。
     *
     * 大小写不敏感：ColorOS 实际输出的是 "Dim layer"，
     * v1.5.6 的表里写的是 "Dim Layer"，于是没过滤掉。
     */
    static boolean isNoiseLayer(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String hint : Constants.Fps.SF_NOISE_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** --latency 的解析结果 */
    static final class Latency {
        boolean layerFound;
        long[] presentNs = new long[0];
        int count;
        long maxPresentNs;
        long minPresentNs;

        int countAfter(long afterNs) {
            int n = 0;
            for (long t : presentNs) {
                if (t > afterNs) {
                    n++;
                }
            }
            return n;
        }

        /**
         * 算 now 这个时刻的帧率。
         *
         * 默认用窗口内的帧数；环装满了而且最老的帧还在窗口内，就改用时间跨度。
         */
        float fpsAt(long now) {
            long since = now - Constants.Fps.SF_WINDOW_NS;
            if (count >= Constants.Fps.SF_RING_FULL_MIN && minPresentNs > since) {
                long span = maxPresentNs - minPresentNs;
                if (count < 2 || span <= 0) {
                    return 0f;
                }
                return (count - 1) * 1_000_000_000f / span;
            }
            return countAfter(since) * 1_000_000_000f / Constants.Fps.SF_WINDOW_NS;
        }
    }

    /**
     * 解析 --latency 输出，取第二列 actualPresentTime。
     *
     * 三列分别是 desiredPresentTime、actualPresentTime、frameReadyTime。
     * 实测 ColorOS 上第一、三列常是 9223372036854775807（INT64_MAX，表示这帧没指定
     * 该时刻），第二列才是真正的上屏时间，跟 System.nanoTime() 同为 CLOCK_MONOTONIC
     * 基准。
     */
    static Latency parseLatency(List<String> lines) {
        Latency out = new Latency();
        if (lines == null || lines.isEmpty()) {
            return out;
        }
        List<Long> times = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || isHeaderLine(line)) {
                continue;
            }
            String[] cols = line.split("\\s+");
            if (cols.length < 3) {
                // vsync 周期行，跳过
                continue;
            }
            long present;
            try {
                present = Long.parseLong(cols[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            out.layerFound = true;
            if (present <= 0 || present == Long.MAX_VALUE) {
                continue;
            }
            times.add(present);
        }

        out.count = times.size();
        out.presentNs = new long[out.count];
        for (int i = 0; i < out.count; i++) {
            long t = times.get(i);
            out.presentNs[i] = t;
            if (t > out.maxPresentNs) {
                out.maxPresentNs = t;
            }
            if (out.minPresentNs == 0 || t < out.minPresentNs) {
                out.minPresentNs = t;
            }
        }
        return out;
    }

    /**
     * 取 SurfaceFlinger 的 dump 内容，通道逻辑见 SfDumpChannel。
     */
    private List<String> dump(String[] args, boolean preferRoot) {
        SfDumpChannel channel = SfDumpChannel.get();
        List<String> out = channel.dump(args, preferRoot, Constants.Fps.SF_DUMP_MAX_LINES);
        lastViaRoot = channel.lastViaRoot();
        return out;
    }

    /** 采集停了就释放 dump 线程 */
    synchronized void shutdown() {
        SfDumpChannel.get().shutdown();
    }
}