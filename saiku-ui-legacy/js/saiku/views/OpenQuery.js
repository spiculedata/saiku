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
 * The open query tab (Repository viewer)
 */
var OpenQuery = Backbone.View.extend({
    className: 'tab_container',

    events: {
        'click .query': 'view_query',
        'dblclick .query': 'select_and_open_query',
        'click .add_folder' : 'add_folder',
        'click li.folder': 'toggle_folder',
        'click .workspace_toolbar a.button' : 'prevent_default',
        'click .workspace_toolbar a.run': 'open_query',
        'click .workspace_toolbar a.edit': 'edit_query',
        'click .workspace_toolbar [href=#edit_folder]': 'edit_folder',
        'click .workspace_toolbar [href=#delete_folder]': 'delete_repoObject',
        'click .workspace_toolbar [href=#delete_query]': 'delete_repoObject',
        'click .workspace_toolbar [href=#edit_permissions]': 'edit_permissions',
        'click .queries' : 'click_canvas',
        'keyup .repository_search_file' : 'search_file',
        'click .cancel_search' : 'cancel_search'
    },

    template: function() {
        return _.template($("#template-open-dialog").html())();
    },

    template_repository_objects: function( repository ) {
        var self = this;
        $(this.el).find('.sidebar ul').html(
            _.template( $( '#template-repository-objects' ).html( ) )( {
                repoObjects: repository
            } )
        );
    },

    caption: function() {
        return '<span class="i18n">Repository</span>';
    },

    render: function() {
        // Load template
        $(this.el).html(this.template());

        // Adjust tab when selected
        this.tab.bind('tab:select', this.fetch_queries);
        this.tab.bind('tab:select', this.adjust);
        $(window).resize(this.adjust);

        var self = this;
        var menuitems = {
                    "open": {name: "Open", i18n: true },
                    "edit": {name: "Edit", i18n: true },
//                    "rename": {name: "Rename", i18n: true },
                    "delete": {name: "Delete", i18n: true },
                    "move": {name: "Move", i18n: true},
                    "sep1": "---------",
                    "new": {name: "New Folder", i18n: true},
                    "opencontents": {name: "Open Folder Contents", i18n: true}
        };
        $.each(menuitems, function(key, item){
            recursive_menu_translate(item, Saiku.i18n.po_file);
        });

        $.contextMenu('destroy', 'li.query, div.folder_row');
        $.contextMenu({
                selector: 'li.query, div.folder_row',
                events: {
                    show: function(opt) {
                        $( self.el ).find( '.selected' ).removeClass( 'selected' );
                        $(this).addClass('selected');
                        var path = $(this).find('a').attr('href').replace('#', '');
                        var item = self.queries[path];

                        if (typeof item.acl != "undefined" && _.indexOf(item.acl, "WRITE") <  0) {
                            opt.commands['delete'].disabled = true;
                            opt.items['delete'].disabled = true;
                            opt.commands['edit'].disabled = true;
                            opt.items['edit'].disabled = true;
                            opt.commands['move'].disabled = true;
                            opt.items['move'].disabled = true;

                        } else {
                            opt.commands['delete'].disabled = false;
                            opt.items['delete'].disabled = false;
                            opt.commands['edit'].disabled = false;
                            opt.items['edit'].disabled = false;
                            opt.commands['move'].disabled = false;
                            opt.items['move'].disabled = false;
                        }

                        if ($(this).hasClass('folder_row')) {
                            opt.commands.open.disabled = true;
                            opt.items.open.disabled = true;
                        } else {
                            opt.commands.open.disabled = false;
                            opt.items.open.disabled = false;
                        }
                    }

                },
                callback: function(key, options) {
                    var path = $(this).find('a').attr('href').replace('#', '');
                    var item = self.queries[path];
                    self.selected_query = new SavedQuery({ file: path, name: item.name, type: item.type });
                    if (key == "open" && $(this).hasClass('query')) {
                        self.open_query();
                    } if (key == "edit" && $(this).hasClass('query')) {
                        self.edit_query();
                    } else if (key == "new") {
                        self.add_folder();
                    } else if (key == "delete") {
                        self.delete_repoObject();
                    } else if(key == "move"){
                        self.move_repoObject();
                    } else if(key =="opencontents"){
                        self.open_contents();
                    }


                },
                items: menuitems
            });

        // Fire off new openQuery event
        Saiku.session.trigger('openQuery:new', { openQuery: this });

        if (Settings.REPOSITORY_LAZY) {
            this.$el.find('.repository_search_file').prop('disabled', true);
        }

        return this;
    },

    initialize: function(args) {
        // Maintain `this`
        _.bindAll(this, "adjust", "fetch_queries",
                "clear_query","select_and_open_query", "cancel_search", "add_folder");

        // Initialize repository
        this.repository = new Repository({}, { dialog: this });
    },

    fetch_queries: function() {
        this.repository.fetch();
    },

    populate: function( repository ) {
        var self = this;
        self.template_repository_objects( repository );
        self.queries = {};
        function getQueries( entries ) {
            _.forEach( entries, function( entry ) {
                self.queries[ entry.path ] = entry;
                if( entry.type === 'FOLDER' ) {
                    getQueries( entry.repoObjects );
                }
            } );
        }
        getQueries( repository );
    },

    search_file: function(event) {
        var filter = $(this.el).find('.repository_search_file').val().toLowerCase();
        var isEmpty = (typeof filter == "undefined" || filter === "" || filter === null);
        if (isEmpty || event.which == 27 || event.which == 9) {
            this.cancel_search();
        } else {
            if ($(this.el).find('.repository_search_file').val()) {
                $(this.el).find('.cancel_search').show();
            } else {
                $(this.el).find('.cancel_search').hide();
            }
            $(this.el).find('li.query').removeClass('hide');
            $(this.el).find('li.query a').each(function (index) {
                if($(this).text().toLowerCase().indexOf(filter) == -1) {
                    $(this).parent('li.query').addClass('hide');
                }
            });
            $(this.el).find('li.folder').addClass('hide');
            $(this.el).find('li.query').not('.hide').parents('li.folder').removeClass('hide');
            //$(this.el).find( 'li.folder .folder_content').not(':has(.query:visible)').parent().addClass('hide');

            //not(':contains("' + filter + '")').parent().hide();
            $(this.el).find( 'li.folder .folder_row' ).find('.sprite').removeClass( 'collapsed' );
            $(this.el).find( 'li.folder .folder_content' ).removeClass('hide');
        }
        return false;
    },
    cancel_search: function(event) {
        $(this.el).find('input.repository_search_file').val('');
        $(this.el).find('.cancel_search').hide();
        $(this.el).find('li.query, li.folder').removeClass('hide');
        $(this.el).find( '.folder_row' ).find('.sprite').addClass( 'collapsed' );
        $(this.el).find( 'li.folder .folder_content' ).addClass('hide');
        $(this.el).find('.repository_search_file').val('').focus();
        $(this.el).find('.cancel_search').hide();

    },
    view_query: function(event) {
        event.preventDefault( );
        var $currentTarget = $( event.currentTarget );
        var $target = $currentTarget.find('a');
        this.unselect_current_selected( );
        $currentTarget.addClass( 'selected' );
        var path = $target.attr('href').replace('#', '');
        var name = $target.text();
        var query = this.queries[path];
        $( this.el ).find( '.workspace_toolbar' ).removeClass( 'hide' );
        $( this.el ).find( '.for_queries' ).addClass( 'hide' );
        $( this.el ).find( '.for_folder' ).addClass( 'hide' );
        $( this.el ).find( '.add_folder' ).parent().addClass( 'hide' );

        if (typeof query.acl != "undefined" && _.indexOf(query.acl, "READ") > -1) {
            $( this.el ).find( '.for_queries .run' ).parent().removeClass( 'hide' );
        }
        if (typeof query.acl != "undefined" && _.indexOf(query.acl, "WRITE") > -1) {
            $( this.el ).find( '.for_queries .delete' ).parent().removeClass( 'hide' );
            $( this.el ).find( '.for_queries .edit' ).parent().removeClass( 'hide' );
        }
        if (typeof query.acl != "undefined" && _.indexOf(query.acl, "GRANT") > -1) {
            $( this.el ).find( '.for_queries .edit_permissions' ).parent().removeClass( 'hide' );
        }
        try {
            var query_path = path.split("/");
            if (query_path.length > 1) {
                    var folder_path = query_path.splice(0,query_path.length - 1).join("/");
                    var folder = this.queries[folder_path];
                    if (typeof folder.acl != "undefined" && _.indexOf(folder.acl, "WRITE") > -1) {
                        $( this.el ).find( '.add_folder' ).parent().removeClass( 'hide' );
                    }
            } else if (query_path.length == 1) {
                $( this.el ).find( '.add_folder' ).parent().removeClass( 'hide' );
            }
        } catch(e) {
            //console.log(e);
        }


        var $results = $(this.el).find('.workspace_results')
            .html('<h3><strong>' + query.name + '</strong></h3>');
        var $properties = $('<ul id="query_info" />').appendTo($results);

        // Iterate through properties and show a key=>value set in the information pane
        for (var property in query) {
            if (query.hasOwnProperty(property) && property != "name") {
                $properties.append($('<li />').html("<strong>" +
                        property + "</strong> : " + query[property]));
            }
        }

        this.selected_query = new SavedQuery({ file: path, name: name, type: query.type });

        return false;
    },

    view_folder: function( event ) {
        var $target = $( event.currentTarget ).children('div').children('a');
        var path = $target.attr('href').replace('#', '');
        var name = $target.text();
        var folder = this.queries[path];
        $( this.el ).find( '.workspace_toolbar' ).removeClass( 'hide' );
        $( this.el ).find( '.add_folder' ).parent().addClass( 'hide' );
        $( this.el ).find( '.for_queries' ).addClass( 'hide' );
        $( this.el ).find( '.for_folder' ).addClass( 'hide' );

        if (typeof folder.acl != "undefined" && _.indexOf(folder.acl, "WRITE") > -1) {
            $( this.el ).find( '.for_folder .delete' ).parent().removeClass( 'hide' );
            $( this.el ).find( '.add_folder' ).parent().removeClass( 'hide' );
        }
        if (typeof folder.acl != "undefined" && _.indexOf(folder.acl, "GRANT") > -1) {
            $( this.el ).find( '.for_folder .edit_permissions' ).parent().removeClass( 'hide' );
        }

        $( this.el ).find( '.workspace_results' )
            .html( '<h3><strong>' + name + '</strong></h3>' );

        this.selected_query = new SavedQuery({ file: path , name: name, type: folder.type });

    },

    prevent_default: function(event) {
        event.preventDefault();
        return false;
    },

    add_folder: function( event ) {
        $selected = $(this.el).find('.selected');
        var path ="";
        if (typeof $selected !== "undefined" && $selected) {
            if ($selected.hasClass('folder_row')) {
                path = $selected.children('a').attr('href');
                path = path.length > 1 ? path.substring(1,path.length) : path;
                path+= "/";

            } else if ($selected.hasClass('query') && !$selected.parent().hasClass('RepositoryObjects')) {
                var query = $selected.find('a');
                path = query.attr('href');
                var queryname = query.text();
                path = path.substring(1, path.length - queryname.length );
            }
        }

        (new AddFolderModal({
            path: path,
            success: this.clear_query
        })).render().open();

        return false;
    },

    click_canvas: function(event) {
        var $target = $( event.currentTarget );
        if ($target.hasClass('sidebar')) {
            $(this.el).find('.selected').removeClass('selected');
        }
        $( this.el ).find( '.add_folder' ).parent().removeClass( 'hide' );
        return false;
    },

    toggle_folder: function( event ) {
        var $target = $( event.currentTarget );
        var path = $target.children('.folder_row').find('a').attr('href');
        path = path.replace('#', '');
        this.unselect_current_selected( );
        $target.children('.folder_row').addClass( 'selected' );
        var $queries = $target.children( '.folder_content' );
        var isClosed = $target.children( '.folder_row' ).find('.sprite').hasClass( 'collapsed' );
        if( isClosed ) {
            $target.children( '.folder_row' ).find('.sprite').removeClass( 'collapsed' );
            $queries.removeClass( 'hide' );
            if (Settings.REPOSITORY_LAZY) {
                this.fetch_lazyload($target, path);
            }
        } else {
            $target.children( '.folder_row' ).find('.sprite').addClass( 'collapsed' );
            $queries.addClass( 'hide' );
            if (Settings.REPOSITORY_LAZY) {
                $target.find('.folder_content').remove();
            }
        }

        this.view_folder( event );

        return false;
    },

    fetch_lazyload: function(target, path) {
        var repositoryLazyLoad = new RepositoryLazyLoad({}, { dialog: this, folder: target, path: path });
        repositoryLazyLoad.fetch();
        Saiku.ui.block('Loading...');
    },

    template_repository_folder_lazyload: function(folder, repository) {
        folder.find('.folder_content').remove();
        folder.append(
            _.template($('#template-repository-folder-lazyload').html())({
                repoObjects: repository
            })
        );
    },

    populate_lazyload: function(folder, repository) {
        Saiku.ui.unblock();
        this.template_repository_folder_lazyload(folder, repository);
    },

    select_and_open_query: function(event) {
        var $target = $(event.currentTarget).find('a');
        var path = $target.attr('href').replace('#', '');
        var name = $target.text();
        this.selected_query = new SavedQuery({ file: path, name: path });
        this.open_query();
    },

    open_query: function(viewstate) {
        Saiku.ui.block("Opening query...");
        var item = this.queries[this.selected_query.get('file')];
        var params = _.extend({
                        file: this.selected_query.get('file'),
                        formatter: Settings.CELLSET_FORMATTER
                    }, Settings.PARAMS);

        var query = new Query(params,{ name: this.selected_query.get('name') });
        var state = null;
        if(viewstate && !viewstate.hasOwnProperty('currentTarget')) {
            state = viewstate;
        }

        if (item.fileType === 'saiku') {
            var tab = Saiku.tabs.add(new Workspace({ query: query, item: item, viewState: state, processURI: false }));
        }
        else {
            Saiku.session.trigger('openQuery:open_query', { query: query, item: item, viewState: state });
        }

        return false;
    },
    open_contents: function(viewstate) {
        var files = [];
        var itemF = this.queries[this.selected_query.get('file')];
        _.forEach( itemF.repoObjects, function( entry){
           if(entry.type === "FILE"){
               files.push(entry);
           }
        });

        var obj = {files: files, viewstate: viewstate};

        (new WarningModal({
            title: 'Open Multiple Queries', message: '<span class="i18n">You are about to open</span> ' + files.length + ' <span class="i18n">queries</span>',
            okay: this.run_open_contents, okayobj: obj
        })).render().open();

        return false;
    },
    run_open_contents: function(fileargs){
        _.forEach( fileargs.files, function( entry ) {
            Saiku.ui.block("Opening query...");

            var item = entry;
            var params = _.extend({
                file: item.path,
                formatter: Settings.CELLSET_FORMATTER
            }, Settings.PARAMS);

            var query = new Query(params,{ name: item.name });
            var state = null;
            if(fileargs.viewstate && !fileargs.viewstate.hasOwnProperty('currentTarget')) {
                state = viewstate;
            }
            var tab = Saiku.tabs.add(new Workspace({ query: query, item: item, viewState: state, processURI: false }));

        });
    },
    edit_query: function() {
        this.open_query('edit');
    },

    delete_repoObject: function(event) {
        (new DeleteRepositoryObject({
            query: this.selected_query,
            success: this.clear_query
        })).render().open();

        return false;
    },

    move_repoObject: function(event) {
        (new MoveRepositoryObject({
            query: this.selected_query,
            success: this.clear_query
        })).render().open();

        return false;
    },

    edit_folder: function( event ) {
        alert( 'todo: edit folder properties/permissions' );
        return false;
    },

    edit_permissions: function(event) {
        (new PermissionsModal({
            workspace: this.workspace,
            title: "<span class='i18n'>Permissions</span>",
            file: this.selected_query.get('file')
        })).open();
    },

    clear_query: function() {
        $(this.el).find('.workspace_toolbar').addClass('hide');
        $(this.el).find('.workspace_results').html('');
        this.fetch_queries();
    },

    adjust: function() {
        // Adjust the height of the separator
        $separator = $(this.el).find('.sidebar_separator');
        $separator.height($("body").height() - 87);
        $(this.el).find('.sidebar').css( { 'width' : 350,
                                            'height' : $("body").height() - 87 });
        $(this.el).find('.workspace_inner').css({ 'margin-left' : 355});
        $(this.el).find('.workspace').css({ 'margin-left' : -355});
        // Adjust the dimensions of the results window
        $(this.el).find('.workspace_results').css({
            width: $(document).width() - $(this.el).find('.sidebar').width() - 30,
            height: $(document).height() - $("#header").height() -
                $(this.el).find('.workspace_toolbar').height() -
                $(this.el).find('.workspace_fields').height() - 40
        });
        //$(this.el).find('.canvas_wrapper').show();
    },

    toggle_sidebar: function() {
        // Toggle sidebar
        $(this.el).find('.sidebar').toggleClass('hide');
        var new_margin = $(this.el).find('.sidebar').hasClass('hide') ?
                5 : 265;
        $(this.el).find('.workspace_inner').css({ 'margin-left': new_margin });
    },

    unselect_current_selected: function( ) {
        $( this.el ).find( '.selected' ).removeClass( 'selected' );
    }

});
