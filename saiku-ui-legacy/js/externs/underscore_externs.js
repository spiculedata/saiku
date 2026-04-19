/**
 * Created by bugg on 14/11/14.
 */
/*
 * Copyright 2012 The Closure Compiler Authors.
 * Modified by Luke Rodgers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @fileoverview Externs for Underscore 1.3.3
 *
 * TODO: Wrapper objects.
 * TODO: _.bind - for some reason this plays up in practice.
 *
 * @see http://documentcloud.github.com/underscore/
 * @externs
 */

/**
 * @param {Object|number} obj
 * @return {!_}
 * @constructor
 */
function _(obj) {};

// Collection functions

/**
 * Object-style annotation
 * @param {Object|Array} obj
 * @param {Function} iterator
 * @param {Object=} opt_context
 */
_.each = function(obj, iterator, opt_context) {};
_.forEach = _.each;

/**
 * Functional-style annotation
 * @param {Function} iterator
 * @param {Object=} opt_context
 */
_.prototype.each = function(iterator, opt_context){};
_.prototype.forEach = _.prototype.each;

/**
 * Object-style annotation
 * @param {Object|Array} obj
 * @param {Function} iterator
 * @param {Object=} opt_context
 * @return {!Array|_}
 */
_.map = function(obj, iterator, opt_context) {};

/**
 * Functional-style annotation
 * @param {Function} iterator
 * @param {Object=} opt_context
 * @return {!Array|_}
 */
_.prototype.map = function(iterator, opt_context){};

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function} iterator
 * @param {Object=} opt_context
 * @return {!Array}
 */
_.collect = function(obj, iterator, opt_context) {};

/**
 * Functional-style notation
 * @param {Function} iterator
 * @param {Object=} opt_context
 * @return {!Array}
 */
_.prototype.collect = function(iterator, opt_context) {};

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function} iterator
 * @param {*=} memo
 * @param {Object=} opt_context
 * @return {!*}
 */
_.reduce = function(obj, iterator, memo, opt_context) {};
_.foldl = _.reduce;
_.inject = _.reduce;

/**
 * Functional-style notation
 * @param {Function} iterator
 * @param {*=} memo
 * @param {Object=} opt_context
 * @return {!*}
 */
_.prototype.reduce = function(iterator, memo, opt_context) {};
_.foldl = _.prototype.reduce;
_.inject = _.prototype.reduce;

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function} iterator
 * @param {*=} memo
 * @param {Object=} opt_context
 * @return {!*}
 */
_.reduceRight = function(obj, iterator, memo, opt_context) {};
_.foldr = _.reduceRight;

/**
 * Functional-style notation
 * @param {Function} iterator
 * @param {*=} memo
 * @param {Object=} opt_context
 * @return {!*}
 */
_.prototype.reduceRight = function(iterator, memo, opt_context) {};
_.prototype.foldr = _.prototype.reduceRight;

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function} iterator
 * @param {Object=} opt_context
 * @return {!*|_}
 */
_.find = function(obj, iterator, opt_context) {};
_.detect = _.find;

/**
 * Functional-style notation
 * @param {Function} iterator
 * @param {Object=} opt_context
 * @return {!*|_}
 */
_.prototype.find = function(iterator, opt_context) {};
_.prototype.detect = _.prototype.find;


/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function} iterator
 * @param {Object=} opt_context
 * @return {!Array|_}
 */
_.filter = function(obj, iterator, opt_context) {};
_.select = _.filter;

/**
 * Functional-style notation
 * @param {Function} iterator
 * @param {Object=} opt_context
 * @return {!Array|_}
 */
_.prototype.filter = function(iterator, opt_context) {};
_.prototype.select = _.prototype.filter;

/**
 * Object-style notation
 * @param {*} list
 * @param {Object} properties
 */
_.where = function(list, properties) {};

/**
 * Functional-style notation
 * @param {Object} properties
 */
_.prototype.where = function(properties) {};

/**
 * Object-style notation
 * @param {*} list
 * @param {Object} properties
 */
_.findWhere = function(list, properties) {};

/**
 * Functional-style notation
 * @param {Object} properties
 */
_.prototype.findWhere = function(properties) {};

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function} iterator
 * @param {*=} opt_context
 * @return {!Array|_}
 */
