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
 * Router for opening query when session is initialized
 */
var QueryRouter = Backbone.Router.extend({
    routes: {
        'query/open/*query_name': 'open_query',
        'query/open': 'open_query_repository'
    },

    open_query: function(query_name) {
        Settings.ACTION = "OPEN_QUERY";
        var options = {};
        var dataType = "text";
        if (!Settings.BIPLUGIN5 && Settings.BIPLUGIN) {
            var file = (Settings.GET.SOLUTION ? (Settings.GET.SOLUTION + "/") : "") +
                       (Settings.GET.PATH && Settings.GET.PATH != "/" ? (Settings.GET.PATH + "/") : "") +
                       (Settings.GET.ACTION || "");
            options = {
                file: file
            };
        } else {
            options = {
                file: query_name
            };
        }

        var params = _.extend({
                file: options.file
            }, Settings.PARAMS);

        var dialog = {
            populate: function(repository) {
                if (repository && repository.length > 0) {
                    var f = repository[0];
                    var query = new Query(params,{ name: options.file });

                    Saiku.tabs.add(new Workspace({ query: query, item: repository[0] }));

                } else {
                    Saiku.tabs.add(new Workspace());
                }
                Settings.INITIAL_QUERY = false;
            }
        };

		var repositoryFile = new Repository({}, { dialog: dialog, file: options.file }).fetch({ async: false, data: { path: options.file }});






    },

    open_query_repository: function( ) {
        Toolbar.prototype.open_query( );
    }
});

Saiku.routers.push(new QueryRouter());
