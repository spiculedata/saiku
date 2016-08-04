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
package org.saiku.plugin;

import java.io.InputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.engine.IFileInfo;
import org.pentaho.platform.api.engine.ISolutionFile;
import org.pentaho.platform.api.engine.SolutionFileMetaAdapter;
import org.pentaho.platform.engine.core.solution.FileInfo;
import org.saiku.plugin.util.PluginConfig;

/**
 * Retrieve content metadata from the .saiku content file.
 * 
 * @author Paul Stoellberger
 */
class SaikuContentTypeMetaProvider extends SolutionFileMetaAdapter {

    private static final Log LOG = LogFactory.getLog(SaikuContentTypeMetaProvider.class);

    private SaikuContentTypeMetaProvider() {}

    public IFileInfo getFileInfo(ISolutionFile solutionFile, InputStream in) {
        try {
            String title = solutionFile.getFileName();
            if (title != null && title.endsWith(".saiku")) {
            	title = title.substring(0,title.indexOf(".saiku"));
            }

            IFileInfo info = new FileInfo();
            info.setAuthor("");
            info.setDescription("");
            info.setIcon(PluginConfig.ICON);
            info.setTitle(title);
            return info;

        } catch (Exception e) {
            LOG.error(getClass().toString(), e);
        }
        return null;
    }
}
