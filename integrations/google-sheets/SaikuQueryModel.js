/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 *
 * Pure query-shaping logic for the Google Sheets add-on (spiculedata/saiku#1436).
 *
 * The sidebar collects a cube, a set of measures, one row level and a limit;
 * this module turns that shelf state into an `AiQueryRequest` body and turns
 * the server's `records` response into a 2-D array a sheet can absorb
 * directly. No Apps Script globals here, so the whole module unit-tests under
 * Node — see test/query-model.test.js.
 */

/**
 * Measures offered by the measure shelf for one cube.
 *
 * @param {!Object} schema  the /ai/schema/{cubeId} response
 * @return {!Array<{key:string,label:string,description:string,unit:string}>}
 */
function measureOptions(schema) {
  var measures = (schema && schema.measures) || {};
  var out = [];
  Object.keys(measures).forEach(function (key) {
    var m = measures[key] || {};
    var label = m.displayName || m.name || key;
    out.push({
      key: key,
      // The server accepts canonical keys, display names and synonyms as
      // `measures[].name`, so send the key and let the server resolve.
      label: label,
      description: m.description || '',
      unit: m.unit || '',
      aggregationKind: m.aggregationKind || '',
    });
  });
  out.sort(function (a, b) {
    return a.label.localeCompare(b.label);
  });
  return out;
}

/**
 * Row-axis levels flattened out of the cube schema into one selectable list —
 * the Sheets analogue of the Office.js add-in's dimension shelf.
 *
 * @param {!Object} schema
 * @return {!Array<{key:string,dimension:string,hierarchy:string,level:string,label:string}>}
 */
function levelOptions(schema) {
  var dimensions = (schema && schema.dimensions) || {};
  var out = [];
  Object.keys(dimensions).forEach(function (dimKey) {
    var dim = dimensions[dimKey] || {};
    var hierarchies = dim.hierarchies || {};
    Object.keys(hierarchies).forEach(function (hierKey) {
      var hier = hierarchies[hierKey] || {};
      var levels = hier.levels || {};
      Object.keys(levels).forEach(function (levelKey) {
        var level = levels[levelKey] || {};
        out.push({
          key: dimKey + '|' + hierKey + '|' + levelKey,
          dimension: dim.name || dimKey,
          hierarchy: hier.name || hierKey,
          level: level.name || levelKey,
          label: (dim.name || dimKey) + ' / ' + (hier.name || hierKey) + ' / ' + (level.name || levelKey),
          grain: level.grain || '',
          cardinality: level.cardinality || '',
        });
      });
    });
  });
  out.sort(function (a, b) {
    return a.label.localeCompare(b.label);
  });
  return out;
}

/**
 * Split a `dimension|hierarchy|level` shelf key. Returns null for anything
 * that isn't a well-formed key, so the caller can skip stale options rather
 * than post garbage to the server.
 *
 * @param {string} key
 * @return {?{dimension:string,hierarchy:string,level:string}}
 */
function parseLevelKey(key) {
  if (!key) {
    return null;
  }
  var parts = String(key).split('|');
  if (parts.length !== 3 || !parts[0] || !parts[1] || !parts[2]) {
    return null;
  }
  return { dimension: parts[0], hierarchy: parts[1], level: parts[2] };
}

/**
 * Validate the sidebar shelf state without a round-trip, so an obvious
 * mistake (no measures, nonsense limit) never becomes a 400.
 *
 * @param {{cubeId:string, measures:!Array<string>, rowKey:string, limit:*}} state
 * @return {!Array<string>} human-readable problems; empty means valid
 */
function validateState(state) {
  var problems = [];
  var s = state || {};
  if (!s.cubeId) {
    problems.push('Pick a cube.');
  }
  var measures = (s.measures || []).filter(function (m) {
    return !!m;
  });
  if (measures.length === 0) {
    problems.push('Pick at least one measure.');
  }
  if (s.rowKey && !parseLevelKey(s.rowKey)) {
    problems.push('Row axis "' + s.rowKey + '" is not a dimension/hierarchy/level.');
  }
  if (s.limit != null && String(s.limit) !== '') {
    var n = Number(s.limit);
    if (!isFinite(n) || Math.floor(n) !== n || n < 0) {
      problems.push('Row limit must be a whole number of rows (0 = no limit).');
    }
  }
  return problems;
}

/**
 * Build the `AiQueryRequest` body the sidebar state describes.
 *
 * `measures` are sent as measure KEYS; Saiku resolves keys, display names and
 * synonyms identically (docs/AI-QUERY-API.md "Display names + semantic
 * annotations"), so a renamed measure keeps working without a UI change.
 *
 * @param {{cubeId:string, measures:!Array<string>, rowKey:string, limit:*}} state
 * @param {{orderBy:string, direction:string, visualTotals:boolean}} [opts]
 * @return {!Object} the request body
 */
function buildRequest(state, opts) {
  var problems = validateState(state);
  if (problems.length) {
    throw new Error(problems.join(' '));
  }
  var options = opts || {};
  var request = {
    cube: state.cubeId,
    measures: (state.measures || [])
      .filter(function (m) {
        return !!m;
      })
      .map(function (name) {
        return { name: name };
      }),
  };

  var row = parseLevelKey(state.rowKey);
  if (row) {
    request.rows = [row];
  }

  var limit = state.limit == null || state.limit === '' ? 0 : Number(state.limit);
  if (limit > 0) {
    request.limit = limit;
    if (options.orderBy) {
      request.order = [{ by: options.orderBy, direction: options.direction === 'asc' ? 'asc' : 'desc' }];
    }
  }

  if (options.visualTotals) {
    request.visualTotals = true;
  }
  return request;
}

/**
 * Human-readable one-liner for the sidebar's "current query" line.
 *
 * @param {{cubeId:string, measures:!Array<string>, rowKey:string, limit:*}} state
 * @param {!Array} [measureList] output of measureOptions()
 * @param {!Array} [levelList] output of levelOptions()
 * @return {string}
 */
function describeState(state, measureList, levelList) {
  var s = state || {};
  var labels = (s.measures || []).map(function (m) {
    var found = (measureList || []).filter(function (o) {
      return o.key === m;
    })[0];
    return found ? found.label : m;
  });
  var row = parseLevelKey(s.rowKey);
  var rowLabel = row ? row.dimension + ' / ' + row.level : null;
  if (row) {
    var level = (levelList || []).filter(function (o) {
      return o.key === s.rowKey;
    })[0];
    if (level) {
      rowLabel = level.label;
    }
  }
  var limit = s.limit == null || s.limit === '' ? '' : Number(s.limit);
  var parts = [labels.join(', ') || '(no measures)'];
  parts.push(rowLabel ? 'by ' + rowLabel : 'no row axis');
  if (limit > 0) {
    parts.push('top ' + limit);
  }
  return parts.join(' · ');
}

/**
 * Header captions for a records response: the row-axis captions first (they
 * key the record objects), then one column caption per measure.
 *
 * @param {!Object} response
 * @return {!Array<string>}
 */
function headerRow(response) {
  var metadata = (response && response.metadata) || {};
  var rows = metadata.rows || [];
  var columns = metadata.columns || [];
  var headers = rows.map(function (c) {
    return c && c.caption ? c.caption : (c && c.name) || '';
  });
  if (columns.length) {
    columns.forEach(function (c) {
      headers.push(c && c.caption ? c.caption : (c && c.name) || '');
    });
  } else {
    // No metadata.columns (defensive — the server always sends it for records):
    // fall back to the response's measure list so the header still lines up.
    (metadata.measures || []).forEach(function (m) {
      headers.push(m);
    });
  }
  return headers;
}

/**
 * Read one cell of a records response into something a sheet can hold.
 *
 * Row-axis cells are plain strings (member captions); measure cells are the
 * typed `{value, formatted, unit, suppressed}` envelope. `asNumbers` picks
 * the raw value (so Sheets can chart/sum it) over Mondrian's pre-formatted
 * display string.
 *
 * @param {*} cell
 * @param {boolean} [asNumbers] defaults to true (chartable numbers); pass
 *     false for Mondrian's pre-formatted display strings
 * @return {*}
 */
function cellValue(cell, asNumbers) {
  var numeric = asNumbers !== false;
  if (cell == null) {
    return '';
  }
  if (typeof cell !== 'object') {
    return cell;
  }
  if (cell.unavailable) {
    return cell.unavailableMessage || '(unavailable)';
  }
  if (cell.suppressed) {
    // k-anonymity small-cell mask: the server nulls the value and formats the
    // cell as an em dash. Render that verbatim — never the underlying number.
    return cell.formatted == null ? '—' : cell.formatted;
  }
  if (numeric && typeof cell.value === 'number') {
    return cell.value;
  }
  if (cell.formatted != null) {
    return cell.formatted;
  }
  if (cell.value == null) {
    return '';
  }
  return cell.value;
}

/**
 * Turn a records response into a header row plus one row per record.
 *
 * @param {!Object} response
 * @param {{asNumbers:boolean}} [opts]
 * @return {!Array<!Array<*>>}
 */
function toSheetValues(response, opts) {
  var options = opts || {};
  var asNumbers = options.asNumbers !== false;
  var headers = headerRow(response);
  var data = (response && response.data) || [];
  var out = [headers];
  data.forEach(function (record) {
    var row = headers.map(function (header) {
      return cellValue(record[header], asNumbers);
    });
    out.push(row);
  });
  return out;
}

/**
 * Footprint of one result block on a sheet — the anchor plus the extent the
 * last write occupied. Refresh rewrites exactly this rectangle, and clears
 * whatever overhangs when the new result is smaller, so a refresh never
 * leaves stale rows behind.
 *
 * @param {{sheetId:number,startRow:number,startColumn:number,rowCount:number,columnCount:number}} footprint
 * @param {!Object} response the /ai/query records response
 * @param {{asNumbers:boolean}} [opts]
 * @return {{write:!Object, clear:!Object, nextFootprint:!Object}}
 */
function planWrite(footprint, response, opts) {
  var options = opts || {};
  var grid = toSheetValues(response, { asNumbers: options.asNumbers });
  var rows = grid.length;
  var columns = rows ? grid[0].length : 0;
  var write = {
    sheetId: footprint.sheetId,
    startRow: footprint.startRow,
    startColumn: footprint.startColumn,
    values: grid,
  };
  var clear = null;
  if (footprint && footprint.rowCount && footprint.columnCount) {
    var staleRows = footprint.rowCount - rows;
    var staleColumns = footprint.columnCount - columns;
    if (staleRows > 0) {
      clear = {
        sheetId: footprint.sheetId,
        startRow: footprint.startRow + rows,
        startColumn: footprint.startColumn,
        rowCount: staleRows,
        columnCount: footprint.columnCount,
      };
    }
    if (!clear && staleColumns > 0) {
      clear = {
        sheetId: footprint.sheetId,
        startRow: footprint.startRow,
        startColumn: footprint.startColumn + columns,
        rowCount: rows,
        columnCount: staleColumns,
      };
    }
  }
  return {
    write: write,
    clear: clear,
    nextFootprint: {
      sheetId: footprint.sheetId,
      startRow: footprint.startRow,
      startColumn: footprint.startColumn,
      rowCount: rows,
      columnCount: columns,
    },
  };
}

/**
 * Stable fingerprint of a request body — the add-on stores it next to the
 * written block so Refresh re-runs the query the user actually ran, even if
 * the sidebar has since been re-pointed at another cube.
 *
 * @param {!Object} request
 * @return {string}
 */
function fingerprint(request) {
  var canonical = function (value) {
    if (Array.isArray(value)) {
      return value.map(canonical);
    }
    if (value && typeof value === 'object') {
      return Object.keys(value)
        .sort()
        .reduce(function (acc, k) {
          acc[k] = canonical(value[k]);
          return acc;
        }, {});
    }
    return value;
  };
  return JSON.stringify(canonical(request || {}));
}

var SaikuQueryModel = {
  measureOptions: measureOptions,
  levelOptions: levelOptions,
  parseLevelKey: parseLevelKey,
  validateState: validateState,
  buildRequest: buildRequest,
  describeState: describeState,
  headerRow: headerRow,
  cellValue: cellValue,
  toSheetValues: toSheetValues,
  planWrite: planWrite,
  fingerprint: fingerprint,
};

if (typeof module !== 'undefined' && module.exports) {
  module.exports = SaikuQueryModel;
}
