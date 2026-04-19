function SaikuTableRenderer(data, options) {
    this._data = data;
    this._options = _.extend({}, SaikuRendererOptions, options);
}

function getAxisLevelsName(data, axisName) {
    var queryData = data.query.queryModel.axes[axisName].hierarchies;
    var len = queryData.length;
    var arrLevels = [];

    for (var i = 0; i < len; i++) {
        for (var level in queryData[i].levels) {
            if (queryData[i].levels.hasOwnProperty(level)) {
                if (Settings.COLUMN_TITLE_TABLE_USE_LEVEL_CAPTION_NAME) {
                    arrLevels.push(queryData[i].levels[level].caption);
                }
                else {
                    arrLevels.push(level);
                }
            }
        }
    }

    return arrLevels;
}

function setStyleNegativeNumber(value) {
    var className = '';

    if (Settings.STYLE_NEGATIVE_NUMBER && parseFloat(value) < 0) {
        className = ' style_negative_number ';
    }

    return className;
}

function getAxisSize(data, axisName) {
    var queryData = data.query.queryModel.axes[axisName].hierarchies;
    var len = queryData.length;
    var axisSize = 0;

    for (var i = 0; i < len; i++) {
        axisSize += _.size(queryData[i].levels);
    }

    return axisSize;
}

function getDomColumnsLevelsName(htmlObject) {
    var $htmlObject = $(htmlObject.closest('.workspace')
                           .find('.workspace_fields')
                           .find('.columns.axis_fields')
                           .find('.hierarchy')
                           .find('.d_level'));
    var arrLevels = [];

    $.each($htmlObject, function(key, level) {
        if ($(level).attr('style') === 'display: list-item;') {
            if (Settings.COLUMN_TITLE_TABLE_USE_LEVEL_CAPTION_NAME) {
                arrLevels.push($(level).find('.level').attr('title'));
            }
            else {
                arrLevels.push($(level).find('.level').attr('level'));
            }
        }
    });

    return arrLevels;
}

SaikuTableRenderer.prototype.render = function(data, options) {
        var self = this;
        if (data) {
            this._data = data;
        }
        if (options) {
            this._options = _.extend({}, SaikuRendererOptions, options);
        }

        if (typeof this._data == "undefined") {
            return;
        }

        if (this._data != null && this._data.error != null) {
            return;
        }
        if (this._data == null || (this._data.cellset && this._data.cellset.length === 0)) {
            return;
        }

        this.hideEmpty = this._options.hideEmpty;

        if (this._options.htmlObject) {
//            $(this._options.htmlObject).stickyTableHeaders("destroy");

            // in case we have some left over scrollers
            if (self._options.hasOwnProperty('batch')) {
                $(self._options.htmlObject).parent().parent().unbind('scroll');
            }

            _.defer(function(that) {
                if (self._options.hasOwnProperty('batch') && !self._options.hasOwnProperty('batchSize')) {
                    self._options['batchSize'] = 1000;
                }

                var html =  self.internalRender(self._data, self._options);
                $(self._options.htmlObject).html(html);
                // Render the totals summary
                $('#totals_summary').remove(); // Remove one previous totals div, if present
                $(self._options.htmlObject).after(self.renderSummary(data)); // Render the new summary

//                $(self._options.htmlObject).stickyTableHeaders( { container: self._options.htmlObject.parent().parent(), fixedOffset: self._options.htmlObject.parent().parent().offset().top });

                _.defer(function(that) {
                    if (self._options.hasOwnProperty('batch') && self._options.hasBatchResult) {
                        var batchRow = 0;
                        var batchIsRunning = false;
                        var batchIntervalSize = self._options.hasOwnProperty('batchIntervalSize') ? self._options.batchIntervalSize : 20;
                        var batchIntervalTime = self._options.hasOwnProperty('batchIntervalTime') ? self._options.batchIntervalTime : 20;

                        var len = self._options.batchResult.length;

                        var batchInsert = function() {
                            // maybe add check for reach table bottom - ($('.workspace_results').scrollTop() , $('.workspace_results table').height()
                            if (!batchIsRunning && len > 0 && batchRow < len) {
                                batchIsRunning = true;
                                var batchContent = "";
                                var startb = batchRow;
                                for (var i = 0;  batchRow < len && i < batchIntervalSize ; i++, batchRow++) {
                                    batchContent += self._options.batchResult[batchRow];
                                }
                                if (batchRow > startb) {
                                    $(self._options.htmlObject).append( $(batchContent));
                                }
                                batchIsRunning = false;
                            }
                            if (batchRow >= len) {
                                $(self._options.htmlObject).parent().parent().unbind('scroll');
                            }
                        };

                        var lazyBatchInsert = _.debounce(batchInsert, batchIntervalTime);
                        $(self._options.htmlObject).parent().parent().scroll(function () {
                            lazyBatchInsert();
                        });
                    }
                });
                return html;
            });
        } else {
            var html =  this.internalRender(this._data, self._options);
            return html;
        }

};

