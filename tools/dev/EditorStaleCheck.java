package com.darwish.nppsim.layeditor;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.swing.SwingUtilities;

/**
 * "编辑器把已调好的位置弹回去"回归（2026-11-03）。
 *
 * <p>场景：布局文件里 az_panel 已经在 (400,300)，但清单 ui_layout_pages.json 还是旧的 (10,107)。
 * 打开编辑器、只拖动 coremap、保存 —— az_panel 必须**保持 400,300**，不能被清单旧坐标覆盖。</p>
 */
public class EditorStaleCheck {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "target");
        File layout = new File(dir, "ui_layout.json");
        File manifestFile = new File(dir, "ui_layout_pages.json");
        String layoutBackup = new String(Files.readAllBytes(layout.toPath()), StandardCharsets.UTF_8);
        String manifestBackup = new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);

        // 1) 布局文件：az_panel 挪到 400,300（模拟"上次已经调好并保存"）
        org.json.JSONObject lay = new org.json.JSONObject(layoutBackup);
        lay.getJSONObject("panels").getJSONObject("az_panel").put("x", 400).put("y", 300);
        Files.write(layout.toPath(), lay.toString(2).getBytes(StandardCharsets.UTF_8));

        // 2) 清单：az_panel 仍写旧坐标（模拟"清单是启动时的快照"）
        org.json.JSONObject man = new org.json.JSONObject(manifestBackup);
        for (int i = 0; i < man.getJSONArray("pages").length(); i++) {
            org.json.JSONObject pg = man.getJSONArray("pages").getJSONObject(i);
            if (!"main".equals(pg.optString("id"))) {
                continue;
            }
            for (int j = 0; j < pg.getJSONArray("blocks").length(); j++) {
                org.json.JSONObject b = pg.getJSONArray("blocks").getJSONObject(j);
                if ("az_panel".equals(b.optString("id"))) {
                    b.put("x", 10).put("y", 107);
                }
            }
        }
        Files.write(manifestFile.toPath(), man.toString(2).getBytes(StandardCharsets.UTF_8));

        Manifest m = Manifest.load(dir);
        final Editor[] holder = new Editor[1];
        SwingUtilities.invokeAndWait(() -> {
            Editor ed = new Editor(m);
            ed.setVisible(true);
            holder[0] = ed;
        });
        Editor ed = holder[0];
        Thread.sleep(1200);

        Manifest.Page page = null;
        for (Manifest.Page p : m.pages) {
            if ("main".equals(p.id)) {
                page = p;
            }
        }
        Manifest.Node az = page.block("az_panel");
        Manifest.Node cm = page.block("coremap");
        System.out.println("编辑器载入后的 az_panel = " + az.x + "," + az.y + "（应为 400,300）");
        boolean seededOk = az.x == 400 && az.y == 300;

        // 3) 只拖 coremap
        SwingUtilities.invokeAndWait(() -> ed.select(cm));
        Thread.sleep(300);
        Field cf = Editor.class.getDeclaredField("canvas");
        cf.setAccessible(true);
        Component canvas = (Component) cf.get(ed);
        canvas.doLayout();
        canvas.paint(canvas.getGraphics());
        Field oxF = canvas.getClass().getDeclaredField("ox");
        Field oyF = canvas.getClass().getDeclaredField("oy");
        Field scF = canvas.getClass().getDeclaredField("scale");
        oxF.setAccessible(true);
        oyF.setAccessible(true);
        scF.setAccessible(true);
        double scale = scF.getDouble(canvas);
        int cx = oxF.getInt(canvas) + (int) Math.round((cm.x + cm.w / 2.0) * scale);
        int cy = oyF.getInt(canvas) + (int) Math.round((cm.y + cm.h / 2.0) * scale);
        SwingUtilities.invokeAndWait(() -> {
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED, cx, cy));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED, cx + 20, cy + 10));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED, cx + 20, cy + 10));
        });
        Thread.sleep(200);
        Method save = Editor.class.getDeclaredMethod("doSave");
        save.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                save.invoke(ed);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        Thread.sleep(400);

        org.json.JSONObject out = new org.json.JSONObject(
                new String(Files.readAllBytes(layout.toPath()), StandardCharsets.UTF_8));
        org.json.JSONObject savedAz = out.getJSONObject("panels").getJSONObject("az_panel");
        int sx = savedAz.getInt("x"), sy = savedAz.getInt("y");
        System.out.println("保存后的 az_panel = " + sx + "," + sy + "（必须仍是 400,300）");
        boolean kept = sx == 400 && sy == 300;
        System.out.println((seededOk && kept) ? "STALE_OK 编辑器不再用清单旧坐标覆盖已保存的位置"
                : ("STALE_FAIL 载入=" + seededOk + " 保持=" + kept));

        Files.write(layout.toPath(), layoutBackup.getBytes(StandardCharsets.UTF_8));
        Files.write(manifestFile.toPath(), manifestBackup.getBytes(StandardCharsets.UTF_8));
        System.exit(seededOk && kept ? 0 : 1);
    }

    private static MouseEvent mouse(Component src, int id, int x, int y) {
        return new MouseEvent(src, id, System.currentTimeMillis(), 0, x, y, 1, false,
                id == MouseEvent.MOUSE_PRESSED ? MouseEvent.BUTTON1 : MouseEvent.NOBUTTON);
    }
}
