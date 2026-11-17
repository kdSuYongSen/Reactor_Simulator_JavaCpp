package com.darwish.nppsim;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;

/**
 * 性能基线探针（2026-11-11）。
 *
 * <p>目标：分清"仿真算不过来"和"界面刷新拖后腿"两件事。</p>
 * <ul>
 *   <li>tick/s：主循环每 tick 目标 50 ms（20 tick/s）。若实测 &lt; 20，说明单 tick 计算超预算。</li>
 *   <li>GC 次数/耗时：每 tick 若大量分配临时对象，Old/Young 回收会明显抬升单 tick 时间。</li>
 *   <li>堆占用：稳态堆大小是否一路上涨（泄漏）。</li>
 * </ul>
 *
 * 用法：{@code java -cp "target/devclasses;target/classes;<fat.jar>" com.darwish.nppsim.PerfProbe [秒数]}
 */
public class PerfProbe {

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 15;

        Localization.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();

        long t0 = System.nanoTime();
        new NPPSim();
        long initMs = (System.nanoTime() - t0) / 1_000_000L;

        while (NPPSim.totalTicks < 20) {
            Thread.sleep(10);
        }
        StandardIC.apply();
        Thread.sleep(2000);

        Runtime rt = Runtime.getRuntime();
        long heapBefore = rt.totalMemory() - rt.freeMemory();
        long gcCountBefore = 0, gcTimeBefore = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCountBefore += Math.max(0, g.getCollectionCount());
            gcTimeBefore += Math.max(0, g.getCollectionTime());
        }

        long tickStart = NPPSim.totalTicks;
        long w0 = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        long w1 = System.nanoTime();
        long tickEnd = NPPSim.totalTicks;

        long gcCountAfter = 0, gcTimeAfter = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCountAfter += Math.max(0, g.getCollectionCount());
            gcTimeAfter += Math.max(0, g.getCollectionTime());
        }
        long heapAfter = rt.totalMemory() - rt.freeMemory();

        double wall = (w1 - w0) / 1e9;
        long ticks = tickEnd - tickStart;
        double perTickMs = wall * 1000.0 / Math.max(1, ticks);

        System.out.println("=================================================");
        System.out.printf("启动耗时          : %d ms%n", initMs);
        System.out.printf("观测窗口          : %.1f s%n", wall);
        System.out.printf("推进 tick         : %d  (%.1f tick/s，目标 20)%n", ticks, ticks / wall);
        System.out.printf("单 tick 墙钟      : %.2f ms/拍（含 50 ms 节拍等待）%n", perTickMs);
        System.out.printf("实时性            : %s（20 tick/s 为实时）%n",
                ticks / wall >= 19.5 ? "达标" : "欠账，单拍计算已超 50 ms");
        System.out.printf("GC                : %d 次 / %d ms%n",
                gcCountAfter - gcCountBefore, gcTimeAfter - gcTimeBefore);
        System.out.printf("堆占用            : %.1f MB → %.1f MB (max %.0f MB)%n",
                heapBefore / 1048576.0, heapAfter / 1048576.0, rt.maxMemory() / 1048576.0);
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == java.lang.management.MemoryType.HEAP) {
                System.out.printf("  %-28s used %6.1f MB%n", p.getName(), p.getUsage().getUsed() / 1048576.0);
            }
        }
        System.out.printf("最终工况          : 堆 %.0f MW / 汽包 %.2f MPa%n",
                NPPSim.core.getThermalPower(), NPPSim.mcc.drum1.getPressure());
        System.out.printf("已注册窗口        : %d 个（后台预热是否生效）%n",
                UI.elementsToUpdate == null ? -1 : UI.elementsToUpdate.size());
        System.out.println("=================================================");
        System.out.flush();
        System.exit(0);
    }
}
