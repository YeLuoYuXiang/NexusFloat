package com.jj.nexusfloat.collector;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.RootShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联发科 GED KPI 的逐进程帧率读取。
 *
 * GED（GPU Extension Device）的 KPI 模块会统计每个 BufferQueue 的帧率，
 * 节点路径在 Constants.Fps.GED_KPI_PATHS 里。各版本的内容格式差得挺多，常见几种：
 *
 * 0x900000001d3d, com.tencent.tmgp, fps: 30, ...
 * 或
 * 0x900000001d3d, com.tencent.tmgp, 30, ...
 * 或
 * pid:12345, pkg:com.tencent.tmgp, fps:30
 *
 * 这里用正则从行里抠包名和帧率数字，不认固定列位。
 *
 * 只给联发科方案（APP 模式）用，是 FPSGO 之后的补充路径。非线程安全。
 */
final class GedKpiReader {

    /** 主正则：匹配包名（com. 或 org. 之类开头），后面跟 fps 数字 */
    private static final Pattern PKG_AND_FPS = Pattern.compile(
            "(?:^|[,;\\s]+)([a-zA-Z][a-zA-Z0-9._-]+\\.[a-zA-Z][a-zA-Z0-9._-]+)[^,;]*?[Ff][Pp][Ss]\\s*[:=]\\s*(\\d+)"
    );
    /** 备用正则：包名后面直接跟数字，没有 fps 关键字 */
    private static final Pattern PKG_AND_NUMBER = Pattern.compile(
            "(?:^|[,;\\s]+)([a-zA-Z][a-zA-Z0-9._-]+\\.[a-zA-Z][a-zA-Z0-9._-]+)[^,;]*?[,;]\\s*(\\d+)"
    );

    private final RootShell rootShell = RootShell.get();

    /**
     * 读指定包名的帧率。
     *
     * pkg 是包名，传 null 就没法归属，直接失败；preferRoot 决定优不优先走 root。
     * 找不到或者失败都返回 Constants.Fps.READ_FAILED。
     */
    float read(String pkg, boolean preferRoot) {
        if (pkg == null) {
            // GED 的输出是逐进程的，不知道包名就说不清哪一行是前台应用。
            // 硬猜一个（比如取最大值）在后台挂着播放器的时候会报出错的帧率，
            // 那不如返回失败让调用方往下走一级
            return Constants.Fps.READ_FAILED;
        }

        for (String path : candidates(preferRoot)) {
            float fps = readPath(path, pkg, preferRoot);
            if (fps > 0f && fps < Constants.Fps.MAX_VALID) {
                return fps;
            }
        }
        return Constants.Fps.READ_FAILED;
    }

    /**
     * root 通道下先用内建的 read 批量筛一遍哪些节点存在，再只对存在的那些 cat。
     *
     * 6 个候选路径在多数机型上一个都没有，逐个 cat 就是 6 次 fork+exec。
     * 批量探测零 fork，这是省电改造里的一块。
     */
    private String[] candidates(boolean preferRoot) {
        if (!preferRoot || rootShell.isDead()) {
            return Constants.Fps.GED_KPI_PATHS;
        }
        String[] first = rootShell.readMany(Constants.Fps.GED_KPI_PATHS);
        if (first == null) {
            return Constants.Fps.GED_KPI_PATHS;
        }
        List<String> present = new ArrayList<>(Constants.Fps.GED_KPI_PATHS.length);
        for (int i = 0; i < Constants.Fps.GED_KPI_PATHS.length; i++) {
            if (first[i] != null) {
                present.add(Constants.Fps.GED_KPI_PATHS[i]);
            }
        }
        return present.toArray(new String[0]);
    }

    private float readPath(String path, String pkg, boolean preferRoot) {
        List<String> lines;
        // 只有 root shell 确实起不来才跳过 root。不能因为某个节点不存在
        // 就认定「没有 root」：GED_KPI_PATHS 有 6 个候选，绝大多数机型上前几个
        // 都不存在，一读空就判死等于整条路永远用不了
        if (preferRoot && !rootShell.isDead()) {
            lines = rootShell.readLines(path);
            if (lines == null) {
                // 这个节点没内容，换下一个路径
                return Constants.Fps.READ_FAILED;
            }
        } else {
            // 直接读
            File f = new File(path);
            if (!f.exists() || !f.canRead()) {
                return Constants.Fps.READ_FAILED;
            }
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    float fps = parseLine(line, pkg);
                    if (fps > 0f) {
                        return fps;
                    }
                }
            } catch (Throwable ignored) {
            }
            return Constants.Fps.READ_FAILED;
        }

        // 走 root 拿回来的 lines
        if (lines == null || lines.isEmpty()) {
            return Constants.Fps.READ_FAILED;
        }
        for (String line : lines) {
            float fps = parseLine(line, pkg);
            if (fps > 0f) {
                return fps;
            }
        }
        return Constants.Fps.READ_FAILED;
    }

    /**
     * 解析一行，把包名和帧率抠出来。
     * 先用带 fps 关键字的主正则，不行再试备用正则。
     */
    private static float parseLine(String line, String pkg) {
        if (line == null || line.isEmpty()) {
            return Constants.Fps.READ_FAILED;
        }
        // 先试主正则
        Matcher m = PKG_AND_FPS.matcher(line);
        if (m.find()) {
            String foundPkg = m.group(1);
            if (foundPkg.equals(pkg) || (pkg != null && foundPkg.contains(pkg))) {
                try {
                    return Float.parseFloat(m.group(2));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // 不行再试备用正则
        m = PKG_AND_NUMBER.matcher(line);
        if (m.find()) {
            String foundPkg = m.group(1);
            if (foundPkg.equals(pkg) || (pkg != null && foundPkg.contains(pkg))) {
                try {
                    return Float.parseFloat(m.group(2));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Constants.Fps.READ_FAILED;
    }
}