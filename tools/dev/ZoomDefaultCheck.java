package com.darwish.nppsim;
public class ZoomDefaultCheck {
    public static void main(String[] a) throws Exception {
        Localization.init();
        UiZoom.init();
        System.out.println("整页缩放默认=" + (UiZoom.isEnabled() ? "开" : "关")
                + " 主窗口缩放=" + Math.round(UiZoom.getScale() * 100) + "%");
        javax.swing.JFrame f = new javax.swing.JFrame("t");
        f.setSize(400, 300);
        UiZoom.setWindowScale(f, 1.5f);
        javax.swing.JFrame g = new javax.swing.JFrame("t2");
        g.setSize(400, 300);
        UiZoom.applyDefault(g);
        System.out.println("旧窗口=" + Math.round(UiZoom.getScale(f) * 100)
                + "% 新窗口=" + Math.round(UiZoom.getScale(g) * 100) + "%");
        System.exit(0);
    }
}
