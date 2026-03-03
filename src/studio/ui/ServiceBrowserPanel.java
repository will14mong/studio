package studio.ui;

import studio.kdb.*;
import studio.utils.SwingWorker;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Left-pane service browser.
 *
 * Shows the parent server as the first item, then any services discovered from
 * that server's discovery query (if non-empty).
 *
 * Connection status:
 *   GREY  = never connected / not tried
 *   GREEN = last query to this service succeeded
 *   RED   = last connection attempt failed
 */
public class ServiceBrowserPanel extends JPanel {

    // ── Connection status per service key (host:port) ─────────────────────────
    public enum Status { UNKNOWN, OK, ERROR }

    private final Map<String, Status> statusMap = new HashMap<>();

    // ── Data ──────────────────────────────────────────────────────────────────
    private final List<ServiceEntry> services = new ArrayList<>();
    private Server currentServer;
    /** Incremented on every loadForServer call; workers compare against it to detect staleness. */
    private int loadGeneration = 0;

    // ── UI ────────────────────────────────────────────────────────────────────
    private final DefaultListModel<ServiceEntry> listModel = new DefaultListModel<>();
    private final JList<ServiceEntry> serviceList = new JList<>(listModel);
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton refreshBtn = new JButton("Refresh");
    private final JTextField filterField = new JTextField();

    // ── Callback ──────────────────────────────────────────────────────────────
    public interface ServiceSelectionListener {
        void onServiceSelected(ServiceEntry entry);
    }

    private ServiceSelectionListener selectionListener;

    // ─────────────────────────────────────────────────────────────────────────

