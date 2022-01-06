/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2020-21 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.Purchase.PurchaseState;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.emanuelef.remote_capture.model.SkusAvailability;

import java.util.List;

public class PlayBilling extends Billing implements BillingClientStateListener, PurchasesUpdatedListener, SkuDetailsResponseListener {
    public static final String TAG = "PlayBilling";
    private final Handler mHandler;
    private final ArrayMap<String, SkuDetails> mDetails;
    private final ArrayMap<String, String> mSkuToPurchToken;
    private BillingClient mBillingClient;
    private PurchaseReadyListener mListener;
    private boolean mWaitingStart;
    private static boolean mPendingNoticeShown = false; // static to make it work across the app
    private final SkusAvailability mAvailability;

    /** setPurchaseListener() -> connectBilling() -> PurchaseReadyListener.onPurchasesReady()
     *   -> the client can now call purchase
     *   -> PurchaseReadyListener.onSKUStateUpdate() may be called in the future
     *
     *  Clear billing cache: adb shell pm clear com.android.vending
     */
    public interface PurchaseReadyListener {
        void onPurchasesReady();
        void onPurchasesError();
        void onSKUStateUpdate(String sku, int state);
    }

    public PlayBilling(Context ctx) {
        super(ctx);
        mHandler = new Handler(Looper.getMainLooper());
        mDetails = new ArrayMap<>();
        mSkuToPurchToken = new ArrayMap<>();
        mAvailability = SkusAvailability.load(mPrefs);
        mWaitingStart = false;
    }

    public static String purchstate2Str(int state) {
        switch (state) {
            case PurchaseState.PENDING: return "PENDING";
            case PurchaseState.PURCHASED: return "PURCHASED";
            case PurchaseState.UNSPECIFIED_STATE: return "UNSPECIFIED";
        }
        return "UNKNOWN";
    }

    private void processPurchases(BillingResult billingResult, @Nullable List<Purchase> purchases) {
        if((billingResult.getResponseCode() == BillingResponseCode.OK) && (purchases != null)) {
            ArraySet<String> purchased = new ArraySet<>();
            boolean show_toast = true;
            mSkuToPurchToken.clear();

            for(Purchase purchase : purchases) {
                boolean newPurchase = false;

                for(String sku: purchase.getSkus()) {
                    Log.d(TAG, "\tPurchase: " + sku + " -> " + purchstate2Str(purchase.getPurchaseState()));

                    switch (purchase.getPurchaseState()) {
                        case PurchaseState.PENDING:
                            if(!mPendingNoticeShown) {
                                mHandler.post(() -> Utils.showToastLong(mContext, R.string.pending_transaction));
                                mPendingNoticeShown = true;
                            }

                            if(!mWaitingStart)
                                // NOTE: using mHandler.post because otherwise any exceptions are caught (and hidden) by the billing library!
                                mHandler.post(() -> {
                                    if(mListener != null)
                                        mListener.onSKUStateUpdate(sku, PurchaseState.PENDING);
                                });
                            break;
                        case PurchaseState.PURCHASED:
                            if(!isPurchased(sku) && setPurchased(sku, true)) {
                                newPurchase = true;
                                Log.d(TAG, "New purchase: " + sku);

                                if(show_toast) {
                                    mHandler.post(() -> Utils.showToastLong(mContext, R.string.purchased_feature_ok));
                                    show_toast = false;
                                }

                                if(!mWaitingStart) {
                                    mHandler.post(() -> {
                                        if (mListener != null)
                                            mListener.onSKUStateUpdate(sku, PurchaseState.PURCHASED);
                                    });
                                }
                            }

                            mSkuToPurchToken.put(sku, purchase.getPurchaseToken());
                            purchased.add(sku);
                            break;
                        case PurchaseState.UNSPECIFIED_STATE:
                            break;
                    }
                }

                if (newPurchase && !purchase.isAcknowledged()) {
                    Log.d(TAG, "Calling acknowledgePurchase on order " + purchase.getOrderId());

                    AcknowledgePurchaseParams acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.getPurchaseToken())
                            .build();

                    mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, billingResult1 ->
                            Log.d(TAG, "acknowledgePurchase: " + billingResult1.getResponseCode() + " " + billingResult1.getDebugMessage()));
                }
            }

