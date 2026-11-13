package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * The readable part of a web page: title, author, description, picture and the article text as paragraphs, headings and lists.
 * HTML is parsed with jsoup; the article is found the way Mozilla's Readability does it (score the text blocks, take the best container and its
 * strong siblings), after trying the structured data most news and blog sites publish (JSON-LD articleBody). Pure Java so the JVM checks cover it.
 */
public final class Extract {
    private Extract() { }

    public static final class Page {
        public String title = "", byline = "", site = "", description = "", image = "", text = "";
        /** The server answered with a bot check, a consent wall or a "turn on JavaScript" page instead of the article. */
        public boolean blocked;
    }

    private static final int I = Pattern.CASE_INSENSITIVE;
    private static final Pattern UNLIKELY = Pattern.compile("banner|breadcrumb|combx|comment|community|cover-wrap|disqus|extra|footer|gdpr|header|legend|menu|related|remark|replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|pagination|pager|popup|yom-remote|cookie|consent|newsletter|subscribe|modal|toolbar|navbar|share|tooltip|overlay|paywall", I);
    private static final Pattern MAYBE = Pattern.compile("and|article|body|column|content|main|shadow|app|post|entry|story", I);
    private static final Pattern POSITIVE = Pattern.compile("article|body|content|entry|hentry|h-entry|main|page|post|text|blog|story|prose|markdown|readme", I);
    private static final Pattern NEGATIVE = Pattern.compile("hidden|banner|combx|comment|com-|contact|footer|footnote|reflist|references|citation|navbox|catlinks|gdpr|masthead|media|meta|outbrain|promo|related|scroll|share|shoutbox|sidebar|skyscraper|sponsor|shopping|tags|tool|widget", I);
    private static final Pattern BLOCKED = Pattern.compile("enable javascript|javascript is (required|disabled)|turn on javascript|verify you are (a )?human|just a moment|captcha|access denied|are you a robot|attention required|403 forbidden|checking your browser|unusual traffic|sign in to continue|log in to continue|accept (all )?cookies", I);
    private static final java.util.Set<String> BLOCKS = new java.util.HashSet<>(java.util.Arrays.asList(("address,article,aside,blockquote,dd,details,dl,dt,div,fieldset,figcaption,figure,footer,form,h1,h2,h3,h4,h5,h6,header,hr,li,main,nav,ol,p,pre,section,table,tbody,thead,tfoot,tr,td,th,ul").split(",")));

    public static Page parse(String html, String url) {
        Page out = new Page();
        if (html == null || html.trim().isEmpty()) return out;
        Document doc = Jsoup.parse(html, url == null ? "" : url);
        out.site = first(meta(doc, "og:site_name"), meta(doc, "application-name"));
        out.image = abs(doc, first(meta(doc, "og:image"), meta(doc, "twitter:image"), meta(doc, "og:image:url")));
        out.description = first(meta(doc, "og:description"), meta(doc, "twitter:description"), meta(doc, "description"));
        out.byline = first(meta(doc, "author"), meta(doc, "article:author"), meta(doc, "twitter:creator"), meta(doc, "parsely-author"));
        String h1 = "";
        Element h = doc.selectFirst("h1");
        if (h != null) h1 = h.text().trim();
        String ldBody = "", ldTitle = "", ldAuthor = "";
        for (Element s : doc.select("script[type=application/ld+json]")) {
            try {
                String[] r = {"", "", ""};
                walk(new org.json.JSONTokener(s.data().trim()).nextValue(), r);
                if (ldBody.length() < r[0].length()) ldBody = r[0];
                if (ldTitle.isEmpty()) ldTitle = r[1];
                if (ldAuthor.isEmpty()) ldAuthor = r[2];
            } catch (Exception badJson) { /* a broken block is skipped */ }
        }
        if (out.byline.isEmpty()) out.byline = ldAuthor;
        if (out.description.isEmpty() && !ldBody.isEmpty()) out.description = ldBody.length() > 300 ? ldBody.substring(0, 300) : ldBody;
        out.title = first(meta(doc, "og:title"), meta(doc, "twitter:title"), ldTitle, h1, doc.title());
        out.title = trimSite(out.title, out.site);
        out.byline = out.byline.replaceAll("^(?i)by\\s+", "").trim();

        String dom = article(doc);
        String text = dom;
        if (!ldBody.isEmpty() && (dom.length() < 300 || ldBody.length() > dom.length() * 1.3)) text = ldBody;
        if (text.length() < 200) {
            String all = render(doc.body());
            if (all.length() > text.length()) text = all;
        }
        text = text.trim();
        if (text.length() < 600 && (BLOCKED.matcher(text.length() < 400 ? text : text.substring(0, 400)).find() || BLOCKED.matcher(out.title).find())) { out.blocked = true; text = ""; }
        if (text.length() < 80 && !out.description.isEmpty() && !out.blocked) text = out.description;
        out.text = text;
        return out;
    }

    private static String meta(Document doc, String name) {
        Element e = doc.selectFirst("meta[property=" + name + "], meta[name=" + name + "], meta[itemprop=" + name + "]");
        return e == null ? "" : e.attr("content").trim();
    }

