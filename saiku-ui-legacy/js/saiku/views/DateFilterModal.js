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
 * Dialog for date filter
 */
var DateFilterModal = Modal.extend({
	type: 'date-filter',

	buttons: [
		{ text: 'Clear', method: 'clear_date_filter' },
		{ text: 'Save', method: 'save' },
		{ text: 'Open Standard Filter', method: 'open_standard_filter' },
		{ text: 'Cancel', method: 'finished' },
        { text: 'Help', method: 'help' }
	],

	events: {
		'click a': 'call',
		'focus .selection-date'  : 'selection_date',
		'click .selection-radio' : 'disable_divselections',
		'click .operator-radio'  : 'show_fields',
		'click #add-date'        : 'add_selected_date',
		'click .del-date'        : 'del_selected_date'
	},

	template_days_mdx: 'Filter({parent}.Members, {parent}.CurrentMember.NAME {comparisonOperator} \'{dates}\'',

	template_many_years_mdx: ' {logicalOperator} {parent}.CurrentMember.NAME {comparisonOperator} \'{dates}\'',

	template_mdx: 'IIF(ISEMPTY(CurrentDateMember([{dimension}.{hierarchy}],' +
	' \'["{dimension}.{hierarchy}"]\\\.{analyzerDateFormat}\', EXACT){lag}), {}, { {parent}' +
	' CurrentDateMember([{dimension}.{hierarchy}],' +
	' \'["{dimension}.{hierarchy}"]\\\.{analyzerDateFormat}\', EXACT){lag}})',

	template_last_mdx: '{parent} LastPeriods({periodAmount}, CurrentDateMember([{dimension}.{hierarchy}], \'["{dimension}.{hierarchy}"]\\\.{analyzerDateFormat}\', EXACT))',

	template_dialog: _.template(
		'<div class="box-selections">' +
			'<div class="selection-option">' +
				'<input type="radio" class="selection-radio" name="selection-radio" id="selection-radio-operator" level-type="TIME_DAYS" disabled>' +
			'</div>' +
			'<div class="available-selections" selection-name="operator" available="false">' +
				'<span class="i18n">Operator:</span><br>' +
				'<div class="selection-options">' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="operator-radio" class="operator-radio" id="op-equals" value="=" data-operator="equals"> <span class="i18n">Equals</span></label>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="operator-radio" class="operator-radio" id="op-after" value=">" data-operator="after"> <span class="i18n">After</span></label>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="operator-radio" class="operator-radio" id="op-before" value="<" data-operator="before"> <span class="i18n">Before</span></label>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="operator-radio" class="operator-radio" id="op-between" value=">=|<=" data-operator="between"> <span class="i18n">Between</span></label><br>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="operator-radio" class="operator-radio" id="op-different" value="<>" data-operator="different"> <span class="i18n">Different</span></label>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="operator-radio" class="operator-radio" id="op-after-equals" value=">=" data-operator="after&equals"> <span class="i18n">After&amp;Equals</span></label>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="operator-radio" class="operator-radio" id="op-before-equals" value="<=" data-operator="before&equals"> <span class="i18n">Before&amp;Equals</span></label>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="operator-radio" class="operator-radio" id="op-notbetween" value=">=||<=" data-operator="notbetween"> <span class="i18n">Not Between</span></label><br>' +
					'</div>' +
					'<div class="inline-form-group">' +
						'<div class="form-group" id="div-selection-date" hidden>' +
							'<label class="i18n">Select a date:</label>' +
							'<input type="text" class="selection-date" id="selection-date" placeholder="Choose a date">' +
							'<a class="form_button i18n" id="add-date">add</a>' +
						'</div>' +
						'<div class="form-group" id="div-selected-date" hidden>' +
							'<fieldset>' +
								'<legend class="i18n">Selected date:</legend>' +
								'<ul id="selected-date"></ul>' +
							'</fieldset>' +
						'</div>' +
					'</div>' +
					'<div class="form-group" id="div-select-start-date" hidden>' +
						'<label class="i18n">Select a start date:</label>' +
						'<input type="text" class="selection-date" id="start-date" placeholder="Choose a date">' +
					'</div>' +
					'<div class="form-group" id="div-select-end-date" hidden>' +
						'<label class="i18n">Select an end date:</label>' +
						'<input type="text" class="selection-date" id="end-date" placeholder="Choose a date">' +
					'</div>' +
				'</div>' +
			'</div>' +
		'</div>' +
		'<div class="box-selections">' +
			'<div class="selection-option">' +
				'<input type="radio" class="selection-radio" name="selection-radio" id="selection-radio-fixed-date">' +
			'</div>' +
			'<div class="available-selections" selection-name="fixed-date" available="false">' +
				'<span class="i18n">Fixed Date:</span><br>' +
				'<div class="selection-options">' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="fixed-radio" id="fd-yesterday" data-leveltype="TIME_DAYS"> <span class="i18n">Yesterday</span></label>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="fixed-radio" id="fd-today" data-leveltype="TIME_DAYS"> <span class="i18n">Today</span></label>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="fixed-radio" id="fd-week" data-leveltype="TIME_WEEKS"> <span class="i18n">Current Week</span></label>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="fixed-radio" id="fd-month" data-leveltype="TIME_MONTHS"> <span class="i18n">Current Month</span></label>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="fixed-radio" id="fd-quarter" data-leveltype="TIME_QUARTERS"> <span class="i18n">Current Quarter</span></label><br>' +
					'</div>' +
					'<div class="form-group-selection">' +
						'<label><input type="radio" name="fixed-radio" id="fd-year" data-leveltype="TIME_YEARS"> <span class="i18n">Current Year</span></label>' +
					'</div>' +
				'</div>' +
			'</div>' +
		'</div>' +
		'<div class="box-selections">' +
			'<div class="selection-option">' +
				'<input type="radio" class="selection-radio" name="selection-radio" id="selection-radio-available">' +
			'</div>' +
			'<div class="available-selections" selection-name="rolling-date" available="false">' +
				'<span class="i18n">Rolling Date:</span><br>' +
				'<div class="selection-options">' +
					'<div class="form-group-rolling">' +
						'<select>' +
							'<option class="i18n" value="last">Last</option>' +
							'<option class="keep-disabled i18n" value="next" disabled>Next</option>' +
						'</select>' +
					'</div>' +
					'<div class="form-group-rolling">' +
						'<input type="text" id="date-input">' +
					'</div>' +
					'<div class="form-group-rolling">' +
						'<select id="period-select">' +
							'<option name="TIME_DAYS" class="i18n" id="rd-days">Day(s)</option>' +
							'<option name="TIME_WEEKS" class="i18n" id="rd-weeks">Week(s)</option>' +
							'<option name="TIME_MONTHS" class="i18n" id="rd-months">Month(s)</option>' +
							'<option name="TIME_YEARS" class="i18n" id="rd-years">Year(s)</option>' +
						'</select>' +
					'</div>' +
				'</div>' +
			'</div>' +
		'</div>'
	),

	initialize: function(args) {
		// Initialize properties
		_.extend(this, args);
		this.options.title = 'Date Filter';
		this.message = 'Loading...';
		this.query = args.workspace.query;
		this.selectedDates = [];

		// Level information
		this.levelInfo = {
			cube: this.get_cube_name(),
			dimension: this.dimension,
			hierarchy: this.hierarchy,
			name: this.name
		};

		// Maintain `this` in callbacks
		_.bindAll(this, 'finished');

		// Resize when rendered
		this.bind('open', this.post_render);
		this.render();

		// show/hide button for clear filter
		if (this.show_button_clear()) {
			this.$el.find('.dialog_footer a:nth-child(1)').show();
			this.$el.find('.dialog_footer a:nth-child(3)').hide();
		}
		else {
			this.$el.find('.dialog_footer a:nth-child(1)').hide();
		}

		// Add function for button Close `x`
		this.$el.parent().find('.ui-dialog-titlebar-close').bind('click', this.finished);

		// Fetch available members
		this.member = new Member({}, {
			cube: this.workspace.selected_cube,
			dimension: this.key
		});

		// Load template
		this.$el.find('.dialog_body').html(this.template_dialog);

		// Disable all elements and remove an event handler
		this.$el.find('.available-selections *').prop('disabled', true).off('click');

		// Save data of levels
		this.dataLevels = this.save_data_levels();

		// Check SaikuDayFormatString in level
		this.check_saikuDayFormatString();

		// Initialize adding values
		this.add_values_fixed_date();
		this.add_values_last_periods();

		// Populate date filter
		this.populate();

		// Translate
		Saiku.i18n.translate();
	},

    help: function(event) {
        event.preventDefault();
        window.open('http://saiku-documentation.readthedocs.io/en/latest/advanced_date_filtering.html');
    },

	open_standard_filter: function(event) {
		event.preventDefault();

    	// Launch selections dialog
    	(new SelectionsModal({
    		source: 'DateFilterModal',
		    target: this.target,
		    name: this.name,
		    key: this.key,
		    objDateFilter: {
		        dimension: this.dimension,
		        hierarchy: this.hierarchy,
		        data: this.data,
		        analyzerDateFormat: this.analyzerDateFormat,
		        dimHier: this.dimHier
		    },
		    workspace: this.workspace
		})).open();

		this.$el.dialog('destroy').remove();
	},

	post_render: function(args) {
		var left = ($(window).width() - 600) / 2,
			width = $(window).width() < 600 ? $(window).width() : 600;
		$(args.modal.el).parents('.ui-dialog')
			.css({ width: width, left: 'inherit', margin: '0', height: 490 })
			.offset({ left: left});
	},

	check_saikuDayFormatString: function() {
		var self = this;
		this.$el.find('.selection-radio').each(function(key, radio) {
			_.find(self.dataLevels, function(value) {
				if (self.name === value.name && value.saikuDayFormatString) {
					$(radio).prop('disabled', false);
				}
			});
		});
	},

	show_fields: function(event) {
		var $currentTarget = event.type ? $(event.currentTarget) : $(event),
			name = $currentTarget.data('operator');
		if (name !== undefined) {
			switch (name) {
			case 'equals':
			case 'different':
				$currentTarget.closest('.selection-options').find('#div-selection-date').show();
				$currentTarget.closest('.selection-options').find('#div-selected-date').show();
				$currentTarget.closest('.selection-options').find('#div-select-start-date').hide();
				$currentTarget.closest('.selection-options').find('#div-select-end-date').hide();
				$currentTarget.closest('.selection-options').find('#add-date').show();
				this.clear_operators();
				break;
			case 'after':
			case 'after&equals':
			case 'before':
			case 'before&equals':
				$currentTarget.closest('.selection-options').find('#div-selection-date').show();
				$currentTarget.closest('.selection-options').find('#div-selected-date').hide();
				$currentTarget.closest('.selection-options').find('#div-select-start-date').hide();
				$currentTarget.closest('.selection-options').find('#div-select-end-date').hide();
				$currentTarget.closest('.selection-options').find('#add-date').hide();
				this.clear_operators();
				break;
			case 'between':
			case 'notbetween':
				$currentTarget.closest('.selection-options').find('#div-selection-date').hide();
				$currentTarget.closest('.selection-options').find('#div-selected-date').hide();
				$currentTarget.closest('.selection-options').find('#div-select-start-date').show();
				$currentTarget.closest('.selection-options').find('#div-select-end-date').show();
				$currentTarget.closest('.selection-options').find('#add-date').hide();
				this.clear_operators();
				break;
			default:
				$currentTarget.closest('.selection-options').find('#div-selection-date').hide();
				$currentTarget.closest('.selection-options').find('#div-selected-date').hide();
				$currentTarget.closest('.selection-options').find('#div-select-start-date').hide();
				$currentTarget.closest('.selection-options').find('#div-select-end-date').hide();
				$currentTarget.closest('.selection-options').find('#add-date').hide();
				this.clear_operators();
			}
		}
		else {
			this.$el.find('.selection-options').find('#div-selection-date').hide();
			this.$el.find('.selection-options').find('#div-selected-date').hide();
			this.$el.find('.selection-options').find('#div-select-start-date').hide();
			this.$el.find('.selection-options').find('#div-select-end-date').hide();
			this.$el.find('.selection-options').find('#add-date').hide();
			this.clear_operators();
		}
	},

	save_data_levels: function() {
		var self = this,
			dataLevels = [];
		_.each(this.data.hierarchies.levels, function(value, key, list) {
			if (list[key].annotations.AnalyzerDateFormat !== undefined || list[key].annotations.SaikuDayFormatString !== undefined) {
				if (list[key].annotations.AnalyzerDateFormat !== undefined) {
					dataLevels.push({
						name: list[key].name,
						analyzerDateFormat: list[key].annotations.AnalyzerDateFormat.replace(/[.]/gi, '\\\.'),
						levelType: list[key].levelType,
						saikuDayFormatString: list[key].annotations.SaikuDayFormatString || ''
					});
				}
				else {
					dataLevels.push({
						name: list[key].name,
						analyzerDateFormat: '',
						levelType: list[key].levelType,
						saikuDayFormatString: list[key].annotations.SaikuDayFormatString || ''
					});
				}
				if (list[key].annotations.SaikuDayFormatString) {
					self.saikuDayFormatString = list[key].annotations.SaikuDayFormatString;
				}
			}
		});

		return dataLevels;
	},

	add_values_fixed_date: function() {
		var self = this;
		this.$el.find('.available-selections').each(function(key, selection) {
			if ($(selection).attr('selection-name') === 'fixed-date') {
				$(selection).find('input:radio').each(function(key, radio) {
					var name = $(radio).data('leveltype');
					_.find(self.dataLevels, function(value, key) {
						if (name === value.levelType) {
							$(radio).val(self.dataLevels[key].analyzerDateFormat);
						}
						else if ((name === 'yesterday' || name === 'today') &&
							value.name === self.name &&
							!(_.isEmpty(self.dataLevels[key].analyzerDateFormat)) &&
							self.dataLevels[key].analyzerDateFormat !== undefined &&
							self.dataLevels[key].analyzerDateFormat !== null &&
							self.dataLevels[key].levelType === 'TIME_DAYS') {
							$(radio).val(self.dataLevels[key].analyzerDateFormat);
						}
					});
				});

				$(selection).find('input:radio').each(function(key, radio) {
					if ($(radio).val() === null ||
						$(radio).val() === undefined ||
						$(radio).val() === '' ||
						$(radio).val() === 'on') {
						$(radio).addClass('keep-disabled');
					}
				});

			}
		});
	},

	add_values_last_periods: function() {
		var self = this;
		this.$el.find('.available-selections').each(function(key, selection) {
			if ($(selection).attr('selection-name') === 'rolling-date') {
				$(selection).find('#period-select > option').each(function(key, radio) {
					var name = $(radio).attr('name');
					_.find(self.dataLevels, function(value, key) {
						if (name === value.levelType) {
							$(radio).val(self.dataLevels[key].analyzerDateFormat);
						}

					});
				});
				$(selection).find('#period-select > option').each(function(key, radio) {
					if ($(radio).attr('value') === null ||
						$(radio).attr('value') === undefined ||
						$(radio).attr('value') === '') {
						$(radio).addClass('keep-disabled');
					}
				});
			}
		});
	},

	selection_date: function(event) {
		var $currentTarget = $(event.currentTarget),
			dateFormat = this.saikuDayFormatString.replace(/yyyy/gi, 'yy');
		$currentTarget.datepicker({
			dateFormat: dateFormat
		});
	},

	clear_selections: function(event) {
		// Clear dialog
		this.show_fields(event);
		this.$el.find('input[type="text"]').val('');
		this.$el.find('select').prop('selectedIndex', 0);
		this.$el.find('#selected-date').empty();
		this.$el.find('.available-selections *').prop('checked', false);
		// Clear variables
		this.selectedDates = [];
	},

	clear_operators: function() {
		// Clear operator
		this.$el.find('input[type="text"]').val('');
		this.$el.find('#selected-date').empty();
		// Clear variables
		this.selectedDates = [];
	},

	disable_divselections: function(event) {
		var params = Array.prototype.slice.call(arguments),
			$currentTarget = event.type ? $(event.currentTarget) : $(event);

		if (!params[1]) {
			this.clear_selections(event);
		}

		this.$el.find('.available-selections').attr('available', false);
		this.$el.find('.available-selections *').prop('disabled', true).off('click');
		$currentTarget.closest('.box-selections').find('.available-selections').attr('available', true);
		$currentTarget.closest('.box-selections').find('.available-selections *:not(.keep-disabled)')
			.prop('disabled', false).on('click');

		if (event.type) {
			$currentTarget.closest('.box-selections').find('select').each(function(key, selection) {
				$(selection).find('option:not([disabled])').first().attr('selected', 'selected');
			});
		}
	},

	day_format_string: function() {
		var dayFormatString = this.saikuDayFormatString;
		dayFormatString = dayFormatString.replace(/[a-zA-Z]/gi, '9');
		return dayFormatString;
	},

	add_selected_date: function(event) {
		event.preventDefault();
		var $currentTarget = $(event.currentTarget),
			dayFormatString = this.day_format_string(),
			sDate = this.$el.find('#selection-date'),
			selectedDate = $currentTarget.closest('.inline-form-group')
				.find('#div-selected-date').find('#selected-date');

		if (sDate.val() !== '') {
			var newDate = Saiku.toPattern(sDate.val(), dayFormatString);
			sDate.css('border', '1px solid #ccc');
			selectedDate.append($('<li></li>')
				.text(newDate)
				.append('<a href="#" class="del-date" data-date="' + newDate + '">x</a>'));
			this.selectedDates.push(newDate);
		}
		else {
			sDate.css('border', '1px solid red');
		}

		sDate.val('');
	},

	del_selected_date: function(event) {
		event.preventDefault();
		var $currentTarget = $(event.currentTarget),
			date = $currentTarget.data('date');
		this.selectedDates = _.without(this.selectedDates, date);
		$currentTarget.parent().remove();
	},

	populate: function() {
		var data = this.get_date_filter(),
			$selection;

		if (data && !(_.isEmpty(data))) {
			if (data.type === 'operator') {
				var $checked = this.$el.find('#' + data.checked),
					name = $checked.data('operator'),
					self = this;
				$selection = this.$el.find('#selection-radio-operator');
				$selection.prop('checked', true);
				$checked.prop('checked', true);
				this.disable_divselections($selection, true);
				this.show_fields($checked);

				this.selectedDates = data.values;

				if (name === 'after' || name === 'after&equals' ||
					name === 'before' || name === 'before&equals') {
					this.$el.find('#selection-date').val(this.selectedDates[0]);
				}
				else if (name === 'between') {
					self.$el.find('#start-date').val(this.selectedDates[0]);
					self.$el.find('#end-date').val(this.selectedDates[1]);
				}
				else if (name === 'notbetween') {
					self.$el.find('#start-date').val(this.selectedDates[0]);
					self.$el.find('#end-date').val(this.selectedDates[1]);
				}
				else {
					_.each(this.selectedDates, function(value, key) {
						self.$el.find('#selected-date').append($('<li></li>')
							.text(value)
							.append('<a href="#" class="del-date" data-date="' + value + '">x</a>'));
					});
				}
			}
			else if (data.type === 'fixed-date') {
				$selection = this.$el.find('#selection-radio-fixed-date');
				$selection.prop('checked', true);
				this.$el.find('#' + data.checked).prop('checked', true);
				this.disable_divselections($selection, true);
			}
			else {
				$selection = this.$el.find('#selection-radio-available');
				$selection.prop('checked', true);
				this.$el.find('#date-input').val(data.periodAmount);
				this.$el.find('select#period-select option[id="' + data.periodSelect + '"]').prop('selected', true);
				this.disable_divselections($selection, true);
			}
		}
	},

	populate_mdx: function(logExp, fixedDateName, periodAmount) {
		logExp.tagdim = logExp.dimension.replace(/m/g, '\\m').replace(/y/g, '\\y').replace(/q/g, '\\q').replace(/d/g, '\\d');
		logExp.taghier = logExp.hierarchy.replace(/m/g, '\\m').replace(/y/g, '\\y').replace(/q/g, '\\q').replace(/d/g, '\\d');

		if ((logExp.workinglevel !== logExp.level) && logExp.workinglevel !== undefined) {
			logExp.parent = '[{dimension}.{hierarchy}].[{level}].members,';
			logExp.parent = logExp.parent.replace(/{(\w+)}/g, function(m, p) {
				return logExp[p];
			});
		}
		else{
			logExp.parent = '';
		}

		if (fixedDateName === 'dayperiods') {
			logExp.parent = '[{dimension}.{hierarchy}].[{level}]';
			logExp.parent = logExp.parent.replace(/{(\w+)}/g, function(m, p) {
				return logExp[p];
			});

			if (this.selectedDates.length > 1) {
				var len = this.selectedDates.length,
					i;

				for (i = 0; i < len; i++) {
					logExp.dates = this.selectedDates[i];

					if (logExp.comparisonOperator === '>=|<=') {
						if (i === 0) {
							logExp.comparisonOperator = logExp.comparisonOperator.split('|')[0];
							this.template_days_mdx = this.template_days_mdx.replace(/{(\w+)}/g, function(m, p) {
								return logExp[p];
							});
							logExp.comparisonOperator = '>=|<=';
						}
						else {
							logExp.logicalOperator = 'AND';
							logExp.comparisonOperator = logExp.comparisonOperator.split('|')[1];
							this.template_days_mdx += this.template_many_years_mdx.replace(/{(\w+)}/g, function(m, p) {
								return logExp[p];
							});
						}
					}
					else if (logExp.comparisonOperator === '>=||<=') {
						if (i === 0) {
							this.template_days_mdx = 'EXCEPT({parent}.Members, ' + this.template_days_mdx;

							logExp.comparisonOperator = logExp.comparisonOperator.split('||')[0];
							this.template_days_mdx = this.template_days_mdx.replace(/{(\w+)}/g, function(m, p) {
								return logExp[p];
							});
							logExp.comparisonOperator = '>=||<=';
						}
						else {
							logExp.logicalOperator = 'AND';
							logExp.comparisonOperator = logExp.comparisonOperator.split('||')[1];
							this.template_days_mdx += this.template_many_years_mdx.replace(/{(\w+)}/g, function(m, p) {
								return logExp[p];
							});

							return this.template_days_mdx + '))';
						}
					}
					else {
						if (i === 0) {
							this.template_days_mdx = this.template_days_mdx.replace(/{(\w+)}/g, function(m, p) {
								return logExp[p];
							});
						}
						else {
							if (logExp.comparisonOperator === '<>') {
								logExp.logicalOperator = 'AND';
							}
							else {
								logExp.logicalOperator = 'OR';
							}
							this.template_days_mdx += this.template_many_years_mdx.replace(/{(\w+)}/g, function(m, p) {
								return logExp[p];
							});
						}
					}
				}

				return this.template_days_mdx + ')';
			}
			else {
				logExp.dates = this.selectedDates[0];
				this.template_days_mdx = this.template_days_mdx.replace(/{(\w+)}/g, function(m, p) {
					return logExp[p];
				}) + ')';

				return this.template_days_mdx;
			}
		}
		else if (fixedDateName === 'lastperiods') {
			this.template_last_mdx = this.template_last_mdx.replace(/{(\w+)}/g, function(m, p) {
				return logExp[p];
			});

			return this.template_last_mdx;
		}
		else {

			if (fixedDateName === 'yesterday') {
				logExp.lag = '.lag(1)';
			}
			else {
				logExp.lag = '';
			}

			this.template_mdx = this.template_mdx.replace(/{(\w+)}/g, function(m, p) {
				return logExp[p];
			});
			return this.template_mdx;
		}
	},

	save: function(event) {
		event.preventDefault();
		// Notify user that updates are in progress
		var $loading = $('<div>Saving...</div>');
		$(this.el).find('.dialog_body').children().hide();
		$(this.el).find('.dialog_body').prepend($loading);

		var self = this,
			fixedDateName,
			comparisonOperator,
			analyzerDateFormat,
			periodAmount,
			parentmembers,
			mdx = null,
			selectedData = {};

		if (self.hierarchy === null || self.hierarchy === undefined) {
			self.hierarchy = self.dimension;
		}

		this.$el.find('.available-selections').each(function(key, selection) {
			if ($(selection).attr('available') === 'true') {
				selectedData.type = $(selection).attr('selection-name');

				if ($(selection).attr('selection-name') === 'operator') {
					$(selection).find('input:radio').each(function (key, radio) {
						if ($(radio).is(':checked') === true) {
							var name = $(radio).data('operator');
							selectedData.checked = $(radio).attr('id');

							if (name === 'after' || name === 'after&equals' ||
								name === 'before' || name === 'before&equals') {
								self.selectedDates = [];
								self.selectedDates.push(self.$el.find('#selection-date').val());
							}
							else if (name === 'between' || name === 'notbetween') {
								self.selectedDates = [];
								self.selectedDates.push(self.$el.find('#start-date').val());
								self.selectedDates.push(self.$el.find('#end-date').val());
							}

							parentmembers = self.name;
							fixedDateName = 'dayperiods';
							comparisonOperator = $(radio).val();
							selectedData.values = self.selectedDates;
						}
					});
				}
				else if ($(selection).attr('selection-name') === 'fixed-date') {
					$(selection).find('input:radio').each(function (key, radio) {
						if ($(radio).is(':checked') === true) {
							fixedDateName = $(radio).attr('id').split('-')[1];
							analyzerDateFormat = $(radio).val();
							selectedData.checked = $(radio).attr('id');
						}
					});
				}
				else if ($(selection).attr('selection-name') === 'rolling-date') {
					analyzerDateFormat = $('#period-select').find(':selected').val();
					fixedDateName = 'lastperiods';
					periodAmount = $(selection).find('input:text').val();
					selectedData.fixedDateName = fixedDateName;
					selectedData.periodAmount = $(selection).find('input:text').val();
					selectedData.periodSelect = $('#period-select').find(':selected').attr('id');
				}

				var len = self.dataLevels.length,
					workinglevel,
					i;

				for (i = 0; i < len; i++) {
					if (self.dataLevels[i].analyzerDateFormat === analyzerDateFormat) {
						if (self.dataLevels[i].name === self.name) {
							parentmembers = self.name;
							workinglevel = self.dataLevels[i].name;
						}
						else {
							workinglevel = self.dataLevels[i].name;
						}
					}
				}

				var logExp = {
					dimension: self.dimension,
					hierarchy: self.hierarchy,
					level: self.name,
					analyzerDateFormat: analyzerDateFormat,
					periodAmount: periodAmount,
					comparisonOperator: comparisonOperator,
					workinglevel: workinglevel
				};

				//if (fixedDateName === 'dayperiods' || (logExp.workinglevel !== logExp.level && logExp.workinglevel !== undefined)) {
					if ((fixedDateName === 'dayperiods' && self.selectedDates[0] !== '' && self.selectedDates[0] !== undefined) ||
						(fixedDateName === 'lastperiods' && !(_.isEmpty(analyzerDateFormat)) && analyzerDateFormat !== 'Day(s)' && !(_.isEmpty(periodAmount))) ||
						(fixedDateName !== 'dayperiods' && fixedDateName !== 'lastperiods') && !(_.isEmpty(analyzerDateFormat))) {
						mdx = self.populate_mdx(logExp, fixedDateName);
					}
					else {
						mdx = null;
					}
				/*}
				else {
					mdx = null;
				}*/
			}
		});

		var hName = decodeURIComponent(this.member.hierarchy),
			lName = decodeURIComponent(this.member.level),
			hierarchy = this.workspace.query.helper.getHierarchy(hName),
			cubeSelected = this.get_cube_name();

		_.extend(selectedData, this.levelInfo);

		if ((fixedDateName === 'dayperiods' && this.selectedDates[0] !== '' && self.selectedDates[0] !== undefined) ||
			(fixedDateName === 'lastperiods' && !(_.isEmpty(analyzerDateFormat)) && analyzerDateFormat !== 'Day(s)' && !(_.isEmpty(periodAmount))) ||
			(fixedDateName !== 'dayperiods' && fixedDateName !== 'lastperiods') && !(_.isEmpty(analyzerDateFormat))) {
			this.set_date_filter(selectedData);
		}
		else {
			var uuid = this.get_uuid(selectedData);
			this.workspace.dateFilter.remove(uuid);
			this.workspace.query.setProperty('saiku.ui.datefilter.data', this.workspace.dateFilter.toJSON());
		}

		// console.log(mdx);

		if (hierarchy && hierarchy.levels.hasOwnProperty(lName)) {
			hierarchy.levels[lName] = { mdx: mdx, name: lName };
		}

		this.finished();
	},

	get_cube_name: function() {
		return decodeURIComponent(this.workspace.selected_cube.split('/')[3]);
	},

	get_uuid: function(data) {
		return '[' + data.cube + '].' + this.dimHier + '.[' + data.name + ']';
	},

	set_date_filter: function(data) {
		var dateFilter = this.workspace.dateFilter,
			objDateFilter = dateFilter.toJSON(),
			uuid = this.get_uuid(data);

		data.id = uuid;
		data.key = this.key;

		if (objDateFilter && !(_.isEmpty(objDateFilter))) {
			if (dateFilter.get(uuid)) {
				dateFilter = dateFilter.get(uuid);
				dateFilter.set(data);
				this.workspace.query.setProperty('saiku.ui.datefilter.data', dateFilter.toJSON());
			}
			else {
				dateFilter.add(data);
				this.workspace.query.setProperty('saiku.ui.datefilter.data', dateFilter.toJSON());
			}
		}
		else {
			dateFilter.add(data);
			this.workspace.query.setProperty('saiku.ui.datefilter.data', dateFilter.toJSON());
		}
	},

	get_date_filter: function() {
		var objData = {
			cube: this.get_cube_name(),
			dimension: this.dimension,
			hierarchy: this.hierarchy,
			name: this.name
		};

		var uuid = this.get_uuid(objData),
			data = this.workspace.dateFilter.get(uuid);

		data = data ? data.toJSON() : [];

		return data;
	},

    clear_date_filter: function(event) {
    	event.preventDefault();

		var objDateFilter = this.workspace.dateFilter.toJSON(),
			uuid;

		uuid = this.get_uuid(this.levelInfo);

		var hName = decodeURIComponent(this.member.hierarchy),
			lName = decodeURIComponent(this.member.level),
			hierarchy = this.workspace.query.helper.getHierarchy(hName);

		if (hierarchy && hierarchy.levels.hasOwnProperty(lName)) {
			hierarchy.levels[lName] = { mdx: null, name: lName };
		}

		this.workspace.dateFilter.remove(uuid);

		this.workspace.query.setProperty('saiku.ui.datefilter.data', this.workspace.dateFilter.toJSON());

		this.clear_selections(event);

		this.finished();
    },

    show_button_clear: function() {
		var dateFilter = this.workspace.dateFilter,
			objDateFilter = dateFilter.toJSON(),
			uuid;

		uuid = this.get_uuid(this.levelInfo);

        if (objDateFilter && !(_.isEmpty(objDateFilter))) {
            if (dateFilter.get(uuid)) {
                return true;
            }
            else {
                return false;
            }
        }
        else {
            return false;
        }
    },

	finished: function(event) {
		this.$el.dialog('destroy').remove();
		if (!event) {
			this.query.run();
		}
	}
});

