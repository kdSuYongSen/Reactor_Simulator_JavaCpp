package com.darwish.nppsim;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import javax.swing.UIManager;

/**
 * 字体与「语言预加载」：保证切换语言后**不出现方框（tofu）**。
 *
 * 三个层次一起做，缺一不可：
 *  1) **选择**：每种语言挑一个能显示该语言文字的字族（韩→Malgun Gothic、简中→Microsoft YaHei、
 *     繁中→Microsoft JhengHei、日→Yu Gothic UI；拉丁/西里尔语言保持系统默认）。
 *  2) **预加载（启动时一次性准备好，而不是等画出方框再补救）**：
 *     - 把该字族交给库内绘制用的 {@code eu.hansolo.steelseries.tools.TextFonts}
 *       （库里原先写死 Verdana/Arial/Serif，这些字体没有中日韩字形 → 仪表文字会变方框）；
 *     - 预先把 UIManager 里所有 "*.font" 默认值换成能显示该语言文字的字族（之后新建的
 *       组件即使自己不设字体也不会方框）；
 *     - 主动把样本字符**光栅化一次**，逼系统把字形加载进缓存（避免首帧才加载导致的闪烁/方框）。
 *  3) **兜底修复**：挂全局 AWT 监听（窗口打开/激活）+ 容器监听（组件后加入），
 *     把仍显示不了该语言的字体就地替换（保留字号与粗细）。
 *  4) **字体文件**（2026-11-12）：选字族之前先让 {@link FontFiles} 扫描字体目录并注册，
 *     系统里没有合适字体时直接用用户放进去的 .ttf/.otf，
 *     判定依据也从"一句样本"升级为"当前语言真正会显示的全部字符"。
 */
public final class Fonts {

    /** UIManager 中与字体相关的键（预加载时逐个检查并替换） */
    private static final String[] UI_FONT_KEYS = {
        "Label.font", "Button.font", "CheckBox.font", "RadioButton.font", "ToggleButton.font",
        "Menu.font", "MenuItem.font", "CheckBoxMenuItem.font", "RadioButtonMenuItem.font", "MenuBar.font",
        "PopupMenu.font", "TabbedPane.font", "TextField.font", "TextArea.font", "TextPane.font",
        "EditorPane.font", "FormattedTextField.font", "PasswordField.font", "ComboBox.font",
        "List.font", "Table.font", "TableHeader.font", "Tree.font", "TitledBorder.font",
        "OptionPane.font", "OptionPane.messageFont", "OptionPane.buttonFont", "ToolTip.font",
        "Spinner.font", "ProgressBar.font", "Panel.font", "RootPane.font", "InternalFrame.font",
        "InternalFrame.titleFont", "DesktopIcon.font", "ColorChooser.font", "FileChooser.font"
    };

    private static String family;   // null = 无需替换（拉丁/西里尔语言）
    /** true = 无条件把界面字体换成 family（用户勾了"优先使用字体目录"时） */
    private static boolean forceFamily = false;
    private static String sample = "";
    /** 当前界面真正会显示的字符集合（ASCII + 当前语言全部译文），字体文件按它判覆盖度 */
    private static String required = "";
    /** 组件字体能否显示 required 的判定缓存（Font 的 equals 按属性比较，命中率很高） */
    private static final java.util.HashMap<Font, Boolean> COVERS_CACHE = new java.util.HashMap<>();
    private static Font uiFont;     // 代表字体（用于预加载光栅化）
    private static boolean installed = false;

    private Fonts() {
    }

