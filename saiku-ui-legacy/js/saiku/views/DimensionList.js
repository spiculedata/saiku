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
 * Controls the appearance and behavior of the dimension list
 * 
 * This is where drag and drop lives
 */
var DimensionList = Backbone.View.extend({
    events: {
        'click span': 'select',
        'click a': 'select',
        'click .parent_dimension ul li a.level' : 'select_dimension',
        'click .measure' : 'select_measure',
        'click .addMeasure' : 'measure_dialog'
    },
    
    initialize: function(args) {
        // Don't lose this
        _.bindAll(this, "render", "load_dimension","select_dimension");
        
        // Bind parent element
        this.workspace = args.workspace;
        this.cube = args.cube;
    },
    
    load_dimension: function() {
        this.template = this.cube.get('template_attributes');
        this.render_attributes();
        this.workspace.sync_query();

    },
    
    render: function() {
         // Fetch from the server if we haven't already
        if (this.cube && this.cube.has('template_attributes')) {
            this.load_dimension();
        } else if (! this.cube){
            $(this.el).html('Could not load attributes. Please log out and log in again.');
        } else {
            var template = _.template($("#template-attributes").html());
            $(this.el).html(template);
            $(this.el).find(".loading").removeClass("hide");
            this.workspace.bind('cube:loaded',  this.load_dimension);

        }

        return this;
    },

    render_attributes: function() {
        // Pull the HTML from cache and hide all dimensions
        var self = this;
        $(this.el).html(this.template);
        if (isIE && isIE <= 8) {
            $(this.el).show();
        } else {
            $(this.el).fadeTo(500,1);
        }
        
        // Add draggable behavior
        $(this.el).find('.addMeasure, .calculated_measures').show();
        $(this.el).find('.measure').parent('li').draggable({
            cancel: '.not-draggable',
            connectToSortable: $(this.workspace.el).find('.fields_list_body.details ul.connectable'),
            helper: 'clone',
            placeholder: 'placeholder',
            opacity: 0.60,
            tolerance: 'touch',
            containment:    $(self.workspace.el),
            cursorAt: { top: 10, left: 35 }
        });        

        $(this.el).find('.level').parent('li').draggable({
            cancel: '.not-draggable, .hierarchy',
            connectToSortable: $(this.workspace.el).find('.fields_list_body.columns > ul.connectable, .fields_list_body.rows > ul.connectable, .fields_list_body.filter > ul.connectable'),
            containment:    $(self.workspace.el),
            //helper: "clone",
            helper: function(event, ui){
                var target = $(event.target).hasClass('d_level') ? $(event.target) : $(event.target).parent();
                var hierarchy = target.find('a').attr('hierarchy');
                var level = target.find('a').attr('level');
                var h = target.parent().clone().removeClass('d_hierarchy').addClass('hierarchy');
                h.find('li a[hierarchy="' + hierarchy + '"]').parent().hide();
                h.find('li a[level="' + level + '"]').parent().show();
                var selection = $('<li class="selection"></li>');
                selection.append(h);
                return selection;

            },

            placeholder: 'placeholder',
            opacity: 0.60,
            tolerance: 'touch',
            cursorAt: {
                top: 10,
                left: 85
            }
        });
    },
    
    select: function(event) {
        var $target = $(event.target).hasClass('root') ? $(event.target) : $(event.target).parent().find('span');
        if ($target.hasClass('root')) {
            $target.find('a').toggleClass('folder_collapsed').toggleClass('folder_expand');
            $target.toggleClass('collapsed').toggleClass('expand');
            $target.parents('li').find('ul').children('li').toggle();

            if($target.hasClass('expand')){
                Saiku.events.trigger("workspace:expandDimension", this, null);

            }
        }
        
        return false;
    },

     select_dimension: function(event, ui) {
        if (this.workspace.query.model.type != "QUERYMODEL") {
            return;
        }
        if ($(event.target).parent().hasClass('ui-state-disabled')) {
            event.preventDefault();
            event.stopPropagation();
            return;
        }

        var hierarchy = $(event.target).attr('hierarchy');
        var hierarchyCaption = $(event.target).parent().parent().attr('hierarchycaption');
        var level = $(event.target).attr('level');
        var axisName = "ROWS";
        var isCalcMember = $(event.target).parent().hasClass('dimension-level-calcmember');

        if ($(this.workspace.el).find(".workspace_fields ul.hierarchy[hierarchy='" + hierarchy + "']").length > 0) {
             var $level = $(this.workspace.el).find(".workspace_fields ul[hierarchy='" + hierarchy + "'] a[level='" + level + "']").parent().show();
            axisName = $level.parents('.fields_list_body').hasClass('rows') ? "ROWS" : "COLUMNS";
        } else {
            var $axis = $(this.workspace.el).find(".workspace_fields .fields_list[title='ROWS'] ul.hierarchy").length > 0 ?
                $(this.workspace.el).find(".workspace_fields .fields_list[title='COLUMNS'] ul.connectable") :
                $(this.workspace.el).find(".workspace_fields .fields_list[title='ROWS'] ul.connectable") ;

            axisName = $axis.parents('.fields_list').attr('title');
        }

        if (isCalcMember) {
            var uniqueName = $(event.target).attr('uniquename');
            this.workspace.toolbar.$el.find('.group_parents').removeClass('on');
            this.workspace.toolbar.group_parents();
            this.workspace.query.helper.includeLevelCalculatedMember(axisName, hierarchy, level, uniqueName);
        }
        else {
            this.workspace.query.helper.includeLevel(axisName, hierarchy, level);
        }

        // Trigger event when select dimension
        Saiku.session.trigger('dimensionList:select_dimension', { workspace: this.workspace });

        this.workspace.sync_query();
        this.workspace.query.run();
        event.preventDefault();
        return false;
    },

    select_measure: function(event, ui) {
        if ($(event.target).parent().hasClass('ui-state-disabled')) {
            return;
        }
        var $target = $(event.target).parent().clone();
        var measure = {
            "name": $target.find('a').attr('measure'),
            "type": $target.find('a').attr('type')
        };
        this.workspace.query.helper.includeMeasure(measure);
        this.workspace.sync_query();
        this.workspace.query.run();
        event.preventDefault();
        return false;
    },

    measure_dialog: function(event, ui) {
        (new CalculatedMemberModal({ 
            workspace: this.workspace,
            measure: null
        })).render().open();
    }
});
