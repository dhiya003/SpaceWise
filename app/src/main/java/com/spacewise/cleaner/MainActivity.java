package com.spacewise.cleaner;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String APP_URL = "https://spacewise-storage-cleaner.dhiviyalakshmi003.chatgpt.site/";
    private FrameLayout root;
    private WebView webView;
    private ProgressBar progress;
    private LinearLayout errorPanel;
    private boolean pageFinished;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(23, 21, 19));
        getWindow().setNavigationBarColor(Color.rgb(23, 21, 19));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(23, 21, 19));
        setContentView(root);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(23, 21, 19));
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(64, 64, Gravity.CENTER);
        root.addView(progress, progressParams);

        createErrorPanel();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(false);
        settings.setUserAgentString(settings.getUserAgentString() + " SpaceWiseAndroid/1.0");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setVisibility(newProgress < 100 && !pageFinished ? View.VISIBLE : View.GONE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageFinished = false;
                errorPanel.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                pageFinished = true;
                progress.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showError("Could not load SpaceWise. Check your internet connection and try again.");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    showError("SpaceWise server returned error " + response.getStatusCode() + ". Please retry.");
                }
            }
        });

        if (state == null) loadApp(); else {
            webView.restoreState(state);
            progress.setVisibility(View.GONE);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack(); else finish();
            }
        });
    }

    private void createErrorPanel() {
        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(48, 48, 48, 48);
        errorPanel.setBackgroundColor(Color.rgb(23, 21, 19));
        errorPanel.setVisibility(View.GONE);

        TextView logo = new TextView(this);
        logo.setText("SPACEWISE");
        logo.setTextColor(Color.rgb(255, 107, 74));
        logo.setTextSize(24);
        logo.setGravity(Gravity.CENTER);
        errorPanel.addView(logo);

        TextView message = new TextView(this);
        message.setId(View.generateViewId());
        message.setTag("error_message");
        message.setTextColor(Color.WHITE);
        message.setTextSize(17);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
        messageParams.setMargins(0, 28, 0, 28);
        errorPanel.addView(message, messageParams);

        Button retry = new Button(this);
        retry.setText("Retry");
        retry.setTextColor(Color.WHITE);
        retry.setBackgroundColor(Color.rgb(255, 107, 74));
        retry.setOnClickListener(v -> loadApp());
        errorPanel.addView(retry, new LinearLayout.LayoutParams(-1, 120));

        root.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showError(String text) {
        progress.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        TextView message = errorPanel.findViewWithTag("error_message");
        message.setText(text);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void loadApp() {
        pageFinished = false;
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        webView.clearCache(false);
        webView.loadUrl(APP_URL);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
