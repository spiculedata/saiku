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
 * Renders a chart for each workspace
 */
var Chart = Backbone.View.extend({
    events: {
        'click #charteditor' : 'show_editor'
    },

    initialize: function(args) {
        this.workspace = args.workspace;

        // Create a unique ID for use as the CSS selector
        this.id = _.uniqueId("chart_");
        $(this.el).attr({ id: this.id });

        this.renderer = new SaikuChartRenderer(null, { htmlObject: $(this.el), zoom: true, adjustSizeTo: ".workspace_results", workspace: this.workspace });

        // Bind table rendering to query result event
        _.bindAll(this, "receive_data", "show", "render_view", "exportChart");
        var self = this;
        this.workspace.bind('query:run',  function() {
            if (! $(self.workspace.querytoolbar.el).find('.render_chart').hasClass('on')) {
                return false;
            }
            self.renderer.data = {};
            self.renderer.data.resultset = [];
            self.renderer.data.metadata = [];
            $(self.el).find('.canvas_wrapper').hide();
            return false;
        });

        this.workspace.bind('query:result', this.receive_data);

         var pseudoForm = "<div id='nav" + this.id + "' style='display:none' class='nav'><form id='svgChartPseudoForm' target='_blank' method='POST'>" +
                "<input type='hidden' name='type' class='type'/>" +
                "<input type='hidden' name='svg' class='svg'/>" +
                "<input type='hidden' name='name' class='name'/>" +
                "</form></div>";
        if (isIE) {
            pseudoForm = "<div></div>";
        }
        this.nav =$(pseudoForm);

        $(this.el).append(this.nav);
    },

    exportChart: function(type) {
        var svgContent = new XMLSerializer().serializeToString($('svg')[0]);
        var rep = '<svg xmlns="http://www.w3.org/2000/svg" ';
        if (svgContent.substr(0,rep.length) != rep) {
            svgContent = svgContent.replace('<svg ', rep);
        }
        svgContent = '<!DOCTYPE svg [<!ENTITY nbsp "&#160;">]>' + svgContent;

        var form = $('#svgChartPseudoForm');
        form.find('.type').val(type);
        form.find('.svg').val(svgContent);
        if(this.workspace.query.name!=undefined) {
            var f = this.workspace.query.name.substring(this.workspace.query.name.lastIndexOf('/') + 1).slice(0, -6);
            form.find('.name').val(f);
        }
        form.attr('action', Settings.REST_URL + this.workspace.query.url() + escape("/../../export/saiku/chart"));
        form.submit();
        return false;
    },

    show_editor:function(event) {
        var $currentTarget = $(event.currentTarget);
        if ($currentTarget.attr('disabled') !== 'disabled') {
            (new ChartEditor({ data: this.renderer, workspace: this.workspace })).render().open();
        }
    },

    render_view: function() {
        // Append chart to workspace, called by workspace
        $(this.workspace.el).find('.workspace_results')
            .prepend($(this.el).hide());
    },

    show: function(event, ui) {
        var self = this;
        this.workspace.adjust();
        this.renderer.cccOptions.width = $(this.workspace.el).find('.workspace_results').width() - 40;
        this.renderer.cccOptions.height = $(this.workspace.el).find('.workspace_results').height() - 40;

        $(this.el).show();

        var hasRun = this.workspace.query.result.hasRun();

        if ($(this.workspace.el).find('#charteditor').length === 0) {
            $(this.workspace.el).find('.query_toolbar_vertical').find('.options.chart.hide li:eq(0)').after('<li id="charteditor" class="seperator_vertical chart_editor"><a href="#charteditor" ' +
                'style="height:30px;" class="button">Properties</a></li>');

            $(this.workspace.el).find('.query_toolbar_vertical').find('#charteditor').on('click', function (event) {
                self.show_editor(event);
            });
        }
        if (hasRun) {
            _.defer(function() {
                var p = self.workspace.query.getProperty('saiku.ui.chart.options');

                self.renderer.process_data_tree({ data: self.workspace.query.result.lastresult() }, true, true);
                self.renderer.switch_chart(self.renderer.type, {workspace: this.workspace, chartDefinition: p ? p.chartDefinition : null});
            });
        }

        this.workspace.trigger('chart:rendered', this);
    },

    export_button: function(event) {
        var self = this;
        var $target = $(event.target).hasClass('button') ? $(event.target) : $(event.target).parent();

        var self = this;
        var $body = $(document);
        //$body.off('.contextMenu .contextMenuAutoHide');
        //$('.context-menu-list').remove();
        $.contextMenu('destroy', '.export_button');
        $.contextMenu({
                selector: '.export_button',
                trigger: 'left',
                ignoreRightClick: true,
                callback: function(key, options) {
                    self.workspace.chart.exportChart(key);
                },
                items: {
                    "png": {name: "PNG"},
                    // "jpg": {name: "JPEG"},
                    "pdf": {name: "PDF"}
                }
        });
        $target.contextMenu();
    },

    receive_data: function(args) {
        if (! $(this.workspace.querytoolbar.el).find('.render_chart').hasClass('on')) {
            return;
        }
        this.workspace.adjust();
        this.renderer.process_data_tree(args, true, true);
        var p = this.workspace.query.getProperty('saiku.ui.chart.options');
        this.renderer.switch_chart(this.renderer.type, {workspace: this.workspace, chartDefinition: p ? p.chartDefinition : null});
        //_.delay(this.renderer.process_data_tree, 0, args, true, true);
    }
});

Saiku.loadCSS('js/saiku/plugins/CCC_Chart/chart_editor.css');
