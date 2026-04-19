package org.saiku.web.rest.resources;

import java.util.List;
import mondrian.olap.MondrianServer;
import mondrian.olap.MondrianServer.MondrianVersion;
import mondrian.server.monitor.ConnectionInfo;
import mondrian.server.monitor.Monitor;
import mondrian.server.monitor.ServerInfo;
import mondrian.server.monitor.StatementInfo;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mondrian Server Info and Stats Endpoints.
 */
@Component
@RestController
@RequestMapping("/saiku/statistics")
public class StatisticsResource {

    /**
     * Get Mondrian Stats
     * @summary Get Mondrian stats
     * @return A selection of Mondrian stats.
     */
    @GetMapping(path = "/mondrian", produces = MediaType.APPLICATION_JSON_VALUE)
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
    @GetMapping(path = "/mondrian/server", produces = MediaType.APPLICATION_JSON_VALUE)
    public ServerInfo getMondrianServer() {
        MondrianServer mondrianServer = MondrianServer.forId(null);
        if (mondrianServer != null) {
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
    @GetMapping(path = "/mondrian/server/version", produces = MediaType.APPLICATION_JSON_VALUE)
    public MondrianVersion getMondrianServerVersion() {
        MondrianServer mondrianServer = MondrianServer.forId(null);
        if (mondrianServer != null) {
            return mondrianServer.getVersion();
        }
        return null;
    }
}