    private static String first(String... values) { for (String v : values) if (v != null && !v.trim().isEmpty()) return v.replaceAll("\\s+", " ").trim(); return ""; }

    private static String abs(Document doc, String link) {
        if (link.isEmpty()) return "";
        try { return new java.net.URL(new java.net.URL(doc.location().isEmpty() ? "https://invalid.example/" : doc.location()), link).toString(); } catch (Exception bad) { return link; }
    }

    private static String trimSite(String title, String site) {
        if (site.isEmpty() || title.length() <= site.length() + 6) return title;
        for (String sep : new String[]{" | ", " - ", " – ", " — ", " · ", " :: "}) {
            if (title.endsWith(sep + site)) return title.substring(0, title.length() - sep.length() - site.length()).trim();
            if (title.startsWith(site + sep)) return title.substring(site.length() + sep.length()).trim();
        }
        return title;
    }

    /** Pulls articleBody, headline and author out of schema.org JSON-LD, wherever it sits (object, array or @graph). r = {body, title, author}. */
    private static void walk(Object node, String[] r) {
        if (node instanceof JSONArray) { JSONArray a = (JSONArray) node; for (int i = 0; i < a.length(); i++) walk(a.opt(i), r); return; }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        String body = o.optString("articleBody", o.optString("text", ""));
        if (body.length() > r[0].length()) r[0] = body.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n\n").trim();
        if (r[1].isEmpty()) r[1] = o.optString("headline", "").trim();
        if (r[2].isEmpty()) {
            Object a = o.opt("author");
            if (a instanceof JSONArray && ((JSONArray) a).length() > 0) a = ((JSONArray) a).opt(0);
            if (a instanceof JSONObject) r[2] = ((JSONObject) a).optString("name", "").trim();
            else if (a instanceof String) r[2] = ((String) a).trim();
        }
        walk(o.opt("@graph"), r);
        walk(o.opt("mainEntity"), r);
    }

    // --- finding the article ---

    private static String article(Document doc) {
        Element body = doc.body();
        if (body == null) return "";
        doc.select("script, style, noscript, svg, iframe, canvas, form, button, select, input, textarea, template, dialog, audio, video, [hidden], [aria-hidden=true], nav, footer, aside, [role=navigation], [role=banner], [role=complementary], [role=contentinfo], [role=search]").remove();
        for (Element e : new ArrayList<>(body.getAllElements())) {
            if (e == body || e.parent() == null) continue;
            String tag = e.tagName();
            if (tag.equals("a") || tag.equals("article") || tag.equals("main") || tag.equals("body") || tag.equals("html")) continue;
            String sig = e.className() + " " + e.id();
            if (sig.trim().isEmpty()) continue;
            if (UNLIKELY.matcher(sig).find() && !MAYBE.matcher(sig).find() && e.select("p").size() < 3) e.remove();
        }
        Map<Element, Double> score = new HashMap<>();
        for (Element e : body.select("p, pre, td, blockquote, div, section")) {
            if (isContainer(e)) continue;
            String t = e.text();
            if (t.length() < 25) continue;
            Element parent = e.parent(), grand = parent == null ? null : parent.parent();
            if (parent == null || parent.tagName().equals("html")) continue;
            double s = 1 + count(t, ',') + count(t, '，') + Math.min(t.length() / 100, 3);
            init(score, parent);
            score.put(parent, score.get(parent) + s);
            if (grand != null && !grand.tagName().equals("html")) { init(score, grand); score.put(grand, score.get(grand) + s / 2); }
        }
        Element top = null;
        double best = 0;
        for (Map.Entry<Element, Double> c : score.entrySet()) {
            double adjusted = c.getValue() * (1 - linkDensity(c.getKey()));
            c.setValue(adjusted);
            if (adjusted > best) { best = adjusted; top = c.getKey(); }
        }
        if (top == null) return render(body);
        // like Readability: when an ancestor scores higher than the winner (an article split into sections, each a small winner), the ancestor is the article
        double floor = best / 3, last = best;
        for (Element up = top.parent(); up != null && !up.tagName().equals("body") && !up.tagName().equals("html"); up = up.parent()) {
            Double s = score.get(up);
            if (s == null) continue;
            if (s < floor) break;
            if (s > last) { top = up; best = s; break; }
            last = s;
        }
        if (top.text().length() < 250 && top.parent() != null && !top.parent().tagName().equals("body")) top = top.parent();
        // a semantic article that wraps the winner is the better container when it is not much bigger
        for (Element p = top.parent(); p != null; p = p.parent()) {
            if (p.tagName().equals("article") || p.attr("itemprop").equalsIgnoreCase("articleBody")) { if (p.text().length() <= top.text().length() * 2.2) top = p; break; }
        }
        Element parent = top.parent();
        StringBuilder out = new StringBuilder();
        if (parent == null || top.tagName().equals("article")) out.append(render(top));
        else {
            double threshold = Math.max(10, best * 0.2);
            for (Element sib : parent.children()) {
                boolean take = sib == top;
                if (!take) {
                    Double s = score.get(sib);
                    String sig = sib.className() + " " + sib.id(), st = sib.text();
                    boolean heading = sib.tagName().matches("h[1-6]") || (st.length() < 120 && sib.selectFirst("h1, h2, h3, h4, h5, h6") != null);
                    if (s != null && s >= threshold) take = true;
                    else if (heading) take = true; // a section title that sits beside its text
                    else if ((sib.tagName().equals("section") || sib.tagName().equals("div")) && st.length() >= 300 && linkDensity(sib) < 0.35 && !NEGATIVE.matcher(sig).find()) take = true; // a long text section next to the winner
                    else if (sib.tagName().equals("p")) { String t = sib.text(); double d = linkDensity(sib); take = (t.length() > 80 && d < 0.25) || (t.length() > 25 && d == 0 && t.matches("(?s).*[.!?]\"?$")); }
                }
                if (take) { String r = render(sib); if (!r.isEmpty()) out.append(out.length() > 0 ? "\n\n" : "").append(r); }
            }
        }
        String text = out.toString();
        return text;
    }

