/*
 * Copyright 2014 OSBI Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Created by bugg on 18/01/15.
 */

var SessionErrorModal = Modal.extend({
	initialize: function(args, options) {
		_.extend(this.options, {
			title: "Error"
			//issue: args.issue
		});

		this.reportedissue = args.issue;
		this.message = "There has been an error creating a session:<br>"+ this.reportedissue;
	},

	events: {
		'click a' : 'close'
	},

	dummy: function() { return true;},

	type: "info",

	//message: _.template("There has been an error creating a session:<br> this.reportedissue>"


});
