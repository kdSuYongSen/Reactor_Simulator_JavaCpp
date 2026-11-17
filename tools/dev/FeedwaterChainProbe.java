package com.darwish.nppsim;

import java.util.ArrayList;

/**
 * 二回路给水链定点诊断（2026-11-02）。
 *
 * <p>环路自检发现：一次侧闭合到 0.0%，但换热器二次侧产汽只有 4 kg/s、
 * 汽轮机进汽 0 —— 水从"凝汽器 → 凝结水泵 → 凝结水联箱 → 精处理 → 低压加热器 →
 * 除氧器 → 给水泵 → 给水母管 → 混合器 → 换热器"这条链上某处断了。
 * 本工具沿链逐段打印，定位断点。</p>
 */
public class FeedwaterChainProbe {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(800);

        start(NPPSim.mcc.mcp);
        start(NPPSim.condensate1A);
        start(NPPSim.condensate2A);
        start(NPPSim.condensate1B);
        start(NPPSim.condensate2B);
        start(NPPSim.mainFeedwaterPumps);
        start(NPPSim.auxFeedwaterPumps);
        start(NPPSim.pcs.admsPumps);
        for (SteamValve v : NPPSim.TG1InletValves) {
            v.setAutoState(1); v.setState(2); v.setPosition(1.0f);
        }
        for (SteamValve v : NPPSim.TG2InletValves) {
            v.setAutoState(1); v.setState(2); v.setPosition(1.0f);
        }
        for (TG tg : new TG[] {NPPSim.tg1, NPPSim.tg2}) {
            for (Condenser c : tg.condenser.units) {
                c.condenserPump.setActive(true);
                c.condenserPump.setSetPoint(1.0f);
            }
        }
        NPPSim.mcc.setWaterTemp(270.0);
        NPPSim.coreHeatOverrideMW = 4800;

        for (int i = 0; i < 8; i++) {
            Thread.sleep(5000);
            report(i * 5);
        }
        System.exit(0);
    }

    static void report(int t) {
        Dearator da = NPPSim.dearators.get(0);
        WaterValve daInlet = NPPSim.dearatorValves.get(0);          // 低压加热器 → 除氧器
        WaterValve makeup = NPPSim.pcs.dearatorMakeupValves.get(0); // 除盐水 → 除氧器
        WaterValve feeder = NPPSim.mainFeederValves.get(0);         // 给水母管 → 混合器
        WaterValve auxFeeder = NPPSim.auxFeederValves.get(0);
        HeatExchanger hx = NPPSim.heatExchangers.get(0);

        System.out.printf("t=%2ds | 联箱 %.3f MPa | 精处理 %.3f | 低压加热器 %.3f | 除氧器1 水位 %.0f%% 进水阀 %.2f(%.0f kg/s) 补水阀 %.2f(%.0f kg/s) 汽阀 %.2f | ADMS泵 %.0f kg/s 头 %.3f MPa | 吸入母管 %.0f kg | 给水泵 %.0f kg/s | 给水母管 %.3f | 给水阀 %.2f(%.0f) | HX 给水 %.0f 产汽 %.0f%n",
                t,
                NPPSim.condensateHeader.getPressure(),
                NPPSim.pcsCondensateFilter.getPressure(),
                NPPSim.lpHeater.getPressure(),
                da.getWaterLevel(), daInlet.getPosition(), daInlet.timestepFlow * 20,
                makeup.getPosition(), makeup.timestepFlow * 20,
                da.steamInlet.getPosition(),
                pumpFlow(NPPSim.pcs.admsPumps), NPPSim.pcs.admsHeader.getPressure(),
                NPPSim.fwSuctionHeader.getWaterLevel(),
                pumpFlow(NPPSim.mainFeedwaterPumps),
                NPPSim.mainFWPressureHeader.getPressure(),
                feeder.getPosition(), feeder.timestepFlow * 20,
                hx.secondaryFeedKgs, hx.secondarySteamKgs);
    }

    static double pumpFlow(ArrayList<Pump> pumps) {
        double s = 0;
        for (Pump p : pumps) {
            s += p.getFlowRate();
        }
        return s;
    }

    static double rpm(ArrayList<Pump> pumps) {
        double s = 0;
        for (Pump p : pumps) {
            s += p.getRPM();
        }
        return s / Math.max(1, pumps.size());
    }

    static void start(ArrayList<Pump> pumps) {
        for (Pump p : pumps) {
            p.setActive(true);
            p.setSetPoint(1.0f);
        }
    }
}
