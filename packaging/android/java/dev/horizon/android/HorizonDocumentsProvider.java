package dev.horizon.android;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Storage Access Framework (SAF) DocumentsProvider from Vasysik/horizon-android.
 */
public final class HorizonDocumentsProvider extends DocumentsProvider {
    private static final String TAG = "HorizonDocsProvider";
    private static final String ROOT_ID = "horizon_root";
    private static final String ROOT_DOC_ID = "root";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    };

    private static boolean nativeLoaded = false;

    private static synchronized void ensureNativeLoaded() {
        if (!nativeLoaded) {
            try {
                System.loadLibrary("horizon_android");
                nativeLoaded = true;
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "libhorizon_android.so not loaded in DocumentsProvider, using Java fallback: " + e.getMessage());
            }
        }
    }

    private static native String nativeCall(String home, String operation, String id, String arg) throws FileNotFoundException;
    private static native int nativeOpen(String home, String id, String mode) throws FileNotFoundException;

    @Override
    public boolean onCreate() {
        ensureNativeLoaded();
        return true;
    }

    private String getHomePath() {
        Context ctx = getContext();
        File filesDir = ctx != null ? ctx.getFilesDir() : null;
        return filesDir != null ? filesDir.getAbsolutePath() : "/data/user/0/dev.horizon.android/files";
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(Root.COLUMN_FLAGS,
            Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_ICON, R.drawable.ic_launcher);
        row.add(Root.COLUMN_TITLE, "HorizonZ Launcher (26.3)");
        row.add(Root.COLUMN_SUMMARY, "Worlds, Resource Packs, Mods & Logs");
        row.add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        String docId = (documentId == null || documentId.isEmpty()) ? ROOT_DOC_ID : documentId;

        if (nativeLoaded) {
            try {
                String jsonStr = nativeCall(getHomePath(), "stat", docId, "");
                if (jsonStr != null && !jsonStr.isEmpty()) {
                    JSONObject obj = new JSONObject(jsonStr);
                    includeJsonRow(result, obj);
                    return result;
                }
            } catch (Throwable ignored) {
            }
        }
        includeFallbackFile(result, docId);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        String parentId = (parentDocumentId == null || parentDocumentId.isEmpty()) ? ROOT_DOC_ID : parentDocumentId;

        if (nativeLoaded) {
            try {
                String jsonStr = nativeCall(getHomePath(), "list", parentId, "");
                if (jsonStr != null && !jsonStr.isEmpty()) {
                    JSONObject obj = new JSONObject(jsonStr);
                    JSONArray arr = obj.optJSONArray("entries");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            includeJsonRow(result, arr.getJSONObject(i));
                        }
                        return result;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        File parent = resolveFallbackFile(parentId);
        File[] children = parent.listFiles();
        if (children != null) {
            for (File child : children) {
                String childDocId = parentId.equals(ROOT_DOC_ID)
                    ? (ROOT_DOC_ID + "/" + child.getName())
                    : (parentId + "/" + child.getName());
                includeFallbackFile(result, childDocId);
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (nativeLoaded) {
            try {
                int fd = nativeOpen(getHomePath(), documentId, mode);
                if (fd >= 0) {
                    return ParcelFileDescriptor.adoptFd(fd);
                }
            } catch (Throwable ignored) {
            }
        }
        File target = resolveFallbackFile(documentId);
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = resolveFallbackFile(parentDocumentId);
        File target = new File(parent, displayName);
        try {
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                target.mkdirs();
            } else {
                target.getParentFile().mkdirs();
                target.createNewFile();
            }
        } catch (Exception e) {
            throw new FileNotFoundException("Failed to create document: " + e.getMessage());
        }
        return parentDocumentId.equals(ROOT_DOC_ID)
            ? (ROOT_DOC_ID + "/" + displayName)
            : (parentDocumentId + "/" + displayName);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File target = resolveFallbackFile(documentId);
        deleteRecursive(target);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        if (parentDocumentId == null || documentId == null) return false;
        return documentId.startsWith(parentDocumentId);
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private File resolveFallbackFile(String docId) {
        File home = new File(getHomePath());
        if (!home.exists()) home.mkdirs();
        if (docId == null || docId.isEmpty() || docId.equals(ROOT_DOC_ID)) {
            return home;
        }
        String rel = docId.startsWith("root/") ? docId.substring(5) : docId;
        return new File(home, rel);
    }

    private void includeJsonRow(MatrixCursor result, JSONObject obj) {
        boolean isDir = obj.optBoolean("is_dir", false);
        int flags = Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_WRITE;
        if (isDir) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;

        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, obj.optString("document_id", ROOT_DOC_ID));
        row.add(Document.COLUMN_MIME_TYPE,
            isDir ? Document.MIME_TYPE_DIR : obj.optString("mime_type", "application/octet-stream"));
        row.add(Document.COLUMN_DISPLAY_NAME, obj.optString("display_name", "HorizonZ"));
        row.add(Document.COLUMN_LAST_MODIFIED, obj.optLong("last_modified", System.currentTimeMillis()));
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, obj.optLong("size", 0L));
    }

    private void includeFallbackFile(MatrixCursor result, String docId) {
        File file = resolveFallbackFile(docId);
        boolean isDir = file.isDirectory() || docId.equals(ROOT_DOC_ID);
        int flags = Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_WRITE;
        if (isDir) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;

        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_MIME_TYPE, isDir ? Document.MIME_TYPE_DIR : "application/octet-stream");
        row.add(Document.COLUMN_DISPLAY_NAME, docId.equals(ROOT_DOC_ID) ? "HorizonZ" : file.getName());
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, file.length());
    }
}
