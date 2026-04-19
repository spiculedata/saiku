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
 * Renders a stats for each workspace
 */
var Statistics = Backbone.View.extend({

    data: {
        resultset: [],
        metadata: []
    },

    initialize: function(args) {
        this.workspace = args.workspace;

        // Create a unique ID for use as the CSS selector
        this.id = _.uniqueId("stats_");
        $(this.el).attr({ id: this.id });

        // Bind table rendering to query result event
        _.bindAll(this, "render", "receive_data", "process_data", "show",
            "setOptions");
        this.workspace.bind('query:result', this.receive_data);

        // Add stats button
        this.add_button();
        this.workspace.querytoolbar.stats = this.show;

        // Listen to adjust event and rerender stats
        //this.workspace.bind('workspace:adjust', this.render);

        // Append stats to workspace
        $(this.workspace.el).find('.workspace_results')
            .prepend($(this.el).hide())
    },

    add_button: function() {
        var $stats_button =
            $('<a href="#stats" class="stats button disabled_toolbar i18n" id="stats_icon" title="Basic Statistics"></a>')
            .css({  'background-image': "url('js/saiku/plugins/Statistics/sigma.png')",
                    'background-repeat':'no-repeat',
                    'background-position':'50% 50%',
                    'height' : '32px',
                    'margin-top' : '5px'
                });

        var $stats_li = $('<li class="seperator_vertical"></li>').append($stats_button);
        $(this.workspace.querytoolbar.el).find("ul.table").append($stats_li);
    },

    show: function(event, ui) {
        $(this.workspace.table.el).toggle();
        $(this.el).toggle();
        $(event.target).toggleClass('on');

        if ($(event.target).hasClass('on')) {
            this.process_data({ data: this.workspace.query.result.lastresult() });
        } else {
            this.workspace.table.render({ data: this.workspace.query.result.lastresult() });
        }
    },

    setOptions: function(event) {
        var type = $(event.target).attr('href').replace('#', '');
        try {
            this[type]();
        } catch (e) { }

        return false;
    },

    render: function() {
        if (! $(this.workspace.querytoolbar.el).find('.stats').hasClass('on') ||
                ($(this.workspace.el).is(':visible') && !$(this.el).is(':visible')))
        {
            return;
        }

        var createRow = function(cells, header){
            var row = "<tr>";
            var ohcell = header?"<th class='row_header'>":"<th class='row'>";
            var odcell = header?"<th class='col'>":"<td class='data'>";
            var ccell = header?"</th>":"</td>";
            _.each(cells, function(it, idx){row += ((idx==0)?ohcell:odcell)
					+ "<div " + ((idx==0)?'class="i18n"':'') + ">"
					+ it
					+ "</div>" + ccell})
            row += "</tr>"
            return row
        }

        var group = function(grid, el, cback){
            //var elements = _.filter(_.map(grid, function(it){return it[el]}), function(it){return it});

            var elements = _.map(grid, function(it){ return it[el]});
            elements = _.filter(elements, function(it) {
                return (typeof it !== "undefined" && it != null && (it !== "" || it === 0));
            });
            var retVal = cback(elements);
            if (elements.length > 0 && (typeof retVal !== "undefined" && retVal != null && (retVal !== "" || retVal === 0))) {
                return retVal.toFixed(3);
            }
            return "";

        }

        var sum = function(elems){return _.reduce(elems, function(memo, num){ return memo + num }, 0)};
        var average = function(elems){return (sum(elems))/elems.length};
        var stdx = function(elems){
            var m = average(elems)
            return Math.sqrt(sum(_.map(elems, function(it){return Math.pow(m - it,2)}))/elems.length);
        }
        var min = function(elems){return _.min(elems)};
        var max = function(elems){return _.max(elems)};

        $(this.el).empty();
        var grid = this.data.metadata;
        var rs = this.data.resultset;
        var aux = _.filter(grid, function(it){return !it.isHeader && it.colType == 'Numeric'});
        var columns = _.map(aux, function(it){return it.colName});
        var idxs =_.map(columns, function(el){return _.indexOf(_.map(grid, function(it){return it.colName}), el)});

        var $table = $("<table style='display: table; '>");
        var $tbody = $("<tbody>").appendTo($table);
        $tbody.append(createRow(['Statistics'].concat(columns), true));
        $tbody.append(createRow(['Min'].concat(_.map(idxs, function(it){return group(rs, it, min)})), false));
        $tbody.append(createRow(['Max'].concat(_.map(idxs, function(it){return group(rs, it, max)})), false));
        $tbody.append(createRow(['Sum'].concat(_.map(idxs, function(it){return group(rs, it, sum)})), false));
        $tbody.append(createRow(['Average'].concat(_.map(idxs, function(it){return group(rs, it, average)})), false));
        $tbody.append(createRow(['Std. Deviation'].concat(_.map(idxs, function(it){return group(rs, it, stdx)})), false));

        $(this.el).append($table);

		Saiku.i18n.translate();
    },

    receive_data: function(args) {
        return _.delay(this.process_data, 0, args);
    },

    process_data: function(args) {
        this.data = {};
        this.data.resultset = [];
        this.data.metadata = [];
        this.data.height = 0;
        this.data.width = 0;

        if (typeof args == "undefined" || typeof args.data == "undefined" || !$(this.el).is(':visible')) {
            return;
        }
        if (args.data != null && args.data.error != null) {
            $(this.el).empty();
        }


        // Check to see if there is data
        if (args.data == null || (args.data.cellset && args.data.cellset.length === 0)) {
            $(this.el).empty();
        }

        if (args.data && args.data.cellset && args.data.cellset.length > 0) {

            var lowest_level = 0;
            var isHead = true
            var columnNames = new Array()
            for (var row = 0, rowLen = args.data.cellset.length; row < rowLen; row++) {
                if (isHead && (args.data.cellset[row][0].type == "ROW_HEADER_HEADER" ||
                    args.data.cellset[row][0].value == "null")) {
                    this.data.metadata = [];
                    for (var field = 0, fieldLen = args.data.cellset[row].length; field < fieldLen; field++) {
                        if (args.data.cellset[row][field].type == "ROW_HEADER_HEADER") {
                            this.data.metadata.shift();
                            lowest_level = field;
                        }
                        if(columnNames[field]){
                            columnNames[field].push(args.data.cellset[row][field].value)
                        }else{
                            columnNames[field] = [args.data.cellset[row][field].value]
                        }
                        if(args.data.cellset[row][0].type == "ROW_HEADER_HEADER"){
                            this.data.metadata.push({
                                colIndex: field,
                                colType: typeof(args.data.cellset[row + 1][field].value) !== "number" &&
                                    isNaN(args.data.cellset[row + 1][field].value
                                    .replace(/[^a-zA-Z 0-9.]+/g,'')) ? "String" : "Numeric",
                                colName: columnNames[field].join(' / '),
                                isHeader: (args.data.cellset[row][field].type == "ROW_HEADER_HEADER")
                            });
                        }
                    }
                } else if (args.data.cellset[row][lowest_level].value !== "null" && args.data.cellset[row][lowest_level].value !== "") {
                    isHead = false
                    var record = [];
                    this.data.width = args.data.cellset[row].length;
                    for (var col = lowest_level, colLen = args.data.cellset[row].length; col < colLen; col++) {
                        var value = args.data.cellset[row][col].value;
                        // check if the resultset contains the raw value, if not try to parse the given value
                        if (args.data.cellset[row][col].properties.raw && args.data.cellset[row][col].properties.raw !== "null")
                        {
                            value = parseFloat(args.data.cellset[row][col].properties.raw);
                        } else if (typeof(args.data.cellset[row][col].value) !== "number" &&
                            parseFloat(args.data.cellset[row][col].value.replace(/[^a-zA-Z 0-9.]+/g,'')))
                        {
                            value = parseFloat(args.data.cellset[row][col].value.replace(/[^a-zA-Z 0-9.]+/g,''));
                        }
                        if (col == lowest_level) {
                            value += " [" + row + "]";
                        }
                        record.push(value);
                    }
                    this.data.resultset.push(record);
                }
            }
            this.data.height = this.data.resultset.length;
            this.workspace.processing.hide();
            this.render();
        } else {
            $(this.el).empty();
            this.no_results();
        }
    },

    no_results: function(args) {
        $(this.el).text("No results");
    },

    error: function(args) {
        $(this.el).text(safe_tags_replace(args.data.error));
    }
});

/**
 * Start Plugin
 */
 Saiku.events.bind('session:new', function(session) {

        function new_workspace(args) {
            // Add stats element
            if (typeof args.workspace.stats == "undefined") {
                args.workspace.stats = new Statistics({ workspace: args.workspace });
            }
        }

        function clear_workspace(args) {
            if (typeof args.workspace.stats != "undefined" && $(args.workspace.stats.el).is(':visible')) {
                $(args.workspace.stats.el).parents().find('.workspace_results table').show();
                $(args.workspace.stats.el).hide();

                args.workspace.stats.data = {};
                args.workspace.stats.data.resultset = [];
                args.workspace.stats.data.metadata = [];
                args.workspace.stats.data.height = 0;
                args.workspace.stats.data.width = 0;

            }
        }


        // Attach stats to existing tabs
        for(var i = 0, len = Saiku.tabs._tabs.length; i < len; i++) {
            var tab = Saiku.tabs._tabs[i];
            if(tab.caption != "Home") {
                new_workspace({
                    workspace: tab.content
                });
            }
        };

        // Attach stats to future tabs
        Saiku.session.bind("workspace:new", new_workspace);
        Saiku.session.bind("workspace:clear", clear_workspace);
    });

