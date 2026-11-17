package com.darwish.nppsim;

/** 全厂稳态探针（2026-11-04）：标准工况后 180 s，每 10 s 一行。 */
public class PlantProbe {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(500);
        StandardIC.apply();
        int n = a.length > 0 ? Integer.parseInt(a[0]) : 18;
        for (int i = 0; i < n; i++) {
            Thread.sleep(10000);
            double flow1 = 0, flow2 = 0;
            for (Pump p : NPPSim.condensate1A) flow1 += p.getFlowRate();
            for (Pump p : NPPSim.mainFeedwaterPumps) flow2 += p.getFlowRate();
            System.out.printf("t=%3ds 堆 %.0f MW | 汽包 %.2f MPa/%+.0f%% | 母管 %.2f | 主汽门 %.0f%% | 进汽 %.0f kg/s"
                    + " | 出力 %.0f MW | 热井 %+.0f%% | 凝泵 %.0f | 给水 %.0f | 除氧器 %+.0f%% | 给水阀 %.2f"
                    + " | AZ1K %s%n",
                    (i + 1) * 10, NPPSim.core.getThermalPower(),
                    NPPSim.mcc.drum1.getPressure(), NPPSim.mcc.drum1.getWaterLevel(),
                    NPPSim.steamPiping.getPressure(),
                    NPPSim.TG1InletValves.get(0).getPosition() * 100,
                    NPPSim.tg1.getSteamInflow() * 20, NPPSim.tg1.getGeneratorLoad(),
                    NPPSim.tg1.condensers[0].getWaterLevel(), flow1, flow2,
                    NPPSim.dearators.get(0).getWaterLevel(),
                    NPPSim.autoControl.feedwaterControl.isEmpty() ? -1
                            : NPPSim.autoControl.feedwaterControl.get(0).getValvePosition(),
                    NPPSim.autoControl.az1Control.isTripped());
            System.out.printf("        tg1 tripped=%s rpm=%.0f 凝汽器=%.4f 汽包水位%+.0f%% | 事件 %s%n",
                    NPPSim.tg1.isTripped(), NPPSim.tg1.getRpm(),
                    NPPSim.tg1.condensers[0].getPressure(),
                    NPPSim.mcc.drum1.getWaterLevel(),
                    NPPSim.autoControl.eventLog.isEmpty() ? "-" :
                    NPPSim.autoControl.eventLog.get(NPPSim.autoControl.eventLog.size() - 1));
        }
        System.exit(0);
    }
}
