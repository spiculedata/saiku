/**
 * Created by bugg on 14/11/14.
 */
/**
 * @fileoverview Externs for backbone-0.9.2.js
 *
 * built with http://www.dotnetwise.com/Code/Externs/index.html
 * see also: http://blog.dotnetwise.com/2009/11/closure-compiler-externs-extractor.html
 * via: http://code.google.com/p/closure-compiler/wiki/FAQ#How_do_I_write_an_externs_file?
 *
 * Note: when building via that page, you first need to load in underscrore.js, as that's a dependency.
 *       also, after running the extern for Backbone, you need to manually run it for:
 *       Backbone.Model.prototype, Backbone.Collection.prototype, Backbone.Router.prototype,
 *       Backbone.History.prototype, and Backbone.View.prototype
 *       because these objects are modified using _.extend(Backbone.Model.prototype ...)
 * @see http://documentcloud.github.com/backbone/
 * @externs
 */

var Backbone = {
	"VERSION": {},
	"setDomLibrary": function () {},
	"noConflict": function () {},
	"emulateHTTP": {},
	"emulateJSON": {}
};

/**
 * @typedef {Object}
 */
Backbone.Events;

/**
 * @param {string} event
 * @param {Function} callback
 * @param {Object=} context
 */
Backbone.Events.on = function(event, callback, context) {};
Backbone.Events.bind = Backbone.Events.on;

/**
 * @param {string=} event
 * @param {Function=} callback
 * @param {Object=} context
 */
Backbone.Events.off = function(event, callback, context) {};
Backbone.Events.unbind = Backbone.Events.off;

/**
 * @param {string} event
 * @param {...*} args
 */
Backbone.Events.trigger = function(event, args) {};

/**
 * @param {string} event
 * @param {Function} callback
 * @param {Object=} context
 */
Backbone.Events.once = function(event, callback, context) {};

/**
 * @param {Object} other
 * @param {string} event
 * @param {Function} callback
 */
Backbone.Events.listenTo = function(other, event, callback) {};

/**
 * @param {Object} other
 * @param {string} event
 * @param {Function} callback
 */
Backbone.Events.listenToOnce = function(other, event, callback) {};

/**
 * @param {Object=} other
 * @param {string=} event
 * @param {Function=} callback
 */
Backbone.Events.stopListening = function(other, event, callback) {};

/**
 * @param {Function} onError
 * @param {Backbone.Model} originalModel
 * @param {Object} options
 */
Backbone.wrapError = function(onError, originalModel, options){};

/**
 * @param {string=} method
 * @param {Backbone.Model=} model
 * @param {Object=} options
 */
Backbone.sync = function(method, model, options){};

/**
 * @extends {Backbone.Events}
 * @constructor
 * @param {Object=} attrs
 * @param {Object=} options
 */
Backbone.Model = function(attrs, options) {};

/** @type {*} */
Backbone.Model.prototype.validationError;

Backbone.Model.prototype.on = Backbone.Events.on;
Backbone.Model.prototype.off = Backbone.Events.off;
Backbone.Model.prototype.trigger = Backbone.Events.trigger;
Backbone.Model.prototype.bind = Backbone.Events.on;
Backbone.Model.prototype.unbind = Backbone.Events.on;
Backbone.Model.prototype.once = Backbone.Events.once;
Backbone.Model.prototype.listenTo = Backbone.Events.listenTo;
Backbone.Model.prototype.listenToOnce = Backbone.Events.listenToOnce;
Backbone.Model.prototype.stopListening = Backbone.Events.stopListening;

Backbone.Model.prototype.keys = _.prototype.keys;
Backbone.Model.prototype.values = _.prototype.values;
Backbone.Model.prototype.pairs = _.prototype.pairs;
Backbone.Model.prototype.invert = _.prototype.invert;
Backbone.Model.prototype.pick = _.prototype.pick;
Backbone.Model.prototype.omit = _.prototype.omit;

/**
 * @param {Object} properties
 * @param {Object=} classProperties
 */
Backbone.Model.prototype.extend = function(properties, classProperties) {};

/**
 * @param {...*} config
 */
Backbone.Model.prototype.initialize = function(config) {};

/**
 * @param {string} attribute
 * @return {*}
 */
Backbone.Model.prototype.get = function(attribute) {};

/**
 * @param {string|Object} key
 * @param {*=} value
 * @param {Object=} options
 * @return {Backbone.Model}
 */
Backbone.Model.prototype.set = function(key, value, options) {};

/**
 * @param {string} attribute
 * @return {string}
 */
Backbone.Model.prototype.escape = function(attribute) {};

/**
 * @param {string} attribute
 * @return {boolean}
 */
Backbone.Model.prototype.has = function(attribute) {};

/**
 * @param {string} attribute
 * @param {Object=} options
 * @return {Backbone.Model}
 */
