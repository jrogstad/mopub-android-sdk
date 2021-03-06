package com.mopub.mobileads;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.os.AsyncTask;
import android.util.Log;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import static com.mopub.mobileads.util.HttpResponses.extractHeader;

public class AdFetchTask extends AsyncTask<String, Void, AdLoadTask> {
    private TaskTracker mTaskTracker;
    private AdViewController mAdViewController;
    private Exception mException;
    private HttpClient mHttpClient;
    private long mTaskId;
    private String mUserAgent;

    private AdFetcher.FetchStatus mFetchStatus = AdFetcher.FetchStatus.NOT_SET;
    private static final int MAXIMUM_REFRESH_TIME_MILLISECONDS = 600000;
    private static final double EXPONENTIAL_BACKOFF_FACTOR = 1.5;

    public AdFetchTask(TaskTracker taskTracker, AdViewController adViewController, String userAgent, int timeoutMilliseconds) {
        mTaskTracker = taskTracker;

        mAdViewController = adViewController;
        mHttpClient = getDefaultHttpClient(timeoutMilliseconds);
        mTaskId = mTaskTracker.getCurrentTaskId();
        mUserAgent = userAgent;
    }

    @Override
    protected AdLoadTask doInBackground(String... urls) {
        AdLoadTask result = null;
        try {
            result = fetch(urls[0]);
        } catch (Exception exception) {
            mException = exception;
        } finally {
            shutdownHttpClient();
        }
        return result;
    }

    private AdLoadTask fetch(String url) throws Exception {
    	/*Patch*/
        //HttpGet httpget = new HttpGet(url);
        //httpget.addHeader(AdFetcher.USER_AGENT_HEADER, mUserAgent);
    	Pattern p = Pattern.compile("(?<=[?&;])id=(.*?)($|[&;])");
	    Matcher m = p.matcher(url);
	    String idvalue = null;
	    if (m.find()) {
		idvalue = "achievemint_data=" + m.group(1);
		Log.d("MoPub", "Found the group: " + idvalue);
	    } else
		Log.d("MoPub", "Group not found in the URL");
	    
	    url = m.replaceAll("");

            HttpPost httppost = new HttpPost(url);
            httppost.addHeader(AdFetcher.USER_AGENT_HEADER, mUserAgent);
            httppost.addHeader("Content-Type",  "application/x-www-form-urlencoded");
            httppost.setEntity(new StringEntity(idvalue));
        
    	

        if (!isStateValid()) return null;

        HttpResponse response = mHttpClient.execute(httppost);
        /*/Patch*/

        if (!isResponseValid(response)) return null;

        mAdViewController.configureUsingHttpResponse(response);

        if (!responseContainsContent(response)) return null;

        return AdLoadTask.fromHttpResponse(response, mAdViewController);
    }

    private boolean responseContainsContent(HttpResponse response) {
        // Ensure that the ad is not warming up.
        if ("1".equals(extractHeader(response, AdFetcher.WARMUP_HEADER))) {
            Log.d("MoPub", "Ad Unit (" + mAdViewController.getAdUnitId() + ") is still warming up. " +
                    "Please try again in a few minutes.");
            mFetchStatus = AdFetcher.FetchStatus.AD_WARMING_UP;
            return false;
        }

        // Ensure that the ad type header is valid and not "clear".
        String adType = extractHeader(response, AdFetcher.AD_TYPE_HEADER);
        if ("clear".equals(adType)) {
            Log.d("MoPub", "No inventory found for adunit (" + mAdViewController.getAdUnitId() + ").");
            mFetchStatus = AdFetcher.FetchStatus.CLEAR_AD_TYPE;
            return false;
        }

        return true;
    }

    private boolean isResponseValid(HttpResponse response) {
        if (response == null || response.getEntity() == null) {
            Log.d("MoPub", "MoPub server returned null response.");
            mFetchStatus = AdFetcher.FetchStatus.INVALID_SERVER_RESPONSE_NOBACKOFF;
            return false;
        }

        final int statusCode = response.getStatusLine().getStatusCode();

        // Client and Server HTTP errors should result in an exponential backoff
        if (statusCode >= 400) {
            Log.d("MoPub", "Server error: returned HTTP status code " + Integer.toString(statusCode) +
                    ". Please try again.");
            mFetchStatus = AdFetcher.FetchStatus.INVALID_SERVER_RESPONSE_BACKOFF;
            return false;
        }
        // Other non-200 HTTP status codes should still fail
        else if (statusCode != HttpStatus.SC_OK) {
            Log.d("MoPub", "MoPub server returned invalid response: HTTP status code " +
                    Integer.toString(statusCode) + ".");
            mFetchStatus = AdFetcher.FetchStatus.INVALID_SERVER_RESPONSE_NOBACKOFF;
            return false;
        }
        return true;
    }

