/*
 * Copyright 2014 OSBI Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.saiku.web.rest.objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Properties;
import java.util.UUID;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.web.rest.util.MondrianLocation;

/**
 * Map from SaikuDatasources to JSON variants.
 */
public class DataSourceMapper {

    private String enabled;
    private String connectionname;
    private String jdbcurl;
    private String schema;
    private String driver;
    private String username;

    /**
     * saiku#1165: the backend datasource password must never be serialised back
     * to a client (it was leaking via GET .../org.saiku.datasources/{id}).
     * WRITE_ONLY omits it from every response while still accepting an inbound
     * value on datasource create/update.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private String connectiontype;
    private String id;
    private String path;
    private String advanced;
    private String security_type;
    private String propertyKey;
    private String csv;

    /** Path to the Ossie YAML file. Only set when {@link #connectiontype} is {@code "OSSIE"}. */
    private String ossieYaml;

    public DataSourceMapper() {}

    public DataSourceMapper(SaikuDatasource ds) {
        // Ossie datasources bypass the Mondrian URL-parsing dance entirely — location is a
        // plain warehouse JDBC URL and the Ossie YAML path rides in the properties bag.
        if (ds.getType() == SaikuDatasource.Type.OSSIE) {
            this.connectiontype = "OSSIE";
            this.connectionname = ds.getName();
            this.jdbcurl = ds.getProperties().getProperty("location");
            this.username = ds.getProperties().getProperty("username");
            this.password = ds.getProperties().getProperty("password");
            this.id = ds.getProperties().getProperty("id");
            this.path = ds.getProperties().getProperty("path");
            this.ossieYaml = ds.getProperties().getProperty("ossieYaml");
            // "schema" holds the Ossie model name for OSSIE datasources — same semantic as
            // Mondrian's catalog/schema field, which the admin UI already exposes.
            this.schema = ds.getProperties().getProperty("schema");
            if (ds.getProperties().containsKey("enabled")) {
                this.enabled = ds.getProperties().getProperty("enabled");
            }
            return;
        }
        if ((!ds.getProperties().containsKey("advanced") && !ds.getProperties().containsKey("csv"))
                || (ds.getProperties().containsKey("advanced")
                        && ds.getProperties().getProperty("advanced").equals("false"))) {
            String location = ds.getProperties().getProperty("location");

            if (ds.getProperties().getProperty("driver").equals("mondrian.olap4j.MondrianOlap4jDriver")) {
                // saiku#1634: parse by key, not by positional ';' split. An inner Jdbc= URL that
                // carries its own ;params (e.g. H2's ;MODE=MySQL) used to shift the Catalog and
                // JdbcDrivers slots — the catalog leaked into 'driver' and a stray param into
                // 'schema'. MondrianLocation anchors on the known keys instead, so ordering and
                // embedded params no longer matter.
                MondrianLocation parsed = MondrianLocation.parse(location);
                this.jdbcurl = parsed.jdbc();
                this.schema = parsed.catalog();
                this.driver = parsed.jdbcDrivers();
                this.connectiontype = "MONDRIAN";
            } else {
                // XMLA: location is `jdbc:xmla:Server=<url>` — split the server URL off the head.
                String[] url = location.split(";", 2)[0].split("=", 2);
                if (url.length > 1) {
                    this.jdbcurl = url[1];
                }
                this.connectiontype = "XMLA";
            }
            this.connectionname = ds.getName();
            this.username = ds.getProperties().getProperty("username");
            this.password = ds.getProperties().getProperty("password");
            this.path = ds.getProperties().getProperty("path");
            this.id = ds.getProperties().getProperty("id");
            if (ds.getProperties().containsKey("schema")) {
                this.schema = ds.getProperties().getProperty("schema");
            }

            if (ds.getProperties().containsKey("security.type")) {
                this.security_type = ds.getProperties().getProperty("security.type");
            }

            if (ds.getProperties().containsKey("propertykey")) {
                this.propertyKey = ds.getProperties().getProperty("propertykey");
            }

        } else if (ds.getProperties().containsKey("csv")) {
            this.csv = "type=" + ds.getType().toString() + "\n";
            this.csv += "name=" + ds.getName() + "\n";
            this.csv += "driver=" + ds.getProperties().getProperty("driver") + "\n";
            this.csv += "location=" + ds.getProperties().getProperty("location") + "\n";
            if (ds.getProperties().containsKey("username")) {
                this.csv += "username=" + ds.getProperties().get("username") + "\n";
            }
            if (ds.getProperties().containsKey("password")) {
                this.csv += "password=" + ds.getProperties().get("password") + "\n";
            }
            if (ds.getProperties().containsKey("security.enabled")) {
                this.csv += "security.enabled=" + ds.getProperties().get("security.enabled") + "\n";
            }
            if (ds.getProperties().containsKey("security.type")) {
                this.csv += "security.type=" + ds.getProperties().get("security.type") + "\n";
            }
            if (ds.getProperties().containsKey("security.mapping")) {
                this.csv += "security.mapping=" + ds.getProperties().get("security.mapping") + "\n";
            }
            if (ds.getProperties().contains("encrypt.password")) {
                this.csv += "encrypt.password=" + ds.getProperties().get("encrypt.password") + "\n";
            }
            this.connectionname = ds.getName();
            this.id = ds.getProperties().getProperty("id");

            if (ds.getProperties().containsKey("enabled")) {
                this.enabled = ds.getProperties().getProperty("enabled");
            }

        } else {
            this.advanced = "type=" + ds.getType().toString() + "\n";
            this.advanced += "name=" + ds.getName() + "\n";
            this.advanced += "driver=" + ds.getProperties().getProperty("driver") + "\n";
            this.advanced += "location=" + ds.getProperties().getProperty("location") + "\n";
            if (ds.getProperties().containsKey("username")) {
                this.advanced += "username=" + ds.getProperties().get("username") + "\n";
            }
            if (ds.getProperties().containsKey("password")) {
                this.advanced += "password=" + ds.getProperties().get("password") + "\n";
            }
            if (ds.getProperties().containsKey("security.enabled")) {
                this.advanced += "security.enabled=" + ds.getProperties().get("security.enabled") + "\n";
            }
            if (ds.getProperties().containsKey("security.type")) {
                this.advanced += "security.type=" + ds.getProperties().get("security.type") + "\n";
            }
            if (ds.getProperties().containsKey("security.mapping")) {
                this.advanced += "security.mapping=" + ds.getProperties().get("security.mapping") + "\n";
            }
            if (ds.getProperties().contains("encrypt.password")) {
                this.advanced += "encrypt.password=" + ds.getProperties().get("encrypt.password") + "\n";
            }
            this.connectionname = ds.getName();
            this.id = ds.getProperties().getProperty("id");
        }
    }

