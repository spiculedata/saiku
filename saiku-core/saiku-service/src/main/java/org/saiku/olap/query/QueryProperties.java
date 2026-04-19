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
package org.saiku.olap.query;

import java.util.Properties;
import org.olap4j.Axis;

public class QueryProperties {

    private static final String KEY_NONEMPTY = "saiku.olap.query.nonempty"; // $NON-NLS-1$
    private static final String KEY_NONEMPTY_ROWS = "saiku.olap.query.nonempty.rows"; // $NON-NLS-1$
    private static final String KEY_NONEMPTY_COLUMNS = "saiku.olap.query.nonempty.columns"; // $NON-NLS-1$
    public static final String KEY_IS_DRILLTHROUGH = "saiku.olap.query.drillthrough"; // $NON-NLS-1$
    private static final String KEY_SUPPORTS_LIMIT = "saiku.olap.query.limit"; // $NON-NLS-1$
    private static final String KEY_SUPPORTS_FILTER = "saiku.olap.query.filter"; // $NON-NLS-1$
    private static final String[] KEYS = {
        KEY_NONEMPTY,
        KEY_NONEMPTY_ROWS,
        KEY_NONEMPTY_COLUMNS,
        KEY_IS_DRILLTHROUGH,
        KEY_SUPPORTS_LIMIT,
        KEY_SUPPORTS_FILTER
    };

    public abstract class QueryProperty {

        final String key;
        final String value;
        final OlapQuery query;

        public QueryProperty(OlapQuery query, String key, String value) {
            this.key = key;
            this.value = value;
            this.query = query;
        }

        public abstract void handle();

        public abstract Properties getProperties();
    }

    public class DummyProperty extends QueryProperty {

        public DummyProperty(OlapQuery query, String key, String value) {
            super(query, key, value);
        }

        @Override
        public void handle() {}

        @Override
        public Properties getProperties() {
            return new Properties();
        }
    }

    public class NonEmptyRowsProperty extends QueryProperty {

        public NonEmptyRowsProperty(OlapQuery query, String key, String value) {
            super(query, key, value);
        }

        @Override
        public void handle() {
            Boolean nonEmpty = Boolean.parseBoolean(NonEmptyRowsProperty.this.value);
            query.getAxis(Axis.ROWS).setNonEmpty(nonEmpty);
        }

        @Override
        public Properties getProperties() {
            String key = this.key;
            String value = Boolean.toString(query.getAxis(Axis.ROWS).isNonEmpty());
            Properties props = new Properties();
            props.put(key, value);
            return props;
        }
    }

    public class NonEmptyProperty extends QueryProperty {

        public NonEmptyProperty(OlapQuery query, String key, String value) {
            super(query, key, value);
        }

        @Override
        public void handle() {
            Boolean nonEmpty = Boolean.parseBoolean(NonEmptyProperty.this.value);
            query.getAxis(Axis.ROWS).setNonEmpty(nonEmpty);
            query.getAxis(Axis.COLUMNS).setNonEmpty(nonEmpty);
        }

        @Override
        public Properties getProperties() {
            String key = this.key;
            String value = Boolean.toString(query.getAxis(Axis.COLUMNS).isNonEmpty()
                    && query.getAxis(Axis.ROWS).isNonEmpty());
            Properties props = new Properties();
            props.put(key, value);
            return props;
        }
    }

    public class NonEmptyColumnsProperty extends QueryProperty {

        public NonEmptyColumnsProperty(OlapQuery query, String key, String value) {
            super(query, key, value);
        }

        @Override
        public void handle() {
            Boolean nonEmpty = Boolean.parseBoolean(NonEmptyColumnsProperty.this.value);
            query.getAxis(Axis.COLUMNS).setNonEmpty(nonEmpty);
        }

        @Override
        public Properties getProperties() {
            String key = this.key;
            String value = Boolean.toString(query.getAxis(Axis.COLUMNS).isNonEmpty());
            Properties props = new Properties();
            props.put(key, value);
            return props;
        }
    }

    public class IsDrillthroughProperty extends QueryProperty {

        public IsDrillthroughProperty(OlapQuery query, String key, String value) {
            super(query, key, value);
        }

        @Override
        public void handle() {}

        @Override
        public Properties getProperties() {
            Properties props = new Properties();
            String key = this.key;
            String value = Boolean.toString(query.isDrillThroughEnabled());
            props.put(key, value);
            return props;
        }
    }

    public class SupportsLimitProperty extends QueryProperty {

        public SupportsLimitProperty(OlapQuery query, String key, String value) {
            super(query, key, value);
        }

        @Override
        public void handle() {}

        @Override
        public Properties getProperties() {
            Properties props = new Properties();
            String key = this.key;
            String value = Boolean.FALSE.toString();
            try {
                query.getAxis(Axis.COLUMNS).getLimitFunction();
                value = Boolean.TRUE.toString();
            } catch (Error e) {

            }
            props.put(key, value);
            return props;
        }
    }

    public class SupportsFilterProperty extends QueryProperty {

        public SupportsFilterProperty(OlapQuery query, String key, String value) {
            super(query, key, value);
        }

        @Override
        public void handle() {}

        @Override
        public Properties getProperties() {
            Properties props = new Properties();
            String key = this.key;
            String value = Boolean.FALSE.toString();
            try {
                query.getAxis(Axis.COLUMNS).getFilterCondition();
                value = Boolean.TRUE.toString();
            } catch (Error e) {

            }
            props.put(key, value);
            return props;
        }
    }

    public static class QueryPropertyFactory {
        private static final QueryProperties q = new QueryProperties();

        public static QueryProperty getProperty(final String key, final String value, final OlapQuery query) {
            if (KEY_NONEMPTY_ROWS.equals(key)) {
                return q.new NonEmptyRowsProperty(query, key, value);
            }
            if (KEY_NONEMPTY_COLUMNS.equals(key)) {
                return q.new NonEmptyColumnsProperty(query, key, value);
            }
            if (KEY_NONEMPTY.equals(key)) {
                return q.new NonEmptyProperty(query, key, value);
            }
            if (KEY_IS_DRILLTHROUGH.equals(key)) {
                return q.new IsDrillthroughProperty(query, key, value);
            }
            if (KEY_SUPPORTS_LIMIT.equals(key)) {
                return q.new SupportsLimitProperty(query, key, value);
            }
            if (KEY_SUPPORTS_FILTER.equals(key)) {
                return q.new SupportsFilterProperty(query, key, value);
            }
            return q.new DummyProperty(query, key, value);
        }

        public static Properties forQuery(OlapQuery query) {
            Properties props = new Properties();
            for (String key : KEYS) {
                props.putAll(getProperty(key, null, query).getProperties());
            }
            return props;
        }
    }
}
