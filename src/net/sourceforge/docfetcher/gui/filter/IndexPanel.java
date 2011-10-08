/*******************************************************************************
 * Copyright (c) 2010, 2011 Tran Nam Quang.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Tran Nam Quang - initial API and implementation
 *******************************************************************************/

package net.sourceforge.docfetcher.gui.filter;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.sourceforge.docfetcher.UtilGlobal;
import net.sourceforge.docfetcher.enums.Img;
import net.sourceforge.docfetcher.enums.SettingsConf;
import net.sourceforge.docfetcher.gui.MultiFileLauncher;
import net.sourceforge.docfetcher.gui.indexing.IndexingDialog;
import net.sourceforge.docfetcher.gui.indexing.SingletonDialogFactory;
import net.sourceforge.docfetcher.model.Folder;
import net.sourceforge.docfetcher.model.Folder.FolderEvent;
import net.sourceforge.docfetcher.model.IndexRegistry;
import net.sourceforge.docfetcher.model.IndexRegistry.ExistingIndexesHandler;
import net.sourceforge.docfetcher.model.LuceneIndex;
import net.sourceforge.docfetcher.model.ViewNode;
import net.sourceforge.docfetcher.model.index.IndexingQueue;
import net.sourceforge.docfetcher.model.index.IndexingQueue.Rejection;
import net.sourceforge.docfetcher.model.index.Task.IndexAction;
import net.sourceforge.docfetcher.model.index.file.FileIndex;
import net.sourceforge.docfetcher.model.index.outlook.OutlookIndex;
import net.sourceforge.docfetcher.util.AppUtil;
import net.sourceforge.docfetcher.util.Event;
import net.sourceforge.docfetcher.util.Util;
import net.sourceforge.docfetcher.util.annotations.MutableCopy;
import net.sourceforge.docfetcher.util.annotations.NotNull;
import net.sourceforge.docfetcher.util.annotations.Nullable;
import net.sourceforge.docfetcher.util.annotations.RecursiveMethod;
import net.sourceforge.docfetcher.util.gui.ContextMenuManager;
import net.sourceforge.docfetcher.util.gui.InputLoop;
import net.sourceforge.docfetcher.util.gui.MenuAction;
import net.sourceforge.docfetcher.util.gui.viewer.SimpleTreeViewer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.sun.jna.platform.win32.Shell32Util;

public final class IndexPanel {
	
	private static abstract class DialogFactory extends SingletonDialogFactory<IndexingDialog> {
		public DialogFactory(Shell shell) {
			super(shell);
		}
	}
	
	public final Event<Void> evtCheckStatesChanged = new Event<Void>();
	public final Event<Set<String>> evtListDocuments = new Event<Set<String>>();
	public final Event<Rectangle> evtIndexingDialogMinimized = new Event<Rectangle>();
	public final Event<Void> evtIndexingDialogOpened = new Event<Void>();

	private final SimpleTreeViewer<ViewNode> viewer;
	private final Tree tree;
	private final IndexRegistry indexRegistry;
	private final DialogFactory dialogFactory;

