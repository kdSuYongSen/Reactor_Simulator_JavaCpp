import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 简单裁剪+放大工具：java Crop.java in.png out.png x y w h scale */
public class Crop {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int x = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]);
        int w = Math.min(Integer.parseInt(a[4]), src.getWidth() - x);
        int h = Math.min(Integer.parseInt(a[5]), src.getHeight() - y);
        double s = Double.parseDouble(a[6]);
        BufferedImage sub = src.getSubimage(x, y, w, h);
        int nw = (int) Math.round(w * s), nh = (int) Math.round(h * s);
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(sub, 0, 0, nw, nh, null);
        g.dispose();
        ImageIO.write(out, "png", new File(a[1]));
        System.out.println(a[1] + " " + nw + "x" + nh);
    }
}
