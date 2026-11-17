package com.darwish.nppsim;

import java.util.ArrayList;

/**
 * 汽轮机出力链定点诊断（2026-11-03）。
 *
 * <p>打印从分离器到发电机的每一段：分离器产汽 → 主蒸汽母管（可供量/压力）→
 * 主汽门开度与流量 → 汽轮机进汽 → 两级抽汽 → 凝汽器真空 → 单位质量功 → 电功率。
 * 用来判断"出力上不去"到底卡在汽量、真空还是焓降。</p>
 */
public class TurbineProbe {

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

        double t0 = System.currentTimeMillis();
        for (int i = 0; i < 18; i++) {
            Thread.sleep(10000);
            line((System.currentTimeMillis() - t0) / 1000.0);
        }
        System.exit(0);
    }

    static void line(double t) {
        Separator sep = NPPSim.separators.get(0);
        TG tg = NPPSim.tg1;
        double vPos = 0, vFlow = 0;
        for (SteamValve v : NPPSim.TG1InletValves) {
            vPos += v.getPosition();
            vFlow += v.getFlowRate();
        }
        vPos /= NPPSim.TG1InletValves.size();
        double sdv = 0;
        if (NPPSim.sdv_c != null) {
            for (SteamValve v : NPPSim.sdv_c) {
                sdv += v.getPosition();
            }
            sdv /= NPPSim.sdv_c.size();
        }
        double daSteam = 0, daPos = 0;
        for (Dearator d : NPPSim.dearators) {
            daSteam += d.steamInlet.getFlowRate();
            daPos += d.steamInlet.getPosition();
        }
        daPos /= NPPSim.dearators.size();
        double mwe = tg.getGeneratorLoad();
        double tIn = tg.getSteamTemperature();
        double pCond = tg.condenser != null ? tg.condenser.getPressure() : -1;
        double w = TG.specificWorkKjPerKg(tIn, pCond);
        double inflow = tg.getSteamInflow() * 20.0;

        System.out.printf(
            "t=%3.0f │ 分离器 %.2f MPa 产汽 %.0f │ 母管 %.2f MPa 可供 %.0f │ 主汽门 %.2f→%.0f kg/s │ "
            + "进汽 %.0f kg/s %.0f°C │ 抽汽 除氧 %.0f 低加 %.0f (阀 %.2f) │ 旁路 %.2f │ 真空 %.3f MPa │ w %.0f kJ/kg │ "
            + "出力 %.0f MWe │ rpm %.0f %s%n",
            t, sep.pressureMPa, sep.outSteamKgs,
            NPPSim.steamPiping.getPressure(), NPPSim.steamPiping.availableSteamKg() * 20.0,
            vPos, vFlow * 20.0,
            inflow, tIn,
            tg.extractionPort.getRateKgs(), tg.lpExtractionPort.getRateKgs(), daPos,
            sdv, pCond, w, mwe, tg.getRpm(), tg.isTripped() ? "已跳闸" : "");
    }
}
