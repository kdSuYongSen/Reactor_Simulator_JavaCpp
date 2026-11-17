package com.darwish.nppsim;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 电网窗口截图（2026-11-12）：确认单线图排版与实时数值。 */
public class GridShot {

    public static void main(String[] a) throws Exception {
        Localization.init();
        Localization.setLanguage(Language.ZH_CN);
        UiStyle.applyLaf();
        Fonts.init();
        UiTheme.install();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        while (NPPSim.totalTicks < 40) {
            Thread.sleep(10);
        }
        StandardIC.apply();
        long target = NPPSim.totalTicks + 300;
        while (NPPSim.totalTicks < target) {
            Thread.sleep(10);
        }

        final GridSystemUI[] ref = new GridSystemUI[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ref[0] = new GridSystemUI();
            ref[0].setVisible(true);
            ref[0].validate();
            ref[0].doLayout();
        });
        Thread.sleep(800);
        javax.swing.SwingUtilities.invokeAndWait(() -> ref[0].update());
        Thread.sleep(600);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                ref[0].setSize(ref[0].getWidth(), ref[0].getHeight() + 1);
                ref[0].validate();
                GridSystemUI f = ref[0];
                BufferedImage img = new BufferedImage(f.getContentPane().getWidth(),
                        f.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                f.getContentPane().doLayout();
                f.getContentPane().paint(g);
                g.dispose();
                File out = new File("target/grid_shot.png");
                ImageIO.write(img, "png", out);
                System.out.println("截图: " + out.getAbsolutePath() + "  " + img.getWidth() + "x" + img.getHeight());
                f.dispose();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        System.exit(0);
    }
}
