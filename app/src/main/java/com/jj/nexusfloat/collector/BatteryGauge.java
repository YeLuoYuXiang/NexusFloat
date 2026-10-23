package com.jj.nexusfloat.collector;

import android.content.Context;
import android.os.BatteryManager;

import com.jj.nexusfloat.constant.Constants;

/**
 * 电池电气量的公共取数（功率 / 电流 / 电压 / 方向）。
 *
 * 为什么单独抽出来：监视条和帧记录都要算功率，但两边跑在不同进程、面对的场景
 * 也不同。各写一套必然对不上——监视条显示 +8W，帧记录曲线里却是 --，用户没法信。
 * 这里把监视条那套已经踩过坑的实现收拢成一处，两边共用。
 *
 * 三条取数原则（都是被实际机型逼出来的）：
 *
 * 1. 电流优先 sysfs 直读，BatteryManager 只作兜底。
 *    getIntProperty(CURRENT_NOW) 在部分 ROM 上返回的是随电池状态更新的缓存，
 *    静态场景下几秒都不动，功率会跟着凝固。sysfs 的 current_now 是内核瞬时值。
 *
 * 2. 电压同样 sysfs 优先。广播的 EXTRA_VOLTAGE 只在电池状态「有变化」时才刷新，
 *    插着线静置时可能几十秒不动。
 *
 * 3. 方向靠 status 节点 + 电流符号，不看系统报的充电状态。
 *    插着 USB 时系统永远说「充电中」，可游戏功耗超过供电时电池其实在倒贴，
 *    功率这时候该是负的。
 */
public final class BatteryGauge {

    private final Context context;
    private final BatteryManager batteryManager;
    private final PowerTracker powerTracker = new PowerTracker();

    /** 上一次读到的充电状态节点原文，附带短缓存避免每拍都读文件 */
    private String statusText;

    public BatteryGauge(Context context) {
        this.context = context.getApplicationContext();
        this.batteryManager =
                (BatteryManager) this.context.getSystemService(Context.BATTERY_SERVICE);
    }

    /**
     * 采一次功率与方向。
     *
     * 返回值的符号就是「电在往哪流」：正数进电池（充电），负数出电池（放电）。
     * 读不到返回 0。
     */
    public Result sample() {
        Result r = new Result();

        float currentA = readCurrentA();
        float voltageV = readVoltageV();
        String status = readStatusText();

        r.discharging = isDischarging(currentA, status);
        r.currentA = currentA;
        r.voltageV = voltageV;

        float power = powerTracker.resolve(Math.abs(currentA), voltageV,
                () -> SysfsReader.direct().readBatteryPowerW());
        if (power > 0f) {
            r.powerW = r.discharging ? -power : power;
        }
        return r;
    }

    /** 一次采样的结果 */
    public static final class Result {
        /** 带符号功率（W）：正进负出；读不到是 0 */
        public float powerW;
        /** 带符号电流（A）：正进负出；读不到是 0 */
        public float currentA;
        public float voltageV;
        /** 是否正在放电 */
        public boolean discharging;
    }

    /**
     * 读电池电流（A），带符号。
     *
     * 顺序：sysfs 直读 → root 直读 → BatteryManager。前两条是内核瞬时值，
     * 最后一条只在前面都拿不到时兜底。
     */
    private float readCurrentA() {
        float a = SysfsReader.direct().readBatteryCurrentA();
        if (a != 0f) {
            return a;
        }
        a = SysfsReader.root().readBatteryCurrentA();
        if (a != 0f) {
            return a;
        }
        if (batteryManager != null) {
            int raw = batteryManager.getIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (raw != 0 && raw != Integer.MIN_VALUE) {
                // ABI 规定 µA，但部分 ROM 给 mA；两者有效区间不重叠，可判定
                a = SysfsReader.parseScaledSigned(String.valueOf(raw),
                        Constants.Battery.CURRENT_DIVISORS,
                        Constants.Battery.CURRENT_MIN_A,
                        Constants.Battery.CURRENT_MAX_A, true);
                if (a != 0f) {
                    return a;
                }
            }
        }
        return 0f;
    }

    /** 读电压（V）。sysfs 优先，读不到才用兜底值 */
    private float readVoltageV() {
        float v = SysfsReader.direct().readBatteryVoltageV();
        if (v > 0f) {
            return v;
        }
        v = SysfsReader.root().readBatteryVoltageV();
        if (v > 0f) {
            return v;
        }
        return Constants.Battery.VOLTAGE_FALLBACK_V;
    }

    /** 读充电状态节点原文，读不到返回 null */
    private String readStatusText() {
        String s = SysfsReader.direct().readBatteryStatus();
        if (s == null) {
            s = SysfsReader.root().readBatteryStatus();
        }
        statusText = s;
        return s;
    }

    /**
     * 判断是不是在放电。
     *
     * status 明确报 Discharging 直接采信；报 Charging 时再看电流——电流明显朝外流
     * 说明供电跟不上、电池在补差额，这时候仍算放电。
     */
    private boolean isDischarging(float currentA, String status) {
        if (status != null) {
            if (Constants.Battery.STATUS_DISCHARGING.equalsIgnoreCase(status)) {
                return true;
            }
            boolean claimsCharging =
                    Constants.Battery.STATUS_CHARGING.equalsIgnoreCase(status)
                            || Constants.Battery.STATUS_FULL.equalsIgnoreCase(status);
            if (claimsCharging) {
                // 内核说在充电，但电流明显朝外：电池在倒贴
                return currentA < -Constants.Battery.DISCHARGE_HINT_A;
            }
        }
        return currentA < 0f;
    }
}
