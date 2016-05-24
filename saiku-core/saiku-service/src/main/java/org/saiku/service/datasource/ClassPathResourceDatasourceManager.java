/*
 *   Copyright 2014 OSBI Ltd
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

package org.saiku.service.datasource;

import org.saiku.database.dto.MondrianSchema;
import org.saiku.datasources.connection.RepositoryFile;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.repository.*;
import org.saiku.service.importer.LegacyImporter;
import org.saiku.service.importer.LegacyImporterImpl;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuServiceException;

import org.saiku.service.util.security.authentication.PasswordProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;

/**
 * A Datasource Manager for the Saiku Repository API layer.
 */
public class ClassPathResourceDatasourceManager implements IDatasourceManager {
  private final Map<String, SaikuDatasource> datasources =
      Collections.synchronizedMap(new HashMap<String, SaikuDatasource>());
  private UserService userService;
  private static final Logger log = LoggerFactory.getLogger(RepositoryDatasourceManager.class);
  private String configurationpath;
  private String datadir;
  private IRepositoryManager irm;
  private String foodmartdir;
  private String foodmartschema;
  private String foodmarturl;
  private PasswordProvider repopasswordprovider;
  private String oldpassword;
  private String earthquakeurl;
  private String earthquakedir;
  private String earthquakeschema;
  private String defaultRole;
  private String externalparameters;

  public void load() {
    Properties ext = checkForExternalDataSourceProperties();
    datasources.clear();
    try {

      List<DataSource> exporteddatasources = null;
      try {
        exporteddatasources = irm.getAllDataSources();
      } catch (RepositoryException e1) {
        log.error("Could not export data sources", e1);
      }

      if (exporteddatasources != null) {
        for (DataSource file : exporteddatasources) {
          if (file.getName() != null && file.getType() != null) {
            Properties props = new Properties();
            if(file.getDriver()!= null) {
              props.put("driver", file.getDriver());
            }
            else if(file.getPropertyKey()!=null && ext.containsKey("datasource."+file.getPropertyKey()+".driver")){
              String p = ext.getProperty("datasource." + file.getPropertyKey() + ".driver");
              props.put("driver", p);
            }
            if(file.getPropertyKey()!=null &&
               ext.containsKey("datasource."+file.getPropertyKey()+".location")){
              String p = ext.getProperty("datasource." + file.getPropertyKey() + ".location");
              if(ext.containsKey("datasource."+file.getPropertyKey()+".schemaoverride")){
                String[] spl = p.split(";");
                spl[1]="Catalog=mondrian://"+file.getSchema();
                StringBuilder sb = new StringBuilder();
                for(String str: spl){
                  sb.append(str+";");
                }
                props.put("location",sb.toString());
              }
              else {
                props.put("location", p);
              }
            }
            else if(file.getLocation()!=null) {
              props.put("location", file.getLocation());
            }
            if(file.getUsername()!=null && file.getPropertyKey()==null) {
              props.put("username", file.getUsername());
            }
            else if(file.getPropertyKey()!=null &&
                    ext.containsKey("datasource."+file.getPropertyKey()+".username")){
              String p = ext.getProperty("datasource." + file.getPropertyKey() + ".username");
              props.put("username", p);
            }
            if(file.getPassword()!=null && file.getPropertyKey()==null) {
              props.put("password", file.getPassword());
            }
            else if(file.getPropertyKey()!=null &&
                    ext.containsKey("datasource."+file.getPropertyKey()+".password")){
              String p = ext.getProperty("datasource." + file.getPropertyKey() + ".password");
              props.put("password", p);
            }
            if(file.getPath()!=null) {
              props.put("path", file.getPath());
            }
            else if(file.getPropertyKey()!=null &&
                    ext.containsKey("datasource."+file.getPropertyKey()+".path")){
              String p = ext.getProperty("datasource." + file.getPropertyKey() + ".path");
              props.put("path", p);
            }
            if(file.getId()!=null) {
              props.put("id", file.getId());
            }
            if(file.getSecurityenabled()!=null) {
              props.put("security.enabled", file.getSecurityenabled());
            }
            else if(file.getPropertyKey()!=null &&
                    ext.containsKey("datasource."+file.getPropertyKey()+".security.enabled")){
              String p = ext.getProperty("datasource." + file.getPropertyKey() + ".security.enabled");
              props.put("security.enabled", p);
            }
            if(file.getSecuritytype()!=null) {
              props.put("security.type", file.getSecuritytype());
            }
            else if(file.getPropertyKey()!=null &&
                    ext.containsKey("datasource."+file.getPropertyKey()+".security.type")){
              String p = ext.getProperty("datasource." + file.getPropertyKey() + ".security.type");
              props.put("security.type", p);
            }
            if(file.getSecuritymapping()!=null) {
              props.put("security.mapping", file.getSecuritymapping());
            }
            else if(file.getPropertyKey()!=null &&
                    ext.containsKey("datasource."+file.getPropertyKey()+".security.mapping")){
              String p = ext.getProperty("datasource." + file.getPropertyKey() + ".security.mapping");
              props.put("security.mapping", p);
            }
            if(file.getAdvanced()!=null){
              props.put("advanced", file.getAdvanced());
            }
            if(file.getPropertyKey()!=null){
              props.put("propertykey", file.getPropertyKey());
            }
            SaikuDatasource.Type t = SaikuDatasource.Type.valueOf(file.getType().toUpperCase());
            SaikuDatasource ds = new SaikuDatasource(file.getName(), t, props);
            datasources.put(file.getName(), ds);
          }
        }
      }


    } catch (Exception e) {
      throw new SaikuServiceException(e.getMessage(), e);
    }
  }

