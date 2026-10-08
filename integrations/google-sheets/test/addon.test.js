/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 *
 * Unit tests for the add-on's pure logic (spiculedata/saiku#1436).
 * Run with: node --test integrations/google-sheets/test
 * No dependencies, no Apps Script runtime — the modules under test are
 * deliberately free of SpreadsheetApp / UrlFetchApp references so they load
 * straight into Node.
 */
const test = require('node:test');
const assert = require('node:assert');

const SaikuClient = require('../SaikuClient.js');
const SaikuQueryModel = require('../SaikuQueryModel.js');
const Code = require('../Code.js');

const SCHEMA = {
  cubeId: 'unknown_foodmart/FoodMart/FoodMart/Sales',
  measures: {
    'unit sales': { name: 'Unit Sales', uniqueName: '[Measures].[Unit Sales]' },
    'store sales': {
      name: 'Store Sales',
      unit: 'USD',
      description: 'Net retail revenue.',
    },
    revenue: { name: 'Store Sales', displayName: 'Revenue' },
  },
  dimensions: {
    time: {
      name: 'Time',
      hierarchies: {
        'time by': {
          name: 'Time By',
          levels: {
            year: { name: 'Year', grain: 'year' },
            quarter: { name: 'Quarter', grain: 'quarter', cardinality: 'low' },
          },
        },
      },
    },
    product: {
      name: 'Product',
      hierarchies: {
        products: {
          name: 'Products',
          levels: {
            'product family': { name: 'Product Family' },
            brand: { name: 'Brand Name' },
          },
        },
      },
    },
  },
};

const RESPONSE = {
  queryId: 'q-1',
  status: 'SUCCESS',
  format: 'records',
  metadata: {
    rows: [{ name: 'Product Family', caption: 'Product Family' }],
    columns: [
      { name: 'Store Sales', caption: 'Store Sales' },
      { name: 'Unit Sales', caption: 'Unit Sales' },
    ],
    measures: ['Store Sales', 'Unit Sales'],
  },
  data: [
    {
      'Product Family': 'Food',
      'Store Sales': { value: 409035.59, formatted: '409,035.59', unit: 'USD' },
      'Unit Sales': { value: 191940.0, formatted: '191,940' },
    },
    {
      'Product Family': 'Drink',
      'Store Sales': { value: 48836.21, formatted: '48,836.21', unit: 'USD' },
      'Unit Sales': { value: 24597.0, formatted: '24,597' },
    },
  ],
  totalRows: 2,
  runtimeMs: 421,
};

// ---------------------------------------------------------------------------
// SaikuClient — URL + auth shaping
// ---------------------------------------------------------------------------

test('normaliseBaseUrl strips trailing slashes and defaults to https', () => {
  assert.strictEqual(SaikuClient.normaliseBaseUrl('  https://saiku.example.com//  '), 'https://saiku.example.com');
  assert.strictEqual(SaikuClient.normaliseBaseUrl('saiku.example.com'), 'https://saiku.example.com');
  assert.strictEqual(SaikuClient.normaliseBaseUrl('http://localhost:8080'), 'http://localhost:8080');
});

test('normaliseBaseUrl keeps a context path but drops the trailing slash', () => {
  assert.strictEqual(SaikuClient.normaliseBaseUrl('https://host.example/bi/'), 'https://host.example/bi');
});

test('normaliseBaseUrl rejects empty and non-http schemes', () => {
  assert.throws(() => SaikuClient.normaliseBaseUrl(''), /Server URL is required/);
  assert.throws(() => SaikuClient.normaliseBaseUrl('   '), /Server URL is required/);
  assert.throws(() => SaikuClient.normaliseBaseUrl('ftp://saiku.example.com'), /must be http or https/);});

test('apiUrl mounts every route behind /rest/saiku/api', () => {
  assert.strictEqual(
    SaikuClient.apiUrl('https://saiku.example.com', '/ai/cubes'),
    'https://saiku.example.com/rest/saiku/api/ai/cubes'
  );
  // A bare path gets its slash.
  assert.strictEqual(
    SaikuClient.apiUrl('https://host.example/bi', 'ai/query'),
    'https://host.example/bi/rest/saiku/api/ai/query'
  );
});

test('basicAuthHeader is standard RFC 7617 base64 of user:password', () => {
  const header = SaikuClient.basicAuthHeader('admin', 'admin');
  assert.strictEqual(header.Authorization, 'Basic ' + Buffer.from('admin:admin').toString('base64'));
});

