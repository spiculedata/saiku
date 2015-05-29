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
 * Object which handles sessionworkspace and stores connections and cubes
 * @param username
 * @param password
 * @returns {Session}
 */
var SessionWorkspace = Backbone.Model.extend({
        
    initialize: function(args, options) {
        // Attach a custom event bus to this model
        _.extend(this, Backbone.Events);
        _.bindAll(this, "process_datasources", "prefetch_dimensions");
        this.initialized = false;
        this.first = true;
        // Check expiration on localStorage
        if (typeof localStorage !== "undefined" && localStorage) {
            if (!Settings.LOCALSTORAGE_EXPIRATION || Settings.LOCALSTORAGE_EXPIRATION === 0) {
                localStorage.clear();
            }
            if (localStorage.getItem('expiration') && localStorage.getItem('expiration') <= (new Date()).getTime()) {
                localStorage.clear();
            } else if (!localStorage.getItem('saiku-version') || (localStorage.getItem('saiku-version') !== Settings.VERSION) ) {
                localStorage.clear();
                localStorage.setItem('saiku-version', Settings.VERSION);
            }
        }        
        Saiku.ui.block("Loading datasources....");
        this.fetch({success:this.process_datasources},{});
        
    },

    refresh: function() {
        if (typeof localStorage !== "undefined" && localStorage) {
            localStorage.clear();
        }

        this.clear();

        if(typeof localStorage !== "undefined" && localStorage) {
          localStorage.setItem('saiku-version', Settings.VERSION);  
        }

        this.fetch({success:this.process_datasources},{});
    },
        
    destroy: function() {
        if (typeof localStorage !== "undefined" && localStorage) {
            localStorage.clear();
        }
        return false;
    },
    
    process_datasources: function(model, response) {
        // Save session in localStorage for other tabs to use
        if (typeof localStorage !== "undefined" && localStorage && localStorage.getItem('session') === null) {
            localStorage.setItem('session', JSON.stringify(response));
            
            // Set expiration on localStorage to one day in the future
            var expires = (new Date()).getTime() +  Settings.LOCALSTORAGE_EXPIRATION;
            if (typeof localStorage !== "undefined" && localStorage) {
                localStorage.setItem('expiration', expires);
            }
        }

        // Generate cube navigation for reuse
        this.cube_navigation = _.template($("#template-cubes").html())({
            connections: response
        });
        
        
        // Create cube objects
        this.cube = {};
        this.connections = response;
        _.delay(this.prefetch_dimensions, 20);
        
        if (!this.initialized) {
            // Show UI
            $(Saiku.toolbar.el).prependTo($("#header"));
            $("#header").show();
            Saiku.ui.unblock();
            // Add initial tab
            Saiku.tabs.render();
            
            // Notify the rest of the application that login was successful
            Saiku.events.trigger('session:new', {
                session: this
            });

            var paramsURI = Saiku.URLParams.paramsURI();

            if (!(_.has(paramsURI, 'splash'))) {
                paramsURI.splash = true;
            }
            else if (_.has(paramsURI, 'splash') && paramsURI.splash ||
                _.has(paramsURI, 'splash') && paramsURI.splash === null) {
                paramsURI.splash = true;
            }

            if (!Settings.INITIAL_QUERY) {
                Saiku.tabs.add(new Workspace());
            }
        } else {
            if (!Settings.INITIAL_QUERY) {
                Saiku.tabs.add(new Workspace());
            }

        }
    },
    
    prefetch_dimensions: function() {        
        for(var i = 0, iLen = this.connections.length; i < iLen; i++) {
            var connection = this.connections[i];
            for(var j = 0, jLen = connection.catalogs.length; j < jLen; j++) {
                var catalog = connection.catalogs[j];
                for(var k = 0, kLen = catalog.schemas.length; k < kLen; k++) {
                    var schema = catalog.schemas[k];
                    for(var l = 0, lLen = schema.cubes.length; l < lLen; l++) {
                        var cube = schema.cubes[l];
                        var key = connection.name + "/" + catalog.name + "/" +
                            ((schema.name === "" || schema.name === null) ? "null" : schema.name) +
                            "/" + encodeURIComponent(cube.name);

                        if (typeof localStorage !== "undefined" && localStorage && 
                            localStorage.getItem("cube." + key) !== null) {
                            this.cube[key] = new Cube(JSON.parse(localStorage.getItem("cube." + key)));
                        } else {
                            this.cube[key] = new Cube({ key: key });
                            if (Settings.DIMENSION_PREFETCH === true) {
                                this.cube[key].fetch();
                            }
                        }
                    }
                }
            }
        }
        
        // Start routing
        if (!this.initialized && Backbone.history) {
            Backbone.history.start();
            this.initialized = true;
        }
    },
    
    url: function() {
        if (this.first) {
            this.first = false;
            return encodeURI(Saiku.session.username + "/discover");
        }
        else {
            return encodeURI(Saiku.session.username + "/discover/refresh");
        }
    }
});
