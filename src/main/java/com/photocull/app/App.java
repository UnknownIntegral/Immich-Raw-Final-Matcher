package com.photocull.app;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class App {
    private App() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Swing's default look and feel is acceptable if the system one is unavailable.
            }

            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }
}

