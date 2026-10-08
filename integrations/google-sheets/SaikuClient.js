/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 *
 * Saiku REST client for the Google Sheets add-on (spiculedata/saiku#1436).
 *
 * Apps Script global: `SaikuClient`. Every function here is pure string /
 * object work so the whole module is unit-testable under Node (no SpreadsheetApp,
 * no UrlFetchApp). The transport itself lives in Code.js and is injected into
 * `SaikuClient.setTransport`.
 */

/** Prefix every Saiku REST route sits behind (Jersey is mounted at /rest/*). */
var SAIKU_REST_PREFIX = '/rest/saiku/api';

/**
 * Normalise a user-typed server URL into a base we can append routes to.
 * Strips whitespace and any trailing slashes; a bare host gets https://.
 *
 * @param {string} raw
 * @return {string}
 */
function normaliseBaseUrl(raw) {
  var trimmed = String(raw == null ? '' : raw).trim();
  if (!trimmed) {
    throw new Error('Server URL is required (e.g. https://saiku.example.com).');
  }
  if (!/^https?:\/\//i.test(trimmed)) {
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed)) {
      // Some other scheme (ftp:, file:, javascript:) — don't paper over it
      // with an https:// prefix.
      throw new Error('Server URL must be http or https.');
    }
    trimmed = 'https://' + trimmed;
  }
  var parsed;
  try {
    parsed = new URL(trimmed);
  } catch (e) {
    throw new Error('Not a valid server URL: ' + raw);
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('Server URL must be http or https, got ' + parsed.protocol);
  }
  // A base may carry a context path (reverse proxies mount Saiku under /bi);
  // keep it, drop the trailing slash only.
  return trimmed.replace(/\/+$/, '');
}

/**
 * Absolute URL for a Saiku REST route. `path` is the route after
 * /rest/saiku/api, e.g. '/ai/cubes'.
 *
 * @param {string} baseUrl
 * @param {string} path
 * @return {string}
 */
function apiUrl(baseUrl, path) {
  var base = normaliseBaseUrl(baseUrl);
  var suffix = String(path == null ? '' : path);
  if (suffix && suffix.charAt(0) !== '/') {
    suffix = '/' + suffix;
  }
  return base + SAIKU_REST_PREFIX + suffix;
}

/**
 * HTTP Basic credential header. Saiku's stateless clients (MCP server, the
 * Excel add-in, this add-on) authenticate this way; SaikuCsrfRequestMatcher
 * exempts any request carrying an Authorization header from CSRF, so the
 * POST to /ai/query needs no XSRF token round-trip.
 *
 * @param {string} username
 * @param {string} password
 * @return {!Object<string,string>}
 */
function basicAuthHeader(username, password) {
  var user = String(username == null ? '' : username).trim();
  if (!user) {
    throw new Error('Username is required.');
  }
  var raw = user + ':' + String(password == null ? '' : password);
  // Loop rather than String.fromCharCode.apply: a long password would blow
  // the argument limit on the spread form.
  var binary = '';
  for (var i = 0; i < raw.length; i++) {
    binary += String.fromCharCode(raw.charCodeAt(i));
  }
  var b64 =
    typeof btoa === 'function' ? btoa(binary) : Buffer.from(raw, 'utf8').toString('base64');
  return { Authorization: 'Basic ' + b64 };
}

/**
 * Header set for every outbound call: JSON accept, Basic auth.
 *
 * @param {{serverUrl:string, username:string, password:string}} config
 * @return {!Object<string,string>}
 */
function authHeaders(config) {
  var headers = basicAuthHeader(config && config.username, config && config.password);
  headers.Accept = 'application/json';
  return headers;
}

/**
 * The 4-segment cube identifier the AI API uses everywhere:
 * `connection/catalog/schema/cubeName`.
 *
 * @param {{connectionName:string,catalog:string,schema:string,cubeName:string}} cube
 * @return {string}
 */
