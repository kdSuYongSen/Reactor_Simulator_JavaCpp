package com.darwish.nppsim;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import javax.imageio.ImageIO;

/**
 * 把主控室窗口渲染成 PNG 并打印左上角报警盘的结构（2026-11-03）。
 *
 * <p>用于确认主控室报警盘已按原 jar 还原：GridLayout(3,5)、14 只灯、
 * 汽轮机的报警不再搬进来，而是在汽轮机窗口自己的报警区。</p>
 */
public class MainSnapshot {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(1200);

        UI ui = null;
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof UI && w.isVisible()) {
                ui = (UI) w;
            }
        }
        if (ui == null) {
            System.out.println("没有主控室窗口");
            System.exit(1);
        }
        ui.validate();
        ui.doLayout();
        Thread.sleep(1200);
        ui.doLayout();

        Field f = UI.class.getDeclaredField("annunciatorPanel");
        f.setAccessible(true);
        javax.swing.JPanel panel = (javax.swing.JPanel) f.get(ui);

        System.out.println("LIGHTS=" + panel.getComponentCount()
                + " layout=" + panel.getLayout().getClass().getSimpleName()
                + " panelSize=" + panel.getWidth() + "x" + panel.getHeight());
        for (java.awt.Component c : panel.getComponents()) {
            if (c instanceof javax.swing.JTextField) {
                javax.swing.JTextField t = (javax.swing.JTextField) c;
                System.out.println("  light pref=" + t.getPreferredSize().width + "x" + t.getPreferredSize().height
                        + " font=" + t.getFont().getSize() + " text=" + t.getText());
            }
        }

        BufferedImage img = new BufferedImage(
                Math.max(1, ui.getWidth()), Math.max(1, ui.getHeight()), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        ui.getContentPane().paintAll(g);
        g.dispose();
        File out = new File(a.length > 0 ? a[0] : "target/main.png");
        ImageIO.write(img, "png", out);
        System.out.println("saved " + out.getAbsolutePath() + " " + img.getWidth() + "x" + img.getHeight());
        System.exit(0);
    }
}
