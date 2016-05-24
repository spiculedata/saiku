var SaikuChartRenderer = function (data, options) {
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
    if(override != null || override != undefined){
        if(override.chartDefinition != null || override.chartDefinition != undefined) {
            this.chartDefinition = override.chartDefinition;
        }
        if(override.workspace !=null || override.workspace != undefined){
            this.workspace = override.workspace;
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
        }
    };

    if (key === null || key === '') {

    }
    else if (key == "sunburst") {
     $(this.el).find('.zoombuttons a').hide();
     this.type = key;
     var o = keyOptions[key];
     this.sunburst(o);
     if (this.hasProcessed) {
     this.render();
     }

     } else if (keyOptions.hasOwnProperty(key)) {
        $(this.el).find('.zoombuttons a').hide();
        this.type = key;
        var o = keyOptions[key];
        this.cccOptions = this.getQuickOptions(o);
        this.define_chart();
        if (this.hasProcessed) {
            this.render();
        }

    } else {
        alert("Do not support chart type: '" + key + "'");
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
//al.appendTo(document.body);
//var width = al.width();
//al.remove();
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

    options.colors = ["#1f77b4", "#aec7e8", "#ff7f0e", "#ffbb78", "#2ca02c", "#98df8a", "#d62728", "#ff9896", "#9467bd", "#c5b0d5", "#8c564b", "#c49c94", "#e377c2", "#f7b6d2", "#7f7f7f", "#c7c7c7", "#bcbd22", "#dbdb8d", "#17becf", "#9edae5"];
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
//	if(this.chartDefinition != undefined && this.chartDefinition.legend == true){
    if (self.zoom) {
        var l = runtimeChartDefinition.legend;
        runtimeChartDefinition = _.extend(runtimeChartDefinition, zoomDefinition);
        if (l === false) {
            runtimeChartDefinition.legend = false;
        }
    }
//}

    if (runtimeChartDefinition.type == "TreemapChart") {
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
    this.chart = new pvc[runtimeChartDefinition.type](runtimeChartDefinition);
    this.chart.setData(this.data, {
        crosstabMode: true,
        seriesInRows: false
    });
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
