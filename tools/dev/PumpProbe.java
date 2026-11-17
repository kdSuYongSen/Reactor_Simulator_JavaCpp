package com.darwish.nppsim;

import java.lang.reflect.Field;

/**
 * 凝结水/给水泵逐台诊断（2026-11-02）。
 *
 * <p>StandardICProbe 只看到"凝结水联箱 0.11 MPa、除氧器进不了水"，
 * 但看不出是哪一级泵没出力。这里逐台打印 rpm / 流量 / 出口阀位 / 扬程，
 * 并用 NPPSim 的累计 tick 时长做**存活检测**（值不变 = 仿真线程真的停了）。</p>
 */
public class PumpProbe {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(400);
        System.out.println("---- 施加 StandardIC ----");
        StandardIC.apply();

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 12; i++) {
            Thread.sleep(5000);
            line((System.currentTimeMillis() - t0) / 1000.0);
        }
        System.exit(0);
    }

    static void line(double t) {
        Pump a1 = NPPSim.condensate1A.get(0);
        Pump b1 = NPPSim.condensate1B.get(0);
        Pump fw = NPPSim.mainFeedwaterPumps.get(0);
        System.out.printf(
            "t=%3.0f ticks=%d │ 1A rpm %.0f 阀 %.2f 流 %4.0f 扬程 %.2f │ 1B rpm %.0f 阀 %.2f 流 %4.0f 扬程 %.2f │ "
            + "给水 rpm %.0f 阀 %.2f 流 %4.0f 扬程 %.2f │ 联箱1 %.2f 联箱2 %.2f 主联箱 %.2f │ 除氧器 %.0f t%n",
            t, ticks(),
            a1.getRPM(), valvePos(a1), a1.getFlowRate(), a1.getHead(),
            b1.getRPM(), valvePos(b1), b1.getFlowRate(), b1.getHead(),
            fw.getRPM(), valvePos(fw), fw.getFlowRate(), fw.getHead(),
            NPPSim.condensateHeader1.getPressure(), NPPSim.condensateHeader2.getPressure(),
            NPPSim.condensateHeader.getPressure(),
            NPPSim.dearators.get(0).getWaterMass());
    }

    /** 泵出口阀位（dischargeValve 是基类的包内字段） */
    static double valvePos(Pump p) {
        try {
            Field f = Pump.class.getDeclaredField("dischargeValve");
            f.setAccessible(true);
            Object v = f.get(p);
            return ((WaterValve) v).getPosition();
        } catch (Exception e) {
            return -1;
        }
    }

    /** NPPSim 内部累计的 tick 时长 ns，用来判断仿真线程是否还在推进 */
    static long ticks() {
        try {
            Field f = NPPSim.class.getDeclaredField("timeStepLengthCumulative");
            f.setAccessible(true);
            return f.getLong(null) / 1_000_000L;
        } catch (Exception e) {
            return -1;
        }
    }
}
