package com.darwish.nppsim;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 电网窗口"失电状态"截图（2026-11-13）：验证红色指示灯与分闸符号。 */
public class GridDeadShot {

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
        Thread.sleep(600);

        // 厂用 A 失电 + 应急 A 失电 + 380 V 失电（B 段保持正常，便于对比红/绿）
        NPPSim.grid.unitBreakerA = false;
        NPPSim.grid.reserveBreakerA = false;
        NPPSim.grid.emergencyBreakerA = false;
        NPPSim.grid.dieselAutoStart = false;
        long target = NPPSim.totalTicks + 120;
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
        Thread.sleep(700);
        javax.swing.SwingUtilities.invokeAndWait(() -> ref[0].update());
        Thread.sleep(400);

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
                File out = new File("target/grid_dead_shot.png");
                ImageIO.write(img, "png", out);
                System.out.println("截图: " + out.getAbsolutePath() + " "
                        + img.getWidth() + "x" + img.getHeight());
                f.dispose();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
        System.exit(0);
    }
}
