package com.darwish.nppsim;

import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 给水窗口截图（2026-11-04）：确认排水阀面板已从界面上消失。 */
public class FeedwaterShot {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(1500);
        final FeedwaterUI[] holder = new FeedwaterUI[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            FeedwaterUI fw = new FeedwaterUI();
            fw.setSize(1180, 760);
            fw.setVisible(true);
            holder[0] = fw;
        });
        Thread.sleep(1500);
        Window target = holder[0];
        if (target == null) {
            System.out.println("SHOT_FAIL 没有给水窗口");
            System.exit(1);
        }
        target.toFront();
        Thread.sleep(1200);
        BufferedImage img = new BufferedImage(target.getWidth(), target.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        target.printAll(img.getGraphics());
        File out = new File("target/feedwater.png");
        ImageIO.write(img, "png", out);
        System.out.println("saved " + out.getAbsolutePath() + " " + img.getWidth() + "x" + img.getHeight());
        System.exit(0);
    }
}
