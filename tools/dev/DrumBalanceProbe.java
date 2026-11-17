package com.darwish.nppsim;

/** 汽包质量/蒸汽平衡（2026-11-04）：看进汽、抽汽、水温、汽空间存量。 */
public class DrumBalanceProbe {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(400);
        StandardIC.apply();
        for (int i = 0; i < 12; i++) {
            Thread.sleep(1000);
            MCC.SeparatorDrum d = NPPSim.mcc.drum1;
            System.out.printf("t=%2ds 汽包 %.2f MPa 水温 %.1f 汽 %.2f t | 进汽 %.0f 抽汽 %.0f kg/s | 水 %.1f t 水位 %+.0f%% | 产汽 %.0f kg/s | 给水 %.0f kg/s%n",
                    i + 1, d.getPressure(), d.getWaterTemperature(), d.getSteamMass() / 1000.0,
                    d.getSteamInflowRate(), d.getSteamOutflowRate(),
                    d.getWaterMass() / 1000.0, d.getWaterLevel(),
                    d.getSteamProduction(), NPPSim.feedwaterMixer1.getWaterInflowRate());
        }
        System.exit(0);
    }
}
