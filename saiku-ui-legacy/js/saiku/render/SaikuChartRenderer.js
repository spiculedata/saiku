var SaikuChartRenderer = function(data, options) {
    this.workspace = options.workspace;
    this.rawdata = data;
    this.cccOptions = {};

    this.data = null;
    this.hasProcessed = false;
    this.hasRendered = false;

    if (!options && !options.hasOwnProperty('htmlObject')) {
        throw("You need to supply a html object in the options for the SaikuChartRenderer!");
    }
    this.el = $(options.htmlObject);
    this.id = _.uniqueId("chart_");
    $(this.el).html('<div class="canvas_wrapper" style="display:none;"><div id="canvas_' + this.id + '"></div></div>');
    this.zoom = options.zoom;

    if (options.zoom) {

        var self = this;
        var btns = "<span style='float:left;' class='zoombuttons'><a href='#' class='button rerender i18n' title='Re-render chart'></a><a href='#' class='button zoomout i18n' style='display:none;' title='Zoom back out'></a></span>";
        $(btns).prependTo($(this.el).find('.canvas_wrapper'));
        $(this.el).find('.zoomout').on('click', function (event) {
            event.preventDefault();
            self.zoomout();
        });
        $(this.el).find('.zoomin').on('click', function (event) {
            event.preventDefault();
            self.zoomin();
        });
        $(this.el).find('.rerender').on('click', function (event) {
            event.preventDefault();
            $(self.el).find('.zoomout').hide();
            self.switch_chart(self.type);
        });
    }

    if (options.chartDefinition) {
        this.chartDefinition = options.chartDefinition;
    }
    this.cccOptions.canvas = 'canvas_' + this.id;
    this.data = null;


    this.adjustSizeTo = null;
    if (options.adjustSizeTo) {
        this.adjustSizeTo = options.adjustSizeTo;
    } else {
        this.adjustSizeTo = options.htmlObject;
    }

    if (this.rawdata) {
        if (this.type == "sunburst") {
            this.process_data_tree({data: this.rawdata});
        } else {
            this.process_data_tree({data: this.rawdata}, true, true);
        }
    }

    if (options.mode) {
        this.switch_chart(options.mode);
    } else {
        // default
        this.switch_chart("stackedBar");
    }

    this.adjust();

};

SaikuChartRenderer.prototype.adjust = function () {
    var self = this;
    var calculateLayout = function () {
        if (self.hasRendered && $(self.el).is(':visible')) {
            self.switch_chart(self.type);
        }
    };

    var lazyLayout = _.debounce(calculateLayout, 300);
    $(window).resize(function () {
        $(self.el).find('.canvas_wrapper').fadeOut(150);
        lazyLayout();
    });
};

SaikuChartRenderer.prototype.zoomin = function () {
    $(this.el).find('.canvas_wrapper').hide();
    var chart = this.chart.root;
    var data = chart.data;
    data
        .datums(null, {selected: false})
        .each(function (datum) {
            datum.setVisible(false);
        });
    data.clearSelected();
    chart.render(true, true, false);
    this.render_chart_element();
};

SaikuChartRenderer.prototype.zoomout = function () {
    var chart = this.chart.root;
    var data = chart.data;
    var kData = chart.keptVisibleDatumSet;

    if (kData === null || kData.length === 0) {
        $(this.el).find('.zoomout').hide();
    }
    else if (kData.length == 1) {
        $(this.el).find('.zoomout').hide();
        chart.keptVisibleDatumSet = [];
        pvc.data.Data.setVisible(data.datums(null, {visible: false}), true);

    } else if (kData.length > 1) {
        chart.keptVisibleDatumSet.splice(kData.length - 1, 1);
        var nonVisible = data.datums(null, {visible: false}).array();
        var back = chart.keptVisibleDatumSet[kData.length - 1];
        _.intersection(back, nonVisible).forEach(function (datum) {
            datum.setVisible(true);
        });
    }
    chart.render(true, true, false);
};

SaikuChartRenderer.prototype.render = function () {
    _.delay(this.render_chart_element, 0, this);
};

