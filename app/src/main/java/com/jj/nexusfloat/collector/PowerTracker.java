package com.jj.nexusfloat.collector;

import com.jj.nexusfloat.constant.Constants;

/**
 * 功率读取与突变守卫。
 *
 * ## 为什么改回电流
 *
 * 上一版只读 power_now，结果在用户机器上读出来是 0，整个功率显示消失。
 * power_now 是 power_supply ABI 里的可选属性，不少机型压根没实现。
 * current_now 是普遍实现的，配合 voltage_now 就能算出功率。
 *
 * ## 符号怎么处理
 *
 * 电流带符号传入（负放电、正充电），本类只对**绝对值**做区间校验和突变守卫，
 * 判定通过后按**原始符号**返回。这样负功率不会被误当成「读不到」，
 * 方向信息也不会在任何一步被抹掉。
 */
public final class PowerTracker {

    /** 上一次的有效值（保留符号） */
    private float lastValid;
    /** 连续被判成尖峰的次数 */
    private int spikeTicks;
    /** 连续读不到的次数 */
    private int staleTicks;
    /** 最近一次返回的是不是新读到的值 */
    private boolean lastFresh;

    /**
     * 采一次功率。
     *
     * signedCurrentA 是带符号的电流（A），voltageV 是电压。
     * 返回带符号功率：负值放电、正值充电。读不到时沿用旧值。
     */
    public float resolve(float signedCurrentA, float voltageV) {
        float signedPower = calcPower(signedCurrentA, voltageV);

        if (signedPower != 0f) {
            float abs = Math.abs(signedPower);
            // 符号翻转说明充放电方向切换，是真实变化，直接放行
            boolean signFlip = lastValid != 0f
                    && (lastValid > 0f) != (signedPower > 0f);
            if (signFlip || !isSpike(abs)) {
                spikeTicks = 0;
            } else if (++spikeTicks < Constants.Battery.POWER_SPIKE_CONFIRM_TICKS) {
                lastFresh = false;
                return lastValid;
            } else {
                spikeTicks = 0;
            }
            lastValid = signedPower;
            staleTicks = 0;
            lastFresh = true;
            return signedPower;
        }

        // 读不到：沿用旧值几拍，之后归零
        if (staleTicks < Constants.Battery.STALE_MAX_TICKS) {
            staleTicks++;
            lastFresh = false;
            return lastValid;
        }
        lastValid = 0f;
        lastFresh = false;
        return 0f;
    }

    /**
     * 电流 × 电压，符号跟着电流。
     *
     * 不做 Math.abs——负电流必须算出负功率，否则方向信息在这一步就没了
     */
    private static float calcPower(float signedCurrentA, float voltageV) {
        if (signedCurrentA == 0f || voltageV <= 0f) {
            return 0f;
        }
        float power = signedCurrentA * voltageV;
        float abs = Math.abs(power);
        boolean sane = abs >= Constants.Battery.POWER_MIN_W
                && abs <= Constants.Battery.POWER_MAX_W;
        return sane ? power : 0f;
    }

    /**
     * 上一次 resolve 返回的是不是本拍新读到的值。
     *
     * 必须在每次 resolve 之后立刻读，它反映的是「最近一次调用」的结果。
     */
    public boolean lastWasFresh() {
        return lastFresh;
    }

    /**
     * 是不是疑似尖峰。按绝对值比较，不影响符号。
     */
    private boolean isSpike(float absPower) {
        if (lastValid == 0f) {
            return false;
        }
        float lastAbs = Math.abs(lastValid);
        return absPower > lastAbs * Constants.Battery.POWER_SPIKE_RATIO
                && absPower - lastAbs > Constants.Battery.POWER_SPIKE_MIN_DELTA_W;
    }
}
