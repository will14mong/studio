package studio.ui;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import javax.swing.*;
import javax.swing.event.UndoableEditEvent;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.table.TableModel;
import javax.swing.text.*;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

import kx.c;
import org.netbeans.editor.*;
import org.netbeans.editor.Utilities;
import org.netbeans.editor.ext.ExtKit;
import studio.kdb.*;
import studio.utils.SwingWorker;

import static javax.swing.JSplitPane.VERTICAL_SPLIT;

/**
 * A self-contained editor + results panel that can be hosted as a tab in StudioPanel.
 * Each instance has its own editor pane, result tabs, and server binding.
 */
public class EditorTabPanel extends JPanel {

    // ── UI components ────────────────────────────────────────────────────────
    private JEditorPane textArea;
    private JSplitPane  splitPane;
    private JTabbedPane resultTabs;
    private JTable      table;

    // ── State ─────────────────────────────────────────────────────────────────
    private Server     server;
    private SwingWorker worker;
    private String     lastQuery;
    private int        dividerLastPosition;

    // ── Actions extracted from the editor ────────────────────────────────────
    private ActionFactory.UndoAction undoAction;
    private ActionFactory.RedoAction redoAction;
    private BaseKit.CutAction        cutAction;
    private BaseKit.CopyAction       copyAction;
    private BaseKit.PasteAction      pasteAction;
    private BaseKit.SelectAllAction  selectAllAction;
    private Action findAction;
    private Action replaceAction;

    // ── Callback so StudioPanel can update its toolbar / menu state ───────────
    public interface ExecutionCallback {
        JFrame getFrame();
        void onExecutionStarted();
        void onExecutionFinished(JTable resultTable);
        void onExecutionError();
    }

    private ExecutionCallback callback;

    // ─────────────────────────────────────────────────────────────────────────

    public EditorTabPanel(ExecutionCallback callback) {
        super(new BorderLayout());
        this.callback = callback;
        initEditor();
    }

    // ─────────────────────────── Initialisation ──────────────────────────────

