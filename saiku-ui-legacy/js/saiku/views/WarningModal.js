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
 * The "warning message" dialog
 */
var WarningModal = Modal.extend({
    type: 'info',

    buttons: [
        { text: 'Okay', method: 'okay' },
        { text: 'Cancel', method: 'close' }
    ],

    initialize: function(args) {
        var self = this;

        this.options.title = args.title;
        this.message = '<span class="i18n">' + args.message + '</span>';
        this.okayfunction = args.okay;
        this.okaycallbackobject = args.okayobj;
        this.cancelfunction = args.cancel;
        this.cancelcallbackobject = args.cancelobj;

        this.bind('open', function() {
          self.$el.closest('.ui-dialog').on('keydown', function(event) {
            if (event.keyCode === $.ui.keyCode.ESCAPE) {
              self.close(event);
            }
          });

          self.$el.closest('.ui-dialog').find('.ui-dialog-titlebar-close').on('click', function(event) {
            self.close(event);
          });
        });
    },

    okay: function(event) {
        event.preventDefault();

        if (_.isFunction(this.okayfunction)) {
            this.okayfunction(this.okaycallbackobject);
        }

        this.$el.dialog('close');
    },

    close: function(event) {
        if (event.target.hash === '#close') {
            event.preventDefault();
        }

        if (_.isFunction(this.cancelfunction)) {
            this.cancelfunction(this.cancelcallbackobject);
        }

        this.$el.dialog('close');
    }
});
