package com.darwish.nppsim;

import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * 主窗口"长按放大 + 可配置热键"自检（2026-11-03）。
 *
 * <p>把热键改成 M，验证主窗口按新键绑定、按下能弹出放大镜、松开能收起。</p>
 */
public class GameZoomCheck {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();

        File f = ZoomSettings.file();
        String backup = f.isFile()
                ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;

        ZoomSettings.clearCache();
        ZoomSettings zs = ZoomSettings.load();
        zs.magnify = KeyEvent.VK_M;
        zs.zoomIn = KeyEvent.VK_EQUALS;
        zs.zoomOut = KeyEvent.VK_MINUS;
        zs.fit = KeyEvent.VK_0;
        zs.loupe = 3.0;
        zs.save();

        new NPPSim();
        Thread.sleep(2000);

        UI ui = null;
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof UI && w.isVisible()) {
                ui = (UI) w;
            }
        }
        if (ui == null) {
            System.out.println("GAMEZOOM_FAIL 没有主控室窗口");
            System.exit(1);
        }

        final UI main = ui;
        javax.swing.InputMap im = ui.getRootPane()
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        boolean bound = im.get(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0)) != null;
        boolean boundRelease = im.get(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0, true)) != null;
        boolean notQ = im.get(KeyStroke.getKeyStroke(KeyEvent.VK_Q, 0)) == null;

        Object onName = im.get(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0));
        Object offName = im.get(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0, true));
        javax.swing.Action on = ui.getRootPane().getActionMap().get(onName);
        javax.swing.Action off = ui.getRootPane().getActionMap().get(offName);
        if (on != null) {
            SwingUtilities.invokeAndWait(() -> on.actionPerformed(
                    new java.awt.event.ActionEvent(main, 0, "test")));
        }
        Thread.sleep(400);
        java.awt.Component gp = ui.getGlassPane();
        boolean shown = gp.isVisible();
        boolean hasShot = false;
        try {
            java.lang.reflect.Method m = gp.getClass().getDeclaredMethod("isLoupeVisible");
            m.setAccessible(true);
            hasShot = (Boolean) m.invoke(gp);
        } catch (Exception ignore) {
            // 拿不到就按"没显示"处理
        }

        if (off != null) {
            SwingUtilities.invokeAndWait(() -> off.actionPerformed(
                    new java.awt.event.ActionEvent(main, 0, "test")));
        }
        Thread.sleep(300);
        boolean hidden = !gp.isVisible();

        /* 按住不放的判定（2026-11-03 晚）：系统自动重复连发 keyPressed 时只执行一次；
         * 按住期间倍率快捷键不响应；松开后 + 才生效。 */
        Object shotBefore = null;
        double factorBefore = 0, factorHeld = 0, factorAfterRelease = 0;
        boolean onceOnly = false, ignoredWhileHeld = false, worksAfterRelease = false;
        try {
            java.lang.reflect.Field shotF = gp.getClass().getDeclaredField("shot");
            shotF.setAccessible(true);
            java.lang.reflect.Field facF = gp.getClass().getDeclaredField("factor");
            facF.setAccessible(true);
            javax.swing.Action zin = ui.getRootPane().getActionMap()
                    .get(im.get(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, 0)));

            if (on != null) {
                SwingUtilities.invokeAndWait(() -> on.actionPerformed(
                        new java.awt.event.ActionEvent(main, 0, "test")));
                Thread.sleep(300);
                shotBefore = shotF.get(gp);
                factorBefore = facF.getDouble(gp);

                // 自动重复的第二次 keyPressed
                SwingUtilities.invokeAndWait(() -> on.actionPerformed(
                        new java.awt.event.ActionEvent(main, 0, "test")));
                onceOnly = shotBefore != null && shotBefore == shotF.get(gp);

                if (zin != null) {
                    SwingUtilities.invokeAndWait(() -> zin.actionPerformed(
                            new java.awt.event.ActionEvent(main, 0, "test")));
                }
                factorHeld = facF.getDouble(gp);
                ignoredWhileHeld = Math.abs(factorHeld - factorBefore) < 1e-6;

                if (off != null) {
                    SwingUtilities.invokeAndWait(() -> off.actionPerformed(
                            new java.awt.event.ActionEvent(main, 0, "test")));
                }
                Thread.sleep(200);
                if (zin != null) {
                    SwingUtilities.invokeAndWait(() -> zin.actionPerformed(
                            new java.awt.event.ActionEvent(main, 0, "test")));
                }
                factorAfterRelease = facF.getDouble(gp);
                worksAfterRelease = factorAfterRelease > factorHeld + 0.01;
                // 倍率还原，别把配置文件改了
                System.out.println("按住倍率=" + factorBefore + " 按住时+无效=" + factorHeld
                        + " 松开后+=" + factorAfterRelease);
                ZoomSettings cur = ZoomSettings.load();
                cur.loupe = factorBefore;
                cur.save();
                ZoomSettings.clearCache();
            }
        } catch (Exception ignore) {
            // 拿不到内部字段就跳过这一段
        }

        System.out.println("M 绑定=" + bound + "/" + boundRelease + "（旧 Q 解除=" + notQ + "）"
                + " 按下=" + shown + "/" + hasShot + " 松开隐藏=" + hidden
                + " 按住只执行一次=" + onceOnly + " 按住忽略其它键=" + ignoredWhileHeld
                + " 松开后可调=" + worksAfterRelease);
        boolean all = bound && boundRelease && notQ && shown && hasShot && hidden
                && onceOnly && ignoredWhileHeld && worksAfterRelease;
        System.out.println(all ? "GAMEZOOM_OK" : "GAMEZOOM_FAIL");

        if (backup != null) {
            Files.write(f.toPath(), backup.getBytes(StandardCharsets.UTF_8));
        } else {
            f.delete();
        }
        ZoomSettings.clearCache();
        System.exit(all ? 0 : 1);
    }
}