_.reject = function(obj, iterator, opt_context) {};

/**
 * Functional-style notation
 * @param {Function} iterator
 * @param {*=} opt_context
 * @return {!Array|_}
 */
_.prototype.reject = function(iterator, opt_context) {};

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function} iterator
 * @param {Object=} opt_context
 * @return {boolean}
 */
_.all = function(obj, iterator, opt_context) {};
_.every = _.all;

/**
 * Functional-style notation
 * @param {Function} iterator
 * @param {Object=} opt_context
 * @return {boolean}
 */
_.prototype.all = function(iterator, opt_context) {};
_.prototype.every = _.prototype.all;

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function=} iterator
 * @param {Object=} opt_context
 * @return {boolean|_}
 */
_.any = function(obj, iterator, opt_context) {};
_.some = _.any;

/**
 * Functional-style notation
 * @param {Function=} iterator
 * @param {Object=} opt_context
 * @return {boolean|_}
 */
_.prototype.any = function(iterator, opt_context) {};
_.prototype.some = _.prototype.any;

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {*} target
 * @return {boolean}
 */
_.include = function(obj, target) {};
_.contains = _.include;

/**
 * Functional-style notation
 * @param {*} target
 * @return {boolean}
 */
_.prototype.include = function(target) {};
_.prototype.contains = _.prototype.include;

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function|string} method
 * @param {...*} var_args
 */
_.invoke = function(obj, method, var_args) {};

/**
 * Functional-style notation
 * @param {Function|string} method
 * @param {...*} var_args
 */
_.prototype.invoke = function(method, var_args) {};

/**
 * Object-style notation
 * @param {Array.<Object>} obj
 * @param {string} key
 * @return {!Array}
 */
_.pluck = function(obj, key) {};

/**
 * @param {string} key
 * @return {!Array}
 */
_.prototype.pluck = function(key) {};

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function=} opt_iterator
 * @param {Object=} opt_context
 * @return {!*}
 */
_.max = function(obj, opt_iterator, opt_context) {};

/**
 * Functional-style notation
 * @param {Function=} opt_iterator
 * @param {Object=} opt_context
 * @return {!*}
 */
_.prototype.max = function(opt_iterator, opt_context) {};

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function=} opt_iterator
 * @param {Object=} opt_context
 * @return {!*}
 */
_.min = function(obj, opt_iterator, opt_context) {};

/**
 * Functional-style notation
 * @param {Function=} opt_iterator
 * @param {Object=} opt_context
 * @return {!*}
 */
_.prototype.min = function(opt_iterator, opt_context) {};

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {Function|string} iterator
 * @param {Object=} opt_context
 * @return {!Array|_}
 */
_.sortBy = function(obj, iterator, opt_context) {};

/**
 * Functional-style notation
 * @param {Function|string} iterator
 * @param {Object=} opt_context
 * @return {!Array|_}
 */
_.prototype.sortBy = function(iterator, opt_context) {};

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {number|string|Function=} iterator
 * @param {*=} context
 * @return {!Array.<!Array>|_}
 */
_.groupBy = function(obj, iterator, context) {};

/**
 * Functional-style notation
 * @param {number|string|Function=} iterator
 * @param {*=} context
 * @return {!Array.<!Array>|_}
 */
_.prototype.groupBy = function(iterator, context) {};

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @param {string|Function=} iterator
 * @param {*=} context
 * @return {!Array.<!Array>|_}
 */
_.countBy = function(obj, iterator, context) {};

/**
 * Functional-style notation
 * @param {string|Function=} iterator
 * @param {*=} context
 * @return {!Array.<!Array>|_}
 */
_.prototype.countBy = function(iterator, context) {};

/**
 * Object-style notation
 * @param {Array} list
 * @param {*} obj
 * @param {Function|string=} opt_iterator
 * @param {*=} context
 * @return {!number}
 */
_.sortedIndex = function(list, obj, opt_iterator, context) {};

/**
 * Functional-style notation
 * @param {*} obj
 * @param {Function|string=} opt_iterator
 * @param {*=} context
 * @return {!number}
 */
_.prototype.sortedIndex = function(obj, opt_iterator, context) {};

