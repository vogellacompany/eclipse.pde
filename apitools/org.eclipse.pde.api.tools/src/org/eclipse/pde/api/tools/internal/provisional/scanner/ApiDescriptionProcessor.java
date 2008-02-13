/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.api.tools.internal.provisional.scanner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.pde.api.tools.internal.ApiDescription;
import org.eclipse.pde.api.tools.internal.ApiProblemFilter;
import org.eclipse.pde.api.tools.internal.ApiSettingsXmlVisitor;
import org.eclipse.pde.api.tools.internal.JavadocTagManager;
import org.eclipse.pde.api.tools.internal.provisional.ApiDescriptionVisitor;
import org.eclipse.pde.api.tools.internal.provisional.ApiPlugin;
import org.eclipse.pde.api.tools.internal.provisional.Factory;
import org.eclipse.pde.api.tools.internal.provisional.IApiAnnotations;
import org.eclipse.pde.api.tools.internal.provisional.IApiDescription;
import org.eclipse.pde.api.tools.internal.provisional.IApiFilterStore;
import org.eclipse.pde.api.tools.internal.provisional.IApiJavadocTag;
import org.eclipse.pde.api.tools.internal.provisional.IApiProblemFilter;
import org.eclipse.pde.api.tools.internal.provisional.RestrictionModifiers;
import org.eclipse.pde.api.tools.internal.provisional.VisibilityModifiers;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IElementDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IFieldDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IMethodDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IPackageDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IReferenceTypeDescriptor;
import org.eclipse.pde.api.tools.internal.util.Util;
import org.eclipse.text.edits.TextEdit;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Provides tools for scanning/loading/parsing component.xml files.
 * 
 * @since 1.0.0
 */
public class ApiDescriptionProcessor {

	/**
	 * Visits each type, collecting all members before processing the type.
	 */
	static class DescriptionVisitor extends ApiDescriptionVisitor {
		
		/**
		 * The API description associated with the project. 
		 */
		private IApiDescription apiDescription = null;
		
		/**
		 * Java project to resolve types in
		 */
		private IJavaProject project = null;
		
		/**
		 * List to collect text edits
		 */
		private Map fCollector = null;
		
		/**
		 * Members collected from current type.
		 */
		private List members = new ArrayList();
		
		/**
		 * List of exception statuses that occurred, or <code>null</code> if none.
		 */
		private List exceptions = null;
		
		/**
		 * Constructs a new visitor to collect tag updates in a java project.
		 * 
		 * @param jp project to update
		 * @param cd project's API description
		 * @param collector collection to place text edits into
		 */
		DescriptionVisitor(IJavaProject jp, IApiDescription cd, Map collector) {
			project = jp;
			apiDescription = cd;
			fCollector = collector;
		}
		
		/* (non-Javadoc)
		 * @see org.eclipse.pde.api.tools.model.component.ApiDescriptionVisitor#visitElement(org.eclipse.pde.api.tools.model.component.IElementDescriptor, java.lang.String, org.eclipse.pde.api.tools.model.IApiAnnotations)
		 */
		public boolean visitElement(IElementDescriptor element, String componentContext, IApiAnnotations description) {
			switch(element.getElementType()) {
				case IElementDescriptor.T_PACKAGE: {
					return true;
				}
				case IElementDescriptor.T_REFERENCE_TYPE: {
					members.clear(); 
					members.add(element);
					return true;
				}
				default: {
					members.add(element);
				}
			}
			return false;
		}

		/* (non-Javadoc)
		 * @see org.eclipse.pde.api.tools.model.component.ApiDescriptionVisitor#endVisitElement(org.eclipse.pde.api.tools.model.component.IElementDescriptor, java.lang.String, org.eclipse.pde.api.tools.model.IApiAnnotations)
		 */
		public void endVisitElement(IElementDescriptor element, String componentContext, IApiAnnotations description) {
			if (element.getElementType() == IElementDescriptor.T_REFERENCE_TYPE) {
				IReferenceTypeDescriptor refType = (IReferenceTypeDescriptor) element;
				try {
					IReferenceTypeDescriptor topLevelType = refType.getEnclosingType();
					while (topLevelType != null) {
						refType = topLevelType;
						topLevelType = refType.getEnclosingType();
					}
					IType type = project.findType(refType.getQualifiedName(), new NullProgressMonitor());
					if(type != null) {
						processTagUpdates(type, refType, apiDescription, members, fCollector);
					}
				} catch (CoreException e) {
					addStatus(e.getStatus());
				} catch (BadLocationException e) {
					addStatus(new Status(IStatus.ERROR, ApiPlugin.PLUGIN_ID, 
							ScannerMessages.ComponentXMLScanner_0 + element.toString(),e));
				}
				members.clear();
			}
		}
		