SaikuChartRenderer.prototype.switch_chart = function (key, override) {
    if((override != null || override != undefined) && (override.chartDefinition!=null || override.chartDefinition != undefined)) {
        this.chartDefinition = override.chartDefinition;
        if(override.chartDefinition.type!=undefined) {
            this.type = override.chartDefinition.type;
        }
        if(override.workspace !=null || override.workspace != undefined){
                this.workspace = override.workspace;
        }

        if(override.hasProcessed != undefined){
            this.hasProcessed = override.hasProcessed;
        }
    }

    var keyOptions =
    {
        "stackedBar": {
            type: "BarChart",
            stacked: true
        },
        "bar": {
            type: "BarChart",
        },
        "multiplebar": {
            type: "BarChart",
            multiChartIndexes: [1],
            dataMeasuresInColumns: true,
            orientation: "vertical",
            smallTitlePosition: "top",
            multiChartMax: 30,
            multiChartColumnsMax: Math.floor(this.cccOptions.width / 200),
            smallWidth: 200,
            smallHeight: 150
        },
        "line": {
            type: "LineChart"
        },
        "pie": {
            type: "PieChart",
            multiChartIndexes: [0] // ideally this would be chosen by the user (count, which)
        },
        "heatgrid": {
            type: "HeatGridChart"
        },
        "stackedBar100": {
            type: "NormalizedBarChart"
        },
        "area": {
            type: "StackedAreaChart"
        },
        "dot": {
            type: "DotChart"
        },
        "waterfall": {
            type: "WaterfallChart"
        },
        "treemap": {
            type: "TreemapChart"
        },
        "sunburst": {
            type: "SunburstChart"
        },
        "multiplesunburst": {
            type: "SunburstChart",
            multiChartIndexes: [1],
            dataMeasuresInColumns: true,
            orientation: "vertical",
            smallTitlePosition: "top",
            multiChartMax: 30,
            multiChartColumnsMax: Math.floor(this.cccOptions.width / 200),
            smallWidth: 200,
            smallHeight: 150,
            seriesInRows: false
        },
        "radar": {
            type: "RadarChart"
        },
        "timewheel": {
            type: "TimeWheel"
        }
    };

    if (key === null || key === '') {

    } else if (key == "sunburst") {
        $(this.el).find('.zoombuttons a').hide();
        this.type = key;
        var o = keyOptions[key];
        this.sunburst(o);
        if (this.hasProcessed) {
            this.render();
        }
    } else if (key == "radar") {
        this.type = key;
        this.drawRadarChart(o);
    } else if (keyOptions.hasOwnProperty(key)) {
        $(this.el).find('.zoombuttons a').hide();
        this.type = key;
        var o = keyOptions[key];
        this.cccOptions = this.getQuickOptions(o);
        if(this.chartDefinition!=undefined) {
            this.chartDefinition.type = o.type;
        }
        this.define_chart();
        if (this.hasProcessed) {
            this.render();
        }
    } else {
        if (key !== 'charteditor') {
            alert("Do not support chart type: '" + key + "'");
        }
    }

};

SaikuChartRenderer.prototype.sunburst = function (o) {
    this.type = "sunburst";

    var data = this.process_data_tree({data: this.rawdata});
    var options = this.getQuickOptions(o);

    function title(d) {
        return d.parentNode ? (title(d.parentNode) + "." + d.nodeName) : d.nodeName;
    }

    var re = "",
        nodes = pv.dom(data).nodes(); // .root("flare").nodes();

    var tipOptions = {
        delayIn: 200,
        delayOut: 80,
        offset: 2,
        html: true,
        gravity: "nw",
        fade: false,
        followMouse: true,
        corners: true,
        arrow: false,
        opacity: 1
    };

    var color = pv.colors(options.colors).by(function (d) {
        return d.parentNode && d.parentNode.nodeName;
    });

    var vis = new pv.Panel()
        .width(options.width)
        .height(options.height)
        .canvas(options.canvas);

    var partition = vis.add(pv.Layout.Partition.Fill)
        .nodes(nodes)
        .size(function (d) {
            return d.nodeValue;
        })
        .order("descending")
        .orient("radial");

    partition.node.add(pv.Wedge)
        .fillStyle(pv.colors(options.colors).by(function (d) {
            return d.parentNode && d.parentNode.nodeName;
        }))
        .visible(function (d) {
            return d.depth > 0;
        })
        .strokeStyle("#000")
        .lineWidth(0.5)
        .text(function (d) {
            var v = "";
            if (typeof d.nodeValue != "undefined") {
                v = " : " + d.nodeValue;
            }
            return (d.nodeName + v);
        })
        .cursor('pointer')
        .events("all")
        .event('mousemove', pv.Behavior.tipsy(tipOptions));

    partition.label.add(pv.Label)
        .visible(function (d) {
            return d.angle * d.outerRadius >= 6;
        });


    this.chart = vis;
};


