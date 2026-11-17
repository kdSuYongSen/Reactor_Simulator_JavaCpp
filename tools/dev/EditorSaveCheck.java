package com.darwish.nppsim.layeditor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 编辑器写盘链路自检（2026-11-03）。
 *
 * <p>模拟"在编辑器里把某个方块挪 120 px → 保存"，检查：</p>
 * <ol>
 *   <li>拖动的那个方块在新文件里确实是新坐标；</li>
 *   <li>没拖过的方块保持原坐标（不能被清单里的旧值覆盖）。</li>
 * </ol>
 */
public class EditorSaveCheck {

    public static void main(String[] a) throws Exception {
        File dir = new File(a.length > 0 ? a[0] : "target");
        Manifest m = Manifest.load(dir);
        if (m == null) {
            System.out.println("SAVE_FAIL 读不到 ui_layout_pages.json");
            System.exit(1);
        }
        Manifest.Page page = null;
        for (Manifest.Page p : m.pages) {
            if ("main".equals(p.id)) {
                page = p;
            }
        }
        if (page == null) {
            System.out.println("SAVE_FAIL 清单里没有 main 页");
            System.exit(1);
        }

        Manifest.Node target = null, other = null;
        for (Manifest.Node b : page.blocks) {
            if ("az_panel".equals(b.blockId)) {
                target = b;
            } else if ("coremap".equals(b.blockId)) {
                other = b;
            }
        }
        if (target == null || other == null) {
            System.out.println("SAVE_FAIL 找不到 az_panel / coremap");
            System.exit(1);
        }
        int otherX = other.x, otherY = other.y;
        System.out.println("load: az_panel=" + target.x + "," + target.y
                + " coremap=" + otherX + "," + otherY);

        target.x += 120;
        target.y += 30;
        target.dirty = true;

        String json = LayoutWriter.buildJson(page);
        File out = new File(dir, "editor_save_test.json");
        Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));

        org.json.JSONObject root = new org.json.JSONObject(json);
        org.json.JSONObject panels = root.getJSONObject("panels");
        org.json.JSONObject az = panels.getJSONObject("az_panel");
        org.json.JSONObject cm = panels.getJSONObject("coremap");
        boolean tOk = az.getInt("x") == target.x && az.getInt("y") == target.y;
        boolean oOk = cm.getInt("x") == otherX && cm.getInt("y") == otherY;
        System.out.println("saved: az_panel=" + az.getInt("x") + "," + az.getInt("y")
                + "  coremap=" + cm.getInt("x") + "," + cm.getInt("y"));
        System.out.println((tOk && oOk) ? "SAVE_OK 拖动项已保存、未动项保持原值"
                : ("SAVE_FAIL 拖动项=" + tOk + " 未动项=" + oOk));
        System.exit(tOk && oOk ? 0 : 1);
    }
}
