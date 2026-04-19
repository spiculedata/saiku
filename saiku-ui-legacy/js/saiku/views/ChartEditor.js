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
 * @dependencies
 * - plugins/CCC_Chart/plugin.js
 * - render/SaikuChartRenderer.js
 * - views/QueryToolbar.js
 * - views/Workspace.js
 */

/**
 * Class for edit charts
 *
 * @class ChartEditor
 */
var ChartEditor = Modal.extend({
    /**
     * Type name
     *
     * @property type
     * @type {String}
     * @private
     */
    type: 'chart-editor',

    /**
     * Property with main template of modal
     *
     * @property message
     * @type {String}
     * @private
     */
    message: '<form class="form-group-inline">' +
                '<div class="form-group">' +
                    '<label for="chart-title" class="i18n">Chart Title:</label>' +
                    '<input type="text" class="form-control" id="chart-title">' +
                '</div>' +
                '<div class="form-group">' +
                    '<label for="xtitle" class="i18n">X Axis label:</label>' +
                    '<input type="text" class="form-control" id="xtitle">' +
                '</div>' +
                '<div class="form-group">' +
                    '<label for="ytitle" class="i18n">Y Axis label:</label>' +
                    '<input type="text" class="form-control" id="ytitle">' +
                '</div>' +
                '<div class="form-group">' +
                    '<label for="colors" class="i18n">Colors:</label>' +
                    '<div class="container-colors"></div>' +
                '</div>' +
                '<div class="clearfix"></div>' +
                '<div class="form-group">' +
                    '<label for="legend" class="i18n">Show Legend:</label>' +
                    '<label for="legend" class="lbl-legend-yes i18n">' +
                        '<input type="radio" name="legend" value="true" checked> Yes' +
                    '</label>' +
                    '<label for="legend" class="lbl-legend-no i18n">' +
                        '<input type="radio" name="legend" value="false"> No' +
                    '</label>' +
                '</div>' +
                '<div class="div-trend hide">' +
                    '<div class="form-group">' +
                        '<label for="trend-line" class="i18n">Trend Line:</label>' +
                        '<select class="form-control" id="trend-line">' +
                            '<option value="none" class="i18n">None</option>' +
                            '<option value="linear" class="i18n">Linear</option>' +
                            '<option value="weighted-moving-average" class="i18n">Weighted Moving Average</option>' +
                            '<option value="moving-average" class="i18n">Moving Average</option>' +
                        '</select>' +
                    '</div>' +
                    '<div class="form-group">' +
                        '<div class="div-trend-points">' +
                            '<label for="trend-points" class="i18n">Trend Periods:</label>' +
                            '<input type="text" class="form-control" id="trend-points" value="3">' +
                        '</div>' +
                    '</div>' +
                '</div>' +
                '<div class="div-multiplesunburst hide">' +
                    '<div class="form-group">' +
                        '<label for="multiplesunburst-multichartmax" class="i18n">Max number of charts:</label>' +
                        '<input type="number" class="form-control" id="multiplesunburst-multichartmax" min="0" value="30" style="width: 160px;">' +
                    '</div>' +
                '</div>' +
                '<div class="div-pie hide">' +
                    '<div class="form-group">' +
                        '<label for="pie-values-label-style" class="i18n">Values Label Style:</label>' +
                        '<select class="form-control" id="pie-values-label-style">' +
                            '<option value="linked" class="i18n">Linked</option>' +
                            '<option value="inside" class="i18n">Inside</option>' +
                        '</select>' +
                    '</div>' +
                    '<div class="form-group">' +
                        '<label for="pie-values-mask" class="i18n">Values Mask:</label>' +
                        '<input type="text" class="form-control" id="pie-values-mask" value="{value.percent}">' +
                    '</div>' +
                '</div>' +
             '</form>',

    /**
     * Events of buttons
     *
     * @property buttons
     * @type {Array}
     * @private
     */
    buttons: [
        { text: 'Add Color Palette', method: 'add_color_palette' },
        { text: 'Save', method: 'save' },
        { text: 'Cancel', method: 'close' }
    ],

    /**
     * The events hash (or method) can be used to specify a set of DOM events
     * that will be bound to methods on your View through delegateEvents
     *
     * @property events
     * @type {Object}
     * @private
     */
    events: {
        'click .dialog_footer a' : 'call',
        'change #trend-line'     : 'hide_periods'
    },

    /**
     * The constructor of view, it will be called when the view is first created
     *
     * @constructor
     * @private
     * @param  {Object} args Attributes, events and others things
     */
    initialize: function(args) {
        // Initialize properties
        _.extend(this, args);
        this.options.closeOnEscape = false;
        this.options.title = 'Chart Settings';
        this.renderer = {};

        var self = this;

        if (args && args.workspace && args.data) {
            this.workspace = args.workspace;
            this.renderer = args.data;
        }

        // Maintain `this` in callbacks
        _.bindAll(this, 'template_editor', 'add_color_palette', 'get_chart_properties', 'hide_periods');

        this.bind('open', function() {
            this.get_chart_properties();
            this.$el.closest('.ui-dialog').on('keydown', function(event) {
                if (event.keyCode === $.ui.keyCode.ESCAPE) {
                    self.close(event);
                }
            });
        });
    },

    /**
     * Main template of modal
     *
     * @public
     * @method template_editor
     * @return {String} HTML template
     */
    template_editor: function() {
        return this.message;
    },

    /**
     * Plugin color picker Spectrum and options
     *
     * @method spectrum_options
     * @private
     * @param  {String} color Color selected
     * @return {Object}       Plugin Spectrum options
     */
    spectrum_options: function(color) {
        return {
            color: color,
            showInput: true,
            showInitial: true,
            showPalette: true,
            showSelectionPalette: true,
            maxPaletteSize: 10,
            preferredFormat: 'hex',
            palette: [
                ['rgb(0, 0, 0)', 'rgb(67, 67, 67)', 'rgb(102, 102, 102)', 'rgb(153, 153, 153)','rgb(183, 183, 183)',
                'rgb(204, 204, 204)', 'rgb(217, 217, 217)', 'rgb(239, 239, 239)', 'rgb(243, 243, 243)', 'rgb(255, 255, 255)'],
                ['rgb(152, 0, 0)', 'rgb(255, 0, 0)', 'rgb(255, 153, 0)', 'rgb(255, 255, 0)', 'rgb(0, 255, 0)',
                'rgb(0, 255, 255)', 'rgb(74, 134, 232)', 'rgb(0, 0, 255)', 'rgb(153, 0, 255)', 'rgb(255, 0, 255)'],
                ['rgb(230, 184, 175)', 'rgb(244, 204, 204)', 'rgb(252, 229, 205)', 'rgb(255, 242, 204)', 'rgb(217, 234, 211)',
                'rgb(208, 224, 227)', 'rgb(201, 218, 248)', 'rgb(207, 226, 243)', 'rgb(217, 210, 233)', 'rgb(234, 209, 220)',
                /*'rgb(221, 126, 107)',*/ 'rgb(234, 153, 153)', 'rgb(249, 203, 156)', 'rgb(255, 229, 153)', 'rgb(182, 215, 168)',
                'rgb(162, 196, 201)', 'rgb(164, 194, 244)', 'rgb(159, 197, 232)', 'rgb(180, 167, 214)', 'rgb(213, 166, 189)',
                'rgb(204, 65, 37)', /*'rgb(224, 102, 102)',*/ 'rgb(246, 178, 107)', 'rgb(255, 217, 102)', 'rgb(147, 196, 125)',
                'rgb(118, 165, 175)', 'rgb(109, 158, 235)', 'rgb(111, 168, 220)', 'rgb(142, 124, 195)', 'rgb(194, 123, 160)',
                'rgb(166, 28, 0)', 'rgb(204, 0, 0)', /*'rgb(230, 145, 56)',*/ 'rgb(241, 194, 50)', 'rgb(106, 168, 79)',
                'rgb(69, 129, 142)', 'rgb(60, 120, 216)', 'rgb(61, 133, 198)', 'rgb(103, 78, 167)', 'rgb(166, 77, 121)',
                'rgb(133, 32, 12)', 'rgb(153, 0, 0)', 'rgb(180, 95, 6)', /*'rgb(191, 144, 0)',*/ 'rgb(56, 118, 29)',
                'rgb(19, 79, 92)', 'rgb(17, 85, 204)', 'rgb(11, 83, 148)', 'rgb(53, 28, 117)', 'rgb(116, 27, 71)',
                'rgb(91, 15, 0)', 'rgb(102, 0, 0)', 'rgb(120, 63, 4)', 'rgb(127, 96, 0)', /*'rgb(39, 78, 19)',*/
                'rgb(12, 52, 61)', 'rgb(28, 69, 135)', 'rgb(7, 55, 99)', 'rgb(32, 18, 77)', 'rgb(76, 17, 48)']
            ]
        }
    },

    /**
     * Add color palette using spectrum plugin
     *
     * @method add_color_palette
     * @public
     * @param  {Object} event The Event interface represents any event of the DOM
     * @param  {String} color Color selected
     */
    add_color_palette: function(event, color) {
        if (event) {
            event.preventDefault();
        }

        var uid = _.uniqueId('color');
        var randomColor = '#' + Math.floor(Math.random()*16777215).toString(16);
        var getColor = color || randomColor;
        var $input = '<input type="color" id="' + uid + '">';

        this.$el.find('.container-colors').append($input);
        this.$el.find('#' + uid).spectrum(this.spectrum_options(getColor));

        this.$el.find('#' + uid).on('beforeShow.spectrum', this.delete_color_palette);
        this.$el.find('.sp-replacer').hover(this.delete_color_palette);
        this.$el.find('.sp-replacer').click(this.delete_color_palette);
    },

    /**
     * Delete color palette using spectrum plugin
     *
     * @method delete_color_palette
     * @public
     * @param  {Object} event The Event interface represents any event of the DOM
     */
    delete_color_palette: function function_name(event) {
        var $currentTarget = $(event.currentTarget);

        if (event.type === 'beforeShow') {
            Saiku.inputColorUID = event.target.id;
        }

        $currentTarget.find('.delete-color').remove();

        if (event.type === 'mouseover' ||
            event.type === 'mouseenter' ||
            event.type === 'click') {
            $currentTarget.append('<a href="#delete_color_palette" class="delete-color">x</a>');
        }
        else {
            $currentTarget.find('.delete-color').remove();
        }

        if (event.type === 'click' && $(event.target).hasClass('delete-color')) {
            $('.sp-container').addClass('sp-hidden');
            $('#' + Saiku.inputColorUID).spectrum('destroy');
            $('#' + Saiku.inputColorUID).remove();
            $currentTarget.remove();
        }
    },

    /**
     * Properties charts
     *
     * @method get_chart_properties
     * @public
     */
    get_chart_properties: function() {
        var args = Array.prototype.slice.call(arguments);
        var chartOptions = {};

        if (_.isEmpty(args)) {
            chartOptions = this.workspace.query.getProperty('saiku.ui.chart.options');
        }
        else {
            this.$el = args[0].element;
            this.renderer.type = args[0].type;
            if (args[0].chartDefinition && !(_.isEmpty(args[0].chartDefinition))) {
                chartOptions.chartDefinition = _.isString(args[0].chartDefinition) ? JSON.parse(args[0].chartDefinition) : args[0].chartDefinition;
            }
        }

        if (chartOptions !== undefined && chartOptions.chartDefinition !== undefined && chartOptions.chartDefinition.title !== null) {
            this.$el.find('#chart-title').val(chartOptions.chartDefinition.title);
        }

        if (chartOptions !== undefined && chartOptions.chartDefinition !== undefined && chartOptions.chartDefinition.baseAxisTitle !== null) {
            this.$el.find('#xtitle').val(chartOptions.chartDefinition.baseAxisTitle);
        }

        if (chartOptions !== undefined && chartOptions.chartDefinition !== undefined && chartOptions.chartDefinition.orthoAxisTitle !== null) {
            this.$el.find('#ytitle').val(chartOptions.chartDefinition.orthoAxisTitle);
        }

        if (chartOptions === undefined || chartOptions.chartDefinition === undefined || chartOptions.chartDefinition.colors === undefined) {
            var colors = ['#bb0000', '#007070', '#74af00'];
            var lenColor = colors.length;

            for (var i = 0; i < lenColor; i++) {
                this.add_color_palette(null, colors[i]);
            }
        }
        else {
            var lenColor = chartOptions.chartDefinition.colors.length;

            for (var i = 0; i < lenColor; i++) {
                this.add_color_palette(null, chartOptions.chartDefinition.colors[i]);
            }
        }

        if (chartOptions !== undefined && chartOptions.chartDefinition !== undefined && chartOptions.chartDefinition.legend !== null) {
            this.$el.find('input[type=radio][value="' + chartOptions.chartDefinition.legend + '"]').first().attr('checked', 'checked');
        }

        if (this.renderer.type === 'line') {
            this.$el.find('.div-trend').show();
            if (chartOptions !== undefined && chartOptions.chartDefinition !== undefined && chartOptions.chartDefinition.trend !== null) {
                this.$el.find('#trend-line').val(chartOptions.chartDefinition.trend.type);
                if (chartOptions.chartDefinition.trend.type !== 'linear' || chartOptions.chartDefinition.trend.type !== 'none') {
                    this.$el.find('.div-trend-points').show();
                    this.$el.find('#trend-points').val(chartOptions.chartDefinition.trend.periods);
                }
                else {
                    this.$el.find('.div-trend-points').hide();
                }
            }
            else {
                this.$el.find('.div-trend-points').hide();
            }
        }
        else if (this.renderer.type === 'multiplesunburst') {
            this.$el.find('.div-multiplesunburst').show();

            if (chartOptions !== undefined && chartOptions.chartDefinition !== undefined && chartOptions.chartDefinition.multiChartMax !== null) {
                this.$el.find('#multiplesunburst-multichartmax').val(chartOptions.chartDefinition.multiChartMax || 30);
            }
        }
        else if (this.renderer.type === 'pie') {
            this.$el.find('.div-pie').show();
            if (chartOptions !== undefined && chartOptions.chartDefinition !== undefined && chartOptions.chartDefinition.valuesLabelStyle !== null) {
                this.$el.find('#pie-values-label-style').val(chartOptions.chartDefinition.valuesLabelStyle || 'linked');
            }

            if (chartOptions !== undefined && chartOptions.chartDefinition !== undefined && chartOptions.chartDefinition.valuesMask !== null) {
                this.$el.find('#pie-values-mask').val(chartOptions.chartDefinition.valuesMask || '{value.percent}');
            }
        }
        else {
            this.$el.find('.div-trend').hide();
            this.$el.find('.div-multiplesunburst').hide();
            this.$el.find('.div-pie').hide();
        }
    },

    /**
     * Show/hide periods
     *
     * @method hide_periods
     * @public
     * @param  {Object} event The Event interface represents any event of the DOM
     */
    hide_periods: function(event) {
        event.preventDefault();

        var $currentTarget = $(event.currentTarget);
        var value = $currentTarget.val();

        if (value !== 'linear' && value !== 'none') {
            this.$el.find('.div-trend-points').show();
        }
        else {
            this.$el.find('.div-trend-points').hide();
        }
    },

    /**
     * Save chart options
     *
     * @method save
     * @private
     * @param  {Object} event The Event interface represents any event of the DOM
     */
    save: function(event) {
        event.preventDefault();

        var self = this;
        var override = {};
        var colors = [];
        var type;

        override.chartDefinition = {};

        this.renderer.cccOptions.title = this.$el.find('#chart-title').val();

        if (this.$el.find('#chart-title').val() !== null) {
            override.chartDefinition.title = this.$el.find('#chart-title').val();
        }

        if (this.$el.find('#xtitle').val() !== null) {
            override.chartDefinition.baseAxisTitle = this.$el.find('#xtitle').val();
        }

        if (this.$el.find('#ytitle').val() !== null) {
            override.chartDefinition.orthoAxisTitle = this.$el.find('#ytitle').val();
        }

        $.each(this.$el.find('.container-colors > input[type="color"]'), function(key, value) {
            colors.push(self.$el.find('#' + $(value).attr('id')).spectrum('get').toHexString());
        });

        override.chartDefinition.colors = colors;
        override.chartDefinition.legend = this.$el.find('input:radio[name=legend]:checked').val() === 'true';

        if (this.$el.find('#trend-line').val() !== 'none') {
            override.chartDefinition.trend = {
                type: this.$el.find('#trend-line').val(),
                periods: this.$el.find('#trend-points').val()
            };
        }
        else {
            override.chartDefinition.trend = null;
        }

        if (this.renderer.type === 'multiplesunburst') {
            override.chartDefinition.multiChartMax = this.$el.find('#multiplesunburst-multichartmax').val() || 30;
        }

        if (this.renderer.type === 'pie') {
            override.chartDefinition.valuesLabelStyle = this.$el.find('#pie-values-label-style').val();
            override.chartDefinition.valuesMask = this.$el.find('#pie-values-mask').val() || '{value.percent}';
        }

        type = this.workspace.querytoolbar.$el.find('ul.chart li').not('.chart_editor').find('a.on').attr('href');

        if (type) {
            type = type.replace('#', '');
        }
        else {
            // default
            type = 'stackedBar';
        }

        this.workspace.querytoolbar.$el.find('ul.chart [href="#charteditor"]').addClass('on');
        this.renderer.switch_chart(this.renderer.type, override);
        this.workspace.query.setProperty('saiku.ui.chart.options', override);
        this.workspace.query.setProperty('saiku.ui.render.type', type);
        this.$el.dialog('close');
    },

    /**
     * Close dialog
     *
     * @method close
     * @private
     * @param  {Object} event The Event interface represents any event of the DOM
     */
    close: function(event) {
        event.preventDefault();
        $('.sp-container').addClass('sp-hidden');
        this.$el.dialog('close');
    }
});
