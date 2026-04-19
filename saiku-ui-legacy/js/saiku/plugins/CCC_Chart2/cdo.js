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

var cdo = function(def, pv) {
    function cdo_disposeChildList(list, parentProp) {
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
    function cdo_addColChild(parent, childrenProp, child, parentProp, index) {
        child[parentProp] = parent;
        var col = parent[childrenProp] || (parent[childrenProp] = []);
        null == index || index >= col.length ? col.push(child) : col.splice(index, 0, child);
    }
    function cdo_removeColChild(parent, childrenProp, child, parentProp) {
        var index, children = parent[childrenProp];
        children && (index = children.indexOf(child)) >= 0 && def.array.removeAt(children, index);
        child[parentProp] = null;
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
            label = def.string.to(label);
            !label && def.debug >= 2 && def.log("Only the null value should have an empty label.");
            atom = new cdo.Atom(this, value, label, sourceValue, key);
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
    function dim_createNullAtom(sourceLabel) {
        var nullAtom = this._nullAtom;
        if (!nullAtom) {
            if (this.owner === this) {
                var label = sourceLabel;
                if (null == sourceLabel) {
                    var typeFormatter = this.type._formatter;
                    label = typeFormatter ? def.string.to(typeFormatter.call(null, null, null)) : "";
                }
                nullAtom = new cdo.Atom(this, null, label, null, "");
                this.data._atomsBase[this.name] = nullAtom;
            } else nullAtom = dim_createNullAtom.call(this.parent || this.linkParent, sourceLabel);
            this._atomsByKey[""] = this._nullAtom = nullAtom;
            this._atoms.unshift(nullAtom);
        }
        return nullAtom;
    }
    function dim_createVirtualNullAtom() {
        this.owner === this || def.assert("Can only create atoms on an owner dimension.");
        if (!this._virtualNullAtom) {
            this._virtualNullAtom = new cdo.Atom(this, null, "", null, "");
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
        cdo_addColChild(this, "childNodes", child, "parent");
        child.owner = this.owner;
    }
    function dim_addLinkChild(linkChild) {
        cdo_addColChild(this, "_linkChildren", linkChild, "linkParent");
        linkChild.owner = this.owner;
    }
    function dim_onDatumVisibleChanged(datum, visible) {
        var map;
        if (!this._disposed && (map = this._atomVisibleDatumsCount)) {
            var atom = datum.atoms[this.name], key = atom.key, count = map[key];
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
    function cdo_addChild(child, index) {
        this.insertAt(child, index);
        def.lazy(this, "_childrenByKey")[child.key] = child;
    }
    function cdo_addLinkChild(linkChild, index) {
        cdo_addColChild(this, "_linkChildren", linkChild, "linkParent", index);
    }
    function cdo_removeLinkChild(linkChild) {
        cdo_removeColChild(this, "_linkChildren", linkChild, "linkParent");
    }
    function cdo_disposeChildLists() {
        cdo_disposeChildList(this.childNodes, "parent");
        this._childrenByKey = null;
        cdo_disposeChildList(this._linkChildren, "linkParent");
        this._groupByCache = null;
        this._sumAbsCache = null;
    }
    function cdo_assertIsOwner() {
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
    function data_ancestorsAndSelfList(data) {
        for (var p, ancestors = [ data ]; p = data.parent || data.linkParent; ) ancestors.unshift(data = p);
        return ancestors;
    }
    function data_lowestCommonAncestor(listA, listB) {
        for (var next, i = 0, L = Math.min(listA.length, listB.length), a = null; L > i && (next = listA[i]) === listB[i]; ) {
            a = next;
            i++;
        }
        return a;
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
                    if (!newDatum.isNull) {
                        selDatums && newDatum.isSelected && selDatums.set(id, newDatum);
                        newDatum.isVisible && visDatums.set(id, newDatum);
                    }
                }
            }
        }
        addDatums || def.fail.argumentRequired("addDatums");
        var i, L, oldDatumsByKey, oldDatumsById, newDatums, datums, datumsByKey, datumsById, doAtomGC = def.get(keyArgs, "doAtomGC", !1), isAdditive = def.get(keyArgs, "isAdditive", !1), internNewAtoms = !!this._dimensions, visDatums = this._visibleNotNullDatums, selDatums = this._selectedNotNullDatums, oldDatums = this._datums;
        if (oldDatums) {
            oldDatumsByKey = this._datumsByKey;
            oldDatumsById = this._datumsById;
        } else isAdditive = !1;
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
                cdo_disposeChildLists.call(this);
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
        this.select && this.select(datums).forEach(cdo_removeDatumLocal, this);
        oldDatums && isAdditive && doAtomGC && oldDatums.forEach(function(oldDatum) {
            data_processDatumAtoms.call(this, oldDatum, !1, !0);
        }, this);
        if (newDatums || !isAdditive) {
            isAdditive || (newDatums = datums);
            newDatums.forEach(function(newDatum) {
                data_processDatumAtoms.call(this, newDatum, internNewAtoms, doAtomGC);
                if (!newDatum.isNull) {
                    var id = newDatum.id;
                    selDatums && newDatum.isSelected && selDatums.set(id, newDatum);
                    newDatum.isVisible && visDatums.set(id, newDatum);
                }
            }, this);
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
                for (;L > i; ) cdo_addDatumsSimple.call(linkChildren[i++], newDatums);
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
    function cdo_addDatumsSimple(newDatums) {
        newDatums || def.fail.argumentRequired("newDatums");
        var groupOper = this._groupOper;
        if (groupOper) newDatums = groupOper.executeAdd(this, newDatums); else {
            var wherePred = this._wherePred;
            wherePred && (newDatums = newDatums.filter(wherePred));
            cdo_addDatumsLocal.call(this, newDatums);
        }
        var list = this._linkChildren, L = list && list.length;
        if (L) for (var i = 0; L > i; i++) cdo_addDatumsSimple.call(list[i], newDatums);
    }
    function cdo_addDatumsLocal(newDatums) {
        var me = this, ds = me._datums, vds = me._visibleNotNullDatums, sds = me._selectedNotNullDatums, dsById = me._datumsById;
        me._sumAbsCache = null;
        for (var i = 0, L = newDatums.length; L > i; i++) {
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
    function cdo_removeDatumLocal(datum) {
        var datums = this._datums, selDatums = this._selectedNotNullDatums, id = datum.id;
        datums.splice(datums.indexOf(datum), 1);
        delete this._datumsById[id];
        delete this._datumsByKey[datum.key];
        selDatums && datum.isSelected && selDatums.rem(id);
        datum.isVisible && this._visibleNotNullDatums.rem(id);
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
        whereSpec && ps.unshift(cdo_whereSpecPredicate(whereSpec));
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
    function cdo_whereSpecPredicate(whereSpec) {
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
    function groupSpec_parseGroupingLevel(groupLevelText, type) {
        def.string.is(groupLevelText) || def.fail.argumentInvalid("groupLevelText", "Invalid grouping specification.");
        return def.query(groupLevelText.split(/\s*\|\s*/)).where(def.truthy).select(function(dimSpecText) {
            var match = groupSpec_matchDimSpec.exec(dimSpecText) || def.fail.argumentInvalid("groupLevelText", "Invalid grouping level syntax '{0}'.", [ dimSpecText ]), name = match[1], order = (match[2] || "").toLowerCase(), reverse = "desc" === order;
            return new cdo.GroupingDimensionSpec(name, reverse, type);
        });
    }
    function relTransl_dataPartGet(plot2DataSeriesIndexes, seriesReader) {
        function calcAxis2SeriesKeySet() {
            var atoms = {}, seriesKeys = def.query(me.source).select(function(row) {
                seriesReader(row, atoms);
                var value = atoms.series;
                null != value && null != value.v && (value = value.v);
                return value || null;
            }).distinct().array();
            return me._createPlot2SeriesKeySet(plot2DataSeriesIndexes, seriesKeys);
        }
        var me = this;
        this._dataPartGet(calcAxis2SeriesKeySet);
    }
    function numForm_tryConfigure(other) {
        return def.string.is(other) ? !!this.mask(other) : def.is(other, numForm) ? !!this.mask(other.mask()).style(other.style()) : void 0;
    }
    function numForm_cachedFormatter(mask) {
        mask || (mask = "");
        var key = "_" + mask, f = numForm_cache[key];
        if (!f) {
            if (numForm_cacheCount === numForm.cacheLimit) {
                numForm_cache = {};
                numForm_cacheCount = 0;
            }
            numForm_cache[key] = f = numberFormatter(mask);
            numForm_cacheCount++;
        }
        return f;
    }
    function numberFormatter(mask) {
        function formatter(value, style) {
            if (null == value) return nullFormat ? nullFormat(style) : "";
            value = +value;
            return isNaN(value) || !isFinite(value) ? "" : posFormat ? 0 === value ? zeroFormat ? zeroFormat(style) : posFormat(style, value, null, !1) : value > 0 ? posFormat(style, value, zeroFormat, !1) : negFormat ? negFormat(style, -value, zeroFormat || posFormat) : posFormat(style, -value, zeroFormat, !0) : String(value);
        }
        function compileMask() {
            var L, section, posSection, sections = numForm_parseMask(mask);
            sections.forEach(numForm_compileSection);
            L = sections.length;
            posFormat = nullFormat = negFormat = zeroFormat = null;
            if (L) {
                posFormat = numForm_buildFormatSectionPosNeg(posSection = sections[0]);
                if (L > 1) {
                    section = sections[1];
                    negFormat = numForm_buildFormatSectionPosNeg(section.empty ? posSection : section);
                    if (L > 2) {
                        section = sections[2];
                        zeroFormat = numForm_buildFormatSectionZero(section.empty ? posSection : section);
                        if (L > 3) {
                            section = sections[3];
                            nullFormat = numForm_buildFormatSectionNull(section.empty ? posSection : section);
                            if (L > 4) throw new Error("Invalid mask. More than 4 sections.");
                        }
                    }
                }
            }
        }
        var posFormat, negFormat, zeroFormat, nullFormat;
        compileMask();
        return formatter;
    }
    function numForm_parseMask(mask) {
        var sections = [];
        if (mask) {
            var c, section, part, dcount, i = -1, L = mask.length, textFrag = "", empty = 1, beforeDecimal = 1, hasInteger = 0, hasDot = 0, addToken0 = function(token) {
                empty = 0;
                part.list.push(token);
            }, addTextFrag = function(t) {
                empty = 0;
                textFrag += t;
            }, endTextFrag = function() {
                if (textFrag) {
                    tryParseAbbrCurr();
                    addToken0({
                        type: 0,
                        text: textFrag
                    });
                    textFrag = "";
                }
            }, addToken = function(token) {
                endTextFrag();
                addToken0(token);
            }, endInteger = function() {
                endTextFrag();
                !hasInteger && hasDot && addToken0({
                    type: 2
                });
                part.digits = dcount;
                dcount = 0;
                beforeDecimal = 0;
                part = section.fractional;
            }, endSection = function() {
                if (section && (!empty || !sections.length)) {
                    beforeDecimal ? endInteger() : endTextFrag();
                    part.digits = dcount;
                    sections.push(section);
                }
                empty = beforeDecimal = 1;
                hasDot = dcount = hasInteger = 0;
                section = {
                    empty: 0,
                    scale: 0,
                    groupOn: 0,
                    scientific: 0,
                    abbreviationOn: 0,
                    integer: {
                        list: [],
                        digits: 0
                    },
                    fractional: {
                        list: [],
                        digits: 0
                    }
                };
                part = section.integer;
            }, tryParseAbbrCurr = function() {
                if ("A" === textFrag) {
                    textFrag = "";
                    addToken({
                        type: 6
                    });
                } else if ("C" === textFrag) {
                    textFrag = "";
                    addToken({
                        type: 4
                    });
                } else if ("AC" === textFrag) {
                    textFrag = "";
                    addToken({
                        type: 6
                    });
                    addToken({
                        type: 4
                    });
                } else if ("CA" === textFrag) {
                    textFrag = "";
                    addToken({
                        type: 4
                    });
                    addToken({
                        type: 6
                    });
                }
            }, tryParseExponent = function() {
                var c2, k = i + 1, positive = !1, digits = 0;
                if (L > k) {
                    c2 = mask.charAt(k);
                    if ("-" === c2 || "+" === c2) {
                        positive = "+" === c2;
                        if (!(++k < L)) return 0;
                        c2 = mask.charAt(k);
                    }
                    for (;"0" === c2 && ++digits && ++k < L && (c2 = mask.charAt(k)); ) ;
                    if (digits) {
                        i = k - 1;
                        addToken({
                            type: 5,
                            text: c,
                            digits: digits,
                            positive: positive
                        });
                        section.scientific = 1;
                        return 1;
                    }
                }
                return 0;
            };
            endSection();
            for (;++i < L; ) {
                c = mask.charAt(i);
                if ("0" === c) {
                    addToken({
                        type: 1
                    });
                    hasInteger = 1;
                    dcount++;
                } else if ("#" === c) {
                    addToken({
                        type: 2
                    });
                    hasInteger = 1;
                    dcount++;
                } else if ("," === c) beforeDecimal && addToken({
                    type: 3
                }); else if ("." === c) {
                    if (beforeDecimal) {
                        hasDot = 1;
                        endInteger();
                    }
                } else if ("¤" === c) addToken({
                    type: 4
                }); else if ("C" === c && "Currency" === mask.substring(i, i + 8)) {
                    addToken({
                        type: 4
                    });
                    i += 7;
                } else if ("A" === c && "Abbreviation" === mask.substring(i, i + 12)) {
                    addToken({
                        type: 6
                    });
                    i += 11;
                } else if (";" === c) {
                    endSection();
                    if (i + 1 >= L || ";" === mask.charAt(i + 1)) {
                        i++;
                        sections.push({
                            empty: 1
                        });
                    }
                } else if ("\\" === c) {
                    i++;
                    L > i && addTextFrag(mask.charAt(i));
                } else if ('"' === c) {
                    i++;
                    var j = mask.indexOf(c, i);
                    0 > j && (j = L);
                    addTextFrag(mask.substring(i, j));
                    i = j;
                } else if (" " === c) addToken({
                    type: 7
                }); else if ("e" !== c && "E" !== c || !tryParseExponent()) {
                    "%" === c ? section.scale += 2 : "‰" === c ? section.scale += 3 : "‱" === c && (section.scale += 4);
                    addTextFrag(c);
                } else ;
            }
            endSection();
        }
        return sections;
    }
    function numForm_compileSection(section) {
        if (!section.empty) {
            numForm_compileSectionPart(section, !0);
            numForm_compileSectionPart(section, !1);
        }
    }
    function numForm_compileSectionPart(section, beforeDecimal) {
        function addStep(step) {
            steps[stepMethName](step);
        }
        for (var token, type, stepMethName = beforeDecimal ? "push" : "unshift", part = section[beforeDecimal ? "integer" : "fractional"], tokens = part.list, steps = part.list = [], digit = part.digits - 1, hasInteger = 0, hasZero = 0, L = tokens.length, i = beforeDecimal ? 0 : L, nextToken = beforeDecimal ? function() {
            return L > i ? tokens[i++] : null;
        } : function() {
            return i > 0 ? tokens[--i] : null;
        }; token = nextToken(); ) switch (type = token.type) {
          case 0:
            addStep(numForm_buildLiteral(token.text));
            break;

          case 1:
          case 2:
            hasZero && 2 === type && (type = 1);
            addStep(numForm_buildReadDigit(beforeDecimal, digit, 1 === type, !hasInteger));
            digit--;
            hasInteger = 1;
            hasZero || 1 !== type || (hasZero = 1);
            break;

          case 3:
            numForm_hasIntegerAhead(tokens, i, L) ? hasInteger && (section.groupOn = 1) : section.scale -= 3;
            break;

          case 4:
            addStep(numFormRt_currencySymbol);
            break;

          case 5:
            addStep(numForm_buildExponent(section, token));
            break;

          case 6:
            section.abbreviationOn = 1;
            addStep(numForm_abbreviationSymbol);
            break;

          case 7:
            addStep(numForm_buildLiteral(" "));
        }
        !beforeDecimal && part.digits && steps.unshift(numForm_buildReadDecimalSymbol(hasZero));
    }
    function numForm_hasIntegerAhead(tokens, i, L) {
        for (;L > i; ) {
            var type = tokens[i++].type;
            if (1 === type || 2 === type) return 1;
        }
        return 0;
    }
    function numForm_buildFormatSectionZero(section) {
        function numFormRt_formatSectionZero(style) {
            return numFormRt_formatSection(section, style, 0, !1);
        }
        return numFormRt_formatSectionZero;
    }
    function numForm_buildFormatSectionNull(section) {
        function numFormRt_formatSectionNull(style) {
            return numFormRt_formatSection(section, style, "", !1);
        }
        return numFormRt_formatSectionNull;
    }
    function numForm_buildFormatSectionPosNeg(section) {
        function numFormRt_formatSectionPosNeg(style, value, zeroFormat, negativeMode) {
            var sdigits, abbreviation, value0 = value, exponent = 0, scale = section.scale;
            if (section.abbreviationOn) for (var L = style.abbreviations.length, i = L; i > 0; i--) {
                var y = 3 * i;
                if (Math.pow(10, y) <= value) {
                    scale -= y;
                    abbreviation = i - 1;
                    break;
                }
            }
            if (section.scientific) {
                sdigits = Math.floor(Math.log(value) / Math.LN10);
                exponent = scale + sdigits - section.integer.digits + 1;
                scale -= exponent;
            }
            scale && (value = def.mult10(value, scale));
            value = def.round10(value, section.fractional.digits);
            return !value && zeroFormat ? zeroFormat(style, value0) : numFormRt_formatSection(section, style, value, negativeMode, exponent, abbreviation);
        }
        return numFormRt_formatSectionPosNeg;
    }
    function numFormRt_formatSection(section, style, value, negativeMode, exponent, abbreviation) {
        var svalue = "" + value, idot = svalue.indexOf("."), itext = 0 > idot ? svalue : svalue.substr(0, idot), ftext = 0 > idot ? "" : svalue.substr(idot + 1);
        "0" === itext && (itext = "");
        exponent || (exponent = 0);
        var out = [];
        negativeMode && out.push(style.negativeSign);
        itext = itext.split("");
        ftext = ftext.split("");
        style.group && section.groupOn && numFormRt_addGroupSeparators(style, itext);
        section.integer.list.forEach(function(f) {
            out.push(f(style, itext, exponent, abbreviation));
        });
        section.fractional.list.forEach(function(f) {
            out.push(f(style, ftext, exponent, abbreviation));
        });
        return out.join("");
    }
    function numFormRt_addGroupSeparators(style, itext) {
        for (var S, gsym = style.group, separate = function() {
            itext[D - d - 1] += gsym;
        }, D = itext.length, gs = style.groupSizes, G = gs.length, d = 0, g = -1; ++g < G; ) {
            d += S = gs[g];
            if (!(D > d)) return;
            separate();
        }
        for (;(d += S) < D; ) separate();
    }
    function numForm_buildLiteral(s) {
        function numFormRt_literal() {
            return s;
        }
        return numFormRt_literal;
    }
    function numForm_buildReadDigit(beforeDecimal, digit, zero, edge) {
        function numFormRt_stylePadding(style) {
            return style[beforeDecimal ? "integerPad" : "fractionPad"];
        }
        function numFormRt_readInteger(style, text) {
            var L = text.length;
            if (L > digit) {
                var i = L - 1 - digit;
                return edge ? text.slice(0, i + 1).join("") : text[i];
            }
            return pad ? pad(style) : "";
        }
        function numFormRt_readFractional(style, text) {
            return digit < text.length ? text[digit] : pad ? pad(style) : "";
        }
        var pad = zero ? numFormRt_stylePadding : null;
        return beforeDecimal ? numFormRt_readInteger : numFormRt_readFractional;
    }
    function numForm_buildReadDecimalSymbol(hasZero) {
        return hasZero ? numFormRt_decimalSymbol : numFormRt_decimalSymbolUnlessInt;
    }
    function numForm_buildExponent(section, token) {
        function numFormRt_exponent(style, text, exponent) {
            var sign = 0 > exponent ? style.negativeSign : token.positive ? "+" : "", exp = "" + Math.abs(exponent), P = token.digits - exp.length;
            P > 0 && (exp = new Array(P + 1).join("0") + exp);
            return token.text + sign + exp;
        }
        return numFormRt_exponent;
    }
    function numFormRt_decimalSymbol(style) {
        return style.decimal;
    }
    function numFormRt_decimalSymbolUnlessInt(style, ftext) {
        return ftext.length ? style.decimal : "";
    }
    function numFormRt_currencySymbol(style) {
        return style.currency;
    }
    function numForm_abbreviationSymbol(style, text, exponent, abbreviation) {
        return null != abbreviation ? style.abbreviations[abbreviation] : void 0;
    }
    function dateForm_tryConfigure(other) {
        return def.string.is(other) ? !!this.mask(other) : def.is(other, dateForm) ? !!this.mask(other.mask()) : void 0;
    }
    function dateForm_createFormatter(mask) {
        return mask ? pv.Format.createFormatter(pv.Format.date(mask)) : def.string.to;
    }
    function customForm_tryConfigure(other) {
        return def.is(other, customForm) ? !!this.formatter(other.formatter()) : def.fun.is(other) ? !!this.formatter(other) : void 0;
    }
    function customForm_defaultFormatter(v) {
        return null != v ? String(v) : "";
    }
    function formProvider_field(mainFactory) {
        function fieldCast(value) {
            return def.is(value, mainFactory) || def.is(value, customForm) ? value : null;
        }
        function fieldFactory(config, proto) {
            var f = def.fun.is(config) ? customForm : mainFactory;
            return f(config, proto);
        }
        return {
            cast: fieldCast,
            factory: fieldFactory
        };
    }
    function formProvider_tryConfigure(other) {
        switch (def.classOf(other)) {
          case formProvider:
            return !!this.number(other.number()).percent(other.percent()).date(other.date()).any(other.any());

          case numForm:
            return !!this.number(other);

          case dateForm:
            return !!this.date(other);

          case customForm:
            return !!this.any(other);
        }
        if (def.string.is(other)) {
            var formP = langProvider(other);
            if (formP) return !!def.configure(this, formP);
        }
    }
    function configLanguage(lang, config) {
        lang = normalizeLanguageCode(lang);
        var langStyle = def.getOwn(_languages, lang);
        if (langStyle) def.configure(langStyle, config); else {
            langStyle = _languages[lang] = formProvider(config);
            langStyle.languageCode = lang;
        }
        return langStyle;
    }
    function parseLanguageCode(langCode) {
        var re = /^([a-z]{2,8})(?:[-_]([a-z]{2}|\d{3}))?$/i, m = re.exec(langCode);
        if (!m) return null;
        var primary = m[1] ? m[1].toLowerCase() : "", region = m[2] ? m[2].toLowerCase() : "";
        return {
            code: primary + (region ? "-" + region : ""),
            primary: primary,
            region: region
        };
    }
    function normalizeLanguageCode(langCode) {
        return langCode ? langCode.toLowerCase() : "";
    }
    function getLanguage(langCode, fallback) {
        langCode = normalizeLanguageCode(langCode);
        var lang = def.getOwn(_languages, langCode);
        if (lang) return lang;
        if (!fallback) return null;
        var norm = parseLanguageCode(langCode);
        if (norm) {
            if (norm.code !== langCode && (lang = def.getOwn(_languages, norm.code))) return lang;
            if (norm.region && (lang = def.getOwn(_languages, norm.primary))) return lang;
        }
        return def.getOwn(_languages, _defaultLangCode, null);
    }
    var cdo = def.globalSpace("cdo", {});
    def.type("cdo.DimensionType").init(function(complexType, name, keyArgs) {
        this.complexType = complexType;
        this.name = name;
        this.label = def.get(keyArgs, "label") || def.titleFromName(name);
        var groupAndLevel = def.splitIndexedId(name);
        this.group = groupAndLevel[0];
        this.groupLevel = def.nullyTo(groupAndLevel[1], 0);
        this.label.indexOf("{") >= 0 && (this.label = def.format(this.label, [ this.groupLevel + 1 ]));
        this.isHidden = !!def.get(keyArgs, "isHidden");
        var valueType = def.get(keyArgs, "valueType") || null, valueTypeName = cdo.DimensionType.valueTypeName(valueType), cast = def.getOwn(cdo.DimensionType.cast, valueTypeName, null);
        this.valueType = valueType;
        this.valueTypeName = valueTypeName;
        this.cast = cast;
        var isNumber = this.valueType === Number, isDate = !isNumber && this.valueType === Date;
        this.isDiscreteValueType = !isNumber && !isDate;
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
        this.setComparer(keyArgs && keyArgs.comparer);
        var format, formatter = def.get(keyArgs, "formatter"), formatProto = def.get(keyArgs, "formatProto"), formatName = isNumber ? "number" : isDate ? "date" : "any";
        if (formatter) format = cdo.format(def.set({}, formatName, formatter), formatProto); else if (this.isDiscreteValueType) format = formProvider(null, formatProto); else {
            format = def.get(keyArgs, "format");
            if (!format && !isNumber) {
                format = def.get(keyArgs, "rawFormat");
                format && (format = format.replace(/-/g, "/"));
            }
            if (format) {
                if (!def.is(format, formProvider)) {
                    (def.string.is(format) || def.fun.is(format) && !def.classOf(format)) && (format = def.set({}, formatName, format));
                    format = formProvider(format, formatProto);
                }
            } else format = formProvider(null, formatProto);
            formatter = format[formatName]();
        }
        this._formatter = formatter || null;
        this._format = format || null;
    }).add({
        isCalculated: !1,
        compare: function(a, b) {
            return null == a ? null == b ? 0 : -1 : null == b ? 1 : this._comparer.call(null, a, b);
        },
        comparer: function(reverse) {
            var me = this;
            return me.isComparable ? reverse ? me._rc || (me._rc = function(a, b) {
                return me.compare(b, a);
            }) : me._dc || (me._dc = function(a, b) {
                return me.compare(a, b);
            }) : null;
        },
        setComparer: function(comparer) {
            if (void 0 === comparer || !comparer && !this.isDiscrete) switch (this.valueType) {
              case Number:
              case Date:
                comparer = def.compare;
            }
            this._comparer = comparer || null;
            this.isComparable = null != this._comparer;
            this._rc = this._dc = this._rac = this._dac = null;
        },
        atomComparer: function(reverse) {
            return reverse ? this._rac || (this._rac = this._createReverseAtomComparer()) : this._dac || (this._dac = this._createDirectAtomComparer());
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
        format: function() {
            return this._format;
        },
        formatter: function() {
            return this._formatter;
        },
        converter: function() {
            return this._converter;
        }
    });
    cdo.DimensionType.cast = {
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
    cdo.DimensionType.dimensionGroupName = function(dimName) {
        return dimName.replace(/^(.*?)(\d*)$/, "$1");
    };
    cdo.DimensionType.valueTypeName = function(valueType) {
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
    def.type("cdo.ComplexType").init(function(dimTypeSpecs) {
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
            var table = def.textTable(2).rowSep().row("Dimension", "Properties").rowSep();
            this._dimsList.forEach(function(type) {
                var features = [];
                features.push('"' + type.label + '"');
                features.push(type.valueTypeName);
                type.isComparable && features.push("comparable");
                type.isDiscrete || features.push("continuous");
                type.isHidden && features.push("hidden");
                table.row(type.name, features.join(", "));
            });
            table.rowSep(!0);
            return "COMPLEX TYPE INFORMATION\n" + table() + "\n";
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
            var dimension = new cdo.DimensionType(this, name, dimTypeSpec);
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
            return dimension;
        },
        addCalculation: function(calcSpec) {
            calcSpec || def.fail.argumentRequired("calcSpec");
            var calculation = calcSpec.calculation || def.fail.argumentRequired("calculations[i].calculation"), dimNames = calcSpec.names;
            dimNames = def.string.is(dimNames) ? dimNames.split(/\s*\,\s*/) : def.array.as(dimNames);
            if (dimNames && dimNames.length) {
                var calcDimNames = this._calculatedDimNames;
                dimNames.forEach(function(name) {
                    if (name) {
                        name = name.replace(/^\s*(.+?)\s*$/, "$1");
                        !def.hasOwn(calcDimNames, name) || def.fail.argumentInvalid("calculations[i].names", "Dimension name '{0}' is already being calculated.", [ name ]);
                        var dimType = this._dims[name] || def.fail.argumentInvalid("calculations[i].names", "Undefined dimension with name '{0}'' ", [ name ]);
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
    def.type("cdo.ComplexTypeProject").init(function(dimGroupSpecs) {
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
            var dimGroupName = cdo.DimensionType.dimensionGroupName(name), dimGroupSpec = this._dimGroupSpecs[dimGroupName];
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
            dimNames = def.string.is(dimNames) ? dimNames.split(/\s*\,\s*/) : def.array.as(dimNames);
            dimNames && dimNames.length && dimNames.forEach(this.calcDim, this);
            this._calcList.push(calcSpec);
        },
        configureComplexType: function(complexType, dimsOptions) {
            this._dimList.forEach(function(dimInfo) {
                var dimName = dimInfo.name, spec = dimInfo.spec;
                spec = this._extendSpec(dimName, spec, dimsOptions);
                complexType.addDimension(dimName, spec);
            }, this);
            this._calcList.forEach(function(calcSpec) {
                complexType.addCalculation(calcSpec);
            });
        },
        _extendSpec: function(dimName, dimSpec, keyArgs) {
            var dimGroup = cdo.DimensionType.dimensionGroupName(dimName);
            dimSpec || (dimSpec = {});
            switch (dimGroup) {
              case "category":
                var isCategoryTimeSeries = def.get(keyArgs, "isCategoryTimeSeries", !1);
                isCategoryTimeSeries && void 0 === dimSpec.valueType && (dimSpec.valueType = Date);
                break;

              case "value":
                void 0 === dimSpec.valueType && (dimSpec.valueType = Number);
            }
            void 0 !== dimSpec.converter || dimSpec.valueType !== Date || dimSpec.rawFormat || (dimSpec.rawFormat = def.get(keyArgs, "timeSeriesFormat"));
            dimSpec.formatProto = def.get(keyArgs, "formatProto");
            return dimSpec;
        }
    });
    def.type("cdo.Atom").init(function(dimension, value, label, rawValue, key) {
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
    def.type("cdo.Complex").init(function(source, atomsByName, dimNames, atomsBase, wantLabel, calculate) {
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
            return new cdo.ComplexView(this, dimNames);
        },
        toString: function() {
            var s = [ "" + def.qualNameOf(this.constructor) ];
            null != this.index && s.push("#" + this.index);
            this.owner.type.dimensionsNames().forEach(function(name) {
                s.push(name + ": " + def.describe(this.atoms[name].value));
            }, this);
            return s.join(" ");
        },
        rightTrimKeySep: function(key) {
            return key && cdo.Complex.rightTrimKeySep(key, this.owner.keySep);
        },
        absKeyTrimmed: function() {
            return this.rightTrimKeySep(this.absKey);
        },
        keyTrimmed: function() {
            return this.rightTrimKeySep(this.key);
        }
    });
    cdo.Complex.rightTrimKeySep = function(key, keySep) {
        if (key && keySep) for (var j, K = keySep.length; key.lastIndexOf(keySep) === (j = key.length - K) && j >= 0; ) key = key.substr(0, j);
        return key;
    };
    cdo.Complex.values = function(complex, dimNames) {
        var atoms = complex.atoms;
        return dimNames.map(function(dimName) {
            return atoms[dimName].value;
        });
    };
    cdo.Complex.compositeKey = function(complex, dimNames) {
        var atoms = complex.atoms;
        return dimNames.map(function(dimName) {
            return atoms[dimName].key;
        }).join(complex.owner.keySep);
    };
    cdo.Complex.labels = function(complex, dimNames) {
        var atoms = complex.atoms;
        return dimNames.map(function(dimName) {
            return atoms[dimName].label;
        });
    };
    var complex_id = def.propGet("id");
    def.type("cdo.ComplexView", cdo.Complex).init(function(source, viewDimNames) {
        this.source = source;
        this.viewDimNames = viewDimNames;
        this.base(source, source.atoms, viewDimNames, source.owner.atoms, !0);
    }).add({
        values: function() {
            return cdo.Complex.values(this, this.viewDimNames);
        },
        labels: function() {
            return cdo.Complex.labels(this, this.viewDimNames);
        }
    });
    def.type("cdo.Datum", cdo.Complex).init(function(data, atomsByName) {
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
    var datum_isSelected = cdo.Datum.isSelected = def.propGet("isSelected");
    cdo.Datum.isSelectedT = datum_isSelectedT;
    cdo.Datum.isSelectedF = datum_isSelectedF;
    cdo.Datum.isVisibleT = datum_isVisibleT;
    cdo.Datum.isVisibleF = datum_isVisibleF;
    cdo.Datum.isNullT = datum_isNullT;
    cdo.Datum.isNullF = datum_isNullF;
    def.type("cdo.TrendDatum", cdo.Datum).init(function(data, atomsByName, trend) {
        this.base(data, atomsByName);
        this.trend = trend;
    }).add({
        isVirtual: !0,
        isTrend: !0
    });
    def.type("cdo.InterpolationDatum", cdo.Datum).init(function(data, atomsByName, interpolation, dimName) {
        this.base(data, atomsByName);
        this.interpolation = interpolation;
        this.interpDimName = dimName;
    }).add({
        isVirtual: !0,
        isInterpolated: !0
    });
    def.type("cdo.Dimension").init(function(data, type) {
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
            if (value instanceof cdo.Atom) return value;
            this._lazyInit && this._lazyInit();
            var typeKey = this.type._key, key = typeKey ? typeKey.call(null, value) : value;
            return this._atomsByKey[key] || null;
        },
        getDistinctAtoms: function(values) {
            var atom, key, atomsByKey, atoms = [], L = values ? values.length : 0;
            if (L) {
                atomsByKey = {};
                for (var i = 0; L > i; i++) if ((atom = this.atom(values[i])) && !atomsByKey[key = "\x00" + atom.key]) {
                    atomsByKey[key] = atom;
                    atoms.push(atom);
                }
            }
            return atoms;
        },
        extent: function(keyArgs) {
            var tmp, atoms = this.atoms(keyArgs), L = atoms.length;
            if (L) {
                var offset = this._nullAtom && null == atoms[0].value ? 1 : 0, countWithoutNull = L - offset;
                if (countWithoutNull > 0) {
                    var min = atoms[offset], max = atoms[L - 1];
                    if (min !== max && def.get(keyArgs, "abs", !1)) {
                        var minSign = min.value < 0 ? -1 : 1, maxSign = max.value < 0 ? -1 : 1;
                        if (minSign === maxSign) 0 > maxSign && (tmp = max, max = min, min = tmp); else if (countWithoutNull > 2) {
                            max.value < -min.value && (max = min);
                            var zeroIndex = def.array.binarySearch(atoms, 0, this.type.comparer(), function(a) {
                                return a.value;
                            });
                            if (0 > zeroIndex) {
                                zeroIndex = ~zeroIndex;
                                var negAtom = atoms[zeroIndex - 1], posAtom = atoms[zeroIndex];
                                min = -negAtom.value < posAtom.value ? negAtom : posAtom;
                            } else min = atoms[zeroIndex];
                        } else max.value < -min.value && (tmp = max, max = min, min = tmp);
                    }
                    return {
                        min: min,
                        max: max
                    };
                }
            }
        },
        min: function(keyArgs) {
            var atoms = this.atoms(keyArgs), L = atoms.length;
            if (L) {
                var offset = this._nullAtom && null == atoms[0].value ? 1 : 0;
                return L > offset ? atoms[offset] : void 0;
            }
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
            var value = atomOrValue instanceof cdo.Atom ? atomOrValue.value : atomOrValue;
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
            return def.string.to(this.type._formatter ? this.type._formatter.call(null, value, sourceValue) : value);
        },
        intern: function(sourceValue, isVirtual) {
            if (null == sourceValue || "" === sourceValue) return this._nullAtom || dim_createNullAtom.call(this);
            if (sourceValue instanceof cdo.Atom) {
                if (sourceValue.dimension !== this) throw def.error.operationInvalid("Atom is of a different dimension.");
                return sourceValue;
            }
            var value, label, type = this.type;
            if ("object" == typeof sourceValue && "v" in sourceValue) {
                label = sourceValue.f;
                sourceValue = sourceValue.v;
                if (null == sourceValue || "" === sourceValue) return this._nullAtom || dim_createNullAtom.call(this, label);
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
            label = def.string.to(label);
            return {
                rawValue: sourceValue,
                key: key,
                value: value,
                label: label
            };
        },
        dispose: function() {
            var v, me = this;
            if (!me._disposed) {
                cdo_disposeChildList(me.childNodes, "parent");
                cdo_disposeChildList(me._linkChildren, "linkParent");
                (v = me.parent) && cdo_removeColChild(v, "childNodes", me, "parent");
                (v = me.linkParent) && cdo_removeColChild(v, "_linkChildren", me, "linkParent");
                dim_clearVisiblesCache.call(me);
                me._lazyInit = me._atoms = me._nullAtom = me._virtualNullAtom = null;
                me._disposed = !0;
            }
        }
    });
    def.type("cdo.Data", cdo.Complex).init(function(keyArgs) {
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
                cdo_addLinkChild.call(linkParent, this, index);
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
            cdo_addChild.call(parent, this, index);
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
            var dim = new cdo.Dimension(this, dimType);
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
                return !(atom instanceof cdo.Atom) || null == atom.value;
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
        contains: function(d) {
            var ds = this._datumsById;
            return !!ds && def.hasOwn(ds, d.id);
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
                cdo_disposeChildLists.call(me);
                var v;
                (v = me._selectedNotNullDatums) && v.clear();
                me._visibleNotNullDatums.clear();
                v = me._dimensionsList;
                for (var i = 0, L = v.length; L > i; i++) v[i].dispose();
                me._dimensions = null;
                me._dimensionsList = null;
                if (v = me.parent) {
                    v.removeChild(me);
                    me.parent = null;
                }
                (v = me.linkParent) && cdo_removeLinkChild.call(v, me);
                me._disposed = !0;
            }
        },
        disposeChildren: function() {
            cdo_disposeChildLists.call(this);
        }
    });
    cdo.Data.add({
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
            if (this.isOwner()) return this._selectedNotNullDatums.clone();
            var datums = this.datums(null, {
                selected: !0
            }).object({
                name: def.propGet("id")
            });
            return new def.Set(datums);
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
            changed |= cdo.Data.setSelected(datums, !0);
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
    cdo.Data.setSelected = function(datums, selected) {
        var anyChanged = 0;
        datums && def.query(datums).each(function(datum) {
            anyChanged |= datum.setSelected(selected);
        });
        return !!anyChanged;
    };
    cdo.Data.toggleSelected = function(datums, any) {
        def.array.isLike(datums) || (datums = def.query(datums).array());
        var q = def.query(datums), on = any ? q.any(datum_isSelected) : q.all(datum_isNullOrSelected);
        return this.setSelected(datums, !on);
    };
    cdo.Data.setVisible = function(datums, visible) {
        var anyChanged = 0;
        datums && def.query(datums).each(function(datum) {
            anyChanged |= datum.setVisible(visible);
        });
        return !!anyChanged;
    };
    cdo.Data.toggleVisible = function(datums) {
        def.array.isLike(datums) || (datums = def.query(datums).array());
        var allVisible = def.query(datums).all(def.propGet("isVisible"));
        return cdo.Data.setVisible(datums, !allVisible);
    };
    cdo.Data.add({
        select: null,
        load: function(atomz, keyArgs) {
            cdo_assertIsOwner.call(this);
            var whereFun = def.get(keyArgs, "where"), isNullFun = def.get(keyArgs, "isNull"), isAdditive = def.get(keyArgs, "isAdditive", !1), datums = def.query(atomz).select(function(atoms) {
                var datum = new cdo.Datum(this, atoms);
                isNullFun && isNullFun(datum) && (datum.isNull = !0);
                return whereFun && !whereFun(datum) ? null : datum;
            }, this);
            data_setDatums.call(this, datums, {
                isAdditive: isAdditive,
                doAtomGC: !0
            });
        },
        clearVirtuals: function() {
            var datums = this._datums;
            if (datums) {
                this._sumAbsCache = null;
                for (var removed, i = 0, L = datums.length; L > i; ) {
                    var datum = datums[i];
                    if (datum.isVirtual) {
                        cdo_removeDatumLocal.call(this, datum);
                        L--;
                        removed = !0;
                    } else i++;
                }
                if (removed) {
                    if (!datums.length && this.parent) return void this.dispose();
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
            cdo_assertIsOwner.call(this);
            data_setDatums.call(this, datums, {
                isAdditive: !0,
                doAtomGC: !0
            });
        },
        groupBy: function(groupingSpecText, keyArgs) {
            var groupByCache, data, groupOper = new cdo.GroupingOper(this, groupingSpecText, keyArgs), cacheKey = groupOper.key;
            if (cacheKey) {
                groupByCache = this._groupByCache;
                data = groupByCache && groupByCache[cacheKey];
            }
            if (data) def.debug >= 7 && def.log("[GroupBy] Cache key hit '" + cacheKey + "'"); else {
                def.debug >= 7 && def.log("[GroupBy] " + (cacheKey ? "Cache key not found: '" + cacheKey + "'" : "No Cache key"));
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
            return new cdo.Data({
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
    }).type().add({
        lca: function(datas) {
            var dataA, dataB, listA, listB, L = datas.length, a = null;
            if (L) {
                if (1 === L) return datas[0];
                var i = 1;
                listA = data_ancestorsAndSelfList(datas[0]);
                do {
                    dataB = datas[i];
                    listB = data_ancestorsAndSelfList(dataB);
                    if (!(a = data_lowestCommonAncestor(listA, listB))) return null;
                    dataA = dataB;
                    listA = listB;
                } while (++i < L);
            }
            return a;
        }
    });
    cdo.whereSpecPredicate = cdo_whereSpecPredicate;
    cdo.Data.add({
        getInfo: function() {
            var out = [ "DATA SUMMARY", def.logSeparator, "  Dimension", def.logSeparator ];
            def.eachOwn(this.dimensions(), function(dimension, name) {
                var count = dimension.count(), type = dimension.type, features = [];
                features.push();
                features.push(type.valueTypeName);
                type.isComparable && features.push("comparable");
                type.isDiscrete || features.push("continuous");
                type.isHidden && features.push("hidden");
                out.push("  " + name + ' ("' + type.label + '", #' + count + ")\n	" + dimension.atoms().slice(0, 10).map(function(atom) {
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
    def.type("cdo.DataOper").init(function(linkParent, keyArgs) {
        linkParent || def.fail.argumentRequired("linkParent");
        this._linkParent = linkParent;
    }).add({
        key: null,
        execute: def.abstractMethod
    });
    def.type("cdo.GroupingOper", cdo.DataOper).init(function(linkParent, groupingSpecs, keyArgs) {
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
            if (groupSpec instanceof cdo.GroupingSpec) {
                if (groupSpec.type !== linkParent.type) throw def.error.argumentInvalid("groupingSpecText", "Invalid associated complex type.");
            } else groupSpec = cdo.GroupingSpec.parse(groupSpec, linkParent.type);
            ids.push(groupSpec.id);
            return groupSpec;
        });
        hasKey && (this.key = ids.join("!!") + "$" + [ this._visible, this._isNull, whereKey ].join("||"));
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
            var isPostOrder = group.flatteningMode === cdo.FlatteningMode.DfsPost, levels = group.levels, L = levels.length, isLastGroup = groupIndex === this._groupSpecs.length - 1, flatChildren = [], flatChildrenByKey = {}, groupParentNode = {
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
            realGroupParentNode.children = flatChildren;
            realGroupParentNode.childrenByKey = flatChildrenByKey;
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
                childNode.key = cdo.Complex.rightTrimKeySep(absKey, keySep);
            } else childNode.absKey = key;
        },
        _generateData: function(node, parentNode, parentData, rootData) {
            var data, isNew;
            if (node.isRoot) if (rootData) {
                data = rootData;
                cdo_addDatumsLocal.call(data, node.datums);
            } else {
                isNew = !0;
                data = new cdo.Data({
                    linkParent: parentData,
                    datums: node.datums
                });
                data.treeHeight = node.treeHeight;
                data._groupOper = this;
            } else {
                if (rootData) {
                    data = parentData.child(node.key);
                    data && cdo_addDatumsSimple.call(data, node.datums);
                }
                if (!data) {
                    isNew = !0;
                    var index, siblings;
                    rootData && (siblings = parentData.childNodes) && (index = ~def.array.binarySearch(siblings, node.datums[0], parentNode.groupLevelSpec.comparer));
                    data = new cdo.Data({
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
    def.space("cdo").FlatteningMode = def.set(def.makeEnum([ "DfsPre", "DfsPost" ]), "None", 0);
    def.type("cdo.GroupingSpec").init(function(levelSpecs, type, ka) {
        this.type = type || null;
        var ids = [], dimNames = [];
        this.hasCompositeLevels = !1;
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
        this.lastDimension = this.depth > 0 ? this.levels[this.depth - 1].lastDimension() : null;
        this.rootLabel = def.get(ka, "rootLabel") || "";
        this.flatteningMode = def.get(ka, "flatteningMode") || cdo.FlatteningMode.None;
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
            return !this.isSingleDimension || !!(d = this.lastDimension) && d.type.isDiscrete;
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
        lastDimensionType: function() {
            var d = this.lastDimension;
            return d && d.type;
        },
        lastDimensionName: function() {
            var dt = this.lastDimensionType();
            return dt && dt.name;
        },
        lastDimensionValueType: function() {
            var dt = this.lastDimensionType();
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
            return flatteningMode !== me.flatteningMode || rootLabel !== me.rootLabel ? new cdo.GroupingSpec(me.levels, me.type, {
                flatteningMode: flatteningMode,
                rootLabel: rootLabel
            }) : me;
        },
        _singleLevelGrouping: function(ka) {
            var reverse = !!def.get(ka, "reverse"), dimSpecs = this.dimensions().select(function(dimSpec) {
                return reverse ? new cdo.GroupingDimensionSpec(dimSpec.name, !dimSpec.reverse, dimSpec.type.complexType) : dimSpec;
            }), levelSpec = new cdo.GroupingLevelSpec(dimSpecs, this.type);
            return new cdo.GroupingSpec([ levelSpec ], this.type, {
                flatteningMode: null,
                rootLabel: def.get(ka, "rootLabel") || this.rootLabel
            });
        },
        _reverse: function(ka) {
            var levelSpecs = def.query(this.levels).select(function(levelSpec) {
                var dimSpecs = def.query(levelSpec.dimensions).select(function(dimSpec) {
                    return new cdo.GroupingDimensionSpec(dimSpec.name, !dimSpec.reverse, dimSpec.type.complexType);
                });
                return new cdo.GroupingLevelSpec(dimSpecs, this.type);
            }, this);
            return new cdo.GroupingSpec(levelSpecs, this.type, {
                flatteningMode: def.get(ka, "flatteningMode") || this.flatteningMode,
                rootLabel: def.get(ka, "rootLabel") || this.rootLabel
            });
        },
        toString: function() {
            return this.levels.map(String).join(", ");
        }
    });
    def.type("cdo.GroupingLevelSpec").init(function(dimSpecs, type) {
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
        lastDimension: function() {
            return this.dimensions[this.depth - 1];
        },
        bind: function(type) {
            this._sortDimensions(type);
            this.dimensions.forEach(function(dimSpec) {
                dimSpec.bind(type);
            });
        },
        compare: function(a, b) {
            for (var result, dims = this.dimensions, D = this.depth, i = 0; D > i; i++) if (result = dims[i].compareDatums(a, b)) return result;
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
            for (var atoms = {}, dimNames = this._dimNames, D = this.depth, datoms = datum.atoms, i = 0; D > i; i++) {
                var dimName = dimNames[i];
                atoms[dimName] = datoms[dimName];
            }
            return {
                atoms: atoms,
                dimNames: dimNames
            };
        },
        toString: function() {
            return def.query(this.dimensions).select(String).array().join("|");
        }
    });
    def.type("cdo.GroupingDimensionSpec").init(function(name, reverse, type) {
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
            return this.name + (this.type ? ' ("' + this.type.label + '")' : "") + (this.reverse ? " desc" : "");
        }
    });
    cdo.GroupingSpec.parse = function(specText, type) {
        if (!specText) return new cdo.GroupingSpec(null, type);
        var levels = def.string.is(specText) ? specText.split(/\s*,\s*/) : def.array.as(specText), levelSpecs = def.query(levels).select(function(levelText) {
            var dimSpecs = groupSpec_parseGroupingLevel(levelText, type);
            return new cdo.GroupingLevelSpec(dimSpecs, type);
        });
        return new cdo.GroupingSpec(levelSpecs, type);
    };
    var groupSpec_matchDimSpec = /^\s*(.+?)(?:\s+(asc|desc))?\s*$/i;
    def.type("cdo.LinearInterpolationOper").init(function(baseData, partData, visibleData, catRole, serRole, valRole, stretchEnds) {
        this._newDatums = [];
        this._data = visibleData;
        var qAllCatDatas = catRole.flatten(baseData).children(), serDatas1 = serRole.isBound() ? serRole.flatten(partData, {
            visible: !0,
            isNull: !1
        }).children().array() : [ null ], valDim = this._valDim = baseData.owner.dimensions(valRole.lastDimensionName()), visibleKeyArgs = {
            visible: !0,
            zeroIfNone: !1
        };
        this._isCatDiscrete = catRole.grouping.isDiscrete();
        this._stretchEnds = stretchEnds;
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
            return new cdo.LinearInterpolationOperSeriesState(this, serIndex);
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
    def.type("cdo.LinearInterpolationOperSeriesState").init(function(interpolation, serIndex) {
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
            var next = this.__nextNonNull, prev = this.__lastNonNull, one = next || prev;
            if (one) {
                var value, group, interpolation = this.interpolation, catInfo = catSerInfo.catInfo;
                if (next && prev) if (interpolation._isCatDiscrete) {
                    var groupIndex = catInfo.index - prev.catInfo.index;
                    value = prev.value + this._stepValue * groupIndex;
                    group = (this._isOdd ? groupIndex < this._middleIndex : groupIndex <= this._middleIndex) ? prev.group : next.group;
                } else {
                    var cat = +catInfo.value, lastCat = +prev.catInfo.value;
                    value = prev.value + this._steep * (cat - lastCat);
                    group = cat < this._middleCat ? prev.group : next.group;
                } else {
                    if (!interpolation._stretchEnds) return;
                    value = one.value;
                    group = one.group;
                }
                var atoms = Object.create(group.atoms);
                def.copyOwn(atoms, catInfo.data.atoms);
                var valDim = interpolation._valDim, valueAtom = valDim.intern(value, !0);
                atoms[valDim.name] = valueAtom;
                interpolation._newDatums.push(new cdo.InterpolationDatum(group.owner, atoms, "linear", valDim.name));
            }
        }
    });
    def.type("cdo.ZeroInterpolationOper").init(function(baseData, partData, visibleData, catRole, serRole, valRole, stretchEnds) {
        this._newDatums = [];
        this._data = visibleData;
        var qAllCatDatas = catRole.flatten(baseData).children(), serDatas1 = serRole.isBound() ? serRole.flatten(partData, {
            visible: !0,
            isNull: !1
        }).children().array() : [ null ], valDim = this._valDim = baseData.owner.dimensions(valRole.lastDimensionName()), visibleKeyArgs = {
            visible: !0,
            zeroIfNone: !1
        };
        this._isCatDiscrete = catRole.grouping.isDiscrete();
        this._stretchEnds = stretchEnds;
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
            return new cdo.ZeroInterpolationOperSeriesState(this, serIndex);
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
    def.type("cdo.ZeroInterpolationOperSeriesState").init(function(interpolation, serIndex) {
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
                if (next && last) if (this.interpolation._isCatDiscrete) {
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
                var atoms = Object.create(group.atoms);
                def.copyOwn(atoms, catInfo.data.atoms);
                var valDim = interpolation._valDim, zeroAtom = interpolation._zeroAtom || (interpolation._zeroAtom = valDim.intern(0, !0));
                atoms[valDim.name] = zeroAtom;
                interpolation._newDatums.push(new cdo.InterpolationDatum(group.owner, atoms, "zero", valDim.name));
            }
        }
    });
    def.type("cdo.TranslationOper").init(function(complexTypeProj, source, metadata, options) {
        this.complexTypeProj = complexTypeProj || def.fail.argumentRequired("complexTypeProj");
        this.source = source || def.fail.argumentRequired("source");
        this.metadata = metadata || def.fail.argumentRequired("metadata");
        this.options = options || {};
        this._initType();
        if (def.debug >= 4) {
            this._logLogicalRows = !0;
            this._logLogicalRowCount = 0;
        }
    }).add({
        _logLogicalRows: !1,
        logSource: def.abstractMethod,
        logLogicalRow: def.abstractMethod,
        _translType: "Unknown",
        logTranslatorType: function() {
            return this._translType + " data source translator";
        },
        logicalColumnCount: function() {
            return this.metadata.length;
        },
        setSource: function(source) {
            if (!source) throw def.error.argumentRequired("source");
            this.source = source;
        },
        defReader: function(dimReaderSpec) {
            dimReaderSpec || def.fail.argumentRequired("readerSpec");
            var dimNames = def.string.is(dimReaderSpec) ? dimReaderSpec : dimReaderSpec.names;
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
        _configureTypeCore: def.abstractMethod,
        _initType: function() {
            this._userDimsReaders = [];
            this._userDimsReadersByDim = {};
            this._userUsedIndexes = {};
            this._userIndexesToSingleDim = [];
            var userDimReaders = this.options.readers;
            userDimReaders && def.array.each(userDimReaders, this.defReader, this);
            var multiChartIndexes = def.parseDistinctIndexArray(this.options.multiChartIndexes);
            multiChartIndexes && (this._multiChartIndexes = this.defReader({
                names: "multiChart",
                indexes: multiChartIndexes
            }));
        },
        _userUseIndex: function(index) {
            index = +index;
            if (0 > index) throw def.error.argumentInvalid("index", "Invalid reader index: '{0}'.", [ index ]);
            if (def.hasOwn(this._userUsedIndexes, index)) throw def.error.argumentInvalid("index", "Column '{0}' of the logical table is already assigned.", [ index ]);
            this._userUsedIndexes[index] = !0;
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
                    nextIndex = this._getNextFreeLogicalColumnIndex(nextIndex);
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
            if (N > L) for (var splitGroupName = def.splitIndexedId(dimNames[N - 1]), groupName = splitGroupName[0], level = def.nullyTo(splitGroupName[1], 0), i = L; I > i; i++, 
            level++) {
                dimName = def.indexedId(groupName, level);
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
                info = this._logicalRowInfos[index];
                if (info && !this.options.ignoreMetadataLabels) {
                    var label = info.label || info.name && def.titleFromName(info.name);
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
            return def.query(this._getLogicalRows()).select(function(row) {
                return this._readLogicalRow(row, dimsReaders);
            }, this);
        },
        _getLogicalRows: function() {
            return this.source;
        },
        _getDimensionsReaders: function() {
            return this._userDimsReaders.slice().reverse();
        },
        _readLogicalRow: function(logicalRow, dimsReaders) {
            for (var doLog = this._logLogicalRows && this._logLogicalRowBefore(logicalRow), r = dimsReaders.length, data = this.data, atoms = {}; r--; ) dimsReaders[r].call(data, logicalRow, atoms);
            doLog && this._logLogicalRowAfter(atoms);
            return atoms;
        },
        _logLogicalRowBefore: function(logicalRow) {
            if (this._logLogicalRowCount < 10) return def.log("logical row [" + this._logLogicalRowCount++ + "]: " + def.describe(logicalRow)), 
            !0;
            def.log("...");
            return this._logLogicalRows = !1;
        },
        _logLogicalRowAfter: function(readAtoms) {
            var logAtoms = {};
            for (var dimName in readAtoms) {
                var atom = readAtoms[dimName];
                def.object.is(atom) && (atom = "v" in atom ? atom.v : "value" in atom ? atom.value : "...");
                logAtoms[dimName] = atom;
            }
            def.log("-> read: " + def.describe(logAtoms));
        },
        _propGet: function(dimName, prop) {
            function propGet(logicalRow, atoms) {
                atoms[dimName] = logicalRow[prop];
            }
            return propGet;
        },
        _getNextFreeLogicalColumnIndex: function(index, L) {
            null == index && (index = 0);
            null == L && (L = 1 / 0);
            for (;L > index && def.hasOwn(this._userUsedIndexes, index); ) index++;
            return L > index ? index : -1;
        },
        _getPhysicalGroupStartIndex: function(name) {
            return def.getOwn(this._logicalRowPhysicalGroupIndex, name);
        },
        _getPhysicalGroupLength: function(name) {
            return def.getOwn(this._logicalRowPhysicalGroupsLength, name);
        },
        _configureTypeByPhysicalGroup: function(physicalGroupName, dimGroupName, dimCount, levelMax) {
            var gStartIndex = this._logicalRowPhysicalGroupIndex[physicalGroupName], gLength = this._logicalRowPhysicalGroupsLength[physicalGroupName], gEndIndex = gStartIndex + gLength - 1, index = gStartIndex;
            dimCount = null == dimCount ? gLength : Math.min(gLength, dimCount);
            if (dimCount && gEndIndex >= index) {
                dimGroupName || (dimGroupName = physicalGroupName);
                levelMax || (levelMax = 1 / 0);
                for (var dimName, level = 0; dimCount && levelMax > level; ) {
                    dimName = def.indexedId(dimGroupName, level++);
                    if (!this.complexTypeProj.isReadOrCalc(dimName)) {
                        index = this._getNextFreeLogicalColumnIndex(index);
                        if (index > gEndIndex) return index;
                        this.defReader({
                            names: dimName,
                            indexes: index
                        });
                        index++;
                        dimCount--;
                    }
                }
            }
            return index;
        },
        _configureTypeByOrgLevel: function(discreteDimGroups, continuousDimGroups) {
            var freeContinuous = [], freeDiscrete = [];
            this._logicalRowInfos.forEach(function(info, index) {
                if (!this[index]) {
                    var indexes = 1 === info.type ? freeContinuous : freeDiscrete;
                    indexes && indexes.push(index);
                }
            }, this._userUsedIndexes);
            this._configureTypeByDimGroups(freeDiscrete, this._processDimGroupSpecs(discreteDimGroups, !0, 1 / 0));
            this._configureTypeByDimGroups(freeContinuous, this._processDimGroupSpecs(continuousDimGroups, !1, 1));
        },
        _processDimGroupSpecs: function(dimGroupSpecs, defaultGreedy, defaultMaxCount) {
            return dimGroupSpecs.map(function(dimGroupSpec) {
                return def.string.is(dimGroupSpec) ? {
                    name: dimGroupSpec,
                    greedy: defaultGreedy,
                    maxCount: defaultMaxCount
                } : def.setDefaults(dimGroupSpec, {
                    greedy: defaultGreedy,
                    maxCount: defaultMaxCount
                });
            });
        },
        _configureTypeByDimGroups: function(freeIndexes, dimGroups) {
            if (dimGroups) for (var F, g = -1, G = dimGroups.length; ++g < G && (F = freeIndexes.length); ) {
                var dimGroupSpec = dimGroups[g], maxCount = Math.min(dimGroupSpec.maxCount, F), defaultDims = this._getFreeDimGroupNames(dimGroupSpec.name, maxCount, dimGroupSpec.greedy);
                if (defaultDims) {
                    defaultDims.length;
                    this.defReader({
                        names: defaultDims,
                        indexes: freeIndexes.splice(0, defaultDims.length)
                    });
                }
            }
        },
        _getFreeDimGroupNames: function(dimGroupName, dimCount, greedy) {
            if (!dimGroupName) return null;
            var dims = [], level = 0;
            null == dimCount && (dimCount = 1);
            for (;dimCount; ) {
                var dimName = def.indexedId(dimGroupName, level++);
                if (this.complexTypeProj.isReadOrCalc(dimName)) greedy || dimCount--; else {
                    dims.push(dimName);
                    dimCount--;
                }
            }
            return dims.length ? dims : null;
        }
    });
    def.type("cdo.MatrixTranslationOper", cdo.TranslationOper).add({
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
            var columnTypes, typeCheckingMode = this.options.typeCheckingMode, knownContinColTypes = this._knownContinuousColTypes;
            if ("none" === typeCheckingMode) columnTypes = def.query(this.metadata).select(function(colDef, colIndex) {
                colDef.colIndex = colIndex;
                var colType = colDef.colType;
                return colType && 1 === knownContinColTypes[colType.toLowerCase()] ? 1 : 0;
            }).array(); else {
                var checkNumericString = "extended" === typeCheckingMode, columns = def.query(this.metadata).select(function(colDef, colIndex) {
                    colDef.colIndex = colIndex;
                    return colDef;
                }).where(function(colDef) {
                    var colType = colDef.colType;
                    return !colType || 1 !== knownContinColTypes[colType.toLowerCase()];
                }).select(function(colDef) {
                    return colDef.colIndex;
                }).array(), I = this.I, source = this.source, J = columns.length;
                columnTypes = def.array.create(this.J, 1);
                for (var i = 0; I > i && J > 0; i++) for (var row = source[i], m = 0; J > m; ) {
                    var j = columns[m], value = row[j];
                    if (null != value) {
                        columnTypes[j] = this._getSourceValueType(value, checkNumericString);
                        columns.splice(m, 1);
                        J--;
                    } else m++;
                }
            }
            this._columnTypes = columnTypes;
        },
        _buildLogicalColumnInfoFromMetadata: function(index) {
            var meta = this.metadata[index];
            return {
                type: this._columnTypes[index],
                name: meta.colName,
                label: meta.colLabel
            };
        },
        _getSourceValueType: function(value, checkNumericString) {
            switch (typeof value) {
              case "number":
                return 1;

              case "string":
                return checkNumericString && "" !== value && !isNaN(+value) ? 1 : 0;

              case "object":
                return value instanceof Date ? 1 : 0;
            }
            return 0;
        },
        logSource: function() {
            var R = cdo.previewRowsMax, C = cdo.previewColsMax, md = this.metadata, L = md.length, prepend = def.array.prepend;
            if (L > C) {
                md = md.slice(0, C);
                md.push({
                    colName: "(" + C + "/" + L + ")",
                    colType: "..."
                });
            }
            var table = def.textTable(md.length + 1).rowSep().row.apply(table, prepend(md.map(function(col) {
                return col.colName;
            }), [ "Name" ])).rowSep().row.apply(table, prepend(md.map(function(col) {
                return col.colLabel ? '"' + col.colLabel + '"' : "";
            }), [ "Label" ])).rowSep().row.apply(table, prepend(md.map(function(col) {
                return col.colType;
            }), [ "Type" ])).rowSep();
            def.query(this.source).take(R).each(function(row, index) {
                L > C && (row = row.slice(0, C));
                table.row.apply(table, prepend(row.map(function(v) {
                    return def.describe(v);
                }), [ index + 1 ]));
            });
            table.rowSep().row("(" + Math.min(R, this.I) + "/" + this.I + ")").rowSep(!0);
            return "DATA SOURCE SUMMARY\n" + table() + "\n";
        },
        _logLogicalRow: function(kindList, kindScope) {
            var table = def.textTable(6).rowSep().row("Index", "Kind", "Type", "Name", "Label", "Dimension").rowSep(), index = 0;
            kindList.forEach(function(kind) {
                for (var i = 0, L = kindScope[kind]; L > i; i++) {
                    var info = this._logicalRowInfos[index];
                    table.row(index, kind, info.type ? "number" : "string", info.name || "", info.label || "", this._userIndexesToSingleDim[index] || "");
                    index++;
                }
            }, this);
            table.rowSep(!0);
            return "LOGICAL TABLE\n" + table() + "\n";
        },
        _createPlot2SeriesKeySet: function(plot2DataSeriesIndexes, seriesKeys) {
            var plot2SeriesKeySet = null, seriesCount = seriesKeys.length;
            def.query(plot2DataSeriesIndexes).each(function(indexText) {
                var seriesIndex = +indexText;
                if (isNaN(seriesIndex)) throw def.error.argumentInvalid("plot2SeriesIndexes", "Element is not a number '{0}'.", [ indexText ]);
                if (0 > seriesIndex) {
                    if (-seriesCount >= seriesIndex) throw def.error.argumentInvalid("plot2SeriesIndexes", "Index is out of range '{0}'.", [ seriesIndex ]);
                    seriesIndex = seriesCount + seriesIndex;
                } else if (seriesIndex >= seriesCount) throw def.error.argumentInvalid("plot2SeriesIndexes", "Index is out of range '{0}'.", [ seriesIndex ]);
                plot2SeriesKeySet || (plot2SeriesKeySet = {});
                plot2SeriesKeySet[seriesKeys[seriesIndex]] = !0;
            });
            return plot2SeriesKeySet;
        },
        _dataPartGet: function(calcAxis2SeriesKeySet) {
            function calcDataPart(series, outAtoms) {
                outAtoms[dataPartDimName] = def.hasOwn(plot2SeriesKeySet, series) ? part2Atom || (part2Atom = dataPartDimension.intern("1")) : part1Atom || (part1Atom = dataPartDimension.intern("0"));
            }
            var dataPartDimension, plot2SeriesKeySet, part1Atom, part2Atom, me = this, dataPartDimName = this.options.dataPartDimName, init = function() {
                plot2SeriesKeySet = calcAxis2SeriesKeySet();
                dataPartDimension = me.data.dimensions(dataPartDimName);
                def.debug >= 3 && plot2SeriesKeySet && def.log("Second axis series values: " + def.describe(def.keys(plot2SeriesKeySet)));
                init = null;
            };
            this.complexTypeProj.setCalc({
                names: dataPartDimName,
                calculation: function(datum, outAtoms) {
                    init && init();
                    calcDataPart(datum.atoms.series.value, outAtoms);
                }
            });
        },
        _configureTypeCore: function() {
            [ "series", "category", "value" ].forEach(function(physicalGroupName) {
                this._configureTypeByPhysicalGroup(physicalGroupName);
            }, this);
        }
    });
    cdo.previewRowsMax = 15;
    cdo.previewColsMax = 6;
    def.type("cdo.CrosstabTranslationOper", cdo.MatrixTranslationOper).add({
        _translType: "Crosstab",
        logicalColumnCount: function() {
            return this.R + this.C + this.M;
        },
        _executeCore: function() {
            function updateLogicalRowCrossGroup(crossGroupId, source) {
                for (var logColIndex = logicalRowCrossGroupIndex[crossGroupId], sourceIndex = 0, depth = me[crossGroupId]; depth-- > 0; ) logRow[logColIndex++] = source[sourceIndex++];
            }
            function updateLogicalRowMeasure(line, cg) {
                for (var logColIndex = logicalRowCrossGroupIndex.M, cgIndexes = me._colGroupsIndexes[cg], depth = me.M, i = 0; depth > i; i++) {
                    var lineIndex = cgIndexes[i];
                    logRow[logColIndex++] = null != lineIndex ? line[lineIndex] : null;
                }
            }
            if (!this.metadata.length) return def.query();
            var dimsReaders = this._getDimensionsReaders(), logRow = new Array(this.logicalColumnCount()), logicalRowCrossGroupIndex = this._logicalRowCrossGroupIndex, me = this, q = def.query(this.source);
            if (this._colGroups && this._colGroups.length) {
                var expandLine = function(line) {
                    updateLogicalRowCrossGroup("R", line);
                    return def.query(this._colGroups).select(function(colGroup, cg) {
                        updateLogicalRowCrossGroup("C", colGroup);
                        updateLogicalRowMeasure(line, cg);
                        return this._readLogicalRow(logRow, dimsReaders);
                    }, this);
                };
                return q.selectMany(expandLine, this);
            }
            return q.select(function(line) {
                updateLogicalRowCrossGroup("R", line);
                return this._readLogicalRow(logRow, dimsReaders);
            }, this);
        },
        _processMetadata: function() {
            this.base();
            this._separator = this.options.separator || "~";
            var R = this.R = 1;
            this.C = 1;
            this.M = 1;
            this.measuresDirection = null;
            var seriesInRows = this.options.seriesInRows, metadata = this.metadata, isV1Compat = this.options.compatVersion <= 1, colNames = function() {
                var f = seriesInRows ? function(d) {
                    return d.colName;
                } : isV1Compat ? function(d) {
                    return {
                        v: d.colName
                    };
                } : function(d) {
                    return {
                        v: d.colName,
                        f: d.colLabel
                    };
                };
                return metadata.map(f);
            }(), logicalRowCrossGroupInfos = this._logicalRowCrossGroupInfos = {};
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
                            logicalRowCrossGroupInfos.M = [ this._buildLogicalColumnInfoFromMetadata(R) ];
                        }
                        this.C = this._colGroups[0].length;
                        logicalRowCrossGroupInfos.C = def.range(0, this.C).select(function() {
                            return {
                                type: 0
                            };
                        }).array();
                    } else {
                        this.C = this.M = 0;
                        logicalRowCrossGroupInfos.M = [];
                        logicalRowCrossGroupInfos.C = [];
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
                logicalRowCrossGroupInfos.C = [ {
                    type: 0
                } ];
                logicalRowCrossGroupInfos.M = [ {
                    type: this._columnTypes[R]
                } ];
            }
            logicalRowCrossGroupInfos.R = def.range(0, this.R).select(this._buildLogicalColumnInfoFromMetadata, this).array();
            var logicalRowCrossGroupIndex = this._logicalRowCrossGroupIndex = {
                C: seriesInRows ? this.R : 0,
                R: seriesInRows ? 0 : this.C,
                M: this.C + this.R
            }, logicalRowInfos = this._logicalRowInfos = new Array(this.logicalColumnCount());
            def.eachOwn(logicalRowCrossGroupIndex, function(groupStartIndex, crossGroup) {
                logicalRowCrossGroupInfos[crossGroup].forEach(function(info, groupIndex) {
                    logicalRowInfos[groupStartIndex + groupIndex] = info;
                });
            });
            this._logicalRowPhysicalGroupsLength = {
                series: seriesInRows ? this.R : this.C,
                category: seriesInRows ? this.C : this.R,
                value: this.M
            };
            this._logicalRowPhysicalGroupIndex = {
                series: 0,
                category: this._logicalRowPhysicalGroupsLength.series,
                value: this.C + this.R
            };
        },
        logLogicalRow: function() {
            return this._logLogicalRow([ "C", "R", "M" ], {
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
            this._logicalRowCrossGroupInfos.M = measuresInfoList;
            this.M = M;
        },
        configureType: function() {
            if ("rows" === this.measuresDirection) throw def.error.notImplemented();
            var dataPartDimName = this.options.dataPartDimName;
            if (dataPartDimName && 1 === this.C && !this.complexTypeProj.isReadOrCalc(dataPartDimName)) {
                var plot2DataSeriesIndexes = this.options.plot2DataSeriesIndexes;
                if (null != plot2DataSeriesIndexes) {
                    var seriesKeys = this._colGroups.map(function(colGroup) {
                        return "" + colGroup[0].v;
                    });
                    this._plot2SeriesKeySet = this._createPlot2SeriesKeySet(plot2DataSeriesIndexes, seriesKeys);
                }
            }
            this.base();
            if (this._plot2SeriesKeySet) {
                var seriesReader = this._userDimsReadersByDim.series;
                if (seriesReader) {
                    var calcAxis2SeriesKeySet = def.fun.constant(this._plot2SeriesKeySet);
                    this._dataPartGet(calcAxis2SeriesKeySet);
                }
            }
        }
    });
    def.type("cdo.RelationalTranslationOper", cdo.MatrixTranslationOper).add({
        M: 0,
        C: 0,
        S: 0,
        _translType: "Relational",
        _processMetadata: function() {
            this.base();
            var S, valuesColIndexes, M, D, metadata = this.metadata, J = this.J, C = this.options.categoriesCount;
            null != C && (!isFinite(C) || 0 > C) && (C = 0);
            if (this.options.isMultiValued) {
                valuesColIndexes = def.parseDistinctIndexArray(this.options.measuresIndexes, 0, J - 1);
                M = valuesColIndexes ? valuesColIndexes.length : 0;
            }
            if (null == M) if (J > 0 && 3 >= J && (null == C || 1 === C)) {
                M = 1;
                valuesColIndexes = [ J - 1 ];
                C = J >= 2 ? 1 : 0;
                S = J >= 3 ? 1 : 0;
                D = C + S;
            } else if (null != C && C >= J) {
                D = C = J;
                S = M = 0;
            } else {
                var Mmax = null != C ? J - C : 1 / 0;
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
            valuesColIndexes && valuesColIndexes.slice().sort(def.descending).forEach(function(inputIndex) {
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
            var logicalRowPerm = [];
            [ "S", "C", "M" ].forEach(function(name) {
                var groupSpec = specsByName[name];
                groupSpec && def.array.append(logicalRowPerm, groupSpec.indexes);
            });
            this._logicalRowInfos = logicalRowPerm.map(this._buildLogicalColumnInfoFromMetadata, this);
            this._logicalRowPerm = logicalRowPerm;
            this._logicalRowPhysicalGroupsLength = {
                series: this.S,
                category: this.C,
                value: this.M
            };
            this._logicalRowPhysicalGroupIndex = {
                series: 0,
                category: this._logicalRowPhysicalGroupsLength.series,
                value: this.S + this.C
            };
        },
        logLogicalRow: function() {
            return this._logLogicalRow([ "S", "C", "M" ], {
                S: this.S,
                C: this.C,
                M: this.M
            });
        },
        configureType: function() {
            this.base();
            var dataPartDimName = this.options.dataPartDimName;
            if (dataPartDimName && !this.complexTypeProj.isReadOrCalc(dataPartDimName)) {
                var plot2DataSeriesIndexes = this.options.plot2DataSeriesIndexes;
                if (null != plot2DataSeriesIndexes) {
                    var seriesReader = this._userDimsReadersByDim.series;
                    seriesReader && relTransl_dataPartGet.call(this, plot2DataSeriesIndexes, seriesReader);
                }
            }
        },
        _executeCore: function() {
            var dimsReaders = this._getDimensionsReaders(), permIndexes = this._logicalRowPerm;
            return def.query(this._getLogicalRows()).select(function(row) {
                row = pv.permute(row, permIndexes);
                return this._readLogicalRow(row, dimsReaders);
            }, this);
        }
    });
    var numFormStyle = cdo.numberFormatStyle = function(other, proto) {
        return new NumFormStyle(other, proto);
    }, numForm_privProp = def.priv.key().property(), NumFormStyle = numFormStyle.of = def("cdo.NumberFormatStyle", def.FieldsBase.extend({
        init: function() {
            def.classify(this, numFormStyle);
        },
        fields: {
            decimal: {
                cast: String,
                fail: def.falsy
            },
            group: {
                cast: String
            },
            groupSizes: {
                fail: def.array.empty
            },
            negativeSign: {
                cast: String,
                fail: def.falsy
            },
            currency: {
                cast: String,
                fail: def.falsy
            },
            integerPad: {
                cast: String,
                fail: def.falsy
            },
            fractionPad: {
                cast: String,
                fail: def.falsy
            },
            abbreviations: {
                fail: def.array.empty
            }
        },
        methods: {
            tryConfigure: function(other) {
                if (def.is(other, numFormStyle)) return !!this.integerPad(other.integerPad()).fractionPad(other.fractionPad()).decimal(other.decimal()).group(other.group()).groupSizes(other.groupSizes()).negativeSign(other.negativeSign()).currency(other.currency()).abbreviations(other.abbreviations());
                if (def.string.is(other)) {
                    var formP = langProvider(other);
                    if (formP) return !!def.configure(this, formP.number().style());
                }
            }
        }
    }, {
        fieldsPrivProp: numForm_privProp
    }));
    def.classify(NumFormStyle.prototype, numFormStyle);
    numFormStyle.defaults = numFormStyle({
        integerPad: "0",
        fractionPad: "0",
        decimal: ".",
        group: ",",
        groupSizes: [ 3 ],
        abbreviations: [ "k", "m", "b", "t" ],
        negativeSign: "-",
        currency: "$"
    });
    var numForm = cdo.numberFormat = function(config, proto) {
        function numFormat(value) {
            formatter || (formatter = numForm_cachedFormatter(fields.mask));
            return formatter(value, numForm_privProp(fields.style));
        }
        var fields, formatter;
        numFormat.format = numFormat;
        numFormat.tryConfigure = numForm_tryConfigure;
        def.classify(numFormat, numForm);
        fields = def.instance(numFormat, config, proto, {
            mask: {
                cast: String,
                change: function() {
                    formatter = null;
                }
            },
            style: {
                cast: def.createAs(NumFormStyle),
                factory: numFormStyle
            }
        });
        return numFormat;
    };
    numForm.defaults = numForm().style(numFormStyle());
    numForm.cacheLimit = 20;
    var numForm_cache = {}, numForm_cacheCount = 0, dateForm = cdo.dateFormat = function(config, proto) {
        function dateFormat(value) {
            formatter || (formatter = dateForm_createFormatter(fields.mask));
            return formatter(value);
        }
        var fields, formatter;
        dateFormat.format = dateFormat;
        dateFormat.tryConfigure = dateForm_tryConfigure;
        def.classify(dateFormat, dateForm);
        fields = def.instance(dateFormat, config, proto, {
            mask: {
                cast: String,
                change: function() {
                    formatter = null;
                }
            }
        });
        arguments.length && def.configure(dateFormat, arguments[0]);
        return dateFormat;
    };
    dateForm.defaults = dateForm();
    var customForm = cdo.customFormat = function(config, proto) {
        function customFormat(v) {
            var formatter = fields.formatter;
            return String(formatter && formatter.apply(null, arguments));
        }
        var fields;
        customFormat.format = customFormat;
        customFormat.tryConfigure = customForm_tryConfigure;
        def.classify(customFormat, customForm);
        fields = def.instance(customFormat, config, proto, {
            formatter: {
                cast: def.fun.as
            }
        });
        return customFormat;
    };
    customForm.defaults = customForm().formatter(customForm_defaultFormatter);
    var _defaultLangCode = "en-us", formProvider = cdo.format = function(config, proto) {
        function formatProvider() {}
        formatProvider.tryConfigure = formProvider_tryConfigure;
        var language;
        if (!proto && def.string.is(config)) {
            var formP = langProvider(config);
            language = formP.languageCode;
            if (formP) {
                proto = formP;
                config = null;
            }
        }
        formatProvider.languageCode = language ? language : _defaultLangCode;
        def.classify(formatProvider, formProvider);
        def.instance(formatProvider, config, proto, {
            number: formProvider_field(numForm),
            percent: formProvider_field(numForm),
            date: formProvider_field(dateForm),
            any: {
                cast: def.createAs(customForm),
                factory: customForm
            }
        });
        return formatProvider;
    };
    formProvider.defaults = formProvider({
        number: "#,0.##",
        percent: "#,0.#%",
        date: "%Y/%m/%d",
        any: customForm()
    });
    var _languages = {}, _currentProvider = _languages[_defaultLangCode] = formProvider.defaults, langProvider = cdo.format.language = function(style, config) {
        var L = arguments.length;
        if (!L) return _currentProvider;
        if (1 == L) {
            if (void 0 === style) throw def.error.operationInvalid("Undefined 'style' value.");
            if (null === style || "" === style) style = _defaultLangCode; else {
                if (def.is(style, formProvider)) return _currentProvider = style;
                if ("object" == typeof style) {
                    for (var key in style) configLanguage(key, def.getOwn(style, key));
                    return cdo.format;
                }
            }
            return getLanguage(style, !0);
        }
        if (2 == L) return configLanguage(style, config);
        throw def.error.operationInvalid("Wrong number of arguments");
    };
    langProvider({
        "en-gb": {
            number: {
                mask: "#,0.##",
                style: {
                    integerPad: "0",
                    fractionPad: "0",
                    decimal: ".",
                    group: ",",
                    groupSizes: [ 3 ],
                    abbreviations: [ "k", "m", "b", "t" ],
                    negativeSign: "-",
                    currency: "£"
                }
            },
            date: {
                mask: "%d/%m/%Y"
            }
        },
        "pt-pt": {
            number: {
                mask: "#,0.##",
                style: {
                    integerPad: "0",
                    fractionPad: "0",
                    decimal: ",",
                    group: " ",
                    groupSizes: [ 3 ],
                    abbreviations: [ "k", "m", "b", "t" ],
                    negativeSign: "-",
                    currency: "€"
                }
            },
            date: {
                mask: "%d/%m/%Y"
            }
        }
    });
    return cdo;
}(def, pv);