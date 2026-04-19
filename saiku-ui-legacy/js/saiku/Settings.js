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

// If you would like to add more properties to Settings, add in "Settings.yaml"
var Settings = {};

$.ajax({
    async: false, // do not change to true
    type: 'GET',
    url: 'js/saiku/Settings.yaml',
    success: function(props) {
        // JavaScript YAML parser
        // link: https://github.com/nodeca/js-yaml
        Settings = jsyaml.load(props);
    }
});

Settings.BASE_URL = window.location.origin;

/**
 * Extend settings with query parameters
 */
Settings.GET = function () {
    var qs = document.location.search;
    var params = {};
    var tokens;
    var re = /[?&]?([^=]+)=([^&]*)/g;

    qs = qs.split('+').join(' ');
    tokens = re.exec(qs);

    while (tokens) {
        var value = decodeURIComponent(tokens[2]);

        if (!isNaN(value)) value = parseInt(value);
        if (value === 'true') value = true;
        if (value === 'false') value = false;
        if (decodeURIComponent(tokens[1].toUpperCase()).substring(0,5) === 'PARAM') {
            params['PARAM' + decodeURIComponent(tokens[1]).substring(5, decodeURIComponent(tokens[1]).length)] = value;
        }
        else {
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
        if (key.match('^PARAM') === 'PARAM') {
            p[key] = Settings[key];
        }
    }

    return p;
}());

Settings.REST_URL = Settings.TOMCAT_WEBAPP + Settings.REST_MOUNT_POINT;

// lets assume we dont need a min width/height for table mode
if (Settings.MODE === 'table') {
    Settings.DIMENSION_PREFETCH = false;
    $('body, html').css('min-height', 0);
    $('body, html').css('min-width', 0);
}
if (Settings.BIPLUGIN5) {
    Settings.BIPLUGIN = true;
}

Settings.INITIAL_QUERY = false;
if (document.location.hash) {
    var hash = document.location.hash;

    if (hash.length > 11 && hash.substring(1, 11) === 'query/open') {
        Settings.INITIAL_QUERY = true;
    }
}

Settings.MONDRIAN_LOCALES = {
    'English': 'en_US',
    'Dutch'  : 'nl_BE',
    'French' : 'fr_FR'
};

/**
 * < IE9 doesn't support Array.indexOf
 */
if (!Array.prototype.indexOf) {
    Array.prototype.indexOf = function(elt /*, from*/) {
        var len = this.length >>> 0;
        var from = Number(arguments[1]) || 0;

        from = (from < 0) ? Math.ceil(from) : Math.floor(from);

        if (from < 0) {
            from += len;
        }

        for (; from < len; from++) {
            if (from in this && this[from] === elt) {
                return from;
            }
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

if (window.location.hostname && (window.location.hostname === 'try.meteorite.bi')) {
    Settings.USERNAME = 'demo';
    Settings.PASSWORD = 'demo';
    Settings.DEMO = true;
    Settings.UPGRADE = false;
}

var isIE = (function(){
    var v = 3;
    var dav = navigator.appVersion;

    if (dav.indexOf('MSIE') !== -1) {
        v = parseFloat(dav.split('MSIE ')[1]);

        return v > 4 ? v : false;
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
Settings.CHART_COLORS = [
    '#1f77b4', '#aec7e8', '#ff7f0e', '#ffbb78', '#2ca02c',
    '#98df8a', '#d62728', '#ff9896', '#9467bd', '#c5b0d5',
    '#8c564b', '#c49c94', '#e377c2', '#f7b6d2', '#7f7f7f',
    '#c7c7c7', '#bcbd22', '#dbdb8d', '#17becf', '#9edae5'
];
