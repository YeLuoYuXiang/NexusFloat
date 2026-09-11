package com.jj.nexusfloat.collector;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 靠 SurfaceFlinger TimeStats 读真实渲染帧率。
 *
 * --latency 背后是 FrameTracker，Android 14 起被 FrameTimeline 取代了，参数还留着
 * 但不再填帧数据。实测天玑 1200 / Android 13 能取到值，同一份代码在天玑 9400 /
 * Android 16 上所有候选层的帧环都是空的。TimeStats 是官方的替代路径：它按 layer
 * 累计 totalFrames，两次采样的帧数差除以时间差就是真实帧率。
 *
 * v1.6.4 修过一个 bug：帧率恒为 0。
 * 差分是按 layer 名做 key 的，而 layer 名尾巴上带 #序号：
 *
 * b89cf9a SurfaceView[com.miHoYo.hkrpg/...](BLAST)#292
 * b89cf9a SurfaceView[com.miHoYo.hkrpg/...](BLAST)#388
 *
 * 同一个 SurfaceView 在 Activity 重建或者 buffer 重分配之后序号就变了，于是每轮
 * 都被当成「新层、没基线、跳过」，永远算不出增量，这才是 0t 的直接原因。
 * 现在 key 先把 #序号 剥掉再比，同一层跨序号还能对上。
 *
 * 剥序号有个副作用要处理：同名不同序号的层会并成一个 key（上面例子的 #292 和 #388）。
 * 取累计值最大的那个，因为老序号的层往往已经不出帧、累计值停在那儿了，新序号才是
 * 活跃的。但两者谁的累计值大，得看存续时长，没法先验判断。取最大值再配合下面的
 * 「负增量归零」是安全的：合并结果只要单调不减，差分就有意义。
 *
 * 其他几个点：
 * 得先用 -enable 打开统计，默认是关的；
 * 它只给累计值，必须自己存基线做差分，所以第一次调用只能建基线；
 * -maxlayers N 是按累计帧数排名取前 N 个，不是按当前活跃度。实测排名靠前的位置会被
 * Wallpaper BBQ wrapper、launcher、StatusBar 这些静止层占满，所以启用的时候先清一次
 * 统计、取 32 层。
 *
 * 这里不做「哪个 layer 属于前台应用」的判断：ColorOS 上包名经常取不到。
 * 改成取帧数增量最大的 layer，正在动的那个就是用户在看的那个。
 *
 * 非线程安全，只该由采集线程调。
 */
final class TimeStatsFpsReader {

    /** 上一次采样：layer 名 → 累计总帧数 */
    private Map<String, Long> baseline;
    /** 上一次采样的时刻（System.nanoTime()） */
    private long baselineNs;

    /** 试没试过打开统计 */
    private boolean enableTried;

    /**
     * 上一拍的前台包名，用来检测应用切换。
     *
     * 切了应用之后新层的累计帧数从 0 起，挤不进按累计排名的前 N 名，表现就是帧率
     * 恒为 0。检测到切换立刻重清，比等 zeroTicks 攒够快得多。包名取不到时
     * （ColorOS 常见）这条优化就失效了，还有 zeroTicks 兜着。
     */
    private String lastPkg;

    /**
     * 连着多少拍所有 layer 的增量都是 0。
     *
     * 用来区分「画面真静止」和「排名被陈旧的高累计层占满」。两者表现一样，
     * 但后者不会自己恢复，得重清统计。
     */
    private int zeroTicks;

    /** 连续失败几次了，到上限就判定本机不支持 */
    private int failures;

    /** 上一次取数走没走 root */
    private boolean lastViaRoot;
    /** 上一次失败卡在哪一步，见 Constants.Fps.SF_FAIL_*；成功时是空串 */
    private String lastFailure = "";

    boolean lastViaRoot() {
        return lastViaRoot;
    }

    /** 本机是不是已经判定不支持 TimeStats 了 */
    boolean unavailable() {
        return failures >= Constants.Fps.SF_CHANNEL_MAX_FAILURES;
    }

