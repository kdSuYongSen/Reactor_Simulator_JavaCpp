package com.darwish.nppsim.layeditor;

import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/**
 * 缩放热键（2026-11-03）—— 与模拟器**共用** {@code <布局目录>/ui_zoom_keys.json}。
 *
 * <p>在编辑器里改完，模拟器下次启动（或点"应用"）就跟着变；反之亦然。
 * 键码存 {@link KeyEvent} 的 VK_ 值，跨语言没有歧义。</p>
 */
public final class ZoomKeys {

    public int magnify = KeyEvent.VK_Q;
    public int zoomIn = KeyEvent.VK_EQUALS;
    public int zoomOut = KeyEvent.VK_MINUS;
    public int fit = KeyEvent.VK_0;
    public double loupe = 2.5;

    public static File file(File dir) {
        return new File(dir != null ? dir : new File("."), "ui_zoom_keys.json");
    }

    public static ZoomKeys load(File dir) {
        ZoomKeys k = new ZoomKeys();
        File f = file(dir);
        if (!f.isFile()) {
            return k;
        }
        try {
            JSONObject o = new JSONObject(new String(
                    Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            k.magnify = o.optInt("magnify", k.magnify);
            k.zoomIn = o.optInt("zoom_in", k.zoomIn);
            k.zoomOut = o.optInt("zoom_out", k.zoomOut);
            k.fit = o.optInt("fit", k.fit);
            double l = o.optDouble("loupe", k.loupe);
            if (l >= 1.2 && l <= 6.0) {
                k.loupe = l;
            }
        } catch (Exception ignore) {
            // 文件坏了用默认值
        }
        return k;
    }

    public void save(File dir) {
        try {
            JSONObject o = new JSONObject();
            o.put("magnify", magnify);
            o.put("zoom_in", zoomIn);
            o.put("zoom_out", zoomOut);
            o.put("fit", fit);
            o.put("loupe", loupe);
            Files.write(file(dir).toPath(), o.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("[ZoomKeys] 保存失败: " + e);
        }
    }

    public static String text(int code) {
        String t = KeyEvent.getKeyText(code);
        return t == null || t.isEmpty() ? String.valueOf((char) code) : t;
    }
}
