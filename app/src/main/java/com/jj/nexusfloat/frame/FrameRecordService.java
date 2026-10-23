package com.jj.nexusfloat.frame;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.ui.MainActivity;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 帧记录的前台服务：托着悬浮球和采样循环。
 *
 * 为什么必须是前台服务：用户点了加号就要切到游戏里去，悬浮球得跨应用一直显示、
 * 采样得跨应用一直跑。后台服务在现在的 Android 上活不过几分钟，只有前台服务能
 * 托得住。代价是一条通知——记录中会实时显示应用名和帧率，不算白占地方。
 *
 * 服务只负责「让进程活着」和「把悬浮球/采样开关起来」；采样逻辑在
 * {@link FrameSampler}，悬浮球本体在 {@link FrameBubble}，都不在这儿。
 */
public final class FrameRecordService extends Service implements FrameSampler.Listener {

    private static final String TAG = "FrameRecordService";

    /**
     * 服务是否在跑。给界面上的「+ / 关闭」开关用：页面点一下要立刻翻转，
     * 不能等下一轮 3 秒刷新。onCreate 置 true、onDestroy 清理完置 false。
     */
    public static volatile boolean serviceRunning;

    /** 悬浮球（帧记录服务）是否正在运行 */
    public static boolean isRunning() {
        return serviceRunning;
    }

    /** 通知最多每 5 秒刷一次（帧率每秒都在变，但通知没必要跟着跳） */
    private static final long NOTIFY_THROTTLE_MS = 5000L;
    /** 通知里显示的帧率取这几秒的平均值，别一秒一跳看着像坏了一样 */
    private static final int NOTIFY_FPS_WINDOW = 5;

    private NotificationManager notificationManager;
    private FrameBubble bubble;
    private FrameSampler sampler;

    private boolean foregroundStarted;
    private long lastNotifyTs;
    private final float[] fpsWindow = new float[NOTIFY_FPS_WINDOW];
    private int fpsWindowIdx;
    /** 正在记录的应用名，开始记录那一下存下来给通知用 */
    private volatile String recordingLabel;

    // ======================= 启停入口 =======================

