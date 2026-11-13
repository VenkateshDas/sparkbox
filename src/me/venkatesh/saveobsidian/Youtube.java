package me.venkatesh.saveobsidian;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What a YouTube link is made of: title, channel, description, and the spoken words. The words come from the video's captions when it has
 * any (written or automatic); otherwise the audio is downloaded and transcribed. Uses YouTube's own player service, the one its mobile apps use.
 */
final class Youtube {
    /** Audio fetched for transcription is capped; at YouTube's smallest audio rate this is about half an hour. */
    static final int AUDIO_BYTES = 12 * 1024 * 1024;

    static final class Info {
        String id = "", title = "", author = "", description = "", transcript = "", how = "none", note = "";
        int seconds;
        /** True once the words were really looked for (captions found, or audio transcription tried). Without it the text has no Transcript heading, so a later run knows to try. */
        boolean tried;

        /** The text kept for the link: facts first, then what was said. The "### Transcript" heading marks that the words were looked for. */
        String text() {
            StringBuilder s = new StringBuilder();
            if (!author.isEmpty()) s.append("Channel: ").append(author).append('\n');
            if (seconds > 0) s.append("Length: ").append(seconds / 60).append(" min\n");
            if (!description.isEmpty()) s.append("\n### Description\n").append(description.trim()).append('\n');
            if (!tried) return s.toString();
            s.append("\n### Transcript\n");
            if (transcript.isEmpty()) s.append("(No transcript was available for this video.").append(note.isEmpty() ? "" : " " + note + ".").append(")\n");
            else s.append(how.equals("audio") ? "(Transcribed from the audio" + (note.isEmpty() ? "" : "; " + note) + ".)\n\n" : "(From the video's captions.)\n\n").append(transcript).append('\n');
            return s.toString();
        }
    }

