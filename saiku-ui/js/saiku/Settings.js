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

/**
 * Change settings here
 */
var Settings = {
    VERSION: "Saiku-${version}",
    LICENSE: {},
    BIPLUGIN: false,
    BIPLUGIN5: false,
    BASE_URL: window.location.origin,
    TOMCAT_WEBAPP: "/saiku",
    REST_MOUNT_POINT: "/rest/saiku/",
    DIMENSION_PREFETCH: true,
    DIMENSION_SHOW_ALL: true,
    /*
     * Valid values for DIMENSION_HIDE_HIERARCHY:
     * 1) NONE
     * 2) SINGLE_LEVEL
     * 3) ALL
     */
    DIMENSION_HIDE_HIERARCHY: 'SINGLE_LEVEL',
    ERROR_LOGGING: false,
    I18N_LOCALE: "en",
    // number of erroneous ajax calls in a row before UI cant recover
    ERROR_TOLERANCE: 3,
    QUERY_PROPERTIES: {
        'saiku.olap.query.automatic_execution': true,
        'saiku.olap.query.nonempty': true,
        'saiku.olap.query.nonempty.rows': true,
        'saiku.olap.query.nonempty.columns': true,
        'saiku.ui.render.mode' : 'table',
        'saiku.olap.query.filter' : true,
        'saiku.olap.result.formatter' : "flattened"
    },
    REPOSITORY_LAZY: false,
    TABLE_LAZY: true,          // Turn lazy loading off / on
    TABLE_LAZY_SIZE: 1000,     // Initial number of items to be rendered
    TABLE_LAZY_LOAD_ITEMS: 20,       // Additional item per scroll
    TABLE_LAZY_LOAD_TIME: 20,  // throttling call of lazy loading items
    /* Valid values for CELLSET_FORMATTER:
     * 1) flattened
     * 2) flat
     */
    CELLSET_FORMATTER: "flattened",
    // limits the number of rows in the result
    // 0 - no limit
    RESULT_LIMIT: 0,
    MEMBERS_FROM_RESULT: true,
    MEMBERS_LIMIT: 3000,
    MEMBERS_SEARCH_LIMIT: 75,
    ALLOW_IMPORT_EXPORT: false,
    ALLOW_PARAMETERS: true,
    PLUGINS: [
        "Chart"
    ],
    DEFAULT_VIEW_STATE: 'view', // could be 'edit' as well
    DEMO: false,
    TELEMETRY_SERVER: 'http://telemetry.analytical-labs.com:7000',
    LOCALSTORAGE_EXPIRATION: 10 * 60 * 60 * 1000 /* 10 hours, in ms */,
    UPGRADE: true,
    EVALUATION_PANEL_LOGIN: true,
    QUERY_OVERWRITE_WARNING: true,
    MAPS: true,
    MAPS_TYPE: 'OSM', // OSM || GMAPS
    MAPS_TILE_LAYER: {
        OSM: {
            'map_marker': 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
            'map_heat': 'https://otile{s}-s.mqcdn.com/tiles/1.0.0/sat/{z}/{x}/{y}.png'
        },
        GMAPS: {
        }
    },
    MAPS_OPTIONS: {
        OSM: {
            maxZoom: 18,
            attribution: '© <a href="http://osm.org/copyright" target="_blank">OpenStreetMap</a>'
        },
        GMAPS: {
        }
    },
    MAPS_OSM_NOMINATIM: 'https://nominatim.openstreetmap.org/', // http://wiki.openstreetmap.org/wiki/Nominatim
    DATA_SOURCES_LOOKUP: false,
    DEFAULT_REPORT_SHOW: false, // true/false
    DEFAULT_REPORTS: {
        'admin': [
            {
                path: 'ADD_PATH1', // example: /homes/home:admin/chart.saiku
                visible: false    // true/false
            }
        ],
        '_': [
            {
                path: 'ADD_PATH2',
                visible: false
            }
        ],
        'ROLE_ADMIN': [
            {
                path: 'ADD_PATH3',
                visible: false
            }
        ]
    },
    PARENT_MEMBER_DIMENSION: false,
    EXT_DATASOURCE_PROPERTIES: false,
    SHOW_USER_MANAGEMENT: true,
    SHOW_REFRESH_NONADMIN: false,
    EMPTY_VALUE_CHARACTER: '-',
    HIDE_EMPTY_ROWS: true,
    MEASURE_GROUPS_COLLAPSED: false,
    ORBIS_AUTH: {
        enabled: false,
        cookieName: 'SAIKU_AUTH_PRINCIPAL'
    },
    SCHEMA_EDITOR: {
        // The `^` matches beginning of input.
        STAR_SCHEMA_FACT_TABLE: /^fact_|^f_/i,
        STAR_SCHEMA_DIMENSION_TABLE: /^dimension_|^dim_|^d_/i,
        STAR_SCHEMA_MEASURE_COLUMN: /^measure_|^m_/i,
        // The `$` matches end of input.
        STAR_SCHEMA_MEASURE_AGGREGATION_COLUMN: /_sum$|_avg$|_count$|_min$|_max$/i
    },
    ALLOW_TABLE_DATA_COLLAPSE: false,
    ALLOW_AXIS_COLUMN_TITLE_TABLE: true,
    COLUMN_TITLE_TABLE_USE_LEVEL_CAPTION_NAME: true
};