    public SaikuDatasource toSaikuDataSource() {
        Properties props = new Properties();
        // Ossie datasource branch: skip the Mondrian URL wrapping. location=warehouse JDBC URL,
        // schema=Ossie model name, ossieYaml=path to the YAML. The JDBC URL still goes through
        // JdbcUrlValidator to defeat the H2 INIT/RUNSCRIPT/ALIAS RCE class.
        if (connectiontype != null && connectiontype.equals("OSSIE")) {
            if (this.ossieYaml == null || this.ossieYaml.isBlank()) {
                throw new IllegalArgumentException("Ossie datasource requires an ossieYaml path");
            }
            if (this.jdbcurl != null) {
                JdbcUrlValidator.validate(this.jdbcurl);
                props.setProperty("location", this.jdbcurl);
            }
            props.setProperty("ossieYaml", this.ossieYaml);
            if (this.schema != null) {
                props.setProperty("schema", this.schema);
            }
            if (this.username != null) {
                props.setProperty("username", this.username);
            }
            if (this.password != null) {
                props.setProperty("password", this.password);
            }
            if (this.id != null) {
                props.setProperty("id", this.id);
            } else {
                props.setProperty("id", UUID.randomUUID().toString());
            }
            if (this.path != null) {
                props.setProperty("path", this.path);
            }
            props.setProperty("advanced", "false");
            return new SaikuDatasource(this.getConnectionname(), SaikuDatasource.Type.OSSIE, props);
        }
        if (advanced == null && csv == null) {
            String location;
            if (connectiontype.equals("MONDRIAN")) {
                props.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
                location = "jdbc:mondrian:Jdbc=" + jdbcurl + ";Catalog=mondrian://" + schema + ";JdbcDrivers=" + driver;
            } else {
                props.setProperty("driver", "org.olap4j.driver.xmla.XmlaOlap4jDriver");
                location = "jdbc:xmla:Server=" + jdbcurl;
            }

            // Reject admin-supplied JDBC URLs that smuggle H2 INIT/RUNSCRIPT/ALIAS (RCE) before
            // the location ever reaches DriverManager.getConnection. Covers the wrapped jdbcurl.
            JdbcUrlValidator.validate(location);

            props.setProperty("location", location);
            props.setProperty("username", this.username);
            props.setProperty("password", this.password);
            if (this.security_type != null) {
                props.setProperty("security.type", this.security_type);
            }
            if (this.schema != null) {
                props.setProperty("schema", this.schema);
            }
            if (this.path != null) {
                props.setProperty("path", this.path);
            }
            if (this.id != null) {
                props.setProperty("id", this.id);
            } else {
                props.setProperty("id", UUID.randomUUID().toString());
            }
            props.setProperty("advanced", "false");

            if (this.propertyKey != null) {
                props.setProperty("propertyKey", this.propertyKey);
            }

            return new SaikuDatasource(this.getConnectionname(), SaikuDatasource.Type.OLAP, props);
        } else {
            String name = null;
            String[] lines;
            String type;
            if (advanced != null && !advanced.equals("false") && !advanced.equals("")) {
                lines = advanced.split("\\r?\\n");
                type = "advanced";
            } else {
                lines = csv.split("\\r?\\n");
                type = "csv";
            }

            for (String row : lines) {
                if (row.startsWith("name=")) {
                    name = row.substring(5, row.length());
                }
                if (row.startsWith("driver=")) {
                    props.setProperty("driver", row.substring(7, row.length()));
                }
                if (row.startsWith("location=")) {
                    String rawLocation = row.substring(9, row.length());
                    // Same RCE guard for the advanced/csv raw location= line.
                    JdbcUrlValidator.validate(rawLocation);
                    props.setProperty("location", rawLocation);
                }
                if (row.startsWith("username=")) {
                    if (row.length() > 9) {
                        props.setProperty("username", row.substring(9, row.length()));
                    } else {
                        props.setProperty("username", "");
                    }
                }
                if (row.startsWith("password=")) {
                    if (row.length() > 9) {
                        props.setProperty("password", row.substring(9, row.length()));
                    } else {
                        props.setProperty("password", "");
                    }
                }

                if (row.startsWith("security.type=")) {
                    props.setProperty("security.type", row.substring(14, row.length()));
                }
                if (row.startsWith("security.mapping=")) {
                    props.setProperty("security.mapping", row.substring(17, row.length()));
                }
                if (row.startsWith("security.enabled=")) {
                    props.setProperty("security.enabled", row.substring(17, row.length()));
                }
                if (row.startsWith("encrypt.password=")) {
                    props.setProperty("encrypt.password", row.substring(17, row.length()));
                }
                if (this.id != null) {
                    props.setProperty("id", this.id);
                } else {
                    props.setProperty("id", UUID.randomUUID().toString());
                }
                if (row.startsWith("propertyKey=")) {
                    props.setProperty("propertyKey", row.substring(12, row.length()));
                }

                if (row.startsWith("enabled=")) {
                    props.setProperty("enabled", row.substring(8, row.length()));
                }
            }

            props.setProperty(type, "true");

            return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, props);
        }
    }

    public String getConnectionname() {
        return connectionname;
    }

    public void setConnectionname(String connectionname) {
        this.connectionname = connectionname;
    }

    public String getJdbcurl() {
        return jdbcurl;
    }

    public void setJdbcurl(String jdbcurl) {
        this.jdbcurl = jdbcurl;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getConnectiontype() {
        return connectiontype;
    }

    public void setConnectiontype(String connectiontype) {
        this.connectiontype = connectiontype;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getAdvanced() {
        return advanced;
    }

    public void setAdvanced(String advanced) {
        this.advanced = advanced;
    }

    public void setSecurity_type(String security_type) {
        this.security_type = security_type;
    }

    public void setPropertyKey(String propertyKey) {
        this.propertyKey = propertyKey;
    }

    public String getPropertyKey() {
        return propertyKey;
    }

    public String getSecurity_type() {
        return security_type;
    }

    public String getEnabled() {
        return enabled;
    }

    public void setEnabled(String enabled) {
        this.enabled = enabled;
    }

    public String getCsv() {
        return csv;
    }

    public void setCsv(String csv) {
        this.csv = csv;
    }

    public String getOssieYaml() {
        return ossieYaml;
    }

    public void setOssieYaml(String ossieYaml) {
        this.ossieYaml = ossieYaml;
    }
}