  public Properties checkForExternalDataSourceProperties(){
    Properties p = new Properties();
    InputStream input;

    try {
      input = new FileInputStream(externalparameters);
      p.load(input);
    } catch (IOException e) {
      log.debug("file did not exist");
    }

    return p;

  }

  public String[] getAvailablePropertiesKeys(){
    Properties p = new Properties();
    InputStream input;

    try {
      input = new FileInputStream(externalparameters);
      p.load(input);
    } catch (IOException e) {
      log.debug("file did not exist");
    }

    String[] arr = p.keySet().toArray(new String[p.keySet().size()]);

    ArrayList<String> newlist = new ArrayList<>();
    for(String str: arr){
      String[] s = str.split("\\.");
      newlist.add(s[1]);
    }
    Set<String> unique = new HashSet<>(newlist);

    return  unique.toArray(new String[unique.size()]);
  }

  public void unload() {
    irm.shutdown();
  }

  public SaikuDatasource addDatasource(SaikuDatasource datasource) throws Exception {
    DataSource ds = new DataSource(datasource);

    irm.saveDataSource(ds, "/datasources/" + ds.getName() + ".sds", "fixme");
    datasources.put(datasource.getName(), datasource);

    return datasource;
  }

  public SaikuDatasource setDatasource(SaikuDatasource datasource) throws Exception {
    return addDatasource(datasource);
  }

  public List<SaikuDatasource> addDatasources(List<SaikuDatasource> dsources) {
    for (SaikuDatasource datasource : dsources) {
      DataSource ds = new DataSource(datasource);

      try {
        irm.saveDataSource(ds, "/datasources/" + ds.getName() + ".sds", "fixme");
        datasources.put(datasource.getName(), datasource);

      } catch (RepositoryException e) {
        log.error("Could not add data source"+ datasource.getName(), e);
      }

    }
    return dsources;
  }

  public boolean removeDatasource(String datasourceId) {
    List<DataSource> ds = null;
    try {
      ds = irm.getAllDataSources();
    } catch (RepositoryException e) {
      log.error("Could not get all data sources");
    }

    if (ds != null) {
      for(DataSource data : ds){
        if(data.getId().equals(datasourceId)){
          datasources.remove(data.getName());
          irm.deleteFile(data.getPath());
          return true;
        }
      }
    }
    return false;
  }

