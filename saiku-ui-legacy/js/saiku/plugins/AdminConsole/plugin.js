Saiku.AdminConsole = {
    show_admin: function() {
        var tab = _.find(Saiku.tabs._tabs, function(tab) {
            return tab.content instanceof AdminConsole;
        });

        if (tab) {
            tab.select();
        }
        else {
            Saiku.tabs.add(new AdminConsole());
        }

        return false;
    }
};
var AdminConsole = Backbone.View.extend({
    events: {
        'click .back_query': 'back_query',
        'click .user': 'view_user',
        'click .save_user': 'save_user',
        'click .save_password': 'save_password',
        'click .add_role': 'add_role',
        'click .remove_role': 'remove_role',
        'click .create_user': 'create_user',
        'click .new_remove_role': 'new_remove_role',
        'click .new_add_role': 'new_add_role',
        'click .save_new_user': 'save_new_user',
        'click .create_datasource': 'create_datasource',
        'click .submitdatasource': 'uploadFile',
        'click .save_datasource': 'save_datasource',
        'click .datasource': 'view_datasource',
        'click .schema': 'view_schema',
        'click .accordion-toggle': 'accordion',
        'click .remove_datasource' : 'remove_datasource',
        'click .remove_schema' : 'remove_schema',
        'click .remove_user' : 'remove_user',
        'click .refresh_button':'refresh_datasource',
        'click .advancedurl' :'advanced_url',
        'click .getdatasources' :'get_data_sources',
        'change .drivertype' : 'change_driver',
        'click .create_schema': 'create_schema',
        'click .backup_restore' : 'backup_restore',
        'click .submitrestore' : 'restoreFile',
        'click .submitrestorelegacy' : 'restoreLegacy',
        //'click .download_schema' : 'download_schema',
        'click .license_info' : 'show_license_info',
        'click .license_users_list' : 'show_license_user_list',
        'click .add_license_user' : 'add_license_user',
        'click .remove_license_user' : 'remove_license_user',
        'keyup input[name="jdbcurl"]' : 'trigger_set_jdbcdriver'
    },
    initialize: function (args) {
        _.bindAll(this, "fetch_users", "fetch_schemas", "fetch_propkeys", "fetch_datasources", "clear_users", "clear_datasources", "new_add_role", "new_remove_role", "save_new_user", "advanced_url", "view_datasource");
        // Initialize repository
        this.users = new Users({}, { dialog: this });
        this.schemas = new Schemas({}, { dialog: this });
        this.datasources = new Connections({}, { dialog: this });
        this.propertieskeys = new PropertiesKeys({}, {dialog:this});
        var that = this,
            license = new License();

        license.fetch_license('api/license/', function(opt) {
            if (opt.status === 'success') {
                that.licenseInfo = opt.data.toJSON();
                that.licenseUsers = new LicenseUsersCollection(null, {});
                that.licenseUsers.fetch();
            }
            else {
                $(that.el).find('.license_container').hide();
            }
        });
    },
    show_license_info: function(event) {
        event.preventDefault();
        var html = this.licenseInfoTemplate;

        yourEpoch = parseFloat(this.licenseInfo.expiration);
        var yourDate = new Date(yourEpoch);
        $(this.el).find('.user_info').html(html);
        $(this.el).find('.license_type > li:nth-child(1)').append(this.licenseInfo.licenseType);
        $(this.el).find('.license_type > li:nth-child(2)').append(yourDate.toLocaleDateString());
        $(this.el).find('.license_type > li:nth-child(3)').append(this.licenseInfo.name);
        $(this.el).find('.license_type > li:nth-child(4)').append(this.licenseInfo.hostname);
        $(this.el).find('.license_type > li:nth-child(5)').append(this.licenseInfo.userLimit);
    },

    show_license_user_list: function(event) {
        event ? event.preventDefault() : '';
        var html = this.licenseAddUserTemplate;
        $(this.el).find('.user_info').html(html);
        var listUsers = this.licenseUsers.toJSON();
        if (listUsers && !(_.isEmpty(listUsers))) {
            var htmlListUsers = this.list_users_license_template(listUsers);
            $(this.el).find('.license_listusers').html(htmlListUsers);
        }
    },

    add_license_user: function(event) {
        event.preventDefault();
        var self = this;
        var user = new LicenseUserModel();
        var name = $(this.el).find("input[name='username']").val();

        if (name !== '') {
            this.licenseUsers.add({
                name: name
            });


            user.save({}, {
                data: JSON.stringify(self.licenseUsers.toJSON()),
                contentType: "application/json",
                success: function(data) {
                    $(self.el).find("input[name='username']").val('');
                    self.licenseUsers.fetch();
                    self.show_license_user_list();
                },
                error: function(data) {
                    $(self.el).find("input[name='username']").val('');
                    self.licenseUsers.fetch();
                    self.show_license_user_list();
                }
            });
        }
        else {
            alert('The username field can not be empty!');
            $(this.el).find("input[name='username']").focus();
        }
    },

    remove_license_user: function(event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var self = this;
        var idUser = $currentTarget.parent().attr('id').split('-')[1];
        var user = this.licenseUsers.get(idUser);
        /*user.destroy({
            wait: true,
            success: function(data) {
                self.licenseUsers.fetch();
                self.show_license_user_list();
            },
            error: function(data) {
                self.licenseUsers.fetch();
                self.show_license_user_list();
            }
        });*/
        this.licenseUsers.remove(user);

        var s =this.licenseUsers.first();

        if(s != null || s!= undefined){
            s.save({}, {
                data: JSON.stringify(self.licenseUsers.toJSON()),
                contentType: "application/json",
                success: function(data) {
                    $(self.el).find("input[name='username']").val('');
                    self.licenseUsers.fetch();
                    self.show_license_user_list();
                },
                error: function(data) {
                    $(self.el).find("input[name='username']").val('');
                    self.licenseUsers.fetch();
                    self.show_license_user_list();
                }
            });
        }
    },

    trigger_set_jdbcdriver: function(event) {
        event.preventDefault();

        var $currentTarget = $(event.currentTarget);
        var regexp = /jdbc:mysql:|jdbc:postgresql:|jdbc:oracle:|jdbc:drill:|jdbc:jtds:sqlserver:/i;
        var dbUrl = this.$el.find('input[name="jdbcurl"]').val();
        var dbDriver = this.$el.find('input[name="driver"]').val();
        var jdbcDrivers = {
            'mysql'      : 'com.mysql.jdbc.Driver',
            'postgresql' : 'org.postgresql.Driver',
            'oracle'     : 'oracle.jdbc.OracleDriver',
            'drill'      : 'org.apache.drill.jdbc.Driver',
            'mssql'      : 'net.sourceforge.jtds.jdbc.Driver'
        };
        var driver;

        if (_.isEmpty(dbDriver)) {
            if (!!dbUrl.match(regexp)) {
                driver = dbUrl.match(regexp)[0].split(':')[1];
                this.$el.find('input[name="driver"]').val(jdbcDrivers[driver]);
            }
        }
    },

    list_users_license_template: function(obj) {
        return _.template(
            '<% _.each(obj, function(entry) { %>' +
                '<li id="user-<%= entry.id %>"><%= entry.name %> <a href="#" class="remove_license_user">x</a></li>' +
            '<% }); %>'
        )(obj);
    },

    back_query: function() {
        Saiku.tabs.add(new Workspace());
        return false;
    },
    change_driver: function(){
        var div = $(this.el).find(".simpleConnection");
        var type = $(this.el).find(".drivertype").val();
        Saiku.events.trigger('admin:changedriver', {
            div: div,
            type: type
        });
    },
    fetch_users: function () {
        self = this;
        this.users.fetch({
            success: function () {
            }
        });
    },
    fetch_schemas: function () {
        self = this;
        this.schemas.fetch({
            success: function () {
            }
        });
    },
    fetch_datasources: function () {
        self = this;
        this.datasources.fetch({
            success: function () {
            }
        });
    },
    fetch_propkeys: function(){
        this.propertieskeys.fetch({
            success: function(){

            }
        });
    },

    template: function () {
        return _.template(" <div class='workspace' style='margin-left: -305px'>" +
            "<div class='workspace_inner' style='margin-left: 305px'>" +
            "<div class='workspace_results'>" +
            "<div class='user_info'></div>" +
            "</div>" +
            "</div>" +
            "</div>" +
            "" +
            "<div class='sidebar queries' style='width: 300px'>" +

            "<div class='sidebar_inner'>" +
            "<a class='back_query' href='#back_query' style='display:none'></a>" +
            "    <ul id='queries' class='RepositoryObjects'>" +
            "<% if(Settings.SHOW_USER_MANAGEMENT) { %>"+
            "<li><strong>User Management</strong>" +
            "<ul class='inner_users'><li class='create_user'>Add User</li></ul></li>" +
            "<% } %>"+
            "<li><strong>Data Source Management</strong></li>" +
            "<ul class='dslist'><strong>Data Sources</strong>"+
            "<ul class='inner_datasource'><li class='create_datasource'>Add Data Source</li></ul></ul>" +
            "<ul class='dslist'><strong>Schema</strong>"+
            "<ul class='inner_schema'><li class='create_schema'>Add Schema</li></ul></ul>" +
            "<li class='license_container'><strong>License</strong>" +
            "<ul><li class='license_info'>Information</li>" +
            "<li class='license_users_list'>Users List</li></ul></li>"+
            "</ul>" +
            "</ul>" +
            "</div>" +
            "</div>" +

            "<div class='sidebar_separator'></div>" +
            "<div class='sidebar_separator' style='height: 676px;'></div>"+
            "    <div class='clear'></div>")();
    },

    create_schema: function(event){
        event.preventDefault();
        var conn = new Connection();
        var s = this.schemas;

        var schema = new Schema();
        schema.id = "";
        var html = this.schemauploadtemplate({schema:schema});

        $(this.el).find('.user_info').html(html);
        Saiku.events.trigger('admin:viewdatasource', {
            admin: this
        });
    },

    backup_restore: function(event){
        event.preventDefault();
        var html = this.backup_restore_template();
        $(this.el).find('.user_info').html(html);
    },

    caption: function() {
        return '<span class="i18n">Admin Console</span>';
    },

    render: function () {
        // Load template
        $(this.el).html(this.template());
        // Adjust tab when selected
        this.tab.bind('tab:select', this.fetch_users);
        this.tab.bind('tab:select', this.fetch_datasources);
        this.tab.bind('tab:select', this.fetch_propkeys);
        this.tab.bind('tab:select', this.fetch_schemas);
        this.tab.bind('tab:select', this.adjust);
        $(window).resize(this.adjust);

        var self = this;
        var menuitems = {
            "open": {name: "Open", i18n: true },
            "edit": {name: "Edit", i18n: true },
//                    "rename": {name: "Rename", i18n: true },
            "delete": {name: "Delete", i18n: true },
            "sep1": "---------",
            "new": {name: "New Folder", i18n: true}
        };
        $.each(menuitems, function (key, item) {
            recursive_menu_translate(item, Saiku.i18n.po_file);
        });

        $.contextMenu('destroy', 'li.query, div.folder_row');
        $.contextMenu({
            selector: 'li.query, div.folder_row',
            events: {
                show: function (opt) {
                    $(self.el).find('.selected').removeClass('selected');
                    $(this).addClass('selected');
                    var path = $(this).find('a').attr('href').replace('#', '');
                    var item = self.queries[path];

                    if (typeof item.acl != "undefined" && _.indexOf(item.acl, "WRITE") < 0) {
                        opt.commands['delete'].disabled = true;
                        opt.items['delete'].disabled = true;
                        opt.commands['edit'].disabled = true;
                        opt.items['edit'].disabled = true;
                    } else {
                        opt.commands['delete'].disabled = false;
                        opt.items['delete'].disabled = false;
                        opt.commands['edit'].disabled = false;
                        opt.items['edit'].disabled = false;
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

            callback: function (key, options) {
                var path = $(this).find('a').attr('href').replace('#', '');
                var item = self.queries[path];
                self.selected_query = new SavedQuery({ file: path, name: item.name, type: item.type });
                if (key == "open" && $(this).hasClass('query')) {
                    self.open_query();
                }
                if (key == "edit" && $(this).hasClass('query')) {
                    self.edit_query();
                } else if (key == "new") {
                    self.add_folder();
                } else if (key == "delete") {
                    self.delete_repoObject();
                }


            },
            items: menuitems
        });

        if (Settings.PLUGIN) {
            $(this.el).find('.back_query').css('display', 'block');
        }

        Saiku.i18n.translate();
        return this;
    },
    populate: function (repository) {
        var self = this;
        self.clear_users();
        self.template_user_objects(repository);
        self.fetchedusers = {};
        function getQueries(entries) {
            _.forEach(entries, function (entry) {
                self.fetchedusers[ entry.username ] = entry;

            });
        }

        getQueries(repository);
    },
    populate2: function (repository) {
        this.clear_datasources();
        this.template_datasource_objects(repository);


    },
    populateschema: function (repository) {
        this.clear_schema();

        this.template_schema_objects(repository);


    },
    template_user_objects: function (repository) {
        var html = this.maintemplate({repoObjects: repository});
        $(this.el).find('.inner_users').append(html);

    },
    template_datasource_objects: function (repository) {
        var html = this.connectiontemplate({repoObjects: repository});
        $(this.el).find('.inner_datasource').append(html);
    },
    template_schema_objects: function (repository) {
        var html = this.schematemplate({repoObjects: repository});
        $(this.el).find('.inner_schema').append(html);
    },
    backup_restore_template: _.template("<div><h1>Backup</h1><p><a href='<%= Settings.REST_URL + AdminUrl %>/backup' class='form_button'>Backup Now!</a></p>" +
        "<hr>" +
        "<h1>Restore</h1>" +
        "<form><input name='restore' type='file' class='restore_button'/><div class='clear'></div><br/>" +
        "<input type='submit' class='form_button upload_button submitrestore' value='Restore Repository'><input type='submit' class='form_button upload_button submitrestorelegacy' value='Restore Legacy Reports'></form>" +
"<br/><div id='uploadstatus'>"),
    //itemTemplate : _.template( "<% console.log('Hello2 from template' +Object.keys(entry)); %>" +"Helo<!--<li
    // class='query'><span class='icon'></span><a href=''>hello</a></li>-->"),
    maintemplate: _.template("<% _.each( repoObjects, function( entry ) { %>" +
        "<li class='user'><span class='icon'></span><a href='<%= entry.id%>'><%= entry.username %></a></li>" +
        "<% } ); %>"),
    connectiontemplate: _.template("<% _.each( repoObjects, function( entry ) { %>" +
        "<li class='datasource'><span class='icon'></span><a href='<%= entry.id%>'><%= entry.connectionname %></a></li>" +
        "<% } ); %>"),
    schematemplate: _.template("<% _.each( repoObjects, function( entry ) { %>" +
        "<li class='schema'><span class='icon'></span><a href='<%= entry.name%>'><%= entry.name %></a></li>" +
        "<% } ); %>"),

    usertemplate: _.template(" <form><div id='accordion'><h3 class='accordion-toggle' >User Details</h3>" +
        "<div class='accordion-content default'>"+
        "<label for='username'>Username:</label> <input class='form-control'  onfocus=\"this.value=''; this.onfocus=null;\" type='text' name='username' value='<% if(user.username) { %><%= user.username %><%} else{ %>Enter Username<%}%>'><br/>" +
        "<label for='email'>Email address:</label> <input class='form-control' type='text' onfocus=\"this.value=''; this.onfocus=null;\" name='email' value='<% if(user.email) { %><%= user.email %><%} else{ %>Enter Email Address<%}%>'><br/>" +
        "<div class='clear'></div>" +
        "</div>" +

        "<h3 class='accordion-toggle'>Password</h3>" +
        "<div class='accordion-content'>"+
        //"Current password:<input type='password' value='' name='currpassword'><br/>" +
        "<label for='newpassword'>Change password:</label><input class='form-control' type='password' value='' name='newpassword'><br/>" +
        "<label for='newpassword2'>Repeat Password:</label><input class='form-control' type='password' value='' name='newpassword2'><br/>" +
        "<a href='<%= user.id%>' class='save_password user_button btn btn-default form_button'>Change Password</a><div class='clear'></div>" +
        "</div>" +

        "<h3 class='accordion-toggle'>Roles</h3>" +
        "<div class='accordion-content'>"+
        "<label for='roleselect'>Existing Roles: </label><select name='roleselect' class='form-control role_select'" +
            " multiple>" +
        " <% _.each(user.roles, function(role){%><option><%= role %></option><%});%></select><br/><br/>" +
        "<a href='<%= user.id%>' class='remove_role form_button btn btn-default  user_button'>Remove selected role</a><br/> " +
        "<a href='#' class='new_remove_role btn btn-default  form_button user_button hide'>Remove selected role</a><br/><br/> <br/> " +
        "<label for='role'>Add new role: </label><input class='form-control' type='text' name='role'><br/>" +
        "<a href='<%= user.id%>' class=' form_button btn btn-default  user_button add_role'>Add role</a>" +
        "<a href='#' class='new_add_role form_button btn btn-default  user_button hide'>Add role</a><br/></div>" +
        "<br/><br/><a href='<%= user.id%>' class='remove_user btn btn-danger form_button user_button hide'>Remove User</a> " +
        "<a href='<%= user.id%>' class='save_user btn btn-default  user_button form_button'>Save Changes</a>" +
        "<a href='#' class='save_new_user form_button btn btn-default  user_button hide'>Save User</a><div class='clear'>" +
        "</div></div></form>"),
    datasourcetemplate: _.template("<form><h3>Create Data Source</h3>"+
        "<div class='simpleConnection'><label for='connname'>Name:</label><input type='text' class='form-control' name='connname' value='<%= conn.connectionname %>'/><br />" +
        "<label for='drivertype'>Connection Type:</label><select name='drivertype' class='form-control drivertype'><option value='MONDRIAN'>Mondrian</option><option value='XMLA'>XMLA</option></select><br/>" +
        "<% if(!Settings.EXT_DATASOURCE_PROPERTIES) { %>"+
        "<label for='jdbcurl'>URL:</label><input name='jdbcurl' class='form-control' value='<%= conn.jdbcurl %>' type='text'/><br class='horridbr'/>" +
        "<% } else {    %>"+
        "<input name='jdbcurl' type='hidden'/>" +
        "<% } %>"+
        "<label for='schemapath'>Schema:</label><select class='form-control schemaselect' name='schemapath'>" +
        "<% _.each(schemas, function(path){%>" +
        "<option <% if (conn.schema !== null && (conn.schema === 'mondrian://' + path.attributes.path || conn.schema === path.attributes.path)) { print('selected'); } %> ><%= path.attributes.path %></option>" +
        "<%});%></select><br/>" +
        "<% if(!Settings.EXT_DATASOURCE_PROPERTIES) { %>"+
        "<label for='driver'>JDBC Driver: </label><input name='driver' class='form-control' value='<%= conn.driver %>' type='text'/><br class='horridbr'/>" +
        "<label for='connusername'>Username: </label><input name='connusername' class='form-control' type='text' value='<%= conn.username %>'/><br/>" +
        "<label for='connpassword'>Password:</label><input name='connpassword' class='form-control' type='password' value='<%= conn.password %>'/><br/>" +
        "<% } else {    %>"+
        "<input name='driver' type='hidden'/>" +
        "<input name='connusername' type='hidden'/>" +
        "<input name='connpassword' type='hidden'/>" +
        "<% } %>"+
        "<label for='securityselect'>Security:</label><select class='form-control securityselect' id='secselect' name='securityselect'>" +
        "<option value='NONE'>None</option><option value='ONE2ONE'>One To One Mapping</option><option value='PASSTHROUGH'>Passthrough (for XMLA)</option></select><br/>" +
        "<% if(Settings.EXT_DATASOURCE_PROPERTIES) { %>"+
        "<label for='extpropselect'>External Properties Key:</label>" +
        "<select name='extpropselect' class='form-control extpropselect'><option></option>" +
        "<% _.each(properties, function(path){%>" +
        "<option><%= path %></option>"+
        "<%});%>"+
        "</select><% } %><br/></div>" +
        "<div class='advconnection' style='display:none;'><textarea name='adv_text' class='form-control' rows='10' cols='75'><%= conn.advanced %></textarea></div>" +
        "<br/><br/><a href='' name='advancedurl' class='advancedurl btn btn-default'>Advanced</a>" +
        "<% if(Settings.DATA_SOURCES_LOOKUP) { %><a href='' name='getdatasources' class='btn btn-default getdatasources'>Data Sources</a> <% } %>" +
        "<a href='<%= conn.id%>' class='user_button btn btn-danger form_button remove_datasource hide'>Remove</a>" +
        "<a href='<%= conn.id%>' class='user_button form_button btn btn-default save_datasource'>Save</a>" +
        "<a href='<%= conn.id%>' class='refresh_button form_button user_button btn btn-default hide'>Refresh" +
        " Cache</a><div class='clear'></div></form>" +
        "<div id='savestatus'></div>"
       ),
    schemauploadtemplate: _.template( "<h3>Schema Management</h3>" +
        "<input name='fileschema' type='file' class='form-control upload_button'/><div class='clear'></div><br/>" +
        "<label for='schemaname'>Schema Name:</label><input name='schemaname' type='text' class='form-control' value='<%=" +
        " schema.id %>'/><br/>" +
        "<a href='<%= schema.id%>' class='user_button form_button btn btn-default remove_schema hide'>Remove</a>" +
        "<a href='<%= Settings.REST_URL + AdminUrl %>/schema/<%= schema.id%>' class='user_button btn btn-default form_button download_schema hide'>Download</a><input type='submit' class='user_button form-control form_button upload_button submitdatasource' value='Upload'>" +
        "<br/><div id='uploadstatus'></div>"),
    licenseInfoTemplate: _.template("<h3>License Information</h3>" +
        "<ul class='license_type'><li><strong>License Type: </strong></li>" +
        "<li><strong>License Expiry: </strong></li>" +
        "<li><strong>License Contact: </strong></li>" +
        "<li><strong>License Hostname: </strong></li>" +
        "<li><strong>User Limit: </strong></li></ul>"),
    licenseAddUserTemplate: _.template("<form>" +
        "<h3>Add user</h3><br>" +
        "<label for='username'>Username:</label> <input class='form-control' type='text' name='username'>" +
        "<a href='#' class='add_license_user btn btn-default form_button user_button'>Add User</a><div class='clear'></div><br>" +
        "<h3>List of Users</h3>" +
        "<ol class='license_listusers'></ol>" +
        "</form>"),

    view_user: function (event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var $t = $(event.target);
        var $target = $currentTarget.find('a');
        $currentTarget.addClass('selected');
        var path = $target.attr('href').replace('#', '');
        var user = this.users.get(path);
        var html = this.usertemplate({user: user.attributes});
        $(this.el).find('.user_info').html(html);
        $(this.el).find('.remove_user').removeClass("hide");

    },
    accordion : function(event){
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);


            //Expand or collapse this panel
            $($currentTarget).next().slideToggle('fast');

            //Hide the other panels
            //$($currentTarget.parent()).not($($currentTarget).next()).slideUp('fast');



    },
    view_schema: function (event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var $target = $currentTarget.find('a');
        $currentTarget.addClass('selected');
        var path = $target.attr('href').replace('#', '');

        var user = this.schemas.get(path);
        var html = this.schemauploadtemplate({schema: user});

        $(this.el).find('.user_info').html(html);
        Saiku.events.trigger('admin:viewschema', {
            admin: this
        });

        $(this.el).find('.remove_schema').removeClass("hide");
        $(this.el).find('.download_schema').removeClass("hide");

        $(this.el).find('.submitdatasource').hide();
        $(this.el).find('input[name="fileschema"]').hide();

    },
    view_datasource: function (event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var $target = $currentTarget.find('a');
        $currentTarget.addClass('selected');
        var path = $target.attr('href').replace('#', '');
        var user = this.datasources.get(path);
        var s = this.schemas;
        var html = this.datasourcetemplate({conn: user.attributes,schemas: s.models, properties: this.pkeys});

        $(this.el).find('.user_info').html(html);
        Saiku.events.trigger('admin:viewdatasource', {
            admin: this
        });
        if(user.get("advanced")!=null){
            this.advanced_url(event);
        }
        else{
            this.simple_url(event);
        }
        this.hide_driver_els(user.get("connectiontype"));

        $(this.el).find('.drivertype').val(user.get("connectiontype"));
        if(user.get("security_type")) {
            $(this.el).find('#secselect').val(user.get("security_type").toUpperCase());
        }
        if(user.get("propertyKey")){
            $(this.el).find(".extpropselect").val(user.get("propertyKey"));
        }
        $(this.el).find('.remove_datasource').removeClass("hide");
        $(this.el).find('.refresh_button').removeClass("hide");
    },
    save_user: function (event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        $currentTarget.addClass('selected');
        var name = $currentTarget.text();


        var path = $currentTarget.attr('href')//.replace('#', '');


        var user = this.users.get(path);
        var username = $(this.el).find("input[name='username']");
        var emailaddress = $(this.el).find("input[name='email']");
        if(username===null){
            $.notify('Cannot update user with empty username', {globalPosition: 'top center', className: 'error'})
        }
        else {
            user.set({username: username.val(), email: emailaddress.val(), password: null});

            user.save({}, {
                data: JSON.stringify(user.attributes), contentType: "application/json"
                , success: function (e) {
                    $.notify('User updated successfully', {globalPosition: 'top center', className: 'success'});

                }
            });
        }


    },
    save_password: function (event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var $target = $currentTarget.find('a');
        $currentTarget.addClass('selected');
        var path = $currentTarget.attr('href').replace('#', '');
        var name = $target.text();
        var $newtarget = $(this.el).find("input[name='newpassword']");
        var $newtarget2 = $(this.el).find("input[name='newpassword2']");

        var user = this.users.get(path);

        if ($newtarget.val() == $newtarget2.val()) {
            user.set({password: $newtarget.val()});
            user.save({}, {data: JSON.stringify(user.attributes), contentType: "application/json", success: function(e){
                $.notify('Password updated successfully', { globalPosition: 'top center', className: 'success' });
                $newtarget.val("");
                $newtarget2.val("");
            }});
        }
        else {
            console.log("validation error");
            $.notify('Validation Error', { globalPosition: 'top center', className: 'error' });

        }
    },
    add_role: function (event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var $target = $(this.el).find("input[name='role']");
        $currentTarget.addClass('selected');
        var path = $currentTarget.attr('href').replace('#', '');
        var name = $target.val();


        var user = this.users.get(path);
        var roles = user.get("roles");
        if (roles == undefined) {
            roles = [];
        }
        roles.push(name);
        user.set({roles: roles, password: null});
        $(this.el).find(".role_select").append($("<option></option>")
            .attr("value", name)
            .text(name));
        user.save({}, {data: JSON.stringify(user.attributes), contentType: "application/json"});
    },
    new_add_role: function (event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var $target = $(this.el).find("input[name='role']");
        $currentTarget.addClass('selected');
        var name = $target.val();


        if(this.temproles == undefined) {
            this.temproles = [];
        }
        this.temproles.push(name);
        //user.set({roles: roles});
        $(this.el).find(".role_select").append($("<option></option>")
            .attr("value", name)
            .text(name));
        $target.val("");
        //user.save({}, {data: JSON.stringify(user.attributes), contentType: "application/json"});
    },
    new_remove_role: function (event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        $currentTarget.addClass('selected');
        var selected = $(this.el).find('.role_select').val();


        for (var i = 0; i < selected.length; i++) {
            this.temproles = jQuery.grep(this.temproles, function (value) {
                return value != selected[i];
            });

            $(this.el).find(".role_select").find(":selected").remove();
        }
        //user.save({}, {data: JSON.stringify(user.attributes), contentType: "application/json"});
    },
    remove_role: function (event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var $target = $currentTarget.find('a');
        $currentTarget.addClass('selected');
        var path = $currentTarget.attr('href').replace('#', '');
        var selected = $(this.el).find('.role_select').val();
        var user = this.users.get(path);
        var roles = user.get("roles");
        for (var i = 0; i < selected.length; i++) {
            roles = jQuery.grep(roles, function (value) {
                return value != selected[i];
            });
            user.set({roles: roles, password:null});
            $(this.el).find(".role_select").find(":selected").remove();
        }
        user.save({}, {data: JSON.stringify(user.attributes), contentType: "application/json"});
    },
    create_user: function (event) {
        event.preventDefault();
        var $currentTarget = $(event.currentTarget);
        var user = new User();
        var html = this.usertemplate({user: user.attributes});
        $(this.el).find('.user_info').html(html);
        $(this.el).find('.user_info').find(".save_user").hide();
        $(this.el).find('.user_info').find(".save_password").hide();
        $(this.el).find('.user_info').find(".add_role").hide();
        $(this.el).find('.user_info').find(".remove_role").hide();
        $(this.el).find('.user_info').find(".new_add_role").show();
        $(this.el).find('.user_info').find(".new_remove_role").show();
        $(this.el).find('.user_info').find(".save_new_user").show();
    },
    save_new_user: function (event) {
        event.preventDefault();

        var user = new User();
        var username = $(this.el).find("input[name='username']");
        var emailaddress = $(this.el).find("input[name='email']");
        var $newtarget = $(this.el).find("input[name='newpassword']");
        var $newtarget2 = $(this.el).find("input[name='newpassword2']");
        var roles = $(this.el).find(".role_select").val();



        var that = this;
        //var user = this.users.get(path);

        if ($newtarget.val() == $newtarget2.val()) {

            user.set({username: username.val(), email: emailaddress.val(), roles: that.temproles, password: $newtarget.val()});
            this.users.add(user);
            user.save({}, {data: JSON.stringify(user.attributes), contentType: "application/json", success: function(){
                that.temproles = [];
                that.fetch_users();
                $(that.el).find('.user_info').html("");


            }});
        }
        else {
            console.log("validation error");
        }

    },
    clear_users: function() {
    $(this.el).find('.inner_users').empty();
    $(this.el).find('.inner_users').append("<li class='create_user'>Add User</li>");
    },
    clear_datasources: function(){
        $(this.el).find('.inner_datasource').empty();
        Saiku.events.trigger('admin:loaddatasources', {
            admin: this
        });


        //this.fetch_datasources();
    },
    clear_schema: function(){
        $(this.el).find('.inner_schema').empty();
        Saiku.events.trigger('admin:loadschema', {
            admin: this
        });


        //this.fetch_datasources();
    },

    create_datasource: function (event) {
        event.preventDefault();
        var conn = new Connection();
        var s = this.schemas;
        var html = this.datasourcetemplate({conn: conn, schemas: s.models, properties: this.pkeys});

        $(this.el).find('.user_info').html(html);
        Saiku.events.trigger('admin:viewdatasource', {
            admin: this
        });

    },
    uploadFile: function (event) {
        event.preventDefault();

        var file = $(this.el).find("input[type='file']")[0].files[0];
        var schema = new Schema();
        schema.set('file', file);
        schema.set('name', $(this.el).find("input[name='schemaname']").val());
        var that = this;
        this.schemas.create({file: file, name: $(this.el).find("input[name='schemaname']").val()}, {processData: true, success: function(){
            $(that.el).find('#uploadstatus').html("Upload Successful!");
            that.schemas.fetch();
        },error: function(data, xhr){
            $(that.el).find('#uploadstatus').html("Upload failed!<br/>("+xhr.responseText+")");
            that.schemas.fetch();
        }});
    },
    restoreFile: function(event){
        event.preventDefault();

        var file = $(this.el).find("input[type='file']")[0].files[0];
        var restore = new Restore();
        restore.set('file', file);
        restore.save();
    },
    restoreLegacy: function(event){
        event.preventDefault();

        var file = $(this.el).find("input[type='file']")[0].files[0];
        var restore = new RestoreFiles();
        restore.set('file', file);
        restore.save();
    },

    save_datasource: function (event) {
        event.preventDefault();

        var self = this;
        var $currentTarget = $(event.currentTarget);
        var name = this.$el.find('input[name="connname"]').val();
        var connType = this.$el.find('.drivertype').val();
        var jdbcUrl = this.$el.find('input[name="jdbcurl"]').val();
        var mondrianSchema = this.$el.find('.schemaselect').val();
        var driver = this.$el.find('input[name="driver"]').val();
        var connUserName = this.$el.find('input[name="connusername"]').val();
        var connPassword = this.$el.find('input[name="connpassword"]').val();
        var uuid = $currentTarget.attr('href').replace('#', '');
        var advanced = this.$el.find('textarea[name="adv_text"]').val();
        var isVisibleAdvancedConn = this.$el.find('.advconnection').is(':visible');
        var alertMsg = '';
        var conn;

        if (uuid === undefined || uuid === '') {
            conn = new Connection();
            this.datasources.add(conn);
        }
        else {
            conn = this.datasources.get(uuid);
        }

        if (advanced !== null && advanced !== undefined && advanced !== '') {
            conn.set({ 'advanced':  advanced });
        }
        else if (connType === 'MONGO') {
            var mongoSchema = this.$el.find('select[name="mongoschema"]').val();
            var dataSource = 'type=OLAP\n' +
                'name=' + name + '\n' +
                'driver=mondrian.olap4j.MondrianOlap4jDriver\n' +
                'location=jdbc:mondrian:Jdbc=jdbc:calcite:model=mongo:///etc/mongoschema/' + mongoSchema + ';Catalog=mondrian://' + mondrianSchema + ';JdbcDrivers=org.apache.calcite.jdbc.Driver;\n' +
                'username=admin\n' +
                'password=admin';

            conn.set({ 'advanced': dataSource });
        }
        else {
            var securityType = this.$el.find('.securityselect').val();

            conn.set({
               'connectionname': name,
                'connectiontype': connType,
                'jdbcurl': jdbcUrl,
                'schema': mondrianSchema,
                'driver': driver,
                'username': connUserName,
                'password': connPassword
            });

            if (securityType === 'ONE2ONE') {
                conn.set({ 'security_type': 'one2one' });
            }
            else if (securityType === 'PASSTHROUGH') {
                conn.set({ 'security_type': 'passthrough' });
            }
            else {
                conn.set({ 'security_type': null });
            }

            if (this.$el.find('.extpropselect').val()) {
                conn.set({ 'propertyKey': this.$el.find('.extpropselect').val() });
            }
        }

        if (_.isEmpty(name) && !isVisibleAdvancedConn) {
            alertMsg += '<li>The Name field can not be empty!</li>';
        }
        if (_.isEmpty(jdbcUrl) && connType !== 'MONGO' && !isVisibleAdvancedConn) {
            alertMsg += '<li>The URL field can not be empty!</li>';
        }
        if (_.isEmpty(driver) && connType === 'MONDRIAN' && connType !== 'MONGO' && !isVisibleAdvancedConn) {
            alertMsg += '<li>The JDBC Driver field can not be empty!</li>';
        }
        if (_.isEmpty(advanced) && isVisibleAdvancedConn) {
            alertMsg += '<li>The Advanced field can not be empty!</li>';
        }
        if (alertMsg !== '') {
            (new WarningModal({
                title: '<span class="i18n">Required Fields</span>',
                message: '<ul>' + alertMsg + '</ul>'
            })).render().open();
        }
        else {
            conn.save({}, {
                data: JSON.stringify(conn.attributes),
                contentType: 'application/json',
                success: function() {
                    self.fetch_datasources();
                    self.$el.find('.user_info').html('');
                },
                error: function(data, xhr) {
                    self.$el.find('#savestatus').html('Save failed!<br/>(' + xhr.responseText + ')');
                    self.schemas.fetch();
                }
            });
        }
    },

    remove_datasource : function(event) {
        event.preventDefault();

        var $currentTarget = $(event.currentTarget);
       // var $target = $currentTarget.find('a');
        $currentTarget.addClass('selected');
        var path = $currentTarget.attr('href').replace('#', '');

        var ds = this.datasources.get(path);
        var that = this;
        ds.destroy({wait: true,success: function(){that.fetch_datasources();$(that.el).find('.user_info').html("");}});
    },
    remove_schema : function(event){
        event.preventDefault();

        var $currentTarget = $(event.currentTarget);
        // var $target = $currentTarget.find('a');
        $currentTarget.addClass('selected');
        var path = $currentTarget.attr('href').replace('#', '');

        var s = this.schemas.get(path);
        var that = this;
        s.destroy({wait:true, success: function(){that.fetch_schemas();$(that.el).find('.user_info').html("");}})
    },
    download_schema : function(event) {
        event.preventDefault();

        var $currentTarget = $(event.currentTarget);
        // var $target = $currentTarget.find('a');
        $currentTarget.addClass('selected');
        var path = $currentTarget.attr('href').replace('#', '');

        var s = this.schemas.get(path);
        s.fetch({wait:true, success: function(){alert("here");}})

    },
    remove_user : function(event){
        event.preventDefault();

        var $currentTarget = $(event.currentTarget);
        var $target = $currentTarget.find('a');
        $currentTarget.addClass('selected');
        var path = $currentTarget.attr('href').replace('#', '');

        var ds = this.users.get(path);
        var that = this;
        ds.destroy({wait:true, success: function(){this.fetch_users();$(that.el).find('.user_info').html("");}});
    },
    refresh_datasource : function(event){
        event.preventDefault();

        var $currentTarget = $(event.currentTarget);
        $currentTarget.addClass('selected');
        var path = $currentTarget.attr('href').replace('#', '');

        var ds = this.datasources.get(path);

        ds.refresh();
    },
    advanced_url : function(event){
        event.preventDefault();
        $(this.el).find(".simpleConnection").hide();
        $(this.el).find(".advconnection").show();
    },
    get_data_sources: function(event) {
        event.preventDefault();
        var formElements = {
            'url': 'input[name="jdbcurl"]',
            'driver': 'input[name="driver"]',
            'username': 'input[name="connusername"]',
            'password': 'input[name="connpassword"]',
			'name': 'input[name="connname"]'
        };
        (new DataSourcesModal({ dialog: this, formElements: formElements })).render().open();
    },
    simple_url : function(event){
        event.preventDefault();
        $(this.el).find(".simpleConnection").show();
        $(this.el).find(".advconnection").hide();

    },
    hide_driver_els :function(type){
        switch(type) {
            case "XMLA":
                console.log("Xmla");
                $(this.el).find('label[for="connname"]').show();
                $(this.el).find('label[for="drivertype"]').show();
                $(this.el).find('label[for="jdbcurl"]').show();
                $(this.el).find('label[for="schemapath"]').hide();
                $(this.el).find('label[for="driver"]').hide();
                $(this.el).find('label[for="connusername"]').show();
                $(this.el).find('label[for="connpassword"]').show();
                $(this.el).find('label[for="securityselect"]').show();

                $(this.el).find('input[name="connname"]').show();
                $(this.el).find('select[name="drivertype"]').show();
                $(this.el).find('input[name="jdbcurl"]').show();
                $(this.el).find('select[name="schemapath"]').hide();
                $(this.el).find('input[name="driver"]').hide();
                $(this.el).find('input[name="connusername"]').show();
                $(this.el).find('input[name="connpassword"]').show();
                $(this.el).find('select[name="securityselect"]').show();

                $(this.el).parent().find('.advancedurl').show();
                $(this.el).parent().find('.save_datasource').show();

                $(this.el).find('.horridbr').hide();
                break;
            case "MONDRIAN":
                console.log("mondrian");
                $(this.el).find('label[for="connname"]').show();
                $(this.el).find('label[for="drivertype"]').show();
                $(this.el).find('label[for="jdbcurl"]').show();
                $(this.el).find('label[for="schemapath"]').show();
                $(this.el).find('label[for="driver"]').show();
                $(this.el).find('label[for="connusername"]').show();
                $(this.el).find('label[for="connpassword"]').show();
                $(this.el).find('label[for="securityselect"]').show();

                $(this.el).find('input[name="connname"]').show();
                $(this.el).find('select[name="drivertype"]').show();
                $(this.el).find('input[name="jdbcurl"]').show();
                $(this.el).find('select[name="schemapath"]').show();
                $(this.el).find('input[name="driver"]').show();
                $(this.el).find('input[name="connusername"]').show();
                $(this.el).find('input[name="connpassword"]').show();
                $(this.el).find('select[name="securityselect"]').show();

                $(this.el).parent().find('.advancedurl').show();
                $(this.el).parent().find('.save_datasource').show();

                $(this.el).find('.horridbr').show();
                break;
        }
    }

});
Saiku.events.bind('admin:loaddatasources', function(admin){
    $(admin.admin.el).find('.inner_datasource').append("<li class='i18n create_datasource'>Add Data Source</li>");

});
Saiku.events.bind('admin:loadschema', function(admin){
    $(admin.admin.el).find('.inner_schema').append("<li class='i18n create_schema'>Add Schema</li>");

});
Saiku.events.bind('session:new', function (session) {
if(Saiku.session.isAdmin) {
    var $link = $("<a />")
        .attr({
            href: "#adminconsole",
            title: "Admin Console",
            class: "i18n",
			id: "admin_icon"
        })
        .click(Saiku.AdminConsole.show_admin)
        .addClass('admin');
    var $li = $("<li />").append($link);
    $(Saiku.toolbar.el).find('ul').append($li).append('<li class="separator">&nbsp;</li>');
}
});
Saiku.events.bind('admin:changedriver', function(options){
    var div = options.div;
    var type = options.type;

    switch(type) {
        case "XMLA":
            console.log("Xmla");
            $(div).find('label[for="connname"]').show();
            $(div).find('label[for="drivertype"]').show();
            $(div).find('label[for="jdbcurl"]').show();
            $(div).find('label[for="schemapath"]').hide();
            $(div).find('label[for="driver"]').hide();
            $(div).find('label[for="connusername"]').show();
            $(div).find('label[for="connpassword"]').show();
            $(div).find('label[for="securityselect"]').show();

            $(div).find('input[name="connname"]').show();
            $(div).find('select[name="drivertype"]').show();
            $(div).find('input[name="jdbcurl"]').show();
            $(div).find('select[name="schemapath"]').hide();
            $(div).find('input[name="driver"]').hide();
            $(div).find('input[name="connusername"]').show();
            $(div).find('input[name="connpassword"]').show();
            $(div).find('select[name="securityselect"]').show();

            $(div).parent().find('.advancedurl').show();
            $(div).parent().find('.save_datasource').show();

            $(div).find('.horridbr').hide();
            break;
        case "MONDRIAN":
            console.log("mondrian");
            $(div).find('label[for="connname"]').show();
            $(div).find('label[for="drivertype"]').show();
            $(div).find('label[for="jdbcurl"]').show();
            $(div).find('label[for="schemapath"]').show();
            $(div).find('label[for="driver"]').show();
            $(div).find('label[for="connusername"]').show();
            $(div).find('label[for="connpassword"]').show();
            $(div).find('label[for="securityselect"]').show();

            $(div).find('input[name="connname"]').show();
            $(div).find('select[name="drivertype"]').show();
            $(div).find('input[name="jdbcurl"]').show();
            $(div).find('select[name="schemapath"]').show();
            $(div).find('input[name="driver"]').show();
            $(div).find('input[name="connusername"]').show();
            $(div).find('input[name="connpassword"]').show();
            $(div).find('select[name="securityselect"]').show();

            $(div).parent().find('.advancedurl').show();
            $(div).parent().find('.save_datasource').show();

            $(div).find('.horridbr').show();
            break;
    }
});

var AdminUrl = "admin";


var User = Backbone.Model.extend({

});

var Users = Backbone.Collection.extend({
    model: User,
    initialize: function (args, options) {
        if (options && options.dialog) {
            this.dialog = options.dialog;
        }
    },

    parse: function (response) {
        if (this.dialog) {
            this.dialog.populate(response);
        }
        return response;
    },

    url: function () {
        var segment = AdminUrl + "/users";
        return segment;
    }
});


var PropertyKey = Backbone.Model.extend({

});

var Schema = Backbone.Model.extend({

    fileAttribute: 'file',
    idAttribute: "name"
});
var Restore = Backbone.Model.extend({
    url: function(){
      return AdminUrl + "/restore";
    },
    fileAttribute: 'file'
});

var RestoreFiles = Backbone.Model.extend({
    url: function(){
        return AdminUrl + "/legacyfiles";
    },
    fileAttribute: 'file'
});


var PropertiesKeys = Backbone.Collection.extend({
   model: PropertyKey,
    url: function () {
        return AdminUrl + "/datakeys"
    },
    initialize: function(args,options){
        if(options && options.dialog){
            this.dialog = options.dialog;


        }
    },
    parse: function(response) {
        this.dialog.pkeys=[];
        var that=this;
        _.each(response, function(f){
            that.dialog.pkeys.push(f);
        });
        return response;
    }
});

var Schemas = Backbone.Collection.extend({
    model: Schema,
    url: function () {
        return AdminUrl + "/schema";
    },
    initialize: function (args, options) {
        if (options && options.dialog) {
            this.dialog = options.dialog;
        }
    },

    parse: function (response) {
        if (this.dialog) {
            this.dialog.populateschema(response);
        }
        return response;
    }
})
var Connection = Backbone.Model.extend({
    refresh: function(){
        $.ajax({
            type: 'GET',
            url: Settings.REST_URL+AdminUrl+"/datasources/"+this.get("connectionname")+"/refresh"
        });
    }
});

var Connections = Backbone.Collection.extend({
    url: AdminUrl + "/datasources",
    model: Connection,
    initialize: function (args, options) {
        if (options && options.dialog) {
            this.dialog = options.dialog;
        }
    },

    parse: function (response) {
        if (this.dialog) {
            this.dialog.populate2(response);
        }
        return response;
    },
    refresh: function(){
        $.ajax({
            type: 'GET',
            //url: AdminUrl+"/datasources/"+this.connectionname+"/refresh"
            url: Settings.REST_URL + AdminUrl + "/datasources"
        });
    }
});
