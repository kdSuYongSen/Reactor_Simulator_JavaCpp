package com.darwish.nppsim;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * 布局修改链路综合回归（2026-11-03）。
 *
 * <p>验四件事：</p>
 * <ol>
 *   <li>方块位置跟随布局文件；</li>
 *   <li>方块内子组件的**尺寸**跟随（旧实现强制回默认尺寸）；</li>
 *   <li>子组件的**可见性**跟随（旧实现完全忽略）；</li>
 *   <li>应用后 {@code ui_layout_pages.json} 立刻刷新（否则编辑器读到旧坐标，
 *       下次保存就把位置写回去）。</li>
 * </ol>
 */
public class LayoutFixCheck {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(1800);

        UI ui = null;
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof UI && w.isVisible()) {
                ui = (UI) w;
            }
        }
        if (ui == null) {
            System.out.println("FIX_FAIL 没有主控室窗口");
            System.exit(1);
        }
        Field bm = UI.class.getDeclaredField("layoutBlockMap");
        bm.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, javax.swing.JComponent> blocks =
                (Map<String, javax.swing.JComponent>) bm.get(ui);

        File f = UiLayoutFile.locate();
        String original = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        javax.swing.JComponent ann = blocks.get("annunciator");
        javax.swing.JComponent light0 = (javax.swing.JComponent) ann.getComponent(0);
        javax.swing.JComponent light1 = (javax.swing.JComponent) ann.getComponent(1);
        int w0 = light0.getWidth(), h0 = light0.getHeight();

        org.json.JSONObject root = new org.json.JSONObject();
        root.put("mode", "absolute");
        root.put("layoutVersion", 2);
        org.json.JSONObject panels = new org.json.JSONObject();
        int azX = 400, azY = 300;
        panels.put("az_panel", new org.json.JSONObject()
                .put("x", azX).put("y", azY).put("w", 268).put("h", 129).put("visible", true));
        org.json.JSONObject kids = new org.json.JSONObject();
        kids.put("0", new org.json.JSONObject().put("cls", "JTextField")
                .put("x", 7).put("y", 5).put("w", w0 + 20).put("h", h0 + 6).put("visible", true));
        kids.put("1", new org.json.JSONObject().put("cls", "JTextField")
                .put("x", 7).put("y", 5).put("w", Math.max(1, light1.getWidth()))
                .put("h", Math.max(1, light1.getHeight())).put("visible", false));
        panels.put("annunciator", new org.json.JSONObject()
                .put("x", 20).put("y", 20).put("w", 570).put("h", 120).put("visible", true)
                .put("children", kids));
        root.put("panels", panels);
        Files.write(f.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
        Thread.sleep(3200);

        boolean posOk = Math.abs(blocks.get("az_panel").getX() - azX) <= 1;
        boolean sizeOk = Math.abs(light0.getWidth() - (w0 + 20)) <= 1
                && Math.abs(light0.getHeight() - (h0 + 6)) <= 1;
        boolean visOk = !light1.isVisible();

        Thread.sleep(1200);          // 清单刷新是应用后立刻写，这里再等一轮确保落盘
        org.json.JSONObject manifest = new org.json.JSONObject(
                new String(Files.readAllBytes(new File(f.getParentFile(),
                        "ui_layout_pages.json").toPath()), StandardCharsets.UTF_8));
        int manX = -1;
        for (int i = 0; i < manifest.getJSONArray("pages").length(); i++) {
            org.json.JSONObject pg = manifest.getJSONArray("pages").getJSONObject(i);
            if (!"main".equals(pg.optString("id"))) {
                continue;
            }
            for (int j = 0; j < pg.getJSONArray("blocks").length(); j++) {
                org.json.JSONObject b = pg.getJSONArray("blocks").getJSONObject(j);
                if ("az_panel".equals(b.optString("id"))) {
                    manX = b.getInt("x");
                }
            }
        }
        boolean manifestOk = manX == azX;

        System.out.println("位置=" + (posOk ? "OK" : "FAIL") + "（az_panel x=" + blocks.get("az_panel").getX()
                + " 期望 " + azX + "）");
        System.out.println("尺寸=" + (sizeOk ? "OK" : "FAIL") + "（子组件 " + light0.getWidth() + "x"
                + light0.getHeight() + " 期望 " + (w0 + 20) + "x" + (h0 + 6) + "）");
        System.out.println("可见性=" + (visOk ? "OK" : "FAIL") + "（子组件1 visible=" + light1.isVisible() + "）");
        System.out.println("清单刷新=" + (manifestOk ? "OK" : "FAIL") + "（清单 az_panel x=" + manX + "）");
        boolean all = posOk && sizeOk && visOk && manifestOk;
        System.out.println(all ? "FIX_OK" : "FIX_FAIL");

        Files.write(f.toPath(), original.getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1500);
        System.exit(all ? 0 : 1);
    }
}
