package com.darwish.nppsim.layeditor;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * 把编辑器的画布按三种状态渲染成 PNG（2026-11-03）：
 * 适应窗口 / 放大 1.5 倍 / 长按 Q。用来肉眼确认内容没有被裁、缩放正常。
 */
public class EditorRender {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "target");
        Manifest m = Manifest.load(dir);
        if (m == null) {
            System.out.println("RENDER_FAIL 读不到清单");
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
        Class<?> cc = canvas.getClass();
        Method mZoomBy = cc.getDeclaredMethod("zoomBy", double.class);
        Method mFit = cc.getDeclaredMethod("fitView");
        for (Method x : new Method[] {mZoomBy, mFit}) {
            x.setAccessible(true);
        }

        // 纯放大镜：页面不动，只把鼠标周围一块放大盖上去
        Field lof = Editor.class.getDeclaredField("loupe");
        lof.setAccessible(true);
        JComponent loupe = (JComponent) lof.get(ed);
        Method mShow = loupe.getClass().getDeclaredMethod("showAt", java.awt.Point.class);
        mShow.setAccessible(true);
        Method mHide = loupe.getClass().getDeclaredMethod("hideLoupe");
        mHide.setAccessible(true);

        render(canvas, new File(dir, "editor_fit.png"));
        mZoomBy.invoke(canvas, 1.5);
        Thread.sleep(400);
        render(canvas, new File(dir, "editor_zoom150.png"));
        mFit.invoke(canvas);
        Thread.sleep(300);
        mShow.invoke(loupe, new java.awt.Point(ed.getX() + 420, ed.getY() + 300));
        Thread.sleep(300);
        renderFrame(ed, loupe, new File(dir, "editor_loupe.png"));
        mHide.invoke(loupe, new Object[0]);
        System.out.println("RENDER_OK");
        System.exit(0);
    }

    /** 把"窗口内容 + 放大镜"一起渲染出来（放大镜平时盖在最上层） */
    private static void renderFrame(Editor ed, JComponent loupe, File out) throws Exception {
        java.awt.Container content = ed.getContentPane();
        BufferedImage img = new BufferedImage(Math.max(1, content.getWidth()),
                Math.max(1, content.getHeight()), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        content.printAll(g);
        loupe.setSize(content.getSize());
        loupe.paint(g);
        g.dispose();
        ImageIO.write(img, "png", out);
        System.out.println("saved " + out.getName() + " " + img.getWidth() + "x" + img.getHeight());
    }

    private static void render(JComponent canvas, File out) throws Exception {
        Dimension d = canvas.getPreferredSize();
        BufferedImage img = new BufferedImage(Math.max(1, d.width), Math.max(1, d.height),
                BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        canvas.paint(g);
        g.dispose();
        ImageIO.write(img, "png", out);
        System.out.println("saved " + out.getName() + " " + img.getWidth() + "x" + img.getHeight());
    }
}
