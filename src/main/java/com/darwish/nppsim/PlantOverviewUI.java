package com.darwish.nppsim;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * 全厂总图（2026-11-12 新增）。
 *
 * <p>按参考图的做法：**一张图看全厂**，按系统分色，关键点叠加实时数值。
 * 六个分区，流程从左到右、上下两排：</p>
 *
 * <pre>
 *   一回路（反应堆/汽包/主泵）  →  蒸汽与汽机（主汽门/TG/凝汽器）  →  电气（20kV→750kV/6kV/380V/DC）
 *          ↑                                                                  │
 *   除氧给水（除氧器/给水泵）  ←  凝结水（凝汽器/凝结水泵/精处理/LP加热器） ←  循环水/热阱
 * </pre>
 *
 * <p>配色沿用参考图的"按介质/系统分色"：饱和蒸汽—红，给水/凝结水—蓝，一回路—棕，
 * 电气—绿，循环水—青。厂内未建模的系统（ECCS/通风/照明/辐射监测）不在图上冒充，
 * 避免"画了但没有数"。</p>
 */
public class PlantOverviewUI extends JFrame implements UIUpdateable {

    private final Canvas canvas = new Canvas();

    public PlantOverviewUI() {
        setTitle(Localization.t("Plant Overview"));
        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        getContentPane().setBackground(UiTheme.BG);
        setLayout(new java.awt.BorderLayout());

        WindowMenu.install(this);
        PageLayout.installAuto(this, "PlantOverviewUI");

        JScrollPane sp = new JScrollPane(canvas);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        sp.getHorizontalScrollBar().setUnitIncrement(24);
        add(sp, java.awt.BorderLayout.CENTER);

        setSize(1440, 900);
        setMinimumSize(new Dimension(1100, 720));
        setLocationRelativeTo(null);
    }

    @Override
    public void update() {
        if (!isVisible()) {
            return;
        }
        java.awt.EventQueue.invokeLater(() -> {
            try {
                canvas.repaint();
            } catch (Exception ignored) {
                // 单窗口刷新异常不得拖垮全局更新
            }
        });
    }

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

    // ================================================================ 画布

    private static final class Canvas extends JPanel {

        private static final int W = 1600, H = 670;

        Canvas() {
            setBackground(UiTheme.BG);
            setPreferredSize(new Dimension(W, H));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            title(g);
            // 上排
            zone(g, 30, 70, 420, 240, "一回路", C_PRIMARY);
            primaryZone(g, 30, 70, 420, 240);

            zone(g, 500, 70, 460, 240, "蒸汽与汽轮机", C_STEAM);
            steamZone(g, 500, 70, 460, 240);

            zone(g, 1010, 70, 560, 240, "电气系统", C_ELEC);
            elecZone(g, 1010, 70, 560, 240);

            // 下排
            zone(g, 30, 370, 420, 240, "除氧与给水", C_FEED);
            feedZone(g, 30, 370, 420, 240);

            zone(g, 500, 370, 460, 240, "凝结水", C_COND);
            condZone(g, 500, 370, 460, 240);

            zone(g, 1010, 370, 560, 240, "循环水与热阱", C_COOL);
            coolZone(g, 1010, 370, 560, 240);

            // ---- 分区之间的主流程 ----
            arrow(g, 450, 190, 500, 190, C_STEAM, "饱和蒸汽 " + fmt(steamPressure(), 2) + " MPa");
            arrow(g, 960, 190, 1010, 190, C_ELEC, "发电 " + fmt(genMW(), 0) + " MW");
            arrow(g, 730, 310, 730, 370, C_STEAM, "乏汽");
            arrow(g, 960, 490, 1010, 490, C_COOL, "循环水");
            arrow(g, 500, 490, 450, 490, C_COND, "凝结水");
            arrow(g, 240, 370, 240, 310, C_FEED, "给水 " + fmt(feedFlow(), 0) + " kg/s");

            legend(g);
            g.dispose();
        }

        // ---------- 各分区内容 ----------

