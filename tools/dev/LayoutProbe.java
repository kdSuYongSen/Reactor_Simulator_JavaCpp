package com.darwish.nppsim;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 布局工具自检（2026-11-13）：外部 layout.json 能否移动/缩放窗口里的面板。 */
public class LayoutProbe {

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

        final GridSystemUI[] ref = new GridSystemUI[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ref[0] = new GridSystemUI();
            ref[0].setVisible(true);
        });
        // 轮询器每秒检查一次 layout.json：等它把新布局应用上去（最多等 8 s）
        for (int i = 0; i < 16; i++) {
            Thread.sleep(500);
            final boolean[] changed = new boolean[1];
            javax.swing.SwingUtilities.invokeAndWait(() ->
                    changed[0] = ref[0].getContentPane().getLayout() == null);
            if (changed[0]) {
                break;
            }
        }

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            System.out.println("== 应用布局后的面板位置 ==");
            for (java.awt.Component c : ref[0].getContentPane().getComponents()) {
                System.out.println("   " + c.getClass().getSimpleName() + " " + c.getBounds());
            }
            ref[0].getContentPane().doLayout();
            try {
                BufferedImage img = new BufferedImage(ref[0].getContentPane().getWidth(),
                        ref[0].getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                ref[0].getContentPane().paint(g);
                g.dispose();
                File out = new File("target/grid_layout_shot.png");
                ImageIO.write(img, "png", out);
                System.out.println("截图: " + out.getAbsolutePath());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            ref[0].dispose();
        });
        System.exit(0);
    }
}
