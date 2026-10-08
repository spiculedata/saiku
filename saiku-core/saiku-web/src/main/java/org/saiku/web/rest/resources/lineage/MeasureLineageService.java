/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.lineage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.repository.IRepositoryObject;
import org.saiku.repository.RepositoryFileObject;
import org.saiku.repository.RepositoryFolderObject;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiAxisSelection;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.util.xml.SecureXml;
import org.saiku.web.rest.resources.dashboards.Dashboard;
import org.saiku.web.rest.resources.dashboards.DashboardTile;
import org.saiku.web.rest.resources.dashboards.KpiConfig;
import org.saiku.web.rest.resources.dashboards.TileQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * saiku#1120 Phase 1: static-source measure/dimension/hierarchy/level lineage. Scans every saved
 * query ({@code .saiku}), dashboard ({@code .saikudash}) and schema-level calculated member for a
 * reference to the requested unique name, so "if I deprecate this measure, what breaks?" has an
 * answer other than grepping git.
 *
 * <p>Always re-scans the repository from scratch — there is no cache to go stale, so the "index
 * rebuilds on save" requirement from the issue's test plan is trivially satisfied. If this becomes
 * a performance problem on large repositories, a future phase can add an incrementally-maintained
 * index; Phase 1 deliberately doesn't, to keep behaviour obviously correct.
 *
 * <p>Ad-hoc query lineage (via the AI audit log, saiku#906) and the reverse view ("pick a
 * dashboard, see every measure it uses") are later phases per the issue and out of scope here.
 */
public class MeasureLineageService {

    private static final Logger log = LoggerFactory.getLogger(MeasureLineageService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // Dashboards are UI-authored documents with fields this scan doesn't need to know
            // about (chart options, sparkline config, ...) — see DashboardResource's MAPPER for
            // the same rationale. Failing to even locate the tiles we DO care about because of an
            // unrelated field would make the lineage scan silently miss dashboards.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DatasourceService datasourceService;

    public void setDatasourceService(DatasourceService datasourceService) {
        this.datasourceService = datasourceService;
    }

    /**
     * Find every dashboard, saved query and calculated measure that references {@code uniqueName}
     * (e.g. {@code [Measures].[Store Sales]}, {@code [Store].[Stores].[Store Country]}). Results
     * are unordered by kind — group client-side.
     */
    public List<LineageDependent> findDependents(String uniqueName, String username, List<String> roles) {
        MemberRef ref = MemberRef.parse(uniqueName);
        List<LineageDependent> out = new ArrayList<>();

        scanSavedQueries(ref, username, roles, out);
        scanDashboards(ref, username, roles, out);
        scanCalculatedMembers(ref, out);

        out.sort(Comparator.<LineageDependent, String>comparing(d -> d.kind)
                .thenComparing(d -> d.name == null ? "" : d.name));
        return out;
    }

    private void scanSavedQueries(MemberRef ref, String username, List<String> roles, List<LineageDependent> out) {
        for (RepositoryFileObject f : flattenFiles(datasourceService.getFiles(List.of("saiku"), username, roles))) {
            String content = datasourceService.getFileData(f.getPath(), username, roles);
            // Saved queries embed MDX with unique names verbatim, in both the legacy XML format
            // (<MDX>...</MDX>) and the newer JSON format ({"mdx": "..."}) — a raw substring match
            // is sufficient and format-agnostic.
            if (ref.matchesRawText(content)) {
                out.add(new LineageDependent("saved-query", f.getName(), f.getPath(), f.getModified()));
            }
        }
    }

    private void scanDashboards(MemberRef ref, String username, List<String> roles, List<LineageDependent> out) {
        for (RepositoryFileObject f : flattenFiles(datasourceService.getFiles(List.of("saikudash"), username, roles))) {
            String content = datasourceService.getFileData(f.getPath(), username, roles);
            if (content == null) {
                continue;
            }
            Dashboard dashboard = null;
            boolean matched = false;
            try {
                dashboard = MAPPER.readValue(content, Dashboard.class);
                if (dashboard.layout != null && dashboard.layout.tiles != null) {
                    for (DashboardTile tile : dashboard.layout.tiles) {
                        if (tileReferences(tile, ref, username, roles)) {
                            matched = true;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not parse dashboard {} as structured JSON, falling back to raw scan", f.getPath(), e);
            }
            // Fallback covers malformed/partial JSON and any inline MDX the structured DTOs don't
            // model — dashboards persisted before a schema change, for instance.
            if (!matched && ref.matchesRawText(content)) {
                matched = true;
            }
            if (matched) {
                String name = dashboard != null && dashboard.name != null ? dashboard.name : f.getName();
                out.add(new LineageDependent("dashboard", name, f.getPath(), f.getModified()));
            }
        }
    }

    private boolean tileReferences(DashboardTile tile, MemberRef ref, String username, List<String> roles) {
        if (tile == null) {
            return false;
        }
        KpiConfig kpi = tile.kpi;
        if (kpi != null) {
            if (ref.matchesMeasureField(kpi.measure) || ref.matchesMeasureField(kpi.measureCaption)) {
                return true;
            }
            if (kpi.timeLevel != null
                    && ref.matchesAxis(kpi.timeLevel.dimension, kpi.timeLevel.hierarchy, kpi.timeLevel.level)) {
                return true;
            }
        }
        TileQuery query = tile.query;
        if (query == null) {
            return false;
        }
        if ("inline".equals(query.kind) && query.body != null) {
            return queryBodyReferences(query.body, ref);
        }
        if ("reference".equals(query.kind) && query.path != null) {
            String referenced = datasourceService.getFileData(query.path, username, roles);
            return ref.matchesRawText(referenced);
        }
        return false;
    }

    private boolean queryBodyReferences(AiQueryRequest body, MemberRef ref) {
        if (ref.isMeasure()) {
            for (AiMeasureSelection m : body.getMeasures()) {
                if (ref.matchesMeasureField(m.getName())) {
                    return true;
                }
            }
            return false;
        }
        for (AiAxisSelection a : body.getRows()) {
            if (ref.matchesAxis(a.getDimension(), a.getHierarchy(), a.getLevel())) {
                return true;
            }
        }
        for (AiAxisSelection a : body.getColumns()) {
            if (ref.matchesAxis(a.getDimension(), a.getHierarchy(), a.getLevel())) {
                return true;
            }
        }
        for (AiFilterSelection f : body.getFilters()) {
            if (ref.matchesAxis(f.getDimension(), f.getHierarchy(), f.getLevel())) {
                return true;
            }
        }
        return false;
    }

    private void scanCalculatedMembers(MemberRef ref, List<LineageDependent> out) {
        List<MondrianSchema> schemas = datasourceService.getAvailableSchema();
        if (schemas == null) {
            return;
        }
        for (MondrianSchema schema : schemas) {
            String xml = datasourceService.getInternalFileData(schema.getPath());
            if (xml == null) {
                continue;
            }
            for (CalcMember cm : parseCalculatedMembers(xml)) {
                // A calc member that IS the target isn't a dependent of itself.
                if ("Measures".equalsIgnoreCase(cm.dimension)
                        && ref.isMeasure()
                        && ref.leafName().equalsIgnoreCase(cm.name)) {
                    continue;
                }
                if (cm.formula != null && ref.matchesRawText(cm.formula)) {
                    out.add(new LineageDependent("calc-measure", cm.name, schema.getPath() + "#" + cm.name, 0L));
                }
            }
        }
    }

    private List<CalcMember> parseCalculatedMembers(String schemaXml) {
        List<CalcMember> out = new ArrayList<>();
        try {
            DocumentBuilder builder = SecureXml.secureDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(schemaXml)));
            NodeList nodes = doc.getElementsByTagName("CalculatedMember");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                String name = el.getAttribute("name");
                String dimension = el.getAttribute("dimension");
                String formula = el.getAttribute("formula");
                NodeList formulaNodes = el.getElementsByTagName("Formula");
                if (formulaNodes.getLength() > 0) {
                    formula = formulaNodes.item(0).getTextContent();
                }
                out.add(new CalcMember(name, dimension, formula));
            }
        } catch (Exception e) {
            log.warn("Could not parse schema XML for calculated members", e);
        }
        return out;
    }

    private List<RepositoryFileObject> flattenFiles(List<IRepositoryObject> objects) {
        List<RepositoryFileObject> out = new ArrayList<>();
        flattenFiles(objects, out);
        return out;
    }

    private void flattenFiles(List<IRepositoryObject> objects, List<RepositoryFileObject> out) {
        if (objects == null) {
            return;
        }
        for (IRepositoryObject obj : objects) {
            if (obj instanceof RepositoryFileObject) {
                out.add((RepositoryFileObject) obj);
            } else if (obj instanceof RepositoryFolderObject) {
                flattenFiles(((RepositoryFolderObject) obj).getRepoObjects(), out);
            }
        }
    }

    private static final class CalcMember {
        final String name;
        final String dimension;
        final String formula;

        CalcMember(String name, String dimension, String formula) {
            this.name = name;
            this.dimension = dimension;
            this.formula = formula;
        }
    }
}
