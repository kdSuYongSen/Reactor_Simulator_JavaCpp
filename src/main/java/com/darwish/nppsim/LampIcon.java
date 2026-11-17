package com.darwish.nppsim;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import javax.swing.Icon;

/**
 * 运行指示灯（2026-11-14 改为"实心圆灯"样式）。
 *
 * <p>外观按控制台实灯画：外面一圈浅色金属灯座，里面是玻璃灯罩，
 * 亮的时候灯罩带高光并向外发光，灭的时候灯罩压暗。颜色语义不变：
 * <b>绿 = 运行 / 带电，红 = 停机 / 失电</b>。</p>
 */
final class LampIcon implements Icon {

    static final Color GREEN = new Color(64, 214, 122);
    static final Color RED = new Color(226, 58, 58);

    /** 常亮（绿）与熄灭（红）两个共享实例 */
    static final LampIcon ON = new LampIcon(true, 14);
    static final LampIcon OFF = new LampIcon(false, 14);

    private final boolean on;
    private final int size;

    LampIcon(boolean on, int size) {
        this.on = on;
        this.size = size;
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        paint(g, x + size / 2f, y + size / 2f, size / 2f - 1.5f, on ? GREEN : RED, on);
        g.dispose();
    }

    /**
     * 画一只实心指示灯。
     *
     * @param cx   灯心 x
     * @param cy   灯心 y
     * @param r    灯罩半径（灯座会再大一圈）
     * @param lens 灯罩颜色
     * @param on   是否点亮
     */
    static void paint(Graphics2D g, float cx, float cy, float r, Color lens, boolean on) {
        if (r < 1.5f) {
            return;
        }
        // 光晕（只亮时）：控制台里亮灯周围会有一层散射光
        if (on) {
            float glow = r * 2.0f;
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), glow,
                    new float[]{0f, 0.42f, 1f},
                    new Color[]{new Color(lens.getRed(), lens.getGreen(), lens.getBlue(), 150),
                            new Color(lens.getRed(), lens.getGreen(), lens.getBlue(), 42),
                            new Color(lens.getRed(), lens.getGreen(), lens.getBlue(), 0)}));
            g.fillOval((int) (cx - glow), (int) (cy - glow), (int) (glow * 2), (int) (glow * 2));
        }

        // 金属灯座
        float br = r + Math.max(1.6f, r * 0.34f);
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(cx - br * 0.35f, cy - br * 0.4f), br * 1.7f,
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(250, 246, 234), new Color(214, 206, 186),
                        new Color(150, 142, 122)}));
        g.fillOval((int) (cx - br), (int) (cy - br), (int) (br * 2), (int) (br * 2));
        g.setColor(new Color(96, 90, 78));
        g.setStroke(new BasicStroke(Math.max(0.8f, r * 0.14f)));
        g.drawOval((int) (cx - br), (int) (cy - br), (int) (br * 2), (int) (br * 2));

        // 灯罩：亮时本色 + 上部高光，灭时压暗
        Color dark = mix(lens, Color.BLACK, 0.78f);
        Color top = on ? mix(lens, Color.WHITE, 0.45f) : mix(dark, Color.WHITE, 0.12f);
        Color mid = on ? lens : dark;
        Color bottom = on ? mix(lens, Color.BLACK, 0.32f) : mix(dark, Color.BLACK, 0.35f);
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(cx - r * 0.3f, cy - r * 0.35f), r * 1.7f,
                new float[]{0f, 0.5f, 1f}, new Color[]{top, mid, bottom}));
        g.fillOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));

        // 玻璃高光
        if (r >= 3) {
            g.setColor(new Color(255, 255, 255, on ? 190 : 70));
            g.fillOval((int) (cx - r * 0.55f), (int) (cy - r * 0.68f),
                    Math.max(2, (int) (r * 0.62f)), Math.max(2, (int) (r * 0.46f)));
        }
        // 内圈阴影，做出"灯罩嵌进灯座"的感觉
        g.setColor(new Color(0, 0, 0, on ? 70 : 96));
        g.setStroke(new BasicStroke(Math.max(0.7f, r * 0.12f)));
        g.drawOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));
    }

    static Color mix(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
