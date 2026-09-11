package com.jj.nexusfloat.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import io.github.libxposed.service.XposedService;

/**
 * 模块 App 进程这边：把 GPU 数据写出去给 SystemUI 读。
 *
 * 两条通道并存：
 * publishLocal——写本地 SharedPreferences，SystemUI 经 EarlyInitProvider 读。
 * 不依赖任何框架能力，是保底的那条。
 * publish——写 libxposed RemotePreferences，更快，但要模块自己的进程也被框架注入
 * （实测澎湃 OS 4 上不满足）。
 */
public final class NexusRemoteWriter {

    /** 本地 SharedPreferences 用的 Context，由 bind 注入 */
    private static volatile Context appContext;

    private NexusRemoteWriter() {}

    /** 绑上模块进程的 Context，本地写入要用 */
    public static void bind(Context ctx) {
        appContext = ctx != null ? ctx.getApplicationContext() : null;
    }

    /**
     * 写本地 SharedPreferences。
     *
     * v1.6.10 加的保底通道：SystemUI 从 exported ContentProvider 读同一份 prefs，
     * 跟框架有没有注入模块进程没关系。
     *
     * 返回写成功没有。
     */
    public static boolean publishLocal(int gpuMhz, int gpuUsage) {
        Context ctx = appContext;
        if (ctx == null) {
            return false;
        }
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(
                    Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(Constants.Remote.KEY_GPU_FREQ_MHZ, gpuMhz);
            if (gpuUsage >= 0) {
                editor.putInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, gpuUsage);
            }
            editor.putLong(Constants.Remote.KEY_UPDATED_AT, System.currentTimeMillis());
            // 用 commit 不用 apply：下一拍就可能被 SystemUI 读走，
            // apply 是异步落盘，读到的值会慢一拍
            return editor.commit();
        } catch (Throwable t) {
            LogUtils.w("write local prefs failed", t);
            return false;
        }
    }

    /**
     * 写 RemotePreferences 和备用文件。
     *
     * 任意一条通道写成功就算 true。
     */
    public static boolean publish(XposedService service, int gpuMhz, int gpuUsage) {
        if (service == null) {
            return false;
        }
        boolean ok = writePrefs(service, gpuMhz, gpuUsage);
        if (gpuMhz > 0) {
            ok |= writeFreqFile(service, gpuMhz);
        }
        return ok;
    }

    private static boolean writePrefs(XposedService service, int gpuMhz, int gpuUsage) {
        try {
            SharedPreferences prefs = service.getRemotePreferences(Constants.Remote.PREFS_NAME);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(Constants.Remote.KEY_GPU_FREQ_MHZ, gpuMhz);
            if (gpuUsage >= 0) {
                editor.putInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, gpuUsage);
            }
            editor.putLong(Constants.Remote.KEY_UPDATED_AT, System.currentTimeMillis());
            return editor.commit();
        } catch (Throwable t) {
            LogUtils.e("write remote prefs failed", t);
            return false;
        }
    }

    private static boolean writeFreqFile(XposedService service, int gpuMhz) {
        try (ParcelFileDescriptor pfd = service.openRemoteFile(Constants.Remote.GPU_FREQ_FILE)) {
            try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
                fos.write(String.valueOf(gpuMhz).getBytes(StandardCharsets.UTF_8));
                fos.flush();
                return true;
            }
        } catch (Throwable t) {
            LogUtils.w("write remote file failed", t);
            return false;
        }
    }
}
