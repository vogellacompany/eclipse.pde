/*******************************************************************************
 * Copyright (c) 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.ui.tests.imports;

import junit.framework.*;

public class AllImportTests {
	
	public static Test suite() {
		TestSuite suite = new TestSuite("Test Suite to test the plug-in import wizard."); //$NON-NLS-1$
		suite.addTest(ImportWithLinksTestCase.suite());
		suite.addTest(ImportAsBinaryTestCase.suite());
		return suite;
	}

}
