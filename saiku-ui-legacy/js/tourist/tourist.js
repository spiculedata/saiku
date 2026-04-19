(function() {
    var _ref, _ref1, _ref2, _ref3,
        __hasProp = {}.hasOwnProperty,
        __extends = function(child, parent) { for (var key in parent) { if (__hasProp.call(parent, key)) child[key] = parent[key]; } function ctor() { this.constructor = child; } ctor.prototype = parent.prototype; child.prototype = new ctor(); child.__super__ = parent.prototype; return child; },
        __bind = function(fn, me){ return function(){ return fn.apply(me, arguments); }; };

    window.Tourist = window.Tourist || {};

    /*
     A model for the Tour. We'll only use the 'current_step' property.
     */


    Tourist.Model = (function(_super) {
        __extends(Model, _super);

        function Model() {
            _ref = Model.__super__.constructor.apply(this, arguments);
            return _ref;
        }

        Model.prototype._module = 'Tourist';

        return Model;

    })(Backbone.Model);

    window.Tourist.Tip = window.Tourist.Tip || {};

    /*
     The flyout showing the content of each step.

     This is the base class containing most of the logic. Can extend for different
     tooltip implementations.
     */


    Tourist.Tip.Base = (function() {
        Base.prototype._module = 'Tourist';

        _.extend(Base.prototype, Backbone.Events);

        Base.prototype.skipButtonTemplate = '<button class="btn btn-default btn-sm pull-right tour-next">Skip this step →</button>';

        Base.prototype.nextButtonTemplate = '<button class="btn btn-primary btn-sm pull-right tour-next">Next step →</button>';

        Base.prototype.finalButtonTemplate = '<button class="btn btn-primary btn-sm pull-right tour-next">Finish up</button>';

        Base.prototype.closeButtonTemplate = '<a class="btn btn-close tour-close" href="#"><i class="glyphicon glyphicon-remove"></i></a>';

        Base.prototype.okButtonTemplate = '<button class="btn btn-sm tour-close btn-primary">Okay</button>';

        Base.prototype.actionLabelTemplate = _.template('<h4 class="action-label"><%= label %></h4>');

        Base.prototype.actionLabels = ['Do this:', 'Then this:', 'Next this:'];

        Base.prototype.highlightClass = 'tour-highlight';

        Base.prototype.template = _.template('<div>\n  <div class="tour-container">\n    <%= close_button %>\n    <%= content %>\n    <p class="tour-counter <%= counter_class %>"><%= counter%></p>\n  </div>\n  <div class="tour-buttons">\n    <%= buttons %>\n  </div>\n</div>');

        function Base(options) {
            this.options = options != null ? options : {};
            this.onClickNext = __bind(this.onClickNext, this);
            this.onClickClose = __bind(this.onClickClose, this);
            this.el = $('<div/>');
            this.initialize(options);
            this._bindClickEvents();
            Tourist.Tip.Base._cacheTip(this);
        }

        Base.prototype.destroy = function() {
            return this.el.remove();
        };

        Base.prototype.render = function(step) {
            this.hide();
            if (step) {
                this._setTarget(step.target || false, step);
                this._setZIndex('');
                this._renderContent(step, this._buildContentElement(step));
                if (step.target) {
                    this.show();
                }
                if (step.zIndex) {
                    this._setZIndex(step.zIndex, step);
                }
            }
            return this;
        };

        Base.prototype.show = function() {};

        Base.prototype.hide = function() {};

        Base.prototype.setTarget = function(targetElement, step) {
            return this._setTarget(targetElement, step);
        };

        Base.prototype.cleanupCurrentTarget = function() {
            if (this.target && this.target.removeClass) {
                this.target.removeClass(this.highlightClass);
            }
            return this.target = null;
        };

        /*
         Event Handlers
         */


        Base.prototype.onClickClose = function(event) {
            this.trigger('click:close', this, event);
            return false;
        };

        Base.prototype.onClickNext = function(event) {
            this.trigger('click:next', this, event);
            return false;
        };

        /*
         Private
         */


        Base.prototype._getTipElement = function() {};

        Base.prototype._renderContent = function(step, contentElement) {};

        Base.prototype._bindClickEvents = function() {
            var el;
            el = this._getTipElement();
            el.delegate('.tour-close', 'click', this.onClickClose);
            return el.delegate('.tour-next', 'click', this.onClickNext);
        };

        Base.prototype._setTarget = function(target, step) {
            this.cleanupCurrentTarget();
            if (target && step && step.highlightTarget) {
                target.addClass(this.highlightClass);
            }
            return this.target = target;
        };

        Base.prototype._setZIndex = function(zIndex) {
            var el;
            el = this._getTipElement();
            return el.css('z-index', zIndex || '');
        };

        Base.prototype._buildContentElement = function(step) {
            var buttons, content;
            buttons = this._buildButtons(step);
            content = $($.parseHTML(this.template({
                content: step.content,
                buttons: buttons,
                close_button: this._buildCloseButton(step),
                counter: step.final ? '' : "step " + (step.index + 1) + " of " + step.total,
                counter_class: step.final ? 'final' : ''
            })));
            if (!buttons) {
                content.find('.tour-buttons').addClass('no-buttons');
            }
            this._renderActionLabels(content);
            return content;
        };

        Base.prototype._buildButtons = function(step) {
            var buttons;
            buttons = '';
            if (step.okButton) {
                buttons += this.okButtonTemplate;
            }
            if (step.skipButton) {
                buttons += this.skipButtonTemplate;
            }
            if (step.nextButton) {
                buttons += step.final ? this.finalButtonTemplate : this.nextButtonTemplate;
            }
            return buttons;
        };

        Base.prototype._buildCloseButton = function(step) {
            if (step.closeButton) {
                return this.closeButtonTemplate;
            } else {
                return '';
            }
        };

        Base.prototype._renderActionLabels = function(el) {
            var action, actionIndex, actions, label, _i, _len, _results;
            actions = el.find('.action');
            actionIndex = 0;
            _results = [];
            for (_i = 0, _len = actions.length; _i < _len; _i++) {
                action = actions[_i];
                label = $($.parseHTML(this.actionLabelTemplate({
                    label: this.actionLabels[actionIndex]
                })));
                label.insertBefore(action);
                _results.push(actionIndex++);
            }
            return _results;
        };

        Base._cacheTip = function(tip) {
            if (!Tourist.Tip.Base._cachedTips) {
                Tourist.Tip.Base._cachedTips = [];
            }
            return Tourist.Tip.Base._cachedTips.push(tip);
        };

        Base.destroy = function() {
            var tip, _i, _len, _ref1;
            if (!Tourist.Tip.Base._cachedTips) {
                return;
            }
            _ref1 = Tourist.Tip.Base._cachedTips;
            for (_i = 0, _len = _ref1.length; _i < _len; _i++) {
                tip = _ref1[_i];
                tip.destroy();
            }
            return Tourist.Tip.Base._cachedTips = null;
        };

        return Base;

    })();

    /*
     Bootstrap based tip implementation
     */


    Tourist.Tip.Bootstrap = (function(_super) {
        __extends(Bootstrap, _super);

        function Bootstrap() {
            _ref1 = Bootstrap.__super__.constructor.apply(this, arguments);
            return _ref1;
        }

        Bootstrap.prototype.initialize = function(options) {
            var defs;
            defs = {
                showEffect: null,
                hideEffect: null
            };
            this.options = _.extend(defs, options);
            return this.tip = new Tourist.Tip.BootstrapTip(this.options);
        };

        Bootstrap.prototype.destroy = function() {
            this.tip.destroy();
            return Bootstrap.__super__.destroy.call(this);
        };

        Bootstrap.prototype.show = function() {
            var fn;
            if (this.options.showEffect) {
                fn = Tourist.Tip.Bootstrap.effects[this.options.showEffect];
                return fn.call(this, this.tip, this.tip.el);
            } else {
                return this.tip.show();
            }
        };

        Bootstrap.prototype.hide = function() {
            var fn;
            if (this.options.hideEffect) {
                fn = Tourist.Tip.Bootstrap.effects[this.options.hideEffect];
                return fn.call(this, this.tip, this.tip.el);
            } else {
                return this.tip.hide();
            }
        };

        /*
         Private
         */


        Bootstrap.prototype._getTipElement = function() {
            return this.tip.el;
        };

        Bootstrap.prototype._setTarget = function(target, step) {
            Bootstrap.__super__._setTarget.call(this, target, step);
            return this.tip.setTarget(target);
        };

        Bootstrap.prototype._renderContent = function(step, contentElement) {
            var at, my;
            my = step.my || 'left center';
            at = step.at || 'right center';
            this.tip.setContainer(step.container || $('body'));
            this.tip.setContent(contentElement);
            return this.tip.setPosition(step.target || false, my, at);
        };

        return Bootstrap;

    })(Tourist.Tip.Base);

    Tourist.Tip.Bootstrap.effects = {
        slidein: function(tip, element) {
            var OFFSETS, css, easing, easings, offset, side, value, _i, _len;
            OFFSETS = {
                top: 80,
                left: 80,
                right: -80,
                bottom: -80
            };
            side = tip.my.split(' ')[0];
            side = side || 'top';
            offset = OFFSETS[side];
            if (side === 'bottom') {
                side = 'top';
            }
            if (side === 'right') {
                side = 'left';
            }
            value = parseInt(element.css(side));
            element.stop();
            css = {};
            css[side] = value + offset;
            element.css(css);
            element.show();
            css[side] = value;
            easings = ['easeOutCubic', 'swing', 'linear'];
            for (_i = 0, _len = easings.length; _i < _len; _i++) {
                easing = easings[_i];
                if ($.easing[easing]) {
                    break;
                }
            }
            element.animate(css, 300, easing);
            return null;
        }
    };

    /*
     Simple implementation of tooltip with bootstrap markup.

     Almost entirely deals with positioning. Uses the similar method for
     positioning as qtip2:

     my: 'top center'
     at: 'bottom center'
     */


    Tourist.Tip.BootstrapTip = (function() {
        BootstrapTip.prototype.template = '<div class="popover tourist-popover">\n  <div class="arrow"></div>\n  <div class="popover-content"></div>\n</div>';

        BootstrapTip.prototype.FLIP_POSITION = {
            bottom: 'top',
            top: 'bottom',
            left: 'right',
            right: 'left'
        };

        function BootstrapTip(options) {
            var defs;
            defs = {
                offset: 10,
                tipOffset: 10
            };
            this.options = _.extend(defs, options);
            this.el = $($.parseHTML(this.template));
            this.hide();
        }

        BootstrapTip.prototype.destroy = function() {
            return this.el.remove();
        };

        BootstrapTip.prototype.show = function() {
            return this.el.show().addClass('visible');
        };

        BootstrapTip.prototype.hide = function() {
            return this.el.hide().removeClass('visible');
        };

        BootstrapTip.prototype.setTarget = function(target) {
            this.target = target;
            return this._setPosition(this.target, this.my, this.at);
        };

        BootstrapTip.prototype.setPosition = function(target, my, at) {
            this.target = target;
            this.my = my;
            this.at = at;
            return this._setPosition(this.target, this.my, this.at);
        };

        BootstrapTip.prototype.setContainer = function(container) {
            return container.append(this.el);
        };

        BootstrapTip.prototype.setContent = function(content) {
            return this._getContentElement().html(content);
        };

        /*
         Private
         */


        BootstrapTip.prototype._getContentElement = function() {
            return this.el.find('.popover-content');
        };

        BootstrapTip.prototype._getTipElement = function() {
            return this.el.find('.arrow');
        };

        BootstrapTip.prototype._setPosition = function(target, my, at) {
            var clas, css, originalDisplay, position, shift, targetPosition, tip, tipOffset, tipPosition, _ref2;
            if (my == null) {
                my = 'left center';
            }
            if (at == null) {
                at = 'right center';
            }
            if (!target) {
                return;
            }
            _ref2 = my.split(' '), clas = _ref2[0], shift = _ref2[1];
            originalDisplay = this.el.css('display');
            this.el.css({
                top: 0,
                left: 0,
                margin: 0,
                display: 'block'
            }).removeClass('top').removeClass('bottom').removeClass('left').removeClass('right').addClass(this.FLIP_POSITION[clas]);
            if (!target) {
                return;
            }
            tip = this._getTipElement().css({
                left: '',
                right: '',
                top: '',
                bottom: ''
            });
            if (shift !== 'center') {
                tipOffset = {
                    left: tip[0].offsetWidth / 2,
                    right: 0,
                    top: tip[0].offsetHeight / 2,
                    bottom: 0
                };
                css = {};
                css[shift] = tipOffset[shift] + this.options.tipOffset;
                css[this.FLIP_POSITION[shift]] = 'auto';
                tip.css(css);
            }
            targetPosition = this._caculateTargetPosition(at, target);
            tipPosition = this._caculateTipPosition(my, targetPosition);
            position = this._adjustForArrow(my, tipPosition);
            this.el.css(position);
            return this.el.css({
                display: originalDisplay
            });
        };

        BootstrapTip.prototype._caculateTargetPosition = function(atPosition, target) {
            var bounds, pos;
            if (Object.prototype.toString.call(target) === '[object Array]') {
                return {
                    left: target[0],
                    top: target[1]
                };
            }
            bounds = this._getTargetBounds(target);
            pos = this._lookupPosition(atPosition, bounds.width, bounds.height);
            return {
                left: bounds.left + pos[0],
                top: bounds.top + pos[1]
            };
        };

        BootstrapTip.prototype._caculateTipPosition = function(myPosition, targetPosition) {
            var height, pos, width;
            width = this.el[0].offsetWidth;
            height = this.el[0].offsetHeight;
            pos = this._lookupPosition(myPosition, width, height);
            return {
                left: targetPosition.left - pos[0],
                top: targetPosition.top - pos[1]
            };
        };

        BootstrapTip.prototype._adjustForArrow = function(myPosition, tipPosition) {
            var clas, height, position, shift, tip, width, _ref2;
            _ref2 = myPosition.split(' '), clas = _ref2[0], shift = _ref2[1];
            tip = this._getTipElement();
            width = tip[0].offsetWidth;
            height = tip[0].offsetHeight;
            position = {
                top: tipPosition.top,
                left: tipPosition.left
            };
            switch (clas) {
                case 'top':
                    position.top += height + this.options.offset;
                    break;
                case 'bottom':
                    position.top -= height + this.options.offset;
                    break;
                case 'left':
                    position.left += width + this.options.offset;
                    break;
                case 'right':
                    position.left -= width + this.options.offset;
            }
            switch (shift) {
                case 'left':
                    position.left -= width / 2 + this.options.tipOffset;
                    break;
                case 'right':
                    position.left += width / 2 + this.options.tipOffset;
                    break;
                case 'top':
                    position.top -= height / 2 + this.options.tipOffset;
                    break;
                case 'bottom':
                    position.top += height / 2 + this.options.tipOffset;
            }
            return position;
        };

        BootstrapTip.prototype._lookupPosition = function(position, width, height) {
            var height2, posLookup, width2;
            width2 = width / 2;
            height2 = height / 2;
            posLookup = {
                'top left': [0, 0],
                'left top': [0, 0],
                'top right': [width, 0],
                'right top': [width, 0],
                'bottom left': [0, height],
                'left bottom': [0, height],
                'bottom right': [width, height],
                'right bottom': [width, height],
                'top center': [width2, 0],
                'left center': [0, height2],
                'right center': [width, height2],
                'bottom center': [width2, height]
            };
            return posLookup[position];
        };

        BootstrapTip.prototype._getTargetBounds = function(target) {
            var el, size;
            el = target[0];
            if (typeof el.getBoundingClientRect === 'function') {
                size = el.getBoundingClientRect();
            } else {
                size = {
                    width: el.offsetWidth,
                    height: el.offsetHeight
                };
            }
            return $.extend({}, size, target.offset());
        };

        return BootstrapTip;

    })();

    /*
     Qtip based tip implementation
     */


    Tourist.Tip.QTip = (function(_super) {
        var ADJUST, OFFSETS, TIP_HEIGHT, TIP_WIDTH;

        __extends(QTip, _super);

        function QTip() {
            this._renderTipBackground = __bind(this._renderTipBackground, this);
            _ref2 = QTip.__super__.constructor.apply(this, arguments);
            return _ref2;
        }

        TIP_WIDTH = 6;

        TIP_HEIGHT = 14;

        ADJUST = 10;

        OFFSETS = {
            top: 80,
            left: 80,
            right: -80,
            bottom: -80
        };

        QTip.prototype.QTIP_DEFAULTS = {
            content: {
                text: ' '
            },
            show: {
                ready: false,
                delay: 0,
                effect: function(qtip) {
                    var css, el, offset, side, value;
                    el = $(this);
                    side = qtip.options.position.my;
                    if (side) {
                        side = side[side.precedance];
                    }
                    side = side || 'top';
                    offset = OFFSETS[side];
                    if (side === 'bottom') {
                        side = 'top';
                    }
                    if (side === 'right') {
                        side = 'left';
                    }
                    value = parseInt(el.css(side));
                    css = {};
                    css[side] = value + offset;
                    el.css(css);
                    el.show();
                    css[side] = value;
                    el.animate(css, 300, 'easeOutCubic');
                    return null;
                },
                autofocus: false
            },
            hide: {
                event: null,
                delay: 0,
                effect: false
            },
            position: {
                adjust: {
                    method: 'shift shift',
                    scroll: false
                }
            },
            style: {
                classes: 'ui-tour-tip',
                tip: {
                    height: TIP_WIDTH,
                    width: TIP_HEIGHT
                }
            },
            events: {},
            zindex: 2000
        };

        QTip.prototype.initialize = function(options) {
            options = $.extend(true, {}, this.QTIP_DEFAULTS, options);
            this.el.qtip(options);
            this.qtip = this.el.qtip('api');
            return this.qtip.render();
        };

        QTip.prototype.destroy = function() {
            if (this.qtip) {
                this.qtip.destroy();
            }
            return QTip.__super__.destroy.call(this);
        };

        QTip.prototype.show = function() {
            return this.qtip.show();
        };

        QTip.prototype.hide = function() {
            return this.qtip.hide();
        };

        /*
         Private
         */


        QTip.prototype._getTipElement = function() {
            return $('#qtip-' + this.qtip.id);
        };

        QTip.prototype._setTarget = function(targetElement, step) {
            QTip.__super__._setTarget.call(this, targetElement, step);
            return this.qtip.set('position.target', targetElement || false);
        };

        QTip.prototype._renderContent = function(step, contentElement) {
            var at, my,
                _this = this;
            my = step.my || 'left center';
            at = step.at || 'right center';
            this._adjustPlacement(my, at);
            this.qtip.set('content.text', contentElement);
            this.qtip.set('position.container', step.container || $('body'));
            this.qtip.set('position.my', my);
            this.qtip.set('position.at', at);
            this.qtip.set('position.viewport', step.viewport || false);
            this.qtip.set('position.target', step.target || false);
            return setTimeout(function() {
                return _this._renderTipBackground(my.split(' ')[0]);
            }, 10);
        };

        QTip.prototype._adjustPlacement = function(my, at) {
            if (my.indexOf('top') === 0) {
                return this._adjust(0, ADJUST);
            } else if (my.indexOf('bottom') === 0) {
                return this._adjust(0, -ADJUST);
            } else if (my.indexOf('right') === 0) {
                return this._adjust(-ADJUST, 0);
            } else {
                return this._adjust(ADJUST, 0);
            }
        };

        QTip.prototype._adjust = function(adjustX, adjusty) {
            this.qtip.set('position.adjust.x', adjustX);
            return this.qtip.set('position.adjust.y', adjusty);
        };

        QTip.prototype._renderTipBackground = function(direction) {
            var bg, el;
            el = $('#qtip-' + this.qtip.id + ' .qtip-tip');
            bg = el.find('.qtip-tip-bg');
            if (!bg.length) {
                bg = $('<div/>', {
                    'class': 'icon icon-tip qtip-tip-bg'
                });
                el.append(bg);
            }
            bg.removeClass('top left right bottom');
            return bg.addClass(direction);
        };

        return QTip;

    })(Tourist.Tip.Base);

    /*
     Simplest implementation of a tooltip. Used in the tests. Useful as an example
     as well.
     */


    Tourist.Tip.Simple = (function(_super) {
        __extends(Simple, _super);

        function Simple() {
            _ref3 = Simple.__super__.constructor.apply(this, arguments);
            return _ref3;
        }

        Simple.prototype.initialize = function(options) {
            return $('body').append(this.el);
        };

        Simple.prototype.show = function() {
            return this.el.show();
        };

        Simple.prototype.hide = function() {
            return this.el.hide();
        };

        Simple.prototype._getTipElement = function() {
            return this.el;
        };

        Simple.prototype._renderContent = function(step, contentElement) {
            return this.el.html(contentElement);
        };

        return Simple;

    })(Tourist.Tip.Base);

    /*

     A way to make a tour. Basically, you specify a series of steps which explain
     elements to point at and what to say. This class manages moving between those
     steps.

     The 'step object' is a simple js obj that specifies how the step will behave.

     A simple Example of a step object:
     {
     content: '<p>Welcome to my step</p>'
     target: $('#something-to-point-at')
     closeButton: true
     highlightTarget: true
     setup: (tour, options) ->
     # do stuff in the interface/bind
     teardown: (tour, options) ->
     # remove stuff/unbind
     }

     Basic Step object options:

     content - a string of html to put into the step.
     target - jquery object or absolute point: [10, 30]
     highlightTarget - optional bool, true will outline the target with a bright color.
     container - optional jquery element that should contain the step flyout.
     default: $('body')
     viewport - optional jquery element that the step flyout should stay within.
     $(window) is commonly used. default: false

     my - string position of the pointer on the tip. default: 'left center'
     at - string position on the element the tip points to. default: 'right center'
     see http://craigsworks.com/projects/qtip2/docs/position/#basics

     Step object button options:

     okButton - optional bool, true will show a red ok button
     closeButton - optional bool, true will show a grey close button
     skipButton - optional bool, true will show a grey skip button
     nextButton - optional bool, true will show a red next button

     Step object function options:

     All functions on the step will have the signature '(tour, options) ->'

     tour - the Draw.Tour object. Handy to call tour.next()
     options - the step options. An object passed into the tour when created.
     It has the environment that the fns can use to manipulate the
     interface, bind to events, etc. The same object is passed to all
     of a step object's functions, so it is handy for passing data
     between steps.

     setup - called before step is shown. Use to scroll to your target, hide/show things, ...

     'this' is the step object itself.

     MUST return an object. Properties in the returned object will override
     properties in the step object.

     i.e. the target might be dynamic so you would specify:

     setup: (tour, options) ->
     return { target: $('#point-to-me') }

     teardown - function called right before hiding the step. Use to unbind from
     things you bound to in setup().

     'this' is the step object itself.

     Return nothing.

     bind - an array of function names to bind. Use this for event handlers you use in setup().

     Will bind functions to the step object as this, and the first 2 args as tour and options.

     i.e.

     bind: ['onChangeSomething']
     setup: (tour, options) ->
     options.document.bind('change:something', @onChangeSomething)
     onChangeSomething: (tour, options, model, value) ->
     tour.next()
     teardown: (tour, options) ->
     options.document.unbind('change:something', @onChangeSomething)
     */


    Tourist.Tour = (function() {
        _.extend(Tour.prototype, Backbone.Events);

        function Tour(options) {
            var defs, tipOptions;
            this.options = options != null ? options : {};
            this.onChangeCurrentStep = __bind(this.onChangeCurrentStep, this);
            this.next = __bind(this.next, this);
            defs = {
                tipClass: 'Bootstrap'
            };
            this.options = _.extend(defs, this.options);
            this.model = new Tourist.Model({
                current_step: null
            });
            tipOptions = _.extend({
                model: this.model
            }, this.options.tipOptions);
            this.view = new Tourist.Tip[this.options.tipClass](tipOptions);
            this.view.bind('click:close', _.bind(this.stop, this, true));
            this.view.bind('click:next', this.next);
            this.model.bind('change:current_step', this.onChangeCurrentStep);
        }

        /*
         Public
         */


        Tour.prototype.start = function() {
            this.trigger('start', this);
            return this.next();
        };

        Tour.prototype.stop = function(doFinalStep) {
            if (doFinalStep) {
                return this._showCancelFinalStep();
            } else {
                return this._stop();
            }
        };

        Tour.prototype.next = function() {
            var currentStep, index;
            currentStep = this._teardownCurrentStep();
            index = 0;
            if (currentStep) {
                index = currentStep.index + 1;
            }
            if (index < this.options.steps.length) {
                return this._showStep(this.options.steps[index], index);
            } else if (index === this.options.steps.length) {
                return this._showSuccessFinalStep();
            } else {
                return this._stop();
            }
        };

        Tour.prototype.setStepOptions = function(stepOptions) {
            return this.options.stepOptions = stepOptions;
        };

        /*
         Handlers
         */


        Tour.prototype.onChangeCurrentStep = function(model, step) {
            return this.view.render(step);
        };

        /*
         Private
         */


        Tour.prototype._showCancelFinalStep = function() {
            return this._showFinalStep(false);
        };

        Tour.prototype._showSuccessFinalStep = function() {
            return this._showFinalStep(true);
        };

        Tour.prototype._teardownCurrentStep = function() {
            var currentStep;
            currentStep = this.model.get('current_step');
            this._teardownStep(currentStep);
            return currentStep;
        };

        Tour.prototype._stop = function() {
            this._teardownCurrentStep();
            this.model.set({
                current_step: null
            });
            return this.trigger('stop', this);
        };

        Tour.prototype._showFinalStep = function(success) {
            var currentStep, finalStep;
            currentStep = this._teardownCurrentStep();
            finalStep = success ? this.options.successStep : this.options.cancelStep;
            if (_.isFunction(finalStep)) {
                finalStep.call(this, this, this.options.stepOptions);
                finalStep = null;
            }
            if (!finalStep) {
                return this._stop();
            }
            if (currentStep && currentStep.final) {
                return this._stop();
            }
            finalStep.final = true;
            return this._showStep(finalStep, this.options.steps.length);
        };

        Tour.prototype._showStep = function(step, index) {
            if (!step) {
                return;
            }
            step = _.clone(step);
            step.index = index;
            step.total = this.options.steps.length;
            if (!step.final) {
                step.final = this.options.steps.length === index + 1 && !this.options.successStep;
            }
            step = _.extend(step, this._setupStep(step));
            return this.model.set({
                current_step: step
            });
        };

        Tour.prototype._setupStep = function(step) {
            var fn, _i, _len, _ref4;
            if (!(step && step.setup)) {
                return {};
            }
            if (step.bind) {
                _ref4 = step.bind;
                for (_i = 0, _len = _ref4.length; _i < _len; _i++) {
                    fn = _ref4[_i];
                    step[fn] = _.bind(step[fn], step, this, this.options.stepOptions);
                }
            }
            return step.setup.call(step, this, this.options.stepOptions) || {};
        };

        Tour.prototype._teardownStep = function(step) {
            if (step && step.teardown) {
                step.teardown.call(step, this, this.options.stepOptions);
            }
            return this.view.cleanupCurrentTarget();
        };

        return Tour;

    })();

}).call(this);