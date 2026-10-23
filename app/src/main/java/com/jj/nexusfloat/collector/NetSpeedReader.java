package com.jj.nexusfloat.collector;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.RootShell;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * 网速采样（v1.8.14）。
 *
 * 数据源是 /proc/net/dev，各家内核都有这张表。格式是每个接口一行：
 *
 *   wlan0: 1234567 89 0 0 0 0 0 0 9876543 21 0 0 0 0 0 0
 *
 * 冒号后面 16 个数字，第 1 个是收到的字节数、第 9 个是发出的字节数，其余是包数、
 * 错误数之类。我们只取这两个字节数。
 *
 * 速率由两次采样差分算：内核没提供口径统一的「当前速率」，累计字节数是唯一
 * 所有内核都给的量。
 *
 * 只统计 wlan / rmnet / ccmni / eth / p2p 这几类接口，理由有两个：
 * 回环（lo）上有本机进程间通信，几十 MB/s 很平常，算进去网速就虚高；
 * VPN 的 tun 会把同一份流量再算一遍，导致数字翻倍。
 */
public final class NetSpeedReader {

    /** 上一次采样的累计字节数（收发合计）与时间戳 */
    private long lastTotalBytes = -1;
    private long lastSampleAtMs;

    /** 最近一次算出的速率（MB/s），用来在读不到时沿用 */
    private float lastSpeedMBps;

    /**
     * 采样一次，返回当前网速（MB/s）。
     *
     * 第一次调用没有基准，返回 0——差分至少需要两个点。
     * 读取失败也返回上一次的值，避免网速项在界面上闪成 0。
     */
    public float sample() {
        long now = System.currentTimeMillis();
        long total = readTotalBytes();
        if (total < 0) {
            return lastSpeedMBps;
        }
        if (lastTotalBytes < 0) {
            // 第一次只记基准
            lastTotalBytes = total;
            lastSampleAtMs = now;
            return 0f;
        }
        long deltaBytes = total - lastTotalBytes;
        long deltaMs = now - lastSampleAtMs;
        lastTotalBytes = total;
        lastSampleAtMs = now;

        // 计数器回绕（或者接口重建后归零）会算出负数，这种情况丢掉这一拍
        if (deltaBytes < 0 || deltaMs <= 0) {
            return lastSpeedMBps;
        }
        float mbps = deltaBytes / Constants.Net.BYTES_PER_MB * 1000f / deltaMs;
        lastSpeedMBps = mbps;
        return mbps;
    }

    /** 汇总所有对外接口的收发字节数；一个都读不到返回 -1 */
    private long readTotalBytes() {
        long total = readFromProc();
        if (total >= 0) {
            return total;
        }
        // 直读不到或读出来的全是 0，再走 root 试一次。
        //
        // 为什么要这条兜底：Android 10 起 netd 给每个 uid 做了网络统计隔离，
        // 普通进程读 /proc/net/dev 有可能只看到自己 namespace 的一小部分接口，
        // 表现就是网速永远是 0。root 里读到的是完整表。
        return readFromRoot();
    }

    /** 本进程直接读 /proc/net/dev；失败返回 -1 */
    private long readFromProc() {
        try (BufferedReader br = new BufferedReader(
                new FileReader(Constants.Net.DEV_STATS))) {
            return sumBytes(br);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 走 root 通道读同一份文件；失败返回 -1 */
    private long readFromRoot() {
        try {
            // RootShell 用的是 shell 内建 read，只取第一行，所以这里不能用它——
            // /proc/net/dev 是多行的，得整份读。用 execLines 拿到全部行
            java.util.List<String> lines = RootShell.get().execLines(
                    "cat " + Constants.Net.DEV_STATS, 64,
                    Constants.Config.EXEC_TIMEOUT_MS);
            if (lines == null || lines.isEmpty()) {
                return -1;
            }
            long total = 0;
            for (String line : lines) {
                total += bytesFromLine(line);
            }
            return total;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 逐行累加，返回合计 */
    private static long sumBytes(BufferedReader br) throws java.io.IOException {
        long total = 0;
        String line;
        while ((line = br.readLine()) != null) {
            total += bytesFromLine(line);
        }
        return total;
    }

    /** 解析一行 /proc/net/dev，不是要统计的接口就返回 0 */
    private static long bytesFromLine(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            // 两行表头没有冒号
            return 0;
        }
        String iface = line.substring(0, colon).trim();
        if (!isCountedIface(iface)) {
            return 0;
        }
        String[] fields = line.substring(colon + 1).trim().split("\\s+");
        // 需要至少 9 个字段才能拿到第 9 个（发送字节数）
        if (fields.length < 9) {
            return 0;
        }
        return parseLong(fields[0]) + parseLong(fields[8]);
    }

    /** 这个接口算不算对外流量，见类注释 */
    private static boolean isCountedIface(String iface) {
        if (iface.isEmpty() || Constants.Net.LOOPBACK.equals(iface)) {
            return false;
        }
        for (String prefix : Constants.Net.IFACE_PREFIXES) {
            if (iface.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 字段可能是空串或异常值，解析不了按 0 算 */
    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
