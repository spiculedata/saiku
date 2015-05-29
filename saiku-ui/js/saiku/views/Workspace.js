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
 * The analysis workspace
 */
var Workspace = Backbone.View.extend({
    className: 'tab_container',

    events: {
        'click .sidebar_separator': 'toggle_sidebar',
        'change .cubes': 'new_query',
        'drop .sidebar': 'remove_dimension',
        'drop .workspace_results': 'remove_dimension',
        'click .refresh_cubes' : 'refresh',
        'click .cancel' : 'cancel',
    },

    initialize: function(args) {
        // Maintain `this` in jQuery event handlers
        _.bindAll(this, "caption", "adjust", "toggle_sidebar", "prepare", "new_query",
                "init_query", "update_caption", "populate_selections","refresh", "sync_query", "cancel", "cancelled", "no_results", "error", "switch_view_state");

        // Attach an event bus to the workspace
        _.extend(this, Backbone.Events);
        this.loaded = false;
        this.bind('query:result',this.render_result);

        // Generate toolbar and append to workspace
        this.toolbar = new WorkspaceToolbar({ workspace: this });
        this.toolbar.render();

        this.querytoolbar = new QueryToolbar({ workspace: this });
        this.querytoolbar.render();

        // Create drop zones
        this.drop_zones = new WorkspaceDropZone({ workspace: this });
        this.drop_zones.render();

        // Generate table
        this.table = new Table({ workspace: this });

        this.chart = new Chart({ workspace: this });

        // Create instance for Date Filter
        this.dateFilter = new DateFilterCollection();

        // Pull query from args
        this.item = {};
        this.viewState = (args && args.viewState) ? args.viewState : Settings.DEFAULT_VIEW_STATE; // view / edit
        this.isReadOnly = (Settings.MODE == 'view' || false);
        if (args && args.item) {
            this.item = args.item;
            if (this.item && this.item.hasOwnProperty('acl') && _.indexOf(this.item.acl, "WRITE") <  0) {
                this.isReadOnly = true;
                this.viewState = 'view';
            }
        }
        if (!args || (!args.query && !args.viewState)) {
            this.viewState = 'edit';
        }
        if (args && args.query) {
            this.query = args.query;
            this.query.workspace = this;
            this.query.save({}, { success: this.init_query , error: function() {
                Saiku.ui.unblock();
                if ( $('body').find('.error_loading_query').length < 1) {
                    var message = (Saiku.i18n && Saiku.i18n.po_file.error_loading_query) ? Saiku.i18n.po_file.error_loading_query : null;
                    if (!message) {
                        message = "Error Loading Query";
                        $('<span class="i18n error_loading_query">' + message + '</span>').hide().appendTo('body');
                        Saiku.i18n.translate();
                        message = $('.error_loading_query').text();
                    }
                    alert(message);

                } else {
                    var m = $('.error_loading_query').text();
                    alert(m);
                }
            }});
        }

        // Flash cube navigation when rendered
        Saiku.session.bind('tab:add', this.prepare);

        // Selected schema and cube via url
        var paramsURI = Saiku.URLParams.paramsURI();
        if (Saiku.URLParams.equals({ schema: paramsURI.schema, cube: paramsURI.cube })) {
            this.data_connections(paramsURI);
        }
        else {
            this.data_connections(paramsURI);
        }
    },

    caption: function(increment) {
        if (this.query && this.query.model) {
            if (this.item && this.item.name) {
                return this.item.name.split('.')[0];
            } else if (this.query.model.mdx) {
                return this.query.model.name;
            }
        } else if (this.query && this.query.get('name')) {
            return this.query.get('name');
        }
        if (increment) {
            Saiku.tabs.queryCount++;
        }
        return "<span class='i18n'>Unsaved query</span> (" + (Saiku.tabs.queryCount) + ")";
    },

    selected_cube_template: function(selectedCube) {
        var connections = Saiku.session.sessionworkspace;
        connections.selected = selectedCube;
        return _.template(
            '<select class="cubes">' +
                '<option value="" class="i18n">Select a cube</option>' +
                '<% _.each(connections, function(connection) { %>' +
                    '<% _.each(connection.catalogs, function(catalog) { %>' +
                        '<% _.each(catalog.schemas, function(schema) { %>' +
                            '<% if (schema.cubes.length > 0) { %>' +
                                '<optgroup label="<%= (schema.name !== "" ? schema.name : catalog.name) %> <%= (connection.name) %>">' +
                                    '<% _.each(schema.cubes, function(cube) { %>' +
                                        '<% if ((typeof cube["visible"] === "undefined" || cube["visible"]) && selected !== cube.caption) { %>' +
                                            '<option value="<%= connection.name %>/<%= catalog.name %>/<%= ((schema.name === "" || schema.name === null) ? "null" : schema.name) %>/<%= encodeURIComponent(cube.name) %>"><%= ((cube.caption === "" || cube.caption === null) ? cube.name : cube.caption) %></option>' +
                                        '<% } else if ((typeof cube["visible"] === "undefined" || cube["visible"]) && selected === cube.caption) { %>' +
                                            '<option value="<%= connection.name %>/<%= catalog.name %>/<%= ((schema.name === "" || schema.name === null) ? "null" : schema.name) %>/<%= encodeURIComponent(cube.name) %>" selected><%= ((cube.caption === "" || cube.caption === null) ? cube.name : cube.caption) %></option>' +
                                        '<% } %>' +
                                    '<% }); %>' +
                                '</optgroup>' +
                            '<% } %>' +
                        '<% }); %>' +
                    '<% }); %>' +
                '<% }); %>' +
            '</select>'
        )(connections);
    },

    template: function() {
        var template = $("#template-workspace").html() || "",
            htmlCubeNavigation = false,
            selectedCube;

        if (this.isUrlCubeNavigation) {
            selectedCube = this.selected_cube.split('/')[3],
            htmlCubeNavigation = this.selected_cube_template(selectedCube);
        }

        return _.template(template)({
            cube_navigation: htmlCubeNavigation
                ? htmlCubeNavigation
                : Saiku.session.sessionworkspace.cube_navigation
        });
    },

    refresh: function(e) {
        if (e) { e.preventDefault(); }
        Saiku.session.sessionworkspace.refresh();
    },

    render: function() {
        // Load template
		var self = this;
        $(this.el).html(this.template());

        this.processing = $(this.el).find('.query_processing');

        if (this.isReadOnly || Settings.MODE && (Settings.MODE == "view" || Settings.MODE == "table" || Settings.MODE == "chart")) {
            $(this.el).find('.workspace_editor').remove();
            this.toggle_sidebar();
            $(this.el).find('.sidebar_separator').remove();
            $(this.el).find('.workspace_inner')
                .css({ 'margin-left': 0 });
            $(this.el).find('.workspace_fields').remove();
            $(this.el).find('.sidebar').hide();

            $(this.toolbar.el)
                .find(".run, .auto, .toggle_fields, .toggle_sidebar,.switch_to_mdx, .new")
                .parent().remove();

        } else {

            // Show drop zones

            $(this.el).find('.workspace_editor').append($(this.drop_zones.el));
            // Activate sidebar for removing elements
            $(this.el).find('.sidebar')
                .droppable({
                    accept: '.d_measure, .selection'
                });

            $(this.el).find('.workspace_results')
                .droppable({
                    accept: '.d_measure, .selection'
                });
        }

        if (Settings.MODE && (Settings.MODE == "table" || Settings.MODE == "chart")) {
            $(this.el).find('.workspace_toolbar').remove();
            $(this.el).find('.query_toolbar').remove();
        } else {
            // Show toolbar
            $(this.el).find('.workspace_toolbar').append($(this.toolbar.el));
            $(this.el).find('.query_toolbar').append($(this.querytoolbar.el));
        }

        this.switch_view_state(this.viewState, true);

        // Add results table
        $(this.el).find('.workspace_results')
            .append($(this.table.el));

        this.chart.render_view();
        // Adjust tab when selected
        this.tab.bind('tab:select', this.adjust);
        $(window).resize(this.adjust);


        // Fire off new workspace event
        Saiku.session.trigger('workspace:new', { workspace: this });

        return this;
    },

    clear: function() {
        // Prepare the workspace for a new query
        this.table.clearOut();
        $(this.el).find('.workspace_results table,.connectable')
            .html('');
        $(this.el).find('.workspace_results_info').empty();
        $(this.el).find('.parameter_input').empty();
        $(this.chart.el).find('div.canvas').empty();
        $(this.querytoolbar.el).find('ul.options a.on').removeClass('on');
        $(this.el).find('.fields_list[title="ROWS"] .limit').removeClass('on');
        $(this.el).find('.fields_list[title="COLUMNS"] .limit').removeClass('on');
        // Trigger clear event
        Saiku.session.trigger('workspace:clear', { workspace: this });

    },

    adjust: function() {
        // Adjust the height of the separator
        var $separator = $(this.el).find('.sidebar_separator');
        var heightReduction = 87;
        if (Settings.PLUGIN === true || Settings.BIPLUGIN === true) {
            heightReduction = 2;
            if (Settings.MODE == 'table') {
                heightReduction = -5;
            }
        }
        if ($('#header').length === 0 || $('#header').is('hidden')) {
            heightReduction = 2;
        }

        $separator.height($("body").height() - heightReduction);
        $(this.el).find('.sidebar').height($("body").height() - heightReduction);

        $(this.querytoolbar.el).find('div').height($("body").height() - heightReduction - 10);

        // Adjust the dimensions of the results window
        var editorHeight = $(this.el).find('.workspace_editor').is(':hidden') ? 0 : $(this.el).find('.workspace_editor').height();
        var processingHeight = $(this.el).find('.query_processing').is(':hidden') ? 0 : $(this.el).find('.query_processing').height() + 62;
        var upgradeHeight = $(this.el).find('.upgradeheader').is(':hidden') ? 0 : $(this.el).find('.upgrade').height();

        $(this.el).find('.workspace_results').css({
            height: $("body").height() - heightReduction -
                $(this.el).find('.workspace_toolbar').height() -
                $(this.el).find('.workspace_results_info').height() -
                editorHeight - processingHeight - upgradeHeight - 20
        });

        if (this.querytoolbar) { $(this.querytoolbar.el).find('a').tipsy({ delayIn: 700, fade: true}); }
        if (this.toolbar) { $(this.toolbar.el).find('a').tipsy({ delayIn: 900, fade: true}); }

        // Fire off the adjust event
        this.trigger('workspace:adjust', { workspace: this });
    },

    toggle_sidebar: function() {
        // Toggle sidebar
        $(this.el).find('.sidebar').toggleClass('hide');
        $(this.toolbar.el).find('.toggle_sidebar').toggleClass('on');
        var calculatedMargin =
                ($(this.el).find('.sidebar').is(':visible') ? $(this.el).find('.sidebar').width() : 0) +
                ($(this.el).find('.sidebar_separator').width()) +
                1;
        var new_margin = calculatedMargin;
        $(this.el).find('.workspace_inner').css({ 'margin-left': new_margin });
    },

    prepare: function() {
        // Draw user's attention to cube navigation
        $(this.el).find('.cubes')
            .parent()
            .css({ backgroundColor: '#AC1614' })
            .delay(300)
            .animate({ backgroundColor: '#fff' }, 'slow');
    },

    data_connections: function(paramsURI) {
        var connections = Saiku.session.sessionworkspace.connections,
            self = this;
        _.each(connections, function(connection) {
            _.each(connection.catalogs, function(catalog) {
                _.each(catalog.schemas, function(schema) {
                    if (schema.cubes.length > 0) {
                        _.each(schema.cubes, function(cube) {
                            if (typeof cube['visible'] === 'undefined' || cube['visible']) {
                                var schemaName = ((schema.name === '' || schema.name === null) ? 'null' : schema.name),
                                    cubeName = ((cube.caption === '' || cube.caption === null) ? cube.name : cube.caption);
                                if (paramsURI.schema === schemaName && paramsURI.cube === cubeName) {
                                    self.selected_cube = connection.name + '/' + catalog.name + '/' + schemaName + '/' + cubeName;
                                    self.isUrlCubeNavigation = true;
                                    _.delay(self.new_query, 1000);
                                }
                            }
                        });
                    }
                });
            });
        });
    },

    new_query: function() {
        // Delete the existing query
        if (this.query) {
            this.query.destroy();
            this.query.clear();
            if (this.query.name) {
                this.query.name = undefined;
                this.update_caption(true);
            }
            this.query.name = undefined;
        }
        this.clear();
        this.processing.hide();
        Saiku.session.trigger('workspace:clear', { workspace: this });

        // Initialize the new query
        this.selected_cube = $(this.el).find('.cubes').val()
            ? $(this.el).find('.cubes').val()
            : this.selected_cube;
        if (!this.selected_cube) {
            // Someone literally selected "Select a cube"
            $(this.el).find('.calculated_measures, .addMeasure').hide();
            $(this.el).find('.dimension_tree').html('');
            $(this.el).find('.measure_tree').html('');
            return false;
        }
        this.metadata = Saiku.session.sessionworkspace.cube[this.selected_cube];
        var parsed_cube = this.selected_cube.split('/');
        var cube = parsed_cube[3];
        for (var i = 4, len = parsed_cube.length; i < len; i++) {
            cube += "/" + parsed_cube[i];
        }
        this.query = new Query({
            cube: {
                connection: parsed_cube[0],
                catalog: parsed_cube[1],
                schema: (parsed_cube[2] == "null" ? "" : parsed_cube[2]) ,
                name: decodeURIComponent(cube)
            }
        }, {
                workspace: this
        });

        // Save the query to the server and init the UI
        this.query.save({},{ data: { json: JSON.stringify(this.query.model) }, async: false });
        this.init_query();
    },

    init_query: function(isNew) {
        var self = this;
        try
        {

            // TODO: This should be refactored, the workspace should have a renderer set and always use that
            // probably extend the Table.js with TableRenderer and make it an advanced one

            var properties = this.query.model.properties ? this.query.model.properties : {} ;

            var renderMode =  ('RENDER_MODE' in Settings) ? Settings.RENDER_MODE
                                    : ('saiku.ui.render.mode' in properties) ? properties['saiku.ui.render.mode']
                                    : null;
            var renderType =  ('RENDER_TYPE' in Settings) ? Settings.RENDER_TYPE
                                    : ('saiku.ui.render.type' in properties) ? properties['saiku.ui.render.type']
                                    : null;

	    if(Settings.MODE == "table"){
		renderMode= "table";
	    }
	    else if(Settings.MODE == "chart"){
		renderMode="chart";
	    }
            if (typeof renderMode != "undefined" && renderMode !== null) {
                this.querytoolbar.switch_render(renderMode);
            }

            if ('chart' == renderMode) {
                $(this.chart.el).find('.canvas_wrapper').hide();
                this.chart.renderer.switch_chart(renderType);
                $(this.querytoolbar.el).find('ul.chart [href="#' + renderType+ '"]').parent().siblings().find('.on').removeClass('on');
                $(this.querytoolbar.el).find('ul.chart [href="#' + renderType+ '"]').addClass('on');


            } else if ('table' == renderMode && renderType in this.querytoolbar) {
                this.querytoolbar.render_mode = "table";
                this.querytoolbar.spark_mode = renderType;
                $(this.querytoolbar.el).find('ul.table a.' + renderType).addClass('on');
            }
        } catch (e) {
                Saiku.error(this.cid, e);
        }

        if ((Settings.MODE == "table") && this.query) {
            this.query.run(true);
            return;
        }

        if (this.query.model.type == "MDX") {
                this.query.setProperty("saiku.olap.result.formatter", "flat");
            if (! $(this.el).find('.sidebar').hasClass('hide')) {
                this.toggle_sidebar();
            }
            $(this.el).find('.workspace_fields').addClass('hide');
            this.toolbar.switch_to_mdx();
        } else {
            $(this.el).find('.workspace_editor').removeClass('hide').show();
            $(this.el).find('.workspace_fields').removeClass('disabled').removeClass('hide');
            $(this.el).find('.workspace_editor .mdx_input').addClass('hide');
            $(this.el).find('.workspace_editor .editor_info').addClass('hide');
            $(this.toolbar.el).find('.auto, .toggle_fields, .query_scenario, .buckets, .non_empty, .swap_axis, .mdx, .switch_to_mdx, .zoom_mode, .drillacross').parent().show();
            $(this.el).find('.run').attr('href','#run_query');
        }
        this.adjust();
        this.switch_view_state(this.viewState, true);

        if (!$(this.el).find('.sidebar').hasClass('hide') && (Settings.MODE == "chart" || Settings.MODE == "table" || Settings.MODE == "view" || this.isReadOnly)) {
                this.toggle_sidebar();
        }
        if ((Settings.MODE == "view") && this.query || this.isReadOnly) {
            this.query.run(true);
            return;
        }


        // Find the selected cube
        if (this.selected_cube === undefined) {
            var schema = this.query.model.cube.schema;
            this.selected_cube = this.query.model.cube.connection + "/" +
                this.query.model.cube.catalog + "/" +
                ((schema === "" || schema === null) ? "null" : schema) +
                "/" + encodeURIComponent(this.query.model.cube.name);
            $(this.el).find('.cubes')
                .val(this.selected_cube);
        }

        if (this.selected_cube) {
            // Create new DimensionList and MeasureList
            var cubeModel = Saiku.session.sessionworkspace.cube[this.selected_cube];

            this.dimension_list = new DimensionList({
                workspace: this,
                cube: cubeModel
            });
            this.dimension_list.render();

            $(this.el).find('.metadata_attribute_wrapper').html('').append($(this.dimension_list.el));

            if (!cubeModel.has('data')) {
                cubeModel.fetch({ success: function() {
                    self.trigger('cube:loaded');
                }});
            }
            this.trigger('query:new', { workspace: this });

        } else {
            // Someone literally selected "Select a cube"
            $(this.el).find('.calculated_measures, .addMeasure').hide();
            $(this.el).find('.dimension_tree').html('');
            $(this.el).find('.measure_tree').html('');
        }

        // is this a new query?
        if (typeof isNew != "undefined") {
            this.query.run(true);
        }
        Saiku.i18n.translate();


    },

    synchronize_query: function() {
        var self = this;
        if (!self.isReadOnly && (!Settings.hasOwnProperty('MODE') || (Settings.MODE != "table" && Settings.MODE != "view"))) {


        }



    },

    sync_query: function(dimension_el) {

        var model = this.query.helper.model();
        if (model.type === "QUERYMODEL") {

            var self = this;
            var dimlist = dimension_el ? dimension_el : $(self.dimension_list.el);

            if (!self.isReadOnly && (!Settings.hasOwnProperty('MODE') || (Settings.MODE != "table" && Settings.MODE != "view"))) {
                dimlist.find('.selected').removeClass('selected');

                var cms = self.query.helper.getCalculatedMeasures();
                if (cms && cms.length > 0) {
                    var template = _.template($("#template-calculated-measures").html(),{ measures: cms });
                    dimlist.find('.calculated_measures').html(template);
                    dimlist.find('.calculated_measures').find('.measure').parent('li').draggable({
                        cancel: '.not-draggable',
                        connectToSortable: $(self.el).find('.fields_list_body.details ul.connectable'),
                        helper: 'clone',
                        placeholder: 'placeholder',
                        opacity: 0.60,
                        tolerance: 'touch',
                        containment:    $(self.el),
                        cursorAt: { top: 10, left: 35 }
                    });
                }

                self.drop_zones.synchronize_query();

            }
        }
        Saiku.i18n.translate();

    },

    /*jshint -W027*/
    /*jshint -W083*/
    populate_selections: function(dimlist) {
        var self = this;

        console.log('populate_selections');
        dimlist.workspace.sync_query();
        return false;

        if (this.other_dimension) {
        // Populate selections - trust me, this is prettier than it was :-/
        var axes = this.query ? this.query.get('axes') : false;
        if (axes) {
            for (var axis_iter = 0, axis_iter_len = axes.length; axis_iter < axis_iter_len; axis_iter++) {
                var axis = axes[axis_iter];
                var $axis = $(this.el).find('.' +
                    axis.name.toLowerCase() + ' ul');
                if ((axis.filterCondition !== null) ||
                        (axis.limitFunction && axis.limitFunction !== null && axis.limitFunction !== "") ||
                        (axis.sortOrder !== null))
                {
                    $axis.parent().siblings('.fields_list_header').addClass('on');
                }
                for (var dim_iter = 0, dim_iter_len = axis.dimensionSelections.length; dim_iter < dim_iter_len; dim_iter++) {
                    var dimension = axis.dimensionSelections[dim_iter];
                    var levels = [];
                    var members = {};

                    if (dimension.name != "Measures" && dimension.selections.length > 0) {
                        var ds = Saiku.session.sessionworkspace.cube[this.selected_cube].get('data').dimensions;
                        var h = dimension.selections[0].hierarchyUniqueName;
                        _.each(ds, function(d) {
                            if (dimension.name == d.name) {
                                _.each(d.hierarchies, function(hierarchy) {
                                    if (hierarchy.uniqueName == h) {
                                        var levels = [];
                                        _.each(hierarchy.levels, function(level) {
                                            levels.push(level.uniqueName);
                                        });
                                        dimension.selections = _.sortBy(dimension.selections, function(selection) {
                                            return _.indexOf(levels, selection.levelUniqueName);
                                        });
                                    }
                                });
                            }
                        });
                    } else if (dimension.name == "Measures" && dimension.selections.length > 0) {
                        var ms = Saiku.session.sessionworkspace.cube[this.selected_cube].get('data').measures;
                        var mlist = [];
                        _.each(ms, function(m) {
                            mlist.push(m.uniqueName);
                        });
                        dimension.selections = _.sortBy(dimension.selections, function(selection) {
                            return _.indexOf(mlist, selection.uniqueName);
                        });
                    }


                    for (var sel_iter = 0, sel_iter_len = dimension.selections.length; sel_iter < sel_iter_len; sel_iter++) {
                        var selection = dimension.selections[sel_iter];

                        // Drag over dimensions and measures
                        var type, name;
                        if (selection.dimensionUniqueName == "Measures") {
                            type = "measure";
                            name = selection.uniqueName;
                        } else {
                            type = "dimension";
                            name = selection.levelUniqueName;
                        }

                        if (levels.indexOf(name) === -1) {

                            var $dim = $('');

                            if (typeof dimension_el != "undefined" && (!$dim.html() || $dim.html() === null)) {
                                $dim = $(dimension_el)
                                .find('a[rel="' + name + '"]')
                                .parent();
                            }
/*
                            if (typeof self.measure_list != "undefined" && (!$dim.html() || $dim.html() == null)) {
                                $dim = $(self.measure_list.el)
                                .find('a[rel="' + name + '"]')
                                .parent();
                            }
*/
                            if (typeof self.dimension_list != "undefined" && (!$dim.html() || $dim.html() === null)) {
                                $dim = $(self.dimension_list.el)
                                .find('a[rel="' + name + '"]')
                                .parent();
                            }


                            var $clone = $dim.clone()
                                .addClass('d_' + type)
                                .appendTo($axis);

                            var sort;

                            if (type == "dimension") {
                                $("<span />").addClass('sprite selections')
                                    .prependTo($clone);
                                $icon = $("<span />").addClass('sort');
                                sort = false;
                                _.each(axes, function(i_axis) {
                                    if (i_axis.sortLiteral && i_axis.sortLiteral !== null && i_axis.sortLiteral.indexOf(selection.hierarchyUniqueName) != -1) {
                                        $icon.addClass(i_axis.sortOrder);
                                        sort = true;
                                    }
                                });
                                if (!sort) {
                                    $icon.addClass('none');
                                }

                                $icon.insertBefore($clone.find('span'));
                            }

                            if (type == "measure") {
                                $icon = $("<span />").addClass('sort');
                                sort = false;
                                _.each(axes, function(i_axis) {
                                    if (i_axis.sortLiteral && i_axis.sortLiteral !== null && i_axis.sortLiteral.indexOf(name) != -1) {
                                        $icon.addClass(i_axis.sortOrder);
                                        sort = true;
                                    }
                                });
                                if (!sort) {
                                    $icon.addClass('none');
                                }

                                $icon.insertBefore($clone.find('a'));
                            }



                            $dim.css({fontWeight: "bold"})
                                .draggable('disable')
                                .parents('.parent_dimension')
                                .find('.folder_collapsed')
                                .css({fontWeight: "bold"});
                            levels.push(name);
                        }

                        // FIXME - something needs to be done about selections here
                    }
                }
            }
        }

        // Make sure appropriate workspace buttons are enabled
        this.trigger('query:new', { workspace: this });

        // Update caption when saved
        this.query.bind('query:save', this.update_caption);
        } else {
            this.other_dimension = dimension_el;
        }

    },

    update_caption: function(increment) {
        var caption = this.caption(increment);
        this.tab.set_caption(caption);
    },



    remove_dimension: function(event, ui) {
        if (this.query.model.type == "QUERYMODEL") {
                this.drop_zones.remove_dimension(event, ui);
        }
    },

    update_parameters: function () {
        var self = this;
        $(this.el).find('.parameter_input').html("");
		if (!self.hasOwnProperty('query') || !Settings.ALLOW_PARAMETERS || Settings.MODE === "view" || self.viewState === 'view')
			return;

        var paramDiv = "<span class='i18n'>Parameters</span>: ";
        var parameters = this.query.helper.model().parameters;
        var hasParams = false;
        for (var key in parameters) {
            var val = "";
            if (parameters[key] && parameters[key] !== null) {
                val = parameters[key];
            }
            paramDiv += "<b>" + key + "</b> <input type='text' placeholder='" + key + "' value='" + val + "' />";
            hasParams = true;
        }
        paramDiv +="";

        if (hasParams) {
            $(this.el).find('.parameter_input').html(paramDiv);
        } else {
            $(this.el).find('.parameter_input').html("");
        }

        $(this.el).find('.parameter_input input').off('change');
        $(this.el).find('.parameter_input input').on('change', function(event) {
            var paramName = $(event.target).attr('placeholder');
            var paramVal = $(event.target).val();
            self.query.helper.model().parameters[paramName] = paramVal;
        });

    },

    render_result: function(args) {
        var self = this;
        $(this.el).find(".workspace_results_info").empty();

        if (args.data !== null && args.data.error !== null) {
            return this.error(args);
        }
        // Check to see if there is data
        if (args.data === null || (args.data.cellset && args.data.cellset.length === 0)) {
            return this.no_results(args);
        }

        var chour = new Date().getHours();
        if (chour < 10) chour = "0" + chour;

        var cminutes = new Date().getMinutes();
        if (cminutes < 10) cminutes = "0" + cminutes;

        var cdate = chour + ":" + cminutes;
        var runtime = args.data.runtime !== null ? (args.data.runtime / 1000).toFixed(2) : "";
        /*
        var info = '<b>Time:</b> ' + cdate
                + " &emsp;<b>Rows:</b> " + args.data.height
                + " &emsp;<b>Columns:</b> " + args.data.width
                + " &emsp;<b>Duration:</b> " + runtime + "s";
        */
        var info = '<b><span class="i18n">Info:</span></b> &nbsp;' + cdate +
                   "&emsp;/ &nbsp;" + args.data.width +
                   " x " + args.data.height +
                   "&nbsp; / &nbsp;" + runtime + "s";


        this.update_parameters();

        $(this.el).find(".workspace_results_info").html(info);
        this.adjust();
        return;
    },

    switch_view_state: function(mode, dontAnimate) {
        var target = mode || 'edit';

        if (target == 'edit') {
                //$(this.el).find('.workspace_editor').show();
                this.toolbar.toggle_fields_action('show', dontAnimate);
                if (this.query && this.query.get('type') == "MDX") {
                    this.toolbar.editor.gotoLine(0);
                }
                if ($(this.el).find('.sidebar').hasClass('hide')) {
                    this.toggle_sidebar();
                }
                //$(this.el).find('.sidebar_separator').show();
                //$(this.el).find('.workspace_inner').removeAttr('style');
                $(this.toolbar.el).find(".auto, .toggle_fields, .toggle_sidebar,.switch_to_mdx, .new").parent().css({ "display" : "block" });
        } else if (target == 'view') {
                //$(this.el).find('.workspace_editor').hide();
                this.toolbar.toggle_fields_action('hide', dontAnimate);
                if (!$(this.el).find('.sidebar').hasClass('hide')) {
                    this.toggle_sidebar();
                }
                //$(this.el).find('.sidebar_separator').hide();
                //$(this.el).find('.workspace_inner').css({ 'margin-left': 0 });

                $(this.toolbar.el).find(".auto, .toggle_fields, .toggle_sidebar,.switch_to_mdx").parent().hide();
        }
        this.viewState = target;
		this.update_parameters();
        $(window).trigger('resize');

    },

    block: function(message) {
        /* Most probably not needed anymore. Seems ok now with fix #192
        if (isIE) {
            var $msg = $("<span>" + message + "</span>");
            $msg.find('.processing_image').removeClass('processing_image');
            Saiku.ui.block($msg.html());
        }
        */
            $(this.el).block({
                message: '<span class="saiku_logo" style="float:left">&nbsp;&nbsp;</span> ' + message
            });
            Saiku.i18n.translate();
    },

    unblock: function() {
        if (isIE) {
            Saiku.ui.unblock();
        } else {
            $(this.el).unblock();
            Saiku.ui.unblock();
        }
    },

    cancel: function(event) {
        var self = this;
        if (event) {
            event.preventDefault();
        }
        this.query.action.del("/cancel", {
            success: function() {
                self.cancelled();
            }
        });
    },

    cancelled: function(args) {
        this.processing.html('<span class="processing_image">&nbsp;&nbsp;</span> <span class="i18n">Canceling Query...</span>').show();
    },

    no_results: function(args) {
        this.processing.html('<span class="i18n">No Results</span>').show();
    },

    error: function(args) {
        this.processing.html(safe_tags_replace(args.data.error)).show();
    }
});
