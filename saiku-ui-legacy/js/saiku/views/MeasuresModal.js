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
 * The "add a folder" dialog
 */
var MeasuresModal = Modal.extend({

    type: "filter",
    closeText: "Save",

    mdxFunctions: ['distinct', 'count distinct'],

    events: {
        'submit form': 'save',
        'click .dialog_footer a': 'call',
        'change #Measures': 'addMeasureToCalculationField',
        'click .form_button.mathBtn': 'addMathOperatorToCalculationField',
        'click .form_button.growthBtn': 'openGrowthModal',
        'click .form_button.formatBtn': 'openFormatModal'

    },

    buttons: [
        { text: "OK", method: "save" },
        { text: "Cancel", method: "close" }
    ],

    addMeasureTemplate: _.template("<form id='measure_form'>" +

        "<table border='0px'>" +
        "<tr><td class='col0 i18n'>Name:</td>" +
        "<td class='col1'><input type='text' class='measure_name' value='Measure Name'></input></td></tr>" +
            "<tr><td class='col0 i18n'>Measure:</td>" +
            "<td class='col1'>" +
            "<select id='Measures' name='MeasuresId'> " +
            "    <option value='' selected='selected'>--select an existing measure--</option> " +
            "    <% _(measures).each(function(m) { %> " +
            "      <option value='<%= m.uniqueName %>'><%= m.name %></option> " +
            "    <% }); %> " +
            "</select> " +
            "</td></tr>" +

            "<tr><td class='col0 i18n'>Formula:</td>" +
            "<td class='col1'><textarea class='measureFormula auto-hint' placeholder='Start writing a calculated measure or use the dropdown list'></textarea></td></tr>" +

            "<tr> <td class='col0'> </td>" +
            "<td class='col1'>" +
            " <form> <input type='button' class='form_button mathBtn' style='padding-bottom: 18px;' value='+' id='plusBtn' >  </input>   " +
            " <input type='button' class='form_button mathBtn' style='padding-bottom: 18px;' value='-' id='minusBtn' > </input>  " +
            " <input type='button' class='form_button mathBtn' style='padding-bottom: 18px;' value='*' id='multiplyBtn' >  </input>  " +
            " <input type='button' class='form_button mathBtn' style='padding-bottom: 18px;' value='/' id='divisionBtn' >  </input> " +
            " <input type='button' class='form_button mathBtn' style='padding-bottom: 18px;' value='(' id='leftBracketBtn' >  </input> " +
            " <input type='button' class='form_button mathBtn' style='padding-bottom: 18px;' value=')' id='rightBracketBtn' >  </input> " +

            "</form> </td>" +
            "</tr>" +

//            "<tr><td class='col0 i18n'>Function:</td>" +
//            "<td class='col1'>" +
//            "<select id='mdxFunction' name='mdxFunctionId'> " +
//            "    <option value='' selected='selected'>--select a MDX function--</option> " +
//            "    <% _(mdxFunctions).each(function(mdxFunction) { %> " +
//            "      <option value='<%= mdxFunction %>'><%= mdxFunction %></option> " +
//            "    <% }); %> " +
//            "</select> " +
//            "</td></tr>" +

            "<tr><td class='col0 i18n'>Format:</td>" +
            "<td class='col1'><input class='measure_format' type='text' value='#,##0.00'></input></td></tr>" +
            "</table></form>"
    ),


    measure: null,

    initialize: function (args) {
        var self = this;
        this.workspace = args.workspace;
        this.measure = args.measure;

        var selectedHierarchies = this.workspace.query.helper.model().queryModel.axes.ROWS.hierarchies.concat(this.workspace.query.helper.model().queryModel.axes.COLUMNS.hierarchies);
        this.selectedDimensions = this.extractDimensionChoices(selectedHierarchies);
        this.selectedRowDimensions = this.extractDimensionChoices(this.workspace.query.helper.model().queryModel.axes.ROWS.hierarchies);
        this.selectedColumnDimensions = this.extractDimensionChoices(this.workspace.query.helper.model().queryModel.axes.COLUMNS.hierarchies);
        this.selectedMeasures = this.workspace.query.helper.model().queryModel.details.measures;

        var cube = this.workspace.selected_cube;
        this.measures = Saiku.session.sessionworkspace.cube[cube].get('data').measures;

        _.bindAll(this, "save", "openGrowthModal", "openFormatModal");


        this.options.title = "Calculated Measure";

        if (this.measure) {
            _.extend(this.options, {
                title: "Custom Filter for " + this.axis
            });
        }

        this.bind('open', function () {
            if (self.measure) {
            }

        });


        // fix event listening in IE < 9
        if (isIE && isIE < 9) {
            $(this.el).find('form').on('submit', this.save);
        }
        ;

        // Load template
        this.message = this.addMeasureTemplate({
            measures: this.measures,
            mdxFunctions: this.mdxFunctions
        });

    },


    save: function (event) {
        event.preventDefault();
        var self = this;
        var measure_name = $(this.el).find('.measure_name').val();
        var measure_formula = $(this.el).find('.measureFormula').val();
        var measure_format = $(this.el).find('.measure_format').val();


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
            var m = { name: measure_name, formula: measure_formula, properties: {}, uniqueName: "[Measures]." + measure_name };
            if (measure_format) {
                m.properties.FORMAT_STRING = measure_format;
            }
            self.workspace.query.helper.addCalculatedMeasure(m);
            self.workspace.sync_query();
            this.close();
        }

        return false;
    },

    error: function () {
        $(this.el).find('dialog_body')
            .html("Could not add new folder");
    },

    addMathOperatorToCalculationField: function (event) {
        var mathOperator = ' ' + event.target.value + ' ';
        $(".measureFormula").val($(".measureFormula").val() + mathOperator);
    },

    addMeasureToCalculationField: function (event) {
        var measureName = this.$("#Measures option:selected").text();
        measureName = this.surroundWithSquareBrackets("Measures") + '.' + this.surroundWithSquareBrackets(measureName);
        $(".measureFormula").val($(".measureFormula").val() + measureName);
        this.resetSelectDropdown();
    },

    surroundWithSquareBrackets: function (text) {
        return '[' + text + ']';
    },

    resetSelectDropdown: function () {
        document.getElementById("Measures").selectedIndex = 0;

    },

    extractDimensionChoices: function (hierarchies) {
        dimensionNames = [];
        _.each(hierarchies, function (hierarchy) {
            dimensionNames.push(hierarchy.name)
        }, this);
        return dimensionNames;
    },

    openGrowthModal: function (event) {
        this.close();
        (new GrowthModal({
            workspace: this.workspace,
            measures: this.measures,
            dimensions: this.selectedDimensions,
            workspace: this.workspace
        })).render().open();
    },

    openFormatModal: function (event) {
        this.close();
        (new FormatAsPercentageModal({
            workspace: this.workspace,
            measures: this.selectedMeasures,
            workspace: this.workspace
        })).render().open();
    }
});
