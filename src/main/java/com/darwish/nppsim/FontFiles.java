package com.darwish.nppsim;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * 用户字体文件（2026-11-12 新增）。
 *
 * <p>解决的问题：界面语言的目标文字只能靠"系统里已经装好的字体"来显示，
 * {@link Fonts} 里选字族用的是写死的候选清单（Microsoft YaHei / Yu Gothic / Malgun Gothic …）。
 * 一旦系统没装、或者某个字库里缺了译文用到的个别字符，界面上就是方框（tofu）。</p>
 *
 * <p>现在允许**直接放字体文件**：把 {@code .ttf / .otf / .ttc} 丢进字体目录，
 * 启动时自动载入、用 {@link GraphicsEnvironment#registerFont} 注册进 JVM，
 * 之后 {@code new Font(字族名, style, size)} 在 Swing 与仪器库（steelseries）里都能直接用。</p>
 *
 * <p>字体目录（按顺序全部扫描，可以只用一个）：</p>
 * <ol>
 *   <li>系统属性 {@code -Drbmk.fonts.dir=<目录>}（调试/便携包用）</li>
 *   <li>{@code <程序目录>/fonts}（与 ui_layout.json 同级；设置界面里"打开字体目录"就指这里）</li>
 *   <li>{@code <当前工作目录>/fonts}</li>
 *   <li>{@code <用户主目录>/.rbmk/fonts}</li>
 * </ol>
 *
 * <p>选中规则见 {@link #pickFor(String)}：按"当前界面真正会显示的文字"逐个字符核对覆盖度，
 * 挑能全覆盖的那个字族；一个都覆盖不了就退回系统候选（{@link Fonts} 的原有逻辑）。</p>
 */
public final class FontFiles {

    /** 认的字体扩展名（小写比较） */
    private static final String[] EXTENSIONS = {".ttf", ".otf", ".ttc", ".otc"};

    private static final Preferences PREFS = Preferences.userRoot().node("com.darwish/nppsim");
    /** 设置项：即使系统字体够用，也优先使用字体目录里的字体（用于换观感） */
    private static final String PREF_PREFER = "fonts.preferFile";

    /** 已注册字族（去重、保序）与其来源文件 */
    private static final List<String> FAMILIES = new ArrayList<>();
    private static final List<File> FAMILY_SOURCES = new ArrayList<>();
    /** 实际扫描到的目录 */
    private static final List<File> SCANNED = new ArrayList<>();
    private static int failedFiles = 0;
    private static boolean loaded = false;

    private FontFiles() {
    }

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    /** 主字体目录（程序目录/fonts）；不存在时会被创建，保证"打开字体目录"总有地方可放 */
    public static File primaryDir() {
        File layout = null;
        try {
            layout = UiLayoutFile.writePath();
        } catch (Throwable ignored) {
            // 拿不到程序路径时退回工作目录
        }
        File base = (layout != null) ? layout.getParentFile() : null;
        if (base == null) {
            base = new File(".");
        }
        return new File(base, "fonts");
    }

    /** 所有候选目录（去重、保序） */
    public static List<File> searchDirs() {
        Set<File> dirs = new LinkedHashSet<>();
        String prop = System.getProperty("rbmk.fonts.dir");
        if (prop != null && !prop.trim().isEmpty()) {
            dirs.add(new File(prop.trim()));
        }
        dirs.add(primaryDir());
        dirs.add(new File("fonts"));
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            dirs.add(new File(new File(home), ".rbmk" + File.separator + "fonts"));
        }
        return new ArrayList<>(dirs);
    }

    /** 确保主目录存在（"打开字体目录"按钮用），失败返回 false */
    public static boolean ensurePrimaryDir() {
        File dir = primaryDir();
        if (dir.isDirectory()) {
            return true;
        }
        try {
            return dir.mkdirs() || dir.isDirectory();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 载入
    // ------------------------------------------------------------------

    /** 启动路径调用：只载入一次 */
    public static synchronized void loadOnce() {
        if (!loaded) {
            reload();
        }
    }

    /**
     * 重新扫描并注册字体（设置界面"重新扫描"用）。
     *
     * @return 本次载入到的字族数
     */
    public static synchronized int reload() {
        loaded = true;
        FAMILIES.clear();
        FAMILY_SOURCES.clear();
        SCANNED.clear();
        failedFiles = 0;

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        // 首次运行就把主目录建出来：用户不用先去设置里点"打开目录"才知道往哪放
        ensurePrimaryDir();
        for (File dir : searchDirs()) {
            if (!dir.isDirectory()) {
                continue;
            }
            SCANNED.add(dir);
            for (File file : collect(dir, 2)) {
                try {
                    /* 一个字体文件里的所有字面：.ttc/.otc 这种集合会返回多个。
                     * Java 9+ 有 Font.createFonts(File)，但本工程按 --release 8 编译，
                     * 编译期引用不到它，所以走 loadFonts()（运行期反射 + 8 的兜底）。 */
                    Font[] fonts = loadFonts(file);
                    for (Font f : fonts) {
                        Font plain = f.deriveFont(Font.PLAIN, 14f);
                        String fam = plain.getFamily(Locale.ENGLISH);
                        if (fam == null || fam.isEmpty()) {
                            fam = plain.getFamily();
                        }
                        // 已注册（同名字族，例如系统里也有这份字库）时 registerFont 返回 false，
                        // 但字族依然可用，所以两种情况都记录。
                        ge.registerFont(plain);
                        if (!FAMILIES.contains(fam)) {
                            FAMILIES.add(fam);
                            FAMILY_SOURCES.add(file);
                        }
                    }
                } catch (Throwable t) {
                    failedFiles++;
                    System.out.println("[Fonts] 跳过无法解析的字体文件 " + file + "：" + t);
                }
            }
        }
        System.out.println("[Fonts] 字体目录 " + SCANNED
                + " → 载入 " + FAMILIES.size() + " 个字族 " + FAMILIES
                + (failedFiles > 0 ? "（" + failedFiles + " 个文件无法解析）" : ""));
        return FAMILIES.size();
    }

    /**
     * 读一个字体文件里的所有字面。
     *
     * <p>Java 9+ 用 {@code Font.createFonts(File)}：{@code .ttc/.otc} 字体集合会返回多个字面。
     * 本工程按 {@code --release 8} 编译（见 pom.xml），编译期引用不到这个方法，
     * 所以运行期反射取；Java 8 上退到 {@code Font.createFont(TRUETYPE_FONT, file)}，
     * 集合文件只能拿到第一个字面（可接受的降级，日志里仍会列出该字族）。</p>
     */
    private static Font[] loadFonts(File file) throws Exception {
        try {
            java.lang.reflect.Method m = Font.class.getMethod("createFonts", File.class);
            Object r = m.invoke(null, file);
            if (r instanceof Font[]) {
                return (Font[]) r;
            }
        } catch (NoSuchMethodException java8) {
            // Java 8：没有 createFonts，往下走单字体分支
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;        // 文件损坏等：交给调用方记日志并跳过
            }
            throw e;
        }
        return new Font[]{Font.createFont(Font.TRUETYPE_FONT, file)};
    }

    /** 递归收集字体文件（限制层数，避免用户把整个磁盘目录指进来时扫太久） */
    private static List<File> collect(File dir, int depth) {
        List<File> out = new ArrayList<>();
        File[] items;
        try {
            items = dir.listFiles();
        } catch (Throwable t) {
            return out;
        }
        if (items == null) {
            return out;
        }
        java.util.Arrays.sort(items, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : items) {
            if (f.isDirectory()) {
                if (depth > 0) {
                    out.addAll(collect(f, depth - 1));
                }
                continue;
            }
            String name = f.getName().toLowerCase(Locale.ROOT);
            for (String ext : EXTENSIONS) {
                if (name.endsWith(ext)) {
                    out.add(f);
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 选择
    // ------------------------------------------------------------------

    /** 已载入的字族（只读快照） */
    public static List<String> families() {
        return new ArrayList<>(FAMILIES);
    }

    /** 字族 → 来源文件（找不到时返回 null） */
    public static File sourceOf(String family) {
        int i = FAMILIES.indexOf(family);
        return (i >= 0 && i < FAMILY_SOURCES.size()) ? FAMILY_SOURCES.get(i) : null;
    }

    public static boolean isPreferFile() {
        return PREFS.getBoolean(PREF_PREFER, false);
    }

    public static void setPreferFile(boolean prefer) {
        try {
            PREFS.putBoolean(PREF_PREFER, prefer);
            PREFS.flush();
        } catch (Exception ignored) {
            // 首选项不可用时只在本次运行生效
        }
    }

    /**
     * 从字体文件里挑覆盖度最高的字族。
     *
     * <p>是否真的采用，由 {@link Fonts} 比较"系统字体覆盖度 vs 文件字体覆盖度"后决定 ——
     * 只有文件字体**严格更好**时才换（否则俄文这种只缺一个上标字符的情况会被无谓地换成别的字体）。</p>
     *
     * @param required 当前界面真正会用到的字符（ASCII + 当前语言全部译文）
     * @return 覆盖度最高的字族；没有载入任何字体文件时返回 null
     */
    public static String pickFor(String required) {
        loadOnce();
        if (required == null || required.isEmpty() || FAMILIES.isEmpty()) {
            return null;
        }
        int total = required.length();
        String best = null;
        int bestOk = -1;
        for (String fam : FAMILIES) {
            int ok = coverage(fam, required);
            if (ok > bestOk) {
                bestOk = ok;
                best = fam;
            }
        }
        if (best == null) {
            return null;
        }
        if (bestOk < total && (isPreferFile() || Boolean.getBoolean("npp.startup.timing"))) {
            // 只在"用户要求优先用文件字体"或诊断开关下才提示，避免正常启动刷屏
            System.out.println("[Fonts] 字体文件均不能完整覆盖当前文字（最佳 " + best
                    + "，缺 " + (total - bestOk) + "/" + total + "：" + missing(best, required, 6) + "）");
        }
        return best;
    }

    /** 某字族能显示 text 里多少个字符（0 = 找不到该字族） */
    public static int coverage(String family, String text) {
        if (family == null || text == null || text.isEmpty()) {
            return 0;
        }
        return displayable(new Font(family, Font.PLAIN, 14), text);
    }

    /** 字体能显示 text 里多少个 char（与 text.length() 同口径，便于算差集） */
    private static int displayable(Font font, String text) {
        int ok = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isSurrogate(c)) {
                ok++;                       // 增补平面字符：交给系统兜底，不计为缺字
            } else if (font.canDisplay(c)) {
                ok++;
            }
        }
        return ok;
    }

    /** 列出前几个显示不了的字符（诊断用） */
    private static String missing(String family, String text, int limit) {
        Font font = new Font(family, Font.PLAIN, 14);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < text.length() && shown < limit; i++) {
            char c = text.charAt(i);
            if (!Character.isSurrogate(c) && !font.canDisplay(c)) {
                sb.append('\'').append(c).append("'(U+")
                  .append(String.format("%04X", (int) c)).append(") ");
                shown++;
            }
        }
        return sb.toString().trim();
    }

    /** 设置界面显示用：已载入多少个字族、来自哪些目录 */
    public static String summary() {
        if (FAMILIES.isEmpty()) {
            return "0";
        }
        return String.valueOf(FAMILIES.size());
    }
}
