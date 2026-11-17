package com.darwish.nppsim;

import java.util.ArrayList;

/**
 * 全环路逻辑自检（2026-11-02，v2）。
 *
 * <p>按真实额定运行工况整定：8 台主泵 + 凝汽器/凝结水泵 + 给水泵全投，主汽门全开，
 * 堆芯 4800 MW。**不用 feedwaterOverrideKgs** —— 那个覆盖值是往换热器管侧
 * *额外*注入 3000 kg/s（`HeatExchanger.updateWaterInflow` 是累加），
 * 会凭空造水，用它做物料台账自检没有意义。</p>
 *
 * <p>观察窗内对各段流量取平均（系统有 10~20 s 量级的振荡，瞬时值没有可比性），核对：</p>
 * <ol>
 *   <li>一回路闭合：主泵 ≈ 压力母管出流 ≈ 1661 根通道进水；</li>
 *   <li>汽量链闭合：通道产汽 ≈ 换热器一次侧进汽 ≈ 二次侧产汽 ≈ 分离器出口 ≈ 汽轮机进汽；</li>
 *   <li>能量平衡：换热器一次侧放热 ≈ 二次侧产汽 × h_fg；汽轮机出力 ≈ 汽量 × 单位功 × η；</li>
 *   <li>全厂水量台账漂移；</li>
 *   <li>汽包水位 / 凝汽器热井不越界、不持续漂移；</li>
 *   <li>关键读数无 NaN、无负质量。</li>
 * </ol>
 */
public class LoopSelfCheck {

    static int fails = 0;

    // ---- 观察窗累加器 ----
    static double sMcp, sHdr, sChan, sChanSteam, sDrumSteam, sPrimIn, sPrimMW, sSec,
            sSecHeat, sSepOut, sTgIn, sWater, sSteam, sHotwell, sLevel, sMWe, sDrain;
    static int n;

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(800);

        // ---------- 整定到额定工况 ----------
        start(NPPSim.mcc.mcp);
        start(NPPSim.condensate1A);
        start(NPPSim.condensate2A);
        start(NPPSim.condensate1B);
        start(NPPSim.condensate2B);
        start(NPPSim.mainFeedwaterPumps);
        start(NPPSim.auxFeedwaterPumps);
        // 除盐水箱 → 除氧器补水的 4 台 ADMS 泵（补水源：除氧器水位低时补水）
        start(NPPSim.pcs.admsPumps);
        // 凝汽器冷却水泵（8 台，每台 45 m³/s）：不开的话冷却水出口温度会算出
        // 上万度，2026-11-02 之前这会直接把蒸汽表查表撑爆、整机停摆。
        startCondenserPumps(NPPSim.tg1);
        startCondenserPumps(NPPSim.tg2);
        // 主汽门全开（默认关闭；不开则汽轮机进汽恒为 0，二回路蒸汽无处可去）
        for (SteamValve v : NPPSim.TG1InletValves) {
            v.setAutoState(1);
            v.setState(2);
            v.setPosition(1.0f);
        }
        for (SteamValve v : NPPSim.TG2InletValves) {
            v.setAutoState(1);
            v.setState(2);
            v.setPosition(1.0f);
        }
        /* 投入给水/除氧器水位自动（2026-11-02 环路自检定位到的断点）：
         * 除氧器的水位自动、进水阀自动、补水阀自动、给水泵出口自动**默认全是关闭的**
         * （AutoControl 里只有 SDV/MSV 那几组默认投入），于是默认工况下：
         *   除氧器无水 → 吸入母管空 → 给水泵无源 → 给水母管无压 → 给水阀无压差
         *   → 混合器无水 → 换热器二次侧无给水 → 不产汽 → 汽轮机进汽 0 → 出力 0。
         * 也就是说"新建电厂"的默认状态是一条死掉的二回路，必须由操作员投自动或手开阀。
         * 这里按真实运行方式把相关自动投入。 */
        AutoControl ac = NPPSim.autoControl;
        ac.dearatorWaterAndMakeupControl.forEach(c -> c.setEnabled(true));
        ac.dearatorWaterControl.forEach(c -> c.setEnabled(true));
        ac.dearatorMakeupControl.forEach(c -> c.setEnabled(true));
        ac.dearatorPressureControl.forEach(c -> c.setEnabled(true));
        ac.mainFeederControl.forEach(c -> c.setEnabled(true));
        ac.auxFeederControl.forEach(c -> c.setEnabled(true));
        ac.condenserWaterLevelControl.forEach(c -> c.setEnabled(true));
        ac.tgValveControl.forEach(c -> c.setEnabled(true));

