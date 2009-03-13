/*******************************************************************************
 * Copyright (c) 2004 Vlad Dumitrescu and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution.
 *
 * Contributors:
 *     Vlad Dumitrescu
 *******************************************************************************/
package org.erlide.ui.editors.erl;

import java.net.URL;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.internal.text.html.BrowserInformationControl;
import org.eclipse.jface.internal.text.html.HTMLPrinter;
import org.eclipse.jface.internal.text.html.HTMLTextPresenter;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.AbstractReusableInformationControlCreator;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.information.IInformationProviderExtension2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.editors.text.EditorsUI;
import org.erlide.core.erlang.ErlModelException;
import org.erlide.core.erlang.ErlScanner;
import org.erlide.core.erlang.ErlToken;
import org.erlide.core.erlang.ErlangCore;
import org.erlide.core.erlang.IErlElement;
import org.erlide.core.erlang.IErlFunction;
import org.erlide.core.erlang.IErlImport;
import org.erlide.core.erlang.IErlModule;
import org.erlide.core.erlang.IErlPreprocessorDef;
import org.erlide.core.erlang.IErlProject;
import org.erlide.core.erlang.util.Util;
import org.erlide.core.util.ErlangFunction;
import org.erlide.jinterface.rpc.Tuple;
import org.erlide.runtime.backend.Backend;
import org.erlide.ui.ErlideUIPlugin;
import org.erlide.ui.actions.OpenAction;
import org.erlide.ui.util.ErlModelUtils;
import org.osgi.framework.Bundle;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangRangeException;
import com.ericsson.otp.erlang.OtpErlangString;
import com.ericsson.otp.erlang.OtpErlangTuple;

import erlang.ErlideDoc;

