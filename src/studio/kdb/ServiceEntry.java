package studio.kdb;

import java.awt.Color;

/**
 * Represents a service discovered via the discovery server, or the parent server itself.
 * Discovery response format: name!(host;port) — a kdb+ dict keyed by service name.
 *
 * When created with a Server reference (e.g., the parent server entry or a pre-resolved
 * discovery result), the Server is reused directly to enable ConnectionPool reuse.
 */
public class ServiceEntry {
    private final String name;
    private final String host;
    private final int port;
    /** Pre-resolved Server, if available. Set once credentials are known. */
    private Server server;

    /** Constructor for discovery results (no pre-resolved Server). */
    public ServiceEntry(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
    }

    /** Constructor for the parent server entry — stores the Server directly for connection reuse. */
    public ServiceEntry(String name, String host, int port, Server server) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.server = server;
    }

    public String getName() { return name; }
    public String getHost() { return host; }
    public int getPort() { return port; }

    /** Unique key used for credential caching: host:port */
    public String getKey() { return host + ":" + port; }

    /** Returns the pre-resolved Server, or null if credentials have not been gathered yet. */
    public Server getServer() { return server; }

    /** Stores the resolved Server for future reuse (called after credential prompt). */
    public void setServer(Server server) { this.server = server; }

    /**
     * Returns the pre-resolved Server if available, otherwise creates one from the
     * supplied credentials. Use setServer() to cache the result for connection reuse.
     */
    public Server toServer(String username, String password) {
        if (server != null) return server;
        return new Server(
            name,
            host,
            port,
            username != null ? username : "",
            password != null ? password : "",
            Color.white,
            Config.getInstance().getDefaultAuthMechanism(),
            false
        );
    }

    @Override
    public String toString() {
        return name + "  (" + host + ":" + port + ")";
    }
}
