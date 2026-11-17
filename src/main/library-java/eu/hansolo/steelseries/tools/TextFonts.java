package eu.hansolo.steelseries.tools;


import eu.hansolo.steelseries.tools.TextFonts;
import java.awt.Font;

/**
 * 语言感知的字体创建器（供本库内部绘制文本时使用）。
 *
 * 背景：本库原先到处写死 <code>TextFonts.get("Verdana", ...)</code> / "Arial" / "Serif"，
 * 这些字体**只有拉丁字形**，一旦界面语言是中文/日文/韩文，仪表标题等就会显示成方框。
 *
 * 用法（已完成替换）：把库里的
 *     TextFonts.get("Verdana", 0, size)
 * 换成
 *     TextFonts.get("Verdana", 0, size)
 * 由应用在启动时调用 {@link #configure(String, String)} 指定"能显示当前语言文字的字族"，
 * 于是：能显示就保持原字族（英文界面观感不变），不能显示就自动换成该字族。
 */
public final class TextFonts {

    /** 由应用按界面语言设置的字体族；null 表示不做替换 */
    private static volatile String preferredFamily = null;
    /** 该语言的样本字符（用于判断某字族能否显示该语言） */
    private static volatile String probe = "";
    /**
     * 强制模式（2026-11-12）：非 null 时**不检查**原字族能否显示，一律用该字族。
     *
     * <p>用于"优先使用字体目录里的字体"：Java 的逻辑字体（Dialog）自带回退链，
     * 中文/日文也能"显示得出来"，但用的不是用户指定那份字体。只有强制才能让仪表也用上它。</p>
     */
    private static volatile String forcedFamily = null;

    private TextFonts() {
    }

    /**
     * 由应用启动时调用。
     *
     * @param family 能显示 probe 中所含字符的字族（如韩文用 "Malgun Gothic"）；为空则关闭替换
     * @param probe  该语言的样本字符
     */
    public static void configure(String family, String probeText) {
        preferredFamily = (family == null || family.trim().isEmpty()) ? null : family;
        probe = (probeText == null) ? "" : probeText;
    }

    /** 强制所有库内文本使用该字族（传 null 取消强制） */
    public static void configureForced(String family) {
        forcedFamily = (family == null || family.trim().isEmpty()) ? null : family;
    }

    /**
     * 创建用于绘制界面文本的字体：优先保持原字族，若它显示不了当前语言则换成 preferredFamily。
     *
     * @param requestedFamily 原本要用的字族（如 "Verdana"），可为 null 表示"由系统决定"
     * @param style           Font.PLAIN / BOLD / ITALIC
     * @param size            字号
     */
    public static Font get(String requestedFamily, int style, int size) {
        String forced = forcedFamily;
        if (forced != null) {
            return new Font(forced, style, size);   // 强制模式：直接用指定字族
        }
        String target = preferredFamily;
        if (target != null && !probe.isEmpty()) {
            Font requested = (requestedFamily == null) ? new Font("Dialog", style, size)
                                                       : new Font(requestedFamily, style, size);
            if (requested.canDisplayUpTo(probe) == -1) {
                return requested;              // 原字族够用（英文界面即此路径，观感不变）
            }
            return new Font(target, style, size);   // 原字族缺字 → 换成本语言字族
        }
        return (requestedFamily == null) ? new Font("Dialog", style, size)
                                         : new Font(requestedFamily, style, size);
    }

    /** 同上，但不指定原字族 */
    public static Font get(int style, int size) {
        return get(null, style, size);
    }
}
