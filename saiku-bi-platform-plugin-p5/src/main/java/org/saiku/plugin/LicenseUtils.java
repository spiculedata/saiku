/* Copyright (C) OSBI Ltd - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by OSBI LTD, 2014
 */

package org.saiku.plugin;

import bi.meteorite.license.LicenseException;
import bi.meteorite.license.SaikuLicense;
import bi.meteorite.license.SaikuLicense2;

import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.license.Base64Coder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Date;
import java.util.Properties;

import javax.jcr.RepositoryException;

/**
 * Created by bugg on 01/07/14.
 */
public class LicenseUtils implements org.saiku.service.license.ILicenseUtils {
  private static final Logger log = LoggerFactory.getLogger(LicenseUtils.class);

  private IDatasourceManager repositoryDatasourceManager;
  private String adminuser;

  public IDatasourceManager getRepositoryDatasourceManager() {
    return repositoryDatasourceManager;
  }

  public void setRepositoryDatasourceManager(
      IDatasourceManager repositoryDatasourceManager) {
    this.repositoryDatasourceManager = repositoryDatasourceManager;
  }


  public void init() {

  }

  public LicenseUtils() {

  }

  public void setLicense(SaikuLicense lic) throws IOException {

    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    ObjectOutputStream so = new ObjectOutputStream(bo);
    so.writeObject(lic);
    so.flush();
    this.repositoryDatasourceManager
        .saveInternalFile("/etc/license.lic", bo.toString(), null);


  }

  public void setLicense(String lic) {

    this.repositoryDatasourceManager.deleteFolder("/etc/license.lic");
    this.repositoryDatasourceManager
        .saveInternalFile("/etc/license.lic", lic, null);


  }

  public Object getLicense()
      throws IOException, ClassNotFoundException, RepositoryException {

    String file = this.repositoryDatasourceManager
        //.getInternalFileData("/etc/license.lic");
        .getInternalFileData("license.lic");

    Object obj = null;
    if(file == null){
      throw new RepositoryException("Could not find license in predefined places");
    }
    byte[] b = Base64Coder.decode(file);

    try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(
        b))) {
      Object license = null;
      try {
        license = in.readObject();
      } catch (ClassNotFoundException e) {
        log.debug("Could not decode license(classnotfound) please fetch a new one", e);
      }
      catch (InvalidClassException e){
        log.debug("Could not decode license, please fetch a new one", e);
      }
      return license;
    }


  }

  public SaikuLicense getLicenseNo64()
      throws IOException, ClassNotFoundException, RepositoryException {

    String file = this.repositoryDatasourceManager
        .getInternalFileData("/etc/license.lic");

    SaikuLicense obj = null;

    try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(
        file.getBytes()))) {
      SaikuLicense license = null;
      try {
        license = (SaikuLicense) in.readObject();
      } catch (ClassNotFoundException e) {
        e.printStackTrace();
      }
      return license;
    }


  }

  public void validateLicense() throws RepositoryException, IOException, ClassNotFoundException, LicenseException {
    Object l = getLicense();

    if (l instanceof SaikuLicense) {
      ((SaikuLicense) l).validate(new Date(), getVersion(), false, false, true, false);
    } else if (l instanceof SaikuLicense2) {
      ((SaikuLicense2) l).validate(new Date(), getVersion(), false, false, true, false);
    } else {
      throw new LicenseException("Can't validate license unknown license type");
    }
  }

  private String getVersion() {
    Properties prop = new Properties();
    InputStream input = null;
    String version = "";
    ClassLoader classloader = Thread.currentThread().getContextClassLoader();
    InputStream is = classloader.getResourceAsStream(
        "org/saiku/web/rest/resources/version.properties");
    try {

      //input = new FileInputStream("version.properties");

      // load a properties file
      prop.load(is);

      // get the property value and print it out
      version = prop.getProperty("VERSION");
    } catch (IOException ex) {
      ex.printStackTrace();
    } finally {
      if (input != null) {
        try {
          input.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
    return "3.8.8";
  }


  public void setAdminuser(String adminuser) {
    this.adminuser = adminuser;
  }

  public String getAdminuser() {
    return adminuser;
  }

}
