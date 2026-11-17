package com.darwish.nppsim;

/** 母管汽源能力排查（2026-11-04）：分离器/换热器能给母管多少汽。 */
public class HeaderSourceProbe {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(500);
        StandardIC.apply();
        for (int i = 0; i < 10; i++) {
            Thread.sleep(4000);
            Separator s = NPPSim.separators.get(0);
            HeatExchanger hx = NPPSim.heatExchangers.get(0);
            System.out.printf("t=%3ds 母管 %.2f MPa 可供 %.0f kg/s | 分离器 %.2f MPa 产汽 %.0f 疏水 %.0f | "
                    + "换热器二次汽 %.0f 管侧水 %.0f t | 二回路给水阀 %.2f | 主汽门 %.2f | 汽机进汽 %.0f%n",
                    (i + 1) * 4, NPPSim.steamPiping.getPressure(),
                    NPPSim.steamPiping.availableSteamKg() * 20.0,
                    s.pressureMPa, s.outSteamKgs, s.outWaterKgs,
                    hx.steamOutKgs, hx.secondaryWaterKg,
                    NPPSim.mainFeederValves.get(0).getPosition(),
                    NPPSim.TG1InletValves.get(0).getPosition(),
                    NPPSim.tg1.getSteamInflow());
        }
        System.exit(0);
    }
}
