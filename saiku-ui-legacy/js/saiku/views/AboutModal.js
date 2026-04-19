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
 * The "about us" dialog
 */
var AboutModal = Modal.extend({
    type: 'login',

    message: '<div class="about-us">' +
                 '<div class="header">' +
                     '<span>' + Settings.VERSION + '<span>' +
                     '<a href="http://saiku.meteorite.bi" target="_blank">http://saiku.meteorite.bi</a>' +
                 '</div>' +
                 '<div class="license-info">' +
                     '<h3>License Info</h3>' +
                     '<ul>' +
                         '<li class="label">Type: <span class="item license-type"></span></li>' +
                         '<li class="label item-license-expiration" hidden>Expires: <span class="item license-expiration"></span></li>' +
                         '<li class="label">Number of users: <span class="item license-user-limit"></span></li>' +
                         '<li class="label">Licensed to: <span class="item license-name"></span> - <span class="item license-email"></span></li>' +
                         '<li><a href="http://www.meteorite.bi/saiku-pricing" target="_blank">Order more licenses here</a></li>' +
                     '</ul>' +
                 '</div>' +
                 '<div class="footer">' +
                     '<span>Want to help? <a href="https://www.paypal.com/uk/cgi-bin/webscr?cmd=_flow&SESSION=YV5t-PRrJWXJ1nMw9KlOlfrivAY32xkbYXJ1sGaCkmEIEZgLPBXuP_FKQL0&dispatch=5885d80a13c0db1f8e263663d3faee8dc3f308debf7330dd8d0b0a9f21afd7d3&rapidsState=Donation__DonationFlow___StateDonationLogin&rapidsStateSignature=16fabaac8c6b9c132f78003cf605e5237537aa2b" target="_blank">Make a donation</a> or <a href="https://github.com/OSBI/saiku" target="_blank">contribute to the code!</a></span>' +
                     '<span>Powered by <img src="images/src/meteorite_free.png" width="20px"> <a href="http://www.meteorite.bi/services/consulting" target="_blank">www.meteorite.bi</a></span>' +
                 '</div>' +
             '</div>',

    events: {
        'click .dialog_footer a' : 'call'
    },

    initialize: function(args) {
        _.extend(this, args);

        this.options.title = '<span class="i18n">About</span> ' + Settings.VERSION;

        this.bind('open', function() {
            this.render_license_info();
        });
    },

    render_license_info: function() {
        var licenseType = Settings.LICENSE.licenseType === 'community_edition'
            ? 'Open Source License'
            : Settings.LICENSE.licenseType;
        var expiration;

        if (Settings.LICENSE.expiration) {
            expiration = new Date(parseFloat(Settings.LICENSE.expiration));
            this.$el.find('.item-license-expiration').show();
            this.$el.find('.license-expiration').text(expiration.toLocaleDateString());
        }

        this.$el.find('.license-type').text(licenseType);
        this.$el.find('.license-user-limit').text(Settings.LICENSE.userLimit);
        this.$el.find('.license-name').text(Settings.LICENSE.name);
        this.$el.find('.license-email').text(Settings.LICENSE.email);
    }
});