    private static final String[][] CLIENTS = {
        {"{\"clientName\":\"ANDROID\",\"clientVersion\":\"20.10.38\",\"androidSdkVersion\":30,\"hl\":\"en\"}", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"},
        {"{\"clientName\":\"IOS\",\"clientVersion\":\"20.10.4\",\"deviceModel\":\"iPhone16,2\",\"osVersion\":\"18.3.2.22D82\",\"hl\":\"en\"}", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"},
        {"{\"clientName\":\"ANDROID_VR\",\"clientVersion\":\"1.60.19\",\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\",\"androidSdkVersion\":32,\"hl\":\"en\"}", "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"},
    };

    private Youtube() { }

    /** key may be empty and transcribe false: then only captions are used. */
    static Info fetch(String url, String key, boolean transcribe) throws IOException {
        Info info = new Info();
        info.id = Transcript.videoId(url);
        if (info.id.isEmpty()) throw new IOException("Not a YouTube video link");
        JSONObject player = null;
        String agent = CLIENTS[0][1];
        IOException last = null;
        for (String[] client : CLIENTS) {
            try {
                JSONObject reply = player(info.id, client[0], client[1]);
                String status = reply.optJSONObject("playabilityStatus") == null ? "" : reply.optJSONObject("playabilityStatus").optString("status");
                if (reply.optJSONObject("videoDetails") != null && !reply.optJSONObject("videoDetails").optString("title").isEmpty() && player == null) { player = reply; agent = client[1]; }
                if (status.equals("OK")) { player = reply; agent = client[1]; break; }
            } catch (IOException failed) { last = failed; }
        }
        if (player == null) { oembed(url, info); if (info.title.isEmpty()) throw last == null ? new IOException("YouTube would not give this video's details") : last; return info; }
        JSONObject d = player.optJSONObject("videoDetails");
        if (d != null) {
            info.title = d.optString("title");
            info.author = d.optString("author");
            info.description = d.optString("shortDescription").replace("\r", "").trim();
            try { info.seconds = Integer.parseInt(d.optString("lengthSeconds", "0")); } catch (NumberFormatException none) { }
        }
        boolean playable = player.optJSONObject("playabilityStatus") != null && "OK".equals(player.optJSONObject("playabilityStatus").optString("status"));
        if (!playable) return info; // private, removed or age-restricted: the title and description are all there is
        JSONObject captions = player.optJSONObject("captions");
        JSONObject list = captions == null ? null : captions.optJSONObject("playerCaptionsTracklistRenderer");
        JSONObject track = Transcript.pick(list == null ? null : list.optJSONArray("captionTracks"));
        if (track != null) {
            try {
                String words = Transcript.fromXml(download(track.optString("baseUrl"), agent, 2 * 1024 * 1024));
                if (!words.isEmpty()) { info.transcript = words; info.how = "captions"; info.tried = true; return info; }
            } catch (IOException failed) { /* fall through to the audio */ }
        }
        if (transcribe && !key.isEmpty()) {
            try { audio(player, agent, key, info); info.tried = true; }
            catch (Model.Rejected refused) { info.note = "the transcription service refused the audio"; info.tried = true; }
        }
        return info;
    }

    private static JSONObject player(String id, String client, String agent) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", agent);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            String body = "{\"context\":{\"client\":" + client + "},\"videoId\":\"" + id + "\",\"contentCheckOk\":true,\"racyCheckOk\":true}";
            try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            if (c.getResponseCode() != 200) throw new IOException("YouTube answered " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) { return new JSONObject(new String(Web.readAll(in), StandardCharsets.UTF_8)); }
        } catch (org.json.JSONException bad) { throw new IOException("YouTube sent an unreadable answer"); }
        finally { c.disconnect(); }
    }

    /** The public title/channel service: all that is left when the player service refuses. */
    private static void oembed(String url, Info info) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8")).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            try {
                if (c.getResponseCode() != 200) return;
                try (InputStream in = c.getInputStream()) {
                    JSONObject o = new JSONObject(new String(Web.readAll(in), StandardCharsets.UTF_8));
                    info.title = o.optString("title");
                    info.author = o.optString("author_name");
                }
            } finally { c.disconnect(); }
        } catch (Exception ignored) { }
    }

    private static String download(String address, String agent, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", agent);
        try {
            if (c.getResponseCode() != 200) throw new IOException("YouTube answered " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while (bytes.size() < max && (n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { c.disconnect(); }
    }

    /** No captions: take the smallest audio stream, up to AUDIO_BYTES of it, and have it transcribed. */
    private static void audio(JSONObject player, String agent, String key, Info info) throws IOException {
        JSONObject streaming = player.optJSONObject("streamingData");
        JSONArray formats = streaming == null ? null : streaming.optJSONArray("adaptiveFormats");
        JSONObject pick = null;
        for (int i = 0; formats != null && i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null || !f.optString("mimeType").startsWith("audio/mp4") || f.optString("url").isEmpty()) continue;
            if (pick == null || f.optInt("bitrate", Integer.MAX_VALUE) < pick.optInt("bitrate", Integer.MAX_VALUE)) pick = f;
        }
        if (pick == null) { info.note = "no audio stream was available"; return; }
        long total = pick.optLong("contentLength", 0);
        int want = (int) Math.min(AUDIO_BYTES, total > 0 ? total : AUDIO_BYTES);
        HttpURLConnection c = (HttpURLConnection) new URL(pick.optString("url")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", agent);
        c.setRequestProperty("Range", "bytes=0-" + (want - 1));
        byte[] audio;
        try {
            int code = c.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("YouTube answered " + code + " for the audio");
            long deadline = System.currentTimeMillis() + 120000;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[32768];
                int n;
                while (bytes.size() < want && (n = in.read(buffer, 0, Math.min(buffer.length, want - bytes.size()))) != -1) {
                    bytes.write(buffer, 0, n);
                    if (System.currentTimeMillis() > deadline) throw new IOException("The audio download was too slow");
                }
                audio = bytes.toByteArray();
            }
        } finally { c.disconnect(); }
        if (audio.length < 2048) { info.note = "the audio could not be downloaded"; return; }
        String words;
        try { words = Model.transcribe(key, audio, "m4a"); }
        catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Transcription failed: " + e.getMessage()); }
        if (words.isEmpty()) { info.note = "no speech was found"; return; }
        info.transcript = words;
        info.how = "audio";
        if (total > want && info.seconds > 0) info.note = "first " + Math.max(1, (int) (info.seconds * (double) want / total / 60)) + " minutes";
    }
}
