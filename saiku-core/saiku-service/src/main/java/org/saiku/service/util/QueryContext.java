package org.saiku.service.util;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.olap4j.CellSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.util.exception.SaikuServiceException;

public class QueryContext {

    private final String id = UUID.randomUUID().toString();

    public enum Type {
        OLAP
    }

    public enum ObjectKey {
        CONTEXT_TYPE,
        CONNECTION,
        RESULT,
        CACHE,
        STATEMENT,
        QUERY
    }

    private final Type type;

    private final Map<ObjectKey, Object> objects = new HashMap<>();

    public QueryContext(Type type, ISaikuQuery query) {
        this.type = type;
        // this.objects.put(ObjectKey.CONNECTION, con);
        this.objects.put(ObjectKey.QUERY, query);
    }

    public String id() {
        return this.id;
    }

    public Type getType() {
        return this.type;
    }

    private Connection getConnection() {
        if (objects.containsKey(ObjectKey.CONNECTION)) {
            return (Connection) objects.get(ObjectKey.CONNECTION);
        }
        throw new SaikuServiceException("Context: " + id + " does not contain a connection object");
    }

    public ThinQuery getOlapQuery() {
        if (objects.containsKey(ObjectKey.QUERY)) {
            Object o = objects.get(ObjectKey.QUERY);
            if (o instanceof ThinQuery) {
                return (ThinQuery) objects.get(ObjectKey.QUERY);
            }
        }
        throw new SaikuServiceException("Context: " + id + " does not contain a query object");
    }

    public CellSet getOlapResult() {
        if (objects.containsKey(ObjectKey.RESULT)) {
            Object o = objects.get(ObjectKey.RESULT);
            if (o instanceof CellSet) {
                return (CellSet) objects.get(ObjectKey.RESULT);
            }
        }
        return null;
        // throw new SaikuServiceException("Context: " + id + " does not contain a olap result object");
    }

    public Statement getStatement() {
        if (objects.containsKey(ObjectKey.STATEMENT)) {
            return (Statement) objects.get(ObjectKey.STATEMENT);
        }
        throw new SaikuServiceException("Context: " + id + " does not contain a statement object");
    }

    public void store(ObjectKey key, Object value) {
        objects.put(key, value);
    }

    public boolean contains(ObjectKey key) {
        return objects.containsKey(key);
    }

    public void remove(ObjectKey key) {
        if (contains(key)) {
            objects.remove(key);
        }
    }

    public void destroy() throws Exception {
        if (contains(ObjectKey.STATEMENT)) {
            Statement stmt = getStatement();
            stmt.close();
            stmt = null;
        }
        if (contains(ObjectKey.CONNECTION)) {
            Connection con = getConnection();
            con.close();
            con = null;
        }
        objects.clear();
    }
}