  public boolean removeSchema(String schemaName) {
    List<org.saiku.database.dto.MondrianSchema> s = null;
    try {
      s = irm.getAllSchema();
    } catch (RepositoryException e) {
      log.error("Could not get All Schema", e);
    }

    if (s != null) {
      for(MondrianSchema data : s){
        if(data.getName().equals(schemaName)){
          irm.deleteFile(data.getPath());
          break;
        }
      }
      return true;
    }
    else{
      return false;
    }



  }

  public Map<String, SaikuDatasource> getDatasources() {
    return datasources;
  }

  public SaikuDatasource getDatasource(String datasourceName) {
    return datasources.get(datasourceName);
  }

  @Override
  public SaikuDatasource getDatasource(String datasourceName, boolean refresh) {
    if(!refresh) {
      if(datasources.size()>0) {
        return datasources.get(datasourceName);
      }
    }
    else{
      return getDatasource(datasourceName);
    }
    return null;
  }

  public void addSchema(String file, String path, String name) throws Exception {
    irm.saveInternalFile(file, path, "nt:mondrianschema");

  }

  public List<MondrianSchema> getMondrianSchema() {
    try {
      return irm.getAllSchema();
    } catch (RepositoryException e) {
      log.error("Could not get all Schema", e);
    }
    return null;
  }

  public MondrianSchema getMondrianSchema(String catalog) {
    //return irm.getMondrianSchema();
    return null;
  }

  public RepositoryFile getFile(String file) {
    return irm.getFile(file);
  }


  public String getFileData(String file, String username, List<String> roles) {
    try {
      return irm.getFile(file, username, roles);
    } catch (RepositoryException e) {
      log.error("Could not get file "+file, e);
    }
    return null;
  }

  public String getInternalFileData(String file) throws RepositoryException {

    return irm.getInternalFile(file);


  }

  public InputStream getBinaryInternalFileData(String file) throws RepositoryException {

    return irm.getBinaryInternalFile(file);


  }

  public String saveFile(String path, Object content, String user, List<String> roles) {
    try {
      irm.saveFile(content, path, user, "nt:saikufiles", roles);
      return "Save Okay";
    } catch (RepositoryException e) {
      log.error("Save Failed", e);
      return "Save Failed: " + e.getLocalizedMessage();
    }
  }

  public String removeFile(String path, String user, List<String> roles) {
    try {
      irm.removeFile(path, user, roles);
      return "Remove Okay";
    } catch (RepositoryException e) {
      log.error("Save Failed", e);
      return "Save Failed: " + e.getLocalizedMessage();
    }
  }

  public String moveFile(String source, String target, String user, List<String> roles) {
    try {
      irm.moveFile(source, target, user, roles);
      return "Move Okay";
    } catch (RepositoryException e) {
      log.error("Move Failed", e);
      return "Move Failed: " + e.getLocalizedMessage();
    }
  }

  public String saveInternalFile(String path, Object content, String type) {
    try {
      irm.saveInternalFile(content, path, type);
      return "Save Okay";
    } catch (RepositoryException e) {
      e.printStackTrace();
      return "Save Failed: " + e.getLocalizedMessage();
    }
  }

  public String saveBinaryInternalFile(String path, InputStream content, String type) {
    try {
      irm.saveBinaryInternalFile(content, path, type);
      return "Save Okay";
    } catch (RepositoryException e) {
      e.printStackTrace();
      return "Save Failed: " + e.getLocalizedMessage();
    }
  }
  public void removeInternalFile(String filePath) {
    try{
      irm.removeInternalFile(filePath);
    } catch(RepositoryException e) {
      log.error("Remove file failed: " + filePath);
      e.printStackTrace();
    }
  }

