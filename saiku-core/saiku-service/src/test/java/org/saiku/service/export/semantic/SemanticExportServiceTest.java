/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.semantic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.saiku.service.ossie.OssieModelDto;

/** Dispatch + input-validation tests for {@link SemanticExportService}. */
public class SemanticExportServiceTest {

    @Test
    public void dispatchesCaseInsensitivelyByToolName() {
        OssieModelDto model = fixture();
        SemanticExportService service = new SemanticExportService();

        assertEquals("model.tds", service.export(model, "TABLEAU").getFilename());
        assertEquals("model.tds", service.export(model, "tableau").getFilename());
        assertEquals(
                "model-superset-export.zip", service.export(model, "Superset").getFilename());
    }

    @Test
    public void unknownToolNamesTheValidOptions() {
        OssieModelDto model = fixture();
        SemanticExportException e =
                assertThrows(SemanticExportException.class, () -> new SemanticExportService().export(model, "powerbi"));
        assertTrue(e.getMessage().contains("tableau"));
        assertTrue(e.getMessage().contains("superset"));
    }

    private OssieModelDto fixture() {
        OssieModelDto model = new OssieModelDto();
        model.setName("model");
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("t1");
        OssieModelDto.Field f = new OssieModelDto.Field();
        f.setName("f1");
        ds.getFields().add(f);
        model.getDatasets().add(ds);
        return model;
    }
}
