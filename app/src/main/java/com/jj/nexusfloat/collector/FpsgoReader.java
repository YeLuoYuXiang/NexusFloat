package com.jj.nexusfloat.collector;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.RootShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 联发科 FPSGO frame-stabilizer 的帧率读取。
 *
 * 要解决的问题：SystemUI 进程里用 android.view.Choreographer 数帧，数到的是屏幕
 * 刷新率，不是前台应用的渲染帧率。frame callback 跟着 VSync 走，只要一直 post
 * 就每个 VSync 回调一次，跟前台应用画了几帧没关系。所以 60Hz 屏上永远是 60，
 * 玩 30 帧的游戏、看 30 帧的视频一样显示 60。
 *
 * FPSGO 的 fpsgo_status 节点是内核按进程统计 queue buffer 帧率的，也是唯一能分清
 * 「哪个应用画了多少帧」的来源。输出长这样：
 *
 * tid	bufID	name		currentFPS	targetFPS	FPS_margin
 * 19158	0x900000001d3d	com.tencent.tmg	30		60		0
 * fstb_is_cam_active:0
 * dfps_ceiling:60
 *
 * 列数会随内核版本变（老版本没有 bufID），所以按表头里的 name / currentFPS 找列号，
 * 不写死下标。
 *
 * 这套节点只有联发科有，高通机型必然读不到，所以连续失败就退避重扫，
 * 不至于每秒白跑一遍 su 往返。
 */
final class FpsgoReader {

    /** 命中过的节点路径，命中之后就不用再遍历候选表了 */
    private String hit;
    /** 命中走的是哪条通道：true = root shell，false = 本进程直读 */
    private boolean hitViaRoot;
    /** 整张表都没命中的连续次数，用来退避重扫 */
    private int miss;
    /**
     * 还允许试几次 root 通道。
     *
     * RootShell.get() 在没有 root 的时候每次调用都会把四个 su 路径重试一遍
     * （每个都是一次 fork+exec）。SystemUI 是 system uid，从这儿 spawn su 在没 root
     * 的机器上还会刷一堆 SELinux 拒绝日志。所以只在最开始几轮探一下 root，
     * 之后不管成没成都只走直读。
     */
    private int rootAttemptsLeft = MAX_ROOT_ATTEMPTS;
    private static final int MAX_ROOT_ATTEMPTS = 3;

    /**
     * 读前台应用的渲染帧率。
     *
     * foregroundPkg 是前台包名，传 null 就退回「取表里最高的帧率」。
     * 前台应用当前没在渲染就返回 0；FPSGO 用不了的时候（非联发科、节点被禁用、
     * fstb 关了）返回 Constants.Fps.READ_FAILED。
     */
    float read(String foregroundPkg) {
        if (hit != null) {
            List<String> lines = readLines(hit, hitViaRoot);
            float fps = parse(lines, foregroundPkg);
            if (fps != Constants.Fps.READ_FAILED) {
                miss = 0;
                return fps;
            }
            // 节点没了或者 fstb 被关了，回全表重新探
            hit = null;
        }
        if (skipScan()) {
            miss++;
            return Constants.Fps.READ_FAILED;
        }

        // 先把两个路径的直读都试完再考虑 root。SystemUI 是 system uid，
        // 多数联发科 ROM 直读就够，能省下一趟 su
        boolean tryRoot = rootAttemptsLeft > 0;
        if (tryRoot) {
            rootAttemptsLeft--;
        }
        int channels = tryRoot ? 2 : 1;
        for (int c = 0; c < channels; c++) {
            boolean viaRoot = c == 1;
            // root 通道先用内建 read 批量探一遍哪些节点有内容，再只对有的 cat。
            // 11 个候选全 cat 一遍是 11 次 fork，可绝大多数机型上一个都没有，
            // 这正是要消掉的耗电来源。批量探测零 fork。
            String[] present = viaRoot
                    ? probePresent(Constants.Fps.FPSGO_PATHS) : Constants.Fps.FPSGO_PATHS;
            for (String path : present) {
                List<String> lines = readLines(path, viaRoot);
                float fps = parse(lines, foregroundPkg);
                if (fps != Constants.Fps.READ_FAILED) {
                    hit = path;
                    hitViaRoot = viaRoot;
                    miss = 0;
                    // 命中就说明这条通道能用，以后 hit 失效重扫时还能再试
                    rootAttemptsLeft = MAX_ROOT_ATTEMPTS;
                    return fps;
                }
            }
        }
        miss++;
        return Constants.Fps.READ_FAILED;
    }

    /**
     * 一趟管道往返筛出「存在且有内容」的节点，免得对不存在的路径一个个 cat。
     *
     * 判据是第一行非空：fpsgo_status 首行是表头，节点存在就有；
     * fstb_enable=0 时输出为空，这时候跳过，跟原来 readLines 返回 null 的处理是一样的。
     */
    private static String[] probePresent(String[] paths) {
        String[] first = RootShell.get().readMany(paths);
        if (first == null) {
            return new String[0];
        }
        List<String> present = new ArrayList<>(paths.length);
        for (int i = 0; i < paths.length; i++) {
            if (first[i] != null) {
                present.add(paths[i]);
            }
        }
        return present.toArray(new String[0]);
    }

