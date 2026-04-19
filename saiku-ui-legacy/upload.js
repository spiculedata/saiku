/*  
 *   Copyright 2015 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
 
/**
 * Module License Upload
 */
var upload = (function($, window, document, undefined) {

    // undefined is used here as the undefined global variable in ECMAScript 3 is
    // mutable (ie. it can be changed by someone else). undefined isn't really being
    // passed in so we can ensure the value of it is truly undefined. In ES5, undefined
    // can no longer be modified.

    // window and document are passed through as local variable rather than global
    // as this (slightly) quickens the resolution process and can be more efficiently
    // minified (especially when both are regularly referenced in your plugin).

    'use strict';    

    var module = {
        _baseURL: window.location.origin + '/saiku/rest/saiku/api/license',

        formChange: function() {
            $('#file-chooser').change(function() {
                $('.form-upload p').text(this.files.length + ' file selected');
            });
        },

        _notifyUser: function(alertType, msg) {
            $('#notification').removeClass();        
            $('#notification').addClass(alertType);
            $('#notification').slideDown();
            module._setNotificationMessage(msg);
            setTimeout(function() {
                $('#notification').slideUp();
            }, 3000);
        },

        _setNotificationMessage: function(msg) {
            $('#notification p').text(msg);
        },

        _clearInputFile: function() {
            var inputFile = $('#file-chooser');
            inputFile.replaceWith(inputFile.val('').clone(true));
        },

        _loadingButton: function(isLoadBtn) {
            if (isLoadBtn) {
                $('#btn-sendfile').attr('disabled', 'disabled');
                $('#btn-sendfile').text('Loading file...');
            }
            else {
                $('#btn-sendfile').removeAttr('disabled');
                $('#btn-sendfile').text('Upload');
            }
        },

        _backLogin: function(isShow) {
            if (isShow) {
                $('.back-login').show();
                $('#btn-back-login').on('click', function() {
                    var url = window.location.origin;
                    window.open(url, '_self');
                });
            }
            else {
                $('.back-login').hide();
            }
        },

        _xhr: function(options, callback) {
            $.ajax({
                url: options.url,
                type: options.type,
                data: options.data,
                contentType: 'application/x-java-serialized-object',
                processData: false,
                success: function(data, status, jqXHR) {
                    module._clearInputFile();
                    module._loadingButton(false);
                    module._backLogin(true);
                    callback('alert-success', data);
                },
                error: function(jqXHR, status, errorThrown) {
                    module._clearInputFile();
                    module._loadingButton(false);
                    // module._backLogin(false);
                    callback('alert-danger', 'Error while uploading the file: (' + errorThrown + ')');
                }
            });
        },

        sendFile: function() {
            $('#btn-sendfile').on('click', function() {
                var file = $('#file-chooser')[0].files[0];

                if (file !== undefined) {
                    module._loadingButton(true);
                    module._xhr({type: 'POST', url: module._baseURL, data: file}, module._notifyUser);
                    $('.form-upload p').text('Drag your license or click in this area.');
                }
                else {
                    // module._backLogin(false);
                    module._notifyUser('alert-danger', 'Oops... Select a file!');
                }
            });
        },

        init: function() {
            module.formChange();
            module.sendFile();
        }
    };

    return {
        init: module.init
    }

}(jQuery, window, document));