Backbone.Model.prototype.unset = function(attribute, options) {};

/**
 * @param {Object=} options
 */
Backbone.Model.prototype.clear = function(options) {};

/** @type {number} */
Backbone.Model.prototype.id;

/** @type {string} */
Backbone.Model.prototype.idAttribute;

/** @type {number} */
Backbone.Model.prototype.cid;

/** @type {Object} */
Backbone.Model.prototype.attributes;

/** @type {Object} */
Backbone.Model.prototype.changed;

/** @type {Object|Function} */
Backbone.Model.prototype.defaults;

/** @type {Object} */
Backbone.Model.prototype.collection;

/**
 * @param {Object=} options
 * @return {Object}
 */
Backbone.Model.prototype.toJSON = function(options) {};

/**
 * @param {Object=} options
 * @return {Object} returns jQuery xhr
 */
Backbone.Model.prototype.fetch = function(options) {};

/**
 * @param {string|Object=} key
 * @param {*=} value
 * @param {Object=} options
 * @return {boolean|Object}
 */
Backbone.Model.prototype.save = function(key, value, options) {};

/**
 * @param {Object=} options
 * @return {boolean|Object}
 */
Backbone.Model.prototype.destroy = function(options) {};

/**
 * @param {Object} attributes
 */
Backbone.Model.prototype.validate = function(attributes) {};

/**
 * @return {boolean}
 */
Backbone.Model.prototype.isValid = function() {};

/**
 * @return {string}
 */
Backbone.Model.prototype.url = function() {};

/** @type {string|Function} */
Backbone.Model.urlRoot;

/**
 * @param {Object} resp
 * @param {Object} xhr
 * @return {Object}
 */
Backbone.Model.prototype.parse = function(resp, xhr) {};

/**
 * @return {Backbone.Model}
 */
Backbone.Model.prototype.clone = function() {};

/**
 * @return {boolean}
 */
Backbone.Model.prototype.isNew = function() {};

/**
 * @param {Object=} options
 * @return {Backbone.Model}
 */
Backbone.Model.prototype.change = function(options) {};

/**
 * @param {string|number=} attr
 * @return {boolean}
 */
Backbone.Model.prototype.hasChanged = function(attr) {};

/**
 * @param {Object=} attributes
 * @return {Object}
 */
Backbone.Model.prototype.changedAttributes = function(attributes) {};

/**
 * @param {string|number} attribute
 * @return {*}
 */
Backbone.Model.prototype.previous = function(attribute) {};

/**
 * @return {Object}
 */
Backbone.Model.prototype.previousAttributes = function() {};

/**
 * @extends {Backbone.Events}
 * @constructor
 * @param {Object|Backbone.Model|Array.<Object>=} models
 * @param {Object=} config
 */
Backbone.Collection = function(models, config) {};

/** @type {Object} */
Backbone.Collection.prototype.syncArgs;

/** @type {Array} */
Backbone.Collection.prototype.previousModels;

/**
 * @param {...*} args
 */
Backbone.Collection.prototype.initialize = function(args) {};

/**
 * @param {Object=} options
 */
Backbone.Collection.prototype.sort = function(options) {};

/**
 * @param {Object} resp
 * @param {Object} xhr
 * @return {Object}
 */
Backbone.Collection.prototype.parse = function(resp, xhr) {};

Backbone.Collection.prototype.map = _.prototype.map;
Backbone.Collection.prototype.collect = _.prototype.collect;
Backbone.Collection.prototype.reduce = _.prototype.reduce;
Backbone.Collection.prototype.foldl = _.prototype.reduce;
Backbone.Collection.prototype.inject = _.prototype.reduce;
Backbone.Collection.prototype.reduceRight = _.prototype.reduceRight;
Backbone.Collection.prototype.foldr = _.prototype.reduceRight;
Backbone.Collection.prototype.each = _.prototype.each;
Backbone.Collection.prototype.forEach = _.prototype.forEach;
Backbone.Collection.prototype.find = _.prototype.find;
Backbone.Collection.prototype.detect = _.prototype.detect;
Backbone.Collection.prototype.filter = _.prototype.filter;
Backbone.Collection.prototype.select = _.prototype.select;
Backbone.Collection.prototype.reject = _.prototype.reject;
Backbone.Collection.prototype.every = _.prototype.every;
Backbone.Collection.prototype.all = _.prototype.all;
Backbone.Collection.prototype.any = _.prototype.any;
Backbone.Collection.prototype.some = _.prototype.some;
Backbone.Collection.prototype.include = _.prototype.include;
Backbone.Collection.prototype.contains = _.prototype.contains;
Backbone.Collection.prototype.invoke = _.prototype.invoke;
Backbone.Collection.prototype.min = _.prototype.min;
Backbone.Collection.prototype.max = _.prototype.max;
Backbone.Collection.prototype.chain = _.prototype.chain;
Backbone.Collection.prototype.toArray = _.prototype.toArray;
Backbone.Collection.prototype.size = _.prototype.size;
Backbone.Collection.prototype.first = _.prototype.first;
Backbone.Collection.prototype.head = _.prototype.first;
Backbone.Collection.prototype.take = _.prototype.first;
Backbone.Collection.prototype.initial = _.prototype.initial;
Backbone.Collection.prototype.rest = _.prototype.rest;
Backbone.Collection.prototype.tail = _.prototype.rest;
Backbone.Collection.prototype.drop = _.prototype.rest;
Backbone.Collection.prototype.last = _.prototype.last;
Backbone.Collection.prototype.without = _.prototype.without;
Backbone.Collection.prototype.difference = _.prototype.without;
Backbone.Collection.prototype.shuffle = _.prototype.shuffle;
Backbone.Collection.prototype.lastIndexOf = _.prototype.lastIndexOf;
Backbone.Collection.prototype.isEmpty = _.prototype.isEmpty;

