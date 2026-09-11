package com.jj.nexusfloat.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.jj.nexusfloat.collector.GpuCollectorWorker;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import java.util.Map;

/**
 * 一个可导出的空 ContentProvider，顺便当进程唤醒点、指令入口和设置快照出口用。
 *
 * ContentProvider.onCreate() 比 Application.onCreate() 还早，而且外部进程
 * （比如 SystemUI 的 hook 侧）调 ContentResolver.query() 打到这个 authority 时，
 * 系统会自己把模块进程拉起来，不用等 BOOT_COMPLETED。
 *
 * query 干三件事：
 * 不带路径——纯粹唤醒进程，确认采集在跑；
 * collect/resume、collect/pause——采集启停指令，见 CollectorSignal；
 * prefs——把本地 SharedPreferences 整表吐给 SystemUI，v1.6.10 加的设置传输通道，
 * 给 RemotePreferences 用不了的机型兜底，见 Constants.Component.PATH_PREFS。
 *
 * insert/update/delete 还是空实现。
 */
public class EarlyInitProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        GpuCollectorLauncher.startInProcess();
        return true;
    }

    /**
     * 按路径执行指令；prefs 这条路径返回设置快照，别的一律返回 null。
     *
     * 不带路径（单纯唤醒进程）时保持原样：确认采集在跑。
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String path = uri != null ? uri.getPath() : null;
        if (path == null || path.isEmpty() || "/".equals(path)) {
            GpuCollectorWorker.start();
            return null;
        }
        // 去掉前导 '/' 再比对，Uri.getPath() 总会带上它
        String command = path.startsWith("/") ? path.substring(1) : path;
        if (Constants.Component.PATH_COLLECT_PAUSE.equals(command)) {
            LogUtils.i("collector pause requested");
            GpuCollectorWorker.setOverlayDemand(false);
        } else if (Constants.Component.PATH_COLLECT_RESUME.equals(command)) {
            LogUtils.i("collector resume requested");
            GpuCollectorWorker.setOverlayDemand(true);
        } else if (Constants.Component.PATH_PREFS.equals(command)) {
            // SystemUI 来读设置，说明监视条正在工作，顺带确保采集在跑。
            // 这条比 collect/resume 心跳更可靠：设置每秒都要读，
            // 而心跳每 30 秒才发一次
            GpuCollectorWorker.setOverlayDemand(true);
            return prefsSnapshot();
        }
        return null;
    }

    /**
     * 把本地 SharedPreferences 整表转成 Cursor。
     *
     * 每行三列：键名、类型标记、字符串化的值。为什么要带类型标记——getAll() 返回的是
     * Map<String, ?>，跨进程只能传字符串，对端得知道原本是 boolean 还是 int 才还原得回来，
     * 否则 "true" 和 "1" 就分不清了。
     *
     * 整表一次传完，没有按键查询：设置项有三十来个，一次 binder 调用比三十次往返划算得多，
     * 何况 SystemUI 那边每秒只查一次。
     */
    private Cursor prefsSnapshot() {
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(
                Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
        MatrixCursor cursor = new MatrixCursor(new String[]{
                Constants.Component.COL_KEY,
                Constants.Component.COL_TYPE,
                Constants.Component.COL_VALUE});
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Object v = e.getValue();
            String type;
            if (v instanceof Boolean) {
                type = Constants.Component.TYPE_BOOL;
            } else if (v instanceof Integer) {
                type = Constants.Component.TYPE_INT;
            } else if (v instanceof Long) {
                type = Constants.Component.TYPE_LONG;
            } else if (v instanceof Float) {
                type = Constants.Component.TYPE_FLOAT;
            } else if (v instanceof String) {
                type = Constants.Component.TYPE_STRING;
            } else {
                // Set<String> 等类型本模块不用，跳过而不是硬塞
                continue;
            }
            cursor.addRow(new Object[]{e.getKey(), type, String.valueOf(v)});
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