/**
 * Object-style notation
 * @param {Object|Array} obj
 * @return {!Array}
 */
_.shuffle = function(obj) {};

/**
 * Functional-style notation
 * @return {!Array}
 */
_.prototype.shuffle = function(obj) {};

/**
 * Object-style notation
 * @param {*} iterable
 * @return {!Array}
 */
_.toArray = function(iterable) {};

/**
 * Functional-style notation
 * @return {!Array}
 */
_.prototype.toArray = function() {};

/**
 * Object-style notation
 * @param {Object|Array|string} obj
 * @return {number}
 */
_.size = function(obj) {};

/**
 * Functional-style notation
 * @return {number}
 */
_.prototype.size = function() {};

// Array functions

/**
 * Object-style notation
 * @param {Array|Arguments} array
 * @param {number=} opt_n
 * @return {!*}
 */
_.first = function(array, opt_n) {};
_.head = _.first;
_.take = _.first;

/**
 * Functional-style notation
 * @param {number=} opt_n
 * @return {!*}
 */
_.prototype.first = function(opt_n) {};
_.prototype.head = _.prototype.first;
_.prototype.take = _.prototype.first;

/**
 * Object-style notation
 * @param {Array} array
 * @param {number=} opt_n
 * @return {!Array}
 */
_.initial = function(array, opt_n) {};

/**
 * Functional-style notation
 * @param {number=} opt_n
 * @return {!Array}
 */
_.prototype.initial = function(opt_n) {};

/**
 * Object-style notation
 * @param {Array|Arguments} array
 * @param {number=} opt_n
 * @return {*}
 */
_.last = function(array, opt_n) {};

/**
 * Functional-style notation
 * @param {number=} opt_n
 * @return {*}
 */
_.prototype.last = function(opt_n) {};

/**
 * Object-style notation
 * @param {Array} array
 * @param {number=} opt_n
 * @return {!*}
 */
_.rest = function(array, opt_n) {};
_.tail = _.rest;
_.drop = _.rest;

/**
 * Functional-style notation
 * @param {number=} opt_n
 * @return {!*}
 */
_.prototype.rest = function(opt_n) {};
_.prototype.tail = _.prototype.rest;
_.prototype.drop = _.prototype.rest;

/**
 * Object-style notation
 * @param {Array|Arguments} array
 * @return {!Array}
 */
_.compact = function(array) {};

/**
 * Functional-style notation
 * @return {!Array}
 */
_.prototype.compact = function() {};

/**
 * Object-style notation
 * @param {Array|Arguments} array
 * @param {boolean=} opt_shallow
 * @return {!Array}
 */
_.flatten = function(array, opt_shallow) {};

/**
 * Functional-style notation
 * @param {boolean=} opt_shallow
 * @return {!Array}
 */
_.prototype.flatten = function(opt_shallow) {};

/**
 * Object-style notation
 * @param {Array|Arguments} array
 * @param {...*} var_args
 * @return {!Array|_}
 */
_.without = function(array, var_args) {};

/**
 * Functional-style notation
 * @param {...*} var_args
 * @return {!Array|_}
 */
_.prototype.without = function(var_args) {};

/**
 * Object-style notation
 * @param {...Array|Arguments} arrays
 * @return {!Array}
 */
_.union = function(arrays) {};

/**
 * Functional-style notation
 * @return {!Array}
 */
_.prototype.union = function() {};

/**
 * Object-style notation
 * @param {...Array|Arguments} arrays
 * @return {!Array}
 */
_.intersection = function(arrays) {};
_.intersect = _.intersection;

/**
 * Functional-style notation
 * @param {...Array|Arguments} arrays
 * @return {!Array}
 */
_.prototype.intersection = function(arrays) {};
_.prototype.intersect = _.intersection;

/**
 * Object-style notation
 * @param {Array} array
 * @param {...*} others
 * @return {!Array}
 */
_.difference = function(array, others) {};

/**
 * Functional-style notation
 * @param {...*} others
 * @return {!Array}
 */
_.prototype.difference = function(others) {};

/**
 * Object-style notation
 * @param {Array|Arguments} array
 * @param {boolean|Function=} opt_isSorted
 * @param {Function=} opt_iterator
 * @param {Object=} context
 * @return {!Array}
 */
