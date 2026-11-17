package com.darwish.nppsim;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * 布局热更新回归（2026-11-03）。
 *
 * <p>模拟"布局工具改一个方块的位置 → 程序每秒轮询 → 界面跟随"的完整链路：</p>
 * <ol>
 *   <li>启动主窗口，记录 az_panel 的当前坐标；</li>
 *   <li>把 ui_layout.json 里 az_panel 的 x 挪 +300，写回；</li>
 *   <li>等 3 秒，再读一次坐标；</li>
 *   <li>把文件原样写回。</li>
 * </ol>
 */
public class LayoutApplyProbe {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(1500);

        UI ui = null;
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof UI && w.isVisible()) {
                ui = (UI) w;
            }
        }
        if (ui == null) {
            System.out.println("PROBE_FAIL 没有主控室窗口");
            System.exit(1);
        }
        Field bm = UI.class.getDeclaredField("layoutBlockMap");
        bm.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, javax.swing.JComponent> blocks =
                (Map<String, javax.swing.JComponent>) bm.get(ui);
        Field ft = UI.class.getDeclaredField("absoluteLayoutActive");
        ft.setAccessible(true);

        File f = UiLayoutFile.locate();
        System.out.println("layoutFile=" + (f == null ? "null" : f.getAbsolutePath()));
        System.out.println("absoluteLayoutActive=" + ft.get(ui));
        if (f == null) {
            System.out.println("PROBE_FAIL 程序找不到 ui_layout.json");
            System.exit(1);
        }
        String original = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        javax.swing.JComponent az = blocks.get("az_panel");
        System.out.println("before az_panel=" + az.getX() + "," + az.getY()
                + " " + az.getWidth() + "x" + az.getHeight());

        // 用 JSON 改坐标（模拟编辑器保存），保持 layoutVersion 不变
        org.json.JSONObject root = new org.json.JSONObject(original);
        org.json.JSONObject panel = root.getJSONObject("panels").getJSONObject("az_panel");
        int newX = panel.getInt("x") + 300;
        int newY = panel.getInt("y") + 40;
        panel.put("x", newX);
        panel.put("y", newY);
        Files.write(f.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote az_panel=" + newX + "," + newY);

        Thread.sleep(3000);
        System.out.println("after  az_panel=" + az.getX() + "," + az.getY()
                + " (期望 " + newX + "," + newY + ")");

        boolean ok = Math.abs(az.getX() - newX) <= 1 && Math.abs(az.getY() - newY) <= 1;
        System.out.println(ok ? "PROBE_OK 位置已跟随布局文件" : "PROBE_FAIL 位置没有跟随布局文件");

        Files.write(f.toPath(), original.getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1500);
        System.out.println("restored az_panel=" + az.getX() + "," + az.getY());
        System.exit(ok ? 0 : 1);
    }
}
