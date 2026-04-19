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
 * Dialog for member selections
 */
var SelectionsModal = Modal.extend({
    type: "selections",

    paramvalue: null,

    buttons: [
        { text: "OK", method: "save" },
        { text: "Open Date Filter", method: "open_date_filter" },
        { text: "Cancel", method: "close" }
    ],

    events: {
        'click a': 'call',
        'click .search_term' : 'search_members',
        'click .clear_search' : 'clear_search',
        'change #show_unique': 'show_unique_action',
        'change #use_result': 'use_result_action',
        'dblclick .selection_options li.option_value label' : 'click_move_selection',
        'click li.all_options' : 'click_all_member_selection',
        'change #per_metrics_totals_checkbox': 'per_metrics_totals_action'
        //,'click div.updown_buttons a.form_button': 'updown_selection'
    },

    show_unique_option: false,
    per_metrics_totals: false,

    use_result_option: Settings.MEMBERS_FROM_RESULT,
    show_totals_option: [],
    members_limit: Settings.MEMBERS_LIMIT,
    members_search_limit: Settings.MEMBERS_SEARCH_LIMIT,
    members_search_server: false,
    selection_type: "INCLUSION",

    initialize: function(args) {
        // Initialize properties
        var self = this;
        _.extend(this, args);
        this.options.title = "<span class='i18n'>Selections for</span> " + this.name;
        this.message = "Fetching members...";
        this.query = args.workspace.query;
        this.selected_members = [];
        this.available_members = [];
        this.topLevel;

        _.bindAll(this, "fetch_members", "populate", "finished", "get_members", "use_result_action", "per_metrics_totals_action");

        // Determine axis
        this.axis = "undefined";
        if (args.axis) {
            this.axis = args.axis;
            if (args.axis == "FILTER") {
                this.use_result_option = false;
            }
        } else {
            if (args.target.parents('.fields_list_body').hasClass('rows')) {
                this.axis = "ROWS";
            }
            if (args.target.parents('.fields_list_body').hasClass('columns')) {
                this.axis = "COLUMNS";
            }
            if (args.target.parents('.fields_list_body').hasClass('filter')) {
                this.axis = "FILTER";
                this.use_result_option = false;
            }
        }
        // Resize when rendered
        this.bind('open', this.post_render);
        this.render();

        $(this.el).parent().find('.ui-dialog-titlebar-close').bind('click',this.finished);

        // Fetch available members
        this.member = new Member({}, {
            cube: args.workspace.selected_cube,
            dimension: args.key
        });

        // Load template
        $(this.el).find('.dialog_body')
            .html(_.template($("#template-selections").html())(this));


        var hName = this.member.hierarchy;
        var lName = this.member.level;
        var hierarchy = this.workspace.query.helper.getHierarchy(hName);
        var level = null;
        if (hierarchy && hierarchy.levels.hasOwnProperty(lName)) {
            level = hierarchy.levels[lName];
        }

        if ((this.source === 'DateFilterModal' && (_.has(level, 'selection') && level.selection.members.length === 0)) ||
            (this.source === 'DateFilterModal' && (_.size(level) === 1 && _.has(level, 'name')))) {
            this.$el.find('.dialog_footer a:nth-child(2)').show();
        }
        else {
            this.$el.find('.dialog_footer a:nth-child(2)').hide();
        }

        if (Settings.ALLOW_PARAMETERS) {
            if (level) {
                var pName = level.selection ? level.selection.parameterName : null;
                if (pName) {
                    $(this.el).find('#parameter').val(pName);

                    if(this.query.helper.model().parameters[pName]!=undefined) {
                        this.paramvalue = this.query.helper.model().parameters[pName].split(",");
                    }

                }
            }
            $(this.el).find('.parameter').removeClass('hidden');
        }

		this.init_totals(hierarchy, level);

        $(this.el).find('#use_result').attr('checked', this.use_result_option);
        $(this.el).find('.search_limit').text(this.members_search_limit);
        $(this.el).find('.members_limit').text(this.members_limit);

        var calcMembers = this.workspace.query.helper.getCalculatedMembers();

        if (calcMembers.length > 0) {
            this.fetch_calcmembers_levels();
        }
        else {
            this.get_members();
        }
    },

	init_totals: function (hierarchy, level) {
		var self = this;
		// Add the selections totals
		var measuresArray = self.workspace.query.model.queryModel.details.measures;
		for (var j = 0; j < measuresArray.length; j++) {
			$(this.el).find('#div-totals-container').append(_.template($("#template-selections-totals").html())({measure: measuresArray[j]}));
		}

		if (_.size(hierarchy.levels) > 1 && level && level.hasOwnProperty('aggregators') && level.aggregators && level.aggregators.length > 0) {
			this.show_totals_option = level.aggregators;
			if (_.uniq(this.show_totals_option).length > 1) {
				this.per_metrics_totals_action();
			}
		} else {
			this.show_totals_option = [];
		}

		if (this.show_totals_option.length > 0) {
			if (this.per_metrics_totals) {
				$(this.el).find('.show_totals_select').each(function (index) {
					if (typeof self.show_totals_option[index] !== 'undefined') {
						$(this).val(self.show_totals_option[index]);
					}
				});
			} else {
				$(this.el).find('#all_measures_select').val(self.show_totals_option[0])
			}
		}
		$(this.el).find('#per_metrics_totals_checkbox').attr('checked', this.per_metrics_totals);

	},

    open_date_filter: function(event) {
        event.preventDefault();

        // Launch date filter dialog
        (new DateFilterModal({
            dimension: this.objDateFilter.dimension,
            hierarchy: this.objDateFilter.hierarchy,
            target: this.target,
            name: this.name,
            data: this.objDateFilter.data,
            analyzerDateFormat: this.objDateFilter.analyzerDateFormat,
            dimHier: this.objDateFilter.dimHier,
            key: this.key,
            workspace: this.workspace
        })).open();

        this.$el.dialog('destroy').remove();
    },

    per_metrics_totals_action: function(event) {
        this.per_metrics_totals = ! this.per_metrics_totals;

        if(this.per_metrics_totals === true) {
			$(this.el).find('.per_metrics_container').show();
			$(this.el).find('.all_metrics_container').hide();
		} else {
			$(this.el).find('.per_metrics_container').hide();
			$(this.el).find('.all_metrics_container').show();
		}
    },

    get_members: function() {
            var self = this;
            var path = "/result/metadata/hierarchies/" + encodeURIComponent(this.member.hierarchy) + "/levels/" + encodeURIComponent(this.member.level);
            this.search_path = path;

            var message = '<span class="processing_image">&nbsp;&nbsp;</span> <span class="i18n">' + self.message + '</span> ';
            self.workspace.block(message);

        /**
         * gett isn't a typo, although someone should probably rename that method to avoid confusion.
         */
            this.workspace.query.action.gett(path, {
                success: this.fetch_members,
                error: function() {
                    self.workspace.unblock();
                },
                data: {result: this.use_result_option, searchlimit: this.members_limit }});
    },

    clear_search: function() {
        $(this.el).find('.filterbox').val('');
        this.available_members = [];
        this.get_members();
    },

    search_members: function() {
        var self = this;
        var search_term = $(this.el).find('.filterbox').val();
        if (!search_term)
            return false;

        var message = '<span class="processing_image">&nbsp;&nbsp;</span> <span class="i18n">Searching for members matching:</span> ' + search_term;
        self.workspace.block(message);
        self.workspace.query.action.gett(self.search_path, {
                async: false,

                success: function(response, model) {
                                if (model && model.length > 0) {
                                    self.available_members = model;
                                }
                                self.populate();
                            },
                error: function () {
                    self.workspace.unblock();
                },
                data: { search: search_term, searchlimit: self.members_search_limit }
        });
    },

    fetch_calcmembers_levels: function() {
        var dimHier = this.member.hierarchy.split('].[');
        var m4=true;
        if(dimHier.length===1){
            m4=false;
            dimHier = this.member.hierarchy.split('.');

        }
        if(dimHier.length>1){
            var hName = dimHier[1].replace(/[\[\]]/gi, '');
        }
        var dName = dimHier[0].replace(/[\[\]]/gi, '');


        var message = '<span class="processing_image">&nbsp;&nbsp;</span> <span class="i18n">' + this.message + '</span> ';
        this.workspace.block(message);

        if(!m4){
            if(hName!=undefined) {
                hName = dName + "." + hName;
            }
            else{
                hName = dName;
            }
        }
        var level = new Level({}, {
            ui: this,
            cube: this.workspace.selected_cube,
            dimension: dName,
            hierarchy: hName
        });

        level.fetch({
            success: this.get_levels
        });
    },

    get_levels: function(model, response) {
        if (response && response.length > 0) {
            model.ui.topLevel = response[0];
            model.ui.get_members();
        }
    },

    get_calcmembers: function() {
        var self = this;
        var hName = this.member.hierarchy;
        var calcMembers = this.workspace.query.helper.getCalculatedMembers();
        var arrCalcMembers = [];

        if (this.topLevel.name === this.member.level) {
            _.filter(calcMembers, function(value) {
                if (value.hierarchyName === hName && _.isEmpty(value.parentMember)) {
                    value.uniqueName = value.hierarchyName + '.[' + value.name + ']';
                    arrCalcMembers.push(value);
                }
            });
        }
        else {
            _.filter(calcMembers, function(value) {
                if (value.hierarchyName === hName && value.parentMemberLevel === self.member.level) {
                    value.uniqueName = value.parentMember + '.[' + value.name + ']';
                    arrCalcMembers.push(value);
                }
            });
        }

        return arrCalcMembers;
    },

    fetch_members: function(model, response) {
        var self = this;
        if (response && response.length > 0) {
            _.each(response, function(f){
                var cmem = self.workspace.query.helper.getCalculatedMembers();
                var calc = false;
                if(cmem && cmem.length>0){
                    _.each(cmem, function(c){
                        if(c.uniqueName === f.uniqueName){
                            calc = true;
                        }
                    })
                }
                self.available_members.push({obj:f, calc:calc});

            });
        }
        this.populate();
    },

    populate: function(model, response) {
        var self = this;
        self.workspace.unblock();
        this.members_search_server = (this.available_members.length >= this.members_limit || this.available_members.length == 0);

        self.show_unique_option = false;
        $(this.el).find('.options #show_unique').attr('checked',false);

        var calcMembers = this.workspace.query.helper.getCalculatedMembers();

        if (calcMembers.length > 0) {
            var newCalcMembers = this.get_calcmembers();
            var len = newCalcMembers.length;

            for (var i = 0; i < len; i++) {
                var calc = false;
                if(calcMembers && calcMembers.length>0){
                    _.each(calcMembers, function(c){
                        if(c.uniqueName === newCalcMembers[i].uniqueName){
                            calc = true;
                        }
                    })
                }
                this.available_members.push({obj:newCalcMembers[i], calc:calc});
            }
        }

        $(this.el).find('.items_size').text(this.available_members.length);

        if (this.members_search_server) {
            $(this.el).find('.warning').text("More items available than listed. Pre-Filter on server.");
        } else {
            $(this.el).find('.warning').text("");
        }

        var hName = self.member.hierarchy;
        var lName = self.member.level;
        var hierarchy = self.workspace.query.helper.getHierarchy(hName);

        if (hierarchy && hierarchy.levels.hasOwnProperty(lName)) {
            this.selected_members = [];
            if(hierarchy.levels[lName].selection){
                _.each(hierarchy.levels[lName].selection.members, function(f){
                    var cmem = self.workspace.query.helper.getCalculatedMembers();
                    var calc = false;

                    if(cmem && cmem.length > 0){
                        _.each(cmem, function(c){
                            if(c.uniqueName === f.uniqueName){
                                calc = true;
                            }
                        });
                    }

                    self.selected_members.push({obj: f, calc: calc});
                });
            }
            this.selection_type = hierarchy.levels[lName].selection ? hierarchy.levels[lName].selection.type : "INCLUSION";
        }

        var used_members = [];

        // Populate both boxes
        for (var j = 0, len = this.selected_members.length; j < len; j++) {
                var member = this.selected_members[j];
                used_members.push(member.obj.caption);
        }

        if ($(this.el).find('.used_selections .selection_options li.option_value' ).length == 0) {
            var selectedMembers = $(this.el).find('.used_selections .selection_options');
            selectedMembers.empty();
            var selectedHtml = _.template($("#template-selections-options").html())({ options: this.selected_members });
            $(selectedMembers).html(selectedHtml);
        }

        // Filter out used members
        this.available_members = _.select(this.available_members, function(o) {
			return used_members.indexOf(o.obj ? o.obj.caption : o.caption) === -1;
        });

        if (this.available_members.length > 0) {
            var availableMembersSelect = $(this.el).find('.available_selections .selection_options');
            availableMembersSelect.empty();
            var selectedHtml = _.template($("#template-selections-options").html())({ options: this.available_members });
            $(availableMembersSelect).html(selectedHtml);
        }

        if ($(self.el).find( ".selection_options.ui-selectable" ).length > 0) {
            $(self.el).find( ".selection_options" ).selectable( "destroy" );
        }

        $(self.el).find( ".selection_options" ).selectable({ distance: 20, filter: "li", stop: function( event, ui ) {
            $(self.el).find( ".selection_options li.ui-selected input").each(function(index, element) {
                if (element && element.hasAttribute('checked')) {
                    element.checked = true;
                } else {
                    $(element).attr('checked', true);
                }
                $(element).parents('.selection_options').find('li.all_options input').prop('checked', true);
            });
            $(self.el).find( ".selection_options li.ui-selected").removeClass('ui-selected');
        }});

        $(this.el).find('.filterbox').autocomplete({
            minLength: 1, //(self.members_search_server ? 2 : 1),
            delay: 200, //(self.members_search_server ? 400 : 300),
            appendTo: ".autocomplete",
            source: function(request, response ) {
                var searchlist = self.available_members;
                var search_target = self.show_unique_option == false ? "caption" : "name";
                var result =  $.map( searchlist, function( item ) {
                    var st = item.obj;
                    var obj;

                    if(st === undefined){
                        st = item;
                        obj = st[search_target];
                    } else{
                        obj = st.caption;
                    }

                    if (obj.toLowerCase().indexOf(request.term.toLowerCase()) > -1) {
                        var label = self.show_unique_option == false? st.caption : st.uniqueName;
                        var value = self.show_unique_option == false? st.uniqueName : st.caption;

                        return {
                            label: label,
                            value: value
                        };
                    }
                });

                response(result);
            },
            select:  function(event, ui) {
                var value = encodeURIComponent(ui.item.value);
                var label = ui.item.label;
                var searchVal = self.show_unique_option == false? ui.item.value : ui.item.label;
                var cap = self.show_unique_option == false? ui.item.label : ui.item.value;

                $(self.el).find('.available_selections .selection_options input[value="' + encodeURIComponent(searchVal) + '"]').parent().remove();
                $(self.el).find('.used_selections .selection_options input[value="' + encodeURIComponent(searchVal) + '"]').parent().remove();

                var option = '<li class="option_value"><input type="checkbox" class="check_option" value="'
                    +  encodeURIComponent(searchVal) + '" label="' + encodeURIComponent(cap)  + '">' + label + '</input></li>';

                $(option).appendTo($(self.el).find('.used_selections .selection_options ul'));
                $(self.el).find('.filterbox').val('');
                ui.item.value = "";

            },
            close: function(event, ui) {
                //$('#filter_selections').val('');
                //$(self.el).find('.filterbox').css({ "text-align" : " left"});
            },
            open: function( event, ui ) {
                //$(self.el).find('.filterbox').css({ "text-align" : " right"});
            }
        });

        $(this.el).find('.filterbox').autocomplete("enable");
        if (this.selection_type === "EXCLUSION") {
            $(this.el).find('.selection_type_inclusion').prop('checked', false);
            $(this.el).find('.selection_type_exclusion').prop('checked', true);
        } else {
            $(this.el).find('.selection_type_inclusion').prop('checked', true);
            $(this.el).find('.selection_type_exclusion').prop('checked', false);
        }

        // Translate
        Saiku.i18n.translate();
        // Show dialog
        Saiku.ui.unblock();
    },

    post_render: function(args) {
        var left = ($(window).width() - 1000)/2;
        var width = $(window).width() < 1040 ? $(window).width() : 1040;
        $(args.modal.el).parents('.ui-dialog')
            .css({ width: width, left: "inherit", margin:"0", height: 630 })
            .offset({ left: left});

        $('#filter_selections').attr("disabled", false);
        $(this.el).find('a[href=#save]').focus();
        $(this.el).find('a[href=#save]').blur();
    },

    move_selection: function(event) {
        event.preventDefault();
        var action = $(event.target).attr('id');
        var $to = action.indexOf('add') !== -1 ?
            $(this.el).find('.used_selections .selection_options ul') :
            $(this.el).find('.available_selections .selection_options ul');
        var $from = action.indexOf('add') !== -1 ?
            $(this.el).find('.available_selections .selection_options ul') :
            $(this.el).find('.used_selections .selection_options ul');
        var $els = action.indexOf('all') !== -1 ?
            $from.find('li.option_value input').parent() : $from.find('li.option_value input:checked').parent();
        $els.detach().appendTo($to);
        $(this.el).find('.selection_options ul li.option_value input:checked').prop('checked', false);
        $(this.el).find('.selection_options li.all_options input').prop('checked', false);
    },

    updown_selection: function(event) {
        event.preventDefault();
        return false;
        /*
        var action = $(event.target).attr('href').replace('#','');
        if (typeof action != "undefined") {
            if ("up" == action) {
                $(this.el).find('.used_selections option:selected').insertBefore( $('.used_selections option:selected:first').prev());
            } else if ("down" == action) {
                $(this.el).find('.used_selections option:selected').insertAfter( $('.used_selections option:selected:last').next());
            }

        }
        */
    },

    click_all_member_selection: function(event, ui) {
        var checked = $(event.currentTarget).find('input').is(':checked');
        if (!checked) {
            $(event.currentTarget).parent().find('li.option_value input').removeAttr('checked');
        } else {
            $(event.currentTarget).parent().find('li.option_value input').prop('checked', true);
        }

    },

    click_move_selection: function(event, ui) {
      event.preventDefault();
      var to = ($(event.target).parent().parent().parent().parent().hasClass('used_selections')) ? '.available_selections' : '.used_selections';
      $(event.target).parent().appendTo($(this.el).find(to +' .selection_options ul'));
    },

    show_unique_action: function() {
        var self = this;
        this.show_unique_option= ! this.show_unique_option;

        if(this.show_unique_option === true) {
            $(this.el).find('.available_selections, .used_selections').addClass('unique');
            $(this.el).find('.available_selections, .used_selections').removeClass('caption');
        } else {
            $(this.el).find('.available_selections, .used_selections').addClass('caption');
            $(this.el).find('.available_selections, .used_selections').removeClass('unique');
        }

    },

    use_result_action: function() {
        this.use_result_option = !this.use_result_option;
        this.get_members();
    },

    save: function() {
        this.query.updatedSelectionFromModal = true;
        var self = this;
        // Notify user that updates are in progress
        var $loading = $("<div>Saving...</div>");
        $(this.el).find('.dialog_body').children().hide();
        $(this.el).find('.dialog_body').prepend($loading);
        var show_u = this.show_unique_option;

        var hName = decodeURIComponent(self.member.hierarchy);
        var lName = decodeURIComponent(self.member.level)
        var hierarchy = self.workspace.query.helper.getHierarchy(hName);


        // Determine updates
        var updates = [];

        // If no selections are used, add level
        if ($(this.el).find('.used_selections input').length === 0) {
            // nothing to do - include all members of this level
        } else {
            self.workspace.query.helper.removeAllLevelCalculatedMember(hName);

            // Loop through selections
            $(this.el).find('.used_selections .option_value input')
                .each(function(i, selection) {
                    var value = $(selection).val();
                    if($(selection).hasClass("cmember")){
                        var caption = $(selection).attr('label');

                        self.workspace.toolbar.group_parents();

                        self.workspace.query.helper.includeLevelCalculatedMember(self.axis, hName, lName, decodeURIComponent(value), 0);
                        updates.push({
                            uniqueName: decodeURIComponent(value),
                            caption: decodeURIComponent(caption),
                            type: "calculatedmember"
                        });
                    }
                    else {
                        var caption = $(selection).attr('label');
                        updates.push({
                            uniqueName: decodeURIComponent(value),
                            caption: decodeURIComponent(caption)
                        });
                    }

            });
        }


        var parameterName = $('#parameter').val();
        if (hierarchy && hierarchy.levels.hasOwnProperty(lName)) {
                var totalsArray = [];

                if(this.per_metrics_totals === true) {
                    $('.show_totals_select').each(function() {
                        totalsArray.push($(this).val());
                    });
                } else {
                    var measuresArray = self.workspace.query.model.queryModel.details.measures;
                    for (var j = 0; j < measuresArray.length; j++) {
                        totalsArray.push($('#all_measures_select').val());
                    }
                }

                hierarchy.levels[lName]["aggregators"] = totalsArray;

                var selectionType = $(self.el).find('input.selection_type:checked').val();
                selectionType = selectionType ? selectionType : "INCLUSION";
                hierarchy.levels[lName].selection = { "type": selectionType, "members": updates };
                if (Settings.ALLOW_PARAMETERS && parameterName) {
                    hierarchy.levels[lName].selection["parameterName"] = parameterName;
                    var parameters = self.workspace.query.helper.model().parameters;
                    if (!parameters[parameterName]) {
                    //    self.workspace.query.helper.model().parameters[parameterName] = "";
                    }


                }

        }

        this.finished();
    },

    finished: function() {
        $('#filter_selections').remove();
        this.available_members = null;
        $(this.el).find('.filterbox').autocomplete('destroy').remove();
        $(this.el).dialog('destroy');
        $(this.el).remove();
        this.query.run();
    }
});