_.uniq = function(array, opt_isSorted, opt_iterator, context) {};
_.unique = _.uniq;

/**
 * Functional-style notation
 * @param {boolean|Function=} opt_isSorted
 * @param {Function=} opt_iterator
 * @param {Object=} context
 * @return {!Array}
 */
_.prototype.uniq = function(opt_isSorted, opt_iterator, context) {};
_.prototype.unique = _.prototype.uniq;

/**
 * Object-style notation
 * @param {...Array} arrays
 * @return {!Array}
 */
_.zip = function(arrays) {};

/**
 * Functional-style notation
 * @return {!Array}
 */
_.prototype.zip = function() {};

/**
 * Object-style notation
 * @param {Array} list
 * @param {Array=} values
 * @return {Object}
 */
_.object = function(list, values) {};

/**
 * Functional-style notation
 * @param {Array=} values
 * @return {Object}
 */
_.prototype.object = function(values) {};

/**
 * Object-style notation
 * @param {Array|Arguments} array
 * @param {*} item
 * @param {boolean|number=} opt_isSorted
 * @return {!number}
 */
_.indexOf = function(array, item, opt_isSorted) {};

/**
 * Functional-style notation
 * @param {*} item
 * @param {boolean|number=} opt_isSorted
 * @return {!number}
 */
_.prototype.indexOf = function(item, opt_isSorted) {};

/**
 * Object-style notation
 * @param {Array|Arguments} array
 * @param {*} value
 * @param {*=} fromIndex
 * @return {!number}
 */
_.lastIndexOf = function(array, value, fromIndex) {};

/**
 * Functional-style notation
 * @param {*} value
 * @param {*=} fromIndex
 * @return {!number}
 */
_.prototype.lastIndexOf = function(value, fromIndex) {};

/**
 * Object-style notation
 * @param {number} start
 * @param {number=} opt_stop
 * @param {number=} opt_step
 * @return {!Array.<number>}
 */
_.range = function(start, opt_stop, opt_step) {};

/**
 * Functional-style notation
 * @param {number=} opt_stop
 * @param {number=} opt_step
 * @return {!Array.<number>}
 */
_.prototype.range = function(opt_stop, opt_step) {};

// Function (ahem) functions

/**
 * Object-style notation
 * @param {Object} obj
 * @param {...string} methodNames
 */
_.bindAll = function(obj, methodNames) {};

/**
 * Functinoal-style notation
 * @param {...string} methodNames
 */
_.prototype.bindAll = function(methodNames) {};

/**
 * Object-style notation
 * @param {Function} func
 * @param {*} context
 * @param {...*} args
 */
_.bind = function(func, context, args) {};

/**
 * Functional-style notation
 * @param {*} context
 * @param {...*} args
 */
_.prototype.bind = function(context, args) {};

/**
 * Object-style notation
 * @param {Function} func
 * @param {...*} args
 */
_.partial = function(func, args) {};

/**
 * Functional-style notation
 * @param {...*} args
 */
_.prototype.partial = function(args) {};

/**
 * Object-style notation
 * @param {Function} func
 * @param {Function=} opt_hasher
 */
_.memoize = function(func, opt_hasher) {};

/**
 * Functional-style notation
 * @param {Function=} opt_hasher
 */
_.prototype.memoize = function(opt_hasher) {};

/**
 * Object-style notation
 * @param {Function} func
 * @param {number} wait
 * @param {...*} var_args
 */
_.delay = function(func, wait, var_args) {};

/**
 * Functional-style notation
 * @param {number} wait
 * @param {...*} var_args
 */
_.prototype.delay = function(wait, var_args) {};

/**
 * Object-style notation
 * @param {Function} func
 * @param {...*} opt_args
 */
_.defer = function(func, opt_args) {};

/**
 * Functional-style notation
 * @param {...*} opt_args
 */
_.prototype.defer = function(opt_args) {};

/**
 * Object-style notation
 * @param {Function} func
 * @param {number} wait
 * @param {Object=} options
 */
_.throttle = function(func, wait, options) {};

/**
 * Functional-style notation
 * @param {number} wait
 * @param {Object=} options
 */