// Default static style-sheet
SaikuChartRenderer.prototype.cccOptionsDefault = {
    Base: {
        animate: false,
        selectable: true,
        valuesVisible: false,
        legend: true,
        legendPosition: "top",
        legendAlign: "right",
        compatVersion: 2,
        legendSizeMax: "30%",
        axisSizeMax: "40%",
        plotFrameVisible: false,
        orthoAxisMinorTicks: false,
        colors: ["#1f77b4", "#aec7e8", "#ff7f0e", "#ffbb78", "#2ca02c", "#98df8a", "#d62728", "#ff9896", "#9467bd", "#c5b0d5", "#8c564b", "#c49c94", "#e377c2", "#f7b6d2", "#7f7f7f", "#c7c7c7", "#bcbd22", "#dbdb8d", "#17becf", "#9edae5"]
    },

    HeatGridChart: {
        orientation: "horizontal",
        useShapes: true,
        shape: "circle",
        nullShape: "cross",
        colorNormByCategory: false,
        sizeRole: "value",
        legendPosition: "right",
        legend: true,
        hoverable: true,
        axisComposite: true,
        colors: ["red", "yellow", "lightgreen", "darkgreen"],
        yAxisSize: "20%"

    },

    WaterfallChart: {
        orientation: "horizontal"
    },

    PieChart: {
        multiChartColumnsMax: 3,
        multiChartMax: 30,
        smallTitleFont: "bold 14px sans-serif",
        valuesVisible: true,
        valuesMask: "{category} / {value.percent}",
        explodedSliceRadius: "10%",
        extensionPoints: {
            slice_innerRadiusEx: '40%',
            slice_offsetRadius: function (scene) {
                return scene.isSelected() ? '10%' : 0;
            }
        },
        clickable: true
        //valuesLabelStyle: 'inside'
    },
    TimeWheel: {
        smallTitleFont: "bold 14px sans-serif",
        valuesVisible: true,
        valuesMask: "{category} / {value.percent}",
        explodedSliceRadius: "10%",
        colors: ["#1f77b4", "#aec7e8", "#ff7f0e", "#ffbb78", "#2ca02c", "#98df8a", "#d62728", "#ff9896", "#9467bd", "#c5b0d5", "#8c564b", "#c49c94", "#e377c2", "#f7b6d2", "#7f7f7f", "#c7c7c7", "#bcbd22", "#dbdb8d", "#17becf", "#9edae5"],
        extensionPoints: {
            slice_offsetRadius: function (scene) {
                return scene.isSelected() ? '2%' : 0;
            }
        },
        clickable: false,
        plots: []
    },

    LineChart: {
        extensionPoints: {
            area_interpolate: "monotone", // cardinal
            line_interpolate: "monotone"
        }
    },

    StackedAreaChart: {
        extensionPoints: {
            area_interpolate: "monotone",
            line_interpolate: "monotone"
        }
    },
    TreemapChart: {
        legendPosition: "right",
        multiChartIndexes: 0,
        extensionPoints: {
            leaf_lineWidth: 2
        },
        layoutMode: "slice-and-dice",
        valuesVisible: true
    },
    SunburstChart: {
        valuesVisible: false,
        hoverable: false,
        selectable: true,
        clickable: false,
        multiChartIndexes: [0],
        multiChartMax: 30
    }
};

SaikuChartRenderer.prototype.getQuickOptions = function (baseOptions) {
    var chartType = (baseOptions && baseOptions.type) || "BarChart";
    var options = _.extend({
            type: chartType,
            canvas: 'canvas_' + this.id
        },
        this.cccOptionsDefault.Base,
        this.cccOptionsDefault[chartType], // may be undefined
        baseOptions);

    if (this.adjustSizeTo) {
        var al = $(this.adjustSizeTo);

        if (al && al.length > 0) {
            var runtimeWidth = al.width() - 40;
            var runtimeHeight = al.height() - 40;
            if (runtimeWidth > 0) {
                options.width = runtimeWidth;
            }
            if (runtimeHeight > 0) {
                options.height = runtimeHeight;
            }
        }
    }

    if (this.data !== null && this.data.resultset.length > 5) {
        if (options.type === "HeatGridChart") {
//                options.xAxisSize = 200;
        } else if (options.orientation !== "horizontal") {
            options.extensionPoints = _.extend(Object.create(options.extensionPoints || {}),
                {
                    xAxisLabel_textAngle: -Math.PI / 2,
                    xAxisLabel_textAlign: "right",
                    xAxisLabel_textBaseline: "middle"
                });
        }
    }

    options.colors = Settings.CHART_COLORS;
    return options;
};


