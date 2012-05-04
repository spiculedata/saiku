package org.saiku.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import mondrian.olap.MondrianProperties;

import org.pentaho.platform.api.engine.IPluginLifecycleListener;
import org.pentaho.platform.api.engine.PluginLifecycleException;
import org.pentaho.platform.api.repository.ISolutionRepository;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaikuPluginLifecycleListener implements IPluginLifecycleListener {

	private static final Logger log = LoggerFactory.getLogger(SaikuPluginLifecycleListener.class);

	
	public void init() throws PluginLifecycleException {
		String mondrianPropsFilename = "system" + File.separator + "mondrian" + File.separator + "mondrian.properties"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	    InputStream is = null;
	    try {
	    	
	      ISolutionRepository repository = PentahoSystem.get(ISolutionRepository.class, PentahoSessionHolder.getSession());
	      if (repository.resourceExists(mondrianPropsFilename, ISolutionRepository.ACTION_EXECUTE)) {
	        is = repository.getResourceInputStream(mondrianPropsFilename, false, ISolutionRepository.ACTION_EXECUTE);
	        MondrianProperties.instance().load(is);
	        log.debug("Loaded mondrian properties file: " + mondrianPropsFilename);
	      } else {
	    	  log.debug("Cannot find mondrian properties file: "+  mondrianPropsFilename);
	      }
	    } catch (IOException ioe) {
	    	log.error("Loaded mondrian properties file failed: " + mondrianPropsFilename,ioe); 
	    } finally {
	      try {
	        if (is != null) {
	          is.close();
	        }
	      } catch (IOException e) {
	        // ignore
	      }
	    }
		
	}

	public void loaded() throws PluginLifecycleException {
		// TODO Auto-generated method stub
		
	}

	public void unLoaded() throws PluginLifecycleException {
		// TODO Auto-generated method stub
		
	}

}