test('basicAuthHeader trims the username and requires one', () => {
  const header = SaikuClient.basicAuthHeader('  admin  ', 's3cret');
  assert.strictEqual(header.Authorization, 'Basic ' + Buffer.from('admin:s3cret').toString('base64'));
  assert.throws(() => SaikuClient.basicAuthHeader('   ', 'x'), /Username is required/);
});

test('authHeaders adds the JSON accept header alongside Basic auth', () => {
  const headers = SaikuClient.authHeaders({
    serverUrl: 'https://saiku.example.com',
    username: 'admin',
    password: 'admin',
  });
  assert.strictEqual(headers.Accept, 'application/json');
  assert.ok(headers.Authorization.startsWith('Basic '));
});

test('cubeId joins the 4-segment identifier with catalog/schema defaulted', () => {
  assert.strictEqual(
    SaikuClient.cubeId({
      connectionName: 'unknown_foodmart',
      catalog: 'FoodMart',
      schema: 'FoodMart',
      cubeName: 'Sales',
    }),
    'unknown_foodmart/FoodMart/FoodMart/Sales'
  );
  assert.strictEqual(
    SaikuClient.cubeId({ connectionName: 'c', cubeName: 'Sales' }),
    'c/FoodMart/FoodMart/Sales'
  );
  assert.throws(() => SaikuClient.cubeId({ connectionName: 'c' }), /missing an identifier segment/);
  assert.throws(() => SaikuClient.cubeId(null), /Pick a cube/);
});

// ---------------------------------------------------------------------------
// SaikuClient — transport + error mapping
// ---------------------------------------------------------------------------

/** Minimal stand-in for a UrlFetchApp response. */
function fakeResponse(code, text) {
  return {
    getResponseCode: () => code,
    getContentText: () => text,
  };
}

test('getJson POSTs JSON and returns the parsed body', () => {
  let seen = null;
  SaikuClient.setTransport((url, options) => {
    seen = { url, options };
    return fakeResponse(200, JSON.stringify(RESPONSE));
  });
  const body = SaikuClient.postJson(
    SaikuClient.apiUrl('https://saiku.example.com', '/ai/query'),
    SaikuClient.authHeaders({ username: 'admin', password: 'admin' }),
    { cube: 'c/FoodMart/FoodMart/Sales', measures: [{ name: 'Store Sales' }] }
  );
  assert.strictEqual(body.queryId, 'q-1');
  assert.strictEqual(seen.options.method, 'POST');
  assert.strictEqual(seen.options.contentType, 'application/json');
  assert.deepStrictEqual(JSON.parse(seen.options.payload).measures, [{ name: 'Store Sales' }]);
  assert.strictEqual(seen.options.muteHttpExceptions, true);
  SaikuClient.setTransport(null);
});

test('a GET sends no payload', () => {
  let seen = null;
  SaikuClient.setTransport((url, options) => {
    seen = options;
    return fakeResponse(200, '[]');
  });
  assert.deepStrictEqual(
    SaikuClient.getJson(SaikuClient.apiUrl('https://saiku.example.com', '/ai/cubes'), {}),
    []
  );
  assert.strictEqual(seen.payload, undefined);
  SaikuClient.setTransport(null);
});

test('parseError surfaces VALIDATION_ERROR field + available candidates', () => {
  const err = SaikuClient.parseError(
    400,
    JSON.stringify({
      status: 'VALIDATION_ERROR',
      error: 'Unknown level',
      field: 'rows[0].level',
      available: ['Year', 'Quarter'],
    })
  );
  assert.strictEqual(err.message, 'Unknown level');
  assert.strictEqual(err.status, 400);
  assert.strictEqual(err.code, 'VALIDATION_ERROR');
  assert.strictEqual(err.field, 'rows[0].level');
  assert.deepStrictEqual(err.available, ['Year', 'Quarter']);
});

test('parseError hints at credentials on 401 and at egress on 0', () => {
  assert.match(SaikuClient.parseError(401, '').hint, /username and password/);
  assert.match(SaikuClient.parseError(0, '').hint, /Could not reach the server/);
  assert.strictEqual(SaikuClient.parseError(500, 'boom').message, 'Saiku returned HTTP 500');
});

test('a non-JSON 200 body fails as such rather than silently yielding undefined', () => {
  SaikuClient.setTransport(() => fakeResponse(200, '<html>proxy error</html>'));
  assert.throws(
    () => SaikuClient.getJson('https://x.example/rest/saiku/api/ai/cubes', {}),
    /non-JSON response/
  );
  SaikuClient.setTransport(null);
});

// ---------------------------------------------------------------------------
// SaikuQueryModel — shelf options
// ---------------------------------------------------------------------------