SaikuChartRenderer.prototype.define_chart = function (displayOptions) {
    if (!this.hasProcessed) {
        this.process_data_tree({data: this.rawdata}, true, true);
    }

    var self = this;
    var workspaceResults = (this.adjustSizeTo ? $(this.adjustSizeTo) : $(this.el));
    var isSmall = (this.data !== null && this.data.height < 80 && this.data.width < 80);
    var isMedium = (this.data !== null && this.data.height < 300 && this.data.width < 300);
    var isBig = (!isSmall && !isMedium);
    var animate = false;
    var hoverable = isSmall;

    var runtimeWidth = workspaceResults.width() - 40;
    var runtimeHeight = workspaceResults.height() - 40;

    var runtimeChartDefinition = _.clone(this.cccOptions);

    if (displayOptions && displayOptions.width) {
        runtimeWidth = displayOptions.width;
    }
    if (displayOptions && displayOptions.height) {
        runtimeHeight = displayOptions.height;
    }

    if (runtimeWidth > 0) {
        runtimeChartDefinition.width = runtimeWidth;
    }
    if (runtimeHeight > 0) {
        runtimeChartDefinition.height = runtimeHeight;
    }

    if (isBig) {
        if (runtimeChartDefinition.hasOwnProperty('extensionPoints') && runtimeChartDefinition.extensionPoints.hasOwnProperty('line_interpolate'))
            delete runtimeChartDefinition.extensionPoints.line_interpolate;
        if (runtimeChartDefinition.hasOwnProperty('extensionPoints') && runtimeChartDefinition.extensionPoints.hasOwnProperty('area_interpolate'))
            delete runtimeChartDefinition.extensionPoints.area_interpolate;
    }
    var zoomDefinition = {
        legend: {
            scenes: {
                item: {
                    execute: function () {

                        var chart = this.chart();

                        if (!chart.hasOwnProperty('keptVisibleDatumSet')) {
                            chart.keptVisibleDatumSet = [];
                        }

                        var keptSet = chart.keptVisibleDatumSet.length > 0 ? chart.keptVisibleDatumSet[chart.keptVisibleDatumSet.length - 1] : [];
                        var zoomedIn = keptSet.length > 0;

                        if (zoomedIn) {
                            _.intersection(this.datums().array(), keptSet).forEach(function (datum) {
                                datum.toggleVisible();
                            });

                        } else {
                            pvc.data.Data.toggleVisible(this.datums());
                        }

                        this.chart().render(true, true, false);

                    }
                }
            }
        },
        userSelectionAction: function (selectingDatums) {
            if (selectingDatums.length === 0) {
                return [];
            }

            var chart = self.chart.root;
            var data = chart.data;
            var selfChart = this.chart;

            if (!selfChart.hasOwnProperty('keptVisibleDatumSet')) {
                selfChart.keptVisibleDatumSet = [];
            }

            // we have too many datums to process setVisible = false initially
            if (data.datums().count() > 1500) {
                pvc.data.Data.setSelected(selectingDatums, true);
            } else if (data.datums(null, {visible: true}).count() == data.datums().count()) {
                $(self.el).find('.zoomout, .rerender').show();

                var all = data.datums().array();

                _.each(_.difference(all, selectingDatums), function (datum) {
                    datum.setVisible(false);
                });

                selfChart.keptVisibleDatumSet = [];
                selfChart.keptVisibleDatumSet.push(selectingDatums);

            } else {
                var kept = selfChart.keptVisibleDatumSet.length > 0 ? selfChart.keptVisibleDatumSet[selfChart.keptVisibleDatumSet.length - 1] : [];

                var visibleOnes = data.datums(null, {visible: true}).array();

                var baseSet = kept;
                if (visibleOnes.length < kept.length) {
                    baseSet = visibleOnes;
                    selfChart.keptVisibleDatumSet.push(visibleOnes);
                }

                var newSelection = [];
                _.each(_.difference(visibleOnes, selectingDatums), function (datum) {
                    datum.setVisible(false);
                });
                _.each(_.intersection(visibleOnes, selectingDatums), function (datum) {
                    newSelection.push(datum);
                });

                if (newSelection.length > 0) {
                    selfChart.keptVisibleDatumSet.push(newSelection);
                }
            }


            chart.render(true, true, false);
            return [];

        }
    };

    runtimeChartDefinition = _.extend(runtimeChartDefinition, {
        hoverable: hoverable,
        animate: animate
    }, this.chartDefinition);
//  if(this.chartDefinition != undefined && this.chartDefinition.legend == true){
    if (self.zoom) {
        var l = runtimeChartDefinition.legend;
        runtimeChartDefinition = _.extend(runtimeChartDefinition, zoomDefinition);
        if (l === false) {
            runtimeChartDefinition.legend = false;
        }
    }
//}

    if (runtimeChartDefinition.type == "TreemapChart" && runtimeChartDefinition.legend) {
        runtimeChartDefinition.legend.scenes.item.labelText = function () {
            var indent = "";
            var group = this.group;
            if (group) {
                var depth = group.depth;
                // 0 ""
                // 1 "text"
                // 2 "└ text"
                // 3 "  └ text"
                switch (depth) {
                    case 0:
                        return "";
                    case 1:
                        break;
                    case 2:
                        indent = " └ ";
                        break;
                    default:
                        indent = new Array(2 * (depth - 2) + 1).join(" ") + " └ ";
                }
            }
            return indent + this.base();
        };
    }

    if (runtimeChartDefinition.type === 'TimeWheel') {
        console.log(this.data);

        var nPlots = 0;
        var plotNames = {};

        for (var i = 0; i < this.data.resultset.length; i++) {
            plotNames[this.data.resultset[i][0]] = true;
        }

        plotNames = Object.keys(plotNames);
        nPlots = plotNames.length;

        var donutHeight = 1.0 / nPlots;
        var innerRadius = 1.0 - donutHeight;
        var outerRadius = 1.0;

        runtimeChartDefinition.plots = [];
        runtimeChartDefinition.dimensions = {};

        for (var i = 0; i < this.data.metadata.length; i++) {
            var colName = this.data.metadata[i].colName;

            runtimeChartDefinition.dimensions[colName] = {
                isHidden: false
            };
        }

        for (var i = 0; i < nPlots; i++) {
            var groupValue = plotNames[i];
            var _innerRadius = innerRadius;
            var _outerRadius = outerRadius;

            runtimeChartDefinition.plots.push({
                name: (i === 0 ? 'main' : 'plot_' + i),
                type: 'pie',
                dataPart: groupValue,
                valuesLabelStyle: 'inside',
                valuesOptimizeLegibility: true,
                valuesFont: 'normal 10px "Open Sans"',
                slice_strokeStyle: 'white',
                slice_innerRadiusEx: (_innerRadius * 100).toFixed(0) + '%',
                slice_outerRadius: function() {
                    return _outerRadius * this.delegate();
                }
            });

            innerRadius -= donutHeight;
            outerRadius -= donutHeight;
        }

        if (this.data.metadata.length > 2) {
            runtimeChartDefinition.readers = this.data.metadata[0].colName + ', ' +
                                             this.data.metadata[1].colName + ', ' +
                                             this.data.metadata[2].colName;

            runtimeChartDefinition.visualRoles = {
                dataPart: this.data.metadata[0].colName,
                category: this.data.metadata[1].colName,
                value:    this.data.metadata[2].colName
            };
        }

        this.chart = new pvc['PieChart'](runtimeChartDefinition);
        this.chart.setData(this.data, {
            crosstabMode: false
        });

    } else {
        this.chart = new pvc[runtimeChartDefinition.type](runtimeChartDefinition);
        this.chart.setData(this.data, {
            crosstabMode: true,
            seriesInRows: false
        });
    }
};