		/**
		 * Adds a status to the current listing of messages
		 * @param status
		 */
		private void addStatus(IStatus status) {
			if (exceptions == null) {
				exceptions = new ArrayList();
			}
			exceptions.add(status);
		}
		
		/**
		 * Returns the status of processing the project. Status is OK
		 * if no errors occurred.
		 * 
		 * @return status
		 */
		public IStatus getStatus() {
			if (exceptions == null) {
				return Status.OK_STATUS;
			}
			return new MultiStatus(ApiPlugin.PLUGIN_ID, 0, 
					(IStatus[]) exceptions.toArray(new IStatus[exceptions.size()]),
					ScannerMessages.ComponentXMLScanner_1, null);
		}
		
	}
	
	/**
	 * Visitor used for finding the nodes to update the javadoc tags for, if needed
	 */
	static class ASTTagVisitor extends ASTVisitor {
		private List apis = null;
		private IApiDescription description = null;
		private ASTRewrite rewrite = null;
		private Stack typeStack;
		/**
		 * Constructor
		 * @param APIs a listing of {@link IElementDescriptor}s that we care about for this visit
		 */
		public ASTTagVisitor(List apis, IApiDescription description, ASTRewrite rewrite) {
			this.apis = apis;
			this.description = description;
			this.rewrite = rewrite;
			typeStack = new Stack();
		}

		/* (non-Javadoc)
		 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.TypeDeclaration)
		 */
		public boolean visit(TypeDeclaration node) {
			int type = IApiJavadocTag.TYPE_CLASS;
			if (node.isInterface()) {
				type = IApiJavadocTag.TYPE_INTERFACE;
			}
			typeStack.push(new Integer(type));
			updateDocNode(findDescriptorByName(node.getName().getFullyQualifiedName(), null), node, getType(), IApiJavadocTag.MEMBER_NONE);
			return true;
		}
		
		/* (non-Javadoc)
		 * @see org.eclipse.jdt.core.dom.ASTVisitor#endVisit(org.eclipse.jdt.core.dom.TypeDeclaration)
		 */
		public void endVisit(TypeDeclaration node) {
			typeStack.pop();
		}
		
		/**
		 * Returns the kind of type being visited.
		 * 
		 * @return <code>TYPE_CLASS</code> or <code>TYPE_INTERFACE</code>
		 */
		private int getType() {
			return ((Integer)(typeStack.peek())).intValue();
		}

