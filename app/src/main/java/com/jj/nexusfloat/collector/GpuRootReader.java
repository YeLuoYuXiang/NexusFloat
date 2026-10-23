package com.jj.nexusfloat.collector;

import com.jj.nexusfloat.utils.RootShell;

/**
 * 在模块 App 进程里用常驻 root shell 读 GPU 频率和占用。
 *
 * 普通 FileReader 读不到这些节点，SELinux 把 untrusted_app 域挡在外面
 * （vendor_sysfs_kgsl 是高通那批），所以走 RootShell 到 su 域里读。
 * su 只 fork 一次，之后复用管道，比每拍 exec 一次 cat 省得多。
 *
 * 节点路径和解析都放在 SysfsReader，高通 KGSL、联发科 GED、通用 Mali devfreq 都认。
 */
public final class GpuRootReader {

    private GpuRootReader() {
    }

    /** 读当前 GPU 频率（MHz），读不到返回 0 */
    public static int readFreqMhz() {
        return SysfsReader.root().readGpuFreqMhz();
    }

    /** 读 GPU 占用百分比（0–100），节点读不到就返回 -1 */
    public static int readUsagePercent() {
        return SysfsReader.root().readGpuUsagePercent();
    }
}