SaikuTableRenderer.prototype.clear = function(data, options) {
    var self = this;
    if (this._options && this._options.htmlObject && this._options.hasOwnProperty('batch')) {
        $(self._options.htmlObject).parent().parent().unbind('scroll');
    }

};

SaikuTableRenderer.prototype.processData = function(data, options) {
    this._hasProcessed = true;
};

function genTotalDataCells(currentIndex, cellIndex, scanSums, scanIndexes, lists) {
    var contents = '';
    var lists = lists[ROWS];

    for (var i = scanSums.length - 1; i >= 0; i--) {
        if (currentIndex == scanSums[i]) {
            var currentListNode = lists[i][scanIndexes[i]];
            for (var m = 0; m < currentListNode.cells.length; m++) {
                contents += '<td class="data total">' + currentListNode.cells[m][cellIndex].value + '</td>';
            }

            scanIndexes[i]++;
            if (scanIndexes[i] < lists[i].length)
                scanSums[i] += lists[i][scanIndexes[i]].width;
        }
    }

    return contents;
}

function genTotalHeaderCells(currentIndex, bottom, scanSums, scanIndexes, lists, wrapContent) {
    var contents = '';
    for (var i = bottom; i >= 0; i--) {
        if (currentIndex == scanSums[i]) {
            var currentListNode = lists[i][scanIndexes[i]];
            var cssClass;
            if (i == 0 && bottom == 1)
                cssClass = "col";
            else if (i == bottom)
                cssClass = "col_total_corner";
            else if (i == bottom - 1 && currentListNode.captions)
                cssClass = "col_total_first";
            else cssClass = "col_null";

            for (var m = 0; m < currentListNode.cells.length; m++) {
                var text = '&nbsp;';
                if (bottom == lists.length - 1) {
                    if (currentListNode.captions) {
                        text = lists[i][scanIndexes[i]].captions[m];
                    }
                    if (i == 0 && scanIndexes[i] == 0) {
                        if (currentListNode.captions)
                            text += "&nbsp;";
                        else text = "";
                        text += (wrapContent ? "<span class='i18n'>Grand Total</span>" :  "Grand Total");
                    }
                }
                contents += '<th class="' + cssClass + '">'
                  + (wrapContent ? '<div>' + text + '</div>' : text ) + '</th>';
            }
            scanIndexes[i]++;
            if (scanIndexes[i] < lists[i].length)
                scanSums[i] += lists[i][scanIndexes[i]].width;
        }
    }
    return contents;
}

function totalIntersectionCells(currentIndex, bottom, scanSums, scanIndexes, lists) {
    var contents = '';
    for (var i = bottom; i >= 0; i--) {
        if (currentIndex == scanSums[i]) {
            var currentListNode = lists[i][scanIndexes[i]];
            var cssClass = "data total";
            for (var m = 0; m < currentListNode.cells.length; m++) {
                var text = '&nbsp;';
                contents += '<td class="' + cssClass + '">' + text + '</td>';
            }
            scanIndexes[i]++;
            if (scanIndexes[i] < lists[i].length)
                scanSums[i] += lists[i][scanIndexes[i]].width;
        }
    }
    return contents;
}

