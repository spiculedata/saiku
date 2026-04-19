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
 * Dialog for member selections
 */
var PermissionsModal = Modal.extend({
    type: "permissions",
    
    buttons: [
        { text: "Ok", method: "ok" },
        { text: "Cancel", method: "close" }
    ],

    events: {
        'click .add_role': 'add_role',
        'click .remove_acl' : 'remove_acl',
        'submit form': 'add_role',
        'click a': 'call',
        'click input.private' : 'keep_private'

    },

    rolesacl : {},
    acltype: "SECURED",

    
    initialize: function(args) {
        // Initialize properties
        _.extend(this, args);
        this.options.title = args.title;
        this.file = args.file;
        this.rolesacl = {};
        Saiku.ui.unblock();
        _.bindAll(this, "ok", "add_role", "remove_acl");

        // Resize when rendered
        this.bind('open', Saiku.i18n.translate());
        this.render();
               // Load template
       $(this.el).find('.dialog_body')
          .html(_.template($("#template-permissions").html())(this));

        $(this.el).find('.filterbox').autocomplete({
                    minLength: 1,
                    source: Saiku.session.roles
                }).data( "autocomplete" );
        /*._renderItem = function( ul, item ) {
                return $( "<li></li>" )
                    .data( "item.autocomplete", item )
                    .append( "<a class='label'>" + item.label + "</a>" )
                    .appendTo( ul );
                };
*/
        var acl = new RepositoryAclObject({ file : this.file });
        acl.fetch({ async: false });

        var definedRoles = (typeof acl.get('roles') == "undefined" || acl.get('roles') === null ? {} : acl.get('roles')); 
        this.rolesacl = definedRoles;
        var templ_roles =_.template($("#template-permissions-rolelist").html())({roles: definedRoles });


        $(this.el).find('.rolelist').html(templ_roles);

        var owner = (typeof acl.get('owner') == "undefined" || acl.get('owner') === null ? "" : acl.get('owner')); 
        var atype = (typeof acl.get('type') == "undefined" || acl.get('type') === null ? null : acl.get('type')); 
        if (atype !== null && atype == "PRIVATE") {
            $(this.el).find('.private_owner .owner').text(owner);
            $(this.el).find('.private_owner').show();
        }
        $(this.el).find(".i18n").i18n(Saiku.i18n.po_file);
    },
    
    add_role: function(event) {
        var self = this;
        event.preventDefault();
        if (this.acltype == "PRIVATE") {
            return false;
        }
        var role = $(this.el).find(".filterbox").val();
        var acls = [];
        var aclstring ="";
        var rolecount = 0;
        if (role && role.length > 0) {
            $(this.el).find('.acl:checked').each( function(index) { 
                if (index > 0) {
                    aclstring += ", ";
                }
                rolecount++;
                aclstring += $(this).val();
                acls.push($(this).val());
            });
            if (rolecount > 0) {
                self.rolesacl[role] = acls;
                $("<option value='" + role + "'>" + role + " ["+aclstring+"]</option>").appendTo($(this.el).find(".select_roles"));
                role = $(this.el).find(".filterbox").val("");
            } else {
                alert("You need to chose at least one ACL method for this role.");
            }
        }
        

        return false;
    },

    keep_private: function(event) {
        var isPrivate = $(this.el).find('input.private').is(':checked');
        if (isPrivate) {
            $(this.el).find('.permissions').addClass('disabled_toolbar');
            $("input.acl, input.filterbox, input.add_role, input.remove_acl").prop('disabled', true);
            this.acltype = "PRIVATE";
        } else {
            $(this.el).find('.permissions').removeClass('disabled_toolbar');
            $("input.acl, input.filterbox, input.add_role, input.remove_acl").prop('disabled', false);
            this.acltype = "SECURED";
        }
    },

    remove_acl: function(event) {
        var self = this;
        if (this.acltype == "PRIVATE") {
            return false;
        }

        $(this.el).find(".select_roles option:selected").each( function(index) { 
            delete self.rolesacl[$(this).val()];
        });
        $(this.el).find(".select_roles option:selected").remove();
        return false;
    },

    ok: function() {
        var closer = this.close();
        var acl = {};
        if (this.acltype == "PRIVATE") {
            acl = { "type" : "PRIVATE", "owner" : Saiku.session.username };
        } else {
            acl = { "type" : "SECURED", "roles" : this.rolesacl, "owner" : Saiku.session.username };
        }
        (new RepositoryAclObject({ file: this.file, acl: JSON.stringify(acl)})).save({ success: closer });

        return false;
    }
});