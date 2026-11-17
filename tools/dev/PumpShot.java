package com.darwish.nppsim;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 泵窗口截图（2026-11-15）：看 ON/OFF+Led 是否已换成槽式拨杆。
 *
 * <p>参数 1 = 窗口名（MCP / FW / COND），默认 MCP。</p>
 */
public class PumpShot {

    public static void main(String[] a) throws Exception {
        String which = a.length > 0 ? a[0] : "MCP";
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
        Thread.sleep(400);


        javax.swing.JFrame[] ref = new javax.swing.JFrame[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ref[0] = "FW".equals(which) ? new FeedwaterUI()
                    : ("COND".equals(which) ? new CondensateUI() : new MCPUI());
            ref[0].setVisible(true);
            ref[0].validate();
        });
        Thread.sleep(1500);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ref[0].setSize(ref[0].getWidth(), ref[0].getHeight() + 1);
            ref[0].validate();
            ref[0].getContentPane().doLayout();
        });
        Thread.sleep(600);

        // 停一台泵，验证手柄会跟着转回 0
        try {
            if ("MCP".equals(which)) {
                NPPSim.mcc.mcp.get(0).setActive(false);
            }
        } catch (RuntimeException ignored) {
        }
        Thread.sleep(900);

        // 泵面板在滚动区下方：先滚到它再截图
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            java.awt.Container root = ref[0].getContentPane();
            javax.swing.JScrollPane sp = findScroll(root);
            RotarySwitch first = findSwitch(root);
            if (sp != null && first != null) {
                java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(
                        first.getParent().getParent(), first.getParent().getBounds(),
                        sp.getViewport().getView());
                sp.getViewport().scrollRectToVisible(r);
            }
        });
        Thread.sleep(500);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                java.awt.Container cp = ref[0].getContentPane();
                BufferedImage img = new BufferedImage(cp.getWidth(), cp.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                cp.paint(g);
                g.dispose();
                File out = new File("target/pump_" + which.toLowerCase() + ".png");
                ImageIO.write(img, "png", out);
                System.out.println("截图: " + out.getAbsolutePath() + " "
                        + img.getWidth() + "x" + img.getHeight());
                ref[0].dispose();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
        System.exit(0);
    }

    private static javax.swing.JScrollPane findScroll(java.awt.Container c) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof javax.swing.JScrollPane) {
                return (javax.swing.JScrollPane) k;
            }
            if (k instanceof java.awt.Container) {
                javax.swing.JScrollPane r = findScroll((java.awt.Container) k);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static RotarySwitch findSwitch(java.awt.Container c) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof RotarySwitch) {
                return (RotarySwitch) k;
            }
            if (k instanceof java.awt.Container) {
                RotarySwitch r = findSwitch((java.awt.Container) k);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
