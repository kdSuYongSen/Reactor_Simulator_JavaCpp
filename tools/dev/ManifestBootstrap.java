package com.darwish.nppsim;

/**
 * 刷新页面清单（2026-11-13）。
 *
 * <p>电网 / 柴油机 / 全厂总图这三个窗口是"按需打开"的，只有真正创建过才会进
 * {@code ui_layout_pages.json}。布局编辑器靠这个清单列页面，所以这里把它们各建一次
 * （不显示），等清单落盘后再退出。</p>
 */
public class ManifestBootstrap {

    public static void main(String[] a) throws Exception {
        Localization.init();
        Localization.setLanguage(Language.ZH_CN);
        UiStyle.applyLaf();
        Fonts.init();
        UiTheme.install();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        while (NPPSim.totalTicks < 40) {
            Thread.sleep(10);
        }
        StandardIC.apply();

        // 逐个建出来并短暂显示：方块枚举（discover）依赖真实的组件树与布局尺寸
        for (int i = 0; i < 7; i++) {
            final int k = i;
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                javax.swing.JFrame f;
                switch (k) {
                    case 0: f = new GridSystemUI(); break;
                    case 1: f = new EmergencyDieselUI(); break;
                    case 2: f = new PlantOverviewUI(); break;
                    case 3: f = new MCPUI(); break;
                    case 4: f = new FeedwaterUI(); break;
                    case 5: f = new CondensateUI(); break;
                    default: f = new DearatorUI(); break;
                }
                f.setVisible(true);
                f.setLocation(-4000, -4000);        // 摆到屏幕外，不打扰
                f.validate();
            });
            Thread.sleep(1800);
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window w : java.awt.Window.getWindows()) {
                    if (w instanceof javax.swing.JFrame && !(w instanceof UI)) {
                        w.dispose();
                    }
                }
            });
            Thread.sleep(300);
        }
        // 目录清单是注册后 1.2 s 落盘，多等一会儿确保写完
        Thread.sleep(2500);
        PageLayout.dumpManifest();
        System.out.println("页面清单已刷新: " + new java.io.File("target/ui_layout_pages.json").getAbsolutePath());
        System.exit(0);
    }
}
