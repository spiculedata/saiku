/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection;

import java.util.UUID;

/**
 * Created by bugg on 23/06/14.
 */
public class RepositoryFile {

    private String path = null;
    private String fileName = null;
    private String fileId = null;
    private byte[] data;

    private RepositoryFile() {}

    public RepositoryFile(String fileName, RepositoryFile parent, byte[] data) {
        this(fileName, parent, data, System.currentTimeMillis());
    }

    public RepositoryFile(String fileName, RepositoryFile parent, byte[] data, String path) {
        this(fileName, parent, data, System.currentTimeMillis());
        this.path = path;
    }

    private RepositoryFile(String fileName, RepositoryFile parent, byte[] data, long lastModified) {
        this();
        this.fileId = UUID.randomUUID().toString();

        this.fileName = fileName;

        setData(data);
    }

    private void setData(byte[] data) {
        this.data = data;
    }

    public byte[] getData() {
        return this.data;
    }

    public String getFileName() {
        return this.fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileId() {
        return this.fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public boolean isFolder() {
        return false;
    }

    public String getPath() {
        return this.path;
    }
}
