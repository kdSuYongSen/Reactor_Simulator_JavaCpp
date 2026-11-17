package com.darwish.nppsim;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 转动开关自检（2026-11-14）：关掉 DG-A1 的专用油泵 A，看旋钮指针是否从 I 转回 0。 */
public class KnobProbe {

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
        NPPSim.grid.setDieselRunning(0, true);      // DG-A1 起来，便于看灯

        final EmergencyDieselUI[] r = new EmergencyDieselUI[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            r[0] = new EmergencyDieselUI();
            r[0].setVisible(true);
        });
        // 直接 new 出来的窗口不在主循环的 elementsToUpdate 里，这里手动按 40 ms 刷新
        drive(r[0], 40);
        dump(r[0], "target/knob_I.png");

        // 关掉泵 A：指针应转到 0，红灯亮
        NPPSim.grid.setLubePump(0, false);
        drive(r[0], 40);
        dump(r[0], "target/knob_0.png");

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            java.util.List<RotarySwitch> rs = new java.util.ArrayList<>();
            collect(r[0].getContentPane(), rs);
            for (int i = 0; i < rs.size(); i++) {
                System.out.println("旋钮 " + i + ": 角度=" + String.format("%.1f", rs.get(i).shownAngleDeg())
                        + "  转动中=" + rs.get(i).isTurning() + "  isOn=" + rs.get(i).isOn());
            }
        });
        System.out.println("泵A 状态=" + NPPSim.grid.isLubePumpOn(0)
                + "  油泵台数=" + NPPSim.grid.getDieselLubePumpCount(0)
                + "  油压=" + String.format("%.2f", NPPSim.grid.getDieselLubePress(0)));
        System.exit(0);
    }

    /** 手动推进界面刷新 n 次（每次 40 ms） */
    private static void drive(EmergencyDieselUI f, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            f.update();
            Thread.sleep(40);
        }
    }

    private static void collect(java.awt.Container c, java.util.List<RotarySwitch> out) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof RotarySwitch) {
                out.add((RotarySwitch) k);
            }
            if (k instanceof java.awt.Container) {
                collect((java.awt.Container) k, out);
            }
        }
    }

    private static void dump(EmergencyDieselUI f, String path) throws Exception {
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
                System.out.println("截图: " + path);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }
}
