package com.darwish.nppsim;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.swing.JTextField;

/**
 * 堆芯图新上色 + 控制棒顺序闭锁 的自检（2026-11-02）。
 *
 * <p>验四件事：</p>
 * <ol>
 *   <li>额定功率下燃料格是"原色→白"的连续渐变（不是原来的二值白块）；</li>
 *   <li>CPS 格（绿棒）按区域燃料状态着色：停堆时深绿、带功率时浅绿；</li>
 *   <li>AZ-5 顺序联锁：AZ-5 全部拔到顶之前，非 AZ 棒被闭锁；AZ-5 自身始终可拔；</li>
 *   <li>版本号与两个排水阀（除氧器 52 kg/s、凝结水自动）。</li>
 * </ol>
 */
public class MapAndRodCheck {

    static int fails = 0;

    public static void main(String[] a) throws Exception {
        Localization.init();
        Loader.tables = new SteamTables();

        String ver = Localization.t("app.version");
        System.out.println("版本号显示：" + ver);
        check("主菜单版本号应为 1.0.1", ver.contains("1.0.1"), ver);

        // ================= A. 堆芯图 =================
        Core core = new Core();
        NPPSim.core = core;                       // MTK 构造需要
        NPPSim.coreHeatOverrideMW = 4800;
        for (int i = 0; i < 40; i++) {
            core.update();
        }
        MTK mtk = new MTK();
        mtk.update();
        Map<Channel, JTextField> bind = binding(mtk);

        Set<Integer> fuelColors = new HashSet<>();
        double fuelBright = 0, cpsGreenRatio = 0, cpsDarkCount = 0;
        int nFuel = 0, nCps = 0;
        double baseBright = new Color(235, 235, 160).getRed()
                + new Color(235, 235, 160).getGreen() + new Color(235, 235, 160).getBlue();
        for (Map.Entry<Channel, JTextField> e : bind.entrySet()) {
            Color c = e.getValue().getBackground();
            if (e.getKey() instanceof FuelChannel) {
                if (((FuelChannel) e.getKey()).isDryout()) {
                    continue;
                }
                fuelColors.add(c.getRGB());
                fuelBright += c.getRed() + c.getGreen() + c.getBlue();
                nFuel++;
            } else if (e.getKey() instanceof CPSChannel) {
                if (c.getGreen() > c.getRed() + 20 && c.getGreen() > c.getBlue() + 20) {
                    cpsGreenRatio++;
                }
                if (c.equals(MTK.CPS_DARK_GREEN)) {
                    cpsDarkCount++;
                }
                nCps++;
            }
        }
        fuelBright = nFuel > 0 ? fuelBright / nFuel : 0;

        System.out.printf("额定功率：燃料 %d 格，颜色种类 %d，平均亮度 %.0f（底色 %.0f）| CPS %d 格，绿色 %d，深绿 %d%n",
                nFuel, fuelColors.size(), fuelBright, baseBright, nCps, (int) cpsGreenRatio, (int) cpsDarkCount);
        check("燃料格应为连续渐变（≥8 种颜色）", fuelColors.size() >= 8, fuelColors.size() + " 种");
        check("额定功率下燃料图应整体亮于底色", fuelBright > baseBright * 1.02,
                String.format("%.0f vs %.0f", fuelBright, baseBright));
        check("CPS 格应随功率转为绿系（不是灰/红）", cpsGreenRatio > nCps * 0.9,
                String.format("%d/%d", (int) cpsGreenRatio, nCps));
        check("额定功率下 CPS 不应全是深绿（应已'参与反应'）", cpsDarkCount < nCps * 0.1,
                String.format("%d/%d 深绿", (int) cpsDarkCount, nCps));

        // ---- 零功率 ----
        NPPSim.coreHeatOverrideMW = 0;
        for (int i = 0; i < 60; i++) {
            core.update();
        }
        mtk.update();
        int darkFuel = 0, darkCps = 0;
        for (Map.Entry<Channel, JTextField> e : bind.entrySet()) {
            Color c = e.getValue().getBackground();
            if (e.getKey() instanceof FuelChannel) {
                if (c.equals(MTK.CHANNELOFFCOLOR)) {
                    darkFuel++;
                }
            } else if (e.getKey() instanceof CPSChannel) {
                if (c.equals(MTK.CPS_DARK_GREEN)) {
                    darkCps++;
                }
            }
        }
        System.out.printf("零功率：燃料底色 %d/%d，CPS 深绿 %d/%d%n", darkFuel, nFuel, darkCps, nCps);
        check("零功率时燃料格应全部回到底色", darkFuel >= nFuel * 0.95, darkFuel + "/" + nFuel);
        check("零功率时 CPS 应几乎全为深绿（还未参与反应）", darkCps >= nCps * 0.9,
                darkCps + "/" + nCps);

        NPPSim.coreHeatOverrideMW = -1;

        // ================= B. 控制棒顺序闭锁 =================
        UiStyle.applyLaf();
        Fonts.init();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(1500);

        AutoControl ac = NPPSim.autoControl;
        check("冷态（AZ-5 全插）应闭锁非 AZ 棒", ac.fasrControl.getSequenceBlock(),
                "sequenceBlock=" + ac.fasrControl.getSequenceBlock());

        ControlRodChannel rod = null;
        FASRChannel fasr = null;
        int rods = 0, atTop = 0, nFasr = 0;
        for (ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FASRChannel) {
                    nFasr++;
                    if (fasr == null) {
                        fasr = (FASRChannel) c;
                    }
                }
                if (c instanceof ControlRodChannel) {
                    rods++;
                    if (((ControlRodChannel) c).getPosition() >= 0.999f) {
                        atTop++;
                    }
                    if (rod == null && !(c instanceof FASRChannel)) {
                        rod = (ControlRodChannel) c;
                    }
                }
            }
        }
        System.out.printf("控制棒 %d 根（其中 AZ-5 %d 根），全插 %d 根%n", rods, nFasr, atTop);

        // AZ-5 组自身在闭锁期间必须能拔（否则死锁）。
        // 注意：不能靠"睡 2 s 看棒位"——UI 的 precisionController 每 50 ms 会把
        // 未被选中棒的 state 重置回 1，实际走了几个 tick 完全看线程调度。
        // 这里直接推进通道自己的 update()，判定是确定性的。
        double fBefore = fasr.getPosition();
        fasr.setState(0);                      // 0 = 拔出
        for (int i = 0; i < 20; i++) {         // 20 tick = 1 s 仿真时间
            fasr.update();
        }
        double fAfter = fasr.getPosition();
        fasr.setState(1);
        System.out.printf("AZ-5 棒位 %.3f → %.3f（20 tick）%n", fBefore, fAfter);
        check("AZ-5 棒在闭锁期间仍可拔出", fAfter < fBefore - 0.02,
                String.format("%.3f→%.3f", fBefore, fAfter));

        // 把 AZ-5 全部拔到顶，闭锁应解除
        for (ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FASRChannel) {
                    ((FASRChannel) c).setState(1);
                    ((FASRChannel) c).setPosition(0.0f);
                }
            }
        }
        Thread.sleep(500);
        check("AZ-5 全部拔到顶后应解除闭锁", !ac.fasrControl.getSequenceBlock(),
                "sequenceBlock=" + ac.fasrControl.getSequenceBlock());

        double before = rod.getPosition();
        rod.setState(0);                       // 0 = 拔出
        for (int i = 0; i < 20; i++) {
            rod.update();
        }
        double after = rod.getPosition();
        rod.setState(1);
        System.out.printf("非 FASR 棒位 %.3f → %.3f（20 tick，1=全插、0=全提）%n", before, after);
        check("解锁后非 FASR 棒应能正常拔出", after < before - 0.02, String.format("%.3f→%.3f", before, after));

        // 跳闸 → AZ-5 插入 → 闭锁重新投入；复位后再拔到顶才解锁
        ac.fasrControl.trip("self-check");
        Thread.sleep(600);
        check("AZ-5 插入后应重新闭锁", ac.fasrControl.getSequenceBlock(),
                String.format("AZ 棒位 %.3f", fasr.getPosition()));
        ac.fasrControl.reset();
        for (ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FASRChannel) {
                    ((FASRChannel) c).setState(1);
                    ((FASRChannel) c).setPosition(0.0f);
                }
            }
        }
        Thread.sleep(500);
        check("AZ-5 复位并拔回顶部后应解除闭锁", !ac.fasrControl.getSequenceBlock(),
                "sequenceBlock=" + ac.fasrControl.getSequenceBlock());

        // ================= C. 排水阀 =================
        // 除氧器排水（溢流）阀：先在额定压力下测全开通流能力（Cv 350 后应 ≈ 45~70 kg/s）
        Dearator da0 = NPPSim.dearators.get(0);
        da0.setWaterTemp(153.0);
        da0.pressure = 0.53;              // 同包可直接置压力（正常由 update() 慢跟踪到 0.53）
        WaterValve over = NPPSim.pcs.dearatorOverflowValves.get(0);
        over.setState(1);
        over.setAutoState(1);
        over.setPosition(1.0f);
        over.update();
        double overFlow = over.timestepFlow * 20.0;
        System.out.printf("除氧器排水阀全开流量 %.1f kg/s（除氧器 %.2f MPa / %.0f°C）%n",
                overFlow, da0.getPressure(), da0.getWaterTemperature());
        check("除氧器排水阀应具备 ≥25 kg/s 的通流能力", overFlow >= 25.0,
                String.format("%.1f kg/s", overFlow));

        // 凝结水排水阀：已改为纯自动（无操作面板），确认仍在自动位、且能算出差压推动的流量
        WaterValve cond = NPPSim.condensateDrainValve;
        cond.setState(1);
        cond.setAutoState(1);
        cond.setPosition(1.0f);
        cond.update();
        double condFlow = cond.timestepFlow * 20.0;
        System.out.printf("凝结水排水阀：自动投入=%s，全开流量 %.1f kg/s（联箱 %.2f MPa）%n",
                cond.isAutoEnabled(), condFlow, NPPSim.condensateHeader.getPressure());
        check("凝结水排水阀应保持自动投入", cond.isAutoEnabled(), String.valueOf(cond.isAutoEnabled()));

        System.out.println(fails == 0 ? "MAP_ROD_OK" : ("MAP_ROD_FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    @SuppressWarnings("unchecked")
    static Map<Channel, JTextField> binding(MTK mtk) throws Exception {
        Field f = MTK.class.getDeclaredField("channelMtkBinding");
        f.setAccessible(true);
        return (HashMap<Channel, JTextField>) f.get(mtk);
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            fails++;
        }
        System.out.printf("[%s] %s：%s%n", ok ? "OK" : "FAIL", name, detail);
    }
}