/**
 * @param {Object=} options
 * @return {Array.<Object>}
 */
Backbone.Collection.prototype.toJSON = function(options) {};

Backbone.Collection.prototype.on = Backbone.Events.on;
Backbone.Collection.prototype.off = Backbone.Events.off;
Backbone.Collection.prototype.trigger = Backbone.Events.trigger;
Backbone.Collection.prototype.bind = Backbone.Events.on;
Backbone.Collection.prototype.unbind = Backbone.Events.on;
Backbone.Collection.prototype.once = Backbone.Events.once;
Backbone.Collection.prototype.listenTo = Backbone.Events.listenTo;
Backbone.Collection.prototype.listenToOnce = Backbone.Events.listenToOnce;
Backbone.Collection.prototype.stopListening = Backbone.Events.stopListening;

/** @type {number} */
Backbone.Collection.prototype.length;

/** @type {Array} */
Backbone.Collection.prototype.models;

Backbone.Collection.prototype.on = Backbone.Events.on;
Backbone.Collection.prototype.off = Backbone.Events.off;
Backbone.Collection.prototype.bind = Backbone.Events.on;
Backbone.Collection.prototype.unbind = Backbone.Events.off;

/** @type {Backbone.Model} */
Backbone.Collection.model;

/**
 * @param {Object=} options
 * @return {Object} returns jQuery xhr
 */
Backbone.Collection.prototype.fetch = function(options) {};

/**
 * @param {Object|Array.<Object>} models
 * @param {Object=} options
 * @return {Object} returns jQuery xhr
 */
Backbone.Collection.prototype.update = function(models, options) {};

/**
 * @param {string|number} index
 * @return {Backbone.Model|undefined}
 */
Backbone.Collection.prototype.at = function(index) {};

/**
 * @param {string|number|Backbone.Model} id
 * @return {Backbone.Model|undefined}
 */
Backbone.Collection.prototype.get = function(id) {};

/**
 * @param {string|number} cid
 * @return {Backbone.Model|undefined}
 */
Backbone.Collection.prototype.getByCid = function(cid) {};

/**
 * @param {Object|Backbone.Model|Array.<Object>} models
 * @param {Object=} options
 */
Backbone.Collection.prototype.add = function(models, options){};

/**
 * @param {Object|Backbone.Model|Array.<Object>} models
 * @param {Object=} options
 */
Backbone.Collection.prototype.remove = function(models, options){};

/**
 * @param {Object|Backbone.Model} model
 * @param {Object=} options
 */
Backbone.Collection.prototype.create = function(model, options){};

/**
 * @param {Array|Object=} models
 * @param {Object=} options
 */
Backbone.Collection.prototype.reset = function(models, options){};

/**
 * @param {Array|Object=} models
 * @param {Object=} options
 */
Backbone.Collection.prototype.set = function(models, options){};

/**
 * @param {string} attr
 * @return {Array}
 */
Backbone.Collection.prototype.pluck = function(attr){};

/**
 * @param {*} value
 * @param {Object=} options
 * @return {number|undefined}
 */
Backbone.Collection.prototype.indexOf = function(value, options){};

/**
 * @param {Object=} options
 * @return {Backbone.Model|undefined}
 */
Backbone.Collection.prototype.shift = function(options){};

/**
 * @param {Backbone.Model|Object} model
 * @param {Object=} options
 */
Backbone.Collection.prototype.unshift = function(model, options){};

/**
 * @param {Object=} options
 */
Backbone.Collection.prototype.pop = function(options){};

/**
 * @param {Backbone.Model|Object} model
 * @param {Object=} options
 */
Backbone.Collection.prototype.push = function(model, options){};

/**
 * @param {number=} begin
 * @param {number=} end
 */
Backbone.Collection.prototype.slice = function(begin, end) {};

