package com.darwish.nppsim;

/** 设置对话框"语言 + 字体文件"分组冒烟测试（2026-11-12）：只构建不显示。 */
public class SettingsFontTabProbe {

    public static void main(String[] args) throws Exception {
        Localization.init();
        Loader.tables = new SteamTables();
        final Throwable[] error = new Throwable[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                SettingsDialog d = new SettingsDialog(null);
                System.out.println("SettingsDialog 构建成功");
                System.out.println("字体目录按钮文本: "
                        + findButtonText(d, Localization.t("settings.fonts.open")));
                d.dispose();
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        if (error[0] != null) {
            error[0].printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static String findButtonText(java.awt.Container root, String wanted) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.AbstractButton
                    && wanted.equals(((javax.swing.AbstractButton) c).getText())) {
                return ((javax.swing.AbstractButton) c).getText();
            }
            if (c instanceof java.awt.Container) {
                String r = findButtonText((java.awt.Container) c, wanted);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
