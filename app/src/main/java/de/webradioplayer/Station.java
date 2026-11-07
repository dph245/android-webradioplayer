package de.webradioplayer;

import android.net.Uri;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

record Station(String title, String url) {
    private static final String XSPF = "http://xspf.org/ns/0/";

    static List<Station> read(InputStream input) throws IOException, XmlPullParserException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(input, null);
        parser.nextTag();
        parser.require(XmlPullParser.START_TAG, XSPF, "playlist");

        List<Station> stations = new ArrayList<>();
        boolean inTrackList = false;
        boolean inTrack = false;
        String title = "";
        Uri url = null;
        for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT;
                event = parser.next()) {
            if (!XSPF.equals(parser.getNamespace())) {
                continue;
            }
            int depth = parser.getDepth();
            String tag = parser.getName();
            if (depth == 1 && event == XmlPullParser.END_TAG && "playlist".equals(tag)) {
                parser.next();
                parser.require(XmlPullParser.END_DOCUMENT, null, null);
                return stations;
            }
            if (depth == 2 && "trackList".equals(tag)) {
                inTrackList = event == XmlPullParser.START_TAG;
            } else if (inTrackList && depth == 3 && "track".equals(tag)) {
                if (event == XmlPullParser.START_TAG) {
                    inTrack = true;
                    title = "";
                    url = null;
                } else if (event == XmlPullParser.END_TAG) {
                    if (url != null) {
                        stations.add(new Station(title.isEmpty() ? url.getHost() : title,
                                url.toString()));
                    }
                    inTrack = false;
                }
            } else if (inTrack && depth == 4 && event == XmlPullParser.START_TAG) {
                if ("title".equals(tag)) {
                    title = parser.nextText().trim();
                } else if ("location".equals(tag) && url == null) {
                    Uri candidate = Uri.parse(parser.nextText().trim()).normalizeScheme();
                    if (("https".equals(candidate.getScheme()) || "http".equals(candidate.getScheme()))
                            && candidate.getHost() != null && !candidate.getHost().isEmpty()) {
                        url = candidate;
                    }
                }
            }
        }
        throw new XmlPullParserException("Missing end of XSPF playlist");
    }
}
