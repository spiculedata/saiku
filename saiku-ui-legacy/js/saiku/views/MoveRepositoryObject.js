/*
 *   Copyright 2014 OSBI Ltd
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
 * The delete query confirmation dialog
 */
var MoveRepositoryObject = Modal.extend({
    type: "save",
    closeText: "Move",

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
        { id: "test", text: "Move", method: "open_query" },
        { text: "Cancel", method: "close" }
    ],

    initialize: function(args) {
        // Append events
        var self = this;
        var name = "";
        this.movefolder = args.query;
        this.success = args.success;

        this.message =  "<br/><b><div class='query_name'><span class='i18n'>Please select a folder.....</span></div></b><br/><div class='RepositoryObjects i18n'>Loading...</div>" +
            "<br>" +
            '<div style="height:25px; line-height:25px;"><b><span class="i18n">Search:</span></b> &nbsp;' +
            ' <span class="search"><input type="text" class="search_file"></input><span class="cancel_search"></span></span></div>';

        _.extend(this.options, {
            title: "Move"
        });

        this.selected_folder = null;

        // Initialize repository
        this.repository = new Repository({}, { dialog: this });

        this.bind( 'open', function( ) {
            var height = ( $( "body" ).height() / 2 ) + ( $( "body" ).height() / 6 );
            if( height > 420 ) {
                height = 420;
            }
            $(this.el).find('.RepositoryObjects').height( height );
            $(this.el).dialog( 'option', 'position', 'center' );
            $(this.el).parents('.ui-dialog').css({ width: "550px" });
            $(this.el).find('.dialog_footer').find('a[href="#open_query"]').hide();

            self.repository.fetch( );
        } );


        // Maintain `this`
        _.bindAll( this, "populate", "toggle_folder", "select_name", "select_file", "select_folder", "open_query");


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
                if(entry.type === 'FOLDER') {
                    self.queries[ entry.path ] = entry;
                //}
                //if( entry.type === 'FOLDER' ) {
                    getQueries( entry.repoObjects );
                }
            } );
        }
        getQueries( repository );
    },

    select_root_folder: function( event ) {
        var isNameInputField = $( event.target ).attr( 'name' ) === 'name';
        if( !isNameInputField ) {
            this.unselect_current_selected_folder( );
        }
    },
    toggle_folder: function( event ) {
        var $target = $( event.currentTarget );
        this.unselect_current_selected_folder( );
        $target.children('.folder_row').addClass( 'selected' );
        var $queries = $target.children( '.folder_content' );
        var isClosed = $target.children( '.folder_row' ).find('.sprite').hasClass( 'collapsed' );
        if( isClosed ) {
            $target.children( '.folder_row' ).find('.sprite').removeClass( 'collapsed' );
            $queries.removeClass( 'hide' );
        } else {
            $target.children( '.folder_row' ).find('.sprite').addClass( 'collapsed' );
            $queries.addClass( 'hide' );
        }

        this.select_folder();
        this.select_name(event);
        return false;
    },

    select_name: function( event ) {
        var $currentTarget = $( event.currentTarget );
        this.unselect_current_selected_folder( );
        $currentTarget.parent( ).parent( ).has( '.folder' ).children('.folder_row').addClass( 'selected' );
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

        var that= this;
        var picture_entity = new MoveObject;
        picture_entity.save({source: this.movefolder.get("file"), target: file}, {success: function(){
            that.close();
            that.success();
        }});


        event.preventDefault();
        return false;
    }




});

var MoveObject = Backbone.Model.extend({
    url: function(){
    return "api/repository/resource/move";
}
});