		/* (non-Javadoc)
		 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.FieldDeclaration)
		 */
		public boolean visit(FieldDeclaration node) {
			List fields = node.fragments();
			VariableDeclarationFragment fragment = null;
			for(Iterator iter = fields.iterator(); iter.hasNext();) {
				fragment = (VariableDeclarationFragment) iter.next();
				updateDocNode(findDescriptorByName(fragment.getName().getFullyQualifiedName(), null), node, getType(), IApiJavadocTag.MEMBER_FIELD);
			}
			return false;
		}
		/* (non-Javadoc)
		 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.MethodDeclaration)
		 */
		public boolean visit(MethodDeclaration node) {
			String signature = Util.getMethodSignatureFromNode(node);
			if(signature != null) {
				updateDocNode(findDescriptorByName(node.getName().getFullyQualifiedName(), signature), node, getType(), IApiJavadocTag.MEMBER_METHOD);
			}
			return false;
		}
		/**
		 * Updates the specified javadoc node if needed, creates a new doc node if one is not present
		 * @param element the element to get API information from 
		 * @param docnode the doc node to update
		 * @param type one of <code>CLASS</code> or <code>INTERFACE</code>
     	 * @param member one of <code>METHOD</code> or <code>FIELD</code> or <code>NONE</code>
		 */
		private void updateDocNode(IElementDescriptor element, BodyDeclaration body, int type, int member) {
			if(element != null) {
				//check for missing tags first, might not need to do any work
				IApiAnnotations api = description.resolveAnnotations(null, element);
				if(api != null) {
					Javadoc docnode = body.getJavadoc();
					AST ast = body.getAST();
					boolean newnode = docnode == null;
					if(docnode == null) {
						docnode = ast.newJavadoc();
					}
					String[] missingtags = collectMissingTags(api, docnode.tags(), type, member);
					if(missingtags.length == 0) {
						return;
					}
					else if(newnode) {
						//we do not want to create a new empty Javadoc node in
						//the AST if there are no missing tags
						rewrite.set(body, body.getJavadocProperty(), docnode, null);
					}
					ListRewrite lrewrite = rewrite.getListRewrite(docnode, Javadoc.TAGS_PROPERTY);
					TagElement newtag = null;
					for(int i = 0; i < missingtags.length; i++) {
						newtag = createNewTagElement(ast, missingtags[i]);
						lrewrite.insertLast(newtag, null);
					}
				}
			}
		}
		/**
		 * Creates a new {@link TagElement} against the specified {@link AST} and returns it
		 * @param ast the {@link AST} to create the {@link TagElement} against
		 * @param tagname the name of the new tag
		 * @return a new {@link TagElement} with the given name
		 */
		private TagElement createNewTagElement(AST ast, String tagname) {
			TagElement newtag = ast.newTagElement();
			newtag.setTagName(tagname);
			return newtag;
		}
		/**
		 * Collects the missing javadoc tags from based on the given listing of {@link TagElement}s
		 * @param api
		 * @param tags
		 * @param type one of <code>CLASS</code> or <code>INTERFACE</code>
	     * @param member one of <code>METHOD</code> or <code>FIELD</code> or <code>NONE</code>
		 * @return an array of the missing {@link TagElement}s or an empty array, never <code>null</code>
		 */
		private String[] collectMissingTags(IApiAnnotations api, List tags, int type, int member) {
			int res = api.getRestrictions();
			ArrayList missing = new ArrayList();
			JavadocTagManager jtm = ApiPlugin.getJavadocTagManager();
			if(RestrictionModifiers.isImplementRestriction(res)) {
				if(!containsRestrictionTag(tags, "@noimplement")) { //$NON-NLS-1$
					IApiJavadocTag tag = jtm.getTag("org.eclipse.pde.api.tools.noimplement"); //$NON-NLS-1$
					missing.add(tag.getCompleteTag(type, member));
				}
			}
			if(RestrictionModifiers.isInstantiateRestriction(res)) {
				if(!containsRestrictionTag(tags, "@noinstantiate")) { //$NON-NLS-1$
					IApiJavadocTag tag = jtm.getTag("org.eclipse.pde.api.tools.noinstantiate"); //$NON-NLS-1$
					missing.add(tag.getCompleteTag(type, member));
				}
			}
			if(RestrictionModifiers.isReferenceRestriction(res)) {
				if(!containsRestrictionTag(tags, "@noreference")) { //$NON-NLS-1$
					IApiJavadocTag tag = jtm.getTag("org.eclipse.pde.api.tools.noreference"); //$NON-NLS-1$
					missing.add(tag.getCompleteTag(type, member));
				}
			}
			if(RestrictionModifiers.isExtendRestriction(res)) {
				if(!containsRestrictionTag(tags, "@noextend")) { //$NON-NLS-1$
					IApiJavadocTag tag = jtm.getTag("org.eclipse.pde.api.tools.noextend"); //$NON-NLS-1$
					missing.add(tag.getCompleteTag(type, member));
				}
			}
			return (String[]) missing.toArray(new String[missing.size()]);
		}
		/**
		 * Determines if the specified tag appears in the {@link TagElement} listing given
		 * @param tags
		 * @param tag
		 * @return true if the listing of {@link TagElement}s contains the given tag
		 */
		private boolean containsRestrictionTag(List tags, String tag) {
			TagElement tagelement = null;
			for(int i = 0; i < tags.size(); i++) {
				tagelement = (TagElement) tags.get(i);
				if(tag.equals(tagelement.getTagName())) {
					return true;
				}
			}
			return false;
		}
		/**
		 * Finds the {@link IElementDescriptor} that matches the specified name and signature
		 * @param name
		 * @param signature
		 * @return the matching {@link IElementDescriptor} or <code>null</code> 
		 */
		private IElementDescriptor findDescriptorByName(String name, String signature) {
			IElementDescriptor desc = null;
			for(int i = 0; i < apis.size(); i++) {
				desc = (IElementDescriptor) apis.get(i);
				switch(desc.getElementType()) {
					case IElementDescriptor.T_REFERENCE_TYPE: {
						if(((IReferenceTypeDescriptor)desc).getName().equals(name)) {
							return desc;
						}
						break;
					}
					case IElementDescriptor.T_METHOD: {
						IMethodDescriptor method = (IMethodDescriptor) desc;
						if(method.getName().equals(name) && method.getSignature().equals(signature)) {
							return desc;
						}
						break;
					}
					case IElementDescriptor.T_FIELD: {
						if(((IFieldDescriptor)desc).getName().equals(name)) {
							return desc;
						}
						break;
					}
				}
			}
			return null;
		}
	}
	
