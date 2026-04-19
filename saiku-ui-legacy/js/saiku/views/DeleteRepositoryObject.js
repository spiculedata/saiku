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
 * The delete query confirmation dialog
 */
var DeleteRepositoryObject = Modal.extend({
    type: "delete",
    
    buttons: [
        { text: "Yes", method: "del" },
        { text: "No", method: "close" }
    ],
    
    initialize: function(args) {
        this.options.title = "Confirm deletion";
        this.query = args.query;
        this.success = args.success;
        this.message = '<span class="i18n">Are you sure you want to delete </span>'+'<span>' + this.query.get('name') + '?</span>';
    },
    
    del: function() {
        this.query.set("id", _.uniqueId("query_"));
        this.query.id = _.uniqueId("query_");
        this.query.url = this.query.url() + "?file=" + encodeURIComponent(this.query.get('file'));
        this.query.destroy({
            success: this.success,
            dataType: "text",
            error: this.error,
            wait:true
        });
        this.close();
    },
    
    error: function() {
        $(this.el).find('dialog_body')
            .html('<span class="i18n">Could not delete repository object</span>');
    }
});
