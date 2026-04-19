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
 * The save query dialog
 */
var OpenDialog = Modal.extend({
    type: "save",
    closeText: "Open",

    events: {
        'click': 'select_root_folder', /* select root folder */
        'click .dialog_footer a' : 'call',
        'click .query': 'select_name',
        'dblclick .query': 'open_query',
        'click li.folder': 'toggle_folder',
        'keyup .search_file' : 'search_file',
        'click .cancel_search' : 'cancel_search',
        'click .export_btn' : 'export_zip',
        'change .file' : 'select_file'
    },

    buttons: [
        { id: "test", text: "Open", method: "open_query" },
        { text: "Cancel", method: "close" }
    ],

    initialize: function(args) {
        // Append events
        var self = this;
        var name = "";
        // this.message =  "<br/><b><div class='query_name'><span class='i18n'>Please select a file.....</span></div></b><br/><div class='RepositoryObjects'>Loading....</div>" +
        //                 "<br>" +
        //                 '<div class="box-search-file" style="height:25px; line-height:25px;"><b><span class="i18n">Search:</span></b> &nbsp;' +
        //                 ' <span class="search"><input type="text" class="search_file"></input><span class="cancel_search"></span></span></div>';

        this.message =  '<div class="box-search-file form-inline" style="padding-top:10px; height:35px; line-height:25px;"><label class="i18n">Search:</label> &nbsp;' +
                        ' <input type="text" class="form-control search_file"></input><span class="cancel_search"></span></div>' +
                        "<div class='RepositoryObjects i18n'>Loading...</div>" +
                        "<br>" +
                        "<b><div class='query_name'><span class='i18n'>Please select a file.....</span></div></b><br/>";

        if (Settings.ALLOW_IMPORT_EXPORT) {
            this.message += "<span class='export_zip'> </span> <b><span class='i18n'>Import or Export Files for Folder</span>: </b> <span class='i18n zip_folder'>< Select Folder... ></span>" +
                            " &nbsp; <input type='submit' value='Export' class='export_btn' disabled /><br/><br />" +
                            "<br /><form id='importForm' target='_blank' method='POST' enctype='multipart/form-data'>" +
                            "<input type='hidden' name='directory' class='directory'/>" +
                            "<input type='file' name='file' class='file'/>" +
                            "<input type='submit' value='Import' class='import_btn' disabled />" +
                            "</form>";
        }
        _.extend(this.options, {
            title: "Open"
        });

        this.selected_folder = null;

        // Initialize repository
        this.repository = new Repository({}, { dialog: this, type: 'saiku' });

        this.bind( 'open', function( ) {
            var height = ( $( "body" ).height() / 2 ) + ( $( "body" ).height() / 6 );
            if( height > 420 ) {
                height = 420;
            }
            var perc = (((($( "body" ).height() - 600) / 2) * 100) / $( "body" ).height());
            $(this.el).find('.RepositoryObjects').height( height );
            $(this.el).dialog( 'option', 'position', 'center' );
            $(this.el).parents('.ui-dialog').css({ width: "550px", top: perc+'%' });
            $(this.el).find('.dialog_footer').find('a[href="#open_query"]').hide();

            self.repository.fetch( );

            if (Settings.REPOSITORY_LAZY) {
                this.$el.find('.box-search-file').hide();
            }
        } );


        // Maintain `this`
        _.bindAll( this, "close", "toggle_folder", "select_name", "populate" , "cancel_search", "export_zip", "select_folder", "select_file", "select_last_location");


    },

    populate: function( repository ) {
        var self = this;
        $( this.el ).find( '.RepositoryObjects' ).html(
            _.template( $( '#template-repository-objects' ).html( ) )( {
                repoObjects: repository
            } )
        );

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
        this.context_menu_disabled();
        this.select_last_location();
    },

    context_menu_disabled: function() {
        this.$el.find('.RepositoryObjects').find('.folder_row, .query').addClass('context-menu-disabled');
    },

    select_root_folder: function( event ) {
        var isNameInputField = $( event.target ).attr( 'name' ) === 'name';
        if( !isNameInputField ) {
            this.unselect_current_selected_folder( );
        }
    },

    toggle_folder: function( event ) {
        var $target = $( event.currentTarget );
        var path = $target.children('.folder_row').find('a').attr('href');
        path = path.replace('#', '');
        this.unselect_current_selected_folder( );
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

        this.select_folder();
        this.set_last_location(path);
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

    select_name: function( event ) {
        var $currentTarget = $( event.currentTarget );
        this.unselect_current_selected_folder( );
        //$currentTarget.parent( ).parent( ).has( '.folder' ).children('.folder_row').addClass( 'selected' );
        var path = $currentTarget.parent( ).parent( ).has( '.folder' ).children('.folder_row').find( 'a' ).attr('href');
        path = path.replace('#' , '');
        this.set_last_location(path);
        $currentTarget.addClass('selected');
        var name = $currentTarget.find( 'a' ).attr('href');
        name = name.replace('#','');
        $(this.el).find('.query_name').html( name );
        $(this.el).find('.dialog_footer').find('a[href="#open_query"]').show();
        this.select_folder();
        return false;
    },

    unselect_current_selected_folder: function( ) {
        $( this.el ).find( '.selected' ).removeClass( 'selected' );
    },

    // XXX - duplicaten from OpenQuery
    search_file: function(event) {
        var filter = $(this.el).find('.search_file').val().toLowerCase();
        var isEmpty = (typeof filter == "undefined" || filter === "" || filter === null);
        if (isEmpty || event.which == 27 || event.which == 9) {
            this.cancel_search();
        } else {
            if ($(this.el).find('.search_file').val()) {
                $(this.el).find('.cancel_search').show();
            } else {
                $(this.el).find('.cancel_search').hide();
            }
            $(this.el).find('li.query').removeClass('hide');
            $(this.el).find('li.query a').filter(function (index) {
                return $(this).text().toLowerCase().indexOf(filter) == -1;
            }).parent().addClass('hide');
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
        $(this.el).find('input.search_file').val('');
        $(this.el).find('.cancel_search').hide();
        $(this.el).find('li.query, li.folder').removeClass('hide');
        $(this.el).find( '.folder_row' ).find('.sprite').addClass( 'collapsed' );
        $(this.el).find( 'li.folder .folder_content' ).addClass('hide');
        $(this.el).find('.search_file').val('').focus();
        $(this.el).find('.cancel_search').hide();

    },

    export_zip: function(event) {
        var file = this.selected_folder;
        if (typeof file != "undefined" && file !== "") {
            var url = Settings.REST_URL + (new RepositoryZipExport({ directory : file })).url();
            window.open(url + "?directory=" + file + "&type=saiku");
        }
    },

    select_folder: function() {
        var foldersSelected = $( this.el ).find( '.selected' );
        var file = foldersSelected.length > 0 ? foldersSelected.children('a').attr('href').replace('#','') : null;
        if (typeof file != "undefined" && file !== null && file !== "") {
            var form = $('#importForm');
            form.find('.directory').val(file);
            var url = Settings.REST_URL + (new RepositoryZipExport()).url() + "upload";
            form.attr('action', url);
            $(this.el).find('.zip_folder').text(file);
            this.selected_folder = file;
            $(this.el).find('.export_btn, .import_btn').removeAttr('disabled');
            this.select_file();
        } else {
            $(this.el).find('.import_btn, .export_btn').attr('disabled', 'true');
        }

    },

    select_file: function() {
            var form = $('#importForm');
            var filename = form.find('.file').val();
            if (typeof filename != "undefined" && filename !== "" && filename !== null && this.selected_folder !== null) {
                $(this.el).find('.import_btn').removeAttr('disabled');
            } else {
                $(this.el).find('.import_btn').attr('disabled', 'true');
            }
    },

    open_query: function(event) {
        // Save the name for future reference
        var $currentTarget = $( event.currentTarget );
        var file = $(this.el).find('.query_name').html();
        if ($currentTarget.hasClass('query')) {
            file = $currentTarget.find( 'a' ).attr('href').replace('#','');
        }

        var selected_query = new SavedQuery({ file: file });
        this.close();
        Saiku.ui.block("Opening query...");
        var item = this.queries[file];
                var params = _.extend({
                        file: file,
                        formatter: Settings.CELLSET_FORMATTER
                    }, Settings.PARAMS);

        var query = new Query(params,{ name: file  });
        var tab = Saiku.tabs.add(new Workspace({ query: query, item: item, processURI: false }));

        event.preventDefault();
        return false;
    },

    set_last_location: function(path){
        if (typeof localStorage !== "undefined" && localStorage && !Settings.REPOSITORY_LAZY) {
            if (!Settings.LOCALSTORAGE_EXPIRATION || Settings.LOCALSTORAGE_EXPIRATION === 0) {
                localStorage.clear();
            }
            else {
                localStorage.setItem('last-folder', path);
            }

        }
    },

    select_last_location: function(){
        if(localStorage.getItem('last-folder') && !Settings.REPOSITORY_LAZY){
            var p = $(this.el).find('a[href="\\#'+localStorage.getItem('last-folder')+'"]')

                var path = p.parent().parent().has('.folder').children('.folder_row').find('.sprite').removeClass('collapsed');

                var parents = path.parentsUntil($("div.RepositoryObjects"));

                parents.each(function () {
                    if ($(this).hasClass('folder')) {
                        $(this).children('.folder_row').find('.sprite').removeClass('collapsed');
                        $(this).children('.folder_content').removeClass('hide');

                    }

                });

            }



    }
});
