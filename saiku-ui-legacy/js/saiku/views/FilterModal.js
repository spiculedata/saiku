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
 * The "add a folder" dialog
 */
var FilterModal = Modal.extend({

    type: "filter",
    closeText: "Save",

    events: {
        //'submit form': 'save',
        'click .dialog_footer a' : 'call',
        'click .insert-member' : 'open_select_member_selector'
    },

    buttons: [
        { text: "OK", method: "save" },
        { text: "Cancel", method: "close" },
        { text: "Help", method: "help"}
    ],

    message: "",

    expression_text: function() {
        var c = "<div class='sidebar'><table" +
        " border='0px'>";
        if (this.expressionType == "Order") {
            c += "<tr><td class='col1'><label>Sort Type</label> <select class='form-control' id='fun'><option>ASC</option><option>BASC</option><option>DESC</option><option>BDESC</option> </select></td></tr>";
        }
        c += "<tr><td class='col1'>" + this.expressionType + " MDX Expression:</td></tr>" +
             "<tr><td class='col1' style='width:380px'><div class='filter-editor' style='width:380px' id='"+this.id+"'></div></td></tr>" +
             "</table>" +
            "<a href='#' class='form_button btn btn-default insert-member'>Insert Member</a></div>";
        return c;
    },

    expression: " ",
    expressonType: "",

    initialize: function(args) {
        var self = this;
        this.id = _.uniqueId('filter-modal-');
        this.workspace = args.workspace || args.query.workspace;
        this.axis = args.axis;
        this.query = args.query;
        this.success = args.success;
        this.expression = args.expression;
        this.expressionType = args.expressionType;
        _.bindAll(this, "save", "expression_text");

        _.extend(this.options, {
            title: "Custom " + this.expressionType + " for " + this.axis
        });

        this.message = this.expression_text(this.expressionType);

        this.bind( 'open', function( ) {
            $(this.el).find('.sidebar').width(380);

            this.editor = ace.edit(this.id);
            this.editor.setValue(self.expression);
            this.editor.setShowPrintMargin(false);
            this.editor.setFontSize(11);
        });

        // fix event listening in IE < 9
        if(isIE && isIE < 9) {
            $(this.el).find('form').on('submit', this.save);
        }

    },


    save: function( event ) {
        event.preventDefault( );
        var self = this;
        this.expression = this.editor.getValue();

        var alert_msg = "";
        if (typeof this.expression == "undefined" || !this.expression || this.expression === "") {
            alert_msg += "You have to enter a MDX expression for the " + this.expressionType + " function! ";
            alert(alert_msg);
        } else {
            if (self.expressionType == "Order") {
                var sortOrder = $('#fun').val();
                self.success(sortOrder, this.expression);
            } else {
                self.success(this.expression);
            }
            this.close();
        }

        return false;
    },

    error: function() {
        $(this.el).find('dialog_body')
            .html("Could not add new folder");
    },

    /**
     * Open the select member dialog
     * @param event
     */
    open_select_member_selector: function(event){
        event.preventDefault();
        var dimension = {
            val: this.$el.find('#cms-dimension option:selected').val(),
            txt: this.$el.find('#cms-dimension option:selected').text(),
            dataDimension: this.$el.find('#cms-dimension option:selected').data('dimension'),
            dataType: this.$el.find('#cms-dimension option:selected').data('type')
        };
        var editor = ace.edit(this.id);
        var self = this;

         (new ParentMemberSelectorModal({
                dialog: this,
                workspace: this.workspace,
                cube: this.workspace.selected_cube,
                dimensions: Saiku.session.sessionworkspace.cube[this.workspace.selected_cube].get('data').dimensions,
                selectDimension: dimension.val,
                dimension: dimension.dataDimension,
                hierarchy: dimension.txt,
                uniqueName: this.pmUniqueName,
                lastLevel: this.pmLevel,
                breadcrumbs: this.pmBreadcrumbs,
                select_type: "select_member",
                selected_member: this.selected_member,
                close_callback: function(args){
                    var e = editor;
                    self.close_select_modal(e, args);
                }
            })).render().open();

            this.$el.parents('.ui-dialog').find('.ui-dialog-title').text('Custom Filter');


    },

    /**
     * Callback to update the editor with the selected member.
     * @param editor
     * @param n
     */
    close_select_modal: function(editor, n){
        editor.insert(n);
    },

    help: function(){
        window.open("http://saiku-documentation.readthedocs.io/en/latest/filtering.html");
    }
});
