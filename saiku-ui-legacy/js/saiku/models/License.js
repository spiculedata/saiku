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
 * Model which fetches the information of license
 */
var License = Backbone.Model.extend({
	url: 'api/license',

	initialize: function() {
		_.bindAll(this, 'fetch_license');
	},

	fetch_license: function(path, callback) {
		this.fetch({
			success: function(res) {
				if (callback && typeof(callback) === 'function') {
					callback({status: 'success', data: res});
				}
			},
			error: function(err) {
				if (callback && typeof(callback) === 'function') {
					callback({status: 'error', data: err});
				}
			}
		});
	}
});

var LicenseUserModel = Backbone.Model.extend({
	url: 'api/license/users'
});

var LicenseUsersCollection = Backbone.Collection.extend({
	url: 'api/license/users',
    model: LicenseUserModel
});

var LicenseQuota = Backbone.Model.extend({
	url: 'api/license/quota',

	initialize: function() {
		_.bindAll(this, 'fetch_quota');
	},

	fetch_quota: function(path, callback) {
		this.fetch({
			success: function(res) {
				if (callback && typeof(callback) === 'function') {
					callback({status: 'success', data: res});
				}
			},
			error: function(err) {
				if (callback && typeof(callback) === 'function') {
					callback({status: 'error', data: err});
				}
			}
		});
	}
});