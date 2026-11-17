package com.darwish.nppsim;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JScrollPane;

/** 全厂总图截图（2026-11-12）：排版与实时数值自检。 */
public class OverviewShot {

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
        long target = NPPSim.totalTicks + 400;
        while (NPPSim.totalTicks < target) {
            Thread.sleep(10);
        }

        final PlantOverviewUI[] ref = new PlantOverviewUI[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ref[0] = new PlantOverviewUI();
            ref[0].setVisible(true);
        });
        Thread.sleep(900);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                PlantOverviewUI f = ref[0];
                JScrollPane sp = findScroll(f.getContentPane());
                Component view = sp != null ? sp.getViewport().getView() : f.getContentPane();
                BufferedImage img = new BufferedImage(Math.max(1600, view.getWidth()),
                        Math.max(880, view.getHeight()), BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                view.paint(g);
                g.dispose();
                File out = new File("target/overview_shot.png");
                ImageIO.write(img, "png", out);
                System.out.println("截图: " + out.getAbsolutePath() + " " + img.getWidth() + "x" + img.getHeight());
                f.dispose();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        System.exit(0);
    }

    private static JScrollPane findScroll(Container c) {
        for (Component x : c.getComponents()) {
            if (x instanceof JScrollPane) {
                return (JScrollPane) x;
            }
            if (x instanceof Container) {
                JScrollPane r = findScroll((Container) x);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
