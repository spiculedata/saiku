/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 *
 * Google Sheets add-on for the Saiku semantic layer (spiculedata/saiku#1436).
 *
 * Entry points the Sheets editor calls, plus the thin Apps Script glue around
 * the pure logic in SaikuClient.js / SaikuQueryModel.js. The add-on is
 * intentionally stateless beyond a per-user "last block" record: every query
 * goes to Saiku's typed AI Query REST API (docs/AI-QUERY-API.md), the same
 * surface the Excel Office.js add-in and the MCP server use.
 *
 * Naming: every global is `saiku*` or `Saiku*` — Apps Script shares one global
 * namespace across every file in a project.
 */

/** Sheet-level property key holding the footprint of the last written block. */
var SAIKU_BLOCK_KEY = 'saiku.lastBlock';

var SAIKU_MENU_TITLE = 'Saiku';

/**
 * Transport injection point: UrlFetchApp. Swapped for a stub under Node (see
 * test/saiku-client.test.js) — kept as a named function so the seam is
 * obvious rather than hidden in a closure.
 *
 * @param {string} url
 * @param {!Object} options
 * @return {!Object} an object with getResponseCode() / getContentText()
 */
function saikuUrlFetch(url, options) {
  return UrlFetchApp.fetch(url, options);
}

/**
 * Register the transport once per container so SaikuClient can use it.
 */
function saikuInstallTransport() {
  SaikuClient.setTransport(saikuUrlFetch);
}

/**
 * Called when the spreadsheet opens. Adds the "Saiku" menu; deliberately does
 * NOT open the sidebar, which would steal focus on every open.
 *
 * @param {!Object} e the open event
 */
function onOpen(e) {
  SpreadsheetApp.getUi()
    .createMenu(SAIKU_MENU_TITLE)
    .addItem('Open Saiku sidebar', 'saikuShowSidebar')
    .addItem('Refresh last result', 'saikuRefreshBlock')
    .addItem('Clear last result', 'saikuClearBlock')
    .addToUi();
}

/**
 * Install hook — after an add-on is authorised the menu only appears on the
 * next open, so show it immediately.
 *
 * @param {!Object} e the install event
 */
function onInstall(e) {
  onOpen(e);
}

/**
 * Show the sidebar.
 */
function saikuShowSidebar() {
  var html = HtmlService.createHtmlOutputFromFile('sidebar')
    .setTitle('Saiku')
    .setWidth(360);
  SpreadsheetApp.getUi().showSidebar(html);
}

/**
 * Validate the sidebar's connection form and return the cube list.
 *
 * @param {{serverUrl:string,username:string,password:string}} config
 * @return {!Array<{id:string,caption:string,defaultMeasure:string,measureCount:number}>}
 */
function saikuListCubes(config) {
  saikuInstallTransport();
  var cubes = SaikuClient.getJson(
    SaikuClient.apiUrl(config.serverUrl, '/ai/cubes'),
    SaikuClient.authHeaders(config)
  );
  return (cubes || []).map(function (cube) {
    return {
      id: SaikuClient.cubeId(cube),
      caption: cube.cubeCaption || cube.cubeName,
      defaultMeasure: cube.defaultMeasure || '',
      measureCount: cube.measureCount == null ? 0 : cube.measureCount,
    };
  });
}

/**
 * Typed schema for one cube, plus the shelf options pre-flattened for the UI.
 *
 * @param {{serverUrl:string,username:string,password:string}} config
 * @param {string} cubeId
 * @return {!Object}
 */
function saikuLoadSchema(config, cubeId) {
  saikuInstallTransport();
  if (!cubeId) {
    throw new Error('Pick a cube first.');
  }
  var schema = SaikuClient.getJson(
    SaikuClient.apiUrl(config.serverUrl, '/ai/schema/' + cubeId),
    SaikuClient.authHeaders(config)
  );
  return {
    cubeId: cubeId,
    measures: SaikuQueryModel.measureOptions(schema),
    levels: SaikuQueryModel.levelOptions(schema),
    defaultMeasure: findDefaultMeasure(schema),
  };
}

/**
 * The cube's own suggested default measure, resolved against the option list
 * so the sidebar can preselect it.
 *
 * @param {!Object} schema
 * @return {string}
 */
function findDefaultMeasure(schema) {
  var measures = (schema && schema.measures) || {};
  var keys = Object.keys(measures);
  for (var i = 0; i < keys.length; i++) {
    var m = measures[keys[i]] || {};
    if (m.defaultMeasure || m.isDefault) {
      return keys[i];
    }
  }
  return keys.length ? keys[0] : '';
}

/**
 * Preview a query without writing anything — powers the sidebar's row count.
 *
 * @param {{serverUrl:string,username:string,password:string}} config
 * @param {!Object} request an AiQueryRequest body
 * @return {!Object} a small summary for the sidebar
 */
function saikuPreviewQuery(config, request) {
  saikuInstallTransport();
  var response = runQuery(config, request);
  var headers = SaikuQueryModel.headerRow(response);
  return {
    queryId: response.queryId,
    rowCount: (response.data || []).length,
    columnCount: headers.length,
    headers: headers,
    runtimeMs: response.runtimeMs,
    cached: !!(response.metadata && response.metadata.freshness && response.metadata.freshness.cached),
  };
}

/**
 * Run a query and write the result into the active sheet at the current
 * selection, then remember the footprint so Refresh can rewrite exactly it.
 *
 * @param {{serverUrl:string,username:string,password:string}} config
 * @param {{cubeId:string,measures:!Array<string>,rowKey:string,limit:*,asNumbers:boolean}} state
 * @return {!Object} a summary the sidebar renders
 */
function saikuInsertBlock(config, state) {
  saikuInstallTransport();
  var request = SaikuQueryModel.buildRequest(state, {
    orderBy: state.orderBy,
    direction: state.direction,
    visualTotals: state.visualTotals,
  });
  var response = runQuery(config, request);
  var written = writeBlock(response, state);
  rememberBlock(config, request, written);
  return {
    queryId: response.queryId,
    range: written.range,
    rowCount: written.rowCount,
    columnCount: written.columnCount,
    runtimeMs: response.runtimeMs,
  };
}

/**
 * Re-run the query behind the last written block and rewrite it in place,
 * leaving the user's own formatting on the block alone (only values are
 * rewritten — number formats, fonts and colours the user applied survive).
 *
 * @return {!Object}
 */
function saikuRefreshBlock() {
  saikuInstallTransport();
  var block = readBlock();
  if (!block) {
    throw new Error('Nothing to refresh yet — insert a Saiku result first.');
  }
  var response = runQuery(block.config, block.request);
  var plan = SaikuQueryModel.planWrite(
    {
      sheetId: block.sheetId,
      startRow: block.startRow,
      startColumn: block.startColumn,
      rowCount: block.rowCount,
      columnCount: block.columnCount,
    },
    response,
    { asNumbers: block.asNumbers !== false }
  );
  applyPlan(plan);
  saikuRememberFootprint(
    block.sheetId,
    block.startRow,
    block.startColumn,
    plan.nextFootprint.rowCount,
    plan.nextFootprint.columnCount,
    block.config,
    block.request,
    block.asNumbers
  );
  return {
    queryId: response.queryId,
    range: rangeLabel(block.sheetId, block.startRow, block.startColumn, plan.nextFootprint),
    rowCount: plan.nextFootprint.rowCount,
    columnCount: plan.nextFootprint.columnCount,
    runtimeMs: response.runtimeMs,
  };
}

/**
 * Blank out the last written block (values only — formatting the user added
 * on top of it stays, which is what "clear the data" means to a spreadsheet
 * user).
 */
function saikuClearBlock() {
  var block = readBlock();
  if (!block) {
    throw new Error('Nothing to clear.');
  }
  var sheet = SpreadsheetApp.getActive().getSheetById(block.sheetId);
  if (!sheet) {
    // The sheet was deleted since the block was written; drop the record.
    forgetBlock();
    throw new Error('The sheet this result was written to no longer exists.');
  }
  var range = sheet.getRange(
    block.startRow,
    block.startColumn,
    Math.max(block.rowCount, 1),
    Math.max(block.columnCount, 1)
  );
  range.clearContent();
  forgetBlock();
  return { cleared: range.getA1Notation() };
}

/**
 * Execute a request against Saiku and fail loudly on a non-SUCCESS status.
 *
 * @param {{serverUrl:string,username:string,password:string}} config
 * @param {!Object} request
 * @return {!Object} the records response
 */
function runQuery(config, request) {
  var response = SaikuClient.postJson(
    SaikuClient.apiUrl(config.serverUrl, '/ai/query'),
    SaikuClient.authHeaders(config),
    request
  );
  if (!response) {
    throw new Error('Saiku returned an empty response.');
  }
  if (response.status && response.status !== 'SUCCESS') {
    var message = response.error || ('Saiku returned status ' + response.status + '.');
    var err = new Error(message);
    err.code = response.status;
    err.field = response.field || null;
    err.available = response.available || null;
    throw err;
  }
  if (!response.data && !response.matrix) {
    throw new Error('Saiku returned no rows.');
  }
  return response;
}

/**
 * Write a fresh result at the current selection, clearing whatever occupied
 * the anchor before (an empty top-left selection would otherwise leave the
 * previous run's rows directly above the new header).
 *
 * @param {!Object} response
 * @param {!Object} state
 * @return {{sheetId:number,startRow:number,startColumn:number,rowCount:number,columnCount:number,range:string}}
 */
function writeBlock(response, state) {
  var sheet = SpreadsheetApp.getActive().getActiveSheet();
  var cell = sheet.getActiveCell();
  var startRow = cell.getRow();
  var startColumn = cell.getColumn();

  var previous = readFootprintFor(sheet.getSheetId(), startRow, startColumn);
  var anchor = {
    sheetId: sheet.getSheetId(),
    startRow: startRow,
    startColumn: startColumn,
    rowCount: previous ? previous.rowCount : 0,
    columnCount: previous ? previous.columnCount : 0,
  };
  var plan = SaikuQueryModel.planWrite(anchor, response, {
    asNumbers: state.asNumbers !== false,
  });
  applyPlan(plan);
  var written = plan.nextFootprint;
  written.range = rangeLabel(written.sheetId, written.startRow, written.startColumn, written);
  return written;
}

/**
 * Apply a write plan: values first, then clear the stale overhang.
 *
 * @param {!Object} plan output of SaikuQueryModel.planWrite
 */
function applyPlan(plan) {
  var sheet = SpreadsheetApp.getActive().getSheetById(plan.write.sheetId);
  if (!sheet) {
    throw new Error('Target sheet no longer exists.');
  }
  if (plan.write.values.length && plan.write.values[0].length) {
    sheet
      .getRange(plan.write.startRow, plan.write.startColumn, plan.write.values.length, plan.write.values[0].length)
      .setValues(plan.write.values);
  }
  if (plan.clear) {
    sheet
      .getRange(plan.clear.startRow, plan.clear.startColumn, plan.clear.rowCount, plan.clear.columnCount)
      .clearContent();
  }
}

/**
 * A1-ish label for the sidebar's confirmation line.
 *
 * @return {string}
 */
function rangeLabel(sheetId, startRow, startColumn, extent) {
  var sheet = SpreadsheetApp.getActive().getSheetById(sheetId);
  var name = sheet ? sheet.getName() : 'sheet';
  return name + '!' + startRow + ':' + startColumn + ' (' + extent.rowCount + '×' + extent.columnCount + ')';
}

/**
 * Persist the block record in USER properties: one spreadsheet file is shared
 * between users, and one user refreshing must never re-run (or rewrite) the
 * block another user inserted. Script properties would be shared and would
 * make that race real.
 */
function rememberBlock(config, request, written) {
  saikuRememberFootprint(
    written.sheetId,
    written.startRow,
    written.startColumn,
    written.rowCount,
    written.columnCount,
    config,
    request,
    true
  );
}

/**
 * @private
 */
function saikuRememberFootprint(sheetId, startRow, startColumn, rowCount, columnCount, config, request, asNumbers) {
  PropertiesService.getUserProperties().setProperty(
    SAIKU_BLOCK_KEY,
    JSON.stringify({
      sheetId: sheetId,
      startRow: startRow,
      startColumn: startColumn,
      rowCount: rowCount,
      columnCount: columnCount,
      config: config,
      request: request,
      fingerprint: SaikuQueryModel.fingerprint(request),
      asNumbers: asNumbers !== false,
      writtenAt: new Date().toISOString(),
    })
  );
}

/**
 * @return {?Object} the stored block record, or null
 */
function readBlock() {
  var raw = PropertiesService.getUserProperties().getProperty(SAIKU_BLOCK_KEY);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw);
  } catch (e) {
    return null;
  }
}

