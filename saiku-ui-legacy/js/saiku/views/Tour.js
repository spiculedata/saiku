var Tour = Backbone.View.extend({
    initialize: function(args) {
        _.bindAll(this, "run_tour");
        _.extend(this, Backbone.Events);


        this.toolbar = args.toolbar;


    },
    run_tour: function(){
var that = this;
        var st = [{
            // this is a step object
            content: '<p>Click here to open a new query.</p>',
            highlightTarget: true,
            nextButton: false,
            closeButton: true,
            target: $('#new_query'),
            my: 'top left',
            at: 'right bottom',
            setup: function(tour, options){
                //options.view.reset();
                Saiku.events.bind('toolbar:new_query', this.onSelectKitten);
                //options.view.enable();
            },
            teardown: function(tour, options){
                // Disallow more kitten selection
                //options.view.disable();
                Saiku.events.unbind('toolbar:new_query', this.onSelectKitten);
            },
            bind: ['onSelectKitten'],
            onSelectKitten: function(tour, options, view, kitten){
                options.view = kitten;
                tour.next();
            }
        },
            {
                content: '<p>Select the Foodmart Sales Cube</p>',
                highlightTarget: true,
                nextButton: false,
                closeButton: true,
                target: $('.cubes'),
                my: 'bottom center',
                at: 'top center',
                setup: function(tour, options){
                    //options.view.reset();
                    //options.view.bind('workspace:new_query', this.onSelectCube);
                    Saiku.events.bind('workspace:new_query', this.onSelectCube);
                    //options.view.enable();
                },
                teardown: function(tour, options){
                    // Disallow more kitten selection
                    //options.view.disable();
                    Saiku.events.unbind('workspace:new_query', this.onSelectCube);
                },
                bind: ['onSelectCube'],
                onSelectCube: function(tour, options, view, kitten){
                    if(kitten.cube==="Sales"){
                        options.view = kitten.view;
                        setTimeout( function(){
                            if(that.exec){
                                tour.next();
                            }
                            else{
                                that.exec = true;
                                tour.next();

                            }
                        }, 200 );
                        //tour.next();
                    }
                    else{
                        $('.tour-container').html('<p>You didn\'t select the Sales cube.</p>');
                    }

                }
            },
            {
                content: '<p>Great, next we need to execute a query.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.dimension_tree'),
                my: 'bottom center',
                at: 'top center',
                setup: function(tour, options){
                    return { target: $('.dimension_tree')}
                }
            },
            {
                content: '<p>First lets expand the Customer dimension.</p>',
                highlightTarget: true,
                nextButton: false,
                closeButton: true,
                target: $('a[title="Customer"]'),
                my: 'left center',
                at: 'right center',
                setup: function(tour, options){
                    Saiku.events.bind('workspace:expandDimension', this.onExpandDimension);
                    return { target: $('a[title="Customer"]')}
                },
                bind: ['onExpandDimension'],
                onExpandDimension: function(tour, options, view, kitten){
                    tour.next();
                }
            },
            {
                content: '<p>Great next drag the State Province level to the Rows dropzone.</p>',
                highlightTarget: true,
                nextButton: false,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'left center',
                at: 'right center',
                setup: function(tour, options){

                    Saiku.events.bind('workspaceDropZone:select_dimension', this.onSelectLevel);

                    $('a[title="State Province"]').addClass("tour-highlight");
                    return { target: $('.rows_fields')}
                },
                bind: ['onSelectLevel'],
                onSelectLevel: function(tour, options, view, kitten){
                    if(kitten.hierarchy === "[Customer].[Customers]" && kitten.level === "State Province"){
                        tour.next();
                    }
                    else{
                        $('.tour-container').html('<p>You didn\'t select the correct level. ' +
                            'Remove this one and replace it with the highlighted level.</p>');
                    }

                }
            },
            {
                content: '<p>Next we\'ll add a measure. Drag Unit Sales into the Measures Drop Zone.</p>',
                highlightTarget: true,
                nextButton: false,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'left top',
                at: 'right bottom',
                setup: function(tour, options){

                    Saiku.events.bind('workspaceDropZone:select_measure', this.onSelectMeasure);

                    return { target: $('a[title="[Measures].[Unit Sales]"]')}
                },
                bind: ['onSelectMeasure'],
                onSelectMeasure: function(tour, options, view, kitten){
                    if(kitten.measure.name === "Unit Sales"){
                        tour.next();
                    }
                    else{
                        $('.tour-container').html('<p>You didn\'t select the correct measure. ' +
                            'Remove this one and replace it with the highlighted measure.</p>');
                    }

                }
            },
            {
                content: '<p>Great, we\'ve now run a very basic query. Now we\'ll view it as a chart.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'bottom center',
                at: 'top center',
                setup: function(tour, options){
                    return { target: $('div.table_wrapper')}
                }
            },
            {
                content: '<p>To view your data as a chart, click on the chart icon.</p>',
                highlightTarget: true,
                nextButton: false,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'right center',
                at: 'left center',
                setup: function(tour, options){

                    Saiku.events.bind('queryToolbar:render_chart', this.onClickChartIcon);

                    return { target: $('.query_toolbar_vertical')}
                },
                bind: ['onClickChartIcon'],
                onClickChartIcon: function(tour, options, view, kitten){
                    tour.next();
                }
            },
            {
                content: '<p>You can now select from the different chart type Saiku offers.</' +
                'p>Saiku Enterprise users can set chart properties by clicking on the properties button.</p>' +
                '<p>You can also export your chart to a file by clicking on the Export button.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'right center',
                at: 'left center',
                setup: function(tour, options){

                    Saiku.events.bind('queryToolbar:render_chart', this.onClickChartIcon);

                    return { target: $('.query_toolbar_vertical')}
                },
                bind: ['onClickChartIcon'],
                onClickChartIcon: function(tour, options, view, kitten){
                    tour.next();
                }
            },
            {
                content: '<p>On the workspace toolbar there are a number of useful buttons.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'top center',
                at: 'bottom center',
                setup: function(tour, options){
                    return { target: $('.workspace_toolbar')}
                }
            },
            {
                content: '<p>Edit Query. This button will hide the query design area on the left of the workspace.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'top center',
                at: 'bottom center',
                setup: function(tour, options){
                    return { target: $('a[original-title="Edit query"]')}
                }
            },
            {
                content: '<p>Run Query/Automatic Execution. These icons allow you to manually run a query, or,' +
                ' toggle automatic query execution so that you have to exeucte the query manually.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'top center',
                at: 'bottom center',
                setup: function(tour, options){
                    return { target: $('a[original-title="Run query"]')}
                }
            },
            {
                content: '<p>Hide Parents. This button allows you to show the parent levels in the result set.</p>' +
                '<p>Hidden by default, some queries are easier understood when you can see the data hierarchy.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'top center',
                at: 'bottom center',
                setup: function(tour, options){
                    return { target: $('a[original-title="Hide Parents"]')}
                }
            },
            {
                content: '<p>Non Empty. This button hides null fields so minimise the resultset. Click this button' +
                ' to show the empty fields.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'top center',
                at: 'bottom center',
                setup: function(tour, options){
                    return { target: $('a[original-title="Non-empty"]')}
                }
            },
            {
                content: '<p>Zoom. Zoom into a large table, by dragging over the area you want to show.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'top center',
                at: 'bottom center',
                setup: function(tour, options){
                    return { target: $('a[original-title="Zoom into table"]')}
                }
            },
            {
                content: '<p>Drill Across. A way to discover more about your data. Select drill across and enable' +
                ' the required dimensions in the popup, the new result set will show you a more granular output of your previous query.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'top center',
                at: 'bottom center',
                setup: function(tour, options){
                    return { target: $('a[original-title="Drill across on cell"]')}
                }
            },
            {
                content: '<p>Drill Through. Drill through to the lowest level to show how your query was constructed.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'top center',
                at: 'bottom center',
                setup: function(tour, options){
                    return { target: $('a[original-title="Drill through on cell"]')}
                }
            },
            {
                content: '<p>We\'ve now run through the basics that Saiku Analytics has to offer. There is far more' +
                ' that we haven\'t explored so you might want to try one of the more advanced tutorials or just get started and explore yourself.</p>' +
                '<p>For more help, visit <a href="http://saiku-documentation.readthedocs.io/en/latest" target="_blank">http://saiku-documentation.readthedocs.io/en/latest</a>.</p>',
                highlightTarget: true,
                nextButton: true,
                closeButton: true,
                target: $('.rows_fields'),
                my: 'top center',
                at: 'bottom center',
                setup: function(tour, options){
                    return { target: $('a[original-title="Drill through on cell"]')}
                }
            },
        ];
        var tour = new Tourist.Tour({
            steps: st,
            stepOptions: {
                view: this.toolbar
            },
            tipClass: 'QTip',
            tipOptions:{ showEffect: 'slidein' }
        });
        tour.start();

    }
});

