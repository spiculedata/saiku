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
 * The global toolbar
 */
var Upgrade = Backbone.View.extend({

	events: {
	},


	initialize: function(a, b) {

		this.workspace = a.workspace;

		// Fire off workspace event
		this.workspace.trigger('workspace:toolbar:render', {
			workspace: this.workspace
		});

	},

	daydiff: function(first, second) {
		return Math.round((second-first)/(1000*60*60*24));
	},

	render: function() {

		var self = this;
		var license = new License();

		if(Settings.BIPLUGIN5){
				if(Saiku.session.get("notice") != undefined && Saiku.session.get("notice")!=null && Saiku.session.get("notice")!=""){
					$(this.el).append("<div><div id='uphead' class='upgradeheader'>Notice:"+Saiku.session.get("notice")+"</div>");

				}
				if (Settings.LICENSE.licenseType != undefined && (Settings.LICENSE.licenseType != "trial" && Settings.LICENSE.licenseType != "Open Source License")) {
					return this;
				}
				if (Settings.LICENSE != undefined && Settings.LICENSE.licenseType === "trial") {
					var yourEpoch = parseFloat(Settings.LICENSE.expiration);
					var yourDate = new Date(yourEpoch);
					self.remainingdays = self.daydiff(new Date(), yourDate);


					$(this.el).append("<div><div id='uphead' class='upgradeheader'>You are using a Saiku Enterprise" +
						" Trial license, you have "+ self.remainingdays+" days remaining. <a href='http://www.meteorite.bi/saiku-pricing'>Buy licenses online.</a></div>");
					return self;
				}
				else {
					$(this.el).append("<div><div id='uphead' class='upgradeheader'>You are using Saiku Community" +
						" Edition, please consider upgrading to <a target='_blank' href='http://meteorite.bi'>Saiku Enterprise</a>, or entering a <a href='http://meteorite.bi/products/saiku/sponsorship'>sponsorship agreement with us</a> to support development. " +
						"<a href='http://meteorite.bi/products/saiku/community'>Or contribute by joining our community and helping other users!</a></div></div>");

					return self;
				}
		}
		else {
				if(Saiku.session.get("notice") != undefined && Saiku.session.get("notice")!=null && Saiku.session.get("notice")!=""){
					$(this.el).append("<div><div id='uphead' class='upgradeheader'>Notice:"+Saiku.session.get("notice")+"</div>");

				}
				if (Settings.LICENSE.licenseType != undefined && (Settings.LICENSE.licenseType != "trial" &&
					Settings.LICENSE.licenseType != "Open Source License")) {
					return this;
				}
				if (Settings.LICENSE.licenseType === "trial") {
					var yourEpoch = parseFloat(Settings.LICENSE.expiration);
					var yourDate = new Date(yourEpoch);

					self.remainingdays = self.daydiff(new Date(), yourDate);

					$(this.el).append("<div><div id='uphead' class='upgradeheader'>You are using a Saiku Enterprise" +
						" Trial license, you have "+ self.remainingdays+" days remaining. <a href='http://www.meteorite.bi/saiku-pricing'>Buy licenses online.</a></div>");
					return self;
				}
				else {
					$(this.el).append("<div><div id='uphead' class='upgradeheader'>You are using Saiku Community" +
						" Edition, please consider upgrading to <a target='_blank' href='http://meteorite.bi'>Saiku Enterprise</a>, or entering a <a href='http://meteorite.bi/products/saiku/sponsorship'>sponsorship agreement with us</a> to support development. " +
						"<a href='http://meteorite.bi/products/saiku/community'>Or contribute by joining our community and helping other users!</a></div></div>");
					return self;
				}
		}








	},

	call: function(e) {
	}

});
