package com.darwish.nppsim;

/** IC 后的本底产汽量：关死主汽门，看 4800 MW 在汽包里能产多少汽。 */
public class SingleLoopICProbe {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(400);
        StandardIC.apply();
        NPPSim.autoControl.tgValveControl.forEach(c -> c.setEnabled(false));
        NPPSim.autoControl.mainFeederControl.forEach(c -> c.setEnabled(false));
        NPPSim.autoControl.auxFeederControl.forEach(c -> c.setEnabled(false));
        for (WaterValve v : NPPSim.mainFeederValves) { v.setState(1); v.setAutoState(1); v.setPosition(0f); }
        for (SteamValve v : NPPSim.TG1InletValves) { v.setState(1); v.setAutoState(1); v.setPosition(0f); }
        for (SteamValve v : NPPSim.TG2InletValves) { v.setState(1); v.setAutoState(1); v.setPosition(0f); }
        java.lang.reflect.Field of = null;
        Class<?> c = NPPSim.mcc.drum1.getClass();
        while (c != null && of == null) {
            try { of = c.getDeclaredField("steamOutflow"); } catch (Exception e) { c = c.getSuperclass(); }
        }
        if (of != null) of.setAccessible(true);
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            System.out.printf("t=%2ds 堆芯 %.0f MW | 汽包 %.2f MPa/%.0f°C/%+.0f%% 产汽 %.0f kg/s | 汽机进汽 %.0f | 给水阀 %.2f | 周期 %s%n",
                    i + 1, NPPSim.core.getThermalPower(),
                    NPPSim.mcc.drum1.getPressure(), NPPSim.mcc.drum1.getWaterTemperature(),
                    NPPSim.mcc.drum1.getWaterLevel(), NPPSim.mcc.drum1.getSteamProduction(),
                    NPPSim.tg1.getSteamInflow(),
                    NPPSim.mainFeederValves.get(0).getPosition(),
                    String.format("%.1f", NPPSim.core.getPeriod()));
            if (of != null) {
                System.out.printf("        汽包抽汽 %.0f kg/s | 母管需求 %.0f kg/s | 水位 %.1f%%%n",
                        ((Double) of.get(NPPSim.mcc.drum1)) * 20.0,
                        NPPSim.steamPiping.availableSteamKg() * 20.0,
                        NPPSim.mcc.drum1.getWaterLevel());
            }
        }
        System.exit(0);
    }
}
