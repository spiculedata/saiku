//     Backbone.Model File Upload v0.1
//     by Joe Vu - joe.vu@homeslicesolutions.com
//     For all details and documentation:
//     https://github.com/homeslicesolutions/backbone-model-file-upload

!function(_, Backbone){

  // Clone the original Backbone.Model.prototype
  var backboneModelClone = _.clone( Backbone.Model.prototype );

  // Extending out
  _.extend(Backbone.Model.prototype, {  

    // ! Default file attribute - can be overwritten
    fileAttribute: 'file',

    // @ Save - overwritten
    save: function(key, val, options) {

      // Variables
      var attrs, attributes = this.attributes;

      // Signature parsing - taken directly from original Backbone.Model.save 
      // and it states: 'Handle both "key", value and {key: value} -style arguments.'
      if (key == null || typeof key === 'object') {
        attrs = key;
        options = val;
      } else {
        (attrs = {})[key] = val;
      }

      // Validate & wait options - taken directly from original Backbone.Model.save
      options = _.extend({validate: true}, options);
      if (attrs && !options.wait) {
        if (!this.set(attrs, options)) return false;
      } else {
        if (!this._validate(attrs, options)) return false;
      }
      if (attrs && options.wait) {
        this.attributes = _.extend({}, attributes, attrs);
      }

      // Check for "formData" flag and check for if file exist.
      if ( options.formData === true 
           || options.formData !== false 
              && this.attributes[ this.fileAttribute ] 
              && this.attributes[ this.fileAttribute ] instanceof File ) {
        
        // Flatten Attributes reapplying File Object
        var formAttrs = _.clone( this.attributes ),
            fileAttr = this.attributes[ this.fileAttribute ];
        formAttrs = this._flatten( formAttrs );
        formAttrs[ this.fileAttribute ] = fileAttr;

        // Converting Attributes to Form Data
        var formData = new FormData();
        _.each( formAttrs, function( value, key ){
          formData.append( key, value );
        });

        // Set options for AJAX call
        options = options || {};
        options.data = formData;
        options.processData = false;
        options.contentType = false;

        // Apply custom XHR for processing status & listen to "progress"
        var that = this;
        options.xhr = function() {
          var xhr = $.ajaxSettings.xhr();
          xhr.upload.addEventListener('progress', function(){

            that._progressHandler.apply(that, arguments);
          }, false);
          return xhr;
        }    
      }

      // Resume back to original state
      if (attrs && options.wait) this.attributes = attributes;

      // Continue to call the existing "save" method
      return backboneModelClone.save.call(this, attrs, options);
      
    },

    // _ FlattenObject gist by "penguinboy".  Thank You!
    // https://gist.github.com/penguinboy/762197
    _flatten: function( obj ) {
      var output = {};
      for (var i in obj) {
        if (!obj.hasOwnProperty(i)) continue;
        if (typeof obj[i] == 'object') {
          var flatObject = this._flatten(obj[i]);
          for (var x in flatObject) {
            if (!flatObject.hasOwnProperty(x)) continue;
            output[i + '.' + x] = flatObject[x];
          }
        } else {
          output[i] = obj[i];
        }
      }
      return output;

    },
    
    // _ Get the Progress of the uploading file
    _progressHandler: function( event ) {
      if (event.lengthComputable) {
        var percentComplete = event.loaded / event.total;
        this.trigger( 'progress', percentComplete );
      }
    }

  });

}(_, Backbone);