	/**
	 * Constant representing the value for UTF-8 encoding.
	 * Value is: <code>UTF-8</code>
	 */
	public  static final String UTF_8 = "UTF-8"; //$NON-NLS-1$
	
	/**
	 * Constant representing the name of a component XML file.
	 * Value is: <code>component.xml</code>
	 */
	public static final String COMPONENT_XML_NAME = "component.xml"; //$NON-NLS-1$
	
	/**
	 * Constant representing a component element node in xml.
	 * Value is: <code>component</code> 
	 */
	public static final String ELEMENT_COMPONENT = "component"; //$NON-NLS-1$
	
	/**
	 * Constant representing a plugin element node in xml.
	 * Value is: <code>plugin</code>
	 */
	public static final String ELEMENT_PLUGIN = "plugin"; //$NON-NLS-1$
	
	/**
	 * Constant representing a package element node in xml.
	 * Value is: <code>package</code>
	 */
	public static final String ELEMENT_PACKAGE = "package"; //$NON-NLS-1$
	
	/**
	 * Constant representing a type element node in xml.
	 * Value is: <code>type</code>
	 */
	public static final String ELEMENT_TYPE = "type"; //$NON-NLS-1$
	
	/**
	 * Constant representing a method element node in xml.
	 * Value is: <code>method</code>
	 */
	public static final String ELEMENT_METHOD = "method"; //$NON-NLS-1$
	
	/**
	 * Constant representing a field element node in xml.
	 * Value is: <code>field</code>
	 */
	public static final String ELEMENT_FIELD = "field"; //$NON-NLS-1$
	
	/**
	 * Constant representing the xml attribute for the kind of an {@link IApiProblemFilter}.
	 * Value is: <code>kind</code>
	 */
	public static final String ATTR_KIND = "kind"; //$NON-NLS-1$
	
	/**
	 * Constant representing the id attribute for plug-in xml node.
	 * Value is: <code>id</code>
	 */
	public static final String ATTR_ID = "id"; //$NON-NLS-1$
	
	/**
	 * Constant representing the name attribute for component, package, type, method and field xml nodes.
	 * Value is: <code>name</code>
	 */
	public static final String ATTR_NAME = "name"; //$NON-NLS-1$
	
	/**
	 * Constant representing the context attribute for rules xml nodes specific to a component.
	 * Value is: <code>context</code>
	 */
	public static final String ATTR_CONTEXT = "context"; //$NON-NLS-1$
	
	/**
	 * Constant representing the extend attribute for a type xml node.
	 * Value is: <code>extend</code>
	 */
	public static final String ATTR_EXTEND = "extend"; //$NON-NLS-1$
	
	/**
	 * Constant representing the instantiate attribute for a type xml node.
	 * Value is: <code>instantiate</code>
	 */
	public static final String ATTR_INSTANTIATE = "instantiate";	 //$NON-NLS-1$
	
	/**
	 * Constant representing the implement attribute for a type xml node.
	 * Value is: <code>implement</code>
	 */
	public static final String ATTR_IMPLEMENT = "implement"; //$NON-NLS-1$
	
	/**
	 * Constant representing the reference attribute for a type xml node.
	 * Value is: <code>reference</code>
	 */
	public static final String ATTR_REFERENCE = "reference";	 //$NON-NLS-1$
	
	/**
	 * Constant representing the signature attribute for a method xml node.
	 * Value is: <code>signature</code> 
	 */
	public static final String ATTR_SIGNATURE = "signature"; //$NON-NLS-1$
	
	/**
	 * Constant representing the visibility attribute for component, package, type, method and field xml nodes.
	 * Will be one of: "API", "private", "private_permissable", or "SPI"
	 */
	public static final String ATTR_VISIBILITY = "visibility"; //$NON-NLS-1$
		
	/**
	 * Constructor
	 * can not be instantiated directly
	 */
	private ApiDescriptionProcessor() {}
	
