package studio.kdb;

public class DiscoveryConfig {
    private String host = "";
    private int port = 0;
    private String query = "";
    private String username = "";
    private String password = "";

    public DiscoveryConfig() {}

    public DiscoveryConfig(String host, int port, String query, String username, String password) {
        this.host = host;
        this.port = port;
        this.query = query;
        this.username = username;
        this.password = password;
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isConfigured() {
        return host != null && !host.trim().isEmpty() && port > 0;
    }
}
