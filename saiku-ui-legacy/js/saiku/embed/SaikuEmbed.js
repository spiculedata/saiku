/*
 *   Copyright 2015 OSBI Ltd
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
 *
 * @public
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
 * @public
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
	 * @default
	 * 		{
	 * 			server: '/saiku',
	 * 			path: '/rest/saiku/embed',
	 * 			user: 'admin',
	 * 			password: 'admin',
	 * 			blockUI: false
	 * 		}
	 */
	var _settings = {
		server: '/saiku',
		path: '/rest/saiku/embed',
		user: 'admin',
		password: 'admin',
		blockUI: false
	};

	/**
	 * The configuration options to render the file on page
	 *
	 * @property _options
	 * @type {Object}
	 * @private
	 * @default
	 * 		{
	 * 			file: null,
	 * 			render: 'table',
	 * 			mode: null,
	 * 			formatter: 'flattened',
	 * 			htmlObject: '#saiku',
	 * 			zoom: true,
	 * 			params: {}
	 * 		}
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
	 * Factory for render layout
	 *
	 * @property _saikuRendererFactory
	 * @type {Object}
	 * @private
	 * @default
	 * 		{
	 *      	'table': SaikuTableRenderer,
	 *          'chart': SaikuChartRenderer
	 *      }
	 */
	var _saikuRendererFactory = {
		'table': SaikuTableRenderer,
		'chart': SaikuChartRenderer,
		'map': typeof SaikuMapRenderer !== 'undefined' ? SaikuMapRenderer : '',
		'playground': typeof SaikuPlaygroundRenderer !== 'undefined' ? SaikuPlaygroundRenderer : ''
	};

	/**
	 * Add levels and parameter names
	 *
	 * @private
	 * @param  {String} dataSchema [connection].[catalog].[schema].[cube]
	 * @param  {Object} dataAxis Axis FILTER, COLUMNS and ROWS with values in hierarchies
	 * @return {Object} Levels and parameter names
	 */
	function joinParameters(dataSchema, dataAxis) {
		var parametersLevels = [];

		_.each(dataAxis, function(axis) {
			_.each(axis, function(value) {
				_.each(value.levels, function(levels) {
					if (levels.selection.parameterName) {
						parametersLevels.push({
							levels: dataSchema + '.' + value.name + '.[' + levels.name + ']',
							parameterName: levels.selection.parameterName
						});
					}
				});
			});
		});

		return parametersLevels;
	}

	/**
	 * That constructor enforces the use of new, even if you call the constructor like a function
	 *
	 * @constructor
	 * @private
	 * @param  {Object} opts Settings for the request
	 */
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
 	 * @public
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

		if (typeof ga !== 'undefined') {
			ga('send', 'event', 'SaikuClient', 'Execute');
		}

		if ($.blockUI && this.settings.blockUI) {
			$.blockUI.defaults.css = { 'color': 'black', 'font-weight': 'normal' };
			$.blockUI.defaults.overlayCSS = {};
			$.blockUI.defaults.blockMsgClass = 'processing';
			$.blockUI.defaults.fadeOut = 0;
			$.blockUI.defaults.fadeIn = 0;
			$.blockUI.defaults.ignoreIfBlocked = false;
		}

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

		if ($.blockUI && this.settings.blockUI) {
			$(options.htmlObject).block({
				message: '<span class="saiku_logo" style="float:left">&nbsp;&nbsp;</span> Executing....'
			});
		}

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
				if (data.query && data.height > 0 && data.width > 0) {
					var renderMode = data.query.properties['saiku.ui.render.mode'] ? data.query.properties['saiku.ui.render.mode'] : options.render;
					var mode = data.query.properties['saiku.ui.render.type'] ? data.query.properties['saiku.ui.render.type'] : options.mode;
					var chartDefinition = data.query.properties['saiku.ui.chart.options'] ? data.query.properties['saiku.ui.chart.options'].chartDefinition : '';
					var mapDefinition = data.query.properties['saiku.ui.map.options'] ? data.query.properties['saiku.ui.map.options'] : '';
					var dataSchema = data.query.cube.uniqueName;
					var parametersValues = data.query.parameters;
					var dataAxis = {};
					var parametersLevels;

					if (data.query.type === 'QUERYMODEL') {
						dataAxis = {
							dataFilter: data.query.queryModel.axes.FILTER['hierarchies'],
							dataColumns: data.query.queryModel.axes.COLUMNS['hierarchies'],
							dataRows: data.query.queryModel.axes.ROWS['hierarchies']
						};
					}

					if (self.settings.dashboards) {
						if (options.dropDashboards === undefined) {
							renderMode = options.render;
							mode = options.mode;
						}

						parametersLevels = joinParameters(dataSchema, dataAxis);
						$(options.htmlObject).closest('.gs-w').data('parametersLevels', JSON.stringify(parametersLevels));
						$(options.htmlObject).closest('.gs-w').data('parametersValues', JSON.stringify(parametersValues));

						if (options.openDashboards) {
							$(options.htmlObject).closest('.gs-w').data('id', options.htmlObject);
							$(options.htmlObject).closest('.gs-w').data('title', options.title);
							$(options.htmlObject).closest('.gs-w').data('file', options.file);
							$(options.htmlObject).closest('.gs-w').data('htmlobject', options.htmlObject);
							$(options.htmlObject).closest('.gs-w').data('render', options.render);
							$(options.htmlObject).closest('.gs-w').data('mode', options.mode);
							$(options.htmlObject).closest('.gs-w').data('chartDefinition', JSON.stringify(options.chartDefinition));
							$(options.htmlObject).closest('.gs-w').data('mapDefinition', JSON.stringify(options.mapDefinition));
						}
						else if (options.dropDashboards) {
							if (!(_.isEmpty(chartDefinition))) {
								options['chartDefinition'] = chartDefinition;
								$(options.htmlObject).closest('.gs-w').data('file', options.file);
								$(options.htmlObject).closest('.gs-w').data('htmlobject', options.htmlObject);
								$(options.htmlObject).closest('.gs-w').data('render', renderMode);
								$(options.htmlObject).closest('.gs-w').data('mode', mode);
								$(options.htmlObject).closest('.gs-w').data('chartDefinition', JSON.stringify(chartDefinition));
							}
							else if (!(_.isEmpty(mapDefinition))) {
								if (Settings.MAPS && Settings.MAPS_TYPE === 'OSM') {
									options['mapDefinition'] = mapDefinition;
									$(options.htmlObject).closest('.gs-w').data('file', options.file);
									$(options.htmlObject).closest('.gs-w').data('htmlobject', options.htmlObject);
									$(options.htmlObject).closest('.gs-w').data('render', renderMode);
									$(options.htmlObject).closest('.gs-w').data('mode', mode);
									$(options.htmlObject).closest('.gs-w').data('mapDefinition', JSON.stringify(mapDefinition));
								}
								else {
									$(options.htmlObject).closest('.gs-w').data('file', options.file);
									$(options.htmlObject).closest('.gs-w').data('htmlobject', options.htmlObject);
									$(options.htmlObject).closest('.gs-w').data('render', 'chart');
									$(options.htmlObject).closest('.gs-w').data('mode', 'stackedBar');
									$(options.htmlObject).closest('.gs-w').data('chartDefinition', '');
									$(options.htmlObject).closest('.gs-w').data('mapDefinition', '');
								}
							}
							else {
								$(options.htmlObject).closest('.gs-w').data('file', options.file);
								$(options.htmlObject).closest('.gs-w').data('htmlobject', options.htmlObject);
								$(options.htmlObject).closest('.gs-w').data('render', renderMode);
								$(options.htmlObject).closest('.gs-w').data('mode', mode);
								$(options.htmlObject).closest('.gs-w').data('chartDefinition', '');
								$(options.htmlObject).closest('.gs-w').data('mapDefinition', '');
							}
						}
					}
					else {
						renderMode = options.render;
						mode = options.mode;
					}

					options['render'] = renderMode;
					options['mode'] = mode;

					if (options.render in _saikuRendererFactory) {
						var saikuRenderer = new _saikuRendererFactory[options.render](data, options);

						if (options.render !== 'map') {
							saikuRenderer.render();
						}
						else {
							if (Settings.MAPS && Settings.MAPS_TYPE === 'OSM') {
								saikuRenderer.renderMap();
							}
							else {
								options.render = 'chart';
								options.mode = 'stackedBar';
								saikuRenderer = new _saikuRendererFactory[options.render](data, options);
								saikuRenderer.render();
							}
						}

						if ($.blockUI) {
							$(options.htmlObject).unblock();
						}
					}
					else {
						alert('Render type ' + options.render + ' not found!');
					}
					if ($.blockUI) {
						$(options.htmlObject).unblock();
					}
				}
				else {
					$(options.htmlObject).text('No data');

					if ($.blockUI) {
						$(options.htmlObject).unblock();
					}
				}
			},
			error: function(jqXHR, textStatus, errorThrown) {
				if ($.blockUI) {
					$(options.htmlObject).unblock();
				}
				$(options.htmlObject).text('No data');
				console.error(textStatus);
				console.error(jqXHR);
				console.error(errorThrown);
			}
		};

		$.ajax(params);
	};

	return SaikuClient;
}());
