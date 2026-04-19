package org.saiku.datasources.connection;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.Certificate;
import java.util.Map;
import org.apache.commons.vfs.FileContent;
import org.apache.commons.vfs.FileContentInfo;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.RandomAccessContent;
import org.apache.commons.vfs.util.RandomAccessMode;

class RepositoryVfsFileContent implements FileContent {
    private RepositoryVfsFileObject fileObject = null;
    private InputStream inputStream = null;
    private boolean isOpen;

    public RepositoryVfsFileContent() {}

    public RepositoryVfsFileContent(RepositoryVfsFileObject repositoryVfsFileObject) {
        this.fileObject = repositoryVfsFileObject;
    }

    public FileObject getFile() {
        return this.fileObject;
    }

    public long getSize() throws FileSystemException {
        return 0L;
    }

    public long getLastModifiedTime() throws FileSystemException {
        return 0L;
    }

    public void setLastModifiedTime(long l) throws FileSystemException {}

    public boolean hasAttribute(String s) {
        return false;
    }

    public Map getAttributes() throws FileSystemException {
        return null;
    }

    public String[] getAttributeNames() throws FileSystemException {
        return new String[0];
    }

    public Object getAttribute(String s) throws FileSystemException {
        return null;
    }

    public void setAttribute(String s, Object o) throws FileSystemException {}

    public void removeAttribute(String s) {}

    public Certificate[] getCertificates() throws FileSystemException {
        return new Certificate[0];
    }

    public InputStream getInputStream() throws FileSystemException {
        this.inputStream = this.fileObject.getInputStream();
        this.isOpen = true;
        return this.inputStream;
    }

    public OutputStream getOutputStream() throws FileSystemException {
        return null;
    }

    public RandomAccessContent getRandomAccessContent(RandomAccessMode randomAccessMode) throws FileSystemException {
        return null;
    }

    public OutputStream getOutputStream(boolean b) throws FileSystemException {
        return null;
    }

    public void close() throws FileSystemException {
        if (!this.isOpen) {
            return;
        }
        if (this.inputStream != null) {
            try {
                this.inputStream.close();
            } catch (Exception e) {
            }
        }
        this.isOpen = false;
        this.fileObject.close();
    }

    public FileContentInfo getContentInfo() throws FileSystemException {
        return null;
    }

    public boolean isOpen() {
        return false;
    }
}
