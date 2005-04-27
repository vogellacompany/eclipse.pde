/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM - Initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.internal.build;

import java.io.*;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.State;
import org.eclipse.osgi.util.NLS;
import org.eclipse.pde.internal.build.ant.AntScript;
import org.eclipse.pde.internal.build.site.BuildTimeSite;
import org.eclipse.pde.internal.build.site.BuildTimeSiteFactory;
import org.eclipse.update.core.SiteManager;

/**
 * Generic super-class for all script generator classes. 
 * It contains basic informations like the script, the configurations, and a location 
 */
public abstract class AbstractScriptGenerator implements IXMLConstants, IPDEBuildConstants, IBuildPropertiesConstants {
	protected static boolean embeddedSource = false;
	protected static boolean forceUpdateJarFormat = false;
	private static List configInfos;
	protected static String workingDirectory;
	protected static boolean buildingOSGi = true;
	protected AntScript script;
	
	private static PDEUIStateWrapper pdeUIState;
	
	/** Location of the plug-ins and fragments. */
	protected String[] pluginPath;
	protected BuildTimeSiteFactory siteFactory;
	
	protected boolean reportResolutionErrors;

	static {
		// By default, a generic configuration is set
		configInfos = new ArrayList(1);
		configInfos.add(Config.genericConfig());
	}

	public static List getConfigInfos() {
		return configInfos;
	}

	/**
	 * Starting point for script generation. See subclass implementations for
	 * individual comments.
	 * 
	 * @throws CoreException
	 */
	public abstract void generate() throws CoreException;

	/**
	 * Return a string with the given property name in the format:
	 * <pre>${propertyName}</pre>.
	 * 
	 * @param propertyName the name of the property
	 * @return String
	 */
	public String getPropertyFormat(String propertyName) {
		StringBuffer sb = new StringBuffer();
		sb.append(PROPERTY_ASSIGNMENT_PREFIX);
		sb.append(propertyName);
		sb.append(PROPERTY_ASSIGNMENT_SUFFIX);
		return sb.toString();
	}

	public static void setConfigInfo(String spec) throws CoreException {
		configInfos.clear();
		String[] configs = Utils.getArrayFromStringWithBlank(spec, "&"); //$NON-NLS-1$
		configInfos = new ArrayList(configs.length);
		String[] os = new String[configs.length];
		String[] ws = new String[configs.length];
		String[] archs = new String[configs.length];
		for (int i = 0; i < configs.length; i++) {
			String[] configElements = Utils.getArrayFromStringWithBlank(configs[i], ","); //$NON-NLS-1$
			if (configElements.length != 3) {
				IStatus error = new Status(IStatus.ERROR, IPDEBuildConstants.PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_CONFIG_FORMAT, NLS.bind(Messages.error_configWrongFormat, configs[i]), null);
				throw new CoreException(error);
			}
			Config aConfig = new Config(configs[i]); //$NON-NLS-1$
			if (aConfig.equals(Config.genericConfig()))
				configInfos.add(Config.genericConfig());
			else
				configInfos.add(aConfig);

			// create a list of all ws, os and arch to feed the SiteManager
			os[i] = aConfig.getOs();
			ws[i] = aConfig.getWs();
			archs[i] = aConfig.getArch();
		}
		SiteManager.setOS(Utils.getStringFromArray(os, ",")); //$NON-NLS-1$
		SiteManager.setWS(Utils.getStringFromArray(ws, ",")); //$NON-NLS-1$
		SiteManager.setOSArch(Utils.getStringFromArray(archs, ",")); //$NON-NLS-1$
	}

	public void setWorkingDirectory(String location) {
		workingDirectory = location;
	}

	/**
	 * Return the file system location for the given plug-in model object.
	 * 
	 * @param model the plug-in
	 * @return String
	 */
	public String getLocation(BundleDescription model) {
		return model.getLocation();
	}

	static public class MissingProperties extends Properties {
		private static final long serialVersionUID = 3546924667060303927L;
		private static MissingProperties singleton;

		private MissingProperties() {
			//nothing to do;
		}

		public synchronized Object setProperty(String key, String value) {
			throw new UnsupportedOperationException();
		}

		public synchronized Object put(Object key, Object value) {
			throw new UnsupportedOperationException();
		}

		public static MissingProperties getInstance() {
			if (singleton == null)
				singleton = new MissingProperties();
			return singleton;
		}
	}

