var Fullscreen = Backbone.Model.extend({
    initialize: function(args) {
        //console.log('Fullscreen');
        this.workspace = args.workspace;
        // Add buckets button
        _.bindAll(this, "change_handler", "adjust");
        if (document && document["addEventListener"]) {
            document.addEventListener("fullscreenchange", this.change_handler, false);
            document.addEventListener("webkitfullscreenchange", this.change_handler, false);
            document.addEventListener("mozfullscreenchange", this.change_handler, false);
            this.add_button();
            this.workspace.bind('workspace:adjust', this.adjust);
        }
    },
    add_button: function() {
        var elem = $('.workspace_results', this.workspace.el).get(0);
        var supportsFullScreen = (elem && (elem.requestFullscreen || elem.msRequestFullscreen || elem.mozRequestFullScreen || elem.webkitRequestFullscreen));
        if (!supportsFullScreen)
            return;
        var button =
            $('<a href="#fullscreen" id="fullscreen_icon" class="fullscreen button disabled_toolbar i18n" title="Fullscreen"></a>')
            .css({  'background-image': "url('js/saiku/plugins/Fullscreen/fullscreen.png')",
                    'background-repeat':'no-repeat',
                    'background-position':'50% 50%',
                    'background-size': '16px'
                });
        var li = $('<li class="seperator"></li>').append(button);
        $(this.workspace.toolbar.el).find("ul").append(li);
        this.workspace.toolbar.fullscreen = this.show;
    },
    change_handler: function() {
        //The workspace is not nescessarily done resizing when event is triggered... :(
        //Had to put in a bit of delay, to wait for workspace.adjust() is done...
        //It is bad, I know. But the adjust event is thrown for so many things,
        //which meant doing lots of tests, if event was related to fullscreen change.
        //Was afraid of getting false positives and screw things up.
        setTimeout(function() {
            var fullscreenElement = document.fullscreenElement || document.mozFullScreenElement || document.webkitFullscreenElement;
            if (fullscreenElement) {
                //$(fullscreenElement).css({height: '100%'});
                //$(fullscreenElement).css({height: '100%'});
                //this.workspace.trigger('fullscreen:enabled');
            } else {
                this.workspace.trigger('fullscreen:disabled');
            }
        }.bind(this), 50);
    },
    show: function() {
        var elem = $('.workspace_results', this.workspace.el).get(0);
        if (elem.requestFullscreen) {
             elem.requestFullscreen();
        } else if (elem.msRequestFullscreen) {
            elem.msRequestFullscreen();
        } else if (elem.mozRequestFullScreen) {
            elem.mozRequestFullScreen();
        } else if (elem.webkitRequestFullscreen) {
            elem.webkitRequestFullscreen();
        }
    },
    adjust: function(workspace) {
        //This code is here, instead of in change_handler, because it is run after the workspace has made it's adjustments.
        //Put the code above, and it wil be ran before workspace adjust code, and it will put height to some unwanted value,
        //after we set it to 100%.
        //Could put it on a 100ms delay timeout. It is bad, but now related functionality is in two different places, which is also bad.
        //The adjust event is also thrown for too many things, which is not related...
        var fullscreenElement = document.fullscreenElement || document.mozFullScreenElement || document.webkitFullscreenElement;
        if (fullscreenElement) {
            $(fullscreenElement).css({height: '100%'});
            this.workspace.trigger('fullscreen:enabled');
        }
    }
});

Saiku.events.bind('session:new', function(session) {
    function new_workspace(args) {
        if (typeof args.workspace.fullscreen === "undefined") {
            args.workspace.fullscreen = new Fullscreen({ workspace: args.workspace });
        }
    }

    for(var i = 0, len = Saiku.tabs._tabs.length; i < len; i++) {
        var tab = Saiku.tabs._tabs[i];
        new_workspace({
            workspace: tab.content
        });
    };

    Saiku.session.bind("workspace:new", new_workspace);
});
