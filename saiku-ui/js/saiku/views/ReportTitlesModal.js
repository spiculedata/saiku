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
 * The report titles dialog
 */
var ReportTitlesModal = Modal.extend({
  type: 'report-titles',

  buttons: [
    { text: 'Save', method: 'save' },
    { text: 'Clear', method: 'clear' },
    { text: 'Cancel', method: 'close' }
  ],

  message: _.template(
    '<form>' +
      '<div class="form-group">' +
        '<label for="title" class="i18n">Title:</label>' +
        '<input type="text" class="form-control" id="title">' +
      '</div>' +
      '<div class="form-group">' +
        '<label for="variable" class="i18n">Variable:</label>' +
        '<input type="text" class="form-control" id="variable">' +
      '</div>' +
      '<div class="form-group">' +
        '<label for="explanation" class="i18n">Explanation:</label>' +
        '<input type="text" class="form-control" id="explanation">' +
      '</div>' +
    '</form>'
  )(),

  initialize: function(args) {
    // Initialize properties
    this.options.title = 'Report Titles';
    this.query = args.query;

    var self = this;

    this.bind('open', function() {
      self.$el.find('.dialog_icon').hide();
      self.$el.find('.dialog_body').css({
        'padding': '20px'
      });
      self.$el.find('.dialog_footer a:nth-child(2)').hide();
      self.set_headings();
    });
  },

  set_headings: function() {
    var data = this.query.getProperty('saiku.ui.headings');
    var headings;

    if (data && data !== '' && data !== '{}') {
      headings = JSON.parse(data);

      if (headings.title || headings.variable || headings.explanation) {
        this.$el.find('#title').val(headings.title);
        this.$el.find('#variable').val(headings.variable);
        this.$el.find('#explanation').val(headings.explanation);
        this.$el.find('.dialog_footer a:nth-child(2)').show();
      }
    }
  },

  save: function(event) {
    event.preventDefault();

    var headings = {
      title:       this.$el.find('#title').val(),
      variable:    this.$el.find('#variable').val(),
      explanation: this.$el.find('#explanation').val()
    };

    this.query.setProperty('saiku.ui.headings', JSON.stringify(headings));
    this.query.run(true);

    this.$el.dialog('close');
  },

  clear: function(event) {
    event.preventDefault();

    this.$el.find('#title', '#variable', '#explanation').val('');

    this.query.setProperty('saiku.ui.headings', JSON.stringify({}));
    this.query.run(true);

    this.$el.dialog('close');
  },

  close: function(event) {
    event.preventDefault();
    this.$el.dialog('close');
  }
});
