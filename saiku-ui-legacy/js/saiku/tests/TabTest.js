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
 
module('Tab', {
    setup: function() {
        var workspace = new Workspace();
        this.tab = new Tab({ content: workspace });
    }
});

test('event handler', function event_handler() {
    var global_value = false;
    this.tab.bind('zero', function() {
        global_value = true;
    });
    
    this.tab.trigger('zero');
    equal(true, global_value, 'Could not bind event listener');
});

test('multiple event handlers', function multiple_handlers() {
    var global_value = 0;
    
    this.tab.bind('multiple', function() {
        global_value+=1;
    });
    
    this.tab.bind('multiple', function() {
        global_value+=1;
    });
    
    this.tab.bind('multiple', function() {
        global_value+=1;
    });
    
    this.tab.trigger('multiple');
    equal(3, global_value, 'Could not bind multiple event listeners');
});

test('tab render', function tab_render() {
    this.tab.render();
    notStrictEqual(-1, $(this.tab.el).html().indexOf('Unsaved query'));
    strictEqual(-1, $(this.tab.el).html().indexOf('Unsaved Query (1)'));
});

test('tab properties', function tab_properties() {
    notEqual(this.tab.caption, "");
    notStrictEqual(this.tab.id, undefined);
    equal(this.tab.id, this.tab.content.tab.id);
    notStrictEqual(undefined, this.tab.bind);
    notStrictEqual(undefined, this.tab.trigger);
});

module('TabSet', {
    setup: function() {
        this.tabset = new TabSet();
    },
    
    teardown: function() {
        $("#tab_panel").remove();
    }
});

test('initial conditions', function initial_conditions() {
    equal(this.tabset.queryCount, 0);
    equal(undefined, this.tabset.pager);
    this.tabset.render();
    ok(this.tabset.pager !== undefined);
    ok(this.tabset.content !== undefined);
    notEqual(-1, $(this.tabset.el).html().indexOf('#pager'));
});

test('tab lifecycle', function add_tab() {
    // Add tab
    this.tabset.render();
    raises(this.tabset.add, TypeError, 
        "Don't allow tab instantiation without Backbone view as parameter");
    var tab = this.tabset.add(new Workspace());
    ok(this.tabset === tab.parent, 
        "Should set parent property when creating tab");
    equal(1, this.tabset.queryCount,
        "Query count should be incremented");
    equal(this.tabset.queryCount, this.tabset._tabs.length,
        "Because no tabs have been removed, query count and tab length should match");
    var tab2 = this.tabset.add(new Workspace());
    equal(2, this.tabset.queryCount,
        "Query count should be incremented");
    
    // Select tab
    this.tabset.select(tab);
    this.tabset.select(tab2);
    
    // Remove tab
    this.tabset.remove(tab);
    equal(2, this.tabset.queryCount,
        "Query count should not decrement");
    notEqual(this.tabset.queryCount, this.tabset._tabs.length,
        "Query count should not equal tab length");
    this.tabset.remove(tab2);
    equal(1, this.tabset._tabs.length,
        "Tabset should add a tab when empty");
});
