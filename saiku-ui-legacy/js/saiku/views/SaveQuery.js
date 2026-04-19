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
var SaveQuery = Modal.extend({
    type: 'save',

    closeText: 'Save',

    events: {
        'click'                  : 'select_root_folder', /* select root folder */
        'click .dialog_footer a' : 'call',
        'submit form'            : 'save',
        'click .query'           : 'select_name',
        'click li.folder'        : 'toggle_folder',
        'keyup .search_file'     : 'search_file',
        'click .cancel_search'   : 'cancel_search'
    },

    buttons: [
        { text: 'Save', method: 'save' },
        { text: 'Cancel', method: 'close' }
    ],

    folder_name: null,
    file_name: null,

    initialize: function(args) {
        var self = this;
        var name = '';
        var fullPath = '';

        if (args.query.name) {
            var path;

            args.query.name = args.query.name.replace(/:/g, '/');
            path = args.query.name.split('/');
            fullPath = args.query.name;

            name = path[path.length -1];
            this.file_name = name;

            if (path.length > 1) {
                this.folder_name = path.splice(0, path.length - 1).join('/');
            }
        }

        this.query = args.query;

        // this.message = _.template(
        //     "<form id='save_query_form'>" +
        //     "<label for='name' class='i18n'>File:</label>&nbsp;" +
        //     "<input type='text' name='name' value='<%= name %>' />" +
        //     "<div class='RepositoryObjects'><span class='i18n'>Loading...</span></div>" +
        //     "<br />"+
        //     "</form>"+
        //     '<div class="box-search-file" style="height:25px; line-height:25px;"><b><span class="i18n">Search:</span></b> &nbsp;' +
        //     ' <span class="search"><input type="text" class="search_file"></input><span class="cancel_search"></span></span></div>')({ name: fullPath });

        this.message = _.template(
            '<form id="save_query_form">' +
                '<div class="form-inline box-search-file" style="height: 35px; padding-top: 10px; line-height: 25px;">' +
                    '<label class="i18n">Search:</label> &nbsp; ' +
                    '<input type="text" class="form-control search_file"></input><span class="cancel_search"></span>' +
                '</div>' +
                '<div class="RepositoryObjects"><span class="i18n">Loading...</span></div>' +
                '<div class="form-inline" style="padding-top: 4px;">' +
                    '<label for="name" class="i18n">File:</label>&nbsp;' +
                    '<input type="text" name="name" id="relative-file-path" class="form-control" value="<%= name %>">' +
                '</div>' +
                '<br>' +
            '</form>'
        )({ name: fullPath });

        _.extend(this.options, {
            title: 'Save query'
        });

        // Initialize repository
        this.repository = new Repository({}, { dialog: this, type: 'saiku' });

        this.bind('open', function() {
            var height = ($('body').height() / 2) + ($('body').height() / 6);

            if (height > 420) {
                height = 420;
            }

            var perc = (((($('body').height() - 600) / 2) * 100) / $('body').height());

            self.$el.find('.RepositoryObjects').height(height);
            self.$el.dialog('option', 'position', 'center');
            self.$el.parents('.ui-dialog').css({ width: '550px', top: perc + '%' });

            self.repository.fetch();

            if (Settings.REPOSITORY_LAZY) {
                self.$el.find('.box-search-file').hide();
            }
        });

        // Maintain `this`
        _.bindAll(this, 'copy_to_repository', 'close', 'toggle_folder',
            'select_name', 'populate', 'set_name', 'cancel_search');

        // fix event listening in IE < 9
        if (isIE && isIE < 9) {
            $(this.el).find('form').on('submit', this.save);
        }
    },

    populate: function(repository) {
        this.$el.find('.RepositoryObjects').html(
            _.template($('#template-repository-objects').html())({
                repoObjects: repository
            })
        );

        this.context_menu_disabled();
        this.select_last_location();
    },

    context_menu_disabled: function() {
        this.$el.find('.RepositoryObjects').find('.folder_row, .query').addClass('context-menu-disabled');
    },

    select_root_folder: function(event) {
        var isNameInputField = $(event.target).attr('name') === 'name';

        if (!isNameInputField) {
            this.unselect_current_selected_folder();
        }
    },

    toggle_folder: function(event) {
        var $target = $(event.currentTarget);
        var path = $target.children('.folder_row').find('a').attr('href');
        var folderName;

        path = path.replace('#', '');
        this.unselect_current_selected_folder();
        $target.children('.folder_row').addClass('selected');
        folderName = $target.find('a').attr('href').replace('#', '');

        this.set_name(folderName, null);

        var $queries = $target.children('.folder_content');
        var isClosed = $target.children('.folder_row').find('.sprite').hasClass('collapsed');

        if (isClosed) {
            $target.children('.folder_row').find('.sprite').removeClass('collapsed');
            $queries.removeClass('hide');

            if (Settings.REPOSITORY_LAZY) {
                this.fetch_lazyload($target, path);
            }
        }
        else {
            $target.children('.folder_row').find('.sprite').addClass('collapsed');
            $queries.addClass('hide');

            if (Settings.REPOSITORY_LAZY) {
                $target.find('.folder_content').remove();
            }
        }

        this.set_last_location(path);
        this.$el.find('input[name="name"]').focus();

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

    set_name: function(folder, file) {
        if (folder !== null) {
            var name;

            this.folder_name = folder;
            name = (this.folder_name !== null ? this.folder_name + '/' : '') + (this.file_name !== null ? this.file_name : '');
            this.$el.find('input[name="name"]').val(name);
        }

        if (file !== null) {
            this.$el.find('input[name="name"]').val(file);
        }
    },

    // XXX - duplicaten from OpenQuery
    search_file: function(event) {
        var filter = this.$el.find('.search_file').val().toLowerCase();
        var isEmpty = (typeof filter == 'undefined' || filter === '' || filter === null);

        if (isEmpty || event.which == 27 || event.which == 9) {
            this.cancel_search();
        }
        else {
            if (this.$el.find('.search_file').val()) {
                this.$el.find('.cancel_search').show();
            }
            else {
                this.$el.find('.cancel_search').hide();
            }

            this.$el.find('li.query').removeClass('hide');
            this.$el.find('li.query a').filter(function (index) {
                return $(this).text().toLowerCase().indexOf(filter) == -1;
            }).parent().addClass('hide');
            this.$el.find('li.folder').addClass('hide');
            this.$el.find('li.query').not('.hide').parents('li.folder').removeClass('hide');
            // this.$el.find('li.folder .folder_content').not(':has(.query:visible)').parent().addClass('hide');

            // not(':contains("' + filter + '")').parent().hide();
            this.$el.find('li.folder .folder_row').find('.sprite').removeClass('collapsed');
            this.$el.find('li.folder .folder_content').removeClass('hide');
        }

        return false;
    },

    cancel_search: function(event) {
        this.$el.find('input.search_file').val('');
        this.$el.find('.cancel_search').hide();
        this.$el.find('li.query, li.folder').removeClass('hide');
        this.$el.find('.folder_row').find('.sprite').addClass('collapsed');
        this.$el.find('li.folder .folder_content').addClass('hide');
        this.$el.find('.search_file').val('').focus();
        this.$el.find('.cancel_search').hide();
    },

    select_name: function(event) {
        var $currentTarget = $(event.currentTarget);
        var name;
        var path;

        this.unselect_current_selected_folder();
        // $currentTarget.parent().parent().has('.folder').children('.folder_row').addClass('selected');
        $currentTarget.addClass('selected');
        name = $currentTarget.find('a').attr('href').replace('#', '');
        this.set_name(null, name);
        path = $currentTarget.parent().parent().has('.folder').children('.folder_row').find('a').attr('href');
        path = path.replace('#', '');
        this.set_last_location(path);
        this.$el.find('input[name="name"]').focus();

        return false;
    },

    unselect_current_selected_folder: function() {
        this.$el.find('.selected').removeClass('selected');
    },

    save: function(event) {
        var self = this;
        // Save the name for future reference
        var foldername = ''; /* XXX == root, should it be something different than ''? */
        /*
        var $folder = this.$el.find('.folder_row.selected a').first();
        if ($folder.length) {
            foldername = $folder.attr('href').replace('#', '');
            foldername = (foldername != null && foldername.length > 0 ? foldername + '/' : '');
        }
        */

        var name = this.$el.find('input[name="name"]').val();

        if (this.folder_name !== null && this.folder_name !== undefined && this.folder_name.length > 0) {
            if (name !== null && name.length > 0) {
                this.repository.fetch({
                    success: function(collection, response) {
                        var paths = [];

                        paths.push.apply(paths, self.get_files(response));

                        if (paths.indexOf(name) > -1 && self.query.get('name') !== name) {
                           new OverwriteModal({
                                name: name,
                                foldername: foldername,
                                parent: self
                            }).render().open();
                        }
                        else {
                            self.query.set({ name: name, folder: foldername });
                            self.query.trigger('query:save');
                            self.copy_to_repository();
                            event.stopPropagation();
                            event.preventDefault();

                            return false;
                        }
                    }
                });
            }
            else {
                alert('You need to enter a name!');
            }
        }
        else {
            alert('You need select a folder!');
        }

        return false;
    },

    save_remote: function(name, foldername, parent) {
        parent.query.set({ name: name, folder: foldername });
        parent.query.trigger('query:save');
        parent.copy_to_repository();
        event.preventDefault();

        return false;
    },

    get_files: function(response){
        var self = this;
        var paths = [];

        _.each(response, function(entry) {
            if (entry.type === 'FOLDER') {
                paths.push.apply(paths, self.get_files(entry.repoObjects));
            }
            else {
                paths.push(entry.path);
            }
        });

        return paths;
    },

    copy_to_repository: function() {
        var self = this;
        var folder = this.query.get('folder');
        var file = this.query.get('name');

        file = file.length > 6 && file.indexOf('.saiku') == file.length - 6 ? file : file + '.saiku';
        file = folder + file;

        var error = function(data, textStatus, jqXHR) {
            if (textStatus && textStatus.status == 403 && textStatus.responseText) {
                alert(textStatus.responseText);
            }
            else {
                self.close();
            }

            return true;
        };

        // Rename tab
        this.query.workspace.tab.$el.find('.saikutab').text(file.replace(/^.*[\\\/]/, '').split('.')[0]);

        (new SavedQuery({
            name: this.query.get('name'),
            file: file,
            content: JSON.stringify(this.query.model)
        })).save({}, { success:  this.close, error: error, dataType: 'text' });
    },

    set_last_location: function(path) {
        if (typeof localStorage !== 'undefined' && localStorage && !Settings.REPOSITORY_LAZY) {
            if (!Settings.LOCALSTORAGE_EXPIRATION || Settings.LOCALSTORAGE_EXPIRATION === 0) {
                localStorage.clear();
            }
            else {
                localStorage.setItem('last-folder', path);
            }
        }
    },

    select_last_location: function() {
        if (localStorage.getItem('last-folder') && !Settings.REPOSITORY_LAZY) {
            var p = this.$el.find('a[href="\\#' + localStorage.getItem('last-folder') + '"]');
            var path = p.parent().parent().has('.folder').children('.folder_row').find('.sprite').removeClass('collapsed');
            var parents = path.parentsUntil($('div.RepositoryObjects'));

            parents.each(function() {
                if ($(this).hasClass('folder')) {
                    $(this).children('.folder_row').find('.sprite').removeClass('collapsed');
                    $(this).children('.folder_content').removeClass('hide');
                }
            });
        }
    }
});
