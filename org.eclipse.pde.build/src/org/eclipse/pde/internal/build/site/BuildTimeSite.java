/**********************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved.   This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors: 
 * IBM - Initial API and implementation
 **********************************************************************/
package org.eclipse.pde.internal.build.site;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.service.resolver.*;
import org.eclipse.pde.internal.build.*;
import org.eclipse.update.core.*;

/**
 * This site represent a site at build time. A build time site is made of code
 * to compile, and a potential installation of eclipse (or derived products)
 * against which the code must be compiled. Moreover this site provide access to
 * a pluginRegistry.
 */
public class BuildTimeSite extends Site implements ISite, IPDEBuildConstants, IXMLConstants {
	private PDEState state;

	public PDEState getRegistry() throws CoreException {
		if (state == null) {
			// create the registry according to the site where the code to
			// compile is, and a existing installation of eclipse
			BuildTimeSiteContentProvider contentProvider = (BuildTimeSiteContentProvider) getSiteContentProvider();

			if (AbstractScriptGenerator.isBuildingOSGi())
				state = new PDEState();
			else
				state = new PluginRegistryConverter();

			state.addBundles(contentProvider.getPluginPaths());

			state.resolveState();
			BundleDescription[] allBundles = state.getState().getBundles();
			BundleDescription[] resolvedBundles = state.getState().getResolvedBundles();
			if (allBundles.length == resolvedBundles.length)
				return state;

			MultiStatus errors = new MultiStatus(IPDEBuildConstants.PI_PDEBUILD, 1, Policy.bind("exception.registryResolution"), null); //$NON-NLS-1$
			BundleDescription[] all = state.getState().getBundles();
			StateHelper helper = Platform.getPlatformAdmin().getStateHelper();
			for (int i = 0; i < all.length; i++) {
				if (!all[i].isResolved()) {
					VersionConstraint[] unsatisfiedConstraints = helper.getUnsatisfiedConstraints(all[i]);
					for (int j = 0; j < unsatisfiedConstraints.length; j++) {
						String message = getResolutionFailureMessage(unsatisfiedConstraints[j]);
						errors.add(new Status(IStatus.WARNING, all[i].getSymbolicName(), IStatus.WARNING, message, null));
					}
				}
			}
			BundleHelper.getDefault().getLog().log(errors);
		}
		if (!state.getState().isResolved())
			state.state.resolve(true);
		return state;
	}

	public String getResolutionFailureMessage(VersionConstraint unsatisfied) {
		if (unsatisfied.isResolved())
			throw new IllegalArgumentException();
		if (unsatisfied instanceof PackageSpecification)
			return Policy.bind("unsatisfied.import", displayVersionConstraint(unsatisfied));//$NON-NLS-1$
		if (unsatisfied instanceof BundleSpecification) {
			if (((BundleSpecification) unsatisfied).isOptional())
				return Policy.bind("unsatisfied.optionalBundle", displayVersionConstraint(unsatisfied));//$NON-NLS-1$
			return Policy.bind("unsatisfied.required", displayVersionConstraint(unsatisfied));//$NON-NLS-1$
		}
		return Policy.bind("unsatisfied.host", displayVersionConstraint(unsatisfied));//$NON-NLS-1$
	}

	private String displayVersionConstraint(VersionConstraint constraint) {
		Version versionSpec = constraint.getVersionSpecification();
		if (versionSpec == null)
			return constraint.getName();
		return constraint.getName() + '_' + versionSpec;
	}

	public IFeature findFeature(String featureId) throws CoreException {
		ISiteFeatureReference[] features = getFeatureReferences();
		for (int i = 0; i < features.length; i++) {
			if (features[i].getVersionedIdentifier().getIdentifier().equals(featureId))
				return features[i].getFeature(null);
		}
		return null;
	}

	public void addFeatureReferenceModel(File featureXML) {
		URL featureURL;
		SiteFeatureReferenceModel featureRef;
		if (featureXML.exists()) {
			// Here we could not use toURL() on currentFeatureDir, because the
			// URL has a slash after the colons (file:/c:/foo) whereas the
			// plugins don't
			// have it (file:d:/eclipse/plugins) and this causes problems later
			// to compare URLs... and compute relative paths
			try {
				featureURL = new URL("file:" + featureXML.getAbsolutePath() + '/'); //$NON-NLS-1$
				featureRef = new SiteFeatureReference();
				featureRef.setSiteModel(this);
				featureRef.setURLString(featureURL.toExternalForm());
				featureRef.setType(BuildTimeFeatureFactory.BUILDTIME_FEATURE_FACTORY_ID);
				addFeatureReferenceModel(featureRef);
			} catch (MalformedURLException e) {
				BundleHelper.getDefault().getLog().log(new Status(IStatus.WARNING, PI_PDEBUILD, WARNING_MISSING_SOURCE, Policy.bind("warning.cannotLocateSource", featureXML.getAbsolutePath()), e)); //$NON-NLS-1$
			}
		}
	}
}