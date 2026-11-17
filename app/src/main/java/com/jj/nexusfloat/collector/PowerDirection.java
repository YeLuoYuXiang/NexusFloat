package com.jj.nexusfloat.collector;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 判定电池是在进电还是出电，以及电流符号的约定。
 *
 * 这个类取代了原来散在两处的实现（PerformanceCollector 里一套、BatteryGauge 里
 * 另一套）。两套逻辑不一致本身就是符号"时不时反"的一个来源：同一个读数，
 * 监视条算正、记录页算负。
 *
 * ## 为什么原来会反
 *
 * 老实现全靠推断：看 status 节点说什么，再拿电流符号去对。推断链有三个断点。
 *
 * 一是标定可能压根不发生。老代码默认「负=放电」，只有在「status 是 Discharging
 * 且电流绝对值 ≥ 0.3A」时才学一次真实约定。可静置时电流常在 0.02–0.15A，
 * 永远够不到 0.3A 这个门槛；用户要是在充电状态下开机，也永远遇不到
 * Discharging 样本。结果就是整段使用期都拿默认值猜——碰上「正=放电」的驱动
 * （部分 MTK / 三星）就全程反。
 *
 * 二是学一次就锁死。dischargeSignKnown 一旦为真就不再复核，可驱动行为、
 * 电源路径切换、甚至节点本身换了一个（battery 读不到回落到 bms）都可能让
 * 符号约定变。锁死意味着错一次错到底。
 *
 * 三是 usb/status 混进了候选表。它报的是 USB 端口方向，不是电池相对电源的
 * 方向。插着充电器时它永远说 Charging，哪怕电池在倒贴。
 *
 * 加上老实现把标定结果只放内存，重启就丢，于是每次开机到第一次够大的放电样本
 * 之间又回到默认值——这就是"不定时"反的来历。
 *
 * ## 现在的判定顺序
 *
 * 一、物理约束优先。没接外接电源时，电池只可能放电，不存在"被充电"的可能。
 *     这条不依赖任何驱动报告，是硬事实，能兜住后面所有推断失败。
 * 二、status 节点明确报 Discharging → 放电。报 Charging/Full 时还要看电流：
 *     供电不足时电池在倒贴，内核却仍可能说 Charging。
 * 三、电流符号。这里用持续校正过的约定，不是一次性学习。
 *
 * ## 符号约定的持续校正
 *
 * 只要 status 与电流同时可信就复核一次：status 说放电、电流够大，
 * 那电流的符号就是"放电方向"。不一致就立刻纠正，不再锁死。
 * 校正结果持久化，重启后直接复用，开机第一拍就是对的。
 */
public final class PowerDirection {

    private static final String TAG = "PowerDirection";

    /** 持久化键：放电方向的电流符号（+1 表示正数=放电，-1 表示负数=放电） */
    private static final String PREF_DISCHARGE_SIGN = "battery_discharge_sign";
    private static final String PREF_NAME = "battery_direction";

    /**
     * 符号约定的版本号。
     *
     * 采信规则一变就要加一：老版本按旧规则写下的值可能是错的，必须丢弃重学。
     *
     * 2 = 1.9.6 起。1.9.5 的 calibrate 会在充电时采信电源路径切换的噪声样本，
     * 把约定写反并持久化——这是「充电时符号反」的直接原因。升到 2 之后，
     * 读到版本号不是 2 的存储值就整条丢掉，回落到默认约定重新学。
     */
    private static final String PREF_SIGN_VERSION = "battery_discharge_sign_version";
    private static final int SIGN_VERSION = 2;

    /**
     * 判定的输入。
     *
     * plugged 来自 EXTRA_PLUGGED，是硬件事实：>=0 表示接了外接电源。
     * 它和 status 是两回事——status 是内核的推断，plugged 是物理连接状态。
     */
    public static final class Input {
        /** 电池电流（A），带符号 */
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

        // 电流符号统一成「正进负出」的语义，与判定结果对齐。
        // 不管驱动原来怎么给，对外只暴露这一种约定，上层不用再关心
        float absA = Math.abs(in.currentA);
        r.currentA = r.discharging ? -absA : absA;

        float p = Math.abs(absPowerW);
        r.powerW = r.discharging ? -p : p;
        return r;
    }

