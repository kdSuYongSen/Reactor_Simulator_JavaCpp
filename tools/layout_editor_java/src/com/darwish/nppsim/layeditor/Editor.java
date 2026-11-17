package com.darwish.nppsim.layeditor;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.JViewport;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 * RBMK-1500 外部布局编辑器（独立 jar）。
 *
 * <p>工作方式：模拟器运行时会把每个窗口的方块和**递归**子组件导出成
 * {@code ui_layout_pages.json}；本工具读它，改完写 {@code <pageId>.layout.json}
 * （主窗口写 {@code ui_layout.json}），模拟器每秒轮询、约 1 秒内界面就跟上。</p>
 *
 * <p>与旧 python 工具的区别：树能展开到任意层级，所以面板里再套一层的按键也调得到；
 * 画布显示的是"选中节点所在父容器及其所有兄弟"，拖谁改谁，兄弟不会跟着跑。</p>
 */
public final class Editor extends JFrame {

    private final Manifest manifest;
    private final File dir;

    private final JComboBox<Manifest.Page> pageCombo = new JComboBox<>();
    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("root")));
    private final Canvas canvas = new Canvas();
    private final JSpinner spX = spin(), spY = spin(), spW = spin(), spH = spin();
    private final JCheckBox cbVisible = new JCheckBox(L.t("lbl.visible"));
    /* 颜色（2026-11-16）：预览块 + 十六进制输入框，点预览块打开调色盘 */
    private final javax.swing.JTextField txBg = new javax.swing.JTextField(7);
    private final javax.swing.JTextField txFg = new javax.swing.JTextField(7);
    private final ColorChip swBg = new ColorChip();
    private final ColorChip swFg = new ColorChip();
    private final JLabel status = new JLabel(" ");
    private final JLabel pathLabel = new JLabel(" ");
    private final JLabel hint = new JLabel(L.t("hint.canvas"));
    /** 画布的滚动容器（面板比窗口大时出滚动条，右侧内容不再被截掉） */
    private JScrollPane canvasScroll;
    /** 当前缩放比例显示（100% = 模型像素 1:1） */
    private JLabel zoomLabel;
    /** 长按热键时的纯放大镜（盖在玻璃面板上，不改变页面缩放） */
    private final Loupe loupe = new Loupe();
    /** 放大镜倍率显示（− / + 中间那个数字） */
    private JLabel loupeLabel;

    /**
     * 缩放倍率范围（相对 1:1 模型像素）。
     *
     * <p>上限是 1.0：画布<b>永远不放大</b>（页面自己变大会让人分不清是在看布局还是在放大镜里）。
     * 想看细节请按住热键用放大镜；这里只允许缩小，方便大面板一屏看全。</p>
     */
    static final double ZOOM_MIN = 0.15;
    static final double ZOOM_MAX = 1.0;

    /** 左侧树 / 右侧画布的分隔条（显示后要显式给位置，否则只按树的极小首选宽度分） */
    private JSplitPane split;
    private Manifest.Page page;
    private Manifest.Node selected;
    private String lastSaved = "";
    /**
     * 正在把数值写进输入框（syncSpinners）。
     * 写值会触发 Spinner 的 ChangeListener，如果不去掉，切换选中组件时
     * 新旧数值会互相串台——表现为“点一下组件它就消失 / 随机变大变小”。
     */
    private boolean syncing = false;
    /**
     * Manifest.Node → 树里的 DefaultMutableTreeNode。
     * 之前 pathOfNode() 直接把 Manifest.Node 塞进 TreePath，JTree 解析路径时
     * 抛 ClassCastException（"点一下组件就出问题"的根因之一）。
     */
    private final java.util.IdentityHashMap<Manifest.Node, DefaultMutableTreeNode> treeNodes =
            new java.util.IdentityHashMap<>();
    /** 已经叠过磁盘布局的页面（同一页只叠一次，避免覆盖未保存的拖动） */
    private final java.util.Set<String> layoutApplied = new java.util.HashSet<>();

    public Editor(Manifest manifest) {
        super(L.t("app.title"));
        this.manifest = manifest;
        this.dir = manifest == null ? new File(".") : manifest.dir;

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1180, 760);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);

        // 放大镜挂在玻璃面板上：平时不可见，按住热键才出现，不影响任何点击
        setGlassPane(loupe);
        loupe.setVisible(false);
        // 万一按住时窗口失去焦点（切到别的程序），松开事件收不到，这里兜底撤掉
        addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowLostFocus(java.awt.event.WindowEvent e) {
                magnifyHeld = false;     // 松开事件丢了，状态位也得跟着清
                loupe.hideLoupe();
            }
        });

        buildUi();
        loadPages();
        // 显示后给分隔条一个明确位置（否则树只占自己那点首选宽度，名字全被截断）
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.28));
    }

    private static JSpinner spin() {
        JSpinner s = new JSpinner(new SpinnerNumberModel(0, -8000, 8000, 1));
        s.setPreferredSize(new Dimension(72, 24));
        return s;
    }

    // ------------------------------------------------------------------
    private void buildUi() {
        // ---- 顶部：选窗口 + 重载 ----
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
        JPanel left = new JPanel(new BorderLayout(6, 0));
        left.add(new JLabel(L.t("lbl.window")), BorderLayout.WEST);
        pageCombo.addActionListener(e -> selectPage((Manifest.Page) pageCombo.getSelectedItem()));
        left.add(pageCombo, BorderLayout.CENTER);
        top.add(left, BorderLayout.CENTER);

        JButton reload = new JButton(L.t("btn.reload"));
        reload.addActionListener(e -> reloadManifest());
        JButton help = new JButton(L.t("btn.help"));
        help.setToolTipText(L.t("btn.help.tip"));
        help.addActionListener(e -> showHelp());
        JComboBox<L.Lang> langCombo = new JComboBox<>(L.Lang.values());
        langCombo.setSelectedItem(L.lang());
        langCombo.setToolTipText(L.t("lbl.language"));
        langCombo.addActionListener(e -> {
            L.Lang l = (L.Lang) langCombo.getSelectedItem();
            if (l != null && l != L.lang()) {
                L.setLang(l);
                rebuildWithSameManifest();
            }
        });

        JButton settingsBtn = new JButton(L.t("btn.settings"));
        settingsBtn.setToolTipText(L.t("keys.title"));
        settingsBtn.addActionListener(e -> openKeySettings());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(new JLabel(L.t("lbl.language")));
        right.add(langCombo);
        right.add(settingsBtn);
        right.add(help);
        right.add(reload);
        top.add(right, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        // ---- 左：组件树 ----
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(20);
        tree.addTreeSelectionListener(this::onTreeSelected);
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createTitledBorder(L.t("tree.title")));
        treeScroll.setPreferredSize(new Dimension(360, 500));

        // ---- 右：画布 ----
        JPanel canvasWrap = new JPanel(new BorderLayout());
        canvasWrap.setBorder(BorderFactory.createTitledBorder(L.t("canvas.title")));

        /* 画布套一层滚动容器（2026-11-03）：面板比视口大时（比如 1600×1800 的窗口）
         * 原来右侧/下侧内容直接被裁掉看不见，现在可以用滚动条看全。 */
        canvasScroll = new JScrollPane(canvas,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        canvasScroll.setBorder(null);
        canvasScroll.getViewport().setBackground(canvas.getBackground());
        canvasScroll.getVerticalScrollBar().setUnitIncrement(32);
        canvasScroll.getHorizontalScrollBar().setUnitIncrement(32);
        canvas.setScroll(canvasScroll);
        canvasWrap.add(canvasScroll, BorderLayout.CENTER);
        canvasWrap.add(buildZoomBar(), BorderLayout.NORTH);

        JLabel canvasHint = new JLabel(hintText());
        canvasHint.setBorder(BorderFactory.createEmptyBorder(2, 6, 0, 6));
        canvasWrap.add(canvasHint, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout());
        center.add(canvasWrap, BorderLayout.CENTER);
        center.add(buildPropBar(), BorderLayout.SOUTH);

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, center);
        split.setResizeWeight(0.3);
        split.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        add(split, BorderLayout.CENTER);

        // 画布上的方向键微调（±1 / Shift ±10）
        canvas.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                int dx = 0;
                int dy = 0;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_LEFT:  dx = -1; break;
                    case KeyEvent.VK_RIGHT: dx = 1; break;
                    case KeyEvent.VK_UP:    dy = -1; break;
                    case KeyEvent.VK_DOWN:  dy = 1; break;
                    default: return;
                }
                nudge(dx, dy, e.isShiftDown() ? 10 : 1);
                e.consume();
            }
        });

        // ---- 底部：状态 ----
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));
        bottom.add(status, BorderLayout.WEST);
        JLabel man = new JLabel(manifest == null ? L.t("no.manifest")
                : manifest.manifestFile.getAbsolutePath() + "   " + manifest.generatedAt);
        man.setFont(man.getFont().deriveFont(11f));
        bottom.add(man, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        // 快捷键
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save");
        getRootPane().getActionMap().put("save", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                doSave();
            }
        });
        /* 缩放 / 长按放大热键（2026-11-03）：按键从 <布局目录>/ui_zoom_keys.json 读，
         * 和模拟器共用同一份 —— 在"设置…"里改，两边一起变。 */
        applyZoomKeys();

        // Ctrl+R：重新读取模拟器导出的清单
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), "reload");
        getRootPane().getActionMap().put("reload", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                reloadManifest();
            }
        });
        // F1：打开“提示”对话框
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "help");
        getRootPane().getActionMap().put("help", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                showHelp();
            }
        });
    }

    /** 已经装上的缩放热键（改绑前先撤掉） */
    private final java.util.List<KeyStroke> zoomStrokes = new java.util.ArrayList<>();

    /**
     * 放大键是否还按着（2026-11-03 晚）。
     *
     * <p>按住不放时系统会不停发 keyPressed（自动重复）。靠这个状态位做到
     * "上一次还没松开就不执行"：不会反复重渲染快照，也不会在按住放大键的同时
     * 又响应倍率 / 适应性快捷键。</p>
     */
    private boolean magnifyHeld;

    /** 按当前配置重建缩放/放大热键 */
    private void applyZoomKeys() {
        javax.swing.InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap am = getRootPane().getActionMap();
        for (KeyStroke ks : zoomStrokes) {
            im.remove(ks);
        }
        zoomStrokes.clear();

        ZoomKeys k = ZoomKeys.load(dir);
        loupe.setFactor(k.loupe);
        updateLoupeLabel();
        magnifyHeld = false;          // 重新绑定后当没按过
        /* "放大/缩小"热键改调放大镜倍率（2026-11-03 晚）：页面本身不再有"放大"这回事，
         * 否则一按键整个版面就变大，看着像自己放大了。0 仍然是"适应窗口"。
         * 放大键还按着的时候这几个都不响应（见下面 showLoupe 的说明）。 */
        bindZoom(im, am, KeyStroke.getKeyStroke(k.zoomIn, 0), () -> {
            if (!magnifyHeld) {
                changeLoupeFactor(+0.25);
            }
        }, true);
        bindZoom(im, am, KeyStroke.getKeyStroke(k.zoomOut, 0), () -> {
            if (!magnifyHeld) {
                changeLoupeFactor(-0.25);
            }
        }, true);
        bindZoom(im, am, KeyStroke.getKeyStroke(k.fit, 0), () -> {
            if (!magnifyHeld) {
                canvas.fitView();
            }
        }, true);
        /* 长按热键 = 纯放大镜：只把鼠标周围那块放大，页面缩放和滚动位置都不动。 */
        bindZoom(im, am, KeyStroke.getKeyStroke(k.magnify, 0), this::showLoupe, true);
        // 松开的处理不检查焦点：按住时就算焦点跑进输入框也要能撤掉放大镜
        bindZoom(im, am, KeyStroke.getKeyStroke(k.magnify, 0, true),
                () -> {
                    magnifyHeld = false;
                    loupe.hideLoupe();
                }, false);
    }

    /** 按住热键：在鼠标位置显示放大镜（倍率取配置里的固定值） */
    private void showLoupe() {
        /* 上一个快捷键还没松开就不执行（2026-11-03 晚）：按住不放时系统会一直发
         * keyPressed（自动重复），以前每次都要重新渲染一张快照，画面会抖、也费 CPU。
         * 这里用状态位挡住，一次按住只放大一次。 */
        if (magnifyHeld) {
            return;
        }
        magnifyHeld = true;
        loupe.setFactor(ZoomKeys.load(dir).loupe);
        java.awt.PointerInfo pi = java.awt.MouseInfo.getPointerInfo();
        loupe.showAt(pi == null ? null : pi.getLocation());
    }

    /** 调整放大镜倍率（NaN = 复位成默认 2.5）并写回共用配置 */
    private void changeLoupeFactor(double delta) {
        ZoomKeys k = ZoomKeys.load(dir);
        double f = Double.isNaN(delta) ? 2.5 : k.loupe + delta;
        f = Math.max(Loupe.MIN, Math.min(Loupe.MAX, f));
        k.loupe = f;
        k.save(dir);                 // 模拟器读同一份文件，下次按住就是新倍率
        loupe.setFactor(f);          // 正在按住时只重画，不重新抓图
        updateLoupeLabel();
    }

    private void updateLoupeLabel() {
        if (loupeLabel != null) {
            loupeLabel.setText(String.format("%.1f×", loupe.getFactor()));
        }
    }

    private void bindZoom(javax.swing.InputMap im, javax.swing.ActionMap am, KeyStroke ks,
                          Runnable action, boolean respectTextFocus) {
        String name = "zoom" + ks.toString().hashCode();
        im.put(ks, name);
        am.put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (respectTextFocus && textFocused()) {
                    return;
                }
                action.run();
            }
        });
        zoomStrokes.add(ks);
    }

    /** 打开热键设置；确定后写盘并立刻生效 */
    private void openKeySettings() {
        ZoomKeys cur = ZoomKeys.load(dir);
        ZoomKeys next = KeyBindDialog.show(this, cur, dir);
        if (next == null) {
            return;
        }
        next.save(dir);
        applyZoomKeys();
        status.setText(L.t("keys.title"));
    }

    /** 画布上方的工具条：比例 / 适应窗口 ｜ 放大镜倍率 − / 倍率 / + */
    private JPanel buildZoomBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        bar.setOpaque(false);

        zoomLabel = new JLabel("100%");
        zoomLabel.setPreferredSize(new Dimension(48, 20));
        zoomLabel.setHorizontalAlignment(SwingConstants.CENTER);
        zoomLabel.setToolTipText(L.t("zoom.label"));

        JButton fit = new JButton(L.t("zoom.fit"));
        fit.setToolTipText(L.t("zoom.fit.tip"));
        fit.addActionListener(e -> canvas.fitView());

        /* 放大镜倍率（2026-11-03）：长按热键是"纯放大镜"，页面本身永不放大，
         * 要放大只能靠这里调倍率。改完写进共用的 ui_zoom_keys.json。 */
        JLabel sep = new JLabel("│");
        sep.setForeground(new Color(0x80, 0x86, 0x90));

        JButton lout = new JButton("−");
        lout.setMargin(new Insets(0, 6, 0, 6));
        lout.setToolTipText(L.t("zoom.loupe.out"));
        lout.addActionListener(e -> changeLoupeFactor(-0.25));

        loupeLabel = new JLabel("2.5×");
        loupeLabel.setPreferredSize(new Dimension(52, 20));
        loupeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        loupeLabel.setToolTipText(L.t("zoom.loupe.tip"));
        loupeLabel.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        loupeLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                changeLoupeFactor(Double.NaN);      // 点数字复位
            }
        });

        JButton lin = new JButton("+");
        lin.setMargin(new Insets(0, 6, 0, 6));
        lin.setToolTipText(L.t("zoom.loupe.in"));
        lin.addActionListener(e -> changeLoupeFactor(+0.25));

        updateLoupeLabel();

        bar.add(new JLabel(L.t("zoom.title")));
        bar.add(zoomLabel);
        bar.add(fit);
        bar.add(sep);
        bar.add(new JLabel(L.t("zoom.loupe")));
        bar.add(lout);
        bar.add(loupeLabel);
        bar.add(lin);
        return bar;
    }

    /** 焦点在输入框里时不要吃掉按键（属性栏的 X/Y/W/H 要能正常输数字和 q） */
    private static boolean textFocused() {
        java.awt.Component c = java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager().getFocusOwner();
        return c instanceof javax.swing.text.JTextComponent
                || c instanceof javax.swing.JSpinner;
    }

    private String hintText() {
        return L.t("hint.canvas");
    }

    // ==================================================================
    // 提示：详细的设置 / 操作说明
    // ==================================================================
    private void showHelpButtonAction() {
        showHelp();
    }

    /** 打开“操作说明”对话框（右上角“提示”按钮 / F1 / 启动参数 --help 都走这里） */
    void showHelp() {
        JDialog dlg = new JDialog(this, L.t("help.title"), true);
        dlg.setLayout(new BorderLayout(0, 0));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(L.t("help.tab1"), helpPane(helpQuick()));
        tabs.addTab(L.t("help.tab2"), helpPane(helpCanvas()));
        tabs.addTab(L.t("help.tab3"), helpPane(helpSave()));
        tabs.addTab(L.t("help.tab4"), helpPane(helpKeys()));
        tabs.addTab(L.t("help.tab5"), helpPane(helpFaq()));
        dlg.add(tabs, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton ok = new JButton(L.t("btn.ok"));
        ok.addActionListener(e -> dlg.dispose());
        south.add(ok);
        dlg.add(south, BorderLayout.SOUTH);

        dlg.setSize(780, 580);
        dlg.setMinimumSize(new Dimension(560, 400));
        dlg.setLocationRelativeTo(this);
        dlg.getRootPane().setDefaultButton(ok);
        dlg.getRootPane().registerKeyboardAction(e -> dlg.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dlg.setVisible(true);
    }

    /** 一页说明：只读、自动换行的文本框 */
    private static JScrollPane helpPane(String text) {
        JTextArea ta = new JTextArea(text);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        ta.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        ta.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ta);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        return sp;
    }

    private String helpQuick() {
        return String.format(L.t("help.b1"), dir.getAbsolutePath());
    }

    private String helpCanvas() {
        return L.t("help.b2");
    }

    private String helpSave() {
        return String.format(L.t("help.b3"), dir.getAbsolutePath());
    }

    private String helpKeys() {
        return L.t("help.b4");
    }

    private String helpFaq() {
        return String.format(L.t("help.b5"),
                new File(dir, "ui_layout_pages.json").getAbsolutePath());
    }

    // ------------------------------------------------------------------ 颜色

    /** 颜色预览块：点一下打开调色盘 */
    private final class ColorChip extends JComponent {
        private java.awt.Color color;

        ColorChip() {
            setPreferredSize(new Dimension(26, 18));
            setMinimumSize(new Dimension(26, 18));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        void set(java.awt.Color c) {
            color = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (color == null) {
                g.setColor(new Color(0x33, 0x38, 0x42));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(0x8A, 0x92, 0xA0));
                g.drawLine(1, getHeight() - 2, getWidth() - 2, 1);   // 斜线 = 未设置
            } else {
                g.setColor(color);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            g.setColor(new Color(0x6E, 0x76, 0x86));
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g.dispose();
        }
    }

    /** "×"：清除该路颜色（回到默认） */
    private JButton colorClear(boolean isBg) {
        JButton b = new JButton("×");
        b.setMargin(new Insets(0, 4, 0, 4));
        b.setFocusable(false);
        b.setToolTipText(L.t("color.clear"));
        b.addActionListener(e -> {
            applyColor(isBg, "");
        });
        return b;
    }

    /** 打开系统调色盘（自带 RGB/HSV 滑条与色板） */
    private void pickColor(boolean isBg) {
        if (selected == null) {
            return;
        }
        String cur = isBg ? selected.bg : selected.fg;
        java.awt.Color init = parseColor(cur);
        java.awt.Color c = javax.swing.JColorChooser.showDialog(this,
                L.t(isBg ? "color.pickBg" : "color.pickFg"),
                init != null ? init : new java.awt.Color(0x40, 0x46, 0x52));
        if (c != null) {
            applyColor(isBg, toHex(c));
        }
    }

    /** 输入框/调色盘 → 节点颜色（空串 = 清除；非法值恢复原文本） */
    private void applyColor(boolean isBg, String text) {
        Manifest.Node n = selected;
        if (n == null) {
            return;
        }
        String raw = text == null ? "" : text.trim();
        String hex = null;
        if (!raw.isEmpty()) {
            java.awt.Color c = parseColor(raw);
            if (c == null) {
                syncColorFields(n);          // 输到一半的非法值：恢复显示
                return;
            }
            hex = toHex(c);
        }
        if (isBg) {
            n.bg = hex;
        } else {
            n.fg = hex;
        }
        n.colorDirty = n.colorChanged();
        syncColorFields(n);
        canvas.repaint();
        updateStatus();
        pathLabel.setText(String.format("%s    %s%s", n.display(),
                n.path.isEmpty() ? (L.t("path.block") + n.blockId)
                        : (L.t("path.block") + n.blockId + " / " + n.path),
                n.dirty || n.colorDirty ? L.t("path.dirty2") : ""));
    }

    /** 把节点当前颜色刷到预览块与输入框 */
    private void syncColorFields(Manifest.Node n) {
        if (n == null) {
            swBg.set(null);
            swFg.set(null);
            txBg.setText("");
            txFg.setText("");
            txBg.setEnabled(false);
            txFg.setEnabled(false);
            return;
        }
        txBg.setEnabled(true);
        txFg.setEnabled(true);
        swBg.set(parseColor(n.bg));
        swFg.set(parseColor(n.fg));
        txBg.setText(n.bg == null ? "" : n.bg);
        txFg.setText(n.fg == null ? "" : n.fg);
    }

    /** "#RRGGBB" / "RRGGBB" / "0xRRGGBB" → Color */
    private static java.awt.Color parseColor(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().replace("0x", "").replace("#", "");
        try {
            if (t.length() == 6) {
                return new java.awt.Color(Integer.parseInt(t, 16));
            }
            if (t.length() == 8) {
                long v = Long.parseLong(t, 16);
                return new java.awt.Color((int) ((v >> 16) & 0xFF), (int) ((v >> 8) & 0xFF),
                        (int) (v & 0xFF), (int) ((v >> 24) & 0xFF));
            }
        } catch (RuntimeException ignored) {
            // 非法输入
        }
        return null;
    }

    private static String toHex(java.awt.Color c) {
        if (c == null) {
            return null;
        }
        if (c.getAlpha() < 255) {
            return String.format("#%02X%02X%02X%02X", c.getAlpha(), c.getRed(), c.getGreen(), c.getBlue());
        }
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private JPanel buildPropBar() {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;

        gc.gridy = 0; gc.gridx = 0; gc.gridwidth = 6;
        bar.add(pathLabel, gc);

        gc.gridy = 1; gc.gridwidth = 1;
        gc.gridx = 0; bar.add(new JLabel("X"), gc);
        gc.gridx = 1; bar.add(spX, gc);
        gc.gridx = 2; bar.add(new JLabel("Y"), gc);
        gc.gridx = 3; bar.add(spY, gc);
        gc.gridx = 4; bar.add(new JLabel("W"), gc);
        gc.gridx = 5; bar.add(spW, gc);
        gc.gridx = 6; bar.add(new JLabel("H"), gc);
        gc.gridx = 7; bar.add(spH, gc);
        gc.gridx = 8; bar.add(cbVisible, gc);

        spX.addChangeListener(e -> applySpinners());
        spY.addChangeListener(e -> applySpinners());
        spW.addChangeListener(e -> applySpinners());
        spH.addChangeListener(e -> applySpinners());
        cbVisible.addActionListener(e -> applySpinners());

        /* ---- 第二行：颜色 ----
         * 整行用一个 FlowLayout 子面板（跨满所有列）：直接塞进 GridBag 的话，
         * 列宽受上面那排 spinner 约束，输入框会被压成十几像素宽（实测）。 */
        JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        colorRow.setOpaque(false);
        colorRow.add(new JLabel(L.t("color.bg")));
        colorRow.add(swBg);
        txBg.setColumns(9);
        txBg.setPreferredSize(new Dimension(88, 22));
        colorRow.add(txBg);
        colorRow.add(colorClear(true));
        colorRow.add(Box.createHorizontalStrut(10));
        colorRow.add(new JLabel(L.t("color.fg")));
        colorRow.add(swFg);
        txFg.setColumns(9);
        txFg.setPreferredSize(new Dimension(88, 22));
        colorRow.add(txFg);
        colorRow.add(colorClear(false));

        gc.gridy = 2;
        gc.gridx = 0;
        gc.gridwidth = 14;
        gc.fill = GridBagConstraints.HORIZONTAL;
        bar.add(colorRow, gc);
        gc.gridwidth = 1;
        gc.fill = GridBagConstraints.NONE;

        swBg.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pickColor(true);
            }
        });
        swFg.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pickColor(false);
            }
        });
        txBg.addActionListener(e -> applyColor(true, txBg.getText()));
        txFg.addActionListener(e -> applyColor(false, txFg.getText()));
        txBg.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                applyColor(true, txBg.getText());
            }
        });
        txFg.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                applyColor(false, txFg.getText());
            }
        });
        txBg.setToolTipText(L.t("color.hint"));
        txFg.setToolTipText(L.t("color.hint"));
        swBg.setToolTipText(L.t("color.pick"));
        swFg.setToolTipText(L.t("color.pick"));

        gc.gridx = 9;
        gc.gridy = 1;
        bar.add(Box.createHorizontalStrut(12), gc);
        gc.gridx = 10;
        JButton save = new JButton(L.t("btn.save"));
        save.addActionListener(e -> doSave());
        bar.add(save, gc);
        gc.gridx = 11;
        JButton resetNode = new JButton(L.t("btn.resetNode"));
        resetNode.addActionListener(e -> doResetNode());
        bar.add(resetNode, gc);
        gc.gridx = 12;
        JButton resetPage = new JButton(L.t("btn.resetPage"));
        resetPage.addActionListener(e -> doResetPage());
        bar.add(resetPage, gc);
        gc.gridx = 13;
        JButton del = new JButton(L.t("btn.delete"));
        del.addActionListener(e -> doDeleteFile());
        bar.add(del, gc);
        return bar;
    }

    // ------------------------------------------------------------------
    private void loadPages() {
        pageCombo.removeAllItems();
        if (manifest == null || manifest.pages.isEmpty()) {
            status.setText(L.t("status.noManifest"));
            return;
        }
        for (Manifest.Page p : manifest.pages) {
            pageCombo.addItem(p);
        }
        pageCombo.setSelectedIndex(0);
    }

    /** 换语言：界面文字整体重建（用同一份清单，不重新读盘） */
    private void rebuildWithSameManifest() {
        Editor fresh = new Editor(manifest);
        fresh.setLocation(getLocation());
        fresh.setSize(getSize());
        fresh.setVisible(true);
        dispose();
    }

    private void reloadManifest() {
        try {
            Manifest m = Manifest.load(dir);
            if (m == null || m.pages.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        String.format(L.t("dlg.manifestFail"),
                                new File(dir, "ui_layout_pages.json").getAbsolutePath()),
                        L.t("dlg.noManifest"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            Editor fresh = new Editor(m);
            fresh.setLocation(getLocation());
            fresh.setVisible(true);
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, L.t("dlg.readFail") + ex, L.t("dlg.error"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void selectPage(Manifest.Page p) {
        if (p == null) {
            return;
        }
        page = p;
        /* 把已保存的布局叠到清单坐标上（2026-11-03）：清单是模拟器启动时的快照，
         * 只读清单会让编辑器显示旧位置，保存时又把旧坐标写回去 —— 之前调好的位置被弹回。 */
        if (!layoutApplied.contains(p.id)) {
            Manifest.applyExistingLayout(p, dir);
            layoutApplied.add(p.id);
        }
        selected = null;
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        treeNodes.clear();
        for (Manifest.Node b : p.blocks) {
            root.add(treeOf(b));
        }
        tree.setModel(new DefaultTreeModel(root));
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
        canvas.setContext(p.w, p.h, p.blocks, null);
        syncSpinners(null);
        updateStatus();
    }

    private DefaultMutableTreeNode treeOf(Manifest.Node n) {
        DefaultMutableTreeNode t = new DefaultMutableTreeNode(new TreeItem(n));
        treeNodes.put(n, t);
        for (Manifest.Node c : n.children) {
            t.add(treeOf(c));
        }
        return t;
    }

    /** 树节点包装：toString 决定显示文字（标签为空时用 "#索引 类名"） */
    static final class TreeItem {
        final Manifest.Node node;

        TreeItem(Manifest.Node node) {
            this.node = node;
        }

        @Override public String toString() {
            return node.treeLabel();
        }
    }

    private void onTreeSelected(TreeSelectionEvent e) {
        Object o = tree.getLastSelectedPathComponent();
        if (!(o instanceof DefaultMutableTreeNode)) {
            return;
        }
        Object u = ((DefaultMutableTreeNode) o).getUserObject();
        if (!(u instanceof TreeItem)) {
            return;
        }
        select(((TreeItem) u).node);
    }

    /** 选中一个节点：画布换成"它的父容器 + 兄弟"，并把数值填到表单 */
    void select(Manifest.Node n) {
        selected = n;
        if (n == null || page == null) {
            canvas.setContext(page == null ? 100 : page.w, page == null ? 100 : page.h,
                    page == null ? java.util.Collections.emptyList() : page.blocks, null);
            syncSpinners(null);
            return;
        }
        if (n.path.isEmpty()) {
            canvas.setContext(page.w, page.h, page.blocks, n);
        } else {
            Manifest.Node parent = parentOf(n);
            if (parent == null) {
                parent = page.block(n.blockId);
            }
            canvas.setContext(parent.w, parent.h, parent.children, n);
        }
        syncSpinners(n);

        // 树里的选中项跟画布保持一致（点画布时同步树）
        TreePath tp = pathOfNode(n);
        if (tp != null && !tp.equals(tree.getSelectionPath())) {
            tree.setSelectionPath(tp);
            tree.scrollPathToVisible(tp);
        }
    }

    private Manifest.Node parentOf(Manifest.Node target) {
        Manifest.Node block = page.block(target.blockId);
        return block == null ? null : parentOf(block, target);
    }

    private Manifest.Node parentOf(Manifest.Node cur, Manifest.Node target) {
        for (Manifest.Node c : cur.children) {
            if (c == target) {
                return cur;
            }
            Manifest.Node r = parentOf(c, target);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private TreePath pathOfNode(Manifest.Node target) {
        if (page == null) {
            return null;
        }
        Object root = tree.getModel().getRoot();
        for (Manifest.Node b : page.blocks) {
            if (b == target) {
                DefaultMutableTreeNode t = treeNodes.get(b);
                return t == null ? null : new TreePath(new Object[]{root, t});
            }
        }
        // 逐层构造路径（每一段都要换成树里的 DefaultMutableTreeNode）
        java.util.List<Manifest.Node> chain = new java.util.ArrayList<>();
        if (chainOf(page.block(target.blockId), target, chain)) {
            java.util.List<Object> objs = new java.util.ArrayList<>();
            objs.add(root);
            for (Manifest.Node n : chain) {
                DefaultMutableTreeNode t = treeNodes.get(n);
                if (t == null) {
                    return null;
                }
                objs.add(t);
            }
            return new TreePath(objs.toArray());
        }
        return null;
    }

    private boolean chainOf(Manifest.Node cur, Manifest.Node target, List<Manifest.Node> out) {
        if (cur == target) {
            out.add(cur);
            return true;
        }
        for (Manifest.Node c : cur.children) {
            if (chainOf(c, target, out)) {
                out.add(0, cur);
                return true;
            }
        }
        return false;
    }

    private void syncSpinners(Manifest.Node n) {
        boolean on = n != null;
        // 关键：写值期间禁止回写，否则切换选中项会把上一个组件的宽高带过来
        syncing = true;
        try {
            spX.setEnabled(on);
            spY.setEnabled(on);
            spW.setEnabled(on);
            spH.setEnabled(on);
            cbVisible.setEnabled(on);
            if (n == null) {
                pathLabel.setText(L.t("not.selected"));
                syncColorFields(null);
                return;
            }
            syncColorFields(n);
            spX.setValue(n.x);
            spY.setValue(n.y);
            spW.setValue(n.w);
            spH.setValue(n.h);
            cbVisible.setSelected(n.visible);
        } finally {
            syncing = false;
        }
        String where = n.path.isEmpty() ? (L.t("path.block") + n.blockId)
                : (L.t("path.block") + n.blockId + " / " + n.path);
        pathLabel.setText(String.format("%s    %s%s", n.display(), where,
                n.dirty ? L.t("path.dirty2") : ""));
    }

    private void applySpinners() {
        if (syncing || selected == null) {
            return;                     // 程序写值引起的回调，忽略
        }
        int x = (Integer) spX.getValue();
        int y = (Integer) spY.getValue();
        int w = Math.max(1, (Integer) spW.getValue());
        int h = Math.max(1, (Integer) spH.getValue());
        boolean vis = cbVisible.isSelected();
        Manifest.Node n = selected;
        if (n.x == x && n.y == y && n.w == w && n.h == h && n.visible == vis) {
            return;
        }
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        n.visible = vis;
        n.dirty = true;
        canvas.repaint();
        updateStatus();
        pathLabel.setText(String.format("%s    %s%s", n.display(),
                n.path.isEmpty() ? (L.t("path.block") + n.blockId)
                        : (L.t("path.block") + n.blockId + " / " + n.path),
                L.t("path.dirty2")));
    }

    /** 键盘微调选中组件的位置（step 像素） */
    private void nudge(int dx, int dy, int step) {
        if (selected == null) {
            return;
        }
        selected.x = Math.max(0, selected.x + dx * step);
        selected.y = Math.max(0, selected.y + dy * step);
        selected.dirty = true;
        syncSpinners(selected);
        canvas.repaint();
        updateStatus();
    }

    // ------------------------------------------------------------------
    void doSave() {
        if (page == null) {
            return;
        }
        try {
            File out = LayoutWriter.save(page, dir);
            lastSaved = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
            status.setText(String.format(L.t("status.saved"), out.getName(), lastSaved));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, L.t("dlg.saveFail") + ex, L.t("dlg.error"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doResetNode() {
        if (selected == null) {
            return;
        }
        selected.reset();
        syncSpinners(selected);
        canvas.repaint();
        updateStatus();
    }

    private void doResetPage() {
        if (page == null) {
            return;
        }
        for (Manifest.Node b : page.blocks) {
            b.reset();
        }
        syncSpinners(selected);
        canvas.repaint();
        updateStatus();
    }

    private void doDeleteFile() {
        if (page == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                String.format(L.t("dlg.deleteAsk"), LayoutWriter.targetFile(page, dir).getName()),
                L.t("dlg.deleteTitle"), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        boolean ok = LayoutWriter.remove(page, dir);
        status.setText(ok ? L.t("status.deleted") : L.t("status.deleteFail"));
    }

    private void updateStatus() {
        if (page == null) {
            return;
        }
        int n = LayoutWriter.dirtyPaths(page).size();
        File f = LayoutWriter.existing(page, dir);
        status.setText(String.format(L.t("status.summary"),
                page.title.isEmpty() ? page.id : page.title, page.blocks.size(), n,
                f == null ? L.t("status.noFile")
                        : String.format(L.t("status.hasFile"), f.getName())));
    }

    // ==================================================================
    /** 画布：显示当前父容器 + 所有兄弟，可拖动/缩放选中的那个 */
    final class Canvas extends JComponent {

        private int parentW = 100, parentH = 100;
        private List<Manifest.Node> siblings = java.util.Collections.emptyList();
        private Manifest.Node sel;

        /** 画布四周留白（模型坐标外扩） */
        static final int PAD = 14;
        /** 用户缩放倍率：1.0 = 适应窗口，>1 放大、<1 缩小 */
        private double zoom = 1.0;
        /** 适应窗口时算出的比例（缩放倍率的作用基准） */
        private double fitScale = 1.0;
        /** 实际比例 = fitScale * zoom */
        private double scale = 1;
        private int ox = PAD, oy = PAD;

        /** 滚动容器（画布按缩放后的尺寸放进视口，超出部分用滚动条看） */
        private JScrollPane scroll;
        /** 上一次按哪个视口尺寸算的比例（视口没变就不重算，避免布局抖动） */
        private int lastVpW = -1, lastVpH = -1;

        /* 长按放大的状态（magnifying / magnifyFactor / zoomBeforeMagnify…）已经删掉：
         * 现在是纯粹的放大镜——盖在窗口上放大一块画面，页面本身不缩放、不滚动。 */

        private boolean dragging, resizing;
        private int dragDX, dragDY;
        private int startX, startY, startW, startH;

        Canvas() {
            setBackground(new Color(0x22, 0x26, 0x2E));
            setOpaque(true);
            setFocusable(true);
            MouseAdapter h = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { onPress(e); }
                @Override public void mouseDragged(MouseEvent e) { onDrag(e); }
                @Override public void mouseReleased(MouseEvent e) { onRelease(); }
                @Override public void mouseMoved(MouseEvent e) { updateCursor(e); }
            };
            addMouseListener(h);
            addMouseMotionListener(h);

            /* 滚轮（2026-11-03）：普通滚轮滚动画布；Ctrl+滚轮只能**缩小**（大面板一屏看全），
             * 不会放大——画布永远不超过 1:1，放大交给长按热键的放大镜。 */
            addMouseWheelListener(e -> {
                if (e.isControlDown()) {
                    double f = Math.pow(1.12, -e.getPreciseWheelRotation());
                    if (f < 1.0) {
                        zoomAt(zoom * f, e.getX(), e.getY());
                    }
                } else if (scroll != null) {
                    JScrollBar bar = e.isShiftDown()
                            ? scroll.getHorizontalScrollBar() : scroll.getVerticalScrollBar();
                    bar.setValue(bar.getValue()
                            + (int) Math.round(e.getPreciseWheelRotation() * bar.getUnitIncrement() * 3));
                }
                e.consume();
            });

            // 方向键微调
            bind(KeyEvent.VK_LEFT, -1, 0);
            bind(KeyEvent.VK_RIGHT, 1, 0);
            bind(KeyEvent.VK_UP, 0, -1);
            bind(KeyEvent.VK_DOWN, 0, 1);
        }

        private void bind(int key, int dx, int dy) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), "n" + key);
            getInputMap(WHEN_FOCUSED).put(
                    KeyStroke.getKeyStroke(key, InputEvent.SHIFT_DOWN_MASK), "s" + key);
            getActionMap().put("n" + key, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { nudge(dx, dy, 1); }
            });
            getActionMap().put("s" + key, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { nudge(dx, dy, 10); }
            });
        }

        private void nudge(int dx, int dy, int step) {
            if (sel == null) {
                return;
            }
            sel.x += dx * step;
            sel.y += dy * step;
            sel.dirty = true;
            syncSpinners(sel);
            repaint();
            updateStatus();
        }

        void setContext(int w, int h, List<Manifest.Node> siblings, Manifest.Node sel) {
            this.parentW = Math.max(1, w);
            this.parentH = Math.max(1, h);
            this.siblings = siblings == null ? java.util.Collections.emptyList() : siblings;
            this.sel = sel;
            // 换页/钻取时回到"适应窗口"，否则上一页的缩放倍数会带过来
            zoom = 1.0;
            lastVpW = -1;              // 强制作一次重新计算
            applyTransform();
        }

        void setScroll(JScrollPane sp) {
            this.scroll = sp;
            lastVpW = -1;
            applyTransform();
            if (sp != null) {
                sp.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
                    @Override public void componentResized(java.awt.event.ComponentEvent e) {
                        lastVpW = -1;          // 视口尺寸变了，重新算"适应窗口"比例
                        applyTransform();
                    }
                });
            }
        }

        /** 视口尺寸变化时才重算（paint 里调用，避免每帧重算引起抖动） */
        private void ensureTransform() {
            int vw = viewportWidth(), vh = viewportHeight();
            if (vw != lastVpW || vh != lastVpH) {
                applyTransform();
            }
        }

        /**
         * 用来算"适应窗口"的参考尺寸。
         *
         * <p>注意取的是**滚动面板自身**的尺寸，不是视口可见区域：视口在滚动条出现后会变窄，
         * 若拿它当基准，放大→出滚动条→基准变小→画面变回原来的大小→滚动条消失，会来回抖。
         * 滚动面板的尺寸只在窗口缩放时变化，所以基准是稳定的。</p>
         */
        private int viewportWidth() {
            int w;
            if (scroll != null) {
                java.awt.Insets in = scroll.getInsets();
                w = scroll.getWidth() - in.left - in.right;
            } else {
                w = getWidth();
            }
            return Math.max(60, w);
        }

        private int viewportHeight() {
            int h;
            if (scroll != null) {
                java.awt.Insets in = scroll.getInsets();
                h = scroll.getHeight() - in.top - in.bottom;
            } else {
                h = getHeight();
            }
            return Math.max(60, h);
        }

        /** 按当前 zoom 重算比例与画布首选尺寸（滚动条由 JScrollPane 自己管） */
        void applyTransform() {
            int vw = viewportWidth(), vh = viewportHeight();
            lastVpW = vw;
            lastVpH = vh;
            double aw = Math.max(40, vw - PAD * 2.0);
            double ah = Math.max(40, vh - PAD * 2.0);
            /* 只缩不放（2026-11-03 晚）：原来这里直接取 min(aw/宽, ah/高)，
             * 小面板会被"适应窗口"放大到 200%+ —— 打开就是放大的，看着像自己放大了。
             * 现在封顶 1.0：画布最多 1:1，永不自动放大；要放大请按住热键用放大镜。 */
            fitScale = Math.min(1.0, Math.min(aw / parentW, ah / parentH));
            if (!(fitScale > 0)) {
                fitScale = 1;
            }
            scale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, fitScale * zoom));
            ox = PAD;
            oy = PAD;
            int w = (int) Math.ceil(parentW * scale) + PAD * 2;
            int h = (int) Math.ceil(parentH * scale) + PAD * 2;
            Dimension d = new Dimension(w, h);
            if (!d.equals(getPreferredSize())) {
                setPreferredSize(d);
                revalidate();
            }
            updateZoomLabel();
            repaint();
        }

        private void updateZoomLabel() {
            if (zoomLabel != null) {
                zoomLabel.setText(Math.round(scale * 100) + "%");
            }
        }

        /** 以画布坐标 (mx,my) 为中心缩放到 targetZoom */
        void zoomAt(double targetZoom, int mx, int my) {
            Point model = toModel(mx, my);
            double z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, targetZoom));
            if (Math.abs(z - zoom) < 1e-6) {
                return;
            }
            zoom = z;
            applyTransform();
            keepModelUnder(model, mx, my);
        }

        /** 按倍率缩放（工具栏 +/− 用，围绕视口中心） */
        void zoomBy(double factor) {
            int cx = scroll != null ? scroll.getViewport().getExtentSize().width / 2 : getWidth() / 2;
            int cy = scroll != null ? scroll.getViewport().getExtentSize().height / 2 : getHeight() / 2;
            Point vp = scroll != null ? scroll.getViewport().getViewPosition() : new Point(0, 0);
            zoomAt(zoom * factor, cx + vp.x, cy + vp.y);
        }

        /** 适应窗口（zoom = 1） */
        void fitView() {
            zoom = 1.0;
            applyTransform();
            if (scroll != null) {
                scroll.getViewport().setViewPosition(new Point(0, 0));
            }
        }

        /** 让模型点 model 保持在画布坐标 (mx,my) 处（缩放后不跑偏） */
        private void keepModelUnder(Point model, int mx, int my) {
            if (scroll == null) {
                return;
            }
            int nx = (int) Math.round(ox + model.x * scale);
            int ny = (int) Math.round(oy + model.y * scale);
            JViewport vp = scroll.getViewport();
            Point pos = vp.getViewPosition();
            Dimension view = getPreferredSize();
            Dimension ext = vp.getExtentSize();
            int px = nx - mx + pos.x;
            int py = ny - my + pos.y;
            px = Math.max(0, Math.min(Math.max(0, view.width - ext.width), px));
            py = Math.max(0, Math.min(Math.max(0, view.height - ext.height), py));
            vp.setViewPosition(new Point(px, py));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            ensureTransform();          // 比例/原点由 applyTransform 统一算（滚动 + 缩放）
            updateZoomLabel();

            // 父容器底
            g.setColor(new Color(0x2C, 0x31, 0x3B));
            g.fillRect(ox, oy, (int) Math.round(parentW * scale), (int) Math.round(parentH * scale));
            g.setColor(new Color(0x55, 0x5C, 0x6A));
            g.drawRect(ox, oy, (int) Math.round(parentW * scale), (int) Math.round(parentH * scale));

            /* 文字跟着缩放一起放大（2026-11-03）：只放几何不放字的话，放大后
             * 方块变大了、字还是 11 px，反而更难看清（长按 Q 就看不出细节了）。 */
            double zoomRatio = (fitScale > 0) ? (scale / fitScale) : 1.0;
            int fontSize = (int) Math.round(Math.max(8, Math.min(28, 11 * zoomRatio)));
            g.setFont(new Font("Dialog", Font.PLAIN, fontSize));
            java.awt.FontMetrics fm = g.getFontMetrics();
            for (Manifest.Node n : siblings) {
                Rectangle r = toScreen(n);
                boolean isSel = n == sel;
                java.awt.Color customBg = parseColor(n.bg);
                g.setColor(customBg != null ? customBg
                        : (n.visible
                                ? (isSel ? new Color(0x3A, 0x5A, 0x8C) : new Color(0x39, 0x40, 0x4C))
                                : new Color(0x4A, 0x2A, 0x2A)));
                g.fillRect(r.x, r.y, r.width, r.height);
                g.setColor(isSel ? new Color(0xFF, 0xD8, 0x4A) : new Color(0x6E, 0x76, 0x86));
                g.setStroke(new BasicStroke(isSel ? 2f : 1f));
                g.drawRect(r.x, r.y, r.width, r.height);
                g.setStroke(new BasicStroke(1f));

                // 按可用宽度裁字（按实际字宽算，不再写死 16 个字符）
                String label = n.label.isEmpty() ? n.cls : n.label;
                int avail = r.width - 8;
                if (avail > 10) {
                    while (label.length() > 1 && fm.stringWidth(label) > avail) {
                        label = label.substring(0, label.length() - 1);
                    }
                }
                java.awt.Color customFg = parseColor(n.fg);
                g.setColor(customFg != null ? customFg
                        : (isSel ? Color.WHITE : new Color(0xC8, 0xCE, 0xD8)));
                if (r.width > 30 && r.height > 12) {
                    g.drawString(label, r.x + 4, r.y + Math.min(r.height - 3, fm.getAscent() + 2));
                }
                if (isSel) {
                    // 右下角缩放把手（跟着缩放变大一点，方便抓）
                    int hs = (int) Math.max(6, Math.min(14, Math.round(6 * zoomRatio)));
                    g.setColor(new Color(0xFF, 0xD8, 0x4A));
                    g.fillRect(r.x + r.width - hs, r.y + r.height - hs, hs, hs);
                    // 坐标提示
                    g.setColor(new Color(0xFF, 0xE9, 0x8A));
                    g.drawString(n.x + "," + n.y + "  " + n.w + "×" + n.h,
                            r.x + 4, r.y + Math.min(r.height - 3, fm.getAscent() * 2 + 4));
                }
            }
            if (siblings.isEmpty()) {
                g.setColor(new Color(0x88, 0x90, 0x9C));
                g.drawString(L.t("canvas.noChild"), ox + 8, oy + 18);
            }
        }

        private Rectangle toScreen(Manifest.Node n) {
            return new Rectangle(
                    ox + (int) Math.round(n.x * scale),
                    oy + (int) Math.round(n.y * scale),
                    Math.max(2, (int) Math.round(n.w * scale)),
                    Math.max(2, (int) Math.round(n.h * scale)));
        }

        private Manifest.Node hit(int mx, int my) {
            for (int i = siblings.size() - 1; i >= 0; i--) {
                if (toScreen(siblings.get(i)).contains(mx, my)) {
                    return siblings.get(i);
                }
            }
            return null;
        }

        private boolean nearHandle(Manifest.Node n, int mx, int my) {
            Rectangle r = toScreen(n);
            return mx >= r.x + r.width - 8 && mx <= r.x + r.width + 4
                    && my >= r.y + r.height - 8 && my <= r.y + r.height + 4;
        }

        private void onPress(MouseEvent e) {
            requestFocusInWindow();
            Manifest.Node n = hit(e.getX(), e.getY());
            if (n == null) {
                return;
            }
            if (n != sel) {
                select(n);
            }
            dragging = true;
            resizing = nearHandle(n, e.getX(), e.getY());
            Point p = toModel(e.getX(), e.getY());
            dragDX = p.x - n.x;
            dragDY = p.y - n.y;
            startX = n.x;
            startY = n.y;
            startW = n.w;
            startH = n.h;
        }

        private void onDrag(MouseEvent e) {
            if (!dragging || sel == null) {
                return;
            }
            Point p = toModel(e.getX(), e.getY());
            if (resizing) {
                sel.w = Math.max(4, p.x - sel.x);
                sel.h = Math.max(4, p.y - sel.y);
            } else {
                sel.x = p.x - dragDX;
                sel.y = p.y - dragDY;
            }
            sel.dirty = true;
            syncSpinners(sel);
            repaint();
        }

        private void onRelease() {
            if (dragging && sel != null) {
                updateStatus();
            }
            dragging = false;
            resizing = false;
        }

        private void updateCursor(MouseEvent e) {
            Manifest.Node n = hit(e.getX(), e.getY());
            if (n != null && nearHandle(n, e.getX(), e.getY())) {
                setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
            } else if (n != null) {
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            } else {
                setCursor(Cursor.getDefaultCursor());
            }
        }

        private Point toModel(int mx, int my) {
            return new Point((int) Math.round((mx - ox) / scale), (int) Math.round((my - oy) / scale));
        }
    }
}