    /**
     * 读当前帧率。
     *
     * 第一次调用只建基线，返回 Constants.Fps.READ_FAILED，从第二次起才有值。
     * 两次采样间隔不到 SF_TIMESTATS_MIN_SPAN_NS 也一样返回失败，但基线留着，下一拍再试。
     *
     * foregroundPkg 是前台包名，只用来检测应用切换，可以是 null；preferRoot 决定
     * 优不优先走 root dumpsys。
     *
     * 返回帧率；画面静止时是 0；用不了或者还在预热阶段返回 Constants.Fps.READ_FAILED。
     */
    float read(String foregroundPkg, boolean preferRoot) {
        if (unavailable()) {
            lastFailure = Constants.Fps.SF_FAIL_NO_TIMESTATS;
            return Constants.Fps.READ_FAILED;
        }
        if (!enableTried) {
            enableTried = true;
            lastPkg = foregroundPkg;
            if (!enable(preferRoot)) {
                // 打不开也照样试着读，有些 ROM 出厂就是开的
                LogUtils.w("TimeStats enable failed, trying to read anyway");
            }
        } else if (foregroundPkg != null && !foregroundPkg.equals(lastPkg)) {
            // 换了前台应用：只丢基线，不清统计。
            //
            // v1.6.4 在这里清了统计，结果每次切应用都制造一个「dump 里没有任何
            // layer 段」的窗口，解析出空 Map 被当成失败（标记 x8），还会累加
            // failures，攒到 5 次就把整条路判死。这就是用户报的「切后台会 x8」。
            //
            // 清统计的唯一目的是解决排名被陈旧层占满，而剥掉 #序号 之后同一层
            // 跨序号已经能对上了，排名失效的概率大降；真遇到还有下面的 zeroTicks
            // 兜底。丢基线就够了：新层下一拍就能算出增量。
            lastPkg = foregroundPkg;
            baseline = null;
            baselineNs = 0;
            zeroTicks = 0;
            lastFailure = Constants.Fps.SF_FAIL_TIMESTATS_WARMUP;
            return Constants.Fps.READ_FAILED;
        }

        List<String> lines = sampleLines(preferRoot);
        if (lines == null) {
            // dump 命令本身没跑通（通道不可用），这才是真失败
            failures++;
            lastFailure = Constants.Fps.SF_FAIL_NO_TIMESTATS;
            return Constants.Fps.READ_FAILED;
        }
        Map<String, Long> now = parse(lines);
        if (now.isEmpty()) {
            // 命令跑通了但还没有 layer 段：刚 -clear 过，或者此刻确实没有层在出帧。
            // 这是正常状态，不能累加 failures，否则清一次统计就把这条路判死了
            lastFailure = Constants.Fps.SF_FAIL_TIMESTATS_WARMUP;
            return Constants.Fps.READ_FAILED;
        }
        failures = 0;
        long nowNs = System.nanoTime();

        Map<String, Long> prev = baseline;
        long span = nowNs - baselineNs;

        // 跨度过大说明中间息屏了或者长时间没采集，旧基线算出来的是平均值，弃用
        if (prev == null || span > Constants.Fps.SF_TIMESTATS_MAX_SPAN_NS) {
            baseline = now;
            baselineNs = nowNs;
            lastFailure = Constants.Fps.SF_FAIL_TIMESTATS_WARMUP;
            return Constants.Fps.READ_FAILED;
        }
        // 跨度不够：基线留着继续等，别把基线换成 now，不然永远凑不够
        if (span < Constants.Fps.SF_TIMESTATS_MIN_SPAN_NS) {
            lastFailure = Constants.Fps.SF_FAIL_TIMESTATS_WARMUP;
            return Constants.Fps.READ_FAILED;
        }

        long bestDelta = maxDelta(prev, now, foregroundPkg, span);
        baseline = now;
        baselineNs = nowNs;
        lastFailure = "";

        if (bestDelta <= 0) {
            // 所有 layer 都没有新帧。画面静止时这是正常的，但排名被陈旧的高累计层
            // 占满时表现完全一样，而且不会自己恢复，所以给足宽限后重清。
            // 监视条自己的 NexusFloatOverlay 每秒一帧也在统计里，正常工作时增量
            // 不该长期为 0，真的一直是 0 就说明排名有问题
            if (++zeroTicks >= Constants.Fps.SF_TIMESTATS_ZERO_TICKS_BEFORE_CLEAR) {
                zeroTicks = 0;
                clearAndRebase(preferRoot);
            }
            return 0f;
        }
        zeroTicks = 0;
        float fps = bestDelta * 1_000_000_000f / span;
        return fps < Constants.Fps.MAX_VALID ? fps : Constants.Fps.READ_FAILED;
    }

