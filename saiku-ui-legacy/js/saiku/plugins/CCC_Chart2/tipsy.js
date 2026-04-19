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

!function() {
    function toParentTransform(parentPanel) {
        return pv.Transform.identity.translate(parentPanel.left(), parentPanel.top()).times(parentPanel.transform());
    }
    function getVisibleScreenBounds(mark) {
        for (var right, bottom, parent, instance = mark.instance(), left = instance.left, top = instance.top, width = instance.width, height = instance.height; parent = mark.parent; ) {
            if (0 > left) {
                width += left;
                left = 0;
            }
            if (0 > top) {
                height += top;
                top = 0;
            }
            right = instance.right;
            0 > right && (width += right);
            bottom = instance.bottom;
            0 > bottom && (height += bottom);
            var t = toParentTransform(parent), s = t.k;
            left = t.x + s * left;
            top = t.y + s * top;
            width = s * width;
            height = s * height;
            mark = parent;
            instance = mark.instance();
        }
        return {
            left: left,
            top: top,
            width: width,
            height: height
        };
    }
    $.fn.tipsy.elementOptions = function(elem, options) {
        var markOpts = elem.$tooltipOptions;
        options = $.extend({}, options, markOpts || {}, {
            gravity: options.gravity
        });
        return options;
    };
    var _nextTipsyId = 0;
    pv.Behavior.tipsy = function(opts) {
        function getTooltipText() {
            var instance = _mark.instance(), title = _mark.properties.tooltip ? instance.tooltip : "function" == typeof _mark.tooltip ? _mark.tooltip() : instance.title || instance.text;
            "function" == typeof title && (title = title());
            return title || "";
        }
        function getInstanceBounds() {
            var left, top, width, height, instance = _mark.instance();
            if (_mark.properties.width) {
                var bounds = getVisibleScreenBounds(_mark);
                left = bounds.left;
                top = bounds.top;
                width = bounds.width;
                height = bounds.height;
            } else {
                var radius, t = _mark.toScreenTransform();
                if (_mark.properties.outerRadius) {
                    var midAngle = instance.startAngle + instance.angle / 2;
                    radius = instance.outerRadius;
                    left = t.x + instance.left + radius * Math.cos(midAngle);
                    top = t.y + instance.top + radius * Math.sin(midAngle);
                } else if (_mark.properties.shapeRadius) {
                    radius = getTooltipOptions().ignoreRadius ? 0 : Math.max(2, instance.shapeRadius);
                    var cx = instance.left, cy = instance.top;
                    switch (instance.shape) {
                      case "diamond":
                        radius *= Math.SQRT2;
                        break;

                      case "circle":
                        radius /= Math.SQRT2;
                    }
                    left = (cx - radius) * t.k + t.x;
                    top = (cy - radius) * t.k + t.y;
                    height = width = 2 * radius * t.k;
                } else {
                    left = instance.left * t.k + t.x;
                    top = instance.top * t.k + t.y;
                }
            }
            var left2 = Math.ceil(left), top2 = Math.ceil(top), leftE = left2 - left, topE = top2 - top;
            width = Math.max(1, Math.floor((width || 0) - leftE));
            height = Math.max(1, Math.floor((height || 0) - topE));
            return {
                left: left2,
                top: top2,
                width: width,
                height: height
            };
        }
        function getTooltipOptions() {
            return _mark && _mark.tooltipOptions || opts;
        }
        function updateUserGravity() {
            var opts = getTooltipOptions(), grav = pv.get(opts, "gravity");
            grav && "function" == typeof grav && (grav = grav.call(_mark));
            _tip.debug >= 21 && _tip.log("[TIPSY] #" + _tipsyId + " Update User Gravity " + grav);
            return _userGravity = grav || $.fn.tipsy.defaults.gravity;
        }
        function calculateGravity(tipSize, calcPosition) {
            function scoreGravity(gravity) {
                var tp = calcPosition(gravity);
                return scorePosition(gravity, tp);
            }
            function scorePosition(gravity, tp) {
                var wScore = calcPosScore(tp.left, "width"), hScore = calcPosScore(tp.top, "height"), isMouseInside = _mousePage && !opts.followMouse;
                if (isMouseInside) {
                    var tipRect = new pv.Shape.Rect(tp.left, tp.top, tipSize.width, tipSize.height);
                    isMouseInside = tipRect.containsPoint(_mousePage);
                }
                var isTotal = !isMouseInside && wScore.fits && hScore.fits, value = wScore.value + hScore.value + (2 - gravity.length) + (isMouseInside ? -1e3 : 0);
                return {
                    gravity: gravity,
                    width: wScore,
                    height: hScore,
                    value: value,
                    isMouseInside: isMouseInside,
                    isTotal: isTotal,
                    isPartial: wScore.fits || hScore.fits
                };
            }
            function calcPosScore(absPos, a_len) {
                var maxLen = pageSize[a_len], len = tipSize[a_len], pos = absPos - scrollOffset[a_len], opos = maxLen - (pos + len), fits = pos >= 0 && opos >= 0, value = (pos >= 0 ? pos : 4 * pos) + (opos >= 0 ? opos : 4 * opos);
                return {
                    fits: fits,
                    value: value
                };
            }
            if (this !== $fakeTipTarget[0]) throw new Error("Assertion failed.");
            var $win = $(window), scrollOffset = {
                width: $win.scrollLeft(),
                height: $win.scrollTop()
            }, pageSize = {
                width: $win.width(),
                height: $win.height()
            }, gravity = _userGravity;
            "c" === gravity && (gravity = "w");
            var bestScore = scoreGravity(gravity);
            if (!bestScore.isTotal) {
                for (var g = _gravities.indexOf(gravity), n = 1, L = _gravities.length; L > n; n++) {
                    var i = (g + n) % L;
                    bestScore = chooseScores(bestScore, scoreGravity(_gravities[i]));
                }
                _tip.debug >= 21 && gravity !== bestScore.gravity && _tip.log("[TIPSY] #" + _tipsyId + " Choosing gravity '" + bestScore.gravity + "' over '" + gravity + "'");
                gravity = bestScore.gravity;
            }
            _tip.debug >= 21 && _tip.log("[TIPSY] #" + _tipsyId + " Gravity '" + gravity + "'");
            return gravity;
        }
        function chooseScores(score1, score2) {
            if (score1.isTotal) {
                if (!score2.isTotal) return score1;
            } else if (score2.isTotal) {
                if (!score1.isTotal) return score2;
            } else if (score1.isPartial) {
                if (!score2.isPartial) return score1;
            } else if (score2.isPartial && !score1.isPartial) return score2;
            return score2.value > score1.value ? score2 : score1;
        }
        function setFakeTipTargetBounds(bounds) {
            $fakeTipTarget.css({
                left: bounds.left + parseFloat($canvas.css("padding-left")),
                top: bounds.top + parseFloat($canvas.css("padding-top")),
                width: bounds.width,
                height: bounds.height
            });
        }
        function createTipsy(mark) {
            _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Creating _id=" + _id);
            var c = mark.root.canvas();
            $canvas = $(c);
            c.style.position = "relative";
            $canvas.mouseleave(hideTipsy);
            opts.usesPoint && opts.followMouse && mark.root.event("mousemove", doFollowMouse);
            initTipsyCanvasSharedInfo();
            _id || (_id = "tipsyPvBehavior_" + new Date().getTime());
            var fakeTipTarget = document.getElementById(_id);
            if (!fakeTipTarget) {
                _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Creating Fake Tip Target=" + _id);
                fakeTipTarget = document.createElement("div");
                fakeTipTarget.id = _id;
                fakeTipTarget.className = "fakeTipsyTarget";
                c.appendChild(fakeTipTarget);
            }
            var fakeStyle = fakeTipTarget.style;
            fakeStyle.padding = "0px";
            fakeStyle.margin = "0px";
            fakeStyle.position = "absolute";
            fakeStyle.pointerEvents = "none";
            fakeStyle.display = "block";
            fakeStyle.zIndex = -10;
            $fakeTipTarget = $(fakeTipTarget);
            updateTipDebug();
            $fakeTipTarget.removeData("tipsy");
            var opts2 = Object.create(opts);
            opts2.gravity = calculateGravity;
            opts2.delayOut = 0;
            opts2.trigger = "manual";
            null == opts.animate && (opts.animate = opts.followMouse ? 0 : 400);
            $fakeTipTarget[0].$tooltipOptions = mark.tooltipOptions;
            $fakeTipTarget.tipsy(opts2);
        }
        function initTipsyCanvasSharedInfo() {
            _sharedTipsyInfo = $canvas.data("tipsy-pv-shared-info");
            if (_sharedTipsyInfo) {
                var createId = $canvas[0].$pvCreateId || 0;
                if (_sharedTipsyInfo.createId === createId) {
                    _sharedTipsyInfo.behaviors.push(disposeTipsy);
                    return;
                }
                _sharedTipsyInfo.behaviors.forEach(function(dispose) {
                    dispose();
                });
            }
            _sharedTipsyInfo = {
                createId: $canvas[0].$pvCreateId || 0,
                behaviors: [ disposeTipsy ]
            };
            $canvas.data("tipsy-pv-shared-info", _sharedTipsyInfo);
        }
        function updateTipDebug() {
            $fakeTipTarget && (_tip.debug >= 22 ? $fakeTipTarget.css({
                borderColor: "red",
                borderWidth: "1px",
                borderStyle: "solid",
                zIndex: 1e3
            }) : $fakeTipTarget.css({
                borderWidth: "0px",
                zIndex: -10
            }));
        }
        function getMouseBounds(ev) {
            ev || (ev = pv.event);
            var delta = 5, offset = $canvas.offset();
            return {
                left: ev.pageX - offset.left - delta,
                top: ev.pageY - offset.top - delta,
                width: 10 + 2 * delta,
                height: 20
            };
        }
        function setMark(mark) {
            mark || (mark = null);
            var index, renderId, scenes;
            if (mark !== _mark) {
                _mark = mark;
                if (mark) {
                    _scenes = mark.scene;
                    _index = getOwnerInstance(_scenes, _mark.index);
                    _renderId = mark.renderId();
                } else {
                    _renderId = _scenes = _index = null;
                    _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Cleared Mark");
                }
            } else {
                if (!mark) return !1;
                if (_scenes !== (scenes = mark.scene)) {
                    _scenes = scenes;
                    _index = getOwnerInstance(_scenes, _mark.index);
                    _renderId = mark.renderId();
                } else if (_index !== (index = getOwnerInstance(_scenes, _mark.index))) {
                    _index = index;
                    _renderId = mark.renderId();
                } else {
                    if (_renderId === (renderId = mark.renderId())) return !1;
                    _renderId = renderId;
                }
            }
            $fakeTipTarget[0].$tooltipOptions = _mark && _mark.tooltipOptions;
            mark && _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Set Mark State to " + mark.type + " scenes: #" + _scenes.length + " index: " + _index + " renderId: " + _renderId);
            return !0;
        }
        function setTarget(targetElem, mark) {
            targetElem && mark || (targetElem = mark = null);
            var changedTargetElem = !$targetElem && targetElem || $targetElem && $targetElem[0] !== targetElem;
            if (changedTargetElem) {
                _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " " + (targetElem ? "Changing target element " + targetElem.tagName + "." : "Clearing target element."));
                if (changedTargetElem) {
                    if ($targetElem) {
                        $targetElem.off("mousemove", onTargetElemMouseMove);
                        $targetElem.off("mouseleave", hideTipsy);
                    }
                    $targetElem = targetElem ? $(targetElem) : null;
                }
                setMark(mark);
                if ($targetElem) {
                    $targetElem.mousemove(onTargetElemMouseMove);
                    $targetElem.mouseleave(hideTipsy);
                }
            }
        }
        function getRealIndex(scene, index) {
            var index0 = index;
            if ("function" == typeof _mark.getNearestInstanceToMouse) {
                index = _mark.getNearestInstanceToMouse(scene, index);
                _tip.debug >= 20 && index0 !== index && _tip.log("[TIPSY] #" + _tipsyId + " Changing index " + index0 + " to Nearest index " + index);
            }
            return getOwnerInstance(scene, index);
        }
        function getOwnerInstance(scene, index) {
            if ("function" == typeof _mark.getOwnerInstance) {
                var index0 = index;
                index = _mark.getOwnerInstance(scene, index);
                _tip.debug >= 20 && index0 !== index && _tip.log("[TIPSY] #" + _tipsyId + " Changing index " + index0 + " to Owner index " + index);
            }
            return index;
        }
        function getNewOperationId() {
            return _nextOperId++;
        }
        function checkCanOperate(opId) {
            return opId === _nextOperId - 1;
        }
        function hideTipsy() {
            var opId = getNewOperationId();
            _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Delayed Hide Begin opId=" + opId);
            if (_delayOut > 0) window.setTimeout(function() {
                if (checkCanOperate(opId)) {
                    _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Hiding opId=" + opId);
                    hideTipsyCore(opId);
                } else _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Delayed Hide Cancelled opId=" + opId);
            }, _delayOut); else {
                _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Hiding Immediately opId=" + opId);
                hideTipsyCore(opId);
            }
        }
        function disposeTipsy() {
            _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Disposing");
            hideTipsyOther();
            if ($fakeTipTarget) {
                $fakeTipTarget.removeData("tipsy");
                $fakeTipTarget.each(function(elem) {
                    elem.$tooltipOptions = null;
                });
                $fakeTipTarget.remove();
                $fakeTipTarget = null;
            }
            if ($canvas) {
                $canvas.off("mouseleave", hideTipsy);
                $canvas = null;
            }
        }
        function hideTipsyOther() {
            var opId = getNewOperationId();
            _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Hiding as Other opId=" + opId);
            hideTipsyCore(opId);
        }
        function hideTipsyCore(opId) {
            setTarget(null, null);
            setMark(null);
            $fakeTipTarget && $fakeTipTarget.data("tipsy") && $fakeTipTarget.tipsy("leave");
        }
        function hideOtherTipsies() {
            var hideTipsies = _sharedTipsyInfo && _sharedTipsyInfo.behaviors;
            if (hideTipsies && hideTipsies.length > 1) {
                _tip.debug >= 20 && _tip.group("[TIPSY] #" + _tipsyId + " Hiding Others");
                hideTipsies.forEach(function(hideTipsyFun) {
                    hideTipsyFun !== disposeTipsy && hideTipsyFun();
                });
                _tip.debug >= 20 && _tip.groupEnd();
            }
        }
        function isRealMouseMove(ev) {
            _mousePage = new pv.Shape.Point(ev.pageX, ev.pageY);
            if (_prevMousePage && _mousePage.distance2(_prevMousePage).cost <= 8) {
                _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " mousemove too close");
                return !1;
            }
            return !0;
        }
        function doFollowMouse() {
            _tip.debug >= 20 && _tip.group("[TIPSY] #" + _tipsyId + " doFollowMouse");
            var ev = pv.event;
            if (!_mark || _isEnabledFun && !_isEnabledFun(tipsyBehavior, _mark)) {
                hideTipsy();
                _tip.debug >= 20 && _tip.groupEnd();
            } else {
                if ($fakeTipTarget && _mark && isRealMouseMove(ev)) {
                    _prevMousePage = _mousePage;
                    setFakeTipTargetBounds(getMouseBounds(ev));
                    hideOtherTipsies();
                    $fakeTipTarget.tipsy("update");
                }
                _tip.debug >= 20 && _tip.groupEnd();
            }
        }
        function onTargetElemMouseMove(ev) {
            if ($fakeTipTarget && isRealMouseMove(ev)) {
                var scenes, tag = this.$scene;
                if (tag && (scenes = tag.scenes) && scenes.mark && scenes.mark === _mark) {
                    var renderId = _mark.renderId(), sceneChanged = renderId !== _renderId || scenes !== _scenes, followMouse = opts.followMouse, index = tag.index;
                    if ("function" == typeof _mark.getOwnerInstance || "function" == typeof _mark.getNearestInstanceToMouse) {
                        pv.event = ev;
                        _mark.context(scenes, index, function() {
                            index = getRealIndex(scenes, index);
                        });
                        pv.event = null;
                    }
                    sceneChanged |= index !== _index;
                    if (followMouse || sceneChanged) {
                        var opId = getNewOperationId();
                        _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Updating opId=" + opId);
                        _prevMousePage = _mousePage;
                        var bounds;
                        followMouse && (bounds = getMouseBounds(ev));
                        if (sceneChanged) {
                            _renderId = renderId;
                            _scenes = scenes;
                            _index = index;
                            _mark.context(scenes, index, function() {
                                followMouse || (bounds = getInstanceBounds());
                                var text = getTooltipText();
                                _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Update text. Was hidden. Text: " + text.substr(0, 50));
                                $fakeTipTarget.tipsy("setTitle", text);
                                updateUserGravity();
                            });
                        }
                        setFakeTipTargetBounds(bounds);
                        hideOtherTipsies();
                        $fakeTipTarget.tipsy("update");
                    } else _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " !followMouse and same scene");
                } else _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " mousemove on != mark");
            }
        }
        function initMark(mark) {
            $canvas || createTipsy(mark);
            if (mark._tipsy !== tipsyBehavior) {
                _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Initializing mark");
                mark._tipsy = tipsyBehavior;
                opts.usesPoint && mark.event("unpoint", function() {
                    _tip.debug >= 20 && _tip.group("[TIPSY] #" + _tipsyId + " unpoint");
                    hideTipsy();
                    _tip.debug >= 20 && _tip.groupEnd();
                });
            }
        }
        function showTipsy(mark) {
            function updateTextAndBounds() {
                var text = getTooltipText();
                _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Set Text: " + text.substr(0, 50));
                $fakeTipTarget.tipsy("setTitle", text);
                setFakeTipTargetBounds(opts.followMouse ? getMouseBounds() : getInstanceBounds());
                updateUserGravity();
            }
            var opId = getNewOperationId();
            _tip.debug >= 20 && _tip.group("[TIPSY] #" + _tipsyId + " ShowTipsy opId=" + opId);
            initMark(mark);
            var isHidden = !_mark;
            opts.usesPoint ? setMark(mark) : setTarget(pv.event.target, mark);
            var ev = pv.event;
            isRealMouseMove(ev);
            _prevMousePage = _mousePage;
            mark.index !== _index ? mark.context(_scenes, _index, updateTextAndBounds) : updateTextAndBounds();
            hideOtherTipsies();
            $fakeTipTarget.tipsy(isHidden ? "enter" : "update");
            _tip.debug >= 20 && _tip.groupEnd();
        }
        function tipsyBehavior() {
            var mark = this;
            (!_isEnabledFun || _isEnabledFun(tipsyBehavior, mark)) && showTipsy(mark);
        }
        opts || (opts = {});
        var $fakeTipTarget, _prevMousePage, _mousePage, _userGravity, _renderId, _index, _scenes, _id, $canvas, _sharedTipsyInfo, $targetElem = null, _tipsyId = _nextTipsyId++, _nextOperId = 0, _mark = null, _delayOut = opts.delayOut, _isEnabledFun = opts.isEnabled, _gravities = [ "nw", "n", "ne", "e", "se", "s", "sw", "w" ];
        return tipsyBehavior;
    };
    var _tip = pv.Behavior.tipsy;
    _tip.debug = 0;
    _tip.setDebug = function(level) {
        _tip.debug = level;
    };
    _tip.log = function(m) {
        "undefined" != typeof console && console.log("" + m);
    };
    _tip.group = function(m) {
        "undefined" != typeof console && console.group("" + m);
    };
    _tip.groupEnd = function(m) {
        "undefined" != typeof console && console.groupEnd();
    };
    _tip.disposeAll = function(panel) {
        if (panel) {
            var canvas = panel.root.canvas();
            if (canvas) {
                var $canvas = $(canvas), sharedTipsyInfo = $canvas.data("tipsy-pv-shared-info");
                if (sharedTipsyInfo) {
                    sharedTipsyInfo.behaviors && sharedTipsyInfo.behaviors.forEach(function(dispose) {
                        dispose();
                    });
                    $canvas.removeData("tipsy-pv-shared-info");
                }
            }
        }
        _tip.removeAll();
    };
    _tip.removeAll = function() {
        $(".tipsy").remove();
    };
}();