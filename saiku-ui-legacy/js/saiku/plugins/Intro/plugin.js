/*
 *   Copyright 2017 OSBI Ltd
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
 * Step-by-step guide and feature introduction.
 *
 * @example
 *
 *    @param {String}       fileName         - Filename json
 *    @param {String|Array} specificElements - Specific elements to make the steps
 *    @param {Object}       options          - Intro.js plugin options
 *
 *    Saiku.intro.start({
 *      fileName: 'Workspace',
 *      specificElements: ['#new_query', '#admin_icon'],
 *      options: { showProgress: false }
 *    });
 *
 *    or
 *
 *    Saiku.intro.start({ fileName: 'Workspace' });
 */
Saiku.intro = {
  intro: introJs(),

  fileName: Settings.INTRO_FILE_NAME || 'Workspace',

  forwardStep: null,

  get_specific_elements: function(data, specificElements) {
    var steps = data.steps;
    var newSteps = {};

    newSteps.steps = [];

    if (_.isString(specificElements)) {
      _.find(steps, function(value) {
        if (value.element === specificElements) {
          newSteps.steps.push(value);
        }
      });
    }
    else if (_.isArray(specificElements)) {
      var iSteps = 0;
      var iElems = 0;

      do {
        if (steps[iSteps].element === specificElements[iElems]) {
          newSteps.steps.push(steps[iSteps]);
          iSteps = 0;
          iElems += 1;
        }
        else {
          iSteps += 1;
        }
      } while (iElems < specificElements.length);

      if (newSteps.steps.length === 0) {
        newSteps = data;
      }
    }
    else {
      newSteps = data;
    }

    return newSteps;
  },

  merge_options: function(options) {
    var newOptions = Settings.INTRO_DEFAULT_OPTIONS;

    $.extend(newOptions, options);
    this.intro.setOptions(newOptions);
  },

  start: function(data) {
    var self = this;

    if (data.fileName) {
      this.fileName = data.fileName;
    }

    $.ajax({
      url: 'js/saiku/plugins/Intro/steps/' + self.fileName + '.json',
      type: 'GET',
      dataType: 'json',
      success: function(dataJson) {
        if (data.specificElements) {
          dataJson = self.get_specific_elements(dataJson, data.specificElements);
        }

        if (data.options) {
          self.merge_options(data.options);
        }
        else {
          self.intro.setOptions(Settings.INTRO_DEFAULT_OPTIONS);
        }

        self.intro.setOptions(dataJson);
        self.intro.exit();
        self.intro.onafterchange(function(targetElement) {
          if (this._direction === 'forward') {
            self.forwardStep = this._currentStep;
          }

          if ($(targetElement).hasClass('introjsFloatingElement')) {
            this.nextStep();
          }
          else if ($(targetElement).attr('href') === '#export_button' && $(targetElement).hasClass('disabled_toolbar')) {
            this.exit();
          }
        }).start();
      },
      error: function(jqXHR, textStatus, errorThrown) {
        console.error('PLUGIN INTRO -> ' + jqXHR);
        console.error('PLUGIN INTRO -> ' + textStatus);
        console.error('PLUGIN INTRO -> ' + errorThrown);
      }
    });
  },

  previousStep: function() {
    var currentStep = this.intro._currentStep;
    var introItems = this.intro._introItems;
    var previousStep;

    if (this.forwardStep === currentStep &&
        introItems[currentStep - 1].position === 'floating') {

      while (currentStep > 0) {
        if (introItems[currentStep - 1].position === 'floating') {
          currentStep -= 1;
        }
        else {
          previousStep = currentStep;
          currentStep = 0;
        }
      }
    }

    if (previousStep) {
      this.intro.goToStep(previousStep);
    }
  },

  nextStep: function() {
    this.intro.nextStep();
  }
};

/**
 * Show help using intro.js with the Inter-Window Communication.
 * {@link https://github.com/aml-development/ozp-iwc}
 */
var ShowHelpIntro = Backbone.View.extend({
  initialize: function(args) {
    var self = this;

    // Creating a client IWC.
    var iwc = new ozpIwc.Client(Settings.OZP_IWC_CLIENT_URI);

    // The Intents API is accessed through the intents property
    // of a connected IWC Client.
    var intents = iwc.intents;

    // Testing if the client can connect.
    iwc.connect().then(function() {
      console.log('PLUGIN INTRO -> IWC client connected with address: ', iwc.address);
    }).catch(function(error) {
      console.error('PLUGIN INTRO -> IWC client failed to connect: ', error);
    });

    // The IWC uses the concept of references when accessing resources.
    // References are objects with auto-generated functionality to perform
    // actions on a given resource.

    // If the registration node path matches
    // /{minor}/{major}/{action} ("/application/json/view")
    // the handler Id will be generated automatically and
    // returned in the promise resolution.

    // If the registration node path matches
    // /{minor}/{major}/{action}/{handlerId} ("/application/json/view/123")
    // the handler Id given will be used.
    var funcRef = new intents.Reference(Settings.OZP_IWC_REFERENCE_PATH.intro);

    // When registering an intent handler, two entity properties
    // are used to make choosing a handler easier for the end user:

    // 1 - label: A short string noting the widget handling the intent (typically the widget title);
    // 2 - icon: A url path to a icon to use for the widget.
    var config = {
      label: Settings.OZP_IWC_CONFIG.label,
      icon: Settings.OZP_IWC_CONFIG.icon
    };

    // The callback registered with the register action.
    var onInvoke = function(data, reply) {
      if (reply.entity.status === 'ok') {
        if (_.isString(data)) {
          Saiku.intro.start({ fileName: data });
        }
        else {
          Saiku.intro.start(data);
        }

        _.delay(function() {
          $('.introjs-prevbutton').on('click', self.attach_event_previous_step);
        }, 3000);
      }
    };

    // Registers a handler function to a node to be called when invoked by others.
    funcRef.register(config, onInvoke);
  },

  attach_event_previous_step: function(event) {
    Saiku.intro.previousStep();
  }
});

if (Settings.OZP_IWC_ENABLED) {
  // Start ShowHelpIntro
  Saiku.events.bind('session:new', function() {
    function new_workspace(args) {
      if (typeof args.workspace.showHelpIntro === 'undefined') {
        args.workspace.showHelpIntro = new ShowHelpIntro({ workspace: args.workspace });
      }
    }

    function clear_workspace(args) {
      if (typeof args.workspace.showHelpIntro !== 'undefined') {
        args.workspace.showHelpIntro.$el.hide();
      }
    }

    // Add new tab content
    for (var i = 0, len = Saiku.tabs._tabs.length; i < len; i++) {
      var tab = Saiku.tabs._tabs[i];

      if ($(tab.caption).text() !== 'Home') {
        new_workspace({
          workspace: tab.content
        });
      }
    }

    // Attach ShowHelpIntro to future tabs
    Saiku.session.bind('workspace:new', new_workspace);
    Saiku.session.bind('workspace:clear', clear_workspace);
  });
}
