package studio.kdb;

import java.awt.Color;

/**
 * Represents a service discovered via the discovery server.
 * Discovery response format: name!(host;port) — a kdb+ dict keyed by service name.
 */
public class ServiceEntry {
    private final String name;
    private final String host;
    private final int port;

    public ServiceEntry(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
    }

    public String getName() { return name; }
    public String getHost() { return host; }
    public int getPort() { return port; }

    /** Unique key used for credential caching: host:port */
    public String getKey() { return host + ":" + port; }

    /** Convert to a Server object using the supplied credentials. */
    public Server toServer(String username, String password) {
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
