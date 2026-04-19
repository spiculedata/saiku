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
 * Allow the creation of buckets for queries
 */
var Buckets = Backbone.View.extend({

    events: {
        'click .add_bucket': 'add_bucket',
        'click  a.save' : 'save_bucket',
        'click .bucket' : 'click_bucket',
        'click .delete' : 'delete_bucket'
    },

    bucket_css: { 
        'color': '#555', 
        'margin-right': '5px', 
        'text-decoration': 'none', 
        'border': '1px solid #ccc', 
        'padding': '5px',
        '-moz-border-radius': '3px',
        '-webkit-border-radius': '3px'
    },

    tags: [],
    bucket: null,


     tags_template: function() {
            var self = this;
            var t = "<div class='bucket_items'><ul>" +
                "<li><a href='#' class='add_bucket i18n button' title='Add new tag by selecting cells from your result!'> </a></li>";
            _.each(this.tags, function(tag) {
              t += self.tag_template(tag);
            });     
            t += "</div>";
            return t;
    },

    tag_template: function(tag) {
          var title = tag.name + ": ";
        _.each(tag.saikuTuples, function(tuple) {
            var first = true;
            title += " (";
            _.each(tuple.saikuMembers, function(member) {
                    if (!first) {
                        title += ",  ";
                    }
                    first = false;
                    title += member.uniqueName;
            });
            title += ")";
        });

          return "<li class='seperator'><a href='#" + tag.name + "' title='" + title + "' class='bucket button'>" + tag.name + "</a></li>" 
          + "<li style='padding-left:0px'><a class='delete' href='#" + tag.name + "'>x</a></li>";
    },
    

    initialize: function(args) {
        this.workspace = args.workspace;
        
        // Create a unique ID for use as the CSS selector
        this.id = _.uniqueId("buckets_");
        $(this.el).attr({ id: this.id });
        
        // Bind table rendering to query result event
        _.bindAll(this, "render",  "show", "buildTemplate", "render", "deactivate_add_bucket", 
            "add_bucket", "save_bucket", "click_bucket");
        
        // Add buckets button
        this.add_button();
        this.workspace.toolbar.buckets = this.show;
        
        // Listen to adjust event and rerender buckets
        this.workspace.bind('workspace:adjust', this.render);
        
        $(this.workspace.el).find('.workspace_results')
            .prepend($(this.el).hide());


    },
    
    add_button: function() {
        var $stats_button = 
            $('<a href="#buckets" class="buckets button disabled_toolbar i18n" title="Tags"></a>')
            .css({  'background-image': "url('js/saiku/plugins/Buckets/tag_red.png')",
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
        self.bucket = null;
        if ($(event.target).hasClass('on')) {
            if ($(self.workspace.toolbar.el).find(".zoom_mode.on").length > 0) {
                $(self.workspace.toolbar.el).find(".zoom_mode.on").click();
            }

            
            var schema = self.workspace.query.get('schema');
            var cube = self.workspace.query.get('connection') + "-" + 
                    self.workspace.query.get('catalog') + "-"
                    + ((schema == "" || schema == null) ? "null" : schema) 
                    + "-" + self.workspace.query.get('cube');


            this.repo = new TagRepository({
                cube: cube
            }).fetch({success: this.buildTemplate});
        } else {
            this.workspace.query.action.del("/tag", { 
                            success: this.workspace.query.run
            });
        }
        
    },

    buildTemplate: function(model,response) {
        this.tags = response;
        this.render();
    },
    
    render: function() {
        if (! $(this.workspace.toolbar.el).find('.buckets').hasClass('on')) {
            return;
        }

        $(this.el).empty();

        
        var rendered = this.tags_template();
        var $table = $(rendered);
        if (this.bucket) {
            $table.find('a.bucket[href="#' + this.bucket + '"]').addClass('on');
        }
        $(this.el).append($table);


        
    },

    deactivate_add_bucket: function() {
        var self = this;
        var $addBtn = $(self.el).find('.add_bucket');
        $addBtn.removeClass('on');
        $(self.el).find('.new_bucket').parent().remove();
        return;
    },

    add_bucket: function(event) {
        var self = this;
        var $addBtn = $(self.el).find('.add_bucket');
        if ($addBtn.hasClass('on')) {
            self.deactivate_add_bucket();
            return;
        }
        $addBtn.addClass('on');

        
        $("<li><input id='new_bucket' type='text' class='new_bucket'/></li><li>" 
            + "<a href='#save_bucket' class='i18n save sprite button new_bucket' title='Save Tag'></a></li>")
                            .insertAfter($(self.el).find('.bucket_items .add_bucket').parent());

        var clicked = function(event) {
            $target = $(event.target).hasClass('data') ?
                            $(event.target).find('div') : $(event.target);

            if ($target.parent().hasClass('selected')) {
                $target.parent().removeClass('selected');
            } else {
                $target.parent().addClass('selected');
            }
        };

        $(self.workspace.el).find("td.data").addClass('cellhighlight').unbind('click').click(clicked);
        $(self.workspace.el).find(".query_scenario, .drillthrough, .drillthrough_export").removeClass('on');
    },

    save_bucket: function() {
        var self = this;
        var $cells = $(self.workspace.el).find("td.selected div");
        if($cells.size() < 1) {
            alert("You need to select at least 1 cell for tagging before you can save!");
            return;
        }
        var positions = "";
        $.each($cells, function(index,element) {
            if (index > 0) {
                positions += ",";
            }
            positions += $(element).attr('rel');
            
        });

        $(self.workspace.el).find("td.data").removeClass('cellhighlight').unbind('click');
        $(self.workspace.el).find("td.selected").removeClass('selected');

        var tagname = $(self.el).find('#new_bucket').val();
        
        var saveBucket = function(model, response) {
            self.tags.push(model);
            self.deactivate_add_bucket();
                $tag = $(self.tag_template(model))
                            .appendTo($(self.el).find('.bucket_items ul'));
        };

        var schema = self.workspace.query.get('schema');
        var cube = self.workspace.query.get('connection') + "-" + 
                self.workspace.query.get('catalog') + "-"
                + ((schema == "" || schema == null) ? "null" : schema) 
                + "-" + self.workspace.query.get('cube');

        (new SaikuTag({
            positions: positions,
            name: tagname,
            cube: cube,
            queryname: self.workspace.query.id
        },{success: saveBucket})).save();

    },
    click_bucket: function(event) {
        var tagName = $(event.target).attr('href').replace('#','');
        var self = this;
        if ($(event.target).hasClass('on')) {
            $(event.target).removeClass('on');
            self.bucket = null;
            this.workspace.query.action.del("/tag", { 
                            success: this.workspace.query.run
            });
        } else {
            $(event.target).addClass('on');
            self.bucket = tagName;
            _.each(this.tags, function(tag) {
                if (tag.name == tagName) {
                    self.workspace.query.action.put("/tag", { 
                          success: self.workspace.query.run, data: {tag:JSON.stringify(tag)}});
                }
            });     
        }
        $(event.target).parent().siblings().find('.on').removeClass('on');
    },

    delete_bucket: function(event) {
        var tagname = $(event.target).attr('href').replace('#','');
        var self = this;
        var al= function() {
            alert('y');
        };

        var schema = self.workspace.query.get('schema');
        var cube = self.workspace.query.get('connection') + "-" + 
                self.workspace.query.get('catalog') + "-"
                + ((schema == "" || schema == null) ? "null" : schema) 
                + "-" + self.workspace.query.get('cube');


        (new SaikuTag({
            name: tagname,
            id: 'dummy',
            cube: cube
        },{})).destroy();

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
            if (typeof args.workspace.buckets == "undefined") {
                args.workspace.buckets = new Buckets({ workspace: args.workspace });
                args.workspace.bind('query:result', args.workspace.buckets.deactivate_add_bucket);
            }
        }

        function clear_workspace(args) {
                    if (typeof args.workspace.buckets != "undefined") {
                        $(args.workspace.buckets.el).hide();

                    }
        }
        
        // Attach stats to existing tabs
        for(var i = 0, len = Saiku.tabs._tabs.length; i < len; i++) {
            var tab = Saiku.tabs._tabs[i];
            new_workspace({
                workspace: tab.content
            });
        };

        // Attach stats to future tabs
        Saiku.session.bind("workspace:new", new_workspace);
        Saiku.session.bind("workspace:clear", clear_workspace);
    });


var SaikuTag = Backbone.Model.extend({
    initialize: function(args, options) {
        _.extend(this.attributes, args);
        if (options != null && options.success) {
            this.parse = options.success;
        }

    },

    url: function() {
        return encodeURI(Saiku.session.username + "/tags/" + this.get('cube') + "/" + this.get('name'));
    }
});

/**
 * Repository adapter
 */
var TagRepository = Backbone.Collection.extend({
    model: SaikuTag,
    
    initialize: function(args, options) {
        this.cube = args.cube;
    },
    
    
    url: function() {
        return encodeURI(Saiku.session.username + "/tags/" + this.cube);
    }
});
 
