package com.jj.nexusfloat.collector;

import com.jj.nexusfloat.constant.Constants;

/**
 * 电池功率的取值状态机，决定这一轮用哪个来源的读数。
 *
 * 单独抽出来是为了能脱离 Android 环境跑单测。功率取值线上出过两次问题
 * （联发科恒 0、高通锁死 -10W），这种判定逻辑还是得有回归保护。
 *
 * 两条规则：
 * 一是静态值识别。power_now 连着好几轮读到同一个数，就当成额定功率那类静态量，
 * 以后永远不用了。真实的瞬时功率不可能十秒一字不差。
 * 二是陈旧值上限。读不到的时候可以短暂沿用上次的有效值，把抖动抹平，但沿用是有
 * 次数上限的。数据源彻底失效就回到 0，不能锁死在一个假读数上。
 *
 * 这里不管正负号，符号由外部的充放电状态决定。
 */
final class PowerTracker {

    /** 惰性功率来源，真要用的时候才去调，省掉没必要的 sysfs 读取 */
    interface Source {
        /** 返回功率绝对值（W），读不到就 0 */
        float get();
    }

    /** power_now 上次读到多少、连着重复了几次，用来认静态额定值 */
    private float lastPowerNow = -1f;
    private int powerNowRepeats;
    private boolean powerNowIsStatic;

    /** 上次的有效功率，以及已经连着沿用了多少轮 */
    private float lastValid;
    private int staleTicks;
    /** 疑似尖峰（功率突然比上次有效值大一大截）连着出现了几轮 */
    private int spikeTicks;

    /**
     * 按优先级选出这一轮用哪个功率值。
     *
     * currentA 是电流绝对值（A），读不到就传 0；voltageV 是电压。
     * powerNow 是惰性来源，只有电流那条路走不通、或者这节点已经被判定为
     * 静态值时才去调它。
     *
     * 返回功率绝对值（W），没得用就返回 0。
     */
    float resolve(float currentA, float voltageV, Source powerNow) {
        float power = fromCurrent(currentA, voltageV);
        if (power <= 0) {
            power = fromPowerNow(powerNow);
        }

        if (power > 0) {
            // 突变守卫：正常功率不会一拍之内翻好几倍。
            // 疑似尖峰就先沿用上次有效值，连着好几拍都这么大才当成真变化
            if (isSpike(power)) {
                if (++spikeTicks < Constants.Battery.POWER_SPIKE_CONFIRM_TICKS) {
                    return lastValid;
                }
                // 连着确认了：接受这个新水平，计数清零
                spikeTicks = 0;
            } else {
                spikeTicks = 0;
            }
            lastValid = power;
            staleTicks = 0;
            return power;
        }

        if (staleTicks < Constants.Battery.STALE_MAX_TICKS) {
            staleTicks++;
            return lastValid;
        }
        lastValid = 0f;
        return 0f;
    }

    /**
     * 是不是疑似尖峰。
     *
     * 判据是：上次已经有有效值，新值超过它的若干倍，而且绝对增量也够大。
     * 两条都得满足，不然小功率区间（0.1W→0.9W）会被误伤。
     */
    private boolean isSpike(float power) {
        if (lastValid <= 0f) {
            return false;
        }
        return power > lastValid * Constants.Battery.POWER_SPIKE_RATIO
                && power - lastValid > Constants.Battery.POWER_SPIKE_MIN_DELTA_W;
    }

    /** 电流 × 电压。语义清楚，各平台基本都实现，优先走这条 */
    private static float fromCurrent(float currentA, float voltageV) {
        if (currentA <= 0 || voltageV <= 0) {
            return 0f;
        }
        float power = currentA * voltageV;
        boolean sane = power >= Constants.Battery.POWER_MIN_W
                && power <= Constants.Battery.POWER_MAX_W;
        return sane ? power : 0f;
    }

    /** power_now。只有确认它会变才采纳 */
    private float fromPowerNow(Source source) {
        if (powerNowIsStatic || source == null) {
            return 0f;
        }
        float powerNowW = source.get();
        if (powerNowW <= 0) {
            return 0f;
        }
        if (powerNowW == lastPowerNow) {
            if (++powerNowRepeats >= Constants.Battery.POWER_CONSTANT_REPEATS) {
                powerNowIsStatic = true;
                return 0f;
            }
        } else {
            lastPowerNow = powerNowW;
            powerNowRepeats = 0;
        }
        return powerNowW;
    }
}
