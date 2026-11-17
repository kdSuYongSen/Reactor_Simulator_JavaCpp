package com.darwish.nppsim;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 子页面（汽轮机窗口）布局热更新回归（2026-11-03）。
 *
 * <p>主窗口走 UI#applyAbsoluteLayout，子窗口走 PageLayout#apply，是两套实现。
 * 本工具验证子窗口这条路也能按 {@code <pageId>.layout.json} 移动方块。</p>
 */
public class SubPageLayoutProbe {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(2500);          // 等 PageLayout 的 350 ms 初始化 + 布局落定

        PageLayout.Page page = null;
        for (PageLayout.Page p : PageLayout.pages()) {
            if ("TGUI".equals(p.id)) {
                page = p;
            }
        }
        if (page == null) {
            System.out.println("SUB_FAIL 没有登记 TGUI 页面");
            System.exit(1);
        }
        System.out.println("TGUI host=" + (page.host == null ? "null" : page.host.getClass().getSimpleName())
                + " blocks=" + page.blocks.size() + " active=" + page.active);

        javax.swing.JComponent b0 = page.blocks.get("0");
        javax.swing.JComponent b8 = page.blocks.get("8");
        System.out.println("before block0=" + b0.getX() + "," + b0.getY()
                + " block8=" + b8.getX() + "," + b8.getY());

        File f = page.file;
        System.out.println("file=" + f.getAbsolutePath() + " exists=" + f.isFile());
        String original = f.isFile()
                ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;

        int nx = b0.getX() + 60;
        int ny = b0.getY() + 20;
        org.json.JSONObject root = new org.json.JSONObject();
        root.put("mode", "absolute");
        root.put("layoutVersion", 2);
        org.json.JSONObject panels = new org.json.JSONObject();
        // 只写被移动的这一块，其余保持默认（验证"只改一个"时其它不被打乱）
        panels.put("0", new org.json.JSONObject()
                .put("x", nx).put("y", ny)
                .put("w", b0.getWidth()).put("h", b0.getHeight()).put("visible", true));
        root.put("panels", panels);
        Files.write(f.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));

        Thread.sleep(3000);
        System.out.println("after  block0=" + b0.getX() + "," + b0.getY()
                + " (期望 " + nx + "," + ny + ")"
                + "   block8=" + b8.getX() + "," + b8.getY() + "（应保持不变）");
        boolean ok = Math.abs(b0.getX() - nx) <= 1 && Math.abs(b0.getY() - ny) <= 1;
        System.out.println(ok ? "SUB_OK 子窗口方块位置已跟随" : "SUB_FAIL 子窗口方块没有跟随");

        if (original != null) {
            Files.write(f.toPath(), original.getBytes(StandardCharsets.UTF_8));
        } else {
            f.delete();
        }
        Thread.sleep(1200);
        System.exit(ok ? 0 : 1);
    }
}
