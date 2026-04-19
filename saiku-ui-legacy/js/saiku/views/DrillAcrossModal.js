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
var DrillAcrossModal = DrillthroughModal.extend({

	allMeasures: true,

	templateContent: function() {
		return $("#template-drillacross").html();
	},

	ok: function() {
	if(typeof ga!= 'undefined'){	
		ga('send', 'event', 'DrillAcross', 'Execute');
		}
		var self = this;
		var selections = {};
		$(this.el).find('.check_level:checked').each( function(index) {
			var key = $(this).attr('key');
			if (!selections[key]) {
				selections[key] = [];
			}
			selections[key].push($(this).val());
		});

		Saiku.ui.block("Executing drillacross...");
		this.query.action.post("/drillacross", { data: { position: this.position, drill: JSON.stringify(selections)}, success: function(model, response) {
			self.workspace.query.parse(response);
			self.workspace.unblock();
			self.workspace.sync_query();
			self.workspace.query.run();
		}, error: function(a, b, errorThrown) {
			self.workspace.unblock();
			var text = "";
			if (b && b.hasOwnProperty("responseText")) {
				text = b.responseText;
			}
			alert("Error drilling across. Check logs! " + text);
		}});
		this.close();

		return false;
	}



});