            // Check for voided purchases (e.g. due to a refund)
            for(String sku: ALL_SKUS) {
                if(!purchased.contains(sku) && isPurchased(sku)) {
                    Log.w(TAG, "Previously purchased SKU " + sku + " was voided");

                    if(setPurchased(sku, false) && !mWaitingStart)
                        mHandler.post(() -> {
                            if(mListener != null)
                                mListener.onSKUStateUpdate(sku, PurchaseState.UNSPECIFIED_STATE);
                        });
                }
            }
        }

        if(mWaitingStart) {
            if(billingResult.getResponseCode() == BillingResponseCode.OK)
                mHandler.post(() -> {
                    if(mListener != null)
                        mListener.onPurchasesReady();
                });
            else
                onPurchasesError(billingResult);

            mWaitingStart = false;
        }
    }

    private void onPurchasesError(@NonNull BillingResult billingResult) {
        Log.e(TAG, "Billing returned error " + billingResult + ", disconnecting");

        mHandler.post(() -> {
            if(mListener != null)
                mListener.onPurchasesError();
        });

        disconnectBilling();
    }

    @Override
    public void onSkuDetailsResponse(@NonNull BillingResult billingResult, @Nullable List<SkuDetails> list) {
        Log.d(TAG, "onSkuDetailsResponse: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());

        if((billingResult.getResponseCode() == BillingResponseCode.OK) && (list != null)) {
            mAvailability.update(list, mPrefs);
            Log.d(TAG, "Num available SKUs: " + list.size());

            mDetails.clear();
            for(SkuDetails sku: list) {
                //Log.d(TAG, "Available: " + sku);
                mDetails.put(sku.getSku(), sku);
            }

            mBillingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP, (billingResult1, purchases) -> {
                Log.d(TAG, "queryPurchasesAsync: " + billingResult1.getResponseCode() + " " + billingResult1.getDebugMessage());
                processPurchases(billingResult1, purchases);
            });
        } else
            onPurchasesError(billingResult);
    }

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
        Log.d(TAG, "onBillingSetupFinished: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());

        if(billingResult.getResponseCode() ==  BillingResponseCode.OK) {
            SkuDetailsParams.Builder builder = SkuDetailsParams.newBuilder()
                    .setType(BillingClient.SkuType.INAPP)
                    .setSkusList(ALL_SKUS);

            mBillingClient.querySkuDetailsAsync(builder.build(), this);
        } else
            onPurchasesError(billingResult);
    }

    @Override
    public void onBillingServiceDisconnected() {
        Log.w(TAG, "onBillingServiceDisconnected");

        // Reconnect
        mHandler.postDelayed(() -> {
            if(mBillingClient != null)
                mBillingClient.startConnection(PlayBilling.this);
        }, 5000);
    }

    public void setPurchaseReadyListener(PurchaseReadyListener listener) {
        mListener = listener;
    }

    /*
     * connectBilling -> onBillingSetupFinished -> querySkuDetailsAsync -> queryPurchasesAsync -> processPurchases
     * Starts the connection to Google Play.
     * IMPORTANT: the client must call disconnectBilling to prevent leaks
     * */
    @Override
    public void connectBilling() {
        mWaitingStart = true;

        if(mBillingClient != null)
            return;

        mBillingClient = BillingClient.newBuilder(mContext)
                .setListener(this)
                .enablePendingPurchases()
                .build();

        // Will call onBillingSetupFinished when ready
        mBillingClient.startConnection(this);
    }

    @Override
    public void disconnectBilling() {
        // Use post to avoid unsetting one of the variables below while a client is working on them
        mHandler.post(() -> {
            if(mBillingClient != null) {
                mBillingClient.endConnection();
                mBillingClient = null;
            }
        });
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<Purchase> purchases) {
        Log.d(TAG, "onPurchasesUpdated: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());
        processPurchases(billingResult, purchases);
    }

    /* For testing purposes */
    public void consumePurchase(String sku) {
        String token = mSkuToPurchToken.get(sku);
        if(token == null) {
            mHandler.post(() ->Toast.makeText(mContext, "Purchase token not found", Toast.LENGTH_SHORT).show());
            return;
        }

        mBillingClient.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build(), (billingResult, s) ->
                Log.d(TAG, "consumeAsync response: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage()));
    }

    private String sku2pref(String sku) {
        return "SKU:" + sku;
    }

    @Override
    public boolean isRedeemed(String sku) {
        if(isPurchased(SUPPORTER_SKU))
            return true;

        if(sku.equals(NO_ADS_SKU)) {
            // If the user purchases any feature, then remove ads
            for(String other_sku: ALL_SKUS)
                if(isPurchased(other_sku))
                    return true;
            return false;
        }

        return isPurchased(sku);
    }

    @Override
    public boolean isAvailable(String sku) {
        // mAvailability acts as a persistent cache that can be used before the billing connection
        // is established
        return mAvailability.isAvailable(sku);
    }

    @Override
    public boolean isPlayStore() {
        return true;
    }

    @Override
    public void setLicense(String license) {}

    public boolean isPurchased(String sku) {
        long purchaseTime = mPrefs.getLong(sku2pref(sku), 0);
        return(purchaseTime != 0);
    }

    public boolean setPurchased(String sku, boolean purchased) {
        SharedPreferences.Editor editor = mPrefs.edit();
        String key = sku2pref(sku);

        if(purchased)
            editor.putLong(key, System.currentTimeMillis());
        else
            editor.remove(key);

        editor.apply();
        return true;
    }

    @Nullable
    public SkuDetails getSkuDetails(String sku) {
        return mDetails.get(sku);
    }

    public boolean purchase(Activity activity, String sku) {
        if((mBillingClient == null) || (!mBillingClient.isReady())) {
            mHandler.post(() -> Utils.showToast(mContext, R.string.billing_connecting));
            return false;
        }

        SkuDetails details = mDetails.get(sku);
        if(details == null) {
            mHandler.post(() -> Utils.showToast(mContext, R.string.feature_not_available));
            return false;
        }

        Log.d(TAG, "Starting purchasing SKU " + sku);

        BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                .setSkuDetails(details)
                .build();

        // will call onPurchasesUpdated when done
        mPendingNoticeShown = false;
        BillingResult res = mBillingClient.launchBillingFlow(activity, billingFlowParams);
        Log.d(TAG, "BillingFlow result: " + res.getResponseCode() + " " + res.getDebugMessage());

        return(res.getResponseCode() == BillingResponseCode.OK);
    }
}