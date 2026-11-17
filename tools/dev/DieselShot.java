package com.darwish.nppsim;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 应急柴油发电机窗口截图（2026-11-12）：两台机手动启动后拍摄。 */
public class DieselShot {

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

        // 手动启动两台柴油机，让转速/温度/油量跑起来
        NPPSim.grid.setDieselRunning(0, true);   // DG-A1
        NPPSim.grid.setDieselRunning(2, true);   // DG-B1
        long target = NPPSim.totalTicks + 500;   // 25 s
        while (NPPSim.totalTicks < target) {
            Thread.sleep(10);
        }

        final EmergencyDieselUI[] ref = new EmergencyDieselUI[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ref[0] = new EmergencyDieselUI();
            ref[0].setVisible(true);
            ref[0].validate();
            ref[0].doLayout();
        });
        Thread.sleep(700);
        javax.swing.SwingUtilities.invokeAndWait(() -> ref[0].update());
        Thread.sleep(500);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                ref[0].setSize(ref[0].getWidth(), ref[0].getHeight() + 1);
                ref[0].validate();
                EmergencyDieselUI f = ref[0];
                BufferedImage img = new BufferedImage(f.getContentPane().getWidth(),
                        f.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                f.getContentPane().doLayout();
                f.getContentPane().paint(g);
                g.dispose();
                File out = new File("target/diesel_shot.png");
                ImageIO.write(img, "png", out);
                System.out.println("截图: " + out.getAbsolutePath() + " " + img.getWidth() + "x" + img.getHeight());
                ElectricalGrid e = NPPSim.grid;
                System.out.printf("A: %.0f rpm / %.2f MW / %.0f %%油 / %.2f Hz / 冷却%.0f°C / 排烟%.0f°C%n",
                        e.getDieselRpm(0), e.getDieselPowerMW(0), e.getDieselFuelPct(0),
                        e.getDieselFreq(0), e.getDieselCoolantC(0), e.getDieselExhaustC(0));
                f.dispose();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
        System.exit(0);
    }
}