	/**
	 * Parses a component XML into a string. The location may be a jar, directory containing the component.xml file, or 
	 * the component.xml file itself
	 * 
	 * @param location root location of the component.xml file, or the component.xml file itself
	 * @return component XML as a string or <code>null</code> if none
	 * @throws IOException if unable to parse
	 */
	public static String serializeComponentXml(File location) {
		if(location.exists()) {
			ZipFile jarFile = null;
			InputStream stream = null;
			try {
				String extension = new Path(location.getName()).getFileExtension();
				if (extension != null && extension.equals("jar") && location.isFile()) { //$NON-NLS-1$
					jarFile = new ZipFile(location, ZipFile.OPEN_READ);
					ZipEntry manifestEntry = jarFile.getEntry(COMPONENT_XML_NAME);
					if (manifestEntry != null) {
						stream = jarFile.getInputStream(manifestEntry);
					}
				} else if(location.isDirectory()) {
					File file = new File(location, COMPONENT_XML_NAME);
					if (file.exists()) {
						stream = new FileInputStream(file);
					}
				}
				else if(location.isFile()) {
					if(location.getName().equals(COMPONENT_XML_NAME)) {
						stream = new FileInputStream(location);
					}
				}
				if(stream != null) {
						return new String(Util.getInputStreamAsCharArray(stream, -1, UTF_8));
				}
			} catch(IOException e) {
				ApiPlugin.log(e);
			} finally {
				try {
					if (stream != null) {
						stream.close();
					}
				} catch (IOException e) {
					ApiPlugin.log(e);
				}
				try {
					if (jarFile != null) {
						jarFile.close();
					}
				} catch (IOException e) {
					ApiPlugin.log(e);
				}
			}
		}
		return null;
	}
	
	/**
	 * This method updates the javadoc for members of the specified java source files with information
	 * retrieved from the the specified component.xml file.
	 * @param project the java project to update
	 * @param componentxml the component.xml file to update from
	 * @param collector
	 * @throws CoreException
	 * @throws IOException
	 */
	public static void collectTagUpdates(IJavaProject project, File componentxml, Map collector) throws CoreException, IOException {
		IApiDescription description = new ApiDescription(null);
		annotateApiSettings(project, description, serializeComponentXml(componentxml));
		//visit the types
		DescriptionVisitor visitor = new DescriptionVisitor(project, description, collector);
		description.accept(visitor);
		IStatus status = visitor.getStatus();
		if (!status.isOK()) {
			throw new CoreException(status);
		}
	}
	
	/**
	 * Given the type, the parent type descriptor and an annotated description, update
	 * the javadoc comments for the type and all members of the type found in the description.
	 * @param type
	 * @param desc
	 * @param description
	 * @param members members with API annotations
	 * @param collector
	 * @throws CoreException
	 * @throws BadLocationException
	 */
	private static void processTagUpdates(IType type, IReferenceTypeDescriptor desc, IApiDescription description, List members, Map collector) throws CoreException, BadLocationException {
		ASTParser parser = ASTParser.newParser(AST.JLS3);
		ICompilationUnit cunit = type.getCompilationUnit();
		if(cunit != null) {
			parser.setSource(cunit);
			Map options = cunit.getJavaProject().getOptions(true);
			options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
			parser.setCompilerOptions(options);
			CompilationUnit cast = (CompilationUnit) parser.createAST(new NullProgressMonitor());
			cast.recordModifications();
			ASTRewrite rewrite = ASTRewrite.create(cast.getAST());
			ASTTagVisitor visitor = new ASTTagVisitor(members, description, rewrite);
			cast.accept(visitor);
			ITextFileBufferManager bm = FileBuffers.getTextFileBufferManager(); 
			IPath path = cast.getJavaElement().getPath();
			try {
				bm.connect(path, LocationKind.IFILE, null);
				ITextFileBuffer tfb = bm.getTextFileBuffer(path, LocationKind.IFILE);
				IDocument document = tfb.getDocument();
				TextEdit edit = rewrite.rewriteAST(document, null);
				if(edit.getChildrenSize() > 0 || edit.getLength() != 0) {
					IFile file = (IFile) cunit.getUnderlyingResource();
					HashSet edits = (HashSet) collector.get(file);
					if(edits == null) {
						edits = new HashSet(3);
						collector.put(file, edits);
					}
					edits.add(edit);
				}
			} finally {
				bm.disconnect(path, LocationKind.IFILE, null);
			}
		}
	}
	
	/**
	 * Throws an exception with the given message and underlying exception.
	 * 
	 * @param message error message
	 * @param exception underlying exception, or <code>null</code>
	 * @throws CoreException
	 */
	private static void abort(String message, Throwable exception) throws CoreException {
		IStatus status = new Status(IStatus.ERROR, ApiPlugin.getPluginIdentifier(), message, exception);
		throw new CoreException(status);
	}
	
