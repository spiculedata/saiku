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
 * Workspace query
 */
var Query = Backbone.Model.extend({

    formatter: Settings.CELLSET_FORMATTER,
    properties: null,

    initialize: function(args, options) {
        // Save cube
        _.extend(this, options);

        // Bind `this`
        _.bindAll(this, "run");

        // Generate a unique query id
        this.uuid = 'xxxxxxxx-xxxx-xxxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,
            function (c) {
                var r = Math.random() * 16 | 0,
                v = c == 'x' ? r : (r & 0x3 | 0x8);
                return v.toString(16);
            }).toUpperCase();

        this.model = _.extend({ name: this.uuid }, SaikuOlapQueryTemplate);
        if (args.cube) {
            this.model.cube = args.cube;
        }
        this.helper = new SaikuOlapQueryHelper(this);

        // Initialize properties, action handler, and result handler
        this.action = new QueryAction({}, { query: this });
        this.result = new Result({ limit: Settings.RESULT_LIMIT }, { query: this });
        this.scenario = new QueryScenario({}, { query: this });

        // A flag to tell who changed selection members
        this.updatedSelectionFromModal = false;
    },

    parse: function(response) {
        // Assign id so Backbone knows to PUT instead of POST
        this.id = this.uuid;
        if (response.name) {
            this.id = response.name;
            this.uuid = response.name;
        }
        this.model = _.extend(this.model, response);
        this.model.properties = _.extend({}, Settings.QUERY_PROPERTIES, this.model.properties);
    },

    setProperty: function(key, value) {
            this.model.properties[key] = value;
    },

    getProperty: function(key) {
        return this.model.properties[key];
    },

    syncSelectionsModalAndUpdateParameters: function() {
        if (this.updatedSelectionFromModal) {
            var mParameters = this.helper.model().parameters;
            for (var mKey in mParameters) {
                var mVal       = mParameters[mKey];
                var selections = this.helper.getSelectionsForParameter(mKey);

                mVal = selections.map(function(sel) { return sel.caption; }).join();
                mParameters[mKey] = mVal;
            }
        } else {
            var mParameters = this.helper.model().parameters;
            for (var mKey in mParameters) {
                var mVal       = mParameters[mKey];
                var mLevel     = this.helper.getLevelForParameter(mKey);
                var selections = this.helper.getSelectionsForParameter(mKey);

                if (mVal !== null && mVal !== undefined) {
                    this.helper.setSelectionsForParameter(mKey, _.filter(selections, function(sel) {
                        var containsParam = false;
                        _.each(mVal.split(','), function (v) {
                            if (sel.caption === v) {
                                containsParam = true;
                                return false;
                            }
                        });
                        return containsParam;
                    }));
                }
            }
        }

        this.updatedSelectionFromModal = false;
    },

    run: function(force, mdx) {
        this.syncSelectionsModalAndUpdateParameters();

        var self = this;
        // Check for automatic execution
        Saiku.ui.unblock();
        if (typeof this.model.properties != "undefined" && this.model.properties['saiku.olap.query.automatic_execution'] === false &&
			(force === false || force === undefined || force === null)) {
            return;
        }
        this.workspace.unblock();

        $(this.workspace.el).find(".workspace_results_info").empty();
        this.workspace.trigger('query:run');
        this.result.result = null;
        var validated = false;
        var errorMessage = '<span class="i18n">Query Validation failed!</span>';

        var exModel = this.helper.model();
		for(var k in this.attributes) {
			var att = this.attributes[k];
			if(k.substring(0,5)==="PARAM"){
				var p = k.substring(5, k.length);
				exModel.parameters[p] = att;
			}

		}
        if (exModel.queryType == "OLAP") {
            if (exModel.type == "QUERYMODEL") {
                var columnsOk = Object.keys(exModel.queryModel.axes.COLUMNS.hierarchies).length > 0;
                var rowsOk = Object.keys(exModel.queryModel.axes.ROWS.hierarchies).length > 0;
                var detailsOk = exModel.queryModel.details.axis == 'COLUMNS' && exModel.queryModel.details.measures.length > 0;
                if (!rowsOk || !columnsOk || !detailsOk) {
                    errorMessage = "";
                }
                if (!columnsOk && !detailsOk) {
                    errorMessage += '<span class="i18n">You need to include at least one measure or a level on columns for a valid query.</span>';
                }
                if(!rowsOk) {
                    errorMessage += '<span class="i18n">You need to include at least one level on rows for a valid query.</span>';

                }
                if ( (columnsOk || detailsOk) && rowsOk) {
                    validated = true;
                }

            } else if (exModel.type == "MDX") {
                validated = (exModel.mdx && exModel.mdx.length > 0);
                if (!validated) {
                    errorMessage = '<span class="i18n">You need to enter some MDX statement to execute.</span>';
                }
            }
        }
        if (!validated) {
            this.workspace.table.clearOut();
            $(this.workspace.processing).html(errorMessage).show();
            this.workspace.adjust();
            Saiku.i18n.translate();
            return;
        }


        // Run it
        this.workspace.table.clearOut();
        $(this.workspace.processing).html('<span class="processing_image">&nbsp;&nbsp;</span> <span class="i18n">Running query...</span> [&nbsp;<a class="cancel i18n" href="#cancel">Cancel</a>&nbsp;]').show();
        this.workspace.adjust();
        this.workspace.trigger('query:fetch');
		Saiku.i18n.translate();
        var message = '<span class="processing_image">&nbsp;&nbsp;</span> <span class="i18n">Running query...</span> [&nbsp;<a class="cancel i18n" href="#cancel">Cancel</a>&nbsp;]';
        this.workspace.block(message);
/*
        TODO: i wonder if we should clean up the model (name and captions etc.)
        delete this.model.queryModel.axes['FILTER'].name;
*/
        //console.log('query', JSON.stringify(exModel));

        this.result.save({},{ contentType: "application/json", data: JSON.stringify(exModel), error: function() {
            Saiku.ui.unblock();
            var errorMessage = '<span class="i18n">Error executing query. Please check the server logs or contact your administrator!</span>';
            self.workspace.table.clearOut();
            $(self.workspace.processing).html(errorMessage).show();
            self.workspace.adjust();
            Saiku.i18n.translate();
        } });
    },

    enrich: function() {
        var self = this;
        this.workspace.query.action.post("/../enrich", {
            contentType: "application/json",
            data: JSON.stringify(self.model),
            async: false,
            success: function(response, model) {
                self.model = model;
            }
        });
    },

    url: function() {
        return "api/query/" + encodeURI(this.uuid);
    }
});


