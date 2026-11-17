package com.darwish.nppsim;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 汽轮机润滑油系统窗口（2026-11-16 新增）。
 *
 * <p>两台汽轮发电机各一套润滑油系统：</p>
 * <ul>
 *   <li><b>A 泵</b> —— 交流主油泵，接厂用 380 V 母线；正常运行时由它供油。</li>
 *   <li><b>B 泵</b> —— 直流事故油泵，接 220 V 直流母线；交流全停时靠它保轴承。</li>
 * </ul>
 *
 * <p>油泵开关沿用全厂统一的 {@link RotarySwitch}（槽式拨杆造型，指向右 = 开、
 * 向下 = 关），联锁投入用与"自动 AZ"同款的 {@link OptionSwitch}。</p>
 *
 * <p>母管油压由模型算出（{@link TG}）：单泵 2.6 bar，双泵并联 2.95 bar，额定转速下
 * 主轴泵再补 0.5 bar；油压低于 0.8 bar 持续 3 s，{@link TurbineProtection} 跳机。
 * 这里只做显示与开关，逻辑全部在模型里。</p>
 */
public class TurbineLubeUI extends JFrame implements UIUpdateable {

    private static final int N = 2;
    private static final String[] TAGS = {"TG-1", "TG-2"};

    /** 母管油压表 */
    private final eu.hansolo.steelseries.gauges.Radial[] press =
            new eu.hansolo.steelseries.gauges.Radial[N];
    /** 油温 / 运行泵台数数显 */
    private final eu.hansolo.steelseries.gauges.DisplaySingle[] temp =
            new eu.hansolo.steelseries.gauges.DisplaySingle[N];
    private final eu.hansolo.steelseries.gauges.DisplaySingle[] pumps =
            new eu.hansolo.steelseries.gauges.DisplaySingle[N];

    private final RotarySwitch[] pumpA = new RotarySwitch[N];
    private final RotarySwitch[] pumpB = new RotarySwitch[N];
    private final OptionSwitch[] standby = new OptionSwitch[N];

    private final JLabel[] stateLabel = new JLabel[N];
    private final JLabel[] stateLamp = new JLabel[N];

    public TurbineLubeUI() {
        setTitle(Localization.t("Turbine Lube Oil"));
        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        getContentPane().setBackground(UiTheme.BG);
        setLayout(new BorderLayout(0, 6));

        WindowMenu.install(this);
        PageLayout.installAuto(this, "TurbineLubeUI");

        JPanel units = new JPanel(new GridLayout(1, 2, 10, 0));
        units.setOpaque(false);
        units.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        units.add(unitPanel(0));
        units.add(unitPanel(1));
        add(units, BorderLayout.CENTER);
        add(bottomBar(), BorderLayout.SOUTH);

        setSize(1366, 768);
        setMinimumSize(new Dimension(900, 560));
        setLocationRelativeTo(null);
        refresh();
    }

    // ================================================================ 单台机

