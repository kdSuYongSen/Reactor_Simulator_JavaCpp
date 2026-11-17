package com.darwish.nppsim;

import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/**
 * 界面缩放热键（2026-11-03）。
 *
 * <p>主程序和布局编辑器**共用同一个文件** {@code <布局目录>/ui_zoom_keys.json}，
 * 所以在任意一边改完，另一边启动时自动生效 —— 两边按键不会各说各话。</p>
 *
 * <p>字段（键码用 {@link KeyEvent} 的 VK_ 常量值，跨语言/跨工具都不会有歧义）：</p>
 * <pre>
 * { "magnify": 81, "zoom_in": 61, "zoom_out": 45, "fit": 48, "loupe": 2.5 }
 *    Q(=81)        +(=61)          -(=45)        0(=48)        放大倍率
 * </pre>
 */
public final class ZoomSettings {

    /** 长按放大的键（默认 Q） */
    public int magnify = KeyEvent.VK_Q;
    /** 放大（默认 = / +） */
    public int zoomIn = KeyEvent.VK_EQUALS;
    /** 缩小（默认 -） */
    public int zoomOut = KeyEvent.VK_MINUS;
    /** 适应窗口 / 复位（默认 0） */
    public int fit = KeyEvent.VK_0;
    /** 长按放大时的倍率 */
    public double loupe = 2.5;

    private static ZoomSettings cached;

    /** 丢掉缓存，下次 load() 重新读盘（改设置 / 自检 / 另一个进程改过文件时用） */
    static void clearCache() {
        cached = null;
    }

    /** 热键文件：与 ui_layout.json 同目录 */
    public static File file() {
        File layout = UiLayoutFile.writePath();
        File dir = layout.getParentFile();
        return new File(dir != null ? dir : new File("."), "ui_zoom_keys.json");
    }

    public static ZoomSettings load() {
        if (cached != null) {
            return cached;
        }
        ZoomSettings s = new ZoomSettings();
        File f = file();
        if (f.isFile()) {
            try {
                JSONObject o = new JSONObject(new String(
                        Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                s.magnify = o.optInt("magnify", s.magnify);
                s.zoomIn = o.optInt("zoom_in", s.zoomIn);
                s.zoomOut = o.optInt("zoom_out", s.zoomOut);
                s.fit = o.optInt("fit", s.fit);
                double l = o.optDouble("loupe", s.loupe);
                if (l >= 1.2 && l <= 6.0) {
                    s.loupe = l;
                }
            } catch (Exception ignore) {
                // 文件坏了就用默认值
            }
        }
        cached = s;
        return s;
    }

    public void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("magnify", magnify);
            o.put("zoom_in", zoomIn);
            o.put("zoom_out", zoomOut);
            o.put("fit", fit);
            o.put("loupe", loupe);
            Files.write(file().toPath(), o.toString(2).getBytes(StandardCharsets.UTF_8));
            cached = this;
        } catch (Exception e) {
            System.out.println("[Zoom] 保存热键设置失败: " + e);
        }
    }

    /** 键码转显示文字（"Q"、"+"、"0"…） */
    public static String text(int code) {
        String t = KeyEvent.getKeyText(code);
        return t == null || t.isEmpty() ? String.valueOf((char) code) : t;
    }
}
