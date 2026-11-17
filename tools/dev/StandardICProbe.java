package com.darwish.nppsim;

import java.util.ArrayList;

/**
 * 标准初始工况的**给水链定点诊断**（2026-11-02）。
 *
 * <p>StandardICCheck 只看两端（堆芯功率、二次侧产汽），中间哪一段断了看不出来。
 * 这里施加 StandardIC 之后，每 10 s 把整条二回路从热井到换热器逐段打印一遍：
 * 热井 → 凝结水泵 → 凝结水联箱 → 精处理 → 低压加热器 → 除氧器进水阀 → 除氧器
 * → 给水泵吸入母管 → 给水泵 → 给水母管 → 主/辅给水阀 → 混合器 → 换热器管侧 → 二次侧产汽。
 *
 * <p>用法：{@code java -cp target\icclasses;target\classes;... com.darwish.nppsim.StandardICProbe}</p>
 */
public class StandardICProbe {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(400);

        System.out.println("---- 施加 StandardIC ----");
        boolean coreOk = StandardIC.apply();
        System.out.println("coreOk=" + coreOk);

        double t0 = System.currentTimeMillis();
        for (int i = 0; i < 24; i++) {
            Thread.sleep(10000);
            line((System.currentTimeMillis() - t0) / 1000.0);
        }
        System.exit(0);
    }

    static void line(double t) {
        Dearator da = NPPSim.dearators.get(0);
        WaterValve daIn = NPPSim.dearatorValves.get(0);
        WaterValve feeder = NPPSim.mainFeederValves.get(0);
        WaterValve aux = NPPSim.auxFeederValves.get(0);
        HeatExchanger hx = NPPSim.heatExchangers.get(0);

        double fwPump = 0;
        for (Pump p : NPPSim.mainFeedwaterPumps) {
            fwPump += p.getFlowRate();
        }
        double condPump = 0;
        condPump += pumpFlow(NPPSim.condensate1A);
        condPump += pumpFlow(NPPSim.condensate2A);
        condPump += pumpFlow(NPPSim.condensate1B);
        condPump += pumpFlow(NPPSim.condensate2B);
        double adms = pumpFlow(NPPSim.pcs.admsPumps);
        double mcp = 0;
        for (Pump p : NPPSim.mcc.mcp) {
            mcp += p.getFlowRate();
        }

        System.out.printf(
            "t=%3.0fs │ 堆芯 %4.0f MW 汽包 %.2f MPa/%.0f%% 主泵 %5.0f │ 热井 %4.0f t 凝结水泵 %4.0f │ 联箱 %.2f MPa │ 加热器 %.2f MPa │ "
            + "除氧器 L%4.0f%% 进阀 %.2f(%.0f) 汽阀 %.2f │ ADMS %3.0f 头 %.2f │ 吸入 %4.0f kg │ 给水泵 %4.0f │ 给水母管 %.2f │ "
            + "主阀 %.2f(%.0f) 辅阀 %.2f(%.0f) │ HX 水 %5.0f kg 给水 %4.0f 产汽 %4.0f%n",
            t, NPPSim.core.getThermalPower(),
            NPPSim.mcc.drum1.getPressure(), NPPSim.mcc.drum1.getWaterLevel(), mcp,
            NPPSim.tg1.condenser.getHotwellMassKg() / 1000.0, condPump,
            NPPSim.condensateHeader.getPressure(),
            NPPSim.lpHeater.getPressure(),
            da.getWaterLevel(), daIn.getPosition(), daIn.timestepFlow * 20,
            da.steamInlet.getPosition(),
            adms, NPPSim.pcs.admsHeader.getPressure(),
            NPPSim.fwSuctionHeader.getWaterLevel(),
            fwPump,
            NPPSim.mainFWPressureHeader.getPressure(),
            feeder.getPosition(), feeder.timestepFlow * 20,
            aux.getPosition(), aux.timestepFlow * 20,
            hx.getWaterMass(), hx.secondaryFeedKgs, hx.secondarySteamKgs);
    }

    static double pumpFlow(ArrayList<Pump> pumps) {
        double f = 0;
        if (pumps == null) {
            return 0;
        }
        for (Pump p : pumps) {
            f += p.getFlowRate();
        }
        return f;
    }
}
