package de.webradioplayer;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Log;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

final class Playlist {
    private static final String FILENAME = "streams.xspf";
    private static final int MAX_BYTES = 1024 * 1024;

    private Playlist() {}

    static List<Station> load(Context context) throws IOException, XmlPullParserException {
        try (InputStream input = openSaved(context)) {
            List<Station> stations = Station.read(input);
            if (!stations.isEmpty()) {
                return stations;
            }
        } catch (FileNotFoundException ignored) {
            // A fresh install uses the packaged playlist.
        } catch (IOException | XmlPullParserException error) {
            Log.w("Webradio", "Unable to read the imported playlist", error);
        }
        try (InputStream input = context.getAssets().open(FILENAME)) {
            return Station.read(input);
        }
    }

    static List<Station> importFrom(Context context, Uri uri)
            throws IOException, XmlPullParserException {
        byte[] contents;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("No playlist data");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (bytes.size() + count > MAX_BYTES) {
                    throw new IOException("Playlist exceeds 1 MiB");
                }
                bytes.write(buffer, 0, count);
            }
            contents = bytes.toByteArray();
        }
        List<Station> stations = Station.read(new ByteArrayInputStream(contents));
        if (stations.isEmpty()) {
            throw new IOException("Playlist has no HTTP or HTTPS stations");
        }
        save(context, contents);
        return stations;
    }

    private static synchronized InputStream openSaved(Context context) throws IOException {
        return importedFile(context).openRead();
    }

    private static synchronized void save(Context context, byte[] contents) throws IOException {
        AtomicFile file = importedFile(context);
        FileOutputStream output = file.startWrite();
        try {
            output.write(contents);
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
    }

    private static AtomicFile importedFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), FILENAME));
    }
}
