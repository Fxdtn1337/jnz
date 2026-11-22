package dev.jnz.gui;

import dev.jnz.Main;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** 桌面前端和命令行调用相同的编译入口，编译工作在后台线程中运行。 */
public final class Workbench {
    private final JFrame frame = new JFrame("J2Rust · Java 转 Rust");
    private final JTextField input = field("input"), output = field("output");
    private final JTextField include = field("include"), exclude = field("exclude");
    private final JTextField vmInclude = field("vm-include");
    private final JTextField targets = field("targets"), zig = field("zig");
    private final JTextField rustc = field("rustc");
    private final JComboBox<String> backend = new JComboBox<>(new String[]{"Rust", "Zig（兼容）"});
    private final JTextField shard = field("shard-size"), module = field("module-size");
    private final JTextField bridges = field("jni-bridges");
    private final JTextField report = field("report"), work = field("work-dir");
    private final JTextField key = field("public-key"), license = field("license"), features = field("features");
    private final JCheckBox strict = new JCheckBox("遇到选中但无法转换的方法时终止");
    private final JCheckBox preObfuscation = new JCheckBox("转换前处理字符串和跳转", true);
    private final JCheckBox fullVm = new JCheckBox("全部已选方法使用 VM");
    private final JTextArea log = new JTextArea(8, 65);
    private final JButton inspect = new JButton("检查方法"), build = new JButton("构建 JAR");
    private final JLabel status = new JLabel("就绪");
    private static final int LOG_LIMIT = 65536;

    private Workbench() {
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setBorder(new EmptyBorder(12, 14, 12, 14));
        JPanel options = new JPanel(new BorderLayout(0, 9));
        JPanel paths = form();
        row(paths, 0, "输入 JAR", input, browse(input, "选择输入 JAR", FileDialog.LOAD, true));
        row(paths, 1, "输出 JAR", output, browse(output, "选择输出 JAR", FileDialog.SAVE, true));
        options.add(paths, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        JPanel method = form();
        row(method, 0, "包含模式", include, null);
        row(method, 1, "排除模式", exclude, null);
        note(method, 2, "多项用逗号分隔，例如 com/example/*#calc* 或 @com/example/Sensitive。留空包含全部方法。");
        row(method, 3, "VM 保护范围", vmInclude, null);
        note(method, 4, "Rust 默认直编。填写模式可对部分方法使用 VM，带异常处理表的方法自动使用 VM。");
        add(method, strict, 1, 5, 2, 1);
        add(method, preObfuscation, 1, 6, 2, 1);
        add(method, fullVm, 1, 7, 2, 1);
        fullVm.addActionListener(event -> vmInclude.setEnabled(!fullVm.isSelected()));
        tabs.addTab("方法选择", method);

        JPanel nativeOptions = form();
        row(nativeOptions, 0, "目标平台", targets, null);
        note(nativeOptions, 1, "留空使用本机平台。多个目标以逗号分隔，例如 x86_64-windows-gnu,aarch64-linux-gnu。");
        row(nativeOptions, 2, "Zig 程序", zig, browse(zig, "选择 Zig 程序", FileDialog.LOAD, false));
        row(nativeOptions, 3, "资源分片字节数", shard, null);
        row(nativeOptions, 4, "模块大小", module, null);
        row(nativeOptions, 5, "JNI 桥接组数", bridges, null);
        note(nativeOptions, 6, "留空按包和模块大小分组。可指定更多桥接组，转换后的每个类只绑定一个组。");
        add(nativeOptions, new JLabel("编译后端"), 0, 7, 1, 0);
        add(nativeOptions, backend, 1, 7, 2, 1);
        row(nativeOptions, 8, "Rust 编译器", rustc, browse(rustc, "选择 rustc", FileDialog.LOAD, false));
        tabs.addTab("编译设置", nativeOptions);

        JPanel licensing = form();
        row(licensing, 0, "验证公钥", key, browse(key, "选择验证公钥", FileDialog.LOAD, false));
        row(licensing, 1, "许可证文件", license, browse(license, "选择许可证文件", FileDialog.LOAD, false));
        row(licensing, 2, "功能位掩码", features, null);
        note(licensing, 3, "启用离线授权时选择公钥和许可证。");
        tabs.addTab("离线许可证", licensing);

        JPanel files = form();
        JButton workBrowser = new JButton("浏览…");
        workBrowser.setName("browse-work-dir");
        workBrowser.addActionListener(event -> {
            File selected = DirectoryPicker.select(frame, work.getText().trim());
            if (selected != null) work.setText(selected.getAbsolutePath());
        });
        row(files, 0, "工作目录", work, workBrowser);
        row(files, 1, "跳过报告", report, browse(report, "选择跳过报告", FileDialog.SAVE, false));
        tabs.addTab("输出设置", files);
        options.add(tabs, BorderLayout.CENTER);
        body.add(options, BorderLayout.NORTH);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setName("log");
        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createTitledBorder("运行日志"));
        body.add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        inspect.setName("inspect");
        build.setName("build");
        strict.setName("strict");
        status.setName("status");
        actions.add(inspect);
        actions.add(build);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(status, BorderLayout.WEST);
        footer.add(actions, BorderLayout.EAST);
        body.add(footer, BorderLayout.SOUTH);
        inspect.addActionListener(e -> run(false));
        build.addActionListener(e -> run(true));
        frame.setContentPane(body);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public static void open() {
        Runnable show = () -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { /* 使用当前 Swing 外观。 */ }
            new Workbench().frame.setVisible(true);
        };
        if (SwingUtilities.isEventDispatchThread()) show.run();
        else SwingUtilities.invokeLater(show);
    }

