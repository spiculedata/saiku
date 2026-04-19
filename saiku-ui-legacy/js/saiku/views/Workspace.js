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
        'click .admin' : 'admin'
    },

    initialize: function(args) {
        // Maintain `this` in jQuery event handlers
        _.bindAll(this, "caption", "adjust", "adjust_trigger", "toggle_sidebar", "prepare", "new_query", "set_class_charteditor",
                "init_query", "update_caption", "populate_selections","refresh", "sync_query", "cancel", "cancelled", "no_results", "error", "switch_view_state");

        // Attach an event bus to the workspace
        _.extend(this, Backbone.Events);
        this.loaded = false;
        this.bind('query:result',this.render_result);

        // Generate toolbar and append to workspace
        this.toolbar = new WorkspaceToolbar({ workspace: this });
        this.toolbar.render();

        this.upgrade = new Upgrade({ workspace: this});
        this.upgrade.render();

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

        if (args !== undefined && args.processURI !== undefined && args.processURI === false) {
            paramsURI = {};
        }

        if (Saiku.URLParams.contains({ schema: paramsURI.schema, cube: paramsURI.cube })) {
            this.data_connections(paramsURI);
        }
        else {
            this.data_connections(paramsURI);
        }
    },
    afterRender: function () {
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

        if (this.isReadOnly || Settings.MODE && (Settings.MODE == "view" || Settings.MODE == "table" || Settings.MODE == "map" || Settings.MODE == "chart")) {
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

        if (Settings.MODE && (Settings.MODE == "table" || Settings.MODE == "chart" || Settings.MODE == "map")) {
            $(this.el).find('.workspace_toolbar').remove();
            $(this.el).find('.query_toolbar').remove();
        } else {
            // Show toolbar
            $(this.el).find('.workspace_toolbar').append($(this.toolbar.el));
            $(this.el).find('.query_toolbar').append($(this.querytoolbar.el));
            $(self.el).find('.upgrade').append($(self.upgrade.el));


        }

        this.switch_view_state(this.viewState, true);


        // Add results table
        $(this.el).find('.workspace_results')
            .append($(this.table.el));

        this.chart.render_view();
        // Adjust tab when selected
        this.tab.bind('tab:select', this.adjust);
        this.tab.bind('tab:select', this.adjust_trigger);
        $(window).resize(this.adjust);



        // Fire off new workspace event
        Saiku.session.trigger('workspace:new', { workspace: this });

        if (Settings.PLUGIN && Settings.BIPLUGIN5 == false && Saiku.session.isAdmin) {
            var $link = $('<a />')
                .attr({
                    href: '#adminconsole',
                    title: 'Admin Console'
                })
                .click(Saiku.AdminConsole.show_admin)
                .addClass('button admin_console');
            $(this.el).find('.refresh_cubes_nav').css('margin-right', '40px');
            $(this.el).find('.admin_console_nav').append($link);
        }

        if (!Saiku.session.isAdmin && Settings.SHOW_REFRESH_NONADMIN === false) {
            $(this.el).find('.refresh_cubes_nav').hide();
        }

        var paramsURI = Saiku.URLParams.paramsURI();

        if (!Saiku.introDone && Saiku.URLParams.contains({ show_help: paramsURI.show_help })) {
            Saiku.intro.start({ fileName: 'Workspace' });
            Saiku.introDone = true;
        }

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
        $(this.el).find('.workspace_fields').css({height: $("body").height()- heightReduction-
        $(this.el).find('.workspace_toolbar').height() -
        upgradeHeight - 20});

        // Fire off the adjust event
        this.trigger('workspace:adjust', { workspace: this });
    },

    adjust_trigger: function() {
        _.defer(function() {
            $(window).trigger('resize');
        });
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
        this.adjust_trigger();
    },

    prepare: function() {
        // Draw user's attention to cube navigation
        /*$(this.el).find('.cubes')
            .parent()
            .css({ backgroundColor: '#AC1614' })
            .delay(300)
            .animate({ backgroundColor: '#fff' }, 'slow');*/
    },
   setDefaultFilters: function(filters, query){

        _.each(filters, function(f){

            var n = f.filtername;

            var hierarchy = n.substring(0, n.lastIndexOf("[")-1);
            var level = n.substring(n.lastIndexOf("["));

            query.helper.setDefaultFilter(hierarchy, level, f.filtervalue)
        });


    },

    data_connections: function(paramsURI) {
        var connections = Saiku.session.sessionworkspace.connections;
        var self = this;

        _.each(connections, function(connection) {
            _.each(connection.catalogs, function(catalog) {
                _.each(catalog.schemas, function(schema) {
                    if (schema.cubes.length > 0) {
                        _.each(schema.cubes, function(cube) {
                            if (typeof cube['visible'] === 'undefined' || cube['visible']) {
                                var schemaName = ((schema.name === '' || schema.name === null) ? 'null' : schema.name);
                                var cubeName = ((cube.caption === '' || cube.caption === null) ? cube.name : cube.caption);

                                if (paramsURI.schema === schemaName && paramsURI.cube === cubeName) {
                                    self.selected_cube = connection.name + '/' + catalog.name + '/' + schemaName + '/' + cubeName;
                                    self.isUrlCubeNavigation = true;
                                    self.paramsURI = paramsURI;
                                    _.delay(self.new_query, 1000);
                                }
                            }
                        });
                    }
                });
            });
        });
    },

    create_new_query: function(obj){
        if (obj.query) {
            obj.query.destroy();
            obj.query.clear();
            if (obj.query.name) {
                obj.query.name = undefined;
                obj.update_caption(true);
            }
            obj.query.name = undefined;
        }
        obj.clear();
        obj.processing.hide();
        Saiku.session.trigger('workspace:clear', { workspace: obj });

        // Initialize the new query
        obj.selected_cube = $(obj.el).find('.cubes').val()
            ? $(obj.el).find('.cubes').val()
            : obj.selected_cube;
        if (!obj.selected_cube) {
            // Someone literally selected "Select a cube"
            $(obj.el).find('.calculated_measures, .addMeasure').hide();
            $(obj.el).find('.dimension_tree').html('');
            $(obj.el).find('.measure_tree').html('');
            return false;
        }
        obj.metadata = Saiku.session.sessionworkspace.cube[obj.selected_cube];
        var parsed_cube = obj.selected_cube.split('/');
        var cube = parsed_cube[3];
        for (var i = 4, len = parsed_cube.length; i < len; i++) {
            cube += "/" + parsed_cube[i];
        }
        Saiku.events.trigger("workspace:new_query", this, {view: this, cube: cube});

        this.query = new Query({
            cube: {
                connection: parsed_cube[0],
                catalog: parsed_cube[1],
                schema: (parsed_cube[2] == "null" ? "" : parsed_cube[2]) ,
                name: decodeURIComponent(cube)
            }
        }, {
            workspace: obj
        });

        if (!this.processedParamsURI) {
            var paramsURI = Saiku.URLParams.paramsURI();

            var addCustomFilter = function(axis, filterExpression) {
                var a = this.query.helper.getAxis(axis);
                var checkChar = {
                    first: filterExpression.charAt(0),
                    last: filterExpression.charAt(filterExpression.length - 1)
                };
                var indexHierarchy = '-1';
                var expressions = [];
                var arrHierarchy = [];
                var hierarchy = '';
                var level;
                var filter;
                var hierarchyLevel;

                if (checkChar.first === '(' && checkChar.last === ')') {
                    filterExpression = Saiku.trimFirstLastChar(filterExpression);

                    if (filterExpression.indexOf('],') !== -1) {
                        hierarchyLevel = filterExpression.split('],')[0].split('].[');
                        level = _.last(hierarchyLevel);
                        arrHierarchy = _.first(hierarchyLevel, hierarchyLevel.length > 2 ? 2 : 1);
                        filter = filterExpression.split('],')[1].trim();

                        for (var i = 0, iLen = arrHierarchy.length; i < iLen; i++) {
                            hierarchy += '[' + Saiku.removeBrackets(arrHierarchy[i]) + '].';
                        }

                        hierarchy = Saiku.trimFirstLastChar(hierarchy, 'last');

                        this.query.helper.includeLevel(axis, hierarchy, level, indexHierarchy);
                    }
                    else {
                        filter = filterExpression;
                    }
                }
                else {
                    filter = filterExpression;
                }

                expressions.push(filter);

                this.query.helper.removeFilter(a, 'Generic');
                a.filters.push({
                    'flavour': 'Generic',
                    'operator': null,
                    'function': 'Filter',
                    'expressions': expressions
                });
            }.bind(this);

            if (Saiku.URLParams.contains({ default_mdx_filter_rows: paramsURI.default_mdx_filter_rows })) {
                addCustomFilter('ROWS', paramsURI.default_mdx_filter_rows);
            }

            if (Saiku.URLParams.contains({ default_mdx_filter_columns: paramsURI.default_mdx_filter_columns })) {
                addCustomFilter('COLUMNS', paramsURI.default_mdx_filter_columns);
            }

            if (Saiku.URLParams.contains({ default_mdx_filter: paramsURI.default_mdx_filter })) {
                addCustomFilter('FILTER', paramsURI.default_mdx_filter);
            }

            this.processedParamsURI = true;
        }

        obj.query = this.query;

        // Process default parameters
        if (this.extractDefaultFilters && this.paramsURI) {
            var deffilters = this.extractDefaultFilters(this.paramsURI);
            this.setDefaultFilters(deffilters, obj.query);        
        }

        // Save the query to the server and init the UI
        obj.query.save({},{ data: { json: JSON.stringify(this.query.model) }, async: false });
        obj.init_query();

    },

    extractDefaultFilters: function(p){
        var defaultfilters=[];
        var filtername;
        var filtervalue;
        for(var i in p){
            if(i.indexOf("default_filter_")>-1){
                var j = i.replace("default_filter_", "");
                filtername = j;
                filtervalue = p[i];
                defaultfilters.push({"filtername":j, "filtervalue":filtervalue});
            }

        }
        return defaultfilters;

    },

    new_query: function() {
        // Delete the existing query
        if (this.query) {
            if (Settings.QUERY_OVERWRITE_WARNING) {
                (new WarningModal({
                    title: 'New Query',
                    message: 'You are about to clear your existing query',
                    okay: this.create_new_query,
                    okayobj: this,
                    cancel: this.cancel_new_query,
                    cancelobj: this
                })).render().open();
            }
            else {
                this.create_new_query(this);
            }
        }
        else {
            this.create_new_query(this);
        }
    },

    cancel_new_query: function(args) {
        var selectedCube = args.selected_cube;

        args.$el.find('#cubesselect option[value="' + selectedCube + '"]').prop('selected', true);
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
        else if(Settings.MODE == "map"){
        renderMode="map";
        }
            if (typeof renderMode != "undefined" && renderMode !== null) {
                this.querytoolbar.switch_render(renderMode);
            }

            if ('chart' == renderMode) {
                if (Settings.MODE && Settings.MODE === 'chart' && (renderType === 'map_heat' || renderType === 'map_geo' || renderType === 'map_marker')) {
                    this.query.setProperty('saiku.ui.render.mode', 'chart');
                    renderType = 'stackedBar';
                }
                $(this.chart.el).find('.canvas_wrapper').hide();
                this.chart.renderer.switch_chart(renderType);
                $(this.querytoolbar.el).find('ul.chart [href="#' + renderType+ '"]').parent().siblings().find('.on').removeClass('on');
                $(this.querytoolbar.el).find('ul.chart [href="#' + renderType+ '"]').addClass('on');
                this.set_class_charteditor();
            } else if ('table' == renderMode && renderType in this.querytoolbar) {
                this.querytoolbar.render_mode = "table";
                this.querytoolbar.spark_mode = renderType;
                $(this.querytoolbar.el).find('ul.table a.' + renderType).addClass('on');
            }
            else if (renderMode === 'map') {
                this.querytoolbar.$el.find('ul.chart > li').find('a').removeClass('on');
                this.querytoolbar.$el.find('ul.chart [href="#' + renderMode + '"]').addClass('on');
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

        if (!$(this.el).find('.sidebar').hasClass('hide') && (Settings.MODE == "chart" || Settings.MODE == "table" || Settings.MODE == "map" || Settings.MODE == "view" || this.isReadOnly)) {
                this.toggle_sidebar();
        }
        if ((Settings.MODE == "view") && this.query || this.isReadOnly) {
            this.query.run(true);
            if (this.selected_cube === undefined) {
                var schema = this.query.model.cube.schema;
                this.selected_cube = this.query.model.cube.connection + "/" +
                    this.query.model.cube.catalog + "/" +
                    ((schema === "" || schema === null) ? "null" : schema) +
                    "/" + encodeURIComponent(this.query.model.cube.name);
                $(this.el).find('.cubes')
                    .val(this.selected_cube);
            }
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

    set_class_charteditor: function() {
        var chartOptions = this.query.getProperty('saiku.ui.chart.options');
        if (chartOptions) {
            $(this.querytoolbar.el).find('ul.chart [href="#charteditor"]').addClass('on');
        }
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
            if(self.dimension_list!=null){
                var dimlist = dimension_el ? dimension_el : $(self.dimension_list.el);
            }
            else{
                var dimlist = dimension_el ? dimension_el : null;
            }

            if (!self.isReadOnly && (!Settings.hasOwnProperty('MODE') || (Settings.MODE != "table" && Settings.MODE != "view"))) {
                dimlist.find('.selected').removeClass('selected');

                var calcMeasures = self.query.helper.getCalculatedMeasures();
                //var calcMembers = self.query.helper.getCalculatedMembers();

                if (calcMeasures && calcMeasures.length > 0) {
                    var template = _.template($("#template-calculated-measures").html(),{ measures: calcMeasures });
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
                else {
                    dimlist.find('.calculated_measures').empty();
                }

                /*if (calcMembers && calcMembers.length > 0) {
                    var self = this;
                    var $dimensionTree = dimlist.find('.dimension_tree').find('.parent_dimension').find('.d_hierarchy');
                    var len = calcMembers.length;
                    var templateLevelMember;
                    var i;

                    $dimensionTree.find('.dimension-level-calcmember').remove();

                    for (i = 0; i < len; i++) {
                        $dimensionTree.each(function(key, value) {
                            if ($(value).attr('hierarchy') === calcMembers[i].hierarchyName) {

                                templateLevelMember = _.template($('#template-calculated-member').html(), { member: calcMembers[i] });

                                if(!($(value).closest('.parent_dimension').find('span.root').hasClass('collapsed'))) {
                                    templateLevelMember = $(templateLevelMember).show();
                                }
                                else {
                                    templateLevelMember = $(templateLevelMember).hide();
                                }

                                $(value).append(templateLevelMember);

                                $(value).find('.level-calcmember').parent('li').draggable({
                                    cancel: '.not-draggable, .hierarchy',
                                    connectToSortable: $(self.el).find('.fields_list_body.columns > ul.connectable, .fields_list_body.rows > ul.connectable, .fields_list_body.filter > ul.connectable'),
                                    containment: $(self.el),
                                    helper: function(event, ui) {
                                        var target = $(event.target).hasClass('d_level') ? $(event.target) : $(event.target).parent();
                                        var hierarchy = target.find('a').attr('hierarchy');
                                        var level = target.find('a').attr('level');
                                        var h = target.parent().clone().removeClass('d_hierarchy').addClass('hierarchy');
                                        h.find('li a[hierarchy="' + hierarchy + '"]').parent().hide();
                                        h.find('li a[level="' + level + '"]').parent().show();
                                        var selection = $('<li class="selection selection-calcmember"></li>');
                                        selection.append(h);
                                        return selection;
                                    },
                                    placeholder: 'placeholder',
                                    opacity: 0.60,
                                    tolerance: 'touch',
                                    cursorAt: {
                                        top: 10,
                                        left: 85
                                    }
                                });
                            }
                        });
                    }
                }
                else {
                    dimlist.find('.dimension_tree').find('.parent_dimension').find('.d_hierarchy').find('.dimension-level-calcmember').remove();
                }*/

                self.drop_zones.synchronize_query();

            }
        }
        Saiku.i18n.translate();
    },

    populate_selections: function(dimlist) {
        var self = this;

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
            var comparison;
            if (parameters[key] && parameters[key] !== null) {
                val = parameters[key];
                comparison = parameters[key].split(",");
            }

            if (val != undefined && val != "") {
                val = val + ",";
            }
            var selections = this.query.helper.getSelectionsForParameter(key);
            _.each(selections, function (s) {
                var found = $.inArray(s.name, comparison) > -1;
                if (!found) {
                    val = val + s.name + ",";
                }
            });

            val = val.substr(0, val.lastIndexOf(","));

            paramDiv += "<b>" + key + "</b> <input type='text' placeholder='" + key + "' value='" + val + "' />";
            hasParams = true;

            var values = val.split(",")
            var level = self.query.helper.getLevelForParameter(key);

            _.each(values, function (v) {
                if(v!=undefined && v!="") {
                    self.query.helper.addtoSelection(v, level)
                }
            });
        }
        paramDiv += "";

        if (hasParams) {
            $(this.el).find('.parameter_input').html(paramDiv);
        } else {
            $(this.el).find('.parameter_input').html("");
        }

        $(this.el).find('.parameter_input input').off('change');
        $(this.el).find('.parameter_input input').on('change', function (event) {
            var paramName = $(event.target).attr('placeholder');
            var paramVal = $(event.target).val();
            self.query.helper.model().parameters[paramName] = paramVal;
        });


    }
    ,

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

        if (Settings.REPORT_TITLES) {
            var h = args.workspace.query.getProperty("saiku.ui.headings");
            if(h!=undefined) {
                var headings = JSON.parse(h);
                var header = '';
                if(headings.title!=null && headings.title != "") {
                    header = '<h3><span class="i18n">Title:</span></h3> &nbsp;' + headings.title + '<br/>';
                }
                if(headings.variable != null && headings.variable != "") {
                    header += '<h3><span class="i18n">Variable:</span></h3> &nbsp;' + headings.variable + '<br/>';
                }
                if(headings.explanation!=null && headings.explanation != "") {
                    header += '<h3><span class="i18n">Explanation:</span></h3> &nbsp;' + headings.explanation;
                }
                $(this.el).find(".workspace_results_titles").html(header);
            }
        }

        this.adjust();
        Saiku.i18n.translate();
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
        var self = this;

        if(Settings.LOGO_32x32){
            $(self.el).block({
                message: '<img class="saiku_logo_override" style="float:left" src="'+Settings.LOGO_32x32+'"/> ' + message
            });
            Saiku.i18n.translate();
        }
        else{
            $(self.el).block({
                message: '<span class="saiku_logo" style="float:left">&nbsp;&nbsp;</span> ' + message
            });
            Saiku.i18n.translate();

        }
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

    admin: function(event){
        Saiku.AdminConsole.show_admin();
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