    /** 启动时调用一次（语言切换后再次调用即可重新预加载） */
    public static void init() {
        Language lang = Localization.getLanguage();
        /* 0) 先把用户字体目录里的字体文件注册进 JVM（2026-11-12）：
         *    之后 new Font(字族名, ...) 在 Swing 与仪器库里都能直接用。
         *    幂等 —— 语言切换时不会重复扫描目录。 */
        FontFiles.loadOnce();

        sample = sampleFor(lang);
        /* 目标文字 = ASCII（数字/单位/英文键名）+ 当前语言译文里真正出现的字符。
         * 只看 sample 会漏掉译文里的生僻字：字体"能显示这句样本"不等于"能显示全部界面文字"。 */
        required = requiredText();
        COVERS_CACHE.clear();

        String systemFamily = pickFamily(lang, sample);
        int systemOk = coverage(systemFamily, required);
        boolean systemCovers = systemOk >= required.length();
        String fileFamily = FontFiles.pickFor(required);
        int fileOk = FontFiles.coverage(fileFamily, required);

        if (FontFiles.isPreferFile() && fileFamily != null) {
            family = fileFamily;                    // 用户勾了"优先用字体目录"
            forceFamily = true;
        } else if (systemCovers) {
            family = systemFamily;                  // 系统字体够用 → 保持原观感
        } else if (fileFamily != null && fileOk > systemOk) {
            family = fileFamily;                    // 只有字体文件**确实更好**才换过来
        } else {
            family = systemFamily;                  // 都覆盖不全 → 退回系统候选（尽力而为）
        }
        if (!FontFiles.isPreferFile() || fileFamily == null) {
            forceFamily = false;
        }
        if (family != null) {
            System.out.println("[Fonts] 界面语言 " + lang.id() + " 使用字族 " + family
                    + (FontFiles.sourceOf(family) != null ? "（来自字体文件 "
                    + FontFiles.sourceOf(family).getName() + "）" : "（系统字体）"));
        }

        // ---- 2) 预加载 ----
        // 2a) 让库内绘制（仪表标题/刻度/LCD 文本）也使用能显示本语言文字的字族。
        //     probe 只取样本：这个方法是绘制热路径，探针不能是几千字的全集。
        String textProbe = !sample.isEmpty() ? sample
                : (required.length() > 64 ? required.substring(0, 64) : required);
        eu.hansolo.steelseries.tools.TextFonts.configure(family, textProbe);
        eu.hansolo.steelseries.tools.TextFonts.configureForced(forceFamily ? family : null);
        // 2b) UIManager 默认字体：替换掉显示不了本语言的项（新建组件即刻正确）
        if (family != null) {
            for (String key : UI_FONT_KEYS) {
                Font cur = UIManager.getFont(key);
                if (cur != null && (forceFamily || cur.canDisplayUpTo(required) != -1)) {
                    UIManager.put(key, new Font(family, cur.getStyle(), cur.getSize()));
                }
            }
            // 2c) 预栅格化：逼系统把字形载入缓存
            uiFont = new Font(family, Font.PLAIN, 14);
            preloadGlyphs(uiFont, required);
        } else {
            uiFont = null;
        }

        // ---- 3) 兜底修复 ----
        if (!installed) {
            installed = true;
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override
                public void eventDispatched(AWTEvent event) {
                    if (event instanceof WindowEvent) {
                        int id = event.getID();
                        Window w = ((WindowEvent) event).getWindow();
                        if (id == WindowEvent.WINDOW_OPENED) {
                            apply(w);
                            fitAll(w);          // 窗口尺寸已知 → 把放不下的文字缩到放得下
                            UiZoom.applyDefault(w);   // 套用默认缩放（若有）
                        } else if (id == WindowEvent.WINDOW_ACTIVATED) {
                            apply(w);           // 只做字体族修正，不做昂贵的逐组件 fit 扫描
                        }
                    }
                }
            }, AWTEvent.WINDOW_EVENT_MASK);
        }
        for (Window w : Window.getWindows()) {
            apply(w);
        }
    }

    /** 应用侧创建界面文本字体（英文界面下与原字族一致，中文/韩文等自动换成本语言字族） */
    public static Font text(String requestedFamily, int style, int size) {
        ensureInit();
        return eu.hansolo.steelseries.tools.TextFonts.get(requestedFamily, style, size);
    }

    /** 当前生效的界面字族（null = 用系统默认，不做替换）；设置界面与自检用 */
    public static String currentFamily() {
        ensureInit();
        return family;
    }

    /** 同上，不指定原字族 */
    public static Font text(int style, int size) {
        return text(null, style, size);
    }

    private static void ensureInit() {
        if (!installed) {
            init();
        }
    }

    /**
     * 目标文字集合：ASCII 可打印字符（数字、单位、英文键名）+ 当前语言译文里出现的所有字符。
     *
     * <p>字体文件必须能**完整**覆盖这个集合才算"能显示目标文字"，
     * 否则界面上总会有几个字变成方框。</p>
     */
    private static String requiredText() {
        StringBuilder raw = new StringBuilder(256);
        for (char c = 32; c < 127; c++) {
            raw.append(c);
        }
        raw.append(Localization.usedCharacters());
        java.util.LinkedHashSet<Character> set = new java.util.LinkedHashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\n' && c != '\r' && c != '\t') {
                set.add(c);
            }
        }
        StringBuilder out = new StringBuilder(set.size());
        for (Character c : set) {
            if (out.length() >= 8000) {
                break;
            }
            out.append(c.charValue());
        }
        return out.toString();
    }

    /** 该字族（null = 系统默认 Dialog）能显示目标文字里的多少个字符 */
    private static int coverage(String fam, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Font f = new Font(fam == null ? "Dialog" : fam, Font.PLAIN, 12);
        int ok = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isSurrogate(c) || f.canDisplay(c)) {
                ok++;
            }
        }
        return ok;
    }

    /** 单个组件字体是否够用（带缓存：同一窗口里大量组件共用几种字体） */
    private static boolean canShow(Font f) {
        if (required.isEmpty()) {
            return true;
        }
        Boolean ok = COVERS_CACHE.get(f);
        if (ok == null) {
            ok = f.canDisplayUpTo(required) == -1;
            COVERS_CACHE.put(f, ok);
        }
        return ok;
    }

    /** 把样本文字画到 1×1 离屏图上，促使字形加载（预加载的关键一步） */
    private static void preloadGlyphs(Font font, String text) {
        try {
            BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scratch.createGraphics();
            g.setFont(font);
            g.drawString(text, 0, 0);
            g.dispose();
        } catch (Throwable ignored) {
            // 预加载失败不影响功能（后续兜底修复仍会生效）
        }
    }

    private static String sampleFor(Language lang) {
        switch (lang) {
            case KO: return "한국어 설정 경보 출력";
            case ZH_CN: return "简体中文设置报警功率";
            case ZH_TW: return "繁體中文設定警報功率";
            case JA: return "日本語設定警報出力";
            default: return "";
        }
    }

    private static String pickFamily(Language lang, String probe) {
        if (probe.isEmpty()) {
            return null;                 // 拉丁/西里尔：系统默认字体足够
        }
        String[] candidates;
        switch (lang) {
            case KO: candidates = new String[]{"Malgun Gothic", "Gulim", "Batang", "Dotum", "Arial Unicode MS"}; break;
            case ZH_CN: candidates = new String[]{"Microsoft YaHei", "SimSun", "SimHei", "Arial Unicode MS"}; break;
            case ZH_TW: candidates = new String[]{"Microsoft JhengHei", "MingLiU", "PMingLiU", "Arial Unicode MS"}; break;
            case JA: candidates = new String[]{"Yu Gothic UI", "Meiryo", "MS Gothic", "MS PGothic", "Arial Unicode MS"}; break;
            default: return null;
        }
        for (String c : candidates) {
            Font f = new Font(c, Font.PLAIN, 12);
            if (f.canDisplayUpTo(probe) == -1) {
                return c;
            }
        }
        return null;
    }

    /** 递归替换：仅当组件字体显示不了当前语言字符时才换；并给每个容器挂"新增组件"监听 */
    public static void apply(Component c) {
        if (c == null || family == null) {
            return;
        }
        Font f = c.getFont();
        if (f != null && family != null && (forceFamily || !canShow(f))
                && !family.equals(f.getFamily())) {
            c.setFont(new Font(family, f.getStyle(), f.getSize()));
        }
        if (c instanceof Container) {
            Container cont = (Container) c;
            if (!hasListener(cont)) {
                cont.addContainerListener(ADD_LISTENER);
            }
            for (Component child : cont.getComponents()) {
                apply(child);
            }
        }
    }

    /**
     * 防遮挡：对文本组件，若当前字号下文字宽度超出组件宽度，则逐步缩小字号直到放得下（下限 9）。
     * 换语言后译文往往比英文长短不一，这一步保证标签/按钮/输入框的文字不会被截断。
     * 窗口尺寸已知时（WINDOW_ACTIVATED 之后）调用才有效。
     */
    public static void fitAll(java.awt.Window w) {
        if (w == null) {
            return;
        }
        for (Component c : w.getComponents()) {
            fit(c);
        }
    }

    private static void fit(Component c) {
        if (c instanceof javax.swing.JLabel || c instanceof javax.swing.AbstractButton
                || c instanceof javax.swing.JTextField) {
            String text = textOf(c);
            int width = c.getWidth();
            Font f = c.getFont();
            if (text != null && !text.isEmpty() && width > 0 && f != null) {
                java.awt.FontMetrics fm = c.getFontMetrics(f);
                int avail = width - 4;
                int size = f.getSize();
                while (size > 9 && fm.stringWidth(text) > avail) {
                    size--;
                    f = new Font(f.getFamily(), f.getStyle(), size);
                    fm = c.getFontMetrics(f);
                }
                if (size != c.getFont().getSize()) {
                    c.setFont(f);
                }
            }
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                fit(child);
            }
        }
    }

    private static String textOf(Component c) {
        try {
            if (c instanceof javax.swing.JLabel) {
                return ((javax.swing.JLabel) c).getText();
            }
            if (c instanceof javax.swing.AbstractButton) {
                return ((javax.swing.AbstractButton) c).getText();
            }
            if (c instanceof javax.swing.JTextField) {
                return ((javax.swing.JTextField) c).getText();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final ContainerListener ADD_LISTENER = new ContainerListener() {
        @Override
        public void componentAdded(ContainerEvent e) {
            apply(e.getChild());          // 后加入的组件立即修正
        }

        @Override
        public void componentRemoved(ContainerEvent e) {
        }
    };

    private static boolean hasListener(Container c) {
        for (ContainerListener l : c.getContainerListeners()) {
            if (l == ADD_LISTENER) {
                return true;
            }
        }
        return false;
    }
}
