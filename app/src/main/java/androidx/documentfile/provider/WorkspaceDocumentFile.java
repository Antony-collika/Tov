package androidx.documentfile.provider;

import android.net.Uri;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter used by Tov to expose multiple SAF trees under one virtual workspace root.
 * Kept in this package so it can call DocumentFile's package-private constructor.
 */
public final class WorkspaceDocumentFile extends DocumentFile {
    private final boolean workspaceRoot;
    private final String mountName;
    private final Uri treeUri;
    private final DocumentFile delegate;
    private final List<WorkspaceDocumentFile> mounts;

    private WorkspaceDocumentFile(
            boolean workspaceRoot,
            String mountName,
            Uri treeUri,
            DocumentFile delegate,
            List<WorkspaceDocumentFile> mounts
    ) {
        super(null);
        this.workspaceRoot = workspaceRoot;
        this.mountName = mountName;
        this.treeUri = treeUri;
        this.delegate = delegate;
        this.mounts = mounts;
    }

    public static WorkspaceDocumentFile root(List<WorkspaceDocumentFile> mounts) {
        return new WorkspaceDocumentFile(true, null, null, null, new ArrayList<>(mounts));
    }

    public static WorkspaceDocumentFile mount(String name, Uri treeUri, DocumentFile delegate) {
        return new WorkspaceDocumentFile(false, name, treeUri, delegate, null);
    }

    public boolean isWorkspaceRoot() {
        return workspaceRoot;
    }

    public boolean isWorkspaceMount() {
        return !workspaceRoot;
    }

    public String getMountName() {
        return mountName;
    }

    public Uri getTreeUri() {
        return treeUri;
    }

    @Override public Uri getUri() {
        return workspaceRoot ? Uri.parse("tov-workspace://root") : delegate.getUri();
    }
    @Override public String getName() {
        return workspaceRoot ? null : mountName;
    }
    @Override public String getType() {
        return workspaceRoot ? null : delegate.getType();
    }
    @Override public boolean isDirectory() { return true; }
    @Override public boolean isFile() { return false; }
    @Override public boolean isVirtual() { return false; }
    @Override public long lastModified() { return workspaceRoot ? 0L : delegate.lastModified(); }
    @Override public long length() { return workspaceRoot ? 0L : delegate.length(); }
    @Override public boolean canRead() {
        if (!workspaceRoot) return delegate.canRead();
        for (WorkspaceDocumentFile mount : mounts) if (mount.canRead()) return true;
        return false;
    }
    @Override public boolean canWrite() {
        if (!workspaceRoot) return delegate.canWrite();
        for (WorkspaceDocumentFile mount : mounts) if (mount.canWrite()) return true;
        return false;
    }
    @Override public boolean delete() { return false; }
    @Override public boolean exists() { return workspaceRoot || delegate.exists(); }
    @Override public boolean renameTo(String displayName) { return false; }
    @Override public DocumentFile createFile(String mimeType, String displayName) {
        return workspaceRoot ? null : delegate.createFile(mimeType, displayName);
    }
    @Override public DocumentFile createDirectory(String displayName) {
        return workspaceRoot ? null : delegate.createDirectory(displayName);
    }
    @Override public DocumentFile[] listFiles() {
        if (!workspaceRoot) return delegate.listFiles();
        return mounts.toArray(new DocumentFile[0]);
    }
    @Override public DocumentFile findFile(String displayName) {
        if (!workspaceRoot) return delegate.findFile(displayName);
        for (WorkspaceDocumentFile mount : mounts) {
            if (displayName.equals(mount.getMountName())) return mount;
        }
        return null;
    }
}
