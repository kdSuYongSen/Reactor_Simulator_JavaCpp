package com.darwish.nppsim;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 应急柴油发电机窗口（2026-11-12 新增，2026-11-13 改为 4 台机 + 选项框式开关）。
 *
 * <p>厂内共 4 台应急柴油机：6 kV 应急 A 段带 DG-A1 / DG-A2，B 段带 DG-B1 / DG-B2，
 * 每段 1 号工作、2 号备用（自动投入时工作机优先，油压不足自动改投备用机）。</p>
 *
 * <p>每台机一组：</p>
 * <ul>
 *   <li><b>指针表</b>（steelseries Radial，与原版 MCP / 除氧器窗口同款）：
 *       转速 0…1800 rpm、功率 0…3.6 MW、燃料储量 0…100 %、频率 45…55 Hz；</li>
 *   <li><b>数显</b>（DisplaySingle，浅蓝 LCD）：冷却水温度、润滑油温度、润滑油压、
 *       排烟温度、机端电压、电流、累计运行小时、运行油泵台数。</li>
 * </ul>
 *
 * <p>操作一律使用与"自动 AZ"同款的 {@link OptionSwitch}（深色标题框 + ON/OFF 圆钮）：
 * 启停、出口断路器，以及本机的润滑油泵；公用应急泵 E 与"失电自启"总开关放在底栏。</p>
 *
 * <p>润滑油泵分配：A / B / C / D 分别供 DG-A1 / DG-A2 / DG-B1 / DG-B2，E 为公用泵。
 * 油压没建立（&lt; 1.5 bar）时起机被闭锁；运行中油压低于 1.0 bar 持续 3 s 会跳机。</p>
 */
public class EmergencyDieselUI extends JFrame implements UIUpdateable {

    private static final int N = ElectricalGrid.DIESEL_COUNT;
    private static final String[] TAGS = {"DG-A1", "DG-A2", "DG-B1", "DG-B2"};

    /** 每台机的指针表 */
    private final eu.hansolo.steelseries.gauges.Radial[] rpm =
            new eu.hansolo.steelseries.gauges.Radial[N];
    private final eu.hansolo.steelseries.gauges.Radial[] power =
            new eu.hansolo.steelseries.gauges.Radial[N];
    private final eu.hansolo.steelseries.gauges.Radial[] fuel =
            new eu.hansolo.steelseries.gauges.Radial[N];
    private final eu.hansolo.steelseries.gauges.Radial[] freq =
            new eu.hansolo.steelseries.gauges.Radial[N];
    /** 每台机的数显 */
    private final eu.hansolo.steelseries.gauges.DisplaySingle[] coolant =
            new eu.hansolo.steelseries.gauges.DisplaySingle[N];
    private final eu.hansolo.steelseries.gauges.DisplaySingle[] oil =
            new eu.hansolo.steelseries.gauges.DisplaySingle[N];
    private final eu.hansolo.steelseries.gauges.DisplaySingle[] oilPress =
            new eu.hansolo.steelseries.gauges.DisplaySingle[N];
    private final eu.hansolo.steelseries.gauges.DisplaySingle[] exhaust =
            new eu.hansolo.steelseries.gauges.DisplaySingle[N];
    private final eu.hansolo.steelseries.gauges.DisplaySingle[] volt =
            new eu.hansolo.steelseries.gauges.DisplaySingle[N];
    private final eu.hansolo.steelseries.gauges.DisplaySingle[] amp =
            new eu.hansolo.steelseries.gauges.DisplaySingle[N];
    private final eu.hansolo.steelseries.gauges.DisplaySingle[] hours =
            new eu.hansolo.steelseries.gauges.DisplaySingle[N];
    private final eu.hansolo.steelseries.gauges.DisplaySingle[] lubeCount =
            new eu.hansolo.steelseries.gauges.DisplaySingle[N];

    private final OptionSwitch[] runSwitch = new OptionSwitch[N];
    private final OptionSwitch[] breakerSwitch = new OptionSwitch[N];
    /** 每台机一台专用润滑油泵（泵 A / B / C / D）——拨杆开关 */
    private final RotarySwitch[] lubeSwitch = new RotarySwitch[N];
    private final JLabel[] stateLabel = new JLabel[N];
    /** 每台机的运行指示灯（绿=运行，红=停机） */
    private final JLabel[] stateLamp = new JLabel[N];

    private OptionSwitch autoSwitch;
    /** 公用应急油泵 E：也是拨杆开关 */
    private RotarySwitch commonPumpSwitch;

