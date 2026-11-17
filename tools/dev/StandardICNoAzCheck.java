package com.darwish.nppsim;

/** 标准工况 + 关闭自动 AZ：只看电厂本身会不会自己漂（分离 AZ 的影响，2026-11-03）。 */
public class StandardICNoAzCheck {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(400);
        boolean ok = StandardIC.apply();
        NPPSim.autoControl.azAutoEnabled = false;     // 关掉自动 AZ
        System.out.println("StandardIC=" + ok + " 自动AZ=关");
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) {
            Thread.sleep(20000);
            long t = (System.currentTimeMillis() - t0) / 1000;
            System.out.printf("t=%3ds 堆芯 %.0f MW | 汽包 %.2f MPa/%.0f°C/%.0f%% | 母管 %.2f | 二次汽 %.0f | MWe %.0f | AZ1=%s AZ1K=%s%n",
                    t, NPPSim.core.getThermalPower(), NPPSim.mcc.drum1.getPressure(),
                    NPPSim.mcc.drum1.getWaterTemperature(), NPPSim.mcc.drum1.getWaterLevel(),
                    NPPSim.steamPiping.getPressure(), NPPSim.heatExchangers.get(0).steamOutKgs,
                    NPPSim.tg1.getGeneratorLoad() + NPPSim.tg2.getGeneratorLoad(),
                    NPPSim.autoControl.az1Signal.isTripped(), NPPSim.autoControl.az1Control.isTripped());
        }
        System.exit(0);
    }
}
