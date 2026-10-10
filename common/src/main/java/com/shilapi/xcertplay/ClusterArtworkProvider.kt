package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.media.clusterArtworkCoverFile
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the instrument cluster's album cover to the head unit's own processes.
 *
 * The cluster is not drawn by us: the vendor's DIM service (`com.ecarx.dim_interaction`) reads the
 * Uri we submit and opens it in *its* process. That reader is why this provider exists -- and why it
 * is now the *fallback*: on 2026-10-09 the `content://` handover was granted to both reader packages
 * and the vendor logged every new Uri, yet the instrument cluster never painted one of them, while the
 * earlier `file://` copy under `getExternalFilesDir(null)` had shown the cover (docs/_digest/38 §3).
 * `ClusterArtworkStore` therefore prefers that external file and hands over the `content://` Uri here
 * only when the external copy cannot be written or read back.
 *
 * It maps `content://<applicationId>.artwork/covers/<session>/<uuid>.jpg` back to the file
 * `ClusterArtworkStore` encoded under `<cacheDir>/cluster-artwork/`, and refuses everything else.
 * The shape is unchanged from the reference's `LegacyArtworkLoader` (docs/_digest/45 §2.4), so a car
 * that accepts either tier finds the same authority and path segments.
 *
 * Read-only by construction: [openFile] accepts `"r"` and nothing else, and the mutating operations
 * throw, so a reader can never write into the app's cache. There is no permission attribute: the two
 * reader packages are given access per-Uri by `grantUriPermission` instead, which is narrower than a
 * `<permission>` any app with the same name could hold. `android:exported="true"` is what lets those
 * two processes resolve the authority at all.
 *
 * The mapping itself lives in `:shared` (`clusterArtworkCoverFile`) because the writer in
 * `VendorClusterBridge` and this reader must agree on the shape byte for byte.
 */
class ClusterArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Cover images are read-only")
        return ParcelFileDescriptor.open(coverFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        // Validates the Uri first: an unreadable cover must not be announced as a JPEG.
        coverFile(uri)
        return "image/jpeg"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Cover images are read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Cover images are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Cover images are read-only")

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    private fun coverFile(uri: Uri): File {
        val context = context ?: throw FileNotFoundException("no context")
        return clusterArtworkCoverFile(
            cacheDir = context.cacheDir,
            packageName = context.packageName,
            scheme = uri.scheme,
            authority = uri.authority,
            pathSegments = uri.pathSegments,
            query = uri.query,
            fragment = uri.fragment,
        ) ?: throw FileNotFoundException(uri.toString())
    }
}