test('measureOptions flattens the measures map and honours displayName', () => {
  const options = SaikuQueryModel.measureOptions(SCHEMA);
  assert.deepStrictEqual(
    options.map((o) => o.label),
    ['Revenue', 'Store Sales', 'Unit Sales']
  );
  const storeSales = options.find((o) => o.label === 'Store Sales');
  assert.strictEqual(storeSales.key, 'store sales');
  assert.strictEqual(storeSales.unit, 'USD');
  assert.strictEqual(storeSales.description, 'Net retail revenue.');
});

test('levelOptions flattens dimension/hierarchy/level into shelf keys', () => {
  const options = SaikuQueryModel.levelOptions(SCHEMA);
  assert.deepStrictEqual(
    options.map((o) => o.key).sort(),
    ['product|products|brand', 'product|products|product family', 'time|time by|quarter', 'time|time by|year']
  );
  const quarter = options.find((o) => o.key === 'time|time by|quarter');
  assert.strictEqual(quarter.label, 'Time / Time By / Quarter');
  assert.strictEqual(quarter.grain, 'quarter');
});

test('measureOptions / levelOptions survive an empty schema', () => {
  assert.deepStrictEqual(SaikuQueryModel.measureOptions({}), []);
  assert.deepStrictEqual(SaikuQueryModel.levelOptions(null), []);
});

// ---------------------------------------------------------------------------
// SaikuQueryModel — request building
// ---------------------------------------------------------------------------

test('parseLevelKey only accepts a full 3-segment key', () => {
  assert.deepStrictEqual(SaikuQueryModel.parseLevelKey('product|products|brand'), {
    dimension: 'product',
    hierarchy: 'products',
    level: 'brand',
  });
  assert.strictEqual(SaikuQueryModel.parseLevelKey('product|products'), null);
  assert.strictEqual(SaikuQueryModel.parseLevelKey('product||brand'), null);
  assert.strictEqual(SaikuQueryModel.parseLevelKey(''), null);
  assert.strictEqual(SaikuQueryModel.parseLevelKey(null), null);
});

test('validateState names every problem it finds', () => {
  const problems = SaikuQueryModel.validateState({
    cubeId: '',
    measures: [],
    rowKey: 'bad-key',
    limit: 2.5,
  });
  assert.deepStrictEqual(problems, [
    'Pick a cube.',
    'Pick at least one measure.',
    'Row axis "bad-key" is not a dimension/hierarchy/level.',
    'Row limit must be a whole number of rows (0 = no limit).',
  ]);
  assert.deepStrictEqual(
    SaikuQueryModel.validateState({
      cubeId: 'c/FoodMart/FoodMart/Sales',
      measures: ['store sales'],
      rowKey: 'product|products|brand',
      limit: '0',
    }),
    []
  );
});

test('buildRequest emits the typed AiQueryRequest body', () => {
  const request = SaikuQueryModel.buildRequest({
    cubeId: 'c/FoodMart/FoodMart/Sales',
    measures: ['store sales', 'unit sales'],
    rowKey: 'product|products|product family',
    limit: 3,
  });
  assert.deepStrictEqual(request, {
    cube: 'c/FoodMart/FoodMart/Sales',
    measures: [{ name: 'store sales' }, { name: 'unit sales' }],
    rows: [{ dimension: 'product', hierarchy: 'products', level: 'product family' }],
    limit: 3,
  });
});

test('buildRequest drops empty measures and an empty row shelf', () => {
  const request = SaikuQueryModel.buildRequest({
    cubeId: 'c/FoodMart/FoodMart/Sales',
    measures: ['store sales', '', null],
    rowKey: '',
    limit: '',
  });
  assert.deepStrictEqual(request.measures, [{ name: 'store sales' }]);
  assert.strictEqual('rows' in request, false);
  assert.strictEqual('limit' in request, false);
});

test('buildRequest only adds order alongside a positive limit', () => {
  const base = { cubeId: 'c/FoodMart/FoodMart/Sales', measures: ['store sales'], rowKey: '' };
  assert.strictEqual('order' in SaikuQueryModel.buildRequest(base, { orderBy: 'Store Sales' }), false);
  const withLimit = SaikuQueryModel.buildRequest(
    { ...base, limit: 5 },
    { orderBy: 'Store Sales', direction: 'asc' }
  );
  assert.deepStrictEqual(withLimit.order, [{ by: 'Store Sales', direction: 'asc' }]);
});

