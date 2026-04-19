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
 * - models/SaikuOlapQuery.js
 * - views/DimensionList.js
 * - views/Workspace.js
 * - views/WorkspaceDropZone.js
 * - views/WorkspaceToolbar.js
 * - css/saiku/src/saiku.dropzone.css
 * - css/saiku/src/styles.css
 * - index.jsp
 */

/**
 * Class for calculated measure/member
 *
 * @class CalculatedMemberModal
 */
var CalculatedMemberModal = Modal.extend({

    /**
     * Type name
     *
     * @property type
     * @type {String}
     * @private
     */
    type: 'calculated-member',

    /**
     * Property with main template of modal
     *
     * @property template_modal
     * @type {String}
     * @private
     */
    template_modal: _.template(
        '<div class="cms-container-group">' +
            '<div class="calculated-measure-group">' +
                '<h4 class="i18n">Calculated Measures:</h4>' +
                '<div class="cms-box">' +
                    '<table class="cms-list measures-list">' +
                        '<%= tplCalculatedMeasures %>' +
                    '</table>' +
                '</div>' +
            '</div>' +
            '<div class="calculated-member-group">' +
                '<h4 class="i18n">Calculated Members:</h4>' +
                '<div class="cms-box">' +
                    '<table class="cms-list members-list">' +
                        '<%= tplCalculatedMembers %>' +
                    '</table>' +
                '</div>' +
            '</div>' +
        '</div>' +
        '<div class="cms-container-form">' +
            '<form class="form-group-inline" data-action="cad">' +
                '<div class="form-group"><label for="cms-name" class="i18n">Name:</label>' +
                '<input type="text" class="form-control" id="cms-name" autofocus></div>' +
                '<div class="cms-measure form-inline" style="padding-bottom:10px;">' +
                '<label for="cms-measure" class="i18n">Insert Member:</label>' +
                ' <input type="button" class="form-control btn-primary btn btn-select-member"' +
                ' value="Select Member" title="Insert a member into the formula editor "   ' +
                'id="insertmember"> </input> </div>' +
                '<label for="<%= idEditor %>" class="i18n">Formula:</label>' +
                '<div class="formula-editor" style="padding-bottom:10px" id="<%= idEditor %>"></div>' +
                '<div class="btn-groups">' +

                    '<a class="form_button btn btn-default minimal_padding btn-math" href="#add_math_operator_formula"' +
                            ' data-math="+">&nbsp;+&nbsp;</a>' +
                    '<a class="form_button btn btn-default btn-math minimal_padding" href="#add_math_operator_formula"' +
        ' data-math="-">&nbsp;-&nbsp;</a>' +
                    '<a class="form_button btn btn-default btn-math minimal_padding" href="#add_math_operator_formula"' +
        ' data-math="*">&nbsp;*&nbsp;</a>' +
                    '<a class="form_button btn btn-default btn-math minimal_padding" href="#add_math_operator_formula"' +
        ' data-math="/">&nbsp;/&nbsp;</a>' +
                    '<a class="form_button btn btn-default btn-math minimal_padding" href="#add_math_operator_formula"' +
        ' data-math="(">&nbsp;(&nbsp;</a>' +
                    '<a class="form_button btn btn-default btn-math minimal_padding" href="#add_math_operator_formula"' +
        ' data-math=")">&nbsp;)&nbsp;</a>' +
                    '<a class="form_button btn btn-default btn-math minimal_padding i18n" href="#add_math_operator_formula"' +
        ' data-math="and">&nbsp;and&nbsp;</a>' +
                    '<a class="form_button btn btn-default btn-math minimal_padding i18n"' +
        ' href="#add_math_operator_formula" data-math="or">&nbsp;or&nbsp;</a>' +
                    '<a class="form_button btn btn-default btn-math minimal_padding i18n" href="#add_math_operator_formula"' +
                    ' data-math="not">&nbsp;not&nbsp;</a><br/>' +
                    '<div class="form-inline"><select class="cms-functionlist form-control"><option' +
                    ' value="">---Insert MDX Function---' +
                    '</select>&nbsp; <a href="" class="cms-doclink" target="_blank" style="display:' +
                    ' none;">Documentation</a><br/></div>'+
                    '</div>' +
				    '<div class="cms-function">' +
					'<label for="cms-function" class="i18n">Functions:</label>' +
					' <input type="button" class="form_button btn btn-primary growthBtn"#'+
                    ' value="Predefined Calculations"  ' +
					'         title="Calculate difference. Good to calculate previous period growth "   id="growthBtn" >  </input> ' +
					' <input type="button" class="form_button btn btn-primary formatBtn"' +
                    ' value="Format %" id="formatBtn"  ' +
					'title="Post-process step: format this view as percentage of rows, columns or grand total. " />' +
				'</div><br/>' +
                '<div style="padding-bottom:10px;"><label for="cms-dimension" class="i18n">Dimension:</label>' +
                '<select id="cms-dimension" class="form-control" style="width:365px">' +
                    '<option class="i18n" value="" selected>-- Select an existing dimension --</option>' +
                    '<% if (measures.length > 0) { %>' +
                        '<optgroup label="<%= dataMeasures.name %>">' +
                            '<option value="<%= dataMeasures.uniqueName %>" data-type="calcmeasure"><%= dataMeasures.name %></option>' +
                        '</optgroup>' +
                    '<% } %>' +
                    '<% _(dimensions).each(function(dimension) { %>' +
                        '<optgroup label="<%= dimension.name %>">' +
                            '<% _(dimension.hierarchies).each(function(hierarchy) { %>' +
                                '<option value="<%= hierarchy.uniqueName %>" data-dimension="<%= dimension.name %>" data-type="calcmember"><%= hierarchy.name %></option>' +
                            '<% }); %>' +
                        '</optgroup>' +
                    '<% }); %>' +
                '</select></div>' +
                '<% if (Settings.BIPLUGIN5 == false) { %>'+
                '<div class="btn-groups" style="padding-bottom:10px">' +
                    '<a class="form_button btn btn-primary btn-parent-member" href="#add_math_operator_formula"' +
                    ' disabled>&nbsp;Parent Member Selector&nbsp;</a>' +
                    '<a class="form_button btn btn-default btn-clear-parent" href="#add_math_operator_formula"' +
                    ' disabled>&nbsp;Clear Parent Member&nbsp;</a>' +
                '</div>' +
                '<label class="i18n" for="cms-pmember">Parent Member:</label><input' +
                ' class="form-control" readonly="true" type="text"' +
                ' id="cms-pmember"><br/>'+
                '<% } %>'+
                '<div style="padding-bottom:10px;"><label for="cms-format" class="i18n">Format:</label>' +
                '<select id="cms-format" class="form-control" style="width:365px">' +
                    '<option class="i18n" value="" selected>-- Select a format --</option>' +
                    '<option class="i18n" value="custom">Custom</option>' +
                    '<option class="i18n" value="#,##0.00">#,##0.00 Decimal</option>' +
                    '<option class="i18n" value="#,###">#,### Integer</option>' +
                    '<option class="i18n" value="##.##%">##.##% Decimal percentage</option>' +
                    '<option class="i18n" value="##%">##% Integer percentage</option>' +
                    '<option class="i18n" value="mmmm dd yyyy">mmmm dd yyyy Month Day Year</option>' +
                    '<option class="i18n" value="mmmm yyyy">mmmm yyyy Month Year</option>' +
                    '<option class="i18n" value="yyyy-mm-dd">yyyy-mm-dd ISO format date</option>' +
                    '<option class="i18n" value="yyyy-mm-dd hh:mi:ss">yyyy-mm-dd hh:mi:ss Date and time</option>' +
                    '<option class="i18n" value="##h ##m">##h ##m Minutes</option>' +
                '</select></div>' +
                '<div class="div-format-custom" style="padding-bottom:10px">' +
                    '<label for="cms-format-custom" class="i18n">Format Custom:</label>' +
                    '<input type="text" class="form-control" id="cms-format-custom" value="" placeholder="Add a' +
                    ' custom format">' +
                '</div>' +
            '</form>' +
        '</div>'
    ),

    /**
     * Events of buttons
     *
     * @property buttons
     * @type {Array}
     * @private
     */
    buttons: [
        { text: 'Add', method: 'save' },
        { text: 'Update', method: 'save' },
        { text: 'New', method: 'new' },
        { text: 'Cancel', method: 'close' },
        { text: 'Help', method: 'help'}
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
        'click  .dialog_footer a'       : 'call',
        'blur   #cms-name'              : 'trigger_input_name',
        'change #cms-measure'           : 'add_measure_formula',
        'click  .btn-math'              : 'add_math_operator_formula',
        'change #cms-dimension'         : 'type_dimension',
        'change #cms-format'            : 'type_format',
        'click  .btn-action-edit'       : 'edit_cms',
        'click  .btn-action-del'        : 'show_del_cms',
		'click  .form_button.growthBtn' : 'openGrowthModal',
        'click  .form_button.formatBtn' : 'openFormatModal',
		'click  .btn-parent-member'     : 'open_parent_member_selector',
        'click  .btn-clear-parent'      : 'reset_parent_member',
        'click .cms-functionlist'       : 'change_function_list',
        'click .btn-select-member'      : 'open_select_member_selector'
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
        this.workspace = args.workspace;
        this.options.title = 'Calculated Member';
        this.id = _.uniqueId('cms-formula-');

        var self = this;
        var cube = this.workspace.selected_cube;
        var measures = Saiku.session.sessionworkspace.cube[cube].get('data').measures;
        var dimensions = Saiku.session.sessionworkspace.cube[cube].get('data').dimensions;
        var calculatedMeasures = this.workspace.query.helper.getCalculatedMeasures();
        var calculatedMembers = this.workspace.query.helper.getCalculatedMembers();
        var $tplCalculatedMeasures = this.template_cms(calculatedMeasures, 'calcmeasure');
        var $tplCalculatedMembers = this.template_cms(calculatedMembers, 'calcmember');
        var dataMeasures = {
            name: measures ? measures[0].dimensionUniqueName.replace(/[\[\]]/gi, '') : null,
            uniqueName: measures ? measures[0].hierarchyUniqueName : null
        };

        Saiku.ui.block('<span class="i18n">Loading...</span>');

        // Load template
        this.message = this.template_modal({
            tplCalculatedMeasures: $tplCalculatedMeasures,
            tplCalculatedMembers: $tplCalculatedMembers,
            idEditor: this.id,
            measures: measures,
            dataMeasures: dataMeasures,
            dimensions: dimensions
        });

        this.bind('open', function() {
            this.populate_function_list();
            var calcHeight = this.$el.find('.cms-container-form').height();
            this.post_render();
            this.$el.find('.dialog_footer a:nth-child(2)').hide();
            this.$el.find('.dialog_footer a:nth-child(3)').hide();
            this.$el.find('.cms-container-group').height(calcHeight);
            this.$el.find('.calculated-measure-group').height(calcHeight / 2);
            this.$el.find('.calculated-member-group').height(calcHeight / 2);
            _.defer(function() {
                self.start_editor();
            });
        });

        // Listen to result event
        Saiku.session.bind('ParentMemberSelectorModal:save', this.add_selected_dimension);
    },

    add_selected_dimension: function(args) {
        console.log(args);
        args.dialog.$el.find('#cms-dimension').val(args.selectedDimension);
    },

    /**
     * Centralize dialog in screen
     *
     * @method post_render
     * @public
     */
    post_render: function() {
        var tPerc = (((($('body').height() - 570) / 2) * 100) / $('body').height());
        var lPerc = (((($('body').width() - 800) / 2) * 100) / $('body').width());

        this.$el.dialog('option', 'position', 'center');
        this.$el.parents('.ui-dialog').css({
            width: '800px',
            top: tPerc + '%',
            left: lPerc + '%'
        });
    },

    /**
     * Start editor ace.js
     *
     * @method start_editor
     * @public
     */
    start_editor: function() {
        this.formulaEditor = ace.edit(this.id);
        this.formulaEditor.getSession().setMode('ace/mode/text');
        this.formulaEditor.getSession().setUseWrapMode(true);
        Saiku.ui.unblock();
    },

    /**
     * Template for add calculated measure/member
     *
     * @method template_cms
     * @private
     * @param  {Object} data Data calculated measures/members
     * @param  {String} type Type calcmeasure or calcmember
     * @return {String}      Template HTML
     */
    template_cms: function(data, type) {
        var self = this;
        var $tpl = '';

        if (data && data.length !== 0) {
            if (type === 'calcmeasure') {
                _.each(data, function(value) {
                    $tpl +=
                        '<tr class="row-cms-' + self.replace_cms(value.name) + '">' +
                            '<td class="cms-name">' + value.name + '</td>' +
                            '<td class="cms-actions">' +
                                '<a class="edit button sprite btn-action-edit" href="#edit_cms" data-name="' + value.name + '" data-type="calcmeasure"></a>' +
                                '<a class="delete button sprite btn-action-del" href="#show_del_cms" data-name="' + value.name + '" data-type="calcmeasure"></a>' +
                            '</td>' +
                        '</tr>';
                });
            }
            else {
                _.each(data, function(value) {
                    if(value.name.indexOf("*TOTAL_MEMBER_SEL~SUM")==-1) {
                        $tpl +=
                            '<tr class="row-cms-' + self.replace_cms(value.name) + '">' +
                            '<td class="cms-name">' + value.name + '</td>' +
                            '<td class="cms-actions">' +
                            '<a class="edit button sprite btn-action-edit" href="#edit_cms" data-name="' + value.name + '" data-type="calcmember"></a>' +
                            '<a class="delete button sprite btn-action-del" href="#show_del_cms" data-name="' + value.name + '" data-type="calcmember"></a>' +
                            '</td>' +
                            '</tr>';
                    }
                });
            }
        }
        else {
            if (type === 'calcmeasure') {
                $tpl = '<p class="msg-no-cms i18n">No calculated measures created</p>';
            }
            else {
                $tpl = '<p class="msg-no-cms i18n">No calculated members created</p>';
            }
        }

        return $tpl;
    },

    /**
     * Replace a measure/member name and add a caractere "-"
     *
     * @method replace_cms
     * @private
     * @param  {String} name Measure/Member name
     * @return {String}      Measure/Member name
     * @example
     *     this.replace_cms('My Member 1');
     *     Output: My-Member-1
     */
    replace_cms: function(name) {
        name = name.replace(/\s/g, '-');
        return name;
    },

    /**
     * Edit calculated measure/member
     *
     * @method edit_cms
     * @private
     * @param {Object} event The Event interface represents any event of the DOM
     */
    edit_cms: function(event) {
        event.preventDefault();
        var self = this;
        var $currentTarget = $(event.currentTarget);
        var cms = $currentTarget.data('type') === 'calcmeasure'
            ? this.workspace.query.helper.getCalculatedMeasures()
            : this.workspace.query.helper.getCalculatedMembers();

        this.$el.find('.cms-actions a').removeClass('on');

        _.each(cms, function(value) {
            if (value.name === $currentTarget.data('name')) {
                $currentTarget.addClass('on');
                self.$el.find('#cms-name').val(value.name);
                self.formulaEditor.setValue(value.formula);
                self.$el.find('#cms-dimension').val(value.hierarchyName);
                self.$el.find('#cms-pmember').val(value.parentMember)
                if ((0 !== $('#cms-format option[value="' + value.properties.FORMAT_STRING + '"]').length) ||
                    (value.properties.FORMAT_STRING === undefined && !(0 !== $('#cms-format option[value="' + value.properties.FORMAT_STRING + '"]').length))) {
                    self.$el.find('#cms-format').val(value.properties.FORMAT_STRING);
                    self.$el.find('.div-format-custom').hide();
                }
                else {
                    self.$el.find('#cms-format').prop('selectedIndex', 1);
                    self.$el.find('.div-format-custom').show();
                    self.$el.find('#cms-format-custom').val(value.properties.FORMAT_STRING);
                }

                self.pmUniqueName = value.parentMember || '';
                self.pmLevel = value.parentMemberLevel || '';
                self.lastLevel = value.previousLevel || '';
                self.pmBreadcrumbs = value.parentMemberBreadcrumbs || [];
                self.actualLevel = value.assignedLevel || '';
                self.type_dimension();

                self.$el.find('.form-group-inline').data('action', 'edit');
                self.$el.find('.form-group-inline').data('oldcms', value.name);
            }
        });
        this.$el.find('.dialog_footer a:nth-child(1)').hide();
        this.$el.find('.dialog_footer a:nth-child(2)').show();
        this.$el.find('.dialog_footer a:nth-child(3)').show();
    },

    /**
     * show dialog to delete calculated measure/member
     *
     * @method show_del_cms
     * @private
     * @param {Object} event The Event interface represents any event of the DOM
     */
    show_del_cms: function(event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var cmsType = $currentTarget.data('type') === 'calcmeasure' ? '<span class="i18n">measure</span>' : '<span class="i18n">member</span>';
        this.$delcms = $currentTarget;
        this.new();
        (new WarningModal({
            title: '<span class="i18n">Delete Member</span>',
            message: '<span class="i18n">You want to delete this</span> ' + cmsType + ' <b>' + $currentTarget.data('name') + '</b>?',
            okay: this.del_cms,
            okayobj: this
        })).render().open();
        this.$el.parents('.ui-dialog').find('.ui-dialog-title').text('Calculated Member');
        Saiku.i18n.translate();
    },

    /**
     * Delete calculated measure/member
     *
     * @method del_cms
     * @private
     * @param  {Object} args Object `this` of class CalculatedMemberModal
     */
    del_cms: function(args) {
        args.$delcms.parent().closest('.row-cms-' + args.replace_cms(args.$delcms.data('name'))).remove();

        if (args.$delcms.data('type') === 'calcmeasure') {
            args.workspace.query.helper.removeCalculatedMeasure(args.$delcms.data('name'));
        }
        else {
            args.workspace.query.helper.removeCalculatedMember(args.$delcms.data('name'));
        }

        args.workspace.sync_query();
        args.workspace.drop_zones.set_measures();
        args.new();
        if (!args.check_len_cms(args.$delcms.data('type'))) {
            if (args.$delcms.data('type') === 'calcmeasure') {
                args.$el.find('.measures-list').append('<p class="msg-no-cms i18n">No calculated measures created</p>');
            }
            else {
                args.$el.find('.members-list').append('<p class="msg-no-cms i18n">No calculated members created</p>');
            }
        }

        Saiku.i18n.translate();
    },

    /**
     * Trigger to verify if value of input name exists in calc measures or members
     *
     * @method trigger_input_name
     * @private
     */
    trigger_input_name: function() {
        var formAction = this.$el.find('.form-group-inline').data('action');
        var name = this.$el.find('#cms-name').val();
        var dimensionDataType = this.$el.find('#cms-dimension option:selected').data('type');
        var alertMsg = '';

        if (dimensionDataType === 'calcmeasure') {
            if (this.check_name_cms(dimensionDataType, name) && formAction === 'cad') {
                alertMsg = 'Exists a measure with the same name added!';
                // this.$el.find('#cms-name').focus();
            }
        }
        else if (dimensionDataType === 'calcmember') {
            if (this.check_name_cms(dimensionDataType, name) && formAction === 'cad') {
                alertMsg = 'Exists a member with the same name added!';
                // this.$el.find('#cms-name').focus();
            }
        }
        else {
            if (this.check_name_cms(dimensionDataType, name) && formAction === 'cad') {
                alertMsg = 'Exists a measure or member with the same name added!';
                // this.$el.find('#cms-name').focus();
            }
        }

        if (alertMsg !== '') {
            alert(alertMsg);
        }
    },

    /**
     * Check if calculated measure/member exists
     *
     * @method check_name_cms
     * @private
     * @param  {String} type type Type calcmeasure or calcmember
     * @param  {String} name name Measure/Member name
     * @return {Boolean}     True/False if calculated measure/member exists
     */
    check_name_cms: function(type, name) {
        var cms = type === 'calcmeasure'
            ? this.workspace.query.helper.getCalculatedMeasures()
            : this.workspace.query.helper.getCalculatedMembers();

        if (type === null || type === undefined) {
            var measures = this.workspace.query.helper.getCalculatedMeasures();
            var members = this.workspace.query.helper.getCalculatedMembers();
            cms = [];
            cms = cms.concat(measures, members);
        }

        for (var i = 0; i < cms.length; i++) {
            if (cms[i].name === name) {
                return true;
            }
            else {
                return false;
            }
        }
    },

    /**
     * Check if calculated measure/member length is > 0
     *
     * @method check_len_cms
     * @private
     * @param  {String}  type Type calcmeasure or calcmember
     * @return {Boolean} True/False if calculated measure/member length is > 0
     */
    check_len_cms: function(type) {
        var cms = type === 'calcmeasure'
            ? this.workspace.query.helper.getCalculatedMeasures()
            : this.workspace.query.helper.getCalculatedMembers();
        if (cms.length > 0) {
            return true;
        }
        else {
            return false;
        }
    },

    /**
     * Reset form
     *
     * @method reset_form
     * @private
     */
    reset_form: function() {
        this.$el.find('#cms-name').val('');
        this.$el.find('#cms-measure').prop('selectedIndex', 0);
        this.formulaEditor.setValue('');
        this.$el.find('#cms-dimension').prop('selectedIndex', 0);
        this.$el.find('#cms-format').prop('selectedIndex', 0);
        this.$el.find('.div-format-custom').hide();
        this.$el.find('#cms-format-custom').val('');
        this.reset_parent_member();
        this.type_dimension();
    },

    /**
     * Reset dropdown "Measure"
     *
     * @method reset_dropdown
     * @private
     */
    reset_dropdown: function() {
        this.$el.find('#cms-measure').prop('selectedIndex', 0);
    },

    /**
     * Reset variables of parent member
     *
     * @method reset_parent_member
     * @private
     */
    reset_parent_member: function() {
        this.pmUniqueName = '';
        this.pmLevel = '';
        this.pmBreadcrumbs = [];
        this.$el.find('#cms-pmember').val("");

    },

    /**
     * Add measure in formula
     *
     * @method add_measure_formula
     * @private
     * @param {Object} event The Event interface represents any event of the DOM
     */
    add_measure_formula: function(event) {
        event.preventDefault();
        var measureName = this.$el.find('#cms-measure option:selected').val();
        var formula = this.formulaEditor.getValue();
        formula = formula + measureName;
        this.formulaEditor.setValue(formula);
        this.reset_dropdown();
    },

    /**
     * Add math operator in formula
     *
     * @method add_math_operator_formula
     * @private
     * @param {Object} event The Event interface represents any event of the DOM
     */
    add_math_operator_formula: function(event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var formula = ' ' + $currentTarget.data('math') + ' ';
        var i = this.$el.find(".formula-editor").attr('id');
        var editor = ace.edit(i);
        editor.insert(formula);
    },

    /**
     * Type dimension - Measure/Member
     *
     * @method type_dimension
     * @private
     * @param {Object} event The Event interface represents any event of the DOM
     */
    type_dimension: function(event) {
        var dimensionDataType = this.$el.find('#cms-dimension option:selected').data('type');

        if (event) {
            event.preventDefault();
            this.reset_parent_member();
        }

        if (dimensionDataType === 'calcmember') {
            this.$el.find('.btn-parent-member').removeAttr('disabled');
            this.$el.find('.btn-clear-parent-member').removeAttr('disabled');
        }
        else {
            this.$el.find('.btn-parent-member').attr('disabled', 'disabled');
            this.$el.find('.btn-clear-parent-member').attr('disabled', 'disabled');
        }
    },

    /**
     * Type format - Decimal, Integer, Custom etc
     *
     * @method type_format
     * @private
     * @param {Object} event The Event interface represents any event of the DOM
     */
    type_format: function(event) {
        event.preventDefault();
        var format = this.$el.find('#cms-format option:selected').val();
        if (format === 'custom') {
            this.$el.find('.div-format-custom').show();
        }
        else {
            this.$el.find('.div-format-custom').hide();
        }
    },

    /**
     * New calculated measure/member
     *
     * @method new
     * @private
     * @param {Object} event The Event interface represents any event of the DOM
     */
    new: function(event) {
        if (event) { event.preventDefault(); }
        this.$el.find('.cms-actions a').removeClass('on');
        this.$el.find('.form-group-inline').data('action', 'cad');
        this.$el.find('.form-group-inline').data('oldcms', '');
        this.$el.find('.dialog_footer a:nth-child(1)').show();
        this.$el.find('.dialog_footer a:nth-child(2)').hide();
        this.$el.find('.dialog_footer a:nth-child(3)').hide();
        this.reset_form();
    },

    openGrowthModal: function (event) {
    	var selectedHierarchies = this.workspace.query.helper.model().queryModel.axes.ROWS.hierarchies.concat(this.workspace.query.helper.model().queryModel.axes.COLUMNS.hierarchies);

    	function extractDimensionChoices(hierarchies) {
    		var dimensionNames = [];
    		_.each(hierarchies, function (hierarchy) {
    			dimensionNames.push(hierarchy.name)
    		}, this);
    		return dimensionNames;
    	}

    	var selectedDimensions = extractDimensionChoices(selectedHierarchies);
    	var cube = this.workspace.selected_cube;
    	var measures = Saiku.session.sessionworkspace.cube[cube].get('data').measures;

    	this.close();
    	(new GrowthModal({
    		workspace: this.workspace,
    		measures: measures,
    		dimensions: selectedDimensions
    	})).render().open();
    },

    openFormatModal: function (event) {
    	var selectedMeasures = this.workspace.query.helper.model().queryModel.details.measures;
    	this.close();
    	(new FormatAsPercentageModal({
    		workspace: this.workspace,
    		measures: selectedMeasures
    	})).render().open();
    },

    /**
     * Show dialog for get a parent member
     *
     * @method open_parent_member_selector
     * @private
     * @param {Object} event The Event interface represents any event of the DOM
     */
    open_parent_member_selector: function(event) {
        event.preventDefault();

        // var formAction = this.$el.find('.form-group-inline').data('action');
        var dimension = {
            val: this.$el.find('#cms-dimension option:selected').val(),
            txt: this.$el.find('#cms-dimension option:selected').text(),
            dataDimension: this.$el.find('#cms-dimension option:selected').data('dimension'),
            dataType: this.$el.find('#cms-dimension option:selected').data('type')
        };

        if (dimension.dataType === 'calcmember') {
            (new ParentMemberSelectorModal({
                dialog: this,
                workspace: this.workspace,
                cube: this.workspace.selected_cube,
                dimensions: Saiku.session.sessionworkspace.cube[this.workspace.selected_cube].get('data').dimensions,
                selectDimension: dimension.val,
                dimension: dimension.dataDimension,
                hierarchy: dimension.txt,
                uniqueName: this.pmUniqueName,
                lastLevel: this.lastLevel,
                current_level: this.pmLevel,
                breadcrumbs: this.pmBreadcrumbs
            })).render().open();

            this.$el.parents('.ui-dialog').find('.ui-dialog-title').text('Connection Details');
        }
    },

    /**
     * Save calculated member
     *
     * @method save
     * @private
     * @param {Object} event The Event interface represents any event of the DOM
     */
    save: function(event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var nameOld = this.$el.find('.form-group-inline').data('oldcms');
        var name = this.$el.find('#cms-name').val();
        var formula = this.formulaEditor.getValue();
        var dimension = {
            val: this.$el.find('#cms-dimension option:selected').val(),
            txt: this.$el.find('#cms-dimension option:selected').text(),
            dataDimension: this.$el.find('#cms-dimension option:selected').data('dimension'),
            dataType: this.$el.find('#cms-dimension option:selected').data('type')
        };
        var format = this.$el.find('#cms-format option:selected').val();
        var formAction = this.$el.find('.form-group-inline').data('action');
        var alertMsg = '';
        var objMember;

        if (format === 'custom') {
            format = this.$el.find('#cms-format-custom').val();
        }
        else {
            format = this.$el.find('#cms-format option:selected').val();
        }

        if (typeof name === 'undefined' || name === '' || !name) {
            alertMsg += 'You have to enter a name for the member! ';
        }
        if (typeof formula === 'undefined' || formula === '' || !formula) {
            alertMsg += 'You have to enter a MDX formula for the calculated member! ';
        }
        if (typeof dimension.val === 'undefined' || dimension.val === '' || !dimension.val) {
            alertMsg += 'You have to choose a dimension for the calculated member! ';
        }
        if (alertMsg !== '') {
            alert(alertMsg);
        }
        else {
            if (dimension.dataType === 'calcmeasure') {
                objMember = {
                    name: name,
                    formula: formula,
                    properties: {},
                    uniqueName: name,
                    hierarchyName: dimension.val
                };

                if (format) {
                    objMember.properties.FORMAT_STRING = format;
                }

                if (formAction === 'cad') {
                    this.workspace.query.helper.addCalculatedMeasure(objMember);
                    this.workspace.sync_query();
                }
                else {
                    this.workspace.query.helper.editCalculatedMeasure(nameOld, objMember);
                    this.workspace.sync_query();
                    this.workspace.drop_zones.set_measures();
                }
            }
            else {
                objMember = {
                    name: name,
                    dimension: dimension.dataDimension,
                    uniqueName: '[' + dimension.txt + '].[' + name + ']',
                    caption: name,
                    properties: {},
                    formula: formula,
                    hierarchyName: dimension.val,
                    parentMember: '',
                    parentMemberLevel: '',
                    previousLevel: '',
                    parentMemberBreadcrumbs: [],
                    assignedLevel: ''
                };

                if (format) {
                    objMember.properties.FORMAT_STRING = format;
                }

                if (this.pmUniqueName && !(_.isEmpty(this.pmUniqueName))) {
                    objMember.parentMember = this.pmUniqueName;
                    objMember.parentMemberLevel = this.pmLevel;
                    objMember.previousLevel = this.lastLevel;
                    objMember.parentMemberBreadcrumbs = this.pmBreadcrumbs;
                    objMember.uniqueName = this.pmUniqueName+".["+name+"]";
                    objMember.assignedLevel = this.actualLevel
                }
                this.workspace.query.helper.includeCmember(dimension.val, objMember.uniqueName);
                if (formAction === 'cad') {
                    this.workspace.query.helper.addCalculatedMember(objMember);
                    this.workspace.sync_query();
                }
                else {
                    this.workspace.query.helper.removeLevelCalculatedMember(dimension.val, '[' + dimension.txt + '].[' + nameOld + ']');
                    this.workspace.query.helper.editCalculatedMember(nameOld, objMember);
                    this.workspace.sync_query();
                    this.workspace.drop_zones.set_measures();
                }

                this.workspace.toolbar.$el.find('.group_parents').removeClass('on');
                this.workspace.toolbar.group_parents();
            }

            this.$el.dialog('close');
        }
    },

    /**
     * Populate the MDX function select box with useful MDX constructs.
     * @param event
     */
    populate_function_list: function(event){

        var functions = [
            {name: 'Formula Not Empty Check', example:'Iif(NOT' +
            ' ISEMPTY([Measures].[My Measure]),([Measures].[My Measure] + [Numeric Expression]),null))',
                description: 'Insert a formula with an ISEMPTY check to ensure that only non null cells are' +
                ' calculated',
                doc_link:'http://saiku-documentation.readthedocs.io/en/latest/calculated_members.html?highlight=calculated%20members'},
            {name: 'Aggregate', example:'Aggregate(Set_Expression [ ,Numeric_Expression ])',
                description:'Returns a number that is calculated by aggregating over the cells returned by the set expression.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145524.aspx'},
            {name: 'Avg', example:'Avg( Set_Expression [ , Numeric_Expression ] )',
                description:'Evaluates a set and returns the average of the non empty values of the cells in the set, averaged over the measures in the set or over a specified measure.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms146067.aspx'},
            {name: 'Ancestor', example:'Ancestor(Member_Expression, Distance)',
                description:'A function that returns the ancestor of a specified member at a specified level or at a specified distance from the member.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145616.aspx'},
            {name: 'ClosingPeriod', example:'ClosingPeriod( [ Level_Expression [ ,Member_Expression ] ] )',
                description:'Returns the member that is the last sibling among the descendants of a specified member at a specified level.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145584.aspx'},
            {name: 'Cousin', example:'Cousin( Member_Expression , Ancestor_Member_Expression )',
                description:'Returns the child member with the same relative position under a parent member as the specified child member.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145481.aspx'},
            {name: 'CurrentMember', example:'Hierarchy_Expression.CurrentMember',
                description:'Returns the current member along a specified hierarchy during iteration.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms144948.aspx'},
            {name: 'FirstChild', example:'Member_Expression.FirstChild',
                description:'Returns the first child of a specified member.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms144947.aspx'},
            {name: 'FirstSibling', example:'Member_Expression.FirstSibling',
                description:'Returns the first child of the parent of a member.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145956.aspx'},
            {name: 'IIf', example:'IIf(Logical_Expression, Expression1, Expression2)',
                description:'Evaluates different branch expressions depending on whether a Boolean condition is true or false.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145994.aspx'},
            {name: 'LastChild', example:'Member_Expression.LastChild',
                description:'Returns the last child of a specified member.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145576.aspx'},
            {name: 'LastSibling', example:'Member_Expression.LastSibling',
                description:'Returns the last child of the parent of a specified member.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms144863.aspx'},
            {name: 'Max', example:'Max( Set_Expression [ , Numeric_Expression ] )',
                description:'Returns the maximum value of a numeric expression that is evaluated over a set.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145601.aspx'},
            {name: 'Median', example:'Median(Set_Expression [ ,Numeric_Expression ] )',
                description:'Returns the median value of a numeric expression that is evaluated over a set.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145570.aspx'},
            {name: 'Min', example:'Min( Set_Expression [ , Numeric_Expression ] )',
                description:'Returns the minimum value of a numeric expression that is evaluated over a set.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145600.aspx'},
            {name: 'MTD', example:'Mtd( [ Member_Expression ] )',
                description:'Returns a set of sibling members from the same level as a given member, starting with the first sibling and ending with the given member, as constrained by the Year level in the Time dimension.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms144753.aspx'},
            {name: 'OpeningPeriod', example:'OpeningPeriod( [ Level_Expression [ , Member_Expression ] ] )',
                description:'Returns the first sibling among the descendants of a specified level, optionally at a specified member.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145992.aspx'},
            {name: 'ParallelPeriod', example:'ParallelPeriod( [ Level_Expression [ ,Index [ , Member_Expression ] ] ] )',
                description:'Returns a member from a prior period in the same relative position as a specified member.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145500.aspx'},
            {name: 'Parent', example:'Member_Expression.Parent',
                description:'Returns the parent of a member.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145513.aspx'},
            {name: 'PrevMember', example:'Member_Expression.PrevMember',
                description:'Returns the previous member in the level that contains a specified member.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms144719.aspx'},
            {name: 'QTD', example:'Qtd( [ Member_Expression ] )',
                description:'Returns a set of sibling members from the same level as a given member, starting with the first sibling and ending with the given member, as constrained by the Quarter level in the Time dimension.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145978.aspx'},
            {name: 'Sum', example:'Sum( Set_Expression [ , Numeric_Expression ] )',
                description:'Returns the sum of a numeric expression evaluated over a specified set.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms145484.aspx'},
            {name: 'WTD', example:'Wtd( [ Member_Expression ] )',
                description:'Returns a set of sibling members from the same level as a given member, starting with the first sibling and ending with the given member, as constrained by the Week level in the Time dimension.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms144930.aspx'},
            {name: 'YTD', example:'Ytd( [ Member_Expression ] )',
                description:'Returns a set of sibling members from the same level as a given member, starting with the first sibling and ending with the given member, as constrained by the Year level in the Time dimension.',
                doc_link:'https://msdn.microsoft.com/en-us/library/ms146039.aspx'}

        ]

        var option = '';
        for (var i=0;i<functions.length;i++){
            option += '<option title="'+functions[i].description+'" data-desc="'+functions[i].description+'" data-doc-link="'+functions[i].doc_link+'" ' +
                'value="'+ functions[i].example + '">' + functions[i].name + '</option>';
        }
        $('.cms-functionlist').append(option);
    },

    change_function_list: function(event){
        event.preventDefault();

        var selectedFunction = this.$el.find('.cms-functionlist option:selected');

        $('.cms-functionlist').prop('title',($(selectedFunction).prop('title')));


        if($(selectedFunction).data("doc-link")){
            $('.cms-doclink').prop('href', $(selectedFunction).data("doc-link"));
            $('.cms-doclink').show();
        }
        else{
            $('.cms-doclink').hide();
        }

        if($(selectedFunction).val()){
            var i = this.$el.find(".formula-editor").attr('id');
            var editor = ace.edit(i);
            editor.insert($(selectedFunction).val());
        }
    },

    /**
     * Open the select member dialog
     * @param event
     */
    open_select_member_selector: function(event){
        event.preventDefault();
        var dimension = {
            val: this.$el.find('#cms-dimension option:selected').val(),
            txt: this.$el.find('#cms-dimension option:selected').text(),
            dataDimension: this.$el.find('#cms-dimension option:selected').data('dimension'),
            dataType: this.$el.find('#cms-dimension option:selected').data('type')
        };
        var i = this.$el.find(".formula-editor").attr('id');
        var editor = ace.edit(i);
        var that = this;
        var $currentTarget = $(event.currentTarget);


        if($currentTarget.hasClass("btn-select-member")){
            (new ParentMemberSelectorModal({
                dialog: this,
                workspace: this.workspace,
                cube: this.workspace.selected_cube,
                dimensions: Saiku.session.sessionworkspace.cube[this.workspace.selected_cube].get('data').dimensions,
                selectDimension: dimension.val,
                dimension: dimension.dataDimension,
                hierarchy: dimension.txt,
                uniqueName: this.pmUniqueName,
                lastLevel: this.pmLevel,
                breadcrumbs: this.pmBreadcrumbs,
                select_type: "select_member",
                selected_member: this.selected_member,
                close_callback: function(args){
                    var e = editor;
                    that.close_select_modal(e, args);
                }
            })).render().open();

            this.$el.parents('.ui-dialog').find('.ui-dialog-title').text('Connection Details');
        }
        else if (dimension.dataType === 'calcmember') {
            (new ParentMemberSelectorModal({
                dialog: this,
                workspace: this.workspace,
                cube: this.workspace.selected_cube,
                dimensions: Saiku.session.sessionworkspace.cube[this.workspace.selected_cube].get('data').dimensions,
                selectDimension: dimension.val,
                dimension: dimension.dataDimension,
                hierarchy: dimension.txt,
                uniqueName: this.pmUniqueName,
                lastLevel: this.lastLevel,
                current_level: this.pmLevel,
                breadcrumbs: this.pmBreadcrumbs
            })).render().open();

            this.$el.parents('.ui-dialog').find('.ui-dialog-title').text('Connection Details');
        }

    },

    /**
     * Callback to update the editor with the selected member.
     * @param editor
     * @param n
     */
    close_select_modal: function(editor, n){
        editor.insert(n);
    },

    help: function(){
        window.open("http://saiku-documentation.readthedocs.io/en/latest/calculated_members.html?highlight=calculated%20members");
    }
});
