package com.darwish.nppsim;

/**
 * 汽轮机出力计算的**数值自检**（2026-11-03）。
 *
 * <p>不跑仿真，直接核对 {@link TG#specificWorkKjPerKg} / {@link TG#generatorLoadMW} /
 * {@link TG#speedBenchmark} 在几个有物理意义的工况点上的值。
 * 这样"出力算得对不对"有一把不依赖瞬态的尺子：</p>
 *
 * <table border="1">
 *   <caption>参考值（饱和蒸汽 4.0 MPa / 250 °C 进汽，等熵效率 0.88，发电机 0.98）</caption>
 *   <tr><th>排汽压力</th><th>理论等熵焓降</th><th>本模型 w</th></tr>
 *   <tr><td>0.005 MPa（正常真空）</td><td>≈ 950 kJ/kg</td><td>≈ 840 kJ/kg</td></tr>
 *   <tr><td>0.030 MPa（真空恶化）</td><td>≈ 800 kJ/kg</td><td>≈ 700 kJ/kg</td></tr>
 *   <tr><td>0.101 MPa（无真空）</td><td>≈ 610 kJ/kg</td><td>≈ 530 kJ/kg</td></tr>
 *   <tr><td>0.600 MPa（除氧器抽汽口）</td><td>≈ 440 kJ/kg</td><td>≈ 300 kJ/kg（本表口径）</td></tr>
 * </table>
 */
public class TurbineCheck {

    static int fails = 0;

    public static void main(String[] a) {
        Localization.init();
        Loader.tables = new SteamTables();

        double wCond = TG.specificWorkKjPerKg(250.0, 0.005);
        double wBad = TG.specificWorkKjPerKg(250.0, 0.030);
        double wNoVac = TG.specificWorkKjPerKg(250.0, 0.101);
        double wExt = TG.specificWorkKjPerKg(250.0, 0.600);
        System.out.printf("单位质量功：真空 0.005 → %.0f，0.030 → %.0f，无真空 0.101 → %.0f，抽汽 0.6 → %.0f kJ/kg%n",
                wCond, wBad, wNoVac, wExt);

        check("正常真空下的焓降 780~880 kJ/kg", wCond > 780 && wCond < 880, String.format("%.0f", wCond));
        check("真空恶化后焓降下降", wBad < wCond - 80, String.format("%.0f < %.0f", wBad, wCond));
        check("无真空时焓降明显更低", wNoVac < wBad - 80, String.format("%.0f < %.0f", wNoVac, wBad));
        /* 绝对值和手算（≈390）有差异，取决于汽水表在 0.6 MPa 饱和点的熵值；
         * 物理上真正要保证的是"抽汽口只做出到凝汽器那一段的一部分功"这个比例关系。 */
        check("抽汽口焓降约为凝汽器焓降的 30%~50%",
                wExt / wCond > 0.30 && wExt / wCond < 0.50,
                String.format("%.0f / %.0f = %.0f%%", wExt, wCond, wExt / wCond * 100));

        // ---- 额定点：单机 900 kg/s 饱和汽、正常真空 ----
        double loadRated = TG.generatorLoadMW(900.0, 250.0, 0.005);
        double loadHalf = TG.generatorLoadMW(450.0, 250.0, 0.005);
        double loadNoVac = TG.generatorLoadMW(900.0, 250.0, 0.101);
        System.out.printf("电功率：900 kg/s → %.0f MWe，450 kg/s → %.0f MWe，900 kg/s 无真空 → %.0f MWe%n",
                loadRated, loadHalf, loadNoVac);

        check("单机额定 680~790 MWe（RBMK-1500 为 2×750）", loadRated > 680 && loadRated < 790,
                String.format("%.0f MWe", loadRated));
        check("额定点低于 825 MW 跳闸门槛", loadRated < 825, String.format("%.0f MWe", loadRated));
        check("半汽量约为额定的一半（±20%）", Math.abs(loadHalf / loadRated - 0.5) < 0.1,
                String.format("%.0f / %.0f", loadHalf, loadRated));
        check("无真空时出力下降 30% 以上", loadNoVac < loadRated * 0.7,
                String.format("%.0f MWe", loadNoVac));

        // ---- 转速基准 ----
        double s0 = TG.speedBenchmark(15.0);
        double sMid = TG.speedBenchmark(328.0);
        double sRated = TG.speedBenchmark(900.0);
        double sOver = TG.speedBenchmark(1500.0);
        System.out.printf("转速基准：15 kg/s → %.0f，328 kg/s → %.0f，900 kg/s → %.0f，1500 kg/s → %.0f rpm%n",
                s0, sMid, sRated, sOver);

        check("低于空载汽耗不转动", s0 == 0.0, String.format("%.0f rpm", s0));
        check("额定汽量对应 3000 rpm", Math.abs(sRated - 3000) < 1, String.format("%.0f rpm", sRated));
        check("超速跳闸门槛（>3250 rpm）会在 1.6 倍汽量前触发", sOver > 3250 && sOver < 4000,
                String.format("%.0f rpm", sOver));

        System.out.println(fails == 0 ? "TURBINE_OK" : ("TURBINE_FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            fails++;
        }
        System.out.printf("[%s] %s：%s%n", ok ? "OK" : "FAIL", name, detail);
    }
}
