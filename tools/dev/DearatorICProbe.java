package com.darwish.nppsim;

/** 标准工况下除氧器是否"凭空进水"（2026-11-04）。 */
public class DearatorICProbe {
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
            Dearator d = NPPSim.dearators.get(0);
            System.out.printf("t=%3ds DA1 水位 %+.1f%% 水 %.1f t 进水 %.1f kg/s 蒸汽入口阀 %.0f%% 蒸汽流出 %.1f kg/s | 分离器A 疏水 %.1f kg/s 压力 %.2f%n",
                    (i + 1) * 5, d.getWaterLevel(), d.getWaterMass() / 1000.0,
                    d.getWaterInflowRate(), d.steamInlet.getPosition() * 100,
                    d.getSteamOutflowRate(),
                    NPPSim.separators.get(0).outWaterKgs,
                    NPPSim.separators.get(0).pressureMPa);
        }
        System.exit(0);
    }
}
