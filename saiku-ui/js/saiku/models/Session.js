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
 * Object which handles authentication and stores connections and cubes
 * @param username
 * @param password
 * @returns {Session}
 */
var Session = Backbone.Model.extend({
    username: null,
    password: null,
    sessionid: null,
    upgradeTimeout: null,
    isAdmin: false,
    id: null,
	atemptedToLoginByCookie: false,
    initialize: function(args, options) {
        // Attach a custom event bus to this model
        _.extend(this, Backbone.Events);
        _.bindAll(this, "check_session", "process_session", "load_session","login", "brute_force");
        // Check if credentials are being injected into session
        if (options && options.username && options.password) {
            this.username = options.username;
            this.password = options.password;
            if (!Settings.DEMO) {
                this.save({username:this.username, password:this.password},{success: this.check_session, error: this.check_session});
            } else {
                this.check_session();
            }

        } else {
            this.check_session();
        }
    },

    check_session: function() {
		// This authentication cookie is used only by Orbis authentication strategy
		var authCookie = this.getCookie(Settings.ORBIS_AUTH.cookieName);

		if (Settings.ORBIS_AUTH.enabled && authCookie && !this.atemptedToLoginByCookie) {
			this.atemptedToLoginByCookie = true;
			this.login('orbis', 'orbis');
		} else {
			if (this.sessionid === null || this.username === null || this.password === null) {
				var that = this;
				this.clear();
				this.fetch({ success: this.process_session, error: this.brute_force });
			} else {
				this.username = encodeURIComponent(options.username);
				this.load_session();
			}
		}
    },

	getCookie: function(name) {
		var value = "; " + document.cookie;
		var parts = value.split("; " + name + "=");
		if (parts.length == 2) return parts.pop().split(";").shift();
	},

	/**
	 * This is a complete hack to get the BI platform plugin working.
	 * @param obj
	 */
	brute_force: function(model, response){
		this.clear();
		this.fetch({ success: this.process_session, error: this.show_error });
	},
	show_error: function(model, response){

		// Open form and retrieve credentials
		Saiku.ui.unblock();
		this.form = new SessionErrorModal({ issue: response.responseText });
		this.form.render().open();


	},

    load_session: function() {
        this.sessionworkspace = new SessionWorkspace();
    },

    process_session: function(model, response) {
        if ((response === null || response.sessionid == null)) {
            // Open form and retrieve credentials
            Saiku.ui.unblock();
            if (Settings.DEMO) {
                this.form = new DemoLoginForm({ session: this });
            } else {
                this.form = new LoginForm({ session: this });
            }
            this.form.render().open();
        } else {
            this.sessionid = response.sessionid;
            this.roles = response.roles;
            this.isAdmin = response.isadmin;
            this.username = encodeURIComponent(response.username);
            this.language = response.language;
            if (typeof this.language != "undefined" && this.language != Saiku.i18n.locale) {
                Saiku.i18n.locale = this.language;
                Saiku.i18n.automatic_i18n();
            }
                var license =new License();

                license.fetch_license('api/license/', function(opt) {
                    if (opt.status === 'success') {
                        Settings.LICENSE = opt.data.toJSON();
                    }
                    if(Saiku.session.isAdmin) {

                        var quota = new LicenseQuota();

                        quota.fetch_quota('api/license/quota', function (opt) {
                            if (opt.status === 'success') {
                                Settings.LICENSEQUOTA = opt.data.toJSON();
                            }
                        });
                    }

                });


            this.load_session();
        }

        return this;
    },

    error: function() {
        $(this.form.el).dialog('open');
    },

    login: function(username, password) {
        var that = this;
        this.save({username:username, password:password},{dataType: "text", success: this.check_session, error: function(model, response){
            that.login_failed(response.responseText);
        }});

    },
    login_failed: function(response){
        this.form = new LoginForm({ session: this });
        this.form.render().open();
        this.form.setError(response);
    },
    logout: function() {
        // FIXME - This is a hack (inherited from old UI)
        Saiku.ui.unblock();
        $('#header').empty().hide();
        $('#tab_panel').remove();
        Saiku.tabs = new TabSet();
        Saiku.toolbar.remove();
        Saiku.toolbar = new Toolbar();

        if (typeof localStorage !== "undefined" && localStorage) {
            localStorage.clear();
        }

        this.set('id', _.uniqueId('queryaction_'));
        this.destroy({async: false });

        this.clear();
        this.sessionid = null;
        this.username = null;
        this.password = null;
		this.roles = null;
        this.isAdmin = false;
        this.destroy({async: false });
        //console.log("REFRESH!");
        document.location.reload(false);
        delete this.id;

    },

    url: function() {

        return "session";
    }
});
