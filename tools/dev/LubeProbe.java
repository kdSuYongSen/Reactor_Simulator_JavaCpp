package com.darwish.nppsim;

/**
 * 柴油机润滑油泵联锁自检（2026-11-13，4 台机版本）。
 *
 * <p>验证四件事：</p>
 * <ol>
 *   <li>默认全泵运行时，四台机的母管压力正常、每台运行泵数 = 2（本机泵 + 公用 E）；</li>
 *   <li>把 DG-A1 的 A 泵和公用 E 停掉 → 该机油压跌到 0 → 运行中低油压跳机（3 s 延时）；</li>
 *   <li>油压没建立时手动起机被闭锁；</li>
 *   <li>恢复 A 泵 → 油压回升 → 可以重新起机。</li>
 * </ol>
 */
public class LubeProbe {

    public static void main(String[] a) throws Exception {
        Localization.init();
        Localization.setLanguage(Language.ZH_CN);
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        while (NPPSim.totalTicks < 40) {
            Thread.sleep(10);
        }
        StandardIC.apply();
        waitTicks(40);

        ElectricalGrid g = NPPSim.grid;
        System.out.println("========== 1) 默认状态（A~E 全开） ==========");
        dump(g);

        System.out.println("\n========== 2) 手动启动 DG-A1 ==========");
        g.setDieselRunning(0, true);
        waitTicks(300);
        dump(g);

        System.out.println("\n========== 3) 停 A 泵 + 公用 E（DG-A1 失去全部润滑油泵） ==========");
        g.setLubePump(0, false);
        g.setLubePump(4, false);
        for (int i = 1; i <= 6; i++) {
            waitTicks(100);
            System.out.printf("   %2d s: DG-A1 油压 %.2f bar / 运行泵 %d / 机组 %s%n",
                    i * 5, g.getDieselLubePress(0), g.getDieselLubePumpCount(0),
                    g.isDieselRunning(0) ? "运行" : "停机");
        }

        System.out.println("\n========== 4) 油压为 0 时尝试起机（应被闭锁） ==========");
        g.setDieselRunning(0, true);
        waitTicks(40);
        System.out.printf("   DG-A1 %s（期望：停机）%n", g.isDieselRunning(0) ? "运行" : "停机");

        System.out.println("\n========== 5) 恢复 A 泵 → 油压回升 → 起机 ==========");
        g.setLubePump(0, true);
        waitTicks(120);
        System.out.printf("   油压 %.2f bar / 运行泵 %d%n",
                g.getDieselLubePress(0), g.getDieselLubePumpCount(0));
        g.setDieselRunning(0, true);
        waitTicks(300);
        dump(g);

        System.out.println("\n========== 6) 柴油机相关事件 ==========");
        for (String e : NPPSim.autoControl.eventLog) {
            if (e.toLowerCase().contains("lube") || e.toLowerCase().contains("diesel")) {
                System.out.println("   " + e);
            }
        }
        System.exit(0);
    }

    private static void dump(ElectricalGrid g) {
        for (int u = 0; u < ElectricalGrid.DIESEL_COUNT; u++) {
            System.out.printf("   DG-%-3s 油压 %.2f bar / 运行泵 %d / 转速 %.0f rpm / %.2f MW%n",
                    ElectricalGrid.dieselTag(u), g.getDieselLubePress(u),
                    g.getDieselLubePumpCount(u), g.getDieselRpm(u), g.getDieselPowerMW(u));
        }
    }

    private static void waitTicks(int n) throws Exception {
        long target = NPPSim.totalTicks + n;
        while (NPPSim.totalTicks < target) {
            Thread.sleep(5);
        }
    }
}
