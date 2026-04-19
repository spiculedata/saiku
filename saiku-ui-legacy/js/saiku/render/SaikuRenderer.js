/*
var SaikuRendererRegistry = {

};

SaikuRendererRegistry.prototype.register = function(key, data, options) {
    if (this.hasOwnProperty(key)) {
        return new SaikuRendererRegistry[key](data, options);
    } else {
        throw("No renderer with name '" + key + "' registered!");
    }
};
*/

var SaikuRendererOptions = {
    mode: null,
    dataMode: null,
    htmlObject: null,
    width: null,
    height: null
};

var SaikuRenderer = function(data, options) {
    this._options = _.extend(SaikuRendererOptions, options);
    this._hasProcessed = false;
    if (typeof Backbone !== "undefined") {
        _.extend(this, Backbone.Events);
    }

this.render = function(data, options) {
    var r = null;
    if (typeof Backbone !== "undefined") {
        this.trigger('render:start', this );
    }

    if (!this.hasProcessedData()) {
        this.processData(data, options);
    }
    r = this._render(data, options);
    if (typeof Backbone !== "undefined") {
        this.trigger('render:end', this );
    }
    return r;
};

this.processData = function(data, options) {
    if (typeof Backbone !== "undefined") {
        this.trigger('processData:start', this );
    }
    this._processData(data, options);
    if (typeof Backbone !== "undefined") {
        this.trigger('processData:end', this );
    }
};
this.hasProcessedData = function() {
    return this._hasProcessed;
};


this._render = function(data, options) {};
this._processData = function(data, options) {};

if (data) {
        this._data = data;
        this.processData(data, options);
        this._hasProcessed = true;
}

};