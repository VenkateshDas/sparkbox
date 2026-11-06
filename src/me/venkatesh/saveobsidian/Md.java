package me.venkatesh.saveobsidian;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.View;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Just enough Markdown to read a note on a phone: headings, lists, tables as rows, bold, code, [[links]] and web links. */
final class Md {
    interface Open { void open(String rawTarget); }

    private static final Pattern INLINE = Pattern.compile("(\\*\\*[^*\\n]+\\*\\*)|(`[^`\\n]+`)|(!?\\[\\[[^\\]\\n]+\\]\\])|(\\[[^\\]\\n]+\\]\\((https?://[^)\\s]+)\\))");
    private static final Pattern RULE = Pattern.compile("^\\|?[\\s:|-]+\\|?$");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>)\\]]+");

    /** Body of a note (frontmatter hidden) as styled text. Link taps go to open. */
    static SpannableStringBuilder render(Ui ui, String text, Open open) {
        if (text.startsWith("﻿")) text = text.substring(1);
        text = text.replace("\r\n", "\n");
        int[] at = new int[1];
        Vault.frontmatter(text, at);
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean fence = false, diagram = false;
        for (String line : text.substring(at[0]).split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```")) {
                if (!fence) { fence = true; diagram = t.toLowerCase(java.util.Locale.ROOT).contains("mermaid"); if (diagram) paragraph(out, "Diagram (open in Obsidian to see it)", ui.muted, 0.9f); }
                else fence = diagram = false;
                continue;
            }
            if (fence) { if (!diagram) mono(out, line, ui); continue; }
            if (t.isEmpty()) { int n = out.length(); if (n > 0 && !(n >= 2 && out.charAt(n - 1) == '\n' && out.charAt(n - 2) == '\n')) out.append('\n'); continue; }
            if (t.startsWith("|")) {
                if (RULE.matcher(t).matches()) continue;
                String[] cells = t.replaceAll("^\\||\\|$", "").split("(?<!\\\\)\\|");
                StringBuilder row = new StringBuilder();
                for (String cell : cells) row.append(row.length() == 0 ? "" : "  ·  ").append(cell.replace("\\|", "|").trim());
                inline(out, row.toString(), ui, open);
                out.append('\n');
                continue;
            }
            int level = 0;
            while (level < t.length() && t.charAt(level) == '#') level++;
            if (level > 0 && level < 7 && t.length() > level && t.charAt(level) == ' ') {
                if (out.length() > 0) out.append('\n');
                int start = out.length();
                inline(out, t.substring(level + 1), ui, open);
                out.setSpan(new TypefaceSpan("sans-serif-medium"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(level == 1 ? 1.3f : level == 2 ? 1.18f : 1.06f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }
            if (t.startsWith("- ") || t.startsWith("* ")) { String lead = line.substring(0, line.indexOf(t.charAt(0))); out.append(lead.length() >= 2 ? "      ◦  " : "•  "); t = t.substring(2); }
            else if (t.startsWith(">")) { int start = out.length(); inline(out, t.replaceFirst("^>\\s?", ""), ui, open); out.setSpan(new ForegroundColorSpan(ui.sub), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); out.append('\n'); continue; }
            inline(out, t, ui, open);
            out.append('\n');
        }
        Matcher m = URL.matcher(out.toString());
        while (m.find()) {
            if (out.getSpans(m.start(), m.end(), ClickableSpan.class).length > 0 || out.getSpans(m.start(), m.end(), URLSpan.class).length > 0) continue;
            out.setSpan(new Link(ui.accent, null, m.group()), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** Answer text from Ask: the same inline rules, so [[path|Title]] citations are tappable. */
    static SpannableStringBuilder inlineOnly(Ui ui, String text, Open open) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            String t = line.replaceFirst("^#{1,6}\\s+", "").replaceFirst("^[-*]\\s+", "•  ");
            inline(out, t, ui, open);
            out.append('\n');
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.delete(out.length() - 1, out.length());
        Matcher m = URL.matcher(out.toString());
        while (m.find()) if (out.getSpans(m.start(), m.end(), ClickableSpan.class).length == 0) out.setSpan(new Link(ui.accent, null, m.group()), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    private static void paragraph(SpannableStringBuilder out, String s, int color, float size) {
        int start = out.length();
        out.append(s).append('\n');
        out.setSpan(new ForegroundColorSpan(color), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(size), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void mono(SpannableStringBuilder out, String s, Ui ui) {
        int start = out.length();
        out.append(s).append('\n');
        out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.85f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(ui.sub), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void inline(SpannableStringBuilder out, String s, Ui ui, Open open) {
        Matcher m = INLINE.matcher(s);
        int from = 0;
        while (m.find()) {
            out.append(s, from, m.start());
            from = m.end();
            int start = out.length();
            if (m.group(1) != null) {
                out.append(m.group(1), 2, m.group(1).length() - 2);
                out.setSpan(new TypefaceSpan("sans-serif-medium"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null) {
                out.append(m.group(2), 1, m.group(2).length() - 1);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(ui.raised), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(3) != null) {
                String all = m.group(3), inner = all.substring(all.indexOf("[[") + 2, all.length() - 2);
                if (all.startsWith("!")) { out.append("[").append(Vault.label(inner)).append("]"); out.setSpan(new ForegroundColorSpan(ui.muted), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
                else { out.append(Vault.label(inner)); out.setSpan(new Link(ui.accent, open, inner), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
            } else {
                String label = m.group(4).substring(1, m.group(4).indexOf("]("));
                out.append(label);
                out.setSpan(new Link(ui.accent, null, m.group(5)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        out.append(s, from, s.length());
    }

    /** A tappable run: a vault link calls open, a web link goes to the browser. */
    private static final class Link extends ClickableSpan {
        private final int color; private final Open open; private final String target;
        Link(int color, Open open, String target) { this.color = color; this.open = open; this.target = target; }
        @Override public void onClick(View view) {
            if (open != null) open.open(target);
            else try { view.getContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(target))); } catch (RuntimeException none) { }
        }
        @Override public void updateDrawState(TextPaint paint) { paint.setColor(color); paint.setUnderlineText(false); }
    }
}
