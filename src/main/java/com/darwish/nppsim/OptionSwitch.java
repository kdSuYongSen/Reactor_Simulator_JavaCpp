package com.darwish.nppsim;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

/**
 * 选项框式开关（与"自动 AZ"同款）：深色面板 + 细边框标题 + ON / OFF 互斥圆钮。
 *
 * <p>原界面里的开关是 JToggleButton（按一下变色、看不出"当前处于哪一档"），
 * 这里统一换成主控室里那种带标题的二位选择框：标题说明这是哪个设备，
 * 框内 ON / OFF 两个圆钮永远只有一个是实心的，一眼就能看出实际位置。</p>
 *
 * <p>边框是自绘的"图例框"：上边线在标题处断开，标题压在缺口上，不会出现
 * {@code TitledBorder} 那种横线从文字中间穿过去的效果。</p>
 *
 * <p>用法：</p>
 * <pre>
 * add(new OptionSwitch(Localization.t("diesel.run"), () -&gt; g.isDieselRunning(0),
 *         on -&gt; { g.setDieselRunning(0, on); }));
 * </pre>
 *
 * <p>开关状态由 {@code state} 提供，界面每帧调用 {@link #sync()} 与仿真对齐
 * （读档、联锁跳闸、自动投入都会反映到圆钮上，不需要反向刷新界面）。</p>
 */
public final class OptionSwitch extends JPanel {

    /** 标题框用的底色，与"自动AZ"面板一致 */
    private static final Color PLATE = new Color(52, 52, 52);
    private static final Color LINE = new Color(222, 222, 222);
    private static final Color TEXT = new Color(235, 235, 235);

    private final JRadioButton on = new JRadioButton();
    private final JRadioButton off = new JRadioButton();
    private final BooleanSupplier state;
    private final String title;
    /** 设计字号（布局工具缩放后按比例缩小，见 adaptFonts） */
    private final int baseTitleSize;
    private Font titleFont;
    /** 已适配过的字号，避免每帧新建 Font */
    private int adaptedSize = -1;
    /** 程序化对齐时置位，避免把 setSelected 当成用户操作 */
    private boolean updating;

    public OptionSwitch(String title, BooleanSupplier state, Consumer<Boolean> onChange) {
        this(title, 11, state, onChange);
    }

    public OptionSwitch(String title, int titleSize, BooleanSupplier state, Consumer<Boolean> onChange) {
        this.state = state;
        this.title = title == null ? "" : title;
        this.baseTitleSize = titleSize;
        this.titleFont = Fonts.text("Dialog", Font.BOLD, titleSize);
        // 让布局工具的"内容缩放"能记录并缩放本组件的基准字体
        setFont(titleFont);
        setOpaque(true);
        setBackground(PLATE);
        // 上边留出标题的位置（标题压在断开的边线上）
        setBorder(BorderFactory.createEmptyBorder(titleSize + 6, 5, 4, 5));

        ButtonGroup group = new ButtonGroup();
        group.add(on);
        group.add(off);
        on.setText(Localization.t("ON"));
        off.setText(Localization.t("OFF"));
        for (JRadioButton b : new JRadioButton[]{on, off}) {
            b.setOpaque(false);
            b.setFont(Fonts.text("Dialog", Font.BOLD, 11));
            b.setForeground(TEXT);
            b.setFocusable(false);
            b.setMargin(new java.awt.Insets(0, 1, 0, 1));
        }

        setLayout(new GridLayout(1, 2, 0, 0));
        add(on);
        add(off);

        on.addActionListener(e -> {
            if (updating) {
                return;
            }
            onChange.accept(Boolean.TRUE);
            sync();
        });
        off.addActionListener(e -> {
            if (updating) {
                return;
            }
            onChange.accept(Boolean.FALSE);
            sync();
        });

        sync();
    }

    /**
     * 按当前尺寸自适应字号：布局编辑器把方块改小后，标题与 ON/OFF 一起缩，
     * 否则固定字号会把 "OFF" 裁成 "OF"。
     */
    private void adaptFonts() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        double s = Math.min(w / 190.0, h / 46.0);
        s = Math.max(0.6, Math.min(1.0, s));
        int size = Math.max(7, (int) Math.round(11 * s));
        int titleSize = Math.max(7, (int) Math.round(baseTitleSize * s));
        if (size == adaptedSize) {
            return;
        }
        adaptedSize = size;
        Font body = Fonts.text("Dialog", Font.BOLD, size);
        on.setFont(body);
        off.setFont(body);
        titleFont = Fonts.text("Dialog", Font.BOLD, titleSize);
        setBorder(BorderFactory.createEmptyBorder(titleSize + 6, 5, 4, 5));
        revalidate();
    }

    /** 把圆钮对齐到仿真当前状态（不触发 onChange） */
    public void sync() {
        updating = true;
        try {
            boolean v = state.getAsBoolean();
            if (v != on.isSelected()) {
                if (v) {
                    on.setSelected(true);
                } else {
                    off.setSelected(true);
                }
            }
        } catch (RuntimeException ignored) {
            // 读档瞬间对象可能为空，下一帧再对齐
        } finally {
            updating = false;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        FontMetrics fm = getFontMetrics(titleFont);
        // 标题至少要放得下，否则会被裁字
        return new Dimension(Math.max(d.width, fm.stringWidth(title) + 14), d.height);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        adaptFonts();
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth() - 1;
        int h = getHeight() - 1;

        // 边框：上边线在标题处断开
        g.setColor(LINE);
        FontMetrics fm = g.getFontMetrics(titleFont);
        int textW = fm.stringWidth(title);
        int gapStart = 5;
        g.drawLine(0, 0, gapStart, 0);
        g.drawLine(Math.min(w, gapStart + textW + 6), 0, w, 0);
        g.drawLine(0, 0, 0, h);
        g.drawLine(w, 0, w, h);
        g.drawLine(0, h, w, h);

        // 标题压在缺口上
        g.setFont(titleFont);
        g.setColor(TEXT);
        g.drawString(title, gapStart + 2, fm.getAscent() - 1);
        g.dispose();
    }
}