        private void primaryZone(Graphics2D g, int x, int y, int w, int h) {
            MCC mcc = NPPSim.mcc;
            MCC.SeparatorDrum d1 = mcc == null ? null : mcc.drum1;
            MCC.SeparatorDrum d2 = mcc == null ? null : mcc.drum2;
            int lx = x + 14, ly = y + 34;
            line(g, lx, ly, "堆芯热功率", fmt(coreMW(), 0) + " MW");
            line(g, lx, ly + 20, "反应性", String.format("%+.0f pcm", coreReactivity() * 1e5));
            line(g, lx, ly + 40, "汽包 A", fmt(d1 == null ? 0 : d1.getPressure(), 2) + " MPa / "
                    + fmt(d1 == null ? 0 : d1.getWaterLevel(), 0) + " %");
            line(g, lx, ly + 60, "汽包 B", fmt(d2 == null ? 0 : d2.getPressure(), 2) + " MPa / "
                    + fmt(d2 == null ? 0 : d2.getWaterLevel(), 0) + " %");
            line(g, lx, ly + 80, "产汽量", fmt((d1 == null ? 0 : d1.getSteamProduction())
                    + (d2 == null ? 0 : d2.getSteamProduction()), 0) + " kg/s");

            // 主循环泵：8 台，A 段 4 / B 段 4
            int on = 0, total = 0;
            double flow = 0;
            if (mcc != null && mcc.mcp != null) {
                for (Pump p : mcc.mcp) {
                    total++;
                    if (p.isActive()) {
                        on++;
                    }
                    flow += p.getFlowRate();
                }
            }
            line(g, lx, ly + 100, "主循环泵", on + "/" + total + " 台   " + fmt(flow, 0) + " kg/s");

            // 示意图：汽包 + 下降管 + 堆芯
            int bx = x + w - 150, by = y + 50;
            box(g, bx, by, 120, 30, "汽包 A/B", true);
            box(g, bx, by + 140, 120, 34, "反应堆", coreMW() > 1);
            line2(g, bx + 60, by + 30, bx + 60, by + 140, C_PRIMARY);
            text(g, "下降管 / 主泵", bx - 6, by + 92, UiTheme.dimText(), Font.PLAIN, 10);
            text(g, "燃料通道 1661", bx + 18, by + 132, UiTheme.dimText(), Font.PLAIN, 10);
        }

        private void steamZone(Graphics2D g, int x, int y, int w, int h) {
            int lx = x + 14, ly = y + 34;
            line(g, lx, ly, "主蒸汽母管", fmt(steamPressure(), 2) + " MPa");
            line(g, lx, ly + 20, "主汽门开度", String.format("%.0f %%", valvePos() * 100));
            line(g, lx, ly + 40, "进汽流量", fmt(steamFlow(), 0) + " kg/s");
            line(g, lx, ly + 60, "TG-1", fmt(gen1(), 0) + " MW  " + fmt(rpm1(), 0) + " rpm");
            line(g, lx, ly + 80, "TG-2", fmt(gen2(), 0) + " MW  " + fmt(rpm2(), 0) + " rpm");
            line(g, lx, ly + 100, "凝汽器真空", fmt(vac1(), 1) + " kPa");

            int bx = x + w - 150, by = y + 50;
            box(g, bx, by, 120, 26, "主汽门", valvePos() > 0.01);
            box(g, bx, by + 60, 120, 26, "TG-1 / TG-2", genMW() > 1);
            box(g, bx, by + 120, 120, 26, "凝汽器", true);
            line2(g, bx + 60, by + 26, bx + 60, by + 60, C_STEAM);
            line2(g, bx + 60, by + 86, bx + 60, by + 120, C_STEAM);
        }

        private void elecZone(Graphics2D g, int x, int y, int w, int h) {
            ElectricalGrid e = NPPSim.grid;
            int lx = x + 14, ly = y + 34;
            if (e == null) {
                line(g, lx, ly, "电气系统", "未初始化");
                return;
            }
            line(g, lx, ly, "发电 / 厂用 / 上网",
                    fmt(e.getGenerationMW(), 0) + " / " + fmt(e.getAuxLoadMW(), 1) + " / "
                            + fmt(e.getExportMW(), 0) + " MW");
            line(g, lx, ly + 20, "20 kV 发电机出口", live(e.has20()) + "  " + fmt(e.getVolt20(), 1) + " kV");
            line(g, lx, ly + 40, "750 kV 主电网", live(e.has750()) + "  " + fmt(e.getVolt750(), 0) + " kV");
            line(g, lx, ly + 60, "110 kV 辅助", live(e.has110()) + "  " + fmt(e.getVolt110(), 1) + " kV");
            line(g, lx, ly + 80, "6 kV 常用 A / B",
                    live(e.has6A()) + "/" + live(e.has6B()) + "  " + fmt(e.getLoad6A(), 1) + " / "
                            + fmt(e.getLoad6B(), 1) + " MW");
            line(g, lx, ly + 100, "6 kV 应急 A / B",
                    live(e.has6Ae()) + "/" + live(e.has6Be()) + "  " + "柴油机 "
                            + fmt(e.getDieselOutputA() * ElectricalGrid.DIESEL_MW, 1) + " / "
                            + fmt(e.getDieselOutputB() * ElectricalGrid.DIESEL_MW, 1) + " MW");
            line(g, lx, ly + 120, "380 V", live(e.has380()) + "  " + fmt(e.getVolt04(), 0) + " V");
            line(g, lx, ly + 140, "直流 220 V", live(e.hasDC()) + "  " + fmt(e.getVoltDC(), 0) + " V  蓄电池 "
                    + fmt(e.getBatterySoc() * 100, 0) + " %");
            line(g, lx, ly + 160, "频率", fmt(e.getFrequencyHz(), 2) + " Hz");

            int bx = x + w - 170, by = y + 40;
            box(g, bx, by, 140, 26, "主变 20/750 kV", e.has750());
            box(g, bx, by + 70, 140, 26, "厂用变 750/6 kV", e.has6A() && e.has6B());
            box(g, bx, by + 140, 140, 26, "6 kV 母线段", e.has6A() && e.has6B());
        }

