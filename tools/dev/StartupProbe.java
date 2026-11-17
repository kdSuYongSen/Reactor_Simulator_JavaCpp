package com.darwish.nppsim;

/**
 * 启动耗时拆解（2026-11-11）。
 *
 * <p>分段计时：蒸汽表解析 / 堆芯（1661 通道 + native 内核）/ 一回路 MCC / 整机 NPPSim（含 UI）。</p>
 */
public class StartupProbe {

    public static void main(String[] args) throws Exception {
        Localization.init();
        Loader.soundProvider = new SoundProvider();

        long t = System.nanoTime();
        Loader.tables = new SteamTables();
        mark("SteamTables 解析", t);

        t = System.nanoTime();
        Core core = new Core();
        mark("Core 构建（3136 通道 + native 内核）", t);
        System.out.println("    堆芯热功率 " + core.getThermalPower() + " MW");

        t = System.nanoTime();
        new ReactorKinetics();
        mark("ReactorKinetics 单建", t);

        t = System.nanoTime();
        NPPSim.core = core;
        new MTK();
        mark("MTK 堆芯图（2500 格）", t);

        t = System.nanoTime();
        new NPPSim();
        mark("NPPSim 整机（含 UI 与布局）", t);

        System.out.flush();
        System.exit(0);
    }

    private static void mark(String label, long startNano) {
        long ms = (System.nanoTime() - startNano) / 1_000_000L;
        System.out.printf("%-42s %7d ms%n", label, ms);
        System.out.flush();
    }
}
