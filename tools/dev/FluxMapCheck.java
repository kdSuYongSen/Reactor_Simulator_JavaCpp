package com.darwish.nppsim;

/**
 * 中子通量图（MTK）数据源自检 —— 不启动界面，直接复刻 MTK 的 50×50 视窗统计逻辑。
 *
 * <p>原始 RBMK-1500-Simulator.jar 的 MTK 上色规则（反编译核对）：
 * 分别对燃料通道与 CPS 通道求平均值，正极性下 通量 &gt; 平均×(1+阈值) 高亮，
 * 负极性下 通量 &lt; 平均×(1-阈值) 高亮；阈值默认 0.15。</p>
 *
 * <p>本检查验证：① 额定功率下有合理比例的燃料通道被点亮；
 * ② CPS 通道同样有明暗分布（原版靠邻域中子扩散，点堆内核必须显式下发）；
 * ③ 正/负极性高亮数量互补；④ 零功率时全灭且不出现 NaN。</p>
 */
public class FluxMapCheck {

    static int fails = 0;

    public static void main(String[] a) throws Exception {
        Localization.init();
        Loader.tables = new SteamTables();
        Core core = new Core();
        try {
            NPPSim.coreHeatOverrideMW = 4800;
            for (int i = 0; i < 40; i++) {
                core.update();
            }
            Snapshot s = snapshot(core, 0.15);
            System.out.printf("额定 4800 MW：燃料 %d 格，均值 %.3f，点亮 %d（%.1f%%）| CPS %d 格，均值 %.3f，点亮 %d（%.1f%%）%n",
                    s.nFuel, s.meanFuel, s.litFuelPos, 100.0 * s.litFuelPos / Math.max(1, s.nFuel),
                    s.nCps, s.meanCps, s.litCpsPos, 100.0 * s.litCpsPos / Math.max(1, s.nCps));

            check("燃料通道应有 5%~60% 被点亮（中心高通量区）",
                    s.litFuelPos > s.nFuel * 0.05 && s.litFuelPos < s.nFuel * 0.60,
                    String.format("%.1f%%", 100.0 * s.litFuelPos / Math.max(1, s.nFuel)));
            check("CPS 通道不应整层恒暗（>3% 点亮）",
                    s.litCpsPos > s.nCps * 0.03,
                    String.format("%.1f%%", 100.0 * s.litCpsPos / Math.max(1, s.nCps)));
            check("CPS 均值应与燃料均值同量级（0.7~1.4）",
                    s.meanCps > 0.7 && s.meanCps < 1.4,
                    String.format("%.3f", s.meanCps));
            check("负极性点亮数应远多于正极性（低通量区更大）",
                    s.litFuelNeg > s.litFuelPos,
                    String.format("正 %d / 负 %d", s.litFuelPos, s.litFuelNeg));

            // ---- 零功率 ----
            NPPSim.coreHeatOverrideMW = 0;
            for (int i = 0; i < 20; i++) {
                core.update();
            }
            Snapshot z = snapshot(core, 0.15);
            System.out.printf("零功率：燃料均值 %.4f 点亮 %d | CPS 均值 %.4f 点亮 %d%n",
                    z.meanFuel, z.litFuelPos, z.meanCps, z.litCpsPos);
            check("零功率时两点亮（阈值比较不成立）",
                    z.litFuelPos == 0 && z.litCpsPos == 0,
                    String.format("燃料 %d / CPS %d", z.litFuelPos, z.litCpsPos));
            check("零功率时不出现 NaN",
                    !Double.isNaN(z.meanFuel) && !Double.isNaN(z.meanCps),
                    String.format("%.4f / %.4f", z.meanFuel, z.meanCps));
        } finally {
            NPPSim.coreHeatOverrideMW = -1;
        }

        System.out.println(fails == 0 ? "FLUX_MAP_OK" : ("FLUX_MAP_FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    static class Snapshot {
        int nFuel, nCps, litFuelPos, litCpsPos, litFuelNeg;
        double meanFuel, meanCps;
    }

    /** 复刻 MTK.createRow/update 的 50×50 视窗与统计口径（不建界面） */
    static Snapshot snapshot(Core core, double threshold) {
        Snapshot s = new Snapshot();
        java.util.ArrayList<Channel> fuel = new java.util.ArrayList<>();
        java.util.ArrayList<Channel> cps = new java.util.ArrayList<>();
        for (int row = 0; row < 50; row++) {
            for (int i = 50; i > 0; i--) {
                Channel ch = core.coreArray.get(50 - row + 2).get(i + 2);
                if (ch instanceof FuelChannel) {
                    fuel.add(ch);
                } else if (ch instanceof CPSChannel) {
                    cps.add(ch);
                }
            }
        }
        s.nFuel = fuel.size();
        s.nCps = cps.size();
        double sf = 0, sc = 0;
        for (Channel c : fuel) {
            sf += c.getFastNeutronCount();
        }
        for (Channel c : cps) {
            sc += c.getFastNeutronCount();
        }
        s.meanFuel = s.nFuel > 0 ? sf / s.nFuel : 0.0;
        s.meanCps = s.nCps > 0 ? sc / s.nCps : 0.0;
        for (Channel c : fuel) {
            double f = c.getFastNeutronCount();
            if (f > s.meanFuel * (1.0 + threshold)) {
                s.litFuelPos++;
            }
            if (f < s.meanFuel * (1.0 - threshold)) {
                s.litFuelNeg++;
            }
        }
        for (Channel c : cps) {
            if (c.getFastNeutronCount() > s.meanCps * (1.0 + threshold)) {
                s.litCpsPos++;
            }
        }
        return s;
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            fails++;
        }
        System.out.printf("[%s] %s：%s%n", ok ? "OK" : "FAIL", name, detail);
    }
}