        /* 汽轮机并网：TG 未并网时 load 恒为 0（只升速），要测出力必须先并网。
         * TG.forceSync() 把 rpm 置 3000 并置 synced —— 自检里用它代替操作员的手动同期。 */
        NPPSim.tg1.forceSync();
        NPPSim.tg2.forceSync();

        NPPSim.mcc.setWaterTemp(270.0);      // 热态起步
        NPPSim.coreHeatOverrideMW = 4800;

        System.out.println("--- 整定 150 s ---");
        for (int i = 0; i < 10; i++) {
            Thread.sleep(15000);
            line(i * 15);
        }

        // ---------- 观察窗 ----------
        System.out.println("--- 观察窗 60 s ---");
        double w0 = MassLedger.waterKg();
        double s0 = MassLedger.steamKg();
        double l0 = NPPSim.mcc.drum1.getWaterLevel() + NPPSim.mcc.drum2.getWaterLevel();
        double hw0 = MassLedger.hotwellKg();
        double t0 = System.currentTimeMillis();
        for (int i = 0; i < 12; i++) {
            Thread.sleep(5000);
            sample();
        }
        double dt = (System.currentTimeMillis() - t0) / 1000.0;

        double w1 = MassLedger.waterKg();
        double s1 = MassLedger.steamKg();
        double l1 = NPPSim.mcc.drum1.getWaterLevel() + NPPSim.mcc.drum2.getWaterLevel();
        double hw1 = MassLedger.hotwellKg();
        double drift = (w1 - w0) / dt;

        System.out.println();
        System.out.printf("【一回路】主泵 %.0f | 母管出流 %.0f | 通道进水 %.0f kg/s%n", avg(sMcp), avg(sHdr), avg(sChan));
        System.out.printf("【汽量链】通道产汽 %.0f | 汽包产汽 %.0f | 一次侧进汽 %.0f | 二次侧产汽 %.0f | 分离器出口 %.0f | 汽轮机进汽 %.0f kg/s%n",
                avg(sChanSteam), avg(sDrumSteam), avg(sPrimIn), avg(sSec), avg(sSepOut), avg(sTgIn));
        System.out.printf("【能量】一次侧放热 %.0f MW | 二次侧吸热 %.0f MW | 汽轮机出力 %.0f MWe%n",
                avg(sPrimMW), avg(sSecHeat), avg(sMWe));
        System.out.printf("【台账】水 %.1f → %.1f t（%+.1f kg/s）| 汽 %.1f → %.1f t | 汽包水位 %.1f → %.1f %% | 热井 %.1f → %.1f t%n",
                w0 / 1000, w1 / 1000, drift, s0 / 1000, s1 / 1000, l0, l1, hw0 / 1000, hw1 / 1000);

        // ================= 断言 =================
        check("① 母管出流 ≈ 主泵流量（±5%）", rel(avg(sHdr), avg(sMcp)) < 0.05,
                String.format("母管 %.0f vs 泵 %.0f（%.1f%%）", avg(sHdr), avg(sMcp), rel(avg(sHdr), avg(sMcp)) * 100));
        check("① 通道进水 ≈ 母管出流（±5%）", rel(avg(sChan), avg(sHdr)) < 0.05,
                String.format("通道 %.0f vs 母管 %.0f（%.1f%%）", avg(sChan), avg(sHdr), rel(avg(sChan), avg(sHdr)) * 100));
        check("① 一回路有实际流量（>2000 kg/s）", avg(sMcp) > 2000.0, String.format("%.0f kg/s", avg(sMcp)));

        check("② 换热器一次侧进汽 ≈ 通道产汽（±30%）", rel(avg(sPrimIn), avg(sChanSteam)) < 0.30,
                String.format("一次侧 %.0f vs 通道 %.0f kg/s", avg(sPrimIn), avg(sChanSteam)));
        check("② 二次侧产汽 ≈ 一次侧进汽（±30%）", rel(avg(sSec), avg(sPrimIn)) < 0.30,
                String.format("二次侧 %.0f vs 一次侧 %.0f kg/s", avg(sSec), avg(sPrimIn)));
        check("② 汽轮机进汽 ≈ 分离器出口（±25%）", rel(avg(sTgIn), avg(sSepOut)) < 0.25,
                String.format("汽机 %.0f vs 分离器 %.0f kg/s", avg(sTgIn), avg(sSepOut)));
        check("② 汽轮机确实在进汽（>200 kg/s）", avg(sTgIn) > 200.0, String.format("%.0f kg/s", avg(sTgIn)));

