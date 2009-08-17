/*******************************************************************************
 * Copyright (c) 2009 Vlad Dumitrescu and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available
 * at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Vlad Dumitrescu
 *******************************************************************************/
package org.erlide.ui.views.console;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.IConsoleView;
import org.eclipse.ui.part.IPageBookViewPage;
import org.eclipse.ui.part.IPageSite;
import org.erlide.core.erlang.ErlangCore;
import org.erlide.jinterface.backend.BackendException;
import org.erlide.jinterface.backend.ErlBackend;
import org.erlide.jinterface.backend.IShell;
import org.erlide.jinterface.util.ErlLogger;
import org.erlide.runtime.backend.ErlideBackend;
import org.erlide.runtime.backend.console.ErlConsoleModel;
import org.erlide.runtime.backend.console.ErlConsoleModelListener;
import org.erlide.runtime.backend.console.IoRequest;
import org.erlide.runtime.backend.console.ErlConsoleModel.ConsoleEventHandler;

import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;

public class ErlangConsolePage implements IPageBookViewPage,
		ErlConsoleModelListener {
	public static final String ID = "org.erlide.ui.views.console";

	private static final Color[] colors = {
			new Color(Display.getDefault(), 0xFF, 0xFF, 0xFF),
			new Color(Display.getDefault(), 0xCC, 0xFF, 0xFF),
			new Color(Display.getDefault(), 0xFF, 0xCC, 0xFF),
			new Color(Display.getDefault(), 0xFF, 0xFF, 0xCC),
			new Color(Display.getDefault(), 0xCC, 0xCC, 0xFF),
			new Color(Display.getDefault(), 0xCC, 0xFF, 0xCC),
			new Color(Display.getDefault(), 0xFF, 0xCC, 0xCC),
			new Color(Display.getDefault(), 0x99, 0xFF, 0xFF),
			new Color(Display.getDefault(), 0xFF, 0x99, 0xFF),
			new Color(Display.getDefault(), 0xFF, 0xFF, 0x99),
			new Color(Display.getDefault(), 0x99, 0xCC, 0xFF),
			new Color(Display.getDefault(), 0xCC, 0x99, 0xFF),
			new Color(Display.getDefault(), 0xFF, 0x99, 0xCC),
			new Color(Display.getDefault(), 0xFF, 0xCC, 0x99),
			new Color(Display.getDefault(), 0x99, 0xFF, 0xCC),
			new Color(Display.getDefault(), 0xCC, 0xFF, 0x99),
			new Color(Display.getDefault(), 0x99, 0x99, 0xFF),
			new Color(Display.getDefault(), 0xFF, 0x99, 0x99),
			new Color(Display.getDefault(), 0x99, 0xFF, 0x99) };

	final Color bgColor_Ok = new Color(Display.getCurrent(), new RGB(245, 255,
			245));
	final Color bgColor_Err = new Color(Display.getCurrent(), new RGB(255, 245,
			245));

	StyledText consoleText;
	private boolean fGroupByLeader;
	private boolean fColored;
	private final Set<OtpErlangPid> pids = new TreeSet<OtpErlangPid>();
	private final ErlConsoleDocument fDoc;
	final ErlangConsoleHistory history = new ErlangConsoleHistory();
	StyledText consoleInput;
	private SourceViewer consoleOutputViewer;
	private SourceViewer consoleInputViewer;
	private final ErlConsoleModel model;
	private IShell shell;
	final ErlideBackend backend;
	private Action action;

	public ErlangConsolePage(IConsoleView view) {
		super();
		model = new ErlConsoleModel();
		final ConsoleEventHandler handler = model.getHandler();
		backend = ErlangCore.getBackendManager().getIdeBackend();
		try {
			final Job j = new Job("shell opener") {
				@Override
				protected IStatus run(final IProgressMonitor monitor) {
					if (backend == null) {
						schedule(400);
					} else {
						backend.getEventDaemon().addHandler(handler);
						shell = backend.getShellManager().openShell("main");
					}
					return Status.OK_STATUS;
				}
			};
			j.setSystem(true);
			j.setPriority(Job.SHORT);
			j.schedule(400);
		} catch (final Exception e) {
			ErlLogger.warn(e);
		}
		model.addListener(this);
		fDoc = new ErlConsoleDocument(model);
	}

	public void dispose() {
		backend.getEventDaemon().removeHandler(model.getHandler());
		model.dispose();
		bgColor_Err.dispose();
		bgColor_Ok.dispose();
	}

	void createInputField(final KeyEvent first) {
		if (first.character == SWT.ESC) {
			return;
		}
		try {
			String text = consoleText.getText();
			int charCount = text.length();
			consoleText.setCaretOffset(charCount);
			final Rectangle rect = consoleText.getClientArea();
			final Point relpos = consoleText.getLocationAtOffset(charCount);

			final Shell container = new Shell(consoleText.getShell(),
					SWT.MODELESS);
			container.setLayout(new FillLayout());
			consoleInputViewer = new SourceViewer(container, null, SWT.MULTI
					| SWT.WRAP | SWT.V_SCROLL);
			consoleInputViewer.setDocument(new Document());
			consoleInputViewer
					.configure(new ErlangConsoleSourceViewerConfiguration());
			consoleInput = (StyledText) consoleInputViewer.getControl();
			consoleInput.setParent(container);
			container.setAlpha(220);

			final int b = 1;
			final Point screenPos = consoleText.toDisplay(relpos.x - b,
					relpos.y - b);
			container.setLocation(screenPos);
			container.setSize(rect.width - relpos.x, rect.height - relpos.y);

			consoleInput.addKeyListener(new KeyAdapter() {

				@Override
				public void keyPressed(final KeyEvent e) {
					final boolean historyMode = (e.stateMask & SWT.CTRL) == SWT.CTRL;
					if (e.keyCode == 13
							&& isInputComplete()
							&& consoleInput.getSelection().x == consoleInput
									.getText().length()) {
						sendInput();
						container.close();
						e.doit = false;
					} else if (e.keyCode == 13) {
						final Rectangle loc = container.getBounds();
						final int topIndex = consoleInput.getTopIndex();
						final int lineCount = consoleInput.getLineCount();
						final int lineHeight = consoleInput.getLineHeight();
						final int visibleLines = loc.height / lineHeight;
						final int maxLines = consoleText.getSize().y
								/ lineHeight - 1;
						if (topIndex + visibleLines - 1 <= lineCount
								&& visibleLines < maxLines) {
							container.setBounds(loc.x, loc.y - lineHeight,
									loc.width, loc.height + lineHeight);
							consoleInput.setTopIndex(lineCount - visibleLines
									+ 1);
						}
					} else if (historyMode && e.keyCode == SWT.ARROW_UP) {
						history.prev();
						final String s = history.get();
						consoleInput.setText(s);
						consoleInput.setSelection(consoleInput.getText()
								.length());
						fixPosition(container);
					} else if (historyMode && e.keyCode == SWT.ARROW_DOWN) {
						history.next();
						final String s = history.get();
						consoleInput.setText(s);
						consoleInput.setSelection(consoleInput.getText()
								.length());
						fixPosition(container);
					} else if (e.keyCode == SWT.ESC) {
						container.close();
					}
				}
			});
			consoleInput.addFocusListener(new FocusAdapter() {
				@Override
				public void focusLost(final FocusEvent e) {
					// container.close();
				}
			});
			consoleInput.addModifyListener(new ModifyListener() {
				public void modifyText(ModifyEvent e) {
					if (isInputComplete()) {
						consoleInput.setBackground(bgColor_Ok);
					} else {
						Color bgColorErr = bgColor_Err;
						consoleInput.setBackground(bgColorErr);
					}
				}
			});
			consoleInput.setFont(consoleText.getFont());
			consoleInput.setBackground(consoleText.getBackground());
			consoleInput.setWordWrap(true);

			if (first.character != 0) {
				consoleInput.setText("" + first.character);
			} else {
				String s = "";
				if (history.size() == 0) {
					s = "";
				} else {
					if (first.keyCode == SWT.ARROW_UP) {
						history.gotoLast();
					} else {
						history.gotoFirst();
					}
					s = history.get();
				}
				consoleInput.setText(s);
				fixPosition(container);
			}
			consoleInput.setSelection(consoleInput.getCharCount());

			container.setVisible(true);
			consoleInput.setFocus();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	void fixPosition(final Shell container) {
		final Rectangle loc = container.getBounds();
		final int lineCount = consoleInput.getLineCount();
		final int lineHeight = consoleInput.getLineHeight();
		final int visibleLines = loc.height / lineHeight;
		final int maxLines = consoleText.getSize().y / lineHeight - 1;
		final int lines = Math.max(
				Math.min(maxLines, lineCount) - visibleLines, visibleLines);
		if (visibleLines - 1 <= lineCount) {
			container.setBounds(loc.x, loc.y - lineHeight * lines, loc.width,
					loc.height + lineHeight * lines);
		}
	}

	boolean isInputComplete() {
		try {
			final String str = consoleInput.getText() + " ";
			final OtpErlangObject o = ErlBackend.parseConsoleInput(ErlangCore
					.getBackendManager().getIdeBackend(), str);
			if (o instanceof OtpErlangList && ((OtpErlangList) o).arity() == 0) {
				return false;
			}
			if (!(o instanceof OtpErlangList)) {
				return false;
			}
		} catch (final BackendException e) {
			return false;
		}
		return true;
	}

	protected void sendInput() {
		final String s = consoleInput.getText();
		input(s);
		consoleInput.setText("");
	}

	private void updateConsoleView() {
		consoleText.setRedraw(false);
		try {
			String text = fDoc.get();
			consoleText.setText(text);
			consoleText.setSelection(text.length());
		} finally {
			consoleText.setRedraw(true);
		}
	}

	Color getColor(final OtpErlangPid sender) {
		int ix = 0;
		for (final Object element : pids) {
			final OtpErlangPid pid = (OtpErlangPid) element;
			if (pid.equals(sender)) {
				break;
			}
			ix++;
		}
		if (ix < colors.length - 1) {
			return colors[ix % 19 + 1];
		}
		return colors[0];
	}

	public void input(final String data) {
		model.input(data);
		shell.send(data);
		this.history.addToHistory(data.trim());
	}

	void refreshView() {
		// while (true) {
		if (consoleText.isDisposed()) {
			return;
		}
		try {
			updateConsoleView();
			// break;
		} catch (final Exception e) {
			e.printStackTrace();
		}
		// }
	}

	public void markRequests(final List<IoRequest> reqs) {
		for (final Object element0 : reqs) {
			final IoRequest element = (IoRequest) element0;
			markRequest(element);
		}
	}

	public void markRequest(final IoRequest req) {
		final StyleRange range = new StyleRange();
		range.start = req.getStart();
		range.length = req.getLength();
		range.background = getColor(fGroupByLeader ? req.getLeader() : req
				.getSender());
		consoleText.setStyleRange(range);
	}

	public void clearMarks() {
		final StyleRange range = new StyleRange();
		range.start = 0;
		range.length = consoleText.getCharCount();
		consoleText.setStyleRange(range);
	}

	public void setInput(final String str) {
		consoleInput.setText(str);
		consoleInput.setSelection(str.length());
	}

	public void changed(final ErlConsoleModel erlConsoleModel) {
		Display.getDefault().asyncExec(new Runnable() {
			public void run() {
				refreshView();
			}
		});
	}

	public IPageSite getSite() {
		return null;
	}

	public void init(IPageSite site) throws PartInitException {
	}

	public void createControl(Composite parent) {
		consoleOutputViewer = new SourceViewer(parent, null, SWT.V_SCROLL
				| SWT.H_SCROLL | SWT.MULTI | SWT.READ_ONLY);
		consoleOutputViewer.setDocument(fDoc);
		consoleText = (StyledText) consoleOutputViewer.getControl();
		consoleText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true,
				2, 1));

		consoleOutputViewer
				.configure(new ErlangConsoleSourceViewerConfiguration());

		consoleText.setFont(JFaceResources.getTextFont());
		consoleText.setEditable(false);
		consoleText.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(final KeyEvent e) {
				final boolean isHistoryCommand = ((e.stateMask & SWT.CTRL) == SWT.CTRL)
						&& ((e.keyCode == SWT.ARROW_UP) || (e.keyCode == SWT.ARROW_DOWN));
				if ((e.character != (char) 0) || isHistoryCommand) {
					createInputField(e);
					e.doit = false;
				}
			}
		});
	}

	public Control getControl() {
		return consoleOutputViewer.getControl();
	}

	public void setActionBars(IActionBars bars) {
		final IToolBarManager toolBarManager = bars.getToolBarManager();
		{
			action = new Action("Backends") {
				@Override
				public int getStyle() {
					return AS_DROP_DOWN_MENU;
				}
			};
			action.setToolTipText("backend list");
			action.setImageDescriptor(PlatformUI.getWorkbench()
					.getSharedImages().getImageDescriptor(
							ISharedImages.IMG_OBJS_INFO_TSK));
			toolBarManager.add(action);
		}

		final IMenuManager menuManager = bars.getMenuManager();
		menuManager.add(action);
	}

	public void setFocus() {
	}

}
