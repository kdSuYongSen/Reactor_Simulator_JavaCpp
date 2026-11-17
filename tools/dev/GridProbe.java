package com.darwish.nppsim;

/**
 * 电网系统自检（2026-11-12）。
 *
 * <p>按场景推进，检查：正常态功率平衡 / 备用进线失去 / 双电源失去→主泵跳闸→柴油机自启 /
 * 恢复供电 / 220 kV 解列。最后确认标准工况装载后没有误跳泵。</p>
 */
public class GridProbe {

    public static void main(String[] args) throws Exception {
        Localization.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        waitTicks(40);
        StandardIC.apply();
        waitTicks(600);      // 30 s

        System.out.println("========== 1) 标准工况（正常接线） ==========");
        dump("t=30s");

        ElectricalGrid g = NPPSim.grid;
        System.out.println("========== 2) 断开两路备用进线 Q4/Q5 ==========");
        g.reserveBreakerA = false;
        g.reserveBreakerB = false;
        waitTicks(100);
        dump("备用进线已断开（仍由厂用分支供电）");

        System.out.println("========== 3) 再断厂用分支 A（Q2）→ A 段失电 ==========");
        g.unitBreakerA = false;
        waitTicks(60);
        dump("A 段失电 3 s");

        System.out.println("--- 等柴油机自启（10 s）---");
        waitTicks(400);
        dump("A 段失电 13 s");

        System.out.println("========== 4) 恢复：合厂用分支 A + 备用进线 ==========");
        g.unitBreakerA = true;
        g.reserveBreakerA = true;
        g.reserveBreakerB = true;
        waitTicks(400);
        dump("恢复 20 s");

        System.out.println("========== 5) 解列 750 kV（Q1 分）→ 孤岛 ==========");
        g.gridBreaker = false;
        waitTicks(200);
        dump("孤岛 10 s");
        g.gridBreaker = true;
        waitTicks(100);

        System.out.println("========== 5b) 全厂失电：再断 110 kV 进线（Q1） ==========");
        g.gridBreaker = false;
        g.line110Breaker = false;
        waitTicks(400);
        dump("全厂失电 20 s（等柴油机爬升）");
        g.gridBreaker = true;
        g.line110Breaker = true;
        waitTicks(200);
        dump("恢复 10 s");

        System.out.println("========== 6) 全厂失电：断厂用变 A/B + 两路备用 + 750/110 ==========");
        g.unitBreakerA = false;
        g.unitBreakerB = false;
        g.reserveBreakerA = false;
        g.reserveBreakerB = false;
        g.gridBreaker = false;
        g.line110Breaker = false;
        waitTicks(600);
        dump("全厂失电 30 s（柴油机应已带应急段）");
        System.out.println("========== 7) 恢复全部电源 ==========");
        g.unitBreakerA = true;
        g.unitBreakerB = true;
        g.reserveBreakerA = true;
        g.reserveBreakerB = true;
        g.gridBreaker = true;
        g.line110Breaker = true;
        waitTicks(400);
        dump("恢复 20 s");

        System.out.println("========== 8) 事件日志（最后 12 条） ==========");
        java.util.List<String> log = NPPSim.autoControl.eventLog;
        int from = Math.max(0, log.size() - 12);
        for (int i = from; i < log.size(); i++) {
            System.out.println("  " + log.get(i));
        }
        System.out.flush();
        System.exit(0);
    }

    private static void waitTicks(int ticks) throws InterruptedException {
        long target = NPPSim.totalTicks + ticks;
        while (NPPSim.totalTicks < target) {
            Thread.sleep(10);
        }
    }

    private static void dump(String tag) {
        ElectricalGrid g = NPPSim.grid;
        System.out.printf("  [%s] 发电 %.0f MW / 厂用 %.1f MW / 上网 %.0f MW / %.2f Hz / 蓄电池 %.0f%%%n",
                tag, g.getGenerationMW(), g.getAuxLoadMW(), g.getExportMW(), g.getFrequencyHz(),
                g.getBatterySoc() * 100);
        System.out.printf("    20 kV %s %s | 750 kV %s %s | 110 kV %s %s%n",
                g.has20() ? "带电" : "失电", String.format("%.1f kV", g.getVolt20()),
                g.has750() ? "带电" : "失电", String.format("%.0f kV", g.getVolt750()),
                g.has110() ? "带电" : "失电", String.format("%.1f kV", g.getVolt110()));
        System.out.printf("    6 kV A %s %.2f kV %.1f MW | 6 kV B %s %.2f kV %.1f MW%n",
                g.has6A() ? "带电" : "失电", g.getVolt6A(), g.getLoad6A(),
                g.has6B() ? "带电" : "失电", g.getVolt6B(), g.getLoad6B());
        System.out.printf("    应急 A   %s %.2f kV %.1f MW | 应急 B   %s %.2f kV %.1f MW | 柴油机 A/B %.1f/%.1f MW%n",
                g.has6Ae() ? "带电" : "失电", g.getVolt6Ae(), g.getLoad6Ae(),
                g.has6Be() ? "带电" : "失电", g.getVolt6Be(), g.getLoad6Be(),
                g.getDieselOutputA() * ElectricalGrid.DIESEL_MW,
                g.getDieselOutputB() * ElectricalGrid.DIESEL_MW);
        System.out.printf("    380 V %s %.0f V %.1f MW | 直流 %s %.0f V%n",
                g.has380() ? "带电" : "失电", g.getVolt04(), g.getLoad380(),
                g.hasDC() ? "正常" : "失电", g.getVoltDC());

        int mcpOn = 0, mcpTotal = 0;
        for (Pump p : NPPSim.mcc.mcp) {
            mcpTotal++;
            if (p.isActive()) {
                mcpOn++;
            }
        }
        int fwOn = 0;
        for (Pump p : NPPSim.mainFeedwaterPumps) {
            if (p.isActive()) {
                fwOn++;
            }
        }
        System.out.printf("    主循环泵 %d/%d 运行，主给水泵 %d/%d 运行，堆功率 %.0f MW%n",
                mcpOn, mcpTotal, fwOn, NPPSim.mainFeedwaterPumps.size(),
                NPPSim.core.getThermalPower());
    }

    private static String kv(double v) {
        return v >= 1.0 ? String.format("%.1f kV", v) : String.format("%.0f V", v * 1000.0);
    }

    private static String mw(double v) {
        return String.format("%.1f MW", v);
    }
}
