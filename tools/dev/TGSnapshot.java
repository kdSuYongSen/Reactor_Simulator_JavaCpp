package com.darwish.nppsim;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 把"汽轮发电机"窗口画成 PNG（2026-11-03），用于确认新加的"汽轮机保护"面板
 * 是否真的摆在 2 号机组旁边、内容有没有被挤掉。
 *
 * <p>不需要人看屏幕：直接把窗口内容面板 paint 到 BufferedImage。</p>
 */
public class TGSnapshot {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(500);
        // 只解除跳闸，让画面显示"运行中"（不施加整套标准工况：那会重启界面导致截图空白）
        NPPSim.tg1.forceReset();
        NPPSim.tg2.forceReset();
        Thread.sleep(300);

        TGUI f = new TGUI();
        f.setSize(f.getPreferredSize());
        f.validate();
        f.doLayout();
        f.update();                       // 刷新一次保护面板
        Thread.sleep(600);
        f.update();
        Thread.sleep(900);
        f.doLayout();
        Thread.sleep(400);

        System.out.println("TGUI size=" + f.getWidth() + "x" + f.getHeight()
                + " content=" + f.getContentPane().getWidth() + "x" + f.getContentPane().getHeight()
                + " children=" + f.getContentPane().getComponentCount());
        for (java.awt.Component c : f.getContentPane().getComponents()) {
            System.out.println("   child " + c.getClass().getSimpleName()
                    + " " + c.getX() + "," + c.getY() + " " + c.getWidth() + "x" + c.getHeight()
                    + " visible=" + c.isVisible());
        }
        BufferedImage img = new BufferedImage(
                Math.max(1, f.getWidth()), Math.max(1, f.getHeight()), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        f.getContentPane().paintAll(g);
        g.dispose();
        File out = new File(a.length > 0 ? a[0] : "target/tgui.png");
        ImageIO.write(img, "png", out);
        System.out.println("saved " + out.getAbsolutePath() + " " + img.getWidth() + "x" + img.getHeight());
        System.exit(0);
    }
}
