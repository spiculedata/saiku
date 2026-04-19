/**
 * Created by bugg on 14/11/14.
 */
/**
 * Class which controls the tab collection
 */
var TabSet = Backbone.View.extend({
	className: 'tabs',
	queryCount: 0,
	dashCount: 0,

	events: {
		'click a.pager': 'togglePager' ,
		'click a.new' : 'new_tab'
	},

	_tabs: [],

	/**
	 * Render the tab containers
	 * @returns tab_container
	 */
	render: function() {
		$(this.el).html('<a href="#pager" class="pager sprite"></a><ul><li class="newtab"><a class="new">+&nbsp;&nbsp;</a></li></ul>')
			.appendTo($('#header'));
		this.content = $('<div id="tab_panel">').appendTo($('body'));
		this.pager = new TabPager({ tabset: this });
		return this;
	},

	/**
	 * Add a tab to the collection
	 * @param tab
	 */
	add: function(content, close) {
		// Add it to the set
		if (content.pluginName === 'dashboards') {
			this.dashCount++;
		}
		else {
			this.queryCount++;
		}

		var tab = new Tab({ content: content, close: close});
		this._tabs.push(tab);
		tab.parent = this;

		// Render it in the background, then select it
		tab.render().select();
		$(tab.el).insertBefore($(this.el).find('ul li.newtab'));

		// Trigger add event on session
		Saiku.session.trigger('tab:add', { tab: tab });
		this.pager.render();
		Saiku.i18n.translate();
		Saiku.session.trigger('workspace:toolbar:render',null);
		return tab;
	},

	find: function(id) {
		for (var i = 0, len = this._tabs.length; i < len; i++) {
			if (this._tabs[i].id == id) {
				return this._tabs[i];
			}
		}
		return null;
	},

	/**
	 * Select a tab, and move its contents to the tab panel
	 * @param tab
	 */
	select: function(tab) {
		// Clear selections
		$(this.el).find('li').removeClass('selected');

		Saiku.session.tabSelected = tab.id;
		Saiku.session.trigger('tab:select', { tab: tab });

		// Replace the contents of the tab panel with the new content

		this.content.children().detach();
		this.content.append($(tab.content.el));

	},

	/**
	 * Remove a tab from the collection
	 * @param tab
	 */
	remove: function(tab) {
		// Add another tab if the last one has been deleted
		if (this._tabs.length == 1) {
			//this.add(new Workspace());

		}

		for (var i = 0, len = this._tabs.length; i < len; i++) {
			if (this._tabs[i] == tab) {
				// Remove the element
				this._tabs.splice(i, 1);

				Saiku.session.trigger('tab:remove', { tab: tab });
				this.pager.render();
				// Select the previous, or first tab
				var next = this._tabs[i] ? i : (this._tabs.length - 1);

				if (this._tabs[next]) {
					this._tabs[next].select();
				}
			}
		}

		return true;
	},

	close_others: function(tab) {
		var index = _.indexOf(this._tabs, tab);
		this._tabs[index].select();

		// Remove tabs placed before and after selected tab
		var i = 0;
		while(1 < this._tabs.length){
			if (this._tabs[i] != tab)
				this._tabs[i].remove();
			else
				i++;
		}
	},

	close_all: function() {
		for (var i = 0, len = this._tabs.length; i < len; i++) {
			var otherTab = this._tabs[i];
			otherTab.remove();
		}
	},

	togglePager: function() {
		$(this.pager.el).toggle();
		return false;
	},

	new_tab: function() {
		this.add(new Workspace());
		var next = this._tabs.length - 1;
		this._tabs[next].select();
		return false;
	},

	duplicate: function(tab) {
		// Block UI to prevent other events
		Saiku.ui.block("Duplicating tab...");

		// Check for empty query
		if(tab.content.query){
			// For versions using Query2Resource
			this.add(new Workspace({
				query : new Query({
					json : JSON.stringify(tab.content.query.model)
				}, Settings.PARAMS),
				viewState : tab.content.viewState
			}));

		} else {
			this.add(new Workspace());
		}

		// Unblock UI and restore functionality
		Saiku.ui.unblock();
		return false;
	}
});
