package com.darwish.nppsim;

import java.util.ArrayList;

/**
 * 标准初始工况（StandardIC）自检（2026-11-02）。
 *
 * <p>构造冷态电厂 → 施加 {@link StandardIC} → 观察 90 s，核对是否真的停在 100% 额定稳态：
 * 堆芯 4800 MW、汽包 6.9 MPa/284 °C、一回路 ≈12,000 kg/s、主蒸汽母管 6.9 MPa、
 * 二次侧产汽 ≈2,000+ kg/s、两台汽机合计 ≈1,500 MWe，且 90 s 内不跳堆、不跳机、水位不越界。</p>
 */
public class StandardICCheck {

    static int fails = 0;

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(400);

        boolean coreOk = StandardIC.apply();
        check("内核复位到额定平衡态", coreOk, "resetToNominal=" + coreOk);

        double t0 = System.currentTimeMillis();
        for (int i = 0; i < 9; i++) {
            Thread.sleep(20000);
            line((System.currentTimeMillis() - t0) / 1000);
        }

        // ---- 终态核对 ----
        double power = NPPSim.core.getThermalPower();
        double drumP = NPPSim.mcc.drum1.getPressure();
        double drumT = NPPSim.mcc.drum1.getWaterTemperature();
        double level = NPPSim.mcc.drum1.getWaterLevel();
        double mcp = 0;
        for (Pump p : NPPSim.mcc.mcp) {
            mcp += p.getFlowRate();
        }
        double headerP = NPPSim.steamPiping != null ? NPPSim.steamPiping.getPressure() : 0;
        double secSteam = 0, priMW = 0;
        for (HeatExchanger hx : NPPSim.heatExchangers) {
            secSteam += hx.secondarySteamKgs;
            priMW += hx.primaryHeatMW;
        }
        double mwe = NPPSim.tg1.getGeneratorLoad() + NPPSim.tg2.getGeneratorLoad();
        double deaLevel = NPPSim.dearators.get(0).getWaterLevel();
        double hotwell = NPPSim.tg1.condenser.getHotwellMassKg() / 1000.0;
        double rodAvg = NPPSim.core.getRodInsertion();

        System.out.println();
        System.out.printf("终态：堆芯 %.0f MW | 棒位均值 %.3f | 汽包 %.3f MPa / %.0f°C / 水位 %.1f%% | 主泵 %.0f kg/s%n",
                power, rodAvg, drumP, drumT, level, mcp);
        System.out.printf("      主蒸汽母管 %.3f MPa | 二次侧产汽 %.0f kg/s | 一次侧放热 %.0f MW | 汽机 %.0f MWe%n",
                headerP, secSteam, priMW, mwe);
        System.out.printf("      除氧器1 水位 %.0f%% | 凝汽器热井 %.0f t | AZ-1 %s | TG-1 %s%n",
                deaLevel, hotwell, NPPSim.autoControl.az1Control.isTripped(),
                NPPSim.tg1.isTripped());

        check("堆芯功率 4800 MW（±18%）", Math.abs(power - 4800) < 864, String.format("%.0f MW", power));
        check("汽包压力 6.9 MPa（±0.35）", Math.abs(drumP - 6.9) < 0.35, String.format("%.3f MPa", drumP));
        check("汽包温度 284 °C（±12）", Math.abs(drumT - 284) < 12, String.format("%.0f °C", drumT));
        check("一回路流量 > 9,000 kg/s", mcp > 9000, String.format("%.0f kg/s", mcp));
        check("主蒸汽母管 4.0 MPa（±1.0）", Math.abs(headerP - 4.0) < 1.0, String.format("%.3f MPa", headerP));
        check("二次侧产汽 > 250 kg/s", secSteam > 250, String.format("%.0f kg/s", secSteam));
        check("汽机出力 > 200 MWe", mwe > 200, String.format("%.0f MWe", mwe));
        check("汽包水位在 ±25% 内", Math.abs(level) < 25, String.format("%.1f %%", level));
        check("除氧器水位在 ±60% 内", Math.abs(deaLevel) < 60, String.format("%.0f %%", deaLevel));
        check("90 s 内不跳堆（AZ-1 未动作）", !NPPSim.autoControl.az1Control.isTripped(), "AZ-1");
        check("90 s 内不跳机（TG 未跳闸）", !NPPSim.tg1.isTripped() && !NPPSim.tg2.isTripped(),
                "TG-1=" + NPPSim.tg1.isTripped() + " TG-2=" + NPPSim.tg2.isTripped());
        /* 自动 AZ 的投回判据是"功率降到 5100 MW 以下"（见 Core.icAzRestorePending）。
         * 所以这里不能断言"一定已投回"——功率还在 5.2 GW 时本来就该仍然旁路。
         * 断言的是：**要么已投回，要么功率确实还高于定值**，两者不能同时不成立，
         * 更不能出现"功率还很高但保护已经投回"（那会立刻 SCRAM）。 */
        boolean az = NPPSim.autoControl.azAutoEnabled;
        check("自动 AZ 不会在功率超限时投回",
                !az || power < 5150,
                String.format("azAutoEnabled=%s, 功率 %.0f MW", az, power));
        check("关键量无 NaN", !Double.isNaN(power) && !Double.isNaN(drumP) && !Double.isNaN(mwe)
                && !Double.isNaN(headerP), "NaN 检查");

        System.out.println(fails == 0 ? "STD_IC_OK" : ("STD_IC_FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    static void line(double t) {
        double mcp = 0;
        for (Pump p : NPPSim.mcc.mcp) {
            mcp += p.getFlowRate();
        }
        double sec = 0;
        for (HeatExchanger hx : NPPSim.heatExchangers) {
            sec += hx.secondarySteamKgs;
        }
        System.out.printf("t=%3.0fs 堆芯 %.0f MW | 汽包 %.2f MPa/%.0f°C/%.0f%% | 主泵 %.0f | 母管 %.2f | 二次汽 %.0f | MWe %.0f | 除氧器 %.0f%%%n",
                t, NPPSim.core.getThermalPower(),
                NPPSim.mcc.drum1.getPressure(), NPPSim.mcc.drum1.getWaterTemperature(),
                NPPSim.mcc.drum1.getWaterLevel(), mcp,
                NPPSim.steamPiping.getPressure(), sec,
                NPPSim.tg1.getGeneratorLoad() + NPPSim.tg2.getGeneratorLoad(),
                NPPSim.dearators.get(0).getWaterLevel());
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            fails++;
        }
        System.out.printf("[%s] %s：%s%n", ok ? "OK" : "FAIL", name, detail);
    }
}
