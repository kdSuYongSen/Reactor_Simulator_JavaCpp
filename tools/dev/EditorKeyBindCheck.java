package com.darwish.nppsim.layeditor;

import java.awt.event.KeyEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * 编辑器"热键可配置"自检（2026-11-03）。
 *
 * <p>把配置改成 M 长按放大 / 3.0 倍，然后：
 * 编辑器是否按新键绑定、倍率是否生效、动作是否真能放大、设置对话框能否正常构建。</p>
 */
public class EditorKeyBindCheck {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "target");
        File f = ZoomKeys.file(dir);
        String backup = f.isFile()
                ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;

        ZoomKeys k = new ZoomKeys();
        k.magnify = KeyEvent.VK_M;
        k.zoomIn = KeyEvent.VK_EQUALS;
        k.zoomOut = KeyEvent.VK_MINUS;
        k.fit = KeyEvent.VK_0;
        k.loupe = 3.0;
        k.save(dir);

        Manifest m = Manifest.load(dir);
        final Editor[] holder = new Editor[1];
        SwingUtilities.invokeAndWait(() -> {
            Editor ed = new Editor(m);
            ed.setVisible(true);
            holder[0] = ed;
        });
        Editor ed = holder[0];
        Thread.sleep(1500);

        javax.swing.InputMap im = ed.getRootPane()
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        boolean boundM = im.get(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0)) != null;
        boolean boundMRelease = im.get(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0, true)) != null;
        boolean notQ = im.get(KeyStroke.getKeyStroke(KeyEvent.VK_Q, 0)) == null;

        Field cf = Editor.class.getDeclaredField("canvas");
        cf.setAccessible(true);
        JComponent canvas = (JComponent) cf.get(ed);
        Field mf = canvas.getClass().getDeclaredField("magnifyFactor");
        mf.setAccessible(true);
        double factor = mf.getDouble(canvas);

        // 触发绑定好的"长按放大"动作，看画布是否变大
        Object name = im.get(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0));
        javax.swing.Action act = ed.getRootPane().getActionMap().get(name);
        java.awt.Dimension before = canvas.getPreferredSize();
        if (act != null) {
            SwingUtilities.invokeAndWait(() -> act.actionPerformed(
                    new java.awt.event.ActionEvent(canvas, 0, "test")));
        }
        Thread.sleep(300);
        boolean grew = canvas.getPreferredSize().width > before.width * 1.5;
        Object offName = im.get(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0, true));
        javax.swing.Action off = ed.getRootPane().getActionMap().get(offName);
        if (off != null) {
            SwingUtilities.invokeAndWait(() -> off.actionPerformed(
                    new java.awt.event.ActionEvent(canvas, 0, "test")));
        }
        Thread.sleep(200);
        boolean restored = Math.abs(canvas.getPreferredSize().width - before.width) <= 2;

        // 设置对话框能构建（不显示）
        KeyBindDialog dlg = KeyBindDialog.createForTest(ed, k, dir);
        boolean dialogOk = dlg != null;

        System.out.println("M 绑定=" + boundM + "/" + boundMRelease
                + "（旧 Q 已解除=" + notQ + "）倍率=" + factor
                + " 放大=" + grew + " 松开恢复=" + restored + " 对话框=" + dialogOk);
        boolean all = boundM && boundMRelease && notQ && Math.abs(factor - 3.0) < 1e-6
                && grew && restored && dialogOk;
        System.out.println(all ? "KEYBIND_OK" : "KEYBIND_FAIL");

        if (backup != null) {
            Files.write(f.toPath(), backup.getBytes(StandardCharsets.UTF_8));
        } else {
            f.delete();
        }
        System.exit(all ? 0 : 1);
    }
}
