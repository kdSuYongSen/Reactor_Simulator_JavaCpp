package com.darwish.nppsim;

import java.awt.event.KeyEvent;
import java.io.File;

/**
 * 热键配置"两边共用"自检（2026-11-03）。
 *
 * <p>模拟器 {@link ZoomSettings} 与布局编辑器 {@code layeditor.ZoomKeys} 必须读同一份
 * {@code ui_zoom_keys.json}：任意一边写，另一边读到的要一致。</p>
 */
public class ZoomKeysCheck {

    public static void main(String[] a) throws Exception {
        File f = ZoomSettings.file();
        String backup = f.isFile()
                ? new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8) : null;

        // 1) 模拟器写 → 编辑器读
        ZoomSettings.clearCache();
        ZoomSettings zs = ZoomSettings.load();
        zs.magnify = KeyEvent.VK_M;
        zs.zoomIn = KeyEvent.VK_CLOSE_BRACKET;
        zs.zoomOut = KeyEvent.VK_OPEN_BRACKET;
        zs.fit = KeyEvent.VK_F9;
        zs.loupe = 3.2;
        zs.save();

        com.darwish.nppsim.layeditor.ZoomKeys ek =
                com.darwish.nppsim.layeditor.ZoomKeys.load(f.getParentFile());
        boolean bridge1 = ek.magnify == KeyEvent.VK_M && ek.zoomIn == KeyEvent.VK_CLOSE_BRACKET
                && ek.zoomOut == KeyEvent.VK_OPEN_BRACKET && ek.fit == KeyEvent.VK_F9
                && Math.abs(ek.loupe - 3.2) < 1e-6;

        // 2) 编辑器写 → 模拟器读
        ek.magnify = KeyEvent.VK_F2;
        ek.zoomIn = KeyEvent.VK_9;
        ek.zoomOut = KeyEvent.VK_8;
        ek.fit = KeyEvent.VK_F10;
        ek.loupe = 1.8;
        ek.save(f.getParentFile());

        ZoomSettings.clearCache();
        ZoomSettings back = ZoomSettings.load();
        boolean bridge2 = back.magnify == KeyEvent.VK_F2 && back.zoomIn == KeyEvent.VK_9
                && back.zoomOut == KeyEvent.VK_8 && back.fit == KeyEvent.VK_F10
                && Math.abs(back.loupe - 1.8) < 1e-6;

        System.out.println("文件: " + f.getAbsolutePath());
        System.out.println("模拟器写→编辑器读=" + bridge1 + "；编辑器写→模拟器读=" + bridge2);
        System.out.println(bridge1 && bridge2 ? "KEYS_OK" : "KEYS_FAIL");

        if (backup != null) {
            java.nio.file.Files.write(f.toPath(), backup.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
        } else {
            f.delete();
        }
        ZoomSettings.clearCache();
        System.exit(bridge1 && bridge2 ? 0 : 1);
    }
}