    /** A div or section that holds other blocks is a container, not a text block of its own. */
    private static boolean isContainer(Element e) {
        if (!e.tagName().equals("div") && !e.tagName().equals("section")) return false;
        for (Element c : e.children()) if (BLOCKS.contains(c.tagName())) return true;
        return false;
    }

    private static void init(Map<Element, Double> score, Element e) {
        if (score.containsKey(e)) return;
        double s;
        switch (e.tagName()) {
            case "div": s = 5; break;
            case "pre": case "td": case "blockquote": s = 3; break;
            case "address": case "ol": case "ul": case "dl": case "dd": case "dt": case "li": case "form": s = -3; break;
            case "h1": case "h2": case "h3": case "h4": case "h5": case "h6": case "th": s = -5; break;
            case "article": s = 8; break;
            default: s = 0;
        }
        String sig = e.className() + " " + e.id();
        if (POSITIVE.matcher(sig).find()) s += 25;
        if (NEGATIVE.matcher(sig).find()) s -= 25;
        score.put(e, s);
    }

    private static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }

    static double linkDensity(Element e) {
        int total = e.text().length();
        if (total == 0) return 0;
        int linked = 0;
        for (Element a : e.select("a[href]")) linked += a.text().length();
        return Math.min(1.0, (double) linked / total);
    }

    // --- turning a container into text ---

    /** Paragraphs, headings (### and ####, so they nest under a note's own "## Raw Content"), lists (- ), quotes (> ) and code, separated by blank lines. Link lists and other navigation are left out. */
    static String render(Element root) {
        if (root == null) return "";
        List<String> paras = new ArrayList<>();
        walkBlocks(root, paras);
        StringBuilder out = new StringBuilder();
        String last = "";
        for (String p : paras) {
            if (p.isEmpty() || p.equals(last)) continue;
            out.append(out.length() > 0 ? "\n\n" : "").append(p);
            last = p;
        }
        return out.toString();
    }

    private static void walkBlocks(Element e, List<String> out) {
        StringBuilder run = new StringBuilder();
        for (Node n : e.childNodes()) {
            if (n instanceof TextNode) { run.append(((TextNode) n).text()); continue; }
            if (!(n instanceof Element)) continue;
            Element c = (Element) n;
            String tag = c.tagName();
            if (!BLOCKS.contains(tag)) { run.append(' ').append(tag.equals("br") ? "" : c.text()).append(' '); continue; }
            flush(run, out);
            String t = c.text().trim();
            if (t.isEmpty()) continue;
            if (tag.matches("h[1-6]")) { if (t.length() < 200) out.add((tag.equals("h1") || tag.equals("h2") ? "### " : "#### ") + t); continue; }
            if (tag.equals("pre")) { out.add("```\n" + c.wholeText().replaceAll("\\s+$", "") + "\n```"); continue; }
            if (tag.equals("li")) { if (linkDensity(c) < 0.6 || t.length() > 120) out.add("- " + t); continue; }
            if (tag.equals("blockquote")) { out.add("> " + t); continue; }
            if (tag.equals("tr")) { StringBuilder row = new StringBuilder(); for (Element cell : c.select("> th, > td")) row.append(row.length() > 0 ? " | " : "").append(cell.text().trim()); out.add(row.toString()); continue; }
            if (tag.equals("p")) { if (t.length() > 1 && !(linkDensity(c) > 0.8 && t.length() < 60)) out.add(t); continue; }
            if ((tag.equals("ul") || tag.equals("ol") || tag.equals("div") || tag.equals("section")) && linkDensity(c) > 0.55 && t.length() < 500) continue; // a block of links is navigation
            if (tag.equals("figcaption") || tag.equals("dt") || tag.equals("dd") || tag.equals("th") || tag.equals("td")) { out.add(t); continue; }
            walkBlocks(c, out);
        }
        flush(run, out);
    }

    private static void flush(StringBuilder run, List<String> out) {
        String t = run.toString().replaceAll("\\s+", " ").trim();
        run.setLength(0);
        if (t.length() >= 3) out.add(t);
    }
}