test('buildRequest passes visualTotals through only when set', () => {
  const base = { cubeId: 'c/FoodMart/FoodMart/Sales', measures: ['store sales'], rowKey: '', limit: '' };
  assert.strictEqual('visualTotals' in SaikuQueryModel.buildRequest(base), false);
  assert.strictEqual(
    SaikuQueryModel.buildRequest(base, { visualTotals: true }).visualTotals,
    true
  );
});

test('buildRequest throws the validation message rather than posting a bad body', () => {
  assert.throws(
    () => SaikuQueryModel.buildRequest({ cubeId: '', measures: [], rowKey: '', limit: '' }),
    /Pick a cube\. Pick at least one measure\./
  );
});

test('describeState renders the one-line summary with measure labels', () => {
  const summary = SaikuQueryModel.describeState(
    {
      cubeId: 'c/FoodMart/FoodMart/Sales',
      measures: ['revenue', 'unit sales'],
      rowKey: 'time|time by|year',
      limit: 10,
    },
    SaikuQueryModel.measureOptions(SCHEMA),
    SaikuQueryModel.levelOptions(SCHEMA)
  );
  assert.strictEqual(summary, 'Revenue, Unit Sales · by Time / Time By / Year · top 10');
});

test('describeState degrades gracefully without option lists', () => {
  const summary = SaikuQueryModel.describeState({
    cubeId: 'c',
    measures: ['store sales'],
    rowKey: 'time|time by|year',
    limit: '',
  });
  assert.strictEqual(summary, 'store sales · by time / year');
  assert.strictEqual(
    SaikuQueryModel.describeState({ cubeId: 'c', measures: [], rowKey: '', limit: '5' }),
    '(no measures) · no row axis · top 5'
  );
});

// ---------------------------------------------------------------------------
// SaikuQueryModel — response → sheet values
// ---------------------------------------------------------------------------

test('headerRow is row-axis captions then column captions', () => {
  assert.deepStrictEqual(SaikuQueryModel.headerRow(RESPONSE), [
    'Product Family',
    'Store Sales',
    'Unit Sales',
  ]);
});

test('headerRow falls back to the measures list when metadata.columns is absent', () => {
  const response = { metadata: { rows: [], measures: ['Store Sales'] }, data: [] };
  assert.deepStrictEqual(SaikuQueryModel.headerRow(response), ['Store Sales']);
});

test('cellValue returns numbers by default and formatted text on request', () => {
  const cell = { value: 409035.59, formatted: '409,035.59', unit: 'USD' };
  assert.strictEqual(SaikuQueryModel.cellValue(cell), 409035.59);
  assert.strictEqual(SaikuQueryModel.cellValue(cell, false), '409,035.59');
});

test('cellValue renders a suppressed cell as its mask, never the value', () => {
  const cell = { value: null, formatted: '—', suppressed: true };
  assert.strictEqual(SaikuQueryModel.cellValue(cell, true), '—');
  assert.strictEqual(SaikuQueryModel.cellValue({ suppressed: true }, true), '—');
});

test('cellValue surfaces an unavailable measure instead of a blank', () => {
  assert.strictEqual(
    SaikuQueryModel.cellValue({ unavailable: true, unavailableMessage: 'no join path' }, true),
    'no join path'
  );
  assert.strictEqual(SaikuQueryModel.cellValue({ unavailable: true }, true), '(unavailable)');
});

test('cellValue passes plain member captions and nulls straight through', () => {
  assert.strictEqual(SaikuQueryModel.cellValue('Food'), 'Food');
  assert.strictEqual(SaikuQueryModel.cellValue(null), '');
  assert.strictEqual(SaikuQueryModel.cellValue({}), '');
});

test('toSheetValues builds a header row plus one row per record', () => {
  assert.deepStrictEqual(SaikuQueryModel.toSheetValues(RESPONSE), [
    ['Product Family', 'Store Sales', 'Unit Sales'],
    ['Food', 409035.59, 191940.0],
    ['Drink', 48836.21, 24597.0],
  ]);
  assert.deepStrictEqual(SaikuQueryModel.toSheetValues(RESPONSE, { asNumbers: false })[1], [
    'Food',
    '409,035.59',
    '191,940',
  ]);
});

test('toSheetValues emits just the header when the result is empty', () => {
  const empty = { metadata: RESPONSE.metadata, data: [] };
  assert.deepStrictEqual(SaikuQueryModel.toSheetValues(empty), [
    ['Product Family', 'Store Sales', 'Unit Sales'],
  ]);
});

// ---------------------------------------------------------------------------
// SaikuQueryModel — write planning (refresh semantics)
// ---------------------------------------------------------------------------

