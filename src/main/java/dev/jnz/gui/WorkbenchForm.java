package dev.jnz.gui;

import dev.jnz.BuildConfig;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

import static dev.jnz.gui.StudioStyle.*;
import static dev.jnz.gui.StudioStyle.ButtonKind.SECONDARY;

/** Presents shared compiler options and converts form edits to the same arguments as the CLI. */
final class WorkbenchForm extends JPanel implements Scrollable {
    private static final String[] INSPECT_KEYS = {"input", "include", "exclude", "report", "strict"};
    private final Map<String, JTextField> fields = new LinkedHashMap<>();
    private final JFrame frame;
    private final Consumer<String> notification;
    private final CardLayout pages = new CardLayout();
    private final List<JComponent> inputs = new ArrayList<>();
    private final JTextField config = field("config", "配置文件");
    private final JCheckBox strict = check("严格模式 · 跳过时终止构建", false);
    private final JCheckBox pre = check("启用 Java 字节码预混淆", true);
    private final JComboBox<Choice> vm = choices("vm-coverage", "VM 策略", "",
            "按后端默认", "direct", "直接编译", "full", "全部使用 VM", "partial", "按模式使用 VM");
    private final JComboBox<Choice> flow = choices("flow-profile", "控制流", "balanced",
            "均衡", "strong", "增强", "off", "关闭");
    private final JComboBox<Choice> cipher = choices("string-cipher", "字符串", "aes",
            "AES", "xor", "XOR", "off", "关闭");
    private final JComboBox<Choice> backend = choices("native-backend", "编译后端", "rust",
            "Rust", "zig", "Zig（兼容）");
    private Map<String, String> loadedValues = Collections.emptyMap();
    private Path loadedConfig;

    WorkbenchForm(JFrame frame, Consumer<String> notification) {
        this.frame = frame;
        this.notification = notification;
        setOpaque(false);
        setLayout(pages);
        add(project(), "project");
        add(protection(), "protection");
        add(nativeOptions(), "native");
        strict.setName("strict");
        pre.setName("pre-obfuscation");
        Collections.addAll(inputs, config, strict, pre, vm, flow, cipher, backend);
        vm.addActionListener(event -> updateControlStates(true));
        pre.addActionListener(event -> updateControlStates(true));
        updateControlStates(true);
    }

    void showPage(String page) {
        pages.show(this, page);
        revalidate();
    }

    private JPanel project() {
        JPanel form = form();
        JTextField input = text("input", "输入 JAR");
        JTextField output = text("output", "输出 JAR");
        row(form, 0, "输入 JAR", input, browse(input, "选择输入 JAR", FileDialog.LOAD, ".jar"));
        row(form, 1, "输出 JAR", output, browse(output, "选择输出 JAR", FileDialog.SAVE, ".jar"));
        section(form, 2, "配置与报告");
        JButton configOpen = button("打开配置…", SECONDARY);
        configOpen.setName("browse-config");
        configOpen.addActionListener(event -> {
            File selected = choose("打开配置文件", FileDialog.LOAD, config.getText(), ".properties");
            if (selected != null) loadConfig(selected.toPath());
        });
        config.addActionListener(event -> {
            try {
                if (!config.getText().trim().isEmpty()) loadConfig(Paths.get(config.getText().trim()));
            } catch (RuntimeException error) {
                notification.accept("配置读取失败 · " + error.getMessage());
            }
        });
        row(form, 3, "配置文件", config, configOpen);
        JButton store = button("另存当前配置…", SECONDARY);
        store.setName("save-config");
        store.addActionListener(event -> saveConfig());
        inputs.add(store);
        GridBagConstraints actions = constraints(1, 4, 2, 0);
        actions.anchor = GridBagConstraints.LINE_START;
        actions.fill = GridBagConstraints.NONE;
        form.add(store, actions);
        JTextField report = text("report", "检查报告");
        row(form, 5, "检查报告", report, browse(report, "选择报告路径", FileDialog.SAVE, ".json"));
        JTextField work = text("work-dir", "工作目录");
        JButton workButton = button("浏览…", SECONDARY);
        workButton.setName("browse-work-dir");
        workButton.addActionListener(event -> {
            File selected = DirectoryPicker.select(frame, work.getText().trim());
            if (selected != null) work.setText(selected.getAbsolutePath());
        });
        row(form, 6, "工作目录", work, workButton);
        return form;
    }

