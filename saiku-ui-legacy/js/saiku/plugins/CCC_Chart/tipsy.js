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
                    radius = Math.max(2, instance.shapeRadius);
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
        function updateUserGravity() {
            _userGravityFun && (_userGravity = _userGravityFun.call(_mark) || $.fn.tipsy.defaults.gravity);
            return _userGravity;
        }
        function calculateGravity(tipSize, calcPosition) {
            function scoreGravity(gravity) {
                var tp = calcPosition(gravity);
                return scorePosition(gravity, tp);
            }
            function scorePosition(gravity, tp) {
                var wScore = calcPosScore(tp.left, "width"), hScore = calcPosScore(tp.top, "height"), isTotal = wScore.fits && hScore.fits;
                return {
                    gravity: gravity,
                    width: wScore,
                    height: hScore,
                    value: wScore.value + hScore.value + (2 - gravity.length),
                    isTotal: isTotal,
                    isPartial: !isTotal && (wScore.fits || hScore.fits)
                };
            }
            function calcPosScore(absPos, a_len) {
                var maxLen = pageSize[a_len], len = tipSize[a_len], pos = absPos - scrollOffset[a_len], opos = maxLen - (pos + len), fits = pos >= 0 && opos >= 0, value = (pos >= 0 ? pos : 4 * pos) + (opos >= 0 ? opos : 4 * opos);
                return {
                    fits: fits,
                    value: value
                };
            }
            this === $fakeTipTarget[0] || def.assert();
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
            var c = mark.root.canvas();
            $canvas = $(c);
            c.style.position = "relative";
            $canvas.mouseleave(hideTipsy);
            initTipsyCanvasSharedInfo();
            id || (id = "tipsyPvBehavior_" + new Date().getTime());
            var fakeTipTarget = document.getElementById(id);
            if (!fakeTipTarget) {
                fakeTipTarget = document.createElement("div");
                fakeTipTarget.id = id;
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
            $fakeTipTarget.data("tipsy", null);
            $fakeTipTarget.tipsy(opts);
        }
        function initTipsyCanvasSharedInfo() {
            sharedTipsyInfo = $canvas.data("tipsy-pv-shared-info");
            if (sharedTipsyInfo) {
                var createId = $canvas[0].$pvCreateId || 0;
                if (sharedTipsyInfo.createId === createId) {
                    sharedTipsyInfo.behaviors.push(hideTipsyOther);
                    return;
                }
                sharedTipsyInfo.behaviors.forEach(function(aHideTipsy) {
                    aHideTipsy();
                });
            }
            sharedTipsyInfo = {
                createId: $canvas[0].$pvCreateId || 0,
                behaviors: [ hideTipsyOther ]
            };
            $canvas.data("tipsy-pv-shared-info", sharedTipsyInfo);
        }
        function updateTipDebug() {
            $fakeTipTarget && $fakeTipTarget.css(_tip.debug >= 22 ? {
                borderColor: "red",
                borderWidth: "1px",
                borderStyle: "solid",
                zIndex: 1e3
            } : {
                borderWidth: "0px",
                zIndex: -10
            });
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
        function setTarget(targetElem, mark) {
            if (!$targetElem && targetElem || $targetElem && $targetElem[0] !== targetElem) {
                _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Changing target element.");
                if ($targetElem) {
                    $targetElem.unbind("mousemove", updateTipsy);
                    usesPoint || $targetElem.unbind("mouseleave", hideTipsy);
                }
                $targetElem = targetElem ? $(targetElem) : null;
                _mark = targetElem ? mark : null;
                prevMouseX = prevMouseY = _renderId = null;
                if ($targetElem) {
                    $targetElem.mousemove(updateTipsy);
                    usesPoint || $targetElem.mouseleave(hideTipsy);
                }
            }
        }
        function getNewOperationId() {
            return nextOperationId++;
        }
        function checkCanOperate(opId) {
            return opId === nextOperationId - 1;
        }
        function hideTipsy() {
            var opId = getNewOperationId();
            _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Delayed Hide Begin opId=" + opId);
            if (delayOut > 0) window.setTimeout(function() {
                if (checkCanOperate(opId)) {
                    _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Hiding opId=" + opId + " nextOperationId=" + nextOperationId);
                    hideTipsyCore(opId);
                } else _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Delayed Hide Cancelled opId=" + opId);
            }, delayOut); else {
                _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Hiding Immediately opId=" + opId);
                hideTipsyCore(opId);
            }
        }
        function hideTipsyOther() {
            var opId = getNewOperationId();
            _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Hiding as Other opId=" + opId);
            hideTipsyCore(opId);
        }
        function hideTipsyCore() {
            setTarget(null, null);
            $fakeTipTarget && $fakeTipTarget.tipsy("leave");
        }
        function hideOtherTipsies() {
            var hideTipsies = sharedTipsyInfo && sharedTipsyInfo.behaviors;
            hideTipsies && hideTipsies.length > 1 && hideTipsies.forEach(function(aHideTipsy) {
                aHideTipsy !== hideTipsyOther && aHideTipsy();
            });
        }
        function updateTipsy(ev) {
            if ($fakeTipTarget && !(null != prevMouseX && Math.abs(ev.clientX - prevMouseX) < 3 && Math.abs(ev.clientY - prevMouseY) < 3)) {
                var scenes, tag = this.$scene;
                if (tag && (scenes = tag.scenes) && scenes.mark && scenes.mark === _mark) {
                    var renderId = _mark.renderId(), renderIdChanged = renderId !== _renderId, followMouse = opts.followMouse;
                    if (followMouse || renderIdChanged) {
                        var opId = getNewOperationId();
                        _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Updating opId=" + opId);
                        prevMouseX = ev.clientX;
                        prevMouseY = ev.clientY;
                        var bounds;
                        followMouse && (bounds = getMouseBounds(ev));
                        if (renderIdChanged) {
                            _renderId = renderId;
                            _mark.context(scenes, tag.index, function() {
                                followMouse || (bounds = getInstanceBounds());
                                var text = getTooltipText();
                                _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Update text. Was hidden. Text: " + text);
                                $fakeTipTarget.tipsy("setTitle", text);
                                updateUserGravity();
                            });
                        }
                        setFakeTipTargetBounds(bounds);
                        hideOtherTipsies();
                        $fakeTipTarget.tipsy("update");
                    }
                }
            }
        }
        function initBehavior(mark) {
            _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Creating");
            createTipsy(mark);
            usesPoint && mark.event("unpoint", hideTipsy);
        }
        function showTipsy(mark) {
            var opId = getNewOperationId();
            _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Show IN opId=" + opId);
            $canvas || initBehavior(mark);
            var isHidden = !$targetElem;
            setTarget(pv.event.target, mark);
            var text = getTooltipText();
            _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Text: " + text);
            $fakeTipTarget.tipsy("setTitle", text);
            setFakeTipTargetBounds(opts.followMouse ? getMouseBounds() : getInstanceBounds());
            updateUserGravity();
            hideOtherTipsies();
            $fakeTipTarget.tipsy(isHidden ? "enter" : "update");
            _tip.debug >= 20 && _tip.log("[TIPSY] #" + _tipsyId + " Show OUT");
        }
        function tipsyBehavior() {
            var mark = this;
            (!isEnabled || isEnabled(tipsyBehavior, mark)) && showTipsy(mark);
        }
        var _tipsyId = _nextTipsyId++;
        opts = opts ? Object.create(opts) : {};
        opts.trigger = "manual";
        var _userGravityFun, _userGravity = opts.gravity || $.fn.tipsy.defaults.gravity;
        if ("function" == typeof _userGravity) {
            _userGravityFun = _userGravity;
            _userGravity = null;
        }
        opts.gravity = calculateGravity;
        var $fakeTipTarget, $targetElem, prevMouseX, prevMouseY, _renderId, _mark, id, $canvas, sharedTipsyInfo, nextOperationId = 0, delayOut = opts.delayOut, usesPoint = opts.usesPoint, isEnabled = opts.isEnabled;
        opts.delayOut = 0;
        var _gravities = [ "nw", "n", "ne", "e", "se", "s", "sw", "w" ];
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
}();