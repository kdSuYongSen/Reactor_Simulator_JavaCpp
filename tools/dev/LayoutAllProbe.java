package com.darwish.nppsim;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主窗口"所有方块 + 所有子组件"位置热更新回归（2026-11-03）。
 *
 * <p>逐项平移，逐项核对，把没跟上的点名打印出来 —— 用来定位"某些组件位置改不动"。</p>
 */
public class LayoutAllProbe {

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
            System.out.println("ALL_FAIL 没有主控室窗口");
            System.exit(1);
        }
        Field bm = UI.class.getDeclaredField("layoutBlockMap");
        bm.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, javax.swing.JComponent> blocks =
                (Map<String, javax.swing.JComponent>) bm.get(ui);

        File f = UiLayoutFile.locate();
        String original = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        org.json.JSONObject root = new org.json.JSONObject();
        root.put("mode", "absolute");
        root.put("layoutVersion", 2);
        org.json.JSONObject panels = new org.json.JSONObject();
        Map<String, int[]> want = new LinkedHashMap<>();
        Map<String, int[]> wantKid = new LinkedHashMap<>();
        for (Map.Entry<String, javax.swing.JComponent> e : blocks.entrySet()) {
            javax.swing.JComponent c = e.getValue();
            int nx = c.getX() + 17, ny = c.getY() + 11;
            org.json.JSONObject b = new org.json.JSONObject()
                    .put("x", nx).put("y", ny)
                    .put("w", Math.max(1, c.getWidth())).put("h", Math.max(1, c.getHeight()))
                    .put("visible", true);
            // 每个方块里挑第一个"有位置"的子组件一起平移
            org.json.JSONObject kids = new org.json.JSONObject();
            java.awt.Component[] comps = c.getComponents();
            for (int i = 0; i < comps.length; i++) {
                java.awt.Component k = comps[i];
                if (k.getWidth() > 0 && k.getHeight() > 0) {
                    kids.put(String.valueOf(i), new org.json.JSONObject()
                            .put("cls", k.getClass().getSimpleName())
                            .put("x", k.getX() + 5).put("y", k.getY() + 3)
                            .put("w", k.getWidth()).put("h", k.getHeight())
                            .put("visible", true));
                    wantKid.put(e.getKey() + "#" + i, new int[]{k.getX() + 5, k.getY() + 3});
                    break;
                }
            }
            if (kids.length() > 0) {
                b.put("children", kids);
            }
            panels.put(e.getKey(), b);
            want.put(e.getKey(), new int[]{nx, ny});
        }
        root.put("panels", panels);
        Files.write(f.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));

        Thread.sleep(3500);

        int badBlocks = 0, badKids = 0;
        for (Map.Entry<String, int[]> e : want.entrySet()) {
            javax.swing.JComponent c = blocks.get(e.getKey());
            int[] w = e.getValue();
            if (Math.abs(c.getX() - w[0]) > 1 || Math.abs(c.getY() - w[1]) > 1) {
                badBlocks++;
                System.out.println("BLOCK_FAIL " + e.getKey() + " 期望 " + w[0] + "," + w[1]
                        + " 实际 " + c.getX() + "," + c.getY());
            }
        }
        for (Map.Entry<String, int[]> e : wantKid.entrySet()) {
            String[] parts = e.getKey().split("#");
            javax.swing.JComponent c = blocks.get(parts[0]);
            java.awt.Component k = c.getComponent(Integer.parseInt(parts[1]));
            int[] w = e.getValue();
            if (Math.abs(k.getX() - w[0]) > 1 || Math.abs(k.getY() - w[1]) > 1) {
                badKids++;
                System.out.println("CHILD_FAIL " + e.getKey() + " 期望 " + w[0] + "," + w[1]
                        + " 实际 " + k.getX() + "," + k.getY());
            }
        }
        System.out.println("方块 " + want.size() + " 个，失败 " + badBlocks
                + "；子组件 " + wantKid.size() + " 个，失败 " + badKids);
        System.out.println(badBlocks == 0 && badKids == 0 ? "ALL_OK" : "ALL_FAIL");

        Files.write(f.toPath(), original.getBytes(StandardCharsets.UTF_8));
        Thread.sleep(800);
        System.exit(badBlocks == 0 && badKids == 0 ? 0 : 1);
    }
}
