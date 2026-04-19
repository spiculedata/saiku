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
var AddFolderModal = Modal.extend({

    type: "save",
    closeText: "Save",

    events: {
        'click .form_button': 'save',
        'submit form': 'save'
    },

    buttons: [
        { text: "OK", method: "save" }
    ],

    initialize: function(args) {
        var self = this;
        this.success = args.success;
        this.path = args.path;
        this.message = "<form id='add_folder'>" +
            "<label class='i18n' for='name'>To add a new folder, " + 
            "please type a name in the text box below:</label>" +
            "<input type='text' class='form-control newfolder' name='name' />" +
            "</form>";

        _.extend(this.options, {
            title: "Add Folder"
        });

        
        // fix event listening in IE < 9
        if(isIE && isIE < 9) {
            $(this.el).find('form').on('submit', this.save);    
        }

    },

    save: function( event ) {
        event.preventDefault( );
        var self = this;
        
        var name = $(this.el).find('input[name="name"]').val();
        var file = this.path + name;
        (new SavedQuery( { file: file , name: name} ) ).save({}, { 
            success: self.success,
            dataType: "text",
            error: this.error
        } );
        this.close();
        return false;
    },

    error: function() {
        $(this.el).find('dialog_body')
            .html("Could not add new folder");
    }


});
