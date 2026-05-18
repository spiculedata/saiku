/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.datasources.connection.RepositoryFile;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.service.util.xml.SecureXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classpath Repository Manager for Saiku.
 */
public class FilesystemRepositoryManager implements IRepositoryManager {
    private static final String ORBIS_WORKSPACE_DIR = "workspace";
    private static final Logger log = LoggerFactory.getLogger(FilesystemRepositoryManager.class);

    private static FilesystemRepositoryManager ref;
    private final String defaultRole;
    private final boolean workspaces;
    private UserService userService;
    private String append;
    private String session = null;
    private boolean bootstrapping;

    private String sep = "/";
    private ScopedRepo sessionRegistry;

    private FilesystemRepositoryManager(
            String data, String defaultRole, ScopedRepo sessionRegistry, boolean workspaces) {

        log.info("Path is " + data);
        this.append = cleanse(data);
        log.info("Path is now" + data);
        this.defaultRole = defaultRole;
        this.sessionRegistry = sessionRegistry;
        this.workspaces = workspaces;
    }

    public static synchronized FilesystemRepositoryManager getFilesystemRepositoryManager(
            String data, String defaultRole, ScopedRepo sessionRegistry, boolean workspaces) {
        if (ref == null)
            // it's ok, we can call this constructor
            ref = new FilesystemRepositoryManager(data, defaultRole, sessionRegistry, workspaces);
        return ref;
    }

    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
        // that'll teach 'em
    }

    public void init() {}

    public boolean start(UserService userService) throws RepositoryException {
        this.userService = userService;

        if (session == null) {
            File f = new File(this.append, "unknown");
            File f2 = new File(this.append, "etc");

            if (!f.exists()) {
                f.mkdir();
            }

            if (!f2.exists()) {
                f2.mkdir();
            }

            File n = this.createFolder(sep + "homes");

            HashMap<String, List<AclMethod>> m = new HashMap<>();
            ArrayList<AclMethod> l = new ArrayList<>();
            l.add(AclMethod.READ);
            m.put(defaultRole, l);
            AclEntry e = new AclEntry("admin", AclType.SECURED, m, null);

            Acl2 acl2 = new Acl2(n);
            acl2.addEntry(n.getPath(), e);
            acl2.serialize(n);

            this.createFolder(sep + "datasources");

            m = new HashMap<>();
            l = new ArrayList<>();
            l.add(AclMethod.WRITE);
            l.add(AclMethod.READ);
            l.add(AclMethod.GRANT);
            m.put("ROLE_ADMIN", l);
            e = new AclEntry("admin", AclType.PUBLIC, m, null);

            acl2 = new Acl2(n);
            acl2.addEntry(n.getPath(), e);
            acl2.serialize(n);

            this.createFolder(sep + "etc");
            if (new File(append, "etc/license.lic").exists()) {
                try {
                    FileUtils.copyFile(new File(append, "etc/license.lic"), this.createNode("/etc/license.lic"));
                } catch (IOException e1) {
                    log.debug("Failed to find license 1");
                    try {
                        FileUtils.copyFile(
                                new File(append, "unknown/etc/license.lic"), this.createNode("/etc/license.lic"));
                    } catch (IOException e2) {
                        log.debug("failed to find any licenses. Giving up");
                    }
                }
            }

            this.createFolder(sep + "legacyreports");

            acl2 = new Acl2(n);
            acl2.addEntry(n.getPath(), e);
            acl2.serialize(n);

            this.createFolder(sep + "etc" + sep + "theme");

            acl2 = new Acl2(n);
            acl2.addEntry(n.getPath(), e);
            acl2.serialize(n);

            log.info("node added");
            this.session = "init";
        } else {
            File n = this.createFolder(sep + "homes");

            HashMap<String, List<AclMethod>> m = new HashMap<>();
            ArrayList<AclMethod> l = new ArrayList<>();
            l.add(AclMethod.READ);
            m.put(defaultRole, l);
            AclEntry e = new AclEntry("admin", AclType.SECURED, m, null);

            Acl2 acl2 = new Acl2(n);
            acl2.addEntry(n.getPath(), e);
            acl2.serialize(n);

            this.createFolder(sep + "datasources");

            m = new HashMap<>();
            l = new ArrayList<>();
            l.add(AclMethod.WRITE);
            l.add(AclMethod.READ);
            l.add(AclMethod.GRANT);
            m.put("ROLE_ADMIN", l);
            e = new AclEntry("admin", AclType.PUBLIC, m, null);

            acl2 = new Acl2(n);
            acl2.addEntry(n.getPath(), e);
            acl2.serialize(n);

            this.createFolder(sep + "etc");

            this.createFolder(sep + "etc" + sep + "theme");

            acl2 = new Acl2(n);
            acl2.addEntry(n.getPath(), e);
            acl2.serialize(n);
        }

        return true;
    }

    public void createUser(String u) throws RepositoryException {

        File node = this.createFolder(sep + "homes" + sep + u);

        AclEntry e = new AclEntry(u, AclType.PRIVATE, null, null);

        Acl2 acl2 = new Acl2(node);
        acl2.addEntry(node.getPath(), e);
        acl2.serialize(node);
    }

    public Object getHomeFolders() throws RepositoryException {

        return this.getAllFoldersInCurrentDirectory(sep + "homes");
    }

    public Object getHomeFolder(String path) throws RepositoryException {
        return this.getAllFoldersInCurrentDirectory("home:" + path);
    }

    public Object getFolder(String user, String directory) throws RepositoryException {
        return this.getAllFoldersInCurrentDirectory(sep + "homes" + sep + "home:" + user + sep + directory);
    }

    private Object getFolderNode(String directory) throws RepositoryException {
        if (directory.startsWith(sep)) {
            directory = directory.substring(1, directory.length());
        }
        return this.getAllFoldersInCurrentDirectory(directory);
    }

    public void shutdown() {}

    public boolean createFolder(String username, String folder) throws RepositoryException {
        this.createFolder(folder);

        return true;
    }

    public boolean deleteFolder(String folder) throws RepositoryException {
        if (folder.startsWith(sep)) {
            folder = folder.substring(1, folder.length());
        }

        this.delete(folder);
        return true;
    }

    public void deleteRepository() throws RepositoryException {}

    public boolean moveFolder(String user, String folder, String source, String target) throws RepositoryException {
        return false;
    }

    public Object saveFile(Object file, String path, String user, String type, List<String> roles)
            throws RepositoryException {
        if (file == null) {
            // Create new folder
            String parent;
            if (path.contains(sep)) {
                parent = path.substring(0, path.lastIndexOf(sep));
            } else {
                parent = sep;
            }
            File node = getFolder(parent);
            Acl2 acl2 = new Acl2(node);
            acl2.setAdminRoles(userService.getAdminRoles());
            if (acl2.canWrite(node, user, roles)) {
                throw new SaikuServiceException("Can't write to file or folder");
            }

            int pos = path.lastIndexOf(sep);
            String filename = "." + sep + path.substring(pos + 1, path.length());
            this.createFolder(filename);
            return null;

        } else {
            int pos = path.lastIndexOf(sep);
            String filename = "." + sep + path.substring(pos + 1, path.length());
            File n = getFolder(path.substring(0, pos));
            Acl2 acl2 = new Acl2(n);
            acl2.setAdminRoles(userService.getAdminRoles());

            File check = this.getNode(filename);
            if (check.exists()) {
                check.delete();
            }

            File resNode = this.createNode(path);

            FileWriter fileWriter;

            try {
                if (resNode.getParentFile() != null && !resNode.getParentFile().exists()) {
                    resNode.getParentFile().mkdirs();
                }

                fileWriter = new FileWriter(resNode);

                fileWriter.write((String) file);
                fileWriter.flush();
                fileWriter.close();
            } catch (IOException e) {
                log.error("Failed to write file to {}", path, e);
            }

            return resNode;
        }
    }

    public void removeFile(String path, String user, List<String> roles) throws RepositoryException {
        File node = getFolder(path);
        Acl2 acl2 = new Acl2(node);
        acl2.setAdminRoles(userService.getAdminRoles());
        if (!acl2.canRead(node, user, roles)) {
            // TODO Throw exception
            throw new RepositoryException();
        }

        this.getNode(path).delete();
    }

    public void moveFile(String source, String target, String user, List<String> roles) throws RepositoryException {}

    public Object saveInternalFile(Object file, String path, String type) throws RepositoryException {
        File f = null;
        if (file == null) {
            int pos = path.lastIndexOf(sep);
            String filename = "." + sep + path.substring(pos + 1, path.length());
            this.createFolder(filename);

            return null;

        } else {

            String filename = path;

            if (filename.equals("/etc/license.lic")) {
                File check = new File(append + filename);
                if (check.exists()) {
                    check.delete();
                }
                f = new File(append + filename);
            } else {
                File check = this.getNode(filename);
                if (check.exists()) {
                    check.delete();
                }

                f = this.createNode(filename);
            }
            FileWriter fileWriter;
            try {
                fileWriter = new FileWriter(f);

                fileWriter.write((String) file);
                fileWriter.flush();
                fileWriter.close();
            } catch (IOException e) {
                log.error("Failed to write internal file {}", path, e);
            }

            return f;
        }
    }

    public Object saveBinaryInternalFile(InputStream file, String path, String type) throws RepositoryException {
        if (file == null) {
            // No content: create the directory node at the user-supplied path
            // (createNode resolves the path inside the datadir for us).
            return this.createNode(path);
        }
        // Resolve the target inside the repo *first* and write to it. The legacy
        // code used `"./" + basename` and `new FileOutputStream(filename)` which
        // wrote the bytes to the JVM working directory and silently lost the
        // intended directory structure (saiku#780 follow-up MEDIUM-1).
        File resNode = this.createNode(path);
        if (resNode.getParentFile() != null && !resNode.getParentFile().exists()) {
            resNode.getParentFile().mkdirs();
        }
        if (resNode.exists()) {
            resNode.delete();
        }
        log.debug("Saving binary file to {}", resNode);

        byte[] bytes = new byte[1024];
        try (FileOutputStream outputStream = new FileOutputStream(resNode)) {
            int read;
            while ((read = file.read(bytes)) != -1) {
                outputStream.write(bytes, 0, read);
            }
        } catch (IOException e) {
            log.error("Failed to write binary file to {}", resNode, e);
        }
        return resNode;
    }

    public String getFile(String s, String username, List<String> roles) throws RepositoryException {
        File node = getFolder(s);
        Acl2 acl2 = new Acl2(node);
        acl2.setAdminRoles(userService.getAdminRoles());
        if (!acl2.canRead(node, username, roles)) {
            // TODO Throw exception
            throw new RepositoryException();
        }

        Path resolved = resolveWithinDatadir(s);
        byte[] encoded = new byte[0];
        try {
            encoded = Files.readAllBytes(resolved);
        } catch (IOException e) {
            log.debug("Missing file", e);
        }

        try {
            return new String(encoded, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.debug("Couldn't convert file", e);
        }
        return null;
    }

    public String getInternalFile(String s) throws RepositoryException {
        byte[] encoded = new byte[0];
        if (!s.equals("/etc/license.lic")) {
            Path resolved = resolveWithinDatadir(s);
            try {
                encoded = Files.readAllBytes(resolved);
            } catch (IOException e) {
                log.debug("Missing file", e);
            }
        } else {
            try {
                encoded = Files.readAllBytes(Paths.get(append + s));
            } catch (IOException e) {
                log.debug("Missing file", e);
            }
        }
        try {
            return new String(encoded, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.debug("Couldn't convert file", e);
        }
        return null;
    }

    public InputStream getBinaryInternalFile(String s) throws RepositoryException {
        Path path = resolveWithinDatadir(s);
        try {
            byte[] f = Files.readAllBytes(path);
            return new ByteArrayInputStream(f);
        } catch (IOException e) {
            log.debug("Missing binary file", e);
        }
        return null;
    }

    public void removeInternalFile(String s) throws RepositoryException {
        this.getNode(s).delete();
    }

    public List<MondrianSchema> getAllSchema() throws RepositoryException {

        String[] extensions = new String[1];
        extensions[0] = "xml";

        String datadir = getDatadir();
        File testFile = new File(datadir);

        if (!testFile.exists()) {
            testFile.mkdirs();
        }

        Collection<File> files = FileUtils.listFiles(new File(datadir + "datasources"), extensions, true);
        List<MondrianSchema> schema = new ArrayList<>();

        for (File file : files) {
            try {
                Scanner scanner = new Scanner(file);

                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.contains("<Schema")) {
                        MondrianSchema ms = new MondrianSchema();
                        ms.setName(file.getName());

                        ms.setPath(file.getPath()
                                .substring(
                                        this.getDatadir().length() - 2,
                                        file.getPath().length()));
                        schema.add(ms);
                        break;
                    }
                }
            } catch (FileNotFoundException e) {
                // handle this
            }
        }

        return schema;
    }

    public List<IRepositoryObject> getAllFiles(List<String> type, String username, List<String> roles) {
        try {
            return getRepoObjects(this.getFolder("/"), type, username, roles, false);
        } catch (Exception e) {
            log.error("Failed to list repo objects at root", e);
        }
        return null;
    }

    public List<IRepositoryObject> getAllFiles(List<String> type, String username, List<String> roles, String path)
            throws RepositoryException {

        File file = this.getNode(path);
        if (file.exists()) {
            try {
                return getRepoObjects(this.getFolder(path), type, username, roles, true);
            } catch (Exception e) {
                log.error("Failed to list repo objects under {}", path, e);
            }
        }

        return null;
    }

    public void deleteFile(String datasourcePath) {
        File n;
        try {
            n = getFolder(fixPath(datasourcePath));
            n.delete();

        } catch (RepositoryException e) {
            log.error("Could not remove file " + datasourcePath, e);
        }
    }

    private AclEntry getAclObj(String path) {
        File node = null;
        try {
            node = (File) getFolderNode(path);
        } catch (RepositoryException e) {
            log.error("Could not get file", e);
        }
        Acl2 acl2 = new Acl2(node);
        acl2.setAdminRoles(userService.getAdminRoles());
        AclEntry entry = acl2.getEntry(path);
        if (entry == null) entry = new AclEntry();
        return entry;
    }

    public AclEntry getACL(String object, String username, List<String> roles) {
        File node = null;
        try {
            node = (File) getFolderNode(object);
        } catch (RepositoryException e) {
            log.error("Could not get file/folder", e);
        }
        Acl2 acl2 = new Acl2(node);
        acl2.setAdminRoles(userService.getAdminRoles());

        if (acl2.canGrant(node, username, roles)) {
            return getAclObj(object);
        }

        return null;
    }

    public void setACL(String object, String acl, String username, List<String> roles) throws RepositoryException {

        ObjectMapper mapper = new ObjectMapper();
        log.debug("Set ACL to " + object + " : " + acl);
        AclEntry ae = null;
        try {
            ae = mapper.readValue(acl, AclEntry.class);
        } catch (IOException e) {
            log.error("Could not read ACL blob", e);
        }

        File node = null;
        try {
            node = (File) getFolderNode(object);
        } catch (RepositoryException e) {
            log.error("Could not get file/folder " + object, e);
        }

        Acl2 acl2 = new Acl2(node);
        acl2.setAdminRoles(userService.getAdminRoles());

        if (acl2.canGrant(node, username, roles)) {
            if (node != null) {
                acl2.addEntry(object, ae);
                acl2.serialize(node);
            }
        }
    }

    public List<MondrianSchema> getInternalFilesOfFileType(String type) throws RepositoryException {
        List<MondrianSchema> ds = new ArrayList<>();

        String[] extensions = new String[1];
        extensions[0] = "xml";
        Collection<File> files = FileUtils.listFiles(new File(getDatadir()), extensions, true);

        for (File file : files) {

            String p = file.getPath();

            MondrianSchema m = new MondrianSchema();
            m.setName(file.getName());
            m.setPath(p);
            m.setType(type);

            ds.add(m);
        }

        return ds;
    }

    @Override
    public void createFileMixin(String type) throws RepositoryException {}

    public List<DataSource> getAllDataSources() throws RepositoryException {

        List<DataSource> ds = new ArrayList<>();

        String[] extensions = new String[1];
        extensions[0] = "sds";
        // Workspace scoping: getDatadir() resolves to the current request's
        // workspace subdir (e.g. <append>/<workspace>/) when workspaces=true
        // and a session.workspace attribute is set; otherwise it falls back
        // to <append>/unknown/ — the legacy single-tenant behaviour.
        //
        // Previously this listed `new File(append)` which is the ROOT of
        // all workspaces, recursively. That made `workspaces=true` cosmetic
        // for datasource discovery — every tenant saw every other tenant's
        // SDS files. The fix is a one-line swap to the same path resolver
        // every other read/write method on this class already uses.
        Collection<File> files = FileUtils.listFiles(new File(getDatadir()), extensions, true);

        for (File file : files) {
            JAXBContext jaxbContext = null;
            try {
                jaxbContext = JAXBContext.newInstance(DataSource.class);
            } catch (JAXBException e) {
                log.error("Could not read XML", e);
            }
            DataSource d = null;
            if (jaxbContext != null) {
                try (InputStream stream = FileUtils.openInputStream(file)) {
                    // XXE-hardened: route the InputStream through a SAX parser with DOCTYPE
                    // declarations and external entities disabled (see SecureXml).
                    d = (DataSource) SecureXml.secureUnmarshal(jaxbContext, stream);
                } catch (Exception e) {
                    log.error("Could not read XML from {}", file, e);
                }
            }

            if (d != null) {
                d.setPath(file.getPath());
                if (file.getParentFile().isDirectory()) {
                    String p = file.getParent();
                    p = p.replace("\\", "/");
                    String[] s = p.split("/");

                    log.debug("p split: " + p);
                    String[] t = append.split("/");

                    if (!workspaces && !s[s.length - 2].equals(t[t.length - 1])) {
                        d.setName(s[s.length - 2] + "_" + (d != null ? d.getName() : ""));
                    }
                }

                ds.add(d);
            }
        }

        return ds;
    }

    public void saveDataSource(DataSource ds, String path, String user) throws RepositoryException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(DataSource.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

            // output pretty printed
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

            jaxbMarshaller.marshal(ds, baos);

        } catch (JAXBException e) {
            log.error("Could not read XML", e);
        }

        int pos = path.lastIndexOf(sep);
        // File n = getFolder(path.substring(0, pos));
        File f = this.createNode(path);
        try {
            FileWriter fileWriter = new FileWriter(f);

            fileWriter.write(baos.toString());
            fileWriter.flush();
            fileWriter.close();
        } catch (IOException e) {
            log.error("Failed to save datasource file", e);
        }
    }

    public byte[] exportRepository() throws RepositoryException, IOException {
        return null;
    }

    public void restoreRepository(byte[] xml) throws RepositoryException, IOException {}

    public RepositoryFile getFile(String fileUrl) {
        fileUrl = fixPath(fileUrl);

        File n = null;
        try {
            n = getFolder(fileUrl);
        } catch (RepositoryException e) {
            log.error("Failed to resolve folder {}", fileUrl, e);
        }

        return new RepositoryFile(n != null ? n.getName() : null, null, null, fileUrl);
    }

    public Object getRepository() {
        return null;
    }

    public void setRepository(Object repository) {
        // this.repository = repository;
    }

    public Object getRepositoryObject() {
        return null;
    }

    private List<IRepositoryObject> getRepoObjects(
            File root, List<String> fileType, String username, List<String> roles, boolean includeparent)
            throws Exception {
        List<IRepositoryObject> repoObjects = new ArrayList<IRepositoryObject>();
        ArrayList<File> objects = new ArrayList<>();

        if (root.isDirectory()) {
            this.listf(root.getAbsolutePath(), objects);

        } else {
            objects = new ArrayList<>();
            objects.add(root);
        }

        Acl2 acl = new Acl2(root);
        acl.setAdminRoles(userService.getAdminRoles());

        for (File file : objects) {
            try {
                if (!file.isHidden()) {
                    String filename = file.getName();
                    String relativePath = file.getPath();
                    String datadir = getDatadir();

                    if (relativePath.startsWith(datadir) && datadir.length() >= 0) { // If we have an absolute path
                        relativePath = relativePath.substring(
                                datadir.length(), relativePath.length()); // Make it relative to the datadir
                    }

                    relativePath = relativePath.replace("\\", "/");

                    if (acl.canRead(relativePath, username, roles)) {
                        List<AclMethod> acls = acl.getMethods(new File(relativePath), username, roles);

                        if (file.isFile()) {
                            if (!fileType.isEmpty()) {
                                for (String ft : fileType) {
                                    if (!filename.endsWith(ft)) {
                                        continue;
                                    }

                                    String extension = FilenameUtils.getExtension(file.getPath());
                                    repoObjects.add(new RepositoryFileObject(
                                            filename, "#" + relativePath, extension, relativePath, acls));
                                }
                            }
                        }

                        if (file.isDirectory()) {
                            repoObjects.add(new RepositoryFolderObject(
                                    filename,
                                    "#" + relativePath,
                                    relativePath,
                                    acls,
                                    getRepoObjects(file, fileType, username, roles, false)));
                        }
                    }
                }
            } catch (Exception ex) {
                // If a problem happens when handling one file, it will still return the repoObjects list
                log.warn("Skipping unreadable repo entry", ex);
            }
        }

        // Just after it has filled the repoObjects, sort it alphabetically, putting the directories first
        Collections.sort(repoObjects, new Comparator<IRepositoryObject>() {
            public int compare(IRepositoryObject o1, IRepositoryObject o2) {
                if (o1.getType().equals(IRepositoryObject.Type.FOLDER)
                        && o2.getType().equals(IRepositoryObject.Type.FILE)) return -1;
                if (o1.getType().equals(IRepositoryObject.Type.FILE)
                        && o2.getType().equals(IRepositoryObject.Type.FOLDER)) return 1;
                return o1.getName().toLowerCase().compareTo(o2.getName().toLowerCase());
            }
        });

        return repoObjects;
    }

    private void listf(String directoryName, ArrayList<File> files) {
        if (directoryName == null || files == null) return;

        File directory = new File(fixPath(directoryName));

        // get all the files from a directory
        File[] fList = directory.listFiles();

        if (fList != null && fList.length > 0) {
            Collections.addAll(files, fList);
        }
    }

    private File createFolder(String path) {
        String appended = fixPath(getDatadir() + path);
        boolean success = (new File(appended)).mkdirs();
        if (!success) {
            // Directory creation failed
        }
        return new File(fixPath(path));
    }

    private void bootstrap(String ap) {

        log.debug("creating: " + ap + "/etc");
        new File(ap + "/etc").mkdirs();
        boolean found = false;
        if (new File(append + "/etc/license.lic").exists()) {
            try {
                FileUtils.copyFile(new File(append + "/etc/license.lic"), this.createNode("/etc/license.lic"));
                found = true;
            } catch (IOException e1) {
                log.debug("Failed to find license 1");
            }
        }

        if (!found) {
            try {
                FileUtils.copyFile(new File(append + "/unknown/etc/license.lic"), this.createNode("/etc/license.lic"));
            } catch (IOException e2) {
                log.debug("failed to find any licenses. Giving up");
            }
        }
    }

    private File[] getAllFoldersInCurrentDirectory(String path) {
        return null;
    }

    private void delete(String folder) {
        folder = fixPath(folder);
        File file;
        try {
            file = resolveWithinDatadir(folder).toFile();
        } catch (RepositoryException e) {
            log.warn("Refusing to delete path that escapes datadir: {}", folder);
            return;
        }
        file.delete();
    }

    private File getFolder(String path) throws RepositoryException {
        return this.getNode(path);
    }

    private File getNode(String path) {
        path = fixPath(path);
        try {
            return resolveWithinDatadir(path).toFile();
        } catch (RepositoryException e) {
            // Preserve historical signature (no checked exception) by throwing unchecked.
            // Path-traversal attempts are programmer / attacker errors, not flow control.
            throw new SaikuServiceException("Path traversal attempt rejected: " + path, e);
        }
    }

    /**
     * Resolve {@code userPath} against {@link #getDatadir()} and refuse anything that
     * escapes the resolved data directory (defends against {@code ../} traversal and
     * absolute paths pointing outside the repo root). Symlink-based escapes are not
     * covered here — they need {@link Path#toRealPath} which only works for existing
     * paths, so handle separately if/when relevant.
     */
    private Path resolveWithinDatadir(String userPath) throws RepositoryException {
        if (userPath == null) {
            throw new RepositoryException("Path must not be null");
        }
        Path base = Paths.get(getDatadir()).toAbsolutePath().normalize();
        Path candidate = Paths.get(userPath);
        Path resolved;
        if (candidate.isAbsolute() && userPath.startsWith(getDatadir())) {
            // Truly filesystem-absolute path already inside the datadir — keep as-is.
            resolved = candidate.normalize();
        } else {
            // Saiku treats leading-{slash} paths like {@code "/etc/foo"} as REPO-relative
            // (i.e. {@code <datadir>/etc/foo}), not as filesystem-absolute. The legacy
            // implementation got this for free via string concat ({@code datadir + path});
            // we have to strip the leading separator so {@link Path#resolve} keeps the
            // segment relative on Unix.
            String stripped = userPath;
            while (stripped.startsWith("/") || stripped.startsWith("\\")) {
                stripped = stripped.substring(1);
            }
            resolved = base.resolve(stripped).normalize();
        }
        if (!resolved.startsWith(base)) {
            throw new RepositoryException("Path traversal attempt rejected: " + userPath);
        }
        return resolved;
    }

    private File createNode(String filename) {
        filename = fixPath(filename);
        File nodeFile = new File(filename);

        if (nodeFile.isAbsolute() && filename.startsWith(this.getDatadir())) { // Check if it's a full path already
            log.debug("Creating file:" + filename);
        } else { // If not, prefix it with the datadir
            log.debug("Creating file:" + this.getDatadir() + filename);
            nodeFile = new File(this.getDatadir(), filename);
        }

        return nodeFile;
    }

    private HttpSession getSession() {
        try {
            return sessionRegistry.getSession();
        } catch (Exception e) {
            log.debug("Error while fetching the HTTPSession", e);
        }

        return null;
    }

    private String getDatadir() {
        HttpSession session = getSession(); // Use a variable instead of a method call for debugging purposes

        if (session != null && !bootstrapping) {
            try {
                if (workspaces && session.getAttribute(ORBIS_WORKSPACE_DIR) != null) {
                    String workspace = (String) session.getAttribute(ORBIS_WORKSPACE_DIR);
                    workspace = cleanse(workspace);
                    log.debug("Check " + append + "/" + workspace + "/ exists");
                    if (!new File(append + "/" + workspace + "/").exists()) {
                        bootstrapping = true;
                        try {
                            this.bootstrap(append + "/" + workspace);
                            this.start(userService);
                        } finally {
                            bootstrapping = false;
                        }
                    }

                    log.debug("Workspace directory set to:" + workspace);
                    return fixPath(append + "/" + workspace + "/");
                } else {
                    log.debug("Workspace directory set to: unknown/");
                    if (!new File(append + "/unknown/etc").exists()) {
                        bootstrapping = true;
                        try {
                            this.bootstrap(append + "/unknown");
                            this.start(userService);
                        } finally {
                            bootstrapping = false;
                        }
                    }

                    return fixPath(append + "/unknown/");
                }
            } catch (Exception ex) {
                // This exception is expected at Saiku boot
            }
        }

        String basePath = fixPath(append + "/unknown");

        if (!bootstrapping && !new File(fixPath(basePath + "/etc")).exists()) {
            bootstrapping = true;
            try {
                this.bootstrap(basePath);
                this.start(userService);
            } catch (RepositoryException e) {
                log.error("Error while starting the repository manager", e);
            } finally {
                bootstrapping = false;
            }
        }

        return fixPath(append + "unknown/");
    }

    private String cleanse(String workspace) {
        workspace = workspace.replace("\\", "/");
        if (!workspace.endsWith("/")) {
            return workspace + "/";
        }
        return workspace + "/";
    }

    private String fixPath(String path) {
        if (path != null) {
            while (path.contains("//")) {
                path = path.replace("//", "/");
            }
        }
        return path;
    }
}
