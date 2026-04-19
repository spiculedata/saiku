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
 * Model which fetches the information of Data Sources
 *
 * @class DateSources
 */
var DataSources = Backbone.Model.extend({
    /**
     * Returns the relative URL where the model's resource would be located on the server
     *
     * @property url
     * @type {String}
     * @private
     */
	url: 'admin/attacheddatasources',

    /**
     * The constructor of view, it will be called when the view is first created
     *
     * @constructor
     * @private
     */
	initialize: function(args, options) {
        if (options && options.dialog) {
        	this.dialog = options.dialog;
        }
	},

	/**
	 * Parse is called whenever a model's data is returned by the server, in fetch, and save
	 *
	 * @method parse
	 * @private
	 * @param  {Object} response Returned data from the server
	 */
    parse: function(response) {
        if (this.dialog && this.dialog.callback) {
            this.dialog.callback(response);
        }
        else {
            return response;
        }
    }
});

