package studio.ui;

import studio.kdb.DiscoveryConfig;
import studio.kdb.Config;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Dialog for configuring the kdb+ discovery service connection.
 * Settings are persisted in studio.properties via Config.
 */
public class DiscoveryConfigDialog extends JDialog {

    private final JTextField     hostField    = new JTextField(24);
    private final JSpinner       portSpinner  = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private final JTextField     userField    = new JTextField(24);
    private final JPasswordField passField    = new JPasswordField(24);
    private final JTextArea      queryArea    = new JTextArea(4, 30);

    public DiscoveryConfigDialog(Frame parent) {
        super(parent, "Configure Discovery Service", true);
        buildUI();
        loadCurrent();
        pack();
        Util.centerChildOnParent(this, parent);
    }

    private void buildUI() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(4, 4, 4, 4);

        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.insets = new Insets(4, 4, 4, 4);

        GridBagConstraints wc = new GridBagConstraints();
        wc.fill = GridBagConstraints.BOTH;
        wc.weightx = 1.0;
        wc.weighty = 1.0;
        wc.insets = new Insets(4, 4, 4, 4);

        int row = 0;

        lc.gridy = row; fc.gridy = row;
        form.add(new JLabel("Host:"), lc);
        form.add(hostField, fc);
        row++;

        lc.gridy = row; fc.gridy = row;
        form.add(new JLabel("Port:"), lc);
        // remove default thousands-separator formatting from the spinner
        ((JSpinner.NumberEditor) portSpinner.getEditor()).getFormat().setGroupingUsed(false);
        form.add(portSpinner, fc);
        row++;

        lc.gridy = row; fc.gridy = row;
        form.add(new JLabel("Username:"), lc);
        form.add(userField, fc);
        row++;

        lc.gridy = row; fc.gridy = row;
        form.add(new JLabel("Password:"), lc);
        form.add(passField, fc);
        row++;

        lc.gridy = row;
        lc.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel("Discovery query:"), lc);

        wc.gridy = row;
        wc.gridx = 1;
        queryArea.setLineWrap(true);
        queryArea.setWrapStyleWord(true);
        queryArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane queryScroll = new JScrollPane(queryArea);
        queryScroll.setPreferredSize(new Dimension(300, 80));
        form.add(queryScroll, wc);
        row++;

        // Buttons
        JButton saveBtn   = new JButton("Save");
        JButton cancelBtn = new JButton("Cancel");
        saveBtn.addActionListener(e -> save());
        cancelBtn.addActionListener(e -> dispose());

        getRootPane().setDefaultButton(saveBtn);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancelBtn);
        buttons.add(saveBtn);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
    }

    private void loadCurrent() {
        DiscoveryConfig cfg = Config.getInstance().getDiscoveryConfig();
        hostField.setText(cfg.getHost());
        portSpinner.setValue(cfg.getPort());
        userField.setText(cfg.getUsername());
        passField.setText(cfg.getPassword());
        queryArea.setText(cfg.getQuery());
    }

    private void save() {
        String host = hostField.getText().trim();
        int port;
        try {
            port = ((Number) portSpinner.getValue()).intValue();
        } catch (Exception ex) {
            port = 0;
        }
        if (host.isEmpty() || port <= 0) {
            JOptionPane.showMessageDialog(this,
                    "Please enter a valid host and port.",
                    "Validation Error", JOptionPane.WARNING_MESSAGE);
            return;
        }
        DiscoveryConfig cfg = new DiscoveryConfig(
                host, port,
                queryArea.getText().trim(),
                userField.getText().trim(),
                new String(passField.getPassword()));
        Config.getInstance().setDiscoveryConfig(cfg);
        dispose();
    }
}
