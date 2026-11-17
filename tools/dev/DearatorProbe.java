package com.darwish.nppsim;

/** 除氧器自检（2026-11-04）：冷态开局观察水量/水位/进水/蒸汽入口阀是否自己动。 */
public class DearatorProbe {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(500);
        System.out.println("t=  0s 初始");
        printAll(true);
        for (int i = 1; i <= 10; i++) {
            Thread.sleep(2000);
            System.out.printf("t=%2ds%n", i * 2);
            printAll(false);
        }
        System.exit(0);
    }

    static void printAll(boolean withOutlet) throws Exception {
        for (int i = 0; i < NPPSim.dearators.size(); i++) {
            Dearator d = NPPSim.dearators.get(i);
            double wm = d.getWaterMass();
            System.out.printf("        DA%d 水位 %+.1f%% 水 %.1f t 进水 %.1f kg/s 蒸汽入口阀 %.0f%% 压力 %.3f MPa%s%n",
                    i + 1, d.getWaterLevel(), wm / 1000.0, d.getWaterInflowRate(),
                    d.steamInlet.getPosition() * 100, d.getPressure(),
                    withOutlet ? String.format(" 出口阀 %.0f%%", d.steamOutlet.getPosition() * 100) : "");
        }
    }
}
