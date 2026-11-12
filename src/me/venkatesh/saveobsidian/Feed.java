package me.venkatesh.saveobsidian;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The merged feed: captures, Source notes and topic activity, newest first. Pure Java so the JVM checks cover it. */
public final class Feed {
    public static final String SOURCE = "source", TOPIC = "topic", PERSON = "person";
    public static final String CAPTURED = "Captured", INGESTED = "Ingested", PROCESSED = "Processed";

    public static final class Item {
        public String kind, status = "", lane = "", title = "", path = "", sourceName = "", curiosity = "", why = "", made = "", age = "", waiting = "", url = "", tldr = "";
        /** Source notes a topic gained recently, mentions of a person. */
        public int count, cards;
        public boolean kept;
        /** Epoch minute of the capture (or the start of its day when only a date is known) and the tie-break. */
        long minute, tie;
        /** The inbox entry for a capture that has no note yet. */
        public InboxFile.Entry entry;
    }

    private Feed() { }

    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d\\d)-(\\d\\d)(?:[ T](\\d\\d):(\\d\\d))?");
    private static final Pattern DMY = Pattern.compile("^(\\d{2})/(\\d{2})/(\\d{2})");

    /** The capture time as a date-time. hasTime is false for a date-only value. Null when no date can be read. */
    static LocalDateTime when(String stamp, boolean[] hasTime) {
        if (stamp == null) return null;
        Matcher m = DATE.matcher(stamp);
        try {
            if (m.find()) {
                hasTime[0] = m.group(4) != null;
                return LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    m.group(4) == null ? 0 : Integer.parseInt(m.group(4)), m.group(5) == null ? 0 : Integer.parseInt(m.group(5)));
            }
            Matcher d = DMY.matcher(stamp);
            if (d.find()) { hasTime[0] = false; return LocalDateTime.of(2000 + Integer.parseInt(d.group(3)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(1)), 0, 0); }
        } catch (java.time.DateTimeException | NumberFormatException ignored) { }
        return null;
    }

    private static long minutes(LocalDateTime t) { return t.toEpochSecond(java.time.ZoneOffset.UTC) / 60; }

    /** "12m ago", "3h ago", "Today", "1d ago", "3w ago", or "". */
    public static String age(LocalDateTime then, boolean hasTime, LocalDateTime now) {
        if (then == null) return "";
        if (hasTime) {
            long mins = Duration.between(then, now).toMinutes();
            if (mins < 1) return "now";
            if (mins < 60) return mins + "m ago";
            if (mins < 24 * 60) return (mins / 60) + "h ago";
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate());
        if (days <= 0) return "Today";
        if (days < 14) return days + "d ago";
        if (days < 60) return (days / 7) + "w ago";
        return (days / 30) + "mo ago";
    }

    static String host(String url) {
        try { String h = new URI(url).getHost(); return h == null ? "" : h.replaceFirst("^www\\.", ""); } catch (Exception bad) { return ""; }
    }

    /** A link without tracking tail, fragment or trailing slash, for matching an inbox entry with the note made from it. */
    static String norm(String url) {
        String u = url.trim().toLowerCase(Locale.ROOT).replaceFirst("[?#].*$", "").replaceFirst("^https?://(www\\.)?", "");
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static int count(String list) {
        int n = 0;
        for (int at = list.indexOf("[["); at >= 0; at = list.indexOf("[[", at + 2)) n++;
        return n;
    }

    private static String plural(int n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    /** "Made 1 note, 2 topics (1 new), 1 person, 3 entities", or "" when the note records nothing the phone made. */
    public static String made(Vault.Note n) {
        int tn = count(n.meta("made-topics-new")), tu = count(n.meta("made-topics-updated")), pn = count(n.meta("made-people-new")), pu = count(n.meta("made-people-updated"));
        int en = count(n.meta("made-entities-new")), eu = count(n.meta("made-entities-updated"));
        if (tn + tu + pn + pu + en + eu == 0) return "";
        StringBuilder s = new StringBuilder("Made 1 note");
        if (tn + tu > 0) s.append(", ").append(plural(tn + tu, "topic", "topics")).append(tn > 0 ? " (" + tn + " new)" : "");
        if (pn + pu > 0) s.append(", ").append(plural(pn + pu, "person", "people"));
        if (en + eu > 0) s.append(", ").append(plural(en + eu, "entity", "entities"));
        return s.toString();
    }

    /** All items, newest first (people last, by note change time). Topic cards need two sources in the last seven days. */
    public static List<Item> build(Collection<Vault.Note> notes, Vault.Graph graph, List<InboxFile.Entry> inbox, LocalDateTime now) {
        List<Item> out = new ArrayList<>();
        Set<String> filed = new HashSet<>();
        for (Vault.Note n : notes) {
            if (n.catalog || !Layout.inSources(n.path) || n.path.endsWith("/My cards.md")) continue;
            Item it = new Item();
            it.kind = SOURCE;
            it.path = n.path;
            it.title = n.title;
            it.url = n.meta("source");
            if (!it.url.isEmpty()) filed.add(norm(it.url));
            it.sourceName = it.url.isEmpty() ? n.meta("via") : host(it.url);
            it.status = n.processed() ? PROCESSED : INGESTED;
            it.lane = Lane.of(n.path, n.meta);
            it.curiosity = n.meta("curiosity");
            it.cards = n.cardsText.isEmpty() ? 0 : Cards.parse(n.path, n.cardsText).size();
            it.kept = n.meta("kept").equalsIgnoreCase("true");
            it.tldr = n.description.equals(n.title) ? "" : n.description.replaceAll("\\s*\\((high|medium|low)\\)\\s*$", ""); // the confidence tag is for the Unit screen
            it.why = n.why.startsWith("Reason was not recorded") ? "" : n.why; // the agent writes this placeholder when the owner gave no reason
            it.made = made(n);
            boolean[] time = new boolean[1];
            LocalDateTime at = when(n.meta("captured"), time);
            if (at == null) at = when(n.path.substring(n.path.lastIndexOf('/') + 1), time);
            if (at == null) { at = LocalDateTime.ofEpochSecond(n.mtime / 1000, 0, java.time.ZoneOffset.UTC); time[0] = false; }
            it.minute = minutes(at);
            it.tie = n.mtime;
            it.age = age(at, time[0], now);
            it.waiting = n.processed() ? "" : "Imported note. Not processed yet.";
            out.add(it);
        }
        for (InboxFile.Entry e : inbox) {
            if (!e.url.isEmpty() && filed.contains(norm(e.url))) continue;
            Item it = new Item();
            it.kind = SOURCE;
            it.status = CAPTURED;
            it.entry = e;
            it.title = e.shownTitle();
            it.url = e.url;
            it.sourceName = e.url.isEmpty() ? "Note" : host(e.url);
            it.why = e.why;
            it.tldr = e.tldr.isEmpty() && e.text ? e.body : e.tldr;
            it.waiting = "Saved on this phone. Not in your notes yet.";
            boolean[] time = new boolean[1];
            LocalDateTime at = when(e.time, time);
            if (at == null) { at = now; time[0] = false; }
            it.minute = minutes(at);
            it.age = age(at, time[0], now);
            out.add(it);
        }
        LocalDate cutoff = now.toLocalDate().minusDays(7);
        for (Vault.Note n : notes) {
            if (!n.path.startsWith("Knowledge/Topics/") || n.catalog) continue;
            int fresh = 0;
            long newest = 0;
            String lane = "";
            for (Vault.Note from : graph.in(n)) {
                if (!Layout.inSources(from.path)) continue;
                boolean[] time = new boolean[1];
                LocalDateTime at = when(from.meta("captured"), time);
                if (at == null || at.toLocalDate().isBefore(cutoff)) continue;
                fresh++;
                if (minutes(at) >= newest) { newest = minutes(at); lane = Lane.of(from.path, from.meta); }
            }
            if (fresh < 2) continue;
            Item it = new Item();
            it.kind = TOPIC; it.path = n.path; it.title = n.title; it.count = fresh; it.lane = lane;
            it.minute = newest; it.tie = n.mtime - 1; it.age = "";
            out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                int c = Long.compare(b.minute, a.minute);
                return c != 0 ? c : Long.compare(b.tie, a.tie);
            }
        });
        List<Item> people = new ArrayList<>();
        for (Vault.Note n : notes) {
            if (!n.path.startsWith("Knowledge/People/") || n.catalog) continue;
            Item it = new Item();
            it.kind = PERSON; it.path = n.path; it.title = n.title; it.count = graph.in(n).size(); it.tie = n.mtime;
            people.add(it);
        }
        Collections.sort(people, new Comparator<Item>() { @Override public int compare(Item a, Item b) { return Long.compare(b.tie, a.tie); } });
        out.addAll(people);
        return out;
    }

    /** About `width` characters of the text around the first query word found, with an ellipsis on a cut side. "" when no word is found. */
    public static String snippet(String text, String query, int width) {
        String flat = text.replaceAll("\\s+", " ").trim(), low = flat.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String w : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.isEmpty()) continue;
            int i = low.indexOf(w);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        if (at < 0) return "";
        int from = Math.max(0, at - width / 3), to = Math.min(flat.length(), from + width);
        from = Math.max(0, Math.min(from, to - width));
        return (from > 0 ? "…" : "") + flat.substring(from, to).trim() + (to < flat.length() ? "…" : "");
    }

    /** The items a filter chip shows: All, Craft, Life, Sources, Topics, People. People appear only under their own chip. */
    public static List<Item> filter(List<Item> items, String chip) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            boolean keep;
            switch (chip) {
                case "Craft": keep = it.lane.equals(Lane.CRAFT) && !it.kind.equals(PERSON); break;
                case "Life": keep = it.lane.equals(Lane.LIFE) && !it.kind.equals(PERSON); break;
                case "Sources": keep = it.kind.equals(SOURCE); break;
                case "Topics": keep = it.kind.equals(TOPIC); break;
                case "People": keep = it.kind.equals(PERSON); break;
                default: keep = !it.kind.equals(PERSON);
            }
            if (keep) out.add(it);
        }
        return out;
    }
}
