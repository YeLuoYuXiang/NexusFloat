package com.jj.nexusfloat.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 常驻 root shell，相当于一个进程守护。
 *
 * 起一个长期活着的 su 进程，之后 stdin/stdout 管道复用，省掉每次读 sysfs 都要
 * fork+exec 的开销，同时保留 su/Magisk 那边的 SELinux 域权限，这样
 * untrusted_app 读不到的 vendor_sysfs_kgsl 也能读到。
 *
 * 为什么不用 cat（v1.6.0 的核心改动）：
 * 常驻 su 只省掉了 su 自己的 fork，可 Android 上的 cat 是 toybox 的软链，
 * 每读一个节点还是得 fork+exec 一次 toybox。一轮采集要扫十几个候选路径，
 * 等于每秒十几次进程创建，耗电主要就出在这儿。
 *
 * 改成用 shell 内建的 read：read -r v < path 全程在 shell 进程里完成，零 fork。
 * 再配合 readMany 把一轮里所有路径并成一次管道往返，进程创建次数从
 * 「每节点一次」降到 0，管道往返也从「每节点一次」降到每轮一次。
 *
 * dumpsys 这种必须起子进程的命令还是走 execLines，fork 躲不掉，但每轮最多一次。
 *
 * 线程安全靠内部的 synchronized 把命令执行串起来。
 */
public final class RootShell {

    /**
     * 这里面不能出现任何 shell 特殊字符（&lt; &gt; | &amp; ; ` $ ( ) { } [ ] * ? !），
     * 不然会被 shell 当成重定向或操作符解析掉。
     */
    private static final String SENTINEL = "ROOTSHELL_DONE_OK";

    /**
     * 批量读取时每行的字段分隔符。
     *
     * 用竖线不用冒号、等号：sysfs 的值里冒号（时间戳）和等号（键值对）太常见了，
     * 竖线在数值型节点里基本见不着。真碰上了也不怕，解析只按第一个分隔符切，
     * 后面原样留着，不会错位。
     */
    private static final char FIELD_SEP = '|';

    /** 批量读取用的临时变量名，加个前缀免得撞上 shell 里已有的变量 */
    private static final String TMP_VAR = "__nf_v";
    /** 通配路径展开时的循环变量名 */
    private static final String GLOB_VAR = "__nf_g";
    /**
     * 通配展开一次最多认多少行。
     *
     * 面板候选表里最宽的模式是 /sys/devices/platform/soc/*&#47;drm/*&#47;*，
     * 展开出几十项很正常，上百项就说明模式写得太宽了。宁可截断，也别让 shell
     * 的输出把哨兵行淹掉。
     */
    private static final int GLOB_MAX_LINES = 96;

    private static final String[] SU_CANDIDATES = {
            "su", "/system/bin/su", "/sbin/su", "/system/xbin/su"
    };
    private static final long CMD_TIMEOUT_MS = 600;
    /**
     * 多行读取的行数上限。fpsgo_status 正常只有个位数行（正在渲染的进程数），
     * 设个上限纯粹是防异常内核输出把采集线程的内存和耗时拖爆。
     */
    private static final int MAX_LINES = 64;

    /**
     * 一次批量读取最多几条路径。
     *
     * 整条命令是一行写进管道的，太长会顶到管道缓冲（Linux 默认 64KB）上，
     * 写不进去就卡住。按每条路径 80 字节左右算，64 条约 5KB，余量够。
     */
    private static final int MAX_BATCH = 64;

    private static volatile RootShell sInstance;

    private Process process;
    private PrintWriter writer;
    private BufferedReader reader;
    private volatile boolean dead = false;

    private RootShell() {}

    public static RootShell get() {
        RootShell inst = sInstance;
        if (inst == null || inst.dead) {
            synchronized (RootShell.class) {
                inst = sInstance;
                if (inst == null || inst.dead) {
                    sInstance = inst = start();
                }
            }
        }
        return inst;
    }

