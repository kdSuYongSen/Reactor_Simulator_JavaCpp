package com.darwish.nppsim;

import java.io.Serializable;

/**
 * 汽轮机保护（2026-11-03）——按真实机组的保护清单重写，替换原来那几条散落在
 * {@link TG#update()} 里的判据。
 *
 * <h2>真实汽轮机的停机保护</h2>
 * <table border="1">
 *   <caption>本模型实现的保护项</caption>
 *   <tr><th>保护</th><th>定值</th><th>能否旁路</th><th>依据</th></tr>
 *   <tr><td><b>机械超速脱扣</b>（危急保安器/飞锤）</td><td><b>111.5% ≈ 3345 rpm</b></td>
 *       <td><b>不能</b></td><td>飞锤靠离心力直接打掉脱扣杠杆，纯机械，与电气保护无关。
 *           试验标准就是 110~112%，取 111.5%</td></tr>
 *   <tr><td>电气超速</td><td>110% = 3300 rpm</td><td>能</td><td>电磁式超速保护，比机械低一档</td></tr>
 *   <tr><td>低真空</td><td>凝汽器 &gt; 0.030 MPa</td><td>能</td><td>真空跌 → 排汽温度升高 → 低压缸与凝汽器过热</td></tr>
 *   <tr><td>低主汽压力</td><td>母管 &lt; 2.0 MPa（已并网）</td><td>能</td><td>进汽压力不足会把末级叶片吹成鼓风工况</td></tr>
 *   <tr><td>低汽包压力（一回路）</td><td>汽包 &lt; 5.0 MPa 且转速 &gt; 300</td><td>能</td><td>汽源失去，进汽过热度不足、带水</td></tr>
 *   <tr><td>发电机过负荷</td><td>&gt; 825 MW</td><td>能</td><td>定子过流/过负荷</td></tr>
 *   <tr><td>逆功率</td><td>功率 &lt; 0 持续 7 s</td><td>能</td><td>汽源失去后发电机变成电动机，末级叶片过热</td></tr>
 *   <tr><td><b>低润滑油压</b></td><td><b>母管 &lt; 0.8 bar 且转速 &gt; 300，延时 3 s</b></td><td>能</td>
 *       <td>轴承断油会烧瓦（2026-11-16 随 A/B 润滑油泵加入）</td></tr>
 * </table>
 *
 * <p><b>关于"关掉自动跳闸"</b>：面板上的 ON/OFF 只旁路<b>电气</b>保护；
 * 转速到 111.5% 时机械卡扣照样脱扣，并在窗口上亮红色"机械脱扣"。</p>
 */
