package com.darwish.nppsim;

/**
 * 堆芯热量 → 一回路汽包 → 换热器 → 汽水分离器 的耦合自检。
 *
 * <p>验证三点：① 注入热量后汽包会升温/建压/产汽；② 二回路产汽量随堆芯功率变化
 * （不再"凭空产汽"）；③ 汽包内水+汽的质量守恒（加热本身不增不减物质）。</p>
 */
public class CoreHeatCouplingCheck {

    static int fails = 0;

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(800);

        /* 2026-11-01：热量现在只能走"堆芯 → 燃料通道 → 汽包"，
         * 4800 MW 下通道蒸发约 3100 kg/s，一回路循环流量必须大于它，
         * 否则通道会干锅。这里把 8 台主泵全部投运（额定 6 台 ≈ 12000 kg/s）。 */
        for (Pump p : NPPSim.mcc.mcp) {
            p.setActive(true);
            p.setSetPoint(1.0f);
        }
        /* 热态起步（2026-11-01）：真实机组不可能从 20 °C 直接满功率，
         * 一回路装量（汽包 223 t + 两根压力母管 243 t + 通道 28 t）约 500 t，
         * 冷态升温到饱和要好几分钟，自检窗口里看不到耦合。这里直接把一回路
         * 预置到 270 °C 额定入口温度，测的是"热态下功率→产汽"的响应。 */
        NPPSim.mcc.setWaterTemp(270.0);
        Thread.sleep(20000);   // 等 8 台泵升到全速（加速时间 18 s），否则满功率会先把通道烧干

        MCC.SeparatorDrum d1 = NPPSim.mcc.drum1;
        MCC.SeparatorDrum d2 = NPPSim.mcc.drum2;

        double m0 = mass(d1) + mass(d2);
        System.out.printf("初始：汽包 %.4f MPa / %.1f°C / 水 %.0f t / 汽 %.0f kg%n",
                d1.getPressure(), d1.getWaterTemperature(), d1.getWaterMass() / 1000.0, d1.getSteamMass());

        // ---- 0 MW：先空跑 ----
        NPPSim.coreHeatOverrideMW = 0;
        NPPSim.feedwaterOverrideKgs = 3000;   // 模拟给水系统正常投运（两台合计 3000 kg/s）
        Thread.sleep(5000);
        double steam0 = steamOut();
        System.out.printf("0 MW 稳定 3 s：汽包 %.4f MPa / %.1f°C  二回路出口汽 %.1f kg/s%n",
                d1.getPressure(), d1.getWaterTemperature(), steam0);

        // ---- 4800 MW：满功率注入 ----
        NPPSim.coreHeatOverrideMW = 4800;
        double t0 = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            Thread.sleep(15000);          // 每 15 s 打一行，共 90 s
            System.out.printf("  t=%3.0fs 4800MW：汽包 %.3f MPa / %.1f°C / 水 %.0f t / 汽 %.0f kg | 蒸汽产量 %.0f kg/s | 抽汽 %.0f kg/s | 分离器出口 %.0f kg/s | 热功率 %.0f MW%n",
                    (System.currentTimeMillis() - t0) / 1000.0,
                    d1.getPressure(), d1.getWaterTemperature(), d1.getWaterMass() / 1000.0,
                    d1.getSteamMass(), d1.getSteamProduction(),
                    NPPSim.heatExchangers.get(0).primarySteamInKgs,
                    NPPSim.separators.get(0).outSteamKgs,
                    NPPSim.heatExchangers.get(0).primaryHeatMW);
            System.out.printf("      [诊断] MCP %.0f kg/s | 通道合计功率 %.0f MW | 通道平均水温 %.1f°C | 通道产汽 %.0f kg/s%n",
                    mcpFlow(), channelPowerMW(), channelTemp(), channelSteamKgs());
        }
        double steamHot = steamOut();
        double pHot = d1.getPressure();
        double tHot = d1.getWaterTemperature();

        // ---- 回 0 MW：应降温/降产汽 ----
        NPPSim.coreHeatOverrideMW = 0;
        /* 40 s 而不是 20 s：汽包现在按分离器建模，汽空间里存着 5 t 左右蒸汽，
         * 断热后这段存量还会继续供汽一段时间，20 s 窗口内掉不到 60% 以下。 */
        Thread.sleep(40000);
        double steamCold = steamOut();
        System.out.printf("回 0 MW 20 s：汽包 %.3f MPa / %.1f°C  二回路出口汽 %.1f kg/s%n",
                d1.getPressure(), d1.getWaterTemperature(), steamCold);

        double m1 = mass(d1) + mass(d2);
        double drift = Math.abs(m1 - m0) / Math.max(1.0, m0) * 100.0;

        check("4800 MW 时汽包升压", pHot > 1.0, String.format("%.3f MPa", pHot));
        check("4800 MW 时汽包升温", tHot > 100.0, String.format("%.1f °C", tHot));
        check("4800 MW 时二回路产汽", steamHot > 100.0, String.format("%.0f kg/s", steamHot));
        check("回 0 MW 后产汽明显下降", steamCold < steamHot * 0.6,
                String.format("%.0f → %.0f kg/s", steamHot, steamCold));
        check("汽包内水+汽质量守恒（漂移 < 2%）", drift < 2.0,
                String.format("%.0f → %.0f kg（%.3f%%）", m0, m1, drift));

        NPPSim.coreHeatOverrideMW = -1;
        NPPSim.feedwaterOverrideKgs = 0;
        System.out.println(fails == 0 ? "CORE_HEAT_OK" : ("CORE_HEAT_FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    static double mass(MCC.SeparatorDrum d) {
        return d.getWaterMass() + d.getSteamMass();
    }

    /* ---- 2026-11-01 诊断：确认"堆芯功率只走通道"这条路的能量到底有没有到汽包 ---- */
    static double mcpFlow() {
        double s = 0;
        for (Pump p : NPPSim.mcc.mcp) {
            s += p.getFlowRate();
        }
        return s;
    }

    static double channelPowerMW() {
        double s = 0;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FuelChannel) {
                    s += ((FuelChannel) c).getThermalPower();
                }
            }
        }
        return s;
    }

    static double channelTemp() {
        double s = 0;
        int n = 0;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FuelChannel) {
                    s += ((FuelChannel) c).getWaterTemperature();
                    n++;
                }
            }
        }
        return n == 0 ? 0 : s / n;
    }

    static double channelSteamKgs() {
        double s = 0;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FuelChannel) {
                    s += ((FuelChannel) c).getSteamProduction();
                }
            }
        }
        return s;
    }

    /** 二回路产汽量（换热器管侧实际蒸发量，不受分离器出口阀位影响） */
    static double steamOut() {
        double s = 0;
        for (HeatExchanger hx : NPPSim.heatExchangers) {
            s += hx.secondarySteamKgs;
        }
        return s;
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            fails++;
        }
        System.out.printf("[%s] %s：%s%n", ok ? "OK" : "FAIL", name, detail);
    }
}
