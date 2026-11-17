package com.darwish.nppsim;

/** 凝结水回收链全链探针（2026-11-04）：汽机进汽 → 凝汽器 → 凝结水泵 → 母管 → LP加热器 → 除氧器。 */
public class CondensateChainProbe {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(500);
        StandardIC.apply();
        for (int i = 0; i < 12; i++) {
            Thread.sleep(5000);
            TG tg = NPPSim.tg1;
            Condenser c = tg.condensers[0];
            double p1 = 0, p2 = 0, p3 = 0;
            for (Pump p : NPPSim.condensate1A) p1 += p.getFlowRate();
            for (Pump p : NPPSim.condensate1B) p2 += p.getFlowRate();
            Dearator d = NPPSim.dearators.get(0);
            System.out.printf("t=%3ds | 汽机进汽 %.0f kg/s | 凝汽器 %.3f MPa 热井 %.0f%% 凝水 %.0f kg/s"
                    + " | 凝结水泵A %.0f B %.0f kg/s | 母管 %.2f MPa | LP入 %.0f kg/s 阀 %.0f%% | DA进水 %.0f kg/s 水位 %+.0f%%%n",
                    (i + 1) * 5, tg.getSteamInflow(),
                    c.getPressure(), c.getWaterLevel(), c.getCondensationRate() * 20.0,
                    p1, p2, NPPSim.condensateHeader.getPressure(),
                    NPPSim.lpHeater.getThroughputKgs(), NPPSim.dearatorValves.get(0).getPosition() * 100,
                    d.getWaterInflowRate(), d.getWaterLevel());
        }
        System.exit(0);
    }
}
