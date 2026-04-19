package bi.meteorite.license;

import java.util.Date;

public class SaikuLicense implements ILicense {
    private static final long serialVersionUID = 1L;

    private String name = "";
    private String email = "";
    private String licenseNumber = "";
    private String licenseType = "community";
    private String version = "";
    private String hostname = "";
    private Date expiration = new Date(Long.MAX_VALUE);
    private int users = Integer.MAX_VALUE;
    private int memory = Integer.MAX_VALUE;

    public SaikuLicense() {}

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String v) {
        this.email = v;
    }

    public String getLicenseNumber() {
        return licenseNumber;
    }

    public void setLicenseNumber(String v) {
        this.licenseNumber = v;
    }

    public String getLicenseType() {
        return licenseType;
    }

    public void setLicenseType(String v) {
        this.licenseType = v;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String v) {
        this.version = v;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String v) {
        this.hostname = v;
    }

    public Date getExpiration() {
        return expiration;
    }

    public void setExpiration(Date v) {
        this.expiration = v;
    }

    public int getUsers() {
        return users;
    }

    public void setUsers(int v) {
        this.users = v;
    }

    public int getMemory() {
        return memory;
    }

    public void setMemory(int v) {
        this.memory = v;
    }

    public void validate(Date currentDate, String currentVersion) throws LicenseException {}

    public void validate(Date currentDate, String currentVersion, boolean a, boolean b, boolean c, boolean d)
            throws LicenseException {}
}
