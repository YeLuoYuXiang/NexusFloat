package com.jj.nexusfloat.collector;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 判定电池是在进电还是出电。
 *
 * 监视条和记录页都调这里，保证两处显示的功率符号一致。
 *
 * ## 判定依据
 *
 * 主要看内核的电流符号。Android power_supply ABI 规定：current_now 负数表示
 * 电池在放电（出电），正数表示在充电（进电）。这是内核直接报告的实测方向，
 * 不需要推断，也不需要任何标定。
 *
 * 这个依据能覆盖几个容易搞错的场景：
 *
 * 插着充电器打游戏、功耗超过充电器输出时，电池在倒贴。内核的 status 节点
 * 这时候仍然报 Charging（充电器确实接着），靠状态判断会误认为是充电；
 * 但 current_now 会变成负值，符号直接说明了真相。
 *
 * 没接充电器时电池只可能放电，这条也是硬事实，作为兜底。
 *
 * ## 为什么不看 status
 *
 * status 反映的是「充电器与电池之间的连接关系」，不是「电池净能量流向」。
 * 供电不足、涓流、电源路径切换这些阶段它都会给出误导性的值。只有电流
 * 不可信（读不到、或者数值太小）时才退回用它。
 */
public final class PowerDirection {

    private static final String TAG = "PowerDirection";

    /**
     * 判定的输入。
     */
    public static final class Input {
        /** 电池电流（A），带内核给出的符号：正进负出 */
        public float currentA;
        /** status 节点原文，读不到是 null */
        public String status;
        /** EXTRA_PLUGGED 原值：0 = 没接电源，非 0 = 接了 */
        public int plugged;
        /** 系统报的充电状态（BatteryManager.BATTERY_STATUS_*） */
        public int sysStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;
    }

    /** 判定结果 */
    public static final class Result {
        /** 是否在放电（电池向负载供电） */
        public boolean discharging;
        /** 带符号的功率（W）：正进负出 */
        public float powerW;
        /** 带符号的电流（A）：正进负出 */
        public float currentA;
    }

    private PowerDirection() {
    }

    /**
     * 全量判定：方向 + 带符号的功率/电流。
     *
     * absPowerW 传正的功率绝对值（电流×电压 或 power_now），
     * 方法内部按判定出的方向补上符号。
     */
    public static Result resolve(Context context, Input in, float absPowerW) {
        Result r = new Result();
        r.discharging = isDischarging(context, in);

        float absA = Math.abs(in.currentA);
        r.currentA = r.discharging ? -absA : absA;

        float p = Math.abs(absPowerW);
        r.powerW = r.discharging ? -p : p;
        return r;
    }

    /**
     * 是不是在放电。
     */
    public static boolean isDischarging(Context context, Input in) {
        // ---- 1. 没接外接电源 → 必定放电 ----
        //
        // 不需要判断，没有电源输入时电池只可能出电
        if (in.plugged <= 0) {
            return true;
        }

        // ---- 2. 电流符号 ----
        //
        // 这是主判据。内核报告的电流方向是实测值，比 status 可靠：
        // 插着充电器打游戏、功耗超过充电器输出时，status 还报 Charging，
        // 但 current_now 已经变成负数了
        if (Math.abs(in.currentA) >= Constants.Battery.CURRENT_CREDIBLE_A) {
            return in.currentA < 0f;
        }

        // ---- 3. 电流太小或读不到，退回 status ----
        if (in.status != null) {
            if (Constants.Battery.STATUS_DISCHARGING.equalsIgnoreCase(in.status)) {
                return true;
            }
            if (Constants.Battery.STATUS_CHARGING.equalsIgnoreCase(in.status)
                    || Constants.Battery.STATUS_FULL.equalsIgnoreCase(in.status)) {
                return false;
            }
        }

        // ---- 4. 兜底：按系统报的充电状态 ----
        return in.sysStatus != BatteryManager.BATTERY_STATUS_CHARGING
                && in.sysStatus != BatteryManager.BATTERY_STATUS_FULL;
    }

    /**
     * 从系统广播取当前电源状态的快照。
     *
     * 用 registerReceiver(null, ...) 拿立即返回的 sticky 广播：不需要真的注册
     * 接收者，也不会漏掉状态。plugged 和 status 一次都拿到。
     */
    public static Input readInputFromBattery(Context context) {
        Input in = new Input();
        if (context == null) {
            return in;
        }
        try {
            Intent intent = context.registerReceiver(
                    null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                in.plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                in.sysStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " read battery intent failed", t);
        }
        return in;
    }
}
