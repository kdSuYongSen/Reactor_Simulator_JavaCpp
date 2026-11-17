package com.darwish.nppsim;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Q1 钥匙锁自检（2026-11-14）：锁定 → 解锁 两态截图。 */
public class KeyProbe {

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

        final GridSystemUI[] r = new GridSystemUI[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            r[0] = new GridSystemUI();
            r[0].setVisible(true);
        });
        Thread.sleep(1200);

        System.out.println("锁定状态: " + NPPSim.grid.isGridBreakerLocked());
        shot(r[0], "target/key_locked.png");

        NPPSim.grid.toggleGridBreakerLock();
        javax.swing.SwingUtilities.invokeAndWait(() -> r[0].repaint());
        Thread.sleep(600);
        System.out.println("扭钥匙后: " + NPPSim.grid.isGridBreakerLocked());
        shot(r[0], "target/key_unlocked.png");

        // 解锁后应该可以操作 Q1：直接走模型接口验证联锁是否只拦界面
        NPPSim.grid.gridBreaker = false;
        NPPSim.grid.update();
        System.out.println("解锁后 Q1 分闸 → 750 母线带电=" + NPPSim.grid.has750());
        System.exit(0);
    }

    private static void shot(GridSystemUI f, String path) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                java.awt.Container cp = f.getContentPane();
                cp.doLayout();
                BufferedImage img = new BufferedImage(cp.getWidth(), cp.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                cp.paint(g);
                g.dispose();
                ImageIO.write(img, "png", new File(path));
                // 只截钥匙面板那一块，方便核对
                BufferedImage crop = img.getSubimage(Math.max(0, cp.getWidth() - 260), 0,
                        Math.min(260, cp.getWidth()), Math.min(112, cp.getHeight()));
                ImageIO.write(crop, "png", new File(path.replace(".png", "_crop.png")));
                System.out.println("截图: " + path);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }
}