_.prototype.throttle = function(wait, options) {};

/**
 * Object-style notation
 * @param {Function} func
 * @param {number} wait
 * @param {boolean=} immediate
 */
_.debounce = function(func, wait, immediate) {};

/**
 * Functional-style notation
 * @param {number} wait
 * @param {boolean=} immediate
 */
_.prototype.debounce = function(wait, immediate) {};

/**
 * Object-style notation
 * @param {Function} func
 */
_.once = function(func) {};

/**
 * Functional-style notation
 */
_.prototype.once = function() {};

/**
 * Object-style notation
 * @param {number} times
 * @param {Function} func
 */
_.after = function(times, func) {};

/**
 * Functional-style notation
 * @param {Function} func
 */
_.prototype.after = function(func) {};

/**
 * Object-style notation
 * @param {Function} func
 * @param {Function} wrapper
 * @return {!Function}
 */
_.wrap = function(func, wrapper) {};

/**
 * Functional-style notation
 * @param {Function} wrapper
 * @return {!Function}
 */
_.prototype.wrap = function(wrapper) {};

/**
 * Object-style notation
 * @param {...Function} funcs
 * @return {!Function}
 */
_.compose = function(funcs) {};

/**
 * Functional-style notation
 * @return {!Function}
 */
_.prototype.compose = function() {};

// Object functions

/**
 * Object-style notation
 * @param {Object} obj
 * @return {!Array.<string>}
 */
_.keys = function(obj) {};

/**
 * Functional-style notation
 * @return {!Array.<string>}
 */
_.prototype.keys = function() {};

/**
 * Object-style notation
 * @param {Object} obj
 * @return {!Array}
 */
_.values = function(obj) {};

/**
 * Functional-style notation
 * @return {!Array}
 */
_.prototype.values = function() {};

/**
 * Object-style notation
 * @param {Object} obj
 * @return {Array|_}
 */
_.pairs = function(obj) {};

/**
 * Functional-style notation
 * @return {Array|_}
 */
_.prototype.pairs = function() {};

/**
 * Object-style notation
 * @param {Object} obj
 * @return {!Array.<string>}
 */
_.functions = function(obj) {};

/**
 * Functional-style notation
 * @return {!Array.<string>}
 */
_.prototype.functions = function() {};

/**
 * Object-style notation
 * @param {Object} obj
 * @return {!Array.<string>}
 */
_.methods = function(obj) {};

/**
 * Functional-style notation
 * @return {!Array.<string>}
 */
_.prototype.methods = function() {};

/**
 * Object-style notation
 * @param {Object} obj
 * @param {...Object} objs
 */
_.extend = function(obj, objs) {};

/**
 * Functional-style notation
 * @param {...Object} objs
 */
_.prototype.extend = function(objs) {};

/**
 * Object-style notation
 * @param {Object} obj
 * @param {...*} keys
 */
_.pick = function(obj, keys) {};

/**
 * Functinoal-style notation
 * @param {...*} keys
 */
_.prototype.pick = function(keys) {};

/**
 * Object-style notation
 * @param {Object} obj
 * @param {...*} keys
 */
_.omit = function(obj, keys) {};

/**
 * Functinoal-style notation
 * @param {...*} keys
 */
