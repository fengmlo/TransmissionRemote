package net.yupol.transmissionremote.app.transport.request;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class TrackerAddRequest extends Request<Void> {

    private static final String TAG = TrackerAddRequest.class.getSimpleName();
    private int torrentId;
    private String url;

    public TrackerAddRequest(int torrentId, String url) {
        super(Void.class);
        this.torrentId = torrentId;
        this.url = url;
    }

    @Override
    protected String getMethod() {
        return "torrent-set";
    }

    @Override
    protected JSONObject getArguments() {
        JSONObject args = new JSONObject();
        try {
            args.put("ids", new JSONArray().put(torrentId));
            JSONArray jsonArray = new JSONArray();
            if (url.contains("\n")) {
                String[] split = url.split("\n");
                for (String s : split) {
                    if (TextUtils.isEmpty(s)) continue;
                    jsonArray.put(s.trim());
                }
            }
            args.put("trackerAdd", jsonArray);
        } catch (JSONException e) {
            Log.e(TAG, "Error while creating json object", e);
            return null;
        }

        return args;
    }
}
