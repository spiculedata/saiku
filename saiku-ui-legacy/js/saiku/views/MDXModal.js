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
 * The view MDX dialog
 */
var MDXModal = Modal.extend({
    type: "mdx",
    
    initialize: function(args) {
        this.options.title = "MDX";
        this.message = _.template("<textarea><%= mdx %></textarea>")(args);
        this.bind( 'open', function( ) {
       	var self = this;
        	$(self.el).parents('.ui-dialog').css({ width: "550px" });
        });
    }
});
