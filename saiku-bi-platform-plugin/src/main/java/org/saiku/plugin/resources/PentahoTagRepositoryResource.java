package org.saiku.plugin.resources;

import java.io.File;

import org.pentaho.platform.api.engine.IPluginManager;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.plugin.services.pluginmgr.PluginClassLoader;
import org.saiku.plugin.util.PluginConfig;
import org.saiku.web.rest.resources.BasicTagRepositoryResource;

public class PentahoTagRepositoryResource extends BasicTagRepositoryResource {
	
	@Override
	public void setPath(String path) throws Exception {
		final IPluginManager pluginManager = (IPluginManager) PentahoSystem.get(IPluginManager.class, PentahoSessionHolder.getSession());
		final PluginClassLoader pluginClassloader = (PluginClassLoader)pluginManager.getClassLoader(PluginConfig.PLUGIN_NAME);
		File pluginDir = pluginClassloader.getPluginDir();
		String absolute = "file:" +pluginDir.getAbsolutePath();
		if (!absolute.endsWith("" + File.separatorChar)) {
			 absolute += File.separatorChar;
		}
		absolute += path;
		System.out.println("Using tag repository path: " + absolute);
		super.setPath(absolute);
	}

}