    private void initEditor() {
        // Create the NetBeans Q editor pane
        textArea = new JEditorPane("text/q", "");

        // Extract built-in actions from the editor
        Action[] actions = textArea.getActions();
        int menuShortcutKeyMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();

        for (Action a : actions) {
            if (a instanceof BaseKit.CopyAction) {
                copyAction = (BaseKit.CopyAction) a;
                copyAction.putValue(Action.SHORT_DESCRIPTION, "Copy the selected text to the clipboard");
                copyAction.putValue(Action.SMALL_ICON, Util.COPY_ICON);
                copyAction.putValue(Action.NAME, I18n.getString("Copy"));
                copyAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_C);
                copyAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_C, menuShortcutKeyMask));
            } else if (a instanceof BaseKit.CutAction) {
                cutAction = (BaseKit.CutAction) a;
                cutAction.putValue(Action.SHORT_DESCRIPTION, "Cut the selected text");
                cutAction.putValue(Action.SMALL_ICON, Util.CUT_ICON);
                cutAction.putValue(Action.NAME, I18n.getString("Cut"));
                cutAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_T);
                cutAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_X, menuShortcutKeyMask));
            } else if (a instanceof BaseKit.PasteAction) {
                pasteAction = (BaseKit.PasteAction) a;
                pasteAction.putValue(Action.SHORT_DESCRIPTION, "Paste text from the clipboard");
                pasteAction.putValue(Action.SMALL_ICON, Util.PASTE_ICON);
                pasteAction.putValue(Action.NAME, I18n.getString("Paste"));
                pasteAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_P);
                pasteAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_V, menuShortcutKeyMask));
            } else if (a instanceof ExtKit.FindAction) {
                findAction = a;
                findAction.putValue(Action.SHORT_DESCRIPTION, "Find text in the document");
                findAction.putValue(Action.SMALL_ICON, Util.FIND_ICON);
                findAction.putValue(Action.NAME, I18n.getString("Find"));
                findAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_F);
                findAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_F, menuShortcutKeyMask));
            } else if (a instanceof ExtKit.ReplaceAction) {
                replaceAction = a;
                replaceAction.putValue(Action.SHORT_DESCRIPTION, "Replace text in the document");
                replaceAction.putValue(Action.SMALL_ICON, Util.REPLACE_ICON);
                replaceAction.putValue(Action.NAME, I18n.getString("Replace"));
                replaceAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_R);
                replaceAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_R, menuShortcutKeyMask));
            } else if (a instanceof BaseKit.SelectAllAction) {
                selectAllAction = (BaseKit.SelectAllAction) a;
                selectAllAction.putValue(Action.SHORT_DESCRIPTION, "Select all text in the document");
                selectAllAction.putValue(Action.NAME, I18n.getString("SelectAll"));
                selectAllAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_A);
                selectAllAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_A, menuShortcutKeyMask));
            } else if (a instanceof ActionFactory.UndoAction) {
                undoAction = (ActionFactory.UndoAction) a;
                undoAction.putValue(Action.SHORT_DESCRIPTION, "Undo the last change to the document");
                undoAction.putValue(Action.SMALL_ICON, Util.UNDO_ICON);
                undoAction.putValue(Action.NAME, I18n.getString("Undo"));
                undoAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_U);
                undoAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuShortcutKeyMask));
            } else if (a instanceof ActionFactory.RedoAction) {
                redoAction = (ActionFactory.RedoAction) a;
                redoAction.putValue(Action.SHORT_DESCRIPTION, "Redo the last change to the document");
                redoAction.putValue(Action.SMALL_ICON, Util.REDO_ICON);
                redoAction.putValue(Action.NAME, I18n.getString("Redo"));
                redoAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_R);
                redoAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuShortcutKeyMask));
            }
        }

        // Install UndoManager
        Document doc = textArea.getDocument();
        doc.putProperty("filename", null);
        UndoManager um = new UndoManager() {
            public void undoableEditHappened(UndoableEditEvent e) {
                super.undoableEditHappened(e);
                updateUndoRedoState(this);
            }
            public synchronized void redo() throws CannotRedoException {
                super.redo();
                updateUndoRedoState(this);
            }
            public synchronized void undo() throws CannotUndoException {
                super.undo();
                updateUndoRedoState(this);
            }
        };
        doc.putProperty(BaseDocument.UNDO_MANAGER_PROP, um);
        doc.addUndoableEditListener(um);
        um.discardAllEdits();

        // Editor component (NetBeans wraps it with line numbers etc.)
        JComponent editorComponent = (textArea.getUI() instanceof BaseTextUI)
                ? Utilities.getEditorUI(textArea).getExtComponent()
                : new JScrollPane(textArea);

        // Result tabs (bottom)
        resultTabs = new JTabbedPane();

        // Vertical split: editor top, results bottom
        splitPane = new JSplitPane(VERTICAL_SPLIT, editorComponent, resultTabs);
        splitPane.setOneTouchExpandable(true);
        splitPane.setContinuousLayout(true);
        splitPane.setResizeWeight(0.5);

        // Double-click divider toggles orientation
        try {
            Component divider = ((BasicSplitPaneUI) splitPane.getUI()).getDivider();
            divider.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) toggleOrientation();
                }
            });
        } catch (ClassCastException ignored) {}

        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                new PropertyChangeListener() {
                    public void propertyChange(PropertyChangeEvent pce) {
                        int loc = splitPane.getDividerLocation();
                        if (loc > splitPane.getMinimumDividerLocation()
                                && loc < splitPane.getMaximumDividerLocation()) {
                            dividerLastPosition = loc;
                        }
                    }
                });

        add(splitPane, BorderLayout.CENTER);
    }

    // ──────────────────────────── Public API ─────────────────────────────────

    /** Bind this tab to a discovered service. Updates the editor background. */
    public void bindServer(Server server) {
        this.server = server;
        if (textArea != null) {
            Document doc = textArea.getDocument();
            if (doc != null) doc.putProperty("server", server);
            Utilities.getEditorUI(textArea).getComponent()
                    .setBackground(server.getBackgroundColor());
        }
        if (server != null) new ReloadQKeywords(server);
    }

    public Server getServer() { return server; }

    public JEditorPane getTextArea() { return textArea; }

    public JTabbedPane getResultTabs() { return resultTabs; }

    public JSplitPane getSplitPane() { return splitPane; }

    public JTable getTable() { return table; }

    public boolean isExecuting() { return worker != null; }

    // ── Editor actions ────────────────────────────────────────────────────────

    public ActionFactory.UndoAction getUndoAction()   { return undoAction; }
    public ActionFactory.RedoAction getRedoAction()   { return redoAction; }
    public BaseKit.CutAction        getCutAction()    { return cutAction; }
    public BaseKit.CopyAction       getCopyAction()   { return copyAction; }
    public BaseKit.PasteAction      getPasteAction()  { return pasteAction; }
    public BaseKit.SelectAllAction  getSelectAllAction() { return selectAllAction; }
    public Action getFindAction()    { return findAction; }
    public Action getReplaceAction() { return replaceAction; }

    public String getEditorText() {
        String text = textArea.getSelectedText();
        if (text != null) {
            if (text.trim().isEmpty()) return null;
        } else {
            text = textArea.getText();
        }
        if (text != null) text = text.trim();
        return (text == null || text.isEmpty()) ? null : text;
    }

    public String getCurrentLineText() {
        String newLine = "\n";
        String text = null;
        try {
            int pos = textArea.getCaretPosition();
            int max = textArea.getDocument().getLength();
            if (max > pos && !textArea.getText(pos, 1).equals("\n")) {
                String toEol = textArea.getText(pos, max - pos);
                int eol = toEol.indexOf('\n');
                pos = (eol > 0) ? pos + eol : max;
            }
            text = textArea.getText(0, pos);
            int lrPos = text.lastIndexOf(newLine);
            if (lrPos >= 0) {
                lrPos += newLine.length();
                text = text.substring(lrPos, pos).trim();
            }
        } catch (BadLocationException ignored) {}
        return (text == null || text.trim().isEmpty()) ? null : text.trim();
    }

    // ── Divider helpers ───────────────────────────────────────────────────────

    public void minMaxDivider() {
        if (splitPane.getDividerLocation() >= splitPane.getMaximumDividerLocation()) {
            splitPane.getTopComponent().setMinimumSize(new Dimension());
            splitPane.getBottomComponent().setMinimumSize(null);
            splitPane.setDividerLocation(0.);
            splitPane.setResizeWeight(0.);
        } else if (splitPane.getDividerLocation() <= splitPane.getMinimumDividerLocation()) {
            splitPane.getTopComponent().setMinimumSize(null);
            splitPane.getBottomComponent().setMinimumSize(null);
            splitPane.setResizeWeight(0.);
            if (dividerLastPosition >= splitPane.getMaximumDividerLocation()
                    || dividerLastPosition <= splitPane.getMinimumDividerLocation()) {
                dividerLastPosition = splitPane.getMaximumDividerLocation() / 2;
            }
            splitPane.setDividerLocation(dividerLastPosition);
        } else {
            splitPane.getBottomComponent().setMinimumSize(new Dimension());
            splitPane.getTopComponent().setMinimumSize(null);
            int size = splitPane.getOrientation() == VERTICAL_SPLIT
                    ? splitPane.getHeight() - splitPane.getDividerSize()
                    : splitPane.getWidth() - splitPane.getDividerSize();
            splitPane.setDividerLocation(size);
            splitPane.setResizeWeight(1.);
        }
    }

    public void toggleOrientation() {
        splitPane.setOrientation(splitPane.getOrientation() == JSplitPane.VERTICAL_SPLIT
                ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT);
        splitPane.setDividerLocation(0.5);
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    public void executeQuery() {
        executeQuery(getEditorText());
    }

    public void executeQueryCurrentLine() {
        executeQuery(getCurrentLineText());
    }

    public void refreshQuery() {
        table = null;
        executeK4Query(lastQuery);
    }

    public void stopExecution() {
        if (worker != null) {
            worker.interrupt();
        }
    }

    public void executeQuery(String text) {
        table = null;
        if (text == null) {
            JOptionPane.showMessageDialog(callback.getFrame(),
                    "\nNo text available to submit to server.\n\n",
                    "Studio for kdb+",
                    JOptionPane.OK_OPTION,
                    Util.INFORMATION_ICON);
            return;
        }
        if (server == null) {
            JOptionPane.showMessageDialog(callback.getFrame(),
                    "\nNo server selected. Please click a service in the left panel.\n\n",
                    "Studio for kdb+",
                    JOptionPane.OK_OPTION,
                    Util.INFORMATION_ICON);
            return;
        }
        lastQuery = text;
        executeK4Query(text);
    }

    private void executeK4Query(final String text) {
        if (text == null) return;

        final Cursor cursor = textArea.getCursor();
        textArea.setCursor(new Cursor(Cursor.WAIT_CURSOR));

        if (resultTabs.getTabCount() >= Config.getInstance().getResultTabsCount()) {
            resultTabs.remove(0);
        }

        callback.onExecutionStarted();

        worker = new SwingWorker() {
            Server s = null;
            c conn = null;
            K.KBase r = null;
            Throwable exception;
            boolean cancelled = false;
            long execTime = 0;

            public void interrupt() {
                super.interrupt();
                cancelled = true;
                if (conn != null) conn.close();
                cleanup();
            }

            public Object construct() {
                try {
                    s = server;
                    conn = ConnectionPool.getInstance().leaseConnection(s);
                    ConnectionPool.getInstance().checkConnected(conn);
                    conn.setFrame(callback.getFrame());
                    long startTime = System.currentTimeMillis();
                    conn.k(new K.KCharacterVector(text));
                    r = conn.getResponse();
                    execTime = System.currentTimeMillis() - startTime;
                } catch (Throwable e) {
                    System.err.println("Error during query execution: " + e);
                    e.printStackTrace(System.err);
                    exception = e;
                }
                return null;
            }

            public void finished() {
                if (!cancelled) {
                    if (exception != null) {
                        handleException(exception, s);
                        callback.onExecutionError();
                    } else {
                        try {
                            Utilities.setStatusText(textArea,
                                    "Last execution time:" + (execTime > 0 ? "" + execTime : "<1") + " mS");
                            processResults(r);
                            callback.onExecutionFinished(table);
                        } catch (Exception e) {
                            e.printStackTrace(System.err);
                            JOptionPane.showMessageDialog(callback.getFrame(),
                                    "\nAn unexpected error occurred whilst communicating with "
                                            + s.getHost() + ":" + s.getPort()
                                            + "\n\nError detail is\n\n" + e.getMessage() + "\n\n",
                                    "Studio for kdb+",
                                    JOptionPane.ERROR_MESSAGE,
                                    Util.ERROR_ICON);
                            callback.onExecutionError();
                        }
                    }
                    cleanup();
                }
            }

            private void cleanup() {
                if (conn != null) ConnectionPool.getInstance().freeConnection(s, conn);
                conn = null;
                textArea.setCursor(cursor);
                worker = null;
                System.gc();
            }
        };

        worker.start();
    }

    private void handleException(Throwable exception, Server s) {
        try {
            throw exception;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(callback.getFrame(),
                    "\nA communications error occurred whilst sending the query.\n\n"
                            + "Please check that the server is running on "
                            + s.getHost() + ":" + s.getPort()
                            + "\n\nError detail is\n\n" + ex.getMessage() + "\n\n",
                    "Studio for kdb+",
                    JOptionPane.ERROR_MESSAGE,
                    Util.ERROR_ICON);
        } catch (c.K4Exception ex) {
            JTextPane pane = new JTextPane();
            String hint = QErrors.lookup(ex.getMessage());
            if (hint != null) hint = "\nStudio Hint: Possibly this error refers to " + hint;
            else hint = "";
            pane.setText("An error occurred during execution of the query.\n"
                    + "The server sent the response:\n" + ex.getMessage() + hint);
            pane.setForeground(Color.RED);
            JScrollPane scroll = new JScrollPane(pane);
            TabPanel tp = new TabPanel("Error Details ", Util.ERROR_SMALL_ICON, scroll);
            tp.setTitle("Error Details ");
            resultTabs.addTab(tp.getTitle(), tp.getIcon(), tp.getComponent());
            resultTabs.setSelectedIndex(resultTabs.getTabCount() - 1);
        } catch (OutOfMemoryError ex) {
            JOptionPane.showMessageDialog(callback.getFrame(),
                    "\nOut of memory whilst communicating with "
                            + s.getHost() + ":" + s.getPort()
                            + "\n\nThe result set is probably too large.\n\n"
                            + "Try increasing memory: java -J -Xmx512m\n\n",
                    "Studio for kdb+",
                    JOptionPane.ERROR_MESSAGE,
                    Util.ERROR_ICON);
        } catch (Throwable ex) {
            String message = ex.getMessage();
            if (message == null || message.isEmpty())
                message = "No message. Exception: " + ex.toString();
            JOptionPane.showMessageDialog(callback.getFrame(),
                    "\nAn unexpected error occurred whilst communicating with "
                            + s.getHost() + ":" + s.getPort()
                            + "\n\nError detail is\n\n" + message + "\n\n",
                    "Studio for kdb+",
                    JOptionPane.ERROR_MESSAGE,
                    Util.ERROR_ICON);
        }
    }

    private void processResults(K.KBase r) throws c.K4Exception {
        if (r != null) {
            KTableModel model = KTableModel.getModel(r);
            if (model != null) {
                boolean dictModel  = model instanceof DictModel;
                boolean listModel  = model instanceof studio.kdb.ListModel;
                boolean tableModel = !(dictModel || listModel);
                QGrid grid = new QGrid(model);
                table = grid.getTable();
                String title = tableModel ? "Table" : (dictModel ? "Dict" : "List");
                TabPanel tp = new TabPanel(title + " [" + grid.getRowCount() + " rows] ",
                        Util.TABLE_ICON, grid);
                resultTabs.addTab(tp.getTitle(), tp.getIcon(), tp.getComponent());
            } else {
                table = null;
                LimitedWriter lm = new LimitedWriter(Config.getInstance().getMaxCharsInResult());
                try {
                    if (!(r instanceof K.UnaryPrimitive
                            && 0 == ((K.UnaryPrimitive) r).getPrimitiveAsInt()))
                        r.toString(lm, true);
                } catch (IOException | LimitedWriter.LimitException ignored) {}

                JEditorPane ep = new JEditorPane("text/q", lm.toString());
                ep.setEditable(false);
                TabPanel tp = new TabPanel("Console View ",
                        Util.CONSOLE_ICON,
                        Utilities.getEditorUI(ep).getExtComponent());
                tp.setTitle(I18n.getString("ConsoleView"));
                resultTabs.addTab(tp.getTitle(), tp.getIcon(), tp.getComponent());
            }
        }
        resultTabs.setSelectedIndex(resultTabs.getTabCount() - 1);
    }

    private void updateUndoRedoState(UndoManager um) {
        if (undoAction != null) undoAction.setEnabled(um.canUndo());
        if (redoAction != null) redoAction.setEnabled(um.canRedo());
    }
}
