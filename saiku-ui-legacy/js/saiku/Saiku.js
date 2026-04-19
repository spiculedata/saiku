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
 * Central object for handling global application state
 */
var Saiku = {
    /**
     * View which manages toolbar interactions
     */
    toolbar: {},

    /**
     * View which handles tabs
     */
    tabs: new TabSet(),

    /**
     * Stop the intro plugin when it finishes
     */
    introDone: false,

    /**
     * Saiku SplashScreen
     */
    splash: new SplashScreen({ toolbar: this.toolbar }),

    /**
     * Model which handles session and authentication
     */
    session: null,

    /**
     * Global event bus
     */
    events: _.extend({}, Backbone.Events),

    /**
     * Collection of routers for page fragments
     */
    routers: [],

    /**
     * Create a new variable for Leaflet interactive maps
     */
    leaflet: (typeof L !== 'undefined') ? L : {},

    /**
     * Convenience functions for blocking the UI
     */
    ui: {
        block: function(message) {
            $('.processing_message').html(message);
            $('.processing_message').removeClass('i18n_translated').addClass('i18n');

            Saiku.i18n.translate();

            $('.processing, .processing_container').show();
        },

        unblock: function() {
            $('.processing, .processing_container, .blockOverlay').hide();

            // Fix for Internet Explorer 10 UIBlock issue
            $('.blockUI').fadeOut('slow');
        }
    },

    /**
     * Outputs a message to the Web Console
     */
    log: function(channel, item) {
        if (console && console.log) {
            console.log('Logging for: ' + channel);

            if (item) {
                console.log(item);
            }
        }
    },

    /**
     * Outputs an error message to the Web Console
     */
    error: function(channel, item) {
        if (console && console.error) {
            console.error('Logging for: ' + channel);
            console.error(item);
        }
    },

    /**
     * Defines utility methods to work with the query string of a URL
     *
     * @example:
     *
     *    Put in your URL:
     *    http://<YOUR_MACHINE_IP>:8080/?splash=false
     *
     *    var paramsURI = Saiku.URLParams.paramsURI();
     *
     *    if (Saiku.URLParams.contains({ splash: paramsURI.splash })) {
     *        // Do something...
     *    }
     */
    URLParams: {
        buildValue: function(value) {
            if (/^\s*$/.test(value))           { return null; }
            if (/^(true|false)$/i.test(value)) { return value.toLowerCase() === 'true'; }
            if (isFinite(value))               { return parseFloat(value); }

            return value;
        },

        joinArrayValues: function(values) {
            if (values.length === 2) {
                return values[1];
            }
            else {
                return values[1] + '=' + values[2];
            }
        },

        paramsURI: function() {
            var paramsURI = {};
            var couples = window.location.search.substr(1).split('&');
            var lenCouples = couples.length;
            var keyId;
            var keyValue;

            if (window.location.search.length > 1) {
                for (keyId = 0; keyId < lenCouples; keyId++) {
                    keyValue = couples[keyId].split('=');
                    paramsURI[decodeURIComponent(keyValue[0])] = keyValue.length > 1
                        // ? this.buildValue(decodeURIComponent(keyValue[1]))
                        ? this.buildValue(decodeURIComponent(this.joinArrayValues(keyValue)))
                        : null;
                }
            }

            return paramsURI;
        },

        equals: function() {
            var params = Array.prototype.slice.call(arguments);
            var paramsURI = this.paramsURI();

            if (_.isEqual(paramsURI, params[0])) {
                return true;
            }
            else {
                return false;
            }
        },

        contains: function() {
            var params = Array.prototype.slice.call(arguments);
            var paramsURI = this.paramsURI();
            var common = {};

            for (var key in paramsURI) {
                if (paramsURI.hasOwnProperty(key)) {
                    if (params[0][key] && paramsURI[key] === params[0][key]) {
                        common[key] = params[0][key];
                    }
                }
            }

            if (_.isEqual(common, params[0])) {
                return true;
            }
            else {
                return false;
            }
        }
    },

    /**
     * A function for loading CSS asynchronously
     *
     * @example:
     *
     *    Saiku.loadCSS('PATH_OF_YOUR_CSS');
     */
    loadCSS: function(href, media) {
        var cssNode = window.document.createElement('link');
        var ref = window.document.getElementsByTagName('script')[0];

        cssNode.rel = 'stylesheet';
        cssNode.href = href;

        // Temporarily, set media to something non-matching to
        // ensure it'll fetch without blocking render
        cssNode.media = 'only x';

        // Inject link
        ref.parentNode.insertBefore(cssNode, ref);

        // Set media back to `all` so that the
        // stylesheet applies once it loads
        setTimeout(function() {
            cssNode.media = media || 'all';
        });

        return cssNode;
    },

    /**
     * A function for asynchronously loading JavaScript files
     *
     * @example:
     *
     *    Saiku.loadJS('PATH_OF_YOUR_JS');
     */
    loadJS: function(src, callback) {
        var scriptNode = window.document.createElement('script');
        var ref = window.document.getElementsByTagName('script')[0];

        scriptNode.src = src;
        scriptNode.async = true;

        // Inject script
        ref.parentNode.insertBefore(scriptNode, ref);

        // if callback...
        if (callback && typeof(callback) === 'function') {
            scriptNode.onload = callback;
        }

        return scriptNode;
    },

    /**
     * Converts value using a mask
     *
     * @example:
     *
     *    Saiku.toPattern(1099911111, '(99) 9999-9999');  // -> (10) 9991-1111
     *    Saiku.toPattern(12122000, '99/99/9999');        // -> 12/12/2000
     *    Saiku.toPattern(99911111101, '999.999.999-99'); // -> 999.111.111-01
     *    Saiku.toPattern('ABC1234', 'AAA-9999');         // -> ABC-1234
     */
    toPattern: function(value, opts) {
        var DIGIT = '9';
        var ALPHA = 'A';
        var ALPHANUM = 'S';
        var output = (typeof opts === 'object' ? opts.pattern : opts).split('');
        var values = value ? value.toString().replace(/[^0-9a-zA-Z]/g, '') : '';
        var index = 0;
        var len = output.length;

        for (var i = 0; i < len; i++) {
            if (index >= values.length) {
                break;
            }
            if ((output[i] === DIGIT && values[index].match(/[0-9]/)) ||
                (output[i] === ALPHA && values[index].match(/[a-zA-Z]/)) ||
                (output[i] === ALPHANUM && values[index].match(/[0-9a-zA-Z]/))) {
                output[i] = values[index++];
            }
            else if (output[i] === DIGIT ||
                     output[i] === ALPHA ||
                     output[i] === ALPHANUM) {
                output = output.slice(0, i);
            }
        }

        return output.join('').substr(0, i);
    },

    /**
     * Converts value using a mask
     *
     * @example:
     *
     *    Saiku.replaceString('World', 'Web', 'Saiku Analytics World'); // -> Saiku Analytics Web
     */
    replaceString: function(oldS, newS, fullS) {
        var len = fullS.length;

        for (var i = 0; i < len; i++) {
            if (fullS.substring(i, i + oldS.length) === oldS) {
                fullS = fullS.substring(0, i) + newS + fullS.substring(i + oldS.length, fullS.length);
            }
        }

        return fullS;
    },

    /**
     * Remove brackets
     *
     * @example:
     *
     *    Saiku.removeBrackets('[Time].[Time].[Year]'); // -> Time.Time.Year
     */
    removeBrackets: function(value) {
        var str = value.toString();

        return str.replace(/[\[\]]/gi, '');
    },

    /**
     * Trim first or last char or both
     *
     * @example:
     *
     *    Saiku.trimFirstLastChar('Saiku Analytics', 'first'); // -> aiku Analytics
     *    Saiku.trimFirstLastChar('Saiku Analytics', 'last');  // -> Saiku Analytic
     *    Saiku.trimFirstLastChar('Saiku Analytics');          // -> aiku Analytic
     */
    trimFirstLastChar: function(value, trimPosChar) {
        var str = value.toString();

        if (trimPosChar === 'first') {
            return str.substring(1, (str.length));
        }
        else if (trimPosChar === 'last') {
            return str.substring(0, (str.length - 1));
        }
        else {
            // Trim first and last char
            return str.substring(1, (str.length - 1));
        }
    }
};

