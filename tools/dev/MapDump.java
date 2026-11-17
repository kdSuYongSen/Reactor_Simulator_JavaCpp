package com.darwish.nppsim;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JTextField;

/**
 * 把堆芯图当前上色结果打成 ASCII，肉眼核对空间分布（2026-11-02）。
 *
 * <p>字符：燃料格按亮度 空格 → . → : → * → # → @（越亮越靠后）；CPS 格用 G（绿系）、
 * g（偏灰的绿）；干涸格用 X。</p>
 */
public class MapDump {

    public static void main(String[] a) throws Exception {
        Localization.init();
        Loader.tables = new SteamTables();
        Core core = new Core();
        NPPSim.core = core;

        double mw = a.length > 0 ? Double.parseDouble(a[0]) : 4800;
        NPPSim.coreHeatOverrideMW = mw;
        for (int i = 0; i < 40; i++) {
            core.update();
        }
        MTK mtk = new MTK();
        mtk.update();

        Field f = MTK.class.getDeclaredField("channelMtkBinding");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Channel, JTextField> bind = (HashMap<Channel, JTextField>) f.get(mtk);

        StringBuilder sb = new StringBuilder();
        int lit = 0, green = 0;
        for (int row = 0; row < 50; row++) {
            for (int i = 50; i > 0; i--) {
                Channel ch = core.coreArray.get(50 - row + 2).get(i + 2);
                JTextField cell = bind.get(ch);
                if (cell == null) {
                    sb.append(' ');
                    continue;
                }
                Color c = cell.getBackground();
                if (ch instanceof CPSChannel) {
                    green++;
                    sb.append(c.getRed() > 120 && Math.abs(c.getRed() - c.getBlue()) < 40 ? 'g' : 'G');
                } else if (ch instanceof FuelChannel && ((FuelChannel) ch).isDryout()) {
                    sb.append('X');
                } else {
                    // 底色是淡黄(235,235,160)，往白走主要变的是蓝通道，用蓝通道分档才看得出渐变
                    int b = c.getBlue();
                    if (b > 250) {
                        lit++;
                    }
                    if (b > 245) sb.append('@');
                    else if (b > 230) sb.append('#');
                    else if (b > 215) sb.append('*');
                    else if (b > 195) sb.append(':');
                    else if (b > 175) sb.append('o');
                    else sb.append('.');
                }
            }
            sb.append('\n');
        }
        System.out.println(sb);
        System.out.printf("%.0f MW：近白格 %d，CPS 绿格 %d%n", mw, lit, green);
        System.exit(0);
    }
}
