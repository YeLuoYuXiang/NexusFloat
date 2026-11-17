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
     * 用电流符号判定方向，并算出带符号的功率。
     *
     * signedCurrentA 是内核电流，符号原样保留。
     * voltageV 用来把电流换算成功率。
     */
    public static Result resolve(float signedCurrentA, float voltageV, float dualCellFactor) {
        Result r = new Result();

        r.currentA = signedCurrentA;
        r.discharging = signedCurrentA < 0f;

        // 功率 = 电流 × 电压，符号跟着电流走。
        // 双电芯机型 current_now 常只报单颗电芯的电流，乘倍率补齐
        float absPower = Math.abs(signedCurrentA) * voltageV * dualCellFactor;
        r.powerW = r.discharging ? -absPower : absPower;
        return r;
    }
}