    private JPanel protection() {
        JPanel form = form();
        JTextField include = text("include", "包含模式");
        JTextField exclude = text("exclude", "排除模式");
        row(form, 0, "包含模式", include, null);
        row(form, 1, "排除模式", exclude, null);
        note(form, 2, "例如 com/example/*#calc* 或 @com/example/Sensitive。多项用逗号分隔。");
        row(form, 3, "VM 策略", vm, null);
        JTextField vmInclude = text("vm-include", "VM 方法模式");
        row(form, 4, "VM 方法模式", vmInclude, null);
        note(form, 5, "含异常处理表的方法会自动使用 VM。");
        section(form, 6, "转换前");
        line(form, 7, pre);
        row(form, 8, "控制流", flow, null);
        row(form, 9, "字符串", cipher, null);
        line(form, 10, strict);
        return form;
    }

    private JPanel nativeOptions() {
        JPanel form = form();
        row(form, 0, "编译后端", backend, null);
        JTextField targets = text("targets", "目标平台");
        row(form, 1, "目标平台", targets, null);
        note(form, 2, "留空使用本机平台。多个目标用逗号分隔，例如 x86_64-windows-gnu,aarch64-linux-gnu。");
        JTextField rustc = text("rustc", "Rust 编译器");
        row(form, 3, "Rust 编译器", rustc, browse(rustc, "选择 rustc", FileDialog.LOAD, ""));
        JTextField zig = text("zig", "Zig 程序");
        row(form, 4, "Zig 程序", zig, browse(zig, "选择 Zig", FileDialog.LOAD, ""));
        section(form, 5, "模块分组");
        row(form, 6, "JNI 桥接组数", text("jni-bridges", "JNI 桥接组数"), null);
        row(form, 7, "模块大小", text("module-size", "模块大小"), null);
        row(form, 8, "资源分片大小", text("shard-size", "资源分片大小"), null);
        note(form, 9, "大小可写为 64K 或 1M。Rust 和 Zig 路径留空时由 J2Rust 自动查找。");
        return form;
    }