_.prototype.omit = function(keys) {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {Object|_}
 */
_.invert = function(obj) {};

/**
 * Functional-style notation
 * @return {Object|_}
 */
_.prototype.invert = function() {};

/**
 * Object-style notation
 * @param {Object} obj
 * @param {...Object} defs
 */
_.defaults = function(obj, defs) {};

/**
 * Functional-style notation
 * @param {...Object} defs
 */
_.prototype.defaults = function(defs) {};

/**
 * Object-style notation
 * @param {Object} obj
 * @return {Object}
 */
_.clone = function(obj) {};

/**
 * Functional-style notation
 * @return {Object}
 */
_.prototype.clone = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @param {Function} interceptor
 * @return {Object} obj
 */
_.tap = function(obj, interceptor) {};

/**
 * Functional-style notation
 * @param {Function} interceptor
 * @return {Object} obj
 */
_.prototype.tap = function(interceptor) {};

/**
 * Object-style notation
 * @param {Object} obj
 * @param {string} key
 * @return {boolean}
 */
_.has = function(obj, key) {};

/**
 * Functional-style notation
 * @param {string} key
 * @return {boolean}
 */
_.prototype.has = function(key) {};

/**
 * Object-style notation
 * @param {*} a
 * @param {*} b
 * @return {boolean}
 */
_.isEqual = function(a, b) {};

/**
 * Functional-style notation
 * @param {*} b
 * @return {boolean}
 */
_.prototype.isEqual = function(b) {};

/**
 * Object-style notation
 * @param {Object|Array|string} obj
 * @return {boolean}
 */
_.isEmpty = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isEmpty = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isElement = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isElement = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isArray = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isArray = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isObject = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isObject = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isArguments = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isArguments = function(obj) {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isFunction = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isFunction = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isString = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isString = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isNumber = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isNumber = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isBoolean = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isBoolean = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isDate = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isDate = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isRegExp = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isRegExp = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isNaN = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isNaN = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isNull = function(obj) {};

/**
 * Functional-style notation
 * @return {boolean}
 */
_.prototype.isNull = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isUndefined = function(obj) {};

/**
 * Functional-style notation
 * @param {*=} obj
 * @return {boolean}
 */
_.prototype.isUndefined = function(obj) {};

// Utility functions

/**
 * @return {_}
 */
_.noConflict = function() {};

/**
 * Object-style notation
 * @param {*} value
 * @return {*}
 */
_.identity = function(value) {};

/**
 * Functional-style notation
 * @return {*}
 */
_.prototype.identity = function() {};

/**
 * Object-style notation
 * @param {number} n
 * @param {Function} iterator
 * @param {Object=} opt_context
 */
_.times = function (n, iterator, opt_context) {};

/**
 * Functional-style notation
 * @param {Function} iterator
 * @param {Object=} opt_context
 */
_.prototype.times = function (iterator, opt_context) {};

/**
 * Object-style notation
 * @param {Object} obj
 */
_.mixin = function(obj) {};

/**
 * Functional-style notation
 */
_.prototype.mixin = function() {};

/**
 * Object-styel notation
 * @param {number} min
 * @param {number=} max
 */
_.random = function(min, max) {};

/**
 * Functional-styel notation
 * @param {number} min
 * @param {number=} max
 */
_.prototype.random = function(min, max) {};

/**
 * Object-style notation
 * @param {string=} opt_prefix
 * @return {number|string}
 */
_.uniqueId = function(opt_prefix) {};

/**
 * Functional-style notation
 * @return {number|string}
 */
_.prototype.uniqueId = function() {};

/**
 * Object-style notation
 * @param {string|null} s
 * @return {string}
 */
_.escape = function(s) {};

/**
 * Functional-style notation
 * @return {string}
 */
_.prototype.escape = function() {};

/**
 * Object-style notation
 * @param {string} s
 * @return {string}
 */
_.unescape = function(s) {};

/**
 * Functional-style notation
 * @return {string}
 */
_.prototype.unescape = function() {};

/**
 * Object-style notation
 * @param {Object} obj
 * @param {string} property
 */
_.result = function(obj, property) {};

/**
 * Functional-style notation
 * @param {string} property
 */
_.prototype.result = function(property) {};

/**
 * Object-style notation
 * @param {string} text
 * @param {Object=} opt_data
 * @param {Object=} settings
 */
_.template = function(text, opt_data, settings) {};

/**
 * Functional-style notation
 * @param {Object=} opt_data
 * @param {Object=} settings
 */
_.prototype.template = function(opt_data, settings) {};

/**
 * Object-style notation
 * @param {Object} obj
 * @return {_}
 */
_.chain = function(obj) {};

/**
 * Functional-style notation
 * @return {_}
 */
_.prototype.chain = function() {};

/**
 * Object-style notation
 * @param {Object} obj
 * @return {*}
 */
_.value = function(obj) {};

/**
 * Functioanl-style notation
 * @return {*}
 */
_.prototype.value = function() {};

/**
 * Object-style notation
 * @param {*} obj
 * @return {boolean}
 */
_.isFinite = function(obj) {};

/**
 * Functioanl-style notation
 * @return {boolean}
 */
_.prototype.isFinite = function() {};