SaikuChartRenderer.prototype.render_chart_element = function (context) {
    var self = context || this;
    var isSmall = (self.data !== null && self.data.height < 80 && self.data.width < 80);
    var isMedium = (self.data !== null && self.data.height < 300 && self.data.width < 300);
    var isBig = (!isSmall && !isMedium);
    var animate = false;
    if (self.chart.options && self.chart.options.animate) {
        animate = true;
    }
    if (!animate || $(self.el).find('.canvas_wrapper').is(':visible')) {
        var els = $(self.el).find('.canvas_wrapper');
        $(self.el).find('.canvas_wrapper').hide();
    }

    try {
        if (animate) {
            $(self.el).find('.canvas_wrapper').show();
        }

        self.chart.render();
        self.hasRendered = true;
    } catch (e) {
        $('#' + 'canvas_' + self.id).text("Could not render chart" + e);
    }
    if (self.chart.options && self.chart.options.animate) {
        return false;
    }
    if (isIE || isBig) {
        $(self.el).find('.canvas_wrapper').show();
    } else {
        $(self.el).find('.canvas_wrapper').fadeIn(400);
    }


    return false;
};


SaikuChartRenderer.prototype.process_data_tree = function (args, flat, setdata) {
    var self = this;
    var data = {};
    if (flat) {
        data.resultset = [];
        data.metadata = [];
        data.height = 0;
        data.width = 0;
    }

    var currentDataPos = data;
    if (typeof args == "undefined" || typeof args.data == "undefined") {
        return;
    }

    if (args.data !== null && args.data.error !== null) {
        return;
    }
    // Check to see if there is data
    if (args.data === null || (args.data.cellset && args.data.cellset.length === 0)) {
        return;
    }

    var cellset = args.data.cellset;
    if (cellset && cellset.length > 0) {
        var lowest_level = 0;
        var data_start = 0;
        var hasStart = false;
        var row,
            rowLen,
            labelCol,
            reduceFunction = function (memo, num) {
                return memo + num;
            };

        for (row = 0, rowLen = cellset.length; data_start === 0 && row < rowLen; row++) {
            for (var field = 0, fieldLen = cellset[row].length; field < fieldLen; field++) {
                if (!hasStart) {
                    while (cellset[row][field].type == "COLUMN_HEADER" && cellset[row][field].value == "null") {
                        row++;
                    }
                }
                hasStart = true;
                if (cellset[row][field].type == "ROW_HEADER_HEADER") {
                    while (cellset[row][field].type == "ROW_HEADER_HEADER") {
                        if (flat) {
                            data.metadata.push({
                                colIndex: field,
                                colType: "String",
                                colName: cellset[row][field].value
                            });
                        }
                        field++;
                    }
                    lowest_level = field - 1;
                }
                if (cellset[row][field].type == "COLUMN_HEADER") {
                    var lowest_col_header = 0;
                    var colheader = [];
                    while (lowest_col_header <= row) {
                        if (cellset[lowest_col_header][field].value !== "null") {
                            colheader.push(cellset[lowest_col_header][field].value);
                        }
                        lowest_col_header++;
                    }
                    if (flat) {
                        data.metadata.push({
                            colIndex: field,
                            colType: "Numeric",
                            colName: colheader.join(' ~ ')
                        });
                    }
                    data_start = row + 1;
                }
            }
        }
        var labelsSet = {};
        var rowlabels = [];
        for (labelCol = 0; labelCol <= lowest_level; labelCol++) {
            rowlabels.push(null);
        }
        for (row = data_start, rowLen = cellset.length; row < rowLen; row++) {
            if (cellset[row][0].value !== "") {
                var record = [];
                var flatrecord = [];
                var parent = null;
                var rv = null;

                for (labelCol = 0; labelCol <= lowest_level; labelCol++) {
                    if (cellset[row] && cellset[row][labelCol].value === 'null') {
                        currentDataPos = data;
                        var prevLabel = 0;
                        for (; prevLabel < lowest_level && cellset[row][prevLabel].value === 'null'; prevLabel++) {
                            currentDataPos = currentDataPos[rowlabels[prevLabel]];
                        }
                        if (prevLabel > labelCol) {
                            labelCol = prevLabel;
                        }

                    }
                    if (cellset[row] && cellset[row][labelCol].value !== 'null') {
                        if (labelCol === 0) {
                            for (var xx = 0; xx <= lowest_level; xx++) {
                                rowlabels[xx] = null;
                            }
                        }
                        if (typeof currentDataPos == "number") {
                            parent[rv] = {};
                            currentDataPos = parent[rv];
                        }
                        rv = cellset[row][labelCol].value;
                        rowlabels[labelCol] = rv;

                        if (!currentDataPos.hasOwnProperty(rv)) {
                            currentDataPos[rv] = {};
                        }
                        parent = currentDataPos;
                        currentDataPos = currentDataPos[rv];
                    }
                }
                flatrecord = _.clone(rowlabels);
                for (var col = lowest_level + 1, colLen = cellset[row].length; col < colLen; col++) {
                    var cell = cellset[row][col];
                    var value = cell.value || 0;
                    var maybePercentage = (value !== 0);
                    // check if the resultset contains the raw value, if not try to parse the given value
                    var raw = cell.properties.raw;
                    if (raw && raw !== "null") {
                        value = parseFloat(raw);
                    } else if (typeof(cell.value) !== "number" && parseFloat(cell.value.replace(/[^a-zA-Z 0-9.]+/g, ''))) {
                        value = parseFloat(cell.value.replace(/[^a-zA-Z 0-9.]+/g, ''));
                        maybePercentage = false;
                    }
                    if (value > 0 && maybePercentage) {
                        value = cell.value && cell.value.indexOf('%') >= 0 ? value * 100 : value;
                    }
                    record.push(value);

                    flatrecord.push({f: cell.value, v: value});
                }
                if (flat) data.resultset.push(flatrecord);
                var sum = _.reduce(record, reduceFunction, 0);
                rv = (rv === null ? "null" : rv);
                parent[rv] = sum;
                currentDataPos = data;
            }
        }
        if (setdata) {
            self.rawdata = args.data;
            self.data = data;
            self.hasProcessed = true;
            self.data.height = self.data.resultset.length;
        }
        return data;
    } else {
        $(self.el).find('.canvas_wrapper').text("No results").show();
    }
};

