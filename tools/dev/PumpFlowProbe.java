package com.darwish.nppsim;

/** 泵流量口径排查（2026-11-04）。 */
public class PumpFlowProbe {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(1500);
        Pump p = NPPSim.condensate1A.get(0);
        System.out.printf("凝结水泵A ratedRPM=%.0f rpm=%.0f setPoint=%.2f flow=%.1f kg/s flowRate=%.1f%n",
                p.ratedRPM, p.getRPM(), p.getSetPoint(), p.getFlow(), p.getFlowRate());
        System.out.printf("source=%s 水密度=%.6f 蒸汽密度=%.6f 水质量=%.0f 温度=%.1f%n",
                p.source.getClass().getSimpleName(), p.source.getWaterDensity(),
                p.source.getSteamDensity(), p.source.getWaterMass(), p.source.getWaterTemperature());
        System.out.printf("dischargeValve 位置=%.2f%n", p.dischargeValve.getPosition());
        for (Pump q : NPPSim.condensate1B) {
            System.out.printf("凝结水泵B flow=%.1f 源=%s 密度=%.6f%n",
                    q.getFlow(), q.source.getClass().getSimpleName(), q.source.getWaterDensity());
        }
        for (Pump q : NPPSim.mainFeedwaterPumps) {
            System.out.printf("给水泵 flow=%.1f 源=%s 密度=%.6f%n",
                    q.getFlow(), q.source.getClass().getSimpleName(), q.source.getWaterDensity());
        }
        System.exit(0);
    }
}