	/**
	 * Annotates the given {@link IApiFilterStore} with information loaded from the specified
	 * xml file.
	 * @param store
	 * @param xml
	 * @throws CoreException
	 */
	public static void annotateApiFilters(IApiFilterStore store, String xml) throws CoreException {
		Element root = null;
		try {
			root = Util.parseDocument(xml);
		}
		catch(CoreException ce) {
			abort("Failed to parse API filters xml file", ce); //$NON-NLS-1$
		}
		if (!root.getNodeName().equals(ELEMENT_COMPONENT)) {
			abort(ScannerMessages.ComponentXMLScanner_0, null); 
		}
		String component = root.getAttribute(ATTR_ID);
		if(component.length() == 0) {
			abort("Missing component id", null); //$NON-NLS-1$
		}
		NodeList packages = root.getElementsByTagName(ELEMENT_PACKAGE);
		NodeList types = null;
		IPackageDescriptor packdesc = null;
		Element type = null;
		String name = null;
		String kind = null;
		for (int i = 0; i < packages.getLength(); i++) {
			Element pkg = (Element) packages.item(i);
			name = pkg.getAttribute(ATTR_NAME);
			if (name.length() == 0) {
				abort("Missing package name", null); //$NON-NLS-1$
			}
			kind = pkg.getAttribute(ATTR_KIND);
			String[] kinds = kind.split("\\,"); //$NON-NLS-1$
			if(kinds.length == 0) {
				abort("No kinds have been set on type ["+name+"]", null); //$NON-NLS-1$ //$NON-NLS-2$
			}
			packdesc = Factory.packageDescriptor(name);
			//optimization: String.split will return a 1 entry array with the empty string when called on the empty string
			//we want to leave these out of the filter mapping
			if(!"".equals(kinds[0]) || kinds.length > 1) { //$NON-NLS-1$
				store.addFilter(new ApiProblemFilter(component, packdesc, kinds));
			}
			types = pkg.getElementsByTagName(ELEMENT_TYPE);
			for (int j = 0; j < types.getLength(); j++) {
				type = (Element) types.item(j);
				name = type.getAttribute(ATTR_NAME);
				if (name.length() == 0) {
					abort("Missing type name", null); //$NON-NLS-1$
				}
				kind = type.getAttribute(ATTR_KIND);
				kinds = kind.split("\\,"); //$NON-NLS-1$
				if(kinds.length == 0) {
					abort("No kinds have been set on type ["+name+"]", null); //$NON-NLS-1$ //$NON-NLS-2$
				}
				IReferenceTypeDescriptor typedesc = packdesc.getType(name);
				if(!"".equals(kinds[0]) || kinds.length > 1) { //$NON-NLS-1$
					store.addFilter(new ApiProblemFilter(component, typedesc, kinds));
				}
				annotateMethodFilters(store, component, typedesc, type);
				annotateFieldFilters(store, component, typedesc, type);
			}
		}
	}
	
