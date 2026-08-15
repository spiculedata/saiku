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
package org.saiku.web.rest.resources;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.qmino.miredot.annotations.ReturnType;
import jakarta.servlet.ServletException;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import mondrian.olap.MondrianException;
import mondrian.olap.ResourceLimitExceededException;
import mondrian.olap.ResultLimitExceededException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.olap4j.CellSet;
import org.olap4j.OlapException;
import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.result.ArrowCellsetWriter;
import org.saiku.olap.result.ArrowDrillthroughWriter;
import org.saiku.olap.util.SaikuProperties;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.async.AsyncQueryHandle;
import org.saiku.service.async.AsyncQueryService;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.drillthrough.DrillThroughResult;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.export.JSConverter;
import org.saiku.web.export.PdfReport;
import org.saiku.web.rest.objects.resultset.QueryResult;
import org.saiku.web.rest.util.RestUtil;
import org.saiku.web.util.JdbcCleanup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Saiku Query Endpoints
 */
@Path("/saiku/api/query")
@XmlAccessorType(XmlAccessType.NONE)
public class Query2Resource {

    private static final Logger log = LoggerFactory.getLogger(Query2Resource.class);

    /** Shared, thread-safe ObjectMapper. Per-request instantiation churned through
     *  Jackson's reflection cache on every call; mirror the AiQueryResource pattern. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ThinQueryService thinQueryService;

    // @Autowired
    public void setThinQueryService(ThinQueryService tqs) {
        thinQueryService = tqs;
    }

    private ISaikuRepository repository;

    // @Autowired
    public void setRepository(ISaikuRepository repository) {
        this.repository = repository;
    }

    private AsyncQueryService asyncQueryService;

    public void setAsyncQueryService(AsyncQueryService asyncQueryService) {
        this.asyncQueryService = asyncQueryService;
    }

    public AsyncQueryService getAsyncQueryService() {
        return asyncQueryService;
    }

    /**
     * Resolve the current caller's principal name from the Spring Security
     * context. {@code null} when unauthenticated / no context (mirrors
     * {@link AsyncQueryService#currentPrincipal()} so submit-time and
     * access-time identity are derived the same way).
     */
    private static String currentPrincipal() {
        return AsyncQueryService.currentPrincipal();
    }

    /** True when the current caller holds {@code ROLE_ADMIN}. */
    private static boolean currentUserIsAdmin() {
        try {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                            .getAuthentication();
            if (auth == null) {
                return false;
            }
            for (org.springframework.security.core.GrantedAuthority ga : auth.getAuthorities()) {
                if ("ROLE_ADMIN".equals(ga.getAuthority())) {
                    return true;
                }
            }
        } catch (RuntimeException ignore) {
            // No / broken security context — treat as non-admin.
        }
        return false;
    }

    /**
     * Delete query from the query pool.
     * @summary Delete Query
     * @param queryName The query name
     * @return a HTTP 410(Works) or HTTP 500(Call failed).
     */
    @DELETE
    @Path("/{queryname}")
    public Status deleteQuery(@PathParam("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "\tDELETE");
        }
        try {
            thinQueryService.deleteQuery(queryName);
            return (Status.GONE);
        } catch (Exception e) {
            log.error("Cannot delete query (" + queryName + ")", e);
            throw new WebApplicationException(e);
        }
    }

    /**
     * Create a new Saiku Query.
     * @summary Create query.
     * @param queryName The query name
     * @param fileFormParam The file
     * @param jsonFormParam The json
     * @param formParams The form params
     * @return a query model.
     *
     */
    @POST
    @Produces({"application/json"})
    @Path("/{queryname}")
    public ThinQuery createQuery(
            @PathParam("queryname") String queryName,
            @FormParam("json") String jsonFormParam,
            @FormParam("file") String fileFormParam,
            MultivaluedMap<String, String> formParams)
            throws ServletException {
        try {
            ThinQuery tq;
            String file = fileFormParam, json = jsonFormParam;
            if (formParams != null) {
                json = formParams.containsKey("json") ? formParams.getFirst("json") : jsonFormParam;
                file = formParams.containsKey("file") ? formParams.getFirst("file") : fileFormParam;
            }
            String filecontent = null;
            if (StringUtils.isNotBlank(json)) {
                filecontent = json;
            } else if (StringUtils.isNotBlank(file)) {
                Response f = repository.getResource(file);
                filecontent = new String((byte[]) f.getEntity());
            }
            if (StringUtils.isBlank(filecontent)) {
                throw new SaikuServiceException("Cannot create new query. Empty file content "
                        + StringUtils.isNotBlank(json) + " or read from file:" + file);
            }
            if (thinQueryService.isOldQuery(filecontent)) {
                tq = thinQueryService.convertQuery(filecontent);
            } else {
                tq = MAPPER.readValue(filecontent, ThinQuery.class);
            }

            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "\tPOST\t tq:" + (tq == null) + " file:" + (file));
            }

