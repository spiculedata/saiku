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
 * Sets up workspace drop zones for DnD and other interaction
 */
var WorkspaceDropZone = Backbone.View.extend({

    template: function() {
        var template = $("#template-workspace-dropzones").html() || "";
        return _.template(template)();
    },

    events: {
        'sortstop .fields_list_body.details': 'set_measures',
        'sortstop .axis_fields': 'select_dimension',
        'click .d_measure' : 'remove_measure_click',
        'click .d_level': 'selections',
        // 'click .d_measure span.sort' : 'sort_measure',
        'click .measure_fields.limit' : 'measure_action',
        'click .axis_fields_header.limit' : 'limit_axis',
        'click .clear_axis' : 'clear_axis'
    },

    initialize: function(args) {
        // Keep track of parent workspace
        this.workspace = args.workspace;

        // Maintain `this` in jQuery event handlers
        _.bindAll(this, "clear_axis", "set_measures");
    },

    render: function() {
        var self = this;
        // Generate drop zones from template
        $(this.el).html(this.template());

        // Activate drop zones
        $(this.el).find('.fields_list_body.details ul.connectable').sortable({
            items:          '> li',
            opacityg:        0.60,
            placeholder:    'placeholder',
            tolerance:      'pointer',
            containment:    $(self.workspace.el),
            start:          function(event, ui) {
                ui.placeholder.text(ui.helper.text());
            }
        });

        $(this.el).find('.axis_fields ul.connectable').sortable({
            connectWith: $(self.el).find('.axis_fields ul.connectable'),
            forcePlaceholderSize:   false,
            forceHelperSize:        true,
            items:                  'li.selection',
            opacity:                0.60,
            placeholder:            'placeholder',
            tolerance:              'touch',
            cursorAt:               { top: 10, left: 60 },
            containment:            $(self.workspace.el),
            start:                  function(event, ui) {
                var hierarchy = $(ui.helper).find('a').parent().parent().attr('hierarchycaption');
                ui.placeholder.text(hierarchy);
                $(ui.helper).css({ width: "auto", height: "auto"});
                $(self.el).find('.axis_fields ul.hierarchy li.d_level:visible').addClass('temphide').hide();
            }
        });


        return this;
    },

    set_measures: function(event, ui) {
        var details = [];

        $(this.el).find('.fields_list_body.details ul.connectable li.d_measure').each( function(index, element) {
            var measure = {
                "name": $(element).find('a').attr('measure'),
                "type": $(element).find('a').attr('type')
            };
            details.push(measure);
            Saiku.events.trigger("workspaceDropZone:select_measure", this,
                {measure:measure});
        });
        this.workspace.query.helper.setMeasures(details);
        this.workspace.sync_query();
        this.workspace.query.run();

    },

    remove_measure_click: function(event) {
        event.preventDefault();
        var target = $(event.target).hasClass('d_measure') ?  $(event.target).find('a') :  $(event.target);
        target.parent().remove();
        this.set_measures();
    },

    remove_dimension: function(event, ui) {
        if ($(ui.helper).hasClass('d_measure')) {
                $(ui.helper).detach();
                this.set_measures();
        } else {
            var self = this;
            var hierarchy = $(ui.helper).find('a').attr('hierarchy');
            var fromAxis = $(this.el).find('ul.hierarchy[hierarchy="' + hierarchy + '"]').parents('.fields_list').attr('title');
            var level =  $(ui.helper).find('a').attr('level');

            if (Settings.ALLOW_PARAMETERS) {
                $.each($(ui.helper).find('li'), function(key, value) {
                    if ($(value).hasClass('temphide')) {
                        level = $(value).find('a').attr('level');
                        self.workspace.query.helper.removeParameter(hierarchy, level);
                    }
                });
                this.workspace.$el.find('.parameter_input').empty();
            }

            this.workspace.query.helper.removeHierarchy(hierarchy);
            this.workspace.sync_query();
            this.workspace.query.run();
        }
    },

    synchronize_query: function() {
        var self = this;
        this.reset_dropzones();

        var model = this.workspace.query.helper.model();

        if (model.hasOwnProperty('queryModel') && model.queryModel.hasOwnProperty('axes')) {
            var axes = model.queryModel.axes;

            for (var axis in axes) {
                var $axis = $(self.el).find('.fields_list[title="' + axis + '"]');
                _.each(axes[axis].hierarchies, function(hierarchy) {
					if(self.workspace.dimension_list!=null) {
						var h = $(self.workspace.dimension_list.el).find('ul.d_hierarchy[hierarchy="' + hierarchy.name + '"]').clone().removeClass('d_hierarchy').addClass('hierarchy');
						h.find('li.d_level').hide();
						for (var level in hierarchy.levels) {
							h.find('li a[level="' + level + '"]').parent().show();

							// sync attribute list
							$(self.workspace.dimension_list.el).find('ul.d_hierarchy[hierarchy="' + hierarchy.name + '"] li a[level="' + level + '"]').parent()
								.draggable('disable')
								.parents('.parent_dimension')
								.find('.folder_collapsed')
								.addClass('selected');
						}
						/*for (var member in hierarchy.cmembers) {
						 if (hierarchy.cmembers.hasOwnProperty(member)) {
						 var level = member.split('.')[member.split('.').length-1].replace(/[\[\]]/gi, '');

						 h.find('li a[level="' + level + '"]').parent().show();

						 // sync attribute list
						 $(self.workspace.dimension_list.el).find('ul.d_hierarchy[hierarchy="' + hierarchy.name + '"] li a[level="' + level + '"]').parent()
						 .draggable('disable')
						 .parents('.parent_dimension')
						 .find('.folder_collapsed')
						 .addClass('selected');
						 }
						 }*/
						var selection = $('<li class="selection"></li>');
						selection.append(h);
						selection.appendTo($axis.find('ul.connectable'));
					}
                });
            }
            var measures = model.queryModel.details.measures || [];
            _.each(measures, function (measure) {
				if(self.workspace.dimension_list!=null) {
					var m = $(self.workspace.dimension_list.el).find('.measure_tree a.measure[measure="' + measure.name + '"]').parent();
					var m2 = m.clone().show();
					m2.appendTo($(self.el).find('.fields_list_body.details ul.connectable'));

					m.draggable('disable');
				}
            });

            this.update_dropzones();
        }
    },

    reset_dropzones: function() {
        var self = this;
        $(self.el).find('.fields_list_body ul.connectable').find('li.selection, li.d_measure').remove();
		if(self.workspace.dimension_list!=null) {
			$(self.workspace.dimension_list.el).find('li.ui-draggable-disabled').draggable('enable');
		}

        $(self.el).find('.fields_list[title="ROWS"] .limit').removeClass('on');
        $(self.el).find('.fields_list[title="COLUMNS"] .limit').removeClass('on');
        $(this.workspace.el).find('.fields_list_body .clear_axis').addClass('hide');
    },

    update_dropzones: function() {
        $(this.workspace.el).find('.fields_list_body').each(function(idx, ael) {
            var $axis = $(ael);
            if ($axis.find('ul.connectable li.selection, ul.connectable li.d_measure').length === 0) {
                $axis.siblings('.clear_axis').addClass('hide');
            } else {
                $axis.siblings('.clear_axis').removeClass('hide');
            }
        });

    },

    clear_axis: function(event) {
        event.preventDefault();

        var axisName = $(event.target).siblings('.fields_list_body').parent().attr('title');
        var axisData = this.workspace.query.helper.getAxis(axisName);
        var len = axisData ? axisData.hierarchies.length : 0;
        var isRemovedParameter = false;
        var hierarchy;
        var level;

        if (Settings.ALLOW_PARAMETERS) {
            for (var i = 0; i < len; i++) {
                for (var lName in axisData.hierarchies[i].levels) {
                    if (axisData.hierarchies[i].levels.hasOwnProperty(lName)) {
                        if (axisData.hierarchies[i].levels[lName].selection &&
                            axisData.hierarchies[i].levels[lName].selection['parameterName']) {

                            level = lName;
                            hierarchy = axisData.hierarchies[i].name;
                            this.workspace.query.helper.removeParameter(hierarchy, level);
                        }
                    }
                }

                if (i === (len - 1)) {
                    isRemovedParameter = true;
                    this.workspace.$el.find('.parameter_input').empty();
                }
            }
        }

        if (isRemovedParameter || !Settings.ALLOW_PARAMETERS || axisName === 'DETAILS') {
            if (axisName === 'DETAILS') {
                this.workspace.query.helper.clearMeasures();
            } else {
                this.workspace.query.helper.clearAxis(axisName);
            }

            // Trigger event when clear axis
            Saiku.session.trigger('workspaceDropZone:clear_axis', { workspace: this.workspace, axis: axisName });

            this.workspace.sync_query();
            this.workspace.query.run();
            return false;
        }
    },

    select_dimension: function(event, ui) {
        var self = this;
        // if we drop to remove dont execute the following

        // Trigger event when select dimension
        Saiku.session.trigger('workspaceDropZone:select_dimension', { workspace: this.workspace });

        if ($(ui.item).is(':visible')) {
            $(self.el).find('.axis_fields ul.hierarchy').each( function(index, element) {
                $(element).find('li.temphide').show().removeClass('temphide');
            });

            var hierarchy = ui.item.find('.level').attr('hierarchy');
            var indexHierarchy = -1;
            ui.item.parents('ul.connectable').find('li.selection').each( function(index, element) {
                if ( $(element).find('ul.hierarchy').attr('hierarchy') == hierarchy) {
                    indexHierarchy = index;
                }
            });

            var toAxis = ui.item.parents('.axis_fields').parent().attr('title');
            var fromAxis = $(event.target).parents('.axis_fields').parent().attr('title');
            var isNew = ui.item.hasClass('d_level');
            var isCalcMember = ui.item.hasClass('dimension-level-calcmember');

            var level;
            var uniqueName;
            if (isCalcMember) {
                /*uniqueName = ui.item.find('a.level').attr('uniquename');
                this.workspace.toolbar.$el.find('.group_parents').removeClass('on');
                this.workspace.toolbar.group_parents();
                this.workspace.query.helper.includeLevelCalculatedMember(toAxis, hierarchy, level, uniqueName,
                 indexHierarchy);*/
            }
            else {
                if (isNew) {
                    level = ui.item.find('a.level').attr('level');
                    this.workspace.query.helper.includeLevel(toAxis, hierarchy, level, indexHierarchy);
                } else {
                    self.workspace.query.helper.moveHierarchy(fromAxis, toAxis, hierarchy, indexHierarchy);
                }
            }

            $(ui.item).detach();
            this.workspace.sync_query();
            self.workspace.query.run();
            Saiku.events.trigger("workspaceDropZone:select_dimension", this,
                {level: level, uniquename: uniqueName, toAxis: toAxis, isNew: isNew, isCalc: isCalcMember, hierarchy:hierarchy});
            return;
        }
        return;
    },

	find_type_time: function (dimension, hierarchy, level) {
        if (this.workspace.metadata === undefined) {
            this.workspace.metadata = Saiku.session.sessionworkspace.cube[this.workspace.selected_cube];
        }
		var metadata = this.workspace.metadata.attributes.data,
			value = {};
		value.dimensions = _.findWhere(metadata.dimensions, { name: dimension });
		if (hierarchy === undefined) {
			hierarchy = dimension;
		}
		value.hierarchies = _.findWhere(value.dimensions.hierarchies, { name: hierarchy });
		if (value.hierarchies === undefined || value.hierarchies === null) {
			value.hierarchies = _.findWhere(value.dimensions.hierarchies, { name: dimension + '.' + hierarchy });
		}
		value.level = _.findWhere(value.hierarchies.levels, { name: level });
		if(value.level === null || value.level === undefined) {
			value.level = _.findWhere(value.hierarchies.levels, { caption: level });
		}
		return value;
	},

    selections: function(event, ui) {
        if (event) {
            event.preventDefault();
        }

        // Determine dimension
        var $target = $(event.target).hasClass('d_level') ?
            $(event.target).find('.level') :
            $(event.target);
		var dimension = $target.attr('hierarchy').replace(/[\[\]]/gi, '').split('.')[0],
			hierarchy = $target.attr('hierarchy').replace(/[\[\]]/gi, '').split('.')[1]
				? $target.attr('hierarchy').replace(/[\[\]]/gi, '').split('.')[1]
				: $target.attr('hierarchy').replace(/[\[\]]/gi, '').split('.')[0],
            level = $target.attr('level'),
            objData = this.find_type_time(dimension, hierarchy, level),
            dimHier = $target.attr('hierarchy'),
            key = $target.attr('href').replace('#', '');

        // Fetch available members
        this.member = new Member({}, {
            cube: this.workspace.selected_cube,
            dimension: key
        });

        var hName = decodeURIComponent(this.member.hierarchy),
            memberHierarchy = this.workspace.query.helper.getHierarchy(hName),
            memberLevel;

        if (memberHierarchy && memberHierarchy.levels.hasOwnProperty(level)) {
            memberLevel = memberHierarchy.levels[level];
        }

        if ((objData.level && objData.level.annotations !== undefined && objData.level.annotations !== null) &&
           (objData.level.annotations.AnalyzerDateFormat !== undefined || objData.level.annotations.SaikuDayFormatString !== undefined) &&
           ((_.has(memberLevel, 'selection') && memberLevel.selection.members.length === 0) ||
           ((_.size(memberLevel) === 1 && _.has(memberLevel, 'name')) || (_.has(memberLevel, 'mdx') && memberLevel.mdx) ||
           (_.size(memberLevel) === 2 && _.has(memberLevel, 'name') && _.has(memberLevel, 'mdx'))))) {

            // Launch date filter dialog
            (new DateFilterModal({
                dimension: dimension,
                hierarchy: hierarchy,
                target: $target,
                name: $target.attr('level'),
                data: objData,
                analyzerDateFormat: objData.level.annotations.AnalyzerDateFormat,
                dimHier: dimHier,
                key: key,
                workspace: this.workspace
            })).open();
        }
        else {
            // Launch selections dialog
            (new SelectionsModal({
                target: $target,
                name: $target.text(),
                key: key,
                workspace: this.workspace
            })).open();
        }

        return false;
    },

	measure_action: function(event) {
		var self = this;

		if (typeof this.workspace.query == "undefined" || this.workspace.query.model.type != "QUERYMODEL" || Settings.MODE == "view") {
			return false;
		}

		var $target  = $(event.target).hasClass('limit') ? $(event.target) : $(event.target).parent();
        var query    = self.workspace.query;
        var cube     = self.workspace.selected_cube;
        var details  = query.helper.model().queryModel.details;
        var measures = details.measures;

		var menuitems = {
			"HEADER": {name: "Position", disabled:true, i18n: true },
			"sep1": "---------",
			"BOTTOM_COLUMNS": {name: "Columns | Measures", i18n: true },
			"TOP_COLUMNS": {name: "Measures | Columns", i18n: true },
			"BOTTOM_ROWS": {name: "Rows | Measures", i18n: true },
			"TOP_ROWS": {name: "Measures | Rows", i18n: true },
			"sep2": "---------",
			"reset": {name: "Reset Default", i18n: true },
			"cancel": {name: "Cancel", i18n: true }
		};

		$.each(menuitems, function(key, item){
			recursive_menu_translate(item, Saiku.i18n.po_file);
		});

		$.contextMenu('destroy', '.limit');

		$.contextMenu({
			appendTo: $target,
			selector: '.limit',
			ignoreRightClick: true,
			build: function($trigger, e) {
				return {
					callback: function(key, options) {
						if (key === "cancel") {
							return;
						} else if ( key === "reset") {
							details.location = SaikuOlapQueryTemplate.queryModel.details.location;
							details.axis = SaikuOlapQueryTemplate.queryModel.details.axis;
                        } else {
							var location = key.split('_')[0];
							var axis = key.split('_')[1];
							details.location = location;
							details.axis = axis;
						}
						query.run();
					},
					items: menuitems
				}
			}
		});
		$target.contextMenu();
	},

    limit_axis: function(event) {
        var self = this;

        if (typeof this.workspace.query == "undefined" || this.workspace.query.model.type != "QUERYMODEL" || Settings.MODE == "view") {
            return false;
        }

        var $target =  $(event.target).hasClass('limit') ? $(event.target) : $(event.target).parent();
        var $axis = $target.siblings('.fields_list_body');
        var target = $axis.parent().attr('title');

        $.contextMenu('destroy', '.limit');
        $.contextMenu({
            appendTo: $target,
            selector: '.limit',
            ignoreRightClick: true,
            build: function($trigger, e) {
                var query = self.workspace.query;
                var cube = self.workspace.selected_cube;
                var items = {};
                var measures = Saiku.session.sessionworkspace.cube[cube].get('data').measures;
                var a = self.workspace.query.helper.getAxis(target);
                var hierarchies = a.hierarchies;

                var func, n, sortliteral, filterCondition, sortOrder, sortOrderLiteral, sortHl, topHl, filterHl, totalFunction;
                var isFilter = false, isSort = false, isTop = false;

                if (a && a.filters) {
                    _.each(a.filters, function(filter) {
                        if (filter.flavour == "N") {
                            func = a["function"];
                            n = filter.expressions[0];
                            sortliteral = filter.expressions[1];
                            isTop = true;
                        }
                        if (filter.flavour == "Generic") {
                            filterCondition = filter.expressions[0];
                            isFilter = true;
                        }
                    });
                }

                if (a && a.sortOrder) {
                    sortOrder = a.sortOrder;
                    sortOrderLiteral = a.sortEvaluationLiteral;
                    isSort = true;
                }

                if (a && a.aggregators && a.aggregators.length > 0) {
                    totalFunction = a.aggregators[0];
                }

                if (func !== null && sortliteral === null) {
                    topHl = func + "###SEPARATOR###" + n;
                } else if (func !== null && sortliteral !== null && n !== null) {
                    topHl = "custom";
                }

                if (isSort && sortOrder !== null) {
                    sortHl = "customsort";
                }

                _.each(measures, function(measure) {
                    items[measure.uniqueName] = {
                        name: measure.caption,
                        payload: {
                            "n"     : 10,
                            "sortliteral"    : measure.uniqueName
                        }
                    };
                });

                _.each(hierarchies, function(h){
                    _.each(h.levels, function(l){
                        items[h.name] = {
                            name: h.caption,
                            payload: {
                                "n": 10,
                                "sortliteral": h.name+".["+l.name+"].CURRENTMEMBER.ORDERKEY"
                            }
                        }
                    });
                });

                var levels = [];

				_.each(a.hierarchies, function(hierarchy){
                    for(var property in hierarchy.levels){
                        var n = "";

                        if(hierarchy.levels[property].caption!=null){
                            n = hierarchy.levels[property].caption;
                        } else{
                            n = hierarchy.levels[property].name;
                        }

                        levels[hierarchy.levels[property].name] = {
                            name: n
                        }
                    }
                });

                var addFun = function(items, fun) {
                    var ret = {};
                    for (var key in items) {
                        ret[ (fun + '###SEPARATOR###'+ key) ] = _.clone(items[key]);
                        ret[ (fun + '###SEPARATOR###' + key) ].fun = fun;
                        if (fun == func && sortliteral == key && items[key].payload.n == n) {
                            topHl = fun + "Quick";
                            ret[ (fun + '###SEPARATOR###' + key) ].name =
                                    "<b>" + items[key].name + "</b>";
                        }
                        if (fun == sortOrder && sortOrderLiteral == key) {
                            sortHl = fun + "Quick";
                            ret[ (fun + '###SEPARATOR###' + key) ].name =
                                    "<b>" + items[key].name + "</b>";
                        }
                    }
                    return ret;
                };

                var citems = {
                        "filter" : {name: "Filter", i18n: true, items:
                         {
                                "customfilter": {name: "Custom...", i18n: true },
                                "clearfilter": {name: "Clear Filter", i18n: true }
                         }},
                        "limit" : {name: "Limit", i18n: true, items:
                        {
                                "TopCount###SEPARATOR###10": {name: "Top 10", i18n: true },
                                "BottomCount###SEPARATOR###10": {name: "Bottom 10", i18n: true },
                                "TopCountQuick" : { name: "Top 10 by...", i18n: true, items: addFun(items, "TopCount") },
                                "BottomCountQuick" : { name: "Bottom 10 by...", i18n: true, items: addFun(items, "BottomCount") },
                                "customtop" : {name: "Custom Limit...", i18n: true },
                                "clearlimit" : {name: "Clear Limit", i18n: true }
                         }},
                        "sort" : {name: "Sort", i18n: true, items:
                        {
                            "ASCQuick": {name: "Ascending" , i18n: true, items: addFun(items, "ASC") },
                            "DESCQuick": {name: "Descending", i18n: true, items: addFun(items, "DESC")},
                            "BASCQuick": {name: "Ascending (Breaking Hierarchy)", i18n: true, items: addFun(items, "BASC")},
                            "BDESCQuick": {name: "Descending (Breaking Hierarchy)", i18n: true, items: addFun(items, "BDESC") },
                            "customsort" : { name: "Custom...", i18n: true },
                            "clearsort" : {name: "Clear Sort", i18n: true }
                        }},
                        "fold_totals": {name: "Totals", i18n: true, items:
                        {
                            "grand_totals" : {name: "All", i18n: true, items:
                            {
                                "show_totals_not": {name: "None", i18n: true},
                                "show_totals_sum": {name: "Sum", i18n: true},
                                "show_totals_min": {name: "Min", i18n: true},
                                "show_totals_max": {name: "Max", i18n: true},
                                "show_totals_avg": {name: "Avg", i18n: true}
                            }}
                        }},
                        "cancel" : { name: "Cancel", i18n: true }

                };

                var selectedMeasures = query.helper.model().queryModel.details.measures;
                _.each(selectedMeasures, function(measure) {
                    var foldName = 'fold_' + measure.name.replace(/\s/g, '_').toLowerCase();
                    var fold = {name: measure.name, items: {}};

                    // Also applying a bold style to per measure's selected aggregation total

                    fold.items["show_totals_nil_" + measure.name] = {name: self.formatAggregatorName("None", "nil", a, measure), i18n: true};
                    fold.items["show_totals_sum_" + measure.name] = {name: self.formatAggregatorName("Sum",  "sum", a, measure), i18n: true};
                    fold.items["show_totals_min_" + measure.name] = {name: self.formatAggregatorName("Min",  "min", a, measure), i18n: true};
                    fold.items["show_totals_max_" + measure.name] = {name: self.formatAggregatorName("Max",  "max", a, measure), i18n: true};
                    fold.items["show_totals_avg_" + measure.name] = {name: self.formatAggregatorName("Avg",  "avg", a, measure), i18n: true};

                    citems.fold_totals.items[foldName] = fold;
                });

                $.each(citems, function(key, item){
                    recursive_menu_translate(item, Saiku.i18n.po_file);
                });

                // Applying a bold style to the selected aggregation total
                var totalItems = citems.fold_totals.items.grand_totals.items;
                if (totalFunction) {
                    for (var key in totalItems) {
                        if (key.substring("show_totals_".length) == totalFunction) {
                            totalItems[key].name = "<b>" + totalItems[key].name + "</b";
                        }
                    }
                } else {
                    totalItems.show_totals_not.name = "<b>" + totalItems.show_totals_not.name + "</b";
                }

                items["10"] = {
                   payload: { "n" : 10 }
                };

                if (isFilter) {
                    var f = citems.filter;
                    f.name = "<b>" + f.name + "</b>";
                    f.items.customfilter.name = "<b>" + f.items.customfilter.name + "</b>";
                }

                if (isSort) {
                    var s = citems.sort.items;
                    citems.sort.name = "<b>" + citems.sort.name + "</b>";
                    if (sortHl in s) {
                        s[sortHl].name = "<b>" + s[sortHl].name + "</b>";
                    }
                }

                if (isTop) {
                    var t = citems.limit.items;
                    citems.limit.name = "<b>" + citems.limit.name + "</b>";
                    if (topHl in t) {
                        t[topHl].name = "<b>" + t[topHl].name + "</b>";
                    }
                }

                return {
                    callback: function(key, options) {
                            var save_custom,
                                save_customsort;

                            if (key == "cancel") {
                                return;
                            }

                            if (key == "clearfilter") {
                                $target.removeClass('on');
                                self.workspace.query.helper.removeFilter(a, 'Generic');
                                self.synchronize_query();
                                self.workspace.query.run();
                            } else if (key == "customfilter") {
                                save_custom = function(filterCondition) {
                                    var expressions = [];
                                    expressions.push(filterCondition);

                                    self.workspace.query.helper.removeFilter(a, 'Generic');
                                    a.filters.push(
                                        {   "flavour" : "Generic",
                                            "operator": null,
                                            "function" : "Filter",
                                            "expressions": expressions
                                        });
                                    self.synchronize_query();
                                    self.workspace.query.run();
                                };

                                 (new FilterModal({
                                    axis: target,
                                    success: save_custom,
                                    query: self.workspace.query,
                                    expression: filterCondition,
                                    expressionType: "Filter",
                                     workspace: self.workspace
                                })).render().open();

                            } else if (key == "stringfilter") {
                                save_custom = function(filterCondition, matchtype, filtervalue) {
                                    filterCondition+='.CurrentMember.Name MATCHES ("(?i).*'+filtervalue+'.*")'
                                    var expressions = [];
                                    expressions.push(filterCondition);

                                    self.workspace.query.helper.removeFilter(a, 'Generic');
                                    a.filters.push(
                                        {   "flavour" : "Generic",
                                            "operator": null,
                                            "function" : "Filter",
                                            "expressions": expressions
                                        });
                                    self.synchronize_query();
                                    self.workspace.query.run();
                                };

                                (new StringFilterModal({
                                    axis: target,
                                    success: save_custom,
                                    query: self.workspace.query,
                                    expression: filterCondition,
                                    expressionType: "Filter",
                                    workspace: self.workspace
                                })).render().open();

                            } else if (key == "clearlimit") {
                                $target.removeClass('on');
                                self.workspace.query.helper.removeFilter(a, 'N');
                                self.synchronize_query();
                                self.workspace.query.run();
                            } else if (key == "customtop") {

                                save_custom = function(fun, n, sortliteral) {
                                    var expressions = [];
                                    expressions.push(n);
                                    if (sortliteral) {
                                        expressions.push(sortliteral);
                                    }

                                    self.workspace.query.helper.removeFilter(a, 'N');
                                    a.filters.push(
                                        {   "flavour" : "N",
                                            "operator": null,
                                            "function" : fun,
                                            "expressions": expressions
                                        });
                                    self.synchronize_query();
                                    self.workspace.query.run();
                                };

                                 (new CustomFilterModal({
                                    axis: target,
                                    measures: measures,
                                    success: save_custom,
                                    query: self.workspace.query,
                                    func: func,
                                    n: n,
                                    sortliteral: sortliteral
                                })).render().open();
                            } else if (key == "customsort") {

                                save_customsort = function(sortO, sortL) {
                                    a.sortOrder = sortO;
                                    a.sortEvaluationLiteral = sortL;
                                    self.synchronize_query();
                                    self.workspace.query.run();
                                };

                                 (new FilterModal({
                                    axis: target,
                                    success: save_customsort,
                                    query: self.workspace.query,
                                    expression: sortOrderLiteral,
                                    expressionType: "Order"
                                })).render().open();
                            } else if (key == "clearsort") {
                                a.sortOrder = null;
                                a.sortEvaluationLiteral = null;
                                self.synchronize_query();
                                self.workspace.query.run();
                            } else if (key.indexOf("show_totals_") === 0){
                                var total = key.substring("show_totals_".length);
                                var tokens = total.split('_');

                                if (tokens.length > 1) { // Axis-specific totals
                                    total = tokens[0];
                                    var metric = key.substring(("show_totals_" + total).length + 1);

                                    _.each(selectedMeasures, function(m){
                                        if (metric === m.name) {
                                            if (!m.aggregators) {
                                                m.aggregators = [];
                                            } else if (m.aggregators.length > 0) {
                                                var aggIdx = -1;
                                                for (var i = 0; i < m.aggregators.length; i++) {
                                                    var aggInfo = m.aggregators[i];
                                                    if (aggInfo.indexOf('_') > 0) {
                                                        var aggInfoArray = aggInfo.split('_');
                                                        if (aggInfoArray[1] == a.location) {
                                                            aggIdx = i;
                                                            break;
                                                        }
                                                    }
                                                }
                                                if (aggIdx >= 0) {
                                                    m.aggregators.splice(aggIdx, 1);
                                                }
                                            }

                                            m.aggregators.push(total + '_' + a.location);
                                        }
                                    });

                                    if (!a.aggregators || a.aggregators.length == 0) {
                                        a.aggregators = [total];
                                    }
                                } else { // General totals
                                    var aggs = [];
                                    aggs.push(total);
                                    a.aggregators = aggs;
                                }


                                self.workspace.query.run();
                            } else {

                                var fun = key.split('###SEPARATOR###')[0];
                                var ikey = key.split('###SEPARATOR###')[1];
                                var method = "";
                                var data = {};
                                if (_.indexOf(["ASC", "BASC", "DESC", "BDESC"], fun) > -1) {
                                    a.sortOrder = fun;
                                    a.sortEvaluationLiteral = items[ikey].payload.sortliteral;

                                }
								else if(_.indexOf(["Param"], fun) > -1) {
									a.sortEvaluationLiteral = items[ikey].payload.sortliteral;
								}else {
                                    var expressions = [];
                                    expressions.push(items[ikey].payload.n);
                                    var sl = items[ikey].payload.sortliteral;
                                    if (sl) {
                                        expressions.push(sl);
                                    }

                                    self.workspace.query.helper.removeFilter(a, 'N');
                                    a.filters.push(
                                        {   "flavour" : "N",
                                            "operator": null,
                                            "function" : fun,
                                            "expressions": expressions
                                        });
                                }
                                self.synchronize_query();
                                self.workspace.query.run();
                            }
                    },
                    items: citems
                };
            }
        });
    $target.contextMenu();
    },
    formatAggregatorName: function(name, agg, a, measure) {
        if (measure.aggregators) {
            for (var i = 0; i < measure.aggregators.length; i++) {
                var tokens     = measure.aggregators[i].split('_');
                var aggregator = tokens[0];
                var axis       = tokens[1];

                if (agg == aggregator && axis == a.location) {
                    return '<b>' + name + '</b>';
                }
            }
        }
        return name;
    }
});
