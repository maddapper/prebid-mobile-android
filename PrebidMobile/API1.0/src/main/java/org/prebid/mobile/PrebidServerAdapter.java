/*
 *    Copyright 2018-2019 Prebid.org, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.prebid.mobile;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.CountDownTimer;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class PrebidServerAdapter implements DemandAdapter {
    private ArrayList<ServerConnector> serverConnectors;

    PrebidServerAdapter() {
        serverConnectors = new ArrayList<>();
    }

    @Override
    public void requestDemand(RequestParams params, DemandAdapterListener listener, String auctionId) {
        ServerConnector connector = new ServerConnector(this, listener, params, auctionId);
        serverConnectors.add(connector);
        connector.execute();
    }

    @Override
    public void stopRequest(String auctionId) {
        ArrayList<ServerConnector> toRemove = new ArrayList<>();
        for (ServerConnector connector : serverConnectors) {
            if (connector.getAuctionId().equals(auctionId)) {
                connector.destroy();
                toRemove.add(connector);
            }
        }
        serverConnectors.removeAll(toRemove);
    }

    static class ServerConnector extends AsyncTask<Object, Object, ServerConnector.AsyncTaskResult<JSONObject>> {

        private static final int TIMEOUT_COUNT_DOWN_INTERVAL = 500;

        private final WeakReference<PrebidServerAdapter> prebidServerAdapter;
        private final TimeoutCountDownTimer timeoutCountDownTimer;

        private final RequestParams requestParams;
        private final String auctionId;

        private DemandAdapterListener listener;
        private boolean timeoutFired;

        ServerConnector(PrebidServerAdapter prebidServerAdapter, DemandAdapterListener listener, RequestParams requestParams, String auctionId) {
            this.prebidServerAdapter = new WeakReference<>(prebidServerAdapter);
            this.listener = listener;
            this.requestParams = requestParams;
            this.auctionId = auctionId;
            timeoutCountDownTimer = new TimeoutCountDownTimer(PrebidMobile.getTimeoutMillis(), TIMEOUT_COUNT_DOWN_INTERVAL);
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            timeoutCountDownTimer.start();
        }

        @Override
        @WorkerThread
        protected AsyncTaskResult<JSONObject> doInBackground(Object... objects) {
            try {
                long demandFetchStartTime = System.currentTimeMillis();
                URL url = new URL(getHost());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                String existingCookie = getExistingCookie();
                if (existingCookie != null) {
                    conn.setRequestProperty(PrebidServerSettings.COOKIE_HEADER, existingCookie);
                } // todo still pass cookie if limit ad tracking?

                conn.setRequestMethod("POST");
                conn.setConnectTimeout(PrebidMobile.getTimeoutMillis());

                // Add post data
                OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream(), "UTF-8");
                JSONObject postData = getPostData();
                LogUtil.d("Sending request for auction " + auctionId + " with post data: " + postData.toString());
                wr.write(postData.toString());
                wr.flush();

                // Start the connection
                conn.connect();

                // Read request response
                int httpResult = conn.getResponseCode();
                long demandFetchEndTime = System.currentTimeMillis();
                if (httpResult == HttpURLConnection.HTTP_OK) {
                    StringBuilder builder = new StringBuilder();
                    InputStream is = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line);
                    }
                    reader.close();
                    is.close();
                    String result = builder.toString();
                    JSONObject response = new JSONObject(result);
                    httpCookieSync(conn.getHeaderFields());
                    // in the future, this can be improved to parse response base on request versions
                    if (!PrebidMobile.timeoutMillisUpdated) {
                        int tmaxRequest = -1;
                        try {
                            tmaxRequest = response.getJSONObject("ext").getInt("tmaxrequest");
                        } catch (JSONException e) {
                            // ignore this
                        }
                        if (tmaxRequest >= 0) {
                            PrebidMobile.setTimeoutMillis(Math.min((int) (demandFetchEndTime - demandFetchStartTime) + tmaxRequest + 200, 2000)); // adding 200ms as safe time
                            PrebidMobile.timeoutMillisUpdated = true;
                        }
                    }
                    return new AsyncTaskResult<>(response);
                } else if (httpResult == HttpURLConnection.HTTP_BAD_REQUEST) {
                    StringBuilder builder = new StringBuilder();
                    InputStream is = conn.getErrorStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line);
                    }
                    reader.close();
                    is.close();
                    String result = builder.toString();
                    LogUtil.d("Getting response for auction " + getAuctionId() + ": " + result);
                    Pattern storedRequestNotFound = Pattern.compile("^Invalid request: Stored Request with ID=\".*\" not found.");
                    Pattern storedImpNotFound = Pattern.compile("^Invalid request: Stored Imp with ID=\".*\" not found.");
                    Pattern invalidBannerSize = Pattern.compile("^Invalid request: Request imp\\[\\d\\].banner.format\\[\\d\\] must define non-zero \"h\" and \"w\" properties.");
                    Pattern invalidInterstitialSize = Pattern.compile("Invalid request: Unable to set interstitial size list");
                    Matcher m = storedRequestNotFound.matcher(result);
                    Matcher m2 = invalidBannerSize.matcher(result);
                    Matcher m3 = storedImpNotFound.matcher(result);
                    Matcher m4 = invalidInterstitialSize.matcher(result);
                    if (m.find() || result.contains("No stored request")) {
                        return new AsyncTaskResult<>(ResultCode.INVALID_ACCOUNT_ID);
                    } else if (m3.find() || result.contains("No stored imp")) {
                        return new AsyncTaskResult<>(ResultCode.INVALID_CONFIG_ID);
                    } else if (m2.find() || m4.find() || result.contains("Request imp[0].banner.format")) {
                        return new AsyncTaskResult<>(ResultCode.INVALID_SIZE);
                    } else {
                        return new AsyncTaskResult<>(ResultCode.PREBID_SERVER_ERROR);
                    }
                }

            } catch (MalformedURLException e) {
                return new AsyncTaskResult<>(e);
            } catch (UnsupportedEncodingException e) {
                return new AsyncTaskResult<>(e);
            } catch (SocketTimeoutException ex) {
                return new AsyncTaskResult<>(ResultCode.TIMEOUT);
            } catch (IOException e) {
                return new AsyncTaskResult<>(e);
            } catch (JSONException e) {
                return new AsyncTaskResult<>(e);
            } catch (NoContextException ex) {
                return new AsyncTaskResult<>(ResultCode.INVALID_CONTEXT);
            } catch (Exception e) {
                return new AsyncTaskResult<>(e);
            }
            return new AsyncTaskResult<>(new RuntimeException("ServerConnector exception"));
        }

        @Override
        @MainThread
        protected void onPostExecute(AsyncTaskResult<JSONObject> asyncTaskResult) {
            super.onPostExecute(asyncTaskResult);

            timeoutCountDownTimer.cancel();

            if (asyncTaskResult.getError() != null) {
                asyncTaskResult.getError().printStackTrace();

                //Default error
                notifyDemandFailed(ResultCode.PREBID_SERVER_ERROR);

                removeThisTask();
                return;
            } else if (asyncTaskResult.getResultCode() != null) {
                notifyDemandFailed(asyncTaskResult.getResultCode());

                removeThisTask();
                return;
            }

            JSONObject jsonObject = asyncTaskResult.getResult();

            HashMap<String, String> keywords = new HashMap<>();
            boolean containTopBid = false;
            if (jsonObject != null) {
                LogUtil.d("Getting response for auction " + getAuctionId() + ": " + jsonObject.toString());
                try {
                    JSONArray seatbid = jsonObject.getJSONArray("seatbid");
                    if (seatbid != null) {
                        for (int i = 0; i < seatbid.length(); i++) {
                            JSONObject seat = seatbid.getJSONObject(i);
                            JSONArray bids = seat.getJSONArray("bid");
                            if (bids != null) {
                                for (int j = 0; j < bids.length(); j++) {
                                    JSONObject bid = bids.getJSONObject(j);
                                    JSONObject hb_key_values = null;
                                    try {
                                        hb_key_values = bid.getJSONObject("ext").getJSONObject("prebid").getJSONObject("targeting");
                                    } catch (JSONException e) {
                                        // this can happen if lower bids exist on the same seat
                                    }
                                    if (hb_key_values != null) {
                                        Iterator it = hb_key_values.keys();
                                        boolean containBids = false;
                                        while (it.hasNext()) {
                                            String key = (String) it.next();
                                            if (key.equals("hb_cache_id")) {
                                                containTopBid = true;
                                            }
                                            if (key.startsWith("hb_cache_id")) {
                                                containBids = true;
                                            }
                                        }
                                        it = hb_key_values.keys();
                                        if (containBids) {
                                            while (it.hasNext()) {
                                                String key = (String) it.next();
                                                keywords.put(key, hb_key_values.getString(key));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (JSONException e) {
                    LogUtil.e("Error processing JSON response.");
                }
            }

            if (!keywords.isEmpty() && containTopBid) {
                notifyDemandReady(keywords);
            } else {
                notifyDemandFailed(ResultCode.NO_BIDS);
            }

            removeThisTask();
        }

        @Override
        @MainThread
        protected void onCancelled() {
            super.onCancelled();

            if (timeoutFired) {
                notifyDemandFailed(ResultCode.TIMEOUT);
            } else {
                timeoutCountDownTimer.cancel();
            }
            removeThisTask();
        }

        private void removeThisTask() {
            @Nullable
            PrebidServerAdapter prebidServerAdapter = this.prebidServerAdapter.get();
            if (prebidServerAdapter == null) {
                return;
            }

            prebidServerAdapter.serverConnectors.remove(this);
        }

        String getAuctionId() {
            return auctionId;
        }

        void destroy() {
            this.cancel(true);
            this.listener = null;
        }

        @MainThread
        void notifyDemandReady(HashMap<String, String> keywords) {
            if (this.listener == null) {
                return;
            }

            listener.onDemandReady(keywords, getAuctionId());
        }

        @MainThread
        void notifyDemandFailed(ResultCode code) {
            if (this.listener == null) {
                return;
            }

            listener.onDemandFailed(code, getAuctionId());
        }

        private String getHost() {
            return PrebidMobile.getPrebidServerHost().getHostUrl();
        }

        /**
         * Synchronize the uuid2 cookie to the Webview Cookie Jar
         * This is only done if there is no present cookie.
         *
         * @param headers headers to extract cookies from for syncing
         */
        @SuppressWarnings("deprecation")
        private void httpCookieSync(Map<String, List<String>> headers) {
            if (headers == null || headers.isEmpty()) return;
            CookieManager cm = CookieManager.getInstance();
            if (cm == null) {
                LogUtil.i("PrebidNewAPI", "Unable to find a CookieManager");
                return;
            }
            try {
                String existingUUID = getExistingCookie();

                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    String key = entry.getKey();
                    // Only "Set-cookie" and "Set-cookie2" pair will be parsed
                    if (key != null && (key.equalsIgnoreCase(PrebidServerSettings.VERSION_ZERO_HEADER)
                            || key.equalsIgnoreCase(PrebidServerSettings.VERSION_ONE_HEADER))) {
                        for (String cookieStr : entry.getValue()) {
                            if (!TextUtils.isEmpty(cookieStr) && cookieStr.contains(PrebidServerSettings.AN_UUID)) {
                                // pass uuid2 to WebView Cookie jar if it's empty or outdated
                                if (existingUUID == null || !cookieStr.contains(existingUUID)) {
                                    cm.setCookie(PrebidServerSettings.COOKIE_DOMAIN, cookieStr);
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                                        // CookieSyncManager is deprecated in API 21 Lollipop
                                        CookieSyncManager.createInstance(PrebidMobile.getApplicationContext());
                                        CookieSyncManager csm = CookieSyncManager.getInstance();
                                        if (csm == null) {
                                            LogUtil.i("Unable to find a CookieSyncManager");
                                            return;
                                        }
                                        csm.sync();
                                    } else {
                                        cm.flush();
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IllegalStateException ise) {
            } catch (Exception e) {
            }
        }

        private String getExistingCookie() {
            try {
                CookieSyncManager.createInstance(PrebidMobile.getApplicationContext());
                CookieManager cm = CookieManager.getInstance();
                if (cm != null) {
                    String wvcookie = cm.getCookie(PrebidServerSettings.COOKIE_DOMAIN);
                    if (!TextUtils.isEmpty(wvcookie)) {
                        String[] existingCookies = wvcookie.split("; ");
                        for (String cookie : existingCookies) {
                            if (cookie != null && cookie.contains(PrebidServerSettings.AN_UUID)) {
                                return cookie;
                            }
                        }
                    }
                }
            } catch (Exception e) {
            }
            return null;
        }


        private JSONObject getPostData() throws NoContextException {
            Context context = PrebidMobile.getApplicationContext();
            if (context != null) {
                AdvertisingIDUtil.retrieveAndSetAAID(context);
                PrebidServerSettings.update(context);
            }
            JSONObject postData = new JSONObject();
            try {
                String id = UUID.randomUUID().toString();
                postData.put("id", id);
                JSONObject source = new JSONObject();
                source.put("tid", id);
                postData.put("source", source);
                // add ad units
                JSONArray imp = getImp();
                if (imp != null && imp.length() > 0) {
                    postData.put("imp", imp);
                }
                // add device
                JSONObject device = getDeviceObject();
                if (device != null && device.length() > 0) {
                    postData.put(PrebidServerSettings.REQUEST_DEVICE, device);
                }
                // add app
                JSONObject app = getAppObject();
                if (device != null && device.length() > 0) {
                    postData.put(PrebidServerSettings.REQUEST_APP, app);
                }
                // add user
                JSONObject user = getUserObject();
                if (user != null && user.length() > 0) {
                    postData.put(PrebidServerSettings.REQUEST_USER, user);
                }
                // add regs
                JSONObject regs = getRegsObject();
                if (regs != null && regs.length() > 0) {
                    postData.put("regs", regs);
                }
                // add targeting keywords request
                JSONObject ext = getRequestExtData();
                if (ext != null && ext.length() > 0) {
                    postData.put("ext", ext);
                }
            } catch (JSONException e) {
            }
            return postData;
        }

        private JSONObject getRequestExtData() {
            JSONObject ext = new JSONObject();
            JSONObject prebid = new JSONObject();
            try {
                JSONObject cache = new JSONObject();
                JSONObject bids = new JSONObject();
                cache.put("bids", bids);
                prebid.put("cache", cache);
                JSONObject storedRequest = new JSONObject();
                storedRequest.put("id", PrebidMobile.getPrebidServerAccountId());
                prebid.put("storedrequest", storedRequest);
                JSONObject targetingEmpty = new JSONObject();
                prebid.put("targeting", targetingEmpty);
                ext.put("prebid", prebid);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return ext;
        }

        private JSONArray getImp() throws NoContextException {
            JSONArray impConfigs = new JSONArray();
            // takes information from the ad units
            // look up the configuration of the ad unit
            try {
                JSONObject imp = new JSONObject();
                JSONObject ext = new JSONObject();
                imp.put("id", "PrebidMobile");
                imp.put("secure", 1);
                if (requestParams.getAdType().equals(AdType.INTERSTITIAL)) {
                    imp.put("instl", 1);
                    JSONObject banner = new JSONObject();
                    JSONArray format = new JSONArray();
                    Context context = PrebidMobile.getApplicationContext();
                    if (context != null) {
                        format.put(new JSONObject().put("w", context.getResources().getConfiguration().screenWidthDp).put("h", context.getResources().getConfiguration().screenHeightDp));
                    } else {
                        // Unlikely this is being called, if so, please check if you've set up the SDK properly
                        throw new NoContextException();
                    }
                    banner.put("format", format);
                    imp.put("banner", banner);
                } else if (requestParams.getAdType().equals(AdType.BANNER)) {
                    JSONObject banner = new JSONObject();
                    JSONArray format = new JSONArray();
                    for (AdSize size : requestParams.getAdSizes()) {
                        format.put(new JSONObject().put("w", size.getWidth()).put("h", size.getHeight()));
                    }
                    banner.put("format", format);
                    imp.put("banner", banner);
                } else {
                    // add native request
                    JSONObject nativeObj = new JSONObject();
                    JSONObject request = new JSONObject();
                    JSONArray assets = new JSONArray();
                    HashMap<NativeAdUnit.NATIVE_REQUEST_ASSET, HashMap<String, Object>> assetsParams = requestParams.getNativeRequestAssets();
                    if (assetsParams != null) {
                        for (NativeAdUnit.NATIVE_REQUEST_ASSET asset : assetsParams.keySet()) {
                            JSONObject assetObj = new JSONObject();
                            switch (asset) {
                                case TITLE:
                                    JSONObject title = new JSONObject();
                                    HashMap<String, Object> titleParams = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.TITLE);
                                    title.put("len", titleParams.get("len"));
                                    assetObj.put("title", title);
                                    assetObj.put("required", (Boolean) titleParams.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case IMAGE_TYPE_1_ICON:
                                    HashMap<String, Object> iconParams = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.IMAGE_TYPE_1_ICON);
                                    JSONObject iconImg = new JSONObject();
                                    iconImg.put("type", 1);
                                    if ((Integer) iconParams.get("wmin") > 0 && (Integer) iconParams.get("hmin") > 0) {
                                        iconImg.put("wmin", iconParams.get("wmin"));
                                        iconImg.put("hmin", iconParams.get("hmin"));
                                    }
                                    if ((Integer) iconParams.get("w") > 0 && (Integer) iconParams.get("h") > 0) {
                                        iconImg.put("w", iconParams.get("w"));
                                        iconImg.put("h", iconParams.get("h"));
                                    }
                                    if (iconParams.get("mimes") != null) {
                                        ArrayList<String> mimes = (ArrayList) iconParams.get("mimes");
                                        JSONArray iconMimesArray = new JSONArray();
                                        for (String mime : mimes) {
                                            iconMimesArray.put(mime);
                                        }
                                        if (iconMimesArray.length() != 0) {
                                            iconImg.put("mimes", iconMimesArray);
                                        }
                                    }
                                    assetObj.put("img", iconImg);
                                    assetObj.put("required", (Boolean) iconParams.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case IMAGE_TYPE_3_MAIN:
                                    HashMap<String, Object> imageParams = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.IMAGE_TYPE_3_MAIN);
                                    JSONObject image = new JSONObject();
                                    image.put("type", 3);
                                    if ((Integer) imageParams.get("wmin") > 0 && (Integer) imageParams.get("hmin") > 0) {
                                        image.put("wmin", imageParams.get("wmin"));
                                        image.put("hmin", imageParams.get("hmin"));
                                    }
                                    if ((Integer) imageParams.get("w") > 0 && (Integer) imageParams.get("h") > 0) {
                                        image.put("w", imageParams.get("w"));
                                        image.put("h", imageParams.get("h"));
                                    }
                                    if (imageParams.get("mimes") != null) {
                                        ArrayList<String> mimes = (ArrayList) imageParams.get("mimes");
                                        JSONArray imageMimesArray = new JSONArray();
                                        for (String mime : mimes) {
                                            imageMimesArray.put(mime);
                                        }
                                        if (imageMimesArray.length() != 0) {
                                            image.put("mimes", imageMimesArray);
                                        }
                                    }
                                    assetObj.put("img", image);
                                    assetObj.put("required", (Boolean) imageParams.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_1_SPONSORED:
                                    HashMap<String, Object> data1Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_1_SPONSORED);
                                    JSONObject data1 = new JSONObject();
                                    data1.put("type", 1);
                                    data1.put("len", data1Params.get("len"));
                                    assetObj.put("data", data1);
                                    assetObj.put("required", (Boolean) data1Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_2_DESCRIPTION:
                                    HashMap<String, Object> data2Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_2_DESCRIPTION);
                                    JSONObject data2 = new JSONObject();
                                    data2.put("type", 2);
                                    data2.put("len", data2Params.get("len"));
                                    assetObj.put("data", data2);
                                    assetObj.put("required", (Boolean) data2Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_3_RATING:
                                    HashMap<String, Object> data3Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_3_RATING);
                                    JSONObject data3 = new JSONObject();
                                    data3.put("type", 3);
                                    data3.put("len", data3Params.get("len"));
                                    assetObj.put("data", data3);
                                    assetObj.put("required", (Boolean) data3Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_4_LIKES:
                                    HashMap<String, Object> data4Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_4_LIKES);
                                    JSONObject data4 = new JSONObject();
                                    data4.put("type", 4);
                                    data4.put("len", data4Params.get("len"));
                                    assetObj.put("data", data4);
                                    assetObj.put("required", (Boolean) data4Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_5_DOWNLOADS:
                                    HashMap<String, Object> data5Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_5_DOWNLOADS);
                                    JSONObject data5 = new JSONObject();
                                    data5.put("type", 5);
                                    data5.put("len", data5Params.get("len"));
                                    assetObj.put("data", data5);
                                    assetObj.put("required", (Boolean) data5Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_6_PRICE:
                                    HashMap<String, Object> data6Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_6_PRICE);
                                    JSONObject data6 = new JSONObject();
                                    data6.put("type", 6);
                                    data6.put("len", data6Params.get("len"));
                                    assetObj.put("data", data6);
                                    assetObj.put("required", (Boolean) data6Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_7_SALE_PRICE:
                                    HashMap<String, Object> data7Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_7_SALE_PRICE);
                                    JSONObject data7 = new JSONObject();
                                    data7.put("type", 7);
                                    data7.put("len", data7Params.get("len"));
                                    assetObj.put("data", data7);
                                    assetObj.put("required", (Boolean) data7Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_8_PHONE:
                                    HashMap<String, Object> data8Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_8_PHONE);
                                    JSONObject data8 = new JSONObject();
                                    data8.put("type", 8);
                                    data8.put("len", data8Params.get("len"));
                                    assetObj.put("data", data8);
                                    assetObj.put("required", (Boolean) data8Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_9_ADDRESS:
                                    HashMap<String, Object> data9Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_9_ADDRESS);
                                    JSONObject data9 = new JSONObject();
                                    data9.put("type", 9);
                                    data9.put("len", data9Params.get("len"));
                                    assetObj.put("data", data9);
                                    assetObj.put("required", (Boolean) data9Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_10_ADDITIONAL_DESCRIPTION:
                                    HashMap<String, Object> data10Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_10_ADDITIONAL_DESCRIPTION);
                                    JSONObject data10 = new JSONObject();
                                    data10.put("type", 10);
                                    data10.put("len", data10Params.get("len"));
                                    assetObj.put("data", data10);
                                    assetObj.put("required", (Boolean) data10Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_11_DISPLAY_URL:
                                    HashMap<String, Object> data11Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_11_DISPLAY_URL);
                                    JSONObject data11 = new JSONObject();
                                    data11.put("type", 11);
                                    data11.put("len", data11Params.get("len"));
                                    assetObj.put("data", data11);
                                    assetObj.put("required", (Boolean) data11Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                                case DATA_TYPE_12_CTA_TEXT:
                                    HashMap<String, Object> data12Params = assetsParams.get(NativeAdUnit.NATIVE_REQUEST_ASSET.DATA_TYPE_12_CTA_TEXT);
                                    JSONObject data12 = new JSONObject();
                                    data12.put("type", 12);
                                    data12.put("len", data12Params.get("len"));
                                    assetObj.put("data", data12);
                                    assetObj.put("required", (Boolean) data12Params.get("required") ? 1 : 0);
                                    assets.put(assetObj);
                                    break;
                            }

                        }
                    }
                    request.put("assets", assets);
                    request.put("context", 2);
                    request.put("contextsubtype", 20);
                    request.put("plcmttype", 1);
                    switch (requestParams.getNativeRequestVersion()) {
                        case VERSION_1_1:
                            request.put("ver", "1.1");
                            nativeObj.put("request", request.toString());
                            nativeObj.put("ver", "1.1");
                            break;
                        case VERSION_1_2:
                            request.put("ver", "1.2");
                            nativeObj.put("request", request.toString());
                            nativeObj.put("ver", "1.2");
                    }
                    imp.put("native", nativeObj);
                }
                imp.put("ext", ext);
                JSONObject prebid = new JSONObject();
                ext.put("prebid", prebid);
                JSONObject storedrequest = new JSONObject();
                prebid.put("storedrequest", storedrequest);
                storedrequest.put("id", requestParams.getConfigId());
                imp.put("ext", ext);

                impConfigs.put(imp);
            } catch (JSONException e) {
            }

            return impConfigs;
        }

        private JSONObject getDeviceObject() {
            JSONObject device = new JSONObject();
            try {
                // Device make
                if (!TextUtils.isEmpty(PrebidServerSettings.deviceMake))
                    device.put(PrebidServerSettings.REQUEST_DEVICE_MAKE, PrebidServerSettings.deviceMake);
                // Device model
                if (!TextUtils.isEmpty(PrebidServerSettings.deviceModel))
                    device.put(PrebidServerSettings.REQUEST_DEVICE_MODEL, PrebidServerSettings.deviceModel);
                // Default User Agent
                if (!TextUtils.isEmpty(PrebidServerSettings.userAgent)) {
                    device.put(PrebidServerSettings.REQUEST_USERAGENT, PrebidServerSettings.userAgent);
                }
                // limited ad tracking
                device.put(PrebidServerSettings.REQUEST_LMT, AdvertisingIDUtil.isLimitAdTracking() ? 1 : 0);
                if (!AdvertisingIDUtil.isLimitAdTracking() && !TextUtils.isEmpty(AdvertisingIDUtil.getAAID())) {
                    // put ifa
                    device.put(PrebidServerSettings.REQUEST_IFA, AdvertisingIDUtil.getAAID());
                }

                // os
                device.put(PrebidServerSettings.REQUEST_OS, PrebidServerSettings.os);
                device.put(PrebidServerSettings.REQUEST_OS_VERSION, String.valueOf(Build.VERSION.SDK_INT));
                // language
                if (!TextUtils.isEmpty(Locale.getDefault().getLanguage())) {
                    device.put(PrebidServerSettings.REQUEST_LANGUAGE, Locale.getDefault().getLanguage());
                }
                // POST data that requires context
                Context context = PrebidMobile.getApplicationContext();
                if (context != null) {
                    device.put(PrebidServerSettings.REQUEST_DEVICE_WIDTH, context.getResources().getConfiguration().screenWidthDp);
                    device.put(PrebidServerSettings.REQUEST_DEVICE_HEIGHT, context.getResources().getConfiguration().screenHeightDp);

                    device.put(PrebidServerSettings.REQUEST_DEVICE_PIXEL_RATIO, context.getResources().getDisplayMetrics().density);

                    TelephonyManager telephonyManager = (TelephonyManager) context
                            .getSystemService(Context.TELEPHONY_SERVICE);
                    // Get mobile country codes
                    if (PrebidServerSettings.getMCC() < 0 || PrebidServerSettings.getMNC() < 0) {
                        String networkOperator = telephonyManager.getNetworkOperator();
                        if (!TextUtils.isEmpty(networkOperator)) {
                            try {
                                PrebidServerSettings.setMCC(Integer.parseInt(networkOperator.substring(0, 3)));
                                PrebidServerSettings.setMNC(Integer.parseInt(networkOperator.substring(3)));
                            } catch (Exception e) {
                                // Catches NumberFormatException and StringIndexOutOfBoundsException
                                PrebidServerSettings.setMCC(-1);
                                PrebidServerSettings.setMNC(-1);
                            }
                        }
                    }
                    if (PrebidServerSettings.getMCC() > 0 && PrebidServerSettings.getMNC() > 0) {
                        device.put(PrebidServerSettings.REQUEST_MCC_MNC, String.format(Locale.ENGLISH, "%d-%d", PrebidServerSettings.getMCC(), PrebidServerSettings.getMNC()));
                    }

                    // Get carrier
                    if (PrebidServerSettings.getCarrierName() == null) {
                        try {
                            PrebidServerSettings.setCarrierName(telephonyManager.getNetworkOperatorName());
                        } catch (SecurityException ex) {
                            // Some phones require READ_PHONE_STATE permission just ignore name
                            PrebidServerSettings.setCarrierName("");
                        }
                    }
                    if (!TextUtils.isEmpty(PrebidServerSettings.getCarrierName()))
                        device.put(PrebidServerSettings.REQUEST_CARRIER, PrebidServerSettings.getCarrierName());

                    // check connection type
                    int connection_type = 0;
                    ConnectivityManager cm = (ConnectivityManager) context
                            .getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                    if (activeNetwork != null && activeNetwork.isConnected()) {
                        NetworkInfo wifi = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                        if (wifi != null) {
                            connection_type = wifi.isConnected() ? 1 : 2;
                        }
                    }
                    device.put(PrebidServerSettings.REQUEST_CONNECTION_TYPE, connection_type);

                    // get location
                    // Do we have access to location?
                    if (PrebidMobile.isShareGeoLocation()) {
                        // get available location through Android LocationManager
                        if (context.checkCallingOrSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED
                                || context.checkCallingOrSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED) {
                            Location lastLocation = null;
                            LocationManager lm = (LocationManager) context
                                    .getSystemService(Context.LOCATION_SERVICE);

                            for (String provider_name : lm.getProviders(true)) {
                                Location l = lm.getLastKnownLocation(provider_name);
                                if (l == null) {
                                    continue;
                                }

                                if (lastLocation == null) {
                                    lastLocation = l;
                                } else {
                                    if (l.getTime() > 0 && lastLocation.getTime() > 0) {
                                        if (l.getTime() > lastLocation.getTime()) {
                                            lastLocation = l;
                                        }
                                    }
                                }
                            }
                            JSONObject geo = new JSONObject();
                            if (lastLocation != null) {
                                Double lat = lastLocation.getLatitude();
                                Double lon = lastLocation.getLongitude();
                                geo.put(PrebidServerSettings.REQEUST_GEO_LAT, lat);
                                geo.put(PrebidServerSettings.REQUEST_GEO_LON, lon);
                                Integer locDataPrecision = Math.round(lastLocation.getAccuracy());
                                //Don't report location data from the future
                                Integer locDataAge = (int) Math.max(0, (System.currentTimeMillis() - lastLocation.getTime()));
                                geo.put(PrebidServerSettings.REQUEST_GEO_AGE, locDataAge);
                                geo.put(PrebidServerSettings.REQUEST_GEO_ACCURACY, locDataPrecision);
                                device.put(PrebidServerSettings.REQUEST_GEO, geo);
                            }
                        } else {
                            LogUtil.w("Location permissions ACCESS_COARSE_LOCATION and/or ACCESS_FINE_LOCATION aren\\'t set in the host app. This may affect demand.");
                        }
                    }
                }
            } catch (JSONException e) {
                LogUtil.d("PrebidServerAdapter getDeviceObject() " + e.getMessage());
            }
            return device;
        }

        private JSONObject getAppObject() {
            JSONObject app = new JSONObject();
            try {
                if (!TextUtils.isEmpty(TargetingParams.getBundleName())) {
                    app.put("bundle", TargetingParams.getBundleName());
                }
                if (!TextUtils.isEmpty(PrebidServerSettings.pkgVersion)) {
                    app.put("ver", PrebidServerSettings.pkgVersion);
                }
                if (!TextUtils.isEmpty(PrebidServerSettings.appName)) {
                    app.put("name", PrebidServerSettings.appName);
                }
                if (!TextUtils.isEmpty(TargetingParams.getDomain())) {
                    app.put("domain", TargetingParams.getDomain());
                }
                if (!TextUtils.isEmpty(TargetingParams.getStoreUrl())) {
                    app.put("storeurl", TargetingParams.getStoreUrl());
                }
                JSONObject publisher = new JSONObject();
                publisher.put("id", PrebidMobile.getPrebidServerAccountId());
                app.put("publisher", publisher);
                JSONObject prebid = new JSONObject();
                prebid.put("source", "prebid-mobile");
                prebid.put("version", PrebidServerSettings.sdk_version);
                JSONObject ext = new JSONObject();
                ext.put("prebid", prebid);
                app.put("ext", ext);
            } catch (JSONException e) {
                LogUtil.d("PrebidServerAdapter getAppObject() " + e.getMessage());
            }
            return app;

        }

        private JSONObject getUserObject() {
            JSONObject user = new JSONObject();
            try {
                if (TargetingParams.getYearOfBirth() > 0) {
                    user.put("yob", TargetingParams.getYearOfBirth());
                }
                TargetingParams.GENDER gender = TargetingParams.getGender();
                String g = "O";
                switch (gender) {
                    case FEMALE:
                        g = "F";
                        break;
                    case MALE:
                        g = "M";
                        break;
                    case UNKNOWN:
                        g = "O";
                        break;
                }
                user.put("gender", g);
                StringBuilder builder = new StringBuilder();
                ArrayList<String> keywords = this.requestParams.getKeywords();
                for (String key : keywords) {
                    builder.append(key).append(",");
                }
                String finalKeywords = builder.toString();
                if (!TextUtils.isEmpty(finalKeywords)) {
                    user.put("keywords", finalKeywords);
                }
                if (TargetingParams.isSubjectToGDPR() != null) {
                    JSONObject ext = new JSONObject();
                    ext.put("consent", TargetingParams.getGDPRConsentString());
                    user.put("ext", ext);
                }
            } catch (JSONException e) {
                LogUtil.d("PrebidServerAdapter getUserObject() " + e.getMessage());
            }
            return user;
        }

        private JSONObject getRegsObject() {
            JSONObject regs = new JSONObject();
            try {
                JSONObject ext = new JSONObject();
                Boolean isSubjectToGDPR = TargetingParams.isSubjectToGDPR();

                if (isSubjectToGDPR != null && isSubjectToGDPR) {
                    ext.put("gdpr", 1);
                    regs.put("ext", ext);
                }

                if (TargetingParams.isSubjectToCOPPA()) {
                    regs.put("coppa", 1);
                }

            } catch (JSONException e) {
                LogUtil.d("PrebidServerAdapter getRegsObject() " + e.getMessage());
            }
            return regs;
        }

        private static class NoContextException extends Exception {
        }

        private static class AsyncTaskResult<T> {
            @Nullable
            private T result;
            @Nullable
            private ResultCode resultCode;
            @Nullable
            private Exception error;

            @Nullable
            public T getResult() {
                return result;
            }

            @Nullable
            public ResultCode getResultCode() {
                return resultCode;
            }

            @Nullable
            public Exception getError() {
                return error;
            }

            private AsyncTaskResult(@NonNull T result) {
                this.result = result;
            }

            private AsyncTaskResult(@NonNull ResultCode resultCode) {
                this.resultCode = resultCode;
            }

            private AsyncTaskResult(@NonNull Exception error) {
                this.error = error;
            }
        }

        class TimeoutCountDownTimer extends CountDownTimer {

            /**
             * @param millisInFuture    The number of millis in the future from the call
             *                          to {@link #start()} until the countdown is done and {@link #onFinish()}
             *                          is called.
             * @param countDownInterval The interval along the way to receive
             *                          {@link #onTick(long)} callbacks.
             */
            public TimeoutCountDownTimer(long millisInFuture, long countDownInterval) {
                super(millisInFuture, countDownInterval);

            }

            @Override
            public void onTick(long millisUntilFinished) {
                if (ServerConnector.this.isCancelled()) {
                    TimeoutCountDownTimer.this.cancel();
                }
            }

            @Override
            public void onFinish() {

                if (ServerConnector.this.isCancelled()) {
                    return;
                }

                timeoutFired = true;
                ServerConnector.this.cancel(true);

            }
        }
    }
}