        private void feedZone(Graphics2D g, int x, int y, int w, int h) {
            int lx = x + 14, ly = y + 34;
            double p = 0, lvl = 0, t = 0;
            int n = 0;
            if (NPPSim.dearators != null) {
                for (Dearator d : NPPSim.dearators) {
                    p += d.getPressure();
                    lvl += d.getWaterLevel();
                    t += d.getWaterTemperature();
                    n++;
                }
            }
            n = Math.max(1, n);
            line(g, lx, ly, "除氧器压力（4 台平均）", fmt(p / n, 2) + " MPa");
            line(g, lx, ly + 20, "除氧器水位 / 水温", fmt(lvl / n, 0) + " % / " + fmt(t / n, 0) + " °C");
            line(g, lx, ly + 40, "给水泵流量", fmt(feedFlow(), 0) + " kg/s");
            line(g, lx, ly + 60, "给水母管压力", fmt(fwHeaderPressure(), 2) + " MPa");
            line(g, lx, ly + 80, "给水温度", fmt(feedTemp(), 0) + " °C");

            int bx = x + w - 160, by = y + 50;
            box(g, bx, by, 130, 26, "除氧器 ×4", true);
            box(g, bx, by + 80, 130, 26, "给水泵 ×7", feedFlow() > 1);
            line2(g, bx + 65, by + 26, bx + 65, by + 80, C_FEED);
        }

        private void condZone(Graphics2D g, int x, int y, int w, int h) {
            int lx = x + 14, ly = y + 34;
            line(g, lx, ly, "凝汽器压力 A / B", fmt(vac1(), 1) + " / " + fmt(vac2(), 1) + " kPa");
            line(g, lx, ly + 20, "凝结水母管压力", fmt(condHeaderPressure(), 2) + " MPa");
            line(g, lx, ly + 40, "精处理通过量", fmt(filterKgs(), 0) + " kg/s");
            line(g, lx, ly + 60, "凝结水温度", fmt(condTemp(), 0) + " °C");

            int bx = x + w - 170, by = y + 40;
            box(g, bx, by, 140, 26, "凝汽器 ×8", true);
            box(g, bx, by + 70, 140, 26, "凝结水泵", filterKgs() > 1);
            box(g, bx, by + 140, 140, 26, "精处理 / LP-1", true);
            line2(g, bx + 70, by + 26, bx + 70, by + 70, C_COND);
            line2(g, bx + 70, by + 96, bx + 70, by + 140, C_COND);
        }

        private void coolZone(Graphics2D g, int x, int y, int w, int h) {
            int lx = x + 14, ly = y + 34;
            double cw = NPPSim.CONDENSER_CW_M3S * 2.0;
            line(g, lx, ly, "循环水流量（两台机）", fmt(cw, 0) + " m³/s");
            line(g, lx, ly + 20, "凝汽器压力 A / B", fmt(vac1(), 1) + " / " + fmt(vac2(), 1) + " kPa");
            line(g, lx, ly + 40, "凝结水排水阀", valveText(NPPSim.condensateDrainValve));
            line(g, lx, ly + 60, "给水排水阀", valveText(NPPSim.feedwaterDrainValve));

            int bx = x + w - 190, by = y + 40;
            box(g, bx, by, 160, 26, "循环水泵", true);
            box(g, bx, by + 80, 160, 26, "冷却塔 / 河", true);
            box(g, bx, by + 150, 160, 26, "排水 → 除盐水箱", true);
            line2(g, bx + 80, by + 26, bx + 80, by + 80, C_COOL);
        }