SaikuChartRenderer.prototype.drawRadarChart = function (o) {
    var RadarChart = {
        draw: function(id, d, options, LegendOptions) {
            var cfg = {
                radius: 5,
                w: options.w || 320,
                h: options.h || 240,
                factor: 1,
                factorLegend: .85,
                levels: 3,
                maxValue: 0,
                radians: 2 * Math.PI,
                opacityArea: 0.5,
                ToRight: 5,
                TranslateX: 0,
                TranslateY: 30,
                ExtraWidthX: 0,
                ExtraWidthY: 65,
                color: d3.scale.category10()
            };

            if ('undefined' !== typeof options) {
                for(var i in options) {
                    if('undefined' !== typeof options[i]) {
                        cfg[i] = options[i];
                    }
                }
            }

            cfg.maxValue = Math.max(cfg.maxValue, d3.max(d, function(i){return d3.max(i.map(function(o){return o.value;}))}));

            var allAxis = (d[0].map(function(i, j){return i.axis}));
            var total   = allAxis.length;
            var radius  = cfg.factor * Math.min(cfg.w / 2, cfg.h / 2);

            d3.select(id).select("svg").remove();

            var g = d3.select(id)
                .append("svg")
                .attr("width",  cfg.w + cfg.ExtraWidthX)
                .attr("height", cfg.h + cfg.ExtraWidthY)
                .append("g")
                .attr("transform", "translate(" + cfg.TranslateX + "," + cfg.TranslateY + ")");

            var tooltip;

            //Circular segments
            for (var j=0; j<cfg.levels-1; j++) {
                var levelFactor = cfg.factor*radius*((j+1)/cfg.levels);
                g.selectAll(".levels")
                    .data(allAxis)
                    .enter()
                    .append("svg:line")
                    .attr("x1", function(d, i){return levelFactor*(1-cfg.factor*Math.sin(i*cfg.radians/total));})
                    .attr("y1", function(d, i){return levelFactor*(1-cfg.factor*Math.cos(i*cfg.radians/total));})
                    .attr("x2", function(d, i){return levelFactor*(1-cfg.factor*Math.sin((i+1)*cfg.radians/total));})
                    .attr("y2", function(d, i){return levelFactor*(1-cfg.factor*Math.cos((i+1)*cfg.radians/total));})
                    .attr("class", "line")
                    .style("stroke", "grey")
                    .style("stroke-opacity", "0.75")
                    .style("stroke-width", "0.3px")
                    .attr("transform", "translate(" + (cfg.w/2-levelFactor) + ", " + (cfg.h/2-levelFactor) + ")");
            }

            //Text indicating at what % each level is
            for (var j=0; j<cfg.levels; j++) {
                var levelFactor = cfg.factor*radius*((j+1)/cfg.levels);
                g.selectAll(".levels")
                    .data([1]) //dummy data
                    .enter()
                    .append("svg:text")
                    .attr("x", function(d){return levelFactor*(1-cfg.factor*Math.sin(0));})
                    .attr("y", function(d){return levelFactor*(1-cfg.factor*Math.cos(0));})
                    .attr("class", "legend")
                    .style("font-family", "sans-serif")
                    .style("font-size", "10px")
                    .attr("transform", "translate(" + (cfg.w/2-levelFactor + cfg.ToRight) + ", " + (cfg.h/2-levelFactor) + ")")
                    .attr("fill", "#737373")
                    .text((j+1)*cfg.maxValue/cfg.levels);
            }

            series = 0;

            var axis = g.selectAll(".axis")
                .data(allAxis)
                .enter()
                .append("g")
                .attr("class", "axis");

            axis.append("line")
                .attr("x1", cfg.w/2)
                .attr("y1", cfg.h/2)
                .attr("x2", function(d, i){return cfg.w/2*(1-cfg.factor*Math.sin(i*cfg.radians/total));})
                .attr("y2", function(d, i){return cfg.h/2*(1-cfg.factor*Math.cos(i*cfg.radians/total));})
                .attr("class", "line")
                .style("stroke", "grey")
                .style("stroke-width", "1px");

            function getTextMetrics(text, font) {
                // re-use canvas object for better performance
                var canvas = getTextMetrics.canvas || (getTextMetrics.canvas = document.createElement("canvas"));
                var context = canvas.getContext("2d");
                context.font = font;
                var metrics = context.measureText(text);
                return metrics;
            }

            axis.append("text")
                .attr("class", "legend")
                .text(function(d){return d})
                .style("font-family", "sans-serif")
                .style("font-size", "10px")
                .attr("text-anchor", "start")
                .attr("dy", "1.5em")
                .attr("transform", function(d, i){return "translate(0, -10)"})
                .attr("x", function(d, i) {
                    var x = cfg.w / 2 * (1 - cfg.factorLegend * Math.sin(i * cfg.radians / total)) - 60 * Math.sin(i * cfg.radians / total);
                    var textWidth = getTextMetrics(d, "10px sans-serif").width;

                    x = x - textWidth / 2;

                    if (x < 0) {
                        x = 0;
                    }

                    if ((x + textWidth) > cfg.w) {
                        x = cfg.w - textWidth;
                    }

                    return x;
                })
                .attr("y", function(d, i){
                    var y = cfg.h / 2 * (1 - Math.cos(i * cfg.radians / total)) - 20 * Math.cos(i * cfg.radians / total);
                    return y;
                });

            d.forEach(function(y, x) {
                dataValues = [];
                g.selectAll(".nodes")
                    .data(y, function(j, i) {
                        dataValues.push([
                            cfg.w/2*(1-(parseFloat(Math.max(j.value, 0))/cfg.maxValue)*cfg.factor*Math.sin(i*cfg.radians/total)),
                            cfg.h/2*(1-(parseFloat(Math.max(j.value, 0))/cfg.maxValue)*cfg.factor*Math.cos(i*cfg.radians/total))
                        ]);
                    });
                dataValues.push(dataValues[0]);
                g.selectAll(".area")
                    .data([dataValues])
                    .enter()
                    .append("polygon")
                    .attr("class", "radar-chart-serie"+series)
                    .style("stroke-width", "2px")
                    .style("stroke", cfg.color(series))
                    .attr("points",function(d) {
                        var str="";
                        for(var pti=0;pti<d.length;pti++){
                            str=str+d[pti][0]+","+d[pti][1]+" ";
                        }
                        return str;
                    })
                    .style("fill", function(j, i){return cfg.color(series)})
                    .style("fill-opacity", cfg.opacityArea)
                    .on('mouseover', function (d){
                        z = "polygon."+d3.select(this).attr("class");
                        g.selectAll("polygon")
                            .transition(200)
                            .style("fill-opacity", 0.1);
                            g.selectAll(z)
                            .transition(200)
                            .style("fill-opacity", .7);
                    })
                    .on('mouseout', function(){
                        g.selectAll("polygon")
                            .transition(200)
                            .style("fill-opacity", cfg.opacityArea);
                    });
                series++;
            });
            series=0;


            d.forEach(function(y, x){
                g.selectAll(".nodes")
                    .data(y).enter()
                    .append("svg:circle")
                    .attr("class", "radar-chart-serie"+series)
                    .attr('r', cfg.radius)
                    .attr("alt", function(j){return Math.max(j.value, 0)})
                    .attr("cx", function(j, i){
                        dataValues.push([
                            cfg.w/2*(1-(parseFloat(Math.max(j.value, 0))/cfg.maxValue)*cfg.factor*Math.sin(i*cfg.radians/total)),
                            cfg.h/2*(1-(parseFloat(Math.max(j.value, 0))/cfg.maxValue)*cfg.factor*Math.cos(i*cfg.radians/total))
                        ]);
                        return cfg.w/2*(1-(Math.max(j.value, 0)/cfg.maxValue)*cfg.factor*Math.sin(i*cfg.radians/total));
                    })
                    .attr("cy", function(j, i){
                        return cfg.h/2*(1-(Math.max(j.value, 0)/cfg.maxValue)*cfg.factor*Math.cos(i*cfg.radians/total));
                    })
                    .attr("data-id", function(j){return j.axis})
                    .style("fill", cfg.color(series)).style("fill-opacity", .9)
                    .on('mouseover', function (d){
                        newX =  parseFloat(d3.select(this).attr('cx')) - 10;
                        newY =  parseFloat(d3.select(this).attr('cy')) - 5;

                        tooltip
                            .attr('x', newX)
                            .attr('y', newY)
                            .text(d.value)
                            .transition(200)
                            .style('opacity', 1);

                        z = "polygon."+d3.select(this).attr("class");
                        g.selectAll("polygon")
                            .transition(200)
                            .style("fill-opacity", 0.1);
                        g.selectAll(z)
                            .transition(200)
                            .style("fill-opacity", .7);
                    })
                    .on('mouseout', function(){
                        tooltip
                            .transition(200)
                            .style('opacity', 0);
                        g.selectAll("polygon")
                            .transition(200)
                            .style("fill-opacity", cfg.opacityArea);
                    })
                    .append("svg:title")
                    .text(function(j){return Math.max(j.value, 0)});

                series++;
            });

            //Tooltip
            tooltip = g.append('text')
                .style('opacity', 0)
                .style('font-family', 'sans-serif')
                .style('font-size', '13px');



            ////////////////////////////////////////////
            /////////// Initiate legend ////////////////
            ////////////////////////////////////////////

            var svg = d3.select(canvasIdSelector)
                .selectAll('svg')
                .append('svg')
                .attr("width", w)
                .attr("height", h);

            //Initiate Legend
            var legend = svg.append("g")
                .attr("class", "legend")
                .attr("height", w)
                .attr("width", h);

            //Create colour squares
            legend.selectAll('rect')
                .data(LegendOptions)
                .enter()
                .append("rect")
                .attr("x", 5)
                .attr("y", function(d, i){ return i * 20;})
                .attr("width", 10)
                .attr("height", 10)
                .style("fill", function(d, i){ return colorscale(i);});


            //Create text next to squares
            legend.selectAll('text')
                .data(LegendOptions)
                .enter()
                .append("text")
                .attr("x", 20)
                .attr("y", function(d, i){ return i * 20 + 9;})
                .attr("font-size", "10px")
                .attr("fill", "#737373")
                .text(function(d) { return d; });


        }
    };

    var options = this.getQuickOptions(o);
    var w = options.width;
    var h = options.height;

    var colorscale = d3.scale.category10();

    //Legend titles
    var LegendOptions = [];

    //Dataset
    var d = [];

    var prefixCols = [];
    var dataCols = [];

    //Determining, from data, what is legend and what is values
    for (var i = 0; i < this.data.metadata.length; i++) {
        if (this.data.metadata[i].colType === 'String') {
            prefixCols.push(i);
        } else {
            dataCols.push(i);
        }
    }

    //Filling the dataset values for each row
    for (var i = 0; i < this.data.resultset.length; i++) {
        d[i] = [];
        var legend = [];

        // Build its respective legend
        for (var j = 0; j < prefixCols.length; j++) {
            legend.push(this.data.resultset[i][j]);
        }

        LegendOptions.push(legend.join(' '));

        // Build its respective data object
        for (var j = 0; j < dataCols.length; j++) {
            var col = dataCols[j];

            d[i].push({
                axis: this.data.metadata[col].colName,
                value: this.data.resultset[i][col].v
            });
        }
    }

    //Options for the Radar chart, other than default
    var mycfg = {
        w: w,
        h: h,
        maxValue: 0.6,
        levels: 6,
        ExtraWidthX: 0
    }

    // Drawing the chart
    var canvasIdSelector = '#' + this.cccOptions.canvas;

    // Clean the previous chart
    $(canvasIdSelector).html('');

    //Call function to draw the Radar chart
    //Will expect that data is in %'s
    RadarChart.draw(canvasIdSelector, d, mycfg, LegendOptions);

    this.chart = {
        render: function() {
            RadarChart.draw(canvasIdSelector, d, mycfg, LegendOptions);
        }
    };

    this.render();
};
