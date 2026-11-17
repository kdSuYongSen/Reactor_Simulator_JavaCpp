package com.darwish.nppsim;

/** 除氧器进水来源分解（2026-11-04）。 */
public class DearatorSourceProbe {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(500);
        StandardIC.apply();
        for (int i = 0; i < 8; i++) {
            Thread.sleep(5000);
            Dearator d = NPPSim.dearators.get(0);
            HeatExchanger hx = NPPSim.heatExchangers.get(0);
            Separator s = NPPSim.separators.get(0);
            System.out.printf("t=%2ds DA1 进水 %.0f kg/s | 阀 %.0f%% 流量 %.0f | 补水 %.0f | 分离器疏水 %.0f | 换热器二次排水 %.0f kg/s%n",
                    (i + 1) * 5, d.getWaterInflowRate(),
                    NPPSim.dearatorValves.get(0).getPosition() * 100,
                    NPPSim.dearatorValves.get(0).timestepFlow * 20,
                    NPPSim.pcs.dearatorMakeupValves.get(0).timestepFlow * 20,
                    s.outWaterKgs,
                    hx.secondaryDrainKgs);
        }
        System.exit(0);
    }
}