	public IndexPanel(	@NotNull final Composite parent,
						@NotNull final IndexRegistry indexRegistry) {
		Util.checkNotNull(parent, indexRegistry);
		this.indexRegistry = indexRegistry;

		dialogFactory = new DialogFactory(parent.getShell()) {
			protected IndexingDialog createDialog(Shell shell) {
				IndexingDialog indexingDialog = new IndexingDialog(shell, indexRegistry);
				indexingDialog.evtDialogMinimized.add(new Event.Listener<Rectangle>() {
					public void update(Rectangle eventData) {
						evtIndexingDialogMinimized.fire(eventData);
					}
				});
				return indexingDialog;
			}
		};
		
		dialogFactory.evtDialogOpened.add(new Event.Listener<Void>() {
			public void update(Void eventData) {
				evtIndexingDialogOpened.fire(eventData);
			}
		});
		
		final Comparator<ViewNode> viewNodeComparator = new Comparator<ViewNode>() {
			public int compare(ViewNode o1, ViewNode o2) {
				String name1 = o1.getDisplayName();
				String name2 = o2.getDisplayName();
				return name1.compareTo(name2); // TODO now: use alphanum comparator
			}
		};

		viewer = new SimpleTreeViewer<ViewNode>(parent, SWT.CHECK | SWT.BORDER | SWT.MULTI) {
			protected Iterable<ViewNode> getChildren(ViewNode element) {
				return element.getChildren();
			};

			protected String getLabel(ViewNode element) {
				return element.getDisplayName();
			}
			
			protected boolean isChecked(ViewNode element) {
				return element.isChecked();
			}
			
			protected void setChecked(ViewNode element, boolean checked) {
				setCheckedRecursively(element, checked);
				setCheckedRecursively(viewer.getItem(element), checked);
				evtCheckStatesChanged.fire(null);
			}
			
			protected void sort(List<ViewNode> unsortedElements) {
				Collections.sort(unsortedElements, viewNodeComparator);
			}
		};
		tree = viewer.getControl();
		
		/*
		 * Update the tree viewer when the internal tree structure changes. This
		 * can be caused by an index update running in the background.
		 */
		Folder.evtFolderAdded.add(new Event.Listener<FolderEvent>() {
			public void update(final FolderEvent eventData) {
				Util.runSwtSafe(tree, new Runnable() {
					public void run() {
						viewer.add(eventData.parent, eventData.folder);
					}
				});
			}
		});
		Folder.evtFolderRemoved.add(new Event.Listener<FolderEvent>() {
			public void update(final FolderEvent eventData) {
				Util.runSwtSafe(tree, new Runnable() {
					public void run() {
						viewer.remove(eventData.folder);
					}
				});
			}
		});

		indexRegistry.addListeners(new ExistingIndexesHandler() {
			public void handleExistingIndexes(List<LuceneIndex> indexes) {
				for (LuceneIndex index : indexes)
					viewer.addRoot(index);
			}
		}, new Event.Listener<LuceneIndex>() {
			public void update(final LuceneIndex eventData) {
				Util.runSwtSafe(tree, new Runnable() {
					public void run() {
						viewer.addRoot(eventData);
					}
				});
			}
		}, new Event.Listener<List<LuceneIndex>>() {
			public void update(final List<LuceneIndex> eventData) {
				Util.runSwtSafe(tree, new Runnable() {
					public void run() {
						viewer.remove(UtilGlobal.<ViewNode> convert(eventData));
					}
				});
			}
		});

		initContextMenu();
	}

