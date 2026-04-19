/*  
 *   Copyright 2015 OSBI
 *
 *   Created by Andreas Verleysen
 */

/**
 * Changes the locale of the cube
 */
var ChangeLocale = Backbone.View.extend({

    initialize: function (args) {
        this.visible = false;

        this.workspace = args.workspace;

        // Create a unique ID for use as the CSS selector
        this.id = _.uniqueId("changeLocale_");
        $(this.el).attr({ id: this.id });

        // Bind table rendering to query result event
        _.bindAll(this, "render", "show", "handleClick", "processDatasourcesWithoutAddingNewTab", "toggleLocaleScreen", "setUserFeedback");

        this.datasources = new Connections({}, { dialog: this });

        // Add change locale button to saiku toolbar
        this.add_button();
        this.workspace.toolbar.changeLocale = this.show;

        this.localeOptionsScreenTemplate = _.template("" +
            "<div id='feedback' style='margin-left: 30px;'><p class='editor_info'> Choose your language </p></div>" +
            "<div id='languageOptions'>  " +
            "<ol style='list-style-type: none;'>" +
            "<%_.each(localeMap, function(localeCode, language){ %>" +
            "<li style='padding-bottom: 10px; padding-right: 10px; float: left;'>         <button class='form_button' style='padding-bottom: 18px;' id='<%=localeCode%>' > <%=language%> </button></li> " +
            "<%})%>" +
            " </ol>" +
            "</div> " +
            "");

        // Create locale screen
        this.localeOptionsScreen = $(this.localeOptionsScreenTemplate({localeMap: Settings.MONDRIAN_LOCALES}));

        // attach event handler
        this.localeOptionsScreen.find('button').click(this.handleClick);

        //function for menu
        $("#nav ul ").css({display: "none"}); // Opera Fix
        $("#nav li").hover(function () {
            $(this).find('ul:first').css({visibility: "visible", display: "none"}).show(400);
        }, function () {
            $(this).find('ul:first').css({visibility: "hidden"});
        });

        // Append chart to workspace
        $(this.workspace.el).find('.workspace_results')
            .prepend($(this.el).hide())
            .prepend(this.localeOptionsScreen.hide());

    },

    add_button: function () {
        var $chart_button =
            $('<a id="changeLocaleBtn" href="#changeLocale" class="i18n change_locale button disabled_toolbar sprite" title="Change locale"></a>')
                .css({  'background-image': "url('js/saiku/plugins/ChangeLocale/images/change_locale.png')",
                    'background-repeat': 'no-repeat',
                    'background-position': '7px 7px'
                });

        var $chart_li = $('<li class="seperator"></li>').append($chart_button);
        $(this.workspace.toolbar.el).find("ul").append($chart_li);
    },

    show: function (event, ui) {
        this.toggleWorkspaceWithLocaleScreen();
//        if ($(event.target).hasClass('on')) {
//            this.render();
//        } else {
//            this.workspace.table.render({ data: this.workspace.query.result.lastresult() });
//        }
    },

    toggleWorkspaceWithLocaleScreen: function () {
        this.toggleWorkSpace();
        this.toggleLocaleScreen();
    },

    toggleWorkSpace: function () {
        $(this.workspace.table.el).toggle();
        $(this.el).toggle();
    },

    toggleLocaleScreen: function () {
        if (this.visible) {
            this.setUserFeedback("Choose language");
            $(this.workspace.el).find('#languageOptions').addClass('hide');
            $(this.workspace.el).find('#feedback').addClass('hide');
            this.workspace.toolbar.run_query();
        }
        else {
            $(this.workspace.el).find('#languageOptions').removeClass('hide').show();
            $(this.workspace.el).find('#feedback').removeClass('hide').show();
        }
        $("#changeLocaleBtn").toggleClass('on');
        this.visible = !this.visible;
    },

    handleClick: function (event) {
        // Keep a reference to the main plugin object.
        var this_p = this;
        this_p.setUserFeedback("Changing locale...");
        // get selected locale
        var newLocale = $(event.target).attr('id');

        // get current selected connection name (without URL data)
        var selectedCube = $(".cubes option:selected").val();
        var selectedConnectionName = selectedCube.substring(0, selectedCube.indexOf("/"));

        //send connection name to back-end for update
        this_p.setUserFeedback("Persisting locale...");
        this_p.persistLocaleOfDataSource(selectedConnectionName, newLocale);
        this_p.setUserFeedback("Refreshing data source with new locale...");
        this_p.refreshDatasources();

        return false;
    },


    persistLocaleOfDataSource: function (selectedDataSourceName, newLocale) {
        var putUrl = Settings.REST_URL + "admin/datasources/" + selectedDataSourceName + "/locale";

        var xhr = new XMLHttpRequest();
        xhr.open('PUT', putUrl, false);
        xhr.setRequestHeader('Content-Type', 'application/json');
        xhr.send(newLocale);
    },

    refreshDatasources: function () {
        // keep reference to this plugin
        var this_plugin = this;
        if (typeof localStorage !== "undefined" && localStorage) {
            localStorage.clear();
        }

        Saiku.session.sessionworkspace.clear();

        if (typeof localStorage !== "undefined" && localStorage) {
            localStorage.setItem('saiku-version', Settings.VERSION);
        }

        Saiku.session.sessionworkspace.fetch({success: this_plugin.processDatasourcesWithoutAddingNewTab}, {});
    },

    processDatasourcesWithoutAddingNewTab: function (model, response) {
        // Save session in localStorage for other tabs to use
        if (typeof localStorage !== "undefined" && localStorage && localStorage.getItem('session') === null) {
            localStorage.setItem('session', JSON.stringify(response));

            // Set expiration on localStorage to one day in the future
            var expires = (new Date()).getTime() + Settings.LOCALSTORAGE_EXPIRATION;
            if (typeof localStorage !== "undefined" && localStorage) {
                localStorage.setItem('expiration', expires);
            }
        }

        // Generate cube navigation for reuse
        Saiku.session.sessionworkspace.cube_navigation = _.template($("#template-cubes").html())({
            connections: response
        });

        // Create cube objects
        Saiku.session.sessionworkspace.cube = {};
        Saiku.session.sessionworkspace.connections = response;
        _.delay(Saiku.session.sessionworkspace.prefetch_dimensions, 20);

        this.show();
    },

    setUserFeedback: function (msg) {
        $("#feedback").html("<p class='editor_info'>" + msg + "</p>");
    }
});

