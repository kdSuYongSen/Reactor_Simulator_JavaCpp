package com.darwish.nppsim.layeditor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * 纯放大镜（2026-11-03 重做）。
 *
 * <p>原来"长按 Q"是把整页 zoom 乘一个倍率 —— 页面跟着变、比例一直在动，
 * 看着像放大镜其实是在缩放版面，而且倍率会随当前页面缩放自动变化。现在改成真正的放大镜：</p>
 * <ul>
 *   <li>按住热键 → 把鼠标周围一小块（{@link #SRC_W}×{@link #SRC_H}）原样抓下来，
 *       按倍率盖在窗口上；松开撤掉，页面本身一动不动；</li>
 *   <li>倍率是**固定值**（来自 {@code ui_zoom_keys.json} 的 {@code loupe}），
 *       不跟页面缩放叠加、不随窗口大小自动调整；</li>
 *   <li>调倍率只靠工具栏的 − / +（或设置里那个数字），改了只重画，不重新抓图。</li>
 * </ul>
 *
 * <p>抓图不用 {@link java.awt.Robot}，而是把窗口内容面板直接渲染进一张图 ——
 * 既不会把放大镜自己拍进去（画中画），也不受屏幕截图权限限制。</p>
 */
final class Loupe extends JComponent {

    /** 取景框：鼠标周围的原始区域（像素） */
    static final int SRC_W = 260;
    static final int SRC_H = 174;

    static final double MIN = 1.2;
    static final double MAX = 6.0;

    private BufferedImage shot;
    private final Point at = new Point();
    private double factor = 2.5;

    Loupe() {
        setOpaque(false);
        setFocusable(false);
        setVisible(false);
    }

    /** 设置倍率；正在放大时只重画（纯放大镜，倍率不会自己变） */
    void setFactor(double f) {
        double v = Math.max(MIN, Math.min(MAX, f));
        if (Math.abs(v - factor) < 1e-6) {
            return;
        }
        factor = v;
        if (isVisible()) {
            repaint();
        }
    }

    double getFactor() {
        return factor;
    }

    /** 在屏幕坐标 p 处放大 */
    void showAt(Point screenP) {
        if (screenP == null) {
            hideLoupe();
            return;
        }
        Window win = SwingUtilities.getWindowAncestor(this);
        if (win == null) {
            hideLoupe();
            return;
        }
        java.awt.Container content = (win instanceof JFrame)
                ? ((JFrame) win).getContentPane() : win;
        if (content == null) {
            hideLoupe();
            return;
        }

        Point p = new Point(screenP);
        SwingUtilities.convertPointFromScreen(p, content);

        BufferedImage img = new BufferedImage(SRC_W, SRC_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(content.getBackground() == null ? new Color(0x22, 0x26, 0x2E)
                : content.getBackground());
        g.fillRect(0, 0, SRC_W, SRC_H);
        // 把窗口内容整体平移到取景框原点：内容面板包含画布，但不包含玻璃面板（本类）
        g.translate(-(p.x - SRC_W / 2), -(p.y - SRC_H / 2));
        try {
            content.printAll(g);
        } catch (RuntimeException ignore) {
            // 某个组件正处在半初始化状态时别让放大镜把窗口搞崩
        }
        g.dispose();
        shot = img;

        Point gp = new Point(screenP);
        SwingUtilities.convertPointFromScreen(gp, this);
        at.setLocation(gp);
        setVisible(true);
        repaint();
    }

    void hideLoupe() {
        shot = null;
        setVisible(false);
        repaint();
    }

    /** 是否正在放大（不能叫 isShowing —— 那是 Component 的 public 方法） */
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
        // 别画到窗口外面去（放不下就贴边，画面被裁一点，但不会整块消失）
        x = Math.max(0, Math.min(Math.max(0, getWidth() - w), x));
        y = Math.max(0, Math.min(Math.max(0, getHeight() - h), y));

        g.setColor(new Color(0, 0, 0, 120));
        g.fillRoundRect(x + 3, y + 3, w, h, 10, 10);
        g.drawImage(shot, x, y, w, h, null);
        g.setColor(new Color(0xFF, 0xD8, 0x4A));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, w, h, 10, 10);
        g.setStroke(new BasicStroke(1f));

        g.setColor(Color.WHITE);
        g.setFont(new Font("Dialog", Font.BOLD, 11));
        g.drawString(String.format("%.1f×", factor), x + 8, y + 16);
    }
}