    public EmergencyDieselUI() {
        setTitle(Localization.t("Emergency Diesel Generators"));
        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        getContentPane().setBackground(UiTheme.BG);
        setLayout(new BorderLayout(0, 6));

        WindowMenu.install(this);
        PageLayout.installAuto(this, "EmergencyDieselUI");

        JPanel units = new JPanel(new GridLayout(2, 2, 8, 8));
        units.setOpaque(false);
        units.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        for (int u = 0; u < N; u++) {
            units.add(unitPanel(u, TAGS[u]));
        }
        add(units, BorderLayout.CENTER);
        add(bottomBar(), BorderLayout.SOUTH);

        setSize(1280, 778);
        setMinimumSize(new Dimension(1160, 700));
        setLocationRelativeTo(null);
        refresh();
    }

    // ================================================================ 单个机组面板

    private JPanel unitPanel(int unit, String tag) {
        JPanel plate = new JPanel(new GridBagLayout());
        plate.setBackground(UiTheme.plate());
        javax.swing.border.TitledBorder tb =
                BorderFactory.createTitledBorder(Localization.t("diesel.unit") + " " + tag);
        tb.setTitleColor(UiTheme.plateText());
        tb.setTitleFont(Fonts.text("Dialog", Font.BOLD, 12));
        plate.setBorder(BorderFactory.createCompoundBorder(tb,
                BorderFactory.createLineBorder(UiTheme.LINE, 1, true)));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 3, 2, 3);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 2;
        gc.anchor = GridBagConstraints.CENTER;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;

        // ---- 第一排：四只指针表 ----
        JPanel gauges = new JPanel(new GridLayout(1, 4, 2, 0));
        gauges.setOpaque(false);
        rpm[unit] = radial(Localization.t("diesel.speed"), "rpm", 0, 1800, 0);
        power[unit] = radial(Localization.t("diesel.power"), "MW", 0, 3.6, 1);
        fuel[unit] = radial(Localization.t("diesel.fuel"), "%", 0, 100, 0);
        freq[unit] = radial(Localization.t("diesel.freq"), "Hz", 45, 55, 2);
        gauges.add(rpm[unit]);
        gauges.add(power[unit]);
        gauges.add(fuel[unit]);
        gauges.add(freq[unit]);
        plate.add(gauges, gc);

        // ---- 第二排：数显（温度 / 油压 / 电气量）----
        JPanel digits = new JPanel(new GridLayout(2, 4, 5, 3));
        digits.setOpaque(false);
        coolant[unit] = display("°C", 0);
        oil[unit] = display("°C", 0);
        oilPress[unit] = display("", 2);
        exhaust[unit] = display("°C", 0);
        volt[unit] = display("kV", 2);
        amp[unit] = display("A", 0);
        hours[unit] = display("h", 1);
        lubeCount[unit] = display("", 0);
        digits.add(captioned(Localization.t("diesel.coolant"), coolant[unit]));
        digits.add(captioned(Localization.t("diesel.oil"), oil[unit]));
        digits.add(captioned(Localization.t("diesel.oilPress") + " (bar)", oilPress[unit]));
        digits.add(captioned(Localization.t("diesel.exhaust"), exhaust[unit]));
        digits.add(captioned(Localization.t("diesel.volt"), volt[unit]));
        digits.add(captioned(Localization.t("diesel.amp"), amp[unit]));
        digits.add(captioned(Localization.t("diesel.hours"), hours[unit]));
        digits.add(captioned(Localization.t("diesel.lubeCount"), lubeCount[unit]));
        gc.gridy = 1;
        plate.add(digits, gc);

        // ---- 第三排：状态 + 启停 / 出口断路器（选项框）----
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        stateLabel[unit] = new JLabel();
        stateLabel[unit].setFont(Fonts.text("Dialog", Font.BOLD, 12));
        stateLamp[unit] = new JLabel(LampIcon.OFF);
        // 用 BorderLayout：灯在左、状态字在右，永远不会折行
        JPanel stateBox = new JPanel(new BorderLayout(4, 0));
        stateBox.setOpaque(false);
        stateBox.add(stateLamp[unit], BorderLayout.WEST);
        stateBox.add(stateLabel[unit], BorderLayout.CENTER);
        stateBox.setPreferredSize(new Dimension(104, 44));
        row.add(stateBox, BorderLayout.WEST);

