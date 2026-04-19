package org.saiku.service.importer;

import java.util.List;
import org.saiku.repository.IRepositoryManager;

public interface LegacyImporter {

    void importUsers();

    void importSchema();

    void importDatasources();

    void importLegacyReports(IRepositoryManager repositoryManager, byte[] bytes);

    List<JujuSource> importJujuDatasources();
}
