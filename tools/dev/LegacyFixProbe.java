package com.darwish.nppsim;

/**
 * 停堆链路诊断（2026-11-07）。
 *
 * <p>盯住"堆功率为什么从 5500 掉到 1900、汽机为什么逆功率"这条链：
 * 功率 / 反应性 / АР 棒位 / 一回路流量 / 汽包进出 / 主汽门 / 出力。</p>
 */
public class LegacyFixProbe {

    public static void main(String[] a) throws Exception {
        Localization.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        while (NPPSim.totalTicks < 5) {
            Thread.sleep(20);
        }
        StandardIC.apply();
        System.out.println("装载后立即：" + fasrInfo());
        System.out.flush();

        long[] marks = {100, 200, 400, 600, 800, 1000, 1400, 1800, 2400, 3000, 3600};
        for (long m : marks) {
            while (NPPSim.totalTicks < m) {
                Thread.sleep(20);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("t=%5.1fs | 堆 %5.0f MW R=%+.4f 周期 %6.1f | АР设定 %5.0f 棒位 %.2f%n",
                    m * 0.05, NPPSim.core.getThermalPower(), NPPSim.core.getReactivity(),
                    NPPSim.core.getPeriod(),
                    NPPSim.autoControl.automaticRodController.getSetpoint(),
                    avgRodPos()));

            double mcp = 0;
            for (Pump p : NPPSim.mcc.mcp) {
                mcp += p.getFlowRate();
            }
            MCC.SeparatorDrum d = NPPSim.mcc.drum1;
            sb.append(String.format("          汽包 %.2f MPa/%.0f°C/%+.0f%% 水 %.0f t 产汽 %.0f 出汽 %.0f | 一回路 %.0f kg/s%n",
                    d.getPressure(), d.getWaterTemperature(), d.getWaterLevel(),
                    d.getWaterMass() / 1000.0, d.getSteamProduction(), d.getSteamOutflowRate(), mcp));

            double fw = 0;
            for (Pump p : NPPSim.mainFeedwaterPumps) {
                fw += p.getFlowRate();
            }
            sb.append(String.format("          АР 棒数=%d 序列闭锁=%s 限位=%s/%s 错误=%s/%s 棒速=%.5f%n",
                    NPPSim.autoControl.automaticRodController.linkedChannels.size(),
                    NPPSim.autoControl.fasrControl.getSequenceBlock(),
                    flag(NPPSim.autoControl.automaticRodController.onLimit(), 0),
                    flag(NPPSim.autoControl.automaticRodController.onLimit(), 1),
                    flag(NPPSim.autoControl.automaticRodController.hasError(), 0),
                    flag(NPPSim.autoControl.automaticRodController.hasError(), 1),
                    RodSpeed.stepPerTick()));
            Separator sep = NPPSim.separators.get(0);
            HeatExchanger hx = NPPSim.heatExchangers.get(0);
            sb.append(String.format("          二次侧：分离器 %.2f MPa 产汽 %.0f 疏水 %.0f | 换热器二次汽 %.0f 管侧水 %.0f t | 母管 %.2f MPa%n",
                    sep.pressureMPa, sep.outSteamKgs, sep.outWaterKgs,
                    hx.steamOutKgs, hx.secondaryWaterKg / 1000.0,
                    NPPSim.steamPiping.getPressure()));
            sb.append(String.format("          主汽门 %.0f%% 进汽 %.0f 出力 %5.0f MW rpm %.0f 跳闸=%s | 给水 %.0f 阀 %.2f | AZ1K=%s%n",
                    NPPSim.TG1InletValves.get(0).getPosition() * 100,
                    NPPSim.tg1.getSteamInflow(), NPPSim.tg1.getGeneratorLoad(),
                    NPPSim.tg1.getRpm(), NPPSim.tg1.isTripped(),
                    fw, NPPSim.mainFeederValves.get(0).getPosition(),
                    NPPSim.autoControl.az1Control.isTripped()));
            System.out.print(sb);
            java.util.List<String> ev = NPPSim.autoControl.eventLog;
            if (!ev.isEmpty()) {
                System.out.println("          最近事件: " + ev.get(ev.size() - 1));
            }
            System.out.flush();
        }
        System.exit(0);
    }

    private static String fasrInfo() {
        float mx = -1, mn = 2;
        int total = 0, inserted = 0;
        int atZero = 0, atHalf = 0;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FASRChannel) {
                    float p = ((FASRChannel) c).getPosition();
                    total++;
                    mx = Math.max(mx, p);
                    mn = Math.min(mn, p);
                    if (p > 0.005f) {
                        inserted++;
                    }
                    if (p > 0.4f && p < 0.6f) {
                        atHalf++;
                    }
                    if (p <= 0.005f) {
                        atZero++;
                    }
                }
            }
        }
        return "FASR " + total + " 根，位置 " + String.format("%.3f…%.3f", mn, mx)
                + "，全提 " + atZero + "，半插 " + atHalf + "，未提尽 " + inserted
                + "，序列闭锁=" + NPPSim.autoControl.fasrControl.getSequenceBlock();
    }

    private static String flag(boolean[] a, int i) {
        return i < a.length && a[i] ? "是" : "否";
    }

    private static double avgRodPos() {
        java.util.ArrayList<ControlRodChannel> ch = NPPSim.autoControl.automaticRodController.linkedChannels;
        if (ch.isEmpty()) {
            return -1;
        }
        double s = 0;
        for (ControlRodChannel c : ch) {
            s += c.getPosition();
        }
        return s / ch.size();
    }
}
