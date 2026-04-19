package org.saiku.datasources.connection;

import java.util.Collection;
import org.apache.commons.vfs.FileName;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemConfigBuilder;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileSystemOptions;
import org.apache.commons.vfs.VFS;
import org.apache.commons.vfs.impl.DefaultFileSystemManager;
import org.apache.commons.vfs.provider.FileProvider;
import org.saiku.service.datasource.IDatasourceManager;

public class MondrianVFS implements FileProvider {
    private IDatasourceManager datasourceManager;

    public void setDatasourceManager(IDatasourceManager dms) {
        this.datasourceManager = dms;
    }

    public void init() {
        try {
            DefaultFileSystemManager dfsm = (DefaultFileSystemManager) VFS.getManager();
            if (!dfsm.hasProvider("mondrian")) {
                dfsm.addProvider("mondrian", this);
            }
        } catch (FileSystemException e) {
        }
    }

    public FileObject findFile(FileObject fileObject, String catalog, FileSystemOptions fileSystemOptions)
            throws FileSystemException {
        return new RepositoryVfsFileObject(catalog, this.datasourceManager);
    }

    public FileObject createFileSystem(String s, FileObject fileObject, FileSystemOptions fileSystemOptions)
            throws FileSystemException {
        return null;
    }

    public FileSystemConfigBuilder getConfigBuilder() {
        return null;
    }

    public Collection getCapabilities() {
        return null;
    }

    public FileName parseUri(FileName fileName, String s) throws FileSystemException {
        return null;
    }
}