final class TurbineProtection implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 机械超速脱扣转速（111.5% × 3000）。**任何开关都旁路不了这条。** */
    static final double MECHANICAL_TRIP_RPM = 3345.0;
    /** 电气超速（110%） */
    static final double ELECTRICAL_TRIP_RPM = 3300.0;
    /** 低真空定值：凝汽器压力高于此值跳机 */
    static final double LOW_VACUUM_MPA = 0.030;
    /** 低主汽压力定值（主蒸汽母管） */
    static final double LOW_STEAM_MPA = 2.0;
    /** 低汽包压力定值（一回路） */
    /**
     * 低汽包压力跳机定值 MPa（2026-11-04 由 5.0 下调到 4.5）。
     *
     * <p>RBMK-1500 汽包额定 6.9 MPa，低汽包跳机的目的只是"别让汽机被无汽干转"。
     * 5.0 MPa 相当于只剩 72 % 额定，副产瞬态（升功率的那一分钟里汽包从 5.0 上下摆）
     * 就会误跳，而误跳又引发 AZ-1「Turbine Trip」→ 全堆停堆，把整条二回路打断。
     * 4.5 MPa（65 %）才真正对应"汽源已经失去"。</p>
     */
    /**
     * 低汽包压力跳机定值 MPa（2026-11-07 由 4.5 下调到 3.4）。
     *
     * <p>单回路下汽包压力在升负荷瞬态会掉到 4.3~4.8（堆功率跟不上抽汽），
     * 4.5 会在这个正常瞬态里把汽机打掉，一跳又经 AZ-1「Turbine Trip」停堆。
     * 3.4 MPa（49 % 额定）才真正对应"汽源已失去"。</p>
     */
    static final double LOW_DRUM_MPA = 3.4;
    /** 低汽包压力延时 s：饱和蒸汽压力的小幅摆动不跳机 */
    static final double LOW_DRUM_DELAY_S = 3.0;
    /** 低汽包累计秒数 */
    private double lowDrumSeconds = 0.0;
    /** 发电机过负荷定值 */
    static final double HIGH_LOAD_MW = 825.0;
    /** 逆功率延时 s */
    static final double REVERSE_POWER_DELAY_S = 15.0;   // 2026-11-04：7 s 太敏感，供汽短暂中断就跳机
    /** 机械脱扣的复位门槛：转速必须低于额定才允许复位（防止还在超速时复位又立刻脱扣） */
    static final double MECHANICAL_RESET_MAX_RPM = 3050.0;
    /** 低润滑油压定值 bar（母管，2026-11-16 随润滑油系统加入） */
    static final double LOW_LUBE_BAR = 0.8;
    /** 低润滑油压延时 s：油泵切换的瞬时跌落不跳机 */
    static final double LOW_LUBE_DELAY_S = 3.0;
    /** 低油压累计秒数 */
    private double lubeLowSeconds = 0.0;

    /** 自动跳闸总开关（关掉只旁路电气保护） */
    boolean autoTripEnabled = true;

    /** 机械脱扣已动作（锁存；必须人工复位） */
    boolean mechanicalTripped = false;

    /** 最近一次跳闸原因（界面显示用，空串表示没跳过） */
    String tripReason = "";

    /** 逆功率累计时间 s（内部） */
    private double reversePowerSeconds = 0.0;

    /** 装载标准工况后的启动旁路剩余 tick（见 TG#suppressStartupProtection） */
    private int startupGraceTicks = 0;

    /** 给启动瞬态一段旁路（tick 数，50 ms/tick） */
    void suppressStartupProtection(int ticks) {
        startupGraceTicks = Math.max(0, ticks);
    }

    /** 人工复位：清锁存、清原因、清逆功率计时（转速条件由调用方检查） */
    void reset() {
        mechanicalTripped = false;
        tripReason = "";
        reversePowerSeconds = 0.0;
        lubeLowSeconds = 0.0;
    }

    /** 机械脱扣是否还允许复位（转速已降下来） */
    boolean canResetMechanical(double rpm) {
        return rpm < MECHANICAL_RESET_MAX_RPM;
    }

    /**
     * 每帧评估所有保护。
     *
     * @param tg 本机
     * @param dt 帧长 s
     */
    void evaluate(TG tg, double dt) {
        double rpm = tg.getRpm();

        /* ---- 1) 机械超速脱扣：最先判，且不受 autoTripEnabled 影响 ---- */
        if (rpm >= MECHANICAL_TRIP_RPM) {
            if (!mechanicalTripped) {
                mechanicalTripped = true;
                tripReason = "Mechanical Overspeed 111.5%";
                tg.trip("Mechanical Overspeed 111.5%");
            }
            return;      // 机械脱扣后电气保护无需再判
        }

        if (!autoTripEnabled) {
            return;      // 电气保护被旁路（面板 ON/OFF）
        }
        if (startupGraceTicks > 0) {
            startupGraceTicks--;
            return;
        }
        if (tg.isTripped()) {
            return;      // 已跳闸：等人工复位
        }

        /* ---- 2) 电气超速 ---- */
        if (rpm >= ELECTRICAL_TRIP_RPM) {
            trip(tg, "Electrical Overspeed 110%");
            return;
        }

        /* ---- 3) 低真空（只在转动时判：停机时常压不跳） ---- */
        if (rpm > 300.0 && tg.condenser.getPressure() > LOW_VACUUM_MPA) {
            trip(tg, "Low Condenser Vacuum");
            return;
        }

        /* ---- 4) 低汽包压力（一回路汽源，延时 3 s） ---- */
        boolean lowDrum = rpm > 300.0 && (NPPSim.mcc.drum1.getPressure() < LOW_DRUM_MPA
                || NPPSim.mcc.drum2.getPressure() < LOW_DRUM_MPA);
        if (lowDrum) {
            lowDrumSeconds += dt;
            if (lowDrumSeconds >= LOW_DRUM_DELAY_S) {
                trip(tg, "Low Drum Pressure");
                return;
            }
        } else {
            lowDrumSeconds = 0.0;
        }

        /* ---- 5) 低主汽压力（并网后才判：冲转升速阶段母管本来就在建立） ---- */
        double headerP = NPPSim.steamPiping != null ? NPPSim.steamPiping.getPressure() : 0.0;
        if (tg.isSynced() && headerP < LOW_STEAM_MPA) {
            trip(tg, "Low Main Steam Pressure");
            return;
        }

        /* ---- 6) 发电机过负荷 ---- */
        if (tg.getGeneratorLoad() > HIGH_LOAD_MW) {
            trip(tg, "High Generator Load");
            return;
        }

        /* ---- 7) 逆功率（延时 7 s，替代原来"新开一个线程 sleep"的写法） ---- */
        if (tg.isSynced()) {
            if (tg.getGeneratorLoad() < 0.0) {
                reversePowerSeconds += dt;
                if (reversePowerSeconds >= REVERSE_POWER_DELAY_S) {
                    trip(tg, "Reverse Power");
                }
            } else {
                reversePowerSeconds = 0.0;
            }
        } else {
            reversePowerSeconds = 0.0;
        }
        /* ---- 8) 低润滑油压（延时 3 s，只在转动时判：盘车/停机不需要油压） ---- */
        if (rpm > 300.0 && tg.getLubePress() < LOW_LUBE_BAR) {
            lubeLowSeconds += dt;
            if (lubeLowSeconds >= LOW_LUBE_DELAY_S) {
                trip(tg, "Low Lube Oil Pressure");
            }
        } else {
            lubeLowSeconds = 0.0;
        }
    }

    private void trip(TG tg, String reason) {
        tripReason = reason;
        tg.trip(reason);
    }
}