var DataSource = Backbone.Model.extend({
//    url: "admin/datasources",
    urlRoot: "admin/datasources",
    refresh: function () {
        $.ajax({
            type: 'GET',
            url: Settings.REST_URL + "admin" + "/datasources/" + this.get("connectionname") + "/refresh"
        });
    }
});

/**
 * Start Plugin
 */
Saiku.events.bind('session:new', function (session) {

    function new_workspace(args) {
        if (typeof args.workspace.changeLocale == "undefined") {
            args.workspace.changeLocale = new ChangeLocale({ workspace: args.workspace });
        }
    };

    function clear_workspace(args) {
        if (typeof args.workspace.changeLocale != "undefined") {
            $(args.workspace.changeLocale.localeOptionsScreen).hide();
            $(args.workspace.changeLocale.el).parents().find('.workspace_results table').show();
            $(args.workspace.changeLocale.el).hide();
        }
    };

    if (Settings.PLUGINS.indexOf("ChangeLocale") != -1) {
        // Attach to existing tabs
        for (var i = 0; i < Saiku.tabs._tabs.length; i++) {
            var tab = Saiku.tabs._tabs[i];
            new_workspace({
                workspace: tab.content
            });
        }
        ;

        // Attach to future tabs
        Saiku.session.bind("workspace:new", new_workspace);
        Saiku.session.bind("workspace:clear", clear_workspace);
    }
});
