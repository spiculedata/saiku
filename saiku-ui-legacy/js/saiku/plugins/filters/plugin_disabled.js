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
 * Allow the creation of filters for queries
 */
var Filters = Backbone.View.extend({

    events: {
        'click .add_filter': 'add_filter',
        'click .edit_filter': 'add_filter',
        'click  a.save' : 'save_filter',
        'click a.refresh_filters' : 'render_menu',
        'click .filter' : 'click_filter',
        'click .delete' : 'delete_filter'
    },

    filter_css: {
        'color': '#555',
        'margin-right': '5px',
        'text-decoration': 'none',
        'border': '1px solid #ccc',
        'padding': '5px',
        '-moz-border-radius': '3px',
        '-webkit-border-radius': '3px'
    },

    filters: [],
    currentFilter: null,


     filters_template: function() {
            var self = this;
            var t = "<div class='filter_items'><ul>"
                + "<li><a href='#' class='i18n button refresh_filters' title='Refresh Filters'></a></li>"
                + "<li><a href='#add' class='add_filter i18n button' title='Add new filter based on your result!'> </a></li>"
                + "<li><a href='#edit' class='edit_filter i18n button' title='Edit filter based on your result!'> </a></li>"
                ;
            _.each(this.filters, function(filter) {
              t += self.filter_template(filter);
            });
            t += "</div>";
            return t;
    },

    filter_template: function(filter) {
          var title = "" + filter.name + ": \n "
                + "dimension:" + filter.dimension.caption + " \n(";
          var first = true;

        _.each(filter.members, function(member) {
            if (!first) {
                title += "\n, ";
            }
            first = false;
            title += member.caption;
        });
        title += ")";

          return "<li class='seperator'><a href='#" + filter.name + "' title='" + title + "' class='filter button'>" + filter.name + " (" + filter.members.length + ")</a></li>"
          + "<li style='padding-left:0px'><a class='delete' href='#" + filter.name + "'>x</a></li>";
    },


    initialize: function(args) {
        this.workspace = args.workspace;

        // Create a unique ID for use as the CSS selector
        this.id = _.uniqueId("filters_");
        $(this.el).attr({ id: this.id });

        // Bind table rendering to query result event
        _.bindAll(this, "render",  "show", "buildTemplate", "render", "deactivate_add_filter",
            "add_filter", "save_filter", "click_filter", "render_menu", "post_activation");

        // Add filters button
        this.add_button();
        this.workspace.toolbar.filters = this.show;

        // Listen to adjust event and rerender filters
        //this.workspace.bind('workspace:adjust', this.render);

        $(this.workspace.el).find('.workspace_results')
            .prepend($(this.el).hide());


    },

    add_button: function() {
        var $stats_button =
            $('<a href="#filters" class="filters button disabled_toolbar i18n" title="Filters"></a>')
            .css({  'background-image': "url('js/saiku/plugins/filters/tag_red.png')",
                    'background-repeat':'no-repeat',
                    'background-position':'50% 50%'
                });

        var $stats_li = $('<li class="seperator"></li>').append($stats_button);
        $(this.workspace.toolbar.el).find("ul").append($stats_li);
    },

    show: function(event, ui) {
        var self = this;
        $(this.el).toggle();
        $(event.target).toggleClass('on');

        if ($(event.target).hasClass('on')) {
            this.render_menu();
        } else {
            this.workspace.query.action.del("/filter", {
                            success: this.workspace.query.run,
                            data: this.currentFilter
            });
        }

    },

    render_menu: function() {
            this.repo = new FilterRepository({
                query: this.workspace.query.get('name')
            }).fetch({success: this.buildTemplate, data: { query : this.workspace.query.id }});

    },

    buildTemplate: function(model,response) {
        this.filters = response;
        this.render();
    },

    render: function() {
        /*
        if (! $(this.workspace.toolbar.el).find('.filters').hasClass('on')) {
            return;
        }
        */

        $(this.el).empty();


        var rendered = this.filters_template();
        var $table = $(rendered);
        $(this.el).append($table)
        this.currentFilter = null;
/*
        if (this.currentFilter != null && this.filters.hasOwnProperty(this.currentFilter.name)) {
            //$(this.el).find('a.filter[href="#' + this.currentFilter.name + '"]').addClass('on');
            $(this.el).find('a.filter[href="#' + this.currentFilter.name + '"]').click();
        } else {
            this.currentFilter = null;
        }
*/


    },

    deactivate_add_filter: function(other) {
        var self = this;
        $(self.el).find('a.add_filter, a.edit_filter').removeClass('on');
        $(self.el).find('.new_filter_input, .edit_filter_input, .new_filter').parent().remove();
        return false;
    },

    add_filter: function(event) {
        var self = this;
        var filterAction = $(event.target).attr('href').replace('#','');
        var other = filterAction == "add" ? "edit" : "add";

        this.deactivate_add_filter(other);

        var $addBtn = $(event.target);
        if ($addBtn.hasClass('on')) {
            self.deactivate_add_filter();
            return;
        }
        $addBtn.addClass('on');
        var queryFilter = null;
        this.workspace.query.action.gett("/filter", {
                            success: function(model, response) {
                                if (response != null && response.dimension != null && response.members.length > 0) {
                                    queryFilter = response;
                                } else {
                                    alert("Filter cannot be created. Dimension: " + FilterConfig.dimension + " not part of the query or no matching members found!");
                                    self.deactivate_add_filter();
                                }

                            },
                            data: {
                                dimension: FilterConfig.dimension,
                                hierarchy: FilterConfig.hierarchy,
                                level: FilterConfig.level
                            },
                            async: false
        });
        if (queryFilter != null) {
            var selectedName = (this.currentFilter != null) ? this.currentFilter.name : null;

            this.currentFilter = queryFilter;

            if (filterAction == "add") {
                    $("<li><input id='new_filter' type='text' class='new_filter_input'/> &nbsp; (" + queryFilter.members.length + ") &nbsp; </li>"
                        + "<li><a href='#save_filter' class='i18n save sprite button new_filter' title='Save Filter'></a></li>")
                                        .insertAfter($(self.el).find('.filter_items .add_filter').parent());

            } else {
                var edit = "<li><select id='new_filter' class='edit_filter_input'>";
                _.each(self.filters, function (filter) {
                    edit += "<option value='" + safe_tags_replace(filter.name) + "'>" + safe_tags_replace(filter.name) + "</option>";
                });
                edit += "</select> &nbsp; (" + queryFilter.members.length + ") &nbsp; </li>";
                $(edit + "<li><a href='#save_filter' class='i18n save sprite button new_filter' title='Save Filter'></a></li>")
                    .insertAfter($(self.el).find('.filter_items .edit_filter').parent());

                $('#new_filter').val(selectedName);
            }
        }


        $(self.workspace.el).find(".query_scenario, .drillthrough, .drillthrough_export").removeClass('on');
    },

    save_filter: function() {
        var self = this;
        var filtername = $(self.el).find('#new_filter').val();
        this.currentFilter.name = filtername;

        var savefilter = function(response, model) {
            self.filters[filtername] = model;
            self.deactivate_add_filter();
            self.render();
        };

// FILTER STUFF
        (new SaikuFilter({
            filtername: self.currentFilter.name,
            filter: JSON.stringify(self.currentFilter),
            queryname: self.workspace.query.id
        })).save({}, {
            success: savefilter
        });

    },

    click_filter: function(event) {
        var filterName = $(event.target).attr('href').replace('#','');
        this.currentFilter = this.filters.hasOwnProperty(filterName) ? this.filters[filterName] : null;
        var self = this;
        if ($(event.target).hasClass('on')) {
            $(event.target).removeClass('on');
            this.workspace.query.action.del("/filter", {
                            success: this.post_activation
            });
        } else {
            $(event.target).addClass('on');

            _.each(this.filters, function(filter) {
                if (filter.name == filterName) {
                    self.workspace.query.action.put("/filter", {
                          success: self.post_activation, data: {filter:JSON.stringify(filter)}});
                }
            });
        }
        $(event.target).parent().siblings().find('.on').removeClass('on');
    },

    post_activation: function(response, model) {
        var query = response.hasOwnProperty('uniqueName') ? response : model;
        this.workspace.query.parse(query);
        this.workspace.sync_query();
    },

    delete_filter: function(event) {
        var filtername = $(event.target).attr('href').replace('#','');
        var self = this;


        (new SaikuFilter({
            filtername: filtername,
            id: _.uniqueId("filter_")
        },{})).destroy({
            data: {filtername : filtername}
        });

        $(event.target).parent().prev().remove();
        $(event.target).parent().remove();
    }
});

