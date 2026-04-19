/*
 * Content-Type:text/javascript
 *
 * A bridge between iPad and iPhone touch events and jquery draggable,
 * sortable etc. mouse interactions.
 * @author Oleg Slobodskoi
 *
 * modified by John Hardy to use with any touch device
 * fixed breakage caused by jquery.ui so that mouseHandled internal flag is reset
 * before each touchStart event
 *
 * modified by Nik Graf to trigger click event inside widgets in case they haven't be moved
 *
 */
(function( $ ) {
    $.support.touch = typeof Touch === 'object';
    if (!$.support.touch) {
        return;
    }

    var proto =  $.ui.mouse.prototype,
    _mouseInit = proto._mouseInit;

 	var startX;
 	var dx = 0;


    $.extend( proto, {
        _mouseInit: function() {
 			if ($(this.element).hasClass("ui-dialog") || (!$(this.element).hasClass('ui-draggable') && !$(this.element).hasClass('ui-sortable'))) {
 				return false;
 			}

            this.element
            .bind( "touchstart." + this.widgetName, $.proxy( this, "_touchStart" ) );
            _mouseInit.apply( this, arguments );
        },

        _touchStart: function( event ) {
            if ( event.originalEvent.targetTouches.length != 1 ) {
                return false;
            }
 			
 			
 			startX = event.originalEvent.pageX;
            // reset touchMoved to provide a clean state after touch start
            this._touchMoved = false;
 			dx = 0;

            this.element
            .bind( "touchmove." + this.widgetName, $.proxy( this, "_touchMove" ) )
            .bind( "touchend." + this.widgetName, $.proxy( this, "_touchEnd" ) );

            this._modifyEvent( event );

            //$( document ).trigger($.Event("mouseup")); //reset mouseHandled flag in ui.mouse
            this._mouseDown( event );
 			if (this._mouseOn) {
 				this._mouseOn( event );
 			}
            return false;
        },

        _touchMove: function( event ) {
 			if ($(event.target).hasClass('ui-draggable') || $(event.target).parent().hasClass('ui-draggable') || !$(event.target).parents("ui-dialog").length > 0) {
	            this._touchMoved = true;
	            this._modifyEvent( event );
	            this._mouseMove( event );
			} else {
				$(event.target).click();
				$( document ).trigger($.Event("mouseup")); //reset mouseHandled flag in ui.mouse
			}

        },

        _touchEnd: function( event ) {
            this.element
            .unbind( "touchmove." + this.widgetName )
            .unbind( "touchend." + this.widgetName );
 			//console.log("Distance: " + dx);
 			
 			
            if (Math.abs(dx) < 10 || !this._touchMoved) {
 				//console.log("CLICK!");
                this._touchMoved = false;
                // since $(event.target).click() does not work properly on ios we need to call the click event in this way
                // see http://stackoverflow.com/questions/3543733/jquery-trigger-click-mobile-safari-ipad
 //               var clickEvent = document.createEvent("MouseEvents");
//				clickEvent.initMouseEvent("click", true, true, window, 0, 0, 0, 0, 0, false, false, false, false, 0, null);
				$(event.target).click();
				//console.log(clickEvent);
 //               $(event.currentTarget)[0].dispatchEvent(clickEvent);
            } else {
 				event.preventDefault() ;
 			}
  			startX = null;
 			dx = 0;
            this._mouseUp( event );
 			$( document ).trigger($.Event("mouseup")); //reset mouseHandled flag in ui.mouse

        },

        _modifyEvent: function( event ) {
            event.which = 1;
            var target = event.originalEvent.targetTouches[0];
            event.pageX = target.clientX;
            event.pageY = target.clientY;
 			dx = target.clientX - startX;
        }

    });

})( jQuery );