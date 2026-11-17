package com.darwish.nppsim;

/**
 * 燃料通道"干涸 → 传热恶化 → 包壳升温"的**单元级**自检（2026-11-01）。
 *
 * <p>不启动全厂（NPPSim 构造要两分钟），只拿一根 FuelChannel + 一个桩汽包，
 * 直接验证两节点模型的质量/能量行为。全厂联调用 DryoutCheck。</p>
 *
 * <p>判据：① 正常流量下不干涸、包壳稳定在水温 + 约 20 K；
 * ② 断流后水装量按蒸发速率下降、干涸标志置位；
 * ③ 干涸后包壳温度以数十 K/s 上冲，越过 800 °C。</p>
 */
public class ChannelDryoutUnitCheck {

    static int fails = 0;

    /** 只提供压力/温度/水位的桩汽包 */
    static class StubDrum implements Connectable {
        double p = 6.9, t = 284.0;

        @Override public double getPressure() { return p; }
        @Override public double getSteamDensity() { return 0.0274; }
        @Override public double getWaterDensity() { return 0.001352; }
        @Override public double getSteamMass() { return 0.0; }
        @Override public double getWaterMass() { return 2.0e5; }
        @Override public double getSteamVolume() { return 100.0; }
        @Override public double getWaterTemperature() { return t; }
        @Override public double getSteamTemperature() { return t; }
        @Override public double getWaterLevel() { return 0.0; }
        @Override public void updateSteamOutflow(double flow, double tempC) { }
        @Override public void updateSteamInflow(double flow, double tempC) { }
        @Override public void updateWaterOutflow(double flow, double tempC) { }
        @Override public void updateWaterInflow(double flow, double tempC) { }
    }

    public static void main(String[] a) throws Exception {
        Localization.init();
        Loader.tables = new SteamTables();

        FuelChannel fc = new FuelChannel();
        fc.setDrain(new StubDrum());
        fc.setWaterTemp(270.0);
        fc.setSteamPressure(6.9);
        fc.setKineticsPower(2.89, 0.0, 0.0);     // 额定通道功率（4800 MW / 1661）

        // ---- 1) 正常流量 5 kg/s，跑 30 s ----
        double mass0 = fc.getWaterMass();
        for (int i = 0; i < 600; i++) {
            fc.updateWaterInflow(5.0 * 0.05, 270.0);   // kg/tick
            fc.update();
            if (i < 12 || i % 50 == 0) {
                System.out.printf("    tick %3d  m=%.3f  T=%.2f  clad=%.1f  in=%.3f  wet=%.3f%n",
                        i, fc.getWaterMass(), fc.getWaterTemperature(), fc.getCladTempC(),
                        fc.getWaterInflowRate(), fc.getWetFraction());
            }
        }
        System.out.printf("正常流量 30 s：m=%.2f kg  T=%.1f  clad=%.1f  wet=%.3f  dry=%s%n",
                fc.getWaterMass(), fc.getWaterTemperature(), fc.getCladTempC(),
                fc.getWetFraction(), fc.isDryout());
        check("正常流量下不应干涸", !fc.isDryout(), "dry=" + fc.isDryout());
        check("正常流量下水装量应接近额定 12.5 kg",
                Math.abs(fc.getWaterMass() - 12.5) < 1.5,
                String.format("%.2f kg（初始 %.2f）", fc.getWaterMass(), mass0));
        check("正常流量下包壳温度应低于 350 °C", fc.getCladTempC() < 350.0,
                String.format("%.1f °C", fc.getCladTempC()));

        // ---- 2) 断流：记录包壳升温曲线 ----
        double cladStart = fc.getCladTempC();
        double maxClad = cladStart;
        double dryAt = -1.0;
        double tripAt = -1.0;
        for (int i = 0; i < 2400; i++) {           // 120 s 上限
            fc.updateWaterInflow(0.0, 270.0);
            fc.update();
            double sec = i * 0.05;                  // 仿真时间（每 tick 50 ms），不是墙钟
            maxClad = Math.max(maxClad, fc.getCladTempC());
            if (dryAt < 0 && fc.isDryout()) {
                dryAt = sec;
            }
            if (tripAt < 0 && fc.getCladTempC() > 800.0) {
                tripAt = sec;
                break;
            }
            if (i % 100 == 0) {
                System.out.printf("  断流 t=%5.1fs  m=%.3f kg  T=%.1f  clad=%.0f  wet=%.3f  dry=%s%n",
                        sec, fc.getWaterMass(), fc.getWaterTemperature(), fc.getCladTempC(),
                        fc.getWetFraction(), fc.isDryout());
            }
        }
        System.out.printf("断流结果：干涸于 %.1fs，包壳 800 °C 于 %.1fs，最高 %.0f °C%n",
                dryAt, tripAt, maxClad);
        check("断流后应判定干涸", dryAt >= 0.0, String.format("%.1f s", dryAt));
        check("干涸后包壳温度应越过 800 °C", maxClad > 800.0, String.format("%.0f °C", maxClad));
        check("包壳升温速率应在合理量级（5~200 K/s）",
                tripAt > 0 && (800.0 - cladStart) / Math.max(0.1, tripAt) < 200.0,
                String.format("%.0f K/s", tripAt > 0 ? (800.0 - cladStart) / tripAt : -1));

        // ---- 3) 恢复给水后应回湿、降温 ----
        for (int i = 0; i < 400; i++) {            // 20 s 恢复流量
            fc.updateWaterInflow(5.0 * 0.05, 270.0);
            fc.update();
        }
        System.out.printf("恢复给水 20 s：m=%.2f kg  clad=%.0f  wet=%.3f  dry=%s%n",
                fc.getWaterMass(), fc.getCladTempC(), fc.getWetFraction(), fc.isDryout());
        check("恢复给水后应判定为不干涸", !fc.isDryout(), "wet=" + String.format("%.3f", fc.getWetFraction()));
        check("恢复给水后包壳温度应回落", fc.getCladTempC() < 500.0,
                String.format("%.0f °C", fc.getCladTempC()));

        System.out.println(fails == 0 ? "CHANNEL_DRYOUT_OK" : ("CHANNEL_DRYOUT_FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            fails++;
        }
        System.out.printf("[%s] %s：%s%n", ok ? "OK" : "FAIL", name, detail);
    }
}
