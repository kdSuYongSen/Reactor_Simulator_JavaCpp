package com.darwish.nppsim;

/** 汽包水位表的刻度/红区/量程核对（2026-11-05）。 */
public class DrumGaugeCheck {
    public static void main(String[] a) throws Exception {
        Localization.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(300);
        final FeedwaterUI[] h = new FeedwaterUI[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            FeedwaterUI fw = new FeedwaterUI();
            fw.setSize(1180, 760);
            h[0] = fw;
        });
        FeedwaterUI ui = h[0];
        for (String f : new String[] {"drumLevel1", "drumLevel2"}) {
            java.lang.reflect.Field fld = FeedwaterUI.class.getDeclaredField(f);
            fld.setAccessible(true);
            Object g = fld.get(ui);
            java.lang.reflect.Method mn = g.getClass().getMethod("getMinValue");
            java.lang.reflect.Method mx = g.getClass().getMethod("getMaxValue");
            java.lang.reflect.Method gs = g.getClass().getMethod("getTickmarkSections");
            java.util.List<?> secs = (java.util.List<?>) gs.invoke(g);
            StringBuilder sb = new StringBuilder();
            for (Object s : secs) {
                java.lang.reflect.Method st = s.getClass().getMethod("getStart");
                java.lang.reflect.Method sp = s.getClass().getMethod("getStop");
                sb.append(String.format("[%.0f..%.0f]", (Double) st.invoke(s), (Double) sp.invoke(s)));
            }
            java.lang.reflect.Method tv = g.getClass().getMethod("isTickmarkSectionsVisible");
            System.out.println(f + " 量程 " + mn.invoke(g) + " … " + mx.invoke(g)
                    + " 刻度红区=" + (sb.length() == 0 ? "无" : sb.toString())
                    + " 可见=" + tv.invoke(g));
        }
        // 水位换算核对
        MCC.SeparatorDrum d = NPPSim.mcc.drum1;
        System.out.printf("汽包1 水 %.1f t 水位 %+.1f%% 汽 %.2f t 汽空间 %.0f m³ 压力 %.2f MPa%n",
                d.getWaterMass() / 1000.0, d.getWaterLevel(), d.getSteamMass() / 1000.0,
                d.getSteamVolume(), d.getPressure());
        System.out.println("换算校验：174t→" + Math.round(d.levelFromMass(174000))
                + "% 220t→" + Math.round(d.levelFromMass(220000))
                + "% 285t→" + Math.round(d.levelFromMass(285000))
                + "% 440t→" + Math.round(d.levelFromMass(440000)) + "%");
        System.exit(0);
    }
}