/**
 * Start Plugin
 */
 Saiku.events.bind('session:new', function(session) {

        function new_workspace(args) {
            // Add stats element
            if (typeof args.workspace.filters == "undefined") {
                args.workspace.filters = new Filters({ workspace: args.workspace });
                args.workspace.bind('query:result', args.workspace.filters.deactivate_add_filter);
            }
        }

        function clear_workspace(args) {
                    if (typeof args.workspace.filters != "undefined") {
                        $(args.workspace.filters.el).hide();

                    }
        }

        // Attach stats to existing tabs
        for(var i = 0, len = Saiku.tabs._tabs.length; i < len; i++) {
            var tab = Saiku.tabs._tabs[i];
            if(tab.caption != "Home") {

                new_workspace({
                    workspace: tab.content
                });
            }
        };

        // Attach stats to future tabs
        Saiku.session.bind("workspace:new", new_workspace);
        Saiku.session.bind("workspace:clear", clear_workspace);
    });


var SaikuFilter = Backbone.Model.extend({
    filtername: null,

    initialize: function(args, options) {
        _.extend(this.attributes, args);
        if (options != null && options.success) {
            this.parse = options.success;
        }

    },

    url: function() {
        return encodeURI(Saiku.session.username + "/filters/" + this.get('filtername'));
    }
});

/**
 * Repository adapter
 */
var FilterRepository = Backbone.Collection.extend({
    model: SaikuFilter,

    initialize: function(args, options) {},


    url: function() {
        return encodeURI(Saiku.session.username + "/filters/");
    }
});

