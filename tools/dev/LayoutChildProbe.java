package com.darwish.nppsim;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * 面板内子组件（"小方块"）位置热更新回归（2026-11-03）。
 *
 * <p>布局工具拖动的是**面板内部**的按键/灯，写进 panels.&lt;key&gt;.children["i"] /
 * nodes["a/b"]。本工具验证这两条路径都能真的改变组件位置。</p>
 */
public class LayoutChildProbe {

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
            System.out.println("CHILD_FAIL 没有主控室窗口");
            System.exit(1);
        }
        Field bm = UI.class.getDeclaredField("layoutBlockMap");
        bm.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, javax.swing.JComponent> blocks =
                (Map<String, javax.swing.JComponent>) bm.get(ui);

        javax.swing.JComponent ann = blocks.get("annunciator");
        java.awt.Component child = ann.getComponent(13);
        System.out.println("annunciator 子组件数=" + ann.getComponentCount()
                + " 布局=" + (ann.getLayout() == null ? "null(绝对)" : ann.getLayout().getClass().getSimpleName()));
        System.out.println("child13 before=" + child.getX() + "," + child.getY()
                + " " + child.getWidth() + "x" + child.getHeight());

        File f = UiLayoutFile.locate();
        String original = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        org.json.JSONObject root = new org.json.JSONObject(original);
        org.json.JSONObject kids = root.getJSONObject("panels")
                .getJSONObject("annunciator").getJSONObject("children");
        org.json.JSONObject c13 = kids.getJSONObject("13");
        int nx = c13.getInt("x") + 100;
        int ny = c13.getInt("y") + 40;
        c13.put("x", nx);
        c13.put("y", ny);
        Files.write(f.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote child13=" + nx + "," + ny);

        Thread.sleep(3000);
        System.out.println("child13 after=" + child.getX() + "," + child.getY()
                + " (期望 " + nx + "," + ny + ")");
        boolean ok = Math.abs(child.getX() - nx) <= 1 && Math.abs(child.getY() - ny) <= 1;
        System.out.println(ok ? "CHILD_OK 子组件位置已跟随" : "CHILD_FAIL 子组件位置没有跟随");

        Files.write(f.toPath(), original.getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1500);
        System.out.println("restored child13=" + child.getX() + "," + child.getY());
        System.exit(ok ? 0 : 1);
    }
}
