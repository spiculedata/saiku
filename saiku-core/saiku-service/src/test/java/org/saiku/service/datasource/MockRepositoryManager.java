/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.datasources.connection.RepositoryFile;
import org.saiku.repository.AclEntry;
import org.saiku.repository.DataSource;
import org.saiku.repository.IRepositoryManager;
import org.saiku.repository.IRepositoryObject;
import org.saiku.repository.RepositoryException;
import org.saiku.service.user.UserService;

public class MockRepositoryManager implements IRepositoryManager {
    private Map<String, Object> files = new HashMap<>();
    private Map<String, DataSource> datasources = new HashMap<>();

    @Override
    public void init() {}

    @Override
    public boolean start(UserService userService) throws RepositoryException {
        return false;
    }

    @Override
    public void createUser(String u) throws RepositoryException {}

    @Override
    public Object getHomeFolders() throws RepositoryException {
        return null;
    }

    @Override
    public Object getHomeFolder(String directory) throws RepositoryException {
        return null;
    }

    @Override
    public Object getFolder(String user, String directory) throws RepositoryException {
        return null;
    }

    @Override
    public void shutdown() {}

    @Override
    public boolean createFolder(String username, String folder) throws RepositoryException {
        return false;
    }

    @Override
    public boolean deleteFolder(String folder) throws RepositoryException {
        return false;
    }

    @Override
    public void deleteRepository() throws RepositoryException {}

    @Override
    public boolean moveFolder(String user, String folder, String source, String target) throws RepositoryException {
        return false;
    }

    @Override
    public Object saveFile(Object file, String path, String user, String type, List<String> roles)
            throws RepositoryException {
        return null;
    }

    @Override
    public void removeFile(String path, String user, List<String> roles) throws RepositoryException {}

    @Override
    public void moveFile(String source, String target, String user, List<String> roles) throws RepositoryException {}

    @Override
    public Object saveInternalFile(Object file, String path, String type) throws RepositoryException {
        System.out.println("saving internal file at " + path);
        System.out.println(file);
        this.files.put(path, file);
        return file;
    }

    @Override
    public Object saveBinaryInternalFile(InputStream file, String path, String type) throws RepositoryException {
        return null;
    }

    @Override
    public String getFile(String s, String username, List<String> roles) throws RepositoryException {
        return (String) this.files.get(s);
    }

    @Override
    public String getInternalFile(String s) throws RepositoryException {
        return (String) this.files.get(s);
    }

    @Override
    public InputStream getBinaryInternalFile(String s) throws RepositoryException {
        return null;
    }

    @Override
    public void removeInternalFile(String s) throws RepositoryException {}

    @Override
    public List<MondrianSchema> getAllSchema() throws RepositoryException {
        return null;
    }

    @Override
    public List<DataSource> getAllDataSources() throws RepositoryException {
        return new ArrayList<DataSource>(this.datasources.values());
    }

    @Override
    public void saveDataSource(DataSource ds, String path, String user) throws RepositoryException {
        System.out.println("saving datasource at " + path);
        this.datasources.put(path, ds);
    }

    public DataSource getDataSource(String path) {
        return this.datasources.get(path);
    }

    @Override
    public byte[] exportRepository() throws RepositoryException, IOException {
        return new byte[0];
    }

    @Override
    public void restoreRepository(byte[] xml) throws RepositoryException, IOException {}

    @Override
    public RepositoryFile getFile(String fileUrl) {
        return null;
    }

    @Override
    public List<IRepositoryObject> getAllFiles(List<String> type, String username, List<String> roles) {
        return null;
    }

    @Override
    public List<IRepositoryObject> getAllFiles(List<String> type, String username, List<String> roles, String path)
            throws RepositoryException {
        return null;
    }

    @Override
    public void deleteFile(String datasourcePath) {}

    @Override
    public AclEntry getACL(String object, String username, List<String> roles) {
        return null;
    }

    @Override
    public void setACL(String object, String acl, String username, List<String> roles) throws RepositoryException {}

    @Override
    public List<MondrianSchema> getInternalFilesOfFileType(String type) throws RepositoryException {
        return null;
    }

    @Override
    public void createFileMixin(String type) throws RepositoryException {}

    @Override
    public Object getRepositoryObject() {
        return null;
    }
}
