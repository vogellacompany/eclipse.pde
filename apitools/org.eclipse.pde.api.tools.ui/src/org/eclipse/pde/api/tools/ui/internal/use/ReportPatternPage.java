/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.api.tools.ui.internal.use;

import java.util.regex.PatternSyntaxException;

import org.eclipse.pde.api.tools.ui.internal.IApiToolsConstants;
import org.eclipse.pde.api.tools.ui.internal.IApiToolsHelpContextIds;
import org.eclipse.pde.api.tools.ui.internal.SWTFactory;
import org.eclipse.pde.api.tools.ui.internal.use.ApiUsePatternTab.Pattern;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

/**
 *
 */
public class ReportPatternPage extends UsePatternPage {

	static final String PAGE_NAME = "report"; //$NON-NLS-1$
	
	private Text patterntext = null;
	
	String pattern = null;
	
	/**
	 * Constructor
	 * @param pattern
	 */
	public ReportPatternPage(String pattern) {
		super(PAGE_NAME, Messages.ReportPatternPage_report_conversion_pattern, null);
		this.pattern = pattern;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.ui.internal.use.UsePatternPage#getKind()
	 */
	public int getKind() {
		return Pattern.REPORT;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.ui.internal.use.UsePatternPage#getPattern()
	 */
	public String getPattern() {
		return this.patterntext.getText().trim();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.dialogs.IDialogPage#createControl(org.eclipse.swt.widgets.Composite)
	 */
	public void createControl(Composite parent) {
		Composite comp = SWTFactory.createComposite(parent, 1, 1, GridData.FILL_HORIZONTAL);
		SWTFactory.createLabel(comp, Messages.ReportPatternPage_pattern, 1);
		this.patterntext = SWTFactory.createSingleText(comp, 1);
		if(this.pattern != null) {
			this.patterntext.setText(this.pattern);
		}
		this.patterntext.addModifyListener(new ModifyListener() {
			public void modifyText(ModifyEvent e) {
				setDirty();
				setPageComplete(isPageComplete());
			}
		});
		this.patterntext.selectAll();
		this.patterntext.setFocus();
		setControl(comp);
		PlatformUI.getWorkbench().getHelpSystem().setHelp(getControl(), IApiToolsHelpContextIds.APITOOLS_REPORT_PATTERN_WIZARD_PAGE);
	}
	/* (non-Javadoc)
	 * @see org.eclipse.jface.wizard.WizardPage#isPageComplete()
	 */
	public boolean isPageComplete() {
		String newtext = this.patterntext.getText().trim();
		if(IApiToolsConstants.EMPTY_STRING.equals(newtext)) {
			if(pageDirty()) {
				setErrorMessage(Messages.ReportPatternPage_enter_conversion_pattern);
			}
			else {
				setMessage(Messages.ReportPatternPage_enter_conversion_pattern);
			}
			return false;
		}
		try {
			java.util.regex.Pattern.compile(newtext);
		}
		catch(PatternSyntaxException pse) {
			setErrorMessage(pse.getDescription());
			return false;
		}
		resetMessage(this.pattern != null);
		return true;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.ui.internal.use.UsePatternPage#resetMessage(boolean)
	 */
	protected void resetMessage(boolean isediting) {
		setErrorMessage(null);
		if(isediting) {
			setMessage(Messages.ReportPatternPage_edit_conversion_pattern);
		}
		else {
			setMessage(Messages.ReportPatternPage_create_conversion_pattern);
		}
	}
}
