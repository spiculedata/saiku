/**  
 * Copyright 2015 OSBI Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Base 64 module
 * 
 * @param  {window} window Window is passed through as local variable rather than global
 * @return {String} Encoding data
 */
;(function(window) {
	'use strict';

	var characters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
	var fromCharCode = String.fromCharCode;
	var INVALID_CHARACTER_ERR = (function() {
			// Fabricate a suitable error object
			try { 
				document.createElement('$'); 
			}
			catch(error) { 
				return error; 
			}
		}());

	// Encoder
	window.Base64 || (
		window.Base64 = { encode: function(string) {
			var a, b, b1, b2, b3, b4, c, i = 0,
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
				} 
				else if (!c) {
					b4 = 64;
				}
				result += characters.charAt(b1) + characters.charAt(b2) + characters.charAt(b3) + characters.charAt(b4);
			}

			return result;
		}});
}(this));

/**
 * IE Browser detection
 * 
 * @return {Boolean} If `true` return the value of `v`, else return `false`
 */
var isIE = (function() {
	'use strict';

	var undef, v = 3;
	var dav = navigator.appVersion;

	if (dav.indexOf('MSIE') !== -1) {
		v  = parseFloat(dav.split('MSIE ')[1]);
		return v > 4 ? v : false;
	}

	return false;
}());

/**
 * A client for working with files Saiku
 *
 * @class
 * @constructor
 * @chainable
 * @example
 * 		var myClient = new SaikuClient({
 * 			server: '/saiku',
 * 			path: '/rest/saiku/embed',
 * 			user: 'admin',
 * 			password: 'admin'
 * 		});
 * @return {SaikuClient} The SaikuClient instance (for chaining)
 */
var SaikuClient = (function() {
	'use strict';

	/**
	 * The configuration settings for the request
	 * 
	 * @property _settings
	 * @type {Object}
	 * @private
	 * @default { server: '/saiku', path: '/rest/saiku/embed', user: 'admin', password: 'admin' }
	 */
	var _settings = {
		server: '/saiku',
		path: '/rest/saiku/embed',
		user: 'admin',
		password: 'admin'
	};

	/**
	 * The configuration options to render the file on page
	 * 
	 * @property _options
	 * @type {Object}
	 * @private
	 * @default { file: null, render: 'table', mode: null, formatter: 'flattened', htmlObject: '#saiku', zoom: true, params: {} }
	 */
	var _options = {
		file: null,
		render: 'table', // table || chart
		mode: null,      // table: sparkline, sparkbar || chart: line, bar, treemap, ...
		formatter: 'flattened', // Should be left unless you want an hierarchical resultset
		htmlObject: '#saiku',
		zoom: true,
		params: {}
	};

	/**
	 * Instance of SaikuTableRenderer and SaikuChartRenderer
	 *
	 * @property _SaikuRendererFactory
	 * @type {Object}
	 * @private
	 * @default { 'table': SaikuTableRenderer, 'chart': SaikuChartRenderer }
	 */
	var _SaikuRendererFactory = {
		'table': SaikuTableRenderer,
		'chart': SaikuChartRenderer
	};

	function SaikuClient(opts) {
		// Enforces new
		if (!(this instanceof SaikuClient)) {
			return new SaikuClient(opts);
		}

		this.settings = _.extend(_settings, opts);
	}

	/**
	 * Method for execute the requests of files Saiku
	 *
	 * @method execute
	 * @param  {Object} opts The configuration options to render the file on page
	 * @example
	 * 		myClient.execute({
	 *   		file: '/homes/home:admin/report.saiku',
	 *     		htmlObject: '#panel-body',
	 *       	render: 'table',
	 *      });
	 */
	SaikuClient.prototype.execute = function(opts) {
		var self = this;
		var options = _.extend({}, _options, opts);
		var parameters = {};

		if (options.params) {
			for (var key in options.params) {
				if (options.params.hasOwnProperty(key)) {
					parameters['param' + key] = options.params[key];
				}
			}
		}

		parameters = _.extend(
			parameters,
			{ 'formatter': options.formatter },
			{ 'file': options.file }
		);

		var params = {
			url: self.settings.server + (self.settings.path ? self.settings.path : '') + '/export/saiku/json',
			type: 'GET',
			cache: false,
			data: parameters,
			contentType: 'application/x-www-form-urlencoded',
			dataType: 'json',
			crossDomain: true,
			async: true,
			beforeSend: function(request) {
				if (self.settings.user && self.settings.password) {
					var auth = 'Basic ' + Base64.encode(
							self.settings.user + ':' + self.settings.password
						);
					request.setRequestHeader('Authorization', auth);
					return true;
				}
			},
			success: function(data, textStatus, jqXHR) {
				var renderMode = data.query.properties['saiku.ui.render.mode'] ? data.query.properties['saiku.ui.render.mode'] : options.render;
				var mode =  data.query.properties['saiku.ui.render.type'] ? data.query.properties['saiku.ui.render.type'] : options.mode;

				options['mode'] = mode;

				if (options.render in _SaikuRendererFactory) {
					var saikuRenderer = new _SaikuRendererFactory[options.render](data, options);
					saikuRenderer.render();
				}
				else {
					alert('Render type ' + options.render + ' not found!');
				}
			},
			error: function(jqXHR, textStatus, errorThrown) {
				$(options.htmlObject).text('Error: ' + textStatus);
				console.error(textStatus);
				console.error(jqXHR);
				console.error(errorThrown);
			}
		};

		$.ajax(params);
	};

	return SaikuClient;
}());
