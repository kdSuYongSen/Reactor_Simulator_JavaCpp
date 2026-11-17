package com.darwish.nppsim;

import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JPanel;

/** 试探 steelseries Radial 的 track 三段语义（2026-11-12）。 */
public class TrackProbe {

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        UiTheme.install();

        JPanel p = new JPanel(new GridLayout(1, 4, 6, 0));
        p.setBackground(new Color(51, 51, 55));
        p.add(make("A p88..99 g/r/r", 88, 99,
                new Color(226, 226, 226), Color.RED, Color.RED));
        p.add(make("B p0..8 g/r/r", 0, 8,
                new Color(226, 226, 226), Color.RED, Color.RED));
        p.add(make("C p45..55 g/r/r", 45, 55,
                new Color(226, 226, 226), Color.RED, Color.RED));
        p.add(make("D p90..99 g/g/r", 90, 99,
                new Color(226, 226, 226), new Color(0, 190, 90), Color.RED));

        p.setSize(760, 200);
        p.doLayout();
        for (java.awt.Component c : p.getComponents()) {
            c.setBounds(c.getBounds());
        }
        BufferedImage img = new BufferedImage(760, 200, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        p.paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File("target/track_probe.png"));
        System.out.println("wrote target/track_probe.png");
        System.exit(0);
    }

    private static eu.hansolo.steelseries.gauges.Radial make(String title, double s, double e,
                                                             Color c0, Color c1, Color c2) {
        eu.hansolo.steelseries.gauges.Radial g = new eu.hansolo.steelseries.gauges.Radial();
        g.setBackgroundColor(eu.hansolo.steelseries.tools.BackgroundColor.LIGHT_GRAY);
        g.setFrameDesign(eu.hansolo.steelseries.tools.FrameDesign.TILTED_BLACK);
        g.setLcdVisible(false);
        g.setLedVisible(false);
        g.setPostsVisible(false);
        g.setPointerColor(eu.hansolo.steelseries.tools.ColorDef.BLACK);
        g.setMinValue(0);
        g.setMaxValue(1800);
        g.setValue(1500);
        g.setTitle(title);
        g.setUnitString("rpm");
        g.setTitleAndUnitFont(new Font("Dialog", Font.PLAIN, 10));
        g.setTitleAndUnitFontEnabled(true);
        g.setTrackVisible(true);
        g.setTrackStart(s);
        g.setTrackStop(e);
        g.setTrackStartColor(c0);
        g.setTrackSectionColor(c1);
        g.setTrackStopColor(c2);
        return g;
    }
}
