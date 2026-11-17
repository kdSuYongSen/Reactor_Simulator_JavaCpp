package com.darwish.nppsim;

import java.io.File;
import java.lang.reflect.Method;

/**
 * "菜单 → 工具 → 布局编辑器 (jar)" 启动链路自检（2026-11-03）。
 *
 * <p>验证：能把随包资源解到 {@code <布局目录>/tools/}、能用**本进程的 JVM** 起起来、
 * 输出会落到 {@code layout_editor.log}、并且不会启动即退出。</p>
 */
public class EditorLaunchCheck {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(1200);

        UI ui = null;
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof UI && w.isVisible()) {
                ui = (UI) w;
            }
        }
        if (ui == null) {
            System.out.println("LAUNCH_FAIL 没有主控室窗口");
            System.exit(1);
        }

        File dir = UiLayoutFile.writePath().getParentFile();
        File jar = new File(new File(dir, "tools"), "RBMK-LayoutEditor.jar");
        File log = new File(dir, "layout_editor.log");
        if (jar.isFile()) {
            jar.delete();
        }
        if (log.isFile()) {
            log.delete();
        }

        Method m = UI.class.getDeclaredMethod("launchLayoutEditorJar");
        m.setAccessible(true);
        m.invoke(ui);

        Thread.sleep(6000);

        boolean jarOk = jar.isFile();
        long size = jarOk ? jar.length() : -1;
        String text = log.isFile()
                ? new String(java.nio.file.Files.readAllBytes(log.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8) : "";
        boolean crashed = text.contains("Exception") || text.contains("Error");
        System.out.println("解出的编辑器 jar: " + (jarOk ? size + " 字节" : "缺失"));
        System.out.println("日志: " + (log.isFile() ? (text.isEmpty() ? "空（正常）" : text.trim()) : "未生成"));
        System.out.println((jarOk && size > 50000 && !crashed) ? "LAUNCH_OK" : "LAUNCH_FAIL");

        // 收尾：关掉刚拉起来的编辑器（javaw 不占控制台，按命令行匹配）
        try {
            new ProcessBuilder("taskkill", "/F", "/IM", "javaw.exe").start().waitFor();
        } catch (Exception ignore) {
            // 非 Windows 或没有权限就算了
        }
        System.exit(jarOk && size > 50000 && !crashed ? 0 : 1);
    }
}