    private static JTextField field(String name) {
        JTextField field = new JTextField(34);
        field.setName(name);
        return field;
    }

    private static JPanel form() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));
        return panel;
    }

    private static void add(JPanel panel, Component item, int x, int y, int width, double weight) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x; c.gridy = y; c.gridwidth = width; c.weightx = weight;
        c.fill = weight == 0 ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(4, 5, 4, 5);
        panel.add(item, c);
    }

    private static void row(JPanel panel, int y, String text, JTextField field, JButton button) {
        JLabel label = new JLabel(text);
        label.setLabelFor(field);
        add(panel, label, 0, y, 1, 0);
        add(panel, field, 1, y, button == null ? 2 : 1, 1);
        if (button != null) add(panel, button, 2, y, 1, 0);
    }

    private static void note(JPanel panel, int y, String text) {
        add(panel, new JLabel(text), 0, y, 3, 1);
    }

    private JButton browse(JTextField field, String title, int mode, boolean jar) {
        JButton choose = new JButton("浏览…");
        choose.setName("browse-" + field.getName());
        choose.addActionListener(event -> {
            String oldInput = input.getText().trim();
            String oldOutput = output.getText().trim();
            String initial = field.getText().trim();
            if (field == output && initial.isEmpty() && !oldInput.isEmpty()) initial = defaultOutput();

            // Windows Java 8 的 Swing FilePane 在关闭文件对话框时可能收到失去焦点事件。
            FileDialog dialog = new FileDialog(frame, title, mode);
            try {
                if (!initial.isEmpty()) {
                    File file = new File(initial).getAbsoluteFile();
                    if (file.getParentFile() != null && file.getParentFile().isDirectory())
                        dialog.setDirectory(file.getParent());
                    dialog.setFile(file.getName());
                } else {
                    dialog.setDirectory(System.getProperty("user.dir"));
                }
                if (jar) dialog.setFilenameFilter((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
                dialog.setVisible(true);
                File[] selected = dialog.getFiles();
                if (selected.length == 0) return;
                boolean automaticOutput = field == input
                        && (oldOutput.isEmpty() || !oldInput.isEmpty() && oldOutput.equals(defaultOutput()));
                field.setText(selected[0].getAbsolutePath());
                if (automaticOutput) output.setText(defaultOutput());
            } finally {
                dialog.dispose();
            }
        });
        return choose;
    }

    private String defaultOutput() {
        File file = new File(input.getText().trim()).getAbsoluteFile();
        String name = file.getName();
        if (name.regionMatches(true, Math.max(0, name.length() - 4), ".jar", 0, 4))
            name = name.substring(0, name.length() - 4);
        return new File(file.getParentFile(), name + "-native.jar").getPath();
    }

    private static void option(List<String> args, JTextField field) {
        String value = field.getText().trim();
        if (!value.isEmpty()) {
            args.add("--" + field.getName());
            args.add(value);
        }
    }

    private void run(boolean compile) {
        if (input.getText().trim().isEmpty()) {
            status.setText("请选择输入 JAR");
            input.requestFocusInWindow();
            return;
        }
        if (compile && output.getText().trim().isEmpty()) output.setText(defaultOutput());
        List<String> args = new ArrayList<>();
        args.add(compile ? "build" : "inspect");
        for (JTextField field : new JTextField[]{input, include, exclude, report}) option(args, field);
        if (strict.isSelected()) args.add("--strict");
        if (compile) {
            for (JTextField field : new JTextField[]{output, targets, zig, rustc, shard, module, bridges, work, key, license, features})
                option(args, field);
            if (backend.getSelectedIndex() != 0) args.add("--native-backend=zig");
            if (!preObfuscation.isSelected()) args.add("--pre-obfuscation=off");
            if (fullVm.isSelected()) args.add("--vm-coverage=full");
            else if (!vmInclude.getText().trim().isEmpty()) {
                args.add("--vm-coverage");
                args.add("partial");
                option(args, vmInclude);
            }
        }
        log.setText("");
        status.setText(compile ? "正在构建…" : "正在检查…");
        build.setEnabled(false);
        inspect.setEnabled(false);
        new SwingWorker<Void, String>() {
            @Override protected Void doInBackground() throws Exception {
                Main.execute(args.toArray(new String[args.size()]), s -> publish(s));
                return null;
            }
            @Override protected void process(List<String> lines) {
                // SwingWorker 合并日志事件，每次只渲染面板能保留的尾部。
                int from = lines.size(), count = 0;
                while (from > 0 && count < LOG_LIMIT) count += lines.get(--from).length() + 1;
                StringBuilder tail = new StringBuilder(Math.min(count, LOG_LIMIT));
                for (int i = from; i < lines.size(); i++) {
                    String line = lines.get(i);
                    tail.append(line, Math.max(0, line.length() - LOG_LIMIT), line.length()).append('\n');
                }
                append(tail.toString());
            }
            @Override protected void done() {
                try {
                    get();
                    status.setText(compile ? "构建完成" : "检查完成");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    status.setText("任务已中断");
                } catch (ExecutionException e) {
                    status.setText(compile ? "构建失败" : "检查失败");
                    Throwable cause = e.getCause();
                    append(String.valueOf(cause.getMessage() == null ? cause : cause.getMessage()) + "\n");
                } finally {
                    build.setEnabled(true);
                    inspect.setEnabled(true);
                }
            }
        }.execute();
    }

    private void append(String text) {
        log.append(text);
        int length = log.getDocument().getLength();
        if (length > LOG_LIMIT) log.replaceRange("", 0, length - LOG_LIMIT);
        log.setCaretPosition(log.getDocument().getLength());
    }
}
