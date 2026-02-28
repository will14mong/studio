package studio.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Modal dialog that prompts for username and password before connecting to
 * a discovered service for the first time. Credentials are NOT persisted.
 */
public class CredentialDialog extends JDialog {

    private final JTextField     usernameField = new JTextField(20);
    private final JPasswordField passwordField = new JPasswordField(20);
    private boolean accepted = false;

    private CredentialDialog(Frame parent, String serviceName, String errorMessage) {
        super(parent, "Connect to " + serviceName, true);
        buildUI(serviceName, errorMessage);
        pack();
        Util.centerChildOnParent(this, parent);
    }

    private void buildUI(String serviceName, String errorMessage) {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(4, 8, 4, 4);
        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.insets = new Insets(4, 4, 4, 8);

        int row = 0;

        if (errorMessage != null) {
            GridBagConstraints ec = new GridBagConstraints();
            ec.gridx = 0; ec.gridy = row++; ec.gridwidth = 2;
            ec.fill = GridBagConstraints.HORIZONTAL;
            ec.insets = new Insets(6, 8, 2, 8);
            JLabel errLabel = new JLabel(errorMessage);
            errLabel.setForeground(Color.RED);
            form.add(errLabel, ec);
        }

        lc.gridy = row; fc.gridy = row++;
        form.add(new JLabel("Service:"), lc);
        JLabel svcLabel = new JLabel(serviceName);
        svcLabel.setFont(svcLabel.getFont().deriveFont(Font.BOLD));
        form.add(svcLabel, fc);

        lc.gridy = row; fc.gridy = row++;
        form.add(new JLabel("Username:"), lc);
        form.add(usernameField, fc);

        lc.gridy = row; fc.gridy = row++;
        form.add(new JLabel("Password:"), lc);
        form.add(passwordField, fc);

        JButton okBtn     = new JButton("Connect");
        JButton cancelBtn = new JButton("Cancel");

        okBtn.addActionListener(e -> { accepted = true; dispose(); });
        cancelBtn.addActionListener(e -> dispose());

        // Allow Enter key in password field to submit
        passwordField.addActionListener(e -> { accepted = true; dispose(); });

        // Escape closes the dialog
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        getRootPane().setDefaultButton(okBtn);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancelBtn);
        buttons.add(okBtn);

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
    }

    /**
     * Show the credential dialog. Returns String[]{username, password} if the
     * user clicked Connect, or null if cancelled.
     *
     * @param errorMessage optional message shown in red above the form (e.g. after
     *                     a failed attempt); pass null for the initial prompt.
     */
    public static String[] prompt(Frame parent, String serviceName, String errorMessage) {
        CredentialDialog dlg = new CredentialDialog(parent, serviceName, errorMessage);
        dlg.setVisible(true); // blocks until disposed
        if (!dlg.accepted) return null;
        return new String[]{
            dlg.usernameField.getText(),
            new String(dlg.passwordField.getPassword())
        };
    }

    /** Convenience overload for the initial prompt (no error message). */
    public static String[] prompt(Frame parent, String serviceName) {
        return prompt(parent, serviceName, null);
    }
}
