package com.darwish.nppsim;

/** 探查 MCP 窗口里泵开关所在容器（判断能否整体替换）。 */
public class PumpUiProbe {

    public static void main(String[] a) throws Exception {
        Localization.init();
        Localization.setLanguage(Language.ZH_CN);
        UiStyle.applyLaf();
        Fonts.init();
        UiTheme.install();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        while (NPPSim.totalTicks < 20) {
            Thread.sleep(10);
        }
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            MCPUI f = new MCPUI();
            f.setVisible(true);
            java.awt.Component c = findLed(f.getContentPane());
            if (c == null) {
                System.out.println("没找到 Led");
            } else {
                java.awt.Container p = c.getParent();
                System.out.println("on1 = " + c.getBounds() + " 父容器="
                        + p.getClass().getName() + " layout=" + p.getLayout());
                for (java.awt.Component k : p.getComponents()) {
                    System.out.println("   子: " + k.getClass().getSimpleName()
                            + (k instanceof javax.swing.AbstractButton
                               ? " [" + ((javax.swing.AbstractButton) k).getText() + "]" : "")
                            + " " + k.getBounds());
                }
            }
            System.exit(0);
        });
    }

    private static java.awt.Component findLed(java.awt.Container c) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof eu.hansolo.steelseries.extras.Led) {
                return k;
            }
            if (k instanceof java.awt.Container) {
                java.awt.Component r = findLed((java.awt.Container) k);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
