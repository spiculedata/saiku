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

var pvc = function(def, pv, cdo) {
    function pvc_unwrapExtensionOne(id, prefix) {
        if (id) {
            if (null != id.abs) return id.abs;
            if (!prefix) return id;
            var uid = def.firstUpperCase(id);
            return null != prefix.abs ? {
                abs: prefix.abs + uid
            } : prefix + uid;
        }
        return prefix;
    }
    function pvc_options(specs, context) {
        function resolve(name) {
            return _infos[name].resolve();
        }
        function option(name, noDefault) {
            var info = resolve(name);
            return noDefault && !info.isSpecified ? void 0 : info.value;
        }
        function isSpecified(name) {
            return resolve(name).isSpecified;
        }
        function getSpecified(name, dv) {
            var info = resolve(name);
            return info.isSpecified ? info.value : dv;
        }
        function specified(name) {
            return option(name, !0);
        }
        function isDefined(name) {
            return def.hasOwn(_infos, name);
        }
        function specify(opts) {
            return set(opts, !1);
        }
        function defaults(opts) {
            return set(opts, !0);
        }
        function getDefaultValue(name) {
            return resolve(name)._dv;
        }
        function set(opts, isDefault) {
            var name, info, value;
            for (name in opts) {
                info = def.hasOwnProp.call(_infos, name) && _infos[name];
                info && void 0 !== (value = opts[name]) && info.set(value, isDefault);
            }
            return option;
        }
        specs || def.fail.argumentRequired("specs");
        var _infos = {};
        def.each(specs, function(spec, name) {
            var info = new pvc_OptionInfo(name, option, context, spec);
            _infos[info.name] = info;
        });
        option.option = option;
        option.specified = specified;
        option.defaultValue = getDefaultValue;
        option.isSpecified = isSpecified;
        option.isDefined = isDefined;
        option.getSpecified = getSpecified;
        option.specify = specify;
        option.defaults = defaults;
        return option;
    }
    function options_resolvers(list) {
        return function(optionInfo) {
            var i, m, L;
            for (i = 0, L = list.length; L > i; i++) {
                m = list[i];
                "string" == typeof m && (m = this[m]);
                if (m.call(this, optionInfo) === !0) return !0;
            }
        };
    }
    function options_constantResolver(value, op) {
        return function(optionInfo) {
            return optionInfo.specify(value), !0;
        };
    }
    function options_specifyResolver(fun, op) {
        return function(optionInfo) {
            var value = fun.call(this, optionInfo);
            return void 0 !== value ? (optionInfo.specify(value), !0) : void 0;
        };
    }
    function options_defaultResolver(fun) {
        return function(optionInfo) {
            var value = fun.call(this, optionInfo);
            return void 0 !== value ? (optionInfo.defaultValue(value), !0) : void 0;
        };
    }
    function OptionsMetaType() {
        def.MetaType.apply(this, arguments);
        var baseOptsDef = this.baseType && this.baseType.optionsDef;
        this.optionsDef = def.create(baseOptsDef);
    }
    function pvc_colorIsGray(color) {
        color = pv.color(color);
        var r = color.r, g = color.g, b = color.b, avg = (r + g + b) / 3, tol = 2;
        return Math.abs(r - avg) <= tol && Math.abs(g - avg) <= tol && Math.abs(b - avg) <= tol;
    }
    function pvc_colorScales(keyArgs) {
        keyArgs || def.fail.argumentRequired("keyArgs");
        var type = keyArgs.type || def.fail.argumentRequired("keyArgs.type");
        switch (type) {
          case "linear":
            return new pvc.color.LinearScalesBuild(keyArgs).buildMap();

          case "discrete":
            return new pvc.color.DiscreteScalesBuild(keyArgs).buildMap();

          case "normal":
            return new pvc.color.NormalScalesBuild(keyArgs).buildMap();
        }
        throw def.error.argumentInvalid("scaleType", "Unexistent scale type '{0}'.", [ type ]);
    }
    function pvc_colorScale(keyArgs) {
        keyArgs || def.fail.argumentRequired("keyArgs");
        var type = keyArgs.type || def.fail.argumentRequired("keyArgs.type");
        switch (type) {
          case "linear":
            return new pvc.color.LinearScalesBuild(keyArgs).build();

          case "discrete":
            return new pvc.color.DiscreteScalesBuild(keyArgs).build();

          case "normal":
            return new pvc.color.NormalScalesBuild(keyArgs).build();
        }
        throw def.error.argumentInvalid("scaleType", "Unexistent scale type '{0}'.", [ type ]);
    }
    function visualContext_update(pvMark, scene) {
        this.event = pv.event;
        this.pvMark = pvMark;
        if (pvMark) {
            var sign = this.sign = pvMark.sign || null;
            !scene && sign && (scene = sign.scene());
            if (scene) this.index = scene.childIndex(); else {
                this.index = null;
                scene = new pvc.visual.Scene(null, {
                    panel: this.panel
                });
            }
        } else {
            this.sign = null;
            this.index = null;
            scene = new pvc.visual.Scene(null, {
                panel: this.panel,
                source: this.chart.root.data
            });
        }
        this.scene = scene;
    }
    function pvc_spec_addEvent(oper, spec, eventName, handler) {
        var operStore = def.lazy(spec, oper), eventStore = operStore[eventName];
        eventStore ? def.array.is(eventStore) && (eventStore = operStore[eventName] = [ eventStore ]) : eventStore = operStore[eventName] = [];
        eventStore.push(handler);
        return pvc.spec;
    }
    function slidingWindow_defaultDimensionName() {
        var baseAxis = this.chart.axes.base;
        return baseAxis ? baseAxis.role.grouping.lastDimensionName() : this.chart.data.type.dimensionsNames()[0];
    }
    function slidingWindow_defaultSelect(allData) {
        for (var dim = this.chart.data.dimensions(this.dimension), maxAtom = dim.max(), mostRecent = maxAtom.value, toRemove = [], i = 0, L = allData.length; L > i; i++) {
            var datum = allData[i], datumScore = datum.atoms[this.dimension].value, scoreAtom = dim.read(datumScore);
            if (null == datumScore) toRemove.push(datum); else {
                if (null == scoreAtom || typeof datumScore != typeof scoreAtom.value) {
                    def.debug >= 2 && def.log("[Warning] The specified scoring function has an invalid return value.");
                    toRemove = [];
                    break;
                }
                datumScore = scoreAtom.value;
                var result = +mostRecent - +datumScore;
                result && result > this.length && toRemove.push(datum);
            }
        }
        return toRemove;
    }
    function SceneMetaType() {
        def.MetaType.apply(this, arguments);
        this._vars = def.create(this.baseType && this.baseType._vars);
    }
    function scene_isPointSwitchingToHoverableSign(ev) {
        var pointTo;
        return !!(ev && (pointTo = ev.pointTo) && pointTo.scenes.mark._hasHoverable);
    }
    function scene_renderId(renderId) {
        if (this._renderId !== renderId) {
            this._renderId = renderId;
            this.renderState = {};
        }
    }
    function rootScene_setActive(scene) {
        var ownerScene;
        scene && (ownerScene = scene.ownerScene) && (scene = ownerScene);
        var active = this._active;
        if (active !== scene) {
            active && scene_setActive.call(active, !1);
            this._active = active = scene || null;
            active && scene_setActive.call(active, !0);
            return !0;
        }
        return !1;
    }
    function scene_setActive(isActive) {
        this.isActive !== isActive && (isActive ? this.isActive = !0 : delete this.isActive);
    }
    function scene_createVarMainMethod(name, nameEval) {
        return function() {
            var vb = this.vars[name];
            if (void 0 === vb) {
                vb = this[nameEval]();
                void 0 === vb && (vb = null);
                this.vars[name] = vb;
            }
            return vb;
        };
    }
    function visRoleBinder_assertUnboundRoleIsOptional(r) {
        if (r.isRequired) throw def.error.operationInvalid("The required visual role '{0}' is unbound.", [ r.name ]);
    }
    function visualRolesBinder_assertState(state, desiredState) {
        if (state !== desiredState) throw def.error.operationInvalid("Invalid state.");
    }
    function datum_notNull(d) {
        return !d.isNull;
    }
    function dataCell_dataPartValue(dc) {
        return dc.dataPartValue;
    }
    function axis_groupingScaleType(grouping) {
        return grouping.isDiscrete() ? "discrete" : grouping.lastDimensionValueType() === Date ? "timeSeries" : "numeric";
    }
    function colorAxis_castColorMap(colorMap) {
        var resultMap;
        if (colorMap) {
            var any;
            def.eachOwn(colorMap, function(v, k) {
                any = !0;
                colorMap[k] = pv.fillStyle(v);
            });
            any && (resultMap = colorMap);
        }
        return resultMap;
    }
    function colorAxis_getDefaultColors() {
        var colors, scaleType = this.scaleType;
        if (scaleType) if ("discrete" === scaleType) if (0 === this.index) colors = pvc.createColorScheme(); else {
            var me = this;
            colors = function() {
                return me.chart._getRoleColorScale(me.role.grouping);
            };
        } else {
            colorAxis_defContColors || (colorAxis_defContColors = [ "red", "yellow", "green" ].map(pv.color));
            colors = colorAxis_defContColors.slice();
        } else colors = pvc.createColorScheme();
        return colors;
    }
    function legend_castSize(size) {
        if (!def.object.is(size)) {
            var position = this.option("Position");
            size = new pvc_Size().setSize(size, {
                singleProp: pvc.BasePanel.orthogonalLength[position]
            });
        }
        return size;
    }
    function legend_castAlign(align) {
        var position = this.option("Position");
        return pvc.parseAlign(position, align);
    }
    function legendItem_castSize(size) {
        return new pvc_Size().setSize(size, {
            singleProp: "width"
        });
    }
    function viewSpec_normalizeDims(dims) {
        if (def.string.is(dims)) dims = dims.split(","); else if (!def.array.is(dims)) throw def.error.argumentInvalid("dims", "Must be a string or an array of strings.");
        return def.query(dims).distinct().sort().array();
    }
    function chart_activeSceneEvent_addFilter(name, hi) {
        function eventFilter() {
            if (!inited) {
                inited = !0;
                this.chart._processViewSpec(hi);
                normDimNames = hi.dimNames;
                normDimsKey = hi.dimsKey;
            }
            if (!normDimNames) return !1;
            var activeFilters = def.lazy(this, "_activeFilters"), value = def.getOwn(activeFilters, normDimsKey);
            void 0 === value && (activeFilters[normDimsKey] = value = evalEventFilter(this.event));
            return value;
        }
        function eventHandler() {
            var ev1 = this.event, ev2 = Object.create(ev1);
            ev2.viewKey = normDimsKey;
            ev2.viewFrom = function() {
                return getSceneView(ev1.from);
            };
            ev2.viewTo = function() {
                return getSceneView(ev1.to);
            };
            this.event = ev2;
            try {
                hi.handler.call(this);
            } finally {
                this.event = ev1;
            }
        }
        function evalEventFilter(ev) {
            var vFrom = getSceneView(ev.from, null), vTo = getSceneView(ev.to, null);
            if (vFrom === vTo) return !1;
            if (null == vFrom || null == vTo) return !0;
            for (var i = normDimNames.length, atomsFrom = vFrom.atoms, atomsTo = vTo.atoms; i--; ) if (atomsFrom[normDimNames[i]].value !== atomsTo[normDimNames[i]].value) return !0;
            return !1;
        }
        function getSceneView(scene, dv) {
            return scene ? scene._asView(normDimsKey, normDimNames) : dv;
        }
        var normDimsKey, normDimNames, inited = !1;
        hi._filter = eventFilter;
        hi._handler = eventHandler;
    }
    function sign_createBasic(pvMark) {
        var as = mark_getAncestorSign(pvMark) || def.assert("There must exist an ancestor sign");
        return new pvc.visual.BasicSign(as.panel, pvMark);
    }
    function mark_getAncestorSign(pvMark) {
        var sign;
        do pvMark = pvMark.parent; while (pvMark && !(sign = pvMark.sign) && (!pvMark.proto || !(sign = pvMark.proto.sign)));
        return sign;
    }
    function discreteBandsLayout(N, B, Bmin, Bmax, E, Emin, Emax, R) {
        if (!N) return null;
        var m;
        null == Bmax && (Bmax = 1 / 0);
        null == Emax && (Emax = 1 / 0);
        null == Bmin && (Bmin = 0);
        null == Emin && (Emin = 0);
        Bmin > Bmax && (Bmax = Bmin);
        Emin > Emax && (Emax = Emin);
        null != B ? Bmin > B ? B = Bmin : B > Bmax && (B = Bmax) : pv.floatEqual(Bmin, Bmax) && (B = Bmin);
        null != E ? Emin > E ? E = Emin : E > Emax && (E = Emax) : pv.floatEqual(Emin, Emax) && (E = Emin);
        var hasB = null != B, hasE = null != E;
        if (hasB || hasE) {
            if (hasB && hasE) {
                if (pv.floatZero(B) && pv.floatZero(E)) return discreteBandsLayout(N, null, 0, 1 / 0, null, 0, 1 / 0, R);
                R = B / (B + E);
                return {
                    mode: "abs",
                    ratio: R,
                    value: N * (B + E),
                    band: B,
                    space: E
                };
            }
            var Smin, Smax;
            if (hasB) {
                Smin = B + Emin;
                Smax = B + Emax;
                R = pv.floatZero(B) ? 0 : B / Smin;
            } else {
                Smin = Bmin + E;
                Smax = Bmax + E;
                R = pv.floatZero(E) ? 1 : Bmin / Smin;
            }
            return {
                mode: "abs",
                ratio: R,
                min: N * Smin,
                max: N * Smax,
                band: hasB ? B : void 0,
                space: hasE ? E : void 0
            };
        }
        var hasBmin = Bmin > 0, hasEmin = Emin > 0, hasBmax = isFinite(Bmax) && Bmax > 0, hasEmax = isFinite(Emax) && Emax > 0, hasMin = hasBmin && hasEmin, hasMax = hasBmax && hasEmax;
        if (hasMin || hasMax) if (hasMin) {
            R = Bmin / (Bmin + Emin);
            if (hasMax) return {
                mode: "abs",
                ratio: R,
                min: N * (Bmin + Emin),
                max: N * (Bmax + Emax),
                band: Bmin,
                space: Emin
            };
            m = Emin / Bmin;
            hasBmax ? Emax = m * Bmax : hasEmax && (Bmax = Emax / m);
        } else {
            m = Emax / Bmax;
            R = Bmax / (Bmax + Emax);
            hasBmin ? Emin = m * Bmin : hasEmin && (Bmin = Emin / m);
        } else {
            if (!(hasBmin || hasBmax || hasEmin || hasEmax)) return {
                mode: "rel",
                ratio: R,
                min: 0,
                max: 1 / 0
            };
            m = 1 / R - 1;
            if (hasEmin) {
                Bmin = Emin / m;
                if (hasBmax && Bmin >= Bmax) return discreteBandsLayout(N, B, Bmin, Bmax, E, Emin, Emax, R);
            } else if (hasBmin) {
                Emin = m * Bmin;
                if (hasEmax && Emin >= Emax) return discreteBandsLayout(N, B, Bmin, Bmax, E, Emin, Emax, R);
            }
            hasBmax ? Emax = m * Bmax : hasEmax && (Bmax = Emax / m);
        }
        return {
            mode: "rel",
            ratio: R,
            min: N * (Bmin + Emin),
            max: N * (Bmax + Emax)
        };
    }
    function pvc_castDomainScope(scope) {
        return pvc.parseDomainScope(scope, this.orientation);
    }
    function pvc_castAxisPosition(side) {
        if (side) {
            if (def.hasOwn(pvc_Sides.namesSet, side)) {
                var mapAlign = pvc.BasePanel["y" === this.orientation ? "horizontalAlign" : "verticalAlign2"];
                return mapAlign[side];
            }
            def.debug >= 2 && def.log(def.format("Invalid axis position value '{0}'.", [ side ]));
        }
        return "x" === this.orientation ? "bottom" : "left";
    }
    function cartAxis_castSize(value) {
        var position = this.option("Position");
        return pvc_Size.toOrtho(value, position);
    }
    function cartAxis_castTitleSize(value) {
        var position = this.option("Position");
        return pvc_Size.to(value, {
            singleProp: pvc.BasePanel.orthogonalLength[position]
        });
    }
    function cartAxis_labelDesiredAngles(value) {
        var angles = [];
        if (!value) return angles;
        def.array.is(value) || angles.push(value);
        for (var i = 0, ic = value.length; i != ic; ++i) {
            var angle = def.number.to(value[i]);
            null != angle && angles.push(angle);
        }
        return angles;
    }
    function getExtensionPrefixes() {
        var extensions = [ "axis" ], st = this.scaleType;
        if (st) {
            "discrete" !== st && extensions.push("continuousAxis");
            extensions.push(st + "Axis");
        }
        this.v1SecondOrientedId && extensions.push(this.v1SecondOrientedId + "Axis");
        extensions.push(this.orientedId + "Axis");
        extensions.push(this.id + "Axis");
        return extensions;
    }
    function pvc_castTrend(trend) {
        if ("trend" === this.name) return null;
        var type = this.option("TrendType");
        !type && trend && (type = trend.type);
        if (!type || "none" === type) return null;
        trend = trend ? Object.create(trend) : {};
        var trendInfo = pvc.trends.get(type);
        trend.info = trendInfo;
        trend.type = type;
        var label = this.option("TrendLabel");
        trend.label = null != label ? String(label) : trendInfo.dataPartAtom.f;
        return trend;
    }
    function cartPlotPanel_axisMayOverflow(axis) {
        return !axis.isDiscrete() && (null != axis.option("FixedMin") || null != axis.option("FixedMax") || null != axis.option("FixedLength") || null != axis.option("Ratio") || axis.option("PreserveRatio"));
    }
    function pvcPoint_buildVisibleOption(type, dv) {
        return {
            resolveV1: function(optionInfo) {
                if (0 === this.globalIndex) {
                    this._specifyChartOption(optionInfo, "show" + type) || optionInfo.defaultValue(dv);
                    return !0;
                }
            }
        };
    }
    function pvcMetricPoint_buildVisibleOption(type) {
        return {
            resolveV1: function(optionInfo) {
                return this._specifyChartOption(optionInfo, "show" + type), !0;
            }
        };
    }
    Array.prototype.every || (Array.prototype.every = function(fun) {
        "use strict";
        if (null == this) throw new TypeError();
        var t = Object(this), len = t.length >>> 0;
        if ("function" != typeof fun) throw new TypeError();
        for (var thisArg = arguments.length >= 2 ? arguments[1] : void 0, i = 0; len > i; i++) if (i in t && !fun.call(thisArg, t[i], i, t)) return !1;
        return !0;
    });
    var DEBUG = 1, pvc = def.globalSpace("pvc", {});
    pvc.data = cdo;
    var pvc_arraySlice = Array.prototype.slice;
    pvc.invisibleFill = "rgba(127,127,127,0.00001)";
    !function() {
        var setTipsyDebug = function(level) {
            pv.Behavior.tipsy.setDebug(level);
        };
        setTipsyDebug(def.debug);
        def.addOnDebugChanged(setTipsyDebug);
    }();
    pvc.NoDataException = def.global.NoDataException = function() {
        this.name = "no-data";
        this.message = "No data found";
    };
    pvc.InvalidDataException = def.global.InvalidDataException = function(msg, name) {
        this.name = name || "invalid-data";
        this.message = msg || "Invalid Data.";
    };
    pvc.defaultCompatVersion = function(compatVersion) {
        var defaults = pvc.BaseChart.prototype.defaults;
        return null != compatVersion ? defaults.compatVersion = compatVersion : defaults.compatVersion;
    };
    pvc.cloneMatrix = function(m) {
        return m.map(function(d) {
            return d.slice();
        });
    };
    pvc.normAngle = pv.Shape.normalizeAngle;
    pvc.orientation = {
        vertical: "vertical",
        horizontal: "horizontal"
    };
    pvc.removeTipsyLegends = function() {
        var tipsy = pv.Behavior.tipsy;
        tipsy && tipsy.removeAll && tipsy.removeAll();
    };
    pvc.createDateComparer = function(parser, key) {
        key || (key = pv.identity);
        return function(a, b) {
            return parser.parse(key(a)) - parser.parse(key(b));
        };
    };
    pvc.buildIndexedId = def.indexedId;
    pvc.makeEnumParser = function(enumName, hasKey, dk) {
        if (def.array.is(hasKey)) {
            var keySet = {};
            hasKey.forEach(function(k) {
                k && (keySet[k.toLowerCase()] = k);
            });
            hasKey = function(k) {
                return def.hasOwn(keySet, k);
            };
        }
        dk && (dk = dk.toLowerCase());
        return function(k) {
            k && (k = ("" + k).toLowerCase());
            if (!hasKey(k)) {
                k && def.debug >= 2 && def.log.warn("Invalid '" + enumName + "' value: '" + k + "'. Assuming '" + dk + "'.");
                k = dk;
            }
            return k;
        };
    };
    pvc.unionExtents = function(result, range) {
        if (result) {
            if (range) {
                range.min < result.min && (result.min = range.min);
                range.max > result.max && (result.max = range.max);
            }
        } else {
            if (!range) return null;
            result = {
                min: range.min,
                max: range.max
            };
        }
        return result;
    };
    pvc.roundPixel = {
        epsilon: .1
    };
    null == $.support.svg && ($.support.svg = document.implementation.hasFeature("http://www.w3.org/TR/SVG11/feature#BasicStructure", "1.1"));
    pvc.extensionTag = "extension";
    pvc.extendType = function(type, exts, names) {
        if (exts) {
            var exts2, sceneVars = type.meta && type.meta._vars, addExtension = function(ext, n) {
                if (void 0 !== ext) {
                    exts2 || (exts2 = {});
                    sceneVars && sceneVars[n] && (n = "_" + n + "EvalCore");
                    exts2[n] = def.fun.to(ext);
                }
            };
            names ? names.forEach(function(n) {
                addExtension(exts[n], n);
            }) : def.each(addExtension);
            exts2 && type.add(exts2);
        }
    };
    var pvc_oneNullArray = [ null ];
    pvc.makeExtensionAbsId = function(id, prefix) {
        if (!id) return prefix;
        var result = [];
        prefix = def.array.to(prefix) || pvc_oneNullArray;
        id = def.array.to(id);
        for (var i = 0, I = prefix.length; I > i; i++) for (var j = 0, J = id.length; J > j; j++) {
            var absId = pvc_unwrapExtensionOne(id[j], prefix[i]);
            absId && result.push(absId);
        }
        return result;
    };
    pvc.uniqueExtensionAbsPrefix = function() {
        return "_" + (new Date().getTime() + Math.floor(1e5 * Math.random()));
    };
    pvc.defaultColorScheme = null;
    pvc.brighterColorTransform = function(color) {
        return (color.rgb ? color : pv.color(color)).brighter(.6);
    };
    pvc.setDefaultColorScheme = function(colors) {
        return pvc.defaultColorScheme = pvc.colorScheme(colors);
    };
    pvc.defaultColor = pv.Colors.category10()("?");
    pvc.colorScheme = function(colors) {
        if (null == colors) return null;
        if ("function" == typeof colors) {
            if (!colors.hasOwnProperty("range")) return colors;
            colors = colors.range();
        } else colors = def.array.as(colors);
        return colors.length ? function() {
            var scale = pv.colors(colors);
            scale.domain.apply(scale, arguments);
            return scale;
        } : null;
    };
    pvc.createColorScheme = function(colors) {
        return pvc.colorScheme(colors) || pvc.defaultColorScheme || pv.Colors.category10;
    };
    pvc.toGrayScale = function(color, alpha, maxGrayLevel, minGrayLevel) {
        color = pv.color(color);
        var avg = .299 * color.r + .587 * color.g + .114 * color.b;
        void 0 === maxGrayLevel ? maxGrayLevel = 200 : null == maxGrayLevel && (maxGrayLevel = 255);
        void 0 === minGrayLevel ? minGrayLevel = 30 : null == minGrayLevel && (minGrayLevel = 0);
        var delta = maxGrayLevel - minGrayLevel;
        avg = 0 >= delta ? maxGrayLevel : minGrayLevel + avg / 255 * delta;
        null == alpha ? alpha = color.opacity : 0 > alpha && (alpha = -alpha * color.opacity);
        avg = Math.round(avg);
        return pv.rgb(avg, avg, avg, alpha);
    };
    pvc.time = {
        intervals: {
            y: 31536e6,
            m: 2592e6,
            d30: 2592e6,
            w: 6048e5,
            d7: 6048e5,
            d: 864e5,
            h: 36e5,
            M: 6e4,
            s: 1e3,
            ms: 1
        },
        withoutTime: function(t) {
            return new Date(t.getFullYear(), t.getMonth(), t.getDate());
        },
        weekday: {
            previousOrSelf: function(t, toWd) {
                var wd = t.getDay(), difDays = wd - toWd;
                if (difDays) {
                    var previousOffset = 0 > difDays ? 7 + difDays : difDays;
                    t = new Date(t - previousOffset * pvc.time.intervals.d);
                }
                return t;
            },
            nextOrSelf: function(t, toWd) {
                var wd = t.getDay(), difDays = wd - toWd;
                if (difDays) {
                    var nextOffset = difDays > 0 ? 7 - difDays : -difDays;
                    t = new Date(t + nextOffset * pvc.time.intervals.d);
                }
                return t;
            },
            closestOrSelf: function(t, toWd) {
                var wd = t.getDay(), difDays = wd - toWd;
                if (difDays) {
                    var D = pvc.time.intervals.d, sign = difDays > 0 ? 1 : -1;
                    difDays = Math.abs(difDays);
                    t = difDays >= 4 ? new Date(t.getTime() + sign * (7 - difDays) * D) : new Date(t.getTime() - sign * difDays * D);
                }
                return t;
            }
        }
    };
    var pv_Mark = pv.Mark, pvc_markZOrder = pv_Mark.prototype.zOrder;
    pv_Mark.prototype.zOrder = function(zOrder) {
        var borderPanel = this.borderPanel;
        return borderPanel && borderPanel !== this ? pvc_markZOrder.call(borderPanel, zOrder) : pvc_markZOrder.call(this, zOrder);
    };
    pv_Mark.prototype.wrapper = function(wrapper) {
        this._wrapper = wrapper;
        return this;
    };
    pv_Mark.prototype.wrap = function(f, m) {
        if (f && def.fun.is(f) && this._wrapper && !f._cccWrapped) {
            f = this._wrapper(f, m);
            f._cccWrapped = !0;
        }
        return f;
    };
    pv.Mark.prototype.call = function(f) {
        f.call(this, this);
    };
    pv_Mark.prototype.lock = function(prop, value) {
        void 0 !== value && this[prop](value);
        (this._locked || (this._locked = {}))[prop] = !0;
        return this;
    };
    pv_Mark.prototype.isIntercepted = function(prop) {
        return this._intercepted && this._intercepted[prop];
    };
    pv_Mark.prototype.isLocked = function(prop) {
        return this._locked && this._locked[prop];
    };
    pv_Mark.prototype.ensureEvents = function(defEvs) {
        var events = this.propertyValue("events", !0);
        events && "none" !== events || this.events(defEvs || "all");
        return this;
    };
    pv_Mark.prototype.addMargin = function(name, margin) {
        if (0 !== margin) {
            var staticValue = def.nullyTo(this.propertyValue(name), 0), fMeasure = pv.functor(staticValue);
            this[name](function() {
                return margin + fMeasure.apply(this, pvc_arraySlice.call(arguments));
            });
        }
        return this;
    };
    pv_Mark.prototype.addMargins = function(margins) {
        var all = def.get(margins, "all", 0);
        this.addMargin("left", def.get(margins, "left", all));
        this.addMargin("right", def.get(margins, "right", all));
        this.addMargin("top", def.get(margins, "top", all));
        this.addMargin("bottom", def.get(margins, "bottom", all));
        return this;
    };
    pv_Mark.prototype.eachInstanceWithData = function(fun, ctx) {
        this.eachInstance(function(scenes, index, t) {
            scenes.mark.sign && scenes[index].data && fun.call(ctx, scenes, index, t);
        });
    };
    pv_Mark.prototype.eachSceneWithDataOnRect = function(rect, fun, ctx, selectionMode) {
        function processShape(shape, instance) {
            if (shape.intersectsRect(rect)) {
                var cccScene = instance.data;
                cccScene && cccScene.datum && fun.call(ctx, cccScene);
            }
        }
        var me = this, sign = me.sign;
        if (!sign || sign.selectable()) {
            null == selectionMode && (selectionMode = me.rubberBandSelectionMode || "partial");
            var useCenter = "center" === selectionMode;
            me.eachInstanceWithData(function(scenes, index, toScreen) {
                var shape = me.getShape(scenes, index, .15);
                shape = (useCenter ? shape.center() : shape).apply(toScreen);
                processShape(shape, scenes[index]);
            });
        }
    };
    pv_Mark.prototype.eachDatumOnRect = function(rect, fun, ctx, selectionMode) {
        function processShape(shape, instance) {
            if (shape.intersectsRect(rect)) {
                var cccScene = instance.data;
                cccScene && cccScene.datum && cccScene.datums().each(function(datum) {
                    datum.isNull || fun.call(ctx, datum);
                });
            }
        }
        var me = this, sign = me.sign;
        if (!sign || sign.selectable()) {
            null == selectionMode && (selectionMode = me.rubberBandSelectionMode || "partial");
            var useCenter = "center" === selectionMode;
            me.eachInstanceWithData(function(scenes, index, toScreen) {
                var shape = me.getShape(scenes, index, .15);
                shape = (useCenter ? shape.center() : shape).apply(toScreen);
                processShape(shape, scenes[index]);
            });
        }
    };
    pv_Mark.prototype.getOwnerInstance = function(scene, index) {
        var cccScene = scene[index].data;
        return cccScene instanceof pvc.visual.Scene && (cccScene = cccScene.ownerScene) ? cccScene.childIndex() : index;
    };
    pv.Mark.prototype._ibits = -1;
    pv.Mark.prototype._imask = 0;
    pv.Mark.prototype.ibits = function(ibits) {
        if (arguments.length) {
            ibits = pvc.visual.Interactive.parseBits(ibits);
            this._ibits = null == ibits ? 0 : ibits;
            return this;
        }
        return this._ibits;
    };
    pv.Mark.prototype.imask = function(imask) {
        if (arguments.length) {
            imask = pvc.visual.Interactive.parseBits(imask);
            this._imask = null == imask ? 0 : imask;
            return this;
        }
        return this._imask;
    };
    pv.Transform.prototype.transformHPosition = function(left) {
        return this.x + this.k * left;
    };
    pv.Transform.prototype.transformVPosition = function(top) {
        return this.y + this.k * top;
    };
    pv.Transform.prototype.transformLength = function(length) {
        return this.k * length;
    };
    pv.Format.createParser = function(pvFormat) {
        function parse(value) {
            return value instanceof Date ? value : def.number.is(value) ? new Date(value) : pvFormat.parse(value);
        }
        return parse;
    };
    pv.Format.createFormatter = function(format) {
        function safeFormat(value) {
            return null != value ? format(value) : "";
        }
        return safeFormat;
    };
    pv.Color.prototype.describe = function(out, remLevels, keyArgs) {
        return def.describeRecursive(out, this.key, remLevels, keyArgs);
    };
    pv_Mark.prototype.hasDelegateValue = function(name, tag) {
        var p = this.$propertiesMap[name];
        return p ? !tag || p.tag === tag : !!this.proto && this.proto.hasDelegateValue(name, tag);
    };
    pvc.parseValuesOverflow = pvc.makeEnumParser("valuesOverflow", [ "show", "trim", "hide" ], "hide");
    pvc.parseMultiChartOverflow = pvc.makeEnumParser("multiChartOverflow", [ "grow", "fit", "clip" ], "grow");
    pvc.parseLegendClickMode = pvc.makeEnumParser("legendClickMode", [ "toggleSelected", "toggleVisible", "none" ], "toggleVisible");
    pvc.parseTooltipAutoContent = pvc.makeEnumParser("tooltipAutoContent", [ "summary", "value" ], "value");
    pvc.parseSelectionMode = pvc.makeEnumParser("selectionMode", [ "rubberBand", "focusWindow" ], "rubberBand");
    pvc.parseClearSelectionMode = pvc.makeEnumParser("clearSelectionMode", [ "emptySpaceClick", "manual" ], "emptySpaceClick");
    pvc.parsePointingMode = pvc.makeEnumParser("pointingMode", [ "over", "near" ], "near");
    pvc.parsePointingCollapse = pvc.makeEnumParser("pointingCollapse", [ "none", "x", "y" ], "none");
    pvc.parseShape = pvc.makeEnumParser("shape", pv.Scene.hasSymbol, null);
    pvc.parseDataTypeCheckingMode = pvc.makeEnumParser("typeCheckingMode", [ "none", "minimum", "extended" ], "minimum");
    pvc.parseLegendOverflow = pvc.makeEnumParser("legendOverflow", [ "clip", "collapse" ], "clip");
    pvc.parseLabelRotationDirection = pvc.makeEnumParser("labelRotationDirection", [ "clockwise", "counterclockwise" ], "clockwise");
    pvc.parseContinuousColorScaleType = function(scaleType) {
        if (scaleType) {
            scaleType = ("" + scaleType).toLowerCase();
            switch (scaleType) {
              case "linear":
              case "normal":
              case "discrete":
                break;

              default:
                def.debug >= 2 && def.log("[Warning] Invalid 'ScaleType' option value: '" + scaleType + "'.");
                scaleType = null;
            }
        }
        return scaleType;
    };
    pvc.parseDomainScope = function(scope, orientation) {
        if (scope) {
            scope = ("" + scope).toLowerCase();
            switch (scope) {
              case "cell":
              case "global":
                break;

              case "section":
                if (!orientation) throw def.error.argumentRequired("orientation");
                scope = "y" === orientation ? "row" : "column";
                break;

              case "column":
              case "row":
                if (orientation && orientation !== ("row" === scope ? "y" : "x")) {
                    scope = "section";
                    def.debug >= 2 && def.log("[Warning] Invalid 'DomainScope' option value: '" + scope + "' for the orientation: '" + orientation + "'.");
                }
                break;

              default:
                def.debug >= 2 && def.log("[Warning] Invalid 'DomainScope' option value: '" + scope + "'.");
                scope = null;
            }
        }
        return scope;
    };
    pvc.parseDomainRoundingMode = function(mode) {
        if (mode) {
            mode = ("" + mode).toLowerCase();
            switch (mode) {
              case "none":
              case "nice":
              case "tick":
                break;

              default:
                def.debug >= 2 && def.log("[Warning] Invalid 'DomainRoundMode' value: '" + mode + "'.");
                mode = null;
            }
        }
        return mode;
    };
    pvc.parseOverlappedLabelsMode = function(mode) {
        if (mode) {
            mode = ("" + mode).toLowerCase();
            switch (mode) {
              case "leave":
              case "hide":
              case "rotate":
              case "rotatethenhide":
                break;

              default:
                def.debug >= 2 && def.log("[Warning] Invalid 'OverlappedLabelsMode' option value: '" + mode + "'.");
                mode = null;
            }
        }
        return mode;
    };
    pvc.parseTrendType = function(value) {
        if (value) {
            value = ("" + value).toLowerCase();
            if ("none" === value) return value;
            if (pvc.trends.has(value)) return value;
            def.debug >= 2 && def.log("[Warning] Invalid 'TrendType' value: '" + value + "'.");
        }
    };
    pvc.parseNullInterpolationMode = function(value) {
        if (value) {
            value = ("" + value).toLowerCase();
            switch (value) {
              case "none":
              case "linear":
              case "zero":
                return value;
            }
            def.debug >= 2 && def.log("[Warning] Invalid 'NullInterpolationMode' value: '" + value + "'.");
        }
    };
    pvc.parseAlign = function(side, align) {
        align && (align = ("" + align).toLowerCase());
        var align2, isInvalid;
        if ("left" === side || "right" === side) {
            align2 = align && pvc.BasePanel.verticalAlign[align];
            if (!align2) {
                align2 = "middle";
                isInvalid = !!align;
            }
        } else {
            align2 = align && pvc.BasePanel.horizontalAlign[align];
            if (!align2) {
                align2 = "center";
                isInvalid = !!align;
            }
        }
        isInvalid && def.debug >= 2 && def.log(def.format("Invalid alignment value '{0}'. Assuming '{1}'.", [ align, align2 ]));
        return align2;
    };
    pvc.parseAnchor = function(anchor) {
        if (anchor) {
            anchor = ("" + anchor).toLowerCase();
            switch (anchor) {
              case "top":
              case "left":
              case "center":
              case "bottom":
              case "right":
                return anchor;
            }
            def.debug >= 2 && def.log(def.format("Invalid anchor value '{0}'.", [ anchor ]));
        }
    };
    pvc.parseAnchorWedge = function(anchor) {
        if (anchor) {
            anchor = ("" + anchor).toLowerCase();
            switch (anchor) {
              case "outer":
              case "inner":
              case "center":
              case "start":
              case "end":
                return anchor;
            }
            def.debug >= 2 && def.log(def.format("Invalid wedge anchor value '{0}'.", [ anchor ]));
        }
    };
    pvc.parsePosition = function(side, defaultSide) {
        if (side) {
            side = ("" + side).toLowerCase();
            if (!def.hasOwn(pvc_Sides.namesSet, side)) {
                var newSide = defaultSide || "left";
                def.debug >= 2 && def.log(def.format("Invalid position value '{0}. Assuming '{1}'.", [ side, newSide ]));
                side = newSide;
            }
        }
        return side || defaultSide || "left";
    };
    pvc.parseAxisFixedLength = function(size) {
        var parsedLength;
        if (size) {
            "string" == typeof size && (size = pv.parseDatePrecision(size));
            "number" != typeof size || 0 >= size ? def.debug >= 2 && def.log(def.format("Invalid fixed length value '{0}'.", [ size ])) : parsedLength = size;
        }
        return parsedLength;
    };
    pvc.parseAxisRatio = function(ratio) {
        var parsedRatio;
        if ("string" == typeof ratio) {
            var reg = /([0-9]\u002F[0-9]|[0-9]\u002F[a-z]|[0-9]\u002F[0-9][a-z])/;
            reg.test(ratio) && (ratio = ratio.split("/"));
            if (2 === ratio.length) {
                var rangeSize = parseInt(ratio[0]), domainSize = pv.parseDatePrecision(ratio[1]);
                isNaN(rangeSize) || isNaN(domainSize) || 0 == domainSize || (ratio = rangeSize / domainSize);
            }
        }
        "number" != typeof ratio || 0 > ratio ? def.debug >= 2 && def.log(def.format("Invalid ratio value '{0}'.", [ ratio ])) : parsedRatio = ratio;
        return parsedRatio;
    };
    pvc.parseDomainAlign = function(fixValue) {
        var fixValue2;
        if (fixValue) {
            fixValue = ("" + fixValue).toLowerCase();
            switch (fixValue) {
              case "max":
              case "min":
              case "center":
                return fixValue;
            }
            fixValue2 = "center";
            def.debug >= 2 && def.log(def.format("Invalid domain align value '{0}'. Assuming '{1}'.", [ fixValue, fixValue2 ]));
        }
        return fixValue2;
    };
    pvc.parseDimensionName = function(name, chart) {
        if (name) {
            name = ("" + name).toLowerCase();
            var dimType = chart.data.type.dimensions(name, {
                assertExists: !1
            });
            if (dimType) return name;
            def.debug >= 2 && def.log(def.format("[Warning] Undefined dimension with name '{0}'.", [ name ]));
        }
        return null;
    };
    var pvc_Offset = def.type("pvc.Offset").init(function(x, y) {
        if (1 === arguments.length) null != x && this.setOffset(x); else {
            null != x && (this.x = x);
            null != y && (this.y = y);
        }
    }).add({
        describe: function(out, remLevels, keyArgs) {
            return def.describeRecursive(out, def.copyOwn(this), remLevels, keyArgs);
        },
        setOffset: function(offset, keyArgs) {
            if ("string" == typeof offset) {
                var comps = offset.split(/\s+/).map(function(comp) {
                    return pvc_PercentValue.parse(comp);
                });
                switch (comps.length) {
                  case 1:
                    this.set(def.get(keyArgs, "singleProp", "all"), comps[0]);
                    return this;

                  case 2:
                    this.set("x", comps[0]);
                    this.set("y", comps[1]);
                    return this;

                  case 0:
                    return this;
                }
            } else {
                if ("number" == typeof offset) {
                    this.set(def.get(keyArgs, "singleProp", "all"), offset);
                    return this;
                }
                if ("object" == typeof offset) {
                    this.set("all", offset.all);
                    for (var p in offset) "all" !== p && this.set(p, offset[p]);
                    return this;
                }
            }
            def.debug && def.log("Invalid 'offset' value: " + def.describe(offset));
            return this;
        },
        set: function(prop, value) {
            if (null != value && def.hasOwn(pvc_Offset.namesSet, prop)) {
                value = pvc_PercentValue.parse(value);
                null != value && ("all" === prop ? pvc_Offset.names.forEach(function(p) {
                    this[p] = value;
                }, this) : this[prop] = value);
            }
        },
        resolve: function(refSize) {
            var offset = {};
            pvc_Size.names.forEach(function(length) {
                var offsetProp = pvc_Offset.namesSizeToOffset[length], offsetValue = this[offsetProp];
                if (null != offsetValue) if ("number" == typeof offsetValue) offset[offsetProp] = offsetValue; else if (refSize) {
                    var refLength = refSize[length];
                    null != refLength && (offset[offsetProp] = offsetValue.resolve(refLength));
                }
            }, this);
            return offset;
        }
    });
    pvc_Offset.type().add({
        names: [ "x", "y" ],
        namesSizeToOffset: {
            width: "x",
            height: "y"
        },
        namesSidesToOffset: {
            left: "x",
            right: "x",
            top: "y",
            bottom: "y"
        },
        as: function(v) {
            null == v || v instanceof pvc_Offset || (v = new pvc_Offset().setOffset(v));
            return v;
        }
    }).add({
        namesSet: pv.dict(pvc_Offset.names, def.retTrue)
    });
    var pvc_PercentValue = pvc.PercentValue = function(pct) {
        this.percent = pct;
    };
    pvc_PercentValue.prototype.resolve = function(total) {
        return this.percent * total;
    };
    pvc_PercentValue.prototype.divide = function(divisor) {
        return new pvc_PercentValue(this.percent / divisor);
    };
    pvc_PercentValue.divide = function(value, divisor) {
        return value instanceof pvc_PercentValue ? value.divide(divisor) : value / divisor;
    };
    pvc_PercentValue.parse = function(value) {
        if (null != value && "" !== value) {
            switch (typeof value) {
              case "number":
                return value;

              case "string":
                var match = value.match(/^(.+?)\s*(%)?$/);
                if (match) {
                    var n = +match[1];
                    if (!isNaN(n)) {
                        if (!match[2]) return n;
                        if (n >= 0) return new pvc_PercentValue(n / 100);
                    }
                }
                break;

              case "object":
                if (value instanceof pvc_PercentValue) return value;
            }
            def.debug && def.log(def.format("Invalid margins component '{0}'", [ "" + value ]));
        }
    };
    pvc_PercentValue.resolve = function(value, total) {
        return value instanceof pvc_PercentValue ? value.resolve(total) : value;
    };
    var pvc_Sides = pvc.Sides = function(sides) {
        null != sides && this.setSides(sides);
    };
    pvc_Sides.hnames = "left right".split(" ");
    pvc_Sides.vnames = "top bottom".split(" ");
    pvc_Sides.names = "left right top bottom".split(" ");
    pvc_Sides.namesSet = pv.dict(pvc_Sides.names, def.retTrue);
    pvc_Sides.as = function(v) {
        null == v || v instanceof pvc_Sides || (v = new pvc_Sides().setSides(v));
        return v;
    };
    pvc_Sides.to = function(v) {
        null != v && v instanceof pvc_Sides || (v = new pvc_Sides().setSides(v));
        return v;
    };
    pvc_Sides.prototype.describe = function(out, remLevels, keyArgs) {
        return def.describeRecursive(out, def.copyOwn(this), remLevels, keyArgs);
    };
    pvc_Sides.prototype.setSides = function(sides) {
        if ("string" == typeof sides) {
            var comps = sides.split(/\s+/).map(function(comp) {
                return pvc_PercentValue.parse(comp);
            });
            switch (comps.length) {
              case 1:
                this.set("all", comps[0]);
                return this;

              case 2:
                this.set("top", comps[0]);
                this.set("left", comps[1]);
                this.set("right", comps[1]);
                this.set("bottom", comps[0]);
                return this;

              case 3:
                this.set("top", comps[0]);
                this.set("left", comps[1]);
                this.set("right", comps[1]);
                this.set("bottom", comps[2]);
                return this;

              case 4:
                this.set("top", comps[0]);
                this.set("right", comps[1]);
                this.set("bottom", comps[2]);
                this.set("left", comps[3]);
                return this;

              case 0:
                return this;
            }
        } else {
            if ("number" == typeof sides) {
                this.set("all", sides);
                return this;
            }
            if ("object" == typeof sides) {
                if (sides instanceof pvc_PercentValue) this.set("all", sides); else if (sides instanceof pvc_Sides) pvc_Sides.names.forEach(function(p) {
                    def.hasOwn(sides, p) && (this[p] = sides[p]);
                }, this); else {
                    this.set("all", sides.all);
                    this.set("width", sides.width);
                    this.set("height", sides.height);
                    for (var p in sides) pvc_Sides.namesSet.hasOwnProperty(p) && this.set(p, sides[p]);
                }
                return this;
            }
        }
        def.debug && def.log("Invalid 'sides' value: " + def.describe(sides));
        return this;
    };
    pvc_Sides.prototype.set = function(prop, value) {
        value = pvc_PercentValue.parse(value);
        if (null != value) switch (prop) {
          case "all":
            pvc_Sides.names.forEach(function(p) {
                this[p] = value;
            }, this);
            break;

          case "width":
            this.left = this.right = pvc_PercentValue.divide(value, 2);
            break;

          case "height":
            this.top = this.bottom = pvc_PercentValue.divide(value, 2);
            break;

          default:
            def.hasOwn(pvc_Sides.namesSet, prop) && (this[prop] = value);
        }
    };
    pvc_Sides.prototype.resolve = function(width, height) {
        if ("object" == typeof width) {
            height = width.height;
            width = width.width;
        }
        var sides = {};
        pvc_Sides.names.forEach(function(side) {
            var value = 0, sideValue = this[side];
            null != sideValue && (value = "number" == typeof sideValue ? sideValue : sideValue.resolve("left" === side || "right" === side ? width : height));
            sides[side] = value;
        }, this);
        return pvc_Sides.updateSize(sides);
    };
    pvc_Sides.updateSize = function(sides) {
        sides.width = (sides.left || 0) + (sides.right || 0);
        sides.height = (sides.bottom || 0) + (sides.top || 0);
        return sides;
    };
    pvc_Sides.prototype.getDirectionPercentage = function(a_length) {
        var sides = "width" === a_length ? pvc_Sides.hnames : pvc_Sides.vnames, me = this;
        return sides.reduce(function(pct, side) {
            return (me.getSidePercentage(side) || 0) + pct;
        }, 0);
    };
    pvc_Sides.prototype.getSidePercentage = function(side) {
        var value = this[side];
        return null != value && "number" != typeof value ? value.percent : null;
    };
    pvc_Sides.resolvedMax = function(a, b) {
        var sides = {};
        pvc_Sides.names.forEach(function(side) {
            sides[side] = Math.max(a[side] || 0, b[side] || 0);
        });
        return pvc_Sides.updateSize(sides);
    };
    pvc_Sides.inflate = function(sides, by) {
        var sidesOut = {};
        pvc_Sides.names.forEach(function(side) {
            sidesOut[side] = (sides[side] || 0) + by;
        });
        return pvc_Sides.updateSize(sidesOut);
    };
    pvc_Sides.filterAnchor = function(a, sides) {
        var filtered = new pvc_Sides();
        pvc_Sides.getAnchorSides(a).forEach(function(side) {
            filtered.set(side, sides[side]);
        });
        return filtered;
    };
    pvc_Sides.getAnchorSides = function(a) {
        switch (a) {
          case "left":
          case "right":
            return pvc_Sides.vnames;

          case "top":
          case "bottom":
            return pvc_Sides.hnames;

          case "fill":
            return pvc_Sides.names;
        }
    };
    var pvc_Size = def.type("pvc.Size").init(function(width, height) {
        if (1 === arguments.length) null != width && this.setSize(width); else {
            null != width && (this.width = width);
            null != height && (this.height = height);
        }
    }).add({
        describe: function(out, remLevels, keyArgs) {
            return def.describeRecursive(out, def.copyOwn(this), remLevels, keyArgs);
        },
        setSize: function(size, keyArgs) {
            if ("string" == typeof size) {
                var comps = size.split(/\s+/).map(function(comp) {
                    return pvc_PercentValue.parse(comp);
                });
                switch (comps.length) {
                  case 1:
                    this.set(def.get(keyArgs, "singleProp", "all"), comps[0]);
                    return this;

                  case 2:
                    this.set("width", comps[0]);
                    this.set("height", comps[1]);
                    return this;

                  case 0:
                    return this;
                }
            } else {
                if ("number" == typeof size) {
                    this.set(def.get(keyArgs, "singleProp", "all"), size);
                    return this;
                }
                if ("object" == typeof size) {
                    if (size instanceof pvc_PercentValue) this.set(def.get(keyArgs, "singleProp", "all"), size); else {
                        this.set("all", size.all);
                        for (var p in size) "all" !== p && this.set(p, size[p]);
                    }
                    return this;
                }
            }
            def.debug && def.log("Invalid 'size' value: " + def.describe(size));
            return this;
        },
        set: function(prop, value) {
            if (null != value && ("all" === prop || def.hasOwn(pvc_Size.namesSet, prop))) {
                value = pvc_PercentValue.parse(value);
                null != value && ("all" === prop ? pvc_Size.names.forEach(function(p) {
                    this[p] = value;
                }, this) : this[prop] = value);
            }
            return this;
        },
        clone: function() {
            return new pvc_Size(this.width, this.height);
        },
        intersect: function(size) {
            return new pvc_Size(Math.min(this.width, size.width), Math.min(this.height, size.height));
        },
        resolve: function(refSize) {
            var size = {};
            pvc_Size.names.forEach(function(length) {
                var lengthValue = this[length];
                if (null != lengthValue) if ("number" == typeof lengthValue) size[length] = lengthValue; else if (refSize) {
                    var refLength = refSize[length];
                    null != refLength && (size[length] = lengthValue.resolve(refLength));
                }
            }, this);
            return size;
        }
    });
    pvc_Size.names = [ "width", "height" ];
    pvc_Size.namesSet = pv.dict(pvc_Size.names, def.retTrue);
    pvc_Size.toOrtho = function(value, anchor) {
        if (null != value) {
            var a_ol;
            anchor && (a_ol = pvc.BasePanel.orthogonalLength[anchor]);
            value = pvc_Size.to(value, {
                singleProp: a_ol
            });
            anchor && delete value[pvc.BasePanel.oppositeLength[a_ol]];
        }
        return value;
    };
    pvc_Size.to = function(v, keyArgs) {
        null == v || v instanceof pvc_Size || (v = new pvc_Size().setSize(v, keyArgs));
        return v;
    };
    pvc_Size.applyMax = function(size, sizeMax) {
        var v, vMax;
        null != (vMax = sizeMax.width) && null != (v = size.width) && v > vMax && (size.width = vMax);
        null != (vMax = sizeMax.height) && null != (v = size.height) && v > vMax && (size.height = vMax);
        return size;
    };
    pvc_Size.applyMin = function(size, sizeMin) {
        var v, vMin;
        null != (vMin = sizeMin.width) && null != (v = size.width) && vMin > v && (size.width = vMin);
        null != (vMin = sizeMin.height) && null != (v = size.height) && vMin > v && (size.height = vMin);
        return size;
    };
    pvc_Size.applyMinMax = function(size, sizeMin, sizeMax) {
        var v, vLim;
        null != (v = size.width) && (null != (vLim = sizeMin.width) && vLim > v ? size.width = vLim : null != (vLim = sizeMax.width) && v > vLim && (size.width = vLim));
        null != (v = size.height) && (null != (vLim = sizeMin.height) && vLim > v ? size.height = vLim : null != (vLim = sizeMax.height) && v > vLim && (size.height = vLim));
        return size;
    };
    pvc_Size.deflate = function(size, byWidth, byHeight) {
        var v;
        return {
            width: null != (v = size.width) ? Math.max(v - byWidth, 0) : null,
            height: null != (v = size.height) ? Math.max(v - byHeight, 0) : null
        };
    };
    pvc_Size.inflate = function(size, byWidth, byHeight) {
        var v;
        return {
            width: null != (v = size.width) ? v + byWidth : null,
            height: null != (v = size.height) ? v + byHeight : null
        };
    };
    pvc_Size.clone = function(size) {
        return {
            width: size.width,
            height: size.height
        };
    };
    def.type("pvc.Abstract").init(function() {
        this.log = def.logger(this._getLogId, this);
    }).add({
        invisibleLineWidth: .001,
        defaultLineWidth: 1.5,
        _logId: null,
        _getLogId: function() {
            return this._logId || (this._logId = this._processLogId(this._createLogId()));
        },
        _createLogId: function() {
            return String(def.qualNameOf(this.constructor));
        },
        _processLogId: function(logInstanceId) {
            var L = 30, s = logInstanceId.substr(0, L);
            s.length < L && (s += def.array.create(L - s.length, " ").join(""));
            return "[" + s + "]";
        }
    });
    pvc_options.resolvers = options_resolvers;
    pvc_options.constant = options_constantResolver;
    pvc_options.specify = options_specifyResolver;
    pvc_options.defaultValue = options_defaultResolver;
    pvc.options = pvc_options;
    var pvc_OptionInfo = def.type().init(function(name, option, context, spec) {
        this.name = name;
        this.option = option;
        this._dv = this.value = def.get(spec, "value");
        this._resolve = def.get(spec, "resolve");
        var resolved = !this._resolve;
        this.isResolved = resolved;
        this.isSpecified = !1;
        this._setCalled = !1;
        this._context = context;
        this._cast = def.get(spec, "cast");
        this._getDefault = resolved ? null : def.get(spec, "getDefault");
        this.data = def.get(spec, "data");
    }).add({
        resolve: function() {
            if (!this.isResolved) {
                this.isResolved = !0;
                this._setCalled = !1;
                this._getFunProp("_resolve").call(this._context, this);
                if (!this._setCalled) {
                    this.isSpecified = !1;
                    var value = this._dynDefault();
                    null != value && (this.value = this._dv = value);
                }
            }
            return this;
        },
        specify: function(value) {
            return this.set(value, !1);
        },
        defaultValue: function(defaultValue) {
            arguments.length && this.set(defaultValue, !0);
            return this._dv;
        },
        cast: function(value) {
            if (null != value) {
                var cast = this._getFunProp("_cast");
                cast && (value = cast.call(this._context, value));
            }
            return value;
        },
        set: function(value, isDefault) {
            this._setCalled = !0;
            null != value && (value = this.cast(value));
            if (null == value) {
                value = this._dynDefault();
                if (null == value) {
                    if (!this.isSpecified) return this;
                    value = this._dv;
                }
                isDefault = !0;
            }
            if (isDefault) {
                this._dv = value;
                this.isSpecified || (this.value = value);
            } else {
                this.isResolved = this.isSpecified = !0;
                this.value = value;
            }
            return this;
        },
        _dynDefault: function() {
            var get = this._getFunProp("_getDefault");
            return get && this.cast(get.call(this._context, this));
        },
        _getFunProp: function(name) {
            var ctx, fun = this[name];
            fun && (ctx = this._context) && def.string.is(fun) && (fun = ctx[fun]);
            return fun;
        }
    });
    def.MetaType.subType(OptionsMetaType, {
        methods: {
            options: function(optionsDef) {
                return def.mixin(this.optionsDef, optionsDef), this;
            },
            _addInitSteps: function(steps) {
                function initOptions() {
                    this.option = pvc.options(type.optionsDef, this);
                }
                this.base(steps);
                var type = this;
                steps.push(initOptions);
            }
        }
    });
    var pvc_OptionBase = OptionsMetaType.Ctor;
    def("pvc.visual.OptionsBase", pvc_OptionBase.configure({
        init: function(chart, type, index, keyArgs) {
            this.type = type;
            this.chart = chart;
            this.index = null == index ? 0 : index;
            this.name = def.get(keyArgs, "name");
            this.id = def.indexedId(this.type, this.index);
            this.optionId = this._buildOptionId(keyArgs);
            var rs = this._resolvers = [];
            this._registerResolversFull(rs, keyArgs);
        },
        methods: {
            _buildOptionId: function(keyArgs) {
                return this.id;
            },
            _chartOption: function(name) {
                return this.chart.options[name];
            },
            _registerResolversFull: function(rs, keyArgs) {
                var fixed = def.get(keyArgs, "fixed");
                if (fixed) {
                    this._fixed = fixed;
                    rs.push(pvc.options.specify(function(optionInfo) {
                        return fixed[optionInfo.name];
                    }));
                }
                this._registerResolversNormal(rs, keyArgs);
                var defaults = def.get(keyArgs, "defaults");
                defaults && (this._defaults = defaults);
                rs.push(this._resolveDefault);
            },
            _registerResolversNormal: function(rs, keyArgs) {
                def.get(keyArgs, "byV1", 1) && this.chart.compatVersion() <= 1 && rs.push(this._resolveByV1OnlyLogic);
                this.name && def.get(keyArgs, "byName", 1) && rs.push(this._resolveByName);
                def.get(keyArgs, "byId", 1) && rs.push(this._resolveByOptionId);
                def.get(keyArgs, "byNaked", !this.index) && rs.push(this._resolveByNaked);
            },
            _resolveFull: function(optionInfo) {
                for (var rs = this._resolvers, i = 0, L = rs.length; L > i; i++) if (rs[i].call(this, optionInfo)) return !0;
                return !1;
            },
            _resolveFixed: pvc.options.specify(function(optionInfo) {
                return this._fixed ? this._fixed[optionInfo.name] : void 0;
            }),
            _resolveByV1OnlyLogic: function(optionInfo) {
                var resolverV1, data = optionInfo.data;
                return data && (resolverV1 = data.resolveV1) ? resolverV1.call(this, optionInfo) : void 0;
            },
            _resolveByName: pvc.options.specify(function(optionInfo) {
                return this.name ? this._chartOption(this.name + def.firstUpperCase(optionInfo.name)) : void 0;
            }),
            _resolveByOptionId: pvc.options.specify(function(optionInfo) {
                return this._chartOption(this.optionId + def.firstUpperCase(optionInfo.name));
            }),
            _resolveByNaked: pvc.options.specify(function(optionInfo) {
                return this._chartOption(def.firstLowerCase(optionInfo.name));
            }),
            _resolveDefault: function(optionInfo) {
                var resolverDefault, data = optionInfo.data;
                if (data && (resolverDefault = data.resolveDefault) && resolverDefault.call(this, optionInfo)) return !0;
                if (this._defaults) {
                    var value = this._defaults[optionInfo.name];
                    if (void 0 !== value) return optionInfo.defaultValue(value), !0;
                }
            },
            _specifyChartOption: function(optionInfo, asName) {
                var value = this._chartOption(asName);
                return null != value ? (optionInfo.specify(value), !0) : void 0;
            }
        }
    }));
    def.scope(function() {
        function parseBitsCore(ibits) {
            ibits = Math.floor(ibits);
            return isNaN(ibits) ? null : -1 > ibits || !isFinite(ibits) ? -1 : ibits;
        }
        var I = def.makeEnum([ "Interactive", "ShowsActivity", "ShowsSelection", "ShowsTooltip", "Selectable", "Unselectable", "Hoverable", "Clickable", "DoubleClickable", "SelectableByClick", "SelectableByRubberband", "SelectableByFocusWindow", "Animatable" ]);
        I.ShowsInteraction = I.ShowsActivity | I.ShowsSelection;
        I.Actionable = I.Hoverable | I.Clickable | I.DoubleClickable | I.SelectableByClick;
        I.HandlesEvents = I.Actionable | I.ShowsTooltip;
        I.HandlesClickEvent = I.Clickable | I.SelectableByClick;
        def("pvc.visual.Interactive", def.Object.extend({
            "type.methods": [ I, {
                ShowsAny: I.ShowsInteraction | I.ShowsTooltip,
                SelectableAny: I.Selectable | I.SelectableByClick | I.SelectableByRubberband | I.SelectableByFocusWindow,
                parseBits: function(value) {
                    if (null == value) return null;
                    if ("number" == typeof value) return parseBitsCore(value);
                    "string" != typeof value && (value = String(value));
                    var ibits = parseBitsCore(+value);
                    if (null !== ibits) return ibits;
                    value.split(/\s*\|\s*/).forEach(function(sbit) {
                        var ibit = def.getOwn(I, sbit);
                        null != ibit && (null === ibits ? ibits = ibit : ibits |= ibit);
                    });
                    return ibits;
                }
            } ],
            methods: [ {
                _ibits: -1,
                ibits: function() {
                    return this._ibits;
                }
            }, def.query(def.ownKeys(I)).object({
                name: def.firstLowerCase,
                value: function(p) {
                    var mask = I[p];
                    return function() {
                        return !!(this.ibits() & mask);
                    };
                }
            }) ]
        }));
    });
    pvc.color = {
        scale: pvc_colorScale,
        scales: pvc_colorScales,
        toGray: pvc.toGrayScale,
        isGray: pvc_colorIsGray
    };
    def.type("pvc.color.ScalesBuild").init(function(keyArgs) {
        this.keyArgs = keyArgs;
        this.data = keyArgs.data || def.fail.argumentRequired("keyArgs.data");
        this.domainDimName = keyArgs.colorDimension || def.fail.argumentRequired("keyArgs.colorDimension");
        this.domainDim = this.data.dimensions(this.domainDimName);
        var dimType = this.domainDim.type;
        if (dimType.isComparable) this.domainComparer = function(a, b) {
            return dimType.compare(a, b);
        }; else {
            this.domainComparer = null;
            def.log("Color value dimension should be comparable. Generated color scale may be invalid.");
        }
        this.nullRangeValue = keyArgs.colorMissing ? pv.color(keyArgs.colorMissing) : pv.Color.transparent;
        this.domainRangeCountDif = 0;
    }).add({
        build: function() {
            this.range = this._getRange();
            this.desiredDomainCount = this.range.length + this.domainRangeCountDif;
            var domain = this._getDomain();
            return this._createScale(domain);
        },
        buildMap: function() {
            this.range = this._getRange();
            this.desiredDomainCount = this.range.length + this.domainRangeCountDif;
            var createCategoryScale;
            if (this.keyArgs.normPerBaseCategory) createCategoryScale = function(leafData) {
                var domain = this._ensureDomain(null, !1, leafData);
                return this._createScale(domain);
            }; else {
                var domain = this._getDomain(), scale = this._createScale(domain);
                createCategoryScale = def.fun.constant(scale);
            }
            return this._createCategoryScalesMap(createCategoryScale);
        },
        _createScale: def.abstractMethod,
        _createCategoryScalesMap: function(createCategoryScale) {
            return this.data.children().object({
                name: function(leafData) {
                    return leafData.absKey;
                },
                value: createCategoryScale,
                context: this
            });
        },
        _getRange: function() {
            var keyArgs = this.keyArgs, range = keyArgs.colors || [ "red", "yellow", "green" ];
            null != keyArgs.colorMin && null != keyArgs.colorMax ? range = [ keyArgs.colorMin, keyArgs.colorMax ] : null != keyArgs.colorMin ? range.unshift(keyArgs.colorMin) : null != keyArgs.colorMax && range.push(keyArgs.colorMax);
            return range.map(function(c) {
                return pv.color(c);
            });
        },
        _getDataExtent: function(data) {
            var extent = data.dimensions(this.domainDimName).extent({
                visible: !0
            });
            if (!extent) return null;
            var min = extent.min.value, max = extent.max.value;
            max == min && (max >= 1 ? min = max - 1 : max = min + 1);
            return {
                min: min,
                max: max
            };
        },
        _getDomain: function() {
            var domain = this.keyArgs.colorDomain;
            if (null != domain) {
                domain = domain.slice();
                this.domainComparer && domain.sort(this.domainComparer);
                domain.length > this.desiredDomainCount && (domain = domain.slice(0, this.desiredDomainCount));
            } else domain = [];
            return this._ensureDomain(domain, !0, this.data);
        },
        _ensureDomain: function(domain, doDomainPadding, data) {
            var extent;
            if (domain && doDomainPadding) {
                var domainPointsMissing = this.desiredDomainCount - domain.length;
                if (domainPointsMissing > 0) {
                    extent = this._getDataExtent(data);
                    if (extent) switch (domainPointsMissing) {
                      case 1:
                        this.domainComparer ? def.array.insert(domain, extent.max, this.domainComparer) : domain.push(extent.max);
                        break;

                      case 2:
                        if (this.domainComparer) {
                            def.array.insert(domain, extent.min, this.domainComparer);
                            def.array.insert(domain, extent.max, this.domainComparer);
                        } else {
                            domain.unshift(extent.min);
                            domain.push(extent.max);
                        }
                        break;

                      default:
                        def.debug >= 2 && def.log("Ignoring option 'colorDomain' due to unsupported length." + def.format(" Should have '{0}', but instead has '{1}'.", [ this.desiredDomainCount, domain.length ]));
                        domain = null;
                    }
                }
            }
            if (!domain) {
                extent || (extent = this._getDataExtent(data));
                if (extent) {
                    var min = extent.min, max = extent.max, step = (max - min) / (this.desiredDomainCount - 1);
                    domain = pv.range(min, max + step, step);
                }
            }
            return domain;
        }
    });
    def.type("pvc.color.LinearScalesBuild", pvc.color.ScalesBuild).add({
        _createScale: function(domain) {
            var scale = pv.Scale.linear();
            domain && scale.domain.apply(scale, domain);
            scale.range.apply(scale, this.range);
            return scale;
        }
    });
    def.type("pvc.color.DiscreteScalesBuild", pvc.color.ScalesBuild).init(function(keyArgs) {
        this.base(keyArgs);
        this.domainRangeCountDif = 1;
    }).add({
        _createScale: function(domain) {
            function scale(val) {
                if (null == val) return nullRangeValue;
                for (var i = 0; Dl > i; i++) if (val <= domain[i + 1]) return range[i];
                return range[Rl];
            }
            var Dl = domain.length - 1, range = this.range, nullRangeValue = this.nullRangeValue, Rl = range.length - 1;
            def.copy(scale, pv.Scale.common);
            scale.domain = function() {
                return domain;
            };
            scale.range = function() {
                return range;
            };
            return scale;
        }
    });
    def.type("pvc.visual.Context").init(function(panel, mark, scene) {
        this.chart = panel.chart;
        this.panel = panel;
        visualContext_update.call(this, mark, scene);
    }).add({
        isPinned: !1,
        pin: function() {
            this.isPinned = !0;
            return this;
        },
        compatVersion: function() {
            return this.panel.compatVersion();
        },
        getCompatFlag: function(flagName) {
            return this.panel.getCompatFlag(flagName);
        },
        finished: function(v) {
            return this.sign.finished(v);
        },
        delegate: function(dv) {
            return this.sign.delegate(dv);
        },
        getV1Series: function() {
            var s;
            return def.nullyTo(this.scene.firstAtoms && (s = this.scene.firstAtoms[this.panel._getV1DimName("series")]) && s.rawValue, "Series");
        },
        getV1Category: function() {
            var c;
            return this.scene.firstAtoms && (c = this.scene.firstAtoms[this.panel._getV1DimName("category")]) && c.rawValue;
        },
        getV1Value: function() {
            var v;
            return this.scene.firstAtoms && (v = this.scene.firstAtoms[this.panel._getV1DimName("value")]) && v.value;
        },
        getV1Datum: function() {
            return this.panel._getV1Datum(this.scene);
        },
        get: function(name, prop) {
            return this.scene.get(name, prop);
        },
        getSeries: function() {
            return this.scene.get("series");
        },
        getCategory: function() {
            return this.scene.get("category");
        },
        getValue: function() {
            return this.scene.get("value");
        },
        getTick: function() {
            return this.scene.get("tick");
        },
        getX: function() {
            return this.scene.get("x");
        },
        getY: function() {
            return this.scene.get("y");
        },
        getColor: function() {
            return this.scene.get("color");
        },
        getSize: function() {
            return this.scene.get("size");
        },
        getSeriesLabel: function() {
            return this.scene.get("series", "label");
        },
        getCategoryLabel: function() {
            return this.scene.get("category", "label");
        },
        getValueLabel: function() {
            return this.scene.get("value", "label");
        },
        getTickLabel: function() {
            return this.scene.get("tick", "label");
        },
        getXLabel: function() {
            return this.scene.get("x", "label");
        },
        getYLabel: function() {
            return this.scene.get("y", "label");
        },
        getColorLabel: function() {
            return this.scene.get("color", "label");
        },
        getSizeLabel: function() {
            return this.scene.get("size", "label");
        },
        select: function(ka) {
            return this.scene.select(ka);
        },
        toggleVisible: function() {
            return this.scene.toggleVisible();
        },
        click: function() {
            var me = this;
            me.clickable() && me.panel._onClick(me);
            if (me.selectableByClick()) {
                var ev = me.event;
                me.select({
                    replace: !ev || !(ev.ctrlKey || ev.metaKey)
                });
            }
        },
        doubleClick: function() {
            this.doubleClickable() && this.panel._onDoubleClick(this);
        },
        clickable: function() {
            var me = this;
            return (me.sign ? me.sign.clickable() : me.panel.clickable()) && (!me.scene || me.scene.clickable());
        },
        selectableByClick: function() {
            var me = this;
            return (me.sign ? me.sign.selectableByClick() : me.panel.selectableByClick()) && (!me.scene || me.scene.selectableByClick());
        },
        doubleClickable: function() {
            var me = this;
            return (me.sign ? me.sign.doubleClickable() : me.panel.doubleClickable()) && (!me.scene || me.scene.doubleClickable());
        },
        hoverable: function() {
            var me = this;
            return (me.sign ? me.sign.hoverable() : me.panel.hoverable()) && (!me.scene || me.scene.hoverable());
        }
    });
    if (Object.defineProperty) try {
        Object.defineProperty(pvc.visual.Context.prototype, "parent", {
            get: function() {
                throw def.error.operationInvalid("The 'this.parent.index' idiom has no equivalent in this version. Please try 'this.pvMark.parent.index'.");
            }
        });
    } catch (ex) {}
    pvc.text = {
        getFitInfo: function(w, h, text, font, diagMargin) {
            if ("" === text) return {
                h: !0,
                v: !0,
                d: !0
            };
            var len = pv.Text.measureWidth(text, font);
            return {
                h: w >= len,
                v: h >= len,
                d: len <= Math.sqrt(w * w + h * h) - diagMargin
            };
        },
        trimToWidthB: function(len, text, font, trimTerminator, before) {
            var terminLen = pv.Text.measureWidth(trimTerminator, font), clipLen = 1.5 * terminLen;
            return pvc.text.trimToWidth(len, text, font, trimTerminator, before, clipLen);
        },
        trimToWidth: function(len, text, font, trimTerminator, before, clipLen) {
            if ("" === text) return text;
            var textLen = pv.Text.measureWidth(text, font);
            textLen > len && (text = pvc.text.trimToWidthBin(len, text, font, trimTerminator, before, clipLen));
            return text;
        },
        trimToWidthBin: function(len, text, font, trimTerminator, before, clipLen) {
            function slice(text, sBefore, sAfter) {
                return before ? text.slice(sBefore) : text.slice(0, sAfter);
            }
            var highLen = pv.Text.measureWidth(text, font);
            if (len >= highLen) return text;
            for (var mid, lowLen = 0, targetLen = Math.max(0, len - pv.Text.measureWidth(trimTerminator, font)), tCount = text.length, high = tCount - 1, low = 0; high > low && high > 0; ) {
                var targetRelativePosition = (targetLen - lowLen) / (highLen - lowLen);
                mid = Math.ceil(low * (1 - targetRelativePosition) + high * targetRelativePosition);
                var textMid = slice(text, tCount - mid, mid), textLen = pv.Text.measureWidth(textMid, font);
                if (textLen > targetLen) {
                    high = mid - 1;
                    highLen = textLen;
                } else {
                    if (!(pv.Text.measureWidth(slice(text, tCount - mid - 1, mid + 1), font) < targetLen)) return clipLen && clipLen >= textLen ? "" : before ? trimTerminator + textMid : textMid + trimTerminator;
                    low = mid + 1;
                    lowLen = textLen;
                }
            }
            text = slice(text, tCount - high, high);
            textLen = pv.Text.measureWidth(text, font);
            return clipLen && clipLen >= textLen ? "" : before ? trimTerminator + text : text + trimTerminator;
        },
        justify: function(text, lineWidth, font) {
            var lines = [];
            if (lineWidth < pv.Text.measureWidth("a", font)) return lines;
            for (var words = (text || "").split(/\s+/), line = ""; words.length; ) {
                var word = words.shift();
                if (word) {
                    var nextLine = line ? line + " " + word : word;
                    if (pv.Text.measureWidth(nextLine, font) > lineWidth) {
                        line && lines.push(line);
                        line = word;
                    } else line = nextLine;
                }
            }
            line && lines.push(line);
            return lines;
        },
        getLabelBBox: function(textWidth, textHeight, align, baseline, angle, margin) {
            var polygon = pv.Label.getPolygon(textWidth, textHeight, align, baseline, angle, margin), bbox = polygon.bbox();
            bbox.source = polygon;
            bbox.sourceAngle = angle;
            bbox.sourceAlign = align;
            bbox.sourceTextWidth = textWidth;
            return bbox;
        }
    };
    def.space("pvc.trends", function(trends) {
        var _trends = {};
        def.set(trends, "define", function(type, trendSpec) {
            type || def.fail.argumentRequired("type");
            trendSpec || def.fail.argumentRequired("trendSpec");
            def.object.is(trendSpec) || def.fail.argumentInvalid("trendSpec", "Must be a trend specification object.");
            type = ("" + type).toLowerCase();
            def.debug >= 2 && def.hasOwn(_trends, type) && def.log(def.format("[WARNING] A trend type with the name '{0}' is already defined.", [ type ]));
            var label = trendSpec.label || def.fail.argumentRequired("trendSpec.label"), model = trendSpec.model || def.fail.argumentRequired("trendSpec.model");
            def.fun.is(model) || def.fail.argumentInvalid("trendSpec.mode", "Must be a function.");
            var trendInfo = {
                dataPartAtom: {
                    v: "trend",
                    f: label
                },
                type: type,
                label: label,
                model: model
            };
            _trends[type] = trendInfo;
        }, "get", function(type) {
            type || def.fail.argumentRequired("type");
            return def.getOwn(_trends, type) || def.fail.operationInvalid("Undefined trend type '{0}'.", [ type ]);
        }, "has", function(type) {
            return def.hasOwn(_trends, type);
        }, "types", function() {
            return def.ownKeys(_trends);
        });
        trends.define("linear", {
            label: "Linear trend",
            model: function(options) {
                for (var rows = def.get(options, "rows"), funX = def.get(options, "x"), funY = def.get(options, "y"), i = 0, N = 0, sumX = 0, sumY = 0, sumXY = 0, sumXX = 0, parseNum = function(value) {
                    return null != value ? +value : NaN;
                }; rows.next(); ) {
                    var row = rows.item, x = funX ? parseNum(funX(row)) : i;
                    if (!isNaN(x)) {
                        var y = parseNum(funY(row));
                        if (!isNaN(y)) {
                            N++;
                            sumX += x;
                            sumY += y;
                            sumXY += x * y;
                            sumXX += x * x;
                        }
                    }
                    i++;
                }
                if (N >= 2) {
                    var avgX = sumX / N, avgY = sumY / N, avgXY = sumXY / N, avgXX = sumXX / N, den = avgXX - avgX * avgX, beta = 0 === den ? 0 : (avgXY - avgX * avgY) / den, alpha = avgY - beta * avgX;
                    return {
                        alpha: alpha,
                        beta: beta,
                        reset: def.noop,
                        sample: function(x) {
                            return alpha + beta * +x;
                        }
                    };
                }
            }
        });
        trends.define("moving-average", {
            label: "Moving average",
            model: function(options) {
                var W = Math.max(+(def.get(options, "periods") || 3), 2), sum = 0, avgValues = [];
                return {
                    reset: function() {
                        sum = 0;
                        avgValues.length = 0;
                    },
                    sample: function(x, y, i) {
                        var L = W;
                        if (null != y) {
                            avgValues.unshift(y);
                            sum += y;
                            L = avgValues.length;
                            if (L > W) {
                                sum -= avgValues.pop();
                                L = W;
                            }
                        }
                        return sum / L;
                    }
                };
            }
        });
        trends.define("weighted-moving-average", {
            label: "Weighted Moving average",
            model: function(options) {
                var W = Math.max(+(def.get(options, "periods") || 3), 2), sum = 0, numer = 0, avgValues = [], L = 0, denom = 0;
                return {
                    reset: function() {
                        sum = numer = denom = L = 0;
                        avgValues.length = 0;
                    },
                    sample: function(x, y) {
                        if (null != y) if (W > L) {
                            avgValues.push(y);
                            L++;
                            denom += L;
                            numer += L * y;
                            sum += y;
                        } else {
                            numer += L * y - sum;
                            sum += y - avgValues[0];
                            for (var j = 1; W > j; j++) avgValues[j - 1] = avgValues[j];
                            avgValues[W - 1] = y;
                        }
                        return numer / denom;
                    }
                };
            }
        });
    });
    pvc.spec = {
        on: pvc_spec_addEvent.bind(null, "on"),
        before: pvc_spec_addEvent.bind(null, "before"),
        after: pvc_spec_addEvent.bind(null, "after")
    };
    def("pvc.visual.SlidingWindow", pvc.visual.OptionsBase.extend({
        init: function(chart) {
            this.base(chart, "slidingWindow", 0, {
                byNaked: !1
            });
            this.length = this.option("Length");
        },
        methods: {
            length: null,
            dimension: null,
            select: slidingWindow_defaultSelect,
            initFromOptions: function() {
                if (this.length) {
                    this.dimension = this.option("Dimension");
                    this.override("select", this.option("Select"));
                }
            },
            setDataFilter: function(data) {
                data.select = this.select.bind(this);
            },
            setDimensionsOptions: function(complexType) {
                var chart = this.chart, dimOpts = chart.options.dimensions, dimGroupOpts = chart.options.dimensionGroups;
                complexType.dimensionsList().forEach(function(dimType) {
                    if (dimType.isDiscrete) {
                        var dimName = dimType.name, visualRoles = chart.visualRolesOf(dimName, !0);
                        if (visualRoles) {
                            var dimSpecs = dimOpts && dimOpts[dimName];
                            if (!dimSpecs || !dimSpecs.comparer) {
                                var dimGroup = cdo.DimensionType.dimensionGroupName(dimName), dimGroupSpecs = dimGroupOpts && dimGroupOpts[dimGroup];
                                if (!dimGroupSpecs || !dimGroupSpecs.comparer) {
                                    dimType.setComparer(def.ascending);
                                    visualRoles.forEach(function(role) {
                                        role.grouping.bind(complexType);
                                    });
                                }
                            }
                        }
                    }
                });
            },
            setLayoutPreservation: function(chart) {
                null == chart.options.preserveLayout && (chart.options.preserveLayout = !0);
            },
            setAxesDefaults: function(chart) {
                chart.axesList.forEach(function(axis) {
                    var role = axis.role;
                    if (role) {
                        1 === role.grouping.dimensionNames().length && role.grouping.firstDimensionName() === this.dimension && this._setAxisFixedRatio(axis);
                        "color" === axis.type && axis.option.defaults({
                            PreserveMap: !0
                        });
                    }
                }, this);
            },
            _setAxisFixedRatio: function(axis) {
                var axisOption = axis.option;
                if (axisOption.isDefined("FixedLength") && axisOption.isDefined("PreserveRatio")) {
                    this.option.isSpecified("Length") && axis.option.defaults({
                        FixedLength: this.length
                    });
                    axis.option.defaults({
                        PreserveRatio: !0
                    });
                }
            }
        },
        options: {
            Dimension: {
                resolve: "_resolveFull",
                cast: function(name) {
                    return pvc.parseDimensionName(name, this.chart);
                },
                getDefault: slidingWindow_defaultDimensionName
            },
            Length: {
                resolve: "_resolveFull",
                cast: function(interval) {
                    return pv.parseDatePrecision(interval, null);
                }
            },
            Select: {
                resolve: "_resolveFull",
                cast: def.fun.as,
                getDefault: function() {
                    return slidingWindow_defaultSelect.bind(this);
                }
            }
        }
    }));
    def.MetaType.subType(SceneMetaType, {
        methods: {
            variable: function(name, impl) {
                var methods;
                if (this._vars[name]) void 0 !== impl && (methods = def.set({}, "_" + name + "EvalCore", def.fun.to(impl))); else {
                    this._vars[name] = !0;
                    var instProto = this.Ctor.prototype;
                    methods = {};
                    var nameEval = "_" + name + "Eval";
                    methods[name] = scene_createVarMainMethod(name, nameEval);
                    var nameEvalCore = nameEval + "Core";
                    def.hasOwn(instProto, nameEval) || (methods[nameEval] = def.methodCaller(nameEvalCore));
                    def.hasOwn(instProto, nameEvalCore) || (methods[nameEvalCore] = def.fun.to(void 0 === impl ? null : impl));
                }
                methods && this.methods(methods);
                return this;
            }
        }
    });
    var pvc_Scene = SceneMetaType.Ctor;
    def("pvc.visual.Scene", pvc_Scene.configure({
        init: function(parent, keyArgs) {
            def.debug >= 4 && (this.id = def.nextId("scene"));
            this._renderId = 0;
            this.renderState = {};
            pv.Dom.Node.call(this, null);
            this.parent = parent || null;
            if (parent) {
                this.root = parent.root;
                var index = def.get(keyArgs, "index", null);
                parent.insertAt(this, index);
            } else {
                this.root = this;
                this._active = null;
                this._panel = def.get(keyArgs, "panel") || def.fail.argumentRequired("panel", "Argument is required on root scene.");
            }
            var first, group, datum, datums, groups, atoms, firstAtoms, dataSource = def.array.to(def.get(keyArgs, "source"));
            if (dataSource && dataSource.length) {
                this.source = dataSource;
                first = dataSource[0];
                if (first instanceof cdo.Data) {
                    group = first;
                    groups = dataSource;
                    datum = group.firstDatum() || def.query(groups).select(function(g) {
                        return g.firstDatum();
                    }).first(def.notNully);
                } else {
                    first instanceof cdo.Datum || def.assert("not a datum");
                    datum = first;
                    datums = dataSource;
                }
                atoms = first.atoms;
                firstAtoms = datum && datum.atoms || atoms;
            } else atoms = firstAtoms = parent ? Object.create(parent.atoms) : {};
            this.atoms = atoms;
            this.firstAtoms = firstAtoms;
            groups && (this.groups = groups);
            group && (this.group = group);
            datums && (this._datums = datums);
            datum && (this.datum = datum);
            (!first || first.isNull) && (this.isNull = !0);
            this.vars = parent ? Object.create(parent.vars) : {};
        },
        methods: [ pv.Dom.Node, pvc.visual.Interactive, {
            source: null,
            groups: null,
            group: null,
            _datums: null,
            datum: null,
            isNull: !1,
            get: function(name, prop) {
                var avar = this.vars[name];
                return avar && avar[prop || "value"];
            },
            getSeries: function() {
                return this.get("series");
            },
            getCategory: function() {
                return this.get("category");
            },
            getValue: function() {
                return this.get("value");
            },
            getTick: function() {
                return this.get("tick");
            },
            getX: function() {
                return this.get("x");
            },
            getY: function() {
                return this.get("y");
            },
            getColor: function() {
                return this.get("color");
            },
            getSize: function() {
                return this.get("size");
            },
            getSeriesLabel: function() {
                return this.get("series", "label");
            },
            getCategoryLabel: function() {
                return this.get("category", "label");
            },
            getValueLabel: function() {
                return this.get("value", "label");
            },
            getTickLabel: function() {
                return this.get("tick", "label");
            },
            getXLabel: function() {
                return this.get("x", "label");
            },
            getYLabel: function() {
                return this.get("y", "label");
            },
            getColorLabel: function() {
                return this.get("color", "label");
            },
            getSizeLabel: function() {
                return this.get("size", "label");
            },
            data: function() {
                var data = this.group;
                if (!data) {
                    for (var scene = this; !data && (scene = scene.parent); ) data = scene.group;
                    data || (data = this.panel.data);
                }
                return data;
            },
            allGroup: function() {
                return 1 === this.groups.length ? this.group : this._allGroup || (this._allGroup = this._calcAllGroup());
            },
            _calcAllGroup: function() {
                var groups = this.groups;
                return groups && groups.length ? new cdo.Data({
                    linkParent: cdo.Data.lca(groups),
                    datums: this.datums(),
                    where: function(d) {
                        return !!groups && groups.some(function(g) {
                            return g.contains(d);
                        });
                    }
                }) : new cdo.Data({
                    linkParent: this.data(),
                    datums: this.datums()
                });
            },
            datums: function() {
                return this.groups ? def.query(this.groups).selectMany(function(g) {
                    return g.datums();
                }) : this._datums ? def.query(this._datums) : def.query();
            },
            format: function(mask) {
                return def.format(mask, this._formatScope, this);
            },
            _formatScope: function(prop) {
                if ("#" === prop.charAt(0)) {
                    prop = prop.substr(1).split(".");
                    if (prop.length > 2) throw def.error.operationInvalid("Scene format mask is invalid.");
                    var atom = this.firstAtoms[prop[0]];
                    if (atom) {
                        if (prop.length > 1) switch (prop[1]) {
                          case "value":
                            return atom.value;

                          case "label":
                            break;

                          default:
                            throw def.error.operationInvalid("Scene format mask is invalid.");
                        }
                        return atom;
                    }
                    return null;
                }
                return def.getPath(this.vars, prop);
            },
            isRoot: function() {
                return this.root === this;
            },
            panel: function() {
                return this.root._panel;
            },
            chart: function() {
                return this.root._panel.chart;
            },
            compatVersion: function() {
                return this.root._panel.compatVersion();
            },
            children: function() {
                var cs = this.childNodes;
                return cs.length ? def.query(cs) : def.query();
            },
            leafs: function() {
                function getFirstLeafFrom(leaf) {
                    for (;leaf.childNodes.length; ) leaf = leaf.childNodes[0];
                    return leaf;
                }
                var root = this;
                return def.query(function(nextIndex) {
                    if (!nextIndex) {
                        var item = getFirstLeafFrom(root);
                        return item === root ? 0 : (this.item = item, 1);
                    }
                    var next = this.item.nextSibling;
                    if (next) return this.item = next, 1;
                    for (var current = this.item; current !== root && (current = current.parentNode); ) if (next = current.nextSibling) return this.item = getFirstLeafFrom(next), 
                    1;
                    return 0;
                });
            },
            anyInteraction: function() {
                return !!this.root._active || this.anySelected();
            },
            isActive: !1,
            getIsActive: function() {
                return (this.ownerScene || this).isActive;
            },
            setActive: function(isActive) {
                isActive = !!isActive;
                this.getIsActive() === isActive || !isActive && scene_isPointSwitchingToHoverableSign(pv.event) || this.chart()._setActiveScene(isActive ? this : null);
            },
            clearActive: function() {
                return !!this.active() && this.chart()._setActiveScene(null);
            },
            _setActive: function(isActive) {
                this.isActive !== isActive && rootScene_setActive.call(this.root, this.isActive ? null : this);
            },
            _clearActive: function() {
                return rootScene_setActive.call(this.root, null);
            },
            anyActive: function() {
                return !!this.root._active;
            },
            active: function() {
                return this.root._active;
            },
            activeSeries: function() {
                var seriesVar, active = this.active();
                return active && (seriesVar = active.vars.series) ? seriesVar.value : void 0;
            },
            isActiveSeries: function() {
                if (this.isActive) return !0;
                var isActiveSeries = this.renderState.isActiveSeries;
                if (null == isActiveSeries) {
                    var activeSeries;
                    isActiveSeries = void 0 !== (activeSeries = this.activeSeries()) && activeSeries === this.vars.series.value;
                    this.renderState.isActiveSeries = isActiveSeries;
                }
                return isActiveSeries;
            },
            isActiveDatum: function() {
                return this.isActive ? !0 : !1;
            },
            isActiveDescendantOrSelf: function() {
                return this.isActive || def.lazy(this.renderState, "isActiveDescOrSelf", this._calcIsActiveDescOrSelf, this);
            },
            _calcIsActiveDescOrSelf: function() {
                var scene = this.active();
                if (scene) for (;scene = scene.parent; ) if (scene === this) return !0;
                return !1;
            },
            isVisible: function() {
                return this._visibleInfo().is;
            },
            anyVisible: function() {
                return this._visibleInfo().any;
            },
            _visibleInfo: function() {
                return def.lazy(this.renderState, "visibleInfo", this._createVisibleInfo, this);
            },
            _createVisibleInfo: function() {
                var any = this.chart().data.owner.visibleCount() > 0, isSelected = any && this.datums().any(def.propGet("isVisible"));
                return {
                    any: any,
                    is: isSelected
                };
            },
            isSelected: function() {
                return this._selectedInfo().is;
            },
            anySelected: function() {
                return this._selectedInfo().any;
            },
            _selectedInfo: function() {
                return def.lazy(this.renderState, "selectedInfo", this._createSelectedInfo, this);
            },
            _createSelectedInfo: function() {
                var any = this.chart().data.owner.selectedCount() > 0, isSelected = any && this.datums().any(cdo.Datum.isSelected);
                return {
                    any: any,
                    is: isSelected
                };
            },
            select: function(ka) {
                var me = this, datums = me.datums().array();
                if (datums.length) {
                    var chart = me.chart();
                    chart._updatingSelections(function() {
                        datums = chart._onUserSelection(datums);
                        datums && datums.length && (chart.options.ctrlSelectMode && def.get(ka, "replace", !0) ? chart.data.replaceSelected(datums) : cdo.Data.toggleSelected(datums));
                    });
                }
            },
            isSelectedDescendantOrSelf: function() {
                return this.isSelected() || def.lazy(this.renderState, "isSelectedDescOrSelf", this._calcIsSelectedDescOrSelf, this);
            },
            _calcIsSelectedDescOrSelf: function() {
                var child = this.firstChild;
                if (child) do if (child.isSelectedDescendantOrSelf()) return !0; while (child = child.nextSibling);
                return !1;
            },
            toggleVisible: function() {
                cdo.Data.toggleVisible(this.datums()) && this.chart().render(!0, !0, !1);
            },
            asView: function(viewSpec) {
                this.chart()._processViewSpec(viewSpec);
                return this._asView(viewSpec.dimsKey, viewSpec.dimNames);
            },
            _asView: function(dimsKey, dimNames) {
                if (this.ownerScene) return this.ownerScene._asView(dimsKey, dimNames);
                var views = def.lazy(this, "_viewCache"), view = def.getOwn(views, dimsKey);
                void 0 === view && (views[dimsKey] = view = this._calcView(dimNames));
                return view;
            },
            _calcView: function(normDimNames) {
                for (var atom, dimName, atoms = null, i = 0, L = normDimNames.length; L > i; i++) {
                    dimName = normDimNames[i];
                    atom = this.atoms[dimName];
                    if (!atom || null == atom.value) return null;
                    (atoms || (atoms = {}))[dimName] = atom;
                }
                return new cdo.Complex(this.data().owner, atoms, normDimNames, null, !0, !1);
            }
        } ]
    }));
    var pvc_ValueLabelVar = pvc.visual.ValueLabelVar = function(value, label, rawValue, absLabel) {
        this.value = value;
        this.label = label;
        void 0 !== rawValue && (this.rawValue = rawValue);
        void 0 !== absLabel && (this.absLabel = absLabel);
    };
    def.set(pvc_ValueLabelVar.prototype, "rawValue", void 0, "absLabel", void 0, "setValue", function(v) {
        return this.value = v, this;
    }, "setLabel", function(v) {
        return this.label = v, this;
    }, "clone", function() {
        return new pvc_ValueLabelVar(this.value, this.label, this.rawValue);
    }, "toString", function() {
        var label = this.label || this.value;
        return null == label ? "" : "string" != typeof label ? "" + label : label;
    });
    pvc_ValueLabelVar.fromComplex = function(complex) {
        return complex ? new pvc_ValueLabelVar(complex.value, complex.label, complex.rawValue, complex.absLabel) : new pvc_ValueLabelVar(null, "", null);
    };
    pvc_ValueLabelVar.fromAtom = pvc_ValueLabelVar.fromComplex;
    def.space("pvc.visual").TraversalMode = def.makeEnum([ "Tree", "FlattenLeafs", "FlattenDfsPre", "FlattenDfsPost" ], {
        all: "AllMask"
    });
    def.type("pvc.visual.Role").init(function(name, keyArgs) {
        this.name = name;
        this.label = def.get(keyArgs, "label") || def.titleFromName(name);
        this.index = def.get(keyArgs, "index") || 0;
        this.plot = def.get(keyArgs, "plot");
        this._legend = {
            visible: !0
        };
        this.dimensionDefaults = def.get(keyArgs, "dimensionDefaults") || {};
        def.get(keyArgs, "isRequired", !1) && (this.isRequired = !0);
        def.get(keyArgs, "autoCreateDimension", !1) && (this.autoCreateDimension = !0);
        var defaultSourceRoleName = def.get(keyArgs, "defaultSourceRole");
        defaultSourceRoleName && (this.defaultSourceRoleName = this.plot ? this.plot.ensureAbsRoleRef(defaultSourceRoleName) : defaultSourceRoleName);
        var defaultDimensionName = def.get(keyArgs, "defaultDimension");
        if (defaultDimensionName) {
            this.defaultDimensionName = defaultDimensionName;
            var match = defaultDimensionName.match(/^(.*?)(\*)?$/);
            this.defaultDimensionGroup = match[1];
            this.defaultDimensionGreedy = !!match[2];
        }
        var rootLabel = def.get(keyArgs, "rootLabel");
        null != rootLabel && (this.rootLabel = rootLabel);
        var traversalModes = def.get(keyArgs, "traversalModes");
        traversalModes && this.setTraversalModes(traversalModes);
        var traversalMode = def.get(keyArgs, "traversalMode");
        traversalMode && this.setTraversalMode(traversalMode);
        if (!defaultDimensionName && this.autoCreateDimension) throw def.error.argumentRequired("defaultDimension");
        var requireSingleDimension = def.get(keyArgs, "requireSingleDimension"), requireIsDiscrete = def.get(keyArgs, "requireIsDiscrete"), requireContinuous = null != requireIsDiscrete && !requireIsDiscrete;
        null == requireSingleDimension && (requireSingleDimension = requireContinuous);
        if (!requireIsDiscrete && def.get(keyArgs, "isMeasure")) {
            this.isMeasure = !0;
            var isNormalized = def.get(keyArgs, "isNormalized");
            (isNormalized || def.get(keyArgs, "isPercent")) && (this.isPercent = !0);
            isNormalized && (this.isNormalized = !0);
        }
        var valueType = def.get(keyArgs, "valueType", null);
        valueType !== this.valueType && (this.valueType = this.dimensionDefaults.valueType = valueType);
        requireSingleDimension !== this.requireSingleDimension && (this.requireSingleDimension = requireSingleDimension);
        null != requireIsDiscrete && (this.requireIsDiscrete = this.dimensionDefaults.isDiscrete = !!requireIsDiscrete);
    }).add({
        isRequired: !1,
        requireSingleDimension: !1,
        valueType: null,
        requireIsDiscrete: null,
        isMeasure: !1,
        isNormalized: !1,
        isPercent: !1,
        defaultSourceRoleName: null,
        defaultDimensionName: null,
        defaultDimensionGroup: null,
        defaultDimensionGreedy: null,
        grouping: null,
        traversalMode: pvc.visual.TraversalMode.FlattenLeafs,
        traversalModes: pvc.visual.TraversalMode.AllMask,
        rootLabel: "",
        autoCreateDimension: !1,
        isReversed: !1,
        label: null,
        sourceRole: null,
        _rootSourceRole: void 0,
        _legend: null,
        prettyId: function() {
            return (this.plot ? this.plot.prettyId + "." : "") + this.name;
        },
        legend: function(_) {
            if (arguments.length) {
                if (null != _) switch (typeof _) {
                  case "boolean":
                    this._legend.visible = !!_;
                    break;

                  case "object":
                    def.each(_, function(v, p) {
                        if (void 0 !== v) {
                            "visible" === p && (v = !!v);
                            this[p] = v;
                        }
                    }, this._legend);
                }
                return this;
            }
            return this._legend;
        },
        firstDimensionType: function() {
            var g = this.grouping;
            return g && g.firstDimensionType();
        },
        firstDimensionName: function() {
            var g = this.grouping;
            return g && g.firstDimensionName();
        },
        firstDimensionValueType: function() {
            var g = this.grouping;
            return g && g.firstDimensionValueType();
        },
        lastDimensionType: function() {
            var g = this.grouping;
            return g && g.lastDimensionType();
        },
        lastDimensionName: function() {
            var g = this.grouping;
            return g && g.lastDimensionName();
        },
        lastDimensionValueType: function() {
            var g = this.grouping;
            return g && g.lastDimensionValueType();
        },
        isDiscrete: function() {
            var g = this.grouping;
            return g && g.isDiscrete();
        },
        setSourceRole: function(sourceRole) {
            this.sourceRole = sourceRole;
            this._rootSourceRole = void 0;
        },
        getRootSourceRole: function() {
            var r2, r = this._rootSourceRole;
            if (void 0 === r) {
                r = this.sourceRole || null;
                if (r) for (;r2 = r.sourceRole; ) r = r2;
                this._rootSourceRole = r;
            }
            return r;
        },
        setIsReversed: function(isReversed) {
            isReversed ? this.isReversed = !0 : delete this.isReversed;
        },
        setTraversalMode: function(travMode) {
            var T = pvc.visual.TraversalMode;
            travMode = def.nullyTo(travMode, T.FlattenLeafs);
            if (travMode !== this.traversalMode) {
                if (!(travMode & this.traversalModes)) throw def.error.argumentInvalid("traversalMode", "Value is not currently valid.");
                travMode === T.FlattenLeafs ? delete this.traversalMode : this.traversalMode = travMode;
            }
        },
        setTraversalModes: function(travModes) {
            travModes = this.traversalModes &= travModes;
            if (!travModes) throw def.error.argumentInvalid("traversalModes", "Cannot become empty.");
            var travMode = this.traversalMode & travModes;
            if (!travMode) {
                travMode = travModes & -travModes;
                this.setTraversalMode(travMode);
            }
        },
        setRootLabel: function(rootLabel) {
            if (rootLabel !== this.rootLabel) {
                rootLabel ? this.rootLabel = rootLabel : delete this.rootLabel;
                this.grouping && this._updateBind(this.grouping);
            }
        },
        flatten: function(data, keyArgs) {
            var grouping = this.flattenedGrouping(keyArgs) || def.fail.operationInvalid("Role is unbound.");
            return data.groupBy(grouping, keyArgs);
        },
        flattenedGrouping: function(keyArgs) {
            var grouping = this.grouping;
            if (grouping) {
                keyArgs = keyArgs ? Object.create(keyArgs) : {};
                var flatMode = keyArgs.flatteningMode;
                null == flatMode && (flatMode = keyArgs.flatteningMode = this._flatteningMode());
                null != keyArgs.isSingleLevel || flatMode || (keyArgs.isSingleLevel = !0);
                return grouping.ensure(keyArgs);
            }
        },
        _flatteningMode: function() {
            var Trav = pvc.visual.TraversalMode, Flat = cdo.FlatteningMode;
            switch (this.traversalMode) {
              case Trav.FlattenDfsPre:
                return Flat.DfsPre;

              case Trav.FlattenDfsPost:
                return Flat.DfsPost;
            }
            return Flat.None;
        },
        select: function(data, keyArgs) {
            var grouping = this.grouping;
            if (grouping) {
                def.setUDefaults(keyArgs, "flatteningMode", cdo.FlatteningMode.None);
                return data.groupBy(grouping.ensure(keyArgs), keyArgs);
            }
        },
        view: function(complex) {
            var grouping = this.grouping;
            return grouping ? grouping.view(complex) : void 0;
        },
        preBind: function(groupingSpec) {
            this.__grouping = groupingSpec;
            return this;
        },
        isPreBound: function() {
            return !!this.__grouping;
        },
        preBoundGrouping: function() {
            return this.__grouping;
        },
        isBound: function() {
            return !!this.grouping;
        },
        postBind: function(type) {
            var grouping = this.__grouping;
            if (grouping) {
                delete this.__grouping;
                grouping.bind(type);
                this.bind(grouping);
            }
            return this;
        },
        bind: function(groupingSpec) {
            groupingSpec = this._validateBind(groupingSpec);
            this._updateBind(groupingSpec);
            return this;
        },
        _validateBind: function(groupingSpec) {
            if (groupingSpec) if (groupingSpec.isNull()) groupingSpec = null; else {
                if (this.requireSingleDimension && !groupingSpec.isSingleDimension) throw def.error.operationInvalid("Role '{0}' only accepts a single dimension.", [ this.name ]);
                var valueType = this.valueType, requireIsDiscrete = this.requireIsDiscrete;
                groupingSpec.dimensions().each(function(dimSpec) {
                    var dimType = dimSpec.type;
                    if (valueType && dimType.valueType !== valueType) throw def.error.operationInvalid("Role '{0}' cannot be bound to dimension '{1}'. \nIt only accepts dimensions of type '{2}' and not of type '{3}'.", [ this.name, dimType.name, cdo.DimensionType.valueTypeName(valueType), dimType.valueTypeName ]);
                    if (null != requireIsDiscrete && dimType.isDiscrete !== requireIsDiscrete) {
                        if (!requireIsDiscrete) throw def.error.operationInvalid("Role '{0}' cannot be bound to dimension '{1}'.\nIt only accepts continuous dimensions.", [ this.name, dimType.name ]);
                        dimType._toDiscrete();
                    }
                }, this);
            }
            return groupingSpec;
        },
        canHaveSource: function(source) {
            var tvt = this.valueType;
            return null == tvt || tvt === source.valueType;
        },
        _updateBind: function(groupingSpec) {
            this.grouping = groupingSpec;
            this.grouping && (this.grouping = this.grouping.ensure({
                reverse: this.isReversed,
                rootLabel: this.rootLabel
            }));
        }
    }).type().add({
        parse: function(lookup, name, config) {
            var groupSpec, parsed = {
                isReversed: !1,
                source: null,
                grouping: null,
                legend: null
            };
            if (def.object.is(config)) {
                config.isReversed && (parsed.isReversed = !0);
                parsed.legend = config.legend;
                var sourceName = config.from;
                if (sourceName) {
                    if (sourceName === name) throw def.error.operationInvalid("Invalid source role.");
                    parsed.source = lookup(sourceName) || def.fail.operationInvalid("Source visual role '{0}' is not defined.", [ sourceName ]);
                } else groupSpec = config.dimensions;
            } else (null === config || def.string.is(config)) && (groupSpec = config);
            void 0 !== groupSpec && (parsed.grouping = cdo.GroupingSpec.parse(groupSpec));
            return parsed;
        }
    });
    pvc.visual.rolesBinder = function() {
        function begin() {
            visualRolesBinder_assertState(state, 0);
            if (!context) throw def.error.argumentRequired("context");
            if (!complexTypeProj) throw def.error.argumentRequired("complexTypeProject");
            state = 1;
            doLog = !!logger && logger.level() >= 3;
            context.query().each(function(r) {
                var opts = context.getOptions(r);
                void 0 !== opts && configure(r, opts) || trySourceIfSecondaryRole(r);
            });
            unboundSourcedRoles.forEach(function(r) {
                tryPreBindSourcedRole(r);
            }, this);
            unboundSourcedRoles = [];
            applySingleRoleDefaults();
            state = 2;
            return this;
        }
        function configure(r, opts) {
            var grouping, parsed = pvc.visual.Role.parse(context, r.name, opts);
            parsed.isReversed && r.setIsReversed(!0);
            null != parsed.legend && r.legend(parsed.legend);
            if (parsed.source) {
                r.setSourceRole(parsed.source);
                return addUnboundSourced(r), 1;
            }
            return (grouping = parsed.grouping) ? (preBindToGrouping(r, grouping), 1) : 0;
        }
        function addUnboundSourced(r) {
            unboundSourcedRoles.push(r);
        }
        function preBindToGrouping(r, grouping) {
            r.preBind(grouping);
            grouping.isNull() ? visRoleBinder_assertUnboundRoleIsOptional(r) : registerBindings(r, grouping.dimensionNames());
        }
        function registerBindings(r, ns) {
            ns.forEach(function(n) {
                registerBinding(r, n);
            });
        }
        function registerBinding(r, n) {
            if (dimsBoundTo[n]) delete singleRoleByDimName[n]; else {
                dimsBoundTo[n] = !0;
                singleRoleByDimName[n] = r;
                complexTypeProj.setDim(n);
            }
        }
        function trySourceIfSecondaryRole(r) {
            var mainRole = context(r.name);
            if (mainRole && mainRole !== r && r.canHaveSource(mainRole)) {
                r.setSourceRole(mainRole);
                addUnboundSourced(r);
            }
        }
        function tryPreBindSourcedRole(r, visited) {
            var id = r.prettyId();
            if (visited) {
                if (def.hasOwn(visited, id)) throw def.error.argumentInvalid("visualRoles", "Cyclic source role definition.");
            } else visited = {};
            visited[id] = !0;
            if (r.isPreBound()) return r.preBoundGrouping();
            var source = r.sourceRole;
            if (!source) return r.isPreBound() ? r.preBoundGrouping() : null;
            var sourcePreGrouping = tryPreBindSourcedRole(source, visited);
            if (sourcePreGrouping) {
                source.isReversed && r.setIsReversed(!r.isReversed);
                r.preBind(sourcePreGrouping);
            }
            return sourcePreGrouping;
        }
        function applySingleRoleDefaults() {
            def.eachOwn(singleRoleByDimName, function(r, n) {
                complexTypeProj.setDimDefaults(n, r.dimensionDefaults);
            });
        }
        function end() {
            visualRolesBinder_assertState(state, 2);
            state = 3;
            context.query().each(function(r) {
                r.isPreBound() || autoPrebindUnbound(r);
            });
            unboundSourcedRoles.forEach(function(r) {
                tryPreBindSourcedRole(r) || roleIsUnbound(r);
            });
            applySingleRoleDefaults();
            var complexType = new cdo.ComplexType();
            complexTypeProj.configureComplexType(complexType, dimsOptions);
            doLog && logger(complexType.describe());
            context.query().each(function(r) {
                r.isPreBound() && r.postBind(complexType);
            });
            doLog && logVisualRoles();
            state = 4;
            return complexType;
        }
        function autoPrebindUnbound(r) {
            if (r.sourceRole) return addUnboundSourced(r);
            var defaultName = r.defaultDimensionGroup;
            if (defaultName) {
                if (r.defaultDimensionGreedy) {
                    var groupDimNames = complexTypeProj.groupDimensionsNames(defaultName);
                    if (groupDimNames) return preBindToDims(r, groupDimNames);
                } else if (complexTypeProj.hasDim(defaultName)) return preBindToDims(r, defaultName);
                if (r.autoCreateDimension) {
                    complexTypeProj.setDim(defaultName, {
                        isHidden: !0
                    });
                    return preBindToDims(r, defaultName);
                }
            }
            if (r.defaultSourceRoleName) {
                var source = context(r.defaultSourceRoleName);
                if (source) {
                    r.setSourceRole(source);
                    return addUnboundSourced(r);
                }
            }
            roleIsUnbound(r);
        }
        function preBindToDims(r, ns) {
            var grouping = cdo.GroupingSpec.parse(ns);
            preBindToGrouping(r, grouping);
        }
        function roleIsUnbound(r) {
            visRoleBinder_assertUnboundRoleIsOptional(r);
            r.bind(null);
            r.setSourceRole(null);
        }
        function logVisualRoles() {
            var table = def.textTable(3).rowSep().row("Visual Role", "Source/From", "Bound to Dimension(s)").rowSep();
            context.query().each(function(r) {
                table.row(r.prettyId(), r.sourceRole ? r.sourceRole.prettyId() : "-", String(r.grouping || "-"));
            });
            table.rowSep(!0);
            logger("VISUAL ROLES MAP SUMMARY\n" + table() + "\n");
        }
        var context, complexTypeProj, dimsOptions, logger, doLog, state = 0, unboundSourcedRoles = [], singleRoleByDimName = {}, dimsBoundTo = {};
        return {
            logger: function(_) {
                if (arguments.length) {
                    visualRolesBinder_assertState(state, 0);
                    logger = _;
                    return this;
                }
                return logger;
            },
            dimensionsOptions: function(_) {
                if (arguments.length) {
                    visualRolesBinder_assertState(state, 0);
                    dimsOptions = _;
                    return this;
                }
                return dimsOptions;
            },
            context: function(_) {
                if (arguments.length) {
                    visualRolesBinder_assertState(state, 0);
                    context = _;
                    return this;
                }
                return context;
            },
            complexTypeProject: function(_) {
                if (arguments.length) {
                    visualRolesBinder_assertState(state, 0);
                    complexTypeProj = _;
                    return this;
                }
                return complexTypeProj;
            },
            begin: begin,
            end: end
        };
    };
    def.type("pvc.visual.RoleVarHelper").init(function(rootScene, roleName, role, keyArgs) {
        var panel, hasPercentSubVar = def.get(keyArgs, "hasPercentSubVar", !1), g = this.grouping = role && role.grouping;
        if (g) {
            this.role = role;
            var rootSourceRole = role.getRootSourceRole();
            this.sourceRoleName = rootSourceRole && rootSourceRole.name;
            this.sourceRoleName === role.name && (this.sourceRoleName = null);
            panel = rootScene.panel();
            this.panel = panel;
            if (!g.isDiscrete()) {
                this.rootContDim = panel.data.owner.dimensions(g.lastDimensionName());
                hasPercentSubVar && (this.percentFormatter = panel.chart.options.percentValueFormat);
            }
        }
        if (!roleName) {
            if (!role) throw def.error.operationInvalid("Role is not defined, so the roleName argument is required.");
            roleName = role.name;
        }
        if (!g) {
            var roleVar = rootScene.vars[roleName] = new pvc_ValueLabelVar(null, "");
            hasPercentSubVar && (roleVar.percent = new pvc_ValueLabelVar(null, ""));
        }
        this.roleName = roleName;
        rootScene["is" + def.firstUpperCase(roleName) + "Bound"] = !!g;
        def.get(keyArgs, "allowNestedVars") && (this.allowNestedVars = !0);
    }).add({
        allowNestedVars: !1,
        isBound: function() {
            return !!this.grouping;
        },
        onNewScene: function(scene, isLeaf) {
            if (this.grouping) {
                var roleName = this.roleName;
                if (this.allowNestedVars ? !def.hasOwnProp.call(scene.vars, roleName) : !scene.vars[roleName]) {
                    var sourceVar, sourceName = this.sourceRoleName;
                    if (sourceName && (sourceVar = def.getOwn(scene.vars, sourceName))) scene.vars[roleName] = sourceVar.clone(); else if (isLeaf) {
                        var roleVar, rootContDim = this.rootContDim;
                        if (rootContDim) {
                            var valuePct, valueDim, group = scene.group, singleDatum = group ? group.singleDatum() : scene.datum;
                            if (singleDatum) {
                                if (!singleDatum.isNull) {
                                    roleVar = pvc_ValueLabelVar.fromAtom(singleDatum.atoms[rootContDim.name]);
                                    if (null != roleVar.value && this.percentFormatter) if (group) {
                                        valueDim = group.dimensions(rootContDim.name);
                                        valuePct = valueDim.valuePercent({
                                            visible: !0
                                        });
                                    } else valuePct = scene.data().dimensions(rootContDim.name).percent(roleVar.value);
                                }
                            } else if (group) {
                                valueDim = group.dimensions(rootContDim.name);
                                var value = valueDim.value({
                                    visible: !0,
                                    zeroIfNone: !1
                                });
                                if (null != value) {
                                    var label = rootContDim.format(value);
                                    roleVar = new pvc_ValueLabelVar(value, label, value);
                                    this.percentFormatter && (valuePct = valueDim.valuePercent({
                                        visible: !0
                                    }));
                                }
                            }
                            roleVar && this.percentFormatter && (null == roleVar.value ? roleVar.percent = new pvc_ValueLabelVar(null, "") : roleVar.percent = new pvc_ValueLabelVar(valuePct, this.percentFormatter.call(null, valuePct)));
                        } else {
                            var firstDatum = scene.datum;
                            if (firstDatum) {
                                firstDatum.isNull && (firstDatum = scene.datums().where(datum_notNull).first());
                                if (firstDatum) {
                                    var view = this.grouping.view(firstDatum);
                                    roleVar = pvc_ValueLabelVar.fromComplex(view);
                                }
                            }
                        }
                        if (!roleVar) {
                            roleVar = new pvc_ValueLabelVar(null, "");
                            this.percentFormatter && (roleVar.percent = new pvc_ValueLabelVar(null, ""));
                        }
                        scene.vars[roleName] = roleVar;
                    }
                }
            }
        }
    });
    def.type("pvc.visual.DataCell").init(function(plot, axisType, axisIndex, role, dataPartValue) {
        this.plot = plot;
        this.axisType = axisType;
        this.axisIndex = axisIndex;
        this.role = role;
        this.dataPartValue = dataPartValue;
        this.key = [ axisType, axisIndex, role.prettyId(), dataPartValue ].join("~");
    }).add({
        legendVisible: function() {
            return this.role.legend().visible;
        }
    });
    def.type("pvc.visual.ColorDataCell", pvc.visual.DataCell).init(function(plot, axisType, axisIndex, role, dataPartValue) {
        this.base(plot, axisType, axisIndex, role, dataPartValue);
        this._legendGroupScene = null;
        this._legendSymbolRenderer = null;
    }).add({
        legendSymbolRenderer: function(_) {
            if (arguments.length) {
                _ && "object" == typeof _ && (_ = pvc.visual.legend.symbolRenderer(_));
                this._legendSymbolRenderer = _;
                return this;
            }
            return this._legendSymbolRenderer;
        }
    });
    var pvc_Axis = def("pvc.visual.Axis", pvc.visual.OptionsBase.extend({
        init: function(chart, type, index, keyArgs) {
            this.base(chart, type, index, keyArgs);
            chart._addAxis(this);
            this._state = {};
            keyArgs && keyArgs.state && def.copy(this._state, keyArgs.state);
        },
        methods: {
            _buildOptionId: function() {
                return this.id + "Axis";
            },
            scaleTreatsNullAs: function() {
                return "null";
            },
            scaleNullRangeValue: function() {
                return null;
            },
            scaleUsesAbs: def.retFalse,
            scaleSumNormalized: def.retFalse,
            domainVisibleOnly: def.retTrue,
            domainIgnoreNulls: def.retFalse,
            domainGroupOperator: function() {
                return "flatten";
            },
            domainItemValueProp: function() {
                return "value";
            },
            bind: function(dataCells) {
                var me = this;
                dataCells || def.fail.argumentRequired("dataCells");
                !me.dataCells || def.fail.operationInvalid("Axis is already bound.");
                me.dataCells = def.array.to(dataCells);
                me._dataCellsByKey = def.query(me.dataCells).uniqueIndex(function(dc) {
                    return dc.key;
                });
                me.dataCell = me.dataCells[0];
                me.role = me.dataCell && me.dataCell.role;
                me.scaleType = axis_groupingScaleType(me.role.grouping);
                me._domainData = null;
                me._domainValues = null;
                me._domainItems = null;
                me._conciliateVisualRoles();
                return this;
            },
            _buildState: function() {
                return {};
            },
            getState: function() {
                return this._buildState();
            },
            setDataCellScaleInfo: function(dataCell, scaleInfo) {
                if (this._dataCellsByKey[dataCell.key] !== dataCell) throw def.error.argumentInvalid("dataCell", "Not present in this axis.");
                def.lazy(this, "_dataCellsScaleInfoByKey")[dataCell.key] = scaleInfo;
            },
            getDataCellScaleInfo: function(dataCell) {
                return def.getOwn(this._dataCellsScaleInfoByKey, dataCell.key);
            },
            domainData: function() {
                this.isBound() || def.fail.operationInvalid("Axis is not bound.");
                var domainData = this._domainData;
                if (!domainData) {
                    var dataPartValues = this.dataCells.map(dataCell_dataPartValue), partsData = this.chart.partData(dataPartValues);
                    this._domainData = domainData = this._createDomainData(partsData);
                }
                return domainData;
            },
            domainCellData: function(cellIndex) {
                var dataCells = this.dataCells;
                if (1 === dataCells.length) return this.domainData();
                var dataCell = dataCells[cellIndex], partData = this.chart.partData(dataCell.dataPartValue);
                return this._createDomainData(partData);
            },
            domainCellItems: function(cellDataOrIndex) {
                var dataCells = this.dataCells;
                if (1 === dataCells.length) return this.domainItems();
                var cellData = def.number.is(cellDataOrIndex) ? this.domainCellData(cellDataOrIndex) : cellDataOrIndex;
                return this._selectDomainItems(cellData).array();
            },
            domainValues: function() {
                var domainValues = this._domainValues;
                domainValues || (domainValues = (this._calcDomainItems(), this._domainValues));
                return domainValues;
            },
            domainItems: function() {
                var domainItems = this._domainItems;
                domainItems || (domainItems = (this._calcDomainItems(), this._domainItems));
                return domainItems;
            },
            domainItemCount: function() {
                return this.domainItems().length;
            },
            domainItemValue: function(itemData) {
                return def.nullyTo(itemData[this.domainItemValueProp()], "");
            },
            isDiscrete: function() {
                return !!this.role && this.role.isDiscrete();
            },
            isBound: function() {
                return !!this.role;
            },
            setScale: function(scale, noWrap) {
                this.isBound() || def.fail.operationInvalid("Axis is not bound.");
                this.scale = scale ? noWrap ? scale : this._wrapScale(scale) : null;
                return this;
            },
            _wrapScale: function(scale) {
                scale.type = this.scaleType;
                var by;
                if ("discrete" !== scale.type) {
                    var useAbs = this.scaleUsesAbs(), nullAs = this.scaleTreatsNullAs();
                    if (nullAs && "null" !== nullAs) {
                        var nullIsMin = "min" === nullAs;
                        by = useAbs ? function(v) {
                            return scale(null == v ? nullIsMin ? scale.domain()[0] : 0 : 0 > v ? -v : v);
                        } : function(v) {
                            return scale(null == v ? nullIsMin ? scale.domain()[0] : 0 : v);
                        };
                    } else {
                        var nullRangeValue = this.scaleNullRangeValue();
                        by = useAbs ? function(v) {
                            return null == v ? nullRangeValue : scale(0 > v ? -v : v);
                        } : function(v) {
                            return null == v ? nullRangeValue : scale(v);
                        };
                    }
                } else by = function(v) {
                    return scale(null == v ? "" : v);
                };
                return def.copy(by, scale);
            },
            sceneScale: function(keyArgs) {
                var varName = def.get(keyArgs, "sceneVarName") || this.role.name, grouping = this.role.grouping, scale = this.scale;
                if (grouping.lastDimensionValueType() === Number) {
                    var nullToZero = def.get(keyArgs, "nullToZero", !0), by = function(scene) {
                        var value = scene.vars[varName].value;
                        if (null == value) {
                            if (!nullToZero) return value;
                            value = 0;
                        }
                        return scale(value);
                    };
                    def.copy(by, scale);
                    return by;
                }
                return scale.by1(function(scene) {
                    return scene.vars[varName].value;
                });
            },
            _conciliateVisualRoles: function() {
                var L = this.dataCells.length;
                if (L > 1) {
                    var otherRole, otherGrouping, possibleTraversalModes, traversalMode, otherTravMode, rootLabel, dimNamesKey, i, grouping = this._getBoundRoleGrouping(this.role), createError = function(msg, args) {
                        return def.error.operationInvalid(def.format(msg, args));
                    };
                    if ("discrete" === this.scaleType) {
                        possibleTraversalModes = this.role.traversalModes;
                        rootLabel = this.role.rootLabel;
                        dimNamesKey = String(this.role.grouping.dimensionNames());
                        for (i = 1; L > i && possibleTraversalModes; i++) {
                            otherRole = this.dataCells[i].role;
                            possibleTraversalModes &= otherRole.traversalModes;
                            rootLabel || (rootLabel = otherRole.rootLabel);
                            otherGrouping = this._getBoundRoleGrouping(otherRole);
                            if (dimNamesKey !== String(otherGrouping.dimensionNames())) throw createError("The visual roles '{0}', on axis '{1}', assumed discrete, should be bound to the same dimension list.", [ [ this.role.prettyId(), otherRole.prettyId() ].join("', '"), this.id ]);
                        }
                        if (!possibleTraversalModes) throw createError("The visual roles on axis '{0}', assumed discrete, do not share a possible traversal mode.", [ this.id ]);
                        traversalMode = 0;
                        for (i = 0; L > i; i++) {
                            otherRole = this.dataCells[i].role;
                            otherTravMode = otherRole.traversalMode;
                            otherTravMode & possibleTraversalModes && otherTravMode > traversalMode && (traversalMode = otherTravMode);
                        }
                        traversalMode || (traversalMode = possibleTraversalModes & -possibleTraversalModes);
                        for (i = 0; L > i; i++) {
                            otherRole = this.dataCells[i].role;
                            otherRole.setRootLabel(rootLabel);
                            otherRole.setTraversalMode(traversalMode);
                            otherRole.setTraversalModes(traversalMode);
                        }
                    } else {
                        if (!grouping.lastDimensionType().isComparable) throw createError("The visual roles on axis '{0}', assumed continuous, should have 'comparable' groupings.", [ this.id ]);
                        for (i = 1; L > i; i++) {
                            otherRole = this.dataCells[i].role;
                            otherGrouping = this._getBoundRoleGrouping(otherRole);
                            if (this.scaleType !== axis_groupingScaleType(otherGrouping)) throw createError("The visual roles on axis '{0}', assumed continuous, should have scales of the same type.", [ this.id ]);
                            if (this.role.isNormalized !== otherRole.isNormalized) throw createError("The visual roles on axis '{0}', assumed normalized, should be of the same type.", [ this.id ]);
                        }
                    }
                }
            },
            _getBoundRoleGrouping: function(role) {
                var grouping = role.grouping;
                if (!grouping) throw def.error.operationInvalid("Axis' role '" + role.name + "' is unbound.");
                return grouping;
            },
            _createDomainData: function(baseData) {
                var keyArgs = {
                    visible: this.domainVisibleOnly() ? !0 : null,
                    isNull: this.chart.options.ignoreNulls || this.domainIgnoreNulls() ? !1 : null
                };
                return this.role[this.domainGroupOperator()](baseData, keyArgs);
            },
            _selectDomainItems: function(domainData) {
                return domainData.children();
            },
            _calcDomainItems: function() {
                var hasOwn = def.hasOwnProp, domainValuesSet = {}, domainValues = [], domainItems = [], domainData = this.domainData();
                this._selectDomainItems(domainData).each(function(itemData) {
                    var itemValue = this.domainItemValue(itemData);
                    if (!hasOwn.call(domainValuesSet, itemValue)) {
                        domainValuesSet[itemValue] = 1;
                        domainValues.push(itemValue);
                        domainItems.push(itemData);
                    }
                }, this);
                this._domainItems = domainItems;
                this._domainValues = domainValues;
            }
        }
    }));
    def("pvc.visual.ColorAxis", pvc_Axis.extend({
        methods: {
            scaleNullRangeValue: function() {
                return this.option("Missing") || null;
            },
            scaleUsesAbs: function() {
                return this.option("UseAbs");
            },
            domainVisibleOnly: function() {
                return "discrete" !== this.scaleType;
            },
            bind: function(dataCells) {
                this.base(dataCells);
                this._legendGroupScene = null;
                this._plotList = def.query(dataCells).select(function(dataCell) {
                    return dataCell.plot;
                }).distinct(function(plot) {
                    return plot && plot.id;
                }).array();
                return this;
            },
            _wrapScale: function(scale) {
                var optSpecified = this.option.isSpecified, applyTransf = "discrete" !== this.scaleType || optSpecified("Transform") || !optSpecified("Colors");
                if (applyTransf) {
                    var colorTransf = this.option("Transform");
                    colorTransf && (scale = scale.transform(colorTransf));
                }
                return this.base(scale);
            },
            _buildState: function() {
                return {
                    preservedMap: this._calcPreservedMap()
                };
            },
            _calcPreservedMap: function() {
                var scale = this.scale;
                if (scale && "discrete" === this.scaleType) {
                    var map = this._state.preservedMap || {};
                    scale.domain().forEach(function(key) {
                        def.hasOwn(map, key) || (map[key] = scale(key));
                    });
                    return map;
                }
            },
            _getPreservedMap: function() {
                return this.option("PreserveMap") ? this._state.preservedMap : null;
            },
            scheme: function() {
                return def.lazy(this, "_scheme", this._createScheme, this);
            },
            _createColorMapFilter: function(colorMap, baseScheme) {
                var fixedColors = def.uniqueIndex(colorMap, function(c) {
                    return c.key;
                });
                return {
                    domain: function(k) {
                        return !def.hasOwn(colorMap, k);
                    },
                    color: function(c) {
                        return !def.hasOwn(fixedColors, c.key);
                    }
                };
            },
            _getBaseScheme: function() {
                return this.option("Colors");
            },
            _createScheme: function() {
                var me = this, baseScheme = me._getBaseScheme();
                if ("discrete" !== me.scaleType) return function() {
                    var scale = baseScheme.apply(null, arguments);
                    return me._wrapScale(scale);
                };
                var colorMap = this._getPreservedMap() || me.option("Map");
                if (!colorMap) return function() {
                    var scale = baseScheme.apply(null, arguments);
                    return me._wrapScale(scale);
                };
                var filter = this._createColorMapFilter(colorMap, baseScheme);
                return function(d) {
                    var scale;
                    d instanceof Array || (d = def.array.copy(arguments));
                    d = d.filter(filter.domain);
                    var baseScale = baseScheme(d), r = baseScale.range().filter(filter.color);
                    baseScale.range(r);
                    scale = function(k) {
                        var c = def.getOwn(colorMap, k);
                        return c || baseScale(k);
                    };
                    def.copy(scale, baseScale);
                    var dx, rx;
                    scale.domain = function() {
                        if (arguments.length) throw def.error.operationInvalid("The scale cannot be modified.");
                        dx || (dx = def.array.append(def.ownKeys(colorMap), d));
                        return dx;
                    };
                    scale.range = function() {
                        if (arguments.length) throw def.error.operationInvalid("The scale cannot be modified.");
                        rx || (rx = def.array.append(def.own(colorMap), r));
                        return rx;
                    };
                    return me._wrapScale(scale);
                };
            },
            sceneScale: function(keyArgs) {
                var varName = def.get(keyArgs, "sceneVarName") || this.role.name, fillColorScaleByColKey = this.scalesByCateg;
                if (fillColorScaleByColKey) {
                    var colorMissing = this.option("Missing");
                    return function(scene) {
                        var colorValue = scene.vars[varName].value;
                        if (null == colorValue) return colorMissing;
                        var catAbsKey = scene.group.parent.absKey;
                        return fillColorScaleByColKey[catAbsKey](colorValue);
                    };
                }
                return this.scale.by1(function(scene) {
                    return scene && scene.vars[varName].value;
                });
            },
            _resolveByNaked: pvc.options.specify(function(optionInfo) {
                return this._chartOption(this.id + def.firstUpperCase(optionInfo.name));
            }),
            _specifyV1ChartOption: function(optionInfo, asName) {
                return !this.index && this.chart.compatVersion() <= 1 && this._specifyChartOption(optionInfo, asName) ? !0 : void 0;
            }
        }
    }));
    var colorAxis_defContColors, colorAxis_legendDataSpec = {
        resolveDefault: function(optionInfo) {
            return !this.index && this._specifyChartOption(optionInfo, def.firstLowerCase(optionInfo.name)) ? !0 : void 0;
        }
    };
    pvc.visual.ColorAxis.options({
        Colors: {
            resolve: "_resolveFull",
            getDefault: colorAxis_getDefaultColors,
            data: {
                resolveV1: function(optionInfo) {
                    "discrete" === this.scaleType ? 0 === this.index ? this._specifyChartOption(optionInfo, "colors") : 1 === this.index && this.chart._allowV1SecondAxis && this._specifyChartOption(optionInfo, "secondAxisColor") : this._specifyChartOption(optionInfo, "colorRange");
                    return !0;
                },
                resolveDefault: function(optionInfo) {
                    0 === this.index && this._specifyChartOption(optionInfo, "colors");
                }
            },
            cast: pvc.colorScheme
        },
        Map: {
            resolve: "_resolveFull",
            cast: colorAxis_castColorMap
        },
        PreserveMap: {
            resolve: "_resolveFull",
            cast: Boolean,
            value: !1
        },
        Transform: {
            resolve: "_resolveFull",
            data: {
                resolveDefault: function(optionInfo) {
                    var plotList = this._plotList;
                    if (plotList.length) {
                        var notMainAndAnyOfTrendAndPlot2 = !1;
                        def.query(plotList).each(function(plot) {
                            if (plot.isMain) return notMainAndAnyOfTrendAndPlot2 = !1;
                            var name = plot.name;
                            ("plot2" === name || "trend" === name) && (notMainAndAnyOfTrendAndPlot2 = !0);
                        });
                        if (notMainAndAnyOfTrendAndPlot2) return optionInfo.defaultValue(pvc.brighterColorTransform), 
                        !0;
                    }
                }
            },
            cast: def.fun.to
        },
        NormByCategory: {
            resolve: function(optionInfo) {
                return this.chart._allowColorPerCategory ? this._resolveFull(optionInfo) : (optionInfo.specify(!1), 
                !0);
            },
            data: {
                resolveV1: function(optionInfo) {
                    return this._specifyV1ChartOption(optionInfo, "normPerBaseCategory"), !0;
                }
            },
            cast: Boolean,
            value: !1
        },
        ScaleType: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    return this._specifyV1ChartOption(optionInfo, "scalingType"), !0;
                }
            },
            cast: pvc.parseContinuousColorScaleType,
            value: "linear"
        },
        UseAbs: {
            resolve: "_resolveFull",
            cast: Boolean,
            value: !1
        },
        Domain: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    return this._specifyV1ChartOption(optionInfo, "colorRangeInterval"), !0;
                }
            },
            cast: def.array.to
        },
        Min: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    return this._specifyV1ChartOption(optionInfo, "minColor"), !0;
                }
            },
            cast: pv.color
        },
        Max: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    return this._specifyV1ChartOption(optionInfo, "maxColor"), !0;
                }
            },
            cast: pv.color
        },
        Missing: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    return this._specifyV1ChartOption(optionInfo, "nullColor"), !0;
                }
            },
            cast: pv.color,
            value: pv.color("lightgray")
        },
        Unbound: {
            resolve: "_resolveFull",
            getDefault: function(optionInfo) {
                var scheme = this.option("Colors");
                return scheme().range()[0] || pvc.defaultColor;
            },
            cast: pv.color
        },
        LegendVisible: {
            resolve: "_resolveFull",
            data: colorAxis_legendDataSpec,
            cast: Boolean,
            value: !0
        },
        LegendClickMode: {
            resolve: "_resolveFull",
            data: colorAxis_legendDataSpec,
            cast: pvc.parseLegendClickMode,
            value: "togglevisible"
        },
        LegendDrawLine: {
            resolve: "_resolveFull",
            data: colorAxis_legendDataSpec,
            cast: Boolean,
            value: !1
        },
        LegendDrawMarker: {
            resolve: "_resolveFull",
            data: colorAxis_legendDataSpec,
            cast: Boolean,
            value: !0
        },
        LegendShape: {
            resolve: "_resolveFull",
            data: colorAxis_legendDataSpec,
            cast: pvc.parseShape
        }
    });
    def("pvc.visual.SizeAxis", pvc_Axis.extend({
        init: function(chart, type, index, keyArgs) {
            keyArgs = def.set(keyArgs, "byNaked", !1);
            this.base(chart, type, index, keyArgs);
        },
        methods: {
            scaleTreatsNullAs: function() {
                return "min";
            },
            scaleUsesAbs: function() {
                return this.option("UseAbs");
            },
            setScaleRange: function(range) {
                var scale = this.scale;
                scale.min = range.min;
                scale.max = range.max;
                scale.size = range.max - range.min;
                scale.range(scale.min, scale.max);
                def.debug >= 4 && def.log("Scale: " + def.describe(def.copyOwn(scale)));
                return this;
            }
        },
        options: {
            OriginIsZero: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !1
            },
            FixedMin: {
                resolve: "_resolveFull",
                cast: def.number.to
            },
            FixedMax: {
                resolve: "_resolveFull",
                cast: def.number.to
            },
            FixedLength: {
                resolve: "_resolveFull",
                cast: def.number.to
            },
            DomainAlign: {
                resolve: "_resolveFull",
                cast: pvc.parseDomainAlign,
                value: "center"
            },
            UseAbs: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !1
            }
        }
    }));
    def("pvc.visual.NormalizedAxis", pvc_Axis.extend({
        init: function(chart, type, index, keyArgs) {
            keyArgs = def.set(keyArgs, "byNaked", !1);
            this.base(chart, type, index, keyArgs);
        },
        methods: {
            scaleTreatsNullAs: function() {
                return "zero";
            },
            scaleUsesAbs: def.retTrue,
            scaleSumNormalized: def.retTrue,
            setScaleRange: function(range) {
                var scale = this.scale;
                scale.min = range.min;
                scale.max = range.max;
                scale.size = range.max - range.min;
                scale.range(scale.min, scale.max);
                def.debug >= 4 && def.log("Scale: " + def.describe(def.copyOwn(scale)));
                return this;
            }
        },
        options: {
            OriginIsZero: {
                value: !0
            }
        }
    }));
    def.type("pvc.BasePanel", pvc.Abstract).add(pvc.visual.Interactive).init(function(chart, parent, options) {
        this.chart = chart;
        this.base();
        this.axes = Object.create(chart.axes);
        if (options) {
            if (options.scenes) {
                this._sceneTypeExtensions = options.scenes;
                delete options.scenes;
            }
            var axes = options.axes;
            if (axes) {
                def.copy(this.axes, axes);
                delete options.axes;
            }
        }
        $.extend(this, options);
        this.axes.color || (this.axes.color = chart.axes.color);
        this.position = {};
        var margins = options && options.margins;
        parent || void 0 !== margins || (margins = 3);
        this.margins = new pvc_Sides(margins);
        this.paddings = new pvc_Sides(options && options.paddings);
        this.size = new pvc_Size(options && options.size);
        this.sizeMin = new pvc_Size(options && options.sizeMin);
        this.sizeMax = new pvc_Size(options && options.sizeMax);
        this.parent = parent || null;
        if (parent) {
            this.isTopRoot = !1;
            this.isRoot = parent.chart !== chart;
            this.root = this.isRoot ? this : parent.root;
            this.topRoot = parent.topRoot;
            if (this.isRoot) {
                this.position.left = chart.left;
                this.position.top = chart.top;
            }
            parent._addChild(this);
        } else {
            this.root = this;
            this.topRoot = this;
            this.isRoot = !0;
            this.isTopRoot = !0;
        }
        var above = parent || chart;
        this.data = above.data;
        if (this.isRoot) {
            this.anchor = null;
            this.align = null;
            this.alignTo = null;
            this.offset = null;
        } else {
            this.align = pvc.parseAlign(this.anchor, this.align);
            var alignTo = this.alignTo, side = this.anchor;
            null == alignTo || "" === alignTo || "left" !== side && "right" !== side ? alignTo = this.align : "page-middle" !== alignTo && (alignTo = isNaN(+alignTo.charAt(0)) ? pvc.parseAlign(side, alignTo) : pvc_PercentValue.parse(alignTo));
            this.alignTo = alignTo;
            this.offset = new pvc_Offset(this.offset);
        }
        if (null == this.borderWidth) {
            var borderWidth, extensionId = this._getExtensionId();
            if (extensionId) {
                var strokeStyle = this._getExtension(extensionId, "strokeStyle");
                if (null != strokeStyle) {
                    borderWidth = +this._getConstantExtension(extensionId, "lineWidth");
                    (isNaN(borderWidth) || !isFinite(borderWidth)) && (borderWidth = null);
                }
            }
            this.borderWidth = null == borderWidth ? 0 : 1.5;
        }
        var ibits = above.ibits(), I = pvc.visual.Interactive, ibitsChart = chart.ibits();
        ibits = def.bit.set(ibits, I.Clickable, ibitsChart & I.Clickable && !!this.clickAction);
        ibits = def.bit.set(ibits, I.DoubleClickable, ibitsChart & I.DoubleClickable && !!this.doubleClickAction);
        ibits = def.bit.set(ibits, I.Animatable, ibitsChart & I.Animatable);
        this._ibits &= ibits;
    }).add({
        _ibits: ~pvc.visual.Interactive.Animatable,
        chart: null,
        parent: null,
        _children: null,
        type: pv.Panel,
        _extensionPrefix: "",
        _rubberSelectableMarks: null,
        height: null,
        width: null,
        borderWidth: null,
        anchor: "top",
        pvPanel: null,
        margins: null,
        paddings: null,
        isRoot: !1,
        isTopRoot: !1,
        root: null,
        topRoot: null,
        _layoutInfo: null,
        _signs: null,
        data: null,
        dataPartValue: null,
        _animating: 0,
        _selectingByRubberband: !1,
        _v1DimRoleName: {
            series: "series",
            category: "category",
            value: "value"
        },
        _sceneTypeExtensions: null,
        clickAction: null,
        doubleClickAction: null,
        compatVersion: function(options) {
            return this.chart.compatVersion(options);
        },
        getCompatFlag: function(flagName) {
            return this.chart.getCompatFlag(flagName);
        },
        _createLogId: function() {
            return "" + def.qualNameOf(this.constructor) + this.chart._createLogChildSuffix();
        },
        _getLegendRootScene: function() {
            return this.chart._getLegendRootScene();
        },
        _addChild: function(child) {
            child.parent === this || def.assert("Child has a != parent.");
            (this._children || (this._children = [])).push(child);
        },
        _addSign: function(sign) {
            def.array.lazy(this, "_signs").push(sign);
            sign.selectableByRubberband() && def.array.lazy(this, "_rubberSelectableMarks").push(sign.pvMark);
        },
        visibleData: function(ka) {
            return this.chart.visibleData(this.dataPartValue, ka);
        },
        partData: function() {
            return this.chart.partData(this.dataPartValue);
        },
        layout: function(ka) {
            function processSizeDirection(a_len) {
                var addLen = clientSizeNeeds[a_len] - clientSizeAvailableInput[a_len];
                if (addLen > pv.epsilon) {
                    clientSizeIncrease[a_len] = addLen;
                    var pct = this.margins.getDirectionPercentage(a_len) + this.paddings.getDirectionPercentage(a_len);
                    pct = Math.max(0, Math.min(1, pct));
                    pct > 0 && (addLen /= 1 - pct);
                    sizeIncrease[a_len] = addLen;
                    sizeNeeds[a_len] += addLen;
                } else 0 > addLen && (sizeNeeds[a_len] = clientSizeNeeds[a_len] + li.spacings[a_len]);
            }
            var useLog = def.debug >= 10, layoutInfoPrev = this._layoutInfo || null;
            if (layoutInfoPrev) {
                if (!def.get(ka, "force", !1)) return;
                layoutInfoPrev.previous = null;
            }
            var canChange = def.get(ka, "canChange", !0), sizeAvailable = def.get(ka, "sizeAvailable"), sizeRef = def.get(ka, "sizeRef") || sizeAvailable && pvc_Size.clone(sizeAvailable), sizeMin = this.chart.parent ? {
                width: 0,
                height: 0
            } : this.sizeMin.resolve(null), sizeMax = this.sizeMax.resolve(sizeRef), sizeFix = def.get(ka, "size") || this.size.resolve(sizeRef);
            pvc_Size.applyMin(sizeMax, sizeMin);
            pvc_Size.applyMinMax(sizeFix, sizeMin, sizeMax);
            if (!sizeAvailable) {
                if (null == sizeFix.width || null == sizeFix.height) throw def.error.operationInvalid("Panel layout without width or height set.");
                sizeAvailable = pvc_Size.clone(sizeFix);
                sizeRef || (sizeRef = pvc_Size.clone(sizeAvailable));
            }
            var borderHalf = this.borderWidth / 2, marginsArg = def.get(ka, "margins"), paddingsArg = def.get(ka, "paddings"), margins = marginsArg ? pvc_Sides.updateSize(marginsArg) : pvc_Sides.inflate(this.margins.resolve(sizeRef), borderHalf), paddings = paddingsArg ? pvc_Sides.updateSize(paddingsArg) : pvc_Sides.inflate(this.paddings.resolve(sizeRef), borderHalf), spaceW = margins.width + paddings.width, spaceH = margins.height + paddings.height, sizeAvailableInput = pvc_Size.clone(sizeAvailable);
            pvc_Size.applyMinMax(sizeAvailable, sizeMin, sizeMax);
            var sizeIncrease = {
                width: Math.max(0, sizeAvailable.width - sizeAvailableInput.width),
                height: Math.max(0, sizeAvailable.height - sizeAvailableInput.height)
            }, clientSizeAvailable = pvc_Size.deflate(sizeAvailable, spaceW, spaceH), clientSizeFix = pvc_Size.deflate(sizeFix, spaceW, spaceH), clientSizeAvailableInput = pvc_Size.clone(clientSizeAvailable);
            if (useLog) {
                if (sizeAvailable.width - sizeAvailableInput.width || sizeAvailable.height - sizeAvailableInput.height) {
                    this.log("Size          -> " + def.describe(sizeAvailableInput));
                    this.log("Size (min/max)-> " + def.describe(sizeAvailable));
                } else this.log("Size          -> " + def.describe(sizeAvailable));
                (margins.width || margins.height) && this.log(" Margins      -> " + def.describe(margins));
                (paddings.width || paddings.height) && this.log("  Paddings    -> " + def.describe(paddings));
                this.log("   ClientSize -> " + def.describe(clientSizeAvailable));
            }
            var liRestrictions = {
                canChange: canChange,
                sizeMin: sizeMin,
                sizeMax: sizeMax,
                size: sizeFix,
                clientSizeMin: pvc_Size.deflate(sizeMin, spaceW, spaceH),
                clientSizeMax: pvc_Size.deflate(sizeMax, spaceW, spaceH),
                clientSize: clientSizeFix
            }, li = this._layoutInfo = {
                sizeRef: sizeRef,
                referenceSize: sizeRef,
                desiredClientSize: clientSizeFix,
                borderWidth: this.borderWidth,
                margins: margins,
                paddings: paddings,
                spacings: {
                    width: spaceW,
                    height: spaceH
                },
                size: sizeAvailable,
                clientSize: clientSizeAvailable,
                clientSizePage: layoutInfoPrev ? layoutInfoPrev.clientSizePage : pvc_Size.clone(clientSizeAvailable),
                previous: layoutInfoPrev,
                restrictions: liRestrictions,
                sizeIncrease: null,
                clientSizeIncrease: null
            }, clientSizeNeeds = this._calcLayout(li) || clientSizeAvailable;
            this.isVisible = clientSizeNeeds.width > 0 && clientSizeNeeds.height > 0;
            if (this.isVisible) {
                li.clientSize = pvc_Size.applyMinMax(clientSizeNeeds, liRestrictions.clientSizeMin, liRestrictions.clientSizeMax);
                var clientSizeIncrease = {
                    width: 0,
                    height: 0
                }, sizeNeeds = li.size;
                processSizeDirection.call(this, "width");
                processSizeDirection.call(this, "height");
                (clientSizeIncrease.width || clientSizeIncrease.height) && (li.clientSizeIncrease = clientSizeIncrease);
                (sizeIncrease.width || sizeIncrease.height) && (li.sizeIncrease = sizeIncrease);
                if (li.sizeIncrease && (!marginsArg || !paddingsArg)) {
                    var sizeRef2 = pvc_Size.clone(sizeRef);
                    sizeIncrease.width && (sizeRef2.width += sizeIncrease.width);
                    sizeIncrease.height && (sizeRef2.height += sizeIncrease.height);
                    marginsArg || (margins = pvc_Sides.inflate(this.margins.resolve(sizeRef2), borderHalf));
                    paddingsArg || (paddings = pvc_Sides.inflate(this.paddings.resolve(sizeRef2), borderHalf));
                    li.margins = margins;
                    li.paddings = paddings;
                    li.spacings.width = margins.width + paddings.width;
                    li.spacings.height = margins.height + paddings.height;
                }
            } else li.size = {
                width: 0,
                height: 0
            };
            li.desiredClientSize = li.restrictions = li.previous = null;
            this.width = this.isVisible ? sizeNeeds.width : 0;
            this.height = this.isVisible ? sizeNeeds.height : 0;
            if (useLog) {
                this.log("   ClientSize <- " + def.describe(li.clientSize));
                li.clientSizeIncrease && this.log("             (+) " + def.describe(li.clientSizeIncrease));
                (paddings.width || paddings.height) && this.log("  Paddings    <- " + def.describe(li.paddings));
                (margins.width || margins.height) && this.log(" Margins      <- " + def.describe(li.margins));
                this.log("Size          <- " + def.describe(li.size));
                li.sizeIncrease && this.log("             (+) " + def.describe(li.sizeIncrease));
            }
            this._onLaidOut();
        },
        _onLaidOut: function() {
            this.isRoot && this.chart._onLaidOut();
        },
        _getLayoutState: function() {
            var li = this._layoutInfo, anchor = this.anchor;
            "fill" === anchor && (anchor = null);
            return {
                size: anchor ? pvc_Size.toOrtho(li.size, anchor).resolve() : li.size,
                margins: li.margins,
                paddings: li.paddings
            };
        },
        getLayout: function() {
            return this._layoutInfo || null;
        },
        _calcLayout: function(layoutInfo) {
            function doMaxTimes(maxTimes, fun, ctx) {
                for (var remTimes = maxTimes, index = 0; remTimes--; ) {
                    if (fun.call(ctx, remTimes, index, maxTimes) === !1) return !0;
                    index++;
                }
                return !1;
            }
            function layoutCycle(remTimes, iteration, maxTimes) {
                useLog && me.log.group("Iteration #" + (iteration + 1) + " / " + maxTimes);
                try {
                    margins = new pvc_Sides(0);
                    remSize = def.copyOwn(clientSize);
                    for (var child, canResize = remTimes > 0, index = 0, count = sideChildren.length; count > index; ) {
                        child = sideChildren[index];
                        useLog && child.log.group("Layout SIDE child");
                        try {
                            if (layoutChild.call(this, child, canResize)) return !0;
                        } finally {
                            useLog && child.log.groupEnd();
                        }
                        index++;
                    }
                    index = 0;
                    count = fillChildren.length;
                    for (;count > index; ) {
                        child = fillChildren[index];
                        useLog && child.log.group("Layout FILL child");
                        try {
                            if (layoutChild.call(this, child, canResize)) return !0;
                        } finally {
                            useLog && child.log.groupEnd();
                        }
                        index++;
                    }
                    return !1;
                } finally {
                    useLog && me.log.groupEnd();
                }
            }
            function layoutChild(child, canResize) {
                var paddings, resized = !1;
                childKeyArgs.canChange = canResize;
                doMaxTimes(6, function(remTimes, iteration, maxTimes) {
                    useLog && child.log.group("Iteration #" + (iteration + 1) + " / " + maxTimes);
                    try {
                        childKeyArgs.sizeAvailable = new pvc_Size(remSize);
                        childKeyArgs.paddings = paddings;
                        childKeyArgs.canChange = remTimes > 0;
                        child.layout(childKeyArgs);
                        if (child.isVisible) {
                            resized = checkChildResize.call(this, child, canResize);
                            if (resized) return !1;
                            var contentOverflow = child._layoutInfo.contentOverflow;
                            if (checkContentOverflowChanged(paddings, contentOverflow)) {
                                paddings = contentOverflow;
                                if (remTimes > 0) {
                                    paddings = new pvc_Sides(paddings);
                                    useLog && child.log("Child changed content overflow: " + def.describe(paddings));
                                    return !0;
                                }
                                useLog && child.log.warn("Child content overflow changed, but iterations limit has been reached.");
                            }
                            positionChild.call(this, child);
                            "fill" !== child.anchor && updateSide.call(this, child);
                        }
                        return !1;
                    } finally {
                        useLog && child.log.groupEnd();
                    }
                }, this);
                return resized;
            }
            function checkContentOverflowChanged(paddings, contentOverflow) {
                return contentOverflow ? def.query(pvc_Sides.names).each(function(side) {
                    var curPad = paddings && paddings[side] || 0, newPad = contentOverflow && contentOverflow[side] || 0;
                    return Math.abs(newPad - curPad) >= pvc.roundPixel.epsilon ? !1 : void 0;
                }) : !1;
            }
            function checkChildResize(child, canResize) {
                function checkDimension(a_len) {
                    var addLen = sizeIncrease[a_len];
                    if (addLen > pvc.roundPixel.epsilon) if (canResize) {
                        resized = !0;
                        remSize[a_len] += addLen;
                        clientSize[a_len] += addLen;
                    } else useLog && child.log.warn("Child wanted more " + a_len + ", but layout iterations limit has been reached.");
                }
                var resized = !1, sizeIncrease = child.getLayout().sizeIncrease;
                sizeIncrease && ("fill" === child.anchor ? pvc_Size.names.forEach(checkDimension) : checkDimension(child.anchorLength()));
                return resized;
            }
            function positionChild(child) {
                var sidePos, side = child.anchor, align = child.align, alignTo = child.alignTo;
                if ("fill" === side) {
                    side = "left";
                    sidePos = margins.left + remSize.width / 2 - child.width / 2;
                    align = alignTo = "middle";
                } else sidePos = margins[side];
                var sideo, sideOPosChildOffset;
                switch (align) {
                  case "top":
                  case "bottom":
                  case "left":
                  case "right":
                    sideo = align;
                    sideOPosChildOffset = 0;
                    break;

                  case "center":
                  case "middle":
                    sideo = altMap[aoMap[side]];
                    sideOPosChildOffset = -child[aolMap[sideo]] / 2;
                }
                var sideOPosParentOffset, sideOTo;
                switch (alignTo) {
                  case "top":
                  case "bottom":
                  case "left":
                  case "right":
                    sideOTo = alignTo;
                    sideOPosParentOffset = sideOTo !== sideo ? remSize[aolMap[sideo]] : 0;
                    break;

                  case "center":
                  case "middle":
                    sideOTo = altMap[aoMap[side]];
                    sideOPosParentOffset = remSize[aolMap[sideo]] / 2;
                    break;

                  case "page-center":
                  case "page-middle":
                    sideOTo = altMap[aoMap[side]];
                    var lenProp = aolMap[sideo], pageLen = Math.min(remSize[lenProp], layoutInfo.clientSizePage[lenProp]);
                    sideOPosParentOffset = pageLen / 2;
                }
                var sideOPos = margins[sideOTo] + sideOPosParentOffset + sideOPosChildOffset, resolvedOffset = child.offset.resolve(remSize);
                if (resolvedOffset) {
                    sidePos += resolvedOffset[aofMap[side]] || 0;
                    sideOPos += resolvedOffset[aofMap[sideo]] || 0;
                }
                if (child.keepInBounds) {
                    0 > sidePos && (sidePos = 0);
                    0 > sideOPos && (sideOPos = 0);
                }
                child.setPosition(def.set({}, side, sidePos, sideo, sideOPos));
            }
            function updateSide(child) {
                var side = child.anchor, sideol = aolMap[side], olen = child[sideol];
                margins[side] += olen;
                remSize[sideol] -= olen;
            }
            var clientSize, margins, remSize, useLog, me = this;
            if (me._children) {
                var aolMap = pvc.BasePanel.orthogonalLength, aoMap = pvc.BasePanel.relativeAnchor, altMap = pvc.BasePanel.leftTopAnchor, aofMap = pvc_Offset.namesSidesToOffset, fillChildren = [], sideChildren = [];
                me._children.forEach(function(child) {
                    var a = child.anchor;
                    if (a) if ("fill" === a) fillChildren.push(child); else {
                        def.hasOwn(aoMap, a) || def.fail.operationInvalid("Unknown anchor value '{0}'", [ a ]);
                        sideChildren.push(child);
                    }
                });
                useLog = def.debug >= 10;
                clientSize = def.copyOwn(layoutInfo.clientSize);
                var childKeyArgs = {
                    force: !0,
                    sizeRef: clientSize
                };
                useLog && me.log.group("CCC DOCK LAYOUT");
                try {
                    doMaxTimes(5, layoutCycle, me);
                } finally {
                    useLog && me.log.groupEnd();
                }
            }
            return clientSize;
        },
        invalidateLayout: function() {
            this._layoutInfo = null;
            this._children && this._children.forEach(function(c) {
                c.invalidateLayout();
            });
        },
        _create: function(force) {
            if (!this.pvPanel || force) {
                this.pvPanel = null;
                this.pvRootPanel && (this.pvRootPanel = null);
                delete this._signs;
                this.layout();
                if (this.isTopRoot && this.chart._isMultiChartOverflowClip) return;
                if (!this.isVisible) return;
                this.isRoot && this._creating();
                var margins = this._layoutInfo.margins, paddings = this._layoutInfo.paddings;
                if (this.isTopRoot) {
                    this.pvRootPanel = this.pvPanel = new pv.Panel().canvas(this.chart.options.canvas);
                    var scene = new pvc.visual.Scene(null, {
                        panel: this
                    });
                    this.pvRootPanel.lock("data", [ scene ]);
                    if (margins.width > 0 || margins.height > 0) {
                        this.pvPanel.width(this.width).height(this.height);
                        this.pvPanel = this.pvPanel.add(pv.Panel);
                    }
                } else this.pvPanel = this.parent.pvPanel.add(this.type);
                this.pvPanel.isPointingBarrier = !0;
                var pvBorderPanel = this.pvPanel, width = this.width - margins.width, height = this.height - margins.height;
                pvBorderPanel.width(width).height(height);
                def.debug >= 15 && (margins.width > 0 || margins.height > 0) && (this.isTopRoot ? this.pvRootPanel : this.parent.pvPanel).add(this.type).width(this.width).height(this.height).left(null != this.position.left ? this.position.left : null).right(null != this.position.right ? this.position.right : null).top(null != this.position.top ? this.position.top : null).bottom(null != this.position.bottom ? this.position.bottom : null).strokeStyle("orange").lineWidth(1).strokeDasharray("- .");
                var hasPositions = {};
                def.eachOwn(this.position, function(v, side) {
                    pvBorderPanel[side](v + margins[side]);
                    hasPositions[this.anchorLength(side)] = !0;
                }, this);
                if (!hasPositions.width) {
                    margins.left > 0 && pvBorderPanel.left(margins.left);
                    margins.right > 0 && pvBorderPanel.right(margins.right);
                }
                if (!hasPositions.height) {
                    margins.top > 0 && pvBorderPanel.top(margins.top);
                    margins.bottom > 0 && pvBorderPanel.bottom(margins.bottom);
                }
                (paddings.width > 0 || paddings.height > 0) && (this.pvPanel = pvBorderPanel.add(pv.Panel).width(width - paddings.width).height(height - paddings.height).left(paddings.left).top(paddings.top));
                pvBorderPanel.borderPanel = pvBorderPanel;
                pvBorderPanel.paddingPanel = this.pvPanel;
                this.pvPanel.paddingPanel = this.pvPanel;
                this.pvPanel.borderPanel = pvBorderPanel;
                if (def.debug >= 15) {
                    this.pvPanel.strokeStyle("lightgreen").lineWidth(1).strokeDasharray("- ");
                    this.pvPanel !== pvBorderPanel && pvBorderPanel.strokeStyle("blue").lineWidth(1).strokeDasharray(". ");
                }
                var extensionId = this._getExtensionId();
                new pvc.visual.Panel(this, null, {
                    panel: pvBorderPanel,
                    extensionId: extensionId
                });
                this._createCore(this._layoutInfo);
                if (this.isTopRoot) {
                    this.chart._multiChartOverflowClipped && this._addMultichartOverflowClipMarker();
                    this._initSelection();
                    this.interactive() && "near" === this.chart._pointingOptions.mode && this._requirePointEvent();
                }
                this.applyExtensions();
                if (this.isRoot && def.debug > 5) {
                    var out = [ "SCALES SUMMARY", def.logSeparator ];
                    this.chart.axesList.forEach(function(axis) {
                        var scale = axis.scale;
                        if (scale) {
                            var d = scale.domain && scale.domain(), r = scale.range && scale.range();
                            out.push(axis.id);
                            out.push("    domain: " + (d ? def.describe(d) : "?"));
                            out.push("    range : " + (r ? def.describe(r) : "?"));
                        }
                    }, this);
                    this.log(out.join("\n"));
                }
            }
        },
        _creating: function() {
            this._children && this._children.forEach(function(c) {
                c._creating();
            });
        },
        _createCore: function(layoutInfo) {
            this._children && this._children.forEach(function(c) {
                c._create();
            });
        },
        render: function(ka) {
            if (!this.isTopRoot) return this.topRoot.render(ka);
            this._create(def.get(ka, "recreate", !1));
            if ((!this.isTopRoot || !this.chart._isMultiChartOverflowClip) && this.isVisible) {
                var pvPanel = this.pvRootPanel;
                this._onRender();
                var prevAnimating = this._animating, animate = this.animatable();
                this._animating = animate && !def.get(ka, "bypassAnimation", !1) ? 1 : 0;
                try {
                    pvPanel.render();
                    if (this._animating) {
                        this._animating = 2;
                        var me = this;
                        pvPanel.transition().duration(2e3).ease("cubic-in-out").start(function() {
                            if (prevAnimating) prevAnimating = 0; else {
                                me._animating = 0;
                                me._onRenderEnd(!0);
                            }
                        });
                    } else this._onRenderEnd(!1);
                } finally {
                    this._animating = 0;
                }
            }
        },
        _onRender: function() {
            var renderCallback = this.chart.options.renderCallback;
            if (renderCallback) if (this.compatVersion() <= 1) renderCallback.call(this.chart); else {
                var context = this.context();
                renderCallback.call(context, context.scene);
            }
        },
        _onRenderEnd: function(animated) {
            this._children && this._children.forEach(function(c) {
                c._onRenderEnd(animated);
            });
            if (this.isTopRoot) {
                var renderedCallback = this.chart.options.renderedCallback;
                if (renderedCallback) {
                    var context = this.context();
                    renderedCallback.call(context, context.scene);
                }
            }
        },
        renderInteractive: function() {
            if (this.isVisible) {
                var pvMarks = this._getSelectableMarks();
                if (pvMarks && pvMarks.length) pvMarks.forEach(function(pvMark) {
                    pvMark.render();
                }); else if (!this._children) return void this.pvPanel.render();
                this._children && this._children.forEach(function(c) {
                    c.renderInteractive();
                });
            }
        },
        _getSelectableMarks: function() {
            return this._rubberSelectableMarks;
        },
        animatable: function() {
            return this.base() || !!this._children && this._children.some(function(c) {
                return c.animatable();
            });
        },
        animate: function(start, end) {
            return 1 === this.topRoot._animating ? start : end;
        },
        animatingStart: function() {
            return 1 === this.topRoot._animating;
        },
        animating: function() {
            return this.topRoot._animating > 0;
        },
        setPosition: function(position) {
            for (var side in position) if (def.hasOwn(pvc_Sides.namesSet, side)) {
                var s = position[side];
                if (null === s) delete this.position[side]; else {
                    s = +s;
                    !isNaN(s) && isFinite(s) && (this.position[side] = s);
                }
            }
        },
        createAnchoredSize: function(anchorLength, size) {
            return this.isAnchorTopOrBottom() ? {
                width: size.width,
                height: Math.min(size.height, anchorLength)
            } : {
                width: Math.min(size.width, anchorLength),
                height: size.height
            };
        },
        applyExtensions: function() {
            this._signs && this._signs.forEach(function(s) {
                s.applyExtensions();
            });
        },
        extend: function(mark, id, ka) {
            this.chart.extend(mark, this._makeExtensionAbsId(id), ka);
        },
        extendAbs: function(mark, absId, ka) {
            this.chart.extend(mark, absId, ka);
        },
        _extendSceneType: function(typeKey, type, names) {
            var typeExts = def.get(this._sceneTypeExtensions, typeKey);
            typeExts && pvc.extendType(type, typeExts, names);
        },
        _absBaseExtId: {
            abs: "base"
        },
        _absSmallBaseExtId: {
            abs: "smallBase"
        },
        _getExtensionId: function() {
            return this.isRoot ? this.chart.parent ? this._absSmallBaseExtId : this._absBaseExtId : void 0;
        },
        _getExtensionPrefix: function() {
            return this._extensionPrefix;
        },
        _makeExtensionAbsId: function(id) {
            return pvc.makeExtensionAbsId(id, this._getExtensionPrefix());
        },
        _getExtension: function(id, prop) {
            return this.chart._getExtension(this._makeExtensionAbsId(id), prop);
        },
        _getExtensionAbs: function(absId, prop) {
            return this.chart._getExtension(absId, prop);
        },
        _getConstantExtension: function(id, prop) {
            return this.chart._getConstantExtension(this._makeExtensionAbsId(id), prop);
        },
        getPvPanel: function(layer) {
            var mainPvPanel = this.pvPanel;
            if (!layer) return mainPvPanel;
            if (!this.parent) throw def.error.operationInvalid("Layers are not possible in a root panel.");
            if (!mainPvPanel) throw def.error.operationInvalid("Cannot access layer panels without having created the main panel.");
            var pvPanel = null;
            this._layers ? pvPanel = this._layers[layer] : this._layers = {};
            if (!pvPanel) {
                var pvParentPanel = this.parent.pvPanel;
                pvPanel = pvParentPanel.borderPanel.add(this.type).extend(mainPvPanel.borderPanel);
                var pvBorderPanel = pvPanel;
                mainPvPanel !== mainPvPanel.borderPanel && (pvPanel = pvBorderPanel.add(pv.Panel).extend(mainPvPanel));
                pvBorderPanel.borderPanel = pvBorderPanel;
                pvBorderPanel.paddingPanel = pvPanel;
                pvPanel.paddingPanel = pvPanel;
                pvPanel.borderPanel = pvBorderPanel;
                this.initLayerPanel(pvPanel, layer);
                this._layers[layer] = pvPanel;
            }
            return pvPanel;
        },
        initLayerPanel: function() {},
        _getV1DimName: function(v1Dim) {
            var dimNames = this._v1DimName || (this._v1DimNameCache = {}), dimName = dimNames[v1Dim];
            if (null == dimName) {
                var role = this.visualRoles[this._v1DimRoleName[v1Dim]];
                dimName = role ? role.lastDimensionName() : "";
                dimNames[v1Dim] = dimName;
            }
            return dimName;
        },
        _getV1Datum: function(scene) {
            return scene.datum;
        },
        context: function() {
            var context = this._context;
            !context || context.isPinned ? context = this._context = new pvc.visual.Context(this) : visualContext_update.call(context);
            return context;
        },
        visualRolesOf: function(dimName, includeChart) {
            return includeChart ? this.chart.visualRolesOf(dimName) : null;
        },
        _isTooltipEnabled: function() {
            return !this.selectingByRubberband() && !this.animating();
        },
        _getTooltipFormatter: function(tipOptions) {
            var isV1Compat = this.compatVersion() <= 1, tooltipFormat = tipOptions.format;
            if (!tooltipFormat) {
                if (!isV1Compat) return this._summaryTooltipFormatter.bind(this);
                tooltipFormat = this.chart.options.v1StyleTooltipFormat;
                if (!tooltipFormat) return;
            }
            return isV1Compat ? function(context) {
                return tooltipFormat.call(context.panel, context.getV1Series(), context.getV1Category(), context.getV1Value() || "", context.getV1Datum());
            } : function(context) {
                return tooltipFormat.call(context, context.scene);
            };
        },
        CSS_TT_CLASS: "ccc-tt",
        _summaryTooltipFormatter: function(context) {
            function renderRows() {
                var rows = [];
                firstDatum.isTrend && rows.push(tag("tr", {
                    "class": ttClasses("trendLabel", "trend-" + escapeCssClass(firstDatum.trend.type))
                }, tag("td", {
                    colspan: 3
                }, tag("span", null, escapeHtml(firstDatum.trend.label)))));
                if (isSingleGroup) {
                    rows.push.apply(rows, renderSingleGroupCommonDims());
                    commonDimNames && hasManyRealDatums && rows.push(tag("tr", {
                        "class": ttClasses("dimSep")
                    }, '<td colspan="3"><hr/></td>'));
                }
                rows.push.apply(rows, renderRemainingDims());
                hasManyRealDatums && rows.push(tag("tr", {
                    "class": ttClasses("datumCount")
                }, '<td colspan="3"><span>' + realDatums.length + "</span></td>"));
                return rows;
            }
            function renderSingleGroupCommonDims() {
                var commonAtoms = group.atoms;
                return complexType.sortDimensionNames(def.keys(commonAtoms)).map(function(n) {
                    var value, dimType, atom = commonAtoms[n];
                    if (null != (value = atom.value)) {
                        (commonDimNames || (commonDimNames = {}))[n] = !0;
                        dimType = atom.dimension.type;
                        if (!dimType.isHidden) return renderDim(dimType, value, atom.label);
                    }
                    return "";
                });
            }
            function renderRemainingDims() {
                return complexType.dimensionsList().map(renderRemainingDim);
            }
            function renderRemainingDim(dimType) {
                var dimName = dimType.name;
                if (dimType.isHidden || def.getOwn(commonDimNames, dimName)) return "";
                var dim, value, valueLabel, dimAggr, calcPct, atom, dimInterp;
                if (isSingleDatum) {
                    atom = firstDatum.atoms[dimName];
                    value = atom.value;
                    valueLabel = atom.label;
                    dimType.valueType === Number && null != value && (calcPct = calcAtomPct.bind(null, atom));
                    dimInterp = firstDatum.isInterpolated && firstDatum.interpDimName === dimName ? firstDatum.interpolation : null;
                } else {
                    allGroup || (allGroup = scene.allGroup());
                    dim = allGroup.dimensions(dimName);
                    if (dimType.valueType === Number) {
                        hasManyRealDatums && (dimAggr = "sum");
                        value = dim.value(visibleKeyArgs);
                        valueLabel = dim.format(value);
                        calcPct = null != value ? calcGroupDimPct.bind(null, dim) : null;
                        null == chartInterpolatable && (chartInterpolatable = chart.interpolatable());
                        chartInterpolatable && (dimInterp = Q(datums).where(function(d) {
                            return d.isInterpolated && d.interpDimName === dimName;
                        }).select(function(d) {
                            return d.interpolation;
                        }).first());
                    } else {
                        hasManyRealDatums && (dimAggr = "list");
                        value = valueLabel = dim.atoms(visibleKeyArgs).filter(function(a) {
                            return null != a.value;
                        }).map(function(a) {
                            return a.label || "- ";
                        }).join(", ");
                        value || (value = null);
                    }
                }
                return renderDim(dimType, value, valueLabel, dimAggr, calcPct, dimInterp);
            }
            function renderDim(dimType, value, valueLabel, dimAggr, calcPct, dimInterp) {
                var rowClasses = ttClasses("dim", "dimValueType-" + dimType.valueTypeName, "dim" + (dimType.isDiscrete ? "Discrete" : "Continuous"), dimAggr ? "dimAgg" : "", dimAggr ? "dimAgg-" + dimAggr : ""), anyPercentRole = !1, visRoles = me.visualRolesOf(dimType.name, !0), dimRolesHtml = visRoles ? visRoles.map(function(r) {
                    calcPct && (anyPercentRole |= r.isPercent);
                    return tag("span", {
                        "class": ttClasses("role", "role-" + r.name)
                    }, tag("span", {
                        "class": ttClasses("roleIcon")
                    }, ""), tag("span", {
                        "class": ttClasses("roleLabel")
                    }, escapeHtml(r.label)));
                }) : "";
                return tag("tr", {
                    "class": rowClasses
                }, tag("td", {
                    "class": ttClasses("dimLabel")
                }, tag("span", null, escapeHtml(dimType.label))), tag("td", {
                    "class": ttClasses("dimRoles")
                }, dimRolesHtml), tag("td", {
                    "class": ttClasses("dimValue", null == value ? "valueNull" : "")
                }, tag("span", {
                    "class": ttClasses("value")
                }, escapeHtml(valueLabel)), anyPercentRole ? " " + tag("span", {
                    "class": ttClasses("valuePct")
                }, function() {
                    var valPct = calcPct(), formatter = dimType.format().percent();
                    return escapeHtml(formatter(valPct));
                }) : "", dimInterp ? " " + tag("span", {
                    "class": ttClasses("interp", "interp-" + escapeCssClass(dimInterp))
                }, escapeHtml(def.firstUpperCase(dimInterp) + " interp.")) : ""));
            }
            function calcGroupDimPct(dim) {
                return dim.valuePercent(visibleKeyArgs);
            }
            function calcAtomPct(atom) {
                var dimName = atom.dimension.name;
                return isSingleGroup ? calcGroupDimPct(group.dimensions(dimName)) : data.dimensions(dimName).percent(atom.value, visibleKeyArgs);
            }
            var datums, scene = context.scene, firstDatum = scene.datum, Q = def.query;
            if (!firstDatum) return "";
            datums = scene.datums().array();
            if (Q(datums).all(function(d) {
                return d.isNull;
            })) return "";
            var chartInterpolatable, allGroup, commonDimNames, me = this, ttClass = this.CSS_TT_CLASS, visibleKeyArgs = {
                visible: !0
            }, escapeCssClass = def.css.escapeClass, escapeHtml = def.html.escape, classesHtml = def.html.classes, tag = def.html.tag, ttClasses = classesHtml.bind(null, ttClass), chart = context.chart, group = scene.group, data = scene.data(), complexType = data.type, realDatums = Q(datums).where(function(d) {
                return !d.isVirtual;
            }).array(), color = context.sign.defaultColor(scene), isSingleGroup = !!group && 1 === scene.groups.length, isSingleDatum = 1 === datums.length, hasManyRealDatums = realDatums.length > 1;
            return tag("div", {
                "class": ttClass
            }, function() {
                var tableClasses = def.array.appendMany([ "ds" ], me._getTooltipPanelClasses(), "chartOrient-" + (chart.isOrientationVertical() ? "v" : "h"));
                return tag("table", {
                    "class": ttClasses.apply(null, tableClasses),
                    "data-ccc-color": color && "none" !== color.color ? color.color : ""
                }, tag("tBody", null, renderRows));
            });
        },
        _getTooltipPanelClasses: function() {},
        _requirePointEvent: function() {
            if (!this.isTopRoot) return this.topRoot._requirePointEvent();
            if (!this._attachedPointEvent) {
                this.pvPanel.root.events("all").event("mousemove", pv.Behavior.point(this.chart._pointingOptions));
                this._attachedPointEvent = !0;
            }
        },
        _requireTipsy: function() {
            if (!this.isTopRoot) return this.topRoot._requireTipsy();
            if (!this._tipsy) {
                var chart = this.chart, tipOptions = def.create(chart._tooltipOptions);
                tipOptions.isEnabled = this._isTooltipEnabled.bind(this);
                "near" === chart._pointingOptions.mode && (tipOptions.usesPoint = !0);
                this._tipsy = pv.Behavior.tipsy(tipOptions);
            }
            return this._tipsy;
        },
        _onClick: function(context) {
            var handler = this.clickAction;
            handler && (this.compatVersion() <= 1 ? this._onV1Click(context, handler) : handler.call(context, context.scene));
        },
        _onDoubleClick: function(context) {
            var handler = this.doubleClickAction;
            handler && (this.compatVersion() <= 1 ? this._onV1DoubleClick(context, handler) : handler.call(context, context.scene));
        },
        _onV1Click: function(context, handler) {
            handler.call(context.pvMark, context.getV1Series(), context.getV1Category(), context.getV1Value(), context.event, context.getV1Datum());
        },
        _onV1DoubleClick: function(context, handler) {
            handler.call(context.pvMark, context.getV1Series(), context.getV1Category(), context.getV1Value(), context.event, context.getV1Datum());
        },
        _addMultichartOverflowClipMarker: function() {
            function getRadius(mark) {
                var r = mark.shapeRadius();
                if (null == r) {
                    var s = mark.shapeSize();
                    null != s && (r = Math.sqrt(s));
                }
                return r || dr;
            }
            var m = 10, dr = 5, pvDot = new pvc.visual.Dot(this, this.pvPanel, {
                noSelect: !0,
                noHover: !0,
                noClick: !0,
                noDoubleClick: !0,
                noTooltip: !1,
                freePosition: !0,
                extensionId: "multiChartOverflowMarker"
            }).lock("data").pvMark.shape("triangle").shapeRadius(dr).top(null).left(null).bottom(function() {
                return getRadius(this) + m;
            }).right(function() {
                return getRadius(this) + m;
            }).shapeAngle(0).lineWidth(1.5).strokeStyle("red").fillStyle("rgba(255, 0, 0, 0.2)");
            def.fun.is(pvDot.tooltip) && pvDot.tooltip("Some charts did not fit the available space.");
        },
        selectingByRubberband: function() {
            return this.topRoot._selectingByRubberband;
        },
        _initSelection: function() {
            var me = this, chart = me.chart;
            if (me.interactive()) {
                var clickClearsSelection = me.unselectable(), useRubberband = me.selectableByRubberband();
                if (useRubberband || clickClearsSelection) {
                    var data = me.data, pvParentPanel = me.pvRootPanel || me.pvPanel.paddingPanel;
                    me._getExtensionAbs("base", "fillStyle") || pvParentPanel.fillStyle(pvc.invisibleFill);
                    pvParentPanel.lock("events", "all");
                    if (useRubberband) {
                        var dMin2 = 4;
                        me._selectingByRubberband = !1;
                        var toScreen, rb, selectionEndedDate, selectBar = this.selectBar = new pvc.visual.Bar(me, pvParentPanel, {
                            extensionId: "rubberBand",
                            normalStroke: !0,
                            noHover: !0,
                            noSelect: !0,
                            noClick: !0,
                            noDoubleClick: !0,
                            noTooltip: !0
                        }).override("defaultStrokeWidth", def.fun.constant(1.5)).override("defaultColor", function(scene, type) {
                            return "stroke" === type ? "#86fe00" : "rgba(203, 239, 163, 0.6)";
                        }).override("interactiveColor", function(scene, color) {
                            return color;
                        }).pvMark.lock("visible", function() {
                            return !!rb;
                        }).lock("left", function() {
                            return rb.x;
                        }).lock("right").lock("top", function() {
                            return rb.y;
                        }).lock("bottom").lock("width", function() {
                            return rb.dx;
                        }).lock("height", function() {
                            return rb.dy;
                        }).lock("cursor").lock("events", "none");
                        pvParentPanel.intercept("data", function() {
                            var scenes = this.delegate();
                            scenes && scenes.forEach(function(scene) {
                                null == scene.x && (scene.x = scene.y = scene.dx = scene.dy = 0);
                            });
                            return scenes;
                        }).event("mousedown", pv.Behavior.select().autoRender(!1)).event("select", function(scene) {
                            if (rb) rb = new pv.Shape.Rect(scene.x, scene.y, scene.dx, scene.dy); else {
                                if (me.animating()) return;
                                if (scene.dx * scene.dx + scene.dy * scene.dy <= dMin2) return;
                                rb = new pv.Shape.Rect(scene.x, scene.y, scene.dx, scene.dy);
                                me._selectingByRubberband = !0;
                                toScreen || (toScreen = pvParentPanel.toScreenTransform());
                                me.rubberBand = rb.apply(toScreen);
                            }
                            selectBar.render();
                        }).event("selectend", function() {
                            if (rb) {
                                var ev = arguments[arguments.length - 1];
                                toScreen || (toScreen = pvParentPanel.toScreenTransform());
                                var rbs = rb.apply(toScreen);
                                rb = null;
                                me._selectingByRubberband = !1;
                                selectBar.render();
                                try {
                                    me._processRubberBand(rbs, ev);
                                } finally {
                                    selectionEndedDate = new Date();
                                }
                            }
                        });
                        clickClearsSelection && pvParentPanel.event("click", function() {
                            if (selectionEndedDate) {
                                var timeSpan = new Date() - selectionEndedDate;
                                if (300 > timeSpan) {
                                    selectionEndedDate = null;
                                    return;
                                }
                            }
                            data.clearSelected() && chart.updateSelections();
                        });
                    } else clickClearsSelection && pvParentPanel.event("click", function() {
                        data.clearSelected() && chart.updateSelections();
                    });
                }
            }
        },
        _processRubberBand: function(rb, ev, ka) {
            this.rubberBand = rb;
            try {
                this._onRubberBandSelectionEnd(ev, ka);
            } finally {
                this.rubberBand = null;
            }
        },
        _onRubberBandSelectionEnd: function(ev, ka) {
            def.debug >= 20 && this.log("rubberBand " + def.describe(this.rubberBand));
            ka = Object.create(ka || {});
            ka.toggle = !1;
            var datums = this._getDatumsOnRubberBand(ev, ka);
            if (datums && datums.length) {
                var chart = this.chart;
                chart._updatingSelections(function() {
                    datums = chart._onUserSelection(datums);
                    if (datums && datums.length) {
                        var clearBefore = !(ev.ctrlKey || ev.metaKey) && chart.options.ctrlSelectMode;
                        if (clearBefore) {
                            chart.data.owner.clearSelected();
                            cdo.Data.setSelected(datums, !0);
                        } else ka.toggle ? cdo.Data.toggleSelected(datums) : cdo.Data.setSelected(datums, !0);
                    }
                });
            }
        },
        _getDatumsOnRubberBand: function(ev, ka) {
            var datumMap = new def.Map();
            this._getDatumsOnRect(datumMap, this.rubberBand, ka);
            return datumMap.values();
        },
        _getDatumsOnRect: function(datumMap, rect, ka) {
            this._getOwnDatumsOnRect(datumMap, rect, ka);
            var cs = this._children;
            cs && cs.forEach(function(c) {
                c._getDatumsOnRect(datumMap, rect, ka);
            });
        },
        _getOwnDatumsOnRect: function(datumMap, rect, ka) {
            var me = this;
            if (!me.isVisible) return !1;
            var pvMarks = me._getSelectableMarks();
            if (!pvMarks || !pvMarks.length) return !1;
            var inCount = datumMap.count, selectionMode = def.get(ka, "markSelectionMode"), processDatum = function(datum) {
                datum.isNull || datumMap.set(datum.id, datum);
            }, processScene = function(scene) {
                scene.selectableByRubberband() && scene.datums().each(processDatum);
            }, processMark = function(pvMark) {
                pvMark.eachSceneWithDataOnRect(rect, processScene, null, selectionMode);
            };
            pvMarks.forEach(processMark);
            return inCount < datumMap.count;
        },
        isAnchorTopOrBottom: function(anchor) {
            anchor || (anchor = this.anchor);
            return "top" === anchor || "bottom" === anchor;
        },
        isOrientationVertical: function(o) {
            return this.chart.isOrientationVertical(o);
        },
        isOrientationHorizontal: function(o) {
            return this.chart.isOrientationHorizontal(o);
        }
    }).type().add({
        relativeAnchor: {
            top: "left",
            bottom: "left",
            left: "bottom",
            right: "bottom"
        },
        leftBottomAnchor: {
            top: "bottom",
            bottom: "bottom",
            left: "left",
            right: "left"
        },
        leftTopAnchor: {
            top: "top",
            bottom: "top",
            left: "left",
            right: "left"
        },
        horizontalAlign: {
            top: "right",
            bottom: "left",
            middle: "center",
            right: "right",
            left: "left",
            center: "center"
        },
        verticalAlign: {
            top: "top",
            bottom: "bottom",
            middle: "middle",
            right: "bottom",
            left: "top",
            center: "middle"
        },
        verticalAlign2: {
            top: "top",
            bottom: "bottom",
            middle: "middle",
            right: "top",
            left: "bottom",
            center: "middle"
        },
        relativeAnchorMirror: {
            top: "right",
            bottom: "right",
            left: "top",
            right: "top"
        },
        oppositeAnchor: {
            top: "bottom",
            bottom: "top",
            left: "right",
            right: "left"
        },
        parallelLength: {
            top: "width",
            bottom: "width",
            right: "height",
            left: "height"
        },
        orthogonalLength: {
            top: "height",
            bottom: "height",
            right: "width",
            left: "width"
        },
        oppositeLength: {
            width: "height",
            height: "width"
        }
    });
    def.scope(function() {
        var BasePanel = pvc.BasePanel, methods = {}, anchorDicts = {
            anchorOrtho: "relativeAnchor",
            anchorOrthoMirror: "relativeAnchorMirror",
            anchorOpposite: "oppositeAnchor",
            anchorLength: "parallelLength",
            anchorOrthoLength: "orthogonalLength"
        };
        def.eachOwn(anchorDicts, function(d, am) {
            var dict = BasePanel[d];
            methods[am] = function(a) {
                return dict[a || this.anchor];
            };
        });
        BasePanel.add(methods);
    });
    def.type("pvc.ContentPanel", pvc.BasePanel).add({
        anchor: "fill",
        _getExtensionId: function() {
            return [ {
                abs: this.chart.parent ? "smallContent" : "content"
            } ];
        }
    });
    var pvc_plotClassByType = {};
    def("pvc.visual.Plot", pvc.visual.OptionsBase.extend({
        init: function(chart, keyArgs) {
            var typePlots = def.getPath(chart, [ "plotsByType", this.type ]), index = typePlots ? typePlots.length : 0, globalIndex = chart.plotList.length, isMain = !globalIndex, internalPlot = def.get(keyArgs, "isInternal", !0);
            keyArgs = def.setDefaults(keyArgs, "byNaked", isMain, "byName", internalPlot, "byV1", internalPlot);
            internalPlot || (keyArgs.optionId = pvc.uniqueExtensionAbsPrefix());
            this.base(chart, this.type, index, keyArgs);
            if (this.name && ("$" === this.name || this.name.indexOf(".") >= 0)) throw def.error.argumentInvalid("name", def.format("Invalid plot name '{0}'.", [ this.name ]));
            this.prettyId = this.name || this.id;
            this.isInternal = !!internalPlot;
            this.isMain = isMain;
            var prefixes = this.extensionPrefixes = [ this.optionId ];
            if (internalPlot) {
                isMain && prefixes.push("");
                this.name && prefixes.push(this.name);
            }
            this.visualRoles = {};
            this.visualRoleList = [];
            this.dataCellList = [];
            this.dataCellsByRole = {};
            var plotSpec = def.get(keyArgs, "spec");
            plotSpec && this.processSpec(plotSpec);
        },
        methods: {
            _buildOptionId: function(keyArgs) {
                return def.get(keyArgs, "optionId", this.id);
            },
            processSpec: function(plotSpec) {
                var me = this, options = me.chart.options, extId = (me.isInternal ? me.name : null) || me.optionId;
                me.chart._processExtensionPointsIn(plotSpec, extId, function(optValue, optId, optName) {
                    switch (optName) {
                      case "name":
                      case "type":
                        break;

                      case "visualRoles":
                        me._visualRolesOptions = optValue;
                        def.object.is(optValue) && def.each(optValue, function(spec) {
                            def.object.is(spec) && spec.from && (spec.from = me.ensureAbsRoleRef(spec.from));
                        });
                        break;

                      default:
                        options[optId] = optValue;
                    }
                });
            },
            ensureAbsRoleRef: function(roleName) {
                return roleName && roleName.indexOf(".") < 0 ? this.prettyId + "." + roleName : roleName;
            },
            _getColorRoleSpec: def.fun.constant(null),
            _addVisualRole: function(name, spec) {
                var roleList = this.visualRoleList;
                spec = def.set(spec, "index", roleList.length, "plot", this);
                var role = new pvc.visual.Role(name, spec);
                this.visualRoles[name] = role;
                roleList.push(role);
                return role;
            },
            _addDataCell: function(dataCell) {
                this.dataCellList.push(dataCell);
                def.array.lazy(this.dataCellsByRole, dataCell.role.name).push(dataCell);
            },
            interpolatable: function() {
                return !1;
            },
            visualRole: function(name) {
                return def.getOwn(this.visualRoles, name);
            },
            initEnd: function() {
                this._initVisualRoles();
                this._initDataCells();
            },
            _initVisualRoles: function() {
                var roleSpec = this._getColorRoleSpec();
                roleSpec && this._addVisualRole("color", roleSpec);
            },
            _initDataCells: function() {
                if (this.visualRoles.color) {
                    var dataCell = this._getColorDataCell();
                    dataCell && this._addDataCell(dataCell);
                }
            },
            createVisibleData: function(baseData, ka) {
                var serRole = this.visualRoles.series;
                return serRole && serRole.isBound() ? serRole.flatten(baseData, ka) : baseData.where(null, ka);
            },
            interpolateDataCell: function() {},
            generateTrendsDataCell: function() {},
            getContinuousVisibleCellExtent: function(chart, valueAxis, valueDataCell) {
                if (valueDataCell.plot !== this) throw def.error.operationInvalid("Datacell not of this plot.");
                var valueRole = valueDataCell.role;
                chart._warnSingleContinuousValueRole(valueRole);
                if ("series" === valueRole.name) throw def.error.notImplemented();
                var isSumNorm = valueAxis.scaleSumNormalized(), data = chart.visiblePlotData(this, valueDataCell.dataPartValue), dimName = valueRole.lastDimensionName();
                if (isSumNorm) {
                    var sum = data.dimensionsSumAbs(dimName);
                    if (sum) return {
                        min: 0,
                        max: sum
                    };
                } else {
                    var useAbs = valueAxis.scaleUsesAbs(), extent = data.dimensions(dimName).extent({
                        abs: useAbs
                    });
                    if (extent) {
                        var minValue = extent.min.value, maxValue = extent.max.value;
                        return {
                            min: useAbs ? Math.abs(minValue) : minValue,
                            max: useAbs ? Math.abs(maxValue) : maxValue
                        };
                    }
                }
            },
            _getColorDataCell: function() {
                var colorRole = this.visualRoles.color;
                return colorRole ? new pvc.visual.ColorDataCell(this, "color", this.option("ColorAxis") - 1, colorRole, this.option("DataPart")) : void 0;
            }
        },
        options: {
            Orientation: {
                resolve: function(optionInfo) {
                    return optionInfo.specify(this._chartOption("orientation") || "vertical"), !0;
                },
                cast: String
            },
            ValuesVisible: {
                resolve: "_resolveFull",
                data: {
                    resolveV1: function(optionInfo) {
                        if (0 === this.globalIndex) {
                            var show = this._chartOption("showValues");
                            if (void 0 !== show) optionInfo.specify(show); else {
                                show = "point" !== this.type;
                                optionInfo.defaultValue(show);
                            }
                            return !0;
                        }
                    }
                },
                cast: Boolean,
                value: !1
            },
            ValuesAnchor: {
                resolve: "_resolveFull",
                cast: pvc.parseAnchor
            },
            ValuesFont: {
                resolve: "_resolveFull",
                cast: String,
                value: "10px sans-serif"
            },
            ValuesMask: {
                resolve: "_resolveFull",
                cast: String,
                value: "{value}"
            },
            ValuesOptimizeLegibility: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !1
            },
            ValuesOverflow: {
                resolve: "_resolveFull",
                cast: pvc.parseValuesOverflow,
                value: "hide"
            },
            DataPart: {
                resolve: "_resolveFull",
                cast: String,
                value: "0"
            },
            ColorAxis: {
                resolve: pvc.options.resolvers([ function(optionInfo) {
                    return 0 === this.globalIndex ? (optionInfo.specify(1), !0) : void 0;
                }, "_resolveFull" ]),
                cast: function(value) {
                    value = def.number.to(value);
                    return null != value ? def.between(value, 1, 10) : 1;
                },
                value: 1
            }
        },
        "type.methods": {
            registerClass: function(Class) {
                pvc_plotClassByType[Class.prototype.type] = Class;
            },
            getClass: function(type) {
                return def.getOwn(pvc_plotClassByType, type);
            }
        }
    }));
    var pvc_plotPanelClassByType = {};
    def.type("pvc.PlotPanel", pvc.BasePanel).init(function(chart, parent, plot, options) {
        null == options.sizeMin && (options.sizeMin = this._getOptionSizeMin(chart));
        null == options.paddings && (options.paddings = chart._axisOffsetPct);
        this.base(chart, parent, options);
        this.plot = plot;
        this._extensionPrefix = plot.extensionPrefixes;
        this.dataPartValue = plot.option("DataPart");
        this.axes.color = chart._getAxis("color", (plot.option("ColorAxis") || 0) - 1);
        this.orientation = plot.option("Orientation");
        this.valuesVisible = plot.option("ValuesVisible");
        this.valuesAnchor = plot.option("ValuesAnchor");
        this.valuesMask = plot.option("ValuesMask");
        this.valuesFont = plot.option("ValuesFont");
        this.valuesOverflow = plot.option("ValuesOverflow");
        this.valuesOptimizeLegibility = plot.option("ValuesOptimizeLegibility");
        this.visualRoles = plot.visualRoles;
        this.visualRoleList = plot.visualRoleList;
    }).add({
        anchor: "fill",
        visualRoles: null,
        visibleData: function(ka) {
            return this.chart.visiblePlotData(this.plot, this.dataPartValue, ka);
        },
        _getExtensionId: function() {
            return [ "chart", "plot" ];
        },
        _getOptionSizeMin: function(chart) {
            var plotSizeMin = chart.parent ? null : chart.options.plotSizeMin;
            return null != plotSizeMin ? pvc_Size.to(plotSizeMin) : null;
        },
        visualRolesOf: function(dimName, includeChart) {
            var visualRolesByDim = this._visRolesByDim;
            if (!visualRolesByDim) {
                visualRolesByDim = this._visRolesByDim = {};
                this.visualRoleList.forEach(function(r) {
                    var g = r.grouping;
                    g && g.dimensionNames().forEach(function(n) {
                        def.array.lazy(visualRolesByDim, n).push(r);
                    });
                });
            }
            var plotVisRoles = def.getOwn(visualRolesByDim, dimName, null), chartVisRoles = includeChart ? this.chart.visualRolesOf(dimName) : null;
            return plotVisRoles && chartVisRoles ? plotVisRoles.concat(chartVisRoles) : plotVisRoles || chartVisRoles;
        },
        _getTooltipPanelClasses: function() {
            return [ "plot", "plot-" + this.plot.type ];
        },
        isOrientationVertical: function() {
            return this.orientation === pvc.orientation.vertical;
        },
        isOrientationHorizontal: function() {
            return this.orientation === pvc.orientation.horizontal;
        }
    }).type().add({
        registerClass: function(Class, typeName) {
            pvc_plotPanelClassByType[typeName || Class.prototype.plotType] = Class;
        },
        getClass: function(typeName) {
            return def.getOwn(pvc_plotPanelClassByType, typeName);
        }
    });
    def.type("pvc.PlotBgPanel", pvc.BasePanel).init(function(chart, parent, options) {
        this.base(chart, parent, options);
    }).add({
        anchor: "fill",
        _getExtensionId: function() {
            return "plotBg";
        },
        _createCore: function(layoutInfo) {
            this.pvPanel.borderPanel.lock("zOrder", -13).antialias(!1);
            this.base(layoutInfo);
        }
    });
    def("pvc.visual.Legend", pvc.visual.OptionsBase.extend({
        init: function(chart, type, index, keyArgs) {
            keyArgs = def.set(keyArgs, "byNaked", !1);
            this.base(chart, type, index, keyArgs);
        },
        options: {
            Position: {
                resolve: "_resolveFull",
                cast: pvc.parsePosition,
                value: "bottom"
            },
            Size: {
                resolve: "_resolveFull",
                cast: legend_castSize
            },
            SizeMax: {
                resolve: "_resolveFull",
                cast: legend_castSize
            },
            ItemCountMax: {
                resolve: "_resolveFull",
                cast: def.number.toPositive
            },
            Align: {
                resolve: "_resolveFull",
                data: {
                    resolveDefault: function(optionInfo) {
                        var align, position = this.option("Position");
                        "top" !== position && "bottom" !== position ? align = "top" : this.chart.compatVersion() <= 1 && (align = "left");
                        return optionInfo.defaultValue(align), !0;
                    }
                },
                cast: legend_castAlign
            },
            Margins: {
                resolve: "_resolveFull",
                data: {
                    resolveDefault: function(optionInfo) {
                        if (this.chart.compatVersion() > 1) {
                            var position = this.option("Position"), margins = def.set({}, pvc.BasePanel.oppositeAnchor[position], 5);
                            optionInfo.defaultValue(margins);
                        }
                        return !0;
                    }
                },
                cast: pvc_Sides.as
            },
            Paddings: {
                resolve: "_resolveFull",
                cast: pvc_Sides.as,
                value: 5
            },
            Font: {
                resolve: "_resolveFull",
                cast: String
            },
            ItemSize: {
                resolve: "_resolveFull",
                cast: legendItem_castSize
            },
            Overflow: {
                resolve: "_resolveFull",
                cast: pvc.parseLegendOverflow,
                value: "clip"
            }
        }
    }));
    def.type("pvc.LegendPanel", pvc.BasePanel).init(function(chart, parent, options) {
        this.base(chart, parent, options);
        if (void 0 === options.font) {
            var extFont = this._getConstantExtension("label", "font");
            extFont && (this.font = extFont);
        }
        var I = pvc.visual.Interactive;
        this._ibits & I.Interactive && (this._ibits |= I.Clickable);
    }).add({
        pvRule: null,
        pvDot: null,
        pvLabel: null,
        anchor: "bottom",
        pvLegendPanel: null,
        textMargin: 6,
        itemPadding: 2.5,
        itemSize: null,
        markerSize: 15,
        font: "10px sans-serif",
        _calcLayout: function(layoutInfo) {
            return this._getRootScene().layout(layoutInfo);
        },
        _createCore: function(layoutInfo) {
            var clientSize = layoutInfo.clientSize, rootScene = this._getRootScene(), itemPadding = rootScene.vars.itemPadding, contentSize = rootScene.vars.contentSize, isHorizontal = this.isAnchorTopOrBottom(), a_top = isHorizontal ? "top" : "left", a_bottom = this.anchorOpposite(a_top), a_width = this.anchorLength(a_top), a_height = this.anchorOrthoLength(a_top), a_center = isHorizontal ? "center" : "middle", a_left = isHorizontal ? "left" : "top", a_right = this.anchorOpposite(a_left), leftOffset = 0;
            switch (this.align) {
              case a_right:
                leftOffset = clientSize[a_width] - contentSize[a_width];
                break;

              case a_center:
                leftOffset = (clientSize[a_width] - contentSize[a_width]) / 2;
            }
            this.pvPanel.borderPanel.overflow("hidden");
            var wrapper, pvLegendSectionPanel = this.pvPanel.add(pv.Panel).data(rootScene.vars.sections)[a_left](leftOffset)[a_top](function() {
                var prevSection = this.sibling();
                return prevSection ? prevSection[a_top] + prevSection[a_height] + itemPadding[a_height] : 0;
            })[a_width](function(section) {
                return section.size[a_width];
            })[a_height](function(section) {
                return section.size[a_height];
            });
            this.compatVersion() <= 1 && (wrapper = function(v1f) {
                return function(itemScene) {
                    return v1f.call(this, itemScene.vars.value.rawValue);
                };
            });
            var pvLegendItemPanel = this.pvLegendPanel = new pvc.visual.Panel(this, pvLegendSectionPanel, {
                extensionId: "panel",
                wrapper: wrapper,
                noSelect: !1,
                noHover: !0,
                noClick: !1,
                noClickSelect: !0
            }).pvMark.lock("data", function(section) {
                return section.items;
            })[a_right](null)[a_bottom](null)[a_left](function(clientScene) {
                for (var prevItem, index = this.index; index > 0 && !(prevItem = this.scene[--index]).visible; ) ;
                return prevItem && prevItem.visible ? prevItem[a_left] + prevItem[a_width] + clientScene.vars.itemPadding[a_width] : 0;
            })[a_top](isHorizontal ? function(itemScene) {
                var vars = itemScene.vars;
                return vars.section.size.height / 2 - vars.itemClientSize.height / 2;
            } : 0).height(function(itemScene) {
                return itemScene.vars.itemClientSize.height;
            }).width(isHorizontal ? function(itemScene) {
                return itemScene.vars.itemClientSize.width;
            } : function() {
                return this.parent.width();
            }), pvLegendMarkerPanel = new pvc.visual.Panel(this, pvLegendItemPanel, {
                extensionId: "markerPanel"
            }).pvMark.left(0).top(0).right(null).bottom(null).width(function(itemScene) {
                return itemScene.vars.markerSize;
            }).height(function(itemScene) {
                return itemScene.vars.itemClientSize.height;
            });
            if (def.debug >= 20) {
                pvLegendSectionPanel.strokeStyle("red").lineWidth(.5).strokeDasharray(".");
                pvLegendItemPanel.strokeStyle("green").lineWidth(.5).strokeDasharray(".");
                pvLegendMarkerPanel.strokeStyle("blue").lineWidth(.5).strokeDasharray(".");
            }
            rootScene.childNodes.forEach(function(groupScene) {
                var pvGroupPanel = new pvc.visual.Panel(this, pvLegendMarkerPanel).pvMark.visible(function(itemScene) {
                    return itemScene.parent === groupScene;
                });
                groupScene.renderer()(this, pvGroupPanel, wrapper);
            }, this);
            this.pvLabel = new pvc.visual.Label(this, pvLegendMarkerPanel.anchor("right"), {
                extensionId: "label",
                noTooltip: !1,
                noClick: !1,
                wrapper: wrapper
            }).intercept("textStyle", function(itemScene) {
                this._finished = !1;
                var baseTextStyle = this.delegateExtension() || "black";
                return this._finished || itemScene.isOn() ? baseTextStyle : pvc.toGrayScale(baseTextStyle, null, void 0, 150);
            }).pvMark.textAlign("left").text(function(itemScene) {
                var text = itemScene.labelText(), vars = itemScene.vars;
                vars.textSize.width > vars.labelWidthMax && (text = pvc.text.trimToWidthB(vars.labelWidthMax, text, vars.font, "..", !1));
                return text;
            }).textMargin(function(itemScene) {
                return itemScene.vars.textMargin;
            }).font(function(itemScene) {
                return itemScene.vars.font;
            }).textDecoration(function(itemScene) {
                return itemScene.isOn() ? "" : "line-through";
            }).cursor(function(itemScene) {
                return itemScene.executable() ? "pointer" : "default";
            });
            def.debug >= 16 && pvLegendMarkerPanel.anchor("right").add(pv.Panel)[this.anchorLength()](0)[this.anchorOrthoLength()](0).fillStyle(null).strokeStyle(null).lineWidth(0).add(pv.Line).data(function(scene) {
                var vars = scene.vars, labelBBox = pvc.text.getLabelBBox(Math.min(vars.labelWidthMax, vars.textSize.width), 2 * vars.textSize.height / 3, "left", "middle", 0, vars.textMargin), corners = labelBBox.source.points();
                corners.length > 1 && (corners = corners.concat(corners[0]));
                return corners;
            }).left(function(p) {
                return p.x;
            }).top(function(p) {
                return p.y;
            }).strokeStyle("red").lineWidth(.5).strokeDasharray("-");
        },
        _onClick: function(context) {
            var scene = context.scene;
            def.fun.is(scene.execute) && scene.executable() && scene.execute();
        },
        _getExtensionPrefix: function() {
            return "legend";
        },
        _getExtensionId: function() {
            return "area";
        },
        _getSelectableMarks: function() {
            return [ this.pvLegendPanel ];
        },
        _getRootScene: function() {
            var rootScene = this._rootScene;
            rootScene || (this._rootScene = rootScene = new pvc.visual.legend.LegendRootScene(null, {
                panel: this,
                source: this.chart.data,
                horizontal: this.isAnchorTopOrBottom(),
                font: this.font,
                markerSize: this.markerSize,
                textMargin: this.textMargin,
                itemPadding: this.itemPadding,
                itemSize: this.itemSize,
                itemCountMax: this.itemCountMax,
                overflow: this.overflow
            }));
            return rootScene;
        },
        _getTooltipFormatter: function(tipOptions) {
            tipOptions.isLazy = !1;
            return function(context) {
                var valueVar = context.scene.vars.value, valueText = valueVar.absLabel || valueVar.label, itemText = context.pvMark.text();
                return valueText !== itemText ? valueText : "";
            };
        }
    });
    def.type("pvc.visual.legend.LegendRootScene", pvc.visual.Scene).init(function(parent, keyArgs) {
        this.base(parent, keyArgs);
        this._unresolvedMarkerDiam = def.get(keyArgs, "markerSize");
        this._unresolvedItemPadding = new pvc_Sides(def.get(keyArgs, "itemPadding", 5));
        this._unresolvedItemSize = pvc_Size.to(def.get(keyArgs, "itemSize")) || new pvc_Size();
        this._itemCountMax = def.get(keyArgs, "itemCountMax");
        this._overflow = def.get(keyArgs, "overflow");
        def.set(this.vars, "horizontal", def.get(keyArgs, "horizontal", !1), "font", def.get(keyArgs, "font"), "textMargin", def.get(keyArgs, "textMargin", 6) - 3);
    }).add({
        layout: function(layoutInfo) {
            function layoutItem(itemScene) {
                var textSize = itemScene.labelTextSize(), hidden = !textSize || !textSize.width || !textSize.height;
                itemScene.isHidden = hidden;
                if (!hidden) {
                    var isFirstInSection, itemContentSize = {
                        width: textLeft + textSize.width,
                        height: Math.max(textSize.height, markerDiam)
                    }, itemSize = {
                        width: desiredItemSize.width || itemPadding.width + itemContentSize.width,
                        height: desiredItemSize.height || itemPadding.height + itemContentSize.height
                    }, itemClientSize = {
                        width: Math.max(0, itemSize.width - itemPadding.width),
                        height: Math.max(0, itemSize.height - itemPadding.height)
                    };
                    if (section) isFirstInSection = !section.items.length; else {
                        section = new pvc.visual.legend.LegendItemSceneSection(0);
                        isFirstInSection = !0;
                    }
                    var $newSectionWidth = section.size[a_width] + itemClientSize[a_width];
                    isFirstInSection || ($newSectionWidth += itemPadding[a_width]);
                    if (!isFirstInSection && $newSectionWidth > $sectionWidthMax) {
                        commitSection(!1);
                        $newSectionWidth = itemClientSize[a_width];
                    }
                    var sectionSize = section.size;
                    sectionSize[a_width] = $newSectionWidth;
                    sectionSize[a_height] = Math.max(sectionSize[a_height], itemClientSize[a_height]);
                    var sectionIndex = section.items.length;
                    totalItemCount++;
                    section.items.push(itemScene);
                    def.set(itemScene.vars, "section", section, "sectionIndex", sectionIndex, "textSize", textSize, "itemSize", itemSize, "itemClientSize", itemClientSize, "itemContentSize", itemContentSize);
                }
            }
            function commitSection(isLast) {
                var sectionSize = section.size;
                contentSize[a_height] += sectionSize[a_height];
                sections.length && (contentSize[a_height] += itemPadding[a_height]);
                contentSize[a_width] = Math.max(contentSize[a_width], sectionSize[a_width]);
                sections.push(section);
                isLast || (section = new pvc.visual.legend.LegendItemSceneSection(sections.length));
            }
            function isOverItemMax(itemCountMax) {
                return totalItemCount > itemCountMax;
            }
            function isOverflow() {
                var desiredWidth = clientSizeFix.width || 1 / 0, desiredHeight = clientSizeFix.height || 1 / 0;
                return Math.min(desiredWidth, clientSize.width) < contentSize.width || Math.min(desiredHeight, clientSize.height) < contentSize.height;
            }
            var totalItemCount = 0, clientSize = layoutInfo.clientSize;
            if (!(clientSize.width > 0 && clientSize.height > 0)) return new pvc_Size(0, 0);
            var clientSizeFix = layoutInfo.restrictions.clientSize;
            if (null != clientSizeFix.width && 0 == clientSizeFix.width || null != clientSizeFix.height && 0 == clientSizeFix.height) return new pvc_Size(0, 0);
            var itemPadding = this._unresolvedItemPadding.resolve(clientSize), extClientSize = {
                width: clientSize.width + itemPadding.width,
                height: clientSize.height + itemPadding.height
            }, desiredItemSize = this._unresolvedItemSize.resolve(extClientSize), desiredItemClientSize = {
                width: desiredItemSize.width && Math.max(0, desiredItemSize.width - itemPadding.width),
                height: desiredItemSize.height && Math.max(0, desiredItemSize.height - itemPadding.height)
            }, markerDiam = this._unresolvedMarkerDiam || desiredItemClientSize.height || 15;
            this.vars.itemPadding = itemPadding;
            this.vars.desiredItemSize = desiredItemSize;
            this.vars.desiredItemClientSize = desiredItemClientSize;
            this.vars.markerSize = markerDiam;
            var section, textLeft = markerDiam + this.vars.textMargin, labelWidthMax = Math.max(0, Math.min(desiredItemClientSize.width || 1 / 0, clientSizeFix.width || 1 / 0, clientSize.width) - textLeft), a_width = this.vars.horizontal ? "width" : "height", a_height = pvc.BasePanel.oppositeLength[a_width], sections = [], contentSize = {
                width: 0,
                height: 0
            }, sectionWidthFix = clientSizeFix[a_width], $sectionWidthMax = sectionWidthFix;
            (!$sectionWidthMax || 0 > $sectionWidthMax) && ($sectionWidthMax = clientSize[a_width]);
            this.childNodes.forEach(function(groupScene) {
                groupScene.childNodes.forEach(layoutItem, this);
            }, this);
            if (!section || "collapse" === this._overflow && isOverItemMax(this._itemCountMax)) return new pvc_Size(0, 0);
            commitSection(!0);
            def.set(this.vars, "sections", sections, "contentSize", contentSize, "labelWidthMax", labelWidthMax);
            var isV1Compat = this.compatVersion() <= 1, $w = isV1Compat ? $sectionWidthMax : sectionWidthFix, $h = clientSizeFix[a_height];
            (!$w || 0 > $w) && ($w = contentSize[a_width]);
            (!$h || 0 > $h) && ($h = contentSize[a_height]);
            return "collapse" === this._overflow && isOverflow() ? new pvc_Size(0, 0) : this.vars.size = def.set({}, a_width, Math.min($w, clientSize[a_width]), a_height, Math.min($h, clientSize[a_height]));
        },
        defaultGroupSceneType: function() {
            var GroupType = this._groupType;
            if (!GroupType) {
                GroupType = def.type(pvc.visual.legend.LegendGroupScene);
                this._groupType = GroupType;
            }
            return GroupType;
        },
        createGroup: function(keyArgs) {
            var GroupType = this.defaultGroupSceneType();
            return new GroupType(this, keyArgs);
        }
    });
    def.type("pvc.visual.legend.LegendGroupScene", pvc.visual.Scene).init(function(rootScene, keyArgs) {
        this.base(rootScene, keyArgs);
        this.legendBaseIndex = def.get(keyArgs, "legendBaseIndex") || 0;
        this.colorAxis = def.get(keyArgs, "colorAxis");
        this.clickMode = def.get(keyArgs, "clickMode");
        !this.clickMode && this.colorAxis && (this.clickMode = this.colorAxis.option("LegendClickMode"));
    }).add({
        _rendererCreate: function(legendPanel, pvSymbolPanel, wrapper) {
            function createDataPartSymbolPanel(dataPartValue) {
                var pvDPSymPanel = new pvc.visual.Panel(legendPanel, pvSymbolPanel).pvMark;
                dataPartDimName && pvDPSymPanel.visible(function(scene) {
                    if (scene.group._disposed) {
                        def.log.warn("[CCC] FIXME: Code running on disposed scene!");
                        return !1;
                    }
                    return !!scene.group.dimensions(dataPartDimName).atom(dataPartValue);
                });
                return pvDPSymPanel;
            }
            function defaultRenderer() {
                var pvDefaultSymPanel = new pvc.visual.Panel(legendPanel, pvSymbolPanel).pvMark, keyArgs = {
                    drawLine: colorAxis.option("LegendDrawLine"),
                    drawMarker: colorAxis.option("LegendDrawMarker"),
                    markerShape: colorAxis.option("LegendShape")
                }, renderer = pvc.visual.legend.symbolRenderer(keyArgs);
                renderer(legendPanel, pvDefaultSymPanel, wrapper, def.indexedId("", legendBaseIndex));
            }
            var colorAxis = this.colorAxis;
            if (colorAxis) {
                var legendBaseIndex = this.legendBaseIndex, anyRenderer = !1, dataPartDimName = colorAxis.chart._getDataPartDimName();
                colorAxis.dataCells.forEach(function(dc, index) {
                    var renderer = dc.legendSymbolRenderer();
                    if (renderer) {
                        anyRenderer = !0;
                        var pvDPSymPanel = createDataPartSymbolPanel(dc.plot.option("DataPart"));
                        renderer(legendPanel, pvDPSymPanel, wrapper, def.indexedId("", legendBaseIndex + index));
                    }
                });
                anyRenderer || defaultRenderer();
            }
        },
        hasRenderer: function() {
            return !!this._renderer;
        },
        renderer: function(_) {
            if (arguments.length) {
                _ && "object" == typeof _ && (_ = pvc.visual.legend.symbolRenderer(_));
                this._renderer = _;
                return this;
            }
            return this._renderer || (this._renderer = this._rendererCreate.bind(this));
        },
        itemSceneType: function() {
            var ItemType = this._itemSceneType;
            if (!ItemType) {
                ItemType = def.type(pvc.visual.legend.LegendItemScene);
                var clickMode = this.clickMode;
                switch (clickMode) {
                  case "toggleselected":
                    ItemType.add(pvc.visual.legend.LegendItemSceneSelection);
                    break;

                  case "togglevisible":
                    ItemType.add(pvc.visual.legend.LegendItemSceneVisibility);
                }
                var legendPanel = this.panel();
                legendPanel._extendSceneType("item", ItemType, [ "isOn", "executable", "execute", "value", "labelText" ]);
                var itemSceneExtIds = pvc.makeExtensionAbsId(pvc.makeExtensionAbsId("ItemScene", [ this.extensionPrefix, "$" ]), legendPanel._getExtensionPrefix()), impl = legendPanel.chart._getExtension(itemSceneExtIds, "value");
                void 0 !== impl && ItemType.variable("value", impl);
                this._itemSceneType = ItemType;
            }
            return ItemType;
        },
        createItem: function(keyArgs) {
            var ItemType = this.itemSceneType();
            return new ItemType(this, keyArgs);
        }
    });
    def.type("pvc.visual.legend.LegendItemSceneSection").init(function(index) {
        this.index = index;
        this.items = [];
        this.size = {
            width: 0,
            height: 0
        };
    });
    def.type("pvc.visual.legend.LegendItemScene", pvc.visual.Scene).add({
        _ibits: null,
        ibits: function() {
            var ibits = this._ibits;
            if (null == ibits) {
                if (this.executable()) ibits = -1; else {
                    var I = pvc.visual.Interactive;
                    ibits = I.Interactive | I.ShowsInteraction | I.ShowsTooltip | I.Hoverable | I.SelectableAny;
                }
                this._ibits = ibits;
            }
            return ibits;
        },
        isOn: def.fun.constant(!0),
        executable: def.fun.constant(!1),
        execute: def.fun.constant(),
        labelText: function() {
            return this.value().label;
        },
        labelTextSize: function() {
            return pv.Text.measure(this.labelText(), this.vars.font);
        },
        _valueEval: function() {
            var valueVar = this._valueEvalCore();
            valueVar instanceof pvc_ValueLabelVar || (valueVar = new pvc_ValueLabelVar(valueVar, valueVar));
            return valueVar;
        },
        _valueEvalCore: function() {
            var value, rawValue, label, absLabel, trendSuffix, source = this.group || this.datum;
            if (source) {
                value = source.value;
                rawValue = source.rawValue;
                trendSuffix = this._getTrendLineSuffix(source);
                label = source.ensureLabel() + trendSuffix;
                absLabel = source.absLabel ? source.absLabel + trendSuffix : label;
            }
            return new pvc_ValueLabelVar(value || null, label || "", rawValue, absLabel);
        },
        _getTrendLineSuffix: function(source) {
            var datum, trendOptions;
            return (datum = source.firstDatum()) && (trendOptions = datum.trend) ? " (" + trendOptions.label + ")" : "";
        }
    }).variable("value");
    def.type("pvc.visual.legend.LegendItemSceneSelection").add({
        isOn: function() {
            var source = this.group || this.datum;
            return !source.owner.selectedCount() || this.isSelected();
        },
        executable: function() {
            return this.chart().selectableByClick();
        },
        execute: function() {
            var datums = this.datums().array();
            if (datums.length) {
                var chart = this.chart();
                chart._updatingSelections(function() {
                    datums = chart._onUserSelection(datums);
                    datums && datums.length && cdo.Data.toggleSelected(datums, !0);
                });
            }
        }
    });
    def.type("pvc.visual.legend.LegendItemSceneVisibility").add({
        isOn: function() {
            var isOn = this._isOn;
            null == isOn && (isOn = this._isOn = this.datums().any(function(datum) {
                return !datum.isNull && datum.isVisible;
            }));
            return isOn;
        },
        executable: function() {
            return def.lazy(this, "_execble", this._calcExecutable, this);
        },
        _calcExecutable: function() {
            if (this._executableApriori()) for (var group, groups = this.root.childNodes, g = -1, G = groups.length; ++g < G; ) if ("togglevisible" === (group = groups[g]).clickMode) for (var item, items = group.childNodes, i = -1, I = items.length; ++i < I; ) if ((item = items[i]) !== this && item._executableApriori() && item.isOn()) return !0;
            return !1;
        },
        _executableApriori: function() {
            return def.lazy(this, "_execApriori", this._calcExecutableApriori, this);
        },
        _calcExecutableApriori: function() {
            return this.datums().any(function(d) {
                return !d.isTrend;
            });
        },
        execute: function() {
            if (cdo.Data.toggleVisible(this.datums())) {
                this.clearCachedState();
                this.chart().render(!0, !0, !1);
            }
        },
        clearCachedState: function() {
            delete this._execble;
            delete this._isOn;
            delete this._execApriori;
        }
    });
    def.space("pvc.visual.legend").symbolRenderer = function(config) {
        function createExtIds(id, legacyExtPrefixes) {
            var legacyExtIds = pvc.makeExtensionAbsId(id, legacyExtPrefixes);
            return extPrefix ? legacyExtIds.concat(pvc.makeExtensionAbsId(id, extPrefix)) : legacyExtIds;
        }
        function legendSymbolRenderer(legendPanel, pvSymbolPanel, wrapper, legacyLegendExtensionPrefix) {
            var extag = pvc.extensionTag, sceneColorProp = function(scene) {
                return scene.color;
            }, legacyExtPrefixes = [ "$", legacyLegendExtensionPrefix ];
            if (drawLine) {
                var rulePvBaseProto = new pv_Mark().left(0).top(function() {
                    return this.parent.height() / 2;
                }).width(function() {
                    return this.parent.width();
                }).lineWidth(1, extag).strokeStyle(sceneColorProp, extag).cursor(function(itemScene) {
                    return itemScene.executable() ? "pointer" : "default";
                });
                rulePvProto && (rulePvBaseProto = rulePvProto.extend(rulePvBaseProto));
                new pvc.visual.Rule(legendPanel, pvSymbolPanel, {
                    proto: rulePvBaseProto,
                    noSelect: !1,
                    noHover: !1,
                    activeSeriesAware: !1,
                    extensionId: createExtIds("Rule", legacyExtPrefixes),
                    showsInteraction: !0,
                    wrapper: wrapper
                });
            }
            if (drawMarker) {
                var markerPvBaseProto = new pv_Mark().left(function() {
                    return this.parent.width() / 2;
                }).top(function() {
                    return this.parent.height() / 2;
                }).shapeSize(function() {
                    return this.parent.width();
                }, extag).lineWidth(2, extag).fillStyle(sceneColorProp, extag).strokeStyle(sceneColorProp, extag).shape(markerShape, extag).angle(drawLine ? 0 : Math.PI / 2, extag).antialias(function() {
                    var cos = Math.abs(Math.cos(this.angle()));
                    if (0 !== cos && 1 !== cos) switch (this.shape()) {
                      case "square":
                      case "bar":
                        return !1;
                    }
                    return !0;
                }, extag).cursor(function(itemScene) {
                    return itemScene.executable() ? "pointer" : "default";
                });
                markerPvProto && (markerPvBaseProto = markerPvProto.extend(markerPvBaseProto));
                new pvc.visual.Dot(legendPanel, pvSymbolPanel, {
                    proto: markerPvBaseProto,
                    freePosition: !0,
                    activeSeriesAware: !1,
                    noTooltip: !0,
                    noClick: !0,
                    extensionId: createExtIds("Dot", legacyExtPrefixes),
                    wrapper: wrapper
                }).pvMark.pointingRadiusMax(0);
            }
        }
        var markerShape, markerPvProto, drawLine = def.get(config, "drawLine", !1), drawMarker = !drawLine || def.get(config, "drawMarker", !0), rulePvProto = drawLine ? def.get(config, "rulePvProto") : null, extPrefix = def.array.to(def.get(config, "extensionPrefix"));
        if (drawMarker) {
            markerShape = def.get(config, "markerShape", "square");
            markerPvProto = def.get(config, "markerPvProto");
        }
        return legendSymbolRenderer;
    };
    def.type("pvc.TitlePanelAbstract", pvc.BasePanel).init(function(chart, parent, options) {
        options || (options = {});
        var anchor = options.anchor || this.anchor;
        if (null == options.size) {
            var size = options.titleSize;
            null != size && (options.size = new pvc_Size().setSize(size, {
                singleProp: this.anchorOrthoLength(anchor)
            }));
        }
        if (null == options.sizeMax) {
            var sizeMax = options.titleSizeMax;
            null != sizeMax && (options.sizeMax = new pvc_Size().setSize(sizeMax, {
                singleProp: this.anchorOrthoLength(anchor)
            }));
        }
        null == options.paddings && (options.paddings = this.defaultPaddings);
        this.base(chart, parent, options);
        if (void 0 === options.font) {
            var extensionFont = this._getExtension("label", "font");
            "string" == typeof extensionFont && (this.font = extensionFont);
        }
    }).add({
        pvLabel: null,
        anchor: "top",
        title: null,
        titleSize: void 0,
        font: "12px sans-serif",
        defaultPaddings: 2,
        _extensionPrefix: "title",
        _calcLayout: function(layoutInfo) {
            var clientSizeWant = new pvc_Size(), a = this.anchor, a_width = this.anchorLength(a), a_height = this.anchorOrthoLength(a), title = this.title || "", textWidth = pv.Text.measureWidth(title, this.font) + 2, clientWidthAvailable = layoutInfo.clientSize[a_width], clientWidthFix = layoutInfo.restrictions.clientSize[a_width];
            null == clientWidthFix ? clientWidthFix = textWidth > clientWidthAvailable ? clientWidthAvailable : textWidth : clientWidthFix > clientWidthAvailable && (clientWidthFix = clientWidthAvailable);
            var lines = title ? textWidth > clientWidthFix ? pvc.text.justify(title, clientWidthFix, this.font) : [ title ] : [], lineHeight = pv.Text.fontHeight(this.font), realHeight = lines.length * lineHeight, clientHeightAvailable = layoutInfo.clientSize[a_height], clientHeightFix = layoutInfo.restrictions.clientSize[a_height];
            null == clientHeightFix ? clientHeightFix = realHeight : clientHeightFix > clientHeightAvailable && (clientHeightFix = clientHeightAvailable);
            if (realHeight > clientHeightFix) {
                var lineCountMax = Math.max(1, Math.floor(clientHeightFix / lineHeight));
                if (lines.length > lineCountMax) {
                    var firstCroppedLine = lines[lineCountMax];
                    lines.length = lineCountMax;
                    realHeight = clientHeightFix = lineCountMax * lineHeight;
                    var lastLine = lines[lineCountMax - 1] + " " + firstCroppedLine;
                    lines[lineCountMax - 1] = pvc.text.trimToWidthB(clientWidthFix, lastLine, this.font, "..");
                }
            }
            layoutInfo.lines = lines;
            layoutInfo.topOffset = (clientHeightFix - realHeight) / 2;
            layoutInfo.lineSize = {
                width: clientWidthFix,
                height: lineHeight
            };
            layoutInfo.a_width = a_width;
            layoutInfo.a_height = a_height;
            clientSizeWant[a_width] = clientWidthFix;
            clientSizeWant[a_height] = clientHeightFix;
            return clientSizeWant;
        },
        _createCore: function(layoutInfo) {
            var wrapper, rootScene = this._buildScene(layoutInfo), rotationByAnchor = {
                top: 0,
                right: Math.PI / 2,
                bottom: 0,
                left: -Math.PI / 2
            }, textAlign = pvc.BasePanel.horizontalAlign[this.align], textAnchor = pvc.BasePanel.leftTopAnchor[this.anchor];
            this.compatVersion() <= 1 && (wrapper = function(v1f) {
                return function(itemScene) {
                    return v1f.call(this);
                };
            });
            this.pvLabel = new pvc.visual.Label(this, this.pvPanel, {
                extensionId: "label",
                wrapper: wrapper
            }).lock("data", rootScene.lineScenes).pvMark[textAnchor](function(lineScene) {
                return layoutInfo.topOffset + lineScene.vars.size.height / 2 + this.index * lineScene.vars.size.height;
            }).textAlign(textAlign)[this.anchorOrtho(textAnchor)](function(lineScene) {
                switch (this.textAlign()) {
                  case "center":
                    return lineScene.vars.size.width / 2;

                  case "left":
                    return 0;

                  case "right":
                    return lineScene.vars.size.width;
                }
            }).text(function(lineScene) {
                return lineScene.vars.textLines[this.index];
            }).font(this.font).textBaseline("middle").textAngle(rotationByAnchor[this.anchor]);
        },
        _buildScene: function(layoutInfo) {
            var rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: this.chart.data
            }), textLines = layoutInfo.lines;
            rootScene.vars.size = layoutInfo.lineSize;
            rootScene.vars.textLines = textLines;
            rootScene.lineScenes = def.array.create(textLines.length, rootScene);
            return rootScene;
        },
        _getExtensionId: def.fun.constant("")
    });
    def.type("pvc.TitlePanel", pvc.TitlePanelAbstract).init(function(chart, parent, options) {
        options || (options = {});
        var isV1Compat = chart.compatVersion() <= 1;
        if (isV1Compat) {
            var size = options.titleSize;
            null == size && (options.titleSize = 25);
        }
        this._extensionPrefix = chart.parent ? "smallTitle" : "title";
        this.base(chart, parent, options);
    }).add({
        font: "14px sans-serif",
        defaultPaddings: 4
    });
    def.type("pvc.GridDockingPanel", pvc.ContentPanel).add({
        _getFillSizeMin: function() {
            return null;
        },
        _calcLayout: function(_layoutInfo) {
            function doLayout() {
                var layoutChange = 0;
                try {
                    _me._children.forEach(phase0_initChild);
                } finally {}
                _useLog && _me.log.group("Phase 1 - Determine MARGINS and FILL SIZE from SIDE panels");
                try {
                    layoutChange = phase1();
                } finally {
                    if (_useLog) {
                        _me.log("Final FILL margins = " + def.describe(_margins));
                        _me.log("Final FILL border size = " + def.describe(_fillSize));
                        _me.log.groupEnd();
                    }
                }
                layoutChange |= checkFillSizeMin(_canChangeInitial);
                if (!layoutChange) {
                    _useLog && _me.log.group("Phase 2 - Determine COMMON PADDINGS");
                    try {
                        layoutChange = phase2();
                    } finally {
                        if (_useLog) {
                            _me.log("Final COMMON paddings  = " + def.describe(_contentOverflow));
                            _me.log("Final FILL client size = " + def.describe({
                                width: (_fillSize.width || 0) - (_contentOverflow.width || 0),
                                height: (_fillSize.height || 0) - (_contentOverflow.height || 0)
                            }));
                            _me.log.groupEnd();
                        }
                    }
                }
                _canChangeInitial && !layoutChange && _contentOverflowOptional && (_layoutInfo.contentOverflow = _contentOverflowOptional);
            }
            function phase0_initChild(child) {
                var a = child.anchor;
                a && ("fill" === a ? _fillChildren.push(child) : _sideChildren.push(child));
            }
            function phase1() {
                var layoutChange = 0, sideChildrenSizes = new Array(_sideChildren.length), MAX_TIMES = 5, i = 0;
                do {
                    i++;
                    _useLog && _me.log.group("Iteration #" + i + " / " + MAX_TIMES);
                    try {
                        layoutChange = phase1_iteration(sideChildrenSizes, i === MAX_TIMES);
                        if (layoutChange && 0 !== (layoutChange & OwnClientSizeChanged)) {
                            _useLog && _me.log("Restarting due to clientSize increase.");
                            return layoutChange;
                        }
                    } finally {
                        _useLog && _me.log.groupEnd();
                    }
                } while (MAX_TIMES > i && 0 !== (layoutChange & MarginsChanged));
                for (var i = -1, count = _sideChildren.length, margins = new pvc_Sides(0); ++i < count; ) {
                    var child = _sideChildren[i];
                    if (child.isVisible) {
                        var anchor = child.anchor, pos = margins[anchor] || 0;
                        child.position[anchor] = pos;
                        positionSide(child, child.align);
                        margins[anchor] = pos + sideChildrenSizes[i];
                    } else {
                        _sideChildren.splice(i, 1);
                        sideChildrenSizes.splice(i, 1);
                        count--;
                        i--;
                    }
                }
                return 0;
            }
            function phase1_iteration(sideChildrenSizes, isLastIteration) {
                var layoutChange = 0, canChangeChild = _canChangeInitial && !isLastIteration;
                _contentOverflowOptional = null;
                _contentOverflow = new pvc_Sides(0);
                _hasContentOverflow = !1;
                for (var i = -1, L = _sideChildren.length; ++i < L; ) {
                    var child = _sideChildren[i];
                    _useLog && child.log.group("Layout");
                    try {
                        var anchor = child.anchor, p_alen = _alMap[anchor], p_aolen = _aolMap[anchor], olenPrev = sideChildrenSizes[i];
                        if (null != olenPrev) {
                            _margins[anchor] -= olenPrev;
                            _fillSize[p_aolen] += olenPrev;
                        }
                        var sizeRef = {
                            width: null,
                            height: null
                        };
                        sizeRef[p_alen] = _fillSize[p_alen];
                        sizeRef[p_aolen] = _layoutInfo.clientSize[p_aolen];
                        _childLayoutKeyArgs.sizeAvailable = new pvc_Size(_fillSize);
                        _childLayoutKeyArgs.sizeRef = sizeRef;
                        _childLayoutKeyArgs.canChange = canChangeChild;
                        child.layout(_childLayoutKeyArgs);
                        var olen;
                        if (child.isVisible) {
                            if (checkChildSizeIncreased(child, canChangeChild)) return OwnClientSizeChanged;
                            olen = null == olenPrev || Math.abs(child[p_aolen] - olenPrev) > pvc.roundPixel.epsilon ? child[p_aolen] : olenPrev;
                        } else olen = 0;
                        (null == olenPrev || olen != olenPrev) && (layoutChange |= MarginsChanged);
                        sideChildrenSizes[i] = olen;
                        if (olen) {
                            _margins[anchor] += olen;
                            _fillSize[p_aolen] -= olen;
                        }
                        child.isVisible && checkChildOptionalContentOverflowChanged(child, canChangeChild);
                    } finally {
                        _useLog && child.log.groupEnd();
                    }
                }
                return layoutChange;
            }
            function phase2() {
                var layoutChange = 0, MAX_TIMES = 9, i = 0;
                do {
                    i++;
                    _useLog && _me.log.group("Iteration #" + i + " / " + MAX_TIMES);
                    try {
                        layoutChange = phase2_iteration(1 === i, i === MAX_TIMES);
                        if (layoutChange && 0 !== (layoutChange & OwnClientSizeChanged)) {
                            _useLog && _me.log("Restarting due to clientSize increase.");
                            return layoutChange;
                        }
                    } finally {
                        _useLog && _me.log.groupEnd();
                    }
                } while (MAX_TIMES > i && 0 !== (layoutChange & ContentOverflowChanged));
                return 0;
            }
            function phase2_iteration(isFirstIteration, isLastIteration) {
                var layoutChange, canChangeChild = _canChangeInitial && !isLastIteration, sideCount = _sideChildren.length, children = _sideChildren.concat(_fillChildren), i = (isFirstIteration && !_hasContentOverflow ? sideCount : 0) - 1;
                i = -1;
                var L = children.length;
                0 > i && (_contentOverflowOptional = null);
                for (;++i < L; ) {
                    var child = children[i], isFill = i >= sideCount;
                    _useLog && child.log.group("Layout");
                    try {
                        phase2_layoutChild(child, canChangeChild, isFill);
                        if (child.isVisible) {
                            if (checkChildSizeIncreased(child, canChangeChild)) return OwnClientSizeChanged;
                            if (layoutChange = checkChildContentOverflowChanged(child, canChangeChild)) return ContentOverflowChanged;
                            isFill || checkChildOptionalContentOverflowChanged(child, canChangeChild);
                            if (isFill) {
                                positionSide(child, "center");
                                positionSide(child, "middle");
                            } else positionSide(child, child.align);
                        }
                    } finally {
                        _useLog && child.log.groupEnd();
                    }
                }
                return 0;
            }
            function phase2_layoutChild(child, canChangeChild, isFill) {
                var sizeAvail, sizeFix, sizeRef, pads;
                if (isFill) {
                    sizeAvail = pvc_Size.clone(_fillSize);
                    sizeRef = _fillSize;
                    pads = def.copyOwn(_contentOverflow);
                } else {
                    var anchor = child.anchor, al = _alMap[anchor], aol = _aolMap[anchor];
                    sizeFix = {
                        width: null,
                        height: null
                    };
                    sizeFix[al] = _fillSize[al];
                    sizeFix[aol] = child[aol];
                    sizeRef = {
                        width: null,
                        height: null
                    };
                    sizeRef[al] = _fillSize[al];
                    sizeRef[aol] = _layoutInfo.clientSize[aol];
                    pads = def.copyOwn(pvc_Sides.filterAnchor(anchor, _contentOverflow));
                }
                _childLayoutKeyArgs.sizeAvailable = sizeAvail;
                _childLayoutKeyArgs.size = sizeFix;
                _childLayoutKeyArgs.sizeRef = sizeRef;
                _childLayoutKeyArgs.paddings = pads;
                _childLayoutKeyArgs.canChange = canChangeChild;
                child.layout(_childLayoutKeyArgs);
            }
            function positionSide(child, anchorOrAlign) {
                var pos, side;
                switch (anchorOrAlign) {
                  case "top":
                  case "bottom":
                  case "left":
                  case "right":
                    side = anchorOrAlign;
                    pos = _margins[side];
                    break;

                  case "fill":
                  case "middle":
                    side = "bottom";
                    pos = _margins.bottom + _fillSize.height / 2 - child.height / 2;
                    break;

                  case "center":
                    side = "left";
                    pos = _margins.left + _fillSize.width / 2 - child.width / 2;
                }
                child.position[side] = pos;
            }
            function checkChildSizeIncreased(child, canChangeChild) {
                function checkDimension(a_len) {
                    var addLen = sizeIncrease[a_len];
                    if (addLen > pvc.roundPixel.epsilon) if (canChangeChild) {
                        layoutChange |= OwnClientSizeChanged;
                        _layoutInfo.clientSize[a_len] += addLen;
                        ownClientSizeIncrease[a_len] += addLen;
                    } else _useLog && child.log.warn("Child wanted more " + a_len + ", but layout iterations limit has been reached.");
                }
                var layoutChange = 0, sizeIncrease = child.getLayout().sizeIncrease;
                sizeIncrease && ("fill" === child.anchor ? pvc_Size.names.forEach(checkDimension) : checkDimension(child.anchorLength()));
                return layoutChange;
            }
            function checkFillSizeMin(canChange) {
                function checkDimension(a_len) {
                    var addLen = _fillSizeMin[a_len] - _fillSize[a_len];
                    addLen -= ownClientSizeIncrease[a_len];
                    if (addLen > pvc.roundPixel.epsilon) if (canChange) {
                        layoutChange |= OwnClientSizeChanged;
                        _layoutInfo.clientSize[a_len] += addLen;
                        _useLog && _me.log.warn("Increasing client " + a_len + " by " + addLen + " to satisfy minimum fill " + a_len + ".");
                    } else _useLog && _me.log.warn("Wanted more fill " + a_len + ", " + addLen + ", but layout iterations limit has been reached.");
                }
                var layoutChange = 0;
                if (_fillSizeMin) {
                    checkDimension("width");
                    checkDimension("height");
                }
                return layoutChange;
            }
            function checkChildContentOverflowChanged(child, canChangeChild) {
                var layoutChange = 0, contentOverflow = child._layoutInfo.contentOverflow;
                if (contentOverflow) {
                    _useLog && child.log("ContentOverflow=" + def.describe(contentOverflow));
                    pvc_Sides.getAnchorSides(child.anchor).forEach(function(side) {
                        var value = _contentOverflow[side] || 0, valueNew = contentOverflow[side] || 0;
                        if (valueNew - value > pvc.roundPixel.epsilon) if (canChangeChild) {
                            layoutChange |= ContentOverflowChanged;
                            _contentOverflow[side] = valueNew;
                            _useLog && child.log("Changed content overflow: " + side + " <- " + valueNew);
                        } else _useLog && child.log.warn("CANNOT change but child wanted to: " + side + "=" + valueNew);
                    });
                    layoutChange && pvc_Sides.updateSize(_contentOverflow);
                }
                return layoutChange;
            }
            function checkChildOptionalContentOverflowChanged(child, canChangeChild) {
                var contentOverflowOptional, layoutChange = 0;
                if (_me.chart._preserveLayout || !(contentOverflowOptional = child._layoutInfo.contentOverflowOptional)) return layoutChange;
                _useLog && child.log("<= contentOverflowOptional=" + def.describe(contentOverflowOptional));
                var ownPaddings = _layoutInfo.paddings, a_len = child.anchorLength();
                pvc_Sides.getAnchorSides(child.anchor).forEach(function(side) {
                    if (contentOverflowOptional.hasOwnProperty(side)) {
                        var value = ownPaddings[side] || 0, childOverflow = contentOverflowOptional[side] || 0, childLen = child._layoutInfo.size[a_len], fillOverflow = Math.max(0, childLen + childOverflow - _fillSize[a_len]), valueNew = Math.max(0, fillOverflow - _margins[side]);
                        if (valueNew - value > pvc.roundPixel.epsilon) if (canChangeChild) {
                            layoutChange |= OptionalContentOverflowChanged;
                            _contentOverflowOptional || (_contentOverflowOptional = new pvc_Sides(ownPaddings));
                            _contentOverflowOptional[side] = Math.max(_contentOverflowOptional[side] || 0, valueNew);
                            _useLog && child.log("Changed optional content overflow " + side + " <- " + valueNew);
                        } else def.debug >= 2 && child.log.warn("CANNOT change optional content overflow but child wanted to: " + side + "=" + valueNew);
                    }
                });
                return layoutChange;
            }
            var _me = this;
            if (_me._children) {
                var _contentOverflowOptional, _useLog = def.debug >= 10, _canChangeInitial = _layoutInfo.restrictions.canChange !== !1, _aolMap = pvc.BasePanel.orthogonalLength, _alMap = pvc.BasePanel.parallelLength, ContentOverflowChanged = 2, OptionalContentOverflowChanged = 4, OwnClientSizeChanged = 8, MarginsChanged = 16, _childLayoutKeyArgs = {
                    force: !0
                }, _margins = new pvc_Sides(0), _fillSize = def.copyOwn(_layoutInfo.clientSize), _fillSizeMin = this._getFillSizeMin();
                _fillSizeMin && (_fillSizeMin = _fillSizeMin.resolve(null));
                var ownClientSizeIncrease = {
                    width: 0,
                    height: 0
                }, _contentOverflow = new pvc_Sides(0), _hasContentOverflow = !1, _fillChildren = [], _sideChildren = [];
                _useLog && _me.log.group("CCC GRID LAYOUT");
                try {
                    doLayout();
                } finally {
                    _useLog && _me.log.groupEnd();
                }
                _layoutInfo.gridMargins = pvc_Sides.updateSize(_margins);
                _layoutInfo.gridPaddings = pvc_Sides.updateSize(_contentOverflow);
                _layoutInfo.gridSize = new pvc_Size(_fillSize);
                return _layoutInfo.clientSize;
            }
        }
    });
    def.type("pvc.BaseChart", pvc.Abstract).add(pvc.visual.Interactive).add(def.EventSource).init(function(options) {
        var originalOptions = options, parent = this.parent = def.get(options, "parent") || null;
        if (parent) options || def.fail.argumentRequired("options"); else {
            pvc_initChartClassDefaults && pvc_initChartClassDefaults();
            options = def.mixin.copy({}, this.defaults, options);
        }
        this.options = options;
        if (parent) {
            this.root = parent.root;
            this.smallColIndex = options.smallColIndex;
            this.smallRowIndex = options.smallRowIndex;
        } else {
            this.root = this;
            this._format = cdo.format.language().createChild();
        }
        this.base();
        def.debug >= 3 && this.log.info("NEW CHART\n" + def.logSeparator.replace(/-/g, "=") + "\n  DebugLevel: " + def.debug);
        if (def.debug >= 3 && !parent && originalOptions) {
            this.log.info("OPTIONS:\n", originalOptions);
            def.debug >= 5 && this.log.debug(def.describe(originalOptions, {
                ownOnly: !1,
                funs: !0
            }));
        }
        parent && parent._addChild(this);
        this._constructData(options);
        this._constructVisualRoles(options);
    }).add({
        _disposed: !1,
        parent: null,
        children: null,
        root: null,
        isCreated: !1,
        _createVersion: 0,
        renderCallback: void 0,
        multiChartPageCount: null,
        multiChartPageIndex: null,
        _multiChartOverflowClipped: !1,
        left: 0,
        top: 0,
        width: null,
        height: null,
        margins: null,
        paddings: null,
        _allowV1SecondAxis: !1,
        _preserveLayout: !1,
        compatVersion: function(options) {
            return (options || this.options).compatVersion;
        },
        getCompatFlag: function(flagName) {
            return this.options.compatFlags[flagName];
        },
        _createLogId: function() {
            return "" + def.qualNameOf(this.constructor) + this._createLogChildSuffix();
        },
        _createLogChildSuffix: function() {
            return this.parent ? " (" + (this.smallRowIndex + 1) + "," + (this.smallColIndex + 1) + ")" : "";
        },
        _addChild: function(childChart) {
            childChart.parent === this || def.assert("Not a child of this chart.");
            this.children.push(childChart);
        },
        _savePlotsLayout: function() {
            if (this.options.preserveLayout && this.plotPanelList && this.plotPanelList.length) {
                this._preservedPlotsLayoutInfo = {};
                this.plotPanelList.forEach(function(plotPanel) {
                    this._preservedPlotsLayoutInfo[plotPanel.plot.id] = plotPanel._getLayoutState();
                }, this);
                this._preserveLayout = !0;
            }
        },
        _create: function(keyArgs) {
            this._createPhase1(keyArgs);
            this._createPhase2();
        },
        _createPhase1: function(keyArgs) {
            this._createVersion++;
            this.isCreated = !1;
            def.debug >= 3 && this.log("Creating");
            var hasMultiRole, isRoot = !this.parent, isMultiChartOverflowRetry = this._isMultiChartOverflowClipRetry, isRootInit = isRoot && !isMultiChartOverflowRetry && !this.data;
            this._savePlotsLayout();
            isRoot && (this.children = []);
            this.plotPanels = {};
            this.plotPanelList = [];
            isRoot && isMultiChartOverflowRetry || this._processOptions();
            if (isRootInit) {
                this._processDataOptions(this.options);
                this._checkNoDataI();
                this._initChartVisualRoles();
            }
            (isRootInit || !isRoot) && this._initPlots();
            if (!isMultiChartOverflowRetry) {
                this._initData(keyArgs);
                isRoot && this._checkNoDataII();
            }
            hasMultiRole = this.visualRoles.multiChart.isBound();
            isMultiChartOverflowRetry || this._initAxesEnd();
            if (isRoot) {
                hasMultiRole && this._initMultiCharts();
                this._interpolate(hasMultiRole);
                this._generateTrends(hasMultiRole);
            }
            this._setAxesScales(this._chartLevel());
        },
        _createPhase2: function() {
            var hasMultiRole = this.visualRoles.multiChart.isBound();
            this._initChartPanels(hasMultiRole);
            this.isCreated = !0;
        },
        _setSmallLayout: function(keyArgs) {
            function setProp(p) {
                var v = keyArgs[p];
                return null != v ? (me[p] = v, !0) : void 0;
            }
            if (keyArgs) {
                var me = this, basePanel = me.basePanel;
                setProp("left") | setProp("top") && basePanel && def.set(basePanel.position, "left", this.left, "top", this.top);
                setProp("width") | setProp("height") && basePanel && (basePanel.size = new pvc_Size(this.width, this.height));
                setProp("margins") && basePanel && (basePanel.margins = new pvc_Sides(this.margins));
                setProp("paddings") && basePanel && (basePanel.paddings = new pvc_Sides(this.paddings));
            }
        },
        _processOptions: function() {
            var plotSpecs, options = this.options;
            if (!this.parent) {
                this.width = options.width;
                this.height = options.height;
                this.margins = options.margins;
                this.paddings = options.paddings;
            }
            this.compatVersion() <= 1 ? options.plot2 = this._allowV1SecondAxis && !!options.secondAxis : !options.plot2 && (plotSpecs = options.plots) && (options.plot2 = def.array.is(plotSpecs) ? def.query(plotSpecs).where(function(plotSpec) {
                return "plot2" === plotSpec.name;
            }).any() : !!plotSpecs.plot2);
            this._processFormatOptions(options);
            this._processOptionsCore(options);
            this._processExtensionPoints();
            return options;
        },
        _processOptionsCore: function(options) {
            var parent = this.parent;
            if (parent) {
                ibits = parent.ibits();
                this._tooltipOptions = parent._tooltipOptions;
                this._pointingOptions = parent._pointingOptions;
            } else {
                var interactive = "batik" !== pv.renderer();
                interactive && null == (interactive = options.interactive) && (interactive = !0);
                var ibits = 0;
                if (interactive) {
                    var I = pvc.visual.Interactive;
                    ibits = I.Interactive | I.ShowsInteraction;
                    this._processTooltipOptions(options) && (ibits |= I.ShowsTooltip);
                    options.animate && $.support.svg && (ibits |= I.Animatable);
                    var preventUnselect = !1;
                    if (options.selectable) {
                        ibits |= I.Selectable;
                        switch (pvc.parseSelectionMode(options.selectionMode)) {
                          case "rubberband":
                            ibits |= I.SelectableByRubberband | I.SelectableByClick;
                            break;

                          case "focuswindow":
                            ibits |= I.SelectableByFocusWindow;
                            preventUnselect = !0;
                        }
                    }
                    preventUnselect || "emptyspaceclick" !== pvc.parseClearSelectionMode(options.clearSelectionMode) || (ibits |= I.Unselectable);
                    options.hoverable && (ibits |= I.Hoverable);
                    options.clickable && (ibits |= I.Clickable | I.DoubleClickable);
                    this._processPointingOptions(options);
                    var evs;
                    (evs = options.on) && this.on(evs);
                    (evs = options.before) && this.before(evs);
                    (evs = options.after) && this.after(evs);
                }
            }
            this._ibits = ibits;
        },
        _pointingDefaults: {
            radius: 10,
            radiusHyst: 4,
            stealClick: !0,
            collapse: "none"
        },
        _tooltipDefaults: {
            gravity: "s",
            animate: void 0,
            delayIn: 200,
            delayOut: 80,
            offset: 2,
            opacity: .9,
            html: !0,
            fade: !0,
            useCorners: !1,
            arrowVisible: !0,
            followMouse: !1,
            format: void 0,
            className: ""
        },
        _processTooltipOptions: function(options) {
            var isV1Compat = this.compatVersion() <= 1, tipOptions = options.tooltip, tipEnabled = options.tooltipEnabled;
            if (null == tipEnabled) {
                tipOptions && (tipEnabled = tipOptions.enabled);
                if (null == tipEnabled) {
                    isV1Compat && (tipEnabled = options.showTooltips);
                    null == tipEnabled && (tipEnabled = !0);
                }
            }
            if (tipEnabled) {
                tipOptions = tipOptions ? def.copy(tipOptions) : {};
                isV1Compat && this._importV1TooltipOptions(tipOptions, options);
                def.eachOwn(this._tooltipDefaults, function(dv, p) {
                    var value = options["tooltip" + def.firstUpperCase(p)];
                    void 0 !== value ? tipOptions[p] = value : void 0 === tipOptions[p] && (tipOptions[p] = dv);
                });
            }
            this._tooltipOptions = tipOptions || {};
            return tipEnabled;
        },
        _importV1TooltipOptions: function(tipOptions, options) {
            var v1TipOptions = options.tipsySettings;
            if (v1TipOptions) {
                this.extend(v1TipOptions, "tooltip");
                for (var p in v1TipOptions) void 0 === tipOptions[p] && (tipOptions[p] = v1TipOptions[p]);
                null == tipOptions.html && (tipOptions.html = !1);
            }
        },
        _processPointingOptions: function(options) {
            var pointingOptions = options.pointing, pointingMode = options.pointingMode;
            if (null == pointingMode) {
                pointingOptions && (pointingMode = pointingOptions.mode);
                pointingMode || (pointingMode = "near");
            }
            pointingOptions = pointingOptions ? def.copyOwn(pointingOptions) : {};
            pointingOptions.mode = pvc.parsePointingMode(pointingMode);
            def.eachOwn(this._pointingDefaults, function(dv, p) {
                void 0 === pointingOptions[p] && (pointingOptions[p] = dv);
            });
            pointingOptions.collapse = pvc.parsePointingCollapse(pointingOptions.collapse);
            pointingOptions.painted = !0;
            this._pointingOptions = pointingOptions || {};
        },
        _processFormatOptions: function(options) {
            if (!this.parent) {
                var format = options.format;
                void 0 != format && this.format(format);
                var fp = this._format;
                this._processFormatOption(options, fp, "number", "valueFormat");
                this._processFormatOption(options, fp, "percent", "percentValueFormat");
            }
        },
        _processFormatOption: function(options, formatProvider, formatName, optionName) {
            var format = formatProvider[formatName]();
            if (format !== cdo.format.defaults[formatName]()) options[optionName] = format; else {
                var optionFormat = options[optionName];
                if (optionFormat && optionFormat !== format) {
                    if (!optionFormat._nullWrapped) {
                        options[optionName] = optionFormat = pv.Format.createFormatter(optionFormat);
                        optionFormat._nullWrapped = 1;
                    }
                    formatProvider[formatName](optionFormat);
                }
            }
        },
        _processDataOptions: function(options) {
            function processDataOption(globalName, localName, dv) {
                var v = options[globalName];
                void 0 !== v ? dataOptions[localName] = null == v || "" === v ? dv : v : void 0 != dv && void 0 === dataOptions[localName] && (dataOptions[localName] = dv);
            }
            var dataOptions = options.dataOptions || (options.dataOptions = {});
            processDataOption("dataSeparator", "separator", "~");
            processDataOption("dataMeasuresInColumns", "measuresInColumns");
            processDataOption("dataCategoriesCount", "categoriesCount");
            processDataOption("dataIgnoreMetadataLabels", "ignoreMetadataLabels");
            processDataOption("dataWhere", "where");
            processDataOption("dataTypeCheckingMode", "typeCheckingMode");
            var plot2Series, plot2SeriesIndexes, plot2 = options.plot2;
            if (plot2) {
                if (this._allowV1SecondAxis && this.compatVersion() <= 1) plot2SeriesIndexes = options.secondAxisIdx; else {
                    plot2Series = options.plot2Series ? def.array.as(options.plot2Series) : null;
                    if (!plot2Series || !plot2Series.length) {
                        plot2Series = null;
                        plot2SeriesIndexes = options.plot2SeriesIndexes;
                    }
                }
                plot2Series || (plot2SeriesIndexes = def.parseDistinctIndexArray(plot2SeriesIndexes, -(1 / 0)) || -1);
            }
            options.plot2Series = plot2Series;
            options.plot2SeriesIndexes = plot2SeriesIndexes;
            dataOptions.measuresIndex = dataOptions.measuresIndex || dataOptions.measuresIdx;
            dataOptions.measuresCount = dataOptions.measuresCount || dataOptions.numMeasures;
        },
        format: function(_) {
            var r = this.root;
            if (r !== this) return r.format.apply(r, arguments);
            var v1 = this._format;
            if (arguments.length) {
                if (!_) throw def.error.argumentRequired("format");
                if (_ !== v1) {
                    if (!def.is(_, cdo.format)) {
                        if (v1) return def.configure(v1, _), this;
                        _ = cdo.format(_);
                    }
                    this._format = _;
                }
                return this;
            }
            return v1;
        },
        render: function(keyArgs) {
            var hasError, bypassAnimation, recreate, reloadData, addData, dataOnRecreate, renderError = null;
            if (1 === arguments.length && keyArgs && "object" == typeof keyArgs) {
                bypassAnimation = keyArgs.bypassAnimation;
                recreate = keyArgs.recreate;
                dataOnRecreate = recreate && keyArgs.dataOnRecreate;
                reloadData = "reload" === dataOnRecreate;
                addData = "add" === dataOnRecreate;
            } else {
                bypassAnimation = arguments[0];
                recreate = arguments[1];
                reloadData = arguments[2];
                addData = !1;
            }
            def.debug > 1 && this.log.group("CCC RENDER");
            this._lastRenderError = null;
            this._suspendSelectionUpdate();
            try {
                this.useTextMeasureCache(function() {
                    try {
                        for (;;) {
                            this.parent || pvc.removeTipsyLegends();
                            (!this.isCreated || recreate) && this._create({
                                reloadData: reloadData,
                                addData: addData
                            });
                            this.basePanel.render({
                                bypassAnimation: bypassAnimation,
                                recreate: recreate
                            });
                            if (!this._isMultiChartOverflowClip) {
                                this._isMultiChartOverflowClipRetry = !1;
                                break;
                            }
                            recreate = !0;
                            reloadData = !1;
                            this._isMultiChartOverflowClipRetry = !0;
                            this._isMultiChartOverflowClip = !1;
                            this._multiChartOverflowClipped = !0;
                        }
                    } catch (e) {
                        renderError = e;
                        if (e instanceof pvc.NoDataException) this._addErrorPanelMessage(e.message, "noDataMessage"); else if (e instanceof pvc.InvalidDataException) this._addErrorPanelMessage(e.message, "invalidDataMessage"); else {
                            hasError = !0;
                            this.log.error(e.message);
                            def.debug > 0 && this._addErrorPanelMessage("Error: " + e.message, "errorMessage");
                        }
                    }
                });
            } finally {
                this._lastRenderError = renderError;
                hasError || this._resumeSelectionUpdate();
                def.debug > 1 && this.log.groupEnd();
            }
            return this;
        },
        getLastRenderError: function() {
            return this._lastRenderError;
        },
        renderResize: function(width, height) {
            var canResizeWidth = null != width, canResizeHeight = null != height;
            if (!canResizeWidth && !canResizeHeight) return this;
            var basePanel = this.basePanel;
            if (basePanel) {
                canResizeWidth && (canResizeWidth = width !== basePanel.width);
                canResizeHeight && (canResizeHeight = height !== basePanel.height);
                var prevLayoutInfo, sizeIncrease;
                if ((prevLayoutInfo = basePanel.getLayout()) && (sizeIncrease = prevLayoutInfo.sizeIncrease)) {
                    canResizeWidth && (canResizeWidth = !sizeIncrease.width || width > basePanel.width);
                    canResizeHeight && (canResizeHeight = !sizeIncrease.height || height > basePanel.height);
                    if (!canResizeWidth && !canResizeHeight) return this;
                }
            }
            canResizeWidth ? this.options.width = width : basePanel && (this.options.width = this.width);
            canResizeHeight ? this.options.height = height : basePanel && (this.options.height = this.height);
            return this.render(!0, !0, !1);
        },
        _addErrorPanelMessage: function(text, extensionId) {
            def.debug > 1 && this.log(text);
            var doRender = !extensionId || this._getExtension(extensionId, "visible") !== !1;
            if (doRender) {
                var options = this.options, pvPanel = new pv.Panel().canvas(options.canvas).width(this.width).height(this.height), pvMsg = pvPanel.anchor("center").add(pv.Label).text(text);
                extensionId && this.extend(pvMsg, extensionId);
                pvPanel.render();
            }
        },
        useTextMeasureCache: function(fun, ctx) {
            var root = this.root, textMeasureCache = root._textMeasureCache || (root._textMeasureCache = pv.Text.createCache());
            return pv.Text.usingCache(textMeasureCache, fun, ctx || this);
        },
        animate: function(start, end) {
            return this.basePanel.animate(start, end);
        },
        animatingStart: function() {
            return this.basePanel.animatingStart();
        },
        animating: function() {
            return !!this.basePanel && this.basePanel.animating();
        },
        isOrientationVertical: function(orientation) {
            return (orientation || this.options.orientation) === pvc.orientation.vertical;
        },
        isOrientationHorizontal: function(orientation) {
            return (orientation || this.options.orientation) === pvc.orientation.horizontal;
        },
        dispose: function() {
            if (!this._disposed) {
                this._disposed = !0;
                var pvRootPanel = this.basePanel && this.basePanel.pvRootPanel, tipsy = pv.Behavior.tipsy;
                tipsy && tipsy.disposeAll && tipsy.disposeAll(pvRootPanel);
                pvRootPanel && pvRootPanel.dispose();
            }
        },
        defaults: {
            width: 400,
            height: 300,
            orientation: "vertical",
            ignoreNulls: !0,
            crosstabMode: !0,
            isMultiValued: !1,
            seriesInRows: !1,
            groupedLabelSep: void 0,
            animate: !0,
            titlePosition: "top",
            titleAlign: "center",
            legend: !1,
            legendPosition: "bottom",
            slidingWindow: !1,
            v1StyleTooltipFormat: function(s, c, v, datum) {
                return s + ", " + c + ":  " + this.chart.options.valueFormat(v) + (datum && datum.percent ? " (" + datum.percent.label + ")" : "");
            },
            clickable: !1,
            doubleClickMaxDelay: 300,
            hoverable: !1,
            selectable: !1,
            selectionMode: "rubberband",
            ctrlSelectMode: !0,
            clearSelectionMode: "emptySpaceClick",
            compatVersion: 1 / 0,
            compatFlags: {
                discreteTimeSeriesTickFormat: !0
            }
        }
    });
    var pvc_initChartClassDefaults = function() {
        var defaults = pvc.BaseChart.prototype.defaults;
        defaults.valueFormat || (defaults.valueFormat = cdo.format.defaults.number());
        defaults.percentValueFormat || (defaults.percentValueFormat = cdo.format.defaults.percent());
        pvc_initChartClassDefaults = null;
    };
    pvc.BaseChart.add({
        visualRoles: null,
        visualRoleList: null,
        _measureVisualRoles: null,
        visualRole: function(roleName) {
            var role = def.getOwn(this.visualRoles, roleName);
            if (!role) throw def.error.operationInvalid("roleName", "There is no visual role with name '{0}'.", [ roleName ]);
            return role;
        },
        measureVisualRoles: function() {
            return this.parent ? this.parent.measureVisualRoles() : this._measureVisualRoles || (this._measureVisualRoles = this.visualRoleList.filter(function(r) {
                return r.isBound() && !r.isDiscrete() && r.isMeasure;
            }));
        },
        measureDimensionsNames: function() {
            return def.query(this.measureVisualRoles()).selectMany(function(r) {
                return r.grouping.dimensionNames();
            }).distinct().array();
        },
        visualRolesOf: function(dimName, includePlotLevel) {
            var visualRolesByDim = this._visRolesByDim;
            if (!visualRolesByDim) {
                visualRolesByDim = this._visRolesByDim = {};
                this.visualRoleList.forEach(function(r) {
                    if (includePlotLevel || !r.plot) {
                        var g = r.grouping;
                        g && g.dimensionNames().forEach(function(n) {
                            def.array.lazy(visualRolesByDim, n).push(r);
                        });
                    }
                });
            }
            return def.getOwn(visualRolesByDim, dimName, null);
        },
        _constructVisualRoles: function() {
            var parent = this.parent;
            if (parent) {
                this.visualRoles = parent.visualRoles;
                this.visualRoleList = parent.visualRoleList;
            } else {
                this.visualRoles = {};
                this.visualRoleList = [];
            }
        },
        _addVisualRole: function(name, keyArgs) {
            keyArgs = def.set(keyArgs, "index", this.visualRoleList.length);
            var role = new pvc.visual.Role(name, keyArgs), names = [ name ];
            role.plot || names.push("$." + name);
            return this._addVisualRoleCore(role, names);
        },
        _addVisualRoleCore: function(role, names) {
            names || (names = role.name);
            this.visualRoleList.push(role);
            def.array.is(names) ? names.forEach(function(name) {
                this.visualRoles[name] = role;
            }, this) : this.visualRoles[names] = role;
            return role;
        },
        _initChartVisualRoles: function() {
            this._addVisualRole("multiChart", {
                defaultDimension: "multiChart*",
                requireIsDiscrete: !0
            });
            this._addVisualRole("dataPart", {
                defaultDimension: "dataPart",
                requireIsDiscrete: !0,
                requireSingleDimension: !0,
                dimensionDefaults: {
                    isHidden: !0,
                    comparer: def.compare
                }
            });
        },
        _getDataPartDimName: function(useDefault) {
            var preGrouping, role = this.visualRoles.dataPart;
            return role.isBound() ? role.lastDimensionName() : (preGrouping = role.preBoundGrouping()) ? preGrouping.lastDimensionName() : useDefault ? role.defaultDimensionGroup : null;
        },
        _processViewSpec: function(viewSpec) {
            if (!viewSpec.dimsKeys) if (viewSpec.role) {
                var role = this.visualRoles[viewSpec.role], grouping = role && role.grouping;
                if (grouping) {
                    viewSpec.dimNames = grouping.dimensionNames().slice().sort();
                    viewSpec.dimsKey = viewSpec.dimNames.join(",");
                }
            } else {
                if (!viewSpec.dims) throw def.error.argumentInvalid("viewSpec", "Invalid view spec. No 'role' or 'dims' property.");
                viewSpec.dimNames = viewSpec_normalizeDims(viewSpec.dims);
                viewSpec.dimsKey = String(viewSpec.dimNames);
            }
        }
    });
    pvc.BaseChart.add({
        dataEngine: null,
        data: null,
        _partsDataCache: null,
        _visibleDataCache: null,
        resultset: [],
        metadata: [],
        _constructData: function(options) {
            this.parent && (this.dataEngine = this.data = options.data || def.fail.argumentRequired("options.data"));
        },
        _checkNoDataI: function() {
            if (!this.allowNoData && !this.resultset.length) throw new pvc.NoDataException();
        },
        _checkNoDataII: function() {
            if (!(this.allowNoData || this.data && this.data.count())) {
                this.data = null;
                throw new pvc.NoDataException();
            }
        },
        _initData: function(ka) {
            if (this.parent) {
                this.slidingWindow = this.parent.slidingWindow;
                this._initAxes();
            } else {
                var data = this.data;
                if (data) if (def.get(ka, "reloadData", !0)) this._reloadData(); else {
                    data.disposeChildren();
                    data.clearVirtuals();
                    def.get(ka, "addData", !1) ? this._addData() : this._initAxes();
                } else this._loadData();
            }
            this.slidingWindow && this.slidingWindow.setAxesDefaults(this);
            delete this._partsDataCache;
            delete this._visibleDataCache;
            def.debug >= 3 && this.log(this.data.getInfo());
        },
        _initSlidingWindow: function() {
            var sw = this.options.slidingWindow ? new pvc.visual.SlidingWindow(this) : null;
            this.slidingWindow = sw && sw.length ? sw : null;
        },
        _loadData: function() {
            DEBUG && (!this.data && !this._translation || def.assert("Invalid state."));
            var complexType, data, translation, options = this.options, dimsOptions = this._createDimensionsOptions(options), ctp = this._createComplexTypeProject(), binder = pvc.visual.rolesBinder().dimensionsOptions(dimsOptions).logger(this._createLogger()).context(this._createVisualRolesContext()).complexTypeProject(ctp).begin(), dataPartDimName = this._getDataPartDimName(!0);
            this._maybeAddPlot2SeriesDataPartCalc(ctp, dataPartDimName) || !this.visualRoles.dataPart.isPreBound() && this.plots.trend && ctp.setDim(dataPartDimName);
            this.metadata.length && (translation = this._translation = this._createTranslation(ctp, dimsOptions, dataPartDimName));
            ctp.hasDim(dataPartDimName) && !ctp.isReadOrCalc(dataPartDimName) && this._addDefaultDataPartCalculation(ctp, dataPartDimName);
            complexType = binder.end();
            this._initSlidingWindow();
            if (this.slidingWindow) {
                this.slidingWindow.setDimensionsOptions(complexType);
                this.slidingWindow.setLayoutPreservation(this);
            }
            data = this.dataEngine = this.data = new cdo.Data({
                type: complexType,
                labelSep: options.groupedLabelSep,
                keySep: options.dataOptions.separator
            });
            this._initAxes();
            if (this.slidingWindow) {
                this.slidingWindow.initFromOptions();
                this.slidingWindow.setDataFilter(this.data);
            }
            translation && this._loadDataCore(data, translation);
        },
        _reloadData: function() {
            var data = this.data, translation = this._translation;
            data && translation || def.assert("Invalid state.");
            translation.setSource(this.resultset);
            def.debug >= 3 && this.log(translation.logSource());
            this._initAxes();
            this._loadDataCore(data, translation);
        },
        _addData: function() {
            var data = this.data, translation = this._translation;
            data && translation || def.assert("Invalid state.");
            translation.setSource(this.resultset);
            def.debug >= 3 && this.log(translation.logSource());
            this._isMultiChartOverflowClipRetry;
            this._initAxes();
            this._loadDataCore(data, translation, {
                isAdditive: !0
            });
        },
        _loadDataCore: function(data, translation, ka) {
            var loadKeyArgs = def.copy(ka, {
                where: this.options.dataOptions.where,
                isNull: this._getIsNullDatum()
            }), readQuery = translation.execute(data);
            data.load(readQuery, loadKeyArgs);
        },
        _createVisualRolesContext: function() {
            var options = this.options, chartRolesOptions = options.visualRoles, roles = this.visualRoles, roleList = this.visualRoleList, context = function(rn) {
                return def.getOwn(roles, rn);
            };
            context.query = function() {
                return def.query(roleList);
            };
            context.getOptions = function(r) {
                var v, opts, U, plot = r.plot, name = r.name;
                if (!plot || plot.isMain) {
                    if ((v = options[name + "Role"]) !== U) return v;
                    if (chartRolesOptions && (v = chartRolesOptions[name]) !== U) return v;
                }
                return plot && (opts = plot._visualRolesOptions) ? opts[name] : void 0;
            };
            return context;
        },
        _createLogger: function() {
            function logger() {
                me.log.apply(me, arguments);
            }
            var me = this;
            logger.level = function() {
                return def.debug;
            };
            return logger;
        },
        _createComplexTypeProject: function() {
            var options = this.options, complexTypeProj = new cdo.ComplexTypeProject(options.dimensionGroups), userDimsSpec = options.dimensions;
            for (var dimName in userDimsSpec) complexTypeProj.setDim(dimName, userDimsSpec[dimName]);
            var calcSpecs = options.calculations;
            calcSpecs && calcSpecs.forEach(complexTypeProj.setCalc, complexTypeProj);
            return complexTypeProj;
        },
        _getIsNullDatum: function() {
            var measureDimNames, M, me = this;
            return function(datum) {
                if (!measureDimNames) {
                    measureDimNames = me.measureDimensionsNames();
                    M = measureDimNames.length;
                }
                if (!M) return !1;
                for (var atoms = datum.atoms, i = 0; M > i; i++) if (null != atoms[measureDimNames[i]].value) return !1;
                return !0;
            };
        },
        _createTranslation: function(complexTypeProj, dimsOptions, dataPartDimName) {
            var translOptions = this._createTranslationOptions(dimsOptions, dataPartDimName), TranslationClass = this._getTranslationClass(translOptions), translation = new TranslationClass(complexTypeProj, this.resultset, this.metadata, translOptions);
            def.debug >= 3 && (this.log(translation.logSource()), this.log(translation.logTranslatorType()));
            translation.configureType();
            def.debug >= 3 && this.log(translation.logLogicalRow());
            return translation;
        },
        _getTranslationClass: function(translOptions) {
            return translOptions.crosstabMode ? cdo.CrosstabTranslationOper : cdo.RelationalTranslationOper;
        },
        _createDimensionsOptions: function(options) {
            return {
                isCategoryTimeSeries: options.timeSeries,
                formatProto: this._format,
                timeSeriesFormat: options.timeSeriesFormat
            };
        },
        _createTranslationOptions: function(dimsOptions, dataPartDimName) {
            var options = this.options, dataOptions = options.dataOptions;
            return def.create(dimsOptions, {
                compatVersion: this.compatVersion(),
                plot2DataSeriesIndexes: options.plot2SeriesIndexes,
                seriesInRows: options.seriesInRows,
                crosstabMode: options.crosstabMode,
                isMultiValued: options.isMultiValued,
                dataPartDimName: dataPartDimName,
                readers: options.readers,
                measuresIndexes: options.measuresIndexes,
                multiChartIndexes: options.multiChartIndexes,
                ignoreMetadataLabels: dataOptions.ignoreMetadataLabels,
                typeCheckingMode: pvc.parseDataTypeCheckingMode(dataOptions.typeCheckingMode),
                separator: dataOptions.separator,
                measuresInColumns: dataOptions.measuresInColumns,
                categoriesCount: dataOptions.categoriesCount,
                measuresIndex: dataOptions.measuresIndex,
                measuresCount: dataOptions.measuresCount
            });
        },
        _maybeAddPlot2SeriesDataPartCalc: function(complexTypeProj, dataPartDimName) {
            if (this.compatVersion() <= 1) return !1;
            var options = this.options, serRole = this.visualRoles.series, plot2Series = serRole ? options.plot2Series : null;
            if (!plot2Series) return !1;
            var seriesDimNames, dataPartDim, part1Atom, part2Atom, buildSeriesKey, plot2SeriesSet = def.query(plot2Series).uniqueIndex(), hasOwnProp = def.hasOwnProp, init = function(datum) {
                if (serRole.isBound()) {
                    seriesDimNames = serRole.grouping.dimensionNames();
                    dataPartDim = datum.owner.dimensions(dataPartDimName);
                    if (seriesDimNames.length > 1) buildSeriesKey = cdo.Complex.compositeKey; else {
                        seriesDimNames = seriesDimNames[0];
                        buildSeriesKey = function(dat, serDimName) {
                            return dat.atoms[serDimName].key;
                        };
                    }
                }
                init = null;
            };
            complexTypeProj.setCalc({
                names: dataPartDimName,
                calculation: function(datum, atoms) {
                    init && init(datum);
                    if (dataPartDim) {
                        var seriesKey = buildSeriesKey(datum, seriesDimNames);
                        atoms[dataPartDimName] = hasOwnProp.call(plot2SeriesSet, seriesKey) ? part2Atom || (part2Atom = dataPartDim.intern("1")) : part1Atom || (part1Atom = dataPartDim.intern("0"));
                    }
                }
            });
            return !0;
        },
        _addDefaultDataPartCalculation: function(complexTypeProj, dataPartDimName) {
            var dataPartDim, part1Atom;
            complexTypeProj.setCalc({
                names: dataPartDimName,
                calculation: function(datum, atoms) {
                    dataPartDim || (dataPartDim = datum.owner.dimensions(dataPartDimName));
                    atoms[dataPartDimName] = part1Atom || (part1Atom = dataPartDim.intern("0"));
                }
            });
        },
        partData: function(dataPartValues, baseData) {
            baseData || (baseData = this.data);
            if (null == dataPartValues) return baseData;
            if (this.parent) return this.root.partData(dataPartValues, baseData);
            var partRole = this.visualRoles.dataPart;
            if (!partRole || !partRole.isBound()) return baseData;
            var cacheKey = "\x00" + baseData.id + ":" + def.nullyTo(dataPartValues, ""), partitionedDataCache = def.lazy(this, "_partsDataCache"), partData = partitionedDataCache[cacheKey];
            if (!partData) {
                partData = this._createPartData(baseData, partRole, dataPartValues);
                partitionedDataCache[cacheKey] = partData;
            }
            return partData;
        },
        _createPartData: function(baseData, partRole, dataPartValues) {
            var dataPartDimName = partRole.lastDimensionName(), dataPartAtoms = baseData.dimensions(dataPartDimName).getDistinctAtoms(def.array.to(dataPartValues)), where = cdo.whereSpecPredicate([ def.set({}, dataPartDimName, dataPartAtoms) ]);
            return baseData.where(null, {
                where: where
            });
        },
        visibleData: function(dataPartValue, ka) {
            var mainPlot = this.plots.main || def.fail.operationInvalid("There is no main plot defined.");
            return this.visiblePlotData(mainPlot, dataPartValue, ka);
        },
        visiblePlotData: function(plot, dataPartValue, ka) {
            var baseData = def.get(ka, "baseData") || this.data;
            if (this.parent) {
                ka = ka ? Object.create(ka) : {};
                ka.baseData = baseData;
                return this.root.visiblePlotData(plot, dataPartValue, ka);
            }
            var inverted = !!def.get(ka, "inverted", !1), ignoreNulls = !(!this.options.ignoreNulls && !def.get(ka, "ignoreNulls", !0)), key = [ plot.id, baseData.id, inverted, ignoreNulls, null != dataPartValue ? dataPartValue : null ].join("|"), cache = def.lazy(this, "_visibleDataCache"), data = cache[key];
            if (!data) {
                var partData = this.partData(dataPartValue, baseData);
                ka = ka ? Object.create(ka) : {};
                ka.visible = !0;
                ka.isNull = ignoreNulls ? !1 : null;
                data = cache[key] = plot.createVisibleData(partData, ka);
            }
            return data;
        },
        _initMultiCharts: function() {
            var chart = this;
            chart.multiOptions = new pvc.visual.MultiChart(chart);
            chart.smallOptions = new pvc.visual.SmallChart(chart);
            var colCount, rowCount, multiChartMax, colsMax, multiOption = chart.multiOptions.option, data = chart.visualRoles.multiChart.flatten(chart.data, {
                visible: !0,
                isNull: null
            }), smallDatas = data.childNodes;
            if (chart._isMultiChartOverflowClipRetry) {
                rowCount = chart._clippedMultiChartRowsMax;
                colCount = chart._clippedMultiChartColsMax;
                colsMax = colCount;
                multiChartMax = rowCount * colCount;
            } else multiChartMax = multiOption("Max");
            var count = Math.min(smallDatas.length, multiChartMax);
            if (0 === count) colCount = rowCount = colsMax = 0; else if (!chart._isMultiChartOverflowClipRetry) {
                colsMax = multiOption("ColumnsMax");
                colCount = Math.min(count, colsMax);
                colCount >= 1 && isFinite(colCount) || def.assert("Must be at least 1 and finite");
                rowCount = Math.ceil(count / colCount);
                rowCount >= 1 || def.assert("Must be at least 1");
            }
            chart._multiInfo = {
                data: data,
                smallDatas: smallDatas,
                count: count,
                rowCount: rowCount,
                colCount: colCount,
                colsMax: colsMax
            };
        },
        interpolatable: function() {
            var plotList = this.plotList;
            return !!plotList && plotList.some(function(p) {
                return p.interpolatable();
            });
        },
        _interpolate: function(hasMultiRole) {
            if (this.interpolatable()) {
                var dataCells = def.query(this.axesList).selectMany(def.propGet("dataCells")).where(function(dataCell) {
                    var nim = dataCell.nullInterpolationMode;
                    return !!nim && "none" !== nim;
                }).distinct(function(dataCell) {
                    return [ dataCell.nullInterpolationMode, dataCell.role.grouping.id, dataCell.dataPartValue || "" ].join();
                }).array();
                this._eachLeafDatasAndDataCells(hasMultiRole, dataCells, function(dataCell, baseData) {
                    dataCell.plot.interpolateDataCell(dataCell, baseData);
                });
            }
        },
        _generateTrends: function(hasMultiRole) {
            var dataPartDimName = this._getDataPartDimName();
            if (dataPartDimName && this.plots.trend) {
                var dataCells = def.query(this.axesList).selectMany(def.propGet("dataCells")).where(def.propGet("trend")).distinct(function(dataCell) {
                    return dataCell.role.name + "|" + (dataCell.dataPartValue || "");
                }).array(), newDatums = [];
                this._eachLeafDatasAndDataCells(hasMultiRole, dataCells, function(dataCell, baseData) {
                    dataCell.plot.generateTrendsDataCell(newDatums, dataCell, baseData);
                });
                newDatums.length && this.data.owner.add(newDatums);
            }
        },
        _eachLeafDatasAndDataCells: function(hasMultiRole, dataCells, f, x) {
            var C = dataCells.length;
            if (C) {
                var leafDatas, D;
                if (hasMultiRole) {
                    leafDatas = this._multiInfo.smallDatas;
                    D = this._multiInfo.count;
                } else {
                    leafDatas = [ this.data ];
                    D = 1;
                }
                for (var d = 0; D > d; d++) for (var leafData = leafDatas[d], c = 0; C > c; c++) f.call(x, dataCells[c], leafData, c, d);
            }
        },
        _getTrendDataPartAtom: function() {
            var dataPartDimName = this._getDataPartDimName();
            return dataPartDimName ? this.data.owner.dimensions(dataPartDimName).intern("trend") : void 0;
        },
        setData: function(data, options) {
            this.setResultset(data && data.resultset);
            this.setMetadata(data && data.metadata);
            $.extend(this.options, options);
            return this;
        },
        setResultset: function(resultset) {
            !this.parent || def.fail.operationInvalid("Can only set resultset on root chart.");
            this.resultset = resultset || [];
            this.resultset.length || this.log.warn("Resultset is empty");
            return this;
        },
        setMetadata: function(metadata) {
            !this.parent || def.fail.operationInvalid("Can only set metadata on root chart.");
            this.metadata = metadata || [];
            this.metadata.length || this.log.warn("Metadata is empty");
            return this;
        }
    });
    pvc.BaseChart.add({
        _initPlots: function() {
            var parent = this.parent;
            if (parent) {
                this.plots = parent.plots;
                this.plotList = parent.plotList;
                this.plotsByType = parent.plotsByType;
            } else {
                this.plots = {};
                this.plotList = [];
                this.plotsByType = {};
                this._createPlotsInternal();
                var trendPlotDefExt = this._defPlotsExternal();
                this._initPlotTrend(trendPlotDefExt);
            }
            this._initPlotsEnd();
        },
        _createPlotsInternal: function() {},
        _defPlotsExternal: function() {
            var trendPlotDefExt, plots = this.plots, plotDefs = this.options.plots;
            plotDefs && plotDefs.forEach(function(plotDef) {
                if (plotDef) {
                    var name = plotDef.name;
                    "trend" === name ? trendPlotDefExt = plotDef : ("plot2" !== name || plots.plot2) && this._defPlotExternal(name, plotDef);
                }
            }, this);
            return trendPlotDefExt;
        },
        _initPlotTrend: function(trendPlotDefExt) {
            var needsTrendPlot = this.plotList.some(function(p) {
                return p.option.isDefined("Trend") && !!p.option("Trend");
            });
            if (needsTrendPlot) {
                this._createPlotTrend();
                trendPlotDefExt && this.plots.trend && this._defPlotExternal("trend", trendPlotDefExt);
            }
        },
        _defPlotExternal: function(name, plotSpec) {
            var plot, type = plotSpec.type;
            if (name) {
                name = def.firstLowerCase(name);
                plot = this.plots && this.plots[name];
                if (plot && type && type !== plot.type) throw def.error.argumentInvalid("plots", "Plot named '{0}' is already defined and is of a different type: '{1}'", [ name, plot.type ]);
            }
            plot ? plot.processSpec(plotSpec) : this._addPlot(this._createPlotExternal(name, type, plotSpec));
        },
        _createPlotExternal: function(name, type, plotSpec) {
            if (!type) throw def.error.argumentInvalid("plots", "Plot 'type' option is required.");
            var PlotClass = pvc.visual.Plot.getClass(type);
            if (!PlotClass) throw def.error.argumentInvalid("plots", "The plot type '{0}' is not defined.", [ type ]);
            return new PlotClass(this, {
                name: name,
                isInternal: !1,
                spec: plotSpec
            });
        },
        _createPlotTrend: function() {},
        _addPlot: function(plot) {
            var plots = this.plots, index = plot.index, name = plot.name, id = plot.id;
            if (name && def.hasOwn(plots, name)) throw def.error.operationInvalid("Plot name '{0}' already taken.", [ name ]);
            if (def.hasOwn(plots, id)) throw def.error.operationInvalid("Plot id '{0}' already taken.", [ id ]);
            var typePlots = def.array.lazy(this.plotsByType, plot.type);
            if (def.hasOwn(typePlots, index)) throw def.error.operationInvalid("Plot index '{0}' of type '{1}' already taken.", [ index, plot.type ]);
            plot.globalIndex = this.plotList.length;
            var isMain = !plot.globalIndex;
            typePlots[index] = plot;
            this.plotList.push(plot);
            plots[id] = plot;
            name && (plots[name] = plot);
            isMain && (plots.main = plot);
        },
        _initPlotsEnd: function() {
            var dataCellsByAxisTypeThenIndex;
            if (this.parent) dataCellsByAxisTypeThenIndex = this.parent._dataCellsByAxisTypeThenIndex; else {
                dataCellsByAxisTypeThenIndex = {};
                this.plotList.forEach(function(plot) {
                    plot.initEnd();
                    this._registerPlotVisualRoles(plot);
                    this._indexPlotDataCells(plot, dataCellsByAxisTypeThenIndex);
                }, this);
            }
            this._dataCellsByAxisTypeThenIndex = dataCellsByAxisTypeThenIndex;
        },
        _registerPlotVisualRoles: function(plot) {
            var name = plot.name, id = plot.id, isMain = plot.isMain;
            plot.visualRoleList.forEach(function(role) {
                var rname = role.name, names = [];
                if (isMain) {
                    rname in this.visualRoles || names.push(rname);
                    names.push("main." + rname);
                }
                names.push(id + "." + rname);
                name && names.push(name + "." + rname);
                this._addVisualRoleCore(role, names);
            }, this);
        },
        _indexPlotDataCells: function(plot, dataCellsByAxisTypeThenIndex) {
            plot.dataCellList.forEach(function(dataCell) {
                var dataCellsByAxisIndex = def.array.lazy(dataCellsByAxisTypeThenIndex, dataCell.axisType);
                def.array.lazy(dataCellsByAxisIndex, dataCell.axisIndex).push(dataCell);
            });
        }
    });
    pvc.BaseChart.add({
        colors: null,
        axes: null,
        axesList: null,
        axesByType: null,
        _axisClassByType: {
            color: pvc.visual.ColorAxis,
            size: pvc.visual.SizeAxis
        },
        _axisCreateChartLevel: {
            color: 1,
            size: 2,
            base: 3,
            ortho: 3
        },
        _axisSetScaleChartLevel: {
            color: 1,
            size: 2,
            base: 2,
            ortho: 2
        },
        _axisCreationOrder: [ "color", "size", "base", "ortho" ],
        _axisCreateIfUnbound: {},
        _chartLevel: function() {
            var level = 0;
            this.parent || (level |= 1);
            (this.parent || !this.visualRoles.multiChart.isBound()) && (level |= 2);
            return level;
        },
        _initAxes: function() {
            var axesState, oldByType = this.axesByType;
            if (this.axes) {
                axesState = {};
                this.axesList.forEach(function(axis) {
                    axesState[axis.id] = axis.getState();
                });
            }
            var getAxisState = function(type, axisIndex) {
                if (oldByType) {
                    var axes = oldByType[type];
                    if (axes) {
                        var axisId = axes[axisIndex].id, state = axesState ? axesState[axisId] : void 0;
                        return state;
                    }
                }
            };
            this.axes = {};
            this.axesList = [];
            this.axesByType = {};
            delete this._rolesColorScale;
            var dataCellsByAxisTypeThenIndex = this._dataCellsByAxisTypeThenIndex;
            this.parent || def.eachOwn(dataCellsByAxisTypeThenIndex, function(dataCellsByAxisIndex, type) {
                for (var i = 0, I = dataCellsByAxisIndex.length; I > i; ) {
                    var dataCells = dataCellsByAxisIndex[i];
                    if (dataCells) {
                        dataCells = dataCells.filter(function(dataCell) {
                            return dataCell.role.isBound();
                        });
                        if (dataCells.length) {
                            dataCellsByAxisIndex[i] = dataCells;
                            i++;
                        } else {
                            dataCellsByAxisIndex.splice(i, 1);
                            I--;
                        }
                    } else i++;
                }
                dataCellsByAxisIndex.length || delete dataCellsByAxisTypeThenIndex[type];
            });
            var chartLevel = this._chartLevel();
            this._axisCreationOrder.forEach(function(type) {
                if (0 !== (this._axisCreateChartLevel[type] & chartLevel)) {
                    var AxisClass, dataCellsOfTypeByIndex = dataCellsByAxisTypeThenIndex[type];
                    if (dataCellsOfTypeByIndex) {
                        AxisClass = this._axisClassByType[type] || pvc.visual.Axis;
                        dataCellsOfTypeByIndex.forEach(function(dataCells) {
                            var axisIndex = dataCells[0].axisIndex;
                            new AxisClass(this, type, axisIndex, {
                                state: getAxisState(type, axisIndex)
                            });
                        }, this);
                    } else if (this._axisCreateIfUnbound[type]) {
                        AxisClass = this._axisClassByType[type] || pvc.visual.Axis;
                        AxisClass && new AxisClass(this, type, 0);
                    }
                }
            }, this);
            this.parent && this.root.axesList.forEach(function(axis) {
                def.hasOwn(this.axes, axis.id) || this._addAxis(axis);
            }, this);
            def.eachOwn(dataCellsByAxisTypeThenIndex, function(dataCellsOfTypeByIndex, type) {
                this._axisCreateChartLevel[type] & chartLevel && dataCellsOfTypeByIndex.forEach(function(dataCells) {
                    var axisIndex = dataCells[0].axisIndex, axis = this.axes[def.indexedId(type, axisIndex)];
                    axis.isBound() || axis.bind(dataCells);
                }, this);
            }, this);
        },
        _initAxesEnd: function() {},
        _addAxis: function(axis) {
            this.axes[axis.id] = axis;
            axis.chart === this && (axis.axisIndex = this.axesList.length);
            this.axesList.push(axis);
            var typeAxes = def.array.lazy(this.axesByType, axis.type), typeIndex = typeAxes.count || 0;
            axis.typeIndex = typeIndex;
            typeAxes[axis.index] = axis;
            typeIndex || (typeAxes.first = axis);
            typeAxes.count = typeIndex + 1;
            "color" === axis.type && axis.isBound() && this._onColorAxisScaleSet(axis);
            return this;
        },
        _getAxis: function(type, index) {
            var typeAxes;
            return null != index && +index >= 0 && (typeAxes = this.axesByType[type]) ? typeAxes[index] : void 0;
        },
        _setAxesScales: function(chartLevel) {
            this.axesList.forEach(function(axis) {
                this._axisSetScaleChartLevel[axis.type] & chartLevel && axis.isBound() && this._setAxisScale(axis, chartLevel);
            }, this);
        },
        _setAxisScale: function(axis, chartLevel) {
            this._setAxisScaleByScaleType(axis, chartLevel);
        },
        _setAxisScaleByScaleType: function(axis, chartLevel) {
            switch (axis.scaleType) {
              case "discrete":
                this._setDiscreteAxisScale(axis, chartLevel);
                break;

              case "numeric":
                this._setNumericAxisScale(axis, chartLevel);
                break;

              case "timeSeries":
                this._setTimeSeriesAxisScale(axis, chartLevel);
                break;

              default:
                throw def.error("Unknown axis scale type.");
            }
        },
        _describeScale: function(axis, scale) {
            scale.isNull && def.debug >= 3 && this.log(def.format("{0} scale for axis '{1}'- no data", [ axis.scaleType, axis.id ]));
        },
        _setDiscreteAxisScale: function(axis) {
            if ("color" === axis.type) return this._setDiscreteColorAxisScale(axis);
            var values = axis.domainValues(), scale = new pv.Scale.ordinal();
            values.length ? scale.domain(values) : scale.isNull = !0;
            this._describeScale(axis, scale);
            axis.setScale(scale);
        },
        _setTimeSeriesAxisScale: function(axis) {
            var extent = this._getContinuousVisibleExtentConstrained(axis), scale = new pv.Scale.linear();
            if (extent) {
                var dMin = extent.min, dMax = extent.max, epsi = 1, normalize = function() {
                    var d = dMax - dMin;
                    if (d && Math.abs(d) < epsi) {
                        dMax = dMin = new Date(Math.round((dMin + dMax) / 2));
                        d = 0;
                    }
                    if (d) 0 > d && (!extent.maxLocked || extent.minLocked ? dMax = new Date(dMin.getTime() + pvc.time.intervals.h) : dMin = new Date(dMax.getTime() - pvc.time.intervals.h)); else {
                        extent.minLocked || (dMin = new Date(dMin.getTime() - pvc.time.intervals.h));
                        (!extent.maxLocked || extent.minLocked) && (dMax = new Date(dMax.getTime() + pvc.time.intervals.h));
                    }
                };
                normalize();
                scale.domain(dMin, dMax);
                scale.minLocked = extent.minLocked;
                scale.maxLocked = extent.maxLocked;
            } else scale.isNull = !0;
            this._describeScale(axis, scale);
            axis.setScale(scale);
        },
        _setNumericAxisScale: function(axis) {
            if ("color" === axis.type) return this._setNumericColorAxisScale(axis);
            var extent = this._getContinuousVisibleExtentConstrained(axis), scale = new pv.Scale.linear();
            if (extent) {
                var dMin = extent.min, dMax = extent.max, epsi = 1e-10, normalize = function() {
                    var d = dMax - dMin;
                    if (d && Math.abs(d) <= epsi) {
                        dMin = (dMin + dMax) / 2;
                        dMin = dMax = +dMin.toFixed(10);
                        d = 0;
                    }
                    if (d) 0 > d && (!extent.maxLocked || extent.minLocked ? dMax = Math.abs(dMin) > epsi ? 1.01 * dMin : .1 : dMin = Math.abs(dMax) > epsi ? .99 * dMax : -.1); else {
                        extent.minLocked || (dMin = Math.abs(dMin) > epsi ? .99 * dMin : -.1);
                        (!extent.maxLocked || extent.minLocked) && (dMax = Math.abs(dMax) > epsi ? 1.01 * dMax : .1);
                    }
                };
                normalize();
                var includeZero = !extent.lengthLocked && axis.option.isDefined("OriginIsZero") && axis.option("OriginIsZero");
                if (includeZero) if (0 === dMin) extent.minLocked = !0; else if (0 === dMax) extent.maxLocked = !0; else if (dMin * dMax > 0) {
                    if (dMin > 0) {
                        if (!extent.minLocked) {
                            extent.minLocked = !0;
                            dMin = 0;
                        }
                    } else if (!extent.maxLocked) {
                        extent.maxLocked = !0;
                        dMax = 0;
                    }
                    normalize();
                }
                scale.domain(dMin, dMax);
                scale.minLocked = extent.minLocked;
                scale.maxLocked = extent.maxLocked;
            } else scale.isNull = !0;
            this._describeScale(axis, scale);
            axis.setScale(scale);
        },
        _warnSingleContinuousValueRole: function(valueRole) {
            valueRole.grouping.isSingleDimension || this.log.warn("A linear scale can only be obtained for a single dimension role.");
            valueRole.grouping.isDiscrete() && this.log.warn(def.format("The single dimension of role '{0}' should be continuous.", [ valueRole.name ]));
        },
        _getContinuousVisibleExtentConstrained: function(axis) {
            var dim, me = this, opts = axis.option, read = function(v) {
                dim || (dim = me.data.owner.dimensions(axis.role.grouping.lastDimensionName()));
                var v = dim.read(v);
                return null != v ? v.value : null;
            }, readLimit = function(name) {
                if (!opts.isDefined(name)) return null;
                var v = opts(name);
                return null != v ? read(v) : v;
            }, length = opts.isDefined("FixedLength") ? opts("FixedLength") : null, min = null, max = null, minLocked = !1, maxLocked = !1;
            if (null != (min = readLimit("FixedMin"))) {
                minLocked = !0;
                if (length) {
                    max = +min + length;
                    maxLocked = !0;
                }
            }
            if (null == max && null != (max = readLimit("FixedMax"))) {
                maxLocked = !0;
                if (length) {
                    min = max - length;
                    minLocked = !0;
                }
            }
            if (null == min || null == max) {
                var dataExtent = this._getContinuousVisibleExtent(axis);
                if (!dataExtent) return null;
                if (length) switch (opts("DomainAlign")) {
                  case "min":
                    min = dataExtent.min;
                    max = +min + length;
                    break;

                  case "max":
                    max = dataExtent.max;
                    min = max - length;
                    break;

                  default:
                    var center = dataExtent.max - (dataExtent.max - dataExtent.min) / 2;
                    min = center - length / 2;
                    max = center + length / 2;
                } else {
                    null == min && (min = dataExtent.min);
                    null == max && (max = dataExtent.max);
                }
            }
            if (axis.scaleUsesAbs()) {
                0 > min && (min = -min);
                0 > max && (max = -max);
            }
            if (+min > +max) {
                var temp = min;
                min = max;
                max = temp;
            }
            return {
                min: read(min),
                max: read(max),
                minLocked: minLocked,
                maxLocked: maxLocked,
                lengthLocked: null != length
            };
        },
        _getContinuousVisibleExtent: function(valueAxis) {
            var dataCells = valueAxis.dataCells;
            if (1 === dataCells.length) {
                var valueDataCell = dataCells[0];
                return valueDataCell.plot.getContinuousVisibleCellExtent(this, valueAxis, valueDataCell);
            }
            return def.query(dataCells).select(function(dataCell) {
                return dataCell.plot.getContinuousVisibleCellExtent(this, valueAxis, dataCell);
            }, this).reduce(pvc.unionExtents, null);
        },
        _setDiscreteColorAxisScale: function(axis) {
            var scale = axis.scheme()(axis.domainValues());
            this._describeScale(axis, scale);
            axis.setScale(scale, !0);
            this._onColorAxisScaleSet(axis);
        },
        _setNumericColorAxisScale: function(axis) {
            if (1 !== axis.dataCells.length) throw def.error("Can't handle multiple continuous datacells in color axis.");
            this._warnSingleContinuousValueRole(axis.role);
            var visibleDomainData = this.visiblePlotData(axis.dataCell.plot, axis.dataCell.dataPartValue), normByCateg = axis.option("NormByCategory"), scaleOptions = {
                type: axis.option("ScaleType"),
                colors: axis.option("Colors")().range(),
                colorDomain: axis.option("Domain"),
                colorMin: axis.option("Min"),
                colorMax: axis.option("Max"),
                colorMissing: axis.option("Missing"),
                data: visibleDomainData,
                colorDimension: axis.role.lastDimensionName(),
                normPerBaseCategory: normByCateg
            };
            if (normByCateg) axis.scalesByCateg = pvc_colorScales(scaleOptions); else {
                var scale = pvc_colorScale(scaleOptions);
                this._describeScale(axis, scale);
                axis.setScale(scale);
            }
            this._onColorAxisScaleSet(axis);
        },
        _onColorAxisScaleSet: function(axis) {
            switch (axis.index) {
              case 0:
                this.colors = axis.scheme();
                break;

              case 1:
                this._allowV1SecondAxis && (this.secondAxisColor = axis.scheme());
            }
        },
        _getRoleColorScale: function(grouping) {
            return def.lazy(def.lazy(this, "_rolesColorScale"), grouping.id, this._createRoleColorScale, this);
        },
        _createRoleColorScale: function(groupingId) {
            function addDomainValue(value) {
                var key = "" + value;
                def.hasOwnProp.call(valueToColorMap, key) || (valueToColorMap[key] = scale(value));
            }
            var firstScale, scale, valueToColorMap = {};
            this.axesByType.color.forEach(function(axis) {
                var axisRole = axis.role;
                if (axisRole && axis.scale && "discrete" === axis.scaleType && axisRole.grouping.id === groupingId && 0 === axis.index || axis.option.isSpecified("Colors") || axis.option.isSpecified("Map")) {
                    scale = axis.scale;
                    firstScale || (firstScale = scale);
                    axis.domainValues().forEach(addDomainValue);
                }
            }, this);
            if (!firstScale) return pvc.createColorScheme()();
            scale = function(value) {
                var key = "" + value;
                return def.hasOwnProp.call(valueToColorMap, key) ? valueToColorMap[key] : valueToColorMap[key] = firstScale(value);
            };
            def.copy(scale, firstScale);
            return scale;
        },
        _onLaidOut: function() {}
    });
    pvc.BaseChart.add({
        basePanel: null,
        contentPanel: null,
        titlePanel: null,
        legendPanel: null,
        _multiChartPanel: null,
        _initChartPanels: function(hasMultiRole) {
            this._initBasePanel();
            this._initTitlePanel();
            var legendPanel = this._initLegendPanel(), isMultichartRoot = hasMultiRole && !this.parent;
            isMultichartRoot && this._initMultiChartPanel();
            legendPanel && this._initLegendScenes(legendPanel);
            if (!isMultichartRoot) {
                var o = this.options;
                this.contentPanel = this._createContentPanel(this.basePanel, {
                    margins: hasMultiRole ? o.smallContentMargins : o.contentMargins,
                    paddings: hasMultiRole ? o.smallContentPaddings : o.contentPaddings
                });
                this._createContent(this.contentPanel, {
                    clickAction: o.clickAction,
                    doubleClickAction: o.doubleClickAction
                });
            }
        },
        _initBasePanel: function() {
            var p = this.parent;
            this.basePanel = new pvc.BasePanel(this, p && p._multiChartPanel, {
                margins: this.margins,
                paddings: this.paddings,
                size: {
                    width: this.width,
                    height: this.height
                }
            });
        },
        _initTitlePanel: function() {
            var o = this.options, title = o.title, titleVisible = o.titleVisible;
            null == titleVisible && (titleVisible = !def.empty(title));
            if (titleVisible) {
                var state, titlePanel = this.titlePanel;
                titlePanel && this._preserveLayout && (state = titlePanel._getLayoutState());
                this.titlePanel = new pvc.TitlePanel(this, this.basePanel, {
                    title: title,
                    font: o.titleFont,
                    anchor: o.titlePosition,
                    align: o.titleAlign,
                    alignTo: o.titleAlignTo,
                    offset: o.titleOffset,
                    keepInBounds: o.titleKeepInBounds,
                    margins: state ? state.margins : o.titleMargins,
                    paddings: state ? state.paddings : o.titlePaddings,
                    titleSize: state ? state.size : o.titleSize,
                    titleSizeMax: o.titleSizeMax
                });
            }
        },
        _initLegendPanel: function() {
            var o = this.options;
            if (o.legend) {
                var state, legend = new pvc.visual.Legend(this, "legend", 0), legendPanel = this.legendPanel;
                legendPanel && this._preserveLayout && (state = legendPanel._getLayoutState());
                return this.legendPanel = new pvc.LegendPanel(this, this.basePanel, {
                    anchor: legend.option("Position"),
                    align: legend.option("Align"),
                    alignTo: o.legendAlignTo,
                    offset: o.legendOffset,
                    keepInBounds: o.legendKeepInBounds,
                    size: state ? state.size : legend.option("Size"),
                    margins: state ? state.margins : legend.option("Margins"),
                    paddings: state ? state.paddings : legend.option("Paddings"),
                    sizeMax: legend.option("SizeMax"),
                    font: legend.option("Font"),
                    scenes: def.getPath(o, "legend.scenes"),
                    textMargin: o.legendTextMargin,
                    itemPadding: o.legendItemPadding,
                    itemSize: legend.option("ItemSize"),
                    itemCountMax: legend.option("ItemCountMax"),
                    overflow: legend.option("Overflow"),
                    markerSize: o.legendMarkerSize
                });
            }
        },
        _getLegendRootScene: function() {
            return this.legendPanel && this.legendPanel._getRootScene();
        },
        _initMultiChartPanel: function() {
            var basePanel = this.basePanel, options = this.options;
            this.contentPanel = this._multiChartPanel = new pvc.MultiChartPanel(this, basePanel, {
                margins: options.contentMargins,
                paddings: options.contentPaddings
            });
            this._multiChartPanel.createSmallCharts();
            basePanel._children.unshift(basePanel._children.pop());
        },
        _coordinateSmallChartsLayout: function() {},
        _registerInitLegendScenes: function(handler) {
            def.array.lazy(this, "_initLegendScenesHandlers").push(handler);
        },
        _initLegendScenes: function(legendPanel) {
            this._initLegendScenesHandlers && this._initLegendScenesHandlers.forEach(function(f) {
                f(legendPanel);
            });
            var colorAxes = this.axesByType.color;
            if (colorAxes) {
                var rootScene, dataPartDimName = this._getDataPartDimName(), legendIndex = 0, getRootScene = function() {
                    return rootScene || (rootScene = legendPanel._getRootScene());
                };
                colorAxes.forEach(function(axis) {
                    var visibleDataCells;
                    if (axis.option("LegendVisible") && axis.isBound() && axis.isDiscrete() && (visibleDataCells = axis.dataCells.filter(function(dc) {
                        return dc.legendVisible();
                    })).length) {
                        var groupScene, colorScale = axis.scale, data = axis.domainData(), visibleDataParts = dataPartDimName && def.query(visibleDataCells).uniqueIndex(function(dc) {
                            return dc.dataPartValue;
                        });
                        groupScene = getRootScene().createGroup({
                            source: data,
                            colorAxis: axis,
                            legendBaseIndex: legendIndex
                        });
                        axis.domainItems(data).forEach(function(itemData) {
                            if (dataPartDimName) {
                                var anyDataPartVisible = itemData.dimensions(dataPartDimName).atoms().some(function(atom) {
                                    return def.hasOwn(visibleDataParts, atom.value);
                                });
                                if (!anyDataPartVisible) return;
                            }
                            var itemScene = groupScene.createItem({
                                source: itemData
                            }), itemValue = axis.domainItemValue(itemData);
                            itemScene.color = colorScale(itemValue);
                        });
                        legendIndex += axis.dataCells.length;
                    }
                });
            }
        },
        _createContentPanel: function(parentPanel, contentOptions) {
            return new pvc.ContentPanel(this, parentPanel, {
                margins: contentOptions.margins,
                paddings: contentOptions.paddings
            });
        },
        _createContent: function(parentPanel, contentOptions) {
            var index = 0;
            this.plotList.forEach(function(plot) {
                this._createPlotPanel(plot, parentPanel, contentOptions, index);
                index++;
            }, this);
        },
        _createPlotPanel: function(plot, parentPanel, contentOptions, index) {
            var PlotPanelClass = pvc.PlotPanel.getClass(plot.type);
            if (!PlotPanelClass) throw def.error.invalidOperation("There is no registered panel class for plot type '{0}'.", [ plot.type ]);
            var options = Object.create(contentOptions);
            if (this._preserveLayout) {
                var state = this._preservedPlotsLayoutInfo[plot.id];
                options.size = state.size;
                options.paddings = state.paddings;
                options.margins = state.margins;
            }
            var panel = new PlotPanelClass(this, parentPanel, plot, options), name = plot.name, plotPanels = this.plotPanels;
            plotPanels[plot.id] = panel;
            name && (plotPanels[name] = panel);
            plot.globalIndex || (plotPanels.main = panel);
            this.plotPanelList.push(panel);
        }
    });
    pvc.BaseChart.add({
        _updateSelectionSuspendCount: 0,
        _lastSelectedDatums: null,
        clearSelections: function() {
            this.data.owner.clearSelected() && this.updateSelections();
            return this;
        },
        _updatingSelections: function(method, context) {
            this._suspendSelectionUpdate();
            try {
                method.call(context || this);
            } finally {
                this._resumeSelectionUpdate();
            }
        },
        _suspendSelectionUpdate: function() {
            this === this.root ? this._updateSelectionSuspendCount++ : this.root._suspendSelectionUpdate();
        },
        _resumeSelectionUpdate: function() {
            this === this.root ? this._updateSelectionSuspendCount > 0 && !--this._updateSelectionSuspendCount && this.updateSelections() : this.root._resumeSelectionUpdate();
        },
        renderInteractive: function() {
            this.useTextMeasureCache(function() {
                this.basePanel.renderInteractive();
            }, this);
            return this;
        },
        updateSelections: function(keyArgs) {
            if (this === this.root) {
                if (this._inUpdateSelections || this._updateSelectionSuspendCount) return this;
                var selectedChangedDatumMap = this._calcSelectedChangedDatums();
                if (!selectedChangedDatumMap) return this;
                pvc.removeTipsyLegends();
                this._inUpdateSelections = !0;
                try {
                    var action = this.options.selectionChangedAction;
                    if (action) {
                        var selectedDatums = this.data.selectedDatums(), selectedChangedDatums = selectedChangedDatumMap.values();
                        action.call(this.basePanel.context(), selectedDatums, selectedChangedDatums);
                    }
                    def.get(keyArgs, "render", !0) && this.useTextMeasureCache(function() {
                        this.basePanel.renderInteractive();
                    }, this);
                } finally {
                    this._inUpdateSelections = !1;
                }
            } else this.root.updateSelections(keyArgs);
            return this;
        },
        _calcSelectedChangedDatums: function() {
            if (this.data) {
                var selectedChangedDatums, nowSelectedDatums = this.data.selectedDatumMap(), lastSelectedDatums = this._lastSelectedDatums;
                if (lastSelectedDatums) {
                    selectedChangedDatums = lastSelectedDatums.symmetricDifference(nowSelectedDatums);
                    if (!selectedChangedDatums.count) return;
                } else {
                    if (!nowSelectedDatums.count) return;
                    selectedChangedDatums = nowSelectedDatums.clone();
                }
                this._lastSelectedDatums = nowSelectedDatums;
                return selectedChangedDatums;
            }
        },
        _onUserSelection: function(datums) {
            if (!datums || !datums.length) return datums;
            if (this === this.root) {
                var action = this.options.userSelectionAction;
                return action ? action.call(this.basePanel.context(), datums) || datums : datums;
            }
            return this.root._onUserSelection(datums);
        }
    });
    pvc.BaseChart.add({
        _processExtensionPoints: function() {
            if (this.parent) this._components = this.parent._components; else {
                this._processExtensionPointsIn(this.options);
                this._processExtensionPointsIn(this.options.extensionPoints);
            }
        },
        _processExtensionPointsIn: function(points, prefix, fNep) {
            var name, id, prop, splitIndex, comps = this._components || (this._components = {});
            for (name in points) {
                splitIndex = name.indexOf("_");
                if (splitIndex > 0) {
                    id = name.substring(0, splitIndex);
                    prop = name.substring(splitIndex + 1);
                    if (id && prop) {
                        prefix && (id = pvc_unwrapExtensionOne(id, prefix));
                        (def.getOwn(comps, id) || (comps[id] = new def.OrderedMap())).add(prop, points[name]);
                    }
                } else if (fNep) {
                    id = prefix ? pvc_unwrapExtensionOne(name, prefix) : name;
                    fNep(points[name], id, name);
                }
            }
        },
        extend: function(mark, ids, keyArgs) {
            def.array.is(ids) ? ids.forEach(function(id) {
                this._extendCore(mark, id, keyArgs);
            }, this) : this._extendCore(mark, ids, keyArgs);
        },
        _extendCore: function(mark, id, keyArgs) {
            if (mark) {
                var component = def.getOwn(this._components, id);
                if (component) {
                    mark.borderPanel && (mark = mark.borderPanel);
                    var logOut = def.debug >= 3 ? [] : null, constOnly = def.get(keyArgs, "constOnly", !1), wrap = mark.wrap, keyArgs2 = {
                        tag: pvc.extensionTag
                    }, isRealMark = mark instanceof pv_Mark, isRealMarkAndWrapOrConstOnly = isRealMark && (wrap || constOnly), processValue = function(v, m) {
                        if (null != v) {
                            var type = typeof v;
                            if ("object" === type) {
                                if ("svg" === m || "css" === m) {
                                    var v2 = mark.propertyValue(m);
                                    v2 && (v = def.copy(v2, v));
                                } else if (v instanceof Array) return v.map(function(vi) {
                                    return processValue(vi, m);
                                });
                            } else if (isRealMarkAndWrapOrConstOnly && "function" === type) {
                                if (constOnly) return;
                                "add" !== m && "call" !== m && (v = wrap.call(mark, v, m));
                            }
                        }
                        return v;
                    }, callMethod = function(mm, v) {
                        return v instanceof Array ? mm.apply(mark, v) : mm.call(mark, v);
                    };
                    component.forEach(function(v, m) {
                        if (mark.isLocked && mark.isLocked(m)) logOut && logOut.push(m + ": locked extension point!"); else if (mark.isIntercepted && mark.isIntercepted(m)) logOut && logOut.push(m + ":" + def.describe(v) + " (controlled)"); else {
                            logOut && logOut.push(m + ": " + def.describe(v));
                            v = processValue(v, m);
                            if (void 0 !== v) {
                                var mm = mark[m];
                                "function" == typeof mm ? isRealMark && mark.properties[m] ? mark.intercept(m, v, keyArgs2) : v instanceof Array ? v.forEach(function(vi) {
                                    callMethod(mm, vi);
                                }) : callMethod(mm, v) : mark[m] = v;
                            }
                        }
                    });
                    logOut && (logOut.length ? this.log("Applying Extension Points for: '" + id + "'\n	* " + logOut.join("\n	* ")) : def.debug >= 5 && this.log("No Extension Points for: '" + id + "'"));
                }
            } else def.debug >= 4 && this.log("Applying Extension Points for: '" + id + "' (target mark does not exist)");
        },
        _getExtension: function(id, prop) {
            var component;
            if (def.array.is(id)) for (var value, i = id.length - 1; i >= 0; ) {
                component = def.getOwn(this._components, id[i--]);
                if (component && void 0 !== (value = component.get(prop))) return value;
            } else {
                component = def.getOwn(this._components, id);
                if (component) return component.get(prop);
            }
        },
        _getComponentExtensions: function(id) {
            return def.getOwn(this._components, id);
        },
        _getConstantExtension: function(id, prop) {
            var value = this._getExtension(id, prop);
            return def.fun.is(value) ? void 0 : value;
        }
    });
    pvc.BaseChart.add({
        activeScene: function() {
            return this._activeScene || null;
        },
        _setActiveScene: function(to) {
            if (this.parent) return this.root._setActiveScene(to);
            to ? to.ownerScene && (to = to.ownerScene) : to = null;
            var from = this.activeScene();
            if (to === from) return !1;
            var ctx = new pvc.visual.Context(this.basePanel), ev = this._acting("active:change", function() {
                return this.chart._activeSceneChange(this);
            });
            ev.from = from;
            ev.to = to;
            ctx.event = ev;
            ev.trigger(ctx, []);
            return !0;
        },
        _activeSceneChange: function(ctx) {
            this.useTextMeasureCache(function() {
                var from = ctx.event.from, to = ctx.event.to;
                from && from._clearActive();
                (this._activeScene = to) && to._setActive(!0);
                !from || to && to.root === from.root || from.panel().renderInteractive();
                to && to.panel().renderInteractive();
            });
        },
        _on: function(name, hi, before) {
            "active:change" === name && (hi.role || hi.dims) && chart_activeSceneEvent_addFilter(name, hi);
        }
    });
    def("pvc.visual.MultiChart", pvc.visual.OptionsBase.extend({
        init: function(chart) {
            this.base(chart, "multiChart", 0, {
                byV1: !1,
                byNaked: !1
            });
        },
        options: {
            Max: {
                resolve: "_resolveFull",
                cast: def.number.toPositive,
                value: 1 / 0
            },
            ColumnsMax: {
                resolve: "_resolveFull",
                cast: def.number.toPositive,
                value: 3
            },
            SingleRowFillsHeight: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !0
            },
            SingleColFillsHeight: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !0
            },
            Overflow: {
                resolve: "_resolveFull",
                cast: pvc.parseMultiChartOverflow,
                value: "grow"
            }
        }
    }));
    def.type("pvc.MultiChartPanel", pvc.ContentPanel).add({
        createSmallCharts: function() {
            var count, chart = this.chart, multiInfo = chart._multiInfo;
            if (multiInfo && (count = multiInfo.count)) {
                var coordScopesByType, addChartToScope, indexChartByScope, coordRootAxesByScopeType = this._getCoordinatedRootAxesByScopeType();
                if (coordRootAxesByScopeType) {
                    coordScopesByType = {};
                    addChartToScope = function(childChart, scopeType, scopeIndex) {
                        var scopes = def.array.lazy(coordScopesByType, scopeType);
                        def.array.lazy(scopes, scopeIndex).push(childChart);
                    };
                    indexChartByScope = function(childChart) {
                        coordRootAxesByScopeType.row && addChartToScope(childChart, "row", childChart.smallRowIndex);
                        coordRootAxesByScopeType.column && addChartToScope(childChart, "column", childChart.smallColIndex);
                        coordRootAxesByScopeType.global && addChartToScope(childChart, "global", 0);
                    };
                }
                for (var childOptionsBase = this._buildSmallChartsBaseOptions(), ChildClass = chart.constructor, smallDatas = multiInfo.smallDatas, colCount = multiInfo.colCount, index = 0; count > index; index++) {
                    var smallData = smallDatas[index], colIndex = index % colCount, rowIndex = Math.floor(index / colCount), childOptions = def.set(Object.create(childOptionsBase), "smallColIndex", colIndex, "smallRowIndex", rowIndex, "title", smallData.absLabel, "data", smallData), smallChart = new ChildClass(childOptions);
                    if (coordRootAxesByScopeType) {
                        smallChart._createPhase1();
                        indexChartByScope(smallChart);
                    } else smallChart._create();
                }
                if (coordRootAxesByScopeType) {
                    var me = this;
                    def.eachOwn(coordRootAxesByScopeType, function(axes, scopeType) {
                        axes.forEach(function(axis) {
                            coordScopesByType[scopeType].forEach(function(scopeCharts) {
                                me._coordinateScopeAxes(axis.id, scopeCharts);
                            });
                        });
                    });
                    chart.children.forEach(function(childChart) {
                        childChart._createPhase2();
                    });
                }
                multiInfo.coordScopesByType = coordScopesByType;
            }
        },
        _getCoordinatedRootAxesByScopeType: function() {
            var hasCoordination = !1, rootAxesByScopeType = def.query(this.chart.axesList).multipleIndex(function(axis) {
                if ("discrete" !== axis.scaleType && axis.option.isDefined("DomainScope")) {
                    var scopeType = axis.option("DomainScope");
                    if ("cell" !== scopeType) {
                        hasCoordination = !0;
                        return scopeType;
                    }
                }
            });
            return hasCoordination ? rootAxesByScopeType : null;
        },
        _coordinateScopeAxes: function(axisId, scopeCharts) {
            var unionExtent = def.query(scopeCharts).select(function(childChart) {
                var scale = childChart.axes[axisId].scale;
                if (!scale.isNull) {
                    var domain = scale.domain();
                    return {
                        min: domain[0],
                        max: domain[1]
                    };
                }
            }).reduce(pvc.unionExtents, null);
            unionExtent && scopeCharts.forEach(function(childChart) {
                var axis = childChart.axes[axisId], scale = axis.scale;
                if (!scale.isNull) {
                    scale.domain(unionExtent.min, unionExtent.max);
                    axis.setScale(scale);
                }
            });
        },
        _buildSmallChartsBaseOptions: function() {
            var chart = this.chart, options = chart.options;
            return def.set(Object.create(options), "parent", chart, "legend", !1, "titleVisible", options.smallTitleVisible, "titleFont", options.smallTitleFont, "titlePosition", options.smallTitlePosition, "titleAlign", options.smallTitleAlign, "titleAlignTo", options.smallTitleAlignTo, "titleOffset", options.smallTitleOffset, "titleKeepInBounds", options.smallTitleKeepInBounds, "titleMargins", options.smallTitleMargins, "titlePaddings", options.smallTitlePaddings, "titleSize", options.smallTitleSize, "titleSizeMax", options.smallTitleSizeMax);
        },
        _calcLayout: function(layoutInfo) {
            var chart = this.chart, multiInfo = chart._multiInfo;
            if (multiInfo) {
                var multiOption = chart.multiOptions.option, smallOption = chart.smallOptions.option, clientSize = layoutInfo.clientSize, prevLayoutInfo = layoutInfo.previous, initialClientWidth = prevLayoutInfo ? prevLayoutInfo.initialClientWidth : clientSize.width, initialClientHeight = prevLayoutInfo ? prevLayoutInfo.initialClientHeight : clientSize.height, smallWidth = smallOption("Width");
                null != smallWidth && (smallWidth = pvc_PercentValue.resolve(smallWidth, initialClientWidth));
                var smallHeight = smallOption("Height");
                null != smallHeight && (smallHeight = pvc_PercentValue.resolve(smallHeight, initialClientHeight));
                var ar = smallOption("AspectRatio"), rowCount = multiInfo.rowCount, colCount = multiInfo.colCount;
                if (null == smallWidth) if (isFinite(multiInfo.colsMax)) smallWidth = clientSize.width / colCount; else {
                    null == smallHeight && (smallHeight = initialClientHeight);
                    smallWidth = ar * smallHeight;
                }
                null == smallHeight && (smallHeight = 1 === rowCount && multiOption("SingleRowFillsHeight") || 1 === colCount && multiOption("SingleColFillsHeight") ? initialClientHeight : smallWidth / ar);
                var finalClientWidth = smallWidth * colCount, finalClientHeight = smallHeight * rowCount;
                if (!chart._isMultiChartOverflowClipRetry) {
                    chart._isMultiChartOverflowClip = !1;
                    switch (multiOption("Overflow")) {
                      case "fit":
                        if (finalClientWidth > initialClientWidth) {
                            finalClientWidth = initialClientWidth;
                            smallWidth = finalClientWidth / colCount;
                        }
                        if (finalClientHeight > initialClientHeight) {
                            finalClientHeight = initialClientHeight;
                            smallHeight = finalClientHeight / rowCount;
                        }
                        break;

                      case "clip":
                        var colsMax = colCount, rowsMax = rowCount, clipW = finalClientWidth > initialClientWidth;
                        clipW && (colsMax = Math.floor(initialClientWidth / smallWidth));
                        var clipH = finalClientHeight > initialClientHeight;
                        clipH && (rowsMax = Math.floor(initialClientHeight / smallHeight));
                        if (clipH || clipW) {
                            chart._isMultiChartOverflowClip = !0;
                            chart._clippedMultiChartRowsMax = rowsMax;
                            chart._clippedMultiChartColsMax = colsMax;
                        }
                    }
                }
                def.set(layoutInfo, "initialClientWidth", initialClientWidth, "initialClientHeight", initialClientHeight, "width", smallWidth, "height", smallHeight);
                return {
                    width: finalClientWidth,
                    height: Math.max(clientSize.height, finalClientHeight)
                };
            }
        },
        _createCore: function(li) {
            var chart = this.chart;
            !chart._isMultiChartOverflowClip || def.assert("Overflow & Clip condition should be resolved.");
            var mi = chart._multiInfo;
            if (mi) {
                var smallOption = chart.smallOptions.option, smallMargins = smallOption("Margins"), smallPaddings = smallOption("Paddings");
                chart.children.forEach(function(childChart) {
                    childChart._setSmallLayout({
                        left: childChart.smallColIndex * li.width,
                        top: childChart.smallRowIndex * li.height,
                        width: li.width,
                        height: li.height,
                        margins: this._buildSmallMargins(childChart, smallMargins, mi),
                        paddings: smallPaddings
                    });
                }, this);
                var coordScopesByType = mi.coordScopesByType;
                coordScopesByType && chart._coordinateSmallChartsLayout(coordScopesByType);
                this.base(li);
            }
        },
        _buildSmallMargins: function(childChart, smallMargins, mi) {
            var C = mi.colCount - 1, R = mi.rowCount - 1, c = childChart.smallColIndex, r = childChart.smallRowIndex, margins = {};
            c > 0 && (margins.left = smallMargins.left);
            C > c && (margins.right = smallMargins.right);
            r > 0 && (margins.top = smallMargins.top);
            R > r && (margins.bottom = smallMargins.bottom);
            return margins;
        }
    });
    def("pvc.visual.SmallChart", pvc.visual.OptionsBase.extend({
        init: function(chart) {
            this.base(chart, "small", 0, {
                byV1: !1,
                byNaked: !1
            });
        },
        options: {
            Width: {
                resolve: "_resolveFull",
                cast: pvc_PercentValue.parse,
                value: null
            },
            Height: {
                resolve: "_resolveFull",
                cast: pvc_PercentValue.parse,
                value: null
            },
            AspectRatio: {
                resolve: "_resolveFull",
                cast: def.number.toPositive,
                getDefault: function() {
                    return this.chart instanceof pvc.PieChart ? 10 / 7 : 5 / 4;
                }
            },
            Margins: {
                resolve: "_resolveFull",
                cast: pvc_Sides.as,
                value: new pvc_Sides(new pvc_PercentValue(.02))
            },
            Paddings: {
                resolve: "_resolveFull",
                cast: pvc_Sides.as,
                value: 0
            }
        }
    }));
    pv_Mark.prototype.getSign = function() {
        return this.sign || sign_createBasic(this);
    };
    pv_Mark.prototype.getScene = function() {
        return this.getSign().scene();
    };
    pv_Mark.prototype.getContext = function() {
        return this.getSign().context();
    };
    pv_Mark.prototype.preBuildInstance = function(s) {
        var scene = s.data;
        scene instanceof pvc.visual.Scene && scene_renderId.call(scene, this.renderId());
    };
    def("pvc.visual.BasicSign", def.Object.extend({
        init: function(panel, pvMark) {
            this.chart = panel.chart;
            this.panel = panel;
            !pvMark.sign || def.assert("Mark already has an attached Sign.");
            this.pvMark = pvMark;
            pvMark.sign = this;
        },
        methods: {
            compatVersion: function() {
                return this.chart.compatVersion();
            },
            localProperty: function(name, type) {
                return this.pvMark.localProperty(name, type), this;
            },
            lock: function(pvName, value) {
                return this.lockMark(pvName, this._bindWhenFun(value, pvName));
            },
            optional: function(pvName, value, tag) {
                return this.optionalMark(pvName, this._bindWhenFun(value, pvName), tag);
            },
            lockMark: function(name, value) {
                return this.pvMark.lock(name, value), this;
            },
            optionalMark: function(name, value, tag) {
                return this.pvMark[name](value, tag), this;
            },
            delegate: function(dv, tag) {
                return this.pvMark.delegate(dv, tag);
            },
            delegateExtension: function(dv) {
                return this.pvMark.delegate(dv, pvc.extensionTag);
            },
            delegateNotExtension: function(dv) {
                return this.pvMark.delegateExcept(dv, pvc.extensionTag);
            },
            hasDelegate: function(tag) {
                return this.pvMark.hasDelegate(tag);
            },
            _createPropInterceptor: function(pvName, fun) {
                var me = this;
                return function() {
                    var sign = this.sign;
                    return sign && sign === me ? fun.apply(me, arguments) : me._getPvSceneProp(pvName, this.index);
                };
            },
            _getPvSceneProp: function(prop, defaultIndex) {
                var pvMark = this.pvMark, pvScenes = pvMark.scene;
                if (pvScenes) {
                    var index = pvMark.hasOwnProperty("index") ? pvMark.index : Math.min(defaultIndex, pvScenes.length - 1);
                    if (null != index) return pvScenes[index][prop];
                }
                throw def.error.operationInvalid("Cannot evaluate inherited property.");
            },
            _bindWhenFun: function(value, pvName) {
                if (def.fun.is(value)) {
                    var me = this;
                    return me._createPropInterceptor(pvName, function(scene) {
                        return value.call(me, scene);
                    });
                }
                return value;
            },
            _lockDynamic: function(pvName, method) {
                var me = this;
                return me.lockMark(pvName, me._createPropInterceptor(pvName, function(scene) {
                    return me[method].call(me, scene);
                }));
            },
            scene: function() {
                var instance = this.pvMark.instance(), scene = instance && instance.data;
                return scene instanceof pvc.visual.Scene ? scene : null;
            },
            instanceState: function(s) {
                return this.pvMark.instanceState(s);
            },
            context: function(scene, createIndep) {
                var state;
                return createIndep || !(state = this.instanceState()) ? this._createContext(scene) : state.cccContext || (state.cccContext = this._createContext(scene));
            },
            _createContext: function(scene) {
                return new pvc.visual.Context(this.panel, this.pvMark, scene);
            }
        }
    }));
    def("pvc.visual.Sign", pvc.visual.BasicSign.extend([ {
        init: function(panel, pvMark, keyArgs) {
            var me = this;
            me.base(panel, pvMark, keyArgs);
            me._ibits = panel.ibits();
            var extensionIds = def.get(keyArgs, "extensionId");
            null != extensionIds && (me.extensionAbsIds = def.array.to(panel._makeExtensionAbsId(extensionIds)));
            me.isActiveSeriesAware = def.get(keyArgs, "activeSeriesAware", !0);
            if (me.isActiveSeriesAware) {
                var roles = panel.visualRoles, seriesRole = roles && roles.series;
                seriesRole && seriesRole.isBound() || (me.isActiveSeriesAware = !1);
            }
            pvMark.wrapper(def.get(keyArgs, "wrapper") || me.createDefaultWrapper());
            def.get(keyArgs, "freeColor", !0) || me._bindProperty("fillStyle", "fillColor", "color")._bindProperty("strokeStyle", "strokeColor", "color");
        },
        postInit: function(panel, pvMark, keyArgs) {
            this._addInteractive(keyArgs);
            panel._addSign(this);
        },
        methods: [ pvc.visual.Interactive ],
        "type.methods": {
            properties: function(specs) {
                var spec;
                for (var n in specs) void 0 !== (spec = specs[n]) && (isNaN(+n) ? this.property(n) : def.string.is(spec) && this.property(spec));
                return this;
            },
            property: def.configurable(!1, function(name) {
                var upperName = def.firstUpperCase(name), baseName = "base" + upperName, defName = "default" + upperName, normalName = "normal" + upperName, interName = "interactive" + upperName, methods = {};
                methods[name] = function(scene, arg) {
                    this._finished = !1;
                    this._arg = arg;
                    var value = this[baseName](scene, arg);
                    if (null == value) return null;
                    if (this._finished) return value;
                    value = this[this.showsInteraction() && scene.anyInteraction() ? interName : normalName](scene, value, arg);
                    this._arg = null;
                    return value;
                };
                methods[baseName] = function() {
                    return this.delegateExtension();
                };
                methods[defName] = function() {};
                methods[normalName] = function(scene, value) {
                    return value;
                };
                methods[interName] = function(scene, value) {
                    return value;
                };
                return this.methods(methods);
            })
        }
    }, {
        properties: [ "color" ]
    }, {
        methods: {
            extensionAbsIds: null,
            _processedIbits: !1,
            ibits: function() {
                var ibits = this._ibits, pvMark = this.pvMark;
                if (!this._processedIbits) {
                    this._processedIbits = !0;
                    var extensionAbsIds = this.extensionAbsIds;
                    extensionAbsIds && extensionAbsIds.forEach(function(extensionAbsId) {
                        var v = this.panel._getExtensionAbs(extensionAbsId, "ibits");
                        null != v && pvMark.ibits(v);
                        v = this.panel._getExtensionAbs(extensionAbsId, "imask");
                        null != v && pvMark.imask(v);
                    }, this);
                }
                var imask0 = pvMark.imask();
                if (imask0) {
                    var ibitsON = imask0 & pvMark.ibits(), ibitsOFF = ~imask0 | ibitsON;
                    ibits |= ibitsON;
                    ibits &= ibitsOFF;
                }
                return ibits;
            },
            createDefaultWrapper: function() {
                var me = this;
                return function(f) {
                    return function(scene) {
                        return f.call(me.context(), scene);
                    };
                };
            },
            anyInteraction: function(scene) {
                return scene.anyInteraction();
            },
            finished: function(value) {
                this._finished = !0;
                return value;
            },
            applyExtensions: function() {
                if (!this._extended) {
                    this._extended = !0;
                    var extensionAbsIds = this.extensionAbsIds;
                    extensionAbsIds && extensionAbsIds.forEach(function(extensionAbsId) {
                        this.panel.extendAbs(this.pvMark, extensionAbsId);
                    }, this);
                }
                return this;
            },
            intercept: function(pvName, fun) {
                var interceptor = this._createPropInterceptor(pvName, fun);
                return this._intercept(pvName, interceptor);
            },
            lockDimensions: function() {
                this.pvMark.lock("left").lock("right").lock("top").lock("bottom").lock("width").lock("height");
                return this;
            },
            _extensionKeyArgs: {
                tag: pvc.extensionTag
            },
            _bindProperty: function(pvName, prop, realProp) {
                var me = this;
                realProp || (realProp = prop);
                var defaultPropName = "default" + def.firstUpperCase(realProp);
                if (def.fun.is(me[defaultPropName]) && !me.pvMark.hasDelegateValue(pvName, pvc.extensionTag)) {
                    var defaultPropMethod = function(scene) {
                        return me[defaultPropName](scene, me._arg);
                    };
                    me.pvMark.intercept(pvName, defaultPropMethod, me._extensionKeyArgs);
                }
                var mainPropMethod = this._createPropInterceptor(pvName, function(scene) {
                    return me[prop](scene);
                });
                return me._intercept(pvName, mainPropMethod);
            },
            _intercept: function(name, fun) {
                var mark = this.pvMark, extensionAbsIds = this.extensionAbsIds;
                extensionAbsIds && def.query(extensionAbsIds).select(function(extensionAbsId) {
                    return this.panel._getExtensionAbs(extensionAbsId, name);
                }, this).where(def.notUndef).each(function(extValue) {
                    extValue = mark.wrap(extValue, name);
                    mark.intercept(name, extValue, this._extensionKeyArgs);
                }, this);
                (mark._intercepted || (mark._intercepted = {}))[name] = !0;
                mark.intercept(name, fun);
                return this;
            },
            _addInteractive: function(ka) {
                var me = this, get = def.get;
                if (me.interactive()) {
                    var bits = me.ibits(), I = pvc.visual.Interactive;
                    get(ka, "noTooltip") && (bits &= ~I.ShowsTooltip);
                    get(ka, "noHover") && (bits &= ~I.Hoverable);
                    get(ka, "noClick") && (bits &= ~I.Clickable);
                    get(ka, "noDoubleClick") && (bits &= ~I.DoubleClickable);
                    if (get(ka, "noSelect")) bits &= ~I.SelectableAny; else if (this.selectable()) {
                        get(ka, "noClickSelect") && (bits &= ~I.SelectableByClick);
                        get(ka, "noRubberSelect") && (bits &= ~I.SelectableByRubberband);
                    }
                    if (me.showsInteraction()) {
                        get(ka, "showsInteraction") === !1 && (bits &= ~I.ShowsInteraction);
                        me.showsActivity() && get(ka, "showsActivity") === !1 && (bits &= ~I.ShowsActivity);
                        me.showsSelection() && get(ka, "showsSelection") === !1 && (bits &= ~I.ShowsSelection);
                    }
                    me._ibits = bits;
                }
                if (me.handlesEvents()) {
                    me.showsTooltip() && me._addPropTooltip(get(ka, "tooltipArgs"));
                    me.hoverable() && me._addPropHoverable();
                    me.handlesClickEvent() && me._addPropClick();
                    me.doubleClickable() && me._addPropDoubleClick();
                } else me.pvMark.events("none");
            },
            fillColor: function(scene) {
                return this.color(scene, "fill");
            },
            strokeColor: function(scene) {
                return this.color(scene, "stroke");
            },
            defaultColor: function(scene) {
                return this.scaleColor(scene);
            },
            scaleColor: function(scene) {
                return this.defaultColorSceneScale()(scene);
            },
            dimColor: function(color, type) {
                return "text" === type ? pvc.toGrayScale(color, -.75, null, null) : pvc.toGrayScale(color, -.3, null, null);
            },
            defaultColorSceneScale: function() {
                return def.lazy(this, "_defaultColorSceneScale", this._initDefColorScale, this);
            },
            _initDefColorScale: function() {
                var colorAxis = this.panel.axes.color;
                return colorAxis ? colorAxis.sceneScale({
                    sceneVarName: "color"
                }) : def.fun.constant(pvc.defaultColor);
            },
            mayShowActive: function(scene, noSeries) {
                if (!this.showsActivity()) return !1;
                var owner;
                return scene.isActive || ((owner = scene.ownerScene) && owner !== scene ? owner.isActive : !1) || !noSeries && this.isActiveSeriesAware && scene.isActiveSeries() || scene.isActiveDatum();
            },
            mayShowNotAmongSelected: function(scene) {
                return this.mayShowAnySelected(scene) && !scene.isSelected();
            },
            mayShowSelected: function(scene) {
                return this.showsSelection() && scene.isSelected();
            },
            mayShowAnySelected: function(scene) {
                return this.showsSelection() && scene.anySelected();
            },
            _addPropTooltip: function(ka) {
                if (!this.pvMark.tooltipOptions) {
                    var tipsy = this.panel._requireTipsy(), pointingOptions = this.chart._pointingOptions, tipOptions = def.create(this.chart._tooltipOptions, def.get(ka, "options"));
                    tipOptions.isLazy = def.get(ka, "isLazy", !0);
                    var tooltipFormatter = def.get(ka, "buildTooltip") || this._getTooltipFormatter(tipOptions);
                    if (tooltipFormatter) {
                        var isNear = "near" === pointingOptions.mode, tipsyEvent = def.get(ka, "tipsyEvent") || (isNear ? "point" : "mouseover");
                        this.pvMark.localProperty("tooltip").tooltip(this._createTooltipProp(tooltipFormatter, tipOptions.isLazy)).title(def.fun.constant("")).ensureEvents().event(tipsyEvent, tipsy).tooltipOptions = tipOptions;
                    }
                }
            },
            _getTooltipFormatter: function(tipOptions) {
                return this.panel._getTooltipFormatter(tipOptions);
            },
            _isTooltipEnabled: function() {
                return this.panel._isTooltipEnabled();
            },
            _createTooltipProp: function(tooltipFormatter, isLazy) {
                var formatTooltip, me = this;
                formatTooltip = isLazy ? function(scene) {
                    var tooltip, context = me.context(scene, !0);
                    return function() {
                        if (context) {
                            tooltip = tooltipFormatter(context);
                            context = null;
                        }
                        return tooltip;
                    };
                } : function(scene) {
                    var context = me.context(scene);
                    return tooltipFormatter(context);
                };
                return function(scene) {
                    return scene && scene.showsTooltip() ? formatTooltip(scene) : void 0;
                };
            },
            _addPropHoverable: function() {
                var onEvent, offEvent, pointingOptions = this.chart._pointingOptions, panel = this.panel;
                if ("near" === pointingOptions.mode) {
                    onEvent = "point";
                    offEvent = "unpoint";
                } else {
                    onEvent = "mouseover";
                    offEvent = "mouseout";
                }
                this.pvMark.ensureEvents().event(onEvent, function(scene) {
                    !scene.hoverable() || panel.selectingByRubberband() || panel.animating() || scene.setActive(!0);
                }).event(offEvent, function(scene) {
                    !scene.hoverable() || panel.selectingByRubberband() || panel.animating() || pv.event && pv.event.isPointSwitch || scene.clearActive();
                });
                this.pvMark._hasHoverable = !0;
            },
            _ignoreClicks: 0,
            _propCursorClick: function(s) {
                var ibits = this.ibits() & s.ibits(), I = pvc.visual.Interactive;
                return ibits & I.HandlesClickEvent || ibits & I.DoubleClickable ? "pointer" : null;
            },
            _addPropClick: function() {
                var me = this;
                me.pvMark.cursor(me._propCursorClick.bind(me)).ensureEvents().event("click", me._handleClick.bind(me));
            },
            _addPropDoubleClick: function() {
                var me = this;
                me.pvMark.cursor(me._propCursorClick.bind(me)).ensureEvents().event("dblclick", me._handleDoubleClick.bind(me));
            },
            _handleClick: function() {
                var me = this, pvMark = me.pvMark, pvInstance = pvMark.instance(), scene = pvInstance.data, wait = me.doubleClickable() && scene.doubleClickable();
                if (wait) {
                    var pvScene = pvMark.scene, pvIndex = pvMark.index, pvEvent = pv.event;
                    window.setTimeout(function() {
                        if (me._ignoreClicks) me._ignoreClicks--; else try {
                            pv.event = pvEvent;
                            pvMark.context(pvScene, pvIndex, function() {
                                me._handleClickCore();
                            });
                        } catch (ex) {
                            pv.error(ex);
                        } finally {
                            delete pv.event;
                        }
                    }, me.chart.options.doubleClickMaxDelay || 300);
                } else me._ignoreClicks ? me._ignoreClicks-- : me._handleClickCore();
            },
            _handleClickCore: function() {
                this._onClick(this.context());
            },
            _handleDoubleClick: function() {
                var me = this, scene = me.scene();
                if (scene && scene.doubleClickable()) {
                    me._ignoreClicks = 2;
                    me._onDoubleClick(me.context(scene));
                }
            },
            _onClick: function(context) {
                context.click();
            },
            _onDoubleClick: function(context) {
                context.doubleClick();
            }
        }
    } ]));
    pvc.finished = function(v) {
        return void 0 === v ? function() {
            return this.finished(this.delegate());
        } : def.fun.is(v) ? function() {
            return (this.finished ? this : this.getSign()).finished(v.apply(this, arguments));
        } : function() {
            return (this.finished ? this : this.getSign()).finished(v);
        };
    };
    def("pvc.visual.Panel", pvc.visual.Sign.extend({
        init: function(panel, protoMark, keyArgs) {
            var pvPanel = def.get(keyArgs, "panel");
            if (!pvPanel) {
                var pvPanelType = def.get(keyArgs, "panelType") || pv.Panel;
                pvPanel = protoMark.add(pvPanelType);
            }
            this.base(panel, pvPanel, keyArgs);
        },
        methods: {
            _addInteractive: function(keyArgs) {
                var t = !0;
                keyArgs = def.setDefaults(keyArgs, "noSelect", t, "noHover", t, "noTooltip", t, "noClick", t, "noDoubleClick", t);
                this.base(keyArgs);
            }
        }
    }));
    def("pvc.visual.Label", pvc.visual.Sign.extend({
        init: function(panel, protoMark, keyArgs) {
            var pvMark = protoMark.add(pv.Label);
            this.base(panel, pvMark, keyArgs);
        },
        methods: {
            _addInteractive: function(keyArgs) {
                var t = !0;
                keyArgs = def.setDefaults(keyArgs, "noSelect", t, "noHover", t, "noTooltip", t, "noClick", t, "noDoubleClick", t, "showsInteraction", !1);
                this.base(keyArgs);
            },
            defaultColor: def.fun.constant(pv.Color.names.black)
        }
    }));
    var DEFAULT_BG_COLOR = pv.Color.names.white;
    def("pvc.visual.ValueLabel", pvc.visual.Label.extend({
        init: function(panel, anchorMark, keyArgs) {
            this.valuesFont = def.get(keyArgs, "valuesFont") || panel.valuesFont;
            this.valuesMask = def.get(keyArgs, "valuesMask") || panel.valuesMask;
            this.valuesOptimizeLegibility = def.get(keyArgs, "valuesOptimizeLegibility", panel.valuesOptimizeLegibility);
            this.valuesOverflow = def.get(keyArgs, "valuesOverflow", panel.valuesOverflow);
            this.hideOverflowed = "hide" === this.valuesOverflow;
            this.trimOverflowed = !this.hideOverflowed && "trim" === this.valuesOverflow;
            this.hideOrTrimOverflowed = this.hideOverflowed || this.trimOverflowed;
            var protoMark = def.get(keyArgs, "noAnchor", !1) ? anchorMark : anchorMark.anchor(panel.valuesAnchor);
            keyArgs && null == keyArgs.extensionId && (keyArgs.extensionId = "label");
            this.base(panel, protoMark, keyArgs);
            this.pvMark.font(this.valuesFont);
            this._bindProperty("text", "text")._bindProperty("textStyle", "textColor", "color").intercept("visible", this.visible);
        },
        properties: [ "text", "textStyle" ],
        type: {
            methods: {
                maybeCreate: function(panel, anchorMark, keyArgs) {
                    return panel.valuesVisible && panel.valuesMask ? new pvc.visual.ValueLabel(panel, anchorMark, keyArgs) : null;
                },
                isNeeded: function(panel) {
                    return panel.valuesVisible && !!panel.valuesMask;
                }
            }
        },
        methods: {
            _addInteractive: function(keyArgs) {
                keyArgs = def.setDefaults(keyArgs, "showsInteraction", !0, "noSelect", !0, "noTooltip", !0, "noClick", !0, "noDoubleClick", !0, "noHover", !0);
                this.base(keyArgs);
            },
            visible: function(scene) {
                var anchoredToMark = this.getAnchoredToMark();
                if (anchoredToMark && !anchoredToMark.visible()) return !1;
                if (!this.hideOrTrimOverflowed) return this.delegate(!0);
                var visible;
                if (this.hasDelegate(pvc.extensionTag)) {
                    visible = this.delegateExtension();
                    if (null != visible) return visible;
                }
                visible = this.delegateNotExtension();
                if (visible === !1) return !1;
                if (scene.isActive && this.showsActivity()) return !0;
                var fitInfo = this.textFitInfo(scene);
                return !(fitInfo && fitInfo.hide);
            },
            textFitInfo: function(scene) {
                var state = scene.renderState, fitInfo = state.textFitInfo;
                return void 0 !== fitInfo ? fitInfo : state.textFitInfo = this.calcTextFitInfo(scene, this._evalBaseText()) || null;
            },
            calcTextFitInfo: function(scene, text) {
                return null;
            },
            _evalBaseText: function() {
                var pvLabel = this.pvMark, pdelegate = pvLabel.binds.properties.text.proto;
                return pvLabel.evalInPropertyContext(this.baseText.bind(this), pdelegate);
            },
            baseText: function(scene) {
                var state = scene.renderState, text = state.baseText;
                return void 0 !== text ? text : this.base(scene);
            },
            defaultText: function(scene) {
                return scene.format(this.valuesMask);
            },
            normalText: function(scene, text) {
                var fitInfo;
                return this.trimOverflowed && (fitInfo = this.textFitInfo(scene)) ? this.trimText(scene, text, fitInfo) : text;
            },
            interactiveText: function(scene, text) {
                var fitInfo;
                return !this.trimOverflowed || scene.isActive && this.showsActivity() || !(fitInfo = this.textFitInfo(scene)) ? text : this.trimText(scene, text, fitInfo);
            },
            trimText: function(scene, text, fitInfo) {
                var twMax = fitInfo && fitInfo.widthMax;
                return null != twMax ? pvc.text.trimToWidthB(twMax, text, this.pvMark.font(), "..") : text;
            },
            textColor: function(scene) {
                return this.color(scene, "text");
            },
            backgroundColor: function(scene, type) {
                var state = this.instanceState();
                if (!state) return this.calcBackgroundColor(scene, type);
                var cache = def.lazy(state, "cccBgColorCache");
                return def.getOwn(cache, type) || (cache[type] = this.calcBackgroundColor(scene, type));
            },
            calcBackgroundColor: function(scene, type) {
                var anchoredToMark = this.getAnchoredToMark();
                if (anchoredToMark) {
                    var fillColor = anchoredToMark.fillStyle();
                    if (fillColor && fillColor !== DEFAULT_BG_COLOR && this.isAnchoredInside(scene, anchoredToMark)) return fillColor;
                }
                return DEFAULT_BG_COLOR;
            },
            getAnchoredToMark: function() {
                return this.pvMark.target || this.pvMark.parent;
            },
            isAnchoredInside: function(scene, anchoredToMark) {
                if (!anchoredToMark && !(anchoredToMark = this.getAnchoredToMark())) return !1;
                var p, pvLabel = this.pvMark, text = pvLabel.text(), m = pv.Text.measure(text, pvLabel.font()), l = pvLabel.left(), t = pvLabel.top();
                if (null == l) {
                    p = pvLabel.parent;
                    l = p.width() - (pvLabel.right() || 0);
                }
                if (null == t) {
                    p || (p = pvLabel.parent);
                    t = p.height() - (pvLabel.bottom() || 0);
                }
                var anchoredToShape, labelCenter = pv.Label.getPolygon(m.width, m.height, pvLabel.textAlign(), pvLabel.textBaseline(), pvLabel.textAngle(), pvLabel.textMargin()).center().plus(l, t);
                anchoredToShape = anchoredToMark === pvLabel.parent ? new pv.Shape.Rect(0, 0, anchoredToMark.width(), anchoredToMark.height()) : anchoredToMark.getShape(anchoredToMark.scene, anchoredToMark.index);
                return anchoredToShape.containsPoint(labelCenter);
            },
            maybeOptimizeColorLegibility: function(scene, color, type) {
                if (this.valuesOptimizeLegibility) {
                    var bgColor = this.backgroundColor(scene, type);
                    return bgColor && bgColor !== DEFAULT_BG_COLOR && bgColor.isDark() === color.isDark() ? color.complementary().alpha(.9) : color;
                }
                return color;
            },
            normalColor: function(scene, color, type) {
                return this.maybeOptimizeColorLegibility(scene, color, type);
            },
            interactiveColor: function(scene, color, type) {
                return !this.mayShowActive(scene) && this.mayShowNotAmongSelected(scene) ? this.dimColor(color, type) : this.maybeOptimizeColorLegibility(scene, color, type);
            }
        }
    }));
    def("pvc.visual.Dot", pvc.visual.Sign.extend({
        init: function(panel, parentMark, keyArgs) {
            var pvMark = parentMark.add(pv.Dot), protoMark = def.get(keyArgs, "proto");
            protoMark && pvMark.extend(protoMark);
            keyArgs = def.setDefaults(keyArgs, "freeColor", !1);
            this.base(panel, pvMark, keyArgs);
            if (!def.get(keyArgs, "freePosition", !1)) {
                var a_left = panel.isOrientationVertical() ? "left" : "bottom", a_bottom = panel.anchorOrtho(a_left);
                this._lockDynamic(a_left, "x")._lockDynamic(a_bottom, "y");
            }
            this.pvMark.shapeRadius(function() {
                return Math.sqrt(this.sign.defaultSize());
            });
            this._bindProperty("shape", "shape")._bindProperty("shapeRadius", "radius")._bindProperty("shapeSize", "size");
            this.optional("strokeDasharray", void 0).optional("lineWidth", 1.5);
        },
        properties: [ "size", "shape" ],
        methods: {
            y: def.fun.constant(0),
            x: def.fun.constant(0),
            radius: function() {
                var state = this.instanceState();
                this._finished = !1;
                state.cccRadius = this.delegateExtension();
                state.cccRadiusFinished = this._finished;
                return null;
            },
            baseSize: function(scene) {
                this.pvMark.shapeRadius();
                var state = this.instanceState(), radius = state.cccRadius;
                if (null != radius) {
                    this._finished = state.cccRadiusFinished;
                    return def.sqr(radius);
                }
                return this.base(scene);
            },
            defaultSize: def.fun.constant(12),
            interactiveSize: function(scene, size) {
                return this.mayShowActive(scene, !0) ? 2 * Math.max(size, 5) : size;
            },
            interactiveColor: function(scene, color, type) {
                if (this.mayShowActive(scene, !0)) {
                    if ("stroke" === type) return color.brighter(1);
                } else if (this.mayShowNotAmongSelected(scene)) {
                    if (this.mayShowActive(scene)) return color.alpha(.8);
                    switch (type) {
                      case "fill":
                        return this.dimColor(color, type);

                      case "stroke":
                        return color.alpha(.45);
                    }
                }
                return this.base(scene, color, type);
            }
        }
    }));
    def("pvc.visual.DotSizeColor", pvc.visual.Dot.extend({
        init: function(panel, parentMark, keyArgs) {
            this.base(panel, parentMark, keyArgs);
            this._bindProperty("lineWidth", "strokeWidth").intercept("visible", function(scene) {
                if (!this.canShow(scene)) return !1;
                var visible = this.delegateExtension();
                return null == visible ? this.defaultVisible(scene) : visible;
            });
            this._initColor();
            this._initSize();
            if (this.isSizeBound) {
                var sizeAxis = panel.axes.size;
                if (sizeAxis.scaleUsesAbs()) {
                    this.isSizeAbs = !0;
                    var baseSceneDefColor = this._sceneDefColor;
                    this._sceneDefColor = function(scene, type) {
                        return "stroke" === type && scene.vars.size.value < 0 ? pv.Color.names.black : baseSceneDefColor.call(this, scene, type);
                    };
                    this.pvMark.lineCap("round").strokeDasharray(function(scene) {
                        return scene.vars.size.value < 0 ? "dash" : null;
                    });
                }
            }
        },
        properties: [ "strokeWidth" ],
        methods: {
            isColorBound: !1,
            isColorDiscrete: !1,
            isSizeBound: !1,
            isSizeAbs: !1,
            canShow: function(scene) {
                return !scene.isIntermediate;
            },
            defaultVisible: function(scene) {
                return !scene.isNull && (!this.isSizeBound && !this.isColorBound || this.isSizeBound && null != scene.vars.size.value || this.isColorBound && (this.isColorDiscrete || null != scene.vars.color.value));
            },
            _initColor: function() {
                var colorConstant, sceneColorScale, panel = this.panel, colorRole = panel.visualRoles.color;
                if (colorRole) {
                    this.isColorDiscrete = colorRole.isDiscrete();
                    var colorAxis = panel.axes.color;
                    if (colorRole.isBound()) {
                        this.isColorBound = !0;
                        sceneColorScale = colorAxis.sceneScale({
                            sceneVarName: "color"
                        });
                    } else colorAxis && (colorConstant = colorAxis.option("Unbound"));
                }
                this._sceneDefColor = sceneColorScale || def.fun.constant(colorConstant || pvc.defaultColor);
            },
            _initSize: function() {
                var sceneSizeScale, sceneShapeScale, panel = this.panel, plot = panel.plot, shape = plot.option("Shape"), nullSizeShape = plot.option("NullShape"), sizeRole = panel.visualRoles.size;
                if (sizeRole) {
                    var sizeAxis = panel.axes.size, sizeScale = sizeAxis && sizeAxis.scale, isSizeBound = !!sizeScale && sizeRole.isBound();
                    if (isSizeBound) {
                        this.isSizeBound = !0;
                        var missingSize = sizeScale.min + .05 * (sizeScale.max - sizeScale.min);
                        this.nullSizeShapeHasStrokeOnly = "cross" === nullSizeShape;
                        sceneShapeScale = function(scene) {
                            return null != scene.vars.size.value ? shape : nullSizeShape;
                        };
                        sceneSizeScale = function(scene) {
                            var sizeValue = scene.vars.size.value;
                            return null != sizeValue ? sizeScale(sizeValue) : nullSizeShape ? missingSize : 0;
                        };
                    }
                }
                if (!sceneSizeScale) {
                    sceneShapeScale = def.fun.constant(shape);
                    sceneSizeScale = function(scene) {
                        return this.base(scene);
                    };
                }
                this._sceneDefSize = sceneSizeScale;
                this._sceneDefShape = sceneShapeScale;
            },
            defaultColor: function(scene, type) {
                return this._sceneDefColor(scene, type);
            },
            normalColor: function(scene, color, type) {
                return "stroke" === type ? color.darker() : this.base(scene, color, type);
            },
            interactiveColor: function(scene, color, type) {
                if (this.mayShowActive(scene, !0)) switch (type) {
                  case "fill":
                    return this.isSizeBound ? color.alpha(.75) : color;

                  case "stroke":
                    return color.darker();
                } else if (this.showsSelection()) {
                    var isSelected = scene.isSelected(), notAmongSelected = !isSelected && scene.anySelected();
                    if (notAmongSelected) {
                        if (this.mayShowActive(scene)) return color.alpha(.8);
                        switch (type) {
                          case "fill":
                            return this.dimColor(color, type);

                          case "stroke":
                            return color.alpha(.45);
                        }
                    }
                    if (isSelected && pvc_colorIsGray(color)) return "stroke" === type ? color.darker(3) : color.darker(2);
                }
                return "stroke" === type ? color.darker() : color;
            },
            defaultSize: function(scene) {
                return this._sceneDefSize(scene);
            },
            defaultShape: function(scene) {
                return this._sceneDefShape(scene);
            },
            interactiveSize: function(scene, size) {
                if (!this.mayShowActive(scene, !0)) return size;
                var radius = Math.sqrt(size), radiusInc = Math.max(1, Math.min(1.1 * radius, 2));
                return def.sqr(radius + radiusInc);
            },
            defaultStrokeWidth: function(scene) {
                return this.nullSizeShapeHasStrokeOnly && null == scene.vars.size.value ? 1.8 : 1;
            },
            interactiveStrokeWidth: function(scene, width) {
                return this.mayShowActive(scene, !0) ? 2 * width : this.mayShowSelected(scene) ? 1.5 * width : width;
            }
        }
    }));
    pv.LineInterm = function() {
        pv.Line.call(this);
    };
    pv.LineInterm.prototype = pv.extend(pv.Line);
    pv.LineInterm.prototype.getNearestInstanceToMouse = function(scene, eventIndex) {
        var mouseIndex = pv.Line.prototype.getNearestInstanceToMouse.call(this, scene, eventIndex), s = scene[mouseIndex];
        s && s.data && s.data.isIntermediate && mouseIndex + 1 < scene.length && mouseIndex++;
        return mouseIndex;
    };
    def("pvc.visual.Line", pvc.visual.Sign.extend({
        init: function(panel, protoMark, keyArgs) {
            var pvMark = protoMark.add(pv.LineInterm);
            this.base(panel, pvMark, keyArgs);
            this.lock("segmented", "smart").lock("antialias", !0);
            if (!def.get(keyArgs, "freePosition", !1)) {
                var basePosProp = panel.isOrientationVertical() ? "left" : "bottom", orthoPosProp = panel.anchorOrtho(basePosProp);
                this._lockDynamic(orthoPosProp, "y")._lockDynamic(basePosProp, "x");
            }
            this._bindProperty("strokeStyle", "strokeColor", "color")._bindProperty("lineWidth", "strokeWidth");
        },
        properties: [ "strokeWidth" ],
        methods: {
            _addInteractive: function(keyArgs) {
                keyArgs = def.setDefaults(keyArgs, "noTooltip", !0);
                this.base(keyArgs);
            },
            y: def.fun.constant(0),
            x: def.fun.constant(0),
            defaultStrokeWidth: def.fun.constant(1.5),
            interactiveStrokeWidth: function(scene, strokeWidth) {
                return this.mayShowActive(scene) ? 2.5 * Math.max(1, strokeWidth) : strokeWidth;
            },
            interactiveColor: function(scene, color, type) {
                return this.mayShowNotAmongSelected(scene) ? this.mayShowActive(scene) ? pv.Color.names.darkgray.darker().darker() : this.dimColor(color, type) : this.base(scene, color, type);
            }
        }
    }));
    pv.AreaInterm = function() {
        pv.Area.call(this);
    };
    pv.AreaInterm.prototype = pv.extend(pv.Area);
    pv.AreaInterm.prototype.getNearestInstanceToMouse = function(scene, eventIndex) {
        var mouseIndex = pv.Area.prototype.getNearestInstanceToMouse.call(this, scene, eventIndex), s = scene[mouseIndex];
        s && s.data && s.data.isIntermediate && mouseIndex + 1 < scene.length && mouseIndex++;
        return mouseIndex;
    };
    def("pvc.visual.Area", pvc.visual.Sign.extend({
        init: function(panel, protoMark, keyArgs) {
            var pvMark = protoMark.add(pv.AreaInterm);
            keyArgs || (keyArgs = {});
            keyArgs.freeColor = !0;
            this.base(panel, pvMark, keyArgs);
            var antialias = def.get(keyArgs, "antialias", !0);
            this.lock("segmented", "smart").lock("antialias", antialias);
            if (!def.get(keyArgs, "freePosition", !1)) {
                var basePosProp = panel.isOrientationVertical() ? "left" : "bottom", orthoPosProp = panel.anchorOrtho(basePosProp), orthoLenProp = panel.anchorOrthoLength(orthoPosProp);
                this._lockDynamic(basePosProp, "x")._lockDynamic(orthoPosProp, "y")._lockDynamic(orthoLenProp, "dy");
            }
            this._bindProperty("fillStyle", "fillColor", "color");
            this.lock("strokeStyle", null).lock("lineWidth", 0);
        },
        methods: {
            _addInteractive: function(keyArgs) {
                keyArgs = def.setDefaults(keyArgs, "noTooltip", !0);
                this.base(keyArgs);
            },
            y: def.fun.constant(0),
            x: def.fun.constant(0),
            dy: def.fun.constant(0),
            interactiveColor: function(scene, color, type) {
                return "fill" === type && this.mayShowNotAmongSelected(scene) ? this.dimColor(color, type) : this.base(scene, color, type);
            }
        }
    }));
    def("pvc.visual.Bar", pvc.visual.Sign.extend({
        init: function(panel, protoMark, keyArgs) {
            var pvMark = protoMark.add(pv.Bar);
            keyArgs = def.setDefaults(keyArgs, "freeColor", !1);
            this.base(panel, pvMark, keyArgs);
            this.normalStroke = def.get(keyArgs, "normalStroke", !1);
            this._bindProperty("lineWidth", "strokeWidth");
        },
        properties: [ "strokeWidth" ],
        methods: {
            normalColor: function(scene, color, type) {
                return "stroke" !== type || this.normalStroke ? color : null;
            },
            interactiveColor: function(scene, color, type) {
                if ("stroke" === type) {
                    if (this.mayShowActive(scene, !0)) return color.brighter(1.3).alpha(.7);
                    if (!this.normalStroke) return null;
                    if (this.mayShowNotAmongSelected(scene)) return this.mayShowActive(scene) ? pv.Color.names.darkgray.darker().darker() : this.dimColor(color, type);
                    if (this.mayShowActive(scene)) return color.brighter(1).alpha(.7);
                } else if ("fill" === type) {
                    if (this.mayShowActive(scene, !0)) return color.brighter(.2).alpha(.8);
                    if (this.mayShowNotAmongSelected(scene)) return this.mayShowActive(scene) ? pv.Color.names.darkgray.darker(2).alpha(.8) : this.dimColor(color, type);
                    if (this.mayShowActive(scene)) return color.brighter(.2).alpha(.8);
                }
                return this.base(scene, color, type);
            },
            defaultStrokeWidth: function() {
                return .5;
            },
            interactiveStrokeWidth: function(scene, strokeWidth) {
                return this.mayShowActive(scene, !0) ? 1.3 * Math.max(1, strokeWidth) : strokeWidth;
            }
        }
    }));
    def("pvc.visual.Rule", pvc.visual.Sign.extend({
        init: function(panel, parentMark, keyArgs) {
            var pvMark = parentMark.add(pv.Rule);
            pvMark.pointingRadiusMax(2);
            var protoMark = def.get(keyArgs, "proto");
            protoMark && pvMark.extend(protoMark);
            this.base(panel, pvMark, keyArgs);
            def.get(keyArgs, "freeStyle") || this._bindProperty("strokeStyle", "strokeColor", "color")._bindProperty("lineWidth", "strokeWidth");
        },
        properties: [ "strokeWidth" ],
        methods: {
            _addInteractive: function(keyArgs) {
                var t = !0;
                keyArgs = def.setDefaults(keyArgs, "noHover", t, "noSelect", t, "noTooltip", t, "noClick", t, "noDoubleClick", t, "showsInteraction", !1);
                this.base(keyArgs);
            },
            defaultStrokeWidth: def.fun.constant(1),
            interactiveStrokeWidth: function(scene, strokeWidth) {
                return this.mayShowActive(scene, !0) ? 2.2 * Math.max(1, strokeWidth) : strokeWidth;
            },
            interactiveColor: function(scene, color, type) {
                return scene.datum && !this.mayShowActive(scene, !0) && this.mayShowNotAmongSelected(scene) ? this.dimColor(color, type) : this.base(scene, color, type);
            }
        }
    }));
    def.space("pvc.visual").discreteBandsLayout = discreteBandsLayout;
    var pvc_CartesianAxis = def("pvc.visual.CartesianAxis", pvc_Axis.extend({
        init: function(chart, type, index, keyArgs) {
            var options = chart.options;
            this.orientation = pvc_CartesianAxis.getOrientation(type, options.orientation);
            this.orientedId = pvc_CartesianAxis.getOrientedId(this.orientation, index);
            chart._allowV1SecondAxis && 1 === index && (this.v1SecondOrientedId = "second" + this.orientation.toUpperCase());
            this.base(chart, type, index, keyArgs);
            chart.axes[this.orientedId] = this;
            this.v1SecondOrientedId && (chart.axes[this.v1SecondOrientedId] = this);
            this.extensionPrefixes = getExtensionPrefixes.call(this);
        },
        methods: {
            bind: function(dataCells) {
                this.base(dataCells);
                this._syncExtensionPrefixes();
                return this;
            },
            _syncExtensionPrefixes: function() {
                this.extensionPrefixes = getExtensionPrefixes.call(this);
            },
            _buildState: function() {
                return {
                    ratio: this.option("PreserveRatio") ? this._getCurrentRatio() : null
                };
            },
            _getCurrentRatio: function() {
                var ratio = this._state.ratio || this.option("Ratio");
                if (null == ratio) {
                    var scale = this.scale;
                    if (scale) {
                        var rangeLength = scale.size, domain = scale.domain(), domainLength = domain[1] - domain[0];
                        ratio = rangeLength / domainLength;
                    }
                }
                return ratio;
            },
            setScale: function(scale, noWrap) {
                var hadPrevScale = !!this.scale;
                this.base(scale, noWrap);
                if (hadPrevScale) {
                    delete this.domain;
                    delete this.domainNice;
                    delete this.ticks;
                    delete this._roundingOverflow;
                }
                if (scale && !scale.isNull && "discrete" !== this.scaleType) {
                    this.domain = scale.domain();
                    this.domain.minLocked = !!scale.minLocked;
                    this.domain.maxLocked = !!scale.maxLocked;
                    var tickFormatter = this.option("TickFormatter");
                    tickFormatter && scale.tickFormatter(tickFormatter);
                    var roundMode = this.option("DomainRoundMode");
                    "nice" === roundMode && "numeric" === scale.type && scale.nice();
                    this.domainNice = scale.domain();
                }
                return this;
            },
            setTicks: function(ticks) {
                var scale = this.scale;
                scale && !scale.isNull || def.fail.operationInvalid("Scale must be set and non-null.");
                this.ticks = ticks;
                delete this._roundingOverflow;
                if ("discrete" !== scale.type && "tick" === this.option("DomainRoundMode")) {
                    delete this._roundingOverflow;
                    var tickCount = ticks ? ticks.length : 0;
                    if (tickCount >= 2) {
                        var wasDomainAligned = this._adjustDomain(scale, ticks[0], ticks[tickCount - 1]);
                        wasDomainAligned && this._removeTicks(ticks);
                    } else this._adjustDomain(scale);
                }
            },
            _adjustDomain: function(scale, minIfLower, maxIfGreater) {
                var domainNice = this.domainNice;
                if (!domainNice) return !1;
                var dOrigMin = +domainNice[0], dOrigMax = +domainNice[1];
                if (null == dOrigMin || null == dOrigMax) return !1;
                var dmin = null != minIfLower ? Math.min(+minIfLower, dOrigMin) : dOrigMin, dmax = null != maxIfGreater ? Math.max(+maxIfGreater, dOrigMax) : dOrigMax, wasDomainAligned = !1, axisRangeLength = scale.size;
                if (axisRangeLength) {
                    var ratio = this._state.ratio || this.option("Ratio");
                    if (null != ratio) {
                        wasDomainAligned = !0;
                        var axisDomainLength = axisRangeLength / ratio;
                        switch (this.option("DomainAlign")) {
                          case "min":
                            dmax = dmin + axisDomainLength;
                            break;

                          case "max":
                            dmin = dmax - axisDomainLength;
                            break;

                          case "center":
                            var center = dmax - (dmax - dmin) / 2;
                            dmax = center + axisDomainLength / 2;
                            dmin = center - axisDomainLength / 2;
                        }
                    }
                }
                if (pv.floatEqual(dmin, dOrigMin) && pv.floatEqual(dmax, dOrigMax)) return !1;
                var dim = this.chart.data.owner.dimensions(this.role.grouping.lastDimensionName());
                dmin = dim.read(dmin).value;
                dmax = dim.read(dmax).value;
                scale.domain(dmin, dmax);
                return wasDomainAligned;
            },
            _removeTicks: function(ticks) {
                var opts = this.option;
                if (this._state.ratio || opts("Ratio")) {
                    var ti = ticks[0], tf = ticks[ticks.length - 1], adjustedDomain = this.scale.domain(), li = this.chart.axesPanels[this.id]._layoutInfo;
                    if (ticks !== li.ticks) throw new Error("Assertion failed.");
                    var removeTick = function(tick) {
                        var index = ticks.indexOf(tick);
                        ticks.splice(index, 1);
                        li.ticksText.splice(index, 1);
                    };
                    switch (opts("DomainAlign")) {
                      case "min":
                        tf > adjustedDomain[1] && removeTick(tf);
                        break;

                      case "max":
                        ti < adjustedDomain[0] && removeTick(ti);
                        break;

                      default:
                        tf > adjustedDomain[1] && removeTick(tf);
                        ti < adjustedDomain[0] && removeTick(ti);
                    }
                }
            },
            setScaleRange: function(size) {
                var rangeInfo = this.getScaleRangeInfo();
                rangeInfo && (null != rangeInfo.value ? size = rangeInfo.value : null != rangeInfo.min && (size = Math.max(Math.min(size, rangeInfo.max), rangeInfo.min)));
                var scale = this.scale;
                scale.min = 0;
                scale.max = size;
                scale.size = size;
                delete this._roundingOverflow;
                if ("discrete" === scale.type) rangeInfo && ("abs" === rangeInfo.mode ? scale.splitBandedCenterAbs(scale.min, scale.max, rangeInfo.band, rangeInfo.space) : scale.splitBandedCenter(scale.min, scale.max, rangeInfo.ratio)); else {
                    scale.range(scale.min, scale.max);
                    this._adjustDomain(scale);
                }
                return scale;
            },
            getScaleRangeInfo: function() {
                if (!this.isDiscrete()) return null;
                var layoutInfo = pvc.visual.discreteBandsLayout(this.domainItemCount(), this.option("BandSize"), this.option("BandSizeMin") || 0, this.option("BandSizeMax"), this.option("BandSpacing"), this.option("BandSpacingMin") || 0, this.option("BandSpacingMax"), this.option("BandSizeRatio"));
                return layoutInfo && this.chart.parent ? {
                    mode: "rel",
                    min: 0,
                    max: 1 / 0,
                    ratio: layoutInfo.ratio
                } : layoutInfo;
            },
            getRoundingOverflow: function() {
                var roundingOverflow = this._roundingOverflow;
                if (!roundingOverflow) {
                    roundingOverflow = this._roundingOverflow = {
                        begin: 0,
                        end: 0,
                        beginLocked: !1,
                        endLocked: !1
                    };
                    var scale = this.scale, domainOriginal = this.domain;
                    if (scale && domainOriginal && !scale.isNull && "discrete" !== scale.type) {
                        roundingOverflow.beginLocked = domainOriginal.minLocked;
                        roundingOverflow.endLocked = domainOriginal.maxLocked;
                        if (null != scale.size && "tick" === this.option("DomainRoundMode")) {
                            var domainRounded = scale.domain(), roundingBegin = domainOriginal[0] - domainRounded[0], roundingEnd = domainRounded[1] - domainOriginal[1], eps = pv.epsilon;
                            if (roundingBegin > eps || roundingEnd > eps) {
                                var zeroPos = scale(0);
                                roundingBegin > eps && (roundingOverflow.begin = scale(roundingBegin) - zeroPos);
                                roundingEnd > eps && (roundingOverflow.end = scale(roundingEnd) - zeroPos);
                            }
                        }
                    }
                }
                return roundingOverflow;
            },
            calcContinuousTicks: function(tickCountMax) {
                return this.scale.ticks(this.desiredTickCount(), {
                    roundInside: "tick" !== this.option("DomainRoundMode"),
                    tickCountMax: tickCountMax,
                    precision: this.option("TickUnit"),
                    precisionMin: this.tickUnitMinEf(),
                    precisionMax: this.tickUnitMaxEf()
                });
            },
            desiredTickCount: function() {
                var desiredTickCount = this.option("DesiredTickCount"), isDate = "timeSeries" === this.scaleType;
                return null == desiredTickCount ? isDate ? null : 1 / 0 : isDate && isFinite(desiredTickCount) && desiredTickCount > 10 ? 10 : desiredTickCount;
            },
            tickUnitMinEf: function() {
                var unitMin = this.option("TickUnitMin"), expoMin = this.option("TickExponentMin");
                null == unitMin && (unitMin = 0);
                null != expoMin && isFinite(expoMin) && (unitMin = Math.max(unitMin, Math.pow(10, Math.floor(expoMin))));
                return unitMin;
            },
            tickUnitMaxEf: function() {
                var unitMax = this.option("TickUnitMax"), expoMax = this.option("TickExponentMax");
                null == unitMax && (unitMax = 1 / 0);
                null != expoMax && isFinite(expoMax) && (unitMax = Math.min(unitMax, 9.999 * Math.pow(10, Math.floor(expoMax))));
                return unitMax;
            },
            _registerResolversNormal: function(rs, keyArgs) {
                this.chart.compatVersion() <= 1 && rs.push(this._resolveByV1OnlyLogic);
                rs.push(this._resolveByOptionId, this._resolveByOrientedId);
                1 === this.index && rs.push(this._resolveByV1OptionId);
                rs.push(this._resolveByScaleType, this._resolveByCommonId);
            },
            _resolveByOrientedId: pvc.options.specify(function(optionInfo) {
                return this._chartOption(this.orientedId + "Axis" + optionInfo.name);
            }),
            _resolveByV1OptionId: pvc.options.specify(function(optionInfo) {
                return this._chartOption("secondAxis" + optionInfo.name);
            }),
            _resolveByScaleType: pvc.options.specify(function(optionInfo) {
                var st = this.scaleType;
                if (st) {
                    var name = optionInfo.name, value = this._chartOption(st + "Axis" + name);
                    void 0 === value && "discrete" !== st && (value = this._chartOption("continuousAxis" + name));
                    return value;
                }
            }),
            _resolveByCommonId: pvc.options.specify(function(optionInfo) {
                return this._chartOption("axis" + optionInfo.name);
            })
        }
    }));
    pvc_CartesianAxis.getOrientation = function(type, chartOrientation) {
        return "base" === type == ("vertical" === chartOrientation) ? "x" : "y";
    };
    pvc_CartesianAxis.getOrientedId = function(orientation, index) {
        return 0 === index ? orientation : orientation + (index + 1);
    };
    var cartAxis_fixedMinMaxSpec = {
        resolve: "_resolveFull",
        data: {
            resolveV1: function(optionInfo) {
                this.index || "ortho" !== this.type || this._specifyChartOption(optionInfo, this.id + optionInfo.name);
                return !0;
            }
        }
    }, cartAxis_normalV1Data = {
        resolveV1: function(optionInfo) {
            if (this.index) {
                if (this._resolveByV1OptionId(optionInfo)) return !0;
            } else if (this._resolveByOrientedId(optionInfo)) return !0;
            this._resolveDefault(optionInfo);
            return !0;
        }
    }, defaultPosition = pvc.options.defaultValue(function(optionInfo) {
        if (!this.typeIndex) return "x" === this.orientation ? "bottom" : "left";
        var firstAxis = this.chart.axesByType[this.type].first, position = firstAxis.option("Position");
        return pvc.BasePanel.oppositeAnchor[position];
    });
    pvc_CartesianAxis.options({
        Visible: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    if (this.index <= 1) {
                        var v1OptionId = 0 === this.index ? def.firstUpperCase(this.orientation) : "Second";
                        this._specifyChartOption(optionInfo, "show" + v1OptionId + "Scale");
                    }
                    return !0;
                }
            },
            cast: Boolean,
            value: !0
        },
        Composite: {
            resolve: function(optionInfo) {
                return this.index > 0 ? (optionInfo.specify(!1), !0) : this._resolveFull(optionInfo);
            },
            data: {
                resolveV1: function(optionInfo) {
                    return this._specifyChartOption(optionInfo, "useCompositeAxis"), !0;
                }
            },
            cast: Boolean,
            value: !1
        },
        Size: {
            resolve: "_resolveFull",
            data: cartAxis_normalV1Data,
            cast: cartAxis_castSize
        },
        SizeMax: {
            resolve: "_resolveFull",
            cast: cartAxis_castSize
        },
        Position: {
            resolve: "_resolveFull",
            data: {
                resolveV1: cartAxis_normalV1Data.resolveV1,
                resolveDefault: defaultPosition
            },
            cast: pvc_castAxisPosition
        },
        FixedMin: cartAxis_fixedMinMaxSpec,
        FixedMax: cartAxis_fixedMinMaxSpec,
        FixedLength: {
            resolve: "_resolveFull",
            cast: pvc.parseAxisFixedLength
        },
        Ratio: {
            resolve: "_resolveFull",
            cast: pvc.parseAxisRatio
        },
        DomainAlign: {
            resolve: "_resolveFull",
            cast: pvc.parseDomainAlign,
            value: "center"
        },
        PreserveRatio: {
            resolve: "_resolveFull",
            cast: Boolean,
            value: !1
        },
        OriginIsZero: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    switch (this.index) {
                      case 0:
                        this._specifyChartOption(optionInfo, "originIsZero");
                        break;

                      case 1:
                        this.chart._allowV1SecondAxis && this._specifyChartOption(optionInfo, "secondAxisOriginIsZero");
                    }
                    return !0;
                }
            },
            cast: Boolean,
            value: !0
        },
        DomainScope: {
            resolve: "_resolveFull",
            cast: pvc_castDomainScope,
            value: "global"
        },
        Offset: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    switch (this.index) {
                      case 0:
                        this._specifyChartOption(optionInfo, "axisOffset");
                        break;

                      case 1:
                        this.chart._allowV1SecondAxis && this._specifyChartOption(optionInfo, "secondAxisOffset");
                    }
                    return !0;
                }
            },
            cast: def.number.to
        },
        BandSizeRatio: {
            resolve: function(optionInfo) {
                return this._resolveFull(optionInfo) || this._specifyChartOption(optionInfo, "panelSizeRatio");
            },
            cast: function(v) {
                v = def.number.toNonNegative(v);
                null != v && v > 1 && (v = null);
                return v;
            },
            getDefault: function() {
                return this.chart._defaultAxisBandSizeRatio;
            }
        },
        BandSize: {
            resolve: "_resolveFull",
            cast: def.number.toNonNegative
        },
        BandSpacing: {
            resolve: "_resolveFull",
            cast: def.number.toNonNegative
        },
        BandSizeMin: {
            resolve: "_resolveFull",
            cast: def.number.toNonNegative
        },
        BandSpacingMin: {
            resolve: "_resolveFull",
            cast: def.number.toNonNegative
        },
        BandSizeMax: {
            resolve: "_resolveFull",
            cast: def.number.toNonNegative
        },
        BandSpacingMax: {
            resolve: "_resolveFull",
            cast: def.number.toNonNegative
        },
        LabelSpacingMin: {
            resolve: "_resolveFull",
            cast: def.number.to
        },
        LabelRotationDirection: {
            resolve: "_resolveFull",
            cast: pvc.parseLabelRotationDirection,
            value: "clockwise"
        },
        LabelDesiredAngles: {
            resolve: "_resolveFull",
            cast: cartAxis_labelDesiredAngles,
            value: []
        },
        OverlappedLabelsMode: {
            resolve: "_resolveFull",
            cast: pvc.parseOverlappedLabelsMode,
            value: "hide"
        },
        Grid: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    this.index || this._specifyChartOption(optionInfo, this.orientation + "AxisFullGrid");
                    return !0;
                }
            },
            cast: Boolean,
            value: !1
        },
        GridCrossesMargin: {
            resolve: "_resolveFull",
            cast: Boolean,
            value: !0
        },
        EndLine: {
            resolve: "_resolveFull",
            cast: Boolean
        },
        ZeroLine: {
            resolve: "_resolveFull",
            cast: Boolean,
            value: !0
        },
        RuleCrossesMargin: {
            resolve: "_resolveFull",
            cast: Boolean,
            value: !0
        },
        Ticks: {
            resolve: "_resolveFull",
            cast: Boolean
        },
        DesiredTickCount: {
            resolve: "_resolveFull",
            data: {
                resolveV1: cartAxis_normalV1Data.resolveV1,
                resolveDefault: function(optionInfo) {
                    return this.chart.compatVersion() <= 1 ? (optionInfo.defaultValue(5), !0) : void 0;
                }
            },
            cast: def.number.toPositive
        },
        MinorTicks: {
            resolve: "_resolveFull",
            data: cartAxis_normalV1Data,
            cast: Boolean,
            value: !0
        },
        TickFormatter: {
            resolve: "_resolveFull",
            cast: def.fun.as
        },
        DomainRoundMode: {
            resolve: "_resolveFull",
            data: {
                resolveV1: cartAxis_normalV1Data.resolveV1,
                resolveDefault: function(optionInfo) {
                    return this.chart.compatVersion() <= 1 ? (optionInfo.defaultValue("none"), !0) : void 0;
                }
            },
            cast: pvc.parseDomainRoundingMode,
            value: "tick"
        },
        TickExponentMin: {
            resolve: "_resolveFull",
            cast: def.number.to
        },
        TickExponentMax: {
            resolve: "_resolveFull",
            cast: def.number.to
        },
        TickUnit: {
            resolve: "_resolveFull"
        },
        TickUnitMin: {
            resolve: "_resolveFull"
        },
        TickUnitMax: {
            resolve: "_resolveFull"
        },
        Title: {
            resolve: "_resolveFull",
            cast: String
        },
        TitleVisible: {
            resolve: "_resolveFull",
            cast: Boolean,
            getDefault: function() {
                return !def.empty(this.option("Title"));
            }
        },
        TitleSize: {
            resolve: "_resolveFull",
            cast: cartAxis_castTitleSize
        },
        TitleSizeMax: {
            resolve: "_resolveFull",
            cast: cartAxis_castTitleSize
        },
        TitleFont: {
            resolve: "_resolveFull",
            cast: String
        },
        TitleMargins: {
            resolve: "_resolveFull",
            cast: pvc_Sides.as
        },
        TitlePaddings: {
            resolve: "_resolveFull",
            cast: pvc_Sides.as
        },
        TitleAlign: {
            resolve: "_resolveFull",
            cast: function(align) {
                var position = this.option("Position");
                return pvc.parseAlign(position, align);
            }
        },
        Font: {
            resolve: "_resolveFull",
            cast: String
        },
        ClickAction: {
            resolve: "_resolveFull",
            data: cartAxis_normalV1Data
        },
        DoubleClickAction: {
            resolve: "_resolveFull",
            data: cartAxis_normalV1Data
        },
        TooltipEnabled: {
            resolve: "_resolveFull",
            cast: Boolean,
            value: !0
        },
        TooltipFormat: {
            resolve: "_resolveFull",
            cast: def.fun.as,
            value: null
        },
        TooltipAutoContent: {
            resolve: "_resolveFull",
            cast: pvc.parseTooltipAutoContent,
            value: "value"
        }
    });
    def.type("pvc.visual.CartesianAxisRootScene", pvc.visual.Scene);
    def.type("pvc.visual.CartesianAxisTickScene", pvc.visual.Scene).init(function(parent, keyArgs) {
        this.base(parent, keyArgs);
        this.vars.tick = new pvc_ValueLabelVar(def.get(keyArgs, "tick"), def.get(keyArgs, "tickLabel"), def.get(keyArgs, "tickRaw"));
        def.get(keyArgs, "isHidden") && (this.isHidden = !0);
    }).add({
        isHidden: !1
    });
    def.type("pvc.AxisPanel", pvc.BasePanel).init(function(chart, parent, axis, options) {
        options = def.create(options, {
            anchor: axis.option("Position")
        });
        var anchor = options.anchor || this.anchor;
        null == options.paddings && (options.paddings = pvc_Sides.filterAnchor(anchor, chart._axisOffsetPct));
        this.axis = axis;
        this.base(chart, parent, options);
        this.roleName = axis.role.name;
        this.isDiscrete = axis.role.isDiscrete();
        this._extensionPrefix = axis.extensionPrefixes;
        null == this.labelSpacingMin && (this.labelSpacingMin = this.isDiscrete ? .25 : 1.1);
        null == this.showTicks && (this.showTicks = !this.isDiscrete);
        if (void 0 === options.font) {
            var extFont = this._getConstantExtension("label", "font");
            extFont && (this.font = extFont);
        }
        if (void 0 === options.tickLength) {
            var tickLength = +this._getConstantExtension("ticks", this.anchorOrthoLength(anchor));
            !isNaN(tickLength) && isFinite(tickLength) && (this.tickLength = tickLength);
        }
        var showLabels = this._getConstantExtension("label", "visible");
        null == showLabels || showLabels || (this.showLabels = !1);
    }).add({
        pvRule: null,
        pvTicks: null,
        pvLabel: null,
        pvRuleGrid: null,
        pvScale: null,
        isDiscrete: !1,
        roleName: null,
        axis: null,
        anchor: "bottom",
        tickLength: 6,
        scale: null,
        ruleCrossesMargin: !0,
        font: "9px sans-serif",
        labelSpacingMin: null,
        showMinorTicks: !0,
        showTicks: null,
        showLabels: !0,
        hiddenLabelText: "·",
        _isScaleSetup: !1,
        _createLogId: function() {
            return this.base() + " - " + this.axis.id;
        },
        getTicks: function() {
            return this._layoutInfo && this._layoutInfo.ticks;
        },
        _calcLayout: function(layoutInfo) {
            var scale = this.axis.scale, clientSize = layoutInfo.clientSize;
            if (!this._isScaleSetup) {
                this.pvScale = scale;
                this.scale = scale;
                this.extend(scale, "scale");
                this._isScaleSetup = !0;
            }
            if (scale.isNull) layoutInfo.axisSize = layoutInfo.restrictions.clientSize[this.anchorOrthoLength()] || 0; else {
                var a_length = this.anchorLength(), rangeInfo = this.axis.getScaleRangeInfo();
                rangeInfo && (null != rangeInfo.value ? clientSize[a_length] = rangeInfo.value : null != rangeInfo.min && (clientSize[a_length] = Math.max(Math.min(clientSize[a_length], rangeInfo.max), rangeInfo.min)));
                this.axis.setScaleRange(clientSize[a_length]);
                this._calcLayoutCore(layoutInfo);
            }
            return this.createAnchoredSize(layoutInfo.axisSize, clientSize);
        },
        _calcLayoutCore: function(layoutInfo) {
            var axisSize = layoutInfo.restrictions.clientSize[this.anchorOrthoLength()];
            layoutInfo.axisSize = axisSize;
            if (this.isDiscrete && this.useCompositeAxis) null == layoutInfo.axisSize && (layoutInfo.axisSize = 50); else {
                this._readTextProperties(layoutInfo);
                this._calcTicks();
                if ("discrete" === this.scale.type) {
                    var overlappedLabelsMode = this.axis.option("OverlappedLabelsMode"), labelRotationDirection = "counterclockwise" === this.axis.option("LabelRotationDirection") ? -1 : 1, labelDesiredAngles = this.axis.option("LabelDesiredAngles"), distanceBetweenTicks = this.scale.range().step, labelSpacingMin = this.labelSpacingMin, fontPxWidth = pv.Text.measureWidth("x", this.font), anchor = this.anchor;
                    pvc.AxisPanel._calcDiscreteOverlapSettings(overlappedLabelsMode, labelRotationDirection, labelDesiredAngles, distanceBetweenTicks, labelSpacingMin, fontPxWidth, anchor, layoutInfo);
                }
                this._calcAxisSizeFromLabelBBox(layoutInfo);
                null == layoutInfo.axisSize && (layoutInfo.axisSize = layoutInfo.requiredAxisSize);
                this._calcMaxTextLengthThatFits();
                this._calcContentOverflowOptional();
            }
        },
        _readTextProperties: function(layoutInfo) {
            var textAngle = this._getExtension("label", "textAngle");
            layoutInfo.textAngle = def.number.to(textAngle, 0);
            layoutInfo.textMargin = def.number.to(this._getExtension("label", "textMargin"), 3);
            var align = this._getExtension("label", "textAlign");
            "string" != typeof align && (align = this.isAnchorTopOrBottom() ? "center" : "left" == this.anchor ? "right" : "left");
            layoutInfo.textAlign = align;
            var baseline = this._getExtension("label", "textBaseline");
            if ("string" != typeof baseline) switch (this.anchor) {
              case "right":
              case "left":
              case "center":
                baseline = "middle";
                break;

              case "bottom":
                baseline = "top";
                break;

              default:
                baseline = "bottom";
            }
            layoutInfo.textBaseline = baseline;
        },
        _calcAxisSizeFromLabelBBox: function(layoutInfo) {
            var maxLabelBBox = this.showLabels ? this._calcMaxLabelBBox() : null, length = maxLabelBBox ? this._getLabelBBoxQuadrantLength(maxLabelBBox, this.anchor) : 0, axisSize = (this.showTicks || this.showLabels ? this.tickLength : 0) + length, angle = maxLabelBBox ? maxLabelBBox.sourceAngle : 0;
            !this.showLabels || 0 === angle && this.isAnchorTopOrBottom() || (axisSize += this.tickLength);
            layoutInfo.requiredAxisSize = Math.max(1, axisSize, def.number.as(this._getConstantExtension("rule", "linewidth"), 1) / 2);
        },
        _getLabelBBoxQuadrantLength: function(labelBBox, quadrantSide) {
            var length;
            switch (quadrantSide) {
              case "left":
                length = -labelBBox.x;
                break;

              case "right":
                length = labelBBox.x2;
                break;

              case "top":
                length = -labelBBox.y;
                break;

              case "bottom":
                length = labelBBox.y2;
            }
            return Math.max(length, 0);
        },
        _calcContentOverflowOptional: function() {
            this._layoutInfo.restrictions.canChange ? this.showLabels && this._calcContentOverflowOptionalFromLabelBBox() : def.debug >= 2 && this.log.warn("Layout cannot change. Skipping calculation of optional content overflow.");
        },
        _calcContentOverflowOptionalFromLabelBBox: function() {
            var contentOverflowOptional = null, me = this, li = me._layoutInfo, ticks = li.ticks;
            if (ticks.length) {
                var ticksBBoxes = li.ticksBBoxes || this._calcTicksLabelBBoxes(li), isTopOrBottom = me.isAnchorTopOrBottom(), begSide = isTopOrBottom ? "left" : "bottom", endSide = isTopOrBottom ? "right" : "top", scale = me.scale, isDiscrete = "discrete" === scale.type, includeModulo = isDiscrete ? li.tickVisibilityStep : 1, clientLength = li.clientSize[me.anchorLength()], evalLabelSideOverflow = function(labelBBox, side, isBegin, index) {
                    if (!(includeModulo > 1 && index % includeModulo !== 0)) {
                        var sideLength = me._getLabelBBoxQuadrantLength(labelBBox, side);
                        if (sideLength > 1) {
                            var anchorPosition = scale(isDiscrete ? ticks[index].value : ticks[index]), sidePosition = isBegin ? anchorPosition - sideLength : anchorPosition + sideLength, sideOverflow = Math.max(0, isBegin ? -sidePosition : sidePosition - clientLength);
                            if (sideOverflow > 1) {
                                sideOverflow -= li.paddings[side] || 0;
                                if (sideOverflow > 1) {
                                    isDiscrete && (sideOverflow *= 1.05);
                                    if (contentOverflowOptional) {
                                        var currrOverflowPadding = contentOverflowOptional[side];
                                        (null == currrOverflowPadding || sideOverflow > currrOverflowPadding) && (contentOverflowOptional[side] = sideOverflow);
                                    } else contentOverflowOptional = def.set({}, side, sideOverflow);
                                }
                            }
                        }
                    }
                };
                ticksBBoxes.forEach(function(labelBBox, index) {
                    evalLabelSideOverflow(labelBBox, begSide, !0, index);
                    evalLabelSideOverflow(labelBBox, endSide, !1, index);
                });
                def.debug >= 6 && contentOverflowOptional && me.log("ContentOverflowOptional = " + def.describe(contentOverflowOptional));
            }
            li.contentOverflowOptional = contentOverflowOptional;
        },
        _calcMaxTextLengthThatFits: function() {
            var layoutInfo = this._layoutInfo;
            if (!this.showLabels || this.compatVersion() <= 1) layoutInfo.maxTextWidth = null; else {
                var availableClientLength = layoutInfo.clientSize[this.anchorOrthoLength()], efSize = Math.min(layoutInfo.axisSize, availableClientLength);
                if (efSize >= layoutInfo.requiredAxisSize - this.tickLength) layoutInfo.maxTextWidth = null; else {
                    var mostOrthoDistantPoint, parallelDirection, maxLabelBBox = layoutInfo.maxLabelBBox, maxOrthoLength = efSize - this.tickLength;
                    switch (this.anchor) {
                      case "left":
                        parallelDirection = pv.vector(0, 1);
                        mostOrthoDistantPoint = pv.vector(-maxOrthoLength, 0);
                        break;

                      case "right":
                        parallelDirection = pv.vector(0, 1);
                        mostOrthoDistantPoint = pv.vector(maxOrthoLength, 0);
                        break;

                      case "top":
                        parallelDirection = pv.vector(1, 0);
                        mostOrthoDistantPoint = pv.vector(0, -maxOrthoLength);
                        break;

                      case "bottom":
                        parallelDirection = pv.vector(1, 0);
                        mostOrthoDistantPoint = pv.vector(0, maxOrthoLength);
                    }
                    var orthoOutwardsDir = mostOrthoDistantPoint.norm(), corners = maxLabelBBox.source.points(), botL = corners[0], botR = corners[1], topR = corners[2], topL = corners[3], topLRSideDir = topR.minus(topL), botLRSideDir = botR.minus(botL), intersect = pv.SvgScene.lineIntersect, botI = intersect(mostOrthoDistantPoint, parallelDirection, botL, botLRSideDir), topI = intersect(mostOrthoDistantPoint, parallelDirection, topL, topLRSideDir), sideLRWidth = maxLabelBBox.sourceTextWidth, maxTextWidth = sideLRWidth, botLI = botI.minus(botL), botLILen = botLI.length();
                    sideLRWidth >= botLILen && botLI.dot(topLRSideDir) >= 0 && (maxTextWidth = botL.dot(orthoOutwardsDir) < botR.dot(orthoOutwardsDir) ? botLILen : botI.minus(botR).length());
                    var topLI = topI.minus(topL), topLILen = topLI.length();
                    sideLRWidth >= topLILen && topLI.dot(topLRSideDir) >= 0 && (maxTextWidth = topL.dot(orthoOutwardsDir) < topR.dot(orthoOutwardsDir) ? Math.min(maxTextWidth, topLILen) : Math.min(maxTextWidth, topI.minus(topR).length()));
                    if ("center" === maxLabelBBox.sourceAlign) {
                        var cutWidth = sideLRWidth - maxTextWidth;
                        maxTextWidth -= cutWidth;
                    }
                    layoutInfo.maxTextWidth = maxTextWidth;
                    layoutInfo.maxLabelBBox = this._calcLabelBBox(maxTextWidth);
                    def.debug >= 3 && this.log("Trimming labels' text at length " + maxTextWidth.toFixed(2) + "px maxOrthoLength=" + maxOrthoLength.toFixed(2) + "px");
                }
            }
        },
        _calcTicks: function() {
            var layoutInfo = this._layoutInfo;
            layoutInfo.textHeight = 4 * pv.Text.fontHeight(this.font) / 5;
            layoutInfo.maxTextWidth = null;
            this.axis.setTicks(null);
            switch (this.scale.type) {
              case "discrete":
                this._calcDiscreteTicks();
                break;

              case "timeSeries":
              case "numeric":
                this._calcContinuousTicks();
                break;

              default:
                throw def.error.operationInvalid("Undefined axis scale type");
            }
            this.axis.setTicks(this._layoutInfo.ticks);
            null == layoutInfo.maxTextWidth && this._calcTicksTextLength(layoutInfo);
        },
        _calcDiscreteTicks: function() {
            var axis = this.axis, layoutInfo = this._layoutInfo;
            layoutInfo.ticks = axis.domainItems();
            var format, grouping = axis.role.grouping, tickFormatter = axis.option("TickFormatter");
            if (this.getCompatFlag("discreteTimeSeriesTickFormat") && grouping.isSingleDimension && grouping.lastDimensionValueType() === Date) {
                var domainValues = axis.domainValues(), extent = def.query(domainValues).range();
                if (extent && extent.min !== extent.max) {
                    var scale = new pv.Scale.linear(extent.min, extent.max), ticks = scale.ticks();
                    if (tickFormatter) {
                        def.copyProps(domainValues, ticks, [ "step", "base", "mult", "format" ]);
                        format = function(child, index) {
                            return tickFormatter.call(domainValues, domainValues[index], domainValues.step, index);
                        };
                    } else format = function(child, index) {
                        return ticks.format(domainValues[index]);
                    };
                } else if (tickFormatter) {
                    var dimFormatter = grouping.lastDimensionType().formatter();
                    domainValues.step = domainValues.base = pvc.time.intervals.d;
                    domainValues.mult = 1;
                    domainValues.format = function(value) {
                        return dimFormatter(value);
                    };
                    format = function(child, index) {
                        return tickFormatter.call(domainValues, domainValues[index], domainValues.step, index);
                    };
                }
            } else tickFormatter && (format = function(child) {
                return tickFormatter(child.value, child.absLabel);
            });
            format || (format = function(child) {
                return child.absLabel;
            });
            layoutInfo.ticksText = layoutInfo.ticks.map(format);
            this._clearTicksTextDeps(layoutInfo);
        },
        _clearTicksTextDeps: function(ticksInfo) {
            ticksInfo.maxTextWidth = ticksInfo.ticksTextLength = ticksInfo.ticksBBoxes = null;
        },
        _calcContinuousTicks: function() {
            var doLog = def.debug >= 7;
            doLog && this.log.group("_calcContinuousTicks");
            var layoutInfo = this._layoutInfo;
            layoutInfo.ticks = layoutInfo.ticksText = null;
            var bandSizeMin = this._calcContinuousBandSizeMin(), clientLength = layoutInfo.clientSize[this.anchorLength()], tickCountMax = this._calcContinuousTickCountMax(bandSizeMin, clientLength), ticks = layoutInfo.ticks, roundOutside = "tick" === this.axis.option("DomainRoundMode");
            if (!ticks || ticks.length > (roundOutside ? 3 : 1) && ticks.length > tickCountMax) {
                this._calcContinuousTicksValue(layoutInfo, tickCountMax);
                this._calcContinuousTicksText(layoutInfo);
                ticks = layoutInfo.ticks;
            }
            if (roundOutside) {
                var L = ticks.length;
                if (L > tickCountMax && 2 >= tickCountMax && 3 >= L && (L - 1) * bandSizeMin > clientLength) if (3 === L) {
                    layoutInfo.ticksText[0] = "";
                    layoutInfo.ticksText[2] = "";
                    layoutInfo.maxTextWidth = null;
                } else if (2 === L) {
                    layoutInfo.ticksText[ticks[1] ? 1 : 0] = "";
                    layoutInfo.maxTextWidth = null;
                }
            }
            def.debug >= 5 && this.log.info("RESULT count=" + ticks.length + " step=" + ticks.step);
            doLog && this.log.groupEnd();
        },
        _calcContinuousTicksValue: function(ticksInfo, tickCountMax) {
            ticksInfo.ticks = this.axis.calcContinuousTicks(tickCountMax);
            if (def.debug > 4) {
                this.log("DOMAIN: " + def.describe(this.scale.domain()));
                this.log("TICKS:  " + def.describe(ticksInfo.ticks));
            }
        },
        _calcContinuousTicksText: function(ticksInfo) {
            var ticksText = ticksInfo.ticksText = ticksInfo.ticks.map(function(tick, index) {
                return this.scale.tickFormat(tick, index);
            }, this);
            this._clearTicksTextDeps(ticksInfo);
            return ticksText;
        },
        _calcContinuousTickCountMax: function(Bmin, TS) {
            if (!Bmin && this.showLabels) return 1;
            var li = this._layoutInfo, E = li.textHeight * Math.max(0, this.labelSpacingMin);
            return 1 + Math.floor(TS / (Bmin + E));
        },
        _calcContinuousBandSizeMin: function() {
            var domain = this.scale.domain(), span = Math.abs(domain[1] - domain[0]);
            if (1e-10 > span || !isFinite(span)) return 0;
            var li = this._layoutInfo;
            if (this.isAnchorTopOrBottom()) {
                this._calcContinuousTicksValue(li);
                this._calcTicksTextLength(li);
                return Math.max(li.maxTextWidth, li.textHeight);
            }
            return li.textHeight;
        },
        _calcTicksTextLength: function(ticksInfo) {
            var max = 0, font = this.font, ticksText = ticksInfo.ticksText || this._calcContinuousTicksText(ticksInfo), ticksTextLength = ticksInfo.ticksTextLength = ticksText.map(function(text) {
                var len = pv.Text.measureWidth(text, font);
                len > max && (max = len);
                return len;
            });
            ticksInfo.maxTextWidth = ticksInfo.maxTextWidthReal = max;
            ticksInfo.ticksBBoxes = null;
            ticksInfo.maxLabelBBox = null;
            return ticksTextLength;
        },
        _calcTicksLabelBBoxes: function(ticksInfo) {
            var me = this, li = me._layoutInfo, ticksTextLength = ticksInfo.ticksTextLength || me._calcTicksTextLength(ticksInfo), maxLen = li.maxTextWidth || li.maxTextWidthReal, maxBBox = null;
            ticksInfo.ticksBBoxes = ticksTextLength.map(function(len) {
                var labelBBox = me._calcLabelBBox(Math.min(len, maxLen));
                maxBBox || len !== maxLen || (maxBBox = labelBBox);
                return labelBBox;
            }, me);
            li.maxLabelBBox = maxBBox;
            return ticksInfo.ticksBBoxes;
        },
        _calcMaxLabelBBox: function() {
            var li = this._layoutInfo;
            li.ticksTextLength || this._calcTicksTextLength(li);
            return li.maxLabelBBox = this._calcLabelBBox(li.maxTextWidth);
        },
        _calcLabelBBox: function(textWidth) {
            var li = this._layoutInfo;
            return pvc.text.getLabelBBox(textWidth, li.textHeight, li.textAlign, li.textBaseline, li.textAngle, li.textMargin);
        },
        _createCore: function() {
            if (!this.scale.isNull) {
                var clientSize = this._layoutInfo.clientSize, paddings = this._layoutInfo.paddings, begin_a = this.anchorOrtho(), end_a = this.anchorOpposite(begin_a), size_a = this.anchorOrthoLength(begin_a), rMin = this.ruleCrossesMargin ? -paddings[begin_a] : 0, rMax = clientSize[size_a] + (this.ruleCrossesMargin ? paddings[end_a] : 0), rSize = rMax - rMin;
                this._rSize = rSize;
                var rootScene = this._getRootScene();
                this.pvRule = new pvc.visual.Rule(this, this.pvPanel, {
                    extensionId: "rule"
                }).lock("data", [ rootScene ]).override("defaultColor", def.fun.constant("#666666")).lock(this.anchorOpposite(), 0).lock(begin_a, rMin).lock(size_a, rSize).pvMark.zOrder(30).strokeDasharray(null).lineCap("square");
                this.isDiscrete ? this.useCompositeAxis ? this.renderCompositeOrdinalAxis() : this.renderOrdinalAxis() : this.renderLinearAxis();
            }
        },
        _getExtensionId: function() {
            return "";
        },
        _getRootScene: function() {
            if (!this._rootScene) {
                var rootScene = this._rootScene = new pvc.visual.CartesianAxisRootScene(null, {
                    panel: this,
                    source: this._getRootData()
                }), layoutInfo = this._layoutInfo, ticks = layoutInfo.ticks, ticksText = layoutInfo.ticksText;
                if (this.isDiscrete) if (this.useCompositeAxis) this._buildCompositeScene(rootScene); else {
                    var hiddenDatas, hiddenTexts, createHiddenScene, hiddenIndex, keySep, includeModulo = layoutInfo.tickVisibilityStep, hiddenLabelText = this.hiddenLabelText;
                    rootScene.vars.tickIncludeModulo = includeModulo;
                    rootScene.vars.hiddenLabelText = hiddenLabelText;
                    if (includeModulo > 2) {
                        def.debug >= 3 && this.log.info("Showing only one in every " + includeModulo + " tick labels");
                        keySep = rootScene.group.owner.keySep;
                        createHiddenScene = function() {
                            var k = hiddenDatas.map(function(d) {
                                return d.key;
                            }).join(keySep), l = hiddenTexts.slice(0, 10).join(", ") + (hiddenTexts.length > 10 ? ", ..." : ""), scene = new pvc.visual.CartesianAxisTickScene(rootScene, {
                                source: hiddenDatas,
                                tick: k,
                                tickRaw: k,
                                tickLabel: l,
                                isHidden: !0
                            });
                            scene.dataIndex = hiddenIndex;
                            hiddenDatas = hiddenTexts = hiddenIndex = null;
                        };
                    }
                    ticks.forEach(function(tickData, index) {
                        var isHidden = index % includeModulo !== 0;
                        if (isHidden && includeModulo > 2) {
                            null == hiddenIndex && (hiddenIndex = index);
                            (hiddenDatas || (hiddenDatas = [])).push(tickData);
                            (hiddenTexts || (hiddenTexts = [])).push(ticksText[index]);
                        } else {
                            hiddenDatas && createHiddenScene();
                            var scene = new pvc.visual.CartesianAxisTickScene(rootScene, {
                                source: tickData,
                                tick: tickData.value,
                                tickRaw: tickData.rawValue,
                                tickLabel: ticksText[index],
                                isHidden: isHidden
                            });
                            scene.dataIndex = index;
                        }
                    });
                    hiddenDatas && createHiddenScene();
                } else ticks.forEach(function(majorTick, index) {
                    var scene = new pvc.visual.CartesianAxisTickScene(rootScene, {
                        tick: majorTick,
                        tickRaw: majorTick,
                        tickLabel: ticksText[index]
                    });
                    scene.dataIndex = index;
                }, this);
            }
            return this._rootScene;
        },
        _buildCompositeScene: function(rootScene) {
            function recursive(scene) {
                var data = scene.group;
                if (isV1Compat) {
                    var tickVar = scene.vars.tick;
                    scene.nodeValue = scene.value = tickVar.rawValue;
                    scene.nodeLabel = scene.label = tickVar.label;
                }
                data.childCount() && data.children().each(function(childData) {
                    var label = tickFormatter ? tickFormatter(childData.value, childData.label) : childData.label, childScene = new pvc.visual.CartesianAxisTickScene(scene, {
                        source: childData,
                        tick: childData.value,
                        tickRaw: childData.rawValue,
                        tickLabel: label
                    });
                    childScene.dataIndex = childData.childIndex();
                    recursive(childScene);
                });
            }
            var isV1Compat = this.compatVersion() <= 1, tickFormatter = this.axis.option("TickFormatter");
            rootScene.vars.tick = new pvc_ValueLabelVar("", "");
            recursive(rootScene);
        },
        _getRootData: function() {
            var data;
            if (this.isDiscrete && this.useCompositeAxis) {
                var orient = this.anchor, ka = {
                    visible: this.axis.domainVisibleOnly() ? !0 : null,
                    isNull: this.chart.options.ignoreNulls || this.axis.domainIgnoreNulls() ? !1 : null,
                    reverse: "bottom" == orient || "left" == orient
                };
                data = this.axis.role.select(this.data, ka);
            } else data = this.data;
            return data;
        },
        renderOrdinalAxis: function() {
            var wrapper, scale = this.scale, hiddenLabelText = this.hiddenLabelText, includeModulo = this._layoutInfo.tickVisibilityStep, hiddenStep2 = includeModulo * scale.range().step / 2, anchorOpposite = this.anchorOpposite(), anchorLength = this.anchorLength(), anchorOrtho = this.anchorOrtho(), anchorOrthoLength = this.anchorOrthoLength(), pvRule = this.pvRule, rootScene = this._getRootScene(), layoutInfo = this._layoutInfo, isV1Compat = this.compatVersion() <= 1;
            if (isV1Compat) {
                var DataElement = function(tickVar) {
                    this.value = this.absValue = tickVar.rawValue;
                    this.nodeName = "" + (this.value || "");
                    this.path = this.nodeName ? [ this.nodeName ] : [];
                    this.label = this.absLabel = tickVar.label;
                };
                DataElement.prototype.toString = function() {
                    return "" + this.value;
                };
                wrapper = function(v1f) {
                    return function(tickScene) {
                        var markWrapped = Object.create(this);
                        markWrapped.index = this.parent.index;
                        return v1f.call(markWrapped, new DataElement(tickScene.vars.tick));
                    };
                };
            }
            var pvTicksPanel = new pvc.visual.Panel(this, this.pvPanel, {
                extensionId: "ticksPanel"
            }).lock("data", rootScene.childNodes).lock(anchorOpposite, 0).lockMark(anchorOrtho, function(tickScene) {
                return tickScene.isHidden ? scale(tickScene.previousSibling.vars.tick.value) + hiddenStep2 : scale(tickScene.vars.tick.value);
            }).lock("strokeDasharray", null).lock("strokeStyle", null).lock("fillStyle", null).lock("lineWidth", 0).pvMark.zOrder(20);
            (isV1Compat || this.showTicks) && (this.pvTicks = new pvc.visual.Rule(this, pvTicksPanel, {
                extensionId: "ticks",
                wrapper: wrapper
            }).lock("data").intercept("visible", function(scene) {
                return !scene.isHidden && this.delegateExtension(!0);
            }).optional("lineWidth", 1).lock(anchorOpposite, 0).lock(anchorOrtho, 0).lock(anchorLength, null).optional(anchorOrthoLength, 2 * this.tickLength / 3).override("defaultColor", function() {
                return isV1Compat ? pv.Color.names.transparent : pvRule.scene ? pvRule.scene[0].strokeStyle : "#666666";
            }).pvMark);
            var font = this.font, maxTextWidth = this._layoutInfo.maxTextWidth;
            isFinite(maxTextWidth) || (maxTextWidth = 0);
            this.pvLabel = new pvc.visual.Label(this, pvTicksPanel, {
                extensionId: "label",
                showsInteraction: !0,
                noClick: !1,
                noDoubleClick: !1,
                noSelect: !1,
                noTooltip: !1,
                noHover: !1,
                wrapper: wrapper
            }).intercept("visible", function(tickScene) {
                return tickScene.isHidden ? !!tickScene.vars.hiddenLabelText : this.delegateExtension(!0);
            }).intercept("text", function(tickScene) {
                var text;
                if (tickScene.isHidden) text = hiddenLabelText; else {
                    text = this.delegateExtension();
                    void 0 === text && (text = tickScene.vars.tick.label);
                    !maxTextWidth || this.showsInteraction() && tickScene.isActive || (text = pvc.text.trimToWidthB(maxTextWidth, text, font, "..", !1));
                }
                return text;
            }).pvMark.zOrder(40).lock(anchorOpposite, this.tickLength).lock(anchorOrtho, 0).font(font).textStyle("#666666");
            layoutInfo.textAngleLocked ? this.pvLabel.lock("textAngle", layoutInfo.textAngle) : this.pvLabel.textAngle(layoutInfo.textAngle);
            layoutInfo.textAlignLocked ? this.pvLabel.lock("textAlign", layoutInfo.textAlign) : this.pvLabel.textAlign(layoutInfo.textAlign);
            layoutInfo.textBaselineLocked ? this.pvLabel.lock("textBaseline", layoutInfo.textBaseline) : this.pvLabel.textBaseline(layoutInfo.textBaseline);
            this._debugTicksPanel(pvTicksPanel);
        },
        _getTooltipFormatter: function(tipOptions) {
            if (this.axis.option("TooltipEnabled")) {
                tipOptions.gravity = this._calcTipsyGravity();
                var tooltipFormat = this.axis.option("TooltipFormat");
                if (tooltipFormat) return function(context) {
                    return tooltipFormat.call(context, context.scene);
                };
                var autoContent = this.axis.option("TooltipAutoContent");
                if ("summary" === autoContent) return this._summaryTooltipFormatter.bind(this);
                if ("value" === autoContent) {
                    tipOptions.isLazy = !1;
                    return function(context) {
                        var pvMark = context.pvMark, label = context.scene.vars.tick.label;
                        return pvMark.textAngle() || pvMark.text() !== label ? label : "";
                    };
                }
            }
        },
        _getTooltipPanelClasses: function() {
            return [ "cart-axis" ];
        },
        _debugTicksPanel: function(pvTicksPanel) {
            if (def.debug >= 16 && this.showLabels) {
                var li = this._layoutInfo, ticksBBoxes = li.ticksBBoxes || this._calcTicksLabelBBoxes(li);
                pvTicksPanel.add(pv.Panel)[this.anchorOpposite()](this.tickLength)[this.anchorOrtho()](0)[this.anchorLength()](0)[this.anchorOrthoLength()](0).fillStyle(null).strokeStyle(null).lineWidth(0).visible(function(tickScene) {
                    return !tickScene.isHidden;
                }).add(pv.Line).data(function(scene) {
                    var labelBBox = ticksBBoxes[scene.dataIndex], corners = labelBBox.source.points();
                    corners.length > 1 && (corners = corners.concat(corners[0]));
                    return corners;
                }).left(function(p) {
                    return p.x;
                }).top(function(p) {
                    return p.y;
                }).strokeStyle("red").lineWidth(.5).strokeDasharray("-");
            }
        },
        renderLinearAxis: function() {
            var wrapper, scale = this.scale, pvRule = this.pvRule, anchorOpposite = this.anchorOpposite(), anchorLength = this.anchorLength(), anchorOrtho = this.anchorOrtho(), anchorOrthoLength = this.anchorOrthoLength(), rootScene = this._getRootScene();
            this.compatVersion() <= 1 && (wrapper = function(v1f) {
                return function(tickScene) {
                    var markWrapped = Object.create(this);
                    markWrapped.index = this.parent.index;
                    return v1f.call(markWrapped, tickScene.vars.tick.rawValue);
                };
            });
            var pvTicksPanel = new pvc.visual.Panel(this, this.pvPanel, {
                extensionId: "ticksPanel"
            }).lock("data", rootScene.childNodes).lock(anchorOpposite, 0).lockMark(anchorOrtho, function(tickScene) {
                return scale(tickScene.vars.tick.value);
            }).lock("strokeStyle", null).lock("fillStyle", null).lock("lineWidth", 0).pvMark.zOrder(20);
            if (this.showTicks) {
                var pvTicks = this.pvTicks = new pvc.visual.Rule(this, pvTicksPanel, {
                    extensionId: "ticks",
                    wrapper: wrapper
                }).lock("data").override("defaultColor", function() {
                    return pvRule.scene ? pvRule.scene[0].strokeStyle : "#666666";
                }).lock(anchorOpposite, 0).lock(anchorOrtho, 0).lock(anchorLength, null).optional(anchorOrthoLength, this.tickLength).pvMark;
                if (this.showMinorTicks) {
                    var layoutInfo = this._layoutInfo, ticks = layoutInfo.ticks, tickCount = ticks.length, minorTickOffset = tickCount > 1 ? Math.abs(scale(ticks[1]) - scale(ticks[0])) / 2 : 0;
                    this.pvMinorTicks = new pvc.visual.Rule(this, this.pvTicks, {
                        extensionId: "minorTicks",
                        wrapper: wrapper
                    }).lock("data").intercept("visible", function(scene) {
                        var visible = scene.childIndex() < tickCount - 1 && (!pvTicks.scene || pvTicks.scene[0].visible);
                        return visible && this.delegateExtension(!0);
                    }).override("defaultColor", function() {
                        return pvTicks.scene ? pvTicks.scene[0].strokeStyle : "#666666";
                    }).lock(anchorOpposite, 0).lock(anchorLength, null).optional(anchorOrthoLength, this.tickLength / 2).lockMark(anchorOrtho, minorTickOffset).pvMark;
                }
            }
            this.renderLinearAxisLabel(pvTicksPanel, wrapper);
            this._debugTicksPanel(pvTicksPanel);
        },
        renderLinearAxisLabel: function(pvTicksPanel, wrapper) {
            var anchorOpposite = this.anchorOpposite(), anchorOrtho = this.anchorOrtho(), font = this.font, maxTextWidth = this._layoutInfo.maxTextWidth;
            isFinite(maxTextWidth) || (maxTextWidth = 0);
            var pvLabel = this.pvLabel = new pvc.visual.Label(this, pvTicksPanel, {
                extensionId: "label",
                noHover: !1,
                showsInteraction: !0,
                wrapper: wrapper
            }).lock("data").optional("text", function(tickScene) {
                var text = tickScene.vars.tick.label;
                !maxTextWidth || this.showsInteraction() && tickScene.isActive || (text = pvc.text.trimToWidthB(maxTextWidth, text, font, "..", !1));
                return text;
            }).pvMark.lock(anchorOpposite, this.tickLength).lock(anchorOrtho, 0).zOrder(40).font(this.font).textStyle("#666666"), rootPanel = this.pvPanel.root;
            this.isAnchorTopOrBottom() ? pvLabel.textBaseline(anchorOpposite).textAlign(function(tickScene) {
                var absLeft;
                if (0 === this.index) {
                    absLeft = pvLabel.toScreenTransform().transformHPosition(pvLabel.left());
                    if (0 >= absLeft) return "left";
                } else if (this.index === tickScene.parent.childNodes.length - 1) {
                    absLeft = pvLabel.toScreenTransform().transformHPosition(pvLabel.left());
                    if (absLeft >= rootPanel.width()) return "right";
                }
                return "center";
            }) : pvLabel.textAlign(anchorOpposite).textBaseline(function(tickScene) {
                var absTop;
                if (0 === this.index) {
                    absTop = pvLabel.toScreenTransform().transformVPosition(pvLabel.top());
                    if (absTop >= rootPanel.height()) return "bottom";
                } else if (this.index === tickScene.parent.childNodes.length - 1) {
                    absTop = pvLabel.toScreenTransform().transformVPosition(pvLabel.top());
                    if (0 >= absTop) return "top";
                }
                return "middle";
            });
        },
        _onV1Click: function(context, handler) {
            this.isDiscrete && this.useCompositeAxis && handler.call(context.pvMark, context.scene, context.event);
        },
        _onV1DoubleClick: function(context, handler) {
            this.isDiscrete && this.useCompositeAxis && handler.call(context.pvMark, context.scene, context.event);
        },
        _getSelectableMarks: function() {
            return this.isDiscrete && this.isVisible && this.pvLabel ? this.base() : void 0;
        },
        renderCompositeOrdinalAxis: function() {
            var wrapper, isTopOrBottom = this.isAnchorTopOrBottom(), axisDirection = isTopOrBottom ? "h" : "v", H_CUTOFF_ANG = .3, V_CUTOFF_ANG = 1.27, diagDepthCutoff = 2, vertDepthCutoff = 2, font = this.font, diagMargin = pv.Text.fontHeight(font) / 2, layout = this._pvLayout = this._getCompositeLayoutSingleCluster(), align = isTopOrBottom ? "center" : "left" == this.anchor ? "right" : "left";
            layout.node.def("fitInfo", null).height(function(tickScene, e, f) {
                var fitInfo = pvc.text.getFitInfo(tickScene.dx, tickScene.dy, tickScene.vars.tick.label, font, diagMargin);
                fitInfo.h || ("v" === axisDirection && fitInfo.v ? vertDepthCutoff = Math.min(diagDepthCutoff, tickScene.depth) : diagDepthCutoff = Math.min(diagDepthCutoff, tickScene.depth));
                this.fitInfo(fitInfo);
                return tickScene.dy;
            });
            layout.node.add(pv.Bar).fillStyle("rgba(127,127,127,.001)").strokeStyle(function(tickScene) {
                return 1 !== tickScene.maxDepth && tickScene.maxDepth ? "rgba(127,127,127,0.3)" : null;
            }).lineWidth(function(tickScene) {
                return 1 !== tickScene.maxDepth && tickScene.maxDepth ? .5 : 0;
            }).text(function(tickScene) {
                return tickScene.vars.tick.label;
            });
            this.compatVersion() <= 1 && (wrapper = function(v1f) {
                return function(tickScene) {
                    return v1f.call(this, tickScene);
                };
            });
            this.pvLabel = new pvc.visual.Label(this, layout.label, {
                extensionId: "label",
                noClick: !1,
                noDoubleClick: !1,
                noSelect: !1,
                noTooltip: !1,
                noHover: !1,
                showsInteraction: !0,
                wrapper: wrapper,
                tooltipArgs: {
                    options: {
                        offset: 2 * diagMargin
                    }
                }
            }).pvMark.def("lblDirection", "h").textAngle(function(tickScene) {
                if (tickScene.depth >= vertDepthCutoff && tickScene.depth < diagDepthCutoff) return this.lblDirection("v"), 
                -Math.PI / 2;
                if (tickScene.depth >= diagDepthCutoff) {
                    var tan = tickScene.dy / tickScene.dx, angle = Math.atan(tan);
                    if (angle > V_CUTOFF_ANG) return this.lblDirection("v"), -Math.PI / 2;
                    if (angle > H_CUTOFF_ANG) return this.lblDirection("d"), -angle;
                }
                return this.lblDirection("h"), 0;
            }).textMargin(1).textAlign(function(tickScene) {
                return "v" != axisDirection || tickScene.depth >= vertDepthCutoff || tickScene.depth >= diagDepthCutoff ? "center" : align;
            }).left(function(tickScene) {
                return "v" != axisDirection || tickScene.depth >= vertDepthCutoff || tickScene.depth >= diagDepthCutoff ? tickScene.x + tickScene.dx / 2 : "right" == align ? tickScene.x + tickScene.dx : tickScene.x;
            }).font(font).textStyle("#666666").text(function(tickScene) {
                var label = tickScene.vars.tick.label;
                if (!tickScene.isActive || !this.sign.showsInteraction()) {
                    var fitInfo = this.fitInfo();
                    switch (this.lblDirection()) {
                      case "h":
                        if (!fitInfo.h) return pvc.text.trimToWidthB(tickScene.dx, label, font, "..");
                        break;

                      case "v":
                        if (!fitInfo.v) return pvc.text.trimToWidthB(tickScene.dy, label, font, "..");
                        break;

                      case "d":
                        if (!fitInfo.d) {
                            var diagonalLength = Math.sqrt(def.sqr(tickScene.dy) + def.sqr(tickScene.dx));
                            return pvc.text.trimToWidthB(diagonalLength - diagMargin, label, font, "..");
                        }
                    }
                }
                return label;
            });
        },
        _getCompositeLayoutSingleCluster: function() {
            var rootScene = this._getRootScene(), orientation = this.anchor, maxDepth = rootScene.group.treeHeight + 1, depthLength = this._layoutInfo.axisSize, margin = maxDepth > 2 ? 1 / 12 * depthLength : 0, baseDisplacement = depthLength / maxDepth - margin, scaleFactor = maxDepth / (maxDepth - 1), orthoLength = pvc.BasePanel.orthogonalLength[orientation], displacement = "width" == orthoLength ? "left" === orientation ? [ -baseDisplacement, 0 ] : [ baseDisplacement, 0 ] : "top" === orientation ? [ 0, -baseDisplacement ] : [ 0, baseDisplacement ];
            this.pvRule.sign.override("defaultColor", def.fun.constant(null)).override("defaultStrokeWidth", def.fun.constant(0));
            var panel = this.pvRule.add(pv.Panel)[orthoLength](depthLength).strokeStyle(null).lineWidth(0).add(pv.Panel)[orthoLength](depthLength * scaleFactor).strokeStyle(null).lineWidth(0);
            panel.transform(pv.Transform.identity.translate(displacement[0], displacement[1]));
            return panel.add(pv.Layout.Cluster.Fill).nodes(rootScene.nodes()).orient(orientation);
        },
        _calcTipsyGravity: function() {
            switch (this.anchor) {
              case "bottom":
                return "s";

              case "top":
                return "n";

              case "left":
                return "w";

              case "right":
                return "e";
            }
            return "s";
        }
    });
    pvc.AxisPanel._calcDiscreteOverlapSettings = function(overlappedLabelsMode, labelRotationDirection, labelDesiredAngles, distanceBetweenTicks, labelSpacingMin, fontPxWidth, axisAnchor, layoutInfo) {
        var FLAT_ANGLE = Math.PI, RIGHT_ANGLE = FLAT_ANGLE / 2, FULL_ANGLE = 2 * FLAT_ANGLE;
        layoutInfo.tickVisibilityStep = 1;
        var canHide = !1, canRotate = !1;
        switch (overlappedLabelsMode) {
          case "hide":
            canHide = !0;
            break;

          case "rotate":
            canRotate = !0;
            break;

          case "rotatethenhide":
            canHide = !0;
            canRotate = !0;
            break;

          default:
            return;
        }
        null == labelRotationDirection && (labelRotationDirection = 1);
        labelDesiredAngles = labelDesiredAngles ? labelDesiredAngles.slice().sort(function(v1, v2) {
            return Math.abs(v1) - Math.abs(v2);
        }) : [];
        var h = layoutInfo.textHeight, w = layoutInfo.maxTextWidth, sMin = h * labelSpacingMin, sMinH = sMin, sMinW = fontPxWidth + sMin, onBottom = "bottom" === axisAnchor, onLeft = "left" === axisAnchor, isHorizontal = onBottom || "top" === axisAnchor, min_angle = 0, max_angle = FULL_ANGLE, all_angles_overlap = h + sMinH > distanceBetweenTicks;
        !all_angles_overlap && w + sMinW > distanceBetweenTicks && (isHorizontal ? min_angle = pvc.normAngle(Math.asin(h / (distanceBetweenTicks - sMinH))) : max_angle = pvc.normAngle(Math.acos(h / (distanceBetweenTicks - sMinH))));
        var a;
        if (canRotate) {
            if (labelDesiredAngles.length > 0) for (var i = 0; i !== labelDesiredAngles.length; ++i) {
                a = pvc.normAngle(labelDesiredAngles[i] * labelRotationDirection);
                var abs_angle = a > FLAT_ANGLE ? Math.abs(a - FULL_ANGLE) : a, angle_to_axis = RIGHT_ANGLE > abs_angle ? abs_angle : abs_angle - FLAT_ANGLE;
                if (!all_angles_overlap && angle_to_axis >= min_angle) {
                    canHide = canHide && (all_angles_overlap || angle_to_axis > max_angle);
                    break;
                }
            } else if (all_angles_overlap) a = isHorizontal ? RIGHT_ANGLE : 0; else {
                a = pvc.normAngle(min_angle * labelRotationDirection);
                canHide = !1;
            }
            layoutInfo.textAngle = a;
            layoutInfo.textAngleLocked = !0;
            var align = "center", baseline = "middle";
            if (isHorizontal) {
                a > 0 && FLAT_ANGLE > a ? align = onBottom ? "left" : "right" : a > FLAT_ANGLE && FULL_ANGLE > a && (align = onBottom ? "right" : "left");
                a >= 0 && RIGHT_ANGLE > a || a > FLAT_ANGLE + RIGHT_ANGLE && FULL_ANGLE > a ? baseline = onBottom ? "top" : "bottom" : a > RIGHT_ANGLE && FULL_ANGLE - RIGHT_ANGLE > a && (baseline = onBottom ? "bottom" : "top");
            } else {
                a >= 0 && RIGHT_ANGLE > a || a > FLAT_ANGLE + RIGHT_ANGLE && FULL_ANGLE > a ? align = onLeft ? "right" : "left" : a > RIGHT_ANGLE && FULL_ANGLE - RIGHT_ANGLE > a && (align = onLeft ? "left" : "right");
                a > 0 && FLAT_ANGLE > a ? baseline = onLeft ? "top" : "bottom" : a > FLAT_ANGLE && FULL_ANGLE > a && (baseline = onLeft ? "bottom" : "top");
            }
            layoutInfo.textAlign = align;
            layoutInfo.textAlignLocked = !0;
            layoutInfo.textBaseline = baseline;
            layoutInfo.textBaselineLocked = !0;
        } else {
            a = layoutInfo.textAngle ? pvc.normAngle(layoutInfo.textAngle * labelRotationDirection) : 0;
            layoutInfo.textAngle = a;
            layoutInfo.textAngleLocked = !0;
        }
        var tickCount = layoutInfo.ticks.length;
        if (canHide && tickCount > 2) {
            var projected_size;
            projected_size = isHorizontal ? Math.min(w, Math.abs(h / Math.sin(a))) + (all_angles_overlap || min_angle > a ? sMinW : sMinH) : Math.min(w, Math.abs(h / Math.cos(a))) + (all_angles_overlap || a > max_angle ? sMinW : sMinH);
            if (projected_size > distanceBetweenTicks) {
                var tim = Math.ceil(projected_size / distanceBetweenTicks);
                (!isFinite(tim) || 1 > tim || Math.ceil(tickCount / tim) < 2) && (tim = 1);
                layoutInfo.tickVisibilityStep = tim;
            }
        }
    };
    def.type("pvc.AxisTitlePanel", pvc.TitlePanelAbstract).init(function(chart, parent, axis, options) {
        this.axis = axis;
        this.base(chart, parent, options);
        this._extensionPrefix = axis.extensionPrefixes.map(function(prefix) {
            return prefix + "Title";
        });
    }).add({
        _calcLayout: function(layoutInfo) {
            var scale = this.axis.scale;
            return !scale || scale.isNull ? new pvc_Size(0, 0) : this.base(layoutInfo);
        },
        _createCore: function(layoutInfo) {
            var scale = this.axis.scale;
            return scale && !scale.isNull ? this.base(layoutInfo) : void 0;
        }
    });
    def("pvc.visual.CartesianFocusWindow", pvc.visual.OptionsBase.extend({
        init: function(chart) {
            this.base(chart, "focusWindow", 0, {
                byNaked: !1
            });
            var baseAxis = chart.axes.base;
            this.base = new pvc.visual.CartesianFocusWindowAxis(this, baseAxis);
        },
        methods: {
            _exportData: function() {
                return {
                    base: def.copyProps(this.base, pvc.visual.CartesianFocusWindowAxis.props)
                };
            },
            _importData: function(data) {
                var baseData = data.base;
                this.base.option.specify({
                    Begin: baseData.begin,
                    End: baseData.end,
                    Length: baseData.length
                });
            },
            _initFromOptions: function() {
                this.base._initFromOptions();
            },
            _onAxisChanged: function(axis) {
                var changed = this.option("Changed");
                changed && changed.call(this.chart.basePanel.context());
            }
        },
        options: {
            Changed: {
                resolve: "_resolveFull",
                cast: def.fun.as
            }
        }
    }));
    def("pvc.visual.CartesianFocusWindowAxis", pvc.visual.OptionsBase.extend({
        init: function(fw, axis) {
            this.window = fw;
            this.axis = axis;
            this.isDiscrete = axis.isDiscrete();
            this.base(axis.chart, "focusWindow" + def.firstUpperCase(axis.type), 0, {
                byNaked: !1
            });
        },
        type: {
            methods: {
                props: [ "begin", "end", "length" ]
            }
        },
        methods: {
            _initFromOptions: function() {
                var o = this.option;
                this.set({
                    begin: o("Begin"),
                    end: o("End"),
                    length: o("Length")
                });
            },
            set: function(keyArgs) {
                var b, e, l, me = this, render = def.get(keyArgs, "render"), select = def.get(keyArgs, "select", !0);
                keyArgs = me._readArgs(keyArgs);
                if (keyArgs) {
                    b = keyArgs.begin;
                    e = keyArgs.end;
                    l = keyArgs.length;
                } else if (null != this.begin && null != this.end && null != this.length) return;
                var a, L, ib, ie, ia, nb, ne, axis = me.axis, scale = axis.scale, isDiscrete = me.isDiscrete, contCast = isDiscrete ? null : axis.role.lastDimensionType().cast, domain = scale.domain();
                if (isDiscrete) {
                    L = domain.length;
                    if (null != b) {
                        nb = +b;
                        if (!isNaN(nb)) if (nb === 1 / 0) {
                            ib = L - 1;
                            b = domain[ib];
                        } else if (nb === -(1 / 0)) {
                            ib = 0;
                            b = domain[ib];
                        }
                        if (null == ib) {
                            ib = domain.indexOf("" + b);
                            if (0 > ib) {
                                ib = 0;
                                b = domain[ib];
                            }
                        }
                    }
                    if (null != e) {
                        ne = +e;
                        if (!isNaN(ne)) if (ne === 1 / 0) {
                            ie = L - 1;
                            e = domain[ie];
                        } else if (ne === -(1 / 0)) {
                            ie = 0;
                            e = domain[ie];
                        }
                        if (null == ie) {
                            ie = domain.indexOf("" + e);
                            if (0 > ie) {
                                ie = L - 1;
                                e = domain[ie];
                            }
                        }
                    }
                    if (null != l) {
                        l = +l;
                        if (isNaN(l)) l = null; else if (0 > l && (null != b || null != e)) {
                            a = b;
                            ia = ib;
                            b = e;
                            ib = ie;
                            e = a;
                            ie = ia;
                            l = -l;
                        }
                    }
                    if (null != b) if (null != e) {
                        if (ib > ie) {
                            a = b;
                            ia = ib;
                            b = e;
                            ib = ie;
                            e = a;
                            ie = ia;
                        }
                        l = ie - ib + 1;
                    } else {
                        null == l && (l = L - ib);
                        ie = ib + l - 1;
                        if (ie > L - 1) {
                            ie = L - 1;
                            l = ie - ib + 1;
                        }
                        e = domain[ie];
                    } else if (null != e) {
                        null == l && (l = ie);
                        ib = ie - l + 1;
                        if (0 > ib) {
                            ib = 0;
                            l = ie - ib + 1;
                        }
                        b = domain[ib];
                    } else {
                        null == l && (l = Math.max(~~(L / 3), 1));
                        if (l > L) {
                            l = L;
                            ib = 0;
                            ie = L - 1;
                        } else {
                            ia = ~~(L / 2);
                            ib = ia - ~~(l / 2);
                            ie = ib + l - 1;
                        }
                        b = domain[ib];
                        e = domain[ie];
                    }
                } else {
                    if (null != l) {
                        l = +l;
                        if (isNaN(l)) l = null; else if (0 > l && (null != b || null != e)) {
                            a = b;
                            b = e;
                            e = a;
                            l = -l;
                        }
                    }
                    var bAux, lAux, min = domain[0], max = domain[1];
                    L = max - min;
                    if (null != b) {
                        min > b && (b = min);
                        b > max && (b = max);
                    }
                    if (null != e) {
                        min > e && (e = min);
                        e > max && (e = max);
                    }
                    if (null != b) if (null != e) {
                        if (b > e) {
                            a = b;
                            b = e;
                            e = a;
                        }
                        l = e - b;
                    } else {
                        null == l && (l = max - b);
                        e = b + l;
                        if (e > max) {
                            e = max;
                            l = e - b;
                        }
                    } else if (null != e) {
                        null == l && (l = e - min);
                        b = e - l;
                        if (min > b) {
                            b = min;
                            l = e - b;
                        }
                    } else {
                        null == l && (l = Math.max(~~(L / 3), 1));
                        if (l > L) {
                            l = L;
                            b = min;
                            e = max;
                        } else {
                            a = ~~(L / 2);
                            b = a - ~~(l / 2);
                            bAux = +b;
                            lAux = +l;
                            e = bAux + lAux;
                        }
                    }
                    b = contCast(b);
                    e = contCast(e);
                    l = contCast(l);
                    var constraint = me.option("Constraint");
                    if (constraint) {
                        var oper2 = {
                            type: "new",
                            target: "begin",
                            value: b,
                            length: l,
                            length0: l,
                            min: min,
                            max: max,
                            minView: min,
                            maxView: max
                        };
                        constraint(oper2);
                        b = contCast(oper2.value);
                        l = contCast(oper2.length);
                        bAux = +b;
                        lAux = +l;
                        e = contCast(bAux + lAux);
                    }
                }
                me._set(b, e, l, select, render);
            },
            _updatePosition: function(pbeg, pend, select, render) {
                var b, e, l, me = this, axis = me.axis, scale = axis.scale;
                if (me.isDiscrete) {
                    var ib = scale.invertIndex(pbeg), ie = scale.invertIndex(pend) - 1, domain = scale.domain();
                    b = domain[ib];
                    e = domain[ie];
                    l = ie - ib + 1;
                } else {
                    b = scale.invert(pbeg);
                    e = scale.invert(pend);
                    l = e - b;
                }
                this._set(b, e, l, select, render);
            },
            _constraintPosition: function(oper) {
                var constraint, me = this, axis = me.axis, scale = axis.scale;
                if (me.isDiscrete) {
                    var index = Math.floor(scale.invertIndex(oper.point, !0));
                    if (index >= 0) {
                        var r = scale.range(), L = scale.domain().length, S = (r.max - r.min) / L;
                        index >= L && ("new" === oper.type || "resize-begin" === oper.type) && (index = L - 1);
                        oper.point = index * S;
                    }
                } else if (constraint = me.option("Constraint")) {
                    var vlength0, pother0, vother0, contCast = axis.role.lastDimensionType().cast, v = contCast(scale.invert(oper.point)), sign = "begin" === oper.target ? 1 : -1, pother = oper.point + sign * oper.length, vother = contCast(scale.invert(pother)), vlength = contCast(sign * (vother - v));
                    if (oper.length === oper.length0) vlength0 = vlength; else {
                        pother0 = oper.point + sign * oper.length0;
                        vother0 = contCast(scale.invert(pother0));
                        vlength0 = contCast(sign * (vother0 - v));
                    }
                    var vmin = contCast(scale.invert(oper.min)), vmax = contCast(scale.invert(oper.max)), oper2 = {
                        type: oper.type,
                        target: oper.target,
                        value: v,
                        length: vlength,
                        length0: vlength0,
                        min: vmin,
                        max: vmax,
                        minView: contCast(scale.invert(oper.minView)),
                        maxView: contCast(scale.invert(oper.maxView))
                    };
                    constraint(oper2);
                    if (+oper2.value !== +v) {
                        v = oper2.value;
                        oper.point = scale(v);
                    }
                    var vlength2 = oper2.length;
                    if (+vlength2 !== +vlength) if (+vlength2 === +vlength0) oper.length = oper.length0; else {
                        var vother2 = +v + sign * +vlength2, pother2 = scale(vother2);
                        oper.length = pother2 - sign * oper.point;
                    }
                    +oper2.min !== +vmin && (oper.min = scale(oper2.min));
                    +oper2.max !== +vmax && (oper.max = scale(oper2.max));
                }
            },
            _compare: function(a, b) {
                return this.isDiscrete ? "" + a == "" + b : +a === +b;
            },
            _set: function(b, e, l, select, render) {
                var me = this, changed = !1;
                me._compare(b, me.begin) || (me.begin = b, changed = !0);
                me._compare(e, me.end) || (me.end = e, changed = !0);
                me._compare(l, me.length) || (me.length = l, changed = !0);
                changed && me.window._onAxisChanged(me);
                select && me._updateSelection({
                    render: render
                });
                return changed;
            },
            _readArgs: function(keyArgs) {
                if (keyArgs) {
                    var out = {}, any = 0, read = function(p) {
                        var v = keyArgs[p];
                        null != v ? any = !0 : v = this[p];
                        out[p] = v;
                    };
                    pvc.visual.CartesianFocusWindowAxis.props.forEach(read, this);
                    if (any) return out;
                }
            },
            _updateSelection: function(keyArgs) {
                var selectDatums, domainData, me = this, axis = me.axis, isDiscrete = axis.isDiscrete(), chart = axis.chart, dataCell = axis.dataCell, role = dataCell.role, partData = chart.partData(dataCell.dataPartValue);
                if (isDiscrete) {
                    domainData = role.flatten(partData);
                    var dataBegin = domainData.child(me.begin), dataEnd = domainData.child(me.end);
                    if (dataBegin && dataEnd) {
                        var indexBegin = dataBegin.childIndex(), indexEnd = dataEnd.childIndex();
                        selectDatums = def.range(indexBegin, indexEnd - indexBegin + 1).select(function(index) {
                            return domainData.childNodes[index];
                        }).selectMany(def.propGet("_datums")).where(cdo.Datum.isVisibleT).distinct(def.propGet("key"));
                    }
                } else {
                    domainData = partData;
                    var dimName = role.lastDimensionName();
                    selectDatums = def.query(partData._datums).where(cdo.Datum.isVisibleT).where(function(datum) {
                        var v = datum.atoms[dimName].value;
                        return null != v && v >= me.begin && v <= me.end;
                    });
                }
                if (selectDatums) {
                    chart.data.replaceSelected(selectDatums);
                    chart.root.updateSelections(keyArgs);
                }
            }
        },
        options: {
            Resizable: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !0
            },
            Movable: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !0
            },
            Begin: {
                resolve: "_resolveFull"
            },
            End: {
                resolve: "_resolveFull"
            },
            Length: {
                resolve: "_resolveFull"
            },
            Constraint: {
                resolve: "_resolveFull",
                cast: def.fun.as
            }
        }
    }));
    def.type("pvc.visual.CartesianOrthoDataCell", pvc.visual.DataCell).init(function(plot, axisType, axisIndex, roleName, dataPartValue, isStacked, nullInterpolationMode, trend) {
        this.base(plot, axisType, axisIndex, roleName, dataPartValue);
        this.isStacked = isStacked;
        this.nullInterpolationMode = nullInterpolationMode;
        this.trend = trend;
    });
    def("pvc.visual.CartesianPlot", pvc.visual.Plot.extend({
        init: function(chart, keyArgs) {
            this.base(chart, keyArgs);
            if (!(chart instanceof pvc.CartesianAbstract)) throw def.error(def.format("Plot type '{0}' can only be used from within a cartesian chart.", [ this.type ]));
        },
        methods: {
            _initVisualRoles: function() {
                this.base();
                var roleSpec = this._getSeriesRoleSpec();
                roleSpec && this._addVisualRole("series", roleSpec);
            },
            _getColorRoleSpec: function() {
                return {
                    isRequired: !0,
                    defaultDimension: "color*",
                    defaultSourceRole: "series",
                    requireIsDiscrete: !0
                };
            },
            _getSeriesRoleSpec: function() {
                return {
                    isRequired: !0,
                    defaultDimension: "series*",
                    autoCreateDimension: !0,
                    requireIsDiscrete: !0
                };
            },
            _initDataCells: function() {
                this.base();
                var dataPartValue = this.option("DataPart"), baseRole = this._getBaseRole(), orthoRoles = this._getOrthoRoles();
                baseRole && this._addDataCell(new pvc.visual.DataCell(this, "base", this.option("BaseAxis") - 1, baseRole, dataPartValue));
                if (orthoRoles) {
                    var orthoAxisIndex = this.option("OrthoAxis") - 1, isStacked = this.option.isDefined("Stacked") ? this.option("Stacked") : void 0, nullInterpolationMode = this.option("NullInterpolationMode"), trend = this.option("Trend");
                    orthoRoles.forEach(function(orthoRole) {
                        this._addDataCell(new pvc.visual.CartesianOrthoDataCell(this, "ortho", orthoAxisIndex, orthoRole, dataPartValue, isStacked, nullInterpolationMode, trend));
                    }, this);
                }
            },
            _getOrthoRoles: function() {},
            _getBaseRole: function() {}
        },
        options: {
            BaseAxis: {
                value: 1
            },
            OrthoAxis: {
                resolve: function(optionInfo) {
                    return 0 === this.globalIndex ? (optionInfo.specify(1), !0) : this._resolveFull(optionInfo);
                },
                data: {
                    resolveV1: function(optionInfo) {
                        "plot2" === this.name && this.chart._allowV1SecondAxis && this._chartOption("secondAxisIndependentScale") && optionInfo.specify(2);
                        return !0;
                    }
                },
                cast: function(value) {
                    value = def.number.to(value);
                    return null != value ? def.between(value, 1, 10) : 1;
                },
                value: 1
            },
            Trend: {
                resolve: "_resolveFull",
                data: {
                    resolveDefault: function(optionInfo) {
                        var type = this.option("TrendType");
                        return type ? (optionInfo.defaultValue({
                            type: type
                        }), !0) : void 0;
                    }
                },
                cast: pvc_castTrend
            },
            TrendType: {
                resolve: "_resolveFull",
                cast: pvc.parseTrendType
            },
            TrendLabel: {
                resolve: "_resolveFull",
                cast: String
            },
            NullInterpolationMode: {
                resolve: "_resolveFull",
                cast: pvc.parseNullInterpolationMode,
                value: "none"
            }
        }
    }));
    def.type("pvc.CartesianAbstract", pvc.BaseChart).init(function(options) {
        this.axesPanels = {};
        this.base(options);
    }).add({
        _axisClassByType: {
            base: pvc_CartesianAxis,
            ortho: pvc_CartesianAxis
        },
        contentPanel: null,
        _defaultAxisBandSizeRatio: .9,
        axesPanels: null,
        yAxisPanel: null,
        xAxisPanel: null,
        secondXAxisPanel: null,
        secondYAxisPanel: null,
        yScale: null,
        xScale: null,
        _setAxisScale: function(axis, chartLevel) {
            this.base(axis, chartLevel);
            var isOrtho = "ortho" === axis.type, isCart = isOrtho || "base" === axis.type;
            isCart && (isOrtho && 1 === axis.index ? this.secondScale = axis.scale : axis.index || (this[axis.orientation + "Scale"] = axis.scale));
        },
        _initAxesEnd: function() {
            var p = this.parent;
            this._axisOffsetPaddings = p ? p._axisOffsetPaddings : this._calcAxesOffsetPaddings();
            this._plotsClientSizeInfo = p ? p._plotsClientSizeInfo : this._calcPlotsClientSizeInfo();
            var axisOffsetPct = this._axisOffsetPct = {};
            def.eachOwn(this._axisOffsetPaddings, function(v, p) {
                null != v && (axisOffsetPct[p] = new pvc_PercentValue(v));
            });
            this.base();
        },
        _eachCartAxis: function(f, x) {
            var axesByType = this.axesByType;
            [ "base", "ortho" ].forEach(function(type) {
                var typeAxes = axesByType[type];
                typeAxes && typeAxes.forEach(function(axis) {
                    f.call(x, axis);
                });
            });
        },
        _calcPlotsClientSizeInfo: function() {
            if (!this.parent) {
                var sizeMin = new pvc_Size(0, 0), sizeMax = new pvc_Size(1 / 0, 1 / 0), size = new pvc_Size();
                this._eachCartAxis(function(axis) {
                    var a_size = "x" === axis.orientation ? "width" : "height", rangeInfo = axis.getScaleRangeInfo();
                    if (rangeInfo) if (null != rangeInfo.value) size[a_size] = Math.max(size[a_size] || 0, rangeInfo.value); else if (null != rangeInfo.min) {
                        sizeMin[a_size] = Math.max(sizeMin[a_size], rangeInfo.min);
                        sizeMax[a_size] = Math.min(sizeMax[a_size], rangeInfo.max);
                    }
                });
                sizeMax.width = Math.max(sizeMax.width, sizeMin.width);
                sizeMax.height = Math.max(sizeMax.height, sizeMin.height);
                null != size.width ? size.width = Math.max(Math.min(size.width, sizeMax.width), sizeMin.width) : pv.floatEqual(sizeMin.width, sizeMax.width) && (size.width = sizeMin.width);
                null != size.height ? size.height = Math.max(Math.min(size.height, sizeMax.height), sizeMin.height) : pv.floatEqual(sizeMin.height, sizeMax.height) && (size.height = sizeMin.height);
                return {
                    value: size,
                    min: sizeMin,
                    max: sizeMax
                };
            }
        },
        _calcAxesOffsetPaddings: function() {
            function processAxis(axis) {
                var offset = axis && axis.option("Offset");
                if (null != offset && offset >= 0 && .5 > offset) if ("x" === axis.orientation) {
                    setSide("left", offset);
                    setSide("right", offset);
                } else {
                    setSide("top", offset);
                    setSide("bottom", offset);
                }
            }
            function setSide(side, pct) {
                var value = pctPaddings[side];
                if (null == value || pct > value) {
                    hasAny = !0;
                    pctPaddings[side] = pct;
                }
            }
            var pctPaddings = {}, hasAny = !1;
            this._eachCartAxis(processAxis);
            return hasAny ? pctPaddings : null;
        },
        _createContentPanel: function(parentPanel, contentOptions) {
            this._createFocusWindow();
            var contentPanel = new pvc.CartesianGridDockingPanel(this, parentPanel, {
                margins: contentOptions.margins,
                paddings: contentOptions.paddings
            });
            this._gridDockPanel = contentPanel;
            return contentPanel;
        },
        _createContent: function(contentPanel, contentOptions) {
            [ "base", "ortho" ].forEach(function(type) {
                var typeAxes = this.axesByType[type];
                typeAxes && def.query(typeAxes).reverse().each(function(axis) {
                    this._createAxisPanel(axis);
                }, this);
            }, this);
            this.base(contentPanel, {
                clickAction: contentOptions.clickAction,
                doubleClickAction: contentOptions.doubleClickAction
            });
        },
        _createFocusWindow: function() {
            if (this.selectableByFocusWindow()) {
                var fwData, fw = this.focusWindow;
                fw && (fwData = fw._exportData());
                fw = this.focusWindow = new pvc.visual.CartesianFocusWindow(this);
                fwData && fw._importData(fwData);
                fw._initFromOptions();
            } else this.focusWindow && delete this.focusWindow;
        },
        _createAxisPanel: function(axis) {
            var opts = axis.option;
            if (opts("Visible")) {
                var titlePanel, state, panel = this.axesPanels[axis.id];
                if (opts("TitleVisible")) {
                    var titlePanel = panel && panel.titlePanel;
                    titlePanel && this._preserveLayout && (state = titlePanel._getLayoutState());
                    titlePanel = new pvc.AxisTitlePanel(this, this.contentPanel, axis, {
                        title: opts("Title"),
                        font: opts("TitleFont") || opts("Font"),
                        anchor: opts("Position"),
                        align: opts("TitleAlign"),
                        margins: state ? state.margins : opts("TitleMargins"),
                        paddings: state ? state.paddings : opts("TitlePaddings"),
                        size: state ? state.size : opts("TitleSize"),
                        sizeMax: opts("TitleSizeMax")
                    });
                }
                state = panel && this._preserveLayout ? panel._getLayoutState() : void 0;
                var panel = new pvc.AxisPanel(this, this.contentPanel, axis, {
                    anchor: opts("Position"),
                    size: state ? state.size : opts("Size"),
                    margins: state && state.margins,
                    paddings: state && state.paddings,
                    sizeMax: opts("SizeMax"),
                    clickAction: opts("ClickAction"),
                    doubleClickAction: opts("DoubleClickAction"),
                    useCompositeAxis: opts("Composite"),
                    font: opts("Font"),
                    labelSpacingMin: opts("LabelSpacingMin"),
                    grid: opts("Grid"),
                    gridCrossesMargin: opts("GridCrossesMargin"),
                    ruleCrossesMargin: opts("RuleCrossesMargin"),
                    zeroLine: opts("ZeroLine"),
                    showTicks: opts("Ticks"),
                    showMinorTicks: opts("MinorTicks")
                });
                titlePanel && (panel.titlePanel = titlePanel);
                this.axesPanels[axis.id] = panel;
                this.axesPanels[axis.orientedId] = panel;
                axis.v1SecondOrientedId && (this[axis.v1SecondOrientedId + "AxisPanel"] = panel);
                return panel;
            }
        },
        _onLaidOut: function() {
            this.plotPanelList && this.plotPanelList[0] && this._eachCartAxis(this._setCartAxisScaleRange, this);
        },
        _setCartAxisScaleRange: function(axis) {
            var info = this.plotPanelList[0]._layoutInfo, size = info.clientSize, a_size = "x" === axis.orientation ? size.width : size.height;
            axis.setScaleRange(a_size);
            axis.scale && !axis.scale.isNull && axis.setTicks(axis.ticks);
            return axis.scale;
        },
        _getAxesRoundingOverflow: function() {
            function setSide(side, valueNew, locked) {
                var value = axesPaddings[side];
                if (null == value || valueNew > value) {
                    axesPaddings[side] = valueNew;
                    axesPaddings[side + "Locked"] = locked;
                } else locked && (axesPaddings[side + "Locked"] = locked);
            }
            var axesPaddings = {};
            this._eachCartAxis(function(axis) {
                var overflow = axis.getRoundingOverflow();
                if (overflow) {
                    var isX = "x" === axis.orientation;
                    setSide(isX ? "left" : "bottom", overflow.begin, overflow.beginLocked);
                    setSide(isX ? "right" : "top", overflow.end, overflow.endLocked);
                }
            });
            return axesPaddings;
        },
        _getContinuousVisibleExtentConstrained: function(axis) {
            return "ortho" === axis.type && 1 == axis.role.isNormalized ? {
                min: 0,
                max: 100,
                minLocked: !0,
                maxLocked: !0,
                lengthLocked: !0
            } : this.base(axis);
        },
        _coordinateSmallChartsLayout: function(scopesByType) {
            this.base(scopesByType);
            var titleOrthoLen, titleSizeMax = 0, axisIds = null, sizesMaxByAxisId = {};
            this.children.forEach(function(childChart) {
                childChart.basePanel.layout();
                var size, panel = childChart.titlePanel;
                if (panel) {
                    titleOrthoLen || (titleOrthoLen = panel.anchorOrthoLength());
                    size = panel[titleOrthoLen];
                    size > titleSizeMax && (titleSizeMax = size);
                }
                var axesPanels = childChart.axesPanels;
                axisIds || (axisIds = def.query(def.ownKeys(axesPanels)).where(function(alias) {
                    return alias === axesPanels[alias].axis.id;
                }).select(function(id) {
                    sizesMaxByAxisId[id] = {
                        axis: 0,
                        title: 0
                    };
                    return id;
                }).array());
                axisIds.forEach(function(id) {
                    var axisPanel = axesPanels[id], sizes = sizesMaxByAxisId[id], ol = "x" === axisPanel.axis.orientation ? "height" : "width";
                    size = axisPanel[ol];
                    size > sizes.axis && (sizes.axis = size);
                    var titlePanel = axisPanel.titlePanel;
                    if (titlePanel) {
                        size = titlePanel[ol];
                        size > sizes.title && (sizes.title = size);
                    }
                });
            }, this);
            this.children.forEach(function(childChart) {
                if (titleSizeMax > 0) {
                    var panel = childChart.titlePanel;
                    panel.size = panel.size.clone().set(titleOrthoLen, titleSizeMax);
                }
                var axesPanels = childChart.axesPanels;
                axisIds.forEach(function(id) {
                    var axisPanel = axesPanels[id], sizes = sizesMaxByAxisId[id], ol = "x" === axisPanel.axis.orientation ? "height" : "width";
                    axisPanel.size = axisPanel.size.clone().set(ol, sizes.axis);
                    var titlePanel = axisPanel.titlePanel;
                    titlePanel && (titlePanel.size = titlePanel.size.clone().set(ol, sizes.title));
                });
                childChart.basePanel.invalidateLayout();
            }, this);
        },
        markEventDefaults: {
            strokeStyle: "#5BCBF5",
            lineWidth: "0.5",
            textStyle: "#5BCBF5",
            verticalOffset: 10,
            verticalAnchor: "bottom",
            horizontalAnchor: "right",
            forceHorizontalAnchor: !1,
            horizontalAnchorSwapLimit: 80,
            font: "10px sans-serif"
        },
        markEvent: function(sourceValue, label, options) {
            var me = this, baseAxis = me.axes.base, orthoAxis = me.axes.ortho, baseRole = baseAxis.role, baseScale = baseAxis.scale, baseDim = me.data.owner.dimensions(baseRole.grouping.lastDimensionName());
            if (baseAxis.isDiscrete()) {
                me.log.warn("Can only mark events in charts with a continuous base scale.");
                return me;
            }
            var o = $.extend({}, me.markEventDefaults, options), pseudoAtom = baseDim.read(sourceValue, label), basePos = baseScale(pseudoAtom.value), baseRange = baseScale.range(), baseEndPos = baseRange[1];
            if (basePos < baseRange[0] || basePos > baseEndPos) {
                me.log.warn("Cannot mark event because it is outside the base scale's domain.");
                return me;
            }
            var pvPanel = this.plotPanelList[0].pvPanel, h = orthoAxis.scale.range()[1], ha = o.horizontalAnchor;
            if (!o.forceHorizontalAnchor) {
                var alignRight = "right" === ha, availableSize = alignRight ? baseEndPos - basePos : basePos, labelSize = pv.Text.measureWidth(pseudoAtom.label, o.font);
                labelSize > availableSize && (ha = alignRight ? "left" : "right");
            }
            var topPos = "top" === o.verticalAnchor ? o.verticalOffset : h - o.verticalOffset, line = pvPanel.add(pv.Line).data([ 0, h ]).bottom(def.identity).left(basePos).lineWidth(o.lineWidth).strokeStyle(o.strokeStyle);
            line.anchor(ha).visible(function() {
                return !this.index;
            }).top(topPos).add(pv.Label).font(o.font).text(pseudoAtom.label).textStyle(o.textStyle);
            return me;
        },
        defaults: {
            timeSeries: !1,
            timeSeriesFormat: "%Y-%m-%d"
        }
    });
    def.type("pvc.CartesianGridDockingPanel", pvc.GridDockingPanel).init(function(chart, parent, options) {
        this.base(chart, parent, options);
        this._plotBgPanel = new pvc.PlotBgPanel(chart, this);
    }).add({
        _getFillSizeMin: function() {
            var plotSizeMin = this.chart.parent ? null : this.chart.options.plotSizeMin;
            return null != plotSizeMin ? pvc_Size.to(plotSizeMin) : null;
        },
        _createCore: function(layoutInfo) {
            var chart = this.chart, axes = chart.axes, xAxis = axes.x, yAxis = axes.y;
            xAxis.isBound() || (xAxis = null);
            yAxis.isBound() || (yAxis = null);
            xAxis && xAxis.option("Grid") && (this.xGridRule = this._createGridRule(xAxis));
            yAxis && yAxis.option("Grid") && (this.yGridRule = this._createGridRule(yAxis));
            this.base(layoutInfo);
            chart.focusWindow && this._createFocusWindow(layoutInfo);
            var plotFrameVisible = chart.compatVersion() > 1 ? def.get(chart.options, "plotFrameVisible", !0) : !(!xAxis.option("EndLine") && !yAxis.option("EndLine"));
            plotFrameVisible && (this.pvFrameBar = this._createFrame(layoutInfo, axes));
            xAxis && "numeric" === xAxis.scaleType && xAxis.option("ZeroLine") && (this.xZeroLine = this._createZeroLine(xAxis, layoutInfo));
            yAxis && "numeric" === yAxis.scaleType && yAxis.option("ZeroLine") && (this.yZeroLine = this._createZeroLine(yAxis, layoutInfo));
        },
        _createGridRule: function(axis) {
            var scale = axis.scale;
            if (!scale.isNull) {
                var isDiscrete = axis.role.grouping.isDiscrete(), rootScene = this._getAxisGridRootScene(axis);
                if (rootScene) {
                    var wrapper, margins = this._layoutInfo.gridMargins, paddings = this._layoutInfo.gridPaddings, tick_a = "x" === axis.orientation ? "left" : "bottom", len_a = this.anchorLength(tick_a), obeg_a = this.anchorOrtho(tick_a), oend_a = this.anchorOpposite(obeg_a), mainPlot = this.chart.plotPanelList[0], tick_offset = "x" === axis.orientation ? (mainPlot.position.left || 0) + paddings.left : (mainPlot.position.bottom || 0) + paddings.bottom, obeg = margins[obeg_a], oend = margins[oend_a], tickScenes = rootScene.leafs().array(), tickCount = tickScenes.length;
                    isDiscrete && tickCount && tickScenes.push(tickScenes[tickCount - 1]);
                    this.compatVersion() <= 1 && (wrapper = function(v1f) {
                        return function(tickScene) {
                            return v1f.call(this, tickScene.vars.tick.rawValue);
                        };
                    });
                    var pvGridRule = new pvc.visual.Rule(this, this.pvPanel, {
                        extensionId: axis.extensionPrefixes.map(function(prefix) {
                            return prefix + "Grid";
                        }),
                        wrapper: wrapper
                    }).lock("data", tickScenes).lock(len_a, null).override("defaultColor", def.fun.constant(pv.color("#f0f0f0"))).pvMark.antialias(!0)[obeg_a](obeg)[oend_a](oend).zOrder(-12).events("none");
                    if (isDiscrete) {
                        var halfStep = scale.range().step / 2;
                        pvGridRule[tick_a](function(tickScene) {
                            var tickPosition = tick_offset + scale(tickScene.vars.tick.value), isLastLine = this.index === tickCount;
                            return tickPosition + (isLastLine ? halfStep : -halfStep);
                        });
                    } else pvGridRule[tick_a](function(tickScene) {
                        return tick_offset + scale(tickScene.vars.tick.value);
                    });
                    return pvGridRule;
                }
            }
        },
        _getAxisGridRootScene: function(axis) {
            var isDiscrete = axis.isDiscrete(), data = isDiscrete ? axis.domainData() : this.data, rootScene = new pvc.visual.CartesianAxisRootScene(null, {
                panel: this,
                source: data
            });
            if (isDiscrete) data.childNodes.forEach(function(tickData) {
                new pvc.visual.CartesianAxisTickScene(rootScene, {
                    source: tickData,
                    tick: tickData.value,
                    tickRaw: tickData.rawValue,
                    tickLabel: tickData.label
                });
            }); else {
                var ticks = axis.ticks || axis.calcContinuousTicks();
                ticks.forEach(function(majorTick, index) {
                    new pvc.visual.CartesianAxisTickScene(rootScene, {
                        tick: majorTick,
                        tickRaw: majorTick,
                        tickLabel: axis.scale.tickFormat(majorTick, index)
                    });
                }, this);
            }
            return rootScene;
        },
        _createFrame: function(layoutInfo, axes) {
            if (!axes.base.scale.isNull && (!axes.ortho.scale.isNull || axes.ortho2 && !axes.ortho2.scale.isNull)) {
                var margins = layoutInfo.gridMargins, left = margins.left, right = margins.right, top = margins.top, bottom = margins.bottom, extensionIds = [];
                this.compatVersion() <= 1 && extensionIds.push("xAxisEndLine", "yAxisEndLine");
                extensionIds.push("plotFrame");
                return new pvc.visual.Panel(this, this.pvPanel, {
                    extensionId: extensionIds
                }).pvMark.lock("left", left).lock("right", right).lock("top", top).lock("bottom", bottom).lock("fillStyle", null).events("none").strokeStyle("#666666").lineWidth(1).antialias(!1).zOrder(-8);
            }
        },
        _createZeroLine: function(axis, layoutInfo) {
            var scale = axis.scale;
            if (!scale.isNull) {
                var domain = scale.domain();
                if (domain[0] * domain[1] < -1e-12) {
                    var a = "x" === axis.orientation ? "left" : "bottom", len_a = this.anchorLength(a), obeg_a = this.anchorOrtho(a), oend_a = this.anchorOpposite(obeg_a), margins = layoutInfo.gridMargins, paddings = layoutInfo.gridPaddings, zeroPosition = margins[a] + paddings[a] + scale(0), obeg = margins[obeg_a], oend = margins[oend_a], rootScene = new pvc.visual.Scene(null, {
                        panel: this
                    });
                    return new pvc.visual.Rule(this, this.pvPanel, {
                        extensionId: axis.extensionPrefixes.map(function(prefix) {
                            return prefix + "ZeroLine";
                        })
                    }).lock("data", [ rootScene ]).lock(len_a, null).lock(obeg_a, obeg).lock(oend_a, oend).lock(a, zeroPosition).override("defaultColor", def.fun.constant(pv.color("#666666"))).pvMark.events("none").lineWidth(1).antialias(!0).zOrder(-9);
                }
            }
        },
        _createFocusWindow: function(layoutInfo) {
            function resetSceneY() {
                scene[a_y] = 0 - paddings[a_top];
                scene[a_dy] = h + paddings[a_top] + paddings[a_bottom];
            }
            function sceneProp(p) {
                return function() {
                    return scene[p];
                };
            }
            function boundLeft() {
                var begin = scene[a_x];
                return Math.max(0, Math.min(w, begin));
            }
            function boundWidth() {
                var begin = boundLeft(), end = scene[a_x] + scene[a_dx];
                end = Math.max(0, Math.min(w, end));
                return end - begin;
            }
            function addSelBox(panel, id) {
                return new pvc.visual.Bar(me, panel, {
                    extensionId: id,
                    normalStroke: !0,
                    noHover: !0,
                    noSelect: !0,
                    noClick: !0,
                    noDoubleClick: !0,
                    noTooltip: !0,
                    showsInteraction: !1
                }).pvMark.lock("data").lock("visible").lock(a_left, boundLeft).lock(a_width, boundWidth).lock(a_top, sceneProp(a_y)).lock(a_height, sceneProp(a_dy)).lock(a_bottom).lock(a_right).sign;
            }
            function onDrag() {
                var ev = arguments[arguments.length - 1], isEnd = "end" === ev.drag.phase;
                topRoot._selectingByRubberband = !isEnd;
                baseBgPanel.render();
                baseFgPanel.render();
                var pbeg = scene[a_x], pend = scene[a_x] + scene[a_dx];
                if (!isV) {
                    var temp = w - pbeg;
                    pbeg = w - pend;
                    pend = temp;
                }
                focusWindow._updatePosition(pbeg, pend, isEnd, !0);
            }
            function positionConstraint(drag, op) {
                var l, target, m = drag.m, p = m[a_p], l0 = scene[a_dp];
                switch (op) {
                  case "new":
                    l = 0;
                    target = "begin";
                    break;

                  case "resize-begin":
                    l = scene[a_p] + l0 - p;
                    target = "begin";
                    break;

                  case "move":
                    l = l0;
                    target = "begin";
                    break;

                  case "resize-end":
                    l = p - scene[a_p];
                    target = "end";
                }
                var min = drag.min[a_p], max = drag.max[a_p], oper = {
                    type: op,
                    target: target,
                    point: p,
                    length: l,
                    length0: l0,
                    min: min,
                    max: max,
                    minView: 0,
                    maxView: w
                };
                focusWindow._constraintPosition(oper);
                m[a_p] = oper.point;
                switch (op) {
                  case "new":
                    oper.length !== l && (drag[a_dp + "min"] = l = oper.length);
                    break;

                  case "resize-begin":
                    oper.max = Math.min(oper.max, scene[a_p] + scene[a_dp]);
                    break;

                  case "resize-end":
                    oper.min = Math.max(oper.min, scene[a_p]);
                }
                drag.min[a_p] = oper.min;
                drag.max[a_p] = oper.max;
            }
            var me = this, topRoot = me.topRoot, chart = me.chart, focusWindow = chart.focusWindow.base, axis = focusWindow.axis, scale = axis.scale;
            if (!scale.isNull) {
                var resizable = focusWindow.option("Resizable"), movable = focusWindow.option("Movable"), isDiscrete = axis.isDiscrete(), isV = chart.isOrientationVertical(), a_left = isV ? "left" : "top", a_top = isV ? "top" : "left", a_width = me.anchorOrthoLength(a_left), a_right = me.anchorOpposite(a_left), a_height = me.anchorOrthoLength(a_top), a_bottom = me.anchorOpposite(a_top), a_x = isV ? "x" : "y", a_dx = "d" + a_x, a_y = isV ? "y" : "x", a_dy = "d" + a_y, margins = layoutInfo.gridMargins, paddings = layoutInfo.gridPaddings, space = {
                    left: margins.left + paddings.left,
                    right: margins.right + paddings.right,
                    top: margins.top + paddings.top,
                    bottom: margins.bottom + paddings.bottom
                }, clientSize = layoutInfo.clientSize, wf = clientSize[a_width], hf = clientSize[a_height];
                space.width = space.left + space.right;
                space.height = space.top + space.bottom;
                var w = wf - space[a_width], h = hf - space[a_height], padLeft = paddings[a_left], padRight = paddings[a_right], scene = new pvc.visual.Scene(null, {
                    panel: this
                }), band = isDiscrete ? scale.range().step : 0, halfBand = band / 2;
                scene[a_x] = scale(focusWindow.begin) - halfBand;
                scene[a_dx] = band + (scale(focusWindow.end) - halfBand) - scene[a_x];
                resetSceneY();
                var baseBgPanel = this._plotBgPanel.pvPanel.borderPanel;
                baseBgPanel.lock("data", [ scene ]);
                movable && resizable ? baseBgPanel.paddingPanel.events("all").cursor("crosshair").event("mousedown", pv.Behavior.select().autoRender(!1).collapse(isV ? "y" : "x").positionConstraint(function(drag) {
                    var op = "start" === drag.phase ? "new" : "resize-end";
                    return positionConstraint(drag, op);
                })).event("selectstart", function() {
                    resetSceneY();
                    onDrag.apply(null, arguments);
                }).event("select", onDrag).event("selectend", onDrag) : baseBgPanel.paddingPanel.events("all");
                var focusBg = addSelBox(baseBgPanel.paddingPanel, "focusWindowBg").override("defaultColor", def.fun.constant(pvc.invisibleFill)).pvMark;
                movable ? focusBg.events("all").cursor("move").event("mousedown", pv.Behavior.drag().autoRender(!1).collapse(isV ? "y" : "x").positionConstraint(function(drag) {
                    positionConstraint(drag, "move");
                })).event("drag", onDrag).event("dragend", onDrag) : focusBg.events("none");
                var baseFgPanel = new pvc.visual.Panel(me, me.pvPanel).pvMark.lock("data", [ scene ]).lock("visible").lock("fillStyle", pvc.invisibleFill).lock("left", space.left).lock("right", space.right).lock("top", space.top).lock("bottom", space.bottom).lock("zOrder", 10).events(function() {
                    var drag = scene.drag;
                    return drag && "end" !== drag.phase ? "all" : "none";
                }).cursor(function() {
                    var drag = scene.drag;
                    return drag && "end" !== drag.phase ? "drag" === drag.type || "select" === drag.type && !resizable ? "move" : isV ? "ew-resize" : "ns-resize" : null;
                }).antialias(!1), curtainFillColor = "rgba(20, 20, 20, 0.1)";
                new pvc.visual.Bar(me, baseFgPanel, {
                    extensionId: "focusWindowBaseCurtain",
                    normalStroke: !0,
                    noHover: !0,
                    noSelect: !0,
                    noClick: !0,
                    noDoubleClick: !0,
                    noTooltip: !0,
                    showsInteraction: !1
                }).override("defaultColor", function(scene, type) {
                    return "stroke" === type ? null : curtainFillColor;
                }).pvMark.lock("data", [ scene, scene ]).lock("visible").events("none").lock(a_left, function() {
                    return this.index ? boundLeft() + boundWidth() : -padLeft;
                }).lock(a_right, function() {
                    return this.index ? -padRight : null;
                }).lock(a_width, function() {
                    return this.index ? null : padLeft + boundLeft();
                }).lock(a_top, sceneProp(a_y)).lock(a_height, sceneProp(a_dy)).lock(a_bottom);
                var selectBoxFg = addSelBox(baseFgPanel, "focusWindow").override("defaultColor", def.fun.constant(null)).pvMark.events("none"), addResizeSideGrip = function(side) {
                    var a_begin = "left" === side || "top" === side ? "begin" : "end", opposite = me.anchorOpposite(side), fillColor = "linear-gradient(to " + opposite + ", " + curtainFillColor + ", #444 90%)", grip = new pvc.visual.Bar(me, selectBoxFg.anchor(side), {
                        extensionId: focusWindow.id + "Grip" + def.firstUpperCase(a_begin),
                        normalStroke: !0,
                        noHover: !0,
                        noSelect: !0,
                        noClick: !0,
                        noDoubleClick: !0,
                        noTooltip: !0,
                        showsInteraction: !1
                    }).override("defaultColor", function(scene, type) {
                        return "stroke" === type ? null : fillColor;
                    }).pvMark.lock("data").lock("visible")[a_top](scene[a_y])[a_height](scene[a_dy]);
                    if (resizable) {
                        var opId = "resize-" + a_begin;
                        grip.events("all")[a_width](5).cursor(isV ? "ew-resize" : "ns-resize").event("mousedown", pv.Behavior.resize(side).autoRender(!1).positionConstraint(function(drag) {
                            positionConstraint(drag, opId);
                        }).preserveOrtho(!0)).event("resize", onDrag).event("resizeend", onDrag);
                    } else grip.events("none")[a_width](1);
                    return grip;
                };
                addResizeSideGrip(a_left);
                addResizeSideGrip(a_right);
                var a_p = a_x, a_dp = a_dx;
            }
        },
        _getDatumsOnRect: function(datumMap, rect, keyArgs) {
            var xDatumMap, yDatumMap, chart = this.chart, xAxisPanel = chart.axesPanels.x, yAxisPanel = chart.axesPanels.y;
            if (xAxisPanel) {
                xDatumMap = new def.Map();
                xAxisPanel._getDatumsOnRect(xDatumMap, rect, keyArgs);
                xDatumMap.count || (xDatumMap = null);
            }
            if (yAxisPanel) {
                yDatumMap = new def.Map();
                yAxisPanel._getOwnDatumsOnRect(yDatumMap, rect, keyArgs);
                yDatumMap.count || (yDatumMap = null);
            }
            if (xDatumMap && yDatumMap) {
                xDatumMap.intersect(yDatumMap, datumMap);
                keyArgs.toggle = !0;
            } else xDatumMap ? datumMap.copy(xDatumMap) : yDatumMap ? datumMap.copy(yDatumMap) : chart.plotPanelList.forEach(function(plotPanel) {
                plotPanel._getDatumsOnRect(datumMap, rect, keyArgs);
            }, this);
        }
    });
    def.type("pvc.CartesianAbstractPanel", pvc.PlotPanel).init(function(chart, parent, plot, options) {
        function addAxis(axis) {
            axes[axis.type] = axis;
            axes[axis.orientedId] = axis;
            axis.v1SecondOrientedId && (axes[axis.v1SecondOrientedId] = axis);
        }
        this.base(chart, parent, plot, options);
        var axes = this.axes;
        addAxis(chart._getAxis("base", plot.option("BaseAxis") - 1));
        addAxis(chart._getAxis("ortho", plot.option("OrthoAxis") - 1));
    }).add({
        _getOptionSizeMin: function(chart) {
            return null;
        },
        _calcLayout: function(layoutInfo) {
            var clientSize, chart = this.chart, clientSizeInfo = chart._plotsClientSizeInfo;
            if (clientSizeInfo) {
                clientSize = layoutInfo.clientSize;
                var clientSizeFix = clientSizeInfo.value, clientSizeMin = clientSizeInfo.min, clientSizeMax = clientSizeInfo.max;
                null != clientSizeFix.width ? clientSize.width = clientSizeFix.width : clientSize.width = Math.max(Math.min(clientSize.width, clientSizeMax.width), clientSizeMin.width);
                null != clientSizeFix.height ? clientSize.height = clientSizeFix.height : clientSize.height = Math.max(Math.min(clientSize.height, clientSizeMax.height), clientSizeMin.height);
            }
            chart._preserveLayout || (layoutInfo.contentOverflow = this._calcContentOverflow(layoutInfo));
            return clientSize;
        },
        _calcContentOverflow: function(li) {
            var contentOverflow, offsetPads = this.chart._axisOffsetPaddings;
            if (offsetPads) {
                var tickRoundPads = this.chart._getAxesRoundingOverflow();
                pvc_Sides.names.forEach(function(side) {
                    if (!tickRoundPads[side + "Locked"]) {
                        var len_a = pvc.BasePanel.orthogonalLength[side], offLen = li.size[len_a] * (offsetPads[side] || 0), roundLen = tickRoundPads[side] || 0;
                        (contentOverflow || (contentOverflow = {}))[side] = Math.max(offLen - roundLen, 0);
                    }
                }, this);
            }
            return contentOverflow;
        },
        _createCore: function() {
            this.pvPanel.zOrder(-10);
            var contentOverflow = this.chart.options.leafContentOverflow || "auto", hideOverflow = "auto" === contentOverflow ? this._guessHideOverflow() : "hidden" === contentOverflow;
            hideOverflow && this.pvPanel.borderPanel.overflow("hidden");
        },
        _guessHideOverflow: function() {
            return cartPlotPanel_axisMayOverflow(this.axes.ortho) || cartPlotPanel_axisMayOverflow(this.axes.base);
        }
    });
    def("pvc.visual.CategoricalPlot", pvc.visual.CartesianPlot.extend({
        methods: {
            createVisibleData: function(baseData, ka) {
                var serRole = this.visualRoles.series, serGrouping = serRole && serRole.flattenedGrouping(), catGrouping = this.visualRole("category").flattenedGrouping();
                return serGrouping ? baseData.groupBy(def.get(ka, "inverted", !1) ? [ serGrouping, catGrouping ] : [ catGrouping, serGrouping ], ka) : baseData.groupBy(catGrouping, ka);
            },
            interpolatable: function() {
                return !0;
            },
            _initVisualRoles: function() {
                this.base();
                this._addVisualRole("category", this._getCategoryRoleSpec());
            },
            _getCategoryRoleSpec: function() {
                return {
                    isRequired: !0,
                    defaultDimension: "category*",
                    autoCreateDimension: !0
                };
            },
            getContinuousVisibleCellExtent: function(chart, valueAxis, valueDataCell) {
                var valueRole = valueDataCell.role;
                switch (valueRole.name) {
                  case "series":
                  case "category":
                    return this.base(chart, valueAxis, valueDataCell);
                }
                if (valueDataCell.plot !== this) throw def.error.operationInvalid("DataCell not of this plot.");
                chart._warnSingleContinuousValueRole(valueRole);
                var dataPartValue = valueDataCell.dataPartValue, valueDimName = valueRole.lastDimensionName(), data = chart.visiblePlotData(this, dataPartValue), useAbs = valueAxis.scaleUsesAbs();
                return "ortho" === valueAxis.type && valueDataCell.isStacked ? data.children().select(function(catGroup) {
                    var range = this._getStackedCategoryValueExtent(catGroup, valueDimName, useAbs);
                    return range ? {
                        range: range,
                        group: catGroup
                    } : void 0;
                }, this).where(def.notNully).reduce(function(result, rangeInfo) {
                    return this._reduceStackedCategoryValueExtent(chart, result, rangeInfo.range, rangeInfo.group, valueAxis, valueDataCell);
                }.bind(this), null) : data.leafs().select(function(serGroup) {
                    var value = serGroup.dimensions(valueDimName).value();
                    return useAbs && 0 > value ? -value : value;
                }).range();
            },
            _getStackedCategoryValueExtent: function(catGroup, valueDimName, useAbs) {
                var posSum = null, negSum = null;
                catGroup.children().select(function(serGroup) {
                    var value = serGroup.dimensions(valueDimName).value();
                    return useAbs && 0 > value ? -value : value;
                }).each(function(value) {
                    null != value && (value >= 0 ? posSum += value : negSum += value);
                });
                return null == posSum && null == negSum ? null : {
                    max: posSum || 0,
                    min: negSum || 0
                };
            },
            _reduceStackedCategoryValueExtent: function(chart, result, catRange, catGroup, valueAxis, valueDataCell) {
                return pvc.unionExtents(result, catRange);
            },
            _getBaseRole: function() {
                return this.visualRoles.category;
            },
            _getOrthoRoles: function() {
                return [ this.visualRoles.value ];
            },
            interpolateDataCell: function(dataCell, baseData) {
                var InterpType = this._getNullInterpolationOperType(dataCell.nullInterpolationMode);
                if (InterpType) {
                    this.chart._warnSingleContinuousValueRole(dataCell.role);
                    var partValue = dataCell.dataPartValue, partData = this.chart.partData(partValue, baseData), visibleData = this.chart.visiblePlotData(this, partValue, {
                        baseData: baseData
                    });
                    visibleData.childCount() > 0 && new InterpType(baseData, partData, visibleData, this.visualRoles.category, this.visualRoles.series, dataCell.role, !0).interpolate();
                }
            },
            _getNullInterpolationOperType: function(nim) {
                switch (nim) {
                  case "linear":
                    return cdo.LinearInterpolationOper;

                  case "zero":
                    return cdo.ZeroInterpolationOper;

                  case "none":
                    break;

                  default:
                    throw def.error.argumentInvalid("nullInterpolationMode", "" + nim);
                }
            },
            generateTrendsDataCell: function(newDatums, dataCell, baseData) {
                function genSeriesTrend(serData1) {
                    var funX = isXDiscrete ? null : function(allCatData) {
                        return allCatData.atoms[xDimName].value;
                    }, funY = function(allCatData) {
                        var group = data.child(allCatData.key);
                        group && serData1 && (group = group.child(serData1.key));
                        return group ? group.dimensions(yDimName).value(sumKeyArgs) : null;
                    }, options = def.create(trendOptions, {
                        rows: def.query(allCatDatas),
                        x: funX,
                        y: funY
                    }), trendModel = trendInfo.model(options);
                    trendModel && allCatDatas.forEach(function(allCatData, index) {
                        var trendX = isXDiscrete ? index : allCatData.atoms[xDimName].value, trendY = trendModel.sample(trendX, funY(allCatData), index);
                        if (null != trendY) {
                            var atoms, catData = data.child(allCatData.key), efCatData = catData || allCatData;
                            if (serData1) {
                                var catSerData = catData && catData.child(serData1.key);
                                if (catSerData) atoms = Object.create(catSerData._datums[0].atoms); else {
                                    atoms = Object.create(efCatData._datums[0].atoms);
                                    def.copyOwn(atoms, serData1.atoms);
                                }
                            } else atoms = Object.create(efCatData._datums[0].atoms);
                            atoms[yDimName] = trendY;
                            atoms[dataPartDimName] = dataPartAtom;
                            newDatums.push(new cdo.TrendDatum(efCatData.owner, atoms, trendOptions));
                        }
                    });
                }
                var serRole = this.visualRoles.series, xRole = this.visualRoles.category, yRole = dataCell.role, trendOptions = dataCell.trend, trendInfo = trendOptions.info;
                this.chart._warnSingleContinuousValueRole(yRole);
                var xDimName, yDimName = yRole.lastDimensionName(), isXDiscrete = xRole.isDiscrete();
                isXDiscrete || (xDimName = xRole.lastDimensionName());
                var sumKeyArgs = {
                    zeroIfNone: !1
                }, partData = this.chart.partData(dataCell.dataPartValue, baseData), data = this.chart.visiblePlotData(this, dataCell.dataPartValue, {
                    baseData: baseData
                }), dataPartAtom = this.chart._getTrendDataPartAtom(), dataPartDimName = dataPartAtom.dimension.name, allCatDatas = xRole.flatten(baseData, {
                    visible: !0
                }).childNodes, qVisibleSeries = serRole && serRole.isBound() ? serRole.flatten(partData, {
                    visible: !0
                }).children() : def.query([ null ]);
                qVisibleSeries.each(genSeriesTrend);
            }
        },
        options: {
            Stacked: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !1
            }
        }
    }));
    def.type("pvc.CategoricalAbstract", pvc.CartesianAbstract).add({
        _onWillCreatePlotPanelScene: function(plotPanel, data, axisSeriesDatas, axisCategDatas) {}
    });
    def.type("pvc.CategoricalAbstractPanel", pvc.CartesianAbstractPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.stacked = plot.option("Stacked");
    }).add({
        _buildScene: function(data, axisSeriesDatas, axisCategDatas) {
            this.chart._onWillCreatePlotPanelScene(this, data, axisSeriesDatas, axisCategDatas);
            return this._buildSceneCore(data, axisSeriesDatas, axisCategDatas);
        }
    });
    def("pvc.visual.MetricXYPlot", pvc.visual.CartesianPlot.extend({
        methods: {
            _initVisualRoles: function() {
                this.base();
                this._addVisualRole("x", {
                    isMeasure: !0,
                    isRequired: !0,
                    requireSingleDimension: !0,
                    requireIsDiscrete: !1,
                    defaultDimension: "x",
                    dimensionDefaults: {
                        valueType: this.chart.options.timeSeries ? Date : Number
                    }
                });
                this._addVisualRole("y", {
                    isMeasure: !0,
                    isRequired: !0,
                    requireSingleDimension: !0,
                    requireIsDiscrete: !1,
                    defaultDimension: "y",
                    dimensionDefaults: {
                        valueType: Number
                    }
                });
            },
            _getBaseRole: function() {
                return this.visualRoles.x;
            },
            _getOrthoRoles: function() {
                return [ this.visualRoles.y ];
            },
            generateTrendsDataCell: function(newDatums, dataCell, baseData) {
                function genSeriesTrend(serData) {
                    var funX = function(datum) {
                        return datum.atoms[xDimName].value;
                    }, funY = function(datum) {
                        return datum.atoms[yDimName].value;
                    }, datums = serData.datums().sort(null, funX).array(), options = def.create(trendOptions, {
                        rows: def.query(datums),
                        x: funX,
                        y: funY
                    }), trendModel = trendInfo.model(options);
                    trendModel && datums.forEach(function(datum, index) {
                        var trendY, trendX = funX(datum);
                        if (trendX && null != (trendY = trendModel.sample(trendX, funY(datum), index))) {
                            var atoms = def.set(Object.create(serData.atoms), xDimName, trendX, yDimName, trendY, dataPartDimName, dataPartAtom);
                            newDatums.push(new cdo.TrendDatum(data.owner, atoms, trendOptions));
                        }
                    });
                }
                var serRole = this.visualRoles.series, xRole = this.visualRoles.x, yRole = dataCell.role, trendOptions = dataCell.trend, trendInfo = trendOptions.info;
                this.chart._warnSingleContinuousValueRole(yRole);
                var xDimName = xRole.lastDimensionName(), yDimName = yRole.lastDimensionName(), data = this.chart.visiblePlotData(this, dataCell.dataPartValue, {
                    baseData: baseData
                }), dataPartAtom = this.chart._getTrendDataPartAtom(), dataPartDimName = dataPartAtom.dimension.name;
                (serRole.isBound() ? data.children() : def.query([ data ])).each(genSeriesTrend);
            }
        },
        options: {
            OrthoAxis: {
                resolve: null
            }
        }
    }));
    def.type("pvc.MetricXYAbstract", pvc.CartesianAbstract).add({
        _defaultAxisBandSizeRatio: 1
    });
    def.type("pvc.BarAbstract", pvc.CategoricalAbstract);
    def("pvc.visual.BarPlotAbstract", pvc.visual.CategoricalPlot.extend({
        methods: {
            _initVisualRoles: function() {
                this.base();
                this._addVisualRole("value", {
                    isMeasure: !0,
                    isRequired: !0,
                    isPercent: this.option("Stacked"),
                    isNormalized: this.option("ValuesNormalized"),
                    requireSingleDimension: !0,
                    requireIsDiscrete: !1,
                    valueType: Number,
                    defaultDimension: "value"
                });
            },
            _getCategoryRoleSpec: function() {
                return def.set(this.base(), "requireIsDiscrete", !0);
            }
        },
        options: {
            BarSizeRatio: {
                resolve: "_resolveFull",
                cast: function(value) {
                    value = def.number.to(value);
                    return null == value ? 1 : .05 > value ? .05 : value > 1 ? 1 : value;
                },
                value: .9
            },
            BarSizeMax: {
                resolve: "_resolveFull",
                data: {
                    resolveV1: function(optionInfo) {
                        return this._specifyChartOption(optionInfo, "maxBarSize"), !0;
                    }
                },
                cast: function(value) {
                    value = def.number.to(value);
                    return null == value ? 1 / 0 : 1 > value ? 1 : value;
                },
                value: 2e3
            },
            BarOrthoSizeMin: {
                resolve: "_resolveFull",
                cast: def.number.toNonNegative,
                value: 1.5
            },
            BarStackedMargin: {
                resolve: "_resolveFull",
                cast: function(value) {
                    value = def.number.to(value);
                    return null != value && 0 > value ? 0 : value;
                },
                value: 0
            },
            OverflowMarkersVisible: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !0
            },
            ValuesAnchor: {
                value: "center"
            },
            ValuesNormalized: {
                resolve: "_resolveFull",
                cast: "boolean",
                "default": !1
            }
        }
    }));
    def.type("pvc.BarAbstractPanel", pvc.CategoricalAbstractPanel).add({
        pvBar: null,
        pvBarLabel: null,
        pvCategoryPanel: null,
        pvSecondLine: null,
        pvSecondDot: null,
        _creating: function() {
            var colorDataCell = this.plot.dataCellsByRole.color[0];
            if (!colorDataCell.legendSymbolRenderer() && colorDataCell.legendVisible()) {
                var colorAxis = this.axes.color, drawLine = colorAxis.option("LegendDrawLine"), drawMarker = !drawLine || colorAxis.option("LegendDrawMarker");
                if (drawMarker) {
                    var extAbsPrefix = pvc.uniqueExtensionAbsPrefix();
                    this.chart._processExtensionPointsIn(colorDataCell.role.legend(), extAbsPrefix);
                    var keyArgs = {
                        drawMarker: !0,
                        markerShape: colorAxis.option("LegendShape"),
                        drawLine: drawLine,
                        extensionPrefix: {
                            abs: extAbsPrefix
                        },
                        markerPvProto: new pv.Dot()
                    };
                    this.extend(keyArgs.markerPvProto, "bar", {
                        constOnly: !0
                    });
                    colorDataCell.legendSymbolRenderer(keyArgs);
                }
            }
        },
        _createCore: function() {
            this.base();
            var barWidth, seriesCount, me = this, chart = me.chart, plot = me.plot, isStacked = !!me.stacked, isVertical = me.isOrientationVertical(), data = me.visibleData({
                ignoreNulls: !1
            }), orthoAxis = me.axes.ortho, baseAxis = me.axes.base, axisCategDatas = baseAxis.domainItems(), axisSeriesDatas = me.visualRoles.series.flatten(me.partData(), {
                visible: !0,
                isNull: chart.options.ignoreNulls ? !1 : null
            }).childNodes, rootScene = me._buildScene(data, axisSeriesDatas, axisCategDatas), orthoScale = orthoAxis.scale, orthoZero = orthoScale(0), sceneOrthoScale = orthoAxis.sceneScale({
                sceneVarName: "value",
                nullToZero: !1
            }), sceneBaseScale = baseAxis.sceneScale({
                sceneVarName: "category"
            }), barSizeRatio = plot.option("BarSizeRatio"), barSizeMax = plot.option("BarSizeMax"), barStackedMargin = plot.option("BarStackedMargin"), barOrthoSizeMin = plot.option("BarOrthoSizeMin"), baseRange = baseAxis.scale.range(), bandWidth = baseRange.band, barStepWidth = baseRange.step, barMarginWidth = baseRange.margin, reverseSeries = isVertical === isStacked;
            if (isStacked) barWidth = bandWidth; else {
                seriesCount = axisSeriesDatas.length;
                barWidth = seriesCount ? 1 === seriesCount ? bandWidth : barSizeRatio * bandWidth / seriesCount : 0;
            }
            barWidth > barSizeMax && (barWidth = barSizeMax);
            me.barWidth = barWidth;
            me.barStepWidth = barStepWidth;
            var wrapper;
            me.compatVersion() <= 1 && (wrapper = function(v1f) {
                return function(scene) {
                    var markParent = Object.create(this.parent), mark = Object.create(this);
                    mark.parent = markParent;
                    var serIndex = scene.parent.childIndex(), catIndex = scene.childIndex();
                    if (isStacked) {
                        markParent.index = serIndex;
                        mark.index = catIndex;
                    } else {
                        markParent.index = catIndex;
                        mark.index = serIndex;
                    }
                    return v1f.call(mark, scene.vars.value.rawValue);
                };
            });
            me.pvBarPanel = new pvc.visual.Panel(me, me.pvPanel, {
                panelType: pv.Layout.Band,
                extensionId: "panel"
            }).lock("layers", rootScene.childNodes).lockMark("values", function(seriesScene) {
                return seriesScene.childNodes;
            }).lockMark("orient", isVertical ? "bottom-left" : "left-bottom").lockMark("layout", isStacked ? "stacked" : "grouped").lockMark("verticalMode", me._barVerticalMode()).lockMark("yZero", orthoZero).optionalMark("hZero", barOrthoSizeMin).pvMark.band.x(sceneBaseScale).w(bandWidth).differentialControl(me._barDifferentialControl()).item.order(reverseSeries ? "reverse" : null).h(function(scene) {
                var y = sceneOrthoScale(scene);
                return null != y ? chart.animate(0, y - orthoZero) : null;
            }).w(barWidth).horizontalRatio(barSizeRatio).verticalMargin(barStackedMargin).end;
            var widthNeedsAntialias = 4 >= barWidth || 2 > barMarginWidth, pvBar = this.pvBar = new pvc.visual.Bar(me, me.pvBarPanel.item, {
                extensionId: "bar",
                freePosition: !0,
                wrapper: wrapper
            }).lockDimensions().optional("visible", function(scene) {
                return null != scene.getValue();
            }).pvMark.antialias(function(scene) {
                if (widthNeedsAntialias) return !0;
                var y = sceneOrthoScale(scene), h = null == y ? 0 : Math.abs(y - orthoZero);
                return 1e-8 > h;
            });
            plot.option("OverflowMarkersVisible") && this._addOverflowMarkers(wrapper);
            var label = pvc.visual.ValueLabel.maybeCreate(me, pvBar, {
                wrapper: wrapper
            });
            if (label) {
                var labelBarOrthoLen;
                if (label.hideOrTrimOverflowed) {
                    labelBarOrthoLen = bandWidth;
                    !isStacked && seriesCount > 1 && (labelBarOrthoLen /= seriesCount);
                }
                me.pvBarLabel = label.override("calcTextFitInfo", function(scene, text) {
                    var pvLabel = this.pvMark, tm = pvLabel.textMargin();
                    if (!(-1e-6 > tm)) {
                        var a = pvLabel.textAngle(), sinAngle = Math.sin(a), isHorizText = Math.abs(sinAngle) < 1e-6, isVertiText = !isHorizText && Math.abs(Math.cos(a)) < 1e-6;
                        if (isHorizText || isVertiText && isVertical) {
                            var twMax, isInside, isTaCenter, h = pvBar.height(), w = pvBar.width(), ml = isVertical ? h : w, al = isVertical ? w : h, m = pv.Text.measure(text, pvLabel.font()), th = .75 * m.height, tw = m.width, va = pvLabel.name(), tb = pvLabel.textBaseline(), ta = pvLabel.textAlign(), isVaCenter = "center" === va, hide = !1;
                            if (isVertical) if (isHorizText) {
                                isInside = isVaCenter || va === tb;
                                if (!isInside) return;
                                hide |= isVaCenter && "middle" !== tb ? th + tm > ml / 2 : th + 2 * tm > ml;
                            } else {
                                hide |= th > ml;
                                isTaCenter = "center" === ta;
                                isInside = isVaCenter;
                                isInside || isTaCenter || (isInside = sinAngle >= 1e-6 ? "left" === ta ? "top" === va : "bottom" === va : "left" === ta ? "bottom" === va : "top" === va);
                                if (isInside) {
                                    twMax = !isVaCenter || isTaCenter ? ml - 2 * tm : (ml - tm) / 2;
                                    hide |= ("middle" === tb ? th > al : th > al / 2) || this.hideOverflowed && tw > twMax;
                                } else hide |= th >= Math.max(al, labelBarOrthoLen);
                            } else {
                                hide |= th > ml;
                                isInside = isVaCenter || va === ta;
                                if (isInside) {
                                    twMax = isVaCenter && "center" !== ta ? (ml - tm) / 2 : ml - 2 * tm;
                                    hide |= ("middle" === tb ? th > al : th > al / 2) || this.hideOverflowed && tw > twMax;
                                } else hide |= th >= Math.max(al, labelBarOrthoLen);
                            }
                            return {
                                hide: hide,
                                widthMax: twMax
                            };
                        }
                    }
                }).pvMark;
            }
        },
        _barVerticalMode: function() {
            return this.plot.option("ValuesNormalized") ? "expand" : null;
        },
        _barDifferentialControl: function() {
            return null;
        },
        _getV1Datum: function(scene) {
            var datum = scene.datum;
            if (datum) {
                var datumEx = Object.create(datum);
                datumEx.percent = scene.vars.value.percent;
                datum = datumEx;
            }
            return datum;
        },
        _guessHideOverflow: function() {
            return this.base() || !this.stacked && !this.axes.ortho.option("OriginIsZero");
        },
        _addOverflowMarkers: function(wrapper) {
            var orthoAxis = this.axes.ortho, originIsZero = this.stacked || orthoAxis.option("OriginIsZero");
            originIsZero && null == orthoAxis.option("FixedMax") || (this.pvOverflowMarker = this._addOverflowMarker(!1, orthoAxis.scale, wrapper));
            originIsZero && null == orthoAxis.option("FixedMin") || (this.pvUnderflowMarker = this._addOverflowMarker(!0, orthoAxis.scale, wrapper));
        },
        _addOverflowMarker: function(isMin, orthoScale, wrapper) {
            var angle, isVertical = this.isOrientationVertical(), a_bottom = isVertical ? "bottom" : "left", a_top = this.anchorOpposite(a_bottom), a_height = this.anchorOrthoLength(a_bottom), a_width = this.anchorLength(a_bottom), orthoSizeMinHalf = this.plot.option("BarOrthoSizeMin") / 2, paddings = this._layoutInfo.paddings, botBound = orthoScale.min - paddings[a_bottom], topBound = orthoScale.max + paddings[a_top], orthoBound = isMin ? botBound : topBound;
            angle = isMin ? isVertical ? 0 : Math.PI / 2 : isVertical ? Math.PI : -Math.PI / 2;
            return new pvc.visual.Dot(this, this.pvBar.anchor("center"), {
                noSelect: !0,
                noHover: !0,
                noClick: !0,
                noDoubleClick: !0,
                noTooltip: !1,
                freePosition: !0,
                extensionId: isMin ? "underflowMarker" : "overflowMarker",
                wrapper: wrapper
            }).intercept("visible", function(scene) {
                var visible = this.delegateExtension();
                if (void 0 !== visible && !visible) return !1;
                var value = scene.vars.value.value;
                if (null == value) return !1;
                var targetInstance = this.pvMark.scene.target[this.pvMark.index], botPos = targetInstance[a_bottom], topPos = botPos + targetInstance[a_height], valuePos = 0 > value ? botPos : topPos, hasOverflow = isMin ? botBound >= topPos || botBound > valuePos : botPos >= topBound || valuePos > topBound;
                if (!hasOverflow) return !1;
                if (0 !== value) return !0;
                var orthoOverflow = isMin ? botBound - topPos : topPos - topBound;
                return orthoOverflow > orthoSizeMinHalf;
            }).pvMark.shape("triangle").shapeRadius(function() {
                return Math.min(Math.sqrt(10), this.scene.target[this.index][a_width] / 2);
            }).shapeAngle(angle).lineWidth(1.5).strokeStyle("red").fillStyle("white")[a_top](null)[a_bottom](function() {
                return orthoBound + (isMin ? 1 : -1) * (this.shapeRadius() + 2);
            });
        },
        renderInteractive: function() {
            this.pvPanel.render();
        },
        _buildSceneCore: function(data, axisSeriesDatas, axisCategDatas) {
            function createSeriesScene(axisSeriesData) {
                var seriesScene = new pvc.visual.Scene(rootScene, {
                    source: axisSeriesData
                }), seriesKey = axisSeriesData.key;
                seriesScene.vars.series = pvc_ValueLabelVar.fromComplex(axisSeriesData);
                colorVarHelper.onNewScene(seriesScene, !1);
                axisCategDatas.forEach(function(axisCategData) {
                    var categData = data.child(axisCategData.key), group = categData && categData.child(seriesKey), scene = new pvc.visual.Scene(seriesScene, {
                        source: group
                    }), categVar = scene.vars.category = pvc_ValueLabelVar.fromComplex(categData);
                    categVar.group = categData;
                    valueVarHelper.onNewScene(scene, !0);
                    colorVarHelper.onNewScene(scene, !0);
                });
            }
            var rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: data
            }), roles = this.visualRoles, valueVarHelper = new pvc.visual.RoleVarHelper(rootScene, "value", roles.value, {
                hasPercentSubVar: this.stacked
            }), colorVarHelper = new pvc.visual.RoleVarHelper(rootScene, "color", roles.color);
            axisSeriesDatas.forEach(createSeriesScene);
            return rootScene;
        }
    });
    pv.PieSlice = function() {
        pv.Wedge.call(this);
    };
    pv.PieSlice.prototype = pv.extend(pv.Wedge).property("offsetRadius");
    var normAngle = pv.Shape.normalizeAngle;
    pv.PieSlice.prototype.midAngle = function() {
        var instance = this.instance();
        return normAngle(instance.startAngle) + normAngle(instance.angle) / 2;
    };
    pv.PieSlice.prototype.defaults = new pv.PieSlice().extend(pv.Wedge.prototype.defaults).offsetRadius(0);
    def("pvc.visual.PieSlice", pvc.visual.Sign.extend({
        init: function(panel, protoMark, keyArgs) {
            var pvMark = protoMark.add(pv.PieSlice);
            keyArgs = def.setDefaults(keyArgs, "freeColor", !1);
            this.base(panel, pvMark, keyArgs);
            this._activeOffsetRadius = def.get(keyArgs, "activeOffsetRadius", 0);
            this._maxOffsetRadius = def.get(keyArgs, "maxOffsetRadius", 0);
            this._resolvePctRadius = def.get(keyArgs, "resolvePctRadius");
            this._center = def.get(keyArgs, "center");
            this.optional("lineWidth", .6)._bindProperty("angle", "angle")._bindProperty("offsetRadius", "offsetRadius")._lockDynamic("bottom", "y")._lockDynamic("left", "x").lock("top", null).lock("right", null);
        },
        properties: [ "offsetRadius" ],
        methods: {
            angle: def.fun.constant(0),
            x: function() {
                return this._center.x + this._offsetSlice("cos");
            },
            y: function() {
                return this._center.y - this._offsetSlice("sin");
            },
            _offsetSlice: function(fun) {
                var offset = this.pvMark.offsetRadius() || 0;
                offset && (offset *= Math[fun](this.pvMark.midAngle()));
                return offset;
            },
            defaultColor: function(scene, type) {
                return "stroke" === type ? null : this.base(scene, type);
            },
            interactiveColor: function(scene, color, type) {
                if (this.mayShowActive(scene, !0)) switch (type) {
                  case "fill":
                    return color.brighter(.2).alpha(.8);

                  case "stroke":
                    return color.brighter(1.3).alpha(.7);
                } else if (this.mayShowNotAmongSelected(scene) && "fill" === type) return this.dimColor(color, type);
                return this.base(scene, color, type);
            },
            offsetRadius: function(scene) {
                var offsetRadius = this.base(scene);
                return Math.min(Math.max(0, offsetRadius), this._maxOffsetRadius);
            },
            baseOffsetRadius: function(scene) {
                var offsetRadius = this.base(scene) || 0;
                return this._resolvePctRadius(pvc_PercentValue.parse(offsetRadius));
            },
            interactiveOffsetRadius: function(scene, offsetRadius) {
                return offsetRadius + (this.mayShowActive(scene, !0) ? this._activeOffsetRadius : 0);
            }
        }
    }));
    def("pvc.visual.PiePlot", pvc.visual.Plot.extend({
        init: function(chart, keyArgs) {
            this.base(chart, keyArgs);
            if (!(chart instanceof pvc.PieChart)) throw def.error(def.format("Plot type '{0}' can only be used from within a pie chart.", [ this.type ]));
        },
        methods: {
            type: "pie",
            _initVisualRoles: function() {
                this.base();
                this._addVisualRole("category", {
                    isRequired: !0,
                    defaultDimension: "category*",
                    autoCreateDimension: !0
                });
                this._addVisualRole("value", {
                    isMeasure: !0,
                    isRequired: !0,
                    isPercent: !0,
                    requireSingleDimension: !0,
                    requireIsDiscrete: !1,
                    valueType: Number,
                    defaultDimension: "value"
                });
            },
            _getColorRoleSpec: function() {
                return {
                    isRequired: !0,
                    defaultSourceRole: "category",
                    defaultDimension: "color*",
                    requireIsDiscrete: !0
                };
            },
            createVisibleData: function(baseData, ka) {
                return this.visualRoles.category.flatten(baseData, ka);
            },
            _initDataCells: function() {
                this.base();
                var dataPartValue = this.option("DataPart");
                this._addDataCell(new pvc.visual.DataCell(this, "category", this.index, this.visualRole("category"), dataPartValue));
                this._addDataCell(new pvc.visual.DataCell(this, "angle", this.index, this.visualRoles.value, dataPartValue));
            }
        },
        options: {
            ActiveSliceRadius: {
                resolve: "_resolveFull",
                cast: pvc_PercentValue.parse,
                value: new pvc_PercentValue(.05)
            },
            ExplodedSliceRadius: {
                resolve: "_resolveFull",
                cast: pvc_PercentValue.parse,
                value: 0
            },
            ExplodedSliceIndex: {
                resolve: "_resolveFull",
                cast: def.number.to,
                value: null
            },
            ValuesAnchor: {
                cast: pvc.parseAnchorWedge,
                value: "outer"
            },
            ValuesVisible: {
                value: !0
            },
            ValuesLabelStyle: {
                resolve: function(optionInfo) {
                    return this.chart.compatVersion() > 1 ? this._resolveFull(optionInfo) : (optionInfo.specify("inside"), 
                    !0);
                },
                cast: function(value) {
                    switch (value) {
                      case "inside":
                      case "linked":
                        return value;
                    }
                    def.debug >= 2 && def.log("[Warning] Invalid 'ValuesLabelStyle' value: '" + value + "'.");
                    return "linked";
                },
                value: "linked"
            },
            ValuesMask: {
                resolve: "_resolveFull",
                data: {
                    resolveDefault: function(optionInfo) {
                        optionInfo.defaultValue("linked" === this.option("ValuesLabelStyle") ? "{value} ({value.percent})" : "{value}");
                        return !0;
                    }
                }
            },
            LinkInsetRadius: {
                resolve: "_resolveFull",
                cast: pvc_PercentValue.parse,
                value: new pvc_PercentValue(.05)
            },
            LinkOutsetRadius: {
                resolve: "_resolveFull",
                cast: pvc_PercentValue.parse,
                value: new pvc_PercentValue(.025)
            },
            LinkMargin: {
                resolve: "_resolveFull",
                cast: pvc_PercentValue.parse,
                value: new pvc_PercentValue(.025)
            },
            LinkHandleWidth: {
                resolve: "_resolveFull",
                cast: def.number.to,
                value: .5
            },
            LinkLabelSize: {
                resolve: "_resolveFull",
                cast: pvc_PercentValue.parse,
                value: new pvc_PercentValue(.15)
            },
            LinkLabelSpacingMin: {
                resolve: "_resolveFull",
                cast: def.number.to,
                value: .5
            }
        }
    }));
    pvc.visual.Plot.registerClass(pvc.visual.PiePlot);
    def.type("pvc.PiePanel", pvc.PlotPanel).init(function(chart, parent, plot, options) {
        var labelStyle = plot.option("ValuesLabelStyle");
        this.base(chart, parent, plot, options);
        this.explodedOffsetRadius = plot.option("ExplodedSliceRadius");
        this.explodedSliceIndex = plot.option("ExplodedSliceIndex");
        this.activeOffsetRadius = plot.option("ActiveSliceRadius");
        this.labelStyle = labelStyle;
        if ("linked" === labelStyle) {
            this.linkInsetRadius = plot.option("LinkInsetRadius");
            this.linkOutsetRadius = plot.option("LinkOutsetRadius");
            this.linkMargin = plot.option("LinkMargin");
            this.linkHandleWidth = plot.option("LinkHandleWidth");
            this.linkLabelSize = plot.option("LinkLabelSize");
            this.linkLabelSpacingMin = plot.option("LinkLabelSpacingMin");
        }
        chart.pieChartPanel || (chart.pieChartPanel = this);
        this.axes.category = chart._getAxis("category", plot.index);
        this.axes.angle = chart._getAxis("angle", plot.index);
    }).add({
        plotType: "pie",
        _ibits: -1,
        pvPie: null,
        pvPieLabel: null,
        valueRoleName: "value",
        _getV1Datum: function(scene) {
            var datum = scene.datum;
            if (datum) {
                var datumEx = Object.create(datum);
                datumEx.percent = scene.vars.value.percent;
                datum = datumEx;
            }
            return datum;
        },
        _calcLayout: function(layoutInfo) {
            function resolvePercentRadius(radius) {
                return def.between(pvc_PercentValue.resolve(radius, clientRadius), 0, clientRadius);
            }
            function resolvePercentWidth(width) {
                return def.between(pvc_PercentValue.resolve(width, clientWidth), 0, clientWidth);
            }
            var clientSize = layoutInfo.clientSize, clientWidth = clientSize.width, clientRadius = Math.min(clientWidth, clientSize.height) / 2;
            if (!clientRadius) return new pvc_Size(0, 0);
            var center = pv.vector(clientSize.width / 2, clientSize.height / 2), labelFont = this._getConstantExtension("label", "font");
            def.string.is(labelFont) || (labelFont = this.valuesFont);
            var maxPieRadius = clientRadius;
            if (this.valuesVisible && "linked" === this.labelStyle) {
                var textMargin = def.number.to(this._getConstantExtension("label", "textMargin"), 3), textHeight = pv.Text.fontHeight(labelFont), linkHandleWidth = this.linkHandleWidth * textHeight, linkInsetRadius = resolvePercentRadius(this.linkInsetRadius), linkOutsetRadius = resolvePercentRadius(this.linkOutsetRadius), linkMargin = resolvePercentWidth(this.linkMargin) + linkHandleWidth, linkLabelSize = resolvePercentWidth(this.linkLabelSize), linkLabelSpacingMin = this.linkLabelSpacingMin * textHeight, freeWidthSpace = Math.max(0, clientWidth / 2 - clientRadius), spaceH = Math.max(0, linkOutsetRadius + linkMargin + linkLabelSize - freeWidthSpace), spaceV = linkOutsetRadius + textHeight, linkAndLabelRadius = Math.max(0, spaceV, spaceH);
                if (linkAndLabelRadius >= maxPieRadius) {
                    this.valuesVisible = !1;
                    def.debug >= 2 && this.log("Hiding linked labels due to insufficient space.");
                } else {
                    maxPieRadius -= linkAndLabelRadius;
                    layoutInfo.link = {
                        insetRadius: linkInsetRadius,
                        outsetRadius: linkOutsetRadius,
                        elbowRadius: maxPieRadius + linkOutsetRadius,
                        linkMargin: linkMargin,
                        handleWidth: linkHandleWidth,
                        labelSize: linkLabelSize,
                        maxTextWidth: linkLabelSize - textMargin,
                        labelSpacingMin: linkLabelSpacingMin,
                        textMargin: textMargin,
                        lineHeight: textHeight
                    };
                }
            }
            var explodedOffsetRadius = resolvePercentRadius(this.explodedOffsetRadius), activeOffsetRadius = 0;
            this.hoverable() && (activeOffsetRadius = resolvePercentRadius(this.activeOffsetRadius));
            var maxOffsetRadius = explodedOffsetRadius + activeOffsetRadius, normalPieRadius = maxPieRadius - maxOffsetRadius;
            if (0 > normalPieRadius) return new pvc_Size(0, 0);
            layoutInfo.resolvePctRadius = resolvePercentRadius;
            layoutInfo.center = center;
            layoutInfo.clientRadius = clientRadius;
            layoutInfo.normalRadius = normalPieRadius;
            layoutInfo.explodedOffsetRadius = explodedOffsetRadius;
            layoutInfo.activeOffsetRadius = activeOffsetRadius;
            layoutInfo.maxOffsetRadius = maxOffsetRadius;
            layoutInfo.labelFont = labelFont;
        },
        _createCore: function(layoutInfo) {
            var wrapper, me = this, chart = me.chart, rootScene = this._buildScene(), center = layoutInfo.center, normalRadius = layoutInfo.normalRadius, extensionIds = [ "slice" ];
            if (this.compatVersion() <= 1) {
                extensionIds.push("");
                wrapper = function(v1f) {
                    return function(pieCatScene) {
                        return v1f.call(this, pieCatScene.vars.value.value);
                    };
                };
            }
            this.pvPie = new pvc.visual.PieSlice(this, this.pvPanel, {
                extensionId: extensionIds,
                center: center,
                activeOffsetRadius: layoutInfo.activeOffsetRadius,
                maxOffsetRadius: layoutInfo.maxOffsetRadius,
                resolvePctRadius: layoutInfo.resolvePctRadius,
                wrapper: wrapper,
                tooltipArgs: {
                    options: {
                        useCorners: !0,
                        gravity: function() {
                            var ma = this.midAngle(), isRightPlane = Math.cos(ma) >= 0, isTopPlane = Math.sin(ma) >= 0;
                            return isRightPlane ? isTopPlane ? "nw" : "sw" : isTopPlane ? "ne" : "se";
                        }
                    }
                }
            }).lock("data", rootScene.childNodes).override("angle", function(scene) {
                return scene.vars.value.angle;
            }).override("defaultOffsetRadius", function() {
                var explodeIndex = me.explodedSliceIndex;
                return null == explodeIndex || explodeIndex == this.pvMark.index ? layoutInfo.explodedOffsetRadius : 0;
            }).optionalMark("outerRadius", function() {
                return chart.animate(0, normalRadius);
            }).localProperty("innerRadiusEx", pvc_PercentValue.parse).intercept("innerRadius", function(scene) {
                var innerRadius = this.delegateExtension();
                if (null == innerRadius) {
                    var innerRadiusPct = this.pvMark.innerRadiusEx();
                    innerRadius = null != innerRadiusPct ? pvc_PercentValue.resolve(innerRadiusPct, this.pvMark.outerRadius()) || 0 : 0;
                }
                return innerRadius > 0 ? chart.animate(0, innerRadius) : 0;
            }).pvMark;
            if (this.valuesVisible) {
                this.valuesFont = layoutInfo.labelFont;
                if ("inside" === this.labelStyle) this.pvPieLabel = pvc.visual.ValueLabel.maybeCreate(this, this.pvPie, {
                    wrapper: wrapper
                }).override("defaultText", function(scene) {
                    return scene.vars.value.sliceLabel;
                }).override("calcTextFitInfo", function(scene, text) {
                    var pvLabel = this.pvMark, tm = pvLabel.textMargin();
                    if (!(-1e-6 > tm)) {
                        var tb = pvLabel.textBaseline();
                        if ("middle" === tb) {
                            var sa = pvc.normAngle(me.pvPie.midAngle()), la = pvc.normAngle(pvLabel.textAngle()), sameAngle = Math.abs(sa - la) < 1e-6, oppoAngle = !1;
                            if (!sameAngle) {
                                var la2 = pvc.normAngle(la + Math.PI);
                                oppoAngle = Math.abs(sa - la2) < 1e-6;
                            }
                            if (sameAngle || oppoAngle) {
                                var va = pvLabel.name(), ta = pvLabel.textAlign(), canHandle = "outer" === va ? ta === (sameAngle ? "right" : "left") : !1;
                                if (canHandle) {
                                    var hide = !1, m = pv.Text.measure(text, pvLabel.font()), th = .85 * m.height, or = me.pvPie.outerRadius(), ir = me.pvPie.innerRadius(), a = scene.vars.value.angle, thEf = th + tm / 2, irmin = a < Math.PI ? Math.max(ir, thEf / (2 * Math.tan(a / 2))) : ir, twMax = or - tm - irmin;
                                    hide |= 0 >= twMax;
                                    twMax -= tm;
                                    hide |= this.hideOverflowed && m.width > twMax;
                                    return {
                                        hide: hide,
                                        widthMax: twMax
                                    };
                                }
                            }
                        }
                    }
                }).pvMark.textMargin(10); else if ("linked" === this.labelStyle) {
                    var linkLayout = layoutInfo.link;
                    rootScene.layoutLinkLabels(layoutInfo);
                    this.pvLinkPanel = this.pvPanel.add(pv.Panel).data(rootScene.childNodes).localProperty("pieSlice").pieSlice(function() {
                        return me.pvPie.scene[this.index];
                    });
                    var f = !1, t = !0;
                    this.pvLinkLine = new pvc.visual.Line(this, this.pvLinkPanel, {
                        extensionId: "linkLine",
                        freePosition: t,
                        noClick: t,
                        noDoubleClick: t,
                        noSelect: t,
                        noTooltip: t,
                        noHover: t,
                        showsActivity: f
                    }).lockMark("data", function(scene) {
                        var pieSlice = this.parent.pieSlice(), midAngle = pieSlice.startAngle + pieSlice.angle / 2, outerRadius = pieSlice.outerRadius - linkLayout.insetRadius, x = pieSlice.left + outerRadius * Math.cos(midAngle), y = pieSlice.top + outerRadius * Math.sin(midAngle), firstDotScene = scene.childNodes[0];
                        if (firstDotScene && firstDotScene._isFirstDynamicScene) {
                            firstDotScene.x = x;
                            firstDotScene.y = y;
                        } else {
                            firstDotScene = new pvc.visual.PieLinkLineScene(scene, x, y, 0);
                            firstDotScene._isFirstDynamicScene = t;
                        }
                        return scene.childNodes;
                    }).override("defaultColor", function(scene, type) {
                        return "stroke" === type ? "black" : this.base(scene, type);
                    }).override("defaultStrokeWidth", def.fun.constant(.5)).pvMark.lock("visible").lock("top", function(dot) {
                        return dot.y;
                    }).lock("left", function(dot) {
                        return dot.x;
                    });
                    this.pvPieLabel = new pvc.visual.Label(this, this.pvLinkPanel, {
                        extensionId: "label",
                        noClick: f,
                        noDoubleClick: f,
                        noSelect: f,
                        noHover: f,
                        showsInteraction: t
                    }).lockMark("data", function(scene) {
                        return scene.lineScenes;
                    }).intercept("textStyle", function(scene) {
                        this._finished = f;
                        var style = this.delegate();
                        style && !this._finished && !this.mayShowActive(scene) && this.mayShowNotAmongSelected(scene) && (style = this.dimColor(style, "text"));
                        return style;
                    }).pvMark.lock("visible").left(function(scene) {
                        return scene.vars.link.labelX;
                    }).top(function(scene) {
                        return scene.vars.link.labelY + (this.index + 1) * linkLayout.lineHeight;
                    }).textAlign(function(scene) {
                        return scene.vars.link.labelAnchor;
                    }).textMargin(linkLayout.textMargin).textBaseline("bottom").text(function(scene) {
                        return scene.vars.link.labelLines[this.index];
                    });
                    if (def.debug >= 20) {
                        this.pvPanel.add(pv.Panel).zOrder(-10).left(center.x - layoutInfo.clientRadius).top(center.y - layoutInfo.clientRadius).width(2 * layoutInfo.clientRadius).height(2 * layoutInfo.clientRadius).strokeStyle("red");
                        this.pvPanel.strokeStyle("green");
                        var linkColors = pv.Colors.category10();
                        this.pvLinkLine.segmented(t).strokeStyle(function() {
                            return linkColors(this.index);
                        });
                    }
                }
                this.pvPieLabel.font(layoutInfo.labelFont);
            }
        },
        renderInteractive: function() {
            this.pvPanel.render();
        },
        _buildScene: function() {
            var rootScene = new pvc.visual.PieRootScene(this);
            this.sum = rootScene.vars.sumAbs.value;
            return rootScene;
        }
    });
    pvc.PlotPanel.registerClass(pvc.PiePanel);
    def.type("pvc.visual.PieRootScene", pvc.visual.Scene).init(function(panel) {
        function formatValue(value, categData) {
            if (categData) {
                var datums = categData._datums;
                if (1 === datums.length) return datums[0].atoms[valueDimName].label;
            }
            return valueDim.format(value);
        }
        var categAxis = panel.axes.category, categRootData = categAxis.domainData();
        this.base(null, {
            panel: panel,
            source: categRootData
        });
        var colorVarHelper = new pvc.visual.RoleVarHelper(this, "color", panel.visualRoles.color), valueDimName = panel.visualRoles[panel.valueRoleName].lastDimensionName(), valueDim = categRootData.dimensions(valueDimName), pctValueFormat = panel.chart.options.percentValueFormat, angleScale = panel.axes.angle.scale, sumAbs = angleScale.isNull ? 0 : angleScale.domain()[1], rootScene = this;
        this.vars.sumAbs = new pvc_ValueLabelVar(sumAbs, formatValue(sumAbs));
        var CategSceneClass = def.type(pvc.visual.PieCategoryScene).init(function(categData, value) {
            this.base(rootScene, {
                source: categData
            });
            this.vars.category = pvc_ValueLabelVar.fromComplex(categData);
            var valueVar = new pvc_ValueLabelVar(value, formatValue(value, categData));
            valueVar.angle = angleScale(value);
            var percent = Math.abs(value) / sumAbs;
            valueVar.percent = new pvc_ValueLabelVar(percent, pctValueFormat(percent));
            this.vars.value = valueVar;
            valueVar.sliceLabel = this.sliceLabel();
            colorVarHelper.onNewScene(this, !0);
        });
        panel._extendSceneType("category", CategSceneClass, [ "sliceLabel", "sliceLabelMask" ]);
        var categDatas = categAxis.domainItems();
        if (categDatas.length) {
            categDatas.forEach(function(categData) {
                var value = categData.dimensions(valueDimName).value();
                0 !== value && new CategSceneClass(categData, value);
            });
            if (!rootScene.childNodes.length && !panel.chart.visualRoles.multiChart.isBound()) throw new pvc.InvalidDataException("Unable to create a pie chart, please check the data values.", "all-zero-data");
        }
    }).add({
        layoutLinkLabels: function(layoutInfo) {
            var startAngle = -Math.PI / 2, leftScenes = [], rightScenes = [];
            this.childNodes.forEach(function(categScene) {
                startAngle = categScene.layoutI(layoutInfo, startAngle);
                (categScene.vars.link.dir > 0 ? rightScenes : leftScenes).push(categScene);
            });
            this._distributeLabels(-1, leftScenes, layoutInfo);
            this._distributeLabels(1, rightScenes, layoutInfo);
        },
        _distributeLabels: function(dir, scenes, layoutInfo) {
            scenes.sort(function(sceneA, sceneB) {
                return def.compare(sceneA.vars.link.targetY, sceneB.vars.link.targetY);
            });
            this._distributeLabelsDownwards(scenes, layoutInfo) && this._distributeLabelsUpwards(scenes, layoutInfo) && this._distributeLabelsEvenly(scenes, layoutInfo);
            scenes.forEach(function(categScene) {
                categScene.layoutII(layoutInfo);
            });
        },
        _distributeLabelsDownwards: function(scenes, layoutInfo) {
            for (var linkLayout = layoutInfo.link, labelSpacingMin = linkLayout.labelSpacingMin, yMax = layoutInfo.clientSize.height, overlapping = !1, i = 0, J = scenes.length - 1; J > i; i++) {
                var linkVar0 = scenes[i].vars.link;
                !i && linkVar0.labelTop() < 0 && (overlapping = !0);
                var linkVar1 = scenes[i + 1].vars.link, labelTopMin1 = linkVar0.labelBottom() + labelSpacingMin;
                if (linkVar1.labelTop() < labelTopMin1) {
                    var halfLabelHeight1 = linkVar1.labelHeight / 2, targetY1 = labelTopMin1 + halfLabelHeight1, targetYMax = yMax - halfLabelHeight1;
                    if (targetY1 > targetYMax) {
                        overlapping = !0;
                        linkVar1.targetY = targetYMax;
                    } else linkVar1.targetY = targetY1;
                }
            }
            return overlapping;
        },
        _distributeLabelsUpwards: function(scenes, layoutInfo) {
            for (var linkLayout = layoutInfo.link, labelSpacingMin = linkLayout.labelSpacingMin, overlapping = !1, i = scenes.length - 1; i > 0; i--) {
                var linkVar1 = scenes[i - 1].vars.link, linkVar0 = scenes[i].vars.link;
                1 === i && linkVar1.labelTop() < 0 && (overlapping = !0);
                var labelBottomMax1 = linkVar0.labelTop() - labelSpacingMin;
                if (linkVar1.labelBottom() > labelBottomMax1) {
                    var halfLabelHeight1 = linkVar1.labelHeight / 2, targetY1 = labelBottomMax1 - halfLabelHeight1, targetYMin = halfLabelHeight1;
                    if (targetYMin > targetY1) {
                        overlapping = !0;
                        linkVar1.targetY = targetYMin;
                    } else linkVar1.targetY = targetY1;
                }
            }
            return overlapping;
        },
        _distributeLabelsEvenly: function(scenes, layoutInfo) {
            var totalHeight = 0;
            scenes.forEach(function(categScene) {
                totalHeight += categScene.vars.link.labelHeight;
            });
            var freeSpace = layoutInfo.clientSize.height - totalHeight, labelSpacing = freeSpace;
            scenes.length > 1 && (labelSpacing /= scenes.length - 1);
            var y = 0;
            scenes.forEach(function(scene) {
                var linkVar = scene.vars.link, halfLabelHeight = linkVar.labelHeight / 2;
                y += halfLabelHeight;
                linkVar.targetY = y;
                y += halfLabelHeight + labelSpacing;
            });
            return !0;
        }
    });
    def.type("pvc.visual.PieLinkLabelVar").add({
        labelTop: function() {
            return this.targetY - this.labelHeight / 2;
        },
        labelBottom: function() {
            return this.targetY + this.labelHeight / 2;
        }
    });
    def.type("pvc.visual.PieCategoryScene", pvc.visual.Scene).add({
        sliceLabelMask: function() {
            return this.panel().valuesMask;
        },
        sliceLabel: function() {
            return this.format(this.sliceLabelMask());
        },
        layoutI: function(layoutInfo, startAngle) {
            var valueVar = this.vars.value, endAngle = startAngle + valueVar.angle, midAngle = (startAngle + endAngle) / 2, linkVar = this.vars.link = new pvc.visual.PieLinkLabelVar(), linkLayout = layoutInfo.link, labelLines = pvc.text.justify(valueVar.sliceLabel, linkLayout.maxTextWidth, layoutInfo.labelFont), lineCount = labelLines.length;
            linkVar.labelLines = labelLines;
            linkVar.labelHeight = lineCount * linkLayout.lineHeight;
            this.lineScenes = def.array.create(lineCount, this);
            var cosMid = Math.cos(midAngle), sinMid = Math.sin(midAngle), isAtRight = cosMid >= 0, dir = isAtRight ? 1 : -1;
            linkVar.labelAnchor = isAtRight ? "left" : "right";
            var center = layoutInfo.center, elbowRadius = linkLayout.elbowRadius, elbowX = center.x + elbowRadius * cosMid, elbowY = center.y + elbowRadius * sinMid, anchorX = center.x + dir * elbowRadius, targetX = anchorX + dir * linkLayout.linkMargin;
            new pvc.visual.PieLinkLineScene(this, elbowX, elbowY);
            new pvc.visual.PieLinkLineScene(this, anchorX, elbowY);
            linkVar.elbowY = elbowY;
            linkVar.targetY = elbowY + 0;
            linkVar.targetX = targetX;
            linkVar.dir = dir;
            return endAngle;
        },
        layoutII: function(layoutInfo) {
            var linkVar = this.vars.link, targetY = linkVar.targetY, targetX = linkVar.targetX, handleWidth = layoutInfo.link.handleWidth;
            handleWidth > 0 && new pvc.visual.PieLinkLineScene(this, targetX - linkVar.dir * handleWidth, targetY);
            new pvc.visual.PieLinkLineScene(this, targetX, targetY);
            linkVar.labelX = targetX;
            linkVar.labelY = targetY - linkVar.labelHeight / 2;
        }
    });
    def.type("pvc.visual.PieLinkLineScene", pvc.visual.Scene).init(function(catScene, x, y, index) {
        this.base(catScene, {
            source: catScene.group,
            index: index
        });
        this.x = x;
        this.y = y;
    }).add(pv.Vector);
    def.type("pvc.PieChart", pvc.BaseChart).add({
        _axisClassByType: {
            category: pvc.visual.Axis,
            angle: pvc.visual.NormalizedAxis
        },
        _axisCreateChartLevel: {
            category: 2,
            angle: 2
        },
        _axisSetScaleChartLevel: {
            category: 2,
            angle: 2
        },
        _axisCreationOrder: function() {
            var a = pvc.BaseChart.prototype._axisCreationOrder.slice();
            a.push("category", "angle");
            return a;
        }(),
        _createPlotsInternal: function() {
            this._addPlot(new pvc.visual.PiePlot(this));
        },
        _setAxisScale: function(axis, chartLevel) {
            this.base(axis, chartLevel);
            2 & chartLevel && "angle" === axis.type && axis.setScaleRange({
                min: 0,
                max: 2 * Math.PI
            });
        },
        _createContentPanel: function(parentPanel, contentOptions) {
            var isV1Compat = this.compatVersion() <= 1;
            if (isV1Compat) {
                var innerGap = def.number.to(this.options.innerGap) || .95;
                innerGap = def.between(innerGap, .1, 1);
                contentOptions.paddings = (100 * (1 - innerGap) / 2).toFixed(2) + "%";
            } else null == contentOptions.paddings && (contentOptions.paddings = new pvc_PercentValue(.025));
            return this.base(parentPanel, contentOptions);
        },
        _createContent: function(parentPanel, contentOptions) {
            contentOptions.scenes = def.getPath(this.options, "pie.scenes");
            this.base(parentPanel, contentOptions);
        }
    });
    def("pvc.visual.BarPlot", pvc.visual.BarPlotAbstract.extend({
        methods: {
            type: "bar"
        }
    }));
    pvc.visual.Plot.registerClass(pvc.visual.BarPlot);
    def.type("pvc.BarPanel", pvc.BarAbstractPanel).add({
        plotType: "bar",
        _ibits: -1
    });
    pvc.PlotPanel.registerClass(pvc.BarPanel);
    def.type("pvc.BarChart", pvc.BarAbstract).add({
        _allowV1SecondAxis: !0,
        _createPlotsInternal: function() {
            this._createMainPlot();
            this.options.plot2 && this._addPlot(new pvc.visual.PointPlot(this, {
                name: "plot2",
                defaults: {
                    DataPart: "1",
                    ColorAxis: 2,
                    LinesVisible: !0,
                    DotsVisible: !0
                }
            }));
        },
        _createMainPlot: function() {
            this._addPlot(new pvc.visual.BarPlot(this));
        },
        _createPlotTrend: function() {
            this._addPlot(new pvc.visual.PointPlot(this, {
                name: "trend",
                spec: {
                    visualRoles: {
                        color: {
                            from: "series"
                        }
                    }
                },
                fixed: {
                    TrendType: "none",
                    NullInterpolatioMode: "none"
                },
                defaults: {
                    DataPart: "trend",
                    ColorAxis: 2,
                    LinesVisible: !0,
                    DotsVisible: !1
                }
            }));
        },
        _createContent: function(parentPanel, contentOptions) {
            this.base(parentPanel, contentOptions);
            var barPanel = this.plotPanels.bar, plot2Panel = this.plotPanels.plot2;
            if (plot2Panel && "point" === plot2Panel.plot.type) {
                if (barPanel) {
                    barPanel.pvSecondLine = plot2Panel.pvLine;
                    barPanel.pvSecondDot = plot2Panel.pvDot;
                }
                plot2Panel._applyV1BarSecondExtensions = !0;
            }
        }
    });
    def.type("pvc.NormalizedBarChart", pvc.BarChart).add({
        _createMainPlot: function() {
            this._addPlot(new pvc.visual.BarPlot(this, {
                fixed: {
                    ValuesNormalized: !0,
                    Stacked: !0
                }
            }));
        }
    });
    pvc.parseWaterDirection = function(value) {
        if (value) {
            value = ("" + value).toLowerCase();
            switch (value) {
              case "up":
              case "down":
                return value;
            }
            def.debug >= 2 && def.log("[Warning] Invalid 'WaterDirection' value: '" + value + "'.");
        }
    };
    def("pvc.visual.WaterfallPlot", pvc.visual.BarPlotAbstract.extend({
        init: function(chart, keyArgs) {
            this.base(chart, keyArgs);
            chart._registerInitLegendScenes(this._initLegendScenes.bind(this));
        },
        methods: {
            type: "water",
            _waterColor: pv.color("#1f77b4").darker(),
            _initEnd: function() {
                var extAbsId = pvc.makeExtensionAbsId("line", this.extensionPrefixes), strokeStyle = this.chart._getConstantExtension(extAbsId, "strokeStyle");
                strokeStyle && (this._waterColor = pv.color(strokeStyle));
                this.base();
            },
            _getCategoryRoleSpec: function() {
                var catRoleSpec = this.base(), travProp = this.isFalling() ? "FlattenDfsPre" : "FlattenDfsPost";
                catRoleSpec.traversalModes = pvc.visual.TraversalMode[travProp];
                catRoleSpec.rootLabel = this.option("AllCategoryLabel");
                return catRoleSpec;
            },
            isFalling: function() {
                return "down" === this.option("Direction");
            },
            _reduceStackedCategoryValueExtent: function(chart, result, catRange, catGroup, valueAxis, valueDataCell) {
                var offsetNext, offsetPrev = result ? result.offset : 0, offsetDelta = catRange.min + catRange.max;
                if (!result) {
                    if (catRange) {
                        offsetNext = offsetPrev + offsetDelta;
                        valueAxis.setDataCellScaleInfo(valueDataCell, [ {
                            offset: offsetNext,
                            group: catGroup,
                            range: catRange
                        } ]);
                        return {
                            min: catRange.min,
                            max: catRange.max,
                            offset: offsetNext
                        };
                    }
                    return null;
                }
                var isFalling = this.isFalling(), isProperGroup = catGroup._isFlattenGroup && !catGroup._isDegenerateFlattenGroup;
                if (isProperGroup) {
                    var deltaUp = -catRange.min;
                    if (deltaUp > 0) {
                        var top = offsetPrev + deltaUp;
                        top > result.max && (result.max = top);
                    }
                    var deltaDown = -catRange.max;
                    if (0 > deltaDown) {
                        var bottom = offsetPrev + deltaDown;
                        bottom < result.min && (result.min = bottom);
                    }
                } else {
                    var dir = isFalling ? -1 : 1;
                    offsetNext = result.offset = offsetPrev + dir * offsetDelta;
                    offsetNext > result.max ? result.max = offsetNext : offsetNext < result.min && (result.min = offsetNext);
                }
                valueAxis.getDataCellScaleInfo(valueDataCell).push({
                    offset: isFalling ? offsetPrev : result.offset,
                    group: catGroup,
                    range: catRange
                });
                return result;
            },
            _initLegendScenes: function(legendPanel) {
                var rootScene = legendPanel._getLegendRootScene();
                new pvc.visual.legend.WaterfallLegendGroupScene(rootScene, this, {
                    extensionPrefix: def.indexedId("", 1),
                    label: this.option("TotalLineLabel"),
                    color: this._waterColor
                });
            }
        },
        options: {
            Stacked: {
                resolve: null,
                value: !0
            },
            ValuesNormalized: {
                resolve: null,
                value: !1
            },
            TotalLineLabel: {
                resolve: "_resolveFull",
                cast: String,
                value: "Accumulated"
            },
            TotalValuesVisible: {
                resolve: "_resolveFull",
                data: {
                    resolveDefault: function(optionInfo) {
                        return optionInfo.defaultValue(this.option("ValuesVisible")), !0;
                    }
                },
                cast: Boolean
            },
            Direction: {
                resolve: "_resolveFull",
                cast: pvc.parseWaterDirection,
                value: "down"
            },
            AreasVisible: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !0
            },
            AllCategoryLabel: {
                resolve: "_resolveFull",
                cast: String,
                value: "All"
            }
        }
    }));
    pvc.visual.Plot.registerClass(pvc.visual.WaterfallPlot);
    def.type("pvc.visual.legend.WaterfallLegendGroupScene", pvc.visual.legend.LegendGroupScene).init(function(rootScene, plot, keyArgs) {
        keyArgs = def.set(keyArgs, "clickMode", "none");
        this.base(rootScene, keyArgs);
        this.plot = plot;
        this.createItem(keyArgs);
    }).add({
        itemSceneType: function() {
            return pvc.visual.legend.WaterfallLegendItemScene;
        }
    });
    def.type("pvc.visual.legend.WaterfallLegendItemScene", pvc.visual.legend.LegendItemScene).init(function(groupScene, keyArgs) {
        this.base.apply(this, arguments);
        var I = pvc.visual.Interactive;
        this._ibits = I.Interactive | I.ShowsInteraction;
        this.color = def.get(keyArgs, "color");
        this.vars.value = new pvc_ValueLabelVar(null, def.get(keyArgs, "label"));
    });
    def.type("pvc.WaterfallPanel", pvc.BarAbstractPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        chart.wfChartPanel || (chart.wfChartPanel = this);
    }).add({
        plotType: "water",
        _ibits: -1,
        pvWaterfallLine: null,
        ruleData: null,
        _barDifferentialControl: function() {
            var isFalling = this.plot.isFalling();
            return function(scene) {
                if (isFalling && !this.index) return 1;
                var group = scene.vars.category.group, isProperGroup = group._isFlattenGroup && !group._isDegenerateFlattenGroup;
                return isProperGroup ? -2 : isFalling ? -1 : 1;
            };
        },
        _creating: function() {
            var rootScene = this._getLegendRootScene();
            if (rootScene) {
                var waterfallGroupScene = def.query(rootScene.childNodes).first(function(groupScene) {
                    return groupScene.plot === this;
                }, this.plot);
                if (waterfallGroupScene && !waterfallGroupScene.hasRenderer()) {
                    var keyArgs = {
                        drawLine: !0,
                        drawMarker: !1,
                        rulePvProto: new pv.Rule()
                    };
                    this.extend(keyArgs.rulePvProto, "line", {
                        constOnly: !0
                    });
                    waterfallGroupScene.renderer(keyArgs);
                }
            }
        },
        _createCore: function() {
            this.base();
            var chart = this.chart, isVertical = this.isOrientationVertical(), anchor = isVertical ? "bottom" : "left", ao = this.anchorOrtho(anchor), orthoAxis = this.axes.ortho, baseAxis = this.axes.base, valueDataCell = this.plot.dataCellsByRole.value[0], ris = orthoAxis.getDataCellScaleInfo(valueDataCell), ruleRootScene = this._buildRuleScene(ris), orthoScale = orthoAxis.scale, orthoZero = orthoScale(0), sceneOrthoScale = orthoAxis.sceneScale({
                sceneVarName: "value"
            }), sceneBaseScale = baseAxis.sceneScale({
                sceneVarName: "category"
            }), baseScale = baseAxis.scale, barWidth2 = this.barWidth / 2, barWidth = this.barWidth, barStepWidth = this.barStepWidth, isFalling = this.plot.isFalling(), waterColor = this.plot._waterColor;
            if (this.plot.option("AreasVisible")) {
                var panelColors = pv.Colors.category10(), waterGroupRootScene = this._buildWaterGroupScene(ris), orthoRange = orthoScale.range(), orthoPanelMargin = .04 * (orthoRange[1] - orthoRange[0]);
                this.pvWaterfallGroupPanel = new pvc.visual.Panel(this, this.pvPanel, {
                    extensionId: "group"
                }).lock("data", waterGroupRootScene.childNodes).pvMark.zOrder(-1).fillStyle(function() {
                    return panelColors(0).alpha(.15);
                })[ao](function(scene) {
                    var c = scene.vars.category;
                    return baseScale(c.valueLeft) - barStepWidth / 2;
                })[this.anchorLength(anchor)](function(scene) {
                    var c = scene.vars.category, len = Math.abs(baseScale(c.valueRight) - baseScale(c.valueLeft));
                    return len + barStepWidth;
                })[anchor](function(scene) {
                    var v = scene.vars.value, b = orthoScale(v.valueBottom) - orthoPanelMargin / 2;
                    return chart.animate(orthoZero, b);
                })[this.anchorOrthoLength(anchor)](function(scene) {
                    var v = scene.vars.value, h = orthoScale(v.valueTop) - orthoScale(v.valueBottom) + orthoPanelMargin;
                    return chart.animate(0, h);
                });
            }
            this.pvBar.sign.override("baseColor", function(scene, type) {
                var color = this.base(scene, type);
                return "fill" !== type || scene.vars.category.group._isFlattenGroup ? color : pv.color(color).alpha(.5);
            });
            this.pvWaterfallLine = new pvc.visual.Rule(this, this.pvPanel, {
                extensionId: "line",
                noTooltip: !1,
                noHover: !1,
                noSelect: !1,
                noClick: !1,
                noDoubleClick: !1
            }).lock("data", ruleRootScene.childNodes).optional("visible", function(scene) {
                return isFalling && !!scene.previousSibling || !isFalling && !!scene.nextSibling;
            }).optional(anchor, function(scene) {
                return orthoZero + chart.animate(0, sceneOrthoScale(scene) - orthoZero);
            }).optional(this.anchorLength(anchor), barStepWidth + barWidth).optional(ao, isFalling ? function(scene) {
                return sceneBaseScale(scene) - barStepWidth - barWidth2;
            } : function(scene) {
                return sceneBaseScale(scene) - barWidth2;
            }).override("defaultColor", def.fun.constant(waterColor)).pvMark.antialias(!0).lineCap("butt");
            this.plot.option("TotalValuesVisible") && (this.pvWaterfallLabel = new pvc.visual.Label(this, this.pvWaterfallLine, {
                extensionId: "lineLabel"
            }).intercept("visible", function(scene) {
                return !scene.vars.category.group._isFlattenGroup && (isFalling || !!scene.nextSibling);
            }).pvMark[anchor](function(scene) {
                return orthoZero + chart.animate(0, sceneOrthoScale(scene) - orthoZero);
            })[this.anchorOrtho(anchor)](sceneBaseScale).textAlign(isVertical ? "center" : "left").textBaseline(function(categScene) {
                if (!isVertical) return "middle";
                var direction = categScene.vars.direction;
                if (null == direction) return "bottom";
                var isRising = !isFalling;
                return isRising === ("up" === direction) ? "bottom" : "top";
            }).textStyle(pv.Color.names.darkgray.darker(2)).textMargin(5).text(function(scene) {
                return scene.vars.value.label;
            }));
        },
        _buildRuleScene: function(ris) {
            function createCategScene(ruleInfo) {
                var categData1 = ruleInfo.group, categScene = new pvc.visual.Scene(rootScene, {
                    source: categData1
                }), categVar = categScene.vars.category = pvc_ValueLabelVar.fromComplex(categData1), value = ruleInfo.offset;
                categVar.group = categData1;
                categScene.vars.value = new pvc_ValueLabelVar(value, valueDim.format(value));
            }
            function completeCategScene(categScene, index) {
                var value = categScene.vars.value.value;
                categScene.vars.direction = index && prevValue !== value ? isClimbing === value > prevValue ? "up" : "down" : null;
                prevValue = value;
            }
            var prevValue, isClimbing, valueDim, rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: this.visibleData({
                    ignoreNulls: !1
                })
            });
            if (ris) {
                valueDim = this.chart.data.dimensions(this.visualRoles.value.lastDimensionName());
                ris.forEach(createCategScene, this);
                var q = def.query(rootScene.childNodes);
                isClimbing = !this.plot.isFalling();
                isClimbing || (q = q.reverse());
                q.each(completeCategScene, this);
            }
            return rootScene;
        },
        _buildWaterGroupScene: function(ris) {
            function createRectangleSceneRecursive(catData, level) {
                var q = catData.children().where(function(c) {
                    return "" !== c.key;
                });
                if (q.next()) {
                    level && createRectangleScene(catData, level);
                    level++;
                    do createRectangleSceneRecursive(q.item, level); while (q.next());
                }
            }
            function createRectangleScene(catData, level) {
                var rectScene = new pvc.visual.Scene(rootScene, {
                    source: catData
                }), categVar = rectScene.vars.category = pvc_ValueLabelVar.fromComplex(catData);
                categVar.group = catData;
                categVar.level = level;
                var leafData, leafRuleInfo, lc, rc, bv, valueVar = rectScene.vars.value = {}, ri = ruleInfoByCategKey[catData.absKey], offset = ri.offset, range = ri.range, height = -range.min + range.max;
                if (isFalling) {
                    leafData = lastLeaf(catData);
                    leafRuleInfo = ruleInfoByCategKey[leafData.absKey];
                    lc = ri.group.value;
                    rc = leafRuleInfo.group.value;
                    bv = offset - range.max;
                } else {
                    leafData = firstLeaf(catData);
                    leafRuleInfo = ruleInfoByCategKey[leafData.absKey];
                    lc = leafRuleInfo.group.value;
                    rc = ri.group.value;
                    bv = offset - range.max;
                }
                categVar.valueLeft = lc;
                categVar.valueRight = rc;
                valueVar.valueHeight = height;
                valueVar.valueBottom = bv;
                valueVar.valueTop = bv + height;
            }
            function firstLeaf(data) {
                var children = data.childNodes, first = children && children[0];
                return first ? firstLeaf(first) : data;
            }
            function lastLeaf(data) {
                var children = data.childNodes, last = children && children[children.length - 1];
                return last ? lastLeaf(last) : data;
            }
            var ruleInfoByCategKey, isFalling, chart = this.chart, rootCatData = this.visualRoles.category.select(chart.partData(this.dataPartValue), {
                visible: !0
            }), rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: rootCatData
            });
            if (ris) {
                ruleInfoByCategKey = def.query(ris).object({
                    name: function(ri) {
                        return ri.group.absKey;
                    }
                });
                isFalling = this.plot.isFalling();
                createRectangleSceneRecursive(rootCatData, 0);
            }
            return rootScene;
        }
    });
    pvc.PlotPanel.registerClass(pvc.WaterfallPanel);
    def.type("pvc.WaterfallChart", pvc.BarAbstract).add({
        _processOptionsCore: function(options) {
            options.baseAxisComposite = !1;
            this.base(options);
        },
        _createPlotsInternal: function() {
            this._addPlot(new pvc.visual.WaterfallPlot(this));
        }
    });
    def("pvc.visual.PointPlot", pvc.visual.CategoricalPlot.extend({
        methods: {
            type: "point",
            _initVisualRoles: function() {
                this.base();
                this._addVisualRole("value", {
                    isMeasure: !0,
                    isRequired: !0,
                    isPercent: this.option("Stacked"),
                    requireSingleDimension: !0,
                    requireIsDiscrete: !1,
                    valueType: Number,
                    defaultDimension: "value"
                });
            }
        },
        options: {
            DotsVisible: {
                resolve: "_resolveFull",
                data: pvcPoint_buildVisibleOption("Dots", !0),
                cast: Boolean,
                value: !1
            },
            LinesVisible: {
                resolve: "_resolveFull",
                data: pvcPoint_buildVisibleOption("Lines", !0),
                cast: Boolean,
                value: !1
            },
            AreasVisible: {
                resolve: "_resolveFull",
                data: pvcPoint_buildVisibleOption("Areas", !1),
                cast: Boolean,
                value: !1
            },
            AreasFillOpacity: {
                resolve: "_resolveFull",
                cast: def.number.toNonNegative,
                value: null
            },
            ValuesAnchor: {
                value: "right"
            }
        }
    }));
    pvc.visual.Plot.registerClass(pvc.visual.PointPlot);
    def.type("pvc.PointPanel", pvc.CategoricalAbstractPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.linesVisible = plot.option("LinesVisible");
        this.dotsVisible = plot.option("DotsVisible");
        this.areasVisible = plot.option("AreasVisible");
        if (!this.linesVisible && !this.dotsVisible && !this.areasVisible) {
            this.linesVisible = !0;
            plot.option.specify({
                LinesVisible: !0
            });
        }
        if (this.areasVisible && !this.stacked) {
            this.areasFillOpacity = plot.option("AreasFillOpacity");
            null == this.areasFillOpacity && (this.areasFillOpacity = .5);
        }
        chart.scatterChartPanel || (chart.scatterChartPanel = this);
    }).add({
        plotType: "point",
        _ibits: -1,
        pvLine: null,
        pvArea: null,
        pvDot: null,
        pvLabel: null,
        pvScatterPanel: null,
        _creating: function() {
            var colorDataCell = this.plot.dataCellsByRole.color[0];
            if (!colorDataCell.legendSymbolRenderer() && colorDataCell.legendVisible()) {
                var colorAxis = this.axes.color, drawMarker = def.nullyTo(colorAxis.option("LegendDrawMarker", !0), this.dotsVisible || this.areasVisible), drawLine = !drawMarker || def.nullyTo(colorAxis.option("LegendDrawLine", !0), this.linesVisible && !this.areasVisible), extAbsPrefix = pvc.uniqueExtensionAbsPrefix(), keyArgs = {
                    drawMarker: drawMarker,
                    drawLine: drawLine,
                    extensionPrefix: {
                        abs: extAbsPrefix
                    }
                };
                this.chart._processExtensionPointsIn(colorDataCell.role.legend(), extAbsPrefix);
                if (drawMarker) {
                    var markerShape = colorAxis.option("LegendShape", !0);
                    keyArgs.markerPvProto = new pv.Dot();
                    if (this.dotsVisible) {
                        markerShape || (markerShape = "circle");
                        keyArgs.markerPvProto.lineWidth(1.5, pvc.extensionTag).shapeSize(12, pvc.extensionTag);
                    }
                    keyArgs.markerShape = markerShape;
                    this._applyV1BarSecondExtensions && this.chart.extend(keyArgs.markerPvProto, "barSecondDot", {
                        constOnly: !0
                    });
                    this.extend(keyArgs.markerPvProto, "dot", {
                        constOnly: !0
                    });
                }
                if (drawLine) {
                    keyArgs.rulePvProto = new pv.Rule().lineWidth(1.5, pvc.extensionTag);
                    this._applyV1BarSecondExtensions && this.chart.extend(keyArgs.rulePvProto, "barSecondLine", {
                        constOnly: !0
                    });
                    this.extend(keyArgs.rulePvProto, "line", {
                        constOnly: !0
                    });
                }
                colorDataCell.legendSymbolRenderer(keyArgs);
            }
        },
        _createCore: function() {
            this.base();
            var wrapper, me = this, chart = this.chart, isStacked = this.stacked, dotsVisible = this.dotsVisible, areasVisible = this.areasVisible, linesVisible = this.linesVisible, anchor = this.isOrientationVertical() ? "bottom" : "left", baseAxis = this.axes.base, axisCategDatas = baseAxis.domainItems(), isBaseDiscrete = baseAxis.role.grouping.isDiscrete(), data = this.visibleData({
                ignoreNulls: !1
            }), axisSeriesDatas = this.visualRoles.series.isBound() ? this.visualRoles.series.flatten(this.partData(), {
                visible: !0,
                isNull: chart.options.ignoreNulls ? !1 : null
            }).childNodes : [ null ], rootScene = this._buildScene(data, axisSeriesDatas, axisCategDatas);
            this.pvPanel.zOrder(areasVisible ? -7 : 1);
            this.pvScatterPanel = new pvc.visual.Panel(this, this.pvPanel, {
                extensionId: "panel"
            }).lock("data", rootScene.childNodes).pvMark;
            var areasFillOpacity = this.areasFillOpacity;
            this.compatVersion() <= 1 && (wrapper = isStacked ? function(v1f) {
                return function(dotScene) {
                    return v1f.call(this, dotScene.vars.value.rawValue);
                };
            } : function(v1f) {
                return function(dotScene) {
                    var d = {
                        category: dotScene.vars.category.rawValue,
                        value: dotScene.vars.value.rawValue
                    }, pseudo = Object.create(this);
                    pseudo.index = dotScene.dataIndex;
                    return v1f.call(pseudo, d);
                };
            });
            var sceneNotNullProp = function(scene) {
                return !scene.isNull;
            }, sceneNotNullOrIntermProp = function(scene) {
                return !scene.isNull || scene.isIntermediate;
            }, areaVisibleProp = isBaseDiscrete && isStacked ? sceneNotNullOrIntermProp : sceneNotNullProp, isLineAreaNoSelect = chart.selectableByFocusWindow();
            this.pvArea = new pvc.visual.Area(this, this.pvScatterPanel, {
                extensionId: "area",
                noTooltip: !1,
                wrapper: wrapper,
                noSelect: isLineAreaNoSelect,
                noRubberSelect: !0,
                showsSelection: !isLineAreaNoSelect
            }).lockMark("data", function(seriesScene) {
                return seriesScene.childNodes;
            }).lockMark("visible", areaVisibleProp).override("x", function(scene) {
                return scene.basePosition;
            }).override("y", function(scene) {
                return scene.orthoPosition;
            }).override("dy", function(scene) {
                return chart.animate(0, scene.orthoLength);
            }).override("color", function(scene, type) {
                return areasVisible ? this.base(scene, type) : null;
            }).override("baseColor", function(scene, type) {
                var color = this.base(scene, type);
                return !this._finished && color && null != areasFillOpacity ? color.alpha(def.between(color.opacity * areasFillOpacity, 0, 1)) : color;
            }).override("dimColor", function(color, type) {
                return isStacked ? pvc.toGrayScale(color, 1, null, null).brighter() : this.base(color, type);
            }).lock("events", areasVisible ? "painted" : "none").pvMark;
            var dotsVisibleOnly = dotsVisible && !linesVisible && !areasVisible, darkerLineAndDotColor = isStacked && areasVisible, extensionIds = [ "line" ];
            this._applyV1BarSecondExtensions && extensionIds.push({
                abs: "barSecondLine"
            });
            var lineVisibleProp = dotsVisibleOnly ? !1 : isBaseDiscrete && isStacked && areasVisible ? sceneNotNullOrIntermProp : sceneNotNullProp, noLineInteraction = areasVisible && !linesVisible;
            this.pvLine = new pvc.visual.Line(this, this.pvArea.anchor(this.anchorOpposite(anchor)), {
                extensionId: extensionIds,
                freePosition: !0,
                wrapper: wrapper,
                noTooltip: noLineInteraction,
                noDoubleClick: noLineInteraction,
                noClick: noLineInteraction,
                noHover: noLineInteraction,
                noSelect: noLineInteraction || isLineAreaNoSelect,
                showsSelection: !isLineAreaNoSelect
            }).lockMark("visible", lineVisibleProp).override("defaultColor", function(scene, type) {
                var color = this.base(scene, type);
                return !this._finished && darkerLineAndDotColor && color ? color.darker(.6) : color;
            }).override("normalColor", function(scene, color) {
                return linesVisible ? color : null;
            }).override("interactiveColor", function(scene, color, type) {
                return linesVisible || this.mayShowAnySelected(scene) || this.mayShowActive(scene) ? this.base(scene, color, type) : null;
            }).override("baseStrokeWidth", function(scene) {
                var strokeWidth;
                linesVisible && (strokeWidth = this.base(scene));
                return null == strokeWidth ? 1.5 : strokeWidth;
            }).intercept("strokeDasharray", function(scene) {
                var dashArray = this.delegateExtension();
                if (void 0 === dashArray) {
                    var useDash = scene.isInterpolated;
                    if (!useDash) {
                        var next = scene.nextSibling;
                        useDash = next && next.isIntermediate && next.isInterpolated;
                        if (!useDash) {
                            var previous = scene.previousSibling;
                            useDash = previous && scene.isIntermediate && previous.isInterpolated;
                        }
                    }
                    dashArray = useDash ? ". " : null;
                }
                return dashArray;
            }).pvMark;
            var showAloneDots = !(areasVisible && isBaseDiscrete && isStacked);
            extensionIds = [ "dot" ];
            this._applyV1BarSecondExtensions && extensionIds.push({
                abs: "barSecondDot"
            });
            this.pvDot = new pvc.visual.Dot(this, this.pvLine, {
                extensionId: extensionIds,
                freePosition: !0,
                wrapper: wrapper,
                tooltipArgs: {
                    options: {
                        ignoreRadius: linesVisible
                    }
                }
            }).intercept("visible", function(scene) {
                return !scene.isNull && !scene.isIntermediate && this.delegateExtension(!0);
            }).override("color", function(scene, type) {
                if (!dotsVisible) {
                    var visible = (showAloneDots ? scene.isAlone : scene.isSingle) || scene.isActive && this.showsActivity();
                    if (!visible) return pvc.invisibleFill;
                }
                return this.base(scene, type);
            }).override("defaultColor", function(scene, type) {
                var color = this.base(scene, type);
                if (color && !this._finished) {
                    darkerLineAndDotColor && (color = color.darker(.6));
                    scene.isInterpolated && "fill" === type && (color = color.brighter(.5));
                }
                return color;
            }).override("interactiveColor", function(scene, color, type) {
                var darken = !dotsVisible && (scene.isSingle || scene.isAlone) && !scene.isActive && this.mayShowNotAmongSelected(scene) && this.mayShowActive(scene);
                return darken ? pv.Color.names.darkgray.darker().darker() : this.base(scene, color, type);
            }).optional("lineWidth", function(scene) {
                var isNoDotsAndInactiveAlone = !dotsVisible && (scene.isSingle || scene.isAlone) && !(scene.isActive && this.showsActivity());
                return isNoDotsAndInactiveAlone ? 0 : 1.5;
            }).override("size", function(scene) {
                var showLikeLineDots = !dotsVisible && !(scene.isActive && this.showsActivity()) && (!showAloneDots && scene.isSingle || showAloneDots && scene.isAlone);
                if (showLikeLineDots) {
                    var lineWidth = Math.max(me.pvLine.visible() ? me.pvLine.lineWidth() : 0, 1), radius = lineWidth / 2 + 1;
                    return def.sqr(radius);
                }
                return this.base(scene);
            }).override("baseSize", function(scene) {
                var v = this.base(scene);
                return !this._finished && scene.isInterpolated ? .8 * v : v;
            }).pvMark;
            var label = pvc.visual.ValueLabel.maybeCreate(this, this.pvDot, {
                wrapper: wrapper
            });
            label && (this.pvLabel = label.pvMark);
        },
        renderInteractive: function() {
            this.pvScatterPanel.render();
        },
        _buildSceneCore: function(data, axisSeriesDatas, axisCategDatas) {
            function completeSeriesScenes(seriesScene) {
                for (var fromScene, seriesScenes2 = [], seriesScenes = seriesScene.childNodes, notNullCount = 0, firstAloneScene = null, c = 0, toChildIndex = 0, categCount = seriesScenes.length; categCount > c; c++, 
                toChildIndex++) {
                    var toScene = seriesScenes[toChildIndex], c2 = 2 * c;
                    seriesScenes2[c2] = toScene;
                    completeMainScene.call(this, fromScene, toScene, belowSeriesScenes2 && belowSeriesScenes2[c2]);
                    toScene.isAlone && !firstAloneScene && (firstAloneScene = toScene);
                    toScene.isNull || notNullCount++;
                    if (fromScene) {
                        var interScene = createIntermediateScene.call(this, seriesScene, fromScene, toScene, toChildIndex, belowSeriesScenes2 && belowSeriesScenes2[c2 - 1]);
                        if (interScene) {
                            seriesScenes2[c2 - 1] = interScene;
                            toChildIndex++;
                        }
                    }
                    fromScene = toScene;
                }
                1 === notNullCount && firstAloneScene && 1 === categCount && (firstAloneScene.isSingle = !0);
                isStacked && (belowSeriesScenes2 = seriesScenes2);
            }
            function completeMainScene(fromScene, toScene, belowScene) {
                var toAccValue = toScene.vars.value.accValue;
                if (belowScene) {
                    toScene.isNull && !isBaseDiscrete ? toAccValue = orthoNullValue : toAccValue += belowScene.vars.value.accValue;
                    toScene.vars.value.accValue = toAccValue;
                }
                toScene.basePosition = sceneBaseScale(toScene);
                toScene.orthoPosition = orthoZero;
                toScene.orthoLength = orthoScale(toAccValue) - orthoZero;
                var isNullFrom = !fromScene || fromScene.isNull, isAlone = isNullFrom && !toScene.isNull;
                if (isAlone) {
                    var nextScene = toScene.nextSibling;
                    isAlone = !nextScene || nextScene.isNull;
                }
                toScene.isAlone = isAlone;
                toScene.isSingle = !1;
            }
            function createIntermediateScene(seriesScene, fromScene, toScene, toChildIndex, belowScene) {
                var interValue, interAccValue, interBasePosition, interIsNull = fromScene.isNull || toScene.isNull, areasVisible = this.areasVisible;
                if (interIsNull) {
                    if (areasVisible && belowScene && isBaseDiscrete) {
                        var belowValueVar = belowScene.vars.value;
                        interAccValue = belowValueVar.accValue;
                        interValue = belowValueVar[valueRole.name];
                    } else interValue = interAccValue = orthoNullValue;
                    interBasePosition = areasVisible ? isStacked && isBaseDiscrete ? toScene.basePosition - sceneBaseScale.range().step / 2 : fromScene.isNull ? toScene.basePosition : fromScene.basePosition : (toScene.basePosition + fromScene.basePosition) / 2;
                } else {
                    var fromValueVar = fromScene.vars.value, toValueVar = toScene.vars.value;
                    interValue = (toValueVar.value + fromValueVar.value) / 2;
                    interAccValue = (toValueVar.accValue + fromValueVar.accValue) / 2;
                    interBasePosition = (toScene.basePosition + fromScene.basePosition) / 2;
                }
                var interScene = new pvc.visual.Scene(seriesScene, {
                    index: toChildIndex,
                    source: toScene.source
                });
                interScene.dataIndex = toScene.dataIndex;
                interScene.vars.category = toScene.vars.category;
                var interValueVar = new pvc_ValueLabelVar(interValue, valueDim.format(interValue), interValue);
                interValueVar.accValue = interAccValue;
                interScene.vars.value = interValueVar;
                interScene.ownerScene = toScene;
                interScene.isInterpolated = toScene.isInterpolated;
                interScene.isIntermediate = !0;
                interScene.isSingle = !1;
                interScene.isNull = interIsNull;
                interScene.isAlone = interIsNull && toScene.isNull && fromScene.isNull;
                interScene.basePosition = interBasePosition;
                interScene.orthoPosition = orthoZero;
                interScene.orthoLength = orthoScale(interAccValue) - orthoZero;
                colorVarHelper.onNewScene(interScene, !0);
                return interScene;
            }
            var rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: data
            }), valueRole = this.visualRoles.value, isBaseDiscrete = this.axes.base.role.grouping.isDiscrete(), isStacked = this.stacked, valueVarHelper = new pvc.visual.RoleVarHelper(rootScene, "value", valueRole, {
                hasPercentSubVar: isStacked
            }), colorVarHelper = new pvc.visual.RoleVarHelper(rootScene, "color", this.visualRoles.color), valueDimName = valueRole.lastDimensionName(), valueDim = data.owner.dimensions(valueDimName), orthoScale = this.axes.ortho.scale, orthoNullValue = def.scope(function() {
                var domain = orthoScale.domain(), dmin = domain[0], dmax = domain[1];
                return dmin * dmax >= 0 ? dmin >= 0 ? dmin : dmax : 0;
            }), orthoZero = orthoScale(orthoNullValue), sceneBaseScale = this.axes.base.sceneScale({
                sceneVarName: "category"
            });
            axisSeriesDatas.forEach(function(axisSeriesData) {
                var seriesScene = new pvc.visual.Scene(rootScene, {
                    source: axisSeriesData || data
                });
                seriesScene.vars.series = pvc_ValueLabelVar.fromComplex(axisSeriesData);
                colorVarHelper.onNewScene(seriesScene, !1);
                axisCategDatas.forEach(function(axisCategData, categIndex) {
                    var categData = data.child(axisCategData.key), group = categData;
                    group && axisSeriesData && (group = group.child(axisSeriesData.key));
                    var serCatScene = new pvc.visual.Scene(seriesScene, {
                        source: group
                    });
                    serCatScene.dataIndex = categIndex;
                    serCatScene.vars.category = pvc_ValueLabelVar.fromComplex(axisCategData);
                    valueVarHelper.onNewScene(serCatScene, !0);
                    var valueVar = serCatScene.vars.value, value = valueVar.value;
                    valueVar.accValue = null != value ? value : orthoNullValue;
                    colorVarHelper.onNewScene(serCatScene, !0);
                    var isInterpolated = null != group && group.datums().prop("isInterpolated").any(def.truthy);
                    serCatScene.isInterpolated = isInterpolated;
                    serCatScene.isNull = null == value;
                    serCatScene.isIntermediate = !1;
                }, this);
            }, this);
            var belowSeriesScenes2;
            rootScene.children().reverse().each(completeSeriesScenes, this);
            return rootScene;
        }
    });
    pvc.PlotPanel.registerClass(pvc.PointPanel);
    def.type("pvc.PointAbstract", pvc.CategoricalAbstract).add({
        _defaultAxisBandSizeRatio: 1,
        _createPlotsInternal: function() {
            this._addPlot(this._createPointPlot());
            this.options.plot2 && this._addPlot(new pvc.visual.PointPlot(this, {
                name: "plot2",
                defaults: {
                    DataPart: "1",
                    ColorAxis: 2,
                    LinesVisible: !0,
                    DotsVisible: !0
                }
            }));
        },
        _createPlotTrend: function() {
            this._addPlot(new pvc.visual.PointPlot(this, {
                name: "trend",
                spec: {
                    visualRoles: {
                        color: {
                            from: "series"
                        }
                    }
                },
                fixed: {
                    TrendType: "none",
                    NullInterpolatioMode: "none"
                },
                defaults: {
                    DataPart: "trend",
                    ColorAxis: 2,
                    LinesVisible: !0,
                    DotsVisible: !1
                }
            }));
        },
        _initAxesEnd: function() {
            var typeAxes = this.axesByType.base;
            typeAxes && typeAxes.forEach(function(axis) {
                "discrete" !== axis.scaleType && axis.option.defaults({
                    Offset: .01
                });
            });
            typeAxes = this.axesByType.ortho;
            typeAxes && typeAxes.forEach(function(axis) {
                axis.option.defaults({
                    Offset: .04
                });
            });
            this.base();
        },
        defaults: {
            tooltipOffset: 10
        }
    });
    def.type("pvc.DotChart", pvc.PointAbstract).add({
        _createPointPlot: function() {
            return new pvc.visual.PointPlot(this, {
                fixed: {
                    DotsVisible: !0
                }
            });
        }
    });
    def.type("pvc.LineChart", pvc.PointAbstract).add({
        _createPointPlot: function() {
            return new pvc.visual.PointPlot(this, {
                fixed: {
                    LinesVisible: !0
                }
            });
        }
    });
    def.type("pvc.AreaChart", pvc.PointAbstract).add({
        _createPointPlot: function() {
            return new pvc.visual.PointPlot(this, {
                fixed: {
                    AreasVisible: !0
                }
            });
        }
    });
    pvc.mStackedLineChart = def.type("pvc.StackedLineChart", pvc.PointAbstract).add({
        _createPointPlot: function() {
            return new pvc.visual.PointPlot(this, {
                fixed: {
                    LinesVisible: !0,
                    Stacked: !0
                }
            });
        }
    });
    def.type("pvc.StackedDotChart", pvc.PointAbstract).add({
        _createPointPlot: function() {
            return new pvc.visual.PointPlot(this, {
                fixed: {
                    DotsVisible: !0,
                    Stacked: !0
                }
            });
        }
    });
    pvc.mStackedAreaChart = def.type("pvc.StackedAreaChart", pvc.PointAbstract).add({
        _createPointPlot: function() {
            return new pvc.visual.PointPlot(this, {
                fixed: {
                    AreasVisible: !0,
                    Stacked: !0
                },
                defaults: {
                    LinesVisible: !0
                }
            });
        }
    });
    def("pvc.visual.MetricPointPlot", pvc.visual.MetricXYPlot.extend({
        methods: {
            type: "scatter",
            _initVisualRoles: function() {
                this.base();
                this._addVisualRole("size", {
                    isMeasure: !0,
                    requireSingleDimension: !0,
                    requireIsDiscrete: !1,
                    defaultDimension: "size",
                    dimensionDefaults: {
                        valueType: Number
                    }
                });
            },
            _getColorRoleSpec: function() {
                return {
                    isMeasure: !0,
                    defaultSourceRole: "series",
                    defaultDimension: "color*",
                    dimensionDefaults: {
                        valueType: Number
                    }
                };
            },
            _initDataCells: function() {
                this.base();
                this.option("DotsVisible") && this._addDataCell(new pvc.visual.DataCell(this, "size", this.option("SizeAxis") - 1, this.visualRoles.size, this.option("DataPart")));
            }
        },
        options: {
            SizeRole: {
                resolve: "_resolveFixed",
                value: "size"
            },
            SizeAxis: {
                resolve: "_resolveFixed",
                value: 1
            },
            Shape: {
                resolve: "_resolveFull",
                cast: pvc.parseShape,
                value: "circle"
            },
            NullShape: {
                resolve: "_resolveFull",
                cast: pvc.parseShape,
                value: "cross"
            },
            DotsVisible: {
                resolve: "_resolveFull",
                data: pvcMetricPoint_buildVisibleOption("Dots"),
                cast: Boolean,
                value: !1
            },
            LinesVisible: {
                resolve: "_resolveFull",
                data: pvcMetricPoint_buildVisibleOption("Lines"),
                cast: Boolean,
                value: !1
            },
            ValuesAnchor: {
                value: "right"
            },
            ValuesMask: {
                value: "{x},{y}"
            },
            AutoPaddingByDotSize: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !0
            }
        }
    }));
    pvc.visual.Plot.registerClass(pvc.visual.MetricPointPlot);
    pvc.parseMetricPointSizeAxisRatioTo = pvc.makeEnumParser("ratioTo", [ "minWidthHeight", "height", "width" ], "minWidthHeight");
    def("pvc.visual.MetricPointSizeAxis", pvc.visual.SizeAxis.extend({
        options: {
            Ratio: {
                resolve: "_resolveFull",
                cast: def.number.toNonNegative,
                value: .2
            },
            RatioTo: {
                resolve: "_resolveFull",
                cast: pvc.parseMetricPointSizeAxisRatioTo,
                value: "minwidthheight"
            }
        }
    }));
    def.type("pvc.MetricPointPanel", pvc.CartesianAbstractPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        var sizeAxis = this.axes.size = chart._getAxis("size", (plot.option("SizeAxis") || 0) - 1);
        if (sizeAxis) {
            this.sizeAxisRatio = sizeAxis.option("Ratio");
            this.sizeAxisRatioTo = sizeAxis.option("RatioTo");
            this.autoPaddingByDotSize = plot.option("AutoPaddingByDotSize");
        }
        this.linesVisible = plot.option("LinesVisible");
        this.dotsVisible = plot.option("DotsVisible");
        if (!this.linesVisible && !this.dotsVisible) {
            this.linesVisible = !0;
            plot.option.specify({
                LinesVisible: !0
            });
        }
        chart.scatterChartPanel || (chart.scatterChartPanel = this);
    }).add({
        plotType: "scatter",
        _v1DimRoleName: {
            category: "x",
            value: "y"
        },
        _creating: function() {
            var colorDataCell = this.plot.dataCellsByRole.color[0];
            if (!colorDataCell.legendSymbolRenderer() && colorDataCell.legendVisible()) {
                var colorAxis = this.axes.color, drawMarker = def.nullyTo(colorAxis.option("LegendDrawMarker", !0), this.dotsVisible), drawLine = def.nullyTo(colorAxis.option("LegendDrawLine", !0), this.linesVisible);
                if (drawMarker || drawLine) {
                    var extAbsPrefix = pvc.uniqueExtensionAbsPrefix(), keyArgs = {
                        drawMarker: drawMarker,
                        drawLine: drawLine,
                        extensionPrefix: {
                            abs: extAbsPrefix
                        }
                    };
                    this.chart._processExtensionPointsIn(colorDataCell.role.legend(), extAbsPrefix);
                    if (drawMarker) {
                        keyArgs.markerShape = colorAxis.option("LegendShape", !0) || "circle";
                        keyArgs.markerPvProto = new pv.Dot().lineWidth(1.5, pvc.extensionTag).shapeSize(12, pvc.extensionTag);
                        this.extend(keyArgs.markerPvProto, "dot", {
                            constOnly: !0
                        });
                    }
                    if (drawLine) {
                        keyArgs.rulePvProto = new pv.Rule().lineWidth(1.5, pvc.extensionTag);
                        this.extend(keyArgs.rulePvProto, "line", {
                            constOnly: !0
                        });
                    }
                    colorDataCell.legendSymbolRenderer(keyArgs);
                }
            }
        },
        _getRootScene: function() {
            return def.lazy(this, "_rootScene", this._buildScene, this);
        },
        _calcLayout: function(layoutInfo) {
            var result = this.base(layoutInfo), rootScene = this._getRootScene();
            rootScene.isSizeBound && this.axes.size.setScaleRange(this._calcDotAreaRange(layoutInfo));
            this._calcAxesPadding(layoutInfo, rootScene);
            return result;
        },
        _getDotDiameterRefLength: function(layoutInfo) {
            var size = layoutInfo.size;
            switch (this.sizeAxisRatioTo) {
              case "minwidthheight":
                return Math.min(size.width, size.height);

              case "width":
                return size.width;

              case "height":
                return size.height;
            }
            def.debug >= 2 && this.log(def.format("Invalid option 'sizeAxisRatioTo' value. Assuming 'minWidthHeight'.", [ this.sizeAxisRatioTo ]));
            this.sizeRatioTo = "minwidthheight";
            return this._getDotDiameterRefLength(layoutInfo);
        },
        _calcDotRadiusRange: function(layoutInfo) {
            return {
                min: Math.sqrt(12),
                max: this.sizeAxisRatio / 2 * this._getDotDiameterRefLength(layoutInfo)
            };
        },
        _calcDotAreaRange: function(layoutInfo) {
            var radiusRange = this._calcDotRadiusRange(layoutInfo);
            if ("diamond" === this.shape) {
                radiusRange.max /= Math.SQRT2;
                radiusRange.min /= Math.SQRT2;
            }
            var maxArea = def.sqr(radiusRange.max), minArea = def.sqr(radiusRange.min), areaSpan = maxArea - minArea;
            if (1 >= areaSpan) {
                maxArea = Math.max(maxArea, 2);
                minArea = 1;
                areaSpan = maxArea - minArea;
                def.debug >= 3 && this.log("Using rescue mode dot area calculation due to insufficient space.");
            }
            return {
                min: minArea,
                max: maxArea,
                span: areaSpan
            };
        },
        _calcAxesPadding: function(layoutInfo, rootScene) {
            var contentOverflow;
            if (this.autoPaddingByDotSize) {
                var axes = this.axes, clientSize = layoutInfo.clientSize, paddings = layoutInfo.paddings;
                contentOverflow = {};
                axes.x.setScaleRange(clientSize.width);
                axes.y.setScaleRange(clientSize.height);
                var isV = this.isOrientationVertical(), sceneXScale = axes.x.sceneScale({
                    sceneVarName: isV ? "x" : "y"
                }), sceneYScale = axes.y.sceneScale({
                    sceneVarName: isV ? "y" : "x"
                }), xMax = axes.x.scale.max, yMax = axes.y.scale.max, hasSizeRole = rootScene.isSizeBound, sizeScale = hasSizeRole ? axes.size.scale : null;
                if (!sizeScale) {
                    var defaultSize = def.number.to(this._getExtension("dot", "shapeRadius"), 0);
                    if (0 >= defaultSize) {
                        defaultSize = def.number.to(this._getExtension("dot", "shapeSize"), 0);
                        0 >= defaultSize && (defaultSize = 12);
                    } else defaultSize = def.sqr(defaultSize);
                    sizeScale = def.fun.constant(defaultSize);
                }
                contentOverflow = {};
                var op, axisOffsetPaddings = this.chart._axisOffsetPaddings;
                if (axisOffsetPaddings) {
                    op = {};
                    pvc_Sides.names.forEach(function(side) {
                        var len_a = pvc.BasePanel.orthogonalLength[side];
                        op[side] = (axisOffsetPaddings[side] || 0) * (clientSize[len_a] + paddings[len_a]);
                    }, this);
                }
                var setSide = function(side, padding) {
                    op && (padding += op[side] || 0);
                    0 > padding && (padding = 0);
                    var value = contentOverflow[side];
                    (null == value || padding > value) && (contentOverflow[side] = padding);
                }, processScene = function(scene) {
                    var x = sceneXScale(scene), y = sceneYScale(scene), r = Math.sqrt(sizeScale(hasSizeRole ? scene.vars.size.value : 0));
                    setSide("left", r - x);
                    setSide("bottom", r - y);
                    setSide("right", x + r - xMax);
                    setSide("top", y + r - yMax);
                };
                rootScene.children().selectMany(function(seriesScene) {
                    return seriesScene.childNodes;
                }).each(processScene);
            } else contentOverflow = this._calcContentOverflow(layoutInfo);
            layoutInfo.contentOverflow = contentOverflow;
        },
        _createCore: function() {
            var me = this;
            me.base();
            var chart = me.chart, rootScene = me._getRootScene(), wrapper = me._buildSignsWrapper(), isV1Compat = me.compatVersion() <= 1;
            this._finalizeScene(rootScene);
            me.pvPanel.zOrder(1);
            this.pvScatterPanel = new pvc.visual.Panel(me, me.pvPanel, {
                extensionId: "panel"
            }).lock("data", rootScene.childNodes).pvMark;
            var isLineNoSelect = chart.selectableByFocusWindow(), isColorDiscrete = rootScene.isColorBound && this.visualRoles.color.isDiscrete(), line = new pvc.visual.Line(me, me.pvScatterPanel, {
                extensionId: "line",
                wrapper: wrapper,
                noTooltip: !1,
                noSelect: isLineNoSelect,
                showsSelection: !isLineNoSelect
            }).lockMark("data", function(seriesScene) {
                return seriesScene.childNodes;
            }).intercept("visible", function(scene) {
                if (!me.linesVisible) return !1;
                var visible = this.delegateExtension();
                null == visible && (visible = !scene.isNull && (!rootScene.isSizeBound && !rootScene.isColorBound || rootScene.isSizeBound && null != scene.vars.size.value || rootScene.isColorBound && (isColorDiscrete || null != scene.vars.color.value)));
                return visible;
            }).override("x", function(scene) {
                return scene.basePosition;
            }).override("y", function(scene) {
                return scene.orthoPosition;
            });
            me.pvLine = line.pvMark;
            var dot = new pvc.visual.DotSizeColor(me, me.pvLine, {
                extensionId: "dot",
                wrapper: wrapper,
                activeSeriesAware: me.linesVisible,
                tooltipArgs: {
                    options: {
                        ignoreRadius: rootScene.isSizeBound ? !1 : me.linesVisible
                    }
                }
            }).override("x", function(scene) {
                return scene.basePosition;
            }).override("y", function(scene) {
                return scene.orthoPosition;
            }).override("color", function(scene, type) {
                return me.dotsVisible || scene.isActive || scene.isSingle ? this.base(scene, type) : pvc.invisibleFill;
            });
            rootScene.isSizeBound ? me.autoPaddingByDotSize && "minwidthheight" === me.sizeAxisRatioTo || me.pvPanel.borderPanel.overflow("hidden") : dot.override("size", function(scene) {
                var showLikeLineDots = !me.dotsVisible && scene.isSingle && !(scene.isActive && this.showsActivity());
                if (showLikeLineDots) {
                    var lineWidth = Math.max(me.pvLine.visible() ? me.pvLine.lineWidth() : 0, 1), radius = lineWidth / 2 + 1;
                    return def.sqr(radius);
                }
                return this.base(scene);
            });
            me.pvDot = dot.pvMark;
            me.pvDot.rubberBandSelectionMode = "center";
            if (pvc.visual.ValueLabel.isNeeded(me)) {
                var extensionIds = [ "label" ];
                isV1Compat && extensionIds.push("lineLabel");
                var label = pvc.visual.ValueLabel.maybeCreate(me, me.pvDot, {
                    extensionId: extensionIds,
                    wrapper: wrapper
                });
                label && (me.pvHeatGridLabel = label.pvMark);
            }
        },
        _buildSignsWrapper: function() {
            return this.compatVersion() > 1 ? null : function(v1f) {
                return function(scene) {
                    var d = {
                        category: scene.vars.x.rawValue,
                        value: scene.vars.y.rawValue
                    }, pseudo = Object.create(this);
                    pseudo.index = scene.dataIndex;
                    return v1f.call(pseudo, d);
                };
            };
        },
        renderInteractive: function() {
            this.pvScatterPanel.render();
        },
        _buildScene: function() {
            function createSeriesScene(seriesGroup) {
                var seriesScene = new pvc.visual.Scene(rootScene, {
                    source: seriesGroup
                });
                seriesScene.vars.series = pvc_ValueLabelVar.fromComplex(seriesGroup);
                colorVarHelper.onNewScene(seriesScene, !1);
                seriesGroup.datums().each(function(datum, dataIndex) {
                    var xAtom = datum.atoms[xDim.name];
                    if (null != xAtom.value) {
                        var yAtom = datum.atoms[yDim.name];
                        if (null != yAtom.value) {
                            var scene = new pvc.visual.Scene(seriesScene, {
                                source: datum
                            });
                            scene.dataIndex = dataIndex;
                            scene.vars.x = pvc_ValueLabelVar.fromAtom(xAtom);
                            scene.vars.y = pvc_ValueLabelVar.fromAtom(yAtom);
                            sizeVarHelper.onNewScene(scene, !0);
                            colorVarHelper.onNewScene(scene, !0);
                            scene.isIntermediate = !1;
                        }
                    }
                });
            }
            function completeSeriesScenes(seriesScene) {
                for (var fromScene, seriesScenes = seriesScene.childNodes, c = 0, toChildIndex = 0, pointCount = seriesScenes.length; pointCount > c; c++, 
                toChildIndex++) {
                    var toScene = seriesScenes[toChildIndex];
                    toScene.isSingle = !fromScene && !toScene.nextSibling;
                    if (fromScene) {
                        var interScene = createIntermediateScene(seriesScene, fromScene, toScene, toChildIndex);
                        interScene && toChildIndex++;
                    }
                    fromScene = toScene;
                }
            }
            function createIntermediateScene(seriesScene, fromScene, toScene, toChildIndex) {
                var yToSceneAux = +toScene.vars.y.value, yFromSceneAux = +fromScene.vars.y.value, xToSceneAux = +toScene.vars.x.value, xFromSceneAux = +fromScene.vars.x.value, interYValue = yDim.type.cast.call(null, (yToSceneAux + yFromSceneAux) / 2), interXValue = xDim.type.cast.call(null, (xToSceneAux + xFromSceneAux) / 2), interScene = new pvc.visual.Scene(seriesScene, {
                    index: toChildIndex,
                    source: toScene.datum
                });
                interScene.dataIndex = toScene.dataIndex;
                interScene.vars.x = new pvc_ValueLabelVar(interXValue, xDim.format(interXValue), interXValue);
                interScene.vars.y = new pvc_ValueLabelVar(interYValue, yDim.format(interYValue), interYValue);
                sizeVarHelper.onNewScene(interScene, !0);
                colorVarHelper.onNewScene(interScene, !0);
                interScene.ownerScene = toScene;
                interScene.isIntermediate = !0;
                interScene.isSingle = !1;
                return interScene;
            }
            var data = this.visibleData({
                ignoreNulls: !1
            }), rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: data
            }), roles = this.visualRoles, colorVarHelper = new pvc.visual.RoleVarHelper(rootScene, "color", roles.color), sizeVarHelper = new pvc.visual.RoleVarHelper(rootScene, "size", roles.size), xDim = data.owner.dimensions(roles.x.lastDimensionName()), yDim = data.owner.dimensions(roles.y.lastDimensionName());
            data.children().each(createSeriesScene, this);
            rootScene.children().each(completeSeriesScenes, this);
            return rootScene;
        },
        _finalizeScene: function(rootScene) {
            var axes = this.axes, sceneBaseScale = axes.base.sceneScale({
                sceneVarName: "x"
            }), sceneOrthoScale = axes.ortho.sceneScale({
                sceneVarName: "y"
            });
            rootScene.children().selectMany(function(seriesScene) {
                return seriesScene.childNodes;
            }).each(function(leafScene) {
                leafScene.basePosition = sceneBaseScale(leafScene);
                leafScene.orthoPosition = sceneOrthoScale(leafScene);
            });
            return rootScene;
        }
    });
    pvc.PlotPanel.registerClass(pvc.MetricPointPanel);
    def.type("pvc.MetricPointAbstract", pvc.MetricXYAbstract).add({
        _axisClassByType: {
            size: pvc.visual.MetricPointSizeAxis
        },
        _createPlotsInternal: function() {
            this._addPlot(this._createPointPlot());
        },
        _createPointPlot: function() {},
        _createPlotTrend: function() {
            this._addPlot(new pvc.visual.MetricPointPlot(this, {
                name: "trend",
                spec: {
                    visualRoles: {
                        color: {
                            from: "series"
                        },
                        size: null
                    }
                },
                fixed: {
                    TrendType: "none",
                    NullInterpolatioMode: "none",
                    SizeRole: null,
                    SizeAxis: null,
                    OrthoAxis: 1
                },
                defaults: {
                    DataPart: "trend",
                    ColorAxis: 2,
                    LinesVisible: !0,
                    DotsVisible: !1
                }
            }));
        },
        _getTranslationClass: function(translOptions) {
            return def.type(this.base(translOptions)).methods({
                _configureTypeCore: function() {
                    this._configureTypeByOrgLevel([ "series" ], [ "x", "y", "color", "size" ]);
                }
            });
        },
        _calcAxesOffsetPaddings: function() {
            var aops = this.base();
            return aops || new pvc_Sides(.01);
        },
        defaults: {
            axisOriginIsZero: !1,
            tooltipOffset: 10
        }
    });
    def.type("pvc.MetricDotChart", pvc.MetricPointAbstract).add({
        _createPointPlot: function() {
            return new pvc.visual.MetricPointPlot(this, {
                fixed: {
                    DotsVisible: !0
                }
            });
        }
    });
    def.type("pvc.MetricLineChart", pvc.MetricPointAbstract).add({
        _createPointPlot: function() {
            return new pvc.visual.MetricPointPlot(this, {
                fixed: {
                    LinesVisible: !0
                }
            });
        }
    });
    def("pvc.visual.HeatGridPlot", pvc.visual.CategoricalPlot.extend({
        methods: {
            type: "heatGrid",
            interpolatable: function() {
                return !1;
            },
            _initVisualRoles: function() {
                this.base();
                var chart = this.chart, sizeDimName = chart.compatVersion() > 1 || 1 === chart.options.sizeValIdx ? "value2" : "value";
                this._addVisualRole("size", {
                    isMeasure: !0,
                    requireSingleDimension: !0,
                    requireIsDiscrete: !1,
                    valueType: Number,
                    defaultDimension: sizeDimName
                });
            },
            _getColorRoleSpec: function() {
                var chart = this.chart, colorDimName = chart.compatVersion() <= 1 && 1 === chart.options.colorValIdx ? "value2" : "value";
                return {
                    isMeasure: !0,
                    requireSingleDimension: !0,
                    requireIsDiscrete: !1,
                    valueType: Number,
                    defaultDimension: colorDimName
                };
            },
            _getCategoryRoleSpec: function() {
                var catRoleSpec = this.base();
                catRoleSpec.requireIsDiscrete = !0;
                return catRoleSpec;
            },
            _initDataCells: function() {
                this.base();
                this.option("UseShapes") && this._addDataCell(new pvc.visual.DataCell(this, "size", this.option("SizeAxis") - 1, this.visualRoles.size, this.option("DataPart")));
            },
            _getOrthoRoles: function() {
                return [ this.visualRole("series") ];
            }
        },
        options: {
            SizeRole: {
                value: "size"
            },
            SizeAxis: {
                value: 1
            },
            UseShapes: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !1
            },
            Shape: {
                resolve: "_resolveFull",
                cast: pvc.parseShape,
                value: "square"
            },
            NullShape: {
                resolve: "_resolveFull",
                cast: pvc.parseShape,
                value: "cross"
            },
            ValuesVisible: {
                getDefault: function() {
                    return !this.option("UseShapes");
                },
                value: null
            },
            ValuesMask: {
                value: null
            },
            ValuesAnchor: {
                value: "center"
            },
            OrthoAxis: {
                resolve: null
            },
            NullInterpolationMode: {
                resolve: null,
                value: "none"
            },
            Stacked: {
                resolve: null,
                value: !1
            }
        }
    }));
    pvc.visual.Plot.registerClass(pvc.visual.HeatGridPlot);
    def.type("pvc.HeatGridPanel", pvc.CategoricalAbstractPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.axes.size = chart._getAxis("size", plot.option("SizeAxis") - 1);
        var roles = this.visualRoles;
        roles.size = chart.visualRole(plot.option("SizeRole"));
        this.useShapes = plot.option("UseShapes");
        this.shape = plot.option("Shape");
        this.nullShape = plot.option("NullShape");
        chart.heatGridChartPanel || (chart.heatGridChartPanel = this);
    }).add({
        plotType: "heatGrid",
        defaultBorder: 1,
        nullBorder: 2,
        selectedBorder: 2,
        _createCore: function() {
            var me = this;
            me.base();
            var cellSize = me._calcCellSize(), a_bottom = me.isOrientationVertical() ? "bottom" : "left", a_left = pvc.BasePanel.relativeAnchor[a_bottom], a_width = pvc.BasePanel.parallelLength[a_bottom], a_height = pvc.BasePanel.orthogonalLength[a_bottom], axisSeriesDatas = me.visualRoles.series.flatten(me.partData(), {
                visible: !0,
                isNull: me.chart.options.ignoreNulls ? !1 : null
            }).childNodes, data = me.visibleData({
                ignoreNulls: !1
            }), rootScene = me._buildScene(data, axisSeriesDatas, data.childNodes);
            rootScene.cellSize = cellSize;
            var hasColor = rootScene.isColorBound, hasSize = rootScene.isSizeBound, wrapper = me._buildSignsWrapper(rootScene), isV1Compat = me.compatVersion() <= 1, rowScale = this.axes.base.scale, colScale = this.axes.ortho.scale, rowBand = rowScale.range().band, colBand = colScale.range().band, rowBand2 = rowBand / 2, colBand2 = colBand / 2, pvRowPanel = new pvc.visual.Panel(me, me.pvPanel).pvMark.data(rootScene.childNodes)[a_bottom](function(scene) {
                return colScale(scene.vars.series.value) - colBand2;
            })[a_height](colBand), extensionIds = [ "panel" ];
            isV1Compat && extensionIds.push("");
            var keyArgs = {
                extensionId: extensionIds,
                wrapper: wrapper
            };
            if (!me.useShapes) {
                var f = !1;
                def.copy(keyArgs, {
                    noSelect: f,
                    noHover: f,
                    noClick: f,
                    noDoubleClick: f,
                    freeColor: f,
                    noTooltip: isV1Compat
                });
            }
            me.pvHeatGrid = new pvc.visual.Panel(me, pvRowPanel, keyArgs).pvMark.lock("data", function(serScene) {
                return serScene.childNodes;
            }).lock(a_left, function(scene) {
                return rowScale(scene.vars.category.value) - rowBand2;
            }).lock(a_width, rowBand).antialias(!1);
            me.shapes = me.useShapes ? me._createShapesHeatMap(cellSize, wrapper, hasColor, hasSize) : me._createNoShapesHeatMap(hasColor);
            me.valuesVisible && !me.valuesMask && (me.valuesMask = me._getDefaultValuesMask(hasColor, hasSize));
            var label = pvc.visual.ValueLabel.maybeCreate(me, me.pvHeatGrid, {
                wrapper: wrapper
            });
            if (label) {
                me.pvHeatGridLabel = label.pvMark;
                me.useShapes && label.override("getAnchoredToMark", def.fun.constant(me.shapes));
            }
        },
        _calcCellSize: function() {
            var xScale = this.axes.x.scale, yScale = this.axes.y.scale, w = (xScale.max - xScale.min) / xScale.domain().length, h = (yScale.max - yScale.min) / yScale.domain().length;
            if (!this.isOrientationVertical()) {
                var tmp = w;
                w = h;
                h = tmp;
            }
            return {
                width: w,
                height: h
            };
        },
        _buildSignsWrapper: function(rootScene) {
            if (this.compatVersion() > 1) return null;
            var colorValuesBySerAndCat = def.query(rootScene.childNodes).object({
                name: function(serScene) {
                    return "" + serScene.vars.series.value;
                },
                value: function(serScene) {
                    return def.query(serScene.childNodes).object({
                        name: function(leafScene) {
                            return "" + leafScene.vars.category.value;
                        },
                        value: function(leafScene) {
                            var colorVar = leafScene.vars.color;
                            return colorVar ? "" + colorVar.value : null;
                        }
                    });
                }
            });
            return function(v1f) {
                return function(leafScene) {
                    var colorValuesByCat = colorValuesBySerAndCat[leafScene.vars.series.value], cat = leafScene.vars.category.rawValue, wrapperParent = Object.create(this.parent), wrapper = Object.create(this), catIndex = leafScene.childIndex(), serIndex = leafScene.parent.childIndex();
                    wrapper.parent = wrapperParent;
                    wrapperParent.index = catIndex;
                    wrapper.index = serIndex;
                    return v1f.call(wrapper, colorValuesByCat, cat);
                };
            };
        },
        _getDefaultValuesMask: function(hasColor, hasSize) {
            var roles = this.visualRoles, roleName = hasColor ? "color" : hasSize ? "size" : null;
            if (roleName) {
                var valueDimName = roles[roleName].lastDimensionName();
                return "{#" + valueDimName + "}";
            }
        },
        _createNoShapesHeatMap: function(hasColor) {
            var getBaseColor = this._buildGetBaseFillColor(hasColor);
            return this.pvHeatGrid.sign.override("defaultColor", function(scene, type) {
                return "stroke" === type ? null : getBaseColor.call(this.pvMark, scene);
            }).override("interactiveColor", function(scene, color, type) {
                return scene.isActive ? color.alpha(.6) : scene.anySelected() && !scene.isSelected() ? this.dimColor(color, type) : this.base(scene, color, type);
            }).override("dimColor", function(color) {
                return pvc.toGrayScale(color, .6);
            }).pvMark.lineWidth(1.5);
        },
        _buildGetBaseFillColor: function(hasColor) {
            var colorAxis = this.axes.color;
            return hasColor ? colorAxis.sceneScale({
                sceneVarName: "color"
            }) : def.fun.constant(colorAxis.option("Unbound"));
        },
        _createShapesHeatMap: function(cellSize, wrapper, hasColor, hasSize) {
            var me = this, areaRange = me._calcDotAreaRange(cellSize);
            hasSize && me.axes.size.setScaleRange(areaRange);
            var keyArgs = {
                extensionId: "dot",
                freePosition: !0,
                activeSeriesAware: !1,
                wrapper: wrapper,
                tooltipArgs: me._buildShapesTooltipArgs(hasColor, hasSize)
            }, pvDot = new pvc.visual.DotSizeColor(me, me.pvHeatGrid, keyArgs).override("dimColor", function(color) {
                return pvc.toGrayScale(color, .6);
            }).pvMark;
            hasSize || pvDot.sign.override("defaultSize", def.fun.constant(areaRange.max));
            return pvDot;
        },
        _calcDotAreaRange: function(cellSize) {
            var w = cellSize.width, h = cellSize.height, maxRadius = Math.min(w, h) / 2;
            "diamond" === this.shape && (maxRadius /= Math.SQRT2);
            maxRadius -= 2;
            var maxArea = def.sqr(maxRadius), minArea = 12, areaSpan = maxArea - minArea;
            if (1 >= areaSpan) {
                maxArea = Math.max(maxArea, 2);
                minArea = 1;
                areaSpan = maxArea - minArea;
                def.debug >= 2 && this.log.warn("Using rescue mode dot area calculation due to insufficient space.");
            }
            return {
                min: minArea,
                max: maxArea,
                span: areaSpan
            };
        },
        _buildShapesTooltipArgs: function(hasColor, hasSize) {
            var chart = this.chart;
            if (this.compatVersion() <= 1 && this.showsTooltip()) {
                var options = chart.options, customTooltip = options.customTooltip;
                customTooltip || (customTooltip = function(s, c, d) {
                    return null != d && void 0 !== d[0] ? d.join(", ") : d;
                });
                var roles = this.visualRoles, seriesDimsNames = roles.series.grouping.dimensionNames(), categDimsNames = roles.category.grouping.dimensionNames();
                return {
                    buildTooltip: options.isMultiValued ? function(context) {
                        var group = context.scene.group;
                        if (!group) return "";
                        var s = cdo.Complex.values(group, seriesDimsNames), c = cdo.Complex.values(group, categDimsNames), d = [], vars = context.scene.vars;
                        hasSize && (d[options.sizeValIdx || 0] = vars.size.value);
                        hasColor && (d[options.colorValIdx || 0] = vars.color.value);
                        return customTooltip.call(options, s, c, d);
                    } : function(context) {
                        var vars = context.scene.vars, s = vars.series.rawValue, c = vars.category.rawValue, valueVar = vars[hasColor ? "color" : "size"], d = valueVar ? valueVar.value : null;
                        return customTooltip.call(options, s, c, d);
                    }
                };
            }
        },
        renderInteractive: function() {
            this.pvPanel.render();
        },
        _buildSceneCore: function(data, axisSeriesDatas, axisCategDatas) {
            function createSeriesScene(serData1) {
                var serScene = new pvc.visual.Scene(rootScene, {
                    source: serData1
                });
                serScene.vars.series = pvc_ValueLabelVar.fromComplex(serData1);
                axisCategDatas.forEach(function(catData1) {
                    createSeriesCategoryScene.call(me, serScene, catData1, serData1);
                });
            }
            function createSeriesCategoryScene(serScene, catData1, serData1) {
                var group = data.child(catData1.key).child(serData1.key), serCatScene = new pvc.visual.Scene(serScene, {
                    source: group
                });
                serCatScene.vars.category = pvc_ValueLabelVar.fromComplex(catData1);
                colorVarHelper.onNewScene(serCatScene, !0);
                sizeVarHelper.onNewScene(serCatScene, !0);
            }
            var me = this, rootScene = new pvc.visual.Scene(null, {
                panel: me,
                source: data
            }), roles = me.visualRoles, colorVarHelper = new pvc.visual.RoleVarHelper(rootScene, "color", roles.color), sizeVarHelper = new pvc.visual.RoleVarHelper(rootScene, "size", roles.size);
            axisSeriesDatas.forEach(createSeriesScene);
            return rootScene;
        }
    });
    pvc.PlotPanel.registerClass(pvc.HeatGridPanel);
    def.type("pvc.HeatGridChart", pvc.CategoricalAbstract).add({
        _allowColorPerCategory: !0,
        _defaultAxisBandSizeRatio: 1,
        _axisCreateIfUnbound: {
            color: !0
        },
        _processOptionsCore: function(options) {
            this.base(options);
            options.legend = !1;
        },
        _createPlotsInternal: function() {
            this._addPlot(new pvc.visual.HeatGridPlot(this));
        },
        defaults: {
            colorValIdx: 0,
            sizeValIdx: 1,
            measuresIndexes: [ 2 ],
            axisOffset: 0,
            plotFrameVisible: !1,
            colorNormByCategory: !0,
            numSD: 2
        }
    });
    pvc.parseBoxLayoutMode = pvc.makeEnumParser("layoutMode", [ "overlapped", "grouped" ], "grouped");
    def("pvc.visual.BoxPlot", pvc.visual.CategoricalPlot.extend({
        methods: {
            type: "box",
            _initVisualRoles: function() {
                this.base();
                var roleSpecBase = {
                    isMeasure: !0,
                    requireSingleDimension: !0,
                    requireIsDiscrete: !1,
                    valueType: Number
                };
                [ {
                    name: "median",
                    label: "Median",
                    defaultDimension: "median"
                }, {
                    name: "lowerQuartil",
                    label: "Lower Quartil",
                    defaultDimension: "lowerQuartil"
                }, {
                    name: "upperQuartil",
                    label: "Upper Quartil",
                    defaultDimension: "upperQuartil"
                }, {
                    name: "minimum",
                    label: "Minimum",
                    defaultDimension: "minimum"
                }, {
                    name: "maximum",
                    label: "Maximum",
                    defaultDimension: "maximum"
                } ].forEach(function(info) {
                    this._addVisualRole(info.name, def.create(roleSpecBase, info));
                }, this);
            },
            _getOrthoRoles: function() {
                return pvc.visual.BoxPlot.measureRolesNames.map(this.visualRole, this);
            },
            _getCategoryRoleSpec: function() {
                return def.set(this.base(), "requireIsDiscrete", !0);
            }
        },
        type: {
            methods: {
                measureRolesNames: [ "median", "lowerQuartil", "upperQuartil", "minimum", "maximum" ]
            }
        },
        options: {
            Stacked: {
                resolve: null,
                value: !1
            },
            LayoutMode: {
                resolve: "_resolveFull",
                cast: pvc.parseBoxLayoutMode,
                value: "grouped"
            },
            BoxSizeRatio: {
                resolve: "_resolveFull",
                cast: function(value) {
                    value = def.number.to(value);
                    return null == value ? 1 : .05 > value ? .05 : value > 1 ? 1 : value;
                },
                value: .9
            },
            BoxSizeMax: {
                resolve: "_resolveFull",
                data: {
                    resolveV1: function(optionInfo) {
                        return this._specifyChartOption(optionInfo, "maxBoxSize"), !0;
                    }
                },
                cast: function(value) {
                    value = def.number.to(value);
                    return null == value ? 1 / 0 : 1 > value ? 1 : value;
                },
                value: 1 / 0
            }
        }
    }));
    pvc.visual.Plot.registerClass(pvc.visual.BoxPlot);
    def.type("pvc.BoxplotPanel", pvc.CategoricalAbstractPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.boxSizeRatio = plot.option("BoxSizeRatio");
        this.boxSizeMax = plot.option("BoxSizeMax");
        chart.bpChartPanel || (chart.bpChartPanel = this);
    }).add({
        plotType: "box",
        _v1DimRoleName: {
            value: "median"
        },
        _createCore: function() {
            function defaultColor(scene, type) {
                var color = this.base(scene, type);
                return "stroke" === type ? color.darker(1) : color;
            }
            function setupHCateg(sign) {
                sign.optionalMark(a_width, function() {
                    return this.parent[a_width]();
                }).optionalMark(a_left, function() {
                    return this.parent[a_width]() / 2 - this[a_width]() / 2;
                });
                return sign;
            }
            function setupRule(rule) {
                return rule.override("defaultColor", defaultColor).override("interactiveStrokeWidth", function(scene, strokeWidth) {
                    return strokeWidth;
                }).override("interactiveColor", function(scene, color, type) {
                    return "stroke" === type ? color : this.base(scene, color, type);
                });
            }
            function setupRuleWhisker(rule) {
                return setupRule(rule).optionalMark(a_left, function() {
                    return this.parent[a_width]() / 2 - this[a_width]() / 2;
                });
            }
            function setupHRule(rule) {
                return setupRule(setupHCateg(rule));
            }
            this.base();
            var data = this.visibleData({
                ignoreNulls: !1
            }), baseAxis = this.axes.base, axisCategDatas = baseAxis.domainItems(), axisSeriesDatas = this.visualRoles.series.flatten(this.partData(), {
                visible: !0,
                isNull: this.chart.options.ignoreNulls ? !1 : null
            }).childNodes, rootScene = this._buildScene(data, axisSeriesDatas, axisCategDatas), a_bottom = this.isOrientationVertical() ? "bottom" : "left", a_left = this.anchorOrtho(a_bottom), a_width = this.anchorLength(a_bottom), a_height = this.anchorOrthoLength(a_bottom), pvSeriesPanel = new pvc.visual.Panel(this, this.pvPanel).lock("data", rootScene.childNodes).pvMark, extensionIds = [ "panel" ];
            this.compatVersion() <= 1 && extensionIds.push("");
            this.pvBoxPanel = new pvc.visual.Panel(this, pvSeriesPanel, {
                extensionId: extensionIds
            }).lock("data", function(seriesScene) {
                return seriesScene.childNodes;
            }).pvMark[a_width](function(scene) {
                return scene.vars.category.boxWidth;
            })[a_left](function(scene) {
                var catVar = scene.vars.category;
                return catVar.boxLeft + catVar.boxWidth / 2 - this[a_width]() / 2;
            });
            this.pvBar = setupHCateg(new pvc.visual.Bar(this, this.pvBoxPanel, {
                extensionId: "boxBar",
                freePosition: !0,
                normalStroke: !0
            })).intercept("visible", function(scene) {
                return scene.vars.category.showBox && this.delegateExtension(!0);
            }).lockMark(a_bottom, function(scene) {
                return scene.vars.category.boxBottom;
            }).lockMark(a_height, function(scene) {
                return scene.vars.category.boxHeight;
            }).override("defaultColor", defaultColor).override("interactiveColor", function(scene, color, type) {
                return "stroke" === type ? color : this.base(scene, color, type);
            }).override("defaultStrokeWidth", def.fun.constant(1)).override("interactiveStrokeWidth", function(scene, strokeWidth) {
                return strokeWidth;
            }).pvMark;
            this.pvRuleWhiskerUpper = setupRuleWhisker(new pvc.visual.Rule(this, this.pvBoxPanel, {
                extensionId: "boxRuleWhisker",
                freePosition: !0,
                noHover: !1,
                noSelect: !1,
                noClick: !1,
                noDoubleClick: !1,
                noTooltip: !1,
                showsInteraction: !0
            })).intercept("visible", function(scene) {
                return scene.vars.category.showRuleWhiskerUpper && this.delegateExtension(!0);
            }).pvMark.lock(a_bottom, function(scene) {
                return scene.vars.category.ruleWhiskerUpperBottom;
            }).lock(a_height, function(scene) {
                return scene.vars.category.ruleWhiskerUpperHeight;
            });
            this.pvRuleWhiskerLower = setupRuleWhisker(new pvc.visual.Rule(this, this.pvBoxPanel, {
                extensionId: "boxRuleWhisker",
                freePosition: !0,
                noHover: !1,
                noSelect: !1,
                noClick: !1,
                noDoubleClick: !1,
                noTooltip: !1,
                showsInteraction: !0
            })).intercept("visible", function(scene) {
                return scene.vars.category.showRuleWhiskerBelow && this.delegateExtension(!0);
            }).pvMark.lock(a_bottom, function(scene) {
                return scene.vars.category.ruleWhiskerLowerBottom;
            }).lock(a_height, function(scene) {
                return scene.vars.category.ruleWhiskerLowerHeight;
            });
            this.pvRuleMin = setupHRule(new pvc.visual.Rule(this, this.pvBoxPanel, {
                extensionId: "boxRuleMin",
                freePosition: !0,
                noHover: !1,
                noSelect: !1,
                noClick: !1,
                noDoubleClick: !1,
                noTooltip: !1,
                showsInteraction: !0
            })).intercept("visible", function(scene) {
                return null != scene.vars.minimum.value && this.delegateExtension(!0);
            }).pvMark.lock(a_bottom, function(scene) {
                return scene.vars.minimum.position;
            });
            this.pvRuleMax = setupHRule(new pvc.visual.Rule(this, this.pvBoxPanel, {
                extensionId: "boxRuleMax",
                freePosition: !0,
                noHover: !1,
                noSelect: !1,
                noClick: !1,
                noDoubleClick: !1,
                noTooltip: !1,
                showsInteraction: !0
            })).intercept("visible", function(scene) {
                return null != scene.vars.maximum.value && this.delegateExtension(!0);
            }).pvMark.lock(a_bottom, function(scene) {
                return scene.vars.maximum.position;
            });
            this.pvRuleMedian = setupHRule(new pvc.visual.Rule(this, this.pvBoxPanel, {
                extensionId: "boxRuleMedian",
                freePosition: !0,
                noHover: !1,
                noSelect: !1,
                noClick: !1,
                noDoubleClick: !1,
                noTooltip: !1,
                showsInteraction: !0
            })).intercept("visible", function(scene) {
                return null != scene.vars.median.value && this.delegateExtension(!0);
            }).lockMark(a_bottom, function(scene) {
                return scene.vars.median.position;
            }).override("defaultStrokeWidth", def.fun.constant(2)).pvMark;
        },
        renderInteractive: function() {
            this.pvBoxPanel.render();
        },
        _buildSceneCore: function(data, axisSeriesDatas, axisCategDatas) {
            function createSeriesScene(axisSeriesData, seriesIndex) {
                var seriesScene = new pvc.visual.Scene(rootScene, {
                    source: axisSeriesData
                }), seriesKey = axisSeriesData.key;
                seriesScene.vars.series = pvc_ValueLabelVar.fromComplex(axisSeriesData);
                colorVarHelper.onNewScene(seriesScene, !1);
                axisCategDatas.forEach(function(axisCategData) {
                    createCategScene(seriesScene, seriesKey, axisCategData, seriesIndex);
                });
            }
            function createCategScene(seriesScene, seriesKey, axisCategData, seriesIndex) {
                var categData = data.child(axisCategData.key), group = categData && categData.child(seriesKey), categScene = new pvc.visual.Scene(seriesScene, {
                    source: group
                }), vars = categScene.vars, catVar = vars.category = pvc_ValueLabelVar.fromComplex(categData), x = baseScale(categData.value), boxLeft = x + (isGrouped ? boxesOffsetLeft + seriesIndex * boxStep : -boxWidth / 2);
                def.set(catVar, "group", categData, "x", x, "width", bandWidth, "boxWidth", boxWidth, "boxLeft", boxLeft);
                measureVisualRoleInfos.forEach(function(roleInfo) {
                    var dimName, svar;
                    if (group && (dimName = roleInfo.dimName)) {
                        var dim = group.dimensions(dimName), value = dim.value(visibleKeyArgs);
                        svar = new pvc_ValueLabelVar(value, dim.format(value));
                        svar.position = orthoScale(value);
                    } else {
                        svar = new pvc_ValueLabelVar(null, "");
                        svar.position = null;
                    }
                    vars[roleInfo.roleName] = svar;
                });
                colorVarHelper.onNewScene(categScene, !0);
                var bottom, top, hasMin = null != vars.minimum.value, hasLower = null != vars.lowerQuartil.value, hasMedian = null != vars.median.value, hasUpper = null != vars.upperQuartil.value, show = hasLower || hasUpper;
                if (show) {
                    bottom = hasLower ? vars.lowerQuartil.position : hasMedian ? vars.median.position : vars.upperQuartil.position;
                    top = hasUpper ? vars.upperQuartil.position : hasMedian ? vars.median.position : vars.lowerQuartil.position;
                    show = top !== bottom;
                    if (show) {
                        catVar.boxBottom = bottom;
                        catVar.boxHeight = top - bottom;
                    }
                }
                catVar.showBox = show;
                show = null != vars.maximum.value;
                if (show) {
                    bottom = hasUpper ? vars.upperQuartil.position : hasMedian ? vars.median.position : hasLower ? vars.lowerQuartil.position : hasMin ? vars.minimum.position : null;
                    show = null != bottom;
                    if (show) {
                        catVar.ruleWhiskerUpperBottom = bottom;
                        catVar.ruleWhiskerUpperHeight = vars.maximum.position - bottom;
                    }
                }
                catVar.showRuleWhiskerUpper = show;
                show = hasMin;
                if (show) {
                    top = hasLower ? vars.lowerQuartil.position : hasMedian ? vars.median.position : hasUpper ? vars.upperQuartil.position : null;
                    show = null != top;
                    if (show) {
                        bottom = vars.minimum.position;
                        catVar.ruleWhiskerLowerHeight = top - bottom;
                        catVar.ruleWhiskerLowerBottom = bottom;
                    }
                }
                catVar.showRuleWhiskerBelow = show;
            }
            var boxWidth, boxStep, boxesOffsetLeft, measureVisualRoleInfos = def.query(this.visualRoleList).where(function(r) {
                return !r.isDiscrete() && r.isMeasure;
            }).select(function(r) {
                return {
                    roleName: r.name,
                    dimName: r.lastDimensionName()
                };
            }).array(), visibleKeyArgs = {
                visible: !0,
                zeroIfNone: !1
            }, rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: data
            }), baseScale = this.axes.base.scale, bandWidth = baseScale.range().band, boxSizeMax = this.boxSizeMax, orthoScale = this.axes.ortho.scale, colorVarHelper = new pvc.visual.RoleVarHelper(rootScene, "color", this.visualRoles.color), isGrouped = "overlapped" !== this.plot.option("LayoutMode");
            if (isGrouped) {
                var clip, boxesWidth, boxesWidthWithMargin, seriesCount = axisSeriesDatas.length, boxSizeRatio = this.boxSizeRatio;
                boxWidth = seriesCount ? 1 === seriesCount ? bandWidth : boxSizeRatio * bandWidth / seriesCount : 0;
                clip = boxWidth > boxSizeMax;
                clip && (boxWidth = boxSizeMax);
                boxesWidth = seriesCount * boxWidth;
                if (clip) {
                    boxesWidthWithMargin = boxesWidth / boxSizeRatio;
                    boxesOffsetLeft = -boxesWidthWithMargin / 2;
                } else {
                    boxesWidthWithMargin = bandWidth;
                    boxesOffsetLeft = -bandWidth / 2;
                }
                if (seriesCount > 1) {
                    var boxMargin = (boxesWidthWithMargin - boxesWidth) / (seriesCount - 1);
                    boxStep = boxWidth + boxMargin;
                } else boxStep = 0;
            } else boxWidth = Math.min(bandWidth, boxSizeMax);
            axisSeriesDatas.forEach(createSeriesScene);
            return rootScene;
        }
    });
    pvc.PlotPanel.registerClass(pvc.BoxplotPanel);
    def.type("pvc.BoxplotChart", pvc.CategoricalAbstract).add({
        _defaultAxisBandSizeRatio: 1 / 3,
        _getTranslationClass: function(translOptions) {
            return def.type(this.base(translOptions)).methods({
                _configureTypeCore: function() {
                    this._configureTypeByPhysicalGroup("series");
                    this._configureTypeByPhysicalGroup("category");
                    pvc.visual.BoxPlot.measureRolesNames.forEach(function(roleName) {
                        this._configureTypeByPhysicalGroup("value", roleName, 1, 1);
                    }, this);
                }
            });
        },
        _createPlotsInternal: function() {
            this._addPlot(new pvc.visual.BoxPlot(this));
            this.options.plot2 && this._addPlot(new pvc.visual.PointPlot(this, {
                name: "plot2",
                spec: {
                    visualRoles: {
                        value: {
                            from: "main.median"
                        }
                    }
                },
                defaults: {
                    LinesVisible: !0,
                    DotsVisible: !0,
                    ColorAxis: 2,
                    OrthoAxis: 1
                }
            }));
        },
        _initAxesEnd: function() {
            var typeAxes = this.axesByType.ortho;
            typeAxes && typeAxes.forEach(function(axis) {
                axis.option.defaults({
                    Offset: .02
                });
            });
            this.base();
        },
        defaults: {
            crosstabMode: !1
        }
    });
    pvc.parseTreemapLayoutMode = pvc.makeEnumParser("layoutMode", [ "squarify", "slice-and-dice", "slice", "dice" ], "squarify");
    pvc.parseTreemapColorMode = pvc.makeEnumParser("colorMode", [ "byParent", "bySelf" ], "byParent");
    def("pvc.visual.TreemapPlot", pvc.visual.Plot.extend({
        init: function(chart, keyArgs) {
            this.base(chart, keyArgs);
            if (!(chart instanceof pvc.TreemapChart)) throw def.error(def.format("Plot type '{0}' can only be used from within a treemap chart.", [ this.type ]));
        },
        methods: {
            type: "treemap",
            _initVisualRoles: function() {
                this.base();
                this._addVisualRole("category", {
                    isRequired: !0,
                    defaultDimension: "category*",
                    autoCreateDimension: !0,
                    rootLabel: this.option("RootCategoryLabel")
                });
                this._addVisualRole("size", {
                    isMeasure: !0,
                    isRequired: !1,
                    isPercent: !0,
                    requireSingleDimension: !0,
                    requireIsDiscrete: !1,
                    valueType: Number,
                    defaultDimension: "size"
                });
            },
            _getColorRoleSpec: function() {
                return {
                    defaultSourceRole: "category",
                    defaultDimension: "color*",
                    rootLabel: this.option("RootCategoryLabel"),
                    requireIsDiscrete: !0
                };
            },
            createVisibleData: function(baseData, ka) {
                return this.visualRoles.category.select(baseData, ka);
            },
            _initDataCells: function() {
                this.base();
                this._addDataCell(new pvc.visual.DataCell(this, "size", this.option("SizeAxis") - 1, this.visualRole("size"), this.option("DataPart")));
            }
        },
        options: {
            SizeRole: {
                resolve: "_resolveFixed",
                value: "size"
            },
            SizeAxis: {
                resolve: "_resolveFixed",
                value: 1
            },
            ValuesAnchor: {
                cast: pvc.parseAnchor,
                value: "center"
            },
            ValuesVisible: {
                value: !0
            },
            ValuesMask: {
                resolve: "_resolveFull",
                value: "{category}"
            },
            ValuesOptimizeLegibility: {
                value: !0
            },
            LayoutMode: {
                resolve: "_resolveFull",
                cast: pvc.parseTreemapLayoutMode,
                value: "squarify"
            },
            ColorMode: {
                resolve: "_resolveFull",
                cast: pvc.parseTreemapColorMode,
                value: "byparent"
            },
            RootCategoryLabel: {
                resolve: "_resolveFull",
                cast: String,
                value: "All"
            }
        }
    }));
    pvc.visual.Plot.registerClass(pvc.visual.TreemapPlot);
    def.type("pvc.visual.TreemapDiscreteColorAxis", pvc.visual.ColorAxis).init(function(chart, type, index, keyArgs) {
        this.base(chart, type, index, keyArgs);
        this.isByParent = "byparent" === chart.plots.treemap.option("ColorMode");
    }).add({
        domainItemValueProp: function() {
            return this.role && this.role.grouping.isSingleDimension ? "value" : "absKey";
        },
        domainGroupOperator: function() {
            return "select";
        },
        _calcAvgColor: function(colors) {
            var L = colors.length;
            if (L > 1) {
                var r = 0, g = 0, b = 0, a = 0;
                colors.forEach(function(c) {
                    var rgb = c.rgb();
                    r += rgb.r;
                    g += rgb.g;
                    b += rgb.b;
                    a += rgb.a;
                });
                var f = Math.floor;
                return pv.rgb(f(r / L), f(g / L), f(b / L), f(a / L));
            }
            var color = colors[0];
            return L ? color.darker(.7) : color;
        },
        _getBaseScheme: function() {
            var me = this, isNotDegenerate = function(data) {
                return null != data.value;
            }, children = function(data) {
                return data.children().where(isNotDegenerate);
            }, hasChildren = function(data) {
                return children(data).any();
            }, hasDerivedColor = function(data) {
                return children(data).any(hasChildren);
            }, derivedColorDatas = def.query(this.domainData().nodes()).where(hasDerivedColor).array(), baseScheme = me.option("Colors");
            return function(d) {
                var domainKeys = d instanceof Array ? d : def.array.copy(arguments), derivedDatasByKey = def.query(derivedColorDatas).object({
                    name: function(itemData) {
                        return me.domainItemValue(itemData);
                    }
                });
                def.array.removeIf(domainKeys, function(k) {
                    return def.hasOwnProp.call(derivedDatasByKey, k);
                });
                var baseScale = baseScheme(domainKeys), derivedColorMap = {}, getColor = function(itemData) {
                    var c, k = me.domainItemValue(itemData);
                    if (def.hasOwnProp.call(derivedDatasByKey, k)) {
                        c = def.getOwn(derivedColorMap, k);
                        if (!c) {
                            var colors = children(itemData).select(getColor).array();
                            if (!colors.length) throw def.assert("Should have at least one child that is also a parent.");
                            c = derivedColorMap[k] = me._calcAvgColor(colors);
                        }
                    } else {
                        var map = me.option.isSpecified("Map") && me.option("Map");
                        c = map && map[k] ? map[k] : me.option("PreserveMap") && me._state.preservedMap && me._state.preservedMap[k] ? me._state.preservedMap[k] : baseScale(k);
                    }
                    return c;
                };
                derivedColorDatas.forEach(getColor);
                var scale = function(k) {
                    return def.getOwn(derivedColorMap, k) || baseScale(k);
                };
                def.copy(scale, baseScale);
                var d2, r2;
                scale.domain = function() {
                    if (arguments.length) throw def.error.operationInvalid("The scale cannot be modified.");
                    return d2 || (d2 = def.array.append(def.ownKeys(derivedColorMap), domainKeys));
                };
                scale.range = function(newR) {
                    if (arguments.length) {
                        var derivedRangeKeys = def.own(derivedColorMap).map(function(c) {
                            return c.key;
                        }), newRange = newR;
                        def.array.removeIf(newRange, function(c) {
                            return derivedRangeKeys.indexOf(c.key) > -1;
                        });
                        newRange.length && baseScale.range(newRange);
                        derivedColorMap = {};
                        derivedColorDatas.forEach(getColor);
                    }
                    return r2 || (r2 = def.array.append(def.own(derivedColorMap), baseScale.range()));
                };
                return scale;
            };
        },
        _selectDomainItems: function(domainData) {
            var candidates = def.query(domainData.nodes()), isNotDegenerate = function(data) {
                return null != data.value;
            }, children = function(data) {
                return data.children().where(isNotDegenerate);
            }, hasChildren = function(data) {
                return children(data).any();
            }, isLeaf = function(data) {
                return !hasChildren(data);
            };
            return this.isByParent ? candidates.where(function(itemData) {
                return itemData.parent ? isNotDegenerate(itemData) && hasChildren(itemData) : isLeaf(itemData) || children(itemData).any(isLeaf);
            }) : candidates.where(function(itemData) {
                return (!itemData.parent || isNotDegenerate(itemData)) && isLeaf(itemData);
            });
        }
    });
    def.type("pvc.TreemapPanel", pvc.PlotPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.axes.size = chart._getAxis("size", (plot.option("SizeAxis") || 0) - 1);
        this.layoutMode = plot.option("LayoutMode");
    }).add({
        plotType: "treemap",
        _createCore: function(layoutInfo) {
            var me = this, cs = layoutInfo.clientSize, rootScene = me._buildScene();
            if (rootScene) {
                if (!rootScene.childNodes.length && !this.chart.visualRoles.multiChart.isBound()) throw new pvc.InvalidDataException("Unable to create a treemap chart, please check the data values.", "all-zero-data");
                var lw0 = def.number.to(me._getConstantExtension("leaf", "lineWidth"), 1), lw = lw0, lw2 = lw / 2, sizeProp = me.visualRoles.size.isBound() ? me.axes.size.scale.by1(function(scene) {
                    return scene.vars.size.value;
                }) : 100, panel = me.pvTreemapPanel = new pvc.visual.Panel(me, me.pvPanel, {
                    panelType: pv.Layout.Treemap,
                    extensionId: "panel"
                }).pvMark.lock("visible", !0).lock("nodes", rootScene.nodes()).lock("left", lw2).lock("top", lw2).lock("width", cs.width - lw).lock("height", cs.height - lw).lock("size", sizeProp).lock("mode", me.layoutMode).lock("order", null).lock("round", !1);
                panel.node.left(function(n) {
                    return n.x + lw2;
                }).top(function(n) {
                    return n.y + lw2;
                }).width(function(n) {
                    return n.dx - lw;
                }).height(function(n) {
                    return n.dy - lw;
                });
                var colorAxis = me.axes.color, colorScale = me.visualRoles.color.isBound() ? colorAxis.sceneScale({
                    sceneVarName: "color"
                }) : def.fun.constant(colorAxis.option("Unbound")), pvLeafMark = new pvc.visual.Bar(me, panel.leaf, {
                    extensionId: "leaf"
                }).lockMark("visible").override("defaultColor", function(scene) {
                    return colorScale(scene);
                }).override("defaultStrokeWidth", function() {
                    return lw0;
                }).pvMark.antialias(!1).lineCap("round").strokeDasharray(function(scene) {
                    return scene.vars.size.value < 0 ? "dash" : null;
                });
                new pvc.visual.Bar(me, panel.node, {
                    extensionId: "ascendant",
                    noHover: !0,
                    noSelect: !0,
                    noClick: !0,
                    noDoubleClick: !0,
                    noTooltip: !0
                }).intercept("visible", function(scene) {
                    return !!scene.parent && !!scene.firstChild && this.delegateExtension(!0);
                }).override("anyInteraction", function(scene) {
                    return scene.anyInteraction() || scene.isActiveDescendantOrSelf();
                }).override("defaultStrokeWidth", function() {
                    return 1.5 * lw;
                }).override("interactiveStrokeWidth", function(scene, w) {
                    return this.showsActivity() && scene.isActiveDescendantOrSelf() ? 1.5 * Math.max(1, w) : w;
                }).override("defaultColor", function(scene) {
                    return colorScale(scene);
                }).override("normalColor", def.fun.constant(null)).override("interactiveColor", function(scene, color, type) {
                    if ("stroke" === type) {
                        if (this.showsActivity()) {
                            if (scene.isActiveDescendantOrSelf()) return pv.color(color).brighter(.5);
                            if (scene.anyActive()) return null;
                        }
                        if (this.showsSelection() && scene.isSelectedDescendantOrSelf()) return pv.color(color).brighter(.5);
                    }
                    return null;
                }).pvMark.antialias(!1);
                var label = pvc.visual.ValueLabel.maybeCreate(me, panel.label, {
                    noAnchor: !0
                });
                label && label.pvMark.textMargin(3).sign.optional("textAngle", function(scene) {
                    var text = this.defaultText(scene), pvLabel = this.pvMark;
                    return scene.dx - 2 * pvLabel.textMargin() > pv.Text.measureWidth(text, pvLabel.font()) ? 0 : scene.dx >= scene.dy ? 0 : -Math.PI / 2;
                }).override("calcTextFitInfo", function(scene, text) {
                    var pvLabel = this.pvMark, tm = pvLabel.textMargin();
                    if (!(-1e-6 > tm)) {
                        var ta = pvLabel.textAngle(), isHorizText = Math.abs(Math.sin(ta)) < 1e-6, isVertiText = !isHorizText && Math.abs(Math.cos(ta)) < 1e-6;
                        if (isHorizText || isVertiText) {
                            var hide = !1, m = pv.Text.measure(text, pvLabel.font()), th = .75 * m.height, thMax = scene[isVertiText ? "dx" : "dy"];
                            "middle" !== pvLabel.textBaseline() && (thMax /= 2);
                            thMax -= 2 * tm;
                            hide |= th > thMax;
                            var twMax = scene[isVertiText ? "dy" : "dx"];
                            "center" !== pvLabel.textAlign() && (twMax /= 2);
                            twMax -= 2 * tm;
                            hide |= 0 >= twMax || this.hideOverflowed && m.width > twMax;
                            return {
                                hide: hide,
                                widthMax: twMax
                            };
                        }
                    }
                }).override("getAnchoredToMark", function() {
                    return pvLeafMark;
                });
            }
        },
        renderInteractive: function() {
            this.pvTreemapPanel.render();
        },
        _buildScene: function() {
            function recursive(scene) {
                var group = scene.group;
                scene.vars.category = pvc_ValueLabelVar.fromComplex(group);
                sizeVarHelper.onNewScene(scene, !0);
                if (sizeIsBound && !scene.vars.size.value) {
                    scene.parentNode && scene.parentNode.removeChild(scene);
                    return scene;
                }
                var children = group.children().where(function(childData) {
                    return null != childData.value;
                }).array();
                if (colorGrouping) {
                    var colorGroup = colorByParent && !children.length ? group.parent : group;
                    if (colorGroup) {
                        var colorView = colorGrouping.view(colorGroup);
                        scene.vars.color = new pvc_ValueLabelVar(colorView.keyTrimmed(), colorView.label);
                    } else scene.vars.color = new pvc_ValueLabelVar(null, "");
                } else scene.parent || (scene.vars.color = new pvc_ValueLabelVar(null, ""));
                children.forEach(function(childData) {
                    recursive(new pvc.visual.Scene(scene, {
                        source: childData
                    }));
                });
                return scene;
            }
            var data = this.visibleData({
                ignoreNulls: !1
            });
            if (!data.childCount()) return null;
            var roles = this.visualRoles, rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: data
            }), sizeVarHelper = new pvc.visual.RoleVarHelper(rootScene, "size", roles.size, {
                allowNestedVars: !0,
                hasPercentSubVar: !0
            }), sizeIsBound = roles.size.isBound(), colorGrouping = roles.color && roles.color.grouping, colorByParent = colorGrouping && "byparent" === this.plot.option("ColorMode");
            return recursive(rootScene);
        }
    });
    pvc.PlotPanel.registerClass(pvc.TreemapPanel);
    def.type("pvc.TreemapChart", pvc.BaseChart).add({
        _axisClassByType: {
            size: pvc.visual.NormalizedAxis
        },
        _axisCreateIfUnbound: {
            color: !0
        },
        _getTranslationClass: function(translOptions) {
            return def.type(this.base(translOptions)).methods({
                _configureTypeCore: function() {
                    this._configureTypeByOrgLevel([ "category" ], [ "size" ]);
                }
            });
        },
        _getIsNullDatum: def.fun.constant(),
        _createPlotsInternal: function() {
            this._addPlot(new pvc.visual.TreemapPlot(this));
        },
        _initPlotsEnd: function() {
            this.base();
            null == this.options.legend && (this.options.legend = "byparent" === this.plots.treemap.option("ColorMode"));
        },
        _initAxes: function() {
            if (this.visualRoles.color.isDiscrete()) {
                def.hasOwnProp.call(this, "_axisClassByType") || (this._axisClassByType = Object.create(this._axisClassByType));
                this._axisClassByType.color = pvc.visual.TreemapDiscreteColorAxis;
            } else delete this._axisClassByType;
            return this.base();
        },
        defaults: {
            legend: null
        }
    });
    pvc.parseSunburstSliceOrder = pvc.makeEnumParser("sliceOrder", [ "bySizeAscending", "bySizeDescending", "none" ], "bySizeDescending");
    pvc.parseSunburstColorMode = pvc.makeEnumParser("colorMode", [ "fan", "slice" ], "fan");
    def("pvc.visual.SunburstPlot", pvc.visual.Plot.extend({
        init: function(chart, keyArgs) {
            this.base(chart, keyArgs);
            if (!(chart instanceof pvc.SunburstChart)) throw def.error(def.format("Plot type '{0}' can only be used from within a sunburst chart.", [ this.type ]));
        },
        methods: {
            type: "sunburst",
            _initVisualRoles: function() {
                this.base();
                this._addVisualRole("category", {
                    isRequired: !0,
                    defaultDimension: "category*",
                    autoCreateDimension: !0,
                    rootLabel: this.option("RootCategoryLabel")
                });
                this._addVisualRole("size", {
                    isMeasure: !0,
                    isRequired: !1,
                    isPercent: !0,
                    requireSingleDimension: !0,
                    requireIsDiscrete: !1,
                    valueType: Number,
                    defaultDimension: "size"
                });
            },
            _getColorRoleSpec: function() {
                return {
                    defaultSourceRole: "category",
                    defaultDimension: "color*",
                    requireIsDiscrete: !0,
                    rootLabel: this.option("RootCategoryLabel")
                };
            },
            createVisibleData: function(baseData, ka) {
                return this.visualRoles.category.select(baseData, ka);
            },
            _initDataCells: function() {
                this.base();
                this._addDataCell(new pvc.visual.DataCell(this, "size", this.option("SizeAxis") - 1, this.visualRoles.size, this.option("DataPart")));
            }
        },
        options: {
            SizeRole: {
                resolve: "_resolveFixed",
                value: "size"
            },
            SizeAxis: {
                resolve: "_resolveFixed",
                value: 1
            },
            ValuesAnchor: {
                cast: pvc.parseAnchor,
                value: "center"
            },
            ValuesVisible: {
                value: !0
            },
            ValuesMask: {
                resolve: "_resolveFull",
                value: "{category}"
            },
            ValuesOptimizeLegibility: {
                value: !0
            },
            ColorMode: {
                resolve: "_resolveFull",
                cast: pvc.parseSunburstColorMode,
                value: "fan"
            },
            RootCategoryLabel: {
                resolve: "_resolveFull",
                cast: String,
                value: "All"
            },
            SliceOrder: {
                resolve: "_resolveFull",
                cast: pvc.parseSunburstSliceOrder,
                value: "bySizeDescending"
            },
            EmptySlicesVisible: {
                resolve: "_resolveFull",
                cast: Boolean,
                value: !1
            },
            EmptySlicesLabel: {
                resolve: "_resolveFull",
                cast: String,
                value: ""
            }
        }
    }));
    pvc.visual.Plot.registerClass(pvc.visual.SunburstPlot);
    def("pvc.visual.SunburstDiscreteColorAxis", pvc.visual.ColorAxis.extend({
        methods: {
            domainItemValueProp: function() {
                return this.role && this.role.grouping.isSingleDimension ? "value" : "absKey";
            },
            domainGroupOperator: function() {
                return "select";
            },
            _getBaseScheme: function() {
                var isFanMode = "fan" === this.chart.plots.sunburst.option("ColorMode");
                if (!isFanMode) return this.base();
                var isNotDegenerate = function(data) {
                    return null != data.value;
                }, haveColorMapByKey = def.query(this.domainData().childNodes).where(isNotDegenerate).select(this.domainItemValue.bind(this)).object(), baseScheme = this.option("Colors");
                return function() {
                    function scale(key) {
                        return def.hasOwn(haveColorMapByKey, key) ? baseScale(key) : null;
                    }
                    var baseScale = baseScheme.apply(null, arguments);
                    def.copy(scale, baseScale);
                    return scale;
                };
            },
            _selectDomainItems: function(domainData) {
                var isNotDegenerate = function(data) {
                    return null != data.value;
                }, isFanMode = "fan" === this.chart.plots.sunburst.option("ColorMode");
                return isFanMode ? def.query(domainData.childNodes).where(isNotDegenerate) : def.query(domainData.nodes()).where(function(itemData) {
                    return itemData.parent ? isNotDegenerate(itemData) && !itemData.parent.parent : !1;
                });
            }
        },
        options: {
            SliceBrightnessFactor: {
                resolve: "_resolveFull",
                cast: def.number.toNonNegative,
                value: 1
            }
        }
    }));
    def.type("pvc.SunburstPanel", pvc.PlotPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.axes.size = chart._getAxis("size", (plot.option("SizeAxis") || 0) - 1);
        this.sliceOrder = plot.option("SliceOrder");
        this.emptySlicesVisible = plot.option("EmptySlicesVisible");
        this.emptySlicesLabel = this.emptySlicesVisible ? plot.option("EmptySlicesLabel") : "";
    }).add({
        plotType: "sunburst",
        _createCore: function() {
            var labelFont = this._getConstantExtension("label", "font");
            def.string.is(labelFont) && (this.valuesFont = labelFont);
            var me = this, rootScene = me._buildScene();
            if (rootScene) {
                if (!rootScene.childNodes.length && !this.chart.visualRoles.multiChart.isBound()) throw new pvc.InvalidDataException("Unable to create a sunburst chart, please check the data values.", "all-zero-data");
                var sizeProp = me.visualRoles.size.isBound() ? me.axes.size.scale.by1(function(scene) {
                    return scene.vars.size.value;
                }) : def.fun.constant(100), panel = me.pvSunburstPanel = new pvc.visual.Panel(me, me.pvPanel, {
                    panelType: pv.Layout.Partition.Fill,
                    extensionId: "panel"
                }).pvMark.lock("visible", !0).lock("nodes", rootScene.nodes()).lock("size", sizeProp).lock("orient", "radial"), slice = new pvc.visual.SunburstSlice(this, panel.node, {
                    extensionId: "slice",
                    tooltipArgs: {
                        options: {
                            useCorners: !0,
                            gravity: function() {
                                var ma = this.midAngle(), isRightPlane = Math.cos(ma) >= 0, isTopPlane = Math.sin(ma) >= 0;
                                return isRightPlane ? isTopPlane ? "nw" : "sw" : isTopPlane ? "ne" : "se";
                            }
                        }
                    }
                }), label = pvc.visual.ValueLabel.maybeCreate(me, panel.label, {
                    noAnchor: !0
                });
                label && label.override("defaultText", function(scene) {
                    return scene.isRoot() ? "" : this.base(scene);
                }).override("calcTextFitInfo", function(scene, text) {
                    var pvLabel = this.pvMark, tm = pvLabel.textMargin();
                    if (!(-1e-6 > tm) && "center" === pvLabel.textAlign() && text) {
                        var ma = pvc.normAngle(scene.midAngle), la = pvc.normAngle(pvLabel.textAngle()), sameAngle = Math.abs(ma - la) < 1e-6, oppoAngle = !1;
                        if (!sameAngle) {
                            var la2 = pvc.normAngle(la + Math.PI);
                            oppoAngle = Math.abs(ma - la2) < 1e-6;
                        }
                        if (sameAngle || oppoAngle) {
                            var twMax, ir = scene.innerRadius, irmin = ir, or = scene.outerRadius, a = scene.angle, m = pv.Text.measure(text, pvLabel.font()), hide = !1;
                            if (a < Math.PI) {
                                var th = .85 * m.height, tb = pvLabel.textBaseline(), thEf = "middle" === tb ? th + tm / 2 : 2 * (th + 3 * tm / 2);
                                irmin = Math.max(irmin, thEf / (2 * Math.tan(a / 2)));
                            }
                            twMax = or - tm - irmin;
                            hide |= 0 >= twMax;
                            twMax -= tm;
                            hide |= this.hideOverflowed && m.width > twMax;
                            return {
                                hide: hide,
                                widthMax: twMax
                            };
                        }
                    }
                }).override("getAnchoredToMark", function() {
                    return slice.pvMark;
                });
            }
        },
        renderInteractive: function() {
            this.pvSunburstPanel.render();
        },
        _buildScene: function() {
            function recursive(scene) {
                var group = scene.group, catVar = scene.vars.category = pvc_ValueLabelVar.fromComplex(group);
                emptySlicesLabel && null == catVar.value && (catVar.value = emptySlicesLabel);
                sizeVarHelper.onNewScene(scene, !0);
                if (sizeIsBound && !scene.vars.size.value) {
                    scene.parentNode && scene.parentNode.removeChild(scene);
                    return scene;
                }
                var children = group.children();
                emptySlicesVisible || (children = children.where(function(childData) {
                    return null != childData.value;
                }));
                if (colorGrouping) {
                    var colorView = colorGrouping.view(group);
                    scene.vars.color = new pvc_ValueLabelVar(colorView.keyTrimmed(), colorView.label);
                } else scene.vars.color = new pvc_ValueLabelVar(null, "");
                children.each(function(childData) {
                    recursive(new pvc.visual.SunburstScene(scene, {
                        source: childData
                    }));
                });
                return scene;
            }
            function calculateColor(scene, index, siblingsSize) {
                var baseColor = null, parent = scene.parent;
                if (parent) {
                    baseColor = colorScale(scene);
                    if (!baseColor && !parent.isRoot()) {
                        baseColor = parent.color;
                        baseColor && index && colorBrightnessFactor && (baseColor = baseColor.brighter(colorBrightnessFactor * index / (siblingsSize - 1)));
                    }
                }
                scene.color = baseColor;
                var children = scene.childNodes, childrenSize = children.length;
                children.forEach(function(childScene, index) {
                    calculateColor(childScene, index, childrenSize);
                });
            }
            var data = this.visibleData({
                ignoreNulls: !1
            }), emptySlicesVisible = this.emptySlicesVisible, emptySlicesLabel = this.emptySlicesLabel;
            if (!data.childCount()) return null;
            var roles = this.visualRoles, rootScene = new pvc.visual.SunburstScene(null, {
                panel: this,
                source: data
            }), sizeIsBound = roles.size.isBound(), sizeVarHelper = new pvc.visual.RoleVarHelper(rootScene, "size", roles.size, {
                allowNestedVars: !0,
                hasPercentSubVar: !0
            }), colorGrouping = roles.color && roles.color.grouping, colorAxis = this.axes.color, colorBrightnessFactor = colorAxis.option("SliceBrightnessFactor"), colorScale = roles.color.isBound() ? colorAxis.sceneScale({
                sceneVarName: "color"
            }) : def.fun.constant(colorAxis.option("Unbound"));
            recursive(rootScene);
            if (this.sliceOrder && sizeIsBound && "none" !== this.sliceOrder) {
                var compare = "bysizeascending" === this.sliceOrder ? def.ascending : def.descending;
                rootScene.sort(function(sceneA, sceneB) {
                    return compare(sceneA.vars.size.value, sceneB.vars.size.value) || def.ascending(sceneA.childIndex(), sceneB.childIndex());
                });
            }
            calculateColor(rootScene, 0);
            return rootScene;
        }
    });
    pvc.PlotPanel.registerClass(pvc.SunburstPanel);
    def.type("pvc.visual.SunburstScene", pvc.visual.Scene).add({
        _createSelectedInfo: function() {
            var any = this.chart().data.owner.selectedCount() > 0, isSelected = any && this.datums().all(cdo.Datum.isSelected);
            return {
                any: any,
                is: isSelected
            };
        }
    });
    def.type("pvc.SunburstChart", pvc.BaseChart).add({
        _axisClassByType: {
            size: pvc.visual.NormalizedAxis,
            color: pvc.visual.SunburstDiscreteColorAxis
        },
        _axisCreateIfUnbound: {
            color: !0
        },
        _getTranslationClass: function(translOptions) {
            return def.type(this.base(translOptions)).methods({
                _configureTypeCore: function() {
                    this._configureTypeByOrgLevel([ "category" ], [ "size" ]);
                }
            });
        },
        _getIsNullDatum: def.fun.constant(),
        _createPlotsInternal: function() {
            var sunburstPlot = new pvc.visual.SunburstPlot(this);
            this._addPlot(sunburstPlot);
            this.options.legend = !1;
        }
    });
    def("pvc.visual.SunburstSlice", pvc.visual.Sign.extend({
        init: function(panel, protoMark, keyArgs) {
            var pvMark = protoMark.add(pv.Wedge);
            keyArgs = def.setDefaults(keyArgs, "freeColor", !1);
            this.base(panel, pvMark, keyArgs);
            this._bindProperty("lineWidth", "strokeWidth");
        },
        properties: [ "strokeWidth" ],
        methods: {
            defaultStrokeWidth: def.fun.constant(.5),
            interactiveStrokeWidth: function(scene, strokeWidth) {
                return this.showsActivity() && scene.isActiveDescendantOrSelf() ? 2 * Math.max(1, strokeWidth) : strokeWidth;
            },
            defaultColor: function(scene, type) {
                return scene.color;
            },
            normalColor: function(scene, color, type) {
                return color && "stroke" === type ? color.darker() : color;
            },
            interactiveColor: function(scene, color, type) {
                if (this.showsActivity()) if ("stroke" === type) {
                    if (scene.isActiveDescendantOrSelf()) return color.brighter(2).alpha(.7);
                } else if (scene.isActive) return color.brighter(.2).alpha(.8);
                return this.mayShowNotAmongSelected(scene) ? this.dimColor(color, type) : this.normalColor(scene, color, type);
            }
        }
    }));
    def.type("pvc.visual.BulletPlot", pvc.visual.Plot.extend({
        methods: {
            type: "bullet",
            _initVisualRoles: function() {
                this.base();
                this._addVisualRole("title", {
                    defaultDimension: "title*"
                });
                this._addVisualRole("subTitle", {
                    defaultDimension: "subTitle*"
                });
                this._addVisualRole("value", {
                    isMeasure: !0,
                    requireIsDiscrete: !1,
                    requireSingleDimension: !1,
                    valueType: Number,
                    defaultDimension: "value*"
                });
                this._addVisualRole("marker", {
                    isMeasure: !0,
                    requireIsDiscrete: !1,
                    requireSingleDimension: !1,
                    valueType: Number,
                    defaultDimension: "marker*"
                });
                this._addVisualRole("range", {
                    isMeasure: !0,
                    requireIsDiscrete: !1,
                    requireSingleDimension: !1,
                    valueType: Number,
                    defaultDimension: "range*"
                });
            }
        },
        options: {
            ValuesVisible: {
                value: !0
            }
        }
    }));
    pvc.visual.Plot.registerClass(pvc.visual.BulletPlot);
    def.type("pvc.BulletChart", pvc.BaseChart).add({
        bulletChartPanel: null,
        allowNoData: !0,
        _processOptionsCore: function(options) {
            options.legend = !1;
            options.selectable = !1;
            this.base(options);
        },
        _getTranslationClass: function(translOptions) {
            return def.type(this.base(translOptions)).methods({
                _configureTypeCore: function() {
                    this._configureTypeByOrgLevel([ {
                        name: "title",
                        greedy: !1,
                        maxCount: 1
                    }, {
                        name: "subTitle",
                        greedy: !1,
                        maxCount: 1
                    } ], [ "value", "marker", {
                        name: "range",
                        greedy: !0,
                        maxCount: 1 / 0
                    } ]);
                }
            });
        },
        _createPlotsInternal: function() {
            this._addPlot(new pvc.visual.BulletPlot(this));
        },
        defaults: {
            compatVersion: 1,
            orientation: "horizontal",
            bulletSize: 30,
            bulletSpacing: 50,
            bulletMargin: 100,
            bulletTitle: "Title",
            bulletSubtitle: "",
            bulletTitlePosition: "left",
            tooltipFormat: function(s, c, v) {
                return this.chart.options.valueFormat(v);
            },
            crosstabMode: !1,
            seriesInRows: !1,
            dataTypeCheckingMode: "extended"
        }
    });
    def.type("pvc.BulletChartPanel", pvc.PlotPanel).add({
        plotType: "bullet",
        pvBullets: null,
        pvBullet: null,
        data: null,
        onSelectionChange: null,
        _createCore: function(layoutInfo) {
            var size, angle, align, titleLeftOffset, titleTopOffset, ruleAnchor, leftPos, topPos, titleSpace, chart = this.chart, options = chart.options, data = this.buildData(), me = this, anchor = "horizontal" == options.orientation ? "left" : "bottom";
            if ("horizontal" == options.orientation) {
                size = layoutInfo.clientSize.width - this.chart.options.bulletMargin - 20;
                angle = 0;
                switch (options.bulletTitlePosition) {
                  case "top":
                    leftPos = this.chart.options.bulletMargin;
                    titleLeftOffset = 0;
                    align = "left";
                    titleTopOffset = -12;
                    titleSpace = parseInt(options.titleSize / 2, 10);
                    break;

                  case "bottom":
                    leftPos = this.chart.options.bulletMargin;
                    titleLeftOffset = 0;
                    align = "left";
                    titleTopOffset = options.bulletSize + 32;
                    titleSpace = 0;
                    break;

                  case "right":
                    leftPos = 5;
                    titleLeftOffset = size + 5;
                    align = "left";
                    titleTopOffset = parseInt(options.bulletSize / 2, 10);
                    titleSpace = 0;
                    break;

                  case "left":
                  default:
                    leftPos = this.chart.options.bulletMargin;
                    titleLeftOffset = 0;
                    titleTopOffset = parseInt(options.bulletSize / 2, 10);
                    align = "right";
                    titleSpace = 0;
                }
                ruleAnchor = "bottom";
                topPos = function() {
                    return this.index * (options.bulletSize + options.bulletSpacing) + titleSpace;
                };
            } else {
                size = layoutInfo.clientSize.height - this.chart.options.bulletMargin - 20;
                switch (options.bulletTitlePosition) {
                  case "top":
                    leftPos = this.chart.options.bulletMargin;
                    titleLeftOffset = 0;
                    align = "left";
                    titleTopOffset = -20;
                    angle = 0;
                    topPos = void 0;
                    break;

                  case "bottom":
                    leftPos = this.chart.options.bulletMargin;
                    titleLeftOffset = 0;
                    align = "left";
                    titleTopOffset = size + 20;
                    angle = 0;
                    topPos = 20;
                    break;

                  case "right":
                    leftPos = 5;
                    titleLeftOffset = this.chart.options.bulletSize + 40;
                    align = "left";
                    titleTopOffset = size;
                    angle = -Math.PI / 2;
                    topPos = void 0;
                    break;

                  case "left":
                  default:
                    leftPos = this.chart.options.bulletMargin;
                    titleLeftOffset = -12;
                    titleTopOffset = this.height - this.chart.options.bulletMargin - 20;
                    align = "left";
                    angle = -Math.PI / 2;
                    topPos = void 0;
                }
                ruleAnchor = "right";
                leftPos = function() {
                    return options.bulletMargin + this.index * (options.bulletSize + options.bulletSpacing);
                };
            }
            this.pvBullets = this.pvPanel.add(pv.Panel).data(data)[pvc.BasePanel.orthogonalLength[anchor]](size)[pvc.BasePanel.parallelLength[anchor]](this.chart.options.bulletSize).margin(20).left(leftPos).top(topPos);
            this.pvBullet = this.pvBullets.add(pv.Layout.Bullet).orient(anchor).ranges(function(d) {
                return d.ranges;
            }).measures(function(d) {
                return d.measures;
            }).markers(function(d) {
                return d.markers;
            });
            if (chart.clickable() && this.clickAction) {
                var me = this;
                this.pvBullet.cursor("pointer").event("click", function(d) {
                    var s = d.title, c = d.subtitle, ev = pv.event;
                    return me.clickAction(s, c, d.measures, ev);
                });
            }
            this.pvBulletRange = this.pvBullet.range.add(pv.Bar);
            this.pvBulletMeasure = this.pvBullet.measure.add(pv.Bar).text(function(v, d) {
                return d.formattedMeasures[this.index];
            });
            this.pvBulletMarker = this.pvBullet.marker.add(pv.Dot).shape("square").fillStyle("white").text(function(v, d) {
                return d.formattedMarkers[this.index];
            });
            if (this.showsTooltip()) {
                var myself = this;
                this.pvBulletMeasure.localProperty("tooltip").tooltip(function(v, d) {
                    var s = d.title, c = d.subtitle;
                    return chart.options.tooltipFormat.call(myself, s, c, v);
                });
                this.pvBulletMarker.localProperty("tooltip").tooltip(function(v, d) {
                    var s = d.title, c = d.subtitle;
                    return chart.options.tooltipFormat.call(myself, s, c, v);
                });
                this.pvBulletMeasure.event("mouseover", pv.Behavior.tipsy(this.chart._tooltipOptions));
                this.pvBulletMarker.event("mouseover", pv.Behavior.tipsy(this.chart._tooltipOptions));
            }
            this.pvBulletRule = this.pvBullet.tick.add(pv.Rule);
            this.pvBulletRuleLabel = this.pvBulletRule.anchor(ruleAnchor).add(pv.Label).text(function(v) {
                return me.pvBullet.x.tickFormat(v, this.index);
            });
            this.pvBulletTitle = this.pvBullet.anchor(anchor).add(pv.Label).font("bold 12px sans-serif").textAngle(angle).left(-10).textAlign(align).textBaseline("bottom").left(titleLeftOffset).top(titleTopOffset).text(function(d) {
                return d.formattedTitle;
            });
            this.pvBulletSubtitle = this.pvBullet.anchor(anchor).add(pv.Label).textStyle("#666").textAngle(angle).textAlign(align).textBaseline("top").left(titleLeftOffset).top(titleTopOffset).text(function(d) {
                return d.formattedSubtitle;
            });
            var doubleClickAction = "function" == typeof options.axisDoubleClickAction ? function(d, e) {
                options.axisDoubleClickAction(d, e);
            } : null;
            if (chart.doubleClickable() && doubleClickAction) {
                this.pvBulletTitle.cursor("pointer").events("all").event("dblclick", function(d) {
                    doubleClickAction(d, arguments[arguments.length - 1]);
                });
                this.pvBulletSubtitle.cursor("pointer").events("all").event("dblclick", function(d) {
                    doubleClickAction(d, arguments[arguments.length - 1]);
                });
            }
        },
        applyExtensions: function() {
            this.base();
            this.extend(this.pvBullets, "bulletsPanel");
            this.extend(this.pvBullet, "bulletPanel");
            this.extend(this.pvBulletRange, "bulletRange");
            this.extend(this.pvBulletMeasure, "bulletMeasure");
            this.extend(this.pvBulletMarker, "bulletMarker");
            this.extend(this.pvBulletRule, "bulletRule");
            this.extend(this.pvBulletRuleLabel, "bulletRuleLabel");
            this.extend(this.pvBulletTitle, "bulletTitle");
            this.extend(this.pvBulletSubtitle, "bulletSubtitle");
        },
        _getExtensionId: function() {
            return [ {
                abs: "content"
            } ].concat(this.base());
        },
        buildData: function() {
            var data, chart = this.chart, options = chart.options, titleRole = chart.visualRoles.title, titleGrouping = titleRole.grouping, subTitleRole = chart.visualRoles.subTitle, subTitleGrouping = subTitleRole.grouping, valueRole = chart.visualRoles.value, valueGrouping = valueRole.grouping, markerRole = chart.visualRoles.marker, markerGrouping = markerRole.grouping, rangeRole = chart.visualRoles.range, rangeGrouping = rangeRole.grouping, defaultData = {
                title: options.bulletTitle,
                formattedTitle: options.bulletTitle,
                subtitle: options.bulletSubtitle,
                formattedSubtitle: options.bulletSubtitle,
                ranges: def.array.to(options.bulletRanges) || [],
                measures: def.array.to(options.bulletMeasures) || [],
                markers: def.array.to(options.bulletMarkers) || []
            };
            def.set(defaultData, "formattedRanges", defaultData.ranges.map(String), "formattedMeasures", defaultData.measures.map(String), "formattedMarkers", defaultData.markers.map(String));
            data = valueGrouping || titleGrouping || markerGrouping || subTitleGrouping || rangeGrouping ? chart.data.datums().select(function(datum) {
                var view, d = Object.create(defaultData);
                if (valueGrouping) {
                    view = valueGrouping.view(datum);
                    d.measures = view.values();
                    d.formattedMeasures = view.labels();
                }
                if (titleGrouping) {
                    view = titleGrouping.view(datum);
                    d.title = view.value;
                    d.formattedTitle = view.label;
                }
                if (subTitleGrouping) {
                    view = subTitleGrouping.view(datum);
                    d.subtitle = view.value;
                    d.formattedSubtitle = view.label;
                }
                if (markerGrouping) {
                    view = markerGrouping.view(datum);
                    d.markers = view.values();
                    d.formattedMarkers = view.labels();
                }
                if (rangeGrouping) {
                    view = rangeGrouping.view(datum);
                    d.ranges = view.values();
                    d.formattedRanges = view.labels();
                }
                return d;
            }, this).array() : [ defaultData ];
            return data;
        }
    });
    pvc.PlotPanel.registerClass(pvc.BulletChartPanel);
    def.type("pvc.ParallelCoordinates", pvc.BaseChart).init(function(options) {
        options = options || {};
        options.dimensions = options.dimensions || {};
        options.dimensions.value || (options.dimensions.value = {
            valueType: null
        });
        this.base(options);
    }).add({
        parCoordPanel: null,
        _createContent: function(parentPanel, contentOptions) {
            this.parCoordPanel = new pvc.ParCoordPanel(this, parentPanel, def.create(contentOptions, {
                topRuleOffset: this.options.topRuleOffset,
                botRuleOffset: this.options.botRuleOffset,
                leftRuleOffset: this.options.leftRuleOffset,
                rightRuleOffset: this.options.rightRuleOffset,
                sortCategorical: this.options.sortCategorical,
                mapAllDimensions: this.options.mapAllDimensions,
                numDigits: this.options.numDigits
            }));
        },
        defaults: def.create(pvc.BaseChart.prototype.defaults, {
            compatVersion: 1,
            topRuleOffset: 30,
            botRuleOffset: 30,
            leftRuleOffset: 60,
            rightRuleOffset: 60,
            sortCategorical: !0,
            mapAllDimensions: !0,
            numDigits: 0
        })
    });
    def.type("pvc.ParCoordPanel", pvc.BasePanel).add({
        anchor: "fill",
        pvParCoord: null,
        dimensions: null,
        dimensionDescr: null,
        data: null,
        retrieveData: function() {
            var data = this.chart.data, numDigit = this.chart.options.numDigits;
            this.dimensions = data.getVisibleCategories();
            var values = data.getValues(), dataRowIndex = data.getVisibleSeriesIndexes(), pCoordIndex = data.getVisibleCategoriesIndexes(), pCoordKeys = data.getCategories(), pCoordMapping = this.chart.options.mapAllDimensions ? pCoordIndex.map(function(d) {
                return isNaN(values[d][0]) ? {
                    categorical: !0,
                    len: 0,
                    map: []
                } : {
                    categorical: !1,
                    len: 0,
                    map: [],
                    displayValue: []
                };
            }) : pCoordIndex.map(function(d) {
                return isNaN(values[d][0]) ? {
                    categorical: !0,
                    len: 0,
                    map: []
                } : null;
            }), coordMapUpdate = function(i, val) {
                var cMap = pCoordMapping[i], k = null;
                if (cMap.categorical) {
                    k = cMap.map[val];
                    if (null == k) {
                        k = cMap.len;
                        cMap.len++;
                        cMap.map[val] = k;
                    }
                } else {
                    var keyVal = val.toFixed(numDigit);
                    k = cMap.map[keyVal];
                    if (null == k) {
                        k = cMap.len;
                        cMap.len++;
                        cMap.map[keyVal] = k;
                        cMap.displayValue[keyVal] = val;
                    }
                }
                return k;
            };
            for (var d in pCoordMapping) pCoordMapping.hasOwnProperty(d) && pCoordMapping[d] && pCoordMapping[d].categorical && (pCoordMapping[d].displayValue = pCoordMapping[d].map);
            var i, item, k;
            if (this.chart.options.sortCategorical || this.chart.options.mapAllDimensions) for (i = 0; i < pCoordMapping.length; i++) if (pCoordMapping[i]) {
                for (var col = 0; col < values[i].length; col++) coordMapUpdate(i, values[i][col]);
                var cMap = pCoordMapping[i].map, sorted = [];
                for (item in cMap) cMap.hasOwnProperty(item) && sorted.push(item);
                sorted.sort();
                if (pCoordMapping[i].categorical) for (k = 0; k < sorted.length; k++) cMap[sorted[k]] = k; else for (k = 0; k < sorted.length; k++) cMap[sorted[k]].index = k;
            }
            var generateHashMap = function(col) {
                var record = {};
                for (var j in pCoordIndex) pCoordIndex.hasOwnProperty(j) && (record[pCoordKeys[j]] = pCoordMapping[j] ? coordMapUpdate(j, values[j][col]) : values[j][col]);
                return record;
            };
            this.data = dataRowIndex.map(function(col) {
                return generateHashMap(col);
            });
            var descrVals = this.dimensions.map(function(cat) {
                var item2 = {}, elements = cat.split("__");
                item2.id = cat;
                item2.name = elements[0];
                item2.unit = elements.length > 1 ? elements[1] : "";
                return item2;
            });
            for (i = 0; i < descrVals.length; i++) {
                item = descrVals[i];
                var index = pCoordIndex[i];
                item.orgRowIndex = index;
                var theMin, theMax, theMin2, theMax2, v, len = values[index].length;
                if (pCoordMapping[index]) {
                    theMin = theMax = theMin2 = theMax2 = pCoordMapping[index].displayValue[values[index][0]];
                    for (k = 1; len > k; k++) {
                        v = pCoordMapping[index].displayValue[values[index][k]];
                        if (theMin > v) {
                            theMin2 = theMin;
                            theMin = v;
                        }
                        if (v > theMax) {
                            theMax2 = theMax;
                            theMax = v;
                        }
                    }
                } else {
                    theMin = theMax = theMin2 = theMax2 = values[index][0];
                    for (k = 1; len > k; k++) {
                        v = values[index][k];
                        if (theMin > v) {
                            theMin2 = theMin;
                            theMin = v;
                        }
                        if (v > theMax) {
                            theMax2 = theMax;
                            theMax = v;
                        }
                    }
                }
                var theStep = (theMax - theMax2 + (theMin2 - theMin)) / 2;
                item.min = theMin;
                item.max = theMax;
                item.step = theStep;
                item.categorical = !1;
                if (pCoordMapping[index]) {
                    item.map = pCoordMapping[index].map;
                    item.mapLength = pCoordMapping[index].len;
                    item.categorical = pCoordMapping[index].categorical;
                    if (!item.categorical) {
                        item.orgValue = [];
                        var theMap = pCoordMapping[index].map;
                        for (var key in theMap) theMap.hasOwnProperty(key) && (item.orgValue[theMap[key]] = 0 + key);
                    }
                }
            }
            var genKeyVal = function(keys, vals) {
                for (var record = {}, i = 0; i < keys.length; i++) record[keys[i]] = vals[i];
                return record;
            };
            this.dimensionDescr = genKeyVal(this.dimensions, descrVals);
        },
        _createCore: function() {
            function update(d) {
                var t = d.dim;
                filter[t].min = Math.max(y[t].domain()[0], y[t].invert(height - d.y - d.dy));
                filter[t].max = Math.min(y[t].domain()[1], y[t].invert(height - d.y));
                active = t;
                change.render();
                return !1;
            }
            function selectAll(d) {
                if (d.dy < 3) {
                    var t = d.dim;
                    filter[t].min = Math.max(y[t].domain()[0], y[t].invert(0));
                    filter[t].max = Math.min(y[t].domain()[1], y[t].invert(height));
                    d.y = botRuleOffs;
                    d.dy = ruleHeight;
                    active = t;
                    change.render();
                }
                return !1;
            }
            var myself = this;
            this.retrieveData();
            var height = this.height, numDigits = this.chart.options.numDigits, topRuleOffs = this.chart.options.topRuleOffset, botRuleOffs = this.chart.options.botRuleOffset, leftRuleOffs = this.chart.options.leftRuleOffset, rightRulePos = this.width - this.chart.options.rightRuleOffset, topRulePos = this.height - topRuleOffs, ruleHeight = topRulePos - botRuleOffs, labelTopOffs = topRuleOffs - 12, dims = this.dimensions, dimDescr = this.dimensionDescr, getDimSc = function(t, addMargin) {
                var theMin = dimDescr[t].min, theMax = dimDescr[t].max, theStep = dimDescr[t].step;
                if (addMargin) {
                    theMin -= theStep;
                    theMax += theStep;
                }
                return pv.Scale.linear(theMin, theMax).range(botRuleOffs, topRulePos);
            }, getDimensionScale = function(t) {
                var scale = getDimSc(t, !0).range(botRuleOffs, topRulePos), dd = dimDescr[t];
                if (dd.orgValue && !dd.categorical) {
                    var func = function(x) {
                        var res = scale(dd.orgValue[x]);
                        return res;
                    };
                    func.domain = function() {
                        return scale.domain();
                    };
                    func.invert = function(d) {
                        return scale.invert(d);
                    };
                    return func;
                }
                return scale;
            }, getDimColorScale = function(t) {
                var scale = getDimSc(t, !1).range("steelblue", "brown");
                return scale;
            }, x = pv.Scale.ordinal(dims).splitFlush(leftRuleOffs, rightRulePos), y = pv.dict(dims, getDimensionScale), colors = pv.dict(dims, getDimColorScale), filter = pv.dict(dims, function(t) {
                return {
                    min: y[t].domain()[0],
                    max: y[t].domain()[1]
                };
            }), active = dims[0], selectVisible = this.chart.options.mapAllDimensions ? function(d) {
                return dims.every(function(t) {
                    var dd = dimDescr[t], val = dd.orgValue && !dd.categorical ? dd.orgValue[d[t]] : d[t];
                    return val >= filter[t].min && val <= filter[t].max;
                });
            } : function(d) {
                return dims.every(function(t) {
                    return d[t] >= filter[t].min && d[t] <= filter[t].max;
                });
            };
            this.pvParCoord = this.pvPanel.add(pv.Panel).data(myself.data).visible(selectVisible).add(pv.Line).data(dims).left(function(t, d) {
                return x(t);
            }).bottom(function(t, d) {
                var res = y[t](d[t]);
                return res;
            }).strokeStyle("#ddd").lineWidth(1).antialias(!1);
            var rule = this.pvPanel.add(pv.Rule).data(dims).left(x).top(topRuleOffs).bottom(botRuleOffs);
            rule.anchor("top").add(pv.Label).top(labelTopOffs).font("bold 10px sans-serif").text(function(d) {
                return dimDescr[d].name;
            });
            var labels = [], labelXoffs = 6, labelYoffs = 3;
            for (var d in dimDescr) if (dimDescr.hasOwnProperty(d)) {
                var dim = dimDescr[d];
                if (dim.categorical) {
                    var xVal = x(dim.id) + labelXoffs;
                    for (var l in dim.map) dim.map.hasOwnProperty(l) && (labels[labels.length] = {
                        x: xVal,
                        y: y[dim.id](dim.map[l]) + labelYoffs,
                        label: l
                    });
                }
            }
            this.pvPanel.add(pv.Panel).data(labels).add(pv.Label).left(function(d) {
                return d.x;
            }).bottom(function(d) {
                return d.y;
            }).text(function(d) {
                return d.label;
            }).textAlign("left");
            var change = this.pvPanel.add(pv.Panel);
            change.add(pv.Panel).data(myself.data).visible(selectVisible).add(pv.Line).data(dims).left(function(t, d) {
                return x(t);
            }).bottom(function(t, d) {
                return y[t](d[t]);
            }).strokeStyle(function(t, d) {
                var dd = dimDescr[active], val = dd.orgValue && !dd.categorical ? dd.orgValue[d[active]] : d[active];
                return colors[active](val);
            }).lineWidth(1);
            var handle = change.add(pv.Panel).data(dims.map(function(dim) {
                return {
                    y: botRuleOffs,
                    dy: ruleHeight,
                    dim: dim
                };
            })).left(function(t) {
                return x(t.dim) - 30;
            }).width(60).fillStyle("rgba(0,0,0,.001)").cursor("crosshair").event("mousedown", pv.Behavior.select()).event("select", update).event("selectend", selectAll).add(pv.Bar).left(25).top(function(d) {
                return d.y;
            }).width(10).height(function(d) {
                return d.dy;
            }).fillStyle(function(t) {
                return t.dim == active ? colors[t.dim]((filter[t.dim].max + filter[t.dim].min) / 2) : "hsla(0,0,50%,.5)";
            }).strokeStyle("white").cursor("move").event("mousedown", pv.Behavior.drag()).event("dragstart", update).event("drag", update);
            handle.anchor("bottom").add(pv.Label).textBaseline("top").text(function(d) {
                return dimDescr[d.dim].categorical ? "" : filter[d.dim].min.toFixed(numDigits) + dimDescr[d.dim].unit;
            });
            handle.anchor("top").add(pv.Label).textBaseline("bottom").text(function(d) {
                return dimDescr[d.dim].categorical ? "" : filter[d.dim].max.toFixed(numDigits) + dimDescr[d.dim].unit;
            });
            this.extend(this.pvParCoord, "parCoord");
            this.extend(this.pvPanel, "chart");
        }
    });
    def.type("pvc.DataTree", pvc.BaseChart).init(function(options) {
        options = options || {};
        options.dimensionGroups = options.dimensionGroups || {};
        options.dimensionGroups.value || (options.dimensionGroups.value = {
            valueType: null
        });
        this.base(options);
    }).add({
        structEngine: null,
        structMetadata: null,
        structDataset: null,
        DataTreePanel: null,
        _getColorRoleSpec: function() {
            return {
                isRequired: !0,
                defaultSourceRole: "category",
                requireIsDiscrete: !0
            };
        },
        setStructData: function(data) {
            this.structDataset = data.resultset;
            this.structDataset.length || this.log("Warning: Structure-dataset is empty");
            this.structMetadata = data.metadata;
            this.structMetadata.length || this.log("Warning: Structure-Metadata is empty");
        },
        _createContent: function(parentPanel, contentOptions) {
            var structEngine = this.structEngine, structType = structEngine ? structEngine.type : new cdo.ComplexType();
            structType.addDimension("value", {});
            var translOptions = {
                seriesInRows: !0,
                crosstabMode: !0
            }, translation = new cdo.CrosstabTranslationOper(structType, this.structDataset, this.structMetadata, translOptions);
            translation.configureType();
            structEngine || (structEngine = this.structEngine = new cdo.Data({
                type: structType
            }));
            structEngine.load(translation.execute(structEngine));
            def.debug >= 3 && this.log(this.structEngine.getInfo());
            this.dataTreePanel = new pvc.DataTreePanel(this, parentPanel, def.create(contentOptions, {
                topRuleOffset: this.options.topRuleOffset,
                botRuleOffset: this.options.botRuleOffset,
                leftRuleOffset: this.options.leftRuleOffset,
                rightRuleOffset: this.options.rightRuleOffset,
                boxplotColor: this.options.boxplotColor,
                valueFontsize: this.options.valueFontsize,
                headerFontsize: this.options.headerFontsize,
                border: this.options.border,
                perpConnector: this.options.perpConnector,
                numDigits: this.options.numDigits,
                minVerticalSpace: this.options.minVerticalSpace,
                connectorSpace: this.options.connectorSpace,
                minAspectRatio: this.options.minAspectRatio
            }));
        },
        defaults: {
            compatVersion: 1,
            topRuleOffset: 30,
            botRuleOffset: 30,
            leftRuleOffset: 60,
            rightRuleOffset: 60,
            boxplotColor: "grey",
            headerFontsize: 16,
            valueFontsize: 20,
            border: 2,
            perpConnector: !1,
            numDigits: 0,
            connectorSpace: .15,
            minVerticalSpace: .05,
            minAspectRatio: 2
        }
    });
    def.type("pvc.DataTreePanel", pvc.PlotPanel).add({
        pvDataTree: null,
        treeElements: null,
        structMap: null,
        structArr: null,
        hRules: null,
        vRules: null,
        rules: null,
        generatePerpConnectors: function(leftLength) {
            this.hRules = [];
            this.vRules = [];
            this.rules = [];
            for (var e in this.structMap) {
                var elem = this.structMap[e];
                if (null != elem.children) {
                    var min = 1e4, max = -1e4, theLeft = elem.left + elem.width;
                    this.hRules.push({
                        left: theLeft,
                        width: leftLength,
                        bottom: elem.bottom + elem.height / 2
                    });
                    theLeft += leftLength;
                    for (var i in elem.children) {
                        var child = this.structMap[elem.children[i]], theBottom = child.bottom + child.height / 2;
                        theBottom > max && (max = theBottom);
                        min > theBottom && (min = theBottom);
                        this.hRules.push({
                            left: theLeft,
                            width: child.left - theLeft,
                            bottom: theBottom
                        });
                    }
                    max > min && this.vRules.push({
                        left: theLeft,
                        bottom: min,
                        height: max - min
                    });
                }
            }
        },
        generateLineSegment: function(x1, y1, x2, y2) {
            var line = [];
            line.push({
                x: x1,
                y: y1
            });
            line.push({
                x: x2,
                y: y2
            });
            this.rules.push(line);
        },
        generateConnectors: function(leftLength) {
            this.hRules = [];
            this.vRules = [];
            if (this.chart.options.perpConnector) this.generatePerpConnectors(leftLength); else {
                this.rules = [];
                for (var e in this.structMap) {
                    var elem = this.structMap[e];
                    if (null != elem.children) {
                        var theCenter, child, i, min = 1e4, max = -1e4;
                        for (i in elem.children) {
                            child = this.structMap[elem.children[i]];
                            theCenter = child.bottom + child.height / 2;
                            theCenter > max && (max = theCenter);
                            min > theCenter && (min = theCenter);
                        }
                        var mid = (max + min) / 2, theLeft1 = elem.left + elem.width, theLeft2 = theLeft1 + leftLength;
                        this.generateLineSegment(theLeft1, elem.bottom + elem.height / 2, theLeft2, mid);
                        for (i in elem.children) {
                            child = this.structMap[elem.children[i]];
                            theCenter = child.bottom + child.height / 2;
                            this.generateLineSegment(theLeft2, mid, child.left, theCenter);
                        }
                    }
                }
            }
        },
        retrieveStructure: function() {
            var data = this.chart.structEngine, options = this.chart.options, colLabels = data.getVisibleCategories();
            this.treeElements = data.getVisibleSeries();
            var e, values = data.getValues(), bottomHeightSpecified = colLabels.length > 4;
            for (e in this.treeElements) this.treeElements[e] = $.trim(this.treeElements[e]);
            var bounds = [];
            bounds.getElement = function(label) {
                null == bounds[label] && (bounds[label] = {
                    min: 1e4,
                    max: -1e4
                });
                return bounds[label];
            };
            bounds.addValue = function(label, value) {
                var bnd = bounds.getElement(label);
                value < bnd.min && (bnd.min = value);
                value > bnd.max && (bnd.max = value);
                return bnd;
            };
            var col, colnr, elem, row;
            for (e in this.treeElements) {
                elem = this.treeElements[e];
                col = elem[0];
                colnr = col.charCodeAt(0);
                row = parseInt(elem.slice(1), 10);
                bounds.addValue("__cols", colnr);
                bounds.addValue(col, row);
            }
            var bnds = bounds.getElement("__cols"), gridWidth = this.innerWidth / (bnds.max - bnds.min + 1), connectorWidth = options.connectorSpace * gridWidth, cellWidth = gridWidth - connectorWidth, maxCellHeight = cellWidth / options.minAspectRatio, colBase = bnds.min;
            delete bounds.__cols;
            for (e in bounds) {
                bnds = bounds[e];
                if ("function" != typeof bnds) {
                    var numRows = bnds.max - bnds.min + 1;
                    bnds.gridHeight = this.innerHeight / numRows;
                    bnds.cellHeight = bnds.gridHeight * (1 - options.minVerticalSpace);
                    bnds.cellHeight > maxCellHeight && (bnds.cellHeight = maxCellHeight);
                    bnds.relBottom = (bnds.gridHeight - bnds.cellHeight) / 2;
                    bnds.numRows = numRows;
                }
            }
            var whitespaceQuote = new RegExp("[\\s\"']+", "g");
            this.structMap = {};
            for (e in this.treeElements) {
                var box = {};
                elem = this.treeElements[e];
                box.box_id = elem;
                this.structMap[elem] = box;
                col = elem[0];
                colnr = col.charCodeAt(0);
                row = parseInt(elem.slice(1), 10);
                bnds = bounds.getElement(col);
                box.colIndex = colnr - colBase;
                box.rowIndex = bnds.numRows - (row - bnds.min) - 1;
                box.left = this.leftOffs + box.colIndex * gridWidth;
                box.width = cellWidth;
                if (bottomHeightSpecified) {
                    box.bottom = values[4][e];
                    box.height = values[5][e];
                } else {
                    box.bottom = this.botOffs + box.rowIndex * bnds.gridHeight + bnds.relBottom;
                    box.height = bnds.cellHeight;
                }
                box.label = values[0][e];
                box.selector = values[1][e];
                box.aggregation = values[2][e];
                var children = (values[3][e] || "").replace(whitespaceQuote, " ");
                box.children = " " === children || "" === children ? null : children.split(" ");
            }
            this.generateConnectors((gridWidth - cellWidth) / 2);
            this.structArr = [];
            for (e in this.structMap) {
                elem = this.structMap[e];
                this.structArr.push(elem);
            }
        },
        findDataValue: function(key, data) {
            for (var i = 0; i < data[0].length; i++) if (data[0][i] == key) return data[1][i];
            this.log("Error: value with key : " + key + " not found.");
        },
        generateBoxPlots: function() {
            var options = this.chart.options;
            for (var e in this.structArr) {
                var elem = this.structArr[e];
                if (elem.values.length) {
                    elem.subplot = {};
                    var sp = elem.subplot, dat = [], margin = 15, rlMargin = elem.width / 6;
                    sp.hRules = [];
                    sp.vRules = [];
                    sp.marks = [];
                    sp.labels = [];
                    dat.push(this.findDataValue("_p5", elem.values));
                    dat.push(this.findDataValue("_p25", elem.values));
                    dat.push(this.findDataValue("_p50", elem.values));
                    dat.push(this.findDataValue("_p75", elem.values));
                    dat.push(this.findDataValue("_p95", elem.values));
                    var noBox = !1;
                    if ("undefined" != typeof dat[2]) {
                        if (dat[4] < dat[0]) {
                            dat = dat.reverse();
                            this.log(" dataset " + elem.box_id + " repaired (_p95 was smaller than _p5)");
                        }
                        if (dat[4] > dat[0]) sp.hScale = pv.Scale.linear(dat[0], dat[4]); else {
                            noBox = !0;
                            sp.hScale = pv.Scale.linear(dat[0] - 1e-10, dat[0] + 1e-10);
                        }
                        sp.hScale.range(elem.left + rlMargin, elem.left + elem.width - rlMargin);
                        var i, avLabel = "" + dat[2];
                        for (i = 0; i < dat.length; i++) dat[i] = sp.hScale(dat[i]);
                        sp.bot = elem.bottom + elem.height / 3;
                        sp.top = elem.bottom + 2 * elem.height / 3;
                        sp.mid = (sp.top + sp.bot) / 2;
                        sp.textBottom = elem.bottom + margin;
                        sp.textBottom = sp.bot - options.valueFontsize - 1;
                        var lwa = 3;
                        if (noBox) sp.vRules.push({
                            left: dat[0],
                            bottom: sp.bot,
                            lWidth: lwa,
                            height: sp.top - sp.bot
                        }); else {
                            sp.hRules.push({
                                left: dat[0],
                                width: dat[1] - dat[0],
                                lWidth: 1,
                                bottom: sp.mid
                            });
                            sp.hRules.push({
                                left: dat[1],
                                width: dat[3] - dat[1],
                                lWidth: 1,
                                bottom: sp.bot
                            });
                            sp.hRules.push({
                                left: dat[1],
                                width: dat[3] - dat[1],
                                lWidth: 1,
                                bottom: sp.top
                            });
                            sp.hRules.push({
                                left: dat[3],
                                width: dat[4] - dat[3],
                                lWidth: 1,
                                bottom: sp.mid
                            });
                            for (i = 0; i < dat.length; i++) sp.vRules.push({
                                left: dat[i],
                                bottom: sp.bot,
                                lWidth: 2 == i ? lwa : 1,
                                height: sp.top - sp.bot
                            });
                        }
                        sp.labels.push({
                            left: dat[2],
                            bottom: sp.textBottom,
                            text: this.labelFixedDigits(avLabel),
                            size: options.smValueFont,
                            color: options.boxplotColor
                        });
                    }
                }
            }
        },
        labelFixedDigits: function(value) {
            "string" == typeof value && (value = parseFloat(value));
            if ("number" == typeof value) {
                var nd = this.chart.options.numDigits;
                value = value.toFixed(nd);
            }
            return "" + value;
        },
        addDataPoint: function(key) {
            var options = this.chart.options;
            for (var e in this.structArr) {
                var elem = this.structArr[e];
                if (elem.values.length) {
                    var value = this.findDataValue(key, elem.values);
                    if ("undefined" != typeof value) {
                        var sp = elem.subplot, theLeft = sp.hScale(value), theColor = "green";
                        sp.marks.push({
                            left: theLeft,
                            bottom: sp.mid,
                            color: theColor
                        });
                        sp.labels.push({
                            left: theLeft,
                            bottom: sp.textBottom,
                            text: this.labelFixedDigits(value),
                            size: options.valueFont,
                            color: theColor
                        });
                    }
                }
            }
        },
        retrieveData: function() {
            var i, data = this.chart.data, options = this.chart.options, selectors = data.getVisibleSeries(), values = data.getValues(), selMap = {}, numCols = values.length;
            for (var e in this.structArr) {
                var elem = this.structArr[e];
                elem.values = [];
                for (i = 0; numCols > i; i++) elem.values.push([]);
                selMap[elem.selector] = elem;
            }
            var boxNotFound = {};
            for (i in selectors) {
                var box = selMap[selectors[i]];
                if ("undefined" != typeof box) for (var j in values) box.values[j].push(values[j][i]); else boxNotFound[selectors[i]] = !0;
            }
            for (var sel in boxNotFound) this.log("Could'nt find box for selector: " + sel);
            this.generateBoxPlots();
            var whitespaceQuote = new RegExp("[\\s\"']+", "g");
            if (options.selectParam) {
                var selPar = options.selectParam.replace(whitespaceQuote, "");
                if ("undefined" != selPar && selPar.length > 0 && "undefined" != typeof window[selPar]) {
                    selPar = window[selPar];
                    this.addDataPoint(selPar);
                }
            }
        },
        _createCore: function() {
            var myself = this, options = this.chart.options;
            options.smValueFontsize = Math.round(.6 * options.valueFontsize);
            options.smValueFont = "" + options.smValueFontsize + "px sans-serif";
            options.valueFont = "" + options.valueFontsize + "px sans-serif";
            var topRuleOffs = options.topRuleOffset, botRuleOffs = options.botRuleOffset, leftRuleOffs = options.leftRuleOffset;
            this.innerWidth = this.width - leftRuleOffs - options.rightRuleOffset;
            this.innerHeight = this.height - topRuleOffs - botRuleOffs;
            this.botOffs = botRuleOffs;
            this.leftOffs = leftRuleOffs;
            this.retrieveStructure();
            this.retrieveData();
            var i, topMargin = options.headerFontsize + 3, rules = this.rules;
            for (i = 0; i < rules.length; i++) this.pvPanel.add(pv.Line).data(rules[i]).left(function(d) {
                return d.x;
            }).bottom(function(d) {
                return d.y;
            }).lineWidth(1).strokeStyle("black");
            this.pvDataTree = this.pvPanel.add(pv.Bar).data(myself.structArr).left(function(d) {
                return d.left;
            }).bottom(function(d) {
                return d.bottom;
            }).height(function(d) {
                return d.height;
            }).width(function(d) {
                return d.width;
            }).fillStyle("green").add(pv.Bar).left(function(d) {
                return d.left + options.border;
            }).bottom(function(d) {
                return d.bottom + options.border;
            }).height(function(d) {
                return d.height - options.border - topMargin;
            }).width(function(d) {
                return d.width - 2 * options.border;
            }).fillStyle("white").add(pv.Label).text(function(d) {
                return d.label;
            }).textAlign("center").left(function(d) {
                return d.left + d.width / 2;
            }).bottom(function(d) {
                return d.bottom + d.height - options.headerFontsize - 5 + options.headerFontsize / 5;
            }).font("" + options.headerFontsize + "px sans-serif").textStyle("white").fillStyle("blue");
            for (i = 0; i < this.structArr.length; i++) {
                var box = this.structArr[i];
                this.pvPanel.add(pv.Rule).data(box.subplot.hRules).left(function(d) {
                    return d.left;
                }).width(function(d) {
                    return d.width;
                }).bottom(function(d) {
                    return d.bottom;
                }).lineWidth(function(d) {
                    return d.lWidth;
                }).strokeStyle(myself.chart.options.boxplotColor);
                this.pvPanel.add(pv.Rule).data(box.subplot.vRules).left(function(d) {
                    return d.left;
                }).height(function(d) {
                    return d.height;
                }).bottom(function(d) {
                    return d.bottom;
                }).lineWidth(function(d) {
                    return d.lWidth;
                }).strokeStyle(myself.chart.options.boxplotColor);
                this.pvPanel.add(pv.Dot).data(box.subplot.marks).left(function(d) {
                    return d.left;
                }).bottom(function(d) {
                    return d.bottom;
                }).fillStyle(function(d) {
                    return d.color;
                });
                this.pvPanel.add(pv.Label).data(box.subplot.labels).left(function(d) {
                    return d.left;
                }).bottom(function(d) {
                    return d.bottom;
                }).font(function(d) {
                    return d.size;
                }).text(function(d) {
                    return d.text;
                }).textAlign("center").textStyle(function(d) {
                    return d.color;
                });
            }
            if (options.perpConnector) {
                this.pvPanel.add(pv.Rule).data(myself.vRules).left(function(d) {
                    return d.left;
                }).bottom(function(d) {
                    return d.bottom;
                }).height(function(d) {
                    return d.height;
                }).strokeStyle("black");
                this.pvPanel.add(pv.Rule).data(myself.hRules).left(function(d) {
                    return d.left;
                }).bottom(function(d) {
                    return d.bottom;
                }).width(function(d) {
                    return d.width;
                }).strokeStyle("black");
            }
        },
        applyExtensions: function() {
            this.extend(this.pvDataTree, "dataTree");
        }
    });
    return pvc;
}(def, pv, cdo);