    /**
     * 两次采样之间的帧数增量，取「最像前台应用」的那个 layer。
     *
     * v1.7.0 修过一个 bug：帧率掉到个位数。
     * v1.6.x 是在所有 layer 里取增量最大值，不排除低频层。当游戏层因为掉出 top-N
     * 排名、或者跨 seq 对不上而拿不到增量时，唯一还有有效增量的就剩状态栏、通知栏
     * 以及监视条自己的 NexusFloatOverlay 了，它每秒出 1 帧。于是帧率显示成 1~5，
     * 看着像掉帧，实际是量错了对象。
     *
     * 三层筛选：
     * 1. 排除噪声层（isNoiseLayer），壁纸、状态栏、容器层这些；
     * 2. 排除监视条自己，它必然每秒 1 帧，是最容易冒充的干扰源；
     * 3. 前台包名匹配的层优先。匹配上就直接用它，不跟别的层比大小：前台应用的帧率
     *    才是用户要看的，哪怕它此刻比某个后台层低。
     *
     * 都没匹配上（包名取不到，ColorOS 常见）才退回「剩下这些层里取最大」，
     * 这时候噪声层已经被前两条剔掉了，不会再落到每秒 1 帧的层上。
     *
     * 还有一条下限：换算成帧率低于 SF_TIMESTATS_MIN_FPS 的增量直接忽略。真实的交互
     * 画面不会长期低于这个值，而系统 UI 层的心跳恰好落在这个区间，宁可报 0（静止）
     * 也别报一个明显错的个位数。
     *
     * 新冒出来的 layer 没有基线，本轮跳过，下一轮就能算。不拿它的累计值当增量：
     * 那是它从创建到现在的总帧数，会算出远超实际的帧率。
     *
     * 负增量归零：清零统计后累计值变小会让差变成负的，这种值没意义。
     *
     * span 是采样跨度（纳秒），用来把增量换算成帧率做下限判断。
     */
    private static long maxDelta(Map<String, Long> prev, Map<String, Long> now,
                                 String foregroundPkg, long span) {
        // 按剥掉 #序号 的名字把增量求和：同一个逻辑层可能同时存在多个序号
        // （Activity 重建、buffer 重分配），老序号已经静止、新序号在出帧。
        // v1.6.x 是在 parse() 里按累计值取 max 合并的，那是错的：老序号累计
        // 50000 帧且不再变化、新序号从 0 开始的话，max 会一直停在 50000，
        // 增量恒为 0，直到新层超过它才突然跳一下。求和才是这个逻辑层的真实帧数
        Map<String, Long> merged = new HashMap<>();
        for (Map.Entry<String, Long> e : now.entrySet()) {
            Long before = prev.get(e.getKey());
            if (before == null) {
                continue;
            }
            long delta = e.getValue() - before;
            if (delta <= 0) {
                continue;
            }
            String key = SurfaceFlingerFpsReader.stripSequence(e.getKey());
            Long acc = merged.get(key);
            merged.put(key, acc == null ? delta : acc + delta);
        }

        long minDelta = (long) Math.ceil(
                Constants.Fps.SF_TIMESTATS_MIN_FPS * span / 1_000_000_000f);
        long best = 0;
        for (Map.Entry<String, Long> e : merged.entrySet()) {
            String name = e.getKey();
            long delta = e.getValue();
            if (delta < minDelta) {
                continue;
            }
            // 监视条自己每秒 1 帧，必须排掉，不然它就是那个「个位数」
            if (name.contains(Constants.Overlay.WINDOW_TITLE)) {
                continue;
            }
            if (SurfaceFlingerFpsReader.isNoiseLayer(name)) {
                continue;
            }
            // 前台包名命中了就直接采用，不跟别的层比大小
            if (foregroundPkg != null && !foregroundPkg.isEmpty()
                    && name.contains(foregroundPkg)) {
                return delta;
            }
            if (delta > best) {
                best = delta;
            }
        }
        return best;
    }

    /**
     * 清空 TimeStats 并丢掉基线。
     *
     * 清零之后累计值全归零，旧基线的值全都偏大，做差会得到负数，所以必须同时丢掉
     * 基线，下一拍重建。
     */
    private void clearAndRebase(boolean preferRoot) {
        dump(new String[]{
                Constants.Fps.SF_ARG_TIMESTATS,
                Constants.Fps.SF_TIMESTATS_CLEAR}, preferRoot);
        baseline = null;
        baselineNs = 0;
    }

    /** 上一次失败卡在哪一步，成功的时候是空串 */
    String lastFailure() {
        return lastFailure;
    }

    /**
     * 基线失效时调（采集停了、切 FPS 来源），下一次读取重新建基线。
     *
     * 顺便也重置 enableTried，这样恢复采集时会重新 -enable 并 -clear。
     * 停采期间前台应用大概率已经换了，旧排名不再有效。
     */
    void reset() {
        baseline = null;
        baselineNs = 0;
        zeroTicks = 0;
        enableTried = false;
        lastPkg = null;
    }

