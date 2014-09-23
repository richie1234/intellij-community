/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Created by IntelliJ IDEA.
 * User: yole
 * Date: 31.07.2006
 * Time: 13:24:17
 */
package com.intellij.openapi.vcs.impl;

import com.intellij.lifecycle.PeriodicalTasksCloser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.event.EditorFactoryAdapter;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.ex.DocumentBulkUpdateListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.impl.DirectoryIndex;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.committed.AbstractCalledLater;
import com.intellij.openapi.vcs.ex.LineStatusTracker;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileAdapter;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.concurrency.QueueProcessorRemovePartner;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class LineStatusTrackerManager implements ProjectComponent, LineStatusTrackerManagerI {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.vcs.impl.LineStatusTrackerManager");

  @NonNls protected static final String IGNORE_CHANGEMARKERS_KEY = "idea.ignore.changemarkers";

  @NotNull public final Object myLock = new Object();

  @NotNull private final Project myProject;
  @NotNull private final ProjectLevelVcsManager myVcsManager;
  @NotNull private final VcsBaseContentProvider myStatusProvider;
  @NotNull private final Application myApplication;
  @NotNull private final FileEditorManager myFileEditorManager;
  @NotNull private final Disposable myDisposable;

  @NotNull private final Map<Document, LineStatusTracker> myLineStatusTrackers;

  // !!! no state queries and self lock for add/remove
  // removal from here - not under write action
  @NotNull private final QueueProcessorRemovePartner<Document, BaseRevisionLoader> myPartner;
  private long myLoadCounter;

  public static LineStatusTrackerManagerI getInstance(final Project project) {
    return PeriodicalTasksCloser.getInstance().safeGetComponent(project, LineStatusTrackerManagerI.class);
  }

  public LineStatusTrackerManager(@NotNull final Project project,
                                  @NotNull final ProjectLevelVcsManager vcsManager,
                                  @NotNull final VcsBaseContentProvider statusProvider,
                                  @NotNull final Application application,
                                  @NotNull final FileEditorManager fileEditorManager,
                                  @SuppressWarnings("UnusedParameters") DirectoryIndex makeSureIndexIsInitializedFirst) {
    myLoadCounter = 0;
    myProject = project;
    myVcsManager = vcsManager;
    myStatusProvider = statusProvider;
    myApplication = application;
    myFileEditorManager = fileEditorManager;

    myLineStatusTrackers = new HashMap<Document, LineStatusTracker>();
    myPartner = new QueueProcessorRemovePartner<Document, BaseRevisionLoader>(myProject, new Consumer<BaseRevisionLoader>() {
      @Override
      public void consume(BaseRevisionLoader baseRevisionLoader) {
        baseRevisionLoader.run();
      }
    });

    project.getMessageBus().connect().subscribe(DocumentBulkUpdateListener.TOPIC, new DocumentBulkUpdateListener.Adapter() {
      public void updateStarted(@NotNull final Document doc) {
        final LineStatusTracker tracker = getLineStatusTracker(doc);
        if (tracker != null) tracker.startBulkUpdate();
      }

      public void updateFinished(@NotNull final Document doc) {
        final LineStatusTracker tracker = getLineStatusTracker(doc);
        if (tracker != null) tracker.finishBulkUpdate();
      }
    });

    myDisposable = new Disposable() {
      @Override
      public void dispose() {
        synchronized (myLock) {
          for (final LineStatusTracker tracker : myLineStatusTrackers.values()) {
            tracker.release();
          }

          myLineStatusTrackers.clear();
          myPartner.clear();
        }
      }
    };
    Disposer.register(myProject, myDisposable);
  }

  public void projectOpened() {
    StartupManager.getInstance(myProject).registerPreStartupActivity(new Runnable() {
      @Override
      public void run() {
        final MyFileStatusListener fileStatusListener = new MyFileStatusListener();
        final EditorFactoryListener editorFactoryListener = new MyEditorFactoryListener();
        final MyVirtualFileListener virtualFileListener = new MyVirtualFileListener();
        final EditorColorsListener editorColorsListener = new MyEditorColorsListener();

        final FileStatusManager fsManager = FileStatusManager.getInstance(myProject);
        fsManager.addFileStatusListener(fileStatusListener, myDisposable);

        final EditorFactory editorFactory = EditorFactory.getInstance();
        editorFactory.addEditorFactoryListener(editorFactoryListener, myDisposable);

        final VirtualFileManager virtualFileManager = VirtualFileManager.getInstance();
        virtualFileManager.addVirtualFileListener(virtualFileListener, myDisposable);

        final EditorColorsManager editorColorsManager = EditorColorsManager.getInstance();
        editorColorsManager.addEditorColorsListener(editorColorsListener, myDisposable);
      }
    });
  }

  public void projectClosed() {
  }

  @NonNls
  @NotNull
  public String getComponentName() {
    return "LineStatusTrackerManager";
  }

  public void initComponent() {
  }

  public void disposeComponent() {
  }

  public boolean isDisabled() {
    return !myProject.isOpen() || myProject.isDisposed();
  }

  @Override
  public LineStatusTracker getLineStatusTracker(final Document document) {
    myApplication.assertReadAccessAllowed();
    if (isDisabled()) return null;

    synchronized (myLock) {
      return myLineStatusTrackers.get(document);
    }
  }

  private void resetTracker(@NotNull final VirtualFile virtualFile) {
    myApplication.assertReadAccessAllowed();
    if (isDisabled()) return;

    final Document document = FileDocumentManager.getInstance().getCachedDocument(virtualFile);
    if (document == null) {
      log("Skipping resetTracker() because no cached document for " + virtualFile.getPath());
      return;
    }

    log("resetting tracker for file " + virtualFile.getPath());

    final boolean editorOpened = myFileEditorManager.isFileOpen(virtualFile);
    final boolean shouldBeInstalled = shouldBeInstalled(virtualFile) && editorOpened;

    synchronized (myLock) {
      final LineStatusTracker tracker = myLineStatusTrackers.get(document);

      if (tracker == null && (!shouldBeInstalled)) return;

      if (tracker != null) {
        releaseTracker(document);
      }
      if (shouldBeInstalled) {
        installTracker(virtualFile, document);
      }
    }
  }

  private void releaseTracker(@NotNull final Document document) {
    if (isDisabled()) return;

    synchronized (myLock) {
      myPartner.remove(document);
      final LineStatusTracker tracker = myLineStatusTrackers.remove(document);
      if (tracker != null) {
        tracker.release();
      }
    }
  }

  private boolean shouldBeInstalled(@Nullable final VirtualFile virtualFile) {
    ApplicationManager.getApplication().assertIsDispatchThread();

    if (virtualFile == null || virtualFile instanceof LightVirtualFile) return false;
    if (!virtualFile.isInLocalFileSystem()) return false;
    if (isDisabled()) return false;
    final FileStatusManager statusManager = FileStatusManager.getInstance(myProject);
    if (statusManager == null) return false;
    final AbstractVcs activeVcs = myVcsManager.getVcsFor(virtualFile);
    if (activeVcs == null) {
      log("installTracker() for file " + virtualFile.getPath() + " failed: no active VCS");
      return false;
    }
    final FileStatus status = statusManager.getStatus(virtualFile);
    if (status == FileStatus.NOT_CHANGED || status == FileStatus.ADDED || status == FileStatus.UNKNOWN || status == FileStatus.IGNORED) {
      log("installTracker() for file " + virtualFile.getPath() + " failed: status=" + status);
      return false;
    }
    return true;
  }

  private void installTracker(@NotNull final VirtualFile virtualFile, @NotNull final Document document) {
    synchronized (myLock) {
      if (myLineStatusTrackers.containsKey(document)) return;
      assert !myPartner.containsKey(document);

      final LineStatusTracker tracker = LineStatusTracker.createOn(virtualFile, document, myProject);
      myLineStatusTrackers.put(document, tracker);

      startAlarm(document, virtualFile);
    }
  }

  private void startAlarm(@NotNull final Document document, @NotNull final VirtualFile virtualFile) {
    myApplication.assertReadAccessAllowed();

    synchronized (myLock) {
      myPartner.add(document, new BaseRevisionLoader(document, virtualFile));
    }
  }

  private class BaseRevisionLoader implements Runnable {
    @NotNull private final VirtualFile myVirtualFile;
    @NotNull private final Document myDocument;

    private BaseRevisionLoader(@NotNull final Document document, @NotNull final VirtualFile virtualFile) {
      myDocument = document;
      myVirtualFile = virtualFile;
    }

    @Override
    public void run() {
      if (isDisabled()) return;

      if (!myVirtualFile.isValid()) {
        log("installTracker() for file " + myVirtualFile.getPath() + " failed: virtual file not valid");
        reportTrackerBaseLoadFailed();
        return;
      }

      final VcsRevisionNumber baseRevision = myStatusProvider.getBaseRevision(myVirtualFile);
      if (baseRevision == null) {
        log("installTracker() for file " + myVirtualFile.getPath() + " failed: null returned for base revision number");
        reportTrackerBaseLoadFailed();
        return;
      }
      // loads are sequential (in single threaded QueueProcessor);
      // so myLoadCounter can't take less value for greater base revision -> the only thing we want from it
      final LineStatusTracker.RevisionPack revisionPack = new LineStatusTracker.RevisionPack(myLoadCounter, baseRevision);
      ++myLoadCounter;

      final String lastUpToDateContent = myStatusProvider.getBaseVersionContent(myVirtualFile);
      if (lastUpToDateContent == null) {
        log("installTracker() for file " + myVirtualFile.getPath() + " failed: no up to date content");
        reportTrackerBaseLoadFailed();
        return;
      }

      final String converted = StringUtil.convertLineSeparators(lastUpToDateContent);
      final Runnable runnable = new Runnable() {
        public void run() {
          synchronized (myLock) {
            log("initializing tracker for file " + myVirtualFile.getPath());
            final LineStatusTracker tracker = myLineStatusTrackers.get(myDocument);
            if (tracker != null) {
              tracker.initialize(converted, revisionPack);
            }
          }
        }
      };
      nonModalAliveInvokeLater(runnable);
    }

    private void nonModalAliveInvokeLater(@NotNull Runnable runnable) {
      myApplication.invokeLater(runnable, ModalityState.NON_MODAL, new Condition() {
        @Override
        public boolean value(final Object ignore) {
          return isDisabled();
        }
      });
    }

    private void reportTrackerBaseLoadFailed() {
      synchronized (myLock) {
        log("base revision load failed for file " + myVirtualFile.getPath());
        final LineStatusTracker tracker = myLineStatusTrackers.get(myDocument);
        if (tracker != null) {
          tracker.baseRevisionLoadFailed();
        }
      }
    }
  }

  private void resetTrackersForOpenFiles() {
    myApplication.assertReadAccessAllowed();
    if (isDisabled()) return;

    final VirtualFile[] openFiles = myFileEditorManager.getOpenFiles();
    for (final VirtualFile openFile : openFiles) {
      resetTracker(openFile);
    }
  }

  private class MyFileStatusListener implements FileStatusListener {
    public void fileStatusesChanged() {
      if (myProject.isDisposed()) return;
      log("LineStatusTrackerManager: fileStatusesChanged");
      resetTrackersForOpenFiles();
    }

    public void fileStatusChanged(@NotNull VirtualFile virtualFile) {
      resetTracker(virtualFile);
    }
  }

  private class MyEditorFactoryListener extends EditorFactoryAdapter {
    public void editorCreated(@NotNull EditorFactoryEvent event) {
      // note that in case of lazy loading of configurables, this event can happen
      // outside of EDT, so the EDT check mustn't be done here
      Editor editor = event.getEditor();
      if (editor.getProject() != null && editor.getProject() != myProject) return;
      final Document document = editor.getDocument();
      final VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);

      new AbstractCalledLater(myProject, ModalityState.NON_MODAL) {
        @Override
        public void run() {
          if (shouldBeInstalled(virtualFile)) {
            assert virtualFile != null;
            installTracker(virtualFile, document);
          }
        }
      }.callMe();
    }

    public void editorReleased(@NotNull EditorFactoryEvent event) {
      final Editor editor = event.getEditor();
      if (editor.getProject() != null && editor.getProject() != myProject) return;
      final Document doc = editor.getDocument();
      final Editor[] editors = event.getFactory().getEditors(doc, myProject);
      if (editors.length == 0) {
        new AbstractCalledLater(myProject, ModalityState.NON_MODAL) {
          @Override
          public void run() {
            releaseTracker(doc);
          }
        }.callMe();
      }
    }
  }

  private class MyVirtualFileListener extends VirtualFileAdapter {
    public void beforeContentsChange(@NotNull VirtualFileEvent event) {
      if (event.isFromRefresh()) {
        resetTracker(event.getFile());
      }
    }
  }

  private class MyEditorColorsListener implements EditorColorsListener {
    public void globalSchemeChange(EditorColorsScheme scheme) {
      resetTrackersForOpenFiles();
    }
  }

  private static void log(final String s) {
    if (LOG.isDebugEnabled()) {
      LOG.debug(s);
    }
  }
}
