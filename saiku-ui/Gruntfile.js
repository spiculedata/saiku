module.exports = function(grunt) {
    'use strict';

    var paths = {
        css: './**/*.css',
        html: './**/*.html',
        js: './**/*.js'
    };

    grunt.initConfig({        
        // Watch definitions
        watch: {
            reload: {
                files: [paths.html, paths.css, paths.js],
                options: {
                    livereload: {
                        port: 35729
                    }
                }
            }
        }     
    });

    grunt.loadNpmTasks('grunt-contrib-watch');
    grunt.registerTask('default', ['watch']);
};
