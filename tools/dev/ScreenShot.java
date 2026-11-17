import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 截整屏（验证外部布局编辑器界面）。 */
public class ScreenShot {
    public static void main(String[] a) throws Exception {
        Robot r = new Robot();
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage img = r.createScreenCapture(screen);
        File out = new File(a.length > 0 ? a[0] : "screen.png");
        ImageIO.write(img, "png", out);
        System.out.println("截图: " + out.getAbsolutePath() + " " + img.getWidth() + "x" + img.getHeight());
    }
}