/**
 * Saiku Singleton pattern
 */
Saiku.singleton = (function() {
    'use strict';

    var instance;

    Saiku.singleton = function() {
        if (instance) {
            return instance;
        }

        instance = this;

        this.set = function(data) {
            this.data = data;
        };

        this.get = function() {
            return this.data;
        };
    };

    return Saiku.singleton;
}());

/**
 * Setting this option to true will fake PUT and DELETE requests
 * with a HTTP POST, and pass them under the _method parameter.
 * Setting this option will also set an X-HTTP-Method-Override header
 * with the true method. This is required for BI server integration
 */
Backbone.emulateHTTP = false;

/**
 * Dynamically load plugins!
 *
 * @type {PluginCollection}
 */
if (!Settings.BIPLUGIN) {
    $(document).ready(function() {
        var plugins = new PluginCollection();

        plugins.fetch({
            success: function() {
                var settingsoverride = new SettingsOverrideCollection();

                settingsoverride.fetch({
                    success: function() {
                        var i = plugins.size();
                        var j = 0;

                        plugins.each(function(log) {
                            j = j + 1;

                            if (log.attributes.path !== 'js/saiku/plugins/I18n/plugin.js') {
                                jQuery.ajax({
                                    async: false,
                                    type: 'GET',
                                    url: log.attributes.path,
                                    data: null,
                                    success: function() {
                                        if (j === i) {
                                            var k = settingsoverride.size();
                                            var l = 0;

                                            settingsoverride.each(function(log) {
                                                l = l + 1;

                                                for (var key in log.attributes) {
                                                    if (log.attributes.hasOwnProperty(key)) {
                                                        Settings[key] = log.attributes[key];
                                                    }
                                                }

                                                if (Settings.CSS !== undefined) {
                                                    Saiku.loadCSS(Settings.CSS, null);
                                                }

                                                if (k === l) {
                                                    Saiku.session = new Session({}, {
                                                        username: Settings.USERNAME,
                                                        password: Settings.PASSWORD
                                                    });

                                                    Saiku.toolbar = new Toolbar();
                                                }
                                            });
                                        }
                                    },
                                    dataType: 'script'
                                });
                            }
                            else {
                                if (j === i) {
                                    var k = settingsoverride.size();
                                    var l = 0;

                                    settingsoverride.each(function(log) {
                                        l = l + 1;

                                        for (var key in log.attributes) {
                                            if (log.attributes.hasOwnProperty(key)) {
                                                Settings[key] = log.attributes[key];
                                            }
                                        }

                                        if (Settings.CSS !== undefined) {
                                            Saiku.loadCSS(Settings.CSS, null);
                                        }

                                        if (k === l) {
                                            Saiku.session = new Session({}, {
                                                username: Settings.USERNAME,
                                                password: Settings.PASSWORD
                                            });

                                            Saiku.toolbar = new Toolbar();
                                        }
                                    });
                                }
                            }
                        });
                    },
                    error: function() {
                        var i = plugins.size();
                        var j = 0;

                        plugins.each(function(log) {
                            j = j + 1;

                            if (log.attributes.path !== 'js/saiku/plugins/I18n/plugin.js') {
                                jQuery.ajax({
                                    async: false,
                                    type: 'GET',
                                    url: log.attributes.path,
                                    data: null,
                                    success: function() {
                                        if (j === i) {
                                            if (Settings.CSS !== undefined) {
                                                Saiku.loadCSS(Settings.CSS, null);
                                            }

                                            Saiku.session = new Session({}, {
                                                username: Settings.USERNAME,
                                                password: Settings.PASSWORD
                                            });

                                            Saiku.toolbar = new Toolbar();
                                        }
                                    },
                                    dataType: 'script'
                                });
                            }
                            else {
                                if (j === i) {
                                    if (Settings.CSS !== undefined) {
                                        Saiku.loadCSS(Settings.CSS, null);
                                    }

                                    Saiku.session = new Session({}, {
                                        username: Settings.USERNAME,
                                        password: Settings.PASSWORD
                                    });

                                    Saiku.toolbar = new Toolbar();
                                }
                            }
                        });
                    }
                });
            }
        });
    });
}

var SaikuTimeLogger = function(element) {
    this._element = $(element);
    this._timestamps = [];
    this._events = [];
};

SaikuTimeLogger.prototype.log = function(eventname) {
    var time = (new Date()).getTime();

    if (!eventname) {
        eventname = 'Unknown';
    }

    if (this._timestamps.length > 0) {
        var lastTime = this._timestamps[this._timestamps.length -1];

        if ((time - lastTime) > 1) {
            this._element.append('<div>' + (time - lastTime) + ' ms ' + eventname + ' (previous: ' + this._events[this._events.length -1]  + ' )</div>');
        }
    }

    this._timestamps.push(time);
    this._events.push(eventname);
};
