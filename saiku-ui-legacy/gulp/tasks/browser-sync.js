/**
 *   Copyright 2017 OSBI Ltd
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

'use strict';

// Necessary Plugins
var gulp        = require('gulp');
var url         = require('url');
var proxy       = require('proxy-middleware');
var browserSync = require('browser-sync');
var paths       = require('../paths');

// Serve files from /www/
module.exports = gulp.task('browser-sync', function() {
  var files = [
    paths.browserSync.html,
    paths.browserSync.js,
    paths.browserSync.cssBase,
    paths.browserSync.cssSaiku,
    paths.browserSync.img,
  ];
  var proxyUrl = paths.nodeProxy.protocol + '://' + paths.nodeProxy.hostname +
    ':' + paths.nodeProxy.port;

  browserSync.init(files, {
    server: {
      baseDir: paths.browserSync.html,
      middleware: proxy(url.parse(proxyUrl))
    }
  });
});
