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

/*!
 * tipsy, facebook style tooltips for jquery
 * version 1.0.0a
 * (c) 2008-2010 jason frame [jason@onehackoranother.com]
 * released under the MIT license
 */

!function($) {
    function fixTitle($ele) {
        var title = $ele.attr("title");
        (title || "string" != typeof $ele.attr("original-title")) && $ele.attr("original-title", title || "").removeAttr("title");
    }
    function Tipsy(element, options) {
        this.$element = $(element);
        this.options = options;
        this.enabled = !0;
        fixTitle(this.$element);
    }
    Tipsy.prototype = {
        enter: function() {
            var tipsy = this, options = this.options;
            if (0 == options.delayIn) {
                tipsy.hoverState = null;
                tipsy.show();
            } else {
                tipsy.hoverState = "in";
                setTimeout(function() {
                    if ("in" === tipsy.hoverState) {
                        tipsy.hoverState = null;
                        tipsy.show();
                    }
                }, options.delayIn);
            }
        },
        leave: function() {
            var tipsy = this, options = this.options;
            if (0 == options.delayOut) tipsy.hide(); else {
                tipsy.hoverState = "out";
                setTimeout(function() {
                    "out" === tipsy.hoverState && tipsy.hide();
                }, options.delayOut);
            }
        },
        visible: function() {
            var parent;
            return "in" === this.hoverState || "out" !== this.hoverState && !(!this.$tip || !(parent = this.$tip[0].parentNode) || 11 === parent.nodeType);
        },
        update: function() {
            this.visible() ? this.show(!0) : this.enter();
        },
        show: function(isUpdate) {
            function calcPosition(gravity) {
                var tp;
                switch (gravity.charAt(0)) {
                  case "n":
                    tp = {
                        top: pos.top + pos.height + tipOffset,
                        left: pos.left + pos.width / 2 - actualWidth / 2
                    };
                    break;

                  case "s":
                    tp = {
                        top: pos.top - actualHeight - tipOffset,
                        left: pos.left + pos.width / 2 - actualWidth / 2
                    };
                    break;

                  case "e":
                    tp = {
                        top: pos.top + pos.height / 2 - actualHeight / 2,
                        left: pos.left - actualWidth - tipOffset
                    };
                    break;

                  case "w":
                    tp = {
                        top: pos.top + pos.height / 2 - actualHeight / 2,
                        left: pos.left + pos.width + tipOffset
                    };
                }
                2 === gravity.length && (tp.left = "w" == gravity.charAt(1) ? useCorners ? pos.left + pos.width + tipOffset : pos.left + pos.width / 2 - 15 : useCorners ? pos.left - actualWidth - tipOffset : pos.left + pos.width / 2 - actualWidth + 15);
                return tp;
            }
            if ("in" !== this.hoverState) {
                var title = this.getTitle();
                if (this.enabled && title) {
                    var $tip = this.tip();
                    $tip.find(".tipsy-inner")[this.options.html ? "html" : "text"](title);
                    $tip[0].className = "tipsy";
                    isUpdate || $tip.remove();
                    var parent = $tip[0].parentNode;
                    parent && 11 !== parent.nodeType || $tip.css({
                        top: 0,
                        left: 0,
                        visibility: "hidden",
                        display: "block"
                    }).appendTo(document.body);
                    var pos = $.extend({}, this.$element.offset());
                    if (this.$element[0].nearestViewportElement) {
                        var rect = this.$element[0].getBoundingClientRect();
                        pos.width = rect.width;
                        pos.height = rect.height;
                    } else {
                        pos.width = this.$element[0].offsetWidth || 0;
                        pos.height = this.$element[0].offsetHeight || 0;
                    }
                    var tipOffset = this.options.offset, useCorners = this.options.useCorners, showArrow = this.options.arrowVisible, actualWidth = $tip[0].offsetWidth, actualHeight = $tip[0].offsetHeight;
                    showArrow || (tipOffset -= 4);
                    var gravity = "function" == typeof this.options.gravity ? this.options.gravity.call(this.$element[0], {
                        width: actualWidth,
                        height: actualHeight
                    }, calcPosition) : this.options.gravity, tp = calcPosition(gravity);
                    $tip.css(tp).addClass("tipsy-" + gravity + (useCorners && gravity.length > 1 ? gravity.charAt(1) : ""));
                    if (showArrow) {
                        var hideArrow = useCorners && 2 === gravity.length;
                        $tip.find(".tipsy-arrow")[hideArrow ? "hide" : "show"]();
                    }
                    var doFadeIn = this.options.fade && (!isUpdate || !this._prevGravity || this._prevGravity !== gravity);
                    doFadeIn ? $tip.stop().css({
                        opacity: 0,
                        display: "block",
                        visibility: "visible"
                    }).animate({
                        opacity: this.options.opacity
                    }) : $tip.css({
                        visibility: "visible",
                        opacity: this.options.opacity
                    });
                    this._prevGravity = gravity;
                    this.hoverState = null;
                } else {
                    this.hoverState = null;
                    this.hide();
                }
            }
        },
        hide: function() {
            this.options.fade ? this.tip().stop().fadeOut(function() {
                $(this).remove();
            }) : this.$tip && this.tip().remove();
            this.hoverState = null;
        },
        setTitle: function(title) {
            title = null == title ? "" : "" + title;
            this.$element.attr("original-title", title).removeAttr("title");
        },
        getTitle: function() {
            var title, $e = this.$element, o = this.options;
            fixTitle($e);
            "string" == typeof o.title ? title = $e.attr("title" == o.title ? "original-title" : o.title) : "function" == typeof o.title && (title = o.title.call($e[0]));
            title = ("" + title).replace(/(^\s*|\s*$)/, "");
            return title || o.fallback;
        },
        tip: function() {
            if (!this.$tip) {
                this.$tip = $('<div class="tipsy"></div>');
                this.$tip.html(this.options.arrowVisible ? '<div class="tipsy-arrow"></div><div class="tipsy-inner"/></div>' : '<div class="tipsy-inner"/></div>');
                this.$tip.remove();
            }
            return this.$tip;
        },
        validate: function() {
            var parent = this.$element[0].parentNode;
            if (!parent || 11 === parent.nodeType) {
                this.hide();
                this.$element = null;
                this.options = null;
            }
        },
        enable: function() {
            this.enabled = !0;
        },
        disable: function() {
            this.enabled = !1;
        },
        toggleEnabled: function() {
            this.enabled = !this.enabled;
        }
    };
    $.fn.tipsy = function(options, arg) {
        function get(ele) {
            var tipsy = $.data(ele, "tipsy");
            if (!tipsy) {
                tipsy = new Tipsy(ele, $.fn.tipsy.elementOptions(ele, options));
                $.data(ele, "tipsy", tipsy);
            }
            return tipsy;
        }
        function enter() {
            get(this).enter();
        }
        function leave() {
            get(this).leave();
        }
        if (options === !0) return this.data("tipsy");
        if ("string" == typeof options) return this.data("tipsy")[options](arg);
        options = $.extend({}, $.fn.tipsy.defaults, options);
        null == options.arrowVisible && (options.arrowVisible = !options.useCorners);
        options.live || this.each(function() {
            get(this);
        });
        if ("manual" != options.trigger) {
            var binder = options.live ? "live" : "bind", eventIn = "hover" == options.trigger ? "mouseenter" : "focus", eventOut = "hover" == options.trigger ? "mouseleave" : "blur";
            this[binder](eventIn, enter)[binder](eventOut, leave);
        }
        return this;
    };
    $.fn.tipsy.defaults = {
        delayIn: 0,
        delayOut: 0,
        fade: !1,
        fallback: "",
        gravity: "n",
        html: !1,
        live: !1,
        offset: 0,
        opacity: .8,
        title: "title",
        trigger: "hover",
        useCorners: !1,
        arrowVisible: null
    };
    $.fn.tipsy.elementOptions = function(ele, options) {
        return $.metadata ? $.extend({}, options, $(ele).metadata()) : options;
    };
    $.fn.tipsy.autoNS = function() {
        return $(this).offset().top > $(document).scrollTop() + $(window).height() / 2 ? "s" : "n";
    };
    $.fn.tipsy.autoWE = function() {
        return $(this).offset().left > $(document).scrollLeft() + $(window).width() / 2 ? "e" : "w";
    };
}(jQuery);