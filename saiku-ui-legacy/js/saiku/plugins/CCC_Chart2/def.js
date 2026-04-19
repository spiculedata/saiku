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
    function def(qname, value, space) {
        if (qname && !(qname instanceof def.QualifiedName)) {
            var t = typeof qname;
            if ("object" === t) {
                for (var p in qname) def_1(p, qname[p], value);
                return def;
            }
            if ("array" === t) {
                for (var p in qname) def_1(qname[p], value);
                return def;
            }
        }
        def_1(qname, value, space);
        return value;
    }
    function def_1(qname, value, space) {
        qname = def.qualName(qname);
        space = def.space(qname.namespace, space);
        if (qname.name) {
            space[qname.name] = value;
            value instanceof Object && def.qualNameOf(value, qname);
        }
    }
    function def_syncLog(level) {
        def.log = def.logger("[DEF]");
        def_onDebugChanged && def_onDebugChanged.forEach(function(f) {
            f(level);
        });
    }
    function def_debugLevel() {
        return def.debug;
    }
    function def_loggerReal(prompt, target) {
        prompt = def_evalPrompt(prompt, target);
        var logger = def_createLogFn("log", prompt);
        logger.log = logger;
        logger.level = def_debugLevel;
        def_logNames.forEach(function(name) {
            logger[name] = def_createLogFn(def_logNamesMap[name] || name, prompt);
        });
        return logger;
    }
    function def_evalPrompt(prompt, x) {
        return prompt && (def.fun.is(prompt) ? prompt.call(x) : prompt);
    }
    function def_loggerAutoEnableOnce(target, loggerProp, prompt) {
        var baseLogger = function(name) {
            if (def.debug > 1) {
                var logger = target[loggerProp || "log"] = def_loggerReal(prompt, target);
                logger[name || "log"].apply(logger, A_slice.call(arguments, 1));
            } else "error" === name && def_logError.apply(null, def.array.append([ def_evalPrompt(prompt, target) ], arguments, 1));
        }, logger = baseLogger.bind("log");
        logger.log = logger;
        logger.level = def_debugLevel;
        def_logNames.forEach(function(name) {
            logger[name] = baseLogger.bind(name);
        });
        return logger;
    }
    function def_loggerDisabled() {
        function logger() {}
        logger.log = logger;
        logger.level = def_debugLevel;
        def_logNames.forEach(function(name) {
            logger[name] = logger;
        });
        return logger;
    }
    function def_logError(prompt, e, s) {
        e && "object" == typeof e && e.message && (e = e.message);
        e = (prompt ? prompt + ": " : "") + def.nullyTo(e, "") + (s ? " " + s : "");
        throw new Error(e);
    }
    function def_createLogFn(name, prompt) {
        var fun, c = console, m = c[name] || c.log;
        if (m) {
            var mask = prompt ? prompt + ": %s" : "%s";
            if (def.fun.is(m)) fun = m.bind(c, mask); else {
                var apply = Function.prototype.apply;
                fun = function() {
                    apply.call(m, c, def.array.append([ mask ], arguments));
                };
            }
        }
        return fun;
    }
    function def_priv_key() {
        function newSafe(value) {
            function safe() {
                _channel = value;
            }
            safe.toString = def_priv_safeToString;
            return safe;
        }
        function key(safe) {
            if (void 0 !== _channel) throw new Error("Access denied.");
            var secret = (safe(), _channel);
            return _channel = void 0, secret;
        }
        var _channel;
        key.safe = newSafe;
        key.property = def_priv_propCreate;
        return key;
    }
    function def_priv_safeToString() {
        return "SAFE";
    }
    function def_priv_propCreate(p, prefix) {
        function instInit(inst, secret) {
            def.setNonEnum(inst, p, key.safe(secret));
            return secret;
        }
        function propKey(inst) {
            return key(inst[p]);
        }
        p || (p = def_priv_random(prefix));
        var key = this;
        propKey.init = instInit;
        propKey.propertyName = p;
        return propKey;
    }
    function def_QualifiedName(full) {
        var parts;
        if (full) if (full instanceof Array) {
            parts = full;
            full = parts.join(".");
        } else parts = full.split(".");
        if (parts && parts.length > 1) {
            this.name = parts.pop();
            this.namespace = parts.join(".");
        } else {
            this.name = full || null;
            this.namespace = null;
        }
    }
    function def_getNamespace(name, base) {
        var current = base || def_currentSpace;
        if (name) {
            var parts = name.split("."), L = parts.length;
            if (L) {
                var part, i = 0;
                if (current === def.global) {
                    part = parts[0];
                    var globalSpace = def.getOwn(def_globalSpaces, part);
                    if (globalSpace) {
                        current = globalSpace;
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
    function createMixin(protectNativeObject) {
        return function(instance) {
            return mixinMany(instance, A_slice.call(arguments, 1), protectNativeObject);
        };
    }
    function mixinMany(instance, mixins, protectNativeObject) {
        for (var mixin, i = 0, L = mixins.length; L > i; ) if (mixin = mixins[i++]) {
            mixin = def.object.as(mixin.prototype || mixin);
            mixin && mixinRecursive(instance, mixin, protectNativeObject);
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
                    O_hasOwn.call(instance, p) || (instance[p] = oTo = Object.create(oTo));
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
        var p, vObj;
        for (p in instance) (vObj = def.object.asNative(instance[p])) && createRecursive(instance[p] = Object.create(vObj));
    }
    function def_config(pub, config) {
        if (config) {
            var cfg = pub.configure;
            def.fun.is(cfg) ? cfg.call(pub, config) : def_config.generic(pub, config);
        }
        return pub;
    }
    function def_config_expand(configs, one) {
        function processConfig(config) {
            var root = {};
            roots.push(root);
            for (var name in config) if (name) {
                var v = config[name];
                if (void 0 === v) continue;
                var L, i, n, next, curr = root, di = name.indexOf("."), names = null;
                if (di >= 0) {
                    if (one) {
                        names = [ name.substr(0, di) ];
                        name = name.substr(di + 1);
                    } else {
                        names = name.split(".");
                        name = names.pop();
                    }
                    L = names.length;
                    i = -1;
                    for (;++i < L; ) if (n = names[i]) {
                        next = curr[n];
                        if (void 0 === next) curr[n] = next = {}; else if (null === next || !def.object.is(next) || next.constructor !== Object) {
                            roots.push(root = next = {});
                            i = -1;
                        }
                        curr = next;
                    }
                }
                var v0 = curr[name];
                if (v0 !== v) {
                    if (void 0 !== v0) {
                        if (!def_config_isMergeableObject(v) || !def_config_isMergeableObject(v0)) continue;
                        roots.push(root = curr = {});
                        if (names) {
                            i = -1;
                            for (;++i < L; ) (n = names[i]) && (curr = curr[n] = {});
                        }
                    }
                    curr[name] = v;
                }
            }
        }
        if (!configs) return [];
        var roots = [];
        def.array.each(configs, processConfig);
        return roots;
    }
    function def_config_isMergeableObject(v) {
        return v && def.object.is(v) && !def_config_isCustomObject(v);
    }
    function def_config_isCustomObject(v) {
        return v.constructor !== Object && !(v instanceof Array);
    }
    function def_inherit(Ctor, BaseCtor) {
        if (BaseCtor) {
            var baseProto = BaseCtor.prototype, proto = Ctor.prototype = Object.create(baseProto);
            def.setNonEnum(proto, "constructor", Ctor);
            "__proto__" in proto || def.setNonEnum(proto, "__proto__", baseProto);
        }
        return Ctor;
    }
    function def_overrides(method, base, proto) {
        function overridenClass() {
            var _ = proto.base;
            proto.base = base;
            try {
                return method.apply(this, arguments);
            } finally {
                proto.base = _;
            }
        }
        function overridenInstance() {
            var proto = def_safeProtoBase(this), _ = proto.base;
            proto.base = base;
            try {
                return method.apply(this, arguments);
            } finally {
                proto.base = _;
            }
        }
        if (!method) return base;
        if (!base || base === method || !def_callsBase(method)) return method;
        def_validateProtoBase(proto);
        return def.fun.wraps(proto ? overridenClass : overridenInstance, method);
    }
    function def_callsBase(f) {
        return _reCallsBase.test(f);
    }
    function def_validateProtoBase(proto) {
        if (proto === O_proto) throw def.error.invalidArgument("proto", "Cannot change Object.prototype.");
        return proto;
    }
    function def_safeProtoBase(inst) {
        def_validateProtoBase(def.protoOf(inst));
    }
    function def_inheritedMethod(proto, p) {
        var m = def.fun.as(proto[p]);
        return m === O_proto[p] ? null : m;
    }
    function def_methods(proto, mixins, ka) {
        proto = F_protoOrSelf(proto);
        var baseProto = def.protoOf(proto), rootProto = def.rootProtoOf(proto), enumerable = def.get(ka, "enumerable", !0);
        def.array.each(mixins, function(mixin) {
            (mixin = F_protoOrSelf(mixin)) && (O_getOwnPropDesc ? def.ownKeys(mixin).forEach(function(p) {
                def_methodPropDesc_(proto, p, O_getOwnPropDesc(mixin, p), baseProto, rootProto, enumerable);
            }) : def.eachOwn(mixin, function(v, p) {
                def_method_(proto, p, v, baseProto, rootProto, enumerable);
            }));
        });
        return def;
    }
    function def_method(proto, p, v, ka) {
        proto = F_protoOrSelf(proto);
        var enumerable = def.get(ka, "enumerable", !0);
        return def_method_(proto, p, v, def.protoOf(proto), def.rootProtoOf(proto), enumerable);
    }
    function def_isValidMethodName(p) {
        return "base" !== p && "constructor" !== p;
    }
    function def_method_(proto, p, v, baseProto, rootProto, enumerable) {
        void 0 !== v && baseProto[p] !== v && def_isValidMethodName(p) && def_methodValue(proto, p, v, rootProto, enumerable);
        return def;
    }
    function def_methodValue(proto, p, v, rootProto, enumerable) {
        var m;
        if (v && (m = def.fun.as(v))) {
            v = def.overrides(m, def_inheritedMethod(proto, p), rootProto);
            enumerable ? proto[p] = v : def.setNonEnum(proto, p, v);
        } else mixinProp(proto, p, v, def.identity);
    }
    function def_methodPropDesc_(proto, p, propDesc, baseProto, rootProto, enumerable) {
        "use strict";
        var v;
        if (def_isValidMethodName(p)) if (propDesc.get || propDesc.set) {
            var basePropDesc = def_inheritedPropDesc(baseProto, p);
            if (basePropDesc) {
                (propDesc.get || basePropDesc.get) && (propDesc.get = def.overrides(propDesc.get, basePropDesc.get, rootProto));
                (propDesc.set || basePropDesc.set) && (propDesc.set = def.overrides(propDesc.set, basePropDesc.set, rootProto));
            }
            enumerable || (propDesc.enumerable = !1);
            O_defProp(proto, p, propDesc);
        } else void 0 !== (v = propDesc.value) && def_methodValue(proto, p, v, rootProto, enumerable);
        return def;
    }
    function def_inheritedPropDesc(proto, p) {
        var propDesc;
        if (p in proto) for (;proto = def.protoOf(proto); ) if (propDesc = O_getOwnPropDesc(proto, p)) return propDesc;
    }
    function def_MetaType(TypeCtor, baseType, keyArgs) {
        this.baseType = baseType || null;
        var MetaType = this.constructor;
        if (MetaType.Ctor) throw def.error.operationInvalid("MetaType already has an associated type constructor.");
        if (TypeCtor && TypeCtor.Meta) throw def.error.argumentInvalid("TypeCtor", "Specified type constructor already has an associated MetaType.");
        this.external = !!TypeCtor;
        this.rootType = baseType ? baseType.rootType : this;
        this._init = baseType ? baseType._init : null;
        this._post = baseType ? baseType._post : null;
        var baseMixins = baseType && baseType._mixins;
        this._mixins = baseMixins ? baseMixins.slice() : null;
        this.steps = void 0;
        TypeCtor = this._initConstructor(TypeCtor || this._createConstructor());
        this.Ctor = MetaType.Ctor = TypeCtor;
    }
    function def_isMetaType(fun) {
        return def.fun.is(fun) && fun.meta instanceof def_MetaType;
    }
    function def_MetaTypeStatic_methods(mixins, ka) {
        def.methods(this, mixins, ka);
        var TypeCtor = this.Ctor;
        TypeCtor && def_MetaTypeStatic_syncCtor.call(this, TypeCtor, mixins);
        return this;
    }
    function def_MetaTypeStatic_syncCtor(TypeCtor, mixins) {
        var TypeProto = this.prototype;
        def.array.each(mixins, function(mixin) {
            def.each(F_protoOrSelf(mixin), function(v, p) {
                if (!def.isPropPrivate(p)) {
                    v = TypeProto[p];
                    def.fun.is(v) ? def_MetaTypeStatic_exportMethod(TypeCtor, p, v) : TypeCtor[p] = v;
                }
            });
        });
    }
    function def_MetaTypeStatic_exportMethod(to, p, m) {
        function exportedTypeMethod() {
            var metaType = this.meta, result = m.apply(metaType, arguments);
            return result === metaType ? this : result;
        }
        to[p] = def.fun.wraps(exportedTypeMethod, m);
    }
    function fields_createChild(config) {
        var factory = def.classOf(this);
        return factory(config, this);
    }
    function def_makeSetter(name, spec) {
        function setter(fields, v2) {
            if (void 0 !== v2) {
                var v1 = fields[name];
                if (null === v2) {
                    if (fields.___proto && O_hasOwn.call(fields, name)) {
                        delete fields[name];
                        v2 = fields[name];
                        change && v2 !== v1 && change(v2, v1, this, name);
                    }
                } else if (v2 !== v1) {
                    if (fail && (msg = fail(v2))) throw new def.error.argumentInvalid(name, def.string.is(msg) ? msg : "");
                    cast && (v2 = convert.call(this, fields, v2, v1));
                    if (null != v2) {
                        fields[name] = v2;
                        change && change(v2, v1, this, name);
                    }
                }
            }
            return this;
        }
        function convert(fields, v2, v1) {
            var vSet = cast(v2);
            if (null == vSet) {
                if (!configurable) return;
                if (O_hasOwn.call(fields, name)) {
                    def.configure(v1, v2);
                    return;
                }
                if (!factory) return;
                vSet = factory(v2, v1);
            }
            return vSet;
        }
        def.fun.is(spec) && (spec = {
            factory: spec
        });
        var msg, factory = def.get(spec, "factory"), configurable = def.get(spec, "configurable", !!factory), change = def.get(spec, "change"), cast = def.get(spec, "cast"), fail = def.get(spec, "fail");
        spec = null;
        return setter;
    }
    function FieldsMetaType(Ctor, baseType, keyArgs) {
        def.MetaType.apply(this, arguments);
        var baseType = this.baseType;
        baseType && (this.fieldsPrivProp = baseType.constructor === FieldsMetaType ? def.get(keyArgs, "fieldsPrivProp") || fields_privProp : baseType.fieldsPrivProp);
    }
    function eventSource_on1(inst, type, handler, before) {
        var hi, evs = def.lazy(inst, "__eventz"), has = evs[type] || (evs[type] = {
            before: [],
            after: [],
            count: 0
        });
        if (def.fun.is(handler)) hi = {
            handler: handler,
            _handler: null,
            _filter: null
        }; else {
            if (!(handler instanceof Object)) throw def.error.argumentInvalid("handler", "Invalid type.");
            hi = handler;
            if (!hi.handler) throw def.error.argumentRequired("handler.handler");
            hi._filter = null;
        }
        hi._handler = hi.handler;
        has[before ? "before" : "after"].push(hi);
        has.count++;
        inst._on && inst._on(type, hi, !!before);
    }
    function eventSource_off1(inst, type, handler) {
        var evs, has, handlerFun;
        if (null != handler) if (def.fun.is(handler)) handlerFun = handler; else {
            if (!(handler instanceof Object)) throw def.error.argumentInvalid("handler", "Invalid type.");
            handlerFun = handler.handler;
        }
        if ((evs = inst.__eventz) && (has = evs[type]) && has.count) {
            var pha, hi, i, isAfter;
            if (handlerFun) {
                pha = has.before;
                i = findHandlerIndex(pha, handlerFun);
                isAfter = 0 > i;
                if (isAfter) {
                    pha = has.after;
                    i = findHandlerIndex(pha, handlerFun);
                    if (0 > i) return;
                }
                hi = pha[i];
                if (0 === --has.count) evs[type] = null; else {
                    pha = pha.slice();
                    pha.splice(i, 1);
                    has[isAfter ? "after" : "before"] = pha;
                }
                eventSource_off1Core(inst, type, hi, !isAfter);
            } else {
                var befores = has.before, afters = has.after;
                evs[type] = null;
                i = befores.length;
                for (;i--; ) eventSource_off1Core(inst, type, befores[i], !0);
                i = afters.length;
                for (;i--; ) eventSource_off1Core(inst, type, afters[i], !1);
            }
        }
    }
    function eventSource_off1Core(inst, type, hi, isBefore) {
        inst._off && inst._off(type, hi, isBefore);
        hi._filter = hi._handler = null;
    }
    function eventSource_acting(source, type, defExpr) {
        var evs, has, phaB, phaA, LB, LA;
        if ((evs = source.__eventz) && (has = evs[type])) {
            LB = (phaB = has.before).length;
            LA = (phaA = has.after).length;
        }
        if (!LA && !LB && !defExpr) return null;
        var preventable = !1, prevented = !1, ev = {
            type: type,
            phase: null,
            source: source,
            result: void 0,
            cancelable: !1,
            defaultPrevented: prevented,
            preventDefault: function() {
                preventable && (this.defaultPrevented = prevented = !0);
            },
            trigger: function(ctx, args) {
                if (this.phase) throw def.error.operationInvalid("Event can only be triggered once.");
                ctx || (ctx = source);
                args || (args = [ this ]);
                preventable = !!this.cancelable;
                var result;
                LB && eventSource_actPhase(ctx, this, args, phaB, LB, !0);
                if (!preventable || !this.defaultPrevented) {
                    if (defExpr) {
                        this.phase = "default";
                        this.result = result = defExpr.apply(ctx, args);
                    }
                    LA && eventSource_actPhase(ctx, this, args, phaA, LA, !1);
                }
                this.phase = "done";
                return result;
            }
        };
        return ev;
    }
    function eventSource_actPhase(inst, ev, args, has, L, before) {
        var hi, i = -1;
        ev.phase = before ? "before" : "after";
        for (;++i < L; ) {
            hi = has[i];
            (!hi._filter || hi._filter.apply(inst, args)) && hi._handler.apply(inst, args);
        }
    }
    function eventSource_each(fun, inst, type, handler, before, allowNullHandler) {
        if (def.string.is(type)) eventSource_eachHandler(fun, inst, type, handler, before, allowNullHandler); else if (def.array.is(type)) type.forEach(function(typei) {
            if (!def.string.is(typei)) throw def.error.argumentInvalid("type");
            eventSource_eachHandler(fun, inst, typei, handler, before, allowNullHandler);
        }); else {
            if (!def.object.is(type)) throw def.error.argumentInvalid("type");
            def.eachOwn(type, function(hi, typei) {
                eventSource_eachHandler(fun, inst, typei, hi, before, allowNullHandler);
            });
        }
    }
    function eventSource_eachHandler(fun, inst, type1, handler, before, allowNullHandler) {
        if (!allowNullHandler && !handler) throw def.error.argumentRequired("handler");
        def.array.is(handler) ? handler.forEach(function(hi) {
            fun(inst, type1, hi, before);
        }) : fun(inst, type1, handler, before);
    }
    function findHandlerIndex(a, e) {
        for (var i = 0, L = a.length; L > i; i++) if (a[i].handler === e) return i;
        return -1;
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
    function mult10(value, exponent) {
        if (!exponent) return value;
        value = value.toString().split("e");
        return +(value[0] + "e" + (value[1] ? +value[1] + exponent : exponent));
    }
    var IS_DONTENUM_BUGGY = function() {
        for (var p in {
            toString: 1
        }) if ("toString" === p) return !1;
        return !0;
    }(), O_proto = Object.prototype, O_hasOwn = O_proto.hasOwnProperty, O_needEnumProps = IS_DONTENUM_BUGGY ? [ "toString", "valueOf" ] : null;
    Object.keys || (Object.keys = function(o) {
        if (o !== Object(o)) throw new TypeError("Object.keys called on non-object");
        var ret = [];
        for (var p in o) O_hasOwn.call(o, p) && ret.push(p);
        O_needEnumProps && O_needEnumProps.forEach(function(p) {
            O_hasOwn.call(o, p) && ret.push(p);
        });
        return ret;
    });
    Array.prototype.some || (Array.prototype.some = function(fun) {
        "use strict";
        for (var t = Object(this), len = t.length >>> 0, thisArg = arguments.length >= 2 ? arguments[1] : void 0, i = 0; len > i; i++) if (i in t && fun.call(thisArg, t[i], i, t)) return !0;
        return !1;
    });
    Array.prototype.map || (Array.prototype.map = function(f, o) {
        for (var n = this.length, result = new Array(n), i = 0; n > i; i++) i in this && (result[i] = f.call(o, this[i], i, this));
        return result;
    });
    Array.prototype.filter || (Array.prototype.filter = function(f, o) {
        for (var n = this.length, result = new Array(), i = 0; n > i; i++) if (i in this) {
            var v = this[i];
            f.call(o, v, i, this) && result.push(v);
        }
        return result;
    });
    Array.prototype.forEach || (Array.prototype.forEach = function(f, o) {
        for (var n = this.length >>> 0, i = 0; n > i; i++) i in this && f.call(o, this[i], i, this);
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
    Function.prototype.bind || (Function.prototype.bind = function(A_slice) {
        return function(ctx) {
            var staticArgs = A_slice.call(arguments, 1), fToBind = this;
            return function() {
                return fToBind.apply(ctx, staticArgs.concat(A_slice.call(arguments)));
            };
        };
    }(Array.prototype.slice));
    this.JSON || (this.JSON = {});
    this.JSON.stringify || (this.JSON.stringify = function(t) {
        return String(t);
    });
    var A_slice = Array.prototype.slice, A_empty = [], O_defProp = function() {
        var defProp = Object.defineProperty;
        if (defProp) try {
            defProp({}, "test", {});
        } catch (ex) {
            return null;
        }
        return defProp;
    }(), O_getOwnPropDesc = function() {
        var ownPropDesc = O_defProp && Object.getOwnPropertyDescriptor;
        return ownPropDesc && null === ownPropDesc({
            value: null
        }, "value").value ? ownPropDesc : void 0;
    }(), F_protoOrSelf = function(F) {
        return F.prototype || F;
    };
    def.global = this;
    def.copyOwn = function(a, b) {
        var to, from;
        arguments.length >= 2 ? (to = a || {}, from = b) : (to = {}, from = a);
        for (var p in from) O_hasOwn.call(from, p) && (to[p] = from[p]);
        return to;
    };
    def.copyOwn(def, {
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
            return o && O_hasOwn.call(o, p) && null != (v = o[p]) ? v : dv;
        },
        hasOwn: function(o, p) {
            return !!o && O_hasOwn.call(o, p);
        },
        protoOf: Object.getPrototypeOf || function(o) {
            return o.__proto__ || o.constructor && o.constructor.prototype;
        },
        rootProtoOf: function(inst) {
            for (var proto = null, proto2 = def.protoOf(inst); proto2 && proto2 !== O_proto && proto2 !== proto; ) proto2 = def.protoOf(proto = proto2);
            return proto;
        },
        hasOwnProp: O_hasOwn,
        set: function(o) {
            for (var oo = o || {}, a = arguments, i = 1, A = a.length - 1; A > i; i += 2) oo[a[i]] = a[i + 1];
            return oo;
        },
        setDefaults: function(o, o2) {
            var p, oo = o || {}, a = arguments, A = a.length;
            if (2 === A && def.object.is(o2)) def.each(o2, function(v, p) {
                null == oo[p] && (oo[p] = v);
            }); else {
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
            if (2 === A && def.object.is(o2)) def.each(o2, function(v, p) {
                void 0 === oo[p] && (oo[p] = v);
            }); else {
                A--;
                for (var i = 1; A > i; i += 2) {
                    p = a[i];
                    void 0 === oo[p] && (oo[p] = a[i + 1]);
                }
            }
            return oo;
        },
        setNonEnum: function() {
            if (!O_defProp) return function(o, p, v) {
                o[p] = v;
                return o;
            };
            var nonEnumDesc = {
                enumerable: !1,
                configurable: !0,
                writable: !0,
                value: void 0
            };
            return function(o, p, v) {
                nonEnumDesc.value = v;
                O_defProp(o, p, nonEnumDesc);
                nonEnumDesc.value = null;
                return o;
            };
        }(),
        setConst: function() {
            if (!O_defProp) return function(o, p, v) {
                o[p] = v;
                return o;
            };
            var constDesc = {
                enumerable: !1,
                configurable: !1,
                writable: !1,
                value: void 0
            };
            return function(o, p, v) {
                constDesc.value = v;
                O_defProp(o, p, constDesc);
                constDesc.value = null;
                return o;
            };
        }(),
        eachOwn: function(o, fun, ctx) {
            for (var p in o) O_hasOwn.call(o, p) && fun.call(ctx, o[p], p, o);
            o && O_needEnumProps && O_needEnumProps.forEach(function(p) {
                O_hasOwn.call(o, p) && fun.call(ctx, o[p], p, o);
            });
        },
        each: function(o, fun, ctx) {
            for (var p in o) fun.call(ctx, o[p], p, o);
            o && O_needEnumProps && O_needEnumProps.forEach(function(p) {
                O_hasOwn.call(o, p) && fun.call(ctx, o[p], p, o);
            });
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
            def.each(from, function(v, p) {
                to[p] = v;
            });
            return to;
        },
        copyx: function(to, from, keyArgs) {
            var where = def.get(keyArgs, "where"), set = def.get(keyArgs, "set");
            def.each(from, function(v, p) {
                (!where || where(from, p, to)) && (set ? set(to, p, v) : to[p] = v);
            });
            return to;
        },
        copyProps: function(a, b, props) {
            var to, from;
            arguments.length >= 3 ? (to = a || {}, from = b) : (to = {}, from = a, props = b);
            props && (from ? props.forEach(function(p) {
                to[p] = from[p];
            }) : props.forEach(function(p) {
                to[p] = void 0;
            }));
            return to;
        },
        keys: function(o) {
            var keys = [];
            for (var p in o) keys.push(p);
            o && O_needEnumProps && O_needEnumProps.forEach(function(p) {
                keys.push(p);
            });
            return keys;
        },
        values: function(o) {
            var values = [];
            def.each(o, function(v) {
                values.push(v);
            });
            return values;
        },
        uniqueIndex: function(o, key, ctx) {
            var index = {};
            def.each(o, function(v, p) {
                key && (v = key.call(ctx, v));
                null == v || O_hasOwn.call(index, v) || (index[v] = p);
            });
            return index;
        },
        ownKeys: Object.keys,
        own: function(o, f, ctx) {
            var keys = Object.keys(o);
            return f ? keys.map(function(key) {
                return f.call(ctx, o[key], key);
            }) : keys.map(function(key) {
                return o[key];
            });
        },
        make: function(Ctor, args) {
            var inst = Object.create(Ctor.prototype);
            return Ctor.apply(inst, args || A_empty) || inst;
        },
        isPropPrivate: function(p) {
            return !!p && "_" === p.charAt(0);
        }
    });
    def.object = {
        is: function(v) {
            return !!v && "object" == typeof v;
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
    };
    def.lazy = def.object.lazy;
    def.info = function(o, info) {
        var i;
        if (o && info) {
            i = o.__info__;
            if (arguments.length < 2) return i;
            i || def.setNonEnum(o, "__info__", i = {});
            def.copyOwn(i, info);
        }
        return o;
    };
    def.info.get = function(o, p, dv) {
        return def.get(o && o.__info__, p, dv);
    };
    def.copyOwn(def, {
        bit: {
            set: function(bits, set, on) {
                return on || null == on ? bits | set : bits & ~set;
            }
        }
    });
    def.copyOwn(def, {
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
            },
            wraps: function(by, wrapped) {
                by.valueOf = def.fun.constant(wrapped);
                return by;
            },
            typeFactory: function(Ctor) {
                function typeFactory() {
                    return def.make(Ctor, arguments);
                }
                typeFactory.of = Ctor;
                return typeFactory;
            }
        }
    });
    def.ascending = def.compare;
    def.descending = def.compareReverse;
    def.number = {
        is: function(v) {
            return "number" == typeof v;
        },
        as: function(v, dv) {
            return "number" == typeof v ? v : dv;
        },
        to: function(v, dv) {
            if (null == v) return dv;
            var v2 = +v;
            return isNaN(v2) ? dv : v2;
        },
        toPositive: function(v, dv) {
            v = def.number.to(v);
            return null == v || v > 0 ? v : dv;
        },
        toNonNegative: function(v, dv) {
            v = def.number.to(v);
            return null != v && 0 > v ? dv : v;
        }
    };
    def.array = {
        empty: function(v) {
            return !(v && v.length);
        },
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
            return A_slice.apply(al, A_slice.call(arguments, 1));
        },
        each: function(a, f, x) {
            null != a && (def.array.is(a) ? a.forEach(f, x) : f.call(x, a, 0));
        },
        eachReverse: function(a, f, x) {
            if (null != a) if (def.array.is(a)) {
                for (var i = a.length; i--; ) if (f.call(x, a[i], i) === !1) return !1;
            } else if (f.call(x, a, 0) === !1) return !1;
            return !0;
        },
        like: def.copyOwn(function(v) {
            return AL.is(v) ? v : [ v ];
        }, {
            is: function(v) {
                return !!v && null != v.length && "string" != typeof v;
            },
            as: function(v) {
                return AL.is(v) ? v : null;
            }
        }),
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
            var source, a = arguments, S = a.length;
            if (S > 1) for (var s = 1; S > s; s++) if (source = def.array.to(a[s])) for (var i = 0, L = source.length; L > i; ) target.push(source[i++]);
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
    };
    var AL = def.array.like;
    AL.to = AL;
    def.string = {
        is: function(v) {
            return "string" == typeof v;
        },
        to: function(v, ds) {
            return null != v ? String(v) : ds || "";
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
    };
    def.copyOwn(def, {
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
        titleFromName: function(name) {
            return def.firstUpperCase(name).replace(/([a-z\d])([A-Z])/, "$1 $2");
        },
        format: function(mask, scope, ctx) {
            if (null == mask || "" === mask) return "";
            var isScopeFun = scope && def.fun.is(scope);
            return mask.replace(/(^|[^{])\{([^{}]+)\}/g, function($0, before, prop) {
                var value = scope ? isScopeFun ? scope.call(ctx, prop) : scope[prop] : null;
                return before + (null == value ? "" : String(value));
            });
        }
    });
    def.copyOwn(def, {
        nullyTo: function(v, dv) {
            return null != v ? v : dv;
        }
    });
    def.copyOwn(def, {
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
        }
    });
    def.copyOwn(def, {
        error: function(error) {
            return error instanceof Error ? error : new Error(error);
        },
        fail: function(error) {
            throw def.error(error);
        },
        assert: function(msg, scope) {
            throw def.error.assertionFailed(msg, scope);
        }
    });
    def.eachOwn({
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
    }, function(errorFun, name) {
        def.error[name] = errorFun;
        def.fail[name] = function() {
            throw errorFun.apply(null, arguments);
        };
    });
    def.debug = 0;
    !function() {
        if ("undefined" != typeof window && window.location) {
            var urlIfHasDebug = function(url) {
                return url && /\bdebug=true\b/.test(url) ? url : null;
            }, url = urlIfHasDebug(window.location.href);
            if (!url) try {
                url = urlIfHasDebug(window.top.location.href);
            } catch (e) {}
            if (url) {
                var m = /\bdebugLevel=(\d+)/.exec(url);
                def.debug = m ? +m[1] : 3;
            }
        }
    }();
    def.logSeparator = "------------------------------------------";
    def.setDebug = function(level) {
        level = +level;
        level = isNaN(level) ? 0 : level;
        level > 1 && "undefined" == typeof console && (level = 1);
        if (!def.log || def.debug != level) {
            def.debug = level;
            def_syncLog(level);
        }
        return def.debug;
    };
    var def_onDebugChanged = null;
    def.addOnDebugChanged = function(f) {
        def_onDebugChanged || (def_onDebugChanged = []).push(f);
    };
    var def_disabledLogger, def_logNames = [ "info", "debug", "error", "warn", "group", "groupEnd" ], def_logNamesMap = {
        group: "groupCollapsed"
    };
    def.logger = function(prompt, target, loggerProp) {
        return def.debug > 1 ? def_loggerReal(prompt, target) : target ? def_loggerAutoEnableOnce(target, loggerProp, prompt) : def_disabledLogger || (def_disabledLogger = def_loggerDisabled());
    };
    def.setDebug(def.debug);
    def.priv = {
        key: def_priv_key
    };
    var def_priv_random = function(prefix) {
        return "_" + def.nullyTo(prefix, "safe") + new Date().getTime() + Math.round(1e3 * Math.random());
    }, the_priv_key = def.priv.key();
    def.copyOwn(def, {
        scope: function(scopeFun, ctx) {
            return scopeFun.call(ctx);
        },
        firstDefined: function(funs, args, x) {
            var v, i = 0, L = funs.length;
            args || (args = []);
            for (;L > i; ) if (void 0 !== (v = funs[i++].apply(x, args))) return v;
        },
        indexedId: function(prefix, index) {
            return index > 0 ? prefix + "" + (index + 1) : prefix;
        },
        splitIndexedId: function(indexedId) {
            var match = /^(.*?)(\d*)$/.exec(indexedId), index = null;
            if (match[2]) {
                index = Number(match[2]);
                1 >= index ? index = 1 : index--;
            }
            return [ match[1], index ];
        },
        parseDistinctIndexArray: function(value, min, max) {
            value = def.array.as(value);
            if (null == value) return null;
            null == min && (min = 0);
            null == max && (max = 1 / 0);
            var a = def.query(value).select(function(index) {
                return +index;
            }).where(function(index) {
                return !isNaN(index) && index >= min && max >= index;
            }).distinct().array();
            return a.length ? a : null;
        },
        argumentsTypeBind: function(types, values) {
            var V, T = types.length, result = new Array(T);
            if (T && values && (V = values.length)) {
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
            return result;
        },
        argumentsTypeBound: function(types, fun) {
            return function() {
                var args = def.argumentsTypeBind(types, arguments);
                return fun.apply(this, args);
            };
        }
    });
    def.qualName = function(full) {
        return full instanceof def_QualifiedName ? full : new def_QualifiedName(full);
    };
    def.QualifiedName = def_QualifiedName;
    def_QualifiedName.prototype.toString = function() {
        return def.string.join(".", this.namespace, this.name);
    };
    def.qualNameOf = function(o, n) {
        if (arguments.length > 1) {
            o.__qname__ = def.qualName(n);
            return o;
        }
        return o.__qname__;
    };
    var def_currentSpace = def, def_globalSpaces = {}, def_spaceStack = [];
    def.globalSpace = function(name, space) {
        return def_globalSpaces[name] = space;
    };
    def.space = function(name, base, definition) {
        if (def.fun.is(base)) {
            definition = base;
            base = null;
        }
        def.string.is(base) && (base = def_getNamespace(base));
        var space = def_getNamespace(name, base);
        if (definition) {
            def_spaceStack.push(def_currentSpace);
            try {
                definition(space);
            } finally {
                def_currentSpace = def_spaceStack.pop();
            }
        }
        return space;
    };
    def.describe = function(t, keyArgs) {
        var maxLevel = def.get(keyArgs, "maxLevel") || 5, out = [];
        def.describeRecursive(out, t, maxLevel, keyArgs);
        return out.join("");
    };
    def.describeRecursive = function(out, t, remLevels, keyArgs) {
        if (remLevels > 0) {
            remLevels--;
            switch (typeof t) {
              case "undefined":
                return out.push("undefined");

              case "object":
                if (!t) {
                    out.push("null");
                    return !0;
                }
                if (def.fun.is(t.describe)) return t.describe(out, remLevels, keyArgs);
                if (t instanceof Array) {
                    out.push("[");
                    t.forEach(function(item, index) {
                        index && out.push(", ");
                        def.describeRecursive(out, item, remLevels, keyArgs) || out.pop();
                    });
                    out.push("]");
                } else {
                    var ownOnly = def.get(keyArgs, "ownOnly", !0);
                    if (t === def.global) return out.push("<window>"), !0;
                    if (def.fun.is(t.cloneNode)) return out.push("<dom #" + (t.id || t.name || "?") + ">"), 
                    !0;
                    if (remLevels > 1 && t.constructor !== Object) {
                        remLevels = 1;
                        ownOnly = !0;
                    }
                    out.push("{");
                    var first = !0;
                    for (var p in t) if (!ownOnly || def.hasOwnProp.call(t, p)) {
                        first || out.push(", ");
                        out.push(p + ": ");
                        if (def.describeRecursive(out, t[p], remLevels, keyArgs)) first && (first = !1); else {
                            out.pop();
                            first || out.pop();
                        }
                    }
                    if (first) {
                        var s = "" + t;
                        "[object Object]" !== s && out.push("{" + s + "}");
                    }
                    out.push("}");
                }
                return !0;

              case "number":
                out.push("" + Math.round(1e5 * t) / 1e5);
                return !0;

              case "boolean":
                out.push("" + t);
                return !0;

              case "string":
                out.push(JSON.stringify(t));
                return !0;

              case "function":
                return def.get(keyArgs, "funs", !1) ? (out.push(JSON.stringify(t.toString().substr(0, 13) + "...")), 
                !0) : !1;
            }
            out.push("'new ???'");
            return !0;
        }
    };
    def.mixin = createMixin(Object.create);
    def.copyOwn(def.mixin, {
        custom: createMixin,
        inherit: def.mixin,
        copy: createMixin(def.copy),
        share: createMixin(def.identity)
    });
    def.create = function() {
        var mixins = A_slice.call(arguments), deep = !0, baseProto = mixins.shift();
        if ("boolean" == typeof baseProto) {
            deep = baseProto;
            baseProto = mixins.shift();
        }
        var instance;
        if (baseProto) {
            instance = Object.create(baseProto);
            deep && createRecursive(instance);
        } else instance = {};
        if (mixins.length > 0) {
            mixins.unshift(instance);
            def.mixin.apply(def, mixins);
        }
        return instance;
    };
    def.attached = function(o, n, v) {
        var at = o.__attached__;
        if (arguments.length > 2) {
            if (void 0 !== v) {
                at || def.setNonEnum(o, "__attached__", at = {});
                at[n] = v;
            }
            return o;
        }
        return at && at[n];
    };
    def.attached.is = function(n) {
        return !(!n || n.indexOf("$") < 0);
    };
    var def_configBlackList = {
        tryConfigure: 1,
        configure: 1,
        $type: 1
    };
    def.configure = def.config = def_config;
    def.configurable = function(yes, v) {
        return def.info(v, {
            configurable: !!yes
        });
    };
    def.copyOwn(def_config, {
        generic: function(pub, configs) {
            configs && def.array.each(configs, function(config) {
                var m;
                config.constructor === Object ? def_config.setters(pub, config) : pub !== config && (m = pub.tryConfigure) && def.fun.is(m) && m.call(pub, config);
            });
            return pub;
        },
        isPropConfigurable: function(n) {
            return !!n && "_" !== n.charAt(0) && !O_hasOwn.call(def_configBlackList, n);
        },
        setters: function(pub, config) {
            config && def_config.expand1(config).forEach(function(configx) {
                def.each(configx, function(v, name) {
                    def_config.setter(pub, name, v);
                });
            });
            return pub;
        },
        setter: function(pub, name, value) {
            var m, m0, l, v0;
            if (void 0 !== value && def_config.isPropConfigurable(name) && def.fun.is(m = pub[name]) && (m0 = m.valueOf()) && def.info.get(m0, "configurable", (l = m0.length) >= 1)) if (def.attached.is(name)) def.attached(pub, name, value); else if (l) m.call(pub, value); else {
                v0 = m.call(pub);
                (def.object.is(v0) || def.fun.is(v0)) && def_config(v0, value);
            }
            return pub;
        },
        expand1: function(configs) {
            return def_config_expand(configs, !0);
        },
        expand: def_config_expand
    });
    def.fun.inherit = def_inherit;
    def.overrides = def_overrides;
    def.fun.callsBase = def_callsBase;
    var _reCallsBase = /\.\s*base\b/;
    def.methods = def_methods;
    def.method = def_method;
    def.abstractMethod = def.fail.notImplemented;
    def("MetaType", def_MetaType);
    var metaTypeExcludeStaticCopy = {
        Ctor: 1,
        BaseType: 1,
        prototype: 1
    };
    def.copyOwn(def_MetaType, {
        methods: def_MetaTypeStatic_methods,
        add: def.configurable(!1, function() {
            return def_MetaTypeStatic_methods.apply(this, arguments);
        }),
        inst: def.configurable(!1, function() {
            return this.Ctor;
        }),
        subType: def.configurable(!1, function(MetaType, metaTypeConfig, metaTypeKeyArgs) {
            var BaseMetaType = this, BaseTypeCtor = BaseMetaType.Ctor, baseMetaType = BaseTypeCtor && BaseTypeCtor.meta || def.fail.operationInvalid("MetaType is not yet instantiated.");
            def.fun.inherit(MetaType, BaseMetaType);
            def.copyx(MetaType, BaseMetaType, {
                where: function(o, p) {
                    return O_hasOwn.call(o, p) && "_" !== p.charAt(0) && !O_hasOwn.call(metaTypeExcludeStaticCopy, p);
                }
            });
            MetaType.BaseType = BaseMetaType;
            new MetaType(null, baseMetaType, metaTypeKeyArgs);
            metaTypeConfig && def.configure(MetaType, metaTypeConfig);
            return MetaType;
        }),
        extend: def.configurable(!1, function(typeConfig, typeKeyArgs) {
            function MetaType() {
                return BaseMetaType.apply(this, arguments);
            }
            var BaseMetaType = this;
            def.fun.wraps(MetaType, BaseMetaType);
            return BaseMetaType.subType(MetaType, typeConfig, typeKeyArgs);
        })
    });
    def_MetaType.add({
        closed: function() {
            return !!this.steps;
        },
        close: function() {
            this.steps || this._closeCore(this.steps = []);
            return this;
        },
        _assertOpened: function() {
            if (this.closed()) throw def.error.operationInvalid("MetaType is closed.");
        },
        _closeCore: function(steps) {
            this._addPostSteps(steps);
            this._addInitSteps(steps);
        },
        _createConstructor: function() {
            function Class() {
                for (var i = S; i--; ) steps[i].apply(this, arguments) === !0 && (i = S);
            }
            var S = 1, type = this, steps = [ function() {
                steps = type.close().steps;
                S = steps.length;
                return !0;
            } ];
            return Class;
        },
        _initConstructor: function(TypeCtor) {
            var MetaType = this.constructor;
            def_MetaTypeStatic_syncCtor.call(MetaType, TypeCtor, MetaType);
            TypeCtor.meta = this;
            this.baseType && def.fun.inherit(TypeCtor, this.baseType.close().Ctor);
            TypeCtor.MetaType = MetaType;
            return TypeCtor;
        },
        _addPostSteps: function(steps) {
            def.array.eachReverse(this._mixins, function(mixin) {
                mixin._post && steps.push(mixin._post);
            });
            this._post && steps.push(this._post);
        },
        _addInitSteps: function(steps) {
            def.array.eachReverse(this._mixins, function(mixin) {
                mixin._init && steps.push(mixin._init);
            });
            this._init && steps.push(this._init);
        },
        init: function(init) {
            if (!init) throw def.error.argumentRequired("init");
            this._assertOpened();
            this._init = def.overrides(init, this._init, this.rootType.Ctor.prototype);
            return this;
        },
        postInit: function(postInit) {
            if (!postInit) throw def.error.argumentRequired("postInit");
            this._assertOpened();
            this._post = def.overrides(postInit, this._post, this.rootType.Ctor.prototype);
            return this;
        },
        type: def.configurable(!0, function() {
            return this.constructor;
        }),
        add: def.configurable(!1, function(mixin, ka) {
            def_isMetaType(mixin) && this._mixMetaType(mixin.meta);
            return def.methods(this.Ctor, mixin, ka), this;
        }),
        _mixMetaType: function(meta) {
            def.array.lazy(this, "_mixins").push(meta);
        },
        methods: function(mixins, ka) {
            def.array.each(mixins, this.add, this);
            return this;
        },
        method: def.configurable(!1, function(p, v, ka) {
            return def.method(this.Ctor, p, v, ka), this;
        }),
        configure: function(config) {
            return def.configure.generic(this, config), this;
        },
        extend: def.configurable(!1, function(instConfig, typeKeyArgs) {
            var SubTypeCtor = this.constructor.extend(null, typeKeyArgs).Ctor;
            return SubTypeCtor.configure(instConfig);
        })
    });
    var def_Object = new def_MetaType().close().Ctor.add({
        override: function(p, v) {
            return def_method_(this, p, v, def.protoOf(this), def_Object.prototype, !0), this;
        },
        toString: function() {
            return "[object " + String(def.qualNameOf(def.classOf(this))) + "]";
        }
    }, {
        enumerable: !1
    });
    def("Object", def_Object);
    def.type = def.argumentsTypeBound([ "string", "function", "object" ], function(name, baseCtor, space) {
        var BaseMetaType = baseCtor ? baseCtor.MetaType : def_MetaType, TypeCtor = BaseMetaType.extend().Ctor;
        return def(name, TypeCtor, space);
    });
    def.makeEnum = function(a, ka) {
        var i = 1, all = 0, e = {}, allItem = def.get(ka, "all");
        a.forEach(function(p) {
            e[p] = i;
            allItem && (all |= i);
            i <<= 1;
        });
        allItem && (e[allItem] = all);
        return e;
    };
    def.copyOwn(def, {
        classify: function(v, Class) {
            def.setNonEnum(v, "_class", Class);
            return v;
        },
        classOf: function(v) {
            return v && (v._class || v.constructor) || void 0;
        },
        is: function(v, Class) {
            return !!v && (v._class && v._class === Class || v instanceof (Class.of || Class));
        },
        as: function(v, Class, dv) {
            return def.is(v, Class) ? v : dv;
        },
        createIs: function(Class) {
            function isClass(v) {
                return def.is(v, Class);
            }
            return isClass;
        },
        createAs: function(Class) {
            function asClass(v, dv) {
                return def.as(v, Class, dv);
            }
            return asClass;
        },
        isSubClassOf: function(Ctor, BaseCtor) {
            return !(!Ctor || !BaseCtor) && (Ctor === BaseCtor || def.is(F_protoOrSelf(Ctor), BaseCtor));
        }
    });
    var fields_privProp = the_priv_key.property();
    def.copyOwn(def, {
        instance: function(inst, config, proto, specs, privProp) {
            var fields = def.fields(inst, proto, privProp);
            specs && def.instanceAccessors(inst, fields, specs);
            inst.createChild = fields_createChild;
            config && def.configure(inst, config);
            return fields;
        },
        fields: function(inst, proto, privProp) {
            var klass = def.classOf(inst);
            proto && def.is(proto, klass) || (proto = klass.defaults);
            void 0 === privProp && (privProp = fields_privProp);
            var protoFields = proto && privProp && privProp(proto), fields = protoFields ? Object.create(protoFields) : {};
            fields.___proto = proto;
            privProp && privProp.init(inst, fields);
            return fields;
        },
        classAccessors: function(classOrProto, specs, privProp) {
            var classProto = F_protoOrSelf(classOrProto);
            for (var name in specs) classProto[name] = def.classAccessor(name, specs[name], privProp);
            return def;
        },
        classAccessor: function(name, spec, privProp) {
            function classAccessor(v) {
                var fields = privProp(this);
                return arguments.length ? setter.call(this, fields, v) : fields[name];
            }
            var setter = def_makeSetter(name, spec);
            privProp || (privProp = fields_privProp);
            return classAccessor;
        },
        instanceAccessors: function(inst, fields, specs) {
            for (var name in specs) inst[name] = def.instanceAccessor(inst, name, specs[name], fields);
            return def;
        },
        instanceAccessor: function(inst, name, spec, fields) {
            function instanceAccessor(v) {
                return arguments.length ? setter.call(inst, fields, v) : fields[name];
            }
            var setter = def_makeSetter(name, spec);
            return instanceAccessor;
        }
    });
    def.MetaType.subType(FieldsMetaType, {
        methods: {
            fields: function(specs) {
                var accessors = {};
                for (var name in specs) accessors[name] = def.classAccessor(name, specs[name], this.fieldsPrivProp);
                return this.methods(accessors);
            },
            _addInitSteps: function(steps) {
                function initConfig(config, proto) {
                    config && def.configure(this, config);
                }
                function initFields(config, proto) {
                    def.fields(this, proto, type.fieldsPrivProp);
                }
                steps.push(initConfig);
                this.base(steps);
                var type = this;
                steps.push(initFields);
            }
        }
    });
    def("FieldsBase", FieldsMetaType.Ctor);
    var nextGlobalId = 1, nextIdByScope = {}, P_ID = "__def_id__";
    def.nextId = function(scope) {
        if (scope) {
            var nextId = def.getOwn(nextIdByScope, scope) || 1;
            nextIdByScope[scope] = nextId + 1;
            return nextId;
        }
        return nextGlobalId++;
    };
    def.id = function(inst) {
        var id = def.getOwn(inst, P_ID);
        id || def.setConst(inst, P_ID, id = def.nextId());
        return id;
    };
    def.hashKey = function(value) {
        var t = (typeof value).charAt(0);
        switch (t) {
          case "n":
          case "b":
          case "s":
            return t + ":" + value;
        }
        return t + ":" + (value ? value : def.id(value));
    };
    def("Set", def.Object.extend({
        init: function(source, count) {
            this.source = source || {};
            this.count = source ? null != count ? count : def.ownKeys(source).length : 0;
        },
        methods: {
            has: function(p) {
                return O_hasOwn.call(this.source, p);
            },
            add: function(p) {
                var source = this.source;
                if (!O_hasOwn.call(source, p)) {
                    this.count++;
                    source[p] = !0;
                }
                return this;
            },
            rem: function(p) {
                if (O_hasOwn.call(this.source, p)) {
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
        }
    }));
    def("Map", def.Object.extend({
        init: function(source, count) {
            this.source = source || {};
            this.count = source ? null != count ? count : def.ownKeys(source).length : 0;
        },
        methods: {
            has: function(p) {
                return O_hasOwn.call(this.source, p);
            },
            get: function(p) {
                return O_hasOwn.call(this.source, p) ? this.source[p] : void 0;
            },
            set: function(p, v) {
                var source = this.source;
                O_hasOwn.call(source, p) || this.count++;
                source[p] = v;
                return this;
            },
            rem: function(p) {
                if (O_hasOwn.call(this.source, p)) {
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
                    if (!O_hasOwn.call(bs, p)) {
                        result[p] = a;
                        count++;
                    }
                });
                def.eachOwn(bs, function(b, p) {
                    if (!O_hasOwn.call(as, p)) {
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
        }
    }));
    def("OrderedMap", def.Object.extend({
        init: function(baseMap) {
            if (baseMap instanceof def.OrderedMap) {
                this._list = baseMap._list.slice();
                this._map = def.copy(baseMap._map);
            } else {
                this._list = [];
                this._map = {};
            }
        },
        methods: {
            has: function(key) {
                return O_hasOwn.call(this._map, key);
            },
            count: function() {
                return this._list.length;
            },
            get: function(key) {
                var map = this._map;
                return O_hasOwn.call(map, key) ? map[key].value : void 0;
            },
            at: function(index) {
                var bucket = this._list[index];
                return bucket ? bucket.value : void 0;
            },
            add: function(key, v, index) {
                var map = this._map, bucket = O_hasOwn.call(map, key) && map[key];
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
                var map = this._map, bucket = O_hasOwn.call(map, key) && map[key];
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
        }
    }));
    def("EventSource", def.Object.extend({
        methods: {
            on: function(type, handler) {
                return eventSource_each(eventSource_on1, this, type, handler, !0), this;
            },
            before: function(type, handler) {
                return eventSource_each(eventSource_on1, this, type, handler, !0), this;
            },
            after: function(type, handler) {
                return eventSource_each(eventSource_on1, this, type, handler, !1), this;
            },
            off: function(type, handler) {
                return eventSource_each(eventSource_off1, this, type, handler, null, !0), this;
            },
            _acting: function(type, defExpr) {
                if (!type) throw def.error.argumentRequired("type");
                return eventSource_acting(this, type, defExpr);
            }
        }
    }));
    def.html = {
        escape: function(str) {
            return def.string.to(str).replace(/&/gm, "&amp;").replace(/</gm, "&lt;").replace(/>/gm, "&gt;").replace(/"/gm, "&quot;");
        },
        tag: function(name, attrs) {
            attrs = attrs ? def.ownKeys(attrs).map(function(n) {
                var v = attrs[n];
                return def.empty(v) ? "" : " " + n + '="' + String(v) + '"';
            }).join("") : "";
            var content = arguments.length > 2 ? A_slice.call(arguments, 2).map(function(cont) {
                if (null != cont) {
                    def.fun.is(cont) && (cont = cont());
                    cont = def.array.is(cont) ? cont.map(def.string.to).join("") : def.string.to(cont);
                }
                return cont || "";
            }).join("") : "";
            return "<" + name + attrs + ">" + content + "</" + name + ">";
        },
        classes: function(prefix) {
            prefix = prefix ? prefix + "-" : "";
            var out = [];
            A_slice.call(arguments, 1).forEach(function(s, i) {
                def.empty(s) || out.push(prefix + def.css.escapeClass(s));
            });
            return out.join(" ");
        }
    };
    def.css = {
        escapeClass: function(name) {
            return (name || "").replace(/\s/g, "_");
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
        _next: def.abstractMethod,
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
        array: function(to) {
            for (var array = to || [], me = this; me.next(); ) array.push(me.item);
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
            for (;this.next(); ) if (!pred || pred.call(ctx, this.item, this.index)) return this._finish(), 
            !0;
            return !1;
        },
        all: function(pred, ctx) {
            for (;this.next(); ) if (!pred.call(ctx, this.item, this.index)) return this._finish(), 
            !1;
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
                null == key || O_hasOwn.call(keyIndex, key) || (keyIndex[key] = item);
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
            for (;++i < I; ) if (O_hasOwn.call(list, i)) {
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
        _next: function(nextIndex) {
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
                if (null != v && !O_hasOwn.call(ks, v)) {
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
        _next: function(nextIndex) {
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
        _next: function(nextIndex) {
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
                for (var index = count - nextIndex - 1, source = this._source; !O_hasOwn.call(source, index); ) {
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
    def.textTable = function(C) {
        function table() {
            return rows.map(function(r) {
                switch (r) {
                  case rowSepMarkerFirst:
                    return renderRow(r, "╤", "�?", "╔", "╗");

                  case rowSepMarker:
                    return rowSep || (rowSep = renderRow(r, "┼", "─", "╟", "╢"));

                  case rowSepMarkerLast:
                    return renderRow(r, "╧", "�?", "╚", "�?");
                }
                return renderRow(r, "│", " ", "║", "║");
            }).join("\n");
        }
        function renderRow(r, colSep, pad, first, last) {
            return first + r.map(function(s, i) {
                return def.string.padRight(s || "", colsMaxLen[i], pad);
            }).join(colSep) + last;
        }
        var rowSep, rows = [], contPad = " ", colsMaxLen = new Array(C), rowSepMarkerFirst = def.array.create(C, ""), rowSepMarker = rowSepMarkerFirst.slice(), rowSepMarkerLast = rowSepMarkerFirst.slice();
        table.row = function() {
            for (var v, s, args = arguments, i = -1, r = new Array(C); ++i < C; ) {
                v = args[i];
                s = r[i] = contPad + (void 0 === v ? "" : String(v)) + contPad;
                colsMaxLen[i] = Math.max(colsMaxLen[i] || 0, s.length);
            }
            rows.push(r);
            return table;
        };
        table.rowSep = function(isLast) {
            rows.push(rows.length ? isLast ? rowSepMarkerLast : rowSepMarker : rowSepMarkerFirst);
            return table;
        };
        return table;
    };
    def.round10 = function(value, places) {
        if (!places) return Math.round(value);
        value = +value;
        if (isNaN(value) || "number" != typeof places || places % 1 !== 0) return NaN;
        value = Math.round(mult10(value, places));
        return mult10(value, -places);
    };
    def.mult10 = function(value, exponent) {
        return exponent ? mult10(+value, exponent) : value;
    };
    def.delta = function(a, b) {
        if (a === b) return 0;
        var d = a - b;
        return 0 > d ? -d : d;
    };
    def_currentSpace = def.global;
    return def;
}();