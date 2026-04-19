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
 * The workspace toolbar, and associated actions
 */
var WorkspaceToolbar = Backbone.View.extend({
    enabled: false,
    events: {
        'click a': 'call'
    },

    initialize: function(args) {
        // Keep track of parent workspace
        this.workspace = args.workspace;

        // Maintain `this` in callbacks
        _.bindAll(this, "call", "reflect_properties", "run_query",
            "swap_axes_on_dropzones", "display_drillthrough","clicked_cell_drillthrough_export",
			"clicked_cell_drillacross","clicked_cell_drillthrough","activate_buttons", "switch_to_mdx","post_mdx_transform", "toggle_fields_action", "group_parents");
        this.workspace.bind('workspace:toolbar:render', this.translate);

        // Redraw the toolbar to reflect properties
        this.workspace.bind('properties:loaded', this.reflect_properties);

        // Fire off workspace event
        this.workspace.trigger('workspace:toolbar:render', {
            workspace: this.workspace
        });

        // Activate buttons when a new query is created or run
        this.workspace.bind('query:new', this.activate_buttons);
        this.workspace.bind('query:result', this.activate_buttons);

    },

    activate_buttons: function(args) {
        if (args !== null && args.data && args.data.cellset && args.data.cellset.length > 0 ) {
            $(args.workspace.toolbar.el).find('.button')
                .removeClass('disabled_toolbar');

            $(args.workspace.el).find("td.data").removeClass('cellhighlight').unbind('click');
            $(args.workspace.el).find(".table_mode").removeClass('on');

        } else {
            $(args.workspace.toolbar.el).find('.button')
                .addClass('disabled_toolbar').removeClass('on');
            $(args.workspace.el).find('.fields_list .disabled_toolbar').removeClass('disabled_toolbar');
            $(args.workspace.toolbar.el)
                .find('.about, .new, .open, .save, .edit, .run,.auto,.non_empty,.toggle_fields,.toggle_sidebar,.switch_to_mdx, .mdx')
                .removeClass('disabled_toolbar');
        }

        this.reflect_properties();

    },

    template: function() {
        var template = $("#template-workspace-toolbar").html() || "";
        return _.template(template)();
    },

    render: function() {
        $(this.el).html(this.template());

        return this;
    },

    translate: function() {
        //Saiku.i18n.translate();
    },
    call: function(event) {
        // Determine callback
        event.preventDefault();
        var callback = event.target.hash.replace('#', '');

        // Attempt to call callback
        if (! $(event.target).hasClass('disabled_toolbar') && this[callback]) {
            this[callback](event);
        }

        return false;
    },

    reflect_properties: function() {
        var properties = this.workspace.query.model.properties ?
            this.workspace.query.model.properties : Settings.QUERY_PROPERTIES;

        // Set properties appropriately
        if (properties['saiku.olap.query.nonempty'] === true) {
            $(this.el).find('.non_empty').addClass('on');
        }
        if (properties['saiku.olap.query.automatic_execution'] === true) {
            $(this.el).find('.auto').addClass('on');
        }

        if (properties['saiku.olap.query.drillthrough'] !== true) {
            $(this.el).find('.drillthrough, .drillthrough_export').addClass('disabled_toolbar');
        }

        if (properties['org.saiku.query.explain'] !== true) {
            $(this.el).find('.explain_query').addClass('disabled_toolbar');
        }

        if (properties['org.saiku.connection.scenario'] !== true) {
            $(this.el).find('.query_scenario').addClass('disabled_toolbar');
        } else {
            $(this.el).find('.query_scenario').removeClass('disabled_toolbar');
            $(this.el).find('.drillthrough, .drillthrough_export').addClass('disabled_toolbar');
        }
        if (properties['saiku.olap.query.limit'] == 'true' || properties['saiku.olap.query.filter'] === true) {
            $(this.workspace.el).find('.fields_list_header').addClass('limit');
        }

        if (this.workspace.query.getProperty('saiku.olap.result.formatter') !== "undefined" && this.workspace.query.getProperty('saiku.olap.result.formatter') == "flattened") {
            if (! $(this.el).find('.group_parents').hasClass('on')) {
                $(this.el).find('.group_parents').addClass('on');
            }
        }
        if ($(this.workspace.el).find( ".workspace_results tbody.ui-selectable" ).length > 0) {
            $(this.el).find('.zoom_mode').addClass('on');
        }

        $(this.el).find(".spark_bar, .spark_line").removeClass('on');
        $(this.el).find('a.edit').removeClass('disabled_toolbar');

        if (Settings.MODE == 'VIEW' || this.workspace.isReadOnly) {
            $(this.el).find('a.edit').hide();
            $(this.el).find('a.save').hide();
        } else {
            if (this.workspace.viewState == 'view') {
                $(this.el).find('a.edit').removeClass('on');
            } else {
                $(this.el).find('a.edit').addClass('on');
            }
            $(this.el).find('a.edit').show('normal');
        }
    },

    new_query: function(event) {
        if(typeof ga!= 'undefined'){
		ga('send', 'event', 'Toolbar', 'New Query');
        }
        this.workspace.switch_view_state('edit');
        this.workspace.new_query();

        return false;
    },

    edit_query: function(event) {
        $(event.target).toggleClass('on');

        if ($(event.target).hasClass('on')) {
            this.workspace.switch_view_state('edit');
        } else {
            this.workspace.switch_view_state('view');
        }
    },

    save_query: function(event) {
        var self = this;
        if (this.workspace.query) {
            if (typeof this.editor != "undefined") {
                var mdx = this.editor.getValue();
                this.workspace.query.model.mdx = mdx;
            }
            (new SaveQuery({ query: this.workspace.query })).render().open();
        }
    },

    open_query: function(event) {
            (new OpenDialog()).render().open();
    },


    run_query: function(event) {
        this.workspace.query.run(true);
    },

    automatic_execution: function(event) {
        // Change property
        var newState = !this.workspace.query.getProperty('saiku.olap.query.automatic_execution');
        this.workspace.query.setProperty('saiku.olap.query.automatic_execution', newState);

        if (newState) {
            $(event.target).addClass('on');
        } else {
            $(event.target).removeClass('on');
        }
    },

    toggle_fields: function(event) {
        var self = this;
        if (event) {
            $(this.el).find('.toggle_fields').toggleClass('on');
        }
        if (!$(this.el).find('.toggle_fields').hasClass('on')) {
            this.toggle_fields_action('hide');
        } else {
            this.toggle_fields_action('show');
        }

    },

    toggle_fields_action: function(action, dontAnimate) {
        var self = this;
        if ( action == 'show' && $('.workspace_editor').is(':visible')) {
            return;
        } else if ( action == 'hide' && $('.workspace_editor').is(':hidden')) {
            return;
        }
        if (dontAnimate) {
            $('.workspace_editor').css('height','');
            if ($('.workspace_editor').is(':hidden')) {
                $('.workspace_editor').show();
            } else {
                $('.workspace_editor').hide();
            }
            return;
        }

        if (action == 'hide') {
            $(this.workspace.el).find('.workspace_editor').hide();
        } else {
            $(this.workspace.el).find('.workspace_editor').show();
        }

        // avoid scrollbar on the right

        /*
        var wf = $('.workspace_editor').height();
        if ( action == 'hide') {
            var wr = $('.workspace_results').height();
            $('.workspace_results').height(wr - wf);
        }
        $(this.workspace.el).find('.workspace_editor').slideToggle({
            queue: false,
            progress: function() {
                self.workspace.adjust();
            },
            complete: function() {
                if ($('.workspace_editor').is(':hidden')) {
                    $('.workspace_editor').height(wf);
                } else {
                    $('.workspace_editor').css('height','');
                }

                self.workspace.adjust();
            }
        });

        */
    },

    about: function() {
        (new AboutModal()).render().open();
        return false;
    },

    toggle_sidebar: function() {
        this.workspace.toggle_sidebar();
    },

    // group_parents: function(event) {
    //     $(event.target).toggleClass('on');
    //     if ($(event.target).hasClass('on')) {
    //         this.workspace.query.setProperty('saiku.olap.result.formatter', 'flattened');
    //     } else {
    //         this.workspace.query.setProperty('saiku.olap.result.formatter', 'flat');
    //     }
    //     this.workspace.query.run();
    // },

    group_parents: function(event) {
        if (event) {
            $(event.target).toggleClass('on');
        }
        // this.$el.find('.group_parents').toggleClass('on')
        if (this.$el.find('.group_parents').hasClass('on')) {
            this.workspace.query.setProperty('saiku.olap.result.formatter', 'flattened');
        } else {
            this.workspace.query.setProperty('saiku.olap.result.formatter', 'flat');
        }
        this.workspace.query.run();
    },

    non_empty: function(event) {
        // Change property
        var nonEmpty = !this.workspace.query.getProperty('saiku.olap.query.nonempty');
        this.workspace.query.helper.nonEmpty(nonEmpty);

        this.workspace.query.setProperty('saiku.olap.query.nonempty', nonEmpty);

        // Toggle state of button
        $(event.target).toggleClass('on');

        // Run query
        this.workspace.query.run();
    },

    swap_axis: function(event) {
        // Swap axes
        $(this.workspace.el).find('.workspace_results table').html('');
        this.workspace.query.helper.swapAxes();
        this.workspace.sync_query();
        this.workspace.query.run(true);
    },


    check_modes: function(source) {
        if (typeof source === "undefined" || source === null)
            return;

        if ($(this.workspace.el).find( ".workspace_results tbody.ui-selectable" ).length > 0) {
            $(this.workspace.el).find( ".workspace_results tbody" ).selectable( "destroy" );
        }
        if (!$(source).hasClass('on')) {
            $(this.workspace.el).find("td.data").removeClass('cellhighlight').unbind('click');
            $(this.workspace.el).find(".table_mode").removeClass('on');

            this.workspace.query.run();
        } else {
            if ($(source).hasClass('drillthrough_export')) {
                $(this.workspace.el).find("td.data").addClass('cellhighlight').unbind('click').click(this.clicked_cell_drillthrough_export);
                $(this.workspace.el).find(".query_scenario, .drillthrough, .zoom_mode, .drillacross").removeClass('on');
            } else if ($(source).hasClass('drillthrough')) {
                $(this.workspace.el).find("td.data").addClass('cellhighlight').unbind('click').click(this.clicked_cell_drillthrough);
                $(this.workspace.el).find(".query_scenario, .drillthrough_export, .zoom_mode, .drillacross").removeClass('on');
            } else if ($(source).hasClass('query_scenario')) {
                this.workspace.query.scenario.activate();
                $(this.workspace.el).find(".drillthrough, .drillthrough_export, .zoom_mode, .drillacross").removeClass('on');
            } else if ($(source).hasClass('drillacross')) {
				$(this.workspace.el).find("td.data").addClass('cellhighlight').unbind('click').click(this.clicked_cell_drillacross);
				$(this.workspace.el).find(".query_scenario, .drillthrough, .drillthrough_export, .zoom_mode").removeClass('on');
			} else if ($(source).hasClass('zoom_mode')) {

                var self = this;
                $(self.workspace.el).find( ".workspace_results tbody" ).selectable({ filter: "td", stop: function( event, ui ) {
                    var positions = [];
                    $(self.workspace.el).find( ".workspace_results" ).find('td.ui-selected div').each(function(index, element) {
                        var p = $(element).attr('rel');
                        if (p) {
                            positions.push(p);
                        }
                    });
                    $(self.workspace.el).find( ".workspace_results" ).find('.ui-selected').removeClass('.ui-selected');

                    positions = _.uniq(positions);
                    if (positions.length > 0) {
						self.workspace.query.action.put("/zoomin", { success: function(model, response) {
							self.workspace.query.parse(response);
							self.workspace.unblock();
							self.workspace.sync_query();
							Saiku.ui.unblock();
							self.workspace.query.run();
						},
							data: { selections : JSON.stringify(positions) }
						});
                    }
                } });
                $(this.workspace.el).find(".drillthrough, .drillthrough_export, .query_scenario, .drillacross, .about").removeClass('on');
            }
        }


    },
    query_scenario: function(event) {
       $(event.target).toggleClass('on');
        this.check_modes($(event.target));

    },
    zoom_mode: function(event) {
       $(event.target).toggleClass('on');
        this.check_modes($(event.target));

    },
	drillacross: function(event) {
		 $(event.target).toggleClass('on');
		 this.check_modes($(event.target));
		 },
    drillthrough: function(event) {
       $(event.target).toggleClass('on');
        this.check_modes($(event.target));
    },

    display_drillthrough: function(model, response) {
        this.workspace.table.render({ data: response });
        Saiku.ui.unblock();
    },

    export_drillthrough: function(event) {
        $(event.target).toggleClass('on');
        this.check_modes($(event.target));
    },

	clicked_cell_drillacross: function(event) {
		 $target = $(event.target).hasClass('data') ?
			 $(event.target).find('div') : $(event.target);
		 var pos = $target.attr('rel');
		 (new DrillAcrossModal({
		 workspace: this.workspace,
			 title: "Drill Across",
			 action: "export",
			 position: pos,
			 query: this.workspace.query
		 })).open();

	 },
    clicked_cell_drillthrough_export: function(event) {
        $target = $(event.target).hasClass('data') ?
            $(event.target).find('div') : $(event.target);
        var pos = $target.attr('rel');
        (new DrillthroughModal({
            workspace: this.workspace,
            maxrows: 10000,
            title: "Drill-Through to CSV",
            action: "export",
            position: pos,
            query: this.workspace.query
        })).open();

    },

    clicked_cell_drillthrough: function(event) {
        $target = $(event.target).hasClass('data') ?
            $(event.target).find('div') : $(event.target);
        var pos = $target.attr('rel');
        (new DrillthroughModal({
            workspace: this.workspace,
            maxrows: 200,
            title: "Drill-Through",
            action: "table",
            success: this.display_drillthrough,
            position: pos,
            query: this.workspace.query
        })).open();

    },

    swap_axes_on_dropzones: function(model, response) {
		this.workspace.query.parse(response);
		this.workspace.unblock();
		this.workspace.sync_query();
		Saiku.ui.unblock();
        /*
        $columns = $(this.workspace.drop_zones.el).find('.columns')
            .children()
            .detach();
        $rows = $(this.workspace.drop_zones.el).find('.rows')
            .children()
            .detach();

        $(this.workspace.drop_zones.el).find('.columns').append($rows);
        $(this.workspace.drop_zones.el).find('.rows').append($columns);
        var rowLimit = $(this.workspace).find('fields_list.ROWS .limit').hasClass('on') | false;
        var colLimit = $(this.workspace).find('fields_list.COLUMNS .limit').hasClass('on') | false;
        $(this.workspace).find('fields_list.ROWS .limit').removeClass('on');
        $(this.workspace).find('fields_list.COLUMNS .limit').removeClass('on');
        if (rowLimit) {
            $(this.workspace).find('fields_list.COLUMNS .limit').addClass('on');
        }
        if (colLimit) {
            $(this.workspace).find('fields_list.ROWS .limit').addClass('on');
        }
        */
        this.workspace.unblock();
        this.workspace.sync_query();
        Saiku.ui.unblock();
    },

    show_mdx: function(event) {
        //this.workspace.query.enrich();

        (new MDXModal({ mdx: this.workspace.query.model.mdx })).render().open();
    },

    workspace_report_titles: function(event) {
        (new ReportTitlesModal({ query: this.workspace.query })).render().open();
    },

    export_xls: function(event) {
		if(this.workspace.query.name!=undefined){
			var filename = this.workspace.query.name.substring(this.workspace.query.name.lastIndexOf('/')+1).slice(0, -5);
			window.location = Settings.REST_URL +
			this.workspace.query.url() + "/export/xls/" + this.workspace.query.getProperty('saiku.olap.result.formatter')+"?exportname=" + "\"" + encodeURIComponent(filename)+"xls" + "\"";
		}
		else{
			window.location = Settings.REST_URL +
			this.workspace.query.url() + "/export/xls/" + this.workspace.query.getProperty('saiku.olap.result.formatter');
		}


    },

    export_csv: function(event) {
		if(this.workspace.query.name!=undefined){
			var filename = this.workspace.query.name.substring(this.workspace.query.name.lastIndexOf('/')+1).slice(0, -6);
			window.location = Settings.REST_URL +
			this.workspace.query.url() + "/export/csv/" + this.workspace.query.getProperty('saiku.olap.result.formatter')+"?exportname=" + "\"" + encodeURIComponent(filename) + "\"";
		}
		else{
			window.location = Settings.REST_URL +
			this.workspace.query.url() + "/export/csv/" + this.workspace.query.getProperty('saiku.olap.result.formatter');
		}

    },


    export_pdf: function(event) {
		if(this.workspace.query.name!=undefined){
			var filename = this.workspace.query.name.substring(this.workspace.query.name.lastIndexOf('/')+1).slice(0, -6);
			window.location = Settings.REST_URL +
			this.workspace.query.url() + "/export/pdf/" + this.workspace.query.getProperty('saiku.olap.result.formatter')+"?exportname=" + "\"" + encodeURIComponent(filename) + "\"";
		}
		else{
			window.location = Settings.REST_URL +
			this.workspace.query.url() + "/export/pdf/" + this.workspace.query.getProperty('saiku.olap.result.formatter');
		}
    },

    switch_to_mdx: function(event) {
        var self = this;
        $(this.workspace.el).find('.workspace_fields').addClass('hide');
        $(this.el).find('.auto, .query_scenario, .buckets, .non_empty, .swap_axis, .mdx, .switch_to_mdx, .zoom_mode, .drillacross').parent().hide();

        if ($(this.workspace.el).find( ".workspace_results tbody.ui-selectable" ).length > 0) {
            $(this.workspace.el).find( ".workspace_results tbody" ).selectable( "destroy" );
        }

        $(this.el).find('.run').attr('href','#run_mdx');
        $(this.el).find('.run, .save, .open, .new, .edit').removeClass('disabled_toolbar');

        if (Settings.MODE != "view" && Settings.MODE != "table" && !this.workspace.isReadOnly) {
            $mdx_editor = $(this.workspace.el).find('.mdx_input');
            //$mdx_editor.width($(this.el).width()-5);
            $(this.workspace.el).find('.workspace_editor .mdx_input, .workspace_editor .editor_info, .workspace_editor').removeClass('hide').show();
            this.editor = ace.edit("mdx_editor");
            this.editor.setShowPrintMargin(false);
            this.editor.setFontSize(11);
            this.editor.commands.addCommand({
                name: 'runmdx',
                bindKey: {win: 'Ctrl-Enter',  mac: 'Command-Enter'},
                exec: function(editor) {
                    self.run_mdx();
                },
                readOnly: true // false if this command should not apply in readOnly mode
            });

            var showPosition = function() {
                var pos = self.editor.getCursorPosition();
                $mdx_editor.parent().find('.editor_info').html("&nbsp; " + (pos.row +1) + ", " + pos.column);
            };

            this.editor.on('changeSelection', showPosition);
            showPosition();

             var heightUpdateFunction = function() {

                // http://stackoverflow.com/questions/11584061/
                var max_height = $(document).height() / 3;
                var height = Math.floor(max_height / self.editor.renderer.lineHeight);
                var screen_length = self.editor.getSession().getScreenLength() > height ? height : self.editor.getSession().getScreenLength();
                var newHeight =
                          (screen_length + 1) *
                          self.editor.renderer.lineHeight +
                          self.editor.renderer.scrollBar.getWidth();

                $mdx_editor.height(newHeight.toString() + "px");
                self.editor.resize();
                self.workspace.adjust();
            };

            var resizeFunction = function() {
                var session = self.editor.session;
                //$mdx_editor.width($(self.el).width()-5);
                self.editor.resize();
                session.setUseWrapMode(true);
                if(session.getUseWrapMode()) {
                    var characterWidth = self.editor.renderer.characterWidth;
                    var contentWidth = self.editor.renderer.scroller.clientWidth;

                    if(contentWidth > 0) {
                        session.setWrapLimitRange(null, parseInt(contentWidth / characterWidth, 10));
                    }
                }
            };

            resizeFunction();

            heightUpdateFunction();

            self.editor.focus();
            self.editor.clearSelection();
            self.editor.getSession().setValue("");
            self.editor.getSession().on('change', heightUpdateFunction);
            $(window).resize(resizeFunction);

            self.editor.on('changeSelection', heightUpdateFunction);
            self.editor.on('focus', function(e) { heightUpdateFunction(); return true; });
            self.editor.on('blur', function(e) {
                    if ($(self.workspace.el).find(".mdx_input").height() > 100) {
                                $(self.workspace.el).find(".mdx_input").height(100);
                            }
                            self.editor.resize();
                            self.workspace.adjust();
             return true; });

            //this.editor.on('focusout', function(e) { alert('blur');  });

            //this.editor.setTheme("ace/theme/crimson_editor");
            this.editor.getSession().setMode("ace/mode/text");

        }

        if (this.workspace.dimension_list) {
            $(this.workspace.el).find('.sidebar_inner ul li a')
                .css({fontWeight: "normal"}).parent('li').removeClass('ui-draggable ui-draggable-disabled ui-state-disabled');
        }
        this.activate_buttons({ workspace: this.workspace });
        $(this.workspace.toolbar.el)
                .find('.run')
                .removeClass('disabled_toolbar');

        $(this.workspace.table.el).empty();

        if (this.workspace.timeSeriesFilter) {
            $(this.workspace.timeSeriesFilter.el).empty();
        }

        this.workspace.adjust();
        this.post_mdx_transform();
    },

    post_mdx_transform: function() {
        var self = this;

        if (this.workspace.query.model.type !== "MDX") {
            //this.workspace.query.enrich();
            this.workspace.query.model.queryModel = {};
            this.workspace.query.model.type = "MDX";
            this.workspace.query.setProperty('saiku.olap.result.formatter', 'flat');
            self.workspace.query.helper.model().parameters = {};

        }
        var mdx = this.workspace.query.model.mdx;

        if (self.editor) {
            self.editor.setValue(mdx,0);
            self.editor.clearSelection();
            self.editor.focus();
        }
        $(self.el).find('.group_parents').removeClass('on');

        if (Settings.ALLOW_PARAMETERS) {

            var parameterDetector = function() {
                var mdx = self.editor.getValue();
                var parameters = [];
                if (mdx) {
                    for (var i = 0, len = mdx.length; i < (len-1); i++ ) {
                        if (mdx[i] === "$" && mdx[i+1] === "{") {
                            var param = "";
                            var closed = false;
                            for(i = i + 2; i < len; i++) {
                                if (mdx[i] !== '}') {
                                    param += mdx[i];
                                } else {
                                    closed = true;
                                    i++;
                                    break;
                                }
                            }
                            if (closed && param && param.length > 0) {
                                parameters.push(param);
                            }
                        }
                    }
                }
                var qParams = self.workspace.query.helper.model().parameters;
                var newParams = {};
                _.each(parameters, function(p) {
                    if (!qParams[p]) {
                        newParams[p] = "";
                    } else {
                        newParams[p] = qParams[p];
                    }

                });
                self.workspace.query.helper.model().parameters = newParams;
                self.workspace.update_parameters();


            };

			var lazyDetector = function() { _.delay(parameterDetector, 1000); };
			if (self.editor) {
				self.editor.getSession().off('change', lazyDetector);
				self.editor.getSession().on('change', lazyDetector);
			}
			self.workspace.update_parameters();
        }

    },

    run_mdx: function(event) {
        //var mdx = $(this.workspace.el).find(".mdx_input").val();
        if ($(this.workspace.el).find(".mdx_input").height() > 100) {
            $(this.workspace.el).find(".mdx_input").height(100);
        }
        this.editor.resize();
        var mdx = this.editor.getValue();
        this.workspace.query.model.mdx = mdx;
        this.workspace.query.run(true);
    },

    explain_query: function(event) {
        var self = this;
        var explained = function(model, args) {

            var explainPlan = "<textarea style='width: " + ($("body").width() - 165) + "px;height:" + ($("body").height() - 175) + "px;'>";
            if (args !== null && args.error !== null) {
                explainPlan += args.error;
            } else if (args.cellset && args.cellset.length === 0) {
                explainPlan += "Empty explain plan!";
            } else {
                explainPlan += args.cellset[1][0].value;
            }
            explainPlan += "</textarea>";

            Saiku.ui.unblock();

            var html = '<div id="fancy_results" class="workspace_results" style="overflow:visible"><table>' +
                       '<tr><th clas="row_header">Explain Plan</th></tr>' +
                       '<tr><td>' + explainPlan + '</td></tr>' +
                       '</table></div>';

            $.fancybox(html,
                {
                'autoDimensions'    : false,
                'autoScale'         : false,
                'height'            :  ($("body").height() - 100),
                'width'             :  ($("body").width() - 100),
                'transitionIn'      : 'none',
                'transitionOut'     : 'none'
                }
            );
        };

        self.workspace.query.action.gett("/explain", { success: explained } );

        return false;

    }
});