        // 用 GridBag 按比例分配宽度：三个开关各自拿到需要的宽度，ON/OFF 不会被挤掉
        JPanel ops = new JPanel(new GridBagLayout());
        ops.setOpaque(false);
        final int u = unit;
        runSwitch[unit] = new OptionSwitch(Localization.t("diesel.run"),
                () -> flag(g -> g.isDieselRunning(u)), on -> {
            ElectricalGrid g = NPPSim.grid;
            if (g == null) {
                return;
            }
            boolean before = g.isDieselRunning(u);
            if (on && g.getDieselFuelPct(u) <= 0.5) {
                // 没油起不来：模型不允许，圆钮会在刷新时弹回 OFF
                record("Diesel " + TAGS[u] + " start blocked - no fuel");
                return;
            }
            g.setDieselRunning(u, on);
            if (g.isDieselRunning(u) != before) {
                record("Diesel " + TAGS[u] + (on ? " started manually" : " stopped manually"));
            }
        });
        breakerSwitch[unit] = new OptionSwitch(Localization.t("diesel.breaker"),
                () -> flag(g -> g.isDieselBreaker(u)), on -> {
            ElectricalGrid g = NPPSim.grid;
            if (g == null) {
                return;
            }
            g.setDieselBreaker(u, on);
            record("Diesel " + TAGS[u] + " generator breaker " + (on ? "closed" : "opened"));
        });
        // 本机的润滑油泵（泵 A / B / C / D）与控制开关同一排，省一行高度
        String letter = String.valueOf("ABCDE".charAt(unit));
        lubeSwitch[unit] = new RotarySwitch(
                Localization.t("diesel.lubePump") + " " + letter,
                () -> flag(g -> g.isLubePumpOn(u)), on -> {
            ElectricalGrid g = NPPSim.grid;
            if (g != null) {
                g.setLubePump(u, on);
            }
        });
        runSwitch[unit].setPreferredSize(new Dimension(106, 44));
        breakerSwitch[unit].setPreferredSize(new Dimension(140, 44));
        lubeSwitch[unit].setPreferredSize(new Dimension(184, 44));
        GridBagConstraints oc = new GridBagConstraints();
        oc.gridy = 0;
        oc.fill = GridBagConstraints.BOTH;
        oc.insets = new Insets(0, 4, 0, 0);
        oc.weightx = 106;
        oc.gridx = 0;
        ops.add(runSwitch[unit], oc);
        oc.weightx = 140;
        oc.gridx = 1;
        ops.add(breakerSwitch[unit], oc);
        oc.weightx = 192;
        oc.gridx = 2;
        ops.add(lubeSwitch[unit], oc);
        stateLabel[unit].setPreferredSize(new Dimension(98, 44));
        row.add(ops, BorderLayout.CENTER);

