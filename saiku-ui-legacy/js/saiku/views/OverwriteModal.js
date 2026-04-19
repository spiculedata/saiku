/*
 * Copyright 2015 OSBI Ltd
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
 * The "over write" dialog
 */
var OverwriteModal = Modal.extend({
  type: 'info',

  message: 'Are you sure you want to overwrite the existing query?',

  buttons: [
    { text: 'Yes', method: 'save' },
    { text: 'No', method: 'close' }
  ],

  initialize: function(args) {
    // Initialize properties
    _.extend(this, args);

    this.options.title = 'Warning';

    this.queryname   = this.name;
    this.queryfolder = this.foldername;
    this.parentobj   = this.parent;
  },

  dummy: function() {
    return true;
  },

  save: function(event) {
    event.preventDefault();
    this.parentobj.save_remote(this.queryname, this.queryfolder, this.parentobj);
    this.$el.dialog('destroy').remove();
  }
});