	private void initContextMenu() {
		ContextMenuManager menuManager = new ContextMenuManager(tree);
		// TODO pre-release: check that enabled states of menu items are set correctly
		// TODO pre-release: check that keyboard shortcuts of menu items are set correctly
		// TODO i18n menu item labels
		
		Menu indexSubMenu = menuManager.addSubmenu(new MenuAction(
			"Create Index From"));
		
		menuManager.add(indexSubMenu, new MenuAction(
			Img.FOLDER.get(), "Folder...") {
			public void run() {
				createFileTaskFromDialog(
					tree.getShell(), indexRegistry, dialogFactory, true);
			}
		});
		
		menuManager.addSeparator(indexSubMenu);
		
		menuManager.add(indexSubMenu, new MenuAction(
			Img.PACKAGE.get(), "Archive...") {
			public void run() {
				createFileTaskFromDialog(
					tree.getShell(), indexRegistry, dialogFactory, false);
			}
		});
		
		menuManager.add(indexSubMenu, new MenuAction(
			Img.EMAIL.get(), "Outlook PST...") {
			public void run() {
				createOutlookTaskFromDialog(
					tree.getShell(), indexRegistry, dialogFactory);
			}
		});
		
		// TODO now: Implement keyboard shortcut
		menuManager.add(indexSubMenu, new MenuAction(
			Img.CLIPBOARD.get(), "Clipboard...\tCtrl+V") {
			public void run() {
				createTaskFromClipboard(
					tree.getShell(), indexRegistry, dialogFactory);
			}
		});
		
		menuManager.addSeparator();
		
		class UpdateOrRebuildAction extends MenuAction {
			private final boolean isUpdate;
			public UpdateOrRebuildAction(String label, boolean isUpdate) {
				super(label);
				this.isUpdate = isUpdate;
			}
			public boolean isEnabled() {
				return isOnlyIndexesSelected();
			}
			public void run() {
				IndexingQueue queue = indexRegistry.getQueue();
				IndexAction action = isUpdate
					? IndexAction.UPDATE
					: IndexAction.REBUILD;
				List<LuceneIndex> sel = getSelectedIndexes();
				if (!isUpdate)
					indexRegistry.removeIndexes(sel, false);
				
				/*
				 * The indexing dialog must be opened *before* adding the tasks
				 * to the queue. Otherwise, it may happen that the tasks are
				 * completed before the dialog is opened, in which case the
				 * dialog will be empty and won't close automatically.
				 */
				dialogFactory.open();
				
				for (LuceneIndex index : sel) {
					Rejection rejection = queue.addTask(index, action);
					
					if (action == IndexAction.REBUILD)
						assert rejection == null;
				}
			}
		}
		menuManager.add(new UpdateOrRebuildAction("Update Index...", true));
		menuManager.add(new UpdateOrRebuildAction("Rebuild Index...", false));
		
		menuManager.addSeparator();
		
		menuManager.add(new MenuAction("Remove Index...") {
			public boolean isEnabled() {
				return isOnlyIndexesSelected();
			}
			public void run() {
				List<LuceneIndex> selectedIndexes = getSelectedIndexes();
				assert !selectedIndexes.isEmpty();
				// TODO i18n
				if (AppUtil.showConfirmation("remove_sel_indexes", false))
					indexRegistry.removeIndexes(selectedIndexes, true);
			}
		});
		
		menuManager.add(new MenuAction("Remove Orphaned Indexes") {
			public boolean isEnabled() {
				return tree.getItemCount() > 0;
			}
			public void run() {
				List<LuceneIndex> indexes = indexRegistry.getIndexes();
				List<LuceneIndex> toRemove = new ArrayList<LuceneIndex>(indexes.size());
				for (LuceneIndex index : indexes)
					if (!index.getCanonicalRootFile().exists())
						toRemove.add(index);
				if (toRemove.isEmpty())
					return;
				// TODO i18n; also display the indexes to be removed
				String msg = "remove_orphaned_indexes_msg";
				if (AppUtil.showConfirmation(msg, false))
					indexRegistry.removeIndexes(toRemove, true);
			}
		});
		
		menuManager.addSeparator();
		
		class CheckAllAction extends MenuAction {
			private final boolean checkAll;
			public CheckAllAction(boolean checkAll) {
				super(checkAll ? "Check All" : "Uncheck All");
				this.checkAll = checkAll;
			}
			public boolean isEnabled() {
				return tree.getItemCount() > 0;
			}
			public void run() {
				for (ViewNode element : viewer.getRoots()) {
					setCheckedRecursively(element, checkAll);
					setCheckedRecursively(viewer.getItem(element), checkAll);
				}
				evtCheckStatesChanged.fire(null);
			}
		}
		menuManager.add(new CheckAllAction(true));
		menuManager.add(new CheckAllAction(false));
		
		menuManager.add(new MenuAction("Toggle Individual Check State") {
			public boolean isEnabled() {
				return !viewer.getSelection().isEmpty();
			}
			public void run() {
				boolean allChecked = true;
				List<ViewNode> selection = viewer.getSelection();
				for (ViewNode element : selection) {
					if (!element.isChecked()) {
						allChecked = false;
						break;
					}
				}
				for (ViewNode element : selection) {
					element.setChecked(!allChecked);
					viewer.getItem(element).setChecked(!allChecked);
				}
				evtCheckStatesChanged.fire(null);
			}
		});
		
		menuManager.addSeparator();
		
		menuManager.add(new MenuAction("Open Folder") {
			public boolean isEnabled() {
				return !viewer.getSelection().isEmpty();
			}
			public void run() {
				MultiFileLauncher launcher = new MultiFileLauncher();
				for (ViewNode element : viewer.getSelection()) {
					if (element instanceof LuceneIndex) {
						LuceneIndex index = (LuceneIndex) element;
						File rootFile = index.getCanonicalRootFile();
						if (!rootFile.exists())
							launcher.addMissing(rootFile);
						else
							launcher.addFile(rootFile);
					}
					else {
						Folder<?, ?> folder = (Folder<?, ?>) element;
						File rootFile = folder.getRoot().getPath().getCanonicalFile();
						if (!rootFile.exists())
							launcher.addMissing(rootFile);
						else
							launcher.addFile(getNearestFile(folder));
					}
				}
				launcher.launch();
			}
		});
		
		menuManager.add(new MenuAction("List Documents") {
			public boolean isEnabled() {
				return !viewer.getSelection().isEmpty();
			}
			public void run() {
				List<ViewNode> selection = viewer.getSelection();
				Set<String> uids = new HashSet<String>();
				for (ViewNode viewNode : selection)
					uids.addAll(viewNode.getDocumentIds());
				evtListDocuments.fire(uids);
			}
		});
		
		/*
		 * Hide the context menu if the tree element on which the user opened
		 * the menu is removed by an index update running in the background.
		 */
		final Menu menu = tree.getMenu();
		final ViewNode[] clickedNode = { null };
		final Event.Listener<FolderEvent> menuHider = new Event.Listener<FolderEvent>() {
			public void update(FolderEvent eventData) {
				if (eventData == null)
					return;
				if (eventData.folder != clickedNode[0])
					return;
				Util.runSwtSafe(tree, new Runnable() {
					public void run() {
						menu.setVisible(false);
					}
				});
			}
		};
		menu.addMenuListener(new MenuAdapter() {
			public void menuShown(MenuEvent e) {
				Display display = tree.getDisplay();
				Point pos = display.getCursorLocation();
				pos = display.map(null, tree, pos);
				// TODO now: test on Windows that the model node is always found
				clickedNode[0] = viewer.getElement(pos);
				if (clickedNode[0] != null)
					Folder.evtFolderRemoved.add(menuHider);
			}
			public void menuHidden(MenuEvent e) {
				Folder.evtFolderRemoved.remove(menuHider);
			}
		});
	}
	
