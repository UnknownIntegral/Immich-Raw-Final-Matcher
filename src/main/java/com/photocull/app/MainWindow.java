package com.photocull.app;

import com.photocull.matcher.FileMover;
import com.photocull.matcher.FileScanner;
import com.photocull.matcher.MatchEngine;
import com.photocull.matcher.MatchResult;
import com.photocull.matcher.MatchStatus;
import com.photocull.matcher.PhotoFile;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class MainWindow extends JFrame {
    private final JTextField rawRootField = new JTextField();
    private final JTextField finalRootField = new JTextField();
    private final JTextField destinationField = new JTextField();
    private final JSpinner thresholdSpinner = new JSpinner(new SpinnerNumberModel(90, 0, 100, 1));
    private final JButton scanButton = new JButton("Scan");
    private final JButton acceptButton = new JButton("Accept");
    private final JButton rejectButton = new JButton("Reject");
    private final JButton openRawButton = new JButton("Open RAW");
    private final JButton openFinalButton = new JButton("Open Final");
    private final JButton moveButton = new JButton("Move Accepted RAWs");
    private final JCheckBox reviewOnly = new JCheckBox("Review only");
    private final JLabel statusLabel = new JLabel("Choose folders to begin.");
    private final JProgressBar progressBar = new JProgressBar();
    private final MatchTableModel tableModel = new MatchTableModel();
    private final JTable table = new JTable(tableModel);
    private final TableRowSorter<MatchTableModel> sorter = new TableRowSorter<>(tableModel);

    public MainWindow() {
        super("Photo Culling Assistant");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new java.awt.Dimension(1100, 650));
        setLocationByPlatform(true);

        setLayout(new BorderLayout(10, 10));
        add(buildInputPanel(), BorderLayout.NORTH);
        add(buildTablePanel(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        wireActions();
        setControlsEnabled(true);
        pack();
    }

    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        addFolderRow(panel, gbc, 0, "RAW root", rawRootField, "Browse", () -> chooseDirectory(rawRootField));
        addFolderRow(panel, gbc, 1, "Finished images", finalRootField, "Browse", () -> chooseDirectory(finalRootField));
        addFolderRow(panel, gbc, 2, "Move RAWs to", destinationField, "Browse", () -> chooseDirectory(destinationField));

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        panel.add(new JLabel("Auto-accept"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(thresholdSpinner);
        controls.add(new JLabel("% and higher"));
        controls.add(scanButton);
        controls.add(reviewOnly);
        panel.add(controls, gbc);

        return panel;
    }

    private void addFolderRow(
            JPanel panel,
            GridBagConstraints gbc,
            int row,
            String label,
            JTextField field,
            String buttonText,
            Runnable browseAction
    ) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(field, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        JButton button = new JButton(buttonText);
        button.addActionListener(event -> browseAction.run());
        panel.add(button, gbc);
    }

    private JScrollPane buildTablePanel() {
        table.setAutoCreateRowSorter(false);
        table.setRowSorter(sorter);
        table.setFillsViewportHeight(true);
        table.setRowHeight(26);
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD));
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(280);
        table.getColumnModel().getColumn(3).setPreferredWidth(280);
        table.getColumnModel().getColumn(4).setPreferredWidth(420);
        table.getColumnModel().getColumn(1).setCellRenderer(new ScoreRenderer());
        return new JScrollPane(table);
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(acceptButton);
        actions.add(rejectButton);
        actions.add(openRawButton);
        actions.add(openFinalButton);
        actions.add(moveButton);
        footer.add(actions, BorderLayout.WEST);

        JPanel statusPanel = new JPanel(new BorderLayout(8, 0));
        progressBar.setIndeterminate(false);
        progressBar.setStringPainted(false);
        statusPanel.add(statusLabel, BorderLayout.CENTER);
        statusPanel.add(progressBar, BorderLayout.EAST);
        footer.add(statusPanel, BorderLayout.CENTER);
        return footer;
    }

    private void wireActions() {
        scanButton.addActionListener(event -> scan());
        acceptButton.addActionListener(event -> updateSelected(MatchStatus.ACCEPTED));
        rejectButton.addActionListener(event -> updateSelected(MatchStatus.REJECTED));
        openRawButton.addActionListener(event -> openSelected(true));
        openFinalButton.addActionListener(event -> openSelected(false));
        moveButton.addActionListener(event -> moveAccepted());
        reviewOnly.addActionListener(event -> applyReviewFilter());

        table.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "accept");
        table.getActionMap().put("accept", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                updateSelected(MatchStatus.ACCEPTED);
            }
        });
    }

    private void chooseDirectory(JTextField target) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (!target.getText().isBlank()) {
            chooser.setCurrentDirectory(Path.of(target.getText()).toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void scan() {
        Path rawRoot = parseDirectory(rawRootField.getText(), "RAW root");
        Path finalRoot = parseDirectory(finalRootField.getText(), "finished images");
        if (rawRoot == null || finalRoot == null) {
            return;
        }

        int threshold = (Integer) thresholdSpinner.getValue();
        setControlsEnabled(false);
        progressBar.setIndeterminate(true);
        tableModel.setRows(List.of());
        statusLabel.setText("Scanning folders...");

        SwingWorker<List<MatchResult>, String> worker = new SwingWorker<>() {
            @Override
            protected List<MatchResult> doInBackground() throws Exception {
                FileScanner scanner = new FileScanner();
                publish("Scanning RAW files...");
                List<PhotoFile> raws = scanner.scanRawFiles(rawRoot);
                publish("Found " + raws.size() + " RAW files. Scanning finished images...");
                List<PhotoFile> finals = scanner.scanFinishedFiles(finalRoot);
                publish("Found " + finals.size() + " finished images. Matching...");
                return new MatchEngine().match(raws, finals, threshold, this::publish);
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) {
                    statusLabel.setText(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                try {
                    List<MatchResult> results = get();
                    tableModel.setRows(results);
                    applyReviewFilter();
                    statusLabel.setText(summary(results));
                } catch (Exception ex) {
                    showError("Scan failed", ex);
                    statusLabel.setText("Scan failed.");
                } finally {
                    progressBar.setIndeterminate(false);
                    setControlsEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private Path parseDirectory(String text, String name) {
        if (text == null || text.isBlank()) {
            JOptionPane.showMessageDialog(this, "Choose a " + name + " folder.", "Missing folder", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        Path path = Path.of(text);
        if (!Files.isDirectory(path)) {
            JOptionPane.showMessageDialog(this, "The " + name + " path is not a folder.", "Invalid folder", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return path;
    }

    private String summary(List<MatchResult> results) {
        long accepted = results.stream().filter(MatchResult::isAcceptedForMove).count();
        long review = results.stream().filter(r -> r.status() == MatchStatus.NEEDS_REVIEW).count();
        long rejected = results.stream().filter(r -> r.status() == MatchStatus.REJECTED).count();
        return "Matched " + results.size() + " finished images. Accepted: " + accepted + ", review: " + review + ", rejected: " + rejected + ".";
    }

    private void applyReviewFilter() {
        if (reviewOnly.isSelected()) {
            sorter.setRowFilter(new RowFilter<>() {
                @Override
                public boolean include(Entry<? extends MatchTableModel, ? extends Integer> entry) {
                    MatchResult result = tableModel.getRow(entry.getIdentifier());
                    return result.status() == MatchStatus.NEEDS_REVIEW;
                }
            });
        } else {
            sorter.setRowFilter(null);
        }
    }

    private void updateSelected(MatchStatus status) {
        int[] selected = table.getSelectedRows();
        if (selected.length == 0) {
            return;
        }
        List<Integer> modelRows = new ArrayList<>();
        for (int row : selected) {
            modelRows.add(table.convertRowIndexToModel(row));
        }
        tableModel.updateStatus(modelRows, status);
        statusLabel.setText(summary(tableModel.rows()));
    }

    private void openSelected(boolean raw) {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        MatchResult result = tableModel.getRow(table.convertRowIndexToModel(viewRow));
        Path path = raw ? result.rawPathOrNull() : result.finished().path();
        if (path == null) {
            return;
        }
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException ex) {
            showError("Could not open file", ex);
        }
    }

    private void moveAccepted() {
        Path destination = parseDirectory(destinationField.getText(), "destination");
        if (destination == null) {
            return;
        }
        long count = tableModel.rows().stream()
                .filter(MatchResult::isAcceptedForMove)
                .map(MatchResult::rawPathOrNull)
                .distinct()
                .count();
        if (count == 0) {
            JOptionPane.showMessageDialog(this, "No accepted RAW files are ready to move.", "Nothing to move", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int choice = JOptionPane.showConfirmDialog(
                this,
                "Move " + count + " accepted RAW file(s) to the destination folder?",
                "Move RAW files",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        setControlsEnabled(false);
        progressBar.setIndeterminate(true);
        statusLabel.setText("Moving RAW files...");

        SwingWorker<List<MatchResult>, String> worker = new SwingWorker<>() {
            @Override
            protected List<MatchResult> doInBackground() throws Exception {
                return new FileMover().moveAccepted(tableModel.rows(), destination, this::publish);
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) {
                    statusLabel.setText(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                try {
                    tableModel.setRows(get());
                    statusLabel.setText("Move complete. Manifest written to the destination folder.");
                } catch (Exception ex) {
                    showError("Move failed", ex);
                    statusLabel.setText("Move failed.");
                } finally {
                    progressBar.setIndeterminate(false);
                    setControlsEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private void setControlsEnabled(boolean enabled) {
        scanButton.setEnabled(enabled);
        acceptButton.setEnabled(enabled);
        rejectButton.setEnabled(enabled);
        openRawButton.setEnabled(enabled);
        openFinalButton.setEnabled(enabled);
        moveButton.setEnabled(enabled);
        reviewOnly.setEnabled(enabled);
    }

    private void showError(String title, Exception ex) {
        JOptionPane.showMessageDialog(this, ex.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    private static final class ScoreRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column
        ) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected && value instanceof Number number) {
                int score = number.intValue();
                if (score >= 90) {
                    component.setBackground(new Color(222, 245, 229));
                } else if (score >= 70) {
                    component.setBackground(new Color(255, 245, 214));
                } else {
                    component.setBackground(new Color(255, 225, 225));
                }
            }
            setHorizontalAlignment(CENTER);
            return component;
        }
    }

    private static final class MatchTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Status", "Score", "Finished image", "RAW match", "Reason"};
        private List<MatchResult> rows = new ArrayList<>();

        public void setRows(List<MatchResult> rows) {
            this.rows = new ArrayList<>(rows);
            fireTableDataChanged();
        }

        public List<MatchResult> rows() {
            return List.copyOf(rows);
        }

        public MatchResult getRow(int row) {
            return rows.get(row);
        }

        public void updateStatus(List<Integer> rowIndexes, MatchStatus status) {
            for (int index : rowIndexes) {
                MatchResult current = rows.get(index);
                if (current.rawPathOrNull() != null || status == MatchStatus.REJECTED) {
                    rows.set(index, current.withStatus(status));
                }
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MatchResult result = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> result.status().label();
                case 1 -> result.score();
                case 2 -> result.finished().path().toString();
                case 3 -> result.rawPathOrNull() == null ? "" : result.rawPathOrNull().toString();
                case 4 -> result.reason();
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 1 ? Integer.class : String.class;
        }
    }
}
