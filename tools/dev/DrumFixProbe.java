package com.darwish.nppsim;

/**
 * 汽包整定验证（2026-11-05）。
 *
 * <p>按**仿真步数**采样（不是墙钟）：这台机器上有近 200 个残留 JVM 抢 CPU，
 * 墙钟 1 秒可能只推几十个 tick，按秒采样会看到"时间没走"的假象。
 * 这里直接读 NPPSim.updateCount，跑到指定 tick 就取一行。</p>
 */
public class DrumFixProbe {

    private static long ticks() {
        return NPPSim.totalTicks;
    }

    public static void main(String[] a) throws Exception {
        Localization.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        while (ticks() < 5) {
            Thread.sleep(20);
        }
        StandardIC.apply();

        long[] marks = {40, 200, 600, 1200, 2400};
        long t0 = System.currentTimeMillis();
        for (long m : marks) {
            while (ticks() < m) {
                Thread.sleep(20);
            }
            MCC.SeparatorDrum d = NPPSim.mcc.drum1;
            double scrollM3 = d.getSteamVolume();
            System.out.println(String.format(
                    "t=%5.1fs 堆 %.0f MW | 汽包 %.2f MPa 水温 %.0f 汽 %.2f t 汽空间 %.0f m³ 水 %.1f t 水位 %+.1f%%"
                    + " | 主汽门 %.0f%% 出汽 %.0f kg/s 产汽 %.0f | 出力 %.0f MW | 热井 %+.0f%% | AZ1K=%s",
                    m * 0.05, NPPSim.core.getThermalPower(),
                    d.getPressure(), d.getWaterTemperature(), d.getSteamMass() / 1000.0,
                    scrollM3, d.getWaterMass() / 1000.0, d.getWaterLevel(),
                    NPPSim.TG1InletValves.get(0).getPosition() * 100,
                    d.getSteamOutflowRate(), d.getSteamProduction(),
                    NPPSim.tg1.getGeneratorLoad() + NPPSim.tg2.getGeneratorLoad(),
                    NPPSim.tg1.condensers[0].getWaterLevel(),
                    NPPSim.autoControl.az1Control.isTripped()
                    + " 自动AZ=" + NPPSim.autoControl.azAutoEnabled));
            java.util.List<String> ev = NPPSim.autoControl.eventLog;
            if (!ev.isEmpty()) {
                int from = Math.max(0, ev.size() - 2);
                for (int i = from; i < ev.size(); i++) {
                    System.out.println("        事件: " + ev.get(i));
                }
            }
            System.out.flush();
        }
        System.out.println("墙钟耗时 " + (System.currentTimeMillis() - t0) / 1000 + " s");
        System.exit(0);
    }
}