	private boolean isOnlyIndexesSelected() {
		List<ViewNode> selection = viewer.getSelection();
		if (selection.isEmpty())
			return false;
		
		for (ViewNode viewNode : selection)
			if (!viewNode.isIndex())
				return false;
		return true;
	}
	
	@MutableCopy
	@NotNull
	private List<LuceneIndex> getSelectedIndexes() {
		List<ViewNode> selection = viewer.getSelection();
		List<LuceneIndex> rootSelection = new ArrayList<LuceneIndex>(selection.size());
		for (ViewNode viewNode : selection)
			if (viewNode.isIndex())
				rootSelection.add((LuceneIndex) viewNode);
		return rootSelection;
	}
	
	@RecursiveMethod
	private void setCheckedRecursively(	@NotNull ViewNode element,
										boolean checked) {
		element.setChecked(checked);
		for (ViewNode child : element.getChildren())
			setCheckedRecursively(child, checked);
	}
	
	@RecursiveMethod
	private void setCheckedRecursively(@NotNull TreeItem item, boolean checked) {
		item.setChecked(checked);
		for (TreeItem child : item.getItems())
			setCheckedRecursively(child, checked);
	}
	
	// Returns nearest parent file that is an existing directory or the
	// root file. The path of the returned file is in canonical form.
	@NotNull
	@RecursiveMethod
	private static File getNearestFile(@NotNull Folder<?, ?> folder) {
		File file = folder.getPath().getCanonicalFile();
		if (file.exists())
			return file;
		Folder<?, ?> parent = folder.getParent();
		if (parent == null)
			return file;
		return getNearestFile(parent);
	}

	@NotNull
	public Tree getControl() {
		return tree;
	}
	
	@NotNull
	public IndexRegistry getIndexRegistry() {
		return indexRegistry;
	}

	public static boolean createFileTaskFromDialog(	@NotNull final Shell shell,
													@NotNull final IndexRegistry indexRegistry,
													@Nullable final DialogFactory dialogFactory,
													final boolean dirNotFile) {
		String lastPath = SettingsConf.Str.LastIndexedFolder.get();
		if (!new File(lastPath).exists())
			lastPath = SettingsConf.Str.LastIndexedFolder.defaultValue;

		Object success = new InputLoop<Object>() {
			protected String getNewValue(String lastValue) {
				if (dirNotFile) {
					DirectoryDialog dialog = new DirectoryDialog(shell);
					dialog.setText("scope_folder_title"); // TODO i18n
					dialog.setMessage("scope_folder_msg"); // TODO i18n
					dialog.setFilterPath(lastValue);
					return dialog.open();
				}
				else {
					FileDialog dialog = new FileDialog(shell);
					dialog.setText("scope_folder_title"); // TODO i18n
					dialog.setFilterPath(lastValue);
					return dialog.open();
				}
			}

			protected String getDenyMessage(String newValue) {
				File indexParentDir = indexRegistry.getIndexParentDir();
				File targetFile = new File(newValue);
				FileIndex index = new FileIndex(indexParentDir, targetFile);
				Rejection rejection = indexRegistry.getQueue().addTask(
					index, IndexAction.CREATE);
				if (rejection == null)
					return null;
				return "Rejected!"; // TODO i18n
			}

			protected Object onAccept(String newValue) {
				String absPath = Util.getSystemAbsPath(newValue);
				SettingsConf.Str.LastIndexedFolder.set(absPath); // TODO test
				if (dialogFactory != null)
					dialogFactory.open();
				return new Object();
			}
		}.run(lastPath);
		
		return success != null;
	}