        private void legend(Graphics2D g) {
            int y = 630, x = 30;
            swatch(g, x, y, C_STEAM, "饱和蒸汽");
            swatch(g, x + 130, y, C_FEED, "给水");
            swatch(g, x + 240, y, C_COND, "凝结水");
            swatch(g, x + 360, y, C_PRIMARY, "一回路");
            swatch(g, x + 470, y, C_ELEC, "电气");
            swatch(g, x + 570, y, C_COOL, "循环水");
            text(g, "数值为实时值；红色文字表示该段失电或该设备停机", x + 680, y + 5,
                    UiTheme.dimText(), Font.PLAIN, 11);
        }

        // ---------- 绘制小工具 ----------

        private void title(Graphics2D g) {
            text(g, Localization.t("Plant Overview"), 30, 42, UiTheme.plateText(), Font.BOLD, 18);
            text(g, "一回路 → 蒸汽 → 汽机 → 凝汽器 → 凝结水 → 除氧给水 → 汽包；右侧为电气系统",
                    210, 42, UiTheme.dimText(), Font.PLAIN, 12);
        }

        private void zone(Graphics2D g, int x, int y, int w, int h, String name, Color accent) {
            g.setColor(tint(accent, 0.10f));
            g.fillRoundRect(x, y, w, h, 14, 14);
            g.setColor(accent);
            g.setStroke(new BasicStroke(1.6f));
            g.drawRoundRect(x, y, w, h, 14, 14);
            text(g, name, x + 12, y + 20, accent, Font.BOLD, 13);
        }

        private void line(Graphics2D g, int x, int y, String name, String value) {
            text(g, name, x, y, UiTheme.dimText(), Font.PLAIN, 11);
            text(g, value, x + 168, y, UiTheme.plateText(), Font.BOLD, 11);
        }

        /** 分区内的设备方块 */
        private void box(Graphics2D g, int x, int y, int w, int h, String label, boolean live) {
            g.setColor(live ? new Color(52, 74, 54) : new Color(78, 52, 52));
            g.fillRoundRect(x, y, w, h, 6, 6);
            g.setColor(live ? new Color(140, 220, 140) : new Color(200, 130, 130));
            g.drawRoundRect(x, y, w, h, 6, 6);
            text(g, label, x + 8, y + h / 2 + 4, UiTheme.plateText(), Font.PLAIN, 11);
        }

        private void line2(Graphics2D g, int x1, int y1, int x2, int y2, Color c) {
            g.setColor(c);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x1, y1, x2, y2);
        }

        /** 带箭头与说明的主流程线（说明带底衬，避免压在分区边框上看不清） */
        private void arrow(Graphics2D g, int x1, int y1, int x2, int y2, Color c, String label) {
            g.setColor(c);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x1, y1, x2, y2);
            double ang = Math.atan2(y2 - y1, x2 - x1);
            int ax = x2, ay = y2;
            int s = 9;
            g.fillPolygon(new int[]{ax, (int) (ax - s * Math.cos(ang - 0.4)), (int) (ax - s * Math.cos(ang + 0.4))},
                    new int[]{ay, (int) (ay - s * Math.sin(ang - 0.4)), (int) (ay - s * Math.sin(ang + 0.4))}, 3);

