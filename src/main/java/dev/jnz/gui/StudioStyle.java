package dev.jnz.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JToggleButton;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

final class StudioStyle {
    static final Color INK = new Color(0x142131);
    static final Color MUTED = new Color(0x526278);
    static final Color CANVAS = new Color(0xecf1f8);
    static final Color PAPER = Color.WHITE;
    static final Color LINE = new Color(0xd5dfed);
    static final Color ACCENT = new Color(0x2553d9);
    static final Color RAIL = new Color(0x111d31);
    static final Color RAIL_TEXT = new Color(0xc2d0e6);
    static final Color CONSOLE = new Color(0x0e192b);
    static final Color CONSOLE_TEXT = new Color(0xd8e5f9);
    static final Color OK = new Color(0x167247);
    static final Color ERROR = new Color(0xa82b3e);
    private static final String UI_FONT = uiFont();

    private StudioStyle() {}

    static Font type(int size, int weight) { return new Font(UI_FONT, weight, size); }

    private static String uiFont() {
        String[] installed = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String preferred : new String[]{"Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei"}) {
            for (String actual : installed) if (actual.equalsIgnoreCase(preferred)) return actual;
        }
        return Font.DIALOG;
    }

    static JLabel label(String text, int size, int weight, Color color) {
        JLabel result = new JLabel(text);
        result.setFont(type(size, weight));
        result.setForeground(color);
        return result;
    }

    static JTextField field(String key, String accessibleName) {
        JTextField result = new JTextField(28);
        result.setName(key);
        result.getAccessibleContext().setAccessibleName(accessibleName);
        result.setFont(type(13, Font.PLAIN));
        result.setForeground(INK);
        result.setBackground(new Color(0xf9fbff));
        result.setCaretColor(INK);
        result.setSelectionColor(new Color(0xccdcff));
        result.setSelectedTextColor(INK);
        result.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE, 1, true),
                new EmptyBorder(7, 10, 7, 10)));
        result.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { outline(result, ACCENT); }
            @Override public void focusLost(java.awt.event.FocusEvent event) { outline(result, LINE); }
        });
        return result;
    }

    private static void outline(JTextField field, Color color) {
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(color, 1, true),
                new EmptyBorder(7, 10, 7, 10)));
    }

    static JCheckBox check(String text, boolean on) {
        JCheckBox result = new JCheckBox(text, on);
        result.setOpaque(false);
        result.setForeground(INK);
        result.setFont(type(13, Font.PLAIN));
        return result;
    }

    static void combo(JComboBox<?> combo) {
        combo.setFont(type(13, Font.PLAIN));
        combo.setForeground(INK);
        combo.setBackground(PAPER);
    }

    enum ButtonKind { PRIMARY, SECONDARY, CONSOLE }

    static JButton button(String text, ButtonKind kind) {
        JButton result = new JButton(text) {
            @Override protected void paintComponent(Graphics graphics) {
                ButtonModel state = getModel();
                Color bg;
                Color border;
                if (kind == ButtonKind.PRIMARY) {
                    bg = !state.isEnabled() ? new Color(0xa5b3ce)
                            : state.isPressed() ? new Color(0x143999)
                            : state.isRollover() ? new Color(0x214bc2) : ACCENT;
                    border = bg;
                } else if (kind == ButtonKind.CONSOLE) {
                    bg = state.isRollover() || state.isPressed() ? new Color(0x283b56) : new Color(0x1a2a41);
                    border = new Color(0x344761);
                } else {
                    bg = state.isPressed() ? new Color(0xdce7fb) : state.isRollover() ? new Color(0xeaf1fe) : PAPER;
                    border = LINE;
                }
                paintSurface(this, graphics, bg, isFocusOwner() ? new Color(0x7197ff) : border, 10);
                super.paintComponent(graphics);
            }
        };
        configureButton(result);
        result.setBorder(new EmptyBorder(8, 14, 8, 14));
        result.setForeground(kind == ButtonKind.PRIMARY ? PAPER : kind == ButtonKind.CONSOLE ? CONSOLE_TEXT : INK);
        return result;
    }

    static JToggleButton navigation(String text, Glyph glyph) {
        JToggleButton item = new JToggleButton(text, glyph) {
            @Override protected void paintComponent(Graphics graphics) {
                ButtonModel state = getModel();
                Color bg = state.isSelected() ? new Color(0x253c64)
                        : state.isRollover() ? new Color(0x1b2c48) : RAIL;
                Color border = isFocusOwner() ? new Color(0x7197ff) : bg;
                paintSurface(this, graphics, bg, border, 10);
                if (state.isSelected()) {
                    Graphics2D canvas = (Graphics2D) graphics.create();
                    canvas.setColor(new Color(0x80a9ff));
                    canvas.fillRoundRect(3, 8, 3, Math.max(4, getHeight() - 16), 4, 4);
                    canvas.dispose();
                }
                super.paintComponent(graphics);
            }
        };
        configureButton(item);
        item.setForeground(RAIL_TEXT);
        item.setHorizontalAlignment(SwingConstants.LEFT);
        item.setIconTextGap(12);
        item.setBorder(new EmptyBorder(11, 15, 11, 8));
        item.setMaximumSize(new Dimension(Integer.MAX_VALUE, item.getPreferredSize().height));
        return item;
    }

    private static void configureButton(AbstractButton result) {
        result.setOpaque(false);
        result.setContentAreaFilled(false);
        result.setBorderPainted(false);
        result.setFocusPainted(false);
        result.setRolloverEnabled(true);
        result.setFont(type(13, Font.BOLD));
    }

    private static void paintSurface(JComponent component, Graphics graphics, Color bg, Color line, int radius) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setColor(bg);
        canvas.fillRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, radius, radius);
        canvas.setColor(line);
        canvas.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, radius, radius);
        canvas.dispose();
    }

    enum Glyph implements Icon {
        PROJECT, TRANSFORM, TARGET, HELP;

        @Override public int getIconWidth() { return 18; }
        @Override public int getIconHeight() { return 18; }

        @Override public void paintIcon(Component context, Graphics graphics, int x, int y) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            canvas.translate(x, y);
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            canvas.setColor(context.getForeground());
            canvas.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (this) {
                case PROJECT:
                    canvas.drawRoundRect(2, 5, 14, 10, 3, 3);
                    canvas.drawLine(3, 5, 3, 3);
                    canvas.drawLine(3, 3, 8, 3);
                    canvas.drawLine(8, 3, 10, 5);
                    break;
                case TRANSFORM:
                    canvas.drawPolyline(new int[]{3, 7, 3}, new int[]{4, 8, 12}, 3);
                    canvas.drawLine(10, 12, 15, 12);
                    break;
                case TARGET:
                    canvas.drawRoundRect(4, 4, 10, 10, 2, 2);
                    canvas.drawRoundRect(7, 7, 4, 4, 1, 1);
                    for (int offset = 6; offset <= 12; offset += 3) {
                        canvas.drawLine(offset, 1, offset, 4);
                        canvas.drawLine(offset, 14, offset, 17);
                        canvas.drawLine(1, offset, 4, offset);
                        canvas.drawLine(14, offset, 17, offset);
                    }
                    break;
                case HELP:
                    canvas.drawOval(2, 2, 14, 14);
                    canvas.drawArc(6, 4, 6, 6, -45, 245);
                    canvas.drawLine(9, 9, 9, 11);
                    canvas.fillOval(8, 12, 2, 2);
                    break;
                default:
                    throw new AssertionError(this);
            }
            canvas.dispose();
        }
    }
}
