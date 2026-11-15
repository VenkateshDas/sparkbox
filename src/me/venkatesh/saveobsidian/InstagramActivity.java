package me.venkatesh.saveobsidian;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Instagram's own sign-in page. No password fields, cookie exports or third-party login service. */
public class InstagramActivity extends Activity {
    private WebView web;

    static void open(Context context, String url) {
        context.startActivity(new Intent(context, InstagramActivity.class).putExtra("url", url));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView explanation = new TextView(this);
        explanation.setText("Instagram access\nSign in to Instagram here for reels that cannot be read publicly. Your Instagram app login is separate. Your session stays on this phone and is sent only to Instagram.");
        root.addView(explanation);
        Button retry = new Button(this);
        retry.setText("Retry captures after signing in");
        retry.setOnClickListener(v -> {
            if (!InstagramApi.signedIn()) { Toast.makeText(this, "Finish signing in to Instagram first", Toast.LENGTH_LONG).show(); return; }
            CookieManager.getInstance().flush();
            Ingest.retryAll(this);
            BackgroundJob.schedule(this);
            Toast.makeText(this, "Retrying captures", Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(retry);
        Button signOut = new Button(this);
        signOut.setText("Sign out of Sparkbox's Instagram session");
        signOut.setOnClickListener(v -> {
            CookieManager cookies = CookieManager.getInstance();
            cookies.setCookie("https://www.instagram.com", "sessionid=; Max-Age=0; Path=/; Domain=.instagram.com; Secure", ignored -> {
                cookies.flush();
                web.loadUrl("https://www.instagram.com/accounts/login/");
            });
        });
        root.addView(signOut);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true); // Instagram's first-party sign-in requires JavaScript.
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                return !"https".equals(request.getUrl().getScheme()) || host == null
                    || !(host.equals("instagram.com") || host.endsWith(".instagram.com"));
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        String url = getIntent().getStringExtra("url");
        web.loadUrl(url != null && !Instagram.shortcode(url).isEmpty() ? "https://www.instagram.com/reel/" + Instagram.shortcode(url) + "/" : "https://www.instagram.com/accounts/login/");
    }

    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
