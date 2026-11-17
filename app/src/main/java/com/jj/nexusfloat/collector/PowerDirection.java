package com.jj.nexusfloat.collector;

import android.content.Context;

import com.jj.nexusfloat.constant.Constants;

/**
 * 功率方向的唯一依据：内核电流的符号。
 *
 * ## 只认电流符号
 *
 * current_now 是内核报告的电池实时电流，符号就是方向：负值电池在放电、
 * 正值在充电。这是实测值，不是推断。
 *
 * 之前试过 status 节点、电量趋势、power_now，都不行：
 * - status 反映的是「充电器接着没有」，不是「电池净流向」。插着充电器打游戏、
 *   功耗超过充电器输出时它照样报 Charging
 * - 电量趋势要等掉一格，太慢
 * - power_now 在部分机型上压根没实现，读出来是 0
 *
 * ## 关键：不能取绝对值
 *
 * 整条链路上任何一处取了绝对值，方向信息就丢了，上层再怎么猜都不对。
 * 这个类不做任何绝对值处理，负号原样保留。
 */
public final class PowerDirection {

    /** 判定结果 */
    public static final class Result {
        /** 是否在放电（电池向负载供电） */
        public boolean discharging;
        /** 带符号的功率（W）：负值放电、正值充电 */
        public float powerW;
        /** 带符号的电流（A）：负值放电、正值充电 */
        public float currentA;
    }

    private PowerDirection() {
    }

    /**
     * 电流符号约定：1 表示内核给的是标准约定（负=放电），-1 表示反过来（正=放电）。
     *
     * 实测部分机型的 current_now 是「正=放电」，与 Linux power_supply ABI
     * 规定的「负=放电」相反。这类机器上所有状态（放电、充电、超载放电）的
     * 符号都会整体反过来。
     *
     * 只在这一处翻转，后面全部按标准语义（负=放电）处理，
     * 避免多处翻导致互相抵消。
     */
    private static final float CURRENT_SIGN_FIX = -1f;

    /**
     * 把内核电流归一化成标准语义（负=放电、正=充电）。
     *
     * 调用方应该在读完电流后立刻调它，之后所有计算都用归一化后的值。
     * 这样只有一个地方做翻转，不会出现某处翻了某处没翻导致符号打架。
     */
    public static float normalizeCurrent(float rawCurrentA) {
        return rawCurrentA * CURRENT_SIGN_FIX;
    }

    /**
     * 用归一化后的电流判定方向，并算出带符号的功率。
     *
     * currentA 必须已经过 normalizeCurrent 处理。
     * voltageV 用来把电流换算成功率。
     */
    public static Result resolve(float currentA, float voltageV, float dualCellFactor) {
        Result r = new Result();

        r.currentA = currentA;
        r.discharging = currentA < 0f;

        // 功率 = 电流 × 电压，符号跟着电流走。
        // 双电芯机型 current_now 常只报单颗电芯的电流，乘倍率补齐
        float absPower = Math.abs(currentA) * voltageV * dualCellFactor;
        r.powerW = r.discharging ? -absPower : absPower;
        return r;
    }
}