/**
 * Observer to remove elements in the date filter model
 */
var DateFilterObserver = Backbone.View.extend({
	initialize: function(args) {
		// Keep track of parent workspace
		this.workspace = args.workspace;

		// Maintain `this` in callbacks
		_.bindAll(this, 'receive_data', 'workspace_levels');

		// Listen to result event
		this.workspace.bind('query:result', this.receive_data);
		Saiku.session.bind('dimensionList:select_dimension', this.receive_data);
		Saiku.session.bind('workspaceDropZone:select_dimension', this.receive_data);
		Saiku.session.bind('workspaceDropZone:clear_axis', this.receive_data);
	},

    receive_data: function(args) {
		var objDateFilter = this.workspace.dateFilter.toJSON();

		this.check_dateFilter_saved();

		if (objDateFilter && !(_.isEmpty(objDateFilter))) {
        	return _.delay(this.workspace_levels, 1000, args);
        }
    },

	get_cube_name: function() {
		return decodeURIComponent(this.workspace.selected_cube.split('/')[3]);
	},

    workspace_levels: function(args) {
    	var cubeName = this.get_cube_name(),
    		axisColumns = this.workspace.query.helper.getAxis('COLUMNS'),
    		axisRows = this.workspace.query.helper.getAxis('ROWS'),
    		axisFilter = this.workspace.query.helper.getAxis('FILTER'),
    		arrData = [];

    	if (axisColumns.location === 'COLUMNS' && axisColumns.hierarchies.length > 0) {
    		arrData.push(this.get_axes(cubeName, axisColumns));
    	}
    	if (axisRows.location === 'ROWS' && axisRows.hierarchies.length > 0) {
			arrData.push(this.get_axes(cubeName, axisRows));
    	}
    	if (axisFilter.location === 'FILTER' && axisFilter.hierarchies.length > 0) {
			arrData.push(this.get_axes(cubeName, axisFilter));
    	}

    	arrData = _.compact(_.union(arrData[0], arrData[1], arrData[2]));

    	this.check_dateFilter_model(arrData);
    },

    get_axes: function(cubeName, axis) {
    	var arrAxis = [],
    		len = axis.hierarchies.length,
    		i;

		for (i = 0; i < len; i++) {
			for (var name in axis.hierarchies[i].levels) {
				if (axis.hierarchies[i].levels.hasOwnProperty(name)) {
					arrAxis.push('[' + cubeName + '].' + axis.hierarchies[i].name + '.[' + name + ']');
				}
			}
		}

		return arrAxis;
    },

	check_dateFilter_saved: function() {
		var checkDateFilterSaved = this.workspace.checkDateFilterSaved;

		if ((this.workspace.item && !(_.isEmpty(this.workspace.item))) &&
			checkDateFilterSaved === undefined) {
			var data = this.workspace.query.getProperty('saiku.ui.datefilter.data');
			this.workspace.dateFilter.add(data);
			this.workspace.checkDateFilterSaved = true;
		}
		else {
			this.workspace.checkDateFilterSaved = true;
		}
	},

    check_dateFilter_model: function(data) {
    	var arrRemove = [],
    		arrChecked = [],
    		objDateFilter = this.workspace.dateFilter.toJSON(),
    		lenDateFilter = objDateFilter.length,
    		lenData = data.length,
    		aux = 0,
    		i = 0;

    	if (lenData > 0 && (objDateFilter && !(_.isEmpty(objDateFilter)))) {
    		while (i < lenData) {
	    		if (data[i] === objDateFilter[aux].id) {
	    			arrChecked.push(objDateFilter[aux].id);
	    			if ((aux + 1) < lenDateFilter) {
	    				aux++;
	    			}
	    			else {
	    				aux = 0;
	    				i++;
	    			}
	    		}
	    		else {
	    			arrRemove.push(objDateFilter[aux].id);
	    			if ((aux + 1) < lenDateFilter) {
	    				aux++;
	    			}
	    			else {
	    				aux = 0;
	    				i++;
	    			}
	    		}
    		}
		}
		else if (lenData === 0 && (objDateFilter && !(_.isEmpty(objDateFilter)))) {
			for (var j = 0; j < lenDateFilter; j++) {
				this.workspace.dateFilter.remove(objDateFilter[j].id);
			}

			this.workspace.query.setProperty('saiku.ui.datefilter.data', this.workspace.dateFilter.toJSON());
		}

		this.remove_dateFilter_model(_.difference(arrRemove, arrChecked));
    },

    remove_dateFilter_model: function(data) {
    	var lenData = data.length,
    		i;

    	for (i = 0; i < lenData; i++) {
    		this.workspace.dateFilter.remove(data[i]);
    	}

    	this.workspace.query.setProperty('saiku.ui.datefilter.data', this.workspace.dateFilter.toJSON());
    }
});

 /**
  * Start DateFilterObserver
  */
Saiku.events.bind('session:new', function() {

	function new_workspace(args) {
		if (typeof args.workspace.dateFilterObserver === 'undefined') {
			args.workspace.dateFilterObserver = new DateFilterObserver({ workspace: args.workspace });
		}
	}

	// Add new tab content
	for (var i = 0, len = Saiku.tabs._tabs.length; i < len; i++) {
		var tab = Saiku.tabs._tabs[i];
		new_workspace({
			workspace: tab.content
		});
	}

	// New workspace
	Saiku.session.bind('workspace:new', new_workspace);
});
