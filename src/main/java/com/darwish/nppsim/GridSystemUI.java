package com.darwish.nppsim;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JPanel;

/**
 * 电网系统窗口（2026-11-12 新增，替换原占位页）。
 *
 * <p>一张可点击的单线图 + 顶部功率平衡读数 + 底部开关操作。图上每个断路器/柴油机方块都能直接点，
 * 点一下分合闸；母线颜色即带电状态（绿=有压、暗红=失电），数值是实时电压与负荷。</p>
 *
 * <p>接线与 {@link ElectricalGrid} 一致（750 kV 版本）：</p>
 * <pre>
 *        750 kV 主电网
 *             │ Q1 上网
 *        主变 20/750 kV
 *             │ Q0 发电机出口
 *        20 kV 母线 ── TG-1 / TG-2
 *             │ 厂用变 750/6 kV（Q2/Q3，从 750 kV 两侧引下）
 *        6 kV A ═════ Q6 母联 ═════ 6 kV B
 *             │Q7                   │Q8
 *        应急 A ══ DG-A      DG-B ══ 应急 B
 *             │Q9                   │Q10
 *        380 V 三相 ──Q11── 直流 220 V（蓄电池）
 *
 *        110 kV 辅助（厂外独立进线 Q12）──Q4/Q5──→ 6 kV A/B 备用
 * </pre>
 */
public class GridSystemUI extends JFrame implements UIUpdateable {

    private final Diagram diagram = new Diagram();
    private final MetricRow metrics = new MetricRow();
    /** Q1 钥匙锁定面板（左下红灯常亮 = 锁定） */
    private final KeyPanel keyPanel = new KeyPanel();
    /** 底部开关组：全部是与"自动 AZ"同款的选项框（ON = 合闸 / OFF = 分闸） */
    private final java.util.LinkedHashMap<String, OptionSwitch> switches =
            new java.util.LinkedHashMap<>();

    public GridSystemUI() {
        setTitle(Localization.t("Grid System"));
        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        getContentPane().setBackground(UiTheme.BG);
        setLayout(new java.awt.BorderLayout(0, 6));

        WindowMenu.install(this);
        PageLayout.installAuto(this, "GridSystemUI");

        metrics.setOpaque(true);
        metrics.setBackground(UiTheme.plate());
        javax.swing.JPanel north = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
        north.setBackground(UiTheme.plate());
        north.add(metrics, java.awt.BorderLayout.CENTER);
        north.add(keyPanel, java.awt.BorderLayout.EAST);
        add(north, java.awt.BorderLayout.NORTH);
        add(diagram, java.awt.BorderLayout.CENTER);
        add(buildControls(), java.awt.BorderLayout.SOUTH);

        setSize(1100, 840);
        setMinimumSize(new Dimension(1020, 780));
        setLocationRelativeTo(null);
    }

    // ================================================================ 刷新

    @Override
    public void update() {
        if (!isVisible()) {
            return;
        }
        java.awt.EventQueue.invokeLater(() -> {
            try {
                metrics.refresh();
                syncButtons();
                keyPanel.repaint();
                diagram.repaint();
            } catch (Exception ex) {
                // 单个窗口刷新异常不得拖垮全局 UI 更新
            }
        });
    }

    private void syncButtons() {
        for (OptionSwitch s : switches.values()) {
            s.sync();
        }
    }

    // ================================================================ Q1 钥匙锁定面板

    /**
     * 并网断路器 Q1 的就地控制面板（按参考图：深色盘面 + 红/绿状态窗 + 钥匙）。
     *
     * <p>逻辑：左下红灯常亮 = 锁定，此时断路器分/合一律被拒（事件留痕）；
     * 点右下角的钥匙（I 位）扭一下即解锁，红灯熄灭；再扭一次重新锁定。</p>
     */
    private final class KeyPanel extends javax.swing.JComponent {

        /* 颜色用**实例**常量（不用 static）：本工程按 --release 8 编译，
         * 内部类里不允许 static 字段（Java 16 才放开），而 KeyPanel 只建一个实例。 */
        private final Color FACE = new Color(26, 32, 44);
        private final Color FRAME = new Color(226, 220, 200);
        private final Color PLATE = new Color(176, 168, 148);
        private final Color INK = new Color(28, 26, 20);
        private final Color DIM = new Color(96, 100, 108);

        KeyPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(238, 104));
            setMinimumSize(new Dimension(238, 104));
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            setToolTipText("钥匙：解锁 / 锁定 Q1（红灯常亮 = 锁定）");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    // 只有右下角的钥匙可扭，避免误触
                    if (e.getX() >= getWidth() - 74 && e.getY() >= getHeight() - 56) {
                        ElectricalGrid g = NPPSim.grid;
                        if (g != null) {
                            g.toggleGridBreakerLock();
                        }
                        repaint();
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            ElectricalGrid e = NPPSim.grid;
            boolean locked = e == null || e.isGridBreakerLocked();
            boolean closed = e != null && e.gridBreaker;

            // 盘面
            g.setColor(FACE);
            g.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
            g.setColor(FRAME);
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(1, 1, w - 3, h - 3, 10, 10);

            int halfW = (w - 30) / 2;
            // ---- 上排：分闸（红）/ 合闸（绿）状态窗 ----
            window(g, 10, 10, halfW, 22, closed ? false : true, "分闸");
            window(g, 20 + halfW, 10, halfW, 22, closed, "合闸");

            // ---- 中排：铭牌 ----
            plate(g, 10, 40, halfW, 20, "并网断路器");
            plate(g, 20 + halfW, 40, halfW, 20, "Q1 · 750 kV");

            // ---- 左下：锁定红灯 ----
            LampIcon.paint(g, 24, h - 26, 10f, LampIcon.RED, locked);
            g.setFont(Fonts.text("Dialog", Font.BOLD, 11));
            g.setColor(locked ? new Color(255, 120, 120) : new Color(120, 190, 140));
            g.drawString(locked ? "锁定" : "解锁", 42, h - 22);

            // ---- 右下：钥匙 ----
            int kx = w - 30;
            int ky = h - 28;
            g.setPaint(new java.awt.RadialGradientPaint(
                    new java.awt.geom.Point2D.Float(kx - 3, ky - 4), 18f,
                    new float[]{0f, 0.65f, 1f},
                    new Color[]{new Color(250, 246, 232), new Color(206, 199, 178),
                            new Color(150, 143, 124)}));
            g.fillOval(kx - 14, ky - 14, 28, 28);
            g.setColor(new Color(110, 104, 90));
            g.setStroke(new BasicStroke(1.4f));
            g.drawOval(kx - 14, ky - 14, 28, 28);
            // 钥匙孔朝向：锁定竖直，解锁转 90°；"I" 印在钥匙面上
            g.setColor(new Color(40, 38, 34));
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (locked) {
                g.drawLine(kx, ky - 9, kx, ky - 2);
            } else {
                g.drawLine(kx - 8, ky - 6, kx - 1, ky - 6);
            }
            Font f = Fonts.text("Dialog", Font.BOLD, 10);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(new Color(60, 56, 48));
            g.drawString("I", kx - fm.stringWidth("I") / 2, ky + 10);

            g.dispose();
        }

