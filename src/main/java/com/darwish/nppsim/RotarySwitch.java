package com.darwish.nppsim;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * 转动开关 / 旋钮选择开关（2026-11-14，按参考图重做；上一版误做成拨杆）。
 *
 * <p>造型：左边两盏实心灯（<b>红灯亮 = 停止</b>、<b>绿灯亮 = 运行</b>）+ 中间铭牌 +
 * 右边圆形旋钮。旋钮周围是刻度盘，左位标 {@code 0}（停）、右位标 {@code I}（开），
 * 旋钮上的白色指针指向当前位置；点一下旋钮（或整块）就转到另一个位置，
 * 指针带 160 ms 的转动过渡，手感和真实选择开关一致。</p>
 *
 * <p>状态由 {@code state} 提供，界面每帧调用 {@link #sync()} 与仿真对齐；
 * 联锁（比如低油压跳机、母线失电）会让指针自己转回 {@code 0}。</p>
 */
public final class RotarySwitch extends JComponent {

    private static final Color PLATE = new Color(232, 226, 205);   // 铭牌底色
    private static final Color INK = new Color(38, 34, 26);        // 铭牌文字
    private static final Color DIAL = new Color(30, 33, 39);       // 刻度盘
    private static final Color DIAL_EDGE = new Color(96, 100, 110);
    private static final Color EDGE = new Color(120, 114, 98);

    /**
     * 手柄角度（度，以竖直向下为 0，向右为正）：
     * <b>0° = 向下 = 关</b>，<b>90° = 指向右 = 开</b>。
     */
    private static final double ANG_OFF = 0;
    private static final double ANG_ON = 90;

    private final String name;
    private final BooleanSupplier state;
    private final Consumer<Boolean> onChange;

    /** 当前显示角度（按时间插值，不依赖定时器累计） */
    private double shownAngle = ANG_OFF;
    /** 本次转动的起点角度 / 目标角度 / 起始时刻 */
    private double fromAngle = ANG_OFF;
    private double turnTarget = ANG_OFF;
    private long turnStartNs;
    private boolean turning;
    /** 只用于在转动期间持续重绘 */
    private Timer anim;

    public RotarySwitch(String name, BooleanSupplier state, Consumer<Boolean> onChange) {
        this.name = name == null ? "" : name;
        this.state = state;
        this.onChange = onChange;
        setOpaque(false);
        setLayout(new GridBagLayout());
        setPreferredSize(new Dimension(178, 44));
        setMinimumSize(new Dimension(150, 38));
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        setToolTipText(name + "：转动旋钮（0 = 停 / I = 开）");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                onChange.accept(!state.getAsBoolean());
                sync();
            }
        });
        shownAngle = targetAngle();
        turnTarget = shownAngle;
    }

    /**
     * 与仿真状态对齐（不触发 onChange）；状态变化时指针按时间插值转过去。
     *
     * <p>角度由 {@code (now - 起始时刻) / 160 ms} 算出，而不是靠定时器一帧帧累加——
     * 界面每帧都会调用本方法，定时器被反复重置也不会把指针卡在中途。</p>
     */
    public void sync() {
        double target = targetAngle();
        if (Math.abs(target - turnTarget) > 0.01) {
            fromAngle = shownAngle;
            turnTarget = target;
            turnStartNs = System.nanoTime();
            turning = true;
            if (anim == null) {
                anim = new Timer(16, e -> {
                    advance();
                    repaint();
                });
                anim.setRepeats(true);
            }
            anim.restart();
        }
        advance();
        repaint();
    }

    /** 按经过时间推进指针角度 */
    private void advance() {
        if (!turning) {
            return;
        }
        double p = (System.nanoTime() - turnStartNs) / 1.6e8;   // 160 ms
        if (p >= 1.0) {
            p = 1.0;
            turning = false;
            if (anim != null) {
                anim.stop();
            }
        }
        double e = p * p * (3 - 2 * p);      // smoothstep
        shownAngle = fromAngle + (turnTarget - fromAngle) * e;
    }

    /** 指针当前角度（度，调试/自检用） */
    public double shownAngleDeg() {
        return shownAngle;
    }

    /** 是否仍在转动中（调试/自检用） */
    public boolean isTurning() {
        return turning;
    }

    /** 当前是否在 I 位（开） */
    public boolean isOn() {
        return targetAngle() == ANG_ON;
    }

    /** 目标角度：以模型状态为准，避免界面与联锁结果不一致 */
    private double targetAngle() {
        try {
            return state.getAsBoolean() ? ANG_ON : ANG_OFF;
        } catch (RuntimeException ex) {
            return ANG_OFF;
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        boolean on = isOn();

        // 窄面板（凝结水这类 160 px 的小组）自动收一号，否则铭牌只剩两个字
        boolean narrow = w < 190;

        // ---------------- 两盏灯：左红（停）右绿（开） ----------------
        int ly = h / 2;
        float lr = narrow ? 5f : 6f;
        int lx1 = narrow ? 10 : 13;
        int lx2 = narrow ? 24 : 31;
        LampIcon.paint(g, lx1, ly, lr, LampIcon.RED, !on);
        LampIcon.paint(g, lx2, ly, lr, LampIcon.GREEN, on);

        // ---------------- 铭牌 ----------------
        int px = narrow ? 34 : 44;
        int pw = Math.max(40, w - px - (narrow ? 8 : 16) - (narrow ? 56 : 66));
        g.setPaint(new java.awt.LinearGradientPaint(
                new Point2D.Float(px, 6), new Point2D.Float(px, h - 6),
                new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(246, 241, 224), PLATE, new Color(206, 199, 178)}));
        g.fillRoundRect(px, 6, pw, h - 12, 5, 5);
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(px, 6, pw, h - 12, 5, 5);

        Font f = Fonts.text("Dialog", Font.BOLD, narrow ? 9 : 11);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        String label = name;
        while (label.length() > 1 && fm.stringWidth(label) > pw - 8) {
            label = label.substring(0, label.length() - 1);
        }
        g.setColor(INK);
        g.drawString(label, px + (pw - fm.stringWidth(label)) / 2,
                ly + (fm.getAscent() - fm.getDescent()) / 2);

        // ---------------- 槽式拨杆（按参考图：黑色滑槽 + 红色手柄 + 白色行程弧） ----------------
        int sw = narrow ? 56 : 64;
        int sx = w - sw - (narrow ? 6 : 6);
        int sy = 2;
        int sh = h - 4;
        int scx = sx + sw / 2;
        int acy = sy + 9;                  // 转轴中心（槽上部）
        double len = Math.min(19.0, sh - 21.0);    // 手柄长度（留出档位点）
        double arcR = len + 2.6;                   // 行程弧略大于手柄长度（不压弧）

        // 槽：黑色圆角凹槽（顶部压暗 = 内阴影）
        g.setPaint(new java.awt.LinearGradientPaint(
                new Point2D.Float(sx, sy), new Point2D.Float(sx, sy + sh),
                new float[]{0f, 0.28f, 1f},
                new Color[]{new Color(8, 9, 11), new Color(21, 23, 27), new Color(30, 33, 39)}));
        g.fillRoundRect(sx, sy, sw, sh, 5, 5);
        g.setColor(new Color(88, 92, 100));
        g.setStroke(new BasicStroke(1.1f));
        g.drawRoundRect(sx, sy, sw, sh, 5, 5);

        // 行程弧：整段米白（暗），当前位那一侧提亮
        drawArc(g, scx, acy, arcR, 0, 90, new Color(226, 222, 206, 110), 1.8f);
        if (shownAngle > 2) {
            drawArc(g, scx, acy, arcR, 0, shownAngle,
                    on ? new Color(150, 240, 180, 240) : new Color(244, 138, 112, 240), 2.4f);
        }

        // 两个档位点在弧的外侧：下方 = 关（红），右侧 = 开（绿）
        double dotR = arcR + 4.8;
        dot(g, scx, acy + dotR, !on, true);
        dot(g, scx + dotR, acy, on, false);

        // 手柄：从转轴向下伸出，末端落在弧上
        double rad = Math.toRadians(shownAngle);
        int hx = (int) Math.round(scx + Math.sin(rad) * len);
        int hy = (int) Math.round(acy + Math.cos(rad) * len);

        // 手柄投影
        g.setColor(new Color(0, 0, 0, 120));
        g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(scx + 2, acy + 2, hx + 2, hy + 2);
        // 手柄外缘（深红）
        g.setColor(new Color(74, 22, 18));
        g.setStroke(new BasicStroke(9.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(scx, acy, hx, hy);
        // 手柄主体（红，靠转轴一端更亮）
        g.setPaint(new java.awt.LinearGradientPaint(
                new Point2D.Float(scx, acy), new Point2D.Float(hx, hy),
                new float[]{0f, 1f},
                new Color[]{new Color(232, 96, 72), new Color(158, 44, 34)}));
        g.setStroke(new BasicStroke(7.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(scx, acy, hx, hy);
        // 手柄高光（偏左上的一细条）
        g.setColor(new Color(255, 190, 170, 150));
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(scx - 2, acy + 2, hx - 2, hy - 1);

        // 转轴帽（深色圆 + 高光）
        g.setPaint(new RadialGradientPaint(new Point2D.Float(scx - 1.6f, acy - 2f), 6.5f,
                new float[]{0f, 0.6f, 1f},
                new Color[]{new Color(104, 80, 74), new Color(44, 31, 28),
                        new Color(15, 11, 10)}));
        g.fillOval(scx - 5, acy - 5, 11, 11);
        g.setColor(new Color(12, 10, 9));
        g.setStroke(new BasicStroke(1f));
        g.drawOval(scx - 5, acy - 5, 11, 11);
        g.setColor(new Color(232, 202, 190, 130));
        g.fillOval(scx - 2, acy - 3, 3, 2);

        g.dispose();
    }

    /** 以 (cx,cy) 为心、半径 r、从 a0 到 a1 的圆弧（角度相对竖直向下，正 = 右） */
    private void drawArc(Graphics2D g, int cx, int cy, double r, double a0, double a1,
                         Color color, float width) {
        if (Math.abs(a1 - a0) < 0.6) {
            return;
        }
        java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
        int steps = Math.max(8, (int) (Math.abs(a1 - a0)));
        for (int i = 0; i <= steps; i++) {
            double a = Math.toRadians(a0 + (a1 - a0) * i / steps);
            double x = cx + Math.sin(a) * r;
            double y = cy + Math.cos(a) * r;
            if (i == 0) {
                p.moveTo(x, y);
            } else {
                p.lineTo(x, y);
            }
        }
        g.setColor(color);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
    }

    /**
     * 档位点：亮 = 手柄当前在这一位。
     *
     * @param off 是否是"关"位（决定亮起来的颜色：关=暖红、开=绿）
     */
    private void dot(Graphics2D g, double cx0, double cy0, boolean lit, boolean off) {
        int cx = (int) Math.round(cx0);
        int cy = (int) Math.round(cy0);
        Color c = off ? new Color(246, 126, 100) : new Color(146, 240, 178);
        if (lit) {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), 4.2f,
                    new float[]{0f, 1f},
                    new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), 190),
                            new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
            g.fillOval(cx - 4, cy - 4, 8, 8);
            g.setColor(c);
            g.fillOval(cx - 2, cy - 2, 4, 4);
        } else {
            g.setColor(new Color(120, 124, 132, 170));
            g.fillOval(cx - 1, cy - 1, 3, 3);
        }
    }
}