/**
 * Extend settings with query parameters
 */
Settings.GET = function () {
    var qs = document.location.search;
    qs = qs.split("+").join(" ");
    var params = {},
        tokens,
        re = /[?&]?([^=]+)=([^&]*)/g;

    tokens = re.exec(qs);
    while (tokens) {
        var value = decodeURIComponent(tokens[2]);
        if (! isNaN(value)) value = parseInt(value);
        if (value === "true") value = true;
        if (value === "false") value = false;
        if(decodeURIComponent(tokens[1].toUpperCase()).substring(0,5)==="PARAM"){
          params["PARAM"+decodeURIComponent(tokens[1]).substring(5,decodeURIComponent(tokens[1]).length)] = value;
        }
        else{
          params[decodeURIComponent(tokens[1]).toUpperCase()] = value;
        }

        tokens = re.exec(qs);
    }

    return params;
}();
_.extend(Settings, Settings.GET);

Settings.PARAMS = (function() {
    var p = {};
    for (var key in Settings) {
        if (key.match("^PARAM")=="PARAM") {
            p[key] = Settings[key];
        }
    }
    return p;
}());

Settings.REST_URL = Settings.TOMCAT_WEBAPP + Settings.REST_MOUNT_POINT;

// lets assume we dont need a min width/height for table mode
if (Settings.MODE == "table") {
    Settings.DIMENSION_PREFETCH = false;
    $('body, html').css('min-height',0);
    $('body, html').css('min-width',0);

}
if (Settings.BIPLUGIN5) {
    Settings.BIPLUGIN = true;
}

Settings.INITIAL_QUERY = false;
if (document.location.hash) {
    var hash = document.location.hash;
    if (hash.length > 11 && hash.substring(1, 11) == "query/open") {
        Settings.INITIAL_QUERY = true;
    }
}

Settings.MONDRIAN_LOCALES = {
    "English": "en_US",
    "Dutch": "nl_BE",
    "French": "fr_FR"
};

/**
 * < IE9 doesn't support Array.indexOf
 */
if (!Array.prototype.indexOf)
{
  Array.prototype.indexOf = function(elt /*, from*/)
  {
    var len = this.length >>> 0;

    var from = Number(arguments[1]) || 0;
    from = (from < 0) ? Math.ceil(from) : Math.floor(from);
    if (from < 0)
      from += len;

    for (; from < len; from++)
    {
      if (from in this &&
          this[from] === elt)
        return from;
    }
    return -1;
  };
}

/**
 * IE9, 10 and 11 doesn't have window.location.origin
 */
if (!window.location.origin) {
    window.location.origin = window.location.protocol + '//' + window.location.hostname + (window.location.port ? ':' + window.location.port: '');
    // force update
    Settings.BASE_URL = window.location.origin;
}

var tagsToReplace = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;'
};

function replaceTag(tag) {
    return tagsToReplace[tag] || tag;
}

function safe_tags_replace(str) {
    return str.replace(/[&<>]/g, replaceTag);
}

if ($.blockUI) {
    $.blockUI.defaults.css = {};
    $.blockUI.defaults.overlayCSS = {};
    $.blockUI.defaults.blockMsgClass = 'processing';
    $.blockUI.defaults.fadeOut = 0;
    $.blockUI.defaults.fadeIn = 0;
    $.blockUI.defaults.ignoreIfBlocked = false;

}

if (window.location.hostname && (window.location.hostname == "try.meteorite.bi" )) {
    Settings.USERNAME = "admin";
    Settings.PASSWORD = "admin";
    Settings.DEMO = true;
    Settings.UPGRADE = false;
}

var isIE = (function(){
    var undef, v = 3;

    var dav = navigator.appVersion;

    if(dav.indexOf('MSIE') != -1) {
        v  = parseFloat(dav.split('MSIE ')[1]);
        return v> 4 ? v : false;
    }
    return false;

}());

var isFF = (function(userAgent) {
    'use strict';

    return !!userAgent.match(/Firefox/);
}(navigator.userAgent));

var isMobile = (function(userAgent) {
  'use strict';

  return !!userAgent.match(/android|webos|ip(hone|ad|od)|opera (mini|mobi|tablet)|iemobile|windows.+(phone|touch)|mobile|fennec|kindle (Fire)|Silk|maemo|blackberry|playbook|bb10\; (touch|kbd)|Symbian(OS)|Ubuntu Touch/i);
}(navigator.userAgent));

/**
 * Extend settings with charts colors
 */
 Settings.CHART_COLORS = ["#1f77b4", "#aec7e8", "#ff7f0e", "#ffbb78", "#2ca02c", "#98df8a", "#d62728", "#ff9896", "#9467bd", "#c5b0d5", "#8c564b", "#c49c94", "#e377c2", "#f7b6d2", "#7f7f7f", "#c7c7c7", "#bcbd22", "#dbdb8d", "#17becf", "#9edae5"];