    private static RootShell start() {
        for (String su : SU_CANDIDATES) {
            try {
                Process p = new ProcessBuilder(su)
                        .redirectErrorStream(false)
                        .start();
                RootShell shell = new RootShell();
                shell.process = p;
                shell.writer = new PrintWriter(
                        new OutputStreamWriter(p.getOutputStream(), "UTF-8"), true);
                shell.reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), "UTF-8"));
                if (shell.probe()) {
                    return shell;
                }
                p.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
        RootShell dead = new RootShell();
        dead.dead = true;
        return dead;
    }

    private boolean probe() {
        String result = exec("echo probe_ok");
        return "probe_ok".equals(result);
    }

    /**
     * 跑一条命令，只返回第一行；失败或超时返回 null。
     *
     * 协议：命令发完之后紧接着发一句 echo SENTINEL，用 SENTINEL 这一行当输出结束标记。
     */
    public synchronized String exec(String command) {
        List<String> lines = execLines(command, 1);
        return (lines == null || lines.isEmpty()) ? null : lines.get(0);
    }

    /**
     * 跑一条命令，返回全部输出行；失败或超时返回 null。
     *
     * 协议同 exec。maxLines 之后的行照样读完（必须读到 SENTINEL，不然残留内容
     * 会串到下一条命令的结果里），只是不留下来。
     *
     * maxLines 是最多保留几行，多出来的丢掉；timeoutMs 是超时上限，
     * dumpsys 这类要 fork 子进程的命令比读 sysfs 慢得多，所以放开给调用方自己定。
     */
    public synchronized List<String> execLines(String command, int maxLines, long timeoutMs) {
        if (dead) return null;
        try {
            writer.println(command);
            writer.println("echo " + SENTINEL);

            long deadline = System.currentTimeMillis() + timeoutMs;
            List<String> collected = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (System.currentTimeMillis() > deadline) {
                    markDead();
                    return null;
                }
                if (SENTINEL.equals(line)) {
                    return collected;
                }
                if (collected.size() < maxLines && !line.isEmpty()) {
                    collected.add(line);
                }
            }
            markDead();
            return null;
        } catch (IOException e) {
            markDead();
            return null;
        }
    }

    public List<String> execLines(String command, int maxLines) {
        return execLines(command, maxLines, CMD_TIMEOUT_MS);
    }

    /**
     * 读 sysfs / procfs 文件的第一行，读不到返回 null。
     *
     * 用 shell 内建的 read 而不是 cat，不 fork 任何子进程。
     */
    public String readFirstLine(String path) {
        String[] values = readMany(new String[]{path});
        return values == null ? null : values[0];
    }

    /**
     * 一次管道往返读完多个节点的第一行。
     *
     * v1.6.0 省电的关键就在这儿：原先每个路径一次 cat（一次 fork）加一次管道往返，
     * 扫十几个候选就是十几次进程创建。现在整轮并成一条命令，全用 shell 内建 read
     * 完成，进程创建次数是 0。
     *
     * 输出协议是每个路径一行「序号|值」。带序号是因为读失败的路径也必须占一行，
     * 否则某个节点不存在时后面的值会整体错位，把 GPU 频率当成温度用。
     *
     * paths 是要读的路径，超过 MAX_BATCH 的部分自动分批；返回跟 paths 等长的数组，
     * 读不到的位置是 null，整体失败返回 null。
     */
    public String[] readMany(String[] paths) {
        if (paths == null || paths.length == 0) {
            return new String[0];
        }
        if (paths.length <= MAX_BATCH) {
            return readBatch(paths);
        }
        // 分批：命令行太长会顶到管道缓冲上，写不进去
        String[] out = new String[paths.length];
        for (int from = 0; from < paths.length; from += MAX_BATCH) {
            int to = Math.min(from + MAX_BATCH, paths.length);
            String[] part = readBatch(Arrays.copyOfRange(paths, from, to));
            if (part == null) {
                return null;
            }
            System.arraycopy(part, 0, out, from, part.length);
        }
        return out;
    }

    /** 读一批，调用前得保证 paths.length 不超过 MAX_BATCH */
    private synchronized String[] readBatch(String[] paths) {
        if (dead) return null;
        List<String> lines = execLines(
                buildReadCommand(paths), paths.length, CMD_TIMEOUT_MS);
        if (lines == null) {
            return null;
        }
        return parseBatch(lines, paths.length);
    }

    /**
     * 把「序号|值」的输出还原成跟请求等长的值数组。
     *
     * 按序号回填，不是按行序：命令里每个路径都 echo 一行，但万一 shell 吞掉某行
     * （或者被 maxLines 截了），按行序对应就会整体错位。有序号在，每个值只可能
     * 落到自己的位置上。
     *
     * count 是请求的路径数，决定返回数组多长；返回等长数组，读不到或没回来的位置是 null。
     */
    static String[] parseBatch(List<String> lines, int count) {
        String[] out = new String[count];
        for (String line : lines) {
            int sep = line.indexOf(FIELD_SEP);
            if (sep <= 0) {
                continue;
            }
            int idx;
            try {
                idx = Integer.parseInt(line.substring(0, sep));
            } catch (NumberFormatException e) {
                continue;
            }
            if (idx < 0 || idx >= count) {
                continue;
            }
            String value = line.substring(sep + 1);
            // 空串就是这个节点读不到，统一成 null，让调用方走原来那条「未命中」分支
            out[idx] = value.isEmpty() ? null : value;
        }
        return out;
    }

    /**
     * 拼出批量读取用的 shell 命令。
     *
     * 每个路径打一行「序号|值」，全部靠 shell 内建完成，不产生任何子进程。
     * 每个路径都必须占一行，读失败的也得占位，否则某个节点不存在时后面的值会整体
     * 错位，把 GPU 频率当成温度用。
     *
     * 分两种形态：
     * 普通路径单引号包起来，先 [ -r ] 判一下再 read 一行；含 * 的路径不能加引号
     * （引号会把通配展开关掉），改成 for 循环遍历展开结果，取第一个可读的。
     * 原先这类路径是靠 cat glob 让 shell 展开的，可 read < glob 在展开出多个文件时
     * 会报 ambiguous redirect，所以只能一个个试。
     *
     * 先判 [ -r ] 再重定向，而不是直接重定向让它失败：POSIX 允许非交互 shell 在
     * 重定向出错时直接退出，真出了这事常驻 shell 就死了。候选表里大多数路径在任一
     * 机型上都不存在，这条判断走的恰恰是最常见的路径，不能冒这个险。通配不匹配时
     * POSIX shell 会把原串原样赋给循环变量，同样由这条判断挡掉。
     *
     * read 自己的返回值是故意忽略的：文件末尾没换行时它返回非 0，但变量已经赋好值了，
     * 按失败处理就会漏掉这类节点（sysfs 里很常见）。
     */
    static String buildReadCommand(String[] paths) {
        StringBuilder sb = new StringBuilder(paths.length * 128);
        for (int i = 0; i < paths.length; i++) {
            String path = paths[i];
            sb.append(TMP_VAR).append("=; ");
            if (path.indexOf('*') >= 0) {
                // 含通配的路径：不加引号，交给 shell 展开后挨个试
                sb.append("for ").append(GLOB_VAR).append(" in ").append(path).append("; do ")
                        .append("if [ -r \"$").append(GLOB_VAR).append("\" ]; then ")
                        .append("read -r ").append(TMP_VAR)
                        .append(" < \"$").append(GLOB_VAR).append("\" 2>/dev/null; ")
                        .append("break; fi; done; ");
            } else {
                String quoted = quote(path);
                sb.append("if [ -r ").append(quoted).append(" ]; then ")
                        .append("read -r ").append(TMP_VAR)
                        .append(" < ").append(quoted).append(" 2>/dev/null; ")
                        .append("fi; ");
            }
            sb.append("echo \"").append(i).append(FIELD_SEP)
                    .append('$').append(TMP_VAR).append("\"; ");
        }
        return sb.toString();
    }

    /**
     * 用 root shell 把通配路径展开，返回实际存在的具体路径。
     *
     * 为什么需要它（v1.7.1）：
     * buildReadCommand 处理通配时，取到第一个可读的匹配就 break 了。在「候选表里
     * 好几条路径、只有一条存在」的场景下这是对的，但「一个通配展开出多个都存在的
     * 节点」时就出错了——高通新机每个 crtc 都挂一个 measured_fps，主屏之外的 crtc
     * 恒为 fps: 0.0。通配按字母序取到 crtc-0，而主屏不在 crtc-0 上时，读到的永远
     * 是闲置节点那个 0，表现就是「面板方案用不了」。
     *
     * 没在 shell 里判断是不是零：measured_fps 的内容长这样
     * fps: 0.0 duration:1000000 frame_count:0，纯 shell 的字符串匹配会被 duration
     * 里的数字骗过去。把展开结果交回 Java，让 SysfsReader.readFps 用它已有的
     * 「一个个试、取第一个 fps &gt; 0」逻辑挑，判据统一，也好测。
     *
     * 一条命令展开所有模式，仍然只是一次管道往返。每个模式的匹配用「序号|路径」
     * 标出来，同一个序号可以出现多行。
     *
     * patterns 是含 * 的路径模式，不含通配的原样返回；返回跟 patterns 等长的数组，
     * 每项是该模式展开出的路径，整体失败返回 null。
     */
    public synchronized List<String>[] expandGlobs(String[] patterns) {
        if (patterns == null || patterns.length == 0) {
            return null;
        }
        if (dead) {
            return null;
        }
        StringBuilder sb = new StringBuilder(patterns.length * 96);
        for (int i = 0; i < patterns.length; i++) {
            String p = patterns[i];
            if (p.indexOf('*') < 0) {
                continue;
            }
            // 展开后一个个 echo；通配不匹配时 POSIX shell 会把原串原样赋给循环变量，
            // 所以还得 [ -r ] 挡一次
            sb.append("for ").append(GLOB_VAR).append(" in ").append(p).append("; do ")
                    .append("if [ -r \"$").append(GLOB_VAR).append("\" ]; then ")
                    .append("echo \"").append(i).append(FIELD_SEP)
                    .append("$").append(GLOB_VAR).append("\"; fi; done; ");
        }
        @SuppressWarnings("unchecked")
        List<String>[] out = new List[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            out[i] = new ArrayList<>(2);
            if (patterns[i].indexOf('*') < 0) {
                out[i].add(patterns[i]);
            }
        }
        if (sb.length() == 0) {
            // 一个通配都没有，不用进 shell
            return out;
        }
        List<String> lines = execLines(sb.toString(), GLOB_MAX_LINES, CMD_TIMEOUT_MS);
        if (lines == null) {
            return null;
        }
        for (String line : lines) {
            int sep = line.indexOf(FIELD_SEP);
            if (sep <= 0) {
                continue;
            }
            int idx;
            try {
                idx = Integer.parseInt(line.substring(0, sep));
            } catch (NumberFormatException e) {
                continue;
            }
            if (idx < 0 || idx >= patterns.length) {
                continue;
            }
            String path = line.substring(sep + 1);
            if (!path.isEmpty() && path.indexOf('*') < 0) {
                out[idx].add(path);
            }
        }
        return out;
    }

    /**
     * 读 sysfs / procfs 文件的全部行（上限 MAX_LINES），失败或内容为空返回 null。
     *
     * 给 fpsgo_status 这类多行节点用的。整文件读躲不开 cat（内建 read 一次只能读一行），
     * 但这类节点每轮最多读一个。
     */
    public List<String> readLines(String path) {
        List<String> lines = execLines("cat " + quote(path) + " 2>/dev/null", MAX_LINES);
        return (lines == null || lines.isEmpty()) ? null : lines;
    }

    /**
     * 把参数包成单引号字符串，好拼进 shell 命令。
     *
     * SurfaceFlinger 的 layer 名里带 [ ] # 甚至空格，直接拼进命令行会被 shell
     * 当成通配符或者分词符。参数里真出现单引号的话，就用 '\'' 这个经典写法
     * 先闭合再转义。
     */
    public static String quote(String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    private void markDead() {
        dead = true;
        try {
            if (process != null) process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    public boolean isDead() {
        return dead;
    }
}
