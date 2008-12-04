/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.api.tools.builder.tests.usage;

import java.util.HashSet;
import java.util.Set;

import junit.framework.Test;

import org.eclipse.core.runtime.IPath;
import org.eclipse.pde.api.tools.builder.tests.ApiProblem;
import org.eclipse.pde.api.tools.internal.problems.ApiProblemFactory;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IElementDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblem;
import org.eclipse.pde.api.tools.model.tests.TestSuiteHelper;

/**
 * Tests that unused {@link org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblemFilter}s
 * are reported properly on full and incremental builds
 */
public class UnusedApiProblemFilterTests extends UsageTest {

	private static final String BEFORE = "before";
	private static final String AFTER = "after";
	
	private IPath fRootPath = super.getTestSourcePath().append("filters");
	
	/**
	 * Constructor
	 * @param name
	 */
	public UnusedApiProblemFilterTests(String name) {
		super(name);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.builder.tests.usage.UsageTest#setBuilderOptions()
	 */
	@Override
	protected void setBuilderOptions() {
		super.setBuilderOptions();
		enableLeakOptions(true);
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.builder.tests.usage.UsageTest#getTestSourcePath()
	 */
	@Override
	protected IPath getTestSourcePath() {
		return fRootPath; 
	}
	
	private IPath getBeforePath(String testname) {
		return fRootPath.append(testname).append(BEFORE);
	}
	
	private IPath getAfterPath(String testname) {
		return fRootPath.append(testname).append(AFTER);
	}
	
	private IPath getFilterFilePath(String testname) {
		return TestSuiteHelper.getPluginDirectoryPath().append(TEST_SOURCE_ROOT).append(fRootPath).append(testname).append(".api_filters");
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.builder.tests.usage.UsageTest#assertProblems(org.eclipse.pde.api.tools.builder.tests.ApiProblem[])
	 */
	@Override
	protected void assertProblems(ApiProblem[] problems) {
		int[] expectedProblemIds = getExpectedProblemIds();
		int length = problems.length;
		if (expectedProblemIds.length != length) {
			for (int i = 0; i < length; i++) {
				System.err.println(problems[i]);
			}
		}
		assertEquals("Wrong number of problems", expectedProblemIds.length, length);
		String[][] args = getExpectedMessageArgs();
		if (args != null) {
			// compare messages
			Set<String> set = new HashSet<String>();
			for (int i = 0; i < length; i++) {
				set.add(problems[i].getMessage());
			}
			for (int i = 0; i < expectedProblemIds.length; i++) {
				String[] messageArgs = args[i];
				int messageId = ApiProblemFactory.getProblemMessageId(expectedProblemIds[i]);
				String message = ApiProblemFactory.getLocalizedMessage(messageId, messageArgs);
				assertTrue("Missing expected problem: " + message, set.remove(message));
			}
		} else {
			// compare id's
			Set<Integer> set = new HashSet<Integer>();
			for (int i = 0; i < length; i++) {
				set.add(new Integer(problems[i].getProblemId()));
			}
			for (int i = 0; i < expectedProblemIds.length; i++) {
				assertTrue("Missing expected problem: " + expectedProblemIds[i], set.remove(new Integer(expectedProblemIds[i])));
			}
		}
	}
	
	/**
	 * @return the test suite for this class
	 */
	public static Test suite() {
		return buildTestSuite(UnusedApiProblemFilterTests.class);
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.builder.tests.ApiBuilderTest#getDefaultProblemId()
	 */
	@Override
	protected int getDefaultProblemId() {
		return ApiProblemFactory.createProblemId(
				IApiProblem.CATEGORY_USAGE, 
				IElementDescriptor.METHOD, 
				IApiProblem.UNUSED_PROBLEM_FILTERS, 
				IApiProblem.NO_FLAGS);
	}
	
	public void testUnusedFilter1F() {
		x1(false);
	}
	
	public void testUnusedFilter1I() {
		x1(true);
	}
	
	/**
	 * Tests that unused filters are correctly reported. This test adds the final modifier
	 * to a class that has a protected method leaking and internal type, with a filter for the problem
	 * @param inc
	 */
	private void x1(boolean inc) {
		String testname = "test1";
		String sourcename = "testUF1";
		setExpectedProblemIds(getDefaultProblemIdSet(1));
		setExpectedMessageArgs(new String[][] {{"testUF1.m1(internal) has non-API parameter type internal"}});
		deployReplacementTest(
				getBeforePath(testname), 
				getAfterPath(testname), 
				getFilterFilePath(testname), 
				sourcename, 
				inc);
	}
	
	public void testUnusedFilter2F() {
		x2(false);
	}
	
	public void testUnusedFilter2I() {
		x2(true);
	}
	
	/**
	 * Tests that there is no problem reported for a compilation unit that has been deleted, which has an api 
	 * problem filter
	 * @param inc
	 */
	private void x2(boolean inc) {
		String testname = "test2";
		String sourcename = "testUF2";
		expectingNoProblems();
		deployReplacementTest(
				getBeforePath(testname), 
				null, 
				getFilterFilePath(testname), 
				sourcename, 
				inc);
	}

	public void testUnusedFilter3F() {
		x3(false);
	}
	
	public void testUnusedFilter3I() {
		x3(true);
	}
	
	/**
	 * Tests that a compilation unit with more than one problem in it works correctly when 
	 * deleting a member that had a filter
	 * @param inc
	 */
	private void x3(boolean inc) {
		String testname = "test3";
		String sourcename = "testUF3";
		setExpectedProblemIds(new int[] {
				getDefaultProblemId(),
				ApiProblemFactory.createProblemId(IApiProblem.CATEGORY_USAGE, 
						IElementDescriptor.METHOD, 
						IApiProblem.API_LEAK, 
						IApiProblem.LEAK_METHOD_PARAMETER)}
		);
		setExpectedMessageArgs(new String[][] {{"testUF3.m2() has non-API return type internal"},
				{"internal", "testUF3", "m1(internal[])"}});
		deployReplacementTest(
				getBeforePath(testname), 
				getAfterPath(testname), 
				getFilterFilePath(testname), 
				sourcename, 
				inc);
	}
}
