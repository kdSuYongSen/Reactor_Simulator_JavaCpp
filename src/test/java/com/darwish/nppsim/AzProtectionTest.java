package com.darwish.nppsim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * AZ 分层保护的回归约束（2026-11-04 按新的真实逻辑重写）。
 *
 * <p>新逻辑里 AZ-1~AZ-6 不是一条"功率阶梯"：</p>
 * <ul>
 *   <li>AZ-1 盯一回路压力、AZ-4 盯分离器水位、AZ-6 盯主蒸汽母管压力，只有 AZ-2/AZ-3 是功率档；</li>
 *   <li>真正会插棒的只有 AZ-1K（快速功率保护）和 BSM（快速事故保护），AZ-5 为手动；</li>
 *   <li>AZ-1~AZ-4 越限只报警 + 联锁，持续越限才升级到 AZ-1K。</li>
 * </ul>
 *
 * <p>这里守住的都是"读常量就能验"的硬约束，不启动仿真、不加载 native 库：</p>
 * <ol>
 *   <li>功率档必须高于额定 4800 MW，并按 AZ-2 &lt; AZ-3 &lt; AZ-1K 严格递增；</li>
 *   <li>AZ-1K 的功率定值就是 120% 额定，BSM 用同一定值兜底；</li>
 *   <li>信号越限的升级延时随功率档升高而缩短；</li>
 *   <li>压力/水位的定值链必须逐级放宽（信号 &lt; AZ-1K &lt; BSM）。</li>
 * </ol>
 */
public class AzProtectionTest {

    private static final double RATED_THERMAL_MW = 4800.0;

    @Test
    public void powerSignalsSitAboveRatedAndIncreaseTowardsAz1k() {
        assertTrue("AZ-2（106%）必须高于额定热功率",
                AutoControl.AZ2_POWER_MW > RATED_THERMAL_MW);
        assertTrue("AZ-3 必须高于 AZ-2",
                AutoControl.AZ3_POWER_MW > AutoControl.AZ2_POWER_MW);
        assertTrue("AZ-1K 必须高于 AZ-3",
                AutoControl.AZ1K_POWER_MW > AutoControl.AZ3_POWER_MW);
    }

    @Test
    public void az1kTripPointIsOneHundredTwentyPercent() {
        assertEquals("AZ-1K 功率定值应为 120% 额定",
                RATED_THERMAL_MW * 1.20, AutoControl.AZ1K_POWER_MW, 1.0);
        assertEquals("BSM 与 AZ-1K 用同一个功率定值（AZ-1K 压不住才动作）",
                AutoControl.AZ1K_POWER_MW, AutoControl.BSM_POWER_MW, 0.001);
    }

    @Test
    public void higherPowerBandEscalatesSooner() {
        assertTrue("AZ-3 的升级延时必须短于 AZ-2（功率越高越早升级）",
                AutoControl.AZ3_ESCALATE_S < AutoControl.AZ2_ESCALATE_S);
        assertTrue("所有升级延时都必须为正（0 表示不升级，那是 AZ-5/AZ-6 的语义）",
                AutoControl.AZ3_ESCALATE_S > 0 && AutoControl.AZ2_ESCALATE_S > 0);
    }

    @Test
    public void pressureAndLevelLimitsWidenTowardsBsm() {
        assertTrue("AZ-1K 的压力定值要高于 AZ-1 信号定值",
                AutoControl.AZ1K_DRUM_PRESS_MPA > AutoControl.AZ1_DRUM_PRESS_MPA);
        assertTrue("BSM 的压力定值要高于 AZ-1K",
                AutoControl.BSM_DRUM_PRESS_MPA > AutoControl.AZ1K_DRUM_PRESS_MPA);

        assertTrue("AZ-4 的水位下限要高于 AZ-1K（后者更极端）",
                AutoControl.AZ4_LEVEL_LO > AutoControl.AZ1K_LEVEL_LO);
        assertTrue("AZ-1K 的水位下限要高于 BSM",
                AutoControl.AZ1K_LEVEL_LO > AutoControl.BSM_LEVEL_LO);
    }

    @Test
    public void ratedPowerConstantMatchesCoreRated() {
        assertEquals("AZ 用的额定热功率必须和标准工况一致",
                StandardIC.RATED_POWER_MW, AutoControl.RATED_POWER_MW, 0.001);
        assertEquals("额定热功率 4800 MW", RATED_THERMAL_MW, AutoControl.RATED_POWER_MW, 0.001);
    }
}
