package dev.jnz.gui;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;

/** 显示现有文件夹，工作目录也可以在主窗口中手动输入。 */
final class DirectoryPicker {
    private final JDialog dialog;
    private final JTextField path = new JTextField(32);
    private final JLabel message = new JLabel(" ");
    private final DefaultListModel<File> folders = new DefaultListModel<>();
    private final JList<File> list = new JList<>(folders);
    private File current;
    private File selection;

    private DirectoryPicker(Frame owner, String initial) {
        dialog = new JDialog(owner, "选择工作目录", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(new EmptyBorder(10, 10, 10, 10));
        JButton go = new JButton("打开");
        JButton up = new JButton("上一级");
        JPanel address = new JPanel(new BorderLayout(6, 0));
        address.add(up, BorderLayout.WEST);
        address.add(path, BorderLayout.CENTER);
        address.add(go, BorderLayout.EAST);
        content.add(address, BorderLayout.NORTH);

        list.setVisibleRowCount(10);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        DefaultListCellRenderer renderer = new DefaultListCellRenderer();
        list.setCellRenderer((items, file, index, selected, focused) ->
                renderer.getListCellRendererComponent(
                        items, file.getName().isEmpty() ? file.getPath() : file.getName(), index, selected, focused));
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (event.getClickCount() == 2 && index >= 0
                        && list.getCellBounds(index, index).contains(event.getPoint())) {
                    showFolders(folders.get(index));
                }
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-folder");
        list.getActionMap().put("open-folder", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                File child = list.getSelectedValue();
                if (child != null) showFolders(child);
            }
        });
        content.add(new JScrollPane(list), BorderLayout.CENTER);

        JButton accept = new JButton("选择当前目录");
        JButton cancel = new JButton("取消");
        JPanel controls = new JPanel();
        controls.add(accept);
        controls.add(cancel);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(message, BorderLayout.CENTER);
        footer.add(controls, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);
        go.addActionListener(event -> openPath());
        path.addActionListener(event -> openPath());
        up.addActionListener(event -> showFolders(current == null ? null : current.getParentFile()));
        accept.addActionListener(event -> {
            if (!openPath() || current == null) return;
            selection = current;
            dialog.dispose();
        });
        cancel.addActionListener(event -> dialog.dispose());
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setContentPane(content);
        File directory = initial.isEmpty() ? null : new File(initial).getAbsoluteFile();
        if (directory == null || !directory.isDirectory()) directory = new File(System.getProperty("user.dir"));
        showFolders(directory);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
    }

    static File select(Frame owner, String initial) {
        DirectoryPicker picker = new DirectoryPicker(owner, initial);
        picker.dialog.setVisible(true);
        return picker.selection;
    }

    private boolean openPath() {
        String text = path.getText().trim();
        if (text.isEmpty() && current == null) return false;
        File candidate = new File(text).getAbsoluteFile();
        if (candidate.equals(current)) return true;
        return showFolders(candidate);
    }

    private boolean showFolders(File directory) {
        File[] children = directory == null ? File.listRoots() : directory.listFiles(File::isDirectory);
        if (children == null) {
            message.setText("无法打开此目录");
            return false;
        }
        current = directory;
        path.setText(directory == null ? "" : directory.getPath());
        message.setText(directory == null ? "打开一个驱动器" : " ");
        Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(File::getPath));
        folders.clear();
        for (File child : children) folders.addElement(child);
        return true;
    }
}
