package org.saiku.service.importer;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.jcr.RepositoryException;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.VFS;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.repository.IRepositoryManager;
import org.saiku.service.datasource.IDatasourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by bugg on 19/06/14.
 */
public class LegacyImporterImpl implements LegacyImporter {
    private static final Logger log = LoggerFactory.getLogger(LegacyImporter.class);
    private final IDatasourceManager dsm;

    private URL repoURL;

    public LegacyImporterImpl(IDatasourceManager dsm) {
        this.dsm = dsm;
    }

    public void importUsers() {
        setPath("res:");
    }

    public void importSchema() {
        setPath("res:legacy-schema");

        try {
            if (repoURL != null) {
                File[] files = new File(repoURL.getFile()).listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!file.isHidden() && !file.getName().equals("README")) {
                            byte[] encoded = null;
                            try {
                                encoded = org.apache.commons.io.IOUtils.toByteArray(new FileInputStream(file));
                            } catch (IOException e1) {
                                e1.printStackTrace();
                            }
                            String str = null;
                            if (encoded != null) {
                                str = new String(encoded, Charset.forName("UTF8"));
                            }
                            dsm.addSchema(str, "/datasources/" + file.getName(), "admin");
                        }
                    }
                }
            }
        } catch (Exception e1) {
            e1.printStackTrace();
        }
    }

    public void importDatasources() {
        setPath("res:legacy-datasources");

        try {
            if (repoURL != null) {
                File[] files = new File(repoURL.getFile()).listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!file.isHidden() && !file.getName().equals("README")) {
                            Properties props = new Properties();
                            try {
                                props.load(new FileInputStream(file));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            String name = props.getProperty("name");
                            String type = props.getProperty("type");
                            if (props.getProperty("location") != null) {
                                String toSplit = props.getProperty("location");
                                String split[] = toSplit.split(";");

                                for (int i = 0; i < split.length; i++) {
                                    String s = split[i];
                                    if (s.startsWith("Catalog=")) {
                                        Path p = Paths.get(s.substring(8, s.length()));
                                        String f = p.getFileName().toString();

                                        String fixedString = "Catalog=mondrian:///datasources/" + f;

                                        split[i] = fixedString;
                                        StringBuilder builder = new StringBuilder();
                                        for (String str : split) {
                                            builder.append(str).append(";");
                                        }
                                        props.setProperty("location", builder.toString());
                                    }
                                }
                            }
                            if (name != null && type != null) {
                                props.put("id", java.util.UUID.randomUUID().toString());
                                props.put("advanced", "true");

                                SaikuDatasource.Type t = SaikuDatasource.Type.valueOf(type.toUpperCase());
                                SaikuDatasource ds = new SaikuDatasource(name, t, props);

                                dsm.addDatasource(ds);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setPath(String path) {

        FileSystemManager fileSystemManager;
        try {
            fileSystemManager = VFS.getManager();

            FileObject fileObject;
            fileObject = fileSystemManager.resolveFile(path);
            if (fileObject == null) {
                throw new IOException("File cannot be resolved: " + path);
            }
            if (!fileObject.exists()) {
                throw new IOException("File does not exist: " + path);
            }
            repoURL = fileObject.getURL();
            if (repoURL == null) {
                throw new Exception("Cannot load connection repository from path: " + path);
            } else {
                // load();
            }
        } catch (Exception e) {
            log.error("Exception", e);
        }
    }

    public void importLegacyReports(IRepositoryManager repositoryManager, byte[] file) {

        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(file));
        // get the zipped file list entry
        ZipEntry ze = null;
        try {
            ze = zis.getNextEntry();
        } catch (IOException e) {
            e.printStackTrace();
        }
        String strUnzipped = "";
        while (ze != null) {

            String fileName = ze.getName();
            int size;
            byte[] buffer = new byte[2048];

            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(fileName);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                while ((size = zis.read(buffer, 0, buffer.length)) != -1) {
                    bos.write(buffer, 0, size);
                }
                bos.flush();
                bos.close();
                strUnzipped = new String(bos.toByteArray(), "UTF-8");
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                repositoryManager.saveInternalFile(strUnzipped, "/etc/legacyreports/" + fileName, "nt:saikufiles");
            } catch (RepositoryException e) {
                e.printStackTrace();
            }

            try {
                ze = zis.getNextEntry();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            zis.closeEntry();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            zis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<JujuSource> importJujuDatasources() {
        setPath("res:juju-datasources");

        List<JujuSource> sources = new ArrayList<JujuSource>();
        try {
            if (repoURL != null) {
                File[] files = new File(repoURL.getFile()).listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!file.isHidden() && !file.getName().equals("README")) {
                            Properties props = new Properties();
                            try {
                                props.load(new FileInputStream(file));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            String name = props.getProperty("name");
                            String jdbcurl = props.getProperty("jdbcurl");
                            String username = props.getProperty("username");
                            String password = props.getProperty("password");
                            String driver = props.getProperty("driver");

                            JujuSource s = new JujuSource(name, jdbcurl, username, password, driver);
                            sources.add(s);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sources;
    }
}
