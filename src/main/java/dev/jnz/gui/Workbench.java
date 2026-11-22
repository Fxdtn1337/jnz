package dev.jnz.gui;

import dev.jnz.CliInfo;
import dev.jnz.Main;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicSplitPaneUI;

import static dev.jnz.gui.StudioStyle.*;
import static dev.jnz.gui.StudioStyle.ButtonKind.PRIMARY;
import static dev.jnz.gui.StudioStyle.ButtonKind.SECONDARY;

/** Desktop entry point. Compiler execution stays on a worker shared with the CLI. */
public final class Workbench {
    private static final int LOG_LIMIT = 65_536;
    private final JFrame frame = new JFrame("J2Rust · 转换工作台");
    private final JLabel pageTitle = label("", 27, Font.BOLD, INK);
    private final JLabel pageDetail = label("", 13, Font.PLAIN, MUTED);
    private final JLabel status = label("就绪", 13, Font.BOLD, INK);
    private final JLabel activity = label("", 12, Font.PLAIN, MUTED);
    private final JTextArea log = new JTextArea() {
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getDocument().getLength() == 0) {
                graphics.setColor(new Color(0x99adc8));
                graphics.setFont(type(13, Font.PLAIN));
                graphics.drawString("检查或构建后，日志会显示在这里。", 13, 22);
            }
        }
    };
    private final JButton inspect = button("检查方法", SECONDARY);
    private final JButton build = button("构建 JAR", PRIMARY);
    private final JButton report = button("打开报告", SECONDARY);
    private final JButton output = button("打开产物位置", SECONDARY);
    private final JProgressBar progress = new JProgressBar();
    private final WorkbenchForm form = new WorkbenchForm(frame, this::notice);
    private final BufferedImage brandMark = loadBrand();
    private boolean busy;
    private Path lastReport;
    private Path lastOutput;

    private Workbench() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (busy) notice("任务正在进行，请等待完成后关闭");
                else frame.dispose();
            }
        });
        if (brandMark != null) frame.setIconImages(Arrays.asList(brand(16), brand(32), brand(64)));

        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(CANVAS);
        shell.add(rail(), BorderLayout.WEST);
        JPanel desktop = new JPanel(new BorderLayout());
        desktop.setOpaque(false);
        desktop.add(header(), BorderLayout.NORTH);
        desktop.add(workspace(), BorderLayout.CENTER);
        desktop.add(footer(), BorderLayout.SOUTH);
        shell.add(desktop, BorderLayout.CENTER);
        frame.setContentPane(shell);
        frame.getRootPane().setDefaultButton(build);
        Rectangle available = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setMinimumSize(new Dimension(Math.min(805, available.width), Math.min(585, available.height)));
        frame.setSize(Math.min(1120, available.width), Math.min(810, available.height));
        frame.setLocationRelativeTo(null);
    }

    public static void open() {
        Runnable launch = () -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { /* Native look and feel is optional. */ }
            new Workbench().frame.setVisible(true);
        };
        if (SwingUtilities.isEventDispatchThread()) launch.run();
        else EventQueue.invokeLater(launch);
    }

    private JPanel rail() {
        JPanel rail = new JPanel();
        rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
        rail.setBackground(RAIL);
        rail.setPreferredSize(new Dimension(202, 1));
        rail.setBorder(new EmptyBorder(25, 16, 17, 16));

        JPanel mark = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        mark.setOpaque(false);
        mark.setAlignmentX(LEFT_ALIGNMENT);
        JLabel image = label("JR", 18, Font.BOLD, INK);
        image.setOpaque(true);
        image.setBackground(PAPER);
        image.setHorizontalAlignment(SwingConstants.CENTER);
        image.setPreferredSize(new Dimension(48, 48));
        image.getAccessibleContext().setAccessibleName("J2Rust Logo");
        if (brandMark != null) {
            image.setIcon(new ImageIcon(brand(42)));
            image.setText("");
        }
        mark.add(image);
        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setBorder(new EmptyBorder(1, 10, 0, 0));
        brand.add(label("J2Rust", 22, Font.BOLD, PAPER));
        brand.add(label("v" + CliInfo.VERSION, 12, Font.PLAIN, RAIL_TEXT));
        mark.add(brand);
        mark.setMaximumSize(new Dimension(Integer.MAX_VALUE, mark.getPreferredSize().height));
        rail.add(mark);
        rail.add(Box.createVerticalStrut(32));

        ButtonGroup links = new ButtonGroup();
        installPage(rail, links, "project", "项目与产物", "指定源 JAR、输出位置和可复用的构建配置。", Glyph.PROJECT, true);
        installPage(rail, links, "protection", "方法与混淆", "选择要转换的方法，并控制 VM、控制流和字符串处理。", Glyph.TRANSFORM, false);
        installPage(rail, links, "native", "编译与平台", "设置原生后端、目标平台、工具链和 JNI 模块。", Glyph.TARGET, false);
        rail.add(Box.createVerticalGlue());
        JButton help = button("使用帮助", ButtonKind.CONSOLE);
        help.setIcon(Glyph.HELP);
        help.setHorizontalAlignment(SwingConstants.LEFT);
        help.setAlignmentX(LEFT_ALIGNMENT);
        help.setMaximumSize(new Dimension(Integer.MAX_VALUE, help.getPreferredSize().height));
        help.setName("help");
        help.addActionListener(event -> showHelp());
        rail.add(help);
        return rail;
    }

    private static final float LEFT_ALIGNMENT = 0f;

    private void installPage(JPanel rail, ButtonGroup links, String key, String title, String detail,
                             Glyph icon, boolean selected) {
        JToggleButton item = navigation(title, icon);
        item.setAlignmentX(LEFT_ALIGNMENT);
        item.setName("section-" + key);
        links.add(item);
        rail.add(item);
        rail.add(Box.createVerticalStrut(5));
        item.addActionListener(event -> showPage(key, title, detail));
        if (selected) {
            item.setSelected(true);
            showPage(key, title, detail);
        }
    }

    private void showPage(String key, String title, String detail) {
        form.showPage(key);
        pageTitle.setText(title);
        pageDetail.setText(detail);
    }

    private JPanel header() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(new EmptyBorder(21, 25, 15, 24));
        pageTitle.setAlignmentX(LEFT_ALIGNMENT);
        pageDetail.setAlignmentX(LEFT_ALIGNMENT);
        header.add(pageTitle);
        header.add(Box.createVerticalStrut(4));
        header.add(pageDetail);
        return header;
    }

    private JPanel workspace() {
        JScrollPane settings = new JScrollPane(form);
        settings.setBorder(BorderFactory.createEmptyBorder());
        settings.getViewport().setBackground(CANVAS);
        settings.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        settings.setMinimumSize(new Dimension(0, 190));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, settings, terminal());
        split.setOpaque(false);
        split.setContinuousLayout(true);
        split.setResizeWeight(0.66);
        split.setDividerSize(10);
        split.setUI(new BasicSplitPaneUI() {
            @Override public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
                return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    @Override public void paint(java.awt.Graphics graphics) {
                        graphics.setColor(CANVAS);
                        graphics.fillRect(0, 0, getWidth(), getHeight());
                        graphics.setColor(LINE);
                        int y = getHeight() / 2;
                        graphics.fillRoundRect(getWidth() / 2 - 18, y - 1, 36, 3, 3, 3);
                    }
                };
            }
        });
        split.setBorder(BorderFactory.createEmptyBorder());
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.68));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(new EmptyBorder(0, 24, 0, 24));
        wrapper.add(split, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel terminal() {
        JPanel terminal = new JPanel(new BorderLayout());
        terminal.setBackground(CONSOLE);
        terminal.setMinimumSize(new Dimension(0, 125));
        terminal.setBorder(BorderFactory.createLineBorder(new Color(0x2a3c56), 1, true));

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.setBorder(new EmptyBorder(7, 13, 6, 9));
        heading.add(label("运行日志", 13, Font.BOLD, PAPER), BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.setOpaque(false);
        JButton copy = button("复制", ButtonKind.CONSOLE);
        copy.setName("copy-log");
        copy.addActionListener(event -> {
            if (log.getText().isEmpty()) {
                notice("暂无日志可复制");
                return;
            }
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(log.getText()), null);
                notice("日志已复制");
            } catch (IllegalStateException error) {
                notice("剪贴板正在使用，请重试");
            }
        });
        JButton save = button("保存日志…", ButtonKind.CONSOLE);
        save.setName("save-log");
        save.addActionListener(event -> saveLog());
        actions.add(copy);
        actions.add(save);
        heading.add(actions, BorderLayout.EAST);
        terminal.add(heading, BorderLayout.NORTH);

        log.setName("log");
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        log.setForeground(CONSOLE_TEXT);
        log.setCaretColor(CONSOLE_TEXT);
        log.setSelectedTextColor(PAPER);
        log.setSelectionColor(new Color(0x33588d));
        log.setBackground(CONSOLE);
        log.setBorder(new EmptyBorder(3, 13, 8, 10));
        log.getAccessibleContext().setAccessibleName("编译与检查日志");
        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        terminal.add(scroll);
        return terminal;
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new BorderLayout(10, 0));
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(12, 24, 12, 24));
        JPanel state = new JPanel();
        state.setOpaque(false);
        state.setLayout(new BoxLayout(state, BoxLayout.Y_AXIS));
        status.setName("status");
        activity.setName("activity");
        state.add(status);
        state.add(Box.createVerticalStrut(4));
        state.add(activity);
        footer.add(state, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        actions.setOpaque(false);
        output.setName("open-output");
        report.setName("open-report");
        inspect.setName("inspect");
        build.setName("build");
        output.setVisible(false);
        report.setVisible(false);
        output.addActionListener(event -> openPath(lastOutput == null ? null : lastOutput.getParent()));
        report.addActionListener(event -> openPath(lastReport));
        inspect.addActionListener(event -> run(false));
        build.addActionListener(event -> run(true));
        actions.add(output);
        actions.add(report);
        actions.add(inspect);
        actions.add(build);
        footer.add(actions, BorderLayout.EAST);
        progress.setBorderPainted(false);
        progress.setPreferredSize(new Dimension(1, 3));
        progress.setForeground(ACCENT);
        progress.setBackground(new Color(0xdbe5f8));
        progress.setVisible(false);
        progress.getAccessibleContext().setAccessibleName("任务进行中");
        footer.add(progress, BorderLayout.SOUTH);
        return footer;
    }

    private void run(boolean compile) {
        if (busy) return;
        WorkbenchForm.Job job;
        try {
            job = form.prepare(compile);
        } catch (Exception error) {
            notice(error.getMessage());
            return;
        }
        log.setText("");
        activity.setText("");
        status.setForeground(ACCENT);
        status.setText(compile ? "正在构建…" : "正在检查…");
        setBusy(true);
        new SwingWorker<Void, String>() {
            @Override protected Void doInBackground() throws Exception {
                Main.execute(job.args, text -> publish(text));
                return null;
            }

            @Override protected void process(List<String> lines) {
                // A burst of compiler output should not grow the EDT document unbounded.
                int from = lines.size(), chars = 0;
                while (from > 0 && chars < LOG_LIMIT) chars += lines.get(--from).length() + 1;
                StringBuilder tail = new StringBuilder(Math.min(chars, LOG_LIMIT));
                for (int i = from; i < lines.size(); i++) {
                    String text = lines.get(i);
                    tail.append(text, Math.max(0, text.length() - LOG_LIMIT), text.length()).append('\n');
                }
                append(tail.toString());
            }

            @Override protected void done() {
                try {
                    get();
                    status.setText(compile ? "构建完成" : "检查完成");
                    status.setForeground(OK);
                    if (compile) lastOutput = Files.isRegularFile(job.output) ? job.output : null;
                    lastReport = Files.isRegularFile(job.report) ? job.report : null;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText("任务已中断");
                    status.setForeground(ERROR);
                } catch (ExecutionException error) {
                    status.setText(compile ? "构建失败" : "检查失败");
                    status.setForeground(ERROR);
                    Throwable cause = error.getCause();
                    append("[错误] " + (cause.getMessage() == null ? cause : cause.getMessage()) + "\n");
                    lastReport = Files.isRegularFile(job.report) ? job.report : null;
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void setBusy(boolean active) {
        busy = active;
        progress.setVisible(active);
        progress.setIndeterminate(active);
        form.setEditing(!active);
        build.setEnabled(!active);
        inspect.setEnabled(!active);
        output.setVisible(!active && lastOutput != null);
        report.setVisible(!active && lastReport != null);
    }

    private void append(String text) {
        int length = log.getDocument().getLength();
        boolean follow = log.getCaretPosition() == length;
        log.append(text);
        length = log.getDocument().getLength();
        if (length > LOG_LIMIT) log.replaceRange("", 0, length - LOG_LIMIT);
        if (follow) log.setCaretPosition(log.getDocument().getLength());
    }

    private void notice(String text) {
        String value = text == null || text.isEmpty() ? "操作未完成" : text;
        activity.setText(value.length() > 48 ? value.substring(0, 47) + "…" : value);
        activity.setToolTipText(value);
    }

    private void openPath(Path file) {
        if (file == null) return;
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                throw new IOException("系统未关联打开方式");
            Desktop.getDesktop().open(file.toFile());
        } catch (IOException | RuntimeException error) {
            notice("无法打开 " + file + " · " + error.getMessage());
        }
    }

    private void saveLog() {
        if (log.getText().isEmpty()) {
            notice("暂无日志可保存");
            return;
        }
        File selected = form.choose("保存运行日志", FileDialog.SAVE, "j2rust.log", ".log");
        if (selected == null) return;
        try {
            Files.write(selected.toPath(), log.getText().getBytes(StandardCharsets.UTF_8));
            notice("已保存 " + selected.getName());
        } catch (IOException error) {
            notice("日志保存失败 · " + error.getMessage());
        }
    }

    private void showHelp() {
        JDialog dialog = new JDialog(frame, "J2Rust 使用帮助", false);
        JPanel contents = new JPanel(new BorderLayout(0, 12));
        contents.setBackground(PAPER);
        contents.setBorder(new EmptyBorder(18, 20, 18, 20));
        contents.add(label("使用帮助", 22, Font.BOLD, INK), BorderLayout.NORTH);
        JTextArea help = new JTextArea(
                "1. 在项目页选择输入 JAR。输出留空时，工作台会在源文件旁生成 -native.jar。\n"
                + "2. 可先打开 .properties 配置，再修改需要覆盖的选项。只有另存时才会写入配置文件。\n"
                + "3. 使用检查方法生成报告。确认选择范围后点击构建 JAR。\n\n命令行用法\n");
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        help.setForeground(INK);
        help.setBorder(new EmptyBorder(7, 7, 7, 7));
        try { Main.execute(new String[]{"--help"}, text -> help.append(text + "\n")); }
        catch (Exception error) { help.append(String.valueOf(error.getMessage())); }
        help.setCaretPosition(0);
        contents.add(new JScrollPane(help), BorderLayout.CENTER);
        JButton close = button("关闭", SECONDARY);
        close.addActionListener(event -> dialog.dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bottom.setOpaque(false);
        bottom.add(close);
        contents.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(contents);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(Math.min(660, frame.getWidth()), Math.min(500, frame.getHeight()));
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private static BufferedImage loadBrand() {
        try (InputStream file = Workbench.class.getResourceAsStream("/dev/jnz/gui/logo.png")) {
            return file == null ? null : ImageIO.read(file);
        } catch (IOException error) {
            return null;
        }
    }

    private BufferedImage brand(int pixels) {
        BufferedImage scaled = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB);
        Graphics2D canvas = scaled.createGraphics();
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        canvas.drawImage(brandMark, 0, 0, pixels, pixels, null);
        canvas.dispose();
        return scaled;
    }
}