            if (tq == null) {
                throw new SaikuServiceException("Cannot create blank query (ThinQuery object = null)");
            }
            tq.setName(queryName);

            //			SaikuCube cube = tq.getCube();
            //			if (StringUtils.isNotBlank(xml)) {
            //				String query = ServletUtil.replaceParameters(formParams, xml);
            //				return thinQueryService.createNewOlapQuery(queryName, query);
            //			}
            return thinQueryService.createQuery(tq);
        } catch (Exception e) {
            log.error("Error creating new query", e);
            throw new WebApplicationException(e);
        }
    }

    /**
     *
     * Execute a Saiku Query
     * @summary Execute Query
     * @param tq Thin Query model
     * @return A query result set.
     */
    public static final String ARROW_STREAM_MEDIA_TYPE = "application/vnd.apache.arrow.stream";

    /**
     * Translate an OSSIE ThinQuery's shelf-state into the SQL string the executor would run,
     * without touching the connection or fetching rows. Powers the workbench's "Show SQL"
     * affordance so users can see what the query planner will feed to Calcite. MDX queries
     * are rejected with 400 — the MDX side has its own {@code /getMdx} endpoint.
     */
    @POST
    @Consumes({"application/json"})
    @Produces({"application/json"})
    @Path("/preview-sql")
    public Response previewSql(ThinQuery tq) {
        try {
            if (tq == null || !"OSSIE".equalsIgnoreCase(tq.getQueryType())) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"queryType must be OSSIE\"}")
                        .type("application/json")
                        .build();
            }
            org.saiku.service.ossie.OssieQueryService svc = thinQueryService.getOssieQueryService();
            if (svc == null) {
                return Response.status(Response.Status.NOT_IMPLEMENTED)
                        .entity("{\"error\":\"OssieQueryService not wired\"}")
                        .type("application/json")
                        .build();
            }
            String sql = svc.previewSql(tq.getOssieQueryModel());
            // Return as a JSON envelope so the client can grab it directly without a
            // text-plain content-type dance.
            String body = "{\"sql\":" + jacksonJsonString(sql) + "}";
            return Response.ok(body).type("application/json").build();
        } catch (Exception e) {
            log.error("Cannot preview Ossie SQL", e);
            String msg = e.getMessage() == null ? "internal error" : e.getMessage();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":" + jacksonJsonString(msg) + "}")
                    .type("application/json")
                    .build();
        }
    }

    /** Minimal JSON-string encoder — escapes the four chars the JSON spec requires. */
    private static String jacksonJsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    @POST
    @Consumes({"application/json"})
    @Produces({ARROW_STREAM_MEDIA_TYPE, "application/json"})
    @Path("/execute")
    public Response execute(ThinQuery tq, @Context HttpHeaders headers) {
        try {
            // saiku#861: default ThinQuery.type when the client posts a body
            // with mdx but no explicit type field. The Jackson-deserialized
            // ThinQuery() default ctor leaves type=null; ThinQueryService
            // helpers gate on Type.MDX.equals(type) which silently falls
            // through to the QUERYMODEL path (or worse, the Mondrian
            // executeOlapQuery cast-to-Query that ClassCastExceptions on
            // DRILLTHROUGH).
            if (tq != null && tq.getType() == null) {
                if (tq.getMdx() != null && !tq.getMdx().isBlank()) {
                    tq.setType(ThinQuery.Type.MDX);
                } else if (tq.getQueryModel() != null) {
                    tq.setType(ThinQuery.Type.QUERYMODEL);
                }
            }
            if (thinQueryService.isMdxDrillthrough(tq)) {
                Long start = (new Date()).getTime();
                ResultSet rs = thinQueryService.drillthrough(tq);
                QueryResult rsc = RestUtil.convert(rs);
                rsc.setQuery(tq);
                Long runtime = (new Date()).getTime() - start;
                rsc.setRuntime(runtime.intValue());
                return Response.ok(rsc).type(MediaType.APPLICATION_JSON).build();
            }

            if (clientPrefersArrow(headers)) {
                return executeArrow(tq);
            }

            QueryResult qr = RestUtil.convert(thinQueryService.execute(tq));
            // OSSIE queries don't register a QueryContext (the Ossie service goes straight
            // from JDBC to CellDataSet). Skip the ThinQuery attachment in that case — the
            // client already has the shelf state locally.
            org.saiku.service.util.QueryContext ctx = thinQueryService.getContext(tq.getName());
            if (ctx != null) {
                qr.setQuery(ctx.getOlapQuery());
            } else {
                qr.setQuery(tq);
            }
            return Response.ok(qr).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            log.error("Cannot execute query (" + tq + ")", e);
            return queryFailure(e);
        }
    }

    /**
     * Turn a query failure into a response carrying the right HTTP status.
     *
     * <p>saiku#1865: every failure used to come back as {@code 200 OK} with the message tucked in
     * the envelope's {@code error} field. That is invisible to anything that reasons about
     * transport — proxies, retry middleware, monitoring, and any API client that (reasonably)
     * treats 2xx as success. The body shape is deliberately UNCHANGED: it is still a
     * {@link QueryResult} with {@code error} populated, because that is what the UI renders inline
     * where the grid would be, and what existing clients parse.
     *
     * <p>Classification is by exception type, not by sniffing messages. A {@link SaikuOlapException}
     * anywhere in the cause chain means the request named something that could not be resolved — an
     * unknown connection, cube, or member — which the caller can fix, so 400. Anything else is ours
     * and reports 500.
     */
    private Response queryFailure(Exception e) {
        String error = ExceptionUtils.getRootCauseMessage(e);
        Status status = isClientError(e) ? Status.BAD_REQUEST : Status.INTERNAL_SERVER_ERROR;
        return Response.status(status)
                .entity(new QueryResult(error))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * True when the cause chain describes something the CALLER got wrong, rather than something
     * that went wrong on our side.
     *
     * <p>Three signals, all by exception type — deliberately not by message text, which is
     * localised, version-dependent and the first thing to drift:
     *
     * <ul>
     *   <li>{@link SaikuOlapException} — Saiku could not resolve a connection or a cube.
     *   <li>A {@link MondrianException} with no {@link SQLException} beneath it — the query failed
     *       to parse or resolve, so it never reached the database. A typo'd measure lands here, and
     *       it is by far the most common query failure there is.
     *   <li>…except the resource-limit and cancellation subclasses, which mean the query was
     *       perfectly valid and we could not finish it. Those are ours.
     * </ul>
     *
     * <p>{@link OlapException} extends {@link SQLException}, so it is excluded from the
     * reached-the-database test — otherwise every olap4j wrapper would look like a backend fault.
     */
    private static boolean isClientError(Throwable t) {
        boolean sawMondrianFailure = false;
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SaikuOlapException) {
                return true;
            }
            // ResultLimitExceededException is the abstract base of the whole family —
            // MemoryLimitExceededException, QueryTimeoutException and QueryCanceledException all
            // extend it — so one check covers them.
            if (c instanceof ResultLimitExceededException || c instanceof ResourceLimitExceededException) {
                return false;
            }
            if (c instanceof SQLException && !(c instanceof OlapException)) {
                return false; // the query reached the warehouse and the warehouse failed
            }
            if (c instanceof MondrianException) {
                sawMondrianFailure = true;
            }
        }
        return sawMondrianFailure;
    }

    private boolean clientPrefersArrow(HttpHeaders headers) {
        if (headers == null) {
            return false;
        }
        List<MediaType> accept = headers.getAcceptableMediaTypes();
        if (accept == null || accept.isEmpty()) {
            return false;
        }
        // Jersey returns Accept types sorted by q-value descending. Treat the first
        // matching concrete type as the winner — if Arrow appears before JSON, the
        // client asked for it.
        for (MediaType mt : accept) {
            String full = mt.getType() + "/" + mt.getSubtype();
            if (ARROW_STREAM_MEDIA_TYPE.equalsIgnoreCase(full)) {
                return true;
            }
            if (MediaType.APPLICATION_JSON.equalsIgnoreCase(full)) {
                return false;
            }
        }
        return false;
    }

    private Response executeArrow(final ThinQuery tq) throws Exception {
        // Cache-first path: on hit we stream the stored Arrow bytes straight
        // back without touching Mondrian. On miss the service executes,
        // encodes, caches, and returns the fresh bytes.
        final org.saiku.service.cache.SaikuQueryCache.CachedQueryResult r = thinQueryService.executeCached(tq);
        final byte[] bytes = r.arrowBytes;
        StreamingOutput body = new StreamingOutput() {
            @Override
            public void write(java.io.OutputStream output) throws java.io.IOException {
                output.write(bytes);
            }
        };
        return Response.ok(body, ARROW_STREAM_MEDIA_TYPE)
                .header("X-Saiku-Cache", r.cacheHit ? "hit" : "miss")
                .header("X-Saiku-Runtime-Ms", String.valueOf(r.runtimeMs))
                .build();
    }

    // ===== Async execute / status / result / cancel ==========================

    /**
     * Kick off an async query execution.
     * @summary Submit a query for async execution.
     * @param tq The thin query
     * @return 202 Accepted + { queryId, status: "PENDING" }
     */
    @POST
    @Consumes({"application/json"})
    @Produces({"application/json"})
    @Path("/execute-async")
    public Response executeAsync(ThinQuery tq) {
        if (asyncQueryService == null) {
            return Response.serverError()
                    .entity("AsyncQueryService not configured")
                    .build();
        }
        try {
            // Capture the caller's request attributes so the async worker
            // thread can resolve session-scoped beans (e.g. thinQueryBean).
            RequestAttributes requestAttributes = null;
            try {
                requestAttributes = RequestContextHolder.currentRequestAttributes();
            } catch (IllegalStateException ignore) {
                // No request bound — leave null; submit() falls back to legacy path.
            }
            AsyncQueryHandle h = asyncQueryService.submit(tq, requestAttributes);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("queryId", h.getId());
            body.put("status", h.getStatus().name());
            return Response.status(Status.ACCEPTED)
                    .entity(body)
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            log.error("Cannot submit async query", e);
            return Response.serverError()
                    .entity(ExceptionUtils.getRootCauseMessage(e))
                    .build();
        }
    }

    /**
     * Poll async query status.
     * @summary Async status.
     * @param id The async handle id
     * @return { id, status, errorMessage? }
     */
    @GET
    @Produces({"application/json"})
    @Path("/async/{id}/status")
    public Response asyncStatus(@PathParam("id") String id) {
        if (asyncQueryService == null) {
            return Response.status(Status.NOT_FOUND).build();
        }
        AsyncQueryHandle h = asyncQueryService.getOwned(id, currentPrincipal(), currentUserIsAdmin());
        if (h == null) {
            // Unknown id OR not owned by this caller — 404 on both so a
            // non-owner can't use the response code as an id-existence oracle.
            return Response.status(Status.NOT_FOUND).build();
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", h.getId());
        body.put("status", h.getStatus().name());
        if (h.getErrorMessage() != null) {
            body.put("errorMessage", h.getErrorMessage());
        }
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Fetch a completed async result. Content-negotiates Arrow vs JSON. Only
     * valid once status == DONE; otherwise 409 CONFLICT.
     * @summary Async result.
     * @param id The async handle id
     * @return Arrow stream or JSON QueryResult
     */
    @GET
    @Produces({ARROW_STREAM_MEDIA_TYPE, "application/json"})
    @Path("/async/{id}/result")
    public Response asyncResult(@PathParam("id") String id, @Context HttpHeaders headers) {
        if (asyncQueryService == null) {
            return Response.status(Status.NOT_FOUND).build();
        }
        AsyncQueryHandle h = asyncQueryService.getOwned(id, currentPrincipal(), currentUserIsAdmin());
        if (h == null) {
            // Unknown id OR not owned by this caller — 404 on both.
            return Response.status(Status.NOT_FOUND).build();
        }
        if (h.getStatus() == AsyncQueryHandle.Status.FAILED) {
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity(new QueryResult(h.getErrorMessage() == null ? "failed" : h.getErrorMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        if (h.getStatus() != AsyncQueryHandle.Status.DONE) {
            // PENDING, RUNNING, CANCELLED — not ready.
            return Response.status(Status.CONFLICT)
                    .entity(h.getStatus().name())
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
        final CellSet cellSet = asyncQueryService.result(id);
        if (cellSet == null) {
            return Response.status(Status.NOT_FOUND).build();
        }
        final ThinQuery tqAfter = h.getQuery();

        if (clientPrefersArrow(headers)) {
            StreamingOutput body = new StreamingOutput() {
                @Override
                public void write(java.io.OutputStream output) throws java.io.IOException {
                    new ArrowCellsetWriter().write(cellSet, tqAfter, output);
                }
            };
            return Response.ok(body, ARROW_STREAM_MEDIA_TYPE).build();
        }
        CellDataSet cds = org.saiku.olap.util.OlapResultSetUtil.cellSet2Matrix(cellSet);
        QueryResult qr = RestUtil.convert(cds);
        qr.setQuery(tqAfter);
        return Response.ok(qr).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Cancel a running async query.
     * @summary Async cancel.
     * @param id The async handle id
     * @return 204 No Content on success, 404 otherwise.
     */
    @DELETE
    @Path("/async/{id}/cancel")
    public Response asyncCancel(@PathParam("id") String id) {
        if (asyncQueryService == null) {
            return Response.status(Status.NOT_FOUND).build();
        }
        boolean cancelled = asyncQueryService.cancelOwned(id, currentPrincipal(), currentUserIsAdmin());
        if (!cancelled) {
            // Unknown id OR not owned by this caller — 404 on both.
            return Response.status(Status.NOT_FOUND).build();
        }
        return Response.status(Status.NO_CONTENT).build();
    }

    /**
     * Cancel a running query.
     * @summary Cancel Query.
     * @param queryName The query name
     * @return A 410 on success
     */
    @DELETE
    @Path("/{queryname}/cancel")
    public Response cancel(@PathParam("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "\tDELETE");
        }
        try {
            thinQueryService.cancel(queryName);
            return Response.ok(Status.GONE).build();
        } catch (Exception e) {
            log.error("Cannot cancel query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(
                    Response.serverError().entity(error).build());
        }
    }

    /**
     * Enrich a thin query model
     * @summary Enrich thin query.
     * @param tq The thin query
     * @return An updated thin query.
     */
    @POST
    @Consumes({"application/json"})
    @Path("/enrich")
    public ThinQuery enrich(ThinQuery tq) {
        try {
            return thinQueryService.updateQuery(tq);
        } catch (Exception e) {
            log.error("Cannot enrich query (" + tq + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(
                    Response.serverError().entity(error).build());
        }
    }

    /**
     * Get level members from a query.
     * @summary Get level members.
     * @param queryName The query name
     * @param hierarchyName The hierarchy name
     * @param levelName The level name
     * @param result Use the current result
     * @param searchString The search string
     * @param searchLimit The search limit
     * @return
     */
    @GET
    @Produces({"application/json"})
    @Path("/{queryname}/result/metadata/hierarchies/{hierarchy}/levels/{level}")
    public Response getLevelMembers(
            @PathParam("queryname") String queryName,
            @PathParam("hierarchy") String hierarchyName,
            @PathParam("level") String levelName,
            @QueryParam("result") @DefaultValue("true") boolean result,
            @QueryParam("search") String searchString,
            @QueryParam("searchlimit") @DefaultValue("-1") int searchLimit) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t"
                    + "\t/query/" + queryName + "/result/metadata"
                    + "/hierarchies/" + hierarchyName + "/levels/" + levelName + "\tGET");
        }
        try {
            List<SimpleCubeElement> members = thinQueryService.getResultMetadataMembers(
                    queryName, result, hierarchyName, levelName, searchString, searchLimit);
            // saiku#863: null means the query name isn't in this session's
            // per-user ThinQueryService context — either no /execute happened
            // for this query yet, or the request lost session continuity
            // (e.g. Basic auth with no shared cookie jar). Surface a typed
            // 404 envelope rather than the JAX-RS default 204 No Content
            // (which the SPA can't distinguish from "level has no members").
            if (members == null) {
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("status", "NOT_FOUND");
                body.put("field", "queryname");
                body.put("value", queryName);
                body.put(
                        "error",
                        "No live query named '" + queryName
                                + "' in this session. Re-issue POST /api/query/execute with this name to seed the per-session context, or check the session cookie is being sent.");
                return Response.status(Response.Status.NOT_FOUND)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(body)
                        .build();
            }
            return Response.ok(members).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(
                    Response.serverError().entity(error).build());
        }
    }

    /**
     * Query export to excel.
     * @summary Excel export
     * @param queryName The query name
     * @return A response containing an excel spreadsheet.
     */
    @GET
    @Produces({"application/vnd.ms-excel"})
    @Path("/{queryname}/export/xls")
    public Response getQueryExcelExport(@PathParam("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/xls/\tGET");
        }
        return getQueryExcelExport(queryName, "flattened", null);
    }

    /**
     * Query export to excel
     * @summary Excel export
     * @param queryName The query
     * @param format The cellset format
     * @param name The export name
     * @return A response containing and excel spreadsheet.
     */
    @GET
    @Produces({"application/vnd.ms-excel"})
    @Path("/{queryname}/export/xls/{format}")
    public Response getQueryExcelExport(
            @PathParam("queryname") String queryName,
            @PathParam("format") @DefaultValue("flattened") String format,
            @QueryParam("exportname") @DefaultValue("") String name) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/xls/" + format + "\tGET");
        }
        try {
            byte[] doc = thinQueryService.getExport(queryName, "xls", format);
            if (name == null || name.equals("")) {
                name = SaikuProperties.webExportExcelName + "." + SaikuProperties.webExportExcelFormat;
            }
            return Response.ok(doc, MediaType.APPLICATION_OCTET_STREAM)
                    .header("content-disposition", "attachment; filename = " + name)
                    .header("content-length", doc.length)
                    .build();
        } catch (Exception e) {
            log.error("Cannot get excel for query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(
                    Response.serverError().entity(error).build());
        }
    }

    /**
     * Get CSV export of a query.
     * @summary CSV Export.
     * @param queryName The query name
     * @return A response containing a CSV file
     */
    @GET
    @Produces({"text/csv"})
    @Path("/{queryname}/export/csv")
    public Response getQueryCsvExport(@PathParam("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/csv\tGET");
        }
        return getQueryCsvExport(queryName, "flattened", null);
    }

    /**
     * Get CSV export of a query.
     * @summary CSV Export.
     * @param queryName The query name
     * @param format The cell set format
     * @param name The export name
     * @return A response containing a CSV file
     */
    @GET
    @Produces({"text/csv"})
    @Path("/{queryname}/export/csv/{format}")
    public Response getQueryCsvExport(
            @PathParam("queryname") String queryName,
            @PathParam("format") String format,
            @QueryParam("exportname") @DefaultValue("") String name) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/csv/" + format + "\tGET");
        }
        try {
            byte[] doc = thinQueryService.getExport(queryName, "csv", format);
            if (name == null || name.equals("")) {
                name = SaikuProperties.webExportCsvName;
            }

            return Response.ok(doc, MediaType.APPLICATION_OCTET_STREAM)
                    .header("content-disposition", "attachment; filename = " + name + ".csv")
                    .header("content-length", doc.length)
                    .build();
        } catch (Exception e) {
            log.error("Cannot get csv for query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(
                    Response.serverError().entity(error).build());
        }
    }

    /**
     * Zoom into a query result table.
     * @summary Zoom in.
     * @param queryName The query name
     * @param positionListString The zoom position
     * @return A new thin query model with a reduced table.
     */
    @POST
    @Consumes("application/x-www-form-urlencoded")
    @Path("/{queryname}/zoomin")
    public ThinQuery zoomIn(
            @PathParam("queryname") String queryName, @FormParam("selections") String positionListString) {
        try {

            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/zoomIn\tPUT");
            }
            List<List<Integer>> realPositions = new ArrayList<>();
            if (StringUtils.isNotBlank(positionListString)) {
                String[] positions = MAPPER.readValue(
                        positionListString, MAPPER.getTypeFactory().constructArrayType(String.class));
                if (positions != null && positions.length > 0) {
                    for (String position : positions) {
                        String[] rPos = position.split(":");
                        List<Integer> cellPosition = new ArrayList<>();

                        for (String p : rPos) {
                            Integer pInt = Integer.parseInt(p);
                            cellPosition.add(pInt);
                        }
                        realPositions.add(cellPosition);
                    }
                }
            }
            return thinQueryService.zoomIn(queryName, realPositions);

        } catch (Exception e) {
            log.error("Cannot zoom in on query (" + queryName + ")", e);
            throw new WebApplicationException(e);
        }
    }

    /**
     * Drill through on the query result set.
     * @summary Drill through
     * @param queryName The query name
     * @param maxrows The max rows returned
     * @param position The position
     * @param returns The returned dimensions and levels
     * @return A query result set.
     */
    // Phase 5 decision: drillthrough intentionally does NOT go through the
    // SaikuQueryCache (Task 5). The cache keys for execute are MDX+params;
    // drillthrough also depends on cell position, maxrows, and returns list,
    // and invalidation semantics differ. If/when we add caching here it
    // needs its own key space and TTL policy.
    @GET
    @Produces({ARROW_STREAM_MEDIA_TYPE, "application/json"})
    @Path("/{queryname}/drillthrough")
    public Response drillthrough(
            @PathParam("queryname") String queryName,
            @QueryParam("maxrows") @DefaultValue("100") Integer maxrows,
            @QueryParam("position") String position,
            @QueryParam("returns") String returns,
            @Context HttpHeaders headers) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/drillthrough\tGET");
        }
        boolean arrow = clientPrefersArrow(headers);
        ResultSet rs = null;
        try {
            Long start = (new Date()).getTime();
            DrillThroughResult dtr = null;
            if (position == null) {
                rs = thinQueryService.drillthrough(queryName, maxrows, returns);
            } else {
                String[] positions = position.split(":");
                List<Integer> cellPosition = new ArrayList<>();
                for (String p : positions) {
                    cellPosition.add(Integer.parseInt(p));
                }
                dtr = thinQueryService.drillthroughWithCaptions(queryName, cellPosition, maxrows, returns);
                rs = dtr.getResultSet();
            }

            if (arrow) {
                return drillthroughArrow(rs, dtr, start);
            }

            QueryResult rsc;
            if (dtr != null) {
                rsc = RestUtil.convert(dtr);
            } else {
                rsc = RestUtil.convert(rs);
            }
            Long runtime = (new Date()).getTime() - start;
            rsc.setRuntime(runtime.intValue());
            return Response.ok(rsc).type(MediaType.APPLICATION_JSON).build();

        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
            // saiku#1865 — same status classification as /execute; see queryFailure.
            return queryFailure(e);

        } finally {
            // Arrow path materialises rows eagerly into the ByteArrayOutputStream
            // before the response body is written, so it is safe to close the
            // ResultSet here for both JSON and Arrow branches.
            JdbcCleanup.closeQuietly(rs);
        }
    }

    private Response drillthroughArrow(ResultSet rs, DrillThroughResult dtr, long startMs) throws Exception {
        String[] captions = extractCaptions(rs, dtr);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ArrowDrillthroughWriter.WriteResult wr = new ArrowDrillthroughWriter().write(rs, captions, baos);
        final byte[] bytes = baos.toByteArray();
        long runtime = Math.max(0L, System.currentTimeMillis() - startMs);
        StreamingOutput body = new StreamingOutput() {
            @Override
            public void write(java.io.OutputStream output) throws java.io.IOException {
                output.write(bytes);
            }
        };
        return Response.ok(body, ARROW_STREAM_MEDIA_TYPE)
                .header("X-Saiku-Runtime-Ms", String.valueOf(runtime))
                .header("X-Saiku-Row-Count", String.valueOf(wr.rowCount))
                .build();
    }

    private static String[] extractCaptions(ResultSet rs, DrillThroughResult dtr) {
        if (dtr == null) {
            return null; // let the writer fall back to JDBC column labels
        }
        int width;
        try {
            width = rs.getMetaData().getColumnCount();
        } catch (SQLException ex) {
            return dtr.getSimpleHeaders();
        }
        // Prefer rich cellHeaders (last row = leaf captions) when present,
        // else the flat simpleHeaders array populated from the "returns" list.
        AbstractBaseCell[][] cellHeaders = dtr.getCellHeaders();
        if (cellHeaders != null && cellHeaders.length > 0) {
            AbstractBaseCell[] last = cellHeaders[cellHeaders.length - 1];
            if (last != null && last.length >= width) {
                String[] out = new String[width];
                for (int i = 0; i < width; i++) {
                    AbstractBaseCell c = last[i];
                    if (c instanceof MemberCell) {
                        String cap = ((MemberCell) c).getFormattedValue();
                        out[i] = cap != null ? cap : "";
                    } else {
                        out[i] = "";
                    }
                }
                return out;
            }
        }
        return dtr.getSimpleHeaders();
    }

    /**
     * Export the drill through to a CSV file for further analysis
     * @summary Export to CSV
     * @param queryName The query name
     * @param maxrows The max rows
     * @param position The position
     * @param returns The returned dimensions and levels
     * @return A response containing a CSV file
     */
    @GET
    @Produces({"text/csv"})
    @Path("/{queryname}/drillthrough/export/csv")
    public Response getDrillthroughExport(
            @PathParam("queryname") String queryName,
            @QueryParam("maxrows") @DefaultValue("100") Integer maxrows,
            @QueryParam("position") String position,
            @QueryParam("returns") String returns) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/drillthrough/export/csv (maxrows:" + maxrows + " position"
                    + position + ")\tGET");
        }
        ResultSet rs = null;

        try {
            if (position == null) {
                rs = thinQueryService.drillthrough(queryName, maxrows, returns);
            } else {
                String[] positions = position.split(":");
                List<Integer> cellPosition = new ArrayList<>();

                for (String p : positions) {
                    Integer pInt = Integer.parseInt(p);
                    cellPosition.add(pInt);
                }

                rs = thinQueryService.drillthrough(queryName, cellPosition, maxrows, returns);
            }
            byte[] doc = thinQueryService.exportResultSetCsv(rs);
            String name = SaikuProperties.webExportCsvName;
            return Response.ok(doc, MediaType.APPLICATION_OCTET_STREAM)
                    .header("content-disposition", "attachment; filename = " + name + "-drillthrough.csv")
                    .header("content-length", doc.length)
                    .build();

        } catch (Exception e) {
            log.error("Cannot export drillthrough query (" + queryName + ")", e);
            return Response.serverError().build();
        } finally {
            JdbcCleanup.closeQuietly(rs);
        }
    }

    /**
     * Export PDF with chart
     * @summary Export PDF with Chart.
     * @param queryName The query.
     * @param svg The SVG string
     * @return A response with a PDF file
     */
    @POST
    @Produces({"application/pdf"})
    @Path("/{queryname}/export/pdf")
    public Response exportPdfWithChart(
            @PathParam("queryname") String queryName, @PathParam("svg") @DefaultValue("") String svg) {
        return exportPdfWithChartAndFormat(queryName, null, svg, null);
    }

    /**
     * Export table to PDF.
     * @summary Export to PDF.
     * @param queryName The query name
     * @return A response with a PDF export.
     */
    @GET
    @Produces({"application/pdf"})
    @Path("/{queryname}/export/pdf")
    public Response exportPdf(@PathParam("queryname") String queryName) {
        return exportPdfWithChartAndFormat(queryName, null, null, null);
    }

    /**
     * Export to PDF with cellset format.
     * @summary Export with format
     * @param queryName The query
     * @param format The cellset format
     * @param name The name of the export.
     * @return A response with a PDF
     */
    @GET
    @Produces({"application/pdf"})
    @Path("/{queryname}/export/pdf/{format}")
    public Response exportPdfWithFormat(
            @PathParam("queryname") String queryName,
            @PathParam("format") String format,
            @QueryParam("exportname") String name) {
        return exportPdfWithChartAndFormat(queryName, format, null, name);
    }

    /**
     * Export PDF with chart and cellset format.
     * @summary Export to PDF with chart and cellset format
     * @param queryName The query name
     * @param format The cell set format
     * @param svg The SVG
     * @param name The export name
     * @return A response with a PDF contained.
     */
    @POST
    @Produces({"application/pdf"})
    @Path("/{queryname}/export/pdf/{format}")
    public Response exportPdfWithChartAndFormat(
            @PathParam("queryname") String queryName,
            @PathParam("format") String format,
            @FormParam("svg") @DefaultValue("") String svg,
            @QueryParam("name") String name) {

        try {
            CellDataSet cellData = thinQueryService.getFormattedResult(queryName, format);
            QueryResult queryResult = RestUtil.convert(cellData);
            // The download filename — falls back to "export" so users
            // who didn't pass ?name= still get a sensible default.
            if (name == null || name.equals("")) {
                name = "export";
            }
            PdfReport pdf = new PdfReport();
            // Pass the document name through so it lands as the PDF's
            // running page header (xhtml2fo.xsl reads <head><title>).
            byte[] doc = pdf.createPdf(queryResult, svg, name);
            return Response.ok(doc)
                    .type("application/pdf")
                    .header("content-disposition", "attachment; filename = " + name + ".pdf")
                    .header("content-length", doc.length)
                    .build();
        } catch (Exception e) {
            log.error("Error exporting query to  PDF", e);
            return Response.serverError()
                    .entity(e.getMessage())
                    .status(Status.INTERNAL_SERVER_ERROR)
                    .build();
        }
    }

    /**
     * Get HTML export
     * @summary HTML export
     * @param queryname The query name
     * @param format The cellset format
     * @param css The css stylesheet
     * @param tableonly Export table only or chart as well
     * @param wrapcontent Wrap content
     * @return A response with a HTML export.
     */
    @GET
    @Produces({"text/html"})
    @Path("/{queryname}/export/html")
    @ReturnType("java.lang.String")
    public Response exportHtml(
            @PathParam("queryname") String queryname,
            @QueryParam("format") String format,
            @QueryParam("css") @DefaultValue("false") Boolean css,
            @QueryParam("tableonly") @DefaultValue("false") Boolean tableonly,
            @QueryParam("wrapcontent") @DefaultValue("true") Boolean wrapcontent) {
        ThinQuery tq = thinQueryService.getContext(queryname).getOlapQuery();
        return exportHtml(tq, format, css, tableonly, wrapcontent);
    }

    /**
     * Get HTML export
     * @summary HTML export
     * @param tq The current thin query model
     * @param format The cellset format
     * @param css The css stylesheet
     * @param tableonly Export table only or chart as well
     * @param wrapcontent Wrap content
     * @return A response with a HTML export.
     */
    @POST
    @Produces({"text/html"})
    @Path("/export/html")
    @ReturnType("java.lang.String")
    public Response exportHtml(
            ThinQuery tq,
            @QueryParam("format") String format,
            @QueryParam("css") @DefaultValue("false") Boolean css,
            @QueryParam("tableonly") @DefaultValue("false") Boolean tableonly,
            @QueryParam("wrapcontent") @DefaultValue("true") Boolean wrapcontent) {

        try {
            CellDataSet cs;
            if (StringUtils.isNotBlank(format)) {
                cs = thinQueryService.execute(tq, format);
            } else {
                cs = thinQueryService.execute(tq);
            }
            QueryResult qr = RestUtil.convert(cs);
            String content = JSConverter.convertToHtml(qr, wrapcontent);
            String html = "";
            if (!tableonly) {
                html +=
                        "<!DOCTYPE html><html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n";
                if (css) {
                    html += "<style>\n";
                    // try-with-resources so the bundled CSS stream is always closed (saiku#1191).
                    try (InputStream is = JSConverter.class.getResourceAsStream("saiku.table.full.css")) {
                        html += IOUtils.toString(is);
                    }
                    html += "</style>\n";
                }
                html += "</head>\n<body><div class='workspace_results'>\n";
            }
            html += content;
            if (!tableonly) {
                html += "\n</div></body></html>";
            }
            return Response.ok(html).build();
        } catch (Exception e) {
            log.error("Error exporting query to  HTML", e);
            return Response.serverError()
                    .entity(e.getMessage())
                    .status(Status.INTERNAL_SERVER_ERROR)
                    .build();
        }
    }

    /**
     * Drill across on a result set
     * @summary Drill across
     * @param queryName The query name
     * @param position The drill position
     * @param returns The dimensions and levels returned
     * @return The new thin query object.
     */
    @POST
    @Produces({"application/json"})
    @Path("/{queryname}/drillacross")
    public ThinQuery drillacross(
            @PathParam("queryname") String queryName,
            @FormParam("position") String position,
            @FormParam("drill") String returns) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/drillacross\tPOST");
        }

        try {
            String[] positions = position.split(":");
            List<Integer> cellPosition = new ArrayList<>();
            for (String p : positions) {
                Integer pInt = Integer.parseInt(p);
                cellPosition.add(pInt);
            }
            CollectionType ct = MAPPER.getTypeFactory().constructCollectionType(ArrayList.class, String.class);
            JavaType st = MAPPER.getTypeFactory().uncheckedSimpleType(String.class);

            Map<String, List<String>> levels =
                    MAPPER.readValue(returns, MAPPER.getTypeFactory().constructMapType(Map.class, st, ct));
            return thinQueryService.drillacross(queryName, cellPosition, levels);

        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(
                    Response.serverError().entity(error).build());
        }
    }

    public ThinQueryService getThinQueryService() {
        return thinQueryService;
    }
}
