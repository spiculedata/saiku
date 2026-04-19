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
 * Class which handles table rendering of resultsets
 */
var Table = Backbone.View.extend({
    className: 'table_wrapper',
    events: {
        'click th.row' : 'clicked_cell',
        'click th.col' : 'clicked_cell',
        'click span.expander' : 'expand_row'
    },

    initialize: function(args) {
        this.workspace = args.workspace;
        this.renderer = new SaikuTableRenderer();

        // Bind table rendering to query result event
        _.bindAll(this, "render", "process_data", "collapse" ,"expand");
        this.workspace.bind('query:result', this.render);
        this.id = _.uniqueId("table_");
        $(this.el).attr('id', this.id);
  },


  expand_row: function (event) {
    var self = this;
    var $targetSpan = $(event.currentTarget);
    if ($targetSpan.hasClass('expanded')) {
      self.collapse(event, $targetSpan);
    } else {
      self.expand(event, $targetSpan);
    }
    event.stopPropagation();
    event.preventDefault();
  },

  collapse: function (event, $targetSpan) {
    var self = this;
    var $targetDiv = $(event.currentTarget).parent();
    var $targetRow = $targetDiv.parent().parent();
    $targetSpan.html('&#9658;');
    $targetSpan.removeClass('expanded');
    $targetSpan.addClass('collapsed');
    var pos = $targetDiv.attr('rel').split(':');
    var row = parseInt(pos[0]);
    var col = parseInt(pos[1]);

    var cell = self.workspace.query.result.lastresult().cellset[row][col];
    var $rowIterator = $targetRow.next();
    row++;
    var hasColspan = $targetRow.find('td[colspan],th[colspan]').length > 0;
    if (self.workspace.toolbar.$el.find('.group_parents').hasClass('on')) {
      if(!hasColspan){
        self.collapseAndHide($targetRow, $rowIterator, row, col);
      } else {
        self.justCollapse($rowIterator, $targetDiv);
      }
    } else {
      if(!hasColspan){
        self.collapseAndHide($targetRow, $rowIterator, row, col);
      } else {
        self.justCollapse($rowIterator, $targetDiv);
      }
    }
  },

  justCollapse : function($rowIterator, $targetDiv){
    var nextRowSame = true;
    var tdIndex = $targetDiv.parent().children().index($targetDiv);
    while (nextRowSame) {
      if($($rowIterator.find('th,td')[tdIndex]).hasClass('row_null')){
        $rowIterator.hide();
        $rowIterator = $rowIterator.next();
      } else {
        nextRowSame = false;
      }
    }
  },

  collapseAndHide : function($targetRow, $rowIterator, row, col){
    var self = this;
    var nextRowSame = true;
    var cells = $targetRow.find('th, td');
    for (var i = col + 1; i < cells.length; i++) {
      $(cells[i]).find('div').hide();
    }
    // group_parents = true
    while (nextRowSame) {
      if ($rowIterator.length > 0) {
        var isLast = $rowIterator.find('th,td');
        if (isLast.length > 0 && $(isLast[col]).hasClass('row_total_corner')) {
          nextRowSame = false;
          var targetRowDataValues = $targetRow.find('div.datadiv');
          var summaryDataValues = $rowIterator.find('td.data');
          for (var i = 0; i < targetRowDataValues.length; i++) {
            var div = '<span class="totalSpan">' + $(summaryDataValues[i]).html() + '</span>';
            $(targetRowDataValues[i]).parent().addClass('total');
            $(targetRowDataValues[i]).parent().html($(targetRowDataValues[i]).wrap('<p/>').parent().html() + div);
          }
          $rowIterator.hide();
          break;
        } else {
          for (var i = 0; i <= col; i++) {
            var isLast = $rowIterator.find('th,td');
            var thisIsTotal = false;
            for(var j = 0 ; j < col && j < isLast.length; j ++){
              if($(isLast[j]).hasClass('row_total_corner')){
                thisIsTotal = true;
                nextRowSame = false;
              }
            }
            if (row < self.workspace.query.result.lastresult().cellset.length && self.workspace.query.result.lastresult().cellset[row][i].value != self.workspace.query.result.lastresult().cellset[row - 1][i].value && !thisIsTotal) {
              var $lastTotalRow = null;
              while ($rowIterator.find('.row_total_corner').length != 0) {
                $rowIterator.hide();
                $lastTotalRow = $rowIterator;
                $rowIterator = $rowIterator.next();
              }
              if ($lastTotalRow != null) {
                var targetRowDataValues = $targetRow.find('div.datadiv');
                var summaryDataValues = $lastTotalRow.find('td.data');
                for (var i = 0; i < targetRowDataValues.length; i++) {
                  var div = '<span class="totalSpan">' + $(summaryDataValues[i]).html() + '</span>';
                  $(targetRowDataValues[i]).parent().addClass('total');
                  $(targetRowDataValues[i]).parent().html($(targetRowDataValues[i]).wrap('<p/>').parent().html() + div);
                }
              }
              nextRowSame = false;
              break;
            }
          }
          if (!nextRowSame) {
            break;
          }
        }
        if ($rowIterator.find('.row_total_corner').length == 0) {
          row++;
        }
        $rowIterator.hide();
        $rowIterator = $rowIterator.next();
      } else {
        break;
      }
    }
  },

  expand: function (event, $targetSpan, row, col) {
    var self = this;

    var $targetDiv = $(event.currentTarget).parent();
    var pos = $targetDiv.attr('rel').split(':');
    var row = parseInt(pos[0]);
    var col = parseInt(pos[1]);

    $targetSpan.html('&#9660;');
    $targetSpan.removeClass('collapsed');
    $targetSpan.addClass('expanded');
    var $targetDiv = $(event.currentTarget).parent();
    var $targetRow = $targetDiv.parent().parent();

    var targetRowDataValues = $targetRow.find('th, td');
    for (var i = 0; i < targetRowDataValues.length; i++) {
      var dataCell = $(targetRowDataValues[i]).find('div');
      if (dataCell.length > 0) {
        $(dataCell).show();
      }
      $targetRow.find('.total').removeClass('total');
      $targetRow.find('.totalSpan').remove();
    }
    $targetRow.find('span.collapsed').removeClass('collapsed').addClass('expanded').html('&#9660;');

    var $rowIterator = $targetRow.next();
    while ($rowIterator.length > 0 && ($($rowIterator.find('th,td')[col]).hasClass("row_null") || $($rowIterator.find('th,td')[col]).hasClass("row_total_corner"))) {
      $rowIterator.find('span.collapsed').removeClass('collapsed').addClass('expanded').html('&#9660;');
      $rowIterator.find('.totalSpan').remove();
      $rowIterator.find('div:hidden').show();

      if ($rowIterator.css('display') == 'none') {
        $rowIterator.show();
      } else {
        break;
      }
      $rowIterator = $rowIterator.next();
    }
  },

    clicked_cell: function(event) {
        var self = this;

		//return false;
        if (/*this.workspace.query.get('type') != 'QM' ||*/ Settings.MODE == "table") {
            //return false;
        }
        if ($(this.workspace.el).find( ".workspace_results.ui-selectable" ).length > 0) {
            $(this.workspace.el).find( ".workspace_results" ).selectable( "destroy" );
        }

        var $target = ($(event.target).hasClass('row') || $(event.target).hasClass('col') ) ?
            $(event.target).find('div') : $(event.target);

    var $body = $(document);
    $.contextMenu('destroy', '.row, .col');
    $.contextMenu({
        appendTo: $target,
        selector: '.row, .col',
        ignoreRightClick: true,
         build: function($trigger, e) {
            var $target = $(e.currentTarget).find('div');
            var axis = $(e.currentTarget).hasClass('row') ? "ROWS" : "COLUMNS";
            var pos = $target.attr('rel').split(':');
            var row = parseInt(pos[0]);
            var col = parseInt(pos[1]);
            var cell = self.workspace.query.result.lastresult().cellset[row][col];
            var query = self.workspace.query;
            var schema = query.get('schema');
            var cube = query.get('connection') + "/" +
                query.get('catalog') + "/" +
                ((schema === "" || schema === null) ? "null" : schema) +
                "/" + query.get('cube');

            var d = cell.properties.dimension;
            var h = cell.properties.hierarchy;
            var l = cell.properties.level;
            var l_caption = "";
			 var l_name = "";

            var keep_payload = JSON.stringify(
                {
                    "hierarchy"     :  h,
                    "uniquename"    : l,
                    "type"          : "level",
                    "action"        : "delete"
                }) +
                "," + JSON.stringify(
                {
                    "hierarchy"     :  h,
                    "uniquename"    : cell.properties.uniquename,
                    "type"          : "member",
                    "action"        : "add"
                }
            );

            var children_payload = cell.properties.uniquename;

            var levels = [];
            var items = {};
			 var key = self.workspace.selected_cube;
			 var cubeModel = Saiku.session.sessionworkspace.cube[key];

			 var dimensions;
			 if (!cubeModel || !dimensions || !measures) {
				 if (typeof localStorage !== "undefined" && localStorage && localStorage.getItem("cube." + key) !== null) {
					 Saiku.session.sessionworkspace.cube[key] = new Cube(JSON.parse(localStorage.getItem("cube." + key)));
				 } else {
					 Saiku.session.sessionworkspace.cube[key] = new Cube({ key: key });
					 Saiku.session.sessionworkspace.cube[key].fetch({ async : false });
				 }
				 dimensions = Saiku.session.sessionworkspace.cube[key].get('data').dimensions;
			 }
            var used_levels = [];

             var v1 = self.workspace.query.helper.getHierarchy(h);
             var v2;

             if (v1) {
                 v2 =
                 _.each(v1.levels, function(level){
                     var lev = h+".["+level.name+"]";
                    used_levels.push(lev);
                 });
             }

            _.each(dimensions, function(dimension) {
                if (dimension.name == d) {
                    _.each(dimension.hierarchies, function(hierarchy) {
                        if (hierarchy.uniqueName == h) {
                            _.each(hierarchy.levels, function(level) {
                                items[level.name] = {
                                    name: level.caption,
                                    payload: JSON.stringify({
                                        "hierarchy"     : h,
                                        uniquename    : level.uniqueName,
                                        type          : "level",
                                        action        : "add"
                                    })
                                };
                                if(_.indexOf(used_levels, level.uniqueName) > -1) {
                                    items[level.name].disabled = true;
                                    items["remove-" + level.name] = {
                                        name: level.caption,
                                        payload: JSON.stringify({
                                            "hierarchy"     :  h,
                                            uniquename    : level.uniqueName,
                                            type          : "level",
                                            action        : "delete"
                                        })
                                    };

                                }
                                if (level.uniqueName == l) {
                                    l_caption = level.caption;
                                    l_name = level.name;
                                }
                                items["keep-" + level.name] = items[level.name];
                                items["include-" + level.name] = JSON.parse(JSON.stringify(items[level.name]));
                                items["keep-" + level.name].payload = keep_payload + "," + items[level.name].payload;
                            });
                        }
                    });
                }
            });
            items.keeponly = { payload: keep_payload };
            items.getchildren = { payload: children_payload };
            if (items.hasOwnProperty("remove-" + l_name) && items.hasOwnProperty("include-" + l_name)) {
                items.showall = { payload: items["remove-" + l_name].payload + ", " + items["include-" + l_name].payload};
            }



            var lvlitems = function(prefix) {
                var ritems = {};
                for (var key in items) {
                    if (prefix !== null && prefix.length < key.length && key.substr(0, prefix.length) == prefix) {
                            ritems[key] = items[key];
                    }
                }
                return ritems;
            };

            var member = $target.html();

            var citems = {
                    "name" : {name: "<b>" + member + "</b>", disabled: true },
                    "sep1": "---------",
                    "keeponly": {name: "Keep Only", i18n: true, payload: keep_payload }
            };
            if (d != "Measures") {
                //citems.getchildren = {name: "Show Children", i18n: true, payload: children_payload };
                citems.fold1key = {
                        name: "Include Level", i18n: true,
                        items: lvlitems("include-")
                    };
                citems.fold2key = {
                        name: "Keep and Include Level", i18n: true,
                        items: lvlitems("keep-")
                    };
                citems.fold3key = {
                        name: "Remove Level", i18n: true,
                        items: lvlitems("remove-")
                    };
                citems.filterlevel = {
                    name: "Filter Level", i18n: true
                };
                /*if (items.showall) {
                    citems.showall  =  { name: "Remove Filters", i18n: true };
                }*/
            }
            $.each(citems, function(key, item){
            	recursive_menu_translate(item, Saiku.i18n.po_file);
            });
            return {
                callback: function(key, options) {
                    var updates = [];

                    if(key === "keeponly") {

                        //self.workspace.query.helper.removeLevel(h, k);
                        var hierarchy = self.workspace.query.helper.getHierarchy(h);
						if (hierarchy && hierarchy.levels.hasOwnProperty(l_name)|| h == "[Measures]") {
                            if(h=="[Measures]"){
                                var measure = {
                                    caption: cell.value,
                                    name: cell.value,
                                    type: 'EXACT',
                                    uniqueName: cell.properties.uniquename
                                };

                                self.workspace.query.helper.clearMeasures();
                                self.workspace.query.helper.includeMeasure(measure);
                                self.workspace.sync_query();
                                self.workspace.query.run();
                            }
							else {
								updates.push({
									uniqueName: cell.properties.uniquename,
									caption: cell.properties.uniquename
								});
								hierarchy.levels[l_name].selection = {"type": "INCLUSION", "members": updates};
								self.workspace.drop_zones.synchronize_query();
								self.workspace.query.run(true);
							}
                        }
                    }
                    else if(key === "filterlevel"){
                        var lname = cell.properties.level.substring(cell.properties.level.lastIndexOf(".")+1);
                        lname = lname.replace("[","").replace("]","");
                        (new SelectionsModal({
                            target: $target,
                            name: "Filter Level",
                            key: cell.properties.hierarchy+"/"+lname,
                            workspace: self.workspace,
                            axis: "ROWS"
                        })).open();
                    }
                    else if(key.substring(0,key.indexOf("-")) === "remove"){
                        var k = key.substring(key.indexOf("-") + 1);

                        if (Settings.ALLOW_PARAMETERS) {
                            self.workspace.query.helper.removeParameter(h, k);
                            self.workspace.$el.find('.parameter_input').empty();
                        }

                        self.workspace.query.helper.removeLevel(h, k);
                        self.workspace.drop_zones.synchronize_query();
                        self.workspace.query.run(true);

                    }
                    else if(key.substring(0,key.indexOf("-")) === "keep"){


                        //Keep and Include
                        var k = key.substring(key.indexOf("-") + 1);

                        //self.workspace.query.helper.removeLevel(h, k);
                        var hierarchy = self.workspace.query.helper.getHierarchy(h);
                        if (hierarchy && hierarchy.levels.hasOwnProperty(l_caption)) {
                            updates.push({
                                uniqueName: cell.properties.uniquename,
                                caption: cell.properties.uniquename
                            });
                            hierarchy.levels[l_caption].selection = {"type": "INCLUSION", "members": updates};
                            self.workspace.query.helper.includeLevel(axis, h, k, null);
                            self.workspace.drop_zones.synchronize_query();
                            self.workspace.query.run(true);
                        }
                    }
                    else if(key.substring(0,key.indexOf("-")) === "include"){
                        //Include
                        var k =  key.substring(key.indexOf("-") + 1);
                        self.workspace.query.helper.includeLevel(axis, h, k, null);
                        self.workspace.drop_zones.synchronize_query();
                        self.workspace.query.run(true);
                    }

                },
                items: citems
            };
        }
    });
    $target.contextMenu();


    },


    render: function(args, block) {

        if (typeof args == "undefined" || typeof args.data == "undefined" ||
            ($(this.workspace.el).is(':visible') && !$(this.el).is(':visible'))) {
            return;
        }

        if (args.data !== null && args.data.error !== null) {
            return;
        }
        // Check to see if there is data
        if (args.data === null || (args.data.height && args.data.height === 0)) {
            return;
        }
        this.clearOut();
        $(this.el).html('Rendering ' + args.data.width + ' columns and ' + args.data.height + ' rows...');

        // Render the table without blocking the UI thread
        _.delay(this.process_data, 2, args.data);
    },

    clearOut: function() {
        // Do some clearing in the renderer
        this.renderer.clear();
        $(this.workspace.el).find( ".workspace_results" ).unbind('scroll');
        var element = document.getElementById(this.id);
        if(element == null){
            this.workspace.tab.select();
            var element = document.getElementById(this.id);
        }
        var table = element.firstChild;
        if (table) {
            element.removeChild(table);
        }

    },

    process_data: function(data) {
        var hideEmptyRows = (Settings.HIDE_EMPTY_ROWS && this.workspace.query.getProperty('saiku.olap.query.nonempty'));

        this.workspace.processing.hide();
        this.workspace.adjust();
        // Append the table
        this.clearOut();
        $(this.el).html('<table></table>');
        var contents = this.renderer.render(data, {
            hideEmpty:          hideEmptyRows,
            htmlObject:         $(this.el).find('table'),
            batch:              Settings.TABLE_LAZY,
            batchSize:          Settings.TABLE_LAZY_SIZE,
            batchIntervalSize:  Settings.TABLE_LAZY_LOAD_ITEMS,
            batchIntervalTime:  Settings.TABLE_LAZY_LOAD_TIME
        });
        this.post_process();
    },

    post_process: function() {
        if (this.workspace.query.get('type') == 'QM' && Settings.MODE != "view") {
            $(this.el).addClass('headerhighlight');
        } else {
            $(this.el).removeClass('headerhighlight');
        }
        /*
        var tipOptions = {
          delayIn: 200,
          delayOut:80,
          offset:  2,
          html:    true,
          gravity: "nw",
          fade:    false,
          followMouse: true,
          corners: true,
          arrow:   false,
          opacity: 1
    };

        $(this.el).find('th.row, th.col').tipsy(tipOptions);
        */
        $(this.el).find(".i18n").i18n(Saiku.i18n.po_file);
        this.workspace.trigger('table:rendered', this);

    }
});
