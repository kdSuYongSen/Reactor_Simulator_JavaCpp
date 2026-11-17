package com.darwish.nppsim;

import java.awt.Component;
import java.awt.Container;

/**
 * 窗口组件树导出（2026-11-03）。
 *
 * <p>用来确定"汽轮机窗口"里 1 号/2 号机组分区、以及各面板的实际坐标，
 * 好把新增的汽轮机保护面板摆在 2 号机组旁边，而不是靠猜 GroupLayout。</p>
 */
public class LayoutDump {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(500);
        TGUI tgui = new TGUI();
        tgui.setSize(tgui.getPreferredSize());
        tgui.doLayout();
        Thread.sleep(300);
        tgui.doLayout();
        System.out.println("窗口尺寸 " + tgui.getWidth() + "x" + tgui.getHeight());
        Container cp = tgui.getContentPane();
        dump(cp, 0, 6);
        System.exit(0);
    }

    static void dump(Container c, int depth, int maxDepth) {
        for (Component ch : c.getComponents()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            String txt = "";
            if (ch instanceof javax.swing.AbstractButton) {
                txt = " \"" + ((javax.swing.AbstractButton) ch).getText() + "\"";
            } else if (ch instanceof javax.swing.JLabel) {
                txt = " \"" + ((javax.swing.JLabel) ch).getText() + "\"";
            }
            System.out.printf("%s%-28s %-22s %4d,%4d %4dx%4d%s%n",
                    sb, ch.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(ch)),
                    ch.getName() == null ? "" : ch.getName(),
                    ch.getX(), ch.getY(), ch.getWidth(), ch.getHeight(), txt);
            if (depth + 1 < maxDepth && ch instanceof Container) {
                dump((Container) ch, depth + 1, maxDepth);
            }
        }
    }
}
