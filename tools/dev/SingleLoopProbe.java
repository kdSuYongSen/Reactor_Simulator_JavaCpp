package com.darwish.nppsim;

/** 单回路快速探针（2026-11-04）：标准工况后每 5 s 打一行，60 s。 */
public class SingleLoopProbe {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(400);
        boolean ok = StandardIC.apply();
        System.out.println("StandardIC=" + ok + " 母管设定=6.9 MPa 单回路");
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 12; i++) {
            Thread.sleep(5000);
            long t = (System.currentTimeMillis() - t0) / 1000;
            System.out.printf("t=%3ds 堆芯 %.0f MW | 汽包 %.2f MPa/%.0f°C/%+.0f%% 产汽 %.0f | 母管 %.2f | 主汽门 %.2f/%.2f 进汽 %.0f+%.0f | 给水阀 %.2f | MWe %.0f | 热井 %.0f%% 除氧器 %.0f%%%n",
                    t, NPPSim.core.getThermalPower(),
                    NPPSim.mcc.drum1.getPressure(), NPPSim.mcc.drum1.getWaterTemperature(),
                    NPPSim.mcc.drum1.getWaterLevel(), NPPSim.mcc.drum1.getSteamProduction(),
                    NPPSim.steamPiping.getPressure(),
                    NPPSim.TG1InletValves.get(0).getPosition(), NPPSim.TG1InletValves.get(1).getPosition(),
                    NPPSim.tg1.getSteamInflow(), NPPSim.tg2.getSteamInflow(),
                    NPPSim.mainFeederValves.get(0).getPosition(),
                    NPPSim.tg1.getGeneratorLoad() + NPPSim.tg2.getGeneratorLoad(),
                    NPPSim.tg1.condenser.getWaterLevel(), NPPSim.dearators.get(0).getWaterLevel());
        }
        System.exit(0);
    }
}
