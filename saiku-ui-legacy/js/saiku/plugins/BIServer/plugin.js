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
 * Object which controls save to solution repository
 */
/**
 * Changelog:
 *     2011.12.29 - RNP (rodrigonovo@gmail.com) - replaced the "top."
 *       reference by the "window.parent" reference. This was done to
 *       make saiku work in systems where Pentaho runs inside an iframe.
 *
**/

var puc = {
    allowSave: function(isAllowed) {
        if(window.parent.mantle_initialized !== undefined && window.parent.mantle_initialized &&
            window.parent.enableAdhocSave ) {
            if (window.ALLOW_PUC_SAVE === undefined || ALLOW_PUC_SAVE) {
                window.parent.enableAdhocSave(isAllowed);
            }
        }
    },

    refresh_repo: function() {
        if(window.parent.mantle_initialized !== undefined && window.parent.mantle_initialized) {
            window.parent.mantle_refreshRepository();
        }
    },

    save_to_solution: function(filename, solution, path, type, overwrite) {
        var query = Saiku.tabs._tabs[0].content.query;
        query.action.gett("/xml", {
            success: function(model, response) {
                    filename = filename
                                && filename.length > ".saiku".length
                                && filename.substring(filename.length - ".saiku".length,filename.length) == ".saiku" ? filename : filename + ".saiku";

                    var file = (solution ? (solution + "/") : "")
                        + (path ? (path + "/") : "")
                        + (filename || "");

                    (new SavedQuery({
                        name: filename,
                        file: file,
                        content: response.xml
                })).save({ success: function() {
                    puc.refresh_repo();
                }});
            }
        });
    }
};

/**
 * Objects required for BI server integration
 */
var RepositoryBrowserControllerProxy = function() {
    this.remoteSave = puc.save_to_solution;
};

var Wiz = function() {
    this.currPgNum = 0;
};

var WaqrProxy = function() {
    this.wiz = new Wiz();
    this.repositoryBrowserController = new RepositoryBrowserControllerProxy();
};

var gCtrlr = new WaqrProxy();

var savePg0 = function() {};

/**
 * Manually start session
 */
if (Settings.BIPLUGIN) {
    Settings.PLUGIN = true;
    Settings.REST_URL = "../saiku/";
    if (Settings.BIPLUGIN5) {
        Settings.REST_URL = "../../plugin/saiku/api/";
    }



    $(document).ready(function() {
    var pluginUrl = Settings.REST_URL + "load/plugin/plugins";
        if (Settings.DEBUG) {
            pluginUrl += "?debug=true";
        }
        $.getScript(pluginUrl, function() {  //).promise().done(function() {
                Saiku.session = new Session();
        })/*.fail(function() {
                Saiku.session = new Session();
        })*/;


    });
}

/**
 * Bind callbacks to workspace
 */
var BIPlugin = {
    bind_callbacks: function(workspace) {
        $(workspace.toolbar.el).find('.run').parent().removeClass('seperator');

        // Toggle save button
        workspace.bind('query:result', function(args) {
            var isAllowed = args.data.cellset &&
                args.data.cellset.length > 0;
            puc.allowSave(isAllowed);
        });
    }
};

/**
 * If plugin active, customize chrome
 */
Saiku.events.bind('session:new', function(session) {
    if (Settings.PLUGIN) {
        // Remove tabs and global toolbar
        $('#header').remove();
        // Bind to workspace
        if (Saiku.tabs._tabs[0] && Saiku.tabs._tabs[0].content) {
            BIPlugin.bind_callbacks(Saiku.tabs._tabs[0].content);
        }

        Saiku.session.bind('workspace:new', function(args) {
            BIPlugin.bind_callbacks(args.workspace);
        });
    }
});

var Datasources = Backbone.Model.extend({
    list: [],

    initialize: function(args, options) {
        // Attach a custom event bus to this model
        _.extend(this, Backbone.Events);
    },

    parse: function(response) {
        this.set({
            list: response
        });

        return response;
    },
    url: function() {
        return (Saiku.session.username + "/datasources");
    }
});


// if (Settings.PLUGIN) {
//     window.parent.getSaikuMdx = function() {
//             var myself = this;
//             var query = Saiku.tabs._tabs[0].content.query;
//             query.clear();
//             query.fetch({
//             success: function(model, response) {
//                     var ds = new Datasources();
//                     ds.fetch({
//                         success: function(dmodel, dresponse) {
//                             for (var i = 0, len = dresponse.length; i < len; i ++) {
//                                 if (dresponse[i].name == response.cube.connectionName) {
//                                     var urlParts = dresponse[i].properties.location.split(';');
//                                     var jndi = "";
//                                     var catalog = "";
//                                     $.each(urlParts,function(index, value) {
//                                         var prop = value.split('=');
//                                         if (prop[0] == "DataSource") {
//                                             jndi = prop[1];
//                                         }
//                                        if (prop[0] == "Catalog") {
//                                             catalog = prop[1];
//                                         }
//                                     });
//                                     var saikuStub = {
//                                         connection: dresponse[i].name,
//                                         catalog: catalog,
//                                         jndi: jndi,
//                                         mdx: response.mdx
//                                     }
//                                     window.parent.saveSaiku(saikuStub);
//                                 }
//                             }
//                         }
//                     })

//                 }
//             });
//     };
// }

if (Settings.PLUGIN) {
    window.parent.getSaikuMdx = function() {
            var query = Saiku.tabs._tabs[Saiku.tabs.queryCount-1].content.query;
            var saikuStub = {
                  connection: query.model.cube.connection,
                  catalog: query.model.cube.catalog,
                  jndi: '',
                  mdx: query.model.mdx
                }
            window.parent.saveSaiku(saikuStub);
    };
}
