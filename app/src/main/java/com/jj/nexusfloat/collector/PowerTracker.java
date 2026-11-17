package com.jj.nexusfloat.collector;

import com.jj.nexusfloat.constant.Constants;

/**
 * 功率读取与突变守卫。
 *
 * v1.9.11 起只读内核的 power_now，并且保留它的符号。
 *
 * 之前的实现是「电流 × 电压 优先，power_now 兜底」，两步都取绝对值，
 * 方向另外靠推断。推断这条路走过 status 节点、电流符号、电量趋势，都不对，
 * 因为那些都不是「电池净功率」的直接测量。
 *
 * power_now 是内核直接算好的功率，符号就是方向。唯一要做的是别把符号弄丢——
 * 这也是之前一直失败的原因：读它的槽位构造时漏了 keepSign。
 *
 * ## 突变守卫
 *
 * 保留原有逻辑，但改成按绝对值判断、按原符号返回。功率从 +8W 跳到 -8W
 * 说明电池从充电切到放电，这是真实的方向变化，不该被当成尖峰拦掉——
 * 所以只要符号变了就直接放行。
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
     * 采一功率。
     *
     * signedPowerW 是 power_now 的带符号读数，0 表示读不到。
     * 返回带符号的功率，读不到时沿用旧值，再不行返回 0。
     */
    public float resolve(float signedPowerW) {
        if (signedPowerW != 0f) {
            float abs = Math.abs(signedPowerW);
            boolean sane = abs >= Constants.Battery.POWER_MIN_W
                    && abs <= Constants.Battery.POWER_MAX_W;
            if (sane) {
                // 符号变了说明充放电方向切换，这是真实变化，直接放行
                boolean signFlip = lastValid != 0f
                        && (lastValid > 0f) != (signedPowerW > 0f);
                if (signFlip || !isSpike(abs)) {
                    spikeTicks = 0;
                } else if (++spikeTicks < Constants.Battery.POWER_SPIKE_CONFIRM_TICKS) {
                    // 疑似尖峰还没确认，先沿用旧值
                    lastFresh = false;
                    return lastValid;
                } else {
                    spikeTicks = 0;
                }
                lastValid = signedPowerW;
                staleTicks = 0;
                lastFresh = true;
                return signedPowerW;
            }
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
     * 上一次 resolve 返回的是不是本拍新读到的值。
     *
     * 必须在每次 resolve 之后立刻读，它反映的是「最近一次调用」的结果。
     */
    public boolean lastWasFresh() {
        return lastFresh;
    }

    /**
     * 是不是疑似尖峰。
     *
     * 按绝对值比较：上次已有有效值、新值超过其若干倍、且绝对增量够大。
     * 两条都满足才算，避免小功率区间（0.1W→0.9W）被误伤。
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
