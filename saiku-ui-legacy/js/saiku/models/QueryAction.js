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
 * Model which handles "special" actions against the query
 * Ex.: selections, swap axis, mdx
 */
var QueryAction = Backbone.Model.extend({
    initialize: function(args, options) {
        // Keep track of query
        this.query = options.query;

        // Set default url
        this.url = this.query.url;
    },

    gett: function(action, options) {
        this.handle("fetch", action, options);
    },

    post: function(action, options) {
        this.handle("save", action, options);
    },

    put: function(action, options) {
        this.id = _.uniqueId('queryaction_');
        this.handle("save", action, options);
        delete this.id;
    },

    del: function(action, options) {
        this.id = _.uniqueId('queryaction_');
        this.handle("delete", action, options);
        delete this.id;
    },

    // Call arbitrary actions against the query
    handle: function(method, action, options) {
        // Set query action
        this.url = this.query.url() + action;

		var id = this.id;
        // Clear out old attributes
        this.attributes = options.data? options.data : {};

        // Initiate action
        if (method == "save") {
            // Handle response from server
            //this.parse = options.success;

            this.save({}, options);
        } else if (method == "delete") {
			this.set("id", this.id);
            this.destroy(options);
        } else if (method == "fetch") {
            this.parse = function() {};
            this.fetch(options);
        }
    }
});
