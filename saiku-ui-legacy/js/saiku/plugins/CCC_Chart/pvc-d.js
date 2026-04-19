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

/*!!*/

var pvc = function(def, pv) {
    function pvc_syncLog() {
        if (pvc.debug > 0 && "undefined" != typeof console) [ "log", "info", [ "trace", "debug" ], "error", "warn", [ "group", "groupCollapsed" ], "groupEnd" ].forEach(function(ps) {
            ps = ps instanceof Array ? ps : [ ps, ps ];
            pvc_installLog(pvc, ps[0], ps[1], "[pvChart]");
        }); else {
            pvc.debug > 1 && (pvc.debug = 1);
            [ "log", "info", "trace", "warn", "group", "groupEnd" ].forEach(function(p) {
                pvc[p] = def.noop;
            });
            var _errorPrefix = "[pvChart ERROR]: ";
            pvc.error = function(e) {
                e && "object" == typeof e && e.message && (e = e.message);
                e = "" + def.nullyTo(e, "");
                e.indexOf(_errorPrefix) < 0 && (e = _errorPrefix + e);
                throw new Error(e);
            };
        }
        pvc.logError = pvc.error;
        pv.error = pvc.error;
    }
    function pvc_syncTipsyLog() {
        var tip = pv.Behavior.tipsy;
        if (tip && tip.setDebug) {
            tip.setDebug(pvc.debug);
            tip.log = pvc.log;
        }
    }
    function pvc_installLog(o, pto, pfrom, prompt) {
        pfrom || (pfrom = pto);
        var fun, c = console, m = c[pfrom] || c.log;
        if (m) {
            var mask = prompt + ": %s";
            if (def.fun.is(m)) fun = m.bind(c, mask); else {
                var apply = Function.prototype.apply;
                fun = function() {
                    apply.call(m, c, def.array.append([ mask ], arguments));
                };
            }
        }
        o[pto] = fun;
    }
    function pvc_unwrapExtensionOne(id, prefix) {
        return id ? def.object.is(id) ? id.abs : prefix ? prefix + def.firstUpperCase(id) : id : prefix;
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
            for (var name in opts) {
                var info = def.hasOwnProp.call(_infos, name) && _infos[name];
                if (info) {
                    var value = opts[name];
                    void 0 !== value && info.set(value, isDefault);
                }
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
        option.specify = specify;
        option.defaults = defaults;
        return option;
    }
    function options_resolvers(list) {
        return function(optionInfo) {
            for (var i = 0, L = list.length; L > i; i++) {
                var m = list[i];
                "string" == typeof m && (m = this[m]);
                if (m.call(this, optionInfo) === !0) return !0;
            }
        };
    }
    function options_constantResolver(value) {
        return function(optionInfo) {
            optionInfo.specify(value);
            return !0;
        };
    }
    function options_specifyResolver(fun) {
        return function(optionInfo) {
            var value = fun.call(this, optionInfo);
            if (void 0 !== value) {
                optionInfo.specify(value);
                return !0;
            }
        };
    }
    function options_defaultResolver(fun) {
        return function(optionInfo) {
            var value = fun.call(this, optionInfo);
            if (void 0 !== value) {
                optionInfo.defaultValue(value);
                return !0;
            }
        };
    }
    function data_disposeChildList(list, parentProp) {
        var L = list && list.length;
        if (L) {
            for (var i = 0; L > i; i++) {
                var child = list[i];
                parentProp && (child[parentProp] = null);
                child.dispose();
            }
            list.length = 0;
        }
    }
    function data_addColChild(parent, childrenProp, child, parentProp, index) {
        child[parentProp] = parent;
        var col = parent[childrenProp] || (parent[childrenProp] = []);
        null == index || index >= col.length ? col.push(child) : col.splice(index, 0, child);
    }
    function data_removeColChild(parent, childrenProp, child, parentProp) {
        var children = parent[childrenProp];
        if (children) {
            var index = children.indexOf(child);
            index >= 0 && def.array.removeAt(children, index);
        }
        child[parentProp] = null;
    }
    function dimType_addVisualRole(visualRole) {
        this.playedVisualRoles.set(visualRole.name, visualRole);
        compType_dimensionRolesChanged.call(this.complexType, this);
    }
    function dimType_removeVisualRole(visualRole) {
        this.playedVisualRoles.rem(visualRole.name);
        compType_dimensionRolesChanged.call(this.complexType, this);
    }
    function compType_dimensionRolesChanged() {
        this._isPctRoleDimTypeMap = null;
    }
    function relTransl_dataPartGet(plot2DataSeriesIndexes, seriesReader) {
        function calcAxis2SeriesKeySet() {
            var atoms = {}, seriesKeys = def.query(me.source).select(function(item) {
                seriesReader(item, atoms);
                var value = atoms.series;
                null != value && null != value.v && (value = value.v);
                return value || null;
            }).distinct().array();
            return me._createPlot2SeriesKeySet(plot2DataSeriesIndexes, seriesKeys);
        }
        var me = this;
        return this._dataPartGet(calcAxis2SeriesKeySet, seriesReader);
    }
    function atom_idComparer(a, b) {
        return a.id - b.id;
    }
    function atom_idComparerReverse(a, b) {
        return b.id - a.id;
    }
    function datum_deselect() {
        delete this.isSelected;
    }
    function datum_isNullOrSelected(d) {
        return d.isNull || d.isSelected;
    }
    function datum_isSelectedT(d) {
        return d.isSelected === !0;
    }
    function datum_isSelectedF(d) {
        return d.isSelected === !1;
    }
    function datum_isVisibleT(d) {
        return d.isVisible === !0;
    }
    function datum_isVisibleF(d) {
        return d.isVisible === !1;
    }
    function datum_isNullT(d) {
        return d.isNull === !0;
    }
    function datum_isNullF(d) {
        return d.isNull === !1;
    }
    function dim_createAtom(type, sourceValue, key, value, label, isVirtual) {
        var atom;
        if (this.owner === this) {
            if (null == label) {
                var formatter = type._formatter;
                label = formatter ? formatter(value, sourceValue) : value;
            }
            label = "" + label;
            !label && pvc.debug >= 2 && pvc.log("Only the null value should have an empty label.");
            atom = new pvc.data.Atom(this, value, label, sourceValue, key);
            isVirtual && (atom.isVirtual = !0);
        } else {
            var source = this.parent || this.linkParent;
            atom = source._atomsByKey[key] || dim_createAtom.call(source, type, sourceValue, key, value, label, isVirtual);
        }
        def.array.insert(this._atoms, atom, this._atomComparer);
        dim_clearVisiblesCache.call(this);
        this._atomsByKey[key] = atom;
        return atom;
    }
    function dim_internAtom(atom) {
        var key = atom.key, me = this;
        if (atom.dimension === me) {
            me.owner === me || def.assert("Should be an owner dimension");
            key || atom !== me._virtualNullAtom || (atom = me.intern(null));
            return atom;
        }
        var hasInited = !me._lazyInit;
        if (hasInited) {
            var localAtom = me._atomsByKey[key];
            if (localAtom) {
                if (localAtom !== atom) throw def.error.operationInvalid("Atom is from a different root data.");
                return atom;
            }
            if (me.owner === me) throw def.error.operationInvalid("Atom is from a different root data.");
        }
        dim_internAtom.call(me.parent || me.linkParent, atom);
        if (hasInited) {
            me._atomsByKey[key] = atom;
            if (key) def.array.insert(me._atoms, atom, me._atomComparer); else {
                me._nullAtom = atom;
                me._atoms.unshift(atom);
            }
            dim_clearVisiblesCache.call(me);
        }
        return atom;
    }
    function dim_buildDatumsFilterKey(keyArgs) {
        var visible = def.get(keyArgs, "visible"), selected = def.get(keyArgs, "selected");
        return (null == visible ? null : !!visible) + ":" + (null == selected ? null : !!selected);
    }
    function dim_createNullAtom(sourceValue) {
        var nullAtom = this._nullAtom;
        if (!nullAtom) {
            if (this.owner === this) {
                var typeFormatter = this.type._formatter, label = "" + (typeFormatter ? typeFormatter.call(null, null, sourceValue) : "");
                nullAtom = new pvc.data.Atom(this, null, label, null, "");
                this.data._atomsBase[this.name] = nullAtom;
            } else nullAtom = dim_createNullAtom.call(this.parent || this.linkParent, sourceValue);
            this._atomsByKey[""] = this._nullAtom = nullAtom;
            this._atoms.unshift(nullAtom);
        }
        return nullAtom;
    }
    function dim_createVirtualNullAtom() {
        this.owner === this || def.assert("Can only create atoms on an owner dimension.");
        if (!this._virtualNullAtom) {
            this._virtualNullAtom = new pvc.data.Atom(this, null, "", null, "");
            this.data._atomsBase[this.name] = this._virtualNullAtom;
        }
        return this._virtualNullAtom;
    }
    function dim_uninternUnvisitedAtoms() {
        this.owner === this || def.assert("Can only unintern atoms of an owner dimension.");
        var atoms = this._atoms;
        if (atoms) {
            for (var atomsByKey = this._atomsByKey, i = 0, L = atoms.length; L > i; ) {
                var atom = atoms[i];
                if (atom.visited) {
                    delete atom.visited;
                    i++;
                } else if (atom !== this._virtualNullAtom) {
                    atoms.splice(i, 1);
                    L--;
                    var key = atom.key;
                    delete atomsByKey[key];
                    if (!key) {
                        delete this._nullAtom;
                        this.data._atomsBase[this.name] = this._virtualNullAtom;
                    }
                }
            }
            dim_clearVisiblesCache.call(this);
        }
    }
    function dim_uninternVirtualAtoms() {
        var atoms = this._atoms;
        if (atoms) {
            for (var removed, atomsByKey = this._atomsByKey, i = 0, L = atoms.length; L > i; ) {
                var atom = atoms[i];
                if (atom.isVirtual) {
                    atoms.splice(i, 1);
                    L--;
                    removed = !0;
                    var key = atom.key || def.assert("Cannot be the null or virtual null atom.");
                    delete atomsByKey[key];
                } else i++;
            }
            removed && dim_clearVisiblesCache.call(this);
        }
    }
    function dim_clearVisiblesCache() {
        this._atomVisibleDatumsCount = this._sumCache = this._visibleAtoms = this._visibleIndexes = null;
    }
    function dim_addChild(child) {
        data_addColChild(this, "childNodes", child, "parent");
        child.owner = this.owner;
    }
    function dim_addLinkChild(linkChild) {
        data_addColChild(this, "_linkChildren", linkChild, "linkParent");
        linkChild.owner = this.owner;
    }
    function dim_onDatumVisibleChanged(datum, visible) {
        var map;
        if (!this._disposed && (map = this._atomVisibleDatumsCount)) {
            var atom = datum.atoms[this.name], key = atom.key;
            def.hasOwn(this._atomsByKey, key) || def.assert("Atom must exist in this dimension.");
            var count = map[key];
            visible || count > 0 || def.assert("Must have had accounted for at least one visible datum.");
            map[key] = (count || 0) + (visible ? 1 : -1);
            this._visibleAtoms = this._sumCache = this._visibleIndexes = null;
        }
    }
    function dim_getVisibleDatumsCountMap() {
        var map = this._atomVisibleDatumsCount;
        if (!map) {
            map = {};
            this.data.datums(null, {
                visible: !0
            }).each(function(datum) {
                var atom = datum.atoms[this.name], key = atom.key;
                map[key] = (map[key] || 0) + 1;
            }, this);
            this._atomVisibleDatumsCount = map;
        }
        return map;
    }
    function dim_calcVisibleIndexes(visible) {
        var indexes = [];
        this._atoms.forEach(function(atom, index) {
            this.isVisible(atom) === visible && indexes.push(index);
        }, this);
        return indexes;
    }
    function dim_calcVisibleAtoms(visible) {
        return def.query(this._atoms).where(function(atom) {
            return this.isVisible(atom) === visible;
        }, this).array();
    }
    function data_addChild(child, index) {
        this.insertAt(child, index);
        def.lazy(this, "_childrenByKey")[child.key] = child;
    }
    function data_addLinkChild(linkChild, index) {
        data_addColChild(this, "_linkChildren", linkChild, "linkParent", index);
    }
    function data_removeLinkChild(linkChild) {
        data_removeColChild(this, "_linkChildren", linkChild, "linkParent");
    }
    function data_disposeChildLists() {
        data_disposeChildList(this.childNodes, "parent");
        this._childrenByKey = null;
        data_disposeChildList(this._linkChildren, "linkParent");
        this._groupByCache = null;
        this._sumAbsCache = null;
    }
    function data_assertIsOwner() {
        this.isOwner() || def.fail("Can only be called on the owner data.");
    }
    function data_onDatumSelectedChanged(datum, selected) {
        !datum.isNull || def.assert("Null datums do not notify selected changes");
        selected ? this._selectedNotNullDatums.set(datum.id, datum) : this._selectedNotNullDatums.rem(datum.id);
        this._sumAbsCache = null;
    }
    function data_onDatumVisibleChanged(datum, visible) {
        var did = datum.id, me = this, hasOwn = def.hasOwnProp;
        if (hasOwn.call(me._datumsById, did)) {
            !datum.isNull || def.assert("Null datums do not notify visible changes");
            visible ? me._visibleNotNullDatums.set(did, datum) : me._visibleNotNullDatums.rem(did);
            me._sumAbsCache = null;
            for (var list = me._dimensionsList, i = 0, L = list.length; L > i; ) dim_onDatumVisibleChanged.call(list[i++], datum, visible);
            list = me.childNodes;
            i = 0;
            L = list.length;
            for (;L > i; ) data_onDatumVisibleChanged.call(list[i++], datum, visible);
            list = me._linkChildren;
            if (list && (L = list.length)) {
                i = 0;
                for (;L > i; ) data_onDatumVisibleChanged.call(list[i++], datum, visible);
            }
        }
    }
    function groupSpec_parseGroupingLevel(groupLevelText, type) {
        def.string.is(groupLevelText) || def.fail.argumentInvalid("groupLevelText", "Invalid grouping specification.");
        return def.query(groupLevelText.split(/\s*\|\s*/)).where(def.truthy).select(function(dimSpecText) {
            var match = groupSpec_matchDimSpec.exec(dimSpecText) || def.fail.argumentInvalid("groupLevelText", "Invalid grouping level syntax '{0}'.", [ dimSpecText ]), name = match[1], order = (match[2] || "").toLowerCase(), reverse = "desc" === order;
            return new pvc.data.GroupingDimensionSpec(name, reverse, type);
        });
    }
    function data_setDatums(addDatums, keyArgs) {
        function maybeAddDatum(newDatum) {
            if (newDatum) {
                var key = newDatum.key;
                if (!def.hasOwnProp.call(datumsByKey, key)) {
                    !isAdditive && oldDatumsByKey && def.hasOwnProp.call(oldDatumsByKey, key) && (newDatum = oldDatumsByKey[key]);
                    var id = newDatum.id;
                    datums.push(newDatum);
                    datumsByKey[key] = newDatum;
                    datumsById[id] = newDatum;
                    newDatums && newDatums.push(newDatum);
                    data_processDatumAtoms.call(this, newDatum, internNewAtoms, doAtomGC);
                    if (!newDatum.isNull) {
                        selDatums && newDatum.isSelected && selDatums.set(id, newDatum);
                        newDatum.isVisible && visDatums.set(id, newDatum);
                    }
                }
            }
        }
        addDatums || def.fail.argumentRequired("addDatums");
        var i, L, oldDatumsByKey, oldDatumsById, doAtomGC = def.get(keyArgs, "doAtomGC", !1), isAdditive = def.get(keyArgs, "isAdditive", !1), internNewAtoms = !!this._dimensions, visDatums = this._visibleNotNullDatums, selDatums = this._selectedNotNullDatums, oldDatums = this._datums;
        if (oldDatums) {
            oldDatumsByKey = this._datumsByKey;
            oldDatumsById = this._datumsById;
            isAdditive && doAtomGC && oldDatums.forEach(function(oldDatum) {
                data_processDatumAtoms.call(this, oldDatum, !1, !0);
            }, this);
        } else isAdditive = !1;
        var newDatums, datums, datumsByKey, datumsById;
        if (isAdditive) {
            newDatums = [];
            datums = oldDatums;
            datumsById = oldDatumsById;
            datumsByKey = oldDatumsByKey;
            this._sumAbsCache = null;
        } else {
            this._datums = datums = [];
            this._datumsById = datumsById = {};
            this._datumsByKey = datumsByKey = {};
            if (oldDatums) {
                data_disposeChildLists.call(this);
                visDatums.clear();
                selDatums && selDatums.clear();
            }
        }
        if (def.array.is(addDatums)) {
            i = 0;
            L = addDatums.length;
            for (;L > i; ) maybeAddDatum.call(this, addDatums[i++]);
        } else {
            if (!(addDatums instanceof def.Query)) throw def.error.argumentInvalid("addDatums", "Argument is of invalid type.");
            addDatums.each(maybeAddDatum, this);
        }
        if (doAtomGC) {
            var dims = this._dimensionsList;
            i = 0;
            L = dims.length;
            for (;L > i; ) dim_uninternUnvisitedAtoms.call(dims[i++]);
        }
        if (isAdditive) {
            var linkChildren = this._linkChildren;
            if (linkChildren) {
                i = 0;
                L = linkChildren.length;
                for (;L > i; ) data_addDatumsSimple.call(linkChildren[i++], newDatums);
            }
        }
    }
    function data_processDatumAtoms(datum, intern, markVisited) {
        var dims = this._dimensionsList;
        dims || (intern = !1);
        if (intern || markVisited) {
            var L, atom, dim, datoms = datum.atoms, i = 0;
            if (dims) {
                L = dims.length;
                for (;L > i; ) {
                    dim = dims[i++];
                    atom = datoms[dim.name];
                    if (atom) {
                        intern && dim_internAtom.call(dim, atom);
                        markVisited && (atom.visited = !0);
                    }
                }
            } else {
                var dimNames = this.type.dimensionsNames();
                L = dimNames.length;
                for (;L > i; ) {
                    atom = datoms[dimNames[i++]];
                    atom && (atom.visited = !0);
                }
            }
        }
    }
    function data_addDatumsSimple(newDatums) {
        newDatums || def.fail.argumentRequired("newDatums");
        var groupOper = this._groupOper;
        if (groupOper) newDatums = groupOper.executeAdd(this, newDatums); else {
            var wherePred = this._wherePred;
            wherePred && (newDatums = newDatums.filter(wherePred));
            data_addDatumsLocal.call(this, newDatums);
        }
        var list = this._linkChildren, L = list && list.length;
        if (L) for (var i = 0; L > i; i++) data_addDatumsSimple.call(list[i], newDatums);
    }
    function data_addDatumsLocal(newDatums) {
        var me = this;
        me._sumAbsCache = null;
        for (var ds = me._datums, vds = me._visibleNotNullDatums, sds = me._selectedNotNullDatums, dsById = me._datumsById, i = 0, L = newDatums.length; L > i; i++) {
            var newDatum = newDatums[i], id = newDatum.id;
            dsById[id] = newDatum;
            data_processDatumAtoms.call(me, newDatum, !0, !1);
            if (!newDatum.isNull) {
                sds && newDatum.isSelected && sds.set(id, newDatum);
                newDatum.isVisible && vds.set(id, newDatum);
            }
            ds.push(newDatum);
        }
    }
    function data_processWhereSpec(whereSpec) {
        function processDatumFilter(datumFilter) {
            if (null != datumFilter) {
                "object" == typeof datumFilter || def.fail.invalidArgument("datumFilter");
                var datumProcFilter = {}, any = !1;
                for (var dimName in datumFilter) {
                    var atoms = this.dimensions(dimName).getDistinctAtoms(def.array.as(datumFilter[dimName]));
                    if (atoms.length) {
                        any = !0;
                        datumProcFilter[dimName] = atoms;
                    }
                }
                any && whereProcSpec.push(datumProcFilter);
            }
        }
        var whereProcSpec = [];
        whereSpec = def.array.as(whereSpec);
        whereSpec && whereSpec.forEach(processDatumFilter, this);
        return whereProcSpec;
    }
    function data_whereState(q, keyArgs) {
        var visible = def.get(keyArgs, "visible"), isNull = def.get(keyArgs, "isNull"), selected = def.get(keyArgs, "selected"), where = def.get(keyArgs, "where");
        null != visible && (q = q.where(visible ? datum_isVisibleT : datum_isVisibleF));
        null != isNull && (q = q.where(isNull ? datum_isNullT : datum_isNullF));
        null != selected && (q = q.where(selected ? datum_isSelectedT : datum_isSelectedF));
        where && (q = q.where(where));
        return q;
    }
    function data_wherePredicate(whereSpec, keyArgs) {
        var visible = def.get(keyArgs, "visible"), isNull = def.get(keyArgs, "isNull"), selected = def.get(keyArgs, "selected"), where = def.get(keyArgs, "where"), ps = [];
        null != visible && ps.unshift(visible ? datum_isVisibleT : datum_isVisibleF);
        null != isNull && ps.unshift(isNull ? datum_isNullT : datum_isNullF);
        null != selected && ps.unshift(selected ? datum_isSelectedT : datum_isSelectedF);
        where && ps.unshift(where);
        whereSpec && ps.unshift(data_whereSpecPredicate(whereSpec));
        var P = ps.length;
        if (P) {
            if (1 === P) return ps[0];
            var wherePredicate = function(d) {
                for (var i = P; i; ) if (!ps[--i](d)) return !1;
                return !0;
            };
            return wherePredicate;
        }
    }
    function data_whereSpecPredicate(whereSpec) {
        function datumWhereSpecPredicate(d) {
            for (var datoms = d.atoms, i = 0; L > i; i++) if (datumFilterPredicate(datoms, whereSpec[i])) return !0;
            return !1;
        }
        function datumFilterPredicate(datoms, datumFilter) {
            for (var dimName in datumFilter) if (datumFilter[dimName].indexOf(datoms[dimName]) < 0) return !1;
            return !0;
        }
        var L = whereSpec.length;
        return datumWhereSpecPredicate;
    }
    function data_where(whereSpec, keyArgs) {
        var orderBys = def.array.as(def.get(keyArgs, "orderBy")), datumKeyArgs = def.create(keyArgs || {}, {
            orderBy: null
        }), query = def.query(whereSpec).selectMany(function(datumFilter, index) {
            orderBys && (datumKeyArgs.orderBy = orderBys[index]);
            return data_whereDatumFilter.call(this, datumFilter, datumKeyArgs);
        }, this);
        return query.distinct(def.propGet("id"));
    }
    function data_whereDatumFilter(datumFilter, keyArgs) {
        var groupingSpecText = keyArgs.orderBy;
        if (groupingSpecText) {
            if (groupingSpecText.indexOf("|") >= 0) throw def.error.argumentInvalid("keyArgs.orderBy", "Multi-dimension order by is not supported.");
        } else groupingSpecText = Object.keys(datumFilter).sort().join(",");
        var rootData = this.groupBy(groupingSpecText, keyArgs), H = rootData.treeHeight, stateStack = [];
        return def.query(function() {
            var state;
            if (this._data) {
                if (this._datumsQuery) {
                    this._data || def.assert("Must have a current data");
                    stateStack.length || def.assert("Must have a parent data");
                    !this._dimAtomsOrQuery || def.assert();
                    if (this._datumsQuery.next()) {
                        this.item = this._datumsQuery.item;
                        return 1;
                    }
                    this._datumsQuery = null;
                    state = stateStack.pop();
                    this._data = state.data;
                    this._dimAtomsOrQuery = state.dimAtomsOrQuery;
                }
            } else {
                this._data = rootData;
                this._dimAtomsOrQuery = def.query(datumFilter[rootData._groupLevelSpec.dimensions[0].name]);
            }
            this._dimAtomsOrQuery || def.assert("Invalid programmer");
            this._data || def.assert("Must have a current data");
            for (var depth = stateStack.length; ;) {
                for (;this._dimAtomsOrQuery.next(); ) {
                    var dimAtomOr = this._dimAtomsOrQuery.item, childData = this._data.child(dimAtomOr.key);
                    if (childData && (H - 1 > depth || childData._datums.length)) {
                        stateStack.push({
                            data: this._data,
                            dimAtomsOrQuery: this._dimAtomsOrQuery
                        });
                        this._data = childData;
                        if (!(H - 1 > depth)) {
                            this._dimAtomsOrQuery = null;
                            this._datumsQuery = def.query(childData._datums);
                            this._datumsQuery.next();
                            this.item = this._datumsQuery.item;
                            return 1;
                        }
                        this._dimAtomsOrQuery = def.query(datumFilter[childData._groupLevelSpec.dimensions[0].name]);
                        depth++;
                    }
                }
                if (!depth) return 0;
                state = stateStack.pop();
                this._data = state.data;
                this._dimAtomsOrQuery = state.dimAtomsOrQuery;
                depth--;
            }
            return 0;
        });
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
    function sign_createBasic(pvMark) {
        var as = mark_getAncestorSign(pvMark) || def.assert("There must exist an ancestor sign");
        return new pvc.visual.BasicSign(as.panel, pvMark);
    }
    function mark_getAncestorSign(pvMark) {
        var sign;
        do pvMark = pvMark.parent; while (!(!pvMark || (sign = pvMark.sign) || pvMark.proto && (sign = pvMark.proto.sign)));
        return sign;
    }
    function axis_groupingScaleType(grouping) {
        return grouping.isDiscrete() ? "discrete" : grouping.firstDimensionValueType() === Date ? "timeSeries" : "numeric";
    }
    function pvc_castDomainScope(scope, axis) {
        return pvc.parseDomainScope(scope, axis.orientation);
    }
    function pvc_castAxisPosition(side) {
        if (side) {
            if (def.hasOwn(pvc_Sides.namesSet, side)) {
                var mapAlign = pvc.BasePanel["y" === this.orientation ? "horizontalAlign" : "verticalAlign2"];
                return mapAlign[side];
            }
            pvc.debug >= 2 && pvc.log(def.format("Invalid axis position value '{0}'.", [ side ]));
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
    function colorAxis_castColorMap(colorMap) {
        var resultMap;
        if (colorMap) {
            var any;
            def.eachOwn(colorMap, function(v, k) {
                any = !0;
                colorMap[k] = pv.color(v);
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
                return me.chart._getRoleColorScale(me.role.name);
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
    function dataCell_dataPartValue(dc) {
        return dc.dataPartValue;
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
                this._specifyChartOption(optionInfo, "show" + type);
                return !0;
            }
        };
    }
    Array.prototype.every || (Array.prototype.every = function(fun) {
        if (null == this) throw new TypeError();
        var t = Object(this), len = t.length >>> 0;
        if ("function" != typeof fun) throw new TypeError();
        for (var thisArg = arguments.length >= 2 ? arguments[1] : void 0, i = 0; len > i; i++) if (i in t && !fun.call(thisArg, t[i], i, t)) return !1;
        return !0;
    });
    var pvc = def.globalSpace("pvc", {
        debug: 0
    });
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
                pvc.debug = m ? +m[1] : 3;
            }
        }
    }();
    var pv_Mark = pv.Mark;
    pvc.invisibleFill = "rgba(127,127,127,0.00001)";
    pvc.logSeparator = "------------------------------------------";
    var pvc_arraySlice = Array.prototype.slice;
    pvc.setDebug = function(level) {
        level = +level;
        pvc.debug = isNaN(level) ? 0 : level;
        pvc_syncLog();
        pvc_syncTipsyLog();
        return pvc.debug;
    };
    pvc.setDebug(pvc.debug);
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
    pvc.stringify = function(t, keyArgs) {
        var maxLevel = def.get(keyArgs, "maxLevel") || 5, out = [];
        pvc.stringifyRecursive(out, t, maxLevel, keyArgs);
        return out.join("");
    };
    pvc.stringifyRecursive = function(out, t, remLevels, keyArgs) {
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
                if (def.fun.is(t.stringify)) return t.stringify(out, remLevels, keyArgs);
                if (t instanceof Array) {
                    out.push("[");
                    t.forEach(function(item, index) {
                        index && out.push(", ");
                        pvc.stringifyRecursive(out, item, remLevels, keyArgs) || out.pop();
                    });
                    out.push("]");
                } else {
                    var ownOnly = def.get(keyArgs, "ownOnly", !0);
                    if (t === def.global) {
                        out.push("<window>");
                        return !0;
                    }
                    if (def.fun.is(t.cloneNode)) {
                        out.push("<dom #" + (t.id || t.name || "?") + ">");
                        return !0;
                    }
                    if (remLevels > 1 && t.constructor !== Object) {
                        remLevels = 1;
                        ownOnly = !0;
                    }
                    out.push("{");
                    var first = !0;
                    for (var p in t) if (!ownOnly || def.hasOwnProp.call(t, p)) {
                        first || out.push(", ");
                        out.push(p + ": ");
                        if (pvc.stringifyRecursive(out, t[p], remLevels, keyArgs)) first && (first = !1); else {
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
                if (def.get(keyArgs, "funs", !1)) {
                    out.push(JSON.stringify(t.toString().substr(0, 13) + "..."));
                    return !0;
                }
                return !1;
            }
            out.push("'new ???'");
            return !0;
        }
    };
    pvc.orientation = {
        vertical: "vertical",
        horizontal: "horizontal"
    };
    pvc.extensionTag = "extension";
    pvc.extendType = function(type, exts, names) {
        if (exts) {
            var exts2, sceneVars = type.prototype._vars, addExtension = function(ext, n) {
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
    pv.Color.prototype.stringify = function(out, remLevels, keyArgs) {
        return pvc.stringifyRecursive(out, this.key, remLevels, keyArgs);
    };
    pv_Mark.prototype.hasDelegateValue = function(name, tag) {
        var p = this.$propertiesMap[name];
        return p ? !tag || p.tag === tag : this.proto ? this.proto.hasDelegateValue(name, tag) : !1;
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
    }, pvc.createColorScheme = function(colors) {
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
    pvc.removeTipsyLegends = function() {
        try {
            $(".tipsy").remove();
        } catch (e) {}
    };
    pvc.createDateComparer = function(parser, key) {
        key || (key = pv.identity);
        return function(a, b) {
            return parser.parse(key(a)) - parser.parse(key(b));
        };
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
                    t = new Date(difDays >= 4 ? t.getTime() + sign * (7 - difDays) * D : t.getTime() - sign * difDays * D);
                }
                return t;
            }
        }
    };
    pv.Format.createParser = function(pvFormat) {
        function parse(value) {
            return value instanceof Date ? value : def.number.is(value) ? new Date(value) : pvFormat.parse(value);
        }
        return parse;
    };
    pv.Format.createFormatter = function(pvFormat) {
        function format(value) {
            return null != value ? pvFormat.format(value) : "";
        }
        return format;
    };
    pvc.buildTitleFromName = function(name) {
        return def.firstUpperCase(name).replace(/([a-z\d])([A-Z])/, "$1 $2");
    };
    pvc.buildIndexedId = function(prefix, index) {
        return index > 0 ? prefix + "" + (index + 1) : prefix;
    };
    pvc.splitIndexedId = function(indexedId) {
        var match = /^(.*?)(\d*)$/.exec(indexedId), index = null;
        if (match[2]) {
            index = Number(match[2]);
            1 >= index ? index = 1 : index--;
        }
        return [ match[1], index ];
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
                k && pvc.debug >= 2 && pvc.warn("Invalid '" + enumName + "' value: '" + k + "'. Assuming '" + dk + "'.");
                k = dk;
            }
            return k;
        };
    };
    pvc.parseDistinctIndexArray = function(value, min, max) {
        value = def.array.as(value);
        if (null == value) return null;
        null == min && (min = 0);
        null == max && (max = 1/0);
        var a = def.query(value).select(function(index) {
            return +index;
        }).where(function(index) {
            return !isNaN(index) && index >= min && max >= index;
        }).distinct().array();
        return a.length ? a : null;
    };
    pvc.parseValuesOverflow = pvc.makeEnumParser("valuesOverflow", [ "show", "trim", "hide" ], "hide");
    pvc.parseMultiChartOverflow = pvc.makeEnumParser("multiChartOverflow", [ "grow", "fit", "clip" ], "grow");
    pvc.parseLegendClickMode = pvc.makeEnumParser("legendClickMode", [ "toggleSelected", "toggleVisible", "none" ], "toggleVisible");
    pvc.parseTooltipAutoContent = pvc.makeEnumParser("tooltipAutoContent", [ "summary", "value" ], "value");
    pvc.parseSelectionMode = pvc.makeEnumParser("selectionMode", [ "rubberBand", "focusWindow" ], "rubberBand");
    pvc.parseClearSelectionMode = pvc.makeEnumParser("clearSelectionMode", [ "emptySpaceClick", "manual" ], "emptySpaceClick");
    pvc.parseSunburstSliceOrder = pvc.makeEnumParser("sliceOrder", [ "bySizeAscending", "bySizeDescending", "none" ], "bySizeDescending");
    pvc.parseShape = pvc.makeEnumParser("shape", pv.Scene.hasSymbol, null);
    pvc.parseTreemapColorMode = pvc.makeEnumParser("colorMode", [ "byParent", "bySelf" ], "byParent");
    pvc.parseTreemapLayoutMode = pvc.makeEnumParser("layoutMode", [ "squarify", "slice-and-dice", "slice", "dice" ], "squarify");
    pvc.parseContinuousColorScaleType = function(scaleType) {
        if (scaleType) {
            scaleType = ("" + scaleType).toLowerCase();
            switch (scaleType) {
              case "linear":
              case "normal":
              case "discrete":
                break;

              default:
                pvc.debug >= 2 && pvc.log("[Warning] Invalid 'ScaleType' option value: '" + scaleType + "'.");
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
                    pvc.debug >= 2 && pvc.log("[Warning] Invalid 'DomainScope' option value: '" + scope + "' for the orientation: '" + orientation + "'.");
                }
                break;

              default:
                pvc.debug >= 2 && pvc.log("[Warning] Invalid 'DomainScope' option value: '" + scope + "'.");
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
                pvc.debug >= 2 && pvc.log("[Warning] Invalid 'DomainRoundMode' value: '" + mode + "'.");
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
              case "rotatethenhide":
                break;

              default:
                pvc.debug >= 2 && pvc.log("[Warning] Invalid 'OverlappedLabelsMode' option value: '" + mode + "'.");
                mode = null;
            }
        }
        return mode;
    };
    pvc.castNumber = function(value) {
        if (null != value) {
            value = +value;
            isNaN(value) && (value = null);
        }
        return value;
    };
    pvc.castPositiveNumber = function(value) {
        value = pvc.castNumber(value);
        null == value || value > 0 || (value = null);
        return value;
    };
    pvc.castNonNegativeNumber = function(value) {
        value = pvc.castNumber(value);
        null != value && 0 > value && (value = null);
        return value;
    };
    pvc.parseWaterDirection = function(value) {
        if (value) {
            value = ("" + value).toLowerCase();
            switch (value) {
              case "up":
              case "down":
                return value;
            }
            pvc.debug >= 2 && pvc.log("[Warning] Invalid 'WaterDirection' value: '" + value + "'.");
        }
    };
    pvc.parseTrendType = function(value) {
        if (value) {
            value = ("" + value).toLowerCase();
            if ("none" === value) return value;
            if (pvc.trends.has(value)) return value;
            pvc.debug >= 2 && pvc.log("[Warning] Invalid 'TrendType' value: '" + value + "'.");
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
            pvc.debug >= 2 && pvc.log("[Warning] Invalid 'NullInterpolationMode' value: '" + value + "'.");
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
        isInvalid && pvc.debug >= 2 && pvc.log(def.format("Invalid alignment value '{0}'. Assuming '{1}'.", [ align, align2 ]));
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
            pvc.debug >= 2 && pvc.log(def.format("Invalid anchor value '{0}'.", [ anchor ]));
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
            pvc.debug >= 2 && pvc.log(def.format("Invalid wedge anchor value '{0}'.", [ anchor ]));
        }
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
    var pvc_Sides = pvc.Sides = function(sides) {
        null != sides && this.setSides(sides);
    };
    pvc_Sides.hnames = "left right".split(" ");
    pvc_Sides.vnames = "top bottom".split(" ");
    pvc_Sides.names = "left right top bottom".split(" ");
    pvc_Sides.namesSet = pv.dict(pvc_Sides.names, def.retTrue);
    pvc.parsePosition = function(side, defaultSide) {
        if (side) {
            side = ("" + side).toLowerCase();
            if (!def.hasOwn(pvc_Sides.namesSet, side)) {
                var newSide = defaultSide || "left";
                pvc.debug >= 2 && pvc.log(def.format("Invalid position value '{0}. Assuming '{1}'.", [ side, newSide ]));
                side = newSide;
            }
        }
        return side || defaultSide || "left";
    };
    pvc_Sides.as = function(v) {
        null == v || v instanceof pvc_Sides || (v = new pvc_Sides().setSides(v));
        return v;
    };
    pvc_Sides.to = function(v) {
        null != v && v instanceof pvc_Sides || (v = new pvc_Sides().setSides(v));
        return v;
    };
    pvc_Sides.prototype.stringify = function(out, remLevels, keyArgs) {
        return pvc.stringifyRecursive(out, def.copyOwn(this), remLevels, keyArgs);
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
                if (sides instanceof pvc_PercentValue) this.set("all", sides); else {
                    this.set("all", sides.all);
                    this.set("width", sides.width);
                    this.set("height", sides.height);
                    for (var p in sides) pvc_Sides.namesSet.hasOwnProperty(p) && this.set(p, sides[p]);
                }
                return this;
            }
        }
        pvc.debug && pvc.log("Invalid 'sides' value: " + pvc.stringify(sides));
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
    pvc_Sides.resolvedMax = function(a, b) {
        var sides = {};
        pvc_Sides.names.forEach(function(side) {
            sides[side] = Math.max(a[side] || 0, b[side] || 0);
        });
        return sides;
    };
    pvc_Sides.inflate = function(sides, by) {
        var sidesOut = {};
        pvc_Sides.names.forEach(function(side) {
            sidesOut[side] = (sides[side] || 0) + by;
        });
        return pvc_Sides.updateSize(sidesOut);
    };
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
            pvc.debug && pvc.log(def.format("Invalid margins component '{0}'", [ "" + value ]));
        }
    };
    pvc_PercentValue.resolve = function(value, total) {
        return value instanceof pvc_PercentValue ? value.resolve(total) : value;
    };
    var pvc_markZOrder = pv_Mark.prototype.zOrder;
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
                processShape(shape, scenes[index], index);
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
                processShape(shape, scenes[index], index);
            });
        }
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
    var pvc_Size = def.type("pvc.Size").init(function(width, height) {
        if (1 === arguments.length) null != width && this.setSize(width); else {
            null != width && (this.width = width);
            null != height && (this.height = height);
        }
    }).add({
        stringify: function(out, remLevels, keyArgs) {
            return pvc.stringifyRecursive(out, def.copyOwn(this), remLevels, keyArgs);
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
            pvc.debug && pvc.log("Invalid 'size' value: " + pvc.stringify(size));
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
    var pvc_Offset = def.type("pvc.Offset").init(function(x, y) {
        if (1 === arguments.length) null != x && this.setOffset(x); else {
            null != x && (this.x = x);
            null != y && (this.y = y);
        }
    }).add({
        stringify: function(out, remLevels, keyArgs) {
            return pvc.stringifyRecursive(out, def.copyOwn(this), remLevels, keyArgs);
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
            pvc.debug && pvc.log("Invalid 'offset' value: " + pvc.stringify(offset));
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
    pvc_Offset.addStatic({
        names: [ "x", "y" ]
    }).addStatic({
        namesSet: pv.dict(pvc_Offset.names, def.retTrue),
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
    });
    null == $.support.svg && ($.support.svg = document.implementation.hasFeature("http://www.w3.org/TR/SVG11/feature#BasicStructure", "1.1"));
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
            if (len >= textLen) return text;
            if (textLen > 1.5 * len) return pvc.text.trimToWidthBin(len, text, font, trimTerminator, before, clipLen);
            len -= pv.Text.measureWidth(trimTerminator, font);
            for (;textLen > len; ) {
                text = before ? text.slice(1) : text.slice(0, text.length - 1);
                textLen = pv.Text.measureWidth(text, font);
            }
            return clipLen && clipLen >= textLen ? "" : before ? trimTerminator + text : text + trimTerminator;
        },
        trimToWidthBin: function(len, text, font, trimTerminator, before, clipLen) {
            len -= pv.Text.measureWidth(trimTerminator, font);
            for (var mid, textLen, ilen = text.length, high = ilen - 2, low = 0; high >= low && high > 0; ) {
                mid = Math.ceil((low + high) / 2);
                var textMid = before ? text.slice(ilen - mid) : text.slice(0, mid);
                textLen = pv.Text.measureWidth(textMid, font);
                if (textLen > len) high = mid - 1; else {
                    if (!(pv.Text.measureWidth(before ? text.slice(ilen - mid - 1) : text.slice(0, mid + 1), font) < len)) return clipLen && clipLen >= textLen ? "" : before ? trimTerminator + textMid : textMid + trimTerminator;
                    low = mid + 1;
                }
            }
            text = before ? text.slice(ilen - high) : text.slice(0, high);
            textLen = text.length;
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
            pvc.log("Color value dimension should be comparable. Generated color scale may be invalid.");
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
        _createScale: def.method({
            isAbstract: !0
        }),
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
                        pvc.debug >= 2 && pvc.log("Ignoring option 'colorDomain' due to unsupported length." + def.format(" Should have '{0}', but instead has '{1}'.", [ this.desiredDomainCount, domain.length ]));
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
    def.space("pvc.trends", function(trends) {
        var _trends = {};
        def.set(trends, "define", function(type, trendSpec) {
            type || def.fail.argumentRequired("type");
            trendSpec || def.fail.argumentRequired("trendSpec");
            def.object.is(trendSpec) || def.fail.argumentInvalid("trendSpec", "Must be a trend specification object.");
            type = ("" + type).toLowerCase();
            pvc.debug >= 2 && def.hasOwn(_trends, type) && pvc.log(def.format("[WARNING] A trend type with the name '{0}' is already defined.", [ type ]));
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
                    return null != value ? +value : 0/0;
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
                var alpha, beta;
                if (N >= 2) {
                    var avgX = sumX / N, avgY = sumY / N, avgXY = sumXY / N, avgXX = sumXX / N, den = avgXX - avgX * avgX;
                    beta = 0 === den ? 0 : (avgXY - avgX * avgY) / den;
                    alpha = avgY - beta * avgX;
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
                    sample: function(x, y) {
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
                cast && (value = cast.call(this._context, value, this));
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
            var fun = this[name];
            if (fun) {
                var context = this._context;
                context && "string" == typeof fun && (fun = context[fun]);
            }
            return fun;
        }
    });
    def.global.NoDataException = function() {};
    def.global.InvalidDataException = function(msg) {
        this.message = msg ? msg : "Invalid Data.";
    };
    pvc.data = {
        visibleKeyArgs: {
            visible: !0
        }
    };
    def.type("pvc.data.DimensionType").init(function(complexType, name, keyArgs) {
        this.complexType = complexType;
        this.name = name;
        this.label = def.get(keyArgs, "label") || pvc.buildTitleFromName(name);
        var groupAndLevel = pvc.splitIndexedId(name);
        this.group = groupAndLevel[0];
        this.groupLevel = def.nullyTo(groupAndLevel[1], 0);
        this.label.indexOf("{") >= 0 && (this.label = def.format(this.label, [ this.groupLevel + 1 ]));
        this.playedVisualRoles = new def.Map();
        this.isHidden = !!def.get(keyArgs, "isHidden");
        var valueType = def.get(keyArgs, "valueType") || null, valueTypeName = pvc.data.DimensionType.valueTypeName(valueType), cast = def.getOwn(pvc.data.DimensionType.cast, valueTypeName, null);
        this.valueType = valueType;
        this.valueTypeName = valueTypeName;
        this.cast = cast;
        this.isDiscreteValueType = this.valueType !== Number && this.valueType !== Date;
        this.isDiscrete = def.get(keyArgs, "isDiscrete");
        if (null == this.isDiscrete) this.isDiscrete = this.isDiscreteValueType; else {
            this.isDiscrete = !!this.isDiscrete;
            if (!this.isDiscrete && this.isDiscreteValueType) throw def.error.argumentInvalid("isDiscrete", "The only supported continuous value types are Number and Date.");
        }
        this._converter = def.get(keyArgs, "converter") || null;
        if (!this._converter) {
            var rawFormat = def.get(keyArgs, "rawFormat");
            if (rawFormat) switch (this.valueType) {
              case Date:
                this._converter = pv.Format.createParser(pv.Format.date(rawFormat));
            }
        }
        this._key = def.get(keyArgs, "key") || null;
        this._comparer = def.get(keyArgs, "comparer");
        if (void 0 === this._comparer) switch (this.valueType) {
          case Number:
          case Date:
            this._comparer = def.compare;
            break;

          default:
            this._comparer = null;
        }
        this.isComparable = null != this._comparer;
        this._formatter = def.get(keyArgs, "formatter") || null;
        if (!this._formatter) switch (this.valueType) {
          case Number:
            this._formatter = pv.Format.createFormatter(pv.Format.number().fractionDigits(0, 2));
            break;

          case Date:
            var format = def.get(keyArgs, "format");
            if (!format) {
                format = def.get(keyArgs, "rawFormat");
                format && (format = format.replace(/-/g, "/"));
            }
            format || (format = "%Y/%m/%d");
            this._formatter = pv.Format.createFormatter(pv.Format.date(format));
        }
    }).add({
        isCalculated: !1,
        compare: function(a, b) {
            return null == a ? null == b ? 0 : -1 : null == b ? 1 : this._comparer.call(null, a, b);
        },
        comparer: function(reverse) {
            if (!this.isComparable) return null;
            var me = this;
            return reverse ? this._reverseComparer || (this._reverseComparer = function(a, b) {
                return me.compare(b, a);
            }) : this._directComparer || (this._directComparer = function(a, b) {
                return me.compare(a, b);
            });
        },
        atomComparer: function(reverse) {
            return reverse ? this._reverseAtomComparer || (this._reverseAtomComparer = this._createReverseAtomComparer()) : this._directAtomComparer || (this._directAtomComparer = this._createDirectAtomComparer());
        },
        _toDiscrete: function() {
            this.isDiscrete = !0;
        },
        _toCalculated: function() {
            this.isCalculated = !0;
        },
        _createReverseAtomComparer: function() {
            function reverseAtomComparer(a, b) {
                return a === b ? 0 : me.compare(b.value, a.value);
            }
            if (!this.isComparable) return atom_idComparerReverse;
            var me = this;
            return reverseAtomComparer;
        },
        _createDirectAtomComparer: function() {
            function directAtomComparer(a, b) {
                return a === b ? 0 : me.compare(a.value, b.value);
            }
            if (!this.isComparable) return atom_idComparer;
            var me = this;
            return directAtomComparer;
        },
        formatter: function() {
            return this._formatter;
        },
        converter: function() {
            return this._converter;
        },
        playingPercentVisualRole: function() {
            return def.query(this.playedVisualRoles.values()).any(function(visualRole) {
                return visualRole.isPercent;
            });
        }
    });
    pvc.data.DimensionType.cast = {
        Date: function(value) {
            return value instanceof Date ? value : new Date(value);
        },
        Number: function(value) {
            value = Number(value);
            return isNaN(value) ? null : value;
        },
        String: String,
        Boolean: Boolean,
        Object: Object,
        Any: null
    };
    pvc.data.DimensionType.dimensionGroupName = function(dimName) {
        return dimName.replace(/^(.*?)(\d*)$/, "$1");
    };
    pvc.data.DimensionType.valueTypeName = function(valueType) {
        if (null == valueType) return "Any";
        switch (valueType) {
          case Boolean:
            return "Boolean";

          case Number:
            return "Number";

          case String:
            return "String";

          case Object:
            return "Object";

          case Date:
            return "Date";

          default:
            throw def.error.argumentInvalid("valueType", "Invalid valueType function: '{0}'.", [ valueType ]);
        }
    };
    pvc.data.DimensionType.extendSpec = function(dimName, dimSpec, keyArgs) {
        var dimGroup = pvc.data.DimensionType.dimensionGroupName(dimName), userDimGroupsSpec = def.get(keyArgs, "dimensionGroups");
        if (userDimGroupsSpec) {
            var groupDimSpec = userDimGroupsSpec[dimGroup];
            groupDimSpec && (dimSpec = def.create(groupDimSpec, dimSpec));
        }
        dimSpec || (dimSpec = {});
        switch (dimGroup) {
          case "category":
            var isCategoryTimeSeries = def.get(keyArgs, "isCategoryTimeSeries", !1);
            isCategoryTimeSeries && void 0 === dimSpec.valueType && (dimSpec.valueType = Date);
            break;

          case "value":
            void 0 === dimSpec.valueType && (dimSpec.valueType = Number);
            dimSpec.valueType === Number && (void 0 !== dimSpec.formatter || dimSpec.format || (dimSpec.formatter = def.get(keyArgs, "valueNumberFormatter")));
        }
        void 0 !== dimSpec.converter || dimSpec.valueType !== Date || dimSpec.rawFormat || (dimSpec.rawFormat = def.get(keyArgs, "timeSeriesFormat"));
        return dimSpec;
    };
    def.type("pvc.data.ComplexType").init(function(dimTypeSpecs) {
        this._dims = {};
        this._dimsList = [];
        this._dimsNames = [];
        this._calculations = [];
        this._calculatedDimNames = {};
        this._dimsIndexByName = null;
        this._dimsByGroup = {};
        this._dimsNamesByGroup = {};
        if (dimTypeSpecs) for (var name in dimTypeSpecs) this.addDimension(name, dimTypeSpecs[name]);
    }).add({
        describe: function() {
            var out = [ "COMPLEX TYPE INFORMATION", pvc.logSeparator ];
            this._dimsList.forEach(function(type) {
                var features = [];
                features.push(type.valueTypeName);
                type.isComparable && features.push("comparable");
                type.isDiscrete || features.push("continuous");
                type.isHidden && features.push("hidden");
                out.push("  " + type.name + " (" + features.join(", ") + ")");
            });
            return out.join("\n");
        },
        dimensions: function(name, keyArgs) {
            if (null == name) return this._dims;
            var dimType = def.getOwn(this._dims, name, null);
            if (!dimType && def.get(keyArgs, "assertExists", !0)) throw def.error.argumentInvalid("name", "Undefined dimension '{0}'", [ name ]);
            return dimType;
        },
        dimensionsList: function() {
            return this._dimsList;
        },
        calculatedDimensionsList: function() {
            return this._calcDimsList;
        },
        dimensionsNames: function() {
            return this._dimsNames;
        },
        groupDimensions: function(group, keyArgs) {
            var dims = def.getOwn(this._dimsByGroup, group);
            if (!dims && def.get(keyArgs, "assertExists", !0)) throw def.error.operationInvalid("There is no dimension type group with name '{0}'.", [ group ]);
            return dims;
        },
        groupDimensionsNames: function(group, keyArgs) {
            var dimNames = def.getOwn(this._dimsNamesByGroup, group);
            if (!dimNames && def.get(keyArgs, "assertExists", !0)) throw def.error.operationInvalid("There is no dimension type group with name '{0}'.", [ group ]);
            return dimNames;
        },
        addDimension: function(name, dimTypeSpec) {
            name || def.fail.argumentRequired("name");
            !def.hasOwn(this._dims, name) || def.fail.operationInvalid("A dimension type with name '{0}' is already defined.", [ name ]);
            var dimension = new pvc.data.DimensionType(this, name, dimTypeSpec);
            this._dims[name] = dimension;
            this._dimsIndexByName = null;
            var groupLevel, group = dimension.group;
            if (group) {
                var groupDimsNames, groupDims = def.getOwn(this._dimsByGroup, group);
                if (groupDims) groupDimsNames = this._dimsNamesByGroup[group]; else {
                    groupDims = this._dimsByGroup[group] = [];
                    groupDimsNames = this._dimsNamesByGroup[group] = [];
                }
                groupLevel = def.array.insert(groupDimsNames, name, def.compare);
                groupLevel = ~groupLevel;
                def.array.insertAt(groupDims, groupLevel, dimension);
            }
            var index, L = this._dimsList.length;
            if (group) {
                groupLevel = dimension.groupLevel;
                for (var i = 0; L > i; i++) {
                    var dim = this._dimsList[i];
                    if (dim.group === group) {
                        if (dim.groupLevel > groupLevel) {
                            index = i;
                            break;
                        }
                        index = i + 1;
                    }
                }
                null == index && (index = L);
            } else index = L;
            def.array.insertAt(this._dimsList, index, dimension);
            def.array.insertAt(this._dimsNames, index, name);
            if (dimension._calculate) {
                index = def.array.binarySearch(this._calcDimsList, dimension._calculationOrder, def.compare, function(dimType) {
                    return dimType._calculationOrder;
                });
                index >= 0 ? index++ : index = ~index;
                def.array.insertAt(this._calcDimsList, index, dimension);
            }
            this._isPctRoleDimTypeMap = null;
            return dimension;
        },
        addCalculation: function(calcSpec, dimsOptions) {
            calcSpec || def.fail.argumentRequired("calcSpec");
            var calculation = calcSpec.calculation || def.fail.argumentRequired("calculations[i].calculation"), dimNames = calcSpec.names;
            dimNames = "string" == typeof dimNames ? dimNames.split(/\s*\,\s*/) : def.array.as(dimNames);
            if (dimNames && dimNames.length) {
                var calcDimNames = this._calculatedDimNames;
                dimNames.forEach(function(name) {
                    if (name) {
                        name = name.replace(/^\s*(.+?)\s*$/, "$1");
                        !def.hasOwn(calcDimNames, name) || def.fail.argumentInvalid("calculations[i].names", "Dimension name '{0}' is already being calculated.", [ name ]);
                        var dimType = this._dims[name];
                        if (!dimType) {
                            var dimSpec = pvc.data.DimensionType.extendSpec(name, null, dimsOptions);
                            this.addDimension(name, dimSpec);
                        }
                        calcDimNames[name] = !0;
                        dimType._toCalculated();
                    }
                }, this);
            }
            this._calculations.push(calculation);
        },
        isCalculated: function(dimName) {
            return def.hasOwn(this._calculatedDimNames, dimName);
        },
        _calculate: function(complex) {
            var calcs = this._calculations, L = calcs.length;
            if (L) {
                for (var valuesByName = {}, i = 0; L > i; i++) {
                    var calc = calcs[i];
                    calc(complex, valuesByName);
                }
                return valuesByName;
            }
        },
        getPlayingPercentVisualRoleDimensionMap: function() {
            var map = this._isPctRoleDimTypeMap;
            map || (map = this._isPctRoleDimTypeMap = new def.Map(def.query(def.own(this._dims)).where(function(dimType) {
                return dimType.playingPercentVisualRole();
            }).object({
                name: function(dimType) {
                    return dimType.name;
                }
            })));
            return map;
        },
        sortDimensionNames: function(dims, nameKey) {
            var dimsIndexByName = this._dimsIndexByName;
            if (!dimsIndexByName) {
                dimsIndexByName = def.query(this._dimsList).object({
                    name: function(dim) {
                        return dim.name;
                    },
                    value: function(dim, index) {
                        return index;
                    }
                });
                this._dimsIndexByName = dimsIndexByName;
            }
            dims.sort(function(da, db) {
                return def.compare(dimsIndexByName[nameKey ? nameKey(da) : da], dimsIndexByName[nameKey ? nameKey(db) : db]);
            });
            return dims;
        }
    });
    def.type("pvc.data.ComplexTypeProject").init(function(dimGroupSpecs) {
        this._dims = {};
        this._dimList = [];
        this._dimGroupsDims = {};
        this._dimGroupSpecs = dimGroupSpecs || {};
        this._calcList = [];
    }).add({
        _ensureDim: function(name, spec) {
            name || def.fail.argumentInvalid("name", "Invalid dimension name '{0}'.", [ name ]);
            var info = def.getOwn(this._dims, name);
            if (info) spec && def.setUDefaults(info.spec, spec); else {
                info = this._dims[name] = this._createDim(name, spec);
                this._dimList.push(info);
                var groupDimsNames = def.array.lazy(this._dimGroupsDims, info.groupName);
                def.array.insert(groupDimsNames, name, def.compare);
            }
            return info;
        },
        hasDim: function(name) {
            return def.hasOwn(this._dims, name);
        },
        setDim: function(name, spec) {
            var _ = this._ensureDim(name).spec;
            spec && def.copy(_, spec);
            return this;
        },
        setDimDefaults: function(name, spec) {
            def.setUDefaults(this._ensureDim(name).spec, spec);
            return this;
        },
        _createDim: function(name, spec) {
            var dimGroupName = pvc.data.DimensionType.dimensionGroupName(name), dimGroupSpec = this._dimGroupSpecs[dimGroupName];
            dimGroupSpec && (spec = def.create(dimGroupSpec, spec));
            return {
                name: name,
                groupName: dimGroupName,
                spec: spec || {}
            };
        },
        readDim: function(name, spec) {
            var info = this._ensureDim(name, spec);
            if (info.isRead) throw def.error.operationInvalid("Dimension '{0}' already is the target of a reader.", [ name ]);
            if (info.isCalc) throw def.error.operationInvalid("Dimension '{0}' is being calculated, so it cannot be the target of a reader.", [ name ]);
            info.isRead = !0;
        },
        calcDim: function(name, spec) {
            var info = this._ensureDim(name, spec);
            if (info.isCalc) throw def.error.operationInvalid("Dimension '{0}' already is being calculated.", [ name ]);
            if (info.isRead) throw def.error.operationInvalid("Dimension '{0}' is the target of a reader, so it cannot be calculated.", [ name ]);
            info.isCalc = !0;
        },
        isReadOrCalc: function(name) {
            if (name) {
                var info = def.getOwn(this._dims, name);
                if (info) return info.isRead || info.isCalc;
            }
            return !1;
        },
        groupDimensionsNames: function(groupDimName) {
            return this._dimGroupsDims[groupDimName];
        },
        setCalc: function(calcSpec) {
            calcSpec || def.fail.argumentRequired("calculations[i]");
            calcSpec.calculation || def.fail.argumentRequired("calculations[i].calculation");
            var dimNames = calcSpec.names;
            dimNames = "string" == typeof dimNames ? dimNames.split(/\s*\,\s*/) : def.array.as(dimNames);
            dimNames && dimNames.length && dimNames.forEach(this.calcDim, this);
            this._calcList.push(calcSpec);
        },
        configureComplexType: function(complexType, translOptions) {
            this._dimList.forEach(function(dimInfo) {
                var dimName = dimInfo.name, spec = dimInfo.spec;
                spec = pvc.data.DimensionType.extendSpec(dimName, spec, translOptions);
                complexType.addDimension(dimName, spec);
            });
            this._calcList.forEach(function(calcSpec) {
                complexType.addCalculation(calcSpec);
            });
        }
    });
    def.type("pvc.data.TranslationOper").init(function(chart, complexTypeProj, source, metadata, options) {
        this.chart = chart;
        this.complexTypeProj = complexTypeProj;
        this.source = source || def.fail.argumentRequired("source");
        this.metadata = metadata || def.fail.argumentRequired("metadata");
        this.options = options || {};
        this._initType();
        if (pvc.debug >= 4) {
            this._logItems = !0;
            this._logItemCount = 0;
        }
    }).add({
        _logItems: !1,
        logSource: def.method({
            isAbstract: !0
        }),
        logVItem: def.method({
            isAbstract: !0
        }),
        _translType: "Unknown",
        logTranslatorType: function() {
            return this._translType + " data source translator";
        },
        virtualItemSize: function() {
            return this.metadata.length;
        },
        freeVirtualItemSize: function() {
            return this.virtualItemSize() - this._userUsedIndexesCount;
        },
        setSource: function(source) {
            if (!source) throw def.error.argumentRequired("source");
            this.source = source;
        },
        defReader: function(dimReaderSpec) {
            dimReaderSpec || def.fail.argumentRequired("readerSpec");
            var dimNames;
            dimNames = def.string.is(dimReaderSpec) ? dimReaderSpec : dimReaderSpec.names;
            dimNames = def.string.is(dimNames) ? dimNames.split(/\s*\,\s*/) : def.array.as(dimNames);
            var indexes = def.array.as(dimReaderSpec.indexes);
            indexes && indexes.forEach(this._userUseIndex, this);
            var hasDims = !(!dimNames || !dimNames.length), reader = dimReaderSpec.reader;
            if (reader) {
                hasDims || def.fail.argumentRequired("reader.names", "Required argument when a reader function is specified.");
                this._userRead(reader, dimNames);
            } else {
                if (hasDims) return this._userCreateReaders(dimNames, indexes);
                indexes && indexes.forEach(function(index) {
                    this._userIndexesToSingleDim[index] = null;
                }, this);
            }
            return indexes;
        },
        configureType: function() {
            this._configureTypeCore();
        },
        _configureTypeCore: def.method({
            isAbstract: !0
        }),
        _initType: function() {
            this._userDimsReaders = [];
            this._userDimsReadersByDim = {};
            this._userItem = [];
            this._userUsedIndexes = {};
            this._userUsedIndexesCount = 0;
            this._userIndexesToSingleDim = [];
            var userDimReaders = this.options.readers;
            userDimReaders && userDimReaders.forEach(this.defReader, this);
            var multiChartIndexes = pvc.parseDistinctIndexArray(this.options.multiChartIndexes);
            multiChartIndexes && (this._multiChartIndexes = this.defReader({
                names: "multiChart",
                indexes: multiChartIndexes
            }));
        },
        _userUseIndex: function(index) {
            index = +index;
            if (0 > index) throw def.error.argumentInvalid("index", "Invalid reader index: '{0}'.", [ index ]);
            if (def.hasOwn(this._userUsedIndexes, index)) throw def.error.argumentInvalid("index", "Virtual item index '{0}' is already assigned.", [ index ]);
            this._userUsedIndexes[index] = !0;
            this._userUsedIndexesCount++;
            this._userItem[index] = !0;
            return index;
        },
        _userCreateReaders: function(dimNames, indexes) {
            indexes ? indexes.forEach(function(index, j) {
                indexes[j] = +index;
            }) : indexes = [];
            var dimName, I = indexes.length, N = dimNames.length;
            if (N > I) {
                var nextIndex = I > 0 ? indexes[I - 1] + 1 : 0;
                do {
                    nextIndex = this._nextAvailableItemIndex(nextIndex);
                    indexes[I] = nextIndex;
                    this._userUseIndex(nextIndex);
                    I++;
                } while (N > I);
            }
            for (var index, L = I === N ? N : N - 1, n = 0; L > n; n++) {
                dimName = dimNames[n];
                index = indexes[n];
                this._userIndexesToSingleDim[index] = dimName;
                this._userRead(this._propGet(dimName, index), dimName);
            }
            if (N > L) for (var splitGroupName = pvc.splitIndexedId(dimNames[N - 1]), groupName = splitGroupName[0], level = def.nullyTo(splitGroupName[1], 0), i = L; I > i; i++, 
            level++) {
                dimName = pvc.buildIndexedId(groupName, level);
                index = indexes[i];
                this._userIndexesToSingleDim[index] = dimName;
                this._userRead(this._propGet(dimName, index), dimName);
            }
            return indexes;
        },
        _userRead: function(reader, dimNames) {
            def.fun.is(reader) || def.fail.argumentInvalid("reader", "Reader must be a function.");
            def.array.is(dimNames) ? dimNames.forEach(function(name) {
                this._readDim(name, reader);
            }, this) : this._readDim(dimNames, reader);
            this._userDimsReaders.push(reader);
        },
        _readDim: function(name, reader) {
            var info, spec, index = this._userIndexesToSingleDim.indexOf(name);
            if (index >= 0) {
                info = this._itemInfos[index];
                if (info && !this.options.ignoreMetadataLabels) {
                    var label = info.label || info.name;
                    label && (spec = {
                        label: label
                    });
                }
            }
            this.complexTypeProj.readDim(name, spec);
            this._userDimsReadersByDim[name] = reader;
        },
        execute: function(data) {
            this.data = data;
            return this._executeCore();
        },
        _executeCore: function() {
            var dimsReaders = this._getDimensionsReaders();
            return def.query(this._getItems()).select(function(item) {
                return this._readItem(item, dimsReaders);
            }, this);
        },
        _getItems: function() {
            return this.source;
        },
        _getDimensionsReaders: function() {
            return this._userDimsReaders;
        },
        _readItem: function(item, dimsReaders) {
            var logItem = this._logItems;
            if (logItem) {
                var logItemCount = this._logItemCount;
                if (10 > logItemCount) {
                    pvc.log("virtual item [" + this._logItemCount + "]: " + pvc.stringify(item));
                    this._logItemCount++;
                } else {
                    pvc.log("...");
                    logItem = this._logItems = !1;
                }
            }
            for (var r = 0, R = dimsReaders.length, data = this.data, valuesByDimName = {}; R > r; ) dimsReaders[r++].call(data, item, valuesByDimName);
            if (logItem) {
                var atoms = {};
                for (var dimName in valuesByDimName) {
                    var atom = valuesByDimName[dimName];
                    def.object.is(atom) && (atom = "v" in atom ? atom.v : "value" in atom ? atom.value : "...");
                    atoms[dimName] = atom;
                }
                pvc.log("-> read: " + pvc.stringify(atoms));
            }
            return valuesByDimName;
        },
        _propGet: function(dimName, prop) {
            function propGet(item, atoms) {
                atoms[dimName] = item[prop];
            }
            return propGet;
        },
        _nextAvailableItemIndex: function(index, L) {
            null == index && (index = 0);
            null == L && (L = 1/0);
            for (;L > index && def.hasOwn(this._userItem, index); ) index++;
            return L > index ? index : -1;
        },
        _getUnboundRoleDefaultDimNames: function(roleName, count, dims, level) {
            var role = this.chart.visualRoles[roleName];
            if (role && !role.isPreBound()) {
                var dimGroupName = role.defaultDimensionName;
                if (dimGroupName) {
                    dimGroupName = dimGroupName.match(/^(.*?)(\*)?$/)[1];
                    dims || (dims = []);
                    null == level && (level = 0);
                    null == count && (count = 1);
                    for (;count--; ) {
                        var dimName = pvc.buildIndexedId(dimGroupName, level++);
                        this.complexTypeProj.isReadOrCalc(dimName) || dims.push(dimName);
                    }
                    return dims.length ? dims : null;
                }
            }
        },
        collectFreeDiscreteAndConstinuousIndexes: function(freeDisIndexes, freeMeaIndexes) {
            this._itemInfos.forEach(function(info, index) {
                if (!this._userUsedIndexes[index]) {
                    var indexes = 1 === info.type ? freeMeaIndexes : freeDisIndexes;
                    indexes && indexes.push(index);
                }
            }, this);
        }
    });
    def.type("pvc.data.MatrixTranslationOper", pvc.data.TranslationOper).add({
        _initType: function() {
            this.J = this.metadata.length;
            this.I = this.source.length;
            this._processMetadata();
            this.base();
        },
        setSource: function(source) {
            this.base(source);
            this.I = this.source.length;
        },
        _knownContinuousColTypes: {
            numeric: 1,
            number: 1,
            integer: 1
        },
        _processMetadata: function() {
            for (var knownContinColTypes = this._knownContinuousColTypes, columns = def.query(this.metadata).select(function(colDef, colIndex) {
                colDef.colIndex = colIndex;
                return colDef;
            }).where(function(colDef) {
                var colType = colDef.colType;
                return !colType || 1 !== knownContinColTypes[colType.toLowerCase()];
            }).select(function(colDef) {
                return colDef.colIndex;
            }).array(), columnTypes = def.array.create(this.J, 1), I = this.I, source = this.source, J = columns.length, i = 0; I > i && J > 0; i++) for (var row = source[i], m = 0; J > m; ) {
                var j = columns[m], value = row[j];
                if (null != value) {
                    columnTypes[j] = this._getSourceValueType(value);
                    columns.splice(m, 1);
                    J--;
                } else m++;
            }
            this._columnTypes = columnTypes;
        },
        _buildItemInfoFromMetadata: function(index) {
            var meta = this.metadata[index];
            return {
                type: this._columnTypes[index],
                name: meta.colName,
                label: meta.colLabel
            };
        },
        _getSourceValueType: function(value) {
            switch (typeof value) {
              case "number":
                return 1;

              case "object":
                if (value instanceof Date) return 1;
            }
            return 0;
        },
        logSource: function() {
            var out = [ "DATA SOURCE SUMMARY", pvc.logSeparator, "ROWS (" + Math.min(10, this.I) + "/" + this.I + ")" ];
            def.query(this.source).take(10).each(function(row, index) {
                out.push("  [" + index + "] " + pvc.stringify(row));
            });
            this.I > 10 && out.push("  ...");
            out.push("COLS (" + this.J + ")");
            var colTypes = this._columnTypes;
            this.metadata.forEach(function(col, j) {
                out.push("  [" + j + "] '" + col.colName + "' (type: " + col.colType + ", inspected: " + (colTypes[j] ? "number" : "string") + (col.colLabel ? ", label: '" + col.colLabel + "'" : "") + ")");
            });
            out.push("");
            return out.join("\n");
        },
        _logVItem: function(kindList, kindScope) {
            var out = [ "VIRTUAL ITEM ARRAY", pvc.logSeparator ], maxName = 4, maxLabel = 5, maxDim = 9;
            this._itemInfos.forEach(function(info, index) {
                maxName = Math.max(maxName, (info.name || "").length);
                maxLabel = Math.max(maxLabel, (info.label || "").length);
                var dimName = this._userIndexesToSingleDim[index];
                dimName && (maxDim = Math.max(maxDim, dimName.length));
            }, this);
            out.push("Index | Kind | Type   | " + def.string.padRight("Name", maxName) + " | " + def.string.padRight("Label", maxLabel) + " > Dimension", "------+------+--------+-" + def.string.padRight("", maxName, "-") + "-+-" + def.string.padRight("", maxLabel, "-") + "-+-" + def.string.padRight("", maxDim, "-") + "-");
            var index = 0;
            kindList.forEach(function(kind) {
                for (var i = 0, L = kindScope[kind]; L > i; i++) {
                    var info = this._itemInfos[index], dimName = this._userIndexesToSingleDim[index];
                    void 0 === dimName && (dimName = "");
                    out.push(" " + index + "    | " + kind + "    | " + (info.type ? "number" : "string") + " | " + def.string.padRight(info.name || "", maxName) + " | " + def.string.padRight(info.label || "", maxLabel) + " | " + dimName);
                    index++;
                }
            }, this);
            out.push("");
            return out.join("\n");
        },
        _createPlot2SeriesKeySet: function(plot2DataSeriesIndexes, seriesKeys) {
            var plot2SeriesKeySet = null, seriesCount = seriesKeys.length;
            def.query(plot2DataSeriesIndexes).each(function(indexText) {
                var seriesIndex = +indexText;
                if (isNaN(seriesIndex)) throw def.error.argumentInvalid("plot2DataSeriesIndexes", "Element is not a number '{0}'.", [ indexText ]);
                if (0 > seriesIndex) {
                    if (-seriesCount >= seriesIndex) throw def.error.argumentInvalid("plot2DataSeriesIndexes", "Index is out of range '{0}'.", [ seriesIndex ]);
                    seriesIndex = seriesCount + seriesIndex;
                } else if (seriesIndex >= seriesCount) throw def.error.argumentInvalid("plot2DataSeriesIndexes", "Index is out of range '{0}'.", [ seriesIndex ]);
                plot2SeriesKeySet || (plot2SeriesKeySet = {});
                plot2SeriesKeySet[seriesKeys[seriesIndex]] = !0;
            });
            return plot2SeriesKeySet;
        },
        _dataPartGet: function(calcAxis2SeriesKeySet, seriesReader) {
            function dataPartGet(item, outAtoms) {
                if (!dataPartDimension) {
                    plot2SeriesKeySet = calcAxis2SeriesKeySet();
                    dataPartDimension = me.data.dimensions(dataPartDimName);
                    pvc.debug >= 3 && plot2SeriesKeySet && pvc.log("Second axis series values: " + pvc.stringify(def.keys(plot2SeriesKeySet)));
                }
                var partAtom;
                seriesReader(item, outAtomsSeries);
                var series = outAtomsSeries.series;
                null != series && null != series.v && (series = series.v);
                partAtom = def.hasOwn(plot2SeriesKeySet, series) ? part2Atom || (part2Atom = dataPartDimension.intern("1")) : part1Atom || (part1Atom = dataPartDimension.intern("0"));
                outAtoms[dataPartDimName] = partAtom;
            }
            var dataPartDimension, plot2SeriesKeySet, part1Atom, part2Atom, me = this, dataPartDimName = this.options.dataPartDimName, outAtomsSeries = {};
            return dataPartGet;
        }
    });
    def.type("pvc.data.CrosstabTranslationOper", pvc.data.MatrixTranslationOper).add({
        _translType: "Crosstab",
        virtualItemSize: function() {
            return this.R + this.C + this.M;
        },
        _executeCore: function() {
            function updateVItemCrossGroup(crossGroupId, source) {
                for (var itemIndex = itemCrossGroupIndex[crossGroupId], sourceIndex = 0, depth = me[crossGroupId]; depth-- > 0; ) item[itemIndex++] = source[sourceIndex++];
            }
            function updateVItemMeasure(line, cg) {
                for (var itemIndex = itemCrossGroupIndex.M, cgIndexes = me._colGroupsIndexes[cg], depth = me.M, i = 0; depth > i; i++) {
                    var lineIndex = cgIndexes[i];
                    item[itemIndex++] = null != lineIndex ? line[lineIndex] : null;
                }
            }
            if (!this.metadata.length) return def.query();
            var dimsReaders = this._getDimensionsReaders(), item = new Array(this.virtualItemSize()), itemCrossGroupIndex = this._itemCrossGroupIndex, me = this, q = def.query(this.source);
            if (this._colGroups && this._colGroups.length) {
                var expandLine = function(line) {
                    updateVItemCrossGroup("R", line);
                    return def.query(this._colGroups).select(function(colGroup, cg) {
                        updateVItemCrossGroup("C", colGroup);
                        updateVItemMeasure(line, cg);
                        return this._readItem(item, dimsReaders);
                    }, this);
                };
                return q.selectMany(expandLine, this);
            }
            return q.select(function(line) {
                updateVItemCrossGroup("R", line);
                return this._readItem(item, dimsReaders);
            }, this);
        },
        _processMetadata: function() {
            this.base();
            this._separator = this.options.separator || "~";
            var R = this.R = 1;
            this.C = 1;
            this.M = 1;
            this.measuresDirection = null;
            var colNames, seriesInRows = this.options.seriesInRows, metadata = this.metadata;
            colNames = metadata.map(seriesInRows ? function(d) {
                return d.colName;
            } : this.options.compatVersion <= 1 ? function(d) {
                return {
                    v: d.colName
                };
            } : function(d) {
                return {
                    v: d.colName,
                    f: d.colLabel
                };
            });
            var itemCrossGroupInfos = this._itemCrossGroupInfos = {};
            if (this.options.isMultiValued) {
                var measuresInColumns = def.get(this.options, "measuresInColumns", !0);
                if (measuresInColumns || null == this.options.measuresIndex) {
                    R = this.R = this._getCategoriesCount();
                    var encodedColGroups = colNames.slice(R), L = encodedColGroups.length;
                    if (L > 0) {
                        if (measuresInColumns) {
                            this.measuresDirection = "columns";
                            this._processEncodedColGroups(encodedColGroups);
                        } else {
                            this._colGroups = encodedColGroups;
                            this._colGroupsIndexes = [];
                            this._colGroups.forEach(function(colGroup, cg) {
                                this._colGroups[cg] = this._splitEncodedColGroupCell(colGroup);
                                this._colGroupsIndexes[cg] = [ this.R + cg ];
                            }, this);
                            itemCrossGroupInfos.M = [ this._buildItemInfoFromMetadata(R) ];
                        }
                        this.C = this._colGroups[0].length;
                        itemCrossGroupInfos.C = def.range(0, this.C).select(function() {
                            return {
                                type: 0
                            };
                        }).array();
                    } else {
                        this.C = this.M = 0;
                        itemCrossGroupInfos.M = [];
                        itemCrossGroupInfos.C = [];
                    }
                } else {
                    this.measuresDirection = "rows";
                    this.R = +this.options.measuresIndex;
                    var measuresCount = this.options.measuresCount;
                    null == measuresCount && (measuresCount = 1);
                    this.M = measuresCount;
                    this._colGroups = colNames.slice(this.R + 1);
                    this._colGroups.forEach(function(colGroup, cg) {
                        this._colGroups[cg] = [ colGroup ];
                    }, this);
                }
            } else {
                R = this.R = this._getCategoriesCount();
                this._colGroups = colNames.slice(R);
                this._colGroupsIndexes = new Array(this._colGroups.length);
                this._colGroups.forEach(function(colGroup, cg) {
                    this._colGroups[cg] = [ colGroup ];
                    this._colGroupsIndexes[cg] = [ R + cg ];
                }, this);
                itemCrossGroupInfos.C = [ {
                    type: 0
                } ];
                itemCrossGroupInfos.M = [ {
                    type: this._columnTypes[R]
                } ];
            }
            itemCrossGroupInfos.R = def.range(0, this.R).select(this._buildItemInfoFromMetadata, this).array();
            var itemGroupIndex = this._itemCrossGroupIndex = {
                C: seriesInRows ? this.R : 0,
                R: seriesInRows ? 0 : this.C,
                M: this.C + this.R
            }, itemInfos = this._itemInfos = new Array(this.virtualItemSize());
            def.eachOwn(itemGroupIndex, function(groupStartIndex, crossGroup) {
                itemCrossGroupInfos[crossGroup].forEach(function(info, groupIndex) {
                    itemInfos[groupStartIndex + groupIndex] = info;
                });
            });
            this._itemLogicalGroup = {
                series: seriesInRows ? this.R : this.C,
                category: seriesInRows ? this.C : this.R,
                value: this.M
            };
            this._itemLogicalGroupIndex = {
                series: 0,
                category: this._itemLogicalGroup.series,
                value: this.C + this.R
            };
        },
        logVItem: function() {
            return this._logVItem([ "C", "R", "M" ], {
                C: this.C,
                R: this.R,
                M: this.M
            });
        },
        _getCategoriesCount: function() {
            var R = this.options.categoriesCount;
            null != R && (!isFinite(R) || 0 > R) && (R = null);
            if (null == R) {
                R = def.query(this._columnTypes).whayl(function(type) {
                    return 0 === type;
                }).count();
                R || (R = 1);
            }
            return R;
        },
        _splitEncodedColGroupCell: function(colGroup) {
            var labels, values = colGroup.v;
            if (null == values) values = []; else {
                values = values.split(this._separator);
                labels = colGroup.f;
                labels && (labels = labels.split(this._separator));
            }
            return values.map(function(value, index) {
                return {
                    v: value,
                    f: labels && labels[index]
                };
            });
        },
        _processEncodedColGroups: function(encodedColGroups) {
            for (var currColGroup, L = encodedColGroups.length || def.assert("Must have columns"), R = this.R, colGroups = [], measuresInfo = {}, measuresInfoList = [], i = 0; L > i; i++) {
                var meaName, meaLabel, colGroupValues, colGroupLabels, colGroupCell = encodedColGroups[i], encColGroupValues = colGroupCell.v, encColGroupLabels = colGroupCell.f, sepIndex = encColGroupValues.lastIndexOf(this._separator);
                if (0 > sepIndex) {
                    meaName = encColGroupValues;
                    meaLabel = encColGroupLabels;
                    encColGroupValues = "";
                    colGroupValues = [];
                } else {
                    meaName = encColGroupValues.substring(sepIndex + 1);
                    encColGroupValues = encColGroupValues.substring(0, sepIndex);
                    colGroupValues = encColGroupValues.split(this._separator);
                    if (null != encColGroupLabels) {
                        colGroupLabels = encColGroupLabels.split(this._separator);
                        meaLabel = colGroupLabels.pop();
                    }
                    colGroupValues.forEach(function(value, index) {
                        var label = colGroupLabels && colGroupLabels[index];
                        colGroupValues[index] = {
                            v: value,
                            f: label
                        };
                    });
                }
                if (currColGroup && currColGroup.encValues === encColGroupValues) currColGroup.measureNames.push(meaName); else {
                    currColGroup = {
                        startIndex: i,
                        encValues: encColGroupValues,
                        values: colGroupValues,
                        measureNames: [ meaName ]
                    };
                    colGroups.push(currColGroup);
                }
                var currMeaIndex = i - currColGroup.startIndex, meaInfo = def.getOwn(measuresInfo, meaName);
                if (meaInfo) currMeaIndex > meaInfo.groupIndex && (meaInfo.groupIndex = currMeaIndex); else {
                    measuresInfo[meaName] = meaInfo = {
                        name: meaName,
                        label: meaLabel,
                        type: this._columnTypes[R + i],
                        groupIndex: currMeaIndex,
                        index: i
                    };
                    measuresInfoList.push(meaInfo);
                }
            }
            measuresInfoList.sort(function(meaInfoA, meaInfoB) {
                return def.compare(meaInfoA.groupIndex, meaInfoB.groupIndex) || def.compare(meaInfoA.index, meaInfoB.index);
            });
            measuresInfoList.forEach(function(meaInfoA, index) {
                meaInfoA.groupIndex = index;
            });
            var CG = colGroups.length, colGroupsValues = new Array(CG), colGroupsIndexes = new Array(CG), M = measuresInfoList.length;
            colGroups.map(function(colGroup, cg) {
                colGroupsValues[cg] = colGroup.values;
                var colGroupStartIndex = colGroup.startIndex, meaIndexes = colGroupsIndexes[cg] = new Array(M);
                colGroup.measureNames.forEach(function(meaName2, localMeaIndex) {
                    var meaIndex = measuresInfo[meaName2].groupIndex;
                    meaIndexes[meaIndex] = R + colGroupStartIndex + localMeaIndex;
                });
            });
            this._colGroups = colGroupsValues;
            this._colGroupsIndexes = colGroupsIndexes;
            this._itemCrossGroupInfos.M = measuresInfoList;
            this.M = M;
        },
        configureType: function() {
            if ("rows" === this.measuresDirection) throw def.error.notImplemented();
            this.base();
        },
        _configureTypeCore: function() {
            function add(dimGroupName, level, count) {
                for (var crossEndIndex = itemLogicalGroupIndex[dimGroupName] + count; count > 0; ) {
                    var dimName = pvc.buildIndexedId(dimGroupName, level);
                    if (!me.complexTypeProj.isReadOrCalc(dimName)) {
                        index = me._nextAvailableItemIndex(index);
                        if (index >= crossEndIndex) return;
                        dimsReaders.push({
                            names: dimName,
                            indexes: index
                        });
                        index++;
                        count--;
                    }
                    level++;
                }
            }
            var me = this, itemLogicalGroup = me._itemLogicalGroup, itemLogicalGroupIndex = me._itemLogicalGroupIndex, index = 0, dimsReaders = [], dataPartDimName = this.options.dataPartDimName;
            if (dataPartDimName && 1 === this.C && !this.complexTypeProj.isReadOrCalc(dataPartDimName)) {
                var plot2DataSeriesIndexes = this.options.plot2DataSeriesIndexes;
                if (null != plot2DataSeriesIndexes) {
                    var seriesKeys = this._colGroups.map(function(colGroup) {
                        return "" + colGroup[0].v;
                    });
                    this._plot2SeriesKeySet = this._createPlot2SeriesKeySet(plot2DataSeriesIndexes, seriesKeys);
                }
            }
            [ "series", "category", "value" ].forEach(function(dimGroupName) {
                var L = itemLogicalGroup[dimGroupName];
                L > 0 && add(dimGroupName, 0, L);
            });
            dimsReaders && dimsReaders.forEach(this.defReader, this);
            if (this._plot2SeriesKeySet) {
                var seriesReader = this._userDimsReadersByDim.series;
                if (seriesReader) {
                    var calcAxis2SeriesKeySet = def.fun.constant(this._plot2SeriesKeySet);
                    this._userRead(this._dataPartGet(calcAxis2SeriesKeySet, seriesReader), dataPartDimName);
                }
            }
        }
    });
    def.type("pvc.data.RelationalTranslationOper", pvc.data.MatrixTranslationOper).add({
        M: 0,
        C: 0,
        S: 0,
        _translType: "Relational",
        _processMetadata: function() {
            this.base();
            var metadata = this.metadata, J = this.J, C = this.options.categoriesCount;
            null != C && (!isFinite(C) || 0 > C) && (C = 0);
            var S, valuesColIndexes, M;
            if (this.options.isMultiValued) {
                valuesColIndexes = pvc.parseDistinctIndexArray(this.options.measuresIndexes, 0, J - 1);
                M = valuesColIndexes ? valuesColIndexes.length : 0;
            }
            var D;
            if (null == M) if (J > 0 && 3 >= J && (null == C || 1 === C) && null == S) {
                M = 1;
                valuesColIndexes = [ J - 1 ];
                C = J >= 2 ? 1 : 0;
                S = J >= 3 ? 1 : 0;
                D = C + S;
            } else if (null != C && C >= J) {
                D = J;
                C = J;
                S = 0;
                M = 0;
            } else {
                var Mmax = null != C ? J - C : 1/0;
                valuesColIndexes = def.query(metadata).where(function(colDef, index) {
                    return 0 !== this._columnTypes[index];
                }, this).select(function(colDef) {
                    return colDef.colIndex;
                }).take(Mmax).array();
                M = valuesColIndexes.length;
            }
            if (null == D) {
                D = J - M;
                if (0 === D) S = C = 0; else if (null != C) if (C > D) {
                    C = D;
                    S = 0;
                } else S = D - C; else {
                    S = D > 1 ? 1 : 0;
                    C = D - S;
                }
            }
            var seriesInRows = this.options.seriesInRows, colGroupSpecs = [];
            if (D) {
                S && !seriesInRows && colGroupSpecs.push({
                    name: "S",
                    count: S
                });
                C && colGroupSpecs.push({
                    name: "C",
                    count: C
                });
                S && seriesInRows && colGroupSpecs.push({
                    name: "S",
                    count: S
                });
            }
            M && colGroupSpecs.push({
                name: "M",
                count: M
            });
            var availableInputIndexes = def.range(0, J).array();
            valuesColIndexes && valuesColIndexes.forEach(function(inputIndex) {
                availableInputIndexes.splice(inputIndex, 1);
            });
            var specsByName = {};
            colGroupSpecs.forEach(function(groupSpec) {
                var count = groupSpec.count, name = groupSpec.name;
                specsByName[name] = groupSpec;
                groupSpec.indexes = valuesColIndexes && "M" === name ? valuesColIndexes : availableInputIndexes.splice(0, count);
            });
            this.M = M;
            this.S = S;
            this.C = C;
            var itemPerm = [];
            [ "S", "C", "M" ].forEach(function(name) {
                var groupSpec = specsByName[name];
                groupSpec && def.array.append(itemPerm, groupSpec.indexes);
            });
            this._itemInfos = itemPerm.map(this._buildItemInfoFromMetadata, this);
            this._itemCrossGroupIndex = {
                S: 0,
                C: this.S,
                M: this.S + this.C
            };
            this._itemPerm = itemPerm;
        },
        logVItem: function() {
            return this._logVItem([ "S", "C", "M" ], {
                S: this.S,
                C: this.C,
                M: this.M
            });
        },
        _configureTypeCore: function() {
            function add(dimGroupName, colGroupName, level, count) {
                for (var groupEndIndex = me._itemCrossGroupIndex[colGroupName] + count; count > 0; ) {
                    var dimName = pvc.buildIndexedId(dimGroupName, level);
                    if (!me.complexTypeProj.isReadOrCalc(dimName)) {
                        index = me._nextAvailableItemIndex(index);
                        if (index >= groupEndIndex) return;
                        dimsReaders.push({
                            names: dimName,
                            indexes: index
                        });
                        index++;
                        count--;
                    }
                    level++;
                }
            }
            var me = this, index = 0, dimsReaders = [];
            this.S > 0 && add("series", "S", 0, this.S);
            this.C > 0 && add("category", "C", 0, this.C);
            this.M > 0 && add("value", "M", 0, this.M);
            dimsReaders && dimsReaders.forEach(this.defReader, this);
            var dataPartDimName = this.options.dataPartDimName;
            if (dataPartDimName && !this.complexTypeProj.isReadOrCalc(dataPartDimName)) {
                var plot2DataSeriesIndexes = this.options.plot2DataSeriesIndexes;
                if (null != plot2DataSeriesIndexes) {
                    var seriesReader = this._userDimsReadersByDim.series;
                    seriesReader && this._userRead(relTransl_dataPartGet.call(this, plot2DataSeriesIndexes, seriesReader), dataPartDimName);
                }
            }
        },
        _executeCore: function() {
            var dimsReaders = this._getDimensionsReaders(), permIndexes = this._itemPerm;
            return def.query(this._getItems()).select(function(item) {
                item = pv.permute(item, permIndexes);
                return this._readItem(item, dimsReaders);
            }, this);
        }
    });
    def.type("pvc.data.Atom").init(function(dimension, value, label, rawValue, key) {
        this.dimension = dimension;
        this.id = null == value ? -def.nextId() : def.nextId();
        this.value = value;
        this.label = label;
        void 0 !== rawValue && (this.rawValue = rawValue);
        this.key = key;
    }).add({
        isVirtual: !1,
        rawValue: void 0,
        toString: function() {
            var label = this.label;
            if (null != label) return label;
            label = this.value;
            return null != label ? "" + label : "";
        }
    });
    var complex_nextId = 1;
    def.type("pvc.data.Complex").init(function(source, atomsByName, dimNames, atomsBase, wantLabel, calculate) {
        var me = this;
        me.id = complex_nextId++;
        var owner;
        if (source) {
            owner = source.owner;
            atomsBase || (atomsBase = source.atoms);
        }
        me.owner = owner = owner || me;
        var type = owner.type || def.fail.argumentRequired("owner.type");
        me.atoms = atomsBase ? Object.create(atomsBase) : {};
        var dimNamesSpecified = !!dimNames;
        dimNames || (dimNames = type._dimsNames);
        var i, dimName, atomsMap = me.atoms, D = dimNames.length;
        if (atomsByName) {
            var ownerDims = owner._dimensions, addAtom = function(dimName) {
                var v = atomsByName[dimName], atom = ownerDims[dimName].intern(v);
                null == v || atomsBase && atom === atomsBase[dimName] || (atomsMap[dimName] = atom);
            };
            if (dimNamesSpecified) {
                i = D;
                for (;i--; ) addAtom(dimNames[i]);
            } else for (dimName in atomsByName) addAtom(dimName);
            if (calculate) {
                atomsByName = type._calculate(me);
                for (dimName in atomsByName) def.hasOwnProp.call(atomsMap, dimName) || addAtom(dimName);
            }
        }
        var atom;
        if (D) if (1 === D) {
            atom = atomsMap[dimNames[0]];
            me.value = atom.value;
            me.rawValue = atom.rawValue;
            me.key = atom.key;
            wantLabel && (me.label = atom.label);
        } else {
            var key, label, alabel, keySep = owner.keySep, labelSep = owner.labelSep;
            for (i = 0; D > i; i++) {
                atom = atomsMap[dimNames[i]];
                i ? key += keySep + atom.key : key = atom.key;
                wantLabel && (alabel = atom.label) && (label ? label += labelSep + alabel : label = alabel);
            }
            me.value = me.rawValue = me.key = key;
            wantLabel && (me.label = label);
        } else {
            me.value = null;
            me.key = "";
            wantLabel && (me.label = "");
        }
    }).add({
        labelSep: " ~ ",
        keySep: "~",
        value: null,
        label: null,
        rawValue: void 0,
        ensureLabel: function() {
            var label = this.label;
            if (null == label) {
                label = "";
                var labelSep = this.owner.labelSep;
                def.eachOwn(this.atoms, function(atom) {
                    var alabel = atom.label;
                    alabel && (label ? label += labelSep + alabel : label = alabel);
                });
                this.label = label;
            }
            return label;
        },
        view: function(dimNames) {
            return new pvc.data.ComplexView(this, dimNames);
        },
        toString: function() {
            var s = [ "" + this.constructor.typeName ];
            null != this.index && s.push("#" + this.index);
            this.owner.type.dimensionsNames().forEach(function(name) {
                s.push(name + ": " + pvc.stringify(this.atoms[name].value));
            }, this);
            return s.join(" ");
        },
        rightTrimKeySep: function(key) {
            return key && pvc.data.Complex.rightTrimKeySep(key, this.owner.keySep);
        },
        absKeyTrimmed: function() {
            return this.rightTrimKeySep(this.absKey);
        },
        keyTrimmed: function() {
            return this.rightTrimKeySep(this.key);
        }
    });
    pvc.data.Complex.rightTrimKeySep = function(key, keySep) {
        if (key && keySep) for (var j, K = keySep.length; key.lastIndexOf(keySep) === (j = key.length - K) && j >= 0; ) key = key.substr(0, j);
        return key;
    };
    pvc.data.Complex.values = function(complex, dimNames) {
        var atoms = complex.atoms;
        return dimNames.map(function(dimName) {
            return atoms[dimName].value;
        });
    };
    pvc.data.Complex.compositeKey = function(complex, dimNames) {
        var atoms = complex.atoms;
        return dimNames.map(function(dimName) {
            return atoms[dimName].key;
        }).join(complex.owner.keySep);
    };
    pvc.data.Complex.labels = function(complex, dimNames) {
        var atoms = complex.atoms;
        return dimNames.map(function(dimName) {
            return atoms[dimName].label;
        });
    };
    var complex_id = def.propGet("id");
    def.type("pvc.data.ComplexView", pvc.data.Complex).init(function(source, viewDimNames) {
        this.source = source;
        this.viewDimNames = viewDimNames;
        this.base(source, source.atoms, viewDimNames, source.owner.atoms, !0);
    }).add({
        values: function() {
            return pvc.data.Complex.values(this, this.viewDimNames);
        },
        labels: function() {
            return pvc.data.Complex.labels(this, this.viewDimNames);
        }
    });
    def.type("pvc.data.Datum", pvc.data.Complex).init(function(data, atomsByName) {
        this.base(data, atomsByName, null, null, !1, !0);
    }).add({
        isSelected: !1,
        isVisible: !0,
        isNull: !1,
        isVirtual: !1,
        isTrend: !1,
        trend: null,
        isInterpolated: !1,
        interpolation: null,
        setSelected: function(select) {
            if (this.isNull) return !1;
            select = null == select || !!select;
            var changed = this.isSelected !== select;
            if (changed) {
                select ? this.isSelected = !0 : delete this.isSelected;
                data_onDatumSelectedChanged.call(this.owner, this, select);
            }
            return changed;
        },
        toggleSelected: function() {
            return this.setSelected(!this.isSelected);
        },
        setVisible: function(visible) {
            if (this.isNull) return !1;
            visible = null == visible || !!visible;
            var changed = this.isVisible !== visible;
            if (changed) {
                this.isVisible = visible;
                data_onDatumVisibleChanged.call(this.owner, this, visible);
            }
            return changed;
        },
        toggleVisible: function() {
            return this.setVisible(!this.isVisible);
        }
    });
    var datum_isSelected = def.propGet("isSelected");
    def.type("pvc.data.TrendDatum", pvc.data.Datum).init(function(data, atomsByName, trend) {
        this.base(data, atomsByName);
        this.trend = trend;
    }).add({
        isVirtual: !0,
        isTrend: !0
    });
    def.type("pvc.data.InterpolationDatum", pvc.data.Datum).init(function(data, atomsByName, interpolation) {
        this.base(data, atomsByName);
        this.interpolation = interpolation;
    }).add({
        isVirtual: !0,
        isInterpolated: !0
    });
    def.type("pvc.data.Dimension").init(function(data, type) {
        this.data = data;
        this.type = type;
        this.root = this;
        this.owner = this;
        var name = type.name;
        this.name = name;
        this._atomComparer = type.atomComparer();
        this._atomsByKey = {};
        if (data.isOwner()) {
            this._atoms = [];
            dim_createVirtualNullAtom.call(this);
        } else {
            var source, parentData = data.parent;
            if (parentData) {
                source = parentData._dimensions[name];
                dim_addChild.call(source, this);
                this.root = data.parent.root;
            } else {
                parentData = data.linkParent;
                parentData || def.assert("Data must have a linkParent");
                source = parentData._dimensions[name];
                dim_addLinkChild.call(source, this);
            }
            this._nullAtom = this.owner._nullAtom;
            this._lazyInit = function() {
                this._lazyInit = null;
                for (var datums = this.data._datums, L = datums.length, atomsByKey = this._atomsByKey, i = 0; L > i; i++) {
                    var atom = datums[i].atoms[name];
                    atomsByKey[atom.key] = atom;
                }
                this._atoms = source.atoms().filter(function(atom) {
                    return def.hasOwnProp.call(atomsByKey, atom.key);
                });
            };
        }
    }).add({
        parent: null,
        linkParent: null,
        _linkChildren: null,
        _atomsByKey: null,
        _atomVisibleDatumsCount: null,
        _disposed: !1,
        _nullAtom: null,
        _virtualNullAtom: null,
        _visibleAtoms: null,
        _visibleIndexes: null,
        _atomComparer: null,
        _atoms: null,
        _sumCache: null,
        count: function() {
            this._lazyInit && this._lazyInit();
            return this._atoms.length;
        },
        isVisible: function(atom) {
            this._lazyInit && this._lazyInit();
            def.hasOwn(this._atomsByKey, atom.key) || def.assert("Atom must exist in this dimension.");
            return dim_getVisibleDatumsCountMap.call(this)[atom.key] > 0;
        },
        atoms: function(keyArgs) {
            this._lazyInit && this._lazyInit();
            var visible = def.get(keyArgs, "visible");
            if (null == visible) return this._atoms;
            visible = !!visible;
            this._visibleAtoms || (this._visibleAtoms = {});
            return this._visibleAtoms[visible] || (this._visibleAtoms[visible] = dim_calcVisibleAtoms.call(this, visible));
        },
        indexes: function(keyArgs) {
            this._lazyInit && this._lazyInit();
            var visible = def.get(keyArgs, "visible");
            if (null == visible) return pv.range(0, this._atoms.length);
            visible = !!visible;
            this._visibleIndexes || (this._visibleIndexes = {});
            return this._visibleIndexes[visible] || (this._visibleIndexes[visible] = dim_calcVisibleIndexes.call(this, visible));
        },
        atom: function(value) {
            if (null == value || "" === value) return this._nullAtom;
            if (value instanceof pvc.data.Atom) return value;
            this._lazyInit && this._lazyInit();
            var typeKey = this.type._key, key = typeKey ? typeKey.call(null, value) : value;
            return this._atomsByKey[key] || null;
        },
        getDistinctAtoms: function(values) {
            var atoms = [], L = values ? values.length : 0;
            if (L) for (var atomsByKey = {}, i = 0; L > i; i++) {
                var key, atom = this.atom(values[i]);
                if (atom && !atomsByKey[key = "\x00" + atom.key]) {
                    atomsByKey[key] = atom;
                    atoms.push(atom);
                }
            }
            return atoms;
        },
        extent: function(keyArgs) {
            var atoms = this.atoms(keyArgs), L = atoms.length;
            if (!L) return void 0;
            var offset = this._nullAtom && null == atoms[0].value ? 1 : 0, countWithoutNull = L - offset;
            if (countWithoutNull > 0) {
                var tmp, min = atoms[offset], max = atoms[L - 1];
                if (min !== max && def.get(keyArgs, "abs", !1)) {
                    var minSign = min.value < 0 ? -1 : 1, maxSign = max.value < 0 ? -1 : 1;
                    if (minSign === maxSign) {
                        if (0 > maxSign) {
                            tmp = max;
                            max = min;
                            min = tmp;
                        }
                    } else if (countWithoutNull > 2) {
                        max.value < -min.value && (max = min);
                        var zeroIndex = def.array.binarySearch(atoms, 0, this.type.comparer(), function(a) {
                            return a.value;
                        });
                        if (0 > zeroIndex) {
                            zeroIndex = ~zeroIndex;
                            var negAtom = atoms[zeroIndex - 1], posAtom = atoms[zeroIndex];
                            min = -negAtom.value < posAtom.value ? negAtom : posAtom;
                        } else min = atoms[zeroIndex];
                    } else if (max.value < -min.value) {
                        tmp = max;
                        max = min;
                        min = tmp;
                    }
                }
                return {
                    min: min,
                    max: max
                };
            }
            return void 0;
        },
        min: function(keyArgs) {
            var atoms = this.atoms(keyArgs), L = atoms.length;
            if (!L) return void 0;
            var offset = this._nullAtom && null == atoms[0].value ? 1 : 0;
            return L > offset ? atoms[offset] : void 0;
        },
        max: function(keyArgs) {
            var atoms = this.atoms(keyArgs), L = atoms.length;
            return L && null != atoms[L - 1].value ? atoms[L - 1] : void 0;
        },
        sumAbs: function(keyArgs) {
            return this.sum(def.create(keyArgs, {
                abs: !0
            }));
        },
        value: function(keyArgs) {
            return this.sum(keyArgs && keyArgs.abs ? def.create(keyArgs, {
                abs: !1
            }) : keyArgs);
        },
        valueAbs: function(keyArgs) {
            var value = this.value(keyArgs);
            return value ? Math.abs(value) : value;
        },
        sum: function(keyArgs) {
            var isAbs = !!def.get(keyArgs, "abs", !1), zeroIfNone = def.get(keyArgs, "zeroIfNone", !0), key = dim_buildDatumsFilterKey(keyArgs) + ":" + isAbs, sum = def.getOwn(this._sumCache, key);
            if (void 0 === sum) {
                var dimName = this.name;
                sum = this.data.datums(null, keyArgs).reduce(function(sum2, datum) {
                    var value = datum.atoms[dimName].value;
                    isAbs && 0 > value && (value = -value);
                    return null != sum2 ? sum2 + value : value;
                }, null);
                (this._sumCache || (this._sumCache = {}))[key] = sum;
            }
            return zeroIfNone ? sum || 0 : sum;
        },
        percent: function(atomOrValue, keyArgs) {
            var value = atomOrValue instanceof pvc.data.Atom ? atomOrValue.value : atomOrValue;
            if (!value) return 0;
            var sum = this.sumAbs(keyArgs);
            return sum ? Math.abs(value) / sum : 0;
        },
        valuePercent: function(keyArgs) {
            var value = this.valueAbs(keyArgs);
            if (!value) return 0;
            var parentData = this.data.parent;
            if (!parentData) return 1;
            var sum = parentData.dimensionsSumAbs(this.name, keyArgs);
            return value / sum;
        },
        percentOverParent: function(keyArgs) {
            return this.valuePercent(keyArgs);
        },
        format: function(value, sourceValue) {
            return "" + (this.type._formatter ? this.type._formatter.call(null, value, sourceValue) : "");
        },
        intern: function(sourceValue, isVirtual) {
            if (null == sourceValue || "" === sourceValue) return this._nullAtom || dim_createNullAtom.call(this, sourceValue);
            if (sourceValue instanceof pvc.data.Atom) {
                if (sourceValue.dimension !== this) throw def.error.operationInvalid("Atom is of a different dimension.");
                return sourceValue;
            }
            var value, label, type = this.type;
            if ("object" == typeof sourceValue && "v" in sourceValue) {
                label = sourceValue.f;
                sourceValue = sourceValue.v;
                if (null == sourceValue || "" === sourceValue) return this._nullAtom || dim_createNullAtom.call(this);
            }
            if (isVirtual) value = sourceValue; else {
                var converter = type._converter;
                if (converter) {
                    value = converter(sourceValue);
                    if (null == value || "" === value) return this._nullAtom || dim_createNullAtom.call(this, sourceValue);
                } else value = sourceValue;
            }
            var cast = type.cast;
            if (cast) {
                value = cast(value);
                if (null == value || "" === value) return this._nullAtom || dim_createNullAtom.call(this);
            }
            var keyFun = type._key, key = "" + (keyFun ? keyFun(value) : value);
            key || def.fail.operationInvalid("Only a null value can have an empty key.");
            var atom = this._atomsByKey[key];
            if (atom) {
                !isVirtual && atom.isVirtual && delete atom.isVirtual;
                return atom;
            }
            return dim_createAtom.call(this, type, sourceValue, key, value, label, isVirtual);
        },
        read: function(sourceValue, label) {
            if (null == sourceValue || "" === sourceValue) return null;
            var value, type = this.type;
            if ("object" == typeof sourceValue && "v" in sourceValue) {
                label = sourceValue.f;
                sourceValue = sourceValue.v;
                if (null == sourceValue || "" === sourceValue) return null;
            }
            var converter = type._converter;
            value = converter ? converter(sourceValue) : sourceValue;
            if (null == value || "" === value) return null;
            var cast = type.cast;
            if (cast) {
                value = cast(value);
                if (null == value || "" === value) return null;
            }
            var keyFun = type._key, key = "" + (keyFun ? keyFun(value) : value), atom = this._atomsByKey[key];
            if (atom) return {
                rawValue: sourceValue,
                key: key,
                value: atom.value,
                label: "" + (null == label ? atom.label : label)
            };
            if (null == label) {
                var formatter = type._formatter;
                label = formatter ? formatter(value, sourceValue) : value;
            }
            label = "" + label;
            return {
                rawValue: sourceValue,
                key: key,
                value: value,
                label: label
            };
        },
        dispose: function() {
            var me = this;
            if (!me._disposed) {
                data_disposeChildList(me.childNodes, "parent");
                data_disposeChildList(me._linkChildren, "linkParent");
                var v;
                (v = me.parent) && data_removeColChild(v, "childNodes", me, "parent");
                (v = me.linkParent) && data_removeColChild(v, "_linkChildren", me, "linkParent");
                dim_clearVisiblesCache.call(me);
                me._lazyInit = me._atoms = me._nullAtom = me._virtualNullAtom = null;
                me._disposed = !0;
            }
        }
    });
    def.type("pvc.data.Data", pvc.data.Complex).init(function(keyArgs) {
        keyArgs || def.fail.argumentRequired("keyArgs");
        this._visibleNotNullDatums = new def.Map();
        var owner, atoms, atomsBase, atomsDimNames, datums, index, parent = this.parent = keyArgs.parent || null;
        if (parent) {
            this.root = parent.root;
            this.depth = parent.depth + 1;
            this.type = parent.type;
            datums = keyArgs.datums || def.fail.argumentRequired("datums");
            owner = parent.owner;
            atoms = keyArgs.atoms || def.fail.argumentRequired("atoms");
            atomsDimNames = keyArgs.atomsDimNames || def.fail.argumentRequired("atomsDimNames");
            atomsBase = parent.atoms;
        } else {
            this.root = this;
            atomsDimNames = [];
            var linkParent = keyArgs.linkParent || null;
            if (linkParent) {
                owner = linkParent.owner;
                this.type = owner.type;
                datums = keyArgs.datums || def.fail.argumentRequired("datums");
                this._leafs = [];
                this._wherePred = keyArgs.where || null;
                atomsBase = linkParent.atoms;
                index = def.get(keyArgs, "index", null);
                data_addLinkChild.call(linkParent, this, index);
            } else {
                owner = this;
                atomsBase = {};
                keyArgs.labelSep && (this.labelSep = keyArgs.labelSep);
                keyArgs.keySep && (this.keySep = keyArgs.keySep);
                this.type = keyArgs.type || def.fail.argumentRequired("type");
                this._selectedNotNullDatums = new def.Map();
            }
        }
        datums && data_setDatums.call(this, datums);
        this.owner = owner;
        this._atomsBase = atomsBase;
        this._dimensions = {};
        this._dimensionsList = [];
        this.type.dimensionsList().forEach(this._initDimension, this);
        this.base(owner, atoms, atomsDimNames, atomsBase, !0);
        pv.Dom.Node.call(this);
        if (parent) {
            index = def.get(keyArgs, "index", null);
            data_addChild.call(parent, this, index);
            this.absLabel = parent.absLabel ? def.string.join(owner.labelSep, parent.absLabel, this.label) : this.label;
            this.absKey = parent.absKey ? def.string.join(owner.keySep, parent.absKey, this.key) : this.key;
        } else {
            this.absLabel = this.label;
            this.absKey = this.key;
        }
    }).add(pv.Dom.Node).add({
        parent: null,
        linkParent: null,
        _dimensions: null,
        _dimensionsList: null,
        _freeDimensionNames: null,
        _linkChildren: null,
        _leafs: null,
        _childrenByKey: null,
        _visibleNotNullDatums: null,
        _selectedNotNullDatums: null,
        _groupByCache: null,
        _sumAbsCache: null,
        treeHeight: null,
        _groupOper: null,
        _wherePred: null,
        _groupSpec: null,
        _groupLevel: null,
        _datums: null,
        _datumsById: null,
        _datumsByKey: null,
        depth: 0,
        label: "",
        absLabel: "",
        _disposed: !1,
        _isFlattenGroup: !1,
        _isDegenerateFlattenGroup: !1,
        _initDimension: function(dimType) {
            var dim = new pvc.data.Dimension(this, dimType);
            this._dimensions[dimType.name] = dim;
            this._dimensionsList.push(dim);
        },
        dimensions: function(name, keyArgs) {
            if (null == name) return this._dimensions;
            var dim = def.getOwn(this._dimensions, name);
            if (!dim && def.get(keyArgs, "assertExists", !0)) throw def.error.argumentInvalid("name", "Undefined dimension '{0}'.", [ name ]);
            return dim;
        },
        dimensionsList: function() {
            return this._dimensionsList;
        },
        freeDimensionsNames: function() {
            var free = this._freeDimensionNames;
            free || (this._freeDimensionNames = free = this.type.dimensionsNames().filter(function(dimName) {
                var atom = this.atoms[dimName];
                return !(atom instanceof pvc.data.Atom) || null == atom.value;
            }, this));
            return free;
        },
        isOwner: function() {
            return this.owner === this;
        },
        children: function() {
            var cs = this.childNodes;
            return cs.length ? def.query(cs) : def.query();
        },
        child: function(key) {
            return def.getOwn(this._childrenByKey, key, null);
        },
        childCount: function() {
            return this.childNodes.length;
        },
        leafs: function() {
            return def.query(this._leafs);
        },
        count: function() {
            return this._datums.length;
        },
        firstDatum: function() {
            return this._datums.length ? this._datums[0] : null;
        },
        firstAtoms: function() {
            return (this.firstDatum() || this).atoms;
        },
        singleDatum: function() {
            var datums = this._datums;
            return 1 === datums.length ? datums[0] : null;
        },
        dispose: function() {
            var me = this;
            if (!me._disposed) {
                data_disposeChildLists.call(me);
                var v;
                (v = me._selectedNotNullDatums) && v.clear();
                me._visibleNotNullDatums.clear();
                v = me._dimensionsList;
                for (var i = 0, L = v.length; L > i; i++) v[i].dispose();
                me._dimensions = null;
                me._dimensionsLIst = null;
                if (v = me.parent) {
                    v.removeChild(me);
                    me.parent = null;
                }
                (v = me.linkParent) && data_removeLinkChild.call(v, me);
                me._disposed = !0;
            }
        },
        disposeChildren: function() {
            data_disposeChildLists.call(this);
        }
    });
    pvc.data.Data.add({
        selectedCount: function() {
            return this.isOwner() ? this._selectedNotNullDatums.count : this.datums(null, {
                selected: !0
            }).count();
        },
        selectedDatums: function() {
            return this.isOwner() ? this._selectedNotNullDatums.values() : this.datums(null, {
                selected: !0
            }).array();
        },
        selectedDatumMap: function() {
            if (!this.isOwner()) {
                var datums = this.datums(null, {
                    selected: !0
                }).object({
                    name: def.propGet("id")
                });
                return new def.Set(datums);
            }
            return this._selectedNotNullDatums.clone();
        },
        visibleCount: function() {
            return this._visibleNotNullDatums.count;
        },
        replaceSelected: function(datums) {
            def.array.is(datums) || (datums = datums.array());
            var alreadySelectedById = def.query(datums).where(datum_isSelected).object({
                name: complex_id
            }), changed = this.owner.clearSelected(function(datum) {
                return !def.hasOwn(alreadySelectedById, datum.id);
            });
            changed |= pvc.data.Data.setSelected(datums, !0);
            return changed;
        },
        clearSelected: function(funFilter) {
            if (this.owner !== this) return this.owner.clearSelected(funFilter);
            if (!this._selectedNotNullDatums.count) return !1;
            var changed;
            if (funFilter) {
                changed = !1;
                this._selectedNotNullDatums.values().filter(funFilter).forEach(function(datum) {
                    changed = !0;
                    datum_deselect.call(datum);
                    this._selectedNotNullDatums.rem(datum.id);
                }, this);
            } else {
                changed = !0;
                this._selectedNotNullDatums.values().forEach(function(datum) {
                    datum_deselect.call(datum);
                });
                this._selectedNotNullDatums.clear();
            }
            return changed;
        }
    });
    pvc.data.Data.setSelected = function(datums, selected) {
        var anyChanged = 0;
        datums && def.query(datums).each(function(datum) {
            anyChanged |= datum.setSelected(selected);
        });
        return !!anyChanged;
    };
    pvc.data.Data.toggleSelected = function(datums, any) {
        def.array.isLike(datums) || (datums = def.query(datums).array());
        var q = def.query(datums), on = any ? q.any(datum_isSelected) : q.all(datum_isNullOrSelected);
        return this.setSelected(datums, !on);
    };
    pvc.data.Data.setVisible = function(datums, visible) {
        var anyChanged = 0;
        datums && def.query(datums).each(function(datum) {
            anyChanged |= datum.setVisible(visible);
        });
        return !!anyChanged;
    };
    pvc.data.Data.toggleVisible = function(datums) {
        def.array.isLike(datums) || (datums = def.query(datums).array());
        var allVisible = def.query(datums).all(def.propGet("isVisible"));
        return pvc.data.Data.setVisible(datums, !allVisible);
    };
    def.space("pvc.data").FlatteningMode = def.set(def.makeEnum([ "DfsPre", "DfsPost" ]), "None", 0);
    def.type("pvc.data.GroupingSpec").init(function(levelSpecs, type, ka) {
        this.type = type || null;
        var ids = [];
        this.hasCompositeLevels = !1;
        var dimNames = [];
        this.levels = def.query(levelSpecs || void 0).where(function(levelSpec) {
            return levelSpec.dimensions.length > 0;
        }).select(function(levelSpec) {
            ids.push(levelSpec.id);
            def.array.append(dimNames, levelSpec.dimensionNames());
            !this.hasCompositeLevels && levelSpec.dimensions.length > 1 && (this.hasCompositeLevels = !0);
            levelSpec._setAccDimNames(dimNames.slice(0));
            return levelSpec;
        }, this).array();
        this._dimNames = dimNames;
        this.depth = this.levels.length;
        this.isSingleLevel = 1 === this.depth;
        this.isSingleDimension = this.isSingleLevel && !this.hasCompositeLevels;
        this.firstDimension = this.depth > 0 ? this.levels[0].dimensions[0] : null;
        this.rootLabel = def.get(ka, "rootLabel") || "";
        this.flatteningMode = def.get(ka, "flatteningMode") || pvc.data.FlatteningMode.None;
        this._cacheKey = this._calcCacheKey();
        this.id = this._cacheKey + "##" + ids.join("||");
    }).add({
        _calcCacheKey: function(ka) {
            return [ def.get(ka, "flatteningMode") || this.flatteningMode, def.get(ka, "reverse") || "false", def.get(ka, "isSingleLevel") || this.isSingleLevel, def.get(ka, "rootLabel") || this.rootLabel ].join("#");
        },
        bind: function(type) {
            this.type = type || def.fail.argumentRequired("type");
            this.levels.forEach(function(levelSpec) {
                levelSpec.bind(type);
            });
        },
        dimensions: function() {
            return def.query(this.levels).prop("dimensions").selectMany();
        },
        dimensionNames: function() {
            return this._dimNames;
        },
        view: function(complex) {
            return complex.view(this.dimensionNames());
        },
        isDiscrete: function() {
            var d;
            return !this.isSingleDimension || !!(d = this.firstDimension) && d.type.isDiscrete;
        },
        firstDimensionType: function() {
            var d = this.firstDimension;
            return d && d.type;
        },
        firstDimensionName: function() {
            var dt = this.firstDimensionType();
            return dt && dt.name;
        },
        firstDimensionValueType: function() {
            var dt = this.firstDimensionType();
            return dt && dt.valueType;
        },
        isNull: function() {
            return !this.levels.length;
        },
        ensure: function(ka) {
            var result;
            if (ka) {
                var cacheKey = this._calcCacheKey(ka);
                if (cacheKey !== this._cacheKey) {
                    var cache = def.lazy(this, "_groupingCache");
                    result = def.getOwn(cache, cacheKey);
                    result || (result = cache[cacheKey] = this._ensure(ka));
                }
            }
            return result || this;
        },
        _ensure: function(ka) {
            var me = this;
            if (def.get(ka, "isSingleLevel") && !me.isSingleLevel) return me._singleLevelGrouping(ka);
            if (def.get(ka, "reverse")) return me._reverse(ka);
            var flatteningMode = def.get(ka, "flatteningMode") || me.flatteningMode, rootLabel = def.get(ka, "rootLabel") || me.rootLabel;
            return flatteningMode !== me.flatteningMode || rootLabel !== me.rootLabel ? new pvc.data.GroupingSpec(me.levels, me.type, {
                flatteningMode: flatteningMode,
                rootLabel: rootLabel
            }) : me;
        },
        _singleLevelGrouping: function(ka) {
            var reverse = !!def.get(ka, "reverse"), dimSpecs = this.dimensions().select(function(dimSpec) {
                return reverse ? new pvc.data.GroupingDimensionSpec(dimSpec.name, !dimSpec.reverse, dimSpec.type.complexType) : dimSpec;
            }), levelSpec = new pvc.data.GroupingLevelSpec(dimSpecs, this.type);
            return new pvc.data.GroupingSpec([ levelSpec ], this.type, {
                flatteningMode: null,
                rootLabel: def.get(ka, "rootLabel") || this.rootLabel
            });
        },
        _reverse: function(ka) {
            var levelSpecs = def.query(this.levels).select(function(levelSpec) {
                var dimSpecs = def.query(levelSpec.dimensions).select(function(dimSpec) {
                    return new pvc.data.GroupingDimensionSpec(dimSpec.name, !dimSpec.reverse, dimSpec.type.complexType);
                });
                return new pvc.data.GroupingLevelSpec(dimSpecs, this.type);
            });
            return new pvc.data.GroupingSpec(levelSpecs, this.type, {
                flatteningMode: def.get(ka, "flatteningMode") || this.flatteningMode,
                rootLabel: def.get(ka, "rootLabel") || this.rootLabel
            });
        },
        toString: function() {
            return def.query(this.levels).select(function(level) {
                return "" + level;
            }).array().join(", ");
        }
    });
    def.type("pvc.data.GroupingLevelSpec").init(function(dimSpecs, type) {
        var ids = [], dimNames = [];
        this.dimensions = def.query(dimSpecs).select(function(dimSpec) {
            ids.push(dimSpec.id);
            dimNames.push(dimSpec.name);
            return dimSpec;
        }).array();
        this._dimNames = dimNames;
        this.dimensionsInDefOrder = this.dimensions.slice(0);
        type && this._sortDimensions(type);
        this.id = ids.join(",");
        this.depth = this.dimensions.length;
        var me = this;
        this.comparer = function(a, b) {
            return me.compare(a, b);
        };
    }).add({
        _sortDimensions: function(type) {
            type.sortDimensionNames(this.dimensionsInDefOrder, function(d) {
                return d.name;
            });
        },
        _setAccDimNames: function(accDimNames) {
            this._accDimNames = accDimNames;
        },
        accDimensionNames: function() {
            return this._accDimNames;
        },
        dimensionNames: function() {
            return this._dimNames;
        },
        bind: function(type) {
            this._sortDimensions(type);
            this.dimensions.forEach(function(dimSpec) {
                dimSpec.bind(type);
            });
        },
        compare: function(a, b) {
            for (var dims = this.dimensions, D = this.depth, i = 0; D > i; i++) {
                var result = dims[i].compareDatums(a, b);
                if (result) return result;
            }
            return 0;
        },
        key: function(datum) {
            for (var key = "", dimNames = this._dimNames, D = this.depth, keySep = datum.owner.keySep, datoms = datum.atoms, i = 0; D > i; i++) {
                var k = datoms[dimNames[i]].key;
                i ? key += keySep + k : key = k;
            }
            return key;
        },
        atomsInfo: function(datum) {
            for (var atoms = {}, dimNames = this._dimNames, D = this.depth, datoms = datum.atoms, i = (datum.owner.keySep, 
            0); D > i; i++) {
                var dimName = dimNames[i];
                atoms[dimName] = datoms[dimName];
            }
            return {
                atoms: atoms,
                dimNames: dimNames
            };
        },
        toString: function() {
            return def.query(this.dimensions).select(function(dimSpec) {
                return "" + dimSpec;
            }).array().join("|");
        }
    });
    def.type("pvc.data.GroupingDimensionSpec").init(function(name, reverse, type) {
        this.name = name;
        this.reverse = !!reverse;
        this.id = name + ":" + (reverse ? "0" : "1");
        type && this.bind(type);
    }).add({
        type: null,
        comparer: null,
        bind: function(type) {
            type || def.fail.argumentRequired("type");
            this.type = type.dimensions(this.name);
            this.comparer = this.type.atomComparer(this.reverse);
        },
        compareDatums: function(a, b) {
            if (this.type.isComparable) {
                var name = this.name;
                return this.comparer(a.atoms[name], b.atoms[name]);
            }
            return this.reverse ? b.id - a.id : a.id - b.id;
        },
        toString: function() {
            return this.name + (this.reverse ? " desc" : "");
        }
    });
    pvc.data.GroupingSpec.parse = function(specText, type) {
        if (!specText) return new pvc.data.GroupingSpec(null, type);
        var levels;
        def.array.is(specText) ? levels = specText : def.string.is(specText) && (levels = specText.split(/\s*,\s*/));
        var levelSpecs = def.query(levels).select(function(levelText) {
            var dimSpecs = groupSpec_parseGroupingLevel(levelText, type);
            return new pvc.data.GroupingLevelSpec(dimSpecs, type);
        });
        return new pvc.data.GroupingSpec(levelSpecs, type);
    };
    var groupSpec_matchDimSpec = /^\s*(.+?)(?:\s+(asc|desc))?\s*$/i;
    def.type("pvc.data.DataOper").init(function(linkParent) {
        linkParent || def.fail.argumentRequired("linkParent");
        this._linkParent = linkParent;
    }).add({
        key: null,
        execute: def.method({
            isAbstract: !0
        })
    });
    def.type("pvc.data.GroupingOper", pvc.data.DataOper).init(function(linkParent, groupingSpecs, keyArgs) {
        groupingSpecs || def.fail.argumentRequired("groupingSpecs");
        this.base(linkParent, keyArgs);
        this._where = def.get(keyArgs, "where");
        this._visible = def.get(keyArgs, "visible", null);
        this._selected = def.get(keyArgs, "selected", null);
        var isNull = this._isNull = def.get(keyArgs, "isNull", null);
        this._postFilter = null != isNull ? function(d) {
            return d.isNull === isNull;
        } : null;
        var hasKey = null == this._selected, whereKey = "";
        if (this._where) {
            whereKey = def.get(keyArgs, "whereKey");
            if (!whereKey) if (keyArgs && null !== whereKey) {
                whereKey = "" + def.nextId("dataOperWhereKey");
                keyArgs.whereKey = whereKey;
            } else hasKey = !1;
        }
        var ids = [];
        this._groupSpecs = def.array.as(groupingSpecs).map(function(groupSpec) {
            if (groupSpec instanceof pvc.data.GroupingSpec) {
                if (groupSpec.type !== linkParent.type) throw def.error.argumentInvalid("groupingSpecText", "Invalid associated complex type.");
            } else groupSpec = pvc.data.GroupingSpec.parse(groupSpec, linkParent.type);
            ids.push(groupSpec.id);
            return groupSpec;
        });
        hasKey && (this.key = ids.join("!!") + "||visible:" + this._visible + "||isNull:" + this._isNull + "||where:" + whereKey);
    }).add({
        execute: function() {
            var datumsQuery = data_whereState(def.query(this._linkParent._datums), {
                visible: this._visible,
                selected: this._selected,
                where: this._where
            }), rootNode = this._group(datumsQuery);
            return this._generateData(rootNode, null, this._linkParent);
        },
        executeAdd: function(rootData, datums) {
            var datumsQuery = data_whereState(def.query(datums), {
                visible: this._visible,
                selected: this._selected,
                where: this._where
            }), rootNode = this._group(datumsQuery);
            this._generateData(rootNode, null, this._linkParent, rootData);
            return rootNode.datums;
        },
        _group: function(datumsQuery) {
            var rootNode = {
                isRoot: !0,
                treeHeight: def.query(this._groupSpecs).select(function(spec) {
                    var levelCount = spec.levels.length;
                    return levelCount ? spec.flatteningMode ? 1 : levelCount : 0;
                }).reduce(def.add, 0),
                datums: []
            };
            rootNode.treeHeight > 0 && this._groupSpecRecursive(rootNode, def.query(datumsQuery).array(), 0);
            return rootNode;
        },
        _groupSpecRecursive: function(groupParentNode, groupDatums, groupIndex) {
            var group = this._groupSpecs[groupIndex];
            group.flatteningMode ? this._groupSpecRecursiveFlattened(groupParentNode, groupDatums, group, groupIndex) : this._groupSpecRecursiveNormal(groupParentNode, groupDatums, group, groupIndex);
        },
        _groupSpecRecursiveNormal: function(groupParentNode, groupDatums, group, groupIndex) {
            function groupLevelRecursive(levelParentNode, levelDatums, levelIndex) {
                var level = levels[levelIndex], isLastLevel = levelIndex === L - 1, isLastLevelOfLastGroupSpec = isLastGroup && isLastLevel;
                levelParentNode.groupSpec = group;
                levelParentNode.groupLevelSpec = level;
                for (var childNodes = levelParentNode.children = this._groupLevelDatums(level, levelParentNode, levelDatums, !1), i = 0, C = childNodes.length; C > i; i++) {
                    var childNode = childNodes[i];
                    if (!isLastLevelOfLastGroupSpec) {
                        var childDatums = childNode.datums;
                        childNode.datums = [];
                        isLastLevel ? this._groupSpecRecursive(childNode, childDatums, groupIndex + 1) : groupLevelRecursive.call(this, childNode, childDatums, levelIndex + 1);
                    }
                    def.array.append(levelParentNode.datums, childNode.datums);
                }
            }
            var levels = group.levels, L = levels.length, isLastGroup = groupIndex === this._groupSpecs.length - 1;
            groupParentNode.isRoot && (groupParentNode.label = group.rootLabel);
            groupLevelRecursive.call(this, groupParentNode, groupDatums, 0);
        },
        _groupSpecRecursiveFlattened: function(realGroupParentNode, groupDatums, group, groupIndex) {
            function groupLevelRecursive(levelParentNode, levelDatums, levelIndex) {
                for (var level = levels[levelIndex], isLastLevel = levelIndex === L - 1, isLastLevelOfLastGroupSpec = isLastGroup && isLastLevel, childNodes = this._groupLevelDatums(level, levelParentNode, levelDatums, !0), levelParentNodeDatums = isLastGroup ? levelParentNode.datums : [], i = 0, C = childNodes.length; C > i; i++) {
                    var childNode = childNodes[i], childDatums = childNode.datums;
                    def.array.lazy(levelParentNode, "_children").push(childNode);
                    if (def.hasOwn(flatChildrenByKey, childNode.key)) def.array.append(levelParentNodeDatums, childDatums); else {
                        var specParentChildIndex = flatChildren.length;
                        if (!isPostOrder) {
                            addFlatChild(childNode);
                            levelParentNode.isFlattenGroup = !0;
                        }
                        if (!isLastLevelOfLastGroupSpec) {
                            childNode.datums = [];
                            isLastLevel ? this._groupSpecRecursive(childNode, childDatums, groupIndex + 1) : groupLevelRecursive.call(this, childNode, childDatums, levelIndex + 1);
                        }
                        def.array.append(levelParentNodeDatums, childNode.datums);
                        if (isPostOrder) {
                            if (def.hasOwn(flatChildrenByKey, childNode.key)) {
                                childNode.isFlattenGroup || def.assert("Must be a parent for duplicate keys to exist.");
                                if (1 === childNode._children.length) {
                                    flatChildren.splice(specParentChildIndex, flatChildren.length - specParentChildIndex);
                                    childNode.isDegenerateFlattenGroup = !0;
                                }
                            }
                            addFlatChild(childNode);
                            levelParentNode.isFlattenGroup = !0;
                        }
                    }
                }
                isLastGroup || this._groupSpecRecursive(levelParentNode, levelParentNodeDatums, groupIndex + 1);
            }
            var isPostOrder = group.flatteningMode === pvc.data.FlatteningMode.DfsPost, levels = group.levels, L = levels.length, isLastGroup = groupIndex === this._groupSpecs.length - 1, flatChildren = [], flatChildrenByKey = {};
            realGroupParentNode.children = flatChildren;
            realGroupParentNode.childrenByKey = flatChildrenByKey;
            var groupParentNode = {
                key: "",
                absKey: "",
                atoms: {},
                datums: [],
                label: group.rootLabel,
                dimNames: []
            }, addFlatChild = function(child) {
                flatChildren.push(child);
                flatChildrenByKey[child.key] = child;
            };
            isPostOrder || addFlatChild(groupParentNode);
            groupLevelRecursive.call(this, groupParentNode, groupDatums, 0);
            isPostOrder && addFlatChild(groupParentNode);
            realGroupParentNode.datums = groupParentNode.datums;
        },
        _groupLevelDatums: function(level, levelParentNode, levelDatums, doFlatten) {
            for (var keySep, childNodeList = [], childNodeMap = {}, postFilter = this._postFilter, datumComparer = level.comparer, nodeComparer = function(na, nb) {
                return datumComparer(na.firstDatum, nb.firstDatum);
            }, i = 0, L = levelDatums.length; L > i; i++) {
                var datum = levelDatums[i], key = level.key(datum), childNode = def.hasOwnProp.call(childNodeMap, key) && childNodeMap[key];
                if (childNode) (!postFilter || postFilter(datum)) && childNode.datums.push(datum); else {
                    childNode = level.atomsInfo(datum);
                    childNode.key = key;
                    childNode.firstDatum = datum;
                    childNode.datums = !postFilter || postFilter(datum) ? [ datum ] : [];
                    if (doFlatten) {
                        keySep || (keySep = datum.owner.keySep);
                        this._onNewChildNodeFlattened(key, keySep, childNode, level, levelParentNode);
                    }
                    def.array.insert(childNodeList, childNode, nodeComparer);
                    childNodeMap[key] = childNode;
                }
            }
            if (postFilter) {
                i = childNodeList.length;
                for (;i--; ) childNodeList[i].datums.length || childNodeList.splice(i, 1);
            }
            return childNodeList;
        },
        _onNewChildNodeFlattened: function(key, keySep, childNode, level, levelParentNode) {
            def.copy(childNode.atoms, levelParentNode.atoms);
            childNode.dimNames = level.accDimensionNames();
            if (levelParentNode.dimNames.length) {
                var absKey = levelParentNode.absKey + keySep + key;
                childNode.absKey = absKey;
                childNode.key = pvc.data.Complex.rightTrimKeySep(absKey, keySep);
            } else childNode.absKey = key;
        },
        _generateData: function(node, parentNode, parentData, rootData) {
            var data, isNew;
            if (node.isRoot) if (rootData) {
                data = rootData;
                data_addDatumsLocal.call(data, node.datums);
            } else {
                isNew = !0;
                data = new pvc.data.Data({
                    linkParent: parentData,
                    datums: node.datums
                });
                data.treeHeight = node.treeHeight;
                data._groupOper = this;
            } else {
                if (rootData) {
                    data = parentData.child(node.key);
                    data && data_addDatumsSimple.call(data, node.datums);
                }
                if (!data) {
                    isNew = !0;
                    var index, siblings;
                    rootData && (siblings = parentData.childNodes) && (index = ~def.array.binarySearch(siblings, node.datums[0], parentNode.groupLevelSpec.comparer));
                    data = new pvc.data.Data({
                        parent: parentData,
                        atoms: node.atoms,
                        atomsDimNames: node.dimNames,
                        datums: node.datums,
                        index: index
                    });
                }
            }
            if (isNew) {
                if (node.isFlattenGroup) {
                    data._isFlattenGroup = !0;
                    data._isDegenerateFlattenGroup = !!node.isDegenerateFlattenGroup;
                }
                var label = node.label;
                if (label) {
                    data.label += label;
                    data.absLabel += label;
                }
            }
            var childNodes = node.children, L = childNodes && childNodes.length;
            if (L) {
                if (isNew) {
                    data._groupSpec = node.groupSpec;
                    data._groupLevelSpec = node.groupLevelSpec;
                }
                for (var i = 0; L > i; i++) this._generateData(childNodes[i], node, data, rootData);
            } else if (isNew && !node.isRoot) {
                var leafs = data.root._leafs;
                data.leafIndex = leafs.length;
                leafs.push(data);
            }
            return data;
        }
    });
    def.type("pvc.data.LinearInterpolationOper").init(function(baseData, partData, visibleData, catRole, serRole, valRole, stretchEnds) {
        this._newDatums = [];
        this._data = visibleData;
        var qAllCatDatas = catRole.flatten(baseData).children(), serDatas1 = serRole.isBound() ? serRole.flatten(partData, {
            visible: !0,
            isNull: !1
        }).children().array() : [ null ];
        this._isCatDiscrete = catRole.grouping.isDiscrete();
        this._firstCatDim = this._isCatDiscrete ? null : baseData.owner.dimensions(catRole.firstDimensionName());
        this._stretchEnds = stretchEnds;
        var valDim = this._valDim = baseData.owner.dimensions(valRole.firstDimensionName()), visibleKeyArgs = {
            visible: !0,
            zeroIfNone: !1
        };
        this._catInfos = qAllCatDatas.select(function(allCatData, catIndex) {
            var catData = visibleData.child(allCatData.key), catInfo = {
                data: catData || allCatData,
                value: allCatData.value,
                isInterpolated: !1,
                serInfos: null,
                index: catIndex
            };
            catInfo.serInfos = serDatas1.map(function(serData1) {
                var group = catData;
                group && serData1 && (group = group.child(serData1.key));
                var value = group ? group.dimensions(valDim.name).value(visibleKeyArgs) : null;
                return {
                    data: serData1,
                    group: group,
                    value: value,
                    isNull: null == value,
                    catInfo: catInfo
                };
            });
            return catInfo;
        }).array();
        this._serCount = serDatas1.length;
        this._serStates = def.range(0, this._serCount).select(function(serIndex) {
            return new pvc.data.LinearInterpolationOperSeriesState(this, serIndex);
        }, this).array();
    }).add({
        interpolate: function() {
            for (var catInfo; catInfo = this._catInfos.shift(); ) catInfo.serInfos.forEach(this._visitSeries, this);
            var newDatums = this._newDatums;
            newDatums.length && this._data.owner.add(newDatums);
        },
        _visitSeries: function(catSerInfo, serIndex) {
            this._serStates[serIndex].visit(catSerInfo);
        },
        nextUnprocessedNonNullCategOfSeries: function(serIndex) {
            for (var catIndex = 0, catCount = this._catInfos.length; catCount > catIndex; ) {
                var catInfo = this._catInfos[catIndex++], catSerInfo = catInfo.serInfos[serIndex];
                if (!catSerInfo.isNull) return catSerInfo;
            }
        }
    });
    def.type("pvc.data.LinearInterpolationOperSeriesState").init(function(interpolation, serIndex) {
        this.interpolation = interpolation;
        this.index = serIndex;
        this._lastNonNull(null);
    }).add({
        visit: function(catSeriesInfo) {
            catSeriesInfo.isNull ? this._interpolate(catSeriesInfo) : this._lastNonNull(catSeriesInfo);
        },
        _lastNonNull: function(catSerInfo) {
            if (arguments.length) {
                this.__lastNonNull = catSerInfo;
                this.__nextNonNull = void 0;
            }
            return this.__lastNonNull;
        },
        _nextNonNull: function() {
            return this.__nextNonNull;
        },
        _initInterpData: function() {
            if (void 0 === this.__nextNonNull) {
                var last = this.__lastNonNull, next = this.__nextNonNull = this.interpolation.nextUnprocessedNonNullCategOfSeries(this.index) || null;
                if (next && last) {
                    var fromValue = last.value, toValue = next.value, deltaValue = toValue - fromValue;
                    if (this.interpolation._isCatDiscrete) {
                        var stepCount = next.catInfo.index - last.catInfo.index;
                        stepCount >= 2 || def.assert("Must have at least one interpolation point.");
                        this._stepValue = deltaValue / stepCount;
                        this._middleIndex = ~~(stepCount / 2);
                        var dotCount = stepCount - 1;
                        this._isOdd = dotCount % 2 > 0;
                    } else {
                        var fromCat = +last.catInfo.value, toCat = +next.catInfo.value, deltaCat = toCat - fromCat;
                        this._steep = deltaValue / deltaCat;
                        this._middleCat = (toCat + fromCat) / 2;
                    }
                }
            }
        },
        _interpolate: function(catSerInfo) {
            this._initInterpData();
            var next = this.__nextNonNull, last = this.__lastNonNull, one = next || last;
            if (one) {
                var value, group, interpolation = this.interpolation, catInfo = catSerInfo.catInfo;
                if (next && last) if (interpolation._isCatDiscrete) {
                    var groupIndex = catInfo.index - last.catInfo.index;
                    value = last.value + this._stepValue * groupIndex;
                    group = this._isOdd ? groupIndex < this._middleIndex ? last.group : next.group : groupIndex <= this._middleIndex ? last.group : next.group;
                } else {
                    var cat = +catInfo.value, lastCat = +last.catInfo.value;
                    value = last.value + this._steep * (cat - lastCat);
                    group = cat < this._middleCat ? last.group : next.group;
                } else {
                    if (!interpolation._stretchEnds) return;
                    value = one.value;
                    group = one.group;
                }
                var atoms = Object.create(group._datums[0].atoms);
                def.copyOwn(atoms, catInfo.data.atoms);
                var valueAtom = interpolation._valDim.intern(value, !0);
                atoms[valueAtom.dimension.name] = valueAtom;
                interpolation._newDatums.push(new pvc.data.InterpolationDatum(group.owner, atoms, "linear"));
            }
        }
    });
    def.type("pvc.data.ZeroInterpolationOper").init(function(baseData, partData, visibleData, catRole, serRole, valRole, stretchEnds) {
        this._newDatums = [];
        this._data = visibleData;
        var qAllCatDatas = catRole.flatten(baseData).children(), serDatas1 = serRole.isBound() ? serRole.flatten(partData, {
            visible: !0,
            isNull: !1
        }).children().array() : [ null ];
        this._isCatDiscrete = catRole.grouping.isDiscrete();
        this._firstCatDim = this._isCatDiscrete ? null : baseData.owner.dimensions(catRole.firstDimensionName());
        this._stretchEnds = stretchEnds;
        var valDim = this._valDim = baseData.owner.dimensions(valRole.firstDimensionName()), visibleKeyArgs = {
            visible: !0,
            zeroIfNone: !1
        };
        this._catInfos = qAllCatDatas.select(function(allCatData, catIndex) {
            var catData = visibleData.child(allCatData.key), catInfo = {
                data: catData || allCatData,
                value: allCatData.value,
                isInterpolated: !1,
                serInfos: null,
                index: catIndex
            };
            catInfo.serInfos = serDatas1.map(function(serData1) {
                var group = catData;
                group && serData1 && (group = group.child(serData1.key));
                var value = group ? group.dimensions(valDim.name).value(visibleKeyArgs) : null;
                return {
                    data: serData1,
                    group: group,
                    value: value,
                    isNull: null == value,
                    catInfo: catInfo
                };
            });
            return catInfo;
        }).array();
        this._serCount = serDatas1.length;
        this._serStates = def.range(0, this._serCount).select(function(serIndex) {
            return new pvc.data.ZeroInterpolationOperSeriesState(this, serIndex);
        }, this).array();
    }).add({
        interpolate: function() {
            for (var catInfo; catInfo = this._catInfos.shift(); ) catInfo.serInfos.forEach(this._visitSeries, this);
            var newDatums = this._newDatums;
            newDatums.length && this._data.owner.add(newDatums);
        },
        _visitSeries: function(catSerInfo, serIndex) {
            this._serStates[serIndex].visit(catSerInfo);
        },
        nextUnprocessedNonNullCategOfSeries: function(serIndex) {
            for (var catIndex = 0, catCount = this._catInfos.length; catCount > catIndex; ) {
                var catInfo = this._catInfos[catIndex++], catSerInfo = catInfo.serInfos[serIndex];
                if (!catSerInfo.isNull) return catSerInfo;
            }
        }
    });
    def.type("pvc.data.ZeroInterpolationOperSeriesState").init(function(interpolation, serIndex) {
        this.interpolation = interpolation;
        this.index = serIndex;
        this._lastNonNull(null);
    }).add({
        visit: function(catSeriesInfo) {
            catSeriesInfo.isNull ? this._interpolate(catSeriesInfo) : this._lastNonNull(catSeriesInfo);
        },
        _lastNonNull: function(catSerInfo) {
            if (arguments.length) {
                this.__lastNonNull = catSerInfo;
                this.__nextNonNull = void 0;
            }
            return this.__lastNonNull;
        },
        _nextNonNull: function() {
            return this.__nextNonNull;
        },
        _initInterpData: function() {
            if (void 0 === this.__nextNonNull) {
                var last = this.__lastNonNull, next = this.__nextNonNull = this.interpolation.nextUnprocessedNonNullCategOfSeries(this.index) || null;
                if (next && last) {
                    {
                        last.value, next.value;
                    }
                    if (this.interpolation._isCatDiscrete) {
                        var stepCount = next.catInfo.index - last.catInfo.index;
                        stepCount >= 2 || def.assert("Must have at least one interpolation point.");
                        this._middleIndex = ~~(stepCount / 2);
                        var dotCount = stepCount - 1;
                        this._isOdd = dotCount % 2 > 0;
                    } else {
                        var fromCat = +last.catInfo.value, toCat = +next.catInfo.value;
                        this._middleCat = (toCat + fromCat) / 2;
                    }
                }
            }
        },
        _interpolate: function(catSerInfo) {
            this._initInterpData();
            var next = this.__nextNonNull, last = this.__lastNonNull, one = next || last;
            if (one) {
                var group, interpolation = this.interpolation, catInfo = catSerInfo.catInfo;
                if (next && last) if (interpolation._isCatDiscrete) {
                    var groupIndex = catInfo.index - last.catInfo.index;
                    group = this._isOdd ? groupIndex < this._middleIndex ? last.group : next.group : groupIndex <= this._middleIndex ? last.group : next.group;
                } else {
                    var cat = +catInfo.value;
                    group = cat < this._middleCat ? last.group : next.group;
                } else {
                    if (!interpolation._stretchEnds) return;
                    group = one.group;
                }
                var atoms = Object.create(group._datums[0].atoms);
                def.copyOwn(atoms, catInfo.data.atoms);
                var zeroAtom = interpolation._zeroAtom || (interpolation._zeroAtom = interpolation._valDim.intern(0, !0));
                atoms[zeroAtom.dimension.name] = zeroAtom;
                interpolation._newDatums.push(new pvc.data.InterpolationDatum(group.owner, atoms, "zero"));
            }
        }
    });
    pvc.data.Data.add({
        load: function(atomz, keyArgs) {
            data_assertIsOwner.call(this);
            var whereFun = def.get(keyArgs, "where"), isNullFun = def.get(keyArgs, "isNull"), datums = def.query(atomz).select(function(atoms) {
                var datum = new pvc.data.Datum(this, atoms);
                isNullFun && isNullFun(datum) && (datum.isNull = !0);
                return whereFun && !whereFun(datum) ? null : datum;
            }, this);
            data_setDatums.call(this, datums, {
                isAdditive: !1,
                doAtomGC: !0
            });
        },
        clearVirtuals: function() {
            var datums = this._datums;
            if (datums) {
                this._sumAbsCache = null;
                for (var removed, visDatums = this._visibleNotNullDatums, selDatums = this._selectedNotNullDatums, datumsByKey = this._datumsByKey, datumsById = this._datumsById, i = 0, L = datums.length; L > i; ) {
                    var datum = datums[i];
                    if (datum.isVirtual) {
                        var id = datum.id, key = datum.key;
                        datums.splice(i, 1);
                        delete datumsById[id];
                        delete datumsByKey[key];
                        selDatums && datum.isSelected && selDatums.rem(id);
                        datum.isVisible && visDatums.rem(id);
                        L--;
                        removed = !0;
                    } else i++;
                }
                if (removed) {
                    if (!datums.length && this.parent) {
                        this.dispose();
                        return;
                    }
                    var children = this.childNodes;
                    if (children) {
                        i = 0;
                        L = children.length;
                        for (;L > i; ) {
                            var childData = children[i];
                            childData.clearVirtuals();
                            childData.parent ? i++ : L--;
                        }
                    }
                    this._linkChildren && this._linkChildren.forEach(function(linkChildData) {
                        linkChildData.clearVirtuals();
                    });
                }
            }
            def.eachOwn(this._dimensions, function(dim) {
                dim_uninternVirtualAtoms.call(dim);
            });
        },
        add: function(datums) {
            data_assertIsOwner.call(this);
            data_setDatums.call(this, datums, {
                isAdditive: !0,
                doAtomGC: !0
            });
        },
        groupBy: function(groupingSpecText, keyArgs) {
            var groupByCache, data, groupOper = new pvc.data.GroupingOper(this, groupingSpecText, keyArgs), cacheKey = groupOper.key;
            if (cacheKey) {
                groupByCache = this._groupByCache;
                data = groupByCache && groupByCache[cacheKey];
            }
            if (data) pvc.debug >= 7 && pvc.log("[GroupBy] Cache key hit '" + cacheKey + "'"); else {
                pvc.debug >= 7 && pvc.log("[GroupBy] " + (cacheKey ? "Cache key not found: '" + cacheKey + "'" : "No Cache key"));
                data = groupOper.execute();
                cacheKey && ((groupByCache || (this._groupByCache = {}))[cacheKey] = data);
            }
            return data;
        },
        where: function(whereSpec, keyArgs) {
            var datums;
            if (whereSpec) {
                whereSpec = data_processWhereSpec.call(this, whereSpec, keyArgs);
                datums = data_where.call(this, whereSpec, keyArgs);
            } else {
                if (!keyArgs) return def.query(this._datums);
                datums = data_whereState(def.query(this._datums), keyArgs);
            }
            var where = data_wherePredicate(whereSpec, keyArgs);
            return new pvc.data.Data({
                linkParent: this,
                datums: datums,
                where: where
            });
        },
        datums: function(whereSpec, keyArgs) {
            if (!whereSpec) return keyArgs ? data_whereState(def.query(this._datums), keyArgs) : def.query(this._datums);
            whereSpec = data_processWhereSpec.call(this, whereSpec, keyArgs);
            return data_where.call(this, whereSpec, keyArgs);
        },
        datum: function(whereSpec, keyArgs) {
            whereSpec || def.fail.argumentRequired("whereSpec");
            whereSpec = data_processWhereSpec.call(this, whereSpec, keyArgs);
            return data_where.call(this, whereSpec, keyArgs).first() || null;
        },
        dimensionsSumAbs: function(dimName, keyArgs) {
            var key = dimName + ":" + dim_buildDatumsFilterKey(keyArgs), sum = def.getOwn(this._sumAbsCache, key);
            if (null == sum) {
                sum = this.children().where(function(childData) {
                    return !childData._isFlattenGroup || childData._isDegenerateFlattenGroup;
                }).select(function(childData) {
                    return childData.dimensions(dimName).valueAbs(keyArgs) || 0;
                }, this).reduce(def.add, 0);
                (this._sumAbsCache || (this._sumAbsCache = {}))[key] = sum;
            }
            return sum;
        }
    });
    pvc.data.Data.add({
        getInfo: function() {
            var out = [ "DATA SUMMARY", pvc.logSeparator, "  Dimension", pvc.logSeparator ];
            def.eachOwn(this.dimensions(), function(dimension, name) {
                var count = dimension.count(), type = dimension.type, features = [];
                features.push('"' + type.label + '"');
                features.push(type.valueTypeName);
                type.isComparable && features.push("comparable");
                type.isDiscrete || features.push("continuous");
                type.isHidden && features.push("hidden");
                out.push("  " + name + " (" + features.join(", ") + ") (" + count + ")\n	" + dimension.atoms().slice(0, 10).map(function(atom) {
                    return atom.label;
                }).join(", ") + (count > 10 ? "..." : ""));
            });
            return out.join("\n");
        },
        getValues: function() {
            return pv.range(0, this.getCategoriesSize()).map(function(categIndex) {
                return this._getValuesForCategoryIndex(categIndex);
            }, this);
        },
        _getDimensionValues: function(name) {
            return this.dimensions(name).atoms().map(function(atom) {
                return atom.value;
            });
        },
        _getDimensionVisibleValues: function(name) {
            return this.dimensions(name).atoms({
                visible: !0
            }).map(function(atom) {
                return atom.value;
            });
        },
        getSeries: function() {
            return this._getDimensionValues("series");
        },
        getVisibleSeriesIndexes: function() {
            return this.dimensions("series").indexes({
                visible: !0
            });
        },
        getVisibleCategoriesIndexes: function() {
            return this.dimensions("category").indexes({
                visible: !0
            });
        },
        getVisibleSeries: function() {
            return this._getDimensionVisibleValues("series");
        },
        getCategories: function() {
            return this._getDimensionValues("category");
        },
        getVisibleCategories: function() {
            return this._getDimensionVisibleValues("category");
        },
        _getValuesForCategoryIndex: function(categIdx) {
            var categAtom = this.dimensions("category").atoms()[categIdx], datumsBySeriesKey = this.datums({
                category: categAtom
            }).uniqueIndex(function(datum) {
                return datum.atoms.series.key;
            });
            return this.dimensions("series").atoms().map(function(atom) {
                var datum = def.getOwn(datumsBySeriesKey, atom.key);
                return datum ? datum.atoms.value.value : null;
            });
        },
        getSeriesSize: function() {
            var dim = this.dimensions("series", {
                assertExists: !1
            });
            return dim ? dim.count() : 0;
        },
        getCategoriesSize: function() {
            var dim = this.dimensions("category", {
                assertExists: !1
            });
            return dim ? dim.count() : 0;
        }
    });
    def.scope(function() {
        var I = def.makeEnum([ "Interactive", "ShowsActivity", "ShowsSelection", "ShowsTooltip", "Selectable", "Unselectable", "Hoverable", "Clickable", "DoubleClickable", "SelectableByClick", "SelectableByRubberband", "SelectableByFocusWindow", "Animatable" ]);
        I.ShowsInteraction = I.ShowsActivity | I.ShowsSelection;
        I.Actionable = I.Hoverable | I.Clickable | I.DoubleClickable | I.SelectableByClick;
        I.HandlesEvents = I.Actionable | I.ShowsTooltip;
        I.HandlesClickEvent = I.Clickable | I.SelectableByClick;
        def.type("pvc.visual.Interactive").addStatic(I).addStatic({
            ShowsAny: I.ShowsInteraction | I.ShowsTooltip,
            SelectableAny: I.Selectable | I.SelectableByClick | I.SelectableByRubberband | I.SelectableByFocusWindow
        }).add({
            _ibits: -1
        }).add(def.query(def.ownKeys(I)).object({
            name: def.firstLowerCase,
            value: function(p) {
                var mask = I[p];
                return function() {
                    return !!(this._ibits & mask);
                };
            }
        }));
    });
    def.type("pvc.visual.Scene").init(function(parent, keyArgs) {
        pvc.debug >= 4 && (this.id = def.nextId("scene"));
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
            if (first instanceof pvc.data.Data) {
                group = first;
                groups = dataSource;
                datum = group.firstDatum() || def.query(groups).select(function(g) {
                    return g.firstDatum();
                }).first(def.notNully);
            } else {
                first instanceof pvc.data.Datum || def.assert("not a datum");
                datum = first;
                datums = dataSource;
            }
            atoms = first.atoms;
            firstAtoms = datum && datum.atoms || first.atoms;
        } else atoms = firstAtoms = parent ? Object.create(parent.atoms) : {};
        this.atoms = atoms;
        this.firstAtoms = firstAtoms;
        groups && (this.groups = groups);
        group && (this.group = group);
        datums && (this._datums = datums);
        datum && (this.datum = datum);
        (!first || first.isNull) && (this.isNull = !0);
        this.vars = parent ? Object.create(parent.vars) : {};
    }).add(pv.Dom.Node).add(pvc.visual.Interactive).add({
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
        data: function() {
            var data = this.group;
            if (!data) {
                for (var scene = this; !data && (scene = scene.parent); ) data = scene.group;
                data || (data = this.panel.data);
            }
            return data;
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
                    if (item === root) return 0;
                    this.item = item;
                    return 1;
                }
                var next = this.item.nextSibling;
                if (next) {
                    this.item = next;
                    return 1;
                }
                for (var current = this.item; current !== root && (current = current.parentNode); ) if (next = current.nextSibling) {
                    this.item = getFirstLeafFrom(next);
                    return 1;
                }
                return 0;
            });
        },
        anyInteraction: function() {
            return !!this.root._active || this.anySelected();
        },
        isActive: !1,
        setActive: function(isActive) {
            isActive = !!isActive;
            this.isActive !== isActive && rootScene_setActive.call(this.root, this.isActive ? null : this);
        },
        clearActive: function() {
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
            return active && (seriesVar = active.vars.series) && seriesVar.value;
        },
        isActiveSeries: function() {
            if (this.isActive) return !0;
            var isActiveSeries = this.renderState.isActiveSeries;
            if (null == isActiveSeries) {
                var activeSeries;
                isActiveSeries = null != (activeSeries = this.activeSeries()) && activeSeries === this.vars.series.value;
                this.renderState.isActiveSeries = isActiveSeries;
            }
            return isActiveSeries;
        },
        isActiveDatum: function() {
            return this.isActive ? !0 : !1;
        },
        isActiveDescendantOrSelf: function() {
            return this.isActive ? !0 : def.lazy(this.renderState, "isActiveDescOrSelf", this._calcIsActiveDescOrSelf, this);
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
            var any = this.chart().data.owner.selectedCount() > 0, isSelected = any && this.datums().any(datum_isSelected);
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
                    datums && datums.length && (chart.options.ctrlSelectMode && def.get(ka, "replace", !0) ? chart.data.replaceSelected(datums) : pvc.data.Data.toggleSelected(datums));
                });
            }
        },
        isSelectedDescendantOrSelf: function() {
            return this.isSelected() ? !0 : def.lazy(this.renderState, "isSelectedDescOrSelf", this._calcIsSelectedDescOrSelf, this);
        },
        _calcIsSelectedDescOrSelf: function() {
            var child = this.firstChild;
            if (child) do if (child.isSelectedDescendantOrSelf()) return !0; while (child = child.nextSibling);
            return !1;
        },
        toggleVisible: function() {
            pvc.data.Data.toggleVisible(this.datums()) && this.chart().render(!0, !0, !1);
        }
    });
    pvc.visual.Scene.prototype.variable = function(name, impl) {
        var methods, proto = this;
        if (proto._vars && proto._vars[name]) void 0 !== impl && (methods = def.set({}, "_" + name + "EvalCore", def.fun.to(impl))); else {
            proto.hasOwnProperty("_vars") || (proto._vars = def.create(proto._vars));
            proto._vars[name] = !0;
            methods = {};
            var nameEval = "_" + name + "Eval";
            methods[name] = scene_createVarMainMethod(name, nameEval);
            var nameEvalCore = nameEval + "Core";
            def.hasOwn(proto, nameEval) || (methods[nameEval] = def.methodCaller(nameEvalCore));
            def.hasOwn(proto, nameEvalCore) || (methods[nameEvalCore] = def.fun.to(void 0 === impl ? null : impl));
        }
        methods && proto.constructor.add(methods);
        return proto;
    };
    var pvc_ValueLabelVar = pvc.visual.ValueLabelVar = function(value, label, rawValue, absLabel) {
        this.value = value;
        this.label = label;
        void 0 !== rawValue && (this.rawValue = rawValue);
        void 0 !== absLabel && (this.absLabel = absLabel);
    };
    def.set(pvc_ValueLabelVar.prototype, "rawValue", void 0, "absLabel", void 0, "setValue", function(v) {
        this.value = v;
        return this;
    }, "setLabel", function(v) {
        this.label = v;
        return this;
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
                    replace: !ev || !ev.ctrlKey
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
    def.space("pvc.visual").TraversalMode = def.makeEnum([ "Tree", "FlattenedSingleLevel", "FlattenDfsPre", "FlattenDfsPost" ]);
    def.type("pvc.visual.Role").init(function(name, keyArgs) {
        this.name = name;
        this.label = def.get(keyArgs, "label") || pvc.buildTitleFromName(name);
        this.index = def.get(keyArgs, "index") || 0;
        this.dimensionDefaults = def.get(keyArgs, "dimensionDefaults") || {};
        def.get(keyArgs, "isRequired", !1) && (this.isRequired = !0);
        def.get(keyArgs, "autoCreateDimension", !1) && (this.autoCreateDimension = !0);
        var defaultSourceRoleName = def.get(keyArgs, "defaultSourceRole");
        defaultSourceRoleName && (this.defaultSourceRoleName = defaultSourceRoleName);
        var defaultDimensionName = def.get(keyArgs, "defaultDimension");
        defaultDimensionName && (this.defaultDimensionName = defaultDimensionName);
        if (!defaultDimensionName && this.autoCreateDimension) throw def.error.argumentRequired("defaultDimension");
        var requireSingleDimension, requireIsDiscrete = def.get(keyArgs, "requireIsDiscrete");
        null != requireIsDiscrete && (requireIsDiscrete || (requireSingleDimension = !0));
        if (null != requireSingleDimension) {
            requireSingleDimension = def.get(keyArgs, "requireSingleDimension", !1);
            if (requireSingleDimension) {
                if (def.get(keyArgs, "isMeasure", !1)) {
                    this.isMeasure = !0;
                    def.get(keyArgs, "isPercent", !1) && (this.isPercent = !0);
                }
                var valueType = def.get(keyArgs, "valueType", null);
                if (valueType !== this.valueType) {
                    this.valueType = valueType;
                    this.dimensionDefaults.valueType = valueType;
                }
            }
        }
        requireSingleDimension !== this.requireSingleDimension && (this.requireSingleDimension = requireSingleDimension);
        if (requireIsDiscrete != this.requireIsDiscrete) {
            this.requireIsDiscrete = !!requireIsDiscrete;
            this.dimensionDefaults.isDiscrete = this.requireIsDiscrete;
        }
        var traversalMode = def.get(keyArgs, "traversalMode");
        null != traversalMode && traversalMode !== this.traversalMode && (this.traversalMode = traversalMode);
    }).add({
        isRequired: !1,
        requireSingleDimension: !1,
        valueType: null,
        requireIsDiscrete: null,
        isMeasure: !1,
        isPercent: !1,
        defaultSourceRoleName: null,
        defaultDimensionName: null,
        grouping: null,
        traversalMode: pvc.visual.TraversalMode.FlattenedSingleLevel,
        rootLabel: "",
        autoCreateDimension: !1,
        isReversed: !1,
        label: null,
        sourceRole: null,
        isDefaultSourceRole: !1,
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
        isDiscrete: function() {
            var g = this.grouping;
            return g && g.isDiscrete();
        },
        setSourceRole: function(sourceRole, isDefault) {
            this.sourceRole = sourceRole;
            this.isDefaultSourceRole = !!isDefault;
        },
        setIsReversed: function(isReversed) {
            isReversed ? this.isReversed = !0 : delete this.isReversed;
        },
        setTraversalMode: function(travMode) {
            var T = pvc.visual.TraversalMode;
            travMode = def.nullyTo(travMode, T.FlattenedSingleLevel);
            travMode !== this.traversalMode && (travMode === T.FlattenedSingleLevel ? delete this.traversalMode : this.traversalMode = travMode);
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
            var T = pvc.visual.TraversalMode, F = pvc.data.FlatteningMode;
            switch (this.traversalMode) {
              case T.FlattenDfsPre:
                return F.DfsPre;

              case T.FlattenDfsPost:
                return F.DfsPost;
            }
            return T.None;
        },
        select: function(data, keyArgs) {
            var grouping = this.grouping;
            if (grouping) {
                def.setUDefaults(keyArgs, "flatteningMode", pvc.data.FlatteningMode.None);
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
                    if (valueType && dimType.valueType !== valueType) throw def.error.operationInvalid("Role '{0}' cannot be bound to dimension '{1}'. \nIt only accepts dimensions of type '{2}' and not of type '{3}'.", [ this.name, dimType.name, pvc.data.DimensionType.valueTypeName(valueType), dimType.valueTypeName ]);
                    if (null != requireIsDiscrete && dimType.isDiscrete !== requireIsDiscrete) {
                        if (!requireIsDiscrete) throw def.error.operationInvalid("Role '{0}' cannot be bound to dimension '{1}'. \nIt only accepts {2} dimensions.", [ this.name, dimType.name, requireIsDiscrete ? "discrete" : "continuous" ]);
                        dimType._toDiscrete();
                    }
                }, this);
            }
            return groupingSpec;
        },
        _updateBind: function(groupingSpec) {
            this.grouping && this.grouping.dimensions().each(function(dimSpec) {
                dimSpec.type && dimType_removeVisualRole.call(dimSpec.type, this);
            }, this);
            this.grouping = groupingSpec;
            if (this.grouping) {
                this.grouping = this.grouping.ensure({
                    reverse: this.isReversed,
                    rootLabel: this.rootLabel
                });
                this.grouping.dimensions().each(function(dimSpec) {
                    dimType_addVisualRole.call(dimSpec.type, this);
                }, this);
            }
        }
    });
    def.type("pvc.visual.RoleVarHelper").init(function(rootScene, role, keyArgs) {
        var hasPercentSubVar = def.get(keyArgs, "hasPercentSubVar", !1), roleVarName = def.get(keyArgs, "roleVar"), g = this.grouping = role && role.grouping;
        if (g) {
            this.role = role;
            this.sourceRoleName = role.sourceRole && role.sourceRole.name;
            var panel = rootScene.panel();
            this.panel = panel;
            if (!g.isDiscrete()) {
                this.rootContDim = panel.data.owner.dimensions(g.firstDimensionName());
                hasPercentSubVar && (this.percentFormatter = panel.chart.options.percentValueFormat);
            }
        }
        if (!roleVarName) {
            if (!role) throw def.error.operationInvalid("Role is not defined, so the roleVar argument is required.");
            roleVarName = role.name;
        }
        if (!g) {
            var roleVar = rootScene.vars[roleVarName] = new pvc_ValueLabelVar(null, "");
            hasPercentSubVar && (roleVar.percent = new pvc_ValueLabelVar(null, ""));
        }
        this.roleVarName = roleVarName;
        rootScene["is" + def.firstUpperCase(roleVarName) + "Bound"] = !!g;
        def.get(keyArgs, "allowNestedVars") && (this.allowNestedVars = !0);
    }).add({
        allowNestedVars: !1,
        isBound: function() {
            return !!this.grouping;
        },
        onNewScene: function(scene, isLeaf) {
            if (this.grouping) {
                var roleVarName = this.roleVarName;
                if (this.allowNestedVars ? !def.hasOwnProp.call(scene.vars, roleVarName) : !scene.vars[roleVarName]) {
                    var sourceName = this.sourceRoleName;
                    if (sourceName) {
                        var sourceVar = def.getOwn(scene.vars, sourceName);
                        if (sourceVar) {
                            scene.vars[roleVarName] = sourceVar.clone();
                            return;
                        }
                    }
                    if (isLeaf) {
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
                            roleVar && this.percentFormatter && (roleVar.percent = null == roleVar.value ? new pvc_ValueLabelVar(null, "") : new pvc_ValueLabelVar(valuePct, this.percentFormatter.call(null, valuePct)));
                        } else {
                            var firstDatum = scene.datum;
                            if (firstDatum && !firstDatum.isNull) {
                                var view = this.grouping.view(firstDatum);
                                roleVar = pvc_ValueLabelVar.fromComplex(view);
                            }
                        }
                        if (!roleVar) {
                            roleVar = new pvc_ValueLabelVar(null, "");
                            this.percentFormatter && (roleVar.percent = new pvc_ValueLabelVar(null, ""));
                        }
                        scene.vars[roleVarName] = roleVar;
                    }
                }
            }
        }
    });
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
    def.type("pvc.visual.BasicSign").init(function(panel, pvMark) {
        this.chart = panel.chart;
        this.panel = panel;
        !pvMark.sign || def.assert("Mark already has an attached Sign.");
        this.pvMark = pvMark;
        pvMark.sign = this;
    }).add({
        compatVersion: function() {
            return this.chart.compatVersion();
        },
        localProperty: function(name, type) {
            this.pvMark.localProperty(name, type);
            return this;
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
    });
    def.type("pvc.visual.Sign", pvc.visual.BasicSign).init(function(panel, pvMark, keyArgs) {
        var me = this;
        me.base(panel, pvMark, keyArgs);
        me._ibits = panel._ibits;
        var extensionIds = def.get(keyArgs, "extensionId");
        null != extensionIds && (me.extensionAbsIds = def.array.to(panel._makeExtensionAbsId(extensionIds)));
        me.isActiveSeriesAware = def.get(keyArgs, "activeSeriesAware", !0);
        if (me.isActiveSeriesAware) {
            var roles = panel.visualRoles, seriesRole = roles && roles.series;
            seriesRole && seriesRole.isBound() || (me.isActiveSeriesAware = !1);
        }
        pvMark.wrapper(def.get(keyArgs, "wrapper") || me.createDefaultWrapper());
        def.get(keyArgs, "freeColor", !0) || me._bindProperty("fillStyle", "fillColor", "color")._bindProperty("strokeStyle", "strokeColor", "color");
    }).postInit(function(panel, pvMark, keyArgs) {
        this._addInteractive(keyArgs);
        panel._addSign(this);
    }).add({
        createDefaultWrapper: function() {
            var me = this;
            return function(f) {
                return function(scene) {
                    return f.call(me.context(), scene);
                };
            };
        },
        property: function(name) {
            var upperName = def.firstUpperCase(name), baseName = "base" + upperName, defName = "default" + upperName, normalName = "normal" + upperName, interName = "interactive" + upperName, methods = {};
            methods[name] = function(scene, arg) {
                this._finished = !1;
                this._arg = arg;
                var value = this[baseName](scene, arg);
                if (null == value) return null;
                if (this._finished) return value;
                value = this.showsInteraction() && scene.anyInteraction() ? this[interName](scene, value, arg) : this[normalName](scene, value, arg);
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
            this.constructor.add(methods);
            return this;
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
        }
    }).prototype.property("color").constructor.add(pvc.visual.Interactive).add({
        extensionAbsIds: null,
        _addInteractive: function(ka) {
            var me = this, get = def.get;
            if (me.interactive()) {
                var bits = me._ibits, I = pvc.visual.Interactive;
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
            return this.showsActivity() ? scene.isActive || !noSeries && this.isActiveSeriesAware && scene.isActiveSeries() || scene.isActiveDatum() : !1;
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
            if (!this.pvMark.hasTooltip) {
                var tipOptions = def.create(this.chart._tooltipOptions, def.get(ka, "options"));
                tipOptions.isLazy = def.get(ka, "isLazy", !0);
                var tooltipFormatter = def.get(ka, "buildTooltip") || this._getTooltipFormatter(tipOptions);
                if (tooltipFormatter) {
                    tipOptions.isEnabled = this._isTooltipEnabled.bind(this);
                    var tipsyEvent = def.get(ka, "tipsyEvent");
                    tipsyEvent || (tipsyEvent = "mouseover");
                    this.pvMark.localProperty("tooltip").tooltip(this._createTooltipProp(tooltipFormatter, tipOptions.isLazy)).title(def.fun.constant("")).ensureEvents().event(tipsyEvent, pv.Behavior.tipsy(tipOptions)).hasTooltip = !0;
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
                return scene && !scene.isIntermediate && scene.showsTooltip() ? formatTooltip(scene) : void 0;
            };
        },
        _addPropHoverable: function() {
            var onEvent, offEvent, panel = this.panel;
            onEvent = "mouseover";
            offEvent = "mouseout";
            this.pvMark.ensureEvents().event(onEvent, function(scene) {
                if (scene.hoverable() && !panel.selectingByRubberband() && !panel.animating()) {
                    scene.setActive(!0);
                    panel.renderInteractive();
                }
            }).event(offEvent, function(scene) {
                !scene.hoverable() || panel.selectingByRubberband() || panel.animating() || scene.clearActive() && panel.renderInteractive();
            });
        },
        _ignoreClicks: 0,
        _propCursorClick: function(s) {
            var ibits = this._ibits & s._ibits, I = pvc.visual.Interactive;
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
    });
    def.type("pvc.visual.Panel", pvc.visual.Sign).init(function(panel, protoMark, keyArgs) {
        var pvPanel = def.get(keyArgs, "panel");
        if (!pvPanel) {
            var pvPanelType = def.get(keyArgs, "panelType") || pv.Panel;
            pvPanel = protoMark.add(pvPanelType);
        }
        this.base(panel, pvPanel, keyArgs);
    }).add({
        _addInteractive: function(keyArgs) {
            var t = !0;
            keyArgs = def.setDefaults(keyArgs, "noSelect", t, "noHover", t, "noTooltip", t, "noClick", t, "noDoubleClick", t);
            this.base(keyArgs);
        }
    });
    def.type("pvc.visual.Label", pvc.visual.Sign).init(function(panel, protoMark, keyArgs) {
        var pvMark = protoMark.add(pv.Label);
        this.base(panel, pvMark, keyArgs);
    }).add({
        _addInteractive: function(keyArgs) {
            var t = !0;
            keyArgs = def.setDefaults(keyArgs, "noSelect", t, "noHover", t, "noTooltip", t, "noClick", t, "noDoubleClick", t, "showsInteraction", !1);
            this.base(keyArgs);
        },
        defaultColor: def.fun.constant(pv.Color.names.black)
    });
    var DEFAULT_BG_COLOR = pv.Color.names.white;
    def.type("pvc.visual.ValueLabel", pvc.visual.Label).init(function(panel, anchorMark, keyArgs) {
        this.valuesFont = def.get(keyArgs, "valuesFont") || panel.valuesFont;
        this.valuesMask = def.get(keyArgs, "valuesMask") || panel.valuesMask;
        this.valuesOptimizeLegibility = def.get(keyArgs, "valuesOptimizeLegibility", panel.valuesOptimizeLegibility);
        this.valuesOverflow = def.get(keyArgs, "valuesOverflow", panel.valuesOverflow);
        this.hideOverflowed = "hide" === this.valuesOverflow;
        this.trimOverflowed = !this.hideOverflowed && "trim" === this.valuesOverflow;
        this.hideOrTrimOverflowed = this.hideOverflowed || this.trimOverflowed;
        var protoMark;
        protoMark = def.get(keyArgs, "noAnchor", !1) ? anchorMark : anchorMark.anchor(panel.valuesAnchor);
        keyArgs && null == keyArgs.extensionId && (keyArgs.extensionId = "label");
        this.base(panel, protoMark, keyArgs);
        this.pvMark.font(this.valuesFont);
        this._bindProperty("text", "text")._bindProperty("textStyle", "textColor", "color").intercept("visible", this.visible);
    }).prototype.property("text").property("textStyle").constructor.addStatic({
        maybeCreate: function(panel, anchorMark, keyArgs) {
            return panel.valuesVisible && panel.valuesMask ? new pvc.visual.ValueLabel(panel, anchorMark, keyArgs) : null;
        },
        isNeeded: function(panel) {
            return panel.valuesVisible && panel.valuesMask;
        }
    }).add({
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
            var state = scene.renderState, value = state.textFitInfo;
            return void 0 !== value ? value : state.textFitInfo = this.calcTextFitInfo(scene, this._evalBaseText()) || null;
        },
        calcTextFitInfo: function() {
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
            var cache = def.lazy(state, "cccBgColorCache"), color = def.getOwn(cache, type);
            color || (color = cache[type] = this.calcBackgroundColor(scene, type));
            return color;
        },
        calcBackgroundColor: function(scene) {
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
            var labelCenter = pv.Label.getPolygon(m.width, m.height, pvLabel.textAlign(), pvLabel.textBaseline(), pvLabel.textAngle(), pvLabel.textMargin()).center().plus(l, t), anchoredToShape = anchoredToMark.getShape(anchoredToMark.scene, pvLabel.index);
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
    });
    def.type("pvc.visual.Dot", pvc.visual.Sign).init(function(panel, parentMark, keyArgs) {
        var pvMark = parentMark.add(pv.Dot), protoMark = def.get(keyArgs, "proto");
        protoMark && pvMark.extend(protoMark);
        keyArgs = def.setDefaults(keyArgs, "freeColor", !1);
        this.base(panel, pvMark, keyArgs);
        if (!def.get(keyArgs, "freePosition", !1)) {
            var a_left = panel.isOrientationVertical() ? "left" : "bottom", a_bottom = panel.anchorOrtho(a_left);
            this._lockDynamic(a_left, "x")._lockDynamic(a_bottom, "y");
        }
        this._bindProperty("shape", "shape")._bindProperty("shapeRadius", "radius")._bindProperty("shapeSize", "size");
        this.optional("strokeDasharray", void 0).optional("lineWidth", 1.5);
    }).prototype.property("size").property("shape").constructor.add({
        y: def.fun.constant(0),
        x: def.fun.constant(0),
        radius: function() {
            this.instanceState().cccRadius = this.delegateExtension();
        },
        baseSize: function(scene) {
            var radius = this.instanceState().cccRadius;
            return null != radius ? def.sqr(radius) : this.base(scene);
        },
        defaultSize: def.fun.constant(12),
        interactiveSize: function(scene, size) {
            return this.mayShowActive(scene, !0) ? 2.5 * Math.max(size, 5) : size;
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
    });
    def.type("pvc.visual.DotSizeColor", pvc.visual.Dot).init(function(panel, parentMark, keyArgs) {
        this.base(panel, parentMark, keyArgs);
        var isV1Compat = this.compatVersion() <= 1;
        this._bindProperty("lineWidth", "strokeWidth").intercept("visible", function(scene) {
            if (!this.canShow(scene)) return !1;
            var visible = this.delegateExtension();
            null == visible && (visible = isV1Compat || this.defaultVisible(scene));
            return visible;
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
    }).prototype.property("strokeWidth").constructor.add({
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
            sceneColorScale || (sceneColorScale = def.fun.constant(colorConstant || pvc.defaultColor));
            this._sceneDefColor = sceneColorScale;
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
                if (isSelected && pvc_colorIsGray(color)) {
                    "stroke" === type && (color = color.darker(3));
                    return color.darker(2);
                }
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
    });
    pv.LineInterm = function() {
        pv.Line.call(this);
    };
    pv.LineInterm.prototype = pv.extend(pv.Line);
    pv.LineInterm.prototype.getNearestInstanceToMouse = function(scene, eventIndex) {
        var mouseIndex = pv.Line.prototype.getNearestInstanceToMouse.call(this, scene, eventIndex), s = scene[mouseIndex];
        s && s.data && s.data.isIntermediate && mouseIndex + 1 < scene.length && mouseIndex++;
        return mouseIndex;
    };
    def.type("pvc.visual.Line", pvc.visual.Sign).init(function(panel, protoMark, keyArgs) {
        var pvMark = protoMark.add(pv.LineInterm);
        this.base(panel, pvMark, keyArgs);
        this.lock("segmented", "smart").lock("antialias", !0);
        if (!def.get(keyArgs, "freePosition", !1)) {
            var basePosProp = panel.isOrientationVertical() ? "left" : "bottom", orthoPosProp = panel.anchorOrtho(basePosProp);
            this._lockDynamic(orthoPosProp, "y")._lockDynamic(basePosProp, "x");
        }
        this._bindProperty("strokeStyle", "strokeColor", "color")._bindProperty("lineWidth", "strokeWidth");
    }).prototype.property("strokeWidth").constructor.add({
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
    });
    pv.AreaInterm = function() {
        pv.Area.call(this);
    };
    pv.AreaInterm.prototype = pv.extend(pv.Area);
    pv.AreaInterm.prototype.getNearestInstanceToMouse = function(scene, eventIndex) {
        var mouseIndex = pv.Area.prototype.getNearestInstanceToMouse.call(this, scene, eventIndex), s = scene[mouseIndex];
        s && s.data && s.data.isIntermediate && mouseIndex + 1 < scene.length && mouseIndex++;
        return mouseIndex;
    };
    def.type("pvc.visual.Area", pvc.visual.Sign).init(function(panel, protoMark, keyArgs) {
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
    }).add({
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
    });
    def.type("pvc.visual.Bar", pvc.visual.Sign).init(function(panel, protoMark, keyArgs) {
        var pvMark = protoMark.add(pv.Bar);
        keyArgs = def.setDefaults(keyArgs, "freeColor", !1);
        this.base(panel, pvMark, keyArgs);
        this.normalStroke = def.get(keyArgs, "normalStroke", !1);
        this._bindProperty("lineWidth", "strokeWidth");
    }).prototype.property("strokeWidth").constructor.add({
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
    });
    pv.PieSlice = function() {
        pv.Wedge.call(this);
    };
    pv.PieSlice.prototype = pv.extend(pv.Wedge).property("offsetRadius");
    pv.PieSlice.prototype.midAngle = function() {
        var instance = this.instance();
        return instance.startAngle + instance.angle / 2;
    };
    pv.PieSlice.prototype.defaults = new pv.PieSlice().extend(pv.Wedge.prototype.defaults).offsetRadius(0);
    def.type("pvc.visual.PieSlice", pvc.visual.Sign).init(function(panel, protoMark, keyArgs) {
        var pvMark = protoMark.add(pv.PieSlice);
        keyArgs = def.setDefaults(keyArgs, "freeColor", !1);
        this.base(panel, pvMark, keyArgs);
        this._activeOffsetRadius = def.get(keyArgs, "activeOffsetRadius", 0);
        this._maxOffsetRadius = def.get(keyArgs, "maxOffsetRadius", 0);
        this._resolvePctRadius = def.get(keyArgs, "resolvePctRadius");
        this._center = def.get(keyArgs, "center");
        this.optional("lineWidth", .6)._bindProperty("angle", "angle")._bindProperty("offsetRadius", "offsetRadius")._lockDynamic("bottom", "y")._lockDynamic("left", "x").lock("top", null).lock("right", null);
    }).prototype.property("offsetRadius").constructor.add({
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
    });
    def.type("pvc.visual.Rule", pvc.visual.Sign).init(function(panel, parentMark, keyArgs) {
        var pvMark = parentMark.add(pv.Rule), protoMark = def.get(keyArgs, "proto");
        protoMark && pvMark.extend(protoMark);
        this.base(panel, pvMark, keyArgs);
        def.get(keyArgs, "freeStyle") || this._bindProperty("strokeStyle", "strokeColor", "color")._bindProperty("lineWidth", "strokeWidth");
    }).prototype.property("strokeWidth").constructor.add({
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
    });
    def.type("pvc.visual.OptionsBase").init(function(chart, type, index, keyArgs) {
        this.chart = chart;
        this.type = type;
        this.index = null == index ? 0 : index;
        this.name = def.get(keyArgs, "name");
        this.id = this._buildId();
        this.optionId = this._buildOptionId();
        var rs = this._resolvers = [];
        this._registerResolversFull(rs, keyArgs);
        this.option = pvc.options(this._getOptionsDefinition(), this);
    }).add({
        _buildId: function() {
            return pvc.buildIndexedId(this.type, this.index);
        },
        _buildOptionId: function() {
            return this.id;
        },
        _chartOption: function(name) {
            return this.chart.options[name];
        },
        _getOptionsDefinition: def.method({
            isAbstract: !0
        }),
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
            def.get(keyArgs, "byV1", !0) && this.chart.compatVersion() <= 1 && rs.push(this._resolveByV1OnlyLogic);
            this.name && rs.push(pvc.options.specify(function(optionInfo) {
                return this._chartOption(this.name + def.firstUpperCase(optionInfo.name));
            }));
            rs.push(this._resolveByOptionId);
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
            return this.index ? void 0 : this._chartOption(def.firstLowerCase(optionInfo.name));
        }),
        _resolveDefault: function(optionInfo) {
            var resolverDefault, data = optionInfo.data;
            if (data && (resolverDefault = data.resolveDefault) && resolverDefault.call(this, optionInfo)) return !0;
            if (this._defaults) {
                var value = this._defaults[optionInfo.name];
                if (void 0 !== value) {
                    optionInfo.defaultValue(value);
                    return !0;
                }
            }
        },
        _specifyChartOption: function(optionInfo, asName) {
            var value = this._chartOption(asName);
            if (null != value) {
                optionInfo.specify(value);
                return !0;
            }
        }
    });
    def.type("pvc.visual.MultiChart", pvc.visual.OptionsBase).init(function(chart) {
        this.base(chart, "multiChart", 0, {
            byV1: !1,
            byNaked: !1
        });
    }).add({
        _getOptionsDefinition: function() {
            return pvc.visual.MultiChart.optionsDef;
        }
    }).addStatic({
        optionsDef: {
            Max: {
                resolve: "_resolveFull",
                cast: pvc.castPositiveNumber,
                value: 1/0
            },
            ColumnsMax: {
                resolve: "_resolveFull",
                cast: pvc.castPositiveNumber,
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
    });
    def.type("pvc.visual.SmallChart", pvc.visual.OptionsBase).init(function(chart) {
        this.base(chart, "small", 0, {
            byV1: !1,
            byNaked: !1
        });
    }).add({
        _getOptionsDefinition: function() {
            return pvc.visual.SmallChart.optionsDef;
        }
    }).addStatic({
        optionsDef: {
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
                cast: pvc.castPositiveNumber,
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
    });
    var pvc_Axis = def.type("pvc.visual.Axis", pvc.visual.OptionsBase).init(function(chart, type, index, keyArgs) {
        this.base(chart, type, index, keyArgs);
        chart._addAxis(this);
    }).add({
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
            me.dataCell = me.dataCells[0];
            me.role = me.dataCell && me.dataCell.role;
            me.scaleType = axis_groupingScaleType(me.role.grouping);
            me._domainData = null;
            me._domainValues = null;
            me._domainItems = null;
            me._checkRoleCompatibility();
            return this;
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
            var cellData;
            cellData = "number" == typeof cellDataOrIndex ? this.domainCellData(cellDataOrIndex) : cellDataOrIndex;
            return this._selectDomainItems(cellData).array();
        },
        domainValues: function() {
            var domainValues = this._domainValues;
            if (!domainValues) {
                this._calcDomainItems();
                domainValues = this._domainValues;
            }
            return domainValues;
        },
        domainItems: function() {
            var domainItems = this._domainItems;
            if (!domainItems) {
                this._calcDomainItems();
                domainItems = this._domainItems;
            }
            return domainItems;
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
            var varName = def.get(keyArgs, "sceneVarName") || this.role.name, grouping = this.role.grouping;
            if (grouping.isSingleDimension && grouping.firstDimensionValueType() === Number) {
                var scale = this.scale, nullToZero = def.get(keyArgs, "nullToZero", !0), by = function(scene) {
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
            return this.scale.by1(function(scene) {
                return scene.vars[varName].value;
            });
        },
        _checkRoleCompatibility: function() {
            var L = this.dataCells.length;
            if (L > 1) {
                var otherGrouping, i, grouping = this._getBoundRoleGrouping(this.role);
                if ("discrete" === this.scaleType) for (i = 1; L > i; i++) {
                    otherGrouping = this._getBoundRoleGrouping(this.dataCells[i].role);
                    if (grouping.id !== otherGrouping.id) throw def.error.operationInvalid("Discrete roles on the same axis must have equal groupings.");
                } else {
                    if (!grouping.firstDimensionType().isComparable) throw def.error.operationInvalid("Continuous roles on the same axis must have 'comparable' groupings.");
                    for (i = 1; L > i; i++) {
                        otherGrouping = this._getBoundRoleGrouping(this.dataCells[i].role);
                        if (this.scaleType !== axis_groupingScaleType(otherGrouping)) throw def.error.operationInvalid("Continuous roles on the same axis must have scales of the same type.");
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
            var hasOwn = def.hasOwnProp, domainValuesSet = {}, domainValues = [], domainItems = [], domainData = (this.domainItemValueProp(), 
            this.domainData());
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
        },
        _getOptionsDefinition: function() {
            return axis_optionsDef;
        }
    }), axis_optionsDef = {}, pvc_CartesianAxis = def.type("pvc.visual.CartesianAxis", pvc_Axis).init(function(chart, type, index, keyArgs) {
        var options = chart.options;
        this.orientation = pvc_CartesianAxis.getOrientation(type, options.orientation);
        this.orientedId = pvc_CartesianAxis.getOrientedId(this.orientation, index);
        chart._allowV1SecondAxis && 1 === index && (this.v1SecondOrientedId = "second" + this.orientation.toUpperCase());
        this.base(chart, type, index, keyArgs);
        var extensions = this.extensionPrefixes = [ this.id + "Axis", this.orientedId + "Axis" ];
        this.v1SecondOrientedId && extensions.push(this.v1SecondOrientedId + "Axis");
        this._extPrefAxisPosition = extensions.length;
        extensions.push("axis");
    }).add({
        bind: function(dataCells) {
            this.base(dataCells);
            this._syncExtensionPrefixes();
            return this;
        },
        _syncExtensionPrefixes: function() {
            var extensions = this.extensionPrefixes;
            extensions.length = this._extPrefAxisPosition;
            var st = this.scaleType;
            if (st) {
                extensions.push(st + "Axis");
                "discrete" !== st && extensions.push("continuousAxis");
            }
            extensions.push("axis");
        },
        setScale: function(scale) {
            var oldScale = this.scale;
            this.base(scale);
            if (oldScale) {
                delete this.domain;
                delete this.ticks;
                delete this._roundingPaddings;
            }
            if (scale && !scale.isNull && "discrete" !== this.scaleType) {
                this.domain = scale.domain();
                this.domain.minLocked = !!scale.minLocked;
                this.domain.maxLocked = !!scale.maxLocked;
                var roundMode = this.option("DomainRoundMode");
                "nice" === roundMode && scale.nice();
                var tickFormatter = this.option("TickFormatter");
                tickFormatter && scale.tickFormatter(tickFormatter);
            }
            return this;
        },
        setTicks: function(ticks) {
            var scale = this.scale;
            scale && !scale.isNull || def.fail.operationInvalid("Scale must be set and non-null.");
            this.ticks = ticks;
            if ("discrete" !== scale.type && "tick" === this.option("DomainRoundMode")) {
                delete this._roundingPaddings;
                var tickCount = ticks && ticks.length;
                tickCount ? this.scale.domain(ticks[0], ticks[tickCount - 1]) : this.scale.domain(this.domain[0], this.domain[1]);
            }
        },
        setScaleRange: function(size) {
            var scale = this.scale;
            scale.min = 0;
            scale.max = size;
            scale.size = size;
            if ("discrete" === scale.type) {
                if (scale.domain().length > 0) {
                    var bandRatio = this.chart.options.panelSizeRatio || .8;
                    scale.splitBandedCenter(scale.min, scale.max, bandRatio);
                }
            } else scale.range(scale.min, scale.max);
            return scale;
        },
        getScaleRoundingPaddings: function() {
            var roundingPaddings = this._roundingPaddings;
            if (!roundingPaddings) {
                roundingPaddings = {
                    begin: 0,
                    end: 0,
                    beginLocked: !1,
                    endLocked: !1
                };
                var scale = this.scale;
                if (scale && !scale.isNull && "discrete" !== scale.type) {
                    var originalDomain = this.domain;
                    roundingPaddings.beginLocked = originalDomain.minLocked;
                    roundingPaddings.endLocked = originalDomain.maxLocked;
                    if ("numeric" === scale.type && "none" !== this.option("DomainRoundMode")) {
                        var currDomain = scale.domain(), origDomain = this.domain || def.assert("Original domain must be set"), currLength = currDomain[1] - currDomain[0];
                        if (currLength) {
                            var diff = origDomain[0] - currDomain[0];
                            diff > 0 && (roundingPaddings.begin = diff / currLength);
                            diff = currDomain[1] - origDomain[1];
                            diff > 0 && (roundingPaddings.end = diff / currLength);
                        }
                    }
                }
                this._roundingPaddings = roundingPaddings;
            }
            return roundingPaddings;
        },
        calcContinuousTicks: function(desiredTickCount) {
            null == desiredTickCount && (desiredTickCount = this.option("DesiredTickCount"));
            return this.scale.ticks(desiredTickCount, {
                roundInside: "tick" !== this.option("DomainRoundMode"),
                numberExponentMin: this.option("TickExponentMin"),
                numberExponentMax: this.option("TickExponentMax")
            });
        },
        _getOptionsDefinition: function() {
            return cartAxis_optionsDef;
        },
        _buildOptionId: function() {
            return this.id + "Axis";
        },
        _registerResolversNormal: function(rs) {
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
    });
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
    }, defaultPosition = pvc.options.defaultValue(function() {
        if (!this.typeIndex) return "x" === this.orientation ? "bottom" : "left";
        var firstAxis = this.chart.axesByType[this.type].first, position = firstAxis.option("Position");
        return pvc.BasePanel.oppositeAnchor[position];
    }), cartAxis_optionsDef = def.create(axis_optionsDef, {
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
                if (this.index > 0) {
                    optionInfo.specify(!1);
                    return !0;
                }
                return this._resolveFull(optionInfo);
            },
            data: {
                resolveV1: function(optionInfo) {
                    this._specifyChartOption(optionInfo, "useCompositeAxis");
                    return !0;
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
                        if (this.chart._allowV1SecondAxis) {
                            this._specifyChartOption(optionInfo, "secondAxisOffset");
                            break;
                        }
                    }
                    return !0;
                }
            },
            cast: pvc.castNumber
        },
        LabelSpacingMin: {
            resolve: "_resolveFull",
            cast: pvc.castNumber
        },
        OverlappedLabelsMode: {
            resolve: "_resolveFull",
            cast: pvc.parseOverlappedLabelsMode,
            value: "rotatethenhide"
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
                    if (this.chart.compatVersion() <= 1) {
                        optionInfo.defaultValue(5);
                        return !0;
                    }
                }
            },
            cast: pvc.castNumber
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
                    if (this.chart.compatVersion() <= 1) {
                        optionInfo.defaultValue("none");
                        return !0;
                    }
                }
            },
            cast: pvc.parseDomainRoundingMode,
            value: "tick"
        },
        TickExponentMin: {
            resolve: "_resolveFull",
            cast: pvc.castNumber
        },
        TickExponentMax: {
            resolve: "_resolveFull",
            cast: pvc.castNumber
        },
        Title: {
            resolve: "_resolveFull",
            cast: String
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
    def.type("pvc.visual.CartesianFocusWindow", pvc.visual.OptionsBase).init(function(chart) {
        this.base(chart, "focusWindow", 0, {
            byNaked: !1
        });
        var baseAxis = chart.axes.base;
        this.base = new pvc.visual.CartesianFocusWindowAxis(this, baseAxis);
    }).add({
        _getOptionsDefinition: function() {
            return focusWindow_optionsDef;
        },
        _exportData: function() {
            return {
                base: def.copyProps(this.base, pvc.visual.CartesianFocusWindow.props)
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
        _onAxisChanged: function() {
            var changed = this.option("Changed");
            changed && changed.call(this.chart.basePanel.context());
        }
    });
    var focusWindow_optionsDef = def.create(axis_optionsDef, {
        Changed: {
            resolve: "_resolveFull",
            cast: def.fun.as
        }
    });
    def.type("pvc.visual.CartesianFocusWindowAxis", pvc.visual.OptionsBase).init(function(fw, axis) {
        this.window = fw;
        this.axis = axis;
        this.isDiscrete = axis.isDiscrete();
        this.base(axis.chart, "focusWindow" + def.firstUpperCase(axis.type), 0, {
            byNaked: !1
        });
    }).addStatic({
        props: [ "begin", "end", "length" ]
    }).add({
        _getOptionsDefinition: function() {
            return focusWindowAxis_optionsDef;
        },
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
            var a, L, axis = me.axis, scale = axis.scale, isDiscrete = me.isDiscrete, contCast = isDiscrete ? null : axis.role.firstDimensionType().cast, domain = scale.domain();
            if (isDiscrete) {
                L = domain.length;
                var ib, ie, ia;
                if (null != b) {
                    var nb = +b;
                    if (!isNaN(nb)) if (1/0 === nb) {
                        ib = L - 1;
                        b = domain[ib];
                    } else if (nb === -1/0) {
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
                    var ne = +e;
                    if (!isNaN(ne)) if (1/0 === ne) {
                        ie = L - 1;
                        e = domain[ie];
                    } else if (ne === -1/0) {
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
                        b = e, ib = ie, e = a, ie = ia;
                        l = -l;
                    }
                }
                if (null != b) if (null != e) {
                    if (ib > ie) {
                        a = b;
                        ia = ib;
                        b = e, ib = ie, e = a, ie = ia;
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
                        b = e, e = a;
                        l = -l;
                    }
                }
                var min = domain[0], max = domain[1];
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
                        b = e, e = a;
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
                        var bAux = +b, lAux = +l;
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
                    var bAux = +b, lAux = +l;
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
                var vlength0, pother0, vother0, contCast = axis.role.firstDimensionType().cast, v = contCast(scale.invert(oper.point)), sign = "begin" === oper.target ? 1 : -1, pother = oper.point + sign * oper.length, vother = contCast(scale.invert(pother)), vlength = contCast(sign * (vother - v));
                if (oper.length === oper.length0) vlength0 = vlength; else {
                    pother0 = oper.point + sign * oper.length0;
                    vother0 = contCast(scale.invert(pother0));
                    vlength0 = sign * (vother0 - v);
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
            if (!me._compare(b, me.begin)) {
                me.begin = b;
                changed = !0;
            }
            if (!me._compare(e, me.end)) {
                me.end = e;
                changed = !0;
            }
            if (!me._compare(l, me.length)) {
                me.length = l;
                changed = !0;
            }
            changed && me.window._onAxisChanged(this);
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
                    }).selectMany(def.propGet("_datums")).where(datum_isVisibleT).distinct(def.propGet("key"));
                }
            } else {
                domainData = partData;
                var dimName = role.firstDimensionName();
                selectDatums = def.query(partData._datums).where(datum_isVisibleT).where(function(datum) {
                    var v = datum.atoms[dimName].value;
                    return null != v && v >= me.begin && v <= me.end;
                });
            }
            if (selectDatums) {
                chart.data.replaceSelected(selectDatums);
                chart.root.updateSelections(keyArgs);
            }
        }
    });
    var focusWindowAxis_optionsDef = def.create(axis_optionsDef, {
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
    });
    def.type("pvc.visual.ColorAxis", pvc_Axis).add({
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
            var applyTransf;
            applyTransf = "discrete" === this.scaleType ? this.option.isSpecified("Transform") || !this.option.isSpecified("Colors") && !this.option.isSpecified("Map") : !0;
            if (applyTransf) {
                var colorTransf = this.option("Transform");
                colorTransf && (scale = scale.transform(colorTransf));
            }
            return this.base(scale);
        },
        scheme: function() {
            return def.lazy(this, "_scheme", this._createScheme, this);
        },
        _createColorMapFilter: function(colorMap) {
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
            var colorMap = me.option("Map");
            if (!colorMap) return function() {
                var scale = baseScheme.apply(null, arguments);
                return me._wrapScale(scale);
            };
            var filter = this._createColorMapFilter(colorMap);
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
                    if (arguments.length) throw def.operationInvalid("The scale cannot be modified.");
                    dx || (dx = def.array.append(def.ownKeys(colorMap), d));
                    return dx;
                };
                scale.range = function() {
                    if (arguments.length) throw def.operationInvalid("The scale cannot be modified.");
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
        _buildOptionId: function() {
            return this.id + "Axis";
        },
        _getOptionsDefinition: function() {
            return colorAxis_optionsDef;
        },
        _resolveByNaked: pvc.options.specify(function(optionInfo) {
            return this.index ? void 0 : this._chartOption(this.id + def.firstUpperCase(optionInfo.name));
        }),
        _specifyV1ChartOption: function(optionInfo, asName) {
            return !this.index && this.chart.compatVersion() <= 1 && this._specifyChartOption(optionInfo, asName) ? !0 : void 0;
        }
    });
    var colorAxis_defContColors, colorAxis_legendDataSpec = {
        resolveDefault: function(optionInfo) {
            return !this.index && this._specifyChartOption(optionInfo, def.firstLowerCase(optionInfo.name)) ? !0 : void 0;
        }
    }, colorAxis_optionsDef = def.create(axis_optionsDef, {
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
        Transform: {
            resolve: "_resolveFull",
            data: {
                resolveDefault: function(optionInfo) {
                    var plotList = this._plotList;
                    if (plotList.length <= 2) {
                        var onlyTrendAndPlot2 = def.query(plotList).all(function(plot) {
                            var name = plot.name;
                            return "plot2" === name || "trend" === name;
                        });
                        if (onlyTrendAndPlot2) {
                            optionInfo.defaultValue(pvc.brighterColorTransform);
                            return !0;
                        }
                    }
                }
            },
            cast: def.fun.to
        },
        NormByCategory: {
            resolve: function(optionInfo) {
                if (!this.chart._allowColorPerCategory) {
                    optionInfo.specify(!1);
                    return !0;
                }
                return this._resolveFull(optionInfo);
            },
            data: {
                resolveV1: function(optionInfo) {
                    this._specifyV1ChartOption(optionInfo, "normPerBaseCategory");
                    return !0;
                }
            },
            cast: Boolean,
            value: !1
        },
        ScaleType: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    this._specifyV1ChartOption(optionInfo, "scalingType");
                    return !0;
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
                    this._specifyV1ChartOption(optionInfo, "colorRangeInterval");
                    return !0;
                }
            },
            cast: def.array.to
        },
        Min: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    this._specifyV1ChartOption(optionInfo, "minColor");
                    return !0;
                }
            },
            cast: pv.color
        },
        Max: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    this._specifyV1ChartOption(optionInfo, "maxColor");
                    return !0;
                }
            },
            cast: pv.color
        },
        Missing: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    this._specifyV1ChartOption(optionInfo, "nullColor");
                    return !0;
                }
            },
            cast: pv.color,
            value: pv.color("lightgray")
        },
        Unbound: {
            resolve: "_resolveFull",
            getDefault: function() {
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
    def.type("pvc.visual.SizeAxis", pvc_Axis).init(function(chart, type, index, keyArgs) {
        keyArgs = def.set(keyArgs, "byNaked", !1);
        this.base(chart, type, index, keyArgs);
    }).add({
        _buildOptionId: function() {
            return this.id + "Axis";
        },
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
            pvc.debug >= 4 && pvc.log("Scale: " + pvc.stringify(def.copyOwn(scale)));
            return this;
        },
        _getOptionsDefinition: function() {
            return sizeAxis_optionsDef;
        }
    });
    var sizeAxis_optionsDef = def.create(axis_optionsDef, {
        OriginIsZero: {
            resolve: "_resolveFull",
            cast: Boolean,
            value: !1
        },
        FixedMin: {
            resolve: "_resolveFull",
            cast: pvc.castNumber
        },
        FixedMax: {
            resolve: "_resolveFull",
            cast: pvc.castNumber
        },
        UseAbs: {
            resolve: "_resolveFull",
            cast: Boolean,
            value: !1
        }
    });
    def.type("pvc.visual.NormalizedAxis", pvc_Axis).init(function(chart, type, index, keyArgs) {
        keyArgs = def.set(keyArgs, "byNaked", !1);
        this.base(chart, type, index, keyArgs);
    }).add({
        _buildOptionId: function() {
            return this.id + "Axis";
        },
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
            pvc.debug >= 4 && pvc.log("Scale: " + pvc.stringify(def.copyOwn(scale)));
            return this;
        },
        _getOptionsDefinition: function() {
            return normAxis_optionsDef;
        }
    });
    var normAxis_optionsDef = def.create(axis_optionsDef, {
        OriginIsZero: {
            value: !0
        }
    });
    def.type("pvc.visual.Legend", pvc.visual.OptionsBase).init(function(chart, type, index, keyArgs) {
        keyArgs = def.set(keyArgs, "byNaked", !1);
        this.base(chart, type, index, keyArgs);
    }).add({
        _getOptionsDefinition: function() {
            return legend_optionsDef;
        }
    });
    var legend_optionsDef = {
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
        Align: {
            resolve: "_resolveFull",
            data: {
                resolveDefault: function(optionInfo) {
                    var align, position = this.option("Position");
                    "top" !== position && "bottom" !== position ? align = "top" : this.chart.compatVersion() <= 1 && (align = "left");
                    optionInfo.defaultValue(align);
                    return !0;
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
        }
    };
    def.type("pvc.visual.legend.BulletRootScene", pvc.visual.Scene).init(function(parent, keyArgs) {
        this.base(parent, keyArgs);
        this._unresolvedMarkerDiam = def.get(keyArgs, "markerSize");
        this._unresolvedItemPadding = new pvc_Sides(def.get(keyArgs, "itemPadding", 5));
        this._unresolvedItemSize = pvc_Size.to(def.get(keyArgs, "itemSize")) || new pvc_Size();
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
                        section = new pvc.visual.legend.BulletItemSceneSection(0);
                        isFirstInSection = !0;
                    }
                    var $newSectionWidth = section.size[a_width] + itemClientSize[a_width];
                    isFirstInSection || ($newSectionWidth += itemPadding[a_width]);
                    if (!isFirstInSection && $newSectionWidth > $maxSectionWidth) {
                        commitSection(!1);
                        $newSectionWidth = itemClientSize[a_width];
                    }
                    var sectionSize = section.size;
                    sectionSize[a_width] = $newSectionWidth;
                    sectionSize[a_height] = Math.max(sectionSize[a_height], itemClientSize[a_height]);
                    var sectionIndex = section.items.length;
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
                isLast || (section = new pvc.visual.legend.BulletItemSceneSection(sections.length));
            }
            var clientSize = layoutInfo.clientSize;
            if (!(clientSize.width > 0 && clientSize.height > 0)) return new pvc_Size(0, 0);
            var desiredClientSize = layoutInfo.desiredClientSize, itemPadding = this._unresolvedItemPadding.resolve(clientSize), extClientSize = {
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
            var textLeft = markerDiam + this.vars.textMargin, labelWidthMax = Math.max(0, Math.min(desiredItemClientSize.width || 1/0, desiredClientSize.width || 1/0, clientSize.width) - textLeft), a_width = this.vars.horizontal ? "width" : "height", a_height = pvc.BasePanel.oppositeLength[a_width], $maxSectionWidth = desiredClientSize[a_width];
            (!$maxSectionWidth || 0 > $maxSectionWidth) && ($maxSectionWidth = clientSize[a_width]);
            var section, sections = [], contentSize = {
                width: 0,
                height: 0
            };
            this.childNodes.forEach(function(groupScene) {
                groupScene.childNodes.forEach(layoutItem, this);
            }, this);
            if (!section) return new pvc_Size(0, 0);
            commitSection(!0);
            def.set(this.vars, "sections", sections, "contentSize", contentSize, "labelWidthMax", labelWidthMax);
            var isV1Compat = this.compatVersion() <= 1, $w = isV1Compat ? $maxSectionWidth : contentSize[a_width], $h = desiredClientSize[a_height];
            (!$h || 0 > $h) && ($h = contentSize[a_height]);
            var requestSize = this.vars.size = def.set({}, a_width, Math.min($w, clientSize[a_width]), a_height, Math.min($h, clientSize[a_height]));
            return requestSize;
        },
        defaultGroupSceneType: function() {
            var GroupType = this._bulletGroupType;
            if (!GroupType) {
                GroupType = def.type(pvc.visual.legend.BulletGroupScene);
                this._bulletGroupType = GroupType;
            }
            return GroupType;
        },
        createGroup: function(keyArgs) {
            var GroupType = this.defaultGroupSceneType();
            return new GroupType(this, keyArgs);
        }
    });
    def.type("pvc.visual.legend.BulletItemSceneSection").init(function(index) {
        this.index = index;
        this.items = [];
        this.size = {
            width: 0,
            height: 0
        };
    });
    def.type("pvc.visual.legend.BulletGroupScene", pvc.visual.Scene).init(function(rootScene, keyArgs) {
        this.base(rootScene, keyArgs);
        this.extensionPrefix = def.get(keyArgs, "extensionPrefix") || "";
        this._renderer = def.get(keyArgs, "renderer");
        this.colorAxis = def.get(keyArgs, "colorAxis");
        this.clickMode = def.get(keyArgs, "clickMode");
        !this.clickMode && this.colorAxis && (this.clickMode = this.colorAxis.option("LegendClickMode"));
    }).add({
        hasRenderer: function() {
            return !!this._renderer;
        },
        renderer: function(renderer) {
            if (null != renderer) this._renderer = renderer; else {
                renderer = this._renderer;
                if (!renderer) {
                    var keyArgs, colorAxis = this.colorAxis;
                    colorAxis && (keyArgs = {
                        drawRule: colorAxis.option("LegendDrawLine"),
                        drawMarker: colorAxis.option("LegendDrawMarker"),
                        markerShape: colorAxis.option("LegendShape")
                    });
                    renderer = new pvc.visual.legend.BulletItemDefaultRenderer(keyArgs);
                    this._renderer = renderer;
                }
            }
            return renderer;
        },
        itemSceneType: function() {
            var ItemType = this._itemSceneType;
            if (!ItemType) {
                ItemType = def.type(pvc.visual.legend.BulletItemScene);
                var clickMode = this.clickMode;
                switch (clickMode) {
                  case "toggleselected":
                    ItemType.add(pvc.visual.legend.BulletItemSceneSelection);
                    break;

                  case "togglevisible":
                    ItemType.add(pvc.visual.legend.BulletItemSceneVisibility);
                }
                var legendPanel = this.panel();
                legendPanel._extendSceneType("item", ItemType, [ "isOn", "executable", "execute", "value", "labelText" ]);
                var itemSceneExtIds = pvc.makeExtensionAbsId(pvc.makeExtensionAbsId("ItemScene", [ this.extensionPrefix, "$" ]), legendPanel._getExtensionPrefix()), impl = legendPanel.chart._getExtension(itemSceneExtIds, "value");
                void 0 !== impl && ItemType.prototype.variable("value", impl);
                this._itemSceneType = ItemType;
            }
            return ItemType;
        },
        createItem: function(keyArgs) {
            var ItemType = this.itemSceneType();
            return new ItemType(this, keyArgs);
        }
    });
    def.type("pvc.visual.legend.BulletItemScene", pvc.visual.Scene).init(function() {
        this.base.apply(this, arguments);
        if (!this.executable()) {
            var I = pvc.visual.Interactive;
            this._ibits = I.Interactive | I.ShowsInteraction | I.Hoverable | I.SelectableAny;
        }
    }).add({
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
    }).prototype.variable("value");
    def.type("pvc.visual.legend.BulletItemSceneSelection").add({
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
                    datums && datums.length && pvc.data.Data.toggleSelected(datums, !0);
                });
            }
        }
    });
    def.type("pvc.visual.legend.BulletItemSceneVisibility").add({
        isOn: function() {
            return this.datums().any(function(datum) {
                return !datum.isNull && datum.isVisible;
            });
        },
        executable: def.fun.constant(!0),
        execute: function() {
            pvc.data.Data.toggleVisible(this.datums()) && this.chart().render(!0, !0, !1);
        }
    });
    def.type("pvc.visual.legend.BulletItemRenderer");
    def.type("pvc.visual.legend.BulletItemDefaultRenderer", pvc.visual.legend.BulletItemRenderer).init(function(keyArgs) {
        this.drawRule = def.get(keyArgs, "drawRule", !1);
        this.drawRule && (this.rulePvProto = def.get(keyArgs, "rulePvProto"));
        this.drawMarker = !this.drawRule || def.get(keyArgs, "drawMarker", !0);
        if (this.drawMarker) {
            this.markerShape = def.get(keyArgs, "markerShape", "square");
            this.markerPvProto = def.get(keyArgs, "markerPvProto");
        }
    }).add({
        drawRule: !1,
        drawMarker: !0,
        markerShape: null,
        rulePvProto: null,
        markerPvProto: null,
        create: function(legendPanel, pvBulletPanel, extensionPrefix, wrapper) {
            var renderInfo = {}, drawRule = this.drawRule, sceneColorProp = function(scene) {
                return scene.color;
            };
            if (drawRule) {
                var rulePvBaseProto = new pv_Mark().left(0).top(function() {
                    return this.parent.height() / 2;
                }).width(function() {
                    return this.parent.width();
                }).lineWidth(1, pvc.extensionTag).strokeStyle(sceneColorProp, pvc.extensionTag), rp = this.rulePvProto;
                rp && (rulePvBaseProto = rp.extend(rulePvBaseProto));
                renderInfo.pvRule = new pvc.visual.Rule(legendPanel, pvBulletPanel, {
                    proto: rulePvBaseProto,
                    noSelect: !1,
                    noHover: !1,
                    activeSeriesAware: !1,
                    extensionId: extensionPrefix + "Rule",
                    extensionId: pvc.makeExtensionAbsId("Rule", [ extensionPrefix, "$" ]),
                    showsInteraction: !0,
                    wrapper: wrapper
                }).pvMark;
            }
            if (this.drawMarker) {
                var markerPvBaseProto = new pv_Mark().left(function() {
                    return this.parent.width() / 2;
                }).top(function() {
                    return this.parent.height() / 2;
                }).shapeSize(function() {
                    return this.parent.width();
                }, pvc.extensionTag).lineWidth(2, pvc.extensionTag).fillStyle(sceneColorProp, pvc.extensionTag).strokeStyle(sceneColorProp, pvc.extensionTag).shape(this.markerShape, pvc.extensionTag).angle(drawRule ? 0 : Math.PI / 2, pvc.extensionTag).antialias(function() {
                    var cos = Math.abs(Math.cos(this.angle()));
                    if (0 !== cos && 1 !== cos) switch (this.shape()) {
                      case "square":
                      case "bar":
                        return !1;
                    }
                    return !0;
                }, pvc.extensionTag), mp = this.markerPvProto;
                mp && (markerPvBaseProto = mp.extend(markerPvBaseProto));
                renderInfo.pvDot = new pvc.visual.Dot(legendPanel, pvBulletPanel, {
                    proto: markerPvBaseProto,
                    freePosition: !0,
                    activeSeriesAware: !1,
                    noTooltip: !0,
                    noClick: !0,
                    extensionId: pvc.makeExtensionAbsId("Dot", [ extensionPrefix, "$" ]),
                    wrapper: wrapper
                }).pvMark;
            }
            return renderInfo;
        }
    });
    def.type("pvc.visual.DataCell").init(function(plot, axisType, axisIndex, roleName, dataPartValue) {
        this.plot = plot;
        this.axisType = axisType;
        this.axisIndex = axisIndex;
        this.role = plot.chart.visualRoles[roleName] || def.fail.argumentInvalid("roleName", "Role is not defined.");
        this.dataPartValue = dataPartValue;
    });
    def.type("pvc.visual.ColorDataCell", pvc.visual.DataCell).init(function(plot, axisType, axisIndex, roleName, dataPartValue) {
        this.base(plot, axisType, axisIndex, roleName, dataPartValue);
        this._legendGroupScene = null;
    }).add({
        legendGroupScene: function(_) {
            if (arguments.length) {
                this._legendGroupScene = _;
                return this;
            }
            return this._legendGroupScene;
        }
    });
    def.type("pvc.visual.Plot", pvc.visual.OptionsBase).init(function(chart, keyArgs) {
        var typePlots = def.getPath(chart, [ "plotsByType", this.type ]), index = typePlots ? typePlots.length : 0, globalIndex = chart.plotList.length;
        keyArgs = def.set(keyArgs, "byNaked", !globalIndex);
        this.base(chart, this.type, index, keyArgs);
        chart._addPlot(this);
        var prefixes = this.extensionPrefixes = [ this.id ];
        this.globalIndex || prefixes.push("");
        this.name && prefixes.push(this.name);
    }).add({
        _getOptionsDefinition: function() {
            return pvc.visual.Plot.optionsDef;
        },
        _resolveByNaked: pvc.options.specify(function(optionInfo) {
            return this.globalIndex ? void 0 : this._chartOption(def.firstLowerCase(optionInfo.name));
        }),
        collectDataCells: function(dataCells) {
            var dataCell = this._getColorDataCell();
            dataCell && dataCells.push(dataCell);
        },
        _getColorDataCell: function() {
            var colorRoleName = this.option("ColorRole");
            return colorRoleName ? new pvc.visual.ColorDataCell(this, "color", this.option("ColorAxis") - 1, colorRoleName, this.option("DataPart")) : void 0;
        }
    });
    pvc.visual.Plot.optionsDef = {
        Orientation: {
            resolve: function(optionInfo) {
                optionInfo.specify(this._chartOption("orientation") || "vertical");
                return !0;
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
            resolve: "_resolveFixed",
            cast: String,
            value: "0"
        },
        ColorRole: {
            resolve: "_resolveFixed",
            cast: String,
            value: "color"
        },
        ColorAxis: {
            resolve: pvc.options.resolvers([ function(optionInfo) {
                if (0 === this.globalIndex) {
                    optionInfo.specify(1);
                    return !0;
                }
            }, "_resolveFull" ]),
            cast: function(value) {
                value = pvc.castNumber(value);
                value = null != value ? def.between(value, 1, 10) : 1;
                return value;
            },
            value: 1
        }
    };
    def.type("pvc.visual.CartesianOrthoDataCell", pvc.visual.DataCell).init(function(plot, axisType, axisIndex, roleName, dataPartValue, isStacked, nullInterpolationMode, trend) {
        this.base(plot, axisType, axisIndex, roleName, dataPartValue);
        this.isStacked = isStacked;
        this.nullInterpolationMode = nullInterpolationMode;
        this.trend = trend;
    });
    def.type("pvc.visual.CartesianPlot", pvc.visual.Plot).add({
        collectDataCells: function(dataCells) {
            this.base(dataCells);
            dataCells.push(new pvc.visual.DataCell(this, "base", this.option("BaseAxis") - 1, this.option("BaseRole"), this.option("DataPart")));
            var orthoRoleNames = def.array.to(this.option("OrthoRole")), dataPartValue = this.option("DataPart"), orthoAxisIndex = this.option("OrthoAxis") - 1, isStacked = this.option.isDefined("Stacked") ? this.option("Stacked") : void 0, nullInterpolationMode = this.option("NullInterpolationMode"), trend = this.option("Trend");
            orthoRoleNames.forEach(function(orthoRoleName) {
                dataCells.push(new pvc.visual.CartesianOrthoDataCell(this, "ortho", orthoAxisIndex, orthoRoleName, dataPartValue, isStacked, nullInterpolationMode, trend));
            }, this);
        },
        _getOptionsDefinition: function() {
            return pvc.visual.CartesianPlot.optionsDef;
        }
    });
    pvc.visual.CartesianPlot.optionsDef = def.create(pvc.visual.Plot.optionsDef, {
        BaseAxis: {
            value: 1
        },
        BaseRole: {
            resolve: "_resolveFixed",
            cast: String
        },
        OrthoAxis: {
            resolve: function(optionInfo) {
                if (0 === this.globalIndex) {
                    optionInfo.specify(1);
                    return !0;
                }
                return this._resolveFull(optionInfo);
            },
            data: {
                resolveV1: function(optionInfo) {
                    "plot2" === this.name && this.chart._allowV1SecondAxis && this._chartOption("secondAxisIndependentScale") && optionInfo.specify(2);
                    return !0;
                }
            },
            cast: function(value) {
                value = pvc.castNumber(value);
                return null != value ? def.between(value, 1, 10) : 1;
            },
            value: 1
        },
        OrthoRole: {
            resolve: pvc.options.resolvers([ "_resolveFixed", "_resolveDefault" ])
        },
        Trend: {
            resolve: "_resolveFull",
            data: {
                resolveDefault: function(optionInfo) {
                    var type = this.option("TrendType");
                    if (type) {
                        optionInfo.defaultValue({
                            type: type
                        });
                        return !0;
                    }
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
    });
    def.type("pvc.visual.CategoricalPlot", pvc.visual.CartesianPlot).add({
        _getOptionsDefinition: function() {
            return pvc.visual.CategoricalPlot.optionsDef;
        }
    });
    pvc.visual.CategoricalPlot.optionsDef = def.create(pvc.visual.CartesianPlot.optionsDef, {
        Stacked: {
            resolve: "_resolveFull",
            cast: Boolean,
            value: !1
        },
        BaseRole: {
            value: "category"
        },
        OrthoRole: {
            value: "value"
        }
    });
    def.type("pvc.visual.BarPlotAbstract", pvc.visual.CategoricalPlot).add({
        _getOptionsDefinition: function() {
            return pvc.visual.BarPlotAbstract.optionsDef;
        }
    });
    pvc.visual.BarPlotAbstract.optionsDef = def.create(pvc.visual.CategoricalPlot.optionsDef, {
        BarSizeRatio: {
            resolve: "_resolveFull",
            cast: function(value) {
                value = pvc.castNumber(value);
                null == value ? value = 1 : .05 > value ? value = .05 : value > 1 && (value = 1);
                return value;
            },
            value: .9
        },
        BarSizeMax: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    this._specifyChartOption(optionInfo, "maxBarSize");
                    return !0;
                }
            },
            cast: function(value) {
                value = pvc.castNumber(value);
                null == value ? value = 1/0 : 1 > value && (value = 1);
                return value;
            },
            value: 2e3
        },
        BarOrthoSizeMin: {
            resolve: "_resolveFull",
            cast: pvc.castNonNegativeNumber,
            value: 1.5
        },
        BarStackedMargin: {
            resolve: "_resolveFull",
            cast: function(value) {
                value = pvc.castNumber(value);
                null != value && 0 > value && (value = 0);
                return value;
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
        }
    });
    def.type("pvc.visual.BarPlot", pvc.visual.BarPlotAbstract).add({
        type: "bar"
    });
    def.type("pvc.visual.NormalizedBarPlot", pvc.visual.BarPlotAbstract).add({
        type: "bar",
        _getOptionsDefinition: function() {
            return pvc.visual.NormalizedBarPlot.optionsDef;
        }
    });
    pvc.visual.NormalizedBarPlot.optionsDef = def.create(pvc.visual.BarPlotAbstract.optionsDef, {
        Stacked: {
            resolve: null,
            value: !0
        }
    });
    def.type("pvc.visual.WaterfallPlot", pvc.visual.BarPlotAbstract).add({
        type: "water",
        _getOptionsDefinition: function() {
            return pvc.visual.WaterfallPlot.optionsDef;
        }
    });
    pvc.visual.WaterfallPlot.optionsDef = def.create(pvc.visual.BarPlotAbstract.optionsDef, {
        Stacked: {
            resolve: null,
            value: !0
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
                    optionInfo.defaultValue(this.option("ValuesVisible"));
                    return !0;
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
    });
    def.type("pvc.visual.PointPlot", pvc.visual.CategoricalPlot).add({
        type: "point",
        _getOptionsDefinition: function() {
            return pvc.visual.PointPlot.optionsDef;
        }
    });
    pvc.visual.PointPlot.optionsDef = def.create(pvc.visual.CategoricalPlot.optionsDef, {
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
        ValuesAnchor: {
            value: "right"
        }
    });
    def.type("pvc.visual.MetricXYPlot", pvc.visual.CartesianPlot).add({
        _getOptionsDefinition: function() {
            return pvc.visual.MetricXYPlot.optionsDef;
        }
    });
    pvc.visual.MetricXYPlot.optionsDef = def.create(pvc.visual.CartesianPlot.optionsDef, {
        BaseRole: {
            value: "x"
        },
        OrthoAxis: {
            resolve: null
        },
        OrthoRole: {
            value: "y"
        }
    });
    def.type("pvc.visual.MetricPointPlot", pvc.visual.MetricXYPlot).add({
        type: "scatter",
        collectDataCells: function(dataCells) {
            this.base(dataCells);
            if (this.option("DotsVisible")) {
                var sizeRole = this.chart.visualRole(this.option("SizeRole"));
                sizeRole.isBound() && dataCells.push(new pvc.visual.DataCell(this, "size", this.option("SizeAxis") - 1, sizeRole.name, this.option("DataPart")));
            }
        },
        _getOptionsDefinition: function() {
            return pvc.visual.MetricPointPlot.optionsDef;
        }
    });
    pvc.visual.MetricPointPlot.optionsDef = def.create(pvc.visual.MetricXYPlot.optionsDef, {
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
        }
    });
    def.type("pvc.visual.PiePlot", pvc.visual.Plot).add({
        type: "pie",
        collectDataCells: function(dataCells) {
            this.base(dataCells);
            var dataPartValue = this.option("DataPart");
            dataCells.push(new pvc.visual.DataCell(this, "category", 0, "category", dataPartValue));
            dataCells.push(new pvc.visual.DataCell(this, "angle", 0, "value", dataPartValue));
        },
        _getOptionsDefinition: function() {
            return pvc.visual.PiePlot.optionsDef;
        }
    });
    pvc.visual.PiePlot.optionsDef = def.create(pvc.visual.Plot.optionsDef, {
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
            cast: pvc.castNumber,
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
                var isV1Compat = this.chart.compatVersion() <= 1;
                if (isV1Compat) {
                    optionInfo.specify("inside");
                    return !0;
                }
                return this._resolveFull(optionInfo);
            },
            cast: function(value) {
                switch (value) {
                  case "inside":
                  case "linked":
                    return value;
                }
                pvc.debug >= 2 && pvc.log("[Warning] Invalid 'ValuesLabelStyle' value: '" + value + "'.");
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
            cast: pvc.castNumber,
            value: .5
        },
        LinkLabelSize: {
            resolve: "_resolveFull",
            cast: pvc_PercentValue.parse,
            value: new pvc_PercentValue(.15)
        },
        LinkLabelSpacingMin: {
            resolve: "_resolveFull",
            cast: pvc.castNumber,
            value: .5
        }
    });
    def.type("pvc.visual.HeatGridPlot", pvc.visual.CategoricalPlot).add({
        type: "heatGrid",
        collectDataCells: function(dataCells) {
            this.base(dataCells);
            if (this.option("UseShapes")) {
                var sizeRole = this.chart.visualRole(this.option("SizeRole"));
                sizeRole.isBound() && dataCells.push(new pvc.visual.DataCell(this, "size", this.option("SizeAxis") - 1, sizeRole.name, this.option("DataPart")));
            }
        },
        _getOptionsDefinition: function() {
            return pvc.visual.HeatGridPlot.optionsDef;
        }
    });
    pvc.visual.HeatGridPlot.optionsDef = def.create(pvc.visual.CategoricalPlot.optionsDef, {
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
        OrthoRole: {
            value: "series"
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
    });
    def.type("pvc.visual.BoxPlot", pvc.visual.CategoricalPlot).add({
        type: "box",
        _getOptionsDefinition: function() {
            return pvc.visual.BoxPlot.optionsDef;
        }
    });
    pvc.visual.BoxPlot.optionsDef = def.create(pvc.visual.CategoricalPlot.optionsDef, {
        Stacked: {
            resolve: null,
            value: !1
        },
        OrthoRole: {
            value: [ "median", "lowerQuartil", "upperQuartil", "minimum", "maximum" ]
        },
        BoxSizeRatio: {
            resolve: "_resolveFull",
            cast: function(value) {
                value = pvc.castNumber(value);
                null == value ? value = 1 : .05 > value ? value = .05 : value > 1 && (value = 1);
                return value;
            },
            value: 1 / 3
        },
        BoxSizeMax: {
            resolve: "_resolveFull",
            data: {
                resolveV1: function(optionInfo) {
                    this._specifyChartOption(optionInfo, "maxBoxSize");
                    return !0;
                }
            },
            cast: function(value) {
                value = pvc.castNumber(value);
                null == value ? value = 1/0 : 1 > value && (value = 1);
                return value;
            },
            value: 1/0
        }
    });
    def.type("pvc.visual.BulletPlot", pvc.visual.Plot).add({
        type: "bullet",
        _getOptionsDefinition: function() {
            return pvc.visual.BulletPlot.optionsDef;
        }
    });
    pvc.visual.BulletPlot.optionsDef = def.create(pvc.visual.Plot.optionsDef, {
        ValuesVisible: {
            value: !0
        },
        ColorRole: {
            value: null
        }
    });
    def.type("pvc.visual.TreemapPlot", pvc.visual.Plot).add({
        type: "treemap",
        _getOptionsDefinition: function() {
            return pvc.visual.TreemapPlot.optionsDef;
        },
        collectDataCells: function(dataCells) {
            this.base(dataCells);
            var sizeRoleName = this.option("SizeRole");
            sizeRoleName && dataCells.push(new pvc.visual.DataCell(this, "size", this.option("SizeAxis") - 1, sizeRoleName, this.option("DataPart")));
        }
    });
    pvc.visual.TreemapPlot.optionsDef = def.create(pvc.visual.Plot.optionsDef, {
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
    });
    def.type("pvc.visual.SunburstPlot", pvc.visual.Plot).add({
        type: "sunburst",
        _getOptionsDefinition: function() {
            return pvc.visual.SunburstPlot.optionsDef;
        },
        collectDataCells: function(dataCells) {
            this.base(dataCells);
            var sizeRoleName = this.option("SizeRole");
            sizeRoleName && dataCells.push(new pvc.visual.DataCell(this, "size", this.option("SizeAxis") - 1, sizeRoleName, this.option("DataPart")));
        }
    });
    pvc.visual.SunburstPlot.optionsDef = def.create(pvc.visual.Plot.optionsDef, {
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
    });
    def.type("pvc.Abstract").init(function() {
        this._syncLog();
    }).add({
        invisibleLineWidth: .001,
        defaultLineWidth: 1.5,
        _logInstanceId: null,
        _syncLog: function() {
            if (pvc.debug && "undefined" != typeof console) {
                var logId = this._getLogInstanceId();
                [ "log", "info", [ "trace", "debug" ], "error", "warn", [ "group", "groupCollapsed" ], "groupEnd" ].forEach(function(ps) {
                    ps = ps instanceof Array ? ps : [ ps, ps ];
                    pvc_installLog(this, "_" + ps[0], ps[1], logId);
                }, this);
            }
        },
        _getLogInstanceId: function() {
            return this._logInstanceId || (this._logInstanceId = this._processLogInstanceId(this._createLogInstanceId()));
        },
        _createLogInstanceId: function() {
            return "" + this.constructor;
        },
        _processLogInstanceId: function(logInstanceId) {
            var L = 30, s = logInstanceId.substr(0, L);
            s.length < L && (s += def.array.create(L - s.length, " ").join(""));
            return "[" + s + "]";
        }
    });
    def.scope(function() {
        var o = pvc.Abstract.prototype, syncLogHook = function() {
            this._syncLog();
        };
        [ "log", "info", "trace", "error", "warn", "group", "groupEnd" ].forEach(function(p) {
            o["_" + p] = syncLogHook;
        });
    });
    def.type("pvc.BaseChart", pvc.Abstract).add(pvc.visual.Interactive).init(function(options) {
        var originalOptions = options, parent = this.parent = def.get(options, "parent") || null;
        parent ? options || def.fail.argumentRequired("options") : options = def.mixin.copy({}, this.defaults, options);
        this.options = options;
        if (parent) {
            this.root = parent.root;
            this.smallColIndex = options.smallColIndex;
            this.smallRowIndex = options.smallRowIndex;
        } else this.root = this;
        this.base();
        pvc.debug >= 3 && this._info("NEW CHART\n" + pvc.logSeparator.replace(/-/g, "=") + "\n  DebugLevel: " + pvc.debug);
        if (pvc.debug >= 3 && !parent && originalOptions) {
            this._info("OPTIONS:\n", originalOptions);
            pvc.debug >= 5 && this._trace(pvc.stringify(options, {
                ownOnly: !1,
                funs: !0
            }));
        }
        parent && parent._addChild(this);
        this._constructData(options);
        this._constructVisualRoles(options);
    }).add({
        _disposed: !1,
        _animatable: !1,
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
        compatVersion: function(options) {
            return (options || this.options).compatVersion;
        },
        _createLogInstanceId: function() {
            return "" + this.constructor + this._createLogChildSuffix();
        },
        _createLogChildSuffix: function() {
            return this.parent ? " (" + (this.smallRowIndex + 1) + "," + (this.smallColIndex + 1) + ")" : "";
        },
        _addChild: function(childChart) {
            childChart.parent === this || def.assert("Not a child of this chart.");
            this.children.push(childChart);
        },
        _create: function(keyArgs) {
            this._createPhase1(keyArgs);
            this._createPhase2(keyArgs);
        },
        _createPhase1: function(keyArgs) {
            this._createVersion++;
            this.isCreated = !1;
            pvc.debug >= 3 && this._log("Creating");
            this.children = [];
            this.parent || pvc.removeTipsyLegends();
            this._processOptions();
            this.parent || this._checkNoDataI();
            if (!this.parent && !this.data) {
                this._initVisualRoles();
                this._bindVisualRolesPreI();
                this._complexTypeProj = this._createComplexTypeProject();
                this._bindVisualRolesPreII();
            }
            this._initData(keyArgs);
            this.parent || this._checkNoDataII();
            var hasMultiRole = this.visualRoles.multiChart.isBound(), chartLevel = this._chartLevel();
            this._initPlots(hasMultiRole);
            this._initAxes(hasMultiRole);
            hasMultiRole && !this.parent && this._initMultiCharts();
            if (!this.parent) {
                this._interpolate(hasMultiRole);
                this._generateTrends(hasMultiRole);
            }
            this._setAxesScales(chartLevel);
        },
        _createPhase2: function() {
            var hasMultiRole = this.visualRoles.multiChart.isBound();
            this._initChartPanels(hasMultiRole);
            this.isCreated = !0;
        },
        _setSmallLayout: function(keyArgs) {
            if (keyArgs) {
                var basePanel = this.basePanel;
                this._setProp("left", keyArgs) | this._setProp("top", keyArgs) && basePanel && def.set(basePanel.position, "left", this.left, "top", this.top);
                this._setProp("width", keyArgs) | this._setProp("height", keyArgs) && basePanel && (basePanel.size = new pvc_Size(this.width, this.height));
                this._setProp("margins", keyArgs) && basePanel && (basePanel.margins = new pvc_Sides(this.margins));
                this._setProp("paddings", keyArgs) && basePanel && (basePanel.paddings = new pvc_Sides(this.paddings));
            }
        },
        _setProp: function(p, keyArgs) {
            var v = keyArgs[p];
            if (null != v) {
                this[p] = v;
                return !0;
            }
        },
        _processOptions: function() {
            var options = this.options;
            if (!this.parent) {
                this.width = options.width;
                this.height = options.height;
                this.margins = options.margins;
                this.paddings = options.paddings;
            }
            this.compatVersion() <= 1 && (options.plot2 = this._allowV1SecondAxis && !!options.secondAxis);
            this._processOptionsCore(options);
            this._processExtensionPoints();
            return options;
        },
        _processOptionsCore: function(options) {
            if (this.parent) {
                this._ibits = this.parent._ibits;
                this._tooltipOptions = this.parent._tooltipOptions;
            } else {
                var interactive = "batik" !== pv.renderer();
                if (interactive) {
                    interactive = options.interactive;
                    null == interactive && (interactive = !0);
                }
                var ibits;
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
                } else ibits = 0;
                this._ibits = ibits;
            }
        },
        _tooltipDefaults: {
            gravity: "s",
            delayIn: 200,
            delayOut: 80,
            offset: 2,
            opacity: .9,
            html: !0,
            fade: !0,
            useCorners: !1,
            arrowVisible: !0,
            followMouse: !1,
            format: void 0
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
                tipOptions || (tipOptions = {});
                isV1Compat && this._importV1TooltipOptions(tipOptions, options);
                def.eachOwn(this._tooltipDefaults, function(dv, p) {
                    var value = options["tooltip" + def.firstUpperCase(p)];
                    void 0 !== value ? tipOptions[p] = value : void 0 === tipOptions[p] && (tipOptions[p] = dv);
                });
            } else tipOptions = {};
            this._tooltipOptions = tipOptions;
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
        render: function(bypassAnimation, recreate, reloadData) {
            var hasError;
            pvc.debug > 1 && pvc.group("CCC RENDER");
            this._suspendSelectionUpdate();
            try {
                this.useTextMeasureCache(function() {
                    try {
                        for (;;) {
                            !this.isCreated || recreate ? this._create({
                                reloadData: reloadData
                            }) : !this.parent && this.isCreated && pvc.removeTipsyLegends();
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
                        if (e instanceof NoDataException) {
                            pvc.debug > 1 && this._log("No data found.");
                            this._addErrorPanelMessage("No data found", !0);
                        } else {
                            hasError = !0;
                            pvc.logError(e.message);
                            pvc.debug > 0 && this._addErrorPanelMessage("Error: " + e.message, !1);
                        }
                    }
                });
            } finally {
                hasError || this._resumeSelectionUpdate();
                pvc.debug > 1 && pvc.groupEnd();
            }
            return this;
        },
        _addErrorPanelMessage: function(text, isNoData) {
            var options = this.options, pvPanel = new pv.Panel().canvas(options.canvas).width(this.width).height(this.height), pvMsg = pvPanel.anchor("center").add(pv.Label).text(text);
            isNoData && this.extend(pvMsg, "noDataMessage");
            pvPanel.render();
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
        animatable: function() {
            return this._animatable && this.base();
        },
        isOrientationVertical: function(orientation) {
            return (orientation || this.options.orientation) === pvc.orientation.vertical;
        },
        isOrientationHorizontal: function(orientation) {
            return (orientation || this.options.orientation) === pvc.orientation.horizontal;
        },
        dispose: function() {
            this._disposed || (this._disposed = !0);
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
            v1StyleTooltipFormat: function(s, c, v, datum) {
                return s + ", " + c + ":  " + this.chart.options.valueFormat(v) + (datum && datum.percent ? " (" + datum.percent.label + ")" : "");
            },
            valueFormat: def.scope(function() {
                var pvFormat = pv.Format.number().fractionDigits(0, 2);
                return function(d) {
                    return pvFormat.format(d);
                };
            }),
            percentValueFormat: def.scope(function() {
                var pvFormat = pv.Format.number().fractionDigits(0, 1);
                return function(d) {
                    return pvFormat.format(100 * d) + "%";
                };
            }),
            clickable: !1,
            doubleClickMaxDelay: 300,
            hoverable: !1,
            selectable: !1,
            selectionMode: "rubberband",
            ctrlSelectMode: !0,
            clearSelectionMode: "emptySpaceClick",
            compatVersion: 1/0
        }
    });
    pvc.BaseChart.add({
        visualRoles: null,
        visualRoleList: null,
        _serRole: null,
        _dataPartRole: null,
        _measureVisualRoles: null,
        visualRole: function(roleName) {
            var role = def.getOwn(this.visualRoles, roleName);
            if (!role) throw def.error.operationInvalid("roleName", "There is no visual role with name '{0}'.", [ roleName ]);
            return role;
        },
        measureVisualRoles: function() {
            return this._measureVisualRoles;
        },
        measureDimensionsNames: function() {
            return def.query(this._measureVisualRoles).select(function(role) {
                return role.firstDimensionName();
            }).where(def.notNully).array();
        },
        _constructVisualRoles: function() {
            var parent = this.parent;
            if (parent) {
                this.visualRoles = parent.visualRoles;
                this.visualRoleList = parent.visualRoleList;
                this._measureVisualRoles = parent._measureVisualRoles;
                [ "_multiChartRole", "_serRole", "_colorRole", "_dataPartRole" ].forEach(function(p) {
                    var parentRole = parent[p];
                    parentRole && (this[p] = parentRole);
                }, this);
            } else {
                this.visualRoles = {};
                this.visualRoleList = [];
                this._measureVisualRoles = [];
            }
        },
        _hasDataPartRole: def.fun.constant(!1),
        _getSeriesRoleSpec: def.fun.constant(null),
        _getColorRoleSpec: def.fun.constant(null),
        _addVisualRole: function(name, keyArgs) {
            keyArgs = def.set(keyArgs, "index", this.visualRoleList.length);
            var role = new pvc.visual.Role(name, keyArgs);
            this.visualRoleList.push(role);
            this.visualRoles[name] = role;
            role.isMeasure && this._measureVisualRoles.push(role);
            return role;
        },
        _initVisualRoles: function() {
            this._multiChartRole = this._addVisualRole("multiChart", {
                defaultDimension: "multiChart*",
                requireIsDiscrete: !0
            });
            this._hasDataPartRole() && (this._dataPartRole = this._addVisualRole("dataPart", {
                defaultDimension: "dataPart",
                requireSingleDimension: !0,
                requireIsDiscrete: !0,
                dimensionDefaults: {
                    isHidden: !0,
                    comparer: def.compare
                }
            }));
            var serRoleSpec = this._getSeriesRoleSpec();
            serRoleSpec && (this._serRole = this._addVisualRole("series", serRoleSpec));
            var colorRoleSpec = this._getColorRoleSpec();
            colorRoleSpec && (this._colorRole = this._addVisualRole("color", colorRoleSpec));
        },
        _assertUnboundRoleIsOptional: function(role) {
            if (role.isRequired) throw def.error.operationInvalid("Chart type requires unassigned role '{0}'.", [ role.name ]);
        },
        _bindVisualRolesPreI: function() {
            def.eachOwn(this.visualRoles, function(role) {
                role.setIsReversed(!1);
            });
            var sourcedRoles = [], options = this.options, roleOptions = options.visualRoles;
            this.visualRoleList.forEach(function(role) {
                var name = role.name, roleSpec = options[name + "Role"];
                if (void 0 !== roleSpec) {
                    roleOptions || (roleOptions = options.visualRoles = {});
                    void 0 === roleOptions[name] && (roleOptions[name] = roleSpec);
                }
            });
            var dimsBoundToSingleRole;
            if (roleOptions) {
                dimsBoundToSingleRole = {};
                var rolesWithOptions = def.query(def.keys(roleOptions)).select(this.visualRole, this).array();
                rolesWithOptions.sort(function(a, b) {
                    return a.index - b.index;
                });
                rolesWithOptions.forEach(function(role) {
                    var groupingSpec, sourceRoleName, name = role.name, roleSpec = roleOptions[name];
                    if (def.object.is(roleSpec)) {
                        def.nullyTo(roleSpec.isReversed, !1) && role.setIsReversed(!0);
                        sourceRoleName = roleSpec.from;
                        if (sourceRoleName && sourceRoleName !== name) {
                            var sourceRole = this.visualRoles[sourceRoleName] || def.fail.operationInvalid("Source role '{0}' is not supported by the chart type.", [ sourceRoleName ]);
                            role.setSourceRole(sourceRole);
                            sourcedRoles.push(role);
                        } else groupingSpec = roleSpec.dimensions;
                    } else groupingSpec = roleSpec;
                    if (void 0 !== groupingSpec) {
                        groupingSpec || this._assertUnboundRoleIsOptional(role);
                        var grouping = pvc.data.GroupingSpec.parse(groupingSpec);
                        role.preBind(grouping);
                        grouping.dimensions().each(function(groupDimSpec) {
                            def.hasOwn(dimsBoundToSingleRole, groupDimSpec.name) ? delete dimsBoundToSingleRole[groupDimSpec.name] : dimsBoundToSingleRole[groupDimSpec.name] = role;
                        });
                    }
                }, this);
            }
            this._sourcedRoles = sourcedRoles;
            this._dimsBoundToSingleRole = dimsBoundToSingleRole;
        },
        _bindVisualRolesPreII: function() {
            var dimsBoundToSingleRole = this._dimsBoundToSingleRole;
            if (dimsBoundToSingleRole) {
                delete this._dimsBoundToSingleRole;
                def.eachOwn(dimsBoundToSingleRole, this._setRoleBoundDimensionDefaults, this);
            }
            var sourcedRoles = this._sourcedRoles;
            delete this._sourcedRoles;
            def.query(this.visualRoleList).where(function(role) {
                return role.defaultSourceRoleName && !role.sourceRole && !role.isPreBound();
            }).each(function(role) {
                var sourceRole = this.visualRoles[role.defaultSourceRoleName];
                if (sourceRole) {
                    role.setSourceRole(sourceRole, !0);
                    sourcedRoles.push(role);
                }
            }, this);
            sourcedRoles.forEach(function(role) {
                var sourceRole = role.sourceRole;
                sourceRole.isReversed && role.setIsReversed(!role.isReversed);
                !role.defaultDimensionName && sourceRole.isPreBound() && role.preBind(sourceRole.preBoundGrouping());
            });
        },
        _setRoleBoundDimensionDefaults: function(role, dimName) {
            this._complexTypeProj.setDimDefaults(dimName, role.dimensionDefaults);
        },
        _bindVisualRolesPostI: function() {
            function markDimBoundTo(dimName, role) {
                def.array.lazy(boundDimTypes, dimName).push(role);
            }
            function dimIsDefined(dimName) {
                return complexTypeProj.hasDim(dimName);
            }
            function preBindRoleTo(role, dimNames) {
                def.array.is(dimNames) ? dimNames.forEach(function(dimName) {
                    markDimBoundTo(dimName, role);
                }) : markDimBoundTo(dimNames, role);
                role.setSourceRole(null);
                role.preBind(pvc.data.GroupingSpec.parse(dimNames));
            }
            function preBindRoleToGroupDims(role, groupDimNames) {
                groupDimNames.length && (role.requireSingleDimension ? preBindRoleTo(role, groupDimNames[0]) : preBindRoleTo(role, groupDimNames));
            }
            function preBindRoleToNewDim(role, dimName) {
                complexTypeProj.setDim(dimName, {
                    isHidden: !0
                });
                preBindRoleTo(role, dimName);
            }
            function roleIsUnbound(role) {
                me._assertUnboundRoleIsOptional(role);
                role.bind(null);
                role.setSourceRole(null);
            }
            function markPreBoundRoleDims(role) {
                role.preBoundGrouping().dimensionNames().forEach(markDimBoundTo);
            }
            function autoBindUnboundRole(role) {
                if (!role.sourceRole || role.isDefaultSourceRole) {
                    var dimName = role.defaultDimensionName;
                    if (dimName) {
                        var match = dimName.match(/^(.*?)(\*)?$/) || def.fail.argumentInvalid("defaultDimensionName"), defaultName = match[1], greedy = match[2];
                        if (greedy) {
                            var groupDimNames = complexTypeProj.groupDimensionsNames(defaultName);
                            if (groupDimNames) {
                                preBindRoleToGroupDims(role, groupDimNames);
                                return;
                            }
                        } else if (dimIsDefined(defaultName)) {
                            preBindRoleTo(role, defaultName);
                            return;
                        }
                        role.autoCreateDimension ? preBindRoleToNewDim(role, defaultName) : role.sourceRole ? unboundSourcedRoles.push(role) : roleIsUnbound(role);
                    } else role.sourceRole ? unboundSourcedRoles.push(role) : roleIsUnbound(role);
                } else unboundSourcedRoles.push(role);
            }
            function tryPreBindSourcedRole(role) {
                var sourceRole = role.sourceRole;
                sourceRole.isPreBound() ? role.preBind(sourceRole.preBoundGrouping()) : roleIsUnbound(role);
            }
            var me = this, complexTypeProj = me._complexTypeProj, boundDimTypes = {}, unboundSourcedRoles = [];
            def.query(me.visualRoleList).where(function(role) {
                return role.isPreBound();
            }).each(markPreBoundRoleDims);
            def.query(me.visualRoleList).where(function(role) {
                return !role.isPreBound();
            }).each(autoBindUnboundRole);
            unboundSourcedRoles.forEach(tryPreBindSourcedRole);
            def.query(def.ownKeys(boundDimTypes)).where(function(dimName) {
                return 1 === boundDimTypes[dimName].length;
            }).each(function(dimName) {
                var singleRole = boundDimTypes[dimName][0];
                me._setRoleBoundDimensionDefaults(singleRole, dimName);
            });
        },
        _bindVisualRolesPostII: function(complexType) {
            def.query(this.visualRoleList).where(function(role) {
                return role.isPreBound();
            }).each(function(role) {
                role.postBind(complexType);
            });
        },
        _logVisualRoles: function() {
            var names = def.ownKeys(this.visualRoles), maxLen = Math.max(10, def.query(names).select(function(s) {
                return s.length;
            }).max()), header = def.string.padRight("VisualRole", maxLen) + " < Dimension(s)", out = [ "VISUAL ROLES MAP SUMMARY", pvc.logSeparator, header, def.string.padRight("", maxLen + 1, "-") + "+--------------" ];
            def.eachOwn(this.visualRoles, function(role, name) {
                out.push(def.string.padRight(name, maxLen) + " | " + (role.grouping || "-"));
            });
            out.push("");
            this._log(out.join("\n"));
        },
        _getDataPartDimName: function() {
            var role = this._dataPartRole;
            if (role) {
                if (role.isBound()) return role.firstDimensionName();
                var preGrouping = role.preBoundGrouping();
                return preGrouping ? preGrouping.firstDimensionName() : role.defaultDimensionName;
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
        _trendable: !1,
        _interpolatable: !1,
        _constructData: function(options) {
            this.parent && (this.dataEngine = this.data = options.data || def.fail.argumentRequired("options.data"));
        },
        _checkNoDataI: function() {
            if (!this.allowNoData && !this.resultset.length) throw new NoDataException();
        },
        _checkNoDataII: function() {
            if (!(this.allowNoData || this.data && this.data.count())) {
                this.data = null;
                throw new NoDataException();
            }
        },
        _initData: function(ka) {
            if (!this.parent) {
                var data = this.data;
                if (data) if (def.get(ka, "reloadData", !0)) this._onReloadData(); else {
                    data.disposeChildren();
                    data.clearVirtuals();
                } else this._onLoadData();
            }
            delete this._partsDataCache;
            delete this._visibleDataCache;
            pvc.debug >= 3 && this._log(this.data.getInfo());
        },
        _onLoadData: function() {
            var data = this.data, translation = this._translation;
            !data && !translation || def.assert("Invalid state.");
            var options = this.options, dataPartDimName = this._getDataPartDimName(), complexTypeProj = this._complexTypeProj || def.assert("Invalid state."), translOptions = this._createTranslationOptions(dataPartDimName);
            translation = this._translation = this._createTranslation(translOptions);
            if (pvc.debug >= 3) {
                this._log(translation.logSource());
                this._log(translation.logTranslatorType());
            }
            translation.configureType();
            dataPartDimName && !complexTypeProj.isReadOrCalc(dataPartDimName) && this._addDefaultDataPartCalculation(dataPartDimName);
            pvc.debug >= 3 && this._log(translation.logVItem());
            this._bindVisualRolesPostI();
            var complexType = new pvc.data.ComplexType();
            complexTypeProj.configureComplexType(complexType, translOptions);
            this._bindVisualRolesPostII(complexType);
            pvc.debug >= 10 && this._log(complexType.describe());
            pvc.debug >= 3 && this._logVisualRoles();
            data = this.dataEngine = this.data = new pvc.data.Data({
                type: complexType,
                labelSep: options.groupedLabelSep,
                keySep: translOptions.separator
            });
            var loadKeyArgs = {
                where: this._getLoadFilter(),
                isNull: this._getIsNullDatum()
            }, resultQuery = translation.execute(data);
            data.load(resultQuery, loadKeyArgs);
        },
        _onReloadData: function() {
            var data = this.data, translation = this._translation;
            data && translation || def.assert("Invalid state.");
            this.options;
            translation.setSource(this.resultset);
            pvc.debug >= 3 && this._log(translation.logSource());
            var loadKeyArgs = {
                where: this._getLoadFilter(),
                isNull: this._getIsNullDatum()
            }, resultQuery = translation.execute(data);
            data.load(resultQuery, loadKeyArgs);
        },
        _createComplexTypeProject: function() {
            var options = this.options, complexTypeProj = new pvc.data.ComplexTypeProject(options.dimensionGroups), userDimsSpec = options.dimensions;
            for (var dimName in userDimsSpec) complexTypeProj.setDim(dimName, userDimsSpec[dimName]);
            var dataPartDimName = this._getDataPartDimName();
            if (dataPartDimName) {
                complexTypeProj.setDim(dataPartDimName);
                this._addPlot2SeriesDataPartCalculation(complexTypeProj, dataPartDimName);
            }
            var calcSpecs = options.calculations;
            calcSpecs && calcSpecs.forEach(function(calcSpec) {
                complexTypeProj.setCalc(calcSpec);
            });
            return complexTypeProj;
        },
        _getLoadFilter: function() {},
        _getIsNullDatum: function() {
            var measureDimNames = this.measureDimensionsNames(), M = measureDimNames.length;
            return M ? function(datum) {
                for (var atoms = datum.atoms, i = 0; M > i; i++) if (null != atoms[measureDimNames[i]].value) return !1;
                return !0;
            } : void 0;
        },
        _createTranslation: function(translOptions) {
            var TranslationClass = this._getTranslationClass(translOptions);
            return new TranslationClass(this, this._complexTypeProj, this.resultset, this.metadata, translOptions);
        },
        _getTranslationClass: function(translOptions) {
            return translOptions.crosstabMode ? pvc.data.CrosstabTranslationOper : pvc.data.RelationalTranslationOper;
        },
        _createTranslationOptions: function(dataPartDimName) {
            var options = this.options, dataOptions = options.dataOptions || {}, dataSeparator = options.dataSeparator;
            void 0 === dataSeparator && (dataSeparator = dataOptions.separator);
            dataSeparator || (dataSeparator = "~");
            var dataMeasuresInColumns = options.dataMeasuresInColumns;
            void 0 === dataMeasuresInColumns && (dataMeasuresInColumns = dataOptions.measuresInColumns);
            var dataCategoriesCount = options.dataCategoriesCount;
            void 0 === dataCategoriesCount && (dataCategoriesCount = dataOptions.categoriesCount);
            var dataIgnoreMetadataLabels = options.dataIgnoreMetadataLabels;
            void 0 === dataIgnoreMetadataLabels && (dataIgnoreMetadataLabels = dataOptions.ignoreMetadataLabels);
            var valueFormatter, plot2 = options.plot2, valueFormat = options.valueFormat;
            valueFormat && valueFormat !== this.defaults.valueFormat && (valueFormatter = function(v) {
                return null != v ? valueFormat(v) : "";
            });
            var plot2Series, plot2DataSeriesIndexes;
            if (plot2) {
                if (this._allowV1SecondAxis && this.compatVersion() <= 1) plot2DataSeriesIndexes = options.secondAxisIdx; else {
                    plot2Series = null != this._serRole && options.plot2Series && def.array.as(options.plot2Series);
                    if (!plot2Series || !plot2Series.length) {
                        plot2Series = null;
                        plot2DataSeriesIndexes = options.plot2SeriesIndexes;
                    }
                }
                plot2Series || (plot2DataSeriesIndexes = pvc.parseDistinctIndexArray(plot2DataSeriesIndexes, -1/0) || -1);
            }
            return {
                compatVersion: this.compatVersion(),
                plot2DataSeriesIndexes: plot2DataSeriesIndexes,
                seriesInRows: options.seriesInRows,
                crosstabMode: options.crosstabMode,
                isMultiValued: options.isMultiValued,
                dataPartDimName: dataPartDimName,
                dimensionGroups: options.dimensionGroups,
                dimensions: options.dimensions,
                readers: options.readers,
                measuresIndexes: options.measuresIndexes,
                multiChartIndexes: options.multiChartIndexes,
                separator: dataSeparator,
                measuresInColumns: dataMeasuresInColumns,
                categoriesCount: dataCategoriesCount,
                measuresIndex: dataOptions.measuresIndex || dataOptions.measuresIdx,
                measuresCount: dataOptions.measuresCount || dataOptions.numMeasures,
                isCategoryTimeSeries: options.timeSeries,
                timeSeriesFormat: options.timeSeriesFormat,
                valueNumberFormatter: valueFormatter,
                ignoreMetadataLabels: dataIgnoreMetadataLabels
            };
        },
        _addPlot2SeriesDataPartCalculation: function(complexTypeProj, dataPartDimName) {
            if (!(this.compatVersion() <= 1)) {
                var options = this.options, serRole = this._serRole, plot2Series = null != serRole && options.plot2 && options.plot2Series && def.array.as(options.plot2Series);
                if (plot2Series && plot2Series.length) {
                    var dimNames, dataPartDim, part1Atom, part2Atom, inited = !1, plot2SeriesSet = def.query(plot2Series).uniqueIndex();
                    complexTypeProj.setCalc({
                        names: dataPartDimName,
                        calculation: function(datum, atoms) {
                            if (!inited) {
                                if (serRole.isBound()) {
                                    dimNames = serRole.grouping.dimensionNames();
                                    dataPartDim = datum.owner.dimensions(dataPartDimName);
                                }
                                inited = !0;
                            }
                            if (dataPartDim) {
                                var seriesKey = pvc.data.Complex.compositeKey(datum, dimNames);
                                atoms[dataPartDimName] = def.hasOwnProp.call(plot2SeriesSet, seriesKey) ? part2Atom || (part2Atom = dataPartDim.intern("1")) : part1Atom || (part1Atom = dataPartDim.intern("0"));
                            }
                        }
                    });
                }
            }
        },
        _addDefaultDataPartCalculation: function(dataPartDimName) {
            var dataPartDim, part1Atom;
            this._complexTypeProj.setCalc({
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
            var partRole = this._dataPartRole;
            if (!partRole || !partRole.isBound()) return baseData;
            var cacheKey = "\x00" + baseData.id + ":" + def.nullyTo(dataPartValues, ""), partitionedDataCache = def.lazy(this, "_partsDataCache"), partData = partitionedDataCache[cacheKey];
            if (!partData) {
                partData = this._createPartData(baseData, partRole, dataPartValues);
                partitionedDataCache[cacheKey] = partData;
            }
            return partData;
        },
        _createPartData: function(baseData, partRole, dataPartValues) {
            var dataPartDimName = partRole.firstDimensionName(), dataPartAtoms = baseData.dimensions(dataPartDimName).getDistinctAtoms(def.array.to(dataPartValues)), where = data_whereSpecPredicate([ def.set({}, dataPartDimName, dataPartAtoms) ]);
            return baseData.where(null, {
                where: where
            });
        },
        visibleData: function(dataPartValue, ka) {
            var baseData = def.get(ka, "baseData") || this.data;
            if (this.parent) {
                ka = ka ? Object.create(ka) : {};
                ka.baseData = baseData;
                return this.root.visibleData(dataPartValue, ka);
            }
            var inverted = !!def.get(ka, "inverted", !1), ignoreNulls = !(!this.options.ignoreNulls && !def.get(ka, "ignoreNulls", !0)), key = "\x00" + baseData.id + "|" + inverted + "|" + ignoreNulls + "|" + (null != dataPartValue ? dataPartValue : null), cache = def.lazy(this, "_visibleDataCache"), data = cache[key];
            if (!data) {
                var partData = this.partData(dataPartValue, baseData);
                ka = ka ? Object.create(ka) : {};
                ka.visible = !0;
                ka.isNull = ignoreNulls ? !1 : null;
                data = cache[key] = this._createVisibleData(partData, ka);
            }
            return data;
        },
        _createVisibleData: function(baseData, ka) {
            var serRole = this._serRole;
            return serRole && serRole.isBound() ? serRole.flatten(baseData, ka) : baseData.where(null, ka);
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
        _interpolate: function(hasMultiRole) {
            if (this._interpolatable) {
                var dataCells = def.query(this.axesList).selectMany(def.propGet("dataCells")).where(function(dataCell) {
                    var nim = dataCell.nullInterpolationMode;
                    return !!nim && "none" !== nim;
                }).distinct(function(dataCell) {
                    return dataCell.role.name + "|" + (dataCell.dataPartValue || "");
                }).array();
                this._eachLeafDatasAndDataCells(hasMultiRole, dataCells, this._interpolateDataCell, this);
            }
        },
        _generateTrends: function(hasMultiRole) {
            var dataPartDimName = this._getDataPartDimName();
            if (this._trendable && dataPartDimName) {
                var dataCells = def.query(this.axesList).selectMany(def.propGet("dataCells")).where(def.propGet("trend")).distinct(function(dataCell) {
                    return dataCell.role.name + "|" + (dataCell.dataPartValue || "");
                }).array(), newDatums = [];
                this._eachLeafDatasAndDataCells(hasMultiRole, dataCells, function(dataCell, data) {
                    this._generateTrendsDataCell(newDatums, dataCell, data);
                }, this);
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
        _interpolateDataCell: function() {},
        _generateTrendsDataCell: function() {},
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
            this.resultset.length || this._warn("Resultset is empty");
            return this;
        },
        setMetadata: function(metadata) {
            !this.parent || def.fail.operationInvalid("Can only set metadata on root chart.");
            this.metadata = metadata || [];
            this.metadata.length || this._warn("Metadata is empty");
            return this;
        }
    });
    pvc.BaseChart.add({
        _initPlots: function(hasMultiRole) {
            this.plotPanelList = null;
            if (this.parent) {
                var root = this.root;
                this.plots = root.plots;
                this.plotList = root.plotList;
                this.plotsByType = root.plotsByType;
            } else {
                this.plots = {};
                this.plotList = [];
                this.plotsByType = {};
                this._initPlotsCore(hasMultiRole);
            }
        },
        _initPlotsCore: function() {},
        _addPlot: function(plot) {
            var plotsByType = this.plotsByType, plots = this.plots, plotType = plot.type, plotIndex = plot.index, plotName = plot.name, plotId = plot.id;
            if (plotName && def.hasOwn(plots, plotName)) throw def.error.operationInvalid("Plot name '{0}' already taken.", [ plotName ]);
            if (def.hasOwn(plots, plotId)) throw def.error.operationInvalid("Plot id '{0}' already taken.", [ plotId ]);
            var typePlots = def.array.lazy(plotsByType, plotType);
            if (def.hasOwn(typePlots, plotIndex)) throw def.error.operationInvalid("Plot index '{0}' of type '{1}' already taken.", [ plotIndex, plotType ]);
            plot.globalIndex = this.plotList.length;
            typePlots[plotIndex] = plot;
            this.plotList.push(plot);
            plots[plotId] = plot;
            plotName && (plots[plotName] = plot);
        },
        _collectPlotAxesDataCells: function(plot, dataCellsByAxisTypeThenIndex) {
            var dataCells = [];
            plot.collectDataCells(dataCells);
            dataCells.length && def.query(dataCells).where(function(dataCell) {
                return dataCell.role.isBound();
            }).each(function(dataCell) {
                var dataCellsByAxisIndex = def.array.lazy(dataCellsByAxisTypeThenIndex, dataCell.axisType);
                def.array.lazy(dataCellsByAxisIndex, dataCell.axisIndex).push(dataCell);
            });
        },
        _addPlotPanel: function(plotPanel) {
            def.lazy(this, "plotPanels")[plotPanel.plot.id] = plotPanel;
            def.array.lazy(this, "plotPanelList").push(plotPanel);
        },
        _createPlotPanels: function() {
            throw def.error.notImplemented();
        }
    });
    pvc.BaseChart.add({
        colors: null,
        axes: null,
        axesList: null,
        axesByType: null,
        _axisClassByType: {
            color: pvc.visual.ColorAxis,
            size: pvc.visual.SizeAxis,
            base: pvc_CartesianAxis,
            ortho: pvc_CartesianAxis
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
            this.axes = {};
            this.axesList = [];
            this.axesByType = {};
            delete this._rolesColorScale;
            var dataCellsByAxisTypeThenIndex;
            if (this.parent) dataCellsByAxisTypeThenIndex = this.root._dataCellsByAxisTypeThenIndex; else {
                dataCellsByAxisTypeThenIndex = {};
                this.plotList.forEach(function(plot) {
                    this._collectPlotAxesDataCells(plot, dataCellsByAxisTypeThenIndex);
                }, this);
                this._dataCellsByAxisTypeThenIndex = dataCellsByAxisTypeThenIndex;
            }
            var chartLevel = this._chartLevel();
            this._axisCreationOrder.forEach(function(type) {
                if (0 !== (this._axisCreateChartLevel[type] & chartLevel)) {
                    var AxisClass, dataCellsByAxisIndex = dataCellsByAxisTypeThenIndex[type];
                    if (dataCellsByAxisIndex) {
                        AxisClass = this._axisClassByType[type];
                        AxisClass && dataCellsByAxisIndex.forEach(function(dataCells, axisIndex) {
                            new AxisClass(this, type, axisIndex);
                        }, this);
                    } else if (this._axisCreateIfUnbound[type]) {
                        AxisClass = this._axisClassByType[type];
                        AxisClass && new AxisClass(this, type, 0);
                    }
                }
            }, this);
            this.parent && this.root.axesList.forEach(function(axis) {
                def.hasOwn(this.axes, axis.id) || this._addAxis(axis);
            }, this);
            def.eachOwn(dataCellsByAxisTypeThenIndex, function(dataCellsByAxisIndex, type) {
                this._axisCreateChartLevel[type] & chartLevel && dataCellsByAxisIndex.forEach(function(dataCells, index) {
                    var axis = this.axes[pvc.buildIndexedId(type, index)];
                    axis.isBound() || axis.bind(dataCells);
                }, this);
            }, this);
        },
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
            var typeAxes = this.axesByType[type];
            return typeAxes && null != index && +index >= 0 ? typeAxes[index] : void 0;
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
            scale.isNull && pvc.debug >= 3 && this._log(def.format("{0} scale for axis '{1}'- no data", [ axis.scaleType, axis.id ]));
        },
        _setDiscreteAxisScale: function(axis) {
            if ("color" !== axis.type) {
                var values = axis.domainValues(), scale = new pv.Scale.ordinal();
                values.length ? scale.domain(values) : scale.isNull = !0;
                this._describeScale(axis, scale);
                axis.setScale(scale);
            } else this._setDiscreteColorAxisScale(axis);
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
            if ("color" !== axis.type) {
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
                    var originIsZero = axis.option.isDefined("OriginIsZero") && axis.option("OriginIsZero");
                    if (originIsZero) if (0 === dMin) extent.minLocked = !0; else if (0 === dMax) extent.maxLocked = !0; else if (dMin * dMax > 0) {
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
            } else this._setNumericColorAxisScale(axis);
        },
        _warnSingleContinuousValueRole: function(valueRole) {
            valueRole.grouping.isSingleDimension || this._warn("A linear scale can only be obtained for a single dimension role.");
            valueRole.grouping.isDiscrete() && this._warn(def.format("The single dimension of role '{0}' should be continuous.", [ valueRole.name ]));
        },
        _getContinuousVisibleExtentConstrained: function(axis, min, max) {
            var dim, getDim = function() {
                return dim || (dim = this.data.owner.dimensions(axis.role.grouping.firstDimensionName()));
            }, minLocked = !1, maxLocked = !1;
            if (null == min && axis.option.isDefined("FixedMin")) {
                min = axis.option("FixedMin");
                null != min && (min = getDim.call(this).read(min));
                minLocked = null != min;
                if (minLocked) {
                    min = min.value;
                    0 > min && axis.scaleUsesAbs() && (min = -min);
                }
            }
            if (null == max && axis.option.isDefined("FixedMax")) {
                max = axis.option("FixedMax");
                null != max && (max = getDim.call(this).read(max));
                maxLocked = null != max;
                if (maxLocked) {
                    max = max.value;
                    0 > max && axis.scaleUsesAbs() && (max = -max);
                }
            }
            if (null == min || null == max) {
                var baseExtent = this._getContinuousVisibleExtent(axis);
                if (!baseExtent) return null;
                null == min && (min = baseExtent.min);
                null == max && (max = baseExtent.max);
            }
            return {
                min: min,
                max: max,
                minLocked: minLocked,
                maxLocked: maxLocked
            };
        },
        _getContinuousVisibleExtent: function(valueAxis) {
            var dataCells = valueAxis.dataCells;
            return 1 === dataCells.length ? this._getContinuousVisibleCellExtent(valueAxis, dataCells[0]) : def.query(dataCells).select(function(dataCell) {
                return this._getContinuousVisibleCellExtent(valueAxis, dataCell);
            }, this).reduce(pvc.unionExtents, null);
        },
        _getContinuousVisibleCellExtent: function(valueAxis, valueDataCell) {
            var valueRole = valueDataCell.role;
            this._warnSingleContinuousValueRole(valueRole);
            if ("series" === valueRole.name) throw def.error.notImplemented();
            var sumNorm = valueAxis.scaleSumNormalized(), data = this.visibleData(valueDataCell.dataPartValue), dimName = valueRole.firstDimensionName();
            if (sumNorm) {
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
        _setDiscreteColorAxisScale: function(axis) {
            var domainValues = axis.domainValues(), scale = axis.scheme()(domainValues);
            this._describeScale(axis, scale);
            axis.setScale(scale, !0);
            this._onColorAxisScaleSet(axis);
        },
        _setNumericColorAxisScale: function(axis) {
            if (1 !== axis.dataCells.length) throw def.error("Can't handle multiple continuous datacells in color axis.");
            this._warnSingleContinuousValueRole(axis.role);
            var visibleDomainData = this.visibleData(axis.dataCell.dataPartValue), normByCateg = axis.option("NormByCategory"), scaleOptions = {
                type: axis.option("ScaleType"),
                colors: axis.option("Colors")().range(),
                colorDomain: axis.option("Domain"),
                colorMin: axis.option("Min"),
                colorMax: axis.option("Max"),
                colorMissing: axis.option("Missing"),
                data: visibleDomainData,
                colorDimension: axis.role.firstDimensionName(),
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
        _getRoleColorScale: function(roleName) {
            return def.lazy(def.lazy(this, "_rolesColorScale"), roleName, this._createRoleColorScale, this);
        },
        _createRoleColorScale: function(roleName) {
            function addDomainValue(value) {
                var key = "" + value;
                def.hasOwnProp.call(valueToColorMap, key) || (valueToColorMap[key] = scale(value));
            }
            var firstScale, scale, valueToColorMap = {};
            this.axesByType.color.forEach(function(axis) {
                var axisRole = axis.role, isRoleCompatible = axisRole.name === roleName || axisRole.sourceRole && axisRole.sourceRole.name === roleName;
                if (isRoleCompatible && axis.scale && (0 === axis.index || axis.option.isSpecified("Colors") || axis.option.isSpecified("Map"))) {
                    scale = axis.scale;
                    firstScale || (firstScale = scale);
                    axis.domainValues().forEach(addDomainValue);
                }
            }, this);
            if (!firstScale) return pvc.createColorScheme()();
            scale = function(value) {
                var key = "" + value;
                if (def.hasOwnProp.call(valueToColorMap, key)) return valueToColorMap[key];
                var color = firstScale(value);
                valueToColorMap[key] = color;
                return color;
            };
            def.copy(scale, firstScale);
            return scale;
        },
        _onLaidOut: function() {}
    });
    pvc.BaseChart.add({
        basePanel: null,
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
                this._createContent({
                    margins: hasMultiRole ? o.smallContentMargins : o.contentMargins,
                    paddings: hasMultiRole ? o.smallContentPaddings : o.contentPaddings,
                    clickAction: o.clickAction,
                    doubleClickAction: o.doubleClickAction
                });
            }
        },
        _createContent: function() {},
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
            var me = this, o = me.options, title = o.title;
            if (!def.empty(title)) {
                {
                    !me.parent;
                }
                this.titlePanel = new pvc.TitlePanel(me, me.basePanel, {
                    title: title,
                    font: o.titleFont,
                    anchor: o.titlePosition,
                    align: o.titleAlign,
                    alignTo: o.titleAlignTo,
                    offset: o.titleOffset,
                    keepInBounds: o.titleKeepInBounds,
                    margins: o.titleMargins,
                    paddings: o.titlePaddings,
                    titleSize: o.titleSize,
                    titleSizeMax: o.titleSizeMax
                });
            }
        },
        _initLegendPanel: function() {
            var o = this.options;
            if (o.legend) {
                var legend = new pvc.visual.Legend(this, "legend", 0);
                return this.legendPanel = new pvc.LegendPanel(this, this.basePanel, {
                    anchor: legend.option("Position"),
                    align: legend.option("Align"),
                    alignTo: o.legendAlignTo,
                    offset: o.legendOffset,
                    keepInBounds: o.legendKeepInBounds,
                    size: legend.option("Size"),
                    sizeMax: legend.option("SizeMax"),
                    margins: legend.option("Margins"),
                    paddings: legend.option("Paddings"),
                    font: legend.option("Font"),
                    scenes: def.getPath(o, "legend.scenes"),
                    textMargin: o.legendTextMargin,
                    itemPadding: o.legendItemPadding,
                    itemSize: legend.option("ItemSize"),
                    markerSize: o.legendMarkerSize
                });
            }
        },
        _getLegendBulletRootScene: function() {
            return this.legendPanel && this.legendPanel._getBulletRootScene();
        },
        _initMultiChartPanel: function() {
            var basePanel = this.basePanel, options = this.options;
            this._multiChartPanel = new pvc.MultiChartPanel(this, basePanel, {
                margins: options.contentMargins,
                paddings: options.contentPaddings
            });
            this._multiChartPanel.createSmallCharts();
            basePanel._children.unshift(basePanel._children.pop());
        },
        _coordinateSmallChartsLayout: function() {},
        _initLegendScenes: function(legendPanel) {
            var colorAxes = this.axesByType.color;
            if (colorAxes) {
                var _dataPartAtom, _dataPartDimName, _rootScene, legendIndex = 0, me = this, getCellClickMode = function(axis, cellData) {
                    if ("togglevisible" === axis.option("LegendClickMode")) {
                        if (void 0 === _dataPartAtom) {
                            _dataPartAtom = me._getTrendDataPartAtom() || null;
                            _dataPartAtom && (_dataPartDimName = _dataPartAtom.dimension.name);
                        }
                        if (_dataPartAtom && cellData.firstAtoms()[_dataPartDimName] === _dataPartAtom) return "none";
                    }
                }, getRootScene = function() {
                    return _rootScene || (rootScene = legendPanel._getBulletRootScene());
                };
                def.query(colorAxes).where(function(axis) {
                    return axis.option("LegendVisible") && axis.isBound() && axis.isDiscrete();
                }).each(function(axis) {
                    for (var colorScale = axis.scale, cellIndex = -1, dataCells = axis.dataCells, C = dataCells.length; ++cellIndex < C; ) {
                        var dataCell = dataCells[cellIndex], cellData = axis.domainCellData(cellIndex), groupScene = getRootScene().createGroup({
                            source: cellData,
                            colorAxis: axis,
                            clickMode: getCellClickMode(axis, cellData),
                            extensionPrefix: pvc.buildIndexedId("", legendIndex++)
                        });
                        dataCell.legendGroupScene(groupScene);
                        axis.domainCellItems(cellData).forEach(function(itemData) {
                            var itemScene = groupScene.createItem({
                                source: itemData
                            }), itemValue = axis.domainItemValue(itemData);
                            itemScene.color = colorScale(itemValue);
                        });
                    }
                });
            }
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
            this === this.root ? this._updateSelectionSuspendCount > 0 && (--this._updateSelectionSuspendCount || this.updateSelections()) : this.root._resumeSelectionUpdate();
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
            } else this.root.updateSelections();
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
            var components;
            if (this.parent) components = this.parent._components; else {
                var points = this.options.extensionPoints;
                components = {};
                if (points) for (var p in points) {
                    var id, prop, splitIndex = p.indexOf("_");
                    if (splitIndex > 0) {
                        id = p.substring(0, splitIndex);
                        prop = p.substr(splitIndex + 1);
                        if (id && prop) {
                            var component = def.getOwn(components, id) || (components[id] = new def.OrderedMap());
                            component.add(prop, points[p]);
                        }
                    }
                }
            }
            this._components = components;
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
                    var logOut = pvc.debug >= 3 ? [] : null, constOnly = def.get(keyArgs, "constOnly", !1), wrap = mark.wrap, keyArgs2 = {
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
                        v instanceof Array ? mm.apply(mark, v) : mm.call(mark, v);
                    };
                    component.forEach(function(v, m) {
                        if (mark.isLocked && mark.isLocked(m)) logOut && logOut.push(m + ": locked extension point!"); else if (mark.isIntercepted && mark.isIntercepted(m)) logOut && logOut.push(m + ":" + pvc.stringify(v) + " (controlled)"); else {
                            logOut && logOut.push(m + ": " + pvc.stringify(v));
                            v = processValue(v, m);
                            if (void 0 !== v) {
                                var mm = mark[m];
                                "function" == typeof mm ? isRealMark && mark.properties[m] ? mark.intercept(m, v, keyArgs2) : v instanceof Array ? v.forEach(function(vi) {
                                    callMethod(mm, vi);
                                }) : callMethod(mm, v) : mark[m] = v;
                            }
                        }
                    });
                    logOut && (logOut.length ? this._log("Applying Extension Points for: '" + id + "'\n	* " + logOut.join("\n	* ")) : pvc.debug >= 5 && this._log("No Extension Points for: '" + id + "'"));
                }
            } else pvc.debug >= 4 && this._log("Applying Extension Points for: '" + id + "' (target mark does not exist)");
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
        this.sizeMax = new pvc_Size(options && options.sizeMax);
        if (parent) {
            this.parent = parent;
            this.isTopRoot = !1;
            this.isRoot = parent.chart !== chart;
            this.root = this.isRoot ? this : parent.root;
            this.topRoot = parent.topRoot;
            this._ibits = parent._ibits;
            if (this.isRoot) {
                this.position.left = chart.left;
                this.position.top = chart.top;
            }
            parent._addChild(this);
        } else {
            this.parent = null;
            this.root = this;
            this.topRoot = this;
            this.isRoot = !0;
            this.isTopRoot = !0;
            this._ibits = chart._ibits;
        }
        this.data = (this.isRoot ? chart : parent).data;
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
        var I = pvc.visual.Interactive, ibits = this._ibits;
        ibits = def.bit.set(ibits, I.Clickable, chart._ibits & I.Clickable && !!this.clickAction);
        ibits = def.bit.set(ibits, I.DoubleClickable, chart._ibits & I.DoubleClickable && !!this.doubleClickAction);
        this._ibits = ibits;
    }).add({
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
        _createLogInstanceId: function() {
            return "" + this.constructor + this.chart._createLogChildSuffix();
        },
        _getLegendBulletRootScene: function() {
            return this.chart._getLegendBulletRootScene();
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
        layout: function(availableSize, ka) {
            if (!this._layoutInfo || def.get(ka, "force", !1)) {
                var referenceSize = def.get(ka, "referenceSize");
                !referenceSize && availableSize && (referenceSize = def.copyOwn(availableSize));
                var desiredSize = this.size.resolve(referenceSize), sizeMax = this.sizeMax.resolve(referenceSize);
                if (!availableSize) {
                    if (null == desiredSize.width || null == desiredSize.height) throw def.error.operationInvalid("Panel layout without width or height set.");
                    availableSize = def.copyOwn(desiredSize);
                }
                !referenceSize && availableSize && (referenceSize = def.copyOwn(availableSize));
                null != sizeMax.width && availableSize.width > sizeMax.width && (availableSize.width = sizeMax.width);
                null != sizeMax.height && availableSize.height > sizeMax.height && (availableSize.height = sizeMax.height);
                var halfBorder = this.borderWidth / 2, realMargins = (def.get(ka, "margins") || this.margins).resolve(referenceSize), realPaddings = (def.get(ka, "paddings") || this.paddings).resolve(referenceSize), margins = pvc_Sides.inflate(realMargins, halfBorder), paddings = pvc_Sides.inflate(realPaddings, halfBorder), spaceWidth = margins.width + paddings.width, spaceHeight = margins.height + paddings.height, availableClientSize = new pvc_Size(Math.max(availableSize.width - spaceWidth, 0), Math.max(availableSize.height - spaceHeight, 0)), desiredClientSize = def.copyOwn(desiredSize);
                null != desiredClientSize.width && (desiredClientSize.width = Math.max(desiredClientSize.width - spaceWidth, 0));
                null != desiredClientSize.height && (desiredClientSize.height = Math.max(desiredClientSize.height - spaceHeight, 0));
                var prevLayoutInfo = this._layoutInfo || null, canChange = def.get(ka, "canChange", !0), layoutInfo = this._layoutInfo = {
                    canChange: canChange,
                    referenceSize: referenceSize,
                    realMargins: realMargins,
                    realPaddings: realPaddings,
                    borderWidth: this.borderWidth,
                    margins: margins,
                    paddings: paddings,
                    desiredClientSize: desiredClientSize,
                    clientSize: availableClientSize,
                    pageClientSize: prevLayoutInfo ? prevLayoutInfo.pageClientSize : availableClientSize.clone(),
                    previous: prevLayoutInfo
                };
                if (prevLayoutInfo) {
                    delete prevLayoutInfo.previous;
                    delete prevLayoutInfo.pageClientSize;
                }
                var size, clientSize = this._calcLayout(layoutInfo);
                if (clientSize) {
                    layoutInfo.clientSize = clientSize;
                    size = {
                        width: clientSize.width + spaceWidth,
                        height: clientSize.height + spaceHeight
                    };
                } else {
                    size = availableSize;
                    clientSize = availableClientSize;
                }
                this.isVisible = clientSize.width > 0 && clientSize.height > 0;
                delete layoutInfo.desiredClientSize;
                this.width = size.width;
                this.height = size.height;
                !canChange && prevLayoutInfo && delete layoutInfo.previous;
                if (pvc.debug >= 5) {
                    this._log("Size       = " + pvc.stringify(size));
                    this._log("Margins    = " + pvc.stringify(layoutInfo.margins));
                    this._log("Paddings   = " + pvc.stringify(layoutInfo.paddings));
                    this._log("ClientSize = " + pvc.stringify(layoutInfo.clientSize));
                }
                this._onLaidOut();
            }
        },
        _onLaidOut: function() {
            this.isRoot && this.chart._onLaidOut();
        },
        _calcLayout: function(layoutInfo) {
            function doMaxTimes(maxTimes, fun, ctx) {
                for (var index = 0; maxTimes--; ) {
                    if (fun.call(ctx, maxTimes, index) === !1) return !0;
                    index++;
                }
                return !1;
            }
            function layoutCycle(remTimes, iteration) {
                useLog && me._group("LayoutCycle #" + (iteration + 1) + " (remaining: " + remTimes + ")");
                try {
                    var canResize = remTimes > 0;
                    margins = new pvc_Sides(0);
                    remSize = def.copyOwn(clientSize);
                    for (var child, index = 0, count = sideChildren.length; count > index; ) {
                        child = sideChildren[index];
                        useLog && me._group("SIDE Child #" + (index + 1) + " at " + child.anchor);
                        try {
                            if (layoutChild.call(this, child, canResize)) return !0;
                        } finally {
                            useLog && me._groupEnd();
                        }
                        index++;
                    }
                    index = 0;
                    count = fillChildren.length;
                    for (;count > index; ) {
                        child = fillChildren[index];
                        useLog && me._group("FILL Child #" + (index + 1));
                        try {
                            if (layoutChild.call(this, child, canResize)) return !0;
                        } finally {
                            useLog && me._groupEnd();
                        }
                        index++;
                    }
                    return !1;
                } finally {
                    useLog && me._groupEnd();
                }
            }
            function layoutChild(child, canResize) {
                var paddings, resized = !1;
                childKeyArgs.canChange = canResize;
                doMaxTimes(3, function(remTimes, iteration) {
                    useLog && me._group("Attempt #" + (iteration + 1));
                    try {
                        childKeyArgs.paddings = paddings;
                        childKeyArgs.canChange = remTimes > 0;
                        child.layout(new pvc_Size(remSize), childKeyArgs);
                        if (child.isVisible) {
                            resized = checkChildResize.call(this, child, canResize);
                            if (resized) return !1;
                            var requestPaddings = child._layoutInfo.requestPaddings;
                            if (checkPaddingsChanged(paddings, requestPaddings)) {
                                paddings = requestPaddings;
                                if (remTimes > 0) {
                                    paddings = new pvc_Sides(paddings);
                                    useLog && this._log("Child requested paddings change: " + pvc.stringify(paddings));
                                    return !0;
                                }
                                pvc.debug >= 2 && this._warn("Child requests paddings change but iterations limit has been reached.");
                            }
                            positionChild.call(this, child);
                            "fill" !== child.anchor && updateSide.call(this, child);
                        }
                        return !1;
                    } finally {
                        useLog && me._groupEnd();
                    }
                }, this);
                return resized;
            }
            function checkPaddingsChanged(paddings, newPaddings) {
                return newPaddings ? def.query(pvc_Sides.names).each(function(side) {
                    var curPad = paddings && paddings[side] || 0, newPad = newPaddings && newPaddings[side] || 0;
                    return Math.abs(newPad - curPad) >= .1 ? !1 : void 0;
                }) : !1;
            }
            function checkChildResize(child, canResize) {
                var resized = !1, addWidth = child.width - remSize.width;
                if (addWidth > 0) {
                    pvc.debug >= 3 && this._log("Child added width = " + addWidth);
                    if (canResize) {
                        resized = !0;
                        remSize.width += addWidth;
                        clientSize.width += addWidth;
                    } else pvc.debug >= 2 && this._warn("Child wanted more width, but layout iterations limit has been reached.");
                }
                var addHeight = child.height - remSize.height;
                if (addHeight > 0) {
                    pvc.debug >= 3 && this._log("Child added height =" + addHeight);
                    if (canResize) {
                        resized = !0;
                        remSize.height += addHeight;
                        clientSize.height += addHeight;
                    } else pvc.debug >= 2 && this._warn("Child wanted more height, but layout iterations limit has been reached.");
                }
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
                    var lenProp = aolMap[sideo], pageLen = Math.min(remSize[lenProp], layoutInfo.pageClientSize[lenProp]);
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
                useLog = pvc.debug >= 5;
                clientSize = def.copyOwn(layoutInfo.clientSize);
                var childKeyArgs = {
                    force: !0,
                    referenceSize: clientSize
                };
                useLog && me._group("CCC DOCK LAYOUT clientSize = " + pvc.stringify(clientSize));
                try {
                    doMaxTimes(5, layoutCycle, me);
                } finally {
                    useLog && me._groupEnd();
                }
            }
            return clientSize;
        },
        invalidateLayout: function() {
            this._layoutInfo = null;
            this._children && this._children.forEach(function(child) {
                child.invalidateLayout();
            });
        },
        _create: function(force) {
            if (!this.pvPanel || force) {
                var invalidDataError;
                delete this._invalidDataError;
                this.pvPanel = null;
                this.pvRootPanel && (this.pvRootPanel = null);
                delete this._signs;
                try {
                    this.layout();
                } catch (ex) {
                    if (!(ex instanceof InvalidDataException)) throw ex;
                    this._invalidDataError = invalidDataError = ex;
                }
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
                var pvBorderPanel = this.pvPanel, width = this.width - margins.width, height = this.height - margins.height;
                pvBorderPanel.width(width).height(height);
                pvc.debug >= 15 && (margins.width > 0 || margins.height > 0) && (this.isTopRoot ? this.pvRootPanel : this.parent.pvPanel).add(this.type).width(this.width).height(this.height).left(null != this.position.left ? this.position.left : null).right(null != this.position.right ? this.position.right : null).top(null != this.position.top ? this.position.top : null).bottom(null != this.position.bottom ? this.position.bottom : null).strokeStyle("orange").lineWidth(1).strokeDasharray("- .");
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
                if (pvc.debug >= 15) {
                    this.pvPanel.strokeStyle("lightgreen").lineWidth(1).strokeDasharray("- ");
                    this.pvPanel !== pvBorderPanel && pvBorderPanel.strokeStyle("blue").lineWidth(1).strokeDasharray(". ");
                }
                var extensionId = this._getExtensionId();
                new pvc.visual.Panel(this, null, {
                    panel: pvBorderPanel,
                    extensionId: extensionId
                });
                if (!invalidDataError) try {
                    this._createCore(this._layoutInfo);
                } catch (ex) {
                    if (!(ex instanceof InvalidDataException)) throw ex;
                    this._invalidDataError = invalidDataError = ex;
                }
                if (invalidDataError) {
                    var pvMsg = pvBorderPanel.anchor("center").add(pv.Label).text(invalidDataError.message);
                    this.chart.extend(pvMsg, "invalidDataMessage");
                }
                if (this.isTopRoot) {
                    this.chart._multiChartOverflowClipped && this._addMultichartOverflowClipMarker();
                    this._initSelection();
                }
                this.applyExtensions();
                if (this.isRoot && pvc.debug > 5) {
                    var out = [ "SCALES SUMMARY", pvc.logSeparator ];
                    this.chart.axesList.forEach(function(axis) {
                        var scale = axis.scale;
                        if (scale) {
                            var d = scale.domain && scale.domain(), r = scale.range && scale.range();
                            out.push(axis.id);
                            out.push("    domain: " + (d ? pvc.stringify(d) : "?"));
                            out.push("    range : " + (r ? pvc.stringify(r) : "?"));
                        }
                    }, this);
                    this._log(out.join("\n"));
                }
            }
        },
        _creating: function() {
            this._children && this._children.forEach(function(child) {
                child._creating();
            });
        },
        _createCore: function() {
            this._children && this._children.forEach(function(child) {
                child._create();
            });
        },
        render: function(ka) {
            if (!this.isTopRoot) return this.topRoot.render(ka);
            this._create(def.get(ka, "recreate", !1));
            if ((!this.isTopRoot || !this.chart._isMultiChartOverflowClip) && this.isVisible) {
                var pvPanel = this.pvRootPanel;
                if (this._invalidDataError) pvPanel.render(); else {
                    this._onRender();
                    var prevAnimating = (this.chart.options, this._animating), animate = this.chart.animatable();
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
            this._children && this._children.forEach(function(child) {
                child._onRenderEnd(animated);
            });
        },
        renderInteractive: function() {
            if (this.isVisible) {
                var pvMarks = this._getSelectableMarks();
                if (pvMarks && pvMarks.length) pvMarks.forEach(function(pvMark) {
                    pvMark.render();
                }); else if (!this._children) {
                    this.pvPanel.render();
                    return;
                }
                this._children && this._children.forEach(function(child) {
                    child.renderInteractive();
                });
            }
        },
        _getSelectableMarks: function() {
            return this._rubberSelectableMarks;
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
            return this.isAnchorTopOrBottom() ? new pvc_Size(size.width, Math.min(size.height, anchorLength)) : new pvc_Size(Math.min(size.width, anchorLength), size.height);
        },
        applyExtensions: function() {
            this._signs && this._signs.forEach(function(sign) {
                sign.applyExtensions();
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
                var role = this.chart.visualRoles[this._v1DimRoleName[v1Dim]];
                dimName = role ? role.firstDimensionName() : "";
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
        _isTooltipEnabled: function() {
            return !this.selectingByRubberband() && !this.animating();
        },
        _getTooltipFormatter: function(tipOptions) {
            var isV1Compat = this.compatVersion() <= 1, tooltipFormat = tipOptions.format;
            if (!tooltipFormat) {
                if (!isV1Compat) return this._summaryTooltipFormatter;
                tooltipFormat = this.chart.options.v1StyleTooltipFormat;
                if (!tooltipFormat) return;
            }
            return isV1Compat ? function(context) {
                return tooltipFormat.call(context.panel, context.getV1Series(), context.getV1Category(), context.getV1Value() || "", context.getV1Datum());
            } : function(context) {
                return tooltipFormat.call(context, context.scene);
            };
        },
        _summaryTooltipFormatter: function(context) {
            function addDim(escapedDimLabel, label) {
                tooltip.push("<b>" + escapedDimLabel + "</b>: " + (def.html.escape(label) || " - ") + "<br/>");
            }
            function calcPercent(atom, dimName) {
                var pct;
                pct = group ? group.dimensions(dimName).valuePercent(visibleKeyArgs) : data.dimensions(dimName).percent(atom.value, visibleKeyArgs);
                return percentValueFormat(pct);
            }
            var scene = context.scene;
            if (!scene.datum) return "";
            var group = scene.group, isMultiDatumGroup = group && group.count() > 1, firstDatum = scene.datum;
            if (!isMultiDatumGroup && (!firstDatum || firstDatum.isNull)) return "";
            var data = scene.data(), visibleKeyArgs = {
                visible: !0
            }, tooltip = [];
            firstDatum.isInterpolated ? tooltip.push("<i>Interpolation</i>: " + def.html.escape(firstDatum.interpolation) + "<br/>") : firstDatum.isTrend && tooltip.push("<i>" + def.html.escape(firstDatum.trend.label) + "</i><br/>");
            var complexType = data.type, playingPercentMap = context.panel.stacked === !1 ? null : complexType.getPlayingPercentVisualRoleDimensionMap(), percentValueFormat = playingPercentMap ? context.chart.options.percentValueFormat : null, commonAtoms = isMultiDatumGroup ? group.atoms : scene.datum.atoms, commonAtomsKeys = complexType.sortDimensionNames(def.keys(commonAtoms)), anyCommonAtom = !1;
            commonAtomsKeys.forEach(function(dimName) {
                var atom = commonAtoms[dimName], dimType = atom.dimension.type;
                if (!(dimType.isHidden || isMultiDatumGroup && null == atom.value)) {
                    anyCommonAtom = !0;
                    var valueLabel = atom.label;
                    playingPercentMap && playingPercentMap.has(dimName) && (valueLabel += " (" + calcPercent(atom, dimName) + ")");
                    addDim(def.html.escape(atom.dimension.type.label), valueLabel);
                }
            });
            if (isMultiDatumGroup) {
                anyCommonAtom && tooltip.push("<hr />");
                tooltip.push("<b>#</b>: " + group._datums.length + "<br/>");
                complexType.sortDimensionNames(group.freeDimensionsNames()).forEach(function(dimName) {
                    var dim = group.dimensions(dimName);
                    if (!dim.type.isHidden) {
                        var valueLabel, dimLabel = def.html.escape(dim.type.label);
                        if (dim.type.valueType === Number) {
                            valueLabel = dim.format(dim.value(visibleKeyArgs));
                            playingPercentMap && playingPercentMap.has(dimName) && (valueLabel += " (" + calcPercent(null, dimName) + ")");
                            dimLabel = "&sum; " + dimLabel;
                        } else valueLabel = dim.atoms(visibleKeyArgs).map(function(atom) {
                            return atom.label || "- ";
                        }).join(", ");
                        addDim(dimLabel, valueLabel);
                    }
                });
            }
            return '<div style="text-align: left;">' + tooltip.join("\n") + "</div>";
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
            pvc.debug >= 20 && this._log("rubberBand " + pvc.stringify(this.rubberBand));
            ka = Object.create(ka || {});
            ka.toggle = !1;
            var datums = this._getDatumsOnRubberBand(ev, ka);
            if (datums) {
                var chart = this.chart;
                chart._updatingSelections(function() {
                    var clearBefore = !ev.ctrlKey && chart.options.ctrlSelectMode;
                    if (clearBefore) {
                        chart.data.owner.clearSelected();
                        pvc.data.Data.setSelected(datums, !0);
                    } else ka.toggle ? pvc.data.Data.toggleSelected(datums) : pvc.data.Data.setSelected(datums, !0);
                });
            }
        },
        _getDatumsOnRubberBand: function(ev, ka) {
            var datumMap = new def.Map();
            this._getDatumsOnRect(datumMap, this.rubberBand, ka);
            var datums = datumMap.values();
            if (datums.length) {
                datums = this.chart._onUserSelection(datums);
                datums && !datums.length && (datums = null);
            }
            return datums;
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
    }).addStatic({
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
    def.type("pvc.PlotPanel", pvc.BasePanel).init(function(chart, parent, plot, options) {
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
        var roles = this.visualRoles = Object.create(chart.visualRoles), colorRoleName = plot.option("ColorRole");
        roles.color = colorRoleName ? chart.visualRole(colorRoleName) : null;
        this.chart._addPlotPanel(this);
    }).add({
        anchor: "fill",
        visualRoles: null,
        _getExtensionId: function() {
            var extensionIds = [ "chart", "plot" ];
            this.plotName && extensionIds.push(this.plotName);
            return extensionIds;
        },
        defaultLegendGroupScene: function() {
            var colorAxis = this.axes.color;
            return colorAxis && colorAxis.option("LegendVisible") && colorAxis.isBound() ? def.query(colorAxis.dataCells).where(function(dataCell) {
                return dataCell.plot === this.plot;
            }, this).select(function(dataCell) {
                return dataCell.legendGroupScene();
            }).first(def.notNully) : void 0;
        },
        isOrientationVertical: function() {
            return this.orientation === pvc.orientation.vertical;
        },
        isOrientationHorizontal: function() {
            return this.orientation === pvc.orientation.horizontal;
        }
    });
    def.type("pvc.MultiChartPanel", pvc.BasePanel).add({
        anchor: "fill",
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
            return def.set(Object.create(options), "parent", chart, "legend", !1, "titleFont", options.smallTitleFont, "titlePosition", options.smallTitlePosition, "titleAlign", options.smallTitleAlign, "titleAlignTo", options.smallTitleAlignTo, "titleOffset", options.smallTitleOffset, "titleKeepInBounds", options.smallTitleKeepInBounds, "titleMargins", options.smallTitleMargins, "titlePaddings", options.smallTitlePaddings, "titleSize", options.smallTitleSize, "titleSizeMax", options.smallTitleSizeMax);
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
        _getExtensionId: function() {
            return "content";
        },
        _createCore: function(li) {
            var chart = this.chart;
            !chart._isMultiChartOverflowClip || def.assert("Overflow&clip condition should be resolved.");
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
            var requestSize = new pvc_Size(), a = this.anchor, a_width = this.anchorLength(a), a_height = this.anchorOrthoLength(a), textWidth = pv.Text.measureWidth(this.title, this.font) + 2, clientWidth = layoutInfo.clientSize[a_width], desiredWidth = layoutInfo.desiredClientSize[a_width];
            null == desiredWidth ? desiredWidth = textWidth > clientWidth ? clientWidth : textWidth : desiredWidth > clientWidth && (desiredWidth = clientWidth);
            var lines;
            lines = textWidth > desiredWidth ? pvc.text.justify(this.title, desiredWidth, this.font) : this.title ? [ this.title ] : [];
            var lineHeight = pv.Text.fontHeight(this.font), realHeight = lines.length * lineHeight, availableHeight = layoutInfo.clientSize[a_height], desiredHeight = layoutInfo.desiredClientSize[a_height];
            null == desiredHeight ? desiredHeight = realHeight : desiredHeight > availableHeight && (desiredHeight = availableHeight);
            if (realHeight > desiredHeight) {
                var maxLineCount = Math.max(1, Math.floor(desiredHeight / lineHeight));
                if (lines.length > maxLineCount) {
                    var firstCroppedLine = lines[maxLineCount];
                    lines.length = maxLineCount;
                    realHeight = desiredHeight = maxLineCount * lineHeight;
                    var lastLine = lines[maxLineCount - 1] + " " + firstCroppedLine;
                    lines[maxLineCount - 1] = pvc.text.trimToWidthB(desiredWidth, lastLine, this.font, "..");
                }
            }
            layoutInfo.lines = lines;
            layoutInfo.topOffset = (desiredHeight - realHeight) / 2;
            layoutInfo.lineSize = {
                width: desiredWidth,
                height: lineHeight
            };
            layoutInfo.a_width = a_width;
            layoutInfo.a_height = a_height;
            requestSize[a_width] = desiredWidth;
            requestSize[a_height] = desiredHeight;
            return requestSize;
        },
        _createCore: function(layoutInfo) {
            var wrapper, rootScene = this._buildScene(layoutInfo), rotationByAnchor = {
                top: 0,
                right: Math.PI / 2,
                bottom: 0,
                left: -Math.PI / 2
            }, textAlign = pvc.BasePanel.horizontalAlign[this.align], textAnchor = pvc.BasePanel.leftTopAnchor[this.anchor];
            this.compatVersion() <= 1 && (wrapper = function(v1f) {
                return function() {
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
            return this._getBulletRootScene().layout(layoutInfo);
        },
        _createCore: function(layoutInfo) {
            var clientSize = layoutInfo.clientSize, rootScene = this._getBulletRootScene(), itemPadding = rootScene.vars.itemPadding, contentSize = rootScene.vars.contentSize, isHorizontal = this.isAnchorTopOrBottom(), a_top = isHorizontal ? "top" : "left", a_bottom = this.anchorOpposite(a_top), a_width = this.anchorLength(a_top), a_height = this.anchorOrthoLength(a_top), a_center = isHorizontal ? "center" : "middle", a_left = isHorizontal ? "left" : "top", a_right = this.anchorOpposite(a_left), leftOffset = 0;
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
                var itemPadding = clientScene.vars.itemPadding, prevItem = this.sibling();
                return prevItem ? prevItem[a_left] + prevItem[a_width] + itemPadding[a_width] : 0;
            })[a_top](isHorizontal ? function(itemScene) {
                var vars = itemScene.vars;
                return vars.section.size.height / 2 - vars.itemClientSize.height / 2;
            } : 0).height(function(itemScene) {
                return itemScene.vars.itemClientSize.height;
            }).width(isHorizontal ? function(itemScene) {
                return itemScene.vars.itemClientSize.width;
            } : function() {
                return this.parent.width();
            }).def("hidden", "false").fillStyle(function() {
                return "true" == this.hidden() ? "rgba(200,200,200,1)" : "rgba(200,200,200,0.0001)";
            }), pvLegendMarkerPanel = new pvc.visual.Panel(this, pvLegendItemPanel, {
                extensionId: "markerPanel"
            }).pvMark.left(0).top(0).right(null).bottom(null).width(function(itemScene) {
                return itemScene.vars.markerSize;
            }).height(function(itemScene) {
                return itemScene.vars.itemClientSize.height;
            });
            if (pvc.debug >= 20) {
                pvLegendSectionPanel.strokeStyle("red").lineWidth(.5).strokeDasharray(".");
                pvLegendItemPanel.strokeStyle("green").lineWidth(.5).strokeDasharray(".");
                pvLegendMarkerPanel.strokeStyle("blue").lineWidth(.5).strokeDasharray(".");
            }
            rootScene.childNodes.forEach(function(groupScene) {
                var pvGroupPanel = new pvc.visual.Panel(this, pvLegendMarkerPanel).pvMark.visible(function(itemScene) {
                    return itemScene.parent === groupScene;
                });
                groupScene.renderer().create(this, pvGroupPanel, groupScene.extensionPrefix, wrapper);
            }, this);
            this.pvLabel = new pvc.visual.Label(this, pvLegendMarkerPanel.anchor("right"), {
                extensionId: "label",
                noTooltip: !1,
                noClick: !1,
                wrapper: wrapper
            }).intercept("textStyle", function(itemScene) {
                var baseTextStyle = this.delegateExtension() || "black";
                return itemScene.isOn() ? baseTextStyle : pvc.toGrayScale(baseTextStyle, null, void 0, 150);
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
            });
            pvc.debug >= 16 && pvLegendMarkerPanel.anchor("right").add(pv.Panel)[this.anchorLength()](0)[this.anchorOrthoLength()](0).fillStyle(null).strokeStyle(null).lineWidth(0).add(pv.Line).data(function(scene) {
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
        _getBulletRootScene: function() {
            var rootScene = this._rootScene;
            if (!rootScene) {
                rootScene = new pvc.visual.legend.BulletRootScene(null, {
                    panel: this,
                    source: this.chart.data,
                    horizontal: this.isAnchorTopOrBottom(),
                    font: this.font,
                    markerSize: this.markerSize,
                    textMargin: this.textMargin,
                    itemPadding: this.itemPadding,
                    itemSize: this.itemSize
                });
                this._rootScene = rootScene;
            }
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
    def.type("pvc.CartesianAbstract", pvc.BaseChart).init(function(options) {
        this.axesPanels = {};
        this.base(options);
    }).add({
        _gridDockPanel: null,
        axesPanels: null,
        yAxisPanel: null,
        xAxisPanel: null,
        secondXAxisPanel: null,
        secondYAxisPanel: null,
        yScale: null,
        xScale: null,
        _getSeriesRoleSpec: function() {
            return {
                isRequired: !0,
                defaultDimension: "series*",
                autoCreateDimension: !0,
                requireIsDiscrete: !0
            };
        },
        _getColorRoleSpec: function() {
            return {
                isRequired: !0,
                defaultDimension: "color*",
                defaultSourceRole: "series",
                requireIsDiscrete: !0
            };
        },
        _addAxis: function(axis) {
            this.base(axis);
            switch (axis.type) {
              case "base":
              case "ortho":
                this.axes[axis.orientedId] = axis;
                axis.v1SecondOrientedId && (this.axes[axis.v1SecondOrientedId] = axis);
            }
            return this;
        },
        _setAxisScale: function(axis, chartLevel) {
            this.base(axis, chartLevel);
            var isOrtho = "ortho" === axis.type, isCart = isOrtho || "base" === axis.type;
            isCart && (isOrtho && 1 === axis.index ? this.secondScale = axis.scale : axis.index || (this[axis.orientation + "Scale"] = axis.scale));
        },
        _createContent: function(contentOptions) {
            this._createFocusWindow();
            this._gridDockPanel = new pvc.CartesianGridDockingPanel(this, this.basePanel, {
                margins: contentOptions.margins,
                paddings: contentOptions.paddings
            });
            [ "base", "ortho" ].forEach(function(type) {
                var typeAxes = this.axesByType[type];
                typeAxes && def.query(typeAxes).reverse().each(function(axis) {
                    this._createAxisPanel(axis);
                }, this);
            }, this);
            this._createPlotPanels(this._gridDockPanel, {
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
            if (axis.option("Visible")) {
                var titlePanel, title = axis.option("Title");
                def.empty(title) || (titlePanel = new pvc.AxisTitlePanel(this, this._gridDockPanel, axis, {
                    title: title,
                    font: axis.option("TitleFont") || axis.option("Font"),
                    anchor: axis.option("Position"),
                    align: axis.option("TitleAlign"),
                    margins: axis.option("TitleMargins"),
                    paddings: axis.option("TitlePaddings"),
                    titleSize: axis.option("TitleSize"),
                    titleSizeMax: axis.option("TitleSizeMax")
                }));
                var panel = new pvc.AxisPanel(this, this._gridDockPanel, axis, {
                    anchor: axis.option("Position"),
                    size: axis.option("Size"),
                    sizeMax: axis.option("SizeMax"),
                    clickAction: axis.option("ClickAction"),
                    doubleClickAction: axis.option("DoubleClickAction"),
                    useCompositeAxis: axis.option("Composite"),
                    font: axis.option("Font"),
                    labelSpacingMin: axis.option("LabelSpacingMin"),
                    grid: axis.option("Grid"),
                    gridCrossesMargin: axis.option("GridCrossesMargin"),
                    ruleCrossesMargin: axis.option("RuleCrossesMargin"),
                    zeroLine: axis.option("ZeroLine"),
                    desiredTickCount: axis.option("DesiredTickCount"),
                    showTicks: axis.option("Ticks"),
                    showMinorTicks: axis.option("MinorTicks")
                });
                titlePanel && (panel.titlePanel = titlePanel);
                this.axesPanels[axis.id] = panel;
                this.axesPanels[axis.orientedId] = panel;
                axis.index <= 1 && axis.v1SecondOrientedId && (this[axis.v1SecondOrientedId + "AxisPanel"] = panel);
                return panel;
            }
        },
        _onLaidOut: function() {
            this.plotPanelList && this.plotPanelList[0] && [ "base", "ortho" ].forEach(function(type) {
                var axes = this.axesByType[type];
                axes && axes.forEach(this._setCartAxisScaleRange, this);
            }, this);
        },
        _setCartAxisScaleRange: function(axis) {
            var info = this.plotPanelList[0]._layoutInfo, size = info.clientSize, length = "x" === axis.orientation ? size.width : size.height;
            axis.setScaleRange(length);
            return axis.scale;
        },
        _getAxesRoundingPaddings: function() {
            function setSide(side, pct, locked) {
                var value = axesPaddings[side];
                if (null == value || pct > value) {
                    axesPaddings[side] = pct;
                    axesPaddings[side + "Locked"] = locked;
                } else locked && (axesPaddings[side + "Locked"] = locked);
            }
            function processAxis(axis) {
                if (axis) {
                    var tickRoundPads = axis.getScaleRoundingPaddings();
                    if (tickRoundPads) {
                        var isX = "x" === axis.orientation;
                        setSide(isX ? "left" : "bottom", tickRoundPads.begin, tickRoundPads.beginLocked);
                        setSide(isX ? "right" : "top", tickRoundPads.end, tickRoundPads.endLocked);
                    }
                }
            }
            var axesPaddings = {}, axesByType = this.axesByType;
            [ "base", "ortho" ].forEach(function(type) {
                var typeAxes = axesByType[type];
                typeAxes && typeAxes.forEach(processAxis);
            });
            return axesPaddings;
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
            {
                var me = this, baseAxis = me.axes.base, orthoAxis = me.axes.ortho, baseRole = baseAxis.role, baseScale = baseAxis.scale, baseDim = me.data.owner.dimensions(baseRole.grouping.firstDimensionName());
                baseDim.type;
            }
            if (baseAxis.isDiscrete()) {
                me._warn("Can only mark events in charts with a continuous base scale.");
                return me;
            }
            var o = $.extend({}, me.markEventDefaults, options), pseudoAtom = baseDim.read(sourceValue, label), basePos = baseScale(pseudoAtom.value), baseRange = baseScale.range(), baseEndPos = baseRange[1];
            if (basePos < baseRange[0] || basePos > baseEndPos) {
                this._warn("Cannot mark event because it is outside the base scale's domain.");
                return this;
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
            panelSizeRatio: .9,
            timeSeries: !1,
            timeSeriesFormat: "%Y-%m-%d"
        }
    });
    def.type("pvc.GridDockingPanel", pvc.BasePanel).add({
        anchor: "fill",
        _calcLayout: function(layoutInfo) {
            function layoutCycle(remTimes, iteration) {
                useLog && me._group("LayoutCycle " + (isDisasterRecovery ? "- Disaster MODE" : "#" + (iteration + 1)));
                try {
                    var index, count, paddingsChanged, breakAndRepeat, canChange = layoutInfo.canChange !== !1 && !isDisasterRecovery && remTimes > 0, ownPaddingsChanged = !1;
                    index = 0;
                    count = sideChildren.length;
                    for (;count > index; ) {
                        useLog && me._group("SIDE Child #" + (index + 1));
                        try {
                            paddingsChanged = layoutChild2Side(sideChildren[index], canChange);
                            if (!isDisasterRecovery && paddingsChanged) {
                                breakAndRepeat = !1;
                                if (0 !== (paddingsChanged & OverflowPaddingsChanged)) {
                                    useLog && me._log("SIDE Child #" + (index + 1) + " changed overflow paddings");
                                    if (!ownPaddingsChanged) {
                                        ownPaddingsChanged = !0;
                                        layoutInfo.requestPaddings = layoutInfo.paddings;
                                    }
                                }
                                if (0 !== (paddingsChanged & NormalPaddingsChanged)) if (remTimes > 0) {
                                    useLog && me._log("SIDE Child #" + (index + 1) + " changed normal paddings");
                                    breakAndRepeat = !0;
                                } else pvc.debug >= 2 && me._warn("SIDE Child #" + (index + 1) + " changed paddings but no more iterations possible.");
                                if (0 !== (paddingsChanged & LoopDetected)) {
                                    isDisasterRecovery = !0;
                                    layoutCycle(0);
                                    return !1;
                                }
                                if (breakAndRepeat) return !0;
                            }
                        } finally {
                            useLog && me._groupEnd();
                        }
                        index++;
                    }
                    if (ownPaddingsChanged) {
                        useLog && me._log("Restarting due to overflowPaddings change");
                        return !1;
                    }
                    index = 0;
                    count = fillChildren.length;
                    for (;count > index; ) {
                        useLog && me._group("FILL Child #" + (index + 1));
                        try {
                            paddingsChanged = layoutChildFill(fillChildren[index], canChange);
                            if (!isDisasterRecovery && paddingsChanged) {
                                breakAndRepeat = !1;
                                if (0 !== (paddingsChanged & NormalPaddingsChanged)) if (remTimes > 0) {
                                    pvc.debug >= 5 && me._log("FILL Child #" + (index + 1) + " increased paddings");
                                    breakAndRepeat = !0;
                                } else pvc.debug >= 2 && me._warn("FILL Child #" + (index + 1) + " increased paddings but no more iterations possible.");
                                if (0 !== (paddingsChanged & LoopDetected)) {
                                    isDisasterRecovery = !0;
                                    layoutCycle(0);
                                    return !1;
                                }
                                if (breakAndRepeat) return !0;
                            }
                        } finally {
                            useLog && me._groupEnd();
                        }
                        index++;
                    }
                    return !1;
                } finally {
                    useLog && me._groupEnd();
                }
            }
            function doMaxTimes(maxTimes, fun) {
                for (var index = 0; maxTimes--; ) {
                    if (fun(maxTimes, index) === !1) return !0;
                    index++;
                }
                return !1;
            }
            function initChild(child) {
                var a = child.anchor;
                if (a) if ("fill" === a) {
                    fillChildren.push(child);
                    var childPaddings = child.paddings.resolve(childKeyArgs.referenceSize);
                    paddings = pvc_Sides.resolvedMax(paddings, childPaddings);
                } else {
                    def.hasOwn(aoMap, a) || def.fail.operationInvalid("Unknown anchor value '{0}'", [ a ]);
                    sideChildren.push(child);
                }
            }
            function layoutChild1Side(child, index) {
                useLog && me._group("SIDE Child #" + (index + 1));
                try {
                    var paddingsChanged = 0, a = child.anchor;
                    childKeyArgs.paddings = filterAnchorPaddings(a, paddings);
                    child.layout(new pvc_Size(remSize), childKeyArgs);
                    if (child.isVisible) {
                        paddingsChanged |= checkAnchorPaddingsChanged(a, paddings, child);
                        positionChildNormal(a, child);
                        updateSide(a, child);
                    }
                    return paddingsChanged;
                } finally {
                    useLog && me._groupEnd();
                }
            }
            function layoutChildFill(child, canChange) {
                var paddingsChanged = 0, a = child.anchor;
                childKeyArgs.paddings = filterAnchorPaddings(a, paddings);
                childKeyArgs.canChange = canChange;
                child.layout(new pvc_Size(remSize), childKeyArgs);
                if (child.isVisible) {
                    paddingsChanged |= checkAnchorPaddingsChanged(a, paddings, child, canChange);
                    positionChildNormal(a, child);
                    positionChildOrtho(child, a);
                }
                return paddingsChanged;
            }
            function layoutChild2Side(child, canChange) {
                var paddingsChanged = 0;
                if (child.isVisible) {
                    var a = child.anchor, al = alMap[a], aol = aolMap[a], length = remSize[al], olength = child[aol], childSize2 = new pvc_Size(def.set({}, al, length, aol, olength));
                    childKeyArgs.paddings = filterAnchorPaddings(a, paddings);
                    childKeyArgs.canChange = canChange;
                    child.layout(childSize2, childKeyArgs);
                    if (child.isVisible) {
                        paddingsChanged = checkAnchorPaddingsChanged(a, paddings, child, canChange) | checkOverflowPaddingsChanged(a, layoutInfo.paddings, child, canChange);
                        paddingsChanged || positionChildOrtho(child, child.align);
                    }
                }
                return paddingsChanged;
            }
            function positionChildNormal(side, child) {
                var sidePos;
                if ("fill" === side) {
                    side = "left";
                    sidePos = margins.left + remSize.width / 2 - child.width / 2;
                } else sidePos = margins[side];
                child.setPosition(def.set({}, side, sidePos));
            }
            function updateSide(side, child) {
                var sideol = aolMap[side], olen = child[sideol];
                margins[side] += olen;
                remSize[sideol] -= olen;
            }
            function positionChildOrtho(child, align) {
                var sideo;
                "fill" === align && (align = "middle");
                var sideOPos;
                switch (align) {
                  case "top":
                  case "bottom":
                  case "left":
                  case "right":
                    sideo = align;
                    sideOPos = margins[sideo];
                    break;

                  case "middle":
                    sideo = "bottom";
                    sideOPos = margins.bottom + remSize.height / 2 - child.height / 2;
                    break;

                  case "center":
                    sideo = "left";
                    sideOPos = margins.left + remSize.width / 2 - child.width / 2;
                }
                child.setPosition(def.set({}, sideo, sideOPos));
            }
            function filterAnchorPaddings(a, paddings) {
                var filtered = new pvc_Sides();
                getAnchorPaddingsNames(a).forEach(function(side) {
                    filtered.set(side, paddings[side]);
                });
                return filtered;
            }
            function checkAnchorPaddingsChanged(a, paddings, child, canChange) {
                var newPaddings = child._layoutInfo.requestPaddings, changed = 0;
                if (newPaddings) {
                    if (useLog && pvc.debug >= 10) {
                        me._log("=> clientSize=" + pvc.stringify(child._layoutInfo.clientSize));
                        me._log("<= requestPaddings=" + pvc.stringify(newPaddings));
                    }
                    getAnchorPaddingsNames(a).forEach(function(side) {
                        var value = paddings[side] || 0, newValue = Math.floor(1e4 * (newPaddings[side] || 0)) / 1e4, increase = newValue - value, minChange = Math.max(1, Math.abs(.01 * value));
                        if (0 !== increase && Math.abs(increase) >= minChange) if (canChange) {
                            changed |= NormalPaddingsChanged;
                            paddings[side] = newValue;
                            useLog && me._log("Changed padding " + side + " <- " + newValue);
                        } else pvc.debug >= 2 && me._warn("CANNOT change but child wanted to: " + side + "=" + newValue);
                    });
                    if (changed) {
                        var paddingKey = pvc_Sides.names.map(function(side) {
                            return (paddings[side] || 0).toFixed(0);
                        }).join("|");
                        if (def.hasOwn(paddingHistory, paddingKey)) {
                            pvc.debug >= 2 && me._warn("LOOP detected!!!!");
                            changed |= LoopDetected;
                        } else paddingHistory[paddingKey] = !0;
                        paddings.width = paddings.left + paddings.right;
                        paddings.height = paddings.top + paddings.bottom;
                    }
                }
                return changed;
            }
            function checkOverflowPaddingsChanged(a, ownPaddings, child, canChange) {
                var overflowPaddings = child._layoutInfo.overflowPaddings || emptyNewPaddings, changed = 0;
                useLog && pvc.debug >= 10 && me._log("<= overflowPaddings=" + pvc.stringify(overflowPaddings));
                getAnchorPaddingsNames(a).forEach(function(side) {
                    if (overflowPaddings.hasOwnProperty(side)) {
                        var value = ownPaddings[side] || 0, newValue = Math.floor(1e4 * (overflowPaddings[side] || 0)) / 1e4;
                        newValue -= margins[side];
                        var increase = newValue - value, minChange = Math.max(1, Math.abs(.05 * value));
                        if (increase >= minChange) if (canChange) {
                            changed |= OverflowPaddingsChanged;
                            ownPaddings[side] = newValue;
                            useLog && me._log("changed overflow padding " + side + " <- " + newValue);
                        } else pvc.debug >= 2 && me._warn("CANNOT change overflow padding but child wanted to: " + side + "=" + newValue);
                    }
                });
                if (changed) {
                    ownPaddings.width = ownPaddings.left + ownPaddings.right;
                    ownPaddings.height = ownPaddings.top + ownPaddings.bottom;
                }
                return changed;
            }
            function getAnchorPaddingsNames(a) {
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
            }
            var me = this;
            if (me._children) {
                var useLog = pvc.debug >= 5, margins = new pvc_Sides(0), paddings = new pvc_Sides(0), remSize = def.copyOwn(layoutInfo.clientSize), aolMap = pvc.BasePanel.orthogonalLength, aoMap = pvc.BasePanel.relativeAnchor, alMap = pvc.BasePanel.parallelLength, childKeyArgs = {
                    force: !0,
                    referenceSize: layoutInfo.clientSize
                }, fillChildren = [], sideChildren = [], paddingHistory = {}, LoopDetected = 1, NormalPaddingsChanged = 2, OverflowPaddingsChanged = 4, emptyNewPaddings = new pvc_Sides(), isDisasterRecovery = !1;
                useLog && me._group("CCC GRID LAYOUT clientSize = " + pvc.stringify(remSize));
                try {
                    this._children.forEach(initChild);
                    useLog && me._group("Phase 1 - Determine MARGINS and FILL SIZE from SIDE panels");
                    try {
                        sideChildren.forEach(layoutChild1Side);
                    } finally {
                        if (useLog) {
                            me._groupEnd();
                            me._log("Final FILL margins = " + pvc.stringify(margins));
                            me._log("Final FILL border size = " + pvc.stringify(remSize));
                        }
                    }
                    useLog && me._group("Phase 2 - Determine COMMON PADDINGS");
                    try {
                        doMaxTimes(9, layoutCycle);
                    } finally {
                        if (useLog) {
                            me._groupEnd();
                            me._log("Final FILL clientSize = " + pvc.stringify({
                                width: remSize.width - paddings.width,
                                height: remSize.height - paddings.height
                            }));
                            me._log("Final COMMON paddings = " + pvc.stringify(paddings));
                        }
                    }
                    layoutInfo.gridMargins = new pvc_Sides(margins);
                    layoutInfo.gridPaddings = new pvc_Sides(paddings);
                    layoutInfo.gridSize = new pvc_Size(remSize);
                } finally {
                    useLog && me._groupEnd();
                }
            }
        }
    });
    def.type("pvc.CartesianGridDockingPanel", pvc.GridDockingPanel).init(function(chart, parent, options) {
        this.base(chart, parent, options);
        this._plotBgPanel = new pvc.PlotBgPanel(chart, this);
    }).add({
        _getExtensionId: function() {
            return this.chart.parent ? "smallContent" : "content";
        },
        _createCore: function(layoutInfo) {
            var chart = this.chart, axes = chart.axes, xAxis = axes.x, yAxis = axes.y;
            xAxis.isBound() || (xAxis = null);
            yAxis.isBound() || (yAxis = null);
            xAxis && xAxis.option("Grid") && (this.xGridRule = this._createGridRule(xAxis));
            yAxis && yAxis.option("Grid") && (this.yGridRule = this._createGridRule(yAxis));
            this.base(layoutInfo);
            chart.focusWindow && this._createFocusWindow(layoutInfo);
            var plotFrameVisible;
            plotFrameVisible = chart.compatVersion() <= 1 ? !(!xAxis.option("EndLine") && !yAxis.option("EndLine")) : def.get(chart.options, "plotFrameVisible", !0);
            plotFrameVisible && (this.pvFrameBar = this._createFrame(layoutInfo, axes));
            xAxis && "discrete" !== xAxis.scaleType && xAxis.option("ZeroLine") && (this.xZeroLine = this._createZeroLine(xAxis, layoutInfo));
            yAxis && "discrete" !== yAxis.scaleType && yAxis.option("ZeroLine") && (this.yZeroLine = this._createZeroLine(yAxis, layoutInfo));
        },
        _createGridRule: function(axis) {
            var scale = axis.scale;
            if (!scale.isNull) {
                var isDiscrete = axis.role.grouping.isDiscrete(), rootScene = this._getAxisGridRootScene(axis);
                if (rootScene) {
                    var margins = this._layoutInfo.gridMargins, paddings = this._layoutInfo.gridPaddings, tick_a = "x" === axis.orientation ? "left" : "bottom", len_a = this.anchorLength(tick_a), obeg_a = this.anchorOrtho(tick_a), oend_a = this.anchorOpposite(obeg_a), tick_offset = margins[tick_a] + paddings[tick_a], obeg = margins[obeg_a], oend = margins[oend_a], tickScenes = rootScene.leafs().array(), tickCount = tickScenes.length;
                    isDiscrete && tickCount && tickScenes.push(tickScenes[tickCount - 1]);
                    var wrapper;
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
                ticks.forEach(function(majorTick) {
                    new pvc.visual.CartesianAxisTickScene(rootScene, {
                        tick: majorTick,
                        tickRaw: majorTick,
                        tickLabel: axis.scale.tickFormat(majorTick)
                    });
                }, this);
            }
            return rootScene;
        },
        _createFrame: function(layoutInfo, axes) {
            if (!axes.base.scale.isNull && (!axes.ortho.scale.isNull || axes.ortho2 && !axes.ortho2.scale.isNull)) {
                var margins = layoutInfo.gridMargins, left = margins.left, right = margins.right, top = margins.top, bottom = margins.bottom, extensionIds = [];
                if (this.compatVersion() <= 1) {
                    extensionIds.push("xAxisEndLine");
                    extensionIds.push("yAxisEndLine");
                }
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
                    l = l0;
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
                };
                space.width = space.left + space.right;
                space.height = space.top + space.bottom;
                var clientSize = layoutInfo.clientSize, wf = clientSize[a_width], hf = clientSize[a_height], w = wf - space[a_width], h = hf - space[a_height], padLeft = paddings[a_left], padRight = paddings[a_right], scene = new pvc.visual.Scene(null, {
                    panel: this
                }), band = isDiscrete ? scale.range().step : 0, halfBand = band / 2;
                scene[a_x] = scale(focusWindow.begin) - halfBand, scene[a_dx] = band + (scale(focusWindow.end) - halfBand) - scene[a_x], 
                resetSceneY();
                var sceneProp = function(p) {
                    return function() {
                        return scene[p];
                    };
                }, boundLeft = function() {
                    var begin = scene[a_x];
                    return Math.max(0, Math.min(w, begin));
                }, boundWidth = function() {
                    var begin = boundLeft(), end = scene[a_x] + scene[a_dx];
                    end = Math.max(0, Math.min(w, end));
                    return end - begin;
                }, addSelBox = function(panel, id) {
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
                }, baseBgPanel = this._plotBgPanel.pvPanel.borderPanel;
                baseBgPanel.lock("data", [ scene ]);
                movable && resizable ? baseBgPanel.paddingPanel.lock("events", "all").lock("cursor", "crosshair").event("mousedown", pv.Behavior.select().autoRender(!1).collapse(isV ? "y" : "x").positionConstraint(function(drag) {
                    var op = "start" === drag.phase ? "new" : "resize-end";
                    return positionConstraint(drag, op);
                })).event("selectstart", function(ev) {
                    resetSceneY();
                    onDrag(ev);
                }).event("select", onDrag).event("selectend", onDrag) : baseBgPanel.paddingPanel.events("all");
                var focusBg = addSelBox(baseBgPanel.paddingPanel, "focusWindowBg").override("defaultColor", def.fun.constant(pvc.invisibleFill)).pvMark;
                movable ? focusBg.lock("events", "all").lock("cursor", "move").event("mousedown", pv.Behavior.drag().autoRender(!1).collapse(isV ? "y" : "x").positionConstraint(function(drag) {
                    positionConstraint(drag, "move");
                })).event("drag", onDrag).event("dragend", onDrag) : focusBg.events("none");
                var baseFgPanel = new pvc.visual.Panel(me, me.pvPanel).pvMark.lock("data", [ scene ]).lock("visible").lock("fillStyle", pvc.invisibleFill).lock("left", space.left).lock("right", space.right).lock("top", space.top).lock("bottom", space.bottom).lock("zOrder", 10).lock("events", function() {
                    var drag = scene.drag;
                    return drag && "end" !== drag.phase ? "all" : "none";
                }).lock("cursor", function() {
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
                }).pvMark.lock("data", [ scene, scene ]).lock("visible").lock("events", "none").lock(a_left, function() {
                    return this.index ? boundLeft() + boundWidth() : -padLeft;
                }).lock(a_right, function() {
                    return this.index ? -padRight : null;
                }).lock(a_width, function() {
                    return this.index ? null : padLeft + boundLeft();
                }).lock(a_top, sceneProp(a_y)).lock(a_height, sceneProp(a_dy)).lock(a_bottom);
                var selectBoxFg = addSelBox(baseFgPanel, "focusWindow").override("defaultColor", def.fun.constant(null)).pvMark.lock("events", "none"), addResizeSideGrip = function(side) {
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
                        grip.lock("events", "all")[a_width](5).cursor(isV ? "ew-resize" : "ns-resize").event("mousedown", pv.Behavior.resize(side).autoRender(!1).positionConstraint(function(drag) {
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
        function setSide(side, pct) {
            var value = pctPaddings[side];
            if (null == value || pct > value) {
                hasAny = !0;
                pctPaddings[side] = pct;
            }
        }
        function processAxis(axis) {
            var offset = axis && axis.option("Offset");
            if (null != offset && offset > 0 && 1 > offset) if ("x" === axis.orientation) {
                setSide("left", offset);
                setSide("right", offset);
            } else {
                setSide("top", offset);
                setSide("bottom", offset);
            }
        }
        this.base(chart, parent, plot, options);
        var axes = this.axes;
        addAxis(chart._getAxis("base", plot.option("BaseAxis") - 1));
        addAxis(chart._getAxis("ortho", plot.option("OrthoAxis") - 1));
        var pctPaddings = {}, hasAny = !1, chartAxes = chart.axesByType;
        [ "base", "ortho" ].forEach(function(type) {
            var typeAxes = chartAxes[type];
            typeAxes && typeAxes.forEach(processAxis);
        });
        hasAny && (this.offsetPaddings = pctPaddings);
    }).add({
        offsetPaddings: null,
        _calcLayout: function(layoutInfo) {
            layoutInfo.requestPaddings = this._calcRequestPaddings(layoutInfo);
        },
        _calcRequestPaddings: function(layoutInfo) {
            var reqPads, offPads = this.offsetPaddings;
            if (offPads) {
                var tickRoundPads = this.chart._getAxesRoundingPaddings(), clientSize = layoutInfo.clientSize, pads = layoutInfo.paddings;
                pvc_Sides.names.forEach(function(side) {
                    var len_a = pvc.BasePanel.orthogonalLength[side], clientLen = clientSize[len_a], paddingLen = pads[len_a], len = clientLen + paddingLen;
                    if (!tickRoundPads[side + "Locked"]) {
                        var offLen = len * (offPads[side] || 0), roundLen = clientLen * (tickRoundPads[side] || 0);
                        (reqPads || (reqPads = {}))[side] = Math.max(offLen - roundLen, 0);
                    }
                }, this);
            }
            return reqPads;
        },
        _createCore: function() {
            this.pvPanel.zOrder(-10);
            var hideOverflow, contentOverflow = this.chart.options.leafContentOverflow || "auto";
            hideOverflow = "auto" === contentOverflow ? def.query([ "ortho", "base" ]).select(function(axisType) {
                return this.axes[axisType];
            }, this).any(function(axis) {
                return null != axis.option("FixedMin") || null != axis.option("FixedMax");
            }) : "hidden" === contentOverflow;
            hideOverflow && this.pvPanel.borderPanel.overflow("hidden");
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
    def.type("pvc.CategoricalAbstract", pvc.CartesianAbstract).init(function(options) {
        this.base(options);
        var parent = this.parent;
        parent && (this._catRole = parent._catRole);
    }).add({
        _interpolatable: !0,
        _initVisualRoles: function() {
            this.base();
            this._catRole = this._addVisualRole("category", this._getCategoryRoleSpec());
        },
        _getCategoryRoleSpec: function() {
            return {
                isRequired: !0,
                defaultDimension: "category*",
                autoCreateDimension: !0
            };
        },
        _createVisibleData: function(baseData, ka) {
            var serGrouping = this._serRole && this._serRole.flattenedGrouping(), catGrouping = this._catRole.flattenedGrouping();
            return serGrouping ? baseData.groupBy(def.get(ka, "inverted", !1) ? [ serGrouping, catGrouping ] : [ catGrouping, serGrouping ], ka) : baseData.groupBy(catGrouping, ka);
        },
        _interpolateDataCell: function(dataCell, baseData) {
            var InterpType = this._getNullInterpolationOperType(dataCell.nullInterpolationMode);
            if (InterpType) {
                this._warnSingleContinuousValueRole(dataCell.role);
                var partValue = dataCell.dataPartValue, partData = this.partData(partValue, baseData), visibleData = this.visibleData(partValue, {
                    baseData: baseData
                });
                visibleData.childCount() > 0 && new InterpType(baseData, partData, visibleData, this._catRole, this._serRole, dataCell.role, !0).interpolate();
            }
        },
        _getNullInterpolationOperType: function(nim) {
            switch (nim) {
              case "linear":
                return pvc.data.LinearInterpolationOper;

              case "zero":
                return pvc.data.ZeroInterpolationOper;

              case "none":
                break;

              default:
                throw def.error.argumentInvalid("nullInterpolationMode", "" + nim);
            }
        },
        _generateTrendsDataCell: function(newDatums, dataCell, baseData) {
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
                        newDatums.push(new pvc.data.TrendDatum(efCatData.owner, atoms, trendOptions));
                    }
                }, this);
            }
            var serRole = this._serRole, xRole = this._catRole, yRole = dataCell.role, trendOptions = dataCell.trend, trendInfo = trendOptions.info;
            this._warnSingleContinuousValueRole(yRole);
            var xDimName, yDimName = yRole.firstDimensionName(), isXDiscrete = xRole.isDiscrete();
            isXDiscrete || (xDimName = xRole.firstDimensionName());
            var sumKeyArgs = {
                zeroIfNone: !1
            }, partData = this.partData(dataCell.dataPartValue, baseData), data = this.visibleData(dataCell.dataPartValue, {
                baseData: baseData
            }), dataPartAtom = this._getTrendDataPartAtom(), dataPartDimName = dataPartAtom.dimension.name, allCatDatas = xRole.flatten(baseData, {
                visible: !0
            }).childNodes, qVisibleSeries = serRole && serRole.isBound() ? serRole.flatten(partData, {
                visible: !0
            }).children() : def.query([ null ]);
            qVisibleSeries.each(genSeriesTrend, this);
        },
        _getContinuousVisibleCellExtent: function(valueAxis, valueDataCell) {
            var valueRole = valueDataCell.role;
            switch (valueRole.name) {
              case "series":
              case "category":
                return this.base(valueAxis, valueDataCell);
            }
            this._warnSingleContinuousValueRole(valueRole);
            var dataPartValue = valueDataCell.dataPartValue, valueDimName = valueRole.firstDimensionName(), data = this.visibleData(dataPartValue), useAbs = valueAxis.scaleUsesAbs();
            return "ortho" === valueAxis.type && valueDataCell.isStacked ? data.children().select(function(catGroup) {
                var range = this._getStackedCategoryValueExtent(catGroup, valueDimName, useAbs);
                return range ? {
                    range: range,
                    group: catGroup
                } : void 0;
            }, this).where(def.notNully).reduce(function(result, rangeInfo) {
                return this._reduceStackedCategoryValueExtent(result, rangeInfo.range, rangeInfo.group);
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
        _reduceStackedCategoryValueExtent: function(result, catRange) {
            return pvc.unionExtents(result, catRange);
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
        }
    });
    def.type("pvc.CategoricalAbstractPanel", pvc.CartesianAbstractPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.stacked = plot.option("Stacked");
    });
    def.type("pvc.AxisPanel", pvc.BasePanel).init(function(chart, parent, axis, options) {
        options = def.create(options, {
            anchor: axis.option("Position")
        });
        var anchor = options.anchor || this.anchor;
        this.axis = axis;
        this.base(chart, parent, options);
        this.roleName = axis.role.name;
        this.isDiscrete = axis.role.isDiscrete();
        this._extensionPrefix = axis.extensionPrefixes;
        null == this.labelSpacingMin && (this.labelSpacingMin = this.isDiscrete ? .25 : 1.5);
        null == this.showTicks && (this.showTicks = !this.isDiscrete);
        if (void 0 === options.font) {
            var extFont = this._getConstantExtension("label", "font");
            extFont && (this.font = extFont);
        }
        if (void 0 === options.tickLength) {
            var tickLength = +this._getConstantExtension("ticks", this.anchorOrthoLength(anchor));
            !isNaN(tickLength) && isFinite(tickLength) && (this.tickLength = tickLength);
        }
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
        desiredTickCount: null,
        showMinorTicks: !0,
        showTicks: null,
        hiddenLabelText: "·",
        _isScaleSetup: !1,
        _createLogInstanceId: function() {
            return this.base() + " - " + this.axis.id;
        },
        getTicks: function() {
            return this._layoutInfo && this._layoutInfo.ticks;
        },
        _calcLayout: function(layoutInfo) {
            var scale = this.axis.scale;
            if (!this._isScaleSetup) {
                this.pvScale = scale;
                this.scale = scale;
                this.extend(scale, "scale");
                this._isScaleSetup = !0;
            }
            scale.isNull ? layoutInfo.axisSize = 0 : this._calcLayoutCore(layoutInfo);
            return this.createAnchoredSize(layoutInfo.axisSize, layoutInfo.clientSize);
        },
        _calcLayoutCore: function(layoutInfo) {
            var axisSize = layoutInfo.desiredClientSize[this.anchorOrthoLength()];
            layoutInfo.axisSize = axisSize;
            if (this.isDiscrete && this.useCompositeAxis) null == layoutInfo.axisSize && (layoutInfo.axisSize = 50); else {
                this._readTextProperties(layoutInfo);
                this._calcTicks();
                "discrete" === this.scale.type && (this._tickIncludeModulo = this._calcDiscreteTicksIncludeModulo());
                this._calcAxisSizeFromLabel(layoutInfo);
                null == layoutInfo.axisSize && (layoutInfo.axisSize = layoutInfo.requiredAxisSize);
                this._calcMaxTextLengthThatFits();
                this._calcOverflowPaddings();
            }
        },
        _calcAxisSizeFromLabel: function(layoutInfo) {
            this._calcTicksLabelBBoxes(layoutInfo);
            this._calcAxisSizeFromLabelBBox(layoutInfo);
        },
        _readTextProperties: function(layoutInfo) {
            var textAngle = this._getExtension("label", "textAngle");
            layoutInfo.isTextAngleFixed = null != textAngle;
            layoutInfo.textAngle = def.number.as(textAngle, 0);
            layoutInfo.textMargin = def.number.as(this._getExtension("label", "textMargin"), 3);
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
            var maxLabelBBox = layoutInfo.maxLabelBBox, length = this._getLabelBBoxQuadrantLength(maxLabelBBox, this.anchor), axisSize = this.tickLength + length, angle = maxLabelBBox.sourceAngle;
            0 === angle && this.isAnchorTopOrBottom() || (axisSize += this.tickLength);
            layoutInfo.requiredAxisSize = axisSize;
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
        _calcOverflowPaddings: function() {
            this._layoutInfo.canChange ? this._calcOverflowPaddingsFromLabelBBox() : pvc.debug >= 2 && this._warn("Layout cannot change. Skipping calculation of overflow paddings.");
        },
        _calcOverflowPaddingsFromLabelBBox: function() {
            var overflowPaddings = null, me = this, li = me._layoutInfo, ticks = li.ticks, tickCount = ticks.length;
            if (tickCount) {
                var ticksBBoxes = li.ticksBBoxes, paddings = li.paddings, isTopOrBottom = me.isAnchorTopOrBottom(), begSide = isTopOrBottom ? "left" : "bottom", endSide = isTopOrBottom ? "right" : "top", scale = me.scale, isDiscrete = "discrete" === scale.type, clientLength = li.clientSize[me.anchorLength()];
                this.axis.setScaleRange(clientLength);
                var evalLabelSideOverflow = function(labelBBox, side, isBegin, index) {
                    var sideLength = me._getLabelBBoxQuadrantLength(labelBBox, side);
                    if (sideLength > 1) {
                        var anchorPosition = scale(isDiscrete ? ticks[index].value : ticks[index]), sidePosition = isBegin ? anchorPosition - sideLength : anchorPosition + sideLength, sideOverflow = Math.max(0, isBegin ? -sidePosition : sidePosition - clientLength);
                        if (sideOverflow > 1) {
                            sideOverflow -= paddings[side] || 0;
                            if (sideOverflow > 1) {
                                isDiscrete && (sideOverflow *= 1.05);
                                if (overflowPaddings) {
                                    var currrOverflowPadding = overflowPaddings[side];
                                    (null == currrOverflowPadding || sideOverflow > currrOverflowPadding) && (overflowPaddings[side] = sideOverflow);
                                } else overflowPaddings = def.set({}, side, sideOverflow);
                            }
                        }
                    }
                };
                ticksBBoxes.forEach(function(labelBBox, index) {
                    evalLabelSideOverflow(labelBBox, begSide, !0, index);
                    evalLabelSideOverflow(labelBBox, endSide, !1, index);
                });
                pvc.debug >= 6 && overflowPaddings && me._log("OverflowPaddings = " + pvc.stringify(overflowPaddings));
            }
            li.overflowPaddings = overflowPaddings;
        },
        _calcMaxTextLengthThatFits: function() {
            var layoutInfo = this._layoutInfo;
            if (this.compatVersion() <= 1) layoutInfo.maxTextWidth = null; else {
                var availableClientLength = layoutInfo.clientSize[this.anchorOrthoLength()], efSize = Math.min(layoutInfo.axisSize, availableClientLength);
                if (efSize >= layoutInfo.requiredAxisSize - this.tickLength) layoutInfo.maxTextWidth = null; else {
                    var mostOrthoDistantPoint, parallelDirection, maxLabelBBox = layoutInfo.maxLabelBBox, maxOrthoLength = efSize - 2 * this.tickLength;
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
                    pvc.debug >= 3 && this._log("Trimming labels' text at length " + maxTextWidth.toFixed(2) + "px maxOrthoLength=" + maxOrthoLength.toFixed(2) + "px");
                }
            }
        },
        _calcTicks: function() {
            var layoutInfo = this._layoutInfo;
            layoutInfo.textHeight = 2 * pv.Text.fontHeight(this.font) / 3;
            layoutInfo.maxTextWidth = null;
            this.axis.setTicks(null);
            switch (this.scale.type) {
              case "discrete":
                this._calcDiscreteTicks();
                break;

              case "timeSeries":
                this._calcTimeSeriesTicks();
                break;

              case "numeric":
                this._calcNumberTicks(layoutInfo);
                break;

              default:
                throw def.error.operationInvalid("Undefined axis scale type");
            }
            this.axis.setTicks(layoutInfo.ticks);
            var clientLength = layoutInfo.clientSize[this.anchorLength()];
            this.axis.setScaleRange(clientLength);
            null == layoutInfo.maxTextWidth && this._calcTicksTextLength(layoutInfo);
        },
        _calcDiscreteTicks: function() {
            var axis = this.axis, layoutInfo = this._layoutInfo;
            layoutInfo.ticks = axis.domainItems();
            var format, dimType, grouping = axis.role.grouping;
            if (grouping.isSingleDimension && (dimType = grouping.firstDimensionType()) && dimType.valueType === Date) {
                var extent = def.query(axis.domainValues()).range();
                if (extent && extent.min !== extent.max) {
                    var scale = new pv.Scale.linear(extent.min, extent.max);
                    scale.ticks();
                    var tickFormatter = axis.option("TickFormatter");
                    tickFormatter && scale.tickFormatter(tickFormatter);
                    var domainValues = axis.domainValues();
                    format = function(child, index) {
                        return scale.tickFormat(domainValues[index]);
                    };
                }
            }
            format || (format = function(child) {
                return child.absLabel;
            });
            layoutInfo.ticksText = layoutInfo.ticks.map(format);
            this._clearTicksTextDeps(layoutInfo);
        },
        _clearTicksTextDeps: function(ticksInfo) {
            ticksInfo.maxTextWidth = ticksInfo.ticksTextLength = ticksInfo.ticksBBoxes = null;
        },
        _calcTimeSeriesTicks: function() {
            this._calcContinuousTicks(this._layoutInfo, this.desiredTickCount);
        },
        _calcNumberTicks: function() {
            var desiredTickCount = this.desiredTickCount;
            if (null == desiredTickCount) {
                if (this.isAnchorTopOrBottom()) {
                    this._calcNumberHTicks();
                    return;
                }
                desiredTickCount = this._calcNumberVDesiredTickCount();
            }
            this._calcContinuousTicks(this._layoutInfo, desiredTickCount);
        },
        _calcContinuousTicks: function(ticksInfo, desiredTickCount) {
            this._calcContinuousTicksValue(ticksInfo, desiredTickCount);
            this._calcContinuousTicksText(ticksInfo);
        },
        _calcContinuousTicksValue: function(ticksInfo, desiredTickCount) {
            ticksInfo.ticks = this.axis.calcContinuousTicks(desiredTickCount);
            if (pvc.debug > 4) {
                this._log("DOMAIN: " + pvc.stringify(this.scale.domain()));
                this._log("TICKS:  " + pvc.stringify(ticksInfo.ticks));
            }
        },
        _calcContinuousTicksText: function(ticksInfo) {
            var ticksText = ticksInfo.ticksText = ticksInfo.ticks.map(function(tick) {
                return this.scale.tickFormat(tick);
            }, this);
            this._clearTicksTextDeps(ticksInfo);
            return ticksText;
        },
        _calcTicksTextLength: function(ticksInfo) {
            var max = 0, font = this.font, ticksText = ticksInfo.ticksText || this._calcContinuousTicksText(ticksInfo), ticksTextLength = ticksInfo.ticksTextLength = ticksText.map(function(text) {
                var len = pv.Text.measureWidth(text, font);
                len > max && (max = len);
                return len;
            });
            ticksInfo.maxTextWidth = max;
            ticksInfo.ticksBBoxes = null;
            return ticksTextLength;
        },
        _calcTicksLabelBBoxes: function(ticksInfo) {
            var maxBBox, me = this, li = me._layoutInfo, ticksTextLength = ticksInfo.ticksTextLength || me._calcTicksTextLength(ticksInfo), maxLen = li.maxTextWidth;
            ticksInfo.ticksBBoxes = ticksTextLength.map(function(len) {
                var labelBBox = me._calcLabelBBox(len);
                maxBBox || len !== maxLen || (maxBBox = labelBBox);
                return labelBBox;
            }, me);
            li.maxLabelBBox = maxBBox;
        },
        _calcLabelBBox: function(textWidth) {
            var li = this._layoutInfo;
            return pvc.text.getLabelBBox(textWidth, li.textHeight, li.textAlign, li.textBaseline, li.textAngle, li.textMargin);
        },
        _calcDiscreteTicksIncludeModulo: function() {
            var mode = this.axis.option("OverlappedLabelsMode");
            if ("hide" !== mode && "rotatethenhide" !== mode) return 1;
            var li = this._layoutInfo, ticks = li.ticks, tickCount = ticks.length;
            if (2 >= tickCount) return 1;
            var b = this.scale.range().step, h = li.textHeight, w = li.maxTextWidth;
            if (!(w > 0 && h > 0 && b > 0)) return 1;
            var sMin = h * this.labelSpacingMin, sMinH = sMin, spaceW = pv.Text.measureWidth("x", this.font), sMinW = spaceW + sMin, a = li.textAngle, isH = this.isAnchorTopOrBottom(), sinOrCos = Math.abs(Math[isH ? "sin" : "cos"](a)), cosOrSin = Math.abs(Math[isH ? "cos" : "sin"](a)), timh = 1e-8 > sinOrCos ? 1/0 : Math.ceil((sMinH + h) / (b * sinOrCos)), timw = 1e-8 > cosOrSin ? 1/0 : Math.ceil((sMinW + w) / (b * cosOrSin)), tim = Math.min(timh, timw);
            (!isFinite(tim) || 1 > tim || Math.ceil(tickCount / tim) < 2) && (tim = 1);
            return tim;
        },
        _tickMultipliers: [ 1, 2, 5, 10 ],
        _calcNumberVDesiredTickCount: function() {
            var li = this._layoutInfo, lineHeight = li.textHeight * (1 + Math.max(0, this.labelSpacingMin)), clientLength = li.clientSize[this.anchorLength()], tickCountMax = Math.max(1, ~~(clientLength / lineHeight));
            if (1 >= tickCountMax) return 1;
            var domain = this.scale.domain(), span = domain[1] - domain[0];
            if (0 >= span) return tickCountMax;
            for (var step, stepMin = span / tickCountMax, exponMin = Math.floor(pv.log(stepMin, 10)), stepBase = Math.pow(10, exponMin), ms = this._tickMultipliers, i = 0; i < ms.length; i++) {
                step = ms[i] * stepBase;
                if (step >= stepMin) break;
            }
            return Math.max(1, Math.floor(span / step));
        },
        _calcNumberHTicks: function() {
            for (var dir, prevResultTickCount, ticksInfo, lastBelow, lastAbove, layoutInfo = this._layoutInfo, clientLength = layoutInfo.clientSize[this.anchorLength()], spacing = layoutInfo.textHeight * Math.max(0, this.labelSpacingMin), desiredTickCount = this._calcNumberHDesiredTickCount(spacing), doLog = pvc.debug >= 7; ;) {
                doLog && this._log("calculateNumberHTicks TickCount IN desired = " + desiredTickCount);
                ticksInfo = {};
                this._calcContinuousTicksValue(ticksInfo, desiredTickCount);
                var ticks = ticksInfo.ticks, resultTickCount = ticks.length;
                if (ticks.exponentOverflow) {
                    if (null != dir) {
                        if (1 === dir) {
                            lastBelow && (ticksInfo = lastBelow);
                            break;
                        }
                        lastAbove && (ticksInfo = lastAbove);
                        break;
                    }
                    if (ticks.exponent === this.exponentMin) {
                        lastBelow = ticksInfo;
                        dir = 1;
                    } else {
                        lastAbove = ticksInfo;
                        dir = -1;
                    }
                } else if (null == prevResultTickCount || resultTickCount !== prevResultTickCount) {
                    doLog && this._log("calculateNumberHTicks TickCount desired/resulting = " + desiredTickCount + " -> " + resultTickCount);
                    prevResultTickCount = resultTickCount;
                    this._calcContinuousTicksText(ticksInfo);
                    var length = this._calcNumberHLength(ticksInfo, spacing), excessLength = ticksInfo.excessLength = length - clientLength, pctError = ticksInfo.error = Math.abs(excessLength / clientLength);
                    if (doLog) {
                        this._log("calculateNumberHTicks error=" + (excessLength >= 0 ? "+" : "-") + (100 * ticksInfo.error).toFixed(0) + "% count=" + resultTickCount + " step=" + ticks.step);
                        this._log("calculateNumberHTicks Length client/resulting = " + clientLength + " / " + length + " spacing = " + spacing);
                    }
                    if (excessLength > 0) {
                        if (1 === desiredTickCount) {
                            if (3 === resultTickCount && 1 >= pctError) {
                                ticksInfo.ticks.splice(1, 1);
                                ticksInfo.ticksText.splice(1, 1);
                                ticksInfo.ticks.step *= 2;
                            } else {
                                ticksInfo.ticks.length = 1;
                                ticksInfo.ticksText.length = 1;
                            }
                            delete ticksInfo.maxTextWidth;
                            break;
                        }
                        if (lastBelow) {
                            ticksInfo = lastBelow;
                            break;
                        }
                        lastAbove = ticksInfo;
                        dir = -1;
                    } else {
                        if (.05 >= pctError || -1 === dir) break;
                        lastBelow = ticksInfo;
                        dir = 1;
                    }
                }
                desiredTickCount += dir;
            }
            if (ticksInfo) {
                layoutInfo.ticks = ticksInfo.ticks;
                layoutInfo.ticksText = ticksInfo.ticksText;
                layoutInfo.maxTextWidth = ticksInfo.maxTextWidth;
                pvc.debug >= 5 && this._log("calculateNumberHTicks RESULT error=" + (ticksInfo.excessLength >= 0 ? "+" : "-") + (100 * ticksInfo.error).toFixed(0) + "% count=" + ticksInfo.ticks.length + " step=" + ticksInfo.ticks.step);
            }
            doLog && this._log("calculateNumberHTicks END");
        },
        _calcNumberHDesiredTickCount: function(spacing) {
            var layoutInfo = this._layoutInfo, domainTextLength = this.scale.domain().map(function(tick) {
                tick = +tick.toFixed(2);
                var text = this.scale.tickFormat(tick);
                return pv.Text.measureWidth(text, this.font);
            }, this), avgTextLength = Math.max((domainTextLength[1] + domainTextLength[0]) / 2, layoutInfo.textHeight), clientLength = layoutInfo.clientSize[this.anchorLength()];
            return Math.max(1, ~~(clientLength / (avgTextLength + spacing)));
        },
        _calcNumberHLength: function(ticksInfo, spacing) {
            var ticksText = ticksInfo.ticksText, maxTextWidth = def.query(ticksText).select(function(text) {
                return pv.Text.measureWidth(text, this.font);
            }, this).max();
            return Math.max(maxTextWidth, (ticksText.length - 1) * (maxTextWidth + spacing));
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
                    var includeModulo = this._tickIncludeModulo, hiddenLabelText = this.hiddenLabelText;
                    rootScene.vars.tickIncludeModulo = includeModulo;
                    rootScene.vars.hiddenLabelText = hiddenLabelText;
                    var hiddenDatas, hiddenTexts, createHiddenScene, hiddenIndex;
                    if (includeModulo > 2) {
                        pvc.debug >= 3 && this._info("Showing only one in every " + includeModulo + " tick labels");
                        var keySep = rootScene.group.owner.keySep;
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
                    var childScene = new pvc.visual.CartesianAxisTickScene(scene, {
                        source: childData,
                        tick: childData.value,
                        tickRaw: childData.rawValue,
                        tickLabel: childData.label
                    });
                    childScene.dataIndex = childData.childIndex();
                    recursive(childScene);
                });
            }
            var isV1Compat = this.compatVersion() <= 1;
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
            var wrapper, scale = this.scale, hiddenLabelText = this.hiddenLabelText, includeModulo = this._tickIncludeModulo, hiddenStep2 = includeModulo * scale.range().step / 2, anchorOpposite = this.anchorOpposite(), anchorLength = this.anchorLength(), anchorOrtho = this.anchorOrtho(), anchorOrthoLength = this.anchorOrthoLength(), pvRule = this.pvRule, rootScene = this._getRootScene(), layoutInfo = this._layoutInfo, isV1Compat = this.compatVersion() <= 1;
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
            if (isV1Compat || this.showTicks) {
                this.pvTicks = new pvc.visual.Rule(this, pvTicksPanel, {
                    extensionId: "ticks",
                    wrapper: wrapper
                }).lock("data").intercept("visible", function(scene) {
                    return !scene.isHidden && this.delegateExtension(!0);
                }).optional("lineWidth", 1).lock(anchorOpposite, 0).lock(anchorOrtho, 0).lock(anchorLength, null).optional(anchorOrthoLength, 2 * this.tickLength / 3).override("defaultColor", function() {
                    return isV1Compat ? pv.Color.names.transparent : pvRule.scene ? pvRule.scene[0].strokeStyle : "#666666";
                }).pvMark;
            }
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
            }).pvMark.zOrder(40).lock(anchorOpposite, this.tickLength).lock(anchorOrtho, 0).font(font).textStyle("#666666").textAlign(layoutInfo.textAlign).textBaseline(layoutInfo.textBaseline);
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
                if ("summary" === autoContent) return this._summaryTooltipFormatter;
                if ("value" === autoContent) {
                    tipOptions.isLazy = !1;
                    return function(context) {
                        return context.scene.vars.tick.label;
                    };
                }
            }
        },
        _debugTicksPanel: function(pvTicksPanel) {
            if (pvc.debug >= 16) {
                var li = (this.font, this._layoutInfo), ticksBBoxes = li.ticksBBoxes || this._calcTicksLabelBBoxes(li);
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
                        return pvTicks.scene ? pvTicks.scene[0].strokeStyle : pv.Color.names.d;
                    }).lock(anchorOpposite, 0).lock(anchorLength, null).optional(anchorOrthoLength, this.tickLength / 2).lockMark(anchorOrtho, minorTickOffset).pvMark;
                }
            }
            this.renderLinearAxisLabel(pvTicksPanel, wrapper);
            this._debugTicksPanel(pvTicksPanel);
        },
        renderLinearAxisLabel: function(pvTicksPanel, wrapper) {
            var anchorOpposite = (this.pvTicks, this.anchorOpposite()), anchorOrtho = this.anchorOrtho(), font = (this.scale, 
            this.font), maxTextWidth = this._layoutInfo.maxTextWidth;
            isFinite(maxTextWidth) || (maxTextWidth = 0);
            var pvLabel = this.pvLabel = new pvc.visual.Label(this, pvTicksPanel, {
                extensionId: "label",
                noHover: !1,
                showsInteraction: !0,
                wrapper: wrapper
            }).lock("data").intercept("text", function(tickScene) {
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
            var isTopOrBottom = this.isAnchorTopOrBottom(), axisDirection = isTopOrBottom ? "h" : "v", diagDepthCutoff = 2, vertDepthCutoff = 2, font = this.font, diagMargin = pv.Text.fontHeight(font) / 2, layout = this._pvLayout = this._getCompositeLayoutSingleCluster();
            layout.node.def("fitInfo", null).height(function(tickScene) {
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
            var wrapper, H_CUTOFF_ANG = .3, V_CUTOFF_ANG = 1.27, align = isTopOrBottom ? "center" : "left" == this.anchor ? "right" : "left";
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
                if (tickScene.depth >= vertDepthCutoff && tickScene.depth < diagDepthCutoff) {
                    this.lblDirection("v");
                    return -Math.PI / 2;
                }
                if (tickScene.depth >= diagDepthCutoff) {
                    var tan = tickScene.dy / tickScene.dx, angle = Math.atan(tan);
                    if (angle > V_CUTOFF_ANG) {
                        this.lblDirection("v");
                        return -Math.PI / 2;
                    }
                    if (angle > H_CUTOFF_ANG) {
                        this.lblDirection("d");
                        return -angle;
                    }
                }
                this.lblDirection("h");
                return 0;
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
            var rootScene = this._getRootScene(), orientation = this.anchor, maxDepth = rootScene.group.treeHeight, depthLength = this._layoutInfo.axisSize;
            maxDepth++;
            var baseDisplacement = depthLength / maxDepth, margin = maxDepth > 2 ? 1 / 12 * depthLength : 0;
            baseDisplacement -= margin;
            var scaleFactor = maxDepth / (maxDepth - 1), orthoLength = pvc.BasePanel.orthogonalLength[orientation], displacement = "width" == orthoLength ? "left" === orientation ? [ -baseDisplacement, 0 ] : [ baseDisplacement, 0 ] : "top" === orientation ? [ 0, -baseDisplacement ] : [ 0, baseDisplacement ];
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
    }).add({
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
                var linkInsetRadius = resolvePercentRadius(this.linkInsetRadius), linkOutsetRadius = resolvePercentRadius(this.linkOutsetRadius), linkMargin = resolvePercentWidth(this.linkMargin), linkLabelSize = resolvePercentWidth(this.linkLabelSize), textMargin = def.number.to(this._getConstantExtension("label", "textMargin"), 3), textHeight = 2 * pv.Text.fontHeight(labelFont) / 3, linkHandleWidth = this.linkHandleWidth * textHeight;
                linkMargin += linkHandleWidth;
                var linkLabelSpacingMin = this.linkLabelSpacingMin * textHeight, freeWidthSpace = Math.max(0, clientWidth / 2 - clientRadius), spaceH = Math.max(0, linkOutsetRadius + linkMargin + linkLabelSize - freeWidthSpace), spaceV = linkOutsetRadius + textHeight, linkAndLabelRadius = Math.max(0, spaceV, spaceH);
                if (linkAndLabelRadius >= maxPieRadius) {
                    this.valuesVisible = !1;
                    pvc.debug >= 2 && this._log("Hiding linked labels due to insufficient space.");
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
            }).lockMark("outerRadius", function() {
                return chart.animate(0, normalRadius);
            }).localProperty("innerRadiusEx", pvc_PercentValue.parse).intercept("innerRadius", function() {
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
                    if (pvc.debug >= 20) {
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
        _getExtensionId: function() {
            var extensionIds = [ {
                abs: "content"
            } ];
            this.chart.parent && extensionIds.push({
                abs: "smallContent"
            });
            return extensionIds.concat(this.base());
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
        var colorVarHelper = new pvc.visual.RoleVarHelper(this, panel.visualRoles.color, {
            roleVar: "color"
        }), valueDimName = panel.visualRoles[panel.valueRoleName].firstDimensionName(), valueDim = categRootData.dimensions(valueDimName), pctValueFormat = panel.chart.options.percentValueFormat, angleScale = panel.axes.angle.scale, sumAbs = angleScale.isNull ? 0 : angleScale.domain()[1];
        this.vars.sumAbs = new pvc_ValueLabelVar(sumAbs, formatValue(sumAbs));
        var rootScene = this, CategSceneClass = def.type(pvc.visual.PieCategoryScene).init(function(categData, value) {
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
            if (!rootScene.childNodes.length && !panel.visualRoles.multiChart.isBound()) throw new InvalidDataException("Unable to create a pie chart, please check the data values.");
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
        _animatable: !0,
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
        pieChartPanel: null,
        _getColorRoleSpec: function() {
            return {
                isRequired: !0,
                defaultSourceRole: "category",
                defaultDimension: "color*",
                requireIsDiscrete: !0
            };
        },
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
        _initPlotsCore: function() {
            new pvc.visual.PiePlot(this);
        },
        _createVisibleData: function(baseData, ka) {
            return this.visualRoles.category.flatten(baseData, ka);
        },
        _setAxisScale: function(axis, chartLevel) {
            this.base(axis, chartLevel);
            2 & chartLevel && "angle" === axis.type && axis.setScaleRange({
                min: 0,
                max: 2 * Math.PI
            });
        },
        _createContent: function(contentOptions) {
            this.base();
            var isV1Compat = this.compatVersion() <= 1;
            if (isV1Compat) {
                var innerGap = pvc.castNumber(this.options.innerGap) || .95;
                innerGap = def.between(innerGap, .1, 1);
                contentOptions.paddings = (100 * (1 - innerGap) / 2).toFixed(2) + "%";
            } else null == contentOptions.paddings && (contentOptions.paddings = new pvc_PercentValue(.025));
            var piePlot = this.plots.pie;
            this.pieChartPanel = new pvc.PiePanel(this, this.basePanel, piePlot, def.create(contentOptions, {
                scenes: def.getPath(this.options, "pie.scenes")
            }));
        }
    });
    def.type("pvc.BarAbstractPanel", pvc.CategoricalAbstractPanel).add({
        pvBar: null,
        pvBarLabel: null,
        pvCategoryPanel: null,
        pvSecondLine: null,
        pvSecondDot: null,
        _creating: function() {
            var groupScene = this.defaultLegendGroupScene();
            if (groupScene && !groupScene.hasRenderer()) {
                var colorAxis = groupScene.colorAxis, drawLine = colorAxis.option("LegendDrawLine"), drawMarker = !drawLine || colorAxis.option("LegendDrawMarker");
                if (drawMarker) {
                    var keyArgs = {
                        drawMarker: !0,
                        markerShape: colorAxis.option("LegendShape"),
                        drawRule: drawLine,
                        markerPvProto: new pv_Mark()
                    };
                    this.extend(keyArgs.markerPvProto, "", {
                        constOnly: !0
                    });
                    groupScene.renderer(new pvc.visual.legend.BulletItemDefaultRenderer(keyArgs));
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
                extensionId: "",
                freePosition: !0,
                wrapper: wrapper
            }).lockDimensions().pvMark.antialias(function(scene) {
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
            return null;
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
        _addOverflowMarkers: function(wrapper) {
            var orthoAxis = this.axes.ortho;
            null != orthoAxis.option("FixedMax") && (this.pvOverflowMarker = this._addOverflowMarker(!1, orthoAxis.scale, wrapper));
            null != orthoAxis.option("FixedMin") && (this.pvUnderflowMarker = this._addOverflowMarker(!0, orthoAxis.scale, wrapper));
        },
        _addOverflowMarker: function(isMin, orthoScale, wrapper) {
            var angle, isVertical = this.isOrientationVertical(), a_bottom = isVertical ? "bottom" : "left", a_top = this.anchorOpposite(a_bottom), a_height = this.anchorOrthoLength(a_bottom), a_width = this.anchorLength(a_bottom), paddings = this._layoutInfo.paddings, rOrthoBound = isMin ? orthoScale.min - paddings[a_bottom] : orthoScale.max + paddings[a_top];
            angle = isMin ? isVertical ? 0 : Math.PI / 2 : isVertical ? Math.PI : -Math.PI / 2;
            return new pvc.visual.Dot(this, this.pvBar.anchor("center"), {
                noSelect: !0,
                noHover: !0,
                noClick: !0,
                noDoubleClick: !0,
                noTooltip: !0,
                freePosition: !0,
                extensionId: isMin ? "underflowMarker" : "overflowMarker",
                wrapper: wrapper
            }).intercept("visible", function(scene) {
                var visible = this.delegateExtension();
                if (void 0 !== visible && !visible) return !1;
                var value = scene.vars.value.value;
                if (null == value) return !1;
                var targetInstance = this.pvMark.scene.target[this.pvMark.index], orthoMaxPos = targetInstance[a_bottom] + (value > 0 ? targetInstance[a_height] : 0);
                return isMin ? rOrthoBound > orthoMaxPos : orthoMaxPos > rOrthoBound;
            }).lock(a_top, null).lock("shapeSize").pvMark.shape("triangle").shapeRadius(function() {
                return Math.min(Math.sqrt(10), this.scene.target[this.index][a_width] / 2);
            }).shapeAngle(angle).lineWidth(1.5).strokeStyle("red").fillStyle("white")[a_bottom](function() {
                return rOrthoBound + (isMin ? 1 : -1) * (this.shapeRadius() + 2);
            });
        },
        renderInteractive: function() {
            this.pvPanel.render();
        },
        _buildScene: function(data, axisSeriesDatas, axisCategDatas) {
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
            }), roles = (data.childNodes, this.visualRoles), valueVarHelper = new pvc.visual.RoleVarHelper(rootScene, roles.value, {
                roleVar: "value",
                hasPercentSubVar: this.stacked
            }), colorVarHelper = new pvc.visual.RoleVarHelper(rootScene, roles.color, {
                roleVar: "color"
            });
            axisSeriesDatas.forEach(createSeriesScene);
            return rootScene;
        }
    });
    def.type("pvc.BarAbstract", pvc.CategoricalAbstract).init(function(options) {
        this.base(options);
        var parent = this.parent;
        parent && (this._valueRole = parent._valueRole);
    }).add({
        _initVisualRoles: function() {
            this.base();
            this._addVisualRole("value", {
                isMeasure: !0,
                isRequired: !0,
                isPercent: this.options.stacked,
                requireSingleDimension: !0,
                requireIsDiscrete: !1,
                valueType: Number,
                defaultDimension: "value"
            });
            this._valueRole = this.visualRoles.value;
        },
        _getCategoryRoleSpec: function() {
            var catRoleSpec = this.base();
            catRoleSpec.requireIsDiscrete = !0;
            return catRoleSpec;
        },
        _initData: function() {
            this.base.apply(this, arguments);
            var data = this.data;
            this._valueDim = data.dimensions(this._valueRole.firstDimensionName());
        }
    });
    def.type("pvc.BarPanel", pvc.BarAbstractPanel).add({});
    def.type("pvc.BarChart", pvc.BarAbstract).add({
        _animatable: !0,
        _trendable: !0,
        _allowV1SecondAxis: !0,
        _initPlotsCore: function() {
            var options = this.options, barPlot = new pvc.visual.BarPlot(this), trend = barPlot.option("Trend");
            if (options.plot2) {
                var plot2Plot = new pvc.visual.PointPlot(this, {
                    name: "plot2",
                    fixed: {
                        DataPart: "1"
                    },
                    defaults: {
                        ColorAxis: 2,
                        LinesVisible: !0,
                        DotsVisible: !0
                    }
                });
                trend || (trend = plot2Plot.option("Trend"));
            }
            this._trendable = !!trend;
            trend && new pvc.visual.PointPlot(this, {
                name: "trend",
                fixed: {
                    DataPart: "trend",
                    TrendType: "none",
                    ColorRole: "series",
                    NullInterpolatioMode: "none"
                },
                defaults: {
                    ColorAxis: 2,
                    LinesVisible: !0,
                    DotsVisible: !1
                }
            });
        },
        _hasDataPartRole: function() {
            return !0;
        },
        _createPlotPanels: function(parentPanel, baseOptions) {
            var plots = this.plots, barPlot = plots.bar, barPanel = new pvc.BarPanel(this, parentPanel, barPlot, Object.create(baseOptions));
            this.barChartPanel = barPanel;
            var plot2Plot = plots.plot2;
            if (plot2Plot) {
                pvc.debug >= 3 && this._log("Creating Point panel.");
                var pointPanel = new pvc.PointPanel(this, parentPanel, plot2Plot, Object.create(baseOptions));
                barPanel.pvSecondLine = pointPanel.pvLine;
                barPanel.pvSecondDot = pointPanel.pvDot;
                pointPanel._applyV1BarSecondExtensions = !0;
            }
            var trendPlot = plots.trend;
            if (trendPlot) {
                pvc.debug >= 3 && this._log("Creating Trends Point panel.");
                new pvc.PointPanel(this, parentPanel, trendPlot, Object.create(baseOptions));
            }
        }
    });
    def.type("pvc.NormalizedBarPanel", pvc.BarAbstractPanel).add({
        _barVerticalMode: function() {
            return "expand";
        }
    });
    def.type("pvc.NormalizedBarChart", pvc.BarAbstract).add({
        _processOptionsCore: function(options) {
            options.stacked = !0;
            this.base(options);
        },
        _getContinuousVisibleExtentConstrained: function(axis, min, max) {
            return "ortho" === axis.type ? {
                min: 0,
                max: 100,
                minLocked: !0,
                maxLocked: !0
            } : this.base(axis, min, max);
        },
        _initPlotsCore: function() {
            new pvc.visual.NormalizedBarPlot(this);
        },
        _createPlotPanels: function(parentPanel, baseOptions) {
            var barPlot = this.plots.bar;
            this.barChartPanel = new pvc.NormalizedBarPanel(this, parentPanel, barPlot, Object.create(baseOptions));
        }
    });
    def.type("pvc.visual.legend.WaterfallBulletGroupScene", pvc.visual.legend.BulletGroupScene).init(function(rootScene, keyArgs) {
        keyArgs = def.set(keyArgs, "clickMode", "none");
        this.base(rootScene, keyArgs);
        this.createItem(keyArgs);
    }).add({
        renderer: function(renderer) {
            null != renderer && (this._renderer = renderer);
            return this._renderer;
        },
        itemSceneType: function() {
            return pvc.visual.legend.WaterfallBulletItemScene;
        }
    });
    def.type("pvc.visual.legend.WaterfallBulletItemScene", pvc.visual.legend.BulletItemScene).init(function(bulletGroup, keyArgs) {
        this.base.apply(this, arguments);
        var I = pvc.visual.Interactive;
        this._ibits = I.Interactive | I.ShowsInteraction;
        this.color = def.get(keyArgs, "color");
        this.vars.value = new pvc_ValueLabelVar(null, def.get(keyArgs, "label"));
    });
    def.type("pvc.WaterfallPanel", pvc.BarAbstractPanel).add({
        pvWaterfallLine: null,
        ruleData: null,
        _barDifferentialControl: function() {
            var isFalling = this.chart._isFalling;
            return function(scene) {
                if (isFalling && !this.index) return 1;
                var group = scene.vars.category.group, isProperGroup = group._isFlattenGroup && !group._isDegenerateFlattenGroup;
                return isProperGroup ? -2 : isFalling ? -1 : 1;
            };
        },
        _creating: function() {
            var rootScene = this._getLegendBulletRootScene();
            if (rootScene) {
                var waterfallGroupScene = rootScene.firstChild;
                if (waterfallGroupScene && !waterfallGroupScene.hasRenderer()) {
                    var keyArgs = {
                        drawRule: !0,
                        drawMarker: !1,
                        rulePvProto: new pv_Mark()
                    };
                    this.extend(keyArgs.rulePvProto, "line", {
                        constOnly: !0
                    });
                    waterfallGroupScene.renderer(new pvc.visual.legend.BulletItemDefaultRenderer(keyArgs));
                }
            }
        },
        _createCore: function() {
            this.base();
            var chart = this.chart, isVertical = this.isOrientationVertical(), anchor = isVertical ? "bottom" : "left", ao = this.anchorOrtho(anchor), ruleRootScene = this._buildRuleScene(), orthoScale = chart.axes.ortho.scale, orthoZero = orthoScale(0), sceneOrthoScale = chart.axes.ortho.sceneScale({
                sceneVarName: "value"
            }), sceneBaseScale = chart.axes.base.sceneScale({
                sceneVarName: "category"
            }), baseScale = chart.axes.base.scale, barWidth2 = this.barWidth / 2, barWidth = this.barWidth, barStepWidth = this.barStepWidth, isFalling = chart._isFalling, waterColor = chart._waterColor;
            if (this.plot.option("AreasVisible")) {
                var panelColors = pv.Colors.category10(), waterGroupRootScene = this._buildWaterGroupScene(), orthoRange = orthoScale.range(), orthoPanelMargin = .04 * (orthoRange[1] - orthoRange[0]);
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
                return scene.vars.category.group._isFlattenGroup ? !1 : isFalling || !!scene.nextSibling;
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
        _buildRuleScene: function() {
            function createCategScene(ruleInfo) {
                var categData1 = ruleInfo.group, categScene = new pvc.visual.Scene(rootScene, {
                    source: categData1
                }), categVar = categScene.vars.category = pvc_ValueLabelVar.fromComplex(categData1);
                categVar.group = categData1;
                var value = ruleInfo.offset;
                categScene.vars.value = new pvc_ValueLabelVar(value, this.chart._valueDim.format(value));
            }
            function completeCategScene(categScene, index) {
                var value = categScene.vars.value.value;
                categScene.vars.direction = index && prevValue !== value ? isClimbing === value > prevValue ? "up" : "down" : null;
                prevValue = value;
            }
            var prevValue, isClimbing, rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: this.visibleData({
                    ignoreNulls: !1
                })
            }), ris = this.chart._ruleInfos;
            if (ris) {
                ris.forEach(createCategScene, this);
                var q = def.query(rootScene.childNodes);
                isClimbing = !this.chart._isFalling;
                isClimbing || (q = q.reverse());
                q.each(completeCategScene, this);
            }
            return rootScene;
        },
        _buildWaterGroupScene: function() {
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
            var ruleInfoByCategKey, isFalling, chart = this.chart, rootCatData = chart._catRole.select(chart.partData(this.dataPartValue), {
                visible: !0
            }), rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: rootCatData
            }), ris = chart._ruleInfos;
            if (ris) {
                ruleInfoByCategKey = def.query(ris).object({
                    name: function(ri) {
                        return ri.group.absKey;
                    }
                });
                isFalling = chart._isFalling;
                createRectangleSceneRecursive(rootCatData, 0);
            }
            return rootScene;
        }
    });
    def.type("pvc.WaterfallChart", pvc.BarAbstract).init(function(options) {
        this.base(options);
        var parent = this.parent;
        parent && (this._isFalling = parent._isFalling);
    }).add({
        _animatable: !0,
        _isFalling: !0,
        _ruleInfos: null,
        _waterColor: pv.color("#1f77b4").darker(),
        _processOptionsCore: function(options) {
            options.stacked = !0;
            options.baseAxisComposite = !1;
            this.base(options);
            options.plot2 = !1;
        },
        _initPlotsCore: function() {
            var waterPlot = (this.options, new pvc.visual.WaterfallPlot(this));
            this._isFalling = "down" === waterPlot.option("Direction");
            var travProp = this._isFalling ? "FlattenDfsPre" : "FlattenDfsPost";
            this._catRole.setTraversalMode(pvc.visual.TraversalMode[travProp]);
            this._catRole.setRootLabel(waterPlot.option("AllCategoryLabel"));
        },
        _initLegendScenes: function(legendPanel) {
            var waterPlot = this.plots.water, extAbsId = pvc.makeExtensionAbsId("line", waterPlot.extensionPrefixes), strokeStyle = this._getConstantExtension(extAbsId, "strokeStyle");
            strokeStyle && (this._waterColor = pv.color(strokeStyle));
            var rootScene = legendPanel._getBulletRootScene();
            new pvc.visual.legend.WaterfallBulletGroupScene(rootScene, {
                extensionPrefix: pvc.buildIndexedId("", 1),
                label: waterPlot.option("TotalLineLabel"),
                color: this._waterColor
            });
            this.base(legendPanel);
        },
        _reduceStackedCategoryValueExtent: function(result, catRange, catGroup) {
            var offsetNext, offsetPrev = result ? result.offset : 0, offsetDelta = catRange.min + catRange.max;
            if (!result) {
                if (catRange) {
                    offsetNext = offsetPrev + offsetDelta;
                    this._ruleInfos = [ {
                        offset: offsetNext,
                        group: catGroup,
                        range: catRange
                    } ];
                    return {
                        min: catRange.min,
                        max: catRange.max,
                        offset: offsetNext
                    };
                }
                return null;
            }
            var isFalling = this._isFalling, isProperGroup = catGroup._isFlattenGroup && !catGroup._isDegenerateFlattenGroup;
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
            this._ruleInfos.push({
                offset: isFalling ? offsetPrev : result.offset,
                group: catGroup,
                range: catRange
            });
            return result;
        },
        _createPlotPanels: function(parentPanel, baseOptions) {
            this.wfChartPanel = new pvc.WaterfallPanel(this, parentPanel, this.plots.water, def.create(baseOptions, {
                waterfall: this.options.waterfall
            }));
        }
    });
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
        this.visualRoles.value = chart.visualRole(plot.option("OrthoRole"));
    }).add({
        pvLine: null,
        pvArea: null,
        pvDot: null,
        pvLabel: null,
        pvScatterPanel: null,
        _creating: function() {
            var groupScene = this.defaultLegendGroupScene();
            if (groupScene && !groupScene.hasRenderer()) {
                var colorAxis = groupScene.colorAxis, drawMarker = def.nullyTo(colorAxis.option("LegendDrawMarker", !0), this.dotsVisible || this.areasVisible), drawRule = !drawMarker || def.nullyTo(colorAxis.option("LegendDrawLine", !0), this.linesVisible && !this.areasVisible);
                if (drawMarker || drawRule) {
                    var keyArgs = {
                        drawMarker: drawMarker,
                        drawRule: drawRule
                    };
                    if (drawMarker) {
                        var markerShape = colorAxis.option("LegendShape", !0);
                        if (this.dotsVisible) {
                            markerShape || (markerShape = "circle");
                            keyArgs.markerPvProto = new pv.Dot().lineWidth(1.5, pvc.extensionTag).shapeSize(12, pvc.extensionTag);
                        } else keyArgs.markerPvProto = new pv_Mark();
                        keyArgs.markerShape = markerShape;
                        this._applyV1BarSecondExtensions && this.chart.extend(keyArgs.markerPvProto, "barSecondDot", {
                            constOnly: !0
                        });
                        this.extend(keyArgs.markerPvProto, "dot", {
                            constOnly: !0
                        });
                    }
                    if (drawRule) {
                        keyArgs.rulePvProto = new pv.Line().lineWidth(1.5, pvc.extensionTag);
                        this._applyV1BarSecondExtensions && this.chart.extend(keyArgs.rulePvProto, "barSecondLine", {
                            constOnly: !0
                        });
                        this.extend(keyArgs.rulePvProto, "line", {
                            constOnly: !0
                        });
                    }
                    groupScene.renderer(new pvc.visual.legend.BulletItemDefaultRenderer(keyArgs));
                }
            }
        },
        _createCore: function() {
            this.base();
            var myself = this, chart = this.chart, isStacked = this.stacked, dotsVisible = this.dotsVisible, areasVisible = this.areasVisible, linesVisible = this.linesVisible, anchor = this.isOrientationVertical() ? "bottom" : "left", baseAxis = this.axes.base, axisCategDatas = baseAxis.domainItems(), isBaseDiscrete = baseAxis.role.grouping.isDiscrete(), data = this.visibleData({
                ignoreNulls: !1
            }), rootScene = this._buildScene(data, axisCategDatas, isBaseDiscrete);
            this.pvPanel.zOrder(areasVisible ? -7 : 1);
            this.pvScatterPanel = new pvc.visual.Panel(this, this.pvPanel, {
                extensionId: "panel"
            }).lock("data", rootScene.childNodes).pvMark;
            var wrapper, areaFillColorAlpha = areasVisible && linesVisible && !isStacked ? .5 : null;
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
            var lineAreaVisibleProp = isBaseDiscrete && isStacked ? function(scene) {
                return !scene.isNull || scene.isIntermediate;
            } : function(scene) {
                return !scene.isNull;
            }, isLineAreaNoSelect = chart.selectableByFocusWindow();
            this.pvArea = new pvc.visual.Area(this, this.pvScatterPanel, {
                extensionId: "area",
                noTooltip: !1,
                wrapper: wrapper,
                noSelect: isLineAreaNoSelect,
                noRubberSelect: !0,
                showsSelection: !isLineAreaNoSelect
            }).lockMark("data", function(seriesScene) {
                return seriesScene.childNodes;
            }).lockMark("visible", lineAreaVisibleProp).override("x", function(scene) {
                return scene.basePosition;
            }).override("y", function(scene) {
                return scene.orthoPosition;
            }).override("dy", function(scene) {
                return chart.animate(0, scene.orthoLength);
            }).override("color", function(scene, type) {
                return areasVisible ? this.base(scene, type) : null;
            }).override("baseColor", function(scene, type) {
                var color = this.base(scene, type);
                !this._finished && color && null != areaFillColorAlpha && (color = color.alpha(areaFillColorAlpha));
                return color;
            }).override("dimColor", function(color, type) {
                return isStacked ? pvc.toGrayScale(color, 1, null, null).brighter() : this.base(color, type);
            }).lock("events", areasVisible ? "painted" : "none").pvMark;
            var dotsVisibleOnly = dotsVisible && !linesVisible && !areasVisible, darkerLineAndDotColor = isStacked && areasVisible, extensionIds = [ "line" ];
            this._applyV1BarSecondExtensions && extensionIds.push({
                abs: "barSecondLine"
            });
            var lineVisibleProp = !dotsVisibleOnly && lineAreaVisibleProp, noLineInteraction = areasVisible && !linesVisible;
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
                !this._finished && darkerLineAndDotColor && color && (color = color.darker(.6));
                return color;
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
                wrapper: wrapper
            }).intercept("visible", function(scene) {
                return !scene.isNull && !scene.isIntermediate && this.delegateExtension(!0);
            }).override("color", function(scene, type) {
                if (!dotsVisible) {
                    var visible = scene.isActive || !showAloneDots && scene.isSingle || showAloneDots && scene.isAlone;
                    if (!visible) return pvc.invisibleFill;
                }
                var color = this.base(scene, type);
                return scene.isInterpolated && "fill" === type ? color && pv.color(color).brighter(.5) : color;
            }).override("defaultColor", function(scene, type) {
                var color = this.base(scene, type);
                !this._finished && darkerLineAndDotColor && color && (color = color.darker(.6));
                return color;
            }).override("baseSize", function(scene) {
                if (!dotsVisible) {
                    var visible = scene.isActive || !showAloneDots && scene.isSingle || showAloneDots && scene.isAlone;
                    if (visible && !scene.isActive) {
                        var lineWidth = Math.max(myself.pvLine.lineWidth(), .2) / 2;
                        return def.sqr(lineWidth);
                    }
                }
                return scene.isInterpolated ? .8 * this.base(scene) : this.base(scene);
            }).pvMark;
            var label = pvc.visual.ValueLabel.maybeCreate(this, this.pvDot, {
                wrapper: wrapper
            });
            label && (this.pvLabel = label.pvMark);
        },
        renderInteractive: function() {
            this.pvScatterPanel.render();
        },
        _buildScene: function(data, axisCategDatas, isBaseDiscrete) {
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
                var interIsNull = fromScene.isNull || toScene.isNull;
                if (interIsNull && !this.areasVisible) return null;
                var interValue, interAccValue, interBasePosition;
                if (interIsNull) {
                    if (belowScene && isBaseDiscrete) {
                        var belowValueVar = belowScene.vars.value;
                        interAccValue = belowValueVar.accValue;
                        interValue = belowValueVar[valueRole.name];
                    } else interValue = interAccValue = orthoNullValue;
                    interBasePosition = isStacked && isBaseDiscrete ? toScene.basePosition - sceneBaseScale.range().step / 2 : fromScene.isNull ? toScene.basePosition : fromScene.basePosition;
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
            function trimNullSeriesScenes(seriesScene) {
                for (var scene, siblingScene, seriesScenes = seriesScene.childNodes, L = seriesScenes.length; L && (scene = seriesScenes[0]).isNull; ) {
                    siblingScene = scene.nextSibling;
                    if (siblingScene && !siblingScene.isNull) break;
                    seriesScene.removeAt(0);
                    L--;
                }
                for (;L && (scene = seriesScenes[L - 1]).isNull; ) {
                    siblingScene = scene.previousSibling;
                    if (siblingScene && !siblingScene.isNull) break;
                    seriesScene.removeAt(L - 1);
                    L--;
                }
            }
            var rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: data
            }), chart = this.chart, serRole = this.visualRoles.series, valueRole = this.visualRoles.value, isStacked = this.stacked, valueVarHelper = new pvc.visual.RoleVarHelper(rootScene, valueRole, {
                roleVar: "value",
                hasPercentSubVar: isStacked
            }), colorVarHelper = new pvc.visual.RoleVarHelper(rootScene, this.visualRoles.color, {
                roleVar: "color"
            }), valueDimName = valueRole.firstDimensionName(), valueDim = data.owner.dimensions(valueDimName), axisSeriesData = serRole.isBound() ? serRole.flatten(this.partData(), {
                visible: !0,
                isNull: chart.options.ignoreNulls ? !1 : null
            }) : null, orthoScale = this.axes.ortho.scale, orthoNullValue = def.scope(function() {
                var domain = orthoScale.domain(), dmin = domain[0], dmax = domain[1];
                return dmin * dmax >= 0 ? dmin >= 0 ? dmin : dmax : 0;
            }), orthoZero = orthoScale(orthoNullValue), sceneBaseScale = this.axes.base.sceneScale({
                sceneVarName: "category"
            });
            (axisSeriesData ? axisSeriesData.children() : def.query([ null ])).each(function(axisSeriesData) {
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
                    serCatScene.vars.category = pvc_ValueLabelVar.fromComplex(categData);
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
            var belowSeriesScenes2, reversedSeriesScenes = rootScene.children().reverse().array();
            reversedSeriesScenes.forEach(completeSeriesScenes, this);
            reversedSeriesScenes.forEach(trimNullSeriesScenes, this);
            return rootScene;
        }
    });
    def.type("pvc.PointAbstract", pvc.CategoricalAbstract).add({
        _animatable: !0,
        _trendable: !0,
        _processOptionsCore: function(options) {
            options.panelSizeRatio = 1;
            this.base(options);
        },
        _hasDataPartRole: function() {
            return !0;
        },
        _initVisualRoles: function() {
            this.base();
            this._addVisualRole("value", {
                isMeasure: !0,
                isRequired: !0,
                isPercent: this.options.stacked,
                requireSingleDimension: !0,
                requireIsDiscrete: !1,
                valueType: Number,
                defaultDimension: "value"
            });
        },
        _initPlotsCore: function() {
            var options = this.options, pointPlot = this._createPointPlot(), trend = pointPlot.option("Trend");
            if (options.plot2) {
                var plot2Plot = new pvc.visual.PointPlot(this, {
                    name: "plot2",
                    fixed: {
                        DataPart: "1"
                    },
                    defaults: {
                        ColorAxis: 2,
                        LinesVisible: !0,
                        DotsVisible: !0
                    }
                });
                trend || (trend = plot2Plot.option("Trend"));
            }
            this._trendable = !!trend;
            trend && new pvc.visual.PointPlot(this, {
                name: "trend",
                fixed: {
                    DataPart: "trend",
                    TrendType: "none",
                    ColorRole: "series",
                    NullInterpolatioMode: "none"
                },
                defaults: {
                    ColorAxis: 2,
                    LinesVisible: !0,
                    DotsVisible: !1
                }
            });
        },
        _initAxes: function(hasMultiRole) {
            this.base(hasMultiRole);
            var typeAxes = this.axesByType.base;
            typeAxes && typeAxes.forEach(function(axis) {
                var isDiscrete = "discrete" === axis.scaleType;
                isDiscrete || axis.option.defaults({
                    Offset: .01
                });
            });
            typeAxes = this.axesByType.ortho;
            typeAxes && typeAxes.forEach(function(axis) {
                axis.option.defaults({
                    Offset: .04
                });
            });
        },
        _createPlotPanels: function(parentPanel, baseOptions) {
            var plots = this.plots, pointPlot = plots.point;
            this.scatterChartPanel = new pvc.PointPanel(this, parentPanel, pointPlot, Object.create(baseOptions));
            var plot2Plot = plots.plot2;
            if (plot2Plot) {
                pvc.debug >= 3 && this._log("Creating second Point panel.");
                new pvc.PointPanel(this, parentPanel, plot2Plot, Object.create(baseOptions));
            }
            var trendPlot = plots.trend;
            if (trendPlot) {
                pvc.debug >= 3 && this._log("Creating Trends Point panel.");
                new pvc.PointPanel(this, parentPanel, trendPlot, Object.create(baseOptions));
            }
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
    def.type("pvc.HeatGridPanel", pvc.CategoricalAbstractPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.axes.size = chart._getAxis("size", plot.option("SizeAxis") - 1);
        var roles = this.visualRoles, sizeRoleName = plot.option("SizeRole");
        roles.size = chart.visualRole(sizeRoleName);
        this.useShapes = plot.option("UseShapes");
        this.shape = plot.option("Shape");
        this.nullShape = plot.option("NullShape");
    }).add({
        defaultBorder: 1,
        nullBorder: 2,
        selectedBorder: 2,
        _createCore: function() {
            var me = this;
            me.base();
            var cellSize = me._calcCellSize(), a_bottom = me.isOrientationVertical() ? "bottom" : "left", a_left = pvc.BasePanel.relativeAnchor[a_bottom], a_width = pvc.BasePanel.parallelLength[a_bottom], a_height = pvc.BasePanel.orthogonalLength[a_bottom], rowRootData = me.visualRoles.series.flatten(me.partData(), {
                visible: !0,
                isNull: me.chart.options.ignoreNulls ? !1 : null
            }), rootScene = me._buildScene(me.visibleData({
                ignoreNulls: !1
            }), rowRootData, cellSize), hasColor = rootScene.isColorBound, hasSize = rootScene.isSizeBound, wrapper = me._buildSignsWrapper(rootScene), isV1Compat = me.compatVersion() <= 1, rowScale = this.axes.base.scale, colScale = this.axes.ortho.scale, rowStep = rowScale.range().step, colStep = colScale.range().step, rowStep2 = rowStep / 2, colStep2 = colStep / 2, pvRowPanel = new pvc.visual.Panel(me, me.pvPanel).pvMark.data(rootScene.childNodes)[a_bottom](function(scene) {
                return colScale(scene.vars.series.value) - colStep2;
            })[a_height](colStep), extensionIds = [ "panel" ];
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
                return rowScale(scene.vars.category.value) - rowStep2;
            }).lock(a_width, rowStep).antialias(!1);
            me.shapes = me.useShapes ? me._createShapesHeatMap(cellSize, wrapper, hasColor, hasSize) : me._createNoShapesHeatMap(hasColor);
            me.valuesVisible && !me.valuesMask && (me.valuesMask = me._getDefaultValuesMask(hasColor, hasSize));
            var label = pvc.visual.ValueLabel.maybeCreate(me, me.pvHeatGrid, {
                wrapper: wrapper
            });
            label && (me.pvHeatGridLabel = label.pvMark);
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
                    var colorValuesByCat = colorValuesBySerAndCat[leafScene.vars.series.value], cat = leafScene.vars.category.rawValue, wrapperParent = Object.create(this.parent), wrapper = Object.create(this);
                    wrapper.parent = wrapperParent;
                    var catIndex = leafScene.childIndex(), serIndex = leafScene.parent.childIndex();
                    wrapperParent.index = catIndex;
                    wrapper.index = serIndex;
                    return v1f.call(wrapper, colorValuesByCat, cat);
                };
            };
        },
        _getDefaultValuesMask: function(hasColor, hasSize) {
            var roles = this.visualRoles, roleName = hasColor ? "color" : hasSize ? "size" : null;
            if (roleName) {
                var valueDimName = roles[roleName].firstDimensionName();
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
                pvc.debug >= 2 && this._warn("Using rescue mode dot area calculation due to insufficient space.");
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
                        var s = pvc.data.Complex.values(group, seriesDimsNames), c = pvc.data.Complex.values(group, categDimsNames), d = [], vars = context.scene.vars;
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
        _buildScene: function(data, seriesRootData, cellSize) {
            function createSeriesScene(serData1) {
                var serScene = new pvc.visual.Scene(rootScene, {
                    source: serData1
                });
                serScene.vars.series = pvc_ValueLabelVar.fromComplex(serData1);
                categDatas.forEach(function(catData1) {
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
            }), categDatas = data.childNodes, roles = me.visualRoles, colorVarHelper = new pvc.visual.RoleVarHelper(rootScene, roles.color, {
                roleVar: "color"
            }), sizeVarHelper = new pvc.visual.RoleVarHelper(rootScene, roles.size, {
                roleVar: "size"
            });
            rootScene.cellSize = cellSize;
            seriesRootData.children().each(createSeriesScene);
            return rootScene;
        }
    });
    def.type("pvc.HeatGridChart", pvc.CategoricalAbstract).add({
        _allowColorPerCategory: !0,
        _interpolatable: !1,
        _axisCreateIfUnbound: {
            color: !0
        },
        _processOptionsCore: function(options) {
            this.base(options);
            def.set(options, "legend", !1, "panelSizeRatio", 1);
            var colorDimName = "value", sizeDimName = "value2";
            if (this.compatVersion() <= 1) {
                switch (this.options.colorValIdx) {
                  case 0:
                    colorDimName = "value";
                    break;

                  case 1:
                    colorDimName = "value2";
                    break;

                  default:
                    colorDimName = "value";
                }
                switch (this.options.sizeValIdx) {
                  case 0:
                    sizeDimName = "value";
                    break;

                  case 1:
                    sizeDimName = "value2";
                    break;

                  default:
                    sizeDimName = "value";
                }
            }
            this._colorDimName = colorDimName;
            this._sizeDimName = sizeDimName;
        },
        _getCategoryRoleSpec: function() {
            var catRoleSpec = this.base();
            catRoleSpec.requireIsDiscrete = !0;
            return catRoleSpec;
        },
        _getColorRoleSpec: function() {
            return {
                isMeasure: !0,
                requireSingleDimension: !0,
                requireIsDiscrete: !1,
                valueType: Number,
                defaultDimension: this._colorDimName
            };
        },
        _initVisualRoles: function() {
            this.base();
            this._addVisualRole("size", {
                isMeasure: !0,
                requireSingleDimension: !0,
                requireIsDiscrete: !1,
                valueType: Number,
                defaultDimension: this._sizeDimName
            });
        },
        _initPlotsCore: function() {
            new pvc.visual.HeatGridPlot(this);
        },
        _createPlotPanels: function(parentPanel, baseOptions) {
            var heatGridPlot = this.plots.heatGrid;
            this.heatGridChartPanel = new pvc.HeatGridPanel(this, parentPanel, heatGridPlot, Object.create(baseOptions));
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
    def.type("pvc.MetricXYAbstract", pvc.CartesianAbstract).add({
        _processOptionsCore: function(options) {
            this.base(options);
            options.panelSizeRatio = 1;
        },
        _initVisualRoles: function() {
            this.base();
            this._addVisualRole("x", {
                isMeasure: !0,
                isRequired: !0,
                requireSingleDimension: !0,
                requireIsDiscrete: !1,
                defaultDimension: "x",
                dimensionDefaults: {
                    valueType: this.options.timeSeries ? Date : Number
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
        _generateTrendsDataCell: function(newDatums, dataCell, baseData) {
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
                    var trendX = funX(datum);
                    if (trendX) {
                        var trendY = trendModel.sample(trendX, funY(datum), index);
                        if (null != trendY) {
                            var atoms = def.set(Object.create(serData.atoms), xDimName, trendX, yDimName, trendY, dataPartDimName, dataPartAtom);
                            newDatums.push(new pvc.data.TrendDatum(data.owner, atoms, trendOptions));
                        }
                    }
                });
            }
            var serRole = this._serRole, xRole = this.visualRoles.x, yRole = dataCell.role, trendOptions = dataCell.trend, trendInfo = trendOptions.info;
            this._warnSingleContinuousValueRole(yRole);
            var xDimName = xRole.firstDimensionName(), yDimName = yRole.firstDimensionName(), data = this.visibleData(dataCell.dataPartValue, {
                baseData: baseData
            }), dataPartAtom = this._getTrendDataPartAtom(), dataPartDimName = dataPartAtom.dimension.name;
            (serRole.isBound() ? data.children() : def.query([ data ])).each(genSeriesTrend, this);
        }
    });
    def.type("pvc.data.MetricPointChartTranslationOper").add({
        _meaLayoutRoles: [ "x", "y", "color", "size" ],
        configureType: function() {
            var freeMeaIndexes = [], freeDisIndexes = [];
            this.collectFreeDiscreteAndConstinuousIndexes(freeDisIndexes, freeMeaIndexes);
            var N, autoDimNames = [], F = freeMeaIndexes.length;
            if (F > 0) {
                for (var R = this._meaLayoutRoles.length, i = 0; R > i && autoDimNames.length < F; ) {
                    this._getUnboundRoleDefaultDimNames(this._meaLayoutRoles[i], 1, autoDimNames);
                    i++;
                }
                N = autoDimNames.length;
                if (N > 0) {
                    freeMeaIndexes.length = N;
                    this.defReader({
                        names: autoDimNames,
                        indexes: freeMeaIndexes
                    });
                }
            }
            F = freeDisIndexes.length;
            if (F > 0) {
                autoDimNames.length = 0;
                this._getUnboundRoleDefaultDimNames("series", F, autoDimNames);
                N = autoDimNames.length;
                if (N > 0) {
                    freeDisIndexes.length = N;
                    this.defReader({
                        names: autoDimNames,
                        indexes: freeDisIndexes
                    });
                }
            }
        }
    });
    def.type("pvc.MetricPointPanel", pvc.CartesianAbstractPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.axes.size = chart._getAxis("size", (plot.option("SizeAxis") || 0) - 1);
        var sizeRoleName = plot.option("SizeRole");
        this.visualRoles.size = sizeRoleName ? chart.visualRole(sizeRoleName) : null;
        this.linesVisible = plot.option("LinesVisible");
        this.dotsVisible = plot.option("DotsVisible");
        if (!this.linesVisible && !this.dotsVisible) {
            this.linesVisible = !0;
            plot.option.specify({
                LinesVisible: !0
            });
        }
        this.offsetPaddings || (this.offsetPaddings = new pvc_Sides(.01));
    }).add({
        sizeAxisRatio: .2,
        sizeAxisRatioTo: "minWidthHeight",
        autoPaddingByDotSize: !0,
        _v1DimRoleName: {
            category: "x",
            value: "y"
        },
        _creating: function() {
            var groupScene = this.defaultLegendGroupScene();
            if (groupScene && !groupScene.hasRenderer()) {
                var colorAxis = groupScene.colorAxis, drawMarker = def.nullyTo(colorAxis.option("LegendDrawMarker", !0), this.dotsVisible), drawRule = def.nullyTo(colorAxis.option("LegendDrawLine", !0), this.linesVisible);
                if (drawMarker || drawRule) {
                    var keyArgs = {
                        drawMarker: drawMarker,
                        drawRule: drawRule
                    };
                    if (drawMarker) {
                        keyArgs.markerShape = colorAxis.option("LegendShape", !0) || "circle";
                        keyArgs.markerPvProto = new pv.Dot().lineWidth(1.5, pvc.extensionTag).shapeSize(12, pvc.extensionTag);
                        this.extend(keyArgs.markerPvProto, "dot", {
                            constOnly: !0
                        });
                    }
                    if (drawRule) {
                        keyArgs.rulePvProto = new pv.Line().lineWidth(1.5, pvc.extensionTag);
                        this.extend(keyArgs.rulePvProto, "line", {
                            constOnly: !0
                        });
                    }
                    groupScene.renderer(new pvc.visual.legend.BulletItemDefaultRenderer(keyArgs));
                }
            }
        },
        _getRootScene: function() {
            return def.lazy(this, "_rootScene", this._buildScene, this);
        },
        _calcLayout: function(layoutInfo) {
            var rootScene = this._getRootScene();
            rootScene.isSizeBound && this.axes.size.setScaleRange(this._calcDotAreaRange(layoutInfo));
            this._calcAxesPadding(layoutInfo, rootScene);
        },
        _getDotDiameterRefLength: function(layoutInfo) {
            var clientSize = layoutInfo.clientSize, paddings = layoutInfo.paddings;
            switch (this.sizeAxisRatioTo) {
              case "minWidthHeight":
                return Math.min(clientSize.width + paddings.width, clientSize.height + paddings.height);

              case "width":
                return clientSize.width + paddings.width;

              case "height":
                return clientSize.height + paddings.height;
            }
            pvc.debug >= 2 && this._log(def.format("Invalid option 'sizeAxisRatioTo' value. Assuming 'minWidthHeight'.", [ this.sizeAxisRatioTo ]));
            this.sizeRatioTo = "minWidthHeight";
            return this._getDotDiameterRefLength(layoutInfo);
        },
        _calcDotRadiusRange: function(layoutInfo) {
            var refLength = this._getDotDiameterRefLength(layoutInfo), max = this.sizeAxisRatio / 2 * refLength, min = Math.sqrt(12);
            return {
                min: min,
                max: max
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
                radiusRange = {
                    min: Math.sqrt(minArea),
                    max: Math.sqrt(maxArea)
                };
                pvc.debug >= 3 && this._log("Using rescue mode dot area calculation due to insufficient space.");
            }
            return {
                min: minArea,
                max: maxArea,
                span: areaSpan
            };
        },
        _calcAxesPadding: function(layoutInfo, rootScene) {
            var requestPaddings;
            if (this.autoPaddingByDotSize) {
                var axes = this.axes, clientSize = layoutInfo.clientSize, paddings = layoutInfo.paddings;
                requestPaddings = {};
                axes.x.setScaleRange(clientSize.width);
                axes.y.setScaleRange(clientSize.height);
                var isV = this.isOrientationVertical(), sceneXScale = axes.x.sceneScale({
                    sceneVarName: isV ? "x" : "y"
                }), sceneYScale = axes.y.sceneScale({
                    sceneVarName: isV ? "y" : "x"
                }), xMax = axes.x.scale.max, yMax = axes.y.scale.max, hasSizeRole = rootScene.isSizeBound, sizeScale = hasSizeRole ? axes.size.scale : null;
                if (!sizeScale) {
                    var defaultSize = def.number.as(this._getExtension("dot", "shapeRadius"), 0);
                    if (0 >= defaultSize) {
                        defaultSize = def.number.as(this._getExtension("dot", "shapeSize"), 0);
                        0 >= defaultSize && (defaultSize = 12);
                    } else defaultSize = def.sqr(defaultSize);
                    sizeScale = def.fun.constant(defaultSize);
                }
                requestPaddings = {};
                var op;
                if (this.offsetPaddings) {
                    op = {};
                    pvc_Sides.names.forEach(function(side) {
                        var len_a = pvc.BasePanel.orthogonalLength[side];
                        op[side] = (this.offsetPaddings[side] || 0) * (clientSize[len_a] + paddings[len_a]);
                    }, this);
                }
                var setSide = function(side, padding) {
                    op && (padding += op[side] || 0);
                    0 > padding && (padding = 0);
                    var value = requestPaddings[side];
                    (null == value || padding > value) && (requestPaddings[side] = padding);
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
            } else requestPaddings = this._calcRequestPaddings(layoutInfo);
            layoutInfo.requestPaddings = requestPaddings;
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
                activeSeriesAware: me.linesVisible
            }).override("x", function(scene) {
                return scene.basePosition;
            }).override("y", function(scene) {
                return scene.orthoPosition;
            }).override("color", function(scene, type) {
                return me.dotsVisible || scene.isActive || scene.isSingle ? this.base(scene, type) : pvc.invisibleFill;
            });
            rootScene.isSizeBound ? me.autoPaddingByDotSize && "minWidthHeight" === me.sizeAxisRatioTo || me.pvPanel.borderPanel.overflow("hidden") : dot.override("baseSize", function(scene) {
                if (!me.dotsVisible && scene.isSingle) {
                    var lineWidth = Math.max(me.pvLine.scene[this.pvMark.index].lineWidth, .2) / 2;
                    return def.sqr(lineWidth);
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
            }), roles = this.visualRoles, colorVarHelper = (this.axes, new pvc.visual.RoleVarHelper(rootScene, roles.color, {
                roleVar: "color"
            })), sizeVarHelper = new pvc.visual.RoleVarHelper(rootScene, roles.size, {
                roleVar: "size"
            }), xDim = data.owner.dimensions(roles.x.firstDimensionName()), yDim = data.owner.dimensions(roles.y.firstDimensionName());
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
    def.type("pvc.MetricPointAbstract", pvc.MetricXYAbstract).add({
        _trendable: !0,
        _initPlotsCore: function() {
            var pointPlot = this._createPointPlot(), trend = pointPlot.option("Trend");
            (this._trendable = !!trend) && new pvc.visual.MetricPointPlot(this, {
                name: "trend",
                fixed: {
                    DataPart: "trend",
                    TrendType: "none",
                    NullInterpolatioMode: "none",
                    ColorRole: "series",
                    SizeRole: null,
                    SizeAxis: null,
                    OrthoAxis: 1
                },
                defaults: {
                    ColorAxis: 2,
                    LinesVisible: !0,
                    DotsVisible: !1
                }
            });
        },
        _hasDataPartRole: function() {
            return !0;
        },
        _getColorRoleSpec: function() {
            return {
                defaultSourceRole: "series",
                defaultDimension: "color*",
                dimensionDefaults: {
                    valueType: Number
                }
            };
        },
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
        _getTranslationClass: function(translOptions) {
            return def.type(this.base(translOptions)).add(pvc.data.MetricPointChartTranslationOper);
        },
        _createPlotPanels: function(parentPanel, baseOptions) {
            var options = this.options, panelOptions = def.set(Object.create(baseOptions), "sizeAxisRatio", options.sizeAxisRatio, "sizeAxisRatioTo", options.sizeAxisRatioTo, "autoPaddingByDotSize", options.autoPaddingByDotSize), scatterPlot = this.plots.scatter;
            this.scatterChartPanel = new pvc.MetricPointPanel(this, parentPanel, scatterPlot, panelOptions);
            var trendPlot = this.plots.trend;
            trendPlot && new pvc.MetricPointPanel(this, parentPanel, trendPlot, Object.create(panelOptions));
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
    def.type("pvc.BulletChart", pvc.BaseChart).init(function(options) {
        options = options || {};
        var dimGroups = options.dimensionGroups || (options.dimensionGroups = {}), rangeDimGroup = dimGroups.range || (dimGroups.range = {});
        void 0 === rangeDimGroup.valueType && (rangeDimGroup.valueType = Number);
        var markerDimGroup = dimGroups.marker || (dimGroups.marker = {});
        void 0 === markerDimGroup.valueType && (markerDimGroup.valueType = Number);
        this.base(options);
    }).add({
        bulletChartPanel: null,
        allowNoData: !0,
        _processOptionsCore: function(options) {
            options.legend = !1;
            options.selectable = !1;
            this.base(options);
        },
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
                valueType: Number,
                defaultDimension: "value*"
            });
            this._addVisualRole("marker", {
                isMeasure: !0,
                requireIsDiscrete: !1,
                valueType: Number,
                defaultDimension: "marker*"
            });
            this._addVisualRole("range", {
                isMeasure: !0,
                requireIsDiscrete: !1,
                valueType: Number,
                defaultDimension: "range*"
            });
        },
        _createTranslation: function(translOptions) {
            var translation = this.base(translOptions), size = translation.virtualItemSize();
            if (size) switch (size) {
              case 1:
                translation.defReader({
                    names: "value"
                });
                break;

              case 2:
                translation.defReader({
                    names: [ "title", "value" ]
                });
                break;

              case 3:
                translation.defReader({
                    names: [ "title", "value", "marker" ]
                });
                break;

              default:
                translation.defReader({
                    names: [ "title", "subTitle", "value", "marker" ]
                });
                size > 4 && translation.defReader({
                    names: "range",
                    indexes: pv.range(4, size)
                });
            }
            return translation;
        },
        _initPlotsCore: function() {
            new pvc.visual.BulletPlot(this);
        },
        _createContent: function(contentOptions) {
            var bulletPlot = this.plots.bullet;
            this.bulletChartPanel = new pvc.BulletChartPanel(this, this.basePanel, bulletPlot, contentOptions);
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
            seriesInRows: !1
        }
    });
    def.type("pvc.BulletChartPanel", pvc.PlotPanel).add({
        pvBullets: null,
        pvBullet: null,
        data: null,
        onSelectionChange: null,
        _createCore: function(layoutInfo) {
            var size, angle, align, titleLeftOffset, titleTopOffset, ruleAnchor, leftPos, topPos, titleSpace, chart = this.chart, options = chart.options, data = this.buildData(), anchor = "horizontal" == options.orientation ? "left" : "bottom";
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
            this.pvBulletRuleLabel = this.pvBulletRule.anchor(ruleAnchor).add(pv.Label).text(this.pvBullet.x.tickFormat);
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
    def.type("pvc.ParallelCoordinates", pvc.BaseChart).init(function(options) {
        options = options || {};
        options.dimensions = options.dimensions || {};
        options.dimensions.value || (options.dimensions.value = {
            valueType: null
        });
        this.base(options);
    }).add({
        parCoordPanel: null,
        _createContent: function(contentOptions) {
            this.parCoordPanel = new pvc.ParCoordPanel(this, this.basePanel, def.create(contentOptions, {
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
            var values = data.getValues(), dataRowIndex = data.getVisibleSeriesIndexes(), pCoordIndex = data.getVisibleCategoriesIndexes(), pCoordKeys = data.getCategories(), pCoordMapping = pCoordIndex.map(this.chart.options.mapAllDimensions ? function(d) {
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
            } : function(d) {
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
            this.pvParCoord = this.pvPanel.add(pv.Panel).data(myself.data).visible(selectVisible).add(pv.Line).data(dims).left(function(t) {
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
            var change = (this.pvPanel.add(pv.Panel).data(labels).add(pv.Label).left(function(d) {
                return d.x;
            }).bottom(function(d) {
                return d.y;
            }).text(function(d) {
                return d.label;
            }).textAlign("left"), this.pvPanel.add(pv.Panel)), handle = (change.add(pv.Panel).data(myself.data).visible(selectVisible).add(pv.Line).data(dims).left(function(t) {
                return x(t);
            }).bottom(function(t, d) {
                return y[t](d[t]);
            }).strokeStyle(function(t, d) {
                var dd = dimDescr[active], val = dd.orgValue && !dd.categorical ? dd.orgValue[d[active]] : d[active];
                return colors[active](val);
            }).lineWidth(1), change.add(pv.Panel).data(dims.map(function(dim) {
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
            }).strokeStyle("white").cursor("move").event("mousedown", pv.Behavior.drag()).event("dragstart", update).event("drag", update));
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
            this.structDataset.length || this._log("Warning: Structure-dataset is empty");
            this.structMetadata = data.metadata;
            this.structMetadata.length || this._log("Warning: Structure-Metadata is empty");
        },
        _createContent: function(contentOptions) {
            var structEngine = this.structEngine, structType = structEngine ? structEngine.type : new pvc.data.ComplexType();
            structType.addDimension("value", {});
            var translOptions = {
                seriesInRows: !0,
                crosstabMode: !0
            }, translation = new pvc.data.CrosstabTranslationOper(structType, this.structDataset, this.structMetadata, translOptions);
            translation.configureType();
            structEngine || (structEngine = this.structEngine = new pvc.data.Data({
                type: structType
            }));
            structEngine.load(translation.execute(structEngine));
            pvc.debug >= 3 && this._log(this.structEngine.getInfo());
            this.dataTreePanel = new pvc.DataTreePanel(this, this.basePanel, def.create(contentOptions, {
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
            this._log("Error: value with key : " + key + " not found.");
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
                            this._log(" dataset " + elem.box_id + " repaired (_p95 was smaller than _p5)");
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
            var i, data = this.chart.data, options = this.chart.options, selectors = (data.getVisibleCategories(), 
            data.getVisibleSeries()), values = data.getValues(), selMap = {}, numCols = values.length;
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
            for (var sel in boxNotFound) this._log("Could'nt find box for selector: " + sel);
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
    def.type("pvc.data.BoxplotChartTranslationOper").add({
        _configureTypeCore: function() {
            var autoDimNames = [], freeMeaIndexes = [], freeDisIndexes = [];
            this.collectFreeDiscreteAndConstinuousIndexes(freeDisIndexes, freeMeaIndexes);
            this._getUnboundRoleDefaultDimNames("category", freeDisIndexes.length, autoDimNames);
            def.query(pvc.BoxplotChart.measureRolesNames).take(freeMeaIndexes.length).each(function(roleName) {
                this._getUnboundRoleDefaultDimNames(roleName, 1, autoDimNames);
            }, this);
            autoDimNames.length && this.defReader({
                names: autoDimNames
            });
        }
    });
    def.type("pvc.BoxplotPanel", pvc.CategoricalAbstractPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.boxSizeRatio = plot.option("BoxSizeRatio");
        this.maxBoxSize = plot.option("BoxSizeMax");
    }).add({
        plotType: "box",
        anchor: "fill",
        _v1DimRoleName: {
            value: "median"
        },
        _createCore: function() {
            function defaultColor(scene, type) {
                var color = this.base(scene, type);
                return "stroke" === type ? color.darker(1) : color;
            }
            function setupRuleWhisker(rule) {
                rule.lock(a_left, function() {
                    return this.pvMark.parent[a_width]() / 2;
                }).override("defaultColor", defaultColor);
                return rule;
            }
            function setupHCateg(sign) {
                sign.lockMark(a_left, function(scene) {
                    return scene.vars.category.boxLeft;
                }).lockMark(a_width, function(scene) {
                    return scene.vars.category.boxWidth;
                });
                return sign;
            }
            function setupHRule(rule) {
                setupHCateg(rule);
                rule.override("defaultColor", defaultColor);
                return rule;
            }
            this.base();
            var rootScene = this._buildScene(), a_bottom = this.isOrientationVertical() ? "bottom" : "left", a_left = this.anchorOrtho(a_bottom), a_width = this.anchorLength(a_bottom), a_height = this.anchorOrthoLength(a_bottom), extensionIds = [ "panel" ];
            this.compatVersion() <= 1 && extensionIds.push("");
            this.pvBoxPanel = new pvc.visual.Panel(this, this.pvPanel, {
                extensionId: extensionIds
            }).lock("data", rootScene.childNodes).lockMark(a_left, function(scene) {
                var catVar = scene.vars.category;
                return catVar.x - catVar.width / 2;
            }).pvMark[a_width](function(scene) {
                return scene.vars.category.width;
            });
            this.pvRuleWhiskerUpper = setupRuleWhisker(new pvc.visual.Rule(this, this.pvBoxPanel, {
                extensionId: "boxRuleWhisker",
                freePosition: !0,
                noHover: !1,
                noSelect: !1,
                noClick: !1,
                noDoubleClick: !1,
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
                showsInteraction: !0
            })).intercept("visible", function(scene) {
                return scene.vars.category.showRuleWhiskerBelow && this.delegateExtension(!0);
            }).pvMark.lock(a_bottom, function(scene) {
                return scene.vars.category.ruleWhiskerLowerBottom;
            }).lock(a_height, function(scene) {
                return scene.vars.category.ruleWhiskerLowerHeight;
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
            }).override("defaultColor", defaultColor).override("defaultStrokeWidth", def.fun.constant(1)).pvMark;
            this.pvRuleMin = setupHRule(new pvc.visual.Rule(this, this.pvBoxPanel, {
                extensionId: "boxRuleMin",
                freePosition: !0,
                noHover: !1,
                noSelect: !1,
                noClick: !1,
                noDoubleClick: !1,
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
        _buildScene: function() {
            function createCategScene(categData) {
                var categScene = new pvc.visual.Scene(rootScene, {
                    source: categData
                }), vars = categScene.vars;
                vars.series = new pvc_ValueLabelVar(null, "");
                var catVar = vars.category = new pvc_ValueLabelVar(categData.value, categData.label);
                def.set(catVar, "group", categData, "x", baseScale(categData.value), "width", bandWidth, "boxWidth", boxWidth, "boxLeft", bandWidth / 2 - boxWidth / 2);
                chart.measureVisualRoles().forEach(function(role) {
                    var svar, dimName = measureRolesDimNames[role.name];
                    if (dimName) {
                        var dim = categData.dimensions(dimName), value = dim.value(visibleKeyArgs);
                        svar = new pvc_ValueLabelVar(value, dim.format(value));
                        svar.position = orthoScale(value);
                    } else {
                        svar = new pvc_ValueLabelVar(null, "");
                        svar.position = null;
                    }
                    vars[role.name] = svar;
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
            var chart = this.chart, measureRolesDimNames = def.query(chart.measureVisualRoles()).object({
                name: function(role) {
                    return role.name;
                },
                value: function(role) {
                    return role.firstDimensionName();
                }
            }), visibleKeyArgs = {
                visible: !0,
                zeroIfNone: !1
            }, data = this.visibleData({
                ignoreNulls: !1
            }), rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: data
            }), baseScale = this.axes.base.scale, bandWidth = baseScale.range().band, boxWidth = Math.min(bandWidth * this.boxSizeRatio, this.maxBoxSize), orthoScale = this.axes.ortho.scale, colorVarHelper = new pvc.visual.RoleVarHelper(rootScene, this.visualRoles.color, {
                roleVar: "color"
            });
            data.children().each(createCategScene, this);
            return rootScene;
        }
    });
    def.type("pvc.BoxplotChart", pvc.CategoricalAbstract).add({
        _processOptionsCore: function(options) {
            this.base.apply(this, arguments);
            options.stacked = !1;
        },
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
                defaultDimension: "median",
                isRequired: !0
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
        _getTranslationClass: function(translOptions) {
            return def.type(this.base(translOptions)).add(pvc.data.BoxplotChartTranslationOper);
        },
        _initPlotsCore: function() {
            new pvc.visual.BoxPlot(this);
            if (this.options.plot2) {
                this._animatable = !0;
                new pvc.visual.PointPlot(this, {
                    name: "plot2",
                    defaults: {
                        LinesVisible: !0,
                        DotsVisible: !0,
                        OrthoRole: "median",
                        ColorAxis: 2
                    },
                    fixed: {
                        OrthoAxis: 1
                    }
                });
            }
        },
        _initAxes: function(hasMultiRole) {
            this.base(hasMultiRole);
            var typeAxes = this.axesByType.ortho;
            typeAxes && typeAxes.forEach(function(axis) {
                axis.option.defaults({
                    Offset: .02
                });
            });
        },
        _createPlotPanels: function(parentPanel, baseOptions) {
            var plots = this.plots, boxPlot = plots.box, boxPanel = new pvc.BoxplotPanel(this, parentPanel, boxPlot, Object.create(baseOptions));
            this.bpChartPanel = boxPanel;
            var plot2Plot = plots.plot2;
            if (plot2Plot) {
                pvc.debug >= 3 && this._log("Creating Point panel.");
                var pointPanel = new pvc.PointPanel(this, parentPanel, plot2Plot, Object.create(baseOptions));
                pointPanel._v1DimRoleName.value = plot2Plot.option("OrthoRole");
            }
        },
        defaults: {
            crosstabMode: !1
        }
    }).addStatic({
        measureRolesNames: [ "median", "lowerQuartil", "upperQuartil", "minimum", "maximum" ]
    });
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
                    } else c = baseScale(k);
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
                scale.range = function() {
                    if (arguments.length) throw def.error.operationInvalid("The scale cannot be modified.");
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
            return candidates.where(this.isByParent ? function(itemData) {
                return itemData.parent ? isNotDegenerate(itemData) && hasChildren(itemData) : isLeaf(itemData) || children(itemData).any(isLeaf);
            } : function(itemData) {
                return (!itemData.parent || isNotDegenerate(itemData)) && isLeaf(itemData);
            });
        }
    });
    def.type("pvc.data.TreemapChartTranslationOper").add({
        _configureTypeCore: function() {
            var autoDimNames = [], freeMeaIndexes = [], freeDisIndexes = [];
            this.collectFreeDiscreteAndConstinuousIndexes(freeDisIndexes, freeMeaIndexes);
            var D = freeDisIndexes.length, M = freeMeaIndexes.length;
            D && this._getUnboundRoleDefaultDimNames("category", D, autoDimNames);
            M && def.query([ "size", "color" ]).take(M).each(function(roleName) {
                this._getUnboundRoleDefaultDimNames(roleName, 1, autoDimNames);
            }, this);
            autoDimNames.length && this.defReader({
                names: autoDimNames
            });
        }
    });
    def.type("pvc.TreemapPanel", pvc.PlotPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.axes.size = chart._getAxis("size", (plot.option("SizeAxis") || 0) - 1);
        this.visualRoles.size = chart.visualRole(plot.option("SizeRole"));
        this.layoutMode = plot.option("LayoutMode");
    }).add({
        _createCore: function(layoutInfo) {
            var me = this, cs = layoutInfo.clientSize, rootScene = me._buildScene();
            if (rootScene) {
                if (!rootScene.childNodes.length && !this.visualRoles.multiChart.isBound()) throw new InvalidDataException("Unable to create a treemap chart, please check the data values.");
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
                var colorScale, colorAxis = me.axes.color;
                colorScale = me.visualRoles.color.isBound() ? colorAxis.sceneScale({
                    sceneVarName: "color"
                }) : def.fun.constant(colorAxis.option("Unbound"));
                var pvLeafMark = new pvc.visual.Bar(me, panel.leaf, {
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
                    noDoubleClick: !0
                }).intercept("visible", function(scene) {
                    return !!scene.parent && !!scene.firstChild && this.delegateExtension(!0);
                }).override("anyInteraction", function(scene) {
                    return scene.anyInteraction() || scene.isActiveDescendantOrSelf();
                }).override("defaultStrokeWidth", function() {
                    return 1.5 * lw;
                }).override("interactiveStrokeWidth", function(scene, w) {
                    this.showsActivity() && scene.isActiveDescendantOrSelf() && (w = 1.5 * Math.max(1, w));
                    return w;
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
                        var ta = pvLabel.textAngle();
                        isHorizText = Math.abs(Math.sin(ta)) < 1e-6, isVertiText = !isHorizText && Math.abs(Math.cos(ta)) < 1e-6;
                        if (isHorizText || isVertiText) {
                            var twMax, hide = !1, m = pv.Text.measure(text, pvLabel.font()), th = .75 * m.height, thMax = scene[isVertiText ? "dx" : "dy"];
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
        _getExtensionId: function() {
            var extensionIds = [ {
                abs: this.chart.parent ? "smallContent" : "content"
            } ];
            return extensionIds.concat(this.base());
        },
        renderInteractive: function() {
            this.pvTreemapPanel.render();
        },
        _buildScene: function() {
            var data = this.visibleData({
                ignoreNulls: !1
            });
            if (!data.childCount()) return null;
            var roles = this.visualRoles, rootScene = new pvc.visual.Scene(null, {
                panel: this,
                source: data
            }), sizeVarHelper = new pvc.visual.RoleVarHelper(rootScene, roles.size, {
                roleVar: "size",
                allowNestedVars: !0,
                hasPercentSubVar: !0
            }), sizeIsBound = roles.size.isBound(), colorGrouping = roles.color && roles.color.grouping, colorByParent = colorGrouping && "byparent" === this.plot.option("ColorMode"), recursive = function(scene) {
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
                children.length && children.forEach(function(childData) {
                    recursive(new pvc.visual.Scene(scene, {
                        source: childData
                    }));
                });
                return scene;
            };
            return recursive(rootScene);
        }
    });
    def.type("pvc.TreemapChart", pvc.BaseChart).add({
        _animatable: !1,
        _axisClassByType: {
            size: pvc.visual.NormalizedAxis
        },
        _axisCreateIfUnbound: {
            color: !0
        },
        _getColorRoleSpec: function() {
            return {
                defaultSourceRole: "category",
                defaultDimension: "color*"
            };
        },
        _initVisualRoles: function() {
            this.base();
            this._addVisualRole("category", {
                isRequired: !0,
                defaultDimension: "category*",
                autoCreateDimension: !0
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
        _getTranslationClass: function(translOptions) {
            return def.type(this.base(translOptions)).add(pvc.data.TreemapChartTranslationOper);
        },
        _getIsNullDatum: def.fun.constant(),
        _initPlotsCore: function() {
            var treemapPlot = new pvc.visual.TreemapPlot(this);
            null == this.options.legend && (this.options.legend = "byparent" === treemapPlot.option("ColorMode"));
            var rootCategoryLabel = treemapPlot.option("RootCategoryLabel");
            this.visualRoles.category.setRootLabel(rootCategoryLabel);
            this.visualRoles.color.setRootLabel(rootCategoryLabel);
        },
        _initAxes: function(hasMultiRole) {
            if (this.visualRoles.color.isDiscrete()) {
                def.hasOwnProp.call(this, "_axisClassByType") || (this._axisClassByType = Object.create(this._axisClassByType));
                this._axisClassByType.color = pvc.visual.TreemapDiscreteColorAxis;
            } else delete this._axisClassByType;
            return this.base(hasMultiRole);
        },
        _createContent: function(contentOptions) {
            this.base();
            var treemapPlot = this.plots.treemap;
            new pvc.TreemapPanel(this, this.basePanel, treemapPlot, contentOptions);
        },
        _createVisibleData: function(baseData, ka) {
            return this.visualRoles.category.select(baseData, ka);
        },
        defaults: {
            legend: null
        }
    });
    def.type("pvc.visual.SunburstDiscreteColorAxis", pvc.visual.ColorAxis).add({
        _getOptionsDefinition: function() {
            return sunburstColorAxis_optionsDef;
        },
        domainItemValueProp: function() {
            return this.role && this.role.grouping.isSingleDimension ? "value" : "absKey";
        },
        domainGroupOperator: function() {
            return "select";
        },
        _selectDomainItems: function(domainData) {
            var candidates = def.query(domainData.nodes()), isNotDegenerate = function(data) {
                return null != data.value;
            };
            return candidates.where(function(itemData) {
                return itemData.parent ? isNotDegenerate(itemData) && !itemData.parent.parent : !1;
            });
        }
    });
    var sunburstColorAxis_optionsDef = def.create(colorAxis_optionsDef, {
        SliceBrightnessFactor: {
            resolve: "_resolveFull",
            cast: pvc.castNonNegativeNumber,
            value: 1
        }
    });
    def.type("pvc.visual.SunburstSlice", pvc.visual.Sign).init(function(panel, protoMark, keyArgs) {
        var pvMark = protoMark.add(pv.Wedge);
        keyArgs = def.setDefaults(keyArgs, "freeColor", !1);
        this.base(panel, pvMark, keyArgs);
        this._bindProperty("lineWidth", "strokeWidth");
    }).prototype.property("strokeWidth").constructor.add({
        defaultStrokeWidth: def.fun.constant(.5),
        interactiveStrokeWidth: function(scene, strokeWidth) {
            return this.showsActivity() && scene.isActiveDescendantOrSelf() ? 2 * Math.max(1, strokeWidth) : strokeWidth;
        },
        defaultColor: function(scene) {
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
    });
    def.type("pvc.data.SunburstChartTranslationOper").add({
        _configureTypeCore: function() {
            var autoDimNames = [], freeMeaIndexes = [], freeDisIndexes = [];
            this.collectFreeDiscreteAndConstinuousIndexes(freeDisIndexes, freeMeaIndexes);
            var D = freeDisIndexes.length, M = freeMeaIndexes.length;
            D && this._getUnboundRoleDefaultDimNames("category", D, autoDimNames);
            M && this._getUnboundRoleDefaultDimNames("size", 1, autoDimNames);
            autoDimNames.length && this.defReader({
                names: autoDimNames
            });
        }
    });
    def.type("pvc.SunburstPanel", pvc.PlotPanel).init(function(chart, parent, plot, options) {
        this.base(chart, parent, plot, options);
        this.axes.size = chart._getAxis("size", (plot.option("SizeAxis") || 0) - 1);
        this.visualRoles.size = chart.visualRole(plot.option("SizeRole"));
        this.sliceOrder = plot.option("SliceOrder");
        this.emptySlicesVisible = plot.option("EmptySlicesVisible");
        this.emptySlicesLabel = this.emptySlicesVisible ? plot.option("EmptySlicesLabel") : "";
    }).add({
        _createCore: function(layoutInfo) {
            var labelFont = this._getConstantExtension("label", "font");
            def.string.is(labelFont) && (this.valuesFont = labelFont);
            var me = this, rootScene = (layoutInfo.clientSize, me._buildScene());
            if (rootScene) {
                if (!rootScene.childNodes.length && !this.visualRoles.multiChart.isBound()) throw new InvalidDataException("Unable to create a sunburst chart, please check the data values.");
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
                            var twMax, ir = scene.innerRadius, irmin = ir, or = scene.outerRadius, tm = pvLabel.textMargin(), a = scene.angle, m = pv.Text.measure(text, pvLabel.font()), hide = !1;
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
        _getExtensionId: function() {
            var extensionIds = [ {
                abs: this.chart.parent ? "smallContent" : "content"
            } ];
            return extensionIds.concat(this.base());
        },
        renderInteractive: function() {
            this.pvSunburstPanel.render();
        },
        _buildScene: function() {
            var data = this.visibleData({
                ignoreNulls: !1
            }), emptySlicesVisible = this.emptySlicesVisible, emptySlicesLabel = this.emptySlicesLabel;
            if (!data.childCount()) return null;
            var colorScale, roles = this.visualRoles, rootScene = new pvc.visual.SunburstScene(null, {
                panel: this,
                source: data
            }), sizeIsBound = roles.size.isBound(), sizeVarHelper = new pvc.visual.RoleVarHelper(rootScene, roles.size, {
                roleVar: "size",
                allowNestedVars: !0,
                hasPercentSubVar: !0
            }), colorGrouping = roles.color && roles.color.grouping, colorAxis = this.axes.color, colorBrightnessFactor = colorAxis.option("SliceBrightnessFactor");
            colorScale = roles.color.isBound() ? colorAxis.sceneScale({
                sceneVarName: "color"
            }) : def.fun.constant(colorAxis.option("Unbound"));
            var recursive = function(scene) {
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
            }, calculateColor = function(scene, index, siblingsSize) {
                var baseColor = null, parent = scene.parent;
                if (parent) {
                    baseColor = colorScale(scene);
                    if (!parent.isRoot() && !baseColor.isFixedColor) {
                        baseColor = parent.color;
                        index && colorBrightnessFactor && (baseColor = baseColor.brighter(colorBrightnessFactor * index / (siblingsSize - 1)));
                    }
                }
                scene.color = baseColor;
                var children = scene.childNodes, childrenSize = children.length;
                children.forEach(function(childScene, index) {
                    calculateColor(childScene, index, childrenSize);
                });
            };
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
    def.type("pvc.visual.SunburstScene", pvc.visual.Scene).add({
        _createSelectedInfo: function() {
            var any = this.chart().data.owner.selectedCount() > 0, isSelected = any && this.datums().all(datum_isSelected);
            return {
                any: any,
                is: isSelected
            };
        }
    });
    def.type("pvc.SunburstChart", pvc.BaseChart).add({
        _animatable: !1,
        _axisClassByType: {
            size: pvc.visual.NormalizedAxis
        },
        _axisCreateIfUnbound: {
            color: !0
        },
        _getColorRoleSpec: function() {
            return {
                defaultSourceRole: "category",
                defaultDimension: "color*",
                requireIsDiscrete: !0
            };
        },
        _initVisualRoles: function() {
            this.base();
            this._addVisualRole("category", {
                isRequired: !0,
                defaultDimension: "category*",
                autoCreateDimension: !0
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
        _getTranslationClass: function(translOptions) {
            return def.type(this.base(translOptions)).add(pvc.data.SunburstChartTranslationOper);
        },
        _getIsNullDatum: def.fun.constant(),
        _initPlotsCore: function() {
            var sunburstPlot = new pvc.visual.SunburstPlot(this);
            this.options.legend = !1;
            var rootCategoryLabel = sunburstPlot.option("RootCategoryLabel");
            this.visualRoles.category.setRootLabel(rootCategoryLabel);
            this.visualRoles.color.setRootLabel(rootCategoryLabel);
        },
        _initAxes: function(hasMultiRole) {
            def.hasOwnProp.call(this, "_axisClassByType") || (this._axisClassByType = Object.create(this._axisClassByType));
            this._axisClassByType.color = pvc.visual.SunburstDiscreteColorAxis;
            return this.base(hasMultiRole);
        },
        _createContent: function(contentOptions) {
            this.base();
            var sunburstPlot = this.plots.sunburst;
            new pvc.SunburstPanel(this, this.basePanel, sunburstPlot, contentOptions);
        },
        _createVisibleData: function(baseData, ka) {
            return this.visualRoles.category.select(baseData, ka);
        }
    });
    return pvc;
}(def, pv);