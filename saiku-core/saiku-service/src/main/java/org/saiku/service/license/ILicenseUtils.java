package org.saiku.service.license;

import bi.meteorite.license.LicenseException;
import bi.meteorite.license.SaikuLicense;
import org.saiku.service.datasource.IDatasourceManager;

import javax.jcr.RepositoryException;
import java.io.IOException;

/**
 * Created by bugg on 23/05/16.
 */
public interface ILicenseUtils {
    IDatasourceManager getRepositoryDatasourceManager();

    void setRepositoryDatasourceManager(
            IDatasourceManager repositoryDatasourceManager);

    void setLicense(SaikuLicense lic) throws IOException;

    void setLicense(String lic);

    Object getLicense()
            throws IOException, ClassNotFoundException, RepositoryException;

    SaikuLicense getLicenseNo64()
                throws IOException, ClassNotFoundException, RepositoryException;

    void validateLicense()
                        throws LicenseException, RepositoryException, IOException, ClassNotFoundException;

    void setAdminuser(String adminuser);

    String getAdminuser();
}
