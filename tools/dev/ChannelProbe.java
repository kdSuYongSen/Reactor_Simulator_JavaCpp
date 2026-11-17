package com.darwish.nppsim;

/** 单根燃料通道的状态（2026-11-04）：为什么通道不往汽包送汽。 */
public class ChannelProbe {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(400);
        StandardIC.apply();
        for (int i = 0; i < 8; i++) {
            Thread.sleep(1000);
            FuelChannel c = NPPSim.mcc.fuelChannels1.get(0);
            System.out.printf("t=%2ds 通道 水 %.2f kg 汽 %.4f kg 水温 %.1f 汽温 %.1f | 产汽 %.3f kg/s 进 %.3f 出 %.3f | 汽包 %.2f MPa 沸点 %.1f | 功率 %.2f MW%n",
                    i + 1, c.getWaterMass(), c.getSteamMass(),
                    c.getWaterTemperature(), c.getSteamTemperature(),
                    c.getSteamProduction(), c.getWaterInflowRate(), c.getWaterOutflowRate(),
                    NPPSim.mcc.drum1.getPressure(),
                    Loader.tables.getSteamTemperatureByPressure(NPPSim.mcc.drum1.getPressure()),
                    c.getThermalPower());
        }
        System.exit(0);
    }
}
