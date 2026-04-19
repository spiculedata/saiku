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
 * Model which fetches the dimensions and measures of a cube
 */
var Cube = Backbone.Model.extend({
    initialize: function(args) {
        this.url = Saiku.session.username + "/discover/" +
            args.key + "/metadata";
    },

    parse: function(response) {
        var template_dimensions = _.template($("#template-dimensions").html(), { dimensions: response.dimensions });
        var template_measures = _.template($("#template-measures").html(), { measures: response.measures });
        var template_attributes = _.template($("#template-attributes").html(), { cube: response });

        this.set({
            template_measures: template_measures,
            template_dimensions: template_dimensions,
            template_attributes: $(template_attributes).html(),
            data: response
        });


        if (typeof localStorage !== "undefined" && localStorage) {
            localStorage.setItem("cube." + this.get('key'), JSON.stringify(this).replace(/ {3}/g, ''));
        }

        return response;
    }
});
