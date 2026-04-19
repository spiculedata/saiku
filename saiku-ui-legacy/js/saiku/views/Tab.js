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
 * Class which handles individual tabs
 */
var Tab = Backbone.View.extend({
    tagName: 'li',

    events: {
        'click a': 'select',
        'mousedown a': 'remove',
        'click .close_tab': 'remove'
    },

    template: function() {
        // Create tab
        return _.template("<a class='saikutab' href='#<%= id %>'><%= caption %></a>" +
                "<span class='close_tab sprite'>Close tab</span>")
            ({
                id: this.id,
                caption: this.caption
            });
    },

    /**
     * Assign a unique ID and assign a Backbone view
     * to handle the tab's contents
     * @param args
     */
    initialize: function(args) {
        _.extend(this, Backbone.Events);
        _.extend(this, args);
        this.content.tab = this;
        this.caption = this.content.caption();
        this.id = _.uniqueId('tab_');
        this.close = args.close;
    },

    /**
     * Render the tab and its contents
     * @returns tab
     */
    render: function() {
        var self = this;
        // Render the content
        this.content.render();

        // Generate the element
        $(this.el).html(this.template());
        if(this.close === false){
            $(this.el).find('.close_tab').hide();
            $(this.el).css('padding-right','10px');
        }
        var menuitems = {
            "new": {name: "New", i18n: true },
            "duplicate": { name: "Duplicate", i18n: true},
            "closeothers": {name: "Close Others", i18n: true },
            "closethis": {name: "Close This", i18n: true }
        };
        $.each(menuitems, function(key, item){
            recursive_menu_translate(item, Saiku.i18n.po_file);
        });

        $.contextMenu('destroy', '.saikutab');
        $.contextMenu({
                selector: '.saikutab',
                callback: function(key, options) {
                    var selected = options.$trigger.attr('href').replace('#','');
                    var tab = Saiku.tabs.find(selected);
                 	  if (key == "closethis") {
                        tab.remove();
                        self.select();
                        return;
                    } else if (key == "closeothers") {
                        tab.select();
                        Saiku.tabs.close_others(tab);
                    } else if (key == "duplicate") {
                        Saiku.tabs.duplicate(tab);
                    } else if (key == "new") {
                        Saiku.tabs.new_tab();
                    }
                    //self.workspace.chart.exportChart(key);
                },
                items: menuitems
            });

        return this;
    },

    set_caption: function(caption) {
        $(this.el).find('.saikutab').html(caption);
    },

    /**
     * Destroy any data associated with this tab and ensure proper
     * garbage collection to avoid memory leaks
     */
    destroy: function() {
        // Delete data
        if (this.content && this.content.query) {
            this.content.query.destroy();
        }
    },

    /**
     * Select a tab
     * @param el
     */
    select: function() {
        var self = this;
        // Deselect all tabs
        this.parent.select(this);

        // Select the selected tab
        $(this.el).addClass('selected');

        // Trigger select event
        this.trigger('tab:select');
        return false;
    },

    /**
     * Remove a tab
     * @returns {Boolean}
     */
    remove: function(event) {
        if (!event || event.which === 2 || $(event.target).hasClass('close_tab')) {
            // Remote the tab object from the container
            this.parent.remove(this);

            try {
                // Remove the tab element
                $(this.el).remove();

                // Remove the tab
                this.destroy();
            } catch (e) {
                Log.log(JSON.stringify({
                    Message: "Tab could not be removed",
                    Tab: JSON.stringify(this)
                }));
            }
        }

        return false;
    },

    rendered: function() {
        return $.contains( document, this.el );
    }

});

/**
 * Class which controls tab pager
 */
var TabPager = Backbone.View.extend({
    className: 'pager_contents',
    events: {
        'click a': 'select'
    },

    initialize: function(args) {
        this.tabset = args.tabset;
        $(this.el).hide().appendTo('body');

        // Hide when focus is lost
        $(window).click(function(event) {
            if (! $(event.target).hasClass('pager_contents')) {
                $('.pager_contents').hide();
            }
        });
    },

    render: function() {
        var pager = "";
        for (var i = 0, len = this.tabset._tabs.length; i < len; i++) {
            pager += "<a href='#" + i + "'>" +
                this.tabset._tabs[i].caption + "</a><br />";
        }
        $(this.el).html(pager);
        $(this.el).find(".i18n").i18n(Saiku.i18n.po_file);
    },

    select: function(event) {
        var $currentTarget = $(event.currentTarget);
        var index = $currentTarget.attr('href').replace('#', '');
        this.tabset._tabs[index].select();
        $(this.el).hide();
        event.preventDefault();
        return false;
    }
});