    /**
     * 打开 TimeStats 统计，顺手把已经累计的数据清空。
     *
     * -clear 是关键一步：-dump 按累计帧数排名取前 N 个 layer，ROM 要是出厂就开着
     * 统计，前几名会被开机以来累计了几小时的 launcher / SystemUI 占满，而它们在全屏
     * 游戏下完全静止。于是无论前台怎么渲染，增量最大值恒为 0。这就是 v1.6.2
     * 显示 0t 的原因。
     *
     * 返回 -enable 跑通了没。
     */
    private boolean enable(boolean preferRoot) {
        List<String> out = dump(new String[]{
                Constants.Fps.SF_ARG_TIMESTATS,
                Constants.Fps.SF_TIMESTATS_ENABLE}, preferRoot);
        // 这条命令正常情况下没有输出，能跑通（非 null）就算成功
        boolean ok = out != null;
        // 不管 enable 报没报成功都清一次：ROM 可能出厂就开着，
        // 这时候 enable 未必有回应，但陈旧的累计数据照样会污染排名
        clearAndRebase(preferRoot);
        return ok;
    }

    /**
     * 取一次统计的原始输出行。
     *
     * 返回 null 只表示 dump 命令没跑通（通道不可用）。跑通了但没有 layer 段是另一
     * 回事，那由调用方按「预热」处理，不能算失败。
     */
    private List<String> sampleLines(boolean preferRoot) {
        List<String> lines = dump(new String[]{
                Constants.Fps.SF_ARG_TIMESTATS,
                Constants.Fps.SF_TIMESTATS_DUMP,
                Constants.Fps.SF_TIMESTATS_MAXLAYERS,
                Constants.Fps.SF_TIMESTATS_LAYER_COUNT}, preferRoot);
        return (lines == null || lines.isEmpty()) ? null : lines;
    }

    /**
     * 解析 TimeStats 的输出。
     *
     * 格式大致是这样：
     *
     * SurfaceFlinger TimeStats:
     * totalFrames = 999999      <- 全局字段，必须跳过
     * ...
     * layerName = com.foo/com.foo.Main#0
     * packageName = com.foo
     * totalFrames = 1234        <- 这才是该层的
     * ...
     *
     * layerName 跟它后面第一个 totalFrames 配对。出现在任何 layerName 之前的
     * totalFrames 属于全局段，会被丢掉，不然它会被当成某个层的数据，算出荒谬的帧率。
     *
     * v1.7.0：这里保留完整的层名，不在这一步合并。
     * v1.6.4 是在这里剥掉 #序号 并按累计值取 max 合并同名层的，那是错的：老序号累计
     * 50000 帧且已经静止、新序号从 0 开始活跃的话，max 会一直停在 50000，做差得 0，
     * 直到新层累计超过它才突然跳一下。
     * 现在保留完整名（含序号）逐个存，差分在 maxDelta 里做完之后再按剥掉序号的名字
     * 求增量之和：静止的老序号贡献 0，活跃的新序号贡献真实增量，和才是这个逻辑层的
     * 真实帧数。
     */
    static Map<String, Long> parse(List<String> lines) {
        Map<String, Long> out = new HashMap<>();
        String layer = null;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            int at = line.indexOf(Constants.Fps.SF_TIMESTATS_LAYER_MARKER);
            if (at >= 0) {
                // 保留完整名（含 #序号），合并留到差分之后再做
                layer = line.substring(at
                        + Constants.Fps.SF_TIMESTATS_LAYER_MARKER.length()).trim();
                continue;
            }
            if (layer == null) {
                continue;
            }
            at = line.indexOf(Constants.Fps.SF_TIMESTATS_TOTAL_FRAMES);
            if (at < 0) {
                continue;
            }
            String value = line.substring(at
                    + Constants.Fps.SF_TIMESTATS_TOTAL_FRAMES.length()).trim();
            // 这个字段后面可能还跟着别的内容（单位之类），只取开头的数字
            int end = 0;
            while (end < value.length() && Character.isDigit(value.charAt(end))) {
                end++;
            }
            if (end == 0) {
                continue;
            }
            try {
                out.put(layer, Long.parseLong(value.substring(0, end)));
            } catch (NumberFormatException ignored) {
            }
            // 一个 layer 段只取第一个 totalFrames
            layer = null;
        }
        return out;
    }

    /**
     * 跑一次 dumpsys。TimeStats 的输出比 --latency 长，行数上限单独放宽。
     */
    private List<String> dump(String[] args, boolean preferRoot) {
        SfDumpChannel channel = SfDumpChannel.get();
        List<String> out = channel.dump(args, preferRoot,
                Constants.Fps.SF_TIMESTATS_MAX_LINES);
        lastViaRoot = channel.lastViaRoot();
        return out;
    }
}
