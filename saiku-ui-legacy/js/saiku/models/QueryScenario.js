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
 
var QueryScenario = Backbone.Model.extend({
    initialize: function(args, options) {
        // Maintain `this`
        _.bindAll(this, "attach_listeners", "activate", "clicked_cell", "save_writeback", 
            "cancel_writeback", "check_input");
        
        this.query = options.query;
    },
    
    activate: function() {
        $(this.query.workspace.el).find("td.data").unbind('click').addClass('cellhighlight').click(this.clicked_cell);
    },

    attach_listeners: function(args) {
        if (args.workspace.query && args.workspace.query.properties &&
            args.workspace.query.properties.properties['org.saiku.connection.scenario'] === "true" &&
            $(args.workspace.el).find('.query_scenario').hasClass('on'))
        $(args.workspace.el).find("td.data").click(this.clicked_cell);
    },
    
    clicked_cell: function(event) {
        var $target = $(event.target).hasClass('data') ?
            $(event.target).find('div') : $(event.target);
        var value = $target.attr('alt');
        var pos = $target.attr('rel');
        
        var $input = $("<input type='text' value='" + value + "' />")
            .keyup(this.check_input)
            .blur(this.cancel_writeback);
        $target.html('').append($input);
        $input.focus();
    },
    
    check_input: function(event) {
        if (event.which == 13) {
            this.save_writeback(event);
        } else if (event.which == 27 || event.which == 9) {
            this.cancel_writeback(event);
        }
         
        return false;
    },
    
    save_writeback: function(event) {
        var $input = $(event.target).closest('input');
        this.set({
            value: $input.val(),
            position: $input.parent().attr('rel')
        });
        this.save();
        var value = $input.val();
        $input.parent().text(value);
    },
    
    cancel_writeback: function(event) {
        var $input = $(event.target).closest('input');
        $input.parent().text($input.parent().attr('alt'));
    },
    
    parse: function() {
        this.query.run();
    },

    url: function() {
        return this.query.url() + "/cell/" + this.get('position') + 
            "/" + this.get('value'); 
    }
});
