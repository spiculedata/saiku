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
 * The base class for all modal dialogs
 */
var Modal = Backbone.View.extend({
    tagName: 'div',
    className: 'dialog',
    type: 'modal',
    message: 'Put content here',

    options: {
        autoOpen: false,
        modal: true,
        title: 'Modal dialog',
        resizable: false,
        draggable: true,
        close: function(event, ui) {
            // bugfix: https://github.com/OSBI/saiku/issues/628
            // The defer function fixed the error:
            // Cannot call methods on dialog prior to initialization; attempted to call method 'close'
            _.defer(function() {
                $(event.target).closest('.ui-dialog').remove();
            });
        }
    },

    events: {
        'click a': 'call'
    },

    buttons: [
        { text: 'OK', method: 'close' }
    ],

    template: function() {
        return _.template(
            '<div class="dialog_icon"></div>' +
            '<div class="dialog_body"><%= message %></div>' +
            '<div class="dialog_footer">' +
                '<% _.each(buttons, function(button) { %>' +
                    '<a class="form_button btn btn-default i18n" href="#<%= button.method %>">&nbsp;<%= button.text %>&nbsp;</a>' +
                '<% }); %>' +
                '<div class="dialog_response"></div>' +
            '</div>'
        )(this);
    },

    initialize: function(args) {
        _.extend(this, args);
        _.bindAll(this, 'call');
        _.extend(this, Backbone.Events);
    },

    render: function() {
        this.$el.html(this.template())
            .addClass('dialog_' + this.type)
            .dialog(this.options);

        var uiDialogTitle = $('.ui-dialog-title');

        uiDialogTitle.html(this.options.title);
        uiDialogTitle.addClass('i18n');
        Saiku.i18n.translate();

        return this;
    },

    call: function(event) {
        // Determine callback
        var callback = event.target.hash.replace('#', '');

        // Attempt to call callback
        if (!$(event.target).hasClass('disabled_toolbar') && this[callback]) {
            this[callback](event);
        }

        return false;
    },

    open: function() {
        this.$el.dialog('open');
        this.trigger('open', { modal: this });

        return this;
    },

    close: function() {
        this.$el.dialog('destroy').remove();
        this.$el.remove();

        return false;
    }
});

/* jQuery UI - v1.10.2 - 2013-12-12  (and later)
 * http://bugs.jqueryui.com/ticket/9087#comment:30
 * http://bugs.jqueryui.com/ticket/9087#comment:27 - bugfix
 * http://bugs.jqueryui.com/ticket/4727#comment:23 - bugfix
 * allowInteraction fix
 */
$.ui.dialog.prototype._allowInteraction = function(event) {
    return !!$(event.target).closest('.ui-dialog, .ui-datepicker, .sp-input').length;
};
