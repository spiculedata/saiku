package org.saiku.olap.util.formatter;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.util.exception.SaikuServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CellSetFormatterFactory {

    private static final Logger log = LoggerFactory.getLogger(ThinQueryService.class);

    private Map<String, String> formatters = new HashMap<>();

    private String defaultFormatter = FlattenedCellSetFormatter.class.getName();

    public void setFormatters(Map<String, String> formatters) {
        this.formatters = formatters;
    }

    public void setDefaultFormatter(String clazz) {
        this.defaultFormatter = clazz;
    }

    public CellSetFormatterFactory() {
        formatters.put("flattened", FlattenedCellSetFormatter.class.getName());
        formatters.put("hierarchical", HierarchicalCellSetFormatter.class.getName());
        formatters.put("flat", CellSetFormatter.class.getName());
    }

    public ICellSetFormatter forName(String name) {
        return create(name, defaultFormatter);
    }

    private ICellSetFormatter create(String name, String defaultFormatter) {
        String clazzName =
                StringUtils.isBlank(name) || !formatters.containsKey(name) ? defaultFormatter : formatters.get(name);
        try {
            @SuppressWarnings("unchecked")
            final Class<ICellSetFormatter> clazz = (Class<ICellSetFormatter>) Class.forName(clazzName);
            final Constructor<ICellSetFormatter> ctor = clazz.getConstructor();
            return ctor.newInstance();
        } catch (Exception e) {
            log.error("Error creating CellSetFormatter \"" + clazzName + "\"", e);
            throw new SaikuServiceException("Error creating cellsetformatter for class: " + clazzName, e);
        }
    }
}