function cubeId(cube) {
  if (!cube) {
    throw new Error('Pick a cube first.');
  }
  var segments = [
    cube.connectionName,
    cube.catalog == null ? 'FoodMart' : cube.catalog,
    cube.schema == null ? 'FoodMart' : cube.schema,
    cube.cubeName,
  ];
  for (var i = 0; i < segments.length; i++) {
    if (!segments[i]) {
      throw new Error('Cube is missing an identifier segment.');
    }
  }
  return segments.join('/');
}

/**
 * Turn an error response into an Error carrying the status, the server's
 * machine code and (for VALIDATION_ERROR) the offending field + the server's
 * candidate list, so the sidebar can show "pick one of these" rather than a
 * bare 400.
 *
 * @param {number} status
 * @param {string} body
 * @return {!Error}
 */
function parseError(status, body) {
  var message = 'Saiku returned HTTP ' + status;
  var payload = null;
  if (body) {
    try {
      payload = JSON.parse(body);
    } catch (e) {
      payload = null;
    }
  }
  if (payload) {
    if (payload.error) {
      message = payload.error;
    } else if (payload.message) {
      message = payload.message;
    } else if (payload.statusMessage) {
      message = payload.statusMessage;
    }
  }
  var err = new Error(message);
  err.status = status;
  if (payload) {
    err.code = payload.code || payload.status || null;
    err.field = payload.field || null;
    err.available = payload.available || null;
  }
  if (status === 401 || status === 403) {
    err.hint = 'Check the Saiku username and password in the sidebar.';
  } else if (status === 0) {
    err.hint =
      'Could not reach the server. Self-hosted Saiku must allow Google’s Apps Script egress and your browser session must be authorised.';
  }
  return err;
}

/**
 * Injectable HTTP transport. Code.js sets this to a UrlFetchApp wrapper; the
 * Node tests set it to a stub. Kept as a module-level hook rather than a
 * parameter so call sites read cleanly.
 * @type {?function(string, !Object): !Object}
 */
var transport = null;

/**
 * @param {?function(string, !Object): !Object} fn
 */
function setTransport(fn) {
  transport = fn;
}

/**
 * GET a route and return the parsed JSON body.
 *
 * @param {string} url
 * @param {!Object<string,string>} headers
 * @return {*}
 */
function getJson(url, headers) {
  return request('GET', url, headers, null);
}

/**
 * POST a JSON body to a route and return the parsed JSON body.
 *
 * @param {string} url
 * @param {!Object<string,string>} headers
 * @param {*} body
 * @return {*}
 */
function postJson(url, headers, body) {
  return request('POST', url, headers, body);
}

/**
 * @param {string} method
 * @param {string} url
 * @param {!Object<string,string>} headers
 * @param {*} body
 * @return {*}
 */
function request(method, url, headers, body) {
  if (!transport) {
    throw new Error('No HTTP transport configured (call SaikuClient.setTransport).');
  }
  var options = {
    method: method,
    headers: headers || {},
    muteHttpExceptions: true,
    followRedirects: true,
  };
  if (body != null) {
    options.contentType = 'application/json';
    options.payload = JSON.stringify(body);
  }
  var response = transport(url, options);
  var text = response && response.getContentText ? response.getContentText() : '';
  var code = response && typeof response.getResponseCode === 'function' ? response.getResponseCode() : 0;
  if (code < 200 || code > 299) {
    throw parseError(code, text);
  }
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch (e) {
    var err = new Error('Saiku returned a non-JSON response (HTTP ' + code + ').');
    err.status = code;
    throw err;
  }
}

var SaikuClient = {
  REST_PREFIX: SAIKU_REST_PREFIX,
  normaliseBaseUrl: normaliseBaseUrl,
  apiUrl: apiUrl,
  basicAuthHeader: basicAuthHeader,
  authHeaders: authHeaders,
  cubeId: cubeId,
  parseError: parseError,
  setTransport: setTransport,
  getJson: getJson,
  postJson: postJson,
  request: request,
};

if (typeof module !== 'undefined' && module.exports) {
  module.exports = SaikuClient;
}
