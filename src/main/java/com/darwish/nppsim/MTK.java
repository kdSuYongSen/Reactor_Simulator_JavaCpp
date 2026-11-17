package com.darwish.nppsim;

import java.awt.Color;
import java.util.HashMap;
import javax.swing.JTextField;

public class MTK extends javax.swing.JPanel {
    static Color CPSONCOLOR = Color.GREEN.brighter();
    static Color CPSOFFCOLOR = Color.GREEN.darker().darker();
    static Color CHANNELOFFCOLOR; 
    /** 干涸通道的堆芯图底色（与 Channels.FuelChannel.DRYOUT_COLOR 同色） */
    static final Color DRYOUT_COLOR = new Color(232, 64, 32);

    /* ---- 绿棒（CPS）的燃料状态三色（2026-11-02） ----
     * 深绿 = 还没参与反应；浅绿 = 正在参与反应；灰 = 燃料消耗到接近卸料燃耗。 */
    static final Color CPS_DARK_GREEN = new Color(0, 92, 0);
    static final Color CPS_LIGHT_GREEN = new Color(126, 226, 126);
    static final Color CPS_SPENT_GRAY = new Color(152, 152, 152);

    private short rowNumber = 0;
    private int[] fuelCPSCount = {0, 0};
    private HashMap<Channel, JTextField> channelMtkBinding;
    /** 通道在 56×56 堆芯阵列中的坐标，供 CPS 的"区域平均"取样用 */
    private final HashMap<Channel, int[]> channelCell = new HashMap<>();
    private boolean positive = true;
    private float threshold = 0.15f;

    /* ==================== 组件池（启动加载阶段预构建） ====================
     * 堆芯图共 50×50=2500 个格子，其中通道格 1884 个（燃料 1661 + CPS 223）、
     * 空位格 616 个（由 coreMap.csv 可见区域统计得到）。逐个 new 组件约 0.4ms，
     * 整图构造耗时 ~1s，过去发生在点击 Start 之后，造成主窗口长时间无响应。
     *
     * 现在改为：启动加载（Loader 显示 "Loading..." ）期间，在事件线程（EDT）上
     * 以每批 ≤60ms 的粒度分批预建组件放入本池；构建主窗口时直接取用，
     * 把这段耗时从"点 Start 之后"挪到"加载阶段"，Start 后主窗口立即可见。
     *
     * 线程模型：池只在 EDT 上读写（预建分批、取用/现场补齐都发生在 EDT），无锁竞争。
     * 池取完后自动退化为现场新建（行为与旧版一致，保证任何路径都正确）。
     * 返回主菜单时（UI.formWindowClosed）会再次 requestPreload() 补货，供下一局使用。
     * ================================================================ */
    private static final int CELL_POOL_TARGET = 1900;    // 通道格目标数（实测需 1884）
    private static final int FILLER_POOL_TARGET = 650;   // 空位格目标数（实测需 616）
    private static final long CHUNK_BUDGET_MS = 60;      // 每批最多占用 EDT 的毫秒数
    private static final java.util.ArrayDeque<JTextField> CELL_POOL = new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<javax.swing.JPanel> FILLER_POOL = new java.util.ArrayDeque<>();
    private static boolean preloading = false;

