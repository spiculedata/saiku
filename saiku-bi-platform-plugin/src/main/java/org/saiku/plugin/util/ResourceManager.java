/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.saiku.plugin.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.pentaho.platform.api.engine.IPluginResourceLoader;
import org.pentaho.platform.engine.core.system.PentahoSystem;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;

public class ResourceManager {


  public static ResourceManager instance;

  public static final String PLUGIN_DIR = PentahoSystem.getApplicationContext().getSolutionPath("system/saiku/");
  public static final String SOLUTION_DIR = PentahoSystem.getApplicationContext().getSolutionPath("saiku/");
  
  private static final HashSet<String> CACHEABLE_EXTENSIONS = new HashSet<String>();
  private static final HashMap<String, String> cacheContainer = new HashMap<String, String>();

  private boolean isCacheEnabled = true;

  public ResourceManager() {

    CACHEABLE_EXTENSIONS.add("html");

    final IPluginResourceLoader resLoader = PentahoSystem.get(IPluginResourceLoader.class, null);
    this.isCacheEnabled = Boolean.parseBoolean(resLoader.getPluginSetting(this.getClass(), "saiku/enable-cache"));

  }


  public static ResourceManager getInstance() {

    if (instance == null) {
      instance = new ResourceManager();
    }

    return instance;
  }

  public String getResourceAsString(final String path, final HashMap<String, String> tokens) throws IOException {

    final String extension = getResourceExtension(path);
    final String cacheKey = buildCacheKey(path, tokens);

    // If it's cachable and we have it, return it.
    if (isCacheEnabled && CACHEABLE_EXTENSIONS.contains(extension) && cacheContainer.containsKey(cacheKey)) {
      // return from cache. Make sure we return a clone of the original object
      return cacheContainer.get(cacheKey);
    }

    // Read file
    File file = new File(PLUGIN_DIR + path);
    // if not under plugin dir, try saiku solution dir
    if(!file.exists())
    {
      file = new File(SOLUTION_DIR + path);
    }
    
    String resourceContents = FileUtils.readFileToString(file);

    if (tokens != null) {
      for (final String key : tokens.keySet()) 
      {
        resourceContents = StringUtils.replace(resourceContents, key, tokens.get(key));
      }
    }

    // We have the resource. Should we cache it?
    if (isCacheEnabled && CACHEABLE_EXTENSIONS.contains(extension)) {
      cacheContainer.put(cacheKey, resourceContents);
    }

    return resourceContents;
  }


  public String getResourceAsString(final String path) throws IOException 
  {  
    return getResourceAsString(path, null);
  }


  private String buildCacheKey(final String path, final HashMap<String, String> tokens) {

    final StringBuilder keyBuilder = new StringBuilder(path);

    if (tokens != null) {
      for (final String key : tokens.keySet()) {
        keyBuilder.append(key.hashCode());
        keyBuilder.append(tokens.get(key).hashCode());
      }
    }

    return keyBuilder.toString();
  }


  private String getResourceExtension(final String path) {

    return path.substring(path.lastIndexOf('.') + 1);

  }

  public void cleanCache() {

    cacheContainer.clear();
  }

}
