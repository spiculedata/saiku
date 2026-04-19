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
 * Base 64 module
 */

;(function (window) {
  var
    characters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=',
    fromCharCode = String.fromCharCode,
    INVALID_CHARACTER_ERR = (function () {
      // fabricate a suitable error object
      try { document.createElement('$'); }
      catch (error) { return error; }}());

  // encoder
  window.Base64 || (
  window.Base64 = { encode: function (string) {
    var
      a, b, b1, b2, b3, b4, c, i = 0,
      len = string.length, max = Math.max, result = '';

    while (i < len) {
      a = string.charCodeAt(i++) || 0;
      b = string.charCodeAt(i++) || 0;
      c = string.charCodeAt(i++) || 0;

      if (max(a, b, c) > 0xFF) {
        throw INVALID_CHARACTER_ERR;
      }

      b1 = (a >> 2) & 0x3F;
      b2 = ((a & 0x3) << 4) | ((b >> 4) & 0xF);
      b3 = ((b & 0xF) << 2) | ((c >> 6) & 0x3);
      b4 = c & 0x3F;

      if (!b) {
        b3 = b4 = 64;
      } else if (!c) {
        b4 = 64;
      }
      result += characters.charAt(b1) + characters.charAt(b2) + characters.charAt(b3) + characters.charAt(b4);
    }
    return result;
  }});

}(this));

/**
 * Model which handles AJAX calls to the Saiku Server
 * If you want to hook the UI up to something besides the Saiku Server,
 * this is the class which you want to override.
 * @returns {SaikuServer}
 */
Backbone.sync = function(method, model, options) {
    var params;
    methodMap = {
        'create': "POST",
        'read': "GET",
        'update': "PUT",
        'delete': "DELETE"
    };

    // Generate AJAX action
    var type = methodMap[method];
    var url = Settings.REST_URL + (_.isFunction(model.url) ? model.url() : model.url);

    // Prepare for failure
    if (typeof Settings.ERRORS == "undefined") {
        Settings.ERRORS = 0;
    }

    var errorLogout = function() {
        Settings.ERRORS++;
        if (Settings.ERRORS < Settings.ERROR_TOLERANCE) {
          Saiku.session.logout();
        } else {
          Saiku.ui.block("Communication problem with the server. Please reload the application...");
        }
    };
    var statuscode = {
      0: function() {
        errorLogout();
      },
      401: function() {
        errorLogout();
      }
    };

    var failure = function(jqXHR, textStatus, errorThrown) {
      if (!isIE && typeof console != "undefined" && console && console.error) {
        console.error("Error performing " + type + " on " + url);
        console.error(errorThrown);
      }
      if (options.error) {
        options.error(jqXHR, textStatus, errorThrown);
      }
    };

    var success = function(data, textStatus, jqXHR) {
      Settings.ERRORS = 0;
      Saiku.ui.unblock();
      options.success(data, textStatus, jqXHR);
    };
    var async = true;
    if (options.async === false) {
      async = false;
    }
    var dataType = 'json';
    if (typeof options.dataType != "undefined") {
      dataType = options.dataType;
    }

    var contentType = 'application/x-www-form-urlencoded';
    if (typeof options.contentType != "undefined") {
      contentType = options.contentType;
    }
    var data = model.attributes;
    if (typeof options.data != "undefined") {
      data = options.data;
    }
    // Default JSON-request options.
    params = {
      url:          url,
      type:         type,
      cache:        false,
      data:         data,
      contentType:  contentType,
      dataType:     dataType,
      success:      success,
      statusCode:   statuscode,
      error:        failure,
      async:        async//,
      //processData:  false
      /*
      beforeSend:   function(request) {
        if (!Settings.PLUGIN) {
          var auth = "Basic " + Base64.encode(
              Saiku.session.username + ":" + Saiku.session.password
          );
          request.setRequestHeader('Authorization', auth);
          }
      } */
    };

    if(options.processData === false){
        params.processData = false;
    }
    // For older servers, emulate HTTP by mimicking the HTTP method with `_method`
    // And an `X-HTTP-Method-Override` header.
    if ((Settings.BIPLUGIN && !Settings.BIPLUGIN5) || Backbone.emulateHTTP) {
      if (type === 'PUT' || type === 'DELETE') {
        if (Backbone.emulateHTTP) params.data._method = type;
        params.type = 'POST';
        params.beforeSend = function(xhr) {
          xhr.setRequestHeader("X-HTTP-Method-Override", type);
        };
      }
    }

    // Make the request
    $.ajax(params);
};
