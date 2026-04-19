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
 * Protovis MSIE/VML addon
 * Copyright (C) 2011 by DataMarket <http://datamarket.com>
 * Dual licensed under the terms of the MIT or GPL Version 2 software licenses.
 * 
 * This software includes code from jQuery, http://jquery.com/
 * jQuery is licensed under the MIT or GPL Version 2 license.
 * 
 * This software includes code from the Protovis, http://mbostock.github.com/protovis/
 * Protovis is licensed under the BSD license.
 * 
 */

pv.have_SVG = !(!document.createElementNS || !document.createElementNS("http://www.w3.org/2000/svg", "svg").createSVGRect);

pv.have_VML = function(d, a, b) {
    a = d.createElement("div");
    a.innerHTML = '<pvml:shape adj="1" />';
    b = a.firstChild;
    b.style.behavior = "url(#default#VML)";
    return b ? "object" == typeof b.adj : !0;
}(document);

!pv.have_SVG && pv.have_VML && !function() {
    "function" != typeof Date.now && (Date.now = function() {
        return 1 * new Date();
    });
    var vml = {
        is64Bit: "x64" === window.navigator.cpuClass,
        round: function(n) {
            return Math.round(21.6 * (n || 0));
        },
        styles: null,
        pre: "<pvml:",
        post: ' class="msvml">',
        block: {
            group: 1,
            shape: 1,
            shapetype: 1,
            line: 1,
            polyline: 1,
            curve: 1,
            rect: 1,
            roundrect: 1,
            oval: 1,
            arc: 1,
            image: 1
        },
        caps: {
            butt: "flat",
            round: "round",
            square: "square"
        },
        joins: {
            bevel: "bevel",
            round: "round",
            miter: "miter"
        },
        cursorstyles: {
            hand: "pointer",
            crosshair: 1,
            pointer: 1,
            move: 1,
            text: 1,
            wait: 1,
            help: 1,
            progress: 1,
            "n-resize": 1,
            "ne-resize": 1,
            "nw-resize": 1,
            "s-resize": 1,
            "se-resize": 1,
            "sw-resize": 1,
            "e-resize": 1,
            "w-resize": 1
        },
        text_shim: null,
        text_dims: function(text, font) {
            var shim = vml.text_shim || (vml.init(), vml.text_shim);
            shim.style.font = vml.processFont(font);
            shim.innerText = text;
            return {
                height: shim.offsetHeight,
                width: shim.offsetWidth
            };
        },
        _fontCache: {},
        _fontSubst: {
            "default": "Arial",
            "sans-serif": "Arial",
            sansserif: "Arial",
            sans: "Arial",
            serif: "Times New Roman",
            dialog: "Arial",
            monospaced: "Courier New",
            dialoginput: "Courier New"
        },
        _fontWhiteListIE64Bit: {
            "agency fb": 1,
            aharoni: 1,
            algerian: 1,
            andalus: 1,
            "angsana new": 1,
            angsanaupc: 1,
            aparajita: 1,
            "arabic typesetting": 1,
            arial: 1,
            "arial black": 1,
            "arial narrow": 1,
            "arial rounded mt bold": 1,
            "arial unicode ms": 1,
            "baskerville old face": 1,
            batang: 1,
            batangche: 1,
            "bauhaus 93": 1,
            "bell mt": 1,
            "berlin sans fb": 1,
            "berlin sans fb demi": 1,
            "bernard mt condensed": 1,
            "blackadder itc": 1,
            "bodoni mt": 1,
            "bodoni mt black": 1,
            "bodoni mt condensed": 1,
            "bodoni mt poster compressed": 1,
            "book antiqua": 1,
            "bookman old style": 1,
            "bookshelf symbol 7": 1,
            "bradley hand itc": 1,
            "britannic bold": 1,
            broadway: 1,
            "browallia new": 1,
            browalliaupc: 1,
            "brush script mt": 1,
            calibri: 1,
            "californian fb": 1,
            "calisto mt": 1,
            cambria: 1,
            "cambria math": 1,
            candara: 1,
            castellar: 1,
            centaur: 1,
            century: 1,
            "century gothic": 1,
            "century schoolbook": 1,
            chiller: 1,
            "colonna mt": 1,
            "comic sans ms": 1,
            consolas: 1,
            constantia: 1,
            "cooper black": 1,
            "copperplate gothic bold": 1,
            "copperplate gothic light": 1,
            corbel: 1,
            "cordia new": 1,
            cordiaupc: 1,
            "courier new": 1,
            "curlz mt": 1,
            daunpenh: 1,
            david: 1,
            "dfkai-sb": 1,
            dilleniaupc: 1,
            dokchampa: 1,
            dotum: 1,
            dotumche: 1,
            ebrima: 1,
            "edwardian script itc": 1,
            elephant: 1,
            "engravers mt": 1,
            "eras bold itc": 1,
            "eras demi itc": 1,
            "eras light itc": 1,
            "eras medium itc": 1,
            "estrangelo edessa": 1,
            eucrosiaupc: 1,
            euphemia: 1,
            fangsong: 1,
            "felix titling": 1,
            "footlight mt light": 1,
            forte: 1,
            "franklin gothic book": 1,
            "franklin gothic demi": 1,
            "franklin gothic demi cond": 1,
            "franklin gothic heavy": 1,
            "franklin gothic medium": 1,
            "franklin gothic medium cond": 1,
            frankruehl: 1,
            freesiaupc: 1,
            "freestyle script": 1,
            "french script mt": 1,
            gabriola: 1,
            garamond: 1,
            gautami: 1,
            georgia: 1,
            gigi: 1,
            "gill sans mt": 1,
            "gill sans mt condensed": 1,
            "gill sans mt ext condensed bold": 1,
            "gill sans ultra bold": 1,
            "gill sans ultra bold condensed": 1,
            gisha: 1,
            "gloucester mt extra condensed": 1,
            "goudy old style": 1,
            "goudy stout": 1,
            gulim: 1,
            gulimche: 1,
            gungsuh: 1,
            gungsuhche: 1,
            "guttman adii": 1,
            "guttman adii-light": 1,
            "guttman aharoni": 1,
            "guttman calligraphic": 1,
            "guttman david": 1,
            "guttman drogolin": 1,
            "guttman frank": 1,
            "guttman frnew": 1,
            "guttman haim": 1,
            "guttman haim-condensed": 1,
            "guttman hatzvi": 1,
            "guttman hodes": 1,
            "guttman kav": 1,
            "guttman kav-light": 1,
            "guttman keren": 1,
            "guttman logo1": 1,
            "guttman mantova": 1,
            "guttman mantova-decor": 1,
            "guttman miryam": 1,
            "guttman myamfix": 1,
            "guttman rashi": 1,
            "guttman stam": 1,
            "guttman stam1": 1,
            "guttman vilna": 1,
            "guttman yad": 1,
            "guttman yad-brush": 1,
            "guttman yad-light": 1,
            "guttman-aharoni": 1,
            "guttman-aram": 1,
            "guttman-courmir": 1,
            "guttman-soncino": 1,
            "guttman-toledo": 1,
            haettenschweiler: 1,
            "harlow solid italic": 1,
            harrington: 1,
            "high tower text": 1,
            impact: 1,
            "imprint mt shadow": 1,
            "informal roman": 1,
            irisupc: 1,
            "iskoola pota": 1,
            jasmineupc: 1,
            jokerman: 1,
            "juice itc": 1,
            kaiti: 1,
            kalinga: 1,
            kartika: 1,
            "khmer ui": 1,
            kodchiangupc: 1,
            kokila: 1,
            "kristen itc": 1,
            "kunstler script": 1,
            "lao ui": 1,
            latha: 1,
            leelawadee: 1,
            "levenim mt": 1,
            lilyupc: 1,
            "lucida bright": 1,
            "lucida calligraphy": 1,
            "lucida console": 1,
            "lucida fax": 1,
            "lucida handwriting": 1,
            "lucida sans": 1,
            "lucida sans typewriter": 1,
            "lucida sans unicode": 1,
            magneto: 1,
            "maiandra gd": 1,
            "malgun gothic": 1,
            mangal: 1,
            marlett: 1,
            "matura mt script capitals": 1,
            meiryo: 1,
            "meiryo ui": 1,
            "microsoft himalaya": 1,
            "microsoft jhenghei": 1,
            "microsoft new tai lue": 1,
            "microsoft phagspa": 1,
            "microsoft sans serif": 1,
            "microsoft tai le": 1,
            "microsoft uighur": 1,
            "microsoft yahei": 1,
            "microsoft yi baiti": 1,
            mingliu: 1,
            "mingliu-extb": 1,
            mingliu_hkscs: 1,
            "mingliu_hkscs-extb": 1,
            miriam: 1,
            "miriam fixed": 1,
            mistral: 1,
            "modern no. 20": 1,
            "mongolian baiti": 1,
            "monotype corsiva": 1,
            "monotype hadassah": 1,
            moolboran: 1,
            "ms gothic": 1,
            "ms mincho": 1,
            "ms outlook": 1,
            "ms pgothic": 1,
            "ms pmincho": 1,
            "ms reference sans serif": 1,
            "ms reference specialty": 1,
            "ms ui gothic": 1,
            "mt extra": 1,
            "mv boli": 1,
            narkisim: 1,
            "niagara engraved": 1,
            "niagara solid": 1,
            nsimsun: 1,
            nyala: 1,
            "ocr a extended": 1,
            "old english text mt": 1,
            onyx: 1,
            "palace script mt": 1,
            "palatino linotype": 1,
            papyrus: 1,
            parchment: 1,
            perpetua: 1,
            "perpetua titling mt": 1,
            "plantagenet cherokee": 1,
            playbill: 1,
            pmingliu: 1,
            "pmingliu-extb": 1,
            "poor richard": 1,
            pristina: 1,
            raavi: 1,
            "rage italic": 1,
            ravie: 1,
            rockwell: 1,
            "rockwell condensed": 1,
            "rockwell extra bold": 1,
            rod: 1,
            "sakkal majalla": 1,
            "script mt bold": 1,
            "segoe print": 1,
            "segoe script": 1,
            "segoe ui": 1,
            "segoe ui light": 1,
            "segoe ui semibold": 1,
            "segoe ui symbol": 1,
            "shonar bangla": 1,
            "showcard gothic": 1,
            shruti: 1,
            simhei: 1,
            "simplified arabic": 1,
            "simplified arabic fixed": 1,
            simsun: 1,
            "simsun-extb": 1,
            "snap itc": 1,
            stencil: 1,
            sylfaen: 1,
            symbol: 1,
            tahoma: 1,
            "tempus sans itc": 1,
            "times new roman": 1,
            "toptype soncino": 1,
            "traditional arabic": 1,
            "trebuchet ms": 1,
            tunga: 1,
            "tw cen mt": 1,
            "tw cen mt condensed": 1,
            "tw cen mt condensed extra bold": 1,
            utsaah: 1,
            vani: 1,
            verdana: 1,
            vijaya: 1,
            "viner hand itc": 1,
            vivaldi: 1,
            "vladimir script": 1,
            vrinda: 1,
            webdings: 1,
            "wide latin": 1,
            wingdings: 1,
            "wingdings 2": 1,
            "wingdings 3": 1
        },
        _defaultFontIE64Bit: "Arial",
        processFont: function(font) {
            var processedFont = vml._fontCache[font];
            if (!processedFont) {
                var shim = vml.text_shim || (vml.init(), vml.text_shim), style = shim.style;
                style.font = font;
                var fontFamily = style.fontFamily;
                '"' === fontFamily.charAt(0) && (fontFamily = fontFamily.substr(1, fontFamily.length - 1));
                var ffKey = fontFamily.toLowerCase(), substFF = vml._fontSubst[ffKey];
                substFF ? fontFamily = substFF : vml.is64Bit && !vml._fontWhiteListIE64Bit[ffKey] && (fontFamily = vml._defaultFontIE64Bit);
                style.fontFamily = '"' + fontFamily + '"';
                vml._fontCache[font] = processedFont = style.font;
            }
            return processedFont;
        },
        get_dim: function(attr, target) {
            var o = target || {};
            o.rotation = o.tx = o.ty = 0;
            o.sx = o.sy = 1;
            var transf = attr.transform;
            if (transf) {
                var t = /translate\((-?\d+(?:\.\d+)?(?:e-?\d+)?)(?:,(-?\d+(?:\.\d+)?(?:e-?\d+)?))?\)/.exec(transf);
                if (t) {
                    t[1] && (o.tx = +t[1]);
                    t[2] && (o.ty = +t[2]);
                }
                var r = /rotate\((-?\d+(?:\.\d+)?(?:e-?\d+)?)\)/.exec(transf);
                if (r) {
                    var r = +r[1] % 360;
                    0 > r && (r += 360);
                    r = pv.radians(r);
                }
                var s = /scale\((-?\d+(?:\.\d+)?(?:e-?\d+)?)(?:,(-?\d+(?:\.\d+)?(?:e-?\d+)?))?\)/.exec(transf);
                if (s) {
                    s[1] && (o.sx = +s[1]);
                    s[2] && (o.sy = +s[2]);
                }
                o.rotation = r || 0;
            }
            o.x = parseFloat(attr.x || 0);
            o.y = parseFloat(attr.y || 0);
            "width" in attr && (o.width = parseFloat(attr.width));
            "height" in attr && (o.height = parseFloat(attr.height));
            return o;
        },
        solidFillStyle: {
            type: "solid"
        },
        elm_defaults: {
            g: {
                rewrite: "span",
                attr: function(attr, style, elm) {
                    var d = vml.get_dim(attr);
                    elm.style.cssText = "position:absolute;zoom:1;left:" + (d.tx + d.x) + "px;top:" + (d.ty + d.y) + "px;";
                }
            },
            line: {
                rewrite: "shape",
                attr: function(attr, style, elm, scene, i) {
                    var x1 = parseFloat(attr.x1 || 0), y1 = parseFloat(attr.y1 || 0), x2 = parseFloat(attr.x2 || 0), y2 = parseFloat(attr.y2 || 0), r = vml.round;
                    elm.coordorigin = "0,0";
                    elm.coordsize = "21600,21600";
                    vml.path(elm).v = "M " + r(x1) + " " + r(y1) + " L " + r(x2) + " " + r(y2) + " E";
                    vml.stroke(elm, attr, scene, i);
                },
                css: "top:0px;left:0px;width:1000px;height:1000px"
            },
            rect: {
                rewrite: "shape",
                attr: function(attr, style, elm, scene, i) {
                    var d = vml.get_dim(attr), p = vml.path(elm), r = vml.round;
                    elm.coordorigin = "0,0";
                    elm.coordsize = "21600,21600";
                    var x = r(d.tx + d.x), y = r(d.ty + d.y), w = r(d.width), h = r(d.height);
                    p.v = "M " + x + " " + y + " L " + (x + w) + " " + y + " L " + (x + w) + " " + (y + h) + " L " + x + " " + (y + h) + " x";
                    vml.stroke(elm, attr, scene, i);
                    vml.fill(elm, attr, scene, i);
                },
                css: "top:0px;left:0px;width:1000px;height:1000px"
            },
            path: {
                rewrite: "shape",
                attr: function(attr, style, elm, scene, i) {
                    var d = vml.get_dim(attr), es = elm.style;
                    es.visibility = "hidden";
                    es.left = d.tx + d.x + "px";
                    es.top = d.ty + d.y + "px";
                    elm.coordorigin = "0,0";
                    elm.coordsize = "21600,21600";
                    elm._events = attr["pointer-events"] || "none";
                    vml.path(elm, attr.d);
                    var skew = vml.rotateAndScale(elm, d.rotation && -d.rotation, d.sx, d.sy);
                    skew && (skew.origin = "-0.5,-0.5");
                    vml.fill(elm, attr, scene, i);
                    vml.stroke(elm, attr, scene, i);
                    es.visibility = "visible";
                },
                css: "top:0px;left:0px;width:1000px;height:1000px;"
            },
            ellipse: {
                rewrite: "oval",
                attr: function(attr, style, elm, scene, i) {
                    var d = vml.get_dim(attr), rx = attr.rx, ry = attr.ry, es = elm.style;
                    es.left = d.tx - rx + "px";
                    es.top = d.ty - ry + "px";
                    es.width = 2 * rx + "px";
                    es.height = 2 * ry + "px";
                    var skew = vml.rotateAndScale(elm, d.rotation && -d.rotation);
                    skew && (skew.origin = "0,0");
                    vml.fill(elm, attr, scene, i);
                    vml.stroke(elm, attr, scene, i);
                }
            },
            circle: {
                rewrite: "oval",
                attr: function(attr, style, elm, scene, i) {
                    var d = vml.get_dim(attr), r = parseFloat(attr.r || 0) + .5, cx = parseFloat(attr.cx || 0) + .7, cy = parseFloat(attr.cy || 0) + .7, es = elm.style;
                    es.left = d.tx + cx - r + "px";
                    es.top = d.ty + cy - r + "px";
                    es.width = es.height = 2 * r + "px";
                    vml.fill(elm, attr, scene, i);
                    vml.stroke(elm, attr, scene, i);
                }
            },
            text: {
                rewrite: "shape",
                attr: function(attr, style, elm, scene, i) {
                    elm.style;
                    elm.stroked = "False";
                    elm.path = "m0,0 l1,0 e";
                    var tp = vml.textpath(elm);
                    tp.string = attr.string;
                    var tpStyle = tp.style;
                    tpStyle["v-text-align"] = attr.textAlign;
                    tpStyle.font = attr.font;
                    attr.textDecoration && (tpStyle.textDecoration = attr.textDecoration);
                    vml.path(elm).textpathok = "True";
                    vml.rotateAndScale(elm, attr.rotation && -attr.rotation);
                    var s = scene[i];
                    s.fillStyle = vml.solidFillStyle;
                    vml.fill(elm, attr, scene, i);
                    s.fillStyle = null;
                },
                css: "position:absolute;top:0px;left:0px;width:1px;height:1px;"
            },
            svg: {
                rewrite: "span",
                css: "position:relative;overflow:hidden;display:inline-block;"
            },
            "vml:path": {
                rewrite: "path"
            },
            "vml:stroke": {
                rewrite: "stroke"
            },
            "vml:fill": {
                rewrite: "fill"
            },
            "vml:textpath": {
                rewrite: "textpath"
            },
            "vml:skew": {
                rewrite: "skew"
            }
        },
        _elmcache: {
            span: document.createElement("span"),
            div: document.createElement("div")
        },
        createElement: function(type) {
            var elm, cache = vml._elmcache, helper = vml.elm_defaults[type] || {}, tagName = helper.rewrite || type;
            if (tagName in cache) elm = cache[tagName].cloneNode(!1); else {
                cache[tagName] = document.createElement(vml.pre + tagName + vml.post);
                tagName in vml.block && (cache[tagName].className += " msvml_block");
                elm = cache[tagName].cloneNode(!1);
            }
            helper.css && (elm.style.cssText = helper.css);
            return elm;
        },
        _hex: pv.range(0, 256).map(function(i) {
            return pv.Format.pad("0", 2, i.toString(16));
        }),
        _colorcache: {},
        color: function(value) {
            var rgb, result = vml._colorcache[value];
            !result && (rgb = /^rgb\((\d+),(\d+),(\d+)\)$/i.exec(value)) && (vml._colorcache[value] = result = "#" + vml._hex[rgb[1]] + vml._hex[rgb[2]] + vml._hex[rgb[3]]);
            return result || value;
        },
        fill: function(elm, attr, scene, i) {
            var fill = elm.getElementsByTagName("fill")[0] || (fill = elm.appendChild(vml.createElement("vml:fill"))), fillStyle = scene[i].fillStyle, fillType = fillStyle && fillStyle.type;
            fillType || (fillType = "solid");
            if (!attr.fill || !fillStyle || "solid" === fillType && "none" === attr.fill) fill.on = "false"; else {
                fill.on = "true";
                if ("solid" === fillType) {
                    fill.type = "solid";
                    fill.color = vml.color(attr.fill);
                } else {
                    var isLinear = "lineargradient" === fillType;
                    fill.method = "none";
                    var stops = fillStyle.stops, S = stops.length;
                    if (S > 0) {
                        for (var stopsText = [], i = 0; S > i; i++) {
                            var stop = stops[i];
                            stopsText.push(stop.offset + "% " + vml.color(stop.color.color));
                        }
                        fill.colors && "object" == typeof fill.colors ? fill.colors.value = stopsText.join(",") : fill.colors = stopsText.join(",");
                    }
                    if (isLinear) {
                        fill.type = "gradient";
                        var angle = -pv.degrees(fillStyle.angle) % 360;
                        fill.angle = 0 > angle ? angle + 360 : angle;
                    } else {
                        fill.type = "gradientTitle";
                        fill.focus = "100%";
                        fill.focussize = "0 0";
                        fill.focusposition = "0 0";
                        fill.angle = 0;
                    }
                }
                fill.opacity = Math.min(parseFloat(attr["fill-opacity"] || "1"), 1) || "1";
            }
        },
        stroke: function(elm, attr) {
            var stroke = elm.getElementsByTagName("stroke")[0] || (stroke = elm.appendChild(vml.createElement("vml:stroke")));
            if (attr.stroke && "none" !== attr.stroke) {
                var strokeWidth = attr["stroke-width"];
                strokeWidth = null == strokeWidth || "" === strokeWidth ? 1 : +strokeWidth;
                1e-10 > strokeWidth ? strokeWidth = 0 : 1 > strokeWidth && (strokeWidth = 1);
                if (strokeWidth) {
                    stroke.on = "true";
                    stroke.weight = strokeWidth;
                    stroke.color = vml.color(attr.stroke) || "black";
                    stroke.opacity = Math.min(parseFloat(attr["stroke-opacity"] || "1"), 1) || "1";
                    stroke.joinstyle = vml.joins[attr["stroke-linejoin"]] || "miter";
                    stroke.miterlimit = attr["stroke-miterlimit"] || 8;
                    stroke.endcap = vml.caps[attr["stroke-linecap"]] || "flat";
                    var dashArray = attr["stroke-dasharray"];
                    dashArray && "none" !== dashArray || (dashArray = "Solid");
                    stroke.dashstyle = dashArray;
                } else {
                    stroke.on = "false";
                    stroke.weight = 0;
                }
            } else {
                stroke.on = "false";
                stroke.weight = 0;
            }
        },
        path: function(elm, svgpath) {
            var p = elm.getElementsByTagName("path")[0] || (p = elm.appendChild(vml.createElement("vml:path")));
            arguments.length > 1 && (p.v = vml.rewritePath(svgpath));
            return p;
        },
        skew: function(elm) {
            var sk = elm.getElementsByTagName("skew")[0] || (sk = elm.appendChild(vml.createElement("vml:skew")));
            sk.on = "false";
            return sk;
        },
        rotateAndScale: function(elm, r, sx, sy) {
            r && (r = pv.radians(Math.round(pv.degrees(r)) % 360));
            var hasScale = sx && 1 !== sx || sy && 1 !== sy;
            if (r || hasScale) {
                var skew = vml.skew(elm);
                skew.on = "true";
                skew.offset = "0,0";
                sx || (sx = 1);
                sy || (sy = 1);
                var m;
                if (r) {
                    var ct = Math.cos(r), st = Math.sin(r);
                    m = (sx * ct).toFixed(8) + "," + (sy * st).toFixed(8) + "," + (sx * -st).toFixed(8) + "," + (sy * ct).toFixed(8) + ",0,0";
                } else m = sx.toFixed(8) + ",0,0," + sy.toFixed(8) + ",0,0";
                skew.matrix = m;
                return skew;
            }
        },
        textpath: function(elm) {
            var tp = elm.getElementsByTagName("textpath")[0] || (tp = elm.appendChild(vml.createElement("vml:textpath")));
            tp.style["v-text-align"] = "center";
            tp.style["v-text-kern"] = "true";
            tp.on = "true";
            return tp;
        },
        init: function() {
            if (!vml.text_shim) {
                vml.text_shim = document.getElementById("pv_vml_text_shim") || document.createElement("span");
                vml.text_shim.id = "protovisvml_text_shim";
                vml.text_shim.style.cssText = "position:absolute;left:-9999em;top:-9999em;padding:0;margin:0;line-height:1;display:inline-block;white-space:nowrap;";
                document.body.appendChild(vml.text_shim);
            }
            if (!vml.styles) {
                vml.styles = document.getElementById("protovisvml_styles") || document.createElement("style");
                vml.styles.id = "protovisvml_styles";
                document.documentElement.firstChild.appendChild(vml.styles);
                vml.styles.styleSheet.addRule(".msvml", "behavior:url(#default#VML);");
                vml.styles.styleSheet.addRule(".msvml_block", "position:absolute;top:0;left:0;");
                try {
                    document.namespaces.pvml || document.namespaces.add("pvml", "urn:schemas-microsoft-com:vml");
                } catch (e) {
                    vml.pre = "<";
                    vml.post = ' class="msvml" xmlns="urn:schemas-microsoft.com:vml">';
                }
            }
        },
        _pathcache: {},
        rewritePath: function(p) {
            var x = 0, y = 0, round = vml.round;
            if (!p) return p;
            if (p in vml._pathcache) return vml._pathcache[p];
            p = p.replace(/(\d*)((\.*\d*)(e ?-?\d*))/g, "$1");
            for (var bits = p.match(/([MLHVCSQTAZ ][^MLHVCSQTAZ ]*)/gi), np = [], lastcurve = [], oldOp = "", i = 0, bl = bits.length; bl > i; i++) {
                var itm = bits[i], op = itm.charAt(0), args = itm.substring(1).split(/[,]/);
                " " == op && (op = oldOp);
                oldOp = op;
                switch (op) {
                  case "M":
                    op = "m";
                    x = round(args[0]);
                    y = round(args[1]);
                    args = [ x, y ];
                    break;

                  case "m":
                    op = "m";
                    x += round(args[0]);
                    y += round(args[1]);
                    args = [ x, y ];
                    break;

                  case "A":
                    op = "l";
                    args = [ x = round(args[5]), y = round(args[6]) ];
                    break;

                  case "L":
                    op = "l";
                    args = [ x = round(args[0]), y = round(args[1]) ];
                    break;

                  case "l":
                    op = "l";
                    args = [ x += round(args[0]), y += round(args[1]) ];
                    break;

                  case "H":
                    op = "l";
                    args = [ x = round(args[0]), y ];
                    break;

                  case "h":
                    op = "l";
                    args = [ x += round(args[0]), y ];
                    break;

                  case "V":
                    op = "l";
                    args = [ x, y = round(args[0]) ];
                    break;

                  case "v":
                    op = "l";
                    args = [ x, y += round(args[0]) ];
                    break;

                  case "C":
                    op = "c";
                    lastcurve = args = [ round(args[0]), round(args[1]), round(args[2]), round(args[3]), x = round(args[4]), y = round(args[5]) ];
                    break;

                  case "c":
                    op = "c";
                    lastcurve = args = [ x + round(args[0]), y + round(args[1]), x + round(args[2]), y + round(args[3]), x += round(args[4]), y += round(args[5]) ];
                    break;

                  case "S":
                    op = "c";
                    lastcurve = args = [ lastcurve[4] + (lastcurve[4] - lastcurve[2]), lastcurve[5] + (lastcurve[5] - lastcurve[3]), round(args[0]), round(args[1]), x = round(args[2]), y = round(args[3]) ];
                    break;

                  case "s":
                    op = "c";
                    lastcurve = args = [ lastcurve[4] + (lastcurve[4] - lastcurve[2]), lastcurve[5] + (lastcurve[5] - lastcurve[3]), x + round(args[0]), y + round(args[1]), x += round(args[2]), y += round(args[3]) ];
                    break;

                  case "Q":
                    op = "c";
                    var x1 = round(args[0]), y1 = round(args[1]), x2 = round(args[2]), y2 = round(args[3]);
                    args = [ ~~(x + 2 * (x1 - x) / 3), ~~(y + 2 * (y1 - y) / 3), ~~(x1 + (x2 - x1) / 3), ~~(y1 + (y2 - y1) / 3), x = x2, y = y2 ];
                    break;

                  case "q":
                    op = "l";
                    x += round(args[2]);
                    y += round(args[3]);
                    args = [ x, y ];
                    break;

                  case "Z":
                  case "z":
                    op = "xe";
                    args = [];
                    break;

                  default:
                    op = "";
                    args = [];
                }
                np.push(op, args.join(","));
            }
            return vml._pathcache[p] = np.join("") + "e";
        }
    };
    pv.Text.measure = vml.text_dims;
    pv.Vml = vml;
    pv.VmlScene = {
        scale: 1,
        events: [ "mousewheel", "mousedown", "mouseup", "mouseover", "mouseout", "mousemove", "click", "dblclick", "contextmenu" ],
        mousePositionEventSet: pv.Scene.mousePositionEventSet,
        eventsToNumber: pv.Scene.eventsToNumber,
        numberToEvents: pv.Scene.numberToEvents,
        implicit: {
            css: {}
        },
        copy_functions: function(obj) {
            for (var name in obj) "function" != typeof obj[name] || name in pv.VmlScene || (pv.VmlScene[name] = obj[name]);
        }
    };
    pv.VmlScene.copy_functions(pv.SvgScene);
    pv.Scene = pv.VmlScene;
    pv.renderer = function() {
        return "vml";
    };
    !function(is64bit) {
        pv.VmlScene.minRuleLineWidth = is64bit ? 1.2 : 1.1;
        pv.VmlScene.minBarWidth = is64bit ? 2.2 : 1.8;
        pv.VmlScene.minBarHeight = is64bit ? 2.2 : 1.8;
        pv.VmlScene.minBarLineWidth = is64bit ? 1.2 : 1;
    }(vml.is64Bit);
    pv.VmlScene.expect = function(e, type, scene, i, attr, style) {
        style = style || {};
        var helper = vml.elm_defaults[type] || {}, _type = helper.rewrite || type;
        if (e) {
            if (e.tagName.toUpperCase() !== _type.toUpperCase()) {
                var n = vml.createElement(type);
                e.parentNode.replaceChild(n, e);
                e = n;
            }
        } else e = vml.createElement(type);
        if (attr) {
            "attr" in helper && helper.attr(attr, style, e, scene, i);
            if (attr.cursor in vml.cursorstyles) {
                var curs = vml.cursorstyles[attr.cursor];
                style.cursor = 1 === curs ? attr.cursor : curs;
            }
        }
        style && this.setStyle(e, style);
        return e;
    };
    pv.VmlScene.removeSiblings = function(e) {
        for (;e; ) {
            var n = e.nextSibling;
            e.parentNode.removeChild(e);
            e = n;
        }
    };
    pv.VmlScene.addFillStyleDefinition = function() {};
    pv.VmlScene.setAttributes = function() {};
    pv.VmlScene.setStyle = function(e, style) {
        var prevStyle = e.__style__;
        prevStyle === style && (prevStyle = null);
        var eStyle = e.style;
        for (var name in style) {
            var value = style[name];
            prevStyle && value === prevStyle[name] || (null == value ? eStyle.removeAttribute(name) : eStyle[name] = value);
        }
        e.__style__ = style;
    };
    pv.VmlScene.append = function(e, scene, index) {
        e.$scene = {
            scenes: scene,
            index: index
        };
        e = this.title(e, scene[index]);
        e.parentNode && 11 !== e.parentNode.nodeType || scene.$g.appendChild(e);
        return e.nextSibling;
    };
    pv.VmlScene.title = function(e, s) {
        e.title = s.title || "";
        return e;
    };
    pv.VmlScene.panel = function(scene) {
        for (var style, g = scene.$g, e = g && g.firstChild, inited = !1, i = 0, L = scene.length; L > i; i++) {
            var s = scene[i];
            if (s.visible) {
                if (!scene.parent) {
                    var canvas = s.canvas;
                    style = canvas.style;
                    style.display = "inline-block";
                    style.zoom = 1;
                    if (g && g.parentNode !== canvas) {
                        g = canvas.firstChild;
                        e = g && g.firstChild;
                    }
                    if (!g) {
                        inited = !0;
                        vml.init();
                        g = canvas.appendChild(vml.createElement("svg"));
                        g.unselectable = "on";
                        g.onselectstart = function() {
                            return !1;
                        };
                        for (var events = this.events, dispatch = this.dispatch, j = 0, E = events.length; E > j; j++) g.addEventListener ? g.addEventListener(events[j], dispatch, !1) : g.attachEvent("on" + events[j], dispatch);
                        e = g.firstChild;
                    }
                    scene.$g = g;
                    var w = s.width + s.left + s.right, h = s.height + s.top + s.bottom;
                    style = g.style;
                    style.width = w + "px";
                    style.height = h + "px";
                    style.clip = "rect(0px " + w + "px " + h + "px 0px)";
                }
                var c;
                if ("hidden" === s.overflow) {
                    c = this.expect(e, "g", scene, i);
                    c.style.position = "absolute";
                    c.style.clip = "rect(" + s.top.toFixed(2) + "px " + (s.left + s.width).toFixed(2) + "px " + (s.top + s.height).toFixed(2) + "px " + s.left.toFixed(2) + "px)";
                    c.parentNode || g.appendChild(c);
                    scene.$g = g = c;
                    e = c.firstChild;
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
                    var parentNode = e.parentNode;
                    if (!parentNode || 11 === parentNode.nodeType) {
                        g.appendChild(e);
                        var helper = vml.elm_defaults[e.svgtype];
                        helper && "function" == typeof helper.onappend && helper.onappend(e, scene[i]);
                    }
                    e = e.nextSibling;
                }
                this.scale = k;
                e = this.stroke(e, scene, i);
                if (c) {
                    scene.$g = g = c.parentNode;
                    e = c.nextSibling;
                }
            }
        }
        if (inited) {
            this.removeSiblings(e);
            e = g.appendChild(vml.createElement("oval"));
        }
        return e;
    };
    pv.VmlScene.parseDasharray = function(s) {
        var dashArray = s.strokeDasharray;
        if (dashArray && "none" !== dashArray) {
            var standardDashArray = this.translateDashStyleAlias(dashArray);
            if (this.isStandardDashStyle(standardDashArray)) dashArray = standardDashArray; else {
                dashArray = dashArray.split(/[\s,]+/).map(function(num) {
                    return +num / this.scale;
                }, this);
                dashArray.length % 2 && (dashArray = dashArray.concat(dashArray));
                dashArray = dashArray.join(" ");
            }
        } else dashArray = null;
        return dashArray;
    };
    pv.VmlScene.create = function(type) {
        return vml.createElement(type);
    };
    !function() {
        function IEvent(src) {
            if (src && src.type) {
                this.originalEvent = src;
                this.type = src.type;
                this.isDefaultPrevented = returnFalse;
                (src.defaultPrevented || src.returnValue === !1 || src.getPreventDefault && src.getPreventDefault()) && (this.isDefaultPrevented = returnTrue);
                this.timeStamp = src.timeStamp || Date.now();
            } else {
                this.type = src;
                this.timeStamp = Date.now();
            }
        }
        var returnTrue = function() {
            return !0;
        }, returnFalse = function() {
            return !1;
        }, _event_props = [ "altKey", "attrChange", "attrName", "bubbles", "button", "cancelable", "charCode", "clientX", "clientY", "ctrlKey", "currentTarget", "data", "detail", "eventPhase", "fromElement", "handler", "keyCode", "layerX", "layerY", "metaKey", "newValue", "offsetX", "offsetY", "pageX", "pageY", "prevValue", "relatedNode", "relatedTarget", "screenX", "screenY", "shiftKey", "srcElement", "target", "toElement", "view", "wheelDelta", "which" ], _evPropCount = _event_props.length;
        IEvent.prototype = {
            preventDefault: function() {
                this.isDefaultPrevented = returnTrue;
                var e = this.originalEvent;
                e && (e.preventDefault ? e.preventDefault() : e.returnValue = !1);
            },
            stopPropagation: function() {
                this.isPropagationStopped = returnTrue;
                var e = this.originalEvent;
                if (e) {
                    e.stopPropagation && e.stopPropagation();
                    e.cancelBubble = !0;
                }
            },
            stopImmediatePropagation: function() {
                this.isImmediatePropagationStopped = returnTrue;
                this.stopPropagation();
            },
            isDefaultPrevented: returnFalse,
            isPropagationStopped: returnFalse,
            isImmediatePropagationStopped: returnFalse
        };
        var SCROLL_NODE = document.compatMode && "BackCompat" != document.compatMode ? "documentElement" : "body";
        vml.fixEvent = function(ev) {
            var originalEvent = ev;
            ev = new IEvent(originalEvent);
            for (var type = ev.type, isKey = "keypress" === type, i = _evPropCount; i; ) {
                var prop = _event_props[--i];
                ev[prop] = originalEvent[prop];
            }
            var target = ev.target;
            if (!target) {
                target = ev.srcElement || document;
                3 === target.nodeType && (target = target.parentNode);
                ev.target = target;
            }
            var fromElem;
            !ev.relatedTarget && (fromElem = ev.fromElement) && (ev.relatedTarget = fromElem === target ? ev.toElement : fromElem);
            if (!isKey) {
                var clientX;
                if (null == ev.pageX && null != (clientX = ev.clientX)) {
                    var scrollNode = document[SCROLL_NODE];
                    ev.pageX = clientX + (scrollNode.scrollLeft || 0) - (scrollNode.clientLeft || 0);
                    ev.pageY = ev.clientY + (scrollNode.scrollTop || 0) - (scrollNode.clientTop || 0);
                }
            }
            if (null == ev.which) {
                var charCode, keyCode, btn;
                isKey ? null != (charCode = ev.charCode) ? ev.which = charCode : null != (keyCode = ev.keyCode) && (ev.which = keyCode) : null !== (btn = ev.button) && (ev.which = 1 & btn ? 1 : 2 & btn ? 3 : 4 & btn ? 2 : 0);
            }
            ev.metaKey = !!ev.metaKey;
            "mousewheel" === type && (ev.wheel = ev.wheelDelta);
            return ev;
        };
    }();
    pv.fixEvent = function(ev) {
        return vml.fixEvent(ev || window.event);
    };
    pv.VmlScene.dispatch = pv.listener(function(e) {
        var t = e.target.$scene;
        if (t) {
            var events = e.target._events;
            if ("none" === events || pv.Mark.dispatch(e.type, t.scenes, t.index, e)) {
                e.preventDefault();
                e.stopPropagation();
            }
        }
    });
    pv.VmlScene.image = function(scene) {
        for (var e = scene.$g.firstChild, i = 0; i < scene.length; i++) {
            var s = scene[i];
            if (s.visible) {
                e = this.fill(e, scene, i);
                if (s.image) ; else {
                    e = new Image();
                    e.src = s.url;
                    var st = e.style;
                    st.position = "absolute";
                    st.top = s.top;
                    st.left = s.left;
                    st.width = s.width;
                    st.height = s.height;
                    st.cursor = s.cursor;
                    st.msInterpolationMode = "bicubic";
                }
                e = this.append(e, scene, i);
                e = this.stroke(e, scene, i);
            }
        }
        return e;
    };
    pv.VmlScene.label = function(scene) {
        for (var e = scene.$g.firstChild, i = 0, L = scene.length; L > i; i++) {
            var s = scene[i];
            if (s.visible) {
                var fill = s.textStyle;
                if (fill.opacity && s.text) {
                    var txt = s.text.replace(/\s+/g, " "), font = vml.processFont(s.font), label = vml.text_dims(txt, font), dx = 0, dy = 0;
                    switch (s.textBaseline) {
                      case "middle":
                        dy = .1 * label.height;
                        break;

                      case "top":
                        dy = s.textMargin + .5 * label.height;
                        break;

                      case "bottom":
                        dy = -(s.textMargin + .5 * label.height);
                    }
                    switch (s.textAlign) {
                      case "left":
                        dx = s.textMargin;
                        break;

                      case "right":
                        dx = -s.textMargin;
                    }
                    var a = s.textAngle;
                    if (a) {
                        var ct = Math.cos(a), st = Math.sin(a), dx2 = dx * ct - dy * st, dy2 = dx * st + dy * ct;
                        dx = dx2;
                        dy = dy2;
                    }
                    var left = s.left + dx, top = s.top + dy, attr = {};
                    s.cursor && (attr.cursor = s.cursor);
                    attr.fill = vml.color(fill.color) || "black";
                    vml.is64Bit && (attr["fill-opacity"] = .7);
                    attr.x = left;
                    attr.y = top;
                    attr.rotation = s.textAngle;
                    attr.string = txt;
                    attr.textAlign = s.textAlign;
                    attr.font = font;
                    attr.textDecoration = s.textDecoration;
                    e = this.expect(e, "text", scene, i, attr, {
                        display: "block",
                        lineHeight: 1,
                        whiteSpace: "nowrap",
                        zoom: 1,
                        position: "absolute",
                        cursor: "default",
                        top: top + "px",
                        left: left + "px"
                    });
                    e = this.append(e, scene, i);
                }
            }
        }
        return e;
    };
    pv.VmlScene.wedge = function(scene) {
        for (var e = scene.$g.firstChild, round = vml.round, i = 0, L = scene.length; L > i; i++) {
            var s = scene[i];
            if (s.visible) {
                var fill = s.fillStyle, stroke = s.strokeStyle;
                if (fill.opacity || stroke.opacity) {
                    e = this.expect(e, "path", scene, i, {
                        "pointer-events": s.events,
                        cursor: s.cursor,
                        transform: "translate(" + s.left + "," + s.top + ")",
                        d: "",
                        fill: fill.color,
                        "fill-rule": "evenodd",
                        "fill-opacity": fill.opacity || null,
                        stroke: stroke.color,
                        "stroke-opacity": stroke.opacity || null,
                        "stroke-width": stroke.opacity ? s.lineWidth / this.scale : null,
                        "stroke-linecap": s.lineCap,
                        "stroke-linejoin": s.lineJoin,
                        "stroke-miterlimit": s.strokeMiterLimit,
                        "stroke-dasharray": stroke.opacity ? this.parseDasharray(s) : null
                    });
                    var p = e.getElementsByTagName("path")[0];
                    if (!p) {
                        p = vml.make("path");
                        e.appendChild(p);
                    }
                    var d, r1 = round(s.innerRadius), r2 = round(s.outerRadius);
                    if (s.angle >= 2 * Math.PI) d = r1 ? "AE0,0 " + r2 + "," + r2 + " 0 23592960AL0,0 " + r1 + "," + r1 + " 0 23592960" : "AE0,0 " + r2 + "," + r2 + " 0 23592960"; else {
                        var sa = Math.round(s.startAngle / Math.PI * 11796480), a = Math.round(s.angle / Math.PI * 11796480);
                        d = r1 ? "AE 0,0 " + r2 + "," + r2 + " " + -sa + " " + -a + " 0,0 " + r1 + "," + r1 + " " + -(sa + a) + " " + a + "X" : "M0,0AE0,0 " + r2 + "," + r2 + " " + -sa + " " + -a + "X";
                    }
                    p.v = d;
                    e = this.append(e, scene, i);
                }
            }
        }
        return e;
    };
}();