    private boolean isStateValid() {
        // We check to see if this AsyncTask was cancelled, as per
        // http://developer.android.com/reference/android/os/AsyncTask.html
        if (isCancelled()) {
            mFetchStatus = AdFetcher.FetchStatus.FETCH_CANCELLED;
            return false;
        }

        if (mAdViewController == null || mAdViewController.isDestroyed()) {
            Log.d("MoPub", "Error loading ad: AdViewController has already been GCed or destroyed.");
            return false;
        }
        return true;
    }

    @Override
    protected void onPostExecute(AdLoadTask adLoadTask) {
        if (!isMostCurrentTask()) {
            Log.d("MoPub", "Ad response is stale.");
            cleanup();
            return;
        }

        // If cleanup() has already been called on the AdViewController, don't proceed.
        if (mAdViewController == null || mAdViewController.isDestroyed()) {
            if (adLoadTask != null) {
                adLoadTask.cleanup();
            }
            mTaskTracker.markTaskCompleted(mTaskId);
            cleanup();
            return;
        }

        if (adLoadTask == null) {
            if (mException != null) {
                Log.d("MoPub", "Exception caught while loading ad: " + mException);
            }

            MoPubErrorCode errorCode;
            switch (mFetchStatus) {
                case NOT_SET:
                    errorCode = MoPubErrorCode.UNSPECIFIED;
                    break;
                case FETCH_CANCELLED:
                    errorCode = MoPubErrorCode.CANCELLED;
                    break;
                case INVALID_SERVER_RESPONSE_BACKOFF:
                case INVALID_SERVER_RESPONSE_NOBACKOFF:
                    errorCode = MoPubErrorCode.SERVER_ERROR;
                    break;
                case CLEAR_AD_TYPE:
                case AD_WARMING_UP:
                    errorCode = MoPubErrorCode.NO_FILL;
                    break;
                default:
                    errorCode = MoPubErrorCode.UNSPECIFIED;
                    break;
            }

            mAdViewController.adDidFail(errorCode);

            /*
             * There are numerous reasons for the ad fetch to fail, but only in the specific
             * case of actual server failure should we exponentially back off.
             *
             * Note: We place the exponential backoff after AdViewController's adDidFail because we only
             * want to increase refresh times after the first failure refresh timer is
             * scheduled, and not before.
             */
            if (mFetchStatus == AdFetcher.FetchStatus.INVALID_SERVER_RESPONSE_BACKOFF) {
                exponentialBackoff();
                mFetchStatus = AdFetcher.FetchStatus.NOT_SET;
            }
        } else {
            adLoadTask.execute();
            adLoadTask.cleanup();
        }

        mTaskTracker.markTaskCompleted(mTaskId);
        cleanup();
    }

    @Override
    protected void onCancelled() {
        if (!isMostCurrentTask()) {
            Log.d("MoPub", "Ad response is stale.");
            cleanup();
            return;
        }

        Log.d("MoPub", "Ad loading was cancelled.");
        if (mException != null) {
            Log.d("MoPub", "Exception caught while loading ad: " + mException);
        }
        mTaskTracker.markTaskCompleted(mTaskId);
        cleanup();
    }

    /* This helper function is called when a 4XX or 5XX error is received during an ad fetch.
     * It exponentially increases the parent AdViewController's refreshTime up to a specified cap.
     */
    private void exponentialBackoff() {
        if (mAdViewController == null) {
            return;
        }

        int refreshTimeMilliseconds = mAdViewController.getRefreshTimeMilliseconds();

        refreshTimeMilliseconds = (int) (refreshTimeMilliseconds * EXPONENTIAL_BACKOFF_FACTOR);
        if (refreshTimeMilliseconds > MAXIMUM_REFRESH_TIME_MILLISECONDS) {
            refreshTimeMilliseconds = MAXIMUM_REFRESH_TIME_MILLISECONDS;
        }

        mAdViewController.setRefreshTimeMilliseconds(refreshTimeMilliseconds);
    }

    private void cleanup() {
        mTaskTracker = null;
        mException = null;
        mFetchStatus = AdFetcher.FetchStatus.NOT_SET;
    }

    private DefaultHttpClient getDefaultHttpClient(int timeoutMilliseconds) {
        HttpParams httpParameters = new BasicHttpParams();

        if (timeoutMilliseconds > 0) {
            // Set timeouts to wait for connection establishment / receiving data.
            HttpConnectionParams.setConnectionTimeout(httpParameters, timeoutMilliseconds);
            HttpConnectionParams.setSoTimeout(httpParameters, timeoutMilliseconds);
        }

        // Set the buffer size to avoid OutOfMemoryError exceptions on certain HTC devices.
        // http://stackoverflow.com/questions/5358014/android-httpclient-oom-on-4g-lte-htc-thunderbolt
        HttpConnectionParams.setSocketBufferSize(httpParameters, 8192);

        return new DefaultHttpClient(httpParameters);
    }

    private void shutdownHttpClient() {
        if (mHttpClient != null) {
            ClientConnectionManager manager = mHttpClient.getConnectionManager();
            if (manager != null) {
                manager.shutdown();
            }
            mHttpClient = null;
        }
    }

    private boolean isMostCurrentTask() {
        return mTaskTracker.isMostCurrentTask(mTaskId);
    }
}