	public static boolean createOutlookTaskFromDialog(	@NotNull final Shell shell,
														@NotNull final IndexRegistry indexRegistry,
														@Nullable final DialogFactory dialogFactory) {
		String lastPath = SettingsConf.Str.LastIndexedPSTFile.get();
		if (!lastPath.equals("") && !new File(lastPath).isFile())
			lastPath = SettingsConf.Str.LastIndexedPSTFile.defaultValue;

		File pstFile = getOutlookPSTFile();
		if (pstFile != null) {
			// TODO now: On Windows, if the path is very long, the message dialog's width is too large
			// TODO now: Don't open the file chooser if the user clicks OK on this message dialog
			String msg = "PST file found: " + pstFile + ". Index this file?";
			if (AppUtil.showConfirmation(msg, false)) // TODO i18n
				lastPath = pstFile.getPath();
		}

		Object success = new InputLoop<Object>() {
			protected String getNewValue(String lastValue) {
				FileDialog dialog = new FileDialog(shell);
				// TODO i18n set dialog text and message
				dialog.setFilterExtensions(new String[] { "*.pst" });
				dialog.setFilterNames(new String[] { "Outlook Personal Storage Table (*.pst)" });
				if (!lastValue.equals(""))
					dialog.setFilterPath(lastValue);
				return dialog.open();
			}

			protected String getDenyMessage(String newValue) {
				File indexParentDir = indexRegistry.getIndexParentDir();
				File pstFile = new File(newValue);
				OutlookIndex index = new OutlookIndex(indexParentDir, pstFile);
				Rejection rejection = indexRegistry.getQueue().addTask(
					index, IndexAction.CREATE);
				if (rejection == null)
					return null;
				return "Rejected!"; // TODO i18n
			}

			protected Void onAccept(String newValue) {
				String path = Util.getSystemAbsPath(newValue);
				SettingsConf.Str.LastIndexedPSTFile.set(path); // TODO test
				if (dialogFactory != null)
					dialogFactory.open();
				return null;
			}
		}.run(lastPath);
		
		return success != null;
	}

	/**
	 * Returns a file object representing the Outlook PST file of the Outlook
	 * instance installed on the system, or null if no such file is found. This
	 * method always returns null on non-Windows platforms.
	 */
	@Nullable
	private static File getOutlookPSTFile() {
		if (!Util.IS_WINDOWS)
			return null;
		/*
		 * Win7/Vista:
		 * C:\Users\<UserName>\AppData\Local\Microsoft\Outlook\Outlook.pst
		 * 
		 * WinXP/2000: C:\Documents and Settings\<UserName>\Local
		 * Settings\Application Data\Microsoft\Outlook\Outlook.pst
		 * 
		 * CSIDL_LOCAL_APPDATA = 0x001c
		 */
		String localAppData = Shell32Util.getFolderPath(0x001c);
		String pstFilePath = Util.joinPath(
			localAppData, "Microsoft/Outlook/Outlook.pst");
		File pstFile = new File(pstFilePath);
		return pstFile.isFile() ? pstFile : null;
	}
	
	public static void createTaskFromClipboard(	@NotNull final Shell shell,
												@NotNull final IndexRegistry indexRegistry,
												@Nullable final DialogFactory dialogFactory) {
		List<File> files = Util.getFilesFromClipboard();
		if (files == null) {
			AppUtil.showError("No files found on the clipboard.", true, true); // TODO i18n
			return;
		}
		if (files.isEmpty())
			throw new IllegalStateException();
		
		File indexParentDir = indexRegistry.getIndexParentDir();
		File file = files.get(0); // Ignore all but the first file
		
		LuceneIndex index;
		if (Util.hasExtension(file.getName(), "pst"))
			index = new OutlookIndex(indexParentDir, file);
		else
			index = new FileIndex(indexParentDir, file);
		
		Rejection rejection = indexRegistry.getQueue().addTask(
			index, IndexAction.CREATE);
		
		if (rejection != null)
			AppUtil.showError("Rejected!", true, true); // TODO i18n
		else if (dialogFactory != null)
			dialogFactory.open();
	}
	
	public void openIndexingDialog() {
		dialogFactory.open();
	}

}