/**
 * The stored footprint only when it starts exactly at this anchor — that's
 * what makes a re-insert at the same cell overwrite instead of stacking.
 *
 * @return {?{rowCount:number,columnCount:number}}
 */
function readFootprintFor(sheetId, startRow, startColumn) {
  var block = readBlock();
  if (!block) {
    return null;
  }
  if (
    block.sheetId === sheetId &&
    block.startRow === startRow &&
    block.startColumn === startColumn
  ) {
    return { rowCount: block.rowCount, columnCount: block.columnCount };
  }
  return null;
}

/**
 * Drop the stored record.
 */
function forgetBlock() {
  PropertiesService.getUserProperties().deleteProperty(SAIKU_BLOCK_KEY);
}

/**
 * Status for the sidebar on open: what, if anything, can be refreshed.
 *
 * @return {!Object}
 */
function saikuBlockStatus() {
  var block = readBlock();
  if (!block) {
    return { present: false };
  }
  return {
    present: true,
    sheetId: block.sheetId,
    rowCount: block.rowCount,
    columnCount: block.columnCount,
    writtenAt: block.writtenAt,
    fingerprint: block.fingerprint,
  };
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    findDefaultMeasure: findDefaultMeasure,
    rangeLabel: rangeLabel,
    readFootprintFor: readFootprintFor,
    SAIKU_BLOCK_KEY: SAIKU_BLOCK_KEY,
  };
}