    public ServiceBrowserPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        buildUI();
    }

    public void setSelectionListener(ServiceSelectionListener l) {
        this.selectionListener = l;
    }

    // ──────────────────────── UI construction ────────────────────────────────

    private void buildUI() {
        // ── Top toolbar ──────────────────────────────────────────────────────
        refreshBtn.setToolTipText("Re-run discovery query to refresh service list");
        refreshBtn.setEnabled(false);
        refreshBtn.addActionListener(e -> {
            if (currentServer != null) loadForServer(currentServer);
        });

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        toolbar.add(new JLabel("Services"));
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(refreshBtn);

        // ── Filter bar ───────────────────────────────────────────────────────
        filterField.setToolTipText("Type to filter services by name, host, or port");
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        JPanel filterBar = new JPanel(new BorderLayout(4, 0));
        filterBar.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        filterBar.add(new JLabel("Filter:"), BorderLayout.WEST);
        filterBar.add(filterField, BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout());
        north.add(toolbar,   BorderLayout.NORTH);
        north.add(filterBar, BorderLayout.SOUTH);

        // ── Service list ─────────────────────────────────────────────────────
        serviceList.setCellRenderer(new ServiceCellRenderer());
        serviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        serviceList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1) {
                    int idx = serviceList.locationToIndex(e.getPoint());
                    if (idx >= 0 && selectionListener != null) {
                        selectionListener.onServiceSelected(listModel.getElementAt(idx));
                    }
                }
            }
        });

        JScrollPane scroll = new JScrollPane(serviceList);
        scroll.setBorder(BorderFactory.createEtchedBorder());

        // ── Status bar ───────────────────────────────────────────────────────
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        add(north,  BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    /**
     * Rebuilds listModel from services, keeping only entries whose name, host,
     * or port contain the current filter text (case-insensitive). An empty filter
     * shows all entries.
     */
    private void applyFilter() {
        String text = filterField.getText().trim().toLowerCase();
        listModel.clear();
        for (ServiceEntry e : services) {
            if (text.isEmpty()
                    || e.getName().toLowerCase().contains(text)
                    || e.getHost().toLowerCase().contains(text)
                    || String.valueOf(e.getPort()).contains(text)) {
                listModel.addElement(e);
            }
        }
    }

    // ──────────────────────── Public status update ───────────────────────────

    /** Mark a service as connected-OK (called after a successful query). */
    public void markOk(ServiceEntry entry) {
        statusMap.put(entry.getKey(), Status.OK);
        serviceList.repaint();
    }

    /** Mark a service as failed (called after a connection / query error). */
    public void markError(ServiceEntry entry) {
        statusMap.put(entry.getKey(), Status.ERROR);
        serviceList.repaint();
    }

    /**
     * Called when a query execution (or connection probe) fails for the given service.
     *
     * <ul>
     *   <li>If the failed service is the <em>discovery server</em>: all discovered
     *       services are removed from the list and their pool connections are closed.
     *       Only the discovery row remains, with its indicator turned red.</li>
     *   <li>If the failed service is a <em>discovered service</em>: that row is
     *       removed from the list and its pool connections are closed.</li>
     * </ul>
     */
    public void handleDisconnection(ServiceEntry disconnected) {
        String key = disconnected.getKey();
        boolean isDiscoveryServer = currentServer != null
                && key.equals(currentServer.getHost() + ":" + currentServer.getPort());

        if (isDiscoveryServer) {
            // Remove every discovered entry (index 1 onwards); keep index 0 (the discovery server row).
            for (int i = services.size() - 1; i >= 1; i--) {
                ServiceEntry e = services.remove(i);
                statusMap.remove(e.getKey());
                if (e.getServer() != null)
                    ConnectionPool.getInstance().purge(e.getServer());
            }
            statusMap.put(key, Status.ERROR);
            statusLabel.setText("Discovery server offline");
        } else {
            // Remove only the one disconnected service (skip index 0 = discovery server).
            for (int i = 1; i < services.size(); i++) {
                if (services.get(i).getKey().equals(key)) {
                    ServiceEntry e = services.remove(i);
                    statusMap.remove(key);
                    if (e.getServer() != null)
                        ConnectionPool.getInstance().purge(e.getServer());
                    break;
                }
            }
            updateStatusLabel();
        }
        applyFilter();
        serviceList.repaint();
    }

    private void updateStatusLabel() {
        int n = services.size();
        statusLabel.setText(n + " service" + (n == 1 ? "" : "s"));
    }

    // ─────────────────────────── Load for server ─────────────────────────────

    /**
     * Populates the service list for the given server.
     *
     * The first item is always the server itself (labelled with server.getName()).
     * If the server has a non-empty discoveryQuery, runs it on a background thread
     * and appends the results as additional items.
     */
    public void loadForServer(Server server) {
        if (server == null) return;
        currentServer = server;
        final int myGeneration = ++loadGeneration;
        refreshBtn.setEnabled(true);

        // Clear existing list and reset all status indicators so same-host:port
        // entries on the new server start as UNKNOWN (grey) rather than inheriting
        // stale OK/ERROR state from the previous server.
        listModel.clear();
        services.clear();
        statusMap.clear();
        statusLabel.setText("Loading...");

        // Always add the parent server as the first entry
        ServiceEntry parentEntry = new ServiceEntry(
                server.getName(), server.getHost(), server.getPort(), server);
        services.add(parentEntry);
        applyFilter();

        String query = server.getDiscoveryQuery();
        if (query == null || query.trim().isEmpty()) {
            updateStatusLabel();
            return;
        }

        // Run discovery query on background thread
        statusLabel.setText("Discovering...");

        SwingWorker discoveryWorker = new SwingWorker() {
            private List<ServiceEntry> found = new ArrayList<>();
            private String errorMsg = null;

            public Object construct() {
                kx.c conn = null;
                try {
                    conn = ConnectionPool.getInstance().leaseConnection(server);
                    ConnectionPool.getInstance().checkConnected(conn);
                    conn.k(new K.KCharacterVector(query.trim()));
                    K.KBase response = conn.getResponse();
                    found = parseDiscoveryResponse(response);
                } catch (Throwable e) {
                    errorMsg = e.getMessage();
                    System.err.println("Discovery refresh failed: " + e);
                    e.printStackTrace(System.err);
                } finally {
                    if (conn != null)
                        ConnectionPool.getInstance().freeConnection(server, conn);
                }
                return null;
            }

            public void finished() {
                if (myGeneration != loadGeneration) return; // a newer load has started; discard stale results
                if (errorMsg != null) {
                    statusLabel.setText("Discovery error: " + errorMsg);
                } else {
                    for (ServiceEntry e : found) {
                        // Inherit the parent server's background colour so that
                        // toServer() builds a Server with the right highlight colour.
                        e.setBackgroundColor(server.getBackgroundColor());
                        services.add(e);
                    }
                    applyFilter();
                    updateStatusLabel();
                }
            }
        };

        discoveryWorker.start();
    }

    // ─────────────────────────── Response parsing ─────────────────────────────

    /**
     * Parses a kdb+ response of the form: name!(host;port)
     *
     * Keys   = K.KSymbolVector (service names)
     * Values = K.KList where element[i] is a 2-element K.KList: {K.KSymbol host, K.KInteger port}
     */
    private List<ServiceEntry> parseDiscoveryResponse(K.KBase response) {
        if (response instanceof K.Flip)
            return parseDiscoveryTable((K.Flip) response);
        if (response instanceof K.Dict)
            return parseDiscoveryDict((K.Dict) response);
        System.err.println("Discovery: unexpected response type: "
                + response.getClass().getSimpleName());
        return new ArrayList<>();
    }

    /** Parse a table with columns: name (symbol), host (symbol), port (int). */
    private List<ServiceEntry> parseDiscoveryTable(K.Flip flip) {
        List<ServiceEntry> result = new ArrayList<>();

        // Locate required columns by name
        int nameCol = -1, hostCol = -1, portCol = -1;
        int numCols = flip.x.getLength();
        for (int c = 0; c < numCols; c++) {
            String col = ((K.KSymbol) flip.x.at(c)).s;
            if ("name".equals(col)) nameCol = c;
            else if ("host".equals(col)) hostCol = c;
            else if ("port".equals(col)) portCol = c;
        }
        if (nameCol < 0 || hostCol < 0 || portCol < 0) {
            System.err.println("Discovery: table missing required column(s) (need name, host, port)");
            return result;
        }

        K.KBaseVector nameVec = (K.KBaseVector) flip.y.at(nameCol);
        K.KBaseVector hostVec = (K.KBaseVector) flip.y.at(hostCol);
        K.KBaseVector portVec = (K.KBaseVector) flip.y.at(portCol);

        int rows = nameVec.getLength();
        for (int i = 0; i < rows; i++) {
            try {
                String name = ((K.KSymbol)  nameVec.at(i)).s;
                String host = ((K.KSymbol)  hostVec.at(i)).s;
                int    port = ((K.KInteger) portVec.at(i)).i;
                result.add(new ServiceEntry(name, host, port));
            } catch (Exception e) {
                System.err.println("Discovery: could not parse table row " + i + ": " + e);
            }
        }
        return result;
    }

    /** Parse a dict: `svc1`svc2!((`host1;port1i);(`host2;port2i)) */
    private List<ServiceEntry> parseDiscoveryDict(K.Dict dict) {
        List<ServiceEntry> result = new ArrayList<>();

        if (!(dict.x instanceof K.KSymbolVector)) {
            System.err.println("Discovery: dict keys are not KSymbolVector");
            return result;
        }
        if (!(dict.y instanceof K.KList)) {
            System.err.println("Discovery: dict values are not KList");
            return result;
        }

        K.KSymbolVector names  = (K.KSymbolVector) dict.x;
        K.KList         values = (K.KList) dict.y;

        int count = names.getLength();
        for (int i = 0; i < count; i++) {
            try {
                String name = ((K.KSymbol)  names.at(i)).s;
                K.KList pair = (K.KList) values.at(i);
                String host = ((K.KSymbol)  pair.at(0)).s;
                int    port = ((K.KInteger) pair.at(1)).i;
                result.add(new ServiceEntry(name, host, port));
            } catch (Exception e) {
                System.err.println("Discovery: could not parse dict entry " + i + ": " + e);
            }
        }
        return result;
    }

    // ──────────────────────────── Cell renderer ───────────────────────────────

    private class ServiceCellRenderer extends JPanel implements ListCellRenderer<ServiceEntry> {
        private final JLabel dotLabel  = new JLabel("\u25CF"); // filled circle
        private final JLabel nameLabel = new JLabel();
        private final JLabel addrLabel = new JLabel();

        ServiceCellRenderer() {
            setLayout(new BorderLayout(6, 0));
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            dotLabel.setFont(dotLabel.getFont().deriveFont(10f));

            JPanel left = new JPanel(new BorderLayout(4, 0));
            left.setOpaque(false);
            left.add(dotLabel, BorderLayout.WEST);
            left.add(nameLabel, BorderLayout.CENTER);

            addrLabel.setFont(addrLabel.getFont().deriveFont(Font.ITALIC, 10f));
            addrLabel.setForeground(Color.GRAY);

            add(left,     BorderLayout.CENTER);
            add(addrLabel, BorderLayout.EAST);
        }

        public Component getListCellRendererComponent(
                JList<? extends ServiceEntry> list,
                ServiceEntry entry, int index, boolean isSelected, boolean cellHasFocus) {

            // Status dot colour
            Status st = statusMap.getOrDefault(entry.getKey(), Status.UNKNOWN);
            dotLabel.setForeground(
                    st == Status.OK    ? new Color(0, 160, 0) :
                    st == Status.ERROR ? Color.RED :
                                        Color.LIGHT_GRAY);

            nameLabel.setText(entry.getName());
            addrLabel.setText(entry.getHost() + ":" + entry.getPort());

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                nameLabel.setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                nameLabel.setForeground(list.getForeground());
            }
            setOpaque(true);
            return this;
        }
    }
}
