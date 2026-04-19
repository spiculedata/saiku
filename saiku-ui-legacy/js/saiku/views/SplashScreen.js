/*
 * Copyright 2014 OSBI Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * The Saiku Splash Screen
 */
var SplashScreen = Backbone.View.extend({
    events: {
        'click .run_query'      : 'run_query',
        'click .run_dashboards' : 'run_dashboard',
        'click .head'           : 'click_head',
        'click .beg_tut'        : 'run_tour'
    },

    template: _.template(
        '<div id="splash">' +
            '<nav>' +
                '<ul>' +
                    '<li class="active"><a class="welcome head" href="#">Welcome</a></li>' +
                    '<li><a class="features head" href="#">Features</a></li>' +
                    '<li><a class="help head" href="#">Get Help</a></li>' +
                    '<li class="enterprisetoggle enterprise"><a class="enterprise head" href="#">Enterprise</a></li>' +
                '</ul>' +
                '<h2>Explore Data. Visualise. Act.</h2>' +
            '</nav>' +
            '<section class="stabs">' +
                '<section style="margin-top: 50px; min-height: 700px;" id="welcome">' +
                    '<div style="width: 50%; float: left;">' +
                        '<h1 class="saikulogo">Saiku</h1>' +
                        '<p>Saiku has the power to change the way you think about your business and make decisions. Saiku provides powerful, web based analytics for everyone in your organisation. Quickly and easily analyse data from any data source to discover what is really happening inside and outside your organisation. <i class="icon icon-remove" style="height: 100px;"></i></p>' +
                        '<h2>Quick Links</h2>' +
                        '<ul class="quicklinks">' +
                            '<li><a class="run_query" href="#">Create a new query</a></li>' +
                            '<li><a class="run_dashboards" href="#">Create a dashboard</a></li>' +
                            '<li> <a href="http://www.meteorite.bi/products/saiku" target="_blank">Visit the website</a></li>' +
                            '<li><a class="help head" href="#">Tutorials</a></li>' +
                        '</ul>' +
                        '<p class="fixed"><a class="enterprisetoggle button" href="http://meteorite.bi" target="_blank">Get Enterprise</a></p>' +
                    '</div>' +
                    '<div style="width: 40%; margin-left: 10%; float: left;" id="dyn_content" class="enterprisetoggle">' +
                        '<h2>Discover more about Saiku</h2>' +
                        '<p style="margin-bottom: 10px;">Saiku Analytics provides both a Community Version and an Enterprise Version with added features. To find out more you can <a href="http://meteorite.bi" target="_blank">visit our website</a> or watch the videos on our <a href="https://www.youtube.com/channel/UChivLeroOJx0_JamfuZ_XHA" target="_blank">Youtube channel</a>.</p>' +
                        '<p>If you are using Saiku Analytics in a business or commercial product, you can help give back in many ways. Swing by our <a href="http://webchat.freenode.net/?channels=##saiku" target="_blank">IRC channel</a> and help foster the community, join the <a href="http://community.meteorite.bi" target="_blank">mailing lists</a> and ask/answer questions, <a href="http://meteorite.bi" target="_blank">sponsor a new feature</a>, or best of all <a href="http://www.meteorite.bi/saiku-pricing" target="_blank">purchase an EE license</a>, which funds development of Saiku Community Edition along with Enterprise Edition.</p>' +
                    '</div>' +
                '</section>' +
                '<section style="display: none !important; margin-top: 50px;" id="features">' +
                    '<h1 class="saikulogo">Saiku</h1>' +
                    '<h2>Features</h2>' +
                    '<h3>Web Based Analysis</h3>' +
                    '<p>Saiku provides the user with an entirely browser based experience. We support all modern browsers, and our user interface is 100% HTML and Javascript.<br/><br/>Saiku uses REST based communications, this allows the development of custom user interfaces and facilitates the easy integration of the Saiku Server into other applications and services.</p>' +
                    '<h3>Standards Compliant</h3>' +
                    '<p>Saiku is based upon the Microsoft MDX query language and will work on most JDBC compliant data sources. We also provide a number of connectors to NOSQL data sources.</p>' +
                    '<h3>Dynamic charting</h3>' +
                    '<p>Saiku uses a flexible charting engine to provide a wide range of charts and graphs. These are all HTML & Javascript only and don\'t require flash to be installed on the computer.</p>' +
                    '<h3>Pluggable visualisation engine</h3>' +
                    '<p>Saiku Enterprise boasts a fully pluggable visualisation engine. This allows developers to build third party extensions and plug them into Saiku Enterprise to extend or replace the existing visualisations.</p>' +
                '</section>' +
                '<section style="display: none !important; margin-top: 50px;" id="help">' +
                    '<h1 class="saikulogo">Saiku</h1>' +
                    '<h2>Help</h2>' +
                    '<p>We provide Training, Consulting and Support to ensure you get the most from Saiku and your data. Our services cover all aspects of data analysis including data strategy, design, architecture, deployment and application/software support.</p>' +
                    '<table style="margin-bottom: 100px;">' +
                        '<tr>' +
                            '<th>Tutorials</th>' +
                            '<th>Docs</th>' +
                            '<th>Support</th>' +
                        '</tr>' +
                        '<tr>' +
                            '<td>We have a number of click through tutorials to help get your started:' +
                                '<ul style="list-style: none;">' +
                                    '<li><a class="beg_tut" href="#">Beginners (Query building and charts)</a></li>' +
                                '</ul>' +
                            '</td>' +
                            '<td>Why not try  our new <a href="http://saiku-documentation.readthedocs.io/en/latest/" target="_blank">Documentation site</a><br/>for community documentation.</td>' +
                            '<td>If you require more, <br/><a href="mailto:info@meteorite.bi">contact us</a> for support.</td>' +
                        '</tr>' +
                    '</table>' +
                '</section>' +
                '<section style="display: none !important; margin-top: 50px;" id="enterprise">' +
                    '<h1 class="saikulogo">Saiku</h1>' +
                    '<h2>Enterprise</h2>' +
                    '<p>Saiku Enterprise is our fully supported and tested server and Pentaho plugin system. Buy Saiku Enterprise from as little as $15 per user per month and enjoy the addtional features Saiku Enterprise has to offer!</p>' +
                    '<p>To find out more visit our <a href="http://meteorite.bi" target="_blank">site</a> or <a href="mailto:info@meteorite.bi">schedule a call</a> with one of us and we can show you why you should choose Saiku Enterprise!</p>' +
                '</section>' +
            '</section>' +
        '</div>'
    ),

    initialize: function(args) {
        _.bindAll(this, 'caption');
        _.extend(this, Backbone.Events);
    },

    caption: function() {
        return '<span class="i18n">Home</span>';
    },

    render: function() {
        this.$el.html(this.template());

        if (
            Settings.LICENSE &&
            Settings.LICENSE.licenseType !== undefined &&
            Settings.LICENSE.licenseType !== 'trial' &&
            Settings.LICENSE.licenseType !== 'Open Source License'
        ) {
            this.hide_content_enterprise();
        }

        this.get_content();
        this.setup_page();

        $('#splash > nav > ul > li.active > a').click(function() {
            var active = $(this).attr('class');
            $('nav li').removeClass('active');
            $(this).parent().addClass('active');
            $('.stabs section').hide();
            $('#' + active).fadeIn();
        });

        return this;
    },

    click_head: function(event) {
        event.preventDefault();

        var target = event.target;
        var nav = $(target).attr('class').split(' ');
        var active = '';

        $('nav li').removeClass('active');
        $(target).parent().addClass('active');
        $('.stabs section').hide();

        if (nav.indexOf('welcome') > -1) {
            active = 'welcome';
        }
        else if (nav.indexOf('features') > -1) {
            active = 'features';
        }
        else if (nav.indexOf('help') > -1) {
            active = 'help';
        }
        else if (nav.indexOf('enterprise') > -1) {
            active = 'enterprise';
        }

        $('#' + active).fadeIn();
    },

    run_tour: function() {
        this.toolbar = Saiku.toolbar;

        var tour = new Tour({ toolbar: this.toolbar });

        tour.run_tour();
    },

    run_query: function() {
        Saiku.tabs.add(new Workspace());

        return false;
    },

    run_dashboard: function() {
        if (Saiku.Dashboards === undefined) {
            alert('Please upgrade to Saiku Enterprise for Dashboards');
        }
        else {
            var tab = _.find(Saiku.tabs._tabs, function(tab) {
                return tab.content instanceof Dashboards;
            });

            if (tab) {
                tab.select();
            }
            else {
                Saiku.tabs.add(new Dashboards());
            }

            return false;
        }

        return false;
    },

    setup_page: function() {
        var height = $(window).height();
        var active = $('nav li.active a').attr('class');

        $('body').height(height);
        $('.stabs section').each(function() {
            var vH = $(this).height();
            var dif = (height - vH) / 2 - 50;

            if (dif < 0) {
                dif = 20;
            }
        });

        $('#' + active).fadeIn();
    },

    hide_content_enterprise: function() {
        this.$el.find('.enterprisetoggle').css('visibility', 'hidden');
        this.$el.find('#splash #welcome').css({
            background: 'url("../../../images/src/mac.png") top right no-repeat'
        });
    },

    get_content: function() {
        var self = this;

        $.ajax({
            type: 'GET',
            url: 'http://meteorite.bi/content.json',
            async: false,
            contentType: 'application/json',
            dataType: 'jsonp',
            jsonpCallback: 'jsonCallback2',
            cache: true,
            success: function(json) {
                // self.$el.find('#dyn_content').html(json.item[0].content);
                self.$el.find('.responsive-container').fitVids();
                self.$el.html(self.template());

                if (
                    Settings.LICENSE &&
                    Settings.LICENSE.licenseType !== 'trial' &&
                    Settings.LICENSE.licenseType !== 'Open Source License'
                ) {
                    self.hide_content_enterprise();
                }
            },
            error: function() {
                self.$el.html(self.template());

                if (
                    Settings.LICENSE &&
                    Settings.LICENSE.licenseType !== 'trial' &&
                    Settings.LICENSE.licenseType !== 'Open Source License'
                ) {
                    self.hide_content_enterprise();
                }
            }
        });
    }
});
