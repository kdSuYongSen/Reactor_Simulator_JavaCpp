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
 * 编辑器"拖动组件"链路自检（2026-11-03）。
 *
 * <p>按操作员实际动作走一遍：选中方块 → 在画布上按下 → 拖动 → 松开 → 保存。
 * 然后读回 ui_layout.json，看坐标是否真的变了。</p>
 */
public class EditorDragCheck {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "target");
        Manifest m = Manifest.load(dir);
        if (m == null) {
            System.out.println("DRAG_FAIL 读不到清单");
            System.exit(1);
        }
        File layout = new File(dir, "ui_layout.json");
        String backup = layout.isFile()
                ? new String(Files.readAllBytes(layout.toPath()), StandardCharsets.UTF_8) : null;

        final Editor[] holder = new Editor[1];
        SwingUtilities.invokeAndWait(() -> {
            Editor ed = new Editor(m);
            ed.setVisible(true);
            holder[0] = ed;
        });
        Editor ed = holder[0];
        Thread.sleep(1500);

        Manifest.Page page = null;
        for (Manifest.Page p : m.pages) {
            if ("main".equals(p.id)) {
                page = p;
            }
        }
        Manifest.Node az = page == null ? null : page.block("az_panel");
        if (az == null) {
            System.out.println("DRAG_FAIL 找不到 az_panel");
            System.exit(1);
        }
        int beforeX = az.x, beforeY = az.y;
        System.out.println("before=" + beforeX + "," + beforeY);

        Field cf = Editor.class.getDeclaredField("canvas");
        cf.setAccessible(true);
        Component canvas = (Component) cf.get(ed);
        System.out.println("canvas size=" + canvas.getWidth() + "x" + canvas.getHeight());

        SwingUtilities.invokeAndWait(() -> ed.select(az));
        Thread.sleep(400);
        canvas.doLayout();
        canvas.paint(canvas.getGraphics());

        Field oxF = canvas.getClass().getDeclaredField("ox");
        Field oyF = canvas.getClass().getDeclaredField("oy");
        Field scF = canvas.getClass().getDeclaredField("scale");
        oxF.setAccessible(true);
        oyF.setAccessible(true);
        scF.setAccessible(true);
        int ox = oxF.getInt(canvas), oy = oyF.getInt(canvas);
        double scale = scF.getDouble(canvas);
        System.out.println("canvas ox=" + ox + " oy=" + oy + " scale=" + scale);

        int cx = ox + (int) Math.round((az.x + az.w / 2.0) * scale);
        int cy = oy + (int) Math.round((az.y + az.h / 2.0) * scale);
        final int dx = 80, dy = 30;
        SwingUtilities.invokeAndWait(() -> {
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED, cx, cy));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED, cx + dx, cy + dy));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED, cx + dx, cy + dy));
        });
        Thread.sleep(300);
        System.out.println("after drag (内存)=" + az.x + "," + az.y
                + " (期望 " + (beforeX + dx) + "," + (beforeY + dy) + ")");

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

        org.json.JSONObject root = new org.json.JSONObject(
                new String(Files.readAllBytes(layout.toPath()), StandardCharsets.UTF_8));
        org.json.JSONObject saved = root.getJSONObject("panels").getJSONObject("az_panel");
        int sx = saved.getInt("x"), sy = saved.getInt("y");
        System.out.println("saved=" + sx + "," + sy);

        boolean movedInMemory = Math.abs(az.x - beforeX) > 1;
        boolean savedOk = Math.abs(sx - az.x) <= 1 && Math.abs(sy - az.y) <= 1;
        System.out.println((movedInMemory && savedOk) ? "DRAG_OK 拖动并保存成功"
                : ("DRAG_FAIL 拖动=" + movedInMemory + " 保存=" + savedOk));

        if (backup != null) {
            Files.write(layout.toPath(), backup.getBytes(StandardCharsets.UTF_8));
        }
        System.exit(movedInMemory && savedOk ? 0 : 1);
    }

    private static MouseEvent mouse(Component src, int id, int x, int y) {
        return new MouseEvent(src, id, System.currentTimeMillis(), 0, x, y, 1, false,
                id == MouseEvent.MOUSE_PRESSED ? MouseEvent.BUTTON1 : MouseEvent.NOBUTTON);
    }
}