	public static Properties readProperties(String location, String fileName, int errorLevel) throws CoreException {
		Properties result = new Properties();
		File file = new File(location, fileName);
		try {
			InputStream input = new BufferedInputStream(new FileInputStream(file));
			try {
				result.load(input);
			} finally {
				input.close();
			}
		} catch (FileNotFoundException e) {
			if (errorLevel != IStatus.INFO && errorLevel != IStatus.OK) {
				String message = NLS.bind(Messages.exception_missingFile, file);
				BundleHelper.getDefault().getLog().log(new Status(errorLevel, PI_PDEBUILD, EXCEPTION_READING_FILE, message, null));
			}
			result = MissingProperties.getInstance();
		} catch (IOException e) {
			String message = NLS.bind(Messages.exception_readingFile, file);
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_READING_FILE, message, e));
		}
		return result;
	}

	public void openScript(String scriptLocation, String scriptName) throws CoreException {
		if (script != null)
			return;

		try {
			OutputStream scriptStream = new BufferedOutputStream(new FileOutputStream(scriptLocation + '/' + scriptName)); //$NON-NLS-1$
			try {
				script = new AntScript(scriptStream);
			} catch (IOException e) {
				try {
					scriptStream.close();
					String message = NLS.bind(Messages.exception_writingFile, scriptLocation + '/' + scriptName);
					throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
				} catch (IOException e1) {
					// Ignored		
				}
			}
		} catch (FileNotFoundException e) {
			String message = NLS.bind(Messages.exception_writingFile, scriptLocation + '/' + scriptName);
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
		}
	}

	public void closeScript() {
		script.close();
	}

	public void setBuildingOSGi(boolean b) {
		buildingOSGi = b;
	}

	public static boolean isBuildingOSGi() {
		return buildingOSGi;
	}

	public static String getWorkingDirectory() {
		return workingDirectory;
	}

	public static String getDefaultOutputFormat() {
		return "zip"; //$NON-NLS-1$
	}

	public static boolean getDefaultEmbeddedSource() {
		return false;
	}

	public static void setEmbeddedSource(boolean embed) {
		embeddedSource = embed;
	}

	public static boolean getForceUpdateJarFormat() {
		return false;
	}

	public static void setForceUpdateJar(boolean force) {
		forceUpdateJarFormat = force;
	}

	public static String getDefaultConfigInfos() {
		return "*, *, *"; //$NON-NLS-1$
	}

	public static boolean getDefaultBuildingOSGi() {
		return true;
	}

	/**
	 * Return a build time site referencing things to be built.   
	 * @param refresh : indicate if a refresh must be performed. Although this flag is set to true, a new site is not rebuild if the urls of the site did not changed 
	 * @return BuildTimeSite
	 * @throws CoreException
	 */
	public BuildTimeSite getSite(boolean refresh) throws CoreException {
		if (siteFactory != null && refresh == false)
			return (BuildTimeSite) siteFactory.createSite();
	
		if (siteFactory == null || refresh == true) {
			siteFactory = new BuildTimeSiteFactory();
			siteFactory.setReportResolutionErrors(reportResolutionErrors);
		}
	
		siteFactory.setSitePaths(getPaths());
		siteFactory.setInitialState(pdeUIState);
		return (BuildTimeSite) siteFactory.createSite();
	}

	/**
	 * Method getPaths. 
	 * @return URL[]
	 */
	private String[] getPaths() {
		if (pluginPath != null)
			return pluginPath;
	
		return new String[] {workingDirectory};
	}

	public void setBuildSiteFactory(BuildTimeSiteFactory siteFactory) {
		this.siteFactory = siteFactory;
	}
	
	/**
	 * Return the path of the plugins		//TODO Do we need to add support for features, or do we simply consider one list of URL? It is just a matter of style/
	 * @return URL[]
	 */
	public String[] getPluginPath() {
		return pluginPath;
	}

	/**
	 * Sets the pluginPath.
	 * 
	 * @param path
	 */
	public void setPluginPath(String[] path) {
		pluginPath = path;
	}
	
	public void setPDEState(State  state) {
		ensurePDEUIStateNotNull();
		pdeUIState.setState(state);
	}

	public void setStateExtraData(HashMap p) {
		ensurePDEUIStateNotNull();
		pdeUIState.setExtraData(p);
	}
	
	protected void flushState() {
		ensurePDEUIStateNotNull();
		pdeUIState = null;
	}
	
	private void ensurePDEUIStateNotNull() {
		if (pdeUIState == null)
			pdeUIState = new PDEUIStateWrapper();
	}
}