    /**
     * 是不是在放电。判定顺序见类注释。
     */
    public static boolean isDischarging(Context context, Input in) {
        calibrate(context, in);

        float absA = Math.abs(in.currentA);
        boolean currentTrustworthy = absA >= Constants.Battery.CURRENT_CREDIBLE_A;

        // ---- 1. 没接任何外接电源 → 必定放电 ----
        //
        // 硬件事实，不需要任何推断。电池在没接电源时不可能被充电
        if (in.plugged <= 0) {
            return true;
        }

        // ---- 2. status 明确说放电 → 放电 ----
        if (in.status != null
                && Constants.Battery.STATUS_DISCHARGING.equalsIgnoreCase(in.status)) {
            return true;
        }

        // ---- 3. status 说充电 / 已满 ----
        //
        // 内核说充电就信它——这是充电器与电池管理芯片共同得出的结论，
        // 比用电流符号去猜可靠得多。原来的顺序让电流压过 status，而符号约定
        // 在没标定过时只是个默认值，一旦机型是「正=放电」就会把正常充电判成
        // 放电，这就是「充电时功耗完全相反」的原因。
        //
        // 唯一的例外是供电不足：插着 USB 打游戏、功耗超过充电器输出时，
        // 内核仍报 Charging，但电池其实在放电。这个场景要认出来（1.8.13 修的
        // 就是它）。但它只在符号约定**已经确认过**时才敢用——没确认过就不知道
        // 电流哪边是出，拿默认值去推翻内核结论，又把正常充电判反了
        if (in.status != null
                && (Constants.Battery.STATUS_CHARGING.equalsIgnoreCase(in.status)
                    || Constants.Battery.STATUS_FULL.equalsIgnoreCase(in.status))) {
            if (currentTrustworthy && dischargeSignKnown(context)) {
                int sign = dischargeSign(context);
                boolean outward = sign > 0 ? in.currentA > 0f : in.currentA < 0f;
                return outward;
            }
            return false;
        }

        // ---- 4. status 没有结论时，用电流方向 ----
        //
        // 只有内核没说清（节点读不到、或者报 Not charging / Unknown）才走到这里。
        // 此时用符号约定判方向；约定没标定过就按 Android 标准（负为放电）
        if (currentTrustworthy) {
            int sign = dischargeSign(context);
            boolean outward = sign > 0 ? in.currentA > 0f : in.currentA < 0f;
            return outward;
        }

        // ---- 5. 兜底：接了电源、又没有放电证据 → 当作在充电 ----
        return false;
    }

    /**
     * 校正「放电时电流是正是负」。
     *
     * 采信条件收得很紧，因为标错了代价很大：约定是持久化的，一旦写成反的，
     * 之后每一次充电都会按反的方向算，直到再遇到一个能纠回来的样本。
     *
     * 三个条件同时成立才采信：
     *
     * 一、**必须没接外接电源**。这是最关键的约束。接了电源时内核的 status 会
     *     在电源路径切换、涓流、充满这些阶段短暂报 Discharging，而电流其实还在
     *     进电。拿这种样本标定，就会把「进电方向」误当成「放电方向」，
     *     约定直接写反——1.9.5 的充电符号反向就是这么来的。
     *     没接电源时不存在歧义：电池只可能放电，status 报 Discharging 是真的。
     *
     * 二、status 明确报 Discharging。
     *
     * 三、电流绝对值够大。没接电源时用 LOW 门槛就够——静置时没有充电电流干扰，
     *     噪声小、方向确定。这也解决了老版本「不打游戏就永远学不到」的问题。
     */
    private static void calibrate(Context context, Input in) {
        // 条件一：必须没接电源。接电源时的 Discharging 样本一律不采信
        if (in.plugged > 0) {
            return;
        }
        // 条件二：status 明确说在放电
        if (in.status == null
                || !Constants.Battery.STATUS_DISCHARGING.equalsIgnoreCase(in.status)) {
            return;
        }
        // 条件三：电流够大，符号才有意义
        if (Math.abs(in.currentA) < Constants.Battery.CURRENT_CREDIBLE_A) {
            return;
        }
        int observed = in.currentA > 0f ? 1 : -1;
        int stored = dischargeSign(context);
        if (observed != stored) {
            writeDischargeSign(context, observed);
            LogUtils.i(TAG + " discharge sign corrected: " + stored + " -> " + observed);
        }
    }

    /**
     * 符号约定确认过没有。
     *
     * 「确认过」指的是真的从一次没接电源的放电样本里学到过，
     * 而不是还在用默认值。未确认时不能拿电流去推翻内核的状态结论
     */
    private static boolean dischargeSignKnown(Context context) {
        try {
            SharedPreferences p = prefs(context);
            if (p == null) {
                return false;
            }
            if (p.getInt(PREF_SIGN_VERSION, 0) != SIGN_VERSION) {
                return false;
            }
            return p.contains(PREF_DISCHARGE_SIGN);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 读持久化的放电符号约定；没有记录、或记录来自旧版规则时按 Android 标准
     * （负为放电）。
     *
     * 版本号不匹配就丢掉旧值——这是清掉 1.9.5 污染的关键。
     */
    private static int dischargeSign(Context context) {
        try {
            SharedPreferences p = prefs(context);
            if (p == null) {
                return Constants.Battery.DISCHARGE_SIGN_DEFAULT;
            }
            int version = p.getInt(PREF_SIGN_VERSION, 0);
            if (version != SIGN_VERSION) {
                // 旧版规则写下的值不可信，丢掉
                if (p.contains(PREF_DISCHARGE_SIGN)) {
                    p.edit().remove(PREF_DISCHARGE_SIGN).apply();
                    LogUtils.i(TAG + " dropped stale discharge sign, ver " + version);
                }
                return Constants.Battery.DISCHARGE_SIGN_DEFAULT;
            }
            return p.getInt(PREF_DISCHARGE_SIGN, Constants.Battery.DISCHARGE_SIGN_DEFAULT);
        } catch (Throwable t) {
            return Constants.Battery.DISCHARGE_SIGN_DEFAULT;
        }
    }

    private static void writeDischargeSign(Context context, int sign) {
        try {
            SharedPreferences p = prefs(context);
            if (p == null) {
                return;
            }
            // 一起写版本号：以后采信规则再改，靠它把这些值作废
            p.edit()
                    .putInt(PREF_DISCHARGE_SIGN, sign)
                    .putInt(PREF_SIGN_VERSION, SIGN_VERSION)
                    .apply();
        } catch (Throwable t) {
            LogUtils.w(TAG + " write sign failed", t);
        }
    }

    private static SharedPreferences prefs(Context context) {
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
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
