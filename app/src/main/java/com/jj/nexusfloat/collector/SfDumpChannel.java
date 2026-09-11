package com.jj.nexusfloat.collector;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.RootShell;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * SurfaceFlinger 的 dump 通道，SurfaceFlingerFpsReader 和 TimeStatsFpsReader 共用。
 *
 * 两条通道：
 *
 * 一是 Binder。用 ServiceManager.getService("SurfaceFlinger") 反射拿到服务，
 * 再调 IBinder.dump(fd, args)，写进 createPipe() 的写端。SystemUI 自带
 * android.permission.DUMP，没 root 也能用，而且不用 fork 进程，快得多。
 * 读端必须在另一个线程排空，不然管道写满时 dump 会阻塞、读取又等不到数据，
 * 两边互相等死。
 *
 * 二是 root。在 su 里跑 dumpsys SurfaceFlinger，给 Binder 被 SELinux 拦住的机型用。
 *
 * 失败判定用计数，不用一次性的开关。开机早期 SurfaceFlinger 还没就绪、锁屏时没有
 * 可探的 layer，都会造成偶发失败，一次就永久放弃是 v1.5.6 的设计错误。但「权限被拒」
 * 「取不到服务」「没有 su」这几个是确定性的，直接判死不再重试。
 *
 * 线程安全方面：只该由采集线程调（executor() 的懒初始化除外）。
 */
final class SfDumpChannel {

    private static volatile SfDumpChannel sInstance;

    static SfDumpChannel get() {
        SfDumpChannel inst = sInstance;
        if (inst == null) {
            synchronized (SfDumpChannel.class) {
                inst = sInstance;
                if (inst == null) {
                    sInstance = inst = new SfDumpChannel();
                }
            }
        }
        return inst;
    }

    private SfDumpChannel() {}

    /** SurfaceFlinger 的 Binder，null 表示还没取过 */
    private IBinder binder;
    /** 两条通道各自的连续失败次数，成功一次就清零 */
    private int binderFailures;
    private int rootFailures;

    /** dump 的读取线程。跟采集线程分开，管道写满时才不会互相等死 */
    private ExecutorService io;

    /** 上一次取数走没走 root */
    private boolean lastViaRoot;

    boolean lastViaRoot() {
        return lastViaRoot;
    }

    /** 两条通道是不是都已经连续失败到判定不可用了 */
    boolean exhausted() {
        int max = Constants.Fps.SF_CHANNEL_MAX_FAILURES;
        return binderFailures >= max && rootFailures >= max;
    }

    /**
     * 取 SurfaceFlinger 的 dump 内容。
     *
     * args 是参数，比如 --list 或者 --latency <layer>；preferRoot 决定优不优先走 root；
     * maxLines 是最多保留多少行。用不了就返回 null。
     */
    List<String> dump(String[] args, boolean preferRoot, int maxLines) {
        int max = Constants.Fps.SF_CHANNEL_MAX_FAILURES;
        if (preferRoot) {
            if (rootFailures < max) {
                List<String> out = dumpViaRoot(args, maxLines);
                if (out != null) {
                    rootFailures = 0;
                    lastViaRoot = true;
                    return out;
                }
                rootFailures++;
            }
            if (binderFailures < max) {
                List<String> out = dumpViaBinder(args, maxLines);
                if (out != null) {
                    binderFailures = 0;
                    lastViaRoot = false;
                    return out;
                }
                binderFailures++;
            }
            return null;
        }
        // 默认先走 Binder，不用 fork 进程，快得多
        if (binderFailures < max) {
            List<String> out = dumpViaBinder(args, maxLines);
            if (out != null) {
                binderFailures = 0;
                lastViaRoot = false;
                return out;
            }
            binderFailures++;
        }
        if (rootFailures < max) {
            List<String> out = dumpViaRoot(args, maxLines);
            if (out != null) {
                rootFailures = 0;
                lastViaRoot = true;
                return out;
            }
            rootFailures++;
        }
        return null;
    }

    /**
     * 走 Binder，直接调 SurfaceFlinger 的 dump。
     */
    private List<String> dumpViaBinder(String[] args, int maxLines) {
        IBinder sf = binder();
        if (sf == null) {
            return null;
        }
        ParcelFileDescriptor[] pipe = null;
        try {
            pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor readSide = pipe[0];
            final int cap = maxLines;
            Future<List<String>> task = executor().submit(() -> readAll(readSide, cap));
            try {
                sf.dump(pipe[1].getFileDescriptor(), args);
            } finally {
                // 本地写端必须关掉，不然读端等不到 EOF
                closeQuietly(pipe[1]);
            }
            List<String> out = task.get(Constants.Fps.SF_DUMP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (out == null || out.isEmpty()) {
                return null;
            }
            if (out.get(0).contains(Constants.Fps.SF_PERMISSION_DENIED)) {
                LogUtils.w("SurfaceFlinger dump denied via binder, trying root");
                // 权限被拒是确定性结论，不用重试满次数
                binderFailures = Constants.Fps.SF_CHANNEL_MAX_FAILURES;
                return null;
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (pipe != null) {
                closeQuietly(pipe[0]);
                closeQuietly(pipe[1]);
            }
        }
    }

    /**
     * 走 root shell 调 dumpsys。
     */
    private List<String> dumpViaRoot(String[] args, int maxLines) {
        StringBuilder cmd = new StringBuilder(Constants.Fps.SF_DUMPSYS_CMD);
        for (String arg : args) {
            cmd.append(' ').append(RootShell.quote(arg));
        }
        cmd.append(" 2>/dev/null");
        List<String> out = RootShell.get().execLines(
                cmd.toString(), maxLines, Constants.Fps.SF_ROOT_TIMEOUT_MS);
        if (out == null) {
            if (RootShell.get().isDead()) {
                // 没有 root 是确定性结论
                rootFailures = Constants.Fps.SF_CHANNEL_MAX_FAILURES;
            }
            return null;
        }
        // 空列表也算成功：--timestats -enable 正常情况下本来就没输出
        return out;
    }

    private static List<String> readAll(ParcelFileDescriptor pfd, int maxLines) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd)))) {
            String line;
            // 必须读到 EOF，提前 return 会让写端阻塞在满管道上
            while ((line = br.readLine()) != null) {
                if (lines.size() < maxLines) {
                    lines.add(line);
                }
            }
        } catch (Throwable ignored) {
        }
        return lines;
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try {
            if (pfd != null) {
                pfd.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private synchronized ExecutorService executor() {
        if (io == null) {
            io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, Constants.ThreadName.SF_DUMP);
                t.setDaemon(true);
                return t;
            });
        }
        return io;
    }

    /**
     * 取 SurfaceFlinger 的 Binder。
     */
    private IBinder binder() {
        if (binder != null) {
            return binder;
        }
        try {
            Class<?> sm = Class.forName(Constants.Fps.SF_CLASS_SERVICE_MANAGER);
            Method getService = sm.getMethod(Constants.Fps.SF_METHOD_GET_SERVICE, String.class);
            binder = (IBinder) getService.invoke(null, Constants.Fps.SF_SERVICE);
        } catch (Throwable t) {
            LogUtils.w("ServiceManager.getService(SurfaceFlinger) failed", t);
        }
        if (binder == null) {
            // 取不到服务是确定性结论，直接把 Binder 通道判死，转 root
            binderFailures = Constants.Fps.SF_CHANNEL_MAX_FAILURES;
        }
        return binder;
    }

    /** 供采集停止时释放 dump 线程 */
    synchronized void shutdown() {
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
    }
}
