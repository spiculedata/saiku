/*!
 * Copyright 2002 - 2013 Webdetails, a Pentaho company.  All rights reserved.
 *
 * This software was developed by Webdetails and is provided under the terms
 * of the Mozilla Public License, Version 2.0, or any later version. You may not use
 * this file except in compliance with the license. If you need a copy of the license,
 * please go to  http://mozilla.org/MPL/2.0/. The Initial Developer is Webdetails.
 *
 * Software distributed under the Mozilla Public License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to
 * the license for the specific language governing your rights and limitations.
 */

var def = function() {
    function globalSpace(name, space) {
        return globalNamespaces[name] = space;
    }
    function getNamespace(name, base) {
        var current = base || currentNamespace;
        if (name) {
            var parts = name.split("."), L = parts.length;
            if (L) {
                var part, i = 0;
                if (current === def.global) {
                    part = parts[0];
                    var globalNamespace = def.getOwn(globalNamespaces, part);
                    if (globalNamespace) {
                        current = globalNamespace;
                        i++;
                    }
                }
                for (;L > i; ) {
                    part = parts[i++];
                    current = current[part] || (current[part] = {});
                }
            }
        }
        return current;
    }
    function createSpace(name, base, definition) {
        if (def.fun.is(base)) {
            definition = base;
            base = null;
        }
        var namespace = getNamespace(name, base);
        if (definition) {
            namespaceStack.push(currentNamespace);
            try {
                definition(namespace);
            } finally {
                currentNamespace = namespaceStack.pop();
            }
        }
        return namespace;
    }
    function defineName(namespace, name, value) {
        !def.hasOwn(namespace, name) || def.fail.operationInvalid("Name '{0}' is already defined in namespace.", [ name ]);
        return namespace[name] = value;
    }
    function createMixin(protectNativeObject) {
        return function(instance) {
            return mixinMany(instance, arraySlice.call(arguments, 1), protectNativeObject);
        };
    }
    function mixinMany(instance, mixins, protectNativeObject) {
        for (var i = 0, L = mixins.length; L > i; i++) {
            var mixin = mixins[i];
            if (mixin) {
                mixin = def.object.as(mixin.prototype || mixin);
                mixin && mixinRecursive(instance, mixin, protectNativeObject);
            }
        }
        return instance;
    }
    function mixinRecursive(instance, mixin, protectNativeObject) {
        for (var p in mixin) mixinProp(instance, p, mixin[p], protectNativeObject);
    }
    function mixinProp(instance, p, vMixin, protectNativeObject) {
        if (void 0 !== vMixin) {
            var oMixin, oTo = def.object.asNative(instance[p]);
            if (oTo) {
                oMixin = def.object.as(vMixin);
                if (oMixin) {
                    objectHasOwn.call(instance, p) || (instance[p] = oTo = Object.create(oTo));
                    mixinRecursive(oTo, oMixin, protectNativeObject);
                } else instance[p] = vMixin;
            } else {
                oMixin = def.object.asNative(vMixin);
                oMixin && (vMixin = (protectNativeObject || Object.create)(oMixin));
                instance[p] = vMixin;
            }
        }
    }
    function createRecursive(instance) {
        for (var p in instance) {
            var vObj = def.object.asNative(instance[p]);
            vObj && createRecursive(instance[p] = Object.create(vObj));
        }
    }
    function query_nextMany() {
        for (;this._source.next(); ) {
            var manySource = this._selectMany ? this._selectMany.call(this._ctx, this._source.item, this._source.index) : this._source.item;
            if (null != manySource) {
                this._manySource = def.query(manySource);
                return !0;
            }
        }
    }
    var arraySlice = Array.prototype.slice;
    Object.keys || (Object.keys = function(o) {
        if (o !== Object(o)) throw new TypeError("Object.keys called on non-object");
        var ret = [];
        for (var p in o) Object.prototype.hasOwnProperty.call(o, p) && ret.push(p);
        return ret;
    });
    Object.create || (Object.create = function() {
        function create(baseProto) {
            Klass.prototype = baseProto || {};
            var instance = new Klass();
            Klass.prototype = proto;
            return instance;
        }
        var Klass = function() {}, proto = Klass.prototype;
        return create;
    }());
    Function.prototype.bind || (Function.prototype.bind = function(ctx) {
        var staticArgs = arraySlice.call(arguments, 1), fToBind = this;
        return function() {
            return fToBind.apply(ctx, staticArgs.concat(arraySlice.call(arguments)));
        };
    });
    this.JSON || (this.JSON = {});
    this.JSON.stringify || (this.JSON.stringify = function(t) {
        return "" + t;
    });
    var objectHasOwn = Object.prototype.hasOwnProperty, def = {
        global: this,
        get: function(o, p, dv) {
            var v;
            return o && null != (v = o[p]) ? v : dv;
        },
        gets: function(o, props) {
            return props.map(function(p) {
                return o[p];
            });
        },
        getPath: function(o, path, dv, create) {
            if (!o) return dv;
            if (null != path) {
                var parts = def.array.is(path) ? path : path.split("."), L = parts.length;
                if (L) for (var i = 0; L > i; ) {
                    var part = parts[i++], value = o[part];
                    if (null == value) {
                        if (!create) return dv;
                        value = o[part] = null == dv || isNaN(+dv) ? {} : [];
                    }
                    o = value;
                }
            }
            return o;
        },
        setPath: function(o, path, v) {
            if (o && null != path) {
                var parts = def.array.is(path) ? path : path.split(".");
                if (parts.length) {
                    var pLast = parts.pop();
                    o = def.getPath(o, parts, pLast, !0);
                    null != o && (o[pLast] = v);
                }
            }
            return o;
        },
        propGet: function(p, dv) {
            p = "" + p;
            return function(o) {
                return o ? o[p] : dv;
            };
        },
        getOwn: function(o, p, dv) {
            var v;
            return o && objectHasOwn.call(o, p) && null != (v = o[p]) ? v : dv;
        },
        hasOwn: function(o, p) {
            return !!o && objectHasOwn.call(o, p);
        },
        hasOwnProp: objectHasOwn,
        set: function(o) {
            for (var oo = o || {}, a = arguments, i = 1, A = a.length - 1; A > i; i += 2) oo[a[i]] = a[i + 1];
            return oo;
        },
        setDefaults: function(o, o2) {
            var p, oo = o || {}, a = arguments, A = a.length;
            if (2 === A && def.object.is(o2)) for (p in o2) null == oo[p] && (oo[p] = o2[p]); else {
                A--;
                for (var i = 1; A > i; i += 2) {
                    p = a[i];
                    null == oo[p] && (oo[p] = a[i + 1]);
                }
            }
            return oo;
        },
        setUDefaults: function(o, o2) {
            var p, oo = o || {}, a = arguments, A = a.length;
            if (2 === A && def.object.is(o2)) for (p in o2) void 0 === oo[p] && (oo[p] = o2[p]); else {
                A--;
                for (var i = 1; A > i; i += 2) {
                    p = a[i];
                    void 0 === oo[p] && (oo[p] = a[i + 1]);
                }
            }
            return oo;
        },
        eachOwn: function(o, fun, ctx) {
            if (o) for (var p in o) objectHasOwn.call(o, p) && fun.call(ctx, o[p], p, o);
        },
        each: function(o, fun, ctx) {
            if (o) for (var p in o) fun.call(ctx, o[p], p, o);
        },
        copyOwn: function(a, b) {
            var to, from;
            if (arguments.length >= 2) {
                to = a || {};
                from = b;
            } else {
                to = {};
                from = a;
            }
            if (from) for (var p in from) objectHasOwn.call(from, p) && (to[p] = from[p]);
            return to;
        },
        copy: function(a, b) {
            var to, from;
            if (arguments.length >= 2) {
                to = a || {};
                from = b;
            } else {
                to = {};
                from = a;
            }
            if (from) for (var p in from) to[p] = from[p];
            return to;
        },
        copyProps: function(a, b, props) {
            var to, from;
            if (arguments.length >= 3) {
                to = a || {};
                from = b;
            } else {
                to = {};
                from = a;
                props = b;
            }
            props && props.forEach(from ? function(p) {
                to[p] = from[p];
            } : function(p) {
                to[p] = void 0;
            });
            return to;
        },
        keys: function(o) {
            var keys = [];
            for (var p in o) keys.push(p);
            return keys;
        },
        values: function(o) {
            var values = [];
            for (var p in o) values.push(o[p]);
            return values;
        },
        uniqueIndex: function(o, key, ctx) {
            var index = {};
            for (var p in o) {
                var v = key ? key.call(ctx, o[p]) : o[p];
                null == v || objectHasOwn.call(index, v) || (index[v] = p);
            }
            return index;
        },
        ownKeys: Object.keys,
        own: function(o, f, ctx) {
            var keys = Object.keys(o);
            return keys.map(f ? function(key) {
                return f.call(ctx, o[key], key);
            } : function(key) {
                return o[key];
            });
        },
        scope: function(scopeFun, ctx) {
            return scopeFun.call(ctx);
        },
        bit: {
            set: function(bits, set, on) {
                return on || null == on ? bits | set : bits & ~set;
            }
        },
        compare: function(a, b) {
            return a === b ? 0 : a > b ? 1 : -1;
        },
        compareReverse: function(a, b) {
            return a === b ? 0 : a > b ? -1 : 1;
        },
        methodCaller: function(p, x) {
            return x ? function() {
                return x[p].apply(x, arguments);
            } : function() {
                return this[p].apply(this, arguments);
            };
        },
        identity: function(x) {
            return x;
        },
        add: function(a, b) {
            return a + b;
        },
        negate: function(f) {
            return function() {
                return !f.apply(this, arguments);
            };
        },
        sqr: function(v) {
            return v * v;
        },
        noop: function() {},
        retTrue: function() {
            return !0;
        },
        retFalse: function() {
            return !1;
        },
        number: {
            is: function(v) {
                return "number" == typeof v;
            },
            as: function(d, dv) {
                var v = parseFloat(d);
                return isNaN(v) ? dv || 0 : v;
            },
            to: function(d, dv) {
                var v = parseFloat(d);
                return isNaN(v) ? dv || 0 : v;
            }
        },
        array: {
            is: function(v) {
                return v instanceof Array;
            },
            isLike: function(v) {
                return v && null != v.length && "string" != typeof v;
            },
            as: function(thing) {
                return thing instanceof Array ? thing : null != thing ? [ thing ] : null;
            },
            to: function(thing) {
                return thing instanceof Array ? thing : null != thing ? [ thing ] : null;
            },
            lazy: function(scope, p, f, ctx) {
                return scope[p] || (scope[p] = f ? f.call(ctx, p) : []);
            },
            copy: function(al) {
                return arraySlice.apply(al, arraySlice.call(arguments, 1));
            }
        },
        object: {
            is: function(v) {
                return v && "object" == typeof v;
            },
            isNative: function(v) {
                return !!v && v.constructor === Object;
            },
            as: function(v) {
                return v && "object" == typeof v ? v : null;
            },
            asNative: function(v) {
                return v && v.constructor === Object ? v : null;
            },
            lazy: function(scope, p, f, ctx) {
                return scope[p] || (scope[p] = f ? f.call(ctx, p) : {});
            }
        },
        string: {
            is: function(v) {
                return "string" == typeof v;
            },
            to: function(v, ds) {
                return null != v ? "" + v : ds || "";
            },
            join: function(sep) {
                var v, v2, a = arguments, L = a.length;
                switch (L) {
                  case 3:
                    v = a[1];
                    v2 = a[2];
                    return null != v && "" !== v ? null != v2 && "" !== v2 ? "" + v + sep + ("" + v2) : "" + v : null != v2 && "" !== v2 ? "" + v2 : "";

                  case 2:
                    v = a[1];
                    return null != v ? "" + v : "";

                  case 1:
                  case 0:
                    return "";
                }
                for (var args = [], i = 1; L > i; i++) {
                    v = a[i];
                    null != v && "" !== v && args.push("" + v);
                }
                return args.join(sep);
            },
            padRight: function(s, n, p) {
                s || (s = "");
                null == p && (p = " ");
                var k = ~~((n - s.length) / p.length);
                return k > 0 ? s + new Array(k + 1).join(p) : s;
            }
        },
        fun: {
            is: function(v) {
                return "function" == typeof v;
            },
            as: function(v) {
                return "function" == typeof v ? v : null;
            },
            to: function(v) {
                return "function" == typeof v ? v : def.fun.constant(v);
            },
            constant: function(v) {
                return function() {
                    return v;
                };
            }
        },
        nullyTo: function(v, dv) {
            return null != v ? v : dv;
        },
        between: function(v, min, max) {
            return Math.max(min, Math.min(v, max));
        },
        nully: function(v) {
            return null == v;
        },
        notNully: function(v) {
            return null != v;
        },
        notUndef: function(v) {
            return void 0 !== v;
        },
        empty: function(v) {
            return null == v || "" === v;
        },
        notEmpty: function(v) {
            return null != v && "" !== v;
        },
        truthy: function(x) {
            return !!x;
        },
        falsy: function(x) {
            return !x;
        },
        firstUpperCase: function(s) {
            if (s) {
                var c = s.charAt(0), cU = c.toUpperCase();
                c !== cU && (s = cU + s.substr(1));
            }
            return s;
        },
        firstLowerCase: function(s) {
            if (s) {
                var c = s.charAt(0), cL = c.toLowerCase();
                c !== cL && (s = cL + s.substr(1));
            }
            return s;
        },
        format: function(mask, scope, ctx) {
            if (null == mask || "" === mask) return "";
            var isScopeFun = scope && def.fun.is(scope);
            return mask.replace(/(^|[^{])\{([^{}]+)\}/g, function($0, before, prop) {
                var value = scope ? isScopeFun ? scope.call(ctx, prop) : scope[prop] : null;
                return before + (null == value ? "" : String(value));
            });
        },
        destructuringTypeBind: function(types, values) {
            var T = types.length, result = new Array(T);
            if (T && values) {
                var V = values.length;
                if (V) {
                    var v = 0, t = 0;
                    do {
                        var value = values[v];
                        if (null == value || typeof value === types[t]) {
                            result[t] = value;
                            v++;
                        }
                        t++;
                    } while (T > t && V > v);
                }
            }
            return result;
        },
        error: function(error) {
            return error instanceof Error ? error : new Error(error);
        },
        fail: function(error) {
            throw def.error(error);
        },
        assert: function(msg, scope) {
            throw def.error.assertionFailed(msg, scope);
        }
    };
    def.ascending = def.compare;
    def.descending = def.compareReverse;
    var AL = def.array.like = def.copyOwn(function(v) {
        return AL.is(v) ? v : [ v ];
    }, {
        is: function(v) {
            return v && null != v.length && "string" != typeof v;
        },
        as: function(v) {
            return AL.is(v) ? v : null;
        }
    });
    AL.to = AL;
    def.lazy = def.object.lazy;
    def.shared = function() {
        function create(value) {
            function safe() {
                _channel = value;
            }
            return safe;
        }
        function opener(safe) {
            if (null != _channel) throw new Error("Access denied.");
            safe();
            var value;
            value = _channel;
            _channel = null;
            return value;
        }
        var _channel = null;
        opener.safe = create;
        return opener;
    };
    var errors = {
        operationInvalid: function(msg, scope) {
            return def.error(def.string.join(" ", "Invalid operation.", def.format(msg, scope)));
        },
        notImplemented: function() {
            return def.error("Not implemented.");
        },
        argumentRequired: function(name) {
            return def.error(def.format("Required argument '{0}'.", [ name ]));
        },
        argumentInvalid: function(name, msg, scope) {
            return def.error(def.string.join(" ", def.format("Invalid argument '{0}'.", [ name ]), def.format(msg, scope)));
        },
        assertionFailed: function(msg, scope) {
            return def.error(def.string.join(" ", "Assertion failed.", def.format(msg, scope)));
        }
    };
    def.copyOwn(def.error, errors);
    def.eachOwn(errors, function(errorFun, name) {
        def.fail[name] = function() {
            throw errorFun.apply(null, arguments);
        };
    });
    var currentNamespace = def, globalNamespaces = {}, namespaceStack = [];
    def.space = createSpace;
    def.globalSpace = globalSpace;
    def.mixin = createMixin(Object.create);
    def.copyOwn(def.mixin, {
        custom: createMixin,
        inherit: def.mixin,
        copy: createMixin(def.copy),
        share: createMixin(def.identity)
    });
    def.create = function() {
        var mixins = arraySlice.call(arguments), deep = !0, baseProto = mixins.shift();
        if ("boolean" == typeof baseProto) {
            deep = baseProto;
            baseProto = mixins.shift();
        }
        var instance = baseProto ? Object.create(baseProto) : {};
        deep && createRecursive(instance);
        if (mixins.length > 0) {
            mixins.unshift(instance);
            def.mixin.apply(def, mixins);
        }
        return instance;
    };
    def.scope(function() {
        function typeLocked() {
            return def.error.operationInvalid("Type is locked.");
        }
        function getStatic(state, p) {
            if (state) do {
                var v = def.getOwn(state.constructor, p);
                if (void 0 !== v) return v;
            } while (state = state.base);
        }
        function TypeName(full) {
            var parts;
            if (full) if (full instanceof Array) {
                parts = full;
                full = parts.join(".");
            } else parts = full.split(".");
            if (parts && parts.length > 1) {
                this.name = parts.pop();
                this.namespace = parts.join(".");
                this.namespaceParts = parts;
            } else {
                this.name = full || null;
                this.namespace = null;
                this.namespaceParts = [];
            }
        }
        function Method(spec) {
            this.fun = spec.as;
            this.isAbstract = !!spec.isAbstract;
        }
        function asMethod(fun) {
            if (fun) {
                if (def.fun.is(fun)) return new Method({
                    as: fun
                });
                if (fun instanceof Method) return fun;
                if (def.fun.is(fun.as)) return new Method(fun);
                if (fun.isAbstract) return new Method({
                    isAbstract: !0,
                    as: def.fail.notImplemented
                });
            }
            return null;
        }
        function method(fun) {
            return asMethod(fun) || def.fail.argumentInvalid("fun");
        }
        function rootType() {}
        function override(method, base) {
            return function() {
                var prevBase = rootProto.base;
                rootProto.base = base;
                try {
                    return method.apply(this, arguments);
                } finally {
                    rootProto.base = prevBase;
                }
            };
        }
        function overrideMethod(mname, method) {
            this[mname] = override(method, this[mname]);
            return this;
        }
        function toStringMethod() {
            return "" + this.constructor;
        }
        function inherits(type, base) {
            var proto = type.prototype = Object.create(base.prototype);
            proto.constructor = type;
            return proto;
        }
        function createConstructor(state) {
            function constructor() {
                if (S) for (var i = 0; steps[i].apply(this, arguments) !== !1 && ++i < S; ) ;
            }
            var S = 1, steps = [ function() {
                S = 0;
                if (state.initOrPost) {
                    steps.length = 0;
                    if (state.init) {
                        steps.push(state.init);
                        S++;
                    }
                    if (state.post) {
                        steps.push(state.post);
                        S++;
                    }
                    constructor.apply(this, arguments);
                    return !1;
                }
                steps = null;
            } ];
            return constructor;
        }
        function type() {
            var args = def.destructuringTypeBind(_typeFunArgTypes, arguments);
            return typeCore.apply(this, args);
        }
        function typeCore(name, baseType, space) {
            var baseState, typeName = new TypeName(name);
            if (baseType) {
                baseState = baseType.safe && shared(baseType.safe) || def.fail.operationInvalid('Invalid "foreign" base type.');
                baseState.locked = !0;
            } else {
                baseType = rootType;
                baseState = rootState;
            }
            var state = Object.create(baseState);
            state.locked = !1;
            state.base = baseState;
            state.methods = Object.create(baseState.methods);
            var constructor = createConstructor(state);
            def.copyOwn(constructor, typeProto);
            constructor.name = typeName.name;
            constructor.typeName = typeName;
            constructor.safe = shared.safe(state);
            constructor.toString = function() {
                return "" + this.typeName || "Anonymous type";
            };
            var proto = inherits(constructor, baseType);
            state.constructor = constructor;
            proto.override = overrideMethod;
            proto.toString = toStringMethod;
            typeName.name && defineName(def.space(typeName.namespace, space), typeName.name, constructor);
            return constructor;
        }
        var shared = def.shared(), typeProto = {
            init: function(init) {
                init || def.fail.argumentRequired("init");
                var state = shared(this.safe);
                !state.locked || def.fail(typeLocked());
                var baseInit = state.init;
                baseInit && (init = override(init, baseInit));
                state.init = init;
                state.initOrPost = !0;
                return this;
            },
            postInit: function(postInit) {
                postInit || def.fail.argumentRequired("postInit");
                var state = shared(this.safe);
                !state.locked || def.fail(typeLocked());
                var basePostInit = state.post;
                basePostInit && (postInit = override(postInit, basePostInit));
                state.post = postInit;
                state.initOrPost = !0;
                return this;
            },
            add: function(mixin) {
                var state = shared(this.safe);
                !state.locked || def.fail(typeLocked());
                var proto = this.prototype, baseState = state.base;
                def.each(mixin.prototype || mixin, function(value, p) {
                    switch (p) {
                      case "base":
                      case "constructor":
                        return;

                      case "toString":
                        if (value === toStringMethod) return;
                        break;

                      case "override":
                        if (value === overrideMethod) return;
                    }
                    if (value) {
                        var method = asMethod(value);
                        if (method) {
                            var baseMethod, bm = state.methods[p];
                            if (bm && bm instanceof Method) baseMethod = bm; else if (baseState) {
                                bm = baseState.methods[p];
                                bm && bm instanceof Method && (baseMethod = bm);
                            }
                            state.methods[p] = method;
                            baseMethod && (value = baseMethod.override(method));
                            proto[p] = value;
                            return;
                        }
                    }
                    mixinProp(proto, p, value, def.identity);
                });
                return this;
            },
            getStatic: function(p) {
                return getStatic(shared(this.safe), p);
            },
            addStatic: function(mixin) {
                var state = shared(this.safe);
                !state.locked || def.fail(typeLocked());
                for (var p in mixin) if ("prototype" !== p) {
                    var v2 = mixin[p], o2 = def.object.as(v2);
                    if (o2) {
                        var v1 = def.getOwn(this, p), v1Local = void 0 !== v1;
                        v1Local || (v1 = getStatic(state.base, p));
                        var o1 = def.object.asNative(v1);
                        if (o1) {
                            if (v1Local) {
                                def.mixin(v1, v2);
                                continue;
                            }
                            v2 = def.create(v1, v2);
                        }
                    }
                    this[p] = v2;
                }
                return this;
            }
        };
        TypeName.prototype.toString = function() {
            return def.string.join(".", this.namespace + "." + this.name);
        };
        def.copyOwn(Method.prototype, {
            override: function(method) {
                if (this.isAbstract) return method.fun;
                var fun2 = override(method.fun, this.fun);
                method.fun = fun2;
                return fun2;
            }
        });
        var rootProto = rootType.prototype;
        rootProto.base = void 0;
        var rootState = {
            locked: !0,
            init: void 0,
            postInit: void 0,
            initOrPost: !1,
            methods: {},
            constructor: rootType
        };
        rootType.safe = shared.safe(rootState);
        var _typeFunArgTypes = [ "string", "function", "object" ];
        def.type = type;
        def.method = method;
    });
    def.makeEnum = function(a) {
        var i = 1, e = {};
        a.forEach(function(p) {
            e[p] = i;
            i <<= 1;
        });
        return e;
    };
    def.copyOwn(def.array, {
        create: function(len, dv) {
            var a = len >= 0 ? new Array(len) : [];
            if (void 0 !== dv) for (var i = 0; len > i; i++) a[i] = dv;
            return a;
        },
        append: function(target, source, start) {
            null == start && (start = 0);
            for (var i = 0, L = source.length, T = target.length; L > i; i++) target[T + i] = source[start + i];
            return target;
        },
        appendMany: function(target) {
            var a = arguments, S = a.length;
            if (S > 1) for (var t = target.length, s = 1; S > s; s++) {
                var source = a[s];
                if (source) for (var i = 0, L = source.length; L > i; ) target[t++] = source[i++];
            }
            return target;
        },
        prepend: function(target, source, start) {
            null == start && (start = 0);
            for (var i = 0, L = source.length; L > i; i++) target.unshift(source[start + i]);
            return target;
        },
        removeAt: function(array, index) {
            return array.splice(index, 1)[0];
        },
        insertAt: function(array, index, elem) {
            array.splice(index, 0, elem);
            return array;
        },
        removeIf: function(array, p, x) {
            for (var i = 0, L = array.length; L > i; ) if (p.call(x, array[i], i)) {
                L--;
                array.splice(i, 1);
            } else i++;
            return array;
        },
        binarySearch: function(array, item, comparer, key) {
            comparer || (comparer = def.compare);
            for (var low = 0, high = array.length - 1; high >= low; ) {
                var mid = low + high >> 1, result = comparer(item, key ? key(array[mid]) : array[mid]);
                if (0 > result) high = mid - 1; else {
                    if (!(result > 0)) return mid;
                    low = mid + 1;
                }
            }
            return ~low;
        },
        insert: function(array, item, comparer) {
            var index = def.array.binarySearch(array, item, comparer);
            0 > index && array.splice(~index, 0, item);
            return index;
        },
        remove: function(array, item, comparer) {
            var index = def.array.binarySearch(array, item, comparer);
            return index >= 0 ? array.splice(index, 1)[0] : void 0;
        }
    });
    var nextGlobalId = 1, nextIdByScope = {};
    def.nextId = function(scope) {
        if (scope) {
            var nextId = def.getOwn(nextIdByScope, scope) || 1;
            nextIdByScope[scope] = nextId + 1;
            return nextId;
        }
        return nextGlobalId++;
    };
    def.type("Set").init(function(source, count) {
        this.source = source || {};
        this.count = source ? null != count ? count : def.ownKeys(source).length : 0;
    }).add({
        has: function(p) {
            return objectHasOwn.call(this.source, p);
        },
        add: function(p) {
            var source = this.source;
            if (!objectHasOwn.call(source, p)) {
                this.count++;
                source[p] = !0;
            }
            return this;
        },
        rem: function(p) {
            if (objectHasOwn.call(this.source, p)) {
                delete this.source[p];
                this.count--;
            }
            return this;
        },
        clear: function() {
            if (this.count) {
                this.source = {};
                this.count = 0;
            }
            return this;
        },
        members: function() {
            return def.ownKeys(this.source);
        }
    });
    def.type("Map").init(function(source, count) {
        this.source = source || {};
        this.count = source ? null != count ? count : def.ownKeys(source).length : 0;
    }).add({
        has: function(p) {
            return objectHasOwn.call(this.source, p);
        },
        get: function(p) {
            return objectHasOwn.call(this.source, p) ? this.source[p] : void 0;
        },
        set: function(p, v) {
            var source = this.source;
            objectHasOwn.call(source, p) || this.count++;
            source[p] = v;
            return this;
        },
        rem: function(p) {
            if (objectHasOwn.call(this.source, p)) {
                delete this.source[p];
                this.count--;
            }
            return this;
        },
        clear: function() {
            if (this.count) {
                this.source = {};
                this.count = 0;
            }
            return this;
        },
        copy: function(other) {
            def.eachOwn(other.source, function(value, p) {
                this.set(p, value);
            }, this);
        },
        values: function() {
            return def.own(this.source);
        },
        keys: function() {
            return def.ownKeys(this.source);
        },
        clone: function() {
            return new def.Map(def.copy(this.source), this.count);
        },
        symmetricDifference: function(other) {
            if (!this.count) return other.clone();
            if (!other.count) return this.clone();
            var result = {}, count = 0, as = this.source, bs = other.source;
            def.eachOwn(as, function(a, p) {
                if (!objectHasOwn.call(bs, p)) {
                    result[p] = a;
                    count++;
                }
            });
            def.eachOwn(bs, function(b, p) {
                if (!objectHasOwn.call(as, p)) {
                    result[p] = b;
                    count++;
                }
            });
            return new def.Map(result, count);
        },
        intersect: function(other, result) {
            result || (result = new def.Map());
            def.eachOwn(this.source, function(value, p) {
                other.has(p) && result.set(p, value);
            });
            return result;
        }
    });
    def.type("OrderedMap").init(function() {
        this._list = [];
        this._map = {};
    }).add({
        has: function(key) {
            return objectHasOwn.call(this._map, key);
        },
        count: function() {
            return this._list.length;
        },
        get: function(key) {
            var map = this._map;
            return objectHasOwn.call(map, key) ? map[key].value : void 0;
        },
        at: function(index) {
            var bucket = this._list[index];
            return bucket ? bucket.value : void 0;
        },
        add: function(key, v, index) {
            var map = this._map, bucket = objectHasOwn.call(map, key) && map[key];
            if (bucket) bucket.value !== v && (bucket.value = v); else {
                bucket = map[key] = {
                    key: key,
                    value: v
                };
                null == index ? this._list.push(bucket) : def.array.insertAt(this._list, index, bucket);
            }
            return this;
        },
        rem: function(key) {
            var map = this._map, bucket = objectHasOwn.call(map, key) && map[key];
            if (bucket) {
                var index = this._list.indexOf(bucket);
                this._list.splice(index, 1);
                delete this._map[key];
            }
            return this;
        },
        clear: function() {
            if (this._list.length) {
                this._map = {};
                this._list.length = 0;
            }
            return this;
        },
        keys: function() {
            return def.ownKeys(this._map);
        },
        forEach: function(fun, ctx) {
            return this._list.forEach(function(bucket) {
                fun.call(ctx, bucket.value, bucket.key);
            });
        }
    });
    def.html = {
        escape: function(str) {
            return def.string.to(str).replace(/&/gm, "&amp;").replace(/</gm, "&lt;").replace(/>/gm, "&gt;").replace(/"/gm, "&quot;");
        }
    };
    def.type("Query").add({
        index: -1,
        item: void 0,
        next: function() {
            var me = this, index = me.index;
            if (-2 === index) return !1;
            index++;
            if (!me._next(index)) {
                me._finish();
                return !1;
            }
            me.index = index;
            return !0;
        },
        _next: def.method({
            isAbstract: !0
        }),
        _finish: function() {
            var me = this;
            if (me.index > -2) {
                me.next = def.retFalse;
                me.index = -2;
                delete me.item;
            }
        },
        each: function(f, x) {
            for (var me = this; me.next(); ) if (f.call(x, me.item, me.index) === !1) return !0;
            return !1;
        },
        array: function() {
            for (var array = [], me = this; me.next(); ) array.push(me.item);
            return array;
        },
        sort: function(compare, by) {
            compare || (compare = def.compare);
            if (by) {
                var keyCompare = compare;
                compare = function(a, b) {
                    return keyCompare(by(a), by(b));
                };
            }
            var sorted = this.array().sort(compare);
            return new def.ArrayLikeQuery(sorted);
        },
        object: function(keyArgs) {
            for (var target = def.get(keyArgs, "target") || {}, nameFun = def.get(keyArgs, "name"), valueFun = def.get(keyArgs, "value"), ctx = def.get(keyArgs, "context"); this.next(); ) {
                var name = "" + (nameFun ? nameFun.call(ctx, this.item, this.index) : this.item);
                target[name] = valueFun ? valueFun.call(ctx, this.item, this.index) : this.item;
            }
            return target;
        },
        reduce: function(accumulator) {
            var result, i = 0;
            if (arguments.length < 2) {
                if (!this.next()) throw new TypeError("Length is 0 and no second argument");
                result = this.item;
            } else result = arguments[1];
            for (;this.next(); ) {
                result = accumulator(result, this.item, this.index);
                ++i;
            }
            return result;
        },
        count: function() {
            for (var count = 0; this.next(); ) count++;
            return count;
        },
        first: function(pred, ctx, dv) {
            for (;this.next(); ) if (!pred || pred.call(ctx, this.item, this.index)) {
                var item = this.item;
                this._finish();
                return item;
            }
            return dv;
        },
        last: function(pred, ctx, dv) {
            for (var theItem = dv; this.next(); ) (!pred || pred.call(ctx, this.item, this.index)) && (theItem = this.item);
            return theItem;
        },
        any: function(pred, ctx) {
            for (;this.next(); ) if (!pred || pred.call(ctx, this.item, this.index)) {
                this._finish();
                return !0;
            }
            return !1;
        },
        all: function(pred, ctx) {
            for (;this.next(); ) if (!pred.call(ctx, this.item, this.index)) {
                this._finish();
                return !1;
            }
            return !0;
        },
        min: function() {
            for (var min = null; this.next(); ) (null === min || this.item < min) && (min = this.item);
            return min;
        },
        max: function() {
            for (var max = null; this.next(); ) (null === max || this.item > max) && (max = this.item);
            return max;
        },
        range: function() {
            for (var min = null, max = null; this.next(); ) {
                var item = this.item;
                if (null === min) min = max = item; else {
                    min > item && (min = item);
                    item > max && (max = item);
                }
            }
            return null != min ? {
                min: min,
                max: max
            } : null;
        },
        multipleIndex: function(keyFun, ctx) {
            var keyIndex = {};
            this.each(function(item) {
                var key = keyFun ? keyFun.call(ctx, item) : item;
                if (null != key) {
                    var sameKeyItems = def.getOwn(keyIndex, key) || (keyIndex[key] = []);
                    sameKeyItems.push(item);
                }
            });
            return keyIndex;
        },
        uniqueIndex: function(keyFun, ctx) {
            var keyIndex = {};
            this.each(function(item) {
                var key = keyFun ? keyFun.call(ctx, item) : item;
                null == key || objectHasOwn.call(keyIndex, key) || (keyIndex[key] = item);
            });
            return keyIndex;
        },
        select: function(fun, ctx) {
            return new def.SelectQuery(this, fun, ctx);
        },
        prop: function(p) {
            return new def.SelectQuery(this, function(item) {
                return item ? item[p] : void 0;
            });
        },
        selectMany: function(fun, ctx) {
            return new def.SelectManyQuery(this, fun, ctx);
        },
        union: function() {
            var queries = def.array.append([ this ], arguments);
            return new def.SelectManyQuery(new def.ArrayLikeQuery(queries));
        },
        where: function(fun, ctx) {
            return new def.WhereQuery(this, fun, ctx);
        },
        distinct: function(fun, ctx) {
            return new def.DistinctQuery(this, fun, ctx);
        },
        skip: function(n) {
            return new def.SkipQuery(this, n);
        },
        take: function(n) {
            return 0 >= n ? new def.NullQuery() : isFinite(n) ? new def.TakeQuery(this, n) : this;
        },
        whayl: function(pred, ctx) {
            return new def.WhileQuery(this, pred, ctx);
        },
        reverse: function() {
            return new def.ReverseQuery(this);
        }
    });
    def.type("NullQuery", def.Query).add({
        next: def.retFalse
    });
    def.type("AdhocQuery", def.Query).init(function(next) {
        this._next = next;
    });
    def.type("ArrayLikeQuery", def.Query).init(function(list) {
        function arraLike_next() {
            for (;++i < I; ) if (objectHasOwn.call(list, i)) {
                me.index = i;
                me.item = list[i];
                return !0;
            }
            me._finish();
            return !1;
        }
        var me = this;
        def.array.isLike(list) || (list = [ list ]);
        me._list = list;
        me._count = list.length;
        var i = -1, I = list.length;
        me.next = arraLike_next;
    }).add({
        count: function() {
            var remaining = this._count;
            this.index >= 0 && (remaining -= this.index + 1);
            this._finish();
            return remaining;
        }
    });
    def.type("RangeQuery", def.Query).init(function(start, count, step) {
        this._index = start;
        this._count = count;
        this._step = null == step ? 1 : step;
    }).add({
        _next: function(nextIndex) {
            if (nextIndex < this._count) {
                this.item = this._index;
                this._index += this._step;
                return !0;
            }
        },
        count: function() {
            var remaining = this._count;
            this.index >= 0 && (remaining -= this.index + 1);
            this._finish();
            return remaining;
        }
    });
    def.type("WhereQuery", def.Query).init(function(source, p, x) {
        function where_next() {
            for (;source.next(); ) {
                var e = source.item;
                if (p.call(x, e, source.index)) {
                    me.item = e;
                    me.index = ++i;
                    return !0;
                }
            }
            me._finish();
            return !1;
        }
        var me = this, i = -1;
        me.next = where_next;
    });
    def.type("WhileQuery", def.Query).init(function(s, p, x) {
        function while_next() {
            if (s.next()) {
                var e = s.item;
                if (p.call(x, e, s.index)) {
                    me.item = e;
                    me.index = ++i;
                    return !0;
                }
            }
            me._finish();
            return !1;
        }
        var me = this, i = -1;
        me.next = while_next;
    });
    def.type("SelectQuery", def.Query).init(function(s, f, x) {
        function select_next() {
            if (s.next()) {
                me.item = f.call(x, s.item, s.index);
                me.index = ++i;
                return !0;
            }
            me._finish();
            return !1;
        }
        var me = this, i = -1;
        me.next = select_next;
    });
    def.type("SelectManyQuery", def.Query).init(function(source, selectMany, ctx) {
        this._selectMany = selectMany;
        this._ctx = ctx;
        this._source = source;
        this._manySource = null;
    }).add({
        _next: function() {
            for (;;) {
                if (this._manySource) {
                    if (this._manySource.next()) {
                        this.item = this._manySource.item;
                        return !0;
                    }
                    this._manySource = null;
                }
                if (!query_nextMany.call(this)) break;
            }
        }
    });
    def.type("DistinctQuery", def.Query).init(function(s, k, x) {
        function distinct_next() {
            for (;s.next(); ) {
                var e = s.item, v = k ? k.call(x, e, s.index) : e;
                if (null != v && !objectHasOwn.call(ks, v)) {
                    me.item = e;
                    me.index = ++i;
                    return ks[v] = !0;
                }
            }
            me._finish();
            return !1;
        }
        var me = this, i = -1, ks = {};
        me.next = distinct_next;
    });
    def.type("SkipQuery", def.Query).init(function(source, skip) {
        this._source = source;
        this._skip = skip;
    }).add({
        _next: function() {
            for (;this._source.next(); ) {
                if (!(this._skip > 0)) {
                    this.item = this._source.item;
                    return !0;
                }
                this._skip--;
            }
        }
    });
    def.type("TakeQuery", def.Query).init(function(source, take) {
        this._source = source;
        this._take = take;
    }).add({
        _next: function() {
            if (this._take > 0 && this._source.next()) {
                this._take--;
                this.item = this._source.item;
                return !0;
            }
        }
    });
    def.type("ReverseQuery", def.Query).init(function(source) {
        this._source = source;
    }).add({
        _next: function(nextIndex) {
            if (!nextIndex) {
                this._source instanceof def.Query && (this._source = this._source instanceof def.ArrayLikeQuery ? this._source._list : this._source.array());
                this._count = this._source.length;
            }
            var count = this._count;
            if (count > nextIndex) {
                for (var index = count - nextIndex - 1, source = this._source; !objectHasOwn.call(source, index); ) {
                    if (--index < 0) return !1;
                    this._count--;
                }
                this.item = source[index];
                return !0;
            }
        }
    });
    def.query = function(q) {
        return void 0 === q ? new def.NullQuery() : q instanceof def.Query ? q : def.fun.is(q) ? new def.AdhocQuery(q) : new def.ArrayLikeQuery(q);
    };
    def.range = function(start, count, step) {
        return new def.RangeQuery(start, count, step);
    };
    currentNamespace = def.global;
    return def;
}();