    private static JPanel form() {
        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setBackground(PAPER);
        wrap.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE, 1, true),
                new EmptyBorder(13, 19, 13, 19)));
        // Keep the settings top-aligned when the viewport offers more room.
        GridBagConstraints end = constraints(0, 20, 3, 1);
        end.weighty = 1;
        JPanel filler = new JPanel();
        filler.setOpaque(false);
        wrap.add(filler, end);
        return wrap;
    }

    private static GridBagConstraints constraints(int x, int y, int width, double weight) {
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = x;
        cell.gridy = y;
        cell.gridwidth = width;
        cell.weightx = weight;
        cell.anchor = GridBagConstraints.LINE_START;
        cell.fill = weight > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        cell.insets = new Insets(5, 0, 5, x == 2 ? 0 : 11);
        return cell;
    }

    private void row(JPanel form, int y, String title, JComponent editor, JButton action) {
        JLabel caption = label(title, 13, Font.BOLD, INK);
        caption.setLabelFor(editor);
        // Align every row by reserving a stable label column, even in the compact view.
        caption.setPreferredSize(new java.awt.Dimension(Math.max(104, caption.getPreferredSize().width),
                caption.getPreferredSize().height));
        form.add(caption, constraints(0, y, 1, 0));
        form.add(editor, constraints(1, y, action == null ? 2 : 1, 1));
        if (action != null) {
            form.add(action, constraints(2, y, 1, 0));
            inputs.add(action);
        }
    }

    private static void section(JPanel form, int y, String name) {
        GridBagConstraints cell = constraints(0, y, 3, 1);
        cell.insets = new Insets(15, 0, 3, 0);
        form.add(label(name, 13, Font.BOLD, ACCENT), cell);
    }

    private static void note(JPanel form, int y, String name) {
        form.add(label("<html>" + name + "</html>", 12, Font.PLAIN, MUTED), constraints(1, y, 2, 1));
    }

    private static void line(JPanel form, int y, Component component) {
        form.add(component, constraints(1, y, 2, 1));
    }

    private JTextField text(String key, String accessibleName) {
        JTextField field = StudioStyle.field(key, accessibleName);
        fields.put(key, field);
        inputs.add(field);
        return field;
    }

    private static JComboBox<Choice> choices(String key, String accessibleName, String... values) {
        Choice[] list = new Choice[values.length / 2];
        for (int i = 0; i < list.length; i++) list[i] = new Choice(values[2 * i], values[2 * i + 1]);
        JComboBox<Choice> box = new JComboBox<>(list);
        box.setName(key);
        box.getAccessibleContext().setAccessibleName(accessibleName);
        combo(box);
        return box;
    }

    private static final class Choice {
        final String key;
        final String label;

        Choice(String key, String label) {
            this.key = key;
            this.label = label;
        }

        @Override public String toString() { return label; }
    }

    private static String key(JComboBox<Choice> box) {
        return ((Choice) box.getSelectedItem()).key;
    }

    private static void select(JComboBox<Choice> box, Map<String, String> values) {
        String wanted = values.get(box.getName());
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i).key.equals(wanted)) {
                box.setSelectedIndex(i);
                return;
            }
        }
        box.setSelectedIndex(0);
    }

    private JButton browse(JTextField field, String title, int mode, String suffix) {
        JButton picker = button("浏览…", SECONDARY);
        picker.setName("browse-" + field.getName());
        picker.getAccessibleContext().setAccessibleName(title);
        picker.addActionListener(event -> {
            boolean input = field == fields.get("input");
            boolean output = field == fields.get("output");
            String oldOutput = value("output");
            String initial = field.getText().trim();
            Path derived = defaultOutput();
            if (output && initial.isEmpty() && derived != null) initial = derived.toString();
            File selected = choose(title, mode, initial, suffix);
            if (selected == null) return;
            field.setText(selected.getAbsolutePath());
            if (input && (oldOutput.isEmpty() || derived != null && oldOutput.equals(derived.toString())))
                fields.get("output").setText(defaultOutput().toString());
        });
        return picker;
    }

    File choose(String title, int mode, String initial, String suffix) {
        // Java 8 on Windows may deliver a late FilePane focus event to JFileChooser.
        FileDialog dialog = new FileDialog(frame, title, mode);
        try {
            if (initial.isEmpty()) {
                dialog.setDirectory(System.getProperty("user.dir"));
            } else {
                File file = new File(initial).getAbsoluteFile();
                if (file.getParentFile() != null && file.getParentFile().isDirectory())
                    dialog.setDirectory(file.getParent());
                dialog.setFile(file.getName());
            }
            if (!suffix.isEmpty()) {
                dialog.setFilenameFilter((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(suffix));
            }
            dialog.setVisible(true);
            File[] selected = dialog.getFiles();
            return selected.length == 0 ? null : selected[0];
        } finally {
            dialog.dispose();
        }
    }

    private void loadConfig(Path file) {
        try {
            applyConfig(file.toAbsolutePath().normalize());
            notification.accept("已载入 " + file.getFileName());
        } catch (IOException | RuntimeException error) {
            notification.accept("配置读取失败 · " + error.getMessage());
        }
    }

    private void applyConfig(Path file) throws IOException {
        Map<String, String> values = BuildConfig.read(file);
        loadedConfig = file;
        config.setText(file.toString());
        for (Map.Entry<String, JTextField> entry : fields.entrySet())
            entry.getValue().setText(values.getOrDefault(entry.getKey(), ""));
        strict.setSelected(Boolean.parseBoolean(values.getOrDefault("strict", "false")));
        pre.setSelected(!"off".equals(values.get("pre-obfuscation")));
        select(vm, values);
        select(flow, values);
        select(cipher, values);
        select(backend, values);
        updateControlStates(true);
        loadedValues = options();
    }

    private void saveConfig() {
        String initial = config.getText().trim();
        if (initial.isEmpty()) initial = "j2rust.properties";
        File selected = choose("保存当前配置", FileDialog.SAVE, initial, ".properties");
        if (selected == null) return;
        Path path = selected.toPath().toAbsolutePath().normalize();
        Properties data = new Properties();
        data.putAll(options());
        // GUI paths are interpreted from the process directory, including when saved elsewhere.
        for (String key : new String[]{"input", "output", "report", "work-dir", "rustc", "zig"}) {
            String value = data.getProperty(key);
            if (value != null) data.setProperty(key, Paths.get(value).toAbsolutePath().normalize().toString());
        }
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            data.store(writer, "J2Rust");
        } catch (IOException | RuntimeException error) {
            notification.accept("配置保存失败 · " + error.getMessage());
            return;
        }
        loadConfig(path);
    }

    private Map<String, String> options() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : fields.keySet()) {
            String value = value(key);
            if (!value.isEmpty() && (!key.equals("vm-include") || key(vm).equals("partial"))) result.put(key, value);
        }
        result.put("strict", Boolean.toString(strict.isSelected()));
        result.put("pre-obfuscation", pre.isSelected() ? "full" : "off");
        result.put("flow-profile", key(flow));
        result.put("string-cipher", key(cipher));
        result.put("native-backend", key(backend));
        if (!key(vm).isEmpty()) result.put("vm-coverage", key(vm));
        return result;
    }

    private String value(String key) {
        JTextField field = fields.get(key);
        return field == null ? "" : field.getText().trim();
    }

    private Path defaultOutput() {
        String value = value("input");
        if (value.isEmpty()) return null;
        Path source = Paths.get(value).toAbsolutePath().normalize();
        String name = source.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) name = name.substring(0, name.length() - 4);
        return source.resolveSibling(name + "-native.jar");
    }

    Job prepare(boolean build) throws IOException {
        if (config.getText().trim().isEmpty()) {
            loadedConfig = null;
            loadedValues = Collections.emptyMap();
        } else {
            Path file = Paths.get(config.getText().trim()).toAbsolutePath().normalize();
            if (!file.equals(loadedConfig)) applyConfig(file);
        }
        String source = value("input");
        if (source.isEmpty()) throw new IOException("请选择输入 JAR");
        Path input = Paths.get(source).toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) throw new IOException("找不到输入 JAR · " + input);
        if (build && value("output").isEmpty()) fields.get("output").setText(defaultOutput().toString());
        Path output = build ? Paths.get(value("output")).toAbsolutePath().normalize() : null;
        Path reportBase = build ? output : input;
        Path report = value("report").isEmpty()
                ? reportBase.resolveSibling(reportBase.getFileName() + ".reports").resolve("frontend-skips.json")
                : Paths.get(value("report")).toAbsolutePath().normalize();
        Map<String, String> values = options();
        List<String> args = new ArrayList<>();
        args.add(build ? "build" : "inspect");
        // A config stays active while its loaded snapshot matches the current form. When edited,
        // pass the entire form so clearing an inherited path genuinely restores its default.
        if (loadedConfig != null && values.equals(loadedValues)) {
            args.add("--config");
            args.add(loadedConfig.toString());
        } else {
            if (build) {
                if ("partial".equals(values.get("vm-coverage")) && !values.containsKey("vm-include"))
                    throw new IOException("部分 VM 策略需要填写 VM 方法模式");
                for (Map.Entry<String, String> item : values.entrySet()) addOption(args, item.getKey(), item.getValue());
            } else {
                for (String key : INSPECT_KEYS) if (values.containsKey(key)) addOption(args, key, values.get(key));
            }
        }
        return new Job(args.toArray(new String[0]), output, report);
    }

    private static void addOption(List<String> args, String key, String value) {
        if (key.equals("strict")) {
            if (value.equals("true")) args.add("--strict");
        } else {
            args.add("--" + key + "=" + value);
        }
    }

    void setEditing(boolean enabled) {
        for (JComponent input : inputs) input.setEnabled(enabled);
        updateControlStates(enabled);
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public Dimension getPreferredSize() {
        for (Component page : getComponents()) if (page.isVisible()) return page.getPreferredSize();
        return super.getPreferredSize();
    }
    @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) { return 25; }
    @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
        return Math.max(25, visible.height - 30);
    }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
    }

    private void updateControlStates(boolean enabled) {
        fields.get("vm-include").setEnabled(enabled && key(vm).equals("partial"));
        flow.setEnabled(enabled && pre.isSelected());
        cipher.setEnabled(enabled && pre.isSelected());
    }

    static final class Job {
        final String[] args;
        final Path output;
        final Path report;

        Job(String[] args, Path output, Path report) {
            this.args = args;
            this.output = output;
            this.report = report;
        }
    }
}
