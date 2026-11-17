package com.darwish.nppsim;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** 除氧器窗口截图 + 水位条 track 参数（2026-11-04）。 */
public class DearatorShot {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(800);
        final DearatorUI[] holder = new DearatorUI[1];
        SwingUtilities.invokeAndWait(() -> {
            DearatorUI ui = new DearatorUI();
            ui.setSize(1180, 760);
            ui.setVisible(true);
            holder[0] = ui;
        });
        Thread.sleep(1500);
        DearatorUI ui = holder[0];

        for (String f : new String[]{"level1", "level2", "level3", "level4"}) {
            java.lang.reflect.Field fld = DearatorUI.class.getDeclaredField(f);
            fld.setAccessible(true);
            Object g = fld.get(ui);
            java.lang.reflect.Method gts = g.getClass().getMethod("getTrackStart");
            java.lang.reflect.Method gtst = g.getClass().getMethod("getTrackStop");
            java.lang.reflect.Method gmin = g.getClass().getMethod("getMinValue");
            java.lang.reflect.Method gmax = g.getClass().getMethod("getMaxValue");
            System.out.printf("%s track %.3f..%.3f  min %.1f max %.1f%n", f,
                    (Double) gts.invoke(g), (Double) gtst.invoke(g),
                    (Double) gmin.invoke(g), (Double) gmax.invoke(g));
        }

        BufferedImage img = new BufferedImage(ui.getWidth(), ui.getHeight(), BufferedImage.TYPE_INT_RGB);
        ui.printAll(img.getGraphics());
        File out = new File("target/dearator.png");
        ImageIO.write(img, "png", out);
        System.out.println("saved " + out.getAbsolutePath() + " " + img.getWidth() + "x" + img.getHeight());
        System.exit(0);
    }
}
