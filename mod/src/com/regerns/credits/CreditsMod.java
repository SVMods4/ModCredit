package com.regerns.credits;

import android.webkit.CookieManager;

import cat.narezany.mods.api.MargyCPlugin;
import cat.narezany.mods.api.PluginContext;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CreditsMod implements MargyCPlugin {
    private volatile String credits = null;
    private volatile long lastFetch = 0;
    private volatile boolean fetching = false;

    @Override
    public void onCreate(final PluginContext ctx) {
        if (!ctx.getBoolean("enabled")) return;
        refresh(ctx);

        ctx.addTextFilter(s -> {
            try {
                if (s == null || s.length() > 40 || s.isEmpty()) return s;
                String c = credits;
                maybeRefresh(ctx);
                if (c == null) return s;
                String suffix = ctx.getString("format").replace("{n}", c);
                if (s.endsWith(suffix)) return s;
                for (String m : ctx.getString("models").split(",")) {
                    String t = m.trim();
                    if (!t.isEmpty() && s.contains(t)) return s + suffix;
                }
            } catch (Throwable ignored) { }
            return s;
        });
    }

    private void maybeRefresh(PluginContext ctx) {
        long every = Math.max(1, ctx.getInt("refresh")) * 60_000L;
        if (System.currentTimeMillis() - lastFetch > every) refresh(ctx);
    }

    private void refresh(final PluginContext ctx) {
        if (fetching) return;
        fetching = true;
        lastFetch = System.currentTimeMillis();
        new Thread(() -> {
            try {
                String url = ctx.getString("url").trim();
                if (url.isEmpty()) {
                    String m = ctx.getString("manual").trim();
                    credits = m.isEmpty() ? null : m;
                    return;
                }
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                String cookie = CookieManager.getInstance().getCookie("https://claude.ai");
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
                c.setRequestProperty("Accept", "application/json");
                try (InputStream in = c.getInputStream();
                     Scanner sc = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
                    String body = sc.hasNext() ? sc.next() : "";
                    String key = Pattern.quote(ctx.getString("jsonKey"));
                    Matcher mt = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([0-9.,]+)").matcher(body);
                    if (mt.find()) credits = mt.group(1);
                    else ctx.log("credits: ключ не найден в ответе");
                }
            } catch (Throwable t) {
                ctx.log("credits: " + t);
            } finally {
                fetching = false;
            }
        }, "credits-fetch").start();
    }
}
