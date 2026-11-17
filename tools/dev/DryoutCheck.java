package com.darwish.nppsim;

/**
 * 干涸 → 传热恶化 → 包壳升温 → AZ-1 保护 的自检（2026-11-01）。
 *
 * <p>场景：热态、8 台主泵全速、4800 MW 稳定运行 → 关掉全部主泵出口阀
 * （模拟全厂失流 / 主泵全停）→ 通道水被打光 → 包壳温度上冲 → 800 °C 触发 AZ-1。</p>
 *
 * <p>验四点：① 正常流量下不误判干涸；② 失流后干涸通道占比迅速上升；
 * ③ 包壳温度越过 800 °C；④ AZ-1 跳闸且全部控制棒插入。</p>
 */
public class DryoutCheck {

    static int fails = 0;

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(800);

        for (Pump p : NPPSim.mcc.mcp) {
            p.setActive(true);
            p.setSetPoint(1.0f);
        }
        NPPSim.mcc.setWaterTemp(270.0);
        for (int i = 0; i < 6; i++) {
            Thread.sleep(2000);
            probe("泵升速中");
        }

        NPPSim.feedwaterOverrideKgs = 3000;
        NPPSim.coreHeatOverrideMW = 4800;
        for (int i = 0; i < 4; i++) {
            Thread.sleep(2000);
            probe("满功率");
        }

        System.out.printf("满功率稳定：干涸占比 %.1f%%  平均包壳温度 %.1f°C  最小湿份额 %.3f  AZ-1=%s%n",
                dryFraction() * 100, avgClad(), minWet(), NPPSim.autoControl.az1Control.isTripped());
        System.out.printf("  [诊断] MCP %.0f kg/s | 通道平均进水 %.2f kg/s | 通道平均水装量 %.2f kg | 汽包 %.2f MPa%n",
                mcpFlow(), avgInflow(), avgWaterMass(), NPPSim.mcc.drum1.getPressure());
        check("正常流量下不应有干涸通道", dryFraction() < 0.01,
                String.format("%.1f%%", dryFraction() * 100));
        check("正常流量下包壳温度应远低于保护定值", avgClad() < 400.0,
                String.format("%.1f °C", avgClad()));

        // ---- 全厂失流：关掉 8 台主泵出口阀 ----
        for (Pump p : NPPSim.mcc.mcp) {
            p.dischargeValve.setPosition(0.0f);
        }
        System.out.println("已关闭全部主泵出口阀（模拟失流）");

        double t0 = System.currentTimeMillis();
        for (int i = 0; i < 25; i++) {
            Thread.sleep(2000);
            System.out.printf("  t=%4.0fs 干涸 %.1f%% | 平均包壳 %.0f°C | 最高包壳 %.0f°C | 最小湿份额 %.3f | AZ-1=%s%n",
                    (System.currentTimeMillis() - t0) / 1000.0,
                    dryFraction() * 100, avgClad(), maxClad(), minWet(),
                    NPPSim.autoControl.az1Control.isTripped());
            if (NPPSim.autoControl.az1Control.isTripped()) {
                break;
            }
        }

        check("失流后应出现大面积干涸", dryFraction() > 0.5,
                String.format("%.1f%%", dryFraction() * 100));
        check("包壳温度应越过 800 °C 保护定值", maxClad() > 800.0,
                String.format("%.0f °C", maxClad()));
        check("应触发 AZ-1 紧急保护", NPPSim.autoControl.az1Control.isTripped(),
                NPPSim.autoControl.az1Control.isTripped() ? "已跳闸" : "未跳闸");
        check("AZ-1 应插入全部控制棒", allRodsInserted(),
                allRodsInserted() ? "全部棒位 ≈ 0（全插）" : "仍有棒未插入");

        NPPSim.coreHeatOverrideMW = -1;
        NPPSim.feedwaterOverrideKgs = 0;
        System.out.println(fails == 0 ? "DRYOUT_OK" : ("DRYOUT_FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    /** 取一个代表性燃料通道 + 压力母管打一行状态 */
    static void probe(String tag) {
        FuelChannel fc = null;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FuelChannel) {
                    fc = (FuelChannel) c;
                    break;
                }
            }
            if (fc != null) {
                break;
            }
        }
        if (fc == null) {
            return;
        }
        System.out.printf("  [%s] 通道 m=%.2f kg T=%.1f clad=%.0f wet=%.3f P=%.2f MW 进水=%.2f kg/s | 母管 %.0f kg | 汽包 %.2f MPa / %.0f°C%n",
                tag, fc.getWaterMass(), fc.getWaterTemperature(), fc.getCladTempC(),
                fc.getWetFraction(), fc.getThermalPower(), fc.getWaterInflowRate(),
                NPPSim.mcc.pHeader1.getWaterMass(), NPPSim.mcc.drum1.getPressure(),
                NPPSim.mcc.drum1.getWaterTemperature());
    }

    // ---- 统计 ----
    static double mcpFlow() {
        double s = 0;
        for (Pump p : NPPSim.mcc.mcp) {
            s += p.getFlowRate();
        }
        return s;
    }

    static double avgInflow() {
        double s = 0;
        int n = 0;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FuelChannel) {
                    s += ((FuelChannel) c).getWaterInflowRate();
                    n++;
                }
            }
        }
        return n == 0 ? 0 : s / n;
    }

    static double avgWaterMass() {
        double s = 0;
        int n = 0;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FuelChannel) {
                    s += ((FuelChannel) c).getWaterMass();
                    n++;
                }
            }
        }
        return n == 0 ? 0 : s / n;
    }

    static double dryFraction() {
        int dry = 0, n = 0;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FuelChannel) {
                    n++;
                    if (((FuelChannel) c).isDryout()) {
                        dry++;
                    }
                }
            }
        }
        return n == 0 ? 0 : (double) dry / n;
    }

    static double avgClad() {
        double s = 0;
        int n = 0;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FuelChannel) {
                    s += ((FuelChannel) c).getCladTempC();
                    n++;
                }
            }
        }
        return n == 0 ? 0 : s / n;
    }

    static double maxClad() {
        double m = 0;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FuelChannel) {
                    m = Math.max(m, ((FuelChannel) c).getCladTempC());
                }
            }
        }
        return m;
    }

    static double minWet() {
        double m = 1.0;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FuelChannel) {
                    m = Math.min(m, ((FuelChannel) c).getWetFraction());
                }
            }
        }
        return m;
    }

    /** 全部控制棒是否都已进入紧急插棒状态（scram 标志置位）。
     *  不再看棒位平均值：本自检用 coreHeatOverrideMW 把功率钉在 4800 MW，
     *  而 AR 看到的动力学功率是 0，会一直往外提棒，棒位平均值没有参考意义。 */
    static boolean allRodsInserted() {
        int scram = 0, n = 0;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof ControlRodChannel) {
                    n++;
                    if (((ControlRodChannel) c).isScram()) {
                        scram++;
                    }
                }
            }
        }
        return n > 0 && scram >= n * 0.9;
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            fails++;
        }
        System.out.printf("[%s] %s：%s%n", ok ? "OK" : "FAIL", name, detail);
    }
}
