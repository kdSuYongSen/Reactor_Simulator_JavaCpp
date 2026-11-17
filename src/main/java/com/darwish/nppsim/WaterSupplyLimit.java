package com.darwish.nppsim;

/**
 * 水源可供量（kg / 本 tick，1 tick = 50 ms）。
 *
 * <p>与 {@link SteamSupplyLimit} 对称：给水泵的吸入母管原先只是把"要抽多少"
 * 平均分给各除氧器，从不问源端还有没有水。于是给水流量大于凝结水回收量时，
 * 除氧器被一路抽到负水位 —— 面板上表现为"除氧器水位莫名其妙一直掉"。</p>
 *
 * <p>实现本接口后，母管在分配抽水前先问源端"你还有多少可抽"，
 * 不够就按可供量限流（给水自然减量，而不是把源抽干）。</p>
 */
interface WaterSupplyLimit {
    /** 本 tick 最多能被抽走的水量（kg）。返回 Double.POSITIVE_INFINITY 表示不限。 */
    double availableWaterKg();
}
