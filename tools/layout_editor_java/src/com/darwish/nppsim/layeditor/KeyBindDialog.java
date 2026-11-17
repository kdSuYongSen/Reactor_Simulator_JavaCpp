package com.darwish.nppsim.layeditor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/**
 * 热键设置对话框（2026-11-03）。
 *
 * <p>点按钮 → 按任意键即改绑。确认后写入 {@code ui_zoom_keys.json}，
 * 这份文件和模拟器共用，所以模拟器那边也是同一套按键。</p>
 */
final class KeyBindDialog extends JDialog {

    private final ZoomKeys keys;
    private final File dir;
    private boolean accepted;

    private final CaptureButton bMagnify;
    private final CaptureButton bZoomIn;
    private final CaptureButton bZoomOut;
    private final CaptureButton bFit;
    private final JSpinner factor;

    private KeyBindDialog(java.awt.Window owner, ZoomKeys current, File dir) {
        super(owner, L.t("keys.title"), ModalityType.APPLICATION_MODAL);
        this.keys = current;
        this.dir = dir;

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(5, 6, 5, 6);
        gc.anchor = GridBagConstraints.WEST;

        bMagnify = new CaptureButton(current.magnify);
        bZoomIn = new CaptureButton(current.zoomIn);
        bZoomOut = new CaptureButton(current.zoomOut);
        bFit = new CaptureButton(current.fit);
        factor = new JSpinner(new SpinnerNumberModel(current.loupe, 1.2, 6.0, 0.1));

        int row = 0;
        row = addRow(form, gc, row, L.t("keys.magnify"), bMagnify);
        row = addRow(form, gc, row, L.t("keys.zoomin"), bZoomIn);
        row = addRow(form, gc, row, L.t("keys.zoomout"), bZoomOut);
        row = addRow(form, gc, row, L.t("keys.fit"), bFit);
        row = addRow(form, gc, row, L.t("keys.loupe"), factor);

        JLabel hint = new JLabel(String.format(L.t("keys.hint"),
                ZoomKeys.file(dir).getAbsolutePath()));
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2; gc.weightx = 1;
        gc.insets = new Insets(12, 6, 4, 6);
        form.add(hint, gc);

        JButton ok = new JButton(L.t("btn.ok"));
        ok.addActionListener(e -> {
            keys.magnify = bMagnify.getKeyCode();
            keys.zoomIn = bZoomIn.getKeyCode();
            keys.zoomOut = bZoomOut.getKeyCode();
            keys.fit = bFit.getKeyCode();
            keys.loupe = ((Number) factor.getValue()).doubleValue();
            accepted = true;
            dispose();
        });
        JButton cancel = new JButton(L.t("btn.cancel"));
        cancel.addActionListener(e -> dispose());

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        south.add(cancel);
        south.add(ok);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private int addRow(JPanel form, GridBagConstraints gc, int row, String label,
                       java.awt.Component field) {
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 1; gc.weightx = 0;
        form.add(new JLabel(label), gc);
        gc.gridx = 1; gc.gridy = row; gc.weightx = 1;
        form.add(field, gc);
        return row + 1;
    }

    /** 返回新的热键设置；取消时返回 null */
    static ZoomKeys show(java.awt.Window owner, ZoomKeys current, File dir) {
        KeyBindDialog d = new KeyBindDialog(owner, current, dir);
        d.setVisible(true);
        return d.accepted ? d.keys : null;
    }

    /** 点一下进入"请按键"状态，按任意键取该键 */
    static final class CaptureButton extends JButton {
        private int code;
        private boolean capturing;

        CaptureButton(int initial) {
            this.code = initial;      // 注意别用参数名 code：会遮蔽字段，内部类里就改不动了
            setPreferredSize(new Dimension(140, 26));
            addActionListener(e -> {
                capturing = true;
                setText(L.t("keys.press"));
                requestFocusInWindow();
            });
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (!capturing) {
                        return;
                    }
                    code = e.getKeyCode();
                    capturing = false;
                    setText(ZoomKeys.text(code));
                    e.consume();
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent e) {
                    if (capturing) {
                        capturing = false;
                        setText(ZoomKeys.text(code));
                    }
                }
            });
            setText(ZoomKeys.text(code));
        }

        int getKeyCode() {
            return code;
        }
    }

    /** 供自检用：不弹窗，直接构造（不显示） */
    static KeyBindDialog createForTest(java.awt.Window owner, ZoomKeys k, File dir) {
        return new KeyBindDialog(owner, k, dir);
    }

    static void edt(Runnable r) {
        SwingUtilities.invokeLater(r);
    }
}
