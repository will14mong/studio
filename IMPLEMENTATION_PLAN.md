# Discovery Service Integration — Implementation Plan

## Summary of Requirements
- New resizable **left pane** (service browser) alongside the existing editor/results right pane
- **Discovery configuration** in menu (host, port, query)
- Discovery returns `name!(host;port)` dict → populates left pane
- Left pane shows: service name, host:port, connection-status indicator
- **Manual refresh** only (Refresh button)
- **Multiple simultaneous connections** — each editor tab is independently bound to a service
- **Per-service credentials** — prompted on first connect, remembered in-session only
- Existing `ConnectionPool` reuse for caching

---

## New Files

| File | Package | Purpose |
|---|---|---|
| `DiscoveryConfig.java` | `studio.kdb` | POJO holding discovery host/port/query/credentials |
| `ServiceEntry.java` | `studio.kdb` | Represents one discovered service (name, host, port) |
| `EditorTabPanel.java` | `studio.ui` | Self-contained editor + results split-pane (one per tab) |
| `ServiceBrowserPanel.java` | `studio.ui` | Left pane: service list + Refresh button |
| `DiscoveryConfigDialog.java` | `studio.ui` | Dialog to configure discovery host/port/query |
| `CredentialDialog.java` | `studio.ui` | Prompt username/password on first connect |

---

## Modified Files

| File | Changes |
|---|---|
| `Config.java` | Persist `DiscoveryConfig` under `discovery.*` property keys |
| `StudioPanel.java` | New horizontal split layout, editor tabs, Discovery menu, delegate execution to active tab |

---

## Phase 1 — Data Model

### 1.1 `DiscoveryConfig.java` (new)
```java
public class DiscoveryConfig {
    private String host;
    private int    port;
    private String query;      // q expression run against discovery server
    private String username;
    private String password;
    // getters/setters
}
```

### 1.2 `ServiceEntry.java` (new)
```java
public class ServiceEntry {
    private final String name;
    private final String host;
    private final int    port;

    /** Convert to a Server object using the supplied (possibly prompted) credentials */
    public Server toServer(String username, String password) { ... }
}
```

### 1.3 `Config.java` (modified)
Add two methods:
```java
public DiscoveryConfig getDiscoveryConfig()   // reads discovery.* keys from properties
public void setDiscoveryConfig(DiscoveryConfig c)  // writes + calls save()
```
Property keys: `discovery.host`, `discovery.port`, `discovery.query`,
`discovery.username`, `discovery.password`

---

## Phase 2 — `EditorTabPanel.java` (new)

Extract the vertical-split (editor + results) logic out of `StudioPanel` into a
self-contained panel so many instances can live as tabs.

**Key fields:**
```java
private Server            server;          // bound service; null = unbound
private JEditorPane       textArea;        // Q code editor
private JSplitPane        splitPane;       // VERTICAL: editor (top) / results (bottom)
private JTabbedPane       resultTabs;      // result tabs (table, console, etc.)
private UndoManager       undoManager;
private SwingWorker       worker;          // background query execution
```

**Key methods:**
```java
public void bindServer(Server s)           // set (or change) the bound service
public Server getServer()
public void executeQuery(String text)      // runs query against bound server
public void stopExecution()
public String getEditorText()
public String getSelectedText()
```

`executeQuery` mirrors the existing `StudioPanel.executeK4Query`:
- Calls `ConnectionPool.getInstance().leaseConnection(server)`
- Sends the query on a `SwingWorker` thread
- On completion, calls `processResults(r)` to add a result tab
- Frees the connection back to the pool

Tab title in the parent JTabbedPane reflects the bound service:
`<serviceName> @ <host>:<port>` (or `[unbound]` if none yet).

---

## Phase 3 — `CredentialDialog.java` (new)

A simple modal JDialog:
- Username `JTextField`
- Password `JPasswordField`
- OK / Cancel buttons
- Static factory method: `CredentialDialog.prompt(parent, serviceName)` →
  returns `String[]{username, password}` or `null` if cancelled

In-session credential cache: a `HashMap<String, String[]>` keyed by service name
(host+":"+port) stored in `ServiceBrowserPanel`. This is **not** persisted to disk.

---

## Phase 4 — `ServiceBrowserPanel.java` (new)

Left pane panel.

**Layout:**
```
JPanel (BorderLayout)
├── NORTH: toolbar row
│     ├── JLabel "Services"
│     ├── JButton "Refresh"  ← runs discovery query
│     └── JButton "Configure..." ← opens DiscoveryConfigDialog
└── CENTER: JScrollPane → JList<ServiceEntry>
      Custom renderer: colored status dot | service name | host:port
```

**Status dot colours:**
- Grey  = never connected
- Green = connection is alive (last query succeeded)
- Red   = last connection attempt failed

**Refresh flow:**
1. Read `Config.getInstance().getDiscoveryConfig()`
2. If not configured → show message "Please configure discovery first"
3. Build a `Server` from discovery host/port/credentials
4. `ConnectionPool.leaseConnection(discoveryServer)` → `c.k(discoveryQuery)`
5. Parse `K.KDictionary` response:
   - Keys   = `K.KSymbolVector` (service names)
   - Values = `K.KList` where each element is a 2-element list `{K.KSymbol host, K.KInteger port}`
6. Populate `List<ServiceEntry>` → update JList model
7. Free discovery connection

