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

/*! Copyright 2010 Stanford Visualization Group, Mike Bostock, BSD license. */

/*! 539b7b4908c29303d90a687962d250d90abf0def */

/*
 * TERMS OF USE - EASING EQUATIONS
 *
 * Open source under the BSD License.
 *
 * Copyright 2001 Robert Penner
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * - Neither the name of the author nor the names of contributors may be used to
 *   endorse or promote products derived from this software without specific
 *   prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

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

Array.prototype.reduce || (Array.prototype.reduce = function(f, v) {
    var len = this.length;
    if (!len && 1 == arguments.length) throw new Error("reduce: empty array, no initial value");
    var i = 0;
    if (arguments.length < 2) for (;;) {
        if (i in this) {
            v = this[i++];
            break;
        }
        if (++i >= len) throw new Error("reduce: no values, no initial value");
    }
    for (;len > i; i++) i in this && (v = f(v, this[i], i, this));
    return v;
});

Array.prototype.indexOf || (Array.prototype.indexOf = function(s, from) {
    for (var n = this.length >>> 0, i = !isFinite(from) || 0 > from ? 0 : from > this.length ? this.length : from; n > i; i++) if (this[i] === s) return i;
    return -1;
});

Date.now || (Date.now = function() {
    return +new Date();
});

Object.create || (Object.create = function(proto) {
    function g() {}
    g.prototype = proto;
    return new g();
});

var pv = {};

pv.version = {
    major: 3,
    minor: 3
};

pv.identity = function(x) {
    return x;
};

pv.index = function() {
    return this.index;
};

pv.child = function() {
    return this.childIndex;
};

pv.parent = function() {
    return this.parent.index;
};

!function() {
    pv.extend = function(f) {
        return Object.create(f.prototype || f);
    };
    pv.extendType = function(g, f) {
        var sub = g.prototype = pv.extend(f);
        sub.constructor = g;
        return g;
    };
    pv.parse = function(js) {
        for (var m, d, re = new RegExp("function\\s*(\\b\\w+)?\\s*\\([^)]*\\)\\s*", "mg"), i = 0, s = ""; m = re.exec(js); ) {
            var j = m.index + m[0].length;
            if ("{" != js.charAt(j)) {
                s += js.substring(i, j) + "{return ";
                i = j;
                for (var p = 0; p >= 0 && j < js.length; j++) {
                    var c = js.charAt(j);
                    switch (c) {
                      case '"':
                      case "'":
                        for (;++j < js.length && (d = js.charAt(j)) != c; ) "\\" == d && j++;
                        break;

                      case "[":
                      case "(":
                        p++;
                        break;

                      case "]":
                      case ")":
                        p--;
                        break;

                      case ";":
                      case ",":
                        0 == p && p--;
                    }
                }
                s += pv.parse(js.substring(i, --j)) + ";}";
                i = j;
            }
            re.lastIndex = j;
        }
        s += js.substring(i);
        return s;
    };
    pv.error = function(e) {
        "undefined" != typeof console && console.error ? console.error(e) : alert(e);
    };
    pv.listen = function(target, type, listener) {
        listener = pv.listener(listener);
        if ("load" === type || "onload" === type) return pv.listenForPageLoad(listener);
        if (target.addEventListener) target.addEventListener(type, listener, !1); else {
            target === window && (target = document.documentElement);
            target.attachEvent("on" + type, listener);
        }
        return listener;
    };
    pv.unlisten = function(target, type, listener) {
        listener.$listener && (listener = listener.$listener);
        target.removeEventListener ? target.removeEventListener(type, listener, !1) : target.detachEvent("on" + type, listener);
    };
    pv.listenForPageLoad = function(listener) {
        "complete" !== document.readyState ? document.addEventListener ? window.addEventListener("load", listener, !1) : document.attachEvent && window.attachEvent("onload", listener) : listener(null);
    };
    pv.listener = function(f) {
        return f.$listener || (f.$listener = function(ev) {
            try {
                pv.event = ev = ev && pv.fixEvent(ev);
                return f.call(this, ev);
            } catch (ex) {
                pv.error(ex);
            } finally {
                delete pv.event;
            }
        });
    };
    pv.fixEvent = function(ev) {
        if (null == ev.pageX && null != ev.clientX) {
            var eventDoc = ev.target && ev.target.ownerDocument || document, doc = eventDoc.documentElement, body = eventDoc.body;
            ev.pageX = 1 * ev.clientX + (doc && doc.scrollLeft || body && body.scrollLeft || 0) - (doc && doc.clientLeft || body && body.clientLeft || 0);
            ev.pageY = 1 * ev.clientY + (doc && doc.scrollTop || body && body.scrollTop || 0) - (doc && doc.clientTop || body && body.clientTop || 0);
        }
        return ev;
    };
    pv.ancestor = function(a, e) {
        for (;e; ) {
            if (e === a) return !0;
            e = e.parentNode;
        }
        return !1;
    };
    pv.removeChildren = function(p) {
        for (;p.lastChild; ) p.removeChild(p.lastChild);
    };
    pv.getWindow = function(elem) {
        return null != elem && elem == elem.window ? elem : 9 === elem.nodeType ? elem.defaultView || elem.parentWindow : !1;
    };
    var _reHiphenSep = /\-([a-z])/g;
    pv.hiphen2camel = function(prop) {
        return _reHiphenSep.test(prop) ? prop.replace(_reHiphenSep, function($0, $1) {
            return $1.toUpperCase();
        }) : prop;
    };
    var _getCompStyle = window.getComputedStyle;
    pv.css = function(e, p) {
        return _getCompStyle ? _getCompStyle.call(window, e, null).getPropertyValue(p) : e.currentStyle["float" === p ? "styleFloat" : pv.hiphen2camel(p)];
    };
    pv.cssStyle = function(e) {
        var style;
        if (_getCompStyle) {
            style = _getCompStyle.call(window, e, null);
            return function(p) {
                return style.getPropertyValue(p);
            };
        }
        style = e.currentStyle;
        return function(p) {
            return style["float" === p ? "styleFloat" : pv.hiphen2camel(p)];
        };
    };
    pv._getElementsByClass = function(searchClass, node) {
        null == node && (node = document);
        for (var classElements = [], els = node.getElementsByTagName("*"), L = els.length, pattern = new RegExp("(^|\\s)" + searchClass + "(\\s|$)"), i = 0, j = 0; L > i; i++) if (pattern.test(els[i].className)) {
            classElements[j] = els[i];
            j++;
        }
        return classElements;
    };
    pv.getElementsByClassName = function(node, classname) {
        return node.getElementsByClassName ? node.getElementsByClassName(classname) : pv._getElementsByClass(classname, node);
    };
    pv.elementOffset = function(elem) {
        var doc = elem && elem.ownerDocument;
        if (doc) {
            var body = doc.body;
            if (body !== elem) {
                var box;
                box = "undefined" != typeof elem.getBoundingClientRect ? elem.getBoundingClientRect() : {
                    top: 0,
                    left: 0
                };
                var win = pv.getWindow(doc), docElem = doc.documentElement, clientTop = docElem.clientTop || body.clientTop || 0, clientLeft = docElem.clientLeft || body.clientLeft || 0, scrollTop = win.pageYOffset || docElem.scrollTop, scrollLeft = win.pageXOffset || docElem.scrollLeft;
                return {
                    top: box.top + scrollTop - clientTop,
                    left: box.left + scrollLeft - clientLeft
                };
            }
        }
    };
    pv.renderer = function() {
        var renderer = document.svgImplementation || "nativesvg";
        pv.renderer = function() {
            return renderer;
        };
        return renderer;
    };
    var _id = 1;
    pv.id = function() {
        return _id++;
    };
    pv.functor = function(v) {
        return "function" == typeof v ? v : function() {
            return v;
        };
    };
    pv.get = function(o, p, dv) {
        var v;
        return o && null != (v = o[p]) ? v : dv;
    };
    var hasOwn = Object.prototype.hasOwnProperty;
    pv.lazyArrayOwn = function(o, p) {
        var v;
        return o && hasOwn.call(o, p) && (v = o[p]) ? v : o[p] = [];
    };
}();

pv.listen(window, "load", function() {
    pv.$ = {
        i: 0,
        x: document.getElementsByTagName("script")
    };
    pv.$.xlen = pv.$.x.length;
    for (;pv.$.i < pv.$.xlen; pv.$.i++) {
        pv.$.s = pv.$.x[pv.$.i];
        if ("text/javascript+protovis" == pv.$.s.type) try {
            window.eval(pv.parse(pv.$.s.text));
        } catch (e) {
            pv.error(e);
        }
    }
    delete pv.$;
});

pv.Format = {};

pv.Format.re = function(s) {
    return s.replace(/[\\\^\$\*\+\?\[\]\(\)\.\{\}]/g, "\\$&");
};

pv.Format.pad = function(c, n, s) {
    var m = n - String(s).length;
    return 1 > m ? s : new Array(m + 1).join(c) + s;
};

pv.Format.date = function(pattern) {
    function format(d) {
        return pattern.replace(/%[a-zA-Z0-9]/g, function(s) {
            switch (s) {
              case "%a":
                return [ "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" ][d.getDay()];

              case "%A":
                return [ "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" ][d.getDay()];

              case "%h":
              case "%b":
                return [ "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" ][d.getMonth()];

              case "%B":
                return [ "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December" ][d.getMonth()];

              case "%c":
                return d.toLocaleString();

              case "%C":
                return pad("0", 2, Math.floor(d.getFullYear() / 100) % 100);

              case "%d":
                return pad("0", 2, d.getDate());

              case "%x":
              case "%D":
                return pad("0", 2, d.getMonth() + 1) + "/" + pad("0", 2, d.getDate()) + "/" + pad("0", 2, d.getFullYear() % 100);

              case "%e":
                return pad(" ", 2, d.getDate());

              case "%H":
                return pad("0", 2, d.getHours());

              case "%I":
                var h = d.getHours() % 12;
                return h ? pad("0", 2, h) : 12;

              case "%m":
                return pad("0", 2, d.getMonth() + 1);

              case "%M":
                return pad("0", 2, d.getMinutes());

              case "%n":
                return "\n";

              case "%p":
                return d.getHours() < 12 ? "AM" : "PM";

              case "%T":
              case "%X":
              case "%r":
                var h = d.getHours() % 12;
                return (h ? pad("0", 2, h) : 12) + ":" + pad("0", 2, d.getMinutes()) + ":" + pad("0", 2, d.getSeconds()) + " " + (d.getHours() < 12 ? "AM" : "PM");

              case "%R":
                return pad("0", 2, d.getHours()) + ":" + pad("0", 2, d.getMinutes());

              case "%S":
                return pad("0", 2, d.getSeconds());

              case "%Q":
                return pad("0", 3, d.getMilliseconds());

              case "%t":
                return "	";

              case "%u":
                var w = d.getDay();
                return w ? w : 1;

              case "%w":
                return d.getDay();

              case "%y":
                return pad("0", 2, d.getFullYear() % 100);

              case "%Y":
                return d.getFullYear();

              case "%%":
                return "%";
            }
            return s;
        });
    }
    var pad = pv.Format.pad;
    format.format = format;
    format.parse = function(s) {
        var year = 1970, month = 0, date = 1, hour = 0, minute = 0, second = 0, fields = [ function() {} ], re = pv.Format.re(pattern).replace(/%[a-zA-Z0-9]/g, function(s) {
            switch (s) {
              case "%b":
                fields.push(function(x) {
                    month = {
                        Jan: 0,
                        Feb: 1,
                        Mar: 2,
                        Apr: 3,
                        May: 4,
                        Jun: 5,
                        Jul: 6,
                        Aug: 7,
                        Sep: 8,
                        Oct: 9,
                        Nov: 10,
                        Dec: 11
                    }[x];
                });
                return "([A-Za-z]+)";

              case "%h":
              case "%B":
                fields.push(function(x) {
                    month = {
                        January: 0,
                        February: 1,
                        March: 2,
                        April: 3,
                        May: 4,
                        June: 5,
                        July: 6,
                        August: 7,
                        September: 8,
                        October: 9,
                        November: 10,
                        December: 11
                    }[x];
                });
                return "([A-Za-z]+)";

              case "%e":
              case "%d":
                fields.push(function(x) {
                    date = x;
                });
                return "([0-9]+)";

              case "%I":
              case "%H":
                fields.push(function(x) {
                    hour = x;
                });
                return "([0-9]+)";

              case "%m":
                fields.push(function(x) {
                    month = x - 1;
                });
                return "([0-9]+)";

              case "%M":
                fields.push(function(x) {
                    minute = x;
                });
                return "([0-9]+)";

              case "%p":
                fields.push(function(x) {
                    12 == hour ? "am" == x && (hour = 0) : "pm" == x && (hour = Number(hour) + 12);
                });
                return "(am|pm)";

              case "%S":
                fields.push(function(x) {
                    second = x;
                });
                return "([0-9]+)";

              case "%y":
                fields.push(function(x) {
                    x = Number(x);
                    year = x + (x >= 0 && 69 > x ? 2e3 : x >= 69 && 100 > x ? 1900 : 0);
                });
                return "([0-9]+)";

              case "%Y":
                fields.push(function(x) {
                    year = x;
                });
                return "([0-9]+)";

              case "%%":
                fields.push(function() {});
                return "%";
            }
            return s;
        }), match = s.match(re);
        match && match.forEach(function(m, i) {
            fields[i](m);
        });
        return new Date(year, month, date, hour, minute, second);
    };
    return format;
};

pv.Format.time = function(type) {
    function format(t) {
        t = Number(t);
        switch (type) {
          case "short":
            return t >= 31536e6 ? (t / 31536e6).toFixed(1) + " years" : t >= 6048e5 ? (t / 6048e5).toFixed(1) + " weeks" : t >= 864e5 ? (t / 864e5).toFixed(1) + " days" : t >= 36e5 ? (t / 36e5).toFixed(1) + " hours" : t >= 6e4 ? (t / 6e4).toFixed(1) + " minutes" : (t / 1e3).toFixed(1) + " seconds";

          case "long":
            var a = [], s = t % 6e4 / 1e3 >> 0, m = t % 36e5 / 6e4 >> 0;
            a.push(pad("0", 2, s));
            if (t >= 36e5) {
                var h = t % 864e5 / 36e5 >> 0;
                a.push(pad("0", 2, m));
                if (t >= 864e5) {
                    a.push(pad("0", 2, h));
                    a.push(Math.floor(t / 864e5).toFixed());
                } else a.push(h.toFixed());
            } else a.push(m.toFixed());
            return a.reverse().join(":");
        }
    }
    var pad = pv.Format.pad;
    format.format = format;
    format.parse = function(s) {
        switch (type) {
          case "short":
            for (var a, re = /([0-9,.]+)\s*([a-z]+)/g, t = 0; a = re.exec(s); ) {
                var f = parseFloat(a[0].replace(",", "")), u = 0;
                switch (a[2].toLowerCase()) {
                  case "year":
                  case "years":
                    u = 31536e6;
                    break;

                  case "week":
                  case "weeks":
                    u = 6048e5;
                    break;

                  case "day":
                  case "days":
                    u = 864e5;
                    break;

                  case "hour":
                  case "hours":
                    u = 36e5;
                    break;

                  case "minute":
                  case "minutes":
                    u = 6e4;
                    break;

                  case "second":
                  case "seconds":
                    u = 1e3;
                }
                t += f * u;
            }
            return t;

          case "long":
            var a = s.replace(",", "").split(":").reverse(), t = 0;
            a.length && (t += 1e3 * parseFloat(a[0]));
            a.length > 1 && (t += 6e4 * parseFloat(a[1]));
            a.length > 2 && (t += 36e5 * parseFloat(a[2]));
            a.length > 3 && (t += 864e5 * parseFloat(a[3]));
            return t;
        }
    };
    return format;
};

pv.Format.number = function() {
    function format(x) {
        1/0 > maxf && (x = Math.round(x * maxk) / maxk);
        var s = String(Math.abs(x)).split("."), i = s[0];
        i.length > maxi && (i = i.substring(i.length - maxi));
        padg && i.length < mini && (i = new Array(mini - i.length + 1).join(padi) + i);
        i.length > 3 && (i = i.replace(/\B(?=(?:\d{3})+(?!\d))/g, group));
        !padg && i.length < mins && (i = new Array(mins - i.length + 1).join(padi) + i);
        s[0] = 0 > x ? np + i + ns : i;
        var f = s[1] || "";
        f.length > maxf && (f = s[1] = f.substr(0, maxf));
        f.length < minf && (s[1] = f + new Array(minf - f.length + 1).join(padf));
        return s.join(decimal);
    }
    var mini = 0, maxi = 1/0, mins = 0, minf = 0, maxf = 0, maxk = 1, padi = "0", padf = "0", padg = !0, decimal = ".", group = ",", np = "−", ns = "";
    format.format = format;
    format.parse = function(x) {
        var re = pv.Format.re, s = String(x).split(decimal);
        1 == s.length && (s[1] = "");
        s[0].replace(new RegExp("^(" + re(padi) + ")*"), "");
        s[1].replace(new RegExp("(" + re(padf) + ")*$"), "");
        var i = s[0].replace(new RegExp(re(group), "g"), "");
        i.length > maxi && (i = i.substring(i.length - maxi));
        var f = s[1] ? Number("0." + s[1]) : 0;
        1/0 > maxf && (f = Math.round(f * maxk) / maxk);
        return Math.round(i) + f;
    };
    format.integerDigits = function(min, max) {
        if (arguments.length) {
            mini = Number(min);
            maxi = arguments.length > 1 ? Number(max) : mini;
            mins = mini + Math.floor(mini / 3) * group.length;
            return this;
        }
        return [ mini, maxi ];
    };
    format.fractionDigits = function(min, max) {
        if (arguments.length) {
            minf = Number(min);
            maxf = arguments.length > 1 ? Number(max) : minf;
            maxk = Math.pow(10, maxf);
            return this;
        }
        return [ minf, maxf ];
    };
    format.integerPad = function(x) {
        if (arguments.length) {
            padi = String(x);
            padg = /\d/.test(padi);
            return this;
        }
        return padi;
    };
    format.fractionPad = function(x) {
        if (arguments.length) {
            padf = String(x);
            return this;
        }
        return padf;
    };
    format.decimal = function(x) {
        if (arguments.length) {
            decimal = String(x);
            return this;
        }
        return decimal;
    };
    format.group = function(x) {
        if (arguments.length) {
            group = x ? String(x) : "";
            mins = mini + Math.floor(mini / 3) * group.length;
            return this;
        }
        return group;
    };
    format.negativeAffix = function(x, y) {
        if (arguments.length) {
            np = String(x || "");
            ns = String(y || "");
            return this;
        }
        return [ np, ns ];
    };
    return format;
};

!function() {
    var _cache;
    pv.Text = {};
    pv.Text.createCache = function() {
        return new FontSizeCache();
    };
    pv.Text.usingCache = function(cache, fun, ctx) {
        if (!(cache instanceof FontSizeCache)) throw new Error("Not a valid cache.");
        var prevCache = _cache;
        _cache = cache;
        try {
            return fun.call(ctx);
        } finally {
            _cache = prevCache;
        }
    };
    pv.Text.measure = function(text, font) {
        text = null == text ? "" : String(text);
        var bbox = _cache && _cache.get(font, text);
        if (!bbox) {
            bbox = text ? this.measureCore(text, font) : {
                width: 0,
                height: 0
            };
            _cache && _cache.put(font, text, bbox);
        }
        return bbox;
    };
    pv.Text.measureWidth = function(text, font) {
        return pv.Text.measure(text, font).width;
    };
    pv.Text.fontHeight = function(font) {
        return pv.Text.measure("M", font).height;
    };
    pv.Text.measureCore = function() {
        function getTextSizeElement() {
            return _svgText || (_svgText = createTextSizeElement());
        }
        function createTextSizeElement() {
            var div = document.createElement("div");
            div.id = "pvSVGText_" + new Date().getTime();
            var style = div.style;
            style.position = "absolute";
            style.visibility = "hidden";
            style.width = 0;
            style.height = 0;
            style.left = 0;
            style.top = 0;
            style.lineHeight = 1;
            style.textTransform = "none";
            style.letterSpacing = "normal";
            style.whiteSpace = "nowrap";
            var svgElem = pv.SvgScene.create("svg");
            svgElem.setAttribute("font-size", "10px");
            svgElem.setAttribute("font-family", "sans-serif");
            div.appendChild(svgElem);
            var svgText = pv.SvgScene.create("text");
            svgElem.appendChild(svgText);
            svgText.appendChild(document.createTextNode(""));
            document.body.appendChild(div);
            return svgText;
        }
        var _svgText, _lastFont = "10px sans-serif";
        return function(text, font) {
            font || (font = null);
            var svgText = getTextSizeElement();
            if (_lastFont !== font) {
                _lastFont = font;
                pv.SvgScene.setStyle(svgText, {
                    font: font
                });
            }
            svgText.firstChild.nodeValue = String(text);
            var box;
            try {
                box = svgText.getBBox();
            } catch (ex) {
                "function" == typeof console.error && console.error("GetBBox failed: ", ex);
                throw ex;
            }
            return {
                width: box.width,
                height: box.height
            };
        };
    }();
    var FontSizeCache = function() {
        this._fontsCache = {};
    }, hasOwnProp = Object.prototype.hasOwnProperty;
    FontSizeCache.prototype._getFont = function(font) {
        font = font || "";
        return hasOwnProp.call(this._fontsCache, font) ? this._fontsCache[font] : this._fontsCache[font] = {};
    };
    FontSizeCache.prototype.get = function(font, text) {
        text = text || "";
        var fontCache = this._getFont(font);
        return hasOwnProp.call(fontCache, text) ? fontCache[text] : null;
    };
    FontSizeCache.prototype.put = function(font, text, size) {
        return this._getFont(font)[text || ""] = size;
    };
}();

pv.map = function(array, f) {
    var o = {};
    return f ? array.map(function(d, i) {
        o.index = i;
        return f.call(o, d);
    }) : array.slice();
};

pv.repeat = function(array, n) {
    1 == arguments.length && (n = 2);
    return pv.blend(pv.range(n).map(function() {
        return array;
    }));
};

pv.array = function(len, dv) {
    var a = len >= 0 ? new Array(len) : [];
    if (void 0 !== dv) for (var i = 0; len > i; i++) a[i] = dv;
    return a;
};

pv.cross = function(a, b) {
    for (var array = [], i = 0, n = a.length, m = b.length; n > i; i++) for (var j = 0, x = a[i]; m > j; j++) array.push([ x, b[j] ]);
    return array;
};

pv.blend = function(arrays) {
    return Array.prototype.concat.apply([], arrays);
};

pv.transpose = function(arrays) {
    var n = arrays.length, m = pv.max(arrays, function(d) {
        return d.length;
    });
    if (m > n) {
        arrays.length = m;
        for (var i = n; m > i; i++) arrays[i] = new Array(n);
        for (var i = 0; n > i; i++) for (var j = i + 1; m > j; j++) {
            var t = arrays[i][j];
            arrays[i][j] = arrays[j][i];
            arrays[j][i] = t;
        }
    } else {
        for (var i = 0; m > i; i++) arrays[i].length = n;
        for (var i = 0; n > i; i++) for (var j = 0; i > j; j++) {
            var t = arrays[i][j];
            arrays[i][j] = arrays[j][i];
            arrays[j][i] = t;
        }
    }
    arrays.length = m;
    for (var i = 0; m > i; i++) arrays[i].length = n;
    return arrays;
};

pv.normalize = function(array, f) {
    for (var norm = pv.map(array, f), sum = pv.sum(norm), i = 0; i < norm.length; i++) norm[i] /= sum;
    return norm;
};

pv.permute = function(array, indexes, f) {
    f || (f = pv.identity);
    var p = new Array(indexes.length), o = {};
    indexes.forEach(function(j, i) {
        o.index = j;
        p[i] = f.call(o, array[j]);
    });
    return p;
};

pv.numerate = function(keys, f) {
    f || (f = pv.identity);
    var map = {}, o = {};
    keys.forEach(function(x, i) {
        o.index = i;
        map[f.call(o, x)] = i;
    });
    return map;
};

pv.uniq = function(array, f) {
    f || (f = pv.identity);
    var y, map = {}, keys = [], o = {};
    array.forEach(function(x, i) {
        o.index = i;
        y = f.call(o, x);
        y in map || (map[y] = keys.push(y));
    });
    return keys;
};

pv.naturalOrder = function(a, b) {
    return b > a ? -1 : a > b ? 1 : 0;
};

pv.reverseOrder = function(b, a) {
    return b > a ? -1 : a > b ? 1 : 0;
};

pv.search = function(array, value, f) {
    f || (f = pv.identity);
    for (var low = 0, high = array.length - 1; high >= low; ) {
        var mid = low + high >> 1, midValue = f(array[mid]);
        if (value > midValue) low = mid + 1; else {
            if (!(midValue > value)) return mid;
            high = mid - 1;
        }
    }
    return -low - 1;
};

pv.search.index = function(array, value, f) {
    var i = pv.search(array, value, f);
    return 0 > i ? -i - 1 : i;
};

pv.range = function(start, stop, step) {
    if (1 == arguments.length) {
        stop = start;
        start = 0;
    }
    void 0 == step && (step = 1);
    if ((stop - start) / step == 1/0) throw new Error("range must be finite");
    var j, array = [], i = 0;
    stop -= 1e-10 * (stop - start);
    if (0 > step) for (;(j = start + step * i++) > stop; ) array.push(j); else for (;(j = start + step * i++) < stop; ) array.push(j);
    return array;
};

pv.random = function(start, stop, step) {
    if (1 == arguments.length) {
        stop = start;
        start = 0;
    }
    void 0 == step && (step = 1);
    return step ? Math.floor(Math.random() * (stop - start) / step) * step + start : Math.random() * (stop - start) + start;
};

pv.sum = function(array, f) {
    var o = {};
    return array.reduce(f ? function(p, d, i) {
        o.index = i;
        return p + f.call(o, d);
    } : function(p, d) {
        return p + d;
    }, 0);
};

pv.max = function(array, f) {
    return f == pv.index ? array.length - 1 : Math.max.apply(null, f ? pv.map(array, f) : array);
};

pv.max.index = function(array, f) {
    if (!array.length) return -1;
    if (f == pv.index) return array.length - 1;
    f || (f = pv.identity);
    for (var maxi = 0, maxx = -1/0, o = {}, i = 0; i < array.length; i++) {
        o.index = i;
        var x = f.call(o, array[i]);
        if (x > maxx) {
            maxx = x;
            maxi = i;
        }
    }
    return maxi;
};

pv.min = function(array, f) {
    return f == pv.index ? 0 : Math.min.apply(null, f ? pv.map(array, f) : array);
};

pv.min.index = function(array, f) {
    if (!array.length) return -1;
    if (f == pv.index) return 0;
    f || (f = pv.identity);
    for (var mini = 0, minx = 1/0, o = {}, i = 0; i < array.length; i++) {
        o.index = i;
        var x = f.call(o, array[i]);
        if (minx > x) {
            minx = x;
            mini = i;
        }
    }
    return mini;
};

pv.mean = function(array, f) {
    return pv.sum(array, f) / array.length;
};

pv.median = function(array, f) {
    if (f == pv.index) return (array.length - 1) / 2;
    array = pv.map(array, f).sort(pv.naturalOrder);
    if (array.length % 2) return array[Math.floor(array.length / 2)];
    var i = array.length / 2;
    return (array[i - 1] + array[i]) / 2;
};

pv.variance = function(array, f) {
    if (array.length < 1) return 0/0;
    if (1 == array.length) return 0;
    var mean = pv.mean(array, f), sum = 0, o = {};
    f || (f = pv.identity);
    for (var i = 0; i < array.length; i++) {
        o.index = i;
        var d = f.call(o, array[i]) - mean;
        sum += d * d;
    }
    return sum;
};

pv.deviation = function(array, f) {
    return Math.sqrt(pv.variance(array, f) / (array.length - 1));
};

pv.log = function(x, b) {
    return Math.log(x) / Math.log(b);
};

pv.logSymmetric = function(x, b) {
    return 0 == x ? 0 : 0 > x ? -pv.log(-x, b) : pv.log(x, b);
};

pv.logAdjusted = function(x, b) {
    if (!isFinite(x)) return x;
    var negative = 0 > x;
    b > x && (x += (b - x) / b);
    return negative ? -pv.log(x, b) : pv.log(x, b);
};

pv.logFloor = function(x, b) {
    return x > 0 ? Math.pow(b, Math.floor(pv.log(x, b))) : -Math.pow(b, -Math.floor(-pv.log(-x, b)));
};

pv.logCeil = function(x, b) {
    return x > 0 ? Math.pow(b, Math.ceil(pv.log(x, b))) : -Math.pow(b, -Math.ceil(-pv.log(-x, b)));
};

!function() {
    var _radians = Math.PI / 180, _degrees = 180 / Math.PI;
    pv.radians = function(degrees) {
        return _radians * degrees;
    };
    pv.degrees = function(radians) {
        return _degrees * radians;
    };
}();

pv.keys = function(map) {
    var array = [];
    for (var key in map) array.push(key);
    return array;
};

pv.entries = function(map) {
    var array = [];
    for (var key in map) array.push({
        key: key,
        value: map[key]
    });
    return array;
};

pv.values = function(map) {
    var array = [];
    for (var key in map) array.push(map[key]);
    return array;
};

pv.dict = function(keys, f) {
    for (var m = {}, o = {}, i = 0; i < keys.length; i++) if (i in keys) {
        var k = keys[i];
        o.index = i;
        m[k] = f.call(o, k);
    }
    return m;
};

pv.hasOwnProp = Object.prototype.hasOwnProperty;

pv.copyOwn = function(a, b) {
    if (b) {
        var hop = pv.hasOwnProp;
        for (var p in b) hop.call(b, p) && (a[p] = b[p]);
    }
    return a;
};

pv.dom = function(map) {
    return new pv.Dom(map);
};

pv.Dom = function(map) {
    this.$map = map;
};

pv.Dom.prototype.$leaf = function(n) {
    return "object" != typeof n;
};

pv.Dom.prototype.leaf = function(f) {
    if (arguments.length) {
        this.$leaf = f;
        return this;
    }
    return this.$leaf;
};

pv.Dom.prototype.root = function(nodeName) {
    function recurse(map) {
        var n = new pv.Dom.Node();
        for (var k in map) {
            var v = map[k];
            n.appendChild(leaf(v) ? new pv.Dom.Node(v) : recurse(v)).nodeName = k;
        }
        return n;
    }
    var leaf = this.$leaf, root = recurse(this.$map);
    root.nodeName = nodeName;
    return root;
};

pv.Dom.prototype.nodes = function() {
    return this.root().nodes();
};

pv.Dom.Node = function(value) {
    void 0 !== value && (this.nodeValue = value);
};

pv.Dom.Node.prototype.nodeValue = void 0;

pv.Dom.Node.prototype.childNodes = [];

pv.Dom.Node.prototype.parentNode = null;

pv.Dom.Node.prototype.firstChild = null;

pv.Dom.Node.prototype.lastChild = null;

pv.Dom.Node.prototype.previousSibling = null;

pv.Dom.Node.prototype.nextSibling = null;

pv.Dom.Node.prototype._firstDirtyChildIndex = 1/0;

pv.Dom.Node.prototype._childIndex = -1;

pv.Dom.Node.prototype.findChildIndex = function(n) {
    if (!n) throw new Error("Argument 'n' required");
    if (n.parentNode === this) {
        var i = n.childIndex(!0);
        if (i > -1) return i;
    }
    throw new Error("child not found");
};

pv.Dom.Node.prototype._childRemoved = function() {};

pv.Dom.Node.prototype._childAdded = function() {};

pv.Dom.Node.prototype.removeChild = function(n) {
    var i = this.findChildIndex(n);
    return this.removeAt(i);
};

pv.Dom.Node.prototype.appendChild = function(n) {
    var pn = n.parentNode;
    pn && pn.removeChild(n);
    var lc = this.lastChild;
    n.parentNode = this;
    n.previousSibling = lc;
    if (lc) {
        lc.nextSibling = n;
        n._childIndex = lc._childIndex + 1;
    } else {
        this.firstChild = n;
        n._childIndex = 0;
    }
    this.lastChild = n;
    var L = pv.lazyArrayOwn(this, "childNodes").push(n);
    this._childAdded(n, L - 1);
    return n;
};

pv.Dom.Node.prototype.insertBefore = function(n, r) {
    if (!r) return this.appendChild(n);
    var i = this.findChildIndex(r);
    return this.insertAt(n, i);
};

pv.Dom.Node.prototype.insertAt = function(n, i) {
    if (null == i) return this.appendChild(n);
    var ns = this.childNodes, L = ns.length;
    if (i === L) return this.appendChild(n);
    if (0 > i || i > L) throw new Error("Index out of range.");
    var pn = n.parentNode;
    pn && pn.removeChild(n);
    var ni = i + 1;
    ni < this._firstDirtyChildIndex && (this._firstDirtyChildIndex = ni);
    var r = ns[i];
    n.parentNode = this;
    n.nextSibling = r;
    n._childIndex = i;
    var psib = n.previousSibling = r.previousSibling;
    r.previousSibling = n;
    if (psib) psib.nextSibling = n; else {
        r === this.lastChild && (this.lastChild = n);
        this.firstChild = n;
    }
    ns.splice(i, 0, n);
    this._childAdded(n, i);
    return n;
};

pv.Dom.Node.prototype.removeAt = function(i) {
    var ns = this.childNodes, L = ns.length;
    if (!(0 > i || i >= L)) {
        var n = ns[i];
        ns.splice(i, 1);
        L - 1 > i && i < this._firstDirtyChildIndex && (this._firstDirtyChildIndex = i);
        var psib = n.previousSibling, nsib = n.nextSibling;
        psib ? psib.nextSibling = nsib : this.firstChild = nsib;
        nsib ? nsib.previousSibling = psib : this.lastChild = psib;
        n.nextSibling = n.previousSibling = n.parentNode = null;
        this._childRemoved(n, i);
        return n;
    }
};

pv.Dom.Node.prototype.replaceChild = function(n, r) {
    var i = this.findChildIndex(r), pn = n.parentNode;
    pn && pn.removeChild(n);
    n.parentNode = this;
    n.nextSibling = r.nextSibling;
    n._childIndex = r._childIndex;
    var psib = n.previousSibling = r.previousSibling;
    psib ? psib.nextSibling = n : this.firstChild = n;
    var nsib = r.nextSibling;
    nsib ? nsib.previousSibling = n : this.lastChild = n;
    this.childNodes[i] = n;
    this._childRemoved(r, i);
    this._childAdded(n, i);
    return r;
};

pv.Dom.Node.prototype.childIndex = function(noRebuild) {
    var p = this.parentNode;
    if (p) {
        var di = p._firstDirtyChildIndex;
        if (1/0 > di) {
            var ns = p.childNodes;
            if (!noRebuild) return ns.indexOf(this);
            for (var L = ns.length; L > di; ) {
                ns[di]._childIndex = di;
                di++;
            }
            p._firstDirtyChildIndex = 1/0;
        }
        return this._childIndex;
    }
    return -1;
};

pv.Dom.Node.prototype.visitBefore = function(f) {
    function visit(n, d) {
        f(n, d);
        for (var c = n.firstChild; c; c = c.nextSibling) visit(c, d + 1);
    }
    visit(this, 0);
};

pv.Dom.Node.prototype.visitAfter = function(f) {
    function visit(n, d) {
        for (var c = n.firstChild; c; c = c.nextSibling) visit(c, d + 1);
        f(n, d);
    }
    visit(this, 0);
};

pv.Dom.Node.prototype.sort = function(f) {
    if (this.firstChild) {
        this._firstDirtyChildIndex = 1/0;
        var cs = this.childNodes;
        cs.sort(f);
        var c, p = this.firstChild = cs[0];
        delete p.previousSibling;
        p._childIndex = 0;
        for (var i = 1, L = cs.length; L > i; i++) {
            p.sort(f);
            c = cs[i];
            c._childIndex = i;
            c.previousSibling = p;
            p = p.nextSibling = c;
        }
        this.lastChild = p;
        delete p.nextSibling;
        p.sort(f);
    }
    return this;
};

pv.Dom.Node.prototype.reverse = function() {
    var childNodes = [];
    this.visitAfter(function(n) {
        this._firstDirtyChildIndex = 1/0;
        for (var c; c = n.lastChild; ) childNodes.push(n.removeChild(c));
        if (childNodes.length) for (;c = childNodes.pop(); ) n.insertBefore(c, n.firstChild);
    });
    return this;
};

pv.Dom.Node.prototype.nodes = function() {
    var array = [];
    this.visitBefore(function(n) {
        array.push(n);
    });
    return array;
};

pv.Dom.Node.prototype.toggle = function(recursive) {
    if (recursive) return this.toggled ? this.visitBefore(function(n) {
        n.toggled && n.toggle();
    }) : this.visitAfter(function(n) {
        n.toggled || n.toggle();
    });
    var c, n = this;
    if (n.toggled) {
        for (;c = n.toggled.pop(); ) n.appendChild(c);
        delete n.toggled;
    } else if (c = n.lastChild) {
        n.toggled = [];
        do n.toggled.push(n.removeChild(c)); while (c = n.lastChild);
    }
};

pv.nodes = function(values) {
    for (var root = new pv.Dom.Node(), i = 0, V = values.length; V > i; i++) root.appendChild(new pv.Dom.Node(values[i]));
    return root.nodes();
};

pv.tree = function(array) {
    return new pv.Tree(array);
};

pv.Tree = function(array) {
    this.array = array;
};

pv.Tree.prototype.keys = function(k) {
    this.k = k;
    return this;
};

pv.Tree.prototype.value = function(v) {
    this.v = v;
    return this;
};

pv.Tree.prototype.map = function() {
    for (var map = {}, o = {}, i = 0; i < this.array.length; i++) {
        o.index = i;
        for (var value = this.array[i], keys = this.k.call(o, value), node = map, j = 0; j < keys.length - 1; j++) node = node[keys[j]] || (node[keys[j]] = {});
        node[keys[j]] = this.v ? this.v.call(o, value) : value;
    }
    return map;
};

pv.nest = function(array) {
    return new pv.Nest(array);
};

pv.Nest = function(array) {
    this.array = array;
    this.keys = [];
};

pv.Nest.prototype.key = function(key) {
    this.keys.push(key);
    return this;
};

pv.Nest.prototype.sortKeys = function(order) {
    this.keys[this.keys.length - 1].order = order || pv.naturalOrder;
    return this;
};

pv.Nest.prototype.sortValues = function(order) {
    this.order = order || pv.naturalOrder;
    return this;
};

pv.Nest.prototype.map = function() {
    for (var i, map = {}, values = [], j = 0; j < this.array.length; j++) {
        var x = this.array[j], m = map;
        for (i = 0; i < this.keys.length - 1; i++) {
            var k = this.keys[i](x);
            m[k] || (m[k] = {});
            m = m[k];
        }
        k = this.keys[i](x);
        if (!m[k]) {
            var a = [];
            values.push(a);
            m[k] = a;
        }
        m[k].push(x);
    }
    if (this.order) for (var i = 0; i < values.length; i++) values[i].sort(this.order);
    return map;
};

pv.Nest.prototype.entries = function() {
    function entries(map) {
        var array = [];
        for (var k in map) {
            var v = map[k];
            array.push({
                key: k,
                values: v instanceof Array ? v : entries(v)
            });
        }
        return array;
    }
    function sort(array, i) {
        var o = this.keys[i].order;
        o && array.sort(function(a, b) {
            return o(a.key, b.key);
        });
        if (++i < this.keys.length) for (var j = 0; j < array.length; j++) sort.call(this, array[j].values, i);
        return array;
    }
    return sort.call(this, entries(this.map()), 0);
};

pv.Nest.prototype.rollup = function(f) {
    function rollup(map) {
        for (var key in map) {
            var value = map[key];
            value instanceof Array ? map[key] = f(value) : rollup(value);
        }
        return map;
    }
    return rollup(this.map());
};

pv.flatten = function(map) {
    return new pv.Flatten(map);
};

pv.Flatten = function(map) {
    this.map = map;
    this.keys = [];
};

pv.Flatten.prototype.key = function(key, f) {
    this.keys.push({
        name: key,
        value: f
    });
    delete this.$leaf;
    return this;
};

pv.Flatten.prototype.leaf = function(f) {
    this.keys.length = 0;
    this.$leaf = f;
    return this;
};

pv.Flatten.prototype.array = function() {
    function recurse(value, i) {
        if (leaf(value)) entries.push({
            keys: stack.slice(),
            value: value
        }); else for (var key in value) {
            stack.push(key);
            recurse(value[key], i + 1);
            stack.pop();
        }
    }
    function visit(value, i) {
        if (i < keys.length - 1) for (var key in value) {
            stack.push(key);
            visit(value[key], i + 1);
            stack.pop();
        } else entries.push(stack.concat(value));
    }
    var entries = [], stack = [], keys = this.keys, leaf = this.$leaf;
    if (leaf) {
        recurse(this.map, 0);
        return entries;
    }
    visit(this.map, 0);
    return entries.map(function(stack) {
        for (var m = {}, i = 0; i < keys.length; i++) {
            var k = keys[i], v = stack[i];
            m[k.name] = k.value ? k.value.call(null, v) : v;
        }
        return m;
    });
};

pv.Transform = function() {};

pv.Transform.prototype = {
    k: 1,
    x: 0,
    y: 0
};

pv.Transform.identity = new pv.Transform();

pv.Transform.prototype.translate = function(x, y) {
    var v = new pv.Transform();
    v.k = this.k;
    v.x = this.k * x + this.x;
    v.y = this.k * y + this.y;
    return v;
};

pv.Transform.prototype.scale = function(k) {
    var v = new pv.Transform();
    v.k = this.k * k;
    v.x = this.x;
    v.y = this.y;
    return v;
};

pv.Transform.prototype.invert = function() {
    var v = new pv.Transform(), k = 1 / this.k;
    v.k = k;
    v.x = -this.x * k;
    v.y = -this.y * k;
    return v;
};

pv.Transform.prototype.times = function(m) {
    var v = new pv.Transform();
    v.k = this.k * m.k;
    v.x = this.k * m.x + this.x;
    v.y = this.k * m.y + this.y;
    return v;
};

pv.Scale = function() {};

pv.Scale.interpolator = function(start, end) {
    if ("number" == typeof start) return function(t) {
        return t * (end - start) + start;
    };
    var startGradient = start.type && "solid" !== start.type, endGradient = end.type && "solid" !== end.type;
    if (startGradient || endGradient) {
        start = startGradient ? start : pv.color(start).rgb();
        end = endGradient ? end : pv.color(end).rgb();
        return function(t) {
            return .5 > t ? start : end;
        };
    }
    start = pv.color(start).rgb();
    end = pv.color(end).rgb();
    return function(t) {
        var a = start.a * (1 - t) + end.a * t;
        1e-5 > a && (a = 0);
        return 0 == start.a ? pv.rgb(end.r, end.g, end.b, a) : 0 == end.a ? pv.rgb(start.r, start.g, start.b, a) : pv.rgb(Math.round(start.r * (1 - t) + end.r * t), Math.round(start.g * (1 - t) + end.g * t), Math.round(start.b * (1 - t) + end.b * t), a);
    };
};

pv.Scale.common = {
    by: function(f) {
        function by() {
            return scale(f.apply(this, arguments));
        }
        var scale = this;
        for (var method in scale) by[method] = scale[method];
        return by;
    },
    by1: function(f) {
        function by1(x) {
            return scale(f.call(this, x));
        }
        var scale = this;
        for (var method in scale) by1[method] = scale[method];
        return by1;
    },
    transform: function(t) {
        function transfScale() {
            return t.call(this, scale.apply(scale, arguments));
        }
        var scale = this;
        for (var method in scale) transfScale[method] = scale[method];
        return transfScale;
    }
};

pv.Scale.quantitative = function() {
    function newDate(x) {
        return new Date(x);
    }
    function scale(x) {
        var j = pv.search(d, x);
        0 > j && (j = -j - 2);
        j = Math.max(0, Math.min(i.length - 1, j));
        return i[j]((f(x) - l[j]) / (l[j + 1] - l[j]));
    }
    var dateTickFormat, dateTickPrecision, usedDateTickPrecision, usedNumberExponent, d = [ 0, 1 ], l = [ 0, 1 ], r = [ 0, 1 ], i = [ pv.identity ], type = Number, n = !1, f = pv.identity, g = pv.identity, tickFormat = String, tickFormatter = null;
    scale.transform = function(forward, inverse) {
        f = function(x) {
            return n ? -forward(-x) : forward(x);
        };
        g = function(y) {
            return n ? -inverse(-y) : inverse(y);
        };
        l = d.map(f);
        return this;
    };
    scale.domain = function(array, min, max) {
        if (arguments.length) {
            var o;
            if (array instanceof Array) {
                arguments.length < 2 && (min = pv.identity);
                arguments.length < 3 && (max = min);
                o = array.length && min(array[0]);
                d = array.length ? [ pv.min(array, min), pv.max(array, max) ] : [];
            } else {
                o = array;
                d = Array.prototype.slice.call(arguments).map(Number);
            }
            d.length ? 1 == d.length && (d = [ d[0], d[0] ]) : d = [ -1/0, 1/0 ];
            n = (d[0] || d[d.length - 1]) < 0;
            l = d.map(f);
            type = o instanceof Date ? newDate : Number;
            return this;
        }
        return d.map(type);
    };
    scale.range = function() {
        if (arguments.length) {
            r = Array.prototype.slice.call(arguments);
            r.length ? 1 == r.length && (r = [ r[0], r[0] ]) : r = [ -1/0, 1/0 ];
            i = [];
            for (var j = 0; j < r.length - 1; j++) i.push(pv.Scale.interpolator(r[j], r[j + 1]));
            return this;
        }
        return r;
    };
    scale.invert = function(y) {
        var j = pv.search(r, y);
        0 > j && (j = -j - 2);
        j = Math.max(0, Math.min(i.length - 1, j));
        return type(g(l[j] + (y - r[j]) / (r[j + 1] - r[j]) * (l[j + 1] - l[j])));
    };
    scale.ticks = function(m, options) {
        function floor(d, p) {
            switch (p) {
              case 31536e6:
                d.setMonth(0);

              case 2592e6:
                d.setDate(1);

              case 6048e5:
                6048e5 == p && d.setDate(d.getDate() - d.getDay());

              case 864e5:
                d.setHours(0);

              case 36e5:
                d.setMinutes(0);

              case 6e4:
                d.setSeconds(0);

              case 1e3:
                d.setMilliseconds(0);
            }
        }
        var start = d[0], end = d[d.length - 1], reverse = start > end, min = reverse ? end : start, max = reverse ? start : end, span = max - min;
        if (!span || !isFinite(span)) {
            type == newDate && (tickFormat = pv.Format.date("%x"));
            return [ type(min) ];
        }
        var roundInside = pv.get(options, "roundInside", !0);
        if (type == newDate) {
            var precision, format, increment, nn = null == m ? 5 : m, step = 1;
            if (span >= 31536e6 * nn) {
                precision = 31536e6;
                format = "%Y";
                increment = function(d) {
                    d.setFullYear(d.getFullYear() + step);
                };
            } else if (span >= 2592e6 * nn) {
                precision = 2592e6;
                format = "%m/%Y";
                increment = function(d) {
                    d.setMonth(d.getMonth() + step);
                };
            } else if (span >= 6048e5 * nn) {
                precision = 6048e5;
                format = "%m/%d";
                increment = function(d) {
                    d.setDate(d.getDate() + 7 * step);
                };
            } else if (span >= 864e5 * nn) {
                precision = 864e5;
                format = "%m/%d";
                increment = function(d) {
                    d.setDate(d.getDate() + step);
                };
            } else if (span >= 36e5 * nn) {
                precision = 36e5;
                format = "%I:%M %p";
                increment = function(d) {
                    d.setHours(d.getHours() + step);
                };
            } else if (span >= 6e4 * nn) {
                precision = 6e4;
                format = "%I:%M %p";
                increment = function(d) {
                    d.setMinutes(d.getMinutes() + step);
                };
            } else if (span >= 1e3 * nn) {
                precision = 1e3;
                format = "%I:%M:%S";
                increment = function(d) {
                    d.setSeconds(d.getSeconds() + step);
                };
            } else {
                precision = 1;
                format = "%S.%Qs";
                increment = function(d) {
                    d.setTime(d.getTime() + step);
                };
            }
            precision = dateTickPrecision ? dateTickPrecision : precision;
            format = dateTickFormat ? dateTickFormat : format;
            usedDateTickPrecision = precision;
            tickFormat = pv.Format.date(format);
            var date = new Date(min), dates = [];
            floor(date, precision);
            var n = span / precision;
            if (n > 10) switch (precision) {
              case 36e5:
                step = n > 20 ? 6 : 3;
                date.setHours(Math.floor(date.getHours() / step) * step);
                break;

              case 2592e6:
                step = n > 24 ? 3 : n > 12 ? 2 : 1;
                date.setMonth(Math.floor(date.getMonth() / step) * step);
                break;

              case 6048e5:
                step = n > 15 ? 3 : n > 10 ? 2 : 1;
                date.setDate(1 + 7 * Math.floor(date.getDate() / (7 * step)) * step);
                break;

              case 864e5:
                step = n >= 30 ? 5 : n >= 15 ? 3 : 2;
                date.setDate(1 + Math.floor(date.getDate() / step) * step);
                break;

              case 6e4:
                step = n > 30 ? 15 : n > 15 ? 10 : 5;
                date.setMinutes(Math.floor(date.getMinutes() / step) * step);
                break;

              case 1e3:
                step = n > 90 ? 15 : n > 60 ? 10 : 5;
                date.setSeconds(Math.floor(date.getSeconds() / step) * step);
                break;

              case 1:
                step = n > 1e3 ? 250 : n > 200 ? 100 : n > 100 ? 50 : n > 50 ? 25 : 5;
                date.setMilliseconds(Math.floor(date.getMilliseconds() / step) * step);
                break;

              default:
                step = pv.logCeil(n / 15, 10);
                2 > n / step ? step /= 5 : 5 > n / step && (step /= 2);
                date.setFullYear(Math.floor(date.getFullYear() / step) * step);
            }
            if (dateTickPrecision) {
                step = 1;
                increment = function(d) {
                    d.setSeconds(d.getSeconds() + step * dateTickPrecision / 1e3);
                };
            }
            if (roundInside) for (;;) {
                increment(date);
                if (date > max) break;
                dates.push(new Date(date));
            } else {
                max = new Date(max);
                increment(max);
                do {
                    dates.push(new Date(date));
                    increment(date);
                } while (max >= date);
            }
            return reverse ? dates.reverse() : dates;
        }
        null == m && (m = 10);
        var exponentMin = pv.get(options, "numberExponentMin", -1/0), exponentMax = pv.get(options, "numberExponentMax", +1/0), exponent = Math.floor(pv.log(span / m, 10)), overflow = !1;
        if (exponent > exponentMax) {
            exponent = exponentMax;
            overflow = !0;
        } else if (exponentMin > exponent) {
            exponent = exponentMin;
            overflow = !0;
        }
        step = Math.pow(10, exponent);
        var mObtained = span / step, err = m / mObtained;
        if (.15 >= err && exponentMax - 1 > exponent) {
            step *= 10;
            mObtained /= 10;
        } else if (.35 >= err) {
            step *= 5;
            mObtained /= 5;
        } else if (.75 >= err) {
            step *= 2;
            mObtained /= 2;
        }
        exponent = Math.floor(pv.log(step, 10) + 1e-10);
        start = step * Math[roundInside ? "ceil" : "floor"](min / step);
        end = step * Math[roundInside ? "floor" : "ceil"](max / step);
        usedNumberExponent = Math.max(0, -exponent);
        tickFormat = pv.Format.number().fractionDigits(usedNumberExponent);
        2 === m && mObtained >= 2 && (step = end - start);
        var ticks = pv.range(start, end + step, step);
        reverse && ticks.reverse();
        ticks.roundInside = roundInside;
        ticks.step = step;
        ticks.exponent = exponent;
        ticks.exponentOverflow = overflow;
        ticks.exponentMin = exponentMin;
        ticks.exponentMax = exponentMax;
        return ticks;
    };
    scale.dateTickFormat = function() {
        if (arguments.length) {
            dateTickFormat = arguments[0];
            return this;
        }
        return dateTickFormat;
    };
    scale.dateTickPrecision = function() {
        if (arguments.length) {
            dateTickPrecision = arguments[0];
            return this;
        }
        return dateTickPrecision;
    };
    scale.tickFormatter = function(f) {
        if (arguments.length) {
            tickFormatter = f;
            return this;
        }
        return tickFormatter;
    };
    scale.tickFormat = function(t) {
        var text;
        text = tickFormatter ? tickFormatter(t, type !== Number ? usedDateTickPrecision : usedNumberExponent) : tickFormat(t);
        return null == text ? "" : "" + text;
    };
    scale.nice = function() {
        if (2 != d.length) return this;
        var start = d[0], end = d[d.length - 1], reverse = start > end, min = reverse ? end : start, max = reverse ? start : end, span = max - min;
        if (!span || !isFinite(span)) return this;
        var step = Math.pow(10, Math.round(Math.log(span) / Math.log(10)) - 1);
        d = [ Math.floor(min / step) * step, Math.ceil(max / step) * step ];
        reverse && d.reverse();
        l = d.map(f);
        return this;
    };
    pv.copyOwn(scale, pv.Scale.common);
    scale.domain.apply(scale, arguments);
    return scale;
};

pv.Scale.linear = function() {
    var scale = pv.Scale.quantitative();
    scale.domain.apply(scale, arguments);
    return scale;
};

pv.Scale.log = function() {
    var b, p, scale = pv.Scale.quantitative(1, 10), log = function(x) {
        return Math.log(x) / p;
    }, pow = function(y) {
        return Math.pow(b, y);
    };
    scale.ticks = function() {
        var d = scale.domain(), n = d[0] < 0, i = Math.floor(n ? -log(-d[0]) : log(d[0])), j = Math.ceil(n ? -log(-d[1]) : log(d[1])), ticks = [];
        if (n) {
            ticks.push(-pow(-i));
            for (;i++ < j; ) for (var k = b - 1; k > 0; k--) ticks.push(-pow(-i) * k);
        } else {
            for (;j > i; i++) for (var k = 1; b > k; k++) ticks.push(pow(i) * k);
            ticks.push(pow(i));
        }
        for (i = 0; ticks[i] < d[0]; i++) ;
        for (j = ticks.length; ticks[j - 1] > d[1]; j--) ;
        return ticks.slice(i, j);
    };
    scale.tickFormat = function(t) {
        return t.toPrecision(1);
    };
    scale.nice = function() {
        var d = scale.domain();
        return scale.domain(pv.logFloor(d[0], b), pv.logCeil(d[1], b));
    };
    scale.base = function(v) {
        if (arguments.length) {
            b = Number(v);
            p = Math.log(b);
            scale.transform(log, pow);
            return this;
        }
        return b;
    };
    scale.domain.apply(scale, arguments);
    return scale.base(10);
};

pv.Scale.root = function() {
    var scale = pv.Scale.quantitative();
    scale.power = function(v) {
        if (arguments.length) {
            var b = Number(v), p = 1 / b;
            scale.transform(function(x) {
                return Math.pow(x, p);
            }, function(y) {
                return Math.pow(y, b);
            });
            return this;
        }
        return b;
    };
    scale.domain.apply(scale, arguments);
    return scale.power(2);
};

pv.Scale.ordinal = function() {
    function scale(x) {
        x in i || (i[x] = d.push(x) - 1);
        return r[i[x] % r.length];
    }
    var d = [], i = {}, r = [];
    scale.domain = function(array, f) {
        if (arguments.length) {
            array = array instanceof Array ? arguments.length > 1 ? pv.map(array, f) : array : Array.prototype.slice.call(arguments);
            d = [];
            for (var seen = {}, j = 0; j < array.length; j++) {
                var o = array[j];
                if (!(o in seen)) {
                    seen[o] = !0;
                    d.push(o);
                }
            }
            i = pv.numerate(d);
            return this;
        }
        return d;
    };
    scale.range = function(array, f) {
        if (arguments.length) {
            r = array instanceof Array ? arguments.length > 1 ? pv.map(array, f) : array : Array.prototype.slice.call(arguments);
            "string" == typeof r[0] && (r = r.map(pv.fillStyle));
            r.min = r[0];
            r.max = r[r.length - 1];
            return this;
        }
        return r;
    };
    scale.split = function(min, max) {
        var R = max - min, N = this.domain().length, step = 0;
        if (0 === R) r = pv.array(N, min); else if (N) {
            step = (max - min) / N;
            r = pv.range(min + step / 2, max, step);
        }
        r.min = min;
        r.max = max;
        r.step = step;
        return this;
    };
    scale.splitBandedCenter = function(min, max, band) {
        scale.split(min, max);
        null == band && (band = 1);
        r.band = r.step * band;
        r.margin = r.step - r.band;
        r.min = min;
        r.max = max;
        return this;
    };
    scale.splitBandedFlushCenter = function(min, max, band) {
        null == band && (band = 1);
        var R = max - min, N = this.domain().length, S = 0, B = 0, M = 0;
        if (0 === R) r = pv.array(N, min); else if (N) {
            B = R * band / N;
            M = N > 1 ? (R - N * B) / (N - 1) : 0;
            S = M + B;
            r = pv.range(min + B / 2, max, S);
        }
        r.step = S;
        r.band = B;
        r.margin = M;
        r.min = min;
        r.max = max;
        return this;
    };
    scale.splitFlush = function(min, max) {
        var n = this.domain().length, step = (max - min) / (n - 1);
        r = 1 == n ? [ (min + max) / 2 ] : pv.range(min, max + step / 2, step);
        r.min = min;
        r.max = max;
        return this;
    };
    scale.splitBanded = function(min, max, band) {
        arguments.length < 3 && (band = 1);
        if (0 > band) {
            var n = this.domain().length, total = -band * n, remaining = max - min - total, padding = remaining / (n + 1);
            r = pv.range(min + padding, max, padding - band);
            r.band = -band;
        } else {
            var step = (max - min) / (this.domain().length + (1 - band));
            r = pv.range(min + step * (1 - band), max, step);
            r.band = step * band;
            r.step = step;
            r.margin = step - r.band;
        }
        r.min = min;
        r.max = max;
        return this;
    };
    scale.invertIndex = function(y, noRound) {
        var N = this.domain().length;
        if (0 === N) return -1;
        var r = this.range(), R = r.max - r.min;
        if (0 === R) return 0;
        var S = R / N;
        if (y >= r.max) return N;
        if (y < r.min) return 0;
        var i = (y - r.min) / S;
        return noRound ? i : Math.round(i);
    };
    pv.copyOwn(scale, pv.Scale.common);
    scale.domain.apply(scale, arguments);
    return scale;
};

pv.Scale.quantile = function() {
    function scale(x) {
        return y(Math.max(0, Math.min(j, pv.search.index(q, x) - 1)) / j);
    }
    var n = -1, j = -1, q = [], d = [], y = pv.Scale.linear();
    scale.quantiles = function(x) {
        if (arguments.length) {
            n = Number(x);
            if (0 > n) {
                q = [ d[0] ].concat(d);
                j = d.length - 1;
            } else {
                q = [];
                q[0] = d[0];
                for (var i = 1; n >= i; i++) q[i] = d[~~(i * (d.length - 1) / n)];
                j = n - 1;
            }
            return this;
        }
        return q;
    };
    scale.domain = function(array, f) {
        if (arguments.length) {
            d = array instanceof Array ? pv.map(array, f) : Array.prototype.slice.call(arguments);
            d.sort(pv.naturalOrder);
            scale.quantiles(n);
            return this;
        }
        return d;
    };
    scale.range = function() {
        if (arguments.length) {
            y.range.apply(y, arguments);
            return this;
        }
        return y.range();
    };
    pv.copyOwn(scale, pv.Scale.common);
    scale.domain.apply(scale, arguments);
    return scale;
};

pv.histogram = function(data, f) {
    var frequency = !0;
    return {
        bins: function(ticks) {
            var x = pv.map(data, f), bins = [];
            arguments.length || (ticks = pv.Scale.linear(x).ticks());
            for (var i = 0; i < ticks.length - 1; i++) {
                var bin = bins[i] = [];
                bin.x = ticks[i];
                bin.dx = ticks[i + 1] - ticks[i];
                bin.y = 0;
            }
            for (var i = 0; i < x.length; i++) {
                var j = pv.search.index(ticks, x[i]) - 1, bin = bins[Math.max(0, Math.min(bins.length - 1, j))];
                bin.y++;
                bin.push(data[i]);
            }
            if (!frequency) for (var i = 0; i < bins.length; i++) bins[i].y /= x.length;
            return bins;
        },
        frequency: function(x) {
            if (arguments.length) {
                frequency = Boolean(x);
                return this;
            }
            return frequency;
        }
    };
};

!function() {
    pv.Shape = function() {};
    var _k0 = {
        x: 1,
        y: 1
    };
    pv.Shape.dist2 = function(v, w, k) {
        k = k || _k0;
        var dx = v.x - w.x, dy = v.y - w.y, dx2 = dx * dx, dy2 = dy * dy;
        return {
            cost: dx2 + dy2,
            dist2: k.x * dx2 + k.y * dy2
        };
    };
    var pi = Math.PI, pi2 = 2 * pi, atan2 = Math.atan2;
    pv.Shape.normalizeAngle = function(a) {
        a %= pi2;
        0 > a && (a += pi2);
        return a;
    };
    pv.Shape.atan2Norm = function(dy, dx) {
        var a = atan2(dy, dx);
        0 > a && (a += pi2);
        return a;
    };
    pv.Shape.prototype.hasArea = function() {
        return !0;
    };
}();

!function() {
    var dist2 = pv.Shape.dist2, cos = Math.cos, sin = Math.sin, sqrt = Math.sqrt;
    pv.vector = function(x, y) {
        return new Point(x, y);
    };
    pv.Vector = function(x, y) {
        this.x = x;
        this.y = y;
    };
    var Point = pv.Shape.Point = pv.Vector;
    pv.Vector.prototype = pv.extend(pv.Shape);
    pv.Vector.prototype.perp = function() {
        return new Point(-this.y, this.x);
    };
    pv.Vector.prototype.rotate = function(angle) {
        var c = cos(angle), s = sin(angle);
        return new Point(c * this.x - s * this.y, s * this.x + c * this.y);
    };
    pv.Vector.prototype.norm = function() {
        var l = this.length();
        return this.times(l ? 1 / l : 1);
    };
    pv.Vector.prototype.length = function() {
        return sqrt(this.x * this.x + this.y * this.y);
    };
    pv.Vector.prototype.times = function(k) {
        return new Point(this.x * k, this.y * k);
    };
    pv.Vector.prototype.plus = function(x, y) {
        return 1 == arguments.length ? new Point(this.x + x.x, this.y + x.y) : new Point(this.x + x, this.y + y);
    };
    pv.Vector.prototype.minus = function(x, y) {
        return 1 == arguments.length ? new Point(this.x - x.x, this.y - x.y) : new Point(this.x - x, this.y - y);
    };
    pv.Vector.prototype.dot = function(x, y) {
        return 1 == arguments.length ? this.x * x.x + this.y * x.y : this.x * x + this.y * y;
    };
    pv.Vector.prototype.hasArea = function() {
        return !1;
    };
    pv.Vector.prototype.clone = function() {
        return new Point(this.x, this.y);
    };
    pv.Vector.prototype.apply = function(t) {
        return new Point(t.x + t.k * this.x, t.y + t.k * this.y);
    };
    pv.Vector.prototype.intersectsRect = function(rect) {
        return this.x >= rect.x && this.x <= rect.x2 && this.y >= rect.y && this.y <= rect.y2;
    };
    pv.Vector.prototype.containsPoint = function(p) {
        return this.x === p.x && this.y === p.y;
    };
    pv.Vector.prototype.points = function() {
        return [ this ];
    };
    pv.Vector.prototype.edges = function() {
        return [];
    };
    pv.Vector.prototype.center = function() {
        return this;
    };
    pv.Vector.prototype.distance2 = function(p, k) {
        return dist2(this, p, k);
    };
}();

!function() {
    var Point = pv.Shape.Point, dist2 = pv.Shape.dist2;
    pv.Shape.Line = function(x, y, x2, y2) {
        this.x = x || 0;
        this.y = y || 0;
        this.x2 = x2 || 0;
        this.y2 = y2 || 0;
    };
    var Line = pv.Shape.Line;
    Line.prototype = pv.extend(pv.Shape);
    Line.prototype.hasArea = function() {
        return !1;
    };
    Line.prototype.clone = function() {
        return new Line(this.x, this.y, this.x2, this.x2);
    };
    Line.prototype.apply = function(t) {
        var x = t.x + t.k * this.x, y = t.y + t.k * this.y, x2 = t.x + t.k * this.x2, y2 = t.y + t.k * this.y2;
        return new Line(x, y, x2, y2);
    };
    Line.prototype.points = function() {
        return [ new Point(this.x, this.y), new Point(this.x2, this.y2) ];
    };
    Line.prototype.edges = function() {
        return [ this ];
    };
    Line.prototype.center = function() {
        return new Point((this.x + this.x2) / 2, (this.y + this.y2) / 2);
    };
    Line.prototype.normal = function(at, shapeCenter) {
        var points = this.points(), norm = points[1].minus(points[0]).perp().norm();
        if (shapeCenter) {
            var outside = points[0].minus(shapeCenter);
            outside.dot(norm) < 0 && (norm = norm.times(-1));
        }
        return norm;
    };
    Line.prototype.intersectsRect = function(rect) {
        var i, L, points = this.points();
        L = points.length;
        for (i = 0; L > i; i++) if (points[i].intersectsRect(rect)) return !0;
        var edges = rect.edges();
        L = edges.length;
        for (i = 0; L > i; i++) if (this.intersectsLine(edges[i])) return !0;
        return !1;
    };
    Line.prototype.containsPoint = function(p) {
        var x = this.x, x2 = this.x2, y = this.y, y2 = this.y2;
        return x <= p.x && p.x <= x2 && (x === x2 ? Math.min(y, y2) <= p.y && p.y <= Math.max(y, y2) : Math.abs((y2 - y) / (x2 - x) * (p.x - x) + y - p.y) <= 1e-10);
    };
    Line.prototype.intersectsLine = function(b) {
        var a = this, x21 = a.x2 - a.x, y21 = a.y2 - a.y, x43 = b.x2 - b.x, y43 = b.y2 - b.y, denom = y43 * x21 - x43 * y21;
        if (0 === denom) return !1;
        var y13 = a.y - b.y, x13 = a.x - b.x, numa = x43 * y13 - y43 * x13, numb = x21 * y13 - y21 * x13;
        if (0 === denom) return 0 === numa && 0 === numb;
        var ua = numa / denom;
        if (0 > ua || ua > 1) return !1;
        var ub = numb / denom;
        return 0 > ub || ub > 1 ? !1 : !0;
    };
    Line.prototype.distance2 = function(p, k) {
        var v = this, w = {
            x: this.x2,
            y: this.y2
        }, l2 = dist2(v, w).dist2;
        if (1e-10 >= l2) return dist2(p, v, k);
        var wvx = w.x - v.x, wvy = w.y - v.y, t = ((p.x - v.x) * wvx + (p.y - v.y) * wvy) / l2;
        if (0 > t) return dist2(p, v, k);
        if (t > 1) return dist2(p, w, k);
        var proj = {
            x: v.x + t * wvx,
            y: v.y + t * wvy
        };
        return dist2(p, proj, k);
    };
}();

!function() {
    var Point = pv.Shape.Point, Line = pv.Shape.Line;
    pv.Shape.Polygon = function(points) {
        this._points = points || [];
    };
    var Polygon = pv.Shape.Polygon;
    Polygon.prototype = pv.extend(pv.Shape);
    Polygon.prototype.points = function() {
        return this._points;
    };
    Polygon.prototype.clone = function() {
        return new Polygon(this.points().slice());
    };
    Polygon.prototype.apply = function(t) {
        for (var points = this.points(), L = points.length, points2 = new Array(L), i = 0; L > i; i++) points2[i] = points[i].apply(t);
        return new Polygon(points2);
    };
    Polygon.prototype.intersectsRect = function(rect) {
        var i, L, points = this.points();
        L = points.length;
        for (i = 0; L > i; i++) if (points[i].intersectsRect(rect)) return !0;
        var edges = this.edges();
        L = edges.length;
        for (i = 0; L > i; i++) if (edges[i].intersectsRect(rect)) return !0;
        return !1;
    };
    Polygon.prototype.edges = function() {
        var edges = this._edges;
        if (!edges) {
            edges = this._edges = [];
            var points = this.points(), L = points.length;
            if (L) {
                for (var point, prevPoint = points[0], firstPoint = prevPoint, i = 1; L > i; i++) {
                    point = points[i];
                    edges.push(new Line(prevPoint.x, prevPoint.y, point.x, point.y));
                    prevPoint = point;
                }
                L > 2 && edges.push(new Line(point.x, point.y, firstPoint.x, firstPoint.y));
            }
        }
        return edges;
    };
    Polygon.prototype.distance2 = function(p, k) {
        var min = {
            cost: 1/0,
            dist2: 1/0
        };
        this.edges().forEach(function(edge) {
            var d = edge.distance2(p, k);
            d.cost < min.cost && (min = d);
        }, this);
        return min;
    };
    Polygon.prototype.center = function() {
        for (var points = this.points(), x = 0, y = 0, i = 0, L = points.length; L > i; i++) {
            var p = points[i];
            x += p.x;
            y += p.y;
        }
        return new Point(x / L, y / L);
    };
    Polygon.prototype.containsPoint = function(p) {
        var bbox = this.bbox();
        if (!bbox.containsPoint(p)) return !1;
        var e = .01 * bbox.dx, ray = new Line(bbox.x - e, p.y, p.x, p.y), intersectCount = 0, edges = this.edges();
        edges.forEach(function(edge) {
            edge.intersectsLine(ray) && intersectCount++;
        });
        return 1 === (1 & intersectCount);
    };
    Polygon.prototype.bbox = function() {
        var bbox = this._bbox;
        if (!bbox) {
            var min, max;
            this.points().forEach(function(point) {
                if (null == min) min = {
                    x: point.x,
                    y: point.y
                }; else {
                    point.x < min.x && (min.x = point.x);
                    point.y < min.y && (min.y = point.y);
                }
                if (null == max) max = {
                    x: point.x,
                    y: point.y
                }; else {
                    point.x > max.x && (max.x = point.x);
                    point.y > max.y && (max.y = point.y);
                }
            });
            min && (bbox = this._bbox = new pv.Shape.Rect(min.x, min.y, max.x - min.x, max.y - min.y));
        }
        return bbox;
    };
}();

!function() {
    var Point = pv.Shape.Point, Line = pv.Shape.Line;
    pv.Shape.Rect = function(x, y, dx, dy) {
        this.x = x || 0;
        this.y = y || 0;
        this.dx = dx || 0;
        this.dy = dy || 0;
        if (this.dx < 0) {
            this.dx = -this.dx;
            this.x = this.x - this.dx;
        }
        if (this.dy < 0) {
            this.dy = -this.dy;
            this.y = this.y - this.dy;
        }
        this.x2 = this.x + this.dx;
        this.y2 = this.y + this.dy;
    };
    var Rect = pv.Shape.Rect;
    Rect.prototype = pv.extend(pv.Shape.Polygon);
    Rect.prototype.clone = function() {
        var r2 = Object.create(Rect.prototype);
        r2.x = this.x;
        r2.y = this.y;
        r2.dx = this.dx;
        r2.dy = this.dy;
        r2.x2 = this.x2;
        r2.y2 = this.y2;
        return r2;
    };
    Rect.prototype.apply = function(t) {
        var x = t.x + t.k * this.x, y = t.y + t.k * this.y, dx = t.k * this.dx, dy = t.k * this.dy;
        return new Rect(x, y, dx, dy);
    };
    Rect.prototype.containsPoint = function(p) {
        return this.x <= p.x && p.x <= this.x2 && this.y <= p.y && p.y <= this.y2;
    };
    Rect.prototype.intersectsRect = function(rect) {
        return this.x2 > rect.x && this.x < rect.x2 && this.y2 > rect.y && this.y < rect.y2;
    };
    Rect.prototype.edges = function() {
        if (!this._edges) {
            var x = this.x, y = this.y, x2 = this.x2, y2 = this.y2;
            this._edges = [ new Line(x, y, x2, y), new Line(x2, y, x2, y2), new Line(x2, y2, x, y2), new Line(x, y2, x, y) ];
        }
        return this._edges;
    };
    Rect.prototype.center = function() {
        return new Point(this.x + this.dx / 2, this.y + this.dy / 2);
    };
    Rect.prototype.points = function() {
        var points = this._points;
        if (!points) {
            var x = this.x, y = this.y, x2 = this.x2, y2 = this.y2;
            points = this._points = [ new Point(x, y), new Point(x2, y), new Point(x2, y2), new Point(x, y2) ];
        }
        return points;
    };
    Rect.prototype.bbox = function() {
        return this.clone();
    };
}();

!function() {
    var Point = pv.Shape.Point, dist2 = pv.Shape.dist2, sqrt = Math.sqrt, abs = Math.abs, pow = Math.pow;
    pv.Shape.Circle = function(x, y, radius) {
        this.x = x || 0;
        this.y = y || 0;
        this.radius = radius || 0;
    };
    var Circle = pv.Shape.Circle;
    Circle.prototype = pv.extend(pv.Shape);
    Circle.prototype.clone = function() {
        return new Circle(this.x, this.y, this.radius);
    };
    Circle.prototype.apply = function(t) {
        var x = t.x + t.k * this.x, y = t.y + t.k * this.y, r = t.k * this.radius;
        return new Circle(x, y, r);
    };
    Circle.prototype.intersectsRect = function(rect) {
        var dx2 = rect.dx / 2, dy2 = rect.dy / 2, r = this.radius, circleDistX = abs(this.x - rect.x - dx2), circleDistY = abs(this.y - rect.y - dy2);
        if (circleDistX > dx2 + r || circleDistY > dy2 + r) return !1;
        if (dx2 >= circleDistX || dy2 >= circleDistY) return !0;
        var sqCornerDistance = pow(circleDistX - dx2, 2) + pow(circleDistY - dy2, 2);
        return r * r >= sqCornerDistance;
    };
    Circle.prototype.intersectLine = function(line, isInfiniteLine) {
        var baX = line.x2 - line.x, baY = line.y2 - line.y, caX = this.x - line.x, caY = this.y - line.y, ba2 = baX * baX + baY * baY, bBy2 = baX * caX + baY * caY, r = this.radius, c = caX * caX + caY * caY - r * r, pBy2 = bBy2 / ba2, disc = pBy2 * pBy2 - c / ba2;
        if (!(0 > disc)) {
            var discSqrt = sqrt(disc), t1 = pBy2 - discSqrt, t2 = pBy2 + discSqrt, ps = [];
            (isInfiniteLine || t1 >= 0 && 1 >= t1) && ps.push(new Point(line.x + baX * t1, line.y + baY * t1));
            0 !== disc && (isInfiniteLine || t2 >= 0 && 1 >= t2) && ps.push(new Point(line.x + baX * t2, line.y + baY * t2));
            return ps;
        }
    };
    Circle.prototype.points = function() {
        return [ this.center() ];
    };
    Circle.prototype.center = function() {
        return new Point(this.x, this.y);
    };
    Circle.prototype.normal = function(at) {
        return at.minus(this.x, this.y).norm();
    };
    Circle.prototype.containsPoint = function(p) {
        var dx = p.x - this.x, dy = p.y - this.y, r = this.radius;
        return r * r >= dx * dx + dy * dy;
    };
    Circle.prototype.distance2 = function(p, k) {
        var r = (p.x - this.x, p.y - this.y, this.radius), b = p.minus(this).norm().times(r).plus(this), dBorder = dist2(p, b, k);
        return dBorder;
    };
}();

!function() {
    var Point = pv.Shape.Point, dist2 = pv.Shape.dist2, normalizeAngle = pv.Shape.normalizeAngle, atan2Norm = pv.Shape.atan2Norm, cos = Math.cos, sin = Math.sin, sqrt = Math.sqrt;
    pv.Shape.Arc = function(x, y, radius, startAngle, angleSpan) {
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.startAngle = normalizeAngle(startAngle);
        this.angleSpan = normalizeAngle(angleSpan);
        this.endAngle = this.startAngle + this.angleSpan;
    };
    var Arc = pv.Shape.Arc;
    Arc.prototype = pv.extend(pv.Shape);
    Arc.prototype.hasArea = function() {
        return !1;
    };
    Arc.prototype.clone = function() {
        var arc = Object.create(Arc.prototype), me = this;
        arc.x = me.x;
        arc.y = me.y;
        arc.radius = me.radius;
        arc.startAngle = me.startAngle;
        arc.angleSpan = me.angleSpan;
        arc.endAngle = me.endAngle;
        return arc;
    };
    Arc.prototype.apply = function(t) {
        var x = t.x + t.k * this.x, y = t.y + t.k * this.y, r = t.k * this.radius;
        return new Arc(x, y, r, this.startAngle, this.angleSpan);
    };
    Arc.prototype.containsPoint = function(p) {
        var dx = p.x - this.x, dy = p.y - this.y, r = sqrt(dx * dx + dy * dy);
        if (Math.abs(r - this.radius) <= 1e-10) {
            var a = atan2Norm(dy, dx);
            return this.startAngle <= a && a <= this.endAngle;
        }
        return !1;
    };
    Arc.prototype.intersectsRect = function(rect) {
        var i, L, points = this.points(), L = points.length;
        for (i = 0; L > i; i++) if (points[i].intersectsRect(rect)) return !0;
        var edges = rect.edges();
        L = edges.length;
        for (i = 0; L > i; i++) if (this.intersectLine(edges[i])) return !0;
        return !1;
    };
    var circleIntersectLine = pv.Shape.Circle.prototype.intersectLine;
    Arc.prototype.intersectLine = function(line, isInfiniteLine) {
        var ps = circleIntersectLine.call(this, line, isInfiniteLine);
        if (ps) {
            ps = ps.filter(function(p) {
                return this.containsPoint(p);
            }, this);
            if (ps.length) return ps;
        }
    };
    Arc.prototype.points = function() {
        var x = this.x, y = this.y, r = this.radius, ai = this.startAngle, af = this.endAngle;
        return [ new Point(x + r * cos(ai), y + r * sin(ai)), new Point(x + r * cos(af), y + r * sin(af)) ];
    };
    Arc.prototype.center = function() {
        var x = this.x, y = this.y, r = this.radius, am = (this.startAngle + this.endAngle) / 2;
        return new Point(x + r * cos(am), y + r * sin(am));
    };
    Arc.prototype.normal = function(at, shapeCenter) {
        var norm = at.minus(this.x, this.y).norm();
        if (shapeCenter) {
            var outside = this.center().minus(shapeCenter);
            outside.dot(norm) < 0 && (norm = norm.times(-1));
        }
        return norm;
    };
    Arc.prototype.distance2 = function(p, k) {
        var dx = p.x - this.x, dy = p.y - this.y, a = atan2Norm(dy, dx);
        if (this.startAngle <= a && a <= this.endAngle) {
            var b = new Point(this.x + this.radius * cos(a), this.y + this.radius * sin(a));
            return dist2(p, b, k);
        }
        var points = this.points(), d1 = dist2(p, points[0], k), d2 = dist2(p, points[1], k);
        return d1.cost < d2.cost ? d1 : d2;
    };
}();

!function() {
    var Arc = pv.Shape.Arc, Line = pv.Shape.Line, Point = pv.Shape.Point, cos = Math.cos, sin = Math.sin, sqrt = Math.sqrt, atan2Norm = pv.Shape.atan2Norm, normalizeAngle = pv.Shape.normalizeAngle;
    pv.Shape.Wedge = function(x, y, innerRadius, outerRadius, startAngle, angleSpan) {
        this.x = x;
        this.y = y;
        this.innerRadius = innerRadius;
        this.outerRadius = outerRadius;
        this.startAngle = normalizeAngle(startAngle);
        this.angleSpan = normalizeAngle(angleSpan);
        this.endAngle = this.startAngle + this.angleSpan;
    };
    var Wedge = pv.Shape.Wedge;
    Wedge.prototype = pv.extend(pv.Shape);
    Wedge.prototype.clone = function() {
        return new Wedge(this.x, this.y, this.innerRadius, this.outerRadius, this.startAngle, this.angleSpan);
    };
    Wedge.prototype.apply = function(t) {
        var x = t.x + t.k * this.x, y = t.y + t.k * this.y, ir = t.k * this.innerRadius, or = t.k * this.outerRadius;
        return new Wedge(x, y, ir, or, this.startAngle, this.angleSpan);
    };
    Wedge.prototype.containsPoint = function(p) {
        var dx = p.x - this.x, dy = p.y - this.y, r = sqrt(dx * dx + dy * dy);
        if (r >= this.innerRadius && r <= this.outerRadius) {
            var a = atan2Norm(dy, dx);
            return this.startAngle <= a && a <= this.endAngle;
        }
        return !1;
    };
    Wedge.prototype.intersectsRect = function(rect) {
        var i, L, points = this.points();
        L = points.length;
        for (i = 0; L > i; i++) if (points[i].intersectsRect(rect)) return !0;
        points = rect.points();
        L = points.length;
        for (i = 0; L > i; i++) if (this.containsPoint(points[i])) return !0;
        var edges = this.edges();
        L = edges.length;
        for (i = 0; L > i; i++) if (edges[i].intersectsRect(rect)) return !0;
        return !1;
    };
    Wedge.prototype.points = function() {
        this._points || this.edges();
        return this._points;
    };
    Wedge.prototype.edges = function() {
        var edges = this._edges;
        if (!edges) {
            var pii, pfi, x = this.x, y = this.y, ir = this.innerRadius, or = this.outerRadius, ai = this.startAngle, af = this.endAngle, aa = this.angleSpan, cai = cos(ai), sai = sin(ai), caf = cos(af), saf = sin(af);
            if (ir > 0) {
                pii = new Point(x + ir * cai, y + ir * sai);
                pfi = new Point(x + ir * caf, y + ir * saf);
            } else pii = pfi = new Point(x, y);
            var pio = new Point(x + or * cai, y + or * sai), pfo = new Point(x + or * caf, y + or * saf);
            edges = this._edges = [];
            ir > 0 && edges.push(new Arc(x, y, ir, ai, aa));
            edges.push(new Line(pii.x, pii.y, pio.x, pio.y), new Arc(x, y, or, ai, aa), new Line(pfi.x, pfi.y, pfo.x, pfo.y));
            var points = this._points = [ pii, pio, pfo ];
            ir > 0 && points.push(pfi);
        }
        return edges;
    };
    Wedge.prototype.distance2 = function(p, k) {
        var min = {
            cost: 1/0,
            dist2: 1/0
        };
        this.edges().forEach(function(edge) {
            var d = edge.distance2(p, k);
            d.cost < min.cost && (min = d);
        });
        return min;
    };
    Wedge.prototype.center = function() {
        var midAngle = (this.startAngle + this.endAngle) / 2, midRadius = (this.innerRadius + this.outerRadius) / 2;
        return new Point(this.x + midRadius * cos(midAngle), this.y + midRadius * sin(midAngle));
    };
}();

!function() {
    var round = Math.round, parseRgb = function(c) {
        var f = parseFloat(c);
        return "%" == c[c.length - 1] ? round(2.55 * f) : f;
    }, reSysColor = /([a-z]+)\((.*)\)/i, createColor = function(format) {
        if ("#" === format.charAt(0)) {
            var r, g, b;
            if (4 === format.length) {
                r = format.charAt(1);
                r += r;
                g = format.charAt(2);
                g += g;
                b = format.charAt(3);
                b += b;
            } else if (7 === format.length) {
                r = format.substring(1, 3);
                g = format.substring(3, 5);
                b = format.substring(5, 7);
            }
            return pv.rgb(parseInt(r, 16), parseInt(g, 16), parseInt(b, 16), 1);
        }
        var m1 = reSysColor.exec(format);
        if (m1) {
            var m2 = m1[2].split(","), a = 1;
            switch (m1[1]) {
              case "hsla":
              case "rgba":
                a = parseFloat(m2[3]);
                if (!a) return pv.Color.transparent;
            }
            switch (m1[1]) {
              case "hsla":
              case "hsl":
                var h = parseFloat(m2[0]), s = parseFloat(m2[1]) / 100, l = parseFloat(m2[2]) / 100;
                return new pv.Color.Hsl(h, s, l, a).rgb();

              case "rgba":
              case "rgb":
                var r = parseRgb(m2[0]), g = parseRgb(m2[1]), b = parseRgb(m2[2]);
                return pv.rgb(r, g, b, a);
            }
        }
        return new pv.Color(format, 1);
    }, colorsByFormat = {};
    pv.color = function(format) {
        if (format.rgb) return format.rgb();
        var color = pv.Color.names[format];
        color || (color = colorsByFormat[format] || (colorsByFormat[format] = createColor(format)));
        return color;
    };
}();

pv.Color = function(color, opacity) {
    this.color = color;
    this.opacity = opacity;
    this.key = "solid " + color + " alpha(" + opacity + ")";
};

pv.Color.prototype.hsl = function() {
    return this.rgb().hsl();
};

pv.Color.prototype.brighter = function(k) {
    return this.rgb().brighter(k);
};

pv.Color.prototype.darker = function(k) {
    return this.rgb().darker(k);
};

pv.Color.prototype.alphaBlend = function(mate) {
    var rgb = this.rgb(), a = rgb.a;
    if (1 === a) return this;
    mate = mate ? pv.color(mate) : pv.Color.names.white;
    mate = mate.rgb();
    var z = 1 - a;
    return pv.rgb(z * rgb.r + a * mate.r, z * rgb.g + a * mate.g, z * rgb.b + a * mate.b, 1);
};

pv.Color.prototype.rgbDecimal = function(mate) {
    var rgb = this.alphaBlend(mate);
    return rgb.r << 16 | rgb.g << 8 | rgb.b;
};

pv.Color.prototype.isDark = function() {
    return this.rgbDecimal() < 8388607.5;
};

pv.rgb = function(r, g, b, a) {
    return new pv.Color.Rgb(r, g, b, 4 == arguments.length ? a : 1);
};

pv.Color.Rgb = function(r, g, b, a) {
    pv.Color.call(this, a ? "rgb(" + r + "," + g + "," + b + ")" : "none", a);
    this.r = r;
    this.g = g;
    this.b = b;
    this.a = a;
};

pv.Color.Rgb.prototype = pv.extend(pv.Color);

pv.Color.Rgb.prototype.red = function(r) {
    return pv.rgb(r, this.g, this.b, this.a);
};

pv.Color.Rgb.prototype.green = function(g) {
    return pv.rgb(this.r, g, this.b, this.a);
};

pv.Color.Rgb.prototype.blue = function(b) {
    return pv.rgb(this.r, this.g, b, this.a);
};

pv.Color.Rgb.prototype.alpha = function(a) {
    return pv.rgb(this.r, this.g, this.b, a);
};

pv.Color.Rgb.prototype.rgb = function() {
    return this;
};

pv.Color.Rgb.prototype.brighter = function(k) {
    k = Math.pow(.7, null != k ? k : 1);
    var r = this.r, g = this.g, b = this.b, i = 30;
    if (!r && !g && !b) return pv.rgb(i, i, i, this.a);
    r && i > r && (r = i);
    g && i > g && (g = i);
    b && i > b && (b = i);
    return pv.rgb(Math.min(255, Math.floor(r / k)), Math.min(255, Math.floor(g / k)), Math.min(255, Math.floor(b / k)), this.a);
};

pv.Color.Rgb.prototype.darker = function(k) {
    k = Math.pow(.7, null != k ? k : 1);
    return pv.rgb(Math.max(0, Math.floor(k * this.r)), Math.max(0, Math.floor(k * this.g)), Math.max(0, Math.floor(k * this.b)), this.a);
};

pv.Color.Rgb.prototype.hsl = function() {
    var h, s, r = this.r / 255, g = this.g / 255, b = this.b / 255, max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2;
    if (max === min) h = s = 0; else {
        var d = max - min;
        s = l > .5 ? d / (2 - max - min) : d / (max + min);
        switch (max) {
          case r:
            h = (g - b) / d + (b > g ? 6 : 0);
            break;

          case g:
            h = (b - r) / d + 2;
            break;

          case b:
            h = (r - g) / d + 4;
        }
        h /= 6;
    }
    return pv.hsl(360 * h, s, l, this.a);
};

pv.Color.Rgb.prototype.complementary = function() {
    return this.hsl().complementary().rgb();
};

pv.hsl = function(h, s, l, a) {
    return new pv.Color.Hsl(h, s, l, 4 == arguments.length ? a : 1);
};

pv.Color.Hsl = function(h, s, l, a) {
    pv.Color.call(this, "hsl(" + h + "," + 100 * s + "%," + 100 * l + "%)", a);
    this.h = h;
    this.s = s;
    this.l = l;
    this.a = a;
};

pv.Color.Hsl.prototype = pv.extend(pv.Color);

pv.Color.Hsl.prototype.hsl = function() {
    return this;
};

pv.Color.Hsl.prototype.hue = function(h) {
    return pv.hsl(h, this.s, this.l, this.a);
};

pv.Color.Hsl.prototype.saturation = function(s) {
    return pv.hsl(this.h, s, this.l, this.a);
};

pv.Color.Hsl.prototype.lightness = function(l) {
    return pv.hsl(this.h, this.s, l, this.a);
};

pv.Color.Hsl.prototype.alpha = function(a) {
    return pv.hsl(this.h, this.s, this.l, a);
};

pv.Color.Hsl.prototype.complementary = function() {
    return pv.hsl((this.h + 180) % 360, 1 - this.s, 1 - this.l, this.a);
};

pv.Color.Hsl.prototype.rgb = function() {
    function v(h) {
        h > 360 ? h -= 360 : 0 > h && (h += 360);
        return 60 > h ? m1 + (m2 - m1) * h / 60 : 180 > h ? m2 : 240 > h ? m1 + (m2 - m1) * (240 - h) / 60 : m1;
    }
    function vv(h) {
        return Math.round(255 * v(h));
    }
    var h = this.h, s = this.s, l = this.l;
    h %= 360;
    0 > h && (h += 360);
    s = Math.max(0, Math.min(s, 1));
    l = Math.max(0, Math.min(l, 1));
    var m2 = .5 >= l ? l * (1 + s) : l + s - l * s, m1 = 2 * l - m2;
    return pv.rgb(vv(h + 120), vv(h), vv(h - 120), this.a);
};

pv.Color.names = {
    aliceblue: "#f0f8ff",
    antiquewhite: "#faebd7",
    aqua: "#00ffff",
    aquamarine: "#7fffd4",
    azure: "#f0ffff",
    beige: "#f5f5dc",
    bisque: "#ffe4c4",
    black: "#000000",
    blanchedalmond: "#ffebcd",
    blue: "#0000ff",
    blueviolet: "#8a2be2",
    brown: "#a52a2a",
    burlywood: "#deb887",
    cadetblue: "#5f9ea0",
    chartreuse: "#7fff00",
    chocolate: "#d2691e",
    coral: "#ff7f50",
    cornflowerblue: "#6495ed",
    cornsilk: "#fff8dc",
    crimson: "#dc143c",
    cyan: "#00ffff",
    darkblue: "#00008b",
    darkcyan: "#008b8b",
    darkgoldenrod: "#b8860b",
    darkgray: "#a9a9a9",
    darkgreen: "#006400",
    darkgrey: "#a9a9a9",
    darkkhaki: "#bdb76b",
    darkmagenta: "#8b008b",
    darkolivegreen: "#556b2f",
    darkorange: "#ff8c00",
    darkorchid: "#9932cc",
    darkred: "#8b0000",
    darksalmon: "#e9967a",
    darkseagreen: "#8fbc8f",
    darkslateblue: "#483d8b",
    darkslategray: "#2f4f4f",
    darkslategrey: "#2f4f4f",
    darkturquoise: "#00ced1",
    darkviolet: "#9400d3",
    deeppink: "#ff1493",
    deepskyblue: "#00bfff",
    dimgray: "#696969",
    dimgrey: "#696969",
    dodgerblue: "#1e90ff",
    firebrick: "#b22222",
    floralwhite: "#fffaf0",
    forestgreen: "#228b22",
    fuchsia: "#ff00ff",
    gainsboro: "#dcdcdc",
    ghostwhite: "#f8f8ff",
    gold: "#ffd700",
    goldenrod: "#daa520",
    gray: "#808080",
    green: "#008000",
    greenyellow: "#adff2f",
    grey: "#808080",
    honeydew: "#f0fff0",
    hotpink: "#ff69b4",
    indianred: "#cd5c5c",
    indigo: "#4b0082",
    ivory: "#fffff0",
    khaki: "#f0e68c",
    lavender: "#e6e6fa",
    lavenderblush: "#fff0f5",
    lawngreen: "#7cfc00",
    lemonchiffon: "#fffacd",
    lightblue: "#add8e6",
    lightcoral: "#f08080",
    lightcyan: "#e0ffff",
    lightgoldenrodyellow: "#fafad2",
    lightgray: "#d3d3d3",
    lightgreen: "#90ee90",
    lightgrey: "#d3d3d3",
    lightpink: "#ffb6c1",
    lightsalmon: "#ffa07a",
    lightseagreen: "#20b2aa",
    lightskyblue: "#87cefa",
    lightslategray: "#778899",
    lightslategrey: "#778899",
    lightsteelblue: "#b0c4de",
    lightyellow: "#ffffe0",
    lime: "#00ff00",
    limegreen: "#32cd32",
    linen: "#faf0e6",
    magenta: "#ff00ff",
    maroon: "#800000",
    mediumaquamarine: "#66cdaa",
    mediumblue: "#0000cd",
    mediumorchid: "#ba55d3",
    mediumpurple: "#9370db",
    mediumseagreen: "#3cb371",
    mediumslateblue: "#7b68ee",
    mediumspringgreen: "#00fa9a",
    mediumturquoise: "#48d1cc",
    mediumvioletred: "#c71585",
    midnightblue: "#191970",
    mintcream: "#f5fffa",
    mistyrose: "#ffe4e1",
    moccasin: "#ffe4b5",
    navajowhite: "#ffdead",
    navy: "#000080",
    oldlace: "#fdf5e6",
    olive: "#808000",
    olivedrab: "#6b8e23",
    orange: "#ffa500",
    orangered: "#ff4500",
    orchid: "#da70d6",
    palegoldenrod: "#eee8aa",
    palegreen: "#98fb98",
    paleturquoise: "#afeeee",
    palevioletred: "#db7093",
    papayawhip: "#ffefd5",
    peachpuff: "#ffdab9",
    peru: "#cd853f",
    pink: "#ffc0cb",
    plum: "#dda0dd",
    powderblue: "#b0e0e6",
    purple: "#800080",
    red: "#ff0000",
    rosybrown: "#bc8f8f",
    royalblue: "#4169e1",
    saddlebrown: "#8b4513",
    salmon: "#fa8072",
    sandybrown: "#f4a460",
    seagreen: "#2e8b57",
    seashell: "#fff5ee",
    sienna: "#a0522d",
    silver: "#c0c0c0",
    skyblue: "#87ceeb",
    slateblue: "#6a5acd",
    slategray: "#708090",
    slategrey: "#708090",
    snow: "#fffafa",
    springgreen: "#00ff7f",
    steelblue: "#4682b4",
    tan: "#d2b48c",
    teal: "#008080",
    thistle: "#d8bfd8",
    tomato: "#ff6347",
    turquoise: "#40e0d0",
    violet: "#ee82ee",
    wheat: "#f5deb3",
    white: "#ffffff",
    whitesmoke: "#f5f5f5",
    yellow: "#ffff00",
    yellowgreen: "#9acd32",
    transparent: pv.Color.transparent = pv.rgb(0, 0, 0, 0)
};

!function() {
    var names = pv.Color.names;
    names.none = names.transparent;
    for (var name in names) names[name] = pv.color(names[name]);
}();

pv.colors = function() {
    var scale = pv.Scale.ordinal();
    scale.range.apply(scale, arguments);
    return scale;
};

pv.Colors = {};

pv.Colors.category10 = function() {
    var scale = pv.colors("#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd", "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf");
    scale.domain.apply(scale, arguments);
    return scale;
};

pv.Colors.category20 = function() {
    var scale = pv.colors("#1f77b4", "#aec7e8", "#ff7f0e", "#ffbb78", "#2ca02c", "#98df8a", "#d62728", "#ff9896", "#9467bd", "#c5b0d5", "#8c564b", "#c49c94", "#e377c2", "#f7b6d2", "#7f7f7f", "#c7c7c7", "#bcbd22", "#dbdb8d", "#17becf", "#9edae5");
    scale.domain.apply(scale, arguments);
    return scale;
};

pv.Colors.category19 = function() {
    var scale = pv.colors("#9c9ede", "#7375b5", "#4a5584", "#cedb9c", "#b5cf6b", "#8ca252", "#637939", "#e7cb94", "#e7ba52", "#bd9e39", "#8c6d31", "#e7969c", "#d6616b", "#ad494a", "#843c39", "#de9ed6", "#ce6dbd", "#a55194", "#7b4173");
    scale.domain.apply(scale, arguments);
    return scale;
};

!function() {
    function parseLinearGradient(text) {
        var terms = parseText(text);
        if (!terms.length) return null;
        var keyAngle, m, f, angle = Math.PI, term = terms[0];
        if (0 === term.indexOf("to ")) {
            m = /^to\s+(?:((top|bottom)(?:\s+(left|right))?)|((left|right)(?:\\s+(top|bottom))?))$/.exec(term);
            if (m) {
                if (m[1]) {
                    keyAngle = m[2];
                    m[3] && (keyAngle += " " + m[3]);
                } else {
                    keyAngle = m[5];
                    m[6] && (keyAngle = m[6] + " " + keyAngle);
                }
                angle = pv.radians(keyAnglesDeg[keyAngle]);
                terms.shift();
            }
        } else {
            f = parseFloat(term);
            if (!isNaN(f)) {
                angle = f;
                /^.*?deg$/.test(term) && (angle = pv.radians(angle));
                terms.shift();
            }
        }
        var stops = parseStops(terms);
        switch (stops.length) {
          case 0:
            return null;

          case 1:
            return new pv.FillStyle.Solid(stops[0].color, 1);
        }
        return new pv.FillStyle.LinearGradient(angle, stops, text);
    }
    function parseRadialGradient(text) {
        var terms = parseText(text);
        if (!terms.length) return null;
        var stops = parseStops(terms);
        switch (stops.length) {
          case 0:
            return null;

          case 1:
            return new pv.FillStyle.Solid(stops[0].color, 1);
        }
        return new pv.FillStyle.RadialGradient(50, 50, stops, text);
    }
    function parseText(text) {
        var colorFuns = {}, colorFunId = 0;
        text = text.replace(/\b\w+?\(.*?\)/g, function($0) {
            var id = "__color" + colorFunId++;
            colorFuns[id] = $0;
            return id;
        });
        var terms = text.split(/\s*,\s*/);
        if (!terms.length) return null;
        colorFunId && terms.forEach(function(id, index) {
            colorFuns.hasOwnProperty(id) && (terms[index] = colorFuns[id]);
        });
        return terms;
    }
    function parseStops(terms) {
        function processPendingStops(lastOffset) {
            var count = pendingOffsetStops.length;
            if (count) {
                for (var firstOffset = maxOffsetPercent, step = (lastOffset - firstOffset) / (count + 1), i = 0; count > i; i++) {
                    firstOffset += step;
                    pendingOffsetStops[i].offset = firstOffset;
                }
                pendingOffsetStops.length = 0;
            }
        }
        for (var stops = [], minOffsetPercent = +1/0, maxOffsetPercent = -1/0, pendingOffsetStops = [], i = 0, T = terms.length; T > i; ) {
            var term = terms[i++], m = /^(.+?)\s*([+\-]?[e\.\d]+%)?$/i.exec(term);
            if (m) {
                var stop = {
                    color: pv.color(m[1])
                }, offsetPercent = parseFloat(m[2]);
                isNaN(offsetPercent) && (stops.length ? i === T && (offsetPercent = Math.max(maxOffsetPercent, 100)) : offsetPercent = 0);
                stops.push(stop);
                if (isNaN(offsetPercent)) pendingOffsetStops.push(stop); else {
                    stop.offset = offsetPercent;
                    processPendingStops(offsetPercent);
                    offsetPercent > maxOffsetPercent ? maxOffsetPercent = offsetPercent : maxOffsetPercent > offsetPercent && (offsetPercent = maxOffsetPercent);
                    minOffsetPercent > offsetPercent && (minOffsetPercent = offsetPercent);
                }
            }
        }
        if (stops.length >= 2 && (0 > minOffsetPercent || maxOffsetPercent > 100)) {
            var colorDomain = [], colorRange = [];
            stops.forEach(function(stop) {
                colorDomain.push(stop.offset);
                colorRange.push(stop.color);
            });
            var colorScale = pv.scale.linear().domain(colorDomain).range(colorRange);
            if (0 > minOffsetPercent) {
                for (;stops.length && stops[0].offset <= 0; ) stops.shift();
                stops.unshift({
                    offset: 0,
                    color: colorScale(0)
                });
            }
            if (maxOffsetPercent > 100) {
                for (;stops.length && stops[stops.length - 1].offset >= 100; ) stops.pop();
                stops.push({
                    offset: 100,
                    color: colorScale(100)
                });
            }
        }
        return stops;
    }
    pv.fillStyle = function(format) {
        if (format.type) return format;
        var k = format.key || format, fillStyle = fillStylesByKey[k];
        fillStyle = fillStyle ? fillStyle.clone() : fillStylesByKey[k] = createFillStyle(format);
        return fillStyle;
    };
    var fillStylesByKey = {}, createFillStyle = function(format) {
        if (format.rgb) return new pv.FillStyle.Solid(format.color, format.opacity);
        var match = /^\s*([a-z\-]+)\(\s*(.*?)\s*\)\s*$/.exec(format);
        if (match) switch (match[1]) {
          case "linear-gradient":
            return parseLinearGradient(match[2]);

          case "radial-gradient":
            return parseRadialGradient(match[2]);
        }
        return new pv.FillStyle.Solid(pv.color(format));
    }, keyAnglesDeg = {
        top: 0,
        "top right": 45,
        right: 90,
        "bottom right": 135,
        bottom: 180,
        "bottom left": 225,
        left: 270,
        "top left": 315
    }, FillStyle = pv.FillStyle = function(type) {
        this.type = type;
        this.key = type;
    };
    pv.extendType(FillStyle, new pv.Color("none", 1));
    FillStyle.prototype.rgb = function() {
        var color = pv.color(this.color);
        this.opacity !== color.opacity && (color = color.alpha(this.opacity));
        return color;
    };
    FillStyle.prototype.alphaBlend = function(mate) {
        return this.rgb().alphaBlend(mate);
    };
    FillStyle.prototype.rgbDecimal = function(mate) {
        return this.rgb().rgbDecimal(mate);
    };
    FillStyle.prototype.isDark = function() {
        return this.rgb().isDark();
    };
    var Solid = pv.FillStyle.Solid = function(color, opacity) {
        FillStyle.call(this, "solid");
        if (color.rgb) {
            this.color = color.color;
            this.opacity = color.opacity;
        } else {
            this.color = color;
            this.opacity = opacity;
        }
        this.key += " " + this.color + " alpha(" + this.opacity + ")";
    };
    pv.extendType(Solid, FillStyle);
    Solid.prototype.alpha = function(opacity) {
        return new Solid(this.color, opacity);
    };
    Solid.prototype.brighter = function(k) {
        return new Solid(this.rgb().brighter(k));
    };
    Solid.prototype.darker = function(k) {
        return new Solid(this.rgb().darker(k));
    };
    Solid.prototype.complementary = function() {
        return new Solid(this.rgb().complementary());
    };
    Solid.prototype.clone = function() {
        var o = pv.extend(Solid);
        o.type = this.type;
        o.key = this.key;
        o.color = this.color;
        o.opacity = this.opacity;
        return o;
    };
    pv.FillStyle.transparent = new Solid(pv.Color.transparent);
    var gradient_id = 0, Gradient = pv.FillStyle.Gradient = function(type, stops) {
        FillStyle.call(this, type);
        this.id = ++gradient_id;
        this.stops = stops;
        stops.length && (this.color = stops[0].color.color);
        this.key += " stops(" + stops.map(function(stop) {
            var color = stop.color;
            return color.color + " alpha(" + color.opacity + ") at(" + stop.offset + ")";
        }).join(", ") + ")";
    };
    pv.extendType(Gradient, FillStyle);
    Gradient.prototype.rgb = function() {
        return this.stops.length ? this.stops[0].color : void 0;
    };
    Gradient.prototype.alpha = function(opacity) {
        return this._cloneWithStops(this.stops.map(function(stop) {
            return {
                offset: stop.offset,
                color: stop.color.alpha(opacity)
            };
        }));
    };
    Gradient.prototype.darker = function(k) {
        return this._cloneWithStops(this.stops.map(function(stop) {
            return {
                offset: stop.offset,
                color: stop.color.darker(k)
            };
        }));
    };
    Gradient.prototype.brighter = function(k) {
        return this._cloneWithStops(this.stops.map(function(stop) {
            return {
                offset: stop.offset,
                color: stop.color.brighter(k)
            };
        }));
    };
    Gradient.prototype.complementary = function() {
        return this._cloneWithStops(this.stops.map(function(stop) {
            return {
                offset: stop.offset,
                color: stop.color.complementary()
            };
        }));
    };
    Gradient.prototype.alphaBlend = function(mate) {
        return this._cloneWithStops(this.stops.map(function(stop) {
            return {
                offset: stop.offset,
                color: stop.color.alphaBlend(mate)
            };
        }));
    };
    Gradient.prototype.clone = function() {
        var Type = this.constructor, o = pv.extend(Type);
        o.constructor = Type;
        o.id = ++gradient_id;
        o.type = this.type;
        o.key = this.key;
        var stops = this.stops;
        o.stops = stops;
        stops.length && (o.color = stops[0].color.color);
        this._initClone(o);
        return o;
    };
    var LinearGradient = pv.FillStyle.LinearGradient = function(angle, stops) {
        Gradient.call(this, "lineargradient", stops);
        this.angle = angle;
        this.key += " angle(" + angle + ")";
    };
    pv.extendType(LinearGradient, Gradient);
    LinearGradient.prototype._cloneWithStops = function(stops) {
        return new LinearGradient(this.angle, stops);
    };
    LinearGradient.prototype._initClone = function(o) {
        o.angle = this.angle;
    };
    var RadialGradient = pv.FillStyle.RadialGradient = function(cx, cy, stops) {
        Gradient.call(this, "radialgradient", stops);
        this.cx = cx;
        this.cy = cy;
        this.key += " center(" + cx + "," + cy + ")";
    };
    pv.extendType(RadialGradient, Gradient);
    RadialGradient.prototype._cloneWithStops = function(stops) {
        return new RadialGradient(this.cx, this.cy, stops);
    };
    RadialGradient.prototype._initClone = function(o) {
        o.cx = this.cx;
        o.cy = this.cy;
    };
}();

pv.ramp = function() {
    var scale = pv.Scale.linear();
    scale.range.apply(scale, arguments);
    return scale;
};

pv.Scene = pv.SvgScene = {
    svg: "http://www.w3.org/2000/svg",
    xmlns: "http://www.w3.org/2000/xmlns",
    xlink: "http://www.w3.org/1999/xlink",
    xhtml: "http://www.w3.org/1999/xhtml",
    scale: 1,
    events: [ "DOMMouseScroll", "mousewheel", "mousedown", "mouseup", "mouseover", "mouseout", "mousemove", "click", "dblclick", "contextmenu" ],
    mousePositionEventSet: {
        mousedown: 1,
        mouseup: 1,
        mouseover: 1,
        mouseout: 1,
        mousemove: 1,
        click: 1,
        dblclick: 1,
        contextmenu: 1
    },
    implicit: {
        svg: {
            "shape-rendering": "auto",
            "pointer-events": "painted",
            x: 0,
            y: 0,
            dy: 0,
            "text-anchor": "start",
            transform: "translate(0,0)",
            fill: "none",
            "fill-opacity": 1,
            stroke: "none",
            "stroke-opacity": 1,
            "stroke-width": 1.5,
            "stroke-linejoin": "miter",
            "stroke-linecap": "butt",
            "stroke-miterlimit": 8,
            "stroke-dasharray": "none"
        },
        css: {
            font: "10px sans-serif"
        }
    }
};

pv.SvgScene.updateAll = function(scenes) {
    if (scenes.length && scenes[0].reverse && "line" !== scenes.type && "area" !== scenes.type) {
        for (var reversed = Object.create(scenes), i = 0, j = scenes.length - 1; j >= 0; i++, 
        j--) reversed[i] = scenes[j];
        scenes = reversed;
    }
    this.removeSiblings(this[scenes.type](scenes));
};

pv.SvgScene.create = function(type) {
    return document.createElementNS(this.svg, type);
};

pv.SvgScene.expect = function(e, type, scenes, i, attributes, style) {
    var tagName;
    if (e) {
        tagName = e.tagName;
        if ("defs" === tagName) {
            e = e.nextSibling;
            e && (tagName = e.tagName);
        } else "a" === tagName && (e = e.firstChild);
    }
    if (e) {
        if (tagName !== type) {
            var n = this.create(type);
            e.parentNode.replaceChild(n, e);
            e = n;
        }
    } else e = this.create(type);
    attributes && this.setAttributes(e, attributes);
    style && this.setStyle(e, style);
    return e;
};

pv.SvgScene.setAttributes = function(e, attributes) {
    var implicitSvg = this.implicit.svg, prevAttrs = e.__attributes__;
    prevAttrs === attributes && (prevAttrs = null);
    for (var name in attributes) {
        var value = attributes[name];
        prevAttrs && value === prevAttrs[name] || (null == value || value == implicitSvg[name] ? e.removeAttribute(name) : e.setAttribute(name, value));
    }
    e.__attributes__ = attributes;
};

pv.SvgScene.setStyle = function(e, style) {
    var implicitCss = this.implicit.css, prevStyle = e.__style__;
    prevStyle === style && (prevStyle = null);
    for (var name in style) {
        var value = style[name];
        prevStyle && value === prevStyle[name] || (null == value || value == implicitCss[name] ? e.style.removeProperty(name) : e.style[name] = value);
    }
    e.__style__ = style;
};

pv.SvgScene.append = function(e, scenes, index) {
    e.$scene = {
        scenes: scenes,
        index: index
    };
    e = this.title(e, scenes[index]);
    e.parentNode || scenes.$g.appendChild(e);
    return e.nextSibling;
};

pv.SvgScene.title = function(e, s) {
    var a = e.parentNode;
    a && "a" != a.tagName && (a = null);
    if (s.title) {
        if (!a) {
            a = this.create("a");
            a.setAttributeNS(this.xlink, "xlink:href", "");
            e.parentNode && e.parentNode.replaceChild(a, e);
            a.appendChild(e);
        }
        a.setAttributeNS(this.xlink, "xlink:title", s.title);
        for (var t = null, c = e.firstChild; null != c; c = c.nextSibling) if ("title" == c.nodeName) {
            t = c;
            break;
        }
        if (t) t.removeChild(t.firstChild); else {
            t = this.create("title");
            e.appendChild(t);
        }
        t.appendChild(document.createTextNode(s.title));
        return a;
    }
    a && a.parentNode.replaceChild(e, a);
    return e;
};

pv.SvgScene.dispatch = pv.listener(function(e) {
    var t = e.target.$scene;
    if (t) {
        var type = e.type;
        switch (type) {
          case "DOMMouseScroll":
            type = "mousewheel";
            e.wheel = -480 * e.detail;
            break;

          case "mousewheel":
            e.wheel = (window.opera ? 12 : 1) * e.wheelDelta;
        }
        if (pv.Mark.dispatch(type, t.scenes, t.index, e)) {
            e.preventDefault();
            e.stopPropagation();
        }
    }
});

pv.SvgScene.removeSiblings = function(e) {
    for (;e; ) {
        var n = e.nextSibling;
        "defs" !== e.nodeName && e.parentNode.removeChild(e);
        e = n;
    }
};

pv.SvgScene.undefined = function() {};

!function() {
    var dashAliasMap = {
        "-": "shortdash",
        ".": "shortdot",
        "-.": "shortdashdot",
        "-..": "shortdashdotdot",
        ". ": "dot",
        "- ": "dash",
        "--": "longdash",
        "- .": "dashdot",
        "--.": "longdashdot",
        "--..": "longdashdotdot"
    }, dashMap = {
        shortdash: [ 3, 1 ],
        shortdot: [ 1, 1 ],
        shortdashdot: [ 3, 1, 1, 1 ],
        shortdashdotdot: [ 3, 1, 1, 1, 1, 1 ],
        dot: [ 1, 3 ],
        dash: [ 4, 3 ],
        longdash: [ 8, 3 ],
        dashdot: [ 4, 3, 1, 3 ],
        longdashdot: [ 8, 3, 1, 3 ],
        longdashdotdot: [ 8, 3, 1, 3, 1, 3 ]
    };
    pv.SvgScene.isStandardDashStyle = function(dashArray) {
        return dashMap.hasOwnProperty(dashArray);
    };
    pv.SvgScene.translateDashStyleAlias = function(dashArray) {
        return dashAliasMap.hasOwnProperty(dashArray) ? dashAliasMap[dashArray] : dashArray;
    };
    pv.SvgScene.parseDasharray = function(s) {
        var dashArray = s.strokeDasharray;
        if (dashArray && "none" !== dashArray) {
            dashArray = this.translateDashStyleAlias(dashArray);
            var standardDashArray = dashMap[dashArray];
            dashArray = standardDashArray ? standardDashArray : dashArray.split(/[\s,]+/);
            var lineWidth = s.lineWidth, lineCap = s.lineCap || "butt", isButtCap = "butt" === lineCap;
            dashArray = dashArray.map(function(num, index) {
                num = +num;
                isButtCap || (index % 2 ? num++ : num -= 1);
                0 >= num && (num = .001);
                return num * lineWidth / this.scale;
            }, this).join(" ");
        } else dashArray = null;
        return dashArray;
    };
}();

!function() {
    var reTestUrlColor = /^url\(#/, next_gradient_id = 1, pi2 = Math.PI / 2, pi4 = pi2 / 2, sqrt22 = Math.SQRT2 / 2, abs = Math.abs, sin = Math.sin, cos = Math.cos, zr = function(x) {
        return abs(x) <= 1e-12 ? 0 : x;
    };
    pv.SvgScene.addFillStyleDefinition = function(scenes, fill) {
        if (fill.type && "solid" !== fill.type && !reTestUrlColor.test(fill.color)) {
            var rootMark = scenes.mark.root, fillStyleMap = rootMark.__fillStyleMap__ || (rootMark.__fillStyleMap__ = {}), k = fill.key, instId = fillStyleMap[k];
            if (!instId) {
                instId = fillStyleMap[k] = "__pvGradient" + next_gradient_id++;
                var elem = createGradientDef.call(this, scenes, fill, instId);
                rootMark.scene.$defs.appendChild(elem);
            }
            fill.color = "url(#" + instId + ")";
        }
    };
    var createGradientDef = function(scenes, fill, instId) {
        var isLinear = "lineargradient" === fill.type, elem = this.create(isLinear ? "linearGradient" : "radialGradient");
        elem.setAttribute("id", instId);
        if (isLinear) {
            var svgAngle = fill.angle - pi2, diagAngle = abs(svgAngle % pi2) - pi4, r = abs(sqrt22 * cos(diagAngle)), dirx = r * cos(svgAngle), diry = r * sin(svgAngle);
            elem.setAttribute("x1", zr(.5 - dirx));
            elem.setAttribute("y1", zr(.5 - diry));
            elem.setAttribute("x2", zr(.5 + dirx));
            elem.setAttribute("y2", zr(.5 + diry));
        }
        for (var stops = fill.stops, S = stops.length, i = 0; S > i; i++) {
            var stop = stops[i], stopElem = elem.appendChild(this.create("stop")), color = stop.color;
            stopElem.setAttribute("offset", stop.offset + "%");
            stopElem.setAttribute("stop-color", color.color);
            stopElem.setAttribute("stop-opacity", color.opacity + "");
        }
        return elem;
    };
}();

pv.SvgScene.pathBasis = function() {
    function weight(w, p0, p1, p2, p3) {
        return {
            x: w[0] * p0.left + w[1] * p1.left + w[2] * p2.left + w[3] * p3.left,
            y: w[0] * p0.top + w[1] * p1.top + w[2] * p2.top + w[3] * p3.top
        };
    }
    var basis = [ [ 1 / 6, 2 / 3, 1 / 6, 0 ], [ 0, 2 / 3, 1 / 3, 0 ], [ 0, 1 / 3, 2 / 3, 0 ], [ 0, 1 / 6, 2 / 3, 1 / 6 ] ], convert = function(p0, p1, p2, p3) {
        var b1 = weight(basis[1], p0, p1, p2, p3), b2 = weight(basis[2], p0, p1, p2, p3), b3 = weight(basis[3], p0, p1, p2, p3);
        return "C" + b1.x + "," + b1.y + "," + b2.x + "," + b2.y + "," + b3.x + "," + b3.y;
    };
    convert.segment = function(p0, p1, p2, p3) {
        var b0 = weight(basis[0], p0, p1, p2, p3), b1 = weight(basis[1], p0, p1, p2, p3), b2 = weight(basis[2], p0, p1, p2, p3), b3 = weight(basis[3], p0, p1, p2, p3);
        return [ "M" + b0.x + "," + b0.y, "C" + b1.x + "," + b1.y + "," + b2.x + "," + b2.y + "," + b3.x + "," + b3.y ];
    };
    return convert;
}();

pv.SvgScene.curveBasis = function(points, from, to) {
    var L;
    if (null == from) {
        L = points.length;
        from = 0;
        to = L - 1;
    } else L = to - from + 1;
    if (2 >= L) return "";
    var path = "", p0 = points[from], p1 = p0, p2 = p0, p3 = points[from + 1];
    path += this.pathBasis(p0, p1, p2, p3);
    for (var i = from + 2; to >= i; i++) {
        p0 = p1;
        p1 = p2;
        p2 = p3;
        p3 = points[i];
        path += this.pathBasis(p0, p1, p2, p3);
    }
    path += this.pathBasis(p1, p2, p3, p3);
    path += this.pathBasis(p2, p3, p3, p3);
    return path;
};

pv.SvgScene.curveBasisSegments = function(points, from, to) {
    var L;
    if (null == from) {
        L = points.length;
        from = 0;
        to = L - 1;
    } else L = to - from + 1;
    if (2 >= L) return "";
    var paths = [], p0 = points[from], p1 = p0, p2 = p0, p3 = points[from + 1], firstPath = this.pathBasis.segment(p0, p1, p2, p3);
    p0 = p1;
    p1 = p2;
    p2 = p3;
    p3 = points[from + 2];
    firstPath[1] += this.pathBasis(p0, p1, p2, p3);
    paths.push(firstPath);
    for (var i = from + 3; to >= i; i++) {
        p0 = p1;
        p1 = p2;
        p2 = p3;
        p3 = points[i];
        paths.push(this.pathBasis.segment(p0, p1, p2, p3));
    }
    var lastPath = this.pathBasis.segment(p1, p2, p3, p3);
    lastPath[1] += this.pathBasis(p2, p3, p3, p3);
    paths.push(lastPath);
    return paths;
};

pv.SvgScene.curveHermite = function(points, tangents, from, to) {
    var L;
    if (null == from) {
        L = points.length;
        from = 0;
        to = L - 1;
    } else L = to - from + 1;
    var T = tangents.length;
    if (1 > T || L !== T && L !== T + 2) return "";
    var quad = L !== T, path = "", p0 = points[from], p = points[from + 1], t0 = tangents[0], t = t0, pi = from + 1;
    if (quad) {
        path += "Q" + (p.left - 2 * t0.x / 3) + "," + (p.top - 2 * t0.y / 3) + "," + p.left + "," + p.top;
        p0 = points[from + 1];
        pi = from + 2;
    }
    if (T > 1) {
        t = tangents[1];
        p = points[pi];
        pi++;
        path += "C" + (p0.left + t0.x) + "," + (p0.top + t0.y) + "," + (p.left - t.x) + "," + (p.top - t.y) + "," + p.left + "," + p.top;
        for (var i = 2; T > i; i++, pi++) {
            p = points[pi];
            t = tangents[i];
            path += "S" + (p.left - t.x) + "," + (p.top - t.y) + "," + p.left + "," + p.top;
        }
    }
    if (quad) {
        var lp = points[pi];
        path += "Q" + (p.left + 2 * t.x / 3) + "," + (p.top + 2 * t.y / 3) + "," + lp.left + "," + lp.top;
    }
    return path;
};

pv.SvgScene.curveHermiteSegments = function(points, tangents, from, to) {
    var L;
    if (null == from) {
        L = points.length;
        from = 0;
        to = L - 1;
    } else L = to - from + 1;
    var T = tangents.length;
    if (1 > T || L !== T && L !== T + 2) return [];
    var quad = L !== T, paths = [], p0 = points[from], p = p0, t0 = tangents[0], t = t0, pi = from + 1;
    if (quad) {
        p = points[from + 1];
        paths.push([ "M" + p0.left + "," + p0.top, "Q" + (p.left - 2 * t.x / 3) + "," + (p.top - 2 * t.y / 3) + "," + p.left + "," + p.top ]);
        pi = from + 2;
    }
    for (var i = 1; T > i; i++, pi++) {
        p0 = p;
        t0 = t;
        p = points[pi];
        t = tangents[i];
        paths.push([ "M" + p0.left + "," + p0.top, "C" + (p0.left + t0.x) + "," + (p0.top + t0.y) + "," + (p.left - t.x) + "," + (p.top - t.y) + "," + p.left + "," + p.top ]);
    }
    if (quad) {
        var lp = points[pi];
        paths.push([ "M" + p.left + "," + p.top, "Q" + (p.left + 2 * t.x / 3) + "," + (p.top + 2 * t.y / 3) + "," + lp.left + "," + lp.top ]);
    }
    return paths;
};

pv.SvgScene.cardinalTangents = function(points, tension, from, to) {
    var L;
    if (null == from) {
        L = points.length;
        from = 0;
        to = L - 1;
    } else L = to - from + 1;
    for (var tangents = [], a = (1 - tension) / 2, p0 = points[from], p1 = points[from + 1], p2 = points[from + 2], i = from + 3; to >= i; i++) {
        tangents.push({
            x: a * (p2.left - p0.left),
            y: a * (p2.top - p0.top)
        });
        p0 = p1;
        p1 = p2;
        p2 = points[i];
    }
    tangents.push({
        x: a * (p2.left - p0.left),
        y: a * (p2.top - p0.top)
    });
    return tangents;
};

pv.SvgScene.curveCardinal = function(points, tension, from, to) {
    var L;
    if (null == from) {
        L = points.length;
        from = 0;
        to = L - 1;
    } else L = to - from + 1;
    return 2 >= L ? "" : this.curveHermite(points, this.cardinalTangents(points, tension, from, to), from, to);
};

pv.SvgScene.curveCardinalSegments = function(points, tension, from, to) {
    var L;
    if (null == from) {
        L = points.length;
        from = 0;
        to = L - 1;
    } else L = to - from + 1;
    return 2 >= L ? "" : this.curveHermiteSegments(points, this.cardinalTangents(points, tension, from, to), from, to);
};

pv.SvgScene.monotoneTangents = function(points, from, to) {
    var L;
    if (null == from) {
        L = points.length;
        from = 0;
        to = L - 1;
    } else L = to - from + 1;
    var j, tangents = [], d = [], m = [], dx = [], k = 0;
    for (k = 0; L - 1 > k; k++) {
        j = from + k;
        var den = points[j + 1].left - points[j].left;
        d[k] = Math.abs(den) <= 1e-12 ? 0 : (points[j + 1].top - points[j].top) / den;
    }
    m[0] = d[0];
    dx[0] = points[from + 1].left - points[from].left;
    for (k = 1, j = from + k; L - 1 > k; k++, j++) {
        m[k] = (d[k - 1] + d[k]) / 2;
        dx[k] = (points[j + 1].left - points[j - 1].left) / 2;
    }
    m[k] = d[k - 1];
    dx[k] = points[j].left - points[j - 1].left;
    for (k = 0; L - 1 > k; k++) if (0 == d[k]) {
        m[k] = 0;
        m[k + 1] = 0;
    }
    for (k = 0; L - 1 > k; k++) if (!(Math.abs(m[k]) < 1e-5 || Math.abs(m[k + 1]) < 1e-5)) {
        var ak = m[k] / d[k], bk = m[k + 1] / d[k], s = ak * ak + bk * bk;
        if (s > 9) {
            var tk = 3 / Math.sqrt(s);
            m[k] = tk * ak * d[k];
            m[k + 1] = tk * bk * d[k];
        }
    }
    for (var len, i = 0; L > i; i++) {
        len = 1 + m[i] * m[i];
        tangents.push({
            x: dx[i] / 3 / len,
            y: m[i] * dx[i] / 3 / len
        });
    }
    return tangents;
};

pv.SvgScene.curveMonotone = function(points, from, to) {
    var L;
    if (null == from) {
        L = points.length;
        from = 0;
        to = L - 1;
    } else L = to - from + 1;
    return 2 >= L ? "" : this.curveHermite(points, this.monotoneTangents(points, from, to), from, to);
};

pv.SvgScene.curveMonotoneSegments = function(points, from, to) {
    var L;
    if (null == from) {
        L = points.length;
        from = 0;
        to = L - 1;
    } else L = to - from + 1;
    return 2 >= L ? "" : this.curveHermiteSegments(points, this.monotoneTangents(points, from, to), from, to);
};

pv.SvgScene.area = function(scenes) {
    var e = scenes.$g.firstChild, count = scenes.length;
    if (!count) return e;
    var s = scenes[0];
    return "smart" === s.segmented ? this.areaSegmentedSmart(e, scenes) : s.segmented ? this.areaSegmentedFull(e, scenes) : this.areaFixed(e, scenes, 0, count - 1, !0);
};

pv.SvgScene.areaFixed = function(elm, scenes, from, to, addEvents) {
    var count = to - from + 1;
    if (1 === count) return this.lineAreaDotAlone(elm, scenes, from);
    var s = scenes[from];
    if (!s.visible) return elm;
    var fill = s.fillStyle, stroke = s.strokeStyle;
    if (!fill.opacity && !stroke.opacity) return elm;
    this.addFillStyleDefinition(scenes, fill);
    this.addFillStyleDefinition(scenes, stroke);
    var isInterpBasis = !1, isInterpCardinal = !1, isInterpMonotone = !1, isInterpStepAfter = !1, isInterpStepBefore = !1;
    switch (s.interpolate) {
      case "basis":
        isInterpBasis = !0;
        break;

      case "cardinal":
        isInterpCardinal = !0;
        break;

      case "monotone":
        isInterpMonotone = !0;
        break;

      case "step-after":
        isInterpStepAfter = !0;
        break;

      case "step-before":
        isInterpStepBefore = !0;
    }
    for (var si, sj, isInterpBasisCardinalOrMonotone = isInterpBasis || isInterpCardinal || isInterpMonotone, d = [], i = from; to >= i; i++) {
        si = scenes[i];
        if (si.width || si.height) {
            for (var j = i + 1; to >= j; j++) {
                sj = scenes[j];
                if (!sj.width && !sj.height) break;
            }
            i > from && !isInterpStepAfter && i--;
            to >= j && !isInterpStepBefore && j++;
            var fun = isInterpBasisCardinalOrMonotone && j - i > 2 ? this.areaPathCurve : this.areaPathStraight;
            d.push(fun.call(this, scenes, i, j - 1, s));
            i = j - 1;
        }
    }
    if (!d.length) return elm;
    var sop = stroke.opacity;
    elm = this.expect(elm, "path", scenes, from, {
        "shape-rendering": s.antialias ? null : "crispEdges",
        "pointer-events": addEvents ? s.events : "none",
        cursor: s.cursor,
        d: "M" + d.join("ZM") + "Z",
        fill: fill.color,
        "fill-opacity": fill.opacity || null,
        stroke: stroke.color,
        "stroke-opacity": sop || null,
        "stroke-width": sop ? s.lineWidth / this.scale : null,
        "stroke-linecap": s.lineCap,
        "stroke-linejoin": s.lineJoin,
        "stroke-miterlimit": s.strokeMiterLimit,
        "stroke-dasharray": sop ? this.parseDasharray(s) : null
    });
    s.svg && this.setAttributes(elm, s.svg);
    s.css && this.setStyle(elm, s.css);
    return this.append(elm, scenes, from);
};

pv.SvgScene.areaSegmentedSmart = function(elm, scenes) {
    return this.eachLineAreaSegment(elm, scenes, function(elm, scenes, from, to) {
        var segment = this.areaSegmentPaths(scenes, from, to), pathsT = segment.top, pathsB = segment.bottom, fromp = from, options = {
            breakOnKeyChange: !0,
            from: from,
            to: to
        };
        return this.eachLineAreaSegment(elm, scenes, options, function(elm, scenes, from, to, ka, eventsMax) {
            var s1 = scenes[from], fill = s1.fillStyle, stroke = s1.strokeStyle;
            this.addFillStyleDefinition(scenes, fill);
            this.addFillStyleDefinition(scenes, stroke);
            if (from === to) return this.lineAreaDotAlone(elm, scenes, from);
            var d = this.areaJoinPaths(pathsT, pathsB, from - fromp, to - fromp - 1), sop = stroke.opacity, attrs = {
                "shape-rendering": s1.antialias ? null : "crispEdges",
                "pointer-events": eventsMax,
                cursor: s1.cursor,
                d: d,
                fill: fill.color,
                "fill-opacity": fill.opacity || null,
                stroke: stroke.color,
                "stroke-opacity": sop || null,
                "stroke-width": sop ? s1.lineWidth / this.scale : null,
                "stroke-linecap": s1.lineCap,
                "stroke-linejoin": s1.lineJoin,
                "stroke-miterlimit": s1.strokeMiterLimit,
                "stroke-dasharray": sop ? this.parseDasharray(s1) : null
            };
            elm = this.expect(elm, "path", scenes, from, attrs, s1.css);
            return this.append(elm, scenes, from);
        });
    });
};

pv.SvgScene.areaSegmentPaths = function(scenes, from, to) {
    return this.areaSegmentCurvePaths(scenes, from, to) || this.areaSegmentStraightPaths(scenes, from, to);
};

pv.SvgScene.areaSegmentCurvePaths = function(scenes, from, to) {
    var count = to - from + 1, s = scenes[from], isBasis = "basis" === s.interpolate, isCardinal = !isBasis && "cardinal" === s.interpolate;
    if (isBasis || isCardinal || "monotone" == s.interpolate) {
        for (var pointsT = [], pointsB = [], i = 0; count > i; i++) {
            var si = scenes[from + i], sj = scenes[to - i];
            pointsT.push(si);
            pointsB.push({
                left: sj.left + sj.width,
                top: sj.top + sj.height
            });
        }
        var pathsT, pathsB;
        if (isBasis) {
            pathsT = this.curveBasisSegments(pointsT);
            pathsB = this.curveBasisSegments(pointsB);
        } else if (isCardinal) {
            pathsT = this.curveCardinalSegments(pointsT, s.tension);
            pathsB = this.curveCardinalSegments(pointsB, s.tension);
        } else {
            pathsT = this.curveMonotoneSegments(pointsT);
            pathsB = this.curveMonotoneSegments(pointsB);
        }
        if (pathsT || pathsT.length) return {
            from: from,
            top: pathsT,
            bottom: pathsB
        };
    }
};

pv.SvgScene.areaSegmentStraightPaths = function(scenes, i, j) {
    for (var pathsT = [], pathsB = [], k = j, m = i; k > i; i++, j--) {
        var si = scenes[i], sj = scenes[j], pi = [ "M" + si.left + "," + si.top ], pj = [ "M" + (sj.left + sj.width) + "," + (sj.top + sj.height) ], sk = scenes[i + 1], sl = scenes[j - 1];
        switch (si.interpolate) {
          case "step-before":
            pi.push("V" + sk.top + "H" + sk.left);
            break;

          case "step-after":
            pi.push("H" + sk.left + "V" + sk.top);
            break;

          default:
            pi.push("L" + sk.left + "," + sk.top);
        }
        pj.push("L" + (sl.left + sl.width) + "," + (sl.top + sl.height));
        pathsT.push(pi);
        pathsB.push(pj);
    }
    return {
        from: m,
        top: pathsT,
        bottom: pathsB
    };
};

pv.SvgScene.areaJoinPaths = function(pathsT, pathsB, i, j) {
    for (var fullPathT = "", fullPathB = "", N = pathsT.length, k = i, l = N - 1 - j; j >= k; k++, 
    l++) {
        var dT, dB, pathT = pathsT[k], pathB = pathsB[l];
        if (k === i) {
            dT = pathT.join("");
            dB = "L" + pathB[0].substr(1) + pathB[1];
        } else {
            dT = pathT[1];
            dB = pathB[1];
        }
        fullPathT += dT;
        fullPathB += dB;
    }
    return fullPathT + fullPathB + "Z";
};

pv.SvgScene.areaSegmentedFull = function(e, scenes) {
    var pathsT, pathsB, count = scenes.length, result = this.areaSegmentCurvePaths(scenes, 0, count - 1);
    if (result) {
        pathsT = result.top;
        pathsB = result.bottom;
    }
    for (var i = (scenes[0], 0); count - 1 > i; i++) {
        var s1 = scenes[i], s2 = scenes[i + 1];
        if (s1.visible && s2.visible) {
            var fill = s1.fillStyle, stroke = s1.strokeStyle;
            if (fill.opacity || stroke.opacity) {
                var d;
                if (pathsT) {
                    var pathT = pathsT[i].join(""), pathB = "L" + pathsB[count - i - 2].join("").substr(1);
                    d = pathT + pathB + "Z";
                } else {
                    var si = s1, sj = s2;
                    switch (s1.interpolate) {
                      case "step-before":
                        si = s2;
                        break;

                      case "step-after":
                        sj = s1;
                    }
                    d = "M" + s1.left + "," + si.top + "L" + s2.left + "," + sj.top + "L" + (s2.left + s2.width) + "," + (sj.top + sj.height) + "L" + (s1.left + s1.width) + "," + (si.top + si.height) + "Z";
                }
                var attrs = {
                    "shape-rendering": s1.antialias ? null : "crispEdges",
                    "pointer-events": s1.events,
                    cursor: s1.cursor,
                    d: d,
                    fill: fill.color,
                    "fill-opacity": fill.opacity || null,
                    stroke: stroke.color,
                    "stroke-opacity": stroke.opacity || null,
                    "stroke-width": stroke.opacity ? s1.lineWidth / this.scale : null
                };
                e = this.expect(e, "path", scenes, i, attrs);
                s1.svg && this.setAttributes(e, s1.svg);
                s1.css && this.setStyle(e, s1.css);
                e = this.append(e, scenes, i);
            }
        }
    }
    return e;
};

pv.SvgScene.areaPathStraight = function(scenes, i, j, s) {
    for (var pointsT = [], pointsB = [], k = j; k >= i; i++, j--) {
        var si = scenes[i], sj = scenes[j], pi = si.left + "," + si.top, pj = sj.left + sj.width + "," + (sj.top + sj.height);
        if (k > i) {
            var sk = scenes[i + 1], sl = scenes[j - 1];
            switch (s.interpolate) {
              case "step-before":
                pi += "V" + sk.top;
                pj += "H" + (sl.left + sl.width);
                break;

              case "step-after":
                pi += "H" + sk.left;
                pj += "V" + (sl.top + sl.height);
            }
        }
        pointsT.push(pi);
        pointsB.push(pj);
    }
    return pointsT.concat(pointsB).join("L");
};

pv.SvgScene.areaPathCurve = function(scenes, i, j, s) {
    for (var pathT, pathB, pointsT = [], pointsB = [], k = j; k >= i; i++, j--) {
        var sj = scenes[j];
        pointsT.push(scenes[i]);
        pointsB.push({
            left: sj.left + sj.width,
            top: sj.top + sj.height
        });
    }
    switch (s.interpolate) {
      case "basis":
        pathT = this.curveBasis(pointsT);
        pathB = this.curveBasis(pointsB);
        break;

      case "cardinal":
        pathT = this.curveCardinal(pointsT, s.tension);
        pathB = this.curveCardinal(pointsB, s.tension);
        break;

      default:
        pathT = this.curveMonotone(pointsT);
        pathB = this.curveMonotone(pointsB);
    }
    return pointsT[0].left + "," + pointsT[0].top + pathT + "L" + pointsB[0].left + "," + pointsB[0].top + pathB;
};

pv.SvgScene.minBarWidth = 1;

pv.SvgScene.minBarHeight = 1;

pv.SvgScene.minBarLineWidth = .2;

pv.SvgScene.bar = function(scenes) {
    for (var e = scenes.$g.firstChild, i = 0; i < scenes.length; i++) {
        var s = scenes[i];
        if (!(!s.visible || Math.abs(s.width) <= 1e-10 || Math.abs(s.height) <= 1e-10)) {
            s.width < this.minBarWidth && (s.width = this.minBarWidth);
            s.height < this.minBarHeight && (s.height = this.minBarHeight);
            var fill = s.fillStyle, stroke = s.strokeStyle;
            if (fill.opacity || stroke.opacity) {
                this.addFillStyleDefinition(scenes, fill);
                this.addFillStyleDefinition(scenes, stroke);
                var lineWidth;
                if (stroke.opacity) {
                    lineWidth = s.lineWidth;
                    lineWidth = 1e-10 > lineWidth ? 0 : Math.max(this.minBarLineWidth, lineWidth / this.scale);
                } else lineWidth = null;
                e = this.expect(e, "rect", scenes, i, {
                    "shape-rendering": s.antialias ? null : "crispEdges",
                    "pointer-events": s.events,
                    cursor: s.cursor,
                    x: s.left,
                    y: s.top,
                    width: Math.max(1e-10, s.width),
                    height: Math.max(1e-10, s.height),
                    fill: fill.color,
                    "fill-opacity": fill.opacity || null,
                    stroke: stroke.color,
                    "stroke-opacity": stroke.opacity || null,
                    "stroke-width": lineWidth,
                    "stroke-linecap": s.lineCap,
                    "stroke-dasharray": stroke.opacity ? this.parseDasharray(s) : null
                });
                s.svg && this.setAttributes(e, s.svg);
                s.css && this.setStyle(e, s.css);
                e = this.append(e, scenes, i);
            }
        }
    }
    return e;
};

pv.SvgScene.dot = function(scenes) {
    for (var e = scenes.$g.firstChild, i = 0, L = scenes.length; L > i; i++) {
        var s = scenes[i];
        if (s.visible) {
            var fill = s.fillStyle, fillOp = fill.opacity, stroke = s.strokeStyle, strokeOp = stroke.opacity;
            if (fillOp || strokeOp) {
                this.addFillStyleDefinition(scenes, fill);
                this.addFillStyleDefinition(scenes, stroke);
                var svg = {
                    "shape-rendering": s.antialias ? null : "crispEdges",
                    "pointer-events": s.events,
                    cursor: s.cursor,
                    fill: fill.color,
                    "fill-opacity": fillOp || null,
                    stroke: stroke.color,
                    "stroke-opacity": strokeOp || null,
                    "stroke-width": strokeOp ? s.lineWidth / this.scale : null,
                    "stroke-linecap": s.lineCap,
                    "stroke-dasharray": strokeOp ? this.parseDasharray(s) : null
                }, shape = s.shape || "circle", ar = s.aspectRatio, sa = s.shapeAngle, t = null;
                if ("circle" === shape) if (1 === ar) {
                    svg.cx = s.left;
                    svg.cy = s.top;
                    svg.r = s.shapeRadius;
                } else {
                    shape = "ellipse";
                    svg.cx = svg.cy = 0;
                    t = "translate(" + s.left + "," + s.top + ") ";
                    sa && (t += "rotate(" + pv.degrees(sa) + ") ");
                    svg.rx = s._width / 2;
                    svg.ry = s._height / 2;
                } else {
                    var r = s.shapeRadius, rx = r, ry = r;
                    if (ar > 0 && 1 !== ar) {
                        var sy = 1 / Math.sqrt(ar), sx = ar * sy;
                        rx *= sx;
                        ry *= sy;
                    }
                    svg.d = this.renderSymbol(shape, s, rx, ry);
                    shape = "path";
                    t = "translate(" + s.left + "," + s.top + ") ";
                    sa && (t += "rotate(" + pv.degrees(sa) + ") ");
                }
                t && (svg.transform = t);
                e = this.expect(e, shape, scenes, i, svg);
                s.svg && this.setAttributes(e, s.svg);
                s.css && this.setStyle(e, s.css);
                e = this.append(e, scenes, i);
            }
        }
    }
    return e;
};

!function(S) {
    var _renderersBySymName = {};
    S.registerSymbol = function(symName, funRenderer) {
        _renderersBySymName[symName] = funRenderer;
        return S;
    };
    S.renderSymbol = function(symName, instance, rx, ry) {
        return _renderersBySymName[symName].call(S, instance, symName, rx, ry);
    };
    S.hasSymbol = function(symName) {
        return _renderersBySymName.hasOwnProperty(symName);
    };
    S.symbols = function() {
        return pv.keys(_renderersBySymName);
    };
    var C1 = 2 / Math.sqrt(3);
    S.registerSymbol("circle", function() {
        throw new Error("Not implemented as a symbol");
    }).registerSymbol("cross", function(s, name, rx, ry) {
        var rxn = (s.shapeRadius, -rx), ryn = -ry;
        return "M" + rxn + "," + ryn + "L" + rx + "," + ry + "M" + rx + "," + ryn + "L" + rxn + "," + ry;
    }).registerSymbol("triangle", function(s, name, rx, ry) {
        var hp = ry, wp = rx * C1, hn = -ry, wn = -wp;
        return "M0," + hp + "L" + wp + "," + hn + " " + wn + "," + hn + "Z";
    }).registerSymbol("diamond", function(s, name, rx, ry) {
        var rxp = rx * Math.SQRT2, ryp = ry * Math.SQRT2, rxn = -rxp, ryn = -ryp;
        return "M0," + ryn + "L" + rxp + ",0 0," + ryp + " " + rxn + ",0Z";
    }).registerSymbol("square", function(s, name, rx, ry) {
        var rxn = -rx, ryn = -ry;
        return "M" + rxn + "," + ryn + "L" + rx + "," + ryn + " " + rx + "," + ry + " " + rxn + "," + ry + "Z";
    }).registerSymbol("tick", function(s, name, rx, ry) {
        var ry2 = -ry * ry;
        return "M0,0L0," + ry2;
    }).registerSymbol("bar", function(s, name, rx, ry) {
        var z2 = ry * ry / 2;
        return "M0," + z2 + "L0," + -z2;
    });
}(pv.SvgScene);

pv.SvgScene.image = function(scenes) {
    for (var e = scenes.$g.firstChild, i = 0; i < scenes.length; i++) {
        var s = scenes[i];
        if (s.visible) {
            e = this.fill(e, scenes, i);
            if (s.image) {
                e = this.expect(e, "foreignObject", scenes, i, {
                    cursor: s.cursor,
                    x: s.left,
                    y: s.top,
                    width: s.width,
                    height: s.height
                });
                s.svg && this.setAttributes(e, s.svg);
                s.css && this.setStyle(e, s.css);
                var c = e.firstChild || e.appendChild(document.createElementNS(this.xhtml, "canvas"));
                c.$scene = {
                    scenes: scenes,
                    index: i
                };
                c.style.width = s.width;
                c.style.height = s.height;
                c.width = s.imageWidth;
                c.height = s.imageHeight;
                c.getContext("2d").putImageData(s.image, 0, 0);
            } else {
                e = this.expect(e, "image", scenes, i, {
                    preserveAspectRatio: "none",
                    cursor: s.cursor,
                    x: s.left,
                    y: s.top,
                    width: s.width,
                    height: s.height
                });
                s.svg && this.setAttributes(e, s.svg);
                s.css && this.setStyle(e, s.css);
                e.setAttributeNS(this.xlink, "xlink:href", s.url);
            }
            e = this.append(e, scenes, i);
            e = this.stroke(e, scenes, i);
        }
    }
    return e;
};

pv.SvgScene.label = function(scenes) {
    for (var e = scenes.$g.firstChild, i = 0; i < scenes.length; i++) {
        var s = scenes[i];
        if (s.visible) {
            var fill = s.textStyle;
            if (fill.opacity && s.text) {
                var x = 0, y = 0, dy = 0, anchor = "start";
                switch (s.textBaseline) {
                  case "middle":
                    dy = ".35em";
                    break;

                  case "top":
                    dy = ".71em";
                    y = s.textMargin;
                    break;

                  case "bottom":
                    y = "-" + s.textMargin;
                }
                switch (s.textAlign) {
                  case "right":
                    anchor = "end";
                    x = "-" + s.textMargin;
                    break;

                  case "center":
                    anchor = "middle";
                    break;

                  case "left":
                    x = s.textMargin;
                }
                e = this.expect(e, "text", scenes, i, {
                    "pointer-events": s.events,
                    cursor: s.cursor,
                    x: x,
                    y: y,
                    dy: dy,
                    transform: "translate(" + s.left + "," + s.top + ")" + (s.textAngle ? " rotate(" + 180 * s.textAngle / Math.PI + ")" : "") + (1 != this.scale ? " scale(" + 1 / this.scale + ")" : ""),
                    fill: fill.color,
                    "fill-opacity": fill.opacity || null,
                    "text-anchor": anchor
                }, {
                    font: s.font,
                    "text-shadow": s.textShadow,
                    "text-decoration": s.textDecoration
                });
                s.svg && this.setAttributes(e, s.svg);
                s.css && this.setStyle(e, s.css);
                e.firstChild ? e.firstChild.nodeValue = s.text : e.appendChild(document.createTextNode(s.text));
                e = this.append(e, scenes, i);
            }
        }
    }
    return e;
};

pv.SvgScene.line = function(scenes) {
    var e = scenes.$g.firstChild, count = scenes.length;
    if (!count) return e;
    var s = scenes[0];
    return "smart" === s.segmented ? this.lineSegmentedSmart(e, scenes) : 2 > count ? e : s.segmented ? this.lineSegmentedFull(e, scenes) : this.lineFixed(e, scenes);
};

pv.SvgScene.lineFixed = function(elm, scenes) {
    var count = scenes.length;
    if (1 === count) return this.lineAreaDotAlone(elm, scenes, 0);
    var s = scenes[0];
    if (!s.visible) return elm;
    var fill = s.fillStyle, stroke = s.strokeStyle;
    if (!fill.opacity && !stroke.opacity) return elm;
    this.addFillStyleDefinition(scenes, fill);
    this.addFillStyleDefinition(scenes, stroke);
    var d = "M" + s.left + "," + s.top, curveInterpolated = count > 2;
    if (curveInterpolated) switch (s.interpolate) {
      case "basis":
        d += this.curveBasis(scenes);
        break;

      case "cardinal":
        d += this.curveCardinal(scenes, s.tension);
        break;

      case "monotone":
        d += this.curveMonotone(scenes);
        break;

      default:
        curveInterpolated = !1;
    }
    if (!curveInterpolated) for (var i = 1; count > i; i++) d += this.lineSegmentPath(scenes[i - 1], scenes[i]);
    var sop = stroke.opacity, attrs = {
        "shape-rendering": s.antialias ? null : "crispEdges",
        "pointer-events": s.events,
        cursor: s.cursor,
        d: d,
        fill: fill.color,
        "fill-opacity": fill.opacity || null,
        stroke: stroke.color,
        "stroke-opacity": sop || null,
        "stroke-width": sop ? s.lineWidth / this.scale : null,
        "stroke-linecap": s.lineCap,
        "stroke-linejoin": s.lineJoin,
        "stroke-miterlimit": s.strokeMiterLimit,
        "stroke-dasharray": sop ? this.parseDasharray(s) : null
    };
    elm = this.expect(elm, "path", scenes, 0, attrs, s.css);
    s.svg && this.setAttributes(elm, s.svg);
    return this.append(elm, scenes, 0);
};

pv.SvgScene.lineSegmentedSmart = function(elm, scenes) {
    return this.eachLineAreaSegment(elm, scenes, function(elm, scenes, from, to) {
        var paths = this.lineSegmentPaths(scenes, from, to), fromp = from, options = {
            breakOnKeyChange: !0,
            from: from,
            to: to
        };
        return this.eachLineAreaSegment(elm, scenes, options, function(elm, scenes, from, to, ka, eventsMax) {
            var s1 = scenes[from], fill = s1.fillStyle;
            this.addFillStyleDefinition(scenes, fill);
            var stroke = s1.strokeStyle;
            this.addFillStyleDefinition(scenes, stroke);
            if (from === to) return this.lineAreaDotAlone(elm, scenes, from);
            var d = this.lineJoinPaths(paths, from - fromp, to - fromp - 1), sop = stroke.opacity, attrs = {
                "shape-rendering": s1.antialias ? null : "crispEdges",
                "pointer-events": eventsMax,
                cursor: s1.cursor,
                d: d,
                fill: fill.color,
                "fill-opacity": fill.opacity || null,
                stroke: stroke.color,
                "stroke-opacity": sop || null,
                "stroke-width": sop ? s1.lineWidth / this.scale : null,
                "stroke-linecap": s1.lineCap,
                "stroke-linejoin": s1.lineJoin,
                "stroke-miterlimit": s1.strokeMiterLimit,
                "stroke-dasharray": sop ? this.parseDasharray(s1) : null
            };
            elm = this.expect(elm, "path", scenes, from, attrs, s1.css);
            return this.append(elm, scenes, from);
        });
    });
};

pv.SvgScene.lineSegmentedFull = function(e, scenes) {
    var paths, s = scenes[0];
    switch (s.interpolate) {
      case "basis":
        paths = this.curveBasisSegments(scenes);
        break;

      case "cardinal":
        paths = this.curveCardinalSegments(scenes, s.tension);
        break;

      case "monotone":
        paths = this.curveMonotoneSegments(scenes);
    }
    for (var i = 0, n = scenes.length - 1; n > i; i++) {
        var s1 = scenes[i], s2 = scenes[i + 1];
        if (s1.visible && s2.visible) {
            var stroke = s1.strokeStyle, fill = pv.FillStyle.transparent;
            if (stroke.opacity) {
                var d;
                if ("linear" == s1.interpolate && "miter" == s1.lineJoin) {
                    fill = stroke;
                    stroke = pv.FillStyle.transparent;
                    d = this.pathJoin(scenes[i - 1], s1, s2, scenes[i + 2]);
                } else d = paths ? paths[i].join("") : "M" + s1.left + "," + s1.top + this.lineSegmentPath(s1, s2);
                e = this.expect(e, "path", scenes, i, {
                    "shape-rendering": s1.antialias ? null : "crispEdges",
                    "pointer-events": s1.events,
                    cursor: s1.cursor,
                    d: d,
                    fill: fill.color,
                    "fill-opacity": fill.opacity || null,
                    stroke: stroke.color,
                    "stroke-opacity": stroke.opacity || null,
                    "stroke-width": stroke.opacity ? s1.lineWidth / this.scale : null,
                    "stroke-linejoin": s1.lineJoin
                });
                s1.svg && this.setAttributes(e, s1.svg);
                s1.css && this.setStyle(e, s1.css);
                e = this.append(e, scenes, i);
            }
        }
    }
    return e;
};

pv.SvgScene.lineSegmentPath = function(s1, s2) {
    var l = 1;
    switch (s1.interpolate) {
      case "polar-reverse":
        l = 0;

      case "polar":
        var dx = s2.left - s1.left, dy = s2.top - s1.top, e = 1 - s1.eccentricity, r = Math.sqrt(dx * dx + dy * dy) / (2 * e);
        if (0 >= e || e > 1) break;
        return "A" + r + "," + r + " 0 0," + l + " " + s2.left + "," + s2.top;

      case "step-before":
        return "V" + s2.top + "H" + s2.left;

      case "step-after":
        return "H" + s2.left + "V" + s2.top;
    }
    return "L" + s2.left + "," + s2.top;
};

pv.SvgScene.lineSegmentPaths = function(scenes, from, to) {
    var paths, s = scenes[from];
    switch (s.interpolate) {
      case "basis":
        paths = this.curveBasisSegments(scenes, from, to);
        break;

      case "cardinal":
        paths = this.curveCardinalSegments(scenes, s.tension, from, to);
        break;

      case "monotone":
        paths = this.curveMonotoneSegments(scenes, from, to);
    }
    if (!paths || !paths.length) {
        paths = [];
        for (var i = from + 1; to >= i; i++) {
            var s1 = scenes[i - 1], s2 = scenes[i];
            paths.push([ "M" + s1.left + "," + s1.top, this.lineSegmentPath(s1, s2) ]);
        }
    }
    return paths;
};

pv.strokeMiterLimit = 4;

pv.SvgScene.pathJoin = function(s0, s1, s2, s3) {
    var miterLimit, miterRatio, miterLength, pts = [], w1 = s1.lineWidth / this.scale, p1 = pv.vector(s1.left, s1.top), p2 = pv.vector(s2.left, s2.top), p21 = p2.minus(p1), v21 = p21.perp().norm(), w21 = v21.times(w1 / 2), a = p1.plus(w21), d = p1.minus(w21), b = p2.plus(w21), c = p2.minus(w21);
    if (s0 && s0.visible) {
        var p0 = pv.vector(s0.left, s0.top), p10 = p1.minus(p0), v10 = p10.perp().norm(), v1 = v10.plus(v21).norm(), am = this.lineIntersect(p1, v1, a, p21), dm = this.lineIntersect(p1, v1, d, p21);
        miterLength = am.minus(dm).length();
        var w0 = s0.lineWidth / this.scale, w10avg = (w1 + w0) / 2;
        miterRatio = miterLength / w10avg;
        miterLimit = s1.strokeMiterLimit || pv.strokeMiterLimit;
        if (miterLimit >= miterRatio) pts.push(dm, am); else {
            var p12 = p21.times(-1), v1Outer = p10.norm().plus(p12.norm()).norm(), bevel10 = p1.plus(v1Outer.times(w10avg / 2));
            v1Outer.dot(v21) >= 0 ? pts.push(dm, bevel10, a) : pts.push(d, bevel10, am);
        }
    } else pts.push(d, a);
    if (s3 && s3.visible) {
        var p3 = pv.vector(s3.left, s3.top), p32 = p3.minus(p2), v32 = p32.perp().norm(), v2 = v32.plus(v21).norm(), bm = this.lineIntersect(p2, v2, b, p21), cm = this.lineIntersect(p2, v2, c, p21);
        miterLength = bm.minus(cm).length();
        var w3 = s3.lineWidth / this.scale, w31avg = (w3 + w1) / 2;
        miterRatio = miterLength / w31avg;
        miterLimit = s2.strokeMiterLimit || pv.strokeMiterLimit;
        if (miterLimit >= miterRatio) pts.push(bm, cm); else {
            var p23 = p32.times(-1), v2Outer = p21.norm().plus(p23.norm()).norm(), bevel31 = p2.plus(v2Outer.times(w31avg / 2));
            v2Outer.dot(v21) >= 0 ? pts.push(b, bevel31, cm) : pts.push(bm, bevel31, c);
        }
    } else pts.push(b, c);
    var pt = pts.shift();
    return "M" + pt.x + "," + pt.y + "L" + pts.map(function(pt2) {
        return pt2.x + "," + pt2.y;
    }).join(" ");
};

pv.SvgScene.lineIntersect = function(o1, d1, o2, d2) {
    return o1.plus(d1.times(o2.minus(o1).dot(d2.perp()) / d1.dot(d2.perp())));
};

pv.SvgScene.lineJoinPaths = function(paths, from, to) {
    for (var d = paths[from].join(""), i = from + 1; to >= i; i++) d += paths[i][1];
    return d;
};

pv.SvgScene.lineAreaDotAlone = function(elm) {
    return elm;
};

pv.Scene.eventsToNumber = {
    "": 0,
    none: 0,
    painted: 1,
    all: 2
};

pv.Scene.numberToEvents = [ "none", "painted", "all" ];

pv.SvgScene.eachLineAreaSegment = function(elm, scenes, keyArgs, lineAreaSegment) {
    if ("function" == typeof keyArgs) {
        lineAreaSegment = keyArgs;
        keyArgs = null;
    }
    var eventsNumber, ki, kf, breakOnKeyChange = pv.get(keyArgs, "breakOnKeyChange", !1), from = pv.get(keyArgs, "from") || 0, to = pv.get(keyArgs, "to", scenes.length - 1);
    if (breakOnKeyChange) {
        ki = [];
        kf = [];
    }
    for (var i = from; to >= i; ) {
        var si = scenes[i];
        if (this.isSceneVisible(si)) {
            eventsNumber = this.eventsToNumber[si.events] || 0;
            breakOnKeyChange && this.lineAreaSceneKey(si, ki);
            for (var i2, f = i; ;) {
                var f2 = f + 1;
                if (f2 > to) {
                    i2 = f2;
                    break;
                }
                var sf = scenes[f2];
                if (!this.isSceneVisible(sf)) {
                    i2 = f2 + 1;
                    break;
                }
                eventsNumber = Math.max(eventsNumber, this.eventsToNumber[sf.events] || 0);
                f = f2;
                if (breakOnKeyChange) {
                    this.lineAreaSceneKey(sf, kf);
                    if (!this.equalSceneKeys(ki, kf)) {
                        i2 = f;
                        break;
                    }
                }
            }
            elm = lineAreaSegment.call(this, elm, scenes, i, f, keyArgs, this.numberToEvents[eventsNumber]);
            i = i2;
        } else i++;
    }
    return elm;
};

pv.SvgScene.lineAreaSceneKey = function(s, k) {
    k[0] = s.fillStyle.key;
    k[1] = s.strokeStyle.key;
    k[2] = s.lineWidth;
    k[3] = s.strokeDasharray || "none";
    k[4] = s.interpolate;
    return k;
};

pv.SvgScene.isSceneVisible = function(s) {
    return s.visible && (s.fillStyle.opacity > 0 || s.strokeStyle.opacity > 0);
};

pv.SvgScene.equalSceneKeys = function(ka, kb) {
    for (var i = 0, K = ka.length; K > i; i++) if (ka[i] !== kb[i]) return !1;
    return !0;
};

pv.SvgScene.panel = function(scene) {
    for (var g = scene.$g, e = g && g.firstChild, i = 0, L = scene.length; L > i; i++) {
        var s = scene[i];
        if (s.visible) {
            if (!scene.parent) {
                var canvas = s.canvas;
                this.applyCanvasStyle(canvas);
                if (g && g.parentNode !== canvas) {
                    g = canvas.firstChild;
                    e = g && g.firstChild;
                }
                if (g) e && "defs" === e.tagName && (e = e.nextSibling); else {
                    g = this.createRootPanelElement();
                    e = null;
                    this.initRootPanelElement(g, scene.mark);
                    canvas.appendChild(g);
                    scene.$defs = g.appendChild(this.create("defs"));
                    scene.$g = g;
                }
                g.setAttribute("width", s.width + s.left + s.right);
                g.setAttribute("height", s.height + s.top + s.bottom);
            }
            var clip_g = null;
            if ("hidden" === s.overflow) {
                var clipResult = this.addPanelClipPath(g, e, scene, i, s);
                clip_g = clipResult.g;
                scene.$g = g = clip_g;
                e = clipResult.next;
            }
            e = this.fill(e, scene, i);
            var k = this.scale, t = s.transform, x = s.left + t.x, y = s.top + t.y;
            this.scale *= t.k;
            if (s.children.length) for (var attrs = {
                transform: "translate(" + x + "," + y + ")" + (1 != t.k ? " scale(" + t.k + ")" : "")
            }, childScenes = this.getSortedChildScenes(scene, i), j = 0, C = childScenes.length; C > j; j++) {
                var childScene = childScenes[j];
                childScene.$g = e = this.expect(e, "g", scene, i, attrs);
                this.updateAll(childScene);
                e.parentNode || g.appendChild(e);
                e = e.nextSibling;
            }
            this.scale = k;
            e = this.stroke(e, scene, i);
            if (clip_g) {
                scene.$g = g = clip_g.parentNode;
                e = clip_g.nextSibling;
            }
        }
    }
    return e;
};

pv.SvgScene.applyCanvasStyle = function(canvas) {
    canvas.style.display = "inline-block";
};

pv.SvgScene.createRootPanelElement = function() {
    return this.create("svg");
};

pv.SvgScene.initRootPanelElement = function(g, panel) {
    g.setAttribute("font-size", "10px");
    g.setAttribute("font-family", "sans-serif");
    g.setAttribute("fill", "none");
    g.setAttribute("stroke", "none");
    g.setAttribute("stroke-width", 1.5);
    this.disableElementSelection(g);
    this.listenRootPanelElement(g, panel);
};

pv.SvgScene.listenRootPanelElement = function(g, panel) {
    for (var j = 0, evs = this.events, J = evs.length; J > j; j++) {
        g.addEventListener(evs[j], this.dispatch, !1);
        panel._registerBoundEvent(g, evs[j], this.dispatch, !1);
    }
};

pv.SvgScene.disableElementSelection = function(g) {
    g.setAttribute("style", "-webkit-touch-callout: none; -webkit-user-select: none; -khtml-user-select: none; -moz-user-select: none; -ms-user-select: none; user-select: none;");
    if ("undefined" != typeof g.onselectstart) {
        g.setAttribute("unselectable", "on");
        g.onselectstart = function() {
            return !1;
        };
    }
};

pv.SvgScene.addPanelClipPath = function(g, e, scene, i, s) {
    var id = pv.id().toString(36), clip_g = this.expect(e, "g", scene, i, {
        "clip-path": "url(#" + id + ")"
    }), clip_p = this.expect(clip_g.firstChild, "clipPath", scene, i, {
        id: id
    }), r = clip_p.firstChild || clip_p.appendChild(this.create("rect"));
    r.setAttribute("x", s.left);
    r.setAttribute("y", s.top);
    r.setAttribute("width", s.width);
    r.setAttribute("height", s.height);
    clip_p.parentNode || clip_g.appendChild(clip_p);
    clip_g.parentNode || g.appendChild(clip_g);
    return {
        g: clip_g,
        next: clip_p.nextSibling
    };
};

pv.SvgScene.getSortedChildScenes = function(scene, i) {
    var children = scene[i].children;
    if (scene.mark._zOrderChildCount) {
        children = children.slice(0);
        children.sort(function(scenes1, scenes2) {
            var compare = scenes1.mark._zOrder - scenes2.mark._zOrder;
            0 === compare && (compare = scenes1.childIndex - scenes2.childIndex);
            return compare;
        });
    }
    return children;
};

pv.SvgScene.fill = function(e, scene, i) {
    var s = scene[i], fill = s.fillStyle;
    if (fill.opacity || "all" == s.events) {
        this.addFillStyleDefinition(scene, fill);
        e = this.expect(e, "rect", scene, i, {
            "shape-rendering": s.antialias ? null : "crispEdges",
            "pointer-events": s.events,
            cursor: s.cursor,
            x: s.left,
            y: s.top,
            width: s.width,
            height: s.height,
            fill: fill.color,
            "fill-opacity": fill.opacity,
            stroke: null
        });
        e = this.append(e, scene, i);
    }
    return e;
};

pv.SvgScene.stroke = function(e, scene, i) {
    var s = scene[i], stroke = s.strokeStyle;
    if (stroke.opacity || "all" == s.events) {
        e = this.expect(e, "rect", scene, i, {
            "shape-rendering": s.antialias ? null : "crispEdges",
            "pointer-events": "all" == s.events ? "stroke" : s.events,
            cursor: s.cursor,
            x: s.left,
            y: s.top,
            width: Math.max(1e-10, s.width),
            height: Math.max(1e-10, s.height),
            fill: null,
            stroke: stroke.color,
            "stroke-opacity": stroke.opacity,
            "stroke-width": s.lineWidth / this.scale,
            "stroke-linecap": s.lineCap,
            "stroke-dasharray": stroke.opacity ? this.parseDasharray(s) : null
        });
        e = this.append(e, scene, i);
    }
    return e;
};

pv.SvgScene.minRuleLineWidth = 1;

pv.SvgScene.rule = function(scenes) {
    for (var e = scenes.$g.firstChild, i = 0; i < scenes.length; i++) {
        var s = scenes[i];
        if (s.visible) {
            var stroke = s.strokeStyle;
            if (stroke.opacity) {
                var lineWidth = s.lineWidth;
                lineWidth = 1e-10 > lineWidth ? 0 : Math.max(this.minRuleLineWidth, lineWidth / this.scale);
                e = this.expect(e, "line", scenes, i, {
                    "shape-rendering": s.antialias ? null : "crispEdges",
                    "pointer-events": s.events,
                    cursor: s.cursor,
                    x1: s.left,
                    y1: s.top,
                    x2: s.left + s.width,
                    y2: s.top + s.height,
                    stroke: stroke.color,
                    "stroke-opacity": stroke.opacity,
                    "stroke-width": lineWidth,
                    "stroke-linecap": s.lineCap,
                    "stroke-dasharray": stroke.opacity ? this.parseDasharray(s) : null
                });
                s.svg && this.setAttributes(e, s.svg);
                s.css && this.setStyle(e, s.css);
                e = this.append(e, scenes, i);
            }
        }
    }
    return e;
};

pv.SvgScene.wedge = function(scenes) {
    for (var e = scenes.$g.firstChild, i = 0; i < scenes.length; i++) {
        var s = scenes[i];
        if (s.visible) {
            var fill = s.fillStyle, stroke = s.strokeStyle;
            if (fill.opacity || stroke.opacity) {
                var p, r1 = s.innerRadius, r2 = s.outerRadius, a = Math.abs(s.angle);
                if (a >= 2 * Math.PI) p = r1 ? "M0," + r2 + "A" + r2 + "," + r2 + " 0 1,1 0," + -r2 + "A" + r2 + "," + r2 + " 0 1,1 0," + r2 + "M0," + r1 + "A" + r1 + "," + r1 + " 0 1,1 0," + -r1 + "A" + r1 + "," + r1 + " 0 1,1 0," + r1 + "Z" : "M0," + r2 + "A" + r2 + "," + r2 + " 0 1,1 0," + -r2 + "A" + r2 + "," + r2 + " 0 1,1 0," + r2 + "Z"; else {
                    var sa = Math.min(s.startAngle, s.endAngle), ea = Math.max(s.startAngle, s.endAngle), c1 = Math.cos(sa), c2 = Math.cos(ea), s1 = Math.sin(sa), s2 = Math.sin(ea);
                    p = r1 ? "M" + r2 * c1 + "," + r2 * s1 + "A" + r2 + "," + r2 + " 0 " + (a < Math.PI ? "0" : "1") + ",1 " + r2 * c2 + "," + r2 * s2 + "L" + r1 * c2 + "," + r1 * s2 + "A" + r1 + "," + r1 + " 0 " + (a < Math.PI ? "0" : "1") + ",0 " + r1 * c1 + "," + r1 * s1 + "Z" : "M" + r2 * c1 + "," + r2 * s1 + "A" + r2 + "," + r2 + " 0 " + (a < Math.PI ? "0" : "1") + ",1 " + r2 * c2 + "," + r2 * s2 + "L0,0Z";
                }
                this.addFillStyleDefinition(scenes, fill);
                this.addFillStyleDefinition(scenes, stroke);
                e = this.expect(e, "path", scenes, i, {
                    "shape-rendering": s.antialias ? null : "crispEdges",
                    "pointer-events": s.events,
                    cursor: s.cursor,
                    transform: "translate(" + s.left + "," + s.top + ")",
                    d: p,
                    fill: fill.color,
                    "fill-rule": "evenodd",
                    "fill-opacity": fill.opacity || null,
                    stroke: stroke.color,
                    "stroke-opacity": stroke.opacity || null,
                    "stroke-width": stroke.opacity ? s.lineWidth / this.scale : null,
                    "stroke-linejoin": s.lineJoin,
                    "stroke-miterlimit": s.strokeMiterLimit,
                    "stroke-linecap": s.lineCap,
                    "stroke-dasharray": stroke.opacity ? this.parseDasharray(s) : null
                });
                s.svg && this.setAttributes(e, s.svg);
                s.css && this.setStyle(e, s.css);
                e = this.append(e, scenes, i);
            }
        }
    }
    return e;
};

pv.Mark = function() {
    this.$properties = [];
    this.$propertiesMap = {};
    this.$handlers = {};
};

pv.Mark.prototype.properties = {};

pv.Mark.cast = {};

pv.Mark.prototype.property = function(name, cast) {
    this.hasOwnProperty("properties") || (this.properties = pv.extend(this.properties));
    this.properties[name] = !0;
    pv.Mark.prototype.propertyMethod(name, !1, pv.Mark.cast[name] = cast);
    return this;
};

pv.Mark.prototype.localProperty = function(name, cast) {
    this.hasOwnProperty("properties") || (this.properties = pv.extend(this.properties));
    this.properties[name] = !0;
    var currCast = pv.Mark.cast[name];
    cast && (pv.Mark.cast[name] = currCast = cast);
    this.propertyMethod(name, !1, currCast);
    return this;
};

pv.Mark.prototype.def = function(name, v) {
    this.propertyMethod(name, !0);
    return this[name](arguments.length > 1 ? v : null);
};

pv.Mark.prototype.propertyMethod = function(name, isDef, cast) {
    cast || (cast = pv.Mark.cast[name]);
    this[name] = function(v, tag) {
        if (isDef && this.scene) {
            var defs = this.scene.defs;
            if (arguments.length) {
                defs[name] = {
                    id: null == v ? 0 : pv.id(),
                    value: null != v && cast ? cast(v) : v
                };
                return this;
            }
            var def = defs[name];
            return def ? def.value : null;
        }
        if (arguments.length) {
            this.setPropertyValue(name, v, isDef, cast, !1, tag);
            return this;
        }
        var s = this.instance();
        if (pv.propBuildMark === this && 1 !== pv.propBuilt[name]) {
            pv.propBuilt[name] = 1;
            return s[name] = this.evalProperty(this.binds.properties[name]);
        }
        return s[name];
    };
};

pv.Mark.funPropertyCaller = function(fun, cast) {
    function mark_callFunProperty() {
        var value = fun.apply(this, stack);
        return null != value ? cast(value) : value;
    }
    var stack = pv.Mark.stack;
    return mark_callFunProperty;
};

pv.Mark.prototype.setPropertyValue = function(name, v, isDef, cast, chain, tag) {
    var type = !isDef << 1 | "function" == typeof v;
    1 & type && cast ? v = pv.Mark.funPropertyCaller(v, cast) : null != v && cast && (v = cast(v));
    var propertiesMap = this.$propertiesMap, properties = this.$properties, p = {
        name: name,
        id: pv.id(),
        value: v,
        type: type,
        tag: tag
    }, specified = propertiesMap[name];
    propertiesMap[name] = p;
    if (specified) for (var i = 0, P = properties.length; P > i; i++) if (properties[i] === specified) {
        properties.splice(i, 1);
        break;
    }
    properties.push(p);
    if (chain && specified && 3 === type) {
        p.proto = specified;
        p.root = specified.root || specified;
    }
    return p;
};

pv.Mark.prototype.intercept = function(name, v, keyArgs) {
    this.setPropertyValue(name, v, !1, pv.get(keyArgs, "noCast") ? null : pv.Mark.cast[name], !0, pv.get(keyArgs, "tag"));
    return this;
};

pv.Mark.prototype.propertyValue = function(name, inherit) {
    var p = this.$propertiesMap[name];
    if (p) return p.value;
    if (inherit) {
        if (this.proto) {
            var value = this.proto._propertyValueRecursive(name);
            if (void 0 !== value) return value;
        }
        return this.defaults._propertyValueRecursive(name);
    }
};

pv.Mark.prototype._propertyValueRecursive = function(name) {
    var p = this.$propertiesMap[name];
    return p ? p.value : this.proto ? this.proto._propertyValueRecursive(name) : void 0;
};

pv.Mark.stack = [];

pv.Mark.prototype.property("data").property("visible", Boolean).property("css", Object).property("svg", Object).property("left", Number).property("right", Number).property("top", Number).property("bottom", Number).property("cursor", String).property("title", String).property("reverse", Boolean).property("antialias", Boolean).property("events", String).property("id", String);

pv.Mark.prototype.childIndex = -1;

pv.Mark.prototype.index = -1;

pv.Mark.prototype.scale = 1;

pv.Mark.prototype._zOrder = 0;

pv.Mark.prototype.defaults = new pv.Mark().data(function(d) {
    return [ d ];
}).visible(!0).antialias(!0).events("painted");

pv.Mark.prototype.extend = function(proto) {
    this.proto = proto;
    this.target = proto.target;
    return this;
};

pv.Mark.prototype.add = function(type) {
    return this.parent.add(type).extend(this);
};

pv.Mark.prototype.zOrder = function(zOrder) {
    if (!arguments.length) return this._zOrder;
    zOrder = +zOrder || 0;
    if (this._zOrder !== zOrder) {
        var p = this.parent;
        p && 0 !== this._zOrder && p._zOrderChildCount--;
        this._zOrder = zOrder;
        p && 0 !== this._zOrder && p._zOrderChildCount++;
    }
    return this;
};

pv.Mark.prototype.anchor = function(name) {
    return new pv.Anchor(this).name(name || "center").data(function() {
        return this.scene.target.map(function(s) {
            return s.data;
        });
    }).visible(function() {
        return this.scene.target[this.index].visible;
    }).id(function() {
        return this.scene.target[this.index].id;
    }).left(function() {
        var s = this.scene.target[this.index], w = s.width || 0;
        switch (this.name()) {
          case "bottom":
          case "top":
          case "center":
            return s.left + w / 2;

          case "left":
            return null;
        }
        return s.left + w;
    }).top(function() {
        var s = this.scene.target[this.index], h = s.height || 0;
        switch (this.name()) {
          case "left":
          case "right":
          case "center":
            return s.top + h / 2;

          case "top":
            return null;
        }
        return s.top + h;
    }).right(function() {
        var s = this.scene.target[this.index];
        return "left" == this.name() ? s.right + (s.width || 0) : null;
    }).bottom(function() {
        var s = this.scene.target[this.index];
        return "top" == this.name() ? s.bottom + (s.height || 0) : null;
    }).textAlign(function() {
        switch (this.name()) {
          case "bottom":
          case "top":
          case "center":
            return "center";

          case "right":
            return "right";
        }
        return "left";
    }).textBaseline(function() {
        switch (this.name()) {
          case "right":
          case "left":
          case "center":
            return "middle";

          case "top":
            return "top";
        }
        return "bottom";
    });
};

pv.Mark.prototype.anchorTarget = function() {
    return this.target;
};

pv.Mark.prototype.margin = function(n) {
    return this.left(n).right(n).top(n).bottom(n);
};

pv.Mark.prototype.instance = function(defaultIndex) {
    var scene = this.scene || this.parent.instance(-1).children[this.childIndex], index = null == defaultIndex || this.hasOwnProperty("index") ? this.index : defaultIndex;
    return scene[0 > index ? scene.length - 1 : index];
};

pv.Mark.prototype.instances = function(source) {
    for (var scene, mark = this, index = []; !(scene = mark.scene); ) {
        index.push({
            index: source.parentIndex,
            childIndex: mark.childIndex
        });
        source = source.parent;
        mark = mark.parent;
    }
    for (var j = index.length; j--; ) {
        var info = index[j];
        scene = scene[info.index].children[info.childIndex];
    }
    if (this.hasOwnProperty("index")) {
        var s = pv.extend(scene[this.index]);
        s.right = s.top = s.left = s.bottom = 0;
        return [ s ];
    }
    return scene;
};

pv.Mark.prototype.first = function() {
    return this.scene[0];
};

pv.Mark.prototype.last = function() {
    return this.scene[this.scene.length - 1];
};

pv.Mark.prototype.sibling = function() {
    return 0 == this.index ? null : this.scene[this.index - 1];
};

pv.Mark.prototype.cousin = function() {
    var p = this.parent, s = p && p.sibling();
    return s && s.children ? s.children[this.childIndex][this.index] : null;
};

pv.Mark.prototype._renderId = 0;

pv.Mark.prototype.renderId = function() {
    return this.root._renderId;
};

pv.Mark.prototype.render = function() {
    var root = this.root;
    if (!this.parent || root.scene) {
        root._renderId++;
        this.renderCore();
    } else root.render();
};

pv.Mark.prototype.renderCore = function() {
    function render(mark, depth, scale) {
        mark.scale = scale;
        if (L > depth) {
            var addStack = depth >= stack.length;
            addStack && stack.unshift(null);
            if (mark.hasOwnProperty("index")) renderCurrentInstance(mark, depth, scale, addStack); else {
                for (var i = 0, n = mark.scene.length; n > i; i++) {
                    mark.index = i;
                    renderCurrentInstance(mark, depth, scale, addStack);
                }
                delete mark.index;
            }
            addStack && stack.shift();
        } else {
            mark.build();
            pv.Scene.scale = scale;
            pv.Scene.updateAll(mark.scene);
        }
        delete mark.scale;
    }
    function renderCurrentInstance(mark, depth, scale, fillStack) {
        var i, s = mark.scene[mark.index];
        if (s.visible) {
            var childMarks = mark.children, childScenez = s.children, childIndex = indexes[depth], childMark = childMarks[childIndex];
            childMark.scene || childIndex++;
            for (i = 0; childIndex > i; i++) childMarks[i].scene = childScenez[i];
            fillStack && (stack[0] = s.data);
            render(childMark, depth + 1, scale * s.transform.k);
            for (i = 0; childIndex > i; i++) childMarks[i].scene = void 0;
        }
    }
    for (var parent = this.parent, stack = pv.Mark.stack, S = stack.length, indexes = [], mark = this; mark.parent; mark = mark.parent) indexes.unshift(mark.childIndex);
    var L = indexes.length;
    this.bind();
    for (;parent && !parent.hasOwnProperty("index"); ) parent = parent.parent;
    try {
        this.context(parent ? parent.scene : void 0, parent ? parent.index : -1, function() {
            render(this.root, 0, 1);
        });
    } catch (e) {
        stack.length > S && (stack.length = S);
        throw e;
    }
};

pv.Mark.prototype.bind = function() {
    function bind(mark) {
        do for (var properties = mark.$properties, i = properties.length; i--; ) {
            var p = properties[i], name = p.name, pLeaf = seen[name];
            if (pLeaf) {
                if (3 === pLeaf.type) {
                    var pRoot = pLeaf.root;
                    pLeaf.root = p;
                    pRoot ? pRoot.proto || (pRoot.proto = p) : pLeaf.proto = p;
                }
            } else {
                seen[name] = p;
                switch (name) {
                  case "data":
                    data = p;
                    break;

                  case "visible":
                  case "id":
                    required.push(p);
                    break;

                  default:
                    types[p.type].push(p);
                }
            }
        } while (mark = mark.proto);
    }
    var data, seen = {}, required = [], types = [ [], [], [], [] ];
    bind(this);
    bind(this.defaults);
    var types0 = types[0], types1 = types[1].reverse(), types2 = types[2];
    types[3].reverse();
    var mark = this;
    do for (var name in mark.properties) name in seen || types2.push(seen[name] = {
        name: name,
        type: 2,
        value: null
    }); while (mark = mark.proto);
    var defs;
    if (types0.length || types1.length) {
        defs = types0.concat(types1);
        for (var i = 0, D = defs.length; D > i; i++) this.propertyMethod(defs[i].name, !0);
    } else defs = [];
    this.binds = {
        properties: seen,
        data: data,
        defs: defs,
        required: required,
        optional: pv.blend(types)
    };
};

pv.Mark.prototype.build = function() {
    var stack = pv.Mark.stack, scene = this.scene;
    if (!scene) {
        scene = this.scene = [];
        scene.mark = this;
        scene.type = this.type;
        scene.childIndex = this.childIndex;
        var parent = this.parent;
        if (parent) {
            scene.parent = parent.scene;
            scene.parentIndex = parent.index;
        }
    }
    this.target && (scene.target = this.target.instances(scene));
    var bdefs = this.binds.defs;
    if (bdefs.length) for (var defs = scene.defs || (scene.defs = {}), i = 0, B = bdefs.length; B > i; i++) {
        var p = bdefs[i], d = defs[p.name];
        (!d || p.id > d.id) && (defs[p.name] = {
            id: 0,
            value: 1 & p.type ? p.value.apply(this, stack) : p.value
        });
    }
    var datas = this.evalProperty(this.binds.data), L = datas.length;
    scene.length = L;
    if (L) {
        var markProto = pv.Mark.prototype;
        stack.unshift(null);
        var propBuildMarkBefore = pv.propBuildMark, propBuiltBefore = pv.propBuilt;
        pv.propBuildMark = this;
        try {
            for (var i = 0; L > i; i++) {
                markProto.index = this.index = i;
                pv.propBuilt = {};
                var instance = scene[i];
                instance ? instance._state && delete instance._state : instance = scene[i] = {};
                instance.data = stack[0] = datas[i];
                this.preBuildInstance(instance);
                this.buildInstance(instance);
            }
        } finally {
            markProto.index = -1;
            delete this.index;
            stack.shift();
            pv.propBuildMark = propBuildMarkBefore;
            pv.propBuilt = propBuiltBefore;
        }
    }
    return this;
};

pv.Mark.prototype.instanceState = function(s) {
    s || (s = this.instance());
    return s ? s._state || (s._state = {}) : null;
};

pv.Mark.prototype.preBuildInstance = function() {};

pv.Mark.prototype.buildInstance = function(s) {
    this.buildProperties(s, this.binds.required);
    if (s.visible) {
        this.buildProperties(s, this.binds.optional);
        this.buildImplied(s);
    }
};

!function() {
    var _protoProp, _stack = pv.Mark.stack, _evalPropByType = [ function(p) {
        return this.scene.defs[p.name].value;
    }, null, function(p) {
        return p.value;
    }, function(p) {
        _protoProp = p.proto;
        return p.value.apply(this, _stack);
    } ];
    _evalPropByType[1] = _evalPropByType[0];
    pv.Mark.prototype.buildProperties = function(s, properties) {
        var built = pv.propBuilt, localBuilt = !built;
        if (localBuilt) {
            pv.propBuildMark = this;
            pv.propBuilt = built = {};
        }
        for (var protoPropBefore = _protoProp, i = 0, P = properties.length; P > i; i++) {
            var p = properties[i], pname = p.name;
            if (!(pname in built)) {
                built[pname] = 1;
                s[pname] = _evalPropByType[p.type].call(this, p);
            }
        }
        _protoProp = protoPropBefore;
        localBuilt && (pv.propBuildMark = pv.propBuilt = null);
    };
    pv.Mark.prototype.evalProperty = function(p) {
        var protoPropBefore = _protoProp, v = _evalPropByType[p.type].call(this, p);
        _protoProp = protoPropBefore;
        return v;
    };
    pv.Mark.prototype.evalInPropertyContext = function(f, protoProp) {
        var protoPropBefore = _protoProp;
        _protoProp = protoProp;
        var v = f.apply(this, _stack);
        _protoProp = protoPropBefore;
        return v;
    };
    pv.Mark.prototype.delegate = function(dv, tag) {
        if (_protoProp && (!tag || _protoProp.tag === tag)) {
            var value = this.evalProperty(_protoProp);
            if (void 0 !== value) return value;
        }
        return dv;
    };
    pv.Mark.prototype.delegateExcept = function(dv, notTag) {
        if (_protoProp && (!notTag || _protoProp.tag !== notTag)) {
            var value = this.evalProperty(_protoProp);
            if (void 0 !== value) return value;
        }
        return dv;
    };
    pv.Mark.prototype.hasDelegate = function(tag) {
        return !(!_protoProp || tag && _protoProp.tag !== tag);
    };
}();

pv.Mark.prototype.buildImplied = function(s) {
    var parent_s, checked, l = s.left, r = s.right, t = s.top, b = s.bottom, p = this.properties, w = p.width ? s.width : 0, h = p.height ? s.height : 0;
    if (null == w || null == r || null == l) {
        parent_s = this.parent && this.parent.instance();
        checked = !0;
        var width = parent_s ? parent_s.width : w + l + r;
        null == w ? w = width - (r = r || 0) - (l = l || 0) : null == r ? null == l ? l = r = (width - w) / 2 : r = width - w - l : l = width - w - r;
    }
    if (null == h || null == b || null == t) {
        checked || (parent_s = this.parent && this.parent.instance());
        var height = parent_s ? parent_s.height : h + t + b;
        null == h ? h = height - (t = t || 0) - (b = b || 0) : null == b ? b = null == t ? t = (height - h) / 2 : height - h - t : t = height - h - b;
    }
    s.left = l;
    s.right = r;
    s.top = t;
    s.bottom = b;
    p.width && (s.width = w);
    p.height && (s.height = h);
    p.textStyle && !s.textStyle && (s.textStyle = pv.FillStyle.transparent);
    p.fillStyle && !s.fillStyle && (s.fillStyle = pv.FillStyle.transparent);
    p.strokeStyle && !s.strokeStyle && (s.strokeStyle = pv.FillStyle.transparent);
};

pv.Mark.prototype.mouse = function() {
    var n = this.root.canvas(), ev = pv.event, x = ev && ev.pageX || 0, y = ev && ev.pageY || 0, offset = pv.elementOffset(n);
    if (offset) {
        var getStyle = pv.cssStyle(n);
        x -= offset.left + parseFloat(getStyle("paddingLeft") || 0);
        y -= offset.top + parseFloat(getStyle("paddingTop") || 0);
    }
    var t = pv.Transform.identity, p = this.properties.transform ? this : this.parent, pz = [];
    do pz.push(p); while (p = p.parent);
    for (;p = pz.pop(); ) {
        var pinst = p.instance();
        t = t.translate(pinst.left, pinst.top).times(pinst.transform);
    }
    t = t.invert();
    return pv.vector(x * t.k + t.x, y * t.k + t.y);
};

pv.Mark.prototype.event = function(type, handler) {
    handler = pv.functor(handler);
    var hs = this.$handlers[type];
    hs ? hs instanceof Array ? hs.push(handler) : hs = [ hs, handler ] : hs = handler;
    this.$hasHandlers = !0;
    this.$handlers[type] = hs;
    return this;
};

pv.Mark.prototype.context = function(scene, index, f) {
    function apply(scene, index) {
        pv.Mark.scene = scene;
        proto.index = index;
        if (scene) {
            var i, that = scene.mark, mark = that, ancestors = [];
            do {
                ancestors.push(mark);
                stack.push(scene[index].data);
                mark.index = index;
                mark.scene = scene;
                if (mark = mark.parent) {
                    index = scene.parentIndex;
                    scene = scene.parent;
                }
            } while (mark);
            var k = 1;
            i = ancestors.length - 1;
            if (i > 0) do {
                mark = ancestors[i--];
                mark.scale = k;
                k *= mark.scene[mark.index].transform.k;
            } while (i);
            that.scale = k;
            var n, children = that.children;
            if (children && (n = children.length) > 0) {
                var thatInst = that.scene[that.index];
                k *= thatInst.transform.k;
                var childScenes = thatInst.children;
                i = n;
                for (;i--; ) {
                    mark = children[i];
                    mark.scene = childScenes[i];
                    mark.scale = k;
                }
            }
        }
    }
    function clear(scene) {
        if (scene) {
            var mark, that = scene.mark, children = that.children;
            if (children) for (var i = children.length; i--; ) {
                mark = children[i];
                mark.scene = void 0;
                mark.scale = 1;
            }
            mark = that;
            var parent, count = 0;
            do {
                count++;
                delete mark.index;
                if (parent = mark.parent) {
                    mark.scene = void 0;
                    mark.scale = 1;
                }
            } while (mark = parent);
            count && (stack.length -= count);
        }
    }
    var proto = pv.Mark.prototype, stack = pv.Mark.stack, oscene = pv.Mark.scene, oindex = proto.index;
    if (scene && scene === oscene && index === oindex) try {
        f.apply(this, stack);
    } catch (ex) {
        pv.error(ex);
        throw ex;
    } finally {
        pv.Mark.scene = oscene;
        proto.index = oindex;
    } else {
        clear(oscene, oindex);
        apply(scene, index);
        try {
            f.apply(this, stack);
        } catch (ex) {
            pv.error(ex);
            throw ex;
        } finally {
            clear(scene, index);
            apply(oscene, oindex);
        }
    }
};

pv.Mark.prototype.getEventHandler = function(type, scenes, index, ev) {
    var handler = this.$handlers[type];
    return handler ? [ handler, scenes, index, ev ] : this.getParentEventHandler(type, scenes, index, ev);
};

pv.Mark.prototype.getParentEventHandler = function(type, scenes, index, ev) {
    var parentScenes = scenes.parent;
    return parentScenes ? parentScenes.mark.getEventHandler(type, parentScenes, scenes.parentIndex, ev) : void 0;
};

pv.Mark.dispatch = function(type, scenes, index, event) {
    var root = scenes.mark.root;
    if (root.$transition) return !0;
    var handlerInfo, interceptors = root.$interceptors && root.$interceptors[type];
    if (interceptors) for (var i = 0, L = interceptors.length; L > i; i++) {
        handlerInfo = interceptors[i](type, event);
        if (handlerInfo) break;
        if (handlerInfo === !1) return !0;
    }
    if (!handlerInfo) {
        handlerInfo = scenes.mark.getEventHandler(type, scenes, index, event);
        if (!handlerInfo) return !1;
    }
    return this.handle.apply(this, handlerInfo);
};

pv.Mark.handle = function(handler, scenes, index, event) {
    var m = scenes.mark;
    m.context(scenes, index, function() {
        var i, L, mi, stack = pv.Mark.stack.concat(event);
        if (handler instanceof Array) {
            var ms;
            for (i = 0, L = handler.length; L > i; i++) {
                mi = handler[i].apply(m, stack);
                mi && mi.render && (ms || (ms = [])).push(mi);
            }
            if (ms) for (i = 0, L = ms.length; L > i; i++) ms[i].render();
        } else {
            mi = handler.apply(m, stack);
            mi && mi.render && mi.render();
        }
    });
    return !0;
};

pv.Mark.prototype.addEventInterceptor = function(type, handler, before) {
    var root = this.root;
    if (root) {
        var ints = root.$interceptors || (root.$interceptors = {}), list = ints[type] || (ints[type] = []);
        before ? list.unshift(handler) : list.push(handler);
    }
};

pv.Mark.prototype.eachInstance = function(fun, ctx) {
    function mapRecursive(scene, level, toScreen) {
        var D = scene.length;
        if (D > 0) {
            var childIndex, isLastLevel = level === L;
            isLastLevel || (childIndex = indexes[level]);
            for (var index = 0; D > index; index++) {
                var instance = scene[index];
                if (instance.visible) if (level === L) fun.call(ctx, scene, index, toScreen); else {
                    var childScene = instance.children[childIndex];
                    if (childScene) {
                        var childToScreen = toScreen.times(instance.transform).translate(instance.left, instance.top);
                        mapRecursive(childScene, level + 1, childToScreen);
                    }
                }
            }
        }
    }
    for (var mark = this, indexes = []; mark.parent; ) {
        indexes.unshift(mark.childIndex);
        mark = mark.parent;
    }
    var rootScene = mark.scene;
    if (rootScene) {
        var L = indexes.length;
        mapRecursive(rootScene, 0, pv.Transform.identity);
    }
};

pv.Mark.prototype.toScreenTransform = function() {
    var t = pv.Transform.identity;
    this instanceof pv.Panel && (t = t.translate(this.left(), this.top()).times(this.transform()));
    var parent = this.parent;
    if (parent) do t = t.translate(parent.left(), parent.top()).times(parent.transform()); while (parent = parent.parent);
    return t;
};

pv.Mark.prototype.transition = function() {
    return new pv.Transition(this);
};

pv.Mark.prototype.on = function(state) {
    return this["$" + state] = new pv.Transient(this);
};

pv.Mark.prototype.getShape = function(scenes, index, inset) {
    var s = scenes[index];
    if (!s.visible) return null;
    null == inset && (inset = 0);
    var key = "_shape_inset_" + inset;
    return s[key] || (s[key] = this.getShapeCore(scenes, index, inset));
};

pv.Mark.prototype.getShapeCore = function(scenes, index, inset) {
    var s = scenes[index], l = s.left, t = s.top, w = s.width, h = s.height;
    if (inset > 0 && 1 >= inset) {
        var dw = inset * w, dh = inset * h;
        l += dw;
        t += dh;
        w -= 2 * dw;
        h -= 2 * dh;
    }
    return new pv.Shape.Rect(l, t, w, h);
};

pv.Anchor = function(target) {
    pv.Mark.call(this);
    this.target = target;
    this.parent = target.parent;
};

pv.Anchor.prototype = pv.extend(pv.Mark).property("name", String);

pv.Anchor.prototype.extend = function(proto) {
    this.proto = proto;
    return this;
};

pv.Area = function() {
    pv.Mark.call(this);
};

pv.Area.castSegmented = function(v) {
    if (!v) return "";
    switch (v) {
      case "smart":
      case "full":
        break;

      default:
        v = "full";
    }
    return v;
};

pv.Area.prototype = pv.extend(pv.Mark).property("width", Number).property("height", Number).property("lineWidth", Number).property("lineJoin", String).property("strokeMiterLimit", Number).property("lineCap", String).property("strokeDasharray", String).property("strokeStyle", pv.fillStyle).property("fillStyle", pv.fillStyle).property("segmented", pv.Area.castSegmented).property("interpolate", String).property("tension", Number);

pv.Area.prototype.type = "area";

pv.Area.prototype.defaults = new pv.Area().extend(pv.Mark.prototype.defaults).lineWidth(1.5).fillStyle(pv.Colors.category20().by(pv.parent)).interpolate("linear").tension(.7).lineJoin("miter").strokeMiterLimit(8).lineCap("butt").strokeDasharray("none");

pv.Area.prototype.buildImplied = function(s) {
    null == s.height && (s.height = 0);
    null == s.width && (s.width = 0);
    pv.Mark.prototype.buildImplied.call(this, s);
};

pv.Area.fixed = {
    lineWidth: 1,
    lineJoin: 1,
    strokeMiterLimit: 1,
    lineCap: 1,
    strokeStyle: 1,
    strokeDasharray: 1,
    fillStyle: 1,
    segmented: 1,
    interpolate: 1,
    tension: 1
};

pv.Area.prototype.bind = function() {
    pv.Mark.prototype.bind.call(this);
    for (var binds = this.binds, required = binds.required, optional = binds.optional, i = 0, n = optional.length; n > i; i++) {
        var p = optional[i];
        p.fixed = p.name in pv.Area.fixed;
        if ("segmented" == p.name) {
            required.push(p);
            optional.splice(i, 1);
            i--;
            n--;
        }
    }
    this.binds.$required = required;
    this.binds.$optional = optional;
};

pv.Area.prototype.buildInstance = function(s) {
    function f(p) {
        return !p.fixed || (fixed.push(p), !1);
    }
    var binds = this.binds;
    if (this.index) {
        var fixed = binds.fixed;
        if (!fixed) {
            fixed = binds.fixed = [];
            binds.required = binds.required.filter(f);
            this.scene[0].segmented || (binds.optional = binds.optional.filter(f));
        }
        var n = fixed.length;
        if (n) for (var firstScene = this.scene[0], i = 0; n > i; i++) {
            var p = fixed[i].name;
            s[p] = firstScene[p];
        }
    } else {
        binds.required = binds.$required;
        binds.optional = binds.$optional;
        binds.fixed = null;
    }
    pv.Mark.prototype.buildInstance.call(this, s);
};

pv.Area.prototype.anchor = function(name) {
    return pv.Mark.prototype.anchor.call(this, name).interpolate(function() {
        return this.scene.target[this.index].interpolate;
    }).eccentricity(function() {
        return this.scene.target[this.index].eccentricity;
    }).tension(function() {
        return this.scene.target[this.index].tension;
    });
};

pv.Area.prototype.getEventHandler = function(type, scene, index, ev) {
    var s = scene[index], needEventSimulation = 1 === pv.Scene.mousePositionEventSet[type] && !s.segmented || "smart" === s.segmented;
    if (!needEventSimulation) return pv.Mark.prototype.getEventHandler.call(this, type, scene, index, ev);
    var handler = this.$handlers[type], isMouseMove = "mousemove" === type, handlerMouseOver = isMouseMove ? this.$handlers.mouseover : null;
    if (!handler && !handlerMouseOver) return this.getParentEventHandler(type, scene, index, ev);
    var mouseIndex = this.getNearestInstanceToMouse(scene, index);
    if (handler) {
        if (handlerMouseOver) {
            var prevMouseOverScene = this._mouseOverScene;
            if (!prevMouseOverScene || prevMouseOverScene !== scene || this._mouseOverIndex !== mouseIndex) {
                this._mouseOverScene = scene;
                this._mouseOverIndex = mouseIndex;
                return [ [ handler, handlerMouseOver ], scene, mouseIndex, ev ];
            }
        }
        return [ handler, scene, mouseIndex, ev ];
    }
    return [ handlerMouseOver, scene, mouseIndex, ev ];
};

pv.Area.prototype.getNearestInstanceToMouse = function(scene, eventIndex) {
    for (var p = this.mouse(), minDist2 = 1/0, minIndex = null, index = eventIndex, L = scene.length; L > index; index++) {
        var shape = this.getShape(scene, index);
        if (shape) {
            if (shape.containsPoint(p)) return index;
            var dist2 = shape.distance2(p).dist2;
            if (minDist2 > dist2) {
                minDist2 = dist2;
                minIndex = index;
            }
        }
    }
    return null != minIndex ? minIndex : eventIndex;
};

pv.Area.prototype.getShapeCore = function(scenes, index) {
    var s = scenes[index], w = s.width || 0, h = s.height || 0, x = s.left, y = s.top, s2 = index + 1 < scenes.length ? scenes[index + 1] : null;
    if (!s2 || !s2.visible) return new pv.Shape.Line(x, y, x + w, y + h);
    var x2 = s2.left, y2 = s2.top, h2 = s2.height || 0, w2 = s2.width || 0;
    return new pv.Shape.Polygon([ new pv.Vector(x, y), new pv.Vector(x2, y2), new pv.Vector(x2 + w2, y2 + h2), new pv.Vector(x + w, y + h) ]);
};

pv.Bar = function() {
    pv.Mark.call(this);
};

pv.Bar.prototype = pv.extend(pv.Mark).property("width", Number).property("height", Number).property("lineWidth", Number).property("strokeStyle", pv.fillStyle).property("fillStyle", pv.fillStyle).property("lineCap", String).property("strokeDasharray", String);

pv.Bar.prototype.type = "bar";

pv.Bar.prototype.defaults = new pv.Bar().extend(pv.Mark.prototype.defaults).lineWidth(1.5).fillStyle(pv.Colors.category20().by(pv.parent)).lineCap("butt").strokeDasharray("none");

pv.Dot = function() {
    pv.Mark.call(this);
};

pv.Dot.prototype = pv.extend(pv.Mark).property("shape", String).property("shapeAngle", Number).property("shapeRadius", Number).property("shapeSize", Number).property("aspectRatio", Number).property("lineWidth", Number).property("strokeStyle", pv.fillStyle).property("lineCap", String).property("strokeDasharray", String).property("fillStyle", pv.fillStyle);

pv.Dot.prototype.type = "dot";

pv.Dot.prototype.defaults = new pv.Dot().extend(pv.Mark.prototype.defaults).shape("circle").aspectRatio(1).lineWidth(1.5).strokeStyle(pv.Colors.category10().by(pv.parent)).lineCap("butt").strokeDasharray("none");

pv.Dot.prototype.anchor = function(name) {
    return pv.Mark.prototype.anchor.call(this, name).left(function() {
        var s = this.scene.target[this.index];
        switch (this.name()) {
          case "bottom":
          case "top":
          case "center":
            return s.left;

          case "left":
            return null;
        }
        return s.left + s._width / 2;
    }).right(function() {
        var s = this.scene.target[this.index];
        return "left" == this.name() ? s.right + s._width / 2 : null;
    }).top(function() {
        var s = this.scene.target[this.index];
        switch (this.name()) {
          case "left":
          case "right":
          case "center":
            return s.top;

          case "top":
            return null;
        }
        return s.top + s._height / 2;
    }).bottom(function() {
        var s = this.scene.target[this.index];
        return "top" == this.name() ? s.bottom + s._height / 2 : null;
    }).textAlign(function() {
        switch (this.name()) {
          case "left":
            return "right";

          case "bottom":
          case "top":
          case "center":
            return "center";
        }
        return "left";
    }).textBaseline(function() {
        switch (this.name()) {
          case "right":
          case "left":
          case "center":
            return "middle";

          case "bottom":
            return "top";
        }
        return "bottom";
    });
};

pv.Dot.prototype.buildImplied = function(s) {
    var r = s.shapeRadius, z = s.shapeSize, a = s.aspectRatio || 1;
    if (null == r) if (null == z) {
        z = s.shapeSize = 20.25;
        r = s.shapeRadius = 4.5;
    } else r = s.shapeRadius = Math.sqrt(z); else null == z && (z = s.shapeSize = r * r);
    var h, w;
    if (1 === a || 0 > a) h = w = 2 * r; else {
        h = 2 * r / Math.sqrt(a);
        w = a * h;
    }
    s._height = h;
    s._width = w;
    pv.Mark.prototype.buildImplied.call(this, s);
};

pv.Dot.prototype.width = function() {
    return this.instance()._width;
};

pv.Dot.prototype.height = function() {
    return this.instance()._height;
};

pv.Dot.prototype.getShapeCore = function(scenes, index) {
    var s = scenes[index], h = s._width, w = s._height, cx = s.left, cy = s.top;
    switch (s.shape) {
      case "diamond":
        h *= Math.SQRT2;
        w *= Math.SQRT2;

      case "square":
      case "cross":
        return new pv.Shape.Rect(cx - w / 2, cy - h / 2, w, h);
    }
    return new pv.Shape.Circle(cx, cy, s.shapeRadius);
};

pv.Label = function() {
    pv.Mark.call(this);
};

pv.Label.prototype = pv.extend(pv.Mark).property("text", String).property("font", String).property("textAngle", Number).property("textStyle", pv.color).property("textAlign", String).property("textBaseline", String).property("textMargin", Number).property("textDecoration", String).property("textShadow", String);

pv.Label.prototype.type = "label";

pv.Label.prototype.defaults = new pv.Label().extend(pv.Mark.prototype.defaults).events("none").text(pv.identity).font("10px sans-serif").textAngle(0).textStyle("black").textAlign("left").textBaseline("bottom").textMargin(3);

pv.Label.prototype.getShapeCore = function(scenes, index, inset) {
    var s = scenes[index], size = pv.Text.measure(s.text, s.font), l = s.left, t = s.top, w = size.width, h = size.height;
    if (inset > 0 && 1 >= inset) {
        var dw = inset * w, dh = inset * h;
        l += dw;
        t += dh;
        w -= 2 * dw;
        h -= 2 * dh;
    }
    return pv.Label.getPolygon(w, h, s.textAlign, s.textBaseline, s.textAngle, s.textMargin).apply(pv.Transform.identity.translate(l, t));
};

pv.Label.getPolygon = function(textWidth, textHeight, align, baseline, angle, margin) {
    var x, y;
    switch (baseline) {
      case "middle":
        y = textHeight / 2;
        break;

      case "top":
        y = margin + textHeight;
        break;

      case "bottom":
        y = -margin;
    }
    switch (align) {
      case "right":
        x = -margin - textWidth;
        break;

      case "center":
        x = -textWidth / 2;
        break;

      case "left":
        x = margin;
    }
    var bl = new pv.Vector(x, y), br = bl.plus(textWidth, 0), tr = br.plus(0, -textHeight), tl = bl.plus(0, -textHeight);
    if (0 !== angle) {
        bl = bl.rotate(angle);
        br = br.rotate(angle);
        tl = tl.rotate(angle);
        tr = tr.rotate(angle);
    }
    return new pv.Shape.Polygon([ bl, br, tr, tl ]);
};

pv.Line = function() {
    pv.Mark.call(this);
};

pv.Line.prototype = pv.extend(pv.Mark).property("lineWidth", Number).property("lineJoin", String).property("strokeMiterLimit", Number).property("lineCap", String).property("strokeStyle", pv.fillStyle).property("strokeDasharray", String).property("fillStyle", pv.fillStyle).property("segmented", pv.Area.castSegmented).property("interpolate", String).property("eccentricity", Number).property("tension", Number);

pv.Line.prototype.type = "line";

pv.Line.prototype.defaults = new pv.Line().extend(pv.Mark.prototype.defaults).lineWidth(1.5).strokeStyle(pv.Colors.category10().by(pv.parent)).interpolate("linear").eccentricity(0).tension(.7).lineJoin("miter").strokeMiterLimit(8).lineCap("butt").strokeDasharray("none");

pv.Line.prototype.bind = pv.Area.prototype.bind;

pv.Line.prototype.buildInstance = pv.Area.prototype.buildInstance;

pv.Line.prototype.getEventHandler = pv.Area.prototype.getEventHandler;

pv.Line.prototype.getNearestInstanceToMouse = pv.Area.prototype.getNearestInstanceToMouse;

pv.Line.prototype.anchor = function(name) {
    return pv.Area.prototype.anchor.call(this, name).textAlign(function() {
        switch (this.name()) {
          case "left":
            return "right";

          case "bottom":
          case "top":
          case "center":
            return "center";

          case "right":
            return "left";
        }
    }).textBaseline(function() {
        switch (this.name()) {
          case "right":
          case "left":
          case "center":
            return "middle";

          case "top":
            return "bottom";

          case "bottom":
            return "top";
        }
    });
};

pv.Line.prototype.getShapeCore = function(scenes, index) {
    var s = scenes[index], s2 = index + 1 < scenes.length ? scenes[index + 1] : null;
    return null != s2 && s2.visible ? new pv.Shape.Line(s.left, s.top, s2.left, s2.top) : new pv.Shape.Point(s.left, s.top);
};

pv.Rule = function() {
    pv.Mark.call(this);
};

pv.Rule.prototype = pv.extend(pv.Mark).property("width", Number).property("height", Number).property("lineWidth", Number).property("strokeStyle", pv.fillStyle).property("lineCap", String).property("strokeDasharray", String);

pv.Rule.prototype.type = "rule";

pv.Rule.prototype.defaults = new pv.Rule().extend(pv.Mark.prototype.defaults).lineWidth(1).strokeStyle("black").antialias(!1).lineCap("butt").strokeDasharray("none");

pv.Rule.prototype.anchor = pv.Line.prototype.anchor;

pv.Rule.prototype.buildImplied = function(s) {
    {
        var l = s.left, r = s.right;
        s.top, s.bottom;
    }
    null != s.width || null == l && null == r || null != r && null != l ? s.height = 0 : s.width = 0;
    pv.Mark.prototype.buildImplied.call(this, s);
};

pv.Rule.prototype.getShapeCore = function(scenes, index) {
    var s = scenes[index];
    return new pv.Shape.Line(s.left, s.top, s.left + s.width, s.top + s.height);
};

pv.Panel = function() {
    pv.Bar.call(this);
    this.children = [];
    this.root = this;
    this.$dom = pv.$ && pv.$.s;
};

pv.Panel.prototype = pv.extend(pv.Bar).property("transform").property("overflow", String).property("canvas", function(c) {
    return "string" == typeof c ? document.getElementById(c) : c;
});

pv.Panel.prototype.type = "panel";

pv.Panel.prototype._zOrderChildCount = 0;

pv.Panel.prototype.defaults = new pv.Panel().extend(pv.Bar.prototype.defaults).fillStyle(null).overflow("visible");

pv.Panel.prototype.anchor = function(name) {
    var anchor = pv.Bar.prototype.anchor.call(this, name);
    anchor.parent = this;
    return anchor;
};

pv.Panel.prototype.add = function(Type) {
    var child = new Type();
    child.parent = this;
    child.root = this.root;
    child.childIndex = this.children.length;
    this.children.push(child);
    var zOrder = +child._zOrder || 0;
    0 !== zOrder && this._zOrderChildCount++;
    return child;
};

pv.Panel.prototype.bind = function() {
    pv.Mark.prototype.bind.call(this);
    for (var children = this.children, i = 0, n = children.length; n > i; i++) children[i].bind();
};

pv.Panel.prototype.buildInstance = function(s) {
    pv.Bar.prototype.buildInstance.call(this, s);
    if (s.visible) {
        var scale = this.scale * s.transform.k;
        pv.Mark.prototype.index = -1;
        for (var child, children = this.children, childScenes = s.children || (s.children = []), i = 0, n = children.length; n > i; i++) {
            child = children[i];
            child.scene = childScenes[i];
            child.scale = scale;
            child.build();
        }
        i = n;
        for (;i--; ) {
            child = children[i];
            childScenes[i] = child.scene;
            delete child.scene;
            delete child.scale;
        }
        childScenes.length = n;
    }
};

pv.Panel.prototype.buildImplied = function(s) {
    if (this.parent || this._buildRootInstanceImplied(s)) {
        s.transform || (s.transform = pv.Transform.identity);
        pv.Mark.prototype.buildImplied.call(this, s);
    } else s.visible = !1;
};

pv.Panel.prototype._buildRootInstanceImplied = function(s) {
    var c = s.canvas;
    if (c) {
        if (!this._rootInstanceStealCanvas(s, c)) return !1;
        this._rootInstanceInitCanvas(s, c);
    } else s.canvas = this._rootInstanceGetInlineCanvas(s);
    return !0;
};

pv.Panel.prototype._rootInstanceStealCanvas = function(s, c) {
    var cPanel = c.$panel;
    if (cPanel !== this) {
        if (cPanel) {
            if (this.$lastCreateId) return !1;
            cPanel._disposeRootPanel();
            this._updateCreateId(c);
        }
        c.$panel = this;
        pv.removeChildren(c);
    } else this._updateCreateId(c);
    return !0;
};

pv.Panel.prototype._registerBoundEvent = function(source, name, listener, capturePhase) {
    if (source.removeEventListener) {
        var boundEvents = this._boundEvents || (this._boundEvents = []);
        boundEvents.push([ source, name, listener, capturePhase ]);
    }
};

pv.Panel.prototype._disposeRootPanel = function() {
    var t = this.$transition;
    t && t.stop();
    var boundEvents = this._boundEvents;
    if (boundEvents) {
        this._boundEvents = null;
        for (var i = 0, L = boundEvents.length; L > i; i++) {
            var be = boundEvents[i];
            be[0].removeEventListener(be[1], be[2], be[3]);
        }
    }
};

pv.Panel.prototype._rootInstanceInitCanvas = function(s, c) {
    var w, h, cssStyle;
    if (null == s.width) {
        cssStyle = pv.cssStyle(c);
        w = parseFloat(cssStyle("width") || 0);
        s.width = w - s.left - s.right;
    }
    if (null == s.height) {
        cssStyle || (cssStyle = pv.cssStyle(c));
        h = parseFloat(cssStyle("height") || 0);
        s.height = h - s.top - s.bottom;
    }
    cssStyle = null;
};

pv.Panel.prototype._rootInstanceGetInlineCanvas = function() {
    var c, cache = this.$canvas || (this.$canvas = []);
    if (!(c = cache[this.index])) {
        c = cache[this.index] = document.createElement("span");
        if (this.$dom) this.$dom.parentNode.insertBefore(c, this.$dom); else {
            for (var n = document.body; n.lastChild && n.lastChild.tagName; ) n = n.lastChild;
            n != document.body && (n = n.parentNode);
            n.appendChild(c);
        }
    }
    return c;
};

pv.Panel.prototype._updateCreateId = function(c) {
    this.$lastCreateId = c.$pvCreateId = (c.$pvCreateId || 0) + 1;
};

pv.Image = function() {
    pv.Bar.call(this);
};

pv.Image.prototype = pv.extend(pv.Bar).property("url", String).property("imageWidth", Number).property("imageHeight", Number);

pv.Image.prototype.type = "image";

pv.Image.prototype.defaults = new pv.Image().extend(pv.Bar.prototype.defaults).fillStyle(null);

pv.Image.prototype.image = function(f) {
    this.$image = function() {
        var c = f.apply(this, arguments);
        return null == c ? pv.Color.transparent : "string" == typeof c ? pv.color(c) : c;
    };
    return this;
};

pv.Image.prototype.bind = function() {
    pv.Bar.prototype.bind.call(this);
    var binds = this.binds, mark = this;
    do binds.image = mark.$image; while (!binds.image && (mark = mark.proto));
};

pv.Image.prototype.buildImplied = function(s) {
    pv.Bar.prototype.buildImplied.call(this, s);
    if (s.visible) {
        null == s.imageWidth && (s.imageWidth = s.width);
        null == s.imageHeight && (s.imageHeight = s.height);
        if (null == s.url && this.binds.image) {
            var data, canvas = this.$canvas || (this.$canvas = document.createElement("canvas")), context = canvas.getContext("2d"), w = s.imageWidth, h = s.imageHeight, stack = pv.Mark.stack;
            canvas.width = w;
            canvas.height = h;
            data = (s.image = context.createImageData(w, h)).data;
            stack.unshift(null, null);
            for (var y = 0, p = 0; h > y; y++) {
                stack[1] = y;
                for (var x = 0; w > x; x++) {
                    stack[0] = x;
                    var color = this.binds.image.apply(this, stack);
                    data[p++] = color.r;
                    data[p++] = color.g;
                    data[p++] = color.b;
                    data[p++] = 255 * color.a;
                }
            }
            stack.splice(0, 2);
        }
    }
};

pv.Wedge = function() {
    pv.Mark.call(this);
};

pv.Wedge.prototype = pv.extend(pv.Mark).property("startAngle", Number).property("endAngle", Number).property("angle", Number).property("innerRadius", Number).property("outerRadius", Number).property("lineWidth", Number).property("strokeStyle", pv.fillStyle).property("lineJoin", String).property("strokeMiterLimit", Number).property("lineCap", String).property("strokeDasharray", String).property("fillStyle", pv.fillStyle);

pv.Wedge.prototype.type = "wedge";

pv.Wedge.prototype.defaults = new pv.Wedge().extend(pv.Mark.prototype.defaults).startAngle(function() {
    var s = this.sibling();
    return s ? s.endAngle : -Math.PI / 2;
}).innerRadius(0).lineWidth(1.5).strokeStyle(null).fillStyle(pv.Colors.category20().by(pv.index)).lineJoin("miter").strokeMiterLimit(8).lineCap("butt").strokeDasharray("none");

pv.Wedge.prototype.midRadius = function() {
    return (this.innerRadius() + this.outerRadius()) / 2;
};

pv.Wedge.prototype.midAngle = function() {
    return (this.startAngle() + this.endAngle()) / 2;
};

pv.Wedge.prototype.anchor = function(name) {
    function partial(s) {
        return s.innerRadius || s.angle < 2 * Math.PI;
    }
    function midRadius(s) {
        return (s.innerRadius + s.outerRadius) / 2;
    }
    function midAngle(s) {
        return (s.startAngle + s.endAngle) / 2;
    }
    return pv.Mark.prototype.anchor.call(this, name).left(function() {
        var s = this.scene.target[this.index];
        if (partial(s)) switch (this.name()) {
          case "outer":
            return s.left + s.outerRadius * Math.cos(midAngle(s));

          case "inner":
            return s.left + s.innerRadius * Math.cos(midAngle(s));

          case "start":
            return s.left + midRadius(s) * Math.cos(s.startAngle);

          case "center":
            return s.left + midRadius(s) * Math.cos(midAngle(s));

          case "end":
            return s.left + midRadius(s) * Math.cos(s.endAngle);
        }
        return s.left;
    }).top(function() {
        var s = this.scene.target[this.index];
        if (partial(s)) switch (this.name()) {
          case "outer":
            return s.top + s.outerRadius * Math.sin(midAngle(s));

          case "inner":
            return s.top + s.innerRadius * Math.sin(midAngle(s));

          case "start":
            return s.top + midRadius(s) * Math.sin(s.startAngle);

          case "center":
            return s.top + midRadius(s) * Math.sin(midAngle(s));

          case "end":
            return s.top + midRadius(s) * Math.sin(s.endAngle);
        }
        return s.top;
    }).textAlign(function() {
        var s = this.scene.target[this.index];
        if (partial(s)) switch (this.name()) {
          case "outer":
            return pv.Wedge.upright(midAngle(s)) ? "right" : "left";

          case "inner":
            return pv.Wedge.upright(midAngle(s)) ? "left" : "right";
        }
        return "center";
    }).textBaseline(function() {
        var s = this.scene.target[this.index];
        if (partial(s)) switch (this.name()) {
          case "start":
            return pv.Wedge.upright(s.startAngle) ? "top" : "bottom";

          case "end":
            return pv.Wedge.upright(s.endAngle) ? "bottom" : "top";
        }
        return "middle";
    }).textAngle(function() {
        var s = this.scene.target[this.index], a = 0;
        if (partial(s)) switch (this.name()) {
          case "center":
          case "inner":
          case "outer":
            a = midAngle(s);
            break;

          case "start":
            a = s.startAngle;
            break;

          case "end":
            a = s.endAngle;
        }
        return pv.Wedge.upright(a) ? a : a + Math.PI;
    });
};

pv.Wedge.upright = function(angle) {
    angle %= 2 * Math.PI;
    angle = 0 > angle ? 2 * Math.PI + angle : angle;
    return angle < Math.PI / 2 || angle >= 3 * Math.PI / 2;
};

pv.Wedge.prototype.buildImplied = function(s) {
    null == s.angle ? s.angle = s.endAngle - s.startAngle : null == s.endAngle && (s.endAngle = s.startAngle + s.angle);
    pv.Mark.prototype.buildImplied.call(this, s);
};

pv.Wedge.prototype.getShapeCore = function(scenes, index) {
    var s = scenes[index];
    return new pv.Shape.Wedge(s.left, s.top, s.innerRadius, s.outerRadius, s.startAngle, s.angle);
};

pv.Ease = function() {
    function reverse(f) {
        return function(t) {
            return 1 - f(1 - t);
        };
    }
    function reflect(f) {
        return function(t) {
            return .5 * (.5 > t ? f(2 * t) : 2 - f(2 - 2 * t));
        };
    }
    function poly(e) {
        return function(t) {
            return 0 > t ? 0 : t > 1 ? 1 : Math.pow(t, e);
        };
    }
    function sin(t) {
        return 1 - Math.cos(t * Math.PI / 2);
    }
    function exp(t) {
        return t ? Math.pow(2, 10 * (t - 1)) - .001 : 0;
    }
    function circle(t) {
        return -(Math.sqrt(1 - t * t) - 1);
    }
    function elastic(a, p) {
        var s;
        p || (p = .45);
        if (!a || 1 > a) {
            a = 1;
            s = p / 4;
        } else s = p / (2 * Math.PI) * Math.asin(1 / a);
        return function(t) {
            return 0 >= t || t >= 1 ? t : -(a * Math.pow(2, 10 * --t) * Math.sin(2 * (t - s) * Math.PI / p));
        };
    }
    function back(s) {
        s || (s = 1.70158);
        return function(t) {
            return t * t * ((s + 1) * t - s);
        };
    }
    function bounce(t) {
        return 1 / 2.75 > t ? 7.5625 * t * t : 2 / 2.75 > t ? 7.5625 * (t -= 1.5 / 2.75) * t + .75 : 2.5 / 2.75 > t ? 7.5625 * (t -= 2.25 / 2.75) * t + .9375 : 7.5625 * (t -= 2.625 / 2.75) * t + .984375;
    }
    var quad = poly(2), cubic = poly(3), elasticDefault = elastic(), backDefault = back(), eases = {
        linear: pv.identity,
        "quad-in": quad,
        "quad-out": reverse(quad),
        "quad-in-out": reflect(quad),
        "quad-out-in": reflect(reverse(quad)),
        "cubic-in": cubic,
        "cubic-out": reverse(cubic),
        "cubic-in-out": reflect(cubic),
        "cubic-out-in": reflect(reverse(cubic)),
        "sin-in": sin,
        "sin-out": reverse(sin),
        "sin-in-out": reflect(sin),
        "sin-out-in": reflect(reverse(sin)),
        "exp-in": exp,
        "exp-out": reverse(exp),
        "exp-in-out": reflect(exp),
        "exp-out-in": reflect(reverse(exp)),
        "circle-in": circle,
        "circle-out": reverse(circle),
        "circle-in-out": reflect(circle),
        "circle-out-in": reflect(reverse(circle)),
        "elastic-in": elasticDefault,
        "elastic-out": reverse(elasticDefault),
        "elastic-in-out": reflect(elasticDefault),
        "elastic-out-in": reflect(reverse(elasticDefault)),
        "back-in": backDefault,
        "back-out": reverse(backDefault),
        "back-in-out": reflect(backDefault),
        "back-out-in": reflect(reverse(backDefault)),
        "bounce-in": bounce,
        "bounce-out": reverse(bounce),
        "bounce-in-out": reflect(bounce),
        "bounce-out-in": reflect(reverse(bounce))
    };
    pv.ease = function(f) {
        return eases[f];
    };
    return {
        reverse: reverse,
        reflect: reflect,
        linear: function() {
            return pv.identity;
        },
        sin: function() {
            return sin;
        },
        exp: function() {
            return exp;
        },
        circle: function() {
            return circle;
        },
        elastic: elastic,
        back: back,
        bounce: bounce,
        poly: poly
    };
}();

pv.Transient = function(mark) {
    pv.Mark.call(this);
    this.fillStyle(null).strokeStyle(null).textStyle(null);
    this.on = function(state) {
        return mark.on(state);
    };
};

pv.Transient.prototype = pv.extend(pv.Mark);

!function() {
    function ids(scene) {
        for (var map = {}, i = scene.length; i--; ) {
            var s = scene[i], id = s.id;
            id && (map[id] = s);
        }
        return map;
    }
    function interpolateProperty(list, name, before, after) {
        var step;
        if (name in _interpolated) {
            var interp = pv.Scale.interpolator(before[name], after[name]);
            step = function(t) {
                before[name] = interp(t);
            };
        } else step = function(t) {
            t > .5 && (before[name] = after[name]);
        };
        step.next = list.head;
        list.head = step;
    }
    function interpolateInstance(list, beforeInst, afterInst) {
        for (var name in beforeInst) "children" !== name && beforeInst[name] != afterInst[name] && interpolateProperty(list, name, beforeInst, afterInst);
        var beforeChildScenes = beforeInst.children;
        if (beforeChildScenes) for (var afterChildScenes = afterInst.children, j = 0, L = beforeChildScenes.length; L > j; j++) interpolate(list, beforeChildScenes[j], afterChildScenes[j]);
    }
    function overrideInstance(scene, index, proto, other) {
        var t, otherInst = Object.create(scene[index]), m = scene.mark, rs = m.root.scene;
        if (other.target && (t = other.target[other.length])) {
            scene = Object.create(scene);
            scene.target = Object.create(other.target);
            scene.target[index] = t;
        }
        proto || (proto = _defaults);
        var ps = proto.$properties, overriden = proto.$propertiesMap;
        ps = m.binds.optional.filter(function(p) {
            return !(p.name in overriden);
        }).concat(ps);
        m.context(scene, index, function() {
            this.buildProperties(otherInst, ps);
            this.buildImplied(otherInst);
        });
        m.root.scene = rs;
        return otherInst;
    }
    function interpolate(list, before, after) {
        for (var beforeInst, afterInst, mark = before.mark, beforeById = ids(before), afterById = ids(after), i = 0, L = before.length; L > i; i++) {
            beforeInst = before[i];
            afterInst = beforeInst.id ? afterById[beforeInst.id] : after[i];
            beforeInst.index = i;
            if (beforeInst.visible) {
                if (!afterInst || !afterInst.visible) {
                    var overridenAfterInst = overrideInstance(before, i, mark.$exit, after);
                    beforeInst.transition = afterInst ? 2 : (after.push(overridenAfterInst), 1);
                    afterInst = overridenAfterInst;
                }
                interpolateInstance(list, beforeInst, afterInst);
            }
        }
        i = 0;
        L = after.length;
        for (;L > i; i++) {
            afterInst = after[i];
            beforeInst = afterInst.id ? beforeById[afterInst.id] : before[i];
            if ((!beforeInst || !beforeInst.visible) && afterInst.visible) {
                var overridenBeforeInst = overrideInstance(after, i, mark.$enter, before);
                beforeInst ? before[beforeInst.index] = overridenBeforeInst : before.push(overridenBeforeInst);
                interpolateInstance(list, overridenBeforeInst, afterInst);
            }
        }
    }
    function cleanup(scene) {
        for (var i = 0, j = 0; i < scene.length; i++) {
            var s = scene[i];
            if (1 != s.transition) {
                scene[j++] = s;
                2 == s.transition && (s.visible = !1);
                s.children && s.children.forEach(cleanup);
            }
        }
        scene.length = j;
    }
    var _interpolated = {
        top: 1,
        left: 1,
        right: 1,
        bottom: 1,
        width: 1,
        height: 1,
        innerRadius: 1,
        outerRadius: 1,
        radius: 1,
        shapeRadius: 1,
        shapeSize: 1,
        startAngle: 1,
        endAngle: 1,
        angle: 1,
        fillStyle: 1,
        strokeStyle: 1,
        lineWidth: 1,
        eccentricity: 1,
        tension: 1,
        textAngle: 1,
        textStyle: 1,
        textMargin: 1
    }, _defaults = new pv.Transient();
    pv.Transition = function(mark) {
        function doEnd(success) {
            var started = mark.root.$transition === that;
            started && (mark.root.$transition = null);
            if (null != timer) {
                clearInterval(timer);
                timer = null;
            }
            started && cleanupOnce(mark.scene);
            if (onEndCallback) {
                var cb = onEndCallback;
                onEndCallback = null;
                cb(success);
            }
            return success;
        }
        var timer, onEndCallback, cleanedup, that = this, ease = pv.ease("cubic-in-out"), duration = 250, cleanupOnce = function(scene) {
            if (!cleanedup) {
                cleanedup = !0;
                cleanup(scene);
            }
        };
        that.ease = function(x) {
            return arguments.length ? (ease = "function" == typeof x ? x : pv.ease(x), that) : ease;
        };
        that.duration = function(x) {
            return arguments.length ? (duration = Number(x), that) : duration;
        };
        that.start = function(onEnd) {
            if (mark.parent) throw new Error("Animated partial rendering is not supported.");
            onEndCallback = onEnd;
            var root = mark.root;
            if (root.$transition) try {
                root.$transition.stop();
            } catch (ex) {
                return doEnd(!1);
            }
            var list, start;
            root.$transition = that;
            var before = mark.scene;
            mark.scene = null;
            var i0 = pv.Mark.prototype.index;
            try {
                mark.bind();
                mark.build();
                var after = mark.scene;
                mark.scene = before;
                pv.Mark.prototype.index = i0;
                start = Date.now();
                list = {};
                interpolate(list, before, after);
            } catch (ex) {
                pv.Mark.prototype.index = i0;
                return doEnd(!1);
            }
            if (!list.head) return doEnd(!0);
            var advance = function() {
                var t = Math.max(0, Math.min(1, (Date.now() - start) / duration)), te = ease(t), step = list.head;
                do step(te); while (step = step.next);
                if (1 === t) {
                    cleanupOnce(mark.scene);
                    pv.Scene.updateAll(before);
                    doEnd(!0);
                } else pv.Scene.updateAll(before);
            };
            timer = setInterval(function() {
                try {
                    advance();
                } catch (ex) {
                    doEnd(!1);
                }
            }, 24);
        };
        that.stop = function() {
            doEnd(!0);
        };
    };
}();

pv.simulation = function(particles) {
    return new pv.Simulation(particles);
};

pv.Simulation = function(particles) {
    for (var i = 0; i < particles.length; i++) this.particle(particles[i]);
};

pv.Simulation.prototype.particle = function(p) {
    p.next = this.particles;
    isNaN(p.px) && (p.px = p.x);
    isNaN(p.py) && (p.py = p.y);
    isNaN(p.fx) && (p.fx = 0);
    isNaN(p.fy) && (p.fy = 0);
    this.particles = p;
    return this;
};

pv.Simulation.prototype.force = function(f) {
    f.next = this.forces;
    this.forces = f;
    return this;
};

pv.Simulation.prototype.constraint = function(c) {
    c.next = this.constraints;
    this.constraints = c;
    return this;
};

pv.Simulation.prototype.stabilize = function(n) {
    var c;
    arguments.length || (n = 3);
    for (var i = 0; n > i; i++) {
        var q = new pv.Quadtree(this.particles);
        for (c = this.constraints; c; c = c.next) c.apply(this.particles, q);
    }
    for (var p = this.particles; p; p = p.next) {
        p.px = p.x;
        p.py = p.y;
    }
    return this;
};

pv.Simulation.prototype.step = function() {
    var p, f, c;
    for (p = this.particles; p; p = p.next) {
        var px = p.px, py = p.py;
        p.px = p.x;
        p.py = p.y;
        p.x += p.vx = p.x - px + p.fx;
        p.y += p.vy = p.y - py + p.fy;
    }
    var q = new pv.Quadtree(this.particles);
    for (c = this.constraints; c; c = c.next) c.apply(this.particles, q);
    for (p = this.particles; p; p = p.next) p.fx = p.fy = 0;
    for (f = this.forces; f; f = f.next) f.apply(this.particles, q);
};

pv.Quadtree = function(particles) {
    function insert(n, p, x1, y1, x2, y2) {
        if (!isNaN(p.x) && !isNaN(p.y)) if (n.leaf) if (n.p) if (Math.abs(n.p.x - p.x) + Math.abs(n.p.y - p.y) < .01) insertChild(n, p, x1, y1, x2, y2); else {
            var v = n.p;
            n.p = null;
            insertChild(n, v, x1, y1, x2, y2);
            insertChild(n, p, x1, y1, x2, y2);
        } else n.p = p; else insertChild(n, p, x1, y1, x2, y2);
    }
    function insertChild(n, p, x1, y1, x2, y2) {
        var sx = .5 * (x1 + x2), sy = .5 * (y1 + y2), right = p.x >= sx, bottom = p.y >= sy;
        n.leaf = !1;
        switch ((bottom << 1) + right) {
          case 0:
            n = n.c1 || (n.c1 = new pv.Quadtree.Node());
            break;

          case 1:
            n = n.c2 || (n.c2 = new pv.Quadtree.Node());
            break;

          case 2:
            n = n.c3 || (n.c3 = new pv.Quadtree.Node());
            break;

          case 3:
            n = n.c4 || (n.c4 = new pv.Quadtree.Node());
        }
        right ? x1 = sx : x2 = sx;
        bottom ? y1 = sy : y2 = sy;
        insert(n, p, x1, y1, x2, y2);
    }
    var p, x1 = Number.POSITIVE_INFINITY, y1 = x1, x2 = Number.NEGATIVE_INFINITY, y2 = x2;
    for (p = particles; p; p = p.next) {
        p.x < x1 && (x1 = p.x);
        p.y < y1 && (y1 = p.y);
        p.x > x2 && (x2 = p.x);
        p.y > y2 && (y2 = p.y);
    }
    var dx = x2 - x1, dy = y2 - y1;
    dx > dy ? y2 = y1 + dx : x2 = x1 + dy;
    this.xMin = x1;
    this.yMin = y1;
    this.xMax = x2;
    this.yMax = y2;
    this.root = new pv.Quadtree.Node();
    for (p = particles; p; p = p.next) insert(this.root, p, x1, y1, x2, y2);
};

pv.Quadtree.Node = function() {
    this.leaf = !0;
    this.c1 = null;
    this.c2 = null;
    this.c3 = null;
    this.c4 = null;
    this.p = null;
};

pv.Force = {};

pv.Force.charge = function(k) {
    function accumulate(n) {
        function accumulateChild(c) {
            accumulate(c);
            n.cn += c.cn;
            cx += c.cn * c.cx;
            cy += c.cn * c.cy;
        }
        var cx = 0, cy = 0;
        n.cn = 0;
        if (!n.leaf) {
            n.c1 && accumulateChild(n.c1);
            n.c2 && accumulateChild(n.c2);
            n.c3 && accumulateChild(n.c3);
            n.c4 && accumulateChild(n.c4);
        }
        if (n.p) {
            n.cn += k;
            cx += k * n.p.x;
            cy += k * n.p.y;
        }
        n.cx = cx / n.cn;
        n.cy = cy / n.cn;
    }
    function forces(n, p, x1, y1, x2, y2) {
        var dx = n.cx - p.x, dy = n.cy - p.y, dn = 1 / Math.sqrt(dx * dx + dy * dy);
        if (n.leaf && n.p != p || theta > (x2 - x1) * dn) {
            if (max1 > dn) return;
            dn > min1 && (dn = min1);
            var kc = n.cn * dn * dn * dn, fx = dx * kc, fy = dy * kc;
            p.fx += fx;
            p.fy += fy;
        } else if (!n.leaf) {
            var sx = .5 * (x1 + x2), sy = .5 * (y1 + y2);
            n.c1 && forces(n.c1, p, x1, y1, sx, sy);
            n.c2 && forces(n.c2, p, sx, y1, x2, sy);
            n.c3 && forces(n.c3, p, x1, sy, sx, y2);
            n.c4 && forces(n.c4, p, sx, sy, x2, y2);
            if (max1 > dn) return;
            dn > min1 && (dn = min1);
            if (n.p && n.p != p) {
                var kc = k * dn * dn * dn, fx = dx * kc, fy = dy * kc;
                p.fx += fx;
                p.fy += fy;
            }
        }
    }
    var min = 2, min1 = 1 / min, max = 500, max1 = 1 / max, theta = .9, force = {};
    arguments.length || (k = -40);
    force.constant = function(x) {
        if (arguments.length) {
            k = Number(x);
            return force;
        }
        return k;
    };
    force.domain = function(a, b) {
        if (arguments.length) {
            min = Number(a);
            min1 = 1 / min;
            max = Number(b);
            max1 = 1 / max;
            return force;
        }
        return [ min, max ];
    };
    force.theta = function(x) {
        if (arguments.length) {
            theta = Number(x);
            return force;
        }
        return theta;
    };
    force.apply = function(particles, q) {
        accumulate(q.root);
        for (var p = particles; p; p = p.next) forces(q.root, p, q.xMin, q.yMin, q.xMax, q.yMax);
    };
    return force;
};

pv.Force.drag = function(k) {
    var force = {};
    arguments.length || (k = .1);
    force.constant = function(x) {
        if (arguments.length) {
            k = x;
            return force;
        }
        return k;
    };
    force.apply = function(particles) {
        if (k) for (var p = particles; p; p = p.next) {
            p.fx -= k * p.vx;
            p.fy -= k * p.vy;
        }
    };
    return force;
};

pv.Force.spring = function(k) {
    var links, kl, d = .1, l = 20, force = {};
    arguments.length || (k = .1);
    force.links = function(x) {
        if (arguments.length) {
            links = x;
            kl = x.map(function(l) {
                return 1 / Math.sqrt(Math.max(l.sourceNode.linkDegree, l.targetNode.linkDegree));
            });
            return force;
        }
        return links;
    };
    force.constant = function(x) {
        if (arguments.length) {
            k = Number(x);
            return force;
        }
        return k;
    };
    force.damping = function(x) {
        if (arguments.length) {
            d = Number(x);
            return force;
        }
        return d;
    };
    force.length = function(x) {
        if (arguments.length) {
            l = Number(x);
            return force;
        }
        return l;
    };
    force.apply = function() {
        for (var i = 0; i < links.length; i++) {
            var a = links[i].sourceNode, b = links[i].targetNode, dx = a.x - b.x, dy = a.y - b.y, dn = Math.sqrt(dx * dx + dy * dy), dd = dn ? 1 / dn : 1, ks = k * kl[i], kd = d * kl[i], kk = (ks * (dn - l) + kd * (dx * (a.vx - b.vx) + dy * (a.vy - b.vy)) * dd) * dd, fx = -kk * (dn ? dx : .01 * (.5 - Math.random())), fy = -kk * (dn ? dy : .01 * (.5 - Math.random()));
            a.fx += fx;
            a.fy += fy;
            b.fx -= fx;
            b.fy -= fy;
        }
    };
    return force;
};

pv.Constraint = {};

pv.Constraint.collision = function(radius) {
    function constrain(n, p, x1, y1, x2, y2) {
        if (!n.leaf) {
            var sx = .5 * (x1 + x2), sy = .5 * (y1 + y2), top = sy > py1, bottom = py2 > sy, left = sx > px1, right = px2 > sx;
            if (top) {
                n.c1 && left && constrain(n.c1, p, x1, y1, sx, sy);
                n.c2 && right && constrain(n.c2, p, sx, y1, x2, sy);
            }
            if (bottom) {
                n.c3 && left && constrain(n.c3, p, x1, sy, sx, y2);
                n.c4 && right && constrain(n.c4, p, sx, sy, x2, y2);
            }
        }
        if (n.p && n.p != p) {
            var dx = p.x - n.p.x, dy = p.y - n.p.y, l = Math.sqrt(dx * dx + dy * dy), d = r1 + radius(n.p);
            if (d > l) {
                var k = (l - d) / l * .5;
                dx *= k;
                dy *= k;
                p.x -= dx;
                p.y -= dy;
                n.p.x += dx;
                n.p.y += dy;
            }
        }
    }
    var r1, px1, py1, px2, py2, n = 1, constraint = {};
    arguments.length || (r1 = 10);
    constraint.repeat = function(x) {
        if (arguments.length) {
            n = Number(x);
            return constraint;
        }
        return n;
    };
    constraint.apply = function(particles, q) {
        var p, r, max = -1/0;
        for (p = particles; p; p = p.next) {
            r = radius(p);
            r > max && (max = r);
        }
        for (var i = 0; n > i; i++) for (p = particles; p; p = p.next) {
            r = (r1 = radius(p)) + max;
            px1 = p.x - r;
            px2 = p.x + r;
            py1 = p.y - r;
            py2 = p.y + r;
            constrain(q.root, p, q.xMin, q.yMin, q.xMax, q.yMax);
        }
    };
    return constraint;
};

pv.Constraint.position = function(f) {
    var a = 1, constraint = {};
    arguments.length || (f = function(p) {
        return p.fix;
    });
    constraint.alpha = function(x) {
        if (arguments.length) {
            a = Number(x);
            return constraint;
        }
        return a;
    };
    constraint.apply = function(particles) {
        for (var p = particles; p; p = p.next) {
            var v = f(p);
            if (v) {
                p.x += (v.x - p.x) * a;
                p.y += (v.y - p.y) * a;
                p.fx = p.fy = p.vx = p.vy = 0;
            }
        }
    };
    return constraint;
};

pv.Constraint.bound = function() {
    var x, y, constraint = {};
    constraint.x = function(min, max) {
        if (arguments.length) {
            x = {
                min: Math.min(min, max),
                max: Math.max(min, max)
            };
            return this;
        }
        return x;
    };
    constraint.y = function(min, max) {
        if (arguments.length) {
            y = {
                min: Math.min(min, max),
                max: Math.max(min, max)
            };
            return this;
        }
        return y;
    };
    constraint.apply = function(particles) {
        if (x) for (var p = particles; p; p = p.next) p.x = p.x < x.min ? x.min : p.x > x.max ? x.max : p.x;
        if (y) for (var p = particles; p; p = p.next) p.y = p.y < y.min ? y.min : p.y > y.max ? y.max : p.y;
    };
    return constraint;
};

pv.Layout = function() {
    pv.Panel.call(this);
};

pv.Layout.prototype = pv.extend(pv.Panel);

pv.Layout.prototype.property = pv.Mark.prototype.localProperty;

pv.Layout.Network = function() {
    pv.Layout.call(this);
    var that = this;
    this.$id = pv.id();
    (this.node = new pv.Mark().data(function() {
        return that.nodes();
    }).strokeStyle("#1f77b4").fillStyle("#fff").left(function(n) {
        return n.x;
    }).top(function(n) {
        return n.y;
    })).parent = this;
    this.link = new pv.Mark().extend(this.node).data(function(p) {
        return [ p.sourceNode, p.targetNode ];
    }).fillStyle(null).lineWidth(function(d, p) {
        return 1.5 * p.linkValue;
    }).strokeStyle("rgba(0,0,0,.2)");
    this.link.add = function(type) {
        return that.add(pv.Panel).data(function() {
            return that.links();
        }).add(type).extend(this);
    };
    (this.label = new pv.Mark().extend(this.node).textMargin(7).textBaseline("middle").text(function(n) {
        return n.nodeName || n.nodeValue;
    }).textAngle(function(n) {
        var a = n.midAngle;
        return pv.Wedge.upright(a) ? a : a + Math.PI;
    }).textAlign(function(n) {
        return pv.Wedge.upright(n.midAngle) ? "left" : "right";
    })).parent = this;
};

pv.Layout.Network.prototype = pv.extend(pv.Layout).property("nodes", function(v) {
    return v.map(function(d, i) {
        "object" != typeof d && (d = {
            nodeValue: d
        });
        d.index = i;
        return d;
    });
}).property("links", function(v) {
    return v.map(function(d) {
        isNaN(d.linkValue) && (d.linkValue = isNaN(d.value) ? 1 : d.value);
        return d;
    });
});

pv.Layout.Network.prototype.reset = function() {
    this.$id = pv.id();
    return this;
};

pv.Layout.Network.prototype.buildProperties = function(s, properties) {
    (s.$id || 0) < this.$id && pv.Layout.prototype.buildProperties.call(this, s, properties);
};

pv.Layout.Network.prototype.buildImplied = function(s) {
    pv.Layout.prototype.buildImplied.call(this, s);
    if (s.$id >= this.$id) return !0;
    s.$id = this.$id;
    s.nodes.forEach(function(d) {
        d.linkDegree = 0;
    });
    s.links.forEach(function(d) {
        var v = d.linkValue;
        (d.sourceNode || (d.sourceNode = s.nodes[d.source])).linkDegree += v;
        (d.targetNode || (d.targetNode = s.nodes[d.target])).linkDegree += v;
    });
};

pv.Layout.Hierarchy = function() {
    pv.Layout.Network.call(this);
    this.link.strokeStyle("#ccc");
};

pv.Layout.Hierarchy.prototype = pv.extend(pv.Layout.Network);

pv.Layout.Hierarchy.prototype.buildImplied = function(s) {
    s.links || (s.links = pv.Layout.Hierarchy.links.call(this));
    pv.Layout.Network.prototype.buildImplied.call(this, s);
};

pv.Layout.Hierarchy.links = function() {
    return this.nodes().filter(function(n) {
        return n.parentNode;
    }).map(function(n) {
        return {
            sourceNode: n,
            targetNode: n.parentNode,
            linkValue: 1
        };
    });
};

pv.Layout.Hierarchy.NodeLink = {
    buildImplied: function(s) {
        function radius(n) {
            return n.parentNode ? n.depth * (or - ir) + ir : 0;
        }
        function midAngle(n) {
            return n.parentNode ? 2 * (n.breadth - .25) * Math.PI : 0;
        }
        function x(n) {
            switch (orient) {
              case "left":
                return n.depth * w;

              case "right":
                return w - n.depth * w;

              case "top":
                return n.breadth * w;

              case "bottom":
                return w - n.breadth * w;

              case "radial":
                return w / 2 + radius(n) * Math.cos(n.midAngle);
            }
        }
        function y(n) {
            switch (orient) {
              case "left":
                return n.breadth * h;

              case "right":
                return h - n.breadth * h;

              case "top":
                return n.depth * h;

              case "bottom":
                return h - n.depth * h;

              case "radial":
                return h / 2 + radius(n) * Math.sin(n.midAngle);
            }
        }
        var nodes = s.nodes, orient = s.orient, horizontal = /^(top|bottom)$/.test(orient), w = s.width, h = s.height;
        if ("radial" == orient) {
            var ir = s.innerRadius, or = s.outerRadius;
            null == ir && (ir = 0);
            null == or && (or = Math.min(w, h) / 2);
        }
        for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            n.midAngle = "radial" == orient ? midAngle(n) : horizontal ? Math.PI / 2 : 0;
            n.x = x(n);
            n.y = y(n);
            n.firstChild && (n.midAngle += Math.PI);
        }
    }
};

pv.Layout.Hierarchy.Fill = {
    constructor: function() {
        this.node.strokeStyle("#fff").fillStyle("#ccc").width(function(n) {
            return n.dx;
        }).height(function(n) {
            return n.dy;
        }).innerRadius(function(n) {
            return n.innerRadius;
        }).outerRadius(function(n) {
            return n.outerRadius;
        }).startAngle(function(n) {
            return n.startAngle;
        }).angle(function(n) {
            return n.angle;
        });
        this.label.textAlign("center").left(function(n) {
            return n.x + n.dx / 2;
        }).top(function(n) {
            return n.y + n.dy / 2;
        });
        delete this.link;
    },
    buildImplied: function(s) {
        function scale(d, depth) {
            return (d + depth) / (1 + depth);
        }
        function x(n) {
            switch (orient) {
              case "left":
                return scale(n.minDepth, depth) * w;

              case "right":
                return (1 - scale(n.maxDepth, depth)) * w;

              case "top":
                return n.minBreadth * w;

              case "bottom":
                return (1 - n.maxBreadth) * w;

              case "radial":
                return w / 2;
            }
        }
        function y(n) {
            switch (orient) {
              case "left":
                return n.minBreadth * h;

              case "right":
                return (1 - n.maxBreadth) * h;

              case "top":
                return scale(n.minDepth, depth) * h;

              case "bottom":
                return (1 - scale(n.maxDepth, depth)) * h;

              case "radial":
                return h / 2;
            }
        }
        function dx(n) {
            switch (orient) {
              case "left":
              case "right":
                return (n.maxDepth - n.minDepth) / (1 + depth) * w;

              case "top":
              case "bottom":
                return (n.maxBreadth - n.minBreadth) * w;

              case "radial":
                return n.parentNode ? (n.innerRadius + n.outerRadius) * Math.cos(n.midAngle) : 0;
            }
        }
        function dy(n) {
            switch (orient) {
              case "left":
              case "right":
                return (n.maxBreadth - n.minBreadth) * h;

              case "top":
              case "bottom":
                return (n.maxDepth - n.minDepth) / (1 + depth) * h;

              case "radial":
                return n.parentNode ? (n.innerRadius + n.outerRadius) * Math.sin(n.midAngle) : 0;
            }
        }
        function innerRadius(n) {
            return Math.max(0, scale(n.minDepth, depth / 2)) * (or - ir) + ir;
        }
        function outerRadius(n) {
            return scale(n.maxDepth, depth / 2) * (or - ir) + ir;
        }
        function startAngle(n) {
            return 2 * (n.parentNode ? n.minBreadth - .25 : 0) * Math.PI;
        }
        function angle(n) {
            return 2 * (n.parentNode ? n.maxBreadth - n.minBreadth : 1) * Math.PI;
        }
        var nodes = s.nodes, orient = s.orient, horizontal = /^(top|bottom)$/.test(orient), w = s.width, h = s.height, depth = -nodes[0].minDepth;
        if ("radial" == orient) {
            var ir = s.innerRadius, or = s.outerRadius;
            null == ir && (ir = 0);
            ir && (depth *= 2);
            null == or && (or = Math.min(w, h) / 2);
        }
        for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            n.x = x(n);
            n.y = y(n);
            if ("radial" == orient) {
                n.innerRadius = innerRadius(n);
                n.outerRadius = outerRadius(n);
                n.startAngle = startAngle(n);
                n.angle = angle(n);
                n.midAngle = n.startAngle + n.angle / 2;
            } else n.midAngle = horizontal ? -Math.PI / 2 : 0;
            n.dx = dx(n);
            n.dy = dy(n);
        }
    }
};

pv.Layout.Grid = function() {
    pv.Layout.call(this);
    var that = this;
    (this.cell = new pv.Mark().data(function() {
        return that.scene[that.index].$grid;
    }).width(function() {
        return that.width() / that.cols();
    }).height(function() {
        return that.height() / that.rows();
    }).left(function() {
        return this.width() * (this.index % that.cols());
    }).top(function() {
        return this.height() * Math.floor(this.index / that.cols());
    })).parent = this;
};

pv.Layout.Grid.prototype = pv.extend(pv.Layout).property("rows").property("cols");

pv.Layout.Grid.prototype.defaults = new pv.Layout.Grid().extend(pv.Layout.prototype.defaults).rows(1).cols(1);

pv.Layout.Grid.prototype.buildImplied = function(s) {
    pv.Layout.prototype.buildImplied.call(this, s);
    var r = s.rows, c = s.cols;
    "object" == typeof c && (r = pv.transpose(c));
    if ("object" == typeof r) {
        s.$grid = pv.blend(r);
        s.rows = r.length;
        s.cols = r[0] ? r[0].length : 0;
    } else s.$grid = pv.repeat([ s.data ], r * c);
};

pv.Layout.Stack = function() {
    function proxy(name) {
        return function() {
            return prop[name](this.parent.index, this.index);
        };
    }
    pv.Layout.call(this);
    var values, that = this, none = function() {
        return null;
    }, prop = {
        t: none,
        l: none,
        r: none,
        b: none,
        w: none,
        h: none
    }, buildImplied = that.buildImplied;
    this.buildImplied = function(s) {
        buildImplied.call(this, s);
        var m, data = s.layers, n = data.length, orient = s.orient, horizontal = /^(top|bottom)\b/.test(orient), h = this.parent[horizontal ? "height" : "width"](), x = [], y = [], dy = [], stack = pv.Mark.stack, o = {
            parent: {
                parent: this
            }
        };
        stack.unshift(null);
        values = [];
        for (var i = 0; n > i; i++) {
            dy[i] = [];
            y[i] = [];
            o.parent.index = i;
            stack[0] = data[i];
            values[i] = this.$values.apply(o.parent, stack);
            i || (m = values[i].length);
            stack.unshift(null);
            for (var j = 0; m > j; j++) {
                stack[0] = values[i][j];
                o.index = j;
                i || (x[j] = this.$x.apply(o, stack));
                dy[i][j] = this.$y.apply(o, stack);
            }
            stack.shift();
        }
        stack.shift();
        var index;
        switch (s.order) {
          case "inside-out":
            for (var max = dy.map(function(v) {
                return pv.max.index(v);
            }), map = pv.range(n).sort(function(a, b) {
                return max[a] - max[b];
            }), sums = dy.map(function(v) {
                return pv.sum(v);
            }), top = 0, bottom = 0, tops = [], bottoms = [], i = 0; n > i; i++) {
                var j = map[i];
                if (bottom > top) {
                    top += sums[j];
                    tops.push(j);
                } else {
                    bottom += sums[j];
                    bottoms.push(j);
                }
            }
            index = bottoms.reverse().concat(tops);
            break;

          case "reverse":
            index = pv.range(n - 1, -1, -1);
            break;

          default:
            index = pv.range(n);
        }
        switch (s.offset) {
          case "silohouette":
            for (var j = 0; m > j; j++) {
                for (var o = 0, i = 0; n > i; i++) o += dy[i][j];
                y[index[0]][j] = (h - o) / 2;
            }
            break;

          case "wiggle":
            for (var o = 0, i = 0; n > i; i++) o += dy[i][0];
            y[index[0]][0] = o = (h - o) / 2;
            for (var j = 1; m > j; j++) {
                for (var s1 = 0, s2 = 0, dx = x[j] - x[j - 1], i = 0; n > i; i++) s1 += dy[i][j];
                for (var i = 0; n > i; i++) {
                    for (var s3 = (dy[index[i]][j] - dy[index[i]][j - 1]) / (2 * dx), k = 0; i > k; k++) s3 += (dy[index[k]][j] - dy[index[k]][j - 1]) / dx;
                    s2 += s3 * dy[index[i]][j];
                }
                y[index[0]][j] = o -= s1 ? s2 / s1 * dx : 0;
            }
            break;

          case "expand":
            for (var j = 0; m > j; j++) {
                y[index[0]][j] = 0;
                for (var k = 0, i = 0; n > i; i++) k += dy[i][j];
                if (k) {
                    k = h / k;
                    for (var i = 0; n > i; i++) dy[i][j] *= k;
                } else {
                    k = h / n;
                    for (var i = 0; n > i; i++) dy[i][j] = k;
                }
            }
            break;

          default:
            for (var j = 0; m > j; j++) y[index[0]][j] = 0;
        }
        for (var j = 0; m > j; j++) for (var o = y[index[0]][j], i = 1; n > i; i++) {
            o += dy[index[i - 1]][j];
            y[index[i]][j] = o;
        }
        var i = orient.indexOf("-"), pdy = horizontal ? "h" : "w", px = 0 > i ? horizontal ? "l" : "b" : orient.charAt(i + 1), py = orient.charAt(0);
        for (var p in prop) prop[p] = none;
        prop[px] = function(i, j) {
            return x[j];
        };
        prop[py] = function(i, j) {
            return y[i][j];
        };
        prop[pdy] = function(i, j) {
            return dy[i][j];
        };
    };
    this.layer = new pv.Mark().data(function() {
        return values[this.parent.index];
    }).top(proxy("t")).left(proxy("l")).right(proxy("r")).bottom(proxy("b")).width(proxy("w")).height(proxy("h"));
    this.layer.add = function(type) {
        return that.add(pv.Panel).data(function() {
            return that.layers();
        }).add(type).extend(this);
    };
};

pv.Layout.Stack.prototype = pv.extend(pv.Layout).property("orient", String).property("offset", String).property("order", String).property("layers");

pv.Layout.Stack.prototype.defaults = new pv.Layout.Stack().extend(pv.Layout.prototype.defaults).orient("bottom-left").offset("zero").layers([ [] ]);

pv.Layout.Stack.prototype.$x = pv.Layout.Stack.prototype.$y = function() {
    return 0;
};

pv.Layout.Stack.prototype.x = function(f) {
    this.$x = pv.functor(f);
    return this;
};

pv.Layout.Stack.prototype.y = function(f) {
    this.$y = pv.functor(f);
    return this;
};

pv.Layout.Stack.prototype.$values = pv.identity;

pv.Layout.Stack.prototype.values = function(f) {
    this.$values = pv.functor(f);
    return this;
};

pv.Layout.Band = function() {
    function proxy(name) {
        return function() {
            return itemProps[name](this.index, this.parent.index);
        };
    }
    pv.Layout.call(this);
    var itemProps, values, that = this, buildImplied = that.buildImplied, itemProto = new pv.Mark().data(function() {
        return values[this.parent.index];
    }).top(proxy("t")).left(proxy("l")).right(proxy("r")).bottom(proxy("b")).width(proxy("w")).height(proxy("h")).antialias(proxy("antialias"));
    this.buildImplied = function(s) {
        buildImplied.call(this, s);
        itemProps = Object.create(pv.Layout.Band.$baseItemProps);
        values = [];
        var data = s.layers, L = data.length;
        if (L > 0) {
            var orient = s.orient, horizontal = /^(top|bottom)\b/.test(orient), bh = this.parent[horizontal ? "height" : "width"](), bands = this._readData(data, values, s), B = bands.length;
            "reverse" === s.bandOrder && bands.reverse();
            if ("reverse" === s.order) {
                values.reverse();
                for (var b = 0; B > b; b++) bands[b].items.reverse();
            }
            switch (s.layout) {
              case "grouped":
                this._calcGrouped(bands, L, s);
                break;

              case "stacked":
                this._calcStacked(bands, L, bh, s);
            }
            for (var hZero = s.hZero || 0, isStacked = "stacked" === s.layout, i = 0; B > i; i++) for (var band = bands[i], hMargin2 = isStacked ? Math.max(0, band.vertiMargin) / 2 : 0, j = 0; L > j; j++) {
                var item = band.items[j];
                if (item.zero) {
                    item.h = hZero;
                    item.y -= hMargin2 + hZero / 2;
                }
            }
            this._bindItemProps(bands, itemProps, orient, horizontal);
        }
    };
    var itemAccessor = this.item = {
        end: this,
        add: function(type) {
            return that.add(pv.Panel).data(function() {
                return that.layers();
            }).add(type).extend(itemProto);
        },
        order: function(value) {
            that.order(value);
            return this;
        },
        w: function(f) {
            that.$iw = pv.functor(f);
            return this;
        },
        h: function(f) {
            that.$ih = pv.functor(f);
            return this;
        },
        horizontalRatio: function(f) {
            that.$ihorizRatio = pv.functor(f);
            return this;
        },
        verticalMargin: function(f) {
            that.$ivertiMargin = pv.functor(f);
            return this;
        }
    }, bandAccessor = this.band = {
        end: this,
        w: function(f) {
            that.$bw = pv.functor(f);
            return this;
        },
        x: function(f) {
            that.$bx = pv.functor(f);
            return this;
        },
        order: function(value) {
            that.bandOrder(value);
            return this;
        },
        differentialControl: function(f) {
            that.$bDiffControl = pv.functor(f);
            return this;
        }
    };
    this.band.item = itemAccessor;
    this.item.band = bandAccessor;
};

pv.Layout.Band.$baseItemProps = function() {
    var none = function() {
        return null;
    };
    return {
        t: none,
        l: none,
        r: none,
        b: none,
        w: none,
        h: none
    };
}();

pv.Layout.Band.prototype = pv.extend(pv.Layout).property("orient", String).property("layout", String).property("layers").property("yZero", Number).property("hZero", Number).property("verticalMode", String).property("horizontalMode", String).property("order", String).property("bandOrder", String);

pv.Layout.Band.prototype.defaults = new pv.Layout.Band().extend(pv.Layout.prototype.defaults).orient("bottom-left").layout("grouped").yZero(0).hZero(1.5).layers([ [] ]);

pv.Layout.Band.prototype.$bx = pv.Layout.Band.prototype.$bw = pv.Layout.Band.prototype.$bDiffControl = pv.Layout.Band.prototype.$iw = pv.Layout.Band.prototype.$ih = pv.Layout.Band.prototype.$ivertiMargin = pv.functor(0);

pv.Layout.Band.prototype.$ihorizRatio = pv.functor(.9);

pv.Layout.Band.prototype.$values = pv.identity;

pv.Layout.Band.prototype.values = function(f) {
    this.$values = pv.functor(f);
    return this;
};

pv.Layout.prototype._readData = function(data, layersValues, scene) {
    var B, L = data.length, bands = [], stack = pv.Mark.stack, hZero = scene.hZero, o = {
        parent: {
            parent: this
        }
    };
    stack.unshift(null);
    for (var l = 0; L > l; l++) {
        o.parent.index = l;
        stack[0] = data[l];
        var layerValues = layersValues[l] = this.$values.apply(o.parent, stack);
        l || (B = layerValues.length);
        stack.unshift(null);
        for (var b = 0; B > b; b++) {
            stack[0] = layerValues[b];
            o.index = b;
            var band = bands[b];
            band || (band = bands[b] = {
                horizRatio: this.$ihorizRatio.apply(o, stack),
                vertiMargin: this.$ivertiMargin.apply(o, stack),
                w: this.$bw.apply(o, stack),
                x: this.$bx.apply(o, stack),
                diffControl: this.$bDiffControl ? this.$bDiffControl.apply(o, stack) : 0,
                items: []
            });
            var ih = this.$ih.apply(o, stack), h = null != ih ? Math.abs(ih) : ih;
            band.items[l] = {
                y: scene.yZero || 0,
                x: 0,
                w: this.$iw.apply(o, stack),
                h: h,
                zero: null != h && hZero >= h,
                dir: 0 > ih ? -1 : 1
            };
        }
        stack.shift();
    }
    stack.shift();
    return bands;
};

pv.Layout.Band.prototype._calcGrouped = function(bands, L, scene) {
    for (var b = 0, B = bands.length; B > b; b++) {
        for (var band = bands[b], items = band.items, w = band.w, horizRatio = band.horizRatio, wItems = 0, l = 0; L > l; l++) wItems += items[l].w;
        1 === L ? horizRatio = 1 : horizRatio > 0 && 1 >= horizRatio || (horizRatio = 1);
        if (null == w) w = band.w = wItems / horizRatio; else if ("expand" === scene.horizontalMode) {
            var wItems2 = horizRatio * w;
            if (wItems) for (var wScale = wItems2 / wItems, l = 0; L > l; l++) items[l].w *= wScale; else for (var wiavg = wItems2 / L, l = 0; L > l; l++) items[l].w = wiavg;
            wItems = wItems2;
        }
        for (var wItemsWithMargin = wItems / horizRatio, ix = band.x - wItemsWithMargin / 2, margin = L > 1 ? (wItemsWithMargin - wItems) / (L - 1) : 0, l = 0; L > l; l++) {
            var item = items[l];
            item.x = ix;
            ix += item.w + margin;
            item.dir < 0 && (item.y -= item.h);
        }
    }
};

pv.Layout.Band.prototype._calcStacked = function(bands, L, bh, scene) {
    var items, B = bands.length;
    if ("expand" === scene.verticalMode) for (var b = 0; B > b; b++) {
        items = bands[b].items;
        for (var hSum = null, nonNullCount = 0, l = 0; L > l; l++) {
            var item = items[l];
            item.dir = 1;
            var h = item.h;
            if (null != h) {
                nonNullCount++;
                hSum += h;
            }
        }
        if (nonNullCount) if (hSum) for (var hScale = bh / hSum, l = 0; L > l; l++) {
            var h = items[l].h;
            null != h && (items[l].h = h * hScale);
        } else if (0 == hSum) for (var l = 0; L > l; l++) items[l].h = 0; else for (var hAvg = bh / nonNullCount, l = 0; L > l; l++) {
            var h = items[l].h;
            null != h && (items[l].h = hAvg);
        }
    }
    for (var yZero = scene.yZero, yOffset = yZero, b = 0; B > b; b++) {
        var band = bands[b], bx = band.x, bDiffControl = band.diffControl, positiveGoesDown = 0 > bDiffControl, vertiMargin = Math.max(0, band.vertiMargin);
        items = band.items;
        var resultPos = this._layoutItemsOfDir(1, positiveGoesDown, items, vertiMargin, bx, yOffset), resultNeg = null;
        resultPos.existsOtherDir && (resultNeg = this._layoutItemsOfDir(-1, positiveGoesDown, items, vertiMargin, bx, yOffset));
        if (bDiffControl) {
            if (1 === Math.abs(bDiffControl)) {
                var yOffset0 = yOffset;
                yOffset = resultPos.yOffset;
                resultNeg && (yOffset -= yOffset0 - resultNeg.yOffset);
            }
        } else yOffset = yZero;
    }
};

pv.Layout.Band.prototype._layoutItemsOfDir = function(stackDir, positiveGoesDown, items, vertiMargin, bx, yOffset) {
    for (var existsOtherDir = !1, vertiMargin2 = vertiMargin / 2, efDir = positiveGoesDown ? -stackDir : stackDir, reverseLayers = positiveGoesDown, l = 0, L = items.length; L > l; l += 1) {
        var item = items[reverseLayers ? L - l - 1 : l];
        if (item.dir === stackDir) {
            var h = item.h || 0;
            if (efDir > 0) {
                item.y = yOffset + vertiMargin2;
                yOffset += h;
            } else {
                item.y = yOffset - (h - vertiMargin2);
                yOffset -= h;
            }
            var h2 = h - vertiMargin;
            item.h = h2 > 0 ? h2 : 0;
            item.x = bx - item.w / 2;
        } else existsOtherDir = !0;
    }
    return {
        existsOtherDir: existsOtherDir,
        yOffset: yOffset
    };
};

pv.Layout.Band.prototype._bindItemProps = function(bands, itemProps, orient, horizontal) {
    var index = orient.indexOf("-"), ph = horizontal ? "h" : "w", pw = horizontal ? "w" : "h", px = 0 > index ? horizontal ? "l" : "b" : orient.charAt(index + 1), py = orient.charAt(0);
    itemProps[px] = function(b, l) {
        return bands[b].items[l].x;
    };
    itemProps[py] = function(b, l) {
        return bands[b].items[l].y;
    };
    itemProps[pw] = function(b, l) {
        return bands[b].items[l].w;
    };
    itemProps[ph] = function(b, l) {
        return bands[b].items[l].h || 0;
    };
    itemProps.antialias = function(b, l) {
        return bands[b].items[l].zero;
    };
};

pv.Layout.Treemap = function() {
    pv.Layout.Hierarchy.call(this);
    this.node.strokeStyle("#fff").fillStyle("rgba(31, 119, 180, .25)").width(function(n) {
        return n.dx;
    }).height(function(n) {
        return n.dy;
    });
    this.label.visible(function(n) {
        return !n.firstChild;
    }).left(function(n) {
        return n.x + n.dx / 2;
    }).top(function(n) {
        return n.y + n.dy / 2;
    }).textAlign("center").textAngle(function(n) {
        return n.dx > n.dy ? 0 : -Math.PI / 2;
    });
    (this.leaf = new pv.Mark().extend(this.node).fillStyle(null).strokeStyle(null).visible(function(n) {
        return !n.firstChild;
    })).parent = this;
    delete this.link;
};

pv.Layout.Treemap.prototype = pv.extend(pv.Layout.Hierarchy).property("round", Boolean).property("mode", String).property("order", String);

pv.Layout.Treemap.prototype.defaults = new pv.Layout.Treemap().extend(pv.Layout.Hierarchy.prototype.defaults).mode("squarify").order("ascending");

pv.Layout.Treemap.prototype.$size = function(d) {
    return Number(d.nodeValue);
};

pv.Layout.Treemap.prototype.$padLeft = pv.Layout.Treemap.prototype.$padRight = pv.Layout.Treemap.prototype.$padBottom = pv.Layout.Treemap.prototype.$padTop = function() {
    return 0;
};

pv.Layout.Treemap.prototype.size = function(f) {
    this.$size = pv.functor(f);
    return this;
};

pv.Layout.Treemap.prototype.padding = function(n) {
    n = pv.functor(n);
    return this.paddingLeft(n).paddingRight(n).paddingTop(n).paddingBottom(n);
};

pv.Layout.Treemap.prototype.paddingLeft = function(f) {
    if (arguments.length) {
        this.$padLeft = pv.functor(f);
        return this;
    }
    return this.$padLeft;
};

pv.Layout.Treemap.prototype.paddingRight = function(f) {
    if (arguments.length) {
        this.$padRight = pv.functor(f);
        return this;
    }
    return this.$padRight;
};

pv.Layout.Treemap.prototype.paddingBottom = function(f) {
    if (arguments.length) {
        this.$padBottom = pv.functor(f);
        return this;
    }
    return this.$padBottom;
};

pv.Layout.Treemap.prototype.paddingTop = function(f) {
    if (arguments.length) {
        this.$padTop = pv.functor(f);
        return this;
    }
    return this.$padTop;
};

pv.Layout.Treemap.prototype.buildImplied = function(s) {
    function slice(row, sum, horizontal, x, y, w, h) {
        for (var i = 0, d = 0; i < row.length; i++) {
            var n = row[i];
            if (horizontal) {
                n.x = x + d;
                n.y = y;
                d += n.dx = round(w * n.size / sum);
                n.dy = h;
            } else {
                n.x = x;
                n.y = y + d;
                n.dx = w;
                d += n.dy = round(h * n.size / sum);
            }
        }
        n && (horizontal ? n.dx += w - d : n.dy += h - d);
    }
    function ratio(row, l) {
        for (var rmax = -1/0, rmin = 1/0, s = 0, i = 0; i < row.length; i++) {
            var r = row[i].size;
            rmin > r && (rmin = r);
            r > rmax && (rmax = r);
            s += r;
        }
        s *= s;
        l *= l;
        return Math.max(l * rmax / s, s / (l * rmin));
    }
    function layout(n, i) {
        function position(row) {
            var horizontal = w == l, sum = pv.sum(row, size), r = l ? round(sum / l) : 0;
            slice(row, sum, horizontal, x, y, horizontal ? w : r, horizontal ? r : h);
            if (horizontal) {
                y += r;
                h -= r;
            } else {
                x += r;
                w -= r;
            }
            l = Math.min(w, h);
            return horizontal;
        }
        var p = n.parentNode, x = n.x, y = n.y, w = n.dx, h = n.dy;
        if (p) {
            x += p.paddingLeft;
            y += p.paddingTop;
            w += -p.paddingLeft - p.paddingRight, h += -p.paddingTop - p.paddingBottom;
        }
        if ("squarify" == mode) {
            var row = [], mink = 1/0, l = Math.min(w, h), k = w * h / n.size;
            if (!(n.size <= 0)) {
                n.visitBefore(function(n) {
                    n.size *= k;
                });
                for (var children = n.childNodes.slice(); children.length; ) {
                    var child = children[children.length - 1];
                    if (child.size) {
                        row.push(child);
                        var k = ratio(row, l);
                        if (mink >= k) {
                            children.pop();
                            mink = k;
                        } else {
                            row.pop();
                            position(row);
                            row.length = 0;
                            mink = 1/0;
                        }
                    } else children.pop();
                }
                if (position(row)) for (var i = 0; i < row.length; i++) row[i].dy += h; else for (var i = 0; i < row.length; i++) row[i].dx += w;
            }
        } else slice(n.childNodes, n.size, "slice" == mode ? !0 : "dice" == mode ? !1 : 1 & i, x, y, w, h);
    }
    if (!pv.Layout.Hierarchy.prototype.buildImplied.call(this, s)) {
        var that = this, nodes = s.nodes, root = nodes[0], stack = pv.Mark.stack, size = function(n) {
            return n.size;
        }, round = s.round ? Math.round : Number, mode = s.mode;
        stack.unshift(null);
        try {
            root.visitAfter(function(n, i) {
                n.depth = i;
                n.x = n.y = n.dx = n.dy = 0;
                stack[0] = n;
                if (n.firstChild) {
                    n.size = pv.sum(n.childNodes, size);
                    n.paddingRight = +that.$padRight.apply(that, stack) || 0;
                    n.paddingLeft = +that.$padLeft.apply(that, stack) || 0;
                    n.paddingBottom = +that.$padBottom.apply(that, stack) || 0;
                    n.paddingTop = +that.$padTop.apply(that, stack) || 0;
                } else n.size = that.$size.apply(that, stack);
            });
        } finally {
            stack.shift();
        }
        switch (s.order) {
          case "ascending":
            root.sort(function(a, b) {
                return a.size - b.size;
            });
            break;

          case "descending":
            root.sort(function(a, b) {
                return b.size - a.size;
            });
            break;

          case "reverse":
            root.reverse();
        }
        root.x = 0;
        root.y = 0;
        root.dx = s.width;
        root.dy = s.height;
        root.visitBefore(layout);
    }
};

pv.Layout.Tree = function() {
    pv.Layout.Hierarchy.call(this);
};

pv.Layout.Tree.prototype = pv.extend(pv.Layout.Hierarchy).property("group", Number).property("breadth", Number).property("depth", Number).property("orient", String);

pv.Layout.Tree.prototype.defaults = new pv.Layout.Tree().extend(pv.Layout.Hierarchy.prototype.defaults).group(1).breadth(15).depth(60).orient("top");

pv.Layout.Tree.prototype.buildImplied = function(s) {
    function firstWalk(v) {
        var l, r, a;
        if (v.firstChild) {
            l = v.firstChild;
            r = v.lastChild;
            a = l;
            for (var c = l; c; c = c.nextSibling) {
                firstWalk(c);
                a = apportion(c, a);
            }
            executeShifts(v);
            var midpoint = .5 * (l.prelim + r.prelim);
            if (l = v.previousSibling) {
                v.prelim = l.prelim + distance(v.depth, !0);
                v.mod = v.prelim - midpoint;
            } else v.prelim = midpoint;
        } else (l = v.previousSibling) && (v.prelim = l.prelim + distance(v.depth, !0));
    }
    function secondWalk(v, m, depth) {
        v.breadth = v.prelim + m;
        m += v.mod;
        for (var c = v.firstChild; c; c = c.nextSibling) secondWalk(c, m, depth);
    }
    function apportion(v, a) {
        var w = v.previousSibling;
        if (w) {
            for (var vip = v, vop = v, vim = w, vom = v.parentNode.firstChild, sip = vip.mod, sop = vop.mod, sim = vim.mod, som = vom.mod, nr = nextRight(vim), nl = nextLeft(vip); nr && nl; ) {
                vim = nr;
                vip = nl;
                vom = nextLeft(vom);
                vop = nextRight(vop);
                vop.ancestor = v;
                var shift = vim.prelim + sim - (vip.prelim + sip) + distance(vim.depth, !1);
                if (shift > 0) {
                    moveSubtree(ancestor(vim, v, a), v, shift);
                    sip += shift;
                    sop += shift;
                }
                sim += vim.mod;
                sip += vip.mod;
                som += vom.mod;
                sop += vop.mod;
                nr = nextRight(vim);
                nl = nextLeft(vip);
            }
            if (nr && !nextRight(vop)) {
                vop.thread = nr;
                vop.mod += sim - sop;
            }
            if (nl && !nextLeft(vom)) {
                vom.thread = nl;
                vom.mod += sip - som;
                a = v;
            }
        }
        return a;
    }
    function nextLeft(v) {
        return v.firstChild || v.thread;
    }
    function nextRight(v) {
        return v.lastChild || v.thread;
    }
    function moveSubtree(wm, wp, shift) {
        var subtrees = wp.number - wm.number;
        wp.change -= shift / subtrees;
        wp.shift += shift;
        wm.change += shift / subtrees;
        wp.prelim += shift;
        wp.mod += shift;
    }
    function executeShifts(v) {
        for (var shift = 0, change = 0, c = v.lastChild; c; c = c.previousSibling) {
            c.prelim += shift;
            c.mod += shift;
            change += c.change;
            shift += c.shift + change;
        }
    }
    function ancestor(vim, v, a) {
        return vim.ancestor.parentNode == v.parentNode ? vim.ancestor : a;
    }
    function distance(depth, siblings) {
        return (siblings ? 1 : group + 1) / ("radial" == orient ? depth : 1);
    }
    function midAngle(n) {
        return "radial" == orient ? n.breadth / depth : 0;
    }
    function x(n) {
        switch (orient) {
          case "left":
            return n.depth;

          case "right":
            return w - n.depth;

          case "top":
          case "bottom":
            return n.breadth + w / 2;

          case "radial":
            return w / 2 + n.depth * Math.cos(midAngle(n));
        }
    }
    function y(n) {
        switch (orient) {
          case "left":
          case "right":
            return n.breadth + h / 2;

          case "top":
            return n.depth;

          case "bottom":
            return h - n.depth;

          case "radial":
            return h / 2 + n.depth * Math.sin(midAngle(n));
        }
    }
    if (!pv.Layout.Hierarchy.prototype.buildImplied.call(this, s)) {
        var nodes = s.nodes, orient = s.orient, depth = s.depth, breadth = s.breadth, group = s.group, w = s.width, h = s.height, root = nodes[0];
        root.visitAfter(function(v, i) {
            v.ancestor = v;
            v.prelim = 0;
            v.mod = 0;
            v.change = 0;
            v.shift = 0;
            v.number = v.previousSibling ? v.previousSibling.number + 1 : 0;
            v.depth = i;
        });
        firstWalk(root);
        secondWalk(root, -root.prelim, 0);
        root.visitAfter(function(v) {
            v.breadth *= breadth;
            v.depth *= depth;
            v.midAngle = midAngle(v);
            v.x = x(v);
            v.y = y(v);
            v.firstChild && (v.midAngle += Math.PI);
            delete v.breadth;
            delete v.depth;
            delete v.ancestor;
            delete v.prelim;
            delete v.mod;
            delete v.change;
            delete v.shift;
            delete v.number;
            delete v.thread;
        });
    }
};

pv.Layout.Indent = function() {
    pv.Layout.Hierarchy.call(this);
    this.link.interpolate("step-after");
};

pv.Layout.Indent.prototype = pv.extend(pv.Layout.Hierarchy).property("depth", Number).property("breadth", Number);

pv.Layout.Indent.prototype.defaults = new pv.Layout.Indent().extend(pv.Layout.Hierarchy.prototype.defaults).depth(15).breadth(15);

pv.Layout.Indent.prototype.buildImplied = function(s) {
    function position(n, breadth, depth) {
        n.x = ax + depth++ * dspace;
        n.y = ay + breadth++ * bspace;
        n.midAngle = 0;
        for (var c = n.firstChild; c; c = c.nextSibling) breadth = position(c, breadth, depth);
        return breadth;
    }
    if (!pv.Layout.Hierarchy.prototype.buildImplied.call(this, s)) {
        var nodes = s.nodes, bspace = s.breadth, dspace = s.depth, ax = 0, ay = 0;
        position(nodes[0], 1, 1);
    }
};

pv.Layout.Pack = function() {
    pv.Layout.Hierarchy.call(this);
    this.node.shapeRadius(function(n) {
        return n.radius;
    }).strokeStyle("rgb(31, 119, 180)").fillStyle("rgba(31, 119, 180, .25)");
    this.label.textAlign("center");
    delete this.link;
};

pv.Layout.Pack.prototype = pv.extend(pv.Layout.Hierarchy).property("spacing", Number).property("order", String);

pv.Layout.Pack.prototype.defaults = new pv.Layout.Pack().extend(pv.Layout.Hierarchy.prototype.defaults).spacing(1).order("ascending");

pv.Layout.Pack.prototype.$radius = function() {
    return 1;
};

pv.Layout.Pack.prototype.size = function(f) {
    this.$radius = "function" == typeof f ? function() {
        return Math.sqrt(f.apply(this, arguments));
    } : (f = Math.sqrt(f), function() {
        return f;
    });
    return this;
};

pv.Layout.Pack.prototype.buildImplied = function(s) {
    function radii(nodes) {
        var stack = pv.Mark.stack;
        stack.unshift(null);
        for (var i = 0, n = nodes.length; n > i; i++) {
            var c = nodes[i];
            c.firstChild || (c.radius = that.$radius.apply(that, (stack[0] = c, stack)));
        }
        stack.shift();
    }
    function packTree(n) {
        for (var nodes = [], c = n.firstChild; c; c = c.nextSibling) {
            c.firstChild && (c.radius = packTree(c));
            c.n = c.p = c;
            nodes.push(c);
        }
        switch (s.order) {
          case "ascending":
            nodes.sort(function(a, b) {
                return a.radius - b.radius;
            });
            break;

          case "descending":
            nodes.sort(function(a, b) {
                return b.radius - a.radius;
            });
            break;

          case "reverse":
            nodes.reverse();
        }
        return packCircle(nodes);
    }
    function packCircle(nodes) {
        function bound(n) {
            xMin = Math.min(n.x - n.radius, xMin);
            xMax = Math.max(n.x + n.radius, xMax);
            yMin = Math.min(n.y - n.radius, yMin);
            yMax = Math.max(n.y + n.radius, yMax);
        }
        function insert(a, b) {
            var c = a.n;
            a.n = b;
            b.p = a;
            b.n = c;
            c.p = b;
        }
        function splice(a, b) {
            a.n = b;
            b.p = a;
        }
        function intersects(a, b) {
            var dx = b.x - a.x, dy = b.y - a.y, dr = a.radius + b.radius;
            return dr * dr - dx * dx - dy * dy > .001;
        }
        var a, b, c, j, k, xMin = 1/0, xMax = -1/0, yMin = 1/0, yMax = -1/0;
        a = nodes[0];
        a.x = -a.radius;
        a.y = 0;
        bound(a);
        if (nodes.length > 1) {
            b = nodes[1];
            b.x = b.radius;
            b.y = 0;
            bound(b);
            if (nodes.length > 2) {
                c = nodes[2];
                place(a, b, c);
                bound(c);
                insert(a, c);
                a.p = c;
                insert(c, b);
                b = a.n;
                for (var i = 3; i < nodes.length; i++) {
                    place(a, b, c = nodes[i]);
                    var isect = 0, s1 = 1, s2 = 1;
                    for (j = b.n; j != b; j = j.n, s1++) if (intersects(j, c)) {
                        isect = 1;
                        break;
                    }
                    if (1 == isect) for (k = a.p; k != j.p; k = k.p, s2++) if (intersects(k, c)) {
                        if (s1 > s2) {
                            isect = -1;
                            j = k;
                        }
                        break;
                    }
                    if (0 == isect) {
                        insert(a, c);
                        b = c;
                        bound(c);
                    } else if (isect > 0) {
                        splice(a, j);
                        b = j;
                        i--;
                    } else if (0 > isect) {
                        splice(j, b);
                        a = j;
                        i--;
                    }
                }
            }
        }
        for (var cx = (xMin + xMax) / 2, cy = (yMin + yMax) / 2, cr = 0, i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            n.x -= cx;
            n.y -= cy;
            cr = Math.max(cr, n.radius + Math.sqrt(n.x * n.x + n.y * n.y));
        }
        return cr + s.spacing;
    }
    function place(a, b, c) {
        var da = b.radius + c.radius, db = a.radius + c.radius, dx = b.x - a.x, dy = b.y - a.y, dc = Math.sqrt(dx * dx + dy * dy), cos = (db * db + dc * dc - da * da) / (2 * db * dc), theta = Math.acos(cos), x = cos * db, h = Math.sin(theta) * db;
        dx /= dc;
        dy /= dc;
        c.x = a.x + x * dx + h * dy;
        c.y = a.y + x * dy - h * dx;
    }
    function transform(n, x, y, k) {
        for (var c = n.firstChild; c; c = c.nextSibling) {
            c.x += n.x;
            c.y += n.y;
            transform(c, x, y, k);
        }
        n.x = x + k * n.x;
        n.y = y + k * n.y;
        n.radius *= k;
    }
    if (!pv.Layout.Hierarchy.prototype.buildImplied.call(this, s)) {
        var that = this, nodes = s.nodes, root = nodes[0];
        radii(nodes);
        root.x = 0;
        root.y = 0;
        root.radius = packTree(root);
        var w = this.width(), h = this.height(), k = 1 / Math.max(2 * root.radius / w, 2 * root.radius / h);
        transform(root, w / 2, h / 2, k);
    }
};

pv.Layout.Force = function() {
    pv.Layout.Network.call(this);
    this.link.lineWidth(function(d, p) {
        return 1.5 * Math.sqrt(p.linkValue);
    });
    this.label.textAlign("center");
};

pv.Layout.Force.prototype = pv.extend(pv.Layout.Network).property("bound", Boolean).property("iterations", Number).property("dragConstant", Number).property("chargeConstant", Number).property("chargeMinDistance", Number).property("chargeMaxDistance", Number).property("chargeTheta", Number).property("springConstant", Number).property("springDamping", Number).property("springLength", Number);

pv.Layout.Force.prototype.defaults = new pv.Layout.Force().extend(pv.Layout.Network.prototype.defaults).dragConstant(.1).chargeConstant(-40).chargeMinDistance(2).chargeMaxDistance(500).chargeTheta(.9).springConstant(.1).springDamping(.3).springLength(20);

pv.Layout.Force.prototype.buildImplied = function(s) {
    function speed(n) {
        return n.fix ? 1 : n.vx * n.vx + n.vy * n.vy;
    }
    if (pv.Layout.Network.prototype.buildImplied.call(this, s)) {
        var f = s.$force;
        if (f) {
            f.next = this.binds.$force;
            this.binds.$force = f;
        }
    } else {
        for (var n, that = this, nodes = s.nodes, links = s.links, k = s.iterations, w = s.width, h = s.height, i = 0; i < nodes.length; i++) {
            n = nodes[i];
            isNaN(n.x) && (n.x = w / 2 + 40 * Math.random() - 20);
            isNaN(n.y) && (n.y = h / 2 + 40 * Math.random() - 20);
        }
        var sim = pv.simulation(nodes);
        sim.force(pv.Force.drag(s.dragConstant));
        sim.force(pv.Force.charge(s.chargeConstant).domain(s.chargeMinDistance, s.chargeMaxDistance).theta(s.chargeTheta));
        sim.force(pv.Force.spring(s.springConstant).damping(s.springDamping).length(s.springLength).links(links));
        sim.constraint(pv.Constraint.position());
        s.bound && sim.constraint(pv.Constraint.bound().x(6, w - 6).y(6, h - 6));
        if (null == k) {
            sim.step();
            sim.step();
            {
                s.$force = this.binds.$force = {
                    next: this.binds.$force,
                    nodes: nodes,
                    min: 1e-4 * (links.length + 1),
                    sim: sim
                };
            }
            this.$timer || (this.$timer = setInterval(function() {
                for (var render = !1, f = that.binds.$force; f; f = f.next) if (pv.max(f.nodes, speed) > f.min) {
                    f.sim.step();
                    render = !0;
                }
                render && that.render();
            }, 42));
        } else for (var i = 0; k > i; i++) sim.step();
    }
};

pv.Layout.Cluster = function() {
    pv.Layout.Hierarchy.call(this);
    var interpolate, buildImplied = this.buildImplied;
    this.buildImplied = function(s) {
        buildImplied.call(this, s);
        interpolate = /^(top|bottom)$/.test(s.orient) ? "step-before" : /^(left|right)$/.test(s.orient) ? "step-after" : "linear";
    };
    this.link.interpolate(function() {
        return interpolate;
    });
};

pv.Layout.Cluster.prototype = pv.extend(pv.Layout.Hierarchy).property("group", Number).property("orient", String).property("innerRadius", Number).property("outerRadius", Number);

pv.Layout.Cluster.prototype.defaults = new pv.Layout.Cluster().extend(pv.Layout.Hierarchy.prototype.defaults).group(0).orient("top");

pv.Layout.Cluster.prototype.buildImplied = function(s) {
    if (!pv.Layout.Hierarchy.prototype.buildImplied.call(this, s)) {
        var breadth, depth, root = s.nodes[0], group = s.group, leafCount = 0, leafIndex = .5 - group / 2, p = void 0;
        root.visitAfter(function(n) {
            if (n.firstChild) n.depth = 1 + pv.max(n.childNodes, function(n) {
                return n.depth;
            }); else {
                if (group && p != n.parentNode) {
                    p = n.parentNode;
                    leafCount += group;
                }
                leafCount++;
                n.depth = 0;
            }
        });
        breadth = 1 / leafCount;
        depth = 1 / root.depth;
        var p = void 0;
        root.visitAfter(function(n) {
            if (n.firstChild) n.breadth = pv.mean(n.childNodes, function(n) {
                return n.breadth;
            }); else {
                if (group && p != n.parentNode) {
                    p = n.parentNode;
                    leafIndex += group;
                }
                n.breadth = breadth * leafIndex++;
            }
            n.depth = 1 - n.depth * depth;
        });
        root.visitAfter(function(n) {
            n.minBreadth = n.firstChild ? n.firstChild.minBreadth : n.breadth - breadth / 2;
            n.maxBreadth = n.firstChild ? n.lastChild.maxBreadth : n.breadth + breadth / 2;
        });
        root.visitBefore(function(n) {
            n.minDepth = n.parentNode ? n.parentNode.maxDepth : 0;
            n.maxDepth = n.parentNode ? n.depth + root.depth : n.minDepth + 2 * root.depth;
        });
        root.minDepth = -depth;
        pv.Layout.Hierarchy.NodeLink.buildImplied.call(this, s);
    }
};

pv.Layout.Cluster.Fill = function() {
    pv.Layout.Cluster.call(this);
    pv.Layout.Hierarchy.Fill.constructor.call(this);
};

pv.Layout.Cluster.Fill.prototype = pv.extend(pv.Layout.Cluster);

pv.Layout.Cluster.Fill.prototype.buildImplied = function(s) {
    pv.Layout.Cluster.prototype.buildImplied.call(this, s) || pv.Layout.Hierarchy.Fill.buildImplied.call(this, s);
};

pv.Layout.Partition = function() {
    pv.Layout.Hierarchy.call(this);
};

pv.Layout.Partition.prototype = pv.extend(pv.Layout.Hierarchy).property("order", String).property("orient", String).property("innerRadius", Number).property("outerRadius", Number);

pv.Layout.Partition.prototype.defaults = new pv.Layout.Partition().extend(pv.Layout.Hierarchy.prototype.defaults).orient("top");

pv.Layout.Partition.prototype.$size = function() {
    return 1;
};

pv.Layout.Partition.prototype.size = function(f) {
    this.$size = f;
    return this;
};

pv.Layout.Partition.prototype.buildImplied = function(s) {
    if (!pv.Layout.Hierarchy.prototype.buildImplied.call(this, s)) {
        var that = this, root = s.nodes[0], stack = pv.Mark.stack, maxDepth = 0;
        stack.unshift(null);
        root.visitAfter(function(n, depth) {
            depth > maxDepth && (maxDepth = depth);
            n.size = n.firstChild ? pv.sum(n.childNodes, function(n) {
                return n.size;
            }) : that.$size.apply(that, (stack[0] = n, stack));
        });
        stack.shift();
        switch (s.order) {
          case "ascending":
            root.sort(function(a, b) {
                return a.size - b.size;
            });
            break;

          case "descending":
            root.sort(function(b, a) {
                return a.size - b.size;
            });
        }
        root.minBreadth = 0;
        root.breadth = .5;
        root.maxBreadth = 1;
        root.visitBefore(function(n) {
            for (var b = n.minBreadth, s = n.maxBreadth - b, c = n.firstChild; c; c = c.nextSibling) {
                c.minBreadth = b;
                b += c.size / n.size * s;
                c.maxBreadth = b;
                c.breadth = (b + c.minBreadth) / 2;
            }
        });
        root.visitAfter(function(n, depth) {
            n.minDepth = (depth - 1) / maxDepth;
            n.maxDepth = n.depth = depth / maxDepth;
        });
        pv.Layout.Hierarchy.NodeLink.buildImplied.call(this, s);
    }
};

pv.Layout.Partition.Fill = function() {
    pv.Layout.Partition.call(this);
    pv.Layout.Hierarchy.Fill.constructor.call(this);
};

pv.Layout.Partition.Fill.prototype = pv.extend(pv.Layout.Partition);

pv.Layout.Partition.Fill.prototype.buildImplied = function(s) {
    pv.Layout.Partition.prototype.buildImplied.call(this, s) || pv.Layout.Hierarchy.Fill.buildImplied.call(this, s);
};

pv.Layout.Arc = function() {
    pv.Layout.Network.call(this);
    var interpolate, directed, reverse, buildImplied = this.buildImplied;
    this.buildImplied = function(s) {
        buildImplied.call(this, s);
        directed = s.directed;
        interpolate = "radial" == s.orient ? "linear" : "polar";
        reverse = "right" == s.orient || "top" == s.orient;
    };
    this.link.data(function(p) {
        var s = p.sourceNode, t = p.targetNode;
        return reverse != (directed || s.breadth < t.breadth) ? [ s, t ] : [ t, s ];
    }).interpolate(function() {
        return interpolate;
    });
};

pv.Layout.Arc.prototype = pv.extend(pv.Layout.Network).property("orient", String).property("directed", Boolean);

pv.Layout.Arc.prototype.defaults = new pv.Layout.Arc().extend(pv.Layout.Network.prototype.defaults).orient("bottom");

pv.Layout.Arc.prototype.sort = function(f) {
    this.$sort = f;
    return this;
};

pv.Layout.Arc.prototype.buildImplied = function(s) {
    function midAngle(b) {
        switch (orient) {
          case "top":
            return -Math.PI / 2;

          case "bottom":
            return Math.PI / 2;

          case "left":
            return Math.PI;

          case "right":
            return 0;

          case "radial":
            return 2 * (b - .25) * Math.PI;
        }
    }
    function x(b) {
        switch (orient) {
          case "top":
          case "bottom":
            return b * w;

          case "left":
            return 0;

          case "right":
            return w;

          case "radial":
            return w / 2 + r * Math.cos(midAngle(b));
        }
    }
    function y(b) {
        switch (orient) {
          case "top":
            return 0;

          case "bottom":
            return h;

          case "left":
          case "right":
            return b * h;

          case "radial":
            return h / 2 + r * Math.sin(midAngle(b));
        }
    }
    if (!pv.Layout.Network.prototype.buildImplied.call(this, s)) {
        var nodes = s.nodes, orient = s.orient, sort = this.$sort, index = pv.range(nodes.length), w = s.width, h = s.height, r = Math.min(w, h) / 2;
        sort && index.sort(function(a, b) {
            return sort(nodes[a], nodes[b]);
        });
        for (var i = 0; i < nodes.length; i++) {
            var n = nodes[index[i]], b = n.breadth = (i + .5) / nodes.length;
            n.x = x(b);
            n.y = y(b);
            n.midAngle = midAngle(b);
        }
    }
};

pv.Layout.Horizon = function() {
    pv.Layout.call(this);
    var bands, mode, size, fill, red, blue, that = this, buildImplied = this.buildImplied;
    this.buildImplied = function(s) {
        buildImplied.call(this, s);
        bands = s.bands;
        mode = s.mode;
        size = Math.round(("color" == mode ? .5 : 1) * s.height);
        fill = s.backgroundStyle;
        red = pv.ramp(fill, s.negativeStyle).domain(0, bands);
        blue = pv.ramp(fill, s.positiveStyle).domain(0, bands);
    };
    var bands = new pv.Panel().data(function() {
        return pv.range(2 * bands);
    }).overflow("hidden").height(function() {
        return size;
    }).top(function(i) {
        return "color" == mode ? (1 & i) * size : 0;
    }).fillStyle(function(i) {
        return i ? null : fill;
    });
    this.band = new pv.Mark().top(function(d, i) {
        return "mirror" == mode && 1 & i ? (i + 1 >> 1) * size : null;
    }).bottom(function(d, i) {
        return "mirror" == mode ? 1 & i ? null : (i + 1 >> 1) * -size : (1 & i || -1) * (i + 1 >> 1) * size;
    }).fillStyle(function(d, i) {
        return (1 & i ? red : blue)((i >> 1) + 1);
    });
    this.band.add = function(type) {
        return that.add(pv.Panel).extend(bands).add(type).extend(this);
    };
};

pv.Layout.Horizon.prototype = pv.extend(pv.Layout).property("bands", Number).property("mode", String).property("backgroundStyle", pv.fillStyle).property("positiveStyle", pv.fillStyle).property("negativeStyle", pv.fillStyle);

pv.Layout.Horizon.prototype.defaults = new pv.Layout.Horizon().extend(pv.Layout.prototype.defaults).bands(2).mode("offset").backgroundStyle("white").positiveStyle("#1f77b4").negativeStyle("#d62728");

pv.Layout.Rollup = function() {
    pv.Layout.Network.call(this);
    var nodes, links, that = this, buildImplied = that.buildImplied;
    this.buildImplied = function(s) {
        buildImplied.call(this, s);
        nodes = s.$rollup.nodes;
        links = s.$rollup.links;
    };
    this.node.data(function() {
        return nodes;
    }).shapeSize(function(d) {
        return 20 * d.nodes.length;
    });
    this.link.interpolate("polar").eccentricity(.8);
    this.link.add = function(type) {
        return that.add(pv.Panel).data(function() {
            return links;
        }).add(type).extend(this);
    };
};

pv.Layout.Rollup.prototype = pv.extend(pv.Layout.Network).property("directed", Boolean);

pv.Layout.Rollup.prototype.x = function(f) {
    this.$x = pv.functor(f);
    return this;
};

pv.Layout.Rollup.prototype.y = function(f) {
    this.$y = pv.functor(f);
    return this;
};

pv.Layout.Rollup.prototype.buildImplied = function(s) {
    function id(i) {
        return x[i] + "," + y[i];
    }
    if (!pv.Layout.Network.prototype.buildImplied.call(this, s)) {
        var nodes = s.nodes, links = s.links, directed = s.directed, n = nodes.length, x = [], y = [], rnindex = 0, rnodes = {}, rlinks = {}, stack = pv.Mark.stack, o = {
            parent: this
        };
        stack.unshift(null);
        for (var i = 0; n > i; i++) {
            o.index = i;
            stack[0] = nodes[i];
            x[i] = this.$x.apply(o, stack);
            y[i] = this.$y.apply(o, stack);
        }
        stack.shift();
        for (var i = 0; i < nodes.length; i++) {
            var nodeId = id(i), rn = rnodes[nodeId];
            if (!rn) {
                rn = rnodes[nodeId] = Object.create(nodes[i]);
                rn.index = rnindex++;
                rn.x = x[i];
                rn.y = y[i];
                rn.nodes = [];
            }
            rn.nodes.push(nodes[i]);
        }
        for (var i = 0; i < links.length; i++) {
            var source = links[i].sourceNode, target = links[i].targetNode, rsource = rnodes[id(source.index)], rtarget = rnodes[id(target.index)], reverse = !directed && rsource.index > rtarget.index, linkId = reverse ? rtarget.index + "," + rsource.index : rsource.index + "," + rtarget.index, rl = rlinks[linkId];
            rl || (rl = rlinks[linkId] = {
                sourceNode: rsource,
                targetNode: rtarget,
                linkValue: 0,
                links: []
            });
            rl.links.push(links[i]);
            rl.linkValue += links[i].linkValue;
        }
        s.$rollup = {
            nodes: pv.values(rnodes),
            links: pv.values(rlinks)
        };
    }
};

pv.Layout.Matrix = function() {
    pv.Layout.Network.call(this);
    var n, dx, dy, labels, pairs, that = this, buildImplied = that.buildImplied;
    this.buildImplied = function(s) {
        buildImplied.call(this, s);
        n = s.nodes.length;
        dx = s.width / n;
        dy = s.height / n;
        labels = s.$matrix.labels;
        pairs = s.$matrix.pairs;
    };
    this.link.data(function() {
        return pairs;
    }).left(function() {
        return dx * (this.index % n);
    }).top(function() {
        return dy * Math.floor(this.index / n);
    }).width(function() {
        return dx;
    }).height(function() {
        return dy;
    }).lineWidth(1.5).strokeStyle("#fff").fillStyle(function(l) {
        return l.linkValue ? "#555" : "#eee";
    }).parent = this;
    delete this.link.add;
    this.label.data(function() {
        return labels;
    }).left(function() {
        return 1 & this.index ? dx * ((this.index >> 1) + .5) : 0;
    }).top(function() {
        return 1 & this.index ? 0 : dy * ((this.index >> 1) + .5);
    }).textMargin(4).textAlign(function() {
        return 1 & this.index ? "left" : "right";
    }).textAngle(function() {
        return 1 & this.index ? -Math.PI / 2 : 0;
    });
    delete this.node;
};

pv.Layout.Matrix.prototype = pv.extend(pv.Layout.Network).property("directed", Boolean);

pv.Layout.Matrix.prototype.sort = function(f) {
    this.$sort = f;
    return this;
};

pv.Layout.Matrix.prototype.buildImplied = function(s) {
    if (!pv.Layout.Network.prototype.buildImplied.call(this, s)) {
        var nodes = s.nodes, links = s.links, sort = this.$sort, n = nodes.length, index = pv.range(n), labels = [], pairs = [], map = {};
        s.$matrix = {
            labels: labels,
            pairs: pairs
        };
        sort && index.sort(function(a, b) {
            return sort(nodes[a], nodes[b]);
        });
        for (var i = 0; n > i; i++) for (var j = 0; n > j; j++) {
            var a = index[i], b = index[j], p = {
                row: i,
                col: j,
                sourceNode: nodes[a],
                targetNode: nodes[b],
                linkValue: 0
            };
            pairs.push(map[a + "." + b] = p);
        }
        for (var i = 0; n > i; i++) {
            var a = index[i];
            labels.push(nodes[a], nodes[a]);
        }
        for (var i = 0; i < links.length; i++) {
            var l = links[i], source = l.sourceNode.index, target = l.targetNode.index, value = l.linkValue;
            map[source + "." + target].linkValue += value;
            s.directed || (map[target + "." + source].linkValue += value);
        }
    }
};

pv.Layout.Bullet = function() {
    pv.Layout.call(this);
    var orient, horizontal, rangeColor, measureColor, x, that = this, buildImplied = that.buildImplied, scale = that.x = pv.Scale.linear();
    this.buildImplied = function(s) {
        buildImplied.call(this, x = s);
        orient = s.orient;
        horizontal = /^left|right$/.test(orient);
        rangeColor = pv.ramp("#bbb", "#eee").domain(0, Math.max(1, x.ranges.length - 1));
        measureColor = pv.ramp("steelblue", "lightsteelblue").domain(0, Math.max(1, x.measures.length - 1));
    };
    (this.range = new pv.Mark()).data(function() {
        return x.ranges;
    }).reverse(!0).left(function() {
        return "left" == orient ? 0 : null;
    }).top(function() {
        return "top" == orient ? 0 : null;
    }).right(function() {
        return "right" == orient ? 0 : null;
    }).bottom(function() {
        return "bottom" == orient ? 0 : null;
    }).width(function(d) {
        return horizontal ? scale(d) : null;
    }).height(function(d) {
        return horizontal ? null : scale(d);
    }).fillStyle(function() {
        return rangeColor(this.index);
    }).antialias(!1).parent = that;
    (this.measure = new pv.Mark()).extend(this.range).data(function() {
        return x.measures;
    }).left(function() {
        return "left" == orient ? 0 : horizontal ? null : this.parent.width() / 3.25;
    }).top(function() {
        return "top" == orient ? 0 : horizontal ? this.parent.height() / 3.25 : null;
    }).right(function() {
        return "right" == orient ? 0 : horizontal ? null : this.parent.width() / 3.25;
    }).bottom(function() {
        return "bottom" == orient ? 0 : horizontal ? this.parent.height() / 3.25 : null;
    }).fillStyle(function() {
        return measureColor(this.index);
    }).parent = that;
    (this.marker = new pv.Mark()).data(function() {
        return x.markers;
    }).left(function(d) {
        return "left" == orient ? scale(d) : horizontal ? null : this.parent.width() / 2;
    }).top(function(d) {
        return "top" == orient ? scale(d) : horizontal ? this.parent.height() / 2 : null;
    }).right(function(d) {
        return "right" == orient ? scale(d) : null;
    }).bottom(function(d) {
        return "bottom" == orient ? scale(d) : null;
    }).strokeStyle("black").shape("bar").shapeAngle(function() {
        return horizontal ? 0 : Math.PI / 2;
    }).parent = that;
    (this.tick = new pv.Mark()).data(function() {
        return scale.ticks(7);
    }).left(function(d) {
        return "left" == orient ? scale(d) : null;
    }).top(function(d) {
        return "top" == orient ? scale(d) : null;
    }).right(function(d) {
        return "right" == orient ? scale(d) : horizontal ? null : -6;
    }).bottom(function(d) {
        return "bottom" == orient ? scale(d) : horizontal ? -8 : null;
    }).height(function() {
        return horizontal ? 6 : null;
    }).width(function() {
        return horizontal ? null : 6;
    }).parent = that;
};

pv.Layout.Bullet.prototype = pv.extend(pv.Layout).property("orient", String).property("ranges").property("markers").property("measures").property("minimum").property("maximum");

pv.Layout.Bullet.prototype.defaults = new pv.Layout.Bullet().extend(pv.Layout.prototype.defaults).orient("left").ranges([]).markers([]).measures([]);

pv.Layout.Bullet.prototype._originIsZero = !0;

pv.Layout.Bullet.prototype.originIsZero = function(value) {
    return arguments.length ? this._originIsZero = !!value : this._originIsZero;
};

pv.Layout.Bullet.prototype.buildImplied = function(s) {
    pv.Layout.prototype.buildImplied.call(this, s);
    var allValues, size = this.parent[/^left|right$/.test(s.orient) ? "width" : "height"](), max = s.maximum, min = s.minimum, delta = 1e-10;
    if (null == max) {
        allValues = [].concat(s.ranges, s.markers, s.measures);
        max = pv.max(allValues);
    } else max = +max;
    if (null == min) {
        allValues || (allValues = [].concat(s.ranges, s.markers, s.measures));
        min = pv.min(allValues);
        min = .95 * min;
    } else min = +min;
    (min > max || delta > max - min) && (min = Math.abs(max) < delta ? -.1 : .99 * max);
    this._originIsZero && min * max > 0 && (min > 0 ? min = 0 : max = 0);
    s.minimum = min;
    s.maximum = max;
    this.x.domain(min, max).range(0, size);
};

pv.Behavior = {};

pv.Behavior.dragBase = function(shared) {
    function mousedown(d) {
        if (!inited) {
            inited = !0;
            this.addEventInterceptor("click", eventInterceptor, !0);
        }
        if (!events) {
            var root = this.root.scene.$g;
            events = [ [ root, "mousemove", pv.listen(root, "mousemove", mousemove) ], [ root, "mouseup", pv.listen(root, "mouseup", mouseup) ], [ document, "mousemove", pv.listen(document, "mousemove", mousemove) ], [ document, "mouseup", pv.listen(document, "mouseup", mouseup) ] ];
        }
        var ev = arguments[arguments.length - 1];
        downElem = ev.target;
        cancelClick = !1;
        ev.stopPropagation();
        var m1 = this.mouse(), scene = this.scene, index = this.index;
        drag = scene[index].drag = {
            phase: "start",
            m: m1,
            m1: m1,
            m2: null,
            d: d,
            scene: scene,
            index: index
        };
        ev = wrapEvent(ev, drag);
        shared.dragstart.call(this, ev);
        var m = drag.m;
        if (m !== m1) {
            m1.x = m.x;
            m1.y = m.y;
        }
    }
    function mousemove(ev) {
        if (drag) {
            drag.phase = "move";
            ev.stopPropagation();
            ev = wrapEvent(ev, drag);
            var scene = drag.scene;
            scene.mark.context(scene, drag.index, function() {
                var mprev = drag.m2 || drag.m1, m2 = this.mouse();
                if (!(mprev && m2.distance2(mprev).dist2 <= 2)) {
                    drag.m = drag.m2 = m2;
                    shared.drag.call(this, ev);
                    var m = drag.m;
                    if (m !== m2) {
                        m2.x = m.x;
                        m2.y = m.y;
                    }
                }
            });
        }
    }
    function mouseup(ev) {
        if (drag) {
            drag.phase = "end";
            var m2 = drag.m2, isDrag = m2 && drag.m1.distance2(m2).dist2 > .1;
            drag.canceled = !isDrag;
            cancelClick = isDrag && downElem === ev.target;
            cancelClick || (downElem = null);
            ev.stopPropagation();
            ev = wrapEvent(ev, drag);
            if (events) {
                events.forEach(function(registration) {
                    pv.unlisten.apply(pv, registration);
                });
                events = null;
            }
            var scene = drag.scene, index = drag.index;
            try {
                scene.mark.context(scene, index, function() {
                    shared.dragend.call(this, ev);
                });
            } finally {
                drag = null;
                delete scene[index].drag;
            }
        }
    }
    function wrapEvent(ev, drag) {
        try {
            ev.drag = drag;
            return ev;
        } catch (ex) {}
        var ev2 = {};
        for (var p in ev) {
            var v = ev[p];
            ev2[p] = "function" != typeof v ? v : bindEventFun(v, ev);
        }
        ev2._sourceEvent = ev;
        return ev2;
    }
    function bindEventFun(f, ctx) {
        return function() {
            return f.apply(ctx, arguments);
        };
    }
    function eventInterceptor(type, ev) {
        if (cancelClick && downElem === ev.target) {
            cancelClick = !1;
            downElem = null;
            return !1;
        }
    }
    var events, downElem, cancelClick, inited, drag;
    shared.autoRender = !0;
    shared.positionConstraint = null;
    shared.bound = function(v, a_p) {
        return Math.max(drag.min[a_p], Math.min(drag.max[a_p], v));
    };
    mousedown.autoRender = function(_) {
        if (arguments.length) {
            shared.autoRender = !!_;
            return mousedown;
        }
        return shared.autoRender;
    };
    mousedown.positionConstraint = function(_) {
        if (arguments.length) {
            shared.positionConstraint = _;
            return mousedown;
        }
        return shared.positionConstraint;
    };
    return mousedown;
};

pv.Behavior.drag = function() {
    var v1, collapse = null, kx = 1, ky = 1, shared = {
        dragstart: function(ev) {
            var drag = ev.drag;
            drag.type = "drag";
            var p = drag.d, fix = pv.vector(p.x, p.y);
            p.fix = fix;
            p.drag = drag;
            v1 = fix.minus(drag.m1);
            var parent = this.parent;
            drag.max = {
                x: parent.width() - (p.dx || 0),
                y: parent.height() - (p.dy || 0)
            };
            drag.min = {
                x: 0,
                y: 0
            };
            shared.autoRender && this.render();
            pv.Mark.dispatch("dragstart", drag.scene, drag.index, ev);
        },
        drag: function(ev) {
            var drag = ev.drag, m2 = drag.m2, p = drag.d;
            drag.m = v1.plus(m2);
            var constraint = shared.positionConstraint;
            constraint && constraint(drag);
            var m = drag.m;
            kx && (p.x = p.fix.x = shared.bound(m.x, "x"));
            ky && (p.y = p.fix.y = shared.bound(m.y, "y"));
            shared.autoRender && this.render();
            pv.Mark.dispatch("drag", drag.scene, drag.index, ev);
        },
        dragend: function(ev) {
            var drag = ev.drag, p = drag.d;
            p.fix = null;
            v1 = null;
            shared.autoRender && this.render();
            try {
                pv.Mark.dispatch("dragend", drag.scene, drag.index, ev);
            } finally {
                delete p.drag;
            }
        }
    }, mousedown = pv.Behavior.dragBase(shared);
    mousedown.collapse = function(x) {
        if (arguments.length) {
            collapse = String(x);
            switch (collapse) {
              case "y":
                kx = 1;
                ky = 0;
                break;

              case "x":
                kx = 0;
                ky = 1;
                break;

              default:
                kx = 1;
                ky = 1;
            }
            return mousedown;
        }
        return collapse;
    };
    return mousedown;
};

pv.Behavior.point = function(r) {
    function search(scene, index) {
        for (var s = scene[index], point = {
            cost: 1/0
        }, i = (s.visible ? s.children.length : 0) - 1; i >= 0; i--) {
            var p, child = s.children[i], mark = child.mark;
            if ("panel" == mark.type) {
                mark.scene = child;
                for (var j = child.length - 1; j >= 0; j--) {
                    mark.index = j;
                    p = search(child, j);
                    p.cost < point.cost && (point = p);
                }
                delete mark.scene;
                delete mark.index;
            } else if (mark.$handlers.point) for (var v = mark.mouse(), j = child.length - 1; j >= 0; j--) {
                var c = child[j], dx = v.x - c.left - (c.width || 0) / 2, dy = v.y - c.top - (c.height || 0) / 2, dd = kx * dx * dx + ky * dy * dy;
                if (dd < point.cost) {
                    point.distance = dx * dx + dy * dy;
                    point.cost = dd;
                    point.scene = child;
                    point.index = j;
                }
            }
        }
        return point;
    }
    function mousemove(e) {
        var point = search(this.scene, this.index);
        (1/0 == point.cost || point.distance > r2) && (point = null);
        if (unpoint) {
            if (point && unpoint.scene == point.scene && unpoint.index == point.index) return;
            pv.Mark.dispatch("unpoint", unpoint.scene, unpoint.index, e);
        }
        if (unpoint = point) {
            pv.Mark.dispatch("point", point.scene, point.index, e);
            if (pointingPanel || "panel" !== this.type) pv.listen(this.root.canvas(), "mouseout", mouseout); else {
                pointingPanel = this;
                pointingPanel.event("mouseout", function() {
                    var ev = arguments[arguments.length - 1];
                    mouseout.call(pointingPanel.scene.$g, ev);
                });
            }
        }
    }
    function mouseout(e) {
        if (unpoint && !pv.ancestor(this, e.relatedTarget)) {
            pv.Mark.dispatch("unpoint", unpoint.scene, unpoint.index, e);
            unpoint = null;
        }
    }
    var unpoint, collapse = null, kx = 1, ky = 1, pointingPanel = null, r2 = arguments.length ? r * r : 900;
    mousemove.collapse = function(x) {
        if (arguments.length) {
            collapse = String(x);
            switch (collapse) {
              case "y":
                kx = 1;
                ky = 0;
                break;

              case "x":
                kx = 0;
                ky = 1;
                break;

              default:
                kx = 1;
                ky = 1;
            }
            return mousemove;
        }
        return collapse;
    };
    return mousemove;
};

pv.Behavior.select = function() {
    var collapse = null, kx = 1, ky = 1, preserveLength = !1, shared = {
        dragstart: function(ev) {
            var drag = ev.drag;
            drag.type = "select";
            var r = drag.d;
            r.drag = drag;
            drag.max = {
                x: this.width(),
                y: this.height()
            };
            drag.min = {
                x: 0,
                y: 0
            };
            var constraint = shared.positionConstraint;
            if (constraint) {
                drag.m = drag.m.clone();
                constraint(drag);
            }
            var m = drag.m;
            if (kx) {
                r.x = shared.bound(m.x, "x");
                preserveLength || (r.dx = 0);
            }
            if (ky) {
                r.y = shared.bound(m.y, "y");
                preserveLength || (r.dy = 0);
            }
            pv.Mark.dispatch("selectstart", drag.scene, drag.index, ev);
        },
        drag: function(ev) {
            var drag = ev.drag, m1 = drag.m1, r = drag.d;
            drag.max.x = this.width();
            drag.max.y = this.height();
            var constraint = shared.positionConstraint;
            if (constraint) {
                drag.m = drag.m.clone();
                constraint(drag);
            }
            var m = drag.m;
            if (kx) {
                var bx = Math.min(m1.x, m.x);
                bx = shared.bound(bx, "x");
                r.x = bx;
                if (!preserveLength) {
                    var ex = Math.max(m.x, m1.x);
                    ex = shared.bound(ex, "x");
                    r.dx = ex - bx;
                }
            }
            if (ky) {
                var by = Math.min(m1.y, m.y);
                by = shared.bound(by, "y");
                r.y = by;
                if (!preserveLength) {
                    var ey = Math.max(m.y, m1.y);
                    ey = shared.bound(ey, "y");
                    r.dy = ey - by;
                }
            }
            shared.autoRender && this.render();
            pv.Mark.dispatch("select", drag.scene, drag.index, ev);
        },
        dragend: function(ev) {
            var drag = ev.drag;
            try {
                pv.Mark.dispatch("selectend", drag.scene, drag.index, ev);
            } finally {
                var r = drag.d;
                delete r.drag;
            }
        }
    }, mousedown = pv.Behavior.dragBase(shared);
    mousedown.collapse = function(x) {
        if (arguments.length) {
            collapse = String(x);
            switch (collapse) {
              case "y":
                kx = 1;
                ky = 0;
                break;

              case "x":
                kx = 0;
                ky = 1;
                break;

              default:
                kx = 1;
                ky = 1;
            }
            return mousedown;
        }
        return collapse;
    };
    mousedown.preserveLength = function(_) {
        if (arguments.length) {
            preserveLength = !!_;
            return mousedown;
        }
        return preserveLength;
    };
    return mousedown;
};

pv.Behavior.resize = function(side) {
    var preserveOrtho = !1, isLeftRight = "left" === side || "right" === side, shared = {
        dragstart: function(ev) {
            var drag = ev.drag;
            drag.type = "resize";
            var m1 = drag.m1, r = drag.d;
            r.drag = drag;
            switch (side) {
              case "left":
                m1.x = r.x + r.dx;
                break;

              case "right":
                m1.x = r.x;
                break;

              case "top":
                m1.y = r.y + r.dy;
                break;

              case "bottom":
                m1.y = r.y;
            }
            var parent = this.parent;
            drag.max = {
                x: parent.width(),
                y: parent.height()
            };
            drag.min = {
                x: 0,
                y: 0
            };
            pv.Mark.dispatch("resizestart", drag.scene, drag.index, ev);
        },
        drag: function(ev) {
            var drag = ev.drag, m1 = drag.m1, constraint = shared.positionConstraint;
            if (constraint) {
                drag.m = drag.m.clone();
                constraint(drag);
            }
            var m = drag.m, r = drag.d;
            if (!preserveOrtho || isLeftRight) {
                var bx = Math.min(m1.x, m.x), ex = Math.max(m.x, m1.x);
                bx = shared.bound(bx, "x");
                ex = shared.bound(ex, "x");
                r.x = bx;
                r.dx = ex - bx;
            }
            if (!preserveOrtho || !isLeftRight) {
                var by = Math.min(m1.y, m.y), ey = Math.max(m.y, m1.y);
                bx = shared.bound(by, "y");
                ex = shared.bound(ey, "y");
                r.y = by;
                r.dy = ey - by;
            }
            shared.autoRender && this.render();
            pv.Mark.dispatch("resize", drag.scene, drag.index, ev);
        },
        dragend: function(ev) {
            var drag = ev.drag;
            max = null;
            try {
                pv.Mark.dispatch("resizeend", drag.scene, drag.index, ev);
            } finally {
                var r = drag.d;
                delete r.drag;
            }
        }
    }, mousedown = pv.Behavior.dragBase(shared);
    mousedown.preserveOrtho = function(_) {
        if (arguments.length) {
            preserveOrtho = !!_;
            return mousedown;
        }
        return preserveOrtho;
    };
    return mousedown;
};

pv.Behavior.pan = function() {
    function mousedown() {
        index = this.index;
        scene = this.scene;
        v1 = pv.vector(pv.event.pageX, pv.event.pageY);
        m1 = this.transform();
        k = 1 / (m1.k * this.scale);
        bound && (bound = {
            x: (1 - m1.k) * this.width(),
            y: (1 - m1.k) * this.height()
        });
    }
    function mousemove(e) {
        if (scene) {
            scene.mark.context(scene, index, function() {
                var x = (pv.event.pageX - v1.x) * k, y = (pv.event.pageY - v1.y) * k, m = m1.translate(x, y);
                if (bound) {
                    m.x = Math.max(bound.x, Math.min(0, m.x));
                    m.y = Math.max(bound.y, Math.min(0, m.y));
                }
                this.transform(m).render();
            });
            pv.Mark.dispatch("pan", scene, index, e);
        }
    }
    function mouseup() {
        scene = null;
    }
    var scene, index, m1, v1, k, bound;
    mousedown.bound = function(x) {
        if (arguments.length) {
            bound = Boolean(x);
            return this;
        }
        return Boolean(bound);
    };
    pv.listen(window, "mousemove", mousemove);
    pv.listen(window, "mouseup", mouseup);
    return mousedown;
};

pv.Behavior.zoom = function(speed) {
    function mousewheel(e) {
        var v = this.mouse(), k = pv.event.wheel * speed, m = this.transform().translate(v.x, v.y).scale(0 > k ? 1e3 / (1e3 - k) : (1e3 + k) / 1e3).translate(-v.x, -v.y);
        if (bound) {
            m.k = Math.max(1, m.k);
            m.x = Math.max((1 - m.k) * this.width(), Math.min(0, m.x));
            m.y = Math.max((1 - m.k) * this.height(), Math.min(0, m.y));
        }
        this.transform(m).render();
        pv.Mark.dispatch("zoom", this.scene, this.index, e);
    }
    var bound;
    arguments.length || (speed = 1 / 48);
    mousewheel.bound = function(x) {
        if (arguments.length) {
            bound = Boolean(x);
            return this;
        }
        return Boolean(bound);
    };
    return mousewheel;
};

pv.Geo = function() {};

pv.Geo.projections = {
    mercator: {
        project: function(latlng) {
            return {
                x: latlng.lng / 180,
                y: latlng.lat > 85 ? 1 : latlng.lat < -85 ? -1 : Math.log(Math.tan(Math.PI / 4 + pv.radians(latlng.lat) / 2)) / Math.PI
            };
        },
        invert: function(xy) {
            return {
                lng: 180 * xy.x,
                lat: pv.degrees(2 * Math.atan(Math.exp(xy.y * Math.PI)) - Math.PI / 2)
            };
        }
    },
    "gall-peters": {
        project: function(latlng) {
            return {
                x: latlng.lng / 180,
                y: Math.sin(pv.radians(latlng.lat))
            };
        },
        invert: function(xy) {
            return {
                lng: 180 * xy.x,
                lat: pv.degrees(Math.asin(xy.y))
            };
        }
    },
    sinusoidal: {
        project: function(latlng) {
            return {
                x: pv.radians(latlng.lng) * Math.cos(pv.radians(latlng.lat)) / Math.PI,
                y: latlng.lat / 90
            };
        },
        invert: function(xy) {
            return {
                lng: pv.degrees(xy.x * Math.PI / Math.cos(xy.y * Math.PI / 2)),
                lat: 90 * xy.y
            };
        }
    },
    aitoff: {
        project: function(latlng) {
            var l = pv.radians(latlng.lng), f = pv.radians(latlng.lat), a = Math.acos(Math.cos(f) * Math.cos(l / 2));
            return {
                x: 2 * (a ? Math.cos(f) * Math.sin(l / 2) * a / Math.sin(a) : 0) / Math.PI,
                y: 2 * (a ? Math.sin(f) * a / Math.sin(a) : 0) / Math.PI
            };
        },
        invert: function(xy) {
            var x = xy.x * Math.PI / 2, y = xy.y * Math.PI / 2;
            return {
                lng: pv.degrees(x / Math.cos(y)),
                lat: pv.degrees(y)
            };
        }
    },
    hammer: {
        project: function(latlng) {
            var l = pv.radians(latlng.lng), f = pv.radians(latlng.lat), c = Math.sqrt(1 + Math.cos(f) * Math.cos(l / 2));
            return {
                x: 2 * Math.SQRT2 * Math.cos(f) * Math.sin(l / 2) / c / 3,
                y: Math.SQRT2 * Math.sin(f) / c / 1.5
            };
        },
        invert: function(xy) {
            var x = 3 * xy.x, y = 1.5 * xy.y, z = Math.sqrt(1 - x * x / 16 - y * y / 4);
            return {
                lng: pv.degrees(2 * Math.atan2(z * x, 2 * (2 * z * z - 1))),
                lat: pv.degrees(Math.asin(z * y))
            };
        }
    },
    identity: {
        project: function(latlng) {
            return {
                x: latlng.lng / 180,
                y: latlng.lat / 90
            };
        },
        invert: function(xy) {
            return {
                lng: 180 * xy.x,
                lat: 90 * xy.y
            };
        }
    }
};

pv.Geo.scale = function(p) {
    function scale(latlng) {
        if (!lastLatLng || latlng.lng != lastLatLng.lng || latlng.lat != lastLatLng.lat) {
            lastLatLng = latlng;
            var p = project(latlng);
            lastPoint = {
                x: x(p.x),
                y: y(p.y)
            };
        }
        return lastPoint;
    }
    function project(latlng) {
        var offset = {
            lng: latlng.lng - c.lng,
            lat: latlng.lat
        };
        return j.project(offset);
    }
    function invert(xy) {
        var latlng = j.invert(xy);
        latlng.lng += c.lng;
        return latlng;
    }
    var lastLatLng, lastPoint, rmin = {
        x: 0,
        y: 0
    }, rmax = {
        x: 1,
        y: 1
    }, d = [], j = pv.Geo.projections.identity, x = pv.Scale.linear(-1, 1).range(0, 1), y = pv.Scale.linear(-1, 1).range(1, 0), c = {
        lng: 0,
        lat: 0
    };
    scale.x = function(latlng) {
        return scale(latlng).x;
    };
    scale.y = function(latlng) {
        return scale(latlng).y;
    };
    scale.ticks = {
        lng: function(m) {
            var lat, lng;
            if (d.length > 1) {
                var s = pv.Scale.linear();
                void 0 == m && (m = 10);
                lat = s.domain(d, function(d) {
                    return d.lat;
                }).ticks(m);
                lng = s.domain(d, function(d) {
                    return d.lng;
                }).ticks(m);
            } else {
                lat = pv.range(-80, 81, 10);
                lng = pv.range(-180, 181, 10);
            }
            return lng.map(function(lng) {
                return lat.map(function(lat) {
                    return {
                        lat: lat,
                        lng: lng
                    };
                });
            });
        },
        lat: function(m) {
            return pv.transpose(scale.ticks.lng(m));
        }
    };
    scale.invert = function(p) {
        return invert({
            x: x.invert(p.x),
            y: y.invert(p.y)
        });
    };
    scale.domain = function(array, f) {
        if (arguments.length) {
            d = array instanceof Array ? arguments.length > 1 ? pv.map(array, f) : array : Array.prototype.slice.call(arguments);
            if (d.length > 1) {
                var lngs = d.map(function(c) {
                    return c.lng;
                }), lats = d.map(function(c) {
                    return c.lat;
                });
                c = {
                    lng: (pv.max(lngs) + pv.min(lngs)) / 2,
                    lat: (pv.max(lats) + pv.min(lats)) / 2
                };
                var n = d.map(project);
                x.domain(n, function(p) {
                    return p.x;
                });
                y.domain(n, function(p) {
                    return p.y;
                });
            } else {
                c = {
                    lng: 0,
                    lat: 0
                };
                x.domain(-1, 1);
                y.domain(-1, 1);
            }
            lastLatLng = null;
            return this;
        }
        return d;
    };
    scale.range = function(min, max) {
        if (arguments.length) {
            if ("object" == typeof min) {
                rmin = {
                    x: Number(min.x),
                    y: Number(min.y)
                };
                rmax = {
                    x: Number(max.x),
                    y: Number(max.y)
                };
            } else {
                rmin = {
                    x: 0,
                    y: 0
                };
                rmax = {
                    x: Number(min),
                    y: Number(max)
                };
            }
            x.range(rmin.x, rmax.x);
            y.range(rmax.y, rmin.y);
            lastLatLng = null;
            return this;
        }
        return [ rmin, rmax ];
    };
    scale.projection = function(p) {
        if (arguments.length) {
            j = "string" == typeof p ? pv.Geo.projections[p] || pv.Geo.projections.identity : p;
            return this.domain(d);
        }
        return p;
    };
    pv.copyOwn(scale, pv.Scale.common);
    arguments.length && scale.projection(p);
    return scale;
};