test('planWrite anchors the grid and returns the new footprint', () => {
  const plan = SaikuQueryModel.planWrite(
    { sheetId: 7, startRow: 3, startColumn: 2, rowCount: 0, columnCount: 0 },
    RESPONSE
  );
  assert.strictEqual(plan.write.sheetId, 7);
  assert.strictEqual(plan.write.startRow, 3);
  assert.strictEqual(plan.write.startColumn, 2);
  assert.deepStrictEqual(plan.nextFootprint, {
    sheetId: 7,
    startRow: 3,
    startColumn: 2,
    rowCount: 3,
    columnCount: 3,
  });
  assert.strictEqual(plan.clear, null);
});

test('a smaller refresh result clears the rows the bigger one left behind', () => {
  const smaller = { metadata: RESPONSE.metadata, data: [RESPONSE.data[0]] };
  const plan = SaikuQueryModel.planWrite(
    { sheetId: 7, startRow: 1, startColumn: 1, rowCount: 3, columnCount: 3 },
    smaller
  );
  assert.strictEqual(plan.nextFootprint.rowCount, 2);
  assert.deepStrictEqual(plan.clear, {
    sheetId: 7,
    startRow: 3,
    startColumn: 1,
    rowCount: 1,
    columnCount: 3,
  });
});

test('a narrower refresh result clears the columns the wider one left behind', () => {
  const narrow = {
    metadata: { rows: RESPONSE.metadata.rows, columns: [RESPONSE.metadata.columns[0]] },
    data: [{ 'Product Family': 'Food', 'Store Sales': { value: 1, formatted: '1' } }],
  };
  const plan = SaikuQueryModel.planWrite(
    { sheetId: 7, startRow: 1, startColumn: 1, rowCount: 2, columnCount: 3 },
    narrow
  );
  assert.deepStrictEqual(plan.nextFootprint, {
    sheetId: 7,
    startRow: 1,
    startColumn: 1,
    rowCount: 2,
    columnCount: 2,
  });
  assert.deepStrictEqual(plan.clear, {
    sheetId: 7,
    startRow: 1,
    startColumn: 3,
    rowCount: 2,
    columnCount: 1,
  });
});

test('planWrite keeps the same anchor on refresh, so user formatting survives', () => {
  const plan = SaikuQueryModel.planWrite(
    { sheetId: 7, startRow: 10, startColumn: 4, rowCount: 3, columnCount: 3 },
    RESPONSE
  );
  assert.strictEqual(plan.write.startRow, 10);
  assert.strictEqual(plan.write.startColumn, 4);
});

// ---------------------------------------------------------------------------
// Fingerprints
// ---------------------------------------------------------------------------

test('fingerprint is key-order independent', () => {
  const a = { cube: 'c', measures: [{ name: 'x' }], limit: 2 };
  const b = { limit: 2, measures: [{ name: 'x' }], cube: 'c' };
  assert.strictEqual(SaikuQueryModel.fingerprint(a), SaikuQueryModel.fingerprint(b));
});

test('fingerprint changes when the query changes', () => {
  assert.notStrictEqual(
    SaikuQueryModel.fingerprint({ cube: 'c', measures: [{ name: 'x' }] }),
    SaikuQueryModel.fingerprint({ cube: 'c', measures: [{ name: 'y' }] })
  );
});

// ---------------------------------------------------------------------------
// Code.js helpers that don't need the Apps Script runtime
// ---------------------------------------------------------------------------

test('findDefaultMeasure falls back to the first measure when none is flagged', () => {
  assert.strictEqual(Code.findDefaultMeasure(SCHEMA), 'unit sales');
  assert.strictEqual(
    Code.findDefaultMeasure({ measures: { b: {}, a: { isDefault: true } } }),
    'a'
  );
  assert.strictEqual(Code.findDefaultMeasure({}), '');
});

test('readFootprintFor matches only the exact anchor it stored', () => {
  const store = new Map();
  global.PropertiesService = {
    getUserProperties: () => ({
      getProperty: (k) => (store.has(k) ? store.get(k) : null),
      setProperty: (k, v) => store.set(k, v),
      deleteProperty: (k) => store.delete(k),
    }),
  };
  store.set(
    Code.SAIKU_BLOCK_KEY,
    JSON.stringify({ sheetId: 7, startRow: 3, startColumn: 2, rowCount: 5, columnCount: 3 })
  );
  assert.deepStrictEqual(Code.readFootprintFor(7, 3, 2), { rowCount: 5, columnCount: 3 });
  assert.strictEqual(Code.readFootprintFor(7, 4, 2), null);
  assert.strictEqual(Code.readFootprintFor(8, 3, 2), null);
  delete global.PropertiesService;
});