        gc.gridy = 2;
        plate.add(row, gc);
        return plate;
    }

    private JPanel bottomBar() {
        JPanel bottom = new JPanel(new BorderLayout(12, 0));
        bottom.setOpaque(false);
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));

        JPanel left = new JPanel(new GridLayout(1, 2, 8, 0));
        left.setOpaque(false);
        autoSwitch = new OptionSwitch(Localization.t("diesel.auto"),
                () -> flag(g -> g.dieselAutoStart), on -> {
            ElectricalGrid g = NPPSim.grid;
            if (g != null) {
                g.dieselAutoStart = on;
                record("Diesel auto-start " + (on ? "enabled" : "disabled"));
            }
        });
        commonPumpSwitch = new RotarySwitch(Localization.t("diesel.commonPump"),
                () -> flag(g -> g.isLubePumpOn(4)), on -> {
            ElectricalGrid g = NPPSim.grid;
            if (g != null) {
                g.setLubePump(4, on);
            }
        });
        autoSwitch.setPreferredSize(new Dimension(280, 44));
        commonPumpSwitch.setPreferredSize(new Dimension(206, 44));
        left.add(autoSwitch);
        left.add(commonPumpSwitch);
        bottom.add(left, BorderLayout.WEST);

        JButton refuel = new JButton(Localization.t("diesel.refuel"));
        refuel.setFont(Fonts.text("Dialog", Font.PLAIN, 11));
        refuel.setFocusable(false);
        refuel.addActionListener(e -> {
            ElectricalGrid g = NPPSim.grid;
            if (g != null) {
                g.refuel();
                record("Diesel generators refuelled");
            }
        });
        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(refuel);
        bottom.add(right, BorderLayout.EAST);
        return bottom;
    }

    /** 安全读取电网里的开关状态（读档瞬间 grid 可能为 null） */
    private static boolean flag(java.util.function.Predicate<ElectricalGrid> f) {
        ElectricalGrid g = NPPSim.grid;
        return g != null && f.test(g);
    }

    private static void record(String text) {
        if (NPPSim.autoControl != null) {
            NPPSim.autoControl.recordEvent(text);
        }
    }

    /** 数显 + 上方小标题（DisplaySingle 本身不画标题） */
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

    /**
     * 指针表：不显示 LCD（数显另有专门控件）。
     *
     * <p>超限/低油位用**指针变色**表示，不用 steelseries 的 track 色带 ——
     * 那个 track 在本工程这份库里行为异常（无论给什么值，色带都只画在零点附近一小段），
     * 与其调不准，不如把报警信息放在指针上，一眼就能看到。</p>
     */
    private eu.hansolo.steelseries.gauges.Radial radial(String title, String unit,
                                                       double min, double max, int decimals) {
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
        g.setMinValue(min);
        g.setMaxValue(max);
        g.setTitle(title);
        g.setUnitString(unit);
        g.setTitleAndUnitFont(Fonts.text("Verdana", Font.PLAIN, 10));
        g.setTitleAndUnitFontEnabled(true);
        g.setPreferredSize(new Dimension(136, 136));
        g.setMinimumSize(new Dimension(136, 136));
        if (decimals > 0) {
            g.setLcdDecimals(decimals);
        }
        return g;
    }

    /** 数显（浅蓝 LCD，与原窗口一致） */
    private eu.hansolo.steelseries.gauges.DisplaySingle display(String unit, int decimals) {
        eu.hansolo.steelseries.gauges.DisplaySingle d = new eu.hansolo.steelseries.gauges.DisplaySingle();
        d.setLcdColor(eu.hansolo.steelseries.tools.LcdColor.LIGHTBLUE_LCD);
        d.setCustomLcdForeground(new Color(204, 204, 255));
        d.setLcdUnitString(unit);
        d.setLcdUnitFont(Fonts.text("Verdana", Font.PLAIN, 9));
        d.setLcdDecimals(decimals);
        d.setPreferredSize(new Dimension(112, 34));
        d.setMinimumSize(new Dimension(88, 34));
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
        ElectricalGrid g = NPPSim.grid;
        if (g == null) {
            return;
        }
        autoSwitch.sync();
        commonPumpSwitch.sync();
        for (int u = 0; u < N; u++) {
            boolean running = g.isDieselRunning(u);
            rpm[u].setValue(g.getDieselRpm(u));
            power[u].setValue(g.getDieselPowerMW(u));
            fuel[u].setValue(g.getDieselFuelPct(u));
            freq[u].setValue(g.getDieselFreq(u));

            coolant[u].setLcdValue(g.getDieselCoolantC(u));
            oil[u].setLcdValue(g.getDieselOilC(u));
            oilPress[u].setLcdValue(g.getDieselLubePress(u));
            exhaust[u].setLcdValue(g.getDieselExhaustC(u));
            volt[u].setLcdValue(g.getDieselVoltKV(u));
            amp[u].setLcdValue(g.getDieselAmp(u));
            hours[u].setLcdValue(g.getDieselHours(u));
            lubeCount[u].setLcdValue(g.getDieselLubePumpCount(u));

            runSwitch[u].sync();
            breakerSwitch[u].sync();
            lubeSwitch[u].sync();

            String state;
            Color color;
            if (g.getDieselFuelPct(u) <= 0.5 && !running) {
                state = Localization.t("diesel.nofuel");
            } else if (running) {
                state = Localization.t("diesel.running");
            } else {
                state = Localization.t("diesel.stopped");
            }
            stateLabel[u].setText(state);
            // 灯与文字同一语义：绿 = 运行，红 = 停机 / 缺油
            stateLabel[u].setForeground(running ? LampIcon.GREEN : LampIcon.RED);
            stateLamp[u].setIcon(running ? LampIcon.ON : LampIcon.OFF);

            // 超限/低油位 → 指针变红（本库 track 色带不可靠，改用指针表达）
            eu.hansolo.steelseries.tools.ColorDef normalColor =
                    eu.hansolo.steelseries.tools.ColorDef.BLACK;
            eu.hansolo.steelseries.tools.ColorDef alarmColor =
                    eu.hansolo.steelseries.tools.ColorDef.RED;
            rpm[u].setPointerColor(g.getDieselRpm(u) > 1600 ? alarmColor : normalColor);
            power[u].setPointerColor(g.getDieselPowerMW(u) > 3.4 ? alarmColor : normalColor);
            fuel[u].setPointerColor(g.getDieselFuelPct(u) < 15 ? alarmColor : normalColor);
            double hz = g.getDieselFreq(u);
            freq[u].setPointerColor((running && Math.abs(hz - 50) > 0.5) ? alarmColor : normalColor);
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