    /** 起服务并显示悬浮球。已在跑就只当刷新一次 */
    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, FrameRecordService.class);
            intent.setAction(Constants.Frame.ACTION_SHOW_BUBBLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            // Android 12+ 部分时机拒绝启动前台服务；拒绝就拒绝，别让调用方崩
            LogUtils.w(TAG + " start failed", t);
        }
    }

    public static void stop(Context context) {
        // 直接停服务就行：onDestroy 会把进行中的记录先保存、把悬浮球摘掉。
        // 走 startForegroundService 反而要在 5 秒内再进一次前台，纯属给自己埋雷
        try {
            context.stopService(new Intent(context, FrameRecordService.class));
        } catch (Throwable t) {
            LogUtils.w(TAG + " stop failed", t);
        }
    }

    // ======================= 生命周期 =======================

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        if (!startForegroundCompat(buildNotification(false))) {
            // 见 StatsService.onCreate：5 秒内没进前台系统会直接杀进程，只能自尽
            LogUtils.w(TAG + " startForeground failed, stopping self");
            stopSelf();
            return;
        }
        foregroundStarted = true;
        serviceRunning = true;

        // 收孤儿 + 清过期明细。放服务起来这一拍做，而不是每次打开页面都做
        FrameRecordStore store = FrameRecordStore.get(this);
        // 跳过正在记的那条：服务重建（进程没死）时采样器可能还挂着一条进行中的记录
        store.finalizeOrphans(FrameSampler.get(this).currentRecordId());
        store.pruneOldSamples();

        sampler = FrameSampler.get(this);
        sampler.addListener(this);
        bubble = new FrameBubble(this);
        bubble.show(new FrameBubble.Callback() {
            @Override
            public void onTap() {
                toggleRecording();
            }

            @Override
            public void onDismiss() {
                // 长按收球：记录中的先保存，然后整个服务退掉
                if (sampler.isRecording()) {
                    sampler.finishRecording();
                }
                stopSelf();
            }

        });
        // 采样循环平时不起：悬浮球挂着但没记录，就是「棕黄、没在记」的状态。
        // 空转一个线程没意义，等点按开始时再拉起（FrameSampler.beginRecording 里会 start）
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foregroundStarted) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent != null ? intent.getAction() : null;
        if (Constants.Frame.ACTION_STOP.equals(action)) {
            if (sampler != null && sampler.isRecording()) {
                sampler.finishRecording();
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        // ACTION_SHOW_BUBBLE 或空 action：球已经在 onCreate 里挂上，不用再做什么
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (bubble != null) {
            bubble.hide();
            bubble = null;
        }
        if (sampler != null) {
            sampler.removeListener(this);
            // stop() 内部会把没落库的尾巴刷掉，并把进行中的记录正常收尾
            sampler.stop();
        }
        foregroundStarted = false;
        // 一定要等上面采样清理完再翻 false，界面开关才不会再拉到「还在跑」的假状态
        serviceRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ======================= 记录控制 =======================

    /** 悬浮球点按：未记录 → 开始；记录中 → 结束并保存 */
    private void toggleRecording() {
        if (sampler.isRecording()) {
            sampler.finishRecording();
            recordingLabel = null;
        } else {
            sampler.beginRecording();
            recordingLabel = sampler.currentLabel();
        }
        refreshNotification(System.currentTimeMillis());
    }

    // ======================= 采样回调（采样线程） =======================

    @Override
    public void onRecordingChanged(boolean recording) {
        recordingLabel = recording ? sampler.currentLabel() : null;
        // 丢弃/结束后不会再有 onTick，球的颜色必须在这里翻回去
        if (bubble != null) {
            bubble.update(recording, -1f);
        }
        refreshNotification(System.currentTimeMillis());
    }

    @Override
    public void onTick(FrameRecordStore.Sample sample) {
        // 悬浮球每拍都刷新（游戏里瞄一眼就要看到帧率）；通知按节流来
        if (bubble != null) {
            bubble.update(sampler.isRecording(), sample.fps);
        }
        if (sample.fps >= 0f) {
            synchronized (fpsWindow) {
                fpsWindow[fpsWindowIdx % NOTIFY_FPS_WINDOW] = sample.fps;
                fpsWindowIdx++;
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastNotifyTs < NOTIFY_THROTTLE_MS) {
            return;
        }
        lastNotifyTs = now;
        refreshNotification(now);
    }

    private void refreshNotification(long now) {
        if (notificationManager == null) {
            return;
        }
        try {
            notificationManager.notify(Constants.Frame.NOTIFICATION_ID,
                    buildNotification(sampler != null && sampler.isRecording()));
        } catch (Throwable t) {
            LogUtils.w(TAG + " notify failed", t);
        }
    }

    // ======================= 通知 =======================

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager == null) {
            return;
        }
        try {
            NotificationChannel channel = new NotificationChannel(
                    Constants.Frame.CHANNEL_ID,
                    Constants.Frame.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("帧率记录悬浮窗与记录进度，可在此关闭");
            channel.setShowBadge(false);
            channel.enableLights(false);
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);
        } catch (Throwable t) {
            LogUtils.w(TAG + " createChannel failed", t);
        }
    }

    private Notification buildNotification(boolean recording) {
        String title;
        String text;
        if (recording) {
            title = "正在记录 " + (recordingLabel != null ? recordingLabel : "帧率");
            text = String.format("点按悬浮球结束并保存 · 近几秒 %.0f 帧", windowAvgFps());
        } else {
            title = "NexusFloat 帧记录";
            text = "悬浮球已就绪：点按开始记录当前应用的帧率";
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, Constants.Frame.CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openAppIntent());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_SECRET);
        }
        return builder.build();
    }

    /** 通知里显示的帧率：最近几拍的均值，别一秒一跳看着像坏了一样 */
    private float windowAvgFps() {
        synchronized (fpsWindow) {
            int n = Math.min(fpsWindowIdx, NOTIFY_FPS_WINDOW);
            if (n == 0) {
                return 0f;
            }
            float sum = 0f;
            for (int i = 0; i < n; i++) {
                sum += fpsWindow[i];
            }
            return sum / n;
        }
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 1, intent, flags);
    }

    /** 返回是否成功进入前台；失败时调用方必须立刻 stopSelf */
    private boolean startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Constants.Frame.NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(Constants.Frame.NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Throwable t) {
            LogUtils.w(TAG + " startForeground failed", t);
            return false;
        }
    }
}