**Click flow (single-click):**
1. Get selected `ServiceEntry`
2. Notify `StudioPanel` via callback: `onServiceSelected(ServiceEntry e)`

---

## Phase 5 — `DiscoveryConfigDialog.java` (new)

Modal dialog opened from the Discovery menu.

**Fields:**
- Discovery Host: `JTextField`
- Discovery Port: `JSpinner` (int)
- Discovery Username: `JTextField`
- Discovery Password: `JPasswordField`
- Discovery Query: `JTextArea` (multi-line, e.g. `getServices[]`)

**Buttons:** Save, Cancel

On Save → `Config.getInstance().setDiscoveryConfig(...)` → close.

---

## Phase 6 — `StudioPanel.java` (modified)

### 6.1 New layout

Replace the current `BorderLayout.CENTER` (which holds a single `JSplitPane`) with:

```
BorderLayout.CENTER: outerSplit (JSplitPane, HORIZONTAL_SPLIT)
├── LEFT:  ServiceBrowserPanel   (preferred width 220px, min 120px)
└── RIGHT: editorTabs (JTabbedPane)
           Each tab: EditorTabPanel instance
```

The outer horizontal split is resizable with one-touch expander (left pane
can be collapsed entirely).

### 6.2 Editor tabs

```java
private JTabbedPane       editorTabs;       // replaces single splitpane/textArea
private EditorTabPanel    activeTab();      // returns (EditorTabPanel) editorTabs.getSelectedComponent()
```

- On launch: one default `EditorTabPanel` is created (unbound, title `[unbound]`)
- A **"+"** button (or right-click > New Tab) adds another unbound tab
- Tabs are closeable (standard Swing close-tab button)
- The `executeAction` / `stopAction` delegate to `activeTab().executeQuery(...)` /
  `activeTab().stopExecution()`

### 6.3 Service selection callback

```java
// Called by ServiceBrowserPanel when user clicks a service
public void onServiceSelected(ServiceEntry entry) {
    // 1. Check session credential cache
    String[] creds = sessionCredentials.get(entry.getKey());
    if (creds == null) {
        creds = CredentialDialog.prompt(frame, entry.getName());
        if (creds == null) return; // user cancelled
        sessionCredentials.put(entry.getKey(), creds);
    }
    Server s = entry.toServer(creds[0], creds[1]);

    // 2. Bind to currently selected (or new) editor tab
    EditorTabPanel tab = activeTab();
    if (tab.getServer() != null) {
        // Active tab already bound → open a new tab
        tab = new EditorTabPanel();
        editorTabs.addTab("...", tab);
        editorTabs.setSelectedComponent(tab);
    }
    tab.bindServer(s);
    // Update tab title
    editorTabs.setTitleAt(editorTabs.getSelectedIndex(),
        entry.getName() + " @ " + entry.getHost() + ":" + entry.getPort());
}
```

### 6.4 Menu change — add Discovery menu

New top-level menu **"Discovery"** (added after "Server" menu):

```
Discovery
├── Configure Discovery...    → opens DiscoveryConfigDialog
└── Refresh Services          → calls serviceBrowserPanel.refresh()
```

### 6.5 Fields to keep / remove

- **Keep**: all existing `UserAction`s (file, edit, export, chart, settings, about, server list)
- **Remove**: `private Server server` (moved into `EditorTabPanel`)
- **Remove**: `private JEditorPane textArea`, `private JSplitPane splitpane`,
  `private JTabbedPane tabbedPane` (moved into `EditorTabPanel`)
- **Add**: `private ServiceBrowserPanel serviceBrowserPanel`
- **Add**: `private JTabbedPane editorTabs`
- **Add**: `private Map<String, String[]> sessionCredentials = new HashMap<>()`

---

## Phase 7 — ConnectionPool changes (none required)

The existing `ConnectionPool` already satisfies caching requirements:
- `leaseConnection(Server s)` reuses open connections from `freeMap`
- `freeConnection(Server s, kx.c c)` returns connections for reuse
- `Server.equals()` is name-based → service name used as cache key
- TLS and auth mechanism fields on Server objects from discovery will default
  to no-TLS and default auth mechanism (matching most kdb+ services)

No changes needed to `ConnectionPool.java`.

---

## Implementation Order

1. `DiscoveryConfig.java` + `ServiceEntry.java` (pure POJOs, no dependencies)
2. `Config.java` — add `getDiscoveryConfig` / `setDiscoveryConfig`
3. `EditorTabPanel.java` — extract editor+results from StudioPanel logic
4. `CredentialDialog.java` — simple dialog
5. `DiscoveryConfigDialog.java` — simple dialog
6. `ServiceBrowserPanel.java` — depends on DiscoveryConfig, ServiceEntry, ConnectionPool
7. `StudioPanel.java` — wire everything together (layout + menu + callbacks)

---

## Risk / Notes

- `StudioPanel` is large (~1,000+ lines). The `EditorTabPanel` extraction is the
  highest-risk step. Recommend incremental commits per phase.
- `Server.equals()` uses `name` field. Service names returned by discovery must be
  unique and stable across refreshes for connection reuse to work correctly.
- If a discovered service restarts on the same host:port, `ConnectionPool.freeConnection`
  will detect the closed socket and discard it; the next `leaseConnection` will open
  a fresh connection automatically — no extra handling required.
- The discovery server's own connection is also pooled (reused for subsequent Refresh
  operations).