function isNextTotalsRow(currentIndex, scanSums, scanIndexes, totalsLists, wrapContent) {
    var colLists = totalsLists[COLUMNS];
    var colScanSums = scanSums[COLUMNS];
    var colScanIndexes = scanIndexes[COLUMNS];
    var bottom = colLists.length - 2;
    var contents = -1;
    for (var i = bottom; i >= 0; i--) {
        if (currentIndex == colScanSums[i]) {
            for (var m = 0; m < colLists[i][colScanIndexes[i]].cells.length; m++) {
                contents += '<tr>';
                for (var j = 0; j <= bottom; j++) {
                    var cssClass;
                    var text = '&nbsp;';
                    if (i == 0 && j == 0)
                        cssClass = 'row';
                    else if (i == j + 1){
                        cssClass = 'row_total_corner';
                        return j;
                    }
                    else if (i == j && colLists[i][colScanIndexes[i]].captions) {
                        cssClass = 'row_total_first';
                    } else if (i < j + 1)
                        cssClass = 'row_total';
                    else
                        cssClass = 'row_null';
                    if (j == bottom ) {
                        if (colLists[i][colScanIndexes[i]].captions) {
                            text = colLists[i][colScanIndexes[i]].captions[m];
                        }
                        if (i == 0 && colScanIndexes[i] == 0) {
                            if (colLists[i][colScanIndexes[i]].captions)
                                text += "&nbsp;";
                            else text = "";
                            text += (wrapContent ? "<span class='i18n'>Grand Total</span>" :  "Grand Total");
                        }
                    }

                }
            }
        }
    }
    return -1;
}

function genTotalHeaderRowCells(currentIndex, scanSums, scanIndexes, totalsLists, wrapContent) {
    var colLists = totalsLists[COLUMNS];
    var colScanSums = scanSums[COLUMNS];
    var colScanIndexes = scanIndexes[COLUMNS];
    var bottom = colLists.length - 2;
    var contents = '';
    for (var i = bottom; i >= 0; i--) {
        if (currentIndex == colScanSums[i]) {
            for (var m = 0; m < colLists[i][colScanIndexes[i]].cells.length; m++) {
                contents += '<tr>';
                for (var j = 0; j <= bottom; j++) {
                    var cssClass;
                    var text = '&nbsp;';
                    if (i == 0 && j == 0)
                        cssClass = 'row';
                    else if (i == j + 1)
                        cssClass = 'row_total_corner';
                    else if (i == j && colLists[i][colScanIndexes[i]].captions) {
                        cssClass = 'row_total_first';
                    } else if (i < j + 1)
                        cssClass = 'row_total';
                    else
                        cssClass = 'row_null';
                    if (j == bottom ) {
                        if (colLists[i][colScanIndexes[i]].captions) {
                            text = colLists[i][colScanIndexes[i]].captions[m];
                        }
                        if (i == 0 && colScanIndexes[i] == 0) {
                            if (colLists[i][colScanIndexes[i]].captions)
                                text += "&nbsp;";
                            else text = "";
                            text += (wrapContent ? "<span class='i18n'>Grand Total</span>" :  "Grand Total");
                        }
                    }
                    contents += '<th class="' + cssClass + '">'
                                + (wrapContent ? '<div>' + text + '</div>' : text ) + '</th>';

                }

                var scanIndexes = {};
                var scanSums = {};

                if (totalsLists[ROWS]) {
                    for (var z = 0; z < totalsLists[ROWS].length; z++) {
                        scanIndexes[z] = 0;
                        scanSums[z] = totalsLists[ROWS][z][scanIndexes[z]].width;
                    }
                }

                for (var k = 0; k < colLists[i][colScanIndexes[i]].cells[m].length; k++) {
                    contents += '<td class="data total">' + colLists[i][colScanIndexes[i]].cells[m][k].value + '</td>';

                    if (totalsLists[ROWS]) {
                        contents += totalIntersectionCells(k + 1, totalsLists[ROWS].length - 1, scanSums, scanIndexes, totalsLists[ROWS]);
                    }
                }

                contents += '</tr>';
            }

            colScanIndexes[i]++;

            if (colScanIndexes[i] < colLists[i].length) {
                colScanSums[i] += colLists[i][colScanIndexes[i]].width;
            }
        }
    }
    return contents;
}

