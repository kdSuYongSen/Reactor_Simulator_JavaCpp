package com.darwish.nppsim;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * 长按放大镜（2026-11-03）。
 *
 * <p>主窗口没有"整页缩放"——面板都是真实的 Swing 组件，缩放会把布局和命中判定全搅乱。
 * 所以这里做成**放大镜**：按住热键（默认 Q）时抓一张鼠标周围的屏幕快照，
 * 按倍率画在窗口最上层的玻璃面板上；松开就撤掉，界面回到原样。</p>
 *
 * <p>快照用 {@link Robot} 抓屏，看到什么就放大什么（不受组件绘制方式限制）；
 * 抓之前先把自己隐藏，避免"画中画"。</p>
 */
final class ZoomLoupe extends JComponent {

    /** 取景框：鼠标周围的原始区域（屏幕像素） */
    private static final int SRC_W = 240;
    private static final int SRC_H = 160;

    private BufferedImage shot;
    private Point at = new Point();
    private double factor = 2.5;

    ZoomLoupe() {
        setOpaque(false);
        setVisible(false);
    }

    void setFactor(double f) {
        double v = Math.max(1.2, Math.min(6.0, f));
        if (Math.abs(v - factor) < 1e-6) {
            return;
        }
        factor = v;
        // 纯放大镜：倍率变了只按新倍率重画同一张快照，不重新抓屏、不改变任何布局
        if (isVisible()) {
            repaint();
        }
    }

    double getFactor() {
        return factor;
    }

    /** 在屏幕坐标 p 处放大（p 由 MouseInfo 取当前鼠标位置） */
    void showAt(Point screenP) {
        if (screenP == null) {
            return;
        }
        setVisible(false);          // 先隐藏，别把上一次的放大镜自己也拍进去
        try {
            Rectangle src = new Rectangle(
                    screenP.x - SRC_W / 2, screenP.y - SRC_H / 2, SRC_W, SRC_H);
            shot = new Robot().createScreenCapture(src);
            Point gp = new Point(screenP);
            SwingUtilities.convertPointFromScreen(gp, this);
            at = gp;
            setVisible(true);
            repaint();
        } catch (AWTException | SecurityException e) {
            shot = null;
            setVisible(false);
        }
    }

    void hideLoupe() {
        shot = null;
        setVisible(false);
        repaint();
    }

    /** 是否正在放大（注意不能叫 isShowing —— 那是 Component 的 public 方法） */
    boolean isLoupeVisible() {
        return isVisible() && shot != null;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        if (shot == null) {
            return;
        }
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        int w = (int) Math.round(SRC_W * factor);
        int h = (int) Math.round(SRC_H * factor);
        int x = at.x - w / 2;
        int y = at.y - h / 2;
        // 别画到窗口外面去（放不下就贴边，画面裁一点，但不会整块消失）
        x = Math.max(0, Math.min(Math.max(0, getWidth() - w), x));
        y = Math.max(0, Math.min(Math.max(0, getHeight() - h), y));

        // 阴影 + 白边 + 放大内容
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRoundRect(x + 3, y + 3, w, h, 10, 10);
        g.drawImage(shot, x, y, w, h, null);
        g.setColor(new Color(0xFF, 0xD8, 0x4A));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, w, h, 10, 10);
        g.setStroke(new BasicStroke(1f));

        g.setColor(Color.WHITE);
        g.setFont(new java.awt.Font("Dialog", java.awt.Font.BOLD, 11));
        g.drawString(String.format("%.1f×", factor), x + 8, y + 16);
    }
}
