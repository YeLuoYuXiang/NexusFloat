package com.jj.nexusfloat.collector;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 功率方向的唯一依据：内核 power_now 节点的符号。
 *
 * ## 只看 power_now
 *
 * 早期版本靠 status 节点、电流符号、电量趋势去推断方向，都不对。status 反映的是
 * 「充电器接着没有」，不是「电池在进电还是在出电」——插着充电器打游戏、功耗超过
 * 充电器输出时，它照样报 Charging。电流符号要看驱动约定，还有反着给的机型。
 * 电量趋势太慢，等掉一格要好几分钟。
 *
 * power_now 是内核直接算好的功率，符号就是方向：负值电池在放电，正值在充电。
 * 不需要推断，不需要标定，也没有机型差异。
 *
 * ## 为什么之前没生效
 *
 * 读 power_now 的那个槽位构造时没传 keepSign，parseScaledSigned 会把符号丢掉，
 * 于是所有功率都是正数，方向判定完全失效。这一个参数就是全部原因。
 */
public final class PowerDirection {

    private static final String TAG = "PowerDirection";

    /** 判定结果 */
    public static final class Result {
        /** 是否在放电（电池向负载供电） */
        public boolean discharging;
        /** 带符号的功率（W）：负值放电、正值充电 */
        public float powerW;
    }

    private PowerDirection() {
    }

    /**
     * 用 power_now 的实测值判定方向。
     *
     * signedPowerW 是 power_now 读出来的带符号功率。负值即放电。
     * 读不到（0）时退回「系统是否接着电源」这个粗判据。
     */
    public static Result resolve(Context context, float signedPowerW) {
        Result r = new Result();

        if (signedPowerW != 0f) {
            // 内核给了数，符号就是答案，不需要任何推断
            r.powerW = signedPowerW;
            r.discharging = signedPowerW < 0f;
            return r;
        }

        // ---- power_now 读不到：按有没有接电源兜底 ----
        //
        // 没接电源必定放电；接了电源又没有功率读数，只能当作在充电。
        // 这个分支只在极少数机型上会走到
        r.discharging = !isPlugged(context);
        r.powerW = 0f;
        return r;
    }

    /** 当前有没有接外接电源 */
    private static boolean isPlugged(Context context) {
        if (context == null) {
            return false;
        }
        try {
            Intent intent = context.registerReceiver(
                    null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " read plugged failed", t);
        }
        return false;
    }
}