var ROWS = "ROWS";
var COLUMNS = "COLUMNS";

function nextParentsDiffer(data, row, col) {
    while (row-- > 0) {
        if (data[row][col].properties.uniquename != data[row][col + 1].properties.uniquename)
            return true;
    }
    return false;
}


function topParentsDiffer(data, row, col) {
    while (col-- > 0)
        if (data[row][col].properties.uniquename != data[row - 1][col].properties.uniquename)
            return true;
    return false;
}

/**
 * This function is intended to traverse the totals arrays and cleanup empty
 * totals. This will optimize the query result on screen, displaying just the
 * needed cells.
 * @param dirs The direction array ['ROWS', 'COLUMNS']
 * @param totalsLists The totals from allData.rowTotalsLists and allData.colTotalsLists.
 */
function cleanupTotals(dirs, totalsLists) {
    // For each direction (ROWS/COLUMNS)
    for (var dirIndex = 0; dirIndex < dirs.length; dirIndex++) {
        var dir = dirs[dirIndex];

        // If there are defined totals
        if (totalsLists[dir]) {
            var isEmpty = true; // A flag to indicate if this total is empty
            for (var row = 0; row < totalsLists[dir].length; row++) {
                var totalsInfoArray = totalsLists[dir][row];
                for (var totalIndex = 0; totalIndex < totalsInfoArray.length; totalIndex++) {
                    var cells = totalsLists[dir][row][totalIndex].cells;
                    for (var cellIndex = 0; cellIndex < cells.length; cellIndex++) {
                        var cellArray = cells[cellIndex];
                        // For each total cell
                        for (var i = 0; i < cellArray.length; i++) {
                            var cell = cellArray[i];
                            // If it contains a value different from empty
                            if (cell.value !== '-') {
                                isEmpty = false; // So, this total is not empty
                            }
                        }
                    }
                }
            }

            if (isEmpty) { // If this total is empty
                totalsLists[dir] = null; // Remove it
            }
        }
    }
}

