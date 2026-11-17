package com.darwish.nppsim;

/** 汽机为停汽/跳闸排查（2026-11-04）。 */
public class TgTripProbe {
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
            TG tg = NPPSim.tg1;
            System.out.printf("t=%3ds 母管 %.2f MPa | 主汽门 %.2f/%.2f | 进汽 %.0f kg/s | rpm %.0f 并网 %s 跳闸 %s"
                    + " | 出力 %.0f MW | 凝汽器 %.4f MPa | AZ1K %s%n",
                    (i + 1) * 4, NPPSim.steamPiping.getPressure(),
                    NPPSim.TG1InletValves.get(0).getPosition(), NPPSim.TG1InletValves.get(1).getPosition(),
                    tg.getSteamInflow(), tg.getRpm(), tg.isSynced(), tg.isTripped(),
                    tg.getGeneratorLoad(), tg.condensers[0].getPressure(),
                    NPPSim.autoControl.az1Control.isTripped());
        }
        System.exit(0);
    }
}