    /** 请求预建组件池（幂等；池已满则忽略）。可在任意线程调用。 */
    public static void requestPreload() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (preloading) {
                return;
            }
            if (CELL_POOL.size() >= CELL_POOL_TARGET && FILLER_POOL.size() >= FILLER_POOL_TARGET) {
                return;
            }
            preloading = true;
            pumpChunk();
        });
    }

    /** 单批预建：占满 CHUNK_BUDGET_MS 后让出 EDT（重新排队），保持加载窗口可响应 */
    private static void pumpChunk() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            long deadline = System.currentTimeMillis() + CHUNK_BUDGET_MS;
            do {
                if (CELL_POOL.size() < CELL_POOL_TARGET) {
                    CELL_POOL.add(newCell());
                } else if (FILLER_POOL.size() < FILLER_POOL_TARGET) {
                    FILLER_POOL.add(newFiller());
                } else {
                    preloading = false;
                    return;
                }
            } while (System.currentTimeMillis() < deadline);
            pumpChunk();
        });
    }

    /** 通道格：只做与通道无关的固定配置；颜色/描边在绑定时按通道设置 */
    private static JTextField newCell() {
        JTextField f = new JTextField();
        f.setEditable(false);
        f.setFocusable(false);
        // 尺寸只是首选值：面板用 GridLayout(50,50)，格子会被拉伸到
        // 面板尺寸/50，因此放大面板格子就跟着变大（且始终在窗口内）。
        f.setPreferredSize(new java.awt.Dimension(9, 9));
        f.setMinimumSize(new java.awt.Dimension(2, 2));
        f.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return f;
    }

    /** 空位格（堆芯圆形之外） */
    private static javax.swing.JPanel newFiller() {
        javax.swing.JPanel p = new javax.swing.JPanel();
        p.setBackground(UI.BACKGROUND.darker());
        p.setPreferredSize(new java.awt.Dimension(9, 9));
        p.setMinimumSize(new java.awt.Dimension(2, 2));
        p.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return p;
    }

    // 池使用统计（用于日志核对预加载效果）
    private int poolCells = 0, freshCells = 0, poolFillers = 0, freshFillers = 0;

    private JTextField takeCell() {
        JTextField f = CELL_POOL.poll();
        if (f != null) {
            poolCells++;
            return f;
        }
        freshCells++;
        return newCell();
    }

    private javax.swing.JPanel takeFiller() {
        javax.swing.JPanel p = FILLER_POOL.poll();
        if (p != null) {
            poolFillers++;
            return p;
        }
        freshFillers++;
        return newFiller();
    }

    public MTK() {
        initComponents();
        this.setVisible(true);
        if (NPPSim.core == null) { //return when the IDE tries to create this object as the mcc will not be initialized 
            return;
        }
        CHANNELOFFCOLOR = new FuelChannel().uiData.UIBackgroundColor;
        channelMtkBinding = new HashMap<>();
        while (rowNumber != 50) {
            createRow();
        }
        System.out.println(String.format(
                "[MTK] 堆芯图构建完成：通道格 %d（池取用 %d / 现场新建 %d），空位格 %d（池取用 %d / 现场新建 %d）",
                poolCells + freshCells, poolCells, freshCells,
                poolFillers + freshFillers, poolFillers, freshFillers));
        channelMtkBinding.forEach((channel, light) -> {
            if (channel instanceof FuelChannel) {
                fuelCPSCount[0]++;
            } else if (channel instanceof CPSChannel) {
                fuelCPSCount[1]++;
            }
        });
        
    }

    /* ==================== 中子通量图（移植自原始 jar，2026-10-18；2026-11-02 改上色） ====================
     * 原始 RBMK-1500-Simulator.jar（2023-10-01 版）的 MTK 是二值上色：
     * 分别求燃料 / CPS 的平均中子数，正极性下 通量 > 平均×(1+阈值) 高亮、其余背景色，
     * 负极性反过来；燃料高亮=白、背景=淡黄，CPS 高亮=亮绿、背景=暗绿。
     *
     * 2026-11-02 按用户定义改成连续渐变（见 update() 里的详细说明）：
     *   · 燃料格（淡黄）按中子数量从原色渐变到白色；
     *   · CPS 格（绿棒）显示所在区域燃料的平均状态：深绿（未参与反应）→
     *     浅绿（正在反应）→ 灰（燃料消耗）。
     * 阈值滑杆与正/负极性按钮保留，仍控制燃料格渐变的跨度与方向。
     *
     * 结构与性能上的既有修复保持不变：
     *   · 行容器用 GridLayout(1,50) 而非 FlowLayout（后者会换行、形成"重影方格"）；
     *   · 组件池预加载（EDT 分片）与"颜色不变则不 setBackground"的变化检测。
     * ================================================================================ */

    public void update() {
        try {
            while (NPPSim.core1) {
                Thread.sleep(1);
            }
        } catch (InterruptedException e) {

        }
        /* ==================== 上色规则（2026-11-02 重做） ====================
         *
         * 原版（以及本文件此前的移植）是**二值**上色：通量高于均值×(1+阈值) 的白块，
         * 其余淡黄；CPS 同样只有亮绿/暗绿两档。问题有两个：
         *   1) 燃料格只有"亮/不亮"，看不出通量高低，中心区和边缘区一样是死板的两种颜色；
         *   2) CPS 格（绿棒）原本也按通量二值上色，但它的意义应该是**所在区域的燃料状态**。
         *
         * 现在的规则（按用户定义）：
         *   · 燃料格 = 中子数量，从少到多：原色（淡黄）→ 白色，连续渐变；
         *     阈值滑杆当对比度用（跨度 S = 0.10 + 2×阈值），极性按钮翻转方向；
         *   · CPS 格 = 该位置周围 5×5 区域内燃料通道的平均状态：
         *       深绿  = 还没参与反应（无功率 / 新燃料）
         *       浅绿  = 正在参与反应（按相对功率连续加深）
         *       灰    = 燃料消耗程度（按累计比燃耗，浅绿→灰渐变）
         *   · 干涸通道仍用红色压过一切（安全相关，优先于通量着色）。
         * ========================================================================= */
        final java.util.ArrayList<java.util.ArrayList<Channel>> core = NPPSim.core.coreArray;
        final int gridN = 56;
        final double[] activation = new double[gridN * gridN];
        final double[] depletion = new double[gridN * gridN];

        // ---- 1. 收集燃料通道的"反应程度 / 消耗程度"，并求通量均值 ----
        double sumFuel = 0.0;
        int nFuel = 0;
        for (int y = 0; y < core.size() && y < gridN; y++) {
            java.util.ArrayList<Channel> row = core.get(y);
            for (int x = 0; x < row.size() && x < gridN; x++) {
                Channel c = row.get(x);
                if (c instanceof FuelChannel) {
                    FuelChannel fc = (FuelChannel) c;
                    sumFuel += c.getFastNeutronCount();
                    nFuel++;
                    activation[y * gridN + x] = fc.getActivation01();
                    depletion[y * gridN + x] = fc.getDepletion01();
                }
            }
        }
        final double meanFuel = nFuel > 0 ? sumFuel / nFuel : 0.0;
        final boolean pos = positive;
        final double thr = Math.max(1.0e-3, threshold);

        // ---- 2. 逐格上色 ----
        for (java.util.Map.Entry<Channel, JTextField> entry : channelMtkBinding.entrySet()) {
            Channel channel = entry.getKey();
            JTextField light = entry.getValue();
            java.awt.Color desired;
            if (channel instanceof FuelChannel) {
                FuelChannel fc = (FuelChannel) channel;
                if (fc.isDryout()) {
                    desired = DRYOUT_COLOR;
                } else if (meanFuel <= 0.0) {
                    desired = CHANNELOFFCOLOR;
                } else {
                    /* 渐变跨度 S：均值 ±S 映射到 0..1。
                     * 阈值滑杆（0.005~0.25）在这里当"对比度"用：S = 0.10 + 2×阈值，
                     * 默认 0.15 → S=0.40。直接用阈值本身（±0.15）的话，径向功率形状
                     * （0.55~1.45）只有中间很窄一圈落在渐变带里，中心会糊成一大片纯白，
                     * 看上去仍是原来的二值图。 */
                    double span = 0.10 + 2.0 * thr;
                    double t = (channel.getFastNeutronCount() / meanFuel - (1.0 - span)) / (2.0 * span);
                    t = Math.max(0.0, Math.min(1.0, t));
                    if (!pos) {
                        t = 1.0 - t;   // 负极性：低通量反而更亮（与原版极性语义一致）
                    }
                    desired = blend(CHANNELOFFCOLOR, java.awt.Color.WHITE, t);
                }
            } else if (channel instanceof CPSChannel) {
                int[] xy = channelCell.get(channel);
                double act = Double.NaN, dep = Double.NaN;
                if (xy != null) {
                    double sa = 0.0, sd = 0.0;
                    int n = 0;
                    for (int dy = -2; dy <= 2; dy++) {
                        int yy = xy[1] + dy;
                        if (yy < 0 || yy >= gridN || yy >= core.size()) {
                            continue;
                        }
                        for (int dx = -2; dx <= 2; dx++) {
                            int xx = xy[0] + dx;
                            if (xx < 0 || xx >= gridN || xx >= core.get(yy).size()) {
                                continue;
                            }
                            int idx = yy * gridN + xx;
                            Channel nb = core.get(yy).get(xx);
                            if (nb instanceof FuelChannel) {
                                sa += activation[idx];
                                sd += depletion[idx];
                                n++;
                            }
                        }
                    }
                    if (n > 0) {
                        act = sa / n;
                        dep = sd / n;
                    }
                }
                if (Double.isNaN(act)) {
                    act = 0.0;
                    dep = 0.0;
                }
                // 深绿（未参与反应）→ 浅绿（正在反应）→ 灰（燃料消耗）
                java.awt.Color reacting = blend(CPS_DARK_GREEN, CPS_LIGHT_GREEN, act);
                desired = blend(reacting, CPS_SPENT_GRAY, dep);
            } else {
                continue;
            }
            if (!desired.equals(light.getBackground())) {
                light.setBackground(desired);
            }
        }
    }

    /** 线性混色，t 夹在 0..1 */
    private static java.awt.Color blend(java.awt.Color a, java.awt.Color b, double t) {
        if (t <= 0.0) {
            return a;
        }
        if (t >= 1.0) {
            return b;
        }
        return new java.awt.Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private javax.swing.JPanel createRow() {
        javax.swing.JPanel row = new javax.swing.JPanel();
        row.setBackground(UI.BACKGROUND.darker());
        row.setMaximumSize(new java.awt.Dimension(1000, 5));
        row.setMinimumSize(new java.awt.Dimension(6, 5));
        row.setPreferredSize(new java.awt.Dimension(6, 5));
        row.setRequestFocusEnabled(false);
        // 修复"方格重复"：原先用 FlowLayout，50 个 6px 格共 300px 超出行的内宽时会换行，
        // 换行后的几个格子居中显示在行底，形成地图中部的重影方格。
        // GridLayout(1,50) 强制 50 格均分整行宽度，永不换行（格子自动拉伸）。
        row.setLayout(new java.awt.GridLayout(1, 50, 0, 0));
        for (short i = 50; i > 0; i--) {
            Channel currentChannel = NPPSim.core.coreArray.get(50 - rowNumber + 2).get(i + 2);
            if (currentChannel instanceof FuelChannel || currentChannel instanceof CPSChannel) {
                javax.swing.JTextField button = takeCell();
                channelMtkBinding.put(currentChannel, button);
                channelCell.put(currentChannel, new int[] {i + 2, 50 - rowNumber + 2});
                button.setBorder(javax.swing.BorderFactory.createLineBorder(CHANNELOFFCOLOR.darker(), 1));
                if (currentChannel instanceof FuelChannel) {
                    button.setBackground(currentChannel.uiData.UIBackgroundColor);
                } else {
                    button.setBackground(CPSOFFCOLOR);
                }
                row.add(button);
            } else {
                javax.swing.JPanel button = takeFiller();
                row.add(button);
            }
        }
        this.add(row);
        rowNumber++;
        return row;
    }
    
    /** 阈值：相对平均通量的比例（0.15 = 高于/低于平均 15% 才算高亮），与原始 jar 一致 */
    public void setThresHold(float threshold) {
        this.threshold = threshold;
        update();
    }
    
    public void setSignPositive(boolean positive) {
        this.positive = positive;
        update();
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        setBackground(UI.BACKGROUND.darker());
        setBorder(new javax.swing.border.SoftBevelBorder(javax.swing.border.BevelBorder.RAISED, java.awt.Color.darkGray, java.awt.Color.darkGray, java.awt.Color.darkGray, java.awt.Color.darkGray));
        // 2026-10-18：上限从 300x320 放到 470x520，首选 430x430 ——
        // 主窗口给堆芯图跨两行的空间约 470x520，格子可以到 9 px 左右。
        setMaximumSize(new java.awt.Dimension(470, 520));
        setMinimumSize(new java.awt.Dimension(300, 260));
        setPreferredSize(new java.awt.Dimension(430, 430));
        setLayout(new java.awt.GridLayout(50, 50));
    }// </editor-fold>//GEN-END:initComponents

    /* ==================== 圆形内容保护（配合布局编辑器放大功能） ====================
     * 堆芯图是近似圆形的 50×50 通道阵列。面板被拉伸成非正方形时，
     * 若让格子随宽高各自拉伸，圆形会变成椭圆。
     * 这里在每次布局前把内容限制在居中的"内接正方形"区域：
     *   · 面板正方形时无异样（内边距 0）；
     *   · 面板变宽/变高时自动在两侧/上下留边，格子始终保持正方、圆形不变形。
     * 装饰边框（SoftBevelBorder）保留，叠加 EmptyBorder 使用。
     * ================================================================= */
    private javax.swing.border.Border mtkDecorBorder;
    private boolean mtkBorderCaptured = false;
    private int mtkPadH = -1;
    private int mtkPadV = -1;

    @Override
    public void doLayout() {
        if (!mtkBorderCaptured) {
            mtkDecorBorder = getBorder();
            mtkBorderCaptured = true;
        }
        int w = getWidth();
        int h = getHeight();
        int sq = Math.min(w, h);
        int hpad = Math.max(0, (w - sq) / 2);
        int vpad = Math.max(0, (h - sq) / 2);
        if (hpad != mtkPadH || vpad != mtkPadV) {
            mtkPadH = hpad;
            mtkPadV = vpad;
            javax.swing.border.Border pad =
                    javax.swing.BorderFactory.createEmptyBorder(vpad, hpad, vpad, hpad);
            setBorder(mtkDecorBorder == null ? pad
                    : javax.swing.BorderFactory.createCompoundBorder(mtkDecorBorder, pad));
        }
        super.doLayout();
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    // End of variables declaration//GEN-END:variables

}