SaikuTableRenderer.prototype.internalRender = function(allData, options) {
    var tableContent = "";
    var rowContent = "";
    var data = allData.cellset;

    var newRowContent = '';
    var arrRowData = [];
    var objRowData = [];

    var table = data ? data : [];
    var colSpan;
    var colValue;
    var isHeaderLowestLvl;
    var isBody = false;
    var firstColumn;
    var isLastColumn, isLastRow;
    var nextHeader;
    var processedRowHeader = false;
    var lowestRowLvl = 0;
    var rowGroups = [];
    var batchSize = null;
    var batchStarted = false;
    var isColHeader = false, isColHeaderDone = false;
    var resultRows = [];
    var wrapContent = true;
    if (options) {
        batchSize = options.hasOwnProperty('batchSize') ? options.batchSize : null;
        wrapContent = options.hasOwnProperty('wrapContent') ? options.wrapContent : true;
    }
    var totalsLists = {};
    totalsLists[COLUMNS] = allData.rowTotalsLists;
    totalsLists[ROWS] = allData.colTotalsLists;

    var scanSums = {};
    var scanIndexes = {};

    var dirs = [ROWS, COLUMNS];

    var hasMeasures = allData.query && allData.query.queryModel && allData.query.queryModel.details
                   ? allData.query.queryModel.details.measures.length
                   : 0;

    if (typeof this._options.htmlObject === 'object' &&
        Settings.ALLOW_AXIS_COLUMN_TITLE_TABLE &&
        hasMeasures > 0 &&
        allData.query.type === 'QUERYMODEL' &&
        allData.query.queryModel.details.axis === 'COLUMNS' &&
        allData.query.queryModel.details.location === 'BOTTOM') {

        var arrColumnTitleTable = getAxisLevelsName(allData, COLUMNS);
        var arrDomColumnTitleTable = getDomColumnsLevelsName(this._options.htmlObject);
        var colspanColumnTitleTable = getAxisSize(allData, ROWS);
        var auxColumnTitleTable = 0;

        if (arrColumnTitleTable.length === arrDomColumnTitleTable.length) {
            arrColumnTitleTable = arrDomColumnTitleTable;
        }
        else {
            arrColumnTitleTable = _.intersection(arrDomColumnTitleTable, arrColumnTitleTable);
        }
    }

    for (var i = 0; i < dirs.length; i++) {
        scanSums[dirs[i]] = new Array();
        scanIndexes[dirs[i]] = new Array();
    }

    // Here we cleaup the empty totals
    cleanupTotals(dirs, totalsLists);

    if (totalsLists[COLUMNS]) {
        for (var i = 0; i < totalsLists[COLUMNS].length; i++) {
            scanIndexes[COLUMNS][i] = 0;
            scanSums[COLUMNS][i] = totalsLists[COLUMNS][i][scanIndexes[COLUMNS][i]].width;
        }
    }

    for (var row = 0, rowLen = table.length; row < rowLen; row++) {
        var rowShifted = row - allData.topOffset;
        colSpan = 1;
        colValue = "";
        isHeaderLowestLvl = false;
        isLastColumn = false;
        isLastRow = false;
        isColHeader = false;
        var headerSame = false;

        if (totalsLists[ROWS]) {
            for (var i = 0; i < totalsLists[ROWS].length; i++) {
                scanIndexes[ROWS][i] = 0;
                scanSums[ROWS][i] = totalsLists[ROWS][i][scanIndexes[ROWS][i]].width;
            }
        }

        rowWithOnlyEmptyCells = true;
        rowContent = "<tr>";
        var header = null;

        if (row === 0) {
            rowContent = "<thead>" + rowContent;
        }

        if (typeof this._options.htmlObject === 'object' &&
            Settings.ALLOW_AXIS_COLUMN_TITLE_TABLE &&
            hasMeasures > 0 &&
            allData.query.type === 'QUERYMODEL' &&
            allData.query.queryModel.details.axis === 'COLUMNS' &&
            allData.query.queryModel.details.location === 'BOTTOM' &&
            auxColumnTitleTable < arrColumnTitleTable.length) {

            rowContent += '<th class="row_header" style="text-align: right;" colspan="' + colspanColumnTitleTable + '" title="' + arrColumnTitleTable[auxColumnTitleTable] + '">'
                + (wrapContent ? '<div>' + arrColumnTitleTable[auxColumnTitleTable] + '</div>' : arrColumnTitleTable[auxColumnTitleTable])
                + '</th>';

            auxColumnTitleTable += 1;
        }

        for (var col = 0, colLen = table[row].length; col < colLen; col++) {
            var colShifted = col - allData.leftOffset;
            header = data[row][col];

            if (header.type === "COLUMN_HEADER") {
                isColHeader = true;
            }

            // If the cell is a column header and is null (top left of table)
            if (header.type === "COLUMN_HEADER" && header.value === "null" && (firstColumn == null || col < firstColumn)) {
                if (((!Settings.ALLOW_AXIS_COLUMN_TITLE_TABLE || (Settings.ALLOW_AXIS_COLUMN_TITLE_TABLE && (allData.query.queryModel.details && allData.query.queryModel.details.location !== 'BOTTOM'))) || hasMeasures === 0) ||
                    allData.query.type === 'MDX') {
                    rowContent += '<th class="all_null">&nbsp;</th>';
                }
            } // If the cell is a column header and isn't null (column header of table)
            else if (header.type === "COLUMN_HEADER") {
                if (firstColumn == null) {
                    firstColumn = col;
                }
                if (table[row].length == col+1)
                    isLastColumn = true;
                else
                    nextHeader = data[row][col+1];


                if (isLastColumn) {
                    // Last column in a row...
                    if (header.value == "null") {
                        rowContent += '<th class="col_null">&nbsp;</th>';
                    } else {
                        if (totalsLists[ROWS])
                            colSpan = totalsLists[ROWS][row + 1][scanIndexes[ROWS][row + 1]].span;
                        rowContent += '<th class="col" style="text-align: center;" colspan="' + colSpan + '" title="' + header.value + '">'
                            + (wrapContent ? '<div rel="' + row + ":" + col +'">' + header.value + '</div>' : header.value)
                            + '</th>';
                    }

                } else {
                    // All the rest...
                    var groupChange = (col > 1 && row > 1 && !isHeaderLowestLvl && col > firstColumn) ?
                        data[row-1][col+1].value != data[row-1][col].value || data[row-1][col+1].properties.uniquename != data[row-1][col].properties.uniquename
                        : false;

                    var maxColspan = colSpan > 999 ? true : false;
                    if (header.value != nextHeader.value || nextParentsDiffer(data, row, col) || isHeaderLowestLvl || groupChange || maxColspan) {
                        if (header.value == "null") {
                            rowContent += '<th class="col_null" colspan="' + colSpan + '">&nbsp;</th>';
                        } else {
                            if (totalsLists[ROWS])
                                colSpan = totalsLists[ROWS][row + 1][scanIndexes[ROWS][row + 1]].span;
                            rowContent += '<th class="col" style="text-align: center;" colspan="' + (colSpan == 0 ? 1 : colSpan) + '" title="' + header.value + '">'
                            + (wrapContent ? '<div rel="' + row + ":" + col +'">' + header.value + '</div>' : header.value)
                            + '</th>';
                        }
                        colSpan = 1;
                    } else {
                        colSpan++;
                    }
                }
                if (totalsLists[ROWS])
                    rowContent += genTotalHeaderCells(col - allData.leftOffset + 1, row + 1, scanSums[ROWS], scanIndexes[ROWS], totalsLists[ROWS], wrapContent);
            } // If the cell is a row header and is null (grouped row header)
            else if (header.type === "ROW_HEADER" && header.value === "null") {
                rowContent += '<th class="row_null">&nbsp;</th>';
            } // If the cell is a row header and isn't null (last row header)
            else if (header.type === "ROW_HEADER") {
                if (lowestRowLvl == col)
                    isHeaderLowestLvl = true;
                else
                    nextHeader = data[row][col+1];

                var previousRow = data[row - 1];
                var nextRow = data[row + 1];
                var same = !headerSame && !isHeaderLowestLvl && (col == 0 || !topParentsDiffer(data, row, col)) && header.value === previousRow[col].value;
                headerSame = !same;
                var sameAsPrevValue = false;
                if(Settings.ALLOW_TABLE_DATA_COLLAPSE){
                    if (row > 0 && row < rowLen - 1) {
                        if (totalsLists[ROWS] == null || (col <= colLen - totalsLists[ROWS].length - 1)) {
                            var checkOther = true;
                            if (totalsLists[COLUMNS] && rowShifted >= 0 && col <= isNextTotalsRow(rowShifted + 1, scanSums, scanIndexes, totalsLists, wrapContent)) {
                                sameAsPrevValue = true;
                                checkOther = false;
                            }
                            if (checkOther && nextRow[col].value == header.value) {
                                if (col > 0) {
                                    for (var j = 0; j < col; j++) {
                                        if (nextRow[j].value == data[row][j].value) {
                                            sameAsPrevValue = true;
                                        } else {
                                            sameAsPrevValue = false;
                                            break;
                                        }
                                    }
                                } else {
                                    sameAsPrevValue = true;
                                }
                            }
                        }
                    } else if(row > 0 && row == rowLen - 1) {
                        if (totalsLists[COLUMNS] && rowShifted >= 0 && col <= isNextTotalsRow(rowShifted + 1, scanSums, scanIndexes, totalsLists, wrapContent)) {
                            sameAsPrevValue = true;
                        }
                    }
                }
                var value = (same ? "<div>&nbsp;</div>" : '<div rel="' + row + ":" + col + '">'
                            + (sameAsPrevValue && Settings.ALLOW_TABLE_DATA_COLLAPSE ? '<span class="expander expanded" style="cursor: pointer;">&#9660;</span>' : '' ) + header.value + '</div>');
                if (!wrapContent) {
                    value = (same ? "&nbsp;" : header.value );
                }
                var tipsy = "";
                /* var tipsy = ' original-title="';
                if (!same && header.metaproperties) {
                    for (key in header.metaproperties) {
                        if (key.substring(0,1) != "$" && key.substring(1,2).toUpperCase() != key.substring(1,2)) {
                            tipsy += "<b>" + safe_tags_replace(key) + "</b> : " + safe_tags_replace(header.metaproperties[key]) + "<br>";
                        }
                    }
                }
                tipsy += '"';
                */
                var cssclass = (same ? "row_null" : "row");
                var colspan = 0;

                if (!isHeaderLowestLvl && (typeof nextHeader == "undefined" || nextHeader.value === "null")) {
                    colspan = 1;
                    var group = header.properties.dimension;
                    var level = header.properties.level;
                    var groupWidth = (group in rowGroups ? rowGroups[group].length - rowGroups[group].indexOf(level) : 1);
                    for (var k = col + 1; colspan < groupWidth && k <= (lowestRowLvl+1) && data[row][k] !== "null"; k++) {
                        colspan = k - col;
                    }
                    col = col + colspan -1;
                }
                rowContent += '<th class="' + cssclass + '" ' + (colspan > 0 ? ' colspan="' + colspan + '"' : "") + tipsy + '>' + value + '</th>';
            }
            else if (header.type === "ROW_HEADER_HEADER") {
                var hierName = function(data) {
                    var hier = data.properties.hierarchy;
                    var name = hier.replace(/[\[\]]/gi, '').split('.')[1]
                        ? hier.replace(/[\[\]]/gi, '').split('.')[1]
                        : hier.replace(/[\[\]]/gi, '').split('.')[0];

                    return name;
                };
                var arrPosRowData = [];

                if (_.contains(arrRowData, header.value)) {
                    for (var i = 0; i < arrRowData.length; i++) {
                        if (arrRowData[i] === header.value) {
                            arrPosRowData.push(i);
                        }
                    }

                    arrPosRowData.push(col);
                }

                rowContent += '<th class="row_header">' + (wrapContent ? '<div>' + header.value + '</div>' : header.value) + '</th>';

                arrRowData.push(header.value);
                objRowData.push({
                    name: header.value,
                    hierName: hierName(header) + '/' + header.value
                });

                isHeaderLowestLvl = true;
                processedRowHeader = true;
                lowestRowLvl = col;
                if (header.properties.hasOwnProperty("dimension")) {
                    var group = header.properties.dimension;
                    if (!(group in rowGroups)) {
                        rowGroups[group] = [];
                    }
                    rowGroups[group].push(header.properties.level);
                }

                if (arrPosRowData.length > 0) {
                    var aux = 0;

                    rowContent = '<tr>';

                    if (row === 0) {
                        rowContent = '<thead>' + rowContent;
                    }

                    for (var i = 0; i < objRowData.length; i++) {
                        if (arrPosRowData[aux] === i) {
                            newRowContent += '<th class="row_header">' + (wrapContent ? '<div>' + objRowData[i].hierName + '</div>' : objRowData[i].hierName) + '</th>';
                            aux += 1;
                        }
                        else {
                            newRowContent += '<th class="row_header">' + (wrapContent ? '<div>' + objRowData[i].name + '</div>' : objRowData[i].name) + '</th>';
                        }
                    }

                    rowContent += newRowContent;
                }
            } // If the cell is a normal data cell
            else if (header.type === "DATA_CELL") {
                batchStarted = true;
                var color = "";
                var val = _.isEmpty(header.value) ? Settings.EMPTY_VALUE_CHARACTER : header.value;
                var arrow = "";

                if (header.properties.hasOwnProperty('image')) {
                    var img_height = header.properties.hasOwnProperty('image_height') ? " height='" + header.properties.image_height + "'" : "";
                    var img_width = header.properties.hasOwnProperty('image_width') ? " width='" + header.properties.image_width + "'" : "";
                    val = "<img " + img_height + " " + img_width + " style='padding-left: 5px' src='" + header.properties.image + "' border='0'>";
                }

                // Just apply formatting to non-empty cells
                if (val !== '-' && val !== '' && header.properties.hasOwnProperty('style')) {
                    color = " style='background-color: " + header.properties.style + "' ";
                }
                if (header.properties.hasOwnProperty('link')) {
                    val = "<a target='__blank' href='" + header.properties.link + "'>" + val + "</a>";
                }
                if (header.properties.hasOwnProperty('arrow')) {
                    arrow = "<img height='10' width='10' style='padding-left: 5px' src='./images/arrow-" + header.properties.arrow + ".gif' border='0'>";
                }

                if (val !== '-' && val !== '') {
                    rowWithOnlyEmptyCells = false;
                }

                rowContent += '<td class="data" ' + color + '>'
                        + (wrapContent ? '<div class="datadiv '+ setStyleNegativeNumber(header.properties.raw) + '" alt="' + header.properties.raw + '" rel="' + header.properties.position + '">' : "")
                        + val + arrow
                        + (wrapContent ? '</div>' : '') + '</td>';
                if (totalsLists[ROWS])
                    rowContent += genTotalDataCells(colShifted + 1, rowShifted, scanSums[ROWS], scanIndexes[ROWS], totalsLists, wrapContent);
            }
        }
        rowContent += "</tr>";

        // Change it to let hideEmpty true by default
        if (options.hideEmpty && header.type === "DATA_CELL" && rowWithOnlyEmptyCells) {
            rowContent = '';
        }

        var totals = "";
        if (totalsLists[COLUMNS] && rowShifted >= 0) {
            totals += genTotalHeaderRowCells(rowShifted + 1, scanSums, scanIndexes, totalsLists, wrapContent);
        }
        if (batchStarted && batchSize) {
                if (row <= batchSize) {
                    if (!isColHeader && !isColHeaderDone) {
                        tableContent += "</thead><tbody>";
                        isColHeaderDone = true;
                    }
                    tableContent += rowContent;
                    if (totals.length > 0) {
                        tableContent += totals;
                    }

                } else {
                    resultRows.push(rowContent);
                    if (totals.length > 0) {
                        resultRows.push(totals);
                    }

                }
        } else {
            if (!isColHeader && !isColHeaderDone) {
                tableContent += "</thead><tbody>";
                isColHeaderDone = true;
            }
            tableContent += rowContent;
            if (totals.length > 0) {
                tableContent += totals;
            }
        }
    }
    if (options) {
        options['batchResult'] = resultRows;
        options['hasBatchResult'] = resultRows.length > 0;
    }
    return "<table>" + tableContent + "</tbody></table>";
};

