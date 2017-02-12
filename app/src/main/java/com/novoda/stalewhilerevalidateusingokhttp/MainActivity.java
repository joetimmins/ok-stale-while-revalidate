package com.novoda.stalewhilerevalidateusingokhttp;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.TextView;

import java.io.IOException;

import okhttp3.Cache;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String GOOGLE_URL = "http://www.google.com";
    private final Callback doubleResponseCallback = new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
            String message = e.getLocalizedMessage();
            show(message);
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            String string = response.body().string();
            show(string);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Request request = new Request.Builder()
                .url(GOOGLE_URL)
                .build();
        OkHttpClient client = new OkHttpClient.Builder()
                .cache(new Cache(getCacheDir(), 10 * 1024 * 1024))
                .build();

        RevalidatingHttpClient revalidatingHttpClient = new RevalidatingHttpClient(client);
        revalidatingHttpClient.request(request, doubleResponseCallback);
    }

    private void show(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView textView = (TextView) findViewById(R.id.text_view);
                textView.setText(text);
            }
        });
    }

    private static class RevalidatingHttpClient {
        private final OkHttpClient okHttpClient;
        private static final String TAG = "stale-while-revalidate";

        private RevalidatingHttpClient(OkHttpClient okHttpClient) {
            this.okHttpClient = okHttpClient;
        }

        void request(Request request, final Callback doubleResponseCallback) {
            // look in the cache first
            Request cacheRequest = request.newBuilder()
                    .cacheControl(CacheControl.FORCE_CACHE)
                    .build();
            Call callToCache = okHttpClient.newCall(cacheRequest);

            boolean successfulCacheRequest = false;

            try {
                Response responseFromCache = callToCache.execute();
                if (responseFromCache.isSuccessful()) {
                    successfulCacheRequest = true;
                    Log.d(TAG, "Call to cache was successful");
                    doubleResponseCallback.onResponse(callToCache, responseFromCache);
                } else {
                    Log.d(TAG, "Call to cache was unsuccessful with code " + responseFromCache.code());
                }
            } catch (IOException e) {
                // the call to the cache failed due to cancellation, a connectivity problem or timeout
                // if there's just nothing in the cache we shouldn't come in here
                Log.e(TAG, "Call to cache failed badly", e);
            }
            final boolean unsuccessfulCacheRequest = !successfulCacheRequest;
            Call call = okHttpClient.newCall(request);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    // if the cache request was successful but this one wasn't, just do nothing
                    // if both failed, call back with failure
                    if (unsuccessfulCacheRequest) {
                        Log.e(TAG, "both cache and http revalidate calls failed", e);
                        doubleResponseCallback.onFailure(call, e);
                    } else {
                        // if we're in here, it means the cache request was fine
                        // and doubleResponseCallback.onResponse() has already been called
                        Log.e(TAG, "http revalidate call failed badly", e);
                    }
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    // even if we called back with something from the cache first, we still wanna make the request
                    if (response.isSuccessful()) {
                        Log.d(TAG, "http revalidate call succeeded");
                        doubleResponseCallback.onResponse(call, response);
                    } else {
                        Log.e(TAG, "http revalidate call was unsuccessful, with server code: " + response.code());
                    }
                }
            });
        }
    }
}