    private JPanel unitPanel(final int idx) {
        JPanel plate = new JPanel();
        plate.setBackground(UiTheme.plate());
        plate.setLayout(new javax.swing.BoxLayout(plate, javax.swing.BoxLayout.Y_AXIS));
        javax.swing.border.TitledBorder tb = BorderFactory.createTitledBorder(
                Localization.t("Turbine-Generator") + " " + TAGS[idx]);
        tb.setTitleColor(UiTheme.plateText());
        tb.setTitleFont(Fonts.text("Dialog", Font.BOLD, 12));
        plate.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createCompoundBorder(tb,
                        BorderFactory.createLineBorder(UiTheme.LINE, 1, true)),
                BorderFactory.createEmptyBorder(6, 10, 8, 10)));

        // 竖直排列、靠上排布：多余高度全部落在底部（仪表盘类的窗口习惯把内容放在上沿）
        plate.add(javax.swing.Box.createVerticalStrut(10));

        // ---- 第一行：油压表 + 两台数显 ----
        press[idx] = radial();
        temp[idx] = display("°C", 1);
        pumps[idx] = display("", 0);
        JPanel row1 = new JPanel(new BorderLayout(12, 0));
        row1.setOpaque(false);
        row1.add(captioned(Localization.t("lube.press"), press[idx]), BorderLayout.WEST);
        JPanel digits = new JPanel(new java.awt.GridLayout(2, 1, 0, 8));
        digits.setOpaque(false);
        digits.add(captioned(Localization.t("lube.temp"), temp[idx]));
        digits.add(captioned(Localization.t("lube.count"), pumps[idx]));
        row1.add(digits, BorderLayout.CENTER);
        row1.setAlignmentX(0.5f);
        row1.setPreferredSize(new Dimension(640, 224));
        row1.setMaximumSize(new Dimension(640, 224));
        plate.add(row1);

        JPanel st = stateBox(idx);
        st.setAlignmentX(0.5f);
        st.setMaximumSize(new Dimension(620, 26));
        plate.add(javax.swing.Box.createVerticalStrut(8));
        plate.add(st);

        // ---- A / B 泵 + 联锁 ----
        pumpA[idx] = new RotarySwitch(Localization.t("lube.pumpA"),
                () -> flag(idx, TG::isLubePumpA), on -> {
                    TG unit = unit(idx);
                    if (unit != null) {
                        unit.setLubePumpA(on);
                    }
                });
        pumpB[idx] = new RotarySwitch(Localization.t("lube.pumpB"),
                () -> flag(idx, TG::isLubePumpB), on -> {
                    TG unit = unit(idx);
                    if (unit != null) {
                        unit.setLubePumpB(on);
                    }
                });
        standby[idx] = new OptionSwitch(Localization.t("lube.auto"),
                () -> flag(idx, TG::isLubeStandbyAuto), on -> {
                    TG unit = unit(idx);
                    if (unit != null) {
                        unit.setLubeStandbyAuto(on);
                    }
                });
        // 铭牌只写泵名（电压放在脚注和鼠标提示里）
        pumpA[idx].setToolTipText(Localization.t("lube.pumpA") + " · 380 V AC");
        pumpB[idx].setToolTipText(Localization.t("lube.pumpB") + " · 220 V DC");
        // 固定尺寸：BoxLayout 会把子件拉到最大高度，旋钮的槽会被拉长变形
        for (javax.swing.JComponent sw : new javax.swing.JComponent[]{
                pumpA[idx], pumpB[idx], standby[idx]}) {
            sw.setAlignmentX(0.5f);
            sw.setPreferredSize(new Dimension(430, 46));
            sw.setMaximumSize(new Dimension(430, 46));
        }
        plate.add(javax.swing.Box.createVerticalStrut(14));
        plate.add(pumpA[idx]);
        plate.add(javax.swing.Box.createVerticalStrut(8));
        plate.add(pumpB[idx]);
        plate.add(javax.swing.Box.createVerticalStrut(10));
        plate.add(standby[idx]);

        plate.add(javax.swing.Box.createVerticalGlue());
        return plate;
    }

    /** 灯 + 状态字（灯绿 = 油压正常，灯红 = 油压不足） */
    private JPanel stateBox(int idx) {
        JPanel box = new JPanel(new BorderLayout(6, 0));
        box.setOpaque(false);
        stateLamp[idx] = new JLabel(LampIcon.OFF);
        stateLabel[idx] = new JLabel("—");
        stateLabel[idx].setFont(Fonts.text("Dialog", Font.BOLD, 12));
        box.add(stateLamp[idx], BorderLayout.WEST);
        box.add(stateLabel[idx], BorderLayout.CENTER);
        return box;
    }

    private JPanel bottomBar() {
        JPanel bottom = new JPanel(new BorderLayout(0, 0));
        bottom.setOpaque(false);
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        JLabel note = new JLabel(Localization.t("lube.note"));
        note.setFont(Fonts.text("Dialog", Font.PLAIN, 11));
        note.setForeground(UiTheme.dimText());
        bottom.add(note, BorderLayout.WEST);
        return bottom;
    }

    // ================================================================ 工具

    private static TG unit(int idx) {
        return idx == 0 ? NPPSim.tg1 : NPPSim.tg2;
    }

    private static boolean flag(int idx, java.util.function.Predicate<TG> f) {
        TG u = unit(idx);
        return u != null && f.test(u);
    }

    /** 数显 + 上方小标题 */
    private JPanel captioned(String title, javax.swing.JComponent c) {
        JPanel p = new JPanel(new BorderLayout(0, 1));
        p.setOpaque(false);
        JLabel l = new JLabel(title);
        l.setFont(Fonts.text("Dialog", Font.PLAIN, 10));
        l.setForeground(UiTheme.dimText());
        p.add(l, BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    /** 油压表（与应急柴油窗口同款外观；标题单独做成 JLabel，表内只留单位） */
    private eu.hansolo.steelseries.gauges.Radial radial() {
        eu.hansolo.steelseries.gauges.Radial g = new eu.hansolo.steelseries.gauges.Radial();
        g.setBackgroundColor(eu.hansolo.steelseries.tools.BackgroundColor.LIGHT_GRAY);
        g.setFrameDesign(eu.hansolo.steelseries.tools.FrameDesign.TILTED_BLACK);
        g.setFrameVisible(true);
        g.setLcdVisible(false);
        g.setLedVisible(false);
        g.setPointerColor(eu.hansolo.steelseries.tools.ColorDef.BLACK);
        g.setPointerShadowVisible(false);
        g.setPostsVisible(false);
        g.setTicklabelOrientation(eu.hansolo.steelseries.tools.TicklabelOrientation.HORIZONTAL);
        g.setMinValue(0.0);
        g.setMaxValue(4.0);
        g.setTitle("");
        g.setUnitString("bar");
        g.setTitleAndUnitFont(Fonts.text("Verdana", Font.PLAIN, 10));
        g.setTitleAndUnitFontEnabled(true);
        g.setPreferredSize(new Dimension(176, 200));
        g.setMinimumSize(new Dimension(150, 170));
        return g;
    }

    private eu.hansolo.steelseries.gauges.DisplaySingle display(String unit, int decimals) {
        eu.hansolo.steelseries.gauges.DisplaySingle d =
                new eu.hansolo.steelseries.gauges.DisplaySingle();
        d.setLcdColor(eu.hansolo.steelseries.tools.LcdColor.LIGHTBLUE_LCD);
        d.setCustomLcdForeground(new Color(204, 204, 255));
        d.setLcdUnitString(unit);
        d.setLcdUnitFont(Fonts.text("Verdana", Font.PLAIN, 9));
        d.setLcdDecimals(decimals);
        d.setPreferredSize(new Dimension(120, 34));
        d.setMinimumSize(new Dimension(96, 32));
        return d;
    }

    // ================================================================ 刷新

    @Override
    public void update() {
        if (!isVisible()) {
            return;
        }
        java.awt.EventQueue.invokeLater(() -> {
            try {
                refresh();
            } catch (Exception ignored) {
                // 单窗口刷新异常不得拖垮全局更新
            }
        });
    }

    private void refresh() {
        for (int i = 0; i < N; i++) {
            TG u = unit(i);
            if (u == null) {
                continue;
            }
            press[i].setValue(u.getLubePress());
            temp[i].setLcdValue(u.getLubeOilTemp());
            pumps[i].setLcdValue(u.getLubePumpCount());

            pumpA[i].sync();
            pumpB[i].sync();
            standby[i].sync();

            boolean low = u.getLubePress() < TurbineProtection.LOW_LUBE_BAR;
            boolean turning = u.getRpm() > 300.0;
            stateLabel[i].setText(low
                    ? Localization.t("lube.low") : Localization.t("lube.ok"));
            stateLabel[i].setForeground(low ? LampIcon.RED : LampIcon.GREEN);
            stateLamp[i].setIcon(low ? LampIcon.OFF : LampIcon.ON);

            // 油压不足且在转动 → 指针变红（本库 track 色带不可靠，改用指针表达）
            press[i].setPointerColor(low && turning
                    ? eu.hansolo.steelseries.tools.ColorDef.RED
                    : eu.hansolo.steelseries.tools.ColorDef.BLACK);
        }
    }

    // ================================================================ UIUpdateable

    @Override
    public void initializeDialUpdateThread() {
    }

    @Override
    public void setVisibility(boolean visible) {
        setVisible(visible);
    }

    @Override
    public void discard() {
        setVisible(false);
    }

    @Override
    public void acknowledge() {
    }
}