/**
 * @param {Object} attrs
 * @param {boolean=} first
 * @return {Array}
 */
Backbone.Collection.prototype.where = function(attrs, first) {};

/**
 * @param {Object} attrs
 * @return {Object}
 */
Backbone.Collection.prototype.findWhere = function(attrs) {};

/**
 * @param {Function|string} iterator
 * @param {Object=} context
 */
Backbone.Collection.prototype.sortBy = function(iterator, context) {};

/**
 * @param {Function|string} iterator
 * @param {Object=} context
 */
Backbone.Collection.prototype.groupBy = function(iterator, context) {};

Backbone.Router.prototype = {
	"route": function () {},
	"_bindRoutes": function () {},
	"_routeToRegExp": function () {},
	"_extractParameters": function () {}
};

/**
 * @param {...*} args
 */
Backbone.Router.prototype.initialize = function(args) {};

Backbone.Router.prototype.on = Backbone.Events.on;
Backbone.Router.prototype.off = Backbone.Events.off;
Backbone.Router.prototype.trigger = Backbone.Events.trigger;
Backbone.Router.prototype.bind = Backbone.Events.on;
Backbone.Router.prototype.unbind = Backbone.Events.on;
Backbone.Router.prototype.once = Backbone.Events.once;
Backbone.Router.prototype.listenTo = Backbone.Events.listenTo;
Backbone.Router.prototype.listenToOnce = Backbone.Events.listenToOnce;
Backbone.Router.prototype.stopListening = Backbone.Events.stopListening;

/**
 * @constructor
 */
Backbone.History = function() {};

Backbone.History.prototype.on = Backbone.Events.on;
Backbone.History.prototype.off = Backbone.Events.off;
Backbone.History.prototype.trigger = Backbone.Events.trigger;
Backbone.History.prototype.bind = Backbone.Events.on;
Backbone.History.prototype.unbind = Backbone.Events.on;
Backbone.History.prototype.once = Backbone.Events.once;
Backbone.History.prototype.listenTo = Backbone.Events.listenTo;
Backbone.History.prototype.listenToOnce = Backbone.Events.listenToOnce;
Backbone.History.prototype.stopListening = Backbone.Events.stopListening;

/** @type {boolean} */
Backbone.History.started;

/** @type {boolean} */
Backbone.History.prototype.started;

/**
 * @param {Object=} options
 */
Backbone.History.prototype.start = function(options) {};

/**
 * @param {Object=} options
 */
Backbone.History.prototype.stop = function(options) {};

/**
 * @param {Object=} e
 */
Backbone.History.prototype.checkUrl = function(e) {};

/**
 * @param {string} fragment
 * @param {boolean|Object=} options
 */
Backbone.History.prototype.navigate = function(fragment, options) {};

/**
 * @param {string=} fragment
 * @param {boolean=} forcePushState
 */
Backbone.History.prototype.getFragment = function(fragment, forcePushState) {};

/**
 * @param {...*} args
 * @constructor
 */
Backbone.View = function(args){};

Backbone.View.prototype.on = Backbone.Events.on;
Backbone.View.prototype.off = Backbone.Events.off;
Backbone.View.prototype.trigger = Backbone.Events.trigger;
Backbone.View.prototype.bind = Backbone.Events.on;
Backbone.View.prototype.unbind = Backbone.Events.on;
Backbone.View.prototype.once = Backbone.Events.once;
Backbone.View.prototype.listenTo = Backbone.Events.listenTo;
Backbone.View.prototype.listenToOnce = Backbone.Events.listenToOnce;
Backbone.View.prototype.stopListening = Backbone.Events.stopListening;

/** @type {string} */
Backbone.View.prototype.tagName;
/** @type {{id: string, className: string, tagName: string}} */
Backbone.View.prototype.options;
/**
 * @param {...*} args
 */
Backbone.View.prototype.initialize = function(args) {};
/**
 * @return {Backbone.View}
 */
Backbone.View.prototype.render = function(){};
/** @type {Element} */
Backbone.View.prototype.el;
/** @type {jQuery} */
Backbone.View.prototype.$el;
Backbone.View.prototype.remove = function(){};
/**
 * @param {string} tagName
 * @param {Object=} attributes
 * @param {string=} content
 */
Backbone.View.prototype.make = function(tagName, attributes, content){};
Backbone.View.prototype.remove = function(){};
/**
 * @param {Object=} events
 */
Backbone.View.prototype.delegateEvents = function(events){};
/**
 * @param {Object=} events
 */
Backbone.View.prototype.undelegateEvents = function(events){};

/**
 * @param {string|Element|jQuery} element
 * @param {boolean=} delegate
 */
Backbone.View.prototype.setElement = function(element, delegate){};

/**
 * @param {string} selector
 */
Backbone.View.prototype.$ = function(selector){};
