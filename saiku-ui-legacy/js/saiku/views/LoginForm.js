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
 * The login prompt on startup
 */
var LoginForm = Modal.extend({
    type: 'login',

    message: _.template(
        '<form id="login_form">' +
            '<label for="username" class="i18n">Username</label>' +
            '<input type="text" id="username" name="username">' +
            '<label for="password" class="i18n">Password</label>' +
            '<input type="password" id="password" name="password">' +
            '<% if (Settings.EVALUATION_PANEL_LOGIN) { %>' +
                '<div class="eval-panel">' +
                    '<a href="#eval_login" class="i18n" id="eval-login">Evaluation Login</a>' +
                    '<div class="eval-panel-user clearfix" hidden>' +
                        '<ul>' +
                            '<li class="i18n">Administrator</li>' +
                            '<li class="i18n">Username: admin</li>' +
                            '<li class="i18n">Password: admin</li>' +
                        '</ul>' +
                    '</div>' +
                '</div>' +
            '<% } %>' +
        '</form>'
    )(),

    options: {
        autoOpen: false,
        closeOnEscape: false,
        modal: true,
        title: Settings.VERSION,
        resizable: false,
        draggable: false
    },

    buttons: [
        { text: 'Login', method: 'login' },
        { text: 'Upload License', method: 'upload_license' },
    ],

    events: {
        'click .dialog_footer a'  : 'call',
        'keyup #login_form input' : 'check',
        'click #eval-login'       : 'show_panel_user',
        'click .clearlink'        : 'clear_login'
    },

    initialize: function(args) {
        _.extend(this, args);
        _.bindAll(this, 'adjust');
        // this.options.title = Settings.VERSION;
        this.bind('open', this.adjust);
    },

    adjust: function() {
        this.$el.parent().find('.ui-dialog-titlebar-close').hide();
        this.$el.find('#username').focus();
        this.$el.find('.dialog_footer').find('a[href="#upload_license"]').hide();
    },

    check: function(event) {
        if (event.which === 13) {
            this.login();
        }
    },

    login: function() {
        var l_username = this.$el.find('#username').val();
        var l_password = this.$el.find('#password').val();
        this.$el.dialog('close');
        this.session.login(l_username, l_password, this.$el);
        return true;
    },

    clear_login: function(event) {
        window.open('/clear.html', '_blank');
    },

    setMessage: function(message) {
        this.$el.find('.dialog_body').html(this.message);
    },

	setError: function(message) {
        this.$el.find('.dialog_response').html(message);
        if (message === 'license expired') {
            this.$el.find('.dialog_footer').find('a[href="#upload_license"]').show();
        }
        this.$el.find('.clearlink').unbind();
    },

    show_panel_user: function(event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        $currentTarget.next().slideToggle('fast');
    },

    upload_license: function(event) {
        event.preventDefault();
        var url = window.location;
        if (url.search === '') {
            window.open(url.href + 'upload.html', '_self');
        }
        else {
            window.open(url.origin + '/upload.html', '_self');
        }
    }
});
