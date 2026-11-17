package com.darwish.nppsim;

/** 装载后 12 s 的细粒度跟踪（2026-11-04）：看 AZ-1K 到底被哪一条定值打掉。 */
public class AzTraceProbe {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(400);
        StandardIC.apply();
        int n = a.length > 0 ? Integer.parseInt(a[0]) : 48;
        for (int i = 0; i < n; i++) {
            Thread.sleep(250);
            System.out.println(String.format("t=%4.1fs 堆 %.0f MW 周期 %.1f s 反应性 %+.4f | 汽包 %.2f MPa/%+.0f%% | 母管 %.2f | 主汽门 %.0f%% | AZ1K=%s 自动AZ=%s",
                    (i + 1) * 0.25, NPPSim.core.getThermalPower(), NPPSim.core.getPeriod(),
                    NPPSim.core.getReactivity(),
                    NPPSim.mcc.drum1.getPressure(), NPPSim.mcc.drum1.getWaterLevel(),
                    NPPSim.steamPiping.getPressure(),
                    NPPSim.TG1InletValves.get(0).getPosition() * 100,
                    NPPSim.autoControl.az1Control.isTripped(),
                    NPPSim.autoControl.azAutoEnabled));
        }
        System.exit(0);
    }
}
