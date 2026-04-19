/*  
 *   Copyright 2012 OSBI Ltd
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
 * Initialize logger
 */
var logger = function(config) { 
    this.url = config.url;
    this.log = function(data) {
        janky({
            url: this.url,
            method: "post",
            data: data,
            error: function() {
                console.log("Could not reach telemetry server");
            }
        });
    };
};

if (Settings.ERROR_LOGGING) {
    window.Log = new logger({
        url: Settings.TELEMETRY_SERVER + '/input/errors'
    });
    
    /**
     * Log errors
     */
     
    window.defaultHandler = window.onerror;
    window.onerror = function(errorMsg, url, lineNumber) {
        if (lineNumber !== 0) {
            Log.log({
            		browser: navigator.userAgent,
                    browserName: navigator.appCodeName,
                    browserVersion: navigator.appVersion,
            		message: errorMsg,
            		file: url,
            	    lineNumber: lineNumber,
            	    timestamp: new Date(),
                    version: Settings.VERSION,
                    biplugin: Settings.BIPLUGIN
            });

            console.error({
                    message: errorMsg,
                    file: url,
                    lineNumber: lineNumber,
                    timestamp: new Date()
            }); 
            
            if (defaultHandler) {
                return defaultHandler(errorMsg, url, lineNumber);
            }

        }
        
        
        
    };
    
}