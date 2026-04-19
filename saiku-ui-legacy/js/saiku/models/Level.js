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
 * Model which fetches the levels
 *
 * @class Level
 */
var Level = Backbone.Model.extend({
    /**
     * The constructor of view, it will be called when the view is first created
     *
     * @constructor
     * @private
     */
    initialize: function(args, options) {
        if (options && !(_.isEmpty(options))) {
            this.ui = options.ui;
            this.cube = options.cube;
            this.dimension = options.dimension;
            this.hierarchy = options.hierarchy;
        }
    },

    /**
     * Returns the relative URL where the model's resource would be located on the server
     *
     * @method url
     * @private
     * @return {String} Relative URL
     */
    url: function() {
        return Saiku.session.username + '/discover/' + this.cube + '/dimensions/' + this.dimension + '/hierarchies/' + 
            this.hierarchy + '/levels';
    }
});

/**
 * Model which fetches the members
 *
 * @class LevelMember
 */
var LevelMember = Backbone.Model.extend({
    /**
     * The constructor of view, it will be called when the view is first created
     *
     * @constructor
     * @private
     */
    initialize: function(args, options) {
        if (options && !(_.isEmpty(options))) {
            this.ui = options.ui;
            this.cube = options.cube;
            this.dimension = options.dimension;
            this.hierarchy = options.hierarchy;
            this.level = options.level;
        }
    },

    /**
     * Returns the relative URL where the model's resource would be located on the server
     *
     * @method url
     * @private
     * @return {String} Relative URL
     */
    url: function() {
        return Saiku.session.username + '/discover/' + this.cube + '/dimensions/' + this.dimension + '/hierarchies/' + 
            this.hierarchy + '/levels/' + encodeURIComponent(this.level);
    }
});

/**
 * Model which fetches the child members
 *
 * @class LevelMember
 */
var LevelChildMember = Backbone.Model.extend({
    /**
     * The constructor of view, it will be called when the view is first created
     *
     * @constructor
     * @private
     */
    initialize: function(args, options) {
        if (options && !(_.isEmpty(options))) {
            this.ui = options.ui;
            this.cube = options.cube;
            this.uniqueName = options.uniqueName;
            this.levelUniqueName = options.levelUniqueName;
            this.mname = options.mname;
            this.mcaption = options.mcaption;
        }
    },

    /**
     * Returns the relative URL where the model's resource would be located on the server
     *
     * @method url
     * @private
     * @return {String} Relative URL
     */
    url: function() {
        return Saiku.session.username + '/discover/' + this.cube + '/member/' + encodeURIComponent(this.uniqueName) + '/children';
    }
});