  public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles) {
    return irm.getAllFiles(type, username, roles);
  }

  public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles, String path) {
    try {
      return irm.getAllFiles(type, username, roles, path);
    } catch (RepositoryException e) {
      log.error("Get failed", e);
    }
    return null;
  }


  public void createUser(String username){
    try {
      irm.createUser(username);
    } catch (RepositoryException e) {
      log.error("Create User Failed", e);
    }
  }

  public void deleteFolder(String folder) {
    try {
      irm.deleteFolder(folder);
    } catch (RepositoryException e) {
      log.error("Delete User Failed", e);
    }
  }

  public AclEntry getACL(String object, String username, List<String> roles) {
    return irm.getACL(object, username, roles);
  }

  public void setACL(String object, String acl, String username, List<String> roles) {
    try {
      irm.setACL(object, acl, username, roles);
    } catch (RepositoryException e) {
      log.error("Set ACL Failed", e);
    }
  }


  public void setUserService(UserService userService) {
    this.userService = userService;
  }

  public List<MondrianSchema> getInternalFilesOfFileType(String type){
    try {
      return irm.getInternalFilesOfFileType(type);
    } catch (RepositoryException e) {
      log.error("Get internal file failed", e);
    }
    return null;
  }

  public void createFileMixin(String type) throws RepositoryException {
    irm.createFileMixin(type);
  }

  public byte[] exportRepository(){
    try {
      return irm.exportRepository();

    } catch (RepositoryException e) {
      log.error("could not export repository", e);
    } catch (IOException e) {
      log.error("could not export repository IO issue", e);
    }
    return null;
  }

  public void restoreRepository(byte[] data) {
    try {
      irm.restoreRepository(data);
    }
    catch (Exception e){
      log.error("Could not restore export", e);
    }
  }

  public boolean hasHomeDirectory(String name) {
    try{
      Object eturn = irm.getHomeFolder(name);
      return eturn != null;
    } catch(PathNotFoundException e) {
      return false;
    } catch (RepositoryException e) {
      log.error("could not get home directory");
    }
    return false;
  }

  public void restoreLegacyFiles(byte[] data) {
    LegacyImporter l = new LegacyImporterImpl(null);
    l.importLegacyReports(irm, data);
  }

  public Object getRepository() {
    return irm.getRepositoryObject();
  }

  public void setConfigurationpath(String configurationpath) {
    this.configurationpath = configurationpath;
  }

  public String getConfigurationpath() {
    return configurationpath;
  }

  public void setDatadir(String datadir) {
    this.datadir = datadir;
  }

  public String getDatadir() {
    return datadir;
  }

  public void setFoodmartdir(String foodmartdir) {
    this.foodmartdir = foodmartdir;
  }

  public String getFoodmartdir() {
    return foodmartdir;
  }

  public void setFoodmartschema(String foodmartschema) {
    this.foodmartschema = foodmartschema;
  }

  public String getFoodmartschema() {
    return foodmartschema;
  }

  public void setFoodmarturl(String foodmarturl) {
    this.foodmarturl = foodmarturl;
  }

  public String getFoodmarturl() {
    return foodmarturl;
  }

  public String getEarthquakeUrl() {
    return earthquakeurl;
  }

  public String getEarthquakeDir() {
    return earthquakedir;
  }

  public String getEarthquakeSchema() {
    return earthquakeschema;
  }


  public void setEarthquakeUrl(String earthquakeurl) {
    this.earthquakeurl = earthquakeurl;
  }

  public void setEarthquakeDir(String earthquakedir) {
    this.earthquakedir = earthquakedir;
  }

  public void setEarthquakeSchema(String earthquakeschema) {
    this.earthquakeschema = earthquakeschema;
  }

  @Override
  public void setExternalPropertiesFile(String file) {
    this.externalparameters = file;
  }

  public void setRepoPasswordProvider(PasswordProvider passwordProvider){
    this.repopasswordprovider = passwordProvider;
  }

  public PasswordProvider getRepopasswordprovider(){
    return repopasswordprovider;
  }

  public void setOldRepoPassword(String password){
    this.oldpassword = password;
  }

  public String getOldRepopassword(){
    return oldpassword;
  }

  public void setDefaultRole(String defaultRole)
  {
    this.defaultRole = defaultRole;
  }
}