SaikuTableRenderer.prototype.renderSummary = function(data) {
    if (data && data.query) {
        var hasSomethingToRender = false;
        var measures = data.query.queryModel.details
                       ? data.query.queryModel.details.measures
                       : [];
        var summaryData = {};

        for (var i = 0; i < measures.length; i++) {
            var m = measures[i];
            if (m.aggregators) {
                for (var j = 0; j < m.aggregators.length; j++) {
                    var a = m.aggregators[j];
                    if (a.indexOf('_') > 0) {
                        var tokens = a.split('_');
                        var aggregator = tokens[0];
                        var axis = tokens[1];

                        if (aggregator !== 'nil' && aggregator !== 'not') {
                            hasSomethingToRender = true;
                            aggregator = aggregator.capitalizeFirstLetter();
                            if (!(axis in summaryData)) summaryData[axis] = [];
                            summaryData[axis].push(m.name + ": " + aggregator);
                        }
                    }
                }
            }
        }

        if (hasSomethingToRender) {
            var summary = "<div id='totals_summary'><br/>";

            $.each(summaryData, function(key, aggregators) {
                summary += "<h3>" + key.capitalizeFirstLetter();
                for (var i = 0; i < aggregators.length; i++) {
                    summary += "<br/>&nbsp;" + aggregators[i];
                }
                summary += "</h3>";
            });

            return summary + "</div>";
        }
    }

    return "";
};

String.prototype.capitalizeFirstLetter = function() {
    return this.charAt(0).toUpperCase() + this.slice(1).toLowerCase();
}