            Font f = Fonts.text("Dialog", Font.BOLD, 10);
            g.setFont(f);
            java.awt.FontMetrics fm = g.getFontMetrics(f);
            int tw = fm.stringWidth(label);
            int mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
            boolean vertical = Math.abs(x2 - x1) < Math.abs(y2 - y1);
            int tx = vertical ? mx + 9 : mx - tw / 2;
            int ty = vertical ? my + 4 : y1 - 8;
            // 底衬
            g.setColor(new Color(24, 24, 26, 220));
            g.fillRoundRect(tx - 4, ty - 11, tw + 8, 15, 5, 5);
            text(g, label, tx, ty, brighten(c), Font.BOLD, 10);
        }

        /** 把颜色提亮到能在橄榄色背景上读清 */
        private static Color brighten(Color c) {
            return new Color(
                    Math.min(255, (int) (c.getRed() * 1.25)),
                    Math.min(255, (int) (c.getGreen() * 1.25)),
                    Math.min(255, (int) (c.getBlue() * 1.25)));
        }

        private void swatch(Graphics2D g, int x, int y, Color c, String name) {
            g.setColor(c);
            g.setStroke(new BasicStroke(3f));
            g.drawLine(x, y, x + 24, y);
            text(g, name, x + 30, y + 4, UiTheme.dimText(), Font.PLAIN, 11);
        }

        private void text(Graphics2D g, String s, int x, int y, Color c, int style, int size) {
            g.setFont(Fonts.text("Dialog", style, size));
            g.setColor(c);
            g.drawString(s, x, y);
        }

        private static Color tint(Color c, float f) {
            return new Color(
                    Math.min(255, (int) (c.getRed() * f)),
                    Math.min(255, (int) (c.getGreen() * f)),
                    Math.min(255, (int) (c.getBlue() * f)),
                    c.getAlpha());
        }

        // ---------- 读数（全部做空值保护） ----------

        private static String fmt(double v, int dec) {
            if (!Double.isFinite(v)) {
                return "---";
            }
            return String.format("%." + dec + "f", v);
        }

        private static String live(boolean on) {
            return on ? "带电" : "失电";
        }

        private static String valveText(WaterValve v) {
            return v == null ? "---" : String.format("%.0f %%", v.getPosition() * 100);
        }

        private static double coreMW() {
            return NPPSim.core == null ? 0 : NPPSim.core.getThermalPower();
        }

        private static double coreReactivity() {
            return NPPSim.core == null ? 0 : NPPSim.core.getReactivity();
        }

        private static double steamPressure() {
            return NPPSim.steamPiping == null ? 0 : NPPSim.steamPiping.getPressure();
        }

        private static double valvePos() {
            try {
                return NPPSim.TG1InletValves.get(0).getPosition();
            } catch (Exception e) {
                return 0;
            }
        }

        private static double steamFlow() {
            try {
                double sum = 0;
                for (SteamValve v : NPPSim.TG1InletValves) {
                    sum += v.getFlowRate();
                }
                for (SteamValve v : NPPSim.TG2InletValves) {
                    sum += v.getFlowRate();
                }
                return sum;
            } catch (Exception e) {
                return 0;
            }
        }

        private static double gen1() {
            return NPPSim.tg1 == null ? 0 : NPPSim.tg1.getGeneratorLoad();
        }

        private static double gen2() {
            return NPPSim.tg2 == null ? 0 : NPPSim.tg2.getGeneratorLoad();
        }

        private static double genMW() {
            return gen1() + gen2();
        }

        private static double rpm1() {
            return NPPSim.tg1 == null ? 0 : NPPSim.tg1.getRpm();
        }

        private static double rpm2() {
            return NPPSim.tg2 == null ? 0 : NPPSim.tg2.getRpm();
        }

        private static double vac1() {
            return NPPSim.tg1 == null ? 0 : NPPSim.tg1.condenser.getPressure() * 1000.0;
        }

        private static double vac2() {
            return NPPSim.tg2 == null ? 0 : NPPSim.tg2.condenser.getPressure() * 1000.0;
        }

        private static double feedFlow() {
            double sum = 0;
            if (NPPSim.mainFeedwaterPumps != null) {
                for (Pump p : NPPSim.mainFeedwaterPumps) {
                    sum += p.getFlowRate();
                }
            }
            return sum;
        }

        private static double fwHeaderPressure() {
            return NPPSim.mainFWPressureHeader == null ? 0 : NPPSim.mainFWPressureHeader.getPressure();
        }

        private static double feedTemp() {
            if (NPPSim.dearators == null || NPPSim.dearators.isEmpty()) {
                return 0;
            }
            return NPPSim.dearators.get(0).getWaterTemperature();
        }

        private static double condHeaderPressure() {
            return NPPSim.condensateHeader == null ? 0 : NPPSim.condensateHeader.getPressure();
        }

        private static double filterKgs() {
            return NPPSim.pcsCondensateFilter == null ? 0 : NPPSim.pcsCondensateFilter.getThroughputKgs();
        }

        private static double condTemp() {
            if (NPPSim.condensateHeader1 == null) {
                return 0;
            }
            return NPPSim.condensateHeader1.getWaterTemperature();
        }
    }

    // ================================================================ 配色

    private static final Color C_PRIMARY = new Color(205, 160, 110);
    private static final Color C_STEAM = new Color(226, 108, 108);
    private static final Color C_ELEC = new Color(130, 215, 130);
    private static final Color C_FEED = new Color(120, 175, 230);
    private static final Color C_COND = new Color(110, 200, 205);
    private static final Color C_COOL = new Color(120, 205, 180);
}
