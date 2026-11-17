package com.darwish.nppsim;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 把老式"ON / OFF 按钮 + Led"的泵控制块就地换成槽式拨杆开关（2026-11-15）。
 *
 * <p>MCP / 给水 / 凝结水这几页是 NetBeans 表单，泵控件的行高只有 30–36 px，
 * 塞不下 44 px 的拨杆。它们的泵控件都集中在一个独立子面板里，外层 GroupLayout
 * 把该子面板当成**一个整体**来定位——所以只能改它的**内部**，绝不能动它的尺寸，
 * 否则外层会重排（实测会把整块泵面板挤到窗口底部并裁掉）。</p>
 *
 * <p>做法：等子面板拿到真实尺寸后，把它的布局改成 null，隐藏原有子组件（对象仍被
 * 窗口字段引用，刷新代码照常调用不报错），再按行把拨杆开关铺上去——尺寸与原面板
 * 完全一致，外层布局零影响。</p>
 */
public final class PumpSwitchPanel {

    /** 全局登记表：各窗口每帧调 {@link #syncAll()} 让手柄跟随模型（含联锁跳泵） */
    private static final List<RotarySwitch> ALL = new ArrayList<>();

    private PumpSwitchPanel() {
    }

    /**
     * 安装拨杆开关（尺寸就绪后自动执行；构造期间尺寸为 0 时会等第一次布局）。
     *
     * @param host       泵控件所在的容器（原 ON/OFF/Led 的父面板）
     * @param pumps      该面板对应的泵，顺序与编号一致
     * @param pattern    铭牌格式，"主循环泵 %d" 这类
     * @param startIndex 编号起点（一个窗口里有多块面板时用）
     */
    public static void install(final Container host, final Pump[] pumps,
                               final String pattern, final int startIndex) {
        if (host == null || pumps == null || pumps.length == 0) {
            return;
        }
        if (host.getWidth() > 40 && host.getHeight() > 20) {
            doInstall(host, pumps, pattern, startIndex);
            return;
        }
        // 构造阶段还没有真实尺寸：等第一次布局完成再装入
        host.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (host.getWidth() > 40 && host.getHeight() > 20) {
                    host.removeComponentListener(this);
                    doInstall(host, pumps, pattern, startIndex);
                }
            }
        });
    }

    private static void doInstall(Container host, Pump[] pumps, String pattern, int startIndex) {
        host.doLayout();
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int rows = pumps.length;
        // 每行 44 px（槽式拨杆需要的高度）；面板因此变高，交给外层 GroupLayout 重排
        int rowH = 44;
        int newH = rows * rowH;

        // 原控件全部隐藏（不 remove：窗口的刷新代码仍在调用它们）
        for (Component c : host.getComponents()) {
            c.setVisible(false);
        }
        host.setLayout(null);
        /* 改成 null 布局后容器不再有首选尺寸，外层 GroupLayout 会把它压成 1×1
         * （实测就是这个原因导致整块泵面板"消失"）。这里把尺寸显式交还。 */
        host.setPreferredSize(new java.awt.Dimension(w, newH));
        host.setMinimumSize(new java.awt.Dimension(w, newH));
        host.setMaximumSize(new java.awt.Dimension(w, newH));

        for (int i = 0; i < rows; i++) {
            final Pump p = pumps[i];
            RotarySwitch s = new RotarySwitch(String.format(pattern, startIndex + i),
                    () -> p != null && p.isActive(),
                    on -> {
                        if (p != null) {
                            p.setActive(on);
                        }
                    });
            s.setBounds(0, i * rowH, w, rowH);
            host.add(s);
            ALL.add(s);
        }
        host.revalidate();
        if (host.getParent() != null) {
            host.getParent().revalidate();
        }
        host.repaint();
    }

    /** 每帧与模型状态对齐 */
    public static void syncAll() {
        for (int i = 0; i < ALL.size(); i++) {
            ALL.get(i).sync();
        }
    }

    /**
     * "选泵号 + 启停"型面板：保留选择器，把"开始/停止"两个单选钮换成槽式拨杆。
     *
     * <p>给水 / 凝结水的泵控面板宽 175 px，左边控制列只有 47 px，塞不下拨杆。
     * 这两页右侧有大片空白，所以做法是：面板整体加宽 {@code shift} px，把数显右移，
     * 空出来的左侧放拨杆。</p>
     *
     * @param host   泵控面板（含 JSpinner + 开始/停止 单选钮 + 数显）
     * @param spinner 泵号选择器（拨杆作用于它选中的那台泵）
     * @param pumps   候选泵列表（下标 = spinner 值 - 1）
     * @param label   拨杆铭牌（如"主给水泵"）
     * @param shift   面板加宽量（数显等右侧元素右移同样的距离）
     */
    public static void installPicker(final Container host, final javax.swing.JSpinner spinner,
                                     final java.util.List<Pump> pumps, final String label,
                                     final int shift) {
        if (host == null || spinner == null || pumps == null || pumps.isEmpty()) {
            return;
        }
        Runnable job = () -> doInstallPicker(host, spinner, pumps, label, shift);
        if (host.getWidth() > 40 && host.getHeight() > 20) {
            job.run();
            return;
        }
        host.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (host.getWidth() > 40 && host.getHeight() > 20) {
                    host.removeComponentListener(this);
                    job.run();
                }
            }
        });
    }

    private static void doInstallPicker(Container host, javax.swing.JSpinner spinner,
                                        List<Pump> pumps, String label, int shift) {
        host.doLayout();
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // 数显 / 指示灯等右侧元素整体右移，给拨杆腾位置
        for (Component c : host.getComponents()) {
            if (c instanceof javax.swing.JRadioButton) {
                c.setVisible(false);
                continue;
            }
            if (c instanceof javax.swing.JSpinner) {
                continue;
            }
            java.awt.Rectangle b = c.getBounds();
            if (b.x >= 60) {
                c.setBounds(b.x + shift, b.y, b.width, b.height);
            }
        }
        // 加宽面板（外层 GroupLayout 用的是 PREFERRED→DEFAULT→PREFERRED，会跟随）
        host.setPreferredSize(new java.awt.Dimension(w + shift, h));
        host.setMinimumSize(new java.awt.Dimension(w + shift, h));
        if (host instanceof javax.swing.JComponent) {
            ((javax.swing.JComponent) host).setMaximumSize(
                    new java.awt.Dimension(w + shift, h));
        }

        RotarySwitch s = new RotarySwitch(label,
                () -> {
                    int i = ((Number) spinner.getValue()).intValue() - 1;
                    return i >= 0 && i < pumps.size() && pumps.get(i).isActive();
                },
                on -> {
                    int i = ((Number) spinner.getValue()).intValue() - 1;
                    if (i >= 0 && i < pumps.size()) {
                        pumps.get(i).setActive(on);
                    }
                });
        // 原"开始/停止"两钮的位置（13,45 起，47×23 上下排）：拨杆盖住这一块
        s.setBounds(13, 44, 170, 44);
        host.setLayout(null);
        host.add(s);
        ALL.add(s);

        host.revalidate();
        if (host.getParent() != null) {
            host.getParent().revalidate();
        }
        host.repaint();
    }

    /**
     * 紧凑面板版：不加宽，只把面板加高 {@code growY}，拨杆占满面板宽度的中间一条。
     *
     * <p>凝结水页的四个泵控面板宽 226、彼此挨得很近，加宽会互相压住；
     * 但它们下方还有空位，所以改成"加高一行"来放拨杆，读数整体下移。</p>
     */
    public static void installPickerTall(final Container host, final javax.swing.JSpinner spinner,
                                         final java.util.List<Pump> pumps, final String label,
                                         final int growY) {
        if (host == null || spinner == null || pumps == null || pumps.isEmpty()) {
            return;
        }
        Runnable job = () -> doInstallPickerTall(host, spinner, pumps, label, growY);
        if (host.getWidth() > 40 && host.getHeight() > 20) {
            job.run();
            return;
        }
        host.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (host.getWidth() > 40 && host.getHeight() > 20) {
                    host.removeComponentListener(this);
                    job.run();
                }
            }
        });
    }

    private static void doInstallPickerTall(Container host, javax.swing.JSpinner spinner,
                                            List<Pump> pumps, String label, int growY) {
        host.doLayout();
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // 原"开始/停止"在 y≈53：把这一行及以下的组件整体下移，给拨杆让出 44 px
        for (Component c : host.getComponents()) {
            java.awt.Rectangle b = c.getBounds();
            if (c instanceof javax.swing.JRadioButton) {
                c.setVisible(false);
                continue;
            }
            if (b.y >= 50) {
                c.setBounds(b.x, b.y + growY, b.width, b.height);
            }
        }
        host.setPreferredSize(new java.awt.Dimension(w, h + growY));
        host.setMinimumSize(new java.awt.Dimension(w, h + growY));
        if (host instanceof javax.swing.JComponent) {
            ((javax.swing.JComponent) host).setMaximumSize(
                    new java.awt.Dimension(w, h + growY));
        }

        RotarySwitch s = new RotarySwitch(label,
                () -> {
                    int i = ((Number) spinner.getValue()).intValue() - 1;
                    return i >= 0 && i < pumps.size() && pumps.get(i).isActive();
                },
                on -> {
                    int i = ((Number) spinner.getValue()).intValue() - 1;
                    if (i >= 0 && i < pumps.size()) {
                        pumps.get(i).setActive(on);
                    }
                });
        s.setBounds(8, 50, w - 16, 44);
        host.setLayout(null);
        host.add(s);
        ALL.add(s);

        host.revalidate();
        if (host.getParent() != null) {
            host.getParent().revalidate();
        }
        host.repaint();
    }

    /**
     * 同上（加高模式），但状态由外部提供——用于"泵对象会随选择器变化"的场合
     * （凝结水页的循环水泵跟着 TG-A/TG-B 切换）。
     */
    public static void installCustom(final Container host, final String label,
                                     final java.util.function.BooleanSupplier state,
                                     final java.util.function.Consumer<Boolean> action,
                                     final int growY) {
        if (host == null) {
            return;
        }
        Runnable job = () -> {
            host.doLayout();
            int w = host.getWidth();
            int h = host.getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            for (Component c : host.getComponents()) {
                java.awt.Rectangle b = c.getBounds();
                if (c instanceof javax.swing.JRadioButton) {
                    c.setVisible(false);
                    continue;
                }
                if (b.y >= 50) {
                    c.setBounds(b.x, b.y + growY, b.width, b.height);
                }
            }
            host.setPreferredSize(new java.awt.Dimension(w, h + growY));
            host.setMinimumSize(new java.awt.Dimension(w, h + growY));
            if (host instanceof javax.swing.JComponent) {
                ((javax.swing.JComponent) host).setMaximumSize(
                        new java.awt.Dimension(w, h + growY));
            }
            RotarySwitch s = new RotarySwitch(label, state, action);
            s.setBounds(8, 50, w - 16, 44);
            host.setLayout(null);
            host.add(s);
            ALL.add(s);
            host.revalidate();
            if (host.getParent() != null) {
                host.getParent().revalidate();
            }
            host.repaint();
        };
        if (host.getWidth() > 40 && host.getHeight() > 20) {
            job.run();
            return;
        }
        host.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (host.getWidth() > 40 && host.getHeight() > 20) {
                    host.removeComponentListener(this);
                    job.run();
                }
            }
        });
    }
}