public class ErlTextHover implements ITextHover,
		IInformationProviderExtension2, ITextHoverExtension {

	// private ITextEditor fEditor;
	private Collection<IErlImport> fImports;
	private final IErlModule fModule;
	private final String fExternalIncludes;
	private static URL fgStyleSheet;
	private final List<Tuple> pathVars;
	private final String fExternalModules;

	public ErlTextHover(final IErlModule module, final String externalModules,
			final String externalIncludes) {
		fImports = null;
		fModule = module;
		fExternalModules = externalModules;
		fExternalIncludes = externalIncludes;
		pathVars = OpenAction.getPathVars();
		initStyleSheet();
	}

	public IRegion getHoverRegion(final ITextViewer textViewer, final int offset) {
		final ErlToken token = fModule.getScanner().getTokenAt(offset);
		if (token == null) {
			return null;
		}
		// ErlLogger.debug("getHoverRegion " + token.toString());
		return new Region(token.getOffset(), token.getLength());
	}

	@SuppressWarnings("restriction")
	public String getHoverInfo(final ITextViewer textViewer,
			final IRegion hoverRegion) {
		final StringBuffer result = new StringBuffer();
		if (fImports == null) {
			fImports = ErlModelUtils.getImportsAsList(fModule);
		}
		final int offset = hoverRegion.getOffset();
		OtpErlangObject r1 = null;
		final int length = hoverRegion.getLength();
		final String debuggerVar = makeDebuggerVariableHover(textViewer,
				offset, length);
		if (debuggerVar.length() > 0) {
			result.append(debuggerVar);
		}
		final String stateDir = ErlideUIPlugin.getDefault().getStateLocation()
				.toString();
		final Backend b = ErlangCore.getBackendManager().getIdeBackend();
		r1 = ErlideDoc.getDocFromScan(b, offset, stateDir, ErlScanner
				.createScannerModuleName(fModule), fImports, fExternalModules,
				pathVars);
		// ErlLogger.debug("getHoverInfo getDocFromScan " + r1);
		if (r1 instanceof OtpErlangString) {
			final OtpErlangString s1 = (OtpErlangString) r1;
			result.append(s1.stringValue());
		} else if (r1 instanceof OtpErlangTuple) {
			// hr� ska vi kolla som open, m�ste faktorisera lite...
			final OtpErlangTuple t = (OtpErlangTuple) r1;
			final OtpErlangObject o0 = t.elementAt(0);
			final OtpErlangObject o1 = t.elementAt(1);
			if (o0 instanceof OtpErlangAtom && o1 instanceof OtpErlangAtom) {
				final OtpErlangAtom a0 = (OtpErlangAtom) o0;
				final OtpErlangAtom a1 = (OtpErlangAtom) o1;
				final String openKind = a0.atomValue();
				if (openKind.equals("error")) {
					return null;
				}
				String definedName = a1.atomValue();
				if (definedName.charAt(0) == '?') {
					definedName = definedName.substring(1);
				}
				// TODO code below should be cleaned up, we should factorize and
				// use same code for content assist, open and hover
				if (openKind.equals("local") || openKind.equals("external")) {
					IErlModule m = null;
					IErlFunction f = null;
					OtpErlangLong arityLong = null;
					if (openKind.equals("local")) {
						arityLong = (OtpErlangLong) t.elementAt(2);
						m = fModule;
					} else if (openKind.equals("external")) {
						final OtpErlangAtom a2 = (OtpErlangAtom) t.elementAt(2);
						final String mod = definedName;
						definedName = a2.atomValue();
						arityLong = (OtpErlangLong) t.elementAt(3);
						final OtpErlangString s4 = (OtpErlangString) t
								.elementAt(4);
						final String path = Util.stringValue(s4);
						IResource r = null;
						try {
							r = ErlModelUtils.openExternalModule(mod, path,
									fModule.getResource().getProject());
						} catch (final CoreException e2) {
						}
						if (!(r instanceof IFile)) {
							return null;
						}
						final IFile file = (IFile) r;
						m = ErlModelUtils.getModule(file);
					}
					int arity = -1;
					try {
						arity = arityLong.intValue();
					} catch (final OtpErlangRangeException e) {
					}
					final ErlangFunction erlangFunction = new ErlangFunction(
							definedName, arity);
					if (m == null) {
						return null;
					}
					try {
						m.open(null);
						f = ErlModelUtils.findFunction(m, erlangFunction);
					} catch (final ErlModelException e) {
					}
					if (f == null) {
						return null;
					}
					result.append(f.getComment());
				}
				final IErlElement.Kind kindToFind = openKind.equals("record") ? IErlElement.Kind.RECORD_DEF
						: IErlElement.Kind.MACRO_DEF;
				final IErlProject project = fModule.getProject();
				final IProject proj = project == null ? null
						: (IProject) project.getResource();
				final IErlPreprocessorDef pd = ErlModelUtils
						.findPreprocessorDef(b, proj, fModule, definedName,
								kindToFind, fExternalIncludes,
								ErlContentAssistProcessor.getPathVars());
				if (pd != null) {
					result.append(pd.getExtra());
				}
			}
		}
		if (result.length() > 0) {
			HTMLPrinter.insertPageProlog(result, 0, fgStyleSheet);
			HTMLPrinter.addPageEpilog(result);
		}
		return result.toString();
	}

	private void initStyleSheet() {
		final Bundle bundle = Platform.getBundle(ErlideUIPlugin.PLUGIN_ID);
		fgStyleSheet = bundle.getEntry("/edoc.css"); //$NON-NLS-1$
		if (fgStyleSheet != null) {

			try {
				fgStyleSheet = FileLocator.toFileURL(fgStyleSheet);
			} catch (final Exception e) {
			}
		}
	}

	/**
	 * @param textViewer
	 * @param offset
	 * @param length
	 */
	private String makeDebuggerVariableHover(final ITextViewer textViewer,
			final int offset, final int length) {
		final IAdaptable adaptable = DebugUITools.getDebugContext();
		if (adaptable != null) {
			final IStackFrame frame = (IStackFrame) adaptable
					.getAdapter(IStackFrame.class);
			try {
				if (frame != null && frame.hasVariables()) {
					String varName = "";
					try {
						varName = textViewer.getDocument().get(offset, length);
					} catch (final BadLocationException e) {
					}
					if (varName.length() > 0) {
						final String firstLetter = varName.substring(0, 1);
						if (firstLetter.toUpperCase().equals(firstLetter)) {
							final IVariable[] vars = frame.getVariables();
							for (final IVariable variable : vars) {
								if (variable.getName().equals(varName)) {
									final String value = variable.getValue()
											.getValueString();
									return makeVariablePresentation(varName,
											value);
								}
							}
						}
					}
				}
			} catch (final DebugException e) {
			}
		}
		return "";
	}

	private String makeVariablePresentation(final String varName,
			final String value) {
		return varName + " = " + value;
	}

	public IInformationControlCreator getInformationPresenterControlCreator() {
		return new AbstractReusableInformationControlCreator() {

			@SuppressWarnings("restriction")
			@Override
			protected IInformationControl doCreateInformationControl(
					final Shell parent) {
				if (BrowserInformationControl.isAvailable(parent)) {
					try {
						final ToolBarManager tbm = new ToolBarManager(SWT.FLAT);
						final String font = JFaceResources.DIALOG_FONT;
						final BrowserInformationControl iControl = new BrowserInformationControl(
								parent, font, tbm);
						return iControl;
					} catch (final NoSuchMethodError e) {
						// API changed in 3.4
						return new DefaultInformationControl(parent, EditorsUI
								.getTooltipAffordanceString(),
								new HTMLTextPresenter(true));
					}
				} else {
					return new DefaultInformationControl(parent, EditorsUI
							.getTooltipAffordanceString(),
							new HTMLTextPresenter(true));
				}
			}
		};
	}

	public IInformationControlCreator getHoverControlCreator() {
		return new AbstractReusableInformationControlCreator() {

			@SuppressWarnings("restriction")
			@Override
			protected IInformationControl doCreateInformationControl(
					final Shell parent) {
				if (BrowserInformationControl.isAvailable(parent)) {
					try {
						return new BrowserInformationControl(parent,
								JFaceResources.DIALOG_FONT, EditorsUI
										.getTooltipAffordanceString());
					} catch (final NoSuchMethodError e) {
						// API changed in 3.4
						return new DefaultInformationControl(parent, EditorsUI
								.getTooltipAffordanceString(),
								new HTMLTextPresenter(true));
					}
				} else {
					return new DefaultInformationControl(parent, EditorsUI
							.getTooltipAffordanceString(),
							new HTMLTextPresenter(true));
				}
			}
		};
	}

	public static String getHoverTextForOffset(final int offset,
			final ErlangEditor editor) {
		final ErlTextHover h = new ErlTextHover(
				ErlModelUtils.getModule(editor), editor.getExternalModules(),
				editor.getExternalIncludes());
		final ITextViewer tv = editor.getViewer();
		final IRegion r = h.getHoverRegion(tv, offset);
		if (r == null) {
			return null;
		}
		return h.getHoverInfo(tv, r);
	}

}
