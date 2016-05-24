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


import org.saiku.datasources.connection.RepositoryFile;
import org.saiku.service.user.UserService;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.jcr.RepositoryException;

/**
 * Repository Manager Interface
 */
public interface IRepositoryManager {
  void init();

  boolean start(UserService userService) throws RepositoryException;

  void createUser( String u ) throws RepositoryException;

  Object getHomeFolders() throws RepositoryException;

  Object getHomeFolder( String directory ) throws RepositoryException;

  Object getFolder( String user, String directory ) throws RepositoryException;

  void shutdown();

  boolean createFolder( String username, String folder ) throws RepositoryException;

  boolean deleteFolder( String folder ) throws RepositoryException;

  void deleteRepository() throws RepositoryException;

  boolean moveFolder(String user, String folder, String source, String target) throws RepositoryException;

    Object saveFile(Object file, String path, String user, String type, List<String> roles) throws RepositoryException;

    void removeFile(String path, String user, List<String> roles) throws RepositoryException;

    void moveFile(String source, String target, String user, List<String> roles) throws RepositoryException;


    Object saveInternalFile(Object file, String path, String type) throws RepositoryException;

  Object saveBinaryInternalFile(InputStream file, String path, String type) throws RepositoryException;

    String getFile(String s, String username, List<String> roles) throws RepositoryException;

    String getInternalFile(String s) throws RepositoryException;

    InputStream getBinaryInternalFile(String s) throws RepositoryException;

    void removeInternalFile(String s) throws RepositoryException;

    List<org.saiku.database.dto.MondrianSchema> getAllSchema() throws RepositoryException;

    List<DataSource> getAllDataSources() throws RepositoryException;

    void saveDataSource(DataSource ds, String path, String user) throws RepositoryException;

    byte[] exportRepository() throws RepositoryException, IOException;

    void restoreRepository(byte[] xml) throws RepositoryException, IOException;

    RepositoryFile getFile(String fileUrl);

    List<IRepositoryObject> getAllFiles(List<String> type, String username, List<String> roles);

    List<IRepositoryObject> getAllFiles(List<String> type, String username, List<String> roles, String path) throws
        RepositoryException;

    void deleteFile(String datasourcePath);

    AclEntry getACL(String object, String username, List<String> roles);

    void setACL(String object, String acl, String username, List<String> roles) throws RepositoryException;

    List<org.saiku.database.dto.MondrianSchema> getInternalFilesOfFileType(String type) throws RepositoryException;

    void createFileMixin(String type) throws RepositoryException;

    Object getRepositoryObject();

}
