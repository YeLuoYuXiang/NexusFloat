package com.jj.nexusfloat.stats;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.ui.MainActivity;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 统计采样常驻的前台服务。
 *
 * 为什么必须是前台服务：采样要跨设备息屏、跨应用切换一直跑，后台服务在
 * 现在的 Android 上活不过几分钟。前台服务的代价是一条通知——默认显示，
 * 但内容会跟着电池状态走（充电中就写充到多少了、功率多少），不算白占地方。
 *
 * 服务类型用 specialUse 而不是 dataSync：Android 15 起 dataSync 类型的
 * 前台服务一天只允许累计跑 6 小时，对「一直在记」的场景等于没有。
 * specialUse 没有时长上限，代价是上架应用商店要说明用途——本模块不分发到商店，
 * 没这个顾虑。
 *
 * 采样本身不在这里：服务只负责「让进程活着」和「把采样循环开关起来」。
 * 真正的循环在 {@link BatterySampler} 里，界面进程里也能单独调它取实时值。
 */
public class StatsService extends Service implements BatterySampler.Listener {

    private static final String TAG = "StatsService";

    /** 通知最多每 30 秒刷一次：每秒更新通知本身就是一笔可观的耗电 */
    private static final long NOTIFY_THROTTLE_MS = 30_000L;
    private long lastNotifyTs;

    private NotificationManager notificationManager;

    /** 是否已经成功进入前台；false 时 onStartCommand 直接让服务退出 */
    private boolean foregroundStarted;

    /**
     * 用户开着统计功能才启动服务。
     *
     * 开机、被 SystemUI 唤醒、打开 App 这三处都调它，所以「开关状态」的判断放在
     * 这里而不是各个调用点——漏判一个地方就会出现「关了统计还在后台跑」。
     */
    public static void startIfEnabled(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences p = context.getSharedPreferences(
                Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
        if (!p.getBoolean(Constants.Stats.KEY_ENABLED, true)) {
            return;
        }
        start(context);
    }

    /** 启一个已经在跑的也可以调，不会重复起 */
    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, StatsService.class);
            intent.setAction(Constants.Stats.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            // Android 12+ 在部分时机（比如刚开机）拒绝启动前台服务。
            // 拒绝就拒绝，采样停到用户下次打开 App 再恢复，不该让调用方崩
            LogUtils.w(TAG + " start failed", t);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, StatsService.class));
        } catch (Throwable t) {
            LogUtils.w(TAG + " stop failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        if (!startForegroundCompat(buildNotification(null))) {
            // 前台服务没起来就必须马上自尽。
            //
            // startForegroundService() 是有时限的：调用方在 5 秒内没有真正进入前台，
            // 系统会直接抛 ForegroundServiceDidNotStartInTimeException 把进程干掉——
            // 那是个系统侧的检查，我们 catch 不到，只能靠 stopSelf 主动退出，
            // 否则用户看到的就是「打开 App 闪退」，而真实原因只是一个我们本来就
            // 打算容忍的降级（比如 Android 12+ 在开机广播里拒绝启动某些前台服务）。
            LogUtils.w(TAG + " startForeground failed, stopping self to avoid ANR kill");
            stopSelf();
            return;
        }
        foregroundStarted = true;
        BatterySampler sampler = BatterySampler.get(this);
        sampler.addListener(this);
        sampler.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foregroundStarted) {
            // 前台没起来（见 onCreate），这里什么都别做，让它自然结束。
            // 尤其不能再拉采集：没有前台服务罩着的采集活不了多久，
            // 只会留下一个半死不活的循环
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent != null ? intent.getAction() : null;
        if (Constants.Stats.ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        BatterySampler.get(this).start();
        // START_STICKY：被系统回收后自动重建，重建时会重走 onCreate 把采集接上
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        foregroundStarted = false;
        BatterySampler sampler = BatterySampler.get(this);
        sampler.removeListener(this);
        sampler.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ======================= 通知 =======================

    @Override
    public void onSample(StatsStore.Sample sample) {
        long now = System.currentTimeMillis();
        if (now - lastNotifyTs < NOTIFY_THROTTLE_MS) {
            return;
        }
        lastNotifyTs = now;
        if (!notificationEnabled()) {
            return;
        }
        try {
            notificationManager.notify(Constants.Stats.NOTIFICATION_ID,
                    buildNotification(sample));
        } catch (Throwable t) {
            LogUtils.w(TAG + " notify failed", t);
        }
    }

    private boolean notificationEnabled() {
        SharedPreferences p = getSharedPreferences(
                Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
        return p.getBoolean(Constants.Stats.KEY_NOTIFICATION, true);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager == null) {
            return;
        }
        try {
            NotificationChannel channel = new NotificationChannel(
                    Constants.Stats.CHANNEL_ID,
                    Constants.Stats.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("记录充电曲线与使用时长，可在此关闭");
            channel.setShowBadge(false);
            channel.enableLights(false);
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);
        } catch (Throwable t) {
            LogUtils.w(TAG + " createChannel failed", t);
        }
    }

    /**
     * 通知内容直接写当前电池状态。
     *
     * 常驻通知最怕的就是「一条永远不变的框」，用户看一眼就想关掉后台。写成实时
     * 状态之后它反而有点用：不用解锁就能知道还剩多少、充得快不快。
     */
    private Notification buildNotification(StatsStore.Sample sample) {
        int icon = android.R.drawable.ic_lock_idle_charging;
        String title;
        String text;
        if (sample == null) {
            title = "NexusFloat 统计";
            text = "正在记录充电与使用情况";
        } else {
            // 跟监视条、统计页用同一个双电芯倍率，否则同一个瞬间三个地方三个数
            float power = Math.abs(sample.powerW) * StatsRepository.dualCellFactor(this);
            if (sample.charging()) {
                title = "充电中 " + sample.level + "%";
                text = String.format("功率 %.1fW · 电池 %.1f°C", power, sample.tempC);
            } else {
                title = (sample.level <= 20 ? "电量偏低 " : "使用中 ") + sample.level + "%";
                text = String.format("放电 %.1fW · 电池 %.1f°C", power, sample.tempC);
            }
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, Constants.Stats.CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(icon)
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

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    /** 返回是否成功进入前台；失败时调用方必须立刻 stopSelf，理由见 onCreate */
    private boolean startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Constants.Stats.NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(Constants.Stats.NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Throwable t) {
            LogUtils.w(TAG + " startForeground failed", t);
            return false;
        }
    }
}
