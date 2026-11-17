package com.darwish.nppsim;

/**
 * 字体文件自检（2026-11-12）。
 *
 * <p>验证整条链路：目录扫描 → registerFont → 按"目标文字"选字族 → 语言切换后重选。
 * 会临时切换语言与"优先文件"开关，结束时恢复原值，不影响用户设置。</p>
 *
 * <p>用法：先从 C:\Windows\Fonts 拷一个字体文件到字体目录再跑。</p>
 */
public class FontFileProbe {

    public static void main(String[] args) {
        Localization.init();
        Language original = Localization.getLanguage();
        boolean preferOriginal = FontFiles.isPreferFile();

        try {
            System.out.println("主字体目录      : " + FontFiles.primaryDir());
            System.out.println("扫描目录        : " + FontFiles.searchDirs());

            int n = FontFiles.reload();
            System.out.println("载入字族        : " + n + " " + FontFiles.families());
            for (String fam : FontFiles.families()) {
                System.out.println("                 - " + fam + "  ← " + FontFiles.sourceOf(fam));
            }

            System.out.println("pickFor(中文)   : " + FontFiles.pickFor("简体中文测试报警功率"));
            System.out.println("pickFor(ASCII)  : " + FontFiles.pickFor("Power 1234 MPa"));
            System.out.println("pickFor(韩文)   : " + FontFiles.pickFor("한국어 설정"));

            String used = Localization.usedCharacters();
            System.out.println("当前语言        : " + original.id() + "，目标字符 " + used.length() + " 个");

            Localization.setLanguage(Language.ZH_CN);
            Fonts.init();
            System.out.println("简体中文 → 字族 : " + Fonts.currentFamily());

            Localization.setLanguage(Language.KO);
            Fonts.init();
            System.out.println("韩文     → 字族 : " + Fonts.currentFamily());

            Localization.setLanguage(Language.RU);
            Fonts.init();
            System.out.println("俄文     → 字族 : " + Fonts.currentFamily());

            FontFiles.setPreferFile(true);
            Localization.setLanguage(Language.ZH_CN);
            Fonts.init();
            System.out.println("优先文件 → 字族 : " + Fonts.currentFamily());
            /* 端到端验证：新建组件与仪器库取字体时，实际拿到的是不是这个字族 */
            javax.swing.JLabel probeLabel = new javax.swing.JLabel("报警功率");
            System.out.println("新建 JLabel 字族 : " + probeLabel.getFont().getFamily());
            System.out.println("Fonts.text 字族  : "
                    + Fonts.text("Dialog", java.awt.Font.BOLD, 12).getFamily());

            // 顺带校验 7 份 i18n 都能解析、且带了新增的字体键（解析失败会退回键名本身）
            System.out.println("i18n 字体键校验：");
            for (Language l : Language.values()) {
                Localization.setLanguage(l);
                System.out.println("  " + l.id() + " → " + Localization.t("settings.fonts.group")
                        + " / " + Localization.t("settings.fonts.rescan")
                        + " / 目标字符 " + Localization.usedCharacters().length());
            }
        } finally {
            // 恢复用户设置，避免自检改掉真实的语言/开关
            FontFiles.setPreferFile(preferOriginal);
            Localization.setLanguage(original);
            Fonts.init();
            System.out.println("已恢复语言      : " + Localization.getLanguage().id());
        }
        System.out.flush();
        System.exit(0);
    }
}
