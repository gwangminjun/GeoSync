package geomex.sync.configuration;

public class TargetDb {
    private String name = "";
    private String host = "";
    private int port = 5432;
    private String dbname = "";
    private String username = "";
    private String password = "";
    private boolean enabled = true;

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + dbname;
    }

    public String getName()     { return name; }
    public void setName(String v)     { this.name = v; }
    public String getHost()     { return host; }
    public void setHost(String v)     { this.host = v; }
    public int getPort()        { return port; }
    public void setPort(int v)        { this.port = v; }
    public String getDbname()   { return dbname; }
    public void setDbname(String v)   { this.dbname = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { this.password = v; }
    public boolean isEnabled()  { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
}
