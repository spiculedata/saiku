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
var DrillthroughModal = Modal.extend({
	type: "drillthrough",

	buttons: [
		{ text: "Ok", method: "ok" },
		{ text: "Cancel", method: "close" }
	],

	events: {
		'click .collapsed': 'select',
		'click .expand': 'select',
		'click .folder_collapsed': 'select',
		'click .folder_expanded': 'select',
		'click .dialog_footer a' : 'call',
		'click .parent_dimension input' : 'select_dimension',
		'click .measure_tree input' : 'select_measure',
		'click input.all_measures' : 'select_all_measures',
		'click input.all_dimensions' : 'select_all_dimensions'
	},

	allMeasures: false,

	templateContent: function() {
		return $("#template-drillthrough").html();
	},



	initialize: function(args) {
		// Initialize properties
		_.extend(this, args);
		this.options.title = args.title;
		this.query = args.workspace.query;

		this.position = args.position;
		this.action = args.action;
		Saiku.ui.unblock();
		_.bindAll(this, "ok", "drilled", "template");

		// Resize when rendered

		this.render();
		// Load template
		$(this.el).find('.dialog_body')
			.html(_.template(this.templateContent())(this));
		// Show dialog
		$(this.el).find('.maxrows').val(this.maxrows);

		var schema = this.query.get('schema');

		var container = $("#template-drillthrough-list").html();

		var cubeModel = Saiku.session.sessionworkspace.cube[key];
		var dimensions = null;
		var measures = null;
		var key = this.workspace.selected_cube;

		if (cubeModel && cubeModel.has('data')) {
			dimensions = cubeModel.get('data').dimensions;
			measures = cubeModel.get('data').measures;
		}

		if (!cubeModel || !dimensions || !measures) {
			if (typeof localStorage !== "undefined" && localStorage && localStorage.getItem("cube." + key) !== null) {
				Saiku.session.sessionworkspace.cube[key] = new Cube(JSON.parse(localStorage.getItem("cube." + key)));
			} else {
				Saiku.session.sessionworkspace.cube[key] = new Cube({ key: key });
				Saiku.session.sessionworkspace.cube[key].fetch({ async : false });
			}
			dimensions = Saiku.session.sessionworkspace.cube[key].get('data').dimensions;
			measures = Saiku.session.sessionworkspace.cube[key].get('data').measures;
		}

		var templ_dim =_.template($("#template-drillthrough-dimensions").html())({dimensions: dimensions});
		var templ_measure =_.template($("#template-drillthrough-measures").html())({measures: measures, allMeasures: this.allMeasures});

		$(container).appendTo($(this.el).find('.dialog_body'));
		$(this.el).find('.sidebar').height(($("body").height() / 2) + ($("body").height() / 6) );
		$(this.el).find('.sidebar').width(380);

		$(this.el).find('.dimension_tree').html('').append($(templ_dim));
		$(this.el).find('.measure_tree').html('').append($(templ_measure));

		Saiku.i18n.translate();
	},

	select: function(event) {
		var $target = $(event.target).hasClass('root')
			? $(event.target) : $(event.target).parent().find('span');
		if ($target.hasClass('root')) {
			$target.find('a').toggleClass('folder_collapsed').toggleClass('folder_expand');
			$target.toggleClass('collapsed').toggleClass('expand');
			$target.parents('li').find('ul').children('li').toggle();
		}

		return false;
	},

	select_dimension: function(event) {
		var $target = $(event.target);
		var checked = $target.is(':checked');
		$target.parent().find('input').attr('checked', checked);
	},

	select_all_dimensions: function(event) {
		var $target = $(event.target);
		var checked = $target.is(':checked');
		$(this.el).find('.dimension_tree input').attr('checked', checked);
	},

	select_all_measures: function(event) {
		var $target = $(event.target);
		var checked = $target.is(':checked');
		$(this.el).find('.measure_tree input').attr('checked', checked);
	},

	select_measure: function(event) {
		var $target = $(event.target);
		var checked = $target.is(':checked');
		if(checked) {
			//$target.parent().siblings().find('input').attr('checked', false);
		}
	},



	ok: function() {
		if(typeof ga!= 'undefined'){
			ga('send', 'event', 'Drillthrough', 'Execute');
		}
		// Notify user that updates are in progress
		var $loading = $("<div>Drilling through...</div>");
		$(this.el).find('.dialog_body').children().hide();
		$(this.el).find('.dialog_body').prepend($loading);
		var selections = "";
		$(this.el).find('.check_level:checked').each( function(index) {
			if (index > 0) {
				selections += ", ";
			}
			selections += $(this).val();
		});

		var maxrows = parseInt($(this.el).find('.maxrows').val(), 10);
		maxrows = !Number.isNaN(maxrows) ? maxrows : '';
		var params = "?maxrows=" + maxrows;
		params = params + (typeof this.position !== "undefined" ? "&position=" + this.position : "" );
		params += "&returns=" + selections;
		if (this.action == "export") {
			var location = Settings.REST_URL +
				"api/query/" +
				this.query.id + "/drillthrough/export/csv" + params;
			this.close();
			window.open(location);
		} else if (this.action == "table") {
			Saiku.ui.block("Executing drillthrough...");
			this.query.action.gett("/drillthrough", { data: { position: this.position, maxrows: maxrows , returns: selections}, success: this.drilled } );
			this.close();
		}

		return false;
	},

	drilled: function(model, response) {
		var html = "";
		if (response != null && response.error != null) {
			html = safe_tags_replace(response.error);
		} else {
			var tr = new SaikuTableRenderer();
			html = tr.render(response);
		}

		//table.render({ data: response }, true);

    if (typeof html === 'undefined' || html === '' || !html) {
    	html = '<h3 style="text-align:center;">Your drill through returned 0 rows. Nothing to display!</h3>';
    }

		Saiku.ui.unblock();
		var htmlfancy = '<div id="fancy_results" class="workspace_results" style="overflow:visible">' + html + '</div>';
		this.remove();
		$.fancybox(htmlfancy
			,
			{
				'autoDimensions'    : false,
				'autoScale'         : false,
				'height'            :  ($("body").height() - 100),
				'width'             :  ($("body").width() - 100),
				'transitionIn'      : 'none',
				'transitionOut'     : 'none'
			}
		);

	},

	finished: function() {
		$(this.el).dialog('destroy').remove();
		this.query.run();
	}
});
