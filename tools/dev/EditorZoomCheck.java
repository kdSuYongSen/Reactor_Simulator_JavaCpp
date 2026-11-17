package com.darwish.nppsim.layeditor;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.KeyEvent;

/**
 * 编辑器缩放 / 放大镜自检（2026-11-03，10-03 改成"纯放大镜"后重写）。
 *
 * <p>验：画布在滚动容器里（内容超出视口能滚）；+/− 改变画布尺寸（页面缩放）；
 * 0/适应窗口复位；<b>长按热键只显示放大镜、页面尺寸一点不变</b>；
 * 放大镜倍率固定（来自配置），点 −/+ 才变、且页面尺寸仍不变；Q 的按键绑定确实存在。</p>
 */
public class EditorZoomCheck {

    /** 取某个键绑定的动作（模拟真实按键） */
    private static javax.swing.Action action(Editor ed, KeyStroke ks) {
        Object name = ed.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(ks);
        return name == null ? null : ed.getRootPane().getActionMap().get(name);
    }

    public static void main(String[] args) throws Exception {
        java.io.File dir = new java.io.File(args.length > 0 ? args[0] : "target");
        Manifest m = Manifest.load(dir);
        if (m == null) {
            System.out.println("ZOOM_FAIL 读不到清单");
            System.exit(1);
        }
        final Editor[] holder = new Editor[1];
        SwingUtilities.invokeAndWait(() -> {
            Editor ed = new Editor(m);
            ed.setVisible(true);
            holder[0] = ed;
        });
        Editor ed = holder[0];
        Thread.sleep(1500);

        Field cf = Editor.class.getDeclaredField("canvas");
        cf.setAccessible(true);
        JComponent canvas = (JComponent) cf.get(ed);
        Field sf = Editor.class.getDeclaredField("canvasScroll");
        sf.setAccessible(true);
        JScrollPane scroll = (JScrollPane) sf.get(ed);
        Field zf = Editor.class.getDeclaredField("zoomLabel");
        zf.setAccessible(true);
        javax.swing.JLabel zoomLabel = (javax.swing.JLabel) zf.get(ed);
        Field lof = Editor.class.getDeclaredField("loupe");
        lof.setAccessible(true);
        JComponent loupe = (JComponent) lof.get(ed);

        Class<?> cc = canvas.getClass();
        Method mZoomBy = cc.getDeclaredMethod("zoomBy", double.class);
        Method mFit = cc.getDeclaredMethod("fitView");
        for (Method x : new Method[] {mZoomBy, mFit}) {
            x.setAccessible(true);
        }
        Method mShow = loupe.getClass().getDeclaredMethod("showAt", Point.class);
        mShow.setAccessible(true);
        Method mHide = loupe.getClass().getDeclaredMethod("hideLoupe");
        mHide.setAccessible(true);
        Method mFactor = loupe.getClass().getDeclaredMethod("getFactor");
        mFactor.setAccessible(true);

        Dimension base = canvas.getPreferredSize();
        System.out.println("适应窗口后画布=" + base.width + "x" + base.height
                + "，视口=" + (scroll == null ? "-"
                        : scroll.getViewport().getExtentSize().width + "x"
                          + scroll.getViewport().getExtentSize().height)
                + "，比例=" + zoomLabel.getText());

        java.lang.reflect.Field scF = cc.getDeclaredField("scale");
        scF.setAccessible(true);
        java.lang.reflect.Field fitF = cc.getDeclaredField("fitScale");
        fitF.setAccessible(true);
        // 0) 打开就"不放大"：比例封顶 100%，绝不会自己放大到 200%
        boolean neverUp = scF.getDouble(canvas) <= 1.0001 && fitF.getDouble(canvas) <= 1.0001;
        System.out.println("初始 scale=" + scF.getDouble(canvas)
                + " fitScale=" + fitF.getDouble(canvas) + " 不放大=" + neverUp);

        // 1) 试着放大：页面必须纹丝不动（放大只属于放大镜）
        mZoomBy.invoke(canvas, 2.0);
        Thread.sleep(300);
        Dimension z2 = canvas.getPreferredSize();
        boolean noPageZoom = Math.abs(z2.width - base.width) <= 2;
        System.out.println("试着放大 2 倍后画布=" + z2.width + "x" + z2.height + "，比例显示=" + zoomLabel.getText());

        mFit.invoke(canvas);
        Thread.sleep(300);
        Dimension back = canvas.getPreferredSize();
        boolean fitted = Math.abs(back.width - base.width) <= 2;
        System.out.println("适应窗口后=" + back.width + "x" + back.height + "，比例显示=" + zoomLabel.getText());

        // 2) 长按 = 纯放大镜：玻璃面板出现，但画布尺寸/页面比例不变
        double factorBefore = ((Number) mFactor.invoke(loupe)).doubleValue();
        Point inWin = new Point(ed.getX() + ed.getWidth() / 2, ed.getY() + ed.getHeight() / 2);
        mShow.invoke(loupe, inWin);
        Thread.sleep(300);
        Dimension duringMag = canvas.getPreferredSize();
        boolean loupeShown = loupe.isVisible();
        boolean pageUnchanged = Math.abs(duringMag.width - back.width) <= 2;
        System.out.println("放大镜显示=" + loupeShown + " 倍率=" + factorBefore
                + " 期间画布=" + duringMag.width + "x" + duringMag.height);
        mHide.invoke(loupe);
        Thread.sleep(200);
        boolean loupeHidden = !loupe.isVisible();

        // 3) 点 − / + 只改倍率，不改页面
        Method mChange = Editor.class.getDeclaredMethod("changeLoupeFactor", double.class);
        mChange.setAccessible(true);
        mChange.invoke(ed, 0.25);
        Thread.sleep(150);
        double f2 = ((Number) mFactor.invoke(loupe)).doubleValue();
        Dimension afterChange = canvas.getPreferredSize();
        boolean factorChanged = f2 > factorBefore;
        boolean pageStillSame = Math.abs(afterChange.width - back.width) <= 2;
        System.out.println("点 + 后倍率=" + f2 + " 画布=" + afterChange.width + "x" + afterChange.height);
        // 还原成自检前的倍率（配置文件是模拟器共用的，别留测试痕迹）
        mChange.invoke(ed, factorBefore - ((Number) mFactor.invoke(loupe)).doubleValue());

        boolean qBound = ed.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_Q, 0)) != null;
        boolean qReleaseBound = ed.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_Q, 0, true)) != null;

        /* 4) 按住不放（系统自动重复连发 keyPressed）只执行一次：
         *    第二次不该重新渲染快照；按住期间倍率快捷键也不该响应。 */
        javax.swing.Action qOn = action(ed, KeyStroke.getKeyStroke(KeyEvent.VK_Q, 0));
        javax.swing.Action qOff = action(ed, KeyStroke.getKeyStroke(KeyEvent.VK_Q, 0, true));
        javax.swing.Action zIn = action(ed, KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, 0));
        Field heldF = Editor.class.getDeclaredField("magnifyHeld");
        heldF.setAccessible(true);
        Field shotF = loupe.getClass().getDeclaredField("shot");
        shotF.setAccessible(true);
        boolean onceOnly = false;
        boolean ignoredWhileHeld = false;
        boolean canRepeatAfterRelease = false;
        if (qOn != null && qOff != null) {
            SwingUtilities.invokeAndWait(() -> qOn.actionPerformed(
                    new java.awt.event.ActionEvent(ed, 0, "test")));
            Object shot1 = shotF.get(loupe);
            SwingUtilities.invokeAndWait(() -> qOn.actionPerformed(
                    new java.awt.event.ActionEvent(ed, 0, "test")));   // 模拟自动重复
            Object shot2 = shotF.get(loupe);
            onceOnly = shot1 != null && shot1 == shot2;

            double fBefore = ((Number) mFactor.invoke(loupe)).doubleValue();
            if (zIn != null) {
                SwingUtilities.invokeAndWait(() -> zIn.actionPerformed(
                        new java.awt.event.ActionEvent(ed, 0, "test")));
            }
            ignoredWhileHeld = Math.abs(((Number) mFactor.invoke(loupe)).doubleValue() - fBefore) < 1e-6;

            SwingUtilities.invokeAndWait(() -> qOff.actionPerformed(
                    new java.awt.event.ActionEvent(ed, 0, "test")));
            SwingUtilities.invokeAndWait(() -> qOn.actionPerformed(
                    new java.awt.event.ActionEvent(ed, 0, "test")));
            canRepeatAfterRelease = heldF.getBoolean(ed) && shotF.get(loupe) != null;
            SwingUtilities.invokeAndWait(() -> qOff.actionPerformed(
                    new java.awt.event.ActionEvent(ed, 0, "test")));
        }
        System.out.println("按住只执行一次=" + onceOnly + " 按住时忽略其它键=" + ignoredWhileHeld
                + " 松开后可再按=" + canRepeatAfterRelease);

        System.out.println("滚动容器=" + (scroll != null) + " 不自动放大=" + neverUp
                + " 页面不可放大=" + noPageZoom + " 复位=" + fitted
                + " 放大镜显示=" + loupeShown + " 页面不动=" + pageUnchanged
                + " 松开消失=" + loupeHidden + " 倍率可调=" + factorChanged
                + " 调倍率不动页面=" + pageStillSame
                + " Q绑定=" + qBound + "/" + qReleaseBound
                + " 按住只执行一次=" + onceOnly + " 按住忽略=" + ignoredWhileHeld
                + " 松开可再按=" + canRepeatAfterRelease);
        boolean all = scroll != null && neverUp && noPageZoom && fitted && loupeShown && pageUnchanged
                && loupeHidden && factorChanged && pageStillSame && qBound && qReleaseBound
                && onceOnly && ignoredWhileHeld && canRepeatAfterRelease;
        System.out.println(all ? "ZOOM_OK" : "ZOOM_FAIL");
        System.exit(all ? 0 : 1);
    }
}
