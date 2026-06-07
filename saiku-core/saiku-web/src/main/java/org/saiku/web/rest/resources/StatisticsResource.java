package org.saiku.web.rest.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import java.util.List;
import mondrian.olap.MondrianServer;
import mondrian.olap.MondrianServer.MondrianVersion;
import mondrian.server.monitor.ConnectionInfo;
import mondrian.server.monitor.Monitor;
import mondrian.server.monitor.ServerInfo;
import mondrian.server.monitor.StatementInfo;

/**
 * Mondrian Server Info and Stats Endpoints.
 *
 * <p>saiku#1165 hardening: these endpoints expose <b>server-global</b> Mondrian
 * state — every open OLAP connection and every in-flight MDX/SQL statement
 * across all users' sessions ({@code monitor.getConnections()} /
 * {@code getStatements()}). That is operator-only diagnostic data, so the whole
 * resource is restricted to {@code ROLE_ADMIN} (enforced by the registered
 * {@code RolesAllowedDynamicFeature}, same as {@code AdminResource}). Previously
 * it was reachable by any authenticated {@code ROLE_USER}, leaking other
 * tenants' query text and activity.
 */
@Path("/saiku/statistics")
@RolesAllowed("ROLE_ADMIN")
public class StatisticsResource {

    //	StringWriter sqlWriter = new StringWriter();
    //	StringWriter mdxWriter = new StringWriter();
    //	StringWriter profileWriter = new StringWriter();
    //	StringWriter saikuWriter = new StringWriter();
    //
    //	public StatisticsResource() {
    //		setupLog("mondrian.sql", "DEBUG", sqlWriter);
    //		setupLog("mondrian.mdx", "DEBUG", mdxWriter);
    //		setupLog("mondrian.profile", "DEBUG", profileWriter);
    //		setupLog("org.saiku.service", "INFO", saikuWriter);
    //		System.out.println("##########################SETUP LOG");
    //
    //
    //	}

    //	private void setupLog(String category, String level, StringWriter writer) {
    //		Logger pkgLogger = Logger.getRootLogger().getLoggerRepository().getLogger(category);
    //		pkgLogger.setLevel(Level.toLevel(level));
    //		WriterAppender appender = new WriterAppender(new PatternLayout("%d{ISO8601} %p - %m%n"), writer);
    //		appender.setName("CONSOLE_APPENDER");
    //		appender.setThreshold(Level.ERROR);
    //		pkgLogger.addAppender(appender);
    //		Logger.getRootLogger().addAppender(appender);
    //	}

    /**
     * Get Mondrian Stats
     * @summary Get Mondrian stats
     * @return A selection of Mondrian stats.
     */
    @GET
    @Produces({"application/json"})
    @Path("/mondrian")
    public MondrianStats getMondrianStats() {

        MondrianServer mondrianServer = MondrianServer.forId(null);
        if (mondrianServer != null) {
            MondrianVersion mv = mondrianServer.getVersion();

            final Monitor monitor = mondrianServer.getMonitor();
            final ServerInfo server = monitor.getServer();

            int statementCurrentlyOpenCount = 0; // server.statementCurrentlyOpenCount();
            int connectionCurrentlyOpenCount = 0; // server.connectionCurrentlyOpenCount();
            int sqlStatementCurrentlyOpenCount = 0; // server.sqlStatementCurrentlyOpenCount();
            int statementCurrentlyExecutingCount = 0; // server.statementCurrentlyExecutingCount();
            float avgCellDimensionality = ((float) server.cellCoordinateCount / (float) server.cellCount);

            final List<ConnectionInfo> connections = monitor.getConnections();
            final List<StatementInfo> statements = monitor.getStatements();

            return new MondrianStats(
                    server,
                    mv,
                    statementCurrentlyOpenCount,
                    connectionCurrentlyOpenCount,
                    sqlStatementCurrentlyOpenCount,
                    statementCurrentlyExecutingCount,
                    avgCellDimensionality,
                    connections,
                    statements);
        }

        return null;
    }

    /**
     * Get Mondrian Server Info
     * @summary Get Mondrian Info
     * @return Server Info
     */
    @GET
    @Produces({"application/json"})
    @Path("/mondrian/server")
    public ServerInfo getMondrianServer() {
        MondrianServer mondrianServer = MondrianServer.forId(null);
        if (mondrianServer != null) {
            MondrianVersion mv = mondrianServer.getVersion();

            final Monitor monitor = mondrianServer.getMonitor();
            return monitor.getServer();
        }
        return null;
    }

    /**
     * Get Mondrian Server Info
     * @summary Get Mondrian Info
     * @return Server Info
     */
    @GET
    @Produces({"application/json"})
    @Path("/mondrian/server/version")
    public MondrianVersion getMondrianServerVersion() {
        MondrianServer mondrianServer = MondrianServer.forId(null);
        if (mondrianServer != null) {

            return mondrianServer.getVersion();
        }
        return null;
    }
    //	@GET
    //	@Produces({"text/plain" })
    //	@Path("/log/sql")
    //	public String getSqlLog() {
    //		return sqlWriter.toString();
    //	}
    //
    //	@GET
    //	@Produces({"text/plain" })
    //	@Path("/log/mdx")
    //	public String getMdxLog() {
    //		return mdxWriter.toString();
    //	}
    //
    //	@GET
    //	@Produces({"text/plain" })
    //	@Path("/log/profile")
    //	public String getProfileLog() {
    //		return profileWriter.toString();
    //	}
    //
    //	@GET
    //	@Produces({"text/plain" })
    //	@Path("/log/saiku")
    //	public String getSaikuLog() {
    //		return saikuWriter.toString();
    //	}

}