        double hfg = NPPSim.tables.getSpecificVaporEnthalpyByTemperature(
                NPPSim.heatExchangers.get(0).getSteamTemperature());
        double expectedMW = avg(sSec) * hfg / 1000.0;
        check("③ 一次侧放热 ≈ 二次侧产汽 × h_fg（±30%）", rel(avg(sPrimMW), expectedMW) < 0.30,
                String.format("%.0f MW vs %.0f MW（h_fg=%.0f kJ/kg）", avg(sPrimMW), expectedMW, hfg));
        check("③ 二次侧吸热 ≈ 一次侧放热（±30%）", rel(avg(sSecHeat), avg(sPrimMW)) < 0.30,
                String.format("%.0f MW vs %.0f MW", avg(sSecHeat), avg(sPrimMW)));
        check("③ 汽轮机出力为正（>100 MWe）", avg(sMWe) > 100.0, String.format("%.0f MWe", avg(sMWe)));
        System.out.printf("   [备注] 单台额定汽量 900 kg/s 与发电机 825 MW 限值不自洽："
                + "按等熵膨胀 w≈2500 kJ/kg，900 kg/s 会算出约 2200 MW，超过 825 MW 跳闸门槛。"
                + "并网带大负荷时需注意这一处。%n");

        check("④ 全厂水量台账漂移 < 0.5%%/min", Math.abs(drift) < (w0 * 0.005 / 60.0),
                String.format("%+.1f kg/s（%+.2f t/60s，总量 %.0f t）", drift, (w1 - w0) / 1000.0, w0 / 1000));

        check("⑤ 汽包水位在 ±25%% 内", Math.abs(l1) < 25.0, String.format("%.1f %%", l1));
        check("⑤ 汽包水位观察窗内漂移 < 8%%", Math.abs(l1 - l0) < 8.0, String.format("%.1f → %.1f %%", l0, l1));
        check("⑤ 凝汽器热井不持续上涨（<200 kg/s）", (hw1 - hw0) / dt < 200.0,
                String.format("%+.1f kg/s", (hw1 - hw0) / dt));

        check("⑥ 关键读数无 NaN", !Double.isNaN(avg(sMcp)) && !Double.isNaN(w1) && !Double.isNaN(l1)
                        && !Double.isNaN(avg(sSec)) && !Double.isNaN(hw1), "NaN 检查");
        check("⑥ 无负质量", w1 > 0 && s1 > 0 && hw1 >= 0,
                String.format("水 %.1f t / 汽 %.1f t / 热井 %.1f t", w1 / 1000, s1 / 1000, hw1 / 1000));

        // ================= 场景：凝汽器冷却水中断 =================
        /* 2026-11-02 自检抓到的那个坑：冷却水泵停机 → 冷却水出口温度算出上万度 →
         * 蒸汽表查表越界 → endSimulation() + 模态窗（弹在更新线程里）→ 整机冻住。
         * 现在应当：仿真继续跑、真空破坏、汽机出力下降、查表只被钳制不致命。 */
        System.out.println("--- 场景：TG-1 冷却水中断 ---");
        long simBefore = NPPSim.autoControl.getSimulationTime();
        int clampsBefore = SteamTables.clampedCount;
        double mweBefore = NPPSim.tg1.getGeneratorLoad() + NPPSim.tg2.getGeneratorLoad();
        stopCondenserPumps(NPPSim.tg1);
        Thread.sleep(25000);
        long simAfter = NPPSim.autoControl.getSimulationTime();
        double mweAfter = NPPSim.tg1.getGeneratorLoad() + NPPSim.tg2.getGeneratorLoad();
        double pCond = NPPSim.tg1.condenser.getPressure();
        System.out.printf("  中断 25 s：仿真时间 %d → %d s | TG-1 凝汽器 %.3f MPa | 汽机出力 %.0f → %.0f MWe | 查表钳制 %d 次%n",
                simBefore, simAfter, pCond, mweBefore, mweAfter, SteamTables.clampedCount - clampsBefore);
        check("⑦ 冷却水中断后仿真仍在推进（未被 endSimulation 停掉）", simAfter > simBefore + 100,
                String.format("仿真时间 +%d s", simAfter - simBefore));
        check("⑦ 冷却水中断后凝汽器压力上升（真空破坏）", pCond > 0.03,
                String.format("%.4f MPa", pCond));
        check("⑦ 汽机出力下降（带负荷失去冷却）", mweAfter < mweBefore,
                String.format("%.0f → %.0f MWe", mweBefore, mweAfter));