	/**
	 * Adds all child method entries to the given store from xml
	 * @param store
	 * @param component
	 * @param type
	 * @param parent
	 * @throws CoreException
	 */
	private static void annotateMethodFilters(IApiFilterStore store, String component, IReferenceTypeDescriptor type, Element parent) throws CoreException {
		NodeList methods = parent.getElementsByTagName(ELEMENT_METHOD);
		Element method = null;
		String name = null;
		String signature = null;
		String kind = null;
		IMethodDescriptor methoddesc = null;
		for(int i = 0; i < methods.getLength(); i++) {
			method = (Element) methods.item(i);
			name = method.getAttribute(ATTR_NAME);
			if(name == null) {
				abort(ScannerMessages.ComponentXMLScanner_2, null); 
			}
			signature = method.getAttribute(ATTR_SIGNATURE);
			if(signature == null) {
				abort(ScannerMessages.ComponentXMLScanner_3, null); 
			}
			kind = method.getAttribute(ATTR_KIND);
			methoddesc = type.getMethod(name, Util.dequalifySignature(signature));
			String[] kinds = kind.split("\\,"); //$NON-NLS-1$
			if(kinds.length == 0) {
				abort("No kinds have been set on method ["+name+"]", null); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if(!"".equals(kinds[0]) || kinds.length > 1) { //$NON-NLS-1$
				store.addFilter(new ApiProblemFilter(component, methoddesc, kinds));
			}
		}
	}
	
	/**
	 * Adds all child field entries to the given store form xml
	 * @param store
	 * @param component
	 * @param type
	 * @param parent
	 * @throws CoreException
	 */
	private static void annotateFieldFilters(IApiFilterStore store, String component, IReferenceTypeDescriptor type, Element parent) throws CoreException {
		NodeList fields = parent.getElementsByTagName(ELEMENT_FIELD);
		Element field = null;
		String name = null;
		String kind = null;
		IFieldDescriptor fielddesc = null;
		for(int i = 0; i < fields.getLength(); i++) {
			field = (Element) fields.item(i);
			name = field.getAttribute(ATTR_NAME);
			if(name == null) {
				abort(ScannerMessages.ComponentXMLScanner_1, null); 
			}
			kind = field.getAttribute(ATTR_KIND); 
			fielddesc = type.getField(name);
			String[] kinds = kind.split("\\,"); //$NON-NLS-1$
			if(kinds.length == 0) {
				abort("No kinds have been set on field ["+name+"]", null); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if(!"".equals(kinds[0]) || kinds.length > 1) { //$NON-NLS-1$
				store.addFilter(new ApiProblemFilter(component, fielddesc, kinds));
			}
		}
	}
	
	/**
	 * Parses the given xml document (in string format), and annotates the specified 
	 * {@link IApiDescription} with {@link IPackageDescriptor}s, {@link IReferenceTypeDescriptor}s, {@link IMethodDescriptor}s
	 * and {@link IFieldDescriptor}s.
	 * 
	 * @param settings API settings to annotate
	 * @param xml XML used to generate settings
	 * @throws CoreException 
	 */
	public static void annotateApiSettings(IJavaProject project, IApiDescription settings, String xml) throws CoreException {
		Element root = null;
		try {
			root = Util.parseDocument(xml);
		}
		catch(CoreException ce) {
			abort("Failed to parse API description xml file", ce); //$NON-NLS-1$
		}
		if (!root.getNodeName().equals(ELEMENT_COMPONENT)) {
			abort(ScannerMessages.ComponentXMLScanner_0, null); 
		}
		NodeList packages = root.getElementsByTagName(ELEMENT_PACKAGE);
		NodeList types = null;
		IPackageDescriptor packdesc = null;
		Element type = null;
		for (int i = 0; i < packages.getLength(); i++) {
			Element pkg = (Element) packages.item(i);
			// package visibility comes from the MANIFEST.MF
			String pkgName = pkg.getAttribute(ATTR_NAME);
			packdesc = Factory.packageDescriptor(pkgName);
			types = pkg.getElementsByTagName(ELEMENT_TYPE);
			for (int j = 0; j < types.getLength(); j++) {
				type = (Element) types.item(j);
				String name = type.getAttribute(ATTR_NAME);
				if (name.length() == 0) {
					abort("Missing type name", null); //$NON-NLS-1$
				}
				IReferenceTypeDescriptor typedesc = packdesc.getType(name); 
				annotateDescriptor(project, settings, typedesc, type);
				annotateMethodSettings(project, settings, typedesc, type);
				annotateFieldSettings(project, settings, typedesc, type);
			}
		}
	}
	
	/**
	 * Annotates the backing {@link IApiDescription} from the given {@link Element}, by adding the visibility
	 * and restriction attributes to the specified {@link IElementDescriptor}
	 * 
	 * @param settings the settings to annotate
	 * @param descriptor the current descriptor context
	 * @param element the current element to annotate from
	 */
	private static void annotateDescriptor(IJavaProject project, IApiDescription settings, IElementDescriptor descriptor, Element element) {
		String component = element.getAttribute(ATTR_CONTEXT);
		if (component.length() == 0) {
			component = null;
		}
		int typeVis = getVisibility(element);
		if (typeVis != -1) {
			settings.setVisibility(component, descriptor, typeVis);
		}
		int restrictions = getRestrictions(element);
		if (descriptor.getElementType() == IElementDescriptor.T_REFERENCE_TYPE) {
			IReferenceTypeDescriptor referenceTypeDescriptor = (IReferenceTypeDescriptor) descriptor;
			IType type = null;
			if (project != null) {
				try {
					type = project.findType(referenceTypeDescriptor.getQualifiedName());
					if (type != null) {
						if ((restrictions & RestrictionModifiers.NO_INSTANTIATE) != 0) {
							// don't set for abstract class or interface
							if (type.isInterface() || Flags.isAbstract(type.getFlags())) {
								restrictions &= ~RestrictionModifiers.NO_INSTANTIATE;
							}
						}
						if ((restrictions & RestrictionModifiers.NO_IMPLEMENT) != 0) {
							// don't set for class
							if (type.isClass()) {
								restrictions &= ~RestrictionModifiers.NO_IMPLEMENT;
							}
						}
						if ((restrictions & RestrictionModifiers.NO_EXTEND) != 0) {
							// don't set for class
							if (type.isClass() && Flags.isFinal(type.getFlags())) {
								restrictions &= ~RestrictionModifiers.NO_EXTEND;
							}
						}
					}
				} catch (JavaModelException e) {
					// ignore
				}
			}
		}
		settings.setRestrictions(component, descriptor, restrictions);
	}
	
	/**
	 * Returns restriction settings described in the given element.
	 * 
	 * @param element XML element
	 * @return restriction settings
	 */
	private static int getRestrictions(Element element) {
		int res = annotateRestriction(element, ATTR_IMPLEMENT, RestrictionModifiers.NO_IMPLEMENT, RestrictionModifiers.NO_RESTRICTIONS);
		res = annotateRestriction(element, ATTR_EXTEND, RestrictionModifiers.NO_EXTEND, res);
		res = annotateRestriction(element, "subclass", RestrictionModifiers.NO_EXTEND, res); //$NON-NLS-1$
		res = annotateRestriction(element, ATTR_INSTANTIATE, RestrictionModifiers.NO_INSTANTIATE, res);
		res = annotateRestriction(element, ATTR_REFERENCE, RestrictionModifiers.NO_REFERENCE, res);
		return res;
	}
	
	/**
	 * Tests if the given restriction exists for the given element
	 * and returns an updated restrictions flag.
	 * 
	 * @param element XML element
	 * @param name attribute to test
	 * @param flag bit mask for attribute
	 * @param res flag to combine with 
	 * @return updated flags
	 */
	private static int annotateRestriction(Element element, String name, int flag, int res) {
		String value = element.getAttribute(name);
		if (value.length() > 0) {
			if (!Boolean.valueOf(value).booleanValue()) {
				res = res | flag;
			}
		}
		return res;
	}
	
	/**
	 * Returns visibility settings described in the given element or
	 * -1 if none.
	 * 
	 * @param element XML element
	 * @return visibility settings or -1 if none
	 */
	private static int getVisibility(Element element) {
		String attribute = element.getAttribute(ATTR_VISIBILITY);
		if (ApiSettingsXmlVisitor.VALUE_API.equals(attribute)) {
			return VisibilityModifiers.API;
		}
		if (ApiSettingsXmlVisitor.VALUE_PRIVATE.equals(attribute)) {
			return VisibilityModifiers.PRIVATE;
		}
		if (ApiSettingsXmlVisitor.VALUE_PRIVATE_PERMISSABLE.equals(attribute)) {
			return VisibilityModifiers.PRIVATE_PERMISSIBLE;
		}
		if (ApiSettingsXmlVisitor.VALUE_SPI.equals(attribute)) {
			return VisibilityModifiers.SPI;
		}
		return -1;
	}
	
	/**
	 * Annotates the supplied {@link IApiDescription} from all of the field elements
	 * that are direct children of the specified {@link Element}. {@link IFieldDescriptor}s are created
	 * as needed and added as children of the specified {@link IReferenceTypeDescriptor}.
	 * 
	 * @param settings the {@link IApiDescription} to add the new {@link IFieldDescriptor} to
	 * @param typedesc the containing type descriptor for this field
	 * @param type the parent {@link Element}
	 * @throws CoreException
	 */
	private static void annotateFieldSettings(IJavaProject project, IApiDescription settings, IReferenceTypeDescriptor typedesc, Element type) throws CoreException {
		NodeList fields = type.getElementsByTagName(ELEMENT_FIELD);
		Element field = null;
		IFieldDescriptor fielddesc = null;
		String name = null;
		for(int i = 0; i < fields.getLength(); i++) {
			field = (Element) fields.item(i);
			name = field.getAttribute(ATTR_NAME);
			if(name == null) {
				abort(ScannerMessages.ComponentXMLScanner_1, null); 
			}
			fielddesc = typedesc.getField(name);
			annotateDescriptor(project, settings, fielddesc, field);
		}
	}
	
	/**
	 * Annotates the supplied {@link IApiDescription} from all of the method elements
	 * that are direct children of the specified {@link Element}. {@link IMethodDescriptor}s are created
	 * as needed and added as children of the specified {@link IReferenceTypeDescriptor}.
	 * 
	 * @param settings the {@link IApiDescription} to add the new {@link IMethodDescriptor} to 
	 * @param typedesc the containing type descriptor for this method
	 * @param type the parent {@link Element}
	 * @throws CoreException
	 */
	private static void annotateMethodSettings(IJavaProject project, IApiDescription settings, IReferenceTypeDescriptor typedesc, Element type) throws CoreException {
		NodeList methods = type.getElementsByTagName(ELEMENT_METHOD);
		Element method = null;
		IMethodDescriptor methoddesc = null;
		String name, signature;
		for(int i = 0; i < methods.getLength(); i++) {
			method = (Element) methods.item(i);
			name = method.getAttribute(ATTR_NAME);
			if(name == null) {
				abort(ScannerMessages.ComponentXMLScanner_2, null); 
			}
			signature = method.getAttribute(ATTR_SIGNATURE);
			if(signature == null) {
				abort(ScannerMessages.ComponentXMLScanner_3, null); 
			}
			methoddesc = typedesc.getMethod(name, signature);
			annotateDescriptor(project, settings, methoddesc, method);
		}
	}
}
