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
 *
 */
var FormatAsPercentageModal = Modal.extend({

	type: "filter",
	closeText: "Save",

	generatedMeasures: [],
	selectedMeasure: "",
	selectedDimension: "",
	asPercent: true,

	events: {
		'click .dialog_footer a': 'call',
		'click .formatButton': 'format'

	},

	buttons: [
		{text: "Cancel", method: "close"},
		{text: "Help", method: "help"}
	],

	addMeasureTemplate: _.template(
		"<div class='text'>Name: <input type='text' class='measure_name form-control'></input> </div> " +
		"<div> <span> Select how to express the measures: </span> </div>" +
		"<ol style='list-style-type: none;'> " +
		"<li style='padding-bottom: 10px; padding-right: 10px; float: inherit;'>     " +
		"    <button class='form_button btn btn-primary formatButton' id='formatOverRows'> Format as % of rows </button></li>" +
		"<li style='padding-bottom: 10px; padding-right: 10px; float: inherit;'>     " +
		"    <button class='form_button btn btn-primary formatButton' id='formatOverColumns'> Format as % of columns </button></li>" +
		"<li style='padding-bottom: 10px; padding-right: 10px; float: inherit;'>     " +
		"    <button class='form_button formatButton btn btn-primary' id='formatOverTotal'> Format as % of total </button></li>" +
		"</ol>" +
		"<span id='userFeedback'> <p> <%= userFeedback %> </p> </span>"
	),

	userFeedback: "",
	selectedRows: [],
	selectedColumns: [],

	initialize: function (args) {
		var self = this;
		this.measures = args.measures;
		this.workspace = args.workspace;

		var mdx = this.workspace.query.model.mdx;
		this.selectedRows = this.extractFromMdx(mdx, "~ROWS");
		this.selectedColumns = this.extractFromMdx(mdx, "~COLUMNS");
		this.generatedMeasures = [];

		_.bindAll(this, "save", "format");

		this.options.title = "Format as percentage";

		// fix event listening in IE < 9
		if (isIE && isIE < 9) {
			$(this.el).find('form').on('submit', this.save);
		}

		// Determine feasibility
		this.userFeedback = this.checkRowsOrColumnsPresent(this.selectedColumns, this.selectedRows);

		// Load template
		this.message = this.addMeasureTemplate({userFeedback: this.userFeedback});

		this.$el.find('.dialog_icon')

	},

	save: function (measureExpression, measureName, percentOver) {
		var self = this;
		var measure_name = $(this.el).find('.measure_name').val();
		if (measure_name == null || measure_name == "") {
			measure_name = measureName + ' % of ' + percentOver;
		}
		var measure_formula = measureExpression;
		var measure_format = '0.00%';

		var alert_msg = "";
		if (typeof measure_name == "undefined" || !measure_name) {
			alert_msg += "You have to enter a name for the measure! ";
		}
		if (typeof measure_formula == "undefined" || !measure_formula || measure_formula === "") {
			alert_msg += "You have to enter a MDX formula for the calculated measure! ";
		}
		if (alert_msg !== "") {
			alert(alert_msg);
		} else {
			var m = {name: measure_name, formula: measure_formula, properties: {}, uniqueName: "[Measures]." + measure_name};
			if (measure_format) {
				m.properties.FORMAT_STRING = measure_format;
			}
			self.workspace.query.helper.addCalculatedMeasure(m);
			this.generatedMeasures.push(m);
		}
	},

	format: function (event) {
		event.preventDefault();
		var self = this;
		var action = event.target.id;
		var formatOver = this.determineFormatOver(action);

		for (var m = 0; m < this.measures.length; m++) {
			var measure = this.measures[m].uniqueName;
			try {
				var formattedMeasure = this.formatMeasure(measure, action);
				this.save(formattedMeasure, this.measures[m].caption, formatOver);
			} catch (err) {
				this.setUserFeedback(err);
			}
		}

		this.replaceMeasuresWithFormattedMeasures();
		self.workspace.sync_query();
		self.workspace.toolbar.run_query();
		//this.workspace.query.run();
		this.generatedMeasures = [];
		this.close();
	},

	formatMeasure: function (measure, action) {
		var measureExpression = "";
		if (action == "formatOverRows") {
			if (this.selectedColumns.length == 0) {
				throw "There are no dimensions in the columns to format over";
			}
			else {
				var dimensionsCrossJoined = this.makeCrossJoinExpression(this.selectedColumns);
				measureExpression = measure + " / SUM(CROSSJOIN(" + dimensionsCrossJoined + "," + measure + "))";
			}
		}
		else if (action == "formatOverColumns") {
			if (this.selectedRows.length == 0) {
				throw "There are no dimensions in the rows to format over";
			} else {
				var dimensionsCrossJoined = this.makeCrossJoinExpression(this.selectedRows);
				measureExpression = measure + " / SUM(CROSSJOIN(" + dimensionsCrossJoined + "," + measure + "))";
			}
		}
		else if (action == "formatOverTotal") {
			if (this.selectedRows.length == 0 && this.selectedColumns.length == 0) {
				throw "There are no dimensions in the rows and columns to format over";
			} else {
				var dimensionsCrossJoined = this.makeCrossJoinExpression(this.selectedRows.concat(this.selectedColumns));
				measureExpression = measure + " / SUM(CROSSJOIN(" + dimensionsCrossJoined + "," + measure + "))";
			}
		}

		return measureExpression;
	},

	replaceMeasuresWithFormattedMeasures: function () {
		var queryHelper = this.workspace.query.helper;
		queryHelper.clearMeasures();
		var calculatedMeasures = this.generatedMeasures;
		var calculatedMeasuresNamesAndTypes = [];
		for (var m = 0; m < calculatedMeasures.length; m++) {
			var calculatedMeasure = calculatedMeasures[m];
			calculatedMeasuresNamesAndTypes[m] = {name: calculatedMeasure.name, type: "CALCULATED"};
		}
		queryHelper.setMeasures(calculatedMeasuresNamesAndTypes);
	},

	extractFromMdx: function (mdx, what) {
		// A Saiku generated MDX query always generates the columns and rows as SET's prefixed with '~ROWS' or '~COLUMNS', let's find these
		var extraction = [];
		if (mdx == null || mdx == "") {
			extraction = [];
		}
		else {
			var searchArea = mdx.substring(0, mdx.indexOf("SELECT") - 1);

			var amountOfTimesToBeFound = this.occurrences(searchArea, what, false);
			var previousStart = 0;
			for (var i = 0; i < amountOfTimesToBeFound; i++) {
				var start = searchArea.indexOf(what, previousStart);
				var end = searchArea.indexOf("]", start);
				var dimensionSetName = searchArea.substring(start, end);
				extraction[i] = '[' + dimensionSetName + ']';
				previousStart = start + 1;
			}
		}
		return extraction;
	},

	/** Function count the occurrences of substring in a string;
	 * @param {String} string   Required. The string;
	 * @param {String} subString    Required. The string to search for;
	 * @param {Boolean} allowOverlapping    Optional. Default: false;
	 */
	occurrences: function (string, subString, allowOverlapping) {

		string += "";
		subString += "";
		if (subString.length <= 0) return string.length + 1;

		var n = 0, pos = 0;
		var step = (allowOverlapping) ? (1) : (subString.length);

		while (true) {
			pos = string.indexOf(subString, pos);
			if (pos >= 0) {
				n++;
				pos += step;
			} else break;
		}
		return (n);
	},

	makeCrossJoinExpression: function (dimensions) {
		var crossJoinExpression = dimensions[0];

		for (var i = 1; i < dimensions.length; i++) {
			crossJoinExpression += ' * ';
			crossJoinExpression += dimensions[i];
		}

		return crossJoinExpression;
	},

	determineFormatOver: function (action) {
		var formatOver = "";
		if (action.indexOf("Rows") != -1) {
			formatOver = "rows";
		} else if (action.indexOf("Columns") != -1) {
			formatOver = "columns";
		} else {
			formatOver = "total";
		}

		return formatOver;
	},

	setUserFeedback: function (msg) {
		$("#userFeedback").html("<p class='editor_info'>" + msg + "</p>");
	},

	checkRowsOrColumnsPresent: function (rows, cols) {
		if ((rows == null || rows == undefined || rows.length == 0) && (cols == null || cols == undefined || cols.length == 0)) {
			return "You selected no columns or rows, you should probably return.";
		} else return "";
	},
	help: function(){
		window.location("http://saiku-documentation.readthedocs.io/en/latest/totals_and_subtotals.htmlTou");
	}


});
