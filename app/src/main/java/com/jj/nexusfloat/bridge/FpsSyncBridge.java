package com.jj.nexusfloat.bridge;

import android.content.Context;
import android.provider.Settings;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 把监视条的帧率同步给模块进程。
 *
 * 为什么要这条通道：帧记录自己也有一套帧率取数，但它跑在模块 App 进程里，
 * 和监视条（SystemUI 进程）看到的 layer 与节点权限不一样。两边算出来的数经常
 * 对不上——监视条上明明显示 60，切到记录页却是 --。既然监视条那个数已经是准的，
 * 帧记录直接拿它的结果就行。
 *
 * 通道选 Settings.Global，理由：
 * - 读侧不要权限：普通应用读 Settings.Global 里自定义的键不会被拦（只有写需要）
 * - 写侧走 root：SystemUI 有 su，`settings put global` 一行就够
 * - 复用现成的 SettingsChannel 写入逻辑与超时参数，不新开一套
 *
 * 方向是 SystemUI 写、模块读。RemotePreferences 只能模块写、SystemUI 读，
 * 方向反着来用不了，所以没走那条。
 */
public final class FpsSyncBridge {

    private FpsSyncBridge() {}

    private static float lastPublished = -1f;
    private static String lastTag = "";

    /**
     * SystemUI 侧调用：把当前帧率写进 Settings.Global。
     *
     * 数值没变就不写——`settings put` 每次要 fork 一个进程，一秒一次已经很贵了，
     * 帧率稳定时没必要重复写。
     *
     * 写的是「帧率|标记」这种复合串，一次写完，省掉第二次写。
     */
    public static void publish(Context context, float fps, String tag) {
        if (context == null) {
            return;
        }
        if (fps == lastPublished && safeTag(tag).equals(lastTag)) {
            return;
        }
        lastPublished = fps;
        lastTag = safeTag(tag);
        try {
            String value = String.format(java.util.Locale.US, "%.1f", fps)
                    + "|" + lastTag;
            // 复用 SettingsChannel 的写入通道（内部就是 su + settings put）
            SettingsChannel.writeRaw(context, Constants.Component.SETTINGS_KEY_FPS_SYNC, value);
        } catch (Throwable t) {
            LogUtils.w("FpsSyncBridge publish failed", t);
        }
    }

    private static String safeTag(String tag) {
        return tag != null ? tag : "";
    }

    /**
     * 模块侧调用：读监视条同步过来的帧率。
     *
     * 返回 NaN 表示没有可用值（监视条没在跑，或者这条通道还没写成功），
     * 调用方应该退回自己算。
     */
    public static float read(Context context) {
        String raw = readRaw(context);
        if (raw == null) {
            return Float.NaN;
        }
        int sep = raw.indexOf('|');
        String num = sep >= 0 ? raw.substring(0, sep) : raw;
        try {
            return Float.parseFloat(num);
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** 读监视条同步过来的诊断标记；没有返回空串 */
    public static String readTag(Context context) {
        String raw = readRaw(context);
        if (raw == null) {
            return "";
        }
        int sep = raw.indexOf('|');
        return sep >= 0 && sep + 1 < raw.length() ? raw.substring(sep + 1) : "";
    }

    private static String readRaw(Context context) {
        if (context == null) {
            return null;
        }
        try {
            String v = Settings.Global.getString(context.getContentResolver(),
                    Constants.Component.SETTINGS_KEY_FPS_SYNC);
            return (v == null || v.isEmpty()) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }
}