        /** 状态窗：亮 = 该状态成立 */
        private void window(Graphics2D g, int x, int y, int w, int h, boolean lit, String text) {
            Color base = "分闸".equals(text) ? new Color(200, 40, 44) : new Color(40, 176, 96);
            g.setColor(lit ? base : new Color(base.getRed() / 4, base.getGreen() / 4,
                    base.getBlue() / 4));
            if (lit) {
                g.setPaint(new java.awt.RadialGradientPaint(
                        new java.awt.geom.Point2D.Float(x + w / 2f, y + h / 2f), w * 0.75f,
                        new float[]{0f, 1f},
                        new Color[]{new Color(base.getRed(), base.getGreen(), base.getBlue(), 210),
                                new Color(base.getRed(), base.getGreen(), base.getBlue(), 90)}));
            }
            g.fillRoundRect(x, y, w, h, 4, 4);
            g.setColor(new Color(12, 14, 18));
            g.drawRoundRect(x, y, w, h, 4, 4);
            g.setFont(Fonts.text("Dialog", Font.BOLD, 11));
            g.setColor(lit ? Color.WHITE : new Color(150, 150, 150));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, x + (w - fm.stringWidth(text)) / 2,
                    y + (h + fm.getAscent() - fm.getDescent()) / 2);
        }

        /** 铭牌：浅色小牌 + 深色字 */
        private void plate(Graphics2D g, int x, int y, int w, int h, String text) {
            g.setPaint(new java.awt.LinearGradientPaint(
                    new java.awt.geom.Point2D.Float(x, y), new java.awt.geom.Point2D.Float(x, y + h),
                    new float[]{0f, 1f},
                    new Color[]{new Color(240, 234, 216), PLATE}));
            g.fillRoundRect(x, y, w, h, 4, 4);
            g.setColor(new Color(120, 114, 98));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(x, y, w, h, 4, 4);
            g.setFont(Fonts.text("Dialog", Font.BOLD, 10));
            g.setColor(INK);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, x + (w - fm.stringWidth(text)) / 2,
                    y + (h + fm.getAscent() - fm.getDescent()) / 2);
        }
    }

    // ================================================================ 开关操作

    /** @return true = 合（导通），false = 分 */
    private boolean stateOf(String id) {
        ElectricalGrid g = NPPSim.grid;
        if (g == null) {
            return false;
        }
        switch (id) {
            case "Q0":  return g.genBreaker20;
            case "Q1":  return g.gridBreaker;
            case "Q2":  return g.unitBreakerA;
            case "Q3":  return g.unitBreakerB;
            case "Q4":  return g.reserveBreakerA;
            case "Q5":  return g.reserveBreakerB;
            case "Q6":  return g.busTie;
            case "Q7":  return g.emergencyBreakerA;
            case "Q8":  return g.emergencyBreakerB;
            case "Q9":  return g.transformer04A;
            case "Q10": return g.transformer04B;
            case "Q11": return g.chargerOn;
            case "Q12": return g.line110Breaker;
            case "DG1": return g.isDieselRunning(0);
            case "DG2": return g.isDieselRunning(1);
            case "DG3": return g.isDieselRunning(2);
            case "DG4": return g.isDieselRunning(3);
            default:    return false;
        }
    }

    /** 执行一次分/合操作（on = 合闸） */
    private void apply(String id, boolean on) {
        ElectricalGrid g = NPPSim.grid;
        if (g == null) {
            return;
        }
        if ("Q1".equals(id) && g.gridBreakerLocked) {
            // 钥匙没扭到解锁位 → 断路器操作无效（面板左下红灯常亮）
            if (NPPSim.autoControl != null) {
                NPPSim.autoControl.recordEvent("Q1 locked - turn the key to unlock");
            }
            keyPanel.repaint();
            return;
        }
        switch (id) {
            case "Q0":  g.genBreaker20 = on; break;
            case "Q1":  g.gridBreaker = on; break;
            case "Q2":  g.unitBreakerA = on; break;
            case "Q3":  g.unitBreakerB = on; break;
            case "Q4":  g.reserveBreakerA = on; break;
            case "Q5":  g.reserveBreakerB = on; break;
            case "Q6":  g.busTie = on; break;
            case "Q7":  g.emergencyBreakerA = on; break;
            case "Q8":  g.emergencyBreakerB = on; break;
            case "Q9":  g.transformer04A = on; break;
            case "Q10": g.transformer04B = on; break;
            case "Q11": g.chargerOn = on; break;
            case "Q12": g.line110Breaker = on; break;
            case "DG1": g.setDieselRunning(0, on); break;
            case "DG2": g.setDieselRunning(1, on); break;
            case "DG3": g.setDieselRunning(2, on); break;
            case "DG4": g.setDieselRunning(3, on); break;
            default: break;
        }
        if (NPPSim.autoControl != null && !id.startsWith("DG")) {
            NPPSim.autoControl.recordEvent("Grid: " + id + " -> " + (stateOf(id) ? "CLOSED" : "OPEN"));
        }
        syncButtons();
        diagram.repaint();
    }

    /** 选项框标题：编号 + 短名（长名称放不下 5 列网格） */
    private static String shortLabel(String id) {
        String name;
        switch (id) {
            case "Q0":  name = Localization.t("grid.q0"); break;
            case "Q1":  name = Localization.t("grid.q1"); break;
            case "Q2":  name = Localization.t("grid.q2"); break;
            case "Q3":  name = Localization.t("grid.q3"); break;
            case "Q4":  name = Localization.t("grid.q4"); break;
            case "Q5":  name = Localization.t("grid.q5"); break;
            case "Q6":  name = Localization.t("grid.q6"); break;
            case "Q7":  name = Localization.t("grid.q7"); break;
            case "Q8":  name = Localization.t("grid.q8"); break;
            case "Q9":  name = Localization.t("grid.q9"); break;
            case "Q10": name = Localization.t("grid.q10"); break;
            case "Q11": name = Localization.t("grid.q11"); break;
            case "Q12": name = Localization.t("grid.q12"); break;
            case "DG1": name = Localization.t("grid.dg1"); break;
            case "DG2": name = Localization.t("grid.dg2"); break;
            case "DG3": name = Localization.t("grid.dg3"); break;
            case "DG4": name = Localization.t("grid.dg4"); break;
            default:    return id;
        }
        return id + " · " + name;
    }

    private JPanel buildControls() {
        JPanel p = new JPanel(new java.awt.GridLayout(3, 6, 6, 4));
        p.setBackground(UiTheme.BG);
        p.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 8, 8, 8));
        String[] ids = {"Q0", "Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8", "Q9", "Q10", "Q11",
                "Q12", "DG1", "DG2", "DG3", "DG4"};
        for (String id : ids) {
            OptionSwitch s = new OptionSwitch(shortLabel(id), 10,
                    () -> stateOf(id), on -> apply(id, on));
            s.setPreferredSize(new Dimension(168, 46));
            p.add(s);
            switches.put(id, s);
        }
        syncButtons();
        return p;
    }

    // ================================================================ 顶部读数

    private static final class MetricRow extends JPanel {
        private final javax.swing.JLabel gen = value();
        private final javax.swing.JLabel aux = value();
        private final javax.swing.JLabel exp = value();
        private final javax.swing.JLabel freq = value();
        private final javax.swing.JLabel soc = value();
        private final javax.swing.JLabel dc = value();

        MetricRow() {
            setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 14, 5));
            add(cell("Generation", gen));
            add(cell("Station load", aux));
            add(cell("Export", exp));
            add(cell("Frequency", freq));
            add(cell("Battery", soc));
            add(cell("DC bus", dc));
            refresh();
        }

        private static javax.swing.JLabel value() {
            javax.swing.JLabel l = new javax.swing.JLabel("—");
            l.setFont(new Font("Monospaced", Font.BOLD, 13));
            l.setForeground(UiTheme.accent());
            return l;
        }

        private JPanel cell(String name, javax.swing.JLabel v) {
            JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
            p.setOpaque(false);
            javax.swing.JLabel n = new javax.swing.JLabel(Localization.t(name));
            n.setFont(Fonts.text("Dialog", Font.BOLD, 11));
            n.setForeground(UiTheme.plateText());
            p.add(n);
            p.add(v);
            return p;
        }

        void refresh() {
            ElectricalGrid g = NPPSim.grid;
            if (g == null) {
                return;
            }
            gen.setText(String.format("%.0f MW", g.getGenerationMW()));
            aux.setText(String.format("%.1f MW", g.getAuxLoadMW()));
            exp.setText(String.format("%.0f MW", g.getExportMW()));
            freq.setText(String.format("%.2f Hz", g.getFrequencyHz()));
            soc.setText(String.format("%.0f %%", g.getBatterySoc() * 100.0));
            dc.setText(String.format("%.0f V", g.getVoltDC()));
            gen.setForeground(g.getGenerationMW() > 1 ? UiTheme.accent() : new Color(255, 130, 130));
            exp.setForeground(g.getExportMW() < -1 ? new Color(255, 170, 80) : UiTheme.accent());
            freq.setForeground(Math.abs(g.getFrequencyHz() - 50.0) > 0.2 ? new Color(255, 130, 130)
                    : UiTheme.accent());
            soc.setForeground(g.getBatterySoc() > 0.5 ? new Color(150, 235, 150)
                    : new Color(255, 170, 80));
            dc.setForeground(g.hasDC() ? UiTheme.accent() : new Color(255, 130, 130));
        }
    }

    // ================================================================ 单线图

    private final class Diagram extends JPanel {

        /** 图幅（绘制坐标）；实际显示时等比缩放并居中 */
        private static final int W = 1010;
        private static final int H = 652;
        /** 顶部为"外部主电网"预留的高度（其余图元整体下移这么多） */
        private static final int TOP = 32;

        private final List<Hit> hits = new ArrayList<>();
        private double ox, oy, sc = 1.0;

        // 图线统一用金属灰：带电/失电不再靠线色，而是靠指示灯（绿=带电/运行，红=失电/停机）
        private final Color WIRE = new Color(200, 208, 218);
        private final Color WIRE_DIM = new Color(116, 124, 134);
        private final Color OK = new Color(52, 214, 124);
        private final Color BAD = new Color(255, 86, 92);
        private final Color TXT = new Color(220, 228, 238);
        private final Color TXT_DIM = new Color(150, 158, 168);
        private final Color FACE = new Color(50, 56, 63);

        Diagram() {
            setBackground(UiTheme.BG);
            setPreferredSize(new Dimension(1010, 600));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    double x = (e.getX() - ox) / sc;
                    double y = (e.getY() - oy) / sc - TOP;
                    for (Hit h : hits) {
                        if (h.rect.contains(x, y)) {
                            apply(h.id, !stateOf(h.id));
                            return;
                        }
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            hits.clear();

            sc = Math.min((getWidth() - 6) / (double) W, (getHeight() - 4) / (double) H);
            if (sc > 1.0) {
                sc = 1.0;
            }
            if (sc <= 0.05) {
                g.dispose();
                return;
            }
            ox = (getWidth() - W * sc) / 2.0;
            oy = (getHeight() - H * sc) / 2.0;
            g.translate(ox, oy);
            g.scale(sc, sc);

            ElectricalGrid e = NPPSim.grid;
            boolean v750 = e != null && e.has750();
            boolean v20 = e != null && e.has20();
            boolean v110 = e != null && e.has110();
            boolean v6a = e != null && e.has6A();
            boolean v6b = e != null && e.has6B();
            boolean v6ae = e != null && e.has6Ae();
            boolean v6be = e != null && e.has6Be();
            boolean v380 = e != null && e.has380();
            boolean vdc = e != null && e.hasDC();
            boolean gen = e != null && e.getGenerationMW() > 1;

            // ================= 外部主电网 =================
            gridBlock(g, 395, 22, 210, 46, v750);
            g.translate(0, TOP);

            // ================= 750 kV 母线 =================
            bus(g, 300, 700, 55, v750, "750 kV 母线    "
                    + fmtKV750(e == null ? 0 : e.getVolt750()), 324, true);
            // 厂用变引下线（从主电网母线两端引下，避开 20 kV / 110 kV 母线）
            wire(g, 300, 55, 150, 55, v750);
            wire(g, 150, 55, 150, 272, v750);
            wire(g, 700, 55, 890, 55, v750);
            wire(g, 890, 55, 890, 272, v750);

            // ================= 主变 20/750 → 20 kV 母线 =================
            wire(g, 500, 55, 500, 64, v750);
            breaker(g, "Q1", 500, 80, false);
            transformer(g, 500, 128, v750);
            text(g, "主变 20/750 kV", 522, 132, TXT_DIM, Font.PLAIN, 11);
            wire(g, 500, 158, 500, 158, v750);
            breaker(g, "Q0", 500, 174, false);

            bus(g, 270, 730, 200, v20, "20 kV 发电机母线    "
                    + String.format("%.1f kV", e == null ? 0 : e.getVolt20()), 322);
            wire(g, 300, 200, 300, 226, v20);
            generator(g, 300, 246, "TG-1", gen);
            wire(g, 700, 200, 700, 226, v20);
            generator(g, 700, 246, "TG-2", gen);

            // ================= 厂用变 750/6 kV =================
            transformer(g, 150, 302, v750);
            text(g, "厂用变 A 750/6 kV", 172, 306, TXT_DIM, Font.PLAIN, 10);
            wire(g, 150, 332, 150, 344, v750);
            breaker(g, "Q2", 150, 360, false, true);
            wire(g, 150, 376, 150, 390, v750);

            transformer(g, 890, 302, v750);
            text(g, "厂用变 B 750/6 kV", 760, 306, TXT_DIM, Font.PLAIN, 10);
            wire(g, 890, 332, 890, 344, v750);
            breaker(g, "Q3", 890, 360, false, true);
            wire(g, 890, 376, 890, 390, v750);

            // ================= 110 kV 厂外辅助电源 =================
            text(g, "厂外 110 kV", 34, 308, TXT_DIM, Font.PLAIN, 10);
            wire(g, 60, 340, 114, 340, v110);
            breaker(g, "Q12", 130, 340, true, true);
            wire(g, 146, 340, 186, 340, v110);
            bus(g, 186, 800, 340, v110, "110 kV 辅助母线    "
                    + String.format("%.1f kV", e == null ? 0 : e.getVolt110()), 360);
            wire(g, 410, 340, 410, 349, v110);
            breaker(g, "Q4", 410, 365, false);
            wire(g, 410, 381, 410, 390, v110);
            wire(g, 620, 340, 620, 349, v110);
            breaker(g, "Q5", 620, 365, false, true);
            wire(g, 620, 381, 620, 390, v110);

            // ================= 6 kV 常用 A / B =================
            bus(g, 110, 430, 390, v6a, "6 kV 常用 A    "
                    + fmtKV6(e == null ? 0 : e.getVolt6A()) + "  "
                    + fmtMW(e == null ? 0 : e.getLoad6A()), 162);
            bus(g, 570, 890, 390, v6b, "6 kV 常用 B    "
                    + fmtKV6(e == null ? 0 : e.getVolt6B()) + "  "
                    + fmtMW(e == null ? 0 : e.getLoad6B()), 632);
            wire(g, 430, 390, 484, 390, v6a || v6b);
            breaker(g, "Q6", 500, 390, true);
            wire(g, 516, 390, 570, 390, v6a || v6b);

            // ---- 各段 6 kV 母线上带的泵（运行/总数）----
            wire(g, 305, 394, 305, 394, v6a);
            loadBox(g, 200, 394, 210, 48, "厂用负荷 A", v6a,
                    pumpSummary("MCP", NPPSim.mcc == null ? null : NPPSim.mcc.mcp, 0, 4) + "   "
                            + pumpSummary("MFP", NPPSim.mainFeedwaterPumps, 0, 4) + "   "
                            + pumpSummary("CEP", NPPSim.condensate1A, 0, 99),
                    "应急段 " + pumpSummary("AFP", NPPSim.auxFeedwaterPumps, 0, 3) + "（经 Q7）");
            wire(g, 785, 394, 785, 394, v6b);
            loadBox(g, 680, 394, 210, 48, "厂用负荷 B", v6b,
                    pumpSummary("MCP", NPPSim.mcc == null ? null : NPPSim.mcc.mcp, 4, 8) + "   "
                            + pumpSummary("MFP", NPPSim.mainFeedwaterPumps, 4, 99) + "   "
                            + pumpSummary("CEP", NPPSim.condensate2A, 0, 99),
                    "应急段 " + pumpSummary("AFP", NPPSim.auxFeedwaterPumps, 3, 99) + "（经 Q8）");

            // ================= 6 kV 应急 A / B =================
            wire(g, 182, 390, 182, 406, v6a);
            breaker(g, "Q7", 182, 422, false, true);
            wire(g, 182, 438, 182, 466, v6ae);
            bus(g, 110, 430, 466, v6ae, "6 kV 应急 A    "
                    + fmtKV6(e == null ? 0 : e.getVolt6Ae()) + "  "
                    + fmtMW(e == null ? 0 : e.getLoad6Ae()), 210);
            wire(g, 662, 390, 662, 406, v6b);
            breaker(g, "Q8", 662, 422, false, true);
            wire(g, 662, 438, 662, 466, v6be);
            bus(g, 570, 890, 466, v6be, "6 kV 应急 B    "
                    + fmtKV6(e == null ? 0 : e.getVolt6Be()) + "  "
                    + fmtMW(e == null ? 0 : e.getLoad6Be()), 690);

            // ================= 四台应急柴油机 =================
            wire(g, 182, 466, 182, 489, v6ae);
            diesel(g, "DG1", "DG-A1", 120, 489);
            wire(g, 320, 466, 320, 489, v6ae);
            diesel(g, "DG2", "DG-A2", 258, 489);
            wire(g, 662, 466, 662, 489, v6be);
            diesel(g, "DG3", "DG-B1", 600, 489);
            wire(g, 800, 466, 800, 489, v6be);
            diesel(g, "DG4", "DG-B2", 738, 489);

            // ================= 380 V / 直流 220 V =================
            wire(g, 416, 466, 416, 492, v6ae);
            breaker(g, "Q9", 416, 508, false);
            wire(g, 416, 524, 416, 568, v380);
            bus(g, 110, 430, 568, v380, "380 V 三相    "
                    + String.format("%.0f V", e == null ? 0 : e.getVolt04()) + "  "
                    + fmtMW(e == null ? 0 : e.getLoad380()));

            // 380 V 第二路电源：应急 B 段经 6/0.4 kV 变（Q10）反送
            wire(g, 580, 466, 580, 531, v6be);
            wire(g, 580, 531, 516, 531, v6be);
            breaker(g, "Q10", 500, 531, true);
            wire(g, 484, 531, 430, 531, v380 || v6be);
            wire(g, 430, 531, 430, 568, v380);

            wire(g, 430, 568, 446, 568, v380);
            breaker(g, "Q11", 462, 568, true);
            wire(g, 478, 568, 512, 568, vdc);
            battery(g, 512, 568, vdc);
            wire(g, 542, 568, 580, 568, vdc);
            dcBox(g, 580, 542, 330, 52, e, vdc);

            legend(g);
            g.dispose();
        }

        // ================================================== 图元

        /** 母线：金属排 + 指示灯 + 读数 */
        private void bus(Graphics2D g, int x1, int x2, int y, boolean live, String label) {
            bus(g, x1, x2, y, live, label, x1 + 24, false);
        }

        /** 同上，但指定标签起点（用于避开左侧的其它标注） */
        private void bus(Graphics2D g, int x1, int x2, int y, boolean live, String label,
                         int labelX) {
            bus(g, x1, x2, y, live, label, labelX, false);
        }

        /** 同上；below = 标题画在母排下方（顶部有设备时用） */
        private void bus(Graphics2D g, int x1, int x2, int y, boolean live, String label,
                         int labelX, boolean below) {
            g.setColor(live ? WIRE : WIRE_DIM);
            g.fillRoundRect(x1, y - 4, x2 - x1, 8, 6, 6);
            g.setColor(new Color(22, 26, 30));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(x1, y - 4, x2 - x1, 8, 6, 6);
            lamp(g, x1 + 10, below ? y + 18 : y - 18, live);
            text(g, label, labelX, below ? y + 23 : y - 14,
                    live ? TXT : TXT_DIM, Font.BOLD, 11);
        }

        /** 指示灯：绿 = 带电/运行，红 = 失电/停机 */
        private void lamp(Graphics2D g, int cx, int cy, boolean on) {
            Color c = on ? OK : BAD;
            g.setPaint(new java.awt.RadialGradientPaint(
                    new java.awt.geom.Point2D.Float(cx, cy), 10f,
                    new float[]{0f, 0.4f, 1f},
                    new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), 215),
                            new Color(c.getRed(), c.getGreen(), c.getBlue(), 80),
                            new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
            g.fillOval(cx - 10, cy - 10, 20, 20);
            g.setColor(new Color(26, 30, 34));
            g.fillOval(cx - 5, cy - 5, 10, 10);
            g.setColor(c);
            g.fillOval(cx - 4, cy - 4, 8, 8);
            g.setColor(new Color(255, 255, 255, 160));
            g.fillOval(cx - 2, cy - 3, 3, 3);
        }

        /** 开关（断路器）：静触头 + 动触头，合闸为竖线、分闸为斜线 */
        private void breaker(Graphics2D g, String id, int cx, int cy, boolean horizontal) {
            breaker(g, id, cx, cy, horizontal, false);
        }

        private void breaker(Graphics2D g, String id, int cx, int cy, boolean horizontal,
                             boolean labelLeft) {
            boolean closed = stateOf(id);
            Color c = closed ? OK : BAD;

            g.setStroke(new BasicStroke(2f));
            g.setColor(WIRE);
            if (horizontal) {
                g.drawLine(cx - 16, cy, cx - 7, cy);
                g.drawLine(cx + 7, cy, cx + 16, cy);
            } else {
                g.drawLine(cx, cy - 16, cx, cy - 7);
                g.drawLine(cx, cy + 7, cx, cy + 16);
            }

            g.setColor(c);
            if (horizontal) {
                g.fillOval(cx - 10, cy - 3, 6, 6);
                g.fillOval(cx + 4, cy - 3, 6, 6);
                if (closed) {
                    g.drawLine(cx - 7, cy, cx + 7, cy);
                } else {
                    g.drawLine(cx - 7, cy + 1, cx + 4, cy - 9);
                }
            } else {
                g.fillOval(cx - 3, cy - 10, 6, 6);
                g.fillOval(cx - 3, cy + 4, 6, 6);
                if (closed) {
                    g.drawLine(cx, cy - 7, cx, cy + 7);
                } else {
                    g.drawLine(cx + 1, cy - 7, cx - 9, cy + 4);
                }
            }
            if (horizontal) {
                text(g, id, labelLeft ? cx - 34 : cx + 10, cy - 12, TXT_DIM, Font.BOLD, 10);
            } else if (labelLeft) {
                java.awt.FontMetrics fm = g.getFontMetrics(g.getFont());
                text(g, id, cx - 9 - fm.stringWidth(id), cy + 4, TXT_DIM, Font.BOLD, 10);
            } else {
                text(g, id, cx + 9, cy + 4, TXT_DIM, Font.BOLD, 10);
            }
            hits.add(new Hit(new java.awt.Rectangle(cx - 15, cy - 15, 30, 30), id));
        }

        /** 双绕组变压器：上下两个圆 + 中间铁芯 */
        private void transformer(Graphics2D g, int cx, int cy, boolean live) {
            g.setColor(live ? WIRE : WIRE_DIM);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(cx - 14, cy - 30, 28, 28);
            g.drawOval(cx - 14, cy + 2, 28, 28);
            g.setStroke(new BasicStroke(1.4f));
            g.drawLine(cx - 9, cy - 1, cx + 9, cy - 1);
            g.drawLine(cx - 9, cy + 1, cx + 9, cy + 1);
            lamp(g, cx + 22, cy - 20, live);
        }

        /** 发电机：圆 + 正弦波 */
        private void generator(Graphics2D g, int cx, int cy, String tag, boolean live) {
            g.setColor(live ? WIRE : WIRE_DIM);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(cx - 20, cy - 20, 40, 40);
            java.awt.geom.Path2D sine = new java.awt.geom.Path2D.Double();
            double x0 = cx - 11;
            sine.moveTo(x0, cy);
            sine.quadTo(x0 + 5.5, cy - 10, x0 + 11, cy);
            sine.quadTo(x0 + 16.5, cy + 10, x0 + 22, cy);
            g.draw(sine);
            text(g, tag, cx - 14, cy + 36, live ? TXT : TXT_DIM, Font.BOLD, 11);
            lamp(g, cx + 30, cy - 16, live);
        }

        /** 柴油发电机组：机壳 + 三根气缸 + 灯 */
        private void diesel(Graphics2D g, String id, String name, int x, int y) {
            boolean run = stateOf(id);
            int w = 124;
            int h = 36;
            g.setColor(FACE);
            g.fillRoundRect(x, y, w, h, 7, 7);
            g.setColor(run ? OK : BAD);
            g.setStroke(new BasicStroke(1.4f));
            g.drawRoundRect(x, y, w, h, 7, 7);

            g.setColor(new Color(126, 136, 146));
            for (int i = 0; i < 3; i++) {
                g.fillRoundRect(x + 10 + i * 7, y + 10, 4, 16, 2, 2);
            }
            text(g, name, x + 38, y + 16, TXT, Font.BOLD, 11);
            text(g, run ? "运行" : "停机", x + 38, y + 30, run ? OK : TXT_DIM, Font.PLAIN, 10);
            lamp(g, x + w - 13, y + h / 2, run);
            hits.add(new Hit(new java.awt.Rectangle(x, y, w, h), id));
        }

        /** 蓄电池组符号 */
        private void battery(Graphics2D g, int x, int y, boolean live) {
            g.setColor(live ? WIRE : WIRE_DIM);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(x - 6, y, x, y);
            g.drawLine(x, y - 16, x, y + 16);
            g.drawLine(x + 8, y - 8, x + 8, y + 8);
            g.drawLine(x + 16, y - 16, x + 16, y + 16);
            g.drawLine(x + 24, y - 8, x + 24, y + 8);
            g.drawLine(x + 24, y, x + 30, y);
        }

        /** 直流系统：充电机 + 蓄电池读数 */
        private void dcBox(Graphics2D g, int x, int y, int w, int h, ElectricalGrid e, boolean live) {
            g.setColor(FACE);
            g.fillRoundRect(x, y, w, h, 7, 7);
            g.setColor(live ? OK : BAD);
            g.setStroke(new BasicStroke(1.4f));
            g.drawRoundRect(x, y, w, h, 7, 7);
            text(g, "直流 220 V · 蓄电池 " + String.format("%.0f", ElectricalGrid.BATTERY_AH)
                    + " Ah", x + 12, y + 20,
                    TXT, Font.BOLD, 11);
            String soc = e == null ? "--" : String.format("%.0f", e.getBatterySoc() * 100.0);
            String volt = e == null ? "--" : String.format("%.0f", e.getVoltDC());
            text(g, "电压 " + volt + " V    剩余电量 " + soc + " %", x + 12, y + 38,
                    TXT_DIM, Font.PLAIN, 10);
            lamp(g, x + w - 14, y + h / 2, live);
        }

        /** 外部主电网：铁塔图标 + 灯 + 引出线 */
        private void gridBlock(Graphics2D g, int x, int y, int w, int h, boolean live) {
            g.setColor(FACE);
            g.fillRoundRect(x, y, w, h, 8, 8);
            g.setColor(live ? OK : BAD);
            g.setStroke(new BasicStroke(1.4f));
            g.drawRoundRect(x, y, w, h, 8, 8);

            // 输电铁塔
            g.setColor(live ? WIRE : WIRE_DIM);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int px = x + 26;
            int py = y + 10;
            g.drawLine(px - 9, py + 26, px, py - 2);
            g.drawLine(px + 9, py + 26, px, py - 2);
            g.drawLine(px - 7, py + 7, px + 7, py + 7);
            g.drawLine(px - 9, py + 15, px + 9, py + 15);
            g.drawLine(px, py - 2, px, py + 26);

            text(g, "外部主电网", x + 50, y + 20, TXT, Font.BOLD, 11);
            text(g, "750 kV 系统 · 上网 / 受电", x + 50, y + 35, TXT_DIM, Font.PLAIN, 9);
            lamp(g, x + w - 14, y + h / 2, live);
            // 引出线（接到 750 kV 母线）
            wire(g, x + w / 2, y + h, x + w / 2, y + h + 19, live);
        }

        /** 母线所带负荷框（机组/泵组 + 运行台数 + 灯） */
        private void loadBox(Graphics2D g, int x, int y, int w, int h, String title,
                             boolean live, String line1, String line2) {
            g.setColor(FACE);
            g.fillRoundRect(x, y, w, h, 7, 7);
            g.setColor(live ? new Color(88, 108, 98) : new Color(112, 70, 70));
            g.setStroke(new BasicStroke(1.2f));
            g.drawRoundRect(x, y, w, h, 7, 7);
            text(g, title, x + 12, y + 14, TXT, Font.BOLD, 10);
            text(g, line1, x + 12, y + 27, new Color(206, 214, 224), Font.PLAIN, 9);
            text(g, line2, x + 12, y + 40, new Color(176, 186, 198), Font.PLAIN, 9);
            lamp(g, x + w - 13, y + 13, live);
        }

        /** "MCP 4/4" 这样的运行/总数摘要 */
        private String pumpSummary(String tag, java.util.ArrayList<Pump> list, int from, int to) {
            if (list == null) {
                return tag + " -/-";
            }
            int total = 0;
            int run = 0;
            for (int i = Math.max(0, from); i < list.size() && i < to; i++) {
                total++;
                Pump p = list.get(i);
                if (p != null && p.rpm > 0.1f) {
                    run++;
                }
            }
            return tag + " " + run + "/" + total;
        }

        private void wire(Graphics2D g, int x1, int y1, int x2, int y2, boolean live) {
            g.setColor(live ? WIRE : WIRE_DIM);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x1, y1, x2, y2);
        }

        private void legend(Graphics2D g) {
            int y = 610;
            lamp(g, 24, y - 4, true);
            text(g, "带电 / 运行", 40, y, TXT_DIM, Font.PLAIN, 10);
            lamp(g, 126, y - 4, false);
            text(g, "失电 / 停机", 142, y, TXT_DIM, Font.PLAIN, 10);

            // 开关图例
            g.setColor(WIRE);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(232, y - 12, 232, y + 4);
            g.setColor(OK);
            g.fillOval(229, y - 10, 6, 6);
            g.fillOval(229, y + 3, 6, 6);
            g.drawLine(232, y - 4, 232, y + 3);
            text(g, "开关合", 246, y, TXT_DIM, Font.PLAIN, 10);

            g.setColor(WIRE);
            g.drawLine(306, y - 12, 306, y + 4);
            g.setColor(BAD);
            g.fillOval(303, y - 10, 6, 6);
            g.fillOval(303, y + 3, 6, 6);
            g.drawLine(307, y - 4, 298, y + 4);
            text(g, "开关分", 320, y, TXT_DIM, Font.PLAIN, 10);

            text(g, "点击图上的开关符号 / 柴油机方块可直接操作", 396, y, TXT_DIM, Font.PLAIN, 10);
        }

        private void text(Graphics2D g, String s, int x, int y, Color c, int style, int size) {
            g.setFont(Fonts.text("Dialog", style, size));
            g.setColor(c);
            g.drawString(s, x, y);
        }

        private String fmtKV750(double v) {
            return String.format("%.0f kV", v);
        }

        private String fmtKV6(double v) {
            return String.format("%.2f kV", v);
        }

        private String fmtMW(double v) {
            return String.format("%.1f MW", v);
        }
    }

    /** 可点击区域 */
    private static final class Hit {
        final java.awt.Rectangle rect;
        final String id;
        Hit(java.awt.Rectangle r, String id) {
            this.rect = r;
            this.id = id;
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