        NPPSim.coreHeatOverrideMW = -1;
        System.out.println(fails == 0 ? "LOOP_OK" : ("LOOP_FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    /** 每 5 s 采一次样 */
    static void sample() {
        sMcp += mcpFlow();
        sHdr += headerFlow();
        sChan += channelInflow();
        sChanSteam += channelSteamKgs();
        sDrumSteam += NPPSim.mcc.drum1.getSteamProduction() + NPPSim.mcc.drum2.getSteamProduction();
        for (HeatExchanger hx : NPPSim.heatExchangers) {
            sPrimIn += hx.primarySteamInKgs;
            sPrimMW += hx.primaryHeatMW;
            sSec += hx.secondarySteamKgs;
            sSecHeat += hx.secondaryHeatMW;
        }
        for (Separator sep : NPPSim.separators) {
            sSepOut += sep.outSteamKgs;
        }
        sTgIn += tgInflow();
        sMWe += NPPSim.tg1.getGeneratorLoad() + NPPSim.tg2.getGeneratorLoad();
        sWater += MassLedger.waterKg();
        sSteam += MassLedger.steamKg();
        sHotwell += MassLedger.hotwellKg();
        sLevel += NPPSim.mcc.drum1.getWaterLevel() + NPPSim.mcc.drum2.getWaterLevel();
        n++;
    }

    static void line(int t) {
        System.out.printf("  t=%3ds 泵 %.0f | 通道进水 %.0f | 通道产汽 %.0f | 二回路产汽 %.0f | 汽机进汽 %.0f | 汽包 %.2f MPa/%.0f°C/水位 %.0f%% | 水 %.0f t%n",
                t, mcpFlow(), channelInflow(), channelSteamKgs(), secSteam(), tgInflow(),
                NPPSim.mcc.drum1.getPressure(), NPPSim.mcc.drum1.getWaterTemperature(),
                NPPSim.mcc.drum1.getWaterLevel(), MassLedger.waterKg() / 1000);
    }

    static void start(ArrayList<Pump> pumps) {
        for (Pump p : pumps) {
            p.setActive(true);
            p.setSetPoint(1.0f);
        }
    }

    /** 启动一台汽轮机的全部凝汽器冷却水泵 */
    static void startCondenserPumps(TG tg) {
        if (tg == null || tg.condenser == null) {
            return;
        }
        for (Condenser c : tg.condenser.units) {
            c.condenserPump.setActive(true);
            c.condenserPump.setSetPoint(1.0f);
        }
    }

    /** 停掉一台汽轮机的全部凝汽器冷却水泵（做"冷却水中断"场景） */
    static void stopCondenserPumps(TG tg) {
        if (tg == null || tg.condenser == null) {
            return;
        }
        for (Condenser c : tg.condenser.units) {
            c.condenserPump.setActive(false);
        }
    }

    static double mcpFlow() {
        double s = 0;
        for (Pump p : NPPSim.mcc.mcp) {
            s += p.getFlowRate();
        }
        return s;
    }

    /** 两根压力母管的出水流量 kg/s（update 里按体积偏差算，每 tick 结算一次） */
    static double headerFlow() {
        return NPPSim.mcc.pHeader1.waterOutflowRate * 20 + NPPSim.mcc.pHeader2.waterOutflowRate * 20;
    }

    /** 1661 根通道的进水之和 kg/s */
    static double channelInflow() {
        double s = 0;
        for (ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (int i = 0; i < row.size(); i++) {
                Channel c = row.get(i);
                if (c instanceof FuelChannel) {
                    s += ((FuelChannel) c).getWaterInflowRate();
                }
            }
        }
        return s;
    }

    /** 1661 根通道的产汽之和 kg/s（堆芯 → 汽包的蒸汽） */
    static double channelSteamKgs() {
        double s = 0;
        for (ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (int i = 0; i < row.size(); i++) {
                Channel c = row.get(i);
                if (c instanceof FuelChannel) {
                    s += ((FuelChannel) c).getSteamProduction();
                }
            }
        }
        return s;
    }

    static double secSteam() {
        double s = 0;
        for (HeatExchanger hx : NPPSim.heatExchangers) {
            s += hx.secondarySteamKgs;
        }
        return s;
    }

    /** 汽轮机进汽 kg/s。注意 TG.getSteamInflow() 返回的是 kg/tick，界面自己乘 20 */
    static double tgInflow() {
        return (NPPSim.tg1.getSteamInflow() + NPPSim.tg2.getSteamInflow()) * 20.0;
    }

    static double avg(double sum) {
        return n == 0 ? 0.0 : sum / n;
    }

    static double rel(double a, double b) {
        return Math.abs(a - b) / Math.max(1.0, Math.abs(b));
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            fails++;
        }
        System.out.printf("[%s] %s：%s%n", ok ? "OK" : "FAIL", name, detail);
    }
}