    /** 退避策略跟 SysfsReader.skipScan 一样 */
    private boolean skipScan() {
        int grace = Constants.Config.SYSFS_MISS_GRACE;
        if (miss < grace) {
            return false;
        }
        return (miss - grace) % Constants.Config.SYSFS_RESCAN_EVERY_TICKS != 0;
    }

    /**
     * 从 fpsgo_status 的内容里取出目标帧率。
     *
     * 内容不是 fpsgo_status（没有表头）就返回 Constants.Fps.READ_FAILED。
     */
    private static float parse(List<String> lines, String foregroundPkg) {
        if (lines == null || lines.isEmpty()) {
            return Constants.Fps.READ_FAILED;
        }

        int nameCol = -1;
        int fpsCol = -1;
        /** 跟前台包名对上的那些行里，最高的帧率 */
        float matched = -1f;
        /** 全表最高帧率，前台包名没对上时兜底 */
        float any = -1f;

        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            String[] cols = line.trim().split("\\s+");
            if (cols.length < 3) {
                continue;
            }

            // 表头行，用来找列号。fstb_enable=0 时内核只返回空内容，
            // 所以能读到表头就说明 FPSGO 在正常干活
            if (line.contains(Constants.Fps.FPSGO_COL_FPS)) {
                for (int i = 0; i < cols.length; i++) {
                    if (Constants.Fps.FPSGO_COL_NAME.equals(cols[i])) {
                        nameCol = i;
                    } else if (Constants.Fps.FPSGO_COL_FPS.equals(cols[i])) {
                        fpsCol = i;
                    }
                }
                if (nameCol < 0 || fpsCol < 0) {
                    nameCol = Constants.Fps.FPSGO_DEFAULT_NAME_COL;
                    fpsCol = Constants.Fps.FPSGO_DEFAULT_FPS_COL;
                }
                continue;
            }

            if (nameCol < 0 || cols.length <= Math.max(nameCol, fpsCol)) {
                continue;
            }
            // 数据行首列是 tid，尾巴上的 dfps_ceiling:60 这类就这么排掉了
            if (!isNumeric(cols[0])) {
                continue;
            }
            // name 列不能是纯数字。内核用 %s 打印 proc_name，comm 要是空的
            // 这一列整体左移一位，这时候 cols[fpsCol] 就落到 targetFPS 上了，
            // 那是目标帧率、恒等于屏幕刷新率，等于把这个 bug 换个地方重现
            if (isNumeric(cols[nameCol])) {
                continue;
            }

            float fps = parseFps(cols[fpsCol]);
            if (fps < 0) {
                continue;
            }
            any = Math.max(any, fps);
            if (foregroundPkg != null && matches(cols[nameCol], foregroundPkg)) {
                matched = Math.max(matched, fps);
            }
        }

        if (nameCol < 0) {
            // 读到的不是 fpsgo_status（节点没有、内容为空、fstb 关了）
            return Constants.Fps.READ_FAILED;
        }
        if (matched >= 0) {
            return matched;
        }
        // 前台包名跟哪一行都对不上就退回全表最高帧率。
        // 会走到这儿的情况：拿不到前台包名；游戏用独立渲染进程，它的 comm
        // 跟包名没关系；启动器这类系统界面的进程名和包名对不上。
        // fstb 只跟正在 queue buffer 的进程，最高的那个基本就是当前在渲染的画面。
        if (any >= 0) {
            return any;
        }
        // 有表头但一行数据都没有：当前没应用在提交帧，画面是静止的
        return 0f;
    }

    /**
     * 判断内核给的进程名是不是指的这个包。
     *
     * 内核里的 proc_name 取自 task comm，受 TASK_COMM_LEN 限制最多 15 个可见字符，
     * 长包名会被截断（com.tencent.tmgp.sgame → com.tencent.tmg），
     * 所以除了完全相等，还得做前缀匹配。
     *
     * 前缀匹配也设了长度下限：包名基本都是 com. 开头，要是允许很短的进程名做前缀，
     * 一个叫 com 的进程就能匹配上所有应用。
     */
    private static boolean matches(String procName, String pkg) {
        if (procName == null || procName.isEmpty()) {
            return false;
        }
        if (procName.equals(pkg)) {
            return true;
        }
        int min = Constants.Fps.FPSGO_NAME_MIN_LEN;
        // 只有被截断的名字才需要前缀匹配，所以要求它够长
        return procName.length() >= min && pkg.startsWith(procName);
    }

    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /** 返回帧率，解析不了或者超出合理范围就给 -1 */
    private static float parseFps(String token) {
        try {
            float fps = Float.parseFloat(token);
            return (fps >= 0 && fps < Constants.Fps.MAX_VALID) ? fps : -1f;
        } catch (Exception e) {
            return -1f;
        }
    }

    /** 返回文件的所有行，读不到给 null */
    private static List<String> readLines(String path, boolean viaRoot) {
        if (viaRoot) {
            return RootShell.get().readLines(path);
        }
        File f = new File(path);
        if (!f.exists()) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
            return lines.isEmpty() ? null : lines;
        } catch (Exception e) {
            return null;
